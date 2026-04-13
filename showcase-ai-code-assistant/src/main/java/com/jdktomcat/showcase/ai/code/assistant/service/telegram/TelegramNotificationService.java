package com.jdktomcat.showcase.ai.code.assistant.service.telegram;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {

    private final RestTemplate restTemplate;

    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.bot.chat-id:}")
    private String chatId;

    public void sendCommitReview(CommitTaskState state) {
        if (StringUtils.isAnyBlank(botToken, chatId)) {
            log.warn("Telegram 未配置 token 或 chat-id，跳过提交结果通知");
            return;
        }

        String message = buildMessage(state);
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        
        log.debug("准备发送 Telegram 通知 repository={} sha={} message 长度={}", 
                state.getRepository(), state.getSha(), message.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", message);
        body.put("disable_web_page_preview", true);

        try {
            var response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            log.info("Telegram 提交结果通知已发送 repository={} sha={} responseStatus={}", 
                    state.getRepository(), state.getSha(), response.getStatusCode());
        } catch (Exception e) {
            log.error("发送 Telegram 通知失败 repository={} sha={}", state.getRepository(), state.getSha(), e);
            throw e;
        }
    }

    private String buildMessage(CommitTaskState state) {
        StringBuilder builder = new StringBuilder();
        builder.append("GitHub 提交评审结果").append('\n')
                .append("仓库: ").append(state.getRepository()).append('\n')
                .append("分支: ").append(state.getBranch()).append('\n')
                .append("提交: ").append(state.getSha()).append('\n')
                .append("描述: ").append(state.getMessage()).append('\n')
                .append("作者: ").append(state.getAuthor()).append('\n')
                .append("结论: ").append(state.getDecision()).append('\n');

        if (StringUtils.isNotBlank(state.getTelegramMessage())) {
            builder.append('\n').append(state.getTelegramMessage()).append('\n');
        } else if (StringUtils.isNotBlank(state.getFinalReport())) {
            builder.append('\n').append(state.getFinalReport()).append('\n');
        }

        if (StringUtils.isNotBlank(state.getCompareUrl())) {
            builder.append('\n').append("Compare: ").append(state.getCompareUrl());
        }

        return builder.toString();
    }
}
