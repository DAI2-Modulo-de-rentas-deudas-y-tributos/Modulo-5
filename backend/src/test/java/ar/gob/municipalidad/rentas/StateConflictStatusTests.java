package ar.gob.municipalidad.rentas;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateConflictStatusTests {
    @ParameterizedTest
    @MethodSource("transitionConflictCodes")
    void knownLifecycleGuardCodesUseConflictStatus(String code) {
        assertThatThrownBy(() -> CatalogService.require(false, code, "transition not allowed"))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                org.assertj.core.api.Assertions.assertThat(error.code).isEqualTo(code);
                org.assertj.core.api.Assertions.assertThat(error.status).isEqualTo(409);
            });
    }

    @Test void semanticValidationRemainsUnprocessableEntity() {
        assertThatThrownBy(() -> CatalogService.require(false, "INVALID_VALIDITY_RANGE", "invalid range"))
            .isInstanceOfSatisfying(BusinessException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.status).isEqualTo(422));
    }

    static Stream<String> transitionConflictCodes() {
        return Stream.of(
            "TAXPAYER_BLOCKED", "DEBT_NOT_PAYABLE", "DEBT_NOT_BILLABLE", "BILL_NOT_PAYABLE",
            "DEBT_IN_ACTIVE_PAYMENT_PLAN", "DEBT_ALREADY_IN_PAYMENT_PLAN", "DEBT_ALREADY_IN_ACTIVE_PAYMENT_PLAN",
            "DEBT_NOT_ADJUSTABLE", "DEBT_NOT_ELIGIBLE_FOR_PLAN", "PAYMENT_NOT_ALLOCATABLE",
            "PAYMENT_BALANCE_CONVERTED_TO_CREDIT", "INSTALLMENT_NOT_PAYABLE", "PAYMENT_NOT_REVERSIBLE",
            "REVERSAL_ALREADY_EXISTS", "REVERSAL_NOT_PENDING", "REVERSAL_NOT_APPROVED", "ADJUSTMENT_NOT_PENDING",
            "RUN_NOT_DRAFT", "RUN_NOT_READY", "RUN_NOT_PENDING", "RUN_NOT_APPROVED", "RUN_CONFIGURATION_CHANGED",
            "INVALID_CONFIGURATION_TRANSITION", "CONFIGURATION_NOT_EDITABLE", "CONFIGURATION_NOT_ACTIVE",
            "INVALID_EXEMPTION_TRANSITION", "DOCUMENTATION_NOT_REQUIRED", "INVALID_PLAN_REQUEST_TRANSITION",
            "PLAN_DEBT_CHANGED", "PLAN_EXCEPTION_APPROVAL_REQUIRED", "PLAN_NOT_ACTIVE", "PLAN_NOT_DEFAULTED",
            "EXPIRATION_ALREADY_PENDING", "EXPIRATION_NOT_PENDING", "REFINANCING_ALREADY_PENDING",
            "INVALID_REFINANCING_TRANSITION", "REFINANCING_NOT_PENDING", "REFINANCING_EXCEPTION_APPROVAL_REQUIRED",
            "PLAN_NOT_REFINANCING_ELIGIBLE", "PLAN_BALANCE_CHANGED", "PLAN_WITHOUT_BALANCE", "MAX_REFINANCING_REACHED",
            "TICKET_ALREADY_CLOSED", "INTEGRATION_EVENT_NOT_REPROCESSABLE", "EXTERNAL_OBLIGATION_NOT_RETRYABLE",
            "RECONCILIATION_ALREADY_RESOLVED", "RECONCILIATION_PAYMENT_MISMATCH");
    }
}
