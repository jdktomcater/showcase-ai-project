package com.jdktomcat.showcase.ai.aletheia.service;

import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class AletheiaPromptService {

    private final AletheiaProperties properties;

    public AletheiaPromptService(AletheiaProperties properties) {
        this.properties = properties;
    }

    public String buildPerformanceUserPrompt(AnalysisRequest request, boolean mcpAvailable) {
        int durationMinutes = request.getDurationMinutes() == null
                ? properties.getDefaultDurationMinutes()
                : request.getDurationMinutes();
        String prompt = """
                请对以下系统性能问题进行智能分析。

                问题：
                %s

                上下文：
                - 分析域：性能
                - 服务：%s
                - 接口关键字：%s
                - TraceId：%s
                - 时间窗口：最近 %d 分钟
                - MCP 工具可用：%s
                - 候选工具：skywalking:listServices、skywalking:locateSlowEndpoints、skywalking:diagnoseTrace、skywalking:diagnoseServicePerformance

                分析要求：
                - 如果 MCP 工具可用，请优先调用工具获取事实证据后再下结论。
                - 如果已提供 traceId，优先深挖该 Trace。
                - 如果给了 serviceName，但未给 traceId，请先定位慢接口和慢 Trace。
                - 如果信息不足，请指出缺失的服务名、接口名或时间范围。
                """.formatted(
                StringUtils.defaultString(request.getQuestion()).trim(),
                StringUtils.defaultIfBlank(request.getServiceName(), "未指定"),
                StringUtils.defaultIfBlank(request.getEndpointKeyword(), "未指定"),
                StringUtils.defaultIfBlank(request.getTraceId(), "未指定"),
                durationMinutes,
                mcpAvailable ? "是" : "否"
        );
        return truncate(prompt);
    }

    private String truncate(String prompt) {
        int safeMaxChars = Math.max(1000, properties.getPromptMaxChars());
        if (prompt.length() <= safeMaxChars) {
            return prompt;
        }
        return prompt.substring(0, safeMaxChars) + "\n...[prompt truncated]...";
    }
}
