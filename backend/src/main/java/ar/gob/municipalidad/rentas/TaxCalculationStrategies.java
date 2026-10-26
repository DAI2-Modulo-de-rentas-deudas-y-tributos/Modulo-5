package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

interface TaxCalculationStrategy {
    CalculationType type();
    BigDecimal calculate(TaxConfiguration configuration, BigDecimal taxableBase);
}

@Component
class FixedTaxCalculationStrategy implements TaxCalculationStrategy {
    @Override public CalculationType type() { return CalculationType.FIXED; }
    @Override public BigDecimal calculate(TaxConfiguration configuration, BigDecimal taxableBase) { return configuration.fixedAmount; }
}

@Component
class PercentageTaxCalculationStrategy implements TaxCalculationStrategy {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override public CalculationType type() { return CalculationType.PERCENTAGE; }
    @Override public BigDecimal calculate(TaxConfiguration configuration, BigDecimal taxableBase) {
        return taxableBase.multiply(configuration.rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}

@Component
class ExternalTaxCalculationStrategy implements TaxCalculationStrategy {
    @Override public CalculationType type() { return CalculationType.EXTERNAL; }
    @Override public BigDecimal calculate(TaxConfiguration configuration, BigDecimal taxableBase) {
        throw new BusinessException("EXTERNAL_CONFIGURATION_NOT_LIQUIDATABLE", "Un importe externo no se liquida en M5", 422);
    }
}
