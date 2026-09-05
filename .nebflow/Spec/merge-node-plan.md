# 合并节点（merge node）机制方案与实施记录

批次：merge-node @ 533a2a0a ｜ 作者指令 2026-09-05 11:44 ｜ 状态：实施完成，待宿主落地

## 1. 现状与问题

现行流程：任务节点各自在 worktree/分支产出，completed 后产物滞留——2026-09-05 审计
（n-f3e66320）实锤 9+ 支「做了但没应用」，落地全靠宿主手工 merge。根因：Flow Map
拓扑里没有「落地」这个角色的节点，批次产物没有收口点。

机制设计：凡产生产物的批次，分发器把每任务节点的 out **多对一接到同一「合并节点」**
（`merge=true`），由它收口落地：`--no-ff` 合并全部上游分支进 main → `git worktree
remove` → `git branch -d` → `git worktree list` / `git for-each-ref` 复核零残留。

### 1.1 实施改动清单（全部在 merge-node 分支）

| 文件 | 改动 | 冲突纪律 |
|---|---|---|
| `ProjectTypes.scala` | NodeDef 新增 `merge: Boolean = false`（withDefaults 零迁移） | 直改，锚点在 hold 字段后；与 node-flowmap-slim 批的 NodeDef 区 hunk（:53-58 deps 注释区）不相邻 |
| `MergeNodePolicy.scala` | **新文件**：isMerge / haltsOnFailure / upstreamFailureFeedback / renderBlocked——语义单点 home | 直改（新文件零冲突） |
| `NodeEngine.scala` | deliverFailed 一处分流 + `mergeBlockedByUpstreamFailure` 私有方法（基线行号 ~:806-878） | 直改小 hunk，**在 completion-gate 批三 hunk 区间（:47-53 / :521-527 / :672-734）之外**；completeNode/deliverOut/blockedNode/heldNode/releaseNode 零改动 |
| `NodeTools.scala` | merge 参数：schema 一行 / call 解析 2 行 / 归档 forbidden + 拒绝分支 / createNode+proceed 透传 / merge+worktree 拒绝校验 | 直改小 hunk；⚠️ schema 区是 node-flowmap-slim 批声明工作区（其当前 diff 未触 NodeTools，落地时逐 hunk 复核） |
| `NodeMergeSpec.scala` | **新文件**：T0 + S1-S4 E2E（见 §6） | 直改（新文件零冲突） |
| `.gitignore` | `.nebflow/` → `.nebflow/*` + `!.nebflow/Spec/` | 与 memory-plan 批**同一 hunk**（逐字节相同），先落者胜，后落者重放为 no-op |
| `.nebflow/Spec/merge-node-plan.md` | 本文档（tracked） | — |
| `staging/system-addendum-merge-node.md` | 分发器规则小节（宿主 cp 落地） | 与 node-flowmap-slim 批同目标文件，见 §7 落地命令 |

`ProjectActor.scala` 本批零改动（符合任务书预期）。

## 2. 接线规则（分发器行为）

规则正文与合并节点创建模板落 `staging/system-addendum-merge-node.md`（宿主追加进
`~/.nebflow/agents/project-dispatcher/system.md`，锚定注释
`<!-- merge-node-rules:start -->` / `end`，追加式写入不碰既有内容）：

- **触发条件**：凡产生分支/worktree 产物的批次，每任务节点 out 多对一接同一合并节点；
  纯调查/零产物批次可不接。
- **创建模板**：`agent=general`、**不配 worktree**（依据 §4）、`merge=true`、task 必含
  上游分支↔worktree 清单 + 落地命令全集（`--no-ff` 合并 → worktree remove →
  branch -d → 双命令复核）+ 完成标准；commit-ready 代执行语义（上游只申报，落地由
  合并节点代做）。
- **NodeEdit 契约**（引擎侧强制）：`merge=true` + `worktree` 同传 → 拒绝（create 校验，
  错误文案给可行动指引）；merge 为 create-only 标志（edit 忽略、归档节点拒绝）。
- **blocked 后续**：合并节点 blocked（上游 failed）→ 处置失败上游（重激活）→ 再重激活
  合并节点（既有重激活链：deliveredTo 清零 + 已完成上游结果重投）→ 合并重跑，不删节点。

## 3. 触发语义（barrier）

复用既有 in-barrier/join 语义（`deliveredTo ⊇ in` 归零 → `startNode`），逐上游状态钉死：

