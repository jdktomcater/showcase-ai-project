package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.NotBlank;

public record DependencyQueryRequest(
        @NotBlank String fqn,
        String nodeType,
        Integer depth
) {

    public String resolvedNodeType() {
        if (nodeType == null || nodeType.isBlank()) {
            return "TYPE";
        }
        return nodeType.trim().toUpperCase();
    }

    public int resolvedDepth() {
        return depth == null || depth < 1 ? 2 : Math.min(depth, 8);
    }
}
