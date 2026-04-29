package com.jdktomcat.showcase.ai.aletheia.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceLogAnalysisRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class AletheiaPromptService {

    private final AletheiaProperties properties;
    private final ObjectMapper prettyJsonMapper;

    public AletheiaPromptService(AletheiaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.prettyJsonMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
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

    /**
     * 构造 SkyWalking 链路日志智能分析的 User Prompt，把已经从 MCP 工具拉取到的链路证据塞进去。
     *
     * @param request  原始请求
     * @param evidence 聚合层从 MCP 工具拿到的链路证据（候选 Trace、Span 列表等）
     * @return 用户消息
     */
    public String buildTraceLogUserPrompt(TraceLogAnalysisRequest request, Object evidence) {
        int durationMinutes = request.getDurationMinutes() == null
                ? properties.getDefaultDurationMinutes()
                : request.getDurationMinutes();
        int minTraceDurationMs = request.getMinTraceDurationMs() == null
                ? properties.getDefaultMinTraceDurationMs()
                : request.getMinTraceDurationMs();
        String prompt = """
                请基于下方真实抓取到的 SkyWalking 链路日志证据，对调用链进行根因诊断。

                诊断诉求：
                %s

                诊断上下文：
                - 分析域：链路日志（trace-log）
                - TraceId：%s
                - 服务：%s
                - 接口关键字：%s
                - 时间窗口：最近 %d 分钟
                - 慢 Trace 阈值：%d ms

                ===== 链路证据 BEGIN =====
                %s
                ===== 链路证据 END =====

                分析要求：
                - 只能引用上面的证据，不要编造未在 JSON 中出现的服务、接口或 TraceId。
                - 请清晰区分入口耗时、下游调用耗时、数据库 / 缓存耗时，并指出可能的瓶颈所在层。
                - 如果证据里出现 error=true 的 Span，请优先关注，给出可能的失败原因。
                - 如果证据为空或 error 字段提示工具失败，请明确指出无法下结论以及还需要补充什么。
                """.formatted(
                StringUtils.defaultIfBlank(StringUtils.trimToNull(request.getQuestion()),
                        "请定位本次链路中最值得关注的耗时瓶颈和异常根因。"),
                StringUtils.defaultIfBlank(request.getTraceId(), "未指定，由聚合层根据服务自动采样"),
                StringUtils.defaultIfBlank(request.getServiceName(), "未指定"),
                StringUtils.defaultIfBlank(request.getEndpointKeyword(), "未指定"),
                durationMinutes,
                minTraceDurationMs,
                serializeEvidence(evidence)
        );
        return truncate(prompt);
    }

    private String serializeEvidence(Object evidence) {
        if (Objects.isNull(evidence)) {
            return "{\"note\":\"无可用链路证据\"}";
        }
        try {
            return prettyJsonMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException ex) {
            return "{\"serializeError\":\"" + ex.getMessage() + "\"}";
        }
    }

    private String truncate(String prompt) {
        int safeMaxChars = Math.max(1000, properties.getPromptMaxChars());
        if (prompt.length() <= safeMaxChars) {
            return prompt;
        }
        return prompt.substring(0, safeMaxChars) + "\n...[prompt truncated]...";
    }
}
