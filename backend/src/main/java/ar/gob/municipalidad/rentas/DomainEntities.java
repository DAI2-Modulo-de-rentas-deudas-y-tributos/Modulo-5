package ar.gob.municipalidad.rentas;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

enum TaxpayerType { CITIZEN, ORGANIZATION }
enum TaxpayerStatus { ACTIVE, BLOCKED, DECEASED, INACTIVE }
enum TaxConceptType { FEE, FINE, CHARGE }
enum CalculationType { PERCENTAGE, FIXED, EXTERNAL }
enum TaxConfigurationStatus { DRAFT, PENDING_APPROVAL, ACTIVE, REJECTED, INACTIVE }
enum DebtOriginType { LIQUIDATION, EXTERNAL_OBLIGATION }
enum DebtStatus { PENDING, PARTIALLY_PAID, PAID, CANCELLED }
enum PaymentMethod { CASH, CARD, TRANSFER, DIGITAL_WALLET }
enum PaymentStatus { CONFIRMED, REVERSED }
enum PaymentOrigin { CASHIER, ELECTRONIC, EXTERNAL }
enum PaymentAllocationStatus { UNALLOCATED, PARTIALLY_ALLOCATED, FULLY_ALLOCATED }
enum AllocationTargetType { DEBT, INSTALLMENT }
enum PaymentReversalStatus { PENDING_APPROVAL, APPROVED, REJECTED, EXECUTED }
enum CreditBalanceStatus { AVAILABLE, PARTIALLY_USED, USED, CANCELLED }
enum BillStatus { ISSUED, CANCELLED }
enum ElectronicPaymentStatus { PREVIEWED, APPROVED, REJECTED }
enum ExternalObligationType { PERMIT_FEE, COMMERCIAL_FINE, TRAFFIC_INFRACTION }
enum ExternalObligationStatus { RECEIVED, PROCESSED, ERROR }
enum IntegrationEventStatus { RECEIVED, PENDING, PROCESSED, IGNORED, PUBLISHED, FAILED, DEAD_LETTER }
enum EventDirection { INBOUND, OUTBOUND }
enum OutboxStatus { PENDING, PUBLISHED, FAILED, DEAD_LETTER }
enum PaymentPlanStatus { ACTIVE, COMPLETED, EXPIRED, REFINANCED, CANCELLED }
enum PaymentPlanRequestStatus { PENDING, PENDING_EXCEPTION_APPROVAL, GRANTED, REJECTED }
enum PaymentPlanDebtStatus { ACTIVE, SETTLED, RELEASED }
enum InstallmentType { DOWN_PAYMENT, REGULAR }
enum InstallmentStatus { PENDING, PARTIALLY_PAID, OVERDUE, PAID, CANCELLED }
enum PlanExpirationStatus { PENDING_APPROVAL, APPROVED, REJECTED }
enum RefinancingRequestStatus { PENDING, PENDING_EXCEPTION_APPROVAL, GRANTED, REJECTED }
enum AdjustmentType { DISCOUNT, SURCHARGE, INTEREST, CORRECTION }
enum AdjustmentStatus { PENDING_APPROVAL, APPROVED, REJECTED }
enum LiquidationRunStatus { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, EXECUTED }
enum LiquidationRunItemStatus { PENDING, VALID, ERROR, LIQUIDATED }
enum TicketPriority { LOW, MEDIUM, HIGH }
enum TicketCaseStatus { OPEN, IN_PROGRESS, WAITING_FOR_INFORMATION, COMPLETED, REJECTED }
enum TicketUpdateType { INTERNAL_NOTE, INFORMATION_REQUEST, RESOLUTION, REJECTION }
enum SocialBenefitStatus { ACTIVE, SUSPENDED, EXPIRED, CANCELLED }
enum ExemptionRequestStatus { PENDING, UNDER_REVIEW, DOCUMENTATION_REQUIRED, PENDING_RESOLUTION, APPROVED, REJECTED }
enum LiquidationComponentType { BASE, DISCOUNT, EXEMPTION, SOCIAL_BENEFIT, SURCHARGE, INTEREST }

