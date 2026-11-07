# perception

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

感知守护进程：邮件/计费/Otp 等后台感知能力。

**入口建议**：先读 [`PerceptionService.scala`](PerceptionService.scala)（本模块最大文件）。

## 文件（14）

| 文件 | 说明 |
|---|---|
| [`BillingClassifier.scala`](BillingClassifier.scala) | L0 账单分类（r2 §5.1 裁决 5 的「账单正则」腿）：新邮件 → 当日账单缓冲。 判据（命中即账单类，旁路 L1 入缓冲、日结攒批零 UI）： |
| [`DraftCodec.scala`](DraftCodec.scala) | 发送草稿编解码（r2 §7.2，落盘形态；UI 永不露出——画布面走结构化编辑器）。 文件 = front-matter + 正文。front-matter 字段： |
| [`MailClient.scala`](MailClient.scala) | 极简 IMAP/SMTP 客户端（零第三方邮件依赖；标准库 TLS socket 直连）。 范围（骨架批，深度优先核心链路）： |
| [`MailImportanceJudge.scala`](MailImportanceJudge.scala) | L1 LLM 重要性判定腿（r2 §5.2「一次调用定生死」，r3 未推翻；作者构想 1 逐字 「新邮件先用代码级自动过滤……然后一次 LLM 调用判断这封邮件是否需要投递」）。 |
| [`MailboxPresets.scala`](MailboxPresets.scala) | One mailbox provider preset: the TLS endpoints a domain maps to. |
| [`OtpExtractor.scala`](OtpExtractor.scala) | OTP（验证码）提取：新邮件正文/主题 → 单码片数据面（r2 §6 / r3 裁决 2）。 纯函数层，无 IO：PerceptionService 对每封新邮件调用 [[extract]]，命中 = |
| [`PerceptionClient.scala`](PerceptionClient.scala) | 引擎 → 统一 daemon 管理进程的客户端腿（统一 daemon 批 2026-10-29）。 端点经 [[PerceptionDaemonDock]] 的 dock 腿登记（`bind`：port + token） |
| [`PerceptionDaemonMain.scala`](PerceptionDaemonMain.scala) | 统一 daemon 管理进程入口（统一 daemon 批 2026-10-29；作者裁定逐字 「独立daemon吧 正好和我们心跳进程合并 那就是管daemon的」）。 |
| [`PerceptionDaemonRoutes.scala`](PerceptionDaemonRoutes.scala) | 统一 daemon 管理进程的 HTTP 面（统一 daemon 批 2026-10-29）。挂载于 daemon 进程 `127.0.0.1:<port>`（[[PerceptionDaemonMain]]），**全端 |
| [`PerceptionEngineLeg.scala`](PerceptionEngineLeg.scala) | 统一 daemon 管理进程 → 引擎进程的出域腿（统一 daemon 批 2026-10-29）。 daemon 进程对引擎的全部依赖收敛在这三条窄端口上（引擎侧由 |
| [`PerceptionModel.scala`](PerceptionModel.scala) | 感知域模型（r2 规格条目化面板 §9 + 状态机 §4.5 四色投影 + r3 极简修订）。 面板契约（§9.2）：条目 = id/icon/name/state/summary/detail；本批邮件 = |
| [`PerceptionPanelRoutes.scala`](PerceptionPanelRoutes.scala) | 感知面板 API 处理表（统一 daemon 批 2026-10-29 自 PerceptionRoutes 整体迁入， 请求/响应形状逐字节保持——单一实现防双端漂移）。 |
| [`PerceptionService.scala`](PerceptionService.scala) | 感知守护进程（统一 daemon 管理进程批 2026-10-29：作者裁定「独立daemon吧 正好和我们心跳进程合并 那就是管daemon的」——感知五循环自引擎进程抽出， |
| [`PerceptionStore.scala`](PerceptionStore.scala) | 感知域文件持久化（r2 §13 文件布局，数据根随实例： `<dataRoot>/perception/`）： |

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)
