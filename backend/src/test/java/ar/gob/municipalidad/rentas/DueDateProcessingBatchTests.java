package ar.gob.municipalidad.rentas;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DueDateProcessingBatchTests {
    @Test void enqueueResetsOnlyFailedRuns() {
        DueDateProcessingRepository runs=mock(DueDateProcessingRepository.class);
        FiscalProcessingDatePolicy dates=mock(FiscalProcessingDatePolicy.class);
        CurrentIdentity identity=mock(CurrentIdentity.class);
        LocalDate date=LocalDate.of(2026,11,5);
        DueDateProcessing run=new DueDateProcessing();run.id=9L;run.processingDate=date;run.status=DueDateRunStatus.FAILED;
        run.attemptCount=3;run.lastErrorCode="DUE_DATE_PROCESSING_FAILED";run.nextAttemptAt=OffsetDateTime.now();run.leaseExpiresAt=OffsetDateTime.now();
        when(identity.get()).thenReturn(new AuthenticatedIdentity("rentas",null));
        when(runs.findByProcessingDate(date)).thenReturn(Optional.of(run));

        var response=new DueDateService(runs,mock(DueDateProcessor.class),dates,identity).enqueue(date);

        assertThat(response.status()).isEqualTo(DueDateRunStatus.PENDING);
        assertThat(run.attemptCount).isZero();
        assertThat(run.lastErrorCode).isNull();
        assertThat(run.nextAttemptAt).isNull();
        assertThat(run.leaseExpiresAt).isNull();
        verify(runs).save(run);
    }

    @Test void exhaustedClaimIsMarkedFailedAndMissingClaimIsIgnored() {
        DueDateProcessingRepository runs=mock(DueDateProcessingRepository.class);
        DueDateProcessing run=new DueDateProcessing();run.id=9L;run.status=DueDateRunStatus.PROCESSING;run.attemptCount=3;
        run.leaseExpiresAt=OffsetDateTime.now();
        when(runs.lockNextClaimable(any())).thenReturn(Optional.empty(),Optional.of(run));
        DueDateService service=new DueDateService(runs,mock(DueDateProcessor.class),mock(FiscalProcessingDatePolicy.class),mock(CurrentIdentity.class));

        assertThat(service.claimNext()).isNull();
        assertThat(service.claimNext()).isNull();

        assertThat(run.status).isEqualTo(DueDateRunStatus.FAILED);
        assertThat(run.lastErrorCode).isEqualTo("DUE_DATE_PROCESSING_FAILED");
        assertThat(run.leaseExpiresAt).isNull();
        verify(runs).save(run);
    }

    @Test void failureIsFencedByAttemptAndThirdFailureIsTerminal() {
        DueDateProcessingRepository runs=mock(DueDateProcessingRepository.class);
        DueDateProcessing run=new DueDateProcessing();run.id=9L;run.status=DueDateRunStatus.PROCESSING;run.attemptCount=2;
        when(runs.findByIdForUpdate(9L)).thenReturn(Optional.of(run));
        DueDateService service=new DueDateService(runs,mock(DueDateProcessor.class),mock(FiscalProcessingDatePolicy.class),mock(CurrentIdentity.class));

        service.recordFailure(9L,1,"STALE");
        assertThat(run.status).isEqualTo(DueDateRunStatus.PROCESSING);
        verify(runs,never()).save(run);

        service.recordFailure(9L,2,"RETRYABLE");
        assertThat(run.status).isEqualTo(DueDateRunStatus.PENDING);
        assertThat(run.lastErrorCode).isEqualTo("RETRYABLE");
        assertThat(run.nextAttemptAt).isAfter(OffsetDateTime.now());
        assertThat(run.leaseExpiresAt).isNull();

        run.status=DueDateRunStatus.PROCESSING;run.attemptCount=3;
        service.recordFailure(9L,3,"TERMINAL");
        assertThat(run.status).isEqualTo(DueDateRunStatus.FAILED);
        assertThat(run.lastErrorCode).isEqualTo("TERMINAL");
        assertThat(run.nextAttemptAt).isNull();
        verify(runs,times(2)).save(run);
    }

    @Test void workerLoopsBatchesAndPersistsBusinessFailureCode() {
        DueDateService dueDates=mock(DueDateService.class);
        DueDateProcessor processor=mock(DueDateProcessor.class);
        FiscalProcessingController.DueDateRunClaim claim=new FiscalProcessingController.DueDateRunClaim(9L,LocalDate.now(),2);
        when(dueDates.claimNext()).thenReturn(claim,null,claim);
        when(processor.processNextBatch(9L,2)).thenReturn(false,true).thenThrow(new BusinessException("INVALID_RUN","invalid",422));
        DueDateProcessingWorker worker=new DueDateProcessingWorker(dueDates,processor);

        worker.processNext();
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
        worker.processNext();
        worker.processNext();

        verify(processor,times(3)).processNextBatch(9L,2);
        verify(dueDates).recordFailure(9L,2,"INVALID_RUN");
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void completedStageFinalizesWithoutReadingEconomicRows() {
        DueDateProcessingRepository runs=mock(DueDateProcessingRepository.class);
        DebtRepository debts=mock(DebtRepository.class);
        InstallmentRepository installments=mock(InstallmentRepository.class);
        PaymentPlanRepository plans=mock(PaymentPlanRepository.class);
        PaymentPlanConfigurationRepository configurations=mock(PaymentPlanConfigurationRepository.class);
        PlanExpirationRepository expirations=mock(PlanExpirationRepository.class);
        LateChargeService lateCharges=mock(LateChargeService.class);
        ConfirmedIntegrationOutbox integrationOutbox=mock(ConfirmedIntegrationOutbox.class);
        CurrentIdentity identity=mock(CurrentIdentity.class);
        AuditService audit=mock(AuditService.class);
        EntityManager entityManager=mock(EntityManager.class);
        DueDateProcessing run=new DueDateProcessing();run.id=9L;run.status=DueDateRunStatus.PROCESSING;run.attemptCount=2;
        run.processingStage=DueDateProcessingStage.COMPLETED;run.leaseExpiresAt=OffsetDateTime.now();
        when(runs.findByIdForUpdate(9L)).thenReturn(Optional.of(run));
        when(identity.get()).thenReturn(new AuthenticatedIdentity("system",null));
        DueDateProcessor processor=new DueDateProcessor(runs,debts,installments,plans,configurations,expirations,lateCharges,
            integrationOutbox,identity,audit,mock(FiscalProcessingDatePolicy.class));
        ReflectionTestUtils.setField(processor,"entityManager",entityManager);

        assertThat(processor.processNextBatch(9L,2)).isTrue();

        assertThat(run.status).isEqualTo(DueDateRunStatus.COMPLETED);
        assertThat(run.completedAt).isNotNull();
        assertThat(run.processedAt).isEqualTo(run.completedAt);
        assertThat(run.leaseExpiresAt).isNull();
        verify(audit).record("DueDateProcessing",9L,"DUE_DATES_PROCESSED",run);
        verifyNoInteractions(debts,installments,plans,configurations,expirations,lateCharges,integrationOutbox);
    }

    @Test void staleBatchClaimDoesNotAdvanceTheRun() {
        DueDateProcessingRepository runs=mock(DueDateProcessingRepository.class);
        DueDateProcessing run=new DueDateProcessing();run.id=9L;run.status=DueDateRunStatus.PROCESSING;run.attemptCount=3;
        run.processingStage=DueDateProcessingStage.DEBTS;
        when(runs.findByIdForUpdate(9L)).thenReturn(Optional.of(run));
        DueDateProcessor processor=new DueDateProcessor(runs,mock(DebtRepository.class),mock(InstallmentRepository.class),
            mock(PaymentPlanRepository.class),mock(PaymentPlanConfigurationRepository.class),mock(PlanExpirationRepository.class),
            mock(LateChargeService.class),mock(ConfirmedIntegrationOutbox.class),mock(CurrentIdentity.class),mock(AuditService.class),
            mock(FiscalProcessingDatePolicy.class));

        assertThat(processor.processNextBatch(9L,2)).isTrue();

        assertThat(run.processingStage).isEqualTo(DueDateProcessingStage.DEBTS);
        assertThat(run.leaseExpiresAt).isNull();
    }
}
