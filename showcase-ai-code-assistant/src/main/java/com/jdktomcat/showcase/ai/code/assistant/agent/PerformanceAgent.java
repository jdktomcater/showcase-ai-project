package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class PerformanceAgent implements NodeAction<CommitTaskState> {

    private final ReviewChatService reviewChatService;

    public PerformanceAgent(ReviewChatService reviewChatService) {
        this.reviewChatService = reviewChatService;
    }

    /** 根据 diff 生成规范审查报告 */
    public void review(CommitTaskState state) {
        String prompt = String.format("""
        你是性能风险审查专家，请分析下面的代码改动，识别性能瓶颈、扩展性风险和资源消耗问题。
        请用中文并按以下 Markdown 结构输出：
        ## 结论
        风险等级：低风险 / 中风险 / 高风险
        
        ## 发现
        - 最多 3 条，重点关注循环、IO、数据库调用、缓存、并发、远程调用
        
        ## 建议
        - 最多 3 条，给出可执行优化建议；如果没有明显问题，明确写“未发现显著性能风险”
        
        提交上下文：
        - 仓库：%s
        - 分支：%s
        - 提交说明：%s
        
        Diff:
        %s
        """, state.getRepository(), state.getBranch(), state.getMessage(), state.getDiff());
        String review = reviewChatService.callOrFallback(
                "performance-review",
                prompt,
                () -> """
                        ## 结论
                        风险等级：低风险

                        ## 发现
                        - AI 模型当前不可用，未完成自动性能审查

                        ## 建议
                        - 模型恢复后重新执行性能审查，重点关注长链路、数据库和远程调用
                        """
        );
        log.info("性能审查完成，报告长度 {} 字", review.length());
        state.setPerformanceReport(review);
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        review(commitState);
        return commitState.toMap();
    }
}
