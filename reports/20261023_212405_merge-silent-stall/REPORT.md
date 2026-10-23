# 合并路线静默空等修复批 · REPORT

- 时间：2026-10-23 21:2x（worktree 分支 `delegate/delegate-nebflow-9d658971`）
- 任务：作者质询「排队等合并却无人通知、空等」排查后的修复批——拒绝静默、搁浅无看护、生成物堵源三条腿。
- **中途设计裁定纠正（作者 2026-10-23 20:0x）**：合并不自动化——delegate 自己负责合并，平台只做排队/通知。原「事件驱动重试（树转洁代跑 merge）」腿按令撤销，实现与测试已按纠正版返工。

## 1. 排查结论（修复前形态）

- `DelegateAutoMerge`（引擎原生终态自动合并，#524）遇基工作区脏 ⇒ fail-closed 拒并：拒绝只落在终态尾注 + ChainStore 审计行，**无任何持久通知**——父会话回 Idle、root 不知情，分支搁浅（实锤三例：#775、identity-sync 批 c2b7661f、胶囊批 a65aba03）。
- 堵源：主仓工作树里 `evidence/20261022_diff-chip-headline` 等 8 张 PNG 被引擎测试覆写（tracked M，挂两天），每次合并评估都撞同一堵墙。
- 生成物与源码不分级：拒并理由只有一句「has uncommitted changes」，不带路径、不带清法——即使想清也不知道清什么。

## 2. 落地面（按作者纠正版三腿）

### 腿① 拒绝通知（新件 `MergeRefusalCare.scala` + `DelegateAutoMerge.postSettle`）

refused 终态后双通知（`MergeRefusalCare.EscalationPort`，BgInboxPort 同款 M2 注册器形态，core 不 import agent）：

- **delegate 本人 = 主通知**（rec.id 会话）：持久注入，可行动诊断——哪条路径脏（porcelain 清单）、什么原因、怎么清、**重试合并是 delegate 自己的责任（平台零代跑）**；
- **父会话 = 知会一行**（rec.parentSessionId）：一行 FYI，责任与行动都在 delegate 侧，无需父会话行动；
- 投递路由（SharedResources 装配面）：`BgNotifyDelivery.deliverToRootOrInbox` 同族——目标活 = 直投；死 = 持久收件箱补投（`RecoverPersistedQueues` 复活轮重放）；
- 幂等：correlationId = `auto-merge-refused-<delegateId>-<reason 摘要>`（父侧独立键族 `auto-merge-refused-parent-…`）——同脏重评不重复轰炸持久队列；脏面变化（reason 变）= 新诊断照发；
- 拒绝审计行增肥：refused 行携 `dirtyPaths`（cap 10 折 `+N more`）与 `artifactsRestored`；
- `Result.Refused` 尾注文案同步改写（清障与重试责任归 delegate）。

### 腿② 平台零代跑核对（申报面，零代码改动）

按令核对 D3 管家批已落面，结论：**排队/通知/出队/看护语义，无代跑合并**——

| 面 | 动作 | 代跑？ |
|---|---|---|
| `MergeQueue` | 合并轮提醒是**发给 delegate 自己执行的提示词**（`git -C <baseWs> merge --no-ff <branch>` 原文在其回合内跑）；文件头明文「引擎不代跑任何 git merge」 | 否 |
| `MergeWindowCare.care`（D3-⑤） | 合并轮司机死亡 ⇒ `onTerminal(keepForRetry=true)` 释放窗轮转 + 升级通知（root 活投/收件箱补投） | 否 |
| `careWaitingDock`（D3-④，TaskStuckWatcher） | 死停靠 ⇒ 出队/留队重试 + 台账翻 failed + 广播 + `escalateDockDeath` 升级 | 否 |
| `DelegateAutoMerge`（#524 本体） | **是**——终态判词 completed 后引擎亲自 `git merge --no-ff`。此面是本批拒绝通知的宿主；是否按「合并不自动化」原则进一步退役（降为纯注记/纯通知），**交作者裁定，本批不自行扩动** | **是（申报）** |

本批撤销的实现：初版曾落的「被拒分支登记 + 转洁事件触发代跑重试（retrySweep）」整段移除（含登记表）——平台不代跑。

### 腿③ 生成物自动还原（`DelegateAutoMerge.restoreArtifacts`，保留）

- 评估撞脏树时：白名单 `evidence/`、`reports/` 下「已跟踪文件的未提交修改」（porcelain X/Y 列 = M；`??` untracked、D、R 一概不碰）自动还原 HEAD；
- `git checkout HEAD -- <path>`（HEAD 形：staged 修改连索引一并复位，比裸 `checkout --` 严格；对 unstaged 场效果等同）；
- **还原前先落审计行**（action=`artifact-restore`，每路径带还原前 `--stat HEAD` diff 摘要，cap 300 字符），后动手；单条失败只 WARN（树仍脏 ⇒ 走既有 refused 面），绝不阻断评估链；
- 源码路径绝不碰（白名单严格面：目录本体 + `目录/` 前缀）；
- 开关 `delegateAutoMerge.restoreArtifacts`（nebflow.json 热读，缺省 true；false = 一键回「脏即拒」原语义）。

