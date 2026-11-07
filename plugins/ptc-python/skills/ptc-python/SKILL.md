---
name: ptc-python
description: PTC（Programmatic Tool Calling）Python 承载件的作业指令：ptc_run / ptc_run_rw 的请求面、代码里的 tools.* 绑定、响应与错误分类判读、限额与安全边界、排障。用于需要模型写代码编排多次工具调用的任务，或 ptc_run 报错需要判读时。
---

# ptc-python 作业指令

契约（权威）：`skills/ptc-python/contract/README.md`（= 仓内 `contracts/ptc/v1/README.md` 同字节副本）。

## 1. 运行时形态

| 层 | 进程 | 作用 |
|---|---|---|
| 宿主（agent 核心） | 引擎 JVM | 以 MCP stdio 挂载本插件，只看到 `ptc_run` / `ptc_run_rw` 两个工具 |
| 执行器 | `scripts/ptc_executor.py`（python3，stdio MCP server） | 校验请求、spawn 运行器、转发工具调用、记账限额 |
| 运行器 | `scripts/ptc_runner.py`（`python -I -B` 子进程） | 跑模型写的代码；`sys.addaudithook` 做政策拦截 |
| 工具面 | `scripts/ptc_tools_fs.py`（stdio MCP server） | 真正的宿主字节面：read/write/edit/glob/grep/stat/remove |

反向通道：**执行器是工具 server 的 MCP client**（标准 MCP，无协议扩展）。代码里的每次 `tools.x()` →
执行器 → 工具 server → 宿主字节 → 原路返回并记进台账。

## 2. 排障对照

| 现象 | 判读 |
|---|---|
| `PTC_RUNTIME_UNAVAILABLE`（tool server failed to start） | 执行面没有 `python3`，或 `PLUGIN_ROOT` 没注入到 MCP 进程 |
| `PTC_INVALID_REQUEST` + `ptc_run is the readonly entry point` | 用 `ptc_run` 传了 `profile=readwrite`；写要用 `ptc_run_rw` |
| `PTC_TOOL_DENIED` | 代码调了档位外的工具（如只读档调 `write`） |
| `PTC_POLICY_FS` | 路径跑出 `fs_roots`，或只读档做了写 |
| `PTC_POLICY_NETWORK` / `PTC_POLICY_SUBPROCESS` | 代码试图 `import socket` / `subprocess` / `ctypes` |
| `PTC_TIMEOUT` | 整个执行超 `timeout_ms`（不是单次工具调用） |
| `PTC_LIMIT_MEMORY` | Windows：Job Object 上限；POSIX：`RLIMIT_AS` |
| `tool_calls[]` 里某条 `ok=false` | 那次工具调用失败，`error.code` 是原因 |

## 3. 手工验证（不起引擎）

```bash
# 直接用 MCP client 打一次（仓内 harness）
node scripts/ptc/run-conformance.mjs --only ptc-python
```

## 5. fs_roots 与工作区（缺陷③，2026-11-02）

缺省只有一个根：`${PLUGIN_DATA}/workspace`（插件自己的沙盒），cwd 也在其中。delegate
会话的工作区是它的 worktree，**不在**这个根下 ⇒ 缺省配置读自己工作区的 `notes/` 会全量
`PTC_POLICY_FS`。三档显式覆盖（写进 mcp.json 的 `env`，或引擎进程环境；`${PLUGIN_DATA}`
/`${PLUGIN_ROOT}` 由装载器展开）：

| 变量 | 作用 |
|---|---|
| `PTC_FS_ROOTS` | pathsep 分隔的额外根，并入缺省根成为新上界 |
| `PTC_FS_ROOTS_OPEN=1` | 允许请求级 `fs_roots` 点名任意**已存在目录**（每次 `ptc_run` 仍过 `McpToolGate`）；请求给了 `fs_roots` 没给 `cwd` 时 cwd 缺省取第一个根 |
| `PTC_CWD` | 缺省 cwd 覆盖（须在最终 `fs_roots` 内） |

插件 MCP server 是**多会话共享进程**（引用计数），env 是静态值——要 per-session 边界
用 `PTC_FS_ROOTS_OPEN=1` + 每次请求自带 `fs_roots`。

## 6. 两侧工具语义（缺陷⑤，2026-11-02 对齐）

`ptc-python` 与 `ptc-javascript` 的 `ptc_tools_fs` **行为逐字一致**，由
`scripts/ptc/fs-contract.mjs`（同一 fixture、同一用例、两侧输出逐字比对）把守：

- `read`：`text` = 按行切片的**原始子串**，行终止符随行保留（读整文件与文件内容逐字
  相等；旧实现 `splitlines()` 吃行尾 ⇒ 138 vs 141）。
- `glob`：段式 shell glob，`**` 跨段（含零段）、`*` 段内、`?` 单字符、`[..]` 类；
  返回相对 `base` 的 `/` 路径，排序后取前 200，`truncated` = 命中数>200。
- `grep`：返回 `{path,line,text}`，path 相对 base、按 (path,line) 排序、text 去掉
  行终止符（含尾随 `\r`）；命中超 `max_results` 时 `count` 为截断后条数 + `truncated`。
- `bytes`：value 的**紧凑 JSON** 字节数（两侧同一算式）。
