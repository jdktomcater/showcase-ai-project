package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityAgentTest {

    @Test
    void shouldGenerateThreeReportsInSingleModelCall() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "conventionReport": "## 结论\\n风险等级：低风险\\n\\n## 发现\\n- 规范问题较少\\n\\n## 建议\\n- 规范层面可接受",
                  "performanceReport": "## 结论\\n风险等级：低风险\\n\\n## 发现\\n- 未发现显著性能风险\\n\\n## 建议\\n- 持续关注高并发场景",
                  "securityReport": "## 结论\\n风险等级：低风险\\n\\n## 发现\\n- 未发现阻断发布的安全风险\\n\\n## 建议\\n- 按最小权限原则持续加固"
                }
                """);

        QualityAgent agent = buildAgent(chatModel);
        CommitTaskState state = new CommitTaskState();
        state.setRepository("jdktomcater/showcase-pay");
        state.setBranch("main");
        state.setSha("46d25588aeec5b7be3e6afc28be8af925e45c47e");
        state.setMessage("日志优化");
        state.setDiff("File: src/main/java/com/demo/OrderService.java\n+ log.info(\"ok\");");
        state.setBusinessReport("## 结论\n风险等级：低风险");

        agent.review(state);

        verify(chatModel, times(1)).call(anyString());
        assertThat(state.getConventionReport()).contains("## 结论");
        assertThat(state.getPerformanceReport()).contains("## 结论");
        assertThat(state.getSecurityReport()).contains("## 结论");
        assertThat(state.getDecision()).isEqualTo("PASS");
        assertThat(state.getFinalReport()).contains("## 总体结论");
        assertThat(state.getTelegramMessage()).contains("结论:");
    }

    @Test
    void shouldFallbackWhenAnyReportFieldMissing() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "conventionReport": "## 结论\\n风险等级：中风险\\n\\n## 发现\\n- 存在重复日志\\n\\n## 建议\\n- 清理重复输出",
                  "performanceReport": ""
                }
                """);

        QualityAgent agent = buildAgent(chatModel);
        CommitTaskState state = new CommitTaskState();
        state.setRepository("jdktomcater/showcase-pay");
        state.setBranch("main");
        state.setMessage("日志优化");
        state.setDiff("File: src/main/java/com/demo/OrderService.java\n+ log.info(\"ok\");");

        agent.review(state);

        assertThat(state.getConventionReport()).contains("中风险");
        assertThat(state.getPerformanceReport()).contains("AI 模型当前不可用");
        assertThat(state.getSecurityReport()).contains("AI 模型当前不可用");
        assertThat(state.getDecision()).isEqualTo("PASS");
        assertThat(state.getFinalReport()).contains("## 建议动作");
    }

    @Test
    void shouldAppendAffectedEntryPointsToFinalReportAndTelegram() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "conventionReport": "## 结论\\n风险等级：低风险\\n\\n## 发现\\n- 规范问题较少\\n\\n## 建议\\n- 规范层面可接受",
                  "performanceReport": "## 结论\\n风险等级：低风险\\n\\n## 发现\\n- 未发现显著性能风险\\n\\n## 建议\\n- 持续关注高并发场景",
                  "securityReport": "## 结论\\n风险等级：低风险\\n\\n## 发现\\n- 未发现阻断发布的安全风险\\n\\n## 建议\\n- 按最小权限原则持续加固",
                  "decision": "PASS",
                  "summary": "风险可控",
                  "finalReport": "## 总体结论\\nPASS\\n\\n## 关键风险\\n- 无阻断项\\n\\n## 建议动作\\n- 合并后观察",
                  "telegramMessage": "结论: PASS"
                }
                """);

        QualityAgent agent = buildAgent(chatModel);
        CommitTaskState state = new CommitTaskState();
        state.setRepository("jdktomcater/showcase-pay");
        state.setBranch("main");
        state.setSha("46d25588aeec5b7be3e6afc28be8af925e45c47e");
        state.setMessage("日志优化");
        state.setDiff("File: src/main/java/com/demo/OrderService.java\n+ log.info(\"ok\");");
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

        agent.review(state);

        assertThat(state.getFinalReport()).contains("## 影响入口点");
        assertThat(state.getFinalReport()).contains("/api/order/create");
        assertThat(state.getTelegramMessage()).contains("影响入口点");
    }

    @Test
    void shouldRecoverTruncatedJsonDecisionResult() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {"decision":"PASS","summary":"新增info日志，风险低，无安全或性能问题","finalReport":"## 总体结论\\n本次提交仅在 OrderServiceImpl#createOrder 中新增一条 info 级别日志，未涉及业务逻辑变更，风险等级评估为低风险，建议通过。\\n\\n## 关键风险\\n暂无关键风险点，未发现安全、关键业务链路受损或显著性能问题。\\n\\n## 建议动作\\n1.`
                """);

        QualityAgent agent = buildAgent(chatModel);
        CommitTaskState state = new CommitTaskState();
        state.setRepository("jdktomcater/showcase-pay");
        state.setBranch("main");
        state.setMessage("日志优化");
        state.setDiff("File: src/main/java/com/demo/OrderService.java\n+ log.info(\"ok\");");

        agent.review(state);

        verify(chatModel, times(1)).call(argThat((String prompt) -> prompt.contains("必填 JSON 字段")));
        assertThat(state.getDecision()).isEqualTo("PASS");
        assertThat(state.getFinalReport()).contains("## 总体结论");
        assertThat(state.getFinalReport()).contains("## 建议动作");
        assertThat(state.getTelegramMessage()).contains("结论: PASS");
    }

    private QualityAgent buildAgent(ChatModel chatModel) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("chatModel", chatModel);

        ReviewChatService reviewChatService = new ReviewChatService(beanFactory.getBeanProvider(ChatModel.class));
        ReflectionTestUtils.setField(reviewChatService, "aiReviewEnabled", true);
        ReflectionTestUtils.setField(reviewChatService, "failOpen", true);

        QualityAgent agent = new QualityAgent(reviewChatService);
        ReflectionTestUtils.setField(agent, "qualityDiffMaxChars", 4200);
        ReflectionTestUtils.setField(agent, "qualityBusinessMaxChars", 260);
        ReflectionTestUtils.setField(agent, "qualityImpactMaxChars", 360);
        ReflectionTestUtils.setField(agent, "qualityPromptMaxChars", 4800);
        return agent;
    }
}
