# 排队呈现回退批 2026-10-22（作者回访令，带截图证据）

作者原话（逐字）：「怎么还出现了发送了的消息还在队列里的问题。我觉得这个可能需要
回退，已收到的标记也是多余的。」——对今日 #714 两连发批（乐观气泡 + 已收到标）+
排队信息量批（队列行位次标）的**呈现层回退**。

## 回退契约（按作者抱怨点执行）

- **去重复**：busy 排队消息已作为乐观气泡上屏，队列条不再重复渲染其内容行——
  队列条只保留头行「等待当前回复完成 · N 条排队 · 已进行 Xs」（活秒表保留）；
  行级明细（消息文本/位次标/已收到 chip/立即发送/撤回/移除按钮/拖拽重排）全退役。
- **去「已收到」标**：气泡 footer `.msg-pending-mark.acked` 绿✓确认标 + 队列行
  ack chip 同撤——消息上屏本身即收到，不用标。
- **保留**：乐观气泡本身；离线腿「待送达」时钟标（语义不同：真离线未出线）；
  drain 翻面机制（flipOfflineMark 按 `.msg-pending-mark` 寻址不动）；qattachvault
  禁静默面（行级失败注记退役 ⇒ 头行红色失败计数后缀 `.queue-bar-failed`）。
- **能力不丢、入口收敛**：立即发送/撤回/移除移入乐观气泡右键菜单
  （msgQuote.js attachQuoteMenu 挂点 → input.js runQueuedAction 执行面，动态
  import 破静态环；手机端长按派发 contextmenu 同一入口）。菜单含既有 复制/引用。
- **数据层不动**：state.messageQueue / queueMessage 钉死寻址（clientMessageId）/
  drain 逐条派发 / persistQueue 持久化轨全部原样，本批只撤 UI 重复面。

## 改前参照

改前状态（队列行 + 已收到标双呈现）= 今日早些时候批次证据：
`evidence/20261022_chatinput-two-defects/after/D-queuebar-informative-*.png`。

## 改后（shots/，`node tests/queuebar-informative.spec.mjs`，ALL PASS 亮暗双版）

- `D-queuebar-rollback-<dark|light>.png` — 全窗：两条乐观气泡在场（零小标裸上屏），
  队列条 = 单行头「等待当前回复完成 · 1 条排队 · 已进行 3s」（此帧摄于「立即发送」
  菜单动作把第一条推出线之后，计数 2→1 = 能力实证）。
- `D-queuebar-rollback-<dark|light>-closeup.png` — 输入区特写：队列条单行头，
  零行级明细、零按钮、零已收到标。

## 断言（stdout，全部 PASS ×亮暗）

① 头行 = 等待当前回复完成 · 2 条排队；② 活秒表在场且走字（1s ticker 实证）；
③ 队列条零 `.queue-item*` 行级明细；④ busy 气泡零 `.acked` 零 pending 标；
⑤ 气泡 ×2 各自独立完整；⑥ 右键菜单含 立即发送/撤回/移除（+复制/引用），
「立即发送」恰一帧出线、计数 2→1、气泡数不变；⑦ drain 后队列条隐藏、零标；
⑧ 静态源钉（busy 腿裸上屏 / 离线腿 pending:true 保留 / 行族退役 / 退役键清零 /
runQueuedAction 通道）。

## 回归（全绿）

- 邻族：`send-bubble-independence`（S2/S6 重钉裸上屏）、`busy-queue-ack`（换代重写：
  裸上屏 + 头行计数 + 菜单移除）、`queue-image-refresh`（失败面重锚头行红色计数 +
  撤回走 runQueuedAction）、`msgqueue-recall-attach`（6/6，撤回/立即发送入菜单、
  附件可见面重锚气泡媒体卡）、`msgqueue-image-mime`（5/5，同上；基线红的 K5 随
  重锚转绿）、`f1-send-queue`、`resend-dedup`、`conn-banner`、`user-attach-canvas-open`
  （5/5）、`attach-bubble-unify`、`chatinput-two-defects-shots`（A3 重钉零小标）。
- `queuepos-detail`（合并窗位次，与聊天队列条无接触，未跑——不属本批邻族）。
- `askuser-lockline-history` / `askinput-off-pending-text` / `askuser-refresh-survive`：
  交互面已迁移到新菜单入口（`.queue-item-btn.immediate` → 气泡右键「立即发送」），
  但三件依赖外部 runner 拉起活实例（改前在本机即因无活后端 BASE 连接拒绝而红，
  非本批引入）——本批改动为选择器迁移 + 逻辑复核，活后端回归待环境。
- checkJs：**254 < 基线 257**（退役面顺带清 3 个既有错）。check-circular：与 main
  HEAD 输出逐字节同（既有 SCC[3] popArtifacts/reference/msgQuote 原样；新菜单通道
  为动态 import 不入静态环）。`npm run build`：179 sources orphans 0。

## 明确不做 / 已知收窄

- 拖拽重排随队列行退役（行级能力；数组数据层与 drain 队序不动）。若需恢复，候选
  方案（下批再议）：气泡菜单「上移一位」。
- **跨刷新窗口**的失败项/排队项无 UI 入口（乐观气泡不跨刷新，队列条已无行）——
  数据层（撤回/移除执行面）完好，菜单入口待气泡在场。如实录，作者可再令。
