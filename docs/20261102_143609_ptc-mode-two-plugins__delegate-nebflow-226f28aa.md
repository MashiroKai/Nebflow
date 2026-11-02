---
type: design
status: 实施批（两插件 + 契约 + 实证）
title: PTC 模式激活——两个独立插件（Python / TypeScript）+ 语言中立契约 + 最小闭环实证
date: 2026-11-02
batch: delegate-nebflow-226f28aa
tags: [ptc, programmatic-tool-calling, mcp, plugin, sandbox, cross-language]
related:
  - "[[20261030_orchestration-execution-split.zh-CN]]"
  - "[[20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]]"
  - "[[20261030_092600_preset-chain-migration__delegate-nebflow-acc72f03]]"
---

# PTC 模式激活——两个独立插件（Python / TypeScript）+ 语言中立契约 + 最小闭环实证

> **作者令**（2026-11-02 逐字）：「这里可以顺带激活 PTC 模式的设计与实施，提供两个独立的插件就好了」
> 「不等等，scala 也应该交付一个插件啊？PTC 应该可以通过 MCP 实现吧？」
> **本批口径**：不改核心 agent 循环；PTC = **一个独立进程的 MCP server**，宿主侧零改动挂载；
> 语言中立契约 = **MCP 本身**（本批只在其上补三层形状：manifest 声明块、请求/响应 schema、runner 线协议）。

---

## 0. 一页结论

| 结论 | 证据 |
|---|---|
| 仓里**没有**任何 PTC / code-mode 既有设计（全仓 grep 无命中） | §1 |
| PTC 走 MCP 可行，宿主零改动：插件 = stdio MCP server，暴露 `ptc_run` / `ptc_run_rw` | §2、§3 |
| **反向通道**（server → 宿主工具）用"方向翻转"解决：执行器自己当工具 MCP server 的 **client**，全程标准 MCP，不加协议扩展 | §3.3 |
| 两个插件各 11/11 过同一份契约用例（含四类错误分类） | §5.2 |
| 最小闭环真跑通（真模型 → 代码 → 3 个不同工具 → 结果回模型 → 继续） | §5.3 |
| 宿主侧改动 = **0 行 scala**；rust 线挂同一个 MCP server 即可复用 | §8 |

---

## 1. 先对齐语义：既有什么、没有什么（file:line）

### 1.1 既有 PTC 设计：**无**

`grep -ri "programmatic tool\|PTC\|code mode\|code interpreter"` 在 `src/main/scala` / `docs` / `plugins` /
`tools` / `tests` 下**零命中**（仅两处同形误命中：`AskUserQuestionTool.scala:168` 的
"One-time verification-code mode"（OTP）、某个 seed 插件 LICENSE 里的 "object code interpreter"（GPL 条文））。
⇒ **无既有 PTC 设计，本文语义定义即为首个提案**。

### 1.2 既有 preset：**只是「命名模型链」，与本需求无关**

- 类型：`case class ModelPreset(name, description, preferred, fallbacks)` —— `src/main/scala/nebflow/core/presets/PresetStore.scala:16-21`。
- 字段里**没有** tools / system prompt / permissions / 温度（同处定义）。
- 解析只 `copy(model=…, preset=…)`：`src/main/scala/nebflow/core/tools/PresetResolver.scala:42`。
- 节点级 preset 已被硬拒：`src/main/scala/nebflow/core/tools/NodeEditTool.scala:556-568`（`NODE_PRESET_UNSUPPORTED`），
  理由文案即「nodes have no model-scheme setting of their own」。
- `/presets` CRUD 整族已退役：`src/main/scala/nebflow/gateway/PresenceRoutes.scala:2081-2085`。

⇒ **需求方对 preset 的理解（模型+工具集+权限+系统提示词）与本仓现状不符**。本批的纠正见 §4。

### 1.3 既有的"执行面在宿主、编排面在容器"：**有，且正是本批的地基**

`docs/design/20261030_orchestration-execution-split.zh-CN.md:40`（作者已批 2026-10-30）：

> 「**编排层**（LLM 调用/Jev 判定/路由派发/台账监管/合并账与放行通知/UI）留在编排机…
> **执行层**（delegate 会话执行、worktree 分配、git 操作与合并执行、构建测试、任务执行）全部落到**项目所在设备**」

判据同一文档 `:82`：「看它的**字节副作用落在哪块盘上**」。
PTC 完全落在这条线上：**代码生成在编排层（模型），代码执行与字节副作用在执行层（插件 MCP server 是引擎的子进程，随引擎跑在项目设备上）**。

### 1.4 既有插件/MCP 机制（本批复用，未改一行）

| 机制 | 位置 |
|---|---|
| 包布局 + 装载校验（包根只允许 `plugin.json` / `mcp.json` / `skills` / `org.nebflow` / `Plugin.md`） | `src/main/scala/nebflow/core/plugin/PluginRegistry.scala:661` |
| manifest 必填 `$schema` + `name` | `PluginRegistry.scala:701-719` |
| 装载闸：`skills/` 与 `mcp.json` 至少其一 | `PluginRegistry.scala:896-899` |
| 官方前缀闸（`nebflow-` 保留）：本批两包不用该前缀 ⇒ 不经闸 | `PluginRegistry.scala:901-915`、`OfficialPackages.scala:47` |
| MCP server 启动 + 引用计数 + 失败即 failNode | `src/main/scala/nebflow/core/plugin/PluginMcpManager.scala:44-98` |
| 工具命名 `mcp__plugin_<plugin>_<server>__<tool>` | `PluginMcpManager.scala:229-230`、`core/mcp/McpClient.scala:119-131` |
| stdio 传输（newline-delimited JSON-RPC，协议 `2025-06-18`） | `src/main/scala/nebflow/core/mcp/transports.scala:24-25, 84-113` |
| `PLUGIN_ROOT` / `PLUGIN_DATA` / `${secret:…}` 注入 | `PluginMcpManager.scala:246-277` |
| MCP 工具逐次审批（风险分级 + 卡面） | `src/main/scala/nebflow/core/McpToolGate.scala:402-429, 439-457` |

