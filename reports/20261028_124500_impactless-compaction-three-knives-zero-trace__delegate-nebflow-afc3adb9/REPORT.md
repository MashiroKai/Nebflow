# 无感压缩「三刀+彻底零痕迹」实施批——交付报告

- 授权：作者 2026-10-28 11:44 拍板（彻底零痕迹，连尾注都不要；影子腿加固三刀默认全做）。设计依据 = `reports/20261028_113024_console-replay-compaction-audit__delegate-nebflow-485b188a/REPORT.md` 案二三刀设计。
- 纯代码批：未触碰在产引擎（pid 70481 / gateway 6138），随下一波包生效。未动回放折叠批的 turnGroup.js 判据区（本批零字节触碰该文件）。
- 工作分支：`delegate/delegate-nebflow-afc3adb9`（worktree delegate-nebflow-afc3adb9）。

---

## 改动面（10 文件改 + 1 spec 新增）

### 刀一·影子腿可靠性（引擎侧）

**① `pingpong.shadowModel` 配置位落地（§H2 预留位）**
- `core/compact/PingpongConfig.scala`：新增 `shadowModel: IO[Option[String]]`（读 `pingpong.shadowModel`，热读无缓存同 `enabled`；空白点名归一为 None 防手误生成空 preferred 链）。
- `agent/AgentPingpong.scala` shadowFiber：点名 ⇒ `AgentModelConfig(preferred = 点名, fallbacks = Nil)` **钉死单模型链**——摘要调用脱离主腿廉价回落链（10-26 三连败实证死因）；缺省 None ⇒ `agentDef.model` 链逐字（一期 §B3 行为不变）。

**② fork 内跨供应商重试 1-2 拍后才计 ppFailures**
- shadowFiber 的单次 `llm.send`（内部已遍历整条候选链）失败后短退避重投 `ShadowSendRetryBeats = 2` 拍（间隔 `ShadowSendRetryDelayMs = 5s`），全灭才回投 `PingpongShadowFailed` ⇒ ppFailures 计 1。
- `circuitBreakerMax = 3` 与 30s×2ⁿ 退避**不变**——三败容忍窗等效从 3 次链失败放大到 9 次调用级失败（spec 钉死该算术）。

**③ 「summary missing <summary> pair」解析截断先续写一拍再判败**
- `FullCompact.scala`：截断错误文案收为单点常量 `MissingSummaryPairError` + 判据 `isMissingSummaryPairError`（前缀匹配；hard-cap 超限/空响应等其它解析失败不适用续写）。
- shadowFiber：命中判据 ⇒ partial 原文以 assistant 名义回填 + 续写指令（`ShadowContinuationInstruction`：从断点接写、补 `</summary>`、不重复已写内容）追加一拍 LLM 调用，拼接产出重走同一 pingpong 解析（regex 取第一个完整 `<summary>` 对，接缝噪声被吞）；续写拍后仍不可解析才判败。续写拍传输失败 ⇒ 按原始解析失败判（截断是语义真因）。

**④ fork 触发提前到阈值 ×0.9 预置（换入仍在轮边界，语义不变）**
- `PingpongConfig.forkPrePositionThreshold(t) = ceil(t × ForkPrePositionRatio=0.9)`（纯函数，spec 钉：256000 → 230400；t≥10 恒严格低于原阈值）。
- `agent/AgentSessionExecution.scala` maybeAutoCompact：触发决策点改为双观测线——`legacyCrossing`（原阈值）与 `ppForkCrossing`（×0.9 预置线）。
  - **门判定上提**（本批关键结构决策）：maybeAutoCompact 返回类型 `Option[IO[Behavior]]` → `IO[Option[IO[Behavior]]]`，门状态（`pingpong.enabled` 热读 × `RootAgentIdentity` 谓词）在触发决策点单次读取后以 `gateOpen: Boolean` 传入 forkOrLegacyAuto。动因：门读若留在 forkOrLegacy 内部，「门关 + 仅 ×0.9 越阈」分支没有可用的「拒绝」终值——续跑 `onForked(state)` 会经 AgentActor.pipeLlmCall **重入** maybeAutoCompact（freeze shadow → corePipeLlmCall → 7 参形态实为同一入口）造成无限递归，且任何替代续跑都绕过调用方 None 体（FastMicroCompact/ContextRefresher 等派发体）违反 §G1。上提后门关+窗口内直接 None = 派发逐字节原样。
  - 门开：原阈值越阈或 ×0.9 越阈都 fork（C₀ 含当前全量；换入仍只在轮边界——`secondBeat`、排队位判据均未动）；×0.9 触发的观测行追加 `(pp-fork pre-position ×0.9; swap-in unchanged at round boundary)` 标记，原阈值触发的观测行逐字。
  - 门关：仅原阈值越阈走 `startDirectCompaction` 逐字（先 log 后 legacy 序列不变）；窗口内 ⇒ None。
  - 排队位/退避/三连败回落三分支**判据与阈值逐字不变**（回落不预置——老同步路径零漂移）；0.95 硬守卫与熔断应急原样先行。
  - 模型切换顺手压缩点（AgentIdle §A3 判定点之二）**不预置**：一次性机会点非周期观测点，保持原阈值逐字（forkOrLegacyModelSwitch 签名不变）。

