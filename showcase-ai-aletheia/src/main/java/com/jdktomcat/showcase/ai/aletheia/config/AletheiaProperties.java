package com.jdktomcat.showcase.ai.aletheia.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.aletheia")
public class AletheiaProperties {

    private boolean mcpEnabled = true;

    @Min(1000)
    @Max(20_000)
    private int promptMaxChars = 6000;

    @Min(1)
    @Max(1440)
    private int defaultDurationMinutes = 30;

    @NotBlank
    private String performanceSystemPrompt = """
            你是 Aletheia，全系统智能分析中枢，负责结合运行态工具证据给出系统分析结论。
            当前任务以性能诊断为主。若 MCP 工具可用，优先调用工具获取证据，再输出结论。
            输出要求：
            1. 先给出一句话结论。
            2. 再给出关键发现，尽量引用服务、接口、TraceId、依赖、耗时等证据。
            3. 最后给出建议动作，最多 3 条。
            4. 如果证据不足，明确指出还需要补充什么信息。
            """;

    public boolean isMcpEnabled() {
        return mcpEnabled;
    }

    public void setMcpEnabled(boolean mcpEnabled) {
        this.mcpEnabled = mcpEnabled;
    }

    public int getPromptMaxChars() {
        return promptMaxChars;
    }

    public void setPromptMaxChars(int promptMaxChars) {
        this.promptMaxChars = promptMaxChars;
    }

    public int getDefaultDurationMinutes() {
        return defaultDurationMinutes;
    }

    public void setDefaultDurationMinutes(int defaultDurationMinutes) {
        this.defaultDurationMinutes = defaultDurationMinutes;
    }

    public String getPerformanceSystemPrompt() {
        return performanceSystemPrompt;
    }

    public void setPerformanceSystemPrompt(String performanceSystemPrompt) {
        this.performanceSystemPrompt = performanceSystemPrompt;
    }
}
