package br.com.activation.infrastructure.config;

import br.com.activation.application.ProcessRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
class RequestScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(RequestScheduler.class);
    private final ProcessRequests processor;

    RequestScheduler(ProcessRequests processor) { this.processor = processor; }

    @Scheduled(fixedDelayString = "${worker.poll-delay:1000}")
    void process() {
        try {
            processor.runBatch();
        } catch (RuntimeException exception) {
            LOG.error("Falha no lote de processamento; registros serão recuperados pelo lease", exception);
        }
    }
}
