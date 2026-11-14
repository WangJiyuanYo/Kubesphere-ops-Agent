package com.lanhao.ops.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.lanhao.ops.command.CommandModel.*;

@Repository
public class CommandAudit {
    private final JdbcTemplate jdbc;private final SqlDialect db;private final ObjectMapper json;private final TransactionTemplate tx;
    public CommandAudit(JdbcTemplate jdbc,ObjectMapper json,TransactionTemplate tx){this.jdbc=jdbc;this.db=new SqlDialect(jdbc);this.json=json;this.tx=tx;}
    public List<Execution> find(UUID batchId){return jdbc.query(db.sql("SELECT payload@text@,state FROM agent_command_execution WHERE batch_id=? ORDER BY ordinal"),
            (rs,i)->{var e=decode(rs.getString(1));return new Execution(e.id(),e.review(),rs.getString(2),e.output(),e.exitCode(),e.at());},batchId);}
    public void guard(Scope scope) {
        com.lanhao.ops.workflow.StepControl.check();
        if(!Instant.now().isBefore(scope.deadline()))throw new IllegalStateException("命令采证阶段时限已耗尽");
        if(jdbc.queryForObject(db.sql("SELECT count(*) FROM agent_run WHERE id=? AND lease_owner=? AND lease_until>@now@ AND state='DIAGNOSING'"),Integer.class,scope.runId(),scope.leaseOwner())!=1)
            throw new IllegalStateException("命令执行租约已失效");
    }
    public List<Execution> prepare(Scope scope,List<Review> reviews) {
        return tx.execute(status->{
            guard(scope);var existing=find(scope.batchId());if(!existing.isEmpty())return existing;
            for(int i=0;i<reviews.size();i++) {
                Review review=reviews.get(i);UUID id=UUID.nameUUIDFromBytes((scope.batchId()+":"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Execution record=new Execution(id,review,review.allowed()?"APPROVED":"REJECTED","",null,Instant.now());
                jdbc.update(db.sql("INSERT INTO agent_command_execution(id,batch_id,run_id,ordinal,state,payload) VALUES(?,?,?,?,?,@json@) ON CONFLICT DO NOTHING"),
                        id,scope.batchId(),scope.runId(),i,record.state(),encode(record));
            }
            return find(scope.batchId());
        });
    }
    public void start(Scope scope,Execution record) {
        guard(scope);
        if(jdbc.update(db.sql("UPDATE agent_command_execution SET state='RUNNING' WHERE id=? AND state='APPROVED' AND "+lease()),record.id(),scope.runId(),scope.leaseOwner())!=1)
            throw new IllegalStateException("命令已被认领或租约失效");
    }
    public void finish(Scope scope,Execution record) {
        if(jdbc.update(db.sql("UPDATE agent_command_execution SET state=?,payload=@json@ WHERE id=? AND state='RUNNING' AND "+lease()),
                record.state(),encode(record),record.id(),scope.runId(),scope.leaseOwner())!=1)throw new IllegalStateException("命令结果所属租约失效");
    }
    private String lease(){return "EXISTS(SELECT 1 FROM agent_run r WHERE r.id=? AND r.lease_owner=? AND r.lease_until>@now@ AND r.state='DIAGNOSING')";}
    private String encode(Execution value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("命令审计序列化失败");}}
    private Execution decode(String value){try{return json.readValue(value,Execution.class);}catch(Exception failure){throw new IllegalStateException("命令审计读取失败");}}
}
