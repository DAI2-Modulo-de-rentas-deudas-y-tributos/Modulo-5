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

@org.springframework.data.repository.NoRepositoryBean
interface FilteredRepository<T,ID> extends JpaRepository<T,ID>,JpaSpecificationExecutor<T> {}

interface TaxpayerRepository extends FilteredRepository<TaxpayerReference,Long> {
    Optional<TaxpayerReference> findByTaxpayerTypeAndExternalId(TaxpayerType type, String externalId);
}
interface TaxConceptRepository extends FilteredRepository<TaxConcept,Long> { Optional<TaxConcept> findByCode(String code); }
interface TaxConfigurationRepository extends FilteredRepository<TaxConfiguration,Long> {
    Optional<TaxConfiguration> findFirstByTaxConceptIdAndStatusAndValidFromLessThanEqualAndValidUntilGreaterThanEqualOrderByVersionDesc(Long conceptId, TaxConfigurationStatus status, LocalDate from, LocalDate until);
    Optional<TaxConfiguration> findFirstByTaxConceptIdOrderByVersionDesc(Long conceptId);
}
interface LiquidationRepository extends FilteredRepository<Liquidation,Long> { List<Liquidation> findByTaxpayerId(Long taxpayerId); boolean existsByTaxpayerIdAndTaxConceptIdAndPeriod(Long taxpayerId,Long conceptId,String period); }
interface LiquidationComponentRepository extends JpaRepository<LiquidationComponent,Long> { List<LiquidationComponent> findByLiquidationIdOrderById(Long liquidationId); }
interface DebtRepository extends FilteredRepository<Debt,Long> { List<Debt> findByTaxpayerId(Long taxpayerId); boolean existsByExternalObligationId(Long externalObligationId); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from Debt d where d.id=:id") Optional<Debt> findByIdForUpdate(Long id); }
interface ExternalObligationRepository extends FilteredRepository<ExternalObligation,Long> {
    Optional<ExternalObligation> findBySourceModuleAndExternalTypeAndExternalReferenceId(String module, ExternalObligationType type, String reference);
    Page<ExternalObligation> findByStatus(ExternalObligationStatus status,Pageable pageable);
}
interface PaymentRepository extends FilteredRepository<Payment,Long> { List<Payment> findByTaxpayerId(Long taxpayerId); Page<Payment> findByTaxpayerId(Long taxpayerId,Pageable pageable); Page<Payment> findByUnallocatedAmountGreaterThan(BigDecimal amount,Pageable pageable); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from Payment p where p.id=:id") Optional<Payment> findByIdForUpdate(Long id); }
interface PaymentAllocationRepository extends FilteredRepository<PaymentAllocation,Long> { List<PaymentAllocation> findByPaymentId(Long paymentId); }
interface BillRepository extends FilteredRepository<Bill,Long> { List<Bill> findByTaxpayerId(Long taxpayerId); Page<Bill> findByTaxpayerId(Long taxpayerId,Pageable pageable); }
interface BillDebtRepository extends JpaRepository<BillDebt,Long> { List<BillDebt> findByBillId(Long billId); }
interface ElectronicPaymentRepository extends JpaRepository<ElectronicPaymentAttempt,Long> { Optional<ElectronicPaymentAttempt> findByPaymentId(Long paymentId); }
interface CreditBalanceRepository extends FilteredRepository<CreditBalance,Long> { Optional<CreditBalance> findBySourcePaymentId(Long paymentId); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from CreditBalance c where c.sourcePaymentId=:paymentId") Optional<CreditBalance> findBySourcePaymentIdForUpdate(Long paymentId); List<CreditBalance> findByTaxpayerId(Long taxpayerId); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from CreditBalance c where c.id=:id") Optional<CreditBalance> findByIdForUpdate(Long id); }
interface CreditBalanceApplicationRepository extends JpaRepository<CreditBalanceApplication,Long> {}
interface PaymentReversalRepository extends FilteredRepository<PaymentReversalRequest,Long> { @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PaymentReversalRequest r where r.id=:id") Optional<PaymentReversalRequest> findByIdForUpdate(Long id); boolean existsByPaymentIdAndStatusIn(Long paymentId,Collection<PaymentReversalStatus> statuses); }
interface ProcessedEventRepository extends JpaRepository<ProcessedEvent,UUID> {}
interface IntegrationEventLogRepository extends FilteredRepository<IntegrationEventLog,Long> { Optional<IntegrationEventLog> findFirstByEventIdOrderByIdDesc(UUID eventId); Page<IntegrationEventLog> findByStatusIn(Collection<IntegrationEventStatus> statuses,Pageable pageable); }
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
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PaymentPlan p where p.id=:id") Optional<PaymentPlan> findByIdForUpdate(Long id);
    Page<PaymentPlan> findByTaxpayerId(Long taxpayerId,Pageable pageable);
}
interface PaymentPlanDebtRepository extends JpaRepository<PaymentPlanDebt,Long> {
    boolean existsByDebtIdAndStatus(Long debtId,PaymentPlanDebtStatus status);
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
interface ExemptionRequestRepository extends FilteredRepository<ExemptionRequest,Long> { Page<ExemptionRequest> findByTaxpayerId(Long taxpayerId,Pageable pageable); @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from ExemptionRequest e where e.id=:id") Optional<ExemptionRequest> findByIdForUpdate(Long id); }
interface ExemptionRequestDocumentRepository extends JpaRepository<ExemptionRequestDocument,Long> { List<ExemptionRequestDocument> findByExemptionRequestId(Long requestId); }
interface ExemptionRepository extends FilteredRepository<Exemption,Long> {
    List<Exemption> findByTaxpayerIdAndTaxConceptIdAndStatus(Long taxpayerId,Long conceptId,String status);
    List<Exemption> findByTaxpayerId(Long taxpayerId);
}
