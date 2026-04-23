package com.jdktomcat.showcase.ai.aletheia.service;

import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AletheiaPromptServiceTest {

    @Test
    void shouldBuildPerformancePromptWithContext() {
        AletheiaProperties properties = new AletheiaProperties();
        AletheiaPromptService promptService = new AletheiaPromptService(properties);
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
}
