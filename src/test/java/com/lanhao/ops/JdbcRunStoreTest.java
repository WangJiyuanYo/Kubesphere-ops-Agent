package com.lanhao.ops;

import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.persistence.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** 必须使用专用 PostgreSQL 测试库；不会用 H2 模拟锁和 JSONB 语义。 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class JdbcRunStoreTest {
    private JdbcRunStore store;
    private JdbcTemplate jdbc;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
                System.getenv().getOrDefault("TEST_DATABASE_USER", "deployment_agent"),
                System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "demo-postgres-only"));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("TRUNCATE agent_run CASCADE");
        store = new JdbcRunStore(jdbc, TestFixtures.json(),
                new TransactionTemplate(new JdbcTransactionManager(source)), TestFixtures.config(true));
    }
    @Test void idempotencyAndPayloadSurviveRepositoryRecreation() {
        Run run = store.create(TestFixtures.request("same-key"));
        assertThat(store.create(TestFixtures.request("same-key")).id).isEqualTo(run.id);
        assertThat(store.find(run.id).orElseThrow().provisioningId).isEqualTo("9007199254740993");
        assertThatThrownBy(() -> store.create(new StartRequest("same-key", "123", run.targets)))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentWorkersCannotClaimSameRunAndExpiredOwnerCannotSave() {
        Run run = store.create(TestFixtures.request("lease-key"));
        var first = store.claim(1).getFirst();
        assertThat(store.claim(1)).isEmpty();
        jdbc.update("UPDATE agent_run SET lease_until=now()-interval '1 second' WHERE id=?", run.id);
        var replacement = store.claim(1).getFirst();
        assertThat(replacement.owner()).isNotEqualTo(first.owner());
        assertThatThrownBy(() -> store.save(first, run, Instant.now())).isInstanceOf(IllegalStateException.class);
        store.save(replacement, run, Instant.now());
    }
    @Test void activeProvisioningCannotHaveASecondAgent() {
        store.create(TestFixtures.request("first"));
        assertThatThrownBy(() -> store.create(TestFixtures.request("second")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void approvalIsBoundToCurrentPlanIdentity() {
        Run run = store.create(TestFixtures.request("approve"));
        var claim = store.claim(1).getFirst();
        run.state = State.WAITING_APPROVAL;
        run.plan = new Plan("plan-1", Action.RETRY_TRANSIENT, null, false, Instant.now());
        store.save(claim, run, Instant.now());
        assertThatThrownBy(() -> store.approve(run.id, "stale-plan", "operator"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.approve(run.id, "plan-1", "operator").state).isEqualTo(State.RETRY_WAIT);
    }
    @Test void waitingApprovalsRemainEligibleForDeadlineChecks() {
        Run run = store.create(TestFixtures.request("approval-deadline"));
        var claim = store.claim(1).getFirst();
        run.state = State.WAITING_APPROVAL;
        store.save(claim, run, Instant.now().minusSeconds(1));
        assertThat(store.claim(1)).hasSize(1);
    }
    @Test void expiredApprovalIsRejectedAndReleasedForNewMonitoring() {
        Run run = store.create(TestFixtures.request("expired-approval"));
        var claim = store.claim(1).getFirst();
        run.state = State.WAITING_APPROVAL;
        run.createdAt = Instant.now().minusSeconds(3601);
        run.plan = new Plan("expired-plan", Action.RETRY_TRANSIENT, null, false, Instant.now());
        store.save(claim, run, Instant.now());
        assertThatThrownBy(() -> store.approve(run.id, "expired-plan", "operator")).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(run.id).orElseThrow().state).isEqualTo(State.MANUAL_REQUIRED);
        assertThat(store.create(TestFixtures.request("new-monitoring")).state).isEqualTo(State.MONITORING);
    }
}
