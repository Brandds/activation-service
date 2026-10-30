package br.com.activation.infrastructure.config;

import br.com.activation.application.*;
import br.com.activation.infrastructure.provider.HttpActivationProvider;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ApplicationConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean RequestService requestService(RequestStore store, Clock clock) {
        return new RequestService(store, clock);
    }

    @Bean ActivationProvider activationProvider(@Value("${provider.base-url}") String baseUrl,
            @Value("${provider.connect-timeout}") Duration connectTimeout,
            @Value("${provider.read-timeout}") Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new HttpActivationProvider(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build());
    }

    @Bean ProcessRequests processRequests(RequestStore store, ActivationProvider provider, Clock clock,
            @Value("${worker.max-attempts}") int maxAttempts,
            @Value("${worker.lease}") Duration lease,
            @Value("${worker.backoff}") Duration backoff,
            @Value("${provider.connect-timeout}") Duration connectTimeout,
            @Value("${provider.read-timeout}") Duration readTimeout) {
        if (maxAttempts < 1 || maxAttempts > 10 || backoff.isNegative() || backoff.isZero()
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()
                || lease.compareTo(connectTimeout.plus(readTimeout).plusSeconds(5)) <= 0) {
            throw new IllegalArgumentException("Configuração inválida de tentativas, backoff, lease ou timeouts");
        }
        return new ProcessRequests(store, provider, clock, maxAttempts, lease, backoff);
    }
}