@Entity @Table(name="taxpayer_reference", uniqueConstraints=@UniqueConstraint(columnNames={"taxpayer_type","external_id"}))
class TaxpayerReference {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Enumerated(EnumType.STRING) @Column(name="taxpayer_type", nullable=false) public TaxpayerType taxpayerType;
    @Column(name="external_id", nullable=false) public String externalId;
    public String dni; public String cuit;
    @Column(name="display_name", nullable=false) public String displayName;
    @Enumerated(EnumType.STRING) @Column(name="external_status", nullable=false) public TaxpayerStatus externalStatus;
    @Column(name="created_at", nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at", nullable=false) public OffsetDateTime updatedAt;
    protected TaxpayerReference() {}
}

@Entity @Table(name="tax_concept")
class TaxConcept {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false, unique=true, updatable=false) public String code;
    @Column(nullable=false) public String name;
    public String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public TaxConceptType type;
    @Column(name="origin_module", nullable=false) public String originModule;
    @Column(nullable=false) public boolean active;
    @Column(name="created_at", nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at", nullable=false) public OffsetDateTime updatedAt;
    protected TaxConcept() {}
}

@Entity @Table(name="tax_configuration", uniqueConstraints=@UniqueConstraint(columnNames={"tax_concept_id","version"}))
class TaxConfiguration {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="tax_concept_id", nullable=false) public Long taxConceptId;
    @Column(nullable=false) public int version;
    @Enumerated(EnumType.STRING) @Column(name="calculation_type", nullable=false) public CalculationType calculationType;
    @Column(precision=19,scale=4) public BigDecimal rate;
    @Column(name="fixed_amount",precision=19,scale=2) public BigDecimal fixedAmount;
    @Column(name="minimum_amount",precision=19,scale=2) public BigDecimal minimumAmount;
    @Column(name="maximum_amount",precision=19,scale=2) public BigDecimal maximumAmount;
    @Column(name="partial_payment_allowed",nullable=false) public boolean partialPaymentAllowed;
    @Column(name="payment_plan_allowed",nullable=false) public boolean paymentPlanAllowed;
    @Column(name="valid_from",nullable=false) public LocalDate validFrom;
    @Column(name="valid_until") public LocalDate validUntil;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public TaxConfigurationStatus status;
    @Column(name="created_by",nullable=false) public String createdBy;
    @Column(name="approved_by") public String approvedBy;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="approved_at") public OffsetDateTime approvedAt;
    protected TaxConfiguration() {}
}

@Entity @Table(name="liquidation",uniqueConstraints=@UniqueConstraint(columnNames={"taxpayer_id","tax_concept_id","period"}))
class Liquidation {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="tax_concept_id",nullable=false) public Long taxConceptId;
    @Column(name="tax_configuration_id",nullable=false) public Long taxConfigurationId;
    @Column(name="configuration_version",nullable=false) public int configurationVersion;
    @Column(nullable=false,length=7) public String period;
    @Column(name="taxable_base",nullable=false,precision=19,scale=2) public BigDecimal taxableBase;
    @Column(name="base_amount",nullable=false,precision=19,scale=2) public BigDecimal baseAmount;
    @Column(name="discount_amount",nullable=false,precision=19,scale=2) public BigDecimal discountAmount;
    @Column(name="exemption_amount",nullable=false,precision=19,scale=2) public BigDecimal exemptionAmount;
    @Column(name="surcharge_amount",nullable=false,precision=19,scale=2) public BigDecimal surchargeAmount;
    @Column(name="interest_amount",nullable=false,precision=19,scale=2) public BigDecimal interestAmount;
    @Column(name="final_amount",nullable=false,precision=19,scale=2) public BigDecimal finalAmount;
    @Column(name="due_date",nullable=false) public LocalDate dueDate;
    @Column(nullable=false) public String status;
    @Column(name="created_by",nullable=false) public String createdBy;
    @Column(name="issued_at",nullable=false) public OffsetDateTime issuedAt;
    protected Liquidation() {}
}

@Entity @Table(name="liquidation_component")
class LiquidationComponent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="liquidation_id",nullable=false) public Long liquidationId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public LiquidationComponentType type;
    @Column(name="source_type",nullable=false) public String sourceType;
    @Column(name="source_id") public String sourceId;
    @Column(nullable=false) public String description;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    protected LiquidationComponent() {}
}

