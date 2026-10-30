package br.com.activation.infrastructure.provider;

import br.com.activation.application.ActivationProvider;
import br.com.activation.domain.ActivationRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class HttpActivationProvider implements ActivationProvider {
    private static final Logger LOG = LoggerFactory.getLogger(HttpActivationProvider.class);
    private final RestClient client;

    public HttpActivationProvider(RestClient client) { this.client = client; }

    @Override
    public Outcome activate(ActivationRequest request) {
        try (var ignored = MDC.putCloseable("traceId", request.traceId())) {
            long started = System.nanoTime();
            var outcome = send(request);
            LOG.info("provider_activation requestId={} attempt={} successful={} code={} durationMs={}",
                    request.id(), request.attempts(), outcome.successful(), outcome.code(),
                    (System.nanoTime() - started) / 1_000_000);
            return outcome;
        }
    }

    private Outcome send(ActivationRequest request) {
        try {
            return client.post().uri("/provider/v1/activate")
                    .header("Idempotency-Key", request.id().toString())
                    .header("X-Correlation-ID", request.traceId())
                    .body(Map.of("externalId", request.externalId(), "customerId", request.customerId(),
                            "productCode", request.productCode()))
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 200 && status < 300) return Outcome.success();
                        if (status >= 500 || status == 429) return Outcome.temporary("PROVIDER_" + status);
                        return Outcome.permanent("PROVIDER_" + status);
                    });
        } catch (ResourceAccessException exception) {
            return Outcome.temporary("PROVIDER_UNAVAILABLE");
        } catch (RestClientException exception) {
            return Outcome.temporary("PROVIDER_PROTOCOL_ERROR");
        }
    }
}
