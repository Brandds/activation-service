package br.com.activation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import br.com.activation.application.ActivationProvider;
import br.com.activation.application.ProcessRequests;
import br.com.activation.application.RequestService;
import br.com.activation.domain.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "worker.enabled=false", "worker.backoff=1ms"})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestService service;
    @Autowired ProcessRequests processor;
    @MockitoBean ActivationProvider provider;

    @BeforeEach void clean() { jdbc.update("delete from activation_requests"); reset(provider); }

    @Test void createsReadsReplaysAndRejectsConflictingPayload() throws Exception {
        var response = mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                .content(payload("EXT-1", "NET")))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/v1/requests/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-1"));
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                .content(payload("EXT-1", "NET"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                .content(payload("EXT-1", "OTHER"))).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertThat(jdbc.queryForObject("select count(*) from activation_requests", Integer.class)).isEqualTo(1);
    }

    @Test void rejectsInvalidDataMalformedJsonAndUnknownFields() throws Exception {
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalId\":\"\",\"customerId\":\"CUST\",\"productCode\":\"NET\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.traceId").exists()).andExpect(jsonPath("$.stackTrace").doesNotExist());
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalId\":\"EXT\",\"customerId\":\"CUST\",\"productCode\":\"NET\",\"extra\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test void returns404And400ForMissingAndInvalidIdentifiers() throws Exception {
        mvc.perform(get("/api/v1/requests/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/requests/invalid")).andExpect(status().isBadRequest());
    }

    @Test void completesSuccessfulActivation() {
        when(provider.activate(any())).thenReturn(ActivationProvider.Outcome.success());
        var request = service.create("EXT-OK", "CUST", "NET", "a".repeat(32)).request();
        processor.runBatch();
        assertThat(service.get(request.id()).status()).isEqualTo(Status.COMPLETED);
        assertThat(service.get(request.id()).attempts()).isEqualTo(1);
    }

    @Test void recordsPermanentFailure() {
        when(provider.activate(any())).thenReturn(ActivationProvider.Outcome.permanent("PROVIDER_400"));
        var request = service.create("EXT-FAIL", "CUST", "NET", "a".repeat(32)).request();
        processor.runBatch();
        assertThat(service.get(request.id()).status()).isEqualTo(Status.FAILED);
        assertThat(service.get(request.id()).failureCode()).isEqualTo("PROVIDER_400");
    }

    @Test void retriesTemporaryFailureAndExhaustsBudget() {
        when(provider.activate(any())).thenReturn(ActivationProvider.Outcome.temporary("PROVIDER_500"));
        var request = service.create("EXT-RETRY", "CUST", "NET", "a".repeat(32)).request();
        processor.runBatch();
        assertThat(service.get(request.id()).status()).isEqualTo(Status.PENDING);
        for (int attempt = 2; attempt <= 3; attempt++) {
            jdbc.update("update activation_requests set next_attempt_at = ? where id = ?",
                    java.time.Instant.now().minusSeconds(1), request.id());
            processor.runBatch();
        }
        assertThat(service.get(request.id()).status()).isEqualTo(Status.FAILED);
        assertThat(service.get(request.id()).attempts()).isEqualTo(3);
    }

    private String payload(String externalId, String product) {
        return "{\"externalId\":\"" + externalId + "\",\"customerId\":\"CUST-1\",\"productCode\":\"" + product + "\"}";
    }
}
