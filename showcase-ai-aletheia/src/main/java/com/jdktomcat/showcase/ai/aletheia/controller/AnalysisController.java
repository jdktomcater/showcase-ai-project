package com.jdktomcat.showcase.ai.aletheia.controller;

import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisResponse;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceLogAnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.TraceLogAnalysisResponse;
import com.jdktomcat.showcase.ai.aletheia.service.AletheiaAnalysisService;
import com.jdktomcat.showcase.ai.aletheia.service.TraceLogAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/aletheia/analysis")
public class AnalysisController {

    private final AletheiaAnalysisService analysisService;
    private final TraceLogAnalysisService traceLogAnalysisService;

    public AnalysisController(AletheiaAnalysisService analysisService,
                              TraceLogAnalysisService traceLogAnalysisService) {
        this.analysisService = analysisService;
        this.traceLogAnalysisService = traceLogAnalysisService;
    }

    @PostMapping
    public AnalysisResponse analyze(@Valid @RequestBody AnalysisRequest request) {
        return analysisService.analyze(request);
    }

    /**
     * SkyWalking 链路日志智能分析入口。
     * <p>
     * 调用方既可以传入 {@code traceId} 直接定位单条链路，
     * 也可以仅传 {@code serviceName}（可叠加 {@code endpointKeyword}），由聚合层自动采样慢 Trace 后再下钻。
     */
    @PostMapping("/trace-log")
    public TraceLogAnalysisResponse analyzeTraceLog(@Valid @RequestBody TraceLogAnalysisRequest request) {
        return traceLogAnalysisService.analyze(request);
    }
}
