package com.jdktomcat.showcase.ai.aletheia.dto;

import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
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

}
