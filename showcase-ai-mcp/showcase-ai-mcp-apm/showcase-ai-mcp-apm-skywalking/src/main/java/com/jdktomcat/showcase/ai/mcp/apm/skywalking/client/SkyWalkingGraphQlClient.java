package com.jdktomcat.showcase.ai.mcp.apm.skywalking.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.mcp.apm.skywalking.config.SkyWalkingProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class SkyWalkingGraphQlClient {

    private final SkyWalkingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SkyWalkingGraphQlClient(SkyWalkingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    public Map<String, Object> query(String operationName, String document, Map<String, Object> variables) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operationName", operationName);
            body.put("query", document);
            body.put("variables", variables == null ? Map.of() : variables);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(buildGraphQlUrl()))
                    .timeout(properties.getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (StringUtils.isNotBlank(properties.getAuthorization())) {
                requestBuilder.header("Authorization", properties.getAuthorization());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new SkyWalkingClientException("SkyWalking GraphQL request failed, status=" + response.statusCode());
            }

            Map<String, Object> responseMap = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            List<Map<String, Object>> errors = castList(responseMap.get("errors"));
            if (errors != null && !errors.isEmpty()) {
                String message = errors.stream()
                        .map(error -> Objects.toString(error.get("message"), "unknown error"))
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("unknown graphql error");
                throw new SkyWalkingClientException(message);
            }

            Map<String, Object> data = castMap(responseMap.get("data"));
            if (data == null) {
                throw new SkyWalkingClientException("SkyWalking GraphQL response has no data section");
            }
            return data;
        } catch (IOException ex) {
            throw new SkyWalkingClientException("Failed to serialize or parse SkyWalking GraphQL payload", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SkyWalkingClientException("Interrupted while calling SkyWalking GraphQL", ex);
        }
    }

    public Map<String, Object> queryWithFallback(GraphQlAttempt primary, GraphQlAttempt fallback) {
        try {
            return query(primary.operationName(), primary.document(), primary.variables());
        } catch (SkyWalkingClientException ex) {
            if (!isSchemaCompatibilityError(ex)) {
                throw ex;
            }
            return query(fallback.operationName(), fallback.document(), fallback.variables());
        }
    }

    private String buildGraphQlUrl() {
        String baseUrl = StringUtils.removeEnd(properties.getBaseUrl(), "/");
        String graphqlPath = properties.getGraphqlPath().startsWith("/")
                ? properties.getGraphqlPath()
                : "/" + properties.getGraphqlPath();
        return baseUrl + graphqlPath;
    }

    private boolean isSchemaCompatibilityError(SkyWalkingClientException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Cannot query field")
                || message.contains("Unknown argument")
                || message.contains("Unknown type")
                || message.contains("FieldUndefined");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return null;
    }

    public record GraphQlAttempt(String operationName, String document, Map<String, Object> variables) {
    }
}
