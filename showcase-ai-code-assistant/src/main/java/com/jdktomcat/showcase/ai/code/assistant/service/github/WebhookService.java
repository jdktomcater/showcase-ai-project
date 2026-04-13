package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.PushPayload;
import com.jdktomcat.showcase.ai.code.assistant.service.telegram.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final CommitReviewService commitReviewService;
    private final TelegramNotificationService telegramNotificationService;

    @Value("${github.webhook.secret}")
    private String webhookSecret;

    @Value("${github.api.token}")
    private String githubToken;

    public boolean verifySignature(String payload, String signatureHeader) {
        log.debug("验证 Webhook 签名 signatureHeader={}", signatureHeader);
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Webhook 签名头为空");
            return false;
        }
        String expected = "sha256=" + hmacSha256(payload, webhookSecret);
        boolean matches = expected.equals(signatureHeader);
        log.debug("Webhook 签名验证结果={} expected={} actual={}", matches, expected, signatureHeader);
        return matches;
    }

    @Async("webhookTaskExecutor")
    public void handlePushEvent(String payload) {
        log.info("收到 GitHub Push 事件，payload 长度={}", payload != null ? payload.length() : 0);
        try {
            PushPayload pushPayload = objectMapper.readValue(payload, PushPayload.class);
            String fullName = pushPayload.getRepository().getFullName();
            String before = pushPayload.getBefore();
            String after = pushPayload.getAfter();
            log.info("解析 Push Payload 成功 repository={} branch={} before={} after={}", 
                    fullName, pushPayload.getRef(), before, after);
            
            log.debug("开始获取 Compare 数据 repository={} before={} after={}", fullName, before, after);
            CompareResponse compareResponse = fetchCompare(fullName, before, after);
            log.info("Compare 数据获取完成 repository={} files={}", 
                    fullName, compareResponse != null && compareResponse.getFiles() != null ? compareResponse.getFiles().size() : 0);
            
            log.debug("开始执行提交评审 repository={}", fullName);
            CommitTaskState finalState = commitReviewService.reviewPush(pushPayload, compareResponse);
            log.info("提交评审完成 repository={} branch={} decision={} passed={}",
                    fullName, pushPayload.getRef(), finalState.getDecision(), finalState.isPassed());
            
            log.debug("开始发送 Telegram 通知 repository={}", fullName);
            telegramNotificationService.sendCommitReview(finalState);
            log.info("GitHub push 处理完成 repository={} branch={} decision={}",
                    fullName, pushPayload.getRef(), finalState.getDecision());
        } catch (Exception e) {
            log.error("GitHub push 处理失败", e);
        }
    }

    public CompareResponse fetchCompare(String fullName, String before, String after) {
        String url = "https://api.github.com/repos/" + fullName + "/compare/" + before + "..." + after;
        log.debug("调用 GitHub Compare API url={}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(githubToken);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.add("X-GitHub-Api-Version", "2022-11-28");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<CompareResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    CompareResponse.class
            );
            log.debug("GitHub Compare API 响应状态={} repository={}", response.getStatusCode(), fullName);
            return response.getBody();
        } catch (Exception e) {
            log.error("GitHub Compare API 调用失败 repository={} url={}", fullName, url, e);
            throw e;
        }
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC calculation failed", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        Formatter formatter = new Formatter();
        for (byte b : bytes) {
            formatter.format("%02x", b);
        }
        String result = formatter.toString();
        formatter.close();
        return result;
    }
}
