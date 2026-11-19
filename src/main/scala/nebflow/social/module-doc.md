# social

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

社交通道：飞书/微信出站与桥接。

**入口建议**：先读 [`WeixinIlinkBridgePlugin.scala`](WeixinIlinkBridgePlugin.scala)（本模块最大文件）。

## 文件（15）

| 文件 | 说明 |
|---|---|
| [`FeishuAskCard.scala`](FeishuAskCard.scala) | Feishu card face for AskUser prompts (askuser batch, 2026-10-01). Everything here is a TOTAL FUNCTION over pla |
| [`FeishuBridgePlugin.scala`](FeishuBridgePlugin.scala) | Feishu bridge adapter (feishubridge batch, 2026-09-25) — the consumer side the upstream plan card (`20260925_1 |
| [`FeishuChannel.scala`](FeishuChannel.scala) | Feishu channel — the LIVE half (feishu-chan, 2026-09-23). Route (upstream plan card `20260923_093043_feishu-pr |
| [`FeishuCredentials.scala`](FeishuCredentials.scala) | Feishu credential resolution — the read-only load point for the long-connection channel (feishu-chan, 2026-09- |
| [`FeishuMessage.scala`](FeishuMessage.scala) | Pure mapping layer for the Feishu channel (feishu-chan, 2026-09-23). Everything in this object is a TOTAL FUNC |
| [`FeishuOutboundAdapter.scala`](FeishuOutboundAdapter.scala) | Feishu implementation of the channel-agnostic outbound layer (socoutbound batch, section 2 of the design card |
| [`FeishuScanBind.scala`](FeishuScanBind.scala) | Feishu scan-bind (feiscanbind batch, 2026-09-27) — the "create the app by scanning a QR code" main path, on to |
| [`InboundParse.scala`](InboundParse.scala) | Channel-agnostic inbound STRUCTURE parser (inbound-parse batch, 2026-10-01). Everything here is a TOTAL FUNCTI |
| [`SocialChannels.scala`](SocialChannels.scala) | Social interface channels — domain layer for the REST face (socpanel batch, 2026-09-19; design = `socremote-de |
| [`WeixinAskLeg.scala`](WeixinAskLeg.scala) | The weixin numbered-reply ask face (askuser-remote P1, 卡 B / 拍板 B-1…B-4). iLink 2.4.9 carries passive TEXT onl |
| [`WeixinBindAutoWire.scala`](WeixinBindAutoWire.scala) | 绑定即通 auto-wire (weixin bind-through batch, 2026-10-11; transactional hardening batch, 2026-10-20) — the two fo |
| [`WeixinIlinkBridgePlugin.scala`](WeixinIlinkBridgePlugin.scala) | Weixin iLink bridge adapter — the side-car seam (wechat iLink batch). The iLink protocol is deliberately NOT i |
| [`WeixinIlinkScanBind.scala`](WeixinIlinkScanBind.scala) | Weixin iLink scan-bind (weixin-scanbind batch, 2026-10-03) — the "connect by scanning a QR code" main path for |
| [`WeixinIngressToken.scala`](WeixinIngressToken.scala) | The weixin-ilink ingress token — the shim's credential for the ingest leg (weixin-inbound batch, 2026-10-07).  |
| [`WeixinOutboundAdapter.scala`](WeixinOutboundAdapter.scala) | Weixin outbound leg — engine → side-car send face (weixin-outbound batch, 2026-10-07). The receiver-side count |

## 子模块（1）

- [`outbound/`](outbound/module-doc.md) — 社交通道出站腿（飞书/微信）。

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`social/outbound/`](outbound/module-doc.md)、[`core/`](../core/module-doc.md)、[`bridge/`](../bridge/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)、[`gateway/`](../gateway/module-doc.md)
