package com.jdktomcat.showcase.ai.code.chunk.dto;

import java.util.List;

/**
 * Response for graph view query.
 */
public record ImpactGraphResponse(
        boolean success,
        String mode,
        String focusId,
        int nodeCount,
        int edgeCount,
        List<NodeView> nodes,
        List<EdgeView> edges
) {
    public record NodeView(
            String id,
            String name,
            String type,
            String fqn,
            String kind,
            String filePath,
            String module,
            Integer startLine,
            Integer endLine,
            boolean isEntryPoint
    ) {
    }

    public record EdgeView(
            String id,
            String from,
            String to,
            String type
    ) {
    }
}
