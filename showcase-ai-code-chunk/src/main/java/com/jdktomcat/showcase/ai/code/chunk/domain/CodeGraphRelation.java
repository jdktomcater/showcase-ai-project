package com.jdktomcat.showcase.ai.code.chunk.domain;

public record CodeGraphRelation(
        String fromId,
        String toId,
        RelationType type
) {
}
