package com.jdktomcat.showcase.ai.aletheia.service;

import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceLogAnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceLogAnalysisResponse;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceSpanEvidence;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SkyWalking 链路日志智能分析编排服务。
 * <p>
 * 编排流程：
 * <ol>
 *     <li>通过 {@link McpToolInvoker} 调用 SkyWalking MCP 工具读取真实链路证据；</li>
 *     <li>把证据塞进 Prompt，交给 ChatClient 做智能分析；</li>
 *     <li>聚合层兜底：若模型 / MCP 不可用，仍能返回原始链路 / 友好降级响应。</li>
 * </ol>
 */
@Service
public class TraceLogAnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceLogAnalysisService.class);

    private static final String TOOL_DIAGNOSE_TRACE = "diagnoseTrace";
    private static final String TOOL_LOCATE_SLOW_ENDPOINTS = "locateSlowEndpoints";

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final AletheiaProperties properties;
    private final AletheiaPromptService promptService;
    private final McpToolInvoker mcpToolInvoker;

    public TraceLogAnalysisService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                                   AletheiaProperties properties,
                                   AletheiaPromptService promptService,
                                   McpToolInvoker mcpToolInvoker) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.properties = properties;
        this.promptService = promptService;
        this.mcpToolInvoker = mcpToolInvoker;
    }

    public TraceLogAnalysisResponse analyze(TraceLogAnalysisRequest request) {
        Objects.requireNonNull(request, "TraceLogAnalysisRequest 不能为空");

        boolean modelAvailable = chatClientBuilderProvider.getIfAvailable() != null;
        boolean mcpAvailable = properties.isMcpEnabled() && mcpToolInvoker.hasAnyTool();

        if (StringUtils.isAllBlank(request.getTraceId(), request.getServiceName())) {
            return fallback(request, modelAvailable, mcpAvailable, null,
                    "请求未提供 traceId，也未提供 serviceName，无法定位 SkyWalking 链路日志。",
                    List.of("请补充 traceId 直接定位单次链路", "或提供 serviceName 让聚合层自动采样慢 Trace"));
        }

        if (!mcpAvailable) {
            return fallback(request, modelAvailable, false, null,
                    "MCP 工具未就绪，无法读取 SkyWalking 链路日志。",
                    List.of("启用 spring.ai.mcp.client 配置并连接 showcase-ai-mcp-apm-skywalking",
                            "确认环境变量 ALETHEIA_MCP_ENABLED 与 ALETHEIA_ANALYSIS_MCP_ENABLED=true",
                            "校验 SKYWALKING_BASE_URL 可访问"));
        }

        TraceEvidence evidence = readTraceEvidence(request);
        if (evidence == null || evidence.spans.isEmpty()) {
            return fallback(request, modelAvailable, true, evidence == null ? null : evidence.rawEvidence,
                    evidence == null
                            ? "MCP 工具未返回有效链路日志，请检查 SkyWalking 数据范围与服务名。"
                            : "MCP 工具返回成功，但未在该 Trace 中找到 Span 数据。",
                    List.of("扩大查询的时间窗口", "下调最小慢 Trace 阈值", "确认服务名 / TraceId 在 SkyWalking 中存在"));
        }

        if (!modelAvailable) {
            return new TraceLogAnalysisResponse(
                    AnalysisDomain.TRACE_LOG,
                    "模型未接入，仅返回 MCP 抓取的链路证据，未生成智能分析报告。",
                    buildEvidenceOnlyReport(request, evidence),
                    false,
                    true,
                    evidence.evidenceSource,
                    evidence.traceId,
                    evidence.totalDurationMs,
                    evidence.spans.size(),
                    capByLimit(evidence.topSpans, request),
                    deriveBasicSuggestions(evidence),
                    request.isIncludeRawEvidence() ? evidence.rawEvidence : null,
                    OffsetDateTime.now()
            );
        }

        String userPrompt = promptService.buildTraceLogUserPrompt(request, evidence.rawEvidence);
        try {
            String content = chatClientBuilderProvider.getObject()
                    .build()
                    .prompt()
                    .system(properties.getTraceLogSystemPrompt())
                    .user(userPrompt)
                    .call()
                    .content();
            if (StringUtils.isBlank(content)) {
                return fallback(request, true, true, evidence.rawEvidence,
                        "模型返回空内容，可能是上下文超长或模型异常。",
                        deriveBasicSuggestions(evidence));
            }
            return new TraceLogAnalysisResponse(
                    AnalysisDomain.TRACE_LOG,
                    summarize(content),
                    content,
                    true,
                    true,
                    evidence.evidenceSource,
                    evidence.traceId,
                    evidence.totalDurationMs,
                    evidence.spans.size(),
                    capByLimit(evidence.topSpans, request),
                    deriveBasicSuggestions(evidence),
                    request.isIncludeRawEvidence() ? evidence.rawEvidence : null,
                    OffsetDateTime.now()
            );
        } catch (Exception ex) {
            LOGGER.warn("调用模型分析链路日志失败: traceId={}", evidence.traceId, ex);
            return fallback(request, true, true, evidence.rawEvidence,
                    "模型调用失败：" + ex.getClass().getSimpleName() + " - " + ex.getMessage(),
                    deriveBasicSuggestions(evidence));
        }
    }

    private TraceEvidence readTraceEvidence(TraceLogAnalysisRequest request) {
        Map<String, Object> rawEvidence = new LinkedHashMap<>();
        Set<String> traceIds = new LinkedHashSet<>();
        String evidenceSource;

        if (StringUtils.isNotBlank(request.getTraceId())) {
            evidenceSource = "traceId=" + request.getTraceId();
            traceIds.add(request.getTraceId().trim());
        } else {
            evidenceSource = "serviceName=" + request.getServiceName();
            Map<String, Object> slowResult = mcpToolInvoker.invoke(TOOL_LOCATE_SLOW_ENDPOINTS,
                    buildSlowEndpointArgs(request));
            rawEvidence.put("locateSlowEndpoints", slowResult);
            traceIds.addAll(extractTraceIds(slowResult));
            if (traceIds.isEmpty()) {
                rawEvidence.put("note", "未从 locateSlowEndpoints 中解析出 TraceId");
                return new TraceEvidence(evidenceSource, null, 0L, List.of(), List.of(), rawEvidence);
            }
        }

        int safeTraceLimit = request.getTraceLimit() == null
                ? properties.getDefaultTraceLimit()
                : request.getTraceLimit();
        List<String> orderedTraceIds = traceIds.stream().limit(Math.max(1, safeTraceLimit)).toList();

        Map<String, Object> traceDetails = new LinkedHashMap<>();
        List<SpanRecord> allSpans = new ArrayList<>();
        long maxTotalDurationMs = 0L;
        String focusTraceId = orderedTraceIds.get(0);

        for (String traceId : orderedTraceIds) {
            Map<String, Object> traceResponse = mcpToolInvoker.invoke(TOOL_DIAGNOSE_TRACE,
                    Map.of("traceId", traceId));
            traceDetails.put(traceId, traceResponse);
            long total = asLong(traceResponse.get("totalDurationMs"));
            if (total > maxTotalDurationMs) {
                maxTotalDurationMs = total;
                focusTraceId = traceId;
            }
            allSpans.addAll(extractSpans(traceId, traceResponse));
        }
        rawEvidence.put("traces", traceDetails);

        List<SpanRecord> orderedSpans = allSpans.stream()
                .sorted(Comparator.comparingLong((SpanRecord span) -> span.durationMs).reversed())
                .toList();
        List<TraceSpanEvidence> topSpans = orderedSpans.stream()
                .map(this::toEvidence)
                .toList();

        return new TraceEvidence(evidenceSource, focusTraceId, maxTotalDurationMs, allSpans, topSpans, rawEvidence);
    }

    private Map<String, Object> buildSlowEndpointArgs(TraceLogAnalysisRequest request) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("serviceName", request.getServiceName());
        if (StringUtils.isNotBlank(request.getEndpointKeyword())) {
            args.put("endpointKeyword", request.getEndpointKeyword());
        }
        args.put("durationMinutes", request.getDurationMinutes() == null
                ? properties.getDefaultDurationMinutes()
                : request.getDurationMinutes());
        args.put("minTraceDurationMs", request.getMinTraceDurationMs() == null
                ? properties.getDefaultMinTraceDurationMs()
                : request.getMinTraceDurationMs());
        args.put("traceLimit", request.getTraceLimit() == null
                ? properties.getDefaultTraceLimit()
                : request.getTraceLimit());
        return args;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTraceIds(Map<String, Object> slowResult) {
        if (slowResult == null) {
            return List.of();
        }
        Object endpointsObj = slowResult.get("endpoints");
        if (!(endpointsObj instanceof List<?> endpoints)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object endpoint : endpoints) {
            if (!(endpoint instanceof Map<?, ?> endpointMap)) {
                continue;
            }
            Object tracesObj = endpointMap.get("traces");
            if (!(tracesObj instanceof List<?> traces)) {
                continue;
            }
            for (Object trace : traces) {
                if (!(trace instanceof Map<?, ?> traceMap)) {
                    continue;
                }
                Object idsObj = traceMap.get("traceIds");
                if (idsObj instanceof Collection<?> ids) {
                    for (Object id : ids) {
                        String stringId = Objects.toString(id, null);
                        if (StringUtils.isNotBlank(stringId)) {
                            result.add(stringId);
                        }
                    }
                } else if (idsObj instanceof String single && StringUtils.isNotBlank(single)) {
                    result.add(single);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<SpanRecord> extractSpans(String traceId, Map<String, Object> traceResponse) {
        if (traceResponse == null) {
            return List.of();
        }
        Object spansObj = traceResponse.get("spans");
        if (!(spansObj instanceof List<?> spans)) {
            return List.of();
        }
        List<SpanRecord> result = new ArrayList<>();
        for (Object span : spans) {
            if (!(span instanceof Map<?, ?> spanMap)) {
                continue;
            }
            Map<String, Object> typed = (Map<String, Object>) spanMap;
            result.add(new SpanRecord(
                    traceId,
                    Objects.toString(typed.get("service"), null),
                    Objects.toString(typed.get("endpoint"), null),
                    Objects.toString(typed.get("component"), null),
                    Objects.toString(typed.get("peer"), null),
                    Objects.toString(typed.get("layer"), null),
                    Objects.toString(typed.get("type"), null),
                    asLong(typed.get("durationMs")),
                    asBoolean(typed.get("error"))
            ));
        }
        return result;
    }

    private TraceSpanEvidence toEvidence(SpanRecord span) {
        return new TraceSpanEvidence(
                span.service,
                span.endpoint,
                span.component,
                span.peer,
                span.layer,
                span.type,
                span.durationMs,
                span.error,
                describeSuspicion(span)
        );
    }

    private String describeSuspicion(SpanRecord span) {
        String operation = StringUtils.firstNonBlank(span.endpoint, span.component, span.peer,
                StringUtils.isBlank(span.service) ? "unknown" : span.service);
        if (span.error) {
            return operation + " 出现 error=true，应优先排查异常堆栈和重试逻辑。";
        }
        if (StringUtils.equalsIgnoreCase(span.layer, "Database")) {
            return operation + " 是数据库访问慢，建议检查 SQL、索引和连接池。";
        }
        if (StringUtils.equalsIgnoreCase(span.type, "Exit") && StringUtils.isNotBlank(span.peer)) {
            return operation + " 是下游调用慢点，重点看 `" + span.peer + "` 的 RT、超时与重试。";
        }
        if (StringUtils.equalsIgnoreCase(span.type, "Entry")) {
            return operation + " 入口耗时较长，可能存在串行等待或本地计算偏重。";
        }
        return operation + " 耗时偏高，建议核对组件 `" + StringUtils.defaultIfBlank(span.component, "-")
                + "` 的具体实现。";
    }

    private List<TraceSpanEvidence> capByLimit(List<TraceSpanEvidence> topSpans, TraceLogAnalysisRequest request) {
        int limit = request.getTopSpanLimit() == null
                ? properties.getDefaultTopSpanLimit()
                : request.getTopSpanLimit();
        return topSpans.stream().limit(Math.max(1, limit)).toList();
    }

    private List<String> deriveBasicSuggestions(TraceEvidence evidence) {
        if (evidence == null || evidence.spans.isEmpty()) {
            return List.of("无可用 Span，建议扩大时间窗口或确认 TraceId / 服务名是否正确");
        }
        List<String> suggestions = new ArrayList<>();
        SpanRecord top = evidence.spans.stream()
                .max(Comparator.comparingLong(span -> span.durationMs))
                .orElse(null);
        if (top != null) {
            suggestions.add("Top1 瓶颈：`" + StringUtils.defaultIfBlank(top.endpoint, top.component) + "`，耗时 "
                    + top.durationMs + " ms，所在服务 `" + top.service + "`。");
        }
        long errorCount = evidence.spans.stream().filter(span -> span.error).count();
        if (errorCount > 0) {
            suggestions.add("链路中出现 " + errorCount + " 个 error Span，建议先排查异常根因。");
        }
        if (suggestions.size() < 2) {
            suggestions.add("可结合 diagnoseServicePerformance 工具进一步聚合慢 Trace 与下游依赖。");
        }
        return suggestions;
    }

    private String buildEvidenceOnlyReport(TraceLogAnalysisRequest request, TraceEvidence evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 链路证据\n");
        builder.append("- 证据来源：").append(evidence.evidenceSource).append('\n');
        builder.append("- 聚焦 TraceId：").append(StringUtils.defaultString(evidence.traceId, "-")).append('\n');
        builder.append("- 总耗时（最长 Trace）：").append(evidence.totalDurationMs).append(" ms\n");
        builder.append("- Span 数量：").append(evidence.spans.size()).append('\n');
        builder.append("\n## Top 瓶颈 Span\n");
        capByLimit(evidence.topSpans, request).forEach(span ->
                builder.append("- ")
                        .append(StringUtils.defaultIfBlank(span.endpoint(), span.component()))
                        .append("（service=")
                        .append(StringUtils.defaultString(span.service(), "-"))
                        .append("，layer=")
                        .append(StringUtils.defaultString(span.layer(), "-"))
                        .append("，duration=")
                        .append(span.durationMs())
                        .append(" ms")
                        .append(span.error() ? "，error" : "")
                        .append(")\n"));
        builder.append("\n## 下一步建议\n");
        deriveBasicSuggestions(evidence).forEach(item -> builder.append("- ").append(item).append('\n'));
        return builder.toString();
    }

    private TraceLogAnalysisResponse fallback(TraceLogAnalysisRequest request,
                                              boolean modelAvailable,
                                              boolean mcpAvailable,
                                              Object rawEvidence,
                                              String summary,
                                              List<String> suggestions) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 当前状态\n").append(summary).append('\n');
        builder.append("\n## 上下文\n");
        builder.append("- traceId: ").append(StringUtils.defaultString(request.getTraceId(), "-")).append('\n');
        builder.append("- serviceName: ").append(StringUtils.defaultString(request.getServiceName(), "-")).append('\n');
        builder.append("- endpointKeyword: ").append(StringUtils.defaultString(request.getEndpointKeyword(), "-")).append('\n');
        builder.append("- mcpAvailable: ").append(mcpAvailable).append('\n');
        builder.append("- modelAvailable: ").append(modelAvailable).append('\n');
        builder.append("\n## 建议动作\n");
        suggestions.forEach(item -> builder.append("- ").append(item).append('\n'));

        return new TraceLogAnalysisResponse(
                AnalysisDomain.TRACE_LOG,
                summary,
                builder.toString(),
                modelAvailable,
                mcpAvailable,
                StringUtils.isNotBlank(request.getTraceId())
                        ? "traceId=" + request.getTraceId()
                        : "serviceName=" + StringUtils.defaultString(request.getServiceName(), "-"),
                request.getTraceId(),
                0L,
                0,
                List.of(),
                suggestions,
                request.isIncludeRawEvidence() ? rawEvidence : null,
                OffsetDateTime.now()
        );
    }

    private String summarize(String content) {
        String normalized = StringUtils.defaultString(content).trim();
        if (normalized.isEmpty()) {
            return "";
        }
        String firstLine = normalized.lines()
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(normalized);
        return firstLine.length() <= 120 ? firstLine : firstLine.substring(0, 120);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private record SpanRecord(
            String traceId,
            String service,
            String endpoint,
            String component,
            String peer,
            String layer,
            String type,
            long durationMs,
            boolean error
    ) {
    }

    private record TraceEvidence(
            String evidenceSource,
            String traceId,
            long totalDurationMs,
            List<SpanRecord> spans,
            List<TraceSpanEvidence> topSpans,
            Map<String, Object> rawEvidence
    ) {
    }
}
