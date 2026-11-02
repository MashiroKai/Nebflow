# ptc-typescript —— PTC 模式（用 JavaScript/TypeScript 代码编排工具调用）

你获得了两个工具：`ptc_run`（只读档）与 `ptc_run_rw`（读写档）。**你写一段 JS/TS 代码，代码在受控子进程里跑，跑的过程中可以调宿主工具；一次调用把整段程序的 stdout、返回值、工具调用台账和结构化错误一起还给你。**

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
| `cwd` / `fs_roots` | 都必须落在插件声明的 workspace 根内 |
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

## 硬边界（撞上即 POLICY_DENIED）

- 上下文里没有 `require` / `import` / `process` / `fetch`；**模块加载、联网、起进程一律不可用**。
- 文件访问经 `tools.*` 且限制在 `fs_roots` 内（Node 权限模型兜底）；写能力只由 `ptc_run_rw` 给。
- 代码里的每次工具调用都会记进 `tool_calls[]` 台账，宿主会看。
