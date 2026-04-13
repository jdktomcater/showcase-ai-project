package com.jdktomcat.showcase.ai.code.chunk.dto;

public record GraphViewNode(
        String id,
        String label,
        String type,
        String fqn,
        String kind,
        String filePath,
        String module,
        Integer startLine,
        Integer endLine,
        boolean focus
) {
}
