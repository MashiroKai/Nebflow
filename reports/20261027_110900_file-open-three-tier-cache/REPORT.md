# 收口批 F：文件打开加速三级缓存（台账 #902）

批次：20261027_110900_file-open-three-tier-cache
作者令：10-26 晨「本机秒开 / 持久缓存 / 无端秒开」三条口径（台账 #902 零 commit 欠账，本批收口）。

## 1. 链路盘点（一句话）

文件打开三条腿——本机 `readFile`/`pop.readFile`（每次开盘全量 `os.read`，无任何进程内状态）、跨设备 `readFile{device}`（每次开盘走 `resolveBrowseTarget` → `transferGetScopedChunked` 分块全量拉取 → `PointerPickup` 落盘重读，即使对端文件一字未改；`wholeSha256` 只是免费副产品没被消费）、二进制元数据帧腿（`/api/nf-file` + nf-ticket 逐次铸造，字节永远从源重流）——重复开销 = **重复全量拉取 + 每开盘全量磁盘读 + 零内存态**（票与分片本身无重复：票是前端一次性铸造、分块腿已有断点续传）。

## 2. 三级缓存结构（新 `nebflow.core.tools.FileOpenCache` 单例）

| 层 | 键 | 命中效果 | 落位 |
|---|---|---|---|
| ① 进程内热缓存 | 指纹键（路径 + mtime + size） | 二次打开零磁盘读直出（`hotHits`） | 内存 LRU，缺省 64 MiB |
| ② 持久缓存 | 同指纹键 | 引擎重启后 `dropProcessStateForTest` 复演命中持久层（`diskHits`），源文件零读 | `<dataRoot>/cache/fileopen/v1/`：`index.json` + blob（tmp+move 原子写、全局 LRU、缺省 512 MiB 上限、单件上限 96 MiB ≥ MaxScopedFetchBytes 50 MiB、**0600**） |
| ③ 跨设备层 | `dev:<deviceId>:<path>` | 开盘先做一次父目录元数据探测（`transferListScoped`，零字节流动）拿 `{size,mtime}` 对指纹——命中即**跳过全量分块拉取**（`remotePullSkips`），字节从本机缓存经 `ensurePickup` 幂等落位 pickup（二进制腿 `/api/nf-file` absPath 契约保住），帧形逐键同构 + additive `cached:true` | 与 ② 同一持久层 |

**接线**：`handleReadFile` 整件腿（≤2MB）与 `handlePopReadFile` 整件腿走读穿；`readRemoteFile` 前置探测/命中腿（探测失败、指纹变、无缓存一律回落原分块拉取——缓存永不挡路）；写穿失效挂在 `NfCacheInvalidator.invalidated` 单点（Write/Edit/MultiEdit/createFile/deletePath/movePath/writeFile 全量覆盖）+ `writeRemoteFileForScaffold` 失效 dev 键。**>2MB 有界腿不进缓存**（部分读 ≠ 整件内容，缓存字节恒等真件内容是红线）。

## 3. 语义红线落实

- 缓存只对「指纹未变」的读生效：本地每开盘 stat、远端每开盘探测元数据；指纹不符 = 条目立即淘汰 + 全量重取，绝不显示陈旧内容（同毫秒同尺寸改写为申报取舍，写穿失效补齐）。
- 外部位修改：读侧 stat/探测兜底闸天然覆盖；引擎写路径走写穿单点。
- 缓存文件权限 0600（POSIX；Windows 跳过，同仓惯例）。

## 4. Spec 结果（`FileOpenCacheSpec` 8/8 绿 + 邻域回归全绿）

作者三条钉全数钉住（计数器断言面 = `statsSnapshot` 单调计数器）：

- T1 二次打开不走全量取数：连开两次 `diskReads` 只 +1，第二次 `fromCache=true`；
- T2 指纹变化后缓存失效：改写 ⇒ `invalidations` +1 + 全量重读 + 内容必新；
- T4 跨设备二次打开命中本机缓存：同指纹命中、异指纹未命中且立即失效；
- 红线钉：T3 重启后持久层命中（源文件零读）；T5 blob 0600；T6 pickup 落位幂等（同 matFp 零写）；T7 残缺指纹（缺 mtime）绝不放行；T8 写穿失效后同指纹必未命中。
- 邻域回归：`NfCacheInvalidatorSpec` 5/5、`WsBrowseDeviceLegSpec` 3/3、`ExplorerDotDirDanglingSymlinkSpec` 8/8 —— 合计 24/24 绿（2026-10-27 11:09 sbt 实跑）。

## 5. 变更面

- 新增 `src/main/scala/nebflow/core/tools/FileOpenCache.scala`（三级缓存单例 + 计数器面）
- 新增 `src/test/scala/nebflow/core/tools/FileOpenCacheSpec.scala`（8 钉）
- `WsFileOpsHandlers.scala`：readFile/pop.readFile 整件腿读穿 + readRemoteFile 探测/命中/统一帧形
- `NfCacheInvalidator.scala`：同一写事实同步失效引擎侧账面（写穿红线，attempt 兜底）
- `RemoteExecutor.scala`：`writeRemoteFileForScaffold` 写穿失效 dev 键（1 行）

## 6. 申报取舍与开放点

- mtime+size 双元组是作者钦定指纹；同毫秒同尺寸的内容改写理论上可逃逸（本地读由写穿失效补齐，跨设备探测同口径）。
- 远端探测 = 父目录元数据列举（零字节流动，与既有 `wsBrowse.list` 同一工具通道）；裸文件名（无父目录）路径旁路。
- 前端 `nfFileCache.js`（浏览器侧 Cache Storage）与本缓存分层不同面（浏览器↔引擎），互不替代；本批零前端改动。
- 同一逻辑文件在 A/B 两设备各一份的 sha 副键去重：sha16 已随条目持久化（`RemoteHit.sha16` / 索引 `sha16` 字段），但命中闸仍以「探测到的 size+mtime」为准——跨设备 mtime 天然不同，副键直配留待后续批（作者口径「跨设备缓存复用」的主场景 = 同设备二次打开免拉取，本批已收口）。
