package com.lanhao.ops.console;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;

@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=${TEST_DATABASE_URL}","spring.datasource.username=${TEST_DATABASE_USER:deployment_agent}","spring.datasource.password=${TEST_DATABASE_PASSWORD:demo-postgres-only}",
        "agent.mode=KUBERNETES","agent.api-password=fixture-api-password-long","agent.platform-base-url=https://platform.test","agent.platform-token=fixture-platform-token",
        "agent.provisioning.enabled=true","agent.provisioning.platform-instance-id=platform-fixture","agent.ops.verification-profiles.app.url=https://application.test/health",
        "agent.console.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ProvisioningPostgresTest extends ProvisioningContract {
    static final Path memory=memory();
    static Path memory(){try{return Files.createTempDirectory("agent-provisioning-pg-").resolve("memory.db");}catch(Exception failure){throw new RuntimeException(failure);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("agent.memory.file",()->memory.toString());}
}
