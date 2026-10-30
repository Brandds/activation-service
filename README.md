# Serviço de ativação de produtos

Implementação do desafio Java Pleno/Sênior: recebe solicitações, impede duplicidade inclusive sob concorrência, persiste o estado e chama um provedor HTTP. Java 21, Spring Boot 3.5.7, Spring MVC, JPA/Hibernate, Flyway e Maven Wrapper.

## Como executar

### Ambiente completo (recomendado)

Pré-requisitos: Git, Docker Engine/Docker Desktop com Compose v2 e acesso à internet na primeira execução. A imagem de build já contém Java e Maven.

```bash
docker compose up --build -d
docker compose logs -f app
```

API em `http://localhost:8080`. PostgreSQL tem volume persistente e não publica uma porta no host. WireMock em `http://localhost:8081`, retornando 204 por padrão. A senha padrão do Compose serve apenas ao desenvolvimento. Para parar preservando os dados: `docker compose down`.

```bash
curl -i http://localhost:8080/api/v1/requests \
  -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -i http://localhost:8080/api/v1/requests/UUID_RETORNADO
curl http://localhost:8080/actuator/health/readiness
```

No PowerShell, use `Invoke-RestMethod` para evitar diferenças de escape do `curl`:

```powershell
$body = @{ externalId = 'EXT-12345'; customerId = 'CUST-10001'; productCode = 'INTERNET-500' } | ConvertTo-Json
$request = Invoke-RestMethod http://localhost:8080/api/v1/requests -Method Post -ContentType application/json -Body $body
Invoke-RestMethod "http://localhost:8080/api/v1/requests/$($request.id)"
```

O POST responde assim que a solicitação é persistida, normalmente em `PENDING`. Um worker consulta o banco a cada segundo e chama o provedor. O GET permite acompanhar `PROCESSING`, `COMPLETED` ou `FAILED`. Como há concorrência, um retry do POST pode receber um estado já mais avançado.

### Execução local sem Docker

Pré-requisito: JDK 21 (`JAVA_HOME` configurado). Maven é baixado pelo Wrapper na primeira execução. Em Linux/macOS, se necessário, execute `chmod +x mvnw` antes dos comandos abaixo.

```bash
./mvnw verify
./mvnw spring-boot:run
```

No Windows: ` .\mvnw.cmd verify` e ` .\mvnw.cmd spring-boot:run`. O perfil padrão usa H2 **em arquivo**, em `./data/activation`, com as mesmas migrações. O provedor continua sendo uma dependência externa; pode-se iniciar apenas o mock com `docker compose up -d provider`, apontar `PROVIDER_BASE_URL` para outro stub ou testar com a integração indisponível (ocorrerão novas tentativas até `FAILED`). Para só explorar a criação/consulta sem processar, configure `WORKER_ENABLED=false`.

### Configuração

| Variável | Padrão | Finalidade |
|---|---|---|
| `DB_URL` | H2 em arquivo | URL JDBC; produção usa PostgreSQL |
| `DB_USER`, `DB_PASSWORD` | `sa`, vazio | Credenciais do banco local |
| `PROVIDER_BASE_URL` | `http://localhost:8081` | Endereço do provedor |
| `PROVIDER_CONNECT_TIMEOUT` | `1s` | Timeout de conexão |
| `PROVIDER_READ_TIMEOUT` | `2s` | Timeout de leitura |
| `WORKER_ENABLED` | `true` | Habilita o worker |
| `WORKER_POLL_DELAY` | `1000` | Intervalo entre lotes, em ms |
| `WORKER_MAX_ATTEMPTS` | `3` | Máximo de tentativas (1 a 10) |
| `WORKER_LEASE` | `30s` | Prazo da reserva de processamento |
| `WORKER_BACKOFF` | `2s` | Base do backoff |

O startup rejeita timeouts e backoff não positivos, limites de tentativas inválidos e lease menor ou igual à soma dos timeouts mais 5 segundos. Ajuste lease e timeouts em conjunto. O lote tem no máximo 20 registros e é processado sequencialmente por instância.

## Contrato da API

