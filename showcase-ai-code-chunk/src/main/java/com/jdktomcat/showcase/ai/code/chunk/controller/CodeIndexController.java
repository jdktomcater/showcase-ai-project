package com.jdktomcat.showcase.ai.code.chunk.controller;

import com.jdktomcat.showcase.ai.code.chunk.dto.CodeSearchHit;
import com.jdktomcat.showcase.ai.code.chunk.dto.CodeLocationRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.CodeSearchRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.DependencyQueryRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.GraphViewRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.GraphViewResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.ImpactQueryRequest;
import com.jdktomcat.showcase.ai.code.chunk.service.CodeIndexService;
import com.jdktomcat.showcase.ai.code.chunk.service.CodeSearchService;
import com.jdktomcat.showcase.ai.code.chunk.service.DependencyIndexService;
import com.jdktomcat.showcase.ai.code.chunk.service.ImpactAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class CodeIndexController {

    private final CodeIndexService codeIndexService;
    private final CodeSearchService codeSearchService;
    private final DependencyIndexService dependencyIndexService;
    private final ImpactAnalysisService impactAnalysisService;

    @PostMapping("/api/code/index")
    public Map<String, Object> index() throws IOException {
        log.info("收到代码索引请求");
        long startTime = System.currentTimeMillis();
        int count = codeIndexService.fullIndex();
        long duration = System.currentTimeMillis() - startTime;
        log.info("代码索引完成 indexedChunks={} 耗时={}ms", count, duration);
        return Map.of(
                "success", true,
                "indexedChunks", count
        );
    }

    @PostMapping("/api/code/search")
    public Map<String, Object> search(@Valid @RequestBody CodeSearchRequest request) {
        log.debug("收到代码搜索请求 query={} topK={}", request.query(), request.resolvedTopK());
        long startTime = System.currentTimeMillis();
        List<CodeSearchHit> hits = codeSearchService.search(request.query(), request.resolvedTopK());
        long duration = System.currentTimeMillis() - startTime;
        log.debug("代码搜索完成 query={} hits={} 耗时={}ms", request.query(), hits.size(), duration);
        return Map.of(
                "success", true,
                "count", hits.size(),
                "hits", hits
        );
    }

    @PostMapping("/api/code/graph/index")
    public Map<String, Object> indexGraph() throws IOException {
        log.info("收到依赖图索引请求");
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = dependencyIndexService.fullIndex();
        long duration = System.currentTimeMillis() - startTime;
        log.info("依赖图索引完成 result={} 耗时={}ms", result, duration);
        return result;
    }

    @PostMapping("/api/code/dependencies")
    public Map<String, Object> dependencies(@Valid @RequestBody DependencyQueryRequest request) {
        log.debug("收到依赖查询请求 nodeType={} fqn={} depth={}", request.resolvedNodeType(), request.fqn(), request.resolvedDepth());
        List<Map<String, Object>> dependencies = impactAnalysisService.dependencies(
                request.resolvedNodeType(),
                request.fqn(),
                request.resolvedDepth()
        );
        log.debug("依赖查询完成 target={} count={}", request.resolvedNodeType() + ":" + request.fqn(), dependencies.size());
        return Map.of(
                "success", true,
                "target", request.resolvedNodeType() + ":" + request.fqn(),
                "count", dependencies.size(),
                "dependencies", dependencies
        );
    }

    @PostMapping("/api/code/type-dependencies")
    public Map<String, Object> typeDependencies(@Valid @RequestBody DependencyQueryRequest request) {
        log.debug("收到类型依赖查询请求 fqn={} depth={}", request.fqn(), request.resolvedDepth());
        List<Map<String, Object>> dependencies = impactAnalysisService.typeDependencies(
                request.fqn(),
                request.resolvedDepth()
        );
        log.debug("类型依赖查询完成 target={} count={}", "TYPE:" + request.fqn(), dependencies.size());
        return Map.of(
                "success", true,
                "target", "TYPE:" + request.fqn(),
                "count", dependencies.size(),
                "dependencies", dependencies
        );
    }

    @PostMapping("/api/code/impact")
    public Map<String, Object> impact(@Valid @RequestBody ImpactQueryRequest request) {
        log.debug("收到影响面查询请求 methodFqn={} depth={}", request.methodFqn(), request.resolvedDepth());
        List<Map<String, Object>> impacts = impactAnalysisService.impact(
                request.methodFqn(),
                request.resolvedDepth()
        );
        log.debug("影响面查询完成 target={} count={}", request.methodFqn(), impacts.size());
        return Map.of(
                "success", true,
                "target", request.methodFqn(),
                "count", impacts.size(),
                "impacts", impacts
        );
    }

    @PostMapping("/api/code/location")
    public Map<String, Object> locate(@Valid @RequestBody CodeLocationRequest request) {
        log.debug("收到代码定位请求 filePath={} line={}", request.filePath(), request.line());
        List<Map<String, Object>> nodes = impactAnalysisService.locate(request.filePath(), request.line());
        log.debug("代码定位完成 filePath={} line={} count={}", request.filePath(), request.line(), nodes.size());
        return Map.of(
                "success", true,
                "filePath", request.filePath(),
                "line", request.line(),
                "count", nodes.size(),
                "nodes", nodes
        );
    }

    @PostMapping("/api/code/type-impact")
    public Map<String, Object> typeImpact(@Valid @RequestBody DependencyQueryRequest request) {
        log.debug("收到类型影响面查询请求 fqn={} depth={}", request.fqn(), request.resolvedDepth());
        List<Map<String, Object>> impacts = impactAnalysisService.typeImpact(
                request.fqn(),
                request.resolvedDepth()
        );
        log.debug("类型影响面查询完成 target={} count={}", "TYPE:" + request.fqn(), impacts.size());
        return Map.of(
                "success", true,
                "target", "TYPE:" + request.fqn(),
                "count", impacts.size(),
                "impacts", impacts
        );
    }

    @PostMapping("/api/code/graph/view")
    public GraphViewResponse graphView(@Valid @RequestBody GraphViewRequest request) {
        try {
            log.debug("收到图视图请求 mode={} nodeType={} fqn={} methodFqn={} depth={}",
                    request.resolvedMode(), request.nodeType(), request.fqn(), request.methodFqn(), request.resolvedDepth());
            GraphViewResponse response = switch (request.resolvedMode()) {
                case "impact" -> impactAnalysisService.impactGraph(request.requiredMethodFqn(), request.resolvedDepth());
                case "dependencies" -> impactAnalysisService.dependencyGraph(
                        request.requiredNodeType(),
                        request.requiredFqn(),
                        request.resolvedDepth()
                );
                case "classgraph" -> impactAnalysisService.fullTypeDependencyGraph();
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported mode: " + request.mode());
            };
            log.debug("图视图查询完成 mode={} nodes={} edges={}", 
                    request.resolvedMode(), response.nodes().size(), response.edges().size());
            return response;
        } catch (IllegalArgumentException ex) {
            log.warn("图视图查询参数错误 mode={} error={}", request.resolvedMode(), ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("图视图查询失败 mode={} error={}", request.resolvedMode(), ex.getMessage(), ex);
            throw ex;
        }
    }
}
