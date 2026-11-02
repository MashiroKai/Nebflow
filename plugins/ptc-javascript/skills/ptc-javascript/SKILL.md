---
name: ptc-javascript
description: PTC（Programmatic Tool Calling）JavaScript（Node）承载件的作业指令：ptc_run / ptc_run_rw 的请求面、代码里的 tools.* 绑定（同步调用/可选 await）、响应与错误分类判读、限额与安全边界、排障。用于需要模型写代码编排多次工具调用的任务，或 ptc_run 报错需要判读时。
---

# ptc-javascript 作业指令

契约（权威）：`skills/ptc-javascript/contract/README.md`（= 仓内 `contracts/ptc/v1/README.md` 同字节副本）。

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

## 2b. 语言边界（缺陷④，2026-11-02 改名）

本插件**只吃纯 JavaScript（ES2022）**：模型写的代码由 `node` 直接执行，**没有转译步骤**。
TypeScript 类型注解（`const x: string = …`、`interface`、`as Foo`）会直接落 `PTC_SYNTAX`。

- 为什么不再叫 TypeScript：插件挂载面禁止构建步骤（dist 预编译入库、装载侧不跑 npm），
  而 node ≥20 没有 `--experimental-strip-types`（node ≥22.6 才内建剥类型）。转译要么
  依赖常驻 tsc/esbuild/swc（部署面不可保证），要么就是「名字叫 TS、实际不转译」——
  2026-11-02 作者裁定取后者之反：**改名 ptc-javascript，把限制写死在这里**。
- 要类型信息就写 JSDoc（`/** @type {number[]} */`），运行期零成本。
- 想恢复 TS 支持 = 新增一个真正的转译前置（`PTC_TS_TRANSPILER=<tsc|esbuild|swc 绝对路径>`），
  插件名随之改回——属后续批次，不在本版。

## 3. 排障对照

| 现象 | 判读 |
|---|---|
| `PTC_RUNTIME_UNAVAILABLE` | 执行面没有 node ≥20（权限模型要求） |
| `PTC_SYNTAX` + `Unexpected token ':'` | 代码里带了 TypeScript 类型注解——本插件不转译，删掉注解（见 §2b） |
| `PTC_POLICY_IMPORT` | 代码里用了 `import()` / `require`：上下文不提供模块加载 |
| `PTC_INVALID_REQUEST` + `readonly entry point` | 用 `ptc_run` 传了 `profile=readwrite` |
| `PTC_TOOL_DENIED` | 代码调了档位外的工具 |
| `PTC_LIMIT_MEMORY` | V8 堆上限（`--max-old-space-size`）撞线，或父进程轮询 RSS 超限 |
| `RUNTIME_ERROR: process is not defined` | 试图从 vm 逃逸：上下文里没有 process，属预期 |

## 4. 手工验证

```bash
node scripts/ptc/run-conformance.mjs --only ptc-javascript
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
