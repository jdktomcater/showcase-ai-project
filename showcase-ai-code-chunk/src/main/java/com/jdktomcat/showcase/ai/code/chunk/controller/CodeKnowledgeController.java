package com.jdktomcat.showcase.ai.code.chunk.controller;

import com.jdktomcat.showcase.ai.code.chunk.dto.FileReadRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.FileReadResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.GrepSearchHit;
import com.jdktomcat.showcase.ai.code.chunk.dto.GrepSearchRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.HybridSearchRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.HybridSearchResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.RepositorySummaryResponse;
import com.jdktomcat.showcase.ai.code.chunk.service.CodeKnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/code/knowledge")
@RequiredArgsConstructor
public class CodeKnowledgeController {

    private final CodeKnowledgeService codeKnowledgeService;

    @GetMapping("/summary")
    public RepositorySummaryResponse summary() throws IOException {
        log.debug("收到仓库概览请求");
        return codeKnowledgeService.repositorySummary();
    }

    @PostMapping("/search/hybrid")
    public HybridSearchResponse hybridSearch(@Valid @RequestBody HybridSearchRequest request) throws IOException {
        log.debug("收到混合检索请求 query={} pathPrefix={}", request.query(), request.normalizedPathPrefix());
        return codeKnowledgeService.hybridSearch(request);
    }

    @PostMapping("/grep")
    public Map<String, Object> grep(@Valid @RequestBody GrepSearchRequest request) throws IOException {
        log.debug("收到 grep 请求 pattern={} pathPrefix={}", request.pattern(), request.normalizedPathPrefix());
        List<GrepSearchHit> hits = codeKnowledgeService.grep(request);
        return Map.of(
                "success", true,
                "pattern", request.pattern(),
                "count", hits.size(),
                "hits", hits
        );
    }

    @PostMapping("/file")
    public FileReadResponse readFile(@Valid @RequestBody FileReadRequest request) throws IOException {
        log.debug("收到文件读取请求 path={} startLine={} endLine={}",
                request.filePath(), request.resolvedStartLine(), request.resolvedEndLine());
        return codeKnowledgeService.readFile(request);
    }
}