| Operação | Resultado |
|---|---|
| `POST /api/v1/requests` | `201 Created` e `Location` na criação |
| Mesmo externalId e mesmo conteúdo | `200 OK`, mesmo id e estado atual; não agenda nova ativação |
| Mesmo externalId e conteúdo diferente | `409 Conflict` |
| `GET /api/v1/requests/{id}` | `200 OK` ou `404 Not Found` |
| JSON, UUID ou campos inválidos | `400 Bad Request` |
| Formato de corpo incompatível | `415 Unsupported Media Type` |
| Falha inesperada da API/banco | `500 Internal Server Error`, detalhe genérico |

`externalId`, `customerId` e `productCode` são obrigatórios, têm de 1 a 100 caracteres, começam com letra ou número e permitem somente letras ASCII, números, ponto, hífen e underscore. Não se removem espaços nem se altera caixa silenciosamente: `EXT-a` e `EXT-A` são identificadores diferentes. Propriedades desconhecidas são rejeitadas para detectar erros no contrato.

As datas são `Instant`, serializadas em ISO-8601/UTC. DTOs não expõem entidades JPA. A resposta contém os sete campos obrigatórios, além de `attempts` e `failureCode`. Este último é um código controlado, sem corpo da resposta remota ou detalhes de rede. Uma falha de integração assíncrona aparece no recurso consultado, em vez de transformar retroativamente o POST em erro HTTP.

Erros HTTP usam `application/problem+json` (Problem Details), com `type`, `title`, `status`, `detail`, `instance`, `traceId` e, quando aplicável, `errors`. Um `X-Correlation-ID` de 32 caracteres hexadecimais é aceito; valores diferentes são substituídos por um id gerado. Esse id é devolvido no header, armazenado na solicitação e propagado ao provedor. Stack traces ficam restritos aos logs internos.

## Decisões técnicas

### Arquitetura e SOLID

```text
br.com.activation
├── domain                 # Estado e transições: Java puro e objetos imutáveis
├── application            # Casos de uso e portas RequestStore/ActivationProvider
└── infrastructure
    ├── web                # REST, validação, correlação, tradução de erros
    ├── persistence        # JPA, mapeamento, locks e fronteiras transacionais
    ├── provider           # Cliente HTTP e classificação de resultados
    └── config             # Composição de dependências e scheduler
```

O domínio não conhece Spring, JPA nem HTTP. Os casos de uso dependem de interfaces pequenas e são compostos por configuração Spring. Os adaptadores implementam persistência e integração. Isso aplica inversão de dependência e responsabilidade única sem criar módulos Maven, interfaces para cada classe, factories ou abstrações sem uso. A entidade de persistência é interna ao adaptador; o domínio controla transições por métodos que devolvem uma nova instância. ArchUnit verifica a direção das dependências.

### Duplicidade concorrente

`UNIQUE(external_id)` é a garantia definitiva. Uma consulta prévia é apenas uma otimização: duas threads podem não encontrar o registro ao mesmo tempo. Uma delas insere; a outra viola a restrição. O adaptador conclui o rollback da transação de inserção antes da releitura, e o caso de uso retorna o vencedor ou `409` se o conteúdo divergir. Não se tenta reutilizar uma transação marcada como rollback-only. Não há restrição em memória, portanto a regra também funciona com várias instâncias. A unicidade é global, conforme o enunciado; não se apagam solicitações na API.

### Processamento durável e transações

Foi escolhido processamento assíncrono por polling no banco, sem broker. A própria tabela funciona como fila durável: a criação já grava `PENDING` e `nextAttemptAt`. Assim, cair depois do commit e antes da chamada remota não perde a ativação. Não há dependência de evento em memória nem de `@Async` para garantir entrega.

1. O worker busca candidatos `PENDING` ou `PROCESSING` com prazo vencido.
2. Uma transação curta bloqueia a linha, revalida o estado e grava `PROCESSING`, incrementa `attempts` e define um lease.
3. A transação termina **antes** da chamada HTTP.
4. Outra transação curta grava o resultado somente se o registro ainda estiver em `PROCESSING` com a mesma tentativa. Um resultado atrasado não sobrescreve uma tentativa mais recente.

Há lock pessimista nas reservas/finalizações e `@Version` como proteção adicional da entidade. No PostgreSQL, workers podem buscar os mesmos candidatos, mas apenas um reserva cada tentativa. Após queda, a expiração do lease libera nova tentativa. Se a última tentativa ficou abandonada, o registro vai para `FAILED` com `RETRY_EXHAUSTED` na próxima varredura.

