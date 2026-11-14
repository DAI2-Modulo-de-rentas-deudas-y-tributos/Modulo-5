package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
class PaymentIdempotencyService {
    private final TaxpayerRepository taxpayers;
    private final PaymentRepository payments;
    private final ObjectMapper json;

    PaymentIdempotencyService(TaxpayerRepository taxpayers, PaymentRepository payments, ObjectMapper json) {
        this.taxpayers = taxpayers;
        this.payments = payments;
        this.json = json;
    }

    Resolution resolve(ApiDtos.RegisterPaymentRequest request, PaymentOrigin origin, String value) {
        String key = normalize(value);
        if (key == null) return new Resolution(null, null, null);

        taxpayers.findByIdForUpdate(request.taxpayerId()).orElseThrow(() -> CatalogService.notFound("Contribuyente"));
        String fingerprint = fingerprint(request, origin);
        Optional<Payment> existing = payments.findByTaxpayerIdAndIdempotencyKey(request.taxpayerId(), key);
        if (existing.isPresent()) {
            Payment previous = existing.get();
            if (previous.origin != origin || !fingerprint.equals(previous.idempotencyFingerprint)) {
                throw new BusinessException("IDEMPOTENCY_KEY_REUSED", "La clave de idempotencia ya fue utilizada con otro contenido", 409);
            }
            return new Resolution(key, fingerprint, previous);
        }
        return new Resolution(key, fingerprint, null);
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key debe contener entre 1 y 128 caracteres alfanuméricos, punto, guion, guion bajo o dos puntos", 400);
        }
        return normalized;
    }

    private String fingerprint(ApiDtos.RegisterPaymentRequest request, PaymentOrigin origin) {
        List<ApiDtos.AllocationRequest> allocations = request.allocations() == null ? List.of() : request.allocations().stream()
            .map(a -> new ApiDtos.AllocationRequest(a.debtId(), a.installmentId(), a.amount().stripTrailingZeros())).toList();
        var normalized = new ApiDtos.RegisterPaymentRequest(request.taxpayerId(), request.billId(), request.paymentMethod(),
            request.amount().stripTrailingZeros(), allocations);
        try {
            byte[] content = json.valueToTree(new PaymentFingerprint(origin, normalized)).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private record PaymentFingerprint(PaymentOrigin origin, ApiDtos.RegisterPaymentRequest request) {}
    record Resolution(String key, String fingerprint, Payment existing) {}
}
