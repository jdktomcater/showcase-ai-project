package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request for impact chain query.
 */
public record ImpactChainRequest(
        String entryPointId,
        @Min(1) @Max(10) Integer depth
) {
    public int resolvedDepth() {
        return depth != null ? depth : 5;
    }
}
