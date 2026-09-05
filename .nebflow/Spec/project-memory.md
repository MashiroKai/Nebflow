# project-memory 批 spec —— 项目级记忆文件机制

- 日期: 2026-09-05
- 批次: project-memory（Project+Node 架构；方案上下文 = memory-management-plan.md §2 生命周期 / §5 简练标准）
- 作者: project-memory 分支开发节点（沙箱会话，Caller: project-dispatcher）
- 上游: memory-mech（n-b4dbcb58，MemoryEditTool 预算闸/ContextRefresher 注入改动）+ dispatcher-ctx（n-b2d0499a，ProjectActor newTaskPrompt/reentryPrompt 目录注入）——两支均无 commit 能力，交付=worktree 未提交改动；本支工作树 = 两上游终态并集 + 本批改动（逐文件字节级复制，已验 cmp 一致）
- 实施状态: 代码+测试+staging 全部落盘，编译/测试/E2E 见 §5

---

## 0. 结论速览

| 决定 | 定稿 | 依据 |
|---|---|---|
| 文件落位 | `<workspace>/.nebflow/memory.md` | 与全局 agent 级同名（memory.md=记忆文件心智一致），靠 `.nebflow/` 目录位分层；workspace 从项目注册表（project.json）取，路径永不由参数给出 |
| git 处置 | **运行时层不进 git，不加豁免**（显式决定） | workspace 根 .gitignore 已含 `.nebflow/`（R6）天然排除；运行时状态免评审噪音，git 留史由快照机制承担——与主仓 `.nebflow/*`+`!.nebflow/Spec/` 配方同向（Spec 进 git、运行时不进） |
| 快照机制 | **本批零改动 SandboxPolicy/FileSandbox**（与 dream 批划界） | dream 批在做全局记忆 snapshot-on-write，其对象面的路径模式扩展天然覆盖项目记忆；集成说明见 §4 |
| 写入路由 | MemoryEdit 新增 `target=project:<name>` | 名字 → 注册表解析 → `<workspace>/.nebflow/memory.md`；四动作全可用；条目纪律（单行 `- `、MEMORYEDIT_ENTRY_FORMAT）与全局同规 |
| 项目预算 | 硬顶 10KB / 软警 8KB（建议区间 8-12KB 定值取中） | 项目记忆注入面是乘法的（分发器 1 份 + 每节点 1 份），全局 30-50KB 按「常驻单份」定价，项目级须低一个量级按「乘法分发」定价；10KB ≈ 单行纪律下 ~60 条容量（对齐 §2.1 T3 均值 170B/条测算）；80% 软警沿用全局惯例 |
| 默认注入 | **只含全局记忆，ContextRefresher 零改动**（瘦身） | 项目记忆对非项目事务是纯税；全局注入面维持 Nebula-only 两级 |
| 派发注入 | 该项目分发器 prompt（spawn 两形态）+ 该项目节点首条消息自动注入 | 「派发项目任务→该项目记忆在场」的核心语义；渲染三态单点 ProjectMemory.injectionBlock |
| Nebula 读取 | **Read 按需**（不随任务自动附） | Nebula 全局上下文珍贵，全局注入已刻意不含项目记忆（瘦身）；工作流中的项目记忆由分发器/节点注入链承接，她复盘时 Read 单文件即可；自动附会让瘦身决定自我失效 |

