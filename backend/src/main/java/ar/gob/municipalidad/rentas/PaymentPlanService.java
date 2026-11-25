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
class PaymentPlanService {
    private final DebtRepository debts; private final PaymentPlanRepository plans; private final InstallmentRepository installments; private final AuditService audit; private final CurrentIdentity identity;
    PaymentPlanService(DebtRepository debts,PaymentPlanRepository plans,InstallmentRepository installments,AuditService audit,CurrentIdentity identity){this.debts=debts;this.plans=plans;this.installments=installments;this.audit=audit;this.identity=identity;}
    @Transactional PaymentPlan grant(ApiDtos.GrantPlanRequest r){Debt d=debts.findByIdForUpdate(r.debtId()).orElseThrow(()->CatalogService.notFound("Deuda"));CatalogService.requireConflict(!plans.existsByDebtIdAndStatus(d.id,PaymentPlanStatus.ACTIVE),"DEBT_ALREADY_IN_PAYMENT_PLAN","La deuda ya pertenece a un plan activo");BigDecimal interest=d.outstandingBalance.multiply(r.annualInterestRate()).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP);BigDecimal total=d.outstandingBalance.add(interest);PaymentPlan p=new PaymentPlan();p.taxpayerId=d.taxpayerId;p.debtId=d.id;p.originalPrincipalAmount=d.outstandingBalance;p.downPaymentAmount=MonetaryAmounts.zero();p.financedPrincipalAmount=d.outstandingBalance;p.financingInterestAmount=interest;p.totalPlanAmount=p.outstandingPlanAmount=total;p.paidAmount=MonetaryAmounts.zero();p.installmentCount=r.installments();p.refinancingCount=0;p.status=PaymentPlanStatus.ACTIVE;p.grantedBy=identity.get().userId();p.grantedAt=OffsetDateTime.now();plans.save(p);BigDecimal each=total.divide(BigDecimal.valueOf(r.installments()),2,RoundingMode.DOWN);BigDecimal principalEach=d.outstandingBalance.divide(BigDecimal.valueOf(r.installments()),2,RoundingMode.DOWN);for(int i=1;i<=r.installments();i++){Installment x=new Installment();x.paymentPlanId=p.id;x.number=i;x.type=InstallmentType.REGULAR;x.totalAmount=i==r.installments()?total.subtract(each.multiply(BigDecimal.valueOf(i-1))):each;x.principalAmount=i==r.installments()?d.outstandingBalance.subtract(principalEach.multiply(BigDecimal.valueOf(i-1))):principalEach;x.interestAmount=x.totalAmount.subtract(x.principalAmount);x.paidAmount=MonetaryAmounts.zero();x.outstandingAmount=x.totalAmount;x.dueDate=LocalDate.now().plusMonths(i);x.status=InstallmentStatus.PENDING;installments.save(x);}audit.record("PaymentPlan",p.id,"PAYMENT_PLAN_GRANTED",p);return p;}
}
