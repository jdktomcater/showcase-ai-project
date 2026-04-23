package com.jdktomcat.showcase.ai.aletheia.dto;

import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;

import java.time.OffsetDateTime;
import java.util.List;

public record AnalysisResponse(
        AnalysisDomain domain,
        String summary,
        String report,
        boolean modelAvailable,
        boolean mcpAvailable,
        List<String> candidateTools,
        OffsetDateTime analyzedAt
) {
}
