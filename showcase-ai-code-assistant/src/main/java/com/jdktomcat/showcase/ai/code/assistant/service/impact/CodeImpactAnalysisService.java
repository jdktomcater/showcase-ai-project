package com.jdktomcat.showcase.ai.code.assistant.service.impact;

import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeImpactAnalysisService {

    private static final Pattern DIFF_HUNK_PATTERN = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");
    private static final List<String> FINGERPRINT_EXCLUDED_SEGMENTS = List.of(
            ".git", ".idea", ".settings", ".vscode",
            "target", "build", "node_modules", "dist"
    );

    private final RestTemplate restTemplate;

    @Autowired(required = false)
    @Qualifier("codeChunkGraphRestTemplate")
    private RestTemplate graphIndexRestTemplate;

    @Value("${code-chunk.base-url:http://localhost:8081}")
    private String codeChunkBaseUrl;

    @Value("${code-chunk.impact.enabled:true}")
    private boolean impactEnabled;

    @Value("${code-chunk.impact.depth:1}")
    private int impactDepth;

    @Value("${code-chunk.impact.max-types:3}")
    private int maxTypes;

    @Value("${code-chunk.impact.max-items-per-type:3}")
    private int maxItemsPerType;

    @Value("${code-chunk.impact.repo-root:}")
    private String impactRepoRoot;

    private final Object dependencyGraphRefreshLock = new Object();
    private final Map<String, String> dependencyGraphFingerprintByRepo = new ConcurrentHashMap<>();

    /**
     * 在评审前强制重建依赖图，确保后续审查使用最新图数据。
     */
    public void rebuildDependencyGraphBeforeReview(String repository, CompareResponse compareResponse) {
        refreshDependencyGraph(repository, compareResponse, true);
    }

    private String doBuildGraphImpactSummary(String repository, CompareResponse compareResponse) {
        log.debug("开始构建依赖图影响面摘要 repository={}", repository);
        if (!impactEnabled) {
            log.info("依赖图影响面分析未启用 repository={}", repository);
            return "依赖图影响面分析未启用。";
        }
        if (compareResponse == null || compareResponse.getFiles() == null || compareResponse.getFiles().isEmpty()) {
            log.debug("本次提交无可用 diff 文件 repository={}", repository);
            return "本次提交无可用 diff 文件，无法结合依赖图评估影响面。";
        }

        List<String> changedTypes = extractChangedJavaTypes(compareResponse.getFiles());
        log.debug("提取到 {} 个变更的 Java 类型 repository={}", changedTypes.size(), repository);
        if (changedTypes.isEmpty()) {
            return "本次提交未包含可映射到 Java 类型的工程代码变更。";
        }

        StringBuilder summary = new StringBuilder("## 代码依赖图影响面摘要\n");
        summary.append("- 仓库：").append(safe(repository)).append('\n');
        summary.append("- 变更类型数：").append(changedTypes.size()).append('\n');

        boolean hasData = false;
        for (String typeFqn : changedTypes) {
            try {
                log.debug("查询类型依赖 type={} repository={}", typeFqn, repository);
                DependencyResponse dependencyResponse = fetchTypeDependencies(typeFqn);
                ImpactResponse impactResponse = fetchTypeImpact(typeFqn);
                List<Map<String, Object>> dependencies = limit(dependencyResponse.getDependencies());
                List<Map<String, Object>> impacts = limit(impactResponse.getImpacts());

                summary.append("\n### ").append(typeFqn).append('\n');
                if (dependencies.isEmpty() && impacts.isEmpty()) {
                    summary.append("- 未在依赖图中发现明显工程内关联节点\n");
                    continue;
                }

                hasData = true;
                appendItems(summary, "下游依赖（被当前类型依赖）", dependencies);
                appendItems(summary, "上游影响（当前类型依赖）", impacts);
            } catch (Exception ex) {
                log.warn("读取代码依赖图失败 type={} url={}", typeFqn, codeChunkBaseUrl, ex);
                summary.append("\n### ").append(typeFqn).append('\n')
                        .append("- 依赖图查询失败：").append(ex.getMessage()).append('\n');
            }
        }

        if (!hasData) {
            summary.append("\n- 暂未识别到明确的工程内扩散路径，请结合 diff 人工判断。");
        }
        log.debug("依赖图影响面摘要构建完成 repository={} hasData={}", repository, hasData);
        return summary.toString();
    }

    public ImpactAnalysisResult analyzeImpact(String repository, CompareResponse compareResponse) {
        log.info("开始构建完整影响面评估报告 repository={}", repository);
        refreshDependencyGraph(repository, compareResponse, false);
        ImpactChainAnalysis impactChainAnalysis = analyzeImpactChain(repository, compareResponse);
        StringBuilder report = new StringBuilder();
        report.append("# 代码变更影响面评估报告\n\n");
        report.append("## 变更概览\n");
        report.append("- 仓库：").append(safe(repository)).append('\n');
        report.append("- 变更文件数：").append(compareResponse != null && compareResponse.getFiles() != null ? compareResponse.getFiles().size() : 0).append("\n\n");

        // 1. 代码依赖图影响面
        log.debug("构建代码依赖图影响面 repository={}", repository);
        report.append(doBuildGraphImpactSummary(repository, compareResponse));
        report.append("\n\n---\n\n");

        // 2. 业务影响链路
        log.debug("构建业务影响链路 repository={}", repository);
        report.append(impactChainAnalysis.summary());

        log.info("完整影响面评估报告构建完成 repository={} report 长度={}", repository, report.length());
        return new ImpactAnalysisResult(report.toString(), impactChainAnalysis.affectedEntryPoints());
    }

    private void refreshDependencyGraph(String repository, CompareResponse compareResponse, boolean forceRebuild) {
        if (!impactEnabled) {
            return;
        }
        if (compareResponse == null || compareResponse.getFiles() == null || compareResponse.getFiles().isEmpty()) {
            return;
        }
        if (!forceRebuild) {
            boolean hasJavaChanges = compareResponse.getFiles().stream()
                    .map(CompareResponse.FileDiff::getFilename)
                    .filter(Objects::nonNull)
                    .anyMatch(filename -> filename.endsWith(".java"));
            if (!hasJavaChanges) {
                return;
            }
        }
        String repoKey = normalizeRepositoryKey(repository);
        String currentFingerprint = buildDependencyGraphFingerprint(repository, compareResponse.getFiles());
        synchronized (dependencyGraphRefreshLock) {
            String previousFingerprint = dependencyGraphFingerprintByRepo.get(repoKey);
            if (!forceRebuild && Objects.equals(previousFingerprint, currentFingerprint)) {
                log.debug("依赖图状态未变化，跳过重建 repository={} fingerprint={}", repository, currentFingerprint);
                return;
            }
            try {
                IndexGraphRequest indexGraphRequest = buildGraphIndexRequest(repository);
                IndexGraphResponse response = postForObject("/api/code/graph/index", indexGraphRequest, IndexGraphResponse.class);
                if (forceRebuild) {
                    log.info("依赖图已强制重建 repository={} group={} project={} branch={} files={} nodes={} relations={} skipped={} fingerprint={}",
                            repository, response.getGroup(), response.getProject(), response.getBranch(),
                            response.getFiles(), response.getNodes(), response.getRelations(), response.getSkippedFiles(),
                            currentFingerprint);
                } else {
                    log.info("依赖图已重建 repository={} group={} project={} branch={} files={} nodes={} relations={} skipped={} fingerprint={}",
                            repository, response.getGroup(), response.getProject(), response.getBranch(),
                            response.getFiles(), response.getNodes(), response.getRelations(), response.getSkippedFiles(),
                            currentFingerprint);
                }
                dependencyGraphFingerprintByRepo.put(repoKey, currentFingerprint);
            } catch (Exception ex) {
                if (forceRebuild) {
                    log.error("评审前强制重建依赖图失败 repository={} url={}", repository, codeChunkBaseUrl, ex);
                    throw new IllegalStateException("评审前强制重建依赖图失败：" + ex.getMessage(), ex);
                }
                log.error("触发依赖图重建失败 repository={} url={}", repository, codeChunkBaseUrl, ex);
                throw new IllegalStateException("触发依赖图重建失败：" + ex.getMessage(), ex);
            }
        }
    }

    private String buildDependencyGraphFingerprint(String repository, List<CompareResponse.FileDiff> files) {
        Optional<Path> repoRoot = resolveRepositoryRoot(repository);
        if (repoRoot.isPresent()) {
            try {
                String fingerprint = buildJavaFileStatsFingerprint(repoRoot.get());
                log.debug("基于仓库文件状态生成依赖图指纹 repository={} repoRoot={} fingerprint={}",
                        repository, repoRoot.get(), fingerprint);
                return fingerprint;
            } catch (IOException ex) {
                log.warn("生成仓库文件状态指纹失败，降级使用 diff 指纹 repository={} repoRoot={}",
                        repository, repoRoot.get(), ex);
            }
        }
        String fallbackFingerprint = buildDiffFallbackFingerprint(files);
        log.debug("基于 diff 生成依赖图指纹 repository={} fingerprint={}", repository, fallbackFingerprint);
        return fallbackFingerprint;
    }

    private Optional<Path> resolveRepositoryRoot(String repository) {
        if (impactRepoRoot != null && !impactRepoRoot.isBlank()) {
            Path configured = Path.of(impactRepoRoot).toAbsolutePath().normalize();
            if (Files.isDirectory(configured)) {
                return Optional.of(configured);
            }
            log.warn("配置的 code-chunk.impact.repo-root 不存在或不可访问 path={}", configured);
        }

        String repositoryName = extractRepositoryName(repository);
        if (repositoryName.isBlank()) {
            return Optional.empty();
        }

        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        candidates.add(cwd);
        candidates.add(cwd.resolve(repositoryName));
        if (cwd.getParent() != null) {
            candidates.add(cwd.getParent().resolve(repositoryName));
            if (cwd.getParent().getParent() != null) {
                candidates.add(cwd.getParent().getParent().resolve(repositoryName));
            }
        }

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate) && candidate.getFileName() != null
                    && repositoryName.equals(candidate.getFileName().toString())) {
                return Optional.of(candidate);
            }
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate.resolve(repositoryName))) {
                return Optional.of(candidate.resolve(repositoryName));
            }
        }
        return Optional.empty();
    }

    private String buildJavaFileStatsFingerprint(Path repoRoot) throws IOException {
        MessageDigest digest = newSha256Digest();
        updateDigest(digest, "root=" + repoRoot.toString());

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(repoRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isFingerprintJavaFile)
                    .sorted(Comparator.comparing(path -> repoRoot.relativize(path).toString()))
                    .toList();
        }

        for (Path file : javaFiles) {
            Path relativePath = repoRoot.relativize(file);
            String normalizedPath = relativePath.toString().replace('\\', '/');
            updateDigest(digest, normalizedPath);
            updateDigest(digest, String.valueOf(Files.size(file)));
            updateDigest(digest, String.valueOf(Files.getLastModifiedTime(file).toMillis()));
        }
        updateDigest(digest, "javaFileCount=" + javaFiles.size());
        return toHex(digest.digest());
    }

    private boolean isFingerprintJavaFile(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!fileName.endsWith(".java")) {
            return false;
        }
        String normalizedPath = path.toString().replace('\\', '/');
        return FINGERPRINT_EXCLUDED_SEGMENTS.stream()
                .noneMatch(segment -> normalizedPath.contains("/" + segment + "/"));
    }

    private String buildDiffFallbackFingerprint(List<CompareResponse.FileDiff> files) {
        MessageDigest digest = newSha256Digest();
        if (files == null || files.isEmpty()) {
            updateDigest(digest, "NO_DIFF_FILES");
            return toHex(digest.digest());
        }

        files.stream()
                .filter(Objects::nonNull)
                .filter(file -> file.getFilename() != null && file.getFilename().endsWith(".java"))
                .sorted(Comparator.comparing(CompareResponse.FileDiff::getFilename))
                .forEach(file -> {
                    updateDigest(digest, "file=" + file.getFilename());
                    updateDigest(digest, "status=" + Objects.toString(file.getStatus(), ""));
                    updateDigest(digest, "additions=" + Objects.toString(file.getAdditions(), ""));
                    updateDigest(digest, "deletions=" + Objects.toString(file.getDeletions(), ""));
                    updateDigest(digest, "changes=" + Objects.toString(file.getChanges(), ""));
                    updateDigest(digest, "patchHash=" + Integer.toHexString(Objects.hashCode(file.getPatch())));
                });
        return toHex(digest.digest());
    }

    private String normalizeRepositoryKey(String repository) {
        if (repository == null || repository.isBlank()) {
            return "unknown";
        }
        return repository.trim().toLowerCase();
    }

    private IndexGraphRequest buildGraphIndexRequest(String repository) {
        if (repository == null || repository.isBlank()) {
            return new IndexGraphRequest(null, null, null);
        }

        String normalized = repository.trim();
        int slash = normalized.lastIndexOf('/');
        if (slash > 0 && slash + 1 < normalized.length()) {
            return new IndexGraphRequest(
                    normalized.substring(0, slash),
                    normalized.substring(slash + 1),
                    null
            );
        }
        return new IndexGraphRequest(null, normalized, null);
    }

    private String extractRepositoryName(String repository) {
        if (repository == null || repository.isBlank()) {
            return "";
        }
        String normalized = repository.trim();
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private ImpactChainAnalysis analyzeImpactChain(String repository, CompareResponse compareResponse) {
        log.debug("开始构建影响链路摘要 repository={}", repository);
        if (!impactEnabled) {
            log.info("影响链路分析未启用 repository={}", repository);
            return new ImpactChainAnalysis("影响链路分析未启用。", List.of());
        }
        if (compareResponse == null || compareResponse.getFiles() == null || compareResponse.getFiles().isEmpty()) {
            return new ImpactChainAnalysis("本次提交无可用 diff 文件。", List.of());
        }

        ChangedCodeContext changedContext = resolveChangedCodeContext(compareResponse.getFiles());
        List<String> changedTypeFqns = extractChangedJavaTypes(compareResponse.getFiles());
        List<String> changedFilePaths = compareResponse.getFiles().stream()
                .map(CompareResponse.FileDiff::getFilename)
                .filter(Objects::nonNull)
                .toList();
        List<EntryPointInfo> entryPoints = findRelatedEntryPoints(changedContext, changedTypeFqns, changedFilePaths);
        List<AffectedEntryPoint> affectedEntryPoints = entryPoints.stream()
                .map(this::toAffectedEntryPoint)
                .toList();

        StringBuilder summary = new StringBuilder("## 业务影响链路摘要\n");
        summary.append("- 仓库：").append(safe(repository)).append('\n');
        summary.append("- 变更方法数：").append(changedContext.methodFqns().size()).append('\n');
        summary.append("- 变更类型数：").append(changedTypeFqns.size()).append('\n');

        if (entryPoints.isEmpty()) {
            summary.append("- 未找到与变更直接相关的业务入口点\n");
            log.debug("未找到相关入口点 repository={}", repository);
            return new ImpactChainAnalysis(summary.toString(), List.of());
        }

        summary.append("- 关联入口点：").append(entryPoints.size()).append(" 个\n\n");
        for (EntryPointInfo entryPoint : entryPoints) {
            try {
                log.debug("查询影响链路 entryPointId={} type={}", entryPoint.getId(), entryPoint.getType());
                ImpactChainResult chainResult = fetchImpactChain(entryPoint.getId());

                summary.append("### 入口点：").append(entryPoint.getId()).append('\n');
                summary.append("- 类型：").append(entryPoint.getType()).append('\n');
                String route = formatRoute(entryPoint);
                if (!route.isBlank()) {
                    summary.append("- 路由：").append(route).append('\n');
                }
                summary.append("- 位置：").append(entryPoint.getClassName()).append('#').append(entryPoint.getMethodName()).append('\n');

                if (chainResult.getImpactChain().isEmpty()) {
                    summary.append("- 影响链路：未发现下游依赖\n");
                } else {
                    summary.append("- 影响链路：\n");
                    for (Map<String, Object> item : chainResult.getImpactChain()) {
                        summary.append("  - ").append(safe(item.get("name")))
                                .append(" (").append(safe(item.get("type"))).append(")")
                                .append(" hops=").append(safe(item.get("hops")))
                                .append('\n');
                    }
                }
                summary.append('\n');
            } catch (Exception ex) {
                log.warn("读取影响链路失败 entryPoint={} url={}", entryPoint.getId(), codeChunkBaseUrl, ex);
                summary.append("### ").append(entryPoint.getId()).append('\n')
                        .append("- 查询失败：").append(ex.getMessage()).append('\n');
            }
        }

        log.debug("影响链路摘要构建完成 repository={} entryPoints={}", repository, entryPoints.size());
        return new ImpactChainAnalysis(summary.toString(), affectedEntryPoints);
    }

    private List<EntryPointInfo> findRelatedEntryPoints(
            ChangedCodeContext changedContext,
            List<String> changedTypeFqns,
            List<String> changedFilePaths
    ) {
        try {
            List<EntryPointInfo> allEntryPoints = loadAllEntryPoints();
            if (allEntryPoints.isEmpty()) {
                log.warn("入口点列表为空，尝试触发 impact analyze 重建");
                triggerImpactAnalyze();
                allEntryPoints = loadAllEntryPoints();
                log.info("impact analyze 后入口点数量={}", allEntryPoints.size());
            }
            Map<String, EntryPointInfo> entryPointByMethod = new LinkedHashMap<>();
            Map<String, EntryPointInfo> entryPointByMethodNoArgs = new LinkedHashMap<>();
            Map<String, List<EntryPointInfo>> entryPointsByClass = new LinkedHashMap<>();
            for (EntryPointInfo entryPoint : allEntryPoints) {
                if (entryPoint.getMethodSignature() != null && !entryPoint.getMethodSignature().isBlank()) {
                    String normalizedMethodSignature = normalizeMethodSignature(entryPoint.getMethodSignature());
                    entryPointByMethod.put(normalizedMethodSignature, entryPoint);
                    entryPointByMethodNoArgs.put(stripMethodArgs(normalizedMethodSignature), entryPoint);
                }
                if (entryPoint.getClassName() != null && !entryPoint.getClassName().isBlank()) {
                    entryPointsByClass.computeIfAbsent(entryPoint.getClassName(), key -> new ArrayList<>())
                            .add(entryPoint);
                }
            }

            List<EntryPointInfo> tracedByCallGraph = traceEntryPointsByCallGraph(
                    changedContext.methodFqns(),
                    entryPointByMethod
            );
            if (!tracedByCallGraph.isEmpty()) {
                return tracedByCallGraph;
            }

            List<EntryPointInfo> tracedByTypeImpact = traceEntryPointsByTypeImpact(
                    changedTypeFqns,
                    entryPointByMethod,
                    entryPointByMethodNoArgs,
                    entryPointsByClass
            );
            if (!tracedByTypeImpact.isEmpty()) {
                log.debug("通过类型影响面兜底匹配到入口点 count={}", tracedByTypeImpact.size());
                return prioritizeStrictEntryPoints(tracedByTypeImpact);
            }

            List<EntryPointInfo> directTypeMatches = traceEntryPointsByDirectTypeMatch(changedTypeFqns, entryPointsByClass);
            if (!directTypeMatches.isEmpty()) {
                log.debug("通过类型直连兜底匹配到入口点 count={}", directTypeMatches.size());
                return prioritizeStrictEntryPoints(directTypeMatches);
            }

            List<EntryPointInfo> moduleAndKeywordMatches = traceEntryPointsByModuleAndKeywords(
                    allEntryPoints,
                    changedTypeFqns,
                    changedFilePaths
            );
            if (!moduleAndKeywordMatches.isEmpty()) {
                log.debug("通过模块/关键词兜底匹配到入口点 count={}", moduleAndKeywordMatches.size());
                return prioritizeStrictEntryPoints(moduleAndKeywordMatches);
            }

            if (changedContext.methodFqns().isEmpty()) {
                log.debug("未解析到变更方法，且兜底入口点匹配为空");
            }
            return List.of();
        } catch (Exception e) {
            log.warn("获取入口点列表失败", e);
            return List.of();
        }
    }

    private void triggerImpactAnalyze() {
        try {
            AnalysisResponse response = postForObject("/api/impact/analyze", Map.of(), AnalysisResponse.class);
            log.info("触发 impact analyze 完成 success={} message={}", response.isSuccess(), response.getMessage());
        } catch (Exception ex) {
            log.warn("触发 impact analyze 失败", ex);
        }
    }

    private List<EntryPointInfo> traceEntryPointsByTypeImpact(
            List<String> changedTypeFqns,
            Map<String, EntryPointInfo> entryPointByMethod,
            Map<String, EntryPointInfo> entryPointByMethodNoArgs,
            Map<String, List<EntryPointInfo>> entryPointsByClass
    ) {
        if (changedTypeFqns == null || changedTypeFqns.isEmpty()) {
            return List.of();
        }

        Set<EntryPointInfo> matched = new LinkedHashSet<>();
        for (String changedTypeFqn : changedTypeFqns) {
            if (changedTypeFqn == null || changedTypeFqn.isBlank()) {
                continue;
            }
            try {
                ImpactResponse impactResponse = fetchTypeImpact(changedTypeFqn);
                for (Map<String, Object> impact : impactResponse.getImpacts()) {
                    String impactedFqn = safe(impact.get("fqn"));
                    if (impactedFqn.isBlank() || "-".equals(impactedFqn)) {
                        continue;
                    }
                    collectMatchedEntryPoints(
                            impactedFqn,
                            entryPointByMethod,
                            entryPointByMethodNoArgs,
                            entryPointsByClass,
                            matched
                    );
                    if (matched.size() >= maxTypes) {
                        return new ArrayList<>(matched);
                    }
                }
            } catch (Exception ex) {
                log.debug("按类型影响面回溯入口点失败 type={}", changedTypeFqn, ex);
            }
        }
        return new ArrayList<>(matched);
    }

    private List<EntryPointInfo> traceEntryPointsByDirectTypeMatch(
            List<String> changedTypeFqns,
            Map<String, List<EntryPointInfo>> entryPointsByClass
    ) {
        if (changedTypeFqns == null || changedTypeFqns.isEmpty()) {
            return List.of();
        }
        Set<EntryPointInfo> matched = new LinkedHashSet<>();
        for (String changedTypeFqn : changedTypeFqns) {
            if (changedTypeFqn == null || changedTypeFqn.isBlank()) {
                continue;
            }
            List<EntryPointInfo> entryPoints = entryPointsByClass.get(changedTypeFqn);
            if (entryPoints == null || entryPoints.isEmpty()) {
                continue;
            }
            matched.addAll(entryPoints);
            if (matched.size() >= maxTypes) {
                break;
            }
        }
        return new ArrayList<>(matched);
    }

    private List<EntryPointInfo> traceEntryPointsByModuleAndKeywords(
            List<EntryPointInfo> allEntryPoints,
            List<String> changedTypeFqns,
            List<String> changedFilePaths
    ) {
        if (allEntryPoints == null || allEntryPoints.isEmpty()) {
            return List.of();
        }

        Set<String> changedModules = extractChangedModules(changedFilePaths);
        List<String> tokens = extractMatchingTokens(changedTypeFqns, changedFilePaths);
        Set<EntryPointInfo> matched = new LinkedHashSet<>();

        for (EntryPointInfo entryPoint : allEntryPoints) {
            boolean sameModule = !changedModules.isEmpty()
                    && changedModules.contains(normalize(entryPoint.getModule()));
            int keywordScore = calculateKeywordScore(entryPoint, tokens);
            if (sameModule && keywordScore > 0) {
                matched.add(entryPoint);
            }
        }
        if (!matched.isEmpty()) {
            return new ArrayList<>(matched);
        }

        for (EntryPointInfo entryPoint : allEntryPoints) {
            boolean sameModule = !changedModules.isEmpty()
                    && changedModules.contains(normalize(entryPoint.getModule()));
            if (sameModule && hasRoute(entryPoint)) {
                matched.add(entryPoint);
                if (matched.size() >= maxTypes) {
                    break;
                }
            }
        }
        if (!matched.isEmpty()) {
            return new ArrayList<>(matched);
        }

        for (EntryPointInfo entryPoint : allEntryPoints) {
            int keywordScore = calculateKeywordScore(entryPoint, tokens);
            if (keywordScore > 1 && hasRoute(entryPoint)) {
                matched.add(entryPoint);
                if (matched.size() >= maxTypes) {
                    break;
                }
            }
        }
        return new ArrayList<>(matched);
    }

    private Set<String> extractChangedModules(List<String> changedFilePaths) {
        if (changedFilePaths == null || changedFilePaths.isEmpty()) {
            return Set.of();
        }
        Set<String> modules = new LinkedHashSet<>();
        for (String filePath : changedFilePaths) {
            if (filePath == null || filePath.isBlank()) {
                continue;
            }
            String normalizedPath = filePath.replace('\\', '/');
            int split = normalizedPath.indexOf('/');
            if (split <= 0) {
                continue;
            }
            modules.add(normalize(normalizedPath.substring(0, split)));
        }
        return modules;
    }

    private List<String> extractMatchingTokens(List<String> changedTypeFqns, List<String> changedFilePaths) {
        Set<String> tokens = new LinkedHashSet<>();
        if (changedTypeFqns != null) {
            for (String changedTypeFqn : changedTypeFqns) {
                tokens.addAll(tokenize(changedTypeFqn));
            }
        }
        if (changedFilePaths != null) {
            for (String changedFilePath : changedFilePaths) {
                tokens.addAll(tokenize(changedFilePath));
            }
        }
        return new ArrayList<>(tokens);
    }

    private int calculateKeywordScore(EntryPointInfo entryPoint, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        String searchable = normalize(String.join(" ",
                safe(entryPoint.getId()),
                safe(entryPoint.getClassName()),
                safe(entryPoint.getMethodName()),
                safe(entryPoint.getMethodSignature()),
                safe(entryPoint.getFilePath()),
                safe(entryPoint.getModule()),
                safe(entryPoint.getMetadata())));

        int score = 0;
        for (String token : tokens) {
            if (token.length() < 3) {
                continue;
            }
            if (searchable.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase();
        String[] segments = normalized.split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>();
        for (String segment : segments) {
            if (segment.length() >= 3) {
                tokens.add(segment);
            }
        }
        return tokens;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase();
    }

    private void collectMatchedEntryPoints(
            String impactedFqn,
            Map<String, EntryPointInfo> entryPointByMethod,
            Map<String, EntryPointInfo> entryPointByMethodNoArgs,
            Map<String, List<EntryPointInfo>> entryPointsByClass,
            Set<EntryPointInfo> matched
    ) {
        String normalizedImpactedFqn = normalizeMethodSignature(impactedFqn);
        EntryPointInfo exactMethodMatch = entryPointByMethod.get(normalizedImpactedFqn);
        if (exactMethodMatch != null) {
            matched.add(exactMethodMatch);
        }

        EntryPointInfo normalizedMethodMatch = entryPointByMethodNoArgs.get(stripMethodArgs(normalizedImpactedFqn));
        if (normalizedMethodMatch != null) {
            matched.add(normalizedMethodMatch);
        }

        String impactedClassName = extractClassName(normalizedImpactedFqn);
        if (impactedClassName.isBlank()) {
            return;
        }
        List<EntryPointInfo> classMatches = entryPointsByClass.get(impactedClassName);
        if (classMatches != null && !classMatches.isEmpty()) {
            matched.addAll(classMatches);
        }
    }

    private String stripMethodArgs(String methodFqn) {
        if (methodFqn == null || methodFqn.isBlank()) {
            return "";
        }
        int leftBracket = methodFqn.indexOf('(');
        if (leftBracket < 0) {
            return methodFqn;
        }
        return methodFqn.substring(0, leftBracket);
    }

    private String extractClassName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int split = fqn.indexOf('#');
        if (split > 0) {
            return fqn.substring(0, split);
        }
        return fqn;
    }

    private String normalizeMethodSignature(String methodSignature) {
        if (methodSignature == null || methodSignature.isBlank()) {
            return "";
        }
        String normalized = methodSignature.trim().replaceAll("\\s+", "");
        if (normalized.indexOf('#') >= 0) {
            return normalized;
        }
        int leftBracket = normalized.indexOf('(');
        if (leftBracket < 0) {
            return normalized;
        }
        int separator = normalized.lastIndexOf('.', leftBracket);
        if (separator > 0) {
            return normalized.substring(0, separator) + "#" + normalized.substring(separator + 1);
        }
        return normalized;
    }

    private List<Map<String, Object>> fetchMethodImpactWithCompatibility(
            String methodFqn,
            Map<String, List<String>> ownerAliasCache
    ) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seenCallerMethods = new LinkedHashSet<>();
        for (String candidateMethodFqn : buildMethodImpactCandidates(methodFqn, ownerAliasCache)) {
            try {
                ImpactResponse impactResponse = fetchMethodImpact(candidateMethodFqn);
                for (Map<String, Object> impact : impactResponse.getImpacts()) {
                    String callerMethod = normalizeMethodSignature(safe(impact.get("fqn")));
                    if (callerMethod.isBlank() || "-".equals(callerMethod) || !seenCallerMethods.add(callerMethod)) {
                        continue;
                    }
                    merged.add(impact);
                }
            } catch (Exception ex) {
                log.debug("按兼容签名查询方法影响失败 method={} candidate={}", methodFqn, candidateMethodFqn, ex);
            }
        }
        return merged;
    }

    private List<String> buildMethodImpactCandidates(
            String methodFqn,
            Map<String, List<String>> ownerAliasCache
    ) {
        Set<String> candidates = new LinkedHashSet<>();
        if (methodFqn != null && !methodFqn.isBlank()) {
            candidates.add(methodFqn);
        }

        String normalized = normalizeMethodSignature(methodFqn);
        if (normalized.isBlank()) {
            return new ArrayList<>(candidates);
        }

        candidates.add(normalized);
        String dotStyle = toDotStyleMethodSignature(normalized);
        if (!dotStyle.isBlank()) {
            candidates.add(dotStyle);
        }

        int separator = normalized.indexOf('#');
        if (separator > 0) {
            String ownerType = normalized.substring(0, separator);
            String methodSuffix = normalized.substring(separator);
            for (String aliasOwner : resolveRelatedOwners(ownerType, ownerAliasCache)) {
                String aliasSignature = aliasOwner + methodSuffix;
                candidates.add(aliasSignature);
                String aliasDotStyle = toDotStyleMethodSignature(aliasSignature);
                if (!aliasDotStyle.isBlank()) {
                    candidates.add(aliasDotStyle);
                }
            }
        }
        return new ArrayList<>(candidates);
    }

    private String toDotStyleMethodSignature(String methodSignature) {
        if (methodSignature == null || methodSignature.isBlank()) {
            return "";
        }
        int separator = methodSignature.indexOf('#');
        if (separator <= 0) {
            return methodSignature;
        }
        return methodSignature.substring(0, separator) + "." + methodSignature.substring(separator + 1);
    }

    private List<String> resolveRelatedOwners(
            String ownerType,
            Map<String, List<String>> ownerAliasCache
    ) {
        if (ownerType == null || ownerType.isBlank()) {
            return List.of();
        }
        return ownerAliasCache.computeIfAbsent(ownerType, this::loadRelatedOwners);
    }

    private List<String> loadRelatedOwners(String ownerType) {
        Set<String> relatedOwners = new LinkedHashSet<>();
        try {
            DependencyResponse dependencies = fetchTypeDependencies(ownerType);
            for (Map<String, Object> dependency : dependencies.getDependencies()) {
                if (!containsTypeHierarchyRelation(dependency.get("relationTypes"))) {
                    continue;
                }
                String fqn = safe(dependency.get("fqn"));
                if (!fqn.isBlank() && !"-".equals(fqn)) {
                    relatedOwners.add(fqn);
                }
            }
        } catch (Exception ex) {
            log.debug("读取类型依赖用于方法别名回溯失败 type={}", ownerType, ex);
        }

        try {
            ImpactResponse impacts = fetchTypeImpact(ownerType);
            for (Map<String, Object> impact : impacts.getImpacts()) {
                if (!containsTypeHierarchyRelation(impact.get("relationTypes"))) {
                    continue;
                }
                String fqn = safe(impact.get("fqn"));
                if (!fqn.isBlank() && !"-".equals(fqn)) {
                    relatedOwners.add(fqn);
                }
            }
        } catch (Exception ex) {
            log.debug("读取类型影响用于方法别名回溯失败 type={}", ownerType, ex);
        }

        relatedOwners.remove(ownerType);
        if (relatedOwners.isEmpty()) {
            return List.of();
        }
        return relatedOwners.stream().limit(maxTypes).toList();
    }

    private boolean containsTypeHierarchyRelation(Object relationTypes) {
        if (!(relationTypes instanceof List<?> types) || types.isEmpty()) {
            return false;
        }
        for (Object relationType : types) {
            String normalized = safe(relationType).toUpperCase();
            if ("IMPLEMENTS".equals(normalized) || "EXTENDS".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private List<EntryPointInfo> traceEntryPointsByCallGraph(
            List<String> changedMethods,
            Map<String, EntryPointInfo> entryPointByMethod
    ) {
        Set<EntryPointInfo> matched = new LinkedHashSet<>();
        Set<String> visitedMethods = new LinkedHashSet<>();
        ArrayDeque<MethodTraceNode> queue = new ArrayDeque<>();
        Map<String, List<String>> ownerAliasCache = new LinkedHashMap<>();

        for (String methodFqn : changedMethods) {
            if (methodFqn == null || methodFqn.isBlank()) {
                continue;
            }
            String normalizedMethodFqn = normalizeMethodSignature(methodFqn);
            EntryPointInfo directEntryPoint = entryPointByMethod.get(normalizedMethodFqn);
            if (directEntryPoint != null) {
                matched.add(directEntryPoint);
            }
            if (visitedMethods.add(normalizedMethodFqn)) {
                queue.addLast(new MethodTraceNode(methodFqn, 0));
            }
        }

        while (!queue.isEmpty() && matched.size() < maxTypes) {
            MethodTraceNode current = queue.removeFirst();
            if (current.depth() >= impactDepth) {
                continue;
            }

            List<Map<String, Object>> impacts = fetchMethodImpactWithCompatibility(
                    current.methodFqn(),
                    ownerAliasCache
            );
            for (Map<String, Object> impact : impacts) {
                String callerMethodRaw = safe(impact.get("fqn"));
                String callerMethodNormalized = normalizeMethodSignature(callerMethodRaw);
                if (callerMethodNormalized.isBlank() || "-".equals(callerMethodNormalized)) {
                    continue;
                }

                EntryPointInfo callerEntryPoint = entryPointByMethod.get(callerMethodNormalized);
                if (callerEntryPoint != null) {
                    matched.add(callerEntryPoint);
                    if (matched.size() >= maxTypes) {
                        break;
                    }
                }

                if (visitedMethods.add(callerMethodNormalized)) {
                    queue.addLast(new MethodTraceNode(callerMethodRaw, current.depth() + 1));
                }
            }
        }

        return prioritizeStrictEntryPoints(new ArrayList<>(matched));
    }

    private List<EntryPointInfo> prioritizeStrictEntryPoints(List<EntryPointInfo> entryPoints) {
        if (entryPoints.isEmpty()) {
            return List.of();
        }

        entryPoints.sort((left, right) -> {
            int typeCompare = Integer.compare(entryPointPriority(left), entryPointPriority(right));
            if (typeCompare != 0) {
                return typeCompare;
            }
            int routeCompare = Boolean.compare(hasRoute(right), hasRoute(left));
            if (routeCompare != 0) {
                return routeCompare;
            }
            return left.getId().compareTo(right.getId());
        });

        boolean hasHttp = entryPoints.stream().anyMatch(entryPoint -> "HTTP".equalsIgnoreCase(entryPoint.getType()));
        return entryPoints.stream()
                .filter(entryPoint -> !hasHttp || "HTTP".equalsIgnoreCase(entryPoint.getType()))
                .limit(maxTypes)
                .toList();
    }

    private int entryPointPriority(EntryPointInfo entryPoint) {
        String type = safe(entryPoint.getType()).toUpperCase();
        return switch (type) {
            case "HTTP" -> 0;
            case "RPC" -> 1;
            case "MQ" -> 2;
            case "SCHEDULED" -> 3;
            case "EVENT" -> 4;
            default -> 9;
        };
    }

    private boolean hasRoute(EntryPointInfo entryPoint) {
        return !formatRoute(entryPoint).isBlank();
    }

    private List<EntryPointInfo> loadAllEntryPoints() {
        EntryPointListResponse entryPointResponse = fetchAllEntryPoints();
        return entryPointResponse.getEntryPoints().stream()
                .map(this::toEntryPointInfo)
                .toList();
    }

    private EntryPointInfo toEntryPointInfo(Map<String, Object> entryPoint) {
        EntryPointInfo info = new EntryPointInfo();
        info.setId(safe(entryPoint.get("id")));
        info.setType(safe(entryPoint.get("type")));
        info.setClassName(safe(entryPoint.get("className")));
        info.setMethodName(safe(entryPoint.get("methodName")));
        info.setMethodSignature(safe(entryPoint.get("methodSignature")));
        info.setFilePath(safe(entryPoint.get("filePath")));
        info.setModule(safe(entryPoint.get("module")));
        info.setMetadata(safe(entryPoint.get("metadata")));
        return info;
    }

    private AffectedEntryPoint toAffectedEntryPoint(EntryPointInfo entryPoint) {
        Map<String, String> metadata = parseMetadata(entryPoint.getMetadata());
        return new AffectedEntryPoint(
                entryPoint.getId(),
                entryPoint.getType(),
                metadata.getOrDefault("path", ""),
                metadata.getOrDefault("httpMethod", ""),
                entryPoint.getClassName(),
                entryPoint.getMethodName(),
                entryPoint.getMethodSignature()
        );
    }

    private DependencyResponse fetchTypeDependencies(String fqn) {
        return postForObject("/api/code/type-dependencies", new GraphQueryRequest(fqn, impactDepth), DependencyResponse.class);
    }

    private ImpactResponse fetchTypeImpact(String fqn) {
        return postForObject("/api/code/type-impact", new GraphQueryRequest(fqn, impactDepth), ImpactResponse.class);
    }

    private ImpactResponse fetchMethodImpact(String methodFqn) {
        return postForObject("/api/code/impact", new ImpactQueryRequest(methodFqn, impactDepth), ImpactResponse.class);
    }

    private LocationResponse fetchCodeLocation(String filePath, int line) {
        return postForObject("/api/code/location", new CodeLocationRequest(filePath, line), LocationResponse.class);
    }

    private ImpactChainResult fetchImpactChain(String entryPointId) {
        return postForObject("/api/impact/chain", new ImpactChainQuery(entryPointId, impactDepth), ImpactChainResult.class);
    }

    private EntryPointListResponse fetchAllEntryPoints() {
        return getForObject("/api/impact/entry-points", EntryPointListResponse.class);
    }

    private <T> T postForObject(String path, Object request, Class<T> responseType) {
        String url = codeChunkBaseUrl + path;
        log.debug("HTTP POST 请求 url={}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            RestTemplate client = resolvePostClient(path);
            T response = client.postForObject(url, new HttpEntity<>(request, headers), responseType);
            if (response == null) {
                log.warn("依赖图服务返回空响应 path={}", path);
                throw new IllegalStateException("依赖图服务返回空响应：" + path);
            }
            log.debug("HTTP POST 响应成功 path={}", path);
            return response;
        } catch (Exception e) {
            log.error("HTTP POST 请求失败 url={} path={}", url, path, e);
            throw e;
        }
    }

    private RestTemplate resolvePostClient(String path) {
        if ("/api/code/graph/index".equals(path) && graphIndexRestTemplate != null) {
            return graphIndexRestTemplate;
        }
        return restTemplate;
    }

    private <T> T getForObject(String path, Class<T> responseType) {
        String url = codeChunkBaseUrl + path;
        log.debug("HTTP GET 请求 url={}", url);

        try {
            T response = restTemplate.getForObject(url, responseType);
            log.debug("HTTP GET 响应成功 path={}", path);
            return response;
        } catch (Exception e) {
            log.error("HTTP GET 请求失败 url={} path={}", url, path, e);
            throw e;
        }
    }

    private void appendItems(StringBuilder summary, String title, List<Map<String, Object>> items) {
        summary.append("- ").append(title).append("：");
        if (items.isEmpty()) {
            summary.append("无\n");
            return;
        }
        summary.append('\n');
        for (Map<String, Object> item : items) {
            summary.append("  - ")
                    .append(safe(item.get("fqn")))
                    .append(" [").append(safe(item.get("kind"))).append("]")
                    .append(" hops=").append(safe(item.get("hops")))
                    .append(" relations=").append(formatRelationTypes(item.get("relationTypes")))
                    .append('\n');
        }
    }

    private String formatRelationTypes(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).distinct().reduce((left, right) -> left + " -> " + right).orElse("-");
        }
        return "-";
    }

    private ChangedCodeContext resolveChangedCodeContext(List<CompareResponse.FileDiff> files) {
        Set<String> changedMethods = new LinkedHashSet<>();

        for (CompareResponse.FileDiff file : files) {
            if (file.getFilename() == null || !file.getFilename().endsWith(".java")) {
                continue;
            }
            for (Integer line : extractChangedLineNumbers(file.getPatch())) {
                try {
                    LocationResponse response = fetchCodeLocation(file.getFilename(), line);
                    for (Map<String, Object> node : response.getNodes()) {
                        String nodeType = safe(node.get("type"));
                        String fqn = safe(node.get("fqn"));
                        if ("METHOD".equals(nodeType) && !fqn.isBlank() && !"-".equals(fqn)) {
                            changedMethods.add(fqn);
                        }
                    }
                } catch (Exception ex) {
                    log.debug("按文件行解析变更节点失败 file={} line={}", file.getFilename(), line, ex);
                }
            }
        }

        return new ChangedCodeContext(new ArrayList<>(changedMethods));
    }

    private List<Integer> extractChangedLineNumbers(String patch) {
        if (patch == null || patch.isBlank()) {
            return List.of();
        }

        Set<Integer> changedLines = new LinkedHashSet<>();
        String[] lines = patch.split("\\R");
        int currentNewLine = -1;
        for (String line : lines) {
            Matcher matcher = DIFF_HUNK_PATTERN.matcher(line);
            if (matcher.find()) {
                currentNewLine = Integer.parseInt(matcher.group(1));
                int newCount = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
                if (newCount == 0) {
                    changedLines.add(Math.max(1, currentNewLine));
                }
                continue;
            }

            if (currentNewLine < 0) {
                continue;
            }

            if (line.startsWith("+++")) {
                continue;
            }
            if (line.startsWith("+")) {
                changedLines.add(Math.max(1, currentNewLine));
                currentNewLine++;
                continue;
            }
            if (line.startsWith("-")) {
                continue;
            }
            currentNewLine++;
        }

        return new ArrayList<>(changedLines);
    }

    private List<String> extractChangedJavaTypes(List<CompareResponse.FileDiff> files) {
        Set<String> types = new LinkedHashSet<>();
        for (CompareResponse.FileDiff file : files) {
            inferJavaTypeFqn(file.getFilename()).ifPresent(types::add);
            if (types.size() >= maxTypes) {
                break;
            }
        }
        return new ArrayList<>(types);
    }

    private Optional<String> inferJavaTypeFqn(String filename) {
        if (filename == null || !filename.endsWith(".java")) {
            return Optional.empty();
        }

        String normalized = filename.replace('\\', '/');
        String marker = normalized.contains("/src/main/java/") ? "/src/main/java/" : "/src/test/java/";
        int index = normalized.indexOf(marker);
        if (index < 0) {
            return Optional.empty();
        }

        String typePath = normalized.substring(index + marker.length(), normalized.length() - ".java".length());
        if (typePath.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(typePath.replace('/', '.'));
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item.get("filePath") != null)
                .limit(maxItemsPerType)
                .toList();
    }

    private String safe(Object value) {
        return Objects.toString(value, "-");
    }

    private String formatRoute(EntryPointInfo entryPoint) {
        if (!"HTTP".equalsIgnoreCase(entryPoint.getType())) {
            return "";
        }
        Map<String, String> metadata = parseMetadata(entryPoint.getMetadata());
        String path = metadata.getOrDefault("path", "");
        String httpMethod = metadata.getOrDefault("httpMethod", "");
        if (!httpMethod.isBlank() && !path.isBlank()) {
            return httpMethod + " " + path;
        }
        return path;
    }

    private Map<String, String> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank() || "-".equals(metadata)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String token : metadata.split(";")) {
            if (token.isBlank()) {
                continue;
            }
            int index = token.indexOf('=');
            if (index < 0) {
                result.put(token, "");
                continue;
            }
            result.put(token.substring(0, index), token.substring(index + 1));
        }
        return result;
    }

    // ==================== Records ====================

    private record GraphQueryRequest(
            String fqn,
            Integer depth
    ) {
    }

    private record ImpactChainQuery(
            String entryPointId,
            Integer depth
    ) {
    }

    private record ImpactQueryRequest(
            String methodFqn,
            Integer depth
    ) {
    }

    private record CodeLocationRequest(
            String filePath,
            Integer line
    ) {
    }

    private record ChangedCodeContext(List<String> methodFqns) {
    }

    private record MethodTraceNode(String methodFqn, int depth) {
    }

    private record IndexGraphRequest(
            String group,
            String project,
            String branch
    ) {
    }

    @Data
    public static class DependencyResponse {
        private boolean success;
        private String target;
        private Integer count;
        private List<Map<String, Object>> dependencies = List.of();
    }

    @Data
    public static class ImpactResponse {
        private boolean success;
        private String target;
        private Integer count;
        private List<Map<String, Object>> impacts = List.of();
    }

    @Data
    public static class ImpactChainResult {
        private boolean success;
        private String entryPointId;
        private String entryPointType;
        private Integer depth;
        private Integer count;
        private List<Map<String, Object>> impactChain = List.of();
        public List<Map<String, Object>> chain() {
            return impactChain;
        }
    }

    @Data
    public static class EntryPointListResponse {
        private boolean success;
        private Integer count;
        private List<Map<String, Object>> entryPoints = List.of();
    }

    @Data
    public static class LocationResponse {
        private boolean success;
        private String filePath;
        private Integer line;
        private Integer count;
        private List<Map<String, Object>> nodes = List.of();
    }

    @Data
    public static class AnalysisResponse {
        private boolean success;
        private String message;
    }

    @Data
    public static class IndexGraphResponse {
        private boolean success;
        private Integer files;
        private Integer nodes;
        private Integer relations;
        private Integer skippedFiles;
        private String group;
        private String project;
        private String branch;
    }

    @Data
    public static class EntryPointInfo {
        private String id;
        private String type;
        private String className;
        private String methodName;
        private String methodSignature;
        private String filePath;
        private String module;
        private String metadata;
    }

    public record ImpactAnalysisResult(
            String summary,
            List<AffectedEntryPoint> affectedEntryPoints
    ) {
    }

    private record ImpactChainAnalysis(
            String summary,
            List<AffectedEntryPoint> affectedEntryPoints
    ) {
    }
}
