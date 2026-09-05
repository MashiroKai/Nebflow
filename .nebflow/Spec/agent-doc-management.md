> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Agent 产出文档统一管理方案

> 状态：**frozen · 已批准（2026-08-17 用户裁定）· dispatch-ready** ｜ 日期：2026-08-17 ｜ 作者：Explorer
> 三项开放问题裁定：① `plan/` 废弃并入 `docs/`；② `docs/` 纳入 `~/.nebflow` git 白名单；③ worktree 风险另立任务。
> 派发任务书见附录 A——对 Nebula 说「执行 docs/Nebflow/agent-doc-management.md 附录 A」即可启动。

---

## 0 · TL;DR

**建一个统一文档区 `~/.nebflow/docs/<域>/`，用「文件名是否带日期前缀」编码生命周期**：活文档（规格书/方案/roadmap）无日期、原地版本演进；阶段文档（诊断/调研/评审）带日期前缀、完成即冻结；纯中间产物（截图/脚本/日志）留在 `/tmp` 由系统清理。规范本体写 `docs/CONVENTIONS.md`，通过「Nebula + Explorer + design-engineer 的 system.md、5 个团队 rules.md 派发模板、research flow」共 8 处注入落地——**核心教训是必须覆盖派发者**（实证：Manager 派发 prompt 里写死的 `/tmp` 路径覆盖了 design-engineer system.md 里已有的 `plan/` 规则）。`plan/` 目录并入 `docs/` 后废弃。存量 58 份 `/tmp` 文档中 24 份迁移、34 份留给系统清理。

---

## 1 · 现状盘点（2026-08-17 实测）

### 1.1 四个存放区，互不知情

| 存放区 | 存量 | 性质 | 问题 |
|---|---|---|---|
| `/tmp/` | **427 个常规产物**（58 md / 208 png / 61 txt / 49 svg / 35 html / 16 json）+ 196 个 `nb-*` 条目 | 默认去处，无规则约束 | 重启即清空；活文档与一次性报告混住；跨区重复 |
| `~/.nebflow/plan/` | 20 份，3 个子目录（`Nebflow/`14 · `default/`5 · `nebflow-project/`1） | Explorer/design-engineer 的 system.md 指定 | 子目录名来源不一致（folderName 推断 / 团队名 / 兜底），同域分散 |
| `~/.nebflow/agents/*/projects/` | Nebula 自用（czt/、抗辐照报告/ 等） | agent 工作产物区 | 仅 Nebula 在用，其他 agent 未采用 |
| `~/.nebflow/workspace-items/` | 2 个残留 JSON | SaveWorkspaceItem 存储（该工具 **2026-08-15 已删除**，有 DeletedToolGuardSpec 守卫） | 死机制，不在本方案范围 |

![/tmp 产物分布](assets/docmgmt-a-distribution.svg)

### 1.2 引用死链风险（重启后全部失效的指针）

- **任务系统**：`tasks/…/284.json`（「等用户确认」**挂起中**）与 `276.json` 记录 `/tmp/message-search-spec-v3.md` 路径；`subagent-tasks/…json` 中 4+ 处派发 prompt 写死 `/tmp/message-search-spec.md`
- **记忆系统**：`User.md` FACT 2 引用 `/tmp/rebrand-param-plan.md`、FACT 8 引用 `/tmp/entity-ecosystem-design.md`（「后续讨论 skill/flow/agent/team 组织时以此文档为基准」——基准文档放在易失区）
- **文档互引**：v3 规格书头部声明「迭代基线：v2.1 规格书 `/tmp/message-search-spec.md`」
- **版本漂移**：`message-search-spec.md`（内部 v2.1）→ `message-search-spec-v3.md`（内部 v3.1）——文件名版本号与内容版本号脱节，两份并存
- **跨区重复**：`/tmp/uiux-quality-system.md` ↔ `plan/Nebflow/20260817_uiux-quality-system.md` 同日同主题；`/tmp/unified-login-design.md` ↔ `plan/Nebflow/20260813_…`；`/tmp/flow-timeout-redesign.md` ↔ `plan/Nebflow/20260814_…`

![现状：四区混住与引用死链](assets/docmgmt-b-status.svg)

### 1.3 规则失效实证（落地路径的关键证据）

design-engineer 的 system.md 防线 1 第 3 条明确写着「规格书写入 `~/.nebflow/plan/<团队>/design-specs/<date>_<feature>.md`」——但 `plan/` 下**不存在** design-specs/ 目录；实际产出在 `/tmp/`。原因：Manager 派发 prompt（subagent-tasks）里写死了「规格书文档写到 `/tmp/message-search-spec.md`」，**派发指令覆盖了 agent 自身规则**。结论：任何只改 agent system.md 的落地方式都不可靠，必须同时约束派发者（Manager/Nebula/团队 rules.md 的派发模板）。

### 1.4 相邻发现（不在本方案范围，建议另立任务）

`/tmp` 同时承载 **git worktree 代码区**：`/tmp/nebflow-rust`（nebflow-rust 团队整个仓库工作区）+ 多个 `nb-*` worktree（最大 1.0G）。重启丢失的是**未合并分支**，风险等级高于文档。worktree-per-agent 模式（写入 `teams/nebflow-project/rules.md` 的既定决策）需要重新选址（如 `~/worktrees/`），属独立任务。

---

## 2 · 推荐方案：单一 docs 区 + 文件名编码生命周期

### 2.1 目录结构

```
~/.nebflow/docs/
├── CONVENTIONS.md            # 规范本体（单一事实源，人类可读）
└── <域>/                      # 域 = folder 名，与 ~/.nebflow/folders/ 对齐
    ├── INDEX.md              # 活文档索引（写入 agent 顺带维护）
    ├── <topic>.md            # 活文档：无日期前缀，原地版本演进
    ├── <YYYYMMDD>_<topic>.md # 阶段文档：日期前缀，完成即冻结
    └── assets/               # 文档配图（活文档配图必须随文档持久化）
```

- **域的确定**：文档跟着项目走。产出时取当前 folder 名；跨域通用文档（产品战略等）放产出会话所在域，INDEX 注明。同项目多入口（如 `Nebflow` 与 `nebflow-project`）**宁集中勿分散**，归并为主 folder。
- **不建 specs/plans/reports/diagnosis 类型子目录**：类型由文件名中的语义词（spec/plan/design/report/diagnosis/audit/roadmap）自然表达。类型目录会让 agent 在写入时多一次分类决策（分类错误的迁移成本高于收益），而生命周期二分法（有无日期前缀）判断成本为零。
- **`plan/` 处置**：存量 20 份全部迁入对应 `docs/<域>/`，目录废弃。派发版 plan 不再是独立概念——它就是活文档的一个状态（见 2.4）。

### 2.2 生命周期：判断三问

![生命周期决策树](assets/docmgmt-c-lifecycle.svg)

| 生命周期 | 判断标准 | 去处 | 命名 | 清理 |
|---|---|---|---|---|
| **活文档** | 会被会话外的引用：任务系统派发、记忆指针、后续修订、迭代基线 | `docs/<域>/` | `<topic>.md`（kebab-case，无日期） | 不自动清理；过时由作者域 agent 标注 `状态: deprecated` |
| **阶段文档** | 完成即冻结但有回看价值：诊断、调研、评审、audit、验收快照 | `docs/<域>/` | `<YYYYMMDD>_<topic>.md` | 不自动清理；日期前缀天然归档 |
| **临时产物** | 仅会话内使用：截图、调试/验证脚本、日志、临时对比图、commit-msg | `/tmp/` | 不限 | 系统自动；零维护成本 |

### 2.3 活文档修订纪律（message-search-spec 教训）

1. **版本日志在文件头**：`v2 (2026-08-17 裁定修订)：…` 的现有实践保留并强化，每次修订追加一行。
2. **禁止文件名版本号**：不允许 `-v3.md` 副本承载新版本。旧版内容沉淀进头部 changelog，单一文件即真相。
3. **配图随文档**：活文档引用的 SVG/PNG 存 `assets/`，禁用 `/tmp` 路径（否则文档活、图先死）。

### 2.4 派发流（替代 plan/ 的派发版概念）

活文档头部维护状态字段：`状态: draft → frozen（用户批准）→ dispatch（已派发）→ shipped（已实施）`。Explorer/Manager 派发时引用同一文件的 frozen 版本；实施完成后更新为 shipped。**不再复制派发快照**——快照的唯一价值是防实施期间源头变动，而状态字段 + git（`docs/` 可纳入 `~/.nebflow` 白名单仓库，见开放问题 3）已覆盖该需求。

### 2.5 INDEX.md 机制

每个域一个 INDEX.md，表格三列：`文件 | 一句话主题 | 状态`。