@Entity @Table(name="external_obligation", uniqueConstraints=@UniqueConstraint(columnNames={"source_module","external_type","external_reference_id"}))
class ExternalObligation {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="source_module",nullable=false) public String sourceModule;
    @Enumerated(EnumType.STRING) @Column(name="external_type",nullable=false) public ExternalObligationType externalType;
    @Column(name="external_reference_id",nullable=false) public String externalReferenceId;
    @Column(name="source_event_id",nullable=false) public UUID sourceEventId;
    @Enumerated(EnumType.STRING) @Column(name="external_taxpayer_type",nullable=false) public TaxpayerType externalTaxpayerType;
    @Column(name="external_taxpayer_id",nullable=false) public String externalTaxpayerId;
    @Column(name="taxpayer_id") public Long taxpayerId;
    @Column(name="tax_concept_id") public Long taxConceptId;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    @Column(name="due_date",nullable=false) public LocalDate dueDate;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public ExternalObligationStatus status;
    @Column(name="error_message") public String errorMessage;
    @Column(name="retry_count",nullable=false) public int retryCount;
    @Column(name="received_at",nullable=false) public OffsetDateTime receivedAt;
    @Column(name="processed_at") public OffsetDateTime processedAt;
    protected ExternalObligation() {}
}

@Entity @Table(name="debt")
class Debt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="tax_concept_id",nullable=false) public Long taxConceptId;
    @Enumerated(EnumType.STRING) @Column(name="origin_type",nullable=false) public DebtOriginType originType;
    @Column(name="liquidation_id") public Long liquidationId;
    @Column(name="external_obligation_id") public Long externalObligationId;
    @Column(name="configuration_id") public Long configurationId;
    @Column(name="original_amount",nullable=false,precision=19,scale=2) public BigDecimal originalAmount;
    @Column(name="current_amount",nullable=false,precision=19,scale=2) public BigDecimal currentAmount;
    @Column(name="outstanding_balance",nullable=false,precision=19,scale=2) public BigDecimal outstandingBalance;
    @Column(name="due_date",nullable=false) public LocalDate dueDate;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public DebtStatus status;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
    protected Debt() {}
}

@Entity @Table(name="payment")
class Payment {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="bill_id") public Long billId;
    @Enumerated(EnumType.STRING) @Column(name="payment_method",nullable=false) public PaymentMethod paymentMethod;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    @Column(name="allocated_amount",nullable=false,precision=19,scale=2) public BigDecimal allocatedAmount;
    @Column(name="unallocated_amount",nullable=false,precision=19,scale=2) public BigDecimal unallocatedAmount;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public PaymentStatus status;
    @Enumerated(EnumType.STRING) @Column(name="allocation_status",nullable=false) public PaymentAllocationStatus allocationStatus;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public PaymentOrigin origin;
    @Column(name="receipt_number",nullable=false,unique=true) public String receiptNumber;
    @Column(name="registered_by",nullable=false) public String registeredBy;
    @Column(name="paid_at",nullable=false) public OffsetDateTime paidAt;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    protected Payment() {}
}

@Entity @Table(name="bill")
class Bill {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false,unique=true) public String number;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="total_amount",nullable=false,precision=19,scale=2) public BigDecimal totalAmount;
    @Column(name="issue_date",nullable=false) public LocalDate issueDate;
    @Column(name="due_date",nullable=false) public LocalDate dueDate;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public BillStatus status;
    @Column(name="created_by",nullable=false) public String createdBy;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    protected Bill() {}
}

@Entity @Table(name="bill_debt",uniqueConstraints=@UniqueConstraint(columnNames={"bill_id","debt_id"}))
class BillDebt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="bill_id",nullable=false) public Long billId;
    @Column(name="debt_id",nullable=false) public Long debtId;
    @Column(name="amount_at_issue",nullable=false,precision=19,scale=2) public BigDecimal amountAtIssue;
    protected BillDebt() {}
}

@Entity @Table(name="electronic_payment_attempt")
class ElectronicPaymentAttempt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="payment_id") public Long paymentId;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="debt_id",nullable=false) public Long debtId;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public ElectronicPaymentStatus status;
    @Column(name="gateway_reference",nullable=false,unique=true) public String gatewayReference;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    protected ElectronicPaymentAttempt() {}
}

