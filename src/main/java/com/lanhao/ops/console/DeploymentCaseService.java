package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.OpsProperties;
import com.lanhao.ops.domain.DeploymentIdentity;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.persistence.RunStore;
import com.lanhao.ops.persistence.SqlDialect;
import com.lanhao.ops.port.DeploymentPort;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 以发现记录作为调查案例；平台核验后关联唯一恢复账本，不授予页面连接写权限。 */
@Service
public class DeploymentCaseService {
    public record RecoveryInput(@Min(0) long revision,
            @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") @DecimalMax("9223372036854775807") String provisioningId,
            @NotBlank @Size(max=100) String deploymentVersion,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String verificationProfile) {}
    public record PlatformInput(@Min(0) long revision,
            @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") @DecimalMax("9223372036854775807") String provisioningId) {}
    public record CaseView(UUID id,DiscoveryStore.Task investigation,List<ChatStore.Session> sessions,Run recovery,
            List<JsonNode> commandExecutions,List<com.lanhao.ops.memory.MemoryStore.Entry> reflections,String learningWarning,String recoveryBlockedReason,List<String> verificationProfiles) {}
    private final DiscoveryStore discovery;
    private final DiscoveryGateway gateway;
    private final RunStore runs;
    private final DeploymentPort deployment;
    private final AgentProperties config;
    private final OpsProperties ops;
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ChatStore chats;
    private final com.lanhao.ops.memory.MemoryStore memories;

    public DeploymentCaseService(DiscoveryStore discovery,DiscoveryGateway gateway,RunStore runs,DeploymentPort deployment,
            AgentProperties config,OpsProperties ops,JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,ChatStore chats,com.lanhao.ops.memory.MemoryStore memories) {
        this.discovery=discovery;this.gateway=gateway;this.runs=runs;this.deployment=deployment;this.config=config;this.ops=ops;
        this.jdbc=jdbc;db=new SqlDialect(jdbc);this.tx=tx;this.json=json;this.chats=chats;this.memories=memories;
    }
    public CaseView view(UUID id) {
        var task=discovery.task(id);var run=linked(id).orElse(null);
        var sessions=chats.forCase(id,run==null?null:run.id);
        List<String> sources=new ArrayList<>();sources.add(id.toString());if(run!=null)sources.add(run.id.toString());
        sources.addAll(jdbc.query("SELECT t.id FROM agent_chat_turn t JOIN agent_chat_session s ON s.id=t.session_id WHERE s.discovery_id=? OR s.run_id=? ORDER BY t.created_at DESC,t.id LIMIT 100",
                (rs,i)->rs.getString(1),id,run==null?null:run.id));
        List<JsonNode> commands=run==null?List.of():jdbc.query(db.sql("SELECT payload@text@,state FROM agent_command_execution WHERE run_id=? ORDER BY batch_id,ordinal LIMIT 100"),
                (rs,i)->{try{var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(rs.getString(1));node.put("state",rs.getString(2));return node;}
                    catch(Exception failure){throw new ConsoleFailure("命令审计不可读取");}},run.id);
        List<com.lanhao.ops.memory.MemoryStore.Entry> reflections;String warning="";
        try{reflections=memories.bySourceRefs(sources);}
        catch(RuntimeException failure){reflections=List.of();warning="案例总结暂不可读取；主任务及恢复操作仍可使用，请检查记忆存储后刷新。";}
        return new CaseView(id,task,sessions,run,commands,reflections,warning,blocked(task),ops.verificationProfiles().keySet().stream().sorted().toList());
    }
    public Optional<Run> linked(UUID id) {
        return jdbc.query("SELECT run_id FROM agent_case_recovery WHERE discovery_id=?",(rs,i)->SqlDialect.uuid(rs,1),id)
                .stream().findFirst().flatMap(runs::find);
    }
    public StartRequest preview(UUID id,RecoveryInput input) {
        var task=discovery.task(id);check(task,input);
        var connection=discovery.connection(task);
        gateway.read(connection,task.observation().target());
        // 完整目标来自已认证的平台；不能只用一个被选中的 CR 冒充整条发布。
        String key="case_"+UUID.nameUUIDFromBytes((input.provisioningId()+"\n"+input.deploymentVersion()).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
        Run probe=new Run(new StartRequest(key,input.provisioningId(),List.of(),input.deploymentVersion(),input.verificationProfile()),Instant.now());
        var targets=deployment.discover(probe);
        if(targets.isEmpty()||targets.size()>10||!targets.contains(task.observation().target()))
            throw new ContextMismatch("平台完整目标未就绪或不包含所选资源的 UID / 代次，不能转交恢复");
        if(targets.stream().anyMatch(t->!config.namespaces().contains(t.namespace())))throw new ContextMismatch("平台目标包含执行身份未授权的命名空间");
        var request=new StartRequest(key,input.provisioningId(),targets,input.deploymentVersion(),input.verificationProfile());
        deployment.validateRegistration(request);
        discovery.connection(task);
        return request;
    }
    public StartRequest resolve(UUID id,PlatformInput input) {
        var task=discovery.task(id);String reason=blocked(task);
        if(!reason.isBlank())throw new ContextMismatch(reason);
        if(task.revision()!=input.revision())throw new ContextMismatch("调查记录已变化，请刷新后读取平台身份");
        var registration=deployment.registration(input.provisioningId());
        return preview(id,new RecoveryInput(input.revision(),input.provisioningId(),registration.deploymentVersion(),registration.verificationProfile()));
    }
    public Run handoff(UUID id,RecoveryInput input,String actor) {
        var previous=linked(id);
        if(previous.isPresent())return sameRequest(previous.get(),input);
        StartRequest request=preview(id,input);
        return tx.execute(status->{
            var task=discovery.task(id);
            jdbc.queryForList(db.sql("SELECT id FROM agent_console_connection WHERE id=?@lock@"),task.connectionId());
            jdbc.queryForList(db.sql("SELECT id FROM agent_discovered_task WHERE id=?@lock@"),id);
            var replay=linked(id);if(replay.isPresent())return sameRequest(replay.get(),input);
            check(discovery.task(id),input);
            // 同一发布已由事件或其他案例注册时复用，终态也不能重置预算。
            var existing=jdbc.query(db.sql("SELECT payload@text@ FROM agent_run WHERE provisioning_id=? AND payload->>'deploymentVersion'=?@lock@"),
                    (rs,i)->{try{return json.readValue(rs.getString(1),Run.class);}catch(Exception failure){throw new ConsoleFailure("恢复账本不可读取");}},input.provisioningId(),input.deploymentVersion());
            Run run=existing.isEmpty()?runs.create(request):sameRequest(existing.getFirst(),input);
            if(!"KUBERNETES".equals(run.environment)||!DeploymentIdentity.endpoint(run.clusterApiUrl).equals(DeploymentIdentity.endpoint(deployment.clusterApiUrl()))
                    ||!new HashSet<>(run.targets).equals(new HashSet<>(request.targets())))
                throw new ContextMismatch("既有恢复账本与当前执行环境或平台完整目标不一致，不能重新分配预算");
            jdbc.update("INSERT INTO agent_case_recovery(discovery_id,run_id,selected_by) VALUES(?,?,?)",id,run.id,actor);
            return run;
        });
    }
    private Run sameRequest(Run run,RecoveryInput input) {
        if(!run.provisioningId.equals(input.provisioningId())||!Objects.equals(run.deploymentVersion,input.deploymentVersion())
                ||!Objects.equals(run.verificationProfile,input.verificationProfile()))throw new ContextMismatch("案例已关联恢复账本，不可更换平台任务、版本或验收目录");
        return run;
    }
    private void check(DiscoveryStore.Task task,RecoveryInput input) {
        String reason=blocked(task);if(!reason.isBlank())throw new ContextMismatch(reason);
        if(task.revision()!=input.revision())throw new ContextMismatch("调查记录已变化，请刷新后重新预览");
        if(!ops.verificationProfiles().containsKey(input.verificationProfile()))throw new ContextMismatch("验收目录不在服务端受权清单内");
    }
    private String blocked(DiscoveryStore.Task task) {
        if(config.mode()!=AgentProperties.Mode.KUBERNETES)return "当前为 DEMO 模式，真实调查不能转交模拟恢复；请配置独立 Kubernetes 执行身份和平台契约";
        if(!ops.strictPlatformContract())return "转交恢复需要严格平台契约，不能降级使用旧重试接口";
        if(!task.presence().equals("PRESENT"))return "资源不在当前部署清单，请同步后选择最新记录";
        try {
            var connection=discovery.connection(task);
            if(!DeploymentIdentity.endpoint(connection.apiUrl()).equals(DeploymentIdentity.endpoint(deployment.clusterApiUrl())))return "页面连接与独立恢复执行身份不属于同一集群";
            if(!config.namespaces().contains(connection.namespace()))return "调查命名空间未授予恢复执行身份";
        }catch(ConsoleFailure failure){return failure.getMessage();}
        if(ops.verificationProfiles().isEmpty())return "请先配置服务端应用验收目录";
        return "";
    }
}
