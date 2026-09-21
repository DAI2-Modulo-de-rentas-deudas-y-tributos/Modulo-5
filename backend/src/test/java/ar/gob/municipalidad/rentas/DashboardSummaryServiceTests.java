package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardSummaryServiceTests {
    @Mock AdjustmentRepository adjustments;
    @Mock PaymentReversalRepository reversals;
    @Mock ExemptionRequestRepository exemptions;
    @Mock PaymentPlanRequestRepository planRequests;
    @Mock RefinancingRequestRepository refinancings;
    @Mock LiquidationRunRepository liquidationRuns;
    @Mock PlanExpirationRepository planExpirations;
    @Mock TicketCaseRepository tickets;
    @Mock PaymentRepository payments;
    @Mock AuditRepository audit;
    @InjectMocks DashboardSummaryService service;

    @Test void supervisorSummaryUsesOnlyActualApprovalQueues() {
        when(adjustments.countByStatus(AdjustmentStatus.PENDING_APPROVAL)).thenReturn(1L);
        when(reversals.countByStatus(PaymentReversalStatus.PENDING_APPROVAL)).thenReturn(2L);
        when(exemptions.countByStatus(ExemptionRequestStatus.PENDING_RESOLUTION)).thenReturn(3L);
        when(planRequests.countByStatus(PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL)).thenReturn(4L);
        when(refinancings.countByStatus(RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL)).thenReturn(5L);
        when(liquidationRuns.countByStatus(LiquidationRunStatus.PENDING_APPROVAL)).thenReturn(6L);
        when(planExpirations.countByStatus(PlanExpirationStatus.PENDING_APPROVAL)).thenReturn(7L);

        ApiDtos.SupervisorDashboardResponse result=service.supervisor();

        assertThat(result.totalPending()).isEqualTo(28);
        assertThat(result.queues()).extracting(ApiDtos.DashboardQueueResponse::key)
            .containsExactly("adjustments","reversals","exemptions","exceptionalPlans",
                "exceptionalRefinancings","liquidationRuns","planExpirations");
        assertThat(result.queues()).allSatisfy(queue->{assertThat(queue.path()).startsWith("/rentas/");assertThat(queue.statusFilter()).isNotBlank();});
    }

    @Test void rentasSummaryReturnsOperationalCountsAndSanitizedRecentOperations() {
        BigDecimal zero=BigDecimal.ZERO.setScale(2);
        when(tickets.countByStatusIn(List.of(TicketCaseStatus.OPEN,TicketCaseStatus.IN_PROGRESS,TicketCaseStatus.WAITING_FOR_INFORMATION))).thenReturn(3L);
        when(payments.countByStatusAndUnallocatedAmountGreaterThan(PaymentStatus.CONFIRMED,zero)).thenReturn(2L);
        when(payments.sumUnallocatedAmount(PaymentStatus.CONFIRMED,zero)).thenReturn(new BigDecimal("125.00"));
        when(exemptions.countByStatusIn(List.of(ExemptionRequestStatus.PENDING,ExemptionRequestStatus.UNDER_REVIEW,ExemptionRequestStatus.DOCUMENTATION_REQUIRED))).thenReturn(4L);
        when(planRequests.countByStatus(PaymentPlanRequestStatus.PENDING)).thenReturn(5L);
        AuditEntry entry=new AuditEntry();entry.entityType="Payment";entry.entityId="91";entry.action="PAYMENT_REGISTERED";entry.occurredAt=OffsetDateTime.now();entry.previousData="sensible";entry.newData="sensible";
        when(audit.findTop10ByEntityTypeInOrderByOccurredAtDescIdDesc(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(entry));

        ApiDtos.RentasDashboardResponse result=service.rentas();

        assertThat(result.openTickets()).isEqualTo(3);
        assertThat(result.unallocatedPayments()).isEqualTo(2);
        assertThat(result.unallocatedPaymentAmount()).isEqualByComparingTo("125.00");
        assertThat(result.pendingExemptions()).isEqualTo(4);
        assertThat(result.pendingPlanRequests()).isEqualTo(5);
        assertThat(result.recentOperations()).singleElement().satisfies(operation->{
            assertThat(operation.entityType()).isEqualTo("Payment");
            assertThat(operation.entityId()).isEqualTo("91");
            assertThat(operation.action()).isEqualTo("PAYMENT_REGISTERED");
        });
    }
}
