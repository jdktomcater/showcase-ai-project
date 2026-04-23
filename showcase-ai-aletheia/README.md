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
  - `AletheiaAnalysisService`：总编排服务
  - `AletheiaPromptService`：Prompt 组装
  - `McpCapabilityService`：MCP 能力探测与工具目录
- `config`
  - `AletheiaProperties`：聚合层参数配置

## 示例能力链路

性能问题分析：

1. 用户输入性能问题
2. Aletheia 判断为 `PERFORMANCE`
3. 若 MCP 已接入，则通过 SkyWalking 工具获取慢接口、慢 Trace、依赖瓶颈
4. 模型基于事实证据生成最终分析报告

## 启用 SkyWalking MCP

默认关闭了 MCP Client，避免在未配置 SkyWalking Server 路径时启动失败。

可通过环境变量启用：

```bash
export ALETHEIA_MCP_ENABLED=true
export ALETHEIA_ANALYSIS_MCP_ENABLED=true
export ALETHEIA_MCP_SKYWALKING_JAR=/absolute/path/showcase-ai-mcp-apm-skywalking.jar
export SKYWALKING_BASE_URL=http://localhost:12800
```

## 后续扩展方向

- 日志 MCP
- 指标 MCP
- K8s / 主机资源 MCP
- 数据库巡检 MCP
- 发布 / 配置变更 MCP

通过统一的 Orchestrator 可以逐步扩展为全系统智能分析平台。
