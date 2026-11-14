package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.Model.Plan;
import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.lanhao.ops.console.DiscoveryGateway.*;

/** 自动发现账本与恢复任务隔离；仅显式选择的记录进入诊断队列。 */
@Repository
public class DiscoveryStore {
    public enum State { PENDING, QUEUED, RUNNING, DONE, FAILED, IGNORED }
    public record Source(UUID connectionId,boolean enabled,long revision,Instant checkedAt,String error,boolean syncing,List<String> namespaces) {}
    public record ScanClaim(UUID connectionId,UUID owner,ConsoleSettings.Connection connection,List<String> namespaces) {}
    public record Report(Instant collectedAt,Observation observation,Plan plan,com.lanhao.ops.domain.Model.Evidence evidence) {
        public Report(Instant collectedAt,Observation observation,Plan plan){this(collectedAt,observation,plan,observation.evidence());}
    }
    public record Task(UUID id,UUID connectionId,String originHash,String connectionName,Observation observation,String presence,
            State state,long revision,UUID requestId,String selectedBy,Instant selectedAt,Report report,String error,Instant discoveredAt,Instant observedAt) {}
    public record Page(List<Task> items,long total) {}
    public record Work(Task task,UUID owner) {}
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ConsoleSettings settings;
    public DiscoveryStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,ConsoleSettings settings) {
        this.jdbc=jdbc;db=new SqlDialect(jdbc);this.tx=tx;this.json=json;this.settings=settings;
    }
    public List<Source> sources() {
        return jdbc.query(db.sql("SELECT *, namespaces@text@ AS namespace_json, (lease_until>@now@ AND lease_owner IS NOT NULL) AS syncing FROM agent_discovery_source ORDER BY connection_id"),
                (rs,i)->new Source(SqlDialect.uuid(rs,"connection_id"),rs.getBoolean("enabled"),rs.getLong("revision"),time(rs,"checked_at"),rs.getString("error"),rs.getBoolean("syncing"),namespaceList(rs.getString("namespace_json"))));
    }
    public Source source(UUID id){return sources().stream().filter(s->s.connectionId().equals(id)).findFirst().orElse(new Source(id,false,0,null,"",false,List.of(settings.connection(id).namespace())));}
    public Source configure(UUID id,boolean enabled,long revision) {
        return configure(id,enabled,revision,null);
    }
    public Source configure(UUID id,boolean enabled,long revision,List<String> namespaces) {
        return tx.execute(status->{
            var connection=lockConnection(id);
            List<String> selected=namespaces==null?source(id).namespaces():validateNamespaces(namespaces);
            jdbc.update(db.sql("INSERT INTO agent_discovery_source(connection_id,namespaces) VALUES(?,@json@) ON CONFLICT DO NOTHING"),id,encode(selected));
            if(jdbc.update(db.sql("UPDATE agent_discovery_source SET enabled=?,namespaces=@json@,revision=revision+1,lease_owner=NULL,lease_until=NULL,due_at=@now@,error='' WHERE connection_id=? AND revision=?"),enabled,encode(selected),id,revision)!=1)
                throw new IllegalStateException("监控设置已更新，请刷新后重试");
            return source(id);
        });
    }
    public ScanClaim claimScan(UUID id,boolean force) {
        return tx.execute(status->{
            var connection=lockConnection(id);UUID owner=UUID.randomUUID();
            int changed=jdbc.update(db.sql("UPDATE agent_discovery_source SET lease_owner=?,lease_until=@afterSeconds@ WHERE connection_id=? AND enabled=true "
                    +"AND (lease_until IS NULL OR lease_until<=@now@)"+(force?"":" AND due_at<=@now@")),owner,60,id);
            return changed==0?null:new ScanClaim(id,owner,connection,source(id).namespaces());
        });
    }
    public void scanned(ScanClaim claim,List<Observation> observations,String error) {
        tx.executeWithoutResult(status->{
            var connection=lockConnection(claim.connectionId());
            // 凭据轮换、地址变化、暂停或租约过期后，丢弃旧请求的全部结果。
            if(connection.revision()!=claim.connection().revision())return;
            int changed=jdbc.update(db.sql("UPDATE agent_discovery_source SET lease_owner=NULL,lease_until=NULL,checked_at=@now@,due_at=@afterSeconds@,error=? "
                    +"WHERE connection_id=? AND lease_owner=? AND enabled=true AND lease_until>@now@"),30,error,claim.connectionId(),claim.owner());
            if(changed==0||!error.isBlank())return;
            Set<String> origins=new HashSet<>();
            for(String namespace:claim.namespaces())origins.add(origin(scoped(connection,namespace)));
            // 全部范围扫描成功才提交；未扫描/未授权的目标不能被注入账本。
            for(var observation:observations)if(!claim.namespaces().contains(observation.target().namespace()))
                throw new ConsoleFailure("同步结果包含监控范围外的目标");
            jdbc.update("UPDATE agent_discovered_task SET presence='OUTDATED' WHERE connection_id=?",connection.id());
            for(String origin:origins)jdbc.update("UPDATE agent_discovered_task SET presence='MISSING' WHERE connection_id=? AND origin_hash=?",connection.id(),origin);
            for(var observation:observations) {
                var target=observation.target();
                String origin=origin(scoped(connection,target.namespace()));
                jdbc.update("UPDATE agent_discovered_task SET presence='SUPERSEDED' WHERE connection_id=? AND origin_hash=? AND resource_uid=? AND generation<>?",
                        connection.id(),origin,target.uid(),target.generation());
                jdbc.update(db.sql("INSERT INTO agent_discovered_task(id,connection_id,origin_hash,connection_name,resource_uid,generation,observation) VALUES(?,?,?,?,?,?,@json@) "
                        +"ON CONFLICT(connection_id,origin_hash,resource_uid,generation) DO UPDATE SET observation=excluded.observation,connection_name=excluded.connection_name,presence='PRESENT',observed_at=@now@"),
                        UUID.randomUUID(),connection.id(),origin,connection.name(),target.uid(),target.generation(),encode(observation));
            }
        });
    }
    public Page list(UUID connectionId,State state,int offset) {
        if(offset<0||offset>100000)throw new IllegalArgumentException("分页范围无效");
        String where=" WHERE 1=1";List<Object> parameters=new ArrayList<>();
        if(connectionId!=null){where+=" AND connection_id=?";parameters.add(connectionId);}
        if(state!=null){where+=" AND state=?";parameters.add(state.name());}
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM agent_discovered_task"+where,Long.class,parameters.toArray()));
        parameters.add(offset);
        var items=jdbc.query(db.sql("SELECT *,observation@text@ AS observation_json,diagnosis@text@ AS report_json FROM agent_discovered_task"+where+" ORDER BY discovered_at DESC,id LIMIT 30 OFFSET ?"),(rs,i)->read(rs),parameters.toArray());
        return new Page(items,total);
    }
    public Task task(UUID id) {
        return jdbc.query(db.sql("SELECT *,observation@text@ AS observation_json,diagnosis@text@ AS report_json FROM agent_discovered_task WHERE id=?"),(rs,i)->read(rs),id)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"发现任务不存在"));
    }
    public Task select(UUID id,long revision,UUID requestId,String actor) {
        return tx.execute(status->{
            Task initial=task(id);if(initial.connectionId()==null)throw new ConsoleFailure("原连接已删除，只能查看历史记录");
            var connection=lockConnection(initial.connectionId());
            jdbc.queryForList(db.sql("SELECT id FROM agent_discovered_task WHERE id=?@lock@"),id);
            Task task=task(id);
            if(requestId.equals(task.requestId()))return task;
            if(!authorized(connection,task)||!task.presence().equals("PRESENT"))throw new ConsoleFailure("部署目标已变化或不在当前清单，请同步后选择最新任务");
            if(task.revision()!=revision||task.state()==State.QUEUED||task.state()==State.RUNNING||task.state()==State.IGNORED)throw new IllegalStateException("任务已变化或正在排查，请刷新");
            jdbc.update(db.sql("UPDATE agent_discovered_task SET state='QUEUED',revision=revision+1,request_id=?,selected_by=?,selected_at=@now@,error='' WHERE id=?"),requestId,actor,id);
            return task(id);
        });
    }
    public Task ignore(UUID id,long revision,boolean ignored) {
        return tx.execute(status->{
            if(jdbc.update("UPDATE agent_discovered_task SET state=?,revision=revision+1 WHERE id=? AND revision=? AND state NOT IN ('QUEUED','RUNNING')",
                    ignored?State.IGNORED.name():State.PENDING.name(),id,revision)!=1)throw new IllegalStateException("任务已变化或正在排查");
            return task(id);
        });
    }
    public Work claimWork() {
        return tx.execute(status->{
            jdbc.update(db.sql("UPDATE agent_discovered_task SET state='FAILED',revision=revision+1,error='上次处理超时或进程中断，请手动重新排查',lease_owner=NULL,lease_until=NULL WHERE state='RUNNING' AND lease_until<=@now@"));
            var ids=jdbc.query(db.sql("SELECT id FROM agent_discovered_task WHERE state='QUEUED' ORDER BY selected_at,id@skipLocked@ LIMIT 1"),(rs,i)->SqlDialect.uuid(rs,1));
            if(ids.isEmpty())return null;
            UUID id=ids.getFirst(),owner=UUID.randomUUID();
            if(jdbc.update(db.sql("UPDATE agent_discovered_task SET state='RUNNING',revision=revision+1,lease_owner=?,lease_until=@afterSeconds@ WHERE id=? AND state='QUEUED'"),owner,120,id)!=1)return null;
            return new Work(task(id),owner);
        });
    }
    public void finish(Work work,Report report,String error) {
        tx.executeWithoutResult(status->{
            Task latest=task(work.task().id());
            boolean current=latest.connectionId()!=null;
            if(current){var connection=lockConnection(latest.connectionId());latest=task(work.task().id());current=authorized(connection,latest)&&latest.presence().equals("PRESENT");}
            String failure=current?error:"连接或目标已变化，未采纳本次诊断，请选择最新任务";
            Report saved=current?(report==null?latest.report():report):null;
            int changed=jdbc.update(db.sql("UPDATE agent_discovered_task SET state=?,revision=revision+1,diagnosis=@json@,error=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=? AND state='RUNNING' AND lease_until>@now@"),
                    failure.isBlank()?State.DONE.name():State.FAILED.name(),saved==null?null:encode(saved),failure,latest.id(),work.owner());
            if(changed==1&&failure.isBlank()&&report!=null) {
                var connection=connection(latest);
                com.lanhao.ops.memory.LearningOutbox.enqueue(jdbc,json,com.lanhao.ops.memory.LearningOutbox.investigation(
                        latest.id(),latest.requestId(),com.lanhao.ops.domain.DeploymentIdentity.realScope(connection.apiUrl(),connection.namespace()),
                        report.evidence()==null?report.observation().evidence():report.evidence(),report.plan()));
            }
        });
    }
    /** 模型调用之前提交采证快照；分析失败或进程中断后仍能查看已有证据。 */
    public void collected(Work work,Report report) {
        tx.executeWithoutResult(s->{
            var task=task(work.task().id());var connection=lockConnection(task.connectionId());
            if(!authorized(connection,task)||!task.presence().equals("PRESENT"))throw new ContextMismatch("采证目标已变化，不采纳旧结果");
            if(jdbc.update(db.sql("UPDATE agent_discovered_task SET diagnosis=@json@ WHERE id=? AND lease_owner=? AND state='RUNNING' AND lease_until>@now@"),encode(report),task.id(),work.owner())!=1)throw new ContextMismatch("调查租约已过期，不采纳迟到证据");
        });
    }
    public ConsoleSettings.Connection connection(Task task) {
        if(task.connectionId()==null)throw new ConsoleFailure("原连接已删除，无法读取部署");
        var connection=settings.connection(task.connectionId());
        if(!authorized(connection,task))throw new ContextMismatch("原连接的目标或监控范围已改变，无法读取此历史部署；请重新选择目标");
        return scoped(connection,task.observation().target().namespace());
    }
    private boolean authorized(ConsoleSettings.Connection connection,Task task) {
        String namespace=task.observation().target().namespace();
        return source(connection.id()).namespaces().contains(namespace)&&origin(scoped(connection,namespace)).equals(task.originHash());
    }
    public static ConsoleSettings.Connection scoped(ConsoleSettings.Connection c,String namespace) {
        return new ConsoleSettings.Connection(c.id(),c.revision(),c.name(),c.apiUrl(),c.consoleUrl(),namespace,c.encryptedToken(),c.encryptedCaCertificate());
    }
    private List<String> namespaceList(String value) {
        try{return validateNamespaces(json.readValue(value,json.getTypeFactory().constructCollectionType(List.class,String.class)));}
        catch(Exception failure){throw new ConsoleFailure("监控范围不可读取，请重新配置");}
    }
    public static List<String> validateNamespaces(List<String> namespaces) {
        if(namespaces==null||namespaces.isEmpty()||namespaces.size()>20)throw new IllegalArgumentException("请选择 1–20 个命名空间");
        if(namespaces.stream().anyMatch(n->n==null||n.length()>63||!n.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")))
            throw new IllegalArgumentException("监控命名空间无效，不支持通配符或全局扫描");
        if(new HashSet<>(namespaces).size()!=namespaces.size())throw new IllegalArgumentException("监控命名空间不可重复");
        return namespaces.stream().sorted().toList();
    }
    private ConsoleSettings.Connection lockConnection(UUID id) {
        if(jdbc.queryForList(db.sql("SELECT id FROM agent_console_connection WHERE id=?@lock@"),id).isEmpty())throw new ConsoleFailure("连接已删除，请刷新");
        return settings.connection(id);
    }
    private Task read(ResultSet row)throws SQLException {
        return new Task(SqlDialect.uuid(row,"id"),SqlDialect.uuid(row,"connection_id"),row.getString("origin_hash"),row.getString("connection_name"),decode(row.getString("observation_json"),Observation.class),
                row.getString("presence"),State.valueOf(row.getString("state")),row.getLong("revision"),SqlDialect.uuid(row,"request_id"),row.getString("selected_by"),time(row,"selected_at"),
                decode(row.getString("report_json"),Report.class),row.getString("error"),time(row,"discovered_at"),time(row,"observed_at"));
    }
    private static Instant time(ResultSet row,String name)throws SQLException {Timestamp stamp=row.getTimestamp(name);return stamp==null?null:stamp.toInstant();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new ConsoleFailure("发现记录保存失败");}}
    private <T>T decode(String value,Class<T> type){if(value==null)return null;try{return json.readValue(value,type);}catch(Exception failure){throw new ConsoleFailure("发现记录读取失败");}}
}
