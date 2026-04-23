package com.jdktomcat.showcase.ai.mcp.apm.skywalking.model;

public record TraceSpan(
        String traceId,
        String segmentId,
        int spanId,
        int parentSpanId,
        String serviceCode,
        String serviceInstanceName,
        long startTime,
        long endTime,
        String endpointName,
        String type,
        String peer,
        String component,
        boolean error,
        String layer,
        long durationMs
) {
}
