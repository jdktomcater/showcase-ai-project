package com.jdktomcat.showcase.ai.aletheia.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Setter
@Getter
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

    @Min(1)
    @Max(300_000)
    private int defaultMinTraceDurationMs = 500;

    @Min(1)
    @Max(20)
    private int defaultTraceLimit = 3;

    @Min(1)
    @Max(100)
    private int defaultTopSpanLimit = 10;

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

    @NotBlank
    private String traceLogSystemPrompt = """
            你是 Aletheia，全系统智能分析中枢，当前任务是基于 SkyWalking 链路日志做根因分析。
            用户提供的「链路证据」是聚合层已经从 SkyWalking 抓取的真实运行态数据，
            包括候选慢 Trace、TraceId、瓶颈 Span（service/endpoint/component/peer/layer/duration/error 等）。
            请严格基于证据回答，不要编造未在证据中出现的服务、接口或 TraceId。
            输出要求（中文，使用 Markdown）：
            1. ## 结论：一句话给出最可能的根因或主要现象。
            2. ## 关键发现：3~5 条，引用 TraceId / Span 名称 / 耗时 / 依赖等证据，必须说明耗时或错误占比。
            3. ## 链路热点：列出 Top 1~3 个最可疑 Span，说明它们处在调用链的什么位置（入口 / 出口 / 数据库 / 下游服务）。
            4. ## 建议动作：最多 3 条，按优先级排序，要落到具体可执行的排查或修复动作。
            5. 如果证据缺失或不足以下结论，请在末尾用 ## 待补充 列出仍需要的字段或时间窗口，不要强行猜测。
            """;

}
