package com.lanhao.ops;

import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.workflow.RunScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "agent.memory.file=./tmp/http-test-memory.db",
        "spring.datasource.url=${TEST_DATABASE_URL}",
        "spring.datasource.username=${TEST_DATABASE_USER:deployment_agent}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD:demo-postgres-only}"})
class ApplicationContextTest {
    @Autowired TestRestTemplate http;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean RunScheduler scheduler;
    @MockitoBean com.lanhao.ops.memory.LearningOutbox backgroundLearning;
    @Test void demoBootsWithoutAnyModelKeyAndProtectsItsApi() {
        jdbc.execute("TRUNCATE agent_run CASCADE");
        var request = new StartRequest("http-test", "9007199254740995", TestFixtures.request("http-test").targets());
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/runs/00000000-0000-0000-0000-000000000000", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        var response = http.withBasicAuth("operator", "demo-local-only")
                .postForEntity("/api/runs", request, Run.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().provisioningId).isEqualTo("9007199254740995");
        var duplicate = http.withBasicAuth("operator", "demo-local-only")
                .postForEntity("/api/runs", request, Run.class);
        assertThat(duplicate.getBody().id).isEqualTo(response.getBody().id);
        var auth = http.withBasicAuth("operator", "demo-local-only");
        assertThat(auth.getForEntity("/api/runs?limit=20&offset=0", Run[].class).getBody()).hasSize(1);
        assertThat(auth.getForEntity("/api/runbooks", com.fasterxml.jackson.databind.JsonNode.class).getBody().get(0).path("id").asText())
                .isEqualTo("platform-transient-retry");
        assertThat(auth.getForEntity("/actuator/metrics", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/metrics", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var cancelled = auth.postForEntity("/api/runs/" + response.getBody().id + "/cancel", null, Run.class);
        assertThat(cancelled.getBody().state).isEqualTo(State.CANCELLED);
        var audit = auth.getForEntity("/api/runs/" + response.getBody().id + "/audit", com.fasterxml.jackson.databind.JsonNode.class).getBody();
        assertThat(audit.findValuesAsText("state")).contains("CANCELLED");
        var notifications = auth.getForEntity("/api/runs/" + response.getBody().id + "/notifications", com.fasterxml.jackson.databind.JsonNode.class).getBody();
        assertThat(notifications.size()).isEqualTo(1);
        var invalidEvent = new DeploymentEvent("demo-event", "9007199254740995", "v1", "app");
        assertThat(auth.postForEntity("/api/deployment-events", invalidEvent, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
