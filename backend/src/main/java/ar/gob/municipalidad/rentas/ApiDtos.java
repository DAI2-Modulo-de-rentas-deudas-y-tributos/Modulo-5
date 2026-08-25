package ar.gob.municipalidad.rentas;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {}

    public record TaxpayerResponse(Long id,TaxpayerType taxpayerType,String externalId,String dni,String cuit,String displayName,TaxpayerStatus status,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
    public record TaxConceptResponse(Long id,String code,String name,String description,TaxConceptType type,String originModule,boolean active,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
    public record TaxConfigurationResponse(Long id,Long taxConceptId,int version,CalculationType calculationType,BigDecimal rate,BigDecimal fixedAmount,BigDecimal minimumAmount,BigDecimal maximumAmount,boolean partialPaymentAllowed,boolean paymentPlanAllowed,LocalDate validFrom,LocalDate validUntil,TaxConfigurationStatus status,String createdBy,String approvedBy,OffsetDateTime createdAt,OffsetDateTime approvedAt) {}
    public record DebtResponse(Long id,Long taxpayerId,Long taxConceptId,DebtOriginType originType,Long liquidationId,Long externalObligationId,BigDecimal originalAmount,BigDecimal currentAmount,BigDecimal outstandingBalance,LocalDate dueDate,DebtStatus status,boolean overdue,boolean inPaymentPlan,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
    public record PaymentResponse(Long id,Long taxpayerId,Long billId,PaymentMethod paymentMethod,BigDecimal amount,BigDecimal allocatedAmount,BigDecimal unallocatedAmount,PaymentStatus status,PaymentAllocationStatus allocationStatus,PaymentOrigin origin,String receiptNumber,String registeredBy,OffsetDateTime paidAt,OffsetDateTime createdAt) {}
    public record PaymentAllocationResponse(Long id,Long paymentId,AllocationTargetType targetType,Long debtId,Long installmentId,BigDecimal amount,String status,String allocatedBy,OffsetDateTime allocatedAt,OffsetDateTime reversedAt) {}
    public record BillDebtResponse(Long debtId,BigDecimal amountAtIssue) {}
    public record BillResponse(Long id,String number,Long taxpayerId,BigDecimal totalAmount,LocalDate issueDate,LocalDate dueDate,BillStatus status,boolean expired,String createdBy,OffsetDateTime createdAt,List<BillDebtResponse> debts) {}
    public record CreditBalanceResponse(Long id,Long taxpayerId,Long sourcePaymentId,BigDecimal originalAmount,BigDecimal availableAmount,CreditBalanceStatus status,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
    public record CreditBalanceApplicationResponse(Long id,Long creditBalanceId,Long debtId,BigDecimal amount,String status,String appliedBy,OffsetDateTime appliedAt,OffsetDateTime reversedAt) {}
    public record PaymentReversalResponse(Long id,Long paymentId,String reason,PaymentReversalStatus status,String requestedBy,OffsetDateTime requestedAt,String resolvedBy,OffsetDateTime resolvedAt,String executedBy,OffsetDateTime executedAt) {}
    public record PaymentPlanConfigurationResponse(Long id,int version,int minimumInstallments,int maximumInstallments,BigDecimal minimumDownPaymentPercentage,BigDecimal interestRate,int graceDays,int maxOverdueInstallments,boolean partialInstallmentPaymentAllowed,boolean refinancingAllowed,int maxRefinancingCount,LocalDate validFrom,LocalDate validUntil,boolean active,String createdBy,OffsetDateTime createdAt) {}
    public record PaymentPlanRequestResponse(Long id,Long taxpayerId,int requestedInstallments,BigDecimal totalDebtAtRequest,BigDecimal estimatedDownPayment,BigDecimal estimatedFinancedAmount,BigDecimal estimatedInterest,BigDecimal estimatedTotalAmount,boolean exceptional,String exceptionReason,PaymentPlanRequestStatus status,String requestedBy,OffsetDateTime requestedAt,String resolvedBy,OffsetDateTime resolvedAt,String resolutionReason,Long paymentPlanId) {}
    public record PaymentPlanResponse(Long id,Long taxpayerId,Long requestId,Long configurationId,Integer configurationVersion,BigDecimal originalPrincipalAmount,BigDecimal downPaymentAmount,BigDecimal financedPrincipalAmount,BigDecimal financingInterestAmount,BigDecimal totalPlanAmount,BigDecimal paidAmount,BigDecimal outstandingPlanAmount,int installmentCount,PaymentPlanStatus status,int refinancingCount,String grantedBy,OffsetDateTime grantedAt,OffsetDateTime completedAt,OffsetDateTime expiredAt,OffsetDateTime refinancedAt) {}
    public record InstallmentResponse(Long id,Long paymentPlanId,int number,InstallmentType type,BigDecimal principalAmount,BigDecimal interestAmount,BigDecimal totalAmount,BigDecimal paidAmount,BigDecimal outstandingAmount,LocalDate dueDate,InstallmentStatus status,boolean overdue,OffsetDateTime paidAt) {}
    public record PlanExpirationResponse(Long id,Long paymentPlanId,String reason,PlanExpirationStatus status,String requestedBy,OffsetDateTime requestedAt,String resolvedBy,OffsetDateTime resolvedAt,String resolutionObservation) {}
    public record RefinancingRequestResponse(Long id,Long originalPlanId,Long taxpayerId,int requestedInstallments,BigDecimal outstandingPrincipalAtRequest,BigDecimal estimatedInterest,BigDecimal estimatedTotalAmount,boolean exceptional,String exceptionReason,RefinancingRequestStatus status,String requestedBy,OffsetDateTime requestedAt,String resolvedBy,OffsetDateTime resolvedAt,Long newPaymentPlanId) {}
    public record AdjustmentResponse(Long id,Long debtId,AdjustmentType type,BigDecimal amount,String reason,AdjustmentStatus status,String requestedBy,OffsetDateTime requestedAt,String resolvedBy,OffsetDateTime resolvedAt,String resolutionReason,BigDecimal previousDebtAmount,BigDecimal newDebtAmount) {}
    public record LiquidationRunResponse(Long id,Long taxConceptId,String period,LocalDate dueDate,Long configurationId,Integer configurationVersion,LiquidationRunStatus status,int totalItems,int validItems,int errorItems,BigDecimal estimatedTotalAmount,String createdBy,OffsetDateTime createdAt,OffsetDateTime submittedAt,String resolvedBy,OffsetDateTime resolvedAt) {}
    public record LiquidationRunItemResponse(Long id,Long liquidationRunId,Long taxpayerId,BigDecimal taxableBase,BigDecimal previewAmount,LiquidationRunItemStatus status,String errorCode,String errorMessage,Long liquidationId) {}
    public record LiquidationRunDetailResponse(LiquidationRunResponse run,List<LiquidationRunItemResponse> items) {}
    public record ExemptionRequestResponse(Long id,Long taxpayerId,Long taxConceptId,String reason,BigDecimal requestedPercentage,LocalDate requestedFrom,LocalDate requestedUntil,ExemptionRequestStatus status,String requestedBy,OffsetDateTime requestedAt,String reviewedBy,OffsetDateTime reviewStartedAt,String resolutionSubmittedBy,OffsetDateTime resolutionSubmittedAt,String resolvedBy,OffsetDateTime resolvedAt,String resolutionReason) {}
    public record ExemptionResponse(Long id,Long requestId,Long taxpayerId,Long taxConceptId,BigDecimal percentage,LocalDate validFrom,LocalDate validUntil,String status,boolean expired,String approvedBy,OffsetDateTime approvedAt,OffsetDateTime cancelledAt) {}
    public record TicketResponse(Long id,String externalTicketId,Long taxpayerId,String externalCitizenId,String category,String description,TicketPriority priority,TicketCaseStatus status,String assignedTo,OffsetDateTime createdAt,OffsetDateTime updatedAt,OffsetDateTime completedAt) {}
    public record SocialBenefitResponse(Long id,String externalBenefitId,Long taxpayerId,String externalCitizenId,String benefitType,SocialBenefitStatus status,BigDecimal discountPercentage,LocalDate validFrom,LocalDate validUntil,UUID sourceEventId,OffsetDateTime updatedAt) {}
    public record ExternalObligationResponse(Long id,String sourceModule,ExternalObligationType externalType,String externalReferenceId,UUID sourceEventId,TaxpayerType externalTaxpayerType,String externalTaxpayerId,Long taxpayerId,Long taxConceptId,BigDecimal amount,LocalDate dueDate,ExternalObligationStatus status,String errorMessage,int retryCount,OffsetDateTime receivedAt,OffsetDateTime processedAt) {}
    public record AuditEntryResponse(Long id,String entityType,String entityId,String action,String userId,String userRole,String previousData,String newData,String correlationId,OffsetDateTime occurredAt) {}
    public record IntegrationEventResponse(Long id,UUID eventId,String eventType,String sourceModule,String targetModule,EventDirection direction,IntegrationEventStatus status,String payload,int retryCount,String errorMessage,OffsetDateTime occurredAt,OffsetDateTime receivedAt,OffsetDateTime processedAt,OffsetDateTime lastRetryAt) {}
    public record ElectronicPaymentResponse(Long id,Long paymentId,Long taxpayerId,Long debtId,BigDecimal amount,ElectronicPaymentStatus status,String gatewayReference,OffsetDateTime createdAt) {}
    public record OutboxEventResponse(UUID id,String eventType,String targetModule,String aggregateType,String aggregateId,String payload,OutboxStatus status,int retryCount,OffsetDateTime createdAt,OffsetDateTime publishedAt,OffsetDateTime lastAttemptAt,String errorMessage) {}

    public record CreateTaxpayerRequest(@NotNull TaxpayerType taxpayerType, @NotBlank String externalId,
        String dni, String cuit, @NotBlank String displayName) {}
    public record CreateTaxConceptRequest(@NotBlank String code, @NotBlank String name, String description,
        @NotNull TaxConceptType type, @NotBlank String originModule) {}
    public record UpdateTaxConceptRequest(@NotBlank String name, String description, @NotNull Boolean active) {}
    public record CreateTaxConfigurationRequest(@NotNull Long taxConceptId, @NotNull CalculationType calculationType,
        @PositiveOrZero BigDecimal rate, @PositiveOrZero BigDecimal fixedAmount,
        @PositiveOrZero BigDecimal minimumAmount, @PositiveOrZero BigDecimal maximumAmount,
        boolean partialPaymentAllowed, boolean paymentPlanAllowed, @NotNull LocalDate validFrom, LocalDate validUntil) {}
    public record UpdateTaxConfigurationRequest(CalculationType calculationType,BigDecimal rate,BigDecimal fixedAmount,
        BigDecimal minimumAmount,BigDecimal maximumAmount,Boolean partialPaymentAllowed,Boolean paymentPlanAllowed,
        LocalDate validFrom,LocalDate validUntil) {}
    public record ApprovalRequest(String observation) {}
    public record RejectionRequest(@NotBlank String reason) {}
    public record DeactivationRequest(@NotBlank String reason) {}
    public record LiquidationRequest(@NotNull Long taxpayerId, @NotNull Long taxConceptId,
        @NotBlank @Pattern(regexp="\\d{4}-\\d{2}") String period, @NotNull @PositiveOrZero BigDecimal taxableBase,
        @NotNull LocalDate dueDate) {}
    public record LiquidationComponentResponse(Long id, LiquidationComponentType type, String sourceType,
        String sourceId, String description, BigDecimal amount) {}
    public record LiquidationPreview(Long taxConfigurationId, int configurationVersion, BigDecimal baseAmount,
        BigDecimal discountAmount, BigDecimal exemptionAmount, BigDecimal surchargeAmount,
        BigDecimal interestAmount, BigDecimal finalAmount, List<LiquidationComponentResponse> components) {}
    public record LiquidationResponse(Long id, Long taxpayerId, Long taxConceptId, Long taxConfigurationId,
        int configurationVersion, String period, BigDecimal taxableBase, BigDecimal baseAmount,
        BigDecimal discountAmount, BigDecimal exemptionAmount, BigDecimal surchargeAmount,
        BigDecimal interestAmount, BigDecimal finalAmount, LocalDate dueDate, String status,
        String createdBy, OffsetDateTime issuedAt, List<LiquidationComponentResponse> components) {}
    public record RegisterPaymentRequest(@NotNull Long taxpayerId, Long billId, @NotNull PaymentMethod paymentMethod,
        @NotNull @Positive BigDecimal amount, List<AllocationRequest> allocations) {
        public RegisterPaymentRequest(Long taxpayerId, PaymentMethod method, BigDecimal amount, List<AllocationRequest> allocations) { this(taxpayerId,null,method,amount,allocations); }
    }
    public record AllocationRequest(Long debtId, Long installmentId, @NotNull @Positive BigDecimal amount) {
        public AllocationRequest(Long debtId, BigDecimal amount) { this(debtId, null, amount); }
    }
    public record CreateBillRequest(@NotNull Long taxpayerId, @NotEmpty List<Long> debtIds, @NotNull LocalDate dueDate) {}
    public record BillDetail(Bill bill,List<BillDebt> debts) {}
    public record ReceiptResponse(String receiptNumber,Long paymentId,Long taxpayerId,BigDecimal amount,OffsetDateTime paidAt,String status) {}
    public record ElectronicPaymentRequest(@NotNull Long debtId,@NotNull PaymentMethod paymentMethod,@NotNull @Positive BigDecimal amount) {}
    public record ElectronicPaymentPreview(Long debtId,BigDecimal requestedAmount,BigDecimal payableAmount,boolean approved,String message) {}
    public record ApplyCreditBalanceRequest(@NotNull Long debtId,@NotNull @Positive BigDecimal amount) {}
    public record CreateReversalRequest(@NotBlank String reason) {}
    public record GrantPlanRequest(@NotNull Long debtId, @Min(1) @Max(60) int installments,
        @NotNull @PositiveOrZero BigDecimal annualInterestRate) {}
    public record CreatePaymentPlanConfigurationRequest(@Min(1) int minimumInstallments,@Min(1) int maximumInstallments,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal minimumDownPaymentPercentage,
        @NotNull @PositiveOrZero BigDecimal interestRate,@PositiveOrZero int graceDays,@PositiveOrZero int maxOverdueInstallments,
        boolean partialInstallmentPaymentAllowed,boolean refinancingAllowed,@PositiveOrZero int maxRefinancingCount,
        @NotNull LocalDate validFrom,LocalDate validUntil,boolean active) {}
    public record UpdatePaymentPlanConfigurationRequest(Integer minimumInstallments,Integer maximumInstallments,
        BigDecimal minimumDownPaymentPercentage,BigDecimal interestRate,Integer graceDays,Integer maxOverdueInstallments,
        Boolean partialInstallmentPaymentAllowed,Boolean refinancingAllowed,Integer maxRefinancingCount,
        LocalDate validFrom,LocalDate validUntil,Boolean active) {}
    public record PaymentPlanSimulationRequest(@NotNull Long taxpayerId,@NotEmpty List<Long> debtIds,@Min(1) @Max(60) int installments) {}
    public record PaymentPlanSimulationResponse(Long configurationId,int configurationVersion,BigDecimal principal,
        BigDecimal downPayment,BigDecimal financedPrincipal,BigDecimal interest,BigDecimal total,int installments,
        BigDecimal regularInstallmentAmount,boolean exceptional) {}
    public record CreatePaymentPlanRequest(@NotNull Long taxpayerId,@NotEmpty List<Long> debtIds,@Min(1) @Max(60) int installments) {}
    public record GrantPaymentPlanRequest(@PositiveOrZero BigDecimal downPaymentAmount) {}
    public record SubmitPlanExceptionRequest(@NotBlank String reason) {}
    public record ApprovePlanExceptionRequest(String observation) {}
    public record CreatePlanExpirationRequest(@NotBlank String reason) {}
    public record RefinancingSimulationRequest(@Min(1) @Max(60) int installments) {}
    public record RefinancingSimulationResponse(Long originalPlanId,BigDecimal outstandingPrincipal,BigDecimal interest,
        BigDecimal total,int installments,boolean exceptional) {}
    public record CreateRefinancingRequest(@Min(1) @Max(60) int installments) {}
    public record CreateAdjustmentRequest(@NotNull Long debtId,@NotNull AdjustmentType type,@NotNull @DecimalMin(value="0.01",inclusive=false) BigDecimal amount,@NotBlank String reason) {}
    public record LiquidationRunItemRequest(@NotNull Long taxpayerId,@NotNull @PositiveOrZero BigDecimal taxableBase) {}
    public record CreateLiquidationRunRequest(@NotNull Long taxConceptId,@NotBlank @Pattern(regexp="\\d{4}-\\d{2}") String period,
        @NotNull LocalDate dueDate,@NotEmpty List<@Valid LiquidationRunItemRequest> items) {}
    public record LiquidationRunDetail(LiquidationRun run,List<LiquidationRunItem> items) {}
    public record CreateExemptionRequest(@NotNull Long taxpayerId, @NotNull Long taxConceptId, @NotBlank String reason,
        @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal percentage,
        @NotNull LocalDate validFrom, LocalDate validUntil) {}
    public record RequestDocumentationRequest(@NotBlank String message) {}
    public record SubmitDocumentationRequest(@NotBlank String externalDocumentId,@NotBlank String documentType,String fileName) {}
    public record SubmitExemptionResolutionRequest(String observation) {}
    public record ApproveExemptionRequest(@DecimalMin("0.01") @DecimalMax("100.00") BigDecimal percentage,LocalDate validFrom,LocalDate validUntil,String observation) {}
    public record EventEnvelope<T>(@NotNull UUID eventId, @NotBlank String eventType,
        @NotNull OffsetDateTime occurredAt, @NotBlank String sourceModule, @NotNull T data) {}
    public record InfractionData(@NotBlank String externalReferenceId, @NotNull TaxpayerType taxpayerType,
        @NotBlank String taxpayerExternalId, @NotNull @Positive BigDecimal amount, @NotNull LocalDate dueDate) {}
    public record ExternalObligationData(@NotBlank String externalReferenceId,@NotNull TaxpayerType taxpayerType,
        @NotBlank String taxpayerExternalId,@NotNull @Positive BigDecimal amount,@NotNull LocalDate dueDate) {}
    public record TaxpayerEventData(@NotNull TaxpayerType taxpayerType,@NotBlank String externalId,String dni,String cuit,
        @NotBlank String displayName,@NotNull TaxpayerStatus status) {}
    public record TicketEventData(@NotBlank String externalTicketId,String externalCitizenId,@NotBlank String category,
        @NotBlank String description,@NotNull TicketPriority priority,@NotNull TicketCaseStatus status,OffsetDateTime createdAt) {}
    public record SocialBenefitEventData(@NotBlank String externalBenefitId,@NotBlank String externalCitizenId,
        @NotBlank String benefitType,@NotNull SocialBenefitStatus status,@DecimalMin("0.00") @DecimalMax("100.00") BigDecimal discountPercentage,
        @NotNull LocalDate validFrom,LocalDate validUntil,List<String> taxConceptCodes) {}
    public record CreateTicketUpdateRequest(@NotBlank String message) {}
    public record TicketInformationRequest(@NotBlank String message) {}
    public record CompleteTicketRequest(@NotBlank String resolution) {}
    public record CollectionIndicatorResponse(long paymentCount,BigDecimal confirmedAmount,BigDecimal allocatedAmount,BigDecimal unallocatedAmount) {}
    public record DebtIndicatorResponse(long debtCount,long paidCount,long openCount,BigDecimal originalAmount,BigDecimal outstandingAmount) {}
    public record DelinquencyIndicatorResponse(long overdueDebtCount,BigDecimal overdueAmount,BigDecimal overduePercentage) {}
    public record IndicatorSummaryResponse(CollectionIndicatorResponse collection,DebtIndicatorResponse debt,DelinquencyIndicatorResponse delinquency) {}
    public record TaxpayerSummaryResponse(Long taxpayerId,BigDecimal outstandingDebt,BigDecimal paidAmount,long openDebts,long activePlans,long activeBenefits) {}
    public record DebtSummaryResponse(Long taxpayerId,long totalDebts,long openDebts,long paidDebts,long overdueDebts,BigDecimal outstandingAmount) {}
    public record ReprocessEventRequest(String reason) {}
    public record ErrorResponse(OffsetDateTime timestamp, int status, String code, String message, String path, String traceId) {}
}
