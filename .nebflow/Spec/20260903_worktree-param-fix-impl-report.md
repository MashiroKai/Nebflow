# NodeEdit worktree 参数修复 — 实施报告

> 2026-09-03 · worktree-param-fix · 实施完成，已合并本地 main（未 push）
> 设计依据：`20260903_nodeedit-worktree-param-fix.md`（~/.nebflow commit b0d4c2d，行号级证据冻结）

---

## 根因（引用分析报告原句）

> 分发器 system prompt 教 LLM 写 `worktree: "worktrees/<名>"`，而 NodeEdit 校验层用 os-lib 的 `/` 操作符拼接该参数、os-lib 拒绝含 `/` 的动态段并抛出面向 Scala 开发者的报错，LLM 读不懂只能盲试……每个 worktree 节点必烧 1-3 轮重试，且 worktree 落盘位置随机分裂。

当日取证：NodeEdit 7 崩（首试成功率 0%）+ Glob 5 崩。

## 一、归一化函数（轴 1 核心）

**位置**：`src/main/scala/nebflow/core/paths.scala`（`PathUtil`，单点收口——NodeTools 校验与 NodeEngine 运行时 cwd 引同一份，未两处各写）

**签名与规则**：

```scala
val WorktreeFormatError: String                 // 轴 2 文案（下节）
def normalizeWorktree(raw: String): Either[String, String]
```

1. `trim` → 剥 leading `"./"` → 剥 `".nebflow/worktrees/"` / `"worktrees/"` / `".nebflow/"` 前缀（各剥一次，顺序保证最长前缀先剥）
2. 拒绝（→ `Left(WorktreeFormatError)`）：绝对路径（`PathUtil.isAbsolute`，Unix/UNC/Windows 盘符）；含 `".."`（**段级检查** `split('/').contains("..")`，QC P3 统一）；剥后仍含 `"/"`；剥后为空；`"."`
3. 安全边界不变：只剥已知固定前缀，不做任意路径解析；拒绝面（绝对路径/`..`）零放宽
4. QC P3：`v2..fix` 这类含连续点合法名放行（与旧公式经 os-lib 的行为一致，与 GlobTool 段级语义统一）

**双位置实存解析**（同文件）：

```scala
val ReservedTopLevelNames: Set[String]          // QC P1: {worktrees, skills, commands}
def resolveWorktreeDir(workspace: os.Path, bareName: String): Option[os.Path]
def resolveNodeProjectRoot(workspace: String, worktree: Option[String]): String
```

- `resolveWorktreeDir`：`worktrees/<裸名>`（**权威位置优先**）→ `.nebflow/<裸名>`（顶层存量 fallback，**保留名不参与 fallback**）→ 均无 `None`。`os.exists` 沿软链，与现状一致
- `resolveNodeProjectRoot`（NodeEngine 唯一调用点）：命中 → 实存目录（**顶层命中 = 旧公式 `(os.Path(workspace) / ".nebflow" / wt).toString` 逐字节一致——回归红线，有等值断言 pin 死**）；两处均无 → 旧公式路径（校验期实存、运行期目录被删窗口的字节等价兜底）；归一化拒绝 → workspace 兜底 + warnSync 留痕（QC P2；旧实现此处 InvalidSegment 炸 spawn）

**保留名守卫（QC P1，合并时自动化 QC 审查发现并跟进）**：项目级 `.nebflow/skills/`、`.nebflow/commands/` 是真实系统目录（`SkillService.projectSkillPaths/projectCommandPaths` 实证）——顶层 fallback 与 NodeList 枚举均排除保留名，否则 `worktree: "skills"` 会因实存而放行、节点跑进系统目录，且 NodeList 会主动列出误导 LLM（参照系污染新变体）。权威位置 `worktrees/<保留名>` 不受限。

## 二、改动清单（文件/函数级）

