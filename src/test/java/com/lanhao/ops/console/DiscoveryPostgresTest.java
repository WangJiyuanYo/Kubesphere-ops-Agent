package com.lanhao.ops.console;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;

@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=${TEST_DATABASE_URL}","spring.datasource.username=${TEST_DATABASE_USER:deployment_agent}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD:demo-postgres-only}","agent.memory.file=./tmp/discovery-test-memory.db",
        "agent.console.allow-http=true","agent.console.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","agent.kubectl.enabled=true"})
class DiscoveryPostgresTest extends DiscoveryContract {
    static final Path memory=memoryFile();
    static Path memoryFile(){try{return Files.createTempDirectory("agent-discovery-pg-memory-").resolve("memory.db");}catch(Exception failure){throw new RuntimeException(failure);}}
    @DynamicPropertySource static void memoryProperties(DynamicPropertyRegistry registry){registry.add("agent.memory.file",()->memory.toString());}
}
