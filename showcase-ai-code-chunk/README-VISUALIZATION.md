# Impact Chain Visualization 使用指南

## 概述

本模块提供了基于 D3.js 的交互式影响链路可视化功能，帮助您直观地分析代码依赖关系。

## 访问方式

启动应用后，访问以下 URL：

- **首页**: http://localhost:8081/index.html
- **影响链路可视化**: http://localhost:8081/impact-chain.html
- **代码依赖图**: http://localhost:8081/graph.html

## 功能特性

### 1. 入口点管理

#### 加载入口点
点击 **Load Entry Points** 按钮，从 Neo4j 加载所有已分析的入口点。

#### 按类型过滤
支持按入口点类型过滤：
- **ALL**: 显示所有入口点
- **HTTP**: REST 控制器接口
- **RPC**: Dubbo/gRPC 服务
- **MQ**: 消息队列消费者
- **SCHEDULED**: 定时任务
- **EVENT**: 事件监听器

#### 快速加载
点击任意入口点卡片，自动填充 Entry Point ID 并加载其影响链路图。

### 2. 图查询

#### 单入口点模式 (Single Entry Point)
- 输入入口点 ID（如：`HTTP:com.example.Controller#method()`）
- 设置查询深度（1-10）
- 点击 **Load Graph** 加载该入口点的影响链路

#### 全图模式 (Full Graph)
- 选择 **Full Graph** 模式
- 可选择性地按入口点类型过滤
- 加载所有入口点的完整影响链路图

### 3. 执行分析

点击 **Analyze** 按钮，执行完整的代码影响分析：
1. 扫描所有 Java 文件
2. 检测入口点
3. 构建影响链路
4. 存储到 Neo4j

### 4. 图交互

- **拖拽节点**: 固定节点位置
- **滚轮缩放**: 放大/缩小视图
- **点击节点**: 查看节点详情
- **点击边**: 查看关系详情

### 5. 导出功能

支持导出为以下格式：
- **DOT**: Graphviz 格式，用于专业图表制作
- **JSON**: D3.js 格式，用于二次开发
- **Mermaid**: Markdown 文档嵌入

## 图例说明

### 节点颜色

| 颜色 | 形状 | 含义 |
|------|------|------|
| 🔴 红色 | 圆形 | HTTP 入口点 |
| 🟣 紫色 | 圆形 | RPC 入口点 |
| 🟠 橙色 | 圆形 | MQ 入口点 |
| 🟢 绿色 | 圆形 | SCHEDULED 入口点 |
| 🔷 粉色 | 圆形 | EVENT 入口点 |
| 🔵 蓝色 | 圆形 | Type/Class 节点 |
| 🔵 青色 | 圆形 | Method 节点 |

### 边颜色

| 颜色 | 关系类型 |
|------|----------|
| 🟠 橙色 | CALLS（方法调用） |
| 🟢 绿色 | DEPENDS_ON（依赖） |
| 🟣 紫色 | EXTENDS/IMPLEMENTS（继承/实现） |
| 🩷 粉色 | ANNOTATED_BY（注解） |
| 🔴 红色 | INJECTS（注入） |
| 🟠 浅橙 | INVOKES（调用） |
| 🩷 浅粉 | TRIGGERS（触发） |
| 🟦 青色 | DECLARES（声明） |

## API 参考

### 入口点相关

```bash
# 获取所有入口点
GET /api/impact/entry-points

# 按类型获取入口点
GET /api/impact/entry-points/by-type?type=HTTP

# 执行分析
POST /api/impact/analyze
```

### 图查询相关

```bash
# 查询影响链路
POST /api/impact/chain
Content-Type: application/json

{
  "entryPointId": "HTTP:com.example.Controller#method()",
  "depth": 5
}

# 获取影响图
POST /api/impact/graph
Content-Type: application/json

{
  "entryPointId": "HTTP:com.example.Controller#method()",
  "mode": "single",
  "depth": 5
}
```

### 导出相关

```bash
# 导出为 DOT
GET /api/impact/export/dot?entryPointId=xxx&depth=5

# 导出为 JSON
GET /api/impact/export/json?entryPointId=xxx&depth=5

# 导出为 Mermaid
GET /api/impact/export/mermaid?entryPointId=xxx&depth=5
```

## 配置说明

在 `application.yml` 中配置：

```yaml
# Neo4j 配置
neo4j:
  uri: bolt://localhost:7687
  username: neo4j
  password: your_password

# 代码仓库根目录（用于分析）
app:
  impact-analysis:
    repo-root: /path/to/your/codebase
```

## 使用示例

### 示例 1：分析 HTTP 接口的影响范围

1. 点击 **Load Entry Points**
2. 点击 **HTTP** 类型过滤
3. 点击目标入口点卡片
4. 查看其影响链路图
5. 点击 **Export JSON** 导出结果

### 示例 2：分析定时任务的调用链

1. 点击 **Load Entry Points**
2. 点击 **SCHEDULED** 类型过滤
3. 点击目标定时任务入口点
4. 调整 **Depth** 为 3
5. 点击 **Load Graph**

### 示例 3：全量分析代码库

1. 确保 `app.impact-analysis.repo-root` 配置正确
2. 点击 **Analyze** 按钮
3. 等待分析完成
4. 点击 **Load Entry Points** 查看结果
5. 切换到 **Full Graph** 模式查看全局视图

## 故障排查

### 问题：无法加载入口点

**解决方案**：
1. 检查 Neo4j 是否运行：`docker ps | grep neo4j`
2. 检查 Neo4j 配置是否正确
3. 确认已执行过分析（点击 **Analyze**）

### 问题：图表显示空白

**解决方案**：
1. 检查入口点 ID 是否正确
2. 尝试减小 **Depth** 值
3. 查看浏览器控制台错误信息

### 问题：节点重叠严重

**解决方案**：
1. 拖拽节点手动调整位置
2. 缩小视图（滚轮向下）
3. 使用 **Full Graph** 模式查看全局

## 性能优化建议

1. **大型代码库**: 使用类型过滤减少加载数据量
2. **深度限制**: 建议深度不超过 5，避免性能问题
3. **增量分析**: 只分析变更的文件（未来功能）

## 技术栈

- **前端**: D3.js 7.x
- **后端**: Spring Boot 3.x + Neo4j
- **通信**: RESTful API

## 未来规划

- [ ] 实时搜索和过滤
- [ ] 节点分组和折叠
- [ ] 时间线视图
- [ ] 对比分析
- [ ] 性能分析集成
