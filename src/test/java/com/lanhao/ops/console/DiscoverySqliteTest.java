package com.lanhao.ops.console;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;

@ActiveProfiles("sqlite")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "agent.console.allow-http=true","agent.console.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","agent.kubectl.enabled=true"})
class DiscoverySqliteTest extends DiscoveryContract {
    static final Path file=database();
    static Path database(){try{return Files.createTempDirectory("agent-discovery-test-").resolve("agent.db");}catch(Exception failure){throw new RuntimeException(failure);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("agent.sqlite.file",()->file.toString());}
}
