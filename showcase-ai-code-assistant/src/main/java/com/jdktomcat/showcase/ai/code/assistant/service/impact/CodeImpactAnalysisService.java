package com.jdktomcat.showcase.ai.code.assistant.service.impact;

import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeImpactAnalysisService {

    private static final Pattern DIFF_HUNK_PATTERN = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final RestTemplate restTemplate;

    @Value("${code-chunk.base-url:http://localhost:8081}")
    private String codeChunkBaseUrl;

    @Value("${code-chunk.impact.enabled:true}")
    private boolean impactEnabled;

    @Value("${code-chunk.impact.depth:3}")
    private int impactDepth;

    @Value("${code-chunk.impact.max-types:10}")
    private int maxTypes;

    @Value("${code-chunk.impact.max-items-per-type:10}")
    private int maxItemsPerType;

    /**
     * 构建依赖图影响面摘要（使用代码依赖图 API）
     */
    public String buildGraphImpactSummary(String repository, CompareResponse compareResponse) {
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

    /**
     * 构建影响链路摘要（使用 Impact Chain API）
     */
    public String buildImpactChainSummary(String repository, CompareResponse compareResponse) {
        return analyzeImpactChain(repository, compareResponse).summary();
    }

    /**
     * 构建完整的影响面评估报告（结合依赖图和影响链路）
     */
    public String buildFullImpactSummary(String repository, CompareResponse compareResponse) {
        return analyzeImpact(repository, compareResponse).summary();
    }

    public ImpactAnalysisResult analyzeImpact(String repository, CompareResponse compareResponse) {
        log.info("开始构建完整影响面评估报告 repository={}", repository);
        ImpactChainAnalysis impactChainAnalysis = analyzeImpactChain(repository, compareResponse);
        StringBuilder report = new StringBuilder();
        report.append("# 代码变更影响面评估报告\n\n");
        report.append("## 变更概览\n");
        report.append("- 仓库：").append(safe(repository)).append('\n');
        report.append("- 变更文件数：").append(compareResponse != null && compareResponse.getFiles() != null ? compareResponse.getFiles().size() : 0).append("\n\n");

        // 1. 代码依赖图影响面
        log.debug("构建代码依赖图影响面 repository={}", repository);
        report.append(buildGraphImpactSummary(repository, compareResponse));
        report.append("\n\n---\n\n");

        // 2. 业务影响链路
        log.debug("构建业务影响链路 repository={}", repository);
        report.append(impactChainAnalysis.summary());

        log.info("完整影响面评估报告构建完成 repository={} report 长度={}", repository, report.length());
        return new ImpactAnalysisResult(report.toString(), impactChainAnalysis.affectedEntryPoints());
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
        if (changedContext.typeFqns().isEmpty() && changedContext.methodFqns().isEmpty()) {
            return new ImpactChainAnalysis("未包含可映射到 Java 类型的变更。", List.of());
        }

        List<EntryPointInfo> entryPoints = findRelatedEntryPoints(changedContext);
        List<AffectedEntryPoint> affectedEntryPoints = entryPoints.stream()
                .map(this::toAffectedEntryPoint)
                .toList();

        StringBuilder summary = new StringBuilder("## 业务影响链路摘要\n");
        summary.append("- 仓库：").append(safe(repository)).append('\n');
        summary.append("- 变更类型数：").append(changedContext.typeFqns().size()).append('\n');
        summary.append("- 变更方法数：").append(changedContext.methodFqns().size()).append('\n');

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

    private List<EntryPointInfo> findRelatedEntryPoints(ChangedCodeContext changedContext) {
        try {
            List<EntryPointInfo> allEntryPoints = loadAllEntryPoints();
            Map<String, EntryPointInfo> entryPointByMethod = new LinkedHashMap<>();
            for (EntryPointInfo entryPoint : allEntryPoints) {
                if (entryPoint.getMethodSignature() != null && !entryPoint.getMethodSignature().isBlank()) {
                    entryPointByMethod.put(entryPoint.getMethodSignature(), entryPoint);
                }
            }

            Set<EntryPointInfo> matched = new LinkedHashSet<>();
            for (String methodFqn : changedContext.methodFqns()) {
                EntryPointInfo directEntryPoint = entryPointByMethod.get(methodFqn);
                if (directEntryPoint != null) {
                    matched.add(directEntryPoint);
                }

                ImpactResponse impactResponse = fetchMethodImpact(methodFqn);
                for (Map<String, Object> impact : impactResponse.getImpacts()) {
                    String callerMethod = safe(impact.get("fqn"));
                    EntryPointInfo callerEntryPoint = entryPointByMethod.get(callerMethod);
                    if (callerEntryPoint != null) {
                        matched.add(callerEntryPoint);
                    }
                    if (matched.size() >= maxTypes) {
                        return new ArrayList<>(matched);
                    }
                }
            }

            Set<String> relatedTypes = new LinkedHashSet<>(changedContext.typeFqns());
            for (String typeFqn : changedContext.typeFqns()) {
                ImpactResponse impactResponse = fetchTypeImpact(typeFqn);
                for (Map<String, Object> impact : impactResponse.getImpacts()) {
                    relatedTypes.add(safe(impact.get("fqn")));
                    if (relatedTypes.size() >= maxTypes * 2) {
                        break;
                    }
                }
            }

            for (String typeFqn : relatedTypes) {
                for (EntryPointInfo entryPoint : allEntryPoints) {
                    if (entryPoint.getClassName().equals(typeFqn) ||
                        entryPoint.getClassName().startsWith(typeFqn + "$") ||
                        typeFqn.contains(entryPoint.getClassName())) {
                        matched.add(entryPoint);
                    }
                }
                if (matched.size() >= maxTypes) {
                    break;
                }
            }

            if (matched.size() < maxTypes) {
                matched.addAll(matchEntryPointsByImpactChain(allEntryPoints, changedContext, matched));
            }

            List<EntryPointInfo> prioritized = prioritizeEntryPoints(new ArrayList<>(matched));
            if (!prioritized.isEmpty()) {
                return prioritized;
            }

            return heuristicEntryPointMatch(allEntryPoints, changedContext);
        } catch (Exception e) {
            log.warn("获取入口点列表失败", e);
            return List.of();
        }
    }

    private List<EntryPointInfo> matchEntryPointsByImpactChain(
            List<EntryPointInfo> allEntryPoints,
            ChangedCodeContext changedContext,
            Set<EntryPointInfo> alreadyMatched
    ) {
        List<EntryPointInfo> matched = new ArrayList<>();
        for (EntryPointInfo entryPoint : allEntryPoints) {
            if (alreadyMatched.contains(entryPoint)) {
                continue;
            }
            try {
                ImpactChainResult chainResult = fetchImpactChain(entryPoint.getId());
                if (matchesChangedContext(entryPoint, chainResult, changedContext)) {
                    matched.add(entryPoint);
                }
            } catch (Exception ex) {
                log.debug("按影响链路回溯入口点失败 entryPointId={}", entryPoint.getId(), ex);
            }
            if (alreadyMatched.size() + matched.size() >= maxTypes * 2) {
                break;
            }
        }
        return matched;
    }

    private boolean matchesChangedContext(
            EntryPointInfo entryPoint,
            ImpactChainResult chainResult,
            ChangedCodeContext changedContext
    ) {
        if (entryPoint.getMethodSignature() != null && changedContext.methodFqns().contains(entryPoint.getMethodSignature())) {
            return true;
        }
        if (entryPoint.getClassName() != null && changedContext.typeFqns().contains(entryPoint.getClassName())) {
            return true;
        }

        for (Map<String, Object> node : chainResult.getImpactChain()) {
            String fqn = safe(node.get("fqn"));
            String filePath = safe(node.get("filePath"));
            if (changedContext.methodFqns().contains(fqn)
                    || changedContext.typeFqns().contains(fqn)
                    || changedContext.filePaths().contains(filePath)
                    || changedContext.typeFqns().stream().anyMatch(type -> fqn.startsWith(type + "#"))) {
                return true;
            }
        }
        return false;
    }

    private List<EntryPointInfo> prioritizeEntryPoints(List<EntryPointInfo> entryPoints) {
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

    private List<EntryPointInfo> heuristicEntryPointMatch(
            List<EntryPointInfo> allEntryPoints,
            ChangedCodeContext changedContext
    ) {
        Set<String> changedTokens = buildChangedContextTokens(changedContext);
        if (changedTokens.isEmpty()) {
            return List.of();
        }

        return allEntryPoints.stream()
                .map(entryPoint -> Map.entry(entryPoint, scoreEntryPoint(entryPoint, changedTokens)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<EntryPointInfo, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entryPointPriority(entry.getKey()))
                        .thenComparing(entry -> entry.getKey().getId()))
                .map(Map.Entry::getKey)
                .filter(entryPoint -> "HTTP".equalsIgnoreCase(entryPoint.getType()))
                .limit(maxTypes)
                .toList();
    }

    private Set<String> buildChangedContextTokens(ChangedCodeContext changedContext) {
        Set<String> tokens = new LinkedHashSet<>();
        changedContext.typeFqns().forEach(value -> tokens.addAll(tokenize(value)));
        changedContext.methodFqns().forEach(value -> tokens.addAll(tokenize(value)));
        changedContext.filePaths().forEach(value -> tokens.addAll(tokenize(value)));
        return tokens;
    }

    private int scoreEntryPoint(EntryPointInfo entryPoint, Set<String> changedTokens) {
        Set<String> entryPointTokens = new LinkedHashSet<>();
        entryPointTokens.addAll(tokenize(entryPoint.getClassName()));
        entryPointTokens.addAll(tokenize(entryPoint.getMethodName()));
        entryPointTokens.addAll(tokenize(entryPoint.getMethodSignature()));
        entryPointTokens.addAll(tokenize(formatRoute(entryPoint)));

        int score = 0;
        for (String token : entryPointTokens) {
            if (changedTokens.contains(token)) {
                score += switch (token) {
                    case "create", "update", "cancel", "query", "order", "pay", "refund" -> 3;
                    default -> 1;
                };
            }
        }

        if ("HTTP".equalsIgnoreCase(entryPoint.getType()) && hasRoute(entryPoint)) {
            score += 2;
        }
        return score;
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = value
                .replace('#', ' ')
                .replace('/', ' ')
                .replace('.', ' ')
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        return Pattern.compile("\\s+")
                .splitAsStream(normalized)
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() > 1)
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
            T response = restTemplate.postForObject(url, new HttpEntity<>(request, headers), responseType);
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
        Set<String> changedTypes = new LinkedHashSet<>(extractChangedJavaTypes(files));
        Set<String> changedMethods = new LinkedHashSet<>();
        Set<String> changedFilePaths = new LinkedHashSet<>();

        for (CompareResponse.FileDiff file : files) {
            if (file.getFilename() == null || !file.getFilename().endsWith(".java")) {
                continue;
            }
            changedFilePaths.add(file.getFilename());
            for (Integer line : extractChangedLineNumbers(file.getPatch())) {
                try {
                    LocationResponse response = fetchCodeLocation(file.getFilename(), line);
                    for (Map<String, Object> node : response.getNodes()) {
                        String nodeType = safe(node.get("type"));
                        String fqn = safe(node.get("fqn"));
                        if ("METHOD".equals(nodeType) && !fqn.isBlank() && !"-".equals(fqn)) {
                            changedMethods.add(fqn);
                        }
                        if ("TYPE".equals(nodeType) && !fqn.isBlank() && !"-".equals(fqn)) {
                            changedTypes.add(fqn);
                        }
                    }
                } catch (Exception ex) {
                    log.debug("按文件行解析变更节点失败 file={} line={}", file.getFilename(), line, ex);
                }
            }
        }

        return new ChangedCodeContext(new ArrayList<>(changedTypes), new ArrayList<>(changedMethods), new ArrayList<>(changedFilePaths));
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

    private record ChangedCodeContext(
            List<String> typeFqns,
            List<String> methodFqns,
            List<String> filePaths
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
    public static class EntryPointInfo {
        private String id;
        private String type;
        private String className;
        private String methodName;
        private String methodSignature;
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
