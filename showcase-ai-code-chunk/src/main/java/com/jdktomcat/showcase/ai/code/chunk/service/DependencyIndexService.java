package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphNode;
import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphRelation;
import com.jdktomcat.showcase.ai.code.chunk.repository.Neo4jGraphRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DependencyIndexService {

    private static final Logger log = LoggerFactory.getLogger(DependencyIndexService.class);

    private final CodeRepositoryScanner scanner;
    private final JavaDependencyAnalyzer analyzer;
    private final Neo4jGraphRepository graphRepository;

    public Map<String, Object> fullIndex() throws IOException {
        log.info("开始依赖图索引");
        List<Path> files = scanner.scan();
        log.info("扫描到 {} 个文件进行依赖分析", files.size());
        String runId = UUID.randomUUID().toString();

        int fileCount = 0;
        int skippedFiles = 0;
        Set<CodeGraphNode> aggregatedNodes = new LinkedHashSet<>();
        Set<CodeGraphRelation> aggregatedRelations = new LinkedHashSet<>();

        for (Path file : files) {
            if (!file.getFileName().toString().endsWith(".java")) {
                continue;
            }

            try {
                log.debug("分析文件依赖 path={}", file);
                JavaDependencyAnalyzer.AnalysisResult result = analyzer.analyze(file);
                fileCount++;
                aggregatedNodes.addAll(result.nodes());
                aggregatedRelations.addAll(result.relations());
                log.debug("文件依赖分析完成 path={} nodes={} relations={}", file, result.nodes().size(), result.relations().size());
            } catch (Exception ex) {
                skippedFiles++;
                log.warn("Skip dependency analysis for {}", file, ex);
            }
        }

        graphRepository.saveAll(
                List.copyOf(aggregatedNodes),
                List.copyOf(aggregatedRelations),
                runId
        );
        graphRepository.cleanupStaleDependencyData(runId);

        int nodeCount = aggregatedNodes.size();
        int relationCount = aggregatedRelations.size();
        log.info("依赖图索引完成 files={} nodes={} relations={} skipped={}", fileCount, nodeCount, relationCount, skippedFiles);
        return Map.of(
                "success", true,
                "files", fileCount,
                "nodes", nodeCount,
                "relations", relationCount,
                "skippedFiles", skippedFiles
        );
    }
}
