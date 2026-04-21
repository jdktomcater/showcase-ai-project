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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @Test
    void shouldUseCompactPromptForFinalDecision() {
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
        state.setBranch("main");
        state.setSha("46d25588aeec5b7be3e6afc28be8af925e45c47e");
        state.setChangedFiles(1);
        state.setAdditions(1);
        state.setDeletions(0);
        state.setCodeImpactSummary("x".repeat(2500));
        state.setBusinessReport("x".repeat(2500));
        state.setConventionReport("x".repeat(2500));
        state.setPerformanceReport("x".repeat(2500));
        state.setSecurityReport("x".repeat(2500));

        agent.validate(state);

        verify(chatModel, times(1)).call(argThat((String prompt) -> prompt.contains("专项审查摘要")));
        assertThat(state.getDecision()).isEqualTo("PASS");
    }

    @Test
    void shouldRecoverMissingSummaryAndFinalReportFromModelResult() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "decision": "FAIL"
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

        assertThat(state.getDecision()).isEqualTo("FAIL");
        assertThat(state.getFinalReport()).contains("## 总体结论");
        assertThat(state.getFinalReport()).contains("## 关键风险");
        assertThat(state.getFinalReport()).contains("## 建议动作");
        assertThat(state.getFinalReport()).doesNotContain("模型未返回有效总结");
        assertThat(state.getTelegramMessage()).contains("结论: FAIL");
    }

    @Test
    void shouldRecoverTruncatedJsonDecisionResult() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {"decision":"PASS","summary":"新增info日志，风险低，无安全或性能问题","finalReport":"## 总体结论\\n本次提交仅在 OrderServiceImpl#createOrder 中新增一条 info 级别日志，未涉及业务逻辑变更，风险等级评估为低风险，建议通过。\\n\\n## 关键风险\\n暂无关键风险点，未发现安全、关键业务链路受损或显著性能问题。\\n\\n## 建议动作\\n1.`
                """);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("chatModel", chatModel);
        ReviewChatService reviewChatService = new ReviewChatService(beanFactory.getBeanProvider(ChatModel.class));
        ReflectionTestUtils.setField(reviewChatService, "aiReviewEnabled", true);
        ReflectionTestUtils.setField(reviewChatService, "failOpen", true);

        CommitResultAgent agent = new CommitResultAgent(reviewChatService);
        CommitTaskState state = new CommitTaskState();
        state.setRepository("jdktomcater/showcase-pay");

        agent.validate(state);

        assertThat(state.getDecision()).isEqualTo("PASS");
        assertThat(state.getFinalReport()).contains("## 总体结论");
        assertThat(state.getFinalReport()).contains("## 建议动作");
        assertThat(state.getTelegramMessage()).contains("结论: PASS");
    }
}
