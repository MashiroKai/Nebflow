# NodeEdit worktree 参数反复失败 — 根因分析与改进方案

> 2026-09-03 · 诊断完成（只读分析，未改代码）· 冻结
> 证据均来自主仓 `/Users/dev/Claude code/Nebflow`、`~/.nebflow/agents/project-dispatcher/`、`~/.nebflow/logs/nebflow.log`，标注文件:行号。

---

## 根因链（一句话）

**分发器 system prompt 教 LLM 写 `worktree: "worktrees/<名>"`，而 NodeEdit 校验层用 os-lib 的 `/` 操作符拼接该参数、os-lib 拒绝含 `/` 的动态段并抛出面向 Scala 开发者的报错，LLM 读不懂只能盲试（剥前缀又撞第二层「目录不存在」校验），最终以「裸名 + git worktree add 建到 `.nebflow/` 顶层」的未定义形态偶然通过——每个 worktree 节点必烧 1-3 轮重试，且 worktree 落盘位置随机分裂。**

不是 LLM 的错：**照 system prompt 的示例写，从未可能一次成功。**

---

## 一、校验点（要查清项 ①）

### 1.1 报错文案出处：os-lib 0.11.3，不是 Nebflow 代码

| 组成 | 出处 |
|---|---|
| `[<input>] is not a valid path segment.` | `os/PathError$`（os-lib `Path.scala`） |
| `[/] is not a valid character to appear in a non-literal path segment. If you are dealing with dynamic path-strings coming from external sources, use the Path(...)/RelPath(...)/SubPath(...) constructor calls...` | `os/BasePath$`（os-lib `BasePath.scala`，从本地 jar `os-lib_3-0.11.3.jar` 反编译确认） |

版本定位于 `project/Dependencies.scala:12`（`OsLibVer = "0.11.3"`）。面向开发者的文案是 os-lib 的——它假设调用者读得懂 Scala 的 Path/RelPath/SubPath 构造器，对 LLM 是纯噪音。

### 1.2 触发点：NodeTools.scala:400

```scala
// NodeTools.scala:400（createNode 的 worktree 存在性校验）
val wtPath = os.Path(rt.project.workspace) / ".nebflow" / wt   // wt = "worktrees/deps-edge" → 炸
if !os.exists(wtPath) then
  IO.pure(Left(ToolError(s"Worktree '$wt' not found under ...")))  // 第二层校验（可行动）
```

os-lib `os.Path` 的 `/` 方法签名是 `$div(os.PathChunk)`；动态 String 经 `PathChunk.stringToPathChunk` 隐式转换后走 `BasePath$.validate`，**只接受单段**（无 `/`、非空、非 `.`/`..`）。含 `/` 即抛 `InvalidSegment`，发生在任何 `os.exists` 之前。

### 1.3 完整校验规则（os-lib 0.11.3 BasePath$.validate，动态 String 段）

| 输入形态 | 结果 |
|---|---|
| `"deps-edge"`（单段裸名） | 通过拼接 → `os.exists` 实存校验 |
| `"worktrees/deps-edge"`（含 `/`） | **抛 InvalidSegment（本次事故）** |
| `""` / `"/abs"` 开头的段逻辑 | 拒绝/另路径 |
| `"."` / `".."` | 拒绝（os-lib 自带，安全边界已存在） |

接受什么：单段裸名，且 `.nebflow/<裸名>` 目录实存。拒绝什么：一切含 `/` 的值——**即使那个值在语义上完全正确**。

---

## 二、工具描述面（要查清项 ②）

### 2.1 NodeEdit schema（NodeTools.scala:280）

```scala
"worktree" -> Json.obj("type" -> "string".asJson,
  "description" -> "Relative path under workspace/.nebflow/ (must exist; create via git worktree)".asJson)
```

**描述本身就有歧义**：「Relative path under workspace/.nebflow/」——`worktrees/xxx` 恰是满足这句话的合法相对路径表达。没有「裸段名」要求、没有禁 `/`、没有正反例。markdown 长描述（同文件 ：246）同样只写 `(worktree must exist under workspace/.nebflow/)`。

LLM 拿到的唯一格式信息就是这句歧义描述 + system.md 的 `worktrees/<名>` 示例——**两者合力把 LLM 推向必败写法**。

---

## 三、分发器侧（要查清项 ③）

### 3.1 错误示例的直接来源：system.md:35

`~/.nebflow/agents/project-dispatcher/system.md` 「worktree 用法」第 2 步：

> `NodeEdit 建节点传 worktree: "worktrees/<名>"`——相对 `workspace/.nebflow/` 的路径，目录必须已存在（工具层校验，缺失即拒）

