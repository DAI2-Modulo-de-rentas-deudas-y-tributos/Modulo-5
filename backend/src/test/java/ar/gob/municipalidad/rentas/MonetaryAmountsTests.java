package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MonetaryAmountsTests {
    @Test void roundsFinalAmountsToTwoDecimalsUsingHalfUp() {
        assertThat(MonetaryAmounts.normalize(new BigDecimal("12.344"))).isEqualByComparingTo("12.34");
        assertThat(MonetaryAmounts.normalize(new BigDecimal("12.345"))).isEqualByComparingTo("12.35");
        assertThat(MonetaryAmounts.normalize(new BigDecimal("-1.235"))).isEqualByComparingTo("-1.24");
    }

    @Test void normalizesNullAndZeroToMonetaryZero() {
        assertThat(MonetaryAmounts.normalize(null)).isEqualTo(new BigDecimal("0.00"));
        assertThat(MonetaryAmounts.zero()).isEqualTo(new BigDecimal("0.00"));
    }
}