Estados normais: `PENDING → PROCESSING → COMPLETED/FAILED`. Falha temporária permite `PROCESSING → PENDING`, respeitando `nextAttemptAt`. Recuperação de uma reserva expirada mantém `PROCESSING` e abre uma nova tentativa. Estados terminais não retornam ao processamento.

A entrega remota é **pelo menos uma vez**, não exatamente uma vez. Cada tentativa envia `Idempotency-Key` igual ao UUID persistido da solicitação. Isso exige que o provedor implemente deduplicação durável com retenção suficiente. Um timeout pode significar que o produto foi ativado, e pausas longas da JVM podem ultrapassar o lease: o banco impede escrita local atrasada, mas somente a idempotência do provedor impede efeitos externos duplicados. O WireMock é um stub de respostas e não simula esse armazenamento de idempotência.

### Retornos do provedor

| Retorno | Estado | Nova tentativa | Motivo |
|---|---|---|---|
| Qualquer 2xx | `COMPLETED` | Não | Contrato assume ativação concluída em todo 2xx |
| 400 e demais 4xx, exceto 429 | `FAILED` | Não | Reenvio do mesmo conteúdo não corrige rejeição |
| 500 e demais 5xx | `PENDING`, depois `FAILED` no limite | Sim | Falha potencialmente temporária |
| 429 / 503 | `PENDING`, depois `FAILED` no limite | Sim | Saturação/indisponibilidade |
| Timeout, falha de conexão ou I/O | `PENDING`, depois `FAILED` no limite | Sim | Resultado ambíguo; mesma chave de idempotência |
| Resposta não classificável pelo cliente | `PENDING`, depois `FAILED` no limite | Sim | `PROVIDER_PROTOCOL_ERROR` |

O backoff é exponencial, limitado a 5 minutos, com jitter adicional de 0 a 250 ms. São no máximo três tentativas por padrão, incluindo a inicial; tentativas reservadas antes de uma queda também consomem orçamento. Não há retry automático para uma solicitação já `FAILED`. `Retry-After` ainda não é interpretado; 429/503 usam o mesmo backoff. O contrato deve ser revisto se o provedor usar 202 para processamento remoto ainda não concluído.

Índice `(status, next_attempt_at)` atende a busca do worker. A restrição única atende a consulta por externalId e a PK atende o GET. Listagem paginada é opcional e não foi incluída. Ao implementá-la, criaria índices a partir dos filtros e planos reais: `(customer_id, created_at, id)` para cliente e `(status, customer_id, created_at, id)` para combinação de status/cliente, evitando índices redundantes sem medir.

## Segurança (abordagem documentada, conforme permitido no item 7)

Os endpoints desta entrega estão abertos para demonstração. A proteção OAuth 2.0/JWT abaixo é um plano explícito para produção, não uma funcionalidade implementada. Não publicar essa configuração de demonstração diretamente na internet.

- Sistemas consumidores usariam **client credentials** em um IdP; este serviço seria um Spring Security OAuth2 Resource Server, sem emitir tokens nem manter senhas de clientes.
- `issuer-uri` e `jwk-set-uri` viriam de configuração confiável. Validar assinatura com algoritmos permitidos e chaves públicas do JWKS, `iss`, `aud` específica desta API, `exp` e `nbf`, com tolerância de relógio pequena. O cache de JWKS permite rotação; não confiar em URLs fornecidas pelo token.
- Exigir `SCOPE_requests.write` para POST e `SCOPE_requests.read` para GET. Ausência/token inválido: 401; token válido sem escopo: 403. Testar ambos com `spring-security-test`, sem IdP real.
- Para isolamento entre consumidores, persistir `consumerId` de uma claim validada (por exemplo, `client_id`) e filtrar todas as consultas/replays por esse proprietário, retornando 404 fora do escopo. A unicidade global de externalId precisa continuar sem vazar o registro de outro consumidor; uma alternativa, se acordada no contrato, é `(consumer_id, external_id)` único. Esse isolamento ainda não existe na demonstração.
- Credencial do provedor sairia de um gerenciador de segredos, com privilégio mínimo e rotação; OAuth client credentials com cache de token e renovação antes do vencimento. Nenhum token/segredo em Git, payload de erro ou log.
- TLS no ingress e na integração, políticas de rede, limites de corpo e rate limit por cliente no gateway. Sem sessões/cookies, configurar CSRF de acordo com o uso exclusivo de bearer tokens. Restringir Actuator à rede de operação.

