package com.jdktomcat.showcase.ai.aletheia.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class McpCapabilityService {

    private static final List<String> PERFORMANCE_TOOLS = List.of(
            "skywalking:listServices",
            "skywalking:locateSlowEndpoints",
            "skywalking:diagnoseTrace",
            "skywalking:diagnoseServicePerformance"
    );

    private static final List<String> TRACE_LOG_TOOLS = List.of(
            "skywalking:locateSlowEndpoints",
            "skywalking:diagnoseTrace"
    );

    private final ObjectProvider<ToolCallbackProvider> toolCallbackProvider;

    public McpCapabilityService(ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public boolean hasMcpTools() {
        return toolCallbackProvider.getIfAvailable() != null;
    }

    public ToolCallbackProvider getToolCallbackProvider() {
        return toolCallbackProvider.getIfAvailable();
    }

    public List<String> performanceTools() {
        return PERFORMANCE_TOOLS;
    }

    public List<String> traceLogTools() {
        return TRACE_LOG_TOOLS;
    }
}
