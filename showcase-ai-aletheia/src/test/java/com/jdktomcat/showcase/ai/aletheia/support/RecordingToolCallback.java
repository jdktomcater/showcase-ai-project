package com.jdktomcat.showcase.ai.aletheia.support;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 简单的 {@link ToolCallback} 录制实现，便于在单元测试中：
 * - 通过工具名匹配；
 * - 记录每次调用的入参；
 * - 用预设函数返回 JSON 字符串。
 */
public final class RecordingToolCallback implements ToolCallback {

    private final ToolDefinition toolDefinition;
    private final Function<String, String> handler;
    private final List<String> invocations = new ArrayList<>();

    public RecordingToolCallback(String name, Function<String, String> handler) {
        this.toolDefinition = ToolDefinition.builder()
                .name(name)
                .description("test-tool-" + name)
                .inputSchema("{}")
                .build();
        this.handler = handler;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        invocations.add(toolInput);
        return handler.apply(toolInput);
    }

    public List<String> invocations() {
        return List.copyOf(invocations);
    }
}
