package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.dto.CodeSearchHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeSearchService {

    private static final int TEXT_PREVIEW_MAX = 2000;

    private final VectorStore vectorStore;

    public List<CodeSearchHit> search(String query, int topK) {
        log.debug("执行代码搜索 query={} topK={}", query, topK);
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThresholdAll()
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        log.debug("向量搜索返回 {} 个文档", documents.size());
        
        List<CodeSearchHit> hits = documents.stream().map(this::toHit).toList();
        log.debug("代码搜索完成 query={} hits={}", query, hits.size());
        return hits;
    }

    private CodeSearchHit toHit(Document document) {
        Map<String, Object> meta = new LinkedHashMap<>(document.getMetadata());
        String text = document.getText() == null ? "" : document.getText();
        String preview = text.length() <= TEXT_PREVIEW_MAX ? text : text.substring(0, TEXT_PREVIEW_MAX) + "…";
        return new CodeSearchHit(
                document.getScore(),
                stringMeta(meta, "chunkId"),
                stringMeta(meta, "repo"),
                stringMeta(meta, "path"),
                stringMeta(meta, "language"),
                stringMeta(meta, "packageName"),
                stringMeta(meta, "className"),
                stringMeta(meta, "methodName"),
                stringMeta(meta, "symbolType"),
                intMeta(meta, "startLine", -1),
                intMeta(meta, "endLine", -1),
                preview,
                meta
        );
    }

    private static String stringMeta(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static int intMeta(Map<String, Object> meta, String key, int defaultValue) {
        Object v = meta.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
