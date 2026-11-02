---
type: audit
status: 修复批（六条实测缺陷 + 独立实例复验）
title: PTC 两插件六条实测缺陷——根因、修法与独立实例复验
date: 2026-11-02
batch: delegate-nebflow-e616097f
tags: [ptc, plugin, mcp, allocator, timeout, rg, fs-roots, audit]
related:
  - "[[20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa]]"
  - "[[20261102_173500_ptc-agent-dispatch-feasibility]]"
  - "[[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]]"
---

# PTC 两插件六条实测缺陷——根因、修法与独立实例复验

> 上一批（[[20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa]]）证明了
> 「插件式 PTC 能真跑通，零行 scala 改动」。本批处理的是**跑通之后暴露的六条实测缺陷**：
> 能力在，但慢模型下会静默消失、预算字段被装载器吃掉、工作区对不上、语言名不实、两个
> 插件行为互相打架、内置 Grep 直接起不来。六条全部修完，并在独立实例（18123 / 新 jar）
> 上复验；第三段把「让 PTC 脚本编程式派 agent」的可行性单独成文（
> [[20261102_173500_ptc-agent-dispatch-feasibility]]）。

---

## 0. 一页结论

| # | 缺陷 | 结论 | 根因落点 | 复验证据 |
|---|---|---|---|---|
| ① | 插件自动分配 60s 上限 + fail-open 静默降级 | **修了**（抬预算 + 超时重试 + 失败显式化） | `OpenAiAdapter.scala:368` / `AnthropicAdapter.scala:473`（缺 `readTimeout` ⇒ sttp 默认 60s）；`PluginInjection.scala:103-109`（失败转空集） | `reports/20261102_ptc-defect-fixes/`（mock 70s 慢响应复验 + 单测） |
| ② | `mcp.json` 的 `timeoutMs` 被装载器丢弃 | **修了** | `PluginRegistry.scala:1060-1064`（白名单缺项）+ `1129-1138`/`1161-1170`（`timeoutMs = None` 硬编码） | `McpManager.scala` budget INFO 行 + `PluginMcpProtocolSpec` 三例 |
| ③ | `fs_roots` 与会话工作区不一致（delegate 读不到自己工作区） | **修了**（显式配置项 + 请求级收窄） | `plugins/ptc-*/plugin.json` 的 `workspace.fs_roots` 恒为 `${PLUGIN_DATA}/workspace` | 实例内 ptc_run_rw 直接读写工作区 |
| ④ | ptc-typescript 不吃类型注解 | **改名为 `ptc-javascript`**（另一条路要转译器，本机不具备；二选一，取明确的那个） | 无转译步骤：node 20 无 `--experimental-strip-types`（≥22.6 才有），无 tsc/esbuild/swc | 插件名/枚举/SKILL/Plugin.md 全量改名 + 类型注解显式报错 |
| ⑤ | 两插件 `glob`/`read` 语义不一致 | **修了**（同一份契约用例把守） | py `str.splitlines()` 吃行尾换行；py `fnmatch` 不认 `**`；两侧 `bytes` 算式不同 | `scripts/ptc/fs-contract.mjs` 15/15 逐字一致（修复前 14/15 红） |
| ⑥ | `Nebflow\rg.exe` 缺失导致内置 Grep 崩（error=267） | **修了**（真因是 cwd 不是目录；品牌路径是第二刀） | `GrepTool.scala:211`（未校验 `user.dir` 当 cwd）+ `RgHelper.scala:50-53`（rebrand 漏网的 `Nebflow` 字面量） | 实例内 Grep 真调通 |

---

## 1. 缺陷①：插件自动分配腿 60 秒上限 + fail-open 静默降级

### 1.1 现象（上一批实测）

慢模型 `ustc/deepseek-v4-flash-ascend` 下，分配耗时**恰好** 60.019s，然后 `plugins=[]`：
模型拿不到 `ptc_run`，改用内置工具把任务做完并"看起来正常完成"。换 `USTC/deepseek-flash`
同提示词 22s 答完就正确返回。分配器自己的代码明确写着「零超时」——所以上限不在分配器，
在更下面的那一层。

### 1.2 根因（两处，缺一不可）

**(a) 60s 来自 sttp client4 的非流式默认 readTimeout。**
分配腿走 `ProviderAdapter.sendMessage`（**非流式**），该请求构造时**没有**给 `readTimeout`：

- `src/main/scala/nebflow/llm/providers/OpenAiAdapter.scala:368-374`

```scala
    val request = basicRequest
      .post(uri"$endpoint")
      .header("Authorization", s"Bearer $apiKey")
      .header("content-type", "application/json")
      .body(bodyWithMetadata.noSpaces)
    IO.delay(logSearchInjection(params)) *>
      backend.send(request).flatMap { response =>   // ← 无 readTimeout ⇒ sttp 默认 60s
```

- `src/main/scala/nebflow/llm/providers/AnthropicAdapter.scala:473-480` 同款（协议为 Anthropic 时命中）。

三条独立旁证咬合：

1. 仓内**已有人写下这个事实**并修过同类面——`src/main/scala/nebflow/llm/decision/DecisionHttpTransport.scala:55-57`：

```scala
          // sttp otherwise applies its own 60s default read timeout — an
          // EXPLICIT Undefined is the only form that disables the kill.
          .readTimeout(Duration.Undefined)
```

2. 唯一显式给了 600s 的是**流式**面（`OpenAiAdapter.scala:480` / `AnthropicAdapter.scala:578`
   `.readTimeout(Defaults.LlmReadTimeoutSec.seconds)`，`Defaults.scala:252-256` 里
   `LlmReadTimeoutSec = 600`）——完全解释「主 agent 正常、分配腿必死 60s」的非对称。
3. 60.019s 与 60s 缺省精确吻合（+19ms = 建连 + 读尾差）。

分配器源码的「零超时」自述属实（应用层确实一个定时器都没有）：
`src/main/scala/nebflow/llm/decision/LlmAllocator.scala:154-156` 与
`PluginAllocatorMachine.scala:175-180`。**误判就出在这里：以为"零超时"= 没有上限，
实际传输层替它上了一个 60s 的闸。**

**(b) 失败被翻译成空集（静默降级点）。**
60s 抛出的 `HttpTimeoutException` 在候选链里只留一行 WARN
（`LlmAllocator.scala:237-257`，旧实现只打异常类名），随后：

- `src/main/scala/nebflow/core/project/PluginInjection.scala:103-109`（修复前）：

```scala
                    case JevAllocation.FellBack(JevFallbackKind.LlmError, _)  => ("llm-error", Nil)
                    ...
                  else if names.isEmpty then IO.pure(Right(NodeEngine.PluginPreparation.empty -> AllocatedSet(Nil, mode)))
```

`llm-error` 被归进「names 为空」的分支，和「模型判定确实不需要插件」**走同一条 `Right(空集)`**
——派发面拿不到任何失败信号。这就是「看起来正常完成」的那一行。

### 1.3 修法（三刀）

| 刀 | 落点 | 内容 |
|---|---|---|
| 抬预算 | `OpenAiAdapter.scala:368-378`、`AnthropicAdapter.scala:473-481` | 非流式请求补 `.readTimeout(Defaults.LlmReadTimeoutSec.seconds)`（600s，与流式面同口径） |
| 超时重试 | `LlmAllocator.scala`（`chainCall` 内新增 `oneShot` + `isTimeoutOrTransport`） | 超时/传输类失败**先原样重试同一候选一次**，仍败才落下一候选（单候选部署下"换下一候选"等于没重试） |
| 去掉静默降级 | `PluginInjection.scala`（`FailureModes` + `failureReason`）、`PluginsConfig.failOpenOnAllocationError` | `llm-error`/`parse-fail`/`fail-open` 三个模式词 ⇒ **`Left`**（派发显式失败，错误里点名模式词 + 真因 + 回退开关）；缺省 fail-closed，`plugins.failOpenOnAllocationError=true` 一键回退旧语义（仍留 WARN，不再无声） |

配置位（`nebflow.json`）：

```json
{"plugins": {"enabled": true, "autoAllocate": true, "failOpenOnAllocationError": false}}
```

### 1.4 复验

- 单测：`nebflow.llm.decision.LlmAllocatorSpec` 新增两例（超时重试同一候选成功 / 重试仍败落下一候选），
  `nebflow.core.project.PluginInjectionSpec` 把原来的 fail-open 断言改为 fail-closed + 回退位两例。
  `sbt "testOnly …"` → `Passed: Total 56, Failed 0, Errors 0`。
- 实例级：`scripts/ptc/mock-llm.mjs` 造 70s 慢分配响应（旧 jar 必在 60s 被掐），
  详见 §7 复验记录与 `reports/20261102_ptc-defect-fixes/`。
- **能把本条打红的复现脚本**：`scripts/ptc/repro-defect1.sh`
  （`--jar <旧jar> --home D:/ptc-e2e/home3 --port 18125 --workspace D:/ptc-e2e/home3/general`），
  判定依据是日志里那一行 `plugin allocation '<id>': … plugins=[…]`：
  旧 jar ⇒ 观察窗内没有任何成功分配行（60s 被掐后 `plugins=[]` 继续派发）；
  本批 jar ⇒ 70s 后出现 `plugins=[ptc-python,ptc-javascript]`。
  两次运行的原始输出：`reports/20261102_ptc-defect-fixes/repro-defect1.RED.txt` / `.GREEN.txt`。

---

## 2. 缺陷②：`mcp.json` 的 `timeoutMs` 被装载器丢弃

### 2.1 现象

`/api/plugins` 直接告警 `unknown field 'timeoutMs' ignored`；插件面永远拿不到 600s 调用预算
（`McpServerConfig.timeoutMs` 是引擎侧真字段，运行期 `McpManager` 会用它给 `tools/call` 上限）。

### 2.2 根因

`src/main/scala/nebflow/core/plugin/PluginRegistry.scala:1060-1064`（修复前）：

```scala
      sj.asObject.foreach(_.keys.foreach { k =>
        if !Set("type", "command", "args", "env", "cwd", "url", "headers").contains(k) then
          warnings += s"mcp server '$name' unknown field '$k' ignored (forward-compat)"
      })
```

白名单比 `McpServerConfig`（`src/main/scala/nebflow/shared/config.scala:204-221`）窄，
而且两个分支把字段**硬写成 None**：`PluginRegistry.scala:1129-1138`（stdio）与
`1161-1170`（http）——`timeoutMs = None`。下游本来全通：`PluginMcpManager.scala:45-52`
按值透传、`272-276` 用 `copy` 展开、`McpManager.scala:84` `cfg.timeoutMs.map(_.millis)`
直接消费。所以只断在装载层。

### 2.3 修法

1. 白名单单点化：新增 `PluginRegistry.McpEntryKnownFields`（含 `timeoutMs`，注释写明它是
   **本引擎 R3 扩展**、非上游 1.0.0 字段）。
2. 两个分支各解一次 + 校验：非整数 ⇒ entry invalid；`<= 0` ⇒ invalid（0 会让每次
   `tools/call` 立刻超时，语义无意义）。
3. `timeoutMs = None` → `timeoutMs = timeoutMs`。
4. 可观测证据：`McpManager.connectWithTransport` 连上即打
   `MCP server '<id>' tools/call budget: 600000ms (from config timeoutMs)`（`none (unbounded)` 即没透传过来）。

### 2.4 复验

- 单测 `PluginMcpProtocolSpec`：stdio 透传 600000、http 透传 1234、`0`/`"60s"` 各拒，
  且**不再出现** `unknown field 'timeoutMs'` 告警。
- 实例：`<dataRoot>/logs/stand.log` 出现上述 budget 行（见 §7）。

---

## 3. 缺陷③：`fs_roots` 与会话工作区不一致（最影响可用性）

### 3.1 现象

`fs_roots` 缺省 `${PLUGIN_DATA}/workspace`，于是 delegate 读自己工作区的 `notes/` 全量
`PTC_POLICY_FS`（`path escapes the authorised fs_roots`），必须先探出插件 workspace 才能干活。

### 3.2 根因

- 声明面：`plugins/ptc-python/plugin.json` 的 `extensions.ptc.workspace.fs_roots =
  ["${PLUGIN_DATA}/workspace"]`（ptc-javascript 同款）。
- 执行器把该值当**唯一上界**，请求级 `fs_roots` 必须 ⊆ 它（`ptc_executor.py:341-349` 修复前）。
- 结构性原因：插件 MCP server 是**按插件引用计数的共享进程**（`PluginMcpManager.acquire`），
  所以「per-session 工作区」没法用 env 静态注入——这条约束决定了修法只能是
  「显式配置项 + 请求级收窄」。

### 3.3 修法（两侧同义，三个环境变量）

| 变量 | 语义 |
|---|---|
| `PTC_FS_ROOTS` | pathsep 分隔的**额外根**，并入缺省根成为新上界 |
| `PTC_FS_ROOTS_OPEN=1` | 允许**请求级** `fs_roots` 点名任意**已存在目录**（不存在的照拒）；请求给了 `fs_roots` 未给 `cwd` 时 `cwd` 缺省取第一个根 |
| `PTC_CWD` | 缺省 cwd 覆盖（须落在最终 `fs_roots` 内） |

三者都可写进 `mcp.json` 的 `env`（装载器展开 `${PLUGIN_ROOT}`/`${PLUGIN_DATA}`），
落点：`ptc_executor.py:57-95`（常量解析）、`:341-370`（`validate`）、ptc-javascript 的
`ptc_executor.ts` 对应段。文档：两个 `Plugin.md` 的「fs_roots 与工作区」节 + 两个 `SKILL.md` §5。

**默认行为不变**（仍只有 `${PLUGIN_DATA}/workspace`）——这是刻意的：放开面必须显式。

### 3.4 复验

实例里把 `<dataRoot>/plugins/ptc-*/mcp.json` 的 `env` 设 `PTC_FS_ROOTS_OPEN=1`，
delegate 侧一次 `ptc_run_rw` 直接用 `fs_roots=[<工作区>]` 读出 `notes/`、写回
`notes/ptc-python.json` / `notes/ptc-js.json`（见 §7 与证据目录）。这条同时证明
**mcp.json 的 `env` 能透传到插件进程**（装载 → 展开 → 子进程）。

---

## 4. 缺陷④：ptc-typescript 不吃类型注解 → 改名 ptc-javascript

### 4.1 事实核对（为什么选改名而不是接转译）

| 选项 | 事实 | 判定 |
|---|---|---|
| `node --experimental-strip-types` | 本机 `node -v` = **v20.20.2**；该 flag 从 **22.6** 才有 | 不可用 |
| `tsc` / `esbuild` / `swc` 常驻转译 | `where tsc` 无命中；无全局 node 包管理面；仓内 `scripts/ptc/build-ts.mjs` 是**构建期**临时 `npm i typescript`（插件装载面禁止构建步骤，`dist/` 预编译入库） | 运行期不可保证 |
| 自带一个类型注解剥离器 | 部分支持 = 更含糊（interface/generics/enum/`as const` 都会静默跑偏） | 不做 |

结论：按作者给的另一个选项**明确改名**——插件叫 `ptc-javascript`，只吃纯 JavaScript（ES2022），
把限制写死在 SKILL/Plugin.md/manifest description 三处。

### 4.2 改动面（共 5 类）

1. 目录/包名：`plugins/ptc-typescript` → `plugins/ptc-javascript`，
   `skills/ptc-typescript` → `skills/ptc-javascript`（`git mv` 保历史）。
2. manifest：`name`、`keywords`（`typescript` → `javascript`）、`runtime.kind: "javascript"`、
   `entry`、工具描述（`Execute JavaScript code…`）、description 里写明「无类型转译」。
3. 契约枚举：`contracts/ptc/v1/ptc-{manifest,request,response}.schema.json` 的
   `["python","typescript"]` → `["python","typescript","javascript"]`（两份插件内副本 +
   仓根一份逐字节同步，README 两行同步）。
4. 源码/产物：`src/*.ts` 改的是注释与 `SERVER_NAME`；`dist/` 用
   `node scripts/ptc/build-ts.mjs`（tsc 重编译）现出，不手改产物。
5. 文档与工具：两个 SKILL/Plugin.md、`scripts/ptc/*.mjs`（build/check/closed-loop/conformance）、
   `docs/index.md` 挂入本批两篇。

### 4.3 限制的落点（模型必须能读到）

