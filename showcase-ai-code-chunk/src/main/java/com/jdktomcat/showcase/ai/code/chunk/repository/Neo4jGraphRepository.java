package com.jdktomcat.showcase.ai.code.chunk.repository;

import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphNode;
import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphRelation;
import com.jdktomcat.showcase.ai.code.chunk.dto.GraphViewEdge;
import com.jdktomcat.showcase.ai.code.chunk.dto.GraphViewNode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.MapAccessor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class Neo4jGraphRepository {

    private final Driver driver;

    public Neo4jGraphRepository(Driver driver) {
        this.driver = driver;
    }

    public void saveAll(List<CodeGraphNode> nodes, List<CodeGraphRelation> relations) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (CodeGraphNode node : nodes) {
                    // Stub nodes from JavaDependencyAnalyzer.externalNode() send null filePath/module/kind/lines.
                    // COALESCE keeps values already set when the real source file is indexed, so type-dependencies
                    // (project-only: filePath IS NOT NULL) are not wiped by later stub merges.
                    tx.run("""
                            MERGE (n:CodeNode {id: $id})
                            SET n.type = $type,
                                n.name = $name,
                                n.fqn = $fqn,
                                n.kind = coalesce($kind, n.kind),
                                n.filePath = coalesce($filePath, n.filePath),
                                n.module = coalesce($module, n.module),
                                n.startLine = coalesce($startLine, n.startLine),
                                n.endLine = coalesce($endLine, n.endLine)
                            """, Values.parameters(
                            "id", node.id(),
                            "type", node.type().name(),
                            "name", node.name(),
                            "fqn", node.fqn(),
                            "kind", node.kind(),
                            "filePath", node.filePath(),
                            "module", node.module(),
                            "startLine", node.startLine(),
                            "endLine", node.endLine()
                    ));
                }

                for (CodeGraphRelation relation : relations) {
                    tx.run("""
                            MATCH (from:CodeNode {id: $fromId})
                            MATCH (to:CodeNode {id: $toId})
                            MERGE (from)-[r:RELATES {type: $type}]->(to)
                            """, Values.parameters(
                            "fromId", relation.fromId(),
                            "toId", relation.toId(),
                            "type", relation.type().name()
                    ));
                }

                return null;
            });
        }
    }

    public List<Map<String, Object>> findDependencies(String nodeId, int depth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (start:CodeNode {id: $nodeId})
                    MATCH p = (start)-[:RELATES*1..10]->(target:CodeNode)
                    WHERE length(p) <= $depth
                    RETURN DISTINCT target.id AS id,
                                    target.type AS type,
                                    target.name AS name,
                                    target.fqn AS fqn,
                                    target.filePath AS filePath,
                                    target.module AS module,
                                    length(p) AS hops,
                                    [r IN relationships(p) | r.type] AS relationTypes
                    ORDER BY hops, fqn
                    """, Values.parameters(
                    "nodeId", nodeId,
                    "depth", depth
            )).list(MapAccessor::asMap));
        }
    }

    /**
     * Outgoing type-to-type dependencies within the indexed repository only ({@code target.filePath} set).
     */
    public List<Map<String, Object>> findTypeDependencies(String typeId, int depth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (start:CodeNode {id: $typeId, type: 'TYPE'})
                    MATCH p = (start)-[:RELATES*1..10]->(target:CodeNode {type: 'TYPE'})
                    WHERE length(p) <= $depth
                      AND target.id <> $typeId
                      AND target.filePath IS NOT NULL
                      AND ALL(r IN relationships(p) WHERE r.type IN ['DEPENDS_ON', 'EXTENDS', 'IMPLEMENTS', 'INJECTS'])
                    RETURN DISTINCT target.id AS id,
                                    target.type AS type,
                                    target.name AS name,
                                    target.fqn AS fqn,
                                    target.kind AS kind,
                                    target.filePath AS filePath,
                                    target.module AS module,
                                    length(p) AS hops,
                                    [r IN relationships(p) | r.type] AS relationTypes
                    ORDER BY hops, fqn
                    """, Values.parameters(
                    "typeId", typeId,
                    "depth", depth
            )).list(MapAccessor::asMap));
        }
    }

    public List<Map<String, Object>> findImpact(String methodId, int depth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (target:CodeNode {id: $methodId})
                    MATCH p = (caller:CodeNode)-[:RELATES*1..10]->(target)
                    WHERE caller.type = 'METHOD'
                      AND length(p) <= $depth
                      AND ALL(r IN relationships(p) WHERE r.type = 'CALLS')
                    OPTIONAL MATCH (owner:CodeNode)-[:RELATES {type: 'DECLARES'}]->(caller)
                    OPTIONAL MATCH (owner)-[:RELATES {type: 'ANNOTATED_BY'}]->(ann:CodeNode)
                    RETURN DISTINCT caller.id AS id,
                                    caller.name AS name,
                                    caller.fqn AS fqn,
                                    caller.filePath AS filePath,
                                    owner.fqn AS ownerType,
                                    collect(DISTINCT ann.name) AS ownerAnnotations,
                                    length(p) AS hops
                    ORDER BY hops, fqn
                    """, Values.parameters(
                    "methodId", methodId,
                    "depth", depth
            )).list(MapAccessor::asMap));
        }
    }

    public List<Map<String, Object>> findNodesByFileAndLine(String filePath, int line) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (node:CodeNode {filePath: $filePath})
                    WHERE node.type IN ['METHOD', 'TYPE']
                      AND node.startLine IS NOT NULL
                      AND coalesce(node.endLine, node.startLine) >= $line
                      AND node.startLine <= $line
                    RETURN node.id AS id,
                           node.type AS type,
                           node.name AS name,
                           node.fqn AS fqn,
                           node.kind AS kind,
                           node.filePath AS filePath,
                           node.module AS module,
                           node.startLine AS startLine,
                           node.endLine AS endLine
                    ORDER BY CASE node.type WHEN 'METHOD' THEN 0 WHEN 'TYPE' THEN 1 ELSE 2 END,
                             coalesce(node.endLine, node.startLine) - node.startLine ASC,
                             node.fqn
                    """, Values.parameters(
                    "filePath", filePath,
                    "line", line
            )).list(MapAccessor::asMap));
        }
    }

    /** Incoming type-to-type references within the indexed repository only ({@code caller.filePath} set). */
    public List<Map<String, Object>> findTypeImpact(String typeId, int depth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (target:CodeNode {id: $typeId, type: 'TYPE'})
                    MATCH p = (caller:CodeNode {type: 'TYPE'})-[:RELATES*1..10]->(target)
                    WHERE length(p) <= $depth
                      AND caller.id <> $typeId
                      AND caller.filePath IS NOT NULL
                      AND ALL(r IN relationships(p) WHERE r.type IN ['DEPENDS_ON', 'EXTENDS', 'IMPLEMENTS', 'INJECTS'])
                    RETURN DISTINCT caller.id AS id,
                                    caller.type AS type,
                                    caller.name AS name,
                                    caller.fqn AS fqn,
                                    caller.kind AS kind,
                                    caller.filePath AS filePath,
                                    caller.module AS module,
                                    length(p) AS hops,
                                    [r IN relationships(p) | r.type] AS relationTypes
                    ORDER BY hops, fqn
                    """, Values.parameters(
                    "typeId", typeId,
                    "depth", depth
            )).list(MapAccessor::asMap));
        }
    }

    public GraphData findDependencyGraph(String nodeId, int depth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record record = tx.run("""
                    MATCH (start:CodeNode {id: $nodeId})
                    OPTIONAL MATCH p = (start)-[:RELATES*1..10]->(target:CodeNode)
                    WHERE length(p) <= $depth
                    WITH start, collect(DISTINCT p) AS paths
                    WITH start, paths,
                         reduce(allNodes = [start], path IN paths |
                             allNodes + CASE WHEN path IS NULL THEN [] ELSE nodes(path) END) AS rawNodes,
                         reduce(allRels = [], path IN paths |
                             allRels + CASE WHEN path IS NULL THEN [] ELSE relationships(path) END) AS rawRels
                    UNWIND rawNodes AS node
                    WITH start, rawRels, collect(DISTINCT node) AS graphNodes
                    UNWIND CASE WHEN size(rawRels) = 0 THEN [NULL] ELSE rawRels END AS rel
                    WITH start, graphNodes, collect(DISTINCT rel) AS graphRels
                    RETURN [node IN graphNodes | {
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
                    "nodeId", nodeId,
                    "depth", depth
                )).single();
                return mapGraphData(
                        record.get("nodes").asList(MapAccessor::asMap),
                        record.get("relationships").asList(MapAccessor::asMap),
                        nodeId
                );
            });
        }
    }

    public GraphData findImpactGraph(String methodId, int depth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record record = tx.run("""
                    MATCH (target:CodeNode {id: $methodId})
                    OPTIONAL MATCH p = (caller:CodeNode)-[:RELATES*1..10]->(target)
                    WHERE caller.type = 'METHOD'
                      AND length(p) <= $depth
                      AND ALL(r IN relationships(p) WHERE r.type = 'CALLS')
                    WITH target, collect(DISTINCT p) AS paths
                    WITH target, paths,
                         reduce(allNodes = [target], path IN paths |
                             allNodes + CASE WHEN path IS NULL THEN [] ELSE nodes(path) END) AS rawNodes,
                         reduce(allRels = [], path IN paths |
                             allRels + CASE WHEN path IS NULL THEN [] ELSE relationships(path) END) AS rawRels
                    UNWIND rawNodes AS node
                    WITH target, rawRels, collect(DISTINCT node) AS graphNodes
                    UNWIND CASE WHEN size(rawRels) = 0 THEN [NULL] ELSE rawRels END AS rel
                    WITH target, graphNodes, collect(DISTINCT rel) AS graphRels
                    RETURN [node IN graphNodes | {
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
                    "methodId", methodId,
                    "depth", depth
                )).single();
                return mapGraphData(
                        record.get("nodes").asList(MapAccessor::asMap),
                        record.get("relationships").asList(MapAccessor::asMap),
                        methodId
                );
            });
        }
    }

    public GraphData findFullTypeDependencyGraph() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record record = tx.run("""
                    OPTIONAL MATCH (source:CodeNode {type: 'TYPE'})
                    OPTIONAL MATCH (source)-[rel:RELATES]->(target:CodeNode {type: 'TYPE'})
                    WHERE rel IS NULL OR rel.type IN ['DEPENDS_ON', 'EXTENDS', 'IMPLEMENTS', 'INJECTS']
                    WITH [node IN collect(DISTINCT source) + collect(DISTINCT target)
                          WHERE node IS NOT NULL] AS rawNodes,
                         [relation IN collect(DISTINCT rel)
                          WHERE relation IS NOT NULL] AS graphRels
                    UNWIND CASE WHEN size(rawNodes) = 0 THEN [NULL] ELSE rawNodes END AS node
                    WITH collect(DISTINCT node) AS graphNodes, graphRels
                    RETURN [node IN graphNodes WHERE node IS NOT NULL | {
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
                           [rel IN graphRels | {
                                id: elementId(rel),
                                fromId: startNode(rel).id,
                                toId: endNode(rel).id,
                                type: rel.type
                            }] AS relationships
                    """).single();
                return mapGraphData(
                        record.get("nodes").asList(MapAccessor::asMap),
                        record.get("relationships").asList(MapAccessor::asMap),
                        null
                );
            });
        }
    }

    public Map<String, Long> countNodesByType() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Map<String, Long> counts = new LinkedHashMap<>();
                tx.run("""
                        MATCH (node:CodeNode)
                        RETURN node.type AS type, count(*) AS total
                        ORDER BY type
                        """).list().forEach(record ->
                        counts.put(record.get("type").asString("UNKNOWN"), record.get("total").asLong(0L))
                );
                return counts;
            });
        }
    }

    public Map<String, Long> countRelationsByType() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Map<String, Long> counts = new LinkedHashMap<>();
                tx.run("""
                        MATCH ()-[rel:RELATES]->()
                        RETURN rel.type AS type, count(*) AS total
                        ORDER BY type
                        """).list().forEach(record ->
                        counts.put(record.get("type").asString("UNKNOWN"), record.get("total").asLong(0L))
                );
                return counts;
            });
        }
    }

    private GraphData mapGraphData(List<Map<String, Object>> nodeMaps, List<Map<String, Object>> relationMaps, String focusId) {
        List<GraphViewNode> nodes = new ArrayList<>();
        for (Map<String, Object> node : nodeMaps) {
            String id = asString(node.get("id"));
            nodes.add(new GraphViewNode(
                    id,
                    firstNonBlank(asString(node.get("name")), id),
                    asString(node.get("type")),
                    asString(node.get("fqn")),
                    asString(node.get("kind")),
                    asString(node.get("filePath")),
                    asString(node.get("module")),
                    asInteger(node.get("startLine")),
                    asInteger(node.get("endLine")),
                    Objects.equals(focusId, id)
            ));
        }

        List<GraphViewEdge> edges = new ArrayList<>();
        for (Map<String, Object> relation : relationMaps) {
            edges.add(new GraphViewEdge(
                    asString(relation.get("id")),
                    asString(relation.get("fromId")),
                    asString(relation.get("toId")),
                    asString(relation.get("type"))
            ));
        }

        return new GraphData(nodes, edges);
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
            List<GraphViewNode> nodes,
            List<GraphViewEdge> edges
    ) {
    }
}
