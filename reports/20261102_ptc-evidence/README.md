# PTC 最小闭环 —— 证据目录（过程文件）

设计文档：`docs/20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa.md`
契约：`contracts/ptc/v1/README.md`　实现：`plugins/ptc-python/`、`plugins/ptc-typescript/`

| 文件 | 内容 |
|---|---|
| `conformance.txt` | 契约一致性门的**原始终端输出**（两个插件 × 11 条用例） |
| `<lang>/conversation.json` | 最小闭环的完整对话日志：每轮请求/响应、模型生成代码原文、每次 MCP 调用与 PTC 台账、耗时、token、产物 |
| `<lang>/summary.artifact.json` | 代码执行真正写出来的产物（`summary.json`） |
| `<lang>/plugin-data/workspace/` | 该轮运行的 workspace（含 fixture 与产物） |
| `SUMMARY.md` | 由 `scripts/ptc/collect-evidence.mjs` 从 conversation.json 汇总的人读摘要 |
| `request-response.sample.json` | **一条请求 + 一条响应原样样本**（rust 线做对照测试可直接复用） |

复现命令：

```bash
PTC_PYTHON=<python3> node scripts/ptc/run-conformance.mjs
PTC_PYTHON=<python3> node scripts/ptc/closed-loop.mjs --lang python     --out reports/<date>_ptc-evidence
                     node scripts/ptc/closed-loop.mjs --lang typescript --out reports/<date>_ptc-evidence
node scripts/ptc/collect-evidence.mjs --dir reports/<date>_ptc-evidence
```
