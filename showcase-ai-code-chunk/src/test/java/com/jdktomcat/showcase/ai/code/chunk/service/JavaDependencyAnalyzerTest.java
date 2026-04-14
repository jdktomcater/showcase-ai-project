package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.RelationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JavaDependencyAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAnalyzeDependenciesWithJdtBindings() throws IOException {
        Path repoRoot = tempDir.resolve("repo");
        Path sourceRoot = repoRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);

        Files.writeString(sourceRoot.resolve("BaseService.java"), """
                package com.example;

                public abstract class BaseService {
                }
                """);

        Files.writeString(sourceRoot.resolve("GreetingPort.java"), """
                package com.example;

                public interface GreetingPort {
                }
                """);

        Files.writeString(sourceRoot.resolve("SupportClient.java"), """
                package com.example;

                public class SupportClient {

                    public String load(String name) {
                        return name.trim();
                    }
                }
                """);

        Path file = sourceRoot.resolve("HelloService.java");
        Files.writeString(file, """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;

                @Service
                public class HelloService extends BaseService implements GreetingPort {

                    @Autowired
                    private SupportClient supportClient;

                    public String hello(String name) {
                        return supportClient.load(name);
                    }
                }
                """);

        JavaDependencyAnalyzer analyzer = new JavaDependencyAnalyzer(repoRoot.toString());
        JavaDependencyAnalyzer.AnalysisResult result = analyzer.analyze(file);

        assertThat(result.nodes())
                .anyMatch(node -> "TYPE:com.example.HelloService".equals(node.id()))
                .anyMatch(node -> "TYPE:com.example.BaseService".equals(node.id()))
                .anyMatch(node -> "TYPE:com.example.GreetingPort".equals(node.id()))
                .anyMatch(node -> "METHOD:com.example.HelloService#hello(java.lang.String)".equals(node.id()))
                .anyMatch(node -> "METHOD:com.example.SupportClient#load(java.lang.String)".equals(node.id()));

        String helloTypeId = "TYPE:com.example.HelloService";
        String helloMethodId = "METHOD:com.example.HelloService#hello(java.lang.String)";

        assertThat(result.relations())
                .anyMatch(relation -> relation.fromId().equals(helloTypeId)
                        && relation.toId().equals("ANNOTATION:Service")
                        && relation.type() == RelationType.ANNOTATED_BY)
                .anyMatch(relation -> relation.fromId().equals(helloTypeId)
                        && relation.toId().equals("TYPE:com.example.BaseService")
                        && relation.type() == RelationType.EXTENDS)
                .anyMatch(relation -> relation.fromId().equals(helloTypeId)
                        && relation.toId().equals("TYPE:com.example.GreetingPort")
                        && relation.type() == RelationType.IMPLEMENTS)
                .anyMatch(relation -> relation.fromId().equals(helloTypeId)
                        && relation.toId().equals("TYPE:com.example.SupportClient")
                        && relation.type() == RelationType.INJECTS)
                .anyMatch(relation -> relation.fromId().equals(helloTypeId)
                        && relation.toId().equals(helloMethodId)
                        && relation.type() == RelationType.DECLARES)
                .anyMatch(relation -> relation.fromId().equals(helloMethodId)
                        && relation.toId().equals("TYPE:java.lang.String")
                        && relation.type() == RelationType.RETURNS)
                .anyMatch(relation -> relation.fromId().equals(helloMethodId)
                        && relation.toId().equals("TYPE:java.lang.String")
                        && relation.type() == RelationType.HAS_PARAM_TYPE)
                .anyMatch(relation -> relation.fromId().equals(helloMethodId)
                        && relation.toId().equals("METHOD:com.example.SupportClient#load(java.lang.String)")
                        && relation.type() == RelationType.CALLS);
    }

    @Test
    void shouldTolerateMissingExternalTypes() throws IOException {
        Path repoRoot = tempDir.resolve("missing-deps-repo");
        Path sourceRoot = repoRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);

        Path file = sourceRoot.resolve("MinioConfig.java");
        Files.writeString(file, """
                package com.example;

                import io.minio.MinioClient;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class MinioConfig {

                    @Bean
                    public MinioClient minioClient() {
                        return null;
                    }
                }
                """);

        JavaDependencyAnalyzer analyzer = new JavaDependencyAnalyzer(repoRoot.toString());
        JavaDependencyAnalyzer.AnalysisResult result = analyzer.analyze(file);

        assertThat(result.nodes())
                .anyMatch(node -> "TYPE:com.example.MinioConfig".equals(node.id()))
                .anyMatch(node -> "METHOD:com.example.MinioConfig#minioClient()".equals(node.id()))
                .anyMatch(node -> "TYPE:io.minio.MinioClient".equals(node.id()));

        assertThat(result.relations())
                .anyMatch(relation -> relation.fromId().equals("TYPE:com.example.MinioConfig")
                        && relation.toId().equals("TYPE:io.minio.MinioClient")
                        && relation.type() == RelationType.DEPENDS_ON)
                .anyMatch(relation -> relation.fromId().equals("METHOD:com.example.MinioConfig#minioClient()")
                        && relation.toId().equals("TYPE:com.example.MinioClient")
                        && relation.type() == RelationType.RETURNS);
    }
}
