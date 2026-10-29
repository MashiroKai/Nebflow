# Mac stand 仓分支清扫 + worktree 收敛 + DOSTEL worktree GC 报告（2026-10-29）

授权：作者 2026-10-29「包括待办都按你推荐」。原则：先归档后删除、全程可逆；零实施代码；不动 main；不 push 远端；全程只做 tag + branch -D + worktree remove/prune + rm 空目录。

## 0. 总账

| 项 | 前 | 后 | 变化 |
|---|---|---|---|
| 分支数 | 169 | 162 | −7（全带归档 tag） |
| 归档 tag archive/20261029/* | — | 7 | 新建 |
| 登记 worktree（含主检出） | 159 | 48 | −111 |
| .nebflow/worktrees 目录 | 164 | 54（含 evidence 数据目录 + 6 个并发 GC 幽灵空目录） | −110 |
| 磁盘 .nebflow/worktrees | 52G | **19G** | **回收 ≈33G** |
| 磁盘 .git | 536M | 485M | −51M（无 gc，对象由 tag 保活） |

DOSTEL 仓：2 个残留目录删除（仅剩 `.nebflow` 会话垃圾 12K/20K），10 个登记 worktree 保留。

未并 main 分支实数 **20**（任务书口径 19）：多出 `delegate/delegate-nebflow-6cbd7d08` 与 `delegate/delegate-nebflow-bd7a0f1c` 两个未点名者，按保守原则一律「留 + 评估」。

## 1. 删除（7 支，全部先打 tag 再删，可恢复）

| 原分支 | tip | 归档 tag | 删除依据（抽查证据） |
|---|---|---|---|
| delegate/delegate-nebflow-b73c3e73 | 0297e71e0 | archive/20261029/delegate/delegate-nebflow-b73c3e73 | 收割快照提交本体已被并入 main（main 历史 19cf65fc8 即该 guard commit），后在 main 由 #886 receipt-dedup（815cad90c）继续演进；ahead=0 |
| delegate/delegate-nebflow-f3b32bbf | 7ba37ff10 | 同型 | 签名件 NfFileDenyLogSpec + tests/nf-ticket-403-heal.spec.mjs 均已在 main（fbe4618c 报告亦证重锚链在 main） |
| delegate/delegate-nebflow-81af85aa | cc1e41ee5 | 同型 | ahead=0，tip 为 main 祖先 |
| delegate/delegate-nebflow-992a3c8c | 1455a2f10 | 同型 | ahead=0，tip 为 main 祖先 |
| delegate/delegate-nebflow-ddbdde82 | ad9715861 | 同型 | KAI 侧三件重派批报告（#919/#920/#923）；git cherry 补丁数=0，4 commits 全部等价在 main（复核确认） |
| ci-gitlab-probe | 29b4ba598 | archive/20261029/ci-gitlab-probe | 唯一内容 = 4 行 `.gitlab-ci.yml` 探针；main 无此文件、GitLab CI 路线未采纳；tag 永存这 4 行 |
| feat/stand-issuer-face | a3b5ceff6 | archive/20261029/feat/stand-issuer-face | mystand 家族改名已落 main（brand.conf=mystand.space；BrandingSpec 6 处 mystand）；本支仅是改名日 spec 钉同步草稿，已被超体 |

注：任务书「7 个 stranded 快照」实为 8 支（81af85aa/992a3c8c 即「及同型」的两个已并者），8 支全部处置完毕。

## 2. 改判「留」（任务书准删，diff 抽查发现独有实质内容）

| 分支 | 独有内容 | 关键证据 |
|---|---|---|
| delegate/delegate-nebflow-1cb7b55c | subagent-generalization 批 WIP（SubagentTool 升级通用面：基础六件+AskUserQuestion+Web 两件，+86 行 spec） | main 的 SubagentTool 至今仍是只读侦察面（docstring "Read/Glob/Grep/WebSearch/WebFetch only"），泛化批从未落 main |
| delegate/delegate-nebflow-ae1c2d58 | canvas-tabs LWW 方案全实现（canvas.js +296、CanvasTabStore +53、spec 203 行） | main 以另一方案收口：#910 墓碑语义（ccd028b1c，作者令「找少的那个认吧」）；LWW 为独有备援方案，是否弃由作者裁 |
| delegate/delegate-nebflow-c6ce9f87 | external executor 流式帧（ToolEnd/Thinking/Usage、RawLineMaxChars=500、+250 行 stream spec） | main 的 ExternalExecutor/ExternalSessionRunner 均无这些帧（grep=0） |
| delegate/delegate-nebflow-f30e15f7 | **office 转档整功能**：NfOfficeRoutes.scala +446、spec +264、e2e +305、4 张证据 png | main 无任何 NfOffice*/office-convert 面（pptx 相关仅查看器侧进度态）。**本次清扫最大独有发现** |
| delegate/delegate-nebflow-e0b069db-c | 面C 设计追加批（真 TUI/pty4j 选型+终端视图设计，净 +121 行） | 同名报告文件在 main 存在但 pty4j/终端视图 命中 0（分支 13）——设计文本只在分支上 |

