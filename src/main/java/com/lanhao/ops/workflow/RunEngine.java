package com.lanhao.ops.workflow;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.diagnosis.DiagnosisGraph;
import com.lanhao.ops.diagnosis.RecoveryPolicy;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.persistence.RunStore;
import com.lanhao.ops.port.DeploymentPort;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Objects;

@Service
public class RunEngine {
    private final RunStore store;
    private final DeploymentPort deployment;
    private final DiagnosisGraph diagnosis;
    private final AgentProperties config;
    private final RecoveryPolicy policy;
    private com.lanhao.ops.console.AutonomyGuard autonomy;
    private com.lanhao.ops.persistence.RunDiagnosisBudget diagnosisBudget;
    @org.springframework.beans.factory.annotation.Autowired
    public void diagnosisBudget(com.lanhao.ops.persistence.RunDiagnosisBudget budget){this.diagnosisBudget=budget;}
    @org.springframework.beans.factory.annotation.Autowired
    public void autonomy(com.lanhao.ops.console.AutonomyGuard autonomy){this.autonomy=autonomy;}
    public RunEngine(RunStore store, DeploymentPort deployment, DiagnosisGraph diagnosis, AgentProperties config, RecoveryPolicy policy) {
        this.store = store; this.deployment = deployment; this.diagnosis = diagnosis; this.config = config; this.policy = policy;
    }
    public void step(RunStore.Claim claim) {
        Run run = store.find(claim.id()).orElseThrow();
        Instant now = Instant.now();
        Instant due = now.plusSeconds(5);
        try {
            store.ensureLease(claim);
            if (run.state.terminal()) return;
            new com.lanhao.ops.domain.DeploymentIdentity(config, deployment).assertCurrent(run);
            if (!now.isBefore(run.createdAt.plus(config.runTimeout()))) {
                run.transition(State.MANUAL_REQUIRED, "任务总时限耗尽，保留诊断与现场", now);
            } else {
                switch (run.state) {
                    case DISCOVERING -> {
                        var targets = deployment.discover(run);
                        if (!targets.isEmpty()) {
                            run.targets = java.util.List.copyOf(targets);
                            run.lastProgressAt = now;
                            run.transition(State.MONITORING, "已从平台受权快照绑定部署资源", now);
                        } else if (!now.isBefore(run.createdAt.plus(config.stallTimeout())))
                            run.transition(State.MANUAL_REQUIRED, "部署资源尚未创建，平台前置阶段持续无进展", now);
                    }
                    case MONITORING -> monitor(run, now);
                    case DIAGNOSING -> {
                        if(autonomy!=null)autonomy.run(run,false);
                        run.diagnosisEvidence = run.evidence;
                        Run diagnosing=run;
                        run.plan = diagnosisBudget==null?diagnosis.diagnose(run,claim):diagnosisBudget.diagnose(run,()->diagnosis.diagnose(diagnosing,claim));
                        StepControl.check();
                        if (run.plan.action() == Action.MANUAL_REPAIR || run.attempts >= config.maxAttempts())
                            run.transition(State.MANUAL_REQUIRED, "需要源配置修复、证据补充或恢复预算已耗尽", now);
                        else {
                            run.transition((autonomy==null?run.plan.automatic():autonomy.automatic(run)) ? State.RETRY_WAIT : State.WAITING_APPROVAL,
                                    "排查方案已生成，等待受控恢复", now);
                            due = run.state == State.WAITING_APPROVAL ? run.createdAt.plus(config.runTimeout()) : backoff(run, now);
                        }
                    }
                    case RETRY_WAIT -> {
                        if(autonomy!=null)autonomy.run(run,true);
                        run.evidence = deployment.observe(run);
                        if (run.evidence.ready()) beginVerification(run, now);
                        else if (!safeToRetry(run.evidence)) run.transition(State.DIAGNOSING, "故障证据已变化，重新评估方案", now);
                        else if (run.attempts >= config.maxAttempts())
                            run.transition(State.MANUAL_REQUIRED, "恢复次数达到上限", now);
                        else {
                            // 先持久化动作身份和预算，下次调度才执行外部副作用。
                            run.attempts++;
                            run.actionKey = "agent_" + run.id.toString().replace("-", "") + "_" + run.attempts;
                            run.recovery = null;
                            run.actionGenerations = new java.util.HashMap<>(run.effectiveGenerations);
                            run.actionRetrySequences = new java.util.HashMap<>(run.retrySequences);
                            run.actionSnapshotToken = run.platformSnapshotToken;
                            run.transition(State.EXECUTING, "恢复意图已落盘，将使用固定幂等键下发", now);
                        }
                    }
                    case EXECUTING -> {
                        var accepted = deployment.lookupRecovery(run);
                        if (accepted.isPresent()) {
                            // 先找回响应丢失前已受理的原动作，之后才能识别自身的 generation 变化。
                            run.recovery = accepted.get();
                            beginVerification(run, now);
                            break;
                        }
                        Evidence fresh = deployment.observe(run);
                        if (fresh.ready()) beginVerification(run, now);
                        else if (!safeToRetry(fresh)) {
                            run.evidence = fresh;
                            run.transition(State.DIAGNOSING, "执行前故障已变化，停止原恢复方案", now);
                        }
                        else {
                            new com.lanhao.ops.diagnosis.RunbookRegistry().requireExecutable(run.plan);
                            if(autonomy!=null)autonomy.run(run,true);
                            StepControl.check();
                            if (!Instant.now().isBefore(run.createdAt.plus(config.runTimeout()))) {
                                run.transition(State.MANUAL_REQUIRED, "执行前任务总时限耗尽，停止下发动作", Instant.now());
                                break;
                            }
                            store.ensureLease(claim);
                            run.recovery = deployment.retry(run);
                            beginVerification(run, now);
                        }
                    }
                    case VERIFYING -> verify(run, now);
                    case WAITING_APPROVAL -> due = run.createdAt.plus(config.runTimeout());
                    default -> { }
                }
                StepControl.check();
                run.transportFailures = 0;
            }
        } catch (com.lanhao.ops.console.ContextMismatch stopped) {
            run.transition(State.MANUAL_REQUIRED,"托管策略、授权或冻结范围已变化，停止新动作；保留原动作身份和预算",now);
        } catch (DeploymentPort.Superseded superseded) {
            run.transition(State.FAILED, superseded.getMessage(), now);
        } catch (RuntimeException failure) {
            if (failure instanceof StepControl.Aborted) {
                // 迟到的健康结果和计算结果不能提交为成功；已落盘动作身份仍保留供对账。
                run = store.find(claim.id()).orElseThrow();
            }
            // 不保存外部异常原文，避免响应体、Token 或内部地址进入审计。
            run.transportFailures++;
            run.stableCount = 0;
            if (run.transportFailures >= 3)
                run.transition(State.MANUAL_REQUIRED, "连续三次采集或接口失败，未确认恢复效果，请检查访问权限和服务可用性", now);
            else run.events.add(new Event(now, run.state, "采集或调用暂不可用，将保持原动作身份重试"));
            due = now.plus(config.retryBackoff());
        }
        store.save(claim, run, due);
    }
    private void monitor(Run run, Instant now) {
        run.evidence = deployment.observe(run);
        if (!Objects.equals(run.progressFingerprint, run.evidence.fingerprint())) {
            run.progressFingerprint = run.evidence.fingerprint();
            run.lastProgressAt = now;
        }
        if (run.evidence.ready()) beginVerification(run, now);
        else if (run.evidence.failed() || now.isAfter(run.lastProgressAt.plus(config.stallTimeout())))
            run.transition(State.DIAGNOSING, "检测到明确失败或部署阶段持续无进展，开始生成排查方案", now);
    }
    private void beginVerification(Run run, Instant now) {
        run.verificationStartedAt = now;
        run.stableCount = 0;
        run.transition(State.VERIFYING, "进入版本、平台结果和应用健康的持续验证", now);
    }
    private void verify(Run run, Instant now) {
        Instant deadline = run.verificationStartedAt.plus(config.verificationTimeout());
        if (!now.isBefore(deadline)) {
            verificationFailed(run, "验证时限耗尽，重新评估当前故障", now);
            return;
        }
        if (run.recovery != null) {
            Recovery latest = deployment.lookupRecovery(run).orElseThrow(() ->
                    new DeploymentPort.Superseded("本次恢复请求已消失或被替换，停止当前任务"));
            if (!run.recovery.id().equals(latest.id()))
                throw new DeploymentPort.Superseded("平台已有不同的恢复请求，停止当前任务");
            run.recovery = latest;
            if ("SUPERSEDED".equals(latest.state()))
                throw new DeploymentPort.Superseded("本次恢复请求已被平台替代，停止当前任务");
        }
        run.evidence = deployment.observe(run);
        if (run.recovery != null && "FAILED".equals(run.recovery.state())) {
            verificationFailed(run, "平台已确认本次恢复失败，重新评估根因", Instant.now());
            return;
        }
        boolean healthy = run.evidence.ready() && deployment.verify(run);
        Instant finishedAt = Instant.now();
        if (!finishedAt.isBefore(run.createdAt.plus(config.runTimeout()))) {
            run.stableCount = 0;
            run.transition(State.MANUAL_REQUIRED, "验证期间任务总时限耗尽", finishedAt);
        } else if (!finishedAt.isBefore(deadline)) {
            verificationFailed(run, "验证响应超过时限，不能计入连续通过次数", finishedAt);
        } else if (healthy && (run.recovery == null || "SUCCEEDED".equals(run.recovery.state()))) {
            run.stableCount++;
            if (run.stableCount >= config.stableChecks()) run.transition(State.SUCCEEDED, "目标版本及应用连续验证通过", finishedAt);
        } else {
            run.stableCount = 0;
        }
    }
    private void verificationFailed(Run run, String message, Instant now) {
        run.stableCount = 0;
        run.transition(run.attempts >= config.maxAttempts() ? State.MANUAL_REQUIRED : State.DIAGNOSING, message, now);
    }
    private Instant backoff(Run run, Instant now) {
        long multiplier = 1L << Math.min(run.attempts, 3);
        long jitter = Math.floorMod(run.id.hashCode(), 5);
        return now.plus(config.retryBackoff().multipliedBy(multiplier)).plusSeconds(jitter);
    }
    private boolean safeToRetry(Evidence evidence) {
        StepControl.check();
        return policy.classify(evidence) == Action.RETRY_TRANSIENT;
    }
}
