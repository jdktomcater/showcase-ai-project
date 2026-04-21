package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
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
        state.setMessage("日志优化");
        state.setDiff("File: src/main/java/com/demo/OrderService.java\n+ log.info(\"ok\");");

        agent.review(state);

        verify(chatModel, times(1)).call(anyString());
        assertThat(state.getConventionReport()).contains("## 结论");
        assertThat(state.getPerformanceReport()).contains("## 结论");
        assertThat(state.getSecurityReport()).contains("## 结论");
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
    }

    private QualityAgent buildAgent(ChatModel chatModel) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("chatModel", chatModel);

        ReviewChatService reviewChatService = new ReviewChatService(beanFactory.getBeanProvider(ChatModel.class));
        ReflectionTestUtils.setField(reviewChatService, "aiReviewEnabled", true);
        ReflectionTestUtils.setField(reviewChatService, "failOpen", true);

        QualityAgent agent = new QualityAgent(reviewChatService);
        ReflectionTestUtils.setField(agent, "qualityDiffMaxChars", 4200);
        return agent;
    }
}
