package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ConventionAgent implements NodeAction<CommitTaskState> {

    private final ReviewChatService reviewChatService;

    public ConventionAgent(ReviewChatService reviewChatService) {
        this.reviewChatService = reviewChatService;
    }

    /**
     * 根据 diff 生成规范审查报告
     */
    public void review(CommitTaskState state) {
        String prompt = String.format("""
                你是代码规范审查专家。基于 Diff 识别可读性和可维护性风险。
                重点关注：命名、异常处理、风格一致性、重复逻辑、可测试性。
                仅用中文，严格按以下 Markdown 输出：
                总字数尽量控制在 220 字内。
                ## 结论
                风险等级：低风险 / 中风险 / 高风险
                
                ## 发现
                - 最多 3 条
                
                ## 建议
                - 最多 3 条；无明显问题时写“规范层面可接受”
                
                提交上下文：
                - 仓库：%s
                - 分支：%s
                - 提交说明：%s
                
                Diff:
                %s
                """, state.getRepository(), state.getBranch(), state.getMessage(), state.getDiff());

        String review = reviewChatService.callOrFallback(
                "convention-review",
                prompt,
                () -> """
                        ## 结论
                        风险等级：低风险

                        ## 发现
                        - AI 模型当前不可用，未完成自动规范审查

                        ## 建议
                        - 检查 OLLAMA_BASE_URL、OLLAMA_CHAT_MODEL 或切换到可用云模型后重试
                        """
        );
        log.info("规范审查完成，报告长度 {} 字", review.length());
        state.setConventionReport(review);
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        review(commitState);
        return commitState.toMap();
    }
}
