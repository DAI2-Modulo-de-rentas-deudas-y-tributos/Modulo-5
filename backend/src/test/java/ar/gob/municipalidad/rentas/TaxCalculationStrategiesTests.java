package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxCalculationStrategiesTests {
    private final FixedTaxCalculationStrategy fixed = new FixedTaxCalculationStrategy();
    private final PercentageTaxCalculationStrategy percentage = new PercentageTaxCalculationStrategy();
    private final ExternalTaxCalculationStrategy external = new ExternalTaxCalculationStrategy();

    @Test
    void fixedStrategyReturnsConfiguredAmountWithoutChangingItsScale() {
        TaxConfiguration configuration = new TaxConfiguration();
        configuration.fixedAmount = new BigDecimal("12.345");

        assertThat(fixed.type()).isEqualTo(CalculationType.FIXED);
        assertThat(fixed.calculate(configuration, new BigDecimal("100"))).isEqualTo(new BigDecimal("12.345"));
    }

    @Test
    void percentageStrategyPreservesTwoDecimalHalfUpRounding() {
        TaxConfiguration configuration = new TaxConfiguration();
        configuration.rate = new BigDecimal("2");

        assertThat(percentage.type()).isEqualTo(CalculationType.PERCENTAGE);
        assertThat(percentage.calculate(configuration, new BigDecimal("100.25")))
            .isEqualTo(new BigDecimal("2.01"));
    }

    @Test
    void externalStrategyKeepsExistingBusinessErrorContract() {
        TaxConfiguration configuration = new TaxConfiguration();

        assertThat(external.type()).isEqualTo(CalculationType.EXTERNAL);
        assertThatThrownBy(() -> external.calculate(configuration, BigDecimal.ONE))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.code).isEqualTo("EXTERNAL_CONFIGURATION_NOT_LIQUIDATABLE");
                assertThat(error.getMessage()).isEqualTo("Un importe externo no se liquida en M5");
                assertThat(error.status).isEqualTo(422);
            });
    }
}