- **活文档必登记**（写入/修订活文档的 agent 顺带更新一行，约 10 秒成本）
- 阶段文档**不强制登记**（`ls` 的日期排序自解释；登记负担会导致 INDEX 腐烂）
- INDEX 只求「活文档不丢失」，不求全量编目

### 2.6 落地路径：CONVENTIONS.md + 8 处注入

![落地注入点](assets/docmgmt-d-rollout.svg)

| # | 注入点 | 改动 |
|---|---|---|
| 1 | `~/.nebflow/docs/CONVENTIONS.md` | 规范本体（文案草案见 §4） |
| 2 | `agents/Nebula/system.md` | +1 行指针（主入口/派发中枢，覆盖所有直连会话） |
| 3 | `agents/Explorer/system.md` §方案输出位置 | `plan/<folderName>/` → `docs/<folderName>/`（本方案已示范） |
| 4 | `agents/design-engineer/system.md` 防线 1 第 3 条 | `plan/<团队>/design-specs/` → `docs/<域>/` |
| 5 | `teams/{nebflow-project, nebflow-rust, slideblocks, czt-project, voice-recognition-test}/rules.md` | 派发模板段 +1 行：「派发含文档产出的任务时，prompt 必须写明目标路径 `~/.nebflow/docs/<域>/`，禁止指定 /tmp」 |
| 6 | `flows/research/agents/writer/system.md` | 报告输出路径对齐 |

**否决的备选**：
- **做成 skill**：skill 是 opt-in 注入（仅 agent.json `skills` 数组声明的可见），覆盖面天然残缺，且新增声明成本分散——否决。
- **工具层强制（类 SaveWorkspaceItem）**：该工具 08-15 刚被删除（用户功能精简偏好）；且 DB 存 content 削弱 agent 用 Read/Grep 直接访问文件系统的能力——否决。
- **只改各 agent system.md**：§1.3 实证规则会被派发 prompt 覆盖——必须与 rules.md 派发模板双管齐下。

---

## 3 · 备选方案对比

| 方案 | 核心思路 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **R · 单一 docs 区 + 文件名编码生命周期** | `docs/<域>/`，日期前缀区分活/阶段 | 判断规则极简（三问）；零工具依赖；agent 用原生文件能力直接访问；INDEX 负担最小 | 依赖 prompt 注入自觉性（靠 8 处注入 + 派发模板对冲） | **推荐** |
| A · plan/ 正名扩展 | 保留 `plan/` 位置，内部加类型子目录 | 迁移成本最低（存量不动） | 名实不符（plan 装报告/规格书）；子目录分类成本高；`default/` 兜底目录的混乱延续 | 否决 |
| B · 全局 docs/ 平铺不分域 | 所有文档进 `docs/` 根级 | 最简单 | 跨项目混杂（Nebflow×CZT×slideblocks…），单目录膨胀后不可导航；与 folders 域模型冲突 | 否决 |
| C · 规范做成 skill | `skills/doc-management/` | 可版本化、可复用、git 跟踪 | opt-in 注入覆盖不全；未声明 agent 完全看不到 | 否决（可作为 R 落地后的补充载体，非主通道） |
| D · 工具层强制 | 重建类 SaveWorkspaceItem 工具 | 强制力最高 | 刚删除该工具（逆用户决策）；DB 存储不利 agent 文件系统直访；开发维护成本 | 否决 |

---

## 4 · 落地步骤清单（批准后执行，预估一次会话完成）

**Step 1 · 规范本体**：创建 `~/.nebflow/docs/CONVENTIONS.md`，文案草案：

> ```markdown
> # Agent 文档管理规范（所有 agent 遵守）
> 产出文件前先问：会话结束后会被引用吗（派发/记忆/修订/基线）？
> 1. 活文档（spec/plan/design/roadmap/体系/checklist）→ ~/.nebflow/docs/<域>/<topic>.md
>    - 无日期前缀；版本日志维护在文件头；禁止 -v2/-v3 副本；修订原地更新
>    - 写入时在 <域>/INDEX.md 登记一行；配图存 <域>/assets/
> 2. 阶段文档（诊断/调研/评审/audit/验收快照）→ ~/.nebflow/docs/<域>/<YYYYMMDD>_<topic>.md
>    - 完成即冻结，不再修订；不必登记 INDEX
> 3. 临时产物（截图/调试脚本/日志/临时对比/一次性 Pop 展示）→ /tmp（系统自动清理）
> 4. 域 = 当前 folder 名，与 ~/.nebflow/folders/ 对齐；同项目宁集中勿分散
> 5. 派发纪律：派发含文档产出的任务时，prompt 必须写明目标路径 docs/<域>/，禁止指定 /tmp
> ```

