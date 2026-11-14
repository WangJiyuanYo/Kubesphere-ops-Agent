package com.lanhao.ops.api;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.OpsProperties;
import com.lanhao.ops.diagnosis.RunbookRegistry;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.persistence.DeploymentInbox;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class OperationsController {
    private final DeploymentInbox inbox;
    private final JdbcTemplate jdbc;
    private final com.lanhao.ops.persistence.SqlDialect db;
    private final AgentProperties config;
    private final OpsProperties ops;
    private final RunbookRegistry runbooks;
    private final ObjectMapper json;
    public OperationsController(DeploymentInbox inbox, JdbcTemplate jdbc, AgentProperties config,
            OpsProperties ops, RunbookRegistry runbooks, ObjectMapper json) {
        this.inbox = inbox; this.jdbc = jdbc; this.db = new com.lanhao.ops.persistence.SqlDialect(jdbc); this.config = config; this.ops = ops; this.runbooks = runbooks; this.json = json;
    }
    @PostMapping("/deployment-events")
    public ResponseEntity<DeploymentInbox.Receipt> ingest(@Valid @RequestBody DeploymentEvent event) {
        if (config.mode() != AgentProperties.Mode.KUBERNETES || !ops.strictPlatformContract()
                || !ops.verificationProfiles().containsKey(event.verificationProfile()))
            throw new IllegalArgumentException("部署事件需要严格 Kubernetes 模式和服务端验收目录");
        return ResponseEntity.accepted().body(inbox.ingest(event));
    }
    @GetMapping("/deployment-events/{eventId}")
    public DeploymentInbox.Receipt event(@PathVariable String eventId) { return inbox.find(eventId); }
    @GetMapping("/runbooks")
    public List<RunbookRegistry.Runbook> runbooks() { return runbooks.list(); }
    @GetMapping("/runs/{id}/audit")
    public List<AuditRecord> audit(@PathVariable UUID id, @RequestParam(defaultValue = "100") int limit) {
        limit(limit);
        return jdbc.query(db.sql("SELECT id,payload@text@,created_at FROM agent_audit WHERE run_id=? ORDER BY created_at DESC,id LIMIT ?"),
                (rs, i) -> {
                    try { return new AuditRecord(com.lanhao.ops.persistence.SqlDialect.uuid(rs,1), json.readTree(rs.getString(2)), rs.getTimestamp(3).toInstant()); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("审计记录不可读取"); }
                }, id, limit);
    }
    public record AuditRecord(UUID id, JsonNode event, java.time.Instant storedAt) {}
    @GetMapping("/runs/{id}/commands")
    public List<JsonNode> commands(@PathVariable UUID id) {
        return jdbc.query(db.sql("SELECT payload@text@,state FROM agent_command_execution WHERE run_id=? ORDER BY batch_id,ordinal LIMIT 100"),(rs,i)->{
            try{var value=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(rs.getString(1));value.put("state",rs.getString(2));return value;}
            catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("命令审计不可读取");}
        },id);
    }
    @GetMapping("/runs/{id}/notifications")
    public List<Map<String, Object>> notifications(@PathVariable UUID id) {
        return jdbc.queryForList("SELECT id,state,attempts,due_at FROM agent_notification WHERE run_id=? ORDER BY created_at DESC LIMIT 100", id);
    }
    @PostMapping("/notifications/{id}/redeliver")
    public void redeliver(@PathVariable UUID id) {
        // 仅人工显式重投 DEAD；保留事件身份以供接收方去重。
        if (jdbc.update(db.sql("UPDATE agent_notification SET state='PENDING',attempts=0,due_at=@now@ WHERE id=? AND state='DEAD'"), id) != 1)
            throw new IllegalStateException("通知不存在或尚未进入 DEAD");
    }
    private static void limit(int value) {
        if (value < 1 || value > 500) throw new IllegalArgumentException("查询数量必须为 1–500");
    }
}
