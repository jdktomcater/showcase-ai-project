package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubCompareClientTest {

    @Test
    void shouldFetchCompareUsingGitHubComparePageUrl() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        GitHubCompareClient client = new GitHubCompareClient(restTemplate);
        ReflectionTestUtils.setField(client, "githubToken", "test-token");

        CompareResponse compareResponse = new CompareResponse();
        when(restTemplate.exchange(
                eq("https://api.github.com/repos/jdktomcater/showcase-pay/compare/490a6e62b5ea...8e1ff9278c2d"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(CompareResponse.class)
        )).thenReturn(ResponseEntity.ok(compareResponse));

        CompareResponse actual = client.fetchCompareByUrl(
                "jdktomcater/showcase-pay",
                "https://github.com/jdktomcater/showcase-pay/compare/490a6e62b5ea...8e1ff9278c2d"
        );

        assertThat(actual).isSameAs(compareResponse);
    }
}
