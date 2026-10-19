# F1 消息静默丢失修复 — harvest 落地档

- 来源会话：external-zcode-9238cbcc（终态 blocked/crash，工作未提交，全部改动搁在本 worktree）
- harvest 会话：delegate-nebflow-7942c8d4（2026-10-19 19:24）
- 验收基线：input.js `send()` WS 未开早退腿（修前 :237-239 `console.warn`+return）不再静默丢；四件套（入队/持久化/幂等补发/待送达小标）+ E2E 三场景

## 文件

- `pre-harvest-zcode-e2e.log` — zcode 崩溃前最后一次 E2E（19:09，5 FAIL；其后 19:16 input.js 又改过一轮，未及重跑即 crash）
- `e2e-f1-send-queue.log` — harvest 时重跑（ALL PASS 26/26，exit 0）
- `e2e-f1-send-queue-run2.log` — 复跑稳态确认（ALL PASS 26/26，exit 0）

## 完成度判定（对照验收，file:line）

1. 断线入队（不静默丢）：input.js:239-251（早退腿改 `enqueueOfflineMessage(v, text)`；`/ask` 帧型无队列承载保持早退 = 已申报残余面）
2. 本地持久化：input.js:434（`offline: it.offline === true` 随 persistQueue 落盘，跨刷新存活；`rendered` 刻意不落盘）
3. 重连幂等补发：input.js:632 `flushOfflineQueue`（onReconnect 注册 :702，含首连）→ :646 `dispatchOfflineItem`，帧带**入队时钉死**的 clientMessageId（#569 服务端判重兜底，恰一次）；写失败留队下次再补
4. 待送达小标：chat.js:434 `createPendingSendMark` + :520 renderUserBubble `opts.pending` 前置小标；chat.css `.msg-pending-mark`；翻面 :572 `flipOfflineMark` / :611 `materializeOfflineBubbles`（S6 整替窗口收口）；撤回/移除/溢出连气泡收掉
5. E2E 三场景：tests/f1-send-queue.spec.mjs（① 断线入队→重连补发同 id 恰一帧；② 刷新持久化→仍补发；③ 在线零回归；④ 源面静态钉）

## harvest 会话的改动（产品逻辑零改动）

仅 tests/f1-send-queue.spec.mjs 读取面两处修正（spec 自身 bug，非产品 bug）：

- `queuedItems()` 读队列的 localStorage 键由硬编码 `nebflow_message_queue` 改为按「包含 message_queue」匹配（branding 命名空间 = `${brand.lowerName}_message_queue`，静态服/测试态 fallback brand.lowerName=`stand`，brand.js:35-40；同仓既有 msgqueue-image-mime.spec.mjs:146 / msgqueue-recall-attach.spec.mjs:421 同款写法）
- addInitScript 不再触碰 message_queue 键（该钩子每次导航都跑，会抹掉场景②被测的持久化轨）

场景②「刷新后补发」在 zcode 原始 spec 下因键名写错而假绿（读错键=恒空队列）/修键后又假红（init 钩子抹轨），harvest 修正后真绿且双跑稳定。

## 结论

四件套齐 + E2E 三场景全绿（26/26 × 2 稳定）→ 按 harvest 流程落地提交并 merge --no-ff 进 main，零 push。
