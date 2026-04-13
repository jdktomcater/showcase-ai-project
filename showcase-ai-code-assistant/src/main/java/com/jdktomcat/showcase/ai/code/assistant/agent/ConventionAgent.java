package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ConventionAgent implements NodeAction<CommitTaskState> {

    private final ChatModel chatModel;

    public ConventionAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 根据 diff 生成规范审查报告
     */
    public void review(CommitTaskState state) {
        String prompt = String.format("""
                你是代码规范审查专家。请检查以下提交 diff，并用中文输出 Markdown 报告。
                输出结构必须是：
                ## 结论
                风险等级：低风险 / 中风险 / 高风险
                
                ## 发现
                - 最多 3 条，聚焦命名、可读性、异常处理、可维护性、风格一致性
                
                ## 建议
                - 最多 3 条；如果没有明显问题，明确写“规范层面可接受”
                
                提交上下文：
                - 仓库：%s
                - 分支：%s
                - 提交说明：%s
                
                Diff:
                %s
                """, state.getRepository(), state.getBranch(), state.getMessage(), state.getDiff());

        String review = chatModel.call(prompt);
        log.info("规范审查完成，报告长度 {} 字", review.length());
        state.setConventionReport(review);
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        review(commitState);
        return commitState.toMap();
    }
}