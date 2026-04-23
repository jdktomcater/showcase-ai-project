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

@Slf4j
@Component
public class QualityAgent implements NodeAction<CommitTaskState> {

    private static final String BLANK_RETRY_SENTINEL = "__QUALITY_MODEL_EMPTY__";

    private static final Pattern DECISION_PATTERN =
            Pattern.compile("\"decision\"\\s*:\\s*\"(PASS|FAIL)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY_PATTERN =
            Pattern.compile("\"summary\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);

    private final ReviewChatService reviewChatService;

    @Value("${app.ai.review.quality-diff-max-chars:4200}")
    private int qualityDiffMaxChars;
    @Value("${app.ai.review.final-section-max-chars:900}")
    private int finalSectionMaxChars;
    @Value("${app.ai.review.quality-business-max-chars:260}")
    private int qualityBusinessMaxChars;
    @Value("${app.ai.review.quality-impact-max-chars:360}")
    private int qualityImpactMaxChars;
    @Value("${app.ai.review.quality-prompt-max-chars:4800}")
    private int qualityPromptMaxChars;

    public QualityAgent(ReviewChatService reviewChatService) {
        this.reviewChatService = reviewChatService;
    }

    public void review(CommitTaskState state) {
        String response = invokeModelWithCompactRetry(state);

        Map<String, Object> result;
        try {
            result = JSONUtils.parseMap(extractJson(response));
        } catch (Exception ex) {
            log.warn("质量评审结果解析失败，尝试恢复结构化结果 raw={}", response, ex);
            result = recoverMalformedReviewResult(response);
        }

        String conventionReport = normalizeField(result.get("conventionReport"));
        String performanceReport = normalizeField(result.get("performanceReport"));
        String securityReport = normalizeField(result.get("securityReport"));

        if (conventionReport == null) {
            conventionReport = fallbackConventionReport();
        }
        if (performanceReport == null) {
            performanceReport = fallbackPerformanceReport();
        }
        if (securityReport == null) {
            securityReport = fallbackSecurityReport();
        }

        String decision = resolveDecision(result, conventionReport, performanceReport, securityReport);
        String summary = resolveSummary(result, decision, state, conventionReport, performanceReport, securityReport);
        String finalReport = appendAffectedEntryPointsSection(
                resolveFinalReport(result, decision, summary),
                state.getAffectedEntryPoints()
        );
        String telegramMessage = appendAffectedEntryPointsSummary(
                resolveTelegramMessage(result, decision, summary),
                state.getAffectedEntryPoints()
        );

        state.setConventionReport(conventionReport);
        state.setPerformanceReport(performanceReport);
        state.setSecurityReport(securityReport);
        state.setDecision(decision);
        state.setPassed("PASS".equals(decision));
        state.setFinalReport(finalReport);
        state.setTelegramMessage(telegramMessage);
        state.setNeedRetry(false);

        log.info("质量审查完成（单次模型调用），decision={} 规范={} 性能={} 安全={}",
                decision, conventionReport.length(), performanceReport.length(), securityReport.length());
    }

    private String invokeModelWithCompactRetry(CommitTaskState state) {
        String primaryResponse = reviewChatService.callOrFallback(
                "quality-review",
                limitQualityPrompt(buildUnifiedPrompt(state)),
                this::blankRetrySentinel,
                false
        );
        if (!isBlankRetrySentinel(primaryResponse) && !isBlankResponse(primaryResponse)) {
            return primaryResponse;
        }

        log.warn("quality-review 首次返回空结果，使用紧凑提示词重试一次");
        String compactResponse = reviewChatService.callOrFallback(
                "quality-review-compact",
                limitQualityPrompt(buildCompactRetryPrompt(state)),
                () -> fallbackUnifiedJson(state),
                false
        );
        if (isBlankResponse(compactResponse) || isBlankRetrySentinel(compactResponse)) {
            log.warn("quality-review-compact 仍为空响应，使用兜底结果");
            return fallbackUnifiedJson(state);
        }
        return compactResponse;
    }

    private String buildUnifiedPrompt(CommitTaskState state) {
        return String.format("""
                你是代码评审专家。请基于同一份 Diff，一次性输出规范、性能、安全三份审查报告，并给出最终发布裁决。
                只输出合法 JSON，不要 Markdown 代码块，不要额外解释。
                
                必填 JSON 字段：
                {
                  "conventionReport":"...",
                  "performanceReport":"...",
                  "securityReport":"...",
                  "decision":"PASS/FAIL",
                  "summary":"<=50字"
                }
                可选 JSON 字段：
                {"telegramMessage":"<=120字","finalReport":"包含 ## 总体结论/## 关键风险/## 建议动作"}
                注意：可选字段缺失时系统会自动生成，严禁返回空字符串。
                
                字段要求（每个 report 都必须满足）：
                - 使用中文 Markdown。
                - 包含且仅包含以下三级结构：
                  ## 结论
                  风险等级：低风险 / 中风险 / 高风险

                ## 发现
                - 最多 2 条
                  
                ## 建议
                - 最多 2 条
                - 每个 report 尽量不超过 160 字。
                
                审查重点：
                - conventionReport：命名、异常处理、风格一致性、重复逻辑、可测试性。
                - performanceReport：循环复杂度、IO/数据库/远程调用、并发竞争、缓存命中、对象与连接资源管理。
                - securityReport：注入、鉴权与越权、敏感信息、反序列化、文件与资源访问控制（参考 OWASP Top 10）。
                - decision 判定：出现高风险安全、关键业务链路受损、显著性能风险或阻断发布问题则 FAIL，否则 PASS。
                - summary/finalReport/telegramMessage 必须与三个专项报告及业务风险摘要一致。
                
                提交上下文：
                - 仓库：%s
                - 分支：%s
                - 提交：%s
                - 提交说明：%s
                - 文件：%s，新增：%s，删除：%s

                业务专项摘要：
                %s

                影响面摘要：
                %s

                关联入口点：
                %s

                Diff:
                %s
                """,
                state.getRepository(),
                state.getBranch(),
                state.getSha(),
                state.getMessage(),
                Objects.toString(state.getChangedFiles(), "0"),
                Objects.toString(state.getAdditions(), "0"),
                Objects.toString(state.getDeletions(), "0"),
                compactForPrompt(state.getBusinessReport(), Math.max(180, qualityBusinessMaxChars)),
                compactForPrompt(state.getCodeImpactSummary(), Math.max(240, qualityImpactMaxChars)),
                formatAffectedEntryPoints(state.getAffectedEntryPoints()),
                compactDiff(state.getDiff())
        );
    }

    private String buildCompactRetryPrompt(CommitTaskState state) {
        return String.format("""
                你是代码评审专家。模型上次响应为空，请基于最小上下文输出非空 JSON。
                仅输出 JSON，不要代码块、不要解释。

                必填字段：
                {"conventionReport":"...","performanceReport":"...","securityReport":"...","decision":"PASS/FAIL","summary":"<=40字"}
                可选字段：
                {"telegramMessage":"<=80字"}

                强约束：
                - 所有必填字段必须非空。
                - 每个 report 用中文 Markdown，结构固定：
                  ## 结论
                  风险等级：低风险 / 中风险 / 高风险
                  ## 发现
                  - 最多 1 条
                  ## 建议
                  - 最多 1 条
                - 若上下文不足，使用“未识别明显风险”补齐，不得留空。

                提交：
                - 仓库：%s
                - 分支：%s
                - 提交：%s
                - 文件：%s，新增：%s，删除：%s
                - 入口点：%s

                业务摘要（压缩）：
                %s

                影响摘要（压缩）：
                %s

                Diff（压缩）：
                %s
                """,
                state.getRepository(),
                state.getBranch(),
                state.getSha(),
                Objects.toString(state.getChangedFiles(), "0"),
                Objects.toString(state.getAdditions(), "0"),
                Objects.toString(state.getDeletions(), "0"),
                shortEntryPointsSummary(state.getAffectedEntryPoints()),
                compactForPrompt(state.getBusinessReport(), 180),
                compactForPrompt(state.getCodeImpactSummary(), 220),
                compactDiff(state.getDiff())
        );
    }

    private String shortEntryPointsSummary(List<AffectedEntryPoint> affectedEntryPoints) {
        if (affectedEntryPoints == null || affectedEntryPoints.isEmpty()) {
            return "-";
        }
        return affectedEntryPoints.stream()
                .limit(2)
                .map(this::shortEntryPointLabel)
                .reduce((left, right) -> left + "; " + right)
                .orElse("-");
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

    private String compactDiff(String diff) {
        if (diff == null || diff.isBlank()) {
            return "No file diff available.";
        }
        int safeMaxChars = Math.max(1200, qualityDiffMaxChars);
        if (diff.length() <= safeMaxChars) {
            return diff;
        }
        return diff.substring(0, safeMaxChars) + "\n...[diff truncated for unified quality review]...";
    }

    private String limitQualityPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        int safeMaxChars = Math.max(1800, qualityPromptMaxChars);
        if (prompt.length() <= safeMaxChars) {
            return prompt;
        }
        return prompt.substring(0, safeMaxChars)
                + "\n...[quality prompt truncated to avoid empty model response]...";
    }

    private String extractJson(String modelOutput) {
        if (modelOutput == null) {
            return "{}";
        }
        String trimmed = modelOutput.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```json\\s*", "")
                    .replaceFirst("^```\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }

    private String normalizeField(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        if (value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    private String fallbackUnifiedJson(CommitTaskState state) {
        boolean allSpecialReportsUnavailable = areAllSpecialReportsUnavailable(state,
                fallbackConventionReport(), fallbackPerformanceReport(), fallbackSecurityReport());
        String summary = allSpecialReportsUnavailable
                ? "AI 模型当前不可用，已返回降级评审结果，请结合专项报告人工复核"
                : "质量评审模型未返回完整结果，请优先参考专项报告并人工复核";
        String finalReport = "## 总体结论\nPASS"
                + "\n\n## 关键风险\n- " + summary
                + "\n\n## 建议动作\n- 检查模型配置后重试，并结合专项报告进行人工复核。";
        String telegramMessage = "结论: PASS\n原因: " + truncateForSummary(summary);
        return "{"
                + "\"conventionReport\":" + toJsonString(fallbackConventionReport()) + ","
                + "\"performanceReport\":" + toJsonString(fallbackPerformanceReport()) + ","
                + "\"securityReport\":" + toJsonString(fallbackSecurityReport()) + ","
                + "\"decision\":\"PASS\","
                + "\"summary\":" + toJsonString(summary) + ","
                + "\"finalReport\":" + toJsonString(finalReport) + ","
                + "\"telegramMessage\":" + toJsonString(telegramMessage)
                + "}";
    }

    private String toJsonString(String raw) {
        return JSONUtils.toJSONString(raw);
    }

    private String fallbackConventionReport() {
        return """
                ## 结论
                风险等级：低风险

                ## 发现
                - AI 模型当前不可用，未完成自动规范审查

                ## 建议
                - 检查 OLLAMA_BASE_URL、OLLAMA_CHAT_MODEL 或切换到可用云模型后重试
                """;
    }

    private String fallbackPerformanceReport() {
        return """
                ## 结论
                风险等级：低风险

                ## 发现
                - AI 模型当前不可用，未完成自动性能审查

                ## 建议
                - 模型恢复后重新执行性能审查，重点关注长链路、数据库和远程调用
                """;
    }

    private String fallbackSecurityReport() {
        return """
                ## 结论
                风险等级：低风险

                ## 发现
                - AI 模型当前不可用，未完成自动安全审查

                ## 建议
                - 模型恢复后重新执行安全审查，并人工关注鉴权、注入和敏感信息处理
                """;
    }

    private Map<String, Object> recoverMalformedReviewResult(String rawOutput) {
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
                "conventionReport", fallbackConventionReport(),
                "performanceReport", fallbackPerformanceReport(),
                "securityReport", fallbackSecurityReport(),
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

    private String resolveDecision(Map<String, Object> result,
                                   String conventionReport,
                                   String performanceReport,
                                   String securityReport) {
        String rawDecision = normalizeField(result.get("decision"));
        if (rawDecision != null) {
            String normalized = rawDecision.toUpperCase();
            if ("PASS".equals(normalized) || "FAIL".equals(normalized)) {
                return normalized;
            }
        }
        if (containsBlockingRisk(conventionReport)
                || containsBlockingRisk(performanceReport)
                || containsBlockingRisk(securityReport)) {
            return "FAIL";
        }
        return "PASS";
    }

    private String resolveSummary(Map<String, Object> result,
                                  String decision,
                                  CommitTaskState state,
                                  String conventionReport,
                                  String performanceReport,
                                  String securityReport) {
        String summary = normalizeField(result.get("summary"));
        if (summary != null) {
            return truncateForSummary(summary);
        }
        String telegramMessage = normalizeField(result.get("telegramMessage"));
        if (telegramMessage != null) {
            return truncateForSummary(telegramMessage);
        }
        String finalReport = normalizeField(result.get("finalReport"));
        if (finalReport != null) {
            return truncateForSummary(finalReport.replaceAll("(?m)^##\\s*", ""));
        }
        if (areAllSpecialReportsUnavailable(state, conventionReport, performanceReport, securityReport)) {
            return "AI 模型当前不可用，已返回降级评审结果，请结合专项报告人工复核";
        }
        if ("PASS".equals(decision)) {
            return "未发现阻断发布风险，请结合专项报告复核";
        }
        return "检测到中高风险，请优先处理后再发布";
    }

    private String resolveFinalReport(Map<String, Object> result, String decision, String summary) {
        String finalReport = normalizeField(result.get("finalReport"));
        if (finalReport != null) {
            return finalReport;
        }
        return "## 总体结论\n" + decision
                + "\n\n## 关键风险\n- " + summary
                + "\n\n## 建议动作\n- 结合业务/规范/性能/安全专项报告进行人工复核。";
    }

    private String resolveTelegramMessage(Map<String, Object> result, String decision, String summary) {
        String telegramMessage = normalizeField(result.get("telegramMessage"));
        if (telegramMessage != null) {
            return telegramMessage;
        }
        return "结论: " + decision + "\n原因: " + truncateForSummary(summary);
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

    private boolean containsBlockingRisk(String report) {
        if (report == null || report.isBlank()) {
            return false;
        }
        String normalized = report.replace(" ", "");
        boolean highRisk = normalized.contains("高风险")
                && !normalized.contains("风险等级：低风险")
                && !normalized.contains("风险等级：中风险");
        boolean hasBlockingPhrase = normalized.contains("阻断发布")
                && !normalized.contains("未发现阻断发布")
                && !normalized.contains("无阻断发布")
                && !normalized.contains("未见阻断发布");
        boolean performanceRisk = normalized.contains("显著性能风险")
                && !normalized.contains("未发现显著性能风险")
                && !normalized.contains("无显著性能风险")
                && !normalized.contains("未见显著性能风险");
        boolean businessChainRisk = normalized.contains("关键业务链路受损")
                && !normalized.contains("未发现关键业务链路受损")
                && !normalized.contains("无关键业务链路受损")
                && !normalized.contains("未见关键业务链路受损");
        return highRisk
                || hasBlockingPhrase
                || businessChainRisk
                || performanceRisk;
    }

    private boolean areAllSpecialReportsUnavailable(CommitTaskState state,
                                                    String conventionReport,
                                                    String performanceReport,
                                                    String securityReport) {
        return isUnavailableReport(state.getBusinessReport())
                && isUnavailableReport(conventionReport)
                && isUnavailableReport(performanceReport)
                && isUnavailableReport(securityReport);
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

    private String appendAffectedEntryPointsSection(String finalReport, List<AffectedEntryPoint> affectedEntryPoints) {
        if (affectedEntryPoints == null || affectedEntryPoints.isEmpty()) {
            return finalReport;
        }
        if (finalReport.contains("## 影响入口点") && containsAnyEntryPointReference(finalReport, affectedEntryPoints)) {
            return finalReport;
        }
        if (finalReport.contains("## 影响入口点")) {
            return finalReport.stripTrailing() + "\n\n## 影响入口点（系统补充）\n"
                    + formatAffectedEntryPoints(affectedEntryPoints);
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

    private boolean isBlankResponse(String value) {
        return value == null || value.isBlank();
    }

    private String blankRetrySentinel() {
        return BLANK_RETRY_SENTINEL;
    }

    private boolean isBlankRetrySentinel(String response) {
        return BLANK_RETRY_SENTINEL.equals(response);
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        review(commitState);
        return commitState.toMap();
    }
}
