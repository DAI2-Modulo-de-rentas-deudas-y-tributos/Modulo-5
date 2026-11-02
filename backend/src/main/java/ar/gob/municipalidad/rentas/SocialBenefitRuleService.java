package ar.gob.municipalidad.rentas;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
class SocialBenefitRuleService {
    private final SocialBenefitRuleRepository rules;
    private final TaxConceptRepository concepts;
    private final SocialBenefitRepository benefits;
    private final CurrentIdentity identity;
    private final AuditService audit;

    SocialBenefitRuleService(SocialBenefitRuleRepository rules,TaxConceptRepository concepts,
            SocialBenefitRepository benefits,CurrentIdentity identity,AuditService audit){
        this.rules=rules;this.concepts=concepts;this.benefits=benefits;this.identity=identity;this.audit=audit;
    }

    @Transactional SocialBenefitRule propose(RuleProposal input){
        requireRole("RENTAS");
        CatalogService.require(input!=null&&input.programId()!=null&&input.conceptCode()!=null&&
            !input.conceptCode().isBlank()&&input.percentage()!=null&&input.validFrom()!=null,
            "INVALID_SOCIAL_RULE","Programa, concepto, porcentaje y fecha inicial son obligatorios");
        CatalogService.require(input.percentage().signum()>=0&&input.percentage().compareTo(new BigDecimal("100"))<=0,
            "INVALID_SOCIAL_RULE","El porcentaje debe estar entre 0 y 100");
        CatalogService.require(input.validUntil()==null||!input.validUntil().isBefore(input.validFrom()),
            "INVALID_VALIDITY_RANGE","La vigencia de la regla es inválida");
        TaxConcept concept=concepts.findByCode(input.conceptCode()).orElseThrow(()->CatalogService.notFound("Concepto"));
        CatalogService.require(concept.active,"TAX_CONCEPT_NOT_ACTIVE","El concepto tributario no está activo");
        int version=rules.findFirstByProgramIdAndTaxConceptIdOrderByVersionDesc(input.programId(),concept.id)
            .map(previous->previous.version+1).orElse(1);
        SocialBenefitRule rule=new SocialBenefitRule();rule.programId=input.programId();rule.taxConceptId=concept.id;
        rule.version=version;rule.percentage=input.percentage();rule.validFrom=input.validFrom();rule.validUntil=input.validUntil();
        rule.enabled=false;rule.status=SocialBenefitRuleStatus.DRAFT;rule.proposedBy=identity.get().userId();
        rule.createdAt=OffsetDateTime.now();rules.save(rule);audit.record("SocialBenefitRule",rule.id,"SOCIAL_RULE_PROPOSED",rule);
        return rule;
    }

    @Transactional SocialBenefitRule submit(Long id){
        requireRole("RENTAS");SocialBenefitRule rule=getForUpdate(id);
        CatalogService.require(rule.status==SocialBenefitRuleStatus.DRAFT,"INVALID_SOCIAL_RULE_TRANSITION","Sólo un borrador puede enviarse");
        rule.status=SocialBenefitRuleStatus.PENDING_APPROVAL;rule.submittedBy=identity.get().userId();audit.record("SocialBenefitRule",id,"SOCIAL_RULE_SUBMITTED",rule);return rule;
    }

    @Transactional SocialBenefitRule approve(Long id){
        requireRole("SUPERVISOR");SocialBenefitRule rule=getForUpdate(id);
        CatalogService.require(rule.status==SocialBenefitRuleStatus.PENDING_APPROVAL,"INVALID_SOCIAL_RULE_TRANSITION","La regla no espera aprobación");
        audit.requireDifferentActor("SocialBenefitRule",id,rule.proposedBy,rule.submittedBy);
        UUID actor=identity.get().integrationActorId();
        CatalogService.require(actor!=null,"INTEGRATION_ACTOR_ID_REQUIRED","El supervisor autenticado necesita un UUID persistente");
        boolean overlap=rules.findByProgramIdAndTaxConceptId(rule.programId,rule.taxConceptId).stream()
            .anyMatch(other->!other.id.equals(rule.id)&&other.status==SocialBenefitRuleStatus.APPROVED&&other.enabled&&
                (other.validUntil==null||!other.validUntil.isBefore(rule.validFrom))&&
                (rule.validUntil==null||!rule.validUntil.isBefore(other.validFrom)));
        CatalogService.require(!overlap,"OVERLAPPING_SOCIAL_RULE","Ya existe una regla aprobada y vigente para el programa y tributo");
        rule.status=SocialBenefitRuleStatus.APPROVED;rule.enabled=true;rule.approvedBy=identity.get().userId();
        rule.approvedActorId=actor;rule.approvedAt=OffsetDateTime.now();
        benefits.findAll().stream().filter(b->rule.programId.toString().equals(b.externalProgramId)&&
            "PENDING_TAX_RULE".equals(b.resolutionStatus)&&b.taxpayerId!=null&&b.validFrom!=null&&
            (rule.validUntil==null||!rule.validUntil.isBefore(b.validFrom))&&
            (b.validUntil==null||!rule.validFrom.isAfter(b.validUntil)))
            .forEach(b->b.resolutionStatus="RESOLVED");
        audit.record("SocialBenefitRule",id,"SOCIAL_RULE_APPROVED",rule);return rule;
    }

