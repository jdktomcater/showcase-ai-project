package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.EntryPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans Java source files and delegates entry point detection to Spoon.
 */
@Service
public class EntryPointScanner {

    private static final Logger log = LoggerFactory.getLogger(EntryPointScanner.class);

    private final Path repoRoot;
    private final SpoonEntryPointAnalyzer spoonEntryPointAnalyzer;

    public EntryPointScanner(
            @Value("${app.impact-analysis.repo-root:#{null}}") String repoRoot,
            SpoonEntryPointAnalyzer spoonEntryPointAnalyzer
    ) {
        this.repoRoot = repoRoot != null && !repoRoot.isBlank() ? Path.of(repoRoot) : Path.of(".").toAbsolutePath();
        this.spoonEntryPointAnalyzer = spoonEntryPointAnalyzer;
    }

    public List<EntryPoint> scan() throws IOException {
        List<EntryPoint> entryPoints = new ArrayList<>();
        List<Path> javaFiles = findJavaFiles();

        for (Path file : javaFiles) {
            try {
                entryPoints.addAll(spoonEntryPointAnalyzer.analyze(file));
            } catch (Exception e) {
                log.warn("Failed to analyze file for entry points: {}", file, e);
            }
        }

        log.info("Scanned {} Java files, found {} entry points", javaFiles.size(), entryPoints.size());
        return entryPoints;
    }

    private List<Path> findJavaFiles() throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        Files.walk(repoRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.toString().contains("/target/") && !path.toString().contains("/build/"))
                .forEach(javaFiles::add);
        return javaFiles;
    }
}
