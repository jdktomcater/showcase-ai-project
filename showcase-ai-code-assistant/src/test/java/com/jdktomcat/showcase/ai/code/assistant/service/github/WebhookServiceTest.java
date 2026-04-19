package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.service.impact.CodeImpactAnalysisService;
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
        CodeImpactAnalysisService codeImpactAnalysisService = mock(CodeImpactAnalysisService.class);
        WebhookReviewAsyncService webhookReviewAsyncService = mock(WebhookReviewAsyncService.class);
        WebhookService webhookService = new WebhookService(
                new ObjectMapper(),
                gitHubCompareClient,
                codeImpactAnalysisService,
                webhookReviewAsyncService
        );

        CompareResponse compareResponse = new CompareResponse();
        when(gitHubCompareClient.fetchCompare(eq("jdktomcater/showcase-pay"), eq("before-sha"), eq("after-sha")))
                .thenReturn(compareResponse);

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

        InOrder inOrder = inOrder(gitHubCompareClient, codeImpactAnalysisService, webhookReviewAsyncService);
        inOrder.verify(gitHubCompareClient).fetchCompare("jdktomcater/showcase-pay", "before-sha", "after-sha");
        inOrder.verify(codeImpactAnalysisService).rebuildDependencyGraphBeforeReview("jdktomcater/showcase-pay", compareResponse);
        inOrder.verify(webhookReviewAsyncService).reviewAndNotify(any(), eq(compareResponse));
    }

    @Test
    void shouldSkipReviewWhenDependencyGraphRebuildFails() {
        GitHubCompareClient gitHubCompareClient = mock(GitHubCompareClient.class);
        CodeImpactAnalysisService codeImpactAnalysisService = mock(CodeImpactAnalysisService.class);
        WebhookReviewAsyncService webhookReviewAsyncService = mock(WebhookReviewAsyncService.class);
        WebhookService webhookService = new WebhookService(
                new ObjectMapper(),
                gitHubCompareClient,
                codeImpactAnalysisService,
                webhookReviewAsyncService
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

        verify(webhookReviewAsyncService, never()).reviewAndNotify(any(), any());
    }
}
