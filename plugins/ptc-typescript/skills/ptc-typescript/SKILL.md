---
name: ptc-typescript
description: PTC（Programmatic Tool Calling）TypeScript/Node 承载件的作业指令：ptc_run / ptc_run_rw 的请求面、代码里的 tools.* 绑定（同步调用/可选 await）、响应与错误分类判读、限额与安全边界、排障。用于需要模型写代码编排多次工具调用的任务，或 ptc_run 报错需要判读时。
---

# ptc-typescript 作业指令

契约（权威）：`skills/ptc-typescript/contract/README.md`（= 仓内 `contracts/ptc/v1/README.md` 同字节副本）。

## 1. 运行时形态

| 层 | 进程 | 作用 |
|---|---|---|
| 宿主（agent 核心） | 引擎 JVM | 以 MCP stdio 挂载本插件，只看到 `ptc_run` / `ptc_run_rw` 两个工具 |
| 执行器 | `dist/ptc_executor.js`（node，stdio MCP server） | 校验请求、spawn 运行器、转发工具调用、记账限额 |
| 运行器 | `dist/ptc_runner.js`（`node --experimental-permission --max-old-space-size=N`） | 跑模型写的代码；vm 上下文 + 权限模型 + 网络 patch |
| 工具面 | `dist/ptc_tools_fs.js`（stdio MCP server） | 真正的宿主字节面：read/write/edit/glob/grep/stat/remove |

反向通道：**执行器是工具 server 的 MCP client**（标准 MCP，无协议扩展）。

## 2. 源码与产物

- 源：`src/*.ts`（TypeScript，strict）。
- 产物：`dist/*.js`（ESM，由 `tsc` 编译；`dist/package.json` 声明 `"type":"module"`）。
- 重新构建：`node scripts/ptc/build-ts.mjs`（临时装 typescript，编译后清理）。
- **`dist/` 是故意入库的产物**（插件挂载时不允许有构建步骤）：仓根 `.gitignore:50` 忽略 `dist/`，
  这两个包用 `git add -f` 显式收编；改动 `src/` 后必须重新编译并 force-add，否则装载侧跑的是旧产物。

## 3. 排障对照

| 现象 | 判读 |
|---|---|
| `PTC_RUNTIME_UNAVAILABLE` | 执行面没有 node ≥20（权限模型要求） |
| `PTC_POLICY_IMPORT` | 代码里用了 `import()` / `require`：上下文不提供模块加载 |
| `PTC_INVALID_REQUEST` + `readonly entry point` | 用 `ptc_run` 传了 `profile=readwrite` |
| `PTC_TOOL_DENIED` | 代码调了档位外的工具 |
| `PTC_LIMIT_MEMORY` | V8 堆上限（`--max-old-space-size`）撞线，或父进程轮询 RSS 超限 |
| `RUNTIME_ERROR: process is not defined` | 试图从 vm 逃逸：上下文里没有 process，属预期 |

## 4. 手工验证

```bash
node scripts/ptc/run-conformance.mjs --only ptc-typescript
```
