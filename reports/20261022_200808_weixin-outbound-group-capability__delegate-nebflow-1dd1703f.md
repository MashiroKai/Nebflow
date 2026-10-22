# 微信腿出站媒体面·能力判定报告（多图合并 + 图文同发）

台账 #756 · 作者 2026-10-22 晚两连实测裁定（逐字）：

> ①「微信上一起发图片好像不会组合一起发送 而是分开发送」——多图应组合发送
> ②「改动一下 微信上发文件和图片 如果没有文字内容 要等一下文字再一起发 不要空发图片和文件」——媒体与文字合流，不许空发媒体

本报告 = 任务书第 1/2 步（考古 + 能力判定）。第 3 步实施按作者批复执行。

---

## 1. 考古结论：出站通道四层现状

### 1.1 线格式层（官方插件 2.4.9 源码，盘上实证）

官方插件包在盘：`~/.openclaw/npm/projects/tencent-weixin-openclaw-weixin-7783ac86ba/node_modules/@tencent-weixin/openclaw-weixin/src/`。逐字证据：

- **消息类型枚举（`src/api/types.ts:70-79`）**：`MessageItemType = { NONE:0, TEXT:1, IMAGE:2, VOICE:3, FILE:4, VIDEO:5, TOOL_CALL_START:11, TOOL_CALL_RESULT:12 }`。**没有「多图合并/媒体组」专门类型**——"组合"在协议里只能是：一条 sendmessage 的 `item_list` 数组里放 N 个 item。
- **`item_list` 是数组（`types.ts:206`）**：`WeixinMessage.item_list?: MessageItem[]`——proto 层面允许多条目；入站方向 msgloop 也按数组消费（`msgloop.mjs:207` 逐 item 扫描）。**但出站方向官方客户端从不发多条目**：`src/messaging/send.ts:188-191` 注释逐字「Each item is sent as its own request so that item_list always has exactly one entry」——`sendMediaItems` 把 caption 文字与媒体拆成**两次独立 sendmessage**（文字先、媒体后，各持 client_id）。服务端是否接受多条目 item_list，官方源码无任何正反证据（未证实约束 vs 防御性写法，不可判）。
- **caption 能力（图片消息能否带文字）**：线面上=同一条消息的 item_list 里 text_item+image_item 并存（未证实渲染）；官方实现面=文字单独先发（已证实可达）。本仓 sidecar **已镜像官方 caption 流**（`tools/weixin-sidecar/send.mjs:594-610`：text 先单独 sendmessage，媒体随后）——但**引擎侧从未用过**：`WeixinOutboundAdapter.buildMediaSendJson`（`WeixinOutboundAdapter.scala:226`）刻意不带 text（注释：「the turn's text already rides its own text send」）。
- **流式状态位**：`MessageState = { NEW:0, GENERATING:1, FINISH:2 }` 官方出站恒用 FINISH，GENERATING 在出站路径零使用——「先发半条再补全」的流式合并不存在现成官方先例。

### 1.2 引擎层（`WeixinIlinkBridgePlugin.scala`）

- 轮终 `done` 臂（:991-1006）：`richLeg（媒体先）*> textLeg（尾段文字后）`——**媒体先行、正文殿后是现行刻意序**（pop batch-media fix 注释逐字）。作者令②推翻的正是这个次序。
- 媒体记忆在 `toolEnd`（:974-990，只记不发），冲刷统一在 `done`——**结构性合流窗口已经存在**：媒体天然等到轮终（通常远超任务书设想的 5-10s），不存在「媒体先到就先发、文字后到」的裸态。作者体感的「空发」来自 done 臂内部：图片逐张出网（每张一次 /send＝getuploadurl+CDN 上传全管线，秒级到数十秒），尾段文字排在全部图片之后 ⇒ 文字落后媒体数十秒＝「图片和文件空发了」。
- 多图现行为（`deliverRichMedia` :1267-1353 + `deliverRichAll` :1361）：每个 RichProduct（一次 Pop 组卡）的每个 image item **各走一次** `outboundMedia`（`image_path` 腿）＝微信里 N 条独立消息。作者令①指认的「分开发送」即此。
- 相邻面（各批次刻意序，本批默认不动、列出待裁决）：
  - ask 附件腿（:687-719）：附件媒体**先**发、问卡文字**后**发（askuser-media-split 批 2026-10-09 刻意序）；
  - 长图伴行注（`deliverLongShotMedia` :1393-1404）：长图先发、原件路径注后发（刻意）；
  - 逐段即时推送（`flushSegment` :1032）：文字段轮中即推——文字段天然先于 done 冲刷的媒体，次序无需动。

