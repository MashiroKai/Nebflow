# 阶段 2c · agent 收敛实施报告（任务书 §C 全量七项）

> 节点：实施-2c-agent收敛·重派（n-ae12a46e）｜执行：Coder｜2026-09-04
> worktree：`.nebflow/worktrees/phase2c-agent-convergence`（分支 phase2c-agent-convergence，预建基线 @c391415b）
> 设计文档唯一权威：`~/.nebflow/docs/Nebflow/20260902_project-architecture-phase2-design.md` §C（已全文通读；§H 相关点 H-1①/H-9① 按文档已裁定值执行）

---

## 0. 环境硬约束与两条降级路径（先读）

本会话沙箱 root=worktree 目录 + /tmp（实测探针：workspace 根、主仓 `.git`（objects/refs/index/ORIG_HEAD）、`~/.sbt`、`~/.ivy2`、`~/.nebflow/*`、`~/.cache` 全部 EPERM；Bash 读面自由）。因此：

| 预期操作 | 现场判定 | 降级路径（已执行） |
|---|---|---|
| `git merge main` | **不可能**（common `.git` 写入 EPERM，`git apply --check` 通过但 objects/refs/index 全禁写） | **文件级合并具体化**：`git diff --binary c391415b main > patch && git apply`（纯工作树补丁，零 index/object 写）→ `git archive main` 双向比对（内容 0 差异） |
| 分支 commit | 不可能（同上） | 交付物全量落 worktree + 宿主侧命令（见 §6/§7） |
| sbt 运行 | `~/.sbt/boot/sbt.boot.lock` EPERM | 缓存 APFS clone 至 /tmp（`cp -Rc ~/.sbt/boot ~/Library/Caches/Coursier ~/.ivy2 ~/.sbt/1.0 → /tmp/nb-2c-sbt/`）+ `SBT_OPTS` 重定向 boot/ivy/coursier/global.base |
| ~/.nebflow/agents 定义写 | EPERM（任务书已知现场前提） | staging 双路径：worktree `deliverables/nebflow-defs/` + `/tmp/nb-2c-agent-converge/nebflow-defs/` |

sbt 完整调用（全量验证均用此形态，前台跑完）：
```
SBT_OPTS="-Dsbt.boot.directory=/tmp/nb-2c-sbt/boot -Dsbt.ivy.home=/tmp/nb-2c-sbt/ivy2 \
  -Dcoursier.cache=/tmp/nb-2c-sbt/coursier -Dsbt.global.base=/tmp/nb-2c-sbt/1.0 -Dsbt.color=false" \
  sbt -batch test
```

## 1. 第〇步：GlobTool/GrepTool hunk 预检（开工第一件事）

- 预检时点 sandbox-readlist tip：`fe3007d207cf6ba1afd75bd76f7280c14069e6f8`（本批未合并）
- `git diff main...sandbox-readlist -- GlobTool.scala GrepTool.scala` hunk 区间（main 侧行号）：
  - GlobTool.scala：[1-7 imports]、[112-125 checkReadRoot Right 分支→memExcludes→runGlob 调用与签名]、[145-150 rg args 尾部追加 excludes]
  - GrepTool.scala：[1-7 imports]、[134-141 Right 分支→memExcludes→runGrep 调用]、[150-155 runGrep 签名]、[176-188 用户 glob/type 之后追加 excludes]
- 我方默认根改动区域：GlobTool ~68-74 / GrepTool ~114-123（`workDir`/`baseDir` 计算）——与最近 hunk 间距 ≥5 行，**不交叠**
- 现场重要发现：**item 6 的实质（缺省根=node root）已由阶段 2a 随主库交付**——main 版 GlobTool.scala:68-74 / GrepTool.scala:114-123 已是 `if ctx.sandbox.enabled then ctx.sandbox.root else os.Path(user.dir)`（代码注释自证「阶段 2a 沙箱（§A.2/§A.8-8）：相对路径与缺省根按节点 sandbox.root 解析——修掉默认根=JVM user.dir 的现状」）。设计文档 §C.5 的「现状 user.dir，:68 改」是 2a 合入前的陈旧锚点。
- **预检结论：不交叠 → 记录后开干**。node root 语义改为「spec 断言固化」而非重复改码（见 §3-6/§4）。

## 2. 七项改动清单（文件/函数级）

