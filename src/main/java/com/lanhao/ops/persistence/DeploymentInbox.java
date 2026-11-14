package com.lanhao.ops.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.Model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class DeploymentInbox {
    private final JdbcTemplate jdbc;
    private final com.lanhao.ops.persistence.SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final com.lanhao.ops.domain.DeploymentIdentity identity;
    private ReleaseRunRegistry releases;
    @org.springframework.beans.factory.annotation.Autowired
    public void releases(ReleaseRunRegistry releases){this.releases=releases;}
    public DeploymentInbox(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this(jdbc, tx, json, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DeploymentInbox(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, com.lanhao.ops.domain.DeploymentIdentity identity) {
        this.jdbc = jdbc; this.db = new com.lanhao.ops.persistence.SqlDialect(jdbc); this.tx = tx; this.json = json;
        this.identity = identity;
    }
    public record Receipt(String eventId, UUID runId, String status) {}
    public Receipt ingest(DeploymentEvent event) {
        return tx.execute(status -> {
            jdbc.update(db.sql("INSERT INTO agent_deployment_event(event_id,provisioning_id,deployment_version,payload) "
                            + "VALUES(?,?,?,@json@) ON CONFLICT DO NOTHING"), event.eventId(), event.provisioningId(),
                    event.deploymentVersion(), encode(event));
            var rows = jdbc.query(db.sql("SELECT payload@text@,run_id,error_code FROM agent_deployment_event "
                            + "WHERE event_id=? OR (provisioning_id=? AND deployment_version=?)"),
                    (rs, i) -> new Object[]{decode(rs.getString(1)), com.lanhao.ops.persistence.SqlDialect.uuid(rs,2), rs.getString(3)},
                    event.eventId(), event.provisioningId(), event.deploymentVersion());
            if (rows.size() != 1) throw new IllegalStateException("事件身份与发布周期冲突");
            DeploymentEvent existing = (DeploymentEvent) rows.getFirst()[0];
            if (!existing.provisioningId().equals(event.provisioningId())
                    || !existing.deploymentVersion().equals(event.deploymentVersion())
                    || !existing.verificationProfile().equals(event.verificationProfile()))
                throw new IllegalStateException("同一事件不能更换目标、版本或验收目录");
            return receipt(existing.eventId(), (UUID) rows.getFirst()[1], (String) rows.getFirst()[2]);
        });
    }
    @Scheduled(fixedDelayString = "${agent.poll-interval:5000}")
    public void dispatch() {
        tx.executeWithoutResult(status -> {
            var events = jdbc.query(db.sql("SELECT payload@text@ FROM agent_deployment_event WHERE run_id IS NULL AND error_code IS NULL "
                    + "AND due_at<=@now@ ORDER BY due_at@skipLocked@ LIMIT 1"), (rs, i) -> decode(rs.getString(1)));
            if (events.isEmpty()) return;
            DeploymentEvent event = events.getFirst();
            var existing = jdbc.query("SELECT id,payload->>'verificationProfile' FROM agent_run WHERE provisioning_id=? AND payload->>'deploymentVersion'=?",
                    (rs, i) -> new Object[]{com.lanhao.ops.persistence.SqlDialect.uuid(rs,1), rs.getString(2)}, event.provisioningId(), event.deploymentVersion());
            if (!existing.isEmpty()) {
                if (!Objects.equals(existing.getFirst()[1], event.verificationProfile())) {
                    jdbc.update("UPDATE agent_deployment_event SET error_code='VERIFICATION_PROFILE_CONFLICT' WHERE event_id=?", event.eventId());
                    return;
                }
                jdbc.update("UPDATE agent_deployment_event SET run_id=? WHERE event_id=?", existing.getFirst()[0], event.eventId());
                return;
            }
            UUID id = UUID.nameUUIDFromBytes((event.provisioningId() + ":" + event.deploymentVersion()).getBytes(StandardCharsets.UTF_8));
            Run run = new Run(new StartRequest("event_" + id.toString().replace("-", ""), event.provisioningId(),
                    List.of(), event.deploymentVersion(), event.verificationProfile()), Instant.now());
            run.id = id;
            if (identity != null) identity.stamp(run);
            run.events.clear();
            run.transition(State.DISCOVERING, "已接收部署事件，等待平台创建受权目标", Instant.now());
            java.util.function.Supplier<Run> create=()->{
            int inserted = jdbc.update(db.sql("INSERT INTO agent_run(id,request_key,provisioning_id,state,payload,due_at) "
                            + "VALUES(?,?,?,?,@json@,@now@) ON CONFLICT DO NOTHING"),
                    id, run.requestKey, run.provisioningId, run.state.name(), encode(run));
            // 旧版本仍在执行时让新事件持久化等待，不撤销已下发动作。
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_run WHERE id=?)", Boolean.class, id));
            return inserted==1||exists?run:null;
            };
            Run selected=releases==null?create.get():releases.unique(event.provisioningId(),event.deploymentVersion(),create);
            if(selected!=null&&!Objects.equals(selected.verificationProfile,event.verificationProfile())) {
                jdbc.update("UPDATE agent_deployment_event SET error_code='VERIFICATION_PROFILE_CONFLICT' WHERE event_id=?",event.eventId());return;
            }
            if(selected!=null)jdbc.update("UPDATE agent_deployment_event SET run_id=? WHERE event_id=?",selected.id,event.eventId());
            else jdbc.update(db.sql("UPDATE agent_deployment_event SET due_at=@inboxDue@ WHERE event_id=?"), event.eventId());
        });
    }
    public Receipt find(String eventId) {
        var rows = jdbc.query("SELECT event_id,run_id,error_code FROM agent_deployment_event WHERE event_id=?",
                (rs, i) -> receipt(rs.getString(1), com.lanhao.ops.persistence.SqlDialect.uuid(rs,2), rs.getString(3)), eventId);
        return rows.stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
    private static Receipt receipt(String eventId, UUID runId, String error) {
        return new Receipt(eventId, runId, error != null ? error : runId == null ? "PENDING" : "ATTACHED");
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("事件序列化失败"); }
    }
    private DeploymentEvent decode(String value) {
        try { return json.readValue(value, DeploymentEvent.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("部署事件不可读取"); }
    }
}
