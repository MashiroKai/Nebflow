# 手机端经官网进控制台 · 启动加载瀑布量化报告（只测不修）

- 批次：2026-10-19 18:37 CST · 方法论复用 `evidence/20261019_082500_imgslow538-segmented-timing.md`（#538 分段计时）
- 测点：作者 Mac 本机直连引擎（127.0.0.1:8080，真机同源）、mystand.space / link.mystand.space 实测探测、headless Chrome CDP 节流模拟手机 CPU/网络
- 原始数据：`measurements/*.json`（逐请求瀑布）、`transcript-probes.txt`、`dist-inventory.txt`、`devtree-module-graph.txt`
- 零源码改动、零 push。引擎实测的是**正在运行的引擎**（源码 dev 树）；审计对象是**当前工作树** a68c217af，两者差异见 §3.5。

---

## TL;DR — 慢的四个根因（按实测贡献排序）

| # | 根因 | 证据 | 量级 |
|---|------|------|------|
| 1 | **运行中的引擎在跑 dev 源码树**（webdist jar 未打包/未启用）：首屏 ES 模块图 **155 个文件 / 4.6MB 原始 JS**，浏览器按 import 深度分波发现，**最深 16 波**；每波在手机+隧道 RTT 下串行等待 | `measurements/devtree-module-graph.txt`；隧道模型实测 DCL **25.0s** | 换 dist 后同模型 DCL **8.3s（-67%）** |
| 2 | **全部静态资源 `Cache-Control: no-cache`**（含内容哈希文件名的 dist 资产）：每次打开 = 每资产一次校验往返，经隧道每次 ≈1 RTT；**回访和首访一样慢** | StaticRoutes.scala:136/173/392/429/469；实测 196 次资产请求/次回访 | dist+immutable 后回访请求数 198→**~2** |
| 3 | **隧道单 WebSocket + base64 膨胀**：全部字节挤一条 Mac↔VPS 连接，+33.4% 膨胀，实测 Mac↔VPS ping **333ms**（比 #538 早间 264ms 更差），单请求全程 ~0.5–1.5s | PortalTunnelBridge.scala:35/545/844；#538；本批实测 ping | 每请求地板 ≈ 手机RTT+333ms×2 |
| 4 | **桌面级巨件挤占手机首屏带宽**：monaco 编辑器空闲预载（3.7MB 原始/932KB gzip，占 boot 字节 31%）、946KB 品牌 PNG 头像、双语 locale 全量（en+zh-CN 各 144KB 原始）、6 个 vendor classic 脚本 1.1MB 头部阻塞 | `boot-dev-tunnel.json`；monacoEditor.js requestIdleCallback 预载；index.html head | 手机节流下 monaco 单件 6.9s |

---

## ① 加载路径还原（首屏 → 可交互）

```
手机 ──4G──▶ mystand.space/portal (Vercel, Next.js)
              │  未登录 → /login?next=%2Fportal（实测 28KB HTML，TTFB 0.83–1.41s①）
              │  已登录 → portal 设备卡 → 控制台 URL
              ▼
            link.mystand.space (= neblink.nebflow.space, 45.32.62.185, Caddy + link-server, HTTP/2)
              │  Logto 会话校验；未授权请求 403 实测 0.44–1.54s②
              ▼
            隧道 WebSocket（Mac ↔ VPS，实测 ping 333ms③；请求按 requestId 并发进
            PortalTunnelBridge，所有回程帧过单 socket 锁④，body 以 256KB body_frame
            分帧 base64+JSON，+33.4% 膨胀⑤）
              ▼
            Mac 引擎网关（indexWithBrand → StaticRoutes / GzipMiddleware）
              │  index.html 11.4KB gz（no-cache, Last-Modified, 无 ETag⑥）
              ▼
            浏览器瀑布（实测见 §2）：
              HTML ──▶ 6 个 vendor classic <script>（head 阻塞，1.1MB 原始）
                   + 28 个 <link rel=stylesheet>
              + ES 模块图 155 文件/4.6MB，按 import 深度 ~16 波发现
              + 手机视口：mobile/bootstrap.js 再动态 import 4 个控制器（额外一波）
              + requestIdleCallback：monaco 932KB gz 预载挤进窗口
              + 11 个 REST 调用（/api/agents、/api/chains、/api/neblink/status×3 …）
              + WS /ws 建立 → welcome/sessions → 会话列表渲染 = 可交互
```

