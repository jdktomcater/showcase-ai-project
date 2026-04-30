# showcase-ai-aletheia

`showcase-ai-aletheia` 是全系统智能分析中枢，负责聚合各类 MCP 工具，对系统运行态问题进行多阶段分析并输出带证据的结论。

## 当前规划

- 定位：聚合层 / 编排层，而不是单一工具实现。
- 首期能力域：性能分析。
- 首个已规划接入的 MCP 工具：`showcase-ai-mcp-apm-skywalking`

## 首期目标

- 接收自然语言问题，例如“订单创建为什么变慢”。
- 调用模型进行任务理解与结论生成。
- 在 MCP 可用时优先使用 SkyWalking 工具采集事实证据。
- 输出统一分析报告，包含结论、关键发现和建议动作。

## 当前模块结构

- `controller`
  - 对外暴露统一分析入口
- `service`
  - `AletheiaAnalysisService`：总编排服务（性能域）
  - `TraceLogAnalysisService`：SkyWalking 链路日志智能分析编排
  - `AletheiaPromptService`：Prompt 组装（性能 / 链路日志）
  - `McpCapabilityService`：MCP 能力探测与工具目录
  - `McpToolInvoker`：按名称程序化调用 MCP 工具，支持精确 / 后缀 / 包含匹配
- `config`
  - `AletheiaProperties`：聚合层参数配置

## 示例能力链路

性能问题分析：

1. 用户输入性能问题
2. Aletheia 判断为 `PERFORMANCE`
3. 若 MCP 已接入，则通过 SkyWalking 工具获取慢接口、慢 Trace、依赖瓶颈
4. 模型基于事实证据生成最终分析报告

SkyWalking 链路日志分析（`TRACE_LOG`）：

1. 调用 `POST /api/aletheia/analysis/trace-log`，至少传入 `traceId` 或 `serviceName`。
2. `TraceLogAnalysisService` 通过 `McpToolInvoker` 按工具名调用 SkyWalking MCP：
   - 提供 `traceId`：直接命中 `skywalking:diagnoseTrace`，拉取所有 Span。
   - 仅有 `serviceName`：先用 `skywalking:locateSlowEndpoints` 采样慢 Trace，
     再对每个候选 TraceId 调用 `skywalking:diagnoseTrace` 抓取 Span 详情。
3. 聚合层抽取 Top N 瓶颈 Span（service / endpoint / component / peer / layer / duration / error），
   将原始 JSON 证据写入 Prompt。
4. 用 `traceLogSystemPrompt` 调用 ChatClient 生成「结论 / 关键发现 / 链路热点 / 建议动作」结构化报告。
5. 模型不可用 / MCP 不可用时，自动降级返回原始链路证据 + 兜底建议，不会让接口失败。

请求示例：

```json
POST /api/aletheia/analysis/trace-log
{
  "question": "订单创建为何超时",
  "traceId": "ce0d27d1f6cf4c10b1c6c7c4f3b6fa39.123.16998765432101234",
  "topSpanLimit": 10,
  "includeRawEvidence": true
}
```

或者只传服务，由聚合层自动采样：

```json
POST /api/aletheia/analysis/trace-log
{
  "question": "订单创建链路最近一直超时",
  "serviceName": "order-service",
  "endpointKeyword": "/api/order/create",
  "durationMinutes": 30,
  "minTraceDurationMs": 800,
  "traceLimit": 3
}
```

响应字段（`TraceLogAnalysisResponse`）：

- `summary` / `report`：模型生成的一句话结论与完整 Markdown 报告。
- `traceId` / `totalDurationMs` / `spanCount`：聚焦的链路统计。
- `topSpans`：聚合层抽取的 Top N 瓶颈 Span，附带 `suspectedCause` 推理。
- `suggestions`：模型缺失或模型异常时的兜底动作。
- `rawEvidence`：当 `includeRawEvidence=true` 时透出 MCP 工具的原始 JSON。
- `modelAvailable` / `mcpAvailable`：标记当前能力是否就绪，便于上游做降级展示。

## 启用 SkyWalking MCP

默认关闭了 MCP Client，避免在未配置 SkyWalking Server 路径时启动失败。

可通过环境变量启用：

```bash
export ALETHEIA_MCP_ENABLED=true
export ALETHEIA_ANALYSIS_MCP_ENABLED=true
export ALETHEIA_MCP_SKYWALKING_JAR=/absolute/path/showcase-ai-mcp-apm-skywalking.jar
export SKYWALKING_BASE_URL=http://localhost:12800
```

## 常见问题：MCP 通道收到非 JSON 输出

如果 Aletheia 启动时报：

```
Error processing inbound message for line: 2026-04-30T... INFO ... --- [showcase-ai-mcp-apm-skywalking] ...
com.fasterxml.jackson.core.JsonParseException: Unexpected character ('-' (code 45)):
Expected space separating root-level values
```

说明 STDIO MCP Server 把 Spring Boot 的启动日志写进了 `System.out`，污染了 MCP JSON-RPC 通道。
本仓库内的 `showcase-ai-mcp-apm-skywalking` 已经通过
`logback-spring.xml` 把日志全量改写到 `System.err`，并关闭了 banner / startup info；
若你接入的是其他第三方 MCP Server，请同样确保它的 stdout 只输出协议消息，
任何业务/启动日志都应改写到 stderr 或文件。

## 链路日志分析参数

通过环境变量可调（也可写入 `application.yml`）：

| 变量 | 默认值 | 含义 |
| ---- | ------ | ---- |
| `ALETHEIA_DEFAULT_DURATION_MINUTES` | 30 | 慢 Trace 采样的时间窗口（分钟） |
| `ALETHEIA_DEFAULT_MIN_TRACE_DURATION_MS` | 500 | 慢 Trace 最小耗时阈值（毫秒） |
| `ALETHEIA_DEFAULT_TRACE_LIMIT` | 3 | 单次分析最多回溯多少条候选 Trace |
| `ALETHEIA_DEFAULT_TOP_SPAN_LIMIT` | 10 | 响应中保留的 Top N 瓶颈 Span 数量 |
| `ALETHEIA_PROMPT_MAX_CHARS` | 6000 | 单次 User Prompt 最大长度（含证据 JSON） |

## 后续扩展方向

- 日志 MCP
- 指标 MCP
- K8s / 主机资源 MCP
- 数据库巡检 MCP
- 发布 / 配置变更 MCP

通过统一的 Orchestrator 可以逐步扩展为全系统智能分析平台。
