---
type: design-spec
status: draft
title: AgentControl 查询能力升级方案设计稿（list/status/新增查询 action）
created: 2026-10-31
author: stand (delegate-nebflow-b536b26a, 主用户 Nebula/Spider 委托)
tags: [design, agentcontrol, query, delegate-observability]
---

# AgentControl 查询能力升级方案设计稿（只设计不实施）

> 作者 10-31 10:47 令：「现在获取的信息太少，查询能力也有不足」。本稿逐条对位主用户
> （Nebula/Spider）当日实打痛点（12 条），给 action 级设计；JSON 参数级 schema 草案见
> 配套规格书 [[20261031_105500_agentcontrol-query-upgrade-spec__delegate-nebflow-b536b26a.zh-CN]]。
> 现状代码基线：`src/main/scala/nebflow/core/tools/AgentControlTool.scala`（1225 行，
> 四 action：list/status/cancel/restart）。

## §0 一句话目标

在 **零回退**（现有 `list`/`status` 不传新参时输出逐字节不变）的前提下，给 AgentControl
补上「过滤/搜索/汇总/合并视角/活性证据/bg 清单/产物指针/人话诊断/mail 队列/历史检索/
等我视角」十二条查询面——能复用的数据源全部复用，缺的数据源列为本批新增埋点（§4）。

## §1 十二痛点 → 设计对位总表

| # | 痛点 | 设计处置 | 形态 | 数据源（现状/新增） |
|---|---|---|---|---|
| 1 | list 无过滤（project/status/stuck/设备/age） | `list` 增可选 `filter{}`；不传 = 现状输出 | 扩展现有 | agentRegistry（内存）+ BgTaskRegistry.deviceName + 新增 AgentRecord.deviceId 埋点 |
| 2 | 无搜索（任务文本/branch/sessionId） | 新 action `search`（持久注册表子串/前缀检索，含历史实例） | 新增 action | DelegateRegistry.list() 全量（含 archived）+ TaskSearchIndex 语义面互补 |
| 3 | 列截断（task 列十几字就断） | `list` 增 `verbose=true`：task 列 48→96、parent 列全显；默认窄表不变 | 扩展现有 | 渲染参数 |
| 4 | 无汇总（在飞/待合并/僵尸） | `list` 增 `stats=true`：表尾一行机器可数汇总 | 扩展现有 | 现有判据复用（stuck assess / orphan join / reportStatus） |
| 5 | 缺合并视角（branch/merge 状态不在行上） | 新 action `mergeboard`：三段板（waiting/merged/rejected） | 新增 action | DelegateRegistry（branch/baseBranch/worktree/reportStatus）+ MergeQueue（内存队列位）+ 新增 mergeCommitSha/tipSha 埋点 |
| 6 | 缺活性证据（只有时间没内容） | `status` 增 `--- activity ---` 节（lastEvent 内容行） | 扩展 + 埋点 | 新增 `AgentRecord.lastEventSummary`（actor 事件桥单写） |
| 7 | 缺 bg 任务清单（状态/日志路径） | 新 action `bgtasks`（按 session 或全量） | 新增 action | BgTaskRegistry（tasksOf/waitingFor 现成；ActiveTask 有 jobId/kind/deviceName/outputPath(remote)/健康三字段） |
| 8 | 缺产物指针（报告/截图路径） | `status` 增 `--- artifacts ---` 节；`search`/`mergeboard` 出参带路径 | 扩展 + 埋点 | **现状零承载**（实证：DelegateTool.scala:594 提示词承诺 "registered as pointers" 但记录面无此字段）→ 新增 `DelegateRegistry.Record.reportArtifacts` |
| 9 | barrier 缺人话诊断（0/0!1 自己解） | `status` 保留旧 barrier 行，尾部追加 `--- barrier diagnosis ---` 人话块 | 扩展现有（纯追加） | 现有四计数（outstanding/held/unread/pendingDelivery）逐项翻译 |
| 10 | 缺 mail 队列状态/重试历史/判词 | 新 action `mailq`（按 session 或全局）；`status` 追加 `--- mail ---` 节 | 新增 action | DelegateDeliveryReceipts（未 ack/parked）+ HeldDeliveryWatchdog（重试/退避）+ DeliveredReceiptLedger（判词）+ mailbox 深度 |
| 11 | 历史实例查不到（branch/tip/merge commit 无检索） | `search` 覆盖 DelegateRegistry 全量（含 archived）+ branch/tip/mergeCommit 列；TaskSearch 索引补齐 branch/sessionId 进嵌入文本（可选第二轨） | 新增 action + 索引扩面 | DelegateRegistry + 新增 tipSha/mergeCommitSha 埋点 |
| 12 | 无「等的是我」视角 | 新 action `pending-me`：四张等待面聚合（等我 Mail/等我合并/等我判词/等我回执注入） | 新增 action | 聚合既有数据源（ExternalOutputStore.mailboxDepth / reportStatus=merge / verdict ledger / unreadReceipts） |

