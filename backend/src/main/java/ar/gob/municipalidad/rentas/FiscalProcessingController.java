package ar.gob.municipalidad.rentas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
class FiscalProcessingController {
    record CalculationDateRequest(LocalDate calculationDate) {}
    record LateChargeResponse(Long applicationId,Long debtId,LocalDate calculationDate,int daysOverdue,BigDecimal principal,
        BigDecimal surchargeRate,BigDecimal surchargeAmount,BigDecimal interestRate,BigDecimal interestAmount,
        BigDecimal previousAdjustments,BigDecimal totalAdjustment,BigDecimal updatedTotal,String ruleCode,boolean applied) {}
    record DueDateRequest(LocalDate processingDate) {}
    record DueDateResponse(Long id,OffsetDateTime processedAt,int debtsScanned,int debtsOverdue,int adjustmentsGenerated,
        int installmentsOverdue,int plansDefaulted,int skippedAlreadyProcessed,int errors) {}
    record ReconciliationItemRequest(@NotBlank String externalReference,@NotBlank String taxpayerDocument,
        @NotNull @Positive BigDecimal amount,@NotNull OffsetDateTime paidAt) {}
    record ImportReconciliationRequest(@NotBlank String batchReference,@NotEmpty List<@Valid ReconciliationItemRequest> items) {}
    record ReconciliationItemResponse(Long id,Long batchId,String externalReference,String taxpayerDocument,BigDecimal amount,
        OffsetDateTime paidAt,ReconciliationStatus status,Long matchedPaymentId,String resolutionReason,String resolvedBy,OffsetDateTime resolvedAt) {}
    record ReconciliationBatchResponse(Long id,String batchReference,int totalItems,int reconciledItems,int observedItems,
        int notFoundItems,String importedBy,OffsetDateTime importedAt,List<ReconciliationItemResponse> items) {}
    record ResolveReconciliationRequest(@NotNull Long paymentId,@NotBlank String reason) {}

    private final LateChargeService lateCharges;private final DueDateService dueDates;private final ReconciliationService reconciliations;
    FiscalProcessingController(LateChargeService lateCharges,DueDateService dueDates,ReconciliationService reconciliations){this.lateCharges=lateCharges;this.dueDates=dueDates;this.reconciliations=reconciliations;}

    @PostMapping("/debts/{id}/late-charge-preview") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','AUDITOR')")
    LateChargeResponse preview(@PathVariable Long id,@RequestBody(required=false) CalculationDateRequest body){return lateCharges.preview(id,date(body));}
    @PostMapping("/debts/{id}/late-charges") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR')")
    LateChargeResponse apply(@PathVariable Long id,@RequestBody(required=false) CalculationDateRequest body){return lateCharges.apply(id,date(body));}
    @PostMapping("/administration/process-due-dates") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR')")
    DueDateResponse process(@RequestBody(required=false) DueDateRequest body){return dueDates.process(body==null||body.processingDate()==null?LocalDate.now():body.processingDate());}
    @PostMapping("/payment-reconciliations/batches") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','CASHIER')")
    ReconciliationBatchResponse importBatch(@Valid @RequestBody ImportReconciliationRequest body){return reconciliations.importBatch(body);}
    @GetMapping("/payment-reconciliations/batches/{id}") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','CASHIER','AUDITOR')")
    ReconciliationBatchResponse batch(@PathVariable Long id){return reconciliations.get(id);}
    @GetMapping("/payment-reconciliations/observed") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','CASHIER','AUDITOR')")
    Page<ReconciliationItemResponse> observed(Pageable pageable){return reconciliations.observed(pageable);}
    @PostMapping("/payment-reconciliations/items/{id}/resolve") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR')")
    ReconciliationItemResponse resolve(@PathVariable Long id,@Valid @RequestBody ResolveReconciliationRequest body){return reconciliations.resolve(id,body);}
    private LocalDate date(CalculationDateRequest body){return body==null||body.calculationDate()==null?LocalDate.now():body.calculationDate();}
}

