# ptc-javascript —— PTC 模式（用 JavaScript 代码编排工具调用）

你获得了两个工具：`ptc_run`（只读档）与 `ptc_run_rw`（读写档）。**你写一段 JavaScript（ES2022）代码，代码在受控子进程里跑，跑的过程中可以调宿主工具；一次调用把整段程序的 stdout、返回值、工具调用台账和结构化错误一起还给你。**

## 什么时候用 / 不用

**用**：一次任务需要 **≥2 次工具调用**，而且它们之间有循环、分支、汇总、互相依赖的关系。
典型：批量读 N 个文件后汇总、检索 → 写 → 复核的流水线、按条件挑选路径的多步编排。

**不用**：只需要一次工具调用（直接调对应工具更省 token、更好审计），或者任务没有工具调用（直接回答）。

## 代码里怎么调工具

```js
const rows = tools.grep({ pattern: 'TODO', path: '.' });   // args = 该工具的 inputSchema
const doc  = tools.read({ path: 'notes.txt' });
console.log('hits:', rows.count);                          // stdout 进响应的 stdout
({ hits: rows.count, chars: doc.text.length })             // 最后一个表达式 = 响应的 value
```

- 工具名就是文本工具名：`read / glob / grep / stat`（`ptc_run` 档），再加 `write / edit / remove`（`ptc_run_rw` 档）。
- **同步调用，不需要 `await`**（执行器侧是阻塞 IO）。若你确实写了 top-level `await`，执行器会把代码包进 async 函数，此时 `value` = `return` 的值。
- 调用的返回值 = 工具结果的 `value`（不是整个信封）。授权之外的工具名会抛结构化拒绝（`e.ptcCode === 'PTC_TOOL_DENIED'`）。
- 另有 `ptc.exec_id` / `ptc.profile` / `ptc.tools` / `ptc.args`（请求里的 `args` 透传）。

## 请求字段（都可不填，缺省即最保守）

| 字段 | 说明 |
|---|---|
| `code` | ✅ 代码本体（≤256 KiB） |
| `profile` | `readonly`（缺省）\| `readwrite`；`ptc_run` 不接受 `readwrite` |
| `allowed_tools` | 只能**收窄**档位：如 `["read","grep"]` |
| `timeout_ms` | 缺省 30000，上限 300000 |
| `limits` | `max_tool_calls`(64) / `max_output_bytes`(65536) / `max_memory_mb`(1024) / `max_cpu_ms`(60000)，只能收紧 |
| `cwd` / `fs_roots` | 默认必须落在插件 workspace 根内；部署若设了 `PTC_FS_ROOTS`/`PTC_FS_ROOTS_OPEN=1`，可点名额外/任意已存在目录（见 SKILL §5） |
| `args` | 透传给代码的参数字典 |

## 响应

```json
{"ok": true, "value": {"hits": 2}, "stdout": "hits: 2\n", "stderr": "", "duration_ms": 111,
 "tool_calls": [{"seq":1,"tool":"grep","args":{...},"ok":true,"duration_ms":0,"result_bytes":145}],
 "tool_call_count": 2, "files_written": [], "error": null, "usage": {...}}
```

`ok=false` 时 `error.category` 是闭集，**按它决定下一步**：

| category | 含义 | 你该做什么 |
|---|---|---|
| `SYNTAX_ERROR` | 代码没跑起来（编译失败） | 修语法，重发 |
| `RUNTIME_ERROR` | 代码抛了未捕获异常 | 读 `error.message` / `detail.stack` 修 |
| `TOOL_CALL_ERROR` | 某次工具调用失败（路径不存在、正则非法…） | 改参数重试；`tool_calls[]` 里有失败那条 |
| `TIMEOUT` | 超时被杀 | 拆分任务或调小范围 |
| `LIMIT_EXCEEDED` | 撞工具调用次数/输出/内存（V8 堆上限）上限 | 缩小任务 |
| `POLICY_DENIED` | 触安全政策（模块加载/联网/起进程/越根/未授权工具） | **不要试图绕过**，换做法 |

## fs_roots 与工作区（缺陷③，2026-11-02）

**缺省**：唯一可访问根 = `${PLUGIN_DATA}/workspace`（插件自己的沙盒目录），缺省 cwd 也在里面。
delegate 会话的工作区是它的 worktree，**不在**这个根下，所以缺省配置下读自己工作区的
`notes/` 会全量 `PTC_POLICY_FS`（"path escapes the authorised fs_roots"）。

三种显式覆盖（任一即可，全部走 mcp.json 的 `env` 或引擎进程环境）：

| 变量 | 作用 |
|---|---|
| `PTC_FS_ROOTS` | pathsep 分隔的**额外根**，并入缺省根成为新的上界（如 `D:/work/tree1;D:/work/tree2`） |
| `PTC_FS_ROOTS_OPEN=1` | 允许**请求级** `fs_roots` 点名任意**已存在目录**（每次 `ptc_run` 仍过宿主 `McpToolGate` 审批）；请求给了 `fs_roots` 而没给 `cwd` 时，cwd 缺省取第一个根 |
| `PTC_CWD` | 缺省 cwd 覆盖（须落在最终 `fs_roots` 内） |

mcp.json 例子（`${PLUGIN_DATA}` / `${PLUGIN_ROOT}` 会被装载器展开）：

```json
{"mcpServers": {"ptc": {"type": "stdio", "command": "python3",
  "args": ["${PLUGIN_ROOT}/skills/ptc-python/scripts/ptc_executor.py"],
  "env": {"PTC_FS_ROOTS": "${PLUGIN_DATA}/workspace;D:/workspace/mytree"},
  "timeoutMs": 600000}}}
```

**已知边界**：插件 MCP server 按插件引用计数、**多会话共享一个进程**，所以 env 是
「谁先起谁生效」的静态值——不能做成 per-session 的工作区。要 per-session 边界就用
`PTC_FS_ROOTS_OPEN=1` + 每次请求自带 `fs_roots`（收窄在请求面，随会话变化）。

## 语言边界（**只吃 JavaScript，不做类型转译**）

本插件跑的是**纯 JavaScript**（ES2022，node 语法校验直接执行，无转译步骤）：

```js
const x: string = 'a';        // ❌ SYNTAX_ERROR —— TypeScript 类型注解不会被剥掉
const x = 'a';                // ✅
interface Foo { a: string }   // ❌ 同上
```

要类型请用 JSDoc 注释（`/** @type {string} */`），它对运行期零影响。
理由：转译需要 tsc/esbuild/swc 常驻，而插件挂载面禁止构建步骤、node ≥20 也没有
`--experimental-strip-types`（node ≥22.6 才有）——与其叫 TypeScript 却在不转译时抛
`PTC_SYNTAX`，不如把名字说成它真正支持的东西（缺陷④，2026-11-02 改名 ptc-typescript → ptc-javascript）。

## 硬边界（撞上即 POLICY_DENIED）

- 上下文里没有 `require` / `import` / `process` / `fetch`；**模块加载、联网、起进程一律不可用**。
- 文件访问经 `tools.*` 且限制在 `fs_roots` 内（Node 权限模型兜底）；写能力只由 `ptc_run_rw` 给。
- 代码里的每次工具调用都会记进 `tool_calls[]` 台账，宿主会看。
