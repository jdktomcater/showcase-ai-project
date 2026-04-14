package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubCompareClient {

    private static final String COMPARE_MARKER = "/compare/";

    private final RestTemplate restTemplate;

    @Value("${github.api.token}")
    private String githubToken;

    public CompareResponse fetchCompare(String fullName, String before, String after) {
        String url = "https://api.github.com/repos/" + fullName + "/compare/" + before + "..." + after;
        log.debug("调用 GitHub Compare API url={}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(githubToken);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.add("X-GitHub-Api-Version", "2022-11-28");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<CompareResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                CompareResponse.class
        );
        return response.getBody();
    }

    public CompareResponse fetchCompareByUrl(String defaultRepository, String compareUrl) {
        CompareRef compareRef = parseCompareUrl(defaultRepository, compareUrl);
        return fetchCompare(compareRef.repository(), compareRef.before(), compareRef.after());
    }

    CompareRef parseCompareUrl(String defaultRepository, String compareUrl) {
        if (StringUtils.isBlank(compareUrl)) {
            throw new IllegalArgumentException("compareUrl is blank");
        }

        URI uri = URI.create(compareUrl.trim());
        String path = StringUtils.defaultString(uri.getPath());
        int compareIndex = path.indexOf(COMPARE_MARKER);
        if (compareIndex < 0) {
            throw new IllegalArgumentException("Invalid compare url: " + compareUrl);
        }

        String repoPath = path.substring(0, compareIndex);
        String comparePart = path.substring(compareIndex + COMPARE_MARKER.length());
        int dotsIndex = comparePart.indexOf("...");
        if (dotsIndex < 0) {
            throw new IllegalArgumentException("Invalid compare ref range: " + compareUrl);
        }

        String repository = normalizeRepositoryPath(repoPath, defaultRepository);
        String before = URLDecoder.decode(comparePart.substring(0, dotsIndex), StandardCharsets.UTF_8);
        String after = URLDecoder.decode(comparePart.substring(dotsIndex + 3), StandardCharsets.UTF_8);
        if (StringUtils.isAnyBlank(repository, before, after)) {
            throw new IllegalArgumentException("Incomplete compare url: " + compareUrl);
        }

        return new CompareRef(repository, before, after);
    }

    private String normalizeRepositoryPath(String repoPath, String defaultRepository) {
        String normalized = StringUtils.removeStart(repoPath, "/");
        if (normalized.startsWith("repos/")) {
            normalized = StringUtils.removeStart(normalized, "repos/");
        }
        if (normalized.split("/").length >= 2) {
            String[] segments = normalized.split("/");
            return segments[0] + "/" + segments[1];
        }
        return StringUtils.defaultIfBlank(normalized, defaultRepository);
    }

    record CompareRef(String repository, String before, String after) {
    }
}
