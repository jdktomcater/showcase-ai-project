package com.jdktomcat.showcase.ai.code.chunk.dto;

import java.util.List;
import java.util.Map;

/**
 * Response for impact chain query.
 */
public record ImpactChainResponse(
        boolean success,
        String entryPointId,
        EntryPointTypeView entryPointType,
        int depth,
        int count,
        List<Map<String, Object>> impactChain
) {
    public enum EntryPointTypeView {
        HTTP, RPC, MQ, SCHEDULED, EVENT
    }
}
