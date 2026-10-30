package br.com.activation.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class CorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var incoming = request.getHeader("X-Correlation-ID");
        var traceId = incoming != null && incoming.matches("[a-fA-F0-9]{32}")
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        request.setAttribute("traceId", traceId);
        response.setHeader("X-Correlation-ID", traceId);
        try (var ignored = MDC.putCloseable("traceId", traceId)) {
            chain.doFilter(request, response);
        }
    }
}