@Entity @Table(name="payment_allocation")
class PaymentAllocation {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="payment_id",nullable=false) public Long paymentId;
    @Enumerated(EnumType.STRING) @Column(name="target_type",nullable=false) public AllocationTargetType targetType;
    @Column(name="debt_id") public Long debtId;
    @Column(name="installment_id") public Long installmentId;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    @Column(name="principal_applied",nullable=false,precision=19,scale=2) public BigDecimal principalApplied;
    @Column(name="interest_applied",nullable=false,precision=19,scale=2) public BigDecimal interestApplied;
    @Column(nullable=false) public String status;
    @Column(name="allocated_by",nullable=false) public String allocatedBy;
    @Column(name="allocated_at",nullable=false) public OffsetDateTime allocatedAt;
    @Column(name="reversed_at") public OffsetDateTime reversedAt;
    protected PaymentAllocation() {}
}

@Entity @Table(name="payment_plan_configuration")
class PaymentPlanConfiguration {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false,unique=true) public int version;
    @Column(name="minimum_installments",nullable=false) public int minimumInstallments;
    @Column(name="maximum_installments",nullable=false) public int maximumInstallments;
    @Column(name="minimum_down_payment_percentage",nullable=false,precision=5,scale=2) public BigDecimal minimumDownPaymentPercentage;
    @Column(name="interest_rate",nullable=false,precision=7,scale=4) public BigDecimal interestRate;
    @Column(name="grace_days",nullable=false) public int graceDays;
    @Column(name="max_overdue_installments",nullable=false) public int maxOverdueInstallments;
    @Column(name="partial_installment_payment_allowed",nullable=false) public boolean partialInstallmentPaymentAllowed;
    @Column(name="refinancing_allowed",nullable=false) public boolean refinancingAllowed;
    @Column(name="max_refinancing_count",nullable=false) public int maxRefinancingCount;
    @Column(name="valid_from",nullable=false) public LocalDate validFrom;
    @Column(name="valid_until") public LocalDate validUntil;
    @Column(nullable=false) public boolean active;
    @Column(name="created_by",nullable=false) public String createdBy;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    protected PaymentPlanConfiguration() {}
}

@Entity @Table(name="payment_plan_request")
class PaymentPlanRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="requested_installments",nullable=false) public int requestedInstallments;
    @Column(name="total_debt_at_request",nullable=false,precision=19,scale=2) public BigDecimal totalDebtAtRequest;
    @Column(name="estimated_down_payment",nullable=false,precision=19,scale=2) public BigDecimal estimatedDownPayment;
    @Column(name="estimated_financed_amount",nullable=false,precision=19,scale=2) public BigDecimal estimatedFinancedAmount;
    @Column(name="estimated_interest",nullable=false,precision=19,scale=2) public BigDecimal estimatedInterest;
    @Column(name="estimated_total_amount",nullable=false,precision=19,scale=2) public BigDecimal estimatedTotalAmount;
    @Column(nullable=false) public boolean exceptional;
    @Column(name="exception_reason") public String exceptionReason;
    @Column(name="exception_approved",nullable=false) public boolean exceptionApproved;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public PaymentPlanRequestStatus status;
    @Column(name="requested_by",nullable=false) public String requestedBy;
    @Column(name="requested_at",nullable=false) public OffsetDateTime requestedAt;
    @Column(name="resolved_by") public String resolvedBy;
    @Column(name="resolved_at") public OffsetDateTime resolvedAt;
    @Column(name="resolution_reason") public String resolutionReason;
    @Column(name="payment_plan_id") public Long paymentPlanId;
    protected PaymentPlanRequest() {}
}

@Entity @Table(name="payment_plan_request_debt",uniqueConstraints=@UniqueConstraint(columnNames={"request_id","debt_id"}))
class PaymentPlanRequestDebt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="request_id",nullable=false) public Long requestId;
    @Column(name="debt_id",nullable=false) public Long debtId;
    @Column(name="balance_at_request",nullable=false,precision=19,scale=2) public BigDecimal balanceAtRequest;
    protected PaymentPlanRequestDebt() {}
}

@Entity @Table(name="credit_balance")
class CreditBalance {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="source_payment_id",nullable=false) public Long sourcePaymentId;
    @Column(name="original_amount",nullable=false,precision=19,scale=2) public BigDecimal originalAmount;
    @Column(name="available_amount",nullable=false,precision=19,scale=2) public BigDecimal availableAmount;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public CreditBalanceStatus status;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
    protected CreditBalance() {}
}

@Entity @Table(name="credit_balance_application")
class CreditBalanceApplication {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="credit_balance_id",nullable=false) public Long creditBalanceId;
    @Column(name="debt_id",nullable=false) public Long debtId;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    @Column(nullable=false) public String status;
    @Column(name="applied_by",nullable=false) public String appliedBy;
    @Column(name="applied_at",nullable=false) public OffsetDateTime appliedAt;
    @Column(name="reversed_at") public OffsetDateTime reversedAt;
    protected CreditBalanceApplication() {}
}