## §2 Action 面设计（保持表格简洁 + 详情下沉）

统一原则：每个 action 输出「紧凑表 + 一行指引（要详情跑 `AgentControl(action=status, sessionId=…)`）」，
详情永远下沉到 `status`。所有新参数**可选**；`list`/`status` 不带新参 = 现状字节级输出。

### §2.1 `list` 扩展（痛点 1/3/4）

- `filter{}`：`kind` / `status` / `stuckOnly` / `idleMinMin`（idle 超过 N 分钟）/
  `ageMinMin`（up 超过 N 分钟）/ `device` / `project` / `text`（任务描述子串，大小写不敏感）。
  - `device` 匹配面：外部/远程行取 `ActiveTask.deviceName` 与 `delegatedBy`；本机行新增
    `AgentRecord.deviceId` 埋点后同面匹配（存量记录缺字段 = 不匹配 device 过滤，不报错）。
  - `project` 匹配面：DelegateRegistry.Record.project（内部行经 registry join，外部行经
    DelegateRegistry 行直取）。
- `stats=true`：表尾追加一行汇总：
  `stats: live=N flying=N idle=N stuck=N orphan=N external=N pendingMerge=N readOnly=N zombie=N`
  （zombie = registry 行存活但 task 行已终态/无任务行的不一致形态，可选计数）。
- `verbose=true`：task 列 48→96、parent 列全显（默认窄表不变）。
- 过滤态仍输出同一表头（脚本继续按列解析），只是行数变少。

### §2.2 `status` 扩展（痛点 6/8/9/10，全部纯追加）

现有行全部保留（barrier 旧行不动），尾部追加新节；**无数据时该节整体省略**：

```
--- activity ---            # 痛点6：活性证据
lastEvent: Bash(cat reports/x.log) 4m12s ago — exit 0, 1.2KB output
--- barrier diagnosis ---   # 痛点9：人话诊断（旧 barrier 行保留在上方原位置）
outstanding: 1 — delegate-nebflow-abc123 (silent 22m)：在飞批次成员，已超出静默阈值
held:      2 — 结果已停靠待注入，投递链正常，下个 turn 注入
unread:    1 — 回执重试已穷尽，将随你下一条输入先补注入
pendingDelivery: 0 — 无在途未确认投递
--- artifacts ---           # 痛点8：产物指针（reportArtifacts 埋点，可空省略）
report: /abs/path/reports/20261031_xxx/REPORT.md
evidence: /abs/path/evidence/… (sha256:…)
--- mail ---                # 痛点10：mail 队列切片
mailbox: 2 queued / 1 in-flight
deliveries pending ack: 1 (delegate-xyz, parked 3m, retry 2/5 backoff 30s)
verdicts: judge=pass @10:31:02 / verifier=fail @10:30:11 (见 DeliveredReceiptLedger)
```

### §2.3 新 action `search`（痛点 2/11）

直接扫 DelegateRegistry（`<dataRoot>/delegates/<id>.json` 每实例一文件，`list()` 全量、
零删除纪律、含 archived）——不依赖 TaskSearchIndex 的嵌入面：

- `q`：title/任务文本子串；`branch`：branch/baseBranch 精确或前缀；`sessionId`：id 前缀；
- `reportStatus`：merge/blocked/finish 过滤；`since`/`until`：createdAt 毫秒区间；
- `limit`（默认 20）、`includeArchived`（默认 **true**——痛点 11 的核心就是查历史实例）。
- 输出列：`id | branch | base | tip | mergeCommit | reportStatus | created | archived | title(48)`。
- 一行指引：活体 → `status`；终态 → `workReport`（工作轮终稿槽）+ artifacts。
- 第二轨（可选，本批可不做）：TaskSearchIndex 把 branch/sessionId/worktree 纳入嵌入文本，
  覆盖「语义搜」诉求；本批子串+前缀已够用，见 §6 开放问题。

