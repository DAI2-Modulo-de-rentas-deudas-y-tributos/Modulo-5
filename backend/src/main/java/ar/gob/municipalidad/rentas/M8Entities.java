package ar.gob.municipalidad.rentas;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

enum SocialBenefitRuleStatus { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED }

@Entity @Table(name="social_benefit_rule",uniqueConstraints=@UniqueConstraint(columnNames={"program_id","tax_concept_id","version"}))
class SocialBenefitRule {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="program_id",nullable=false) public UUID programId;
    @Column(name="tax_concept_id",nullable=false) public Long taxConceptId;
    @Column(nullable=false) public int version;
    @Column(nullable=false,precision=5,scale=2) public BigDecimal percentage;
    @Column(name="valid_from",nullable=false) public LocalDate validFrom;
    @Column(name="valid_until") public LocalDate validUntil;
    @Column(nullable=false) public boolean enabled;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public SocialBenefitRuleStatus status;
    @Column(name="proposed_by",nullable=false) public String proposedBy;
    @Column(name="submitted_by") public String submittedBy;
    @Column(name="approved_by") public String approvedBy;
    @Column(name="approved_actor_id") public UUID approvedActorId;
    @Column(name="created_at",nullable=false) public OffsetDateTime createdAt;
    @Column(name="approved_at") public OffsetDateTime approvedAt;
    @Column(name="disabled_at") public OffsetDateTime disabledAt;
    protected SocialBenefitRule() {}
}

@Entity @Table(name="social_benefit_event_history")
class SocialBenefitEventHistory {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="source_event_id",nullable=false,unique=true) public UUID sourceEventId;
    @Column(name="external_benefit_id",nullable=false) public String externalBenefitId;
    @Column(name="source_updated_at",nullable=false) public OffsetDateTime sourceUpdatedAt;
    @Column(name="external_status",nullable=false) public String externalStatus;
    @Column(nullable=false) public String outcome;
    @Column(name="payload_hash",nullable=false,length=64) public String payloadHash;
    @Column(name="recorded_at",nullable=false) public OffsetDateTime recordedAt;
    protected SocialBenefitEventHistory() {}
}
