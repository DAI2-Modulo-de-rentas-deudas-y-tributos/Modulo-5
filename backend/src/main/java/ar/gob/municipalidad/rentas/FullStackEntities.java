package ar.gob.municipalidad.rentas;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

enum DemoRole { RENTAS, SUPERVISOR, CASHIER, AUDITOR, TAXPAYER }
enum ReconciliationStatus { CONCILIATED, OBSERVED, NOT_FOUND }

@Entity @Table(name="demo_user")
class DemoUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false,unique=true) public String username;
    @Column(name="password_hash",nullable=false) public String passwordHash;
    @Column(name="display_name",nullable=false) public String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public DemoRole role;
    @Column(name="taxpayer_id") public Long taxpayerId;
    @Column(nullable=false) public boolean active;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="updated_at",nullable=false) public OffsetDateTime updatedAt;
    protected DemoUser() {}
}

@Entity @Table(name="late_charge_rule")
class LateChargeRule {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false,unique=true) public String code;
    @Column(name="surcharge_rate",nullable=false,precision=9,scale=4) public BigDecimal surchargeRate;
    @Column(name="daily_interest_rate",nullable=false,precision=9,scale=4) public BigDecimal dailyInterestRate;
    @Column(nullable=false) public boolean active;
    @Column(name="valid_from",nullable=false) public LocalDate validFrom;
    @Column(name="valid_until") public LocalDate validUntil;
    protected LateChargeRule() {}
}

@Entity @Table(name="late_charge_application",uniqueConstraints=@UniqueConstraint(columnNames={"debt_id","rule_id","calculation_date"}))
class LateChargeApplication {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="debt_id",nullable=false) public Long debtId;
    @Column(name="rule_id",nullable=false) public Long ruleId;
    @Column(name="calculation_date",nullable=false) public LocalDate calculationDate;
    @Column(name="days_overdue",nullable=false) public int daysOverdue;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal principal;
    @Column(name="surcharge_rate",nullable=false,precision=9,scale=4) public BigDecimal surchargeRate;
    @Column(name="surcharge_amount",nullable=false,precision=19,scale=2) public BigDecimal surchargeAmount;
    @Column(name="interest_rate",nullable=false,precision=9,scale=4) public BigDecimal interestRate;
    @Column(name="interest_amount",nullable=false,precision=19,scale=2) public BigDecimal interestAmount;
    @Column(name="previous_adjustments",nullable=false,precision=19,scale=2) public BigDecimal previousAdjustments;
    @Column(name="total_adjustment",nullable=false,precision=19,scale=2) public BigDecimal totalAdjustment;
    @Column(name="updated_total",nullable=false,precision=19,scale=2) public BigDecimal updatedTotal;
    @Column(name="applied_by",nullable=false) public String appliedBy;
    @Column(name="applied_at",nullable=false) public OffsetDateTime appliedAt;
    protected LateChargeApplication() {}
}

@Entity @Table(name="due_date_processing")
class DueDateProcessing {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="processing_date",nullable=false,unique=true) public LocalDate processingDate;
    @Column(name="processed_at",nullable=false) public OffsetDateTime processedAt;
    @Column(name="debts_scanned",nullable=false) public int debtsScanned;
    @Column(name="debts_overdue",nullable=false) public int debtsOverdue;
    @Column(name="adjustments_generated",nullable=false) public int adjustmentsGenerated;
    @Column(name="installments_overdue",nullable=false) public int installmentsOverdue;
    @Column(name="plans_defaulted",nullable=false) public int plansDefaulted;
    @Column(name="skipped_already_processed",nullable=false) public int skippedAlreadyProcessed;
    @Column(nullable=false) public int errors;
    @Column(name="processed_by",nullable=false) public String processedBy;
    protected DueDateProcessing() {}
}

@Entity @Table(name="electronic_reconciliation_batch")
class ElectronicReconciliationBatch {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="external_batch_reference",nullable=false,unique=true) public String externalBatchReference;
    @Column(name="total_items",nullable=false) public int totalItems;
    @Column(name="reconciled_items",nullable=false) public int reconciledItems;
    @Column(name="observed_items",nullable=false) public int observedItems;
    @Column(name="not_found_items",nullable=false) public int notFoundItems;
    @Column(name="imported_by",nullable=false) public String importedBy;
    @Column(name="imported_at",nullable=false) public OffsetDateTime importedAt;
    protected ElectronicReconciliationBatch() {}
}

@Entity @Table(name="electronic_reconciliation_item")
class ElectronicReconciliationItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="batch_id",nullable=false) public Long batchId;
    @Column(name="external_reference",nullable=false,unique=true) public String externalReference;
    @Column(name="taxpayer_document",nullable=false) public String taxpayerDocument;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    @Column(name="paid_at",nullable=false) public OffsetDateTime paidAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public ReconciliationStatus status;
    @Column(name="matched_payment_id") public Long matchedPaymentId;
    @Column(name="resolution_reason") public String resolutionReason;
    @Column(name="resolved_by") public String resolvedBy;
    @Column(name="resolved_at") public OffsetDateTime resolvedAt;
    protected ElectronicReconciliationItem() {}
}
