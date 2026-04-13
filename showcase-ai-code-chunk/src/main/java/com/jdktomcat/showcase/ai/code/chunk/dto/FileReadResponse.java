package com.jdktomcat.showcase.ai.code.chunk.dto;

import java.util.List;

public record FileReadResponse(
        boolean success,
        String path,
        int totalLines,
        int startLine,
        int endLine,
        List<String> lines
) {
}