## Testes

```bash
./mvnw verify                  # Domínio, casos de uso, API/H2, HTTP e arquitetura
./mvnw verify -Ppostgres-it    # Inclui PostgreSQL real via Testcontainers; requer Docker
```

No Windows, substitua `./mvnw` por `.\mvnw.cmd`. Os relatórios ficam em `target/surefire-reports` e `target/failsafe-reports`. O perfil PostgreSQL não ignora indisponibilidade do Docker: deve falhar se não puder validar o banco real. O GitHub Actions executa esse perfil.

- **Domínio:** transições inválidas, conclusão, falha definitiva e esgotamento de tentativas, sem Spring.
- **Caso de uso/Mockito:** simula a corrida de inserção e a releitura do vencedor após rollback.
- **Integração API:** contexto Spring completo, MockMvc, migrações Flyway e H2; criação/consulta, validação, JSON inválido, campos desconhecidos, inexistência, retry idempotente, conflito e fluxo de sucesso/falha/retries. Só o provedor é substituído.
- **Adaptador HTTP:** valida classificação de 2xx/400/500/429/503 e headers. Um servidor HTTP local deliberadamente lento testa o timeout real configurado.
- **PostgreSQL/Testcontainers:** 12 chamadas simultâneas disputam o mesmo externalId; exige exatamente um `created`, um UUID e uma linha. Também disputa a reserva entre workers e verifica recuperação e descarte de conclusão atrasada.
- **Contrato de persistência compartilhado:** os mesmos três cenários de concorrência/recuperação também rodam no H2 durante o build padrão, incluindo o abandono da última tentativa.
- **Arquitetura:** ArchUnit verifica que domínio e aplicação não dependem dos adaptadores/frameworks.

H2 facilita feedback local, mas não substitui PostgreSQL para validar concorrência/locks. Os testes de banco não usam uma transação única envolvendo as chamadas concorrentes.

Validação realizada nesta entrega: `verify` pelo Maven Wrapper com **18 testes aprovados**, sem falhas ou testes ignorados no Surefire. Também foi iniciado o JAR gerado e verificados readiness `UP`, criação `201`, consulta `200` e repetição idempotente `200` por HTTP real. O perfil PostgreSQL/Testcontainers e o Docker Compose foram preparados, mas não executados neste ambiente, que não dispõe de Docker. O Failsafe é desabilitado intencionalmente no perfil padrão; execute `-Ppostgres-it` para a validação completa no PostgreSQL.

## Cenário de análise de produção (item 10)

**Primeiro investigaria a linha do tempo e a distribuição de latências**, correlacionando mudança de versão/configuração, volume de tráfego, p50/p95/p99, taxa de erros e saturação de pools. CPU em 45% e memória em 60% não descartam espera de I/O, contenção de locks ou falta de conexões. A média de 2,5 s pode esconder poucos endpoints muito lentos.

**Separaria o tempo por etapa:** tracing da entrada HTTP ao banco/provedor, timers de SQL e HTTP remoto, tempo de aquisição de conexão, filas de threads e métricas Hikari (ativas, ociosas, pendentes e timeouts). No banco: consultas lentas, `pg_stat_statements`, locks, transações longas, I/O, conexões e `EXPLAIN (ANALYZE, BUFFERS)` de consultas representativas em ambiente controlado. Na integração: latência por código, timeout, taxa de erros, retries, DNS/TLS e saturação do provedor. Na aplicação: GC, thread dumps durante a lentidão e filas de execução.

**Hipótese de causa comum:** a versão síncrona de um serviço pode manter transações abertas enquanto espera 3 s do provedor, ocupando conexões e locks; a fila do pool faz a API e outras consultas parecerem lentas. Confirmaria com traces sobrepondo chamada remota e transação, aumento de conexões ativas/pendentes, sessões `idle in transaction` e redução dos sintomas ao isolar o provedor. Outra hipótese é aumento de concorrência/retries saturando simultaneamente banco e provedor. Nesta solução, as chamadas remotas já ficam fora das transações, mas backlog e polling ainda devem ser observados.

**Melhorias dependeriam da evidência:** corrigir plano/índice e N+1, encurtar transações, eliminar consultas redundantes, limitar concorrência e retries, ajustar pool à capacidade do banco e aplicar circuit breaker se falhas prolongadas justificarem. Não aumentaria CPU ou pool indiscriminadamente. O processamento assíncrono mantém a latência do POST independente da latência do provedor, mas exige acompanhar idade e tamanho da fila e tempo total até ativação.