@Entity @Table(name="payment_reversal_request")
class PaymentReversalRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="payment_id",nullable=false) public Long paymentId;
    @Column(nullable=false) public String reason;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public PaymentReversalStatus status;
    @Column(name="requested_by",nullable=false) public String requestedBy;
    @Column(name="requested_at",nullable=false) public OffsetDateTime requestedAt;
    @Column(name="resolved_by") public String resolvedBy;
    @Column(name="resolved_at") public OffsetDateTime resolvedAt;
    @Column(name="executed_by") public String executedBy;
    @Column(name="executed_at") public OffsetDateTime executedAt;
    protected PaymentReversalRequest() {}
}

@Entity @Table(name="processed_event")
class ProcessedEvent {
    @Id @Column(name="event_id") public UUID eventId;
    @Column(name="external_event_id",nullable=false,unique=true) public String externalEventId;
    @Column(name="event_type",nullable=false) public String eventType;
    @Column(name="source_module",nullable=false) public String sourceModule;
    @Column(name="received_at",nullable=false) public OffsetDateTime receivedAt;
    @Column(name="processed_at",nullable=false) public OffsetDateTime processedAt;
    protected ProcessedEvent() {}
}

@Entity @Table(name="integration_event_log")
class IntegrationEventLog {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="event_id",nullable=false) public UUID eventId;
    @Column(name="external_event_id",nullable=false) public String externalEventId;
    @Column(name="event_type",nullable=false) public String eventType;
    @Column(name="source_module",nullable=false) public String sourceModule;
    @Column(name="target_module") public String targetModule;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public EventDirection direction;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public IntegrationEventStatus status;
    @Column(nullable=false,columnDefinition="text") public String payload;
    @Column(name="retry_count",nullable=false) public int retryCount;
    @Column(name="error_message") public String errorMessage;
    @Column(name="occurred_at",nullable=false) public OffsetDateTime occurredAt;
    @Column(name="received_at",nullable=false) public OffsetDateTime receivedAt;
    @Column(name="processed_at") public OffsetDateTime processedAt;
    @Column(name="last_retry_at") public OffsetDateTime lastRetryAt;
    protected IntegrationEventLog() {}
}

@Entity @Table(name="outbox_event")
class OutboxEvent {
    @Id public UUID id;
    @Column(name="event_type",nullable=false) public String eventType;
    @Column(name="target_module",nullable=false) public String targetModule;
    @Column(name="aggregate_type",nullable=false) public String aggregateType;
    @Column(name="aggregate_id",nullable=false) public String aggregateId;
    @Column(nullable=false,columnDefinition="text") public String payload;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public OutboxStatus status;
    @Column(name="retry_count",nullable=false) public int retryCount;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="published_at") public OffsetDateTime publishedAt;
    @Column(name="last_attempt_at") public OffsetDateTime lastAttemptAt;
    @Column(name="error_message") public String errorMessage;
    protected OutboxEvent() {}
}

@Entity @Table(name="audit_entry")
class AuditEntry {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="entity_type",nullable=false) public String entityType;
    @Column(name="entity_id",nullable=false) public String entityId;
    @Column(nullable=false) public String action;
    @Column(name="user_id",nullable=false) public String userId;
    @Column(name="user_role",nullable=false) public String userRole;
    @Column(name="previous_data",columnDefinition="text") public String previousData;
    @Column(name="new_data",columnDefinition="text") public String newData;
    @Column(name="correlation_id") public String correlationId;
    @Column(name="occurred_at",nullable=false) public OffsetDateTime occurredAt;
    protected AuditEntry() {}
}

