# neblink / identity

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

设备身份与入网：Enrollment、自动入网护栏、设备凭据存储、失败诊断。

**入口建议**：先读 [`DeviceCredentialStore.scala`](DeviceCredentialStore.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`CredentialDiagnostics.scala`](CredentialDiagnostics.scala) | 凭据面 / 登录腿的**失败分类**（缺陷 A：换号登录失败频发 + 报错只说文件名不说原因）。 上游只读侦察 `logdev-recon`（`.nebflow/reports/20260918_160923_logde |
| [`DeviceCredentialStore.scala`](DeviceCredentialStore.scala) | Long-lived per-device NebLink credential, persisted to `~/.nebflow/neblink/device.json` with owner-only access |
| [`EnrollGuard.scala`](EnrollGuard.scala) | Isolation guard for automatic enrollment (2026-09-11 作者裁定). Device ids are now machine-code derived (see `Devi |
| [`NeblinkEnrollment.scala`](NeblinkEnrollment.scala) | Enrollment persistence shared by every login path (device flow poll, AC+PKCE callback, silent re-login): devic |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`neblink/core/`](../core/module-doc.md)、[`neblink/logto/`](../logto/module-doc.md)、[`core/`](../../core/module-doc.md)、[`neblink/relay/`](../relay/module-doc.md)、[`neblink/social/`](../social/module-doc.md)
