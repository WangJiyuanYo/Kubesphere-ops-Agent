package com.lanhao.ops.console;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
import com.lanhao.ops.domain.Model.MemoryReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class ChatStore {
    public record Session(UUID id,String title,UUID connectionId,UUID runId,Instant updatedAt,UUID discoveryId,String connectionOrigin) {}
    public record Turn(UUID id,UUID sessionId,String userContent,String assistantContent,boolean includeCluster,
            String state,String source,Instant createdAt,List<MemoryReference> memories) {}
    public record Claim(Turn turn,UUID owner,boolean replay) {}
    private final JdbcTemplate jdbc;
    private final com.lanhao.ops.persistence.SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public ChatStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json){this.jdbc = jdbc; this.db = new com.lanhao.ops.persistence.SqlDialect(jdbc);this.tx=tx;this.json=json;}
    public Session create(String title,UUID connectionId,UUID runId) {
        return create(title,connectionId,runId,null);
    }
    public Session create(String title,UUID connectionId,UUID runId,UUID discoveryId) {
        return tx.execute(status -> {
            String origin=null;
            if(connectionId!=null) {
                var rows=jdbc.query(db.sql("SELECT payload@text@ FROM agent_console_connection WHERE id=?@lock@"),
                        (rs,i)->rs.getString(1),connectionId);
                if(rows.isEmpty())throw new ContextMismatch("连接已删除，请重新选择目标");
                try { origin=currentOrigin(json.readValue(rows.getFirst(),ConsoleSettings.Connection.class),discoveryId); }
                catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new ConsoleFailure("无法冻结会话目标身份");}
                if(origin==null)throw new ContextMismatch("发现目标已变化或不在监控范围，请重新选择");
            }
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO agent_chat_session(id,title,connection_id,run_id,discovery_id,connection_origin) VALUES(?,?,?,?,?,?)",
                    id,title,connectionId,runId,discoveryId,origin);
            return session(id);
        });
    }
    public Session session(UUID id) {
        return jdbc.query("SELECT * FROM agent_chat_session WHERE id=?",(rs,i)->new Session(com.lanhao.ops.persistence.SqlDialect.uuid(rs,"id"),
                rs.getString("title"),com.lanhao.ops.persistence.SqlDialect.uuid(rs,"connection_id"),com.lanhao.ops.persistence.SqlDialect.uuid(rs,"run_id"),
                rs.getTimestamp("updated_at").toInstant(),com.lanhao.ops.persistence.SqlDialect.uuid(rs,"discovery_id"),rs.getString("connection_origin")),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在"));
    }
    public List<Session> sessions() {
        return jdbc.query("SELECT * FROM agent_chat_session ORDER BY updated_at DESC,id LIMIT 50",(rs,i)->new Session(com.lanhao.ops.persistence.SqlDialect.uuid(rs,"id"),
                rs.getString("title"),com.lanhao.ops.persistence.SqlDialect.uuid(rs,"connection_id"),com.lanhao.ops.persistence.SqlDialect.uuid(rs,"run_id"),rs.getTimestamp("updated_at").toInstant(),com.lanhao.ops.persistence.SqlDialect.uuid(rs,"discovery_id"),rs.getString("connection_origin")));
    }
    public List<Turn> turns(UUID session) {
        session(session);
        var rows=jdbc.query("SELECT * FROM (SELECT * FROM agent_chat_turn WHERE session_id=? ORDER BY sequence DESC LIMIT 50) t ORDER BY sequence",
                (rs,i)->read(rs),session);
        return List.copyOf(rows);
    }
    public List<Session> forCase(UUID discoveryId,UUID runId) {
        return jdbc.query("SELECT id FROM agent_chat_session WHERE discovery_id=? OR run_id=? ORDER BY updated_at DESC,id LIMIT 50",
                (rs,i)->com.lanhao.ops.persistence.SqlDialect.uuid(rs,1),discoveryId,runId).stream().map(this::session).toList();
    }
    public Claim begin(UUID sessionId,UUID requestId,String content,boolean includeCluster) {
        return tx.execute(status->{
            var locked=jdbc.queryForList(db.sql("SELECT id FROM agent_chat_session WHERE id=?@lock@"),sessionId);
            if(locked.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");
            jdbc.update(db.sql("UPDATE agent_chat_turn SET state='FAILED',assistant_content='上次响应中断，请重新发送消息。',source='系统' "
                    +"WHERE session_id=? AND state='RUNNING' AND lease_until<=@now@"),sessionId);
            var existing=jdbc.query("SELECT * FROM agent_chat_turn WHERE id=?",(rs,i)->read(rs),requestId);
            if(!existing.isEmpty()) {
                Turn turn=existing.getFirst();
                if(!turn.sessionId().equals(sessionId)||!turn.userContent().equals(content)||turn.includeCluster()!=includeCluster)
                    throw new IllegalStateException("同一消息身份不能更改内容或上下文");
                if("RUNNING".equals(turn.state())) throw new IllegalStateException("消息仍在处理中，请稍后刷新");
                return new Claim(turn,null,true);
            }
            Integer running=jdbc.queryForObject("SELECT count(*) FROM agent_chat_turn WHERE session_id=? AND state='RUNNING'",Integer.class,sessionId);
            if(running!=null&&running>0) throw new IllegalStateException("当前会话仍在回复，请稍后重试");
            UUID owner=UUID.randomUUID();
            jdbc.update(db.sql("INSERT INTO agent_chat_turn(id,session_id,user_content,include_cluster,state,lease_owner,lease_until) "
                    +"VALUES(?,?,?,?,'RUNNING',?,@chatUntil@)"),requestId,sessionId,content,includeCluster,owner);
            jdbc.update(db.sql("UPDATE agent_chat_session SET updated_at=@now@ WHERE id=?"),sessionId);
            return new Claim(jdbc.queryForObject("SELECT * FROM agent_chat_turn WHERE id=?",(rs,i)->read(rs),requestId),owner,false);
        });
    }
    public Turn finish(Claim claim,String answer,String source,boolean success) {
        return finish(claim,answer,source,success,List.of());
    }
    public Turn finish(Claim claim,String answer,String source,boolean success,List<MemoryReference> references) {
        return finish(claim,answer,source,success,references,"UNBOUND");
    }
    public Turn finish(Claim claim,String answer,String source,boolean success,List<MemoryReference> references,String memoryScope) {
        String label=source==null?"系统":source.substring(0,Math.min(source.length(),100));
        return tx.execute(status->{
            String response=answer,responseSource=label;boolean completed=success;
            List<MemoryReference> captured=references;
            // 与连接编辑使用相同的行锁；请求期间换址后的迟到结果不能落为成功或自动复盘。
            var session=session(claim.turn().sessionId());
            if(success&&session.connectionOrigin()!=null) {
                String current=null;
                if(session.connectionId()!=null) {
                    var rows=jdbc.query(db.sql("SELECT payload@text@ FROM agent_console_connection WHERE id=?@lock@"),
                            (rs,i)->rs.getString(1),session.connectionId());
                    if(!rows.isEmpty())try { current=currentOrigin(json.readValue(rows.getFirst(),ConsoleSettings.Connection.class),session.discoveryId()); }
                    catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new ConsoleFailure("会话目标校验失败");}
                }
                if(!session.connectionOrigin().equals(current)) {
                    completed=false;response="本次回复期间目标已改变，未采纳旧结果；请新建对话后重新调查。";
                    responseSource="目标校验";captured=List.of();
                }
            }
            String capturedJson;
            try{capturedJson=json.writeValueAsString(captured);}catch(Exception failure){throw new IllegalStateException("记忆引用保存失败");}
            if(jdbc.update(db.sql("UPDATE agent_chat_turn SET assistant_content=?,source=?,state=?,memory_refs=@json@ WHERE id=? "
                            +"AND lease_owner=? AND state='RUNNING' AND lease_until>@now@"),
                    response,responseSource,completed?"SUCCEEDED":"FAILED",capturedJson,claim.turn().id(),claim.owner())!=1)
                throw new IllegalStateException("本次回复已过期，请刷新会话");
            jdbc.update(db.sql("UPDATE agent_chat_session SET updated_at=@now@ WHERE id=?"),claim.turn().sessionId());
            if(completed&&!"UNBOUND".equals(memoryScope))
                com.lanhao.ops.memory.LearningOutbox.enqueue(jdbc,json,
                        com.lanhao.ops.memory.LearningOutbox.chat(claim.turn().id(),claim.turn().userContent(),response,memoryScope));
            return jdbc.queryForObject("SELECT * FROM agent_chat_turn WHERE id=?",(rs,i)->read(rs),claim.turn().id());
        });
    }
    private String currentOrigin(ConsoleSettings.Connection connection,UUID discoveryId) {
        if(discoveryId==null)return DiscoveryGateway.origin(connection);
        var rows=jdbc.query(db.sql("SELECT t.connection_id,t.origin_hash,t.observation@text@ AS observation_json,s.namespaces@text@ AS namespaces_json "
                +"FROM agent_discovered_task t LEFT JOIN agent_discovery_source s ON s.connection_id=t.connection_id WHERE t.id=?"),
                (rs,i)->{
                    if(!connection.id().equals(com.lanhao.ops.persistence.SqlDialect.uuid(rs,"connection_id")))return null;
                    try {
                        var observation=json.readValue(rs.getString("observation_json"),DiscoveryGateway.Observation.class);
                        var namespaces=json.readTree(rs.getString("namespaces_json"));
                        String namespace=observation.target().namespace();
                        boolean allowed=false;for(var value:namespaces)if(value.asText().equals(namespace))allowed=true;
                        String origin=DiscoveryGateway.origin(DiscoveryStore.scoped(connection,namespace));
                        return allowed&&origin.equals(rs.getString("origin_hash"))?origin:null;
                    }catch(Exception failure){return null;}
                },discoveryId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Turn read(java.sql.ResultSet rs)throws java.sql.SQLException {
        List<MemoryReference> refs;
        try{refs=json.readValue(rs.getString("memory_refs"),json.getTypeFactory().constructCollectionType(List.class,MemoryReference.class));}
        catch(Exception failure){throw new IllegalStateException("记忆引用不可读取");}
        return new Turn(com.lanhao.ops.persistence.SqlDialect.uuid(rs,"id"),com.lanhao.ops.persistence.SqlDialect.uuid(rs,"session_id"),rs.getString("user_content"),
                rs.getString("assistant_content"),rs.getBoolean("include_cluster"),rs.getString("state"),rs.getString("source"),rs.getTimestamp("created_at").toInstant(),refs);
    }
    public Turn turn(UUID id) {
        return jdbc.query("SELECT * FROM agent_chat_turn WHERE id=?",(rs,i)->read(rs),id).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"对话消息不存在"));
    }
}
