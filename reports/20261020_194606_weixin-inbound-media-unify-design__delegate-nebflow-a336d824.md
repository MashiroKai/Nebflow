# 社交通道入站媒体统一适配 · 映射设计（实施批 v2）

作者令：微信等入口发来的图片文件，显示逻辑、agent 读取逻辑都与控制台入口完全同一套。本节 = 映射设计（入站形状 → 统一登记 → 渲染/注入 + 存量「已存至」兼容渲染）；确认后实施微信腿全链，飞书腿只留接口。

## 1. 现状（锚点）

**控制台入口（同一套的基准线）**：
- 前端帧：`attachments:[{mimeType,data(base64),name,…}]`（`src/main/resources/web/js/input/attachments.js:82-128`）。
- 落盘+登记：`WebSocketRoutes.handleMessage`（`src/main/scala/nebflow/gateway/WebSocketRoutes.scala:1651-1773`）——图片存 `<dataRoot>/uploads/<sid>/`，attJson=[{name,type:"image"|"file",path}]（:1764-1773），vision 块 = `ContentBlock.Image(data,mime)` + `[用户附加图片: <absPath>]` 文本块（:1681-1682）；非图片 = `[用户附加文件: <path>]`（:1723/:1738）。`UiMessage.User(content, attJson)` 持久化 + `attachmentsSaved` 帧回传。
- 显示：`renderUserBubble`（`web/js/chat.js:453`）→ `buildUserMediaRow`（`web/js/popArtifacts.js:759`）= 独立 `.row.pop-artifact.user.user-media` 媒体卡行（图=Pop 媒体面、文件=文件卡）；历史恢复走 `persistence.buildUserAttachmentRows`（`web/js/persistence.js:485`，uploads 路径经 `attachmentImageUrl`→`/uploads/<sid>/<file>`）。
- 读取：图片块随 `AgentCommand.UserInput(blocks)` 直入 LLM 上下文；文件 = 路径文本块 → ReadTool 同面。

**微信入口现状（旁路）**：
- sidecar 下载落盘 `<NEBFLOW_HOME>/media/weixin-inbound/<accountId>/<messageId>-<name>`（`tools/weixin-sidecar/msgloop.mjs:265-269`，kind∈{image,file,video}，mime 嗅探），ingress body 携 mediaPath/mediaKind/mediaName/mediaMime。
- 引擎 mediaArm（`src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala:420-429`）：路径在场⇒注入**纯文本** `mediaLine` =「[图片] 已存至 <path>（原始文件名 n），可用 Read 查看」；路径丢失⇒fail-closed 降级裸标签 `[图片]`，仍送达。
- 注入点 = `WebSocketRoutes.handleBridgeMessage`（:813-872，桥→会话唯一注入点）：`UiMessage.User(裸文本, Nil)` + `bridgeUser` 帧（前端 `main.js:2550` 渲染普通用户气泡，**无附件**）+ `ExternalEvent`（payload=marker+文本，**无 vision 块**）。

**落点核实（数据根迁移）**：已实测 `~/.stand/media/weixin-inbound/a-im-bot/m1-image.png` 在场 ⇒ 新入站落点 = `<数据根>/media/weixin-inbound/<accountId>/…`（sidecar `NEBFLOW_HOME` 随引擎数据根，迁移生效）；旧迁移目录 `~/.nebflow-migrated-20261020-190831/media/weixin-inbound/` 仍在盘，存量行路径仍可解析。飞书图片臂落 `<root>/uploads/<sessionId>/`（`FeishuBridgePlugin.scala:478-525`，同样只发「已存至」文本行——存量兼容渲染一并覆盖）。

## 2. 映射设计（三同一）

### ① 登记同一 —— (device,path) 指针登记 + 类型识别走控制台管线

- 新通道无关指针类型 `nebflow.bridge.BridgeAttachment(path, name, mime)`（渠道零知识；device 维度=本机落盘，指针即绝对路径）。
- `BridgeContext` 新方法 `injectMessageWithMedia(sessionId, content, senderId, origin, attachments: List[BridgeAttachment])`：**default 实现 = 降级委托 `injectMessage`**（既有 fake/Feishu 编译与行为零变；飞书腿接口就位）。
- 微信 mediaArm：路径在场 ⇒ 改调 `injectMessageWithMedia(sessionId, content="", senderId, origin, List(BridgeAttachment(path,name,mime)))`；路径丢失 ⇒ 既有降级逐字保留。
- 网关统一登记（`handleBridgeMessage` 增 `media` 形参，console 管线同款）：
  - **类型识别**（控制台判据逐字）：`mime.startsWith("image/")` ⇒ `"image"`，否则 `"file"`（console :1770 同式）；attJson=[{name,type,path}] 同形（name 缺席⇒路径基名）。
  - **持久化**：`UiMessage.User(displayText, attJson, timestamp)`（图片无随文 ⇒ displayText=""，与控制台纯图发送同形）；**display 帧**：`bridgeUser` 增加性 `attachments` 键。
  - 指针登记不复制字节：文件留 media 根，显示走 nf-file 票腿（2026-10-07 撤门后同域判据，`NfFilePolicy` 凭据两层不变）。

### ② 显示同一 —— 聊天流 = 控制台媒体气泡（只动媒体消息渲染分支）