---

## 2. 交付物一览

```
contracts/ptc/v1/                          ← 语言中立契约（rust 线直接照这里做）
  README.md                                  角色/方向、manifest 块、绑定 API、请求/响应、错误枚举、线协议、安全不变量、诚实边界
  ptc-manifest.schema.json                   插件怎么声明自己（JSON Schema）
  ptc-request.schema.json                    执行请求（ptc_run 的 arguments）
  ptc-response.schema.json                   执行响应（含固定错误分类枚举）
  conformance/cases.v1.json                  11 条语言中立用例（py/ts 各一份代码 + 同一份断言）

plugins/ptc-python/                        ← 插件①（Python 运行时）
plugins/ptc-typescript/                    ← 插件②（TypeScript 运行时，src/*.ts + dist/*.js）

scripts/ptc/
  run-conformance.mjs                        契约一致性门（两个插件跑同一份用例）
  check-manifests.mjs                        包面自检（manifest 结构 / entry 存在 / 契约副本逐字节相等 / 跨语言工具面对齐）
  closed-loop.mjs                            最小闭环（真模型 + 真 MCP + 真执行）
  build-ts.mjs                               TypeScript 构建
  lib/mcp-client.mjs                         最小 MCP stdio 客户端（引擎同路径）

reports/20261102_ptc-evidence/            ← 实证（对话日志 / 生成代码 / 工具台账 / 产物 / 耗时）
```

两个插件包结构完全对称：

```
plugins/ptc-<lang>/
  plugin.json          manifest（含 extensions.ptc 声明块）
  Plugin.md            分配注入体（模型照着写代码的作业指导）
  mcp.json             { "ptc": { type: stdio, command: python3|node, args: [<entry>] } }
  skills/ptc-<lang>/
    SKILL.md           按需查阅件（排障 / 运行时形态 / 构建）
    contract/          契约同字节副本（README + 3 份 schema）
    scripts/*.py  |  src/*.ts + dist/*.js
```

---

## 3. 两个插件

### 3.1 挂载类别：**MCP 面**（不是 skills、不是内置工具授予）

- 声明：`mcp.json` 的 `mcpServers.ptc`（stdio；`timeoutMs` 是 `McpServerConfig` 的真实字段，
  见 `src/main/scala/nebflow/shared/config.scala:211`）。
- 消费：`PluginMcpManager.acquire` → `McpManager.startServer` → 工具注册为
  `mcp__plugin_ptc-python_ptc__ptc_run` / `…_ptc_run_rw`（命名见 `PluginMcpManager.scala:229-230`）。
- 节点侧：`NodeDef.plugins`（`ProjectTypes.scala:840`）列出包名即可；未声明 `plugins` 的建位会被
  `NODE_PLUGINS_UNDECLARED` 拒（`NodeEditTool.scala:827-832`）。
- **挂上要两步**（与 imagegen 同形态）：① 包落到 `<dataRoot>/plugins/ptc-python/`（本仓 `plugins/ptc-python/` 是源）；
  ② 节点的 `plugins` 写 `["ptc-python"]`（或 `["ptc-typescript"]`）。不需要改任何 scala 代码，
  也不需要碰 `PluginRegistry` 的允许清单（两包都不占 `nebflow-` 官方前缀）。
- `Plugin.md` 走既有分配注入（`PluginInjection.injectedPluginBlock`，`PluginRegistry.scala` 邻近面），
  模型第一眼就看到"什么时候用 PTC / 代码怎么写"。

### 3.2 暴露的工具

| 工具 | 档位 | 代码里可用的宿主工具 |
|---|---|---|
| `ptc_run` | `readonly` | `read` / `glob` / `grep` / `stat` |
| `ptc_run_rw` | `readwrite` | 上面 4 个 + `write` / `edit` / `remove` |

**为什么拆成两个工具而不是一个带 `profile` 参数**：授权粒度要落在**工具面**上。
只读节点拿到的 `ptc_run` 里根本不存在 `write` 符号——不是"参数拦住了"，而是"能力没给"。
`ptc_run` 传 `profile=readwrite` 直接 `INVALID_REQUEST`（用例 case-09）。

请求参数（schema：`contracts/ptc/v1/ptc-request.schema.json`）：`code`（必填）、`language`、`profile`、
`allowed_tools`（只能收窄）、`timeout_ms`、`limits{max_tool_calls,max_output_bytes,max_memory_mb,max_cpu_ms}`、
`cwd`/`fs_roots`、`args`（透传给代码）、`exec_id`（宿主铸造，原样回显）。

### 3.3 反向通道（本批的核心技术难点）

**问题**：标准 MCP 只有 client→server；PTC 的代码要反过来调**宿主**的工具。

**解法（不发明协议，翻转一次方向）**：

```
宿主(agent 核心) ──MCP client──▶ ptc 执行器(MCP server) ──spawn──▶ runner(沙箱)
                                        │
                                        └──MCP client──▶ 工具 MCP server（宿主字节面：read/write/…）
```

