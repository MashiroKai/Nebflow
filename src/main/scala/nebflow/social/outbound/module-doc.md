# social / outbound

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

社交通道出站腿（飞书/微信）。

**入口建议**：先读 [`RichFeishuAdapter.scala`](RichFeishuAdapter.scala)（本模块最大文件）。

## 文件（17）

| 文件 | 说明 |
|---|---|
| [`AskPrompt.scala`](AskPrompt.scala) | Channel-agnostic question/answer model for AskUser-style prompts (askuser batch, 2026-10-01). |
| [`ChannelAdapter.scala`](ChannelAdapter.scala) | Channel adapter seam (design card section 2). Feishu is an IMPLEMENTATION of this trait, not the place the spl |
| [`ContentClass.scala`](ContentClass.scala) | Content-class split for the outbound rich-content layer. Source of truth: `.nebflow/Spec/20260930_203506_socou |
| [`FileLeg.scala`](FileLeg.scala) | 文件腿直推策略（pop-file-leg batch, 2026-10-21；作者令「这样你使用 pop 把这个 文件发给我，就自动传过来了呀」）：PDF/办公件/压缩包类文件产物在社交出站面 |
| [`FileLongShot.scala`](FileLongShot.scala) | 文件类产物（md/markdown/html/htm）的社交出站「单张长图」腿（pop-social-files batch, 2026-10-20）。 |
| [`OutboundPayload.scala`](OutboundPayload.scala) | Channel-agnostic outbound payloads and call intents (design card section 2). Pure data: no IO, no SDK, no logg |
| [`OutboundPlanner.scala`](OutboundPlanner.scala) | The pure planner: class + payload + gate readings -> call intents. Zero IO, zero SDK, zero logger. That is wha |
| [`OutboundRenderer.scala`](OutboundRenderer.scala) | Rendering-pipeline seam (design card sections 3 and 5.2). The definition layer owns the SHAPE of this seam, no |
| [`OutboundText.scala`](OutboundText.scala) | Channel-agnostic TEXT conversion (design card section 1 text class + the author's judge criteria for the text  |
| [`OutputBlock.scala`](OutputBlock.scala) | `<output>` 用户可见块提取 — 渠道出站腿的**单一实现源**（output 块纪律渠道 接线批，2026-11-01；作者令「微信端和飞书端收到的消息也要做 output 块剥离， |
| [`RichFeishuAdapter.scala`](RichFeishuAdapter.scala) | Feishu outbound leg for rich content (richcontent batch, 2026-09-30). Three kinds, three SDK legs, named expli |
| [`RichKind.scala`](RichKind.scala) | Rich-content kinds of the outbound layer (richcontent batch, 2026-09-30). Scope decision: this file carries ON |
| [`RichPlanner.scala`](RichPlanner.scala) | Call plan for the FEISHU outbound leg of rich content (richcontent batch, 2026-09-30). Pure data + pure builde |
| [`RichProduct.scala`](RichProduct.scala) | The channel-agnostic rich product of one turn (weixin-media batch, 2026-10-08; batch carrying since the pop ba |
| [`RichRenderer.scala`](RichRenderer.scala) | Server-side real rendering for outbound rich content (richcontent batch, 2026-09-30). |
| [`SegmentPacer.scala`](SegmentPacer.scala) | 逐段即时推送批（2026-10-10）：渠道出站文本段的节流队列。 背景：渠道出站腿（[[nebflow.social.FeishuBridgePlugin]] / |
| [`WebPageShot.scala`](WebPageShot.scala) | 网页类产物（html/htm）的出站整页截图（social-webshot batch, 2026-10-09）。 背景（用户实况 2026-10-09）：Pop 出的 `.html` 框图落在两张权威表之外 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/tools/`](../../core/tools/module-doc.md)、[`social/`](../module-doc.md)
