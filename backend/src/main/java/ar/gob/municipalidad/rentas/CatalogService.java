package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Page;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
class CatalogService {
    private final TaxpayerRepository taxpayers; private final TaxConceptRepository concepts;
    private final TaxConfigurationRepository configurations; private final AuditService audit; private final CurrentIdentity identity;
    CatalogService(TaxpayerRepository taxpayers, TaxConceptRepository concepts, TaxConfigurationRepository configurations, AuditService audit, CurrentIdentity identity) {
        this.taxpayers=taxpayers; this.concepts=concepts; this.configurations=configurations; this.audit=audit; this.identity=identity;
    }
    @Transactional TaxpayerReference createTaxpayer(ApiDtos.CreateTaxpayerRequest r) {
        taxpayers.findByTaxpayerTypeAndExternalId(r.taxpayerType(),r.externalId()).ifPresent(x->{throw new BusinessException("TAXPAYER_ALREADY_EXISTS","La referencia del contribuyente ya existe",409);});
        TaxpayerReference x=new TaxpayerReference(); x.taxpayerType=r.taxpayerType(); x.externalId=r.externalId(); x.dni=r.dni(); x.cuit=r.cuit(); x.displayName=r.displayName(); x.externalStatus=TaxpayerStatus.ACTIVE; x.createdAt=x.updatedAt=OffsetDateTime.now(); return taxpayers.save(x);
    }
    @Transactional ApiDtos.CanonicalCitizenLinkResponse linkCanonicalCitizen(Long taxpayerId,ApiDtos.LinkCanonicalCitizenRequest r,String ifMatch) {
        require(r!=null&&r.m1CitizenId()!=null&&r.m1CitizenId()>0&&r.canonicalCitizenId()!=null
            &&r.confirmationReference()!=null&&!r.confirmationReference().isBlank()&&r.confirmationReference().length()<=200,
            "INVALID_CANONICAL_CITIZEN_LINK","La confirmación M1 es incompleta o inválida");
        TaxpayerReference taxpayer=taxpayers.findByIdForUpdate(taxpayerId).orElseThrow(()->notFound("Contribuyente"));
        EntityTags.requireIfMatch(EntityTags.taxpayer(ApiResponses.of(taxpayer)),ifMatch);
        requireConflict(taxpayer.taxpayerType==TaxpayerType.CITIZEN,"TAXPAYER_NOT_CITIZEN","La referencia no es un ciudadano M1");
        requireConflict(Objects.equals(taxpayer.externalId,String.valueOf(r.m1CitizenId())),"M1_CITIZEN_ID_MISMATCH","El ID M1 no coincide con la referencia recibida");
        TaxpayerReference existing=taxpayers.findByCanonicalCitizenId(r.canonicalCitizenId()).orElse(null);
        requireConflict(existing==null||existing.id.equals(taxpayer.id),"CANONICAL_CITIZEN_ID_ALREADY_LINKED","El UUID canónico ya está vinculado a otra referencia");
        requireConflict(taxpayer.canonicalCitizenId==null||taxpayer.canonicalCitizenId.equals(r.canonicalCitizenId()),"CANONICAL_CITIZEN_LINK_CONFLICT","La referencia ya tiene otro UUID canónico");
        if(taxpayer.canonicalCitizenId==null){
            UUID previous=taxpayer.canonicalCitizenId;taxpayer.canonicalCitizenId=r.canonicalCitizenId();taxpayer.updatedAt=OffsetDateTime.now();taxpayers.save(taxpayer);
            audit.recordSecurityEvent("CANONICAL_CITIZEN_ID_LINKED",identity.get().userId(),"SUPERVISOR",
                new CanonicalCitizenLinkAuditData(taxpayer.id,taxpayer.externalId,previous,taxpayer.canonicalCitizenId,r.m1CitizenId(),r.confirmationReference()));
        }
        return new ApiDtos.CanonicalCitizenLinkResponse(taxpayer.id,r.m1CitizenId(),taxpayer.canonicalCitizenId);
    }
    @Transactional TaxConcept createConcept(ApiDtos.CreateTaxConceptRequest r) {
        concepts.findByCode(r.code()).ifPresent(x->{throw new BusinessException("TAX_CONCEPT_CODE_EXISTS","El código del concepto ya existe",409);});
        TaxConcept x=new TaxConcept(); x.code=r.code(); x.name=r.name(); x.description=r.description(); x.type=r.type(); x.originModule=r.originModule(); x.active=true; x.createdAt=x.updatedAt=OffsetDateTime.now(); return concepts.save(x);
    }
    @Transactional TaxConcept updateConcept(Long id, ApiDtos.UpdateTaxConceptRequest r) { return updateConcept(id,r,null); }
    @Transactional TaxConcept updateConcept(Long id, ApiDtos.UpdateTaxConceptRequest r,String ifMatch) {
        TaxConcept x=concepts.findByIdForUpdate(id).orElseThrow(()->notFound("Concepto")); EntityTags.requireIfMatch(EntityTags.taxConcept(x),ifMatch);
        x.name=r.name(); x.description=r.description(); x.active=r.active(); x.updatedAt=OffsetDateTime.now(); return x;
    }
    @Transactional TaxConfiguration createConfiguration(ApiDtos.CreateTaxConfigurationRequest r) {
        concepts.findById(r.taxConceptId()).orElseThrow(()->notFound("Concepto")); validateConfiguration(r);
        int version=configurations.findFirstByTaxConceptIdOrderByVersionDesc(r.taxConceptId()).map(x->x.version+1).orElse(1);
        TaxConfiguration x=new TaxConfiguration(); x.taxConceptId=r.taxConceptId(); x.version=version; x.calculationType=r.calculationType(); x.rate=r.rate(); x.fixedAmount=r.fixedAmount(); x.minimumAmount=r.minimumAmount(); x.maximumAmount=r.maximumAmount(); x.partialPaymentAllowed=r.partialPaymentAllowed(); x.paymentPlanAllowed=r.paymentPlanAllowed(); x.validFrom=r.validFrom(); x.validUntil=r.validUntil(); x.status=TaxConfigurationStatus.DRAFT; x.createdBy=identity.get().userId(); x.createdAt=OffsetDateTime.now(); return configurations.save(x);
    }
    @Transactional TaxConfiguration submit(Long id) { return submit(id,null); }
    @Transactional TaxConfiguration submit(Long id,String ifMatch) { TaxConfiguration x=configurations.findByIdForUpdate(id).orElseThrow(()->notFound("Configuración")); EntityTags.requireIfMatch(EntityTags.taxConfiguration(x),ifMatch); require(x.status==TaxConfigurationStatus.DRAFT,"INVALID_CONFIGURATION_TRANSITION","Solo un borrador puede enviarse"); x.status=TaxConfigurationStatus.PENDING_APPROVAL;x.submittedBy=identity.get().userId();configurations.flush();return x; }
    @Transactional TaxConfiguration updateConfiguration(Long id,ApiDtos.UpdateTaxConfigurationRequest r){return updateConfiguration(id,r,null);}
    @Transactional TaxConfiguration updateConfiguration(Long id,ApiDtos.UpdateTaxConfigurationRequest r,String ifMatch){TaxConfiguration x=configurations.findByIdForUpdate(id).orElseThrow(()->notFound("Configuración"));EntityTags.requireIfMatch(EntityTags.taxConfiguration(x),ifMatch);require(x.status==TaxConfigurationStatus.DRAFT,"CONFIGURATION_NOT_EDITABLE","Sólo se edita una configuración borrador");CalculationType type=r.calculationType()==null?x.calculationType:r.calculationType();BigDecimal rate=r.rate()==null?x.rate:r.rate();BigDecimal fixed=r.fixedAmount()==null?x.fixedAmount:r.fixedAmount();BigDecimal minimum=r.minimumAmount()==null?x.minimumAmount:r.minimumAmount();BigDecimal maximum=r.maximumAmount()==null?x.maximumAmount:r.maximumAmount();boolean partial=r.partialPaymentAllowed()==null?x.partialPaymentAllowed:r.partialPaymentAllowed();boolean plan=r.paymentPlanAllowed()==null?x.paymentPlanAllowed:r.paymentPlanAllowed();LocalDate from=r.validFrom()==null?x.validFrom:r.validFrom();LocalDate until=r.validUntil()==null?x.validUntil:r.validUntil();validateConfiguration(new ApiDtos.CreateTaxConfigurationRequest(x.taxConceptId,type,rate,fixed,minimum,maximum,partial,plan,from,until));x.calculationType=type;x.rate=rate;x.fixedAmount=fixed;x.minimumAmount=minimum;x.maximumAmount=maximum;x.partialPaymentAllowed=partial;x.paymentPlanAllowed=plan;x.validFrom=from;x.validUntil=until;return x;}
    @Transactional TaxConfiguration approve(Long id) { return approve(id,null); }
    @Transactional TaxConfiguration approve(Long id,String ifMatch) {
        TaxConfiguration x=configurations.findByIdForUpdate(id).orElseThrow(()->notFound("Configuración")); EntityTags.requireIfMatch(EntityTags.taxConfiguration(x),ifMatch); require(x.status==TaxConfigurationStatus.PENDING_APPROVAL,"INVALID_CONFIGURATION_TRANSITION","La configuración no está pendiente");audit.requireDifferentActor("TaxConfiguration",x.id,x.createdBy,x.submittedBy);
        x.status=TaxConfigurationStatus.ACTIVE; x.approvedBy=identity.get().userId(); x.approvedAt=OffsetDateTime.now(); audit.record("TaxConfiguration",x.id,"TAX_CONFIGURATION_APPROVED",x);configurations.flush();return x;
    }
    @Transactional TaxConfiguration reject(Long id) { return reject(id,null); }
    @Transactional TaxConfiguration reject(Long id,String ifMatch) { TaxConfiguration x=configurations.findByIdForUpdate(id).orElseThrow(()->notFound("Configuración")); EntityTags.requireIfMatch(EntityTags.taxConfiguration(x),ifMatch); require(x.status==TaxConfigurationStatus.PENDING_APPROVAL,"INVALID_CONFIGURATION_TRANSITION","La configuración no está pendiente");audit.requireDifferentActor("TaxConfiguration",x.id,x.createdBy,x.submittedBy); x.status=TaxConfigurationStatus.REJECTED;configurations.flush();return x; }
    @Transactional TaxConfiguration deactivate(Long id,String reason){return deactivate(id,reason,null);}
    @Transactional TaxConfiguration deactivate(Long id,String reason,String ifMatch){TaxConfiguration x=configurations.findByIdForUpdate(id).orElseThrow(()->notFound("Configuración"));EntityTags.requireIfMatch(EntityTags.taxConfiguration(x),ifMatch);require(x.status==TaxConfigurationStatus.ACTIVE,"CONFIGURATION_NOT_ACTIVE","La configuración no está activa");x.status=TaxConfigurationStatus.INACTIVE;audit.record("TaxConfiguration",x.id,"TAX_CONFIGURATION_DEACTIVATED",Map.of("configuration",x,"reason",reason));configurations.flush();return x;}
    TaxConfiguration activeConfiguration(Long conceptId, LocalDate date) {
        return configurations.findAll().stream().filter(x->x.taxConceptId.equals(conceptId) && x.status==TaxConfigurationStatus.ACTIVE && !x.validFrom.isAfter(date) && (x.validUntil==null || !x.validUntil.isBefore(date))).max(Comparator.comparingInt(x->x.version)).orElseThrow(()->new BusinessException("NO_ACTIVE_TAX_CONFIGURATION","No existe configuración activa y vigente",422));
    }
    private TaxConfiguration config(Long id) { return configurations.findById(id).orElseThrow(()->notFound("Configuración")); }
    private void validateConfiguration(ApiDtos.CreateTaxConfigurationRequest r) {
        require(r.validUntil()==null || !r.validUntil().isBefore(r.validFrom()),"INVALID_VALIDITY_RANGE","La vigencia final no puede preceder a la inicial");
        require(r.minimumAmount()==null || r.maximumAmount()==null || r.minimumAmount().compareTo(r.maximumAmount())<=0,
            "INVALID_CONFIGURATION_RANGE","El importe mínimo no puede superar al importe máximo");
        require(r.calculationType()!=CalculationType.FIXED || r.fixedAmount()!=null,"FIXED_AMOUNT_REQUIRED","El cálculo fijo requiere importe");
        require(r.calculationType()!=CalculationType.PERCENTAGE || r.rate()!=null,"RATE_REQUIRED","El cálculo porcentual requiere alícuota");
    }
    static BusinessException notFound(String entity) { return new BusinessException("NOT_FOUND",entity+" no encontrado",404); }
    static void requireConflict(boolean ok,String code,String message) { if(!ok) throw new BusinessException(code,message,409); }
    static void require(boolean ok,String code,String message) { if(!ok) throw new BusinessException(code,message,422); }
}