## 3. 留 + 逐个评估（不删；收/不收只给结论不动手）

### 3.1 fix/bg-panel-ghost-resync ——【互补，可收】（重点件）
- 分支=15 行单文件（bgAgents.js）：面板在开期每第 2 拍（30s，对齐 BG_SNAPSHOT_STALE_MS）回填一次 activeAgents 快照，带可见性门（后台标签页零取数）。
- 与已并 #1019 权威重同步（43a41c155，10-28 21:15 ask-badge 批：held 回执 getHeldReceipts 重连+会话切换索取）**不同面**——#1019 修的是问询回执徽标，本支修的是后台面板行；同属 #1019 失步家族的两条腿。
- main 现状 bgAgents.js 仍只有「开面板 fetchActiveAgentsOnOpen + WS 重连」两个快照事件流，幽灵行清道夫只吃快照、永无第二张快照来销账，终态帧丢失（断线/引擎重启/OS 休眠）的行以「空闲 N 分钟」挂屏（作者 10-27 实证 17 行僵尸滞留整天）。
- 自 fork（merge-base e4b15973，10-28 09:52）以来 main **零提交**触碰 bgAgents.js → 可干净 apply。
- **结论：收（互补不重复，15 行低风险，建议尽批重落地）。**

### 3.2 其余留评估件
| 分支 | 结论 | 理由 |
|---|---|---|
| fix/kai-mig-p2-correction | **可收** | phase-2 报告 + 版本名纪律（ops/README.md、stand-jar-pull.md）三件全不在 main；纯文档零码 |
| delegate/delegate-nebflow-d8f43803 | 留（独有实质批） | CI 存量红批 6 个实质 commit（web 17 spec 修复+JVM 31 红分诊+ci 门禁三修+SingleInstanceGuard rebrand 修复+scalafmtAll）cherry=7 全不在 main；但 behind=350，重落地需重锚评估 |
| delegate/delegate-nebflow-bd7a0f1c | 留（未点名保守） | scheduler 主动性 WIP（ScheduleInitiativeSpec/TodoScheduleSpec 新件）不在 main |
| delegate/delegate-nebflow-fbe4618c | 报告可收 | revive-mech 三跑实录 9 件（REPORT+3×XML tar.gz+日志+脚本）不在 main；NfFileDenyLogSpec 重锚件已基本在 main（与 main 仅 26 行漂移=main 侧后续演进） |
| delegate/delegate-nebflow-1a35df61 | **可收** | beta.1 发版批 REPORT+RELEASE-NOTES 不在 main（VERSION beta.1 本体已在 main 434585055）；纯文档 |
| delegate/delegate-nebflow-4398a05f | **不收（已被判错替代）** | console-slow ①②③=console-fastpath 三腿已并（791ae42c）；④「稳定 URL 树级 immutable」被 4df51ddcd（10-25 作者令④缓存击穿批）commit 原文点名「未合并分支 4398a05f 的 bcddc32a5 是『immutable at 稳定 URL』的错误半成品，稳定 URL 上的 immutable 正是击穿本体」；main 已落正确形态（内容寻址版本化 URL+immutable、裸路径 no-cache）。建议下轮清扫删（先 tag） |
| delegate/delegate-nebflow-6cbd7d08 | 留（未点名保守） | input-history 附件召回 + 帧附件 path 直取两件，inputHistory.js 不在 main → 独有实质；可收性需作者拍板（behind=346） |

