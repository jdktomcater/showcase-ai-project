package com.jdktomcat.showcase.ai.aletheia.service;

import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import com.jdktomcat.showcase.ai.aletheia.domain.AnalysisDomain;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisRequest;
import com.jdktomcat.showcase.ai.aletheia.dto.AnalysisResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class AletheiaAnalysisService {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final AletheiaProperties properties;
    private final AletheiaPromptService promptService;
    private final McpCapabilityService mcpCapabilityService;

    public AletheiaAnalysisService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                                   AletheiaProperties properties,
                                   AletheiaPromptService promptService,
                                   McpCapabilityService mcpCapabilityService) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.properties = properties;
        this.promptService = promptService;
        this.mcpCapabilityService = mcpCapabilityService;
    }

    public AnalysisResponse analyze(AnalysisRequest request) {
        AnalysisDomain domain = request.getDomain() == null ? AnalysisDomain.PERFORMANCE : request.getDomain();
        boolean modelAvailable = chatClientBuilderProvider.getIfAvailable() != null;
        boolean mcpAvailable = properties.isMcpEnabled() && mcpCapabilityService.hasMcpTools();
        if (domain != AnalysisDomain.PERFORMANCE) {
            return fallbackResponse(domain, modelAvailable, mcpAvailable,
                    "当前版本仅实现性能分析域，后续可扩展日志、指标、配置和发布分析。");
        }
        if (!modelAvailable) {
            return fallbackResponse(domain, false, mcpAvailable,
                    "模型未接入，当前仅完成 Aletheia 聚合骨架。请接入 ChatClient 后启用基于 MCP 的智能分析。");
        }

        String userPrompt = promptService.buildPerformanceUserPrompt(request, mcpAvailable);
        try {
            ChatClient.ChatClientRequestSpec spec = chatClientBuilderProvider.getObject()
                    .build()
                    .prompt()
                    .system(properties.getPerformanceSystemPrompt())
                    .user(userPrompt);
            if (mcpAvailable) {
                ToolCallbackProvider provider = mcpCapabilityService.getToolCallbackProvider();
                if (provider != null) {
                    spec = spec.toolCallbacks(provider);
                }
            }
            String content = spec.call().content();
            if (StringUtils.isBlank(content)) {
                return fallbackResponse(domain, true, mcpAvailable,
                        "模型返回为空，请检查 MCP 工具连通性、服务名和 SkyWalking 数据范围。");
            }
            return new AnalysisResponse(
                    domain,
                    summarize(content),
                    content,
                    true,
                    mcpAvailable,
                    mcpCapabilityService.performanceTools(),
                    OffsetDateTime.now()
            );
        } catch (Exception ex) {
            return fallbackResponse(domain, true, mcpAvailable,
                    "分析调用失败：" + ex.getClass().getSimpleName() + "，请检查模型服务和 MCP 工具配置。");
        }
    }

    private AnalysisResponse fallbackResponse(AnalysisDomain domain,
                                              boolean modelAvailable,
                                              boolean mcpAvailable,
                                              String message) {
        return new AnalysisResponse(
                domain,
                message,
                """
                ## 当前状态
                %s

                ## 已规划能力
                - Aletheia 作为全系统智能分析中枢，负责聚合各类 MCP 工具。
                - 首期已规划接入 SkyWalking，用于慢接口、慢 Trace、依赖瓶颈分析。
                - 后续可扩展日志、指标、配置、数据库、K8s 等工具。

                ## 建议动作
                - 配置 MCP Client 连接到 showcase-ai-mcp-apm-skywalking。
                - 接入模型后通过 ChatClient + ToolCallbackProvider 执行分析。
                - 按域拆分 Orchestrator，逐步增加运行态诊断能力。
                """.formatted(message),
                modelAvailable,
                mcpAvailable,
                mcpCapabilityService.performanceTools(),
                OffsetDateTime.now()
        );
    }

    private String summarize(String content) {
        String normalized = StringUtils.defaultString(content).trim();
        if (normalized.isEmpty()) {
            return "";
        }
        String firstLine = normalized.lines()
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(normalized);
        return firstLine.length() <= 80 ? firstLine : firstLine.substring(0, 80);
    }
}