| # | 项 | 文件与改动 |
|---|---|---|
| 1 | **MemoryEditTool** | 新建 `src/main/scala/nebflow/core/tools/MemoryEditTool.scala`：`object MemoryEditTool extends Tool`。target 白名单硬编码 `MemoryStore.userMemoryPath` + `MemoryStore.agentMemoryPath("Nebula")`（`resolveTarget`，schema 仅 target/action/section/match/content 五参，**无路径参数**）；四 action（append/update/remove/replace_section）+ section 精确匹配（## 前缀可选）+ match 条目内首命中；无命中结构化报错（MEMORYEDIT_NO_MATCH/NO_SECTION/PARAM/TARGET/ACTION）并列出既有条目前缀/节名；结果带「next lifecycle 生效」提示；写路径=MemoryStore 同一写函数（saveUserMemory/saveAgentMemory，mtime 缓存自动失效）。注册：`registry.scala` builtins 尾部 `"MemoryEdit" -> MemoryEditTool`；Nebula 专属：`AgentCore.NebulaExclusiveTools` 增 "MemoryEdit"（§C.1 记忆行：非 Nebula 声明也剥离） |
| 2 | **NebulaOrchestrationTools 补 NodeList** | `AgentCore.scala` companion `NebulaOrchestrationTools` val 重定义为 §C.1 十四件（含新增 NodeList 只读观测面；Task/ProjectCreate/AgentControl/Mail/SendFriendMessage/Delegate/FlowTrigger/FlowExecute/AskUserQuestion/Pop/Schedule/TransferFile/MemoryEdit） |
| 3 | **Nebula 固定工具集机制化** | `AgentCore.fixedToolsFor` Nebula 分支：`AgentCore.NebulaOrchestrationTools + "Issue"`（**BaseTools 六件移除**；Issue=任务书裁定「保持现状不动」保留，未注册故交付面惰性）；`buildAllowedToolSet`：新增 `ConvergedAgentNames = {"Nebula","project-dispatcher","general"}`，收敛三定义 base=∅（**agent.json tools 声明整体失效**——存量 8684acd 六件声明与 flows:["*"] 自动 no-op）；FlowTrigger 对 Nebula 机制固定（`agentDef.flows.nonEmpty || isNebula`，不再依赖 flows 声明）；显式移除面由集合语义保证：六文件工具/Web 系/TeamTask*/SubTask/NodeEdit/NodeCancel 均不在固定集且非 team 类别自动剥离。Delegate/FlowTrigger/FlowExecute 按双轨期保留（阶段 3 拆除）——与 §C.1 原文一致 |
| 4 | **dispatcher 固定工具集** | `AgentCore` 新增 `DispatcherFixedTools = {NodeList, NodeEdit, NodeCancel, Read, Glob, Grep, Bash}`；`fixedToolsFor` 新增 `case _ if name=="project-dispatcher"` 分支。不给 Write/Edit（只分解不产内容）、不给 AskUserQuestion（单次会话不阻塞）——声明失效由 ConvergedAgentNames 保证。分发器 spawn 形态（ProjectActor.scala:440 `isFlowNode=true`）下 Node 三件不在 leaf 剥离集，实测存活 |
| 5 | **general + MultiEdit 删除** | `fixedToolsFor` 新增 `case _ if name=="general"` → `GeneralFixedTools = BaseTools + AskUserQuestion + Pop`（§C.5 裁定 5 八件；val 定义置于 BaseTools 之后避免初始化序问题）；`registry.scala` 删除 `"MultiEdit" -> MultiEditTool`（**注册层不挂**，MultiEditTool 类保留非物理删除）；悬空描述修正：WriteTool.scala:30 / EditTool.scala:47 不再指向已注销的 MultiEdit（Edit replace_all 覆盖语义入文） |
| 6 | **Glob/Grep 默认根=node root** | main 已含 2a 实现（见 §1）；本批以 spec 固化（§3-6）+ 语义说明：node 会话 sandbox.root=SessionContext.projectRoot（NodeEngine.scala:159-161/ProjectActor spawn 唯一权威）=worktree/workspace；分发器 root=workspace（H-5①）；Nebula 无文件工具→默认根语义不存在；沙箱关（§G.1 回滚/双轨旧会话）保持 user.dir 旧行为——与 §A.6/§A.7/§G.1 全部一致，零代码 delta |
| 7 | **双 system.md 重写** | `~/.nebflow/agents/Nebula/system.md`（§C.2 六要点全新重写：身份边界/项目生命周期协议/memory 维护纪律/双轨期知识/工具自包含/消息纪律）；`~/.nebflow/agents/project-dispatcher/system.md`（§C.3 七步协议+无持久上下文/Plugin Catalog/AGENTS.md 优先/固定工具认知）；`~/.nebflow/agents/general/agent.json`（§C.4 极简：name+description，无 tools/skills/mcpServers）+ `general/system.md`（四节 14 行 ≤40）。因沙箱 EPERM 全部落 staging（§5） |

