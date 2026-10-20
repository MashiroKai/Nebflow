## 2. 现状底盘（勿重建，全部已并 main）

### 2.1 #538 · nf-file 字节腿的浏览器缓存确定性（2026-10-19 并 main）

- 服务端：`GET /api/nf-file?path&ticket` = 票证制本机文件直读面（`src/main/scala/nebflow/gateway/NfFileRoutes.scala:163-231`）；票据铸造 `POST /api/nf-ticket`（同文件 `:247-301`，批量、逐路径裁定、拒绝逐条返回）。
- #538 修的正是「重复打开走中继」：路由此前从不发 `Cache-Control`，浏览器按 RFC 9111 §4.2.1 启发式（≈10% × now−Last-Modified，新图只有几秒）判新鲜，**中继腿每次开图都全量重穿隧道**。修后显式 `private, max-age=300`（`NfFileRoutes.scala:41-61`）：5 分钟内重复打开 0 字节过网；过期后一次条件往返拿 304。`private` 强制——URL 内嵌票据凭据，永不进共享缓存；max-age 300 ≪ 票据 TTL 1800s，缓存条目活不过凭据。
- 服务端钉：`src/test/scala/nebflow/gateway/NfFileRoutesSpec.scala:480-486`（200 腿必带 Cache-Control: private, max-age=300；实测 If-None-Match → 304 ≈2ms）。
- Range 腿（视频 seek 用）：单字节区间协商 206/Content-Range/Accept-Ranges（`NfFileRoutes.scala:64-143`）；多区间/畸形按 RFC 9110 §14.2 忽略回 200；越界 416 + `bytes */size`。

### 2.2 #542 · 三档加速（2026-10-20 并 main，验收 spec 站1-8）

作者令逐字存档于 `tests/nffile-three-tier.spec.mjs:1-4`。三档实现面：

- **档1 · tab-first 本机直读**：`previewWithProgress` 点击**同步**开 Canvas 标签+进度面板，取数在后（`src/main/resources/web/js/attachmentPreview.js:463-469`，面板出现时刻请求数=0，spec 站1 钉）；本机全链实测中位 ≈45ms（铸票 1.0ms + 兑换 2.8MB 11.7ms + 解码 2.5ms…，证据 `evidence/20261019_082500_imgslow538-segmented-timing.md` 分段计时表；对照远程腿活窗中位 ≈2.79s，62×，大头=字节穿隧 1531ms 55%）。
- **档2 · 零取数直渲染**：浏览器端 blob 缓存账本 `src/main/resources/web/js/nfFileCache.js`（全文 291 行）——字节存 Cache Storage（`nf-file-cache-v1`，持久），索引（LRU 序+字节数+时间戳）存 localStorage；**LRU + 字节预算 256MiB**（`:34`）+ **TTL 30min**（`:36`，与 nf-ticket TTL 同族）；`caches` 不可用退化为会话内存 Map（`:50-51`，申报降级面）；命中不做服务端再验证（无条件 GET 再验证=全量重下，陈旧窗 ≤TTL，`:14-17` 申报）；配置 `<home>/nebflow.json → nfFileCache.{enabled,maxBytes,ttlMs}`（`:18-19`）。缓存键两个：`nf:<path>`（设备本机件，`messages.js:2618`）、`attach:<id>`（好友附件，`messages.js:2480`）。
- **档3 · 流式进度+失败可见**：`fetchBlobWithProgress`（`nfFileCache.js:261-291`）边收边报 `onProgress(loaded,total)`，Content-Length 缺席→不定态转圈；失败抛错带 status，标签内就地可见错误态+重试键（`attachmentPreview.js:360-375,441-446`），「fail-silent 从此不存在」（`:452`）。
- 合帧批量铸票：同帧 N 路径只发 1 次 POST（`src/main/resources/web/js/nfTicket.js:352` 起；`messages.js:2200`；spec 站5：N 图 ≤1 铸票）。
- 验收：`tests/nffile-three-tier.spec.mjs`（站1 tab-first / 站2 零取数 / 站3 失败可见 / 站4 进度环形态 / 站5 铸票合帧 / 站6 LRU+配置 / 站7 接线钉 / 站8 #538 缓存头引擎面）。

### 2.3 设备无关架构 v2.1 · 文件指针登记制 (device, path)

- 指针条目 = `[{device, path, changeType}]`（`core/delegate/DeviceDelegate.scala:502-513`），信条「字节不回传」（`:21,39-40`）；登记入口两个：会话流 `metadata.artifactPointers` ExternalEvent（`:540-545`）+ TaskLedger 台账注记（`:573-587`，文本逗号串——缺口见 §3.9 行 I）。
- 幂等键 `pointerHash` = sha256(`<deviceId>|<remotePath>`) 前 16 hex（`core/tools/PointerPickup.scala:43-45`）；解引（取件）落盘 `<dataRoot>/uploads/pickups/<hash>/<名>`，重取覆盖（`:40,52-60`），7 天 TTL 清扫 + 6h 周期 fiber（`:34-35,66-86`；`core/tools/RemoteExecutor.scala:1362-1368`）。
- 通道信任：`DeviceEntry`（deviceId/deviceName/sharedKey/allowSpawn，`core/device/DeviceRegistry.scala:28-37`，落盘 `neblink/devices.json` 热读 `:45,71-99`）；HMAC 四元组+常数时间比对+ts±90s+NonceCache（`core/device/DeviceHmac.scala:34-64,84-137`）。
- 📌 **v2.1 spec 文档本体不在库**（worktree/主仓/docs 四处均无，代码大量引用其 §编号，以代码注释为准）——设计引用的口径基准见开放问题 Q11。

### 2.4 设备通道（跨设备字节传输，双形态）

- **通道选择**：P2P 优先 → relay 兜底（`RemoteExecutor.dispatchBrowse :574-595`）；relay 路径在接收端展开 `~`（Windows = `C:\Users\...`，`NeblinkRelayTunnel.scala:883-888`）——Windows 接收链与 macOS 全同（同一 JVM 二进制，MSI 打包）。
- **分块腿（SendMessage 式，完整校验+续传）**：计划 4MiB（`AttachContract.plan`）；单块唯一读法 `neblink/NeblinkFiles.scala:39-46`、整件流式 sha256 `:22-32`；P2P 运输 `dropbox/DropboxChunkTransports.scala:35-108`（裸字节+X-Dropbox-* 头+块级派生超时）；relay 运输 `:268-340`（逐块 base64+确定性落点+归属 token）；收端状态机 `FileTransferAction.chunkedPut :262-464`——权威 offset=落盘长度、幂等/gap 判 `:326-344`、**接收端自算块摘要禁自证** `:284,379-394`、续传截断对齐 `:396-409`、末块整件摘要不符即删 `:414-449`。
- **进度/取消现状**：附件腿有真进度（服务端确认才推进，`AttachUpload.scala:31-36,53-60`）+取消（`:137-139`）；分块腿**协议有进度**（每块 ack 回 bytesReceived/totalBytes，`FileTransferAction.scala:466-478`）**但无 UI 消费**，且无取消信令；浏览取件腿**无进度无取消**。
- **浏览取件腿（单件整取）**：`RemoteExecutor.scala:547-568`（单请求整件 base64，本侧 50MiB 闸）→ PointerPickup 落盘（`:420`）→ 二进制预览复用 `/api/nf-file`（`WsFileOpsHandlers.scala:384-389`）——**无分块/无断点/无整件校验**（`PointerPickup.scala:52-60` 落盘不验摘要）。
