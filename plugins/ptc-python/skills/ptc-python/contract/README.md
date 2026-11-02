# PTC v1 — Programmatic Tool Calling 契约（语言中立）

> 状态：`v1（草案 → 本批实证）`　适用：`nebflow/stand`（scala 线）与 `triple-star` / `triple-main`（rust 线）
> 本契约**不定义新的传输层**：传输 = **MCP**（stdio / JSON-RPC 2.0 / newline-delimited，协议版本 `2025-06-18`）。
> 本契约只定义 MCP 之上的三件事：**manifest 声明块**、**`ptc_run` 的请求/响应形状**、**执行器 ↔ 运行器（runner）的子进程线协议**。
> 三份 JSON Schema 是本目录下的权威件；本文是导读与不变量说明。

---

## 0. 一句话

一个 PTC 插件 = **一个独立进程的 MCP server**，暴露 `ptc_run`（只读档）与 `ptc_run_rw`（读写档）。
宿主（agent 核心）通过既有 MCP 挂载机制挂它，**宿主零改动**；模型写代码，代码在受控子进程里执行，
执行过程中调用**宿主工具面**（由执行器以 MCP **客户端**身份连到的工具 MCP server 提供），
执行结束把结构化的 stdout / 返回值 / 工具调用台账 / 错误一次性回给模型。

---

## 1. 谁是谁（角色与方向）

```
┌───────────────────────────── 编排层（宿主 / agent 核心）──────────────────────────────┐
│  LLM ──tool_call──▶ MCP client ──stdio(JSON-RPC)──▶ ptc 插件（MCP server，执行器）      │
│                              ▲                                    │                    │
│                              └────── tools/call 的 result ─────────┘                    │
└────────────────────────────────────────────────────────────────────────────────────────┘
                                                                       │ spawn（子进程）
                                                                       ▼
                                              ┌───── runner（沙箱内的代码运行时）─────┐
                                              │  代码里：await tools.read({path})     │
                                              └──────────────┬───────────────────────┘
                                                             │ wire/v1（stdio NDJSON）
                                                             ▼
                                              ┌───── 执行器（工具代理 + 台账 + 限额）──┐
                                              │  MCP client                            │
                                              └──────────────┬────────────────────────┘
                                                             │ stdio(JSON-RPC)
                                                             ▼
                                              ┌───── 工具 MCP server（宿主侧字节面）──┐
                                              │  read / write / edit / glob / grep …   │
                                              └────────────────────────────────────────┘
```

**反向通道怎么解决（核心）**：标准 MCP 只有 client→server。PTC **不发明反向帧**，而是把方向翻转一次：
**执行器本身是工具 MCP server 的 MCP client**，代码要的工具调用由执行器向工具 server 发起
（`tools/call`），工具 server 在宿主上执行、碰宿主的字节。这条链上每一跳都是标准 MCP。
所以"server→host 工具调用"= **执行器既是 server（对宿主）又是 client（对工具面）**。

---

## 2. 插件 manifest 声明块（`plugin.json` 的 `extensions.ptc`）

权威 schema：[`ptc-manifest.schema.json`](./ptc-manifest.schema.json)。示例（`ptc-python` 实际件）：

```json
"extensions": {
  "ptc": {
    "contract": "ptc/v1",
    "runtime": { "kind": "python", "min_version": "3.9",
                 "entry": "skills/ptc-python/scripts/ptc_executor.py", "self_exec": true },
    "tools": [ { "name": "ptc_run",    "profile": "readonly"  },
               { "name": "ptc_run_rw", "profile": "readwrite" } ],
    "profiles": {
      "readonly":  { "tools": ["read","glob","grep","stat"] },
      "readwrite": { "tools": ["read","glob","grep","stat","write","edit","remove"] }
    },
    "tool_servers": [ { "id": "fs", "transport": "stdio",
                        "command": "${RUNTIME_SELF}",
                        "args": ["${PLUGIN_ROOT}/skills/ptc-python/scripts/ptc_tools_fs.py"],
                        "timeout_ms": 10000 } ],
    "limits": { "default_timeout_ms": 30000, "max_timeout_ms": 300000,
                "max_tool_calls": 64, "max_output_bytes": 65536,
                "max_memory_mb": 1024, "max_cpu_ms": 60000 },
    "workspace": { "default_cwd": "${PLUGIN_DATA}/workspace",
                   "fs_roots": ["${PLUGIN_DATA}/workspace"] },
    "policy": { "network": "deny", "subprocess": "deny",
                "fs_write": "profile", "env": "scrubbed" }
  }
}
```

