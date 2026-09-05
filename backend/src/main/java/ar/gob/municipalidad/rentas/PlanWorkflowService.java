package ar.gob.municipalidad.rentas;

import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;

@Service
class PlanWorkflowService {
    private final PaymentPlanConfigurationRepository configurations;
    private final PaymentPlanRequestRepository requests;
    private final PaymentPlanRequestDebtRepository requestDebts;
    private final PaymentPlanRepository plans;
    private final PaymentPlanDebtRepository planDebts;
    private final InstallmentRepository installments;
    private final PlanExpirationRepository expirations;
    private final RefinancingRequestRepository refinancings;
    private final DebtRepository debts;
    private final TaxConfigurationRepository taxConfigurations;
    private final CurrentIdentity identity;
    private final AuditService audit;
    private final PaymentService paymentEvents;
    private final ConfirmedIntegrationOutbox integrationEvents;

    PlanWorkflowService(PaymentPlanConfigurationRepository configurations, PaymentPlanRequestRepository requests,
        PaymentPlanRequestDebtRepository requestDebts, PaymentPlanRepository plans, PaymentPlanDebtRepository planDebts,
        InstallmentRepository installments, PlanExpirationRepository expirations, RefinancingRequestRepository refinancings,
        DebtRepository debts, TaxConfigurationRepository taxConfigurations, CurrentIdentity identity, AuditService audit,
        PaymentService paymentEvents,ConfirmedIntegrationOutbox integrationEvents) {
        this.configurations= configurations; this.requests=requests; this.requestDebts=requestDebts; this.plans=plans;
        this.planDebts=planDebts; this.installments=installments; this.expirations=expirations; this.refinancings=refinancings;
        this.debts=debts; this.taxConfigurations=taxConfigurations; this.identity=identity; this.audit=audit; this.paymentEvents=paymentEvents;this.integrationEvents=integrationEvents;
    }

    @Transactional PaymentPlanConfiguration createConfiguration(ApiDtos.CreatePaymentPlanConfigurationRequest r) {
        validateConfiguration(r.minimumInstallments(),r.maximumInstallments(),r.minimumDownPaymentPercentage(),r.interestRate(),r.graceDays(),r.maxOverdueInstallments(),r.maxRefinancingCount(),r.validFrom(),r.validUntil());
        PaymentPlanConfiguration c=new PaymentPlanConfiguration();
        c.version=configurations.findFirstByOrderByVersionDesc().map(x->x.version+1).orElse(1);
        apply(c,r.minimumInstallments(),r.maximumInstallments(),r.minimumDownPaymentPercentage(),r.interestRate(),r.graceDays(),r.maxOverdueInstallments(),r.partialInstallmentPaymentAllowed(),r.refinancingAllowed(),r.maxRefinancingCount(),r.validFrom(),r.validUntil(),r.active());
        c.createdBy=identity.get().userId(); c.createdAt=OffsetDateTime.now();
        configurations.save(c); audit.record("PaymentPlanConfiguration",c.id,"PLAN_CONFIGURATION_CREATED",c); return c;
    }

    @Transactional PaymentPlanConfiguration updateConfiguration(Long id,ApiDtos.UpdatePaymentPlanConfigurationRequest r) {
        PaymentPlanConfiguration c=configuration(id);
        int min=r.minimumInstallments()==null?c.minimumInstallments:r.minimumInstallments();
        int max=r.maximumInstallments()==null?c.maximumInstallments:r.maximumInstallments();
        BigDecimal down=r.minimumDownPaymentPercentage()==null?c.minimumDownPaymentPercentage:r.minimumDownPaymentPercentage();
        BigDecimal rate=r.interestRate()==null?c.interestRate:r.interestRate();
        int grace=r.graceDays()==null?c.graceDays:r.graceDays(); int overdue=r.maxOverdueInstallments()==null?c.maxOverdueInstallments:r.maxOverdueInstallments();
        int maxRefinancing=r.maxRefinancingCount()==null?c.maxRefinancingCount:r.maxRefinancingCount();
        LocalDate from=r.validFrom()==null?c.validFrom:r.validFrom(); LocalDate until=r.validUntil()==null?c.validUntil:r.validUntil();
        validateConfiguration(min,max,down,rate,grace,overdue,maxRefinancing,from,until);
        apply(c,min,max,down,rate,grace,overdue,r.partialInstallmentPaymentAllowed()==null?c.partialInstallmentPaymentAllowed:r.partialInstallmentPaymentAllowed(),r.refinancingAllowed()==null?c.refinancingAllowed:r.refinancingAllowed(),maxRefinancing,from,until,r.active()==null?c.active:r.active());
        audit.record("PaymentPlanConfiguration",c.id,"PLAN_CONFIGURATION_UPDATED",c); return c;
    }