**Após publicar:** canário com rollback, teste de carga de perfil equivalente, comparação de p95/p99 e throughput sob a mesma carga, erros, conexões pendentes, locks, custo de SQL e atraso da fila. Conferir correção dos estados e inexistência de ativações duplicadas. Acompanhar por uma janela representativa, incluindo pico, sem aceitar uma melhora de latência obtida às custas de mais falhas.

## Produção e cloud

O Dockerfile usa build em múltiplos estágios, roda testes no build, copia apenas o JAR para JRE 21 e executa com usuário não-root. Há healthcheck, limite de heap proporcional à memória do contêiner, saída em OOM e desligamento gracioso. Ajustar limite de memória, recursos e tempos de desligamento ao volume e aos timeouts; aguardar/remover tráfego antes do encerramento.

Em produção: PostgreSQL gerenciado com backup/PITR, TLS, migrações aplicadas por job de release, segredos externos e imagem imutável versionada. Remover o mock e o fallback H2 da configuração de implantação. Aplicar a segurança descrita acima. Readiness/liveness em `/actuator/health/readiness` e `/actuator/health/liveness`; readiness padrão verifica ciclo de vida, não prontidão do provedor. Configurar política de probes conscientemente, sem derrubar todas as instâncias por uma indisponibilidade remota.

Várias instâncias compartilham o PostgreSQL: a constraint protege unicidade e os locks curtos protegem as reservas. Em escala maior, substituir a disputa de candidatos por `FOR UPDATE SKIP LOCKED`, particionar workers e medir contenção/fairness. Não se promete ordenação global nem exactly-once. Para notificar outros serviços, incluiria um outbox transacional de eventos de conclusão, com publicador e consumidores idempotentes. Esse outbox de eventos é diferente da fila de trabalho já implementada na tabela de solicitações.

CI/CD: build e testes (incluindo Testcontainers), análise estática e de vulnerabilidades, imagem Docker com tag de commit, publicação em registry, migração controlada, deploy canário, smoke tests e rollback. O workflow entregue cobre build/testes; publicação/deploy dependem da plataforma e credenciais do projeto.

## Pontos pendentes e melhorias futuras

- Segurança está **documentada**, opção expressamente aceita no desafio; faltam Resource Server, isolamento por consumidor e testes 401/403.
- Diferenciais não implementados: listagem paginada, interpretação de `Retry-After`, circuit breaker, limite de corpo/rate limit, Prometheus com métricas de negócio, logs JSON e tracing OpenTelemetry. Actuator e correlação básica estão presentes.
- Evoluir observabilidade com timers de provedor, contagem por resultado, idade do registro pendente mais antigo e tamanho do backlog. Evitar IDs de clientes como labels de métricas.
- A recuperação depende da expiração do lease. Para chamadas longas, usar heartbeat e um cliente com deadline total; timeouts de conexão/leitura não garantem limite total em todas as condições de rede. Provedor deve honrar a chave de idempotência.
- Uma falha ambígua no último envio termina em `FAILED`; em produção, reconciliar com uma API de consulta do provedor antes de uma intervenção manual. Criar procedimento auditado de reprocessamento sem trocar a chave de idempotência.
- O WireMock confirma o contrato HTTP básico, mas não prova idempotência remota. Adicionar testes contratuais/sandbox do provedor e política de retenção de chaves.
- Validar versões e vulnerabilidades das dependências e imagens antes de produção; as versões foram fixadas para reprodutibilidade, sem alegar que são as mais recentes.

## Uso de IA

Este projeto foi desenvolvido com assistência do Codex para interpretar o enunciado, propor a organização, implementar código, testes e documentação. As escolhas e limitações estão descritas para permitir revisão e discussão técnica. Antes da entrega, execute também o perfil PostgreSQL em um ambiente com Docker e revise o código que será apresentado.

## Referências

- [Spring Boot 3.5.7](https://spring.io/blog/2025/10/23/spring-boot-3-5-7-available-now/)
- [Maven Wrapper](https://maven.apache.org/wrapper/)
- [RFC 9457 — Problem Details](https://www.rfc-editor.org/rfc/rfc9457)