- `plugins/ptc-javascript/Plugin.md` §语言边界：`const x: string = 'a'` ❌ / `const x = 'a'` ✅，
  要类型写 JSDoc。
- `skills/ptc-javascript/SKILL.md` §2b：为什么不转译、要恢复 TS 支持需要什么（后续批次）。
- 运行期行为：带注解的代码落 `PTC_SYNTAX`（node 语法校验直接拒），排障表新增该行。

---

## 5. 缺陷⑤：两插件 `glob`/`read` 语义不一致

### 5.1 现象

TS 返回相对 `base` 的路径；Python 的 `notes/**/*.md` 递归返回空；`read` 吃行尾换行
（同文件读出来 138 字符 vs 原始 141）。同一套工具两种行为，模型换插件就踩坑。

### 5.2 根因（四类）

| 症状 | 根因（file:line，修复前） |
|---|---|
| read 吃行尾 | `ptc_tools_fs.py:t_read` 用 `str.splitlines()` + `"\n".join()`：末行终止符与 `\r` 一起被吃掉，且 `splitlines` 还在 `\v`/`\f`/`\u2028` 处切行 |
| `notes/**/*.md` 返回空 | `fnmatch.fnmatch(rel, pattern)`：`*` 可跨 `/`，`**` 无"零段"语义 ⇒ `notes/x.md`（直接子文件）不匹配 |
| 路径口径 | 两侧都相对 `base`，但 ts 侧 `truncated` 取的是**遍历总数**>200 而不是命中数；两侧截断发生在排序前 ⇒ 同一目录两套结果 |
| `bytes` 不同 | py `json.dumps` 缺省分隔符（`, ` / `: `）vs js `JSON.stringify` ⇒ 同一 value 差几个空格 |

### 5.3 修法：一份写死的语义 + 一份两侧都跑的契约用例

语义（两侧逐字同义，写在两个 `ptc_tools_fs` 的头注释里）：

- `read`：`text` = 按行切片的**原始子串**（行终止符随行保留）⇒ 读整文件与文件内容逐字相等；
  `offset`/`limit` 按 `\n` 切的行计；`lines` = 选中行数。
- `glob`：段式 shell glob，`**` 跨段（含零段）、`*` 段内、`?` 单字符、`[..]` 字符类；
  返回相对 `base` 的 `/` 路径，**排序后**取前 200，`truncated` = 命中数 > 200。
- `grep`：`{path,line,text}`，path 相对 base、按 `(path,line)` 排序、`text` 去掉行终止符
  （含尾随 `\r`）；`count` = 截断后条数 + `truncated`。
- `bytes`：value 的**紧凑 JSON** 字节数（两侧同一算式）。

契约用例：`scripts/ptc/fs-contract.mjs`——同一 fixture、15 个用例（read 四种形态 /
glob 五种模式 / grep 三种 / stat 两态 / 越界拒绝），两侧各起一个 MCP stdio server，
把返回信封归一化后**逐字比对**，不一致即 exit 1 并打印两侧原文。

### 5.4 复验（含验红）

- 修复后：`RESULT: all 15 cases identical on both plugins`。
- 验红（`PTC_PY_TOOLS`/`PTC_JS_TOOLS` 指向 `git show HEAD:` 出来的修复前实现）：
  `RESULT: 14/15 case(s) DIVERGE`（唯一 PASS 的是越界拒绝那条）。
  产物：`reports/20261102_ptc-defect-fixes/fs-contract.transcript.txt`、
  `fs-contract.machine.json`、`fs-contract.PRE-FIX.transcript.txt`。

---

## 6. 缺陷⑥：`Nebflow\rg.exe` 缺失导致内置 Grep 崩（CreateProcess error=267）

### 6.1 现象

实例内置 Grep 直接 `CreateProcess error=267`，报错文案把程序路径顶到前面，读起来像
「发行包漏了 rg.exe」。

### 6.2 根因（两刀，不同轴）

**(a) 267 的真身是进程 cwd 不是目录，不是缺二进制。**
Windows 的 `lpCurrentDirectory` 非法时 `CreateProcess` 返回 `ERROR_DIRECTORY (267)`。
`GrepTool` 是唯一把**未校验**的 JVM `user.dir` 当 cwd 交给 ProcessBuilder 的 rg 调用方：