- live：`main.js` bridgeUser 处理器把 `msg.attachments||[]` 透传 `renderUserBubble` ⇒ 既有 `buildUserMediaRow` 媒体卡行（图/文件/混排与控制台逐字同面）；图片字节经 `hydrateMedia` 默认票腿（`ticketUrl(path)`，与 agent Pop 媒体同路由）。
- 历史恢复/缓存恢复：attJson 随 ui.json 走既有 `buildUserAttachmentRows`；`hydrateMedia` 的 resolver-null 分支由「终态失败」改为「**回落票腿**」（uploads 族之外的控制台路径本就该走同一票面；console uploads 路径仍优先命中 resolver，零行为变化）。
- **存量「已存至」兼容渲染**：单点识别器 `legacyMediaAttsOf(text)`（popArtifacts 导出）——严格匹配 `^\[(图片|视频|文件)\] 已存至 <path>(（原始文件名 <n>）)?，可用 Read 查看$`（mediaLine 两种拼装，微信/飞书两腿存量行都覆盖）⇒ 换算 [{type:图片→image|视频→video|文件→file, name:原始文件名||基名, path}]，媒体行接管、文本气泡隐去；不匹配/无附件行逐字现状。挂点三处（同一判据同源）：live `renderUserBubble`、恢复 `restoreFromStorage`、`restoreFromBackendHistory`（识别失败⇒原文本行，fail-open）。

### ③ 读取同一 —— vision 块注入 = 控制台 images 同管线；文件走同一附件读取面

- `AgentCommand.ExternalEvent` 增加性尾参 `blocks: Option[List[ContentBlock]] = None`（构造点全兼容；`CompactionQueueStore` 编解码补可选键，旧快照无键⇒None）。
- 网关构块（console 管线同源）：image ⇒ 读文件 → `ImageInject.prepareImage`（1920/0.8、2MB 触发、5MB 硬帽，压缩管线与 ReadTool/Mail 附件**同一实现**）⇒ `ContentBlock.Image`；构块失败（>10MB 帽/读失败/非具体 mime）⇒ 块缺省、消息仍以文本线送达（fail-closed 降级，与 mediaArm 同哲学）。file ⇒ 无块，路径文本线。
- 模型面文本 = faces(origin,senderId, 标签线)——标签线与控制台逐字同款 `[用户附加图片: <absPath>]` / `[用户附加文件: <absPath>]`（组合单点 `nebflow.bridge.UnifiedMedia`）；marker 块照旧只进模型面。
- 注入组装：idle 直投两处 `Message(User, Left(text))` ⇒ blocks 在场⇒`Right(Text(text) + blocks)`；排队/合批单点 `buildEventReminder` ⇒ 无块事件逐字现状（单 reminder 包裹），任一事件带块⇒逐事件 `Right(Text(编号行+reminder 包裹) + blocks)` 拼合（单事件=微信常态，语义等价）。排队语义零变（仍 pendingEvents/ToolsComplete gap 注入，不升格 UserInput、不触 fromUser 语义）。

## 3. 改动面

| 层 | 文件 | 改动 |
|---|---|---|
| 桥接缝 | `bridge/BridgePlugin.scala` | +BridgeAttachment、+injectMessageWithMedia（default 降级） |
| 微信腿 | `social/WeixinIlinkBridgePlugin.scala` | mediaArm 有盘⇒走媒体缝（content=""）；mediaLine 保留（飞书腿+存量语义仍用） |
| 网关 | `gateway/WebSocketRoutes.scala` | handleBridgeMessage +media：分类/attJson/构块/display 帧/持久化 |
| 网关 | `gateway/GatewayMain.scala` | bridgeCtx 实现 injectMessageWithMedia → routes |
| 模型面 | `actor/AgentCommand.scala`、`agent/AgentActor.scala`、`agent/AgentIdle.scala`、`agent/CompactionQueueStore.scala` | ExternalEvent.blocks + 组装三处 + 编解码 |
| 纯面 | `bridge/UnifiedMedia.scala`（新） | 分类/attJson/标签线/构块（离线可测） |
| 前端 | `web/js/main.js`、`web/js/chat.js`、`web/js/popArtifacts.js`、`web/js/persistence.js` | bridgeUser 透传、legacy 识别器+挂点、hydrateMedia 回落 |
| spec | `test/.../UnifiedMediaSpec.scala`、WeixinIlinkBridgeSpec（媒体臂预期）、agent reminder/codec 兼容、`tests/social-media-unify.spec.mjs`（新） | 质量闸三件 |

**边界（硬）**：飞书腿零改动（imageArm 逐字现状，接口=default 降级继承）；与重连家族批共面只动媒体消息渲染分支（bridgeUser 附件透传 / renderUserBubble 媒体行分支 / hydrateMedia 媒体字节腿 / 恢复腿媒体行），气泡文本/引用/工具行零触碰。

## 4. 验收条件（质量闸）

1. **媒体渲染 spec**（Playwright，真模块 harness）：桥附件图（path→票腿面）/文件卡/混合行形态 = 控制台同构；存量「已存至」三例（图片带原始名/无原始名/视频）⇒ 媒体行接管+文本气泡隐去；无附件旧帧/非媒体文本行逐字现状。
2. **vision 注入 spec**（Scala 离线）：分类判据（mime image/ ⇒ image）；标签线逐字；image 块=prepareImage 管线产物（ContentBlock.Image）；超帽/读失败⇒零块仍送达；buildEventReminder 无块=逐字现状、带块=Right 拼合；ExternalEvent codec 旧快照（无 blocks 键）解码=blocks=None。
3. **家族回归绿**：WeixinIlinkBridgeSpec（媒体臂新预期）+ InboundParseSpec + CompactionQueueStore/agent 相关 spec；前端 attach-bubble-unify / uploads-restore-image / pop-media-index / user-attach-canvas-open / f1-send-queue / quote-message 全绿。
4. 生效方式：引擎重启后生效（Scala 面）；前端随静态资源下发（页面刷新生效）。存量行渲染无需迁移（识别器在渲染面收敛）。
