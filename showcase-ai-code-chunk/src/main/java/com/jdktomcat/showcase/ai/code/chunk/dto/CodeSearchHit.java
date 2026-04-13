package com.jdktomcat.showcase.ai.code.chunk.dto;

import java.util.Map;

public record CodeSearchHit(
        Double score,
        String chunkId,
        String repo,
        String path,
        String language,
        String packageName,
        String className,
        String methodName,
        String symbolType,
        int startLine,
        int endLine,
        String textPreview,
        Map<String, Object> metadata
) {
}
