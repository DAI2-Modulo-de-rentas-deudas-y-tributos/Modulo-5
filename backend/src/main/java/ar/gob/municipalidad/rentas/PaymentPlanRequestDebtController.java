package ar.gob.municipalidad.rentas;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Detalle de obligaciones de una solicitud, limitado al titular o personal autorizado. */
@RestController
@RequestMapping("/api/v1/payment-plan-requests")
class PaymentPlanRequestDebtController {
    private final PaymentPlanRequestRepository requests;
    private final PaymentPlanRequestDebtRepository requestDebts;
    private final DebtRepository debts;
    private final TaxConceptRepository concepts;
    private final CurrentIdentity identity;

    PaymentPlanRequestDebtController(PaymentPlanRequestRepository requests,
            PaymentPlanRequestDebtRepository requestDebts, DebtRepository debts,
            TaxConceptRepository concepts, CurrentIdentity identity) {
        this.requests = requests;
        this.requestDebts = requestDebts;
        this.debts = debts;
        this.concepts = concepts;
        this.identity = identity;
    }

    @GetMapping("/{id}/debts")
    @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','AUDITOR','TAXPAYER')")
    List<RequestDebt> list(@PathVariable Long id) {
        PaymentPlanRequest request = requests.findById(id)
                .orElseThrow(() -> CatalogService.notFound("Solicitud de plan"));
        identity.requireOwnership(request.taxpayerId);
        return requestDebts.findByRequestId(id).stream().map(link -> {
            Debt debt = debts.findById(link.debtId)
                    .orElseThrow(() -> CatalogService.notFound("Deuda"));
            String conceptName = concepts.findById(debt.taxConceptId)
                    .map(concept -> concept.name).orElse("Deuda #" + debt.id);
            return new RequestDebt(debt.id, debt.taxConceptId, conceptName,
                    link.balanceAtRequest, debt.originalAmount);
        }).toList();
    }

    record RequestDebt(Long id, Long taxConceptId, String conceptName,
            BigDecimal balanceAtRequest, BigDecimal originalAmount) {}
}
