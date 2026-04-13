package com.jdktomcat.showcase.ai.code.assistant.agent;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class SecurityAgent implements NodeAction<CommitTaskState> {

    private final ChatModel chatModel;

    public SecurityAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 根据 diff 生成规范审查报告
     */
    public void review(CommitTaskState state) {
        String prompt = String.format("""
                你是安全审计专家，请依据 OWASP Top 10 和常见服务端安全问题审查以下代码改动。
                请用中文并按以下 Markdown 结构输出：
                ## 结论
                风险等级：低风险 / 中风险 / 高风险
                
                ## 发现
                - 最多 3 条，重点关注注入、鉴权、敏感信息泄露、越权、反序列化、资源泄露
                
                ## 建议
                - 最多 3 条；如果没有明显问题，明确写“未发现阻断发布的安全风险”
                
                提交上下文：
                - 仓库：%s
                - 分支：%s
                - 提交说明：%s
                
                Diff:
                %s
                """, state.getRepository(), state.getBranch(), state.getMessage(), state.getDiff());

        String review = chatModel.call(prompt);
        log.info("安全审查完成，报告长度 {} 字", review.length());
        state.setSecurityReport(review);
    }

    @Override
    public Map<String, Object> apply(CommitTaskState commitState) {
        review(commitState);
        return commitState.toMap();
    }
}