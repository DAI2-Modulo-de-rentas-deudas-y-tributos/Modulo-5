package ar.gob.municipalidad.rentas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    record DueDateRunClaim(Long id,LocalDate processingDate,int attemptCount) {}
    record DueDateResponse(Long id,OffsetDateTime processedAt,int debtsScanned,int debtsOverdue,int adjustmentsGenerated,
        int installmentsOverdue,int plansDefaulted,int skippedAlreadyProcessed,int errors,DueDateRunStatus status,
        LocalDate processingDate,OffsetDateTime requestedAt,OffsetDateTime startedAt,OffsetDateTime completedAt,String lastErrorCode) {}
    record ReconciliationItemRequest(@NotBlank String externalReference,@NotBlank String taxpayerDocument,
        @NotNull @Positive BigDecimal amount,@NotNull OffsetDateTime paidAt) {}
    record ImportReconciliationRequest(@NotBlank String batchReference,@NotEmpty List<@NotNull @Valid ReconciliationItemRequest> items) {}
    record ReconciliationItemResponse(Long id,Long batchId,String externalReference,String taxpayerDocument,BigDecimal amount,
        OffsetDateTime paidAt,ReconciliationStatus status,Long matchedPaymentId,String resolutionReason,String resolvedBy,OffsetDateTime resolvedAt) {}
    record ReconciliationBatchResponse(Long id,String batchReference,int totalItems,int reconciledItems,int observedItems,
        int notFoundItems,String importedBy,OffsetDateTime importedAt,List<ReconciliationItemResponse> items) {}
    record ResolveReconciliationRequest(@NotNull Long paymentId,@NotBlank String reason) {}

    private final LateChargeService lateCharges;private final DueDateService dueDates;private final ReconciliationService reconciliations;private final FiscalProcessingDatePolicy dates;private final IdempotencyExecutor idempotency;
    FiscalProcessingController(LateChargeService lateCharges,DueDateService dueDates,ReconciliationService reconciliations,FiscalProcessingDatePolicy dates,IdempotencyExecutor idempotency){this.lateCharges=lateCharges;this.dueDates=dueDates;this.reconciliations=reconciliations;this.dates=dates;this.idempotency=idempotency;}

    @PostMapping("/debts/{id}/late-charge-preview") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','AUDITOR')")
    LateChargeResponse preview(@PathVariable Long id,@RequestBody(required=false) CalculationDateRequest body){LocalDate date=date(body);dates.requireNotFuture(date);return lateCharges.preview(id,date);}
    @PostMapping("/debts/{id}/late-charges") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR')")
    ResponseEntity<LateChargeResponse> apply(@PathVariable Long id,@RequestBody(required=false) CalculationDateRequest body,@RequestHeader(name="If-Match",required=false) String ifMatch,@RequestHeader(name="Idempotency-Key",required=false) String key){var result=idempotency.execute("POST:/debts/{id}/late-charges:"+id,key,ifMatch==null?body:new ApiDtos.IfMatchIdempotencyRequest(ifMatch,body),LateChargeResponse.class,()->{LocalDate date=date(body);dates.requireNotFuture(date);return lateCharges.apply(id,date,ifMatch);});return ResponseEntity.ok().eTag(lateCharges.debtTag(id)).body(result);}
    @ApiResponse(responseCode="202",description="El procesamiento fue aceptado para ejecución asíncrona",useReturnTypeSchema=true)
    @PostMapping("/administration/process-due-dates") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR')")
    ResponseEntity<DueDateResponse> process(@RequestBody(required=false) DueDateRequest body,@RequestHeader(name="Idempotency-Key",required=false) String key){DueDateResponse queued=idempotency.execute("POST:/administration/process-due-dates",key,body,DueDateResponse.class,()->{LocalDate date=dates.todayIfMissing(body==null?null:body.processingDate());dates.requireNotFuture(date);return dueDates.enqueue(date);});return ResponseEntity.accepted().body(dueDates.get(queued.id()));}
    @GetMapping("/administration/process-due-dates/{id}") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','AUDITOR')")
    DueDateResponse dueDateRun(@PathVariable Long id){return dueDates.get(id);}
    @PostMapping("/payment-reconciliations/batches") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','CASHIER')")
    ReconciliationBatchResponse importBatch(@Valid @RequestBody ImportReconciliationRequest body,@RequestHeader(name="Idempotency-Key",required=false) String key){return idempotency.execute("POST:/payment-reconciliations/batches",key,body,ReconciliationBatchResponse.class,()->reconciliations.importBatch(body));}
    @GetMapping("/payment-reconciliations/batches/{id}") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','CASHIER','AUDITOR')")
    ReconciliationBatchResponse batch(@PathVariable Long id){return reconciliations.get(id);}
    @GetMapping("/payment-reconciliations/observed") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR','CASHIER','AUDITOR')")
    Page<ReconciliationItemResponse> observed(Pageable pageable){return reconciliations.observed(pageable);}
    @PostMapping("/payment-reconciliations/items/{id}/resolve") @PreAuthorize("hasAnyRole('RENTAS','SUPERVISOR')")
    ReconciliationItemResponse resolve(@PathVariable Long id,@Valid @RequestBody ResolveReconciliationRequest body,@RequestHeader(name="Idempotency-Key",required=false) String key){return idempotency.execute("POST:/payment-reconciliations/items/{id}/resolve:"+id,key,body,ReconciliationItemResponse.class,()->reconciliations.resolve(id,body));}
    private LocalDate date(CalculationDateRequest body){return dates.todayIfMissing(body==null?null:body.calculationDate());}
}

