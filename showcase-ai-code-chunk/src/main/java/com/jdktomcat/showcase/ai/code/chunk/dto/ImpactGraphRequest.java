package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * Request for graph view query.
 */
public record ImpactGraphRequest(
        String entryPointId,
        String mode,
        @Min(1) @Max(10) Integer depth,
        java.util.List<String> entryPointTypes
) {
    public String resolvedMode() {
        return mode != null ? mode : "impact";
    }

    public int resolvedDepth() {
        return depth != null ? depth : 5;
    }

    public java.util.List<String> resolvedEntryPointTypes() {
        return entryPointTypes != null ? entryPointTypes : java.util.List.of();
    }
}
