package ar.gob.municipalidad.rentas;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    @PostMapping("/{id}/retry")
    @io.swagger.v3.oas.annotations.Operation(summary = "Encolar un reintento manual del Outbox",
        description = "Solo Supervisor. Acepta eventos FAILED o DEAD_LETTER y devuelve 202 cuando quedan encolados; no confirma su publicación ni procesamiento externo.")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('SUPERVISOR')")
    OutboxRetryResponse retry(@PathVariable UUID id,
                              @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return idempotency.execute("POST:/integrations/outbox/{id}/retry:" + id, key, null,
            OutboxRetryResponse.class, () -> retry.requeue(id));
    }
}
