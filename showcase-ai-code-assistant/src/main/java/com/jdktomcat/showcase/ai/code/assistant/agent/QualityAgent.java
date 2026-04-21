package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import com.jdktomcat.showcase.ai.code.assistant.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class QualityAgent implements NodeAction<CommitTaskState> {

    private final ReviewChatService reviewChatService;

    @Value("${app.ai.review.quality-diff-max-chars:4200}")
    private int qualityDiffMaxChars;

    public QualityAgent(ReviewChatService reviewChatService) {
        this.reviewChatService = reviewChatService;
    }

    public void review(CommitTaskState state) {
        String response = reviewChatService.callOrFallback(
                "quality-review",
                buildUnifiedPrompt(state),
                this::fallbackQualityJson,
                false
        );

        Map<String, Object> result;
        try {
            result = JSONUtils.parseMap(extractJson(response));
        } catch (Exception ex) {
            log.warn("质量评审结果解析失败，使用兜底逻辑 raw={}", response, ex);
            result = JSONUtils.parseMap(fallbackQualityJson());
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

        state.setConventionReport(conventionReport);
        state.setPerformanceReport(performanceReport);
        state.setSecurityReport(securityReport);

        log.info("质量审查完成（单次模型调用），规范={} 性能={} 安全={}",
                conventionReport.length(), performanceReport.length(), securityReport.length());
    }

    private String buildUnifiedPrompt(CommitTaskState state) {
        return String.format("""
                你是代码评审专家。请基于同一份 Diff，一次性输出规范、性能、安全三份审查报告。
                只输出合法 JSON，不要 Markdown 代码块，不要额外解释。
                
                输出 JSON 字段：
                {
                  "conventionReport":"...",
                  "performanceReport":"...",
                  "securityReport":"..."
                }
                
                字段要求（每个 report 都必须满足）：
                - 使用中文 Markdown。
                - 包含且仅包含以下三级结构：
                  ## 结论
                  风险等级：低风险 / 中风险 / 高风险
                  
                  ## 发现
                  - 最多 3 条
                  
                  ## 建议
                  - 最多 3 条
                - 每个 report 尽量不超过 220 字。
                
                审查重点：
                - conventionReport：命名、异常处理、风格一致性、重复逻辑、可测试性。
                - performanceReport：循环复杂度、IO/数据库/远程调用、并发竞争、缓存命中、对象与连接资源管理。
                - securityReport：注入、鉴权与越权、敏感信息、反序列化、文件与资源访问控制（参考 OWASP Top 10）。
                
                提交上下文：
                - 仓库：%s
                - 分支：%s
                - 提交说明：%s
                
                Diff:
                %s
                """,
                state.getRepository(),
                state.getBranch(),
                state.getMessage(),
                compactDiff(state.getDiff())
        );
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

    private String fallbackQualityJson() {
        return "{"
                + "\"conventionReport\":" + toJsonString(fallbackConventionReport()) + ","
                + "\"performanceReport\":" + toJsonString(fallbackPerformanceReport()) + ","
                + "\"securityReport\":" + toJsonString(fallbackSecurityReport())
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

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        review(commitState);
        return commitState.toMap();
    }
}
