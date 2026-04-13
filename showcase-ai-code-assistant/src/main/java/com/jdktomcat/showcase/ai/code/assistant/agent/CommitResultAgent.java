package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * 结果校验 Agent：检查报告是否满足用户需求，不满足则标记重试
 */
@Component
@Slf4j
public class CommitResultAgent implements NodeAction<CommitTaskState> {

    private final ChatModel chatModel;

    public CommitResultAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void validate(CommitTaskState state) {
        String prompt = String.format("""
                你是最终提交评审裁决专家。请综合 4 个专项审查报告，对本次提交是否允许通过给出最终结论。
                判断原则：
                1. 如果存在明确高风险安全问题、关键业务逻辑缺失、显著性能风险、或严重规范问题导致可维护性/可运行性受损，则判定 FAIL。
                2. 如果只有一般性优化建议、非阻断告警、或低风险规范问题，则判定 PASS。
                3. 输出必须是合法 JSON，禁止输出 Markdown 代码块。
                
                JSON 结构如下：
                {
                  "decision": "PASS 或 FAIL",
                  "summary": "一句话总结该提交的总体风险",
                  "finalReport": "Markdown 格式的最终汇总，包含“## 总体结论”“## 关键风险”“## 建议动作”三个部分",
                  "telegramMessage": "适合直接发到 Telegram 的简洁 Markdown 文本"
                }
                
                提交上下文：
                - 仓库：%s
                - 分支：%s
                - 提交：%s
                - 作者：%s <%s>
                - 变更文件数：%s
                - 新增行数：%s
                - 删除行数：%s
                - Compare URL：%s
                
                依赖图影响面摘要：
                %s
                
                BusinessAgent 报告：
                %s
                
                ConventionAgent 报告：
                %s
                
                PerformanceAgent 报告：
                %s
                
                SecurityAgent 报告：
                %s
                """,
                state.getRepository(),
                state.getBranch(),
                state.getSha(),
                state.getAuthor(),
                state.getEmail(),
                Objects.toString(state.getChangedFiles(), "0"),
                Objects.toString(state.getAdditions(), "0"),
                Objects.toString(state.getDeletions(), "0"),
                state.getCompareUrl(),
                state.getCodeImpactSummary(),
                state.getBusinessReport(),
                state.getConventionReport(),
                state.getPerformanceReport(),
                state.getSecurityReport()
        );
        String validationResult = chatModel.call(prompt);
        log.info("提交裁决原始结果：{}", validationResult);
        Map<String, Object> result;
        try {
            result = JSONUtils.parseMap(extractJson(validationResult));
        } catch (Exception ex) {
            log.warn("提交裁决结果解析失败，使用兜底逻辑 raw={}", validationResult, ex);
            result = Map.of("decision", "FAIL", "summary", "模型返回结果无法解析，已按高风险处理", "finalReport", "## 总体结论\nFAIL\n\n## 关键风险\n- 模型返回结果无法解析，请人工复核。\n\n## 建议动作\n- 检查模型输出格式并重新触发评审。", "telegramMessage", "结论: FAIL\n原因: 模型返回结果无法解析，请人工复核。");
        }
        String decision = Objects.toString(result.getOrDefault("decision", "FAIL")).trim().toUpperCase();
        String summary = Objects.toString(result.getOrDefault("summary", "模型未返回有效总结"));
        String finalReport = Objects.toString(result.getOrDefault("finalReport", summary));
        String telegramMessage = Objects.toString(result.getOrDefault("telegramMessage", finalReport));
        boolean passed = "PASS".equals(decision);
        state.setDecision(decision);
        state.setPassed(passed);
        state.setFinalReport(finalReport);
        state.setTelegramMessage(telegramMessage);
        state.setNeedRetry(false);
    }

    private String extractJson(String modelOutput) {
        if (modelOutput == null) {
            return "{}";
        }
        String trimmed = modelOutput.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```json\\s*", "").replaceFirst("^```\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        this.validate(commitState);
        return commitState.toMap();
    }
}
