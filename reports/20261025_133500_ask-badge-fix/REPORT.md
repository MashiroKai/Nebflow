# 修复批 · 顶部黄色待答徽章常显（作者报障 2026-10-25 13:0x）

## 结论（一句话）

徽章计数源没有问题（`#pending-asks-indicator` 唯一写点 = `askPending.js renderBar`，只数 AskUser 镜像，疑点②排除）；**hub 作答收口路（`complete()`）是五条终态路里唯一不广播关卡帧的一条**，卡片翻面/镜像清零是「作答面本地」副作用——没亲手作答的标签页/设备收不到任何「已解决」事件，而镜像对账（`getPendingAsks`）此前**只挂在 WS 重连**上，长连标签页可以几天不重连 ⇒ 镜像僵尸、徽标对着早已收口的槽位常显。疑点①的「恢复残留」实为该缺口跨重启的呈现（重启后 hub/台账均空，僵尸纯在前端）；疑点③成立但缺口在**作答路**而非恢复路。

## 线上实证（13:08-13:20 查证）

- `/Users/kaiyu/.stand/logs/stand.log`：今日全部 `ListAllPendingAsks → global pending-ask snapshot: 0 card(s)`（含 13:02 重启后的 13:03:15 / 13:04:03）；`Pending-ask boot reconcile: inspected=0 expiredClosed=0`（每次 boot）。
- `~/.stand/pending-asks.json`：`asks: []`。`event=ask` 审计：今日 0 次提问注册；最后一问 = 10-24 23:36（asknb-9748ebbd5169446a，root 自问要 PAT）。
- ⇒ 引擎侧全天零槽位，作者端徽标「1」= 前端镜像僵尸（`askPending.js` 模块级 `pending` Map），与 10-22 批记录的「计数存活 46h+ 跨三次重启」同形态。

## 根因（文件:行号）

**① hub 作答路不广播「已解决」帧（主根因，疑点③成立处）**
`src/main/scala/nebflow/agent/InteractionHub.scala:918`（`complete()`，改前签名）——deferred 完成 + 台账销账 + 审计，**无任何 WS 广播**。对照其余四路各有关卡帧：cleanup（:728-743）、caller-withdrew（:784-798）、user-cancel（:821+）、boot 对账（`PendingAskLedger.bootReconcile` :128-136）。前端消费面自证（`main.js:1525-1530` 注）：「卡片锁定仅剩两条活入口：askUserClosed 与卡片自身确认/取消回调（chat.js）」——即 `chat.js:3063/:3083` 的 `removePendingAsk` 只发生在**作答的那个标签页**。

**② 镜像对账只挂重连 + 重放帧豁免让对账摘不掉僵尸（放大器）**
- `main.js:4021`（改前）：`getPendingAsks` 只在 `onReconnect` 发；
- `askPending.js:131`（改前）：race 闸 `entry.ts > raced` 一票豁免，而 `ts` 被**任何** askUser 帧（含 hub 重放帧 `replayed:true`，`main.js:1465` 无差别 upsert）刷新——关卡帧丢失时，重放帧刷新过的僵尸在权威快照缺席时仍被保留，且无下一次对账可指望。

**③ 辅证（非本缺但同族）**：`ws.js` GLOBAL 集合缺 `pendingAsksSnapshot`——无 sessionId 的非 GLOBAL 帧走会话内视图路由（`ws.js:1063/:1071`），每次对账都 `setActiveView(primary)` 抢视图。

## 修复（四件）

1. **引擎** `InteractionHub.scala:1049-1079`：`complete()` 增 `rootWsSend` 形参（两调用点 :545/:596 同步），收口随行向全部已注册 root 广播 `askUserClosed{sessionId=卡所在 root, requestId, sourceSession, reason:"answered"}`——帧形同 bootReconcile 族；作答面自身卡片带 `.option-answer` 被 `closeAskUserCard` 幂等跳过（`chat.js:3167`），文案不被覆盖。
2. **前端镜像** `askPending.js:67-90`：`toEntry` 增 `live`（`replayed`/`replaces` 帧 = false）；`applyPendingAskSnapshot:135-152` race 闸改为 `entry.ts > raced && entry.live`——重放帧不再豁免对账摘除。
3. **对账单点化 + 自愈** `askPending.js:149-210`：`requestPendingAskSync`（盖 since 戳 + 发帧）/ `startPendingAskSync`（注入 send 通道；5min 可见期节拍 + 重新可见补账，60s 节流）。`main.js:53,253,1593-1597,4024-4026` 三条请求路（重连/节拍/补账）共用单点。
4. **GLOBAL 补缺** `ws.js:277-284`：`pendingAsksSnapshot` 入 `GLOBAL_MSG_TYPES`（同族先例 ：261-266），对账不再抢视图。

**i18n**：`zh-CN.js:1320` / `en.js:1371` 新增 `askUser.answeredElsewhere`（他端收口时的卡片终态行）。

## 验证

- **Playwright** `tests/askuser-badge-mirror.spec.mjs`（真实模块 `askPending.js` + `tests/fixtures/askuser-badge-mirror/harness.html`，一次性静态服务器，非 8080）5/5 绿：
  A1 无待答不显示（初态+空快照）；A2 有 ask 显数正确（1→2，待办条同镜像）；A3 收口即隐；A4 权威对账摘僵尸（重启恢复形态）；B5 重放帧不再豁免（核心形态钉）；B6 live 竞态豁免保留（#250 ③ 不回归）。截图：`.nebflow/evidence/20261025_ask-badge-fix/mirror-spec/`。
- **验红（变异安全）**：把 race 闸暂回旧口径（去 `&& entry.live`）⇒ 仅 B5 红、其余 4 绿——spec 精确复现作者报障形态并有杀灭力；还原后 5/5。
- **checkJs 门**：`askPending.js` 0 错（tsc -p jsconfig）。注：门仍红 = `nearpath.js` 3 错——`ab81fbc02`（console-fastpath 批）入账晚于基线重锚 `4592f3593` 的既有漂移，非本批文件（本批未触），建议随下个基线批收口。
- **circular-deps 门**：`popArtifacts↔msgQuote↔reference` SCC 既有红（三文件本批未触，静态边与本批 diff 无交），非本批引入。
- **sbt Compile/compile**：绿（worktree，606 sources，52s，13:35:43 完成）。

## 不做 / 开放

- 引擎侧行为 spec（hub 广播断言）未加——hub 现有测试全走命令驱动、`complete()` 无直测先例；广播属 best-effort 副作用，Playwright 已钉前端消费面。如需可后续补 `InteractionHubSpec`。
- 24h 镜像寿限与 boot 对账保留不动（本批三层之上仍留最后兜底）。
- 重启窗：引擎侧修复需随下次 sbt run 重启生效（前端批随静态资源即时生效）。