### 刀二·回落视觉静默（口径=彻底零痕迹）

**数据面零削减**（全部照常留账）：`compactStart/compactComplete/compactFailed` WS 帧原样；ui.json 记录照常（`WebSocketRoutes.compactCompleteRowFromFrame` 纯函数单点，i18nKey/before/after 与旧载荷逐字节一致）；引擎日志、压缩档案、pingpong 档案零改动。

**视觉面归零**（唯一保留例外 = 0.95 硬守卫 emergency 安全阀）：
- live（`web/js/main.js`）：compactComplete 不再渲染完成卡（`msg.emergency` 为 true 时才调 `renderCompactDoneCard`）；compactFailed 不再渲染失败卡；熔断终局 `renderError(chat.compactCircuitBreaker)` 错误行同刀拆除（仅保留 `clearBusyFor` 功能面收尾）；三处 saveMsg 落盘照常。`view.compactStartTs` 用时戳元数据随完成卡拆除（防陈旧戳串入 emergency 卡）。
- 重放（`web/js/persistence.js` restoreCompactCardRow，**四腿同判**：backend history / localStorage / 分页补页 / 初始恢复全走此单点）：`chat.compacted` 仅 `params.emergency` 为真时重建同卡，否则 null；`chat.compactFailed` 一律 null；`chat.compacting` 起始条目维持既有 null。
- localStorage 腿泄漏修补：live saveMsg 条目此前**不带 i18nKey**，会漏进 `restoreSystemNoticeRow` 渲成 notice 行——三处 saveMsg 补 `i18nKey` + `params`（emergency 随行），使该腿与 ui.json 腿同判。已知瞬态：批前遗留的 localStorage 旧缓存条目（无 i18nKey）在部署后首次刷新可能闪现一次 notice 行，backend history 加载后即被替换（best-effort 缓存，随会话推进滚掉）。
- `web/js/chat.js`：`renderCompactFailCard` 拆除；`renderCompactDoneCard` 收窄为 emergency 安全阀专用；`buildCompactCardRow` 维持 emergency 卡 DOM 单点（error 分支保留组件形态、无现存活调用点）。
- **emergency 安全阀通道**（唯一可见例外，保留现状口径）：`AgentStreamEvent.CompactComplete` 增 `emergency: Boolean = false`（缺省 false ⇒ 既有调用点/载荷零漂移）；`runEmergencyCompact`（0.95 硬守卫）发射点传 `emergency = true`（AgentProcessing:1122 常规压缩轮完成点缺省 false）；帧编码两分支均带 emergency 键；ui.json 落盘 `params.emergency` 仅 true 时落键（缺席即不落键，answerOf/quote 兼容先例）；live + 重放两腿按该标记放行渲染。

### 验收（刀三）

