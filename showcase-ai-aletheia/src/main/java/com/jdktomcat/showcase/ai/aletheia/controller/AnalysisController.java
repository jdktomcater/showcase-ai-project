package com.jdktomcat.showcase.ai.aletheia.controller;

import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisResponse;
import com.jdktomcat.showcase.ai.aletheia.service.AletheiaAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/aletheia/analysis")
public class AnalysisController {

    private final AletheiaAnalysisService analysisService;

    public AnalysisController(AletheiaAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping
    public AnalysisResponse analyze(@Valid @RequestBody AnalysisRequest request) {
        return analysisService.analyze(request);
    }
}
