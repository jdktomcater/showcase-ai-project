package com.jdktomcat.showcase.ai.code.chunk.dto;

import java.util.List;

public record HybridSearchResponse(
        boolean success,
        String query,
        int count,
        List<HybridSearchHit> hits,
        List<CodeSearchHit> semanticHits,
        List<GrepSearchHit> grepHits
) {
}
