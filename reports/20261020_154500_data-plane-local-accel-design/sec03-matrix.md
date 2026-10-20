## 3. 数据面盘点矩阵（逐面现状与缺口，file:line 举证）

图例：达标 = 三档已接且零中继路径在位；半接 = 有缓存或进度但面内不统一；未接 = 无账本/无统一进度。

### 3.0 nf-file 端点本体（达标样板，其他面的对齐基准）

| 维度 | 现状 | 锚点 |
|---|---|---|
| 取数路 | `GET /api/nf-file?path&ticket` 票证制直读本机盘，零中继；Range 206 支持 | `gateway/NfFileRoutes.scala:163-231`（Range `:64-143`） |
| 铸票 | `POST /api/nf-ticket` 批量、逐路径裁定、合帧 N≤1 请求 | `NfFileRoutes.scala:247-301`；`web/js/nfTicket.js:352` 起 |
| HTTP 缓存 | `private, max-age=300` + ETag/304（#538） | `NfFileRoutes.scala:41-61`；钉 `NfFileRoutesSpec.scala:480-486` |
| 上限 | 200MB（工具侧引用探针口径） | `core/tools/FileRefs.scala:33` |
| 路径策略 | 单权威裁定阶梯（expandTilde→normalize→exists→toRealPath→credential→inode guard）；读端点/铸票/工具侧 pre-flight 同问一对象，不可读路径永无票据；扩展名门 2026-10-07 起退役（表保留焊死防复活） | `gateway/NfFilePolicy.scala:9-24,27-53`；工具镜像 `core/tools/FileRefs.scala:39-58` |

### 3.1 行 A · 消息附件卡（设备本机件 / legacy 面）——达标

- 取数：双腿分派，判据 = `canFetchLocalBytes`（blob 腿=票据路流式开 / 文本腿=WS readFile 链）：`web/js/messages.js:2567-2633`；缓存键 `nf:<path>`（`:2618`）。
- 三档全接：tab-first（`:2608-2611` 注记）、档2 命中零取数、档3 进度环+失败可见重试（`:2612-2613` 旧静默降级已移除）。
- 缺口：无（本面即 #542 达标样板；`loc` 字段化后仅增加显式位，行为不变）。

### 3.2 行 B · 好友/群附件（server 面）——半接

- 取数：唯一鉴权口 `friendsApi.downloadAttachment` → `GET /api/friends/attachments/{id}`（Bearer）：`web/js/friendsApi.js:707-718`；预览走 `previewWithProgress` + `attach:<id>` 入账：`messages.js:2467-2502`。
- 已有：档2（预览过即零取数）、档3（进度+失败可见+410 终态权威文案 `:2486-2489`）、E4 回执链（`friendsApi.js:766-784`）。
- 缺口：①**下载腿（保存到磁盘）与账本不通联**——`downloadAttachment` 走 `saveBlob` 不入账（`messages.js:2506-2539`），用户「已下载过」的件再预览仍走网络（作者令「已点击下载过的文件应该也打开很快」的字面覆盖差一口，见开放问题 Q5）；②进度环有、速度/取消/停滞态无（§4.4 差距表）。

### 3.3 行 C · Card/Pop 文件引用（HTML 卡内 file: 引用）——半接