    ApiDtos.PaymentPlanSimulationResponse simulate(ApiDtos.PaymentPlanSimulationRequest r) {
        identity.requireOwnership(r.taxpayerId());
        PaymentPlanConfiguration c=currentConfiguration(); List<Debt> selected=eligibleDebts(r.taxpayerId(),r.debtIds());
        return calculate(c,selected.stream().map(x->x.outstandingBalance).reduce(BigDecimal.ZERO,BigDecimal::add),r.installments());
    }

    @Transactional PaymentPlanRequest request(ApiDtos.CreatePaymentPlanRequest r) {
        identity.requireOwnership(r.taxpayerId());
        PaymentPlanConfiguration c=currentConfiguration(); List<Debt> selected=eligibleDebts(r.taxpayerId(),r.debtIds());
        ApiDtos.PaymentPlanSimulationResponse s=calculate(c,selected.stream().map(x->x.outstandingBalance).reduce(BigDecimal.ZERO,BigDecimal::add),r.installments());
        PaymentPlanRequest request=new PaymentPlanRequest(); request.taxpayerId=r.taxpayerId(); request.requestedInstallments=r.installments();
        request.totalDebtAtRequest=s.principal(); request.estimatedDownPayment=s.downPayment(); request.estimatedFinancedAmount=s.financedPrincipal();
        request.estimatedInterest=s.interest(); request.estimatedTotalAmount=s.total(); request.exceptional=s.exceptional(); request.exceptionApproved=false;
        request.status=PaymentPlanRequestStatus.PENDING; request.requestedBy=identity.get().userId(); request.requestedAt=OffsetDateTime.now(); requests.save(request);
        for(Debt debt:selected){PaymentPlanRequestDebt link=new PaymentPlanRequestDebt();link.requestId=request.id;link.debtId=debt.id;link.balanceAtRequest=debt.outstandingBalance;requestDebts.save(link);}
        integrationEvents.paymentPlanRequested(request);audit.record("PaymentPlanRequest",request.id,"PAYMENT_PLAN_REQUESTED",request); return request;
    }

    PaymentPlanRequest getRequest(Long id){PaymentPlanRequest r=requests.findById(id).orElseThrow(()->CatalogService.notFound("Solicitud de plan"));identity.requireOwnership(r.taxpayerId);return r;}

    @Transactional PaymentPlanRequest submitException(Long id,ApiDtos.SubmitPlanExceptionRequest body){PaymentPlanRequest r=getRequest(id);requireStatus(r,PaymentPlanRequestStatus.PENDING);CatalogService.require(r.exceptional,"PLAN_EXCEPTION_NOT_REQUIRED","La solicitud cumple la configuración vigente");r.exceptionReason=body.reason();r.status=PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL;audit.record("PaymentPlanRequest",r.id,"PLAN_EXCEPTION_SUBMITTED",r);return r;}
    @Transactional PaymentPlanRequest approveException(Long id,ApiDtos.ApprovePlanExceptionRequest body){PaymentPlanRequest r=getRequest(id);requireStatus(r,PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL);r.exceptionApproved=true;r.status=PaymentPlanRequestStatus.PENDING;r.resolvedBy=identity.get().userId();r.resolvedAt=OffsetDateTime.now();r.resolutionReason=body==null?null:body.observation();audit.record("PaymentPlanRequest",r.id,"PLAN_EXCEPTION_APPROVED",r);return r;}
    @Transactional PaymentPlanRequest rejectException(Long id,String reason){PaymentPlanRequest r=getRequest(id);requireStatus(r,PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL);return reject(r,reason,"PLAN_EXCEPTION_REJECTED");}
    @Transactional PaymentPlanRequest rejectRequest(Long id,String reason){PaymentPlanRequest r=getRequest(id);requireStatus(r,PaymentPlanRequestStatus.PENDING);return reject(r,reason,"PAYMENT_PLAN_REQUEST_REJECTED");}

