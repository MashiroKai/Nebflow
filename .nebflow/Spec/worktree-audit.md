# Worktree / 分支 / 落地状态 / 进程残留 全量审计报告

- 审计时间：2026-09-05 11:30–12:10（审计节点 n-f3e66320「审计-worktree全量与落地状态」）
- 审计方式：**只读**——所有被审计 worktree 仅执行 `git --no-optional-locks status/log/diff` 级命令；未 add/checkout/stash/clean 任何 worktree；未 kill 任何进程；未 push/merge/删除任何分支或 worktree
- 质询背景：作者 2026-09-05 11:03「.nebflow 里 worktree 非常多，是不是没人合并导致很多任务做了但没应用」
- 实测基数：主仓 `git worktree list` = **33 项**（main + 仓外 6 + `.nebflow/worktrees/` 下 26；分发器口径 32，实测多 1：devport-governance/ruling-audit 等当日新建）。本地分支共 47 个 ref
- **终态刷新（2026-09-05 20:4x 清扫批）**：作者指令 20:27「worktree 全量清扫——已合并的一律删除」。本文件**第七节为清扫后终态**（三清单 + L1–L5 下落核验 + 宿主命令全集，commit-ready）；第一~六节保留为 11:30 审计快照（历史证据，多项已被晚间清理消化，以第七节现场复核为准）

---

## 一、作者质询直接回答：「做了但没应用」有多少？

**答：是。共 9 支存在「做了但未应用到 main」的真实产物，其中 5 支是今日（09-05）已 completed 节点的整支成果滞留在 worktree 未提交——这才是 worktree 堆积的主因。** 另有 8 支已合并 worktree 未清（纯残留）、2 个空壳 worktree。

### 1.1 队列外漏网之鱼（质询核心，逐支列证）

「今日 13 支落地队列」（作者口径 A-M）外的未应用产物：

| # | 分支 / worktree | 节点（flow-map） | 状态 | 未应用内容（实证） | 任务线索 |
|---|---|---|---|---|---|
| L1 | `bgtask-count-fix`（worktree 在，分支 0 commit） | 修复-后台任务计数列表分叉（completed 09-05 02:16） | **整支未应用** | 6 文件 **+150/−90 未提交**；内容级验证：main 仍有旧启发式 `bgTaskRootFor`（4 处），`BgTaskRegistry.scala` 无 rootSessionId 分组 | 修 delegate/subtask 后台任务计数落错桶、badge 失效；改动含 main.js/AgentActor/BashTool/BgTaskRegistry/WebSocketRoutes |
| L2 | `checkjs-gate-fix`（分支 0 commit） | 修复-checkJs门禁与QC跟进批（completed） | **整支未应用** | 18 文件 **+159/−56 未提交**；main 的 package.json 仍无 typescript devDep、check-js-types.mjs 仍有 `npx -y` 回退（沙箱 EPERM 假 PASS 根因修复不在 main） | checkJs 门禁本地 tsc 钉死 + npm install 提示 |
| L3 | `plan-mode-retire`（分支 0 commit，01:49 ff 到 533a2a0a） | 实施-planmode整体退役·重派2（completed；首派 failed） | **整支未应用** | 15 文件 **+14/−1309 未提交**；main 仍存在 `PlanWaitingBufferSpec.scala`（209 行，该支删除对象） | planmode 整体退役（css/locales/input.js + spec 清除） |
| L4 | `plugin-protocol`（分支 0 commit，停在基底 6dc4b398） | 收尾-plugin协议验证与交付·续作（completed） | **整支未应用** | 10 文件 **+683/−116 未提交**（PluginRegistry.scala +579、NodePluginChain、MCP transports 等）；main 历史无任何「插件协议」相关提交 | 插件协议续作：注册表/信任门加强 |
| L5 | `workspace-picker`（分支 0 commit，今日 10:08 从 main 新建） | 实施-工作区选择器原生对话框（completed） | **整支未应用** | 10 文件 **+434/−5 未提交**（`ws-pick-target` 选择工作区卡 + Route C 应用内浏览器 CSS/JS/i18n）；main 全历史无「工作区选择器」提交 | ProjectCreate 选工作区交互 |
| L6 | `friend-chain-ui`（分支已并 main ✅，worktree 有未提交增量） | 实施-好友链补全与界面优化（completed） | **部分未应用**：本体经 59aba2ff 已落 main；worktree 另有 13 文件 **+243/−447 未提交**（相对自身 HEAD），含删除自产 spec `tests/friend-chain-ui.spec.mjs`（−365） | 合并后迭代未提交（sidebar.css/plugins.js/AgentLibrary 等）；**需作者裁定：这批增量是继续打磨待收，还是废弃迭代** |

> L1–L5 五支共同模式：**节点在 worktree 里完成了全部工作并自报 completed，但从未 commit 到分支**——flow-map 记「完成」、main 无痕迹。这是「任务做了但没应用」最直接的证据链。

### 1.2 更早的未应用产物（今日队列外、非 09-05）

| # | 分支 | 内容 | 最后活动 | 判定 |
|---|---|---|---|---|
| L7 | `feat/ci-desktop-verify`（worktree `~/Claude code/.nb-worktrees/nb-437-ci`） | **领先 main 6 commits**，落后 351 | 2026-08-27（9 天前） | 废弃嫌疑但有真实产物 → 需作者裁定（桌测 CI 链） |
| L8 | `pr-41-search-jump`（无 worktree） | 领先 1 commit e843c998 | 2026-09-02 | 需作者裁定（疑被后续批次取代） |
| L9 | `pr-44-send-btn`（无 worktree） | 领先 1 commit 2f95bea6 | 2026-09-03 | 需作者裁定 |

