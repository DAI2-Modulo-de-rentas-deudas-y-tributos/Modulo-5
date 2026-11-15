package ar.gob.municipalidad.rentas;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/outbox")
class OutboxRetryController {
    private final OutboxRetryService retry;
    private final IdempotencyExecutor idempotency;

    OutboxRetryController(OutboxRetryService retry, IdempotencyExecutor idempotency) {
        this.retry = retry;
        this.idempotency = idempotency;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR','AUDITOR','TECHNICAL')")
    ResponseEntity<ApiDtos.OutboxEventResponse> get(@PathVariable UUID id) {
        var response = ApiResponses.of(retry.get(id));
        return ResponseEntity.ok().eTag(EntityTags.outbox(response)).body(response);
    }

    @PostMapping("/{id}/retry")
    @io.swagger.v3.oas.annotations.Operation(summary = "Encolar un reintento manual del Outbox",
        description = "Solo Supervisor. Acepta eventos FAILED o DEAD_LETTER y devuelve 202 cuando quedan encolados; no confirma su publicación ni procesamiento externo.",
        responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Evento reencolado",
            content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = OutboxRetryResponse.class))))
    @PreAuthorize("hasRole('SUPERVISOR')")
    ResponseEntity<OutboxRetryResponse> retry(@PathVariable UUID id,
                              @RequestHeader(name = "If-Match", required = false) String ifMatch,
                              @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        OutboxRetryResult result = idempotency.execute("POST:/integrations/outbox/{id}/retry:" + id, key,
            ifMatch == null ? null : new ApiDtos.IfMatchIdempotencyRequest(ifMatch, null),
            OutboxRetryResult.class, () -> retry.requeue(id, ifMatch));
        return ResponseEntity.accepted().eTag(result.etag()).body(result.response());
    }
}
