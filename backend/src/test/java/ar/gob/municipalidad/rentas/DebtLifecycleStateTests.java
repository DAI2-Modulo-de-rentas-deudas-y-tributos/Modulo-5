package ar.gob.municipalidad.rentas;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class DebtLifecycleStateTests {
    @ParameterizedTest
    @MethodSource("invalidTransitions")
    void invalidDebtTransitionsReturnConflictWithoutChangingEconomicState(DebtStatus status, String operation, String code) {
        Debt debt=debt(status);
        BigDecimal originalAmount=debt.currentAmount;
        BigDecimal originalBalance=debt.outstandingBalance;

        assertThatThrownBy(()->apply(debt,operation))
            .isInstanceOfSatisfying(BusinessException.class,error->{assertThat(error.code).isEqualTo(code);assertThat(error.status).isEqualTo(409);});

        assertThat(debt.status).isEqualTo(status);
        assertThat(debt.currentAmount).isEqualByComparingTo(originalAmount);
        assertThat(debt.outstandingBalance).isEqualByComparingTo(originalBalance);
    }

    @Test void paymentAdjustmentReversalAndAnnulmentUseTheCurrentState() {
        Debt pending=debt(DebtStatus.PENDING);
        assertThat(pending.admiteCobro()).isTrue();
        pending.imputar(new BigDecimal("100.00"));
        assertThat(pending.status).isEqualTo(DebtStatus.PAID);

        Debt partial=debt(DebtStatus.PARTIALLY_PAID);
        partial.revertirImputacion(new BigDecimal("50.00"));
        assertThat(partial.status).isEqualTo(DebtStatus.PENDING);
        partial.ajustar(new BigDecimal("120.00"),new BigDecimal("120.00"));
        assertThat(partial.status).isEqualTo(DebtStatus.PENDING);

        Debt paid=debt(DebtStatus.PAID);
        paid.revertirImputacion(new BigDecimal("25.00"));
        assertThat(paid.status).isEqualTo(DebtStatus.PARTIALLY_PAID);

        Debt cancellable=debt(DebtStatus.PENDING);
        cancellable.anular();
        assertThat(cancellable.status).isEqualTo(DebtStatus.CANCELLED);
        assertThat(cancellable.admiteCobro()).isFalse();
    }

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
            arguments(DebtStatus.PENDING,"reverse","DEBT_NOT_REVERSIBLE"),
            arguments(DebtStatus.PARTIALLY_PAID,"annul","DEBT_NOT_CANCELLABLE"),
            arguments(DebtStatus.PAID,"pay","DEBT_NOT_PAYABLE"),
            arguments(DebtStatus.PAID,"adjust","DEBT_NOT_ADJUSTABLE"),
            arguments(DebtStatus.PAID,"annul","DEBT_NOT_CANCELLABLE"),
            arguments(DebtStatus.CANCELLED,"pay","DEBT_NOT_PAYABLE"),
            arguments(DebtStatus.CANCELLED,"reverse","DEBT_NOT_REVERSIBLE"),
            arguments(DebtStatus.CANCELLED,"adjust","DEBT_NOT_ADJUSTABLE"),
            arguments(DebtStatus.CANCELLED,"annul","DEBT_NOT_CANCELLABLE")
        );
    }

    private static void apply(Debt debt,String operation) {
        switch(operation) {
            case "pay" -> debt.imputar(BigDecimal.ONE);
            case "reverse" -> debt.revertirImputacion(BigDecimal.ONE);
            case "adjust" -> debt.ajustar(new BigDecimal("101.00"),new BigDecimal("101.00"));
            case "annul" -> debt.anular();
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private static Debt debt(DebtStatus status) {
        Debt debt=new Debt();debt.status=status;debt.currentAmount=new BigDecimal("100.00");
        debt.outstandingBalance=switch(status){case PENDING,CANCELLED->new BigDecimal("100.00");case PARTIALLY_PAID->new BigDecimal("50.00");case PAID->BigDecimal.ZERO.setScale(2);};
        return debt;
    }
}