`deadchain-archive-fix`（588eee83）亦领先 1 commit，但节点已裁定「零改码、保留产物不并 main」——已核实其内容纯为调查 harness（+245：fixture 快照 + 223 行 spec + README），**不算漏网**，归「有意不并」。

### 1.3 今日 13 支落地队列（作者口径 A-M）重建映射

**「A-M 精确对应待作者确认」**。按证据重建＝8 已完 + 5 在飞恰 13 支：

| 组 | 支 | 证据 |
|---|---|---|
| 已完 8 | turn-badge-fix(29fc3a11)、taskpanel-audit(67e69bf1†)、nebula-toolface(21bc2e74)、skill2plugins(b7be1819)、dispatcher-to-nebula(57bd7606)、docs2spec(adb0e28a)、micorb-wave(66b60e02)、deadchain-archive-fix(588eee83·零改码不并) | 各分支领先 main 1–4 commit、worktree 干净（†taskpanel-audit 例外：节点仍 running，另有 10 文件未提交增量） |
| 在飞 5 | settings-cleanup、legacy-ui-retire、username-unify、plugins-ux、sidebar-restructure | flow-map：前二 running、后三 wiring；worktree 有未提交增量或为空壳 |

**注意：L1–L5 五支漏网不在 13 支队列内**——它们的节点早已 completed，若无本审计即永久滞留。

### 1.4 已合并未清理残留（清单②）

worktree 目录 + 分支 ref 双清点，均 `git branch --merged main` 实证（ahead=0）：

| worktree 路径 | 分支 | behind | 脏文件 | 备注 |
|---|---|---|---|---|
| `/private/tmp/nb-node-agentfile` | feat/node-agentfile | 213 | 3 个 untracked（http.log 等 QA 产物） | 分支已并 |
| `~/Claude code/.nb-worktrees/nb-window-shell` | feat/window-shell | 342 | 0 | 分支已并 |
| `.nebflow/worktrees/friend-chain-ui` | friend-chain-ui | 16 | **27**（未提交增量→见 L6） | 分支已并 |
| `.nebflow/worktrees/phase2c-agent-convergence` | phase2c-agent-convergence | 23 | 0 | 分支已并（fc44a3ea） |
| `.nebflow/worktrees/plugin-panel-redesign` | plugin-panel-redesign | 9 | 0 | 分支已并（cc506071） |
| `.nebflow/worktrees/plugin-protocol` | plugin-protocol | 17 | **13**（未提交→见 L4） | 分支 0 自有 commit |
| `.nebflow/worktrees/proc-residue-governance` | proc-residue-governance | 1 | 0 | 分支已并（533a2a0a 本体） |
| `.nebflow/worktrees/qc-2d-refactor` | qc-2d-refactor | 18 | 0 | 分支已并（6dc4b398） |
| `.nebflow/worktrees/seed-defaults-converge` | seed-defaults-converge | 16 | 0 | 分支已并（30706486） |

仅剩分支 ref、无 worktree 的已并分支：`feat/task-progress-2`、`feat/turn-collapse-delegate`、`feat/turn-collapse-delegate2`、`fix/paste-attachment-loss`、`red-baseline`、`phase2b-plugins`（其 worktree 目录已删，但曾有进程残留→见进程表 24468/27829）。

另：`.nebflow/` 下 26 个 `worktrees/<名>` 软链逐一核对，**无悬空链**（26/26 目标目录存在）。

---

## 二、审计主表（33 worktree 逐行）

字段：AB=`ahead/behind vs main`（`git rev-list --left-right --count main...<branch>`）；脏=未提交条目数；判定：①commit-ready ②已并残留 ③废弃无主 ④在飞

