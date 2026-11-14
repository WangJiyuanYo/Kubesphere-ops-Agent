package com.lanhao.ops;

import com.lanhao.ops.diagnosis.AiNarrator;
import com.lanhao.ops.domain.Model.Action;
import com.lanhao.ops.evidence.Redactor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiNarratorTest {
    @Test void preservesSafeFailureClassificationAndSeparatesInvalidStructuredOutput() {
        ChatModel model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new com.lanhao.ops.console.ModelFailure(com.lanhao.ops.console.ModelFailure.Code.RATE_LIMIT,429));
        assertThat(new AiNarrator(model,new Redactor()).explain("503",Action.RETRY_TRANSIENT).source())
                .contains("LLM_RATE_LIMIT","HTTP 429","回退规则手册");
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("invalid-output")))));
        assertThat(new AiNarrator(model,new Redactor()).explain("503",Action.RETRY_TRANSIENT).source()).contains("LLM_INVALID_RESPONSE");
    }
    @Test void parsesStructuredModelOutputAndRedactsIt() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"cause":"网络超时 password=example-value", "checks":["检查 DNS"], "repairSteps":["等待仓库恢复"], "verification":"健康为 UP"}
                """)))));
        var result = new AiNarrator(model, new Redactor()).explain("503", Action.RETRY_TRANSIENT);
        assertThat(result.source()).contains("DashScope");
        assertThat(result.cause()).doesNotContain("example-value");
        assertThat(result.checks()).containsExactly("检查 DNS");
    }
    @Test void modelFailureFallsBackToRunbook() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("unavailable"));
        var result = new AiNarrator(model, new Redactor()).explain("503", Action.RETRY_TRANSIENT);
        assertThat(result.source()).contains("回退规则手册");
        assertThat(result.repairSteps()).isNotEmpty();
    }
}
