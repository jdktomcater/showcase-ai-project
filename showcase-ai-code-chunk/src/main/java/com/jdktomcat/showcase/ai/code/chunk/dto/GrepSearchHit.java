package com.jdktomcat.showcase.ai.code.chunk.dto;

public record GrepSearchHit(
        String path,
        int lineNumber,
        int columnNumber,
        int startLine,
        int endLine,
        String preview
) {
}
