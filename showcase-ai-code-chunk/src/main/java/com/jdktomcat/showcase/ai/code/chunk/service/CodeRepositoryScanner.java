package com.jdktomcat.showcase.ai.code.chunk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Service
public class CodeRepositoryScanner {

    private static final List<String> EXCLUDED_SEGMENTS = List.of(
            ".git", ".idea", ".settings", ".vscode",
            "target", "build", "node_modules", "dist"
    );

    @Value("${app.code-rag.repo-root}")
    private String repoRoot;

    public List<Path> scan() throws IOException {
        try (Stream<Path> stream = Files.walk(Path.of(repoRoot))) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .filter(this::notExcluded)
                    .toList();
        }
    }

    private boolean isSupportedFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java")
                || name.endsWith(".xml")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".md")
                || name.endsWith(".sql");
    }

    private boolean notExcluded(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return EXCLUDED_SEGMENTS.stream().noneMatch(segment -> normalized.contains("/" + segment + "/"));
    }
}
