package com.jdktomcat.showcase.ai.code.assistant.controller;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.dto.ReviewDiffRequest;
import com.jdktomcat.showcase.ai.code.assistant.dto.ReviewDiffResponse;
import com.jdktomcat.showcase.ai.code.assistant.service.github.CommitReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final CommitReviewService commitReviewService;

    @PostMapping("/diff")
    public ReviewDiffResponse reviewDiff(@Valid @RequestBody ReviewDiffRequest request) {
        log.info("收到手动 diff 审核请求 repository={} branch={} sha={}",
                request.getRepository(), request.getBranch(), request.getSha());
        CommitTaskState finalState = commitReviewService.reviewDiff(request);
        return ReviewDiffResponse.builder()
                .success(true)
                .repository(finalState.getRepository())
                .branch(finalState.getBranch())
                .sha(finalState.getSha())
                .decision(finalState.getDecision())
                .passed(finalState.isPassed())
                .finalReport(finalState.getFinalReport())
                .telegramMessage(finalState.getTelegramMessage())
                .codeImpactSummary(finalState.getCodeImpactSummary())
                .affectedEntryPoints(finalState.getAffectedEntryPoints())
                .businessReport(finalState.getBusinessReport())
                .conventionReport(finalState.getConventionReport())
                .performanceReport(finalState.getPerformanceReport())
                .securityReport(finalState.getSecurityReport())
                .build();
    }
}