配套 spec（行为变化处独立新增 + 既有 spec 行为面更新）：
- 新增 `src/test/scala/nebflow/core/tools/MemoryEditToolSpec.scala`（13 用例）
- 新增 `src/test/scala/nebflow/agent/AgentConvergenceSpec.scala`（7 用例）
- 更新 `src/test/scala/nebflow/agent/AllowedToolSetSpec.scala`（Nebula §C.1 断言/显式移除断言/dispatcher/general 固定集/六件保留域收窄；FlowTrigger-Nebula 语义更新）
- `build.sbt`：`Test / testGrouping` —— MemoryEditToolSpec 独占 forked JVM（MemoryStore MtimeCache val 在 JVM 首次触碰时钉死 dataRoot 路径，串行化挡不住跨 suite 初始化污染；其余 suite 维持原 in-process 组）

## 3. 验收逐项证据（全部前台真实执行）

### 3.1 定向 spec
`testOnly MemoryEditToolSpec AgentConvergenceSpec AllowedToolSetSpec` + 受影响既有套件（SandboxSpec 外的全部）：**MemoryEdit 13/13 + AgentConvergence 7/7 + AllowedToolSet 全绿**；GlobToolSpec/GrepToolSpec/FriendMessageToolSpec（含 TOOL_MAP 含 SendFriendMessage 断言）/MultiEditToolSpec/EditToolSpec/ToolLoaderSpec/DeletedToolGuardSpec/ToolResultGuardSpec 全绿。

### 3.2 全量 sbt test（前台一次跑完）
**Total 2286, Passed 2265, Failed 21, Errors 0, Ignored 7**。21 个失败逐一签名归因（与基线先例「约 20 个集中于 SandboxSpec/Bash/PopTool」同构，**零语义新增红**）：

| 类别 | 数量 | 明细 |
|---|---|---|
| ~/ 写 EPERM（环境） | 16 | SandboxSpec ×15（`~/.nb-sbx-dataroot-*` createTempDirectory EPERM：A.2×3、§A.4×4、A.8-1/2/3/4/8/8b、G.1×2、H-12）+ PopToolSpec `~ expansion`（`~/.nebflow-pop-test-tmp.png` EPERM） |
| CPU 探测类（环境） | 3 | BashBackgroundHardTimeoutSpec B-2、BashActivityBridgeSpec D-2、ShellStuckDetectorSpec #17（`while True: pass` CPU delta 探测） |
| 时序 flake（环境） | 1 | NodeSessionDeathFinalizeSpec D1——**单独复跑 2/2 全绿**（投递路由本批零触碰，全量负载下 0.16s 窗口竞态） |
| 语义性失败 | **0** | — |

### 3.3 隔离实例冒烟（NEBFLOW_HOME=/tmp/nb-2c-smoke/home + NEBFLOW_GATEWAY_PORT=8281）
assembly fat jar（`target/scala-3.5.2/nebflow-assembly-1.4.1-beta.54.jar`）启动隔离实例（无 LLM 配置即 REST 可用），staging 定义物化进临时 home：

- `GET /api/agents/general` → `fixedTools: [Read, AskUserQuestion, Glob, Write, Grep, Bash, Edit, Pop]`（恰 8 件）；systemPrompt=staging 四节新文 ✓
- `GET /api/agents/Nebula` → `fixedTools: [AgentControl, AskUserQuestion, Delegate, FlowExecute, FlowTrigger, Issue, Mail, MemoryEdit, NodeList, Pop, ProjectCreate, Schedule, SendFriendMessage, Task, TransferFile]`（§C.1 十四件+惰性 Issue；旧 agent.json 文件工具声明仍在 `tools` 字段但机制失效）✓
- `GET /api/agents/project-dispatcher` → `fixedTools: [NodeList, NodeEdit, NodeCancel, Read, Glob, Grep, Bash]`（恰 7 件；声明 Write/Edit 失效）✓
- MemoryEditTool 注册：Nebula fixedTools 含 MemoryEdit + AgentConvergenceSpec `TOOL_MAP.contains("MemoryEdit")` 断言 + 四 action 语义=MemoryEditToolSpec 13 用例（spec 断言路径）✓
- 收尾：lsof 确认残留 PID 74108（worktree cwd 的 java，非宿主 94384）→ kill → 端口 8281 释放 ✓；`rm -rf /tmp/nb-2c-smoke/home` ✓（instance 日志留存 `/tmp/nb-2c-smoke/instance*.log`）

