package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

final class ConfirmedIntegrationTestFixtures {
    private ConfirmedIntegrationTestFixtures() {}

    static TaxpayerReference registerCitizen(ConfirmedM1Consumer consumer,ObjectMapper json,UUID citizenId,
            String dni,String cuil,String name,String lastname) {
        var details=new ConfirmedInboundEvents.M1CitizenRegisteredDetails(dni,cuil,name,lastname,
            LocalDate.of(1990,1,1),"ACTIVE",null);
        var data=new ConfirmedInboundEvents.M1CitizenUpdatedData(citizenId,"REGISTERED",json.valueToTree(details),
            new ConfirmedInboundEvents.UpdatedBy("SYSTEM","test"));
        var event=new ConfirmedInboundEvents.M1CoreEnvelope<>(UUID.randomUUID(),"citizenUpdated","1.0",
            OffsetDateTime.now(),"ciudadanos",UUID.randomUUID(),null,data);
        return consumer.consumeCitizen(event);
    }

    static SocialBenefitRule approveRule(SocialBenefitRuleService rules,UUID programId,String conceptCode,
            BigDecimal percentage,LocalDate validFrom,LocalDate validUntil) {
        SocialBenefitRule proposed=rules.propose(new SocialBenefitRuleService.RuleProposal(
            programId,conceptCode,percentage,validFrom,validUntil));
        rules.submit(proposed.id);
        Authentication previous=SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("fixture-social-supervisor",null,UUID.randomUUID()),null,
            List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))));
        try {
            return rules.approve(proposed.id);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    static SocialBenefitReference approvedBenefit(ConfirmedM8Consumer consumer,UUID citizenId,UUID programId,
            LocalDate validFrom,LocalDate validUntil) {
        OffsetDateTime updatedAt=OffsetDateTime.now();
        var data=new ConfirmedInboundEvents.M8SocialBenefitData(UUID.randomUUID(),citizenId,UUID.randomUUID(),
            programId,UUID.randomUUID(),"Synthetic test program",UUID.randomUUID(),"TAX_EXEMPTION",
            "Synthetic approved tax benefit",null,"APPROVED",null,validFrom,validUntil,updatedAt);
        var event=new ConfirmedInboundEvents.M8SocialBenefitUpdatedEvent(UUID.randomUUID(),"socialBenefitUpdated",
            "1.0",updatedAt,"desarrollo-social",UUID.randomUUID(),null,data);
        return consumer.consume(event);
    }
}
