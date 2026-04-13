package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.NotBlank;

public record ImpactQueryRequest(
        @NotBlank String methodFqn,
        Integer depth
) {

    public int resolvedDepth() {
        return depth == null || depth < 1 ? 3 : Math.min(depth, 10);
    }
}