| 文件 | 函数/位置 | 改动 |
|---|---|---|
| `src/main/scala/nebflow/core/paths.scala` | `PathUtil` 新增 | `WorktreeFormatError` / `normalizeWorktree` / `ReservedTopLevelNames` / `resolveWorktreeDir` / `resolveNodeProjectRoot` |
| `src/main/scala/nebflow/core/tools/NodeTools.scala` | `NodeEditTool.createNode` 校验段（原 :397-404） | 入参先 `normalizeWorktree`；拒绝 → `Left(WORKTREE_FORMAT)`；实存走 `resolveWorktreeDir` 双查；**存储 `Some(bare)` 仍为裸名（零迁移）**；not-found 文案含名字+双位置+具体 git 补救命令 |
| 同上 | `NodeTools.payload`（NodeList，原 :215-218） | `worktrees[]` 改双位置合并去重（`worktrees/` 目录名 ∪ 顶层目录/软链名），排除保留名，`.distinct.sorted` 排序稳定；**节点 payload 的 worktree 字段零改动** |
| 同上 | schema description（原 :280）+ markdown 参数节（原 :246） | 轴 3 文本：裸名要求 + 正例 `micorb-config-hide` + 反例 `NOT "worktrees/…"` + 禁分隔符/绝对路径（三要素齐） |
| `src/main/scala/nebflow/core/project/NodeEngine.scala` | `runWithAgent`（原 :207-209） | cwd 解析改 `PathUtil.resolveNodeProjectRoot`；损坏值 workspace 兜底 + `logger.warnSync` 留痕（QC P2） |
| `src/main/scala/nebflow/core/tools/GlobTool.scala` | `call` 搜索根解析（原 :88） | 静态前缀 `base / baseFromPattern` → `base / os.RelPath(baseFromPattern)`（try/catch 包裹）；`".."` 段构造前显式拒（`GLOB_PATTERN` 可行动文案） |
| 新增 `src/test/scala/nebflow/core/WorktreeParamSpec.scala` | — | 29 用例 |
| 新增 `src/test/scala/nebflow/core/tools/GlobToolSpec.scala` | — | 12 用例 |
| `src/test/scala/nebflow/core/project/NodeAcceptanceSpec.scala` | 文件尾**只增** | WT-1~WT-7 集成用例 |

未动：`~/.nebflow/agents/project-dispatcher/system.md`（P0 已完成，commit 9295df7）；前端（payload 形态=裸名，零改动）；schema 的 skill/mcp 校验缺失问题（另单，不顺手修）。

## 三、双查与 NodeList 双位置实现要点

- 生产实况核对（开工时）：主仓 `.nebflow/` 顶层 5 个同名条目（askuser-refresh 等）**已全是软链**指向 `worktrees/` 同名目标（分发器孤儿清理产物），flow-map.json 存量节点 worktree 全为裸名。因此「worktrees/ 权威优先」对存量节点命中的是与现状同物理目录（软链别名去重后），沙箱根 canonicalize 后等价——回归红线满足，且有「顶层命中=旧公式逐字节」等值断言。
- NodeList 合并去重：软链别名与权威同名 `distinct` 收敛为一个名字；`os.isDir` 沿软链（语义同旧代码）；`worktrees/` 容器与保留名不入列。

## 四、轴 2 文案定稿

**归一化拒绝（WORKTREE_FORMAT，四要素齐：正例裸名/禁形态/补救命令/错误码）**：

```
Worktree must be a bare directory name under the project's .nebflow/worktrees/ — e.g. "micorb-config-hide". Do NOT include the "worktrees/" prefix, path separators, absolute paths, or "..". Create it first via: git worktree add <workspace>/.nebflow/worktrees/<name> -b <branch>, then pass worktree: "<name>". (WORKTREE_FORMAT)
```

**not-found（合法名字、目录缺失——与 format 拒绝是两种失败面，按任务书「或 not-found 文案可行动」保留独立文案）**：

```
Worktree '<bare>' not found under <workspace>/.nebflow/worktrees/ (nor top-level .nebflow/) — create it first via: git worktree add <workspace>/.nebflow/worktrees/<bare> -b <branch>
```

os-lib 原始文案（`is not a valid path segment`）两条路径均不再外泄（各有负向断言）。

## 五、Glob 修复（先例参照）

