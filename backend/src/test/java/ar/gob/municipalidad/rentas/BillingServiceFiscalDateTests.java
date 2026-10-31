package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingServiceFiscalDateTests {
    @Test
    void pricingUsesBuenosAiresFiscalDateAcrossUtcMidnight() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));
        FiscalProcessingDatePolicy dates = new FiscalProcessingDatePolicy(clock);
        assertThat(dates.today()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)).isEqualTo(dates.today().plusDays(1));

        Bill bill = new Bill();
        bill.id = 1L;
        bill.number = "BILL-FISCAL-DATE";
        bill.taxpayerId = 2L;
        bill.totalAmount = BigDecimal.ZERO;
        bill.issueDate = dates.today();
        bill.dueDate = dates.today().plusDays(1);
        bill.status = BillStatus.ISSUED;
        bill.createdBy = "test";
        bill.createdAt = OffsetDateTime.parse("2026-10-29T12:00:00-03:00");

        BillRepository bills = mock(BillRepository.class);
        BillDebtRepository billDebts = mock(BillDebtRepository.class);
        LateChargeService lateCharges = mock(LateChargeService.class);
        when(bills.findById(1L)).thenReturn(Optional.of(bill));
        when(billDebts.findByBillIdOrderByIdAsc(1L)).thenReturn(List.of());

        BillingService service = new BillingService(bills, billDebts, mock(DebtRepository.class),
            mock(TaxpayerRepository.class), mock(CurrentIdentity.class), mock(LiquidationRepository.class),
            mock(AdjustmentRepository.class), lateCharges, dates);

        service.pricingDetail(1L);

        verify(lateCharges).previewIfApplicable(anyCollection(), eq(LocalDate.of(2026, 10, 29)));
    }
}
