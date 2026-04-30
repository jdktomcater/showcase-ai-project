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

## STDIO 日志隔离

STDIO 模式下 `System.out` 是 MCP JSON-RPC 协议通道，**任何**业务/启动日志都不能写入 stdout，
否则上游 MCP Client（例如 `showcase-ai-aletheia`）会出现：

```
com.fasterxml.jackson.core.JsonParseException: Unexpected character ('-' (code 45)):
Expected space separating root-level values
 at [Source: (String)"2026-04-30T08:10:52.667+08:00  INFO 4830 --- [showcase-ai-mcp-apm-skywalking] ..."]
```

为避免该问题，本模块已经做了：

1. `application.yml` 配置 `spring.main.banner-mode=off`、`web-application-type=none`、`log-startup-info=false`；
2. `src/main/resources/logback-spring.xml` 把根 Logger 全量重定向到 `System.err`，并降低 Tomcat / Catalina 噪声日志；
3. 工程内严禁直接 `System.out.println`，统一走 SLF4J。

如果二次开发后仍然出现该 JSON 解析异常，请优先检查：

- 是否新增了直接写 `System.out` 的代码；
- 是否用其他日志桥接（log4j2/jul）绕过了 logback 配置；
- 是否被其他 starter 引入了带 stdout 输出的拦截器或健康检查。
