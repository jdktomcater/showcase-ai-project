package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.NotBlank;

public record HybridSearchRequest(
        @NotBlank String query,
        String pathPrefix,
        Integer semanticTopK,
        Integer lexicalTopK,
        Integer limit
) {

    public int resolvedSemanticTopK() {
        return semanticTopK == null || semanticTopK < 1 ? 8 : Math.min(semanticTopK, 30);
    }

    public int resolvedLexicalTopK() {
        return lexicalTopK == null || lexicalTopK < 1 ? 8 : Math.min(lexicalTopK, 30);
    }

    public int resolvedLimit() {
        return limit == null || limit < 1 ? 10 : Math.min(limit, 30);
    }

    public String normalizedPathPrefix() {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return "";
        }
        String normalized = pathPrefix.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
