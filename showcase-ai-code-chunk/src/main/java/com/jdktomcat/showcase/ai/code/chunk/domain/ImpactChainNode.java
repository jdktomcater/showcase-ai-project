package com.jdktomcat.showcase.ai.code.chunk.domain;

/**
 * Represents a node in the impact chain.
 */
public record ImpactChainNode(
        String id,
        NodeType type,
        String name,
        String fqn,
        String kind,
        String filePath,
        String module,
        Integer startLine,
        Integer endLine,
        boolean isEntryPoint,
        EntryPointType entryPointType
) {
    public static ImpactChainNode fromCodeGraphNode(CodeGraphNode node, boolean isEntryPoint, EntryPointType entryPointType) {
        return new ImpactChainNode(
                node.id(),
                node.type(),
                node.name(),
                node.fqn(),
                node.kind(),
                node.filePath(),
                node.module(),
                node.startLine(),
                node.endLine(),
                isEntryPoint,
                entryPointType
        );
    }
}