### 3.4 变异验红（2 条，验后全部恢复）
| 变异 | 方式 | 红证据（原文） | 恢复后 |
|---|---|---|---|
| MultiEdit 保留变异 | registry.scala 重新挂 `"MultiEdit" -> MultiEditTool` | `==> X AgentConvergenceSpec.MultiEdit is removed from ToolRegistry ... munit.FailException: AgentConvergenceSpec.scala:77 registry 不再挂 MultiEdit（移除=注册层不挂）`；`Failed: Total 8, Failed 1` | 21/21 全绿 |
| MemoryEdit 白名单绕过变异 | resolveTarget 加 `case other if other.endsWith(".md") => Right(Target(os.Path(other), ...))` | `==> X MemoryEditToolSpec.unknown or path-like target is rejected ... IllegalArgumentException: User.md is not an absolute path`；`Failed: Total 13, Failed 1` | 13/13 全绿 |

## 4. item 6 默认根语义（§C 说明，报告写明）

- **general node 会话**：sandbox.enabled（NodeEngine spawn `sandboxEnabled=true`）→ Glob/Grep 缺省根=`ctx.sandbox.root`=SessionContext.projectRoot（worktree 节点=worktree，否则 workspace）——spec 断言：AgentConvergenceSpec「Glob/Grep default root = sandbox.root (node root) — sibling-dir marker invisible」（root 内 marker 可见、sibling/user.dir 文件不可见）
- **分发器会话**：`sandboxEnabled=true` + root=project workspace（ProjectActor.scala:441-444，H-5①）→ 缺省根=workspace
- **Nebula 会话**：无任何文件工具（本批机制移除）→ 默认根语义不适用
- **沙箱关**（`sandbox.enabled=false` 全局回滚 / team-flow-Delegate 双轨旧会话）：user.dir 旧行为保留（§G.1 回滚语义，SandboxSpec「G.1 回滚: … Glob 缺省根=user.dir」既有用例持续覆盖）

## 5. 定义层落地（~/.nebflow repo）：**staging 路径**（直接写 EPERM 实证：`touch ~/.nebflow/agents/.wprobe` → Operation not permitted）

staging 文件清单（双路径，内容一致）：
```
deliverables/nebflow-defs/agents/Nebula/system.md               （§C.2 六要点，全新）
deliverables/nebflow-defs/agents/project-dispatcher/system.md   （§C.3 七步协议，全新）
deliverables/nebflow-defs/agents/general/agent.json             （{"name":"general","description":"通用执行 agent——能力由分配的 plugins 决定"}）
deliverables/nebflow-defs/agents/general/system.md              （四节 14 行）
/tmp/nb-2c-agent-converge/nebflow-defs/agents/…                 （同上四文件镜像）
```
注：Nebula/project-dispatcher 的 agent.json 本批不改（机制层已使 tools 声明惰性，定义层 tools 字段退役属后续 2d/2e 清理面）。

## 6. commit hash 汇总

**本节点会话内 git commit = 0**（沙箱 root=worktree，common `.git` 禁写，merge/commit 均物理不可能——见 §0）。替代交付：
- worktree 工作树 = 文件级「合并 main 后 + 2c 全部改动」的最终态（`git archive main` 双向比对零差异后叠加 2c delta）
- 宿主侧执行 §7 命令后产生的 commit hash 即本节点交付 hash（预计 2-3 笔：2c 主 commit + 定义层 commit + 报告归档 commit）

全链 hash 清单（用户要求最终回报）：
| 环节 | hash |
|---|---|
| 前端件一 sidebar-plugins 合入 main | `dfdcaa858fdd2a88ae29a837de1a7be386fd9e14` |
| 前端件四 archive-result-full | `091928a1afb2f5da07b0d97a331a7901f180eb15` |
| 前端件五 stat-banner-dedupe | `2081655ed433fcd92ef83f7719149b06fef5373c` |
| 前端件六 archive-btn-neutral | `f7b8577b39083f911e2f99a073a0428571b6cecd` |
| 前端件七 flowmap-emoji-clean | `69606a7430559aea919b7c93125133a00f9ff4cf` |
| 前端件 flowmap-cad-zoom | `45f237c0f9486657e293d53e271e194e83e29310` |
| 前端件 micorb-tune | `fd2e0080286858e22cf0f8981ecda8cf736b9843` |
| 2b plugins 合入 main（barrier 注入） | `30a06cf84789474597dcf6b86ffd09ce9fa408b9` |
| 2b 分支保留 | `9558f92199f5eee6bf61faa4af3c6fd3dd048955`（供 2d/2e） |
| sandbox-readlist（在飞，未合并） | `fe3007d207cf6ba1afd75bd76f7280c14069e6f8` |
| 本节点（2c）基线 | `c391415b`（worktree 预建点） |
| 本节点 2c commit | 会话内不可产出（沙箱）→ 宿主执行 §7 命令后生成 |

