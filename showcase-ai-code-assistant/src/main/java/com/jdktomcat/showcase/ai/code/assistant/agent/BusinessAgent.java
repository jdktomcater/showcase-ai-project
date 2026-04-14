package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.dto.AffectedEntryPoint;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import com.jdktomcat.showcase.ai.code.assistant.service.impact.CodeImpactAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BusinessAgent implements NodeAction<CommitTaskState> {

    private final ReviewChatService reviewChatService;
    private final CodeImpactAnalysisService impactAnalysisService;

    public BusinessAgent(ReviewChatService reviewChatService, CodeImpactAnalysisService impactAnalysisService) {
        this.reviewChatService = reviewChatService;
        this.impactAnalysisService = impactAnalysisService;
    }

    /** 根据 diff 和依赖图进行业务风险审查 */
    public void review(CommitTaskState state) {
        if (state.getCodeImpactSummary() == null || state.getCodeImpactSummary().isBlank()) {
            CodeImpactAnalysisService.ImpactAnalysisResult impactAnalysis = impactAnalysisService.analyzeImpact(
                    state.getRepository(),
                    state.getCompareResponse()
            );
            state.setCodeImpactSummary(impactAnalysis.summary());
            state.setAffectedEntryPoints(impactAnalysis.affectedEntryPoints());
        }
        
        String prompt = String.format("""
        你是业务风险审查专家，请基于提交信息和代码 diff 识别业务逻辑层面的变更风险。
        结合"依赖图影响面摘要"和"业务影响链路"分析：
        1. 哪些模块、实现类、继承体系、接口调用方可能被波及
        2. 接口/抽象类变更带来的实现类兼容性影响
        3. 从入口点（HTTP/RPC/MQ/定时任务/事件）出发的完整调用链路
        4. 变更是否影响核心业务流程
        
        请严格使用中文，并按照以下 Markdown 结构输出：
        ## 结论
        风险等级：低风险 / 中风险 / 高风险

        ## 发现
        - 最多 5 条，聚焦业务流程、边界条件、兼容性、回滚影响、入口点覆盖

        ## 建议
        - 最多 5 条，可执行建议；如果没有明显风险，明确写"未发现阻断发布的业务风险"

        提交上下文：
        - 仓库：%s
        - 分支：%s
        - 提交说明：%s

        关联入口点：
        %s

        依赖图影响面摘要：
        %s

        Diff:
        %s
        """,
                state.getRepository(),
                state.getBranch(),
                state.getMessage(),
                formatAffectedEntryPoints(state.getAffectedEntryPoints()),
                state.getCodeImpactSummary(),
                state.getDiff());
        String review = reviewChatService.callOrFallback(
                "business-review",
                prompt,
                () -> """
                        ## 结论
                        风险等级：低风险

                        ## 发现
                        - AI 模型当前不可用，未完成自动业务风险审查

                        ## 建议
                        - 可先依据影响入口点和代码影响面评估报告做人工复核，模型恢复后重新审查
                        """
        );
//        log.info("业务完整审查完成，报告长度 {} 字", review.length());
        log.info("业务完整审查完成，报告： {} ", review);
        state.setBusinessReport(review);
    }

    private String formatAffectedEntryPoints(List<AffectedEntryPoint> affectedEntryPoints) {
        if (affectedEntryPoints == null || affectedEntryPoints.isEmpty()) {
            return "- 未识别到直接相关的入口点";
        }
        StringBuilder builder = new StringBuilder();
        for (AffectedEntryPoint entryPoint : affectedEntryPoints) {
            builder.append("- ")
                    .append(entryPoint.getType());
            if (entryPoint.getRoute() != null && !entryPoint.getRoute().isBlank()) {
                builder.append(" `").append(entryPoint.getRoute()).append('`');
            }
            builder.append(" -> ")
                    .append(entryPoint.getClassName())
                    .append('#')
                    .append(entryPoint.getMethodName())
                    .append('\n');
        }
        return builder.toString().trim();
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitTaskState) throws Exception {
        this.review(commitTaskState);
        return commitTaskState.toMap();
    }
}
