package com.jdktomcat.showcase.ai.aletheia.dto;

import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class AnalysisRequest {

    private AnalysisDomain domain = AnalysisDomain.PERFORMANCE;

    @NotBlank
    private String question;

    private String serviceName;

    private String endpointKeyword;

    private String traceId;

    @Min(1)
    @Max(1440)
    private Integer durationMinutes;

    public AnalysisDomain getDomain() {
        return domain;
    }

    public void setDomain(AnalysisDomain domain) {
        this.domain = domain;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getEndpointKeyword() {
        return endpointKeyword;
    }

    public void setEndpointKeyword(String endpointKeyword) {
        this.endpointKeyword = endpointKeyword;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }
}
