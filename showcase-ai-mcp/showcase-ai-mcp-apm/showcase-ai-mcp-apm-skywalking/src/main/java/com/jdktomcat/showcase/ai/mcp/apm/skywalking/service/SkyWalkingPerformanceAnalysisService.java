package com.jdktomcat.showcase.ai.mcp.apm.skywalking.service;

import com.jdktomcat.showcase.ai.mcp.apm.skywalking.client.SkyWalkingClientException;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.client.SkyWalkingGraphQlClient;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.config.SkyWalkingProperties;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.model.EndpointRef;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.model.ServiceRef;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.model.TraceSpan;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.model.TraceSummary;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SkyWalkingPerformanceAnalysisService {

    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm");

    private final SkyWalkingGraphQlClient graphQlClient;
    private final SkyWalkingProperties properties;

    public SkyWalkingPerformanceAnalysisService(SkyWalkingGraphQlClient graphQlClient, SkyWalkingProperties properties) {
        this.graphQlClient = graphQlClient;
        this.properties = properties;
    }

    public Map<String, Object> listServices(String keyword, Integer limit) {
        List<ServiceRef> services = fetchServices(keyword);
        int safeLimit = normalizePositive(limit, properties.getDefaultEndpointLimit());
        List<Map<String, Object>> items = services.stream()
                .limit(safeLimit)
                .map(service -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", service.id());
                    item.put("name", service.name());
                    item.put("shortName", service.shortName());
                    item.put("group", service.group());
                    item.put("layers", service.layers() == null ? List.of() : service.layers());
                    return item;
                })
                .toList();

        return new LinkedHashMap<>(Map.of(
                "keyword", StringUtils.defaultString(keyword),
                "count", items.size(),
                "services", items
        ));
    }

    public Map<String, Object> locateSlowEndpoints(String serviceName,
                                                   String endpointKeyword,
                                                   Integer durationMinutes,
                                                   Integer minTraceDurationMs,
                                                   Integer endpointLimit,
                                                   Integer traceLimit) {
        ServiceRef service = resolveService(serviceName);
        int safeDurationMinutes = normalizePositive(durationMinutes, properties.getDefaultDurationMinutes());
        int safeEndpointLimit = normalizePositive(endpointLimit, properties.getDefaultEndpointLimit());
        int safeTraceLimit = normalizePositive(traceLimit, properties.getDefaultTraceLimit());
        int safeMinTraceDurationMs = normalizePositive(minTraceDurationMs, properties.getDefaultMinTraceDurationMs());

        List<EndpointRef> endpoints = findEndpoints(service, endpointKeyword, safeEndpointLimit, safeDurationMinutes);
        List<Map<String, Object>> endpointReports = new ArrayList<>();
        for (EndpointRef endpoint : endpoints) {
            List<TraceSummary> traces = findSlowTraces(service, endpoint, safeDurationMinutes, safeMinTraceDurationMs, safeTraceLimit);
            if (traces.isEmpty()) {
                continue;
            }
            endpointReports.add(Map.of(
                    "endpoint", endpoint.name(),
                    "traceCount", traces.size(),
                    "slowestTraceMs", traces.stream().mapToInt(TraceSummary::durationMs).max().orElse(0),
                    "avgTraceMs", traces.stream().mapToInt(TraceSummary::durationMs).average().orElse(0),
                    "traces", traces.stream().map(this::toTraceMap).toList()
            ));
        }

        return new LinkedHashMap<>(Map.of(
                "service", service.name(),
                "durationMinutes", safeDurationMinutes,
                "minTraceDurationMs", safeMinTraceDurationMs,
                "endpointKeyword", StringUtils.defaultString(endpointKeyword),
                "endpoints", endpointReports
        ));
    }

    public Map<String, Object> diagnoseTrace(String traceId) {
        List<TraceSpan> spans = getTrace(traceId);
        if (spans.isEmpty()) {
            return Map.of("traceId", traceId, "spans", List.of(), "suspicions", List.of("未找到 Trace 或 Trace 没有 Span 数据"));
        }

        List<Map<String, Object>> orderedSpans = spans.stream()
                .sorted(Comparator.comparingLong(TraceSpan::durationMs).reversed())
                .map(span -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("service", span.serviceCode());
                    item.put("endpoint", span.endpointName());
                    item.put("component", span.component());
                    item.put("peer", span.peer());
                    item.put("layer", span.layer());
                    item.put("type", span.type());
                    item.put("durationMs", span.durationMs());
                    item.put("error", span.error());
                    return item;
                })
                .toList();

        List<String> suspicions = spans.stream()
                .sorted(Comparator.comparingLong(TraceSpan::durationMs).reversed())
                .limit(3)
                .map(this::describeSpanSuspicion)
                .toList();

        return new LinkedHashMap<>(Map.of(
                "traceId", traceId,
                "spanCount", spans.size(),
                "totalDurationMs", spans.stream().mapToLong(TraceSpan::durationMs).max().orElse(0),
                "suspicions", suspicions,
                "spans", orderedSpans
        ));
    }

    public Map<String, Object> diagnoseServicePerformance(String serviceName,
                                                          String endpointKeyword,
                                                          Integer durationMinutes,
                                                          Integer minTraceDurationMs,
                                                          Integer endpointLimit,
                                                          Integer traceLimit) {
        ServiceRef service = resolveService(serviceName);
        int safeDurationMinutes = normalizePositive(durationMinutes, properties.getDefaultDurationMinutes());
        int safeEndpointLimit = normalizePositive(endpointLimit, properties.getDefaultEndpointLimit());
        int safeTraceLimit = normalizePositive(traceLimit, properties.getDefaultTraceLimit());
        int safeMinTraceDurationMs = normalizePositive(minTraceDurationMs, properties.getDefaultMinTraceDurationMs());

        List<EndpointRef> endpoints = findEndpoints(service, endpointKeyword, safeEndpointLimit, safeDurationMinutes);
        Map<String, Integer> dependencyWeights = new LinkedHashMap<>();
        List<TraceSummary> allTraces = new ArrayList<>();
        List<TraceSpan> allSpans = new ArrayList<>();

        for (EndpointRef endpoint : endpoints) {
            allTraces.addAll(findSlowTraces(service, endpoint, safeDurationMinutes, safeMinTraceDurationMs, safeTraceLimit));
            mergeDependencyWeights(dependencyWeights, findEndpointDependencies(service, endpoint, safeDurationMinutes));
        }
        Set<String> traceIds = allTraces.stream()
                .map(TraceSummary::traceIds)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String traceId : traceIds) {
            allSpans.addAll(getTrace(traceId));
        }

        List<Map<String, Object>> endpointResults = endpoints.stream()
                .map(endpoint -> Map.of(
                        "name", endpoint.name(),
                        "dependencies", dependencyWeights.entrySet().stream()
                                .filter(entry -> entry.getKey().startsWith(endpoint.name() + " -> "))
                                .map(entry -> Map.of(
                                        "target", entry.getKey().substring((endpoint.name() + " -> ").length()),
                                        "weight", entry.getValue()
                                ))
                                .toList()
                ))
                .toList();

        List<Map<String, Object>> topBottlenecks = summarizeTopBottlenecks(allSpans);
        List<String> suggestions = buildSuggestions(topBottlenecks, allTraces, dependencyWeights);

        return new LinkedHashMap<>(Map.of(
                "service", service.name(),
                "durationMinutes", safeDurationMinutes,
                "endpointKeyword", StringUtils.defaultString(endpointKeyword),
                "slowTraceCount", allTraces.size(),
                "slowestTraceMs", allTraces.stream().mapToInt(TraceSummary::durationMs).max().orElse(0),
                "avgTraceMs", allTraces.stream().mapToInt(TraceSummary::durationMs).average().orElse(0),
                "endpoints", endpointResults,
                "topBottlenecks", topBottlenecks,
                "sampleSlowTraces", allTraces.stream().limit(10).map(this::toTraceMap).toList(),
                "suggestions", suggestions
        ));
    }

    private List<ServiceRef> fetchServices(String keyword) {
        SkyWalkingGraphQlClient.GraphQlAttempt primary = new SkyWalkingGraphQlClient.GraphQlAttempt(
                "ListServices",
                "query ListServices($layer:String){ listServices(layer:$layer){ id name shortName group layers } }",
                new LinkedHashMap<>()
        );
        SkyWalkingGraphQlClient.GraphQlAttempt fallback = new SkyWalkingGraphQlClient.GraphQlAttempt(
                "SearchServices",
                "query SearchServices($duration:Duration!, $keyword:String!){ searchServices(duration:$duration, keyword:$keyword){ id name } }",
                Map.of(
                        "duration", buildDuration(properties.getDefaultDurationMinutes()),
                        "keyword", StringUtils.defaultIfBlank(keyword, "")
                )
        );
        Map<String, Object> data = graphQlClient.queryWithFallback(primary, fallback);
        List<Map<String, Object>> items = getListOfMap(data, data.containsKey("listServices") ? "listServices" : "searchServices");
        return items.stream()
                .map(item -> new ServiceRef(
                        asString(item.get("id")),
                        asString(item.get("name")),
                        asString(item.get("shortName")),
                        asString(item.get("group")),
                        asStringList(item.get("layers"))
                ))
                .filter(service -> StringUtils.isNotBlank(service.name()))
                .filter(service -> StringUtils.isBlank(keyword)
                        || StringUtils.containsIgnoreCase(service.name(), keyword)
                        || StringUtils.containsIgnoreCase(service.shortName(), keyword))
                .sorted(Comparator.comparing(ServiceRef::name))
                .toList();
    }

    private ServiceRef resolveService(String serviceName) {
        if (StringUtils.isBlank(serviceName)) {
            throw new IllegalArgumentException("serviceName 不能为空");
        }

        SkyWalkingGraphQlClient.GraphQlAttempt primary = new SkyWalkingGraphQlClient.GraphQlAttempt(
                "FindService",
                "query FindService($serviceName:String!){ findService(serviceName:$serviceName){ id name shortName group layers } }",
                Map.of("serviceName", serviceName)
        );
        SkyWalkingGraphQlClient.GraphQlAttempt fallback = new SkyWalkingGraphQlClient.GraphQlAttempt(
                "SearchService",
                "query SearchService($serviceCode:String!){ searchService(serviceCode:$serviceCode){ id name } }",
                Map.of("serviceCode", serviceName)
        );
        Map<String, Object> data = graphQlClient.queryWithFallback(primary, fallback);
        Map<String, Object> serviceMap = getMap(data, data.containsKey("findService") ? "findService" : "searchService");
        if (serviceMap == null || StringUtils.isBlank(asString(serviceMap.get("name")))) {
            List<ServiceRef> candidates = fetchServices(serviceName);
            Optional<ServiceRef> exact = candidates.stream()
                    .filter(service -> StringUtils.equalsIgnoreCase(service.name(), serviceName))
                    .findFirst();
            if (exact.isPresent()) {
                return exact.get();
            }
            throw new IllegalArgumentException("未找到服务: " + serviceName);
        }
        return new ServiceRef(
                asString(serviceMap.get("id")),
                asString(serviceMap.get("name")),
                asString(serviceMap.get("shortName")),
                asString(serviceMap.get("group")),
                asStringList(serviceMap.get("layers"))
        );
    }

    private List<EndpointRef> findEndpoints(ServiceRef service, String endpointKeyword, int limit, int durationMinutes) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("keyword", StringUtils.defaultIfBlank(endpointKeyword, null));
        variables.put("service", Map.of("serviceName", service.name()));
        variables.put("limit", limit);
        variables.put("duration", buildDuration(durationMinutes));
        SkyWalkingGraphQlClient.GraphQlAttempt primary = new SkyWalkingGraphQlClient.GraphQlAttempt(
                "FindEndpointByName",
                "query FindEndpointByName($keyword:String, $service:ServiceCondition!, $limit:Int!, $duration:Duration){ "
                        + "findEndpointByName(keyword:$keyword, service:$service, limit:$limit, duration:$duration){ id name } }",
                variables
        );
        SkyWalkingGraphQlClient.GraphQlAttempt fallback = new SkyWalkingGraphQlClient.GraphQlAttempt(
                "SearchEndpoint",
                "query SearchEndpoint($keyword:String!, $serviceId:ID!, $limit:Int!){ "
                        + "searchEndpoint(keyword:$keyword, serviceId:$serviceId, limit:$limit){ id name } }",
                Map.of(
                        "keyword", StringUtils.defaultString(endpointKeyword),
                        "serviceId", service.id(),
                        "limit", limit
                )
        );
        Map<String, Object> data = graphQlClient.queryWithFallback(primary, fallback);
        List<Map<String, Object>> items = getListOfMap(data, data.containsKey("findEndpointByName")
                ? "findEndpointByName"
                : "searchEndpoint");
        return items.stream()
                .map(item -> new EndpointRef(asString(item.get("id")), asString(item.get("name"))))
                .filter(endpoint -> StringUtils.isNotBlank(endpoint.name()))
                .toList();
    }

    private List<TraceSummary> findSlowTraces(ServiceRef service,
                                              EndpointRef endpoint,
                                              int durationMinutes,
                                              int minTraceDurationMs,
                                              int traceLimit) {
        Map<String, Object> primaryVariables = new LinkedHashMap<>();
        primaryVariables.put("condition", Map.of(
                "service", Map.of("serviceName", service.name()),
                "endpoint", Map.of("serviceName", service.name(), "endpointName", endpoint.name()),
                "queryDuration", buildDuration(durationMinutes),
                "minTraceDuration", minTraceDurationMs,
                "traceState", "ALL",
                "queryOrder", "BY_DURATION",
                "paging", Map.of("pageNum", 1, "pageSize", traceLimit)
        ));
        SkyWalkingGraphQlClient.GraphQlAttempt primary = new SkyWalkingGraphQlClient.GraphQlAttempt(
                "QueryBasicTracesByName",
                "query QueryBasicTracesByName($condition:TraceQueryConditionByName){ "
                        + "queryBasicTracesByName(condition:$condition){ traces{ segmentId endpointNames duration start isError traceIds } } }",
                primaryVariables
        );
        SkyWalkingGraphQlClient.GraphQlAttempt fallback = new SkyWalkingGraphQlClient.GraphQlAttempt(
                "QueryBasicTraces",
                "query QueryBasicTraces($condition:TraceQueryCondition){ "
                        + "queryBasicTraces(condition:$condition){ traces{ segmentId endpointNames duration start isError traceIds } } }",
                Map.of("condition", Map.of(
                        "serviceId", service.id(),
                        "endpointId", endpoint.id(),
                        "queryDuration", buildDuration(durationMinutes),
                        "minTraceDuration", minTraceDurationMs,
                        "traceState", "ALL",
                        "queryOrder", "BY_DURATION",
                        "paging", Map.of("pageNum", 1, "pageSize", traceLimit)
                ))
        );
        Map<String, Object> data = graphQlClient.queryWithFallback(primary, fallback);
        Map<String, Object> traceBrief = getMap(data, data.containsKey("queryBasicTracesByName")
                ? "queryBasicTracesByName"
                : "queryBasicTraces");
        List<Map<String, Object>> traces = getListOfMap(traceBrief, "traces");
        return traces.stream()
                .map(item -> new TraceSummary(
                        asString(item.get("segmentId")),
                        asStringList(item.get("traceIds")),
                        asStringList(item.get("endpointNames")),
                        asInt(item.get("duration")),
                        asString(item.get("start")),
                        asBoolean(item.get("isError"))
                ))
                .sorted(Comparator.comparingInt(TraceSummary::durationMs).reversed())
                .toList();
    }

    private List<TraceSpan> getTrace(String traceId) {
        Map<String, Object> data = graphQlClient.query(
                "QueryTrace",
                "query QueryTrace($traceId:ID!){ "
                        + "queryTrace(traceId:$traceId){ spans{ traceId segmentId spanId parentSpanId serviceCode serviceInstanceName "
                        + "startTime endTime endpointName type peer component isError layer } } }",
                Map.of("traceId", traceId)
        );
        Map<String, Object> trace = getMap(data, "queryTrace");
        List<Map<String, Object>> spans = getListOfMap(trace, "spans");
        return spans.stream()
                .map(span -> new TraceSpan(
                        asString(span.get("traceId")),
                        asString(span.get("segmentId")),
                        asInt(span.get("spanId")),
                        asInt(span.get("parentSpanId")),
                        asString(span.get("serviceCode")),
                        asString(span.get("serviceInstanceName")),
                        asLong(span.get("startTime")),
                        asLong(span.get("endTime")),
                        asString(span.get("endpointName")),
                        asString(span.get("type")),
                        asString(span.get("peer")),
                        asString(span.get("component")),
                        asBoolean(span.get("isError")),
                        asString(span.get("layer")),
                        Math.max(0L, asLong(span.get("endTime")) - asLong(span.get("startTime")))
                ))
                .sorted(Comparator.comparingLong(TraceSpan::durationMs).reversed())
                .toList();
    }

    private Map<String, Integer> findEndpointDependencies(ServiceRef service, EndpointRef endpoint, int durationMinutes) {
        try {
            Map<String, Object> data = graphQlClient.queryWithFallback(
                    new SkyWalkingGraphQlClient.GraphQlAttempt(
                            "GetEndpointDependenciesByName",
                            "query GetEndpointDependenciesByName($endpoint:EndpointCondition!, $duration:Duration!){ "
                                    + "getEndpointDependenciesByName(endpoint:$endpoint, duration:$duration){ nodes{ id name } calls{ source target } } }",
                            Map.of(
                                    "endpoint", Map.of("serviceName", service.name(), "endpointName", endpoint.name()),
                                    "duration", buildDuration(durationMinutes)
                            )
                    ),
                    new SkyWalkingGraphQlClient.GraphQlAttempt(
                            "GetEndpointDependencies",
                            "query GetEndpointDependencies($endpointId:ID!, $duration:Duration!){ "
                                    + "getEndpointDependencies(endpointId:$endpointId, duration:$duration){ nodes{ id name } calls{ source target } } }",
                            Map.of("endpointId", endpoint.id(), "duration", buildDuration(durationMinutes))
                    )
            );

            Map<String, Object> topology = getMap(data, data.containsKey("getEndpointDependenciesByName")
                    ? "getEndpointDependenciesByName"
                    : "getEndpointDependencies");
            List<Map<String, Object>> nodes = getListOfMap(topology, "nodes");
            List<Map<String, Object>> calls = getListOfMap(topology, "calls");
            Map<String, String> nodeNameById = nodes.stream().collect(Collectors.toMap(
                    node -> asString(node.get("id")),
                    node -> asString(node.get("name")),
                    (left, right) -> left,
                    LinkedHashMap::new
            ));
            Map<String, Integer> result = new LinkedHashMap<>();
            for (Map<String, Object> call : calls) {
                String source = nodeNameById.get(asString(call.get("source")));
                String target = nodeNameById.get(asString(call.get("target")));
                if (StringUtils.isBlank(source) || StringUtils.isBlank(target) || StringUtils.equals(source, target)) {
                    continue;
                }
                result.merge(source + " -> " + target, 1, Integer::sum);
            }
            return result;
        } catch (SkyWalkingClientException ex) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> summarizeTopBottlenecks(List<TraceSpan> spans) {
        Map<String, List<TraceSpan>> grouped = spans.stream()
                .collect(Collectors.groupingBy(this::spanGroupingKey, LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<TraceSpan> items = entry.getValue();
                    TraceSpan sample = items.get(0);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("operation", resolveOperationName(sample));
                    result.put("service", sample.serviceCode());
                    result.put("component", sample.component());
                    result.put("peer", sample.peer());
                    result.put("layer", sample.layer());
                    result.put("count", items.size());
                    result.put("avgDurationMs", items.stream().mapToLong(TraceSpan::durationMs).average().orElse(0));
                    result.put("maxDurationMs", items.stream().mapToLong(TraceSpan::durationMs).max().orElse(0));
                    result.put("errorCount", items.stream().filter(TraceSpan::error).count());
                    result.put("suspectedCause", describeSpanSuspicion(sample));
                    return result;
                })
                .sorted(Comparator.comparingDouble(item -> -toDouble(item.get("avgDurationMs"))))
                .limit(8)
                .toList();
    }

    private List<String> buildSuggestions(List<Map<String, Object>> topBottlenecks,
                                          List<TraceSummary> traces,
                                          Map<String, Integer> dependencyWeights) {
        List<String> suggestions = new ArrayList<>();
        if (!topBottlenecks.isEmpty()) {
            Map<String, Object> top = topBottlenecks.get(0);
            suggestions.add("优先检查 `" + Objects.toString(top.get("operation"), "-")
                    + "`，其平均耗时约 " + Math.round(toDouble(top.get("avgDurationMs"))) + "ms。");
        }
        dependencyWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(2)
                .forEach(entry -> suggestions.add("关注依赖链路 `" + entry.getKey() + "`，该依赖在慢请求样本中反复出现。"));
        long errorTraceCount = traces.stream().filter(TraceSummary::error).count();
        if (errorTraceCount > 0) {
            suggestions.add("慢请求中包含 " + errorTraceCount + " 条异常 Trace，建议优先排查重试、超时和错误回退逻辑。");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("未识别到明显的单点瓶颈，建议扩大时间窗口或降低最小慢 Trace 阈值继续采样。");
        }
        return suggestions;
    }

    private void mergeDependencyWeights(Map<String, Integer> dependencyWeights, Map<String, Integer> endpointDependencies) {
        for (Map.Entry<String, Integer> entry : endpointDependencies.entrySet()) {
            dependencyWeights.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private Map<String, Object> toTraceMap(TraceSummary trace) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segmentId", trace.segmentId());
        result.put("traceIds", trace.traceIds());
        result.put("endpointNames", trace.endpointNames());
        result.put("durationMs", trace.durationMs());
        result.put("start", trace.start());
        result.put("error", trace.error());
        return result;
    }

    private String spanGroupingKey(TraceSpan span) {
        return resolveOperationName(span) + "|" + span.serviceCode() + "|" + span.component() + "|" + span.peer();
    }

    private String resolveOperationName(TraceSpan span) {
        if (StringUtils.isNotBlank(span.endpointName())) {
            return span.endpointName();
        }
        if (StringUtils.isNotBlank(span.component())) {
            return span.component();
        }
        if (StringUtils.isNotBlank(span.peer())) {
            return span.peer();
        }
        return span.serviceCode() + "#" + span.spanId();
    }

    private String describeSpanSuspicion(TraceSpan span) {
        String operation = resolveOperationName(span);
        if (StringUtils.equalsIgnoreCase(span.layer(), "Database")) {
            return operation + " 表现为数据库访问慢，优先检查 SQL、索引和连接池。";
        }
        if (StringUtils.equalsIgnoreCase(span.type(), "Exit") && StringUtils.isNotBlank(span.peer())) {
            return operation + " 是下游调用慢点，重点看 `" + span.peer() + "` 的 RT、超时和重试。";
        }
        if (StringUtils.equalsIgnoreCase(span.type(), "Entry")) {
            return operation + " 入口耗时较长，可能是接口层串行等待或本地计算偏重。";
        }
        return operation + " 耗时偏高，建议检查组件 `" + StringUtils.defaultIfBlank(span.component(), "-") + "` 的实现细节。";
    }

    private Map<String, Object> buildDuration(int durationMinutes) {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusMinutes(durationMinutes);
        return Map.of(
                "start", MINUTE_FORMATTER.format(start),
                "end", MINUTE_FORMATTER.format(end),
                "step", "MINUTE"
        );
    }

    private int normalizePositive(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getListOfMap(Map<String, Object> source, String key) {
        if (source == null) {
            return List.of();
        }
        Object value = source.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(item -> Objects.toString(item, null)).filter(Objects::nonNull).toList();
    }

    private String asString(Object value) {
        return Objects.toString(value, null);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(Objects.toString(value, "0"));
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(Objects.toString(value, "0"));
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(Objects.toString(value, "0"));
    }
}
