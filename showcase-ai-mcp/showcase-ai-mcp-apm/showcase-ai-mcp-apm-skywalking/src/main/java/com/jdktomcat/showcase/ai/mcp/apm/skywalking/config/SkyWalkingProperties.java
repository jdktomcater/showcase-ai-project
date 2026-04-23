package com.jdktomcat.showcase.ai.mcp.apm.skywalking.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "skywalking")
public class SkyWalkingProperties {

    @NotBlank
    private String baseUrl = "http://localhost:12800";

    @NotBlank
    private String graphqlPath = "/graphql";

    private String authorization;

    private Duration connectTimeout = Duration.ofSeconds(3);

    private Duration requestTimeout = Duration.ofSeconds(20);

    @Min(1)
    @Max(200)
    private int defaultEndpointLimit = 5;

    @Min(1)
    @Max(200)
    private int defaultTraceLimit = 5;

    @Min(1)
    @Max(1440)
    private int defaultDurationMinutes = 30;

    @Min(1)
    @Max(300_000)
    private int defaultMinTraceDurationMs = 500;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getGraphqlPath() {
        return graphqlPath;
    }

    public void setGraphqlPath(String graphqlPath) {
        this.graphqlPath = graphqlPath;
    }

    public String getAuthorization() {
        return authorization;
    }

    public void setAuthorization(String authorization) {
        this.authorization = authorization;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getDefaultEndpointLimit() {
        return defaultEndpointLimit;
    }

    public void setDefaultEndpointLimit(int defaultEndpointLimit) {
        this.defaultEndpointLimit = defaultEndpointLimit;
    }

    public int getDefaultTraceLimit() {
        return defaultTraceLimit;
    }

    public void setDefaultTraceLimit(int defaultTraceLimit) {
        this.defaultTraceLimit = defaultTraceLimit;
    }

    public int getDefaultDurationMinutes() {
        return defaultDurationMinutes;
    }

    public void setDefaultDurationMinutes(int defaultDurationMinutes) {
        this.defaultDurationMinutes = defaultDurationMinutes;
    }

    public int getDefaultMinTraceDurationMs() {
        return defaultMinTraceDurationMs;
    }

    public void setDefaultMinTraceDurationMs(int defaultMinTraceDurationMs) {
        this.defaultMinTraceDurationMs = defaultMinTraceDurationMs;
    }
}
