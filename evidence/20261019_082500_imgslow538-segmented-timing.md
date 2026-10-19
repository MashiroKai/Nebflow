# #538 图片打开慢量化批 — 分段计时表 + 现状与三档目标差距分析 + 候选排序

时间：2026-10-19 07:50 – 08:30 · 执行：#538 量化 delegate · 纪律：引擎零重启、VPS 零改动（只读 HTTPS 探测）、远程腿走作者现役路径（neblink.nebflow.space，作者 Safari 真会话贴探针实测）
**批内口径（作者 07:48 产品形态令）**：本批为纯测量+方案。三档管线（①本机即开 / ②已下载到本机即开 / ③远程先开标签+下载进度圈）由新批承接实现；批内曾实现并测试过的 nf-file Cache-Control 小修**已按令撤出落 main 通道**，降级为候选 #2（patch 已备：`evidence/20261019_imgslow538-timing/nf-file-cache-header.patch`，新批裁量吸收）。

## 结论（一句话）

**同一条开图链（2.8MB PNG），本机直连腿全链中位 ≈45ms，远程腿活窗中位 ≈2.79s（62×），大头 = 字节穿隧 1531ms（55%）+ 铸票/兑换两段各 ~0.6s 的隧道往返（各 22%）+ 解码 10ms（0.4%）；今晨作者会话还实测到 15min 死窗全程 403（mint 每次 ~308ms 空转、100% 失败）+ 活窗内 1/5 随机 ERR——死窗与重铸摩擦是叠加在其上的可用性问题，relay 根修（fix/ingress-sliding-refresh）+ 缓存头候选（patch 已备）可把「重复打开」降到 0 字节、把「首开失败」清零。**

## ① 分段计时表（真环境对照组）

测试对象：同一条链 `POST /api/nf-ticket` → `GET /api/nf-file?path&ticket` → 解码/渲染。
测试件：`<dataRoot>/plots/20261019_b538_timing-probe.png`（2.8MB 不可压噪声 PNG，白名单命名空间 plots/）；对照件 0.7MB PNG。

### 本机直连腿（http://127.0.0.1:8080，N=12 HTTP 层 + N=10 浏览器层 Chromium×真实 nfTicket 模块）

| 分段 | 中位 | p95 | min | max |
|---|---|---|---|---|
| RTT 地板（204 探针） | 0.7ms | 20ms | 0.6 | 20 |
| 铸票（服务端） | 1.0ms | 2.9ms | 0.8 | 2.9 |
| 铸票（浏览器模块全链） | 8.7ms | — | 3.8 | 15.4 |
| 兑换 2.8MB（TTFB≈total） | 11.7ms | 24.6ms | 6.5 | 24.6 |
| 兑换 0.7MB | 2.3ms | 5.0ms | 1.8 | 5.0 |
| 同 URL 重取（warm） | 6.6ms | 11.1ms | 6.0 | 11.1 |
| 条件请求 304（ETag） | 1.6ms | — | — | — |
| Range 256KB（206） | 17.9ms | — | — | — |
| fetch 2.8MB（浏览器） | 26.3ms | — | 15.9 | 38.7 |
| 位图解码 createImageBitmap | 2.5ms | — | 2.2 | 6.9 |
| `<img>` decode（缓存 URL） | 3.8ms | — | 3.2 | 5.6 |
| Canvas drawImage | 3.4ms | — | 2.8 | 4.2 |
| **全链合计（mint+bytes+decode）** | **≈45ms** | | | |

### 远程腿（https://neblink.nebflow.space，作者 Safari 真会话，死窗/活窗对照，探针 v2 实测）

| 分段 | 死窗（n=5） | 活窗（n=4 ok，5 轮 1 ERR） |
|---|---|---|
| 铸票 mint | **403 ×5/5，med 308ms**（285-400）——relay 门拒，零字节、零隧道帧 | **med 635ms**（588/613/632/635/969） |
| 兑换往返 TTFB | —（未发生） | **med 609ms，p95 644ms** |
| 字节传输 | — | **med 1531ms**（1247-2304） |
| 解码 | — | **med 10ms** |
| `<img>` 直开（同 URL 已缓存） | — | 5-13ms |
| 全链合计 | **失败** | **≈2.79s（中位）≈ 62× 本地** |
| 可靠性 | 100% 失败 | 1/5 随机 ERR（兑换段，mint 成功后字节腿挂——疑似隧道帧丢失/循环中断） |