**这就是 LLM 写 `worktrees/xxx` 的直接来源**——照抄指引。同一文件的 ：10、：34 行也一致使用 `<workspace>/.nebflow/worktrees/<name>` 形态（Bash 命令本身正确，参数示例错了）。

### 3.2 参照系三方不一致（比文案更深的伤）

| 环节 | worktree 参照系 | 证据 |
|---|---|---|
| system.md + 分发器 git 命令 | `<workspace>/.nebflow/worktrees/<名>` | system.md:34 |
| NodeList 返回的 `worktrees[]` | `worktrees/` 目录下**裸名**列表（`_.last`） | NodeTools.scala:215-218 |
| NodeEdit 校验 + NodeEngine 运行时 cwd | 相对 **`.nebflow/`** 的段（即 `.nebflow/<参数>`） | NodeTools.scala:400 / NodeEngine.scala:208 |

LLM 无论怎么写都有 2/3 概率对不上：传 `worktrees/x` 被 os-lib 拒；传裸名 `x` 指向 `.nebflow/x` 而非 `.nebflow/worktrees/x`，若 git worktree 建在规范位置则第二层校验「not found」；只有 LLM 碰巧把 `git worktree add` 也建到 `.nebflow/` 顶层才能凑齐。

### 3.3 前端不是污染源（已排查）

`flowMapTab.js:145-146` 徽标 title 直接用节点 payload 的 `n.worktree` 原值；`ProjectTypes.scala:97` 原样序列化 `NodeDef.worktree`（存量全是裸名，见 §四）。前端展示形态 = 参数形态 = 裸名，**无 `worktrees/` 前缀展示问题，前端零改动**。

---

## 四、频率取证（要查清项 ④）

nebflow.log 近两日（2026-09-03 全天）`not a valid path segment` 共 **12 次**：

### 4.1 NodeEdit worktree 参数：7 次（project-dispatcher 专属）

| 时间 | 节点 | 首试参数 | 重试轨迹 |
|---|---|---|---|
| 02:46 | 实施-deps依赖连接·重派3 | `worktrees/deps-edge` | 崩 → 裸名撞「not found」→ **OK**（共 2 次浪费）|
| 08:40 | 实施-micOrb音量响应形变 | `worktrees/micorb-volume` | 崩 → **OK** |
| 10:35 | 实施-Node连接规范收紧 | `worktrees/node-conn-policy` | 崩 → 裸名撞「not found」→ **OK** |
| 10:58 | 实施-工具执行结构化日志 | `worktrees/tools-log` | 崩 → **OK** |
| 12:10 | 实施-分层压缩提示词 | `worktrees/compaction-prompts` | 崩 → 裸名撞「not found」→ **OK** |
| 12:31 | 实施-Project面板归档按钮 | `worktrees/proj-archive-btn` | 崩 → 裸名撞「not found」→ **OK** |
| 18:17 | 实施-隐藏光球配置区 | `worktrees/micorb-config-hide` | 崩 → **OK**（作者 18:18 报告案例）|

**数字结论：首试成功率 0/7 = 0%；最终成功率 7/7 = 100%（LLM 每次都自行纠对）；每个 worktree 节点必烧 1-3 轮（合计 11 次浪费调用，含 4 次第二层 not found）。这是每节点固定税，不是偶发。**

### 4.2 磁盘副作用：worktree 位置随机分裂（实锤）

`.nebflow/` 顶层与 `.nebflow/worktrees/` 存在 **5 个同名工作树目录两处各一份**（askuser-refresh、micorb-config-hide、proj-archive-btn、proj-create-tool、result-delivery-fix），另 flowmap-anim、sandbox-2a 只在顶层。flow-map.json 存量 17 个节点的 worktree **全部是裸段名**（LLM 自纠后的存储形态）。这是试错过程留下的垃圾 + 参照系分裂的物证。

### 4.3 同一校验器的其他受害者：Glob 5 次

| 时间 | agent | 调用 |
|---|---|---|
| 04:34 | Coder | `Glob("src/test/scala/**/{NodeRunner,...}*.scala")` |
| 09:38 | Coder | `Glob("src/main/resources/web/js/**/*.js")` |
| 12:35 | Coder | `Glob("src/main/scala/**/InteractionHub*.scala")`（worktree 内） |
| 13:05 | design-engineer | `Glob("src/main/resources/web/**/*.js")` |
| 18:10 | project-dispatcher | `Glob("src/main/resources/web/js/orb*.js")` |

**Glob 的 description/schema 明确鼓励 `src/**/*.ts` 写法（GlobTool.scala:15、:26），而实现在 ：88 把 pattern 静态前缀 `base / baseFromPattern` 直接拼接**——官方示例写法自身必崩。每次 LLM 改用 Grep/Bash 绕过（5/5 自救成功），但每次都是一轮浪费 + 绕过路径更慢。

