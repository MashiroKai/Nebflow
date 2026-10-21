# 本地加速 M2 · 文件分块腿 + 缓存语义（实施批 v2 · 重活打捞续作）

批次：2026-10-20 · 分支 `delegate/delegate-nebflow-79cc79eb` · 设计依据 `reports/20261020_154500_data-plane-local-accel-design/`（v1 §4/§6/§7 + v2 §3/§4）
前史：前会话（delegate-nebflow-8bbd5826）随引擎重启阵亡，遗 32 个未提交文件；本批逐文件盘点后**全部打捞**（成品度高：协议注释/设计条款引用齐备），三方合并落位到含 M1 的 main 基线（27 改文件 `git apply -3` 零冲突 + 5 新文件原样拷入），补齐质量闸缺口后交付。

## 交付范围（五件全落）

1. **设备通道取件腿重写**（v1 §7 M2-1）：`RemoteExecutor.transferGetScoped` 单请求整件 base64 → `transferGetScopedChunked` 分块拉取腿。新收端游标 `BrowseGetCursor`/`BrowseGetLoop`（`src/main/scala/nebflow/core/tools/BrowseGetCursor.scala`）：4MiB 级块 + **逐块本侧自算摘要校验**（对端回传值只作比对基准，禁自证）+ **落盘断点续传**（权威 offset = 落盘实际长度，非整块尾先截断再追加）+ **整件 sha256 收口**（不符删落盘件）+ 每块进度回调/块间取消旗标。设备侧响应腿 `FileTransferAction.chunkedGet`（`neblink/FileTransferAction.scala`）：切片 + 服务端自算块摘要 + `wantWholeSha256` 会话一次付清 + 块长夹取 [1KiB, 4MiB] + 越界结构化 OFFSET_OUT_OF_RANGE。**零 wire 破坏**：旧对端忽略新键 ⇒ legacy 整件消费。`PointerPickup.pickupPath` 提为落点单点（分块腿首块前需知落点）。
2. **进度 UI**（v1 §4.4 统一进度面契约）：explorer.js 取件进度条（大小/百分比/4s 窗速度/常驻取消键⇒`fileTransferCancel`/失败浮条重试=从续传点继续）；**零超时纪律**：>max(10s, 2×上块耗时) 无推进 ⇒ 纯视觉黄条「停滞—可取消」，永不定时杀传输。WS 面（`WsFileOpsHandlers.scala`）：`fileProgress` 逐块帧（权威读数=落盘长度）、`fileTransferCancel`→`BrowseFetchRegistry` 翻旗标、取消终态 `fileContent{cancelled:true}`（区别于传输失败）。
3. **nfFileCache 账本全数据面共享**（v1 §6）：`nfFileCache.js` 账本升 v2——**内容寻址 cid**（整件 sha256 前 16 hex，无 hash 回落取数键）+ 引用计数淘汰（全部键出局才删字节）+ **分桶预算**（local 128/device 96/server 32 MiB，`nebflow.json nfFileCache.buckets` 可配，全局 256MiB 仍为硬顶）+ 桶内 LRU + v1 索引读入即迁移。**事件失效**：引擎写路径（Write/Edit/MultiEdit 工具、编辑器保存、createFile/deletePath/movePath）经 `NfCacheInvalidator`（core 窄端口，gateway 装配期注入 `wsHub.broadcast`）广播 `nf-cache-invalidate {path}` ⇒ 前端精确淘汰；列目录 size+mtime 顺带对账 `nfCacheReconcile`；TTL 30min 保留为兜底。**hash 去重**（v1 §6.3）：设备分块腿整件 hash 免费副产品随帧送达 ⇒ `nfCacheLookupByHash` 副键命中/入账，跨设备同内容只存一份字节。**410 对账**：附件终态过期 ⇒ `nfCacheDrop` 同步淘汰。
4. **canvas 七 viewer 接账本**（v1 §7 M1-2 落地）：image/pdf/docx/xlsx/pptx/epub/video 票据腿统一经 `nfViewerUrl`/`nfViewerSource`——`nf:<absPath>` 命中零取数 blob 直渲，未命中走既有票据链并带 hash/srcPath 入账；video 带 64MiB 尺寸闸（超限保持票据流式 Range seek）；URL 生命周期登记（tab 关闭撤 blob URL）。字节路由零新增（仍走 `/api/nf-file?path&ticket`）。
5. **六端点缓存语义**（v2 §3.1 ①）：`HttpCache`（`gateway/HttpCache.scala`）条件请求协商单面——`/api/usage/aggregate`（内容 hash ETag）、`/api/projects/<n>/flow-map`（结构键 `meta.updatedAt:nodeCount`——载荷含逐秒 TTL 展示字段故禁内容 hash）、`/api/projects/<n>/flow-map/archive`（结构键 批数+最大 archivedAt/completedAt）、`/api/chains`、`/api/delegates/:id`（内容 hash——不可变事实流）、WS `historyPage` 页指纹（`HistoryPageFingerprint` 随帧，前端 `noteHistoryFingerprint` 入 LRU-40 账）。全部 `Cache-Control: private, no-cache` + 304 重述策略头。
   **申报一处设计偏差**：archive 行 v2 §3.1 表写 `private, immutable`；实现改 ETag/304 + `private, no-cache`——该响应是滚动 24h 窗聚合，immutable 会让「新增链 id diff」新鲜性面（toast/脉冲）最长冻结 24h，破既有功能。批级不可变语义留给未来按批端点。

