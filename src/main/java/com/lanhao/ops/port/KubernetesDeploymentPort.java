package com.lanhao.ops.port;

import com.fasterxml.jackson.databind.*;
import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.OpsProperties;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.workflow.StepControl;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "KUBERNETES")
public class KubernetesDeploymentPort implements DeploymentPort {
    private final KubernetesClient kubernetes;
    private final AgentProperties config;
    private final ObjectMapper json;
    private final Redactor redactor;
    private final RestClient platform;
    private final RestClient health;
    private final OpsProperties ops;

    public KubernetesDeploymentPort(KubernetesClient kubernetes, AgentProperties config, ObjectMapper json, Redactor redactor, OpsProperties ops) {
        this.kubernetes = kubernetes; this.config = config; this.json = json; this.redactor = redactor; this.ops = ops;
        if (!ops.strictPlatformContract() && config.autoRetry())
            throw new IllegalArgumentException("兼容平台接口仅允许批准模式，不能启用自动恢复");
        if (ops.strictPlatformContract() && ops.verificationProfiles().isEmpty()
                || !ops.strictPlatformContract() && config.verificationUrl() == null)
            throw new IllegalArgumentException("请先配置服务端验收目录或兼容模式的固定验收地址");
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        platform = RestClient.builder().requestFactory(factory).baseUrl(config.platformBaseUrl().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.platformToken())
                .defaultHeader(HttpHeaders.ACCEPT,org.springframework.http.MediaType.APPLICATION_JSON_VALUE).build();
        health = RestClient.builder().requestFactory(factory).build();
    }
    public String clusterApiUrl() { return kubernetes.getConfiguration().getMasterUrl(); }
    public Evidence observe(Run run) {
        long deadline = System.nanoTime() + ops.collectionTimeout().toNanos();
        check(deadline);
        if (ops.strictPlatformContract()) {
            JsonNode task = detail(run);
            assertBinding(run, task);
            String token = task.path("snapshotToken").asText();
            if (token.isBlank() || token.length() > 512) throw new IllegalStateException("平台缺少有效目标快照令牌");
            run.platformSnapshotToken = token;
        }
        List<String> facts = new ArrayList<>();
        List<EvidenceItem> items = new ArrayList<>();
        List<CollectionIssue> issues = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        boolean ready = true;
        boolean failed = false;
        String reason = "等待部署收敛";
        for (Target target : run.targets) {
            check(deadline);
            if (!config.namespaces().contains(target.namespace())) throw new IllegalArgumentException("目标命名空间未授权");
            ResourceDefinitionContext context = new ResourceDefinitionContext.Builder().withGroup("platform.lanhao.io")
                    .withVersion("v1alpha1").withPlural(target.kind().plural).withNamespaced(true).build();
            GenericKubernetesResource resource = kubernetes.genericKubernetesResources(context)
                    .inNamespace(target.namespace()).withName(target.name()).get();
            if (resource == null || resource.getMetadata().getDeletionTimestamp() != null
                    || !target.uid().equals(resource.getMetadata().getUid())) throw new Superseded();
            long generation = verifyGeneration(run, target, resource);
            JsonNode status = json.valueToTree(resource.getAdditionalProperties().getOrDefault("status", Map.of()));
            String phase = status.path("phase").asText("");
            String code = status.path("reason").asText("");
            boolean current = status.path("observedGeneration").asLong(-1) == generation;
            boolean targetReady = current && "Ready".equalsIgnoreCase(phase);
            ready &= targetReady;
            boolean targetFailed = current && ("Failed".equalsIgnoreCase(phase) || "Degraded".equalsIgnoreCase(phase));
            failed |= targetFailed;
            if (targetFailed) reason = redactor.clean(code.isBlank() ? phase : code);
            progress.add(target.uid() + ":" + current + ":" + phase + ":" + code);
            facts.add(redactor.clean(target.kind() + "/" + target.name() + " phase=" + phase + " reason=" + code
                    + " message=" + status.path("message").asText("")));
            items.add(new EvidenceItem("CR/" + target.kind() + "/" + target.name(), target.uid(), generation,
                    Instant.now(), redactor.clean(code), redactor.clean(status.path("message").asText("")), current && targetFailed));
            if (!targetReady) {
                try { collectRelated(target, facts, items, issues, deadline); }
                catch (RuntimeException unavailable) {
                    check(deadline);
                    issues.add(new CollectionIssue("RELATED/" + target.name(), target.uid(), errorType(unavailable)));
                }
            }
        }
        check(deadline);
        return new Evidence(Instant.now(), ready, failed, String.join("|", progress), reason,
                List.copyOf(facts), List.copyOf(items), List.copyOf(issues));
    }
    private long verifyGeneration(Run run, Target target, GenericKubernetesResource resource) {
        String key = target.uid();
        com.fasterxml.jackson.databind.node.ObjectNode spec = json.valueToTree(
                resource.getAdditionalProperties().getOrDefault("spec", Map.of()));
        long sequence = spec.path("retrySequence").asLong(0);
        boolean release = target.kind() == Kind.PlatformRelease || target.kind() == Kind.ServicePublish;
        if (release) spec.remove("retrySequence");
        String hash;
        try {
            String canonical = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(json.convertValue(spec, new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String, Object>>() {}));
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("无法核验目标配置"); }
        long actual = resource.getMetadata().getGeneration();
        long expected = run.effectiveGenerations.getOrDefault(key, target.generation());
        if (run.specHashes.containsKey(key) && !run.specHashes.get(key).equals(hash)) throw new Superseded();
        if (actual != expected) {
            long baselineGeneration = run.actionGenerations.getOrDefault(key, target.generation());
            long baselineSequence = run.actionRetrySequences.getOrDefault(key, run.retrySequences.getOrDefault(key, 0L));
            if (!(release && run.state == State.VERIFYING && run.recovery != null && actual == expected + 1
                    && run.specHashes.containsKey(key) && actual == baselineGeneration + 1
                    && sequence == baselineSequence + 1
                    && run.recovery.id().equals(detail(run).path("recovery").path("id").asText()))) throw new Superseded();
            // 兼容旧任务快照，但一次恢复的基线只允许初始化一次。
            run.actionGenerations.putIfAbsent(key, baselineGeneration);
            run.actionRetrySequences.putIfAbsent(key, baselineSequence);
        }
        run.specHashes.putIfAbsent(key, hash);
        run.effectiveGenerations.put(key, actual);
        run.retrySequences.put(key, sequence);
        return actual;
    }
    private void collectRelated(Target target, List<String> facts, List<EvidenceItem> items,
            List<CollectionIssue> issues, long deadline) {
        String namespace = target.namespace();
        Set<String> owners = new HashSet<>(Set.of(target.uid()));
        // 只沿 ownerReference UID 收集子资源，避免把同命名空间其他租户的日志混入。
        expand(owners, kubernetes.apps().deployments().inNamespace(namespace).list().getItems());
        check(deadline);
        expand(owners, kubernetes.apps().statefulSets().inNamespace(namespace).list().getItems());
        check(deadline);
        expand(owners, kubernetes.batch().v1().jobs().inNamespace(namespace).list().getItems());
        check(deadline);
        expand(owners, kubernetes.apps().replicaSets().inNamespace(namespace).list().getItems());
        check(deadline);
        List<Pod> pods = kubernetes.pods().inNamespace(namespace).list().getItems().stream()
                .filter(p -> owned(p, owners)).sorted(Comparator.comparing(p -> p.getMetadata().getName())).limit(4).toList();
        for (Pod pod : pods) {
            check(deadline);
            String uid = pod.getMetadata().getUid();
            owners.add(uid);
            if (pod.getStatus() == null) continue;
            facts.add(redactor.clean("Pod=" + pod.getMetadata().getName() + " phase=" + pod.getStatus().getPhase()));
            List<ContainerStatus> statuses = new ArrayList<>();
            if (pod.getStatus().getInitContainerStatuses() != null) statuses.addAll(pod.getStatus().getInitContainerStatuses());
            if (pod.getStatus().getContainerStatuses() != null) statuses.addAll(pod.getStatus().getContainerStatuses());
            for (ContainerStatus container : statuses) {
                ContainerState state = container.getState();
                if (state == null) continue;
                if (state.getWaiting() != null) facts.add(redactor.clean(state.getWaiting().getReason() + " " + state.getWaiting().getMessage()));
                if (state.getTerminated() != null) facts.add(redactor.clean(state.getTerminated().getReason() + " exitCode=" + state.getTerminated().getExitCode()));
                String code = state.getWaiting() != null ? state.getWaiting().getReason()
                        : state.getTerminated() != null ? state.getTerminated().getReason() : "Running";
                String message = state.getWaiting() != null ? state.getWaiting().getMessage() : "";
                items.add(new EvidenceItem("CONTAINER/" + pod.getMetadata().getName() + "/" + container.getName(), uid,
                        target.generation(), Instant.now(), redactor.clean(code), redactor.clean(message), false));
                // 可选日志失败保留 CR 证据，并禁止本次自动动作；日志本身永不授予执行权限。
                if (state.getRunning() != null || state.getTerminated() != null) {
                    check(deadline);
                    try {
                        String log = kubernetes.pods().inNamespace(namespace).withName(pod.getMetadata().getName())
                                .inContainer(container.getName()).limitBytes(4096).tailingLines(60).getLog();
                        facts.add(redactor.clean(log));
                        items.add(new EvidenceItem("LOG/" + pod.getMetadata().getName() + "/" + container.getName(), uid,
                                target.generation(), Instant.now(), "", redactor.clean(log), false));
                    } catch (RuntimeException failure) {
                        check(deadline);
                        issues.add(new CollectionIssue("LOG/" + pod.getMetadata().getName(), uid, errorType(failure)));
                    }
                }
            }
        }
        check(deadline);
        kubernetes.v1().events().inNamespace(namespace).list().getItems().stream()
                .filter(e -> e.getInvolvedObject() != null && owners.contains(e.getInvolvedObject().getUid()))
                .sorted(Comparator.comparing(e -> Objects.toString(e.getLastTimestamp(), ""), Comparator.reverseOrder()))
                .limit(15).forEach(e -> facts.add(redactor.clean(e.getReason() + " " + e.getMessage())));
    }
    private void expand(Set<String> owners, List<? extends HasMetadata> resources) {
        for (HasMetadata resource : resources) if (owned(resource, owners)) owners.add(resource.getMetadata().getUid());
    }
    private boolean owned(HasMetadata resource, Set<String> owners) {
        return resource.getMetadata().getOwnerReferences() != null && resource.getMetadata().getOwnerReferences()
                .stream().anyMatch(ref -> owners.contains(ref.getUid()));
    }
    public Optional<Recovery> lookupRecovery(Run run) {
        JsonNode task = detail(run);
        if (ops.strictPlatformContract()) assertBinding(run, task);
        JsonNode recovery = task.path("recovery");
        if (run.actionKey != null && run.actionKey.equals(recovery.path("requestKey").asText())
                && !recovery.path("id").asText().isBlank()) {
            if (ops.strictPlatformContract() && !run.deploymentVersion.equals(recovery.path("acceptedVersion").asText()))
                throw new Superseded("平台动作对账版本不匹配");
            return Optional.of(new Recovery(recovery.path("id").asText(), recovery.path("state").asText()));
        }
        return Optional.empty();
    }
    public Recovery retry(Run run) {
        // 平台维护持久化恢复请求、组件预算和 Operator 确认，Agent 不直接 patch 子资源。
        // 响应丢失后先允许按原键重放，平台对 requestKey 的判定先于状态门禁。
        // 本地已落盘的同一动作不会生成新的恢复请求键。
        StepControl.check();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestKey", run.actionKey);
        String endpoint = "/provisioning-instances/{id}/retry";
        if (ops.strictPlatformContract()) {
            assertBinding(run, detail(run));
            if (run.actionSnapshotToken == null || run.actionSnapshotToken.isBlank())
                throw new IllegalStateException("缺少已持久化的动作快照前提");
            body.put("expectedVersion", run.deploymentVersion);
            body.put("expectedSnapshotToken", run.actionSnapshotToken);
            body.put("verificationProfile", run.verificationProfile);
            body.put("runbookId", run.plan.runbookId());
            body.put("runbookVersion", run.plan.runbookVersion());
            body.put("expectedTargets", run.targets.stream().map(t -> Map.of("kind", t.kind().name(),
                    "namespace", t.namespace(), "name", t.name(), "uid", t.uid(),
                    "generation", run.actionGenerations.getOrDefault(t.uid(), t.generation()),
                    "specHash", Objects.requireNonNull(run.specHashes.get(t.uid())),
                    "retrySequence", run.actionRetrySequences.getOrDefault(t.uid(), 0L))).toList());
            endpoint = "/provisioning-instances/{id}/agent-recoveries";
        }
        StepControl.check();
        JsonNode response = platform.post().uri(endpoint, run.provisioningId).body(body).retrieve()
                .onStatus(s -> s.value() == 409 || s.value() == 412, (request, result) -> {
                    throw new Superseded("平台拒绝过期版本或快照，停止本次恢复");
                }).body(JsonNode.class);
        JsonNode data = successful(response);
        if (data.path("id").asText().isBlank()) throw new IllegalStateException("平台未返回恢复身份");
        if (!run.actionKey.equals(data.path("requestKey").asText()))
            throw new IllegalStateException("平台已有其他恢复请求，停止与其他操作并发恢复");
        if (ops.strictPlatformContract() && !run.deploymentVersion.equals(data.path("acceptedVersion").asText()))
            throw new IllegalStateException("平台恢复响应未确认受理版本，保留动作身份等待对账");
        return new Recovery(data.path("id").asText(), data.path("state").asText());
    }
    public boolean verify(Run run) {
        JsonNode task = detail(run);
        if (ops.strictPlatformContract()) assertBinding(run, task);
        if (!"SUCCEEDED".equals(task.path("status").asText())) return false;
        if (run.recovery != null) {
            JsonNode recovery = task.path("recovery");
            if (!run.recovery.id().equals(recovery.path("id").asText()))
                throw new Superseded("验收期间恢复请求发生变化，停止当前任务");
            run.recovery = new Recovery(recovery.path("id").asText(), recovery.path("state").asText());
            if (!"SUCCEEDED".equals(run.recovery.state())) return false;
        }
        // 探测地址来自部署配置，HTTP 状态和业务健康字段必须同时通过，不跟随重定向。
        java.net.URI url = config.verificationUrl();
        if (run.verificationProfile != null) {
            OpsProperties.VerificationProfile profile = ops.verificationProfiles().get(run.verificationProfile);
            if (profile == null) throw new IllegalStateException("验收目录已失效");
            url = profile.url();
        } else if (ops.strictPlatformContract()) throw new IllegalStateException("任务未绑定验收目录");
        if (url == null) throw new IllegalStateException("未配置任务验收地址");
        StepControl.check();
        JsonNode result = health.get().uri(url).retrieve().body(JsonNode.class);
        return result != null && "UP".equals(result.path("status").asText())
                && (run.verificationProfile == null || Objects.equals(ops.strictPlatformContract()?run.applicationVersion:run.deploymentVersion, result.path("version").asText()));
    }
    private JsonNode detail(Run run) {
        StepControl.check();
        JsonNode response = platform.get().uri("/provisioning-instances/{id}", run.provisioningId)
                .retrieve().body(JsonNode.class);
        JsonNode data = successful(response);
        if (!run.provisioningId.equals(data.path("id").asText())) throw new IllegalStateException("平台任务身份不匹配");
        return data;
    }
    public void validateRegistration(StartRequest request) {
        if (!ops.strictPlatformContract()) return;
        Run run = new Run(request, Instant.now());
        JsonNode task = detail(run);
        assertBinding(run, task);
        if (!new HashSet<>(targets(task)).equals(new HashSet<>(request.targets())))
            throw new Superseded("注册目标与平台受权快照不一致");
    }
    public List<Target> discover(Run run) {
        if (!ops.strictPlatformContract()) throw new IllegalStateException("自动发现仅支持严格平台契约");
        JsonNode task = detail(run);
        assertIdentity(run, task);
        List<Target> targets = targets(task);
        if ("FAILED".equals(task.path("status").asText()) && (!task.path("targetsComplete").asBoolean() || targets.isEmpty()))
            throw new Superseded("平台前置阶段失败且尚未创建资源，需要人工处理");
        return task.path("targetsComplete").asBoolean() ? targets : List.of();
    }
    public StartRequest registration(String provisioningId) {
        if(!ops.strictPlatformContract())throw new IllegalStateException("查询平台发布身份需要严格契约");
        if(provisioningId==null||!provisioningId.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("平台任务身份无效");
        Run probe=new Run(new StartRequest("preview",provisioningId,List.of()),Instant.now());
        JsonNode task=detail(probe);
        probe.deploymentVersion=task.path("version").asText();probe.verificationProfile=task.path("verificationProfile").asText();
        assertIdentity(probe,task);
        if(!task.path("targetsComplete").asBoolean())throw new Superseded("平台完整目标尚未就绪，当前仅可进行只读调查");
        var targets=targets(task);if(targets.isEmpty())throw new Superseded("平台尚未创建完整发布目标");
        return new StartRequest("preview",provisioningId,targets,probe.deploymentVersion,probe.verificationProfile);
    }
    private void assertBinding(Run run, JsonNode task) {
        assertIdentity(run, task);
        if (!task.path("targetsComplete").asBoolean()) throw new Superseded("平台目标集合尚未完成，不能冻结部分发布目标");
        Set<String> expected = new HashSet<>();
        for (Target target : run.targets) expected.add(identity(target));
        Set<String> actual = new HashSet<>();
        for (Target target : targets(task)) actual.add(identity(target));
        if (!expected.equals(actual)) throw new Superseded("平台目标归属发生变化");
    }
    private void assertIdentity(Run run, JsonNode task) {
        String cluster=task.path("clusterApiUrl").asText("");
        if(cluster.isBlank()||!com.lanhao.ops.domain.DeploymentIdentity.endpoint(cluster).equals(com.lanhao.ops.domain.DeploymentIdentity.endpoint(clusterApiUrl())))
            throw new Superseded("平台发布与当前执行身份的集群 API 不一致或缺失，停止本次任务");
        if (run.deploymentVersion == null || run.verificationProfile == null
                || !ops.verificationProfiles().containsKey(run.verificationProfile))
            throw new IllegalArgumentException("严格模式需要发布版本和受权验收目录");
        if (!run.deploymentVersion.equals(task.path("version").asText())
                || !run.verificationProfile.equals(task.path("verificationProfile").asText()))
            throw new Superseded("发布版本或受权验收目录发生变化");
        if (!task.path("targetsComplete").isBoolean())
            throw new IllegalStateException("平台快照缺少目标集合完成标志");
        String applicationVersion=task.path("applicationVersion").asText("");
        if(applicationVersion.isBlank()||applicationVersion.length()>100)
            throw new IllegalStateException("平台缺少实际应用发布版本，不能完成受控验收");
        if(run.applicationVersion!=null&&!run.applicationVersion.equals(applicationVersion))
            throw new Superseded("实际应用发布版本已经变化，停止本次任务");
        run.applicationVersion=applicationVersion;
    }
    private List<Target> targets(JsonNode task) {
        if (!task.path("targets").isArray() || task.path("targets").size() > 10)
            throw new IllegalStateException("平台目标快照结构不兼容");
        List<Target> targets = new ArrayList<>();
        for (JsonNode node : task.path("targets")) {
            Target target = json.convertValue(node, Target.class);
            if (target.kind() == null || target.generation() < 1 || target.uid() == null || target.uid().isBlank()
                    || !config.namespaces().contains(target.namespace()) || target.name() == null
                    || !target.name().matches("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?"))
                throw new IllegalStateException("平台快照包含未授权或非法目标");
            targets.add(target);
        }
        if (targets.stream().map(this::identity).distinct().count() != targets.size())
            throw new IllegalStateException("平台目标不可重复");
        if (targets.stream().map(Target::uid).distinct().count() != targets.size()
                || targets.stream().map(t -> t.kind() + "/" + t.namespace() + "/" + t.name()).distinct().count() != targets.size())
            throw new IllegalStateException("平台目标资源身份冲突");
        return List.copyOf(targets);
    }
    private String identity(Target target) {
        return target.kind() + "/" + target.namespace() + "/" + target.name() + "/" + target.uid();
    }
    private static void check(long deadline) {
        StepControl.check();
        if (System.nanoTime() >= deadline) throw new IllegalStateException("采证总预算耗尽");
    }
    private static String errorType(RuntimeException failure) {
        if (failure instanceof io.fabric8.kubernetes.client.KubernetesClientException k)
            return "KUBERNETES_HTTP_" + k.getCode();
        return "COLLECTION_UNAVAILABLE";
    }
    private JsonNode successful(JsonNode response) {
        if (response == null || response.path("code").asInt() != 200 || !response.path("data").isObject())
            throw new IllegalStateException("平台业务接口返回失败或结构不兼容");
        return response.path("data");
    }
}
