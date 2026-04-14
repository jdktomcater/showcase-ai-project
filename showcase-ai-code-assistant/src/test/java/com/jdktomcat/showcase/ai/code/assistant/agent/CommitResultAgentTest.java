package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommitResultAgentTest {

    @Test
    void shouldAppendAffectedEntryPointsToFinalReport() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "decision": "PASS",
                  "summary": "风险可控",
                  "finalReport": "## 总体结论\\nPASS\\n\\n## 关键风险\\n- 无阻断项\\n\\n## 建议动作\\n- 合并后观察",
                  "telegramMessage": "结论: PASS"
                }
                """);

        CommitResultAgent agent = new CommitResultAgent(chatModel);
        CommitTaskState state = new CommitTaskState();
        state.setRepository("jdktomcater/showcase-pay");
        state.setAffectedEntryPoints(List.of(
                new AffectedEntryPoint(
                        "HTTP:com.demo.OrderController#create()",
                        "HTTP",
                        "/api/order/create",
                        "POST",
                        "com.demo.OrderController",
                        "create",
                        "com.demo.OrderController#create()"
                )
        ));

        agent.validate(state);

        assertThat(state.getFinalReport()).contains("## 影响入口点");
        assertThat(state.getFinalReport()).contains("/api/order/create");
        assertThat(state.getTelegramMessage()).contains("影响入口点");
    }
}
