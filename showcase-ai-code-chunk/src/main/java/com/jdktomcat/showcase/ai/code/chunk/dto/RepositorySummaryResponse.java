package com.jdktomcat.showcase.ai.code.chunk.dto;

import java.util.List;
import java.util.Map;

public record RepositorySummaryResponse(
        boolean success,
        String repoName,
        String repoRoot,
        int totalFiles,
        int javaFiles,
        Map<String, Long> filesByExtension,
        Map<String, Long> filesByModule,
        Map<String, Long> graphNodesByType,
        Map<String, Long> graphRelationsByType,
        List<String> availableTools
) {
}
