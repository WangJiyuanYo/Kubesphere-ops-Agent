package com.lanhao.ops.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.domain.Model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcRunStore implements RunStore {
    private final JdbcTemplate jdbc;
    private final com.lanhao.ops.persistence.SqlDialect db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final AgentProperties properties;
    private final com.lanhao.ops.domain.DeploymentIdentity identity;
    private ReleaseRunRegistry releases;
    private com.lanhao.ops.console.AutonomyGuard autonomy;
    @org.springframework.beans.factory.annotation.Autowired
    public void automation(ReleaseRunRegistry releases,com.lanhao.ops.console.AutonomyGuard autonomy){this.releases=releases;this.autonomy=autonomy;}

    public JdbcRunStore(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate tx, AgentProperties properties) {
        this(jdbc, json, tx, properties, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcRunStore(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate tx, AgentProperties properties,
            com.lanhao.ops.domain.DeploymentIdentity identity) {
        this.jdbc = jdbc; this.db = new com.lanhao.ops.persistence.SqlDialect(jdbc); this.json = json; this.tx = tx; this.properties = properties;
        this.identity = identity;
    }
    public Run create(StartRequest request) {
        var replay=findByRequestKey(request.requestKey());
        if(replay.isPresent()) {
            Run existing=replay.get();
            if(!existing.provisioningId.equals(request.provisioningId())||!existing.targets.equals(request.targets())
                    ||!Objects.equals(existing.deploymentVersion,request.deploymentVersion())||!Objects.equals(existing.verificationProfile,request.verificationProfile()))
                throw new IllegalStateException("同一 requestKey 不能对应不同发布、目标或验收目录");
            return existing;
        }
        Run result=releases==null?insert(request):releases.unique(request.provisioningId(),request.deploymentVersion(),()->insert(request));
        if(!Objects.equals(result.verificationProfile,request.verificationProfile())
                ||!request.targets().isEmpty()&&!result.targets.isEmpty()&&!new HashSet<>(result.targets).equals(new HashSet<>(request.targets())))
            throw new IllegalStateException("同发布不能更换目标或验收目录，原预算已保留");
        return result;
    }
    private Run insert(StartRequest request) {
        Run run = new Run(request, Instant.now());
        if (identity != null) identity.stamp(run);
        else run.environment = properties.mode().name();
        jdbc.update(db.sql("INSERT INTO agent_run(id,request_key,provisioning_id,state,payload,due_at) "
                        + "VALUES (?,?,?,?,@json@,@now@) ON CONFLICT(request_key) DO NOTHING"),
                run.id, run.requestKey, run.provisioningId, run.state.name(), encode(run));
        Run existing = jdbc.queryForObject(db.sql("SELECT payload@text@ FROM agent_run WHERE request_key=?"),
                (rs, i) -> decode(rs.getString(1)), run.requestKey);
        if (!existing.provisioningId.equals(request.provisioningId()) || !existing.targets.equals(request.targets()))
            throw new IllegalStateException("同一 requestKey 不能对应不同部署目标");
        if (!Objects.equals(existing.deploymentVersion, request.deploymentVersion())
                || !Objects.equals(existing.verificationProfile, request.verificationProfile()))
            throw new IllegalStateException("同一 requestKey 不能更换发布版本或验收目录");
        return existing;
    }
    public Optional<Run> find(UUID id) {
        return jdbc.query(db.sql("SELECT payload@text@ FROM agent_run WHERE id=?"), (rs, i) -> decode(rs.getString(1)), id)
                .stream().findFirst();
    }
    public Optional<Run> findByRequestKey(String requestKey) {
        return jdbc.query(db.sql("SELECT payload@text@ FROM agent_run WHERE request_key=?"), (rs, i) -> decode(rs.getString(1)), requestKey)
                .stream().findFirst();
    }
    public List<Claim> claim(int limit) {
        String owner = UUID.randomUUID().toString();
        if(db.sqlite()) return tx.execute(status->{
            var ids=jdbc.query(db.sql("SELECT id FROM agent_run WHERE state IN "
                    +"('DISCOVERING','MONITORING','DIAGNOSING','WAITING_APPROVAL','RETRY_WAIT','EXECUTING','VERIFYING') "
                    +"AND due_at<=@now@ AND (lease_until IS NULL OR lease_until<@now@) ORDER BY due_at LIMIT ?"),
                    (rs,i)->SqlDialect.uuid(rs,1),limit);
            for(UUID id:ids) jdbc.update(db.sql("UPDATE agent_run SET lease_owner=?,lease_until=@afterSeconds@ WHERE id=?"),
                    owner,properties.leaseDuration().toSeconds(),id);
            return ids.stream().map(id->new Claim(id,owner)).toList();
        });
        return tx.execute(status -> jdbc.query(db.sql("""
                WITH candidates AS (
                    SELECT id FROM agent_run WHERE state IN
                    ('DISCOVERING','MONITORING','DIAGNOSING','WAITING_APPROVAL','RETRY_WAIT','EXECUTING','VERIFYING')
                    AND due_at<=@now@ AND (lease_until IS NULL OR lease_until<@now@)
                    ORDER BY due_at@skipLocked@ LIMIT ?
                ) UPDATE agent_run r SET lease_owner=?,lease_until=@afterSeconds@
                FROM candidates c WHERE r.id=c.id RETURNING r.id
                """), (rs, i) -> new Claim(com.lanhao.ops.persistence.SqlDialect.uuid(rs,1), owner),
                limit, owner, properties.leaseDuration().toSeconds()));
    }
    public void ensureLease(Claim claim) {
        if (jdbc.update(db.sql("UPDATE agent_run SET lease_until=@afterSeconds@ "
                        + "WHERE id=? AND lease_owner=? AND lease_until>@now@"),
                properties.leaseDuration().toSeconds(), claim.id(), claim.owner()) != 1)
            throw new IllegalStateException("任务执行租约已失效");
    }
    public void save(Claim claim, Run run, Instant dueAt) {
        tx.executeWithoutResult(status -> {
            if (jdbc.update(db.sql("UPDATE agent_run SET payload=@json@,state=?,due_at=?,lease_owner=NULL,lease_until=NULL "
                        + "WHERE id=? AND lease_owner=? AND lease_until>@now@"),
                encode(run), run.state.name(), Timestamp.from(dueAt), claim.id(), claim.owner()) != 1)
                throw new IllegalStateException("任务执行租约已失效，拒绝覆盖新状态");
            recordEvents(run);
        });
    }
    public Run approve(UUID id, String planId, String actor) {
        Run result = mutate(id, run -> {
            if(autonomy!=null)autonomy.run(run,false);
            if (run.state == State.WAITING_APPROVAL && !Instant.now().isBefore(run.createdAt.plus(properties.runTimeout()))) {
                run.transition(State.MANUAL_REQUIRED, "批准时任务已超过总时限，当前方案失效", Instant.now());
                return;
            }
            if (run.state != State.WAITING_APPROVAL || run.plan == null || !run.plan.id().equals(planId)
                    || run.plan.action() != Action.RETRY_TRANSIENT)
                throw new IllegalStateException("方案已变化或当前状态不可批准");
            run.transition(State.RETRY_WAIT, actor + " 批准当前低风险恢复方案", Instant.now());
            if(autonomy!=null)autonomy.approved(run);
        });
        // 先提交过期状态并释放活动任务唯一约束，再向调用方返回冲突。
        if (result.state != State.RETRY_WAIT) throw new IllegalStateException("任务已过期，不能批准原方案");
        return result;
    }
    public Run cancel(UUID id, String actor) {
        return mutate(id, run -> {
            if (run.state == State.EXECUTING || run.state == State.VERIFYING)
                throw new IllegalStateException("已下发的恢复操作需先观察完成，不能撤销外部动作");
            if (!run.state.terminal()) run.transition(State.CANCELLED, actor + " 取消监控任务", Instant.now());
        });
    }
    private Run mutate(UUID id, java.util.function.Consumer<Run> change) {
        return tx.execute(status -> {
            List<Run> rows = jdbc.query(db.sql("SELECT payload@text@ FROM agent_run WHERE id=? "
                            + "AND (lease_until IS NULL OR lease_until<@now@)@lock@"),
                    (rs, i) -> decode(rs.getString(1)), id);
            if (rows.isEmpty()) throw new IllegalStateException("任务不存在或正在处理中，请稍后重试");
            Run run = rows.getFirst(); change.accept(run);
            jdbc.update(db.sql("UPDATE agent_run SET payload=@json@,state=?,due_at=@now@,lease_owner=NULL,lease_until=NULL WHERE id=?"),
                    encode(run), run.state.name(), id);
            recordEvents(run);
            return run;
        });
    }
    public List<Run> list(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000)
            throw new IllegalArgumentException("分页参数超出范围");
        return jdbc.query(db.sql("SELECT payload@text@ FROM agent_run ORDER BY created_at DESC,id LIMIT ? OFFSET ?"),
                (rs, i) -> decode(rs.getString(1)), limit, offset);
    }
    private void recordEvents(Run run) {
        if(run.state.terminal()) {
            String outcome=run.verifiedRecovery()?"VERIFIED_RECOVERY":run.state==State.SUCCEEDED?"NATURAL_CONVERGENCE":
                    run.state==State.CANCELLED?"CANCELLED":run.attempts>0?"RECOVERY_NOT_VERIFIED":"MANUAL_OR_FAILED";
            jdbc.update(db.sql("INSERT INTO agent_recovery_outcome(run_id,outcome,payload,recorded_at) VALUES(?,?,@json@,?) ON CONFLICT DO NOTHING"),run.id,outcome,encode(run),System.currentTimeMillis());
        }
        // 事务出队记录保证成功已提交、进程随后退出时仍可归档记忆。
        if(run.verifiedRecovery())
            jdbc.update(db.sql("INSERT INTO agent_memory_outbox(run_id,payload,environment) VALUES(?,@json@,?) ON CONFLICT DO NOTHING"),run.id,encode(run),properties.mode().name());
        else if(run.state.terminal())
            com.lanhao.ops.memory.LearningOutbox.enqueue(jdbc,json,com.lanhao.ops.memory.LearningOutbox.unsuccessfulRun(run));
        List<Object> parameters = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Event event : run.events) {
            String payload;
            try { payload = json.writeValueAsString(event); }
            catch (JsonProcessingException e) { throw new IllegalStateException("审计事件序列化失败"); }
            UUID id = UUID.nameUUIDFromBytes((run.id + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            values.add(db.sql("(?,?,@json@)"));
            parameters.add(id); parameters.add(run.id); parameters.add(payload);
        }
        if (!values.isEmpty()) jdbc.update("INSERT INTO agent_audit(id,run_id,payload) VALUES "
                + String.join(",", values) + " ON CONFLICT DO NOTHING", parameters.toArray());
        if (run.state.terminal() || run.state == State.WAITING_APPROVAL) {
            String key = run.id + ":" + run.state + ":" + (run.plan == null ? "" : run.plan.id());
            UUID id = UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                String body = json.writeValueAsString(Map.of("id", id, "runId", run.id, "state", run.state,
                        "attempts", run.attempts, "planId", run.plan == null ? "" : run.plan.id(),
                        "deploymentVersion", Objects.toString(run.deploymentVersion, "")));
                jdbc.update(db.sql("INSERT INTO agent_notification(id,run_id,payload) VALUES(?,?,@json@) ON CONFLICT DO NOTHING"), id, run.id, body);
            } catch (JsonProcessingException e) { throw new IllegalStateException("通知事件序列化失败"); }
        }
    }
    private String encode(Run run) {
        try { return json.writeValueAsString(run); }
        catch (JsonProcessingException e) { throw new IllegalStateException("任务序列化失败"); }
    }
    private Run decode(String payload) {
        try { return json.readValue(payload, Run.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("任务数据不可读取"); }
    }
}
