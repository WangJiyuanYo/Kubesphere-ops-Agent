package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.*;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.command.CommandPlanner;
import com.lanhao.ops.persistence.*;
import com.lanhao.ops.workflow.RunScheduler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

abstract class DiscoveryContract {
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryService service;
    @Autowired ConsoleSettings settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;
    @Autowired TransactionTemplate tx;
    @Autowired ChatStore chats;
    @Autowired AgentChat agent;
    @Autowired ChatInspectionStore inspections;
    @Autowired ChatInspectionController inspectionController;
    @Autowired MemoryController memoryController;
    @Autowired RunStore runs;
    @Autowired com.lanhao.ops.workflow.RunEngine engine;
    @Autowired com.lanhao.ops.memory.MemoryStore memories;
    @Autowired com.lanhao.ops.memory.MemoryRecall recall;
    @Autowired DiscoveryGateway liveDiscovery;
    @Autowired DeploymentCaseService cases;
    @Autowired PlatformCatalog platformCatalog;
    @MockitoBean PlatformCatalogGateway platformGateway;
    @MockitoBean DiscoveryScheduler discoveryScheduler;
    @MockitoBean RunScheduler scheduler;
    @MockitoBean DeploymentInbox inbox;
    @MockitoBean CommandPlanner commandPlanner;
    @MockitoBean ModelGateway modelGateway;
    @MockitoBean com.lanhao.ops.memory.LearningOutbox backgroundLearning;
    HttpServer server;
    UUID connectionId;
    AtomicInteger code=new AtomicInteger(200), calls=new AtomicInteger(),podCode=new AtomicInteger(200);
    AtomicReference<String> resource=new AtomicReference<>();
    String base;
    @Test void platformCatalogPersistsPreCrPhasesAndKeepsLastSuccessOnFailureWithoutModelOrRecovery() {
        when(platformGateway.enabled()).thenReturn(true);when(platformGateway.source()).thenReturn("fixture-platform");
        var early=catalogEntry("9007199254740993","","",base);
        when(platformGateway.scan()).thenReturn(new PlatformCatalogGateway.Batch(List.of(early),false));
        assertThat(platformCatalog.sync().items()).containsExactly(early);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_platform_catalog",Integer.class)).isEqualTo(1);
        when(platformGateway.scan()).thenThrow(new ConsoleFailure("目录暂时不可读取"));
        assertThat(platformCatalog.sync().warning()).contains("暂时不可读取");assertThat(platformCatalog.view(0).items()).containsExactly(early);
        doReturn(new PlatformCatalogGateway.Batch(List.of(),true)).when(platformGateway).scan();
        assertThat(platformCatalog.sync().warning()).contains("扫描页数上限");assertThat(platformCatalog.view(0).items()).hasSize(1);
        when(platformGateway.source()).thenReturn("other-platform");assertThat(platformCatalog.view(0).items()).isEmpty();
        when(platformGateway.source()).thenReturn("fixture-platform");when(platformGateway.scan()).thenReturn(new PlatformCatalogGateway.Batch(List.of(),false));
        assertThat(platformCatalog.sync().items()).isEmpty();
        assertThat(calls.get()).isZero();verifyNoInteractions(modelGateway);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
    }
    @Test void platformProjectMapsToAuthorizedConnectionAndCurrentCrWithoutAutomaticSelection() {
        when(platformGateway.enabled()).thenReturn(true);when(platformGateway.source()).thenReturn("fixture-platform");
        var entry=catalogEntry("9007199254740993","123","agent-demo",base);
        when(platformGateway.scan()).thenReturn(new PlatformCatalogGateway.Batch(List.of(entry),false));
        when(platformGateway.current(entry.id())).thenReturn(entry);platformCatalog.sync();
        var mapped=platformCatalog.monitor(entry.id(),connectionId);
        assertThat(mapped.investigations()).hasSize(1);var discovered=store.task(mapped.investigations().getFirst());
        assertThat(discovered.state()).isEqualTo(DiscoveryStore.State.PENDING);
        assertThat(platformCatalog.matches(discovered.id())).containsExactly(entry);
        when(platformGateway.current(entry.id())).thenReturn(catalogEntry(entry.id(),"123","agent-demo",base+"/other-cluster"));
        long revision=store.source(connectionId).revision();
        assertThatThrownBy(()->platformCatalog.monitor(entry.id(),connectionId)).isInstanceOf(ContextMismatch.class);
        assertThat(store.source(connectionId).revision()).isEqualTo(revision);
        verifyNoInteractions(modelGateway);assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
    }
    PlatformCatalogGateway.Entry catalogEntry(String id,String project,String namespace,String api) {
        return new PlatformCatalogGateway.Entry(id,"9007199254740995","provision-cycle", "RUNNING","PROJECT_PENDING","workspace","project","site.example",project,namespace,"east",api,namespace.isEmpty()?"项目尚未创建，仅可查看平台阶段":"");
    }
    @Test void completesDiscoveryApprovalStrictRecoveryVerificationAndRecallsOriginalFaultOnNextInvestigation()throws Exception {
        // 本机平台/集群 HTTP 替身与进程边界替身；状态机、Graph、双库、审计和记忆均执行真实实现。
        when(platformGateway.enabled()).thenReturn(true);when(platformGateway.source()).thenReturn("journey-platform");
        var entry=catalogEntry("9007199254740993","123","agent-demo",base);
        when(platformGateway.scan()).thenReturn(new PlatformCatalogGateway.Batch(List.of(entry),false));when(platformGateway.current(entry.id())).thenReturn(entry);
        platformCatalog.sync();var mapping=platformCatalog.monitor(entry.id(),connectionId);UUID caseId=mapping.investigations().getFirst();
        var discovered=store.task(caseId);store.select(caseId,discovered.revision(),UUID.randomUUID(),"operator");service.processNext();
        assertThat(store.task(caseId).state()).isEqualTo(DiscoveryStore.State.DONE);
        var target=discovered.observation().target();var requestKey=new AtomicReference<String>();var postCount=new AtomicInteger();
        HttpServer platform=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String platformBase="http://127.0.0.1:"+platform.getAddress().getPort();
        platform.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();Map<String,Object> data;
            if(path.equals("/health")) {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();data=Map.of("status","UP","version","v2");
            }else {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer local-platform-fixture");
                if(exchange.getRequestMethod().equals("POST")) {
                    var body=json.readTree(exchange.getRequestBody());requestKey.set(body.path("requestKey").asText());postCount.incrementAndGet();
                    assertThat(body.path("expectedVersion").asText()).isEqualTo("provision-cycle");
                    assertThat(body.path("expectedTargets").get(0).path("uid").asText()).isEqualTo("uid-1");
                    var updated=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(resource.get());
                    ((com.fasterxml.jackson.databind.node.ObjectNode)updated.path("metadata")).put("generation",3);
                    ((com.fasterxml.jackson.databind.node.ObjectNode)updated.path("spec")).put("retrySequence",1);
                    ((com.fasterxml.jackson.databind.node.ObjectNode)updated.path("status")).put("phase","Ready").put("observedGeneration",3);
                    resource.set(json.writeValueAsString(updated));
                    data=Map.of("code",200,"data",Map.of("id","901","requestKey",requestKey.get(),"state","ACCEPTED","acceptedVersion","provision-cycle"));
                }else {
                    var snapshot=new LinkedHashMap<String,Object>();snapshot.put("id",entry.id());snapshot.put("version","provision-cycle");
                    snapshot.put("applicationVersion","v2");snapshot.put("verificationProfile","site-app");snapshot.put("clusterApiUrl",base);
                    snapshot.put("targetsComplete",true);snapshot.put("snapshotToken","snapshot-fixture");snapshot.put("targets",List.of(target));
                    snapshot.put("status",requestKey.get()==null?"FAILED":"SUCCEEDED");
                    if(requestKey.get()!=null)snapshot.put("recovery",Map.of("id","901","requestKey",requestKey.get(),"state","SUCCEEDED","acceptedVersion","provision-cycle"));
                    data=Map.of("code",200,"data",snapshot);
                }
            }
            byte[] bytes=json.writeValueAsBytes(data);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });platform.start();
        try(var kubernetes=new io.fabric8.kubernetes.client.KubernetesClientBuilder().withConfig(new io.fabric8.kubernetes.client.ConfigBuilder()
                .withMasterUrl(base).withOauthToken("fake-connection-key").withConnectionTimeout(1000).withRequestTimeout(2000).withRequestRetryBackoffLimit(0).build()).build()) {
            var config=new com.lanhao.ops.AgentProperties(com.lanhao.ops.AgentProperties.Mode.KUBERNETES,false,false,2,
                    java.time.Duration.ofMinutes(10),java.time.Duration.ofHours(1),java.time.Duration.ofSeconds(1),java.time.Duration.ofMinutes(5),
                    java.time.Duration.ofMinutes(5),3,List.of("agent-demo"),"operator","local-fixture-password",java.net.URI.create(platformBase),"local-platform-fixture",null);
            var ops=new com.lanhao.ops.OpsProperties(4,java.time.Duration.ofSeconds(75),java.time.Duration.ofSeconds(45),true,
                    Map.of("site-app",new com.lanhao.ops.OpsProperties.VerificationProfile(java.net.URI.create(platformBase+"/health"))),new com.lanhao.ops.OpsProperties.Notification(false,null,null,8));
            var redactor=new com.lanhao.ops.evidence.Redactor();var port=new com.lanhao.ops.port.KubernetesDeploymentPort(kubernetes,config,json,redactor,ops);
            var liveRuns=new JdbcRunStore(jdbc,json,tx,config,new com.lanhao.ops.domain.DeploymentIdentity(config,port));
            var liveCases=new DeploymentCaseService(store,liveDiscovery,liveRuns,port,config,ops,jdbc,tx,json,chats,memories);
            var registration=liveCases.resolve(caseId,new DeploymentCaseService.PlatformInput(store.task(caseId).revision(),entry.id()));
            var input=new DeploymentCaseService.RecoveryInput(store.task(caseId).revision(),entry.id(),registration.deploymentVersion(),registration.verificationProfile());
            var run=liveCases.handoff(caseId,input,"operator");
            var cli=new com.lanhao.ops.command.KubectlProperties(true,"/fixture/kubectl","/fixture/kubeconfig","fixture",java.time.Duration.ofSeconds(5),2);
            var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("kubernetes",kubernetes);
            var planner=new CommandPlanner(settings,modelGateway,config,beans.getBeanProvider(org.springframework.ai.chat.model.ChatModel.class),redactor);
            var scopes=new com.lanhao.ops.command.CommandScopeResolver(config,beans.getBeanProvider(io.fabric8.kubernetes.client.KubernetesClient.class));
            var process=mock(com.lanhao.ops.command.BoundedProcess.class);when(process.run(anyList(),any())).thenReturn(new com.lanhao.ops.command.BoundedProcess.Result("SUCCEEDED","REGISTRY_TIMEOUT 已读取当前资源",0));
            var identity=mock(com.lanhao.ops.command.KubectlIdentity.class);when(identity.unchanged()).thenReturn(true);
            var commands=new com.lanhao.ops.command.KubectlWorkflow(cli,config,planner,scopes,new com.lanhao.ops.command.CommandSafety(config,cli,redactor),
                    new com.lanhao.ops.command.CommandAudit(jdbc,json,tx),process,redactor,identity);
            var policy=new com.lanhao.ops.diagnosis.RecoveryPolicy();
            var graph=new com.lanhao.ops.diagnosis.DiagnosisGraph(redactor,policy,new com.lanhao.ops.diagnosis.RunbookNarrator(),json,config,recall,commands);
            var engine=new com.lanhao.ops.workflow.RunEngine(liveRuns,port,graph,config,policy);
            for(int step=0;step<15&&!liveRuns.find(run.id).orElseThrow().state.terminal();step++) {
                var current=liveRuns.find(run.id).orElseThrow();
                if(current.state==com.lanhao.ops.domain.Model.State.WAITING_APPROVAL) {
                    assertThat(postCount.get()).isZero();assertThat(current.plan.action()).isEqualTo(Action.RETRY_TRANSIENT);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_command_execution WHERE run_id=? AND state='SUCCEEDED'",Integer.class,run.id)).isEqualTo(2);
                    liveRuns.approve(run.id,current.plan.id(),"operator");
                }
                jdbc.update("UPDATE agent_run SET due_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),run.id);
                engine.step(liveRuns.claim(1).getFirst());
            }
            var completed=liveRuns.find(run.id).orElseThrow();assertThat(completed.state).isEqualTo(com.lanhao.ops.domain.Model.State.SUCCEEDED);
            assertThat(completed.attempts).isEqualTo(1);assertThat(completed.stableCount).isEqualTo(3);assertThat(postCount.get()).isEqualTo(1);
            assertThat(completed.applicationVersion).isEqualTo("v2");assertThat(completed.diagnosisEvidence.failed()).isTrue();assertThat(completed.evidence.ready()).isTrue();
            new com.lanhao.ops.memory.MemoryCapture(jdbc,memories,json,config).capture();
            var recalled=recall.find("REGISTRY_TIMEOUT",com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo"));
            assertThat(recalled.references()).hasSize(1);assertThat(recalled.context()).contains("已受理并验证的动作").doesNotContain("never-persist-spec","never-persist-token");
            assertThat(liveCases.handoff(caseId,input,"operator").id).isEqualTo(run.id);assertThat(postCount.get()).isEqualTo(1);
            verify(process,times(2)).run(argThat(argv->!argv.contains("delete")&&!argv.contains("sh")&&argv.contains("--namespace=agent-demo")),any());
        }finally{platform.stop(0);}
    }
    @Test void resolvesTrustedPlatformIdentityAndMemoryOutageDoesNotHideCoreCase() {
        var task=discover();var port=mock(com.lanhao.ops.port.DeploymentPort.class);when(port.clusterApiUrl()).thenReturn(base);
        var registration=new StartRequest("platform", "9007199254740993",List.of(task.observation().target()),"provision-cycle-1","site-app");
        when(port.registration(registration.provisioningId())).thenReturn(registration);
        when(port.discover(any())).thenReturn(registration.targets());
        var resolved=liveCases(port).resolve(task.id(),new DeploymentCaseService.PlatformInput(task.revision(),registration.provisioningId()));
        assertThat(resolved.deploymentVersion()).isEqualTo("provision-cycle-1");assertThat(resolved.targets()).containsExactly(task.observation().target());
        var broken=mock(com.lanhao.ops.memory.MemoryStore.class);when(broken.bySourceRefs(anyList())).thenThrow(new IllegalStateException("memory fixture failure"));
        var available=new DeploymentCaseService(store,liveDiscovery,runs,port,com.lanhao.ops.TestFixtures.config(false),com.lanhao.ops.TestFixtures.ops(),jdbc,tx,json,chats,broken).view(task.id());
        assertThat(available.investigation().id()).isEqualTo(task.id());assertThat(available.learningWarning()).contains("总结暂不可读取");
        assertThat(available.reflections()).isEmpty();assertThat(available.recoveryBlockedReason()).contains("DEMO");
    }
    @Test void naturalConvergenceNeverBecomesVerifiedActionExperienceAndLegacyOutboxIsDowngraded()throws Exception {
        var run=runs.create(com.lanhao.ops.TestFixtures.request("natural-convergence"));var claim=runs.claim(1).getFirst();
        run.state=com.lanhao.ops.domain.Model.State.SUCCEEDED;run.attempts=1;run.stableCount=3;run.actionKey="intent-only";
        run.plan=new Plan("plan",Action.RETRY_TRANSIENT,new Diagnosis("未执行建议",List.of("检查"),List.of("重试"),"现场自行恢复","fixture"),true,Instant.now());
        runs.save(claim,run,Instant.now());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory_outbox",Integer.class)).isZero();
        assertThatThrownBy(()->memories.capture(run)).isInstanceOf(IllegalArgumentException.class);
        // 历史版本遗留的无动作凭据出队记录也必须降级，不能通过重放获得 VERIFIED。
        jdbc.update(new SqlDialect(jdbc).sql("INSERT INTO agent_memory_outbox(run_id,payload,environment) VALUES(?,@json@,'DEMO')"),run.id,json.writeValueAsString(run));
        new com.lanhao.ops.memory.MemoryCapture(jdbc,memories,json,com.lanhao.ops.TestFixtures.config(false)).capture();
        new com.lanhao.ops.memory.LearningOutbox(jdbc,tx,json,memories).capture();
        var summary=memories.reflection(run.id).orElseThrow();assertThat(summary.status()).isEqualTo(com.lanhao.ops.memory.MemoryStore.Status.DRAFT);
        assertThat(summary.verification()).contains("不能证明原建议有效");assertThat(recall.find("未执行建议","DEMO").references()).isEmpty();
    }
    @Test void multiNamespaceExperienceHasSafePerNamespaceRecallWithoutCrossNamespaceBody() {
        var run=new com.lanhao.ops.domain.Model.Run(com.lanhao.ops.TestFixtures.request("multi-scope"),Instant.now());
        run.environment="KUBERNETES";run.clusterApiUrl=base;
        run.targets=List.of(new Target(Kind.ProjectRuntime,"common","runtime","common-uid",1),new Target(Kind.PlatformRelease,"prod","app","prod-uid",1));
        run.state=com.lanhao.ops.domain.Model.State.SUCCEEDED;run.attempts=1;run.stableCount=3;run.actionKey="accepted";run.recovery=new Recovery("receipt","SUCCEEDED");
        run.plan=new Plan("plan",Action.RETRY_TRANSIENT,new Diagnosis("private-prod-marker",List.of("整体诊断"),List.of("整体步骤"),"整体验收","fixture"),true,Instant.now());
        run.evidence=new Evidence(Instant.now(),true,false,"ready","Ready",List.of(),List.of(
                new EvidenceItem("CR/runtime","common-uid",1,Instant.now(),"LOCAL_TIMEOUT","common retry",true),
                new EvidenceItem("CR/app","prod-uid",1,Instant.now(),"PROD_TIMEOUT","private-prod-marker",true)),List.of());
        var parent=memories.capture(run);memories.capture(run);
        String common=com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"common");
        var matches=memories.search("LOCAL_TIMEOUT",com.lanhao.ops.memory.MemoryStore.Status.VERIFIED,10,common);
        assertThat(matches).hasSize(1);assertThat(matches.getFirst().sourceType()).isEqualTo("RUN_COMPONENT");
        assertThat(json.valueToTree(matches).toString()).doesNotContain("private-prod-marker","prod-uid");
        assertThat(recall.find("LOCAL_TIMEOUT",common).references()).hasSize(1);
        assertThat(recall.find("LOCAL_TIMEOUT",com.lanhao.ops.domain.DeploymentIdentity.realScope(base+"/different","common")).references()).isEmpty();
        assertThat(recall.find("private-prod-marker",common).references()).isEmpty();
        memories.update(parent.id(),parent.revision(),new com.lanhao.ops.memory.MemoryStore.Input(parent.title(),parent.problem(),parent.cause(),parent.solution(),parent.verification(),parent.tags(),com.lanhao.ops.memory.MemoryStore.Status.ARCHIVED));
        assertThat(recall.find("LOCAL_TIMEOUT",common).references()).isEmpty();
        memories.capture(run); // 重放不能复活父经验或间接放行投影。
        assertThat(recall.find("LOCAL_TIMEOUT",common).references()).isEmpty();
    }
    @Test void caseRecoveryRequiresRealExecutionIdentityAndStrictPlatformBinding() {
        var task=discover();
        var input=new DeploymentCaseService.RecoveryInput(task.revision(),"9007199254740993","v2","site-app");
        assertThat(cases.view(task.id()).recoveryBlockedReason()).contains("DEMO");
        assertThatThrownBy(()->cases.handoff(task.id(),input,"operator")).isInstanceOf(ContextMismatch.class);
        var port=mock(com.lanhao.ops.port.DeploymentPort.class);
        when(port.clusterApiUrl()).thenReturn(base);
        var live=liveCases(port);
        when(port.discover(any())).thenReturn(List.of(new Target(Kind.PlatformRelease,"agent-demo","other","other-uid",2)));
        assertThatThrownBy(()->live.preview(task.id(),input)).isInstanceOf(ContextMismatch.class).hasMessageContaining("不包含所选资源");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_case_recovery",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
    }
    @Test void handoffLinksCaseChatAndOneRecoveryBudgetAndReplaysWithoutRemoteCalls() {
        var task=discover();
        var session=chats.create("持续调查",connectionId,null,task.id());
        var port=mock(com.lanhao.ops.port.DeploymentPort.class);when(port.clusterApiUrl()).thenReturn(base);
        when(port.discover(any())).thenReturn(List.of(task.observation().target()));var live=liveCases(port);
        var input=new DeploymentCaseService.RecoveryInput(task.revision(),"9007199254740993","v2","site-app");
        assertThat(live.preview(task.id(),input).targets()).containsExactly(task.observation().target());
        var run=live.handoff(task.id(),input,"operator");
        assertThat(run.environment).isEqualTo("KUBERNETES");
        assertThat(run.attempts).isZero();int before=calls.get();
        assertThat(live.handoff(task.id(),input,"operator").id).isEqualTo(run.id);
        assertThat(calls.get()).isEqualTo(before);
        var view=live.view(task.id());assertThat(view.recovery().id).isEqualTo(run.id);
        assertThat(view.sessions()).extracting(ChatStore.Session::id).contains(session.id());
        var reply=agent.send(session.id(),UUID.randomUUID(),"转交进度",false);
        assertThat(reply.assistantContent()).contains("同一调查案例关联的受控恢复账本",run.id.toString(),"9007199254740993");
        assertThatThrownBy(()->live.handoff(task.id(),new DeploymentCaseService.RecoveryInput(0,"9007199254740993","v3","site-app"),"operator"))
                .isInstanceOf(ContextMismatch.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT selected_by FROM agent_case_recovery",String.class)).isEqualTo("operator");
    }
    @Test void anotherInvestigationReusesExistingReleaseEvenAfterTerminalState()throws Exception {
        var task=discover();var port=mock(com.lanhao.ops.port.DeploymentPort.class);when(port.clusterApiUrl()).thenReturn(base);
        var secondObservation=new DiscoveryGateway.Observation(new Target(Kind.PlatformRelease,"agent-demo","demo-app","uid-2",2),"v2","123","prod","Failed",2,"REGISTRY_TIMEOUT","");
        when(port.discover(any())).thenReturn(List.of(task.observation().target(),secondObservation.target()));var live=liveCases(port);
        var input=new DeploymentCaseService.RecoveryInput(0,"9007199254740993","v2","site-app");
        var run=live.handoff(task.id(),input,"operator");run.state=com.lanhao.ops.domain.Model.State.FAILED;run.attempts=2;
        jdbc.update(new SqlDialect(jdbc).sql("UPDATE agent_run SET payload=@json@,state='FAILED' WHERE id=?"),json.writeValueAsString(run),run.id);
        store.scanned(store.claimScan(connectionId,true),List.of(task.observation(),secondObservation),"");
        var second=store.list(null,null,0).items().stream().filter(t->t.observation().target().uid().equals("uid-2")).findFirst().orElseThrow();
        resource.set(DiscoveryGatewayTest.resource("PlatformRelease","agent-demo","uid-2",2,"Failed",2));
        var reused=live.handoff(second.id(),input,"operator");
        assertThat(reused.id).isEqualTo(run.id);assertThat(reused.attempts).isEqualTo(2);assertThat(reused.state).isEqualTo(com.lanhao.ops.domain.Model.State.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isEqualTo(1);
    }
    private DeploymentCaseService liveCases(com.lanhao.ops.port.DeploymentPort port) {
        var config=mock(com.lanhao.ops.AgentProperties.class);when(config.mode()).thenReturn(com.lanhao.ops.AgentProperties.Mode.KUBERNETES);
        when(config.namespaces()).thenReturn(List.of("agent-demo"));
        var ops=mock(com.lanhao.ops.OpsProperties.class);when(ops.strictPlatformContract()).thenReturn(true);
        when(ops.verificationProfiles()).thenReturn(Map.of("site-app",new com.lanhao.ops.OpsProperties.VerificationProfile(java.net.URI.create("https://app.example.test/health"))));
        var liveRuns=new JdbcRunStore(jdbc,json,tx,config,new com.lanhao.ops.domain.DeploymentIdentity(config,port));
        return new DeploymentCaseService(store,liveDiscovery,liveRuns,port,config,ops,jdbc,tx,json,chats,memories);
    }
    @Test void explicitNamespacesAreIndependentOfDefaultAndConversationsUseActualTarget() {
        var initial=store.configure(connectionId,true,0,List.of("agent-demo","tenant-b"));
        assertThat(initial.namespaces()).containsExactly("agent-demo","tenant-b");
        var claim=store.claimScan(connectionId,true);
        var a=new DiscoveryGateway.Observation(new Target(Kind.PlatformRelease,"agent-demo","demo-app","a",2),"v2","p","prod","Failed",2,"REGISTRY_TIMEOUT","");
        var b=new DiscoveryGateway.Observation(new Target(Kind.PlatformRelease,"tenant-b","demo-app","b",2),"v2","p","prod","Failed",2,"REGISTRY_TIMEOUT","");
        store.scanned(claim,List.of(a,b),"");
        var task=store.list(null,null,0).items().stream().filter(t->t.observation().target().namespace().equals("tenant-b")).findFirst().orElseThrow();
        var session=chats.create("实际目标",connectionId,null,task.id());
        assertThat(agent.send(session.id(),UUID.randomUUID(),"目标在哪",false).assistantContent()).contains("命名空间：tenant-b");
        var old=settings.connection(connectionId);
        settings.saveConnection(connectionId,new ConsoleSettings.ConnectionInput(old.revision(),old.name(),base,"","unmonitored-default","",false));
        assertThat(store.connection(task).namespace()).isEqualTo("tenant-b");
        assertThat(agent.send(session.id(),UUID.randomUUID(),"继续",false).state()).isEqualTo("SUCCEEDED");
        var selected=store.select(task.id(),task.revision(),UUID.randomUUID(),"operator");assertThat(selected.state()).isEqualTo(DiscoveryStore.State.QUEUED);
        store.configure(connectionId,true,initial.revision(),List.of("agent-demo"));
        int before=calls.get();
        assertThat(agent.send(session.id(),UUID.randomUUID(),"范围已移除",true).state()).isEqualTo("FAILED");
        assertThat(calls.get()).isEqualTo(before);
        assertThatThrownBy(()->store.connection(task)).isInstanceOf(ContextMismatch.class);
        store.scanned(store.claimScan(connectionId,true),List.of(a),"");
        assertThat(store.task(task.id()).presence()).isEqualTo("OUTDATED");
    }
    @Test void changingScopeInvalidatesLateScansAndRepliesWithoutInventingMissingResources() {
        var task=discover();var session=chats.create("范围冻结",connectionId,null,task.id());
        var turn=chats.begin(session.id(),UUID.randomUUID(),"旧范围",false);
        var scan=store.claimScan(connectionId,true);
        store.configure(connectionId,true,1,List.of("tenant-b"));
        store.scanned(scan,List.of(),"");
        assertThat(store.task(task.id()).presence()).isEqualTo("PRESENT");
        var result=chats.finish(turn,"迟到建议","模型",true,List.of(),com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo"));
        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_learning_outbox",Integer.class)).isZero();
    }
    @Test void rejectsInvalidAndUnscannedNamespaces() {
        for(List<String> invalid:List.of(List.<String>of(),List.of("*"),List.of("a","a"),List.of("A")))
            assertThatThrownBy(()->store.configure(connectionId,true,0,invalid)).isInstanceOf(IllegalArgumentException.class);
        var task=discover();var scan=store.claimScan(connectionId,true);
        var alien=new DiscoveryGateway.Observation(new Target(Kind.ProjectRuntime,"alien","other","alien",1),"","","","Pending",0,"","");
        assertThatThrownBy(()->store.scanned(scan,List.of(alien),"")).isInstanceOf(ConsoleFailure.class);
        assertThat(store.task(task.id()).presence()).isEqualTo("PRESENT");
    }
    @Test void connectionChangeBeforeReplyCommitRejectsLateResultAndLearningAtomically() {
        var session=chats.create("提交时校验",connectionId,null);
        var claim=chats.begin(session.id(),UUID.randomUUID(),"旧环境问题",true);
        var old=settings.connection(connectionId);
        settings.saveConnection(connectionId,new ConsoleSettings.ConnectionInput(old.revision(),old.name(),base,"","other-ns","",false));
        var result=chats.finish(claim,"不得采纳的旧环境答案","模型",true,List.of(),com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo"));
        assertThat(result.state()).isEqualTo("FAILED");assertThat(result.assistantContent()).contains("未采纳旧结果").doesNotContain("不得采纳的旧环境答案");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_learning_outbox",Integer.class)).isZero();
    }
    @Test void investigationsAndChatsAutomaticallySummarizeWithoutTrustingUnverifiedConclusions() {
        var task=discover();store.select(task.id(),task.revision(),UUID.randomUUID(),"operator");service.processNext();
        var session=chats.create("复盘对话",connectionId,null,task.id());UUID request=UUID.randomUUID();
        var answer=agent.send(session.id(),request,"reflection-proof 镜像拉取",false);
        agent.send(session.id(),request,"reflection-proof 镜像拉取",false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_learning_outbox",Integer.class)).isEqualTo(2);
        assertThat(http.withBasicAuth("operator","demo-local-only").getForObject("/api/console/memories/reflections/"+answer.id(),JsonNode.class).path("state").asText()).isEqualTo("PENDING");
        var learning=new com.lanhao.ops.memory.LearningOutbox(jdbc,tx,json,memories);learning.capture();learning.capture();
        String scope=com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo");
        var entries=memories.search("",null,50,scope);
        assertThat(entries).hasSize(2).allMatch(e->e.status()==com.lanhao.ops.memory.MemoryStore.Status.DRAFT);
        assertThat(cases.view(task.id()).reflections()).extracting(com.lanhao.ops.memory.MemoryStore.Entry::id)
                .containsExactlyInAnyOrderElementsOf(entries.stream().map(com.lanhao.ops.memory.MemoryStore.Entry::id).toList());
        assertThat(entries).allMatch(e->e.sourceType().equals("REFLECTION"));
        assertThat(recall.find("reflection-proof",scope).references()).isEmpty();
        var draft=entries.stream().filter(e->e.sourceRef().equals(answer.id().toString())).findFirst().orElseThrow();
        var reflection=http.withBasicAuth("operator","demo-local-only").getForObject("/api/console/memories/reflections/"+answer.id(),JsonNode.class);
        assertThat(reflection.path("state").asText()).isEqualTo("READY");
        assertThat(reflection.path("entry").path("id").asText()).isEqualTo(draft.id().toString());
        var confirmed=memories.update(draft.id(),draft.revision(),new com.lanhao.ops.memory.MemoryStore.Input(draft.title(),
                draft.problem(),"人工复核根因", "人工补充了实际有效的解决步骤", "已按目标版本完成现场核验", "reflection-proof",com.lanhao.ops.memory.MemoryStore.Status.VERIFIED));
        assertThat(recall.find("reflection-proof",scope).references()).extracting(MemoryReference::id).containsExactly(confirmed.id());
        jdbc.update("UPDATE agent_learning_outbox SET processed_at=NULL");learning.capture();
        assertThat(memories.get(draft.id()).solution()).isEqualTo("人工补充了实际有效的解决步骤");
        assertThat(memories.get(draft.id()).revision()).isEqualTo(2);
    }
    @Test void malformedLearningCannotPromoteItselfAndDoesNotBlockOtherSummaries() {
        String scope=com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo");
        var invalid=new com.lanhao.ops.memory.MemoryStore.Input("invalid-summary", "伪造已验证", "未核验", "不能信任", "文字不构成实际验收", "",
                com.lanhao.ops.memory.MemoryStore.Status.VERIFIED);
        com.lanhao.ops.memory.LearningOutbox.enqueue(jdbc,json,new com.lanhao.ops.memory.LearningOutbox.Draft("invalid-summary","invalid",scope,invalid));
        var session=chats.create("正常复盘",connectionId,null);var turn=agent.send(session.id(),UUID.randomUUID(),"valid-summary",false);
        new com.lanhao.ops.memory.LearningOutbox(jdbc,tx,json,memories).capture();
        assertThat(memories.reflection(turn.id())).isPresent();
        assertThat(memories.search("",null,50,scope)).noneMatch(entry->entry.title().equals("invalid-summary"));
        assertThat(memories.search("",com.lanhao.ops.memory.MemoryStore.Status.VERIFIED,50,scope)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempts FROM agent_learning_outbox WHERE source_key='invalid-summary'",Integer.class)).isEqualTo(1);
    }
    @Test void failedAndUnboundChatsDoNotCreateAutomaticallyTrustedLearning() {
        var unbound=chats.create("无目标",null,null);agent.send(unbound.id(),UUID.randomUUID(),"任意问题",false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_learning_outbox",Integer.class)).isZero();
        var session=chats.create("失败",connectionId,null);
        settings.saveLlm(new ConsoleSettings.LlmInput(0,true,ConsoleSettings.Provider.OPENAI_COMPATIBLE,base+"/v1","fixture-model","fake-key",false,0.1,512));
        when(modelGateway.complete(any(),anyList())).thenThrow(new ModelFailure(ModelFailure.Code.TIMEOUT,0));
        assertThat(agent.send(session.id(),UUID.randomUUID(),"模型失败",false).state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_learning_outbox",Integer.class)).isZero();
    }
    @Test void terminalFailureRetainsAnUnverifiedRetrospectiveInsteadOfInventingSuccess() {
        var run=runs.create(com.lanhao.ops.TestFixtures.request("failed-retrospective"));
        var claim=runs.claim(1).getFirst();run.state=com.lanhao.ops.domain.Model.State.MANUAL_REQUIRED;
        run.events.add(new Event(Instant.now(),run.state,"需要补充容器事件"));runs.save(claim,run,Instant.now());
        var learning=new com.lanhao.ops.memory.LearningOutbox(jdbc,tx,json,memories);learning.capture();
        var results=memories.search("",null,50,"DEMO").stream().filter(e->e.sourceRef().equals(run.id.toString())).toList();
        assertThat(results).hasSize(1);assertThat(results.getFirst().status()).isEqualTo(com.lanhao.ops.memory.MemoryStore.Status.DRAFT);
        assertThat(results.getFirst().verification()).contains("不能作为已解决案例");
    }
    @Test void ordinaryConversationFreezesTargetBeforeReadsAndDoesNotFollowNamespaceEdit() {
        var session=chats.create("冻结目标",connectionId,null);
        assertThat(agent.send(session.id(),UUID.randomUUID(),"第一次读取",true).assistantContent()).contains("live-web-1");
        int before=calls.get();
        var old=settings.connection(connectionId);
        settings.saveConnection(connectionId,new ConsoleSettings.ConnectionInput(old.revision(),old.name(),base,"","other-ns","",false));
        var rejected=agent.send(session.id(),UUID.randomUUID(),"继续读取",true);
        assertThat(rejected.state()).isEqualTo("FAILED");assertThat(rejected.assistantContent()).contains("命名空间已变化","新建对话");
        assertThat(calls.get()).isEqualTo(before);verifyNoInteractions(modelGateway);
        assertThat(chats.turns(session.id())).extracting(ChatStore.Turn::state).containsExactly("SUCCEEDED","FAILED");
    }
    @Test void sameTargetRenameAndCredentialRotationKeepConversationUsableButLegacyOriginDoesNot() {
        var session=chats.create("原目标",connectionId,null);var old=settings.connection(connectionId);
        settings.saveConnection(connectionId,new ConsoleSettings.ConnectionInput(old.revision(),"新显示名称",base,"","agent-demo","fake-connection-key",false));
        assertThat(agent.send(session.id(),UUID.randomUUID(),"继续查看",true).state()).isEqualTo("SUCCEEDED");
        int before=calls.get();jdbc.update("UPDATE agent_chat_session SET connection_origin=NULL WHERE id=?",session.id());
        assertThat(agent.send(session.id(),UUID.randomUUID(),"旧版会话",true).state()).isEqualTo("FAILED");
        assertThat(calls.get()).isEqualTo(before);
    }
    @Test void apiAndSendBothRejectDemoRunCombinedWithLiveConnection() {
        var run=runs.create(com.lanhao.ops.TestFixtures.request("identity-run"));
        assertThat(run.environment).isEqualTo("DEMO");
        HttpHeaders headers=new HttpHeaders();headers.set("X-Console-Request","1");
        var response=http.withBasicAuth("operator","demo-local-only").postForEntity("/api/console/sessions",
                new HttpEntity<>(Map.of("title","混合目标","connectionId",connectionId,"runId",run.id),headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("不一致");
        var bypass=chats.create("内部旧关联",connectionId,run.id);
        assertThat(agent.send(bypass.id(),UUID.randomUUID(),"不得混合",true).state()).isEqualTo("FAILED");
        assertThat(calls.get()).isZero();verifyNoInteractions(modelGateway);
    }
    @Test void changedExecutionEnvironmentStopsBeforeObservingOrRetryingOldRun()throws Exception {
        var run=runs.create(com.lanhao.ops.TestFixtures.request("changed-execution"));
        run.environment="KUBERNETES";run.clusterApiUrl="https://retired.example.com";
        var dialect=new SqlDialect(jdbc);
        jdbc.update(dialect.sql("UPDATE agent_run SET payload=@json@ WHERE id=?"),json.writeValueAsString(run),run.id);
        var claim=runs.claim(1).getFirst();engine.step(claim);
        var stopped=runs.find(run.id).orElseThrow();
        assertThat(stopped.state).isEqualTo(com.lanhao.ops.domain.Model.State.FAILED);
        assertThat(stopped.attempts).isZero();assertThat(stopped.evidence).isNull();
    }
    @Test void realConversationRecallsOnlyItsVerifiedSourceScopeEvenWhenProcessIsDemo() {
        var input=new com.lanhao.ops.memory.MemoryStore.Input("scope-proof", "scope-proof registry failure", "暂时网络异常",
                "检查目标仓库连通性", "人工已核验相同目标的恢复结果", "scope-proof", com.lanhao.ops.memory.MemoryStore.Status.VERIFIED);
        String own=com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo");
        String other=com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"other-ns");
        var expected=memories.create("scope-own-"+UUID.randomUUID(),"MANUAL","",input,own);
        memories.create("scope-other-"+UUID.randomUUID(),"MANUAL","",input,other);
        memories.create("scope-demo-"+UUID.randomUUID(),"MANUAL","",input,"DEMO");
        memories.create("scope-draft-"+UUID.randomUUID(),"MANUAL","",new com.lanhao.ops.memory.MemoryStore.Input("scope-proof",
                "scope-proof", "假设", "未验证建议", "", "", com.lanhao.ops.memory.MemoryStore.Status.DRAFT),own);
        var session=chats.create("来源隔离",connectionId,null);
        var answer=agent.send(session.id(),UUID.randomUUID(),"scope-proof",false);
        assertThat(answer.memories()).extracting(MemoryReference::id).containsExactly(expected.id());
        assertThat(recall.find("scope-proof","UNBOUND").references()).isEmpty();
    }
    @Test void recalledExperienceCanBeMarkedOutdatedOnceAndRevalidatedWithoutPromotingDraftsAutomatically() {
        String scope=com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo");
        var entry=memories.create("feedback-"+UUID.randomUUID(),"MANUAL","",new com.lanhao.ops.memory.MemoryStore.Input(
                "feedback-proof","feedback-proof","已复核","有效旧方案","核验依据","",com.lanhao.ops.memory.MemoryStore.Status.VERIFIED),scope);
        var session=chats.create("经验有效性",connectionId,null);
        var answer=agent.send(session.id(),UUID.randomUUID(),"feedback-proof",false);
        assertThat(answer.memories()).containsExactly(entry.reference());
        HttpHeaders headers=new HttpHeaders();headers.set("X-Console-Request","1");
        var input=new MemoryController.FeedbackInput(UUID.randomUUID(),answer.id(),entry.revision(),com.lanhao.ops.memory.MemoryStore.FeedbackKind.OUTDATED,"现场版本已更新");
        String path="/api/console/memories/"+entry.id()+"/feedback";
        var result=http.withBasicAuth("operator","demo-local-only").postForEntity(path,new HttpEntity<>(input,headers),JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().path("revision").asLong()).isEqualTo(1);
        assertThat(http.withBasicAuth("operator","demo-local-only").postForEntity(path,new HttpEntity<>(input,headers),JsonNode.class).getBody()).isEqualTo(result.getBody());
        assertThat(memories.get(entry.id()).status()).isEqualTo(com.lanhao.ops.memory.MemoryStore.Status.DRAFT);
        assertThat(memories.get(entry.id()).revision()).isEqualTo(2);
        assertThat(recall.find("feedback-proof",scope).references()).isEmpty();
        assertThat(memories.feedbackForTurn(answer.id())).hasSize(1);
        var newer=memories.update(entry.id(),2,new com.lanhao.ops.memory.MemoryStore.Input(entry.title(),entry.problem(),"新版本已复核","修正后的方案","新版本实际验证完成","",com.lanhao.ops.memory.MemoryStore.Status.VERIFIED));
        assertThat(recall.find("feedback-proof",scope).references()).containsExactly(newer.reference());
        assertThatThrownBy(()->memoryController.feedback(entry.id(),new MemoryController.FeedbackInput(UUID.randomUUID(),answer.id(),1,com.lanhao.ops.memory.MemoryStore.FeedbackKind.HELPFUL,"")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(memories.get(entry.id()).status()).isEqualTo(com.lanhao.ops.memory.MemoryStore.Status.VERIFIED);
    }
    @Test void nonApplicableFeedbackIsLocalEvidenceAndCannotChangeOtherMemoryOrReplayContents() {
        String scope=com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo");
        var entry=memories.create("local-feedback-"+UUID.randomUUID(),"MANUAL","",new com.lanhao.ops.memory.MemoryStore.Input(
                "local-feedback-proof","local-feedback-proof","已核对","修复思路","验证完成","",com.lanhao.ops.memory.MemoryStore.Status.VERIFIED),scope);
        var session=chats.create("局部不适用",connectionId,null);var answer=agent.send(session.id(),UUID.randomUUID(),"local-feedback-proof",false);
        var input=new MemoryController.FeedbackInput(UUID.randomUUID(),answer.id(),entry.revision(),com.lanhao.ops.memory.MemoryStore.FeedbackKind.NOT_APPLICABLE,"本次不是相同原因");
        assertThat(memoryController.feedback(entry.id(),input).kind()).isEqualTo(com.lanhao.ops.memory.MemoryStore.FeedbackKind.NOT_APPLICABLE);
        assertThat(memories.get(entry.id()).status()).isEqualTo(com.lanhao.ops.memory.MemoryStore.Status.VERIFIED);
        assertThat(recall.find("local-feedback-proof",scope).references()).contains(entry.reference());
        assertThatThrownBy(()->memoryController.feedback(UUID.randomUUID(),input)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->memoryController.feedback(entry.id(),new MemoryController.FeedbackInput(input.requestId(),answer.id(),1,com.lanhao.ops.memory.MemoryStore.FeedbackKind.OUTDATED,"变更请求"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->memories.feedback(UUID.randomUUID(),entry.id(),1,UUID.randomUUID(),"DEMO",com.lanhao.ops.memory.MemoryStore.FeedbackKind.OUTDATED,""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(memories.get(entry.id()).revision()).isEqualTo(1);
        assertThat(memories.feedbackForTurn(answer.id())).hasSize(1);
    }
    @Test void legacyReferencesWithoutRevisionRemainReadableButCannotBeUsedToInvalidateCurrentExperience()throws Exception {
        var legacy=json.readValue("{\"id\":\""+UUID.randomUUID()+"\",\"title\":\"旧引用\",\"source\":\"RUN\"}",MemoryReference.class);
        assertThat(legacy.revision()).isZero();
        var session=chats.create("旧引用",connectionId,null);var claim=chats.begin(session.id(),UUID.randomUUID(),"旧引用",false);
        var answer=chats.finish(claim,"历史答案","规则助手",true,List.of(legacy),com.lanhao.ops.domain.DeploymentIdentity.realScope(base,"agent-demo"));
        assertThatThrownBy(()->memoryController.feedback(legacy.id(),new MemoryController.FeedbackInput(UUID.randomUUID(),answer.id(),1,com.lanhao.ops.memory.MemoryStore.FeedbackKind.OUTDATED,"")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @BeforeEach void setup()throws Exception {
        for(String table:List.of("agent_platform_catalog","agent_platform_catalog_source","agent_case_recovery","agent_learning_outbox","agent_chat_turn","agent_chat_session","agent_discovered_task","agent_discovery_source","agent_console_connection","agent_console_llm","agent_command_execution","agent_memory_outbox","agent_notification","agent_audit","agent_deployment_event","agent_run"))jdbc.update("DELETE FROM "+table);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        // OS ports can be reused; a unique gateway prefix keeps per-test memory scopes distinct.
        base="http://127.0.0.1:"+server.getAddress().getPort()+"/clusters/fixture-"+UUID.randomUUID();
        resource.set(DiscoveryGatewayTest.resource("PlatformRelease","agent-demo","uid-1",2,"Failed",2));
        server.createContext("/",exchange->{
            calls.incrementAndGet();String path=exchange.getRequestURI().getPath();
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fake-connection-key");
            String body=path.endsWith("/demo-app")?resource.get():"{\"items\":["+(path.endsWith("/platformreleases")?Objects.toString(resource.get(),""):"")+"]}";
            if(code.get()!=200)body="{\"message\":\"token=fake-connection-key\"}";
            int responseCode=code.get();
            if(path.endsWith("/pods")){
                responseCode=podCode.get();
                body=responseCode==200?"{\"items\":["+chatPodFixture()+"]}":"{\"message\":\"fake-connection-key private-upstream-detail\"}";
            }
            if(path.endsWith("/pods/live-web-1"))body=chatPodFixture();
            exchange.getResponseHeaders().set("Content-Type","application/json");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(responseCode,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        connectionId=UUID.randomUUID();settings.saveConnection(connectionId,new ConsoleSettings.ConnectionInput(0,"契约测试连接",base,"","agent-demo","fake-connection-key",false));
    }
    @AfterEach void stop(){server.stop(0);}
    private String chatPodFixture(){return "{\"metadata\":{\"name\":\"live-web-1\",\"namespace\":\"agent-demo\",\"uid\":\"live-pod-uid\"},\"status\":{\"phase\":\"Running\",\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}]}}";}
    @Test void unboundChatCanReadSafeConnectionCatalogWithoutAccessingClusters() {
        var session=chats.create("连接目录",null,null);
        var reply=agent.send(session.id(),UUID.randomUUID(),"有哪些已保存的连接？",false);
        assertThat(reply.state()).isEqualTo("SUCCEEDED");
        assertThat(reply.assistantContent()).contains("已保存的 KubeSphere 连接目录","契约测试连接",base,"agent-demo","本轮未访问任何集群")
                .doesNotContain("fake-connection-key","encryptedToken","encryptedCaCertificate");
        assertThat(calls.get()).isZero();verifyNoInteractions(modelGateway);
    }
    @Test void selectedChatHasConnectionMetadataAndOnlyReadsPodsWhenRequested() {
        var session=chats.create("连接与快照",connectionId,null);
        var metadata=agent.send(session.id(),UUID.randomUUID(),"查看连接信息",false);
        assertThat(metadata.assistantContent()).contains("Kubernetes API："+base,"命名空间：agent-demo","本轮未请求集群采证");
        assertThat(calls.get()).isZero();
        var snapshot=agent.send(session.id(),UUID.randomUUID(),"读取本轮状态",true);
        assertThat(snapshot.state()).isEqualTo("SUCCEEDED");
        assertThat(snapshot.assistantContent()).contains("本轮只读排查Graph：COMPLETED","live-web-1","Running","Ready=true").doesNotContain("fake-connection-key");
        assertThat(snapshot.includeCluster()).isTrue();assertThat(calls.get()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_command_execution",Integer.class)).isZero();
    }
    @Test void unavailableSnapshotIsAnExplicitEvidenceGapAndDoesNotAbortModelConversation() {
        settings.saveLlm(new ConsoleSettings.LlmInput(0,true,ConsoleSettings.Provider.OPENAI_COMPATIBLE,base+"/v1","test-model","fake-model-key",false,0.1,512));
        when(modelGateway.complete(any(),anyList())).thenReturn("本轮采集失败，需检查读取权限。");
        podCode.set(403);var session=chats.create("快照失败",connectionId,null);
        var reply=agent.send(session.id(),UUID.randomUUID(),"检查状态",true);
        assertThat(reply.state()).isEqualTo("SUCCEEDED");
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);verify(modelGateway).complete(any(),captor.capture());
        assertThat(captor.getValue().toString()).contains("本轮只读排查Graph：PARTIAL","PODS：HTTP_403","Kubernetes API："+base)
                .doesNotContain("fake-connection-key","private-upstream-detail","live-web-1");
        assertThat(calls.get()).isEqualTo(1);
    }
    UUID inspectionConnection(String api) {
        UUID id=UUID.randomUUID();settings.saveConnection(id,new ConsoleSettings.ConnectionInput(0,"只读工具验收",api,"","agent-demo","fixture-private-token",false));return id;
    }
    @Test void chatExecutesRealReadRequestsWithPriorAuditAndFeedsCurrentAndPreviousLogsToModelWithoutReplay()throws Exception {
        try(var fixture=new ChatInspectionFixture(inspections,json)) {
            UUID connection=inspectionConnection(fixture.base);var session=chats.create("工具排查",connection,null);UUID request=UUID.randomUUID();fixture.turn.set(request);
            settings.saveLlm(new ConsoleSettings.LlmInput(0,true,ConsoleSettings.Provider.OPENAI_COMPATIBLE,base+"/v1","test-model","fake-model-key",false,0.1,512));
            when(modelGateway.complete(any(),anyList())).thenReturn("## 证据\n\n上次退出日志包含 ECONNREFUSED，需要验证依赖连接。");
            var turn=agent.send(session.id(),request,"请检查 broken-web 的日志和事件",true);
            assertThat(turn.state()).isEqualTo("SUCCEEDED");assertThat(fixture.everyReadAudited.get()).isTrue();
            var report=inspections.find(request).orElseThrow();assertThat(report.stage()).isEqualTo("COMPLETED");
            assertThat(report.evidence()).contains("CrashLoopBackOff","exitCode","ECONNREFUSED","上次退出","BackOff")
                    .doesNotContain("fixture-private-token","unrelated event must not leak");
            assertThat(report.requests()).hasSize(9).allMatch(r->r.state().equals("SUCCEEDED"));
            var messages=org.mockito.ArgumentCaptor.forClass(List.class);verify(modelGateway).complete(any(),messages.capture());
            assertThat(messages.getValue().toString()).contains("RabbitMQ ECONNREFUSED","本轮只读排查Graph：COMPLETED");
            int reads=fixture.calls.get();assertThat(agent.send(session.id(),request,"请检查 broken-web 的日志和事件",true)).isEqualTo(turn);
            assertThat(fixture.calls.get()).isEqualTo(reads);verify(modelGateway,times(1)).complete(any(),anyList());
            var restored=new ChatInspectionStore(jdbc,json,tx);assertThat(restored.find(request)).contains(report);
            assertThat(inspectionController.get(session.id(),request).report()).isEqualTo(report);
            var endpoint="/api/console/sessions/"+session.id()+"/turns/"+request+"/inspection";
            assertThat(http.getForEntity(endpoint,String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            var view=http.withBasicAuth("operator","demo-local-only").getForEntity(endpoint,String.class);
            assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(view.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(view.getBody()).contains("ECONNREFUSED").doesNotContain("fixture-private-token");
            assertThatThrownBy(()->inspectionController.get(UUID.randomUUID(),request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_command_execution",Integer.class)).isZero();
        }
    }
    @Test void deniedEventsAndLogsHaveSpecificPermissionsAndKeepPodEvidenceWithoutLeakingDenialBody()throws Exception {
        try(var fixture=new ChatInspectionFixture(inspections,json)) {
            fixture.eventsCode=403;fixture.logsCode=403;UUID request=UUID.randomUUID();fixture.turn.set(request);
            var session=chats.create("权限排查",inspectionConnection(fixture.base),null);
            var turn=agent.send(session.id(),request,"检查故障",true);assertThat(turn.state()).isEqualTo("SUCCEEDED");
            var report=inspections.find(request).orElseThrow();assertThat(report.stage()).isEqualTo("PARTIAL");
            assertThat(report.evidence()).contains("CrashLoopBackOff","HTTP_403","pods/log 的 get","events 的 list")
                    .doesNotContain("fixture-private-token","upstream-private-denial");
            assertThat(report.requests()).anyMatch(r->r.state().equals("FAILED")&&r.result().equals("HTTP_403"));
            assertThat(fixture.everyReadAudited.get()).isTrue();
        }
    }
    @Test void modelFailureDoesNotDiscardAlreadyCollectedToolEvidenceAndReplayDoesNotReexecute()throws Exception {
        try(var fixture=new ChatInspectionFixture(inspections,json)) {
            UUID request=UUID.randomUUID();fixture.turn.set(request);var session=chats.create("模型失败保留证据",inspectionConnection(fixture.base),null);
            settings.saveLlm(new ConsoleSettings.LlmInput(0,true,ConsoleSettings.Provider.OPENAI_COMPATIBLE,base+"/v1","test-model","fake-model-key",false,0.1,512));
            when(modelGateway.complete(any(),anyList())).thenThrow(new ModelFailure(ModelFailure.Code.TIMEOUT,0));
            var failed=agent.send(session.id(),request,"查看日志",true);assertThat(failed.state()).isEqualTo("FAILED");
            assertThat(inspectionController.get(session.id(),request).report().evidence()).contains("ECONNREFUSED");
            int reads=fixture.calls.get();agent.send(session.id(),request,"查看日志",true);assertThat(fixture.calls.get()).isEqualTo(reads);
        }
    }
    @Test void replacementPodOutputIsDiscardedAndConnectionChangesStopFurtherReads()throws Exception {
        try(var fixture=new ChatInspectionFixture(inspections,json)) {
            fixture.replacePod.set(true);UUID request=UUID.randomUUID();fixture.turn.set(request);
            var session=chats.create("Pod替换",inspectionConnection(fixture.base),null);agent.send(session.id(),request,"读取日志",true);
            var report=inspections.find(request).orElseThrow();assertThat(report.stage()).isEqualTo("PARTIAL");
            assertThat(report.evidence()).contains("POD_IDENTITY_CHANGED").doesNotContain("waiting for dependency","ECONNREFUSED");
        }
        try(var fixture=new ChatInspectionFixture(inspections,json)) {
            UUID connection=inspectionConnection(fixture.base),request=UUID.randomUUID();fixture.turn.set(request);
            var session=chats.create("换址停止",connection,null);
            fixture.onLog=()->settings.saveConnection(connection,new ConsoleSettings.ConnectionInput(1,"改址",fixture.base+"/other","","agent-demo","new-explicit-key",false));
            var failed=agent.send(session.id(),request,"查看故障",true);assertThat(failed.state()).isEqualTo("FAILED");
            var report=inspectionController.get(session.id(),request).report();assertThat(report.stage()).isEqualTo("UNKNOWN");
            assertThat(report.requests()).anyMatch(r->r.state().equals("UNKNOWN"));assertThat(report.evidence()).isEmpty();
            verifyNoInteractions(modelGateway);
        }
    }
    @Test void expiredInspectionCannotWriteSuccessOrReplayInFlightRequests()throws Exception {
        try(var fixture=new ChatInspectionFixture(inspections,json)) {
            UUID request=UUID.randomUUID();fixture.turn.set(request);var session=chats.create("租约失效",inspectionConnection(fixture.base),null);
            fixture.onLog=()->jdbc.update(new SqlDialect(jdbc).sql("UPDATE agent_chat_turn SET lease_until=@now@ WHERE id=?"),request);
            assertThatThrownBy(()->agent.send(session.id(),request,"读取日志",true)).isInstanceOf(IllegalStateException.class);
            var view=inspectionController.get(session.id(),request);assertThat(view.report().stage()).isEqualTo("UNKNOWN");
            assertThat(view.report().requests()).anyMatch(r->r.state().equals("UNKNOWN"));
            int reads=fixture.calls.get();var replay=agent.send(session.id(),request,"读取日志",true);
            assertThat(replay.state()).isEqualTo("FAILED");assertThat(fixture.calls.get()).isEqualTo(reads);verifyNoInteractions(modelGateway);
        }
    }
    @Test void concurrentConversationsKeepConnectionAndRequestAuditIsolated()throws Exception {
        try(var first=new ChatInspectionFixture(inspections,json);var second=new ChatInspectionFixture(inspections,json)) {
            UUID a=inspectionConnection(first.base),b=inspectionConnection(second.base),qa=UUID.randomUUID(),qb=UUID.randomUUID();
            first.turn.set(qa);second.turn.set(qb);var sa=chats.create("独立集群A",a,null);var sb=chats.create("独立集群B",b,null);
            try(var executor=Executors.newFixedThreadPool(2)) {
                var fa=executor.submit(()->agent.send(sa.id(),qa,"查看 broken-web",true));
                var fb=executor.submit(()->agent.send(sb.id(),qb,"查看 broken-web",true));
                assertThat(fa.get(20,TimeUnit.SECONDS).state()).isEqualTo("SUCCEEDED");assertThat(fb.get(20,TimeUnit.SECONDS).state()).isEqualTo("SUCCEEDED");
            }
            assertThat(inspections.find(qa).orElseThrow().plan().connectionId()).isEqualTo(a);
            assertThat(inspections.find(qb).orElseThrow().plan().connectionId()).isEqualTo(b);
            assertThat(inspections.find(qa).orElseThrow().plan().origin()).isNotEqualTo(inspections.find(qb).orElseThrow().plan().origin());
            assertThat(first.everyReadAudited.get()).isTrue();assertThat(second.everyReadAudited.get()).isTrue();
        }
    }
    @Test void classifiedModelFailuresPersistAndReplayWithoutCallingModelAgain() {
        settings.saveLlm(new ConsoleSettings.LlmInput(0,true,ConsoleSettings.Provider.OPENAI_COMPATIBLE,base+"/v1","test-model","fake-model-key",false,0.1,512));
        when(modelGateway.complete(any(),anyList())).thenThrow(new ModelFailure(ModelFailure.Code.TIMEOUT,0)).thenReturn("恢复后的合成回复");
        var session=chats.create("失败恢复",connectionId,null);UUID request=UUID.randomUUID();
        var failed=agent.send(session.id(),request,"first-failed-question",false);
        assertThat(failed.state()).isEqualTo("FAILED");assertThat(failed.assistantContent()).contains("LLM_TIMEOUT");
        assertThat(agent.send(session.id(),request,"first-failed-question",false)).isEqualTo(failed);
        var recovered=agent.send(session.id(),UUID.randomUUID(),"手动再提问",false);
        assertThat(recovered.state()).isEqualTo("SUCCEEDED");
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);verify(modelGateway,times(2)).complete(any(),captor.capture());
        assertThat(captor.getAllValues().getLast().toString()).doesNotContain("first-failed-question");
        var restored=new ChatStore(jdbc,tx,json);
        assertThat(restored.turns(session.id())).extracting(ChatStore.Turn::state).containsExactly("FAILED","SUCCEEDED");
        assertThat(calls.get()).isZero();
    }
    @Test void staleDiscoveredIdentityCannotTriggerLiveCollection() {
        var task=discover();var session=chats.create("旧目标",connectionId,null,task.id());int before=calls.get();
        settings.saveConnection(connectionId,new ConsoleSettings.ConnectionInput(1,"changed",base+"/other","","agent-demo","explicit-new-key",false));
        var failed=agent.send(session.id(),UUID.randomUUID(),"检查原任务",true);
        assertThat(failed.state()).isEqualTo("FAILED");assertThat(failed.assistantContent()).contains("目标或监控范围已改变");
        assertThat(calls.get()).isEqualTo(before);verifyNoInteractions(modelGateway);
    }
    DiscoveryStore.Task discover(){store.configure(connectionId,true,0);service.scan(connectionId,true);return store.list(null,null,0).items().getFirst();}
    @Test void discoversAndDeduplicatesWithoutStartingAgentOrRecoveryAndPreservesGenerations() {
        var task=discover();assertThat(task.state()).isEqualTo(DiscoveryStore.State.PENDING);assertThat(task.report()).isNull();
        assertThat(calls.get()).isEqualTo(4);assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
        service.processNext();assertThat(calls.get()).isEqualTo(4);verifyNoInteractions(commandPlanner);
        service.scan(connectionId,true);assertThat(store.list(null,null,0).total()).isEqualTo(1);
        resource.set(DiscoveryGatewayTest.resource("PlatformRelease","agent-demo","uid-1",3,"Pending",2));service.scan(connectionId,true);
        assertThat(store.list(null,null,0).total()).isEqualTo(2);assertThat(store.task(task.id()).presence()).isEqualTo("SUPERSEDED");
        assertThatThrownBy(()->store.select(task.id(),task.revision(),UUID.randomUUID(),"operator")).isInstanceOf(ConsoleFailure.class);
        resource.set(null);service.scan(connectionId,true);
        assertThat(store.list(null,null,0).items()).allMatch(t->!t.presence().equals("PRESENT"));
    }
    @Test void explicitSelectionIsAuthenticatedIdempotentAndProducesReadOnlyGraphPlan() {
        var task=discover();var request=Map.of("revision",task.revision(),"requestId",UUID.randomUUID());String url="/api/console/discovery/tasks/"+task.id()+"/select";
        HttpHeaders anonymousHeaders=new HttpHeaders();anonymousHeaders.set("X-Console-Request","1");
        assertThat(http.postForEntity(url,new HttpEntity<>(request,anonymousHeaders),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var auth=http.withBasicAuth("operator","demo-local-only");
        assertThat(auth.postForEntity(url,request,String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders headers=new HttpHeaders();headers.set("X-Console-Request","1");headers.setOrigin("https://evil.example");
        assertThat(auth.postForEntity(url,new HttpEntity<>(request,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        headers.remove("Origin");
        assertThat(auth.postForEntity(url,new HttpEntity<>(request,headers),JsonNode.class).getBody().path("state").asText()).isEqualTo("QUEUED");
        assertThat(auth.postForEntity(url,new HttpEntity<>(request,headers),JsonNode.class).getBody().path("state").asText()).isEqualTo("QUEUED");
        service.processNext();var result=store.task(task.id());
        assertThat(result.state()).isEqualTo(DiscoveryStore.State.DONE);assertThat(result.report().plan().automatic()).isFalse();
        assertThat(result.report().plan().diagnosis().commandExecutions()).isEmpty();verifyNoInteractions(commandPlanner);
        assertThat(result.selectedBy()).isEqualTo("operator");assertThat(calls.get()).isGreaterThan(6);int completedCalls=calls.get();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_command_execution",Integer.class)).isZero();
        assertThat(auth.postForEntity(url,new HttpEntity<>(request,headers),JsonNode.class).getBody().path("state").asText()).isEqualTo("DONE");
        service.processNext();assertThat(calls.get()).isEqualTo(completedCalls);
    }
    @Test void changedConnectionOrPausedSourceCannotAcceptLateScanAndErrorsDoNotInventDeletion() {
        var task=discover();code.set(403);service.scan(connectionId,true);
        assertThat(store.task(task.id()).presence()).isEqualTo("PRESENT");assertThat(store.source(connectionId).error()).contains("HTTP 403").doesNotContain("fake-connection-key");
        code.set(200);var claim=store.claimScan(connectionId,true);assertThat(store.claimScan(connectionId,true)).isNull();
        store.configure(connectionId,false,1);store.scanned(claim,List.of(),"");assertThat(store.task(task.id()).presence()).isEqualTo("PRESENT");
        settings.saveConnection(connectionId,new ConsoleSettings.ConnectionInput(1,"changed",base+"/other","","agent-demo","explicit-new-key",false));
        assertThatThrownBy(()->store.select(task.id(),task.revision(),UUID.randomUUID(),"operator")).isInstanceOf(ConsoleFailure.class);
    }
    @Test void ignoresPersistThroughSyncAndPagesAreFilterable() {
        var task=discover();store.ignore(task.id(),task.revision(),true);service.scan(connectionId,true);
        assertThat(store.task(task.id()).state()).isEqualTo(DiscoveryStore.State.IGNORED);
        assertThatThrownBy(()->store.select(task.id(),1,UUID.randomUUID(),"operator")).isInstanceOf(IllegalStateException.class);
        store.ignore(task.id(),1,false);assertThat(store.task(task.id()).state()).isEqualTo(DiscoveryStore.State.PENDING);
        var claim=store.claimScan(connectionId,true);List<DiscoveryGateway.Observation> observations=new ArrayList<>();
        for(int i=0;i<35;i++)observations.add(new DiscoveryGateway.Observation(new Target(Kind.ProjectRuntime,"agent-demo","task-"+i,"uid-"+i,1),"","123","prod","Pending",0,"",""));
        store.scanned(claim,observations,"");
        assertThat(store.list(connectionId,DiscoveryStore.State.PENDING,0).items()).hasSize(30);
        assertThat(store.list(connectionId,DiscoveryStore.State.PENDING,30).items()).hasSize(6);
        assertThat(store.list(UUID.randomUUID(),null,0).total()).isZero();assertThat(store.list(null,DiscoveryStore.State.IGNORED,0).total()).isZero();
    }
    @Test void concurrentWorkersHaveOneOwnerAndExpiredResultsNeverOverwriteManualRetry()throws Exception {
        var task=discover();store.select(task.id(),0,UUID.randomUUID(),"operator");
        var works=new ArrayList<DiscoveryStore.Work>();
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return store.claimWork();});var b=pool.submit(()->{gate.await();return store.claimWork();});gate.countDown();
            var left=a.get(10,TimeUnit.SECONDS);var right=b.get(10,TimeUnit.SECONDS);if(left!=null)works.add(left);if(right!=null)works.add(right);
        }
        assertThat(works).hasSize(1);var old=works.getFirst();
        jdbc.update(new SqlDialect(jdbc).sql("UPDATE agent_discovered_task SET lease_until=@now@ WHERE id=?"),task.id());
        assertThat(store.claimWork()).isNull();assertThat(store.task(task.id()).state()).isEqualTo(DiscoveryStore.State.FAILED);
        var failed=store.task(task.id());store.select(task.id(),failed.revision(),UUID.randomUUID(),"operator");
        store.finish(old,null,"late failure");assertThat(store.task(task.id()).state()).isEqualTo(DiscoveryStore.State.QUEUED);
    }
    @Test void replacedResourceFailsBeforeGraphAndCanBeRetriedOnlyExplicitly() {
        var task=discover();store.select(task.id(),0,UUID.randomUUID(),"operator");
        resource.set(DiscoveryGatewayTest.resource("PlatformRelease","agent-demo","new-uid",2,"Failed",2));service.processNext();
        assertThat(store.task(task.id()).state()).isEqualTo(DiscoveryStore.State.FAILED);assertThat(store.task(task.id()).report()).isNull();
        int before=calls.get();service.processNext();assertThat(calls.get()).isEqualTo(before);verifyNoInteractions(commandPlanner);
    }
    @Test void sameResourceIdentityOnDifferentConnectionsStaysSeparateAndStaleReportIsRejected() {
        var task=discover();UUID other=UUID.randomUUID();
        settings.saveConnection(other,new ConsoleSettings.ConnectionInput(0,"另一个连接",base+"/proxy","","agent-demo","other-key",false));
        store.configure(other,true,0);store.scanned(store.claimScan(other,true),List.of(task.observation()),"");
        assertThat(store.list(null,null,0).total()).isEqualTo(2);
        assertThat(store.list(other,null,0).items().getFirst().id()).isNotEqualTo(task.id());
        store.select(task.id(),0,UUID.randomUUID(),"operator");var work=store.claimWork();
        resource.set(DiscoveryGatewayTest.resource("PlatformRelease","agent-demo","uid-1",3,"Pending",2));service.scan(connectionId,true);
        var plan=new Plan(UUID.randomUUID().toString(),Action.MANUAL_REPAIR,com.lanhao.ops.diagnosis.RunbookNarrator.fallback(Action.MANUAL_REPAIR,"test"),false,Instant.now());
        store.finish(work,new DiscoveryStore.Report(Instant.now(),task.observation(),plan),"");
        assertThat(store.task(task.id()).state()).isEqualTo(DiscoveryStore.State.FAILED);
        assertThat(store.task(task.id()).report()).isNull();assertThat(store.task(task.id()).error()).contains("目标已变化");
    }
    @Test void persistsDiscoveryAndChatContextAndRejectsCrossConnectionAssociation() {
        var task=discover();var restored=new DiscoveryStore(jdbc,tx,json,settings);
        assertThat(restored.task(task.id()).observation()).isEqualTo(task.observation());assertThat(restored.source(connectionId).enabled()).isTrue();
        var session=chats.create("发现任务排查",connectionId,null,task.id());
        var turn=agent.send(session.id(),UUID.randomUUID(),"请分析部署",false);
        assertThat(turn.assistantContent()).contains("自动发现的部署","demo-app","REGISTRY_TIMEOUT").doesNotContain("never-persist-spec","never-persist-token");
        assertThat(chats.session(session.id()).discoveryId()).isEqualTo(task.id());
        HttpHeaders headers=new HttpHeaders();headers.set("X-Console-Request","1");
        assertThat(http.withBasicAuth("operator","demo-local-only").postForEntity("/api/console/sessions",new HttpEntity<>(Map.of("title","wrong scope","discoveryId",task.id()),headers),String.class).getStatusCode().isError()).isTrue();
        settings.deleteConnection(connectionId,1);
        assertThat(store.task(task.id()).connectionId()).isNull();assertThat(store.sources()).isEmpty();
        assertThatThrownBy(()->store.select(task.id(),0,UUID.randomUUID(),"operator")).isInstanceOf(ConsoleFailure.class);
        var failed=agent.send(session.id(),UUID.randomUUID(),"再分析",false);assertThat(failed.state()).isEqualTo("FAILED");
    }
}
