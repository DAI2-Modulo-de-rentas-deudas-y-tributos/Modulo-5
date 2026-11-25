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
class LiquidationService {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    private final TaxpayerRepository taxpayers; private final TaxConceptRepository concepts; private final TaxConfigurationRepository configurations;
    private final LiquidationRepository liquidations; private final LiquidationComponentRepository components; private final DebtRepository debts; private final ExemptionRepository exemptions;
    private final CatalogService catalog; private final AuditService audit; private final CurrentIdentity identity;private final SocialBenefitRepository benefits;private final SocialBenefitTaxConceptRepository benefitConcepts;private final SocialBenefitRuleRepository socialRules;
    private final DebtHistoryService debtHistory;private final AdjustmentRepository adjustments;private final AuditRepository auditEntries;private final ApiResponseService responses;
    private final Map<CalculationType,TaxCalculationStrategy> calculationStrategies;
    LiquidationService(TaxpayerRepository taxpayers, TaxConceptRepository concepts, TaxConfigurationRepository configurations, LiquidationRepository liquidations, LiquidationComponentRepository components, DebtRepository debts, ExemptionRepository exemptions, CatalogService catalog, AuditService audit, CurrentIdentity identity,SocialBenefitRepository benefits,SocialBenefitTaxConceptRepository benefitConcepts,SocialBenefitRuleRepository socialRules,DebtHistoryService debtHistory,AdjustmentRepository adjustments,AuditRepository auditEntries,ApiResponseService responses,List<TaxCalculationStrategy> calculationStrategies) {
        this.taxpayers=taxpayers; this.concepts=concepts; this.configurations=configurations; this.liquidations=liquidations; this.components=components; this.debts=debts; this.exemptions=exemptions; this.catalog=catalog; this.audit=audit; this.identity=identity;this.benefits=benefits;this.benefitConcepts=benefitConcepts;this.socialRules=socialRules;this.debtHistory=debtHistory;this.adjustments=adjustments;this.auditEntries=auditEntries;this.responses=responses;this.calculationStrategies=calculationStrategies.stream().collect(java.util.stream.Collectors.toMap(TaxCalculationStrategy::type,java.util.function.Function.identity()));
    }
    ApiDtos.LiquidationPreview preview(ApiDtos.LiquidationRequest r) {
        taxpayers.findById(r.taxpayerId()).orElseThrow(()->CatalogService.notFound("Contribuyente")); concepts.findById(r.taxConceptId()).orElseThrow(()->CatalogService.notFound("Concepto"));
        LocalDate fiscalDate=fiscalDate(r.period());
        // La configuración vigente se resuelve al momento de emitir; la exención,
        // en cambio, se evalúa contra el período fiscal de la liquidación.
        TaxConfiguration c=catalog.activeConfiguration(r.taxConceptId(),LocalDate.now());
        BigDecimal base=calculate(c,r.taxableBase()); Reduction reduction=reduction(r.taxpayerId(),r.taxConceptId(),base,fiscalDate);
        BigDecimal zero=MonetaryAmounts.zero(); BigDecimal discount=reduction.type==LiquidationComponentType.SOCIAL_BENEFIT?reduction.amount:zero;
        BigDecimal exemption=reduction.type==LiquidationComponentType.EXEMPTION?reduction.amount:zero;
        List<ApiDtos.LiquidationComponentResponse> detail=new ArrayList<>();
        detail.add(new ApiDtos.LiquidationComponentResponse(null,LiquidationComponentType.BASE,"TaxConfiguration",c.id.toString(),"Importe base según configuración v"+c.version,base));
        if(reduction.amount.signum()>0)detail.add(new ApiDtos.LiquidationComponentResponse(null,reduction.type,reduction.sourceType,reduction.sourceId,reduction.description,reduction.amount,reduction.sourceBenefitId,reduction.socialRuleId,reduction.socialRuleVersion));
        return new ApiDtos.LiquidationPreview(c.id,c.version,base,discount,exemption,zero,zero,MonetaryAmounts.normalize(base.subtract(discount).subtract(exemption)),detail);
    }
    @Transactional Liquidation create(ApiDtos.LiquidationRequest r) {
        CatalogService.require(!liquidations.existsByTaxpayerIdAndTaxConceptIdAndPeriod(r.taxpayerId(),r.taxConceptId(),r.period()),"DUPLICATE_LIQUIDATION","Ya existe una liquidación para el contribuyente, concepto y período");
        ApiDtos.LiquidationPreview p=preview(r); TaxConfiguration c=configurations.findById(p.taxConfigurationId()).orElseThrow();
        Liquidation l=new Liquidation(); l.taxpayerId=r.taxpayerId(); l.taxConceptId=r.taxConceptId(); l.taxConfigurationId=c.id; l.configurationVersion=c.version; l.period=r.period(); l.taxableBase=r.taxableBase(); l.baseAmount=p.baseAmount(); l.discountAmount=p.discountAmount(); l.exemptionAmount=p.exemptionAmount(); l.surchargeAmount=p.surchargeAmount(); l.interestAmount=p.interestAmount(); l.finalAmount=p.finalAmount(); l.dueDate=r.dueDate(); l.status="ISSUED"; l.createdBy=identity.get().userId(); l.issuedAt=OffsetDateTime.now(); liquidations.save(l);
        for(ApiDtos.LiquidationComponentResponse source:p.components()){LiquidationComponent component=new LiquidationComponent();component.liquidationId=l.id;component.type=source.type();component.sourceType=source.sourceType();component.sourceId=source.sourceId();component.description=source.description();component.amount=source.amount();component.sourceBenefitId=source.sourceBenefitId();component.socialRuleId=source.socialRuleId();component.socialRuleVersion=source.socialRuleVersion();components.save(component);}
        if(l.finalAmount.signum()>0){Debt d=new Debt(); d.taxpayerId=l.taxpayerId; d.taxConceptId=l.taxConceptId; d.originType=DebtOriginType.LIQUIDATION; d.liquidationId=l.id; d.configurationId=c.id; d.originalAmount=d.currentAmount=d.outstandingBalance=l.finalAmount; d.dueDate=l.dueDate; d.status=DebtStatus.PENDING; d.createdAt=d.updatedAt=OffsetDateTime.now(); debts.save(d);}
        audit.record("Liquidation",l.id,"LIQUIDATION_CREATED",l); return l;
    }
    private BigDecimal calculate(TaxConfiguration c, BigDecimal taxableBase) {
        BigDecimal amount=calculationStrategies.get(c.calculationType).calculate(c,taxableBase);
        if(c.minimumAmount!=null && amount.compareTo(c.minimumAmount)<0) amount=c.minimumAmount;
        if(c.maximumAmount!=null && amount.compareTo(c.maximumAmount)>0) amount=c.maximumAmount;
        return MonetaryAmounts.normalize(amount);
    }
    ApiDtos.LiquidationResponse response(Liquidation l){return new ApiDtos.LiquidationResponse(l.id,l.taxpayerId,l.taxConceptId,l.taxConfigurationId,l.configurationVersion,l.period,l.taxableBase,l.baseAmount,l.discountAmount,l.exemptionAmount,l.surchargeAmount,l.interestAmount,l.finalAmount,l.dueDate,l.status,l.createdBy,l.issuedAt,components.findByLiquidationIdOrderById(l.id).stream().map(this::response).toList());}
    @Transactional(readOnly=true) ApiDtos.LiquidationDetailResponse detail(Long id){
        Liquidation l=liquidations.findById(id).orElseThrow(()->CatalogService.notFound("Liquidación"));ApiDtos.LiquidationResponse base=response(l);Optional<Debt> related=debts.findByLiquidationId(id);
        ApiDtos.DebtResponse debt=related.map(responses::debt).orElse(null);List<ApiDtos.LiquidationHistoryEntryResponse> history=new ArrayList<>();
        auditEntries.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc("Liquidation",id.toString()).forEach(entry->history.add(new ApiDtos.LiquidationHistoryEntryResponse("LIQUIDATION_CHANGE",entry.occurredAt,l.status,entry.action,entry.userId,null,"LIQUIDATION",id.toString(),null)));
        related.ifPresent(value->debtHistory.get(value.id).entries().forEach(entry->history.add(new ApiDtos.LiquidationHistoryEntryResponse(entry.type(),entry.date(),entry.status(),entry.action(),null,null,entry.referenceType(),entry.referenceId(),entry.amount()))));history.sort(Comparator.comparing(ApiDtos.LiquidationHistoryEntryResponse::at).thenComparing(ApiDtos.LiquidationHistoryEntryResponse::type).thenComparing(ApiDtos.LiquidationHistoryEntryResponse::referenceId,Comparator.nullsFirst(String::compareTo)));
        List<ApiDtos.AdjustmentResponse> debtAdjustments=related.map(value->adjustments.findByDebtIdOrderByRequestedAtAscIdAsc(value.id).stream().map(ApiResponses::of).toList()).orElseGet(List::of);
        return new ApiDtos.LiquidationDetailResponse(base.id(),base.taxpayerId(),base.taxConceptId(),base.taxConfigurationId(),base.configurationVersion(),base.period(),base.taxableBase(),base.baseAmount(),base.discountAmount(),base.exemptionAmount(),base.surchargeAmount(),base.interestAmount(),base.finalAmount(),base.dueDate(),base.status(),base.createdBy(),base.issuedAt(),base.components(),new ApiDtos.LiquidationOriginResponse(DebtOriginType.LIQUIDATION,"M5","LIQUIDATION_CREATED",id.toString()),debt,"ASC",List.copyOf(history),debtAdjustments);
    }
    @Transactional(readOnly=true) Page<ApiDtos.LiquidationResponse> responses(Page<Liquidation> page){if(page.isEmpty())return page.map(x->response(x,List.of()));Map<Long,List<LiquidationComponent>> grouped=components.findByLiquidationIdInOrderByLiquidationIdAscIdAsc(page.map(x->x.id).toList()).stream().collect(java.util.stream.Collectors.groupingBy(x->x.liquidationId));return page.map(x->response(x,grouped.getOrDefault(x.id,List.of())));}
    private ApiDtos.LiquidationResponse response(Liquidation l,List<LiquidationComponent> detail){return new ApiDtos.LiquidationResponse(l.id,l.taxpayerId,l.taxConceptId,l.taxConfigurationId,l.configurationVersion,l.period,l.taxableBase,l.baseAmount,l.discountAmount,l.exemptionAmount,l.surchargeAmount,l.interestAmount,l.finalAmount,l.dueDate,l.status,l.createdBy,l.issuedAt,detail.stream().map(this::response).toList());}
    private ApiDtos.LiquidationComponentResponse response(LiquidationComponent c){return new ApiDtos.LiquidationComponentResponse(c.id,c.type,c.sourceType,c.sourceId,c.description,c.amount,c.sourceBenefitId,c.socialRuleId,c.socialRuleVersion);}
    private Reduction reduction(Long taxpayerId,Long conceptId,BigDecimal base,LocalDate date) {
        Exemption exemption=exemptions.findByTaxpayerIdAndTaxConceptIdAndStatus(taxpayerId,conceptId,"ACTIVE").stream().filter(x->!x.validFrom.isAfter(date)&&(x.validUntil==null||!x.validUntil.isBefore(date))).max(Comparator.comparing(x->x.percentage)).orElse(null);
        BigDecimal exemptionRate=exemption==null?BigDecimal.ZERO:exemption.percentage;
        TaxpayerReference taxpayer=taxpayers.findById(taxpayerId).orElseThrow();
        SocialBenefitReference selected=null;SocialBenefitRule selectedRule=null;BigDecimal benefitRate=BigDecimal.ZERO;
        for(SocialBenefitReference benefit:benefits.findByTaxpayerId(taxpayerId)){
            if(benefit.calculatedStatus!=SocialBenefitStatus.ACTIVE||benefit.validFrom==null||
                benefit.validFrom.isAfter(date)||(benefit.validUntil!=null&&benefit.validUntil.isBefore(date)))continue;
            if(benefit.externalApplicationId==null){
                // The legacy adapter has an explicit amount and concept association, kept separate from M8.
                if(benefit.discountPercentage!=null&&benefitConcepts.existsBySocialBenefitIdAndTaxConceptId(benefit.id,conceptId)&&
                    benefit.discountPercentage.compareTo(benefitRate)>0){selected=benefit;selectedRule=null;benefitRate=benefit.discountPercentage;}
                continue;
            }
            if(!"APPROVED".equals(benefit.externalStatus)||!"TAX_EXEMPTION".equals(benefit.benefitType)||
                taxpayer.canonicalCitizenId==null||!taxpayer.canonicalCitizenId.toString().equals(benefit.externalCitizenId))continue;
            UUID programId;
            try{programId=UUID.fromString(benefit.externalProgramId);}catch(IllegalArgumentException ex){continue;}
            SocialBenefitRule rule=socialRules.findByProgramIdAndTaxConceptId(programId,conceptId).stream()
                .filter(x->x.status==SocialBenefitRuleStatus.APPROVED&&x.enabled&&
                    !x.validFrom.isAfter(date)&&(x.validUntil==null||!x.validUntil.isBefore(date)))
                .max(Comparator.comparing((SocialBenefitRule x)->x.percentage).thenComparingInt(x->x.version).thenComparing(x->x.id)).orElse(null);
            if(rule!=null&&rule.percentage.compareTo(benefitRate)>0){selected=benefit;selectedRule=rule;benefitRate=rule.percentage;}
        }
        if(exemptionRate.signum()==0&&benefitRate.signum()==0)return new Reduction(LiquidationComponentType.EXEMPTION,"None",null,"Sin reducción",MonetaryAmounts.zero(),null,null,null);
        if(exemptionRate.compareTo(benefitRate)>=0)return new Reduction(LiquidationComponentType.EXEMPTION,"Exemption",exemption.id.toString(),"Exención aprobada",base.multiply(exemptionRate).divide(HUNDRED,2,RoundingMode.HALF_UP),null,null,null);
        String benefitId=selectedRule==null?null:selected.externalBenefitId;
        return new Reduction(LiquidationComponentType.SOCIAL_BENEFIT,selectedRule==null?"SocialBenefitReference":"M8SocialBenefit",
            selectedRule==null?selected.id.toString():benefitId,"Beneficio social vigente",
            base.multiply(benefitRate).divide(HUNDRED,2,RoundingMode.HALF_UP),benefitId,
            selectedRule==null?null:selectedRule.id,selectedRule==null?null:selectedRule.version);
    }
    private LocalDate fiscalDate(String period) {
        // Se evalúa el último día del período: una vigencia que comenzó durante
        // el mes aplica a la liquidación de ese período, pero no se arrastra a uno
        // posterior si ya terminó.
        try { return YearMonth.parse(period).atEndOfMonth(); }
        catch (DateTimeException ex) { throw new BusinessException("INVALID_FISCAL_PERIOD","El período fiscal debe tener formato AAAA-MM",400); }
    }
    private record Reduction(LiquidationComponentType type,String sourceType,String sourceId,String description,BigDecimal amount,
        String sourceBenefitId,Long socialRuleId,Integer socialRuleVersion) {}
}
