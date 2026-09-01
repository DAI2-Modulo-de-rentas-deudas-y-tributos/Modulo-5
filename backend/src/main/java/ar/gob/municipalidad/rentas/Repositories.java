package ar.gob.municipalidad.rentas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;

interface CollectionIndicatorAggregate {
    long getPaymentCount(); BigDecimal getConfirmedAmount(); BigDecimal getAllocatedAmount(); BigDecimal getUnallocatedAmount();
}
interface DebtIndicatorAggregate {
    long getDebtCount(); long getPaidCount(); long getOpenCount(); BigDecimal getOriginalAmount(); BigDecimal getOutstandingAmount();
}
interface DelinquencyIndicatorAggregate {
    long getOverdueDebtCount(); long getOpenDebtCount(); BigDecimal getOverdueAmount();
}

@org.springframework.data.repository.NoRepositoryBean
interface FilteredRepository<T,ID> extends JpaRepository<T,ID>,JpaSpecificationExecutor<T> {}

interface TaxpayerRepository extends FilteredRepository<TaxpayerReference,Long> {
    Optional<TaxpayerReference> findByTaxpayerTypeAndExternalId(TaxpayerType type, String externalId);
    Optional<TaxpayerReference> findByTaxpayerTypeAndDni(TaxpayerType type,String dni);
    Optional<TaxpayerReference> findByTaxpayerTypeAndCuit(TaxpayerType type,String cuit);
}
interface TaxConceptRepository extends FilteredRepository<TaxConcept,Long> { Optional<TaxConcept> findByCode(String code); }
interface TaxConfigurationRepository extends FilteredRepository<TaxConfiguration,Long> {
    Optional<TaxConfiguration> findFirstByTaxConceptIdAndStatusAndValidFromLessThanEqualAndValidUntilGreaterThanEqualOrderByVersionDesc(Long conceptId, TaxConfigurationStatus status, LocalDate from, LocalDate until);
    Optional<TaxConfiguration> findFirstByTaxConceptIdOrderByVersionDesc(Long conceptId);
}
interface LiquidationRepository extends FilteredRepository<Liquidation,Long> { List<Liquidation> findByTaxpayerId(Long taxpayerId); boolean existsByTaxpayerIdAndTaxConceptIdAndPeriod(Long taxpayerId,Long conceptId,String period); }
interface LiquidationComponentRepository extends JpaRepository<LiquidationComponent,Long> { List<LiquidationComponent> findByLiquidationIdOrderById(Long liquidationId); List<LiquidationComponent> findByLiquidationIdInOrderByLiquidationIdAscIdAsc(Collection<Long> liquidationIds); }
interface DebtRepository extends FilteredRepository<Debt,Long> {
    List<Debt> findByTaxpayerId(Long taxpayerId); boolean existsByExternalObligationId(Long externalObligationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from Debt d where d.id=:id") Optional<Debt> findByIdForUpdate(Long id);
    @Query("""
        select count(d.id) as debtCount,
          coalesce(sum(case when d.status=ar.gob.municipalidad.rentas.DebtStatus.PAID then 1 else 0 end),0) as paidCount,
          coalesce(sum(case when d.status not in (ar.gob.municipalidad.rentas.DebtStatus.PAID,ar.gob.municipalidad.rentas.DebtStatus.CANCELLED) and d.outstandingBalance>0 then 1 else 0 end),0) as openCount,
          coalesce(sum(d.originalAmount),0) as originalAmount,
          coalesce(sum(d.outstandingBalance),0) as outstandingAmount
        from Debt d
        """) DebtIndicatorAggregate aggregateDebt();
    @Query("""
        select coalesce(sum(case when d.status not in (ar.gob.municipalidad.rentas.DebtStatus.PAID,ar.gob.municipalidad.rentas.DebtStatus.CANCELLED) and d.outstandingBalance>0 and d.dueDate<:today then 1 else 0 end),0) as overdueDebtCount,
          coalesce(sum(case when d.status not in (ar.gob.municipalidad.rentas.DebtStatus.PAID,ar.gob.municipalidad.rentas.DebtStatus.CANCELLED) and d.outstandingBalance>0 then 1 else 0 end),0) as openDebtCount,
          coalesce(sum(case when d.status not in (ar.gob.municipalidad.rentas.DebtStatus.PAID,ar.gob.municipalidad.rentas.DebtStatus.CANCELLED) and d.outstandingBalance>0 and d.dueDate<:today then d.outstandingBalance else 0 end),0) as overdueAmount
        from Debt d
        """) DelinquencyIndicatorAggregate aggregateDelinquency(@Param("today") LocalDate today);
}
interface ExternalObligationRepository extends FilteredRepository<ExternalObligation,Long> {
    Optional<ExternalObligation> findBySourceModuleAndExternalTypeAndExternalReferenceId(String module, ExternalObligationType type, String reference);
    Page<ExternalObligation> findByStatus(ExternalObligationStatus status,Pageable pageable);
}
interface PaymentRepository extends FilteredRepository<Payment,Long> {
    List<Payment> findByTaxpayerId(Long taxpayerId); Page<Payment> findByTaxpayerId(Long taxpayerId,Pageable pageable); Page<Payment> findByUnallocatedAmountGreaterThan(BigDecimal amount,Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from Payment p where p.id=:id") Optional<Payment> findByIdForUpdate(Long id);
    @Query("""
        select count(p.id) as paymentCount, coalesce(sum(p.amount),0) as confirmedAmount,
          coalesce(sum(p.allocatedAmount),0) as allocatedAmount, coalesce(sum(p.unallocatedAmount),0) as unallocatedAmount
        from Payment p
        where p.status=ar.gob.municipalidad.rentas.PaymentStatus.CONFIRMED
          and (:from is null or cast(p.paidAt as LocalDate)>=:from)
          and (:to is null or cast(p.paidAt as LocalDate)<=:to)
        """) CollectionIndicatorAggregate aggregateConfirmed(@Param("from") LocalDate from,@Param("to") LocalDate to);
}
interface PaymentAllocationRepository extends FilteredRepository<PaymentAllocation,Long> { List<PaymentAllocation> findByPaymentId(Long paymentId); }
interface BillRepository extends FilteredRepository<Bill,Long> { List<Bill> findByTaxpayerId(Long taxpayerId); Page<Bill> findByTaxpayerId(Long taxpayerId,Pageable pageable); }
interface BillDebtRepository extends JpaRepository<BillDebt,Long> { List<BillDebt> findByBillId(Long billId); List<BillDebt> findByBillIdInOrderByBillIdAscIdAsc(Collection<Long> billIds); }
interface ElectronicPaymentRepository extends JpaRepository<ElectronicPaymentAttempt,Long> { Optional<ElectronicPaymentAttempt> findByPaymentId(Long paymentId); }
interface CreditBalanceRepository extends FilteredRepository<CreditBalance,Long> { Optional<CreditBalance> findBySourcePaymentId(Long paymentId); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from CreditBalance c where c.sourcePaymentId=:paymentId") Optional<CreditBalance> findBySourcePaymentIdForUpdate(Long paymentId); List<CreditBalance> findByTaxpayerId(Long taxpayerId); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from CreditBalance c where c.id=:id") Optional<CreditBalance> findByIdForUpdate(Long id); }
interface CreditBalanceApplicationRepository extends JpaRepository<CreditBalanceApplication,Long> {}
interface PaymentReversalRepository extends FilteredRepository<PaymentReversalRequest,Long> { @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PaymentReversalRequest r where r.id=:id") Optional<PaymentReversalRequest> findByIdForUpdate(Long id); boolean existsByPaymentIdAndStatusIn(Long paymentId,Collection<PaymentReversalStatus> statuses); }
interface ProcessedEventRepository extends JpaRepository<ProcessedEvent,UUID> { boolean existsByExternalEventId(String eventId); }
interface IntegrationEventLogRepository extends FilteredRepository<IntegrationEventLog,Long> { Optional<IntegrationEventLog> findFirstByEventIdOrderByIdDesc(UUID eventId); Optional<IntegrationEventLog> findFirstByExternalEventIdOrderByIdDesc(String eventId); Page<IntegrationEventLog> findByStatusIn(Collection<IntegrationEventStatus> statuses,Pageable pageable); }
interface OutboxRepository extends JpaRepository<OutboxEvent,UUID> { @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from OutboxEvent e where e.status in (ar.gob.municipalidad.rentas.OutboxStatus.PENDING,ar.gob.municipalidad.rentas.OutboxStatus.FAILED) order by e.createdAt") List<OutboxEvent> findPublishable(Pageable pageable); }
interface AuditRepository extends FilteredRepository<AuditEntry,Long> { List<AuditEntry> findByEntityTypeAndEntityIdOrderByOccurredAt(String entityType,String entityId); }
interface PaymentPlanConfigurationRepository extends FilteredRepository<PaymentPlanConfiguration,Long> {
    Optional<PaymentPlanConfiguration> findFirstByOrderByVersionDesc();
    @Query("select c from PaymentPlanConfiguration c where c.active=true and c.validFrom<=:date and (c.validUntil is null or c.validUntil>=:date) order by c.version desc")
    List<PaymentPlanConfiguration> findApplicable(@Param("date") LocalDate date,Pageable pageable);
}
interface PaymentPlanRequestRepository extends FilteredRepository<PaymentPlanRequest,Long> { Page<PaymentPlanRequest> findByTaxpayerId(Long taxpayerId,Pageable pageable); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PaymentPlanRequest r where r.id=:id") Optional<PaymentPlanRequest> findByIdForUpdate(Long id); }
interface PaymentPlanRequestDebtRepository extends JpaRepository<PaymentPlanRequestDebt,Long> { List<PaymentPlanRequestDebt> findByRequestId(Long requestId); }
interface PaymentPlanRepository extends FilteredRepository<PaymentPlan,Long> {
    boolean existsByDebtIdAndStatus(Long debtId, PaymentPlanStatus status);
    long countByTaxpayerIdAndStatus(Long taxpayerId,PaymentPlanStatus status);
    Page<PaymentPlan> findByTaxpayerId(Long taxpayerId,Pageable pageable);
    @Query("select p.debtId from PaymentPlan p where p.debtId in :debtIds and p.status=:status") Set<Long> findDebtIdsByStatus(@Param("debtIds") Collection<Long> debtIds,@Param("status") PaymentPlanStatus status);
    @Query("""
        select p from PaymentPlan p where p.status=ar.gob.municipalidad.rentas.PaymentPlanStatus.ACTIVE
          and (select count(i.id) from Installment i where i.paymentPlanId=p.id
            and i.status not in (ar.gob.municipalidad.rentas.InstallmentStatus.PAID,ar.gob.municipalidad.rentas.InstallmentStatus.CANCELLED)
            and i.outstandingAmount>0 and i.dueDate<:today)
          > (select c.maxOverdueInstallments from PaymentPlanConfiguration c where c.id=p.configurationId)
        """) Page<PaymentPlan> findDefaulted(@Param("today") LocalDate today,Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PaymentPlan p where p.id=:id") Optional<PaymentPlan> findByIdForUpdate(Long id);
}
interface PaymentPlanDebtRepository extends JpaRepository<PaymentPlanDebt,Long> {
    boolean existsByDebtIdAndStatus(Long debtId,PaymentPlanDebtStatus status);
    @Query("select d.debtId from PaymentPlanDebt d where d.debtId in :debtIds and d.status=:status") Set<Long> findDebtIdsByStatus(@Param("debtIds") Collection<Long> debtIds,@Param("status") PaymentPlanDebtStatus status);
    List<PaymentPlanDebt> findByPaymentPlanId(Long paymentPlanId);
}
interface InstallmentRepository extends JpaRepository<Installment,Long> { List<Installment> findByPaymentPlanIdOrderByNumber(Long planId); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from Installment i where i.id=:id") Optional<Installment> findByIdForUpdate(Long id); }
interface PlanExpirationRepository extends FilteredRepository<PlanExpirationRequest,Long> { boolean existsByPaymentPlanIdAndStatus(Long planId,PlanExpirationStatus status); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from PlanExpirationRequest e where e.id=:id") Optional<PlanExpirationRequest> findByIdForUpdate(Long id); }
interface RefinancingRequestRepository extends FilteredRepository<RefinancingRequest,Long> { @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from RefinancingRequest r where r.id=:id") Optional<RefinancingRequest> findByIdForUpdate(Long id); }
interface AdjustmentRepository extends FilteredRepository<AdjustmentRequest,Long> {}
interface LiquidationRunRepository extends FilteredRepository<LiquidationRun,Long> { @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from LiquidationRun r where r.id=:id") Optional<LiquidationRun> findByIdForUpdate(Long id); }
interface LiquidationRunItemRepository extends JpaRepository<LiquidationRunItem,Long> { List<LiquidationRunItem> findByLiquidationRunIdOrderById(Long runId); }
interface TicketCaseRepository extends FilteredRepository<TicketCase,Long> { Optional<TicketCase> findByExternalTicketId(String externalTicketId); }
interface TicketCaseUpdateRepository extends JpaRepository<TicketCaseUpdate,Long> { List<TicketCaseUpdate> findByTicketCaseIdOrderByCreatedAt(Long ticketCaseId); }
interface SocialBenefitRepository extends FilteredRepository<SocialBenefitReference,Long> { Optional<SocialBenefitReference> findByExternalBenefitId(String externalBenefitId); List<SocialBenefitReference> findByTaxpayerId(Long taxpayerId); }
interface SocialBenefitTaxConceptRepository extends JpaRepository<SocialBenefitTaxConcept,Long> { List<SocialBenefitTaxConcept> findBySocialBenefitId(Long benefitId); boolean existsBySocialBenefitIdAndTaxConceptId(Long benefitId,Long conceptId); void deleteBySocialBenefitId(Long benefitId); }
interface TaxpayerRepresentationRepository extends JpaRepository<TaxpayerRepresentationReference,Long> { Optional<TaxpayerRepresentationReference> findByExternalRepresentationId(String externalRepresentationId); }
interface ExemptionRequestRepository extends FilteredRepository<ExemptionRequest,Long> { Page<ExemptionRequest> findByTaxpayerId(Long taxpayerId,Pageable pageable); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from ExemptionRequest e where e.id=:id") Optional<ExemptionRequest> findByIdForUpdate(Long id); }
interface ExemptionRequestDocumentRepository extends JpaRepository<ExemptionRequestDocument,Long> { List<ExemptionRequestDocument> findByExemptionRequestId(Long requestId); }
interface ExemptionRepository extends FilteredRepository<Exemption,Long> {
    List<Exemption> findByTaxpayerIdAndTaxConceptIdAndStatus(Long taxpayerId,Long conceptId,String status);
    List<Exemption> findByTaxpayerId(Long taxpayerId);
}