**Step 2 · 8 处注入**：按 §2.6 表格逐文件修改（grep 验证：每个注入点文件含 `docs/<` 字样）。

**Step 3 · 存量迁移**：执行 §5 清单（脚本化 mv + git 化）。

**Step 4 · 引用修复**：
- `User.md`：FACT 2/8 中 `/tmp/*.md` 路径改为 `docs/Nebflow/` 新路径
- `tasks/284.json`（挂起中）：更新描述内路径
- `subagent-tasks` 历史记录不改（历史事实，不回写）

**Step 5 · INDEX 初始化**：为 `docs/Nebflow/` 生成 INDEX.md，登记全部活文档。

**Step 6 · 验收**（见下）。

### 验收条件

1. **端到端（硬性）**：迁移完成后，经 Nebula 或任一 Manager 派发一个含文档产出的真实小任务（如让 design-engineer 出一份组件规格书），断言：产出文件位于 `~/.nebflow/docs/<域>/` 且命名合规；`INDEX.md` 出现登记行；`/tmp` 未新增文档。**这是本方案的冒烟测试**——规范的价值在于下一个 agent 的行为，不在于文件搬完。
2. **注入完整性**：`grep -rl 'docs/<' ~/.nebflow/agents/{Nebula,Explorer,design-engineer}/system.md ~/.nebflow/teams/*/rules.md` 覆盖 §2.6 全部注入点（6/6）。
3. **迁移完整性**：§5 清单「迁」列逐项 `test -f <新路径>` 通过；`ls /tmp/*.md` 中活文档清零。
4. **记忆指针修复**：`grep -n '/tmp/[a-z0-9-]*\.md' ~/.nebflow/User.md` 零命中（worktree 类路径除外）。
5. **自我示范完整性**：本方案文档四张配图 `assets/*.svg` 全部可访问（Pop 渲染无裂图）。

---

## 5 · 存量迁移清单

### 5.1 /tmp 下 58 份 md（24 迁 / 34 留给系统清）

**活文档（13 份，无日期迁入）**：

| /tmp 现名 | 新路径（docs/Nebflow/） | 说明 |
|---|---|---|
| message-search-spec-v3.md + message-search-spec.md | `message-search-spec.md` | **两份合并**：v3.1 内容为主体，v2.1 沉淀进头部 changelog；被 tasks 284/276 引用（迁移后同步 Step 4 修复） |
| ecosystem-master-plan.md | `ecosystem-master-plan.md` | v2 收口整合版；User.md FACT 8 基准 |
| entity-ecosystem-design.md | `entity-ecosystem-design.md` | 被 FACT 8 引用 |
| ecosystem-decision-board.md | `ecosystem-decision-board.md` | 生态决策板（持续更新） |
| trinity-plan.md | `trinity-plan.md` | 产品战略叙事 |
| frontend-quality-roadmap.md | `frontend-quality-roadmap.md` | 质量路线图 |
| feat-master-overview.md | `feat-master-overview.md` | f1-f5 导览（用户自发产生的 INDEX 雏形） |
| uiux-quality-system.md | `uiux-quality-system.md` | **先与 `plan/Nebflow/20260817_uiux-quality-system.md` 去重合并** |
| unified-login-design.md | `unified-login-design.md` | **先与 `plan/Nebflow/20260813_…` 去重**（plan 版为派发冻结版，以此为准归并） |
| rebrand-param-plan.md | `rebrand-param-plan.md` | 被 FACT 2 引用；派发版 `plan/…20260817_rebrand-parameterization.md` 随 plan/ 迁入为阶段文档 |
| flow-timeout-redesign.md | `flow-timeout-redesign.md` | v2.1 待确认；**先与 `plan/Nebflow/20260814_…` 去重** |
| headless-design.md | `headless-design.md` | 状态「待 Manager 审」 |
| esbuild-design.md | `esbuild-design.md` | W3-i 设计（未实施完） |

**待裁定活/阶段（3 份）**：`agent-benchmark-plan.md`（参赛是否进行中）、`nebflow-paper-plan.md`（论文 v2）、`icp-beian-action-plan.md`（备案流程持续数周）——默认按活文档迁，执行时读头部状态复核。