| 路径 | 分支 | HEAD | AB | 脏 | 最后活动 | 批次证据 | 判定 |
|---|---|---|---|---|---|---|---|
| 主仓 `Nebflow` | main | 533a2a0a | 0/0 | 0 | 09-05 01:10 | — | 基准 |
| `/private/tmp/nb-node-agentfile` | feat/node-agentfile | 0d7b1af6 | 0/213 | 3 | 09-01 | agentfile 节点已并 | ② |
| `~/Claude code/.nb-worktrees/nb-437-ci` | feat/ci-desktop-verify | b7c26ec1 | **6**/351 | 0 | 08-27 | nb-437 桌测 CI | ③（产物未并→L7，需作者裁定） |
| `~/Claude code/.nb-worktrees/nb-window-shell` | feat/window-shell | 1afe312e | 0/342 | 0 | 08-27 | window-shell 已并 | ② |
| `Nebflow-archive-mesh-sync` | archive/mesh-sync-with-session-sync | 87357901 | **408**/2177 | 0 | **06-26**（71 天） | 旧架构存档支 | ③ 存档（建议归档删支或明确保留） |
| `Nebflow-pekko-only` | refactor/Pekko-Only | f8c33b1c | **651**/2177 | 0 | **07-12**（55 天） | Pekko-only 重构（未并） | ③（55 天不动，需作者裁定弃/续） |
| `Nebflow-refactor` | refactor/actor-io-layered | 6d20681d | **627**/2177 | 0 | **07-12**（55 天） | actor-io 分层重构（未并） | ③（同上） |
| `.nebflow/worktrees/bgtask-count-fix` | bgtask-count-fix | 533a2a0a | 0/0 | **7** | 09-05 02:16 | completed 节点，未提交 | **漏网 L1**（先收产物再清） |
| `.nebflow/worktrees/checkjs-gate-fix` | checkjs-gate-fix | 533a2a0a | 0/0 | **18** | 09-05 02:12 | completed 节点，未提交 | **漏网 L2** |
| `.nebflow/worktrees/deadchain-archive-fix` | deadchain-archive-fix | 588eee83 | 1/17 | 0 | 09-05 00:04 | 调查-死链滞留主图（零改码裁定） | ①变体：有意不并，收尾后清 |
| `.nebflow/worktrees/devport-governance` | devport-governance | 533a2a0a | 0/0 | 0 | 09-05 09:56（软链 mtime） | flow-map 无对应节点记录 | ③ 空壳（无 commit 无改动，疑占位/夭折） |
| `.nebflow/worktrees/dispatcher-to-nebula` | dispatcher-to-nebula | 57bd7606 | 0/1 | 0 | 09-05 10:06 | feat(dispatcher) 投递 Nebula | ①（队列内） |
| `.nebflow/worktrees/docs2spec` | docs2spec | adb0e28a | 0/4 | 0 | 09-05 09:29 | docs→Spec 迁移 365 文件 | ①（队列内） |
| `.nebflow/worktrees/friend-chain-ui` | friend-chain-ui | 2d30040a | 0/16 | **27** | 09-05 00:39 | 已并 59aba2ff + 未提交增量 | ②+**漏网 L6** |
| `.nebflow/worktrees/legacy-ui-retire` | legacy-ui-retire | 533a2a0a | 0/0 | 16 | 09-05 11:12（running） | 退役-旧面板入口 | ④ |
| `.nebflow/worktrees/micorb-wave` | micorb-wave | 66b60e02 | 0/1 | 0 | 09-05 08:27 | v8.4.2 波纹调参 | ①（队列内） |
| `.nebflow/worktrees/nebula-toolface` | nebula-toolface | 21bc2e74 | 0/1 | 0 | 09-05 09:24 | Nebula 工具面改版 | ①（队列内） |
| `.nebflow/worktrees/phase2c-agent-convergence` | phase2c-agent-convergence | c3afe166 | 0/23 | 0 | 09-04 20:26 | 已并 fc44a3ea | ② |
| `.nebflow/worktrees/plan-mode-retire` | plan-mode-retire | 533a2a0a | 0/0 | **15** | 09-05 01:49 | completed·重派2，未提交 | **漏网 L3** |
| `.nebflow/worktrees/plugin-panel-redesign` | plugin-panel-redesign | a89a1e7e | 0/9 | 0 | 09-05 00:53 | 已并 cc506071 | ② |
| `.nebflow/worktrees/plugin-protocol` | plugin-protocol | 6dc4b398 | 0/17 | **13** | 09-04 23:50 | completed 续作，未提交 | **漏网 L4** |
| `.nebflow/worktrees/plugins-ux` | plugins-ux | 533a2a0a | 0/0 | 0 | 09-05 11:17（wiring） | 实施节点 | ④ |
| `.nebflow/worktrees/proc-residue-governance` | proc-residue-governance | 2160cb1e | 0/1 | 0 | 09-05 01:10 | 已并=main HEAD 本体 | ② |
| `.nebflow/worktrees/qc-2d-refactor` | qc-2d-refactor | 83d4b8ea | 0/18 | 0 | 09-04 22:55 | 已并 6dc4b398 | ② |
| `.nebflow/worktrees/ruling-audit` | ruling-audit | 533a2a0a | 0/0 | 0 | 09-05 09:56 | flow-map 无对应节点记录 | ③ 空壳 |
| `.nebflow/worktrees/seed-defaults-converge` | seed-defaults-converge | 82af52e5 | 0/16 | 0 | 09-05 00:48 | 已并 30706486 | ② |
| `.nebflow/worktrees/settings-cleanup` | settings-cleanup | 533a2a0a | 0/0 | 15 | 09-05 11:03（running） | 实施批五项 | ④ |
| `.nebflow/worktrees/sidebar-restructure` | sidebar-restructure | 533a2a0a | 0/0 | 0 | 09-05 11:17（wiring） | 重构节点 | ④ |
| `.nebflow/worktrees/skill2plugins` | skill2plugins | b7be1819 | 0/1 | 0 | 09-05 09:23 | skills→plugins 标准源 | ①（队列内） |
| `.nebflow/worktrees/taskpanel-audit` | taskpanel-audit | 67e69bf1 | 0/1 | **10** | 09-05 09:44（running） | 已有 1 commit+增量在写 | ④（节点收口后转①） |
| `.nebflow/worktrees/turn-badge-fix` | turn-badge-fix | 29fc3a11 | 0/1 | 1(仅 node_modules) | 09-05 09:46 | 一 turn 一 badge | ①（队列内） |
| `.nebflow/worktrees/username-unify` | username-unify | 533a2a0a | 0/0 | 0 | 09-05 11:09（wiring） | Username 统一 | ④ |
| `.nebflow/worktrees/workspace-picker` | workspace-picker | 533a2a0a | 0/0 | **16** | 09-05 10:08 | completed 节点，未提交 | **漏网 L5** |

