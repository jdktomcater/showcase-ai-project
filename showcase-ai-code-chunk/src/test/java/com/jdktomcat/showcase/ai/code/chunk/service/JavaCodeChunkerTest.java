package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.CodeChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaCodeChunkerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldChunkJavaSourceWithJdt() throws IOException {
        Path repoRoot = tempDir.resolve("repo");
        Path sourceRoot = repoRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);

        Path file = sourceRoot.resolve("DemoService.java");
        Files.writeString(file, """
                package com.example;

                /**
                 * Service documentation.
                 */
                public class DemoService {

                    /**
                     * Says hello.
                     */
                    public String hello(String name) {
                        return "hello " + name;
                    }
                }
                """);

        JavaCodeChunker chunker = new JavaCodeChunker();
        ReflectionTestUtils.setField(chunker, "repoName", "demo-repo");
        ReflectionTestUtils.setField(chunker, "repoRoot", repoRoot.toString());

        List<CodeChunk> chunks = chunker.chunk(file);

        assertThat(chunks).hasSize(2);

        CodeChunk classChunk = chunks.stream()
                .filter(chunk -> "class".equals(chunk.symbolType()))
                .findFirst()
                .orElseThrow();
        assertThat(classChunk.path()).isEqualTo("src/main/java/com/example/DemoService.java");
        assertThat(classChunk.packageName()).isEqualTo("com.example");
        assertThat(classChunk.className()).isEqualTo("DemoService");
        assertThat(classChunk.summary()).contains("Service documentation.");
        assertThat(classChunk.content()).isEqualTo("public class DemoService");

        CodeChunk methodChunk = chunks.stream()
                .filter(chunk -> "method".equals(chunk.symbolType()))
                .findFirst()
                .orElseThrow();
        assertThat(methodChunk.methodName()).isEqualTo("hello");
        assertThat(methodChunk.summary()).contains("Says hello.");
        assertThat(methodChunk.content()).contains("public String hello(String name)");
        assertThat(methodChunk.content()).contains("return \"hello \" + name;");
    }
}
