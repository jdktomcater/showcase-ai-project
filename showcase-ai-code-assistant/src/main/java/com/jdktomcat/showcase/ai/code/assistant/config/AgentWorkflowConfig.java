package com.jdktomcat.showcase.ai.code.assistant.config;

import com.jdktomcat.showcase.ai.code.assistant.agent.*;
import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import com.jdktomcat.showcase.ai.code.assistant.service.edge.CommitRetryEdgeAction;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 多智能体流程配置：用 LangGraph4j 定义节点流转规则
 */
@Configuration
public class AgentWorkflowConfig {

    @Bean
    public CompiledGraph<CommitTaskState> commitWorkflow(
            BusinessAgent businessAgent,
            ConventionAgent conventionAgent,
            PerformanceAgent performanceAgent,
            SecurityAgent securityAgent,
            CommitResultAgent commitResultAgent,
            CommitRetryEdgeAction commitRetryEdgeAction,
            CommitTaskStateFactory stateFactory
    ) throws GraphStateException {

        // 1. 创建状态图构建器（指定状态类型）
        StateGraph<CommitTaskState> graphBuilder = new StateGraph<>(stateFactory);

        // 2. 添加节点（每个智能体对应一个节点）
        graphBuilder.addNode("business", node_async(businessAgent));
        graphBuilder.addNode("convention", node_async(conventionAgent));
        graphBuilder.addNode("performance", node_async(performanceAgent));
        graphBuilder.addNode("security", node_async(securityAgent));
        graphBuilder.addNode("result", node_async(commitResultAgent));
        graphBuilder.addNode("dispatchReview", node_async(CommitTaskState::toMap));

        // 3. 定义流程流转规则（边）
        // 本地 Ollama 容易在并发大 prompt 下超时，因此四个维度串行执行。
        graphBuilder.addEdge(START, "dispatchReview");
        graphBuilder.addEdge("dispatchReview", "business");
        graphBuilder.addEdge("business", "convention");
        graphBuilder.addEdge("convention", "performance");
        graphBuilder.addEdge("performance", "security");
        graphBuilder.addEdge("security", "result");

        graphBuilder.addConditionalEdges("result",
                AsyncEdgeAction.edge_async(commitRetryEdgeAction),
                Map.of(
                        "retry", "dispatchReview",
                        "end", END
                )
        );

        return graphBuilder.compile();
    }
}
