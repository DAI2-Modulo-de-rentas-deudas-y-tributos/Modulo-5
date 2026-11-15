package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.Mockito.*;

class AdjustmentDomainEventsTests {
    @Test void approvalPublishesThroughTypedPortWithoutDependingOnPaymentService() {
        AdjustmentRepository adjustments=mock(AdjustmentRepository.class);
        DebtRepository debts=mock(DebtRepository.class);
        PaymentPlanDebtRepository planDebts=mock(PaymentPlanDebtRepository.class);
        PaymentPlanRepository plans=mock(PaymentPlanRepository.class);
        CurrentIdentity identity=mock(CurrentIdentity.class);
        AuditService audit=mock(AuditService.class);
        EventosDeDominio events=mock(EventosDeDominio.class);
        AdjustmentRequest request=new AdjustmentRequest();request.id=17L;request.debtId=29L;request.status=AdjustmentStatus.PENDING_APPROVAL;request.type=AdjustmentType.DISCOUNT;request.amount=new BigDecimal("10.00");
        Debt debt=new Debt();debt.id=29L;debt.currentAmount=new BigDecimal("100.00");debt.outstandingBalance=new BigDecimal("100.00");debt.status=DebtStatus.PENDING;
        when(adjustments.findByIdForUpdate(17L)).thenReturn(Optional.of(request));
        when(debts.findByIdForUpdate(29L)).thenReturn(Optional.of(debt));
        when(identity.get()).thenReturn(new AuthenticatedIdentity("supervisor",null));

        new AdjustmentService(adjustments,debts,planDebts,plans,identity,audit,events).approve(17L,"Aprobado");

        verify(events).deudaAjustada(debt,request);
        verifyNoMoreInteractions(events);
    }
}
