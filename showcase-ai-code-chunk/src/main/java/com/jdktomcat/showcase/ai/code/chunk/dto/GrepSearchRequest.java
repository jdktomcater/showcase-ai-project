package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.NotBlank;

public record GrepSearchRequest(
        @NotBlank String pattern,
        String pathPrefix,
        Boolean caseSensitive,
        Boolean regex,
        Integer contextLines,
        Integer limit
) {

    public boolean resolvedCaseSensitive() {
        return Boolean.TRUE.equals(caseSensitive);
    }

    public boolean resolvedRegex() {
        return Boolean.TRUE.equals(regex);
    }

    public int resolvedContextLines() {
        return contextLines == null || contextLines < 0 ? 1 : Math.min(contextLines, 5);
    }

    public int resolvedLimit() {
        return limit == null || limit < 1 ? 20 : Math.min(limit, 100);
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