### 结构常数（实测）

- Mac→VPS ping：**264ms avg**（247-303）——每个远程分段至少含 1-2 个该 RTT（铸票 635ms ≈ 2.4×RTT）。
- TLS 握手：243ms（HTTP/2 复用后摊销）；直连正常，**代理 7890 路径慢 3×**（1.64s vs 0.54s）——备用代理不应进测量/日常路径。
- 穿隧字节膨胀：BodyFrame 256KB 分片 ×11，**base64+JSON 信封 = +33.4% 线上字节**（2.81MB → 3.74MB），仅此一项 ≈ 字节段的 ~25%（2.8MB 图 ≈ 380ms）。出处 `PortalWireCodec.scala:348`（b64Encode）/`PortalTunnelBridge.scala:1004`（BodyChunkBytes=256KB）。
- **穿隧响应头原样透传（实测 cc=""）**：link-server 不给隧道响应加盖/改写 Cache-Control ⇒ 引擎侧缓存头小修对远程腿直接生效，**无需 VPS 配合改动**。

## ② 票据复用检查（file:line）

**前端已有会话级票据缓存，且本批实测精确命中：**

- 缓存本体：`nfTicket.js:35`（`cache: Map<path,{t,exp}>`）；新鲜判 `isFresh`（`nfTicket.js:149`，TTL−5s skew）；复用+在途去重 `mintTickets`（`nfTicket.js:252-272`，F4 同轮并发合并）；强制重铸 `reMintAll`（`nfTicket.js:300`，先 settle 在途轮再删缓存——防旧值复活）。
- 复用腿（缓存命中=零请求，实测 0 次 mint）：Canvas 开图 `viewers/image.js:162`（`ticketUrl`）；聊天内联图 `messages.js:2197`；xlsx/docx/pptx/epub/pdf/video 各 viewer 同构。
- **重铸腿（策略性强制，非缺陷）**：Card 首挂载 `cardRegistry.js:492` `mintForCard(false)`→`mintTickets`（吃缓存）；**重挂载 `cardRegistry.js:552` `mintForCard(true)`→`reMintAll`**——T2 ②「凭据永不跨挂载重放」是刻意的凭据卫生策略（长聊天滚上滚下、iframe 被浏览器丢弃再回来，每张卡的图都重铸一次；远程腿每次 = 635ms）。lightbox `lightbox.js:188` 每次开启 reMint 同理。
- 服务端：`NfTicketStore.issue`（`NfTicketStore.scala:104`）每次铸新 token、无会话级去重——**不需要修**：R4=票内无限次使用，前端缓存已把重复请求消为零；服务端去重无安全收益。
- 实测读数（浏览器层 N=10 轮）：`/api/nf-ticket` 请求总数 = 20 = 10×(冷铸1+强制重铸1)，warm 调用 0 请求，精确符合契约。死窗下每次铸票尝试白耗 ~308ms 后 403（今晨 5/5）。

**结论：票据复用已存在且工作正常；唯一「重复铸票」面是 T2 策略性重挂载重铸 + 页面 reload 后全量重铸（缓存是页内存，reload 即清）。不建议放开 T2（凭据卫生 > 635ms/次的边际收益）。**

## ③ 渲染路径

- **作者点缩略图的腿 = 全量字节后才渲染，零渐进**：`messages.js:2542-2548 savedPathBlob` = `fetch(ticketUrl)` 整体收完 → `previewBlob` → Canvas viewer 挂 `objectUrl`（blob:）。2.8MB 时 = 字节段 1531ms 全部吃完才开始解码+渲染。
- 直连 `<img>` 腿有流式渐进（`viewers/image.js:162` `img.src = ticketUrl`，浏览器边收边绘，progressive 格式可见渐进；onload 仍等全量）。但该腿无加载占位/骨架（pane 在 onload 前空白，失败走 `:34-68` 错误面板）。
- HTTP 缓存利用：改前 `/api/nf-file` **无 Cache-Control**（但有 ETag+Last-Modified，304 重验证本地实测 1.6ms 可用）；浏览器只能靠启发式新鲜度（RFC 9111 §4.2.1 ≈ (now−Last-Modified)×10%，刚生成的图≈几秒 ⇒ 形同虚设）。远程实测：同 URL 二次 `<img>` 打开 5-13ms（内存缓存命中）——**浏览器愿意复用，缺的只是一条明确策略头**。

