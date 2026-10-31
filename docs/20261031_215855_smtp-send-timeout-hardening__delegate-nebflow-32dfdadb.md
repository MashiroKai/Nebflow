---
type: audit-note
status: published
date: 2026-10-31
scope: 邮件发送超时调查修复批（作者 10-31 21:42 报「Host did not answer in time」怒评批）——超时出处定位 / 根因对证 / 分级超时+安全重试+人话文案三修复 / mock SMTP 造例验证
---

# 邮件发送超时调查修复记（2026-10-31）

关联：[[index]]、[[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]]（感知域先例批）。

## 出处定位（谁等谁、阈值、重试）

文案「Host did not answer in time」**全仓与全 git 历史零命中**（含 vendor/locales/测试）。判定：非本仓文案，要么是对端 SMTP 服务器的回执原文被透传，要么是用户对裸超时报错的转述。本仓真实超时判定面只有两处：

- IMAP 腿（`MailClient.connectImap` / `ImapSession`）：SoTimeout 15s，超时报文案已人话化（「IMAP 响应超时 · 服务器长时间无响应…」，10-28 批修）。
- SMTP 腿（`MailClient.send`）：**整个会话统一 SoTimeout 20s、零重试**；超时异常原文（`SocketTimeoutException: Read timed out` 类）经 `SendFailure.Network(msg)` 插值进用户可见文案——与报障的「裸报错」体感吻合。

## 根因对证（逐条）

1. **超时阈值过短（命中，主因）**：20s 统一超时覆盖 DATA 阶段——附件写出后等服务器最终回执时，服务商侧内容扫描/入队确认对大附件普遍 >20s。此时信可能已入库，客户端却报失败：误判 + 裸文案，正是怒评来源。
2. **发送管道单点无重试（命中）**： greeting/EHLO/AUTH 任一瞬时抖动（恰 20s 内无解）直接失败；`readable` 旧文案还谎称「稍后自动重试」——实际从未重试。
3. **DATA 最终回执被丢弃（命中，次生缺陷）**：`_reply` 直接作废，服务器在报文提交后的 5xx 拒收（内容策略/配额）会被报成「发送成功」。
4. WS 链路断导致无回执（排除）：发送腿是前端 fetch → 后端阻塞 SMTP 对话，无 WS 依赖；WS 断不影响 REST。
5. 服务器侧真慢（无法证伪，无生产度量权限）：以「慢回执」造例覆盖，无论服务器为何慢，分级超时+正确分类都成立。
6. Clash 代理干扰（无法复现排除）：本机出站经 Clash 确会放大首段握手时延，但它只影响 connect/EHLO 阶段（仍 20s 充裕），不改变本批结论。

## 修复（`MailClient` + `PerceptionModel.SendFailure`）

- **分级超时**：`dataTimeoutMsFor`——DATA 阶段读超时 60s 底 + 每 100KB 载荷 +1s，封底 300s；握手阶段维持 20s。经 `onDataPhase` 回调在 DATA 前切换 socket SoTimeout。
- **失败三分类**：`Timeout`（DATA 写出前超时，零投递风险）/ `Ambiguous`（DATA 写出后未获最终回执，**可能已投递**）/ `Rejected` 按码分（4xx 瞬时可重试、5xx 确定性）。DATA 回执开始校验：2xx 成功、其余判拒收（修缺陷 3）。
- **安全重试**：`sendWithRetry` 至多 2 次新鲜连接，只重试 Timeout/Network/4xx；Ambiguous/AuthFailed/5xx 一律不重试——**幂等红线 = 盲重试不得造成重复投递**；重试复用同一 Message-ID（服务端可去重）。用户侧重发路径不变：失败时草稿保持未发送态，画布「发送」可再点。
- **人话文案**：Timeout「发送超时…已自动重试仍未成功，请稍后重发」；Ambiguous「发送结果不明…邮件可能已发出，请勿立即重发，先确认收件人是否收到」；Network「网络连接异常…请检查网络后重发」；不再透传裸异常类名。

## 验证

`MailClientSendSpec`（新，12 例）：脚本 mock SMTP 服务器（loopback 明文 socket 直驱 `smtpTransact`）——慢回执 1.2s 落在分级窗口内成功（握手超时 400ms 作验红对照：旧统一超时必败）；慢回执超限归 Ambiguous 且禁重试；握手静默归 Timeout 可重试；535/550/451/552/452 各码分类；`dataTimeoutMsFor` 分级数学；`sendWithRetry` 重试边界（Ambiguous/AuthFailed 一次即停）；readable 三态文案无裸异常。

回归（sbt test 全量，-Xmx3g/MaxMetaspace 1536m 起跑——首跑撞 Metaspace OOM 为宿主资源面）：**8465 例，8447 过，13 失败全部为存量**（RemoteableDeviceParamSpec / UpdateOrchestratorSpec / PresetCatalogInToolDocSpec / NeblinkPresenceDialBudgetSpec 四套件，locale 键集与 Delegate schema 漂移类，与感知域零耦合且其输入文件在本批 base→HEAD diff 中字节不变——`git diff e633d88b6..HEAD` 恰为本批 4 文件，故失败与批无关）；感知域三 spec 81/81 全绿，全量跑中感知域零失败（NeblinkPresenceDialBudget 在隔离重跑中通过，属满载并行下的时序 flake）。

## 诚实边界

此故障在以下网络条件下仍会**真失败**（非误报）：对端 SMTP 服务器最终回执慢于分级上限（300s，超大附件/服务商队列拥塞）、长时物理断网（connect 即败，2 次尝试均败）、或对端对同一 Message-ID 重投不去重且首次 DATA 实际已接收（Ambiguous 只能提示用户人工确认，无法全自动闭环）。Ambiguous 态的终局解法是发件箱 IMAP 回读比对（已发文件夹出现同 Message-ID 即确认投递），留作后续批。
