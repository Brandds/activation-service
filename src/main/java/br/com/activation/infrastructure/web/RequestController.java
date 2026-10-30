package br.com.activation.infrastructure.web;

import br.com.activation.application.RequestService;
import br.com.activation.domain.ActivationRequest;
import br.com.activation.domain.Status;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/requests")
public class RequestController {
    private final RequestService service;

    public RequestController(RequestService service) { this.service = service; }

    @PostMapping
    ResponseEntity<RequestResponse> create(@Valid @RequestBody CreateRequest body, HttpServletRequest http) {
        var creation = service.create(body.externalId(), body.customerId(), body.productCode(),
                (String) http.getAttribute("traceId"));
        var response = RequestResponse.from(creation.request());
        var location = URI.create("/api/v1/requests/" + response.id());
        return ResponseEntity.status(creation.created() ? 201 : 200).location(location).body(response);
    }

    @GetMapping("/{id}")
    RequestResponse get(@PathVariable UUID id) { return RequestResponse.from(service.get(id)); }

    public record CreateRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String externalId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String customerId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String productCode) { }

    public record RequestResponse(UUID id, String externalId, String customerId, String productCode,
            Status status, Instant createdAt, Instant updatedAt, int attempts, String failureCode) {
        static RequestResponse from(ActivationRequest request) {
            return new RequestResponse(request.id(), request.externalId(), request.customerId(), request.productCode(),
                    request.status(), request.createdAt(), request.updatedAt(), request.attempts(), request.failureCode());
        }
    }
}
