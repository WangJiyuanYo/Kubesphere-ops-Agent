package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.*;
import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 记录实际样本量；自然收敛、建议和未验证恢复不能算有效修复。 */
@RestController
@RequestMapping("/api/console/learning-outcomes")
public class RecoveryOutcomesController {
    public record View(List<Map<String,Object>> counts,List<JsonNode> recent,String note) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public RecoveryOutcomesController(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @GetMapping public View view() {
        var counts=jdbc.queryForList("SELECT outcome,count(*) AS samples FROM agent_recovery_outcome GROUP BY outcome ORDER BY outcome");
        var rows=jdbc.query(new SqlDialect(jdbc).sql("SELECT o.run_id,o.outcome,o.payload@text@,o.recorded_at,a.policy_id,a.policy_revision FROM agent_recovery_outcome o LEFT JOIN agent_autonomy_run a ON a.run_id=o.run_id ORDER BY o.recorded_at DESC LIMIT 30"),(rs,i)->{
            try {
                var run=json.readTree(rs.getString(3));var node=json.createObjectNode();
                node.put("runId",rs.getString(1)).put("outcome",rs.getString(2)).put("recordedAt",rs.getLong(4));
                node.set("attempts",run.path("attempts"));node.set("actionKey",run.path("actionKey"));
                node.set("runbookId",run.path("plan").path("runbookId"));node.set("runbookVersion",run.path("plan").path("runbookVersion"));
                node.set("memoryReferences",run.path("plan").path("diagnosis").path("memories"));
                node.put("policyId",rs.getString(5));node.put("policyRevision",rs.getLong(6));
                return (JsonNode)node;
            }catch(Exception invalid){throw new IllegalStateException("恢复效果记录不可读取");}
        });
        return new View(counts,rows,"仅统计本次升级后结束的任务。经过受控恢复并通过完整验收，才计为恢复成功；自然收敛和未验证建议分别记录。少量样本不能证明长期覆盖率，复盘仍需按规则核验。");
    }
}