字段语义：

| 字段 | 含义 |
|---|---|
| `runtime.kind` | `python` \| `typescript`。**必须是插件自己实现的运行时**，rust 侧照此值选 runner。 |
| `runtime.self_exec` | `true` = 工具 server 的 `command` 用 `${RUNTIME_SELF}`（执行器用自己的解释器路径替换）。 |
| `tools[]` | 本插件暴露给宿主的 MCP 工具名 ↔ 授权的档位（profile）。**一个档位 = 一份能力集**。 |
| `profiles` | 档位 → 允许的工具名闭集。执行器**只认**这里列过的工具名。 |
| `tool_servers[]` | 执行器要连的工具 MCP server（宿主侧字节面）。`${PLUGIN_ROOT}` / `${PLUGIN_DATA}` 由宿主/执行器展开。 |
| `limits` | 上限。请求只能**收紧**，不能放宽。 |
| `workspace` | 默认工作目录与文件系统根。请求里的 `cwd` / `fs_roots` 必须落在这些根下。 |
| `policy` | v1 固定 `network=deny`、`subprocess=deny`、`env=scrubbed`；`fs_write` = `profile`（写权限只由档位给）。 |

---

## 3. 模型代码侧的绑定 API（语言中立形状）

代码里的工具面 = **一个对象 `tools`，每个授权工具名一个可调用成员**：

```
tools.<tool>(args) -> value        # 失败 = 抛出/返回结构化 ToolCallError
```

- `args` = 该工具在工具 MCP server 里声明的 `inputSchema` 对象（原样透传）。
- 返回值 = 工具结果的 `value` 字段（见 §5），**不含**信封（`ok/duration/truncated` 由执行器记账）。
- 未授权工具名：**编译期不注入**（代码里根本没有这个成员）→ 语言层面的 `AttributeError` / `TypeError`，
  同时执行器把 `allowed_tools` 之外的任何 wire 上的 `tool_call` 判 `POLICY_DENIED / PTC_TOOL_DENIED`。
- 另有 `ptc` 对象：`ptc.exec_id`、`ptc.profile`、`ptc.tools`（授权工具名列表）、`ptc.args`（请求的 `args` 透传）。

两种语言的**唯一拼写差异**（语义完全一致）：

```ts
// TypeScript / JavaScript
const text = await tools.read({ path: "notes/a.txt" });
const hits  = await tools.grep({ pattern: "TODO", path: "." });
```

```python
# Python（同步调用；执行器侧是阻塞 IO，无需 async）
text = tools.read({"path": "notes/a.txt"})
hits = tools.grep({"pattern": "TODO", "path": "."})
```

> 绑定层的实现方式（Python：模块注入的代理对象；TS：`vm` 上下文里的 Proxy）属于实现细节，
> **契约只钉住"名字 → 可调用 → args 透传 → value 返回 → 失败结构化"**。

---

## 4. 执行请求（`ptc_run` 的 `arguments`）

