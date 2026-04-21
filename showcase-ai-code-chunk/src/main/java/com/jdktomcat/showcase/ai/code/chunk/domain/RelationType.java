package com.jdktomcat.showcase.ai.code.chunk.domain;

/**
 * Relation types in the impact chain.
 */
public enum RelationType {
    CONTAINS,
    DECLARES,
    CALLS,
    DEPENDS_ON,
    EXTENDS,
    IMPLEMENTS,
    ANNOTATED_BY,
    RETURNS,
    HAS_PARAM_TYPE,
    INJECTS,
    INVOKES,
    TRIGGERS,
    PUBLISHES,
    CONSUMES,
    ACCESSES
}