- 机制：本地引用统一探针/告警（probe/enum/计数共享，Pop 与遗留 Card 同源）：`core/tools/FileRefs.scala:9-26`；解析入口 `FileRefs.resolveCandidates`（`core/tools/PopTool.scala:417-438,434-498`、`core/tools/AskUserQuestionTool.scala:430,458-495`）。
- 字节路两族：①**内联 data URI 优先**（单图 ≤5MB `FileRefs.scala:853-854` + 累计 40000 chars `:884,978`，图片族 `:909`）——Pop 媒体主路；②超限/视频/音频/HTML 内引用转 `/api/nf-file` 代理 URL（票腿唯一铸造点 `FileRefs.scala:785-788`）→ §3.0 端点。前端 `popArtifacts.js:162-201` hydrateMedia 唯一加水腿：src 胜 → 否则 ticketUrl；用户附件走 `/uploads/` resolver 腿替换（`:177-187`）；首错重铸一次、再错终态（`:191-200`）。
- **Card 工具已退役**（盘点纠偏）：`PopTool.scala:15-33` 作者裁定面——Pop 吸收 Card，HTML 动画一律 FILE CARD（Canvas 打开）；srcdoc 卡面仅遗留哨兵载荷（`cardRegistry.js:1-2,429-497` 懒挂+近铸票+re-mount 重铸）。
- 缺口：①**卡内/Pop 引用不过 nfFileCache 账本**（`<img src="nf-file…">` 靠浏览器 HTTP 缓存 300s，过期回源，无 LRU/无事件失效/不享受「重开零取数」）；②内联/票腿双 URL 族未统一（`popArtifacts.js:173-187` 自注「登记不承诺本期」）；③单次 re-mint 即终态，长会话票过期无自动重试环（`nfTicket.js:207-210`）；④卡内 img 无进度环（大图静默加载）；⑤镜像表文案仍当服务域事实打印（`FileRefs.scala:134-145` vs 端点 2026-10-07 撤门，读者被引导高估限制）。

### 3.4 行 D · 项目文件夹浏览器（explorer 树 + Canvas viewers + 路径选择器 + dropbox 设备面）——半接

- 列表：WS `listDir`（`web/js/explorer.js:441` → `gateway/WsFileOpsHandlers.scala:94-101`）；本机/远端分叉单点 `explorer.js:881-891`（可选 `device` 键）；后端分叉 `WsFileOpsHandlers.scala:74-75`。列表缓存 L2 = fmMessageCache/fmDropboxCache（localStorage 50 条/设备·30 设备·512KB，stale-while-revalidate，`fmDropboxCache.js:27-30`）。
- 预览：文本 ≤2MB WS readFile 内联（`WsFileOpsHandlers.scala:320-325`）；>8MiB 流式描述符+按需拉窗 256KB（`TextStream.scala:49,58,65`）；**二进制零 WS 字节**——元数据帧（`:332-343`）→ `nfTicket.ticketUrl`（`nfTicket.js:283-288`）→ `/api/nf-file`，七个 viewer 全走此路（`viewers/image.js:162`、`pdf.js:58`、`docx.js:34`、`xlsx.js:30`、`pptx.js:252`、`epub.js:21`、`video.js:78`）。
- 远端腿（跨设备）：`readRemoteFile`（`WsFileOpsHandlers.scala:392-462`）→ `transferGetScoped`（`:415`）→ **`PointerPickup.save` 落本机 pickup**（`:420`）→ 二进制腿 absPath=pickup ⇒ **复用 nf-file 零策略改动**（`:384-389`）；失败 fail-closed error 帧+device 回显（`WsBrowseDeviceLegSpec.scala:121-185`）。
- dropbox 面：控制面 WS（`WsDropboxHandlers.scala:40-44`），字节面浏览器→网关 `POST /api/neblink/dropbox/upload/<tid>` **单 POST 整件无分块**（`dropbox.js:1066`），网关间腿 4MiB 分块+断点续传 probe（`DropboxChunkTransports.scala:21-22,76,127`）；进度帧 `dropbox-file-progress`（`dropbox.js:996-1019`）。
- 缺口：①**canvas viewers 不接 nfFileCache**（viewers 目录零引用——同一文件第二次打开全量重下，nfFileCache 消费方仅 messages.js:2618-2620 与 attachmentPreview.js:399-405）；②viewers 无进度 UI（仅行内 loading 态，进度环是附件卡面专属）；③远端腿仅单件按需取，无目录批量/预热；④浏览器→网关上传无分块/无上传进度/无断点续传（仅网关间腿有）；⑤nfFileCache 无用户清缓存入口（`nfFileCache.js:234` 候作者示）；⑥wsBrowse.list 远端成功腿无 spec 覆盖（`WsBrowseDeviceLegSpec.scala:25-26` 自述）。

