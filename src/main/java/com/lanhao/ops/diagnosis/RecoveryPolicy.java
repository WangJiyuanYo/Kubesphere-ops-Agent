package com.lanhao.ops.diagnosis;

import com.lanhao.ops.domain.Model.Action;
import com.lanhao.ops.domain.Model.Evidence;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.regex.Pattern;

/** 自动动作来源于版本化规则，模型、日志中的指令均不能扩大权限。 */
@Component
public class RecoveryPolicy {
    private static final Pattern TRANSIENT = Pattern.compile(
            "(?i)(i/o timeout|TLS handshake timeout|connection timed out|temporary failure in name resolution|HTTP[_ :]*(?:429|503|504)\\b|(?:REGISTRY|NETWORK|UPSTREAM)_TIMEOUT|HTTP (?:status|code)[ :=]*(?:429|503|504)\\b)");
    public Action classify(Evidence evidence) {
        if (evidence == null || evidence.items() == null || evidence.issues() == null || !evidence.issues().isEmpty()
                || !evidence.failed()) return Action.MANUAL_REPAIR;
        String current = evidence.items().stream().filter(item -> item.decisive())
                .map(item -> item.reason() + "\n" + item.message()).collect(java.util.stream.Collectors.joining("\n"));
        String safety = evidence.items().stream().filter(item -> item.decisive() || item.source().startsWith("CONTAINER/"))
                .map(item -> item.reason() + "\n" + item.message()).collect(java.util.stream.Collectors.joining("\n"));
        if (blocked(safety.toLowerCase(Locale.ROOT))) return Action.MANUAL_REPAIR;
        return classify(current);
    }
    public Action classify(String evidence) {
        String text = evidence.toLowerCase(Locale.ROOT);
        return !blocked(text) && TRANSIENT.matcher(text).find() ? Action.RETRY_TRANSIENT : Action.MANUAL_REPAIR;
    }
    public boolean hasBlockingEvidence(String evidence){return blocked(evidence.toLowerCase(Locale.ROOT));}
    private boolean blocked(String text) {
        return java.util.List.of("unauthorized", "forbidden", "noauth", "oomkilled", "manifest unknown",
                "not found", "invalid", "password", "permission denied", "insufficient", "failedscheduling",
                "corrupt", "migration", "pvc", "volume", "authentication", "access denied")
                .stream().anyMatch(text::contains);
    }
}
