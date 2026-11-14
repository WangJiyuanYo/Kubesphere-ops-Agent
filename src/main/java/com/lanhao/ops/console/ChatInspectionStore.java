package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Each request intent is committed before the read. Expired turns never resume a tool run. */
@Repository
public class ChatInspectionStore {
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public ChatInspectionStore(JdbcTemplate jdbc,ObjectMapper json,TransactionTemplate tx) {
        this.jdbc=jdbc;this.db=new SqlDialect(jdbc);this.json=json;this.tx=tx;
    }
    public void guard(ChatStore.Claim claim) {
        if(claim.replay()||claim.owner()==null||jdbc.queryForObject(db.sql("SELECT count(*) FROM agent_chat_turn WHERE id=? AND session_id=? "
                +"AND lease_owner=? AND state='RUNNING' AND lease_until>@now@"),Integer.class,
                claim.turn().id(),claim.turn().sessionId(),claim.owner())!=1)
            throw new ContextMismatch("本轮排查租约已失效，停止采证；请刷新对话，不会自动重放命令");
    }
    public boolean active(UUID turnId) {
        return jdbc.queryForObject(db.sql("SELECT count(*) FROM agent_chat_turn WHERE id=? AND state='RUNNING' AND lease_until>@now@"),Integer.class,turnId)==1;
    }
    public void save(ChatStore.Claim claim,ChatInspectionGraph.Report report) {
        tx.executeWithoutResult(status->{
            // Lock the parent turn, sharing the same ordering as chat completion.
            jdbc.queryForList(db.sql("SELECT id FROM agent_chat_turn WHERE id=?@lock@"),claim.turn().id());
            guard(claim);
            if(!report.turnId().equals(claim.turn().id()))throw new IllegalArgumentException("排查记录身份不一致");
            try {
                jdbc.update(db.sql("INSERT INTO agent_chat_inspection(turn_id,payload) VALUES(?,@json@) "
                        +"ON CONFLICT(turn_id) DO UPDATE SET payload=excluded.payload"),report.turnId(),json.writeValueAsString(report));
            }catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new ConsoleFailure("排查审计保存失败，已停止执行");}
        });
    }
    public Optional<ChatInspectionGraph.Report> find(UUID turnId) {
        return jdbc.query(db.sql("SELECT payload@text@ FROM agent_chat_inspection WHERE turn_id=?"),(rs,index)->{
            try{return json.readValue(rs.getString(1),ChatInspectionGraph.Report.class);}
            catch(Exception failure){throw new ConsoleFailure("排查审计不可读取");}
        },turnId).stream().findFirst();
    }
}
