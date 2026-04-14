package com.jdktomcat.showcase.ai.code.assistant.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

        try {
            String response = chatModel.call(prompt);
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
        }
    }
}