@org.springframework.stereotype.Component
class FiscalProcessingDatePolicy {
    private final Clock clock;
    FiscalProcessingDatePolicy(Clock clock){this.clock=clock;}
    LocalDate today(){return LocalDate.now(clock);}
    LocalDate todayIfMissing(LocalDate date){return date==null?today():date;}
    void requireNotFuture(LocalDate date){if(date.isAfter(today()))throw new BusinessException("FUTURE_DATE_NOT_ALLOWED","La fecha de procesamiento no puede ser futura",422);}
}

@org.springframework.stereotype.Service
class LateChargeService {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    private final DebtRepository debts;private final LateChargeRuleRepository rules;private final LateChargeApplicationRepository applications;private final PaymentPlanRepository plans;private final PaymentPlanDebtRepository planDebts;
    private final AdjustmentRepository adjustments;private final AuditService audit;private final CurrentIdentity identity;private final FiscalProcessingDatePolicy dates;
    @org.springframework.beans.factory.annotation.Autowired
    LateChargeService(DebtRepository debts,LateChargeRuleRepository rules,LateChargeApplicationRepository applications,PaymentPlanRepository plans,PaymentPlanDebtRepository planDebts,
        AdjustmentRepository adjustments,AuditService audit,CurrentIdentity identity,FiscalProcessingDatePolicy dates){this.debts=debts;this.rules=rules;this.applications=applications;this.plans=plans;this.planDebts=planDebts;this.adjustments=adjustments;this.audit=audit;this.identity=identity;this.dates=dates;}
    LateChargeService(DebtRepository debts,LateChargeRuleRepository rules,LateChargeApplicationRepository applications,
        AdjustmentRepository adjustments,AuditService audit,CurrentIdentity identity,FiscalProcessingDatePolicy dates){this(debts,rules,applications,null,null,adjustments,audit,identity,dates);}