@org.springframework.stereotype.Service
class LateChargeService {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    private final DebtRepository debts;private final LateChargeRuleRepository rules;private final LateChargeApplicationRepository applications;
    private final AdjustmentRepository adjustments;private final AuditService audit;private final CurrentIdentity identity;
    LateChargeService(DebtRepository debts,LateChargeRuleRepository rules,LateChargeApplicationRepository applications,
        AdjustmentRepository adjustments,AuditService audit,CurrentIdentity identity){this.debts=debts;this.rules=rules;this.applications=applications;this.adjustments=adjustments;this.audit=audit;this.identity=identity;}

    FiscalProcessingController.LateChargeResponse preview(Long debtId,LocalDate date){return calculate(debt(debtId),rule(date),date,false,null);}
    @Transactional FiscalProcessingController.LateChargeResponse apply(Long debtId,LocalDate date){
        Debt debt=debts.findByIdForUpdate(debtId).orElseThrow(()->CatalogService.notFound("Deuda"));LateChargeRule rule=rule(date);
        Optional<LateChargeApplication> existing=applications.findByDebtIdAndRuleIdAndCalculationDate(debt.id,rule.id,date);
        if(existing.isPresent())return response(existing.get(),rule.code,false);
        FiscalProcessingController.LateChargeResponse value=calculate(debt,rule,date,false,null);
        LateChargeApplication application=new LateChargeApplication();application.debtId=debt.id;application.ruleId=rule.id;application.calculationDate=date;
        application.daysOverdue=value.daysOverdue();application.principal=value.principal();application.surchargeRate=value.surchargeRate();
        application.surchargeAmount=value.surchargeAmount();application.interestRate=value.interestRate();application.interestAmount=value.interestAmount();
        application.previousAdjustments=value.previousAdjustments();application.totalAdjustment=value.totalAdjustment();application.updatedTotal=value.updatedTotal();
        application.appliedBy=identity.get().userId();application.appliedAt=OffsetDateTime.now();applications.save(application);
        BigDecimal previous=debt.currentAmount;createAdjustment(debt,AdjustmentType.SURCHARGE,value.surchargeAmount(),previous,"Recargo automático "+rule.code);
        previous=previous.add(value.surchargeAmount());createAdjustment(debt,AdjustmentType.INTEREST,value.interestAmount(),previous,"Interés automático "+rule.code);
        debt.currentAmount=debt.currentAmount.add(value.totalAdjustment());debt.outstandingBalance=debt.outstandingBalance.add(value.totalAdjustment());debt.updatedAt=OffsetDateTime.now();
        audit.record("LateChargeApplication",application.id,"LATE_CHARGE_APPLIED",application);return response(application,rule.code,true);
    }
    private FiscalProcessingController.LateChargeResponse calculate(Debt debt,LateChargeRule rule,LocalDate date,boolean applied,Long id){
        CatalogService.require(debt.status!=DebtStatus.PAID&&debt.status!=DebtStatus.CANCELLED&&debt.outstandingBalance.signum()>0,"DEBT_NOT_CHARGEABLE","La deuda no admite recargos");
        CatalogService.require(date.isAfter(debt.dueDate),"DEBT_NOT_OVERDUE","La deuda no está vencida a la fecha indicada");
        int days=Math.toIntExact(ChronoUnit.DAYS.between(debt.dueDate,date));BigDecimal principal=money(debt.outstandingBalance);
        BigDecimal surcharge=money(principal.multiply(rule.surchargeRate).divide(HUNDRED,8,RoundingMode.HALF_UP));
        BigDecimal interest=money(principal.multiply(rule.dailyInterestRate).multiply(BigDecimal.valueOf(days)).divide(HUNDRED,8,RoundingMode.HALF_UP));
        BigDecimal previous=money(applications.findByDebtId(debt.id).stream().map(x->x.totalAdjustment).reduce(BigDecimal.ZERO,BigDecimal::add));
        BigDecimal total=surcharge.add(interest);return new FiscalProcessingController.LateChargeResponse(id,debt.id,date,days,principal,rule.surchargeRate,surcharge,rule.dailyInterestRate,interest,previous,total,principal.add(total),rule.code,applied);
    }
    private void createAdjustment(Debt debt,AdjustmentType type,BigDecimal amount,BigDecimal previous,String reason){if(amount.signum()==0)return;AdjustmentRequest a=new AdjustmentRequest();a.debtId=debt.id;a.type=type;a.amount=amount;a.reason=reason;a.status=AdjustmentStatus.APPROVED;a.requestedBy=a.resolvedBy=identity.get().userId();a.requestedAt=a.resolvedAt=OffsetDateTime.now();a.resolutionReason="Procesamiento automático de vencimiento";a.previousDebtAmount=previous;a.newDebtAmount=previous.add(amount);adjustments.save(a);}
    private Debt debt(Long id){return debts.findById(id).orElseThrow(()->CatalogService.notFound("Deuda"));}
    private LateChargeRule rule(LocalDate date){List<LateChargeRule> found=rules.findApplicable(date,PageRequest.of(0,1));CatalogService.require(!found.isEmpty(),"LATE_CHARGE_RULE_NOT_FOUND","No existe regla de recargos vigente");return found.get(0);}
    private BigDecimal money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP);}
    private FiscalProcessingController.LateChargeResponse response(LateChargeApplication x,String code,boolean applied){return new FiscalProcessingController.LateChargeResponse(x.id,x.debtId,x.calculationDate,x.daysOverdue,x.principal,x.surchargeRate,x.surchargeAmount,x.interestRate,x.interestAmount,x.previousAdjustments,x.totalAdjustment,x.updatedTotal,code,applied);}
}

