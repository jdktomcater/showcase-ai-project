package com.jdktomcat.showcase.ai.code.assistant.domain.dto;

import com.jdktomcat.showcase.ai.code.assistant.domain.entity.CompareResponse;
import com.jdktomcat.showcase.ai.code.assistant.utils.JSONUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class CommitTaskState extends AgentState {
    private String repository;
    private String branch;
    private String sha;
    private String author;
    private String email;
    private String message;
    private String diff;
    private String compareUrl;
    private Integer changedFiles;
    private Integer additions;
    private Integer deletions;
    private CompareResponse compareResponse;
    private String codeImpactSummary;
    // 下面的字段会在各 Agent 中填充
    private String conventionReport;
    private String securityReport;
    private String performanceReport;
    private String businessReport;
    private boolean needRetry;
    private boolean passed;
    private String decision;
    private String finalReport;
    private String telegramMessage;

    public CommitTaskState() {
        super(new HashMap<>());
    }

    public CommitTaskState(Map<String, Object> data) {
        super(data);
        CommitTaskState oldState = JSONUtils.parseObject(JSONUtils.toJSONString(data), CommitTaskState.class);
        if (oldState == null) {
            return;
        }
        this.repository = oldState.getRepository();
        this.branch = oldState.getBranch();
        this.sha = oldState.getSha();
        this.author = oldState.getAuthor();
        this.email = oldState.getEmail();
        this.message = oldState.getMessage();
        this.diff = oldState.getDiff();
        this.compareUrl = oldState.getCompareUrl();
        this.changedFiles = oldState.getChangedFiles();
        this.additions = oldState.getAdditions();
        this.deletions = oldState.getDeletions();
        this.compareResponse = oldState.getCompareResponse();
        this.codeImpactSummary = oldState.getCodeImpactSummary();
        this.conventionReport = oldState.getConventionReport();
        this.securityReport = oldState.getSecurityReport();
        this.performanceReport = oldState.getPerformanceReport();
        this.businessReport = oldState.getBusinessReport();
        this.needRetry = oldState.isNeedRetry();
        this.passed = oldState.isPassed();
        this.decision = oldState.getDecision();
        this.finalReport = oldState.getFinalReport();
        this.telegramMessage = oldState.getTelegramMessage();
    }

    public Map<String, Object> toMap() {
        return JSONUtils.convertToMap(this);
    }
}