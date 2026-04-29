package com.jdktomcat.showcase.ai.aletheia.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通过 MCP {@link ToolCallbackProvider} 按工具名（或工具名后缀）程序化调用 MCP 工具。
 * <p>
 * 由于不同 MCP Server / 客户端版本对工具命名做的前缀策略不一致
 * （可能是 {@code diagnoseTrace}、{@code skywalking_diagnoseTrace}、
 * {@code spring_ai_mcp_client_skywalking_diagnoseTrace} 等），
 * 这里采用「精确匹配 → 后缀匹配 → 包含匹配」的多级回退策略。
 */
@Component
public class McpToolInvoker {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolInvoker.class);

    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;
    private final ObjectMapper objectMapper;

    public McpToolInvoker(ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                          ObjectMapper objectMapper) {
        this.toolCallbackProviders = toolCallbackProviders;
        this.objectMapper = objectMapper;
    }

    /**
     * 当前是否检测到任何 MCP 工具。
     */
    public boolean hasAnyTool() {
        return getAllToolCallbacks().length > 0;
    }

    /**
     * 列出当前已发现的 MCP 工具名称，便于排错和透出。
     */
    public List<String> listToolNames() {
        return Arrays.stream(getAllToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    /**
     * 调用名为 {@code toolName} 的 MCP 工具，并把响应反序列化为 {@code Map}。
     *
     * @param toolName  目标工具名（支持后缀匹配，如使用 "diagnoseTrace" 会命中 "skywalking_diagnoseTrace"）
     * @param arguments 工具参数，将被序列化成 JSON
     * @return 工具响应的 Map 形式；若工具不可用或返回非 JSON 对象，返回带原始字符串的兜底结果
     */
    public Map<String, Object> invoke(String toolName, Map<String, Object> arguments) {
        Optional<ToolCallback> callback = findToolCallback(toolName);
        if (callback.isEmpty()) {
            return errorResult("MCP 工具未找到: " + toolName,
                    Map.of("availableTools", listToolNames()));
        }
        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception ex) {
            LOGGER.warn("序列化 MCP 工具参数失败: tool={} args={}", toolName, arguments, ex);
            return errorResult("序列化 MCP 工具参数失败: " + ex.getMessage(), Map.of());
        }
        try {
            String rawResult = callback.get().call(inputJson);
            return parseResponse(rawResult);
        } catch (Exception ex) {
            LOGGER.warn("调用 MCP 工具失败: tool={} args={}", toolName, arguments, ex);
            return errorResult("调用 MCP 工具异常: " + ex.getClass().getSimpleName() + " - " + ex.getMessage(),
                    Map.of("toolName", toolName));
        }
    }

    private ToolCallback[] getAllToolCallbacks() {
        List<ToolCallback> aggregated = new ArrayList<>();
        toolCallbackProviders.orderedStream().forEach(provider -> {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            aggregated.addAll(Arrays.asList(callbacks));
        });
        return aggregated.toArray(ToolCallback[]::new);
    }

    private Optional<ToolCallback> findToolCallback(String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return Optional.empty();
        }
        ToolCallback[] callbacks = getAllToolCallbacks();
        if (callbacks.length == 0) {
            return Optional.empty();
        }
        Optional<ToolCallback> exact = Arrays.stream(callbacks)
                .filter(callback -> StringUtils.equals(callback.getToolDefinition().name(), toolName))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        Optional<ToolCallback> suffix = Arrays.stream(callbacks)
                .filter(callback -> {
                    String name = callback.getToolDefinition().name();
                    return StringUtils.endsWithIgnoreCase(name, toolName)
                            || StringUtils.endsWithIgnoreCase(name, "_" + toolName)
                            || StringUtils.endsWithIgnoreCase(name, "-" + toolName)
                            || StringUtils.endsWithIgnoreCase(name, ":" + toolName);
                })
                .findFirst();
        if (suffix.isPresent()) {
            return suffix;
        }
        return Arrays.stream(callbacks)
                .filter(callback -> StringUtils.containsIgnoreCase(callback.getToolDefinition().name(), toolName))
                .findFirst();
    }

    private Map<String, Object> parseResponse(String rawResult) {
        if (StringUtils.isBlank(rawResult)) {
            return errorResult("MCP 工具返回为空", Map.of());
        }
        try {
            Object parsed = objectMapper.readValue(rawResult, new TypeReference<Object>() {
            });
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("payload", parsed);
            return wrapper;
        } catch (Exception ex) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("rawText", rawResult);
            wrapper.put("parseError", ex.getMessage());
            return wrapper;
        }
    }

    private Map<String, Object> errorResult(String error, Map<String, Object> extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", error);
        if (extra != null) {
            result.putAll(extra);
        }
        return result;
    }
}
