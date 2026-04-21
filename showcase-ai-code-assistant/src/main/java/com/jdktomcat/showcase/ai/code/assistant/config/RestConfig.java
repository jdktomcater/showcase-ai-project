package com.jdktomcat.showcase.ai.code.assistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${http.client.connect-timeout:2s}") Duration connectTimeout,
            @Value("${http.client.read-timeout:8s}") Duration readTimeout
    ) {
        return builder
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }

    @Bean("codeChunkGraphRestTemplate")
    public RestTemplate codeChunkGraphRestTemplate(
            RestTemplateBuilder builder,
            @Value("${http.client.connect-timeout:2s}") Duration connectTimeout,
            @Value("${code-chunk.graph-index.read-timeout:180s}") Duration readTimeout
    ) {
        return builder
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }
}
