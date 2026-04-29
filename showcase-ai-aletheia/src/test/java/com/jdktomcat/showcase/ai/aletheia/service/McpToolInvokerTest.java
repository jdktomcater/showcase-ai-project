package com.jdktomcat.showcase.ai.aletheia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdktomcat.showcase.ai.aletheia.support.RecordingToolCallback;
import com.jdktomcat.showcase.ai.aletheia.support.StaticObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolInvokerTest {

    @Test
    void shouldReturnFalseWhenNoToolProvider() {
        McpToolInvoker invoker = new McpToolInvoker(StaticObjectProvider.empty(), new ObjectMapper());

        assertThat(invoker.hasAnyTool()).isFalse();
        assertThat(invoker.listToolNames()).isEmpty();
    }

    @Test
    void shouldFindToolBySuffixAndDeserializeJsonObject() {
        RecordingToolCallback callback = new RecordingToolCallback(
                "mcp_skywalking_diagnoseTrace",
                input -> "{\"traceId\":\"trace-1\",\"spanCount\":2}"
        );
        McpToolInvoker invoker = newInvoker(callback);

        Map<String, Object> result = invoker.invoke("diagnoseTrace", Map.of("traceId", "trace-1"));

        assertThat(invoker.hasAnyTool()).isTrue();
        assertThat(invoker.listToolNames()).containsExactly("mcp_skywalking_diagnoseTrace");
        assertThat(result).containsEntry("traceId", "trace-1");
        assertThat(result).containsEntry("spanCount", 2);
        assertThat(callback.invocations()).hasSize(1);
        assertThat(callback.invocations().get(0)).contains("\"traceId\":\"trace-1\"");
    }

    @Test
    void shouldReturnErrorPayloadWhenToolNotFound() {
        RecordingToolCallback callback = new RecordingToolCallback("listServices", input -> "{}");
        McpToolInvoker invoker = newInvoker(callback);

        Map<String, Object> result = invoker.invoke("queryLogs", Map.of());

        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("queryLogs");
        assertThat(result).containsKey("availableTools");
    }

    @Test
    void shouldWrapNonJsonResponse() {
        RecordingToolCallback callback = new RecordingToolCallback("diagnoseTrace", input -> "not-json-text");
        McpToolInvoker invoker = newInvoker(callback);

        Map<String, Object> result = invoker.invoke("diagnoseTrace", Map.of("traceId", "abc"));

        assertThat(result).containsEntry("rawText", "not-json-text");
        assertThat(result).containsKey("parseError");
    }

    @Test
    void shouldCaptureExceptionFromCallback() {
        RecordingToolCallback callback = new RecordingToolCallback("diagnoseTrace", input -> {
            throw new IllegalStateException("upstream-down");
        });
        McpToolInvoker invoker = newInvoker(callback);

        Map<String, Object> result = invoker.invoke("diagnoseTrace", Map.of());

        assertThat((String) result.get("error")).contains("IllegalStateException");
        assertThat((String) result.get("error")).contains("upstream-down");
    }

    private McpToolInvoker newInvoker(ToolCallback callback) {
        ToolCallbackProvider provider = ToolCallbackProvider.from(List.of(callback));
        return new McpToolInvoker(StaticObjectProvider.of(provider), new ObjectMapper());
    }
}
