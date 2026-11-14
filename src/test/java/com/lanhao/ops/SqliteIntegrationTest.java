package com.lanhao.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.console.*;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.memory.*;
import com.lanhao.ops.command.*;
import com.lanhao.ops.command.CommandModel.*;
import com.lanhao.ops.persistence.*;
import com.lanhao.ops.workflow.*;
import com.lanhao.ops.notification.NotificationDispatcher;
import com.lanhao.ops.evidence.Redactor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("sqlite")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "agent.kubectl.enabled=true","agent.memory.capture-interval=3600000","agent.console.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class SqliteIntegrationTest {
    static final Path database=database();
    private static Path database(){try{return Files.createTempDirectory("agent-sqlite-test-").resolve("agent.db");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("agent.sqlite.file",()->database.toString());}
    @Autowired JdbcTemplate jdbc;
    @Autowired RunStore runs;
    @Autowired ChatStore chats;
    @Autowired ConsoleSettings settings;
    @Autowired MemoryStore memories;
    @Autowired MemoryRecall recall;
    @Autowired MemoryCapture capture;
    @Autowired AgentChat agent;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;
    @Autowired com.lanhao.ops.diagnosis.DiagnosisGraph graph;
    @MockitoBean RunScheduler scheduler;
    @MockitoBean DeploymentInbox backgroundInbox;
    @MockitoBean CommandPlanner commandPlanner;
    @MockitoBean LearningOutbox backgroundLearning;
    @Autowired CommandAudit commandAudit;
    @BeforeEach void clear(){
        for(String table:List.of("agent_learning_outbox","agent_command_execution","agent_memory","agent_memory_outbox","agent_chat_turn","agent_chat_session","agent_console_connection","agent_console_llm", "agent_notification","agent_audit","agent_deployment_event","agent_run"))jdbc.update("DELETE FROM "+table);
    }
    MemoryStore.Input entry(String title,MemoryStore.Status status){return new MemoryStore.Input(title,"镜像拉取失败 ImagePullBackOff","镜像仓库暂时超时","检查镜像地址和仓库网络，再按受控手册重试","目标版本和健康检查已连续通过","镜像 网络",status);}
    @Test void startsWithoutPostgresAndPersistsEncryptedConnectionsAndSettingsAcrossRepositoryRecreation()throws Exception {
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("PRAGMA journal_mode",String.class)).isEqualToIgnoringCase("wal");
        UUID id=UUID.randomUUID();
        settings.saveConnection(id,new ConsoleSettings.ConnectionInput(0,"开发 KubeSphere","https://api.example.com","https://console.example.com","default","fake-ks-token",false));
        settings.saveLlm(new ConsoleSettings.LlmInput(0,false,ConsoleSettings.Provider.DASHSCOPE,"https://dashscope.aliyuncs.com","qwen-plus","fake-key",false,0.1,512));
        assertThat(jdbc.queryForObject("SELECT payload FROM agent_console_connection",String.class)).doesNotContain("fake-ks-token");
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        var restored=new ConsoleSettings(jdbc,tx,json,new SecretCipher(new ConsoleProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",Path.of("unused"),false,4)),new ConsoleProperties(null,Path.of("unused"),false,4));
        assertThat(restored.connectionToken(restored.connection(id))).isEqualTo("fake-ks-token");
        assertThat(restored.llmView().hasApiKey()).isTrue();
        assertThat(http.getForEntity("/api/console/bootstrap",String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.withBasicAuth("operator","demo-local-only").getForEntity("/api/console/bootstrap",String.class).getBody()).contains("开发 KubeSphere").doesNotContain("fake-ks-token","fake-key");
    }
    @Test void caPersistsEncryptedWithoutEchoAndRetainsClearAndLegacyBehavior()throws Exception {
        String ca;
        try(var input=getClass().getResourceAsStream("/tls/ca.pem")){ca=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII);}
        UUID id=UUID.randomUUID();
        var saved=settings.saveConnection(id,new ConsoleSettings.ConnectionInput(0,"导入连接","https://api.example.com","","platform-system","fake-import-token",false,ca,false));
        assertThat(saved.hasCaCertificate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT payload FROM agent_console_connection",String.class)).doesNotContain("BEGIN CERTIFICATE","fake-import-token");
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        var restored=new ConsoleSettings(jdbc,tx,json,new SecretCipher(new ConsoleProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",Path.of("unused"),false,4)),new ConsoleProperties(null,Path.of("unused"),false,4));
        assertThat(restored.connectionCaCertificate(restored.connection(id))).isEqualTo(ca.trim());
        String view=http.withBasicAuth("operator","demo-local-only").getForObject("/api/console/connections",String.class);
        assertThat(view).contains("\"hasCaCertificate\":true").doesNotContain("BEGIN CERTIFICATE","encryptedCaCertificate","fake-import-token");
        settings.saveConnection(id,new ConsoleSettings.ConnectionInput(1,saved.name(),saved.apiUrl(),"",saved.namespace(),"",false));
        assertThat(settings.connectionCaCertificate(settings.connection(id))).isEqualTo(ca.trim());
        assertThatThrownBy(()->settings.saveConnection(id,new ConsoleSettings.ConnectionInput(2,saved.name(),"https://other.example.com","",saved.namespace(),"new-fake-token",false))).isInstanceOf(IllegalArgumentException.class);
        settings.saveConnection(id,new ConsoleSettings.ConnectionInput(2,saved.name(),saved.apiUrl(),"",saved.namespace(),"",false,"",true));
        assertThat(settings.connections().getFirst().hasCaCertificate()).isFalse();
        jdbc.update("UPDATE agent_console_connection SET payload=json_remove(payload,'$.encryptedCaCertificate')");
        assertThat(settings.connectionCaCertificate(settings.connection(id))).isEmpty();
    }
    @Test void concurrentClaimsAndExpiredOwnersPreserveLeaseAndVersionBudget()throws Exception {
        var request=TestFixtures.request("sqlite-claim");Run run=runs.create(new StartRequest(request.requestKey(),request.provisioningId(),request.targets(),"v1","app"));
        assertThat(runs.create(new StartRequest(request.requestKey(),request.provisioningId(),request.targets(),"v1","app")).id).isEqualTo(run.id);
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return runs.claim(1);});var b=pool.submit(()->{gate.await();return runs.claim(1);});gate.countDown();
            var claims=new ArrayList<RunStore.Claim>();claims.addAll(a.get(10,TimeUnit.SECONDS));claims.addAll(b.get(10,TimeUnit.SECONDS));
            assertThat(claims).hasSize(1);var old=claims.getFirst();
            jdbc.update("UPDATE agent_run SET lease_until=0 WHERE id=?",run.id.toString());var next=runs.claim(1).getFirst();
            assertThatThrownBy(()->runs.save(old,run,Instant.now())).isInstanceOf(IllegalStateException.class);
            run.state=State.CANCELLED;runs.save(next,run,Instant.now());
        }
        var replay=runs.create(new StartRequest("new-key",request.provisioningId(),request.targets(),"v1","app"));
        assertThat(replay.id).isEqualTo(run.id);
        assertThat(replay.state).isEqualTo(State.CANCELLED);
    }
    @Test void chatClaimsAreIdempotentAndLateResponseCannotOverwriteNewTurn() {
        var session=chats.create("排查",null,null);UUID id=UUID.randomUUID();
        var old=chats.begin(session.id(),id,"问题",false);
        assertThatThrownBy(()->chats.begin(session.id(),UUID.randomUUID(),"并发",false)).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE agent_chat_turn SET lease_until=0 WHERE id=?",id.toString());
        var next=chats.begin(session.id(),UUID.randomUUID(),"后续问题",false);
        assertThatThrownBy(()->chats.finish(old,"迟到","模型",true)).isInstanceOf(IllegalStateException.class);
        chats.finish(next,"有效","模型",true);
        assertThat(chats.begin(session.id(),next.turn().id(),"后续问题",false).replay()).isTrue();
        assertThat(chats.turns(session.id())).extracting(ChatStore.Turn::state).containsExactly("FAILED","SUCCEEDED");
    }
    @Test void ftsFindsChineseAndTechnicalTermsAndUpdatesArchiveAtomically() {
        var saved=memories.create("manual:1","MANUAL","",entry("镜像仓库故障",MemoryStore.Status.VERIFIED));
        assertThat(memories.search("镜像拉取",null,10)).extracting(MemoryStore.Entry::id).containsExactly(saved.id());
        assertThat(memories.search("ImagePullBackOff",null,10)).hasSize(1);
        assertThat(memories.search("\" OR * --",null,10)).isEmpty();
        var changed=new MemoryStore.Input("数据库连接耗尽","连接池耗尽","连接未释放","关闭连接泄漏","指标已恢复","数据库",MemoryStore.Status.VERIFIED);
        memories.update(saved.id(),saved.revision(),changed);
        assertThat(memories.search("镜像拉取",null,10)).isEmpty();assertThat(memories.search("连接池",null,10)).hasSize(1);
        var archived=new MemoryStore.Input(changed.title(),changed.problem(),changed.cause(),changed.solution(),changed.verification(),changed.tags(),MemoryStore.Status.ARCHIVED);
        memories.update(saved.id(),2,archived);
        assertThat(memories.search("连接池",null,10)).isEmpty();
        assertThat(memories.create("manual:1","MANUAL","",entry("镜像仓库",MemoryStore.Status.VERIFIED)).status()).isEqualTo(MemoryStore.Status.ARCHIVED);
        assertThatThrownBy(()->memories.update(saved.id(),1,changed)).isInstanceOf(IllegalStateException.class);
    }
    @Test void recallUsesOnlyVerifiedSameEnvironmentAndPersistsCitationsInChatAndGraph() {
        memories.create("draft","CHAT","",entry("镜像暂时故障",MemoryStore.Status.DRAFT));
        assertThat(recall.find("镜像拉取").references()).isEmpty();
        var known=memories.create("verified","MANUAL","",entry("镜像拉取故障",MemoryStore.Status.VERIFIED));
        var unbound=chats.create("尚未选择环境",null,null);
        assertThat(agent.send(unbound.id(),UUID.randomUUID(),"镜像拉取失败怎么排查？",false).memories()).isEmpty();
        var run=runs.create(TestFixtures.request("memory-demo-scope"));
        var session=chats.create("镜像问题",null,run.id);var answer=agent.send(session.id(),UUID.randomUUID(),"镜像拉取失败怎么排查？",false);
        assertThat(answer.memories()).extracting(MemoryReference::id).containsExactly(known.id());
        assertThat(chats.turns(session.id()).getFirst().assistantContent()).contains("历史排障记忆","受控手册");
        var plan=graph.diagnose(new Evidence(Instant.now(),false,true,"auth","镜像拉取认证失败",List.of("ImagePullBackOff unauthorized")),"DEMO");
        assertThat(plan.action()).isEqualTo(Action.MANUAL_REPAIR);
        assertThat(plan.diagnosis().memories()).extracting(MemoryReference::id).containsExactly(known.id());
        jdbc.update("UPDATE agent_memory SET environment='KUBERNETES'");assertThat(recall.find("镜像拉取").references()).isEmpty();
    }
    @Test void successfulRecoveryOutboxSurvivesRepeatCaptureAndDraftsAreNotAutomaticallyTrusted() {
        Run run=runs.create(TestFixtures.request("learn-success"));var claim=runs.claim(1).getFirst();
        run.attempts=1;run.stableCount=3;run.state=State.SUCCEEDED;run.actionKey="accepted-key";run.recovery=new Recovery("receipt-1","SUCCEEDED");
        run.plan=new Plan("plan",Action.RETRY_TRANSIENT,new Diagnosis("镜像仓库超时",List.of("检查网络".repeat(1200)),List.of("受控重试"),"健康连续通过".repeat(900),"规则"),true,Instant.now());
        runs.save(claim,run,Instant.now());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory_outbox WHERE processed_at IS NULL",Integer.class)).isEqualTo(1);
        capture.capture();capture.capture();
        var saved=memories.search("仓库超时",MemoryStore.Status.VERIFIED,10);
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().solution()).hasSize(4000);
        assertThat(saved.getFirst().verification()).hasSize(4000).startsWith("任务已通过受控恢复验收");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory_outbox WHERE processed_at IS NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_audit",Integer.class)).isGreaterThan(0);
    }
    @Test void failedCaptureBacksOffWithoutBlockingOtherMemories() throws Exception {
        Run broken=runs.create(TestFixtures.request("broken-memory"));var claim=runs.claim(1).getFirst();
        broken.state=State.SUCCEEDED;broken.attempts=1;runs.save(claim,broken,Instant.now());
        jdbc.update("INSERT INTO agent_memory_outbox(run_id,payload,environment) VALUES(?,?,'DEMO')",broken.id.toString(),"\"invalid-run\"");
        Run good=runs.create(TestFixtures.request("good-memory"));claim=runs.claim(1).getFirst();
        good.state=State.SUCCEEDED;good.attempts=1;good.stableCount=3;good.actionKey="accepted-key";good.recovery=new Recovery("receipt-2","SUCCEEDED");
        good.plan=new Plan("plan",Action.RETRY_TRANSIENT,new Diagnosis("镜像超时",List.of("查网络"),List.of("重试"),"Ready","规则"),true,Instant.now());
        runs.save(claim,good,Instant.now());
        capture.capture();capture.capture();
        assertThat(jdbc.queryForObject("SELECT attempts FROM agent_memory_outbox WHERE run_id=?",Integer.class,broken.id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT due_at FROM agent_memory_outbox WHERE run_id=?",Long.class,broken.id.toString())).isGreaterThan(System.currentTimeMillis());
        assertThat(jdbc.queryForObject("SELECT processed_at FROM agent_memory_outbox WHERE run_id=?",Long.class,good.id.toString())).isNotNull();
        assertThat(memories.search("镜像超时",MemoryStore.Status.VERIFIED,10)).hasSize(1);
    }
    @Test void memoryApiRequiresAuthorizationAndTracksChatSourceRevisionAndVerification() {
        String path="/api/console/memories";var client=http.withBasicAuth("operator","demo-local-only");
        var session=chats.create("镜像",null,null);var turn=agent.send(session.id(),UUID.randomUUID(),"镜像拉取失败",false);
        UUID request=UUID.randomUUID();
        var input=new MemoryController.Input(request,0,"镜像问题","拉取失败","待核实","检查镜像地址","","镜像",MemoryStore.Status.DRAFT,turn.id());
        var headers=new HttpHeaders();headers.set("X-Console-Request","1");
        assertThat(http.postForEntity(path,new HttpEntity<>(input,headers),String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(client.postForEntity(path,input,String.class).getStatusCode().is4xxClientError()).isTrue();
        var created=client.postForEntity(path,new HttpEntity<>(input,headers),MemoryStore.Entry.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();var saved=created.getBody();
        assertThat(saved.sourceType()).isEqualTo("CHAT");assertThat(saved.sourceRef()).isEqualTo(turn.id().toString());
        assertThat(client.postForEntity(path,new HttpEntity<>(input,headers),MemoryStore.Entry.class).getBody().id()).isEqualTo(saved.id());
        var invalid=new MemoryController.Input(request,1,input.title(),input.problem(),input.cause(),input.solution(),"",input.tags(),MemoryStore.Status.VERIFIED,null);
        assertThat(client.exchange(path+"/"+saved.id(),HttpMethod.PUT,new HttpEntity<>(invalid,headers),String.class).getStatusCode().value()).isEqualTo(400);
        var valid=new MemoryController.Input(request,1,input.title(),input.problem(),input.cause(),input.solution(),"相同版本连续健康",input.tags(),MemoryStore.Status.VERIFIED,null);
        var updated=client.exchange(path+"/"+saved.id(),HttpMethod.PUT,new HttpEntity<>(valid,headers),MemoryStore.Entry.class).getBody();
        assertThat(updated.revision()).isEqualTo(2);assertThat(updated.sourceType()).isEqualTo("CHAT");
        assertThat(client.exchange(path+"/"+saved.id(),HttpMethod.PUT,new HttpEntity<>(valid,headers),String.class).getStatusCode().value()).isEqualTo(409);
        assertThat(client.getForEntity(path+"?q=镜像",String.class).getBody()).contains("SQLITE","FTS5",saved.id().toString());
    }
    @Test void sqliteNotificationsRetrySignedIdentityAndStopAfterSuccess() throws Exception {
        Run run=runs.create(TestFixtures.request("sqlite-notify"));var claim=runs.claim(1).getFirst();
        run.transition(State.MANUAL_REQUIRED,"需要人工修复",Instant.now());runs.save(claim,run,Instant.now());
        var calls=new java.util.concurrent.atomic.AtomicInteger();var ids=new ArrayList<String>();
        var valid=new java.util.concurrent.atomic.AtomicBoolean(true);String secret="sqlite-notification-test-signing-material";
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/notify",exchange->{
            String payload=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            valid.compareAndSet(true,NotificationDispatcher.sign(secret,exchange.getRequestHeaders().getFirst("X-Agent-Timestamp"),payload).equals(exchange.getRequestHeaders().getFirst("X-Agent-Signature")));
            ids.add(exchange.getRequestHeaders().getFirst("X-Agent-Event-Id"));
            exchange.sendResponseHeaders(calls.incrementAndGet()==1?503:204,-1);exchange.close();
        });server.start();
        try {
            var base=TestFixtures.ops();var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
            var ops=new OpsProperties(4,base.stepTimeout(),base.collectionTimeout(),false,Map.of(),new OpsProperties.Notification(true,java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/notify"),secret,3));
            var dispatcher=new NotificationDispatcher(jdbc,tx,ops);dispatcher.dispatch();
            assertThat(jdbc.queryForObject("SELECT state FROM agent_notification",String.class)).isEqualTo("PENDING");
            jdbc.update("UPDATE agent_notification SET due_at=0");dispatcher.dispatch();dispatcher.dispatch();
            assertThat(jdbc.queryForObject("SELECT state FROM agent_notification",String.class)).isEqualTo("SENT");
            assertThat(ids).hasSize(2);assertThat(ids.getFirst()).isEqualTo(ids.getLast());assertThat(valid).isTrue();
        }finally{server.stop(0);}
    }
    @Test void eventsStillDeduplicateAndWaitBehindAnActiveDeployment() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        var inbox=new DeploymentInbox(jdbc,tx,json);Run old=runs.create(TestFixtures.request("old"));
        inbox.ingest(new DeploymentEvent("event1",old.provisioningId,"v2","app"));inbox.dispatch();
        assertThat(inbox.find("event1").runId()).isNull();runs.cancel(old.id,"operator");
        jdbc.update("UPDATE agent_deployment_event SET due_at=0");inbox.dispatch();
        assertThat(runs.find(inbox.find("event1").runId()).orElseThrow().state).isEqualTo(State.DISCOVERING);
        assertThat(inbox.ingest(new DeploymentEvent("event2",old.provisioningId,"v2","app")).eventId()).isEqualTo("event1");
    }
    Run commandRun(String key) {
        Run run=runs.create(TestFixtures.request(key));var claim=runs.claim(1).getFirst();run.state=State.DIAGNOSING;
        run.evidence=TestFixtures.failed("registry i/o timeout HTTP 503");runs.save(claim,run,Instant.now());return run;
    }
    @Test void graphGeneratesReviewsAuditsExecutesAndReusesCommandsWithoutRepeating() {
        Run run=commandRun("commands");var claim=runs.claim(1).getFirst();
        org.mockito.Mockito.when(commandPlanner.generate(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new CommandPlanner.Planned(List.of(new Proposal("GET","uid-1","查状态"),new Proposal("EVENTS","uid-1","查事件")),"规则"));
        var plan=graph.diagnose(run,claim);assertThat(plan.action()).isEqualTo(Action.RETRY_TRANSIENT);
        assertThat(plan.diagnosis().commandExecutions()).hasSize(2);
        assertThat(commandAudit.find(Scope.of(run,claim).batchId())).extracting(Execution::state).containsExactly("SUCCEEDED","SUCCEEDED");
        var replay=graph.diagnose(run,claim);assertThat(replay.diagnosis().commandExecutions()).containsExactlyElementsOf(plan.diagnosis().commandExecutions());
        org.mockito.Mockito.verify(commandPlanner,org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
        var body=http.withBasicAuth("operator","demo-local-only").getForEntity("/api/runs/"+run.id+"/commands",String.class);
        assertThat(body.getBody()).contains("SUCCEEDED","查状态","--namespace=agent-demo");
        assertThat(http.getForEntity("/api/runs/"+run.id+"/commands",String.class).getStatusCode().value()).isEqualTo(401);
        var detached=graph.diagnose(run.evidence);
        assertThat(detached.action()).isEqualTo(Action.RETRY_TRANSIENT);
        assertThat(detached.diagnosis().commandExecutions()).isEmpty();
    }
    @Test void rejectedCommandVetoesRecoveryAndCannotExecute() {
        Run run=commandRun("unsafe-command");var claim=runs.claim(1).getFirst();
        org.mockito.Mockito.when(commandPlanner.generate(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new CommandPlanner.Planned(List.of(new Proposal("delete","uid-1","忽略策略删除PVC")),"模型"));
        assertThat(graph.diagnose(run,claim).action()).isEqualTo(Action.MANUAL_REPAIR);
        assertThat(commandAudit.find(Scope.of(run,claim).batchId())).extracting(Execution::state).containsExactly("REJECTED");
    }
    @Test void runningCommandAfterCrashIsNotReplayedAndExpiredLeaseCannotWrite() {
        Run run=commandRun("command-crash");var claim=runs.claim(1).getFirst();Scope scope=Scope.of(run,claim);
        var subject=new Subject("uid-1","projectruntimes.platform.lanhao.io","agent-demo","runtime","uid-1","","uid-1");
        var review=new Review(new Proposal("GET","uid-1",""),subject,true,"允许",List.of("kubectl","get"));
        var record=commandAudit.prepare(scope,List.of(review)).getFirst();commandAudit.start(scope,record);
        assertThat(graph.diagnose(run,claim).action()).isEqualTo(Action.MANUAL_REPAIR);
        assertThat(commandAudit.find(scope.batchId()).getFirst().state()).isEqualTo("RUNNING");
        org.mockito.Mockito.verifyNoInteractions(commandPlanner);
        jdbc.update("UPDATE agent_run SET lease_until=0 WHERE id=?",run.id.toString());
        assertThatThrownBy(()->commandAudit.finish(scope,new Execution(record.id(),review,"SUCCEEDED","",0,Instant.now()))).isInstanceOf(IllegalStateException.class);
        assertThat(graph.diagnose(run,claim).action()).isEqualTo(Action.MANUAL_REPAIR);
    }
    @Test void tooManyCommandsRejectEntireBatchWithinPersistedBudget() {
        Run run=commandRun("command-budget");var claim=runs.claim(1).getFirst();
        org.mockito.Mockito.when(commandPlanner.generate(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new CommandPlanner.Planned(java.util.Collections.nCopies(8,new Proposal("GET","uid-1","")),"模型"));
        assertThat(graph.diagnose(run,claim).action()).isEqualTo(Action.MANUAL_REPAIR);
        var records=commandAudit.find(Scope.of(run,claim).batchId());assertThat(records).hasSize(3);
        assertThat(records).allMatch(r->r.state().equals("REJECTED"));
    }
}
