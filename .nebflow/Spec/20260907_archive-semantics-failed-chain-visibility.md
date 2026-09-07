# 归档语义修复：异常终态链保留主图 + 状态渲染保真

> 缺陷⑤（今晨四缺陷批第五条工作流）：「未完成节点被重启直接归档+状态显示-」——死亡现场失去可见性。
> 实施分支：`实施-未完成节点重启归档与状态保真`（worktree 同名）。
> 作者 12:29 NodeMessage 增量裁定（必须合并生效）：「未完成应该向上汇报并且保存状态，不自动归档，由上层决定取消或者重跑。如果是像被强制关闭这种，就走的是我们新设计的进度自动恢复机制。」

## 一、取证结论（先取证后改）

### 路径① 真实触发链：前端链归档规则把死亡链「整链归档」

1. 宿主重启 → ProjectActor mount 时 `reapStaleRunning` 把持久化 running 节点收敛 `cancelled`（NodeEngine.scala），留活动区（原先还带 24h TTL）。
2. TtlTick `settleStaleRunningNodes`（NodeEngine.scala:226）兜底收敛死会话 running → `failed`。
3. **真正把死亡节点「送进归档」的是前端** `flowMapArchive.js`：`refreshChains`/`deriveArchivedIds` 按批次链（createdAt 相邻 ≤120s 聚簇）——**批内全终态即整链归档**淡出主图。死亡节点一被收敛终态，所在链若其余节点也全终态，整链立即从主图消失进归档面板。

实证：用主仓 `flow-map.json` 离线复现 deriveArchivedIds 规则，今晨 11:31 重启死亡的两节点（n-f3b85fd0 / n-a58b464c）所在链按规则全部命中立即归档——主仓 19 条含 failed/cancelled 的链被前端归档。

### 路径② shutdown 直接归档：**不存在**

GatewayMain shutdown hook 只释放 LLM backend + daemonService，零归档写。后端唯一自动归档入口 = `FlowMapStore.sweepExpired`（终态 + 24h TTL 到期）。

### 「-」状态显示出处

- 后端归档载荷**恒带 status**（实测主仓 `flow-map-archive.json` 305 条全有 status）——不是后端清空。
- 前端归档面板状态列只有 glyph（SVG 图标）：cancelled 的 glyph 就是一条横线（视觉上即「-」）；且 `ST_SVG[st] || ''` 对未知状态渲染空白。主图终态卡的 title 用 terminalTag 泛化文案，同样不显示真实终态词。

## 二、修复方案（对齐作者 12:29 裁定）

| 裁定 | 落地 |
|---|---|
| 1) 自动归档仅限 completed 链，failed/cancelled 链永不自动归档、无 TTL 强制清 | 前端 `refreshChains`/`deriveArchivedIds` 判据从「全终态」收紧为「全 completed」；后端 `autoFailDeadRunning`/`failNode`/`cancelNode`/`abandonNode` 四处 ttlExpireAt 改 `None`；`FlowMapStore.sweepExpired` 过滤从 Terminal 收窄为 `status == Completed`（同时保护存量带 TTL 的 failed/cancelled，零迁移） |
| 2) 未完成节点死亡=向上汇报+状态保存，等上层裁决 | 上报底座既有：deliverFailed/redeliver 投递失败事实到根会话 + `flow-map-events.jsonl` 审计事件（dead-session-reaped 等）+ WS 事件；节点 task/result 全部持久化可查，节点不静默消失（无 TTL 不归档） |
| 3) 强制关闭场景走崩溃恢复 P0（0b5f7fb3）auto-resume | 职责边界见下节；本修复管「不可续跑失败留现场」，P0 管「可续跑自动恢复」 |
| 4) 归档面板容量按实现默认 | 未改 |
| 5) 反事实断言：failed 链保留主图+上报发出+状态可查；「running 中杀宿主→重启」走 P0 路径，e2e 接受 cancelled/failed/running 三态 | e2e 断言已按此更新（8/8 PASS） |

### 状态渲染保真（「-」不可接受）

