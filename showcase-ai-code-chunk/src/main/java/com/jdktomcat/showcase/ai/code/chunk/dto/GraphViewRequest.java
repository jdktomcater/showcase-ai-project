package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record GraphViewRequest(
        @NotBlank(message = "mode is required")
        String mode,
        String nodeType,
        String fqn,
        String methodFqn,
        @Min(value = 1, message = "depth must be >= 1")
        @Max(value = 10, message = "depth must be <= 10")
        Integer depth
) {

    public String resolvedMode() {
        return mode == null ? "dependencies" : mode.trim().toLowerCase();
    }

    public int resolvedDepth() {
        return depth == null ? 2 : depth;
    }

    public String requiredNodeType() {
        if (nodeType == null || nodeType.isBlank()) {
            throw new IllegalArgumentException("nodeType is required for dependencies mode");
        }
        return nodeType.trim();
    }

    public String requiredFqn() {
        if (fqn == null || fqn.isBlank()) {
            throw new IllegalArgumentException("fqn is required for dependencies mode");
        }
        return fqn.trim();
    }

    public String requiredMethodFqn() {
        if (methodFqn == null || methodFqn.isBlank()) {
            throw new IllegalArgumentException("methodFqn is required for impact mode");
        }
        return methodFqn.trim();
    }
}