执行器**同时是 server 和 client**：对宿主它是 `ptc_run` 的 server；对工具面它是 MCP client。
代码里的 `tools.read(...)` → runner 发 `tool_call` 帧 → 执行器判白名单/预算 → 向工具 server 发
`tools/call` → 工具 server 在宿主上读字节 → 原路回 runner。**每一跳都是标准 MCP。**

**鉴权与边界（回答"这条反向通道怎么鉴权/凭证活多久/能不能滥用"）**：

| 问题 | 答案 | 实现点 |
|---|---|---|
| 谁授权 | 宿主：`ptc_run` / `ptc_run_rw` 是**两个不同的工具**，MCP 工具授予本身就是授权；每次调用还过引擎既有 `McpToolGate`（逐次审批 + 卡面） | `McpToolGate.scala:402-429` |
| 授权范围 | `(profile 挡位, allowed_tools 子集, limits, fs_roots)` 的闭集，manifest 定上界、请求只能收窄 | `validate()`（两个执行器同形） |
| 凭证是什么、活多久 | **没有可窃取的令牌**：通道是执行器私有的 stdio 管道 + 工具 server 进程本身，生命周期 = 一次执行（每次执行新建、结束即 kill）。不存在"代码拿到 token 后跨会话重放"的形态 | `ToolServer`（每次执行 new/close） |
| 代码能否伪造工具调用 | 能"发"（它就是发起的），但**发什么都会被再判一次**：白名单外的名字 → `POLICY_DENIED/PTC_TOOL_DENIED`；超过 `max_tool_calls` → `LIMIT_EXCEEDED`；每条都进 `tool_calls[]` 台账 | `_dispatch_tool` / `dispatchTool` |
| 失败怎么回传 | 工具 server 的错误按 `code` 前缀映射：`PTC_POLICY_*` → `POLICY_DENIED`，其余 → `TOOL_CALL_ERROR`，连同失败的那一条台账一起回给模型 | 同上 |

**为什么不用 loopback HTTP 回调 + 短时凭证**：那需要引擎开一个**新路由**（宿主改动）并引入
"凭证签发/过期/防重放"三件套；而"执行器当 MCP client"把同样的事做在标准协议里，**宿主零改动**，
凭证面收敛成"进程内一次执行的生命周期"。两条路的安全强度不同：loopback 路由一旦监听就多了一个
入站面（且要防本机其它进程抢 token），stdio 管道天然只有父子进程可达。

### 3.4 代码侧绑定 API（语言中立形状）

```
tools.<tool>(args) -> value          # 失败 = 结构化 ToolCallError（category/code/message）
ptc.exec_id / ptc.profile / ptc.tools / ptc.args
```

| | Python | TypeScript |
|---|---|---|
| 调用 | `tools.read({"path": "a.md"})` | `tools.read({ path: 'a.md' })` |
| 返回 | 工具结果的 `value` | 同 |
| 失败 | 抛 `ToolCallError(category, code, message)` | 抛 `Error`，带 `e.ptcCategory` / `e.ptcCode` |
| 未授权工具 | 符号不存在；已知但未授权 → `PTC_TOOL_DENIED` | 同 |
| 异步 | 同步调用 | 同步调用（`await` 可选：写了 top-level await 会被包进 async 函数，`value` = `return` 的值） |

### 3.5 两个插件共享什么、差异在哪

**共享（同一份，不是两份实现）**：

1. **契约文档 + 3 份 JSON Schema**（`contracts/ptc/v1/`，两包 `skills/*/contract/` 为同字节副本）。
2. **错误分类闭集**：`SYNTAX_ERROR / RUNTIME_ERROR / TOOL_CALL_ERROR / TIMEOUT / LIMIT_EXCEEDED / POLICY_DENIED / INVALID_REQUEST / RUNTIME_UNAVAILABLE / INTERNAL_ERROR`。
3. **响应形状**：`ok / value / stdout / stderr / tool_calls[] / error{category,code,phase,message} / usage`。
4. **runner 线协议** `ptc.wire/v1`（NDJSON：`init` / `log` / `tool_call` / `tool_result` / `done`）。
5. **工具面语义**（工具名、入参、返回 envelope、路径围栏、写权限档位）。
6. **一致性用例**（11 条，两种语言各写一份代码、共用一份断言）。

**差异（只在运行时替身上）**：

| 维度 | Python | TypeScript |
|---|---|---|
| 子进程 | `python -I -B ptc_runner.py` | `node --experimental-permission --max-old-space-size=N ptc_runner.js` |
| 文件围栏 | `sys.addaudithook` 拦 `open/os.listdir/os.scandir` | Node 权限模型 `--allow-fs-read/--allow-fs-write`（进程级） |
| 起进程 | 审计钩子拦 `subprocess.Popen/os.system/os.exec*/ctypes.dlopen` | 权限模型默认拒 `child_process` / `worker` |
| 联网 | 审计钩子拦 `socket.*` / `urllib` / `http.client` | 上下文不给模块加载（`PTC_POLICY_IMPORT`）+ `net.Socket.prototype.connect`/`fetch` patch |
| 内存上限 | POSIX `RLIMIT_AS`；Windows Job Object（硬） | V8 `--max-old-space-size`（硬，堆）+ 父进程 RSS 轮询 |
| CPU 上限 | POSIX `RLIMIT_CPU` | 无（只有 wall-clock，见 §6.2） |
| 值语义 | 最后一个表达式 | 最后一个表达式（script 模式完成值） |

---

## 4. preset 机制：先纠正理解，再给提案

