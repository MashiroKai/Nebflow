# 修复批 #750 · nf-file 媒体面高载 503 硬失败 — REPORT

- 日期：2026-10-23
- 分支：`delegate/delegate-nebflow-85d4087a`（worktree：`.nebflow/worktrees/delegate-nebflow-85d4087a`）
- 触发实测：2026-10-22 18:52，load 28 / 五 JVM 并行构建——文档里点图报
  「Failed to render: HTTP 503」；同源旁证 18:42 relay uplink inbox full。

## 1. 归因链（修前形态）

点图渲染链：`renderFile`（fileViewers.js）→ image viewer → `nfViewerUrl` →
`nfViewerSource` → `fetchBlobWithProgress`（nfFileCache.js）→ `GET /api/nf-file?path&ticket`。
网关侧该腿**无限接纳**：算力被并行构建饿死时请求排队不前，上游（relay
ingress）先于网关放弃，向浏览器回**裸 503**；`fetchBlobWithProgress` 对
`!resp.ok` 直抛 `HTTP 503`，落 `renderFile` 的终态死文案「Failed to render: …」
——无退避、无重试、无手动出口，用户唯一出路是刷新整页。网关自己的 Scala 代码
全程不发 503（全仓 main 源零 `Status.ServiceUnavailable` 于该腿）——503 是
上游代答的，这决定了修复形态是「网关快速让路 + 前端吃可重试信号」。

## 2. 三点修复

### ① 文件腿过载退避语义（服务端）— 429 + Retry-After，永不裸 503

- 新件 `src/main/scala/nebflow/gateway/NfServeGate.scala`：文件腿在飞服务预算闸。
  - `tryAcquire` = AtomicInteger CAS 循环，**零 wall-clock 判据**（不读时钟、
    不等待、无 deadline）；过与不过当场出结果。
  - 预算 = `nebflow.json` `nfFile.serveConcurrency`（缺省 32；钳位 [1, 4096]；
    启动读一次 fail-safe 回缺省，与 `nfFile.ticketTtlSeconds` 同块同形态；不
    做热重读——面窄，预算改动随重启生效）。
- `NfFileRoutes.nfFileRoutes` 增加 `gate: NfServeGate = NfServeGate.fromConfig()`
  参数（缺省实参，GatewayMain/WebSocketRoutes 接线**零改动**）：
  - 闸位在参数快检（400/401，闸外零预算）之后、verdict/`verifyAndConsume`
    之前——**被 429 的请求不铸票不耗票**，获准请求照走既有 ticket-only 判据
    链（C1-5 单一权威），铸票机制与鉴权路径逐字节未动；
  - 过载回答 `nfOverloaded`：`429 + Retry-After: 1 + X-Nf-Reason: overloaded
    + Cache-Control: no-store` + 可读 body（`X-Nf-Reason` 沿用 `nfDenied`
    的 reason-head 同族约定）；
  - 预算释放点 = **响应体终点**（`body.onFinalize`：完整/出错/客户端断开三终
    态都触发）或 handler 失败（`attempt` 左支 release + rethrow）——预算约束
    的是并发字节推送，不是 handler 返回时刻。
- 429 经 relay 原样转发到浏览器（普通 http_response 形态），即过载语义在
  relay 远端同样成立。

### ② 前端渲染腿自动退避重试（恰一次）

`nfFileCache.js` `fetchBlobWithProgress`（全部 canvas viewer 取数单点，
attachmentPreview 等同函数消费方一并受益）：

- `429/503` = 可重试过载信号：按 `Retry-After`（缺席/非法 = 缺省 1s；恒定钳位
  [0.5s, 5s]——服务端建议时长，**非任何 deadline/超时判据**）短退避后**恰一次**
  重试；失败 body 先 cancel 归还连接。
- 第二败即定局（照旧抛 `HTTP <status>`）；其余状态码（4xx 家族）照旧直抛，
  重试面只对 429/503 开。

### ③ 查看器错误态「重试」按钮（手动重拉）

`fileViewers.js` `renderFile` 的 catch 单点：`renderViewerError` —— 保留原句
「Failed to render: …」（改 `textContent` 防 XSS）+「重试」键（既有 i18n
`canvas.retry`；`data-nf-retry` + `.viewer-retry-btn` 与 attachmentPreview
失败面板同族，CSS 零新增）。点击后**原 ctx 原样重入 `renderFile`**（票据链/
nfViewerUrl 重走，blob 账本命中则零取数直渲染），不刷新整页；pane 已断连
（标签已关）则静默不动作。

## 3. 红线核对

- **零超时**：闸内无时钟（CAS 即决）；前端退避只消费服务端 Retry-After 建议
  值（钳位常量），无任何 wall-clock 超时判据引入。
- **最小改动**：2 个前端文件 + 1 个 Scala 路由文件内修改 + 1 个新 Scala 件；
  接线零改动（缺省实参）；CSS/locale 零新增（复用既有键与类）。
