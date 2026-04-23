# showcase-ai-mcp-apm-skywalking

基于 Spring AI MCP Server 和 SkyWalking GraphQL Query Protocol 的 APM 诊断模块。

目标是把 SkyWalking 中常见的性能定位动作封装成 MCP 工具，方便 AI 客户端快速完成：

- 服务发现
- 慢接口定位
- 慢 Trace 分析
- 下游依赖瓶颈识别

## 提供的 MCP 工具

- `listServices`
- `locateSlowEndpoints`
- `diagnoseTrace`
- `diagnoseServicePerformance`

## 启动

默认使用 `STDIO` 作为 MCP 传输方式。

```bash
mvn -pl showcase-ai-mcp/showcase-ai-mcp-apm/showcase-ai-mcp-apm-skywalking spring-boot:run
```

## 配置

```yaml
skywalking:
  base-url: http://localhost:12800
  graphql-path: /graphql
  authorization:
  default-duration-minutes: 30
  default-min-trace-duration-ms: 500
```

可通过环境变量覆盖：

- `SKYWALKING_BASE_URL`
- `SKYWALKING_GRAPHQL_PATH`
- `SKYWALKING_AUTHORIZATION`
- `SKYWALKING_DEFAULT_DURATION_MINUTES`
- `SKYWALKING_DEFAULT_MIN_TRACE_DURATION_MS`

## 实现说明

- 优先使用 SkyWalking Metadata/Trace/Topology 的 V2 ByName 查询，减少 ID 解析步骤。
- 若服务端不支持相关字段，则回退到旧版 V1/V2 API。
- 慢点分析以慢 Trace 样本和 Trace Span 聚合为主，不依赖 UI 页面或截图解析。