    FiscalProcessingController.LateChargeResponse preview(Long debtId,LocalDate date){
        dates.requireNotFuture(date);
        Debt debt=debt(debtId);LateChargeRule rule=rule(date);
        return applications.findByDebtIdAndRuleIdAndCalculationDate(debt.id,rule.id,date)
            .map(existing->response(existing,rule.code,false))
            .orElseGet(()->calculate(debt,rule,date,false,null));
    }
    @Transactional(readOnly=true) Map<Long,FiscalProcessingController.LateChargeResponse> previewIfApplicable(Collection<Debt> candidates,LocalDate date){List<Debt> chargeable=candidates.stream().filter(debt->debt.status!=DebtStatus.PAID&&debt.status!=DebtStatus.CANCELLED&&debt.outstandingBalance.signum()>0&&date.isAfter(debt.dueDate)).toList();if(chargeable.isEmpty())return Map.of();List<LateChargeRule> applicable=rules.findApplicable(date,PageRequest.of(0,1));if(applicable.isEmpty())return Map.of();LateChargeRule rule=applicable.get(0);List<LateChargeApplication> previous=applications.findByDebtIdIn(chargeable.stream().map(x->x.id).toList());Map<Long,BigDecimal> totals=previous.stream().collect(java.util.stream.Collectors.groupingBy(x->x.debtId,java.util.stream.Collectors.reducing(BigDecimal.ZERO,x->x.totalAdjustment,BigDecimal::add)));Set<Long> alreadyApplied=previous.stream().filter(x->x.ruleId.equals(rule.id)&&x.calculationDate.equals(date)).map(x->x.debtId).collect(java.util.stream.Collectors.toSet());Map<Long,FiscalProcessingController.LateChargeResponse> result=new HashMap<>();for(Debt debt:chargeable)if(!alreadyApplied.contains(debt.id))result.put(debt.id,calculate(debt,rule,date,false,null,money(totals.getOrDefault(debt.id,BigDecimal.ZERO))));return result;}
    boolean hasApplicableRule(LocalDate date){return !rules.findApplicable(date,PageRequest.of(0,1)).isEmpty();}
    @Transactional FiscalProcessingController.LateChargeResponse apply(Long debtId,LocalDate date){return apply(debtId,date,null);}
    @Transactional FiscalProcessingController.LateChargeResponse apply(Long debtId,LocalDate date,String ifMatch){
        dates.requireNotFuture(date);
        Debt debt=debts.findByIdForUpdate(debtId).orElseThrow(()->CatalogService.notFound("Deuda"));if(ifMatch!=null)EntityTags.requireIfMatch(debtTag(debt),ifMatch);LateChargeRule rule=rule(date);
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
    private FiscalProcessingController.LateChargeResponse calculate(Debt debt,LateChargeRule rule,LocalDate date,boolean applied,Long id){return calculate(debt,rule,date,applied,id,money(applications.findByDebtId(debt.id).stream().map(x->x.totalAdjustment).reduce(BigDecimal.ZERO,BigDecimal::add)));}
    private FiscalProcessingController.LateChargeResponse calculate(Debt debt,LateChargeRule rule,LocalDate date,boolean applied,Long id,BigDecimal previous){
        CatalogService.requireConflict(debt.admiteCobro(),"DEBT_NOT_CHARGEABLE","La deuda no admite recargos");
        CatalogService.require(date.isAfter(debt.dueDate),"DEBT_NOT_OVERDUE","La deuda no está vencida a la fecha indicada");
        int days=Math.toIntExact(ChronoUnit.DAYS.between(debt.dueDate,date));BigDecimal principal=money(debt.outstandingBalance);
        BigDecimal surcharge=money(principal.multiply(rule.surchargeRate).divide(HUNDRED,8,RoundingMode.HALF_UP));
        BigDecimal interest=money(principal.multiply(rule.dailyInterestRate).multiply(BigDecimal.valueOf(days)).divide(HUNDRED,8,RoundingMode.HALF_UP));
        BigDecimal total=surcharge.add(interest);return new FiscalProcessingController.LateChargeResponse(id,debt.id,date,days,principal,rule.surchargeRate,surcharge,rule.dailyInterestRate,interest,previous,total,principal.add(total),rule.code,applied);
    }
    private void createAdjustment(Debt debt,AdjustmentType type,BigDecimal amount,BigDecimal previous,String reason){if(amount.signum()==0)return;AdjustmentRequest a=new AdjustmentRequest();a.debtId=debt.id;a.type=type;a.amount=amount;a.reason=reason;a.status=AdjustmentStatus.APPROVED;a.requestedBy=a.resolvedBy=identity.get().userId();a.requestedAt=a.resolvedAt=OffsetDateTime.now();a.resolutionReason="Procesamiento automático de vencimiento";a.previousDebtAmount=previous;a.newDebtAmount=previous.add(amount);adjustments.save(a);}
    private Debt debt(Long id){return debts.findById(id).orElseThrow(()->CatalogService.notFound("Deuda"));}
    String debtTag(Long id){Debt debt=debts.findById(id).orElseThrow(()->CatalogService.notFound("Deuda"));return debtTag(debt);}
    private String debtTag(Debt debt){boolean inPlan=plans.existsByDebtIdAndStatus(debt.id,PaymentPlanStatus.ACTIVE)||planDebts.existsByDebtIdAndStatus(debt.id,PaymentPlanDebtStatus.ACTIVE);return EntityTags.debt(ApiResponses.of(debt,inPlan));}
    private LateChargeRule rule(LocalDate date){List<LateChargeRule> found=rules.findApplicable(date,PageRequest.of(0,1));CatalogService.require(!found.isEmpty(),"LATE_CHARGE_RULE_NOT_FOUND","No existe regla de recargos vigente");return found.get(0);}
    private BigDecimal money(BigDecimal value){return MonetaryAmounts.normalize(value);}
    private FiscalProcessingController.LateChargeResponse response(LateChargeApplication x,String code,boolean applied){return new FiscalProcessingController.LateChargeResponse(x.id,x.debtId,x.calculationDate,x.daysOverdue,x.principal,x.surchargeRate,x.surchargeAmount,x.interestRate,x.interestAmount,x.previousAdjustments,x.totalAdjustment,x.updatedTotal,code,applied);}
}

@org.springframework.stereotype.Service
class DueDateService {
    private final DueDateProcessingRepository runs;private final DueDateProcessor processor;private final FiscalProcessingDatePolicy dates;private final CurrentIdentity identity;
    DueDateService(DueDateProcessingRepository runs,DueDateProcessor processor,FiscalProcessingDatePolicy dates,CurrentIdentity identity){this.runs=runs;this.processor=processor;this.dates=dates;this.identity=identity;}
    @Transactional FiscalProcessingController.DueDateResponse enqueue(LocalDate date){
        dates.requireNotFuture(date);
        runs.enqueueIfAbsent(date,identity.get().userId(),OffsetDateTime.now());
        DueDateProcessing run=runs.findByProcessingDate(date).orElseThrow();
        if(run.status==DueDateRunStatus.FAILED){run.status=DueDateRunStatus.PENDING;run.attemptCount=0;run.lastErrorCode=null;run.nextAttemptAt=null;run.leaseExpiresAt=null;runs.save(run);}
        return response(run);
    }
    @Transactional(readOnly=true) FiscalProcessingController.DueDateResponse get(Long id){return runs.findById(id).map(this::response).orElseThrow(()->CatalogService.notFound("Corrida de vencimientos"));}
    @Transactional FiscalProcessingController.DueDateRunClaim claimNext(){
        OffsetDateTime now=OffsetDateTime.now();DueDateProcessing run=runs.lockNextClaimable(now).orElse(null);if(run==null||run.attemptCount>=3){if(run!=null){run.status=DueDateRunStatus.FAILED;run.leaseExpiresAt=null;run.lastErrorCode="DUE_DATE_PROCESSING_FAILED";runs.save(run);}return null;}
        run.status=DueDateRunStatus.PROCESSING;run.attemptCount++;run.startedAt=run.startedAt==null?now:run.startedAt;run.leaseExpiresAt=now.plusMinutes(30);run.nextAttemptAt=null;runs.saveAndFlush(run);
        return new FiscalProcessingController.DueDateRunClaim(run.id,run.processingDate,run.attemptCount);
    }
    @Transactional void recordFailure(Long id,int attemptCount,String errorCode){runs.findByIdForUpdate(id).filter(run->run.status==DueDateRunStatus.PROCESSING&&run.attemptCount==attemptCount).ifPresent(run->{run.status=run.attemptCount>=3?DueDateRunStatus.FAILED:DueDateRunStatus.PENDING;run.lastErrorCode=errorCode;run.leaseExpiresAt=null;run.nextAttemptAt=run.status==DueDateRunStatus.PENDING?OffsetDateTime.now().plusSeconds(run.attemptCount==1?5:30):null;runs.save(run);});}
    FiscalProcessingController.DueDateResponse process(LocalDate date){
        dates.requireNotFuture(date);
        try{return processor.process(date);}catch(DataIntegrityViolationException ex){return runs.findByProcessingDate(date).map(this::response).orElseThrow(()->ex);}
    }
    private FiscalProcessingController.DueDateResponse response(DueDateProcessing x){return new FiscalProcessingController.DueDateResponse(x.id,x.processedAt,x.debtsScanned,x.debtsOverdue,x.adjustmentsGenerated,x.installmentsOverdue,x.plansDefaulted,x.skippedAlreadyProcessed,x.errors,x.status,x.processingDate,x.requestedAt,x.startedAt,x.completedAt,x.lastErrorCode);}
}

@org.springframework.stereotype.Component
class DueDateProcessingWorker {
    private final DueDateService dueDates;private final DueDateProcessor processor;
    DueDateProcessingWorker(DueDateService dueDates,DueDateProcessor processor){this.dueDates=dueDates;this.processor=processor;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${rentas.due-date-processing-delay-ms:5000}",initialDelayString="${rentas.due-date-processing-initial-delay-ms:5000}")
    void processNext(){FiscalProcessingController.DueDateRunClaim claim=dueDates.claimNext();if(claim==null)return;var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new AuthenticatedIdentity("SYSTEM:DUE_DATE_PROCESSOR",null),null,List.of()));org.springframework.security.core.context.SecurityContextHolder.setContext(context);try{while(!processor.processNextBatch(claim.id(),claim.attemptCount())){}}catch(RuntimeException failure){dueDates.recordFailure(claim.id(),claim.attemptCount(),failure instanceof BusinessException business?business.code:"DUE_DATE_PROCESSING_FAILED");}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}}
}

