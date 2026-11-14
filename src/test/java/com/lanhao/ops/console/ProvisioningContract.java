package com.lanhao.ops.console;

import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.persistence.*;
import com.lanhao.ops.port.DeploymentPort;
import com.lanhao.ops.workflow.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.lanhao.ops.console.ProvisioningModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SQLite / PostgreSQL 共用的真实持久化与 API 契约；外部平台、集群、模型为显式替身。 */
abstract class ProvisioningContract {
    @Autowired ProvisioningStore watches;
    @Autowired ProvisioningSync sync;
    @Autowired AutonomyPolicies policies;
    @Autowired ProvisioningAutonomy autonomy;
    @Autowired AutonomyGuard guard;
    @Autowired ConsoleSettings settings;
    @Autowired DiscoveryStore discovery;
    @Autowired DiscoveryService investigations;
    @Autowired RunStore runs;
    @Autowired RunEngine engine;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired RunDiagnosisBudget diagnosisBudget;
    @Autowired RecoveryOutcomesController outcomes;
    @Autowired com.lanhao.ops.AgentProperties config;
    @MockitoBean ProvisioningGateway gateway;
    @MockitoBean DiscoveryGateway clusters;
    @MockitoBean ReadOnlyEvidenceCollector collector;
    @MockitoBean com.lanhao.ops.diagnosis.DiagnosisGraph graph;
    @MockitoBean DeploymentPort deployment;
    @MockitoBean io.fabric8.kubernetes.client.KubernetesClient kubernetes;
    @MockitoBean ProvisioningScheduler provisioningScheduler;
    @MockitoBean DiscoveryScheduler discoveryScheduler;
    @MockitoBean RunScheduler runScheduler;
    @MockitoBean DeploymentInbox inbox;
    @MockitoBean com.lanhao.ops.memory.LearningOutbox learning;
    @MockitoBean com.lanhao.ops.memory.MemoryCapture capture;
    UUID connection;
    Target target=new Target(Kind.PlatformRelease,"agent-demo","app","fixture-uid",1);
    DiscoveryGateway.Observation observation;
    AtomicReference<StageEvent> current=new AtomicReference<>();
    @AfterEach void restoreGuard(){engine.autonomy(guard);}
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM agent_provisioning_watch_audit");
        for(String table:List.of("agent_autonomy_slot","agent_autonomy_run","agent_diagnosis_trigger","agent_autonomy_audit","agent_autonomy_policy","agent_provisioning_stage_event","agent_provisioning_watch","agent_provisioning_source","agent_recovery_outcome","agent_run_diagnosis","agent_release_budget","agent_case_recovery","agent_learning_outbox","agent_chat_turn","agent_chat_session","agent_discovered_task","agent_discovery_source","agent_console_connection","agent_command_execution","agent_memory_outbox","agent_notification","agent_audit","agent_deployment_event","agent_run"))jdbc.update("DELETE FROM "+table);
        connection=UUID.randomUUID();settings.saveConnection(connection,new ConsoleSettings.ConnectionInput(0,"契约连接","https://cluster.test","","agent-demo","fixture-readonly-token",false));
        discovery.configure(connection,true,0,List.of("agent-demo"));
        when(gateway.enabled()).thenReturn(true);when(gateway.authorizationHash()).thenReturn("fixture-auth");
        when(gateway.current(any())).thenAnswer(call->current.get());
        observation=new DiscoveryGateway.Observation(target,"app-v1","project-1","test","Failed",1,"NETWORK_TIMEOUT","i/o timeout");
        when(clusters.scan(any(),anyList())).thenReturn(List.of(observation));when(clusters.read(any(),any())).thenReturn(observation);
        when(collector.collect(any(),any())).thenReturn(observation.evidence());
        when(graph.diagnose(any(Evidence.class),anyString())).thenAnswer(call->plan());
        when(graph.diagnose(any(Run.class),any())).thenAnswer(call->plan());
        when(deployment.clusterApiUrl()).thenReturn("https://cluster.test");
        when(deployment.registration(anyString())).thenReturn(new StartRequest("fixture","100",List.of(target),"cycle-1","app"));
        when(deployment.discover(any())).thenReturn(List.of(target));when(deployment.observe(any())).thenReturn(observation.evidence());
        when(deployment.lookupRecovery(any())).thenReturn(Optional.empty());
        investigations.scan(connection,true);
        current.set(event("e1",1,PhaseState.FAILED,true,0));
    }
    Plan plan(){return new Plan(UUID.randomUUID().toString(),Action.RETRY_TRANSIENT,new Diagnosis("网络暂时失败",List.of("核对网络证据"),List.of("平台幂等重试"),"完整验收","CONTRACT_FIXTURE"),true,Instant.now());}
    StageEvent event(String id,long sequence,PhaseState state,boolean mapped,long units) {
        Instant at=Instant.parse("2026-11-13T00:00:00Z").plusSeconds(sequence);
        return new StageEvent(1,id,"platform-fixture","workspace-1","10","100","cycle-1",sequence,"PROJECT_PREPARE",state,at.minusSeconds(1200),at,units,
                state==PhaseState.FAILED?"NETWORK_TIMEOUT":"","平台步骤状态",List.of("ACCEPTED"),mapped?"project-1":null,mapped?"https://cluster.test":null,mapped?"agent-demo":null,mapped?List.of(target):List.of(),Instant.parse("2026-11-12T23:30:00Z"),state==PhaseState.SUCCEEDED||state==PhaseState.CANCELLED);
    }
    Watch ingest(){return watches.get(watches.ingest(current.get(),"fixture-auth").watchId());}
    PolicyInput input(long revision,Level level,boolean enabled){return new PolicyInput(revision,"workspace-1","10",null,connection,"agent-demo",level,enabled,600,86400,3,30);}
    Policy policy(Level level){return policies.save(UUID.randomUUID(),input(0,level,true),"fixture-operator");}
    void expireCooldown(){jdbc.update("UPDATE agent_diagnosis_trigger SET created_at=created_at-31000");}
    @Test void replayOrderingAndHeartbeatCannotResetMeaningfulProgressOrCallModel() {
        current.set(event("normal1",1,PhaseState.RUNNING,true,0));var first=ingest();
        current.set(event("heartbeat",2,PhaseState.RUNNING,true,0));var next=ingest();
        assertThat(next.lastMeaningfulProgressAt()).isEqualTo(first.lastMeaningfulProgressAt());
        assertThat(watches.ingest(event("late",1,PhaseState.RUNNING,true,0),null).status()).isEqualTo("IGNORED_OLD_SEQUENCE");
        assertThat(watches.get(first.id()).stage().sequence()).isEqualTo(2);
        assertThat(watches.ingest(current.get(),null).watchId()).isEqualTo(first.id());
        assertThatThrownBy(()->watches.ingest(event("heartbeat",2,PhaseState.FAILED,true,0),null)).isInstanceOf(ContextMismatch.class);
        assertThat(watches.timeline(first.id())).hasSize(2);verifyNoInteractions(graph);
    }
    @Test void phaseProgressAndOldMappingAreValidated() {
        var first=ingest();var s=event("move",2,PhaseState.RUNNING,true,1);
        var moved=new StageEvent(1,s.eventId(),s.platformInstanceId(),s.workspaceId(),s.siteId(),s.provisioningId(),s.deploymentVersion(),s.sequence(),s.phase(),s.state(),s.phaseStartedAt(),s.occurredAt(),1,"","",List.of(),s.projectId(),"https://other-cluster.test",s.namespace(),s.resources(),s.provisioningStartedAt(),s.terminal());
        assertThatThrownBy(()->watches.ingest(moved,"fixture-auth")).isInstanceOf(ContextMismatch.class);
        assertThat(watches.get(first.id()).stage().sequence()).isEqualTo(1);
        assertThat(watches.get(watches.ingest(s,"fixture-auth").watchId()).lastMeaningfulProgressAt()).isEqualTo(s.occurredAt());
    }
    @Test void noCrFailureCreatesOnePlatformInvestigationDraftAndNoRecovery() {
        current.set(event("pre-cr",1,PhaseState.FAILED,false,0));var watch=ingest();policy(Level.DIAGNOSE);
        autonomy.tick();autonomy.tick();
        assertThat(autonomy.triggers(watch.id())).singleElement().satisfies(t->{assertThat(t.state()).isEqualTo("MANUAL");assertThat(t.detail()).contains("尚无 CR");});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_learning_outbox",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();verifyNoInteractions(graph);
        verify(deployment,never()).retry(any());
    }
    @Test void observeAndHealthyDeploymentsNeverCallModels() {
        var watch=ingest();var p=policy(Level.OBSERVE);autonomy.tick();assertThat(autonomy.triggers(watch.id())).isEmpty();
        policies.save(p.id(),input(p.revision(),Level.DIAGNOSE,true),"operator");
        var normal=event("healthy",2,PhaseState.SUCCEEDED,true,2);current.set(normal);ingest();autonomy.tick();
        assertThat(autonomy.triggers(watch.id())).isEmpty();verifyNoInteractions(graph);
    }
    @Test void diagnosesOnlyOnceAndNeverHandsOffDiagnosePolicy() {
        var watch=ingest();policy(Level.DIAGNOSE);autonomy.tick();investigations.processNext();autonomy.tick();autonomy.tick();
        verify(graph,times(1)).diagnose(any(Evidence.class),anyString());
        assertThat(autonomy.triggers(watch.id())).singleElement().satisfies(t->assertThat(t.state()).isEqualTo("DONE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
    }
    @Test void pauseAfterQueuePreventsEvidenceAndModelCallsAndDoesNotReplay() {
        var watch=ingest();var p=policy(Level.DIAGNOSE);autonomy.tick();policies.save(p.id(),input(p.revision(),Level.DIAGNOSE,false),"stop-operator");
        investigations.processNext();autonomy.tick();verifyNoInteractions(graph,collector);
        assertThat(autonomy.triggers(watch.id())).singleElement().satisfies(t->assertThat(t.state()).isEqualTo("FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_autonomy_audit",Integer.class)).isEqualTo(2);
    }
    @Test void modelTimeoutIsRecordedAndIdenticalFaultDoesNotRetry() {
        var watch=ingest();policy(Level.DIAGNOSE);when(graph.diagnose(any(Evidence.class),anyString())).thenThrow(new RuntimeException("fixture timeout"));
        autonomy.tick();investigations.processNext();autonomy.tick();expireCooldown();autonomy.tick();
        verify(graph,times(1)).diagnose(any(Evidence.class),anyString());
        assertThat(autonomy.triggers(watch.id())).hasSize(1);assertThat(discovery.task(autonomy.triggers(watch.id()).getFirst().discoveryId()).state()).isEqualTo(DiscoveryStore.State.FAILED);
    }
    @Test void revokedAuthorizationAndScan403AreObservationFailures() {
        var watch=ingest();policy(Level.DIAGNOSE);when(gateway.current(any())).thenThrow(new ConsoleFailure("平台阶段授权失败（HTTP 403）"));autonomy.tick();
        assertThat(watches.get(watch.id()).observationWarning()).contains("403");assertThat(autonomy.triggers(watch.id())).isEmpty();
        doReturn(current.get()).when(gateway).current(any());when(clusters.scan(any(),anyList())).thenThrow(new ConsoleFailure("CR HTTP 403"));investigations.scan(connection,true);autonomy.tick();
        assertThat(autonomy.triggers(watch.id())).isEmpty();verifyNoInteractions(graph);
    }
    @Test void credentialRotationInvalidatesPolicyWithoutChangingBusinessIdentity() {
        var watch=ingest();policy(Level.DIAGNOSE);var c=settings.connection(connection);
        settings.saveConnection(connection,new ConsoleSettings.ConnectionInput(c.revision(),c.name(),c.apiUrl(),"",c.namespace(),"rotated-fixture-token",false));
        autonomy.tick();assertThat(autonomy.triggers(watch.id())).isEmpty();assertThat(watches.get(watch.id()).observationWarning()).contains("凭据");
        assertThat(watches.list(0)).extracting(Watch::id).containsExactly(watch.id());verifyNoInteractions(graph);
    }
    @Test void unverifiedPushCannotReuseOlderAuthorizedSnapshot() {
        var watch=ingest();policy(Level.DIAGNOSE);
        watches.ingest(event("new-push",2,PhaseState.FAILED,true,1),null);
        assertThat(watches.get(watch.id()).authorizedAt()).isNull();
        autonomy.tick();assertThat(autonomy.triggers(watch.id())).isEmpty();verifyNoInteractions(graph);
        assertThat(watches.get(watch.id()).observationWarning()).contains("未核验");
    }
    @Test void incrementalPartialPageRetainsWatchesAndCursorAndFailuresDoNotTerminateThem() {
        var watch=ingest();when(gateway.page("")).thenReturn(new ProvisioningGateway.Page(List.of(current.get()),"cursor-1",true));
        when(gateway.page("cursor-1")).thenThrow(new ConsoleFailure("模拟后续页故障"));
        assertThat(sync.sync().warning()).contains("故障");assertThat(watches.get(watch.id()).stage().state()).isEqualTo(PhaseState.FAILED);
        assertThat(jdbc.queryForObject("SELECT cursor_value FROM agent_provisioning_source",String.class)).isEqualTo("cursor-1");
        doReturn(new ProvisioningGateway.Page(List.of(),"cursor-2",false)).when(gateway).page("cursor-1");sync.sync();
        assertThat(watches.list(0)).hasSize(1);verifyNoInteractions(graph);
    }
    @Test void cursorExpiryRescansWithoutDeletingHistory() {
        var watch=ingest();when(gateway.page("")).thenThrow(new ProvisioningGateway.CursorExpired());sync.sync();
        assertThat(jdbc.queryForObject("SELECT cursor_value FROM agent_provisioning_source",String.class)).isEmpty();
        assertThat(watches.timeline(watch.id())).hasSize(1);
    }
    @Test void approvalHandoffSharesRunAndRequiresCurrentPlanEvenWhenGlobalAutoRetryIsTrue() {
        var watch=ingest();policy(Level.APPROVAL);autonomy.tick();investigations.processNext();autonomy.tick();
        var linked=watches.get(watch.id());assertThat(linked.runId()).isNotNull();
        step(linked.runId());step(linked.runId());var run=runs.find(linked.runId()).orElseThrow();
        assertThat(run.state).isEqualTo(State.WAITING_APPROVAL);verify(deployment,never()).retry(any());
        assertThatThrownBy(()->runs.approve(run.id,"stale-plan","operator")).isInstanceOf(IllegalStateException.class);
        var approved=runs.approve(run.id,run.plan.id(),"operator");assertThat(approved.state).isEqualTo(State.RETRY_WAIT);
        var same=runs.create(new StartRequest("another-key","100",List.of(target),"cycle-1","app"));assertThat(same.id).isEqualTo(run.id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isEqualTo(1);
    }
    @Test void disablingPolicyBeforePersistedExecutionKeepsActionKeyAndNeverWrites() {
        var watch=ingest();var p=policy(Level.APPROVAL);autonomy.tick();investigations.processNext();autonomy.tick();var runId=watches.get(watch.id()).runId();
        step(runId);step(runId);var plan=runs.find(runId).orElseThrow().plan;runs.approve(runId,plan.id(),"operator");step(runId);
        var executing=runs.find(runId).orElseThrow();assertThat(executing.state).isEqualTo(State.EXECUTING);
        policies.save(p.id(),input(p.revision(),Level.APPROVAL,false),"emergency-stop");step(runId);
        var stopped=runs.find(runId).orElseThrow();assertThat(stopped.state).isEqualTo(State.MANUAL_REQUIRED);assertThat(stopped.actionKey).isEqualTo(executing.actionKey);assertThat(stopped.attempts).isEqualTo(1);
        verify(deployment,never()).retry(any());
    }
    @Test void acceptedActionStillReconcilesAfterPolicyStops() {
        var watch=ingest();var p=policy(Level.APPROVAL);autonomy.tick();investigations.processNext();autonomy.tick();var id=watches.get(watch.id()).runId();
        step(id);step(id);runs.approve(id,runs.find(id).orElseThrow().plan.id(),"operator");step(id);
        policies.save(p.id(),input(p.revision(),Level.APPROVAL,false),"stop");when(deployment.lookupRecovery(any())).thenReturn(Optional.of(new Recovery("accepted-on-platform","RUNNING")));step(id);
        assertThat(runs.find(id).orElseThrow().state).isEqualTo(State.VERIFYING);verify(deployment,never()).retry(any());
    }
    @Test void sameReleaseConcurrentRegistrationsShareTerminalBudget()throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return runs.create(new StartRequest("manual-key","100",List.of(target),"cycle-1","app"));});
            var b=pool.submit(()->{gate.await();return runs.create(new StartRequest("event-key","100",List.of(),"cycle-1","app"));});gate.countDown();
            Run one=a.get(10,TimeUnit.SECONDS),two=b.get(10,TimeUnit.SECONDS);assertThat(one.id).isEqualTo(two.id);
            var cancelled=runs.cancel(one.id,"operator");
            assertThat(runs.create(new StartRequest("new-investigation","100",List.of(target),"cycle-1","app")).state).isEqualTo(cancelled.state);
        }
    }
    @Test void requestKeyCannotAliasAnotherExistingRelease() {
        var one=runs.create(new StartRequest("key-one","100",List.of(target),"cycle-1","app"));runs.cancel(one.id,"operator");
        runs.create(new StartRequest("key-two","100",List.of(target),"cycle-2","app"));
        assertThatThrownBy(()->runs.create(new StartRequest("key-one","100",List.of(target),"cycle-2","app"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void runDiagnosisIntentSurvivesUnknownResponseAndDoesNotReplay() {
        Run run=runs.create(new StartRequest("unknown-model","100",List.of(target),"cycle-1","app"));run.evidence=observation.evidence();
        assertThatThrownBy(()->diagnosisBudget.diagnose(run,()->{throw new RuntimeException("response lost");})).isInstanceOf(ContextMismatch.class);
        assertThatThrownBy(()->diagnosisBudget.diagnose(run,()->{throw new AssertionError("must not replay");})).isInstanceOf(ContextMismatch.class);
        assertThat(jdbc.queryForObject("SELECT state FROM agent_run_diagnosis",String.class)).isEqualTo("FAILED");
    }
    @Test void apiRequiresConsoleHeaderAndVersionedEventValidation() {
        var auth=http.withBasicAuth("operator","fixture-api-password-long");
        var response=auth.postForEntity("/api/provisioning-stage-events/v1",current.get(),String.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var watch=watches.list(0).getFirst();
        assertThat(auth.postForEntity("/api/console/provisioning-watches/"+watch.id()+"/pause",new ProvisioningController.PauseInput(watch.revision(),true),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var headers=new HttpHeaders();headers.set("X-Console-Request","1");
        assertThat(auth.exchange("/api/console/provisioning-watches/"+watch.id()+"/pause",HttpMethod.POST,new HttpEntity<>(new ProvisioningController.PauseInput(watch.revision(),true),headers),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void naturalConvergenceAndUnverifiedRecoveryAreSeparateOutcomes() {
        var first=runs.create(new StartRequest("natural","100",List.of(target),"cycle-1","app"));var claim=runs.claim(1).getFirst();first.state=State.SUCCEEDED;runs.save(claim,first,Instant.now());
        assertThat(outcomes.view().counts()).anySatisfy(row->assertThat(row.get("outcome")).isEqualTo("NATURAL_CONVERGENCE"));
        assertThat(outcomes.view().counts()).noneSatisfy(row->assertThat(row.get("outcome")).isEqualTo("VERIFIED_RECOVERY"));
    }
    @Test void autoLowRiskStaysApprovalUntilExplicitRuntimeAcceptanceGateOpens() {
        var watch=ingest();policy(Level.AUTO_LOW_RISK);autonomy.tick();investigations.processNext();autonomy.tick();var id=watches.get(watch.id()).runId();
        step(id);step(id);assertThat(runs.find(id).orElseThrow().state).isEqualTo(State.WAITING_APPROVAL);verify(deployment,never()).retry(any());
    }
    @Test void explicitlyEnabledLowRiskPathExecutesOnceAndRequiresThreeSuccessfulChecks() {
        var enabled=new AutonomyGuard(jdbc,watches,policies,gateway,new ProvisioningProperties(true,"platform-fixture",5,20,true,"CONTRACT_FIXTURE_ONLY"),config);
        engine.autonomy(enabled);
        var watch=ingest();policy(Level.AUTO_LOW_RISK);autonomy.tick();investigations.processNext();autonomy.tick();var id=watches.get(watch.id()).runId();
        step(id);step(id);assertThat(runs.find(id).orElseThrow().state).isEqualTo(State.RETRY_WAIT);
        step(id);when(deployment.retry(any())).thenReturn(new Recovery("fixture-accepted","RUNNING"));step(id);
        assertThat(runs.find(id).orElseThrow().state).isEqualTo(State.VERIFYING);
        when(deployment.lookupRecovery(any())).thenReturn(Optional.of(new Recovery("fixture-accepted","SUCCEEDED")));
        when(deployment.observe(any())).thenReturn(new Evidence(Instant.now(),true,false,"ready","Ready",List.of("fixture ready")));when(deployment.verify(any())).thenReturn(true);
        step(id);assertThat(runs.find(id).orElseThrow().state).isEqualTo(State.VERIFYING);step(id);step(id);
        assertThat(runs.find(id).orElseThrow().verifiedRecovery()).isTrue();verify(deployment,times(1)).retry(any());
        assertThat(jdbc.queryForObject("SELECT outcome FROM agent_recovery_outcome",String.class)).isEqualTo("VERIFIED_RECOVERY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void unknownRecoveryResponseRetainsActionIdentityAndNeverRepeatsWrite() {
        var watch=ingest();policy(Level.APPROVAL);autonomy.tick();investigations.processNext();autonomy.tick();var id=watches.get(watch.id()).runId();
        step(id);step(id);runs.approve(id,runs.find(id).orElseThrow().plan.id(),"operator");step(id);String key=runs.find(id).orElseThrow().actionKey;
        var posted=new java.util.concurrent.atomic.AtomicBoolean();
        when(deployment.lookupRecovery(any())).thenAnswer(call->{if(posted.get())throw new RuntimeException("lookup unavailable");return Optional.empty();});
        when(deployment.retry(any())).thenAnswer(call->{posted.set(true);throw new RuntimeException("accepted but response lost");});
        step(id);step(id);step(id);var result=runs.find(id).orElseThrow();
        assertThat(result.actionKey).isEqualTo(key);assertThat(result.attempts).isEqualTo(1);assertThat(result.state).isEqualTo(State.MANUAL_REQUIRED);verify(deployment,times(1)).retry(any());
        autonomy.tick();assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_autonomy_slot",Integer.class)).isEqualTo(1);
    }
    @Test void clusterQuotaRollsBackNewHandoffAndPreservesExistingRun() {
        var other=runs.create(new StartRequest("other-project","200",List.of(target),"other-cycle","app"));jdbc.update("INSERT INTO agent_autonomy_slot(cluster_api,run_id) VALUES(?,?)","https://cluster.test",other.id);
        var watch=ingest();policy(Level.APPROVAL);autonomy.tick();investigations.processNext();autonomy.tick();
        assertThat(watches.get(watch.id()).runId()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isEqualTo(1);
        assertThat(autonomy.triggers(watch.id())).singleElement().satisfies(t->assertThat(t.detail()).contains("配额"));verify(deployment,never()).retry(any());
    }
    @Test void changedUidDuringModelCallRejectsLatePlanAndRetainsCollectedEvidence() {
        var watch=ingest();policy(Level.DIAGNOSE);autonomy.tick();
        when(graph.diagnose(any(Evidence.class),anyString())).thenAnswer(call->{
            var s=event("new-uid",2,PhaseState.FAILED,true,1);
            current.set(new StageEvent(1,s.eventId(),s.platformInstanceId(),s.workspaceId(),s.siteId(),s.provisioningId(),s.deploymentVersion(),s.sequence(),s.phase(),s.state(),s.phaseStartedAt(),s.occurredAt(),s.completedUnits(),s.errorCode(),s.summary(),s.dependencies(),s.projectId(),s.clusterApiUrl(),s.namespace(),List.of(new Target(target.kind(),target.namespace(),target.name(),"replacement-uid",2)),s.provisioningStartedAt(),s.terminal()));
            return plan();
        });
        investigations.processNext();autonomy.tick();var task=discovery.task(autonomy.triggers(watch.id()).getFirst().discoveryId());
        assertThat(task.state()).isEqualTo(DiscoveryStore.State.FAILED);assertThat(task.report().evidence()).isNotNull();assertThat(task.report().plan().action()).isEqualTo(Action.MANUAL_REPAIR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
    }
    void step(UUID id) {
        jdbc.update("UPDATE agent_run SET due_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);
        var claim=runs.claim(1).getFirst();assertThat(claim.id()).isEqualTo(id);engine.step(claim);
    }
}
