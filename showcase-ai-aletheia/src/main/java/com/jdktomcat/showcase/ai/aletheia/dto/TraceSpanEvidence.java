package com.jdktomcat.showcase.ai.aletheia.dto;

/**
 * 链路日志证据中提炼出的单个 Span 信息，用于在响应中向上层调用方透出关键节点。
 */
public record TraceSpanEvidence(
        String service,
        String endpoint,
        String component,
        String peer,
        String layer,
        String type,
        long durationMs,
        boolean error,
        String suspectedCause
) {
}
