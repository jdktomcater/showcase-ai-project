package com.jdktomcat.showcase.ai.mcp.apm.skywalking.config;

import com.jdktomcat.showcase.ai.mcp.apm.skywalking.tool.SkyWalkingPerformanceTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfig {

    @Bean
    ToolCallbackProvider skyWalkingToolCallbackProvider(SkyWalkingPerformanceTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
