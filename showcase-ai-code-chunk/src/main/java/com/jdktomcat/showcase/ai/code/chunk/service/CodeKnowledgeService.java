package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.dto.CodeSearchHit;
import com.jdktomcat.showcase.ai.code.chunk.dto.FileReadRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.FileReadResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.GrepSearchHit;
import com.jdktomcat.showcase.ai.code.chunk.dto.GrepSearchRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.HybridSearchHit;
import com.jdktomcat.showcase.ai.code.chunk.dto.HybridSearchRequest;
import com.jdktomcat.showcase.ai.code.chunk.dto.HybridSearchResponse;
import com.jdktomcat.showcase.ai.code.chunk.dto.RepositorySummaryResponse;
import com.jdktomcat.showcase.ai.code.chunk.repository.Neo4jGraphRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeKnowledgeService {

    private static final int DEFAULT_FILE_WINDOW = 200;
    private static final int RRF_K = 60;

    private final CodeRepositoryScanner scanner;
    private final CodeSearchService codeSearchService;
    private final Neo4jGraphRepository neo4jGraphRepository;

    @Value("${app.code-rag.repo-name}")
    private String repoName;

    @Value("${app.code-rag.repo-root}")
    private String repoRoot;

    public RepositorySummaryResponse repositorySummary() throws IOException {
        List<Path> files = scanner.scan();
        Map<String, Long> filesByExtension = new LinkedHashMap<>();
        Map<String, Long> filesByModule = new LinkedHashMap<>();
        int javaFiles = 0;

        Path root = Path.of(repoRoot).normalize();
        for (Path file : files) {
            String relativePath = normalize(root.relativize(file));
            if (relativePath.endsWith(".java")) {
                javaFiles++;
            }
            filesByExtension.merge(fileExtension(relativePath), 1L, Long::sum);
            filesByModule.merge(extractModule(relativePath), 1L, Long::sum);
        }

        Map<String, Long> graphNodesByType = safeGraphStats(true);
        Map<String, Long> graphRelationsByType = safeGraphStats(false);

        return new RepositorySummaryResponse(
                true,
                repoName,
                root.toString(),
                files.size(),
                javaFiles,
                filesByExtension,
                filesByModule,
                graphNodesByType,
                graphRelationsByType,
                List.of(
                        "semantic-search",
                        "hybrid-search",
                        "grep",
                        "file-read",
                        "dependency-graph",
                        "impact-graph",
                        "impact-chain"
                )
        );
    }

    public HybridSearchResponse hybridSearch(HybridSearchRequest request) throws IOException {
        List<CodeSearchHit> semanticHits = codeSearchService.search(request.query(), request.resolvedSemanticTopK()).stream()
                .filter(hit -> matchesPathPrefix(hit.path(), request.normalizedPathPrefix()))
                .toList();

        GrepSearchRequest grepRequest = new GrepSearchRequest(
                request.query(),
                request.normalizedPathPrefix(),
                false,
                false,
                1,
                request.resolvedLexicalTopK()
        );
        List<GrepSearchHit> grepHits = grep(grepRequest);

        Map<String, HybridAccumulator> accumulators = new LinkedHashMap<>();

        for (int i = 0; i < semanticHits.size(); i++) {
            CodeSearchHit hit = semanticHits.get(i);
            HybridAccumulator accumulator = accumulators.computeIfAbsent(hit.path(), ignored -> new HybridAccumulator(hit.path()));
            accumulator.addSemantic(i + 1, hit);
        }

        LinkedHashSet<String> lexicalPaths = new LinkedHashSet<>();
        int lexicalRank = 0;
        for (GrepSearchHit hit : grepHits) {
            if (!lexicalPaths.add(hit.path())) {
                continue;
            }
            lexicalRank++;
            HybridAccumulator accumulator = accumulators.computeIfAbsent(hit.path(), ignored -> new HybridAccumulator(hit.path()));
            accumulator.addLexical(lexicalRank, hit);
        }

        List<HybridSearchHit> hits = accumulators.values().stream()
                .sorted((left, right) -> Double.compare(right.score, left.score))
                .limit(request.resolvedLimit())
                .map(HybridAccumulator::toHit)
                .toList();

        return new HybridSearchResponse(
                true,
                request.query(),
                hits.size(),
                hits,
                semanticHits,
                grepHits
        );
    }

    public List<GrepSearchHit> grep(GrepSearchRequest request) throws IOException {
        List<Path> files = scanner.scan();
        List<GrepSearchHit> hits = new ArrayList<>();
        Pattern regex = request.resolvedRegex() ? compilePattern(request) : null;
        Path root = Path.of(repoRoot).normalize();

        for (Path file : files) {
            String relativePath = normalize(root.relativize(file));
            if (!matchesPathPrefix(relativePath, request.normalizedPathPrefix())) {
                continue;
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                int column = findMatchColumn(line, request, regex);
                if (column < 0) {
                    continue;
                }

                int startLine = Math.max(1, index + 1 - request.resolvedContextLines());
                int endLine = Math.min(lines.size(), index + 1 + request.resolvedContextLines());
                hits.add(new GrepSearchHit(
                        relativePath,
                        index + 1,
                        column + 1,
                        startLine,
                        endLine,
                        buildPreview(lines, startLine, endLine)
                ));

                if (hits.size() >= request.resolvedLimit()) {
                    return hits;
                }
            }
        }
        return hits;
    }

    public FileReadResponse readFile(FileReadRequest request) throws IOException {
        Path file = resolveRepoPath(request.filePath());
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(BAD_REQUEST, "File does not exist in repo: " + request.filePath());
        }

        List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int totalLines = allLines.size();
        int startLine = Math.min(Math.max(1, request.resolvedStartLine()), Math.max(totalLines, 1));
        int endLine = request.resolvedEndLine() == null
                ? Math.min(totalLines, startLine + DEFAULT_FILE_WINDOW - 1)
                : Math.min(totalLines, Math.max(startLine, request.resolvedEndLine()));

        List<String> window = totalLines == 0
                ? List.of()
                : new ArrayList<>(allLines.subList(startLine - 1, endLine));

        return new FileReadResponse(
                true,
                normalize(Path.of(repoRoot).normalize().relativize(file)),
                totalLines,
                totalLines == 0 ? 0 : startLine,
                totalLines == 0 ? 0 : endLine,
                window
        );
    }

    private Map<String, Long> safeGraphStats(boolean nodeStats) {
        try {
            return nodeStats
                    ? neo4jGraphRepository.countNodesByType()
                    : neo4jGraphRepository.countRelationsByType();
        } catch (Exception ex) {
            log.warn("Graph stats unavailable", ex);
            return Map.of();
        }
    }

    private Pattern compilePattern(GrepSearchRequest request) {
        try {
            return Pattern.compile(
                    request.pattern(),
                    request.resolvedCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            );
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid regex pattern: " + ex.getMessage(), ex);
        }
    }

    private int findMatchColumn(String line, GrepSearchRequest request, Pattern regex) {
        if (request.resolvedRegex()) {
            Matcher matcher = regex.matcher(line);
            return matcher.find() ? matcher.start() : -1;
        }

        if (request.resolvedCaseSensitive()) {
            return line.indexOf(request.pattern());
        }
        return line.toLowerCase(Locale.ROOT).indexOf(request.pattern().toLowerCase(Locale.ROOT));
    }

    private boolean matchesPathPrefix(String path, String pathPrefix) {
        return pathPrefix == null || pathPrefix.isBlank() || path.startsWith(pathPrefix);
    }

    private String buildPreview(List<String> lines, int startLine, int endLine) {
        StringBuilder builder = new StringBuilder();
        for (int lineNumber = startLine; lineNumber <= endLine; lineNumber++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lineNumber).append(": ").append(lines.get(lineNumber - 1));
        }
        return builder.toString();
    }

    private Path resolveRepoPath(String relativePath) {
        Path root = Path.of(repoRoot).normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new ResponseStatusException(BAD_REQUEST, "Path escapes repo root: " + relativePath);
        }
        return resolved;
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String fileExtension(String relativePath) {
        int index = relativePath.lastIndexOf('.');
        return index < 0 ? "(none)" : relativePath.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String extractModule(String relativePath) {
        int index = relativePath.indexOf('/');
        return index < 0 ? "." : relativePath.substring(0, index);
    }

    private static final class HybridAccumulator {
        private final String path;
        private double score;
        private Integer semanticRank;
        private Integer lexicalRank;
        private Double semanticScore;
        private Integer firstMatchingLine;
        private Integer startLine;
        private Integer endLine;
        private String className;
        private String methodName;
        private String preview;
        private final LinkedHashSet<String> signals = new LinkedHashSet<>();

        private HybridAccumulator(String path) {
            this.path = path;
        }

        private void addSemantic(int rank, CodeSearchHit hit) {
            if (semanticRank == null) {
                semanticRank = rank;
                semanticScore = hit.score();
                startLine = hit.startLine() > 0 ? hit.startLine() : null;
                endLine = hit.endLine() > 0 ? hit.endLine() : null;
                className = blankToNull(hit.className());
                methodName = blankToNull(hit.methodName());
                preview = hit.textPreview();
            }
            score += reciprocalRank(rank);
            signals.add("semantic");
        }

        private void addLexical(int rank, GrepSearchHit hit) {
            if (lexicalRank == null) {
                lexicalRank = rank;
                firstMatchingLine = hit.lineNumber();
                if (startLine == null) {
                    startLine = hit.startLine();
                }
                if (endLine == null) {
                    endLine = hit.endLine();
                }
                preview = hit.preview();
            }
            score += reciprocalRank(rank);
            signals.add("lexical");
        }

        private HybridSearchHit toHit() {
            return new HybridSearchHit(
                    path,
                    score,
                    semanticRank,
                    lexicalRank,
                    semanticScore,
                    firstMatchingLine,
                    startLine,
                    endLine,
                    className,
                    methodName,
                    preview,
                    new ArrayList<>(signals)
            );
        }

        private static double reciprocalRank(int rank) {
            return 1.0d / (RRF_K + rank);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