**汇总**：④在飞 6 ｜ ①commit-ready 7（含 deadchain 变体；taskpanel-audit 待节点收口后计入）｜ ②已并残留 9（含 L4/L6 两个带未提交增量）｜ ③废弃/空壳/存档 7 ｜ **漏网未应用 5 整支 + 2 部分（L1–L6）+ 更早 3（L7–L9）**

### 分支-only ref（无 worktree，共 14 个非主线 ref）

| 分支 | AB | 最后活动 | 判定 |
|---|---|---|---|
| feat/task-progress-2、feat/turn-collapse-delegate、feat/turn-collapse-delegate2、fix/paste-attachment-loss、red-baseline、phase2b-plugins | 0/N（全并） | 08-25～09-04 | 已并 ref 残留 → 可 `branch -d` |
| pr-41-search-jump / pr-44-send-btn | **1** ahead | 09-02 / 09-03 | 未应用（L8/L9）需作者裁定 |
| feat/ci-desktop-verify 见上（有 worktree） | **6** ahead | 08-27 | L7 |
| archive/nebflow-v1（19 ahead）、archive/nebflow-v2（6 ahead）、archive/rust-main（123 ahead）、rust-standalone（137 ahead） | — | 4～8 月 | 历史存档/Rust 线，建议保留不动 |
| beta（24 ahead）、release（1 ahead） | — | 08-30 / 06-05 | 发版线，保留 |

---

## 三、处置建议表 + 清理命令清单（**全部只列不执行**）

### 3.1 漏网支（先抢救产物，再清理）

> 抢救模式建议：在对应 worktree 内 `git add -A && git commit`（由落地流程执行，本审计不执行），或由原节点重派收尾。**未 commit 前禁止 `worktree remove`——会连产物一起删**（worktree remove 对脏目录需 --force，届时不可恢复）。

| 项 | 建议 | 待产物落地后清理命令 |
|---|---|---|
| L1 bgtask-count-fix | **立即收**（completed 成果） | `git worktree remove ".nebflow/worktrees/bgtask-count-fix"` → `git branch -d bgtask-count-fix` |
| L2 checkjs-gate-fix | **立即收** | `git worktree remove ".nebflow/worktrees/checkjs-gate-fix"` → `git branch -d checkjs-gate-fix` |
| L3 plan-mode-retire | **立即收** | `git worktree remove ".nebflow/worktrees/plan-mode-retire"` → `git branch -d plan-mode-retire` |
| L4 plugin-protocol | **立即收** | `git worktree remove ".nebflow/worktrees/plugin-protocol"` → `git branch -d plugin-protocol` |
| L5 workspace-picker | **立即收** | `git worktree remove ".nebflow/worktrees/workspace-picker"` → `git branch -d workspace-picker` |
| L6 friend-chain-ui 增量 | 需作者裁定（收增量 or 弃） | 裁定后 `git worktree remove ".nebflow/worktrees/friend-chain-ui"` → `git branch -d friend-chain-ui` |

### 3.2 今日 13 支队列

| 项 | 建议 |
|---|---|
| turn-badge-fix / nebula-toolface / skill2plugins / dispatcher-to-nebula / docs2spec / micorb-wave | 保留到落地合并后清：`git worktree remove` + `git branch -d` |
| deadchain-archive-fix | 作者已裁定不并：收尾确认后 `git worktree remove ".nebflow/worktrees/deadchain-archive-fix"`（分支可留可删，内容为调查 harness，建议留 ref 一段时间） |
| taskpanel-audit | 节点 running，**等收口**（现有 1 commit + 10 脏文件）→ 落地后清 |
| settings-cleanup / legacy-ui-retire / username-unify / plugins-ux / sidebar-restructure | 在飞，**落地合并后清** |

### 3.3 残留/废弃（可立即清项）

```bash
# ②已并残留（实证 ahead=0，安全）
git worktree remove ".nebflow/worktrees/phase2c-agent-convergence"
git worktree remove ".nebflow/worktrees/plugin-panel-redesign"
git worktree remove ".nebflow/worktrees/proc-residue-governance"
git worktree remove ".nebflow/worktrees/qc-2d-refactor"
git worktree remove ".nebflow/worktrees/seed-defaults-converge"
git worktree remove "$HOME/Claude code/.nb-worktrees/nb-window-shell"
git branch -d phase2c-agent-convergence plugin-panel-redesign proc-residue-governance qc-2d-refactor seed-defaults-converge feat/window-shell feat/node-agentfile
# 已并 ref-only 分支
git branch -d feat/task-progress-2 feat/turn-collapse-delegate feat/turn-collapse-delegate2 fix/paste-attachment-loss red-baseline phase2b-plugins
git worktree remove /private/tmp/nb-node-agentfile   # 内含 3 个 untracked QA 产物，可先查看后删

# ③空壳（无 commit 无改动，flow-map 无节点）
git worktree remove ".nebflow/worktrees/devport-governance" && git branch -d devport-governance
git worktree remove ".nebflow/worktrees/ruling-audit" && git branch -d ruling-audit

# 需作者裁定（有产物/长期不动，本审计不建议自动清）
# feat/ci-desktop-verify（6 commits 未并，L7）
# refactor/Pekko-Only、refactor/actor-io-layered（55 天，651/627 commits 未并——弃则 -D，续则重派）
# archive/mesh-sync-with-session-sync（71 天）
# pr-41-search-jump、pr-44-send-btn（各 1 commit 未并）
# archive/nebflow-v1|v2、archive/rust-main、rust-standalone、beta、release —— 建议永久保留
```

