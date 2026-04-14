package com.jdktomcat.showcase.ai.code.assistant.dto;

import lombok.Builder;

@Builder
public record ReviewDiffResponse(
        boolean success,
        String repository,
        String branch,
        String sha,
        String decision,
        boolean passed,
        String finalReport,
        String telegramMessage,
        String codeImpactSummary,
        java.util.List<AffectedEntryPoint> affectedEntryPoints,
        String businessReport,
        String conventionReport,
        String performanceReport,
        String securityReport
) {
}