① author Mac 直连 curl；#538 证明作者浏览器走系统代理时该腿慢 ~3×，手机不受此影响。② 403 是否含隧道往返无法匿名区分。③ 本批 18:3x 实测，#538 早间 264ms。④ PortalTunnelBridge.scala:35,545,844。⑤ #538 实测 2.8→3.74MB。⑥ gzip 中间件有意剥 ETag（GzipMiddleware.scala:77-90，Last-Modified 校验路径保留，curl 实测 304 生效）。

## ② 分段计时表

**结构常数（本批实测）**

| 常数 | 值 | 来源 |
|---|---|---|
| Mac↔VPS ping（隧道腿） | **333ms avg**（#538 早间 264ms） | transcript §C |
| 手机↔VPS | 未测（无手机）→ 模型取 50–150ms | 假设，见 §4 局限 |
| 隧道单请求全程（Mac 视角） | mint 635ms med（#538）；未授权 403 0.44–1.54s（本批） | #538 + transcript §B |
| 隧道吞吐 | ~2.5MB/s 有效（#538 图像 2.8→3.74MB ≈1.5s） | #538 |
| 隧道膨胀 | +33.4%（base64+JSON；256KB/帧） | #538 + PortalTunnelBridge.scala:1004 |
| 本机引擎/资产 TTFB（warm） | index 5–25ms；js 2–39ms | transcript §F |

**整段 boot 对照（headless Chrome，冷缓存，obs 45s/15s）**

| 场景（dev 树 = 现运行） | 请求数 | 传输量 | FCP | DCL/load | WS |
|---|---|---|---|---|---|
| 本机直连 · 桌面 CPU（×3 中位） | 198 | 3.04MB | 0.15s | 0.58s | 403* |
| 本机直连 · 桌面 CPU（冷 Chrome 首跑） | 199 | 3.04MB | 1.78s | 2.80s | 403* |
| 手机模型：4×CPU + 150ms RTT + 1.6Mbps（Lighthouse Slow 4G） | 199 | 3.04MB | 1.90s | **12.47s** | — |
| 隧道模型：4×CPU + 500ms RTT + 1.2Mbps | 199 | 3.04MB | 4.72s | **24.96s** | — |
| **对照：dist 产物 · 同隧道模型** | **74** | **1.80MB** | 1.41s | **8.34s（-67%）** | — |
| **对照：dist 产物 · 同手机模型** | **74** | **1.80MB** | 0.98s | **5.48s（-56%）** | — |
| 对照：dist 产物 · 直连桌面 | 74 | 1.80MB | 0.09s | 0.41s | — |

\* 本机无会话 cookie，WS/REST 403 快速失败；含会话时每个 REST 再加一次引擎往返。WS 建立腿本机握手 <10ms；远端经隧道估 1–2 RTT（≈0.8–1.3s）+ link-server 会话校验。

**远程腿实测（匿名可达部分）**

| 腿 | 实测 | 备注 |
|---|---|---|
| mystand.space/ → 首字节 | TTFB 0.83s | Vercel 边缘（76.76.21.21） |
| mystand.space/login?next=/portal | 28KB HTML，TTFB 1.41s | 门户/登录腿，非控制台关键路径 |
| link.mystand.space/ 302→login | total 0.52s | VPS Caddy |
| link.mystand.space 静态路径（未授权） | 403 in 0.44–1.54s | 4 个探测点，含 TLS |
| 隧道腿（Mac↔VPS） | ping 333ms | 手机端再叠加自身 RTT |

**隧道模型瀑布分段（dev 树，`boot-dev-tunnel.json`）**：HTML 0.5s → head vendor+CSS 波 1–3.5s → 模块图波次推进（16 波深，尾部模块 8–9s 才完成）→ 模块求值 + monaco 预载 6.9s（单件）→ REST 11 发（0.5–1.5s/个，重叠）→ WS。dev 树最慢 10 个请求全是模块长尾 + monaco（见 json `requests` 字段 durMs 排序）。