权威 schema：[`ptc-request.schema.json`](./ptc-request.schema.json)。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `code` | string | ✅ | 要执行的代码（≤ 256 KiB）。 |
| `contract` | `"ptc/v1"` | | 缺省视为 `ptc/v1`；不匹配 → `INVALID_REQUEST`。 |
| `language` | `python`\|`typescript` | | 可与插件运行时不同 → `INVALID_REQUEST`（防串用）。 |
| `profile` | `readonly`\|`readwrite` | | 缺省 `readonly`。由工具名（`ptc_run` / `ptc_run_rw`）或请求给出，二者冲突以**更窄者**为准。 |
| `allowed_tools` | string[] | | 缺省 = 档位全集；只能**取子集**。 |
| `timeout_ms` | int | | 缺省 `limits.default_timeout_ms`；上限 `limits.max_timeout_ms`（超出即钳到上限并记 `clamped`）。 |
| `limits` | object | | `max_tool_calls` / `max_output_bytes` / `max_memory_mb` / `max_cpu_ms`，逐项只能收紧。 |
| `cwd` | string | | 绝对路径，必须在 `fs_roots` 内；缺省 `workspace.default_cwd`。 |
| `fs_roots` | string[] | | 缺省 = manifest 根；必须是 manifest 根的子集。 |
| `args` | object | | 透传给代码的 `ptc.args`（做参数化，避免拼字符串）。 |
| `exec_id` | string | | 宿主铸造的不透明 id，原样回显（宿主用它把响应与自己的执行记录对账）。 |

## 5. 执行响应（`ptc_run` 的 `result.content[0].text`，JSON 字符串）

权威 schema：[`ptc-response.schema.json`](./ptc-response.schema.json)。

```json
{
  "contract": "ptc/v1",
  "exec_id": "ptc-7f3c1a9e",
  "ok": true,
  "language": "python",
  "profile": "readonly",
  "duration_ms": 812,
  "value": { "matched": 3 },
  "stdout": "…",
  "stderr": "",
  "stdout_truncated": false,
  "stderr_truncated": false,
  "tool_calls": [
    { "seq": 1, "tool": "read",  "args": {"path": "notes/a.txt"},
      "ok": true, "duration_ms": 3, "result_bytes": 214, "truncated": false },
    { "seq": 2, "tool": "grep",  "args": {"pattern": "TODO", "path": "."},
      "ok": true, "duration_ms": 7, "result_bytes": 96,  "truncated": false }
  ],
  "tool_call_count": 2,
  "files_written": [],
  "error": null,
  "usage": { "wall_ms": 812, "cpu_ms": 120, "max_rss_kb": 41234,
             "tool_calls": 2, "output_bytes": 210 },
  "clamped": ["timeout_ms"]
}
```

`ok=false` 时 `error` 非空，`stdout`/`stderr`/`tool_calls` **仍然回传**（失败的执行也有台账）。

## 6. 错误分类（固定枚举，rust 线照搬）

`error.category` 是**闭集**（4 类硬要求 + 5 类工程必需，全部结构化，不靠文本判断）：

| category | 何时 | `phase` | 典型 `code` |
|---|---|---|---|
| `SYNTAX_ERROR` | 代码**没跑起来**：编译/解析失败 | `compile` | `PTC_SYNTAX` |
| `RUNTIME_ERROR` | 代码跑到一半抛未捕获异常 | `run` | `PTC_RUNTIME` |
| `TOOL_CALL_ERROR` | 工具调用失败（工具返回错误 / 超时 / 值超限） | `tool_call` | `PTC_TOOL_ERROR` |
| `TIMEOUT` | 整体 wall-clock 超时，子进程被 kill | `run` \| `tool_call` | `PTC_TIMEOUT` |
| `LIMIT_EXCEEDED` | 撞 `max_tool_calls` / `max_output_bytes` / `max_memory_mb` / `max_cpu_ms` | `run` | `PTC_LIMIT_TOOL_CALLS` / `PTC_LIMIT_OUTPUT` / `PTC_LIMIT_MEMORY` / `PTC_LIMIT_CPU` |
| `POLICY_DENIED` | 触政策：联网 / 起进程 / 越根读写 / 白名单外工具 | `run` \| `tool_call` | `PTC_POLICY_NETWORK` / `PTC_POLICY_SUBPROCESS` / `PTC_POLICY_FS` / `PTC_TOOL_DENIED` |
| `INVALID_REQUEST` | 请求本身不合法（schema / 语言不符 / 子集违反） | `validate` | `PTC_INVALID_REQUEST` |
| `RUNTIME_UNAVAILABLE` | 运行时缺失（宿主没有 python3 / node） | `validate` | `PTC_RUNTIME_UNAVAILABLE` |
| `INTERNAL_ERROR` | 执行器自身故障 | `teardown` | `PTC_INTERNAL` |

