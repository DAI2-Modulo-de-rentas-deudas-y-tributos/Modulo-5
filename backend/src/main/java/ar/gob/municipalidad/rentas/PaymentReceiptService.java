package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
class PaymentReceiptService {
    private final PaymentRepository payments;
    private final CurrentIdentity identity;

    PaymentReceiptService(PaymentRepository payments, CurrentIdentity identity) {
        this.payments = payments;
        this.identity = identity;
    }

    String nextReceiptNumber() { return "REC-" + UUID.randomUUID(); }

    @Transactional(readOnly = true)
    ApiDtos.ReceiptResponse get(Long id) {
        Payment payment = payments.findById(id).orElseThrow(() -> CatalogService.notFound("Pago"));
        identity.requireOwnership(payment.taxpayerId);
        return new ApiDtos.ReceiptResponse(payment.receiptNumber, payment.id, payment.taxpayerId,
            payment.amount, payment.paidAt, payment.status.name());
    }
}
