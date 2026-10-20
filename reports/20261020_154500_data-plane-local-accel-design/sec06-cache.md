## 6. 缓存一致性策略（G3）

底盘 = `nfFileCache.js`（§2.2）：字节在 Cache Storage、索引（LRU 序/字节数/时间戳/version）在 localStorage、LRU+256MiB、TTL 30min、配置可调。本节只设计**增量**，不推翻既有形态。

### 6.1 变更失效（mtime/hash 比对）

现状判据 = 纯 TTL（命中不做再验证，`nfFileCache.js:14-17` 有意取舍：nf-file 无条件 GET 再验证=全量重下）。失效增强走**取数时顺带比对**而非命中时再验证，保住「命中零取数」：

- version 已在读数（`size:lastModified`，`nfFileCache.js:290`）。扩展：任何一条腿**因其他原因**到达服务端/源端时（如铸票、列目录、WS 文件事件），顺带回传该路径现值 `{size,mtime}`；前端比对账本 version，不一致 ⇒ 静默淘汰该条（下次打开按未命中走）。
- 事件驱动腿（零成本高价值）：引擎侧已有 FileChangeLedger/filewatch（`src/main/scala/nebflow/core/FileChangeLedger.scala`）与 WS 通道 ⇒ 本机文件变更推一条 `nf-cache-invalidate {path}` 广播，前端按前缀匹配淘汰。设备侧件以对端文件事件为源（设备通道已有事件面则复用，无则 M2 补）。
- TTL 保留为**兜底**（事件丢失时陈旧窗上限），语义不变。

### 6.2 容量上限与淘汰

- 现状 256MiB 全局预算 + LRU 已达标（§2.2）。增量：**按 locality 分桶预算**（local 128MiB / device 96MiB / server 32MiB，可配），防一类大件（如 evidence 视频）把社交图片全挤出局——分桶在现有索引上加 `bucket` 字段，淘汰仍全局 LRU 但只在同桶内选受害者。
- 持久性分级：Cache Storage 面向「设备大件」（重下代价高），会话内存 Map 维持现状（快路）；server 桶可选 `Cache-Control` 让 HTTP 缓存替账本扛短周期件（#538 已给 max-age=300，免重复建设）。

### 6.3 多设备同文件去重

- 账本键从「单键」升为「内容键双索引」：主键仍为取数键（`nf:<path>` / `attach:<id>` / `dev:<deviceId>:<path>`），副键 = `hash:<sha256 前 16 hex>`。跨设备同一份文件（同一 repo 在 Mac/Windows 各一份）只要 hash 相同即互相视为已缓存：`dev:B:<path>` 未命中时可用 `hash` 副键命中的**另一设备条目**直接打开（字节已在浏览器，与它从哪台设备来无关）。
- hash 来源：设备通道分块传输本就逐块校验（§2.4），整件 hash = 校验链的免费副产品，**入账时写入**；local/server 腿无 hash 时以 `size:lastModified` 代位（副键不命中即放弃，不发起额外读取）。
- 冲突面：同一 hash 多键共存时只存一份字节，索引多键指向同一 Cache 条目（引用计数淘汰：全部键出局才删字节）。

### 6.4 明确保留的取舍（申报）

- 命中不再验证（保零取数）——TTL/事件双兜底；
- 多标签并发写索引竞态可容忍（最坏少记一次 touch，`nfFileCache.js:12-13` 申报）；
- 清缓存用户入口仍不做（底盘注记「候作者示」，`nfFileCache.js:234`），本设计建议 M2 一并补（设置面板一个「清空文件缓存」+ 占用读数，`nfCacheStats()` 读数面已备，`:222-232`）。
