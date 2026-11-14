package com.lanhao.ops.console;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.domain.Model.Run;
import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.lanhao.ops.console.ProvisioningModel.*;

/** 自动接管的每个新副作用均复核；已受理动作的只读对账不受暂停影响。 */
@Service
public class AutonomyGuard {
    private final JdbcTemplate jdbc;
    private final ProvisioningStore watches;
    private final AutonomyPolicies policies;
    private final ProvisioningGateway gateway;
    private final ProvisioningProperties options;
    private final AgentProperties config;
    public AutonomyGuard(JdbcTemplate jdbc,ProvisioningStore watches,AutonomyPolicies policies,ProvisioningGateway gateway,ProvisioningProperties options,AgentProperties config) {
        this.jdbc=jdbc;this.watches=watches;this.policies=policies;this.gateway=gateway;this.options=options;this.config=config;
    }
    public void check(Watch watch,Policy policy,boolean refresh) {
        if(!gateway.enabled())throw new ContextMismatch("自动托管总开关已关闭");
        policies.assertCurrent(policy,watch);
        if(refresh) {
            try{
                var event=gateway.current(watch);watches.ingest(event,gateway.authorizationHash());
                if(event.sequence()!=watches.get(watch.id()).stage().sequence())throw new ContextMismatch("平台当前阶段落后于已收事实，未核验新证据授权");
            }
            catch(RuntimeException failure){watches.warning(watch.id(),"平台当前阶段无法核验，观察已降级");throw failure;}
            watch=watches.get(watch.id());
        }
        if(watch.authorizedAt()==null||watch.authorizedAt().isBefore(java.time.Instant.now().minusSeconds(90))
                ||!gateway.authorizationHash().equals(watch.authorizationHash())||!watch.observationWarning().isBlank())throw new ContextMismatch("平台授权或阶段事实已过期，停止新副作用");
        policies.assertCurrent(policy,watch);
    }
    public void investigation(DiscoveryStore.Task task) {
        var bindings=jdbc.query("SELECT watch_id,policy_id,policy_revision,fingerprint FROM agent_diagnosis_trigger WHERE discovery_id=? AND request_id=?",(rs,i)->new Object[]{SqlDialect.uuid(rs,1),SqlDialect.uuid(rs,2),rs.getLong(3),rs.getString(4)},task.id(),task.requestId());
        if(bindings.isEmpty())return;
        var b=bindings.getFirst();var policy=policies.get((UUID)b[1]);
        if(policy.revision()!=(long)b[2]||policy.scope().level()==Level.OBSERVE)throw new ContextMismatch("排队调查的策略已失效，不自动重放模型");
        check(watches.get((UUID)b[0]),policy,true);
        if(!watches.fingerprint(watches.get((UUID)b[0])).equals(b[3]))throw new ContextMismatch("排队后有效证据已变化，不采纳或重放旧调查");
    }
    public boolean automatic(Run run) {
        var binding=binding(run.id);if(binding==null)return run.plan!=null&&run.plan.automatic();
        Policy policy=policies.get((UUID)binding[1]);
        return policy.scope().level()==Level.AUTO_LOW_RISK&&options.autoLowRiskEnabled()&&config.autoRetry()&&!ProvisioningStore.blank(options.recoveryAcceptanceRef());
    }
    public void run(Run run,boolean execution) {
        var b=binding(run.id);if(b==null)return;
        Policy policy=policies.get((UUID)b[1]);Watch watch=watches.get((UUID)b[0]);
        if(policy.revision()!=(long)b[2]||policy.scope().level()==Level.OBSERVE||policy.scope().level()==Level.DIAGNOSE)throw new ContextMismatch("恢复托管策略已失效，保留原动作身份供对账");
        check(watch,policy,true);watch=watches.get(watch.id());
        if(!java.time.Instant.now().isBefore(watch.stage().provisioningStartedAt().plusSeconds(policy.scope().totalSeconds())))throw new ContextMismatch("发布总时限耗尽，保留预算转人工");
        if(!new HashSet<>(watch.stage().resources()).equals(new HashSet<>(run.targets)))throw new ContextMismatch("平台资源 UID 或代次已变化，停止原恢复");
        if(execution&&!automatic(run)) {
            String approved=jdbc.queryForObject("SELECT approved_plan_id FROM agent_autonomy_run WHERE run_id=?",String.class,run.id);
            if(run.plan==null||!run.plan.id().equals(approved))throw new ContextMismatch("当前方案须由人工批准");
        }
    }
    public void approved(Run run){jdbc.update("UPDATE agent_autonomy_run SET approved_plan_id=? WHERE run_id=?",run.plan.id(),run.id);}
    private Object[] binding(UUID runId) {
        return jdbc.query("SELECT watch_id,policy_id,policy_revision FROM agent_autonomy_run WHERE run_id=?",(rs,i)->new Object[]{SqlDialect.uuid(rs,1),SqlDialect.uuid(rs,2),rs.getLong(3)},runId).stream().findFirst().orElse(null);
    }
}
