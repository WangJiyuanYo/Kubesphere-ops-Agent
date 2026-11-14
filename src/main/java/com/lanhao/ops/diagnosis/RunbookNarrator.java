package com.lanhao.ops.diagnosis;

import com.lanhao.ops.domain.Model.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "agent.ai-enabled", havingValue = "false", matchIfMissing = true)
public class RunbookNarrator implements DiagnosticNarrator {
    public Diagnosis explain(String facts, Action action) { return fallback(action, "规则手册"); }
    public static Diagnosis fallback(Action action, String source) {
        boolean transientFailure = action == Action.RETRY_TRANSIENT;
        return new Diagnosis(transientFailure ? "证据包含暂时性网络或上游限流错误，具体根因仍需核验" : "证据不足或命中配置、权限、资源、数据保护规则",
                List.of("核对失败 CR 的 UID、generation、版本和阶段", "按失败时间核对关联 Pod、Job、事件及依赖连通性"),
                transientFailure ? List.of("检查镜像仓库、DNS 或上游服务可用性", "在预算内通过平台恢复入口重新协调，保持原配置")
                        : List.of("根据失败证据修复源配置、权限或容量", "通过正常发布流程应用修复，重新绑定目标版本"),
                "所有绑定 CR 的当前代次就绪、平台任务成功、应用探测持续通过", source);
    }
}
