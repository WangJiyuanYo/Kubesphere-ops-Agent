package com.lanhao.ops;

import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.persistence.*;
import com.lanhao.ops.notification.NotificationDispatcher;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class OperationsPersistenceTest {
    private JdbcTemplate jdbc;
    private JdbcRunStore store;
    private TransactionTemplate tx;
    private DeploymentInbox inbox;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
                System.getenv().getOrDefault("TEST_DATABASE_USER", "deployment_agent"),
                System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "demo-postgres-only"));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source); jdbc.execute("TRUNCATE agent_run,agent_deployment_event CASCADE");
        tx = new TransactionTemplate(new JdbcTransactionManager(source));
        store = new JdbcRunStore(jdbc, TestFixtures.json(), tx, TestFixtures.config(true));
        inbox = new DeploymentInbox(jdbc, tx, TestFixtures.json());
    }
    private DeploymentEvent event(String id, String version) { return new DeploymentEvent(id, "9007199254740993", version, "app"); }
    @Test void repeatedDeploymentEventsShareOneRunAndOneBudgetAcrossTerminalState() {
        var receipt = inbox.ingest(event("event-1", "v1"));
        assertThat(receipt.runId()).isNull();
        assertThat(inbox.ingest(event("event-2", "v1")).eventId()).isEqualTo("event-1");
        inbox.dispatch();
        UUID id = inbox.find("event-1").runId();
        Run run = store.find(id).orElseThrow();
        assertThat(run.state).isEqualTo(State.DISCOVERING);
        var claim = store.claim(1).getFirst(); run.attempts = 2;
        run.transition(State.MANUAL_REQUIRED, "预算耗尽", Instant.now()); store.save(claim, run, Instant.now());
        inbox.ingest(event("event-3", "v1")); inbox.dispatch();
        assertThat(inbox.find("event-1").runId()).isEqualTo(id);
        assertThat(store.list(100, 0)).hasSize(1);
        assertThat(store.find(id).orElseThrow().attempts).isEqualTo(2);
        assertThatThrownBy(() -> inbox.ingest(event("event-1", "v2"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void newVersionWaitsForOldActiveRunWithoutDroppingEvent() {
        Run old = store.create(TestFixtures.request("old"));
        inbox.ingest(event("event-new", "v2")); inbox.dispatch();
        assertThat(inbox.find("event-new").runId()).isNull();
        store.cancel(old.id, "operator");
        jdbc.update("UPDATE agent_deployment_event SET due_at=now()"); inbox.dispatch();
        assertThat(inbox.find("event-new").runId()).isNotNull();
    }
    @Test void directRegistrationAndEventsCannotResetSameVersionBudget() {
        var request = TestFixtures.request("direct");
        Run direct = store.create(new StartRequest(request.requestKey(), request.provisioningId(), request.targets(), "v1", "app"));
        store.cancel(direct.id, "operator");
        inbox.ingest(event("event-existing", "v1")); inbox.dispatch();
        assertThat(inbox.find("event-existing").runId()).isEqualTo(direct.id);
        assertThatThrownBy(() -> store.create(new StartRequest("another-key", request.provisioningId(), request.targets(), "v1", "app")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void conflictingEventIsIsolatedInsteadOfBlockingOtherDeployments() {
        var request = TestFixtures.request("direct-profile");
        Run direct = store.create(new StartRequest(request.requestKey(), request.provisioningId(), request.targets(), "v1", "original-app"));
        store.cancel(direct.id, "operator");
        inbox.ingest(event("conflicting", "v1")); inbox.dispatch();
        assertThat(inbox.find("conflicting").status()).isEqualTo("VERIFICATION_PROFILE_CONFLICT");
        inbox.ingest(event("next-version", "v2")); inbox.dispatch();
        assertThat(inbox.find("next-version").runId()).isNotNull();
    }
    private Run terminalRun() {
        Run run = store.create(TestFixtures.request("notify"));
        var claim = store.claim(1).getFirst();
        run.transition(State.MANUAL_REQUIRED, "需要人工处理", Instant.now());
        store.save(claim, run, Instant.now());
        return run;
    }
    @Test void auditAndOutboxCommitTogetherAndStaleOwnerCannotEnqueue() {
        Run run = terminalRun();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_audit WHERE run_id=?", Integer.class, run.id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_notification WHERE run_id=?", Integer.class, run.id)).isEqualTo(1);
        store.cancel(run.id, "operator");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_notification", Integer.class)).isEqualTo(1);
        run.transition(State.FAILED, "陈旧状态", Instant.now());
        assertThatThrownBy(() -> store.save(new RunStore.Claim(run.id, "stale"), run, Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_notification", Integer.class)).isEqualTo(1);
    }
    @Test void notificationRetriesSameSignedEventAndStopsAfterSuccess() throws Exception {
        terminalRun();
        AtomicInteger calls = new AtomicInteger(); var ids = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String secret = "test-notification-signing-material-32";
        AtomicBoolean valid = new AtomicBoolean(true);
        server.createContext("/notify", exchange -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String timestamp = exchange.getRequestHeaders().getFirst("X-Agent-Timestamp");
            valid.compareAndSet(true, NotificationDispatcher.sign(secret, timestamp, payload)
                    .equals(exchange.getRequestHeaders().getFirst("X-Agent-Signature")));
            ids.add(exchange.getRequestHeaders().getFirst("X-Agent-Event-Id"));
            exchange.sendResponseHeaders(calls.incrementAndGet() == 1 ? 503 : 204, -1); exchange.close();
        });
        server.start();
        try {
            var base = TestFixtures.ops();
            var ops = new OpsProperties(4, base.stepTimeout(), base.collectionTimeout(), false, Map.of(),
                    new OpsProperties.Notification(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/notify"), secret, 3));
            var dispatcher = new NotificationDispatcher(jdbc, tx, ops);
            dispatcher.dispatch();
            assertThat(jdbc.queryForObject("SELECT state FROM agent_notification", String.class)).isEqualTo("PENDING");
            jdbc.update("UPDATE agent_notification SET due_at=now()"); dispatcher.dispatch(); dispatcher.dispatch();
            assertThat(jdbc.queryForObject("SELECT state FROM agent_notification", String.class)).isEqualTo("SENT");
            assertThat(jdbc.queryForObject("SELECT attempts FROM agent_notification", Integer.class)).isEqualTo(2);
            assertThat(ids).hasSize(2); assertThat(ids.get(0)).isEqualTo(ids.get(1)); assertThat(valid).isTrue();
        } finally { server.stop(0); }
    }
    @Test void deliveryCrashConsumesBudgetAndCannotLeaveAnInfiniteRetryLoop() {
        terminalRun();
        jdbc.update("UPDATE agent_notification SET attempts=8,lease_until=now()-interval '1 second'");
        // 超过投递预算的崩溃接管必须先落 DEAD，不能再访问接收方。
        var base = TestFixtures.ops();
        var ops = new OpsProperties(4, base.stepTimeout(), base.collectionTimeout(), false, Map.of(),
                new OpsProperties.Notification(true, URI.create("https://unused.example.com/notify"), "test-notification-signing-material-32", 8));
        new NotificationDispatcher(jdbc, tx, ops).dispatch();
        assertThat(jdbc.queryForObject("SELECT state FROM agent_notification", String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT attempts FROM agent_notification", Integer.class)).isEqualTo(8);
    }
    @Test void commandAuditPersistsIntentAndRejectsExpiredPostgresOwner() {
        Run run=store.create(TestFixtures.request("pg-command"));var claim=store.claim(1).getFirst();
        run.state=State.DIAGNOSING;run.evidence=TestFixtures.failed("HTTP 503");store.save(claim,run,Instant.now());claim=store.claim(1).getFirst();
        var scope=com.lanhao.ops.command.CommandModel.Scope.of(run,claim);
        var audit=new com.lanhao.ops.command.CommandAudit(jdbc,TestFixtures.json(),tx);
        var proposal=new com.lanhao.ops.command.CommandModel.Proposal("GET","uid-1","检查");
        var review=new com.lanhao.ops.command.CommandModel.Review(proposal,null,true,"test fixture",List.of("kubectl","get"));
        var record=audit.prepare(scope,List.of(review)).getFirst();audit.start(scope,record);
        var done=new com.lanhao.ops.command.CommandModel.Execution(record.id(),review,"SUCCEEDED","bounded",0,Instant.now());
        audit.finish(scope,done);assertThat(audit.find(scope.batchId()).getFirst().output()).isEqualTo("bounded");
        assertThat(audit.prepare(scope,List.of(review))).hasSize(1);
        jdbc.update("UPDATE agent_run SET lease_until=now()-interval '1 second' WHERE id=?",run.id);
        assertThatThrownBy(()->audit.guard(scope)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->audit.finish(scope,done)).isInstanceOf(IllegalStateException.class);
    }
}
