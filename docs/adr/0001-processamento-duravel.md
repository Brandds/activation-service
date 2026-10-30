# ADR 0001 — Processar ativações por fila persistida no banco

Status: aceita.

## Contexto

O provedor pode ficar lento ou indisponível. Uma queda depois da criação não pode perder o envio, e a solução deve permanecer pequena para o desafio.

## Decisão

Gravar a solicitação e os metadados de agendamento na mesma linha. Worker faz polling, reserva sob lock em transação curta, chama HTTP fora da transação e finaliza sob verificação da tentativa. Lease permite recuperar reservas abandonadas. Constraint única garante externalId global. UUID persistido é enviado como chave de idempotência remota.

## Consequências

POST responde sem depender da latência remota; não exige broker nem coordenação em memória. Há latência de polling, disputa de candidatos e semântica pelo menos uma vez. São necessários idempotência do provedor e monitoramento de backlog. Um broker/outbox separado seria reavaliado com maior volume ou publicação de eventos para outros serviços.
