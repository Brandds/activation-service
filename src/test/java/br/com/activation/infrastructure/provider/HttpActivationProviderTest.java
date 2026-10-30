package br.com.activation.infrastructure.provider;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import br.com.activation.domain.ActivationRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpActivationProviderTest {
    private final ActivationRequest request = ActivationRequest.create("EXT", "CUST", "NET", Instant.now(), "a".repeat(32));

    @Test void mapsHttpResponsesAndPropagatesStableIdempotencyKey() {
        for (var status : new HttpStatus[]{HttpStatus.OK, HttpStatus.BAD_REQUEST,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE}) {
            var builder = RestClient.builder().baseUrl("http://provider");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://provider/provider/v1/activate"))
                    .andExpect(header("Idempotency-Key", request.id().toString()))
                    .andExpect(header("X-Correlation-ID", request.traceId())).andRespond(withStatus(status));
            var result = new HttpActivationProvider(builder.build()).activate(request);
            assertThat(result.successful()).isEqualTo(status.is2xxSuccessful());
            assertThat(result.retryable()).isEqualTo(status.is5xxServerError() || status.value() == 429);
            server.verify();
        }
    }

    @Test void slowProviderExceedsConfiguredReadTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/provider/v1/activate", exchange -> {
            try {
                Thread.sleep(500);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(1));
            factory.setReadTimeout(Duration.ofMillis(50));
            var client = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(factory).build();
            var result = new HttpActivationProvider(client).activate(request);
            assertThat(result.retryable()).isTrue();
            assertThat(result.code()).isEqualTo("PROVIDER_UNAVAILABLE");
        } finally { server.stop(0); }
    }
}