- `src/main/scala/nebflow/core/tools/GrepTool.scala:125`（取 `System.getProperty("user.dir")`）
  → `:211`（`RgHelper.runRg(args.toList, workDir)`，无 `processCwd`）
- `src/main/scala/nebflow/core/tools/RgHelper.scala:185-186`（修复前）
  `.directory(new File(processCwd.getOrElse(workDir)))` —— 直接把可能不存在的目录塞进去

对照：`GlobTool.scala:217-221` 早就把 cwd 钉到已 canonicalize 的搜索根，所以 Glob 没事。
指纹就是「Grep 死、Glob 活」。

**(b) `Nebflow\rg.exe` 是 rebrand 漏网字面量。**

- `RgHelper.scala:50-53`（修复前）`LOCALAPPDATA + "Nebflow" + rg.exe`——全 `src/main/scala` 里
  最后一处 `Nebflow` 安装根字面量（`DetachedApply.scala:167`、`UpdateStage.scala:78` 都已经叫 `stand`）。
- 而安装器自 REBRAND 起写的是 `%LOCALAPPDATA%\stand\rg.exe`
  （`release/install.ps1:522` `$InstallDir` + `:879` `ExtractToFile(… "rg.exe")`，
  `brand.conf:6` productName = stand；msi 侧 `packaging/build-msi.sh:199-214` 把 rg.exe 打进
  `<install>\app\rg.exe`）。
- 后果：**新装机的正确位置从不被探测**，只有旧名残留物才会被选中——于是「一个孤儿二进制
  反而优先于找不到」，还把错误消息指成 pre-rebrand 路径。

### 6.3 修法

1. `RgHelper.runRg`：只有 `cwd.isDirectory` 才 `.directory(cwd)`，否则留给父进程
   （rg 的搜索根本来就是绝对参数，不依赖 cwd）。
2. `GrepTool` 与 GlobTool 对齐：`runRg(..., processCwd = Some(searchRoot))`（已 canonicalize）。
3. `RgHelper` 第 5 腿改为品牌名优先 + 旧名兜底：
   `%LOCALAPPDATA%\<productName>\rg.exe`（`Branding.productName`）→ `%LOCALAPPDATA%\Nebflow\rg.exe`；
   纯函数签名 `winInstall: Option[String]` → `List[String]`（spec 同步 `None` → `Nil`）。
4. 回归测试（新）：`RgHelperSpec`「runRg: workDir 不是目录时不再把非法 cwd 交给 CreateProcess」
   —— workDir 指向不存在的 `Z:\nebflow-missing-cwd-267`，修复后 `Right`（cwd 落父进程），
   修复前是 `Left(... CreateProcess error=267)`；本机 `Passed: Total 12, Failed 0`。

### 6.4 部署前置条件（写清，不假装已解决打包面）

- msi 装：`<install>\app\rg.exe` 由 `packaging/build-msi.sh` 提供（`InstallLayout.bundledRg`）。
- jar/zip 装：`release/install.ps1` 下载到 `%LOCALAPPDATA%\stand\rg.exe`——**本批修完才被探测到**。
- 既没装也没在 PATH：Grep/Glob 会显式报 `ripgrep (rg) not found (checked …)`（自诊断清单），
  不是 267 那种误导性错误。
- 隔离实例（`--home` 指向临时根）不会自带到 rg，用的是宿主 `~/.stand/bin/rg`（`RgHelper.rgLocalPaths` 本就这样设计）。

---

## 7. 复验记录（隔离实例 18123 / 本批新编译 jar）

- **新 jar（必须的，本批改了 scala）**：
  `D:/workspace@zcode/nebflow/nebflow-stand/.nebflow/worktrees/delegate-nebflow-e616097f/target/scala-3.5.2/stand-assembly-2026.10.26-beta.2.jar`
  （`sbt -batch assembly` 产出；`~/.stand/bin/versions/…prereq4…` 那份是**改动前**版本，只用于验红）
