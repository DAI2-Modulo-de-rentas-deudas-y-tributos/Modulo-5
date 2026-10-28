package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Keeps a rejected reversal attempt visible even when its economic transaction rolls back. */
@Service
class ReversalRejectionAudit {
    private final AuditService audit;

    ReversalRejectionAudit(AuditService audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(Long reversalId, Long allocationId, String reasonCode) {
        audit.record("PaymentReversal", reversalId, "PAYMENT_REVERSAL_BLOCKED",
            Map.of("allocationId", allocationId, "reasonCode", reasonCode));
    }
}
