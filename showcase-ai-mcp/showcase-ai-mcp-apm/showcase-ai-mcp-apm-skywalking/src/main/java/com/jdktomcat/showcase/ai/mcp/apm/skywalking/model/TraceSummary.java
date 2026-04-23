package com.jdktomcat.showcase.ai.mcp.apm.skywalking.model;

import java.util.List;

public record TraceSummary(
        String segmentId,
        List<String> traceIds,
        List<String> endpointNames,
        int durationMs,
        String start,
        boolean error
) {
}
