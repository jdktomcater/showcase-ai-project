package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.PushPayload;
import com.jdktomcat.showcase.ai.code.assistant.service.telegram.TelegramNotificationService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookReviewAsyncServiceTest {

    @Test
    void shouldReviewAndSendNotification() {
        CommitReviewService commitReviewService = mock(CommitReviewService.class);
        TelegramNotificationService telegramNotificationService = mock(TelegramNotificationService.class);
        WebhookReviewAsyncService service = new WebhookReviewAsyncService(commitReviewService, telegramNotificationService);

        PushPayload pushPayload = buildPushPayload();
        CompareResponse compareResponse = new CompareResponse();
        CommitTaskState finalState = new CommitTaskState();
        finalState.setDecision("PASS");
        when(commitReviewService.reviewPush(pushPayload, compareResponse)).thenReturn(finalState);

        service.reviewAndNotify(pushPayload, compareResponse);

        verify(commitReviewService).reviewPush(pushPayload, compareResponse);
        verify(telegramNotificationService).sendCommitReview(finalState);
    }

    @Test
    void shouldSkipNotificationWhenReviewFails() {
        CommitReviewService commitReviewService = mock(CommitReviewService.class);
        TelegramNotificationService telegramNotificationService = mock(TelegramNotificationService.class);
        WebhookReviewAsyncService service = new WebhookReviewAsyncService(commitReviewService, telegramNotificationService);

        doThrow(new IllegalStateException("boom")).when(commitReviewService).reviewPush(any(), any());

        service.reviewAndNotify(buildPushPayload(), new CompareResponse());

        verify(telegramNotificationService, never()).sendCommitReview(any());
    }

    private PushPayload buildPushPayload() {
        PushPayload payload = new PushPayload();
        payload.setRef("refs/heads/main");
        PushPayload.Repository repository = new PushPayload.Repository();
        repository.setFullName("jdktomcater/showcase-pay");
        payload.setRepository(repository);
        return payload;
    }
}
