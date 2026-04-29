package com.jdktomcat.showcase.ai.aletheia.dto;

import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * SkyWalking 链路日志智能分析响应。
 *
 * @param domain          固定为 {@link AnalysisDomain#TRACE_LOG}
 * @param summary         一句话结论，便于上游做摘要展示
 * @param report          模型返回的完整分析报告（Markdown）
 * @param modelAvailable  当前是否检测到可用的模型
 * @param mcpAvailable    当前是否检测到可用的 MCP 工具
 * @param evidenceSource  链路证据来源标记，例如 traceId / serviceName 等
 * @param traceId         本次分析最终聚焦的 TraceId
 * @param totalDurationMs 该 Trace 的总耗时（毫秒）
 * @param spanCount       Span 数量
 * @param topSpans        Top N 的瓶颈 Span 摘要，按耗时倒序
 * @param suggestions     模型或聚合层提炼出的下一步建议
 * @param rawEvidence     如请求开启 includeRawEvidence，则透出 MCP 工具原始返回数据
 * @param analyzedAt      分析时间
 */
public record TraceLogAnalysisResponse(
        AnalysisDomain domain,
        String summary,
        String report,
        boolean modelAvailable,
        boolean mcpAvailable,
        String evidenceSource,
        String traceId,
        long totalDurationMs,
        int spanCount,
        List<TraceSpanEvidence> topSpans,
        List<String> suggestions,
        Object rawEvidence,
        OffsetDateTime analyzedAt
) {
}
