package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M4DueDatePolicyTests {
    @Test void composeEnvironmentNamesConfigureBothM4DueDatePolicies(){
        var legacyEnvironment=new StandardEnvironment();
        legacyEnvironment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("legacyCompose",
            Map.of("RENTAS_M4_DUEDAYS_PERMITFEE","14")));
        assertThat(new M4DueDatePolicy(legacyEnvironment)
            .dueDate(ExternalObligationType.PERMIT_FEE,OffsetDateTime.parse("2026-10-20T10:00:00-03:00")))
            .isEmpty();

        var environment=new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("compose",Map.of(
            "RENTAS_M4_DUE_DAYS_PERMIT_FEE","14",
            "RENTAS_M4_DUE_DAYS_COMMERCIAL_FINE","7")));
        var policy=new M4DueDatePolicy(environment);
        var occurredAt=OffsetDateTime.parse("2026-10-20T10:00:00-03:00");

        assertThat(policy.dueDate(ExternalObligationType.PERMIT_FEE,occurredAt))
            .contains(LocalDate.parse("2026-11-03"));
        assertThat(policy.dueDate(ExternalObligationType.COMMERCIAL_FINE,occurredAt))
            .contains(LocalDate.parse("2026-10-27"));
    }

    @Test void jsonOffsetNormalizationDoesNotChangeTheConfiguredDueDate(){
        var policy=new M4DueDatePolicy(new MockEnvironment()
            .withProperty("rentas.m4.due-days.permit-fee","14"));
        var original=OffsetDateTime.parse("2026-10-20T23:30:00-03:00");
        var normalized=original.withOffsetSameInstant(java.time.ZoneOffset.UTC);
        assertThat(policy.dueDate(ExternalObligationType.PERMIT_FEE,original))
            .contains(LocalDate.parse("2026-11-03"));
        assertThat(policy.dueDate(ExternalObligationType.PERMIT_FEE,normalized))
            .isEqualTo(policy.dueDate(ExternalObligationType.PERMIT_FEE,original));
        assertThat(policy.dueDate(ExternalObligationType.COMMERCIAL_FINE,original)).isEmpty();
    }

    @Test void malformedDeadlineIsRejectedInsteadOfInventingOne(){
        var policy=new M4DueDatePolicy(new MockEnvironment()
            .withProperty("rentas.m4.due-days.commercial-fine","thirty"));
        assertThatThrownBy(()->policy.dueDate(ExternalObligationType.COMMERCIAL_FINE,
            OffsetDateTime.parse("2026-10-20T10:00:00-03:00")))
            .isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.code).isEqualTo("INVALID_M4_DUE_DATE_POLICY"));
    }
}
