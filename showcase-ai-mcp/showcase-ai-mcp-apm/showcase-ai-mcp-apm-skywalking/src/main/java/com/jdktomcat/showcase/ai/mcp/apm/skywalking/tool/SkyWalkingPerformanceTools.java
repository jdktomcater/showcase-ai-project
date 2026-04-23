package com.jdktomcat.showcase.ai.mcp.apm.skywalking.tool;

import com.jdktomcat.showcase.ai.mcp.apm.skywalking.service.SkyWalkingPerformanceAnalysisService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SkyWalkingPerformanceTools {

    private final SkyWalkingPerformanceAnalysisService analysisService;

    public SkyWalkingPerformanceTools(SkyWalkingPerformanceAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Tool(description = "列出 SkyWalking 中可用的服务，适合在诊断前先做服务发现。")
    public Map<String, Object> listServices(
            @ToolParam(description = "服务关键字，可为空", required = false) String keyword,
            @ToolParam(description = "返回数量上限，默认使用服务端配置", required = false) Integer limit) {
        return analysisService.listServices(keyword, limit);
    }

    @Tool(description = "快速定位某个服务或接口下的慢请求样本，返回慢 Trace 和疑似慢接口。")
    public Map<String, Object> locateSlowEndpoints(
            @ToolParam(description = "服务名，必须是 SkyWalking 中的服务名", required = true) String serviceName,
            @ToolParam(description = "接口关键字，可为空", required = false) String endpointKeyword,
            @ToolParam(description = "查询最近多少分钟，默认 30", required = false) Integer durationMinutes,
            @ToolParam(description = "慢 Trace 最小耗时阈值，单位毫秒，默认 500", required = false) Integer minTraceDurationMs,
            @ToolParam(description = "接口返回上限，默认 5", required = false) Integer endpointLimit,
            @ToolParam(description = "每个接口采样多少条慢 Trace，默认 5", required = false) Integer traceLimit) {
        return analysisService.locateSlowEndpoints(
                serviceName, endpointKeyword, durationMinutes, minTraceDurationMs, endpointLimit, traceLimit);
    }

    @Tool(description = "根据 TraceId 深挖瓶颈 Span，适合对单次慢请求做根因分析。")
    public Map<String, Object> diagnoseTrace(
            @ToolParam(description = "SkyWalking TraceId", required = true) String traceId) {
        return analysisService.diagnoseTrace(traceId);
    }

    @Tool(description = "对某个服务做性能诊断，聚合慢 Trace、依赖链路和瓶颈 Span，适合快速定位性能问题。")
    public Map<String, Object> diagnoseServicePerformance(
            @ToolParam(description = "服务名，必须是 SkyWalking 中的服务名", required = true) String serviceName,
            @ToolParam(description = "接口关键字，可为空", required = false) String endpointKeyword,
            @ToolParam(description = "查询最近多少分钟，默认 30", required = false) Integer durationMinutes,
            @ToolParam(description = "慢 Trace 最小耗时阈值，单位毫秒，默认 500", required = false) Integer minTraceDurationMs,
            @ToolParam(description = "接口返回上限，默认 5", required = false) Integer endpointLimit,
            @ToolParam(description = "每个接口采样多少条慢 Trace，默认 5", required = false) Integer traceLimit) {
        return analysisService.diagnoseServicePerformance(
                serviceName, endpointKeyword, durationMinutes, minTraceDurationMs, endpointLimit, traceLimit);
    }
}
