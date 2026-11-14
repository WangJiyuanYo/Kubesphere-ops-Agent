package com.lanhao.ops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class OpsPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpsProperties.class)
    static class PropertiesConfiguration {}
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);
    @Test void defaultsAreBoundBeforeSchedulerStarts() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(OpsProperties.class).workers()).isEqualTo(4);
            assertThat(c.getBean(OpsProperties.class).strictPlatformContract()).isTrue();
        });
    }
    @Test void unboundedConcurrencyAndInvalidTimeoutsAreRejected() {
        context.withPropertyValues("agent.ops.workers=100").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("agent.ops.collection-timeout=76s").run(c -> assertThat(c).hasFailed());
    }
    @Test void verificationAndNotificationDestinationsMustBeConfiguredSecurely() {
        context.withPropertyValues("agent.ops.verification-profiles.app.url=http://app.example.com/health").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("agent.ops.notification.enabled=true", "agent.ops.notification.url=https://notify.example.com/hooks",
                "agent.ops.notification.secret=short").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("agent.ops.verification-profiles.app.url=https://app.example.com/health").run(c -> assertThat(c).hasNotFailed());
    }
}