@org.springframework.stereotype.Service
class DueDateService {
    private final DueDateProcessingRepository runs;private final DebtRepository debts;private final InstallmentRepository installments;
    private final PaymentPlanRepository plans;private final PaymentPlanConfigurationRepository configurations;private final PlanExpirationRepository expirations;
    private final LateChargeService lateCharges;private final CurrentIdentity identity;private final AuditService audit;
    DueDateService(DueDateProcessingRepository runs,DebtRepository debts,InstallmentRepository installments,PaymentPlanRepository plans,
        PaymentPlanConfigurationRepository configurations,PlanExpirationRepository expirations,LateChargeService lateCharges,CurrentIdentity identity,AuditService audit){this.runs=runs;this.debts=debts;this.installments=installments;this.plans=plans;this.configurations=configurations;this.expirations=expirations;this.lateCharges=lateCharges;this.identity=identity;this.audit=audit;}
    @Transactional FiscalProcessingController.DueDateResponse process(LocalDate date){
        Optional<DueDateProcessing> prior=runs.findByProcessingDate(date);if(prior.isPresent())return response(prior.get());
        DueDateProcessing run=new DueDateProcessing();run.processingDate=date;run.processedAt=OffsetDateTime.now();run.processedBy=identity.get().userId();
        List<Debt> allDebts=debts.findAll();run.debtsScanned=allDebts.size();
        for(Debt debt:allDebts){if(debt.status==DebtStatus.PAID||debt.status==DebtStatus.CANCELLED||debt.outstandingBalance.signum()<=0||!debt.dueDate.isBefore(date))continue;run.debtsOverdue++;try{var result=lateCharges.apply(debt.id,date);if(result.applied())run.adjustmentsGenerated+=(result.surchargeAmount().signum()>0?1:0)+(result.interestAmount().signum()>0?1:0);else run.skippedAlreadyProcessed++;}catch(BusinessException ex){if("DEBT_NOT_CHARGEABLE".equals(ex.code))run.skippedAlreadyProcessed++;else run.errors++;}}
        for(Installment i:installments.findAll())if(i.status!=InstallmentStatus.PAID&&i.status!=InstallmentStatus.CANCELLED&&i.outstandingAmount.signum()>0&&i.dueDate.isBefore(date)){if(i.status!=InstallmentStatus.OVERDUE){i.status=InstallmentStatus.OVERDUE;run.installmentsOverdue++;}}
        for(PaymentPlan plan:plans.findAll())if(plan.status==PaymentPlanStatus.ACTIVE&&plan.configurationId!=null){PaymentPlanConfiguration config=configurations.findById(plan.configurationId).orElse(null);if(config==null)continue;long overdue=installments.findByPaymentPlanIdOrderByNumber(plan.id).stream().filter(i->i.status==InstallmentStatus.OVERDUE).count();if(overdue>config.maxOverdueInstallments&&!expirations.existsByPaymentPlanIdAndStatus(plan.id,PlanExpirationStatus.PENDING_APPROVAL)){PlanExpirationRequest e=new PlanExpirationRequest();e.paymentPlanId=plan.id;e.reason="Caducidad detectada por procesamiento de vencimientos del "+date;e.status=PlanExpirationStatus.PENDING_APPROVAL;e.requestedBy=identity.get().userId();e.requestedAt=OffsetDateTime.now();expirations.save(e);run.plansDefaulted++;audit.record("PlanExpirationRequest",e.id,"PLAN_DEFAULT_DETECTED",e);}}
        runs.save(run);audit.record("DueDateProcessing",run.id,"DUE_DATES_PROCESSED",run);return response(run);
    }
    private FiscalProcessingController.DueDateResponse response(DueDateProcessing x){return new FiscalProcessingController.DueDateResponse(x.id,x.processedAt,x.debtsScanned,x.debtsOverdue,x.adjustmentsGenerated,x.installmentsOverdue,x.plansDefaulted,x.skippedAlreadyProcessed,x.errors);}
}