**四类必须区分的硬要求**映射：语法 = `SYNTAX_ERROR`；运行时异常 = `RUNTIME_ERROR`；
工具调用失败 = `TOOL_CALL_ERROR`；超时 = `TIMEOUT`。四者的 `phase` 与 `code` 互不重叠，调用方可据此分支。

## 7. 执行器 ↔ runner 线协议（`wire/v1`）

执行器与 runner 之间是一条**私有 stdio 管道**（不是 MCP）。NDJSON，每行一个 JSON 对象，字段 `ptc: "wire/1"`。

| 方向 | `type` | 字段 | 说明 |
|---|---|---|---|
| host→runner | `init` | `code, language, profile, allowed_tools, limits, cwd, fs_roots, args, exec_id` | 唯一的第一帧 |
| runner→host | `log` | `stream: stdout\|stderr, data` | 用户的 print/console 输出 |
| runner→host | `tool_call` | `seq, tool, args` | 代码请求一次工具调用 |
| host→runner | `tool_result` | `seq, ok, value?` / `error{category,code,message}` | 执行器回答 |
| runner→host | `done` | `ok, value?, error{category,code,phase,message}?` | 终帧；之后 runner 退出 |

不变量：
1. **seq 单调递增**，一问一答，不发并行帧（v1 简化：串行；并行 `Promise.all` 在 runner 内串行化）。
2. runner 的 `stdout` **只用于协议**；用户输出一律走 `log` 帧（实现上把用户 stdout/stderr 重定向到 `log`）。
3. 代码里的任何直接 IO/网络/进程动作要么被运行时拦成 `POLICY_DENIED`，要么在 `done` 里冒出来——不允许静默。
4. runner 崩溃（被杀/段错）⇒ 执行器按 `RUNTIME_ERROR`（或 `TIMEOUT`/`LIMIT_EXCEEDED`，视触发者）收口，并把已发生的台账照发。

## 8. 安全不变量（v1 必须成立，rust 线同样）

1. **一次 PTC 执行 = 一次授权**：授权对象是 `(profile, allowed_tools, limits, fs_roots)` 的**闭集**，由 manifest 定上界、由请求收窄。
2. **代码不能放宽自己的授权**：请求只能取子集；执行器对每个 wire 帧再判一次（不信任 runner 的自我声明）。
3. **代码不能起进程**：`subprocess`/`child_process` 在运行时层被拦（审计钩子 / Node 权限模型），v1 无例外。
4. **代码不能联网**：socket 层被拦（见 §9 的诚实边界）。
5. **代码只能碰 `fs_roots`**：工具 server 侧做 canonicalize + 前缀判定；runner 侧对直接 `open/readFile` 再做一道。
6. **写权限只由档位给**：`ptc_run` 拿不到 `write/edit/remove`（连符号都不注入）。
7. **台账不可关闭**：`tool_calls[]` 恒回传，宿主可据此审计"代码偷偷干了什么"。

## 9. 诚实边界（v1 做不到的，写清楚）

- **网络拦截是解释器级，不是内核级**：Python 用 `sys.addaudithook`（同时封 `ctypes.dlopen` 断掉主要逃逸路），
  Node 用权限模型 + 模块层 patch。真正的内核边界要容器/`netns`/`seccomp`，v1 没有（见设计文档 §执行模型）。
- **内存上限**：POSIX 用 `RLIMIT_AS`（硬）；Windows 用**父进程 250ms 轮询**工作集（软但可执行，撞线即 kill）。
- **CPU 上限**：POSIX `RLIMIT_CPU`（硬）；Windows 只有 wall-clock。
- **串行工具调用**：v1 一次一个（`Promise.all` 串行化），并行留 v2。

## 10. 一致性门（两个实现不许漂）

`contracts/ptc/v1/conformance/*.json` = 语言中立的用例（请求 + 期望的响应/错误形状）。
两个插件都必须过同一份用例：

```bash
node scripts/ptc/run-conformance.mjs
```

用例断言的是**契约字段**，不是某个实现的内部细节；rust 线只要照 §4/§5/§6 产出响应即可复用同一份用例做对照测试。