    @Transactional PaymentPlanRequest grant(Long id,ApiDtos.GrantPaymentPlanRequest body) {
        PaymentPlanRequest r=requests.findByIdForUpdate(id).orElseThrow(()->CatalogService.notFound("Solicitud de plan")); identity.requireOwnership(r.taxpayerId); requireStatus(r,PaymentPlanRequestStatus.PENDING);
        CatalogService.require(!r.exceptional||r.exceptionApproved,"PLAN_EXCEPTION_APPROVAL_REQUIRED","La excepción requiere aprobación de Supervisor");
        PaymentPlanConfiguration c=currentConfiguration(); List<PaymentPlanRequestDebt> links=requestDebts.findByRequestId(r.id);
        List<Debt> selected=links.stream().map(x->debts.findByIdForUpdate(x.debtId).orElseThrow(()->CatalogService.notFound("Deuda"))).toList();
        CatalogService.require(selected.size()==links.size()&&selected.stream().allMatch(d->d.status!=DebtStatus.PAID&&d.status!=DebtStatus.CANCELLED),"PLAN_DEBT_CHANGED","Las deudas de la solicitud ya no son elegibles");
        CatalogService.require(selected.stream().noneMatch(d->plans.existsByDebtIdAndStatus(d.id,PaymentPlanStatus.ACTIVE)||planDebts.existsByDebtIdAndStatus(d.id,PaymentPlanDebtStatus.ACTIVE)),"DEBT_ALREADY_IN_ACTIVE_PAYMENT_PLAN","Una deuda ya pertenece a un plan activo");
        for(int i=0;i<links.size();i++)CatalogService.require(selected.get(i).outstandingBalance.compareTo(links.get(i).balanceAtRequest)==0,"PLAN_DEBT_CHANGED","El saldo de una deuda cambió desde la solicitud");
        BigDecimal principal=selected.stream().map(x->x.outstandingBalance).reduce(BigDecimal.ZERO,BigDecimal::add); BigDecimal minimum=percentage(principal,c.minimumDownPaymentPercentage);
        BigDecimal down=body==null||body.downPaymentAmount()==null?minimum:money(body.downPaymentAmount());
        CatalogService.require(down.compareTo(minimum)>=0&&down.compareTo(principal)<=0,"INVALID_DOWN_PAYMENT","El anticipo no cumple la configuración");
        PaymentPlan plan=createPlan(r.id,c,selected,principal,down,r.requestedInstallments,0);
        r.status=PaymentPlanRequestStatus.GRANTED;r.paymentPlanId=plan.id;r.resolvedBy=identity.get().userId();r.resolvedAt=OffsetDateTime.now();
        integrationEvents.paymentPlanGranted(r,plan);audit.record("PaymentPlanRequest",r.id,"PAYMENT_PLAN_GRANTED",r);return r;
    }

    PaymentPlan getPlan(Long id){PaymentPlan p=plans.findById(id).orElseThrow(()->CatalogService.notFound("Plan"));identity.requireOwnership(p.taxpayerId);return p;}
    Installment getInstallment(Long planId,Long installmentId){getPlan(planId);Installment i=installments.findById(installmentId).orElseThrow(()->CatalogService.notFound("Cuota"));CatalogService.require(i.paymentPlanId.equals(planId),"INSTALLMENT_PLAN_MISMATCH","La cuota no pertenece al plan");return i;}

    @Transactional(readOnly=true) Page<PaymentPlan> defaulted(Pageable pageable){return plans.findDefaulted(LocalDate.now(),pageable);}