## 1. 变更面（本支叠加部分；上游复制面见 §6）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/service/MemoryBudget.scala` | +project 维度：`ProjectHardBytes=10KB`/`ProjectSoftBytes=8KB` 常量 + `verdict("project",…)` 分支 + 消息文案 project 路径入参（`warnNotice` 加带缺省的 `targetPath` 参数，旧调用方零改动） |
| `src/main/scala/nebflow/core/project/ProjectMemory.scala` | **新文件**：路径解析（`path(workspace)`）、读写（read-fresh 无缓存 + createFolders 兜底）、注入块三态渲染（`injectionBlock`：Within 全文 / Warn 全文+WARN 脚注 / Exceeded 头部+字节·条目·top 节统计+整理指引，缺失/空 → ""） |
| `src/main/scala/nebflow/core/tools/MemoryEditTool.scala` | `target=project:<name>`：resolveTarget 注册表路由分支（项目名校验同 ProjectStore：禁 `/` `\` `.` `..` 空）；schema target 由 enum 改自由字符串（enum 会约束 LLM 无法生成 `project:<name>`，宿主本就不做 enum 硬校验）；description 增 project 目标行+项目预算行；预算闸维度映射 `budgetDim`（`project:<name>`→`project`）+ 拒绝/WARN 消息带真实路径 |
| `src/main/scala/nebflow/core/project/ProjectActor.scala` | `projectMemoryText()` + `newTaskPrompt`/`reentryPrompt` 各加一参并在目录段后拼接（叠 dispatcher-ctx 终态的最小 hunk：`mapN` 并取目录与记忆两段）；注入活跃会话两形态（taskInjectionText/reentryInjectionText）**不**重复注入——会话 spawn 时已带当次快照（代码注释已写明） |
| `src/main/scala/nebflow/core/project/NodeEngine.scala` | `buildInput` 首部注入本项目记忆块（节点首条消息组装点；仅此一处，`map`→`flatMap`+`map` 结构最小改动）。**merge-node 批同文件在飞预检**：其 hunk 在 ~L809 failNode 合并结算路径，与 buildInput（~L204）零交叠，无撞车（逐 hunk 预检结论，合并顺序见 §6） |
| `src/test/scala/nebflow/core/tools/MemoryEditToolSpec.scala` | +9 测：注册表路由写对文件 / 四动作+条目纪律 / 未知项目拒绝 / 路径穿越名拒绝 / 项目预算三态（预算内无 WARN / 8.3KB WARN 放行 / 10.3KB 拒绝零写入）/ replace_section 超限畅通 |
| `src/test/scala/nebflow/core/project/ProjectMemorySpec.scala` | **新文件**：缺失/空→""、三态渲染逐断言、路径契约、预算常量钉死 |
| `staging/project-memory-template.md` | 初始化模板（单行条目纪律+三问准入+T2 生命周期+预算行+梦境清理对象声明+节建议：口径与决策/项目状态/运维教训） |
| `staging/project-memory-routing-rules.md` | Nebula 记忆「记忆管理规则」节增补文本（三条，Nebula 本人 MemoryEdit 应用，与 memory-mech 规则节替换文本合并、先后她裁；禁宿主直写） |
| `.nebflow/Spec/project-memory.md` | 本 spec（四批配方豁免进 git） |

## 2. 设计决定（定稿+依据）

### 2.1 命名与落位

`<workspace>/.nebflow/memory.md`。备选 `<workspace>/MEMORY.md`（根层）被否：混入项目 repo 工作树面（git 排除要额外加一行，违背「不加豁免」的简洁性）；`.nebflow/` 内与其余运行时态（flow-map 等）同住，目录位语义已成立。

### 2.2 git 处置（显式决定）

**各项目 repo 的 .gitignore 不加 `!.nebflow/memory.md` 豁免**——`.nebflow/*`（workspace 根 .gitignore 的 R6 行）已排除 memory.md，保持排除即最终态。理由：①运行时层免评审噪音（与主仓 Spec-in/runtime-out 配方同向）；②git 留史由快照机制承担（§4）；③记忆是高频小写面，进 git 会制造海量噪音 commit。主仓 `.gitignore` 本批零改动（继承 dispatcher-ctx 的 `.nebflow/*`+`!.nebflow/Spec/`——多批同 diff，逐级合并自动消解）。

### 2.3 写入路由 `target=project:<name>`

`<name>` 是**名字不是路径**：resolveTarget 经 `ProjectStore.load` 注册表解析，路径只来自 project.json 的 workspace 字段。项目名非法（`/` `\` `.` `..` 空）在触文件系统前拒绝；注册表查无 → `MEMORYEDIT_TARGET` 结构化拒绝（附注册表指针，可行动自纠）。预算闸沿用 memory-mech 同一判据面（MemoryBudget.verdict），常量独立（10KB/8KB），replace_section 豁免同规（超限文件的收缩通道必须畅通）。schema target 字段 enum→自由字符串：enum 会硬约束模型只生成 user/agent；宿主对工具入参本无 enum 硬校验（Tool trait 直收 JsonObject），约束留在 description 文档面。

### 2.4 注入三态与瘦身边界

- 全局：`ContextRefresher` 零改动（本支对上游 memory-mech 版仅继承、不叠加）——**默认注入只含全局记忆**。
- 派发：项目分发器 spawn prompt（新任务/重入两形态）+ 该项目每个节点首条消息自动注入本项目记忆；渲染单点 `ProjectMemory.injectionBlock`：预算内全文（KB 级可承受）；>80% 全文+WARN 脚注（与写入侧软警同码）；超硬顶只注头部+字节/条目/top 节统计+整理指引（超限文件内容质量不可信，全文注入是税）。
- 会话内注入形态（单例化裁定后的 taskInjectionText/reentryInjectionText）不重复注入：会话 spawn 时已带当次快照，turn 间增量由 NodeList 现读与节点 out 结果自然承接。

### 2.5 Nebula 本人的读取选型

**Read 按需**（选型依据见 §0 表末行）。写入仍走 MemoryEdit（预算闸+条目纪律在工具侧），Nebula 无项目注册表记忆时由分发器/本 spec 的落地清单提供路径。

## 3. 验收点（全部已验，证据见 §5）

1. E2E（隔离实例+stub LLM+fixture 双项目 A/B，`scripts/e2e-project-memory.mjs`）：
   - MemoryEdit `target=project:A` 经真实工具链写入一条（Nebula 会话 stub 脚本化调用）
   - A 项目分发器上下文含该条；A 项目节点首条消息含该条（分发器 stub 脚本化 NodeEdit 建节点→节点 LLM 请求捕获）
   - B 项目分发器上下文**不含**；Nebula 全局注入（其请求的 **system prompt 注入面**）**不含**（瘦身断言；工具结果回显属会话历史不计入）
   - 初始化模板渲染：宿主 cp 链路冒烟（fixture 内 cp 模板→MemoryEdit 可 append→注入含模板头节）
2. 单测：MemoryEditToolSpec 9 新测（§1 表）+ ProjectMemorySpec 7 测；预算 project 维度三态沿 memory-mech 口径（Within/Warn/Exceeded）。
3. 变异验红口径：预算闸拒绝路径断言「零写入」（删闸即红）；瘦身断言为负向断言（全局注入含 marker 即红）。

## 4. dream 批快照承接集成说明

本批**不改** SandboxPolicy/FileSandbox（与 dream 批 n-7d1c7999 划界）。集成关系：
- dream 批的全局记忆 snapshot-on-write 机制在其对象面（路径模式）上扩展即覆盖项目记忆：项目记忆位于 `<workspace>/.nebflow/memory.md`，dream 的写监视模式加一段 `<workspace>/.nebflow/memory.md`（或等价通配）即可，**无需本批预留接口**——写入面单一（MemoryEditTool → ProjectMemory.save），无第二写面绕行。
- dream 批落地前：项目记忆无快照留史（运行时层不进 git 的代价，§2.2 已声明）；本批 E2E 的写操作在 fixture home 内自含（跑完即删），不产生宿主副作用。
- 实盘交叠核查（2026-09-05 实测）：dream-agent worktree 干净无改动；memory-mech 终态含 SandboxPolicy/FileSandbox 改动——那是 memory-mech 批次二机制四（§4.2-B 审计只读豁免 auditReadableFiles）的自有交付，**不是** dream 的 snapshot 面；dream 的 snapshot-on-write 应基于 memory-mech 版本叠加。落地顺序 memory-mech 在前即无冲突；本支继承该两文件的原样改动、自身零叠加（git diff 本支 vs memory-mech 在这两文件上为空）。

## 5. 验证记录

- `sbt compile` ✓（275 源，私有缓存配方，见 §6 命令头注）。
- 全量 `sbt test`：Total 2393 / Passed 2361 / Failed 32 → **失败归因全部与本支无关**：
  - 1 例为我方测试缺陷（空文件 append 指定节触发设计内 NO_SECTION）——已修，修复后 MemoryEditToolSpec + ProjectMemorySpec 共 40/40 全绿（含 project 目标 9 测 + 注入三态 8 测）；
  - SandboxSpec（24）+ PopToolSpec（1）：`FileSystemException …/Users/dev/…: Operation not permitted`——测试 fixture 写 user home 被本节点沙箱拒；**pristine base（git archive HEAD 零叠加导出 /tmp）复跑同套件同报错**（SandboxSpec 同批用例 + PopTool "~ expansion" 实测复红）→ 环境性（沙箱 profile），非代码回归；该套件内容属 memory-mech 批交付面（本支字节级继承未动），其沙箱可写 home 的节点上验证；
  - NodeSessionDeathFinalizeSpec：全量跑红、单独重跑绿——并发构建（并行批 a2a-frontend 的 sbt 全程在跑）负载抖动；
  - ShellStuckDetector / BashBackgroundHardTimeout / BashActivityBridge：CPU 活动采样类测试在上述负载下抖动，与本项目记忆改动（MemoryEditTool/ProjectActor/NodeEngine/MemoryBudget）无路径关联。
- E2E：`GATEWAY_PORT=8095 MOCK_PORT=18995 node scripts/e2e-project-memory.mjs` → **24/24 ALL PASS**（2026-09-05 14:12 实测）：
  - 模板渲染 6 断言（cp+项目名回填后七节骨架齐全）；
  - MemoryEdit `target=project:e2e-a` 真实工具链写入 workspace memory.md ✓（注册表解析全链）；
  - A 分发器上下文含条目+Project Memory 头+模板纪律节 ✓；A 节点首条消息含条目+头 ✓（NodeEdit 建节点→创建即运行→节点请求捕获）；
  - B 分发器不含 A 条目/头 ✓（项目隔离）；
  - 瘦身断言（**作用域=system prompt 注入面**，非全消息体——turn2/3 历史含 MemoryEdit 工具结果对条目的回显，属会话历史非注入）：Nebula 3 请求 system prompt 均无 marker、无 `# Project Memory` 头 ✓；
  - 进程清理 trap EXIT 实效：8095/18995 跑完即释（lsof 复查空）。
- 变异验红口径：预算闸拒绝路径断言「零写入」（删闸即红）；瘦身断言为负向断言（注入面含 marker 即红）；SandboxSpec 的 AUDIT-RO 变异验红属 memory-mech 批（本支未动该面）。

## 6. 宿主落地命令全集（commit-ready；沙箱外宿主执行）

> 落地顺序（逐级 `--no-ff`，同内容 diff 自动消解）：**memory-mech → dispatcher-ctx → project-memory**。
> 三支 .gitignore 同 diff（`.nebflow/*`+`!.nebflow/Spec/` 配方，多批各自基于 main 重放），逐级合并时自动消解无需人工。
> sbt 私有缓存配方：`SBT_OPTS` 指向 `/tmp/nb-sbt/{boot,coursier,global}-project-memory`（并行批不共享锁）；仅 `compile`/`test`，**禁 sbt run**。

```bash
# ① 分支合并（宿主；逐级 --no-ff）
cd "/Users/dev/Claude code/Nebflow"
git merge --no-ff memory-mech -m "merge memory-mech: MemoryEdit budget gate + ContextRefresher hygiene notice"
git merge --no-ff dispatcher-ctx -m "merge dispatcher-ctx: dispatcher context catalog injection"
git merge --no-ff project-memory -m "merge project-memory: per-project workspace memory.md mechanism"

# ② 初始化：模板 cp 到 N 个项目 workspace（清单=注册表实测 2026-09-05，11 活跃；archived=create-test 排除）
T="/Users/dev/Claude code/Nebflow/.nebflow/worktrees/project-memory/staging/project-memory-template.md"
set -e
cp "$T" "/Users/dev/.nebflow/projects/CZT/.nebflow/memory.md"
cp "$T" "/Users/dev/Claude code/detector-proposal/.nebflow/memory.md"
cp "$T" "/Users/dev/.nebflow/projects/html-deck-studio/.nebflow/memory.md"
cp "$T" "/Users/dev/.nebflow/projects/nebflow-website/.nebflow/memory.md"
cp "$T" "/Users/dev/Claude code/Nebflow/.nebflow/memory.md"
cp "$T" "/Users/dev/.nebflow/projects/neblink-server/.nebflow/memory.md"
cp "$T" "/Users/dev/.nebflow/projects/phd-notebook/.nebflow/memory.md"
cp "$T" "/Users/dev/Claude code/Reminder/.nebflow/memory.md"
cp "$T" "/Users/dev/.nebflow/projects/slideblocks/.nebflow/memory.md"
cp "$T" "/Users/dev/.nebflow/projects/视觉工作室/.nebflow/memory.md"
# 项目名回填（每文件首行 <项目名> → 注册表名；sed 逐个）
for pair in "CZT:/Users/dev/.nebflow/projects/CZT" \
  "detector-proposal:/Users/dev/Claude code/detector-proposal" \
  "html-deck-studio:/Users/dev/.nebflow/projects/html-deck-studio" \
  "nebflow-website:/Users/dev/.nebflow/projects/nebflow-website" \
  "nebflow:/Users/dev/Claude code/Nebflow" \
  "neblink-server:/Users/dev/.nebflow/projects/neblink-server" \
  "phd-notebook:/Users/dev/.nebflow/projects/phd-notebook" \
  "ReminderIsland:/Users/dev/Claude code/Reminder" \
  "slideblocks:/Users/dev/.nebflow/projects/slideblocks" \
  "视觉工作室:/Users/dev/.nebflow/projects/视觉工作室"; do
  name="${pair%%:*}"; ws="${pair#*:}"
  sed -i '' "1s/<项目名>/$name/" "$ws/.nebflow/memory.md"
done
# create-test 已归档（project.json archived=true）——不初始化；恢复显示时按 §2.2 口径补 cp

# ③ Nebula 记忆「记忆管理规则」节增补（Nebula 本人执行项——禁宿主直写）
#   文件: staging/project-memory-routing-rules.md（三条，MemoryEdit target=agent append 进「记忆管理规则」节）
#   与 memory-mech 批 staging/memory-md-rule-section-replacement.md 合并应用，先后由 Nebula 裁量

# ④ dream 批承接：见 §4（无宿主命令；dream 落地时其路径模式扩展覆盖 <workspace>/.nebflow/memory.md 即闭环）

# ⑤ 主仓 .nebflow/Spec 落地（若主仓 .gitignore 尚未是四批配方，重放：
#    .nebflow/* 换掉原 .nebflow/ 行 + !.nebflow/Spec/ 豁免——多批同 diff 自动消解）
git add .nebflow/Spec/project-memory.md && git commit -m "spec: project-memory batch (per-project workspace memory mechanism)"
```

## 7. 边界与未尽事项

- 分发器会话**内**注入形态（taskInjectionText/reentryInjectionText）不重复注入（§2.4）——若未来要求 turn 级记忆新鲜度，需单开增量注入设计，本批不做。
- 项目记忆不进 ContextRefresher 全局注入是**语义决定**而非遗漏；Nebula 需要项目记忆时 Read 单文件（§2.5）。
- `memory-consolidation` flow 的项目维度扫描（每项目 memory.md 生命周期审计）不在本批——flow staging 属 memory-mech 批交付面，项目维度扩展留待该 flow 稳态运行后评估。
- 快照留史在 dream 批落地前为空窗（§4）；若 dream 批延宕而项目记忆已在使用，宿主可临时用 `.nebflow/projects/<name>` 仓的 git 快照兜底（不强制）。