- 数据根：`D:/ptc-e2e/home2`（本批复验）、`D:/ptc-e2e/home3`（=home2 副本，专用于①的验红，sessions/logs/delegates 已清空）
- 端口：18123（复验）/ 18125（①验红）。作者的 6138 与 pid 21516 全程未碰，`~/.stand/neblink/` 未动。
- 假模型：`scripts/ptc/mock-llm.mjs`（OpenAI 兼容；分配腿**延迟 70s** 才回包；agent turn 走 SSE 流式面——
  缺 SSE 时引擎会把 provider 标 DOWN，那是另一个假故障，与本条无关）
- 驱动：`scripts/ptc/ptc-e2e-drive.mjs`；双侧契约：`scripts/ptc/fs-contract.mjs`；直连探针：`scripts/ptc/ptc-probe.mjs`
- 原始文件全部在 `reports/20261102_ptc-defect-fixes/`（`green-run.log.txt` / `repro-defect1.RED.txt` /
  `repro-defect1.GREEN.txt` / `fs-contract.*` / `ptc-probe.json` / `wiki-check.txt`）

### 7.1 缺陷①：70s 慢分配不再被掐、也不再静默降级

**验红（改动前 jar，home3 / 18125）** —— 恰好 60,025ms 失败，然后静默以空插件集派发：

```
18:05:25.192 INFO  nebflow.llm.plugin-alloc - plugin allocation 'delegate-nebflow-6c8e2844': llm fallback leg …
18:06:25.217 WARN  nebflow.llm.plugin-alloc - llm allocator: candidate MOCK/mock-slow errored (TimeoutException) — trying next candidate
18:06:25.221 INFO  nebflow.core.plugin.injection - plugin allocation 'delegate-nebflow-6c8e2844': via=- plugins=[] duty=AllocatedViaFallback(llm allocator: call failed (candidate chain exhausted …))
18:06:25.376 INFO  nebflow.handlers - … Tool Delegate(用 ptc-python 与 ptc-javascript 两个插件… ) OK (60234ms)
```

同一时刻假模型侧看到的 delegate spawn 工具面是 `tools:15`（一个 ptc_* 都没有），它如实回
`No PTC tool available; task finished with builtin tools.`——上层却拿到 `OK`。

**修复后（本批 jar，home2 / 18123）** —— 70,044ms 才回，插件集非空，派发继续：

```
17:58:40.868 INFO  nebflow.llm.plugin-alloc - plugin allocation 'delegate-nebflow-f860c0fd': llm fallback leg (jev gate off (jev block absent))
17:59:50.912 INFO  nebflow.core.plugin.injection - plugin allocation 'delegate-nebflow-f860c0fd': via=llm-fallback plugins=[ptc-python,ptc-javascript] duty=Allocated(false)
```

### 7.2 缺陷②：`timeoutMs` 真的进到调用预算

```
17:59:50.942 INFO  nebflow.mcp - MCP server 'plugin_ptc-python_ptc' tools/call budget: 600000ms (from config timeoutMs)
17:59:50.990 INFO  nebflow.mcp - MCP server 'plugin_ptc-python_ptc' connected, 2 tools registered
17:59:50.995 INFO  nebflow.mcp - MCP server 'plugin_ptc-javascript_ptc' tools/call budget: 600000ms (from config timeoutMs)
17:59:51.038 INFO  nebflow.mcp - MCP server 'plugin_ptc-javascript_ptc' connected, 2 tools registered
```

（`/api/plugins` 侧同时不再出现 `unknown field 'timeoutMs' ignored`——见 §2.4 单测断言。）

### 7.3 缺陷③：delegate 在自己的工作区里读写

三次 `ptc_run_rw` 全部落在 delegate 会话自己的 seat（`home2/general`），且在途有 gate 审计行：

```
17:59:51.251 INFO nebflow.audit - event=mcpGate decision=allow tool=mcp__plugin_ptc-python_ptc__ptc_run_rw surface=mcp tier=L0 form=host-executor session=delegate-nebflow-f860c0fd
17:59:51.353 INFO nebflow.handlers - … Tool [MCP] ptc_run_rw OK (101ms)
17:59:51.373 INFO nebflow.handlers - … Tool [MCP] ptc_run_rw OK (120ms)
17:59:51.384 INFO nebflow.handlers - … Tool [MCP] ptc_run_rw OK (131ms)
```