- 修复点：`GlobTool.call` 中 pattern 静态前缀（首个 glob 字符前的目录部分）改 `os.RelPath` 多段构造——**先例**：`TransferFileTool.scala` 6 处 `os.pwd / os.RelPath(...)`、`PathUtil.resolvePath:99` `base / os.RelPath(pathStr)`。
- 关键发现（jar 反编译 os-lib 0.11.3 实证）：`os.RelPath(String)` 走 `Paths.get(s).normalize()` 后把 `".."` 分区为 **Up 段（允许逃逸）**，中段 `".."` 被静默折叠——**与任务书「RelPath 也拒 ..」的假设相反**。故在构造前显式 `split('/').contains("..")` 拒绝并给 `GLOB_PATTERN` 文案，行为与现状（拒）对齐；沙箱读闸门（canonicalize + readableRoots）不受影响。
- `src/**/*.ts`、`**/*.js`（description 自带示例）及 `src/main/resources/web/js/orb*.js`（当日 18:10 崩 case）全部直接可用。

## 六、测试逐条结果（全部真实执行）

| 验收项 | 落点 | 结果 |
|---|---|---|
| 1. normalizeWorktree 单测全覆盖 | WorktreeParamSpec（29 用例：裸名/四种前缀/`./worktrees/` 组合/空白 收 Right；`/abs/x`、`../x`、`a/b`、`""`、`.`、`.nebflow/worktrees/a/b`、`C:\x` 拒 Left 且文案含 WORKTREE_FORMAT+示例裸名；`v2..fix` 收） | 29/29 绿 |
| 2. NodeEdit worktree 集成 | NodeAcceptanceSpec WT-1~6（三形态入参实存时创建成功且 `NodeDef.worktree` 存裸名；仅顶层存在时 fallback 成功；两处均无 → not-found 可行动 Left；绝对路径 → WORKTREE_FORMAT 无崩溃） | 6/6 绿 |
| 3. NodeEngine cwd 双位置 | WorktreeParamSpec projectRoot 6 用例（顶层存量=旧公式逐字节等值断言；worktrees/ 权威路径；前缀形态存储值；无 worktree；双删窗口旧公式兜底；损坏值 workspace 兜底）+ resolveWorktreeDir 5 用例（权威/顶层/双现权威赢/均无 None/软链沿链+别名权威赢） | 11/11 绿 |
| 4. Glob 回归 | GlobToolSpec（当日 5 崩 case 各一条——4 条含 `**` 递归前缀的化简形态 + 1 条 `orb*.js` 原样；另加 canonical `{A,B}`+`**` 形态；description 原示例 `**/*.js`/`src/**/*.ts`；三形态回归：显式相对 path/绝对 path/默认根；`..` 段拒 + 无 os-lib 泄漏） | 12/12 绿 |
| NodeList 双位置 | WT-7（权威 2 + 顶层 1 + 软链别名去重 + 保留名 `skills` 不入列 + stable sort 全等断言） | 1/1 绿 |
| 既有回归 | NodeAcceptanceSpec 既有 23 用例、NodeToolsSpec、NodeDepsSpec（只读未改） | 全绿 |

## 七、变异验红（红绿证据原文）

**变异 ① NodeEdit**（createNode 校验段还原为 main@1f10246a 旧公式 `os.Path(...) / ".nebflow" / wt` + 旧实存校验）→ `testOnly NodeAcceptanceSpec`：

```
==> X ...WT-1 worktree create: bare name ... bare-name create must succeed, got: Left(Worktree 'wt-a' not found under .../ws-wt-bare/.nebflow/ — create it first via git worktree add)
==> X ...WT-2 worktree create: 'worktrees/<name>' prefix form ... os.PathError$InvalidSegment: [worktrees/wt-a] is not a valid path segment. [/] is not a valid character to appear in a non-literal path segment. If you are dealing with dynamic path-strings coming from external sources, use the Path(...)/RelPath(...)/SubPath(...) constructor calls to convert them.
    at nebflow.core.tools.NodeEditTool$.createNode$$anonfun$2(NodeTools.scala:409)
==> X ...WT-3 worktree create: '.nebflow/<name>' top-level form ... os.PathError$InvalidSegment: [.nebflow/wt-a] is not a valid path segment. ...
```

