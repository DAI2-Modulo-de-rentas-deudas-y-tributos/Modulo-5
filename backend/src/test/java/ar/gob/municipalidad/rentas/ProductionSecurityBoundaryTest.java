package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityBoundaryTest {
    @Test
    void productionRejectsDemoModeEvenWhenConfigurationTriesToEnableIt() {
        assertThatThrownBy(() -> new ProductionSecurityBoundary(true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RENTAS_SECURITY_DEV_MODE");
        assertThatCode(() -> new ProductionSecurityBoundary(false)).doesNotThrowAnyException();
    }
}
