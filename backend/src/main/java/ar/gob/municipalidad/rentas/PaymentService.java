package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
class PaymentService {
    private final PaymentRepository payments;
    private final PaymentIdempotencyService idempotency;
    private final BillRepository bills;
    private final PaymentReceiptService receipts;
    private final CurrentIdentity identity;
    private final PaymentAllocationService allocations;

    PaymentService(PaymentRepository payments, PaymentIdempotencyService idempotency, BillRepository bills,
        PaymentReceiptService receipts, CurrentIdentity identity, PaymentAllocationService allocations) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.bills = bills;
        this.receipts = receipts;
        this.identity = identity;
        this.allocations = allocations;
    }

    @Transactional Payment register(ApiDtos.RegisterPaymentRequest request) { return register(request, PaymentOrigin.CASHIER, null); }
    @Transactional Payment register(ApiDtos.RegisterPaymentRequest request, String key) { return register(request, PaymentOrigin.CASHIER, key); }
    @Transactional Payment registerElectronic(ApiDtos.RegisterPaymentRequest request) { return register(request, PaymentOrigin.ELECTRONIC, null); }
    @Transactional Payment registerElectronic(ApiDtos.RegisterPaymentRequest request, String key) { return register(request, PaymentOrigin.ELECTRONIC, key); }

    private Payment register(ApiDtos.RegisterPaymentRequest request, PaymentOrigin origin, String key) {
        PaymentIdempotencyService.Resolution resolution = idempotency.resolve(request, origin, key);
        if (resolution.existing() != null) return resolution.existing();
        if (request.billId() != null) {
            Bill bill = bills.findById(request.billId()).orElseThrow(() -> CatalogService.notFound("Boleta"));
            CatalogService.require(bill.taxpayerId.equals(request.taxpayerId()), "BILL_TAXPAYER_MISMATCH", "La boleta pertenece a otro contribuyente");
            CatalogService.require(bill.status == BillStatus.ISSUED, "BILL_NOT_PAYABLE", "La boleta no admite pagos");
        }
        Payment payment = new Payment();
        payment.taxpayerId = request.taxpayerId();
        payment.billId = request.billId();
        payment.paymentMethod = request.paymentMethod();
        payment.amount = money(request.amount());
        payment.allocatedAmount = MonetaryAmounts.zero();
        payment.unallocatedAmount = payment.amount;
        payment.status = PaymentStatus.CONFIRMED;
        payment.allocationStatus = PaymentAllocationStatus.UNALLOCATED;
        payment.origin = origin;
        payment.idempotencyKey = resolution.key();
        payment.idempotencyFingerprint = resolution.fingerprint();
        payment.receiptNumber = receipts.nextReceiptNumber();
        payment.registeredBy = identity.get().userId();
        payment.paidAt = payment.createdAt = java.time.OffsetDateTime.now();
        payments.save(payment);
        allocations.applyInitial(payment, request.allocations());
        return payment;
    }

    @Transactional PaymentAllocation allocateExisting(Long paymentId, ApiDtos.AllocationRequest request) {
        return allocateExisting(paymentId, request, null);
    }

    @Transactional PaymentAllocation allocateExisting(Long paymentId, ApiDtos.AllocationRequest request, String ifMatch) {
        return allocations.allocateExisting(paymentId, request, ifMatch);
    }

    static BigDecimal money(BigDecimal amount) {
        CatalogService.require(amount != null && amount.signum() > 0, "INVALID_AMOUNT", "El importe debe ser mayor a cero");
        return MonetaryAmounts.normalize(amount);
    }
}
