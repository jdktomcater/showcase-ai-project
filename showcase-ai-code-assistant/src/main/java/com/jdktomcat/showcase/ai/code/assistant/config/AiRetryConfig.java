package com.jdktomcat.showcase.ai.code.assistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * 本地 Ollama 常见超时会抛出 ResourceAccessException，需要纳入重试。
 */
@Slf4j
@Configuration
public class AiRetryConfig {

    @Bean
    public RetryTemplate retryTemplate(SpringAiRetryProperties properties) {
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        int multiplier = Math.max(1, properties.getBackoff().getMultiplier());
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .retryOn(SocketTimeoutException.class)
                .retryOn(ConnectException.class)
                .exponentialBackoff(
                        properties.getBackoff().getInitialInterval(),
                        multiplier,
                        properties.getBackoff().getMaxInterval()
                )
                .withListener(new RetryListener() {
                    @Override
                    public <T, E extends Throwable> void onError(
                            RetryContext context,
                            RetryCallback<T, E> callback,
                            Throwable throwable) {
                        log.warn("AI retry triggered count={} reason={}",
                                context.getRetryCount(),
                                throwable != null ? throwable.getMessage() : "unknown");
                    }
                })
                .build();
    }
}