- **鉴权零让步**：ticket-only 判据链、铸票路径、R4 无限读语义全部未动；
  429 先于 verdict/ticket，不产生任何凭据副作用。
- **引擎未重启**；全部工作在独立分支 worktree。

## 4. 测试

- `src/test/scala/nebflow/gateway/NfServeGateSpec.scala`（新，munit
  CatsEffectSuite，与 NfFileRoutesSpec 同款形态）：
  1. 超预算 429 全形态（Retry-After/X-Nf-Reason/no-store/body）+ drain 后恢复
     接纳 + `inFlightNow` 归零；
  2. 并发 burst（max=2 发 5）：恰 2×200 + 3×429，零 503，drain 后无泄漏；
  3. 400/401 快检不占预算（闸外）；
  4. 获准腿行为逐字节不变（byte-identical 200 / 404 家族，A13 同款）；
  5. `parseMaxConcurrent` 纯函数面 + 预算钳位。
- `tests/nffile-503-backoff.spec.mjs`（新，Playwright，nffile-three-tier 同款
  harness 纪律——真实模块链零 mock 替身、/sw.js 静态 404）：chromium + webkit
  双引擎四站——
  1. 503→自动重试→渲染（恰 2 请求）；
  2. 429+Retry-After:1→间隔 ≥~0.9s→渲染；
  3. 持续 503→恰 2 请求不成环→错误面板含「Failed to render: HTTP 503」+
     重试键→改路 200 点重试→渲染；
  4. 500（确定性）→恰 1 请求立即终态，面板/重试键照常。
- 既有回归面重跑：`NfFileRoutesSpec`、`NfTicketRoutesSpec`、
  `nffile-three-tier.spec.mjs`（fetchBlobWithProgress/attachmentPreview 行为）。
  结果见 §6。

## 5. 有意不做（申报）

- docx/xlsx/epub/pptx 四个 office viewer 自带独立终态面板（「Failed to render
  XLSX」族），非本批报障面（报错文案「Failed to render: HTTP 503」= renderFile
  共享 catch，已覆盖）；不扩 diff 面改动四处面板。
- 取数腿只对 HTTP 状态 429/503 退避；流中途断连（body 半截失败）不重试（会双
  报进度，越出本批面）。
- relay ingress 自身（裸 503 的直接产生地）不在本仓，无法改其语义；本批让网关
  在过载时**先于**上游放弃并给可重试回答，浏览器永远拿得到结构化信号。
- `scripts/check-circular.mjs` 当前在基线即红（popArtifacts/reference/msgQuote
  既有环，与本批无关；已在基线 commit 复验）。

## 6. 验证记录

| 面件 | 命令 | 结果 |
|---|---|---|
| 主+测编译 | `sbt Test/compile` | success（591 main + 810 test 源） |
| 新闸 spec | `sbt "testOnly nebflow.gateway.NfServeGateSpec"` | 6/6 PASS |
| nf-file 回归 | `sbt "testOnly nebflow.gateway.NfServeGateSpec nebflow.gateway.NfFileRoutesSpec nebflow.gateway.NfTicketRoutesSpec"` | 48/48 PASS（0 fail） |
| 前端验收（本批） | `node tests/nffile-503-backoff.spec.mjs` | ALL PASS——chromium + webkit 双引擎 4 站共 30 断言全绿 |
| 前端回归（档1-3/票据/缓存） | `node tests/nffile-three-tier.spec.mjs` | exit 0，83 PASS / 0 FAIL |
| 前端回归（office viewers） | `node tests/canvas-office-viewers.spec.mjs`（`NF_PPTX_FIXTURE` 指向主检出实物） | 17/17 通过 |
| 生产 bundle | `npm run build` | success（180 源全入图，orphans 0） |

- 格式化纪律：`sbt scalafmt` 在本仓基线**非** format-clean（批跑重排了 117 个
  存量源）——已全部 revert，仅保留本批文件的格式；`NfFileRoutes.scala` 中
  nfAuthcheckRoutes 区的两处存量重排亦已回退为基线原样（最小 diff）。
- `scripts/check-circular.mjs` 在基线 commit 即红（popArtifacts/reference/
  msgQuote 既有环，已在基线复验与本批无关）。

## 7. 涉及文件

| 文件 | 变更 |
|---|---|
| `src/main/scala/nebflow/gateway/NfServeGate.scala` | 新增：文件腿过载准入闸 |
| `src/main/scala/nebflow/gateway/NfFileRoutes.scala` | gate 参数 + nfOverloaded(429) + 预算生命周期 |
| `src/main/resources/web/js/nfFileCache.js` | fetchBlobWithProgress 过载退避（恰一次） |
| `src/main/resources/web/js/fileViewers.js` | renderViewerError：错误面板 + 重试键 |
| `src/test/scala/nebflow/gateway/NfServeGateSpec.scala` | 新增：闸的路由级 + 纯函数测试 |
| `tests/nffile-503-backoff.spec.mjs` | 新增：前端三腿验收（双引擎四站） |
| `reports/20261023_101500_nf-file-503-backoff/REPORT.md` | 本报告 |
