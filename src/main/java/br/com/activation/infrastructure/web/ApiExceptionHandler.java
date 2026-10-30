package br.com.activation.infrastructure.web;

import br.com.activation.application.RequestService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var problem = problem(HttpStatus.BAD_REQUEST, "Dados inválidos", "Um ou mais campos não passaram na validação.", request);
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", error.getDefaultMessage())).toList());
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail malformed(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Dados inválidos", "Verifique o JSON e os parâmetros da requisição.", request);
    }

    @ExceptionHandler({RequestService.NotFound.class, NoResourceFoundException.class})
    ProblemDetail notFound(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Não encontrado", "O recurso solicitado não existe.", request);
    }

    @ExceptionHandler(RequestService.Conflict.class)
    ProblemDetail conflict(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Solicitação conflitante", "externalId já utilizado com conteúdo diferente.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido", "Método HTTP não suportado para este recurso.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail mediaType(HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Formato não suportado", "Envie o corpo como application/json.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Erro inesperado traceId={}", request.getAttribute("traceId"), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Não foi possível concluir a operação.", request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:activation:problem:" + status.value()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("traceId", request.getAttribute("traceId"));
        return problem;
    }
}
