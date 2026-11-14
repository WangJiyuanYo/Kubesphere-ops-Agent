package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.DeploymentIdentity;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.persistence.SqlDialect;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.lanhao.ops.console.ProvisioningModel.*;

/** 阶段事实是追加账本；缺页、连接故障和授权故障均不能推导部署终态。 */
@Repository
public class ProvisioningStore {
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Validator validator;
    private final ProvisioningProperties options;
    public ProvisioningStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,Validator validator,ProvisioningProperties options) {
        this.jdbc=jdbc;this.db=new SqlDialect(jdbc);this.tx=tx;this.json=json;this.validator=validator;this.options=options;
    }
    public Receipt ingest(StageEvent input,String authorizationHash) {
        if(!options.enabled()||!Objects.equals(options.platformInstanceId(),input.platformInstanceId()))
            throw new IllegalArgumentException("阶段协议未启用或平台身份不匹配");
        if(!validator.validate(input).isEmpty())throw new IllegalArgumentException("阶段事件字段无效");
        Instant now=Instant.now();
        if(input.occurredAt().isAfter(now.plusSeconds(30))||input.phaseStartedAt().isAfter(input.occurredAt())||input.provisioningStartedAt().isAfter(input.phaseStartedAt())
                ||input.terminal()&&(input.state()==PhaseState.RUNNING||input.state()==PhaseState.BLOCKED))
            throw new IllegalArgumentException("阶段事件时间无效");
        boolean mapped=input.namespace()!=null&&!input.namespace().isBlank();
        if(mapped&&(!input.namespace().matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")
                ||blank(input.projectId())||blank(input.clusterApiUrl())))throw new IllegalArgumentException("阶段目标映射不完整");
        if(!mapped&&!input.resources().isEmpty())throw new IllegalArgumentException("无命名空间时不能声明资源");
        if(mapped) {
            var uri=java.net.URI.create(input.clusterApiUrl());
            if(uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null)
                throw new IllegalArgumentException("集群身份无效");
            if(input.resources().stream().anyMatch(t->!input.namespace().equals(t.namespace()))
                    ||input.resources().stream().map(t->t.uid()).distinct().count()!=input.resources().size())throw new IllegalArgumentException("阶段资源越界或重复");
        }
        // 错误摘要只作为证据，不接受其中的动作指令或凭据。
        StageEvent event=new StageEvent(input.schemaVersion(),input.eventId(),input.platformInstanceId(),input.workspaceId(),input.siteId(),input.provisioningId(),
                input.deploymentVersion(),input.sequence(),input.phase(),input.state(),input.phaseStartedAt(),input.occurredAt(),input.completedUnits(),
                input.errorCode(),new Redactor().clean(Objects.toString(input.summary(),"")),input.dependencies()==null?List.of():List.copyOf(input.dependencies()),
                input.projectId(),mapped?DeploymentIdentity.endpoint(input.clusterApiUrl()):input.clusterApiUrl(),input.namespace(),List.copyOf(input.resources()),input.provisioningStartedAt(),input.terminal());
        String hash=hash(encode(event));
        return tx.execute(status->{
            UUID id=UUID.nameUUIDFromBytes((event.platformInstanceId()+"\n"+event.provisioningId()+"\n"+event.deploymentVersion()).getBytes(StandardCharsets.UTF_8));
            jdbc.update(db.sql("INSERT INTO agent_provisioning_watch(id,platform_id,workspace_id,site_id,provisioning_id,deployment_version,sequence,stage,created_at,phase_started_at,progress_at,observed_at) VALUES(?,?,?,?,?,?,0,@json@,?,?,?,?) ON CONFLICT DO NOTHING"),
                    id,event.platformInstanceId(),event.workspaceId(),event.siteId(),event.provisioningId(),event.deploymentVersion(),encode(event),now.toEpochMilli(),event.phaseStartedAt().toEpochMilli(),event.phaseStartedAt().toEpochMilli(),now.toEpochMilli());
            jdbc.queryForList(db.sql("SELECT id FROM agent_provisioning_watch WHERE id=?@lock@"),id);
            Watch previous=get(id);
            if(!previous.stage().workspaceId().equals(event.workspaceId())||!previous.stage().siteId().equals(event.siteId()))
                throw new ContextMismatch("同一发布不能更换站点或工作空间");
            var replay=jdbc.query("SELECT payload_hash,watch_id,status FROM agent_provisioning_stage_event WHERE platform_id=? AND event_id=?",
                    (rs,i)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},event.platformInstanceId(),event.eventId());
            if(!replay.isEmpty()) {
                if(!hash.equals(replay.getFirst()[0]))throw new ContextMismatch("事件 ID 已用于不同阶段事实");
                if(event.sequence()==previous.stage().sequence())authorize(id,authorizationHash,now);
                return new Receipt(event.eventId(),id,replay.getFirst()[2]);
            }
            long sequence=jdbc.queryForObject("SELECT sequence FROM agent_provisioning_watch WHERE id=?",Long.class,id);
            if(sequence==event.sequence()&&!sameFact(previous.stage(),event))throw new ContextMismatch("同一阶段序号包含冲突事实");
            String result=event.sequence()>sequence?"ACCEPTED":"IGNORED_OLD_SEQUENCE";
            if(event.sequence()>sequence) {
                if(sequence>0&&!previous.stage().provisioningStartedAt().equals(event.provisioningStartedAt()))throw new ContextMismatch("发布开始时间已冻结，不能通过阶段心跳重置总时限");
                if(sequence>0&&!blank(previous.stage().namespace())&&(!Objects.equals(previous.stage().namespace(),event.namespace())
                        ||!Objects.equals(previous.stage().clusterApiUrl(),event.clusterApiUrl())||!Objects.equals(previous.stage().projectId(),event.projectId())))
                    throw new ContextMismatch("发布映射已换址，必须核验新发布周期，不能采纳旧范围结果");
                boolean phaseChanged=sequence==0||!previous.stage().phase().equals(event.phase());
                boolean progress=phaseChanged||event.completedUnits()>previous.stage().completedUnits()
                        ||previous.stage().state()!=event.state();
                Instant progressAt=phaseChanged?event.phaseStartedAt():progress?event.occurredAt():previous.lastMeaningfulProgressAt();
                if(progressAt.isBefore(previous.lastMeaningfulProgressAt()))progressAt=previous.lastMeaningfulProgressAt();
                if(sequence>0&&event.occurredAt().isBefore(previous.stage().occurredAt()))throw new ContextMismatch("较新阶段序号的业务时间不能倒退");
                jdbc.update(db.sql("UPDATE agent_provisioning_watch SET sequence=?,stage=@json@,revision=revision+1,phase_started_at=?,progress_at=?,observed_at=?,warning='',authorized_at=0,authorization_hash='' WHERE id=?"),
                        event.sequence(),encode(event),phaseChanged?event.phaseStartedAt().toEpochMilli():previous.phaseStartedAt().toEpochMilli(),progressAt.toEpochMilli(),now.toEpochMilli(),id);
                authorize(id,authorizationHash,now);
            } else if(sequence==event.sequence())authorize(id,authorizationHash,now);
            jdbc.update(db.sql("INSERT INTO agent_provisioning_stage_event(platform_id,event_id,watch_id,sequence,payload_hash,payload,status,received_at) VALUES(?,?,?,?,?,@json@,?,?)"),
                    event.platformInstanceId(),event.eventId(),id,event.sequence(),hash,encode(event),result,now.toEpochMilli());
            return new Receipt(event.eventId(),id,result);
        });
    }
    private boolean sameFact(StageEvent a,StageEvent b) {
        var left=json.valueToTree(a);var right=json.valueToTree(b);
        ((com.fasterxml.jackson.databind.node.ObjectNode)left).remove("eventId");((com.fasterxml.jackson.databind.node.ObjectNode)right).remove("eventId");
        return left.equals(right);
    }
    private void authorize(UUID id,String hash,Instant now) {
        if(hash!=null&&!hash.isBlank())jdbc.update("UPDATE agent_provisioning_watch SET authorized_at=?,authorization_hash=?,observed_at=?,warning='' WHERE id=?",now.toEpochMilli(),hash,now.toEpochMilli(),id);
    }
    public List<Watch> list(int offset) {
        if(offset<0||offset>100000)throw new IllegalArgumentException("分页范围无效");
        return jdbc.query(db.sql("SELECT *,stage@text@ AS stage_json FROM agent_provisioning_watch WHERE platform_id=? ORDER BY created_at DESC,id LIMIT 30 OFFSET ?"),
                (rs,i)->read(rs),options.platformInstanceId(),offset);
    }
    public List<Watch> candidates() {
        return jdbc.query(db.sql("SELECT *,stage@text@ AS stage_json FROM agent_provisioning_watch WHERE platform_id=? AND paused=false ORDER BY observed_at,id LIMIT 100"),(rs,i)->read(rs),options.platformInstanceId());
    }
    public Watch get(UUID id) {
        return jdbc.query(db.sql("SELECT *,stage@text@ AS stage_json FROM agent_provisioning_watch WHERE id=? AND platform_id=?"),(rs,i)->read(rs),id,options.platformInstanceId())
                .stream().findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
    public List<StageEvent> timeline(UUID id) {
        get(id);return jdbc.query(db.sql("SELECT payload@text@ FROM agent_provisioning_stage_event WHERE watch_id=? AND status='ACCEPTED' ORDER BY sequence DESC LIMIT 100"),
                (rs,i)->decode(rs.getString(1),StageEvent.class),id);
    }
    public Watch pause(UUID id,long revision,boolean paused,String actor) {
        return tx.execute(s->{
            get(id);
            if(jdbc.update("UPDATE agent_provisioning_watch SET paused=?,revision=revision+1 WHERE id=? AND revision=?",paused,id,revision)!=1)throw new ContextMismatch("站点账本已变化，请刷新");
            var result=get(id);
            jdbc.update(db.sql("INSERT INTO agent_provisioning_watch_audit(id,watch_id,payload,actor,created_at) VALUES(?,?,@json@,?,?)"),UUID.randomUUID(),id,encode(Map.of("revision",result.revision(),"paused",paused)),actor,System.currentTimeMillis());
            return result;
        });
    }
    public void warning(UUID id,String warning){jdbc.update("UPDATE agent_provisioning_watch SET warning=?,authorized_at=0 WHERE id=?",warning,id);}
    public String anomaly(Watch watch,Policy policy,Instant now) {
        if(!watch.observationWarning().isBlank()||watch.authorizedAt()==null||watch.authorizedAt().isBefore(now.minusSeconds(90)))return "OBSERVATION_DEGRADED";
        if(watch.stage().state()==PhaseState.FAILED)return "PHASE_FAILED";
        if(watch.stage().state()==PhaseState.CANCELLED||watch.stage().state()==PhaseState.SUCCEEDED&&watch.stage().terminal())return "";
        if(!now.isBefore(watch.stage().provisioningStartedAt().plusSeconds(policy.scope().totalSeconds())))return "TOTAL_TIMEOUT";
        if(!now.isBefore(watch.lastMeaningfulProgressAt().plusSeconds(policy.scope().stallSeconds())))return "PHASE_STALLED";
        return "";
    }
    public String fingerprint(Watch watch) {
        var s=watch.stage();return hash(s.phase()+"\n"+s.state()+"\n"+s.terminal()+"\n"+s.completedUnits()+"\n"+s.errorCode()+"\n"+s.summary()+"\n"+encode(s.resources()));
    }
    private Watch read(ResultSet r)throws SQLException {
        return new Watch(SqlDialect.uuid(r,"id"),r.getLong("revision"),decode(r.getString("stage_json"),StageEvent.class),time(r,"created_at"),time(r,"phase_started_at"),time(r,"progress_at"),time(r,"observed_at"),time(r,"authorized_at"),r.getString("authorization_hash"),r.getString("warning"),r.getBoolean("paused"),SqlDialect.uuid(r,"run_id"));
    }
    private static Instant time(ResultSet r,String field)throws SQLException {long value=r.getLong(field);return value==0?null:Instant.ofEpochMilli(value);}
    static boolean blank(String text){return text==null||text.isBlank();}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("站点账本序列化失败");}}
    <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception failure){throw new IllegalStateException("站点账本读取失败");}}
}
