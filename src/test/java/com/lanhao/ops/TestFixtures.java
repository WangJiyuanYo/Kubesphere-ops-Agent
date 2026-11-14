package com.lanhao.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.Model.*;
import java.time.*;
import java.util.List;
import java.util.Map;

public final class TestFixtures {
    private TestFixtures() {}
    public static OpsProperties ops() {
        return new OpsProperties(4, Duration.ofSeconds(75), Duration.ofSeconds(45), false, Map.of(),
                new OpsProperties.Notification(false, null, null, 8));
    }
    public static ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
    public static AgentProperties config(boolean automatic) {
        return new AgentProperties(AgentProperties.Mode.DEMO, false, automatic, 2,
                Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofMinutes(5),
                Duration.ofMinutes(5), 3, List.of("agent-demo"), "operator", "demo-local-only", null, null, null);
    }
    public static StartRequest request(String key) {
        return new StartRequest(key, "9007199254740993", List.of(new Target(Kind.ProjectRuntime,
                "agent-demo", "runtime", "uid-1", 1)));
    }
    public static Evidence failed(String details) {
        return new Evidence(Instant.now(), false, true, "failed", "DEPLOY_FAILED", List.of(details));
    }
}