**纠正**：本仓的 preset **不是**"模型 + 工具集 + 权限 + 系统提示词"，而是**命名模型链**（§1.2 三处 file:line）。
真正承载"工具集 + 权限 + 提示词"的是另外三件：`AgentDef.tools` / `systemPrompt`（`src/main/scala/nebflow/actor/AgentDef.scala:47-48`）、
`plugins` 能力面（`NodeDef.plugins`，`ProjectTypes.scala:840`）、`SchemePolicy` + `McpToolGate`（模型链与审批）。

**PTC 意义上的"预设"在本仓的正确落法 = 插件组合**（不新增机制）：

| 预设 | 组成 | 绑定方式 |
|---|---|---|
| 只读取数 | `ptc-python`（或 `ptc-typescript`）单挂 → 只有 `ptc_run` | `NodeDef.plugins = ["ptc-python"]` |
| 读写流水线 | 同上，节点提示词要求"落盘用 `ptc_run_rw`" | 同上（`ptc_run_rw` 天然可用） |
| 严格只读节点 | 只挂 `ptc-python` 且下游不派 `ptc_run_rw`（或插件清单里删该工具，需另出只读变体包） | `plugins` + 节点任务约束 |
| 按项目 | 项目模板里固定节点的 `plugins` 清单 | 既有项目创建面 |

**能不能按项目绑定**：能（项目里节点的 `plugins` 就是每个节点的能力面）；**能不能按项目换模型**：不能，
模型链只按"可设角色"绑（`SchemePolicy.scala:48`），这条现状本批不动。

> 若作者要的是"一个 preset = 模型 + 工具集 + 提示词"的一等公民，那是**另一批**（要动 `PresetStore` /
> `AgentDef` / 节点建位三处），本批不做、也不假装做了。

---

## 5. 最小闭环实证（真模型 / 真 MCP / 真执行）

### 5.1 环境

| 项 | 值 |
|---|---|
| 设备 | DESKTOP-V7EUCHT（Windows，执行面 = 本机宿主，无容器、无 WSL） |
| 引擎 | **未起**（红线不碰在产 6138 实例）。宿主半边 = `scripts/ptc/lib/mcp-client.mjs`，与 `core/mcp/McpClient.scala` 同路径（initialize → tools/list → tools/call） |
| 运行时 | node `v20.20.2`；Python `3.11.9`（便携 embed 包，本机无系统 python3 —— 见 §9-O1 的操作事实） |
| 模型 | `USTC/deepseek-v4-flash-ascend`（实例 `nebflow.json` 的 provider，OpenAI 兼容 tool calling） |
| 协议 | MCP `2025-06-18`（stdio，NDJSON） |

### 5.2 契约一致性门：两个插件 × 11 条用例

```
$ PTC_PYTHON=<python3> node scripts/ptc/run-conformance.mjs
ptc-python      tools=[ptc_run, ptc_run_rw]  11/11 cases passed
ptc-typescript  tools=[ptc_run, ptc_run_rw]  11/11 cases passed
exit=0
```

用例覆盖（`contracts/ptc/v1/conformance/cases.v1.json`）：
`two-tools-readonly`（≥2 工具 + 台账）、`syntax-error`、`runtime-error`、
`tool-call-error`、`tool-denied`、`network-denied`、`timeout`、`tool-call-limit`、
`readonly-escalation`、`readwrite-profile`、`fs-escape`。
原始输出：`reports/20261102_ptc-evidence/conformance.txt`。

### 5.3 闭环①：Python 插件（模型 → 代码 → 4 种工具 → 结果回模型 → 继续）

任务（注入给模型的原始 prompt 之一）：「统计 notes/*.md 里含 TODO 的行数、分布文件、字符数合计；写 summary.json；一句话回报。
要求用一次代码执行把「列文件 + 检索 + 读取 + 写汇总」串起来」。

| 轮 | finish_reason | 模型耗时 | tokens | 这一次 MCP 调用 | 代码内的工具台账 |
|---|---|---|---|---|---|
| 1 | tool_calls | 47567 ms | 2877 | `ptc_run_rw` 79 ms | glob → read×3 → grep → write（6 次） |
| 2 | tool_calls | 13297 ms | 3595 | `ptc_run` 64 ms | grep（交叉校验，看到上一轮的结果后**继续**） |
| 3 | stop | 75689 ms | 4370 | — | — |

**模型生成的代码（原文，第一轮）**：

```python
import json, os

found = tools.glob({"pattern": "**/*.md"})
paths = sorted(p for p in (found.get("paths") or found.get("matches") or []) if p.lower().endswith(".md"))
print("md files:", paths)

todo_total = 0
per_file = {}
chars_total = 0
for p in paths:
    doc = tools.read({"path": p})
    text = doc.get("text", "")
    chars_total += len(text)
    n = sum(1 for line in text.splitlines() if "TODO" in line)
    if n:
        per_file[p] = n
    todo_total += n

g = tools.grep({"pattern": "TODO", "path": ".", "glob": "**/*.md"})
grep_hits = g.get("count") if isinstance(g, dict) else None
print("grep count:", grep_hits)

summary = {"todo_lines": todo_total, "files": sorted(per_file.keys()), "chars": chars_total}
w = tools.write({"path": "summary.json", "content": json.dumps(summary, ensure_ascii=False, indent=2)})

