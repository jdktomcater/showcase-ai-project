package com.jdktomcat.showcase.ai.code.assistant.service.telegram;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
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
import java.util.List;
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
        String reviewOpinion = resolveReviewOpinion(state);
        String entryPointSummary = resolveEntryPointSummary(state);

        StringBuilder builder = new StringBuilder();
        builder.append("GitHub 提交评审结果").append('\n')
                .append("仓库: ").append(StringUtils.defaultString(state.getRepository())).append('\n')
                .append("分支: ").append(StringUtils.defaultString(state.getBranch())).append('\n')
                .append("提交: ").append(StringUtils.defaultString(state.getSha())).append('\n')
                .append("描述: ").append(StringUtils.defaultString(state.getMessage())).append('\n')
                .append("作者: ").append(StringUtils.defaultString(state.getAuthor())).append('\n')
                .append("结论: ").append(StringUtils.defaultIfBlank(state.getDecision(), "UNKNOWN"))
                .append('\n')
                .append('\n')
                .append("评审意见: ").append(reviewOpinion);

        if (StringUtils.isNotBlank(entryPointSummary)) {
            builder.append('\n').append("影响入口点: ").append(entryPointSummary);
        }
        if (StringUtils.isNotBlank(state.getCompareUrl())) {
            builder.append('\n').append('\n').append("Compare: ").append(state.getCompareUrl());
        }

        return builder.toString();
    }

    private String resolveReviewOpinion(CommitTaskState state) {
        String opinion = extractOpinionFromTelegramMessage(state.getTelegramMessage());
        if (StringUtils.isNotBlank(opinion)) {
            return opinion;
        }
        opinion = extractOpinionFromFinalReport(state.getFinalReport());
        if (StringUtils.isNotBlank(opinion)) {
            return opinion;
        }
        return "未提供评审意见，请结合专项报告人工复核。";
    }

    private String extractOpinionFromTelegramMessage(String telegramMessage) {
        if (StringUtils.isBlank(telegramMessage)) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        for (String rawLine : telegramMessage.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("结论:") || line.startsWith("Compare:") || line.startsWith("影响入口点:")) {
                continue;
            }
            if (line.startsWith("原因:")) {
                line = StringUtils.trimToEmpty(line.substring("原因:".length()));
            }
            if (line.isEmpty()) {
                continue;
            }
            if (!normalized.isEmpty()) {
                normalized.append(' ');
            }
            normalized.append(line);
        }
        return normalized.toString();
    }

    private String extractOpinionFromFinalReport(String finalReport) {
        if (StringUtils.isBlank(finalReport)) {
            return "";
        }
        for (String rawLine : finalReport.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("- ")) {
                return line.substring(2).trim();
            }
        }
        String normalized = finalReport.replaceAll("(?m)^##\\s*\\S+\\s*$", "")
                .replace('\n', ' ')
                .replaceAll("\\s{2,}", " ")
                .trim();
        return StringUtils.abbreviate(normalized, 120);
    }

    private String resolveEntryPointSummary(CommitTaskState state) {
        if (state.getAffectedEntryPoints() == null || state.getAffectedEntryPoints().isEmpty()) {
            return extractEntryPointFromTelegramMessage(state.getTelegramMessage());
        }
        return formatAffectedEntryPoints(state.getAffectedEntryPoints());
    }

    private String extractEntryPointFromTelegramMessage(String telegramMessage) {
        if (StringUtils.isBlank(telegramMessage)) {
            return "";
        }
        for (String rawLine : telegramMessage.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!line.startsWith("影响入口点:")) {
                continue;
            }
            return StringUtils.trimToEmpty(line.substring("影响入口点:".length()));
        }
        return "";
    }

    private String formatAffectedEntryPoints(List<AffectedEntryPoint> affectedEntryPoints) {
        return affectedEntryPoints.stream()
                .limit(3)
                .map(this::toCompactDisplayText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    private String toCompactDisplayText(AffectedEntryPoint entryPoint) {
        if (StringUtils.isNotBlank(entryPoint.getRoute())) {
            return entryPoint.getType() + " `" + entryPoint.getRoute() + "`";
        }
        return entryPoint.getType() + " " + entryPoint.getMethodSignature();
    }
}
