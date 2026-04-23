package com.jdktomcat.showcase.ai.mcp.apm.skywalking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.client.SkyWalkingGraphQlClient;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.config.SkyWalkingProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class SkyWalkingPerformanceAnalysisServiceTest {

    @Test
    void shouldDiagnoseTraceAndHighlightExitSpan() {
        SkyWalkingGraphQlClient graphQlClient = new StubSkyWalkingGraphQlClient(
                (operationName, document, variables) -> Map.of(
                        "queryTrace", Map.of(
                                "spans", List.of(
                                        buildSpan(
                                                "trace-1", "segment-a", 0, -1,
                                                "order-service", "order-service-1",
                                                1000L, 1600L,
                                                "POST /api/order/create", "Entry",
                                                "", "SpringMVC", false, "Http"
                                        ),
                                        buildSpan(
                                                "trace-1", "segment-a", 1, 0,
                                                "order-service", "order-service-1",
                                                1100L, 1550L,
                                                "OrderMapper.insert", "Exit",
                                                "mysql:3306", "Mysql", false, "Database"
                                        )
                                )
                        )
                ),
                (primary, fallback) -> Map.of()
        );
        SkyWalkingProperties properties = buildProperties();
        SkyWalkingPerformanceAnalysisService service = new SkyWalkingPerformanceAnalysisService(graphQlClient, properties);

        Map<String, Object> result = service.diagnoseTrace("trace-1");

        assertThat(result.get("traceId")).isEqualTo("trace-1");
        assertThat(result.get("spanCount")).isEqualTo(2);
        assertThat(result.get("suspicions").toString()).contains("数据库访问慢");
    }

    @Test
    void shouldListServicesWithKeywordFilter() {
        SkyWalkingGraphQlClient graphQlClient = new StubSkyWalkingGraphQlClient(
                (operationName, document, variables) -> Map.of(),
                (primary, fallback) -> Map.of(
                        "listServices", List.of(
                                Map.of("id", "1", "name", "order-service", "shortName", "order-service", "group", "", "layers", List.of("GENERAL")),
                                Map.of("id", "2", "name", "payment-service", "shortName", "payment-service", "group", "", "layers", List.of("GENERAL"))
                        )
                )
        );
        SkyWalkingProperties properties = buildProperties();
        SkyWalkingPerformanceAnalysisService service = new SkyWalkingPerformanceAnalysisService(graphQlClient, properties);

        Map<String, Object> result = service.listServices("order", 10);

        assertThat(result.get("count")).isEqualTo(1);
        assertThat(result.get("services").toString()).contains("order-service");
    }

    private SkyWalkingProperties buildProperties() {
        SkyWalkingProperties properties = new SkyWalkingProperties();
        properties.setDefaultDurationMinutes(30);
        properties.setDefaultEndpointLimit(5);
        properties.setDefaultTraceLimit(5);
        properties.setDefaultMinTraceDurationMs(500);
        return properties;
    }

    private Map<String, Object> buildSpan(String traceId,
                                          String segmentId,
                                          int spanId,
                                          int parentSpanId,
                                          String serviceCode,
                                          String serviceInstanceName,
                                          long startTime,
                                          long endTime,
                                          String endpointName,
                                          String type,
                                          String peer,
                                          String component,
                                          boolean isError,
                                          String layer) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", traceId);
        span.put("segmentId", segmentId);
        span.put("spanId", spanId);
        span.put("parentSpanId", parentSpanId);
        span.put("serviceCode", serviceCode);
        span.put("serviceInstanceName", serviceInstanceName);
        span.put("startTime", startTime);
        span.put("endTime", endTime);
        span.put("endpointName", endpointName);
        span.put("type", type);
        span.put("peer", peer);
        span.put("component", component);
        span.put("isError", isError);
        span.put("layer", layer);
        return span;
    }

    private static final class StubSkyWalkingGraphQlClient extends SkyWalkingGraphQlClient {

        private final TriFunction<String, String, Map<String, Object>, Map<String, Object>> queryHandler;
        private final BiFunction<GraphQlAttempt, GraphQlAttempt, Map<String, Object>> fallbackHandler;

        private StubSkyWalkingGraphQlClient(
                TriFunction<String, String, Map<String, Object>, Map<String, Object>> queryHandler,
                BiFunction<GraphQlAttempt, GraphQlAttempt, Map<String, Object>> fallbackHandler) {
            super(new SkyWalkingProperties(), new ObjectMapper());
            this.queryHandler = queryHandler;
            this.fallbackHandler = fallbackHandler;
        }

        @Override
        public Map<String, Object> query(String operationName, String document, Map<String, Object> variables) {
            return queryHandler.apply(operationName, document, variables);
        }

        @Override
        public Map<String, Object> queryWithFallback(GraphQlAttempt primary, GraphQlAttempt fallback) {
            return fallbackHandler.apply(primary, fallback);
        }
    }

    @FunctionalInterface
    private interface TriFunction<T, U, V, R> {
        R apply(T t, U u, V v);
    }
}
