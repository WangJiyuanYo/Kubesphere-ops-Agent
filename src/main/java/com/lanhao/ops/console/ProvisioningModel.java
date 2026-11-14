package com.lanhao.ops.console;

import com.lanhao.ops.domain.Model.Target;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ProvisioningModel {
    private ProvisioningModel() {}
    public enum PhaseState { RUNNING, BLOCKED, FAILED, SUCCEEDED, CANCELLED }
    public enum Level { OBSERVE, DIAGNOSE, APPROVAL, AUTO_LOW_RISK }
    public record StageEvent(@Min(1) @Max(1) int schemaVersion,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String eventId,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String platformInstanceId,
            @NotBlank @Size(max=80) String workspaceId,
            @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String siteId,
            @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") @DecimalMax("9223372036854775807") String provisioningId,
            @NotBlank @Size(max=100) String deploymentVersion,
            @Min(1) long sequence,
            @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,79}") String phase,
            @NotNull PhaseState state,
            @NotNull Instant phaseStartedAt,@NotNull Instant occurredAt,
            @Min(0) long completedUnits,
            @Size(max=100) String errorCode,@Size(max=2000) String summary,
            @Size(max=20) List<@Pattern(regexp="[A-Z][A-Z0-9_]{0,79}") String> dependencies,
            @Size(max=80) String projectId,@Size(max=500) String clusterApiUrl,
            @Size(max=63) String namespace,
            @NotNull @Size(max=10) List<@NotNull @Valid Target> resources,
            @NotNull Instant provisioningStartedAt,boolean terminal) {}
    public record Watch(UUID id,long revision,StageEvent stage,Instant createdAt,Instant phaseStartedAt,
            Instant lastMeaningfulProgressAt,Instant lastObservedAt,Instant authorizedAt,String authorizationHash,
            String observationWarning,boolean paused,UUID runId) {}
    public record Receipt(String eventId,UUID watchId,String status) {}
    public record PolicyInput(@Min(0) long revision,@NotBlank @Size(max=80) String workspaceId,
            @Size(max=19) String siteId,@Size(max=80) String projectId,@NotNull UUID connectionId,
            @NotBlank String namespace,@NotNull Level level,boolean enabled,
            @Min(60) @Max(86400) int stallSeconds,@Min(600) @Max(604800) int totalSeconds,
            @Min(1) @Max(3) int maxDiagnoses,@Min(30) @Max(86400) int cooldownSeconds) {}
    public record Policy(UUID id,long revision,PolicyInput scope,String clusterApiUrl,long connectionRevision,
            String actor,Instant updatedAt) {}
    public record Trigger(UUID id,UUID watchId,UUID policyId,long policyRevision,String fingerprint,
            UUID discoveryId,UUID requestId,String state,String detail,Instant createdAt) {}
}