## 质量闸（全绿）

- **分块/校验/续传/取消/legacy/超限/取消登记表**：`BrowseGetSpec`（11 测）；
- **失效发射面**：`NfCacheInvalidatorSpec`（5 测：帧形契约/未注册 no-op/空路径/后者胜/reset 防泄漏）；
- **缓存语义面**：`HttpCacheSpec`（6 测：ETag 稳定性/304 命中（单值/列表/*）/200 全量/策略头缺省）；
- **服务端 chunkedGet**：`FileTransferChunkSpec` 增 3 测（切片+自算摘要+整件摘要一次付清 / 越界结构化错误 / 块长夹取）；
- **家族回归**：`nebflow.gateway.* + nebflow.neblink.*` 家族 **1568/1568 全绿（0 失败）**（含 M1 LocalAccelBudgetSpec 两条预算线与本批新 spec）；`nebflow.core.tools.*` 家族 **909/909 全绿（0 失败）**（EditTool/WriteTool/MultiEditTool/PointerPickup/RemoteExecutor 受影响面 + BrowseGetSpec/NfCacheInvalidatorSpec）；
- **前端**：`nffile-three-tier.spec.mjs` 4 站全绿（v2 账本向后兼容：档2 命中/LRU/配置/410 对账钉不变）；`canvas-video-viewer.spec.mjs` **按 M2 双档语义更新后 13/13**——小件（≤64MiB）账本 blob 腿（seek 零 Range + 重开零取数）+ 大件（>64MiB）票据流式 Range/206 原样；`canvas-office-viewers.spec.mjs` 17/17（fixtures 自主仓补拷——evidence 不入 git）；
- **存量红申报（非本批）**：`explorer-nebflow-dir.spec.mjs` 2/2 失败（⑤ Agent.md 腿）——**main 存量同判**（主仓 checkout 跑同一 spec 同签名 2 failed，2026-09-02 批次的环境/夹具漂移，与本批零关）；
- JS 语法检查零失败；`check-circular.mjs` 输出与 main 逐字节一致（M2 零新增静态环；既有环为 main 存量，不在本批范围）。

## 生效窗（候重启）

- **引擎侧**（FileTransferAction.chunkedGet / WsFileOpsHandlers 进度·取消·失效 / NfCacheInvalidator 装配 / HttpCache ETag 面 / historyPage 指纹）：**候网关重启窗生效**——与 M1 引擎侧同窗重启一次即全量生效（M1 交付时已注明候重启）。
- **前端**（nfFileCache v2 账本 / explorer 进度条 / 七 viewer 接缝 / ws.js GLOBAL 帧）：页面刷新即生效（静态资源随网关分发；网关未重启时新旧引擎帧协议本就兼容——新帧类型为增量，旧引擎不发即无消费）。

## 打捞/修复明细（相对前会话遗留物的增量）

- BrowseGetSpec 半成品语法修复（`val r <-` 悬空于非 for 上下文 ×6、纯同步体未包 IO）；`withTmp` 嵌套 IO 缺陷修复（`IO.blocking(f(dir))` by-name 捕获导致 guarantee 清理先于内层 IO——续传测试因此从 0 重拉实证抓出）；
- 新增 `NfCacheInvalidatorSpec` / `HttpCacheSpec`（前会话缺失的「失效」「缓存语义」spec 腿）；`FileTransferChunkSpec` 增 chunkedGet 节；
- `canvas-video-viewer.spec.mjs` 双档语义更新（M2 申报的行为变更：小件账本化后 Range 断言移至大件站，新增账本命中站）；
- M1 合入的 4 文件冲突（HealthRoutes/PresenceRoutes/两 locale）三方合并零人工干预。

## 未做/开放项

- Q18（历史页「本地先行渲染」语义）候作者裁——本批只落页指纹对账数据刀（申报于 HistoryPageFingerprint 头注）；
- 清缓存用户入口（设置面板「清空文件缓存」+ 占用读数）仍未做（v1 §6.4 申报保留，`nfCacheReset()` seam 在）；
- archive 按批 immutable 端点（本批偏差的承接面）登记为后续项；
- 取件进度 UI 的真人截图验证（浏览器手开 evidence 视频走一遍）留待重启窗后实操。