    @Transactional PlanExpirationRequest requestExpiration(Long planId,ApiDtos.CreatePlanExpirationRequest body){PaymentPlan p=getPlan(planId);CatalogService.require(p.status==PaymentPlanStatus.ACTIVE,"PLAN_NOT_ACTIVE","El plan no está activo");CatalogService.require(isDefaulted(p),"PLAN_NOT_DEFAULTED","El plan no supera el máximo de cuotas vencidas");CatalogService.require(!expirations.existsByPaymentPlanIdAndStatus(p.id,PlanExpirationStatus.PENDING_APPROVAL),"EXPIRATION_ALREADY_PENDING","Ya existe una caducidad pendiente");PlanExpirationRequest e=new PlanExpirationRequest();e.paymentPlanId=p.id;e.reason=body.reason();e.status=PlanExpirationStatus.PENDING_APPROVAL;e.requestedBy=identity.get().userId();e.requestedAt=OffsetDateTime.now();expirations.save(e);audit.record("PlanExpirationRequest",e.id,"PLAN_EXPIRATION_REQUESTED",e);return e;}
    PlanExpirationRequest getExpiration(Long id){return expirations.findById(id).orElseThrow(()->CatalogService.notFound("Solicitud de caducidad"));}
    @Transactional PlanExpirationRequest approveExpiration(Long id,String observation){PlanExpirationRequest e=expirations.findByIdForUpdate(id).orElseThrow(()->CatalogService.notFound("Solicitud de caducidad"));CatalogService.require(e.status==PlanExpirationStatus.PENDING_APPROVAL,"EXPIRATION_NOT_PENDING","La caducidad no está pendiente");PaymentPlan p=plans.findByIdForUpdate(e.paymentPlanId).orElseThrow(()->CatalogService.notFound("Plan"));identity.requireOwnership(p.taxpayerId);p.status=PaymentPlanStatus.EXPIRED;p.expiredAt=OffsetDateTime.now();installments.findByPaymentPlanIdOrderByNumber(p.id).stream().filter(i->i.status==InstallmentStatus.PENDING||i.status==InstallmentStatus.PARTIALLY_PAID||i.status==InstallmentStatus.OVERDUE).forEach(i->i.status=InstallmentStatus.CANCELLED);planDebts.findByPaymentPlanId(p.id).forEach(d->d.status=PaymentPlanDebtStatus.RELEASED);resolve(e,PlanExpirationStatus.APPROVED,observation);paymentEvents.addOutbox("paymentPlanExpired","PaymentPlan",p.id,"{\"paymentPlanId\":"+p.id+"}");audit.record("PaymentPlan",p.id,"PAYMENT_PLAN_EXPIRED",p);return e;}
    @Transactional PlanExpirationRequest rejectExpiration(Long id,String reason){PlanExpirationRequest e=getExpiration(id);CatalogService.require(e.status==PlanExpirationStatus.PENDING_APPROVAL,"EXPIRATION_NOT_PENDING","La caducidad no está pendiente");resolve(e,PlanExpirationStatus.REJECTED,reason);return e;}

