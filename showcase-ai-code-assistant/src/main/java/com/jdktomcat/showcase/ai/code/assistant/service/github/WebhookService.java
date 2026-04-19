package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.PushPayload;
import com.jdktomcat.showcase.ai.code.assistant.service.impact.CodeImpactAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final ObjectMapper objectMapper;
    private final GitHubCompareClient gitHubCompareClient;
    private final CodeImpactAnalysisService codeImpactAnalysisService;
    private final WebhookReviewAsyncService webhookReviewAsyncService;

    @Value("${github.webhook.secret}")
    private String webhookSecret;

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
            log.info("解析 Push Payload 成功 repository={} branch={} before={} after={}", fullName, pushPayload.getRef(), before, after);
            log.debug("开始获取 Compare 数据 repository={} before={} after={}", fullName, before, after);
            CompareResponse compareResponse = gitHubCompareClient.fetchCompare(fullName, before, after);
            log.info("Compare 数据获取完成 repository={} files={}", fullName, compareResponse != null && compareResponse.getFiles() != null ? compareResponse.getFiles().size() : 0);
            log.debug("开始重建依赖图 repository={}", fullName);
            codeImpactAnalysisService.rebuildDependencyGraphBeforeReview(fullName, compareResponse);
            log.info("依赖图重建完成 repository={}", fullName);
            log.debug("开始异步投递代码分析任务 repository={}", fullName);
            webhookReviewAsyncService.reviewAndNotify(pushPayload, compareResponse);
            log.info("GitHub push 处理已进入异步审查阶段 repository={} branch={}", fullName, pushPayload.getRef());
        } catch (Exception e) {
            log.error("GitHub push 处理失败", e);
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
