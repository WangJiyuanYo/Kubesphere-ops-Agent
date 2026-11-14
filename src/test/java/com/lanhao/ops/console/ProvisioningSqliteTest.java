package com.lanhao.ops.console;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;

@ActiveProfiles("sqlite")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "agent.mode=KUBERNETES","agent.api-password=fixture-api-password-long","agent.platform-base-url=https://platform.test","agent.platform-token=fixture-platform-token",
        "agent.provisioning.enabled=true","agent.provisioning.platform-instance-id=platform-fixture","agent.ops.verification-profiles.app.url=https://application.test/health",
        "agent.console.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ProvisioningSqliteTest extends ProvisioningContract {
    static final Path file=database();
    static Path database(){try{return Files.createTempDirectory("agent-provisioning-test-").resolve("agent.db");}catch(Exception failure){throw new RuntimeException(failure);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("agent.sqlite.file",()->file.toString());}
}
