package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.service.impact.CodeImpactAnalysisService;
import com.jdktomcat.showcase.ai.code.assistant.service.telegram.TelegramNotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookServiceTest {

    @Test
    void shouldRebuildDependencyGraphBeforeReviewOnPushEvent() {
        GitHubCompareClient gitHubCompareClient = mock(GitHubCompareClient.class);
        CommitReviewService commitReviewService = mock(CommitReviewService.class);
        CodeImpactAnalysisService codeImpactAnalysisService = mock(CodeImpactAnalysisService.class);
        TelegramNotificationService telegramNotificationService = mock(TelegramNotificationService.class);
        WebhookService webhookService = new WebhookService(
                new ObjectMapper(),
                gitHubCompareClient,
                commitReviewService,
                codeImpactAnalysisService,
                telegramNotificationService
        );

        CompareResponse compareResponse = new CompareResponse();
        CommitTaskState finalState = new CommitTaskState();
        finalState.setDecision("PASS");
        when(gitHubCompareClient.fetchCompare(eq("jdktomcater/showcase-pay"), eq("before-sha"), eq("after-sha")))
                .thenReturn(compareResponse);
        when(commitReviewService.reviewPush(any(), eq(compareResponse)))
                .thenReturn(finalState);

        webhookService.handlePushEvent("""
                {
                  "ref": "refs/heads/main",
                  "before": "before-sha",
                  "after": "after-sha",
                  "repository": {
                    "full_name": "jdktomcater/showcase-pay"
                  },
                  "commits": []
                }
                """);

        InOrder inOrder = inOrder(gitHubCompareClient, codeImpactAnalysisService, commitReviewService, telegramNotificationService);
        inOrder.verify(gitHubCompareClient).fetchCompare("jdktomcater/showcase-pay", "before-sha", "after-sha");
        inOrder.verify(codeImpactAnalysisService).rebuildDependencyGraphBeforeReview("jdktomcater/showcase-pay", compareResponse);
        inOrder.verify(commitReviewService).reviewPush(any(), eq(compareResponse));
        inOrder.verify(telegramNotificationService).sendCommitReview(finalState);
    }

    @Test
    void shouldSkipReviewWhenDependencyGraphRebuildFails() {
        GitHubCompareClient gitHubCompareClient = mock(GitHubCompareClient.class);
        CommitReviewService commitReviewService = mock(CommitReviewService.class);
        CodeImpactAnalysisService codeImpactAnalysisService = mock(CodeImpactAnalysisService.class);
        TelegramNotificationService telegramNotificationService = mock(TelegramNotificationService.class);
        WebhookService webhookService = new WebhookService(
                new ObjectMapper(),
                gitHubCompareClient,
                commitReviewService,
                codeImpactAnalysisService,
                telegramNotificationService
        );

        CompareResponse compareResponse = new CompareResponse();
        when(gitHubCompareClient.fetchCompare(eq("jdktomcater/showcase-pay"), eq("before-sha"), eq("after-sha")))
                .thenReturn(compareResponse);
        doThrow(new IllegalStateException("boom"))
                .when(codeImpactAnalysisService)
                .rebuildDependencyGraphBeforeReview("jdktomcater/showcase-pay", compareResponse);

        webhookService.handlePushEvent("""
                {
                  "ref": "refs/heads/main",
                  "before": "before-sha",
                  "after": "after-sha",
                  "repository": {
                    "full_name": "jdktomcater/showcase-pay"
                  },
                  "commits": []
                }
                """);

        verify(commitReviewService, never()).reviewPush(any(), any());
        verify(telegramNotificationService, never()).sendCommitReview(any());
    }
}