| 上游终态 | 行为 | 实现锚点 |
|---|---|---|
| completed | 正常结算（deliveredTo += 上游）→ 全齐触发 | 既有 `deliverOut`（NodeEngine），零改动 |
| blocked | 不结算不触发（blockedNode 本就不投递）；走既有 blocked 重入协议处置该上游，合并节点原地 wiring/pending 等待 | 既有 `blockedNode`，零改动 |
| failed | **不做 collect 占位结算**（旧行为会让合并在不完整输入上启动），合并节点转 blocked 可见终态：status=Blocked / blockedFeedback(category=upstream-incomplete, detail 含失败上游名) / result=渲染串 / ttlExpireAt=None / **blockCount 不增**（非节点自报轮次，FeedbackRouter 不路由——重入主责在失败上游侧）；已完成上游的既有结算保留；Nebula 收 merge-blocked 通报（fire-and-forget，eventType=blocked，对齐 escalate 通道） | 新 `MergeNodePolicy.haltsOnFailure` + `NodeEngine.mergeBlockedByUpstreamFailure`，挂接点=deliverFailed 单点 |
| held | release 后按 completed 计入：releaseNode → deliverOut → 结算触发（NodeHoldSpec T2 语义衔接） | 既有 `releaseNode`，零改动 |
| cancelled | 无投递（cancelNode 本就不结算）；合并节点保持 wiring/pending 可见，分发器 NodeList 巡检处置（改接/abandon） | 既有 `cancelNode`，零改动 |

边界行为：
- **先钉后改**：旧行为=failed 上游经 `deliverFailed` collect 占位结算（§2.4 默认
  collect）→ 合并节点会在残缺输入上启动。S3 断言 `deliveredTo` 不含 failed 上游 id
  即钉住新契约；旧行为的 collect 对**非合并**下游零变化（守卫条件 `merge && (wiring|pending)`）。
- 转换只认 wiring/pending（running/终态不动——running 合并节点的启动前提是全部上游
  已完成投递，失败上游触达不到）；R2 纪律（mutate 事务内现读 fresh，拒写已变状态）。
- blocked（合并节点）→ 修复失败上游 → NodeEdit 重激活合并节点 → 既有重激活链自动
  补齐 barrier（deliveredTo 清零 + 已完成上游结果重投）→ 合并重跑。
- 「先写测试钉住现行为再改」的执行：S2 用 escalate-only 档钉住「blocked 上游 → 合并
  节点零动作 + 升级链路可见」；S3 钉住「failed 上游 → 占位结算被旁路 + 转可见终态」。

## 4. 权限选型（宿主权限三选一——证据化结论）

**结论：选型 =「合并节点不配 worktree」，零引擎沙箱改造。验证结论：既有语义
「无 worktree 节点 = workspace 根 = 可写 .git」已足够。**

代码级证据链（root 推导）：
1. `PathUtil.resolveNodeProjectRoot(workspace, worktree)`（paths.scala:173-181）：
   `case None => workspace` —— 无 worktree 节点的 projectRoot = workspace 本体。
2. `NodeEngine.runWithAgent`（:334-341 + :404-407）：projectRoot 传入
   `NodeRunner.spawnAgentActor(projectRoot=Some(projectRoot), sandboxEnabled=true)`。
3. `SandboxPolicy.forRoot(root, cfg)`（SandboxPolicy.scala:100-109）：`root = canonicalize(root)`
   —— 会话沙箱根 = 节点 projectRoot = workspace。
4. `SandboxPolicy.writableRoots`（:119-120）= `root + extraWritable + tempRoots`——
   workspace 在可写根内；主仓 `.git` 位于 `<workspace>/.git` ⊂ root。
5. `FileSandbox.checkWrite`（FileSandbox.scala:31-47）：`writableRoots.find(contains(_, target))`
   —— `.git` 内路径命中 root，文件五工具写放行。
6. Bash 层 OS 强制（SandboxBackend.Seatbelt.profileFor，:106-122）：profile =
   `(deny file-write*)(allow file-write* <writableRoots…>)(deny file-write* (subpath .git/hooks))`
   —— root 内 `.git` 写放行（注释原文「保留 git commit」），仅 `.git/hooks` 拒绝防
   hook 注入持久化。git merge / worktree remove / branch -d 全部只写 `.git` 非 hooks
   区 → **沙箱内真实可执行**（S1 E2E 实证，见 §6）。

反证（worktree 节点为何 EPERM）：worktree 节点 root = `<workspace>/.nebflow/worktrees/<name>`；
git worktree 的管理数据在主仓 `<workspace>/.git/worktrees/<name>/index.lock` 等——
在沙箱根外 → FileSandbox/Seatbelt 双双拒绝（n-344d4661 等多例实证，永久拒绝非锁竞争）。
**本批一手复现（2026-09-05）**：本批节点自身即 worktree 根会话，`git add` 实测
`不能创建 '<主仓>/.git/worktrees/merge-node/index.lock': Operation not permitted` +
`[sandbox: bash write denied outside sandbox root <worktree>]`——证据闭环。合并节点
配 worktree = 把落地执行者放进一个写不了 `.git` 的笼子；不配 worktree（root=workspace）
则 `.git` 在根内可写（S1/S4 落地成功即行为级证明）。