### 1.3 sidecar 层（`tools/weixin-sidecar/send.mjs`）

`/send` 契约：`text` / `image_path` / `file_path` 三选一以上、`image_path` 与 `file_path` 互斥（一消息一媒体，官方纪律）；媒体管线＝getuploadurl → AES-128-ECB → CDN POST → x-encrypted-param → sendmessage，一 item 一请求。**无任何组面/多图面**；重试边界＝仅网络类失败（§0.4 预算），协议拒绝永不重试。

### 1.4 引擎↔sidecar 超时预算

文字缝 40s、媒体缝 240s（`WeixinOutboundAdapter.scala:70-79`，240s=单图管线最坏 214s+余量）。**组面需随图数缩放**（N 图最坏 ≈ N×214s+尾量），实施时按 N 计算读超时。

---

## 2. 三方案能力判定

### 方案 A：原生多图合并消息（一条 sendmessage 携 N 个 image_item，text 可作同消息 caption）

| 维度 | 判定 |
|---|---|
| 线格式 | **可行**：`item_list` 本是数组，proto 无单条目限制、无条数上限记载；组＝N image_item 同消息，无需新类型 |
| 官方先例 | **零先例**：官方客户端 2.4.9 出站恒单 item 单请求（send.ts 逐字），拒绝/接受的线上证据两无 |
| 服务端接受度 | **未证实**（离线不可证；mock 只能证我们的实现，证不了真服务器） |
| 微信端渲染 | **未证实**（接受了是否按「组合」渲染，只有作者真机实测能定） |
| 失败面 | 若服务端拒绝＝sendmessage ret≠0＝协议类确定性失败，sidecar 现行边界即不重试、快速返回——**可检测、可自动回退** |

**判定：线面可行、线上行为待实测 ⇒ 实施形态＝「探针＋自动回退」**：组发先试（一条 sendmessage 携 text_item?+N image_item），被拒（ret≠0）即自动回落官方逐条序（caption 文字先、逐图一条），对调用方永远「已投递」，回退零用户可见失败；env 总闸可一键关 A 面。剩余风险仅一条：**服务端接受但渲染异常**（如只显首图）——只有作者实测能定，实测不佳即关闸回 B 面。

### 方案 B：出站合流（引擎侧次序+成组）

考古修正任务书前提：**合流窗口已结构性存在**（媒体 toolEnd 记忆、done 冲刷，等满整轮），无需新造 5-10s 定时器。B 的实际工作量＝三件事：

1. **次序翻转**：done 臂改文字尾段先出、媒体后出（作者令②「等一下文字再一起发」的正解；文字不落后于媒体）；
2. **短尾文字依附**：尾段 ≤4000 字符（现行 `FullTextThreshold` 一档）时随组消息作 caption 依附（图文同发＝令②「一起发」的正解）；长文轮维持全文拉取流（摘要+指令先出），图片随后无 caption；
3. **图片成组**：同一组卡内连续 image items 合并为**一次** /send 组面调用（组内成组、组间按 toolEnd 序、组占单一 pacer 槽与文字严格串行）；文件/长图保持逐条现行腿。

纯媒体无文字轮＝照发（轮终即窗口终点，无文字可等，不空等）；文字先到媒体后到＝现行形态天然满足。

### 方案 C：多图拼长图

仓内既有「长图能力」＝`FileLongShot`（md/html **文档**转单张长图 PNG，node playwright），**不是图片拼接器**——拼 N 图为一张需新造拼接面（sidecar 零 npm 依赖约束下还得自写 pure-JS 拼接或引入依赖），且清晰度损失、动图失效、N 图变一屏长卷阅读性差。**判定：不推荐立项**；仅当 A 实测失败且作者坚持「必须单条消息呈现多图」时再议。

### 结论建议

**A（探针＋自动回退）＋ B 组合**＝任务书预授权路径：B 的次序翻转与成组是确定性修复（作者令②即刻生效）；A 的组面在此之上把多图合成一条（作者令①的最优解），实测不佳可回退、可关闸，无回退悬崖。C 不立项。

---

## 3. 实施草案（获批后执行）