{"summary": summary, "per_file": per_file, "grep_count": grep_hits, "written": w}
```

**这一次 `ptc_run_rw` 的响应（契约 §5 原样，节选）**：

```json
{"contract":"ptc/v1","ok":true,"language":"python","profile":"readwrite",
 "value":{"summary":{"todo_lines":3,"files":["notes/alpha.md","notes/beta.md","notes/gamma.md"],"chars":163},
          "per_file":{"notes/alpha.md":1,"notes/beta.md":1,"notes/gamma.md":1},
          "grep_count":4,
          "written":{"path":"…/plugin-data/workspace/summary.json","bytes":119,"created":true}},
 "stdout":"md files: ['notes/alpha.md', 'notes/beta.md', 'notes/gamma.md']\ngrep count: 4\n",
 "tool_calls":[{"seq":1,"tool":"glob","ok":true},{"seq":2,"tool":"read","ok":true},
               {"seq":3,"tool":"read","ok":true},{"seq":4,"tool":"read","ok":true},
               {"seq":5,"tool":"grep","ok":true},{"seq":6,"tool":"write","ok":true}],
 "tool_call_count":6,"files_written":["summary.json"],"duration_ms":79,"error":null}
```

**产物**（代码真的写到盘上，`reports/20261102_ptc-evidence/python/plugin-data/workspace/summary.json`）：

```json
{ "todo_lines": 3, "files": ["notes/alpha.md","notes/beta.md","notes/gamma.md"], "chars": 163 }
```

**闭环判据**：第 1 轮模型产码并执行（4 种工具 6 次调用） → 第 2 轮模型**看到结果后**又发了一次只读 `ptc_run` 做交叉校验
（`grep`） → 第 3 轮收口成自然语言结论。全程 136.7 s（其中模型 136.5 s，代码执行 143 ms）。

（注意一条真实行为：模型给 `tools.grep` 传了它并不支持的 `glob` 参数 —— 工具忽略未知参数，`grep` 把
`logs/ignore.log` 也算了进去（4 行），模型随后改用 read 逐文件计数取到正确的 3 行。这正是台账与返回值都给模型看的意义。）

### 5.4 闭环②：TypeScript 插件

同一个任务、同一模型，换 TypeScript 插件。**这一轮跑出了更有价值的形态：第 1 轮语法错误 → 模型读结构化错误 → 第 2 轮改对。**

| 轮 | finish_reason | 模型耗时 | 这一次 MCP 调用 | 结果 |
|---|---|---|---|---|
| 1 | tool_calls | 86963 ms | `ptc_run_rw` 116 ms | **`SYNTAX_ERROR / PTC_SYNTAX`**：模型写了 TypeScript 类型标注，运行的是 JS |
| 2 | tool_calls | 48783 ms | `ptc_run_rw` 119 ms | `ok=true`，代码内 glob → read×3 → grep → write → read（7 次） |
| 3 | stop | 15229 ms | — | 收口成自然语言结论 |

**第 1 轮的代码（错的那一版）与它收到的错误**：

```ts
const paths: string[] = (md as any).paths || (md as any).files || (Array.isArray(md) ? md as any : []);
```

```json
{"ok":false,
 "error":{"category":"SYNTAX_ERROR","code":"PTC_SYNTAX","phase":"compile",
          "message":"Missing initializer in const declaration",
          "detail":{"type":"SyntaxError","stack":"<ptc>:3\nconst paths: string[] = …"}},
 "tool_calls":[], "tool_call_count":0, "stdout":"", "duration_ms":115}
```

**第 2 轮（模型据此改对）的代码原文**：

```js
const md = tools.glob({ pattern: '**/*.md' });
const paths = md.paths || md.files || (Array.isArray(md) ? md : []);

const perFile = [];
let totalTodo = 0, totalChars = 0;
for (const p of paths) {
  const doc = tools.read({ path: p });
  const text = doc.text != null ? doc.text : (doc.content != null ? doc.content : '');
  const n = text.split('\n').filter(l => l.includes('TODO')).length;
  perFile.push({ file: p, todoLines: n, chars: text.length });
  totalTodo += n; totalChars += text.length;
}

const g = tools.grep({ pattern: 'TODO', path: '.' });
const filesWithTodo = perFile.filter(f => f.todoLines > 0).map(f => f.file).sort();
const summary = { todo_lines: totalTodo, files: filesWithTodo, chars: totalChars };
const w = tools.write({ path: 'summary.json', content: JSON.stringify(summary, null, 2) + '\n' });
const check = tools.read({ path: 'summary.json' });   // 写完回读自校验
({ summary, perFile, grepCount: g.count });
```

```json
{"ok":true,"duration_ms":118,
 "value":{"summary":{"todo_lines":3,"files":["notes/alpha.md","notes/beta.md","notes/gamma.md"],"chars":147},
          "perFile":[{"file":"notes/alpha.md","todoLines":1,"chars":51},
                     {"file":"notes/beta.md","todoLines":1,"chars":51},
                     {"file":"notes/gamma.md","todoLines":1,"chars":45}],
          "grepCount":4},
 "tool_calls":[{"seq":1,"tool":"glob"},{"seq":2,"tool":"read"},{"seq":3,"tool":"read"},
               {"seq":4,"tool":"read"},{"seq":5,"tool":"grep"},{"seq":6,"tool":"write"},{"seq":7,"tool":"read"}],
 "tool_call_count":7,"files_written":["summary.json"],"error":null}
