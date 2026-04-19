package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.PushPayload;
import com.jdktomcat.showcase.ai.code.assistant.service.telegram.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookReviewAsyncService {

    private final CommitReviewService commitReviewService;
    private final TelegramNotificationService telegramNotificationService;

    @Async("webhookTaskExecutor")
    public void reviewAndNotify(PushPayload pushPayload, CompareResponse compareResponse) {
        String repository = pushPayload != null && pushPayload.getRepository() != null
                ? pushPayload.getRepository().getFullName()
                : "unknown";
        String branch = pushPayload != null ? pushPayload.getRef() : "";

        try {
            log.debug("开始异步执行提交评审 repository={} branch={}", repository, branch);
            CommitTaskState finalState = commitReviewService.reviewPush(pushPayload, compareResponse);
            log.info("异步提交评审完成 repository={} branch={} decision={} passed={}",
                    repository, branch, finalState.getDecision(), finalState.isPassed());
            telegramNotificationService.sendCommitReview(finalState);
            log.info("异步审查通知发送完成 repository={} branch={} decision={}",
                    repository, branch, finalState.getDecision());
        } catch (Exception ex) {
            log.error("异步提交评审失败 repository={} branch={}", repository, branch, ex);
        }
    }
}
