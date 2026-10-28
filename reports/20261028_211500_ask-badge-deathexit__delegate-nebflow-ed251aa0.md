# 修复批 · 黄色问题胶囊无问题仍显示计数（作者报障 2026-10-28 17:5x）

## 结论（一句话）

**那个「1」钉不到任何一条问题记录——它根本不是待答问题数。** 引擎权威面全天干净：hub 槽位 17:02–17:56 每拍 `ListAllPendingAsks → 0 card(s)`、`pending-asks.json` 台账空、当日三问（asknb-7b670edc / asknb-39fab596 / asknb-f42ae498，11:37/13:52/14:32 发起）全部 11:42/14:06/14:33 经卡片收口（审计 `event=answer` 在案）。同位置的琥珀胶囊共有两枚——`#pending-asks-indicator`（待答）与 `#held-receipts-indicator`（回执待投递，2026-10-21 fix 3 可见化批）——**chat.css 里两者逐项同材质**（同 warning 琥珀色/同 `.pa-dot` 脉冲圆点/同计数面），顶栏不可分辨；而回执胶囊在报障窗口实测有活数据：`17:48:29 event=held-receipts-flushed-before-input detail=held=1`（Nebula root，15:49 还有一次 watchdog force-drain held=1）。根会话 turn 在跑、六颗 delegate 在飞（旁证：蓝胶囊=8）、每颗收工回执被屏障扣留 ⇒「1」反复出现——**作者看到的是回执积压计数，被当成了问题数**。批内四件：①回执胶囊换中性灰身份（信息性信号不占警示琥珀，问题胶囊独占琥珀语义）；②回执计数补权威重同步路（`getHeldReceipts`，#1019 失步同源形）；③真正的问题侧生命周期缺口照单全修——hub 待答槽的**死亡出口收口**（Stop/deferred-stop/Retry/ResetSession/RestartAgent/frozen+idle Interrupt，#250② 只接了 Interrupt 一条，其余全部漏）；④spec 三面钉 + 全家回归。

## 数据取证（把「1」钉到具体那一条）

- hub：`~/.stand/logs/stand.log` 全天 `ListAllPendingAsks → 0 card(s)`（17:02/17:06/…/17:56 逐拍，5min 节拍 + 可见性补账都在跑）；14:04 唯一一帧 `1 card(s)` = asknb-39fab596 未答期间的真实待答，14:06:10 `InteractionAnswered` 收口。
- 台账：`~/.stand/pending-asks.json` `asks: []`（updatedAt≈14:33，与最后一问收口同拍）。
- 回执面：`17:48:29 event=held-receipts-flushed-before-input detail=held=1`；`15:49:17 HeldDeliveryWatchdog: nudging d7c3cb54 — 1 held receipt(s)…` + `held-receipts-force-drain held=1 returned-outstanding=3`。会话目录 `held-receipts.json` 现已不存在（=0）——若报障时刻在看根会话，琥珀「1」与此完全吻合。
- 前端镜像（askPending）：唯一写点 = 服务端 askUser 帧 + 快照对账；hub=0 时快照每拍都会摘掉僵尸（10-25 修复批语义，本日实测节拍在跑）⇒ 待答徽标在该窗口**不可能**合法显示 1。

## 生命周期缺口（问题侧，本批修的③）

`AgentActor.closePendingInteractionsForInterruptedTurn`（#250②，2026-09-13）只挂在 **Interrupt** 一条路；原批按「禁扩面」登记未修的出口全部漏收口——turn 的等待方死了（链取消/父重启/收殓走 `Stop` 家族；/clear 走 ResetSession；supervisor 重启走 RestartAgent；checkpoint 重派走 Retry），hub 槽与卡片/徽标永挂（零超时纪律下无自愈）。本批把该助手 `reason` 参数化并接满全部死亡出口（幂等，零命中 no-op）：

| 出口 | 位置 | reason |
|---|---|---|
| Stop（即时） | AgentProcessing / AgentIdle / AgentFrozen | source-stopped |
| Stop（deferred 兑现点） | AgentProcessing ToolsComplete K-1 分支 | source-stopped |
| Retry（checkpoint 重派） | AgentActor.retryFromCheckpoint（processing/frozen 共用） | turn-interrupted |
| ResetSession（/clear） | AgentActor.resetSessionHandler（processing/frozen 共用）+ AgentIdle | session-reset |
| RestartAgent（supervisor 重启） | AgentProcessing / AgentIdle / AgentFrozen | agent-restarted |
| Interrupt（frozen/idle 补全） | AgentFrozen（原缺，与 processing「同语义」名不符实）/ AgentIdle（泄漏槽兜底） | turn-interrupted |