### 3.5 行 E · 插件资产——未接（无缓存策略）

- 取数：`GET /api/plugin-assets/<name>/<rel…>`（`gateway/StaticRoutes.scala:445-461`，挂 /api Router `GatewayMain.scala:1484-1493`；⚠️ 无独立 PluginAssetsRoutes.scala 文件—— StaticRoutes 内嵌函数，同目录 :427 注释自证）；会话 cookie 闸 + realpath 围栏（`StaticRoutes.scala:451-455,483`）。
- 缓存：**无显式 Cache-Control**，仅 http4s 默认 ETag/Last-Modified 条件 304（`StaticRoutes.scala:464-488`）——对照面同文件已有现成模板（vendor/brand 显式 `no-cache` `:533,:570`、hashed 资产 `immutable` 年缓 `:317-318`）。
- 前端：plugins.js:853/908（Plugin.md/SKILL.md 探针）、:848-858 进程内 Promise Map 缓存（会话级）。
- 缺口：①缓存策略缺位（#538 同病未修）；②无版本化路径/指纹文件名，升包靠 ETag 漂移感知；③与账本不通（重复打开走条件往返，非零取数）。

### 3.6 行 F · 上传件——半接（三腿三态，进度/缓存割裂）

- 腿 A 普通聊天上传：WS 帧 base64 内联（`WebSocketRoutes.scala:1612,1651-1656`），帧上限 10MB（`:1540`），落 `~/.nebflow/uploads/<sid>/…`（`:1659-1683`）——**无进度、无失败重试反馈**。
- 腿 B AskUser 卡：`POST /api/askuser/attachments` multipart（`AskUserAttachmentRoutes.scala:199`），单件 ≤100MB/每卡 ≤9/并发 ≤3，sha256 流式+同卡去重（`:297-302`），`xhr.upload.onprogress` 有进度（`askAttachments.js:246`）+重试钮（`:284-291`）。
- 腿 C neblink 跨设备：`POST /api/attachments` 整件入体（`SocialRoutes.scala:409`）→ 网关落临时件 → **4MiB 分块推对端**（`attachUpload.js:8-11`），WS 逐块帧 `attach-upload-progress`（`SocialRoutes.scala:493-512`），取消路由 `:708` + `neblink/AttachUploadRegistry.scala:45-58`。
- 回读：`/uploads/<sid>/<file>` 网关自有路由（persistence.js:445-454 拼 URL；`StaticRoutes.scala:340` 起）——**无 Cache-Control、无 Range**（`:395` 裸 StaticFile）；`/uploads/` 在 FileRefs 豁免单（`FileRefs.scala:636-637`），**不经 nf-file 面**，两互补不相交。
- 缺口：①回读无缓存/无 Range（大附件重复打开每轮全量，#538 同病）；②三腿进度语义不统一（无/有/有）；③腿 A 大小闸藏在 WS 帧上限里，超限仅日志（`:1595`）用户不可见；④腿 C 临时件「7 天 TTL」仅前端注释（attachUpload.js:20），网关清扫实现未追到（未验证）。

### 3.7 行 G · 社交入站媒体（好友/群附件 + 渠道入站图）——半接（收端零本地性）