@org.springframework.stereotype.Service
class DueDateProcessor {
    private static final int BATCH_SIZE=100;
    private static final List<DebtStatus> COLLECTIBLE=List.of(DebtStatus.PENDING,DebtStatus.PARTIALLY_PAID);
    private static final List<InstallmentStatus> OPEN_INSTALLMENTS=List.of(InstallmentStatus.PENDING,InstallmentStatus.PARTIALLY_PAID,InstallmentStatus.OVERDUE);
    private final DueDateProcessingRepository runs;private final DebtRepository debts;private final InstallmentRepository installments;
    private final PaymentPlanRepository plans;private final PaymentPlanConfigurationRepository configurations;private final PlanExpirationRepository expirations;
    private final LateChargeService lateCharges;private final ConfirmedIntegrationOutbox integrationOutbox;private final CurrentIdentity identity;private final AuditService audit;private final FiscalProcessingDatePolicy dates;
    @PersistenceContext private EntityManager entityManager;
    DueDateProcessor(DueDateProcessingRepository runs,DebtRepository debts,InstallmentRepository installments,PaymentPlanRepository plans,
        PaymentPlanConfigurationRepository configurations,PlanExpirationRepository expirations,LateChargeService lateCharges,ConfirmedIntegrationOutbox integrationOutbox,CurrentIdentity identity,AuditService audit,FiscalProcessingDatePolicy dates){this.runs=runs;this.debts=debts;this.installments=installments;this.plans=plans;this.configurations=configurations;this.expirations=expirations;this.lateCharges=lateCharges;this.integrationOutbox=integrationOutbox;this.identity=identity;this.audit=audit;this.dates=dates;}
    @Transactional FiscalProcessingController.DueDateResponse process(LocalDate date){
        dates.requireNotFuture(date);
        Optional<DueDateProcessing> prior=runs.findByProcessingDate(date);if(prior.isPresent()&&prior.get().status==DueDateRunStatus.COMPLETED)return response(prior.get());
        DueDateProcessing run=prior.orElseGet(()->{DueDateProcessing created=new DueDateProcessing();created.processingDate=date;created.requestedAt=OffsetDateTime.now();created.processedBy=identity.get().userId();return created;});
        run.status=DueDateRunStatus.PROCESSING;run.startedAt=run.startedAt==null?OffsetDateTime.now():run.startedAt;run= runs.saveAndFlush(run);Long runId=run.id;
        int debtsScanned=Math.toIntExact(debts.count()),debtsOverdue=0,adjustmentsGenerated=0,installmentsOverdue=0,plansDefaulted=0,skipped=0,errors=0;
        boolean hasLateChargeRule=lateCharges.hasApplicableRule(date);long afterId=0;
        while(true){List<Debt> batch=debts.findByStatusInAndOutstandingBalanceGreaterThanAndDueDateLessThanAndIdGreaterThanOrderByIdAsc(COLLECTIBLE,BigDecimal.ZERO,date,afterId,PageRequest.of(0,BATCH_SIZE));if(batch.isEmpty())break;
            for(Debt debt:batch){debtsOverdue++;integrationOutbox.overdueDebt(debt,date);if(!hasLateChargeRule){errors++;continue;}try{var result=lateCharges.apply(debt.id,date);if(result.applied())adjustmentsGenerated+=(result.surchargeAmount().signum()>0?1:0)+(result.interestAmount().signum()>0?1:0);else skipped++;}catch(BusinessException ex){if("DEBT_NOT_CHARGEABLE".equals(ex.code))skipped++;else errors++;}}
            afterId=batch.get(batch.size()-1).id;flushAndClear();
        }
        afterId=0;
        while(true){List<Installment> batch=installments.findByStatusInAndOutstandingAmountGreaterThanAndDueDateLessThanAndIdGreaterThanOrderByIdAsc(OPEN_INSTALLMENTS,BigDecimal.ZERO,date,afterId,PageRequest.of(0,BATCH_SIZE));if(batch.isEmpty())break;
            for(Installment installment:batch)if(installment.status!=InstallmentStatus.OVERDUE){installment.status=InstallmentStatus.OVERDUE;installmentsOverdue++;}
            afterId=batch.get(batch.size()-1).id;flushAndClear();
        }
        afterId=0;
        while(true){List<PaymentPlan> batch=plans.findByStatusAndConfigurationIdIsNotNullAndIdGreaterThanOrderByIdAsc(PaymentPlanStatus.ACTIVE,afterId,PageRequest.of(0,BATCH_SIZE));if(batch.isEmpty())break;
            List<Long> planIds=batch.stream().map(plan->plan.id).toList();Map<Long,PaymentPlanConfiguration> configById=new HashMap<>();configurations.findAllById(batch.stream().map(plan->plan.configurationId).distinct().toList()).forEach(config->configById.put(config.id,config));
            Map<Long,Long> overdueByPlan=new HashMap<>();installments.countByPlanIdsAndStatus(planIds,InstallmentStatus.OVERDUE).forEach(count->overdueByPlan.put(count.getPaymentPlanId(),count.getInstallmentCount()));
            Set<Long> pendingPlanIds=new HashSet<>(expirations.findPlanIdsByStatus(planIds,PlanExpirationStatus.PENDING_APPROVAL));
            for(PaymentPlan plan:batch){PaymentPlanConfiguration config=configById.get(plan.configurationId);if(config==null)continue;long overdue=overdueByPlan.getOrDefault(plan.id,0L);if(overdue>config.maxOverdueInstallments&&!pendingPlanIds.contains(plan.id)){PlanExpirationRequest e=new PlanExpirationRequest();e.paymentPlanId=plan.id;e.reason="Caducidad detectada por procesamiento de vencimientos del "+date;e.status=PlanExpirationStatus.PENDING_APPROVAL;e.requestedBy=identity.get().userId();e.requestedAt=OffsetDateTime.now();expirations.save(e);plansDefaulted++;audit.record("PlanExpirationRequest",e.id,"PLAN_DEFAULT_DETECTED",e);}}
            afterId=batch.get(batch.size()-1).id;flushAndClear();
        }
        run=runs.findById(runId).orElseThrow();run.debtsScanned=debtsScanned;run.debtsOverdue=debtsOverdue;run.adjustmentsGenerated=adjustmentsGenerated;run.installmentsOverdue=installmentsOverdue;run.plansDefaulted=plansDefaulted;run.skippedAlreadyProcessed=skipped;run.errors=errors;
        run.status=DueDateRunStatus.COMPLETED;run.completedAt=OffsetDateTime.now();run.processedAt=run.completedAt;run.leaseExpiresAt=null;run.lastErrorCode=null;
        run.processingStage=DueDateProcessingStage.COMPLETED;
        runs.save(run);audit.record("DueDateProcessing",run.id,"DUE_DATES_PROCESSED",run);return response(run);
    }
    @Transactional boolean processNextBatch(Long runId,int attemptCount){
        DueDateProcessing run=runs.findByIdForUpdate(runId).orElseThrow(()->CatalogService.notFound("Corrida de vencimientos"));
        if(run.status!=DueDateRunStatus.PROCESSING||run.attemptCount!=attemptCount)return true;
        run.leaseExpiresAt=OffsetDateTime.now().plusMinutes(30);
        return switch(run.processingStage){
            case DEBTS -> processDebtBatch(run);
            case INSTALLMENTS -> processInstallmentBatch(run);
            case PLANS -> processPlanBatch(run);
            case COMPLETED -> complete(run);
        };
    }
    private boolean processDebtBatch(DueDateProcessing run){
        if(run.debtsScanned==0)run.debtsScanned=Math.toIntExact(debts.count());
        List<Debt> batch=debts.findByStatusInAndOutstandingBalanceGreaterThanAndDueDateLessThanAndIdGreaterThanOrderByIdAsc(COLLECTIBLE,BigDecimal.ZERO,run.processingDate,run.lastProcessedId,PageRequest.of(0,BATCH_SIZE));
        if(batch.isEmpty()){run.processingStage=DueDateProcessingStage.INSTALLMENTS;run.lastProcessedId=0;return false;}
        boolean hasLateChargeRule=lateCharges.hasApplicableRule(run.processingDate);int overdue=0,adjustments=0,skipped=0,errors=0;
        for(Debt debt:batch){overdue++;integrationOutbox.overdueDebt(debt,run.processingDate);if(!hasLateChargeRule){errors++;continue;}try{var result=lateCharges.apply(debt.id,run.processingDate);if(result.applied())adjustments+=(result.surchargeAmount().signum()>0?1:0)+(result.interestAmount().signum()>0?1:0);else skipped++;}catch(BusinessException ex){if("DEBT_NOT_CHARGEABLE".equals(ex.code))skipped++;else errors++;}}
        run.lastProcessedId=batch.get(batch.size()-1).id;run.debtsOverdue+=overdue;run.adjustmentsGenerated+=adjustments;run.skippedAlreadyProcessed+=skipped;run.errors+=errors;flushAndClear();return false;
    }
    private boolean processInstallmentBatch(DueDateProcessing run){
        List<Installment> batch=installments.findByStatusInAndOutstandingAmountGreaterThanAndDueDateLessThanAndIdGreaterThanOrderByIdAsc(OPEN_INSTALLMENTS,BigDecimal.ZERO,run.processingDate,run.lastProcessedId,PageRequest.of(0,BATCH_SIZE));
        if(batch.isEmpty()){run.processingStage=DueDateProcessingStage.PLANS;run.lastProcessedId=0;return false;}
        int overdue=0;for(Installment installment:batch)if(installment.status!=InstallmentStatus.OVERDUE){installment.status=InstallmentStatus.OVERDUE;overdue++;}
        run.lastProcessedId=batch.get(batch.size()-1).id;run.installmentsOverdue+=overdue;flushAndClear();return false;
    }
    private boolean processPlanBatch(DueDateProcessing run){
        List<PaymentPlan> batch=plans.findByStatusAndConfigurationIdIsNotNullAndIdGreaterThanOrderByIdAsc(PaymentPlanStatus.ACTIVE,run.lastProcessedId,PageRequest.of(0,BATCH_SIZE));
        if(batch.isEmpty()){run.processingStage=DueDateProcessingStage.COMPLETED;return complete(run);}
        List<Long> planIds=batch.stream().map(plan->plan.id).toList();Map<Long,PaymentPlanConfiguration> configById=new HashMap<>();configurations.findAllById(batch.stream().map(plan->plan.configurationId).distinct().toList()).forEach(config->configById.put(config.id,config));
        Map<Long,Long> overdueByPlan=new HashMap<>();installments.countByPlanIdsAndStatus(planIds,InstallmentStatus.OVERDUE).forEach(count->overdueByPlan.put(count.getPaymentPlanId(),count.getInstallmentCount()));
        Set<Long> pendingPlanIds=new HashSet<>(expirations.findPlanIdsByStatus(planIds,PlanExpirationStatus.PENDING_APPROVAL));int defaulted=0;
        for(PaymentPlan plan:batch){PaymentPlanConfiguration config=configById.get(plan.configurationId);if(config==null)continue;long overdue=overdueByPlan.getOrDefault(plan.id,0L);if(overdue>config.maxOverdueInstallments&&!pendingPlanIds.contains(plan.id)){PlanExpirationRequest e=new PlanExpirationRequest();e.paymentPlanId=plan.id;e.reason="Caducidad detectada por procesamiento de vencimientos del "+run.processingDate;e.status=PlanExpirationStatus.PENDING_APPROVAL;e.requestedBy=identity.get().userId();e.requestedAt=OffsetDateTime.now();expirations.save(e);defaulted++;audit.record("PlanExpirationRequest",e.id,"PLAN_DEFAULT_DETECTED",e);}}
        run.lastProcessedId=batch.get(batch.size()-1).id;run.plansDefaulted+=defaulted;flushAndClear();return false;
    }
    private boolean complete(DueDateProcessing run){run.status=DueDateRunStatus.COMPLETED;run.processingStage=DueDateProcessingStage.COMPLETED;run.completedAt=OffsetDateTime.now();run.processedAt=run.completedAt;run.leaseExpiresAt=null;run.lastErrorCode=null;audit.record("DueDateProcessing",run.id,"DUE_DATES_PROCESSED",run);return true;}
    private void flushAndClear(){entityManager.flush();entityManager.clear();}
    private FiscalProcessingController.DueDateResponse response(DueDateProcessing x){return new FiscalProcessingController.DueDateResponse(x.id,x.processedAt,x.debtsScanned,x.debtsOverdue,x.adjustmentsGenerated,x.installmentsOverdue,x.plansDefaulted,x.skippedAlreadyProcessed,x.errors,x.status,x.processingDate,x.requestedAt,x.startedAt,x.completedAt,x.lastErrorCode);}
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
        for(var input:request.items()){ElectronicReconciliationItem item=new ElectronicReconciliationItem();item.batchId=batch.id;item.externalReference=input.externalReference();item.taxpayerDocument=input.taxpayerDocument();item.amount=MonetaryAmounts.normalize(input.amount());item.paidAt=input.paidAt();match(item);items.save(item);}
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
