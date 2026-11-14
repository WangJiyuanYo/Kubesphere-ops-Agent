package com.lanhao.ops;

import com.lanhao.ops.diagnosis.RecoveryPolicy;
import com.lanhao.ops.domain.Model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RecoveryPolicyTest {
    private final RecoveryPolicy policy = new RecoveryPolicy();
    private Evidence evidence(String reason, String message, List<EvidenceItem> extra, List<CollectionIssue> issues) {
        var items = new java.util.ArrayList<EvidenceItem>();
        items.add(new EvidenceItem("CR/ProjectRuntime/runtime", "uid-1", 1, Instant.now(), reason, message, true));
        items.addAll(extra);
        return new Evidence(Instant.now(), false, true, "failed", reason, List.of(message), items, issues);
    }
    @Test void historicalLogsAndBareNumbersCannotGrantRetry() {
        var log = new EvidenceItem("LOG/pod/container", "pod", 1, Instant.now(), "", "old HTTP 503 i/o timeout", false);
        assertThat(policy.classify(evidence("Unknown", "record 503", List.of(log), List.of()))).isEqualTo(Action.MANUAL_REPAIR);
    }
    @Test void currentCrWithExplicitTransientFailureCanRetry() {
        assertThat(policy.classify(evidence("NETWORK_TIMEOUT", "", List.of(), List.of()))).isEqualTo(Action.RETRY_TRANSIENT);
        assertThat(policy.classify(evidence("UpstreamFailed", "HTTP status=503", List.of(), List.of()))).isEqualTo(Action.RETRY_TRANSIENT);
    }
    @Test void partialCollectionAndCurrentContainerOomOverrideTransientEvidence() {
        assertThat(policy.classify(evidence("NETWORK_TIMEOUT", "", List.of(),
                List.of(new CollectionIssue("LOG/pod", "pod", "KUBERNETES_HTTP_403"))))).isEqualTo(Action.MANUAL_REPAIR);
        var oom = new EvidenceItem("CONTAINER/pod/app", "pod", 1, Instant.now(), "OOMKilled", "", false);
        assertThat(policy.classify(evidence("NETWORK_TIMEOUT", "", List.of(oom), List.of()))).isEqualTo(Action.MANUAL_REPAIR);
    }
    @Test void oldSnapshotWithoutStructuredEvidenceRequiresNewDiagnosis() {
        assertThat(policy.classify(new Evidence(Instant.now(), false, true, "x", "HTTP_503", List.of(), null, null)))
                .isEqualTo(Action.MANUAL_REPAIR);
    }
}
