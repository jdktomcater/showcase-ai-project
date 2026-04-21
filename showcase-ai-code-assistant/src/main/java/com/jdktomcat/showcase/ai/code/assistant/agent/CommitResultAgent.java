package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import com.jdktomcat.showcase.ai.code.assistant.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结果校验 Agent：检查报告是否满足用户需求，不满足则标记重试
 */
@Component
@Slf4j
public class CommitResultAgent implements NodeAction<CommitTaskState> {

    private static final Pattern DECISION_PATTERN =
            Pattern.compile("\"decision\"\\s*:\\s*\"(PASS|FAIL)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY_PATTERN =
            Pattern.compile("\"summary\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);

    private final ReviewChatService reviewChatService;

    @Value("${app.ai.review.final-section-max-chars:900}")
    private int finalSectionMaxChars;

    public CommitResultAgent(ReviewChatService reviewChatService) {
        this.reviewChatService = reviewChatService;
    }

    public void validate(CommitTaskState state) {
        boolean allSpecialReportsUnavailable = areAllSpecialReportsUnavailable(state);

        String validationResult = reviewChatService.callOrFallback(
                "final-decision-compact",
                buildCompactDecisionPrompt(state),
                () -> fallbackDecisionJson(allSpecialReportsUnavailable),
                false
        );
        if (isBlankResponse(validationResult)) {
            log.warn("final-decision 仍为空响应，使用兜底决策 JSON");
            validationResult = fallbackDecisionJson(allSpecialReportsUnavailable);
        }
        log.info("提交裁决原始结果：{}", validationResult);
        Map<String, Object> result;
        try {
            result = JSONUtils.parseMap(extractJson(validationResult));
        } catch (Exception ex) {
            log.warn("提交裁决结果解析失败，使用兜底逻辑 raw={}", validationResult, ex);
            result = recoverMalformedDecisionResult(validationResult);
        }
        String decision = Objects.toString(result.getOrDefault("decision", "FAIL")).trim().toUpperCase();
        String summary = resolveSummary(result, decision);
        String finalReport = appendAffectedEntryPointsSection(
                resolveFinalReport(result, decision, summary),
                state.getAffectedEntryPoints()
        );
        String telegramMessage = appendAffectedEntryPointsSummary(
                resolveTelegramMessage(result, decision, summary),
                state.getAffectedEntryPoints()
        );
        boolean passed = "PASS".equals(decision);
        state.setDecision(decision);
        state.setPassed(passed);
        state.setFinalReport(finalReport);
        state.setTelegramMessage(telegramMessage);
        state.setNeedRetry(false);
    }

    private String appendAffectedEntryPointsSection(String finalReport, List<AffectedEntryPoint> affectedEntryPoints) {
        if (affectedEntryPoints == null || affectedEntryPoints.isEmpty()) {
            return finalReport;
        }
        if (finalReport.contains("## 影响入口点") && containsAnyEntryPointReference(finalReport, affectedEntryPoints)) {
            return finalReport;
        }
        if (finalReport.contains("## 影响入口点")) {
            return finalReport.stripTrailing() + "\n\n## 影响入口点（系统补充）\n" + formatAffectedEntryPoints(affectedEntryPoints);
        }
        return finalReport.stripTrailing() + "\n\n## 影响入口点\n" + formatAffectedEntryPoints(affectedEntryPoints);
    }

    private String appendAffectedEntryPointsSummary(String telegramMessage, List<AffectedEntryPoint> affectedEntryPoints) {
        if (affectedEntryPoints == null || affectedEntryPoints.isEmpty()) {
            return telegramMessage;
        }
        if (telegramMessage.contains("影响入口点")
                && containsAnyEntryPointReference(telegramMessage, affectedEntryPoints)) {
            return telegramMessage;
        }
        return telegramMessage.stripTrailing() + "\n影响入口点: " + affectedEntryPoints.stream()
                .limit(3)
                .map(this::shortEntryPointLabel)
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    private String compactForPrompt(String content) {
        return compactForPrompt(content, Math.max(400, finalSectionMaxChars));
    }

    private String compactForPrompt(String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return "-";
        }
        String normalized = content
                .replace('\r', '\n')
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
        int safeMaxChars = Math.max(120, maxChars);
        if (normalized.length() <= safeMaxChars) {
            return normalized;
        }
        int headLength = Math.max(80, safeMaxChars - 20);
        if (headLength >= normalized.length()) {
            return normalized;
        }
        return normalized.substring(0, headLength) + "\n...[内容截断]...";
    }

    private String buildCompactDecisionPrompt(CommitTaskState state) {
        return String.format("""
                你是提交评审裁决专家，请根据专项审查摘要输出最终 JSON。
                仅输出合法 JSON，不要 Markdown 代码块。
                decision 只能是 "PASS" 或 "FAIL"。
                判定：出现高风险安全、关键业务链路受损、显著性能风险或严重规范问题则 FAIL，否则 PASS。
                JSON 字段：
                {"decision":"PASS/FAIL","summary":"<=50字","finalReport":"包含 ## 总体结论/## 关键风险/## 建议动作","telegramMessage":"<=120字"}
                
                上下文：
                - 仓库：%s
                - 分支：%s
                - 提交：%s
                - 文件：%s，新增：%s，删除：%s
                
                专项审查摘要：
                - Business: %s
                - Convention: %s
                - Performance: %s
                - Security: %s
                - Impact: %s
                """,
                state.getRepository(),
                state.getBranch(),
                state.getSha(),
                Objects.toString(state.getChangedFiles(), "0"),
                Objects.toString(state.getAdditions(), "0"),
                Objects.toString(state.getDeletions(), "0"),
                compactForPrompt(state.getBusinessReport(), 180),
                compactForPrompt(state.getConventionReport(), 150),
                compactForPrompt(state.getPerformanceReport(), 150),
                compactForPrompt(state.getSecurityReport(), 150),
                compactForPrompt(state.getCodeImpactSummary(), 200)
        );
    }

    private boolean isBlankResponse(String value) {
        return value == null || value.isBlank();
    }

    private String resolveSummary(Map<String, Object> result, String decision) {
        String summary = normalizeModelField(result.get("summary"));
        if (summary != null) {
            return summary;
        }
        String telegramMessage = normalizeModelField(result.get("telegramMessage"));
        if (telegramMessage != null) {
            return truncateForSummary(telegramMessage);
        }
        String finalReport = normalizeModelField(result.get("finalReport"));
        if (finalReport != null) {
            return truncateForSummary(finalReport.replaceAll("(?m)^##\\s*", ""));
        }
        if ("PASS".equals(decision)) {
            return "未发现阻断发布风险，请结合专项报告复核";
        }
        return "检测到中高风险，请优先处理后再发布";
    }

    private String resolveFinalReport(Map<String, Object> result, String decision, String summary) {
        String finalReport = normalizeModelField(result.get("finalReport"));
        if (finalReport != null) {
            return finalReport;
        }
        return "## 总体结论\n" + decision
                + "\n\n## 关键风险\n- " + summary
                + "\n\n## 建议动作\n- 结合业务/规范/性能/安全专项报告进行人工复核。";
    }

    private String resolveTelegramMessage(Map<String, Object> result, String decision, String summary) {
        String telegramMessage = normalizeModelField(result.get("telegramMessage"));
        if (telegramMessage != null) {
            return telegramMessage;
        }
        return "结论: " + decision + "\n原因: " + truncateForSummary(summary);
    }

    private String normalizeModelField(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    private String truncateForSummary(String text) {
        if (text == null || text.isBlank()) {
            return "模型未提供可用摘要";
        }
        String normalized = text.replace('\n', ' ').replaceAll("\\s{2,}", " ").trim();
        int max = 50;
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max - 1) + "…";
    }

    private Map<String, Object> recoverMalformedDecisionResult(String rawOutput) {
        String decision = extractDecision(rawOutput);
        String summary = extractSummary(rawOutput);
        if (summary == null || summary.isBlank()) {
            summary = "PASS".equals(decision)
                    ? "模型输出被截断，已按低风险补全结构化结果，请人工复核"
                    : "模型输出被截断，已按高风险补全结构化结果，请人工复核";
        }
        String finalReport = "## 总体结论\n" + decision
                + "\n\n## 关键风险\n- " + summary
                + "\n\n## 建议动作\n- 模型输出存在截断或格式异常，请人工复核后再决策。";
        String telegramMessage = "结论: " + decision + "\n原因: " + truncateForSummary(summary);
        return Map.of(
                "decision", decision,
                "summary", summary,
                "finalReport", finalReport,
                "telegramMessage", telegramMessage
        );
    }

    private String extractDecision(String rawOutput) {
        if (rawOutput == null) {
            return "FAIL";
        }
        Matcher matcher = DECISION_PATTERN.matcher(rawOutput);
        if (!matcher.find()) {
            return "FAIL";
        }
        return matcher.group(1).toUpperCase();
    }

    private String extractSummary(String rawOutput) {
        if (rawOutput == null) {
            return null;
        }
        Matcher matcher = SUMMARY_PATTERN.matcher(rawOutput);
        if (!matcher.find()) {
            return null;
        }
        return unescapeJsonString(matcher.group(1)).trim();
    }

    private String unescapeJsonString(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private String fallbackDecisionJson(boolean allSpecialReportsUnavailable) {
        if (allSpecialReportsUnavailable) {
            return """
                    {
                      "decision": "PASS",
                      "summary": "AI 模型当前不可用，已返回降级评审结果，请结合专项报告人工复核",
                      "finalReport": "## 总体结论\\nPASS\\n\\n## 关键风险\\n- AI 模型当前不可用，最终结论基于降级逻辑生成。\\n- 业务、性能、安全专项报告可能为兜底内容，需要人工复核。\\n\\n## 建议动作\\n- 检查 OLLAMA_BASE_URL、OLLAMA_CHAT_MODEL 或切换到可用云模型后重新执行评审。\\n- 在模型恢复前，将本次评审视为非阻断参考结果。",
                      "telegramMessage": "结论: PASS\\n原因: AI 模型当前不可用，已返回降级评审结果，请人工复核。"
                    }
                    """;
        }
        return """
                {
                  "decision": "PASS",
                  "summary": "最终裁决模型未返回有效结果，已返回降级评审结论，请结合专项报告人工复核",
                  "finalReport": "## 总体结论\\nPASS\\n\\n## 关键风险\\n- 最终裁决模型未返回有效结果，当前结论基于降级逻辑生成。\\n- 至少部分专项报告已正常产出，请以专项报告为主进行复核。\\n\\n## 建议动作\\n- 优先根据业务/规范/性能/安全专项报告确认发布风险。\\n- 如需统一裁决结论，可在模型恢复后重新执行最终裁决。",
                  "telegramMessage": "结论: PASS\\n原因: 最终裁决模型未返回有效结果，请优先参考专项报告并人工复核。"
                }
                """;
    }

    private boolean areAllSpecialReportsUnavailable(CommitTaskState state) {
        return isUnavailableReport(state.getBusinessReport())
                && isUnavailableReport(state.getConventionReport())
                && isUnavailableReport(state.getPerformanceReport())
                && isUnavailableReport(state.getSecurityReport());
    }

    private boolean isUnavailableReport(String report) {
        if (report == null || report.isBlank()) {
            return true;
        }
        String normalized = report.replace(" ", "");
        return normalized.contains("AI模型当前不可用")
                || normalized.contains("未完成自动")
                || normalized.contains("模型恢复后重新");
    }

    private String formatAffectedEntryPoints(List<AffectedEntryPoint> affectedEntryPoints) {
        if (affectedEntryPoints == null || affectedEntryPoints.isEmpty()) {
            return "- 未识别到直接相关的入口点";
        }
        StringBuilder builder = new StringBuilder();
        for (AffectedEntryPoint entryPoint : affectedEntryPoints) {
            builder.append("- ").append(shortEntryPointLabel(entryPoint))
                    .append(" -> ")
                    .append(entryPoint.getClassName())
                    .append('#')
                    .append(entryPoint.getMethodName())
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private String shortEntryPointLabel(AffectedEntryPoint entryPoint) {
        if (entryPoint.getRoute() != null && !entryPoint.getRoute().isBlank()) {
            return entryPoint.getType() + " `" + entryPoint.getRoute() + "`";
        }
        return entryPoint.getType() + " " + entryPoint.getMethodSignature();
    }

    private boolean containsAnyEntryPointReference(String text, List<AffectedEntryPoint> affectedEntryPoints) {
        if (text == null || text.isBlank() || affectedEntryPoints == null || affectedEntryPoints.isEmpty()) {
            return false;
        }
        for (AffectedEntryPoint entryPoint : affectedEntryPoints) {
            if (entryPoint.getRoute() != null && !entryPoint.getRoute().isBlank() && text.contains(entryPoint.getRoute())) {
                return true;
            }
            if (entryPoint.getMethodSignature() != null && !entryPoint.getMethodSignature().isBlank()
                    && text.contains(entryPoint.getMethodSignature())) {
                return true;
            }
            if (entryPoint.getClassName() != null && entryPoint.getMethodName() != null
                    && !entryPoint.getClassName().isBlank() && !entryPoint.getMethodName().isBlank()
                    && text.contains(entryPoint.getClassName() + "#" + entryPoint.getMethodName())) {
                return true;
            }
        }
        return false;
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
        if (start >= 0) {
            return trimmed.substring(start);
        }
        return "{}";
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        this.validate(commitState);
        return commitState.toMap();
    }
}
