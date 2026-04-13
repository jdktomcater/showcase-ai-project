package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.dto.GraphViewResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.GraphViewEdge;
import com.jdktomcat.showcase.ai.code.chunk.dto.GraphViewNode;
import com.jdktomcat.showcase.ai.code.chunk.repository.Neo4jGraphRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImpactAnalysisService {

    private final Neo4jGraphRepository graphRepository;

    public List<Map<String, Object>> dependencies(String nodeType, String fqn, int depth) {
        log.debug("查询依赖 nodeType={} fqn={} depth={}", nodeType, fqn, depth);
        List<Map<String, Object>> result = graphRepository.findDependencies(nodeType.toUpperCase() + ":" + fqn, depth);
        log.debug("依赖查询完成 nodeType={} fqn={} count={}", nodeType, fqn, result.size());
        return result;
    }

    public List<Map<String, Object>> typeDependencies(String fqn, int depth) {
        log.debug("查询类型依赖 fqn={} depth={}", fqn, depth);
        List<Map<String, Object>> result = graphRepository.findTypeDependencies("TYPE:" + fqn, depth);
        log.debug("类型依赖查询完成 fqn={} count={}", fqn, result.size());
        return result;
    }

    public List<Map<String, Object>> impact(String methodFqn, int depth) {
        log.debug("查询影响 methodFqn={} depth={}", methodFqn, depth);
        List<Map<String, Object>> result = graphRepository.findImpact("METHOD:" + methodFqn, depth);
        log.debug("影响查询完成 methodFqn={} count={}", methodFqn, result.size());
        return result;
    }

    public List<Map<String, Object>> locate(String filePath, int line) {
        log.debug("按文件行定位节点 filePath={} line={}", filePath, line);
        List<Map<String, Object>> result = graphRepository.findNodesByFileAndLine(filePath, line);
        log.debug("文件行定位完成 filePath={} line={} count={}", filePath, line, result.size());
        return result;
    }

    public List<Map<String, Object>> typeImpact(String fqn, int depth) {
        log.debug("查询类型影响 fqn={} depth={}", fqn, depth);
        List<Map<String, Object>> result = graphRepository.findTypeImpact("TYPE:" + fqn, depth);
        log.debug("类型影响查询完成 fqn={} count={}", fqn, result.size());
        return result;
    }

    public GraphViewResponse dependencyGraph(String nodeType, String fqn, int depth) {
        log.debug("查询依赖图 nodeType={} fqn={} depth={}", nodeType, fqn, depth);
        String nodeId = nodeType.toUpperCase() + ":" + fqn;
        Neo4jGraphRepository.GraphData graphData = internalOnly(graphRepository.findDependencyGraph(nodeId, depth));
        log.debug("依赖图查询完成 nodeId={} nodes={} edges={}", nodeId, graphData.nodes().size(), graphData.edges().size());
        return new GraphViewResponse(
                true,
                "dependencies",
                nodeId,
                depth,
                graphData.nodes().size(),
                graphData.edges().size(),
                graphData.nodes(),
                graphData.edges()
        );
    }

    public GraphViewResponse impactGraph(String methodFqn, int depth) {
        log.debug("查询影响图 methodFqn={} depth={}", methodFqn, depth);
        String methodId = "METHOD:" + methodFqn;
        Neo4jGraphRepository.GraphData graphData = internalOnly(graphRepository.findImpactGraph(methodId, depth));
        log.debug("影响图查询完成 methodId={} nodes={} edges={}", methodId, graphData.nodes().size(), graphData.edges().size());
        return new GraphViewResponse(
                true,
                "impact",
                methodId,
                depth,
                graphData.nodes().size(),
                graphData.edges().size(),
                graphData.nodes(),
                graphData.edges()
        );
    }

    public GraphViewResponse fullTypeDependencyGraph() {
        log.debug("查询完整类型依赖图");
        Neo4jGraphRepository.GraphData graphData = internalOnly(graphRepository.findFullTypeDependencyGraph());
        log.debug("完整类型依赖图查询完成 nodes={} edges={}", graphData.nodes().size(), graphData.edges().size());
        return new GraphViewResponse(
                true,
                "classgraph",
                "TYPE:*",
                0,
                graphData.nodes().size(),
                graphData.edges().size(),
                graphData.nodes(),
                graphData.edges()
        );
    }

    private Neo4jGraphRepository.GraphData internalOnly(Neo4jGraphRepository.GraphData graphData) {
        List<GraphViewNode> nodes = graphData.nodes().stream()
                .filter(node -> node.filePath() != null && !node.filePath().isBlank())
                .toList();

        Set<String> nodeIds = new LinkedHashSet<>();
        for (GraphViewNode node : nodes) {
            nodeIds.add(node.id());
        }

        List<GraphViewEdge> edges = graphData.edges().stream()
                .filter(edge -> nodeIds.contains(edge.from()) && nodeIds.contains(edge.to()))
                .toList();

        return new Neo4jGraphRepository.GraphData(nodes, edges);
    }
}
