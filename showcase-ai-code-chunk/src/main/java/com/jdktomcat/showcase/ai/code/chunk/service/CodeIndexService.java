package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.CodeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeIndexService {

    private final CodeRepositoryScanner scanner;
    private final JavaCodeChunker javaCodeChunker;
    private final VectorStore vectorStore;

    public int fullIndex() throws IOException {
        log.info("开始完整代码索引");
        List<Path> files = scanner.scan();
        log.info("扫描到 {} 个文件 files={}", files.size(), files);
        
        List<Document> documents = new ArrayList<>();
        int chunkedFiles = 0;
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith(".java")) {
                continue;
            }
            log.debug("分块文件 path={}", file);
            List<CodeChunk> chunks = javaCodeChunker.chunk(file);
            chunkedFiles++;
            log.debug("文件分块完成 path={} chunks={}", file, chunks.size());
            
            for (CodeChunk chunk : chunks) {
                documents.add(toDocument(chunk));
            }
        }

        log.info("准备向向量存储添加文档 totalChunks={}", documents.size());
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            log.info("向量存储添加成功 documents={}", documents.size());
        } else {
            log.warn("没有文档可以添加到向量存储");
        }
        
        log.info("完整代码索引完成 scannedFiles={} chunkedFiles={} documents={}", files.size(), chunkedFiles, documents.size());
        return documents.size();
    }

    private Document toDocument(CodeChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkId", chunk.id());
        metadata.put("repo", chunk.repo());
        metadata.put("path", chunk.path());
        metadata.put("language", chunk.language());
        metadata.put("packageName", empty(chunk.packageName()));
        metadata.put("className", empty(chunk.className()));
        metadata.put("methodName", empty(chunk.methodName()));
        metadata.put("symbolType", chunk.symbolType());
        metadata.put("startLine", chunk.startLine());
        metadata.put("endLine", chunk.endLine());
        metadata.put("chunkHash", chunk.chunkHash());
        return new Document(
                chunk.toEmbeddingText(),
                metadata
        );
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }
}
