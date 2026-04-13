package com.jdktomcat.showcase.ai.code.chunk.repository;

import com.jdktomcat.showcase.ai.code.chunk.domain.*;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.MapAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Neo4j repository for storing and querying impact chain data.
 */
@Repository
public class ImpactChainRepository {

    private static final Logger log = LoggerFactory.getLogger(ImpactChainRepository.class);

    private final Driver driver;

    public ImpactChainRepository(Driver driver) {
        this.driver = driver;
    }

    /**
     * Save entry points to the graph.
     */
    public void saveEntryPoints(List<EntryPoint> entryPoints) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (EntryPoint entryPoint : entryPoints) {
                    tx.run("""
                            MERGE (n:EntryPoint {id: $id})
                            SET n.type = $entryPointType,
                                n.className = $className,
                                n.methodName = $methodName,
                                n.methodSignature = $methodSignature,
                                n.filePath = $filePath,
                                n.module = $module,
                                n.lineNumber = $lineNumber,
                                n.metadata = $metadata
                            """, Values.parameters(
                            "id", entryPoint.id(),
                            "entryPointType", entryPoint.type().name(),
                            "className", entryPoint.className(),
                            "methodName", entryPoint.methodName(),
                            "methodSignature", entryPoint.methodSignature(),
                            "filePath", entryPoint.filePath(),
                            "module", entryPoint.module(),
                            "lineNumber", entryPoint.lineNumber(),
                            "metadata", entryPoint.metadata()
                    ));
                }
                return null;
            });
        }
        log.info("Saved {} entry points to Neo4j", entryPoints.size());
    }

    /**
     * Save impact chain nodes and relations.
     */
    public void saveImpactChain(List<ImpactChainNode> nodes, List<ImpactChainRelation> relations) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // Save nodes
                for (ImpactChainNode node : nodes) {
                    tx.run("""
                            MERGE (n:CodeNode {id: $id})
                            SET n.type = $nodeType,
                                n.name = $name,
                                n.fqn = $fqn,
                                n.kind = $kind,
                                n.filePath = $filePath,
                                n.module = $module,
                                n.startLine = $startLine,
                                n.endLine = $endLine,
                                n.isEntryPoint = $isEntryPoint,
                                n.entryPointType = $entryPointType
                            """, Values.parameters(
                            "id", node.id(),
                            "nodeType", node.type().name(),
                            "name", node.name(),
                            "fqn", node.fqn(),
                            "kind", node.kind(),
                            "filePath", node.filePath(),
                            "module", node.module(),
                            "startLine", node.startLine(),
                            "endLine", node.endLine(),
                            "isEntryPoint", node.isEntryPoint(),
                            "entryPointType", node.entryPointType() != null ? node.entryPointType().name() : null
                    ));
                }

                // Save relations
                for (ImpactChainRelation relation : relations) {
                    tx.run("""
                            MATCH (from:CodeNode {id: $fromId})
                            MATCH (to:CodeNode {id: $toId})
                            MERGE (from)-[r:RELATES {type: $relationType}]->(to)
                            """, Values.parameters(
                            "fromId", relation.fromId(),
                            "toId", relation.toId(),
                            "relationType", relation.type().name()
                    ));
                }

                return null;
            });
        }
        log.info("Saved {} nodes and {} relations to Neo4j", nodes.size(), relations.size());
    }

    /**
     * Link entry points to their corresponding code nodes.
     */
    public void linkEntryPointsToCodeNodes(List<EntryPoint> entryPoints) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (EntryPoint entryPoint : entryPoints) {
                    // Create or update the METHOD node with entry point flag
                    String methodId = "METHOD:" + entryPoint.methodSignature();
                    tx.run("""
                            MERGE (n:CodeNode {id: $methodId})
                            SET n.type = 'METHOD',
                                n.name = $methodName,
                                n.fqn = $methodSignature,
                                n.isEntryPoint = true,
                                n.entryPointType = $entryPointType,
                                n.filePath = $filePath,
                                n.module = $module,
                                n.lineNumber = $lineNumber
                            """, Values.parameters(
                            "methodId", methodId,
                            "methodName", entryPoint.methodName(),
                            "methodSignature", entryPoint.methodSignature(),
                            "entryPointType", entryPoint.type().name(),
                            "filePath", entryPoint.filePath(),
                            "module", entryPoint.module(),
                            "lineNumber", entryPoint.lineNumber()
                    ));

                    // Also create an EntryPoint node and link it
                    tx.run("""
                            MATCH (n:CodeNode {id: $methodId})
                            MERGE (ep:EntryPoint {id: $entryPointId})
                            SET ep.type = $entryPointType,
                                ep.className = $className,
                                ep.methodName = $methodName,
                                ep.methodSignature = $methodSignature,
                                ep.filePath = $filePath,
                                ep.module = $module,
                                ep.lineNumber = $lineNumber,
                                ep.metadata = $metadata
                            MERGE (ep)-[:POINTS_TO]->(n)
                            """, Values.parameters(
                            "methodId", methodId,
                            "entryPointId", entryPoint.id(),
                            "entryPointType", entryPoint.type().name(),
                            "className", entryPoint.className(),
                            "methodName", entryPoint.methodName(),
                            "methodSignature", entryPoint.methodSignature(),
                            "filePath", entryPoint.filePath(),
                            "module", entryPoint.module(),
                            "lineNumber", entryPoint.lineNumber(),
                            "metadata", entryPoint.metadata()
                    ));
                }
                return null;
            });
        }
    }

    /**
     * Find the impact chain for a given entry point.
     */
    public List<Map<String, Object>> findImpactChain(String entryPointId, int depth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (ep:EntryPoint {id: $entryPointId})-[:POINTS_TO]->(start:CodeNode)
                    MATCH p = (start)-[:RELATES*1..10]->(target:CodeNode)
                    WHERE length(p) <= $depth
                    RETURN DISTINCT
                        ep.id AS entryPointId,
                        ep.type AS entryPointType,
                        ep.methodSignature AS methodSignature,
                        target.id AS id,
                        target.type AS type,
                        target.name AS name,
                        target.fqn AS fqn,
                        target.filePath AS filePath,
                        target.module AS module,
                        target.isEntryPoint AS isEntryPoint,
                        length(p) AS hops,
                        [r IN relationships(p) | r.type] AS relationTypes
                    ORDER BY hops, fqn
                    """, Values.parameters(
                    "entryPointId", entryPointId,
                    "depth", depth
            )).list(MapAccessor::asMap));
        }
    }

    /**
     * Find all entry points of a specific type.
     */
    public List<Map<String, Object>> findEntryPointsByType(EntryPointType type) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (ep:EntryPoint {type: $type})
                    OPTIONAL MATCH (ep)-[:POINTS_TO]->(node:CodeNode)
                    RETURN
                        ep.id AS id,
                        ep.type AS type,
                        ep.className AS className,
                        ep.methodName AS methodName,
                        ep.methodSignature AS methodSignature,
                        ep.filePath AS filePath,
                        ep.module AS module,
                        ep.lineNumber AS lineNumber,
                        ep.metadata AS metadata,
                        node.fqn AS linkedNodeFqn
                    ORDER BY ep.type, ep.className, ep.methodName
                    """, Values.parameters(
                    "type", type.name()
            )).list(MapAccessor::asMap));
        }
    }

    /**
     * Find all entry points.
     */
    public List<Map<String, Object>> findAllEntryPoints() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (ep:EntryPoint)
                    OPTIONAL MATCH (ep)-[:POINTS_TO]->(node:CodeNode)
                    RETURN
                        ep.id AS id,
                        ep.type AS type,
                        ep.className AS className,
                        ep.methodName AS methodName,
                        ep.methodSignature AS methodSignature,
                        ep.filePath AS filePath,
                        ep.module AS module,
                        ep.lineNumber AS lineNumber,
                        ep.metadata AS metadata,
                        node.fqn AS linkedNodeFqn
                    ORDER BY ep.type, ep.className, ep.methodName
                    """).list(MapAccessor::asMap));
        }
    }

    /**
     * Get the impact graph for an entry point.
     */
    public GraphData findImpactGraph(String entryPointId, int depth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Record> records = tx.run("""
                        MATCH (ep:EntryPoint {id: $entryPointId})-[:POINTS_TO]->(start:CodeNode)
                        OPTIONAL MATCH p = (start)-[:RELATES*1..10]->(target:CodeNode)
                        WHERE length(p) <= $depth
                        WITH ep, start, collect(DISTINCT p) AS paths
                        WITH ep, start, paths,
                             reduce(allNodes = [start], path IN paths |
                                 allNodes + CASE WHEN path IS NULL THEN [] ELSE nodes(path) END) AS rawNodes,
                             reduce(allRels = [], path IN paths |
                                 allRels + CASE WHEN path IS NULL THEN [] ELSE relationships(path) END) AS rawRels
                        UNWIND rawNodes AS node
                        WITH ep, start, rawRels, collect(DISTINCT node) AS graphNodes
                        UNWIND CASE WHEN size(rawRels) = 0 THEN [NULL] ELSE rawRels END AS rel
                        WITH ep, start, graphNodes, collect(DISTINCT rel) AS graphRels
                        RETURN [{
                                    id: ep.id,
                                    name: ep.methodName,
                                    type: 'ENTRY_POINT',
                                    fqn: ep.methodSignature,
                                    kind: ep.type,
                                    filePath: ep.filePath,
                                    module: ep.module,
                                    startLine: ep.lineNumber,
                                    endLine: ep.lineNumber
                                }] + [node IN graphNodes | {
                                    id: node.id,
                                    name: node.name,
                                    type: node.type,
                                    fqn: node.fqn,
                                    kind: node.kind,
                                    filePath: node.filePath,
                                    module: node.module,
                                    startLine: node.startLine,
                                    endLine: node.endLine
                                }] AS nodes,
                               [rel IN graphRels WHERE rel IS NOT NULL | {
                                    id: elementId(rel),
                                    fromId: startNode(rel).id,
                                    toId: endNode(rel).id,
                                    type: rel.type
                                }] AS relationships
                        """, Values.parameters(
                        "entryPointId", entryPointId,
                        "depth", depth
                )).list();
                
                if (records.isEmpty()) {
                    return new GraphData(List.of(), List.of());
                }
                
                Record record = records.get(0);
                return mapGraphData(
                        record.get("nodes").asList(MapAccessor::asMap),
                        record.get("relationships").asList(MapAccessor::asMap),
                        entryPointId
                );
            });
        }
    }

    /**
     * Get the full impact chain graph filtered by entry point types.
     */
    public GraphData findFullImpactGraph(List<EntryPointType> types) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String typeFilter = types.isEmpty() ? "" : "WHERE ep.type IN $types";
                List<Record> records = tx.run("""
                        MATCH (ep:EntryPoint)
                        """ + typeFilter + """
                        OPTIONAL MATCH (ep)-[:POINTS_TO]->(start:CodeNode)
                        OPTIONAL MATCH (start)-[rel:RELATES]->(target:CodeNode)
                        WITH collect(DISTINCT ep) AS entryPoints,
                             collect(DISTINCT start) AS startNodes,
                             collect(DISTINCT target) AS targetNodes,
                             collect(DISTINCT rel) AS graphRels
                        WITH entryPoints, startNodes, targetNodes, graphRels
                        UNWIND entryPoints AS ep
                        UNWIND CASE WHEN size(startNodes) = 0 THEN [null] ELSE startNodes END AS start
                        UNWIND CASE WHEN size(targetNodes) = 0 THEN [null] ELSE targetNodes END AS target
                        UNWIND CASE WHEN size(graphRels) = 0 THEN [null] ELSE graphRels END AS rel
                        WITH ep, start, target, rel
                        WHERE start IS NOT NULL OR ep IS NOT NULL
                        WITH collect(DISTINCT {
                                    id: ep.id,
                                    name: ep.methodName,
                                    type: 'ENTRY_POINT',
                                    fqn: ep.methodSignature,
                                    kind: ep.type,
                                    filePath: ep.filePath,
                                    module: ep.module,
                                    startLine: ep.lineNumber,
                                    endLine: ep.lineNumber
                                }) AS epNodes,
                             collect(DISTINCT {
                                    id: start.id,
                                    name: start.name,
                                    type: start.type,
                                    fqn: start.fqn,
                                    kind: start.kind,
                                    filePath: start.filePath,
                                    module: start.module,
                                    startLine: start.startLine,
                                    endLine: start.endLine
                                }) AS startNodes,
                             collect(DISTINCT {
                                    id: target.id,
                                    name: target.name,
                                    type: target.type,
                                    fqn: target.fqn,
                                    kind: target.kind,
                                    filePath: target.filePath,
                                    module: target.module,
                                    startLine: target.startLine,
                                    endLine: target.endLine
                                }) AS targetNodes,
                             collect(DISTINCT {
                                    id: elementId(rel),
                                    fromId: CASE WHEN startNode(rel) IS NOT NULL THEN startNode(rel).id ELSE null END,
                                    toId: CASE WHEN endNode(rel) IS NOT NULL THEN endNode(rel).id ELSE null END,
                                    type: rel.type
                                }) AS rels
                        WITH epNodes, startNodes, targetNodes, rels
                        WHERE size(epNodes) > 0 OR size(startNodes) > 0 OR size(targetNodes) > 0
                        RETURN 
                            epNodes + 
                            [n IN startNodes WHERE n.id IS NOT NULL] + 
                            [n IN targetNodes WHERE n.id IS NOT NULL] AS nodes,
                            [r IN rels WHERE r.fromId IS NOT NULL AND r.toId IS NOT NULL] AS relationships
                        """, types.isEmpty() ? Values.parameters() : Values.parameters("types", types.stream().map(Enum::name).toList())).list();

                if (records.isEmpty()) {
                    return new GraphData(List.of(), List.of());
                }

                Record record = records.get(0);
                return mapGraphData(
                        record.get("nodes").asList(MapAccessor::asMap),
                        record.get("relationships").asList(MapAccessor::asMap),
                        null
                );
            });
        }
    }

    private GraphData mapGraphData(List<Map<String, Object>> nodeMaps, List<Map<String, Object>> relationMaps, String focusId) {
        List<ImpactChainNode> nodes = new ArrayList<>();
        for (Map<String, Object> node : nodeMaps) {
            String id = asString(node.get("id"));
            String typeStr = asString(node.get("type"));
            NodeType nodeType = "ENTRY_POINT".equals(typeStr) ? NodeType.ENTRY_POINT : NodeType.valueOf(typeStr);

            // Safely convert kind to EntryPointType
            EntryPointType entryPointType = null;
            if ("ENTRY_POINT".equals(typeStr)) {
                String kind = asString(node.get("kind"));
                if (kind != null && !kind.isBlank()) {
                    try {
                        entryPointType = EntryPointType.valueOf(kind);
                    } catch (IllegalArgumentException e) {
                        // Ignore invalid entry point types
                        log.warn("Invalid EntryPointType: {}", kind);
                    }
                }
            }

            nodes.add(new ImpactChainNode(
                    id,
                    nodeType,
                    firstNonBlank(asString(node.get("name")), id),
                    asString(node.get("fqn")),
                    asString(node.get("kind")),
                    asString(node.get("filePath")),
                    asString(node.get("module")),
                    asInteger(node.get("startLine")),
                    asInteger(node.get("endLine")),
                    "ENTRY_POINT".equals(typeStr),
                    entryPointType
            ));
        }

        List<ImpactChainRelation> relations = new ArrayList<>();
        for (Map<String, Object> relation : relationMaps) {
            relations.add(new ImpactChainRelation(
                    asString(relation.get("fromId")),
                    asString(relation.get("toId")),
                    RelationType.valueOf(asString(relation.get("type"))),
                    null
            ));
        }

        return new GraphData(nodes, relations);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    public record GraphData(
            List<ImpactChainNode> nodes,
            List<ImpactChainRelation> relations
    ) {
    }
}