（WT-2/3 = 当日 7 崩的 os-lib 原文逐字复现；WT-1 = 第二层「not found」失败复现；WT-4 在变异下通过——顶层 fallback 形态旧代码本就能过，与当日轨迹一致。）

**变异 ② Glob**（搜索根解析还原为 `base / baseFromPattern` 直接拼接）→ `testOnly GlobToolSpec`：**5/5 全红**，每条均为

```
os.PathError$InvalidSegment: [src/test/scala] is not a valid path segment. [/] is not a valid character ...
    at nebflow.core.tools.GlobTool$.call$$anonfun$1(GlobTool.scala:89)
```

**恢复后复绿**：MUTATION 标记零残留（grep 计数 0），GlobToolSpec + NodeAcceptanceSpec **42/42 绿**。

## 八、全量 sbt test（worktree 内前台真实跑）

- **Total 2132 / Failed 0 / Errors 0 / Ignored 7**，耗时 **478s（0:07:58）**
- 基线 2087 + 新增 45（WorktreeParam 29 + Glob 12 + WT 7 含既有 23 → 30）= 2132，精确吻合
- 注：全量跑在 QC 修复提交之前（commit 79fb81d8）；QC 修复（22a1b257，纯加守卫/留痕 +3 用例）后按任务书只重跑 spec 子集，未跑第二次全量

## 九、分支与合并

| hash | 说明 |
|---|---|
| `1f10246a` | 基线（分发器预建分支起点） |
| `79fb81d8` | 分支 commit：轴 1+2+3 + Glob 修复 + 全部测试（7 文件，582+/21-） |
| `8dff05cb` | 分支 merge main（合并前拉入在飞落 main 的 send-btn/micorb 链 18a50432，零冲突） |
| `34a986bd` | **main 上的第一次 --no-ff merge commit**（消息按任务书指定文本） |
| `22a1b257` | 分支 commit：QC 三项跟进（保留名守卫 + warnSync 留痕 + 段级 `..`，49+/7-） |
| `2fadf6f6` | **main 上的第二次 --no-ff merge commit**（QC 修复合入） |

- 合并前主仓 `status --porcelain` 干净（无他人未提交改动涉我的文件域）；未 push、未动 origin
- 合并触发仓库自动化 QC 审查钩子，产出 P1/P2/P3 三项——全部当场合入（见 §一/§二），非遗留

## 十、合并后主仓 spec 子集复跑

```
sbt "testOnly nebflow.core.WorktreeParamSpec nebflow.core.tools.GlobToolSpec
          nebflow.core.tools.NodeToolsSpec nebflow.core.project.NodeDepsSpec
          nebflow.core.project.NodeAcceptanceSpec"
→ Passed: Total 94, Failed 0, Errors 0
```

## 十一、清理确认

- `git worktree remove .nebflow/worktrees/worktree-param-fix` ✓（`git worktree list` 中 param 匹配数 0）
- `rm .nebflow/worktree-param-fix` 软链 ✓（ls 匹配数 0）
- `git branch -d worktree-param-fix` ✓（安全删除成功，历史经 --no-ff 全保全）
- `.nebflow/` 下其余软链与 worktrees/ 目录零触碰

## 十二、生效说明

合并进 main 即代码交付。**运行时生效需重建 + 重启宿主——本次严禁任何重启动作，未做。**宿主进程（PID 87216 / 端口 8080）全程零接触。

## 十三、遗留

1. **skill/mcp/preset 无存在性校验**（system.md「硬约束」声称与代码不符）——任务书明示另单裁定，本任务未顺手修
2. Glob 反斜杠形态静态前缀（`src\main`）Unix 下不被拒——旧代码同样不处理，QC 评为不阻塞，未动
3. NodeAcceptanceSpec WT-1~4 解构 `res` 未使用（无告警配置下无影响）
4. 全量 sbt 未在 QC 修复 commit 后重跑（按任务书只跑子集；QC 修复不触碰既有语义，风险面 ≈ 0）