**回访成本（no-cache 后果）**：直连回访（warm cache）仍发 **196 个请求**、传 0.96MB、DCL 0.26s —— 本机便宜；经隧道每次校验 ≈1 RTT（500ms 模型 × 196 请求，h2 多路复用 + 单 socket 泵出）→ **回访估 30–100s，与首访同量级**。这就是"每次进都慢"的机理。（注：`boot-dev-tunnel-revisit.json` 的 23.5s 是中间跑 dist 时清了缓存的重复冷启动，仅作冷启动复现；真回访数据点用直连回访外推，已在 §4 注明。）

## ③ 清单审计

**3.1 build-web 产物（`dist-inventory.txt`，worktree a68c217af）**

| 项 | 值 |
|---|---|
| 目录总大小 | 31MB（440 文件，**含 sourcemap 7.2MB**，build-web.mjs:128 `sourcemap:'linked'` 随产物打包进 jar） |
| 非 map 原始 | 23.0MB；压缩后文本 5.07MB gzip + 二进制 2.14MB（png/ico/woff2 等） |
| 首屏关键路径（gzip） | index.html 11.7KB + app js 73.7KB + app css 66.8KB + **6 个 vendor classic 1.1MB原始/0.30MB gzip（不经 esbuild，原样拷贝、head 阻塞）** |

Top10 chunk（raw/gzip）：chunk-EWHDF4QI 322/95KB · app 261/73KB · chunk-CF25Z7HK 251/70KB · chunk-STNKVVXX 103/32KB · delegateChainView 98/30KB · chunk-ZZ4QPURE 72/23KB · chunk-PM7E2NWS 61/19KB · chunk-JGFKMDVE 50/16KB · chunk-F2SZYYCK 48/10KB · chunk-BQYWERPN 41/12KB。vendor：lucide 355/82KB · lottie 306/77KB · katex 275/76KB · highlight 125/42KB · qrcode 58/12KB · marked 35/11KB。

**3.2 网关压缩（file:line）**

