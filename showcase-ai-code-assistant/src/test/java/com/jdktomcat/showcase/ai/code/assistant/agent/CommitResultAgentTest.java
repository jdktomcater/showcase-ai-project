package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

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

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("chatModel", chatModel);
        ReviewChatService reviewChatService = new ReviewChatService(beanFactory.getBeanProvider(ChatModel.class));
        ReflectionTestUtils.setField(reviewChatService, "aiReviewEnabled", true);
        ReflectionTestUtils.setField(reviewChatService, "failOpen", true);

        CommitResultAgent agent = new CommitResultAgent(reviewChatService);
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

    @Test
    void shouldAppendEntryPointsEvenWhenModelMentionsImpactSectionWithoutDetails() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "decision": "PASS",
                  "summary": "风险可控",
                  "finalReport": "## 总体结论\\nPASS\\n\\n## 关键风险\\n- 无阻断项\\n\\n## 建议动作\\n- 合并后观察\\n\\n## 影响入口点\\n- 详见系统分析",
                  "telegramMessage": "结论: PASS\\n影响入口点详见报告"
                }
                """);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("chatModel", chatModel);
        ReviewChatService reviewChatService = new ReviewChatService(beanFactory.getBeanProvider(ChatModel.class));
        ReflectionTestUtils.setField(reviewChatService, "aiReviewEnabled", true);
        ReflectionTestUtils.setField(reviewChatService, "failOpen", true);

        CommitResultAgent agent = new CommitResultAgent(reviewChatService);
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

        assertThat(state.getFinalReport()).contains("影响入口点（系统补充）");
        assertThat(state.getFinalReport()).contains("/api/order/create");
        assertThat(state.getTelegramMessage()).contains("/api/order/create");
    }
}