## 现状与三档目标的差距分析（作者 07:48 令口径）

| 档 | 目标 | 现状（本批实测/代码定位） | 差距 → 新批输入 |
|---|---|---|---|
| ① 本机文件 | 即开 | **已达标**：直连腿全链 45ms（mint 1ms + 字节 26ms + 解码/绘制 ~6ms）；本批浏览器层实测无任何一段 >40ms | 无需专门管线；只需保证新三档分派判据对「本机」零误判（把本机件误走下载腿 = 人为变慢） |
| ② 已下载到本机 | 即开 | **未达标（条件性）**：无「已下载集合」账本，`savedPathBlob`（`messages.js:2542`）每次整链重取；唯一的快路是浏览器 HTTP 缓存对**同 ticket URL** 的命中——但 (a) 无 Cache-Control，只有启发式新鲜度（对刚生成的图≈几秒，形同虚设）；(b) 票 URL 随重铸变化即缓存键失效（T2 重挂载强制重铸，`cardRegistry.js:552`）；(c) 内存命中实测可行（远程同 URL 二次 `<img>` 5-13ms） | ②档需要：已下载账本（哪件字节在哪）+ 本地字节快路（blob/缓存直挂，零铸票零穿隧）；**候选 #2 的缓存头 patch 把 (a) 确定化**（同票 5min 内 0 字节、之后 304 一个 RTT），是②档的服务端地基 |
| ③ 非本机文件 | 点击立即开标签 + 圈形下载进度 | **未达标（结构性）**：点缩略图腿全量字节收完才开面板（`messages.js:2542` → `previewBlob` → `openTab(objectUrl)`），进度全程无反馈（pane 空白/无占位）；直连 `<img>` 腿（`viewers/image.js:162`）有流式但无占位、无进度；失败才出错误面板 | ③档需要：先 `openTab`（<100ms）→ viewer 挂占位 + 进度环；进度数据源现成——穿隧本体就是流（256KB BodyFrame 序列，2.8MB=11 帧），`fetch` 流式 reader 或 XHR onprogress 即得 received/total（Content-Length 已有）；缓存头候选让「下到一半取消再点」从 0 续传（Range 已支持） |

**量化口径给新批**：③档达标后，远程首开感知 = 标签 ~100ms + 首屏渐进（v.s. 现状中位 2.79s 黑等）；②档达标后，重复打开 = 0 网络（v.s. 现状最多 2.79s）；①档守住 45ms 即是全部要求。

## ④ 优化候选排序（收益 × 工作量；patch 级输入供新批裁量）