    @Transactional SocialBenefitRule disable(Long id){
        requireRole("SUPERVISOR");SocialBenefitRule rule=getForUpdate(id);
        CatalogService.require(rule.status==SocialBenefitRuleStatus.APPROVED&&rule.enabled,
            "INVALID_SOCIAL_RULE_TRANSITION","La regla no está habilitada");
        rule.enabled=false;rule.disabledAt=OffsetDateTime.now();
        List<SocialBenefitRule> remaining=rules.findByProgramId(rule.programId).stream()
            .filter(other->other.status==SocialBenefitRuleStatus.APPROVED&&other.enabled).toList();
        benefits.findAll().stream()
            .filter(b->rule.programId.toString().equals(b.externalProgramId)&&"RESOLVED".equals(b.resolutionStatus))
            .filter(b->remaining.stream().noneMatch(other->b.validFrom!=null&&
                (other.validUntil==null||!other.validUntil.isBefore(b.validFrom))&&
                (b.validUntil==null||!other.validFrom.isAfter(b.validUntil))))
            .forEach(b->b.resolutionStatus="PENDING_TAX_RULE");
        audit.record("SocialBenefitRule",id,"SOCIAL_RULE_DISABLED",rule);
        return rule;
    }

    @Transactional(readOnly=true) List<SocialBenefitRule> list(){return rules.findAll();}
    private SocialBenefitRule getForUpdate(Long id){return rules.findByIdForUpdate(id).orElseThrow(()->CatalogService.notFound("Regla social"));}
    private void requireRole(String role){if(!identity.hasRole(role))throw new BusinessException("ACCESS_DENIED","No tiene permisos para esta regla",403);}

    record RuleProposal(UUID programId,String conceptCode,BigDecimal percentage,LocalDate validFrom,LocalDate validUntil) {}
}

@RestController
@RequestMapping("/api/v1/social-benefit-rules")
class SocialBenefitRuleController {
    private final SocialBenefitRuleService rules;
    private final IdempotencyService idempotency;
    SocialBenefitRuleController(SocialBenefitRuleService rules,IdempotencyService idempotency){this.rules=rules;this.idempotency=idempotency;}
    @GetMapping @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','AUDITOR')")
    List<ApiDtos.SocialBenefitRuleResponse> list(){return rules.list().stream().map(ApiResponses::of).toList();}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('RENTAS')")
    ApiDtos.SocialBenefitRuleResponse propose(@jakarta.validation.Valid @RequestBody ApiDtos.CreateSocialBenefitRuleRequest input,@RequestHeader(name="Idempotency-Key",required=false) String key){
        return idempotency.execute("POST:/social-benefit-rules",key,input,ApiDtos.SocialBenefitRuleResponse.class,()->ApiResponses.of(rules.propose(new SocialBenefitRuleService.RuleProposal(
            input.programId(),input.conceptCode(),input.percentage(),input.validFrom(),input.validUntil()))));
    }
    @PostMapping("/{id}/submit") @PreAuthorize("hasRole('RENTAS')")
    ApiDtos.SocialBenefitRuleResponse submit(@PathVariable Long id,@RequestHeader(name="Idempotency-Key",required=false) String key){return idempotency.execute("POST:/social-benefit-rules/{id}/submit:"+id,key,null,ApiDtos.SocialBenefitRuleResponse.class,()->ApiResponses.of(rules.submit(id)));}
    @PostMapping("/{id}/approve") @PreAuthorize("hasRole('SUPERVISOR')")
    ApiDtos.SocialBenefitRuleResponse approve(@PathVariable Long id,@RequestHeader(name="Idempotency-Key",required=false) String key){return idempotency.execute("POST:/social-benefit-rules/{id}/approve:"+id,key,null,ApiDtos.SocialBenefitRuleResponse.class,()->ApiResponses.of(rules.approve(id)));}
    @PostMapping("/{id}/disable") @PreAuthorize("hasRole('SUPERVISOR')")
    ApiDtos.SocialBenefitRuleResponse disable(@PathVariable Long id,@RequestHeader(name="Idempotency-Key",required=false) String key){return idempotency.execute("POST:/social-benefit-rules/{id}/disable:"+id,key,null,ApiDtos.SocialBenefitRuleResponse.class,()->ApiResponses.of(rules.disable(id)));}
}