```

**产物**：`reports/20261102_ptc-evidence/typescript/plugin-data/workspace/summary.json`
= `{"todo_lines":3,"files":[…三个 .md…],"chars":147}`，总耗时 151.2 s。

> 两次运行的 `chars` 不同（163 vs 147）**不是契约问题**：两个模型对"字符数"的口径不同
> （Python 那次按整文件文本算含行尾换行，TS 这次按 `text.length` 算），任务描述本身没钉死这两个字节的定义。
> 契约保证的是"代码怎么拿到字节、怎么把结果与台账还给模型"，不保证两个模型对业务口径的理解一致。

### 5.4b 两次运行对照

| 判据 | Python | TypeScript |
|---|---|---|
| 轮次 | 3（2 次 PTC 执行） | 3（2 次 PTC 执行，第 1 次语法错） |
| 代码内不同工具 | glob / read / grep / write | glob / read / grep / write |
| 代码内工具调用数 | 6 + 1 | 0（语法错）+ 7 |
| 单次代码执行耗时 | 79 ms / 64 ms | 116 ms / 119 ms |
| 总耗时（含模型） | 136.7 s | 151.2 s |
| 产物 | `summary.json` ✅ | `summary.json` ✅ |
| 闭环判据 | ✅ | ✅ |

### 5.5 rust 线可直接对照的请求/响应样本

`reports/20261102_ptc-evidence/request-response.sample.json`（一条 `ptc_run_rw` 请求 + 完整响应原样落盘）。
rust 线把自家的 `ptc_run` 输出与该样本做字段级对照即可（同一份 `ptc-response.schema.json`）。

---

## 6. 执行模型

### 6.1 代码在哪跑

**执行面 = 引擎所在设备**（与 §1.3 的编排/执行分离一致）：插件 MCP server 是引擎的子进程，
runner 是插件 server 的子进程，工具 server 又是插件 server 的子进程。三层都在**同一台设备**上，
字节副作用只落在这台设备的盘上。

- 不用容器（本仓 `SandboxProvider.Container` 至今是显式未实现：`core/sandbox/SandboxBackend.scala:25-50`），
  不另起炉灶。
- 不用 WSL（本机虽然有 Ubuntu 发行版，但"Windows 原生执行面"是作者裁定，见消息身份牌 Q3）。
- 进程形态：`引擎 JVM → ptc_executor(--experimental-permission 无关) → runner(沙箱内) → ptc_tools_fs`。

### 6.2 限额

| 维度 | 值 | 实现 | 强度 |
|---|---|---|---|
| wall-clock | 缺省 30s，上限 300s | 执行器 `deadline` + kill | 硬 |
| 内存 | 缺省 1024MB | Python：POSIX `RLIMIT_AS` / Windows **Job Object**（`PROCESS_MEMORY`）；TS：V8 堆上限 + 父进程 RSS 轮询 | Python 硬；TS 堆硬、RSS 软 |
| CPU | 缺省 60s | POSIX `RLIMIT_CPU` | **Windows 无（只有 wall-clock）** |
| 工具调用次数 | 缺省 64 | 执行器闸 | 硬 |
| 输出字节 | 缺省 64KB | 执行器累积截断 | 硬 |

### 6.3 网络与文件系统

- **网络**：Python 审计钩子拦 `socket.__new__` / `socket.connect` / `socket.getaddrinfo` / `urllib.Request` / `http.client.connect`；
  TS 是双保险——上下文里没有模块加载（拿不到 `net`/`http`/`dgram`），同时 `net.Socket.prototype.connect` 与
  `fetch`/`WebSocket` 被 patch 成拒绝（万一逃逸出 `process` 也接不上）。用例 case-06 实测两语言都 `POLICY_DENIED`。
- **文件系统**：代码只能通过 `tools.*` 碰盘，工具 server 做 `realpath` + 前缀判定（逃逸 → `PTC_POLICY_FS`，
  case-05 与 case-11 实测）；runner 侧再做一道（Python 审计钩子 / TS 权限模型）。
- **起进程**：两语言都禁（`PTC_POLICY_SUBPROCESS` / 权限模型）。

### 6.4 四类错误怎么回给模型（结构化，不靠文本猜）

| 类 | category | phase | 实测 |
|---|---|---|---|
| 语法错误 | `SYNTAX_ERROR` | `compile` | case-02 两语言 PASS |
| 运行时异常 | `RUNTIME_ERROR` | `run` | case-03 两语言 PASS（且此前的工具台账照发） |
| 工具调用失败 | `TOOL_CALL_ERROR` | `tool_call` | case-04 两语言 PASS |
| 超时 | `TIMEOUT` | `run`/`tool_call` | case-07 两语言 PASS |

其余五类（`LIMIT_EXCEEDED` / `POLICY_DENIED` / `INVALID_REQUEST` / `RUNTIME_UNAVAILABLE` / `INTERNAL_ERROR`）
同为闭集成员，`code` 是细分码（`PTC_*`），**rust 线照枚举搬即可**。

### 6.5 大输出与流式

- **不做流式**：一次执行一个响应。理由：MCP 的 `tools/call` 是请求-响应；PTC 的价值是"一次往返拿到整段程序的结论"，
  退化成分段流式会把复杂度还给宿主。
- **截断**：stdout/stderr 累计到 `max_output_bytes` 后置 `stdout_truncated=true` 并停止累积；
  单次工具结果由工具 server 限 `PTC_MAX_VALUE_BYTES`（缺省 16KB，`truncated` 标记）；
  read 工具单文件上限 256KB。模型看到的是"截断标记 + 已截断内容"，不是静默丢数据。

### 6.6 安全：一次 PTC 执行的授权边界（逐条回答三问）

**① 一次执行的整体授权边界是什么？**

| 维度 | 上界由谁定 | 请求能否放宽 | 代码能否放宽 |
|---|---|---|---|
| 可用工具 | 插件 manifest 的 `profiles`（且由**授予了哪个工具**决定：`ptc_run` 还是 `ptc_run_rw`） | 不能（`allowed_tools` 只能取子集，否则 `INVALID_REQUEST`） | 不能（执行器对每个 wire 帧再判一次） |
| 调用次数 / 输出字节 / 内存 / CPU / 时间 | manifest `limits` | 不能（只能收紧，超上界被钳 + 记 `clamped`） | 不能 |
| 可碰的盘 | manifest `workspace.fs_roots`（缺省 `<PLUGIN_DATA>/workspace`） | 不能（`fs_roots` 必须是 manifest 根的子集） | 不能（工具 server 与 runner 各判一道） |
| 审批 | 引擎 `McpToolGate` 逐次审批（`ptc_run` 一次执行 = 一次审批） | — | 不能（代码无法自己调 Gate） |

**② 代码能不能创建/删除文件、联网、再起进程？**

| 动作 | 结论 | 怎么保证 | 实测 |
|---|---|---|---|
| 创建/写文件 | **能，但仅 `ptc_run_rw` 且仅在 fs_roots 内** | 写工具只在 readwrite 档位注入；工具 server `realpath` 前缀判定 | case-10（写成功）/ case-11（越根被拒）/ case-05（只读档无写符号） |
| 删除文件 | 同上（`remove` 工具，readwrite 档） | 同上 | case-05 覆盖"只读档没有 remove" |
| 联网 | **不能** | Python 审计钩子；TS 无模块加载 + socket 层 patch | case-06 两语言 `POLICY_DENIED` |
| 起进程 | **不能** | Python 拦 `subprocess`/`os.system`/`os.exec*`/`ctypes.dlopen`；TS 权限模型默认拒 `child_process` | 冒烟实测 `PTC_POLICY_SUBPROCESS`；TS 模块加载被拒 |

**③ 有没有"代码里偷偷做用户没批准的事"的可能？怎么防？**

PTC 的**固有代价**是：一次审批覆盖"一段代码"而不是"一次工具调用"，所以颗粒度确实变粗。防御是四条：

1. **能力面收窄**：审批的是"`ptc_run`（只读）"还是"`ptc_run_rw`（可写）"——写能力不是代码里的一个开关，而是**宿主授予的工具**。
   只读节点上用 PTC 写文件，在语言层面就不可能（符号未注入）。
2. **台账不可关闭**：`tool_calls[]` 恒回传（含失败项），宿主/人可以在响应里看到代码到底调了什么。
3. **兜底政策**：联网/起进程/越根一律拒（即便代码想"顺手"干点别的也走不通）。
4. **可审计的产物**：`files_written[]` 明确列出这次执行写了哪些路径。

**残留风险（照实写）**：
- 一次 `ptc_run_rw` 里，"写 3 个文件"与"写 30 个文件"是同一个审批。**要给更细的颗粒度，只有两条路**：
  缩短 `max_tool_calls` / 不要授予 `ptc_run_rw` / 在工具面加"写前确认"（都是后续可做的收紧项，本批未做）。
- 网络拦截在 Python 是**解释器级**（审计钩子），理论上可被更底层的逃逸绕过（`ctypes` 已封，但不是内核边界）。
  TS 的 `vm` 也**不是**安全边界——实测"从 `tools.read.constructor` 拿宿主 `Function`"这条路已被上下文 realm 隔离堵住
  （`process is not defined`），但这类"堵路"本身是工程加固，不是形式化保证。
  真正的边界要容器 + `netns`/`seccomp`（本仓 `SandboxProvider.Container` 至今显式未实现）。
- 引擎侧 `McpToolGate` 目前把 `ptc_run` 归为 `unclassified → L0`（`McpToolGate.scala:325-390` 邻域），
  即"默认逐次审批"。**本批没有放宽任何护栏**。

---

## 7. 共存（与逐次 tool-call 模式）

1. **形态上不冲突**：`ptc_run` 只是 MCP 工具，和 `Bash`/`Read`/其它插件 MCP 工具并列注册。
   同一节点可以既有逐次工具又有 PTC（本批的闭环 harness 就是这样跑的：模型自由选择，它选了 `ptc_run_rw`）。
2. **模型怎么选**：靠 `Plugin.md`（分配即注入）里的显式规则——
   "≥2 次工具调用且有循环/分支/汇总关系 → 用 PTC；单次调用 → 直接用对应工具（更省 token、更好审计）"。
3. **不会互相干扰**：PTC 的工具调用发生在**另一个进程**（runner → 工具 server），不占宿主工具队列；
   `ptc_run` 自身的审批仍走 `McpToolGate`（一次执行一次审批）。
4. **审计面统一**：PTC 的每次工具调用都进 `tool_calls[]` 回给宿主，与逐次 tool-call 的审计面可以对齐
   （本批未做"PTC 台账写进宿主审计日志"的接线，登记为开放项 §9-O3）。
5. **反向也可以**：`ptc_tools_fs` 这个工具 server 本身能被宿主**直接挂到节点上**（逐次模式），
   两种用法共享同一套策略——这就是"外源性工具做成插件提供的 MCP"的最小样板。

---

## 8. 语言中立契约 & 迁移诚实清单

契约正文在 `contracts/ptc/v1/README.md`（含 manifest 块、请求/响应、错误枚举、线协议、安全不变量、诚实边界）。
这里只给**rust 线最需要的三句话 + 一张诚实表**。

**三句话**：
1. 传输 = MCP（stdio / JSON-RPC 2.0 / NDJSON / `2025-06-18`）。不要另造协议。
2. 你只需实现**一个 MCP server**：`initialize` / `tools/list` / `tools/call`，工具 `ptc_run`(readonly) + `ptc_run_rw`(readwrite)。
3. 响应字段与错误枚举照 `ptc-response.schema.json` 填；跑 `scripts/ptc/run-conformance.mjs` 做对照（同一份 11 条用例）。

**绑定在 scala 生态上、无法迁移（或无需迁移）的部分**：

| 项 | 说明 |
|---|---|
| 宿主侧接线 | **本批零改动**：插件走既有 `PluginMcpManager` / `McpClient` / `McpToolGate`。rust 线照挂同一个 MCP server，不需要搬 scala 代码 |
| `Plugin.md` 注入时机 | scala 侧 `PluginInjection.injectedPluginBlock` 的行为（"分配即注入包根 Plugin.md"）是**引擎语义**，rust 线要自己对齐口径（否则模型看不到 PTC 使用规则） |
| `mcp.json` 的 `${PLUGIN_ROOT}` / `${PLUGIN_DATA}` 展开 | 这是 scala 侧 `PluginMcpManager.expandRuntimeConfig` 的实现约定（`PluginMcpManager.scala:246-277`）；rust 线沿用同名占位符即可，但**必须也提供这两个变量**，否则两插件跑不起来 |
| 工具命名 `mcp__plugin_<p>_<s>__<tool>` | 引擎约定；rust 线若要复用同一批插件，最好保一致（模型提示里的名字要对得上） |
| 引擎侧 MCP 客户端的超时语义 | scala 侧 `tools/call` 无硬顶（`McpClient.scala:80-113` 注释），靠 server `timeoutMs` 自限。rust 线若加客户端硬顶，必须在 `mcp.json` 的 `timeoutMs` 之上 |
| **不需要迁移**的 | 两个插件本体（Python / TS 脚本 + TS 源码）、契约、用例、harness —— 它们不依赖 scala 运行时 |

**潜在不一致（照实说）**：
- 本批的"宿主"不是引擎本体，而是 `scripts/ptc/lib/mcp-client.mjs`（与 `core/mcp/McpClient.scala` 同路径的参照实现）。
  引擎侧的**真实挂载**未在本机跑过（红线：不碰 6138 的在产实例）；改动量为零，风险集中在
  "引擎是否按 `mcp.json` 起进程 + 是否注入 `PLUGIN_ROOT/PLUGIN_DATA`"——这两条都有既有 imagegen 插件在生产在用（`plugins/imagegen/mcp.json` 同形态），故判为低风险，但仍是**未验证**。
- 引擎侧 `McpToolGate` 对 `ptc_run` 的默认风险分级是 `unclassified → L0`（`McpToolGate.scala:380-390` 邻域），
  意味着"默认要一次审批"。若作者希望 PTC 免审批，那是**放宽护栏**，本批不做（见 §9-O1）。

---

## 9. 未做 / 未验证 / 开放项

| # | 项 | 状态 |
|---|---|---|
| O1 | 引擎侧真实挂载（`NodeDef.plugins=["ptc-python"]` 起节点跑一次） | **未验证**（不碰在产实例；改了 0 行 scala） |
| O1b | **操作事实**：本机 Windows 无系统 `python3`（只有 Store 占位），`mcp.json` 的 `command: "python3"` 在执行面解析不到 | 实证用便携 CPython 3.11.9（`python-3.11.9-embed-amd64.zip`，落在仓库外 `D:\workspace@zcode\nebflow\ptc-runtime\python311`，并把 `python.exe` 复制成 `python3.exe`）。**要在真机上挂 `ptc-python`，执行面必须让 `python3` 在 PATH 上**——这是运行时前置，不是本批能绕过的（`mcp.json` 只支持 `${PLUGIN_ROOT}/${PLUGIN_DATA}/${secret:…}` 三种占位，不支持任意环境变量） |
| O2 | PTC 台账写进引擎审计日志（现在只回给模型） | 未做 |
| O3 | Windows CPU 限额 | 无（只有 wall-clock） |
| O4 | 网络拦截的强度 | 解释器级，不是内核级（容器 provider 未实现）；Python 已封 `ctypes` 主要逃逸路，TS 已封 vm 逃逸（实测 `process is not defined`） |
| O5 | 并行工具调用（`Promise.all`） | v1 串行化；v2 |
| O6 | `ptc_run` 的 MCP 卡面内容（审批时人看到什么） | 未定制，走既有 `GateRedact.summarize`（会看到 code 的截断摘要）→ 建议后续把"profile + allowed_tools + 代码前 N 行"做成定制卡面 |
| O7 | 只读变体包（连 `ptc_run_rw` 都不暴露） | 未做；当前靠"不授予该工具"实现，够用 |

---

## 附：一条能照抄的验证命令

```bash
PTC_PYTHON=<python3 路径> node scripts/ptc/run-conformance.mjs
```

期望输出：两个插件各 `11/11 cases passed`，退出码 0。（完整输出见 §5.2 与
`reports/20261102_ptc-evidence/`。）

## 相关文档

- 执行面/编排面分离（本批的地基）：[[design/20261030_orchestration-execution-split.zh-CN]]
- 插件协议盘点（三能力面 / 挂载链 / 安全面）：[[design/20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]]
- preset → 命名模型链迁移（§1.2 的出处）：[[20261030_092600_preset-chain-migration__delegate-nebflow-acc72f03]]
- 文档库入口：[[index]]　术语：[[glossary]]
