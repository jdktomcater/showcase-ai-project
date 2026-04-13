package com.jdktomcat.showcase.ai.code.chunk.controller;

import com.jdktomcat.showcase.ai.code.chunk.domain.EntryPointType;
import com.jdktomcat.showcase.ai.code.chunk.dto.AnalysisResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.EntryPointListResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.ImpactChainRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.ImpactChainResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.ImpactGraphRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.ImpactGraphResponse;
import com.jdktomcat.showcase.ai.code.chunk.repository.ImpactChainRepository;
import com.jdktomcat.showcase.ai.code.chunk.service.GraphExportService;
import com.jdktomcat.showcase.ai.code.chunk.service.ImpactChainAnalyzer;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/impact")
public class ImpactChainController {

    private final ImpactChainAnalyzer analyzer;
    private final GraphExportService graphExportService;

    public ImpactChainController(ImpactChainAnalyzer analyzer, GraphExportService graphExportService) {
        this.analyzer = analyzer;
        this.graphExportService = graphExportService;
    }

    /**
     * Execute full impact chain analysis.
     */
    @PostMapping("/analyze")
    public AnalysisResponse analyze() {
        log.info("收到影响链分析请求");
        long startTime = System.currentTimeMillis();
        try {
            ImpactChainAnalyzer.AnalysisResult result = analyzer.fullAnalysis();
            long duration = System.currentTimeMillis() - startTime;
            log.info("影响链分析完成 entryPoints={} nodes={} relations={} 耗时={}ms", 
                    result.entryPointCount(), result.nodeCount(), result.relationCount(), duration);
            return new AnalysisResponse(
                    true,
                    result.entryPointCount(),
                    result.nodeCount(),
                    result.relationCount(),
                    "Analysis completed successfully"
            );
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("影响链分析失败 耗时={}ms error={}", duration, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Analysis failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get all entry points.
     */
    @GetMapping("/entry-points")
    public EntryPointListResponse listEntryPoints() {
        log.debug("获取所有入口点");
        List<Map<String, Object>> entryPoints = analyzer.getAllEntryPoints();
        log.debug("入口点获取完成 count={}", entryPoints.size());
        return new EntryPointListResponse(true, entryPoints.size(), entryPoints);
    }

    /**
     * Get entry points by type.
     */
    @GetMapping("/entry-points/by-type")
    public EntryPointListResponse listEntryPointsByType(@RequestParam EntryPointType type) {
        log.debug("按类型获取入口点 type={}", type);
        List<Map<String, Object>> entryPoints = analyzer.getEntryPointsByType(type);
        log.debug("类型入口点获取完成 type={} count={}", type, entryPoints.size());
        return new EntryPointListResponse(true, entryPoints.size(), entryPoints);
    }

    /**
     * Query impact chain for a specific entry point.
     */
    @PostMapping("/chain")
    public ImpactChainResponse getImpactChain(@Valid @RequestBody ImpactChainRequest request) {
        if (request.entryPointId() == null || request.entryPointId().isBlank()) {
            log.warn("影响链查询参数错误 entryPointId 为空");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entryPointId is required");
        }

        log.debug("查询影响链 entryPointId={} depth={}", request.entryPointId(), request.resolvedDepth());
        List<Map<String, Object>> chain = analyzer.queryImpactChain(
                request.entryPointId(),
                request.resolvedDepth()
        );
        log.debug("影响链查询完成 entryPointId={} chainSize={}", request.entryPointId(), chain.size());

        ImpactChainResponse.EntryPointTypeView typeView = ImpactChainResponse.EntryPointTypeView.HTTP;
        if (!chain.isEmpty()) {
            String typeStr = (String) chain.get(0).get("entryPointType");
            if (typeStr != null) {
                try {
                    typeView = ImpactChainResponse.EntryPointTypeView.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    // Ignore, use default
                }
            }
        }

        return new ImpactChainResponse(
                true,
                request.entryPointId(),
                typeView,
                request.resolvedDepth(),
                chain.size(),
                chain
        );
    }

    /**
     * Get impact graph for visualization.
     */
    @PostMapping("/graph")
    public ImpactGraphResponse getImpactGraph(@Valid @RequestBody ImpactGraphRequest request) {
        log.debug("获取影响图 mode={} entryPointId={} depth={}", 
                request.resolvedMode(), request.entryPointId(), request.resolvedDepth());
        try {
            ImpactChainRepository.GraphData graphData;

            if ("full".equals(request.resolvedMode())) {
                List<EntryPointType> types = request.resolvedEntryPointTypes().stream()
                        .map(EntryPointType::valueOf)
                        .toList();
                log.debug("获取完整影响图 types={}", types);
                graphData = analyzer.getFullImpactGraph(types);
            } else {
                if (request.entryPointId() == null || request.entryPointId().isBlank()) {
                    log.warn("影响图查询参数错误 entryPointId 为空");
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entryPointId is required for impact mode");
                }
                log.debug("获取单点影响图 entryPointId={}", request.entryPointId());
                graphData = analyzer.getImpactGraph(request.entryPointId(), request.resolvedDepth());
            }

            List<ImpactGraphResponse.NodeView> nodes = graphData.nodes().stream()
                    .map(node -> new ImpactGraphResponse.NodeView(
                            node.id(),
                            node.name(),
                            node.type().name(),
                            node.fqn(),
                            node.kind(),
                            node.filePath(),
                            node.module(),
                            node.startLine(),
                            node.endLine(),
                            node.isEntryPoint()
                    ))
                    .toList();

            List<ImpactGraphResponse.EdgeView> edges = graphData.relations().stream()
                    .map(relation -> new ImpactGraphResponse.EdgeView(
                            relation.fromId() + "->" + relation.toId() + ":" + relation.type().name(),
                            relation.fromId(),
                            relation.toId(),
                            relation.type().name()
                    ))
                    .toList();

            log.debug("影响图查询完成 mode={} nodes={} edges={}", 
                    request.resolvedMode(), nodes.size(), edges.size());
            return new ImpactGraphResponse(
                    true,
                    request.resolvedMode(),
                    request.entryPointId() != null ? request.entryPointId() : "TYPE:*",
                    nodes.size(),
                    edges.size(),
                    nodes,
                    edges
            );

        } catch (IllegalArgumentException e) {
            log.warn("影响图查询参数错误 mode={} error={}", request.resolvedMode(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("影响图查询失败 mode={} error={}", request.resolvedMode(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Export impact graph to DOT format.
     */
    @GetMapping("/export/dot")
    public ResponseEntity<String> exportDot(
            @RequestParam String entryPointId,
            @RequestParam(defaultValue = "5") int depth
    ) {
        log.debug("导出 DOT 格式 entryPointId={} depth={}", entryPointId, depth);
        try {
            String dotContent = graphExportService.exportToDot(entryPointId, depth);
            log.debug("DOT 导出完成 entryPointId={} size={} bytes", entryPointId, dotContent.length());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/vnd.graphviz")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + entryPointId + ".dot\"")
                    .body(dotContent);
        } catch (IOException e) {
            log.error("DOT 导出失败 entryPointId={} error={}", entryPointId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + e.getMessage(), e);
        }
    }

    /**
     * Export full impact graph to DOT format.
     */
    @GetMapping("/export/dot/full")
    public ResponseEntity<String> exportFullDot(
            @RequestParam(required = false) List<String> types
    ) {
        log.debug("导出完整 DOT 格式 types={}", types);
        try {
            List<EntryPointType> entryPointTypes = types != null ?
                    types.stream().map(EntryPointType::valueOf).toList() : List.of();
            String dotContent = graphExportService.exportFullGraphToDot(entryPointTypes);
            log.debug("完整 DOT 导出完成 size={} bytes", dotContent.length());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/vnd.graphviz")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"impact-graph-full.dot\"")
                    .body(dotContent);
        } catch (IOException e) {
            log.error("完整 DOT 导出失败 error={}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + e.getMessage(), e);
        }
    }

    /**
     * Export impact graph to JSON format.
     */
    @GetMapping("/export/json")
    public ResponseEntity<String> exportJson(
            @RequestParam String entryPointId,
            @RequestParam(defaultValue = "5") int depth
    ) {
        log.debug("导出 JSON 格式 entryPointId={} depth={}", entryPointId, depth);
        try {
            String jsonContent = graphExportService.exportToJson(entryPointId, depth);
            log.debug("JSON 导出完成 entryPointId={} size={} bytes", entryPointId, jsonContent.length());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + entryPointId + ".json\"")
                    .body(jsonContent);
        } catch (IOException e) {
            log.error("JSON 导出失败 entryPointId={} error={}", entryPointId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + e.getMessage(), e);
        }
    }

    /**
     * Export impact graph to Mermaid format.
     */
    @GetMapping("/export/mermaid")
    public ResponseEntity<String> exportMermaid(
            @RequestParam String entryPointId,
            @RequestParam(defaultValue = "5") int depth
    ) {
        log.debug("导出 Mermaid 格式 entryPointId={} depth={}", entryPointId, depth);
        String mermaidContent = graphExportService.exportToMermaid(entryPointId, depth);
        log.debug("Mermaid 导出完成 entryPointId={} size={} bytes", entryPointId, mermaidContent.length());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + entryPointId + ".mmd\"")
                .body(mermaidContent);
    }

    /**
     * Export impact graph to PlantUML format.
     */
    @GetMapping("/export/plantuml")
    public ResponseEntity<String> exportPlantUml(
            @RequestParam String entryPointId,
            @RequestParam(defaultValue = "5") int depth
    ) {
        log.debug("导出 PlantUML 格式 entryPointId={} depth={}", entryPointId, depth);
        String plantumlContent = graphExportService.exportToPlantUml(entryPointId, depth);
        log.debug("PlantUML 导出完成 entryPointId={} size={} bytes", entryPointId, plantumlContent.length());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + entryPointId + ".puml\"")
                .body(plantumlContent);
    }
}
