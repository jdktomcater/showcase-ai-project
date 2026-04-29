package com.jdktomcat.showcase.ai.aletheia.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * SkyWalking 链路日志智能分析请求。
 * <p>
 * 至少满足以下任意一个条件即可：
 * <ul>
 *     <li>提供 traceId，直接对单条 Trace 做根因分析；</li>
 *     <li>提供 serviceName（可叠加 endpointKeyword 和 durationMinutes），
 *     由聚合服务先采样慢 Trace，再下钻到最慢的链路日志。</li>
 * </ul>
 */
@Setter
@Getter
public class TraceLogAnalysisRequest {

    /**
     * 自然语言问题或分析诉求。可选；为空时使用默认提示。
     */
    private String question;

    /**
     * 直接定位的 SkyWalking TraceId，优先级最高。
     */
    private String traceId;

    /**
     * 服务名（用于在没有 traceId 时先做慢 Trace 采样）。
     */
    private String serviceName;

    /**
     * 接口关键字（缩小慢 Trace 采样范围）。
     */
    private String endpointKeyword;

    /**
     * 时间窗口（分钟），默认 30。
     */
    @Min(1)
    @Max(1440)
    private Integer durationMinutes;

    /**
     * 慢 Trace 最小耗时阈值（毫秒），默认 500。
     */
    @Min(1)
    @Max(300_000)
    private Integer minTraceDurationMs;

    /**
     * 候选 Trace 数量上限，默认 3。
     */
    @Min(1)
    @Max(20)
    private Integer traceLimit;

    /**
     * 单条 Trace 中返回的瓶颈 Span 数量上限，默认 10。
     */
    @Min(1)
    @Max(100)
    private Integer topSpanLimit;

    /**
     * 是否在响应中携带原始链路证据（spans / 候选 trace 等）。
     */
    private boolean includeRawEvidence = true;
}