| 项 | 状态 | 位置 |
|---|---|---|
| gzip | ✅ 开启，仅 "/" 静态树挂载，压缩白名单 text/js/json/xml/svg；/api 结构性不包（SSE 安全）；/ws 显式守卫 | GzipMiddleware.scala:45-49, GatewayMain.scala:1480 |
| brotli | ❌ 无 | — |
| ETag | gzip 时有意剥离（http4s etagMatch 弱化不可行的结论有注释+测试钉住），回落 Last-Modified；curl 条件请求 304 实测生效 | GzipMiddleware.scala:77-90 |
| Vary: Accept-Encoding | ✅ 全压缩响应带 | GzipMiddleware.scala:42,88 |
| 实测响应头 | /js/* /css/* 有 `Cache-Control: no-cache`；**/vendor/* 无 Cache-Control 头**（无 CC=启发式新鲜度≈0 → 同样每次校验） | transcript §D |

**3.3 缓存头现状**

| 资产 | Cache-Control | 校验器 | 可 immutable？ |
|---|---|---|---|
| / （indexWithBrand） | no-cache（StaticRoutes.scala:136） | Last-Modified | 不应（入口须实时） |
| /assets/**（**内容哈希文件名**） | **no-cache**（StaticRoutes.scala:173） | Last-Modified | **完全可 `immutable, max-age=31536000`** —— 现状哈希白做 |
| /js/** /css/**（dev 树） | no-cache（:469 等） | Last-Modified | 不可以（文件名无哈希） |
| /vendor/** | **无 CC 头**（实测；:392 注释称应 no-cache） | Last-Modified | 否 |
| /brand/** /favicon* | no-cache（:429） | Last-Modified | 否（换 logo 流依赖） |

**3.4 首屏关键路径 REST 串行度**

boot 窗口内 11 个 REST：`/agents/Nebula/agent.json`、`/api/agents`、`/api/canvas-tabs`×2、`/api/chains`、`/api/neblink/status`×3、`/api/nf-authcheck`、`/api/plugins`、`/api/portal/status`、`/api/projects`。浏览器侧近乎并行发出（无链式 await），**非首绘阻塞**；但每个经隧道是一次完整往返（~0.6s 起），且 `/api/nf-authcheck` 是其余调用的信任前置。可压缩空间：合并 status 类轮询、portal/status+neblink/status 合一、authcheck 并入首包 WS welcome。

**3.5 运行引擎 vs 当前工作树偏移**：运行引擎 favicon `?v=df9c1c…`、头像 `/brand/black-cat/source.png`（946KB，boot 时全量拉取）；工作树 HEAD 为 `?v=71a456…`、stand-symbol。dev 树测量基于运行引擎响应，结构性结论（模块图/缓存头/压缩）两版一致。

## ④ 候选排序（不动手）

**快赢（小时级，不动源码逻辑）**

| 候选 | 预估收益 | 工作量 |
|---|---|---|
| W1 **生产 jar 打 webdist**（`sbt -D<lower>.webdist=1 assembly`，build.sbt:24-31）并重启引擎 | 手机模型 DCL 12.5s→~5.5s、隧道模型 25s→~8.3s（实测对照），请求数 199→74 | 半小时（纯打包） |
| W2 哈希资产改 `Cache-Control: public, max-age=31536000, immutable`（StaticRoutes.scala:173 一处） | 回访请求数 198→~2；经隧道回访 30–100s→**1–3s**（index 一次校验+WS） | 1 行 + 测试 |
| W3 手机视口跳过 monaco 空闲预载（首开文件再载） | 隧道模型省 932KB（31% 字节）、估再 -25~30% DCL | 数行（bootstrap 判 MOBILE_MQ） |
| W4 /vendor/* 补 no-cache 头 + dist index 对 vendor 加哈希引用或 SRI | 头部一致性；vendor 更新即失效 | 1–2 行 |

**中期（天级）**

| 候选 | 预估收益 | 工作量 |
|---|---|---|
| M1 vendor 六件套转动态 import（dist 已有 chunk 机制，katex/highlight/lottie/qrcode 非首屏必需） | 首屏字节 -0.30MB gz、head 阻塞清零；估 DCL 再 -20% | 1 天（渲染回退路径） |
| M2 locale 单语加载（按 UI 语言取 en 或 zh-CN） | -50KB gz + 1 往返 | 半天 |
| M3 品牌 PNG 头像瘦身（946KB→webp/缩图，boot 不拉原图） | -0.9MB 原始 | 半天 |
| M4 REST boot 合并（/api/boot 单端点聚合 11 调） | 隧道下省 ~10 次往返 ≈3–6s | 1–2 天 |

**结构性（1–2 周）**

| 候选 | 预估收益 | 工作量 |
|---|---|---|
| S1 **静态资产走 Vercel 边缘**（mystand.space 域名发哈希资产，API/WS 仍走隧道） | 首屏 RTT 从 333ms+ 隧道 → 边缘 30–100ms；重访近即时；W1–W3 仍叠加有效 | 1–2 周（CI 上传资产+网关模板替换 origin+证书/CORS） |
| S2 Service Worker 离线壳（缓存 app shell+版本资产，打开即渲染，WS/REST 重水合） | 手机"每次进都慢"→ 秒开；弱网可用 | 3–5 天 + 版本失效策略 |
| S3 隧道二进制帧/按连接压缩（去 base64） | 字节 -33%；吞吐 ×1.5 | 1 周（双端协议版本化） |

**推荐路径**：W1+W2+W3 一个批次内完成（预期：首进 25s→<8s、回访 →秒级，手机实测验证），M1/M2 随后，S1/S2 作结构性底案。

## ④′ 方法与局限

- 手机 CPU/网络为 headless Chrome CDP 节流模拟（`Emulation.setCPUThrottlingRate` 4× + `Network.emulateNetworkConditions`）；**未用真手机**，iOS Safari 的 JS 引擎差一次系数，相对结论（dev vs dist、缓存头）不受影响，绝对值仅供排序。
- 隧道模型（500ms/1.2Mbps）= 手机 RTT 150ms + Mac↔VPS 333ms 的复合地板，非真实链路重放；真实手机全程请以作者 iPhone 会话按 #538 探针法补一次。
- 未授权探测无法区分 link-server 403 是否含隧道往返；WS 远端建立腿未实测（需会话）。
- 回访测量一例受中间清缓存污染（已在 §2 注明），结论以外推模型给出并标注。
- 引擎为生产运行物，探测全程只读（GET/WS 握手），未发任何写请求。