@Entity @Table(name="payment_plan")
class PaymentPlan {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="debt_id") public Long debtId;
    @Column(name="request_id") public Long requestId;
    @Column(name="configuration_id") public Long configurationId;
    @Column(name="configuration_version") public Integer configurationVersion;
    @Column(name="original_principal_amount",nullable=false,precision=19,scale=2) public BigDecimal originalPrincipalAmount;
    @Column(name="down_payment_amount",nullable=false,precision=19,scale=2) public BigDecimal downPaymentAmount;
    @Column(name="financed_principal_amount",nullable=false,precision=19,scale=2) public BigDecimal financedPrincipalAmount;
    @Column(name="financing_interest_amount",nullable=false,precision=19,scale=2) public BigDecimal financingInterestAmount;
    @Column(name="total_plan_amount",nullable=false,precision=19,scale=2) public BigDecimal totalPlanAmount;
    @Column(name="paid_amount",nullable=false,precision=19,scale=2) public BigDecimal paidAmount;
    @Column(name="outstanding_plan_amount",nullable=false,precision=19,scale=2) public BigDecimal outstandingPlanAmount;
    @Column(name="installment_count",nullable=false) public int installmentCount;
    @Column(name="refinancing_count",nullable=false) public int refinancingCount;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public PaymentPlanStatus status;
    @Column(name="granted_by",nullable=false) public String grantedBy;
    @Column(name="granted_at",nullable=false) public OffsetDateTime grantedAt;
    @Column(name="completed_at") public OffsetDateTime completedAt;
    @Column(name="expired_at") public OffsetDateTime expiredAt;
    @Column(name="refinanced_at") public OffsetDateTime refinancedAt;
    protected PaymentPlan() {}
}

@Entity @Table(name="payment_plan_debt",uniqueConstraints=@UniqueConstraint(columnNames={"payment_plan_id","debt_id"}))
class PaymentPlanDebt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="payment_plan_id",nullable=false) public Long paymentPlanId;
    @Column(name="debt_id",nullable=false) public Long debtId;
    @Column(name="included_principal_amount",nullable=false,precision=19,scale=2) public BigDecimal includedPrincipalAmount;
    @Column(name="principal_paid_amount",nullable=false,precision=19,scale=2) public BigDecimal principalPaidAmount;
    @Column(name="remaining_principal_amount",nullable=false,precision=19,scale=2) public BigDecimal remainingPrincipalAmount;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public PaymentPlanDebtStatus status;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    protected PaymentPlanDebt() {}
}

@Entity @Table(name="installment", uniqueConstraints=@UniqueConstraint(columnNames={"payment_plan_id","number"}))
class Installment {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="payment_plan_id",nullable=false) public Long paymentPlanId;
    @Column(nullable=false) public int number;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public InstallmentType type;
    @Column(name="principal_amount",nullable=false,precision=19,scale=2) public BigDecimal principalAmount;
    @Column(name="interest_amount",nullable=false,precision=19,scale=2) public BigDecimal interestAmount;
    @Column(name="total_amount",nullable=false,precision=19,scale=2) public BigDecimal totalAmount;
    @Column(name="paid_amount",nullable=false,precision=19,scale=2) public BigDecimal paidAmount;
    @Column(name="outstanding_amount",nullable=false,precision=19,scale=2) public BigDecimal outstandingAmount;
    @Column(name="due_date",nullable=false) public LocalDate dueDate;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public InstallmentStatus status;
    @Column(name="paid_at") public OffsetDateTime paidAt;
    protected Installment() {}
}

@Entity @Table(name="plan_expiration_request")
class PlanExpirationRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="payment_plan_id",nullable=false) public Long paymentPlanId;
    @Column(nullable=false) public String reason;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public PlanExpirationStatus status;
    @Column(name="requested_by",nullable=false) public String requestedBy;
    @Column(name="requested_at",nullable=false) public OffsetDateTime requestedAt;
    @Column(name="resolved_by") public String resolvedBy;
    @Column(name="resolved_at") public OffsetDateTime resolvedAt;
    @Column(name="resolution_observation") public String resolutionObservation;
    protected PlanExpirationRequest() {}
}

@Entity @Table(name="refinancing_request")
class RefinancingRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="original_plan_id",nullable=false) public Long originalPlanId;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="requested_installments",nullable=false) public int requestedInstallments;
    @Column(name="outstanding_principal_at_request",nullable=false,precision=19,scale=2) public BigDecimal outstandingPrincipalAtRequest;
    @Column(name="estimated_interest",nullable=false,precision=19,scale=2) public BigDecimal estimatedInterest;
    @Column(name="estimated_total_amount",nullable=false,precision=19,scale=2) public BigDecimal estimatedTotalAmount;
    @Column(nullable=false) public boolean exceptional;
    @Column(name="exception_reason") public String exceptionReason;
    @Column(name="exception_approved",nullable=false) public boolean exceptionApproved;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public RefinancingRequestStatus status;
    @Column(name="requested_by",nullable=false) public String requestedBy;
    @Column(name="requested_at",nullable=false) public OffsetDateTime requestedAt;
    @Column(name="resolved_by") public String resolvedBy;
    @Column(name="resolved_at") public OffsetDateTime resolvedAt;
    @Column(name="new_payment_plan_id") public Long newPaymentPlanId;
    protected RefinancingRequest() {}
}