### §2.4 新 action `mergeboard`（痛点 5）

三段板（表列：`id | branch | base | tip | mergeCommit | reportStatus | queuePos | verdict | age`）：

- **waiting**：`reportStatus=merge`（delegate_report 状态槽）∪ MergeQueue 在册行，附队列位次
  （MergeQueue 内存面，重启后在册 delegate 依 reportStatus 重建视图）；
- **merged**：有 `mergeCommitSha` 的行（近 N 天，默认 7，`days` 可调）；
- **rejected**：判词 fail / 验收拒的行（verdict detail + 重跑指引一行）。
- 每行附「下一步动作」：waiting → 合并提醒或人工合并；rejected → cancel + 重派 / Mail 续聊。

### §2.5 新 action `bgtasks`（痛点 7）

- 入参 `sessionId`（可省 = 调用者可见范围全量）；复用 `BgTaskRegistry.tasksOf` /
  `waitingFor`（现成读面）。
- 出参每 job 一行：
  `jobId | session | kind(local/remote) | device | origin | status | age | health | output`。
  - `device`：remote 行 = `ActiveTask.deviceName`；
  - `output`：remote 行 = `outputPath`（命令重定向解析值，可空）；local 行 = 输出留存区
    （BgTaskOutputStore）指针 + 行数快照——**设计明确**：本地 job 无行内 logPath 是现状
    承载面约束，本批不改存储，只把 outputPath/留存区路径照实展示；
  - `health`：`suspicionLevel`/`escalatedAtMs`/`lastActivityMs` 三字段直显（stuck 判别
    的第一手证据）。
- 限制声明：BgTaskRegistry 纯内存（`Ref`，不落盘，重启即清），`bgtasks` 只反映在册
  活动任务；历史输出走 BgTaskOutputStore（另行按 sessionId 查）。

### §2.6 新 action `mailq`（痛点 10）

- 入参 `sessionId`（可省 = 全局可见面）；三段紧凑表：
  1. **mailbox**：`ExternalOutputStore.mailboxDepth`（外部 delegate 排队/在飞）+ 内部会话
     待注入队列计数；
  2. **deliveries**：DelegateDeliveryReceipts 全列（未 ack / parked / unread + 重试历史
     from HeldDeliveryWatchdog 记账）；
  3. **verdicts**：DeliveredReceiptLedger（判词事件：judge pass/fail、verifier 裁定）。
- 每段尾「建议动作」一行：未 ack 持续 → 查投递链；unread → 下条输入自动补注入，无需动作；
  fail 判词 → 附重跑指引。

### §2.7 新 action `pending-me`（痛点 12，「等的是我」）

以调用者为锚（root 桶 = Nebula 全局视角）聚合四张等待面：

1. **等我的 Mail**：mailboxDepth>0 的 delegate（含外部行地址 `delegate:<id>`）/ 终态可续聊实例；
2. **等我的合并**：`reportStatus=merge` 的 delegate + MergeQueue 位次；
3. **等我的判词/验收**：verifier fail 待重跑、judge 待裁的行；
4. **等我的回执注入**：我名下 `unreadReceiptCount>0` / pendingDelivery>0 的会话行。
- 输出：分段紧凑表，每行带「下一步动作」列（`Mail(address=…)` / 合并 / cancel+重派 / 无需动作）。

## §3 兼容性设计（痛点：现有输出不回退）

| 面 | 保证 |
|---|---|
| `list` 无参 | 输出逐字节不变——filter/stats/verbose 全缺省走旧渲染路径（代码上 if-flat 分支隔离） |
| `status` 无参 | 同上；新四节只在**有数据**时追加在文件尾，既有各行（含 barrier 旧行、manage 行）逐字节不变 |
| inputSchema | 只加可选 property；`required` 仍只有 `action` |
| tool description | 增补新 action 段落；旧四 action 描述逐字保留 |
| 权限 | 新读面 action 全部复用 `scopeFilterFor` 语义（root 全局 / Manager 子树）；`search`/`mergeboard` 读持久注册表，同样过 scope 门（Manager 只能搜到自己子树 spawn 的 delegate——按 parentSessionId 链判定） |
| 性能 | DelegateRegistry.list 全扫目录（每实例一小 JSON，量级可接受，热读同既有 AgentControl 外部行 join）；`search`/`mergeboard` 默认 limit；registry 内存扫本就是 list 现行为 |
| 既有测试 | AgentControlE2ESpec 无参用例字节级回归必须全绿；新 action 各加烟测用例 |

