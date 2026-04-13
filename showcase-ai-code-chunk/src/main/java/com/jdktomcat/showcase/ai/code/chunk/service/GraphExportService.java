package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.EntryPointType;
import com.jdktomcat.showcase.ai.code.chunk.repository.ImpactChainRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for exporting impact chain graphs in various visualization formats.
 */
@Service
public class GraphExportService {

    private final ImpactChainRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public GraphExportService(ImpactChainRepository repository) {
        this.repository = repository;
    }

    /**
     * Export graph to DOT format for Graphviz visualization.
     */
    public String exportToDot(String entryPointId, int depth) throws IOException {
        ImpactChainRepository.GraphData graphData = repository.findImpactGraph(entryPointId, depth);
        return buildDotGraph(graphData, entryPointId);
    }

    /**
     * Export full graph to DOT format.
     */
    public String exportFullGraphToDot(List<EntryPointType> types) throws IOException {
        ImpactChainRepository.GraphData graphData = repository.findFullImpactGraph(types);
        return buildDotGraph(graphData, "full-graph");
    }

    /**
     * Export graph to JSON format for D3.js or other JS visualization libraries.
     */
    public String exportToJson(String entryPointId, int depth) throws IOException {
        ImpactChainRepository.GraphData graphData = repository.findImpactGraph(entryPointId, depth);
        Map<String, Object> jsonGraph = buildJsonGraph(graphData, entryPointId);
        return objectMapper.writeValueAsString(jsonGraph);
    }

    /**
     * Export full graph to JSON format.
     */
    public String exportFullGraphToJson(List<EntryPointType> types) throws IOException {
        ImpactChainRepository.GraphData graphData = repository.findFullImpactGraph(types);
        Map<String, Object> jsonGraph = buildJsonGraph(graphData, "full-graph");
        return objectMapper.writeValueAsString(jsonGraph);
    }

    /**
     * Export graph to Mermaid format for easy documentation embedding.
     */
    public String exportToMermaid(String entryPointId, int depth) {
        ImpactChainRepository.GraphData graphData = repository.findImpactGraph(entryPointId, depth);
        return buildMermaidGraph(graphData, entryPointId);
    }

    /**
     * Export graph to PlantUML format.
     */
    public String exportToPlantUml(String entryPointId, int depth) {
        ImpactChainRepository.GraphData graphData = repository.findImpactGraph(entryPointId, depth);
        return buildPlantUmlGraph(graphData, entryPointId);
    }

    private String buildDotGraph(ImpactChainRepository.GraphData graphData, String graphName) {
        StringBuilder dot = new StringBuilder();
        dot.append("digraph \"").append(graphName).append("\" {\n");
        dot.append("  rankdir=TB;\n");
        dot.append("  node [shape=box, style=filled];\n\n");

        for (var node : graphData.nodes()) {
            String color = getNodeColor(node.type().name());
            dot.append("  \"").append(escapeDot(node.id()))
               .append("\" [label=\"").append(escapeDot(node.name()))
               .append("\\n(").append(escapeDot(node.type().name())).append(")\"")
               .append(", fillcolor=").append(color);

            if (node.isEntryPoint()) {
                dot.append(", peripheries=2, style=filled");
            }
            dot.append("];\n");
        }

        dot.append("\n");

        for (var edge : graphData.relations()) {
            String color = getEdgeColor(edge.type().name());
            dot.append("  \"").append(escapeDot(edge.fromId()))
               .append("\" -> \"").append(escapeDot(edge.toId()))
               .append("\" [label=\"").append(escapeDot(edge.type().name()))
               .append("\", color=").append(color).append("];\n");
        }

        dot.append("}\n");
        return dot.toString();
    }

    private Map<String, Object> buildJsonGraph(ImpactChainRepository.GraphData graphData, String graphName) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", graphName);
        result.put("generatedAt", LocalDateTime.now().toString());

        List<Map<String, Object>> nodes = graphData.nodes().stream()
                .map(node -> {
                    Map<String, Object> nodeMap = new HashMap<>();
                    nodeMap.put("id", node.id());
                    nodeMap.put("name", node.name());
                    nodeMap.put("type", node.type().name());
                    nodeMap.put("fqn", node.fqn());
                    nodeMap.put("kind", node.kind());
                    nodeMap.put("filePath", node.filePath());
                    nodeMap.put("module", node.module());
                    nodeMap.put("startLine", node.startLine());
                    nodeMap.put("endLine", node.endLine());
                    nodeMap.put("isEntryPoint", node.isEntryPoint());
                    return nodeMap;
                })
                .toList();

