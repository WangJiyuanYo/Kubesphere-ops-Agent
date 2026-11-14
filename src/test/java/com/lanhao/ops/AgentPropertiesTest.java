package com.lanhao.ops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class AgentPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class).withPropertyValues(
                    "agent.mode=DEMO", "agent.ai-enabled=false", "agent.auto-retry=true", "agent.max-attempts=2",
                    "agent.stall-timeout=10m", "agent.run-timeout=60m", "agent.retry-backoff=15s",
                    "agent.lease-duration=5m", "agent.verification-timeout=5m", "agent.stable-checks=3",
                    "agent.namespaces=agent-demo", "agent.api-username=operator", "agent.api-password=demo-local-only");
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class PropertiesConfiguration {}

    @Test void validDemoSettingsBindWithoutPlatformCredentials() {
        context.run(c -> assertThat(c).hasNotFailed());
    }
    @Test void zeroTimeoutIsRejectedDuringBinding() {
        context.withPropertyValues("agent.verification-timeout=0s").run(c -> assertThat(c).hasFailed());
    }
    @Test void shortLeaseIsRejectedDuringBinding() {
        context.withPropertyValues("agent.lease-duration=119s").run(c -> assertThat(c).hasFailed());
    }
    @Test void invalidLiveSettingsFailBeforeAnySchedulerCanStart() {
        context.withPropertyValues("agent.mode=KUBERNETES").run(c -> assertThat(c).hasFailed());
    }
    @Test void validLiveSettingsBind() {
        context.withPropertyValues("agent.mode=KUBERNETES", "agent.api-password=independent-long-password",
                "agent.platform-base-url=https://platform.example.com/api/admin/integrations/kubesphere",
                "agent.platform-token=placeholder", "agent.verification-url=https://app.example.com/actuator/health")
                .run(c -> assertThat(c).hasNotFailed());
    }
}