**腿③属性申报（作者留的判断题）**：还原是**共享区卫生**——解除的是 delegate 自己重试合并时也绕不开的阻塞，不替 delegate 跑任何一步（清障动作 `git checkout` 之外的全部——决策、重试、合并——仍在 delegate）。按「保留」落地；若作者改判「属代劳」，替代形态 = 通知里建议 delegate 自清（诊断文本已含逐路径清单，改文案即可，别无联动面）。

## 3. 验收（DelegateAutoMergeSpec ⑨–⑫，真仓实测）

| 验收项 | 测试 | 断言要点 |
|---|---|---|
| 拒绝 → 通知到达 delegate | ⑨ | 双通知到达（delegate 可行动诊断 + 父会话一行）；correlationId 键族正确；**同脏重评 correlationId 稳定**（持久队列挡重）；负载含脏路径/清法/责任归属，且不含任何「引擎代跑重试」承诺 |
| delegate 清障后自行重试成功（平台零代跑） | ⑩ | 清障后、重评前：main 不动、无 merged/already-merged 行（无隐藏代跑）；delegate 自己的重评拍才落地；全史恰一次 merge |
| 生成物覆写 → 自动还原 + 审计 | ⑪ | evidence/+reports/ 覆写 ⇒ 内容回 HEAD、树转洁合并落地；`artifact-restore` 行在案（路径 + diff 摘要逐条） |
| （开关）restoreArtifacts=false | ⑪b | 不还原、照旧拒、零还原行 |
| 源码脏文件 → 不还原 | ⑫ | `src/Main.scala` 半成品内容原样保留；refused + dirtyPaths 记录在行；零还原行 |

既有 ①–⑧（零冲突合并/冲突挂起/幂等/开关/拒并/目标覆盖/FIFO 单飞/生命周期接线）回归全绿 ⇒ 既有语义零漂移。

（结果行：见 §5）

## 4. 边界与红线自查

- 零 timer：全批无新增定时器/轮询；通知与还原都挂在既有终态评估拍上。
- 最小改动：`DelegateAutoMerge.scala`（尾注/评估/还原/结算 + Config 一开关）、新件 `MergeRefusalCare.scala`、`SharedResources.scala` 装配一段、spec ⑨–⑫；两腿调用点（DelegateTerminal / ExternalSessionRunner）零改动——通知在钩子内部结算。
- 未重启引擎；不动 MergeQueue/MergeWindowCare/careWaitingDock。
- untracked 脏文件不还原（严格按「已跟踪文件的未提交修改」）——主仓现存 `?? evidence/test/`、`?? reports/20261023_153924_delegate-fullflow-audit.md` 仍会拒并，但拒绝通知会点名并给清法（提交/清理/加 ignore 由 delegate/人定）。
- 兜底：EscalationPort 未装配（无 SharedResources harness）= 响亮 WARN 不静默——本批消灭的正是「无人出声」。

## 5. 结果

- 编译：main + test 全绿。
- `testOnly nebflow.core.delegate.DelegateAutoMergeSpec`：**13/13 PASS**（2026-10-23 22:28）——⑨⑩⑪⑪b⑫ 五条新验收 + 既有 ①–⑧ 回归零漂移。
- 回归基线：`testOnly nebflow.core.delegate.*` 全包 109 例（含 DelegateTerminalSpec 14 例 / ChainStoreSpec 3 例）随本批最终态复跑（见提交说明）。

### 排障留档（本批最刁的一处实现 bug）

`git()` 助手对整段输出 `.trim()`——`git status --porcelain` 首行的**前导空格是 X/Y 状态位**（unstaged 形 ` M path` 的 X 列是空格）。trim 吃掉全段首字符后，` M evidence/...` 变 `M evidence/...`，列解析 `drop(3)` 恰好再吞路径首字符 → `vidence/pic.png` 不命中白名单 → 还原漏文件 → 树仍脏 → 拒并。untracked 形（`??` 无前导空格）不受影响（故 ⑨⑩ 绿、⑪⑫ 红）。修复 = 新增保内容解析面 `gitRaw`（仅剥行尾）供 porcelain 列对齐使用；既有 `git()` 语义不动（其调用方只做非空/摘要判断）。教训：**porcelain 是列对齐协议，任何整体 trim 都是协议破坏**——判红锚已由 ⑫（dirtyPaths 必须逐字记录 `src/Main.scala`）锁定。

## 6. 产物

- `src/main/scala/nebflow/core/delegate/MergeRefusalCare.scala`（新）
- `src/main/scala/nebflow/core/delegate/DelegateAutoMerge.scala`（改）
- `src/main/scala/nebflow/agent/SharedResources.scala`（装配一段）
- `src/test/scala/nebflow/core/delegate/DelegateAutoMergeSpec.scala`（⑨–⑫ + 文案）
- 本报告 `reports/20261023_212405_merge-silent-stall/REPORT.md`
