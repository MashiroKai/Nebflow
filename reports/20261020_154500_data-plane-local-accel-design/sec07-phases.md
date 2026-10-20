## 7. 分阶段落地建议（M1/M2/M3，只设计不实施）

分期原则：每期独立可感、可验收、可单独回滚；不重建底盘（三档/nf-file/账本原样复用），只收拢与推广。

### M1 · 统一取数单点 + 统一进度面（纯前端为主 + 载荷加字段）

| # | 事项 | 说明 | 涉及面（盘点后锚定） |
|---|---|---|---|
| 1 | `resolveBytes(ref)` 取数单点 | 收拢既有三档调用点为一条管线（判序=先账本后按 loc 分流）；行为零变化，纯收拢 | messages.js:2614-2623 / attachmentPreview.js:394-447 改接线 |
| 2 | **canvas viewers 接账本**（盘点最高价值缺口） | 七个 viewer（image/pdf/docx/xlsx/pptx/epub/video）取数走单点 + `nf:<path>` 入账——同一文件第二次打开零取数（行 D 缺口①：现 viewers 零引用账本） | `viewers/*.js` 七处 |
| 3 | 内联图入账 | 气泡内联图取数并入账（行 G 缺口②：现内存 FIFO 刷新即失） | messages.js:2151-2268 分派族 |
| 4 | 统一进度组件 | 现档3 进度环扩展：速度行 / 取消键 / 停滞态（>10s 无推进转黄）/ 失败+重试（§4.4 契约） | attachmentPreview 进度面板家族 |
| 5 | 载荷 `loc` 字段 | 引擎下行引用统一投影 `"loc"`（§5.2；列目录 `device` 键与 canFetchLocalBytes 即位前身） | 引擎消息/卡片/列目录组装点 |
| 6 | server 腿全接入账 | 好友附件（`attach:<id>` 已有）推广到 uploads 回读/插件资产探针 | nfFileCache 调用点 |

验收（二值）：①三档既有 spec（`tests/nffile-three-tier.spec.mjs` 站1-8）全绿不回归；②新增 spec：viewer 打开同一张图两次，第二次 nf-file 计数 Δ=0；好友附件同断言；③进度面板含速度行+取消键（DOM 断言）；④取消后无后续字节请求（计数=0）；⑤停滞态 10s 阈值可注入时钟测试；⑥本机全链性能预算一条（Q6：p95 < 100ms）。

### M2 · 设备通道产品化 + 缓存一致性

| # | 事项 | 说明 |
|---|---|---|
| 1 | **浏览取件腿产品化**（作者令「Windows 下载进度条」的正主，行 I 缺口②） | 单请求整件 base64 → 走既有分块腿形态（4MiB + sha256 校验链 + 断点续传，底盘 §2.4 全在）+ 统一进度面；顺带补落盘验摘要 |
| 2 | 分块腿取消信令 + ack 进度接 UI | 每块 ack bytesReceived/totalBytes（`FileTransferAction.scala:466-478` 协议已在）→ 统一进度组件；新增取消 op（Q2） |
| 3 | 失效事件腿 | 引擎 filewatch/FileChangeLedger → `nf-cache-invalidate{path}` WS 广播；前端前缀匹配淘汰；**410 对账**（行 G 缺口③：expired 元数据可见时同步淘汰账本条目）；TTL 降为兜底 |
| 4 | 分桶预算 + hash 副键 | 索引加 `bucket`/`hash` 字段（§6.2/§6.3）；同桶 LRU；设备腿整件 hash 免费入账；引用计数淘汰 |
| 5 | 清缓存入口 | 设置面板「清空文件缓存」+ 占用读数（`nfCacheStats()` 已备） |

验收：①设备件传输中断网→重试从续传点继续（分块账断言）；②改写本机文件→WS 事件→账本条目消失（下轮打开走取数腿）；③同文件双设备第二设备零传输打开（hash 副键命中）；④超预算分桶淘汰不跨桶。

### M3 · 推平长尾面 + 治理面板

- 逐面接入：插件资产缓存策略（#538 同病，模板现成 `StaticRoutes.scala:317-318,533`）、uploads 回读加 Cache-Control+Range（行 F 缺口①）、evidence/reports 登记面（列目录+索引路由，行 H 缺口①）、渠道入站图进 web 消息面 + weixin 落盘 TTL（行 G 缺口⑤⑥）、popMediaIndex 缓存（行 H 缺口②）。
- 指针表升级：文本注记 → 结构化指针表 + 「pickup 字节已清扫」回写（行 I 缺口③）。
- 健康可见面板：命中率/省字节量/各桶占用/停滞率。
- `>200MB` 本机大件兜底口径（Q7）。

验收：每面一条「第二次打开请求数 Δ=0」spec + 全面接线钉 spec（源码钉模式，同 `nffile-three-tier.spec.mjs` 站7）。
