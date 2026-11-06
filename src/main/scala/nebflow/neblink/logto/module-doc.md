# neblink / logto

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

身份登录协议：AC+PKCE、无头密码登录、静默重登、登录会话状态机。

**入口建议**：先读 [`LogtoPasswordFlow.scala`](LogtoPasswordFlow.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`LogtoAuthCode.scala`](LogtoAuthCode.scala) | Authorization Code + PKCE (RFC 7636/6749) client for the external OIDC provider (Logto) — Stage 2 of the Logto |
| [`LogtoPasswordFlow.scala`](LogtoPasswordFlow.scala) | Headless FIRST-PARTY password login against the deployed Logto tenant — 统一登录路径②（台账 #924，作者裁定 2026-10-26：「无头登录场 |
| [`LogtoSilentRelogin.scala`](LogtoSilentRelogin.scala) | Logto silent re-login (stage 2, 2026-08-28): the server rejected the long-lived deviceToken (HTTP 401 on the s |
| [`LogtoTransport.scala`](LogtoTransport.scala) | First-party Logto transport primitives + the device-registration call — the pieces of the retired RFC 8628 dev |
| [`PkceLoginSession.scala`](PkceLoginSession.scala) | Single-flight Authorization Code + PKCE login session (gateway memory only — restart during a login simply mea |

## 依赖的兄弟模块

[`neblink/core/`](../core/module-doc.md)、[`neblink/identity/`](../identity/module-doc.md)、[`neblink/relay/`](../relay/module-doc.md)