`reason` 经既有 `handleCleanupForSession` 广播 `askUserClosed`；未知 reason 前端回落「已取消」文案，兼容矩阵不破。过期面维持两道既有权威（boot 对账 `PendingAskLedger.bootReconcile` + 前端镜像 24h 寿限），不新增运行时超时（10-22 批「零超时纪律」不破）。

## 改动面（7 改 + 3 新）

- **引擎**：`AgentActor.scala`（助手 reason 参数化 + Retry/Reset 接线）、`AgentProcessing.scala`（Stop 即时/deferred 兑现 + RestartAgent）、`AgentIdle.scala`（Stop/Reset/Interrupt/RestartAgent）、`AgentFrozen.scala`（Stop/RestartAgent/Interrupt 补全）、`WsSessionChatHandlers.scala`（新 `getHeldReceipts {sessionId}` → 直回既有 `heldReceipts` 帧型 + `snapshot:true`；权威链 registry 记录行计数 → 退化 HeldReceiptStore 台账文件）。
- **前端**：`main.js`（held 段内两行路：WS 重连 + `nebflow-session-change` 时 `getHeldReceipts` 拉权威计数——帧丢失/半开/跨会话切换后的失步收口，消费点零新增）、`chat.css`（`#held-receipts-indicator` 换中性灰 + 方形收件点、去脉冲动画；族面 padding/字号/圆角不动）。
- **spec**：`src/test/scala/nebflow/agent/AskDeathExitCleanupSpec.scala`（D1/D2/D3，真 AgentActor+真 hub）；`tests/held-capsule-identity.spec.mjs` + fixture（H1–H4 计算样式）。

## 验证

- **AskDeathExitCleanupSpec 3/3 绿**：D1 Stop→askUserClosed(reason=source-stopped)+槽回收+旁观者（node-bystander）不误伤；D2 ResetSession→session-reset+清；D3 Retry→旧槽 turn-interrupted+重派新槽成立。验红 = 首轮编译件未含接线时三条全红（hub 槽滞留、关卡帧缺失），含接线后转绿。
- **held-capsule-identity 2/2 绿**（真实 base.css+chat.css 计算样式）：H1 色相分离（ask 琥珀 R>G>B；held 近中性 B≥R、两底色不等）、H2 点形分离（50% 脉冲圆点 vs 2px 静止方点）、H3 族面不破（padding/fontSize/borderRadius 逐项相等）、H4 `.hidden` 收口语义不变。验红：基线（琥珀）CSS 下 H1 红，换新 CSS 转绿。截图 `.nebflow/evidence/20261028_ask-badge-deathexit/held-capsule/capsules-lit.png`。
- **askuser 静态族回归**：badge-mirror/badge-conditional/dup-card/expandfix/bubblename/answer-source + 本批新 spec 共 **38/38 绿**；lockline-history/history-attach/askuser.spec 14 跑 9 绿 5 红——5 红**与 prod 基线树（零 diff）逐条同名同败**（既有 home 态 flake，非本批）。
- **askuser-refresh-survive：3/3 红但为既有赤字**（非本批新增）：隔离实例（worktree 构建，:8688 + mock-llm）实跑取证——T1 的问→卡→刷新→点选→作答→桥注入全链**成功**（`InteractionRequest asknb-a671…` / `InteractionAnswered` / answer bridge 日志在案），败在终判 `MOCK_DONE`：mock（09-22 dd344bf34 后未更）押「作答落 tool-result」，而 root 2026-10-02 令 **#462 裁②已废 `mode` 键、AskUserQuestion 一律非阻塞**（作答经 bridge 注入为 user 行）⇒ 终答回「ok」；T3 的 `mode:"non-blocking"` 载荷被引擎显式拒收（`AskUserQuestion does not accept a mode parameter`）。规格(fixture)陈旧于引擎裁决 26 天，与本批无关；建议随下个 e2e 夹具批按非阻塞语义重写 mock 状态机。
- **checkJs**：总量 257 == prod 基线 257；触及件 main.js 12 == 基线 12、askPending 0 == 0，零新增。
- **sbt Test/compile**：绿（879 源）。生产引擎（pid 70481/:6138）全程未触碰；中途一次误将改动落进 prod 树，已 `git apply -R` 原样还原（零残留，补丁留档 /tmp/prod-tree-accidental-edits.patch）。

## 开放 / 未做

- 回执胶囊的**引擎侧死亡清理**（父会话 Stop 时 `HeldReceiptStore.clear`）未做：held 行随 pendingEvents 重启可由 syncFromEvents 复原，贸然清有丢重启复活回执的风险，留待 held 批自治面。
- 待答徽标的多标签页不对称（hub 关卡帧只达最后注册的 root 连接，余靠 ≤5min 对账兜住）维持 10-25 批口径。
- askuser-refresh-survive / mock-llm 的非阻塞语义重写（既有赤字，建议独立 e2e-fixture 批）。
