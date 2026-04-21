package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphNode;
import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphRelation;
import com.jdktomcat.showcase.ai.code.chunk.domain.NodeType;
import com.jdktomcat.showcase.ai.code.chunk.domain.RelationType;
import com.jdktomcat.showcase.ai.code.chunk.repository.Neo4jGraphRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DependencyIndexService {

    private static final Logger log = LoggerFactory.getLogger(DependencyIndexService.class);

    private final CodeRepositoryScanner scanner;
    private final JavaDependencyAnalyzer analyzer;
    private final Neo4jGraphRepository graphRepository;

    @Value("${app.dependency-graph.gitlab.group:default-group}")
    private String defaultGroup;

    @Value("${app.dependency-graph.gitlab.project:${app.code-rag.repo-name:default-project}}")
    private String defaultProject;

    @Value("${app.dependency-graph.gitlab.branch:main}")
    private String defaultBranch;

    public Map<String, Object> fullIndex() throws IOException {
        return fullIndex(null, null, null);
    }

    public Map<String, Object> fullIndex(String group, String project, String branch) throws IOException {
        GraphScope scope = resolveScope(group, project, branch);
        log.info("开始依赖图索引 group={} project={} branch={}", scope.group(), scope.project(), scope.branch());
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

        appendGitLabHierarchy(scope, aggregatedNodes, aggregatedRelations);

        graphRepository.saveAll(
                List.copyOf(aggregatedNodes),
                List.copyOf(aggregatedRelations),
                runId
        );
        graphRepository.cleanupStaleDependencyData(runId);

        int nodeCount = aggregatedNodes.size();
        int relationCount = aggregatedRelations.size();
        log.info("依赖图索引完成 files={} nodes={} relations={} skipped={} group={} project={} branch={}",
                fileCount, nodeCount, relationCount, skippedFiles, scope.group(), scope.project(), scope.branch());
        return Map.of(
                "success", true,
                "files", fileCount,
                "nodes", nodeCount,
                "relations", relationCount,
                "skippedFiles", skippedFiles,
                "group", scope.group(),
                "project", scope.project(),
                "branch", scope.branch()
        );
    }

    private GraphScope resolveScope(String group, String project, String branch) {
        return new GraphScope(
                sanitize(group, defaultGroup),
                sanitize(project, defaultProject),
                sanitize(branch, defaultBranch)
        );
    }

    private String sanitize(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isBlank()) {
            return trimmed;
        }
        return Objects.requireNonNullElse(fallback, "").trim();
    }

    private void appendGitLabHierarchy(
            GraphScope scope,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        if (scope.group().isBlank() || scope.project().isBlank() || scope.branch().isBlank()) {
            return;
        }

        String groupFqn = scope.group();
        String projectFqn = scope.group() + "/" + scope.project();
        String branchFqn = projectFqn + "@" + scope.branch();

        String groupId = nodeId(NodeType.GROUP, groupFqn);
        String projectId = nodeId(NodeType.PROJECT, projectFqn);
        String branchId = nodeId(NodeType.BRANCH, branchFqn);

        nodes.add(new CodeGraphNode(groupId, NodeType.GROUP, scope.group(), groupFqn,
                "GITLAB_GROUP", null, scope.project(), null, null));
        nodes.add(new CodeGraphNode(projectId, NodeType.PROJECT, scope.project(), projectFqn,
                "GITLAB_PROJECT", null, scope.project(), null, null));
        nodes.add(new CodeGraphNode(branchId, NodeType.BRANCH, scope.branch(), branchFqn,
                "GITLAB_BRANCH", null, scope.project(), null, null));

        relations.add(new CodeGraphRelation(groupId, projectId, RelationType.CONTAINS));
        relations.add(new CodeGraphRelation(projectId, branchId, RelationType.CONTAINS));

        Set<String> fileNodeIds = new LinkedHashSet<>();
        for (CodeGraphNode node : nodes.stream().toList()) {
            if (node.type() != NodeType.TYPE || node.filePath() == null || node.filePath().isBlank()) {
                continue;
            }
            String fileFqn = branchFqn + ":" + node.filePath();
            String fileId = nodeId(NodeType.FILE, fileFqn);
            if (fileNodeIds.add(fileId)) {
                nodes.add(new CodeGraphNode(
                        fileId,
                        NodeType.FILE,
                        Path.of(node.filePath()).getFileName().toString(),
                        fileFqn,
                        "SOURCE_FILE",
                        node.filePath(),
                        node.module(),
                        null,
                        null
                ));
                relations.add(new CodeGraphRelation(branchId, fileId, RelationType.CONTAINS));
            }
            relations.add(new CodeGraphRelation(fileId, node.id(), RelationType.CONTAINS));
        }
    }

    private String nodeId(NodeType type, String fqn) {
        return type.name() + ":" + fqn;
    }

    private record GraphScope(
            String group,
            String project,
            String branch
    ) {
    }
}