落盘物证：`D:\ptc-e2e\home2\general\notes\ptc-js.json`（工作区相对路径直接写进 delegate 的 seat，
修复前同一份代码会落进插件进程的工作目录）。③ 的请求级覆盖（`fs_roots`）也在这条链上被用到——
delegate 侧工具参数如下（`code` 里的 `notes/**/*.md` 相对的就是这个根）：

```json
{"code": "…tools.glob({ pattern: 'notes/**/*.md' })…", "language": "javascript",
 "profile": "readwrite", "fs_roots": ["D:/ptc-e2e/home2/general"]}
```

### 7.4 缺陷④：ptc-javascript 对类型注解给出显式 `PTC_SYNTAX`

`scripts/ptc/ptc-probe.mjs` 直连两个 MCP server 跑 `const x: string = 'a';`：

```json
{"ok":false,"error":{"category":"SYNTAX_ERROR","code":"PTC_SYNTAX","phase":"compile",
 "message":"Missing initializer in const declaration",
 "detail":{"type":"SyntaxError","stack":"<ptc>:1\nconst x: string = 'a';\n      ^\n…"}}}
```

是**编译期明确报错 + 稳定错误码**，不再是「跑一半莫名 undefined」；限制同时在 manifest /
SKILL.md / Plugin.md 三处写明（模型能在首屏读到）。

### 7.5 缺陷⑤：两侧 `read/glob/grep/bytes` 逐字一致

- 契约用例（同一 fixture、同一 15 个用例、两侧各起一个 MCP stdio server）：
  `RESULT: all 15 cases identical on both plugins`（`reports/…/fs-contract.transcript.txt`）
- 验红（同一脚本指向 `git show HEAD:` 的修复前实现）：`RESULT: 14/15 case(s) DIVERGE`
  （`reports/…/fs-contract.PRE-FIX.transcript.txt`）
- 实例内直连探针（两个 server 各跑同一份编排、比较返回值）：

```json
{"pythonOk":true,"jsOk":true,"valuesEqual":true,
 "pythonFilesWritten":["notes/probe-python.json"],"jsFilesWritten":["notes/probe-js.json"]}
```

### 7.6 缺陷⑥：内置 Grep 正常返回

```
17:59:51.270 INFO nebflow.handlers - … Tool Grep("TODO")
17:59:51.270 INFO nebflow.handlers -   (path="D:/ptc-e2e/home2/general") OK (33ms)
```

同机对照：`where rg` → `C:\Users\Administrator\AppData\Local\Nebflow\rg.exe`——这台机器上
**只有旧名目录里有 rg**，本批「品牌名优先 + 旧名兜底」两条腿都还能命中，说明兜底腿没被写坏。
267 本身的回归靠单测（workDir 指向不存在的 `Z:\…`，见 §6.3 第 4 条）。

### 7.7 门禁自检

- `sbt -batch test`（本批三份 spec）：`Passed: Total 56, Failed 0, Errors 0`
- `sbt -batch "testOnly nebflow.core.tools.RgHelperSpec"`：`Passed: Total 12, Failed 0, Skipped 1`
- `sbt -batch assembly`：exit 0（产物即上面那个新 jar）
- wiki 校验：`check_wiki_docs.py --root <workspace>/docs` → 本批两篇文档**零告警**；
  退出码 1 来自仓库既有 22 条历史问题（`reports/…/wiki-check.txt` 全文，逐条属别的批次）

---

## 8. 未做 / 边界

- 缺陷①只改**传输层预算**与**失败语义**，没有引入任何"分配预算"配置位（作者 2026-10-19
  「stand 不要任何超时控制」的裁定保留；600s 与流式面同值，非新增策略）。
- 缺陷③的 per-session 隔离**没做**：共享 MCP 进程决定了 env 是静态的；per-session 边界只能靠
  `PTC_FS_ROOTS_OPEN=1` + 请求级 `fs_roots`。要真隔离得改插件进程模型（一批活）。
- 缺陷④选择改名，因此本批**没有** TypeScript 支持；恢复需要新增真正的转译前置（后续批次）。
- 缺陷⑥只修了探测与 cwd 两刀，没有把 rg.exe 打进 jar（发行形态决定，见 §6.4）。
- 本批插件侧的 `mcp.json`（仓内）未写死 `env`：③ 的覆盖是**部署期配置项**，实例内实测才打开。
