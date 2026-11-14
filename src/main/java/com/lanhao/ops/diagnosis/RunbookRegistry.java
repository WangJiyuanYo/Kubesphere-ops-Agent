package com.lanhao.ops.diagnosis;

import com.lanhao.ops.domain.Model.*;
import org.springframework.stereotype.Component;
import java.util.List;

/** 版本化、封闭的执行目录；扩展动作需要代码评审及平台契约。 */
@Component
public class RunbookRegistry {
    public record Runbook(String id, int version, Action action, String executor, List<String> preconditions,
            List<String> verification, String compensation) {}
    public List<Runbook> list() {
        return List.of(new Runbook("platform-transient-retry", 1, Action.RETRY_TRANSIENT, "PLATFORM_RECOVERY",
                List.of("当前代次明确暂时故障", "采证完整", "目标版本与冻结快照一致", "预算与租约有效"),
                List.of("当前代次 CR Ready", "恢复身份成功", "任务专属应用版本与健康连续通过"),
                "不修改源配置；失败后重新诊断或人工接管"));
    }
    public void requireExecutable(Plan plan) {
        if (plan == null || list().stream().noneMatch(r -> r.id().equals(plan.runbookId())
                && r.version() == plan.runbookVersion() && r.action() == plan.action()))
            throw new IllegalStateException("排障手册已失效或不允许自动执行，需要重新诊断");
    }
}
