# neblink / attachment

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

好友附件协议：分块上传、预览字段、回执闸与能力判据。

**入口建议**：先读 [`AttachmentPreviewContract.scala`](AttachmentPreviewContract.scala)（本模块最大文件）。

## 文件（6）

| 文件 | 说明 |
|---|---|
| [`AttachUpload.scala`](AttachUpload.scala) | 附件上传的**唯一**分块驱动（attachcl 批，2026-09-16）。 ## 为什么本件存在（同源性硬钉） |
| [`AttachUploadId.scala`](AttachUploadId.scala) | 附件上传 `uploadId` 的**形态闸** —— **唯一实现点**（attachid 批，2026-09-16）。 ==WHY（缺陷原文 = 独立复核位 A1）== |
| [`AttachUploadRegistry.scala`](AttachUploadRegistry.scala) | 在飞附件上传的**取消位**登记表（attachcl 批，2026-09-16）。 为什么需要它：网页腿的上传是**一个** HTTP 请求（整件 body → 网关 → 分块驱动）， |
| [`AttachmentAck.scala`](AttachmentAck.scala) | E4 回执闸（腿 A 补件批 4b1 · §F.1b）—— **唯一**的 fail-closed 判定点。 ## 为什么单列一处 |
| [`AttachmentCapability.scala`](AttachmentCapability.scala) | 好友附件**能力判据**（4b 腿 A-5）—— 裁定④「不加能力自报字段」下，客户端唯一可用的 判读面 = **路由存在性**（跨仓契约件 §G.2；本件是它在客户端腿的唯一实现点）。 |
| [`AttachmentPreviewContract.scala`](AttachmentPreviewContract.scala) | 附件**预览字段**契约声明件 —— 好友/群图片预览传输批 · **字段方言双兼容批**（2026-09-22）。 ## 为什么是「方言登记」而不是「一套键集」 |

## 依赖的兄弟模块

[`neblink/core/`](../core/module-doc.md)、[`neblink/social/`](../social/module-doc.md)、[`neblink/misc/`](../misc/module-doc.md)