@Entity @Table(name="adjustment_request")
class AdjustmentRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="debt_id",nullable=false) public Long debtId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public AdjustmentType type;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    @Column(nullable=false) public String reason;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public AdjustmentStatus status;
    @Column(name="requested_by",nullable=false) public String requestedBy;
    @Column(name="requested_at",nullable=false) public OffsetDateTime requestedAt;
    @Column(name="resolved_by") public String resolvedBy;
    @Column(name="resolved_at") public OffsetDateTime resolvedAt;
    @Column(name="resolution_reason") public String resolutionReason;
    @Column(name="previous_debt_amount",precision=19,scale=2) public BigDecimal previousDebtAmount;
    @Column(name="new_debt_amount",precision=19,scale=2) public BigDecimal newDebtAmount;
    protected AdjustmentRequest() {}
}

@Entity @Table(name="liquidation_run")
class LiquidationRun {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="tax_concept_id",nullable=false) public Long taxConceptId;
    @Column(nullable=false,length=7) public String period;
    @Column(name="due_date",nullable=false) public LocalDate dueDate;
    @Column(name="configuration_id") public Long configurationId;
    @Column(name="configuration_version") public Integer configurationVersion;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public LiquidationRunStatus status;
    @Column(name="total_items",nullable=false) public int totalItems;
    @Column(name="valid_items",nullable=false) public int validItems;
    @Column(name="error_items",nullable=false) public int errorItems;
    @Column(name="estimated_total_amount",nullable=false,precision=19,scale=2) public BigDecimal estimatedTotalAmount;
    @Column(name="created_by",nullable=false) public String createdBy;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="submitted_at") public OffsetDateTime submittedAt;
    @Column(name="resolved_by") public String resolvedBy;
    @Column(name="resolved_at") public OffsetDateTime resolvedAt;
    protected LiquidationRun() {}
}

@Entity @Table(name="liquidation_run_item")
class LiquidationRunItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="liquidation_run_id",nullable=false) public Long liquidationRunId;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="taxable_base",nullable=false,precision=19,scale=2) public BigDecimal taxableBase;
    @Column(name="preview_amount",precision=19,scale=2) public BigDecimal previewAmount;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public LiquidationRunItemStatus status;
    @Column(name="error_code") public String errorCode;
    @Column(name="error_message") public String errorMessage;
    @Column(name="liquidation_id") public Long liquidationId;
    protected LiquidationRunItem() {}
}

@Entity @Table(name="ticket_case")
class TicketCase {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="external_ticket_id",nullable=false,unique=true) public String externalTicketId;
    @Column(name="taxpayer_id") public Long taxpayerId;
    @Column(name="external_citizen_id") public String externalCitizenId;
    @Column(nullable=false) public String category;
    @Column(nullable=false,columnDefinition="text") public String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public TicketPriority priority;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public TicketCaseStatus status;
    @Column(name="assigned_to") public String assignedTo;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
    @Column(name="completed_at") public OffsetDateTime completedAt;
    protected TicketCase() {}
}

@Entity @Table(name="ticket_case_update")
class TicketCaseUpdate {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="ticket_case_id",nullable=false) public Long ticketCaseId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public TicketUpdateType type;
    @Column(nullable=false,columnDefinition="text") public String message;
    @Column(name="created_by",nullable=false) public String createdBy;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    protected TicketCaseUpdate() {}
}