- 归档面板条目/成员行：glyph 旁恒加状态词文本（`.fm-entry-stw`，statusLabel 取真实状态词；空状态落 `flowmap.detail.none` 而非空白）。
- 主图终态卡：head 行加 `.fm-st-word` 真实状态词（completed→已完成 / failed→失败 / cancelled→已取消），卡 title 从 terminalTag 泛化文案改显真实状态词。
- 归档详情链行三分：chainArchived（全 completed 正常归档）/ chainAbnormal（含异常终态）/ chainRetained（链未齐保留）。

## 三、职责边界（与崩溃恢复 P0 0b5f7fb3 的衔接）

- **auto-resume（P0，今晚窗合入）**：管「强制关闭（kill/crash）后可续跑」——重启后 checkpoint 恢复续跑，节点回 running 是正常路径，不断言为失败。
- **本修复（人工裁决路径）**：管「不可续跑的失败」——failed/cancelled 节点保留主图可见、状态可查、上报已发，由上层（分发器/作者）决定取消或重跑；永不自动归档、无 TTL 强制清。
- **衔接点**：mount 时 `reapStaleRunning` 当前收敛为 cancelled；P0 合入后，该路径将被 auto-resume 取代（可续跑的直接恢复续跑，不可续跑的才落终态走本修复的可见性/裁决路径）。P0 合入时以 P0 的 reap 形态为准，本分支不预设。

## 四、实施清单

### 前端（web/）
- `js/flowMapArchive.js`：refreshChains/deriveArchivedIds 归档判据「全终态」→「全 completed」（~L266 `allCompleted`）；memberHtml/entryHtml 加 `.fm-entry-stw` 状态词；renderDetail 链行三分；notifyChainRetained 异常链改 retainedAbnormalToast。
- `js/flowMapTab.js`：FM_NODE_ST_KEY 映射；nodeHtml 终态卡加 `.fm-st-word`；卡 title 显真实状态词。
- `css/flowMap.css`：`.fm-entry-stw`（四色）/`.fm-st-word`（三色）规则。
- `js/locales/zh-CN.js` + `en.js`（成对）：新增 `flowmap.archive.chainAbnormal`、`flowmap.chain.retainedAbnormalToast`；删已退役 `flowmap.terminalTag`；`flowmap.retained`→「终态保留 {n}」；legend 文案对齐。

### 后端（scala/）
- `core/project/NodeEngine.scala`：autoFailDeadRunning / failNode / cancelNode ttlExpireAt=None（裁定注释）；reapStaleRunning / TtlDisplayMs doc 更新。
- `core/project/FlowMapStore.scala`：sweepExpired 过滤 Terminal→Completed；doc 更新。
- `core/tools/NodeTools.scala`：abandonNode ttlExpireAt=None + 审计/返回消息/NodeEdit 工具 description 更新。
- `core/project/ProjectActor.scala`、`gateway/GatewayMain.scala`：TTL 扫描注释更新。

### 测试
- `FlowMapStoreSpec` 新增：「failed/cancelled with legacy expired TTL are NEVER swept」。
- `NodeDeadSessionAutoReapSpec:273`、`NodeCleanupLivenessSpec:242/347`：断言 ttlExpireAt isDefined→isEmpty。
- `scripts/verify-flowmap-archive-visibility.cjs`（新建）：playwright 打桩 harness，14 断言。

## 五、验收证据

- Scala：`sbt testOnly FlowMapStoreSpec NodeDeadSessionAutoReapSpec NodeCleanupLivenessSpec NodeAcceptanceSpec` → **47/47 PASS**。
- 前端 harness：**14/14 PASS**；双主题截图：`.nebflow/Spec/assets/20260907_archive-visibility-{main-dark,panel-dark,main-light}.png`。
- e2e 反事实（隔离实例 8101，running 中 kill -KILL 模拟宿主死亡 → 重启）：**8/8 PASS**——死亡节点留活动区、真实状态（cancelled）、ttlLeftSec=null（无强制清）、零归档写、审计留痕、记录可查、completed 链 TTL 行为不回归、TtlTick 后仍留活动区。
- tsc checkJs：触碰文件零错误。
