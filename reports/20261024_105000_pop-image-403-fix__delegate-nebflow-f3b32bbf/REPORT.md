# 修复批 · Pop 图片「Failed to render: HTTP 403」— 报告

批次：2026-10-24 10:29 作者报障批（delegate-nebflow-f3b32bbf）

## 403 判据分支根因（一句话）

前端取数腿（`nfViewerSource → fetchBlobWithProgress`）401/403 **直抛死文案**：两个 403 源——① 网关侧引擎重启/票过期后页内缓存票变废票（`X-Nf-Reason: invalid-ticket`，复现：`NfTicketStore` 内存票仓随重启清零，bogus 票 403 invalid-ticket 实测）；② **隧道面中继门**（link-server `require_user`，routes.rs:559/590）在中继平面凭据死时对 `/api/nf-file` 直接回 403 `{"error":"Missing token"}`（JSON 体、无 `X-Nf-Reason`，请求根本不到网关——本批 curl 实测 neblink 域 `/api/nf-reach` 即此形状），与 localhost 路径的网关判据拒**同文不可分**，而 `<img>` onerror 腿的 reMint 自愈够不到 fetch 抛出面，遂成「老是出现、重试无用」的死面板。

## 修复点

| 面 | 改动 | 文件 |
|---|---|---|
| 前端取数腿自愈 | `nfViewerSource`：401/403 ⇒ 重铸一票重试**恰一次**（T3 语义，同 image.js `<img>` 腿）；铸票失败（无票返回）即终态，不成环 | `web/js/nfFileCache.js` nfViewerSource |
| 升级路径（relay 面） | `fetchBlobWithProgress` 403/401 + JSON 体 + 无 `X-Nf-Reason` ⇒ 标记 `relayGate`（与网关 text/plain + X-Nf-Reason 形状机械可分）⇒ 交 `sessionGate` 统一重认证伞（可见横幅 + 自愈 reload），不再静默死亡；**不烧重铸票**（铸票过同一道门） | `web/js/nfFileCache.js` fetchBlobWithProgress |
| 判据拒可见 | 错误 message 携带 `X-Nf-Reason`：`HTTP 403 (invalid-ticket)` / `(credential-path)`——用户可读的退化文案 | 同上 |
| 服务端日志可见性 | `/api/nf-file` 全族 403（verdict 判据拒 + 票拒）写 1 行可 grep `nf-file denied: status=… reason=… path=… remote=…`（`nebflow.nf-file` logger）；ticket 本体不落日志（T2 凭据纪律）；401 缺票刻意不记（F2/F5 降级的设计信号，会话级噪音） | `gateway/NfFileRoutes.scala` |
| 隧道面等权（钉） | 网关 nf-file 判据**零隧道依赖**（TunnelOriginMark 在役零消费，代码路径实证）——带 tunnel-mark 头的请求与直请求同判：Scala spec ④ 钉死（localhost 安全性零削，隧道面无放宽） | `NfFileDenyLogSpec` |

## Spec 结果

**新增 `tests/nf-ticket-403-heal.spec.mjs`（chromium + webkit 双引擎，40/40 ALL PASS）：**
- 站1 网关 403(invalid-ticket) ⇒ 自动重铸重试 ⇒ 200 渲染成功（nf-file 恰 2 请求、铸票恰 2 次）
- 站2 持续 403(invalid-ticket) ⇒ 恰两次即终态，文案 `Failed to render: HTTP 403 (invalid-ticket)` + 重试键在位，**不拉** sessionGate 横幅
- 站3 relay 门形状 403(JSON Missing token) ⇒ nf-file 恰 1 请求、铸票 0 追加、**sessionGate 横幅拉起**（升级可见）
- 站4 确定性 403(credential-path) ⇒ 重铸恰一次后定局，reason 可见，不成环

**新增 `src/test/scala/nebflow/gateway/NfFileDenyLogSpec.scala`**：票拒/判据拒各恰 1 行 deny 日志（status/reason/path/remote 四键，ticket 不落日志）+ 成功腿零日志负控 + tunnel-mark 等权（200 / 同 reason 403）——sbt testOnly 结果见交付 commit message。

**回归**：`nffile-503-backoff`（过载退避，含 `Failed to render: HTTP 503` 文案钉）ALL PASS；`nffile-three-tier`、`card-ticket-credential`（44/44）、`canvas-video-viewer` ALL PASS。

## 现网备注（不属本仓改动面）

- 隧道面中继门 403 的根治在 link-server（`~/.stand/projects/link-server/src/routes.rs` `require_user`）：#520 滑动准入（refresh 行在场即放）已落地，残留面 = refresh 行死亡后的 UX 闭环——本批前端升级路径（横幅 + reload 重进 console_shell 登录腿）即其收口。
- 「控制台突然断连、无法从官网进入」（10:2x）同链：中继平面凭据/隧道帧链路病兆，非宿主网关判据。
- checkJs 存量漂移：main 基线 257 vs 实计 260——`nearpath.js`（console-fastpath 批 landing）带 3 错未重锚基线，**先于本批存在**；本批触碰件零新增。

## 关键文件

| 面 | 路径 |
|---|---|
| 服务端判据 + 日志 | `src/main/scala/nebflow/gateway/NfFileRoutes.scala` |
| 服务端日志 spec | `src/test/scala/nebflow/gateway/NfFileDenyLogSpec.scala` |
| 前端取数腿自愈/升级 | `src/main/resources/web/js/nfFileCache.js`（nfViewerSource / fetchBlobWithProgress） |
| 前端验收 spec | `tests/nf-ticket-403-heal.spec.mjs` |
| 重认证伞 | `src/main/resources/web/js/sessionGate.js`（零 import，本批消费方） |
| 中继门（他仓） | `~/.stand/projects/link-server/src/routes.rs:558-591,3712` |