    ApiDtos.RefinancingSimulationResponse simulateRefinancing(Long planId,ApiDtos.RefinancingSimulationRequest body){PaymentPlan p=getPlan(planId);PaymentPlanConfiguration c=configuration(p.configurationId);validateRefinancing(p,c);BigDecimal principal=remainingPrincipal(p.id);BigDecimal interest=percentage(principal,c.interestRate);boolean exceptional=body.installments()<c.minimumInstallments||body.installments()>c.maximumInstallments;return new ApiDtos.RefinancingSimulationResponse(p.id,principal,interest,principal.add(interest),body.installments(),exceptional);}
    @Transactional RefinancingRequest requestRefinancing(Long planId,ApiDtos.CreateRefinancingRequest body){ApiDtos.RefinancingSimulationResponse s=simulateRefinancing(planId,new ApiDtos.RefinancingSimulationRequest(body.installments()));PaymentPlan p=getPlan(planId);RefinancingRequest r=new RefinancingRequest();r.originalPlanId=p.id;r.taxpayerId=p.taxpayerId;r.requestedInstallments=body.installments();r.outstandingPrincipalAtRequest=s.outstandingPrincipal();r.estimatedInterest=s.interest();r.estimatedTotalAmount=s.total();r.exceptional=s.exceptional();r.exceptionApproved=false;r.status=RefinancingRequestStatus.PENDING;r.requestedBy=identity.get().userId();r.requestedAt=OffsetDateTime.now();refinancings.save(r);audit.record("RefinancingRequest",r.id,"REFINANCING_REQUESTED",r);return r;}
    RefinancingRequest getRefinancing(Long id){RefinancingRequest r=refinancings.findById(id).orElseThrow(()->CatalogService.notFound("Solicitud de refinanciación"));identity.requireOwnership(r.taxpayerId);return r;}
    @Transactional RefinancingRequest submitRefinancingException(Long id,String reason){RefinancingRequest r=getRefinancing(id);requireStatus(r,RefinancingRequestStatus.PENDING);CatalogService.require(r.exceptional,"REFINANCING_EXCEPTION_NOT_REQUIRED","La refinanciación cumple la configuración");r.exceptionReason=reason;r.status=RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL;return r;}
    @Transactional RefinancingRequest approveRefinancingException(Long id){RefinancingRequest r=getRefinancing(id);requireStatus(r,RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL);r.exceptionApproved=true;r.status=RefinancingRequestStatus.PENDING;r.resolvedBy=identity.get().userId();r.resolvedAt=OffsetDateTime.now();return r;}
    @Transactional RefinancingRequest rejectRefinancing(Long id,String reason){RefinancingRequest r=getRefinancing(id);CatalogService.require(r.status==RefinancingRequestStatus.PENDING||r.status==RefinancingRequestStatus.PENDING_EXCEPTION_APPROVAL,"REFINANCING_NOT_PENDING","La solicitud no está pendiente");r.status=RefinancingRequestStatus.REJECTED;r.exceptionReason=reason;r.resolvedBy=identity.get().userId();r.resolvedAt=OffsetDateTime.now();return r;}
    @Transactional RefinancingRequest grantRefinancing(Long id){RefinancingRequest r=getRefinancing(id);requireStatus(r,RefinancingRequestStatus.PENDING);CatalogService.require(!r.exceptional||r.exceptionApproved,"REFINANCING_EXCEPTION_APPROVAL_REQUIRED","La excepción requiere aprobación");PaymentPlan old=getPlan(r.originalPlanId);PaymentPlanConfiguration c=configuration(old.configurationId);validateRefinancing(old,c);BigDecimal remaining=remainingPrincipal(old.id);CatalogService.require(remaining.compareTo(r.outstandingPrincipalAtRequest)==0,"PLAN_BALANCE_CHANGED","El capital pendiente cambió");List<Debt> selected=planDebts.findByPaymentPlanId(old.id).stream().filter(x->x.status==PaymentPlanDebtStatus.ACTIVE).map(x->debts.findById(x.debtId).orElseThrow()).toList();old.status=PaymentPlanStatus.REFINANCED;old.refinancedAt=OffsetDateTime.now();installments.findByPaymentPlanIdOrderByNumber(old.id).stream().filter(i->i.status!=InstallmentStatus.PAID).forEach(i->i.status=InstallmentStatus.CANCELLED);planDebts.findByPaymentPlanId(old.id).forEach(d->d.status=PaymentPlanDebtStatus.RELEASED);PaymentPlan next=createPlan(null,c,selected,remaining,BigDecimal.ZERO,r.requestedInstallments,old.refinancingCount+1);r.status=RefinancingRequestStatus.GRANTED;r.newPaymentPlanId=next.id;r.resolvedBy=identity.get().userId();r.resolvedAt=OffsetDateTime.now();paymentEvents.addOutbox("paymentPlanRefinanced","PaymentPlan",next.id,"{\"oldPaymentPlanId\":"+old.id+",\"newPaymentPlanId\":"+next.id+"}");return r;}

