package br.com.activation.infrastructure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import br.com.activation.application.RequestService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RequestController.class)
class ApiErrorTest {
    @Autowired MockMvc mvc;
    @MockitoBean RequestService service;

    @Test void unexpectedErrorDoesNotExposeInternalDetailsAndPreservesCorrelation() throws Exception {
        var id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new RuntimeException("internal database secret"));
        mvc.perform(get("/api/v1/requests/" + id).header("X-Correlation-ID", "a".repeat(32)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("X-Correlation-ID", "a".repeat(32)))
                .andExpect(jsonPath("$.traceId").value("a".repeat(32)))
                .andExpect(jsonPath("$.detail").value("Não foi possível concluir a operação."))
                .andExpect(content().string(not(containsString("internal database secret"))));
    }

    @Test void rejectsUnsupportedMethodAndContentType() throws Exception {
        mvc.perform(delete("/api/v1/requests/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/requests").contentType("text/plain").content("invalid"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
