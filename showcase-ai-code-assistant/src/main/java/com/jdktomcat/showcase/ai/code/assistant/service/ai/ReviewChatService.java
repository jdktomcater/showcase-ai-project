package com.jdktomcat.showcase.ai.code.assistant.service.ai;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewChatService {

    private final ObjectProvider<ChatModel> chatModelProvider;

    @Value("${app.ai.review.enabled:true}")
    private boolean aiReviewEnabled;

    @Value("${app.ai.review.fail-open:true}")
    private boolean failOpen;

    @Value("${app.ai.ollama.max-concurrent-calls:1}")
    private int maxConcurrentCalls = 1;

    @Value("${app.ai.ollama.acquire-timeout:20s}")
    private Duration acquireTimeout = Duration.ofSeconds(20);

    @Value("${app.ai.ollama.prompt-max-chars:12000}")
    private int promptMaxChars = 12000;

    private Semaphore callSemaphore;

    @PostConstruct
    void initSemaphore() {
        int permits = Math.max(1, maxConcurrentCalls);
        this.callSemaphore = new Semaphore(permits, true);
        log.info("ReviewChatService initialized permits={} acquireTimeout={} promptMaxChars={}",
                permits, acquireTimeout, promptMaxChars);
    }

    public String callOrFallback(String purpose, String prompt, Supplier<String> fallbackSupplier) {
        if (!aiReviewEnabled) {
            log.info("AI review disabled, using fallback purpose={}", purpose);
            return fallbackSupplier.get();
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("ChatModel bean not available, using fallback purpose={}", purpose);
            return fallbackSupplier.get();
        }

        String normalizedPrompt = truncatePrompt(prompt);
        Semaphore semaphore = ensureSemaphore();
        boolean acquired = acquirePermit(semaphore, purpose);
        if (!acquired) {
            return fallbackSupplier.get();
        }

        long startAt = System.nanoTime();
        try {
            String response = chatModel.call(normalizedPrompt);
            if (StringUtils.isBlank(response)) {
                log.warn("ChatModel returned blank response, using fallback purpose={}", purpose);
                return fallbackSupplier.get();
            }
            return response;
        } catch (Exception ex) {
            log.warn("ChatModel call failed, using fallback purpose={}", purpose, ex);
            if (!failOpen) {
                throw ex;
            }
            return fallbackSupplier.get();
        } finally {
            semaphore.release();
            long elapsedMs = (System.nanoTime() - startAt) / 1_000_000;
            log.debug("ChatModel call finished purpose={} elapsed={}ms", purpose, elapsedMs);
        }
    }

    private Semaphore ensureSemaphore() {
        if (callSemaphore != null) {
            return callSemaphore;
        }
        synchronized (this) {
            if (callSemaphore == null) {
                int permits = Math.max(1, maxConcurrentCalls);
                callSemaphore = new Semaphore(permits, true);
            }
            return callSemaphore;
        }
    }

    private boolean acquirePermit(Semaphore semaphore, String purpose) {
        Duration timeout = acquireTimeout != null ? acquireTimeout : Duration.ofSeconds(20);
        try {
            return semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for Ollama concurrency permit purpose={}", purpose, ex);
            return false;
        }
    }

    private String truncatePrompt(String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return "";
        }
        int safeMaxChars = Math.max(1000, promptMaxChars);
        if (prompt.length() <= safeMaxChars) {
            return prompt;
        }
        log.warn("Prompt too long, truncating from {} to {} chars", prompt.length(), safeMaxChars);
        return prompt.substring(0, safeMaxChars) + "\n...[prompt truncated to reduce local ollama timeout risk]...";
    }
}
