package ar.gob.municipalidad.rentas;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class MonetaryAmounts {
    private static final int SCALE = 2;

    private MonetaryAmounts() {}

    static BigDecimal normalize(BigDecimal amount) {
        return amount == null ? zero() : amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }
}