- a) **乒乓换入路径零帧零卡（回归钉住）**：本批零改动换入链（executeSwap/swapObservability/recoverAtIdle 原样）；既有 `PingpongCompactSpec` §D3/§E-7/8/9 套件全绿即回归钉。
- b) **回落路径零卡零横幅**：live/replay/localStorage 三腿渲染分支全部收窄（代码面见上）；emergency 通道契约由帧级 spec 钉死（`CompactFrameRecordSpec`×3 + PingpongCompactSpec 帧编码测试）。
- c) **影子腿加固各点单测/配置证据**：shadowModel 热读+空白归一（1 test）；重试拍算术 3→9（1 test）；截断判据单点正反例（1 test）+ 拼接续写重解析（1 test）；×0.9 预置算术（1 test）——共 6 个新测试进 `PingpongCompactSpec`。
- d) **数据面留账完整**：`CompactFrameRecordSpec` 钉 legacy 帧落盘载荷逐字节不变 + emergency 帧落键 + 畸形帧仍留账；ui.json/日志/档案路径零删减。
- e) **既有 compaction 相关测试全绿**：13 套（PingpongCompactSpec / CompactFrameRecordSpec / FullCompactSpec / CompactThresholdSpec / CompactServiceSpec / CompactionPolicySpec / CompactionProfileSpec / CompactUtilsOversizedSpec / EmergencyCleanOrphanSpec / AgentActorCompactionSpec / CompactThresholdOverrideSpec / CompactionAbandonFaceSpec / CompactionQueueStoreSpec）。

## 执行纪律记录

- 纯代码批：未触碰在产引擎；不碰 turnGroup.js（零字节）。
- 事故与修复（自曝）：中途误跑 `sbt scalafmtAll`，该仓 HEAD 非 scalafmt-clean ⇒ 373 个无关文件被重排（且 scalafmt 向部分既有 spec 插入坏缩进 `end for` 致 Test 编译失败）。处置：`git stash` 隔离本批 10 文件 → `git checkout -- .` 全树还原 HEAD → stash pop 取回本批文件 → 复核（本批文件内 scalafmt 插入的 end 标记经编译验证全部对齐合法，保留）。最终 diff 收敛为上述 10+1 文件，无无关文件入库。

## 结果（测试证据，2026-10-28 13:49 实测）

| 层 | 运行 | 结果 |
|---|---|---|
| 编译 | `sbt compile` / `Test/compile`（641+ 源） | ✅ 全绿 |
| 验收-e compaction 范围 | 13 套（PingpongCompactSpec / CompactFrameRecordSpec / FullCompactSpec / CompactThresholdSpec / CompactServiceSpec / CompactionPolicySpec / CompactionProfileSpec / CompactUtilsOversizedSpec / EmergencyCleanOrphanSpec / AgentActorCompactionSpec / CompactThresholdOverrideSpec / CompactionAbandonFaceSpec / CompactionQueueStoreSpec） | ✅ **144/144**（含本批新增 9 测试：刀一①②③④×5 + 刀二帧契约/落盘×4） |
| 全量套（额外加固门） | `sbt test`（40min 全仓） | 确定性 9 败——**HEAD 纯基线 A/B 同败同位**（HealthRoutes / DataRootPlaceholder / EmptyShellNotify / PasteAttachmentGuard / StatusCommand / DetachedApply×2 / SubAgentInboxMirror / WatchdogSelfMonitor，全部在本批触碰范围外的 gateway/cli/shared/processor 域）⇒ 存量问题，非本批引入 |
| 负载 flaky 排查 | HotRestartSpec（全量跑挂 7）+ BgRemoteOutputRoutesSpec（全量跑挂 1），空闲机器批内复测 | ✅ **31/31 全绿** ⇒ 纯负载性（16G 共驻在产引擎 + 40min JVM 压力下的超时），非本批引入 |

A/B 取证方法：`git stash push -u` 隔离本批 → 纯 HEAD 跑同 10 套 → 恢复批次。结论：全量套相对 HEAD **零新增失败**。

## 遗留与建议（不阻塞本批）

1. 存量 9 败（HEAD 同在）属 gateway/cli/shared/processor 域，建议另开批处理（其中 PasteAttachmentGuard 失败文案「findLocalFile no longer gated by isFrontendBlob」疑似测试与实现已脱节的陈旧断言）。
2. `sbt test` 全仓单 JVM 跑满仓会 Metaspace OOM（默认 launch 限额）+ 负载超时——建议 CI 侧固定 `-J-XX:MaxMetaspaceSize=1g -J-Xmx2g` 并分片。
3. 批前遗留 localStorage 旧缓存条目（无 i18nKey）部署后首次刷新可能闪现一次 notice 行，backend history 加载后即被替换（见刀二段）。