        List<Map<String, Object>> edges = graphData.relations().stream()
                .map(edge -> {
                    Map<String, Object> edgeMap = new HashMap<>();
                    edgeMap.put("from", edge.fromId());
                    edgeMap.put("to", edge.toId());
                    edgeMap.put("type", edge.type().name());
                    return edgeMap;
                })
                .toList();

        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    private String buildMermaidGraph(ImpactChainRepository.GraphData graphData, String graphName) {
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("graph TB\n");

        for (var node : graphData.nodes()) {
            String nodeId = sanitizeForMermaid(node.id());
            String label = node.name() + " (" + node.type().name() + ")";
            mermaid.append("  ").append(nodeId).append("[\"").append(escapeMermaid(label)).append("\"]\n");

            if (node.isEntryPoint()) {
                mermaid.append("  style ").append(nodeId).append(" fill:#f9f,stroke:#333,stroke-width:2px\n");
            }
        }

        for (var edge : graphData.relations()) {
            String fromId = sanitizeForMermaid(edge.fromId());
            String toId = sanitizeForMermaid(edge.toId());
            mermaid.append("  ").append(fromId)
                   .append(" -->|").append(escapeMermaid(edge.type().name())).append("| ")
                   .append(toId).append("\n");
        }

        return mermaid.toString();
    }

    private String buildPlantUmlGraph(ImpactChainRepository.GraphData graphData, String graphName) {
        StringBuilder plantuml = new StringBuilder();
        plantuml.append("@startuml ").append(graphName).append("\n");
        plantuml.append("skinparam nodesep 50\n");
        plantuml.append("skinparam ranksep 50\n\n");

        for (var node : graphData.nodes()) {
            String nodeId = sanitizeForPlantUml(node.id());
            String label = node.name();
            String stereotype = node.type().name();

            if (node.isEntryPoint()) {
                plantuml.append("  rectangle \"").append(escapePlantUml(label))
                       .append("\" as ").append(nodeId)
                       .append(" <<").append(stereotype).append(">>\n");
            } else {
                plantuml.append("  component \"").append(escapePlantUml(label))
                       .append("\" as ").append(nodeId)
                       .append(" <<").append(stereotype).append(">>\n");
            }
        }

        plantuml.append("\n");

        for (var edge : graphData.relations()) {
            String fromId = sanitizeForPlantUml(edge.fromId());
            String toId = sanitizeForPlantUml(edge.toId());
            plantuml.append("  ").append(fromId)
                   .append(" --> ").append(toId)
                   .append(" : ").append(escapePlantUml(edge.type().name()))
                   .append("\n");
        }

        plantuml.append("@enduml\n");
        return plantuml.toString();
    }

    private String getNodeColor(String nodeType) {
        return switch (nodeType) {
            case "ENTRY_POINT" -> "#ff9999";
            case "TYPE" -> "#99ccff";
            case "METHOD" -> "#99ff99";
            case "MODULE" -> "#ffff99";
            case "PACKAGE" -> "#ffcc99";
            default -> "#eeeeee";
        };
    }

    private String getEdgeColor(String relationType) {
        return switch (relationType) {
            case "CALLS" -> "#ff0000";
            case "DEPENDS_ON" -> "#0000ff";
            case "EXTENDS" -> "#00ff00";
            case "IMPLEMENTS" -> "#009900";
            case "INJECTS" -> "#9900ff";
            case "INVOKES" -> "#ff9900";
            case "TRIGGERS" -> "#ff00ff";
            default -> "#666666";
        };
    }

    private String escapeDot(String text) {
        if (text == null) return "";
        return text.replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("<", "\\<")
                   .replace(">", "\\>");
    }

    private String escapeMermaid(String text) {
        if (text == null) return "";
        return text.replace("\"", "&quot;")
                   .replace("\n", "<br/>");
    }

    private String escapePlantUml(String text) {
        if (text == null) return "";
        return text.replace("\"", "\\\"");
    }

    private String sanitizeForMermaid(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String sanitizeForPlantUml(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