@Entity @Table(name="social_benefit_reference")
class SocialBenefitReference {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="external_benefit_id",nullable=false,unique=true) public String externalBenefitId;
    @Column(name="taxpayer_id") public Long taxpayerId;
    @Column(name="external_citizen_id",nullable=false) public String externalCitizenId;
    @Column(name="benefit_type",nullable=false) public String benefitType;
    @Column(name="external_status",nullable=false) public String externalStatus;
    @Enumerated(EnumType.STRING) @Column(name="calculated_status",nullable=false) public SocialBenefitStatus calculatedStatus;
    @Column(name="external_application_id") public String externalApplicationId;
    @Column(name="external_program_id") public String externalProgramId;
    @Column(name="program_name") public String programName;
    @Column(name="benefits_payload",nullable=false,columnDefinition="text") public String benefitsPayload;
    @Column(name="discount_percentage",precision=5,scale=2) public BigDecimal discountPercentage;
    @Column(name="valid_from",nullable=false) public LocalDate validFrom;
    @Column(name="valid_until") public LocalDate validUntil;
    @Column(name="source_event_id",nullable=false) public UUID sourceEventId;
    @Column(name="external_source_event_id",nullable=false) public String externalSourceEventId;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
    protected SocialBenefitReference() {}
}

@Entity @Table(name="taxpayer_representation_reference",uniqueConstraints=@UniqueConstraint(columnNames="external_representation_id"))
class TaxpayerRepresentationReference {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="external_representation_id",nullable=false) public String externalRepresentationId;
    @Column(name="external_person_id",nullable=false) public String externalPersonId;
    @Column(name="external_organization_id",nullable=false) public String externalOrganizationId;
    @Column(nullable=false) public String scope;
    @Column(name="valid_from") public LocalDate validFrom;
    @Column(name="valid_until") public LocalDate validUntil;
    @Column(nullable=false) public String status;
    @Column(name="source_event_id",nullable=false) public String sourceEventId;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
    protected TaxpayerRepresentationReference() {}
}

@Entity @Table(name="social_benefit_tax_concept",uniqueConstraints=@UniqueConstraint(columnNames={"social_benefit_id","tax_concept_id"}))
class SocialBenefitTaxConcept {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="social_benefit_id",nullable=false) public Long socialBenefitId;
    @Column(name="tax_concept_id",nullable=false) public Long taxConceptId;
    protected SocialBenefitTaxConcept() {}
}

@Entity @Table(name="exemption_request")
class ExemptionRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="tax_concept_id",nullable=false) public Long taxConceptId;
    @Column(nullable=false) public String reason;
    @Column(name="requested_percentage",nullable=false,precision=5,scale=2) public BigDecimal requestedPercentage;
    @Column(name="requested_from",nullable=false) public LocalDate requestedFrom;
    @Column(name="requested_until") public LocalDate requestedUntil;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public ExemptionRequestStatus status;
    @Column(name="requested_by",nullable=false) public String requestedBy;
    @Column(name="requested_at",nullable=false) public OffsetDateTime requestedAt;
    @Column(name="reviewed_by") public String reviewedBy;
    @Column(name="review_started_at") public OffsetDateTime reviewStartedAt;
    @Column(name="resolution_submitted_by") public String resolutionSubmittedBy;
    @Column(name="resolution_submitted_at") public OffsetDateTime resolutionSubmittedAt;
    @Column(name="resolved_by") public String resolvedBy;
    @Column(name="resolved_at") public OffsetDateTime resolvedAt;
    @Column(name="resolution_reason") public String resolutionReason;
    protected ExemptionRequest() {}
}

@Entity @Table(name="exemption_request_document")
class ExemptionRequestDocument {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="exemption_request_id",nullable=false) public Long exemptionRequestId;
    @Column(name="external_document_id",nullable=false) public String externalDocumentId;
    @Column(name="document_type",nullable=false) public String documentType;
    @Column(name="file_name") public String fileName;
    @Column(name="uploaded_by",nullable=false) public String uploadedBy;
    @Column(name="uploaded_at",nullable=false) public OffsetDateTime uploadedAt;
    protected ExemptionRequestDocument() {}
}

@Entity @Table(name="exemption")
class Exemption {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="request_id",nullable=false,unique=true) public Long requestId;
    @Column(name="taxpayer_id",nullable=false) public Long taxpayerId;
    @Column(name="tax_concept_id",nullable=false) public Long taxConceptId;
    @Column(nullable=false,precision=5,scale=2) public BigDecimal percentage;
    @Column(name="valid_from",nullable=false) public LocalDate validFrom;
    @Column(name="valid_until") public LocalDate validUntil;
    @Column(nullable=false) public String status;
    @Column(name="approved_by",nullable=false) public String approvedBy;
    @Column(name="approved_at",nullable=false) public OffsetDateTime approvedAt;
    @Column(name="cancelled_at") public OffsetDateTime cancelledAt;
    protected Exemption() {}
}