**阶段文档（8 份，带日期迁入）**：`subagent-task-bug-report.md`（已修复，档案）、`msg-search-visual-review.md`（被 design-system 案例库引用）、`ecosystem-R1…R8.md`（8 份合一行：master-plan 的输入材料，`20260814_ecosystem-R*.md`）、`research-jpackage-desktop.md`、`qa-toolopt-acceptance.md`、`neblink-relay-design.md`、`tool-opt-edit.md`、`tool-opt-task-inventory.md`。

**留给系统清（34 份，不迁）**：诊断/审计类已闭环（`dual-down-recovery-diagnosis` `zai-vision-failure-analysis` `compaction-message-loss-analysis` `flow-bug-analysis` `neblink-speed-analysis` `teams-design-analysis` `login-flow-audit` `neblink-feature-audit` `deepseek-harness-flow-*`×2）、时点快照（`rename-availability*`×2 `neblink-status-report` `overnight-report`）、brief 族（`nb-brief-*`×3 `onight-backend-briefing` `backend-proposals-b3-n2`）、草稿/杂项（`user-head` `bill` `ri` `feat-f1…f5`×5 已被 overview 覆盖或过期）。**迁移脚本执行时逐文件核验头部状态标注，误判项现场改判。**

### 5.2 plan/ 20 份（全部迁入，目录废弃）

- `plan/Nebflow/` 14 份 → `docs/Nebflow/`，保留日期前缀（阶段文档语义）
- `plan/default/` 5 份（rust-migration 等，实为 Nebflow 域）→ `docs/Nebflow/`
- `plan/nebflow-project/` 1 份 → `docs/Nebflow/`（同域归并，INDEX 注明原路径）

### 5.3 配图

`/tmp` 下 49 份 svg / 208 png：仅迁移**被活文档引用**的（执行时逐活文档 grep 其引用并带走，如 `ecosystem-master-arch.svg` → `assets/`）；其余（截图/验证图）留 `/tmp`。

---

## 6 · 已裁定事项（2026-08-17 用户确认）

1. `plan/` 并入 `docs/` 后**废弃** ✓
2. `docs/` **纳入** `~/.nebflow` git 白名单仓库（`.gitignore` 加 `!/docs/`，活文档获得版本历史与备份）✓
3. `/tmp` worktree 代码区风险**另立任务**（worktree 选址如 `~/worktrees/`，涉 teams rules 修改与存量搬迁）✓

---

## 附录 A · 派发任务书（ready-to-dispatch）

**收件人**：nebflow-project（Manager）
**任务**：按 `~/.nebflow/docs/Nebflow/agent-doc-management.md` 执行落地（纯文件层操作，不改产品代码）：

1. 创建 `~/.nebflow/docs/CONVENTIONS.md`（文案照抄方案 §4 Step 1 草案）
2. 8 处注入（方案 §2.6 表格）：Nebula/Explorer/design-engineer 的 system.md + 5 个团队 rules.md 派发模板段 + research flow writer——每处加/改文档输出路径规则为 `docs/<域>/`
3. `.gitignore` 加 `!/docs/` 白名单行
4. 存量迁移（方案 §5）：/tmp 活文档 13+3 份与阶段文档 8 份迁入（逐文件核验头部状态，两份 message-search-spec 合并，三对跨区重复先去重）；plan/ 20 份全部迁入后删除空目录；活文档引用的配图随迁 assets/
5. 引用修复：User.md FACT 2/8 路径、tasks/284.json 描述内路径
6. INDEX.md 补全全部活文档登记行
7. **验收（全部满足才算完成）**：
   - 端到端：派发一个含文档产出的真实小任务（如 design-engineer 出规格书），断言产出落 `docs/<域>/`、INDEX 有登记、/tmp 无新增文档
   - `grep -rl 'docs/<'` 覆盖 6 个注入文件（6/6）
   - §5 迁移清单逐项 `test -f` 新路径通过，/tmp 活文档清零
   - `grep -n '/tmp/[a-z0-9-]*\.md' ~/.nebflow/User.md` 零命中
   - 完成后 git commit（`~/.nebflow` 仓库，一个 commit 含注入+迁移）

---

## 版本日志

- v1（2026-08-17 21:30）：初稿。
- v1.1（2026-08-17 21:35）：用户批准，三项裁定落定（plan/ 废弃 · git 纳入 · worktree 另立任务）；状态 draft → frozen · dispatch-ready；新增附录 A 派发任务书。
