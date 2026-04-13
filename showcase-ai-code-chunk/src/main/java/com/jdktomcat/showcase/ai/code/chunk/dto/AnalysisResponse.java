package com.jdktomcat.showcase.ai.code.chunk.dto;

/**
 * Response for analysis execution.
 */
public record AnalysisResponse(
        boolean success,
        int entryPointCount,
        int nodeCount,
        int relationCount,
        String message
) {
}