## 4. 绝不触碰（核验未动）
- wip/author-neblink-20261014、backup/* ×2（backup/delegate-nebflow-8bbd5826-prerestart 等）：未动。
- 21 个 merged+近期有写入（mtime≥10-28 12:00）的活跃/近期 worktree：未动。
- 主检出（main）、本会话 worktree（delegate-nebflow-1c56b8e5）：未动。

## 5. worktree 收敛明细
- 删除 97：90 个 merged+陈旧干净 worktree（`git worktree remove`）+ 5 个已删分支的 worktree（全干净）+ brand-switch-console（issuer-face 检出处）+ /private/tmp/revive-baseline（detached@4c866d61d、干净、254M）。
- **KEEP-DIRTY 14 个保留**：merged 分支但工作区有未提交 tracked 改动（evidence png 重拍、bgAgentPopup.js、AgentState.scala、MergeWindowCare.scala、ExecutorRegistry.scala、GatewayMain.scala、locales 等）——按可逆原则不 force，留作者裁决。名单见分支：189e295a/2637ab1d/5fa55cde/5fb4d5e4/6aae2fd2/6c055b42/6e4185d6/7961c23f/9a522ebe/a26da039/bd39ddf7/cd833b36/e90a4202/fe4f891e。
- `git worktree prune` ×2：清掉 14 条悬空登记（含 3 条清扫前已坏的陈旧登记）。
- 6 个空孤儿目录（09fd8192/109c9e35/19bc02b5/73dcd2bb/74eb4637/e0b069db）：核验全空（0 文件、无 .git），**在本清扫期间被并发 GC 收走**（多次出没/消失，09:10 时点又现形为空壳）——非本会话遗留问题。
- `.nebflow/worktrees/evidence`（724K，20260915_engine-defects + 20261001_socpanel-min-verify）：非 worktree、非登记，是人工放置的证据数据——**保留**，建议作者另行归置。

## 6. 损耗披露（诚实账）
16 个「无 tracked 改动」worktree 按规则移除，其中 untracked 文件随目录消失（清单存 /tmp/sweep_untracked*.txt，2.6KB）：
- 3 项为 main 已有重复（reports/20261022_133212_aliyun-fallback、reports/20261023_182241_panels-mobile-ux、evidence/20261021_uifix-quad）——零损失。
- 其余为**未入库的复跑/草稿证据**：canvas-stack-impl 复验 evidence ×4、uifix-quad/baseline 复验 ×3、design/20261019_askuser_dragdrop_reply。所属批（canvas-stack/uifix/askuser）本体均已并入 main，损失的只是会话期复验残留；如作者认为某份复验证据有值，需从备份恢复（git 无法恢复 untracked）。
- 教训入档：本清扫的「untracked=垃圾」判据对 evidence/reports 型未跟踪目录过宽，后续清扫应先打包归档再删。

## 7. DOSTEL GC（/Users/kaiyu/Claude code/DOSTEL）
- `git worktree list` 删前核对：登记 worktree 全保留（09:05 时点 10 个：main+9，其中 505be466 的登记在清扫期间被并发方撤销，非本会话动作）。
- 2 个残留删：delegate-nebflow-212392a4、delegate-nebflow-672fc4e7。删前核验：两者仅剩 `.nebflow` 会话垃圾（12K/20K）；08:35 时点曾核对其 reports/ 内容与登记的 -clyc worktree 逐字节一致（diff -rq rc=0），672fc4e7 分支本体在 -clyc 检出中继续保留。无分支删除 → 无需打 tag。
- DOSTEL 仓 10-28 登记口径「8 留 2 删」→ 现实为「登记全留（含清扫期间新增登记者）+ 2 残留已删」，与登记意图一致。

## 8. 并发观察（时点口径）
清扫期间（08:30–09:10）另一 GC 参与者持续在动两仓：DOSTEL 212392a4 的 reports/ 内容于 08:40→09:05 之间消失、DOSTEL 登记数 11→10、stand 6 个空孤儿目录反复出没。本报告全部数字为 **2026-10-29 09:10:21 时点快照**。

## 9. 移交/待办（零实施代码，均未动手）
1. fix/bg-panel-ghost-resync 候批收编（15 行，见 §3.1）。
2. kai-mig-p2 / fbe4618c 报告 / 1a35df61 报告 三件纯文档候选收编。
3. 4398a05f 建议下轮清扫删除（先 tag）；其 ④ 结论已由 4df51ddcd 替代。
4. KEEP-DIRTY 14 处置 + 8 支改判留分支的取舍，候作者裁决。
5. .git 未跑 gc（tag 保活全部删除 tip，可逆性完整）；如需瘦身另批执行。
