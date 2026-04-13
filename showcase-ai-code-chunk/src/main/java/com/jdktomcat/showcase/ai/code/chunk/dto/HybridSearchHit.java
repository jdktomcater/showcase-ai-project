package com.jdktomcat.showcase.ai.code.chunk.dto;

import java.util.List;

public record HybridSearchHit(
        String path,
        double score,
        Integer semanticRank,
        Integer lexicalRank,
        Double semanticScore,
        Integer firstMatchingLine,
        Integer startLine,
        Integer endLine,
        String className,
        String methodName,
        String preview,
        List<String> signals
) {
}
