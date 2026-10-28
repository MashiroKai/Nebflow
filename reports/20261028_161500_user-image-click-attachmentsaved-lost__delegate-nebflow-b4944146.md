# REPORT — 「用户在控制台聊天里发的图片点不开」（作者报障 2026-10-28 15:1x）

- 批次：缺陷修复批（用户报障直派 #1011）
- 分支：`delegate/delegate-nebflow-b4944146`
- 日期：2026-10-28
- 工作区：`.nebflow/worktrees/delegate-nebflow-b4944146`

## 0. 一句话根因

**断点不在图片字节链的任何一跳，而在一个丢了 18 天的 WS 帧订阅**：chat.js 的
`onMessage('attachmentsSaved', …)`（后端附件落盘后回传 `{type:'attachmentsSaved',
sessionId, attachments:[{name,type,path}]}` 的唯一消费端）在 **2026-10-10 USTC
dev/main 合并的冲突解决中整体丢失**（checkpoint `a0f2edfc7` 的 chat.js:490 原文可
溯；讽刺的是 `patchUserMediaRow` 与 `onMessage` 两条 import 线都活着，唯独消费块没
了）。于是 live 发送腿的用户图永远停在 pathless 形态——点击只能落 #491 主题三的
pathless 灯箱（发送时那张 data-URI 预览），**开不了 Canvas 查看器**（三修 ② 裁定
形态、与 agent Pop 图同款），直到刷新页面由历史恢复腿（自带 path）接管——这正是
「还是点不开」：2026-10-08「用户附件点击统一批」修的东西被 10-10 合并打了回去。

样本（`320638041168375_截屏2026-10-28 15.12.35.jpg` 等，含中文+空格文件名）**全程
无嫌疑**：`encodeURIComponent` 逐段编码、`/uploads/` 路由 200、`/api/nf-file` 票据
道 200、凭据判据不命中（NfPathEncodingProbeSpec 既有钉 + 本批隔离实例实测逐跳
200）。作者会话 ui.json（d7c3cb54）的落盘行 path 完整，恢复腿本就完好。

## 1. 逐跳排查结论（断点贴在哪一跳）

链：上传落盘 `~/.stand/uploads/<sid>/` ✓ → ui.json 带 path ✓ → `attachmentsSaved`
帧回传 ✓（隔离实例真发送实测帧到达）→ **❌ 前端订阅消费（断点在此）** →
`patchUserMediaRow` 原位重渲（没跑）→ 点击 `openMediaItemClick`：path 空 ⇒ pathless
灯箱（作者所见「点不开」）；path 在 ⇒ `openPopArtifact` → `workspace-open-item` →
`pop.readFile` → `fileContent` → Canvas 图片 viewer → `/api/nf-file` ✓。

排除面（全部实测/既有 spec 佐证）：`/uploads/<sid>/<中文+空格>.jpg` 200 且像素真解
码（uploads-restore-image A1）；nf-file 对空格/中文/`%20` 的解析有
NfPathEncodingProbeSpec 全家族钉；NfFilePolicy 凭据判据对样本文件名不命中；在产日志
（stand.log）零 `pop.readFile failed`（uploads 路径）、零 `nf-file denied`——点击从
未打到后端失败腿，故障纯在前端装配面。

## 2. 修复点（改动面）

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/chat.js:579-598` | **找回丢失的订阅**（逐字恢复 checkpoint 原块）：`onMessage('attachmentsSaved', (msg) => { if (msg && msg.sessionId) patchUserMediaRow(msg.sessionId, msg.attachments); });` + 断线史注（含「删前先跑 spec」警告）。零新 import（两条线本来就在）。 |
| `tests/fixtures/user-attach-canvas-open/harness.html` | 新增**真 ws.js 入站派发腿**：任何 import 之前桩 `window.WebSocket` → `connect()` 真铸 probe → FakeWebSocket 实例 → `__dispatchWsFrame` 喂帧走真 onmessage（真会话闸 + 真 handler 表）。 |
| `tests/user-attach-canvas-open.spec.mjs` | 新增 **B 组**回归钉：B1 = 真帧派发 → 行补丁（角标出现）→ 媒体点击 = `workspace-open-item` 单点（非灯箱）；B2 = 他会话帧被 ws.js 会话闸丢弃 ⇒ 不跨会话补丁（重入由恢复腿接管的契约面）。 |

**为什么旧网没接住**：user-attach-canvas-open spec A 组直调 `patchUserMediaRow`，
「帧 → 订阅 → 补丁」这一跳从未被钉——订阅丢了全组照绿。B 组补上这一跳后，负控实
测（临时摘订阅）B1 转红，订阅再丢必被抓。

## 3. 验证要点

- **e2e 隔离实例复现**（真引擎 :8291 + 真 Chromium + 真样本文件，隔离 home）：
  - 修复前 live 腿：帧到达但行不补丁（cornerBtns=0）→ 点击零 `workspace-open-item`
    → 只落 pathless 灯箱（= 作者症状逐字节复现）；
  - 恢复腿（刷新后）：点击 → Canvas 页签 → 图片 viewer → `/api/nf-file` 200 全链通
    （证明字节链无恙，差异只在 live 腿的 path 补丁）。
- **spec**：user-attach-canvas-open 7/7（A1-A7 + B1-B2）；负控摘订阅 → B1 红。
- **家族回归零新增失败**：media-resolver-fallback 4/4、uploads-restore-image 3/3、
  attach-bubble-unify、pop-media-index、pop-image-single-fetch、pop-media-alignment、
  social-media-unify、attachkey-idem、attach-split、askuser-attach、
  msgqueue-recall-attach——与干净 HEAD 逐败例对照，唯一败例 pop-artifacts P7 在干净
  HEAD 同败（既有 flake，历史批报告已登记同结论），其余 23 个 `fetch failed` 败例
  HEAD 逐字同集（环境性）。
- **质量闸**：checkJs 257 < 基线 260（PASS）；check-circular 与 HEAD 逐字节同（既有
  SCC[3] 原样，本批零新增 import 边）；`npm run build` 绿（188 sources，orphans 0）；
  `sbt compile` 绿。

## 4. 未做 / 边界

- 在产引擎（pid 70153/6138）零触碰；修复随下一波包生效（服务端静态字节为启动期快
  照，实测 `/js/chat.js` 需重启窗换血——与交付纪律一致）。
- pathless 灯箱回落腿保留不动（#491 主题三形态仍在；修复后它只服务「后端保存失败
  path=null」的真降级，不再吞掉正常发送的点击）。
- name（.png）与落盘扩展名（.jpg）错位是上传管线原有行为，与本故障无关，未动。
- 同聊天作者消息 777/788 显示 `.png` 落盘 `.jpg` 的展示名沿用原文件名，未改。

## 5. 关键假设

- 作者报障时未刷新页面（15:12 发图 → 15:16 报障，4 分钟窗口），点击的是 live
  pathless 行；若作者曾刷新后再点，恢复腿本应可开——与「还是点不开」的持续主诉对
  照，live 腿断链是唯一与全部证据自洽的解释（帧到达无消费、日志零失败、恢复腿完
  好）。
