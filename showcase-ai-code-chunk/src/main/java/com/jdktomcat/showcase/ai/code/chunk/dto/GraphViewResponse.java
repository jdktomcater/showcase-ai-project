package com.jdktomcat.showcase.ai.code.chunk.dto;

import java.util.List;

public record GraphViewResponse(
        boolean success,
        String mode,
        String target,
        int depth,
        int nodeCount,
        int edgeCount,
        List<GraphViewNode> nodes,
        List<GraphViewEdge> edges
) {
}
