package com.lanhao.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AgentProperties.class, OpsProperties.class, com.lanhao.ops.console.ConsoleProperties.class, com.lanhao.ops.command.KubectlProperties.class, com.lanhao.ops.console.PlatformCatalogProperties.class, com.lanhao.ops.console.ProvisioningProperties.class})
public class DeploymentOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeploymentOpsApplication.class, args);
    }
}
