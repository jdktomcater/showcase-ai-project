package com.jdktomcat.showcase.ai.code.assistant.service.github;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.domain.entity.PushPayload;
import com.jdktomcat.showcase.ai.code.assistant.dto.ReviewDiffRequest;
import com.jdktomcat.showcase.ai.code.assistant.service.impact.CodeImpactAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommitReviewService {

    @Qualifier("commitWorkflow")
    private final CompiledGraph<CommitTaskState> commitWorkflow;
    private final CodeImpactAnalysisService codeImpactAnalysisService;
    private final GitHubCompareClient gitHubCompareClient;

    @Value("${github.review.max-diff-length:5000}")
    private int maxDiffLength;

    @Value("${code-chunk.impact.max-files-for-analysis:20}")
    private int impactMaxFilesForAnalysis;

    public CommitTaskState reviewPush(PushPayload pushPayload, CompareResponse compareResponse) {
        String repository = pushPayload.getRepository() != null ? pushPayload.getRepository().getFullName() : "unknown";
        log.info("开始评审 Push repository={} files={} additions={} deletions={}",
                repository,
                compareResponse != null && compareResponse.getFiles() != null ? compareResponse.getFiles().size() : 0,
                compareResponse != null && compareResponse.getFiles() != null ? sumAdditions(compareResponse.getFiles()) : 0,
                compareResponse != null && compareResponse.getFiles() != null ? sumDeletions(compareResponse.getFiles()) : 0);
        
        CommitTaskState initialState = buildInitialState(pushPayload, compareResponse);
        log.debug("初始状态构建完成 repository={} sha={} diff 长度={}", 
                initialState.getRepository(), initialState.getSha(), 
                initialState.getDiff() != null ? initialState.getDiff().length() : 0);
        
        log.info("开始执行 LangGraph4j 工作流 repository={}", repository);
        CommitTaskState finalState = commitWorkflow.invoke(initialState.toMap()).orElse(initialState);
        log.info("提交评审完成 repository={} sha={} decision={} passed={}",
                finalState.getRepository(), finalState.getSha(), finalState.getDecision(), finalState.isPassed());
        return finalState;
    }

    public CommitTaskState reviewDiff(ReviewDiffRequest request) {
        log.info("开始评审手动 diff repository={} branch={} sha={}",
                request.getRepository(), request.getBranch(), request.getSha());
        CommitTaskState initialState = buildInitialState(request);
        CommitTaskState finalState = commitWorkflow.invoke(initialState.toMap()).orElse(initialState);
        log.info("手动 diff 评审完成 repository={} sha={} decision={} passed={}",
                finalState.getRepository(), finalState.getSha(), finalState.getDecision(), finalState.isPassed());
        return finalState;
    }

    private CommitTaskState buildInitialState(PushPayload pushPayload, CompareResponse compareResponse) {
        CommitTaskState state = new CommitTaskState();
        PushPayload.Commit latestCommit = getLatestCommit(pushPayload.getCommits());
        List<CompareResponse.FileDiff> files = compareResponse == null ? List.of() : compareResponse.getFiles();

        state.setRepository(pushPayload.getRepository() == null ? "" : pushPayload.getRepository().getFullName());
        state.setBranch(extractBranch(pushPayload.getRef()));
        state.setSha(pushPayload.getAfter());
        state.setAuthor(latestCommit != null && latestCommit.getAuthor() != null ? latestCommit.getAuthor().getName() : "");
        state.setEmail(latestCommit != null && latestCommit.getAuthor() != null ? latestCommit.getAuthor().getEmail() : "");
        state.setMessage(buildCommitMessage(pushPayload.getCommits()));
        state.setCompareUrl(pushPayload.getCompare());
        state.setChangedFiles(files.size());
        state.setAdditions(sumAdditions(files));
        state.setDeletions(sumDeletions(files));
        state.setCompareResponse(compareResponse);
        
        log.debug("开始构建 Diff 内容 files={} maxLength={}", files.size(), maxDiffLength);
        state.setDiff(buildDiff(compareResponse));
        
        log.debug("开始调用代码影响面分析服务 repository={}", state.getRepository());
        applyImpactAnalysis(state, compareResponse);
        log.debug("代码影响面分析完成 repository={} summary 长度={}", 
                state.getRepository(), 
                state.getCodeImpactSummary() != null ? state.getCodeImpactSummary().length() : 0);
        
        state.setNeedRetry(false);
        state.setPassed(false);
        state.setDecision("PENDING");
        
        log.debug("初始状态构建完成 sha={} author={} files={} additions={} deletions={}",
                state.getSha(), state.getAuthor(), state.getChangedFiles(), state.getAdditions(), state.getDeletions());
        return state;
    }

    private CommitTaskState buildInitialState(ReviewDiffRequest request) {
        CommitTaskState state = new CommitTaskState();
        CompareResponse compareResponse = resolveCompareResponse(request);
        List<CompareResponse.FileDiff> files = compareResponse == null ? List.of() : compareResponse.getFiles();
        state.setRepository(defaultString(request.getRepository()));
        state.setBranch(defaultString(request.getBranch()));
        state.setSha(defaultString(request.getSha()));
        state.setAuthor(defaultString(request.getAuthor()));
        state.setEmail(defaultString(request.getEmail()));
        state.setMessage(defaultString(request.getMessage()));
        state.setCompareUrl(defaultString(request.getCompareUrl()));
        state.setCompareResponse(compareResponse);
        state.setDiff(normalizeDiff(request.getDiff()));
        state.setChangedFiles(request.getChangedFiles() != null ? request.getChangedFiles() : files.size());
        state.setAdditions(request.getAdditions() != null ? request.getAdditions() : sumAdditions(files));
        state.setDeletions(request.getDeletions() != null ? request.getDeletions() : sumDeletions(files));
        applyImpactAnalysis(state, compareResponse);
        state.setNeedRetry(false);
        state.setPassed(false);
        state.setDecision("PENDING");
        return state;
    }

    private void applyImpactAnalysis(CommitTaskState state, CompareResponse compareResponse) {
        if (shouldSkipHeavyImpactAnalysis(compareResponse)) {
            int fileCount = compareResponse != null && compareResponse.getFiles() != null ? compareResponse.getFiles().size() : 0;
            String quickSummary = String.format("""
                    ## 代码依赖图影响面摘要
                    - 仓库：%s
                    - 变更文件数：%s
                    - 已启用快速模式：变更文件数超过阈值（%s），跳过重型依赖图/影响链路查询以降低时延。
                    - 建议：如需完整影响面，请调大 code-chunk.impact.max-files-for-analysis 并重试。
                    """,
                    defaultString(state.getRepository()),
                    fileCount,
                    Math.max(1, impactMaxFilesForAnalysis)
            );
            state.setCodeImpactSummary(quickSummary);
            state.setAffectedEntryPoints(List.of());
            return;
        }
        CodeImpactAnalysisService.ImpactAnalysisResult impactAnalysis =
                codeImpactAnalysisService.analyzeImpact(state.getRepository(), compareResponse);
        state.setCodeImpactSummary(impactAnalysis.summary());
        state.setAffectedEntryPoints(impactAnalysis.affectedEntryPoints());
    }

    private boolean shouldSkipHeavyImpactAnalysis(CompareResponse compareResponse) {
        if (compareResponse == null || compareResponse.getFiles() == null) {
            return false;
        }
        int safeMaxFiles = Math.max(1, impactMaxFilesForAnalysis);
        return compareResponse.getFiles().size() > safeMaxFiles;
    }

    private CompareResponse resolveCompareResponse(ReviewDiffRequest request) {
        if (StringUtils.isBlank(request.getCompareUrl())) {
            return null;
        }
        try {
            return gitHubCompareClient.fetchCompareByUrl(request.getRepository(), request.getCompareUrl());
        } catch (Exception ex) {
            log.warn("通过 compareUrl 拉取 Compare 数据失败 repository={} compareUrl={}",
                    request.getRepository(), request.getCompareUrl(), ex);
            return null;
        }
    }

    private PushPayload.Commit getLatestCommit(List<PushPayload.Commit> commits) {
        if (commits == null || commits.isEmpty()) {
            return null;
        }
        return commits.get(commits.size() - 1);
    }

    private String buildCommitMessage(List<PushPayload.Commit> commits) {
        if (commits == null || commits.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (PushPayload.Commit commit : commits) {
            if (StringUtils.isBlank(commit.getMessage())) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n---\n");
            }
            builder.append(commit.getMessage().trim());
        }
        return builder.toString();
    }

    private Integer sumAdditions(List<CompareResponse.FileDiff> files) {
        if (files == null) {
            return 0;
        }
        return files.stream()
                .map(CompareResponse.FileDiff::getAdditions)
                .filter(Objects::nonNull)
                .reduce(0, Integer::sum);
    }

    private Integer sumDeletions(List<CompareResponse.FileDiff> files) {
        if (files == null) {
            return 0;
        }
        return files.stream()
                .map(CompareResponse.FileDiff::getDeletions)
                .filter(Objects::nonNull)
                .reduce(0, Integer::sum);
    }

    private String buildDiff(CompareResponse compareResponse) {
        if (compareResponse == null || compareResponse.getFiles() == null || compareResponse.getFiles().isEmpty()) {
            return "No file diff available.";
        }

        StringBuilder builder = new StringBuilder();
        for (CompareResponse.FileDiff file : compareResponse.getFiles()) {
            builder.append("File: ").append(file.getFilename()).append('\n')
                    .append("Status: ").append(file.getStatus()).append('\n')
                    .append("Additions: ").append(Objects.toString(file.getAdditions(), "0")).append('\n')
                    .append("Deletions: ").append(Objects.toString(file.getDeletions(), "0")).append('\n');

            if (StringUtils.isNotBlank(file.getPatch())) {
                builder.append(file.getPatch()).append('\n');
            } else {
                builder.append("[patch omitted by GitHub compare API]").append('\n');
            }
            builder.append('\n');

            if (builder.length() >= maxDiffLength) {
                builder.append("[diff truncated to fit model context]");
                break;
            }
        }

        if (builder.length() > maxDiffLength) {
            return builder.substring(0, maxDiffLength);
        }
        return builder.toString();
    }

    private String normalizeDiff(String diff) {
        if (StringUtils.isBlank(diff)) {
            return "No file diff available.";
        }
        if (diff.length() <= maxDiffLength) {
            return diff;
        }
        return diff.substring(0, maxDiffLength) + "\n[diff truncated to fit model context]";
    }

    private String defaultString(String value) {
        return StringUtils.defaultString(value);
    }

    private String extractBranch(String ref) {
        if (StringUtils.isBlank(ref)) {
            return "";
        }
        return ref.replace("refs/heads/", "");
    }
}
