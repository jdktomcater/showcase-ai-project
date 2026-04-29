package com.jdktomcat.showcase.ai.aletheia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceLogAnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceLogAnalysisResponse;
import com.jdktomcat.showcase.ai.aletheia.support.RecordingToolCallback;
import com.jdktomcat.showcase.ai.aletheia.support.StaticObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLogAnalysisServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldFallbackWhenNoTraceIdAndNoServiceName() {
        TraceLogAnalysisService service = newServiceWithoutMcp();

        TraceLogAnalysisResponse response = service.analyze(new TraceLogAnalysisRequest());

        assertThat(response.domain()).isEqualTo(AnalysisDomain.TRACE_LOG);
        assertThat(response.modelAvailable()).isFalse();
        assertThat(response.mcpAvailable()).isFalse();
        assertThat(response.summary()).contains("无法定位 SkyWalking 链路日志");
    }

    @Test
    void shouldFallbackWhenMcpDisabled() {
        TraceLogAnalysisService service = newServiceWithoutMcp();

        TraceLogAnalysisRequest request = new TraceLogAnalysisRequest();
        request.setTraceId("trace-1");

        TraceLogAnalysisResponse response = service.analyze(request);

        assertThat(response.mcpAvailable()).isFalse();
        assertThat(response.summary()).contains("MCP 工具未就绪");
        assertThat(response.report()).contains("ALETHEIA_MCP_ENABLED");
    }

    @Test
    void shouldReadTraceEvidenceWhenTraceIdGiven() {
        RecordingToolCallback diagnoseTrace = new RecordingToolCallback(
                "skywalking_diagnoseTrace",
                input -> """
                        {
                          "traceId": "trace-xyz",
                          "spanCount": 3,
                          "totalDurationMs": 1500,
                          "spans": [
                            {"service": "order-service", "endpoint": "/api/order/create",
                             "component": "SpringMVC", "peer": "", "layer": "Http",
                             "type": "Entry", "durationMs": 1450, "error": false},
                            {"service": "order-service", "endpoint": "MysqlInsert",
                             "component": "Mysql-JDBC-Driver", "peer": "mysql:3306",
                             "layer": "Database", "type": "Exit", "durationMs": 950, "error": false},
                            {"service": "order-service", "endpoint": "RedisGet",
                             "component": "Jedis", "peer": "redis:6379",
                             "layer": "Cache", "type": "Exit", "durationMs": 200, "error": false}
                          ]
                        }
                        """
        );

        TraceLogAnalysisService service = newServiceWithTools(List.of(diagnoseTrace));
        TraceLogAnalysisRequest request = new TraceLogAnalysisRequest();
        request.setTraceId("trace-xyz");

        TraceLogAnalysisResponse response = service.analyze(request);

        assertThat(response.domain()).isEqualTo(AnalysisDomain.TRACE_LOG);
        assertThat(response.mcpAvailable()).isTrue();
        assertThat(response.modelAvailable()).isFalse();
        assertThat(response.traceId()).isEqualTo("trace-xyz");
        assertThat(response.spanCount()).isEqualTo(3);
        assertThat(response.totalDurationMs()).isEqualTo(1500L);
        assertThat(response.topSpans()).hasSize(3);
        assertThat(response.topSpans().get(0).durationMs()).isEqualTo(1450L);
        assertThat(response.suggestions()).isNotEmpty();
        assertThat(response.rawEvidence()).isNotNull();
        assertThat(diagnoseTrace.invocations()).hasSize(1);
        assertThat(diagnoseTrace.invocations().get(0)).contains("\"traceId\":\"trace-xyz\"");
    }

    @Test
    void shouldDiscoverTraceFromSlowEndpointsWhenOnlyServiceProvided() {
        RecordingToolCallback locateSlow = new RecordingToolCallback(
                "skywalking_locateSlowEndpoints",
                input -> """
                        {
                          "service": "order-service",
                          "endpoints": [
                            {
                              "endpoint": "/api/order/create",
                              "traceCount": 1,
                              "traces": [
                                {"segmentId": "seg-1", "traceIds": ["trace-A"], "durationMs": 1300, "error": false}
                              ]
                            }
                          ]
                        }
                        """
        );
        RecordingToolCallback diagnoseTrace = new RecordingToolCallback(
                "skywalking_diagnoseTrace",
                input -> """
                        {
                          "traceId": "trace-A",
                          "spanCount": 1,
                          "totalDurationMs": 1300,
                          "spans": [
                            {"service": "order-service", "endpoint": "/api/order/create",
                             "component": "SpringMVC", "layer": "Http", "type": "Entry",
                             "durationMs": 1300, "error": false}
                          ]
                        }
                        """
        );

        TraceLogAnalysisService service = newServiceWithTools(List.of(locateSlow, diagnoseTrace));
        TraceLogAnalysisRequest request = new TraceLogAnalysisRequest();
        request.setServiceName("order-service");
        request.setEndpointKeyword("/api/order/create");
        request.setDurationMinutes(15);
        request.setTraceLimit(2);

        TraceLogAnalysisResponse response = service.analyze(request);

        assertThat(response.mcpAvailable()).isTrue();
        assertThat(response.traceId()).isEqualTo("trace-A");
        assertThat(response.spanCount()).isEqualTo(1);
        assertThat(response.evidenceSource()).contains("serviceName=order-service");
        assertThat(locateSlow.invocations()).hasSize(1);
        assertThat(locateSlow.invocations().get(0)).contains("\"serviceName\":\"order-service\"");
        assertThat(locateSlow.invocations().get(0)).contains("\"endpointKeyword\":\"/api/order/create\"");
        assertThat(diagnoseTrace.invocations()).hasSize(1);
        assertThat(diagnoseTrace.invocations().get(0)).contains("\"traceId\":\"trace-A\"");
    }

    @Test
    void shouldFallbackWhenSlowEndpointsReturnNoTrace() {
        RecordingToolCallback locateSlow = new RecordingToolCallback(
                "skywalking_locateSlowEndpoints",
                input -> "{\"service\":\"order-service\",\"endpoints\":[]}"
        );

        TraceLogAnalysisService service = newServiceWithTools(List.of(locateSlow));
        TraceLogAnalysisRequest request = new TraceLogAnalysisRequest();
        request.setServiceName("order-service");

        TraceLogAnalysisResponse response = service.analyze(request);

        assertThat(response.mcpAvailable()).isTrue();
        assertThat(response.spanCount()).isZero();
        assertThat(response.summary()).contains("MCP 工具返回成功");
    }

    @Test
    void shouldHonourIncludeRawEvidenceFlag() {
        RecordingToolCallback diagnoseTrace = new RecordingToolCallback(
                "skywalking_diagnoseTrace",
                input -> """
                        {"traceId":"trace-x","spanCount":1,"totalDurationMs":100,
                         "spans":[{"service":"svc","endpoint":"/x","durationMs":100}]}
                        """
        );

        TraceLogAnalysisService service = newServiceWithTools(List.of(diagnoseTrace));
        TraceLogAnalysisRequest request = new TraceLogAnalysisRequest();
        request.setTraceId("trace-x");
        request.setIncludeRawEvidence(false);

        TraceLogAnalysisResponse response = service.analyze(request);

        assertThat(response.rawEvidence()).isNull();
        assertThat(response.spanCount()).isEqualTo(1);
    }

    private TraceLogAnalysisService newServiceWithoutMcp() {
        AletheiaProperties properties = new AletheiaProperties();
        AletheiaPromptService promptService = new AletheiaPromptService(properties, objectMapper);
        McpToolInvoker invoker = new McpToolInvoker(StaticObjectProvider.empty(), objectMapper);
        return new TraceLogAnalysisService(StaticObjectProvider.empty(), properties, promptService, invoker);
    }

    private TraceLogAnalysisService newServiceWithTools(List<ToolCallback> callbacks) {
        AletheiaProperties properties = new AletheiaProperties();
        AletheiaPromptService promptService = new AletheiaPromptService(properties, objectMapper);
        ToolCallbackProvider provider = ToolCallbackProvider.from(callbacks);
        McpToolInvoker invoker = new McpToolInvoker(
                StaticObjectProvider.of(provider),
                objectMapper);
        return new TraceLogAnalysisService(
                StaticObjectProvider.<ChatClient.Builder>empty(),
                properties,
                promptService,
                invoker);
    }
}
