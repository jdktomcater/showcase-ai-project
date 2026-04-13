# Code Knowledge API

`showcase-ai-code-chunk` 现在除了已有的向量检索、依赖图和影响链分析，还提供一组更接近 GitNexus 风格的代码库探索 API：

- `summary`: 仓库概览，返回文件分布、模块分布、图节点/关系统计
- `search/hybrid`: 混合检索，组合语义搜索和 grep 风格文本搜索
- `grep`: 代码库全文搜索，支持路径前缀过滤、大小写和正则
- `file`: 文件片段读取，按行号窗口返回源码

## API

### 仓库概览

```bash
curl http://localhost:8081/api/code/knowledge/summary
```

### 混合检索

```bash
curl -X POST http://localhost:8081/api/code/knowledge/search/hybrid \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "create order payment timeout",
    "pathPrefix": "showcase-pay-order/src/main/java",
    "semanticTopK": 8,
    "lexicalTopK": 8,
    "limit": 10
  }'
```

### grep

```bash
curl -X POST http://localhost:8081/api/code/knowledge/grep \
  -H 'Content-Type: application/json' \
  -d '{
    "pattern": "PaymentStatus",
    "pathPrefix": "showcase-pay-payment/src/main/java",
    "caseSensitive": false,
    "regex": false,
    "contextLines": 1,
    "limit": 20
  }'
```

### 文件读取

```bash
curl -X POST http://localhost:8081/api/code/knowledge/file \
  -H 'Content-Type: application/json' \
  -d '{
    "filePath": "showcase-pay/showcase-pay-payment/src/main/java/com/showcase/pay/payment/service/PaymentService.java",
    "startLine": 1,
    "endLine": 120
  }'
```

## 说明

- `search/hybrid` 里的融合策略是轻量版 RRF，按文件路径汇总语义命中和文本命中
- `grep` 和 `file` 都限制在 `app.code-rag.repo-root` 指定的仓库根目录内
- 图统计来自 Neo4j；如果图数据库不可用，`summary` 会返回空的图统计字段，但文件概览仍可使用
