package com.jdktomcat.showcase.ai.aletheia.service;

import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Collections;
import java.util.Iterator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AletheiaAnalysisServiceTest {

    @Test
    void shouldReturnFallbackWhenChatClientUnavailable() {
        AletheiaProperties properties = new AletheiaProperties();
        AletheiaPromptService promptService = new AletheiaPromptService(properties);
        McpCapabilityService capabilityService = new McpCapabilityService(emptyProvider());
        AletheiaAnalysisService service = new AletheiaAnalysisService(
                emptyProvider(),
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

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                throw new NoSuchBeanDefinitionException(Object.class);
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }

            @Override
            public T getObject() {
                throw new NoSuchBeanDefinitionException(Object.class);
            }

            @Override
            public Iterator<T> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public Stream<T> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<T> orderedStream() {
                return Stream.empty();
            }
        };
    }
}
