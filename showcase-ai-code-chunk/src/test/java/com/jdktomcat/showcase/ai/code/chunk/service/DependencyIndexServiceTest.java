package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphNode;
import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphRelation;
import com.jdktomcat.showcase.ai.code.chunk.domain.NodeType;
import com.jdktomcat.showcase.ai.code.chunk.domain.RelationType;
import com.jdktomcat.showcase.ai.code.chunk.repository.Neo4jGraphRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyIndexServiceTest {

    @Test
    void shouldAppendGitLabHierarchyDuringIndex() throws Exception {
        Path javaFile = Path.of("/tmp/demo/src/main/java/com/example/HelloService.java");
        JavaDependencyAnalyzer.AnalysisResult analysisResult = new JavaDependencyAnalyzer.AnalysisResult(
                List.of(
                        new CodeGraphNode(
                                "TYPE:com.example.HelloService",
                                NodeType.TYPE,
                                "HelloService",
                                "com.example.HelloService",
                                "CLASS",
                                "src/main/java/com/example/HelloService.java",
                                "demo-service",
                                1,
                                20
                        ),
                        new CodeGraphNode(
                                "METHOD:com.example.HelloService#hello()",
                                NodeType.METHOD,
                                "hello",
                                "com.example.HelloService#hello()",
                                "METHOD",
                                "src/main/java/com/example/HelloService.java",
                                "demo-service",
                                10,
                                12
                        )
                ),
                List.of(
                        new CodeGraphRelation(
                                "TYPE:com.example.HelloService",
                                "METHOD:com.example.HelloService#hello()",
                                RelationType.DECLARES
                        )
                )
        );

        CodeRepositoryScanner scanner = new StubScanner(List.of(javaFile));
        JavaDependencyAnalyzer analyzer = new StubAnalyzer(Map.of(javaFile, analysisResult));
        RecordingGraphRepository graphRepository = new RecordingGraphRepository();

        DependencyIndexService service = new DependencyIndexService(scanner, analyzer, graphRepository);
        ReflectionTestUtils.setField(service, "defaultGroup", "default-group");
        ReflectionTestUtils.setField(service, "defaultProject", "default-project");
        ReflectionTestUtils.setField(service, "defaultBranch", "main");

        Map<String, Object> result = service.fullIndex("team-a", "checkout", "feature-login");

        assertThat(graphRepository.savedRunId).isNotBlank();
        assertThat(graphRepository.cleanedRunId).isEqualTo(graphRepository.savedRunId);

        List<CodeGraphNode> nodes = graphRepository.savedNodes;
        List<CodeGraphRelation> relations = graphRepository.savedRelations;
        String groupId = "GROUP:team-a";
        String projectId = "PROJECT:team-a/checkout";
        String branchId = "BRANCH:team-a/checkout@feature-login";
        String fileId = "FILE:team-a/checkout@feature-login:src/main/java/com/example/HelloService.java";
        String typeId = "TYPE:com.example.HelloService";

        assertThat(nodes)
                .extracting(CodeGraphNode::id)
                .contains(groupId, projectId, branchId, fileId, typeId, "METHOD:com.example.HelloService#hello()");

        assertThat(relations)
                .anyMatch(relation -> relation.fromId().equals(groupId)
                        && relation.toId().equals(projectId)
                        && relation.type() == RelationType.CONTAINS)
                .anyMatch(relation -> relation.fromId().equals(projectId)
                        && relation.toId().equals(branchId)
                        && relation.type() == RelationType.CONTAINS)
                .anyMatch(relation -> relation.fromId().equals(branchId)
                        && relation.toId().equals(fileId)
                        && relation.type() == RelationType.CONTAINS)
                .anyMatch(relation -> relation.fromId().equals(fileId)
                        && relation.toId().equals(typeId)
                        && relation.type() == RelationType.CONTAINS);

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("group", "team-a")
                .containsEntry("project", "checkout")
                .containsEntry("branch", "feature-login");
    }

    private static final class StubScanner extends CodeRepositoryScanner {
        private final List<Path> files;

        private StubScanner(List<Path> files) {
            this.files = files;
        }

        @Override
        public List<Path> scan() {
            return files;
        }
    }

    private static final class StubAnalyzer extends JavaDependencyAnalyzer {
        private final Map<Path, AnalysisResult> results;

        private StubAnalyzer(Map<Path, AnalysisResult> results) {
            super(".");
            this.results = results;
        }

        @Override
        public AnalysisResult analyze(Path file) throws IOException {
            AnalysisResult result = results.get(file);
            if (result == null) {
                throw new IOException("No stubbed result for " + file);
            }
            return result;
        }
    }

    private static final class RecordingGraphRepository extends Neo4jGraphRepository {
        private List<CodeGraphNode> savedNodes = List.of();
        private List<CodeGraphRelation> savedRelations = List.of();
        private String savedRunId;
        private String cleanedRunId;

        private RecordingGraphRepository() {
            super(null);
        }

        @Override
        public void saveAll(List<CodeGraphNode> nodes, List<CodeGraphRelation> relations, String runId) {
            savedNodes = List.copyOf(nodes);
            savedRelations = List.copyOf(relations);
            savedRunId = runId;
        }

        @Override
        public void cleanupStaleDependencyData(String runId) {
            cleanedRunId = runId;
        }
    }
}