- 字节源：blob 在**外部 neblink-server**（`{config.url}`，`neblink/NeblinkClient.scala:951-956`）；网关 = 鉴权代理**零缓存零落盘**，每请求全量内存中转（`gateway/SocialRoutes.scala:332-346,352-368`）；前端唯一取字节口 `friendsApi.js:707-718`。状态机 `{uploading|ready|expired}`，blob 瞬态元数据永留（`neblink/NeblinkModel.scala:797-828`），410 终态逐字透传（`SocialRoutes.scala:343-345`）。
- 缓存四层：legacy avatarCache（localStorage 24h/1.5MB，`avatarCache.js:1-44`）、localStore IDB（六级降级 L0-L5，avatars 300 条/6MB，`localStore.js:25-35,82`）、nfFileCache（**仅点击预览腿读写**，`messages.js:2480`）、内联图内存 objectURL FIFO≤64 刷新即失（`messages.js:2270-2275`）。
- 渠道入站图（feishu/weixin）：落盘 `<dataRoot>/uploads/<sid>/feishu-…`（`FeishuBridgePlugin.scala:1430,1439-1459`，32MiB 闸）/ `<NEBFLOW_HOME>/media/weixin-inbound/`（`tools/weixin-sidecar/msgloop.mjs:444-518`，幂等覆盖写、**无清理** `:448-449`）——只以「已存至」文本行进 agent 会话，不进 web 消息面。
- 缺口：①**收端附件零本地性位**——`localFace` 只挂发送侧（`messages.js:2212-2213` 明示接收侧/群面/历史卡不可达），刷新/重开必全量重拉；②气泡内联图绕过 nfFileCache，看过的图刷新即重拉；③nfFileCache 与 410 终态无对账（TTL 内已过期件仍出帧，`nfFileCache.js:14-17` 命中不回源）；④内联腿不发 E4 回执（blob 只能等 24h 强删，`FriendService.scala:1477`）；⑤微信 media 落盘 v1 无 TTL/对账。

### 3.9 行 I · 设备通道本体（跨设备字节腿）——半接（分块腿强、浏览腿弱）

- 分块腿（校验+续传完整，§2.4）：进度协议有、UI 消费面无（每块 ack 回 bytesReceived/totalBytes，`FileTransferAction.scala:466-478` 无挂接点）；**无取消信令**（超时是唯一止血）。
- 浏览取件腿：单请求整件 base64（`RemoteExecutor.scala:547-568`，50MiB 闸）——**无分块/无进度/无断点/无整件校验**（`PointerPickup.scala:52-60` 落盘不验摘要）；作者令「Windows 下载进度条」的直接承载腿恰是此腿，缺口最重。
- 指针登记：仅文本注记，无指针表/无检索面；pickup 字节 7 天清扫后指针仍在、无「字节已清扫」回写（`DeviceDelegate.scala:573-587`、`PointerPickup.scala:74-78`）。
- legacy 整件 put/get 面：无分块/无校验/无续传（`FileTransferAction.scala:110-127`、`NeblinkClient.scala:1525-1561`；旧缺陷自述 `DropboxChunkTransports.scala:255-259`）。

### 3.8 行 H · evidence/reports 与 Pop 产物面——未接（无登记面、无索引缓存）

- 字节路：**无专属静态路由**（gateway 全目录仅（已退役）镜像表提及 plots/evidence/reports，`NfFilePolicy.scala:150`/`FileRefs.scala:135`）——实际走两条：①nf-file 票腿（Pop 载荷超限媒体、HTML 卡内 src/url() 引用、Canvas HTML viewer 子资源 `viewers/shared.js:6,219,245`）；②Canvas 打开主文档 WS readFile（`WsFileOpsHandlers.scala:293-309`）+ 二进制腿 `:387-388`。
- popMediaIndex：对 history 数组的**纯派生索引**（条目只带引用非字节，`popMediaIndex.js:5-6,19-22`；来源三路 `:74-147`）；**无缓存**——每次搜索全量重建（`chatSearch.js:979-993`，4 调用点）。workline.js 与文件取数无关（纯过程行 UI）。
- 缺口：①evidence/reports 无目录列举/索引路由，只有被动显示面（与登记制 v2.1 的可见衔接仅 device pickup 一处 `WsFileOpsHandlers.scala:285-291`）；②索引 O(全 history)/次运行无缓存；③大图 Pop 无进度（内联小图即时，票腿大图静默）。