---

## 四、进程残留扫描（2026-09-05 ≈11:35 实测；**只列 kill，不执行**）

全量 `lsof -nP -iTCP -sTCP:LISTEN` + 逐 PID `lsof -a -p <pid> -d cwd -Fn` 验身。宿主=本会话环境表 **PID 11270**（:8080）——绝对豁免。

| PID | 进程 | cwd | 端口 | 判定 | kill 命令（只列） |
|---|---|---|---|---|---|
| 11270 | java（宿主 GatewayMain） | 主仓 | **8080** | **宿主豁免** | —（绝禁） |
| 11587 | Python | `.nebflow/projects/CZT` | 8765 | 宿主 11270 直接子进程（pgrep -P 验证）→宿主域豁免 | — |
| 10896 | java（sbt-launch.jar，sbt 会话） | **主仓根** | 无监听 | **疑似残留**：主仓内遗留 sbt JVM（非宿主子进程）；需作者确认是否手动所起 | `kill 10896` |
| 13366 | java | `/private/tmp/nb-a2a-frontend` | **8096** | 孤儿残留：A2A 前端 QA 实例（任务早已收尾） | `lsof -nP -tiTCP:8096 -sTCP:LISTEN \| xargs kill` |
| 24468 | java | `.nebflow/worktrees/phase2b-plugins`（**目录已删**，路径悬空） | 无 | **确定孤儿**（cwd 指向已删 worktree） | `kill 24468` |
| 27829 | java | 同上（已删目录） | 无 | **确定孤儿** | `kill 27829` |
| 83206 | Python | `.nebflow/worktrees/friend-chain-ui` | 8976 | 残留：friend-chain 节点已 completed，其 QA 进程仍在 | `kill 83206` |
| 44737 / 44746 / 44826 / 44834 / 44843 / 44852 | node ×6 | `/private/tmp/nb-dev-auth`（**slideblocks** 仓 worktree feat/dev-auth-bypass-seed） | 44746 听 54127/54137 | slideblocks 域 QA 残留（非 Nebflow 主仓域；归 slideblocks 收口） | `kill 44737 44746 44826 44834 44843 44852` |
| 47568 | node | `/private/tmp/nb-visual-preview`（slideblocks worktree） | 4323 | slideblocks 视觉验收预览残留 | `kill 47568` |
| 48912 | node | `/private/tmp/nb-pdf-verify` | 8245 | pdf 验证残留（归属项目不明） | `kill 48912`（需作者裁定） |
| 11629 | node | `.nebflow/projects/nebflow-website` | 3000 | 官网前端预览（AGENTS 惯例 localhost:3000）→项目域，建议保留 | — |
| 11656 | node | `.nebflow/projects/slideblocks` | 4321 | slideblocks astro dev →项目域 | `kill 11656`（需裁定） |
| 39452 | node | `.nebflow/projects/html-deck-studio/ai-fpga-deck-src` | 3030 | html-deck 预览 →项目域 | `kill 39452`（需裁定） |
| 11637 | Python | `.nebflow/projects/voice-recognition-test/zhipu-demo` | 7861 | voice-recognition 项目域服务 | 需裁定（归该 team） |
| 33179 | node | （cwd 不可读） | 8187 | 身份不明 →需作者裁定 | 先 `lsof -p 33179` 验身后定 |

复核项：分发器所列 settings-cleanup sbt PID 25010/25021 **已死**（当前不存在）；端口 8091 **无监听**（全端口复扫确认，known sandbox-2a 残留确已消失）。

---

## 五、其他项目仓（范围二）

| 仓 | 当前分支（=该项目域 main 口径） | worktree | 未提交 | 最后活动 | 判定 |
|---|---|---|---|---|---|
| `~/.nebflow/projects/slideblocks` | fix/deck-workbench（默认分支探测为 codex/live-block-gallery，无 origin HEAD） | **5**（含 /private/tmp/nb-dev-auth、nb-dev-auth-baseline、nb-visual-preview 三个仓外） | 1（?? project.json） | 09-05 | 在飞项目域；仓外 3 worktree + 6 进程残留见第四节 |
| `~/.nebflow/projects/neblink-server` | main | 2 | 3 | 09-02 | 正常 |
| `~/.nebflow/projects/CZT` | interactive-sim（**领先 main 23 commits**） | 1 | 4 | 09-05 | 在飞功能支（CZT team 域，正常） |
| `~/.nebflow/projects/phd-notebook` | main | 1 | 1 | 09-05 | 正常 |
| `~/.nebflow/projects/html-deck-studio` | main | 1 | **23**（roadshow/slides.md 修改 + 大量 .slideblocks probe 脚本 untracked） | 09-04 | 项目域工作产物，建议该 team 收口时归一 |
| `~/Claude code/Reminder`（ReminderIsland 团队工作区） | feat/priority-all-views（**领先 main 9**） | 1 | 1 | 09-03 | 在飞功能支（团队域） |
| `~/Claude code/ReminderIsland` | main | 1 | **36** | **2025-12-12** | **废弃嫌疑**：last commit 去年 12 月，团队实际用 `Reminder` 仓 → 需作者裁定是否归档 |
| `~/Claude code/detector-proposal` | main | 1 | 0 | 09-05 | 正常 |
| `~/Claude code/Nebflow-backup-20260531-115641` | main | 1 | 2 | 05-29 | 备份仓，保留 |
| `~/Claude code/Reports` | main | 1 | 1 | 08-20 | 正常 |