---

## 五、同类面排查（要查清项 ⑤）

### 5.1 NodeEdit 其他参数

| 参数 | 校验 | 报错可行动性 |
|---|---|---|
| agent | EntityLoader 存在性（:394-395） | ✓「Agent 'x' not found in global library」 |
| in / deps / out | 引用存在性 + 环检测（proceed :426-456） | ✓「Referenced node 'x' not found」/「Cycle detected...」 |
| task / nodename | 自由文本，无格式约束 | — 无此类风险 |
| maxRetries / abandon | 类型校验 | ✓ |
| **skill / mcp / preset** | **无任何校验** | ⚠️ system.md「硬约束」声称「agent/skill/mcp/worktree 存在性校验」，代码只校验 agent + worktree——**文档与代码不符**（写错 skill 名静默通过，节点运行时才失效，另开一单） |
| **worktree** | os-lib 格式拒绝（不可行动）+ 实存校验 | ✗ **本案唯一不可行动点** |

### 5.2 全仓 `base / <动态String>` 脆弱点（同一校验器的复用面）

| 位置 | 状态 |
|---|---|
| `NodeTools.scala:400`（`/ wt`） | ✗ 本案 |
| `NodeEngine.scala:208`（`/ wt`，节点运行时 cwd） | ✗ 同模式——存储值一旦含 `/` 即炸（当前存量裸名未触发） |
| `GlobTool.scala:88`（`/ baseFromPattern`） | ✗ 日志 5 次实锤 |
| TransferFileTool（×6 处）、PathUtil.resolvePath:99、FileTransferAction:37 | ✓ 已用 `os.RelPath(s)`（多段构造器，os-lib 报错文案推荐的做法，项目内有正确先例） |
| 其余 `/ "literal"` | ✓ 字面量走编译期 macro 校验，安全 |

---

## 六、改进方案（三轴）

### 轴 1（推荐，根治）：NodeEdit 入参宽容归一 + 权威参照系统一

**新增归一化函数**（建议放 `NodeTools.scala` 或 `PathUtil`，供 NodeTools:400 与 NodeEngine:208 共用）：

```scala
/** worktree 参数归一化：任意合理形态 → worktrees/ 下裸段名；不可归一 → Left(可行动报错)。
  * 安全边界：拒绝绝对路径与含 ".." 的输入；只剥已知固定前缀，不做任意路径解析。 */
def normalizeWorktree(raw: String): Either[String, String] =
  var s = raw.trim.stripPrefix("./")
  if PathUtil.isAbsolute(s) then return Left(ACTIONABLE_ERR)          // 绝对路径仍拒绝
  if s.startsWith("worktrees/") then s = s.drop("worktrees/".length)  // 剥一段前缀
  else if s.startsWith(".nebflow/worktrees/") then s = s.drop(...)    // 全前缀形态也收
  else if s.startsWith(".nebflow/") then s = s.drop(...)              // 兼容 .nebflow/<名> 顶层存量
  if s.contains("/") || s.contains("..") || s.isEmpty then Left(ACTIONABLE_ERR)
  else Right(s)
```

**归一化规则**（逐条）：
1. trim → 剥 leading `./` → 剥 `worktrees/` 或 `.nebflow/worktrees/` 或 `.nebflow/` 前缀（各剥一次）
2. **绝对路径、含 `..`、归一后仍含 `/` 或为空 → 拒绝**，报轴 2 的可行动文案（安全边界不变：检查对象始终是 `workspace/.nebflow/` 内相对段；os.RelPath 的 `..` 拒绝是第二道保险）
3. 归一后裸名 → 实存校验路径改为 **`.nebflow/worktrees/<裸名>`（唯一权威位置）**；兼容存量：权威位置不存在时 fallback 查 `.nebflow/<裸名>` 顶层（今天 17 个存量节点全在顶层，fallback 保证零迁移不破现网）
4. **存储与运行时统一**：NodeDef.worktree 存裸名（现状已如此，零迁移）；NodeEngine:208 cwd 解析与校验共用同一「双查」逻辑（`worktrees/<名>` 优先、顶层 fallback）
5. NodeList `worktrees[]` 列表改为合并双位置去重（或迁移后只读权威位置）

**落盘垃圾清理**（实施时顺手）：`.nebflow/` 顶层 8 个工作树目录保留（运行中引用），`worktrees/` 下 5 个同名孤儿目录（未被任何 flow-map.json 引用的那份）确认后删除。