    private PaymentPlan createPlan(Long requestId,PaymentPlanConfiguration c,List<Debt> selected,BigDecimal principal,BigDecimal down,int count,int refinancingCount){for(Debt d:selected)CatalogService.require(!planDebts.existsByDebtIdAndStatus(d.id,PaymentPlanDebtStatus.ACTIVE)&&!plans.existsByDebtIdAndStatus(d.id,PaymentPlanStatus.ACTIVE),"DEBT_ALREADY_IN_PAYMENT_PLAN","La deuda ya pertenece a un plan activo");BigDecimal financed=principal.subtract(down);BigDecimal interest=percentage(financed,c.interestRate);BigDecimal total=principal.add(interest);PaymentPlan p=new PaymentPlan();p.taxpayerId=selected.get(0).taxpayerId;p.debtId=selected.get(0).id;p.requestId=requestId;p.configurationId=c.id;p.configurationVersion=c.version;p.originalPrincipalAmount=principal;p.downPaymentAmount=down;p.financedPrincipalAmount=financed;p.financingInterestAmount=interest;p.totalPlanAmount=total;p.paidAmount=money(BigDecimal.ZERO);p.outstandingPlanAmount=total;p.installmentCount=count;p.refinancingCount=refinancingCount;p.status=PaymentPlanStatus.ACTIVE;p.grantedBy=identity.get().userId();p.grantedAt=OffsetDateTime.now();plans.save(p);for(Debt debt:selected){PaymentPlanDebt link=new PaymentPlanDebt();link.paymentPlanId=p.id;link.debtId=debt.id;link.includedPrincipalAmount=debt.outstandingBalance;link.principalPaidAmount=money(BigDecimal.ZERO);link.remainingPrincipalAmount=debt.outstandingBalance;link.status=PaymentPlanDebtStatus.ACTIVE;link.createdAt=OffsetDateTime.now();planDebts.save(link);}if(down.signum()>0)createInstallment(p,0,InstallmentType.DOWN_PAYMENT,down,BigDecimal.ZERO,LocalDate.now().plusDays(c.graceDays));BigDecimal regularTotal=financed.add(interest);BigDecimal each=regularTotal.divide(BigDecimal.valueOf(count),2,RoundingMode.DOWN);BigDecimal principalEach=financed.divide(BigDecimal.valueOf(count),2,RoundingMode.DOWN);for(int n=1;n<=count;n++){BigDecimal amount=n==count?regularTotal.subtract(each.multiply(BigDecimal.valueOf(n-1))):each;BigDecimal principalPart=n==count?financed.subtract(principalEach.multiply(BigDecimal.valueOf(n-1))):principalEach;createInstallment(p,n,InstallmentType.REGULAR,principalPart,amount.subtract(principalPart),LocalDate.now().plusMonths(n).plusDays(c.graceDays));}audit.record("PaymentPlan",p.id,"PAYMENT_PLAN_GRANTED",p);return p;}
    private void createInstallment(PaymentPlan p,int number,InstallmentType type,BigDecimal principal,BigDecimal interest,LocalDate due){Installment i=new Installment();i.paymentPlanId=p.id;i.number=number;i.type=type;i.principalAmount=money(principal);i.interestAmount=money(interest);i.totalAmount=i.principalAmount.add(i.interestAmount);i.paidAmount=money(BigDecimal.ZERO);i.outstandingAmount=i.totalAmount;i.dueDate=due;i.status=InstallmentStatus.PENDING;installments.save(i);}
    private ApiDtos.PaymentPlanSimulationResponse calculate(PaymentPlanConfiguration c,BigDecimal principal,int count){BigDecimal down=percentage(principal,c.minimumDownPaymentPercentage);BigDecimal financed=principal.subtract(down);BigDecimal interest=percentage(financed,c.interestRate);BigDecimal total=principal.add(interest);boolean exceptional=count<c.minimumInstallments||count>c.maximumInstallments;return new ApiDtos.PaymentPlanSimulationResponse(c.id,c.version,money(principal),down,financed,interest,total,count,financed.add(interest).divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP),exceptional);}
    private List<Debt> eligibleDebts(Long taxpayerId,List<Long> ids){List<Long> unique=ids.stream().distinct().toList();CatalogService.require(unique.size()==ids.size(),"DUPLICATE_PLAN_DEBT","La solicitud contiene deudas repetidas");List<Debt> result=unique.stream().map(id->debts.findById(id).orElseThrow(()->CatalogService.notFound("Deuda"))).toList();CatalogService.require(result.stream().allMatch(x->x.taxpayerId.equals(taxpayerId)),"PLAN_TAXPAYER_MISMATCH","Todas las deudas deben pertenecer al contribuyente");for(Debt d:result){CatalogService.require(d.status!=DebtStatus.PAID&&d.status!=DebtStatus.CANCELLED&&d.outstandingBalance.signum()>0,"DEBT_NOT_ELIGIBLE_FOR_PLAN","La deuda no admite plan");CatalogService.require(!planDebts.existsByDebtIdAndStatus(d.id,PaymentPlanDebtStatus.ACTIVE)&&!plans.existsByDebtIdAndStatus(d.id,PaymentPlanStatus.ACTIVE),"DEBT_ALREADY_IN_PAYMENT_PLAN","La deuda ya pertenece a un plan activo");if(d.configurationId!=null)CatalogService.require(taxConfigurations.findById(d.configurationId).orElseThrow().paymentPlanAllowed,"PAYMENT_PLAN_NOT_ALLOWED","El concepto no admite planes");}return result;}
    private boolean isDefaulted(PaymentPlan p){if(p.status!=PaymentPlanStatus.ACTIVE)return false;PaymentPlanConfiguration c=configuration(p.configurationId);long overdue=installments.findByPaymentPlanIdOrderByNumber(p.id).stream().filter(i->i.status!=InstallmentStatus.PAID&&i.status!=InstallmentStatus.CANCELLED&&i.outstandingAmount.signum()>0&&i.dueDate.isBefore(LocalDate.now())).count();return overdue>c.maxOverdueInstallments;}
    private void validateRefinancing(PaymentPlan p,PaymentPlanConfiguration c){CatalogService.require(p.status==PaymentPlanStatus.ACTIVE,"PLAN_NOT_ACTIVE","El plan no está activo");CatalogService.require(c.refinancingAllowed,"REFINANCING_NOT_ALLOWED","La configuración no permite refinanciar");CatalogService.require(p.refinancingCount<c.maxRefinancingCount,"MAX_REFINANCING_REACHED","El plan alcanzó el máximo de refinanciaciones");}
    private BigDecimal remainingPrincipal(Long planId){return money(planDebts.findByPaymentPlanId(planId).stream().filter(x->x.status==PaymentPlanDebtStatus.ACTIVE).map(x->x.remainingPrincipalAmount).reduce(BigDecimal.ZERO,BigDecimal::add));}
    private PaymentPlanConfiguration currentConfiguration(){List<PaymentPlanConfiguration> result=configurations.findApplicable(LocalDate.now(),PageRequest.of(0,1));CatalogService.require(!result.isEmpty(),"NO_ACTIVE_PLAN_CONFIGURATION","No existe configuración de planes vigente");return result.get(0);}
    private PaymentPlanConfiguration configuration(Long id){CatalogService.require(id!=null,"PLAN_CONFIGURATION_MISSING","El plan no tiene configuración asociada");return configurations.findById(id).orElseThrow(()->CatalogService.notFound("Configuración de planes"));}
    private void validateConfiguration(int min,int max,BigDecimal down,BigDecimal rate,int grace,int overdue,int refinancings,LocalDate from,LocalDate until){CatalogService.require(min>0&&max>=min,"INVALID_INSTALLMENT_RANGE","El rango de cuotas es inválido");CatalogService.require(down!=null&&down.signum()>=0&&down.compareTo(new BigDecimal("100"))<=0,"INVALID_DOWN_PAYMENT_PERCENTAGE","El porcentaje de anticipo es inválido");CatalogService.require(rate!=null&&rate.signum()>=0,"INVALID_PLAN_INTEREST","La tasa no puede ser negativa");CatalogService.require(grace>=0&&overdue>=0&&refinancings>=0,"INVALID_PLAN_POLICY","Los límites no pueden ser negativos");CatalogService.require(until==null||!until.isBefore(from),"INVALID_VALIDITY_RANGE","La vigencia es inválida");}
    private void apply(PaymentPlanConfiguration c,int min,int max,BigDecimal down,BigDecimal rate,int grace,int overdue,boolean partial,boolean refinancing,int maxRefinancing,LocalDate from,LocalDate until,boolean active){c.minimumInstallments=min;c.maximumInstallments=max;c.minimumDownPaymentPercentage=down;c.interestRate=rate;c.graceDays=grace;c.maxOverdueInstallments=overdue;c.partialInstallmentPaymentAllowed=partial;c.refinancingAllowed=refinancing;c.maxRefinancingCount=maxRefinancing;c.validFrom=from;c.validUntil=until;c.active=active;}
    private PaymentPlanRequest reject(PaymentPlanRequest r,String reason,String action){r.status=PaymentPlanRequestStatus.REJECTED;r.resolutionReason=reason;r.resolvedBy=identity.get().userId();r.resolvedAt=OffsetDateTime.now();integrationEvents.paymentPlanRejected(r);audit.record("PaymentPlanRequest",r.id,action,r);return r;}
    private void resolve(PlanExpirationRequest e,PlanExpirationStatus status,String observation){e.status=status;e.resolutionObservation=observation;e.resolvedBy=identity.get().userId();e.resolvedAt=OffsetDateTime.now();audit.record("PlanExpirationRequest",e.id,"PLAN_EXPIRATION_"+status.name(),e);}
    private void requireStatus(PaymentPlanRequest r,PaymentPlanRequestStatus status){CatalogService.require(r.status==status,"INVALID_PLAN_REQUEST_TRANSITION","Estado de solicitud inválido");}
    private void requireStatus(RefinancingRequest r,RefinancingRequestStatus status){CatalogService.require(r.status==status,"INVALID_REFINANCING_TRANSITION","Estado de refinanciación inválido");}
    private static BigDecimal percentage(BigDecimal amount,BigDecimal rate){return money(amount.multiply(rate).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP));}
    private static BigDecimal money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP);}
}
