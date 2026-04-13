package com.jdktomcat.showcase.ai.code.chunk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record FileReadRequest(
        @NotBlank String filePath,
        @Positive Integer startLine,
        @Positive Integer endLine
) {

    public int resolvedStartLine() {
        return startLine == null ? 1 : startLine;
    }

    public Integer resolvedEndLine() {
        return endLine;
    }
}