**验收断言**（二值）：
- [ ] 单测：`normalizeWorktree("micorb-config-hide")` / `("worktrees/micorb-config-hide")` / `(".nebflow/micorb-config-hide")` 均 → `Right("micorb-config-hide")`
- [ ] 单测：`"/abs/x"`、`"../x"`、`"a/b"`（剥后仍含 / 的畸形值）→ `Left` 且文案含「bare directory name」
- [ ] 单测：worktree 校验对 `.nebflow/worktrees/<名>` 与 `.nebflow/<名>` 双位置实存均通过
- [ ] 单测：NodeEngine cwd 解析对双位置存量均产出正确路径
- [ ] 端到端：起服务 → 分发器会话 NodeEdit 传 `worktree: "worktrees/<已建名>"` **首试即 OK**（日志无 path segment 报错）

### 轴 2：报错可行动化（兜底文案，随轴 1 顺带）

归一化仍拒绝时的报错文案（替换 os-lib 原始异常外泄）：

```
Worktree must be a bare directory name under the project's .nebflow/worktrees/ — e.g. "micorb-config-hide".
Do NOT include the "worktrees/" prefix, path separators, absolute paths, or "..".
Create it first via: git worktree add <workspace>/.nebflow/worktrees/<name> -b <branch>, then pass worktree: "<name>". (WORKTREE_FORMAT)
```

要点：正例 + 反例 + 补救命令 + 错误码（与 EMPTY_NODE_CONNECTION 同风格，LLM 可自纠）。**验收断言**：构造非法入参（如 `"worktrees/x/y"` 剥后仍含 `/`），工具返回文案含 `WORKTREE_FORMAT` 与示例裸名；日志无 os-lib 原始文案外泄。

### 轴 3：预防（文档与示例对齐）

1. **NodeEdit schema description**（NodeTools.scala:280）改为：
   `"Bare directory name under workspace/.nebflow/worktrees/ (must exist). e.g. "micorb-config-hide" — NOT "worktrees/micorb-config-hide", no path separators, no absolute paths"`
2. **markdown 参数节**（:246）worktree 处补正反例一行
3. **system.md:35** 示例改为 `worktree: "<名>"`（裸名），并在 ：34 的 git 命令后补一句「NodeEdit 传裸名，不带 worktrees/ 前缀」
4. **GlobTool.scala:88** 独立小修：`base / baseFromPattern` → `base / os.RelPath(baseFromPattern)`（或分段拼接）——pattern 静态前缀含 `/` 是 description 鼓励的合法写法，当日 5 崩的根源
5. **system.md「硬约束」一节**删除 skill/mcp 的存在性校验声称，或补真实校验（另行裁定，见 §5.1 ⚠️）

**验收断言**：
- [ ] schema diff 含正反例文本；分发器新会话 NodeList→NodeEdit 全链路走查 `worktree` 提示语可见
- [ ] 单测：`Glob("src/main/resources/web/js/**/*.js")` 正常返回（当日 5 个失败 case 各写一条回归）
- [ ] 回归：Glob 显式 path 参数、绝对 path、默认根三形态不破

---

## 七、工作量评估与实施顺序

| 步骤 | 内容 | 工作量 |
|---|---|---|
| P0（立即，止血） | 轴 3 文档三处：schema description + markdown + system.md | ~0.5h（含 `~/.nebflow` git commit） |
| P1（根治） | 轴 1 归一化 util + NodeTools:400 + NodeEngine:208 双查 + 单测 + 轴 2 文案 | ~0.5-1 天（Scala 实现+测试） |
| P2（并行小修） | GlobTool:88 `os.RelPath` + 5 条回归用例 | ~1h |
| P3（收尾） | `worktrees/` 孤儿目录清理 + NodeList 双位置列表 | ~0.5h |

顺序理由：P0 纯文档先行可立刻把首试成功率从 0% 拉起来（LLM 照新示例写裸名即可过现有校验）；P1 让「写错前缀」也宽容、消灭残余重试；P2 独立无依赖；P3 需人工确认孤儿目录再删。

---

## 待实施文件清单

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/tools/NodeTools.scala` | :280 description；:246 markdown；:400 归一化+双查+轴 2 文案；NodeList `worktrees[]` 双位置 |
| `src/main/scala/nebflow/core/project/NodeEngine.scala` | :208 cwd 解析复用归一化/双查 |
| `src/main/scala/nebflow/core/tools/GlobTool.scala` | :88 `os.RelPath` |
| `~/.nebflow/agents/project-dispatcher/system.md` | :35 裸名示例 + :34 后补说明（改后 git commit） |
| 新增测试 | `normalizeWorktree` 单测、NodeEdit worktree 三形态集成、Glob 前缀回归 ×5 |
