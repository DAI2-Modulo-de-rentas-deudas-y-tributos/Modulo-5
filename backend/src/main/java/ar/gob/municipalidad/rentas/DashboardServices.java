package ar.gob.municipalidad.rentas;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@Service
class DashboardSummaryService {
    private static final List<String> OPERATIONAL_ENTITIES = List.of(
        "Payment", "TicketCase", "ExemptionRequest", "PaymentPlanRequest", "Debt", "Liquidation");

    private final AdjustmentRepository adjustments;
    private final PaymentReversalRepository reversals;
    private final ExemptionRequestRepository exemptions;
    private final PaymentPlanRequestRepository planRequests;
    private final RefinancingRequestRepository refinancings;
    private final LiquidationRunRepository liquidationRuns;
    private final PlanExpirationRepository planExpirations;
    private final TicketCaseRepository tickets;
    private final PaymentRepository payments;
    private final AuditRepository audit;

    DashboardSummaryService(AdjustmentRepository adjustments, PaymentReversalRepository reversals,
        ExemptionRequestRepository exemptions, PaymentPlanRequestRepository planRequests,
        RefinancingRequestRepository refinancings, LiquidationRunRepository liquidationRuns,
        PlanExpirationRepository planExpirations, TicketCaseRepository tickets,
        PaymentRepository payments, AuditRepository audit) {
        this.adjustments=adjustments;this.reversals=reversals;this.exemptions=exemptions;
        this.planRequests=planRequests;this.refinancings=refinancings;this.liquidationRuns=liquidationRuns;
        this.planExpirations=planExpirations;this.tickets=tickets;this.payments=payments;this.audit=audit;
    }

    @Transactional(readOnly=true)
    ApiDtos.SupervisorDashboardResponse supervisor() {
        long adjustmentCount=adjustments.countByStatus(AdjustmentStatus.PENDING_APPROVAL);
        long reversalCount=reversals.countByStatus(PaymentReversalStatus.PENDING_APPROVAL);
        long exemptionCount=exemptions.countByStatus(ExemptionRequestStatus.PENDING_RESOLUTION);
        long exceptionalPlanCount=planRequests.countByStatus(PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL);
        long exceptionalRefinancingCount=refinancings.countByStatus(RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL);
        long runCount=liquidationRuns.countByStatus(LiquidationRunStatus.PENDING_APPROVAL);
        long expirationCount=planExpirations.countByStatus(PlanExpirationStatus.PENDING_APPROVAL);
        List<ApiDtos.DashboardQueueResponse> queues=List.of(
            queue("adjustments","Ajustes",adjustmentCount,"/rentas/ajustes","PENDING_APPROVAL"),
            queue("reversals","Reversiones",reversalCount,"/rentas/reversiones","PENDING_APPROVAL"),
            queue("exemptions","Exenciones",exemptionCount,"/rentas/exenciones","PENDING_RESOLUTION"),
            queue("exceptionalPlans","Planes excepcionales",exceptionalPlanCount,"/rentas/planes","PENDING_EXCEPTION_APPROVAL"),
            queue("exceptionalRefinancings","Refinanciaciones",exceptionalRefinancingCount,"/rentas/refinanciacion","PENDING_EXCEPTION_APPROVAL"),
            queue("liquidationRuns","Corridas masivas",runCount,"/rentas/corridas-masivas","PENDING_APPROVAL"),
            queue("planExpirations","Caducidades",expirationCount,"/rentas/caducidades","PENDING_APPROVAL"));
        long total=queues.stream().mapToLong(ApiDtos.DashboardQueueResponse::count).sum();
        return new ApiDtos.SupervisorDashboardResponse(adjustmentCount,reversalCount,exemptionCount,
            exceptionalPlanCount,exceptionalRefinancingCount,runCount,expirationCount,total,queues);
    }

    @Transactional(readOnly=true)
    ApiDtos.RentasDashboardResponse rentas() {
        BigDecimal zero=BigDecimal.ZERO.setScale(2);
        long unallocated=payments.countByStatusAndUnallocatedAmountGreaterThan(PaymentStatus.CONFIRMED,zero);
        BigDecimal unallocatedAmount=payments.sumUnallocatedAmount(PaymentStatus.CONFIRMED,zero);
        List<ApiDtos.RecentOperationResponse> recent=audit
            .findTop10ByEntityTypeInOrderByOccurredAtDescIdDesc(OPERATIONAL_ENTITIES).stream()
            .map(entry->new ApiDtos.RecentOperationResponse(entry.entityType,entry.entityId,entry.action,entry.occurredAt))
            .toList();
        return new ApiDtos.RentasDashboardResponse(
            tickets.countByStatusIn(List.of(TicketCaseStatus.OPEN,TicketCaseStatus.IN_PROGRESS,TicketCaseStatus.WAITING_FOR_INFORMATION)),
            unallocated,unallocatedAmount,
            exemptions.countByStatusIn(List.of(ExemptionRequestStatus.PENDING,ExemptionRequestStatus.UNDER_REVIEW,ExemptionRequestStatus.DOCUMENTATION_REQUIRED)),
            planRequests.countByStatus(PaymentPlanRequestStatus.PENDING),recent);
    }

    private ApiDtos.DashboardQueueResponse queue(String key,String label,long count,String path,String status) {
        return new ApiDtos.DashboardQueueResponse(key,label,count,path,status);
    }
}

@RestController
@RequestMapping("/api/v1/dashboards")
class DashboardController {
    private final DashboardSummaryService summaries;
    DashboardController(DashboardSummaryService summaries){this.summaries=summaries;}

    @GetMapping("/supervisor")
    @PreAuthorize("hasRole('SUPERVISOR')")
    ApiDtos.SupervisorDashboardResponse supervisor(){return summaries.supervisor();}

    @GetMapping("/rentas")
    @PreAuthorize("hasRole('RENTAS')")
    ApiDtos.RentasDashboardResponse rentas(){return summaries.rentas();}
}
