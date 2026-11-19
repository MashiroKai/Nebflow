# neblink

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

设备互联子系统（客户端半边）：登录、中继、门户、好友、同步（9 个子模块）。


## 子模块（9）

- [`attachment/`](attachment/module-doc.md) — 好友附件协议：分块上传、预览字段、回执闸与能力判据。
- [`core/`](core/module-doc.md) — 设备互联核心：客户端/服务门面/领域模型/wire 协议/出站 HTTP/更新收敛。
- [`identity/`](identity/module-doc.md) — 设备身份与入网：Enrollment、自动入网护栏、设备凭据存储、失败诊断。
- [`logto/`](logto/module-doc.md) — 身份登录协议：AC+PKCE、无头密码登录、静默重登、登录会话状态机。
- [`misc/`](misc/module-doc.md) — 杂项动作：头像代理、后台任务取消、文件传输、附件文件 IO。
- [`portal/`](portal/module-doc.md) — 门户隧道：relay 帧桥、wire 编解码、上行背压、宿主面、一次性票据。
- [`relay/`](relay/module-doc.md) — 中继与发现：relay WS 隧道、peer 发现、presence 拨号、隧道诊断。
- [`social/`](social/module-doc.md) — 好友与跨设备邮件：好友服务、名册纯函数、设备邮件收件与 ack。
- [`sync/`](sync/module-doc.md) — 跨设备投影：后台子会话投影、智能体名称/头像收敛。