---

## 六、结论与机制建议

1. **质询答案成立**：worktree 堆积 = ②已并未清（9 支）+ **漏网未应用（L1–L5 整支 + L6/L7–L9 部分）**两类叠加；其中 L1–L5 是「节点 completed 但从未 commit」的新故障模式——落地闸门只看 flow-map 状态、不校验「分支领先 main ≥1 commit 或无脏文件」，建议在 merge 前置检查加这条硬闸（脏 worktree 不允许销毁，必须先 commit 或显式弃置）。
2. L1–L5 五支产物本身质量未知（未经 QC/合并审查），**建议按正常落地流程补收**（编译+测试+review），不要直接快并。
3. 进程侧确认治理见效度不一：8091 老残留已死，但 nb-a2a-frontend(8096)、phase2b 悬空孤儿×2、friend-chain QA(8976) 仍在——验收后 finally-kill 未覆盖到「宿主重启后遗留」场景，proc-residue-governance 的 trap 方案只护新进程。
4. 全部清理动作待作者/落地流程执行，本审计零写入零信号。

*报告由只读审计生成；证据命令均可复现（git rev-list / --no-optional-locks status / lsof cwd）。*

---

## 七、2026-09-05 20:4x 清扫批——worktree 全量清扫终态（commit-ready）

- 触发：作者指令 2026-09-05 20:27「worktree 全量清扫——已合并的一律删除」；执行节点 = worktree-cleanup（`.nebflow/worktrees/worktree-cleanup`）
- 现场复核：2026-09-05 20:4x；全程 `git --no-optional-locks` 只读；唯一写入 = 本文档在工作区内落盘 + 单次 EPERM 取证（`touch 主仓 .git` → `Operation not permitted`）。**本节点未删除任何 worktree/分支，未执行 7.6 任何命令——删除一律走宿主命令清单**
- 基数刷新（vs 11:30 快照）：`git worktree list` = **19 项**（main + 仓外 6 + `.nebflow/worktrees/` 下 12，含本节点自身）；本地分支 ref 33 个；11:30 的 26 软链形态已变更为**实体目录**（`ls -la .nebflow/worktrees/` 实测无软链/残链）；ruling-audit 空壳 worktree 与分支 ref 已消失；phase2b-plugins 分支 ref 已消失；11:30 所列 L1–L5、今日 13 支队列（已完 8 支）、已并残留 5 支（phase2c-agent-convergence / plugin-panel-redesign / proc-residue-governance / qc-2d-refactor / seed-defaults-converge）的 worktree 与分支 ref 均已不在现场
- main 基准：a8328477；全部祖先关系经 `git merge-base --is-ancestor <branch> main` 实测，ahead 经 `git rev-list --count main..<branch>` 实测

### 7.1 L1–L5 下落核验（防「抢救」变「误删」）——全部已落地 ✅

