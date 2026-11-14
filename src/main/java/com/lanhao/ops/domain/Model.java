package com.lanhao.ops.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class Model {
    private Model() {}

    public enum Kind {
        ProjectPostgres("projectpostgreses"), ProjectRuntime("projectruntimes"),
        PlatformRelease("platformreleases"), ServicePublish("servicepublishes");
        public final String plural;
        Kind(String plural) { this.plural = plural; }
    }

    public enum State {
        DISCOVERING, MONITORING, DIAGNOSING, WAITING_APPROVAL, RETRY_WAIT, EXECUTING, VERIFYING,
        SUCCEEDED, MANUAL_REQUIRED, FAILED, CANCELLED;
        public boolean terminal() {
            return this == SUCCEEDED || this == MANUAL_REQUIRED || this == FAILED || this == CANCELLED;
        }
    }
    public enum Action { RETRY_TRANSIENT, MANUAL_REPAIR }

    public record Target(@NotNull Kind kind,
            @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String namespace,
            @NotBlank @Size(max = 253) @Pattern(regexp = "[a-z0-9]([-a-z0-9.]*[a-z0-9])?") String name,
            @NotBlank @Size(max = 100) String uid, @Min(1) long generation) {}

    public record StartRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String requestKey,
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") @DecimalMax("9223372036854775807") String provisioningId,
            @NotEmpty @Size(max = 10) List<@NotNull @Valid Target> targets,
            @Size(max = 100) String deploymentVersion,
            @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String verificationProfile) {
        public StartRequest(String requestKey, String provisioningId, List<Target> targets) {
            this(requestKey, provisioningId, targets, null, null);
        }
    }

    public record DeploymentEvent(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String eventId,
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") @DecimalMax("9223372036854775807") String provisioningId,
            @NotBlank @Size(max = 100) String deploymentVersion,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String verificationProfile) {}

    public record EvidenceItem(String source, String resourceUid, long generation, Instant collectedAt,
            String reason, String message, boolean decisive) {}
    public record CollectionIssue(String source, String resourceUid, String errorType) {}

    public record Evidence(Instant observedAt, boolean ready, boolean failed,
            String fingerprint, String reason, List<String> facts,
            List<EvidenceItem> items, List<CollectionIssue> issues) {
        public Evidence(Instant at, boolean ready, boolean failed, String fingerprint, String reason, List<String> facts) {
            this(at, ready, failed, fingerprint, reason, facts,
                    List.of(new EvidenceItem("DEMO", "demo", 1, at, reason, String.join("\n", facts), true)), List.of());
        }
    }

    public record MemoryReference(UUID id,String title,String source,long revision) {
        public MemoryReference(UUID id,String title,String source){this(id,title,source,0);}
    }
    public record Diagnosis(String cause, List<String> checks, List<String> repairSteps,
            String verification, String source,List<MemoryReference> memories,List<UUID> commandExecutions) {
        public Diagnosis { memories=memories==null?List.of():List.copyOf(memories);commandExecutions=commandExecutions==null?List.of():List.copyOf(commandExecutions); }
        public Diagnosis(String cause,List<String> checks,List<String> repairSteps,String verification,String source,List<MemoryReference> memories){
            this(cause,checks,repairSteps,verification,source,memories,List.of());
        }
        public Diagnosis(String cause,List<String> checks,List<String> repairSteps,String verification,String source) {
            this(cause,checks,repairSteps,verification,source,List.of());
        }
    }

    public record Assessment(String candidateCause,List<String> supportingEvidence,List<String> opposingEvidence,
            List<String> missingEvidence,Action suggestedAction,String verificationConditions) {}
    public record Plan(String id, Action action, Diagnosis diagnosis, boolean automatic, Instant createdAt,
            String runbookId, int runbookVersion,Assessment assessment) {
        public Plan(String id,Action action,Diagnosis diagnosis,boolean automatic,Instant createdAt,String runbookId,int runbookVersion) {
            this(id,action,diagnosis,automatic,createdAt,runbookId,runbookVersion,null);
        }
        public Plan(String id, Action action, Diagnosis diagnosis, boolean automatic, Instant createdAt) {
            this(id, action, diagnosis, automatic, createdAt,
                    action == Action.RETRY_TRANSIENT ? "platform-transient-retry" : "manual-investigation", 1);
        }
    }
    public record Event(Instant at, State state, String message) {}
    public record Recovery(String id, String state) {}

    public static class Run {
        public UUID id;
        public String requestKey;
        public String provisioningId;
        public String environment;
        public String clusterApiUrl;
        public String platformApiUrl;
        public List<Target> targets;
        public String deploymentVersion;
        /** 平台绑定的实际应用发布版本，与部署生命周期身份分开冻结。 */
        public String applicationVersion;
        public String verificationProfile;
        public String platformSnapshotToken;
        public String actionSnapshotToken;
        public State state = State.MONITORING;
        public Instant createdAt;
        public Instant lastProgressAt;
        public Instant verificationStartedAt;
        public String progressFingerprint;
        public Map<String, String> specHashes = new HashMap<>();
        public Map<String, Long> effectiveGenerations = new HashMap<>();
        public Map<String, Long> retrySequences = new HashMap<>();
        public Map<String, Long> actionGenerations = new HashMap<>();
        public Map<String, Long> actionRetrySequences = new HashMap<>();
        public Evidence evidence;
        public Evidence diagnosisEvidence;
        public Plan plan;
        public String actionKey;
        public Recovery recovery;
        public int attempts;
        public int stableCount;
        public int transportFailures;
        public List<Event> events = new ArrayList<>();

        public Run() {}
        public boolean verifiedRecovery() {
            return state == State.SUCCEEDED && attempts > 0 && stableCount > 0 && actionKey != null && !actionKey.isBlank()
                    && recovery != null && recovery.id() != null && !recovery.id().isBlank()
                    && "SUCCEEDED".equals(recovery.state()) && plan != null && plan.action() == Action.RETRY_TRANSIENT
                    && plan.diagnosis() != null;
        }
        public Run(StartRequest request, Instant now) {
            id = UUID.randomUUID();
            requestKey = request.requestKey();
            provisioningId = request.provisioningId();
            targets = List.copyOf(request.targets());
            deploymentVersion = request.deploymentVersion();
            verificationProfile = request.verificationProfile();
            createdAt = now;
            lastProgressAt = now;
            transition(State.MONITORING, "监控已创建，等待目标版本收敛", now);
        }
        public void transition(State next, String message, Instant now) {
            state = next;
            events.add(new Event(now, next, message));
            if (events.size() > 100) events.removeFirst();
        }
    }
}
