package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentPlanServiceMutationTests {
    @Mock DebtRepository debts;
    @Mock PaymentPlanRepository plans;
    @Mock InstallmentRepository installments;
    @Mock AuditService audit;
    @Mock CurrentIdentity identity;

    @Test void grantCreatesExactlyTheRequestedInstallmentsAndConservesAmounts() {
        Debt debt = new Debt();
        debt.id = 41L;
        debt.taxpayerId = 72L;
        debt.outstandingBalance = new BigDecimal("100.00");
        when(debts.findByIdForUpdate(debt.id)).thenReturn(Optional.of(debt));
        when(plans.existsByDebtIdAndStatus(debt.id, PaymentPlanStatus.ACTIVE)).thenReturn(false);
        when(plans.save(any(PaymentPlan.class))).thenAnswer(invocation -> {
            PaymentPlan plan = invocation.getArgument(0);
            plan.id = 93L;
            return plan;
        });
        when(identity.get()).thenReturn(new AuthenticatedIdentity("supervisor.test", null));

        List<Installment> saved = new ArrayList<>();
        when(installments.save(any(Installment.class))).thenAnswer(invocation -> {
            Installment installment = invocation.getArgument(0);
            saved.add(installment);
            if (saved.size() > 3) throw new AssertionError("Se persistió una cuota adicional");
            return installment;
        });

        PaymentPlan plan = new PaymentPlanService(debts, plans, installments, audit, identity)
            .grant(new ApiDtos.GrantPlanRequest(debt.id, 3, new BigDecimal("12.00")));

        assertThat(plan.totalPlanAmount).isEqualByComparingTo("112.00");
        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(installment -> installment.number).containsExactly(1, 2, 3);
        assertThat(saved.stream().map(installment -> installment.principalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(saved.stream().map(installment -> installment.totalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("112.00");
    }
}
