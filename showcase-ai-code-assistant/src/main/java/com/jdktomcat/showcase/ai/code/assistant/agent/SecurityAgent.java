package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.service.ai.ReviewChatService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class SecurityAgent implements NodeAction<CommitTaskState> {

    private final ReviewChatService reviewChatService;

    public SecurityAgent(ReviewChatService reviewChatService) {
        this.reviewChatService = reviewChatService;
    }

    /**
     * 根据 diff 生成规范审查报告
     */
    public void review(CommitTaskState state) {
        String prompt = String.format("""
                你是安全审计专家。基于 OWASP Top 10 和服务端常见漏洞审查以下 Diff。
                重点关注：注入、鉴权与越权、敏感信息、反序列化、文件与资源访问控制。
                仅用中文，严格按以下 Markdown 输出：
                总字数尽量控制在 220 字内。
                ## 结论
                风险等级：低风险 / 中风险 / 高风险
                
                ## 发现
                - 最多 3 条
                
                ## 建议
                - 最多 3 条；无阻断风险时写“未发现阻断发布的安全风险”
                
                提交上下文：
                - 仓库：%s
                - 分支：%s
                - 提交说明：%s
                
                Diff:
                %s
                """, state.getRepository(), state.getBranch(), state.getMessage(), state.getDiff());

        String review = reviewChatService.callOrFallback(
                "security-review",
                prompt,
                () -> """
                        ## 结论
                        风险等级：低风险

                        ## 发现
                        - AI 模型当前不可用，未完成自动安全审查

                        ## 建议
                        - 模型恢复后重新执行安全审查，并人工关注鉴权、注入和敏感信息处理
                        """
        );
        log.info("安全审查完成，报告长度 {} 字", review.length());
        state.setSecurityReport(review);
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        review(commitState);
        return commitState.toMap();
    }
}