@org.springframework.stereotype.Service
class ReconciliationService {
    private static final ZoneId MUNICIPAL_ZONE=ZoneId.of("America/Argentina/Buenos_Aires");
    private final ElectronicReconciliationBatchRepository batches;private final ElectronicReconciliationItemRepository items;
    private final TaxpayerRepository taxpayers;private final PaymentRepository payments;private final CurrentIdentity identity;private final AuditService audit;
    ReconciliationService(ElectronicReconciliationBatchRepository batches,ElectronicReconciliationItemRepository items,TaxpayerRepository taxpayers,PaymentRepository payments,CurrentIdentity identity,AuditService audit){this.batches=batches;this.items=items;this.taxpayers=taxpayers;this.payments=payments;this.identity=identity;this.audit=audit;}
    @Transactional FiscalProcessingController.ReconciliationBatchResponse importBatch(FiscalProcessingController.ImportReconciliationRequest request){
        Optional<ElectronicReconciliationBatch> existing=batches.findByExternalBatchReference(request.batchReference());if(existing.isPresent())return get(existing.get().id);
        CatalogService.require(request.items().stream().map(FiscalProcessingController.ReconciliationItemRequest::externalReference).distinct().count()==request.items().size(),"DUPLICATE_RECONCILIATION_REFERENCE","El lote repite referencias externas");
        for(var input:request.items())CatalogService.require(items.findByExternalReference(input.externalReference()).isEmpty(),"DUPLICATE_RECONCILIATION_REFERENCE","La referencia externa ya fue importada");
        ElectronicReconciliationBatch batch=new ElectronicReconciliationBatch();batch.externalBatchReference=request.batchReference();batch.totalItems=request.items().size();batch.importedBy=identity.get().userId();batch.importedAt=OffsetDateTime.now();batches.save(batch);
        for(var input:request.items()){ElectronicReconciliationItem item=new ElectronicReconciliationItem();item.batchId=batch.id;item.externalReference=input.externalReference();item.taxpayerDocument=input.taxpayerDocument();item.amount=input.amount().setScale(2,RoundingMode.HALF_UP);item.paidAt=input.paidAt();match(item);items.save(item);}
        recount(batch);audit.record("ElectronicReconciliationBatch",batch.id,"ELECTRONIC_RECONCILIATION_IMPORTED",batch);return get(batch.id);
    }
    FiscalProcessingController.ReconciliationBatchResponse get(Long id){ElectronicReconciliationBatch batch=batches.findById(id).orElseThrow(()->CatalogService.notFound("Lote de conciliación"));List<FiscalProcessingController.ReconciliationItemResponse> detail=items.findByBatchId(id,Pageable.unpaged()).getContent().stream().map(this::response).toList();return new FiscalProcessingController.ReconciliationBatchResponse(batch.id,batch.externalBatchReference,batch.totalItems,batch.reconciledItems,batch.observedItems,batch.notFoundItems,batch.importedBy,batch.importedAt,detail);}
    Page<FiscalProcessingController.ReconciliationItemResponse> observed(Pageable pageable){return items.findByStatusIn(List.of(ReconciliationStatus.OBSERVED,ReconciliationStatus.NOT_FOUND),pageable).map(this::response);}
    @Transactional FiscalProcessingController.ReconciliationItemResponse resolve(Long id,FiscalProcessingController.ResolveReconciliationRequest request){ElectronicReconciliationItem item=items.findById(id).orElseThrow(()->CatalogService.notFound("Ítem de conciliación"));CatalogService.require(item.status!=ReconciliationStatus.CONCILIATED,"RECONCILIATION_ALREADY_RESOLVED","El ítem ya está conciliado");Payment payment=payments.findById(request.paymentId()).orElseThrow(()->CatalogService.notFound("Pago"));TaxpayerReference taxpayer=resolveTaxpayer(item.taxpayerDocument);CatalogService.require(payment.status==PaymentStatus.CONFIRMED&&payment.taxpayerId.equals(taxpayer.id),"RECONCILIATION_PAYMENT_MISMATCH","El pago no pertenece al contribuyente o no está confirmado");item.status=ReconciliationStatus.CONCILIATED;item.matchedPaymentId=payment.id;item.resolutionReason=request.reason();item.resolvedBy=identity.get().userId();item.resolvedAt=OffsetDateTime.now();ElectronicReconciliationBatch batch=batches.findById(item.batchId).orElseThrow();recount(batch);audit.record("ElectronicReconciliationItem",item.id,"ELECTRONIC_RECONCILIATION_RESOLVED",item);return response(item);}
    private void match(ElectronicReconciliationItem item){TaxpayerReference taxpayer=findTaxpayer(item.taxpayerDocument).orElse(null);if(taxpayer==null){item.status=ReconciliationStatus.NOT_FOUND;return;}LocalDate paidDate=item.paidAt.atZoneSameInstant(MUNICIPAL_ZONE).toLocalDate();List<Payment> candidates=payments.findByTaxpayerId(taxpayer.id).stream().filter(p->p.status==PaymentStatus.CONFIRMED&&p.amount.compareTo(item.amount)==0&&p.paidAt.atZoneSameInstant(MUNICIPAL_ZONE).toLocalDate().equals(paidDate)).toList();if(candidates.size()==1){item.status=ReconciliationStatus.CONCILIATED;item.matchedPaymentId=candidates.get(0).id;}else item.status=ReconciliationStatus.OBSERVED;}
    private Optional<TaxpayerReference> findTaxpayer(String document){return taxpayers.findByTaxpayerTypeAndDni(TaxpayerType.CITIZEN,document).or(()->taxpayers.findByTaxpayerTypeAndCuit(TaxpayerType.ORGANIZATION,document));}
    private TaxpayerReference resolveTaxpayer(String document){return findTaxpayer(document).orElseThrow(()->new BusinessException("RECONCILIATION_TAXPAYER_NOT_FOUND","No existe el contribuyente del ítem",422));}
    private void recount(ElectronicReconciliationBatch batch){List<ElectronicReconciliationItem> all=items.findByBatchId(batch.id,Pageable.unpaged()).getContent();batch.reconciledItems=(int)all.stream().filter(x->x.status==ReconciliationStatus.CONCILIATED).count();batch.observedItems=(int)all.stream().filter(x->x.status==ReconciliationStatus.OBSERVED).count();batch.notFoundItems=(int)all.stream().filter(x->x.status==ReconciliationStatus.NOT_FOUND).count();}
    private FiscalProcessingController.ReconciliationItemResponse response(ElectronicReconciliationItem x){return new FiscalProcessingController.ReconciliationItemResponse(x.id,x.batchId,x.externalReference,x.taxpayerDocument,x.amount,x.paidAt,x.status,x.matchedPaymentId,x.resolutionReason,x.resolvedBy,x.resolvedAt);}
}
