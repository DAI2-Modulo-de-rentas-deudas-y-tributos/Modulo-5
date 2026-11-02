package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Durable, transactionally written breakdown of an installment's effect on each debt. */
@Service
class PaymentPlanPrincipalLedger {
    public record Share(Long debtId, BigDecimal amount) {}

    private final AuditRepository entries;
    private final AuditService audit;
    private final ObjectMapper json;

    PaymentPlanPrincipalLedger(AuditRepository entries, AuditService audit, ObjectMapper json) {
        this.entries = entries;
        this.audit = audit;
        this.json = json;
    }

    void record(PaymentAllocation allocation, List<Share> shares) {
        validate(allocation, shares);
        CatalogService.require(find(allocation).isEmpty(), "PLAN_DISTRIBUTION_ALREADY_RECORDED", "La distribución de la cuota ya está registrada");
        audit.record("PaymentAllocation", allocation.id, "PLAN_PRINCIPAL_DISTRIBUTED", shares);
    }

    Optional<List<Share>> find(PaymentAllocation allocation) {
        List<AuditEntry> records = entries.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("PaymentAllocation", allocation.id.toString())
            .stream().filter(entry -> "PLAN_PRINCIPAL_DISTRIBUTED".equals(entry.action)).toList();
        CatalogService.require(records.size() <= 1, "PLAN_DISTRIBUTION_AMBIGUOUS", "La distribución histórica de la cuota es ambigua");
        if (records.isEmpty()) return Optional.empty();
        try {
            List<Share> shares = json.readValue(records.get(0).newData, new TypeReference<List<Share>>() {});
            validate(allocation, shares);
            return Optional.of(shares);
        } catch (Exception ex) {
            throw new BusinessException("PLAN_DISTRIBUTION_INVALID", "No se pudo recuperar la distribución registrada de la cuota", 409);
        }
    }

    private void validate(PaymentAllocation allocation, List<Share> shares) {
        CatalogService.require(shares != null, "PLAN_DISTRIBUTION_INVALID", "Falta la distribución de capital de la cuota");
        HashSet<Long> debts = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Share share : shares) {
            CatalogService.require(share != null && share.debtId() != null && share.amount() != null
                && share.amount().signum() > 0 && debts.add(share.debtId()), "PLAN_DISTRIBUTION_INVALID", "La distribución por deuda es inválida");
            total = total.add(share.amount());
        }
        CatalogService.require(total.compareTo(allocation.principalApplied) == 0, "PLAN_DISTRIBUTION_INVALID", "El capital distribuido no coincide con la imputación");
    }
}