| 层 | 改动 |
|---|---|
| sidecar `send.mjs` | 新增组面：body 增列 `images:[<abs path>…]`（与既有单图腿并存；N≤20=PopTool.MaxPopFiles 对齐）；管线=逐图 getuploadurl+CDN 上传 → **一条** sendmessage 携 [text_item?]+N image_item（text 在场即 caption 依附）；sendmessage ret≠0 ⇒ 自动回落官方逐条序（文字先、逐图一条，全新管线不复用参数，零假设）；响应增 `group:true|false` 读数；env 总闸 `WEIXIN_SIDECAR_MEDIA_GROUP=off` 关 A 面 |
| 引擎 `WeixinOutboundAdapter` | 新组缝 `sendMediaGroupLive`（to, images, caption）→ SendResult；组读超时＝240s×(N+1) 且封顶、可配；body 构造纯函数入离线 spec |
| 引擎 `WeixinIlinkBridgePlugin` | done 臂翻转：尾段文字先（≤阈值走 caption 依附组面，长文走既有拉取流）、媒体后；`deliverRichMedia` 连续图片段并组（同组卡内），文件/长图/ask 附件腿逐字不动；失败保底与回退读数原样保持 |
| spec | sidecar：组面桩测试（stub iLink 记录请求序列——组发一条/回退逐条/总闸关/ret≠0 回退/超时缩放）+ 三时序（媒体先文字后=done 合流、文字先媒体后=次序保持、纯媒体无文字=轮终照发）；Scala：done 臂次序断言（文字不落后媒体）、caption 依附断言、组调用次数断言。全 mock，不真发微信 |

## 4. 待裁决点（AskUserQuestion 同步提请）

1. 方案批复：A+B 组合（建议）/ 仅 B / B+C；
2. ask 附件腿是否一并翻转次序（现=附件先、问卡后，2026-10-09 刻意批）：保持 / 一并改为问卡先、附件后。

---

## 5. 作者批复与实施记录（2026-10-22 20:12）

作者裁决：**① A+B 组合（推荐）——照实施；② ask 附件腿保持现状（2026-10-09 刻意序不动，本批只动聊天回复轮终臂）**。

实施落点（与 §3 草案一致，两处小收紧）：

- **sidecar `tools/weixin-sidecar/send.mjs`**：`images[]` 组面（绝对路径数组，与 `image_path`/`file_path` 互斥；逐路径沿用单图腿校验）→ 上传管线抽为 `uploadMediaItem` 共用（单图/组面同源，线形不可漂移）→ 组探针=一条 sendmessage 携 `[text_item?]+N image_item`；ret≠0 或网络预算耗尽 ⇒ 自动回落官方逐条序（caption 文字先、逐图全新管线、各持 client_id、首败即停）；`WEIXIN_SIDECAR_MEDIA_GROUP=off` 直落逐条（探针与上传都不跑）；响应增 `group:true|false`，部分送达 502 带 `delivered_count`。
- **引擎 `WeixinOutboundAdapter`**：`SendGroup` 缝 + `buildMediaGroupSendJson`（键=to/images[/text][/account]，缺省键绝不为 null）+ `groupReadTimeout(n)=min(240s×(n+1), 30min)` + `sendMediaGroupLive`；失败 WARN digest 增 images 臂（`<首路径>…(N images)`）。
- **引擎 `WeixinIlinkBridgePlugin`**：done 臂收口 `flushTurnEnd`——文字先行（次序翻转，媒体殿后）；短尾（≤4000 一档）+ 首个交付段为 ≥2 图组 ⇒ caption 依附（图文同发，无独立文字消息）；长文轮维持拉取流先出、组后出无 caption；连续图片并组 `ImageRun`，单图与非图项逐字保留旧腿（`[图片发送失败 code=…]` 等措辞不变）；组腿 total 化（raise 合成为失败读数）⇒ caption 经文字缝先行找回 + 一次性计数保底注（`[N 张图片发送失败 code=…]`）；逐段即时推送、ask 附件腿、长图伴行注、interrupted/retry 清柜全原状。
- **spec**：sidecar `send.test.mjs` 组面七件（一条消息载 N 图序/caption 组内先/ret≠0 回落/总闸直落/部分送达 delivered_count/网络类失败回落/400 面零 iLink 调用）29/29 绿；`WeixinOutboundSpec` 时序三件（GT1 媒体先到文字后到=组内 caption 合流、GT2 文字先到媒体后到=文字先媒体后、GT3 纯媒体无文字=轮终组照发）+ 线形/超时纯函数两件 + 既有 B/R 系按 runs 语义重锚。全 mock 桩，不真发微信。
