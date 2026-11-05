package ar.gob.municipalidad.rentas;

import java.math.BigDecimal;

sealed interface DebtLifecycleState permits PendingDebt, PartiallyPaidDebt, PaidDebt, CancelledDebt {
    DebtStatus status();

    static DebtLifecycleState from(DebtStatus status) {
        return switch (status) {
            case PENDING -> new PendingDebt();
            case PARTIALLY_PAID -> new PartiallyPaidDebt();
            case PAID -> new PaidDebt();
            case CANCELLED -> new CancelledDebt();
        };
    }

    default boolean admiteCobro(BigDecimal outstanding) { return false; }
    default boolean admiteAjuste() { return false; }
    default DebtLifecycleState imputar(BigDecimal outstandingAfter) { throw conflict("DEBT_NOT_PAYABLE", "La deuda no admite pagos"); }
    default DebtLifecycleState revertirImputacion(BigDecimal outstandingAfter, BigDecimal currentAmount) { throw conflict("DEBT_NOT_REVERSIBLE", "La deuda no admite reversiones de imputación"); }
    default DebtLifecycleState ajustar(BigDecimal paidAmount, BigDecimal outstandingAfter) { throw conflict("DEBT_NOT_ADJUSTABLE", "La deuda no admite ajustes"); }
    default DebtLifecycleState anular(BigDecimal paidAmount) { throw conflict("DEBT_NOT_CANCELLABLE", "La deuda no admite anulación"); }

    private static BusinessException conflict(String code, String message) { return new BusinessException(code, message, 409); }
    static DebtLifecycleState afterPayment(BigDecimal outstanding) { return outstanding.signum()==0?new PaidDebt():new PartiallyPaidDebt(); }
    static DebtLifecycleState afterAdjustment(BigDecimal paid, BigDecimal outstanding) {
        return outstanding.signum()==0?new PaidDebt():paid.signum()>0?new PartiallyPaidDebt():new PendingDebt();
    }
    static DebtLifecycleState afterReversal(BigDecimal outstanding, BigDecimal current) {
        return outstanding.compareTo(current)==0?new PendingDebt():new PartiallyPaidDebt();
    }
}

record PendingDebt() implements DebtLifecycleState {
    @Override public DebtStatus status() { return DebtStatus.PENDING; }
    @Override public boolean admiteCobro(BigDecimal outstanding) { return outstanding!=null&&outstanding.signum()>0; }
    @Override public boolean admiteAjuste() { return true; }
    @Override public DebtLifecycleState imputar(BigDecimal outstandingAfter) { return DebtLifecycleState.afterPayment(outstandingAfter); }
    @Override public DebtLifecycleState ajustar(BigDecimal paidAmount, BigDecimal outstandingAfter) { return DebtLifecycleState.afterAdjustment(paidAmount,outstandingAfter); }
    @Override public DebtLifecycleState anular(BigDecimal paidAmount) {
        if(paidAmount.signum()!=0)throw new BusinessException("DEBT_NOT_CANCELLABLE","Una deuda con pagos no admite anulación",409);
        return new CancelledDebt();
    }
}

record PartiallyPaidDebt() implements DebtLifecycleState {
    @Override public DebtStatus status() { return DebtStatus.PARTIALLY_PAID; }
    @Override public boolean admiteCobro(BigDecimal outstanding) { return outstanding!=null&&outstanding.signum()>0; }
    @Override public boolean admiteAjuste() { return true; }
    @Override public DebtLifecycleState imputar(BigDecimal outstandingAfter) { return DebtLifecycleState.afterPayment(outstandingAfter); }
    @Override public DebtLifecycleState revertirImputacion(BigDecimal outstandingAfter, BigDecimal currentAmount) { return DebtLifecycleState.afterReversal(outstandingAfter,currentAmount); }
    @Override public DebtLifecycleState ajustar(BigDecimal paidAmount, BigDecimal outstandingAfter) { return DebtLifecycleState.afterAdjustment(paidAmount,outstandingAfter); }
}

record PaidDebt() implements DebtLifecycleState {
    @Override public DebtStatus status() { return DebtStatus.PAID; }
    @Override public DebtLifecycleState revertirImputacion(BigDecimal outstandingAfter, BigDecimal currentAmount) { return DebtLifecycleState.afterReversal(outstandingAfter,currentAmount); }
}

record CancelledDebt() implements DebtLifecycleState {
    @Override public DebtStatus status() { return DebtStatus.CANCELLED; }
}
