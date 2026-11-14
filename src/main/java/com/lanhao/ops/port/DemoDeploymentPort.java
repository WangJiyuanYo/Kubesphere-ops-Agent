package com.lanhao.ops.port;

import com.lanhao.ops.domain.Model.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

/** 演示状态由持久化任务推导，重启不会依赖进程内计数。 */
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "DEMO")
public class DemoDeploymentPort implements DeploymentPort {
    public Evidence observe(Run run) {
        boolean recovered = run.recovery != null;
        return new Evidence(Instant.now(), recovered, !recovered,
                recovered ? "demo-ready" : "demo-registry-timeout", recovered ? "Ready" : "REGISTRY_TIMEOUT",
                List.of(recovered ? "模拟目标版本已就绪" : "拉取受审镜像时 i/o timeout；仓库返回 503"));
    }
    public Recovery retry(Run run) { return new Recovery(run.actionKey, "OBSERVED"); }
    public java.util.Optional<Recovery> lookupRecovery(Run run) {
        return run.recovery == null ? java.util.Optional.empty()
                : java.util.Optional.of(new Recovery(run.recovery.id(), "SUCCEEDED"));
    }
    public boolean verify(Run run) { return run.recovery != null; }
}
