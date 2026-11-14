package com.lanhao.ops.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.DeploymentIdentity;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 自动总结先保存为待验证经验；归档失败不回滚对话或诊断，重放不覆盖人工修订。 */
@Component
public class LearningOutbox {
    public record Draft(String sourceKey,String sourceRef,String scopeKey,MemoryStore.Input content) {}
    private record Claim(String key,String payload,UUID owner) {}
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MemoryStore memories;

    public LearningOutbox(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,MemoryStore memories) {
        this.jdbc=jdbc;this.db=new SqlDialect(jdbc);this.tx=tx;this.json=json;this.memories=memories;
    }

    /** 调用者必须处于保存源事实的同一主库事务中。 */
    public static void enqueue(JdbcTemplate jdbc,ObjectMapper json,Draft draft) {
        try {
            jdbc.update(new SqlDialect(jdbc).sql("INSERT INTO agent_learning_outbox(source_key,payload) VALUES(?,@json@) ON CONFLICT DO NOTHING"),
                    draft.sourceKey(),json.writeValueAsString(draft));
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("自动复盘快照保存失败");}
    }

    public static Draft chat(UUID turnId,String question,String answer,String scope) {
        return new Draft("REFLECTION:CHAT:"+turnId,turnId.toString(),scope,new MemoryStore.Input(
                cut("对话复盘："+question,120),cut(question,4000),"根因尚未通过部署结果核验",
                cut("对话建议（未执行，不代表已经修复）：\n"+answer,4000),
                "对话回复已完成；尚无部署恢复验收，需补充结果证据后人工确认。","自动复盘 对话",MemoryStore.Status.DRAFT));
    }

    public static Draft investigation(UUID taskId,UUID requestId,String scope,Evidence evidence,Plan plan) {
        var diagnosis=plan.diagnosis();
        return new Draft("REFLECTION:DISCOVERY:"+taskId+":"+requestId,taskId.toString(),scope,new MemoryStore.Input(
                cut("调查复盘："+evidence.reason(),120),cut(evidence.reason()+"\n"+String.join("\n",evidence.facts()),4000),
                cut("待验证根因假设："+diagnosis.cause(),4000),
                cut("检查计划（未据此执行恢复）：\n"+String.join("\n",diagnosis.checks())+"\n修复建议：\n"+String.join("\n",diagnosis.repairSteps()),4000),
                cut("尚未完成恢复验收；采证缺口："+evidence.issues()+"\n建议验证："+diagnosis.verification(),4000),
                "自动复盘 调查",MemoryStore.Status.DRAFT));
    }

    public static Draft unsuccessfulRun(Run run) {
        String reason=run.evidence==null?"没有完整现场证据":run.evidence.reason();
        String cause=run.plan==null||run.plan.diagnosis()==null?"尚无根因结论":Objects.toString(run.plan.diagnosis().cause(),"尚无根因结论");
        return new Draft("REFLECTION:RUN:"+run.id,run.id.toString(),DeploymentIdentity.scope(run),new MemoryStore.Input(
                cut("任务复盘："+run.state+" "+reason,120),cut(reason+"\n目标："+run.targets,4000),cut("根因假设："+cause,4000),
                cut("本次恢复意图次数："+run.attempts+"；动作身份："+Objects.toString(run.actionKey,"无")
                        +"\n实际状态轨迹：\n"+run.events+"\n下次排查须先核对目标、采证缺口与动作对账，不重复盲目重试。",4000),
                run.state==State.SUCCEEDED?"部署已经收敛，但没有满足已受理恢复动作的完整验收依据；不能证明原建议有效，需人工确认。"
                        :"本次未取得经过恢复后的完整成功验收，不能作为已解决案例。","自动复盘 任务复盘",MemoryStore.Status.DRAFT));
    }

    private static String cut(String value,int max) {
        String clean=new Redactor().clean(value);return clean.substring(0,Math.min(clean.length(),max));
    }

    private Claim claim() {
        return tx.execute(status->{
            var rows=jdbc.query(db.sql("SELECT source_key,payload@text@ FROM agent_learning_outbox WHERE processed_at IS NULL AND due_at<=@now@ "
                    +"AND (lease_until IS NULL OR lease_until<=@now@) ORDER BY due_at,source_key@skipLocked@ LIMIT 1"),
                    (rs,i)->new String[]{rs.getString(1),rs.getString(2)});
            if(rows.isEmpty())return null;
            UUID owner=UUID.randomUUID();String[] row=rows.getFirst();
            int changed=jdbc.update(db.sql("UPDATE agent_learning_outbox SET lease_owner=?,lease_until=@afterSeconds@ WHERE source_key=? "
                    +"AND processed_at IS NULL AND (lease_until IS NULL OR lease_until<=@now@)"),owner,30,row[0]);
            return changed==1?new Claim(row[0],row[1],owner):null;
        });
    }

    @Scheduled(fixedDelayString="${agent.memory.learning-interval:5000}")
    public void capture() {
        for(int i=0;i<10;i++) {
            var claim=claim();if(claim==null)return;
            try {
                Draft draft=json.readValue(claim.payload(),Draft.class);
                if(draft.content().status()!=MemoryStore.Status.DRAFT||!draft.sourceKey().equals(claim.key()))
                    throw new IllegalStateException("自动复盘快照不允许授予已验证状态");
                memories.create(draft.sourceKey(),"REFLECTION",draft.sourceRef(),draft.content(),draft.scopeKey());
                jdbc.update(db.sql("UPDATE agent_learning_outbox SET processed_at=@now@,lease_owner=NULL,lease_until=NULL,error_code='' "
                        +"WHERE source_key=? AND lease_owner=? AND lease_until>@now@"),claim.key(),claim.owner());
            }catch(RuntimeException|com.fasterxml.jackson.core.JsonProcessingException failure) {
                jdbc.update(db.sql("UPDATE agent_learning_outbox SET attempts=attempts+1,due_at=@afterSeconds@,lease_owner=NULL,lease_until=NULL,error_code='SUMMARY_CAPTURE_FAILED' "
                        +"WHERE source_key=? AND lease_owner=?"),60,claim.key(),claim.owner());
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("自动复盘归档失败，保留快照稍后重试");
            }
        }
    }
}