| 项 | 核验点 | 结果 | 证据（main @a8328477） |
|---|---|---|---|
| L1 bgtask-count-fix | BgTaskRegistry rootSessionId 分组；旧启发式 bgTaskRootFor 清除 | ✅ 已落地 | `BgTaskRegistry.scala:26,36,38,55,61` rootSessionId 字段 + 按根会话 groupBy；`bgTaskRootFor` 全库仅存「已删除」说明性注释（web/js/main.js:1809,2730、AgentActor.scala:644、BashTool.scala:740、BgTaskRegistrySpec.scala:14），无函数定义 |
| L2 checkjs-gate-fix | package.json typescript devDep；check-js-types.mjs 无 npx -y 回退 | ✅ 已落地 | devDependencies.typescript = 5.5.4；scripts/check-js-types.mjs:19 注明「The old \`npx -y -p typescript@5.5\` fallback was removed」 |
| L3 plan-mode-retire | PlanWaitingBufferSpec.scala 清除 | ✅ 已落地 | `git ls-tree -r main` 全树无 PlanWaitingBuffer 任何文件 |
| L4 plugin-protocol | PluginRegistry.scala + 插件协议提交史 | ✅ 已落地 | `core/plugin/PluginRegistry.scala` + `PluginRegistrySpec.scala` 在 main；提交史 600cf66d「Merge plugin-protocol: 插件系统协议完善」、e7b3b569、e323bdfb/054a6acf、3dd36276 |
| L5 workspace-picker | main web/ 有 ws-pick-target | ✅ 已落地 | web/css/chat.css:2465,2482,2487 `.ws-pick-target` |

**结论**：五支漏网产物均已落 main——worktree/分支的消失属清理而非误删，无需任何抢救动作。11:30 列的已并残留五支与今日队列已完 8 支消失亦同（skill2plugins=3dd36276、插件面板批=e323bdfb 等可在 main log 实证）。

### 7.2 清单 A——删除类（分支内容完全在 main、无独有内容）【每项状态：待宿主执行】

| # | 对象 | 类型 | 证据（20:4x 实测） | 状态 |
|---|---|---|---|---|
| A1 | `/private/tmp/nb-node-agentfile` | worktree | feat/node-agentfile 已并（is-ancestor 通过，ahead=0）；脏=3 个 untracked 逐个定性：`http.log`=09-01 静态 server 访问日志（纯日志）；`qa-agentfile-blindspots.mjs`、`verify-agentfile.mjs`=#27 agentfile 契约收尾 QA 的 Playwright 脚本（BASE 127.0.0.1:8324，任务域临时产物，可复现）→维持删除类；remove 需 `--force`（untracked 阻断普通 remove） | 待宿主执行 |
| A2 | `feat/node-agentfile` | branch | ahead=0 | 待宿主执行 |
| A3 | `~/Claude code/.nb-worktrees/nb-window-shell` | worktree | feat/window-shell 已并（ahead=0），目录净（status 空） | 待宿主执行 |
| A4 | `feat/window-shell` | branch | ahead=0 | 待宿主执行 |
| A5 | `feat/task-progress-2` | branch-only ref | ahead=0，is-ancestor 通过 | 待宿主执行 |
| A6 | `feat/turn-collapse-delegate` | branch-only ref | ahead=0 | 待宿主执行 |
| A7 | `feat/turn-collapse-delegate2` | branch-only ref | ahead=0 | 待宿主执行 |
| A8 | `fix/paste-attachment-loss` | branch-only ref | ahead=0 | 待宿主执行 |
| A9 | `red-baseline` | branch-only ref | ahead=0 | 待宿主执行 |

七支 A 类分支全部实测 ahead=0，`branch -d` 预期直接通过（已验证祖先关系）；兜底路径见 7.6 附注。

### 7.3 清单 B——绝对不动（红线：在飞/待落地）

| # | 对象 | 现场实测 | 保护原因 |
|---|---|---|---|
| B1 | `.nebflow/worktrees/bgtask-completion-gate` | 脏 9（6 M + 3 新增 Spec，+339/−83），节点 running；分支本体已并 main | 在飞；WIP=批产物。正是 L1 故障模式活体——落地闸门未走完前删 = 连产物删 |
| B2 | `.nebflow/worktrees/trigger-chain-fix` | **非净**（勘误见 7.7）：脏 10（8 M + 新增 `TriggerChainSpec.scala` + `.nebflow/Spec/20260905_pending-…无终态根因分析.md`，+225/−48），节点 running | 在飞，WIP=批产物 |
| B3 | `.nebflow/worktrees/sandbox-nebflow-root` | 净，节点 running；分支已并 main | 今日沙箱可写根批在飞 |
| B4 | `.nebflow/worktrees/node-message-tool` | 脏 8（7 M + 新增 `NodeMessageSpec.scala`，+258/−13），节点 cancelled；分支已并 main | NodeMessage 批产物待批处置，禁擅自删 |
| B5 | 监督 Actor 化批 | 现场无对应 worktree、无分支 ref、Flow Map 无节点 | **空引用**：该保护项当前无实体，无需动作，仅记录 |

### 7.4 清单 C——不删、进拍板清单（有独有内容或裁定悬置，禁擅自删）

| # | 对象 | 现场实测 | 独有内容 | 建议 |
|---|---|---|---|---|
| C1 | `.nebflow/worktrees/devport-governance` + `devport-governance` | 净；HEAD 533a2a0a 旧基线；分支 **0 自有 commit**（空壳） | 无 | 废弃类；作者点头即转 A 类双删 |
| C2 | `.nebflow/worktrees/friend-chain-ui` + `friend-chain-ui` | 分支已并；worktree vs HEAD 脏 **13 文件 +243/−447**（含删自产 spec tests/friend-chain-ui.spec.mjs −365）；index 另持过期 staged 快照（14 文件 +36/−1045，与工作区内容交错） | L6 未提交迭代 | 需作者裁定「收增量 or 弃」；裁定前禁删 |
| C3 | `~/Claude code/.nb-worktrees/nb-437-ci` + `feat/ci-desktop-verify` | 净；ahead=6（git cherry 全 +，非 squash 等价） | L7 桌测 CI 链 | 需作者裁定并/弃 |
| C4 | `pr-41-search-jump` | branch-only，ahead=1（e843c998，cherry +） | L8 | 需作者裁定 |
| C5 | `pr-44-send-btn` | branch-only，ahead=1（2f95bea6，cherry +） | L9 | 需作者裁定 |
| C6 | `Nebflow-archive-mesh-sync`（仓外）+ `archive/mesh-sync-with-session-sync` | 净，ahead=408，最后活动 06-26 | 旧架构存档 | 需作者裁定存档去留 |
| C7 | `Nebflow-pekko-only`（仓外）+ `refactor/Pekko-Only` | 净，ahead=651，07-12 | 未并重构 | 需作者裁定（弃则 -D，续则重派） |
| C8 | `Nebflow-refactor`（仓外）+ `refactor/actor-io-layered` | 净，ahead=627，07-12 | 未并重构 | 同上 |
| C9 | `.nebflow/worktrees/deadchain-archive-fix` + `deadchain-archive-fix` | 净，ahead=1（588eee83） | 调查 harness；作者已裁定零改码不并 | 收尾确认后清 worktree；分支建议暂留 |
| C10 | `.nebflow/worktrees/dialog-unify` + `dialog-unify` | 分支已并；脏 6（chat.css/modal.css/daemons.js/locales×2 5 M + 新增 tests/dialog-unify.spec.mjs；tracked +31/−16） | 疑似 L1–L5 同款漏网新例 | 先定性是否属今晚落地队列，再收/弃 |
| C11 | `.nebflow/worktrees/flowmap-nodecard` + `flowmap-nodecard` | 分支已并；脏 6（flowMap.css/flowMapArchive.js/flowMapTab.js/locales×2/测试改，+37/−12） | 同上 | 同上 |
| C12 | `.nebflow/worktrees/memory-plan` + `memory-plan` | 分支已并；脏 1（.gitignore +2/−1） | 同上 | 同上 |
| C13 | `.nebflow/worktrees/dream-memory-edit` + `dream-memory-edit` | worktree 净；分支 ahead=1（3f7bd31d，cherry +）整支未并 | 整支产物 | 需作者裁定并/弃 |
| C14 | `docs/desktop-trust-and-icp-filing-plan` | branch-only，ahead=2（1f3ce56f、73547375，cherry +），**今日新发现** | 文档计划支 | 需作者裁定并/弃 |

### 7.5 清单 D——永久保留

`main`；`beta`（ahead=25，发版线）；`release`（ahead=1，发版线）；`archive/nebflow-v1`（19）、`archive/nebflow-v2`（6）、`archive/rust-main`（123）、`rust-standalone`（137）——历史存档与 Rust 线。另 `worktree-cleanup`（本批载体）在 7.6 ④ 自清后不复存在。

### 7.6 宿主命令全集（**全部待宿主执行**，本节点零执行；顺序=先文档后删除）

> 沙箱取证：本节点对主仓 `.git` 单次写入探测返回 `touch: …/Nebflow/.git/nb-eperm-probe-20260905: Operation not permitted`（EPERM）；worktree 的 git 元数据实体在主仓 `.git/worktrees/worktree-cleanup`，故本批文档 commit 同样无法在沙箱完成——① 亦整段交宿主。执行目录：`/Users/dev/Claude code/Nebflow`。合并与 push 按项目纪律等用户明确指示。

```bash
cd "/Users/dev/Claude code/Nebflow"

