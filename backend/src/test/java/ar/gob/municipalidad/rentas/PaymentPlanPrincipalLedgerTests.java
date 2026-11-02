package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentPlanPrincipalLedgerTests {
    @Mock AuditRepository entries;
    @Mock AuditService audit;

    private PaymentPlanPrincipalLedger ledger() {
        return new PaymentPlanPrincipalLedger(entries, audit, new ObjectMapper());
    }

    @Test void recordsOnlyPositiveUniqueDebtSharesThatConserveAppliedPrincipal() {
        PaymentAllocation allocation=allocation("50.00");
        List<PaymentPlanPrincipalLedger.Share> shares=List.of(
            new PaymentPlanPrincipalLedger.Share(10L,new BigDecimal("30.00")),
            new PaymentPlanPrincipalLedger.Share(11L,new BigDecimal("20.00")));

        ledger().record(allocation,shares);

        verify(audit).record("PaymentAllocation",44L,"PLAN_PRINCIPAL_DISTRIBUTED",shares);
    }

    @Test void rejectsDistributionThatDoesNotConserveAppliedPrincipalBeforeAuditing() {
        PaymentAllocation allocation=allocation("50.00");
        List<PaymentPlanPrincipalLedger.Share> shares=List.of(
            new PaymentPlanPrincipalLedger.Share(10L,new BigDecimal("30.00")),
            new PaymentPlanPrincipalLedger.Share(11L,new BigDecimal("19.99")));

        assertThatThrownBy(()->ledger().record(allocation,shares))
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code).isEqualTo("PLAN_DISTRIBUTION_INVALID"));

        verifyNoInteractions(audit);
    }

    @Test void rejectsRepeatedDebtAndNonpositiveSharesBeforeAuditing() {
        PaymentAllocation allocation=allocation("50.00");
        List<PaymentPlanPrincipalLedger.Share> duplicateDebt=List.of(
            new PaymentPlanPrincipalLedger.Share(10L,new BigDecimal("25.00")),
            new PaymentPlanPrincipalLedger.Share(10L,new BigDecimal("25.00")));
        List<PaymentPlanPrincipalLedger.Share> zeroShare=List.of(
            new PaymentPlanPrincipalLedger.Share(10L,new BigDecimal("50.00")),
            new PaymentPlanPrincipalLedger.Share(11L,BigDecimal.ZERO));

        assertThatThrownBy(()->ledger().record(allocation,duplicateDebt))
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code).isEqualTo("PLAN_DISTRIBUTION_INVALID"));
        assertThatThrownBy(()->ledger().record(allocation,zeroShare))
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code).isEqualTo("PLAN_DISTRIBUTION_INVALID"));

        verifyNoInteractions(audit);
    }

    @Test void findsPersistedDistributionAndRejectsASecondRecordForTheSameAllocation() throws Exception {
        PaymentAllocation allocation=allocation("50.00");
        List<PaymentPlanPrincipalLedger.Share> shares=List.of(
            new PaymentPlanPrincipalLedger.Share(10L,new BigDecimal("30.00")),
            new PaymentPlanPrincipalLedger.Share(11L,new BigDecimal("20.00")));
        AuditEntry existing=new AuditEntry();existing.action="PLAN_PRINCIPAL_DISTRIBUTED";
        existing.newData=new ObjectMapper().writeValueAsString(shares);
        when(entries.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("PaymentAllocation","44"))
            .thenReturn(List.of(),List.of(existing),List.of(existing));

        ledger().record(allocation,shares);
        assertThat(ledger().find(allocation)).contains(shares);
        assertThatThrownBy(()->ledger().record(allocation,shares))
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code).isEqualTo("PLAN_DISTRIBUTION_ALREADY_RECORDED"));

        verify(audit).record(eq("PaymentAllocation"),eq(44L),eq("PLAN_PRINCIPAL_DISTRIBUTED"),same(shares));
    }

    private PaymentAllocation allocation(String principal) {
        PaymentAllocation allocation=new PaymentAllocation();allocation.id=44L;
        allocation.principalApplied=new BigDecimal(principal);return allocation;
    }
}
