package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.NotBlank;

public record CodeSearchRequest(
        @NotBlank String query,
        Integer topK
) {

    public int resolvedTopK() {
        return topK == null || topK < 1 ? 8 : Math.min(topK, 50);
    }
}
