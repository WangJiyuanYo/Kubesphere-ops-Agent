package com.lanhao.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.port.*;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class KubernetesDeploymentPortTest {
    private HttpServer server;
    private KubernetesDeploymentPort port;
    private KubernetesClient client;
    private GenericKubernetesResource resource;
    private Run run;
    private String taskJson;
    private String healthJson;
    private String recoveryJson;
    private String retryBody;
    private String healthAuthorization;
    private AgentProperties config;
    private boolean relatedForbidden;
    private int retryStatus = 200;
    private String retryPath;
    private String healthPath;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        taskJson = "{\"code\":200,\"data\":{\"id\":\"9007199254740993\",\"status\":\"SUCCEEDED\",\"recovery\":{\"id\":\"rec-1\",\"state\":\"SUCCEEDED\"}}}";
        healthJson = "{\"status\":\"UP\"}";
        recoveryJson = "{\"code\":200,\"data\":{\"id\":\"rec-1\",\"requestKey\":\"fixed-key\",\"state\":\"ACCEPTED\"}}";
        server.createContext("/api/admin/integrations/kubesphere/provisioning-instances/9007199254740993", exchange -> {
            String body;
            if (exchange.getRequestMethod().equals("POST")) {
                retryPath = exchange.getRequestURI().getPath();
                retryBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body = recoveryJson;
            } else body = taskJson;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? retryStatus : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        com.sun.net.httpserver.HttpHandler healthHandler = exchange -> {
            healthPath = exchange.getRequestURI().getPath();
            healthAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = healthJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        };
        server.createContext("/health", healthHandler);
        server.createContext("/service-health", healthHandler);
        com.sun.net.httpserver.HttpHandler kubernetesHandler = exchange -> {
            boolean custom = exchange.getRequestURI().getPath().startsWith("/apis/platform.lanhao.io/");
            String body = custom ? TestFixtures.json().writeValueAsString(resource)
                    : "{\"apiVersion\":\"v1\",\"kind\":\"List\",\"items\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(!custom && relatedForbidden ? 403 : 200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        };
        server.createContext("/apis/", kubernetesHandler);
        server.createContext("/api/v1/", kubernetesHandler);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AgentProperties defaults = TestFixtures.config(true);
        config = new AgentProperties(AgentProperties.Mode.KUBERNETES, false, false, 2,
                defaults.stallTimeout(), defaults.runTimeout(), defaults.retryBackoff(), defaults.leaseDuration(),
                defaults.verificationTimeout(), 3, defaults.namespaces(), "operator", "test-password-long",
                URI.create(base + "/api/admin/integrations/kubesphere"), "test-platform-token", URI.create(base + "/health"));
        client = new KubernetesClientBuilder().withConfig(new ConfigBuilder().withMasterUrl(base)
                .withNamespace("agent-demo").withOauthToken("test-k8s-token").withRequestTimeout(3000).build()).build();
        resource = new GenericKubernetesResource();
        resource.setApiVersion("platform.lanhao.io/v1alpha1");
        resource.setKind("ProjectRuntime");
        resource.setMetadata(new ObjectMetaBuilder().withName("runtime").withNamespace("agent-demo").withUid("uid-1").withGeneration(1L).build());
        resource.setAdditionalProperty("spec", Map.of("configVersion", "v1", "retrySequence", 0));
        status("Ready", 1);
        port = new KubernetesDeploymentPort(client, config, TestFixtures.json(), new Redactor(), TestFixtures.ops());
        run = new Run(TestFixtures.request("k8s-test"), Instant.now());
    }
    @AfterEach void stop() { if (client != null) client.close(); server.stop(0); }
    private void status(String phase, long generation) {
        resource.setAdditionalProperty("status", Map.of("phase", phase, "observedGeneration", generation));
    }
    @Test void oldObservedGenerationCannotPassReadiness() {
        status("Ready", 0);
        assertThat(port.observe(run).ready()).isFalse();
    }
    @Test void changedConfigurationOrUidStopsOldRun() {
        port.observe(run);
        resource.setAdditionalProperty("spec", Map.of("configVersion", "v2"));
        assertThatThrownBy(() -> port.observe(run)).isInstanceOf(DeploymentPort.Superseded.class);
        resource.getMetadata().setUid("replacement-uid");
        assertThatThrownBy(() -> port.observe(run)).isInstanceOf(DeploymentPort.Superseded.class);
    }
    @Test void onlyOwnRecoverySequenceMayAdvanceGeneration() {
        run.targets = List.of(new Target(Kind.PlatformRelease, "agent-demo", "runtime", "uid-1", 1));
        port.observe(run);
        run.state = State.VERIFYING; run.recovery = new Recovery("rec-1", "ACCEPTED");
        resource.getMetadata().setGeneration(2L);
        resource.setAdditionalProperty("spec", Map.of("configVersion", "v1", "retrySequence", 1));
        status("Ready", 2);
        assertThat(port.observe(run).ready()).isTrue();
        assertThat(run.effectiveGenerations.get("uid-1")).isEqualTo(2);
    }
    @Test void recoveryIsIdempotentAndUsesVerifiedBusinessResponse() throws Exception {
        run.actionKey = "fixed-key";
        assertThat(port.retry(run).id()).isEqualTo("rec-1");
        assertThat(new ObjectMapper().readTree(retryBody).path("requestKey").asText()).isEqualTo("fixed-key");
        recoveryJson = "{\"code\":500,\"data\":{\"id\":\"bad\"}}";
        assertThatThrownBy(() -> port.retry(run)).isInstanceOf(IllegalStateException.class);
    }
    @Test void unrelatedRecoveryCannotBeMistakenForOwnAction() {
        run.actionKey = "another-key";
        assertThatThrownBy(() -> port.retry(run)).isInstanceOf(IllegalStateException.class);
    }
    @Test void findsLostResponseByPersistedRequestKey() {
        run.actionKey = "fixed-key";
        taskJson = "{\"code\":200,\"data\":{\"id\":\"9007199254740993\",\"recovery\":{\"id\":\"rec-1\",\"requestKey\":\"fixed-key\",\"state\":\"DISPATCHED\"}}}";
        assertThat(port.lookupRecovery(run).orElseThrow().id()).isEqualTo("rec-1");
        run.actionKey = "unrelated";
        assertThat(port.lookupRecovery(run)).isEmpty();
    }
    @Test void sameRecoveryCannotAuthorizeASecondGenerationIncrement() {
        run.targets = List.of(new Target(Kind.PlatformRelease, "agent-demo", "runtime", "uid-1", 1));
        port.observe(run);
        run.state = State.VERIFYING; run.recovery = new Recovery("rec-1", "DISPATCHED");
        resource.getMetadata().setGeneration(2L);
        resource.setAdditionalProperty("spec", Map.of("configVersion", "v1", "retrySequence", 1));
        status("Ready", 2);
        assertThat(port.observe(run).ready()).isTrue();
        resource.getMetadata().setGeneration(3L);
        resource.setAdditionalProperty("spec", Map.of("configVersion", "v1", "retrySequence", 2));
        status("Ready", 3);
        assertThatThrownBy(() -> port.observe(run)).isInstanceOf(DeploymentPort.Superseded.class);
    }
    @Test void requiresPlatformRecoverySuccessAndUpHealthWithoutLeakingToken() {
        run.recovery = new Recovery("rec-1", "ACCEPTED");
        assertThat(port.verify(run)).isTrue();
        assertThat(healthAuthorization).isNull();
        healthJson = "{\"status\":\"DOWN\"}";
        assertThat(port.verify(run)).isFalse();
        run.recovery = new Recovery("old-recovery", "SUCCEEDED");
        assertThatThrownBy(() -> port.verify(run)).isInstanceOf(DeploymentPort.Superseded.class);
    }
    private void strict() throws Exception {
        run.deploymentVersion = "release-v1"; run.verificationProfile = "app";
        OpsProperties base = TestFixtures.ops();
        var ops = new OpsProperties(4, base.stepTimeout(), base.collectionTimeout(), true,
                Map.of("app", new OpsProperties.VerificationProfile(config.verificationUrl().resolve("/service-health"))), base.notification());
        port = new KubernetesDeploymentPort(client, config, TestFixtures.json(), new Redactor(), ops);
        taskJson = "{\"code\":200,\"data\":{\"id\":\"9007199254740993\",\"status\":\"SUCCEEDED\","
                + "\"clusterApiUrl\":\""+client.getConfiguration().getMasterUrl()+"\",\"applicationVersion\":\"app-v1\","
                + "\"version\":\"release-v1\",\"verificationProfile\":\"app\",\"targetsComplete\":true,\"snapshotToken\":\"snapshot-v1\",\"targets\":"
                + TestFixtures.json().writeValueAsString(run.targets) + "}}";
    }
    @Test void strictContractSendsFrozenPreconditionsAndRejectsPlatformConflict() throws Exception {
        strict();
        port.validateRegistration(new StartRequest("strict", run.provisioningId, run.targets, "release-v1", "app"));
        port.observe(run);
        run.actionKey = "fixed-key"; run.actionSnapshotToken = run.platformSnapshotToken;
        run.plan = new Plan("plan", Action.RETRY_TRANSIENT, null, true, Instant.now());
        recoveryJson = "{\"code\":200,\"data\":{\"id\":\"rec-1\",\"requestKey\":\"fixed-key\",\"state\":\"ACCEPTED\",\"acceptedVersion\":\"release-v1\"}}";
        assertThat(port.retry(run).id()).isEqualTo("rec-1");
        assertThat(retryPath).endsWith("/agent-recoveries");
        var sent = TestFixtures.json().readTree(retryBody);
        assertThat(sent.path("expectedVersion").asText()).isEqualTo("release-v1");
        assertThat(sent.path("expectedSnapshotToken").asText()).isEqualTo("snapshot-v1");
        assertThat(sent.path("expectedTargets").get(0).path("specHash").asText()).hasSize(64);
        retryStatus = 409;
        assertThatThrownBy(() -> port.retry(run)).isInstanceOf(DeploymentPort.Superseded.class);
    }
    @Test void strictRegistrationRejectsWrongVersionAndUnownedTargets() throws Exception {
        strict();
        run.deploymentVersion = "another-version";
        assertThatThrownBy(() -> port.observe(run)).isInstanceOf(DeploymentPort.Superseded.class);
        run.deploymentVersion = "release-v1";
        taskJson = taskJson.replace("uid-1", "other-uid");
        assertThatThrownBy(() -> port.observe(run)).isInstanceOf(DeploymentPort.Superseded.class);
    }
    @Test void taskSpecificVerificationRequiresApplicationVersion() throws Exception {
        strict();
        healthJson = "{\"status\":\"UP\",\"version\":\"old-release\"}";
        assertThat(port.verify(run)).isFalse();
        healthJson = "{\"status\":\"UP\",\"version\":\"app-v1\"}";
        assertThat(port.verify(run)).isTrue();
        assertThat(healthPath).isEqualTo("/service-health");
        assertThat(healthAuthorization).isNull();
        run.verificationProfile = "unconfigured";
        assertThatThrownBy(() -> port.verify(run)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void strictIdentityRequiresPlatformClusterAndFreezesActualApplicationVersion() throws Exception {
        strict();
        var tree=TestFixtures.json().readTree(taskJson);var data=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("data");
        String cluster=data.path("clusterApiUrl").asText();data.put("clusterApiUrl","https://other.invalid:6443");taskJson=tree.toString();
        assertThatThrownBy(()->port.discover(run)).isInstanceOf(DeploymentPort.Superseded.class).hasMessageContaining("集群");
        data.remove("clusterApiUrl");taskJson=tree.toString();
        assertThatThrownBy(()->port.discover(run)).isInstanceOf(DeploymentPort.Superseded.class);
        data.put("clusterApiUrl",cluster);taskJson=tree.toString();port.observe(run);
        run=TestFixtures.json().readValue(TestFixtures.json().writeValueAsString(run),Run.class);
        assertThat(run.applicationVersion).isEqualTo("app-v1");
        data.put("applicationVersion","new-app");taskJson=tree.toString();
        assertThatThrownBy(()->port.observe(run)).isInstanceOf(DeploymentPort.Superseded.class).hasMessageContaining("应用发布版本");
    }
    @Test void platformRegistrationResolvesLifecycleVersionInsteadOfGuessingFromCrVersion() throws Exception {
        strict();var request=port.registration(run.provisioningId);
        assertThat(request.deploymentVersion()).isEqualTo("release-v1");assertThat(request.verificationProfile()).isEqualTo("app");
        assertThat(request.targets()).containsExactlyElementsOf(run.targets);
        healthJson="{\"status\":\"UP\",\"version\":\"release-v1\"}";
        assertThat(port.verify(run)).isFalse();
        healthJson="{\"status\":\"UP\",\"version\":\"app-v1\"}";
        assertThat(port.verify(run)).isTrue();
    }
    @Test void partialRelatedCollectionPreservesCrEvidenceButBlocksAutomaticRetry() {
        resource.setAdditionalProperty("status", Map.of("phase", "Failed", "observedGeneration", 1,
                "reason", "NETWORK_TIMEOUT", "message", "i/o timeout"));
        relatedForbidden = true;
        Evidence evidence = port.observe(run);
        assertThat(evidence.failed()).isTrue();
        assertThat(evidence.items()).anyMatch(item -> item.decisive() && item.reason().equals("NETWORK_TIMEOUT"));
        assertThat(evidence.issues()).hasSize(1);
        assertThat(new com.lanhao.ops.diagnosis.RecoveryPolicy().classify(evidence)).isEqualTo(Action.MANUAL_REPAIR);
    }
    @Test void strictDiscoveryHandlesUncreatedTargetsAndPreDeploymentFailures() throws Exception {
        strict();
        var tree = TestFixtures.json().readTree(taskJson);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("data")).putArray("targets");
        taskJson = tree.toString();
        assertThat(port.discover(run)).isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("data")).put("status", "FAILED");
        taskJson = tree.toString();
        assertThatThrownBy(() -> port.discover(run)).isInstanceOf(DeploymentPort.Superseded.class);
    }
    @Test void discoveryDoesNotFreezeAnIncompleteSetOfDeploymentTargets() throws Exception {
        strict();
        taskJson = taskJson.replace("\"targetsComplete\":true", "\"targetsComplete\":false").replace("SUCCEEDED", "RUNNING");
        assertThat(port.discover(run)).isEmpty();
        taskJson = taskJson.replace("\"targetsComplete\":false", "\"targetsComplete\":true");
        assertThat(port.discover(run)).containsExactlyElementsOf(run.targets);
    }
}
