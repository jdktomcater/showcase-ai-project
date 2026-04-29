package com.jdktomcat.showcase.ai.aletheia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisResponse;
import com.jdktomcat.showcase.ai.aletheia.support.StaticObjectProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AletheiaAnalysisServiceTest {

    @Test
    void shouldReturnFallbackWhenChatClientUnavailable() {
        AletheiaProperties properties = new AletheiaProperties();
        AletheiaPromptService promptService = new AletheiaPromptService(properties, new ObjectMapper());
        McpCapabilityService capabilityService = new McpCapabilityService(StaticObjectProvider.empty());
        AletheiaAnalysisService service = new AletheiaAnalysisService(
                StaticObjectProvider.empty(),
                properties,
                promptService,
                capabilityService
        );
        AnalysisRequest request = new AnalysisRequest();
        request.setDomain(AnalysisDomain.PERFORMANCE);
        request.setQuestion("支付服务为什么慢");

        AnalysisResponse response = service.analyze(request);

        assertThat(response.modelAvailable()).isFalse();
        assertThat(response.report()).contains("Aletheia 聚合骨架");
    }
}