| # | 候选 | 预期收益（实测口径） | 工作量 | 状态 |
|---|---|---|---|---|
| 1 | **relay 根修 `fix/ingress-sliding-refresh` 部署** | 清零死窗：今晨实测死窗 mint 100% 403（~308ms/次空转+sessionGate 自动 reload 整页重穿隧 5-10s/次）；活跃会话每 15min 必撞一次。**注意它不缩短活窗 635ms 铸票/609ms 兑换的结构性 RTT** | VPS `docker compose up -d --build neblink-server`（restart 无效；代码+589/589 测试已备） | **待作者部署窗口；本批死窗实锤即其部署论据** |
| 2 | **`/api/nf-file` Cache-Control patch（已备，未落 main）** | 重复打开同图（同票 URL，5min 内）= **0 字节过隧，省 ≈2.78s/次**；超 5min = 1 次 304 重验证（1 RTT，无字节）。头透传已实测（cc=""），纯引擎侧生效；是②档「已下载即开」的服务端地基 | patch 已写好+测试已写好（`evidence/20261019_imgslow538-timing/nf-file-cache-header.patch`：200/206/304 腿 `private, max-age=300`+401/403/416 `no-store`+304 重述策略，+4 spec 测试，批内曾 23/23 全绿）；**新批在改文件打开管线时一并吸收/裁量** | 候选（按 07:48 令撤出独立落 main 通道） |
| 3 | 前端会话级 bytes LRU（重开已开图零请求，跨 HTTP 缓存语义） | 与 #2 高度重叠；#2 落地后边际收益小 | ~0.5-1 天 + 失效策略 + 测试 | 只出方案：**建议不做**，除非出现「同图频繁重开但票 URL 变化」的实际模式 |
| 4 | Canvas 渐进占位/流式首开（点缩略图腿改流式 `<img>` 或加骨架） | 新图首开感知延迟最多省 ≈1.5s（字节段 1531ms 变为渐进可见）；骨架占位版收益减半 | 流式版 1-2 天（类型判定靠 blob magic bytes 依赖全量，需 Range 预读或信任扩展名）；骨架版 ~0.5 天 | 只出方案：骨架版可做，流式版缓 |
| 5 | 穿隧二进制帧（去 base64+33%） | 字节段 -25%（2.8MB 图 ≈ -380ms）；所有穿隧流量普适 | 大：codec + link-server 对端 + 兼容窗口（2-3 天） | 只出方案 |
| 6 | 票据复用 | 已存在（见 ②），无需新修 | 0 | 关闭 |

**优先级：1（部署即得，消除失败面）→ 2（已落地，消除重复传输面）→ 4 骨架版（可选）→ 5（远期）。**

## 候选 #2 patch 存档说明（原「小修交付」节，按 07:48 令改列候选）

- patch：`evidence/20261019_imgslow538-timing/nf-file-cache-header.patch`（212 行，`git apply` 即用；基于 81a3be27a 基线对 `NfFileRoutes.scala` + `NfFileRoutesSpec.scala`）。
- 内容：`NfFileRoutes.scala` — 200/206 腿 `Cache-Control: private, max-age=300`（`private` 必须——URL 载票；max-age 远低于票 TTL 1800s，缓存条目永不过其凭据寿）；304 重述同策略（http4s 静态 304 不带任何缓存头，须补）；401/403/416 `no-store`；ETag/Last-Modified/Accept-Ranges 原样保留。`NfFileRoutesSpec.scala` — +4 测试（200/206 头、304 重验证+策略重述、401/403 no-store）。
- 批内验证记录：NfFileRoutesSpec 23/23 绿、相邻 NfTicketRoutesSpec + NfPathEncodingProbeSpec 26/26 绿、scalafmt 干净（仅触及该两文件）。
- 附带注册（patch 未包含，upstream nit）：http4s `StaticFile` 的 304 不重述 ETag（RFC 9110 §15.4.5 要求）；浏览器复用存储验证器故重验证循环可用，修复需复刻其内部 ETag 算法，不值当。
- 与三档管线的关系：③档「下到一半取消再点」可顺带获得 Range 续传语义（206 腿已带同款缓存头）；②档同票 URL 命中从此确定化。若新批自建字节账本/缓存层，服务端这层仍是正交地基，吸收无冲突。

## 附带发现（顺手记录）

1. **门户新登录断链**：`GET neblink.nebflow.space/api/auth/callback` 现回 **503**（两轮复现；link-server 侧；本机 headless Logto 全流程走到 callback 即挂）——新会话起不来，老会话（作者 Safari）不受影响。**作者下次 VPS 部署窗口一并查**；在它修好前，任何「新设备/新浏览器登录」路径不可用。
2. **活窗 1/5 兑换 ERR**（mint 成功后字节腿失败）：远程腿存在随机单次循环失败，用户感知 =「偶尔点开失败，再点一次就好」。建议与 relay 根修同窗观察 PortalTunnelBridge backstop 日志（`portal body backstop fired`）。
3. 探针脚本与原始读数存档：`evidence/20261019_imgslow538-timing/`（local-timings.json / browser-local.json / remote-probe-snippet-v2.js——作者真会话 5 轮原文在其中 / nf-file-cache-header.patch——候选 #2 现成 patch）。
4. 测试图 `plots/20261019_b538_timing-probe.png`（2.8MB 噪声 PNG）留在白名单命名空间，供后续复测复用。