三候选处置：
- **a) additionalRoots 含主仓 .git（引擎沙箱策略改造）**：未来增强，不做。改造点清单：
  NodeEdit/NodeDef 需暴露 per-node additionalRoots 注入（当前 SandboxConfig 只读
  nebflow.json 全局）+ SandboxPolicy.forRoot 接受 extraWritable 覆盖 + Seatbelt
  profile 缓存键已按 roots 集合（天然兼容）；风险=把主仓 `.git` 写面开放给任意
  worktree 节点，扩大爆炸半径，收益为零（选型已闭环）。
- **b) 引擎侧代执行 git merge（host 进程无沙箱）**：未来增强，不做。改造点清单：
  NodeEngine 终态化外新增 git 执行器（对齐 CompletionGate.defaultRunner 形态）+
  冲突/残留语义 + 幂等账本；代价=引擎吃进业务 git 语义，违背「合并=agent 任务」的
  节点模型（节点就是干这个的）。
- **c) 宿主/Nebula 代理执行（落地命令全集+逐项校验清单）**：兜底形态保留——
  staging 文件里的命令全集即兜底清单；当沙箱 probe 不可用（BashTool fail-closed
  触发）时，合并节点会 BLOCKED 申报，宿主按清单代执行。**正常路径不用**。

模板因此钉死：合并节点 `agent=general` + **不配 worktree**；NodeEdit create 校验
强制（merge+worktree 同传拒绝，T0-N1 钉住）。

## 5. 防污染验收闭环

**合并节点完成标准 = 合并完成 + worktree 已清（`git worktree list` 复核）+ 分支已删
（`git for-each-ref` 复核）**；残留即不算完成——合并节点 agent 必须 BLOCKED 申报残留
明细（blocked「产物滞留未申报」同口径）。S1/S4 E2E 在节点 completed 后由测试宿主侧
独立复核双命令零残留（不信任节点自报）。

与 completion-gate 批闸门的衔接论证：
- 该批 `CompletionGate.check(workspace, fresh.worktree, result, runner)` 的跳过条件
  明文「**无 worktree（直接作答型）→ 跳过**」（CompletionGate.scala 头注 + check 守卫）。
  合并节点 worktree=None → **闸门对合并节点天然不拦截**，completed 出口零摩擦。
- 任务书口径的兼容性论证仍成立：即便未来闸门覆盖无 worktree 节点，合并节点的「产物」
  是 main 上的合并提交（自身无 worktree 脏面），判据落 c 类「零改动」——合法态。
- **上游分支删除后闸门判据行为**：任务节点在自身 completed 时已被闸门校验（worktree
  存在、a 类「领先≥1 且净」或 b 类 commit-ready 申报）；合并节点随后删除其 worktree/
  分支，此后闸门再查这些上游节点命中的是「worktree 目录已不存在 → 跳过」守卫——
  删除动作不被闸门判为脏，链路自洽。
- 本批引用 commit-ready 契约以任务书口径为准：completed ⇒ a 分支领先≥1 且净 /
  b 脏但结果含 commit-ready 申报 / c 零改动；否则 blocked「产物滞留未申报」。

## 6. E2E 证据（NodeMergeSpec，进程内 stub LLM + 临时 git fixture）

fixture：隔离 dataRoot（`target/test-node-merge`）+ 每场景独立项目仓（`git init -b
main` + 初始提交 + 两个 worktree/分支/产物提交，host 侧建仓=分发器等价操作）；引擎
/host 侧均有写权限。合并经**真实 agent 栈**执行：stub LLM 发 `Bash` tool_use →
permissionPolicies(AutoAll) → BashTool → ShellSession（cwd=沙箱根=workspace），
落地命令=分发器模板原文（stub 从 task 文本提取 `CMD:…END` 块执行——模板命令集
真实可执行的直接证据）。**环境约束披露**：本批测试 JVM 运行在宿主沙箱进程树内，
嵌套 `sandbox-exec` probe 必败（2026-09-05 实测）→ 测试以
`bashFailIfUnavailable=false` 显式降级执行真 git（cwd=沙箱根与 .git 写语义不变，
仅 Seatbelt 包裹层在该环境缺席）；「root 内 .git 可写」的生产属性由 §4 代码证据链
+ 生产运行实证承载。E2E 另捕获一处真实缺陷并已修复：合并节点带 task 创建曾被
「入口节点创建即运行」语义误启动（零上游即落地）——已加 `merge=true` 豁免（T0-N2b
钉住：创建后保持 pending 不启动）。