# ── ① D段·本批文档落地（audit 终态已写好于 worktree-cleanup 工作区，未 add/commit——沙箱 EPERM）
git --no-optional-locks -C ".nebflow/worktrees/worktree-cleanup" add .nebflow/Spec/worktree-audit.md
git --no-optional-locks -C ".nebflow/worktrees/worktree-cleanup" commit -m "docs(spec): worktree-audit 刷新为 2026-09-05 20:4x 清扫批终态（三清单+L1-L5 下落+宿主命令全集）"
git merge --no-ff worktree-cleanup -m "Merge worktree-cleanup: worktree 全量清扫批终态文档"

# ── ② A段·删除类（7.2 全项；先删 worktree 再删分支）
git worktree remove "/Users/dev/Claude code/.nb-worktrees/nb-window-shell"
git worktree remove --force /private/tmp/nb-node-agentfile   # --force：3 个 untracked QA 临时产物（http.log / qa-agentfile-blindspots.mjs / verify-agentfile.mjs，定性见 7.2 A1）
git branch -d feat/window-shell feat/node-agentfile feat/task-progress-2 feat/turn-collapse-delegate feat/turn-collapse-delegate2 fix/paste-attachment-loss red-baseline

# ── ③ C段·收尾
git worktree prune
ls -la .nebflow/worktrees/ | grep -v '^d'   # 残链复查：20:4x 实测无软链（全实体目录），预期仅 total 行

# ── ④ D段·自身收尾（① merge 完成后执行）
git worktree remove ".nebflow/worktrees/worktree-cleanup"
git branch -d worktree-cleanup
```

> **A段兜底**：若任一 `branch -d` 被拒（七项均实测 ahead=0，理论不会），先 `git rev-list --count main..<branch>` + `git cherry main <branch>` 复核；cherry 确证等价（全 `-`）后方可 `-D`；若出现 `+`（真实独有提交）立即停止、回落拍板清单。
> **merge 兜底**：若执行时 main 已前移致 ① 冲突——worktree-audit.md 为本批独改文件正常自动并；冲突则取 worktree-cleanup 侧（本批为最新终态）。

### 7.7 勘误与对照（分发基准 20:40 vs 现场复核）

| 项 | 分发基准 | 现场实测 | 影响 |
|---|---|---|---|
| trigger-chain-fix | 「净（在飞 running）」 | **脏 10**（8 M + 2 ??，含 TriggerChainSpec.scala 与 pending 根因分析 doc） | 无清单归属影响（本即 B 类保护），仅勘误 |
| friend-chain-ui | 脏约13（+243/−447，含删自产 spec） | 13 文件 +243/−447 ✓（另 index 持过期 staged 快照 +36/−1045） | 一致；C2 |
| 其余各项 | — | worktree 形态（6 仓外 + 12 worktrees/）、ahead 计数（L7=6、pr-41/44=1、docs/desktop-trust=2、beta=25、release=1、archive 19/6/123/137）、devport 空壳 0 commit、B1/B3/B4 脏况、nb-node-agentfile 3 untracked | 全部复核一致 |

*第七节由只读清扫批节点生成（2026-09-05 20:4x）；证据命令均可复现（--no-optional-locks worktree list / status / merge-base --is-ancestor / rev-list --count / cherry / ls-tree / grep）。*
