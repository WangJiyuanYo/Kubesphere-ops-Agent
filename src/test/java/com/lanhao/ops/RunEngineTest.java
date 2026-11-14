package com.lanhao.ops;

import com.lanhao.ops.diagnosis.*;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.persistence.RunStore;
import com.lanhao.ops.port.*;
import com.lanhao.ops.workflow.RunEngine;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class RunEngineTest {
    private RunStore store;
    private DeploymentPort port;
    private Run durable;
    private RunEngine engine;
    private RunStore.Claim claim;

    @BeforeEach void setup() throws Exception {
        store = mock(RunStore.class); port = mock(DeploymentPort.class);
        durable = new Run(TestFixtures.request("test-run"), Instant.now());
        claim = new RunStore.Claim(durable.id, "worker");
        when(store.find(claim.id())).thenAnswer(call -> Optional.of(TestFixtures.json().readValue(
                TestFixtures.json().writeValueAsString(durable), Run.class)));
        doAnswer(call -> { durable = call.getArgument(1); return null; }).when(store).save(eq(claim), any(), any());
        when(port.observe(any())).thenReturn(TestFixtures.failed("registry 503 i/o timeout"));
        when(port.retry(any())).thenAnswer(call -> new Recovery(((Run) call.getArgument(0)).actionKey, "ACCEPTED"));
        when(port.lookupRecovery(any())).thenAnswer(call -> {
            Run input = call.getArgument(0);
            return input == null || input.recovery == null ? Optional.empty()
                    : Optional.of(new Recovery(input.recovery.id(), "SUCCEEDED"));
        });
        engine = new RunEngine(store, port, graph(true), TestFixtures.config(true), new RecoveryPolicy());
    }
    private DiagnosisGraph graph(boolean auto) throws Exception {
        return new DiagnosisGraph(new Redactor(), new RecoveryPolicy(), new RunbookNarrator(),
                TestFixtures.json(), TestFixtures.config(auto));
    }
    private void tick() { engine.step(claim); }
    @Test void spontaneousConvergenceAfterIntentDoesNotProveThatTheSuggestedActionWasExecuted() {
        tick();tick();tick();assertThat(durable.state).isEqualTo(State.EXECUTING);
        when(port.observe(any())).thenReturn(new Evidence(Instant.now(),true,false,"ready","Ready",List.of()));
        when(port.verify(any())).thenReturn(true);
        tick();tick();tick();tick();
        assertThat(durable.state).isEqualTo(State.SUCCEEDED);assertThat(durable.attempts).isEqualTo(1);
        assertThat(durable.recovery).isNull();assertThat(durable.verifiedRecovery()).isFalse();
        verify(port,never()).retry(any());
    }
    @Test void persistsIntentBeforeSideEffectAndChecksStableReadiness() {
        tick(); assertThat(durable.state).isEqualTo(State.DIAGNOSING);
        tick(); assertThat(durable.state).isEqualTo(State.RETRY_WAIT);
        tick(); assertThat(durable.state).isEqualTo(State.EXECUTING);
        verify(port, never()).retry(any());
        String key = durable.actionKey;
        tick(); assertThat(durable.state).isEqualTo(State.VERIFYING);
        assertThat(durable.recovery.id()).isEqualTo(key);
        when(port.observe(any())).thenReturn(new Evidence(Instant.now(), true, false, "ready", "Ready", List.of()));
        when(port.verify(any())).thenReturn(true);
        tick(); tick(); assertThat(durable.state).isEqualTo(State.VERIFYING);
        tick(); assertThat(durable.state).isEqualTo(State.SUCCEEDED);
    }
    @Test void uncertainExternalResponseReusesSameKeyAndBudget() {
        tick(); tick(); tick();
        String key = durable.actionKey;
        doThrow(new IllegalStateException("response lost"))
                .doReturn(new Recovery("recovery-1", "ACCEPTED")).when(port).retry(any());
        tick(); assertThat(durable.state).isEqualTo(State.EXECUTING);
        tick();
        ArgumentCaptor<Run> calls = ArgumentCaptor.forClass(Run.class);
        verify(port, times(2)).retry(calls.capture());
        assertThat(calls.getAllValues()).allSatisfy(run -> {
            assertThat(run.actionKey).isEqualTo(key); assertThat(run.attempts).isEqualTo(1);
        });
    }
    @Test void newAuthenticationEvidenceStopsAnAlreadyApprovedPlan() {
        tick(); tick(); tick();
        when(port.observe(any())).thenReturn(TestFixtures.failed("unauthorized 503"));
        tick(); assertThat(durable.state).isEqualTo(State.DIAGNOSING);
        tick(); assertThat(durable.state).isEqualTo(State.MANUAL_REQUIRED);
        verify(port, never()).retry(any());
    }
    @Test void findsAcceptedActionBeforeObservingItsOwnGenerationChange() {
        tick(); tick(); tick();
        when(port.lookupRecovery(any())).thenReturn(Optional.of(new Recovery("recovered-action", "DISPATCHED")));
        doThrow(new DeploymentPort.Superseded()).when(port).observe(any());
        tick();
        assertThat(durable.state).isEqualTo(State.VERIFYING);
        assertThat(durable.recovery.id()).isEqualTo("recovered-action");
        assertThat(durable.attempts).isEqualTo(1);
        verify(port, never()).retry(any());
    }
    @Test void retryBudgetDoesNotResetDuringReplanning() {
        durable.state = State.DIAGNOSING; durable.attempts = 2;
        durable.evidence = TestFixtures.failed("503 i/o timeout");
        tick(); assertThat(durable.state).isEqualTo(State.MANUAL_REQUIRED);
        assertThat(durable.attempts).isEqualTo(2);
    }
    @Test void replacedResourceTerminatesOldRun() {
        when(port.observe(any())).thenThrow(new DeploymentPort.Superseded());
        tick(); assertThat(durable.state).isEqualTo(State.FAILED);
        verify(port, never()).retry(any());
    }
    @Test void healthyPodWithoutApplicationHealthDoesNotSucceed() {
        when(port.observe(any())).thenReturn(new Evidence(Instant.now(), true, false, "ready", "Ready", List.of()));
        when(port.verify(any())).thenReturn(false);
        tick(); tick(); tick(); tick();
        assertThat(durable.state).isEqualTo(State.VERIFYING);
        assertThat(durable.stableCount).isZero();
    }
    @Test void approvalModeDoesNotDispatchUntilApproved() throws Exception {
        engine = new RunEngine(store, port, graph(false), TestFixtures.config(false), new RecoveryPolicy());
        tick(); tick(); assertThat(durable.state).isEqualTo(State.WAITING_APPROVAL);
        tick(); verify(port, never()).retry(any());
    }
    @Test void leaseLossPreventsExternalAction() {
        durable.state = State.EXECUTING; durable.actionKey = "persisted-key";
        doThrow(new IllegalStateException("lost lease")).when(store).ensureLease(claim);
        tick(); verify(port, never()).retry(any());
    }
    @Test void stalledDeploymentStartsDiagnosis() {
        durable.progressFingerprint = "stuck";
        durable.lastProgressAt = Instant.now().minusSeconds(601);
        when(port.observe(any())).thenReturn(new Evidence(Instant.now(), false, false, "stuck", "Pending", List.of()));
        tick(); assertThat(durable.state).isEqualTo(State.DIAGNOSING);
    }
    @Test void expiredApprovalDoesNotHoldAnActiveRunForever() {
        durable.state = State.WAITING_APPROVAL;
        durable.createdAt = Instant.now().minusSeconds(3601);
        tick();
        assertThat(durable.state).isEqualTo(State.MANUAL_REQUIRED);
        verify(port, never()).retry(any());
    }
    @Test void verificationPersistsLatestRecoveryStatus() {
        tick(); tick(); tick(); tick();
        when(port.lookupRecovery(any())).thenReturn(Optional.of(new Recovery(durable.recovery.id(), "SUCCEEDED")));
        when(port.observe(any())).thenReturn(new Evidence(Instant.now(), true, false, "ready", "Ready", List.of()));
        when(port.verify(any())).thenReturn(true);
        tick(); tick(); tick();
        assertThat(durable.state).isEqualTo(State.SUCCEEDED);
        assertThat(durable.recovery.state()).isEqualTo("SUCCEEDED");
    }
    @Test void failedRecoveryDoesNotWaitUntilVerificationTimeout() {
        tick(); tick(); tick(); tick();
        when(port.lookupRecovery(any())).thenReturn(Optional.of(new Recovery(durable.recovery.id(), "FAILED")));
        tick();
        assertThat(durable.state).isEqualTo(State.DIAGNOSING);
        assertThat(durable.recovery.state()).isEqualTo("FAILED");
    }
    @Test void replacedRecoveryStopsOldRunImmediately() {
        tick(); tick(); tick(); tick();
        when(port.lookupRecovery(any())).thenReturn(Optional.of(new Recovery("new-recovery", "DISPATCHED")));
        tick();
        assertThat(durable.state).isEqualTo(State.FAILED);
    }
    @Test void lateHealthyResponseCannotPassAnExpiredVerificationWindow() {
        durable.state = State.VERIFYING;
        durable.verificationStartedAt = Instant.now().minusSeconds(301);
        durable.stableCount = 2;
        durable.evidence = TestFixtures.failed("503 timeout");
        when(port.observe(any())).thenReturn(new Evidence(Instant.now(), true, false, "ready", "Ready", List.of()));
        when(port.verify(any())).thenReturn(true);
        tick();
        assertThat(durable.state).isEqualTo(State.DIAGNOSING);
        assertThat(durable.stableCount).isZero();
    }
    @Test void deploymentEventWaitsForPlatformTargetsThenStartsMonitoring() {
        durable.state = State.DISCOVERING; durable.targets = List.of();
        when(port.discover(any())).thenReturn(List.of(), TestFixtures.request("targets").targets());
        tick(); assertThat(durable.state).isEqualTo(State.DISCOVERING);
        tick(); assertThat(durable.state).isEqualTo(State.MONITORING);
        assertThat(durable.targets).hasSize(1);
    }
    @Test void discoveryCannotWaitForeverForAnUncreatedResource() {
        durable.state = State.DISCOVERING; durable.targets = List.of();
        durable.createdAt = Instant.now().minusSeconds(601);
        when(port.discover(any())).thenReturn(List.of());
        tick(); assertThat(durable.state).isEqualTo(State.MANUAL_REQUIRED);
        verify(port, never()).retry(any());
    }
    @Test void unsupportedRunbookCannotExecuteEvenWithTransientEvidence() {
        tick(); tick(); tick();
        durable.plan = new Plan("unsupported", Action.RETRY_TRANSIENT, null, true, Instant.now(), "arbitrary-shell", 1);
        tick(); tick(); tick();
        assertThat(durable.state).isEqualTo(State.MANUAL_REQUIRED);
        verify(port, never()).retry(any());
    }
    @Test void stepDeadlineRejectsHealthyResponseEvenBeforeVerificationWindowExpires() {
        durable.state = State.VERIFYING; durable.stableCount = 2;
        durable.verificationStartedAt = Instant.now();
        when(port.observe(any())).thenReturn(new Evidence(Instant.now(), true, false, "ready", "Ready", List.of()));
        var control = new com.lanhao.ops.workflow.StepControl(Duration.ofSeconds(10));
        when(port.verify(any())).thenAnswer(c -> { control.abort("迟到的健康响应"); return true; });
        try {
            control.bind(); tick();
        } finally { control.close(); }
        assertThat(durable.state).isEqualTo(State.VERIFYING);
        assertThat(durable.stableCount).isZero();
        assertThat(durable.events).noneMatch(event -> event.state() == State.SUCCEEDED);
    }
}