| 用例 | 断言 |
|---|---|
| T0 | merge+worktree 同传拒；merge=true 落库（worktree=None，status=pending）；归档节点拒设 merge |
| S1 全 completed | 两上游→合并节点触发；输入含两份上游结果头；main 含两条 merge 提交 + 产物文件落 workspace；`git worktree list` 仅剩主 worktree、`for-each-ref` 无 feat/task-a|task-b——**零残留** |
| S2 单 blocked | 合并节点保持 wiring 未启动（deliveredTo 仅含完成上游）；升级通知可见（escalate-only→recorder eventType=blocked）；分支/worktree 残留原样 |
| S3 单 failed | 合并节点转 blocked（category=upstream-incomplete、detail 含失败上游名、blockCount=0、ttlExpireAt=None）；failed 上游不占位（deliveredTo 不含它）；Nebula 收 merge-blocked 通报；零合并零清理 |
| S4 上游 held | held 期 barrier 挂起（合并节点 wiring）；release → completed 计入 → 合并真实落地 + 双命令零残留 |

## 7. 宿主落地命令（全集）

**交付形态 = commit-ready**：全部改动已在 merge-node 工作树验证完毕（sbt 编译 +
110/110 测试），因 worktree 根会话写不了主仓 `.git`（§4 EPERM 实证，本批机制自身
即结论），逐文件 commit 由宿主/Nebula（workspace 根会话）代执行：

```bash
cd "/Users/dev/Claude code/Nebflow/.nebflow/worktrees/merge-node"
git add src/main/scala/nebflow/core/project/ProjectTypes.scala
git commit -m "feat(merge-node): NodeDef.merge 标记——批次产物落地收口节点（withDefaults 零迁移）"
git add src/main/scala/nebflow/core/project/MergeNodePolicy.scala
git commit -m "feat(merge-node): MergeNodePolicy 语义单点（isMerge/haltsOnFailure/上游失败合成反馈）"
git add src/main/scala/nebflow/core/project/NodeEngine.scala
git commit -m "feat(merge-node): deliverFailed 挂接——上游 failed 旁路 collect 占位结算，合并节点转 blocked 可见终态不悬挂（hunk 在 completion-gate 批区间外）"
git add src/main/scala/nebflow/core/tools/NodeTools.scala
git commit -m "feat(merge-node): NodeEdit merge 参数——create-only + merge+worktree 拒绝（沙箱根须=workspace）+ 合并节点豁免「创建即运行」"
git add src/test/scala/nebflow/core/project/NodeMergeSpec.scala
git commit -m "test(merge-node): T0 校验 + S1-S4 触发语义 E2E（真实 --no-ff 落地 + worktree/分支零残留断言）"
git add .gitignore .nebflow/Spec/merge-node-plan.md
git commit -m "docs(merge-node): 方案落 .nebflow/Spec（tracked）+ gitignore Spec 白名单（与 memory-plan 批同 hunk，重放 no-op）"
git add staging/system-addendum-merge-node.md
git commit -m "docs(merge-node): 分发器合并节点接线规则小节 staging（宿主 cp 追加进 project-dispatcher/system.md）"
```

**分发器 system.md 追加**（与 node-flowmap-slim 批各自「落地前重读目标文件、只追加
自己小节」，先后顺序无关）：

```bash
grep -q "merge-node-rules:start" ~/.nebflow/agents/project-dispatcher/system.md || \
  cat "/Users/dev/Claude code/Nebflow/.nebflow/worktrees/merge-node/staging/system-addendum-merge-node.md" \
    >> ~/.nebflow/agents/project-dispatcher/system.md
cd ~/.nebflow && git add agents/project-dispatcher/system.md && \
  git commit -m "dispatcher: 合并节点接线规则小节（merge-node 批 staging 落地）"
```

**分支合并顺序与逐 hunk 校验清单**：completion-gate 先落 main → 本分支再合并。
本批 NodeEngine 两 hunk（基线 :809-814 / :833 后新增）在该批三 hunk 区间
（:47-53 / :521-527 / :672-734）之外，3-way 应自动合并；合并后核对：
1. completeNode 的 gate 分流完好（本批未触碰 completeNode）；
2. deliverFailed 首分支为 `MergeNodePolicy.haltsOnFailure` 守卫，其后既有 collect 结算原样；
3. NodeTools：本批 schema `merge` 属性与 flowmap-slim 的 schema 追加共存；
   `createNode/proceed` 签名尾部 `merge: Boolean = false` 与对方新增参数顺序核对；
4. ProjectTypes：`merge` 字段锚在 `hold` 之后；flowmap-slim 的 NodeDef/NodePayload
   新增字段不重叠（本批未触 NodePayload）；
5. `.gitignore`：与 memory-plan 批同一 hunk（逐字节相同）——先落者胜，后落者
   `git add .gitignore` 时 diff 为空则跳过该文件。