## §4 新增埋点清单（本设计批要求、实施批落地）

| 埋点 | 写点 | 读点 | 痛点 |
|---|---|---|---|
| `DelegateRegistry.Record.mergeCommitSha: Option[String]` | 合并成功落点（MergeArtifactCare/合并执行面单写，first-write-wins 幂等） | mergeboard / search / status | 5/11 |
| `DelegateRegistry.Record.tipSha: Option[String]` | spawn 时基线快照；合并成功后回写新 tip | mergeboard / search | 5/11 |
| `DelegateRegistry.Record.reportArtifacts: Option[List[String]]` | delegate_report 工具落点时回写（报告/证据绝对路径列表，可多次追加） | status artifacts 节 / search / mergeboard | 8 |
| `AgentRecord.lastEventSummary: Option[(String, Long)]`（内容摘要 + 时间戳） | `touchRegistryActivity` 写桥（AgentRegistryEmit.scala:101-141——现状只写时间戳/工具相位，无内容参数，本批扩签名） | status activity 节；list verbose 时 phase 列旁注 | 6 |
| `AgentRecord.deviceId: Option[String]` | 会话 spawn 时写入（种子源 = SessionContext.sessionDevice 内存路由设备，AgentState.scala:398） | list device 过滤 / bgtasks device 列 | 1/7 |

兼容纪律：全部 Option 字段、DelegateRegistry 侧手工容错 Decoder（cliSessionId 同款纪律），
存量记录零迁移；AgentRecord 为内存 case class，加字段无持久兼容问题。

## §5 输出形态示例（mergeboard 草案）

```
Merge board — waiting 3 / merged(7d) 2 / rejected 1

waiting:
| delegate-nebflow-b536b26a | delegate/delegate-nebflow-b536b26a | main | a1b2c3d | -        | merge   | #1 | -                     | 2h15m |
merged:
| delegate-nebflow-002f6988 | delegate/delegate-nebflow-002f6988 | main | d4e5f6a | 9c8b7a6  | finish  | -  | pass                  | 1d    |
rejected:
| delegate-nebflow-abcdef12 | delegate/delegate-nebflow-abcdef12 | main | …      | -        | blocked | -  | fail: 验收读数缺失    | 3h    |
下一步: waiting → 合并提醒队列自动唤醒; rejected → cancel + Delegate 重派 或 Mail 续聊改判
```

## §6 风险与开放问题（呈作者拍板）

| 问题 | 选项 | 倾向 |
|---|---|---|
| 新 action 数量（4 个）是否合流 | A. 保持 search/mergeboard/bgtasks/mailq/pending-me 独立；B. 合成一个 `query` action + `view` 枚举 | **A**：五面差异大，独立 action 对提示词可发现性最好（模型不用记枚举值） |
| `lastEventSummary` 内容上限 | 64 / 128 / 256 字符 | **128**（够人判断、不刷屏） |
| mergeboard 的 merged 窗 | 固定 7d / 默认 7d + `days` 参数 | **默认 7d + `days`** |
| `search` 是否同时扩 TaskSearchIndex 嵌入面（branch/sessionId 进文本） | 本批不做（子串+前缀够用）/ 同事做 | **本批不做**，留后续批（索引重建成本与收益不成比例） |
| device 过滤取值面 | roster deviceId（稳定）/ 展示名 | **roster deviceId**；展示名归前端 |
| `pending-me` 的「判词待裁」数据源 | DeliveredReceiptLedger 已够 / 需新埋点 | 待 recon 定论（见 spec §3 数据源核验表） |

## §7 下一步

1. 作者拍板 §6 开放问题 → 2. 实施批按 spec 落地（JSON schema 见配套规格书）→
3. AgentControlE2ESpec 增补：无参字节级回归 + 五新 action 烟测 + 埋点写读回路用例。
