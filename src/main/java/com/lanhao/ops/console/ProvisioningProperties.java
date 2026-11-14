package com.lanhao.ops.console;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 阶段协议独立开关；自动恢复还必须通过既有严格契约与进程开关。 */
@Validated
@ConfigurationProperties("agent.provisioning")
public record ProvisioningProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String platformInstanceId,
        @DefaultValue("5") @Min(1) @Max(20) int maxPages,
        @DefaultValue("20") @Min(1) @Max(100) int queueLimit,
        @DefaultValue("false") boolean autoLowRiskEnabled,
        @DefaultValue("") String recoveryAcceptanceRef) {
    public ProvisioningProperties {
        if(enabled&&(platformInstanceId==null||!platformInstanceId.matches("[A-Za-z0-9_-]{1,80}")))
            throw new IllegalArgumentException("阶段监控必须配置稳定的平台实例 ID");
        if(autoLowRiskEnabled&&(recoveryAcceptanceRef==null||recoveryAcceptanceRef.isBlank()))
            throw new IllegalArgumentException("自动恢复必须配置非生产恢复验收记录引用");
    }
}
