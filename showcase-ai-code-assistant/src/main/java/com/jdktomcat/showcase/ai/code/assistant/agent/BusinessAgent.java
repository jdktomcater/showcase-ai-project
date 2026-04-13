package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.service.impact.CodeImpactAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class BusinessAgent implements NodeAction<CommitTaskState> {

    private final ChatModel chatModel;
    private final CodeImpactAnalysisService impactAnalysisService;

    public BusinessAgent(ChatModel chatModel, CodeImpactAnalysisService impactAnalysisService) {
        this.chatModel = chatModel;
        this.impactAnalysisService = impactAnalysisService;
    }

    /** 根据 diff 和依赖图进行业务风险审查 */
    public void review(CommitTaskState state) {
        // 构建完整的影响面评估报告（结合依赖图和影响链路）
        String impactSummary = impactAnalysisService.buildFullImpactSummary(
                state.getRepository(), 
                state.getCompareResponse()
        );
        state.setCodeImpactSummary(impactSummary);
        
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

        依赖图影响面摘要：
        %s

        Diff:
        %s
        """, state.getRepository(), state.getBranch(), state.getMessage(), state.getCodeImpactSummary(), state.getDiff());
        String review = chatModel.call(prompt);
//        log.info("业务完整审查完成，报告长度 {} 字", review.length());
        log.info("业务完整审查完成，报告： {} ", review);
        state.setBusinessReport(review);
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitTaskState) throws Exception {
        this.review(commitTaskState);
        return commitTaskState.toMap();
    }
}