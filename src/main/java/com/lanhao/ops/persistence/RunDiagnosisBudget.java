package com.lanhao.ops.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.console.ProvisioningStore;
import com.lanhao.ops.domain.Model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

/** 先记调用意图。崩溃或未知结果不会重放同一份证据的模型请求。 */
@Component
public class RunDiagnosisBudget {
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public RunDiagnosisBudget(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json){this.jdbc=jdbc;db=new SqlDialect(jdbc);this.tx=tx;this.json=json;}
    public Plan diagnose(Run run,Supplier<Plan> invocation) {
        String fingerprint=ProvisioningStore.hash(run.evidence.fingerprint()+"\n"+run.evidence.reason()+"\n"+run.evidence.facts()+"\n"+run.evidence.issues());
        boolean claimed=Boolean.TRUE.equals(tx.execute(s->{
            var binding=jdbc.query("SELECT a.watch_id,p.payload->>'maxDiagnoses' FROM agent_autonomy_run a JOIN agent_autonomy_policy p ON p.id=a.policy_id WHERE a.run_id=?",(rs,i)->new Object[]{SqlDialect.uuid(rs,1),rs.getInt(2)},run.id);
            if(!binding.isEmpty()) {
                UUID watch=(UUID)binding.getFirst()[0];
                jdbc.queryForList(db.sql("SELECT id FROM agent_provisioning_watch WHERE id=?@lock@"),watch);
                int used=jdbc.queryForObject("SELECT count(*) FROM agent_diagnosis_trigger WHERE watch_id=?",Integer.class,watch);
                used+=jdbc.queryForObject("SELECT count(*) FROM agent_run_diagnosis d JOIN agent_autonomy_run a ON a.run_id=d.run_id WHERE a.watch_id=?",Integer.class,watch);
                if(used>=(int)binding.getFirst()[1])return false;
            }
            jdbc.queryForList(db.sql("SELECT id FROM agent_run WHERE id=?@lock@"),run.id);
            if(jdbc.queryForObject("SELECT count(*) FROM agent_run_diagnosis WHERE run_id=?",Integer.class,run.id)>=3)return false;
            return jdbc.update("INSERT INTO agent_run_diagnosis(run_id,fingerprint,state,created_at) VALUES(?,?,'REQUESTED',?) ON CONFLICT DO NOTHING",run.id,fingerprint,System.currentTimeMillis())==1;
        }));
        if(!claimed)throw new com.lanhao.ops.console.ContextMismatch("同证据诊断已记账或发布诊断预算耗尽，停止自动重放");
        try {
            Plan plan=invocation.get();
            jdbc.update(db.sql("UPDATE agent_run_diagnosis SET state='COMPLETED',plan=@json@ WHERE run_id=? AND fingerprint=?"),json.writeValueAsString(plan),run.id,fingerprint);
            return plan;
        }catch(RuntimeException|com.fasterxml.jackson.core.JsonProcessingException failure) {
            jdbc.update("UPDATE agent_run_diagnosis SET state='FAILED' WHERE run_id=? AND fingerprint=?",run.id,fingerprint);
            throw new com.lanhao.ops.console.ContextMismatch("本次模型请求未完成，已保存失败意图，不自动重放");
        }
    }
}