## 7. 宿主侧落地命令（报告归档 + 定义层 + 分支 commit + 后续合并）

```bash
# ① 报告归档进 ~/.nebflow repo
cp /tmp/nb-2c-agent-converge/20260904_phase2c-agent-convergence-report.md ~/.nebflow/docs/Nebflow/20260904_phase2c-agent-convergence-report.md
cd ~/.nebflow && git add docs/Nebflow/20260904_phase2c-agent-convergence-report.md && git commit -m "docs: phase2c agent-convergence report (seven-item delivery, 2265/2286 green, mutation-red x2)"

# ② 定义层四文件落 ~/.nebflow/agents + commit（按文件 add，禁 -A）
mkdir -p ~/.nebflow/agents/general
cp /tmp/nb-2c-agent-converge/nebflow-defs/agents/Nebula/system.md ~/.nebflow/agents/Nebula/system.md
cp /tmp/nb-2c-agent-converge/nebflow-defs/agents/project-dispatcher/system.md ~/.nebflow/agents/project-dispatcher/system.md
cp /tmp/nb-2c-agent-converge/nebflow-defs/agents/general/agent.json ~/.nebflow/agents/general/agent.json
cp /tmp/nb-2c-agent-converge/nebflow-defs/agents/general/system.md ~/.nebflow/agents/general/system.md
cd ~/.nebflow && git add agents/Nebula/system.md agents/project-dispatcher/system.md agents/general/agent.json agents/general/system.md && git commit -m "defs: phase2c agent convergence — Nebula/dispatcher system.md rewrite + general template (§C.2/§C.3/§C.4)"

# ③ 2c 分支 commit（worktree 即最终态；等价于「合并 main + 2c delta」单 commit；末尾再补真 merge commit 保图干净）
cd "/Users/dev/Claude code/Nebflow/.nebflow/worktrees/phase2c-agent-convergence"
git add -A && git commit -m "feat(agent): phase 2c agent convergence — MemoryEditTool/Nebula §C.1 fixed toolset/dispatcher & general fixed sets/MultiEdit registry removal/node-root default assertions (§C seven-item, 2265/2286 green + mutation-red x2)"
git merge main -m "Merge main (30a06cf8) into phase2c: content-identical materialized merge + 2c deltas"   # 内容已一致，秒合
```

## 8. 生效说明

**全部机制层改动（MemoryEditTool/固定工具集/MultiEdit 移除/general 固定 8 件）需重启宿主实例后生效**；定义层四文件待 ②执行后生效（system.md 热加载，general 定义为新增）。**本批未重启宿主（PID 94384/端口 8080 未碰）**——重启前运行实例行为与合并前完全一致（隔离实例已验证新工具面，宿主重启后即同形态）。

## 9. 遗留问题

1. **git 写沙箱缺口**（本批最大现场教训）：worktree 节点会话 root=worktree 时 common `.git` 不可写 → worktree 内开发节点无法 commit/merge。建议后续节点派发为 worktree 开发类节点追加 `additionalRoots`（H-5③ 预留位）包含 `<workspace>/.git`，或 spawn root=workspace（2b dev 节点先例）。
2. **agents.v2 flag**（§G.3 范围项）本批未实现——本批机制化取「不可配置」强语义直接生效；flag 双装配属后续节点（回滚路径：§7-③ commit revert 即定义级回滚）。
3. NodeSessionDeathFinalizeSpec 全量负载下偶发（单独 2/2 绿）——若后续复现建议加大该 spec 交付等待窗口（与本批无关的既有竞态）。
4. Nebula agent.json 的 mcpServers（opendataloader-pdf）/flows:["*"]/CheckIssues 声明为退役残留（机制已惰性），归入 2d/2e 死引用清理。
5. 死引用清理（§D.4）与 §D.1 条件注入移除 = 2d 范围，本批未动（任务书七项外）。
