package com.lanhao.ops.command;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.nio.file.*;
import java.time.Duration;

@Validated
@ConfigurationProperties("agent.kubectl")
public record KubectlProperties(boolean enabled,String executable,String kubeconfig,String context,
        @NotNull Duration timeout,@Min(1) @Max(3) int maxCommands) {
    @AssertTrue(message="kubectl 单条命令超时须在 1–5 秒之间")
    public boolean isTimeoutValid(){return timeout==null||timeout.compareTo(Duration.ofSeconds(1))>=0&&timeout.compareTo(Duration.ofSeconds(5))<=0;}
    public void validateNative() {
        if(executable==null||kubeconfig==null||context==null||!context.matches("[A-Za-z0-9_.:-]{1,100}"))
            throw new IllegalArgumentException("启用 kubectl 需显式配置可执行文件、kubeconfig 和 context");
        Path binary=Path.of(executable),config=Path.of(kubeconfig);
        if(!binary.isAbsolute()||!config.isAbsolute()||!Files.isRegularFile(binary)||!Files.isRegularFile(config)
                ||!java.util.Set.of("kubectl","kubectl.exe").contains(binary.getFileName().toString()))
            throw new IllegalArgumentException("kubectl 与 kubeconfig 必须是受信任的现存绝对文件路径");
    }
}
