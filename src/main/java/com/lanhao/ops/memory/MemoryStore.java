package com.lanhao.ops.memory;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.persistence.*;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
@DependsOn("flywayInitializer")
public class MemoryStore {
    public enum Status { DRAFT, VERIFIED, ARCHIVED }
    public record Input(String title,String problem,String cause,String solution,String verification,String tags,Status status){}
    public record Entry(UUID id,long revision,String title,String problem,String cause,String solution,String verification,
            String tags,Status status,String environment,String sourceType,String sourceRef,Instant createdAt,Instant updatedAt,String scopeKey) {
        public MemoryReference reference(){return new MemoryReference(id,title,sourceType,revision);}
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HikariDataSource owned;
    private final Redactor redactor;
    private final String environment;
    public enum FeedbackKind { HELPFUL, NOT_APPLICABLE, OUTDATED }
    public record Feedback(UUID requestId,UUID memoryId,long revision,UUID turnId,String scopeKey,FeedbackKind kind,String note,Instant createdAt) {}

    public List<Feedback> feedbackForTurn(UUID turnId) {
        return jdbc.query("SELECT * FROM agent_memory_feedback WHERE turn_id=? ORDER BY created_at,request_id",(rs,i)->readFeedback(rs),turnId.toString());
    }
    public Feedback feedback(UUID requestId,UUID memoryId,long revision,UUID turnId,String scope,FeedbackKind kind,String note) {
        validateScope(scope);
        if(requestId==null||memoryId==null||turnId==null||kind==null||revision<1||"UNBOUND".equals(scope))
            throw new IllegalArgumentException("反馈须关联有明确范围和版本的记忆引用");
        String cleanNote=text(note,500);
        return tx.execute(status->{
            var existing=jdbc.query("SELECT * FROM agent_memory_feedback WHERE request_id=?",(rs,i)->readFeedback(rs),requestId.toString());
            if(!existing.isEmpty()) {
                var old=existing.getFirst();
                if(!old.memoryId().equals(memoryId)||old.revision()!=revision||!old.turnId().equals(turnId)||!old.scopeKey().equals(scope)||old.kind()!=kind||!old.note().equals(cleanNote))
                    throw new IllegalStateException("同一反馈请求不能变更内容");
                return old;
            }
            var entry=get(memoryId);
            if(!entry.scopeKey().equals(scope))throw new IllegalArgumentException("反馈与记忆来源范围不一致");
            if(entry.revision()!=revision||entry.status()!=Status.VERIFIED)throw new IllegalStateException("引用的记忆已变化或已退回待确认，请重新调查后反馈");
            if(!feedbackForTurn(turnId).stream().filter(f->f.memoryId().equals(memoryId)).toList().isEmpty())
                throw new IllegalStateException("本轮已反馈该记忆，请勿重复提交");
            long now=System.currentTimeMillis();
            // 过期只降级，不删除历史正文。人工修订和重新验证沿用已有乐观锁流程。
            if(kind==FeedbackKind.OUTDATED&&jdbc.update("UPDATE agent_memory SET status='DRAFT',revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status='VERIFIED'",now,memoryId.toString(),revision)!=1)
                throw new IllegalStateException("记忆已变化，请刷新后重试");
            jdbc.update("INSERT INTO agent_memory_feedback(request_id,memory_id,memory_revision,turn_id,scope_key,kind,note,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    requestId.toString(),memoryId.toString(),revision,turnId.toString(),scope,kind.name(),cleanNote,now);
            return new Feedback(requestId,memoryId,revision,turnId,scope,kind,cleanNote,Instant.ofEpochMilli(now));
        });
    }
    private Feedback readFeedback(ResultSet row)throws SQLException {
        return new Feedback(UUID.fromString(row.getString("request_id")),UUID.fromString(row.getString("memory_id")),row.getLong("memory_revision"),
                UUID.fromString(row.getString("turn_id")),row.getString("scope_key"),FeedbackKind.valueOf(row.getString("kind")),row.getString("note"),Instant.ofEpochMilli(row.getLong("created_at")));
    }
    public MemoryStore(JdbcTemplate primary,Redactor redactor,AgentProperties config,
            @Value("${agent.memory.file:./data/agent-memory.db}") String file)throws Exception {
        var primarySource=Objects.requireNonNull(primary.getDataSource());
        boolean sqlite=new SqlDialect(primary).sqlite();
        owned=sqlite?null:SqliteConfiguration.open(Path.of(file),"agent-memory");
        var source=sqlite?primarySource:owned;
        this.redactor=redactor;this.environment=config.mode().name();
        jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(5);
        new SqlDialect(jdbc);
        tx=new TransactionTemplate(new JdbcTransactionManager(source));
        try {
            Flyway.configure().dataSource(source).locations("classpath:db/memory").table("agent_memory_schema_history")
                    .baselineOnMigrate(true).baselineVersion("0").load().migrate();
            jdbc.queryForObject("SELECT count(*) FROM agent_memory_fts",Integer.class);
        }catch(RuntimeException failure){if(owned!=null)owned.close();throw failure;}
    }
    @PreDestroy public void close(){if(owned!=null)owned.close();}
    public List<Entry> bySourceRefs(List<String> refs) {
        if(refs.isEmpty())return List.of();
        if(refs.size()>102)throw new IllegalArgumentException("来源数量超过查询预算");
        return jdbc.query("SELECT * FROM agent_memory WHERE source_ref IN ("+String.join(",",Collections.nCopies(refs.size(),"?"))+") ORDER BY updated_at DESC,id LIMIT 50",
                (rs,i)->read(rs),refs.toArray());
    }
    public Optional<Entry> reflection(UUID sourceRef) {
        return jdbc.query("SELECT * FROM agent_memory WHERE source_type='REFLECTION' AND source_ref=? ORDER BY created_at DESC LIMIT 1",
                (rs,i)->read(rs),sourceRef.toString()).stream().findFirst();
    }
    public Entry get(UUID id) {
        return jdbc.query("SELECT * FROM agent_memory WHERE id=?",(rs,i)->read(rs),id.toString())
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"记忆不存在"));
    }
    public Entry create(String sourceKey,String sourceType,String sourceRef,Input input) {
        return create(sourceKey,sourceType,sourceRef,input,environment.equals("DEMO")?"DEMO":"UNBOUND");
    }
    public Entry create(String sourceKey,String sourceType,String sourceRef,Input input,String scope) {
        validateScope(scope);
        String identityKey=("UNBOUND".equals(scope)?environment:scope)+":"+sourceKey;
        Input clean=clean(input); UUID id=UUID.nameUUIDFromBytes(identityKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return tx.execute(status->{
            var existing=jdbc.query("SELECT * FROM agent_memory WHERE source_key=?",(rs,i)->read(rs),identityKey);
            if(!existing.isEmpty()) {
                if(!existing.getFirst().scopeKey().equals(scope))throw new IllegalStateException("记忆来源范围不可通过重放更改");
                return existing.getFirst(); // 保留编辑及归档决定，重放不得重新激活。
            }
            long now=System.currentTimeMillis();
            jdbc.update("INSERT INTO agent_memory(id,source_key,title,problem,cause,solution,verification,tags,status,environment,source_type,source_ref,revision,title_terms,body_terms,created_at,updated_at,scope_key) "
                            +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?)",id.toString(),identityKey,clean.title(),clean.problem(),clean.cause(),
                    clean.solution(),clean.verification(),clean.tags(),clean.status().name(),environment,sourceType,sourceRef,
                    SearchTerms.index(clean.title()+" "+clean.tags()),terms(clean),now,now,scope);
            return get(id);
        });
    }
    public Entry update(UUID id,long revision,Input input) {
        Input clean=clean(input);
        return tx.execute(status->{
            if(jdbc.update("UPDATE agent_memory SET title=?,problem=?,cause=?,solution=?,verification=?,tags=?,status=?,revision=revision+1,title_terms=?,body_terms=?,updated_at=? "
                            +"WHERE id=? AND revision=?",clean.title(),clean.problem(),clean.cause(),clean.solution(),clean.verification(),clean.tags(),
                    clean.status().name(),SearchTerms.index(clean.title()+" "+clean.tags()),terms(clean),System.currentTimeMillis(),id.toString(),revision)!=1)
                throw new IllegalStateException("记忆已被更新，请刷新后重试");
            return get(id);
        });
    }
    public List<Entry> search(String query,Status status,int limit) {
        return search(query,status,limit,null);
    }
    public List<Entry> search(String query,Status status,int limit,String scope) {
        if(query==null||query.length()>8000||limit<1||limit>50)throw new IllegalArgumentException("记忆查询参数无效");
        if(scope!=null)validateScope(scope);
        List<Object> args=new ArrayList<>();args.add(scope==null?environment:scope);
        String filter=(scope==null?"m.environment=?":"m.scope_key=?")+" AND "+(status==null?"m.status!='ARCHIVED'":"m.status=?");
        if(status!=null)args.add(status.name());
        if(status==Status.VERIFIED)filter+=" AND (m.source_type!='RUN_COMPONENT' OR EXISTS (SELECT 1 FROM agent_memory parent WHERE parent.source_type='RUN' AND parent.source_ref=m.source_ref AND parent.status='VERIFIED'))";
        String sql;
        if(query.isBlank()) sql="SELECT m.* FROM agent_memory m WHERE "+filter+" ORDER BY m.updated_at DESC,m.id LIMIT ?";
        else {
            String match=SearchTerms.match(query); if(match.isEmpty())return List.of();
            sql="SELECT m.* FROM agent_memory m JOIN agent_memory_fts ON agent_memory_fts.rowid=m.rowid WHERE "+filter
                    +" AND agent_memory_fts MATCH ? ORDER BY CASE m.status WHEN 'VERIFIED' THEN 0 ELSE 1 END,bm25(agent_memory_fts,3.0,1.0),m.updated_at DESC,m.id LIMIT ?";
            args.add(match);
        }
        args.add(limit);return jdbc.query(sql,(rs,i)->read(rs),args.toArray());
    }
    public Entry capture(Run run) {
        if(!run.verifiedRecovery())
            throw new IllegalArgumentException("只归档已经验证成功的恢复任务");
        Diagnosis d=run.plan.diagnosis();
        Evidence failureEvidence=run.diagnosisEvidence==null?run.evidence:run.diagnosisEvidence;
        String title="已恢复："+Objects.toString(d.cause(),"部署暂时故障");
        String problem="本次诊断假设："+Objects.toString(d.cause(),"");
        if(failureEvidence!=null)problem="故障码："+failureEvidence.reason()+"\n"+problem+"\n"
                +failureEvidence.items().stream().filter(EvidenceItem::decisive).map(item->item.reason()+" "+item.message())
                .collect(java.util.stream.Collectors.joining("\n"));
        String tags=run.targets.stream().map(t->t.kind().name()+" "+t.namespace()).distinct().collect(java.util.stream.Collectors.joining(" "));
        Entry saved=create("RUN:"+run.id,"RUN",run.id.toString(),new Input(cut(title,120),cut(problem,4000),
                cut("经过恢复验收的故障假设，尚不等于独立证明根因："+Objects.toString(d.cause(),""),4000),cut("已受理并验证的动作："+run.plan.action()+"\n手册："+run.plan.runbookId()+" v"+run.plan.runbookVersion()
                +"\n排查步骤：\n"+String.join("\n",d.checks())+"\n修复思路：\n"+String.join("\n",d.repairSteps()),
                4000),cut("任务已通过受控恢复验收；连续通过 "+run.stableCount+" 次，恢复次数 "+run.attempts+"。\n"+d.verification(),4000),
                cut(tags,300),Status.VERIFIED),com.lanhao.ops.domain.DeploymentIdentity.scope(run));
        // 多空间发布只投递本空间的 CR 证据和动作引用，不泄露其他空间的诊断正文或日志。
        var namespaces=run.targets.stream().map(Target::namespace).distinct().sorted().toList();
        if(saved.status()==Status.VERIFIED&&"KUBERNETES".equals(run.environment)&&namespaces.size()>1)for(String namespace:namespaces) {
            var targets=run.targets.stream().filter(t->namespace.equals(t.namespace())).toList();
            var uids=targets.stream().map(Target::uid).collect(java.util.stream.Collectors.toSet());
            String evidence=failureEvidence==null?"未保存该空间独立证据":failureEvidence.items().stream()
                    .filter(item->uids.contains(item.resourceUid())).map(item->item.reason()+" "+item.message()).collect(java.util.stream.Collectors.joining("\n"));
            create("RUN:"+run.id+":NAMESPACE:"+namespace,"RUN_COMPONENT",run.id.toString(),new Input(
                    cut("命名空间发布恢复："+namespace,120),cut("目标："+targets+"\n"+evidence,4000),
                    "本发布在受控恢复后收敛；本空间的独立根因尚未被证明。",
                    "关联恢复账本："+run.id+"；已受理动作："+run.recovery.id()+"；手册："+run.plan.runbookId()+" v"+run.plan.runbookVersion()
                            +"。下次须重新核对当前证据、版本、批准和预算，历史记录不授予执行权限。",
                    "整体发布验收覆盖本空间目标；连续通过 "+run.stableCount+" 次。此摘要未包含其他空间的诊断或日志。",
                    cut(namespace+" "+targets.stream().map(t->t.kind().name()).distinct().collect(java.util.stream.Collectors.joining(" ")),300),Status.VERIFIED),
                    com.lanhao.ops.domain.DeploymentIdentity.realScope(run.clusterApiUrl,namespace));
        }
        return saved;
    }
    private Input clean(Input input) {
        if(input==null||input.status()==null)throw new IllegalArgumentException("记忆参数缺失");
        String title=text(input.title(),120),problem=text(input.problem(),4000),cause=text(input.cause(),4000),solution=text(input.solution(),4000),verification=text(input.verification(),4000),tags=text(input.tags(),300);
        if(title.isBlank()||problem.isBlank()||solution.isBlank()||input.status()==Status.VERIFIED&&verification.isBlank())
            throw new IllegalArgumentException("填写标题、问题和解决思路；已验证记忆还需填写验证依据");
        return new Input(title,problem,cause,solution,verification,tags,input.status());
    }
    private String text(String value,int max){if(value!=null&&value.length()>max)throw new IllegalArgumentException("记忆字段过长");return cut(redactor.clean(Objects.toString(value,"").trim()),max);}
    private static String cut(String value,int max){return value.substring(0,Math.min(value.length(),max));}
    private static String terms(Input input){return SearchTerms.index(input.problem()+" "+input.cause()+" "+input.solution()+" "+input.verification());}
    private Entry read(ResultSet row)throws SQLException {
        return new Entry(UUID.fromString(row.getString("id")),row.getLong("revision"),row.getString("title"),row.getString("problem"),row.getString("cause"),row.getString("solution"),
                row.getString("verification"),row.getString("tags"),Status.valueOf(row.getString("status")),row.getString("environment"),row.getString("source_type"),row.getString("source_ref"),
                Instant.ofEpochMilli(row.getLong("created_at")),Instant.ofEpochMilli(row.getLong("updated_at")),row.getString("scope_key"));
    }
    private static void validateScope(String scope) {
        if(scope==null||!scope.matches("DEMO|UNBOUND|KUBERNETES:[a-f0-9]{64}"))throw new IllegalArgumentException("记忆来源范围无效");
    }
}
