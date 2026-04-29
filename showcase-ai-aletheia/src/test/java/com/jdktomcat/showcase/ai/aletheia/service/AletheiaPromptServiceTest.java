package com.jdktomcat.showcase.ai.aletheia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceLogAnalysisRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AletheiaPromptServiceTest {

    @Test
    void shouldBuildPerformancePromptWithContext() {
        AletheiaPromptService promptService = newPromptService();
        AnalysisRequest request = new AnalysisRequest();
        request.setQuestion("订单创建为什么变慢");
        request.setServiceName("order-service");
        request.setEndpointKeyword("/api/order/create");
        request.setTraceId("trace-1");
        request.setDurationMinutes(15);

        String prompt = promptService.buildPerformanceUserPrompt(request, true);

        assertThat(prompt).contains("订单创建为什么变慢");
        assertThat(prompt).contains("order-service");
        assertThat(prompt).contains("trace-1");
        assertThat(prompt).contains("MCP 工具可用：是");
    }

    @Test
    void shouldBuildTraceLogPromptWithEvidenceJson() {
        AletheiaPromptService promptService = newPromptService();
        TraceLogAnalysisRequest request = new TraceLogAnalysisRequest();
        request.setQuestion("订单创建为何超时");
        request.setTraceId("trace-xyz");
        request.setServiceName("order-service");
        request.setEndpointKeyword("/api/order/create");
        request.setDurationMinutes(20);
        request.setMinTraceDurationMs(800);

        Map<String, Object> evidence = Map.of(
                "traces", Map.of("trace-xyz", Map.of(
                        "totalDurationMs", 1234L,
                        "spans", List.of(Map.of(
                                "service", "order-service",
                                "endpoint", "/api/order/create",
                                "durationMs", 1100L
                        ))
                ))
        );

        String prompt = promptService.buildTraceLogUserPrompt(request, evidence);

        assertThat(prompt).contains("订单创建为何超时");
        assertThat(prompt).contains("trace-xyz");
        assertThat(prompt).contains("/api/order/create");
        assertThat(prompt).contains("最近 20 分钟");
        assertThat(prompt).contains("慢 Trace 阈值：800 ms");
        assertThat(prompt).contains("\"durationMs\" : 1100");
        assertThat(prompt).contains("链路证据 BEGIN");
        assertThat(prompt).contains("链路证据 END");
    }

    @Test
    void shouldFallbackOnEmptyTraceLogEvidence() {
        AletheiaPromptService promptService = newPromptService();
        TraceLogAnalysisRequest request = new TraceLogAnalysisRequest();
        request.setTraceId("trace-empty");

        String prompt = promptService.buildTraceLogUserPrompt(request, null);

        assertThat(prompt).contains("无可用链路证据");
        assertThat(prompt).contains("trace-empty");
    }

    private AletheiaPromptService newPromptService() {
        return new AletheiaPromptService(new AletheiaProperties(), new ObjectMapper());
    }
}
