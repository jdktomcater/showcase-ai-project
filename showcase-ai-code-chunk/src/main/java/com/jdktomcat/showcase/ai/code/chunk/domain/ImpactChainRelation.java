package com.jdktomcat.showcase.ai.code.chunk.domain;

/**
 * Represents a relation in the impact chain.
 */
public record ImpactChainRelation(
        String fromId,
        String toId,
        RelationType type,
        String metadata
) {
}
