# PTC（Programmatic Tool Calling）工具链

三个脚本，零第三方依赖（Node ≥20 即可跑；python 侧插件自身需要 `python3`）：

| 脚本 | 作用 |
|---|---|
| `run-conformance.mjs` | **契约一致性门**：把 `contracts/ptc/v1/conformance/cases.v1.json` 打到两个插件的 MCP server 上，断言响应字段与 §6 错误枚举 |
| `closed-loop.mjs` | **最小闭环**：真模型 → 生成代码 → 代码调 ≥2 个宿主工具 → 结果回模型 → 继续；产出 `conversation.json` 证据 |
| `build-ts.mjs` | 把 `plugins/ptc-javascript/skills/ptc-javascript/src/*.ts` 编译到 `dist/` |
| `lib/mcp-client.mjs` | 最小 MCP stdio 客户端（与引擎 `core/mcp/McpClient.scala` 同路径：initialize → tools/list → tools/call） |

## 一条能照抄的验证命令

```bash
# 契约门（两个插件，10 条用例，含四类错误分类、限额、只读/读写档）
PTC_PYTHON=/path/to/python3 node scripts/ptc/run-conformance.mjs
```

`PTC_PYTHON` 只在**执行面没有 `python3` 在 PATH 上**时需要（Windows 便携运行器）：

```bash
# 便携 CPython（一次性，不写进仓库）
mkdir -p /path/to/ptc-runtime && cd /path/to/ptc-runtime
curl -LO https://www.python.org/ftp/python/3.11.9/python-3.11.9-embed-amd64.zip
unzip -q python-3.11.9-embed-amd64.zip -d python311 && cp python311/python.exe python311/python3.exe
export PTC_PYTHON=/path/to/ptc-runtime/python311/python3.exe
```

## 最小闭环

```bash
PTC_PYTHON=/path/to/python3 node scripts/ptc/closed-loop.mjs --lang python     --out reports/<date>_ptc-evidence
                                   node scripts/ptc/closed-loop.mjs --lang typescript --out reports/<date>_ptc-evidence
```

模型/供应商默认取实例 `nebflow.json` 的 `llm.providers.USTC` / `deepseek-v4-flash-ascend`，
可用 `--provider` / `--model` / `PTC_LLM_CONFIG` 覆盖；密钥只用于发请求，不落盘不打印。

## 编译 TypeScript

```bash
node scripts/ptc/build-ts.mjs       # 需要 npm 可达（临时装 typescript + @types/node，编译后清理）
```
