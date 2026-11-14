package com.lanhao.ops.console;

import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.memory.LearningOutbox;
import com.lanhao.ops.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.lanhao.ops.console.ProvisioningModel.*;

/** 调度只复用调查队列和案例转交服务，不模拟页面或另建恢复执行器。 */
@Service
public class ProvisioningAutonomy {
    private final ProvisioningStore watches;
    private final AutonomyPolicies policies;
    private final AutonomyGuard guard;
    private final DiscoveryStore discovery;
    private final DeploymentCaseService cases;
    private final RunStore runs;
    private final ProvisioningProperties options;
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final TransactionTemplate tx;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public ProvisioningAutonomy(ProvisioningStore watches,AutonomyPolicies policies,AutonomyGuard guard,DiscoveryStore discovery,DeploymentCaseService cases,RunStore runs,ProvisioningProperties options,JdbcTemplate jdbc,TransactionTemplate tx,com.fasterxml.jackson.databind.ObjectMapper json) {
        this.watches=watches;this.policies=policies;this.guard=guard;this.discovery=discovery;this.cases=cases;this.runs=runs;this.options=options;this.jdbc=jdbc;db=new SqlDialect(jdbc);this.tx=tx;this.json=json;
    }
    public void tick() {
        if(!options.enabled())return;
        // 不明受理结果的终态仍占据槽位，不能通过换任务掩盖未知外部副作用。
        jdbc.update("DELETE FROM agent_autonomy_slot WHERE run_id IN (SELECT id FROM agent_run WHERE state IN ('SUCCEEDED','FAILED','CANCELLED','MANUAL_REQUIRED') AND (payload->>'actionKey' IS NULL OR payload->>'recovery' IS NOT NULL))");
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(40).toNanos();int count=0;
        for(var trigger:pending()) {
            if(count++>=10||System.nanoTime()>deadline)break;
            advance(trigger);
        }
        for(var watch:watches.candidates()) {
            if(count++>=10||System.nanoTime()>deadline)break;
            var selected=policies.matching(watch);
            if(selected.isEmpty()||watch.paused())continue;
            Policy policy=selected.get();
            try {
                guard.check(watch,policy,true);watch=watches.get(watch.id());
                String anomaly=watches.anomaly(watch,policy,Instant.now());
                if(anomaly.isBlank()||anomaly.equals("OBSERVATION_DEGRADED")||policy.scope().level()==Level.OBSERVE)continue;
                enqueue(watch,policy,anomaly);
            }catch(RuntimeException failure) {
                watches.warning(watch.id(),failure instanceof ConsoleFailure?failure.getMessage():"托管预检未完成，保留已有事实，未发起新动作");
            }
        }
    }
    private void enqueue(Watch watch,Policy policy,String anomaly) {
        tx.executeWithoutResult(s->{
            jdbc.update("INSERT INTO agent_provisioning_source(platform_id) VALUES(?) ON CONFLICT DO NOTHING",watch.stage().platformInstanceId());
            jdbc.queryForList(db.sql("SELECT platform_id FROM agent_provisioning_source WHERE platform_id=?@lock@"),watch.stage().platformInstanceId());
            jdbc.queryForList(db.sql("SELECT id FROM agent_autonomy_policy WHERE id=?@lock@"),policy.id());
            jdbc.queryForList(db.sql("SELECT id FROM agent_provisioning_watch WHERE id=?@lock@"),watch.id());
            guard.check(watches.get(watch.id()),policy,false);
            long now=System.currentTimeMillis();
            int diagnosed=jdbc.queryForObject("SELECT count(*) FROM agent_diagnosis_trigger WHERE watch_id=?",Integer.class,watch.id());
            diagnosed+=jdbc.queryForObject("SELECT count(*) FROM agent_run_diagnosis d JOIN agent_autonomy_run a ON a.run_id=d.run_id WHERE a.watch_id=?",Integer.class,watch.id());
            if(diagnosed>=policy.scope().maxDiagnoses())return;
            if(jdbc.queryForObject("SELECT count(*) FROM agent_diagnosis_trigger WHERE state IN ('QUEUED','INVESTIGATING')",Integer.class)>=options.queueLimit())return;
            if(jdbc.queryForObject("SELECT count(*) FROM agent_diagnosis_trigger WHERE watch_id=? AND created_at>?",Integer.class,watch.id(),now-policy.scope().cooldownSeconds()*1000L)>0)return;
            if(jdbc.queryForObject("SELECT count(*) FROM agent_diagnosis_trigger WHERE policy_id=? AND state='FAILED' AND created_at>?",Integer.class,policy.id(),now-300000)>=3)return;
            String fingerprint=watches.fingerprint(watch);
            UUID id=UUID.randomUUID(),request=UUID.randomUUID();
            if(watch.stage().resources().isEmpty()) {
                int inserted=insert(id,watch,policy,fingerprint,null,request,"MANUAL",anomaly+"：平台前置阶段异常，尚无 CR；错误码 "+Objects.toString(watch.stage().errorCode(),"无")+"；"+watch.stage().summary());
                if(inserted==1) {
                    var evidence=new Evidence(Instant.now(),false,true,fingerprint,"平台阶段 "+watch.stage().phase(),List.of(watch.stage().summary()),List.of(),List.of(new CollectionIssue("PLATFORM",watch.stage().provisioningId(),"NO_CR_TARGET")));
                    var plan=new Plan(id.toString(),Action.MANUAL_REPAIR,new Diagnosis("平台前置阶段异常，根因待核验",List.of("核对平台步骤、错误码及阻塞依赖："+watch.stage().dependencies()),List.of("由平台负责人核验前置条件；未提供完整恢复目标时保持人工处理"),"平台阶段推进且完整目标建立后重新采证","PLATFORM_STAGE_RULES"),false,Instant.now());
                    LearningOutbox.enqueue(jdbc,json,LearningOutbox.investigation(watch.id(),request,"UNBOUND",evidence,plan));
                }
                return;
            }
            var source=discovery.source(policy.scope().connectionId());
            if(source.checkedAt()==null||source.checkedAt().isBefore(Instant.now().minusSeconds(90))||!source.error().isBlank())return;
            var ids=jdbc.query("SELECT id FROM agent_discovered_task WHERE connection_id=? AND presence='PRESENT' AND state NOT IN ('IGNORED','QUEUED','RUNNING') ORDER BY observed_at DESC LIMIT 200",(rs,i)->SqlDialect.uuid(rs,1),policy.scope().connectionId());
            var targets=ids.stream().map(discovery::task).filter(t->watch.stage().resources().contains(t.observation().target())).toList();
            if(targets.isEmpty())return;
            var task=targets.stream().filter(t->t.observation().evidence().failed()).findFirst().orElse(targets.getFirst());
            if(insert(id,watch,policy,fingerprint,task.id(),request,"QUEUED",anomaly)==1)discovery.select(task.id(),task.revision(),request,"autonomy:"+policy.id()+":"+policy.revision());
        });
    }
    private int insert(UUID id,Watch watch,Policy policy,String fingerprint,UUID discovery,UUID request,String state,String detail) {
        return jdbc.update("INSERT INTO agent_diagnosis_trigger(id,watch_id,policy_id,policy_revision,fingerprint,discovery_id,request_id,state,detail,created_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",id,watch.id(),policy.id(),policy.revision(),fingerprint,discovery,request,state,detail,System.currentTimeMillis());
    }
    public List<Trigger> triggers(UUID watchId) {
        watches.get(watchId);return jdbc.query("SELECT * FROM agent_diagnosis_trigger WHERE watch_id=? ORDER BY created_at DESC LIMIT 100",(rs,i)->new Trigger(SqlDialect.uuid(rs,"id"),SqlDialect.uuid(rs,"watch_id"),SqlDialect.uuid(rs,"policy_id"),rs.getLong("policy_revision"),rs.getString("fingerprint"),SqlDialect.uuid(rs,"discovery_id"),SqlDialect.uuid(rs,"request_id"),rs.getString("state"),rs.getString("detail"),Instant.ofEpochMilli(rs.getLong("created_at"))),watchId);
    }
    private List<Trigger> pending() {
        return jdbc.query("SELECT * FROM agent_diagnosis_trigger WHERE state IN ('QUEUED','INVESTIGATING') ORDER BY created_at,id LIMIT 100",(rs,i)->new Trigger(SqlDialect.uuid(rs,"id"),SqlDialect.uuid(rs,"watch_id"),SqlDialect.uuid(rs,"policy_id"),rs.getLong("policy_revision"),rs.getString("fingerprint"),SqlDialect.uuid(rs,"discovery_id"),SqlDialect.uuid(rs,"request_id"),rs.getString("state"),rs.getString("detail"),Instant.ofEpochMilli(rs.getLong("created_at"))));
    }
    private void advance(Trigger trigger) {
        try {
            var task=discovery.task(trigger.discoveryId());
            if(!trigger.requestId().equals(task.requestId())){finish(trigger,"SUPERSEDED","调查请求已由人工或新证据替换，旧结果不采纳");return;}
            if(task.state()==DiscoveryStore.State.FAILED){finish(trigger,"FAILED",task.error());return;}
            var policy=policies.get(trigger.policyId());var watch=watches.get(trigger.watchId());
            if(policy.revision()!=trigger.policyRevision())throw new ContextMismatch("策略版本已变化，停止原自动转交");
            guard.check(watch,policy,false);
            if(!watches.fingerprint(watch).equals(trigger.fingerprint())){finish(trigger,"SUPERSEDED","阶段证据已变化，未采纳旧调查");return;}
            if(task.state()!=DiscoveryStore.State.DONE)return;
            if(policy.scope().level()==Level.DIAGNOSE){finish(trigger,"DONE","只读调查完成；策略不授予恢复动作");return;}
            if(task.report()==null||task.report().plan().action()!=Action.RETRY_TRANSIENT){finish(trigger,"MANUAL","当前证据未命中已发布低风险手册，保留方案转人工");return;}
            guard.check(watch,policy,true);watch=watches.get(watch.id());
            String anomaly=watches.anomaly(watch,policy,Instant.now());
            if(!Instant.now().isBefore(watch.stage().provisioningStartedAt().plusSeconds(policy.scope().totalSeconds()))){finish(trigger,"MANUAL","发布总时限耗尽，保留调查结果，不启动新的恢复任务");return;}
            if(anomaly.isBlank()||!watches.fingerprint(watch).equals(trigger.fingerprint())){finish(trigger,"SUPERSEDED","平台阶段已自然推进，停止旧故障恢复");return;}
            var registration=cases.resolve(task.id(),new DeploymentCaseService.PlatformInput(task.revision(),watch.stage().provisioningId()));
            if(!registration.deploymentVersion().equals(watch.stage().deploymentVersion())||!new HashSet<>(registration.targets()).equals(new HashSet<>(watch.stage().resources())))throw new ContextMismatch("严格恢复快照与阶段资源集合不一致");
            final Watch frozen=watch;
            tx.executeWithoutResult(s->{
                jdbc.queryForList(db.sql("SELECT platform_id FROM agent_provisioning_source WHERE platform_id=?@lock@"),frozen.stage().platformInstanceId());
                jdbc.queryForList(db.sql("SELECT id FROM agent_autonomy_policy WHERE id=?@lock@"),policy.id());
                jdbc.queryForList(db.sql("SELECT id FROM agent_provisioning_watch WHERE id=?@lock@"),frozen.id());
                guard.check(watches.get(frozen.id()),policy,false);
                String state=jdbc.queryForObject("SELECT state FROM agent_diagnosis_trigger WHERE id=?",String.class,trigger.id());
                if(!Set.of("QUEUED","INVESTIGATING").contains(state))return;
                var run=cases.handoff(task.id(),new DeploymentCaseService.RecoveryInput(task.revision(),registration.provisioningId(),registration.deploymentVersion(),registration.verificationProfile()),"autonomy:"+policy.id()+":"+policy.revision());
                if(!run.state.terminal()) {
                    jdbc.update("INSERT INTO agent_autonomy_run(run_id,watch_id,policy_id,policy_revision) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",run.id,frozen.id(),policy.id(),policy.revision());
                    var owner=jdbc.queryForObject("SELECT watch_id FROM agent_autonomy_run WHERE run_id=?",String.class,run.id);
                    if(!frozen.id().toString().equals(owner))throw new ContextMismatch("此发布已绑定其他站点托管账本");
                    jdbc.update("INSERT INTO agent_autonomy_slot(cluster_api,run_id) VALUES(?,?) ON CONFLICT DO NOTHING",policy.clusterApiUrl(),run.id);
                    if(!jdbc.queryForObject("SELECT run_id FROM agent_autonomy_slot WHERE cluster_api=?",String.class,policy.clusterApiUrl()).equals(run.id.toString()))throw new ContextMismatch("集群恢复配额已满，当前方案保留供人工处理");
                }
                jdbc.update("UPDATE agent_provisioning_watch SET run_id=?,revision=revision+1 WHERE id=?",run.id,frozen.id());
                finish(trigger,"HANDED_OFF",run.state.terminal()?"已关联原终态账本，原发布预算不重置":"已转交唯一恢复账本；批准和执行仍受当前策略及严格门禁控制");
            });
        }catch(RuntimeException failure){finish(trigger,"FAILED",failure instanceof ConsoleFailure?failure.getMessage():"自动转交预检失败，未创建新恢复动作，请查看调查详情");}
    }
    private void finish(Trigger trigger,String state,String detail){jdbc.update("UPDATE agent_diagnosis_trigger SET state=?,detail=? WHERE id=? AND state IN ('QUEUED','INVESTIGATING')",state,detail,trigger.id());}
}
