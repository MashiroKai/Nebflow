# 手机首进快赢三刀 W1-W3 · 实施批交付报告

分支 `delegate/delegate-nebflow-98bce68b`（独立分支，基线 = main f59e0aa0a）· 2026-10-19 21:00 CST
上游依据：`evidence/20261019_183728_mobile-boot-waterfall/`（量化审计，已并 main）

## 作者令（逐字）

「首次加载还是很慢，根因已量化实证，本批落地修复」——W1 webdist 产物管线 + W2 哈希资产缓存头 + W3 monaco 按需。

## 结论（一句话）

三刀全落：**隧道模型首进 DCL 16.7s（今日 dev 树）→ 5.4s（-68%），请求数 197→71，传输 2.13MB→0.87MB；回访 0.01MB/1.9s（45 个哈希资产零网络零再验）**；dev 树回退路（无 dist / 显式 dev 标志 / dist 过期）逐路实测保持原行为。

## 实测汇总（瀑布批同一 harness：headless Chrome CDP，Emulation.setCPUThrottlingRate + emulateNetworkConditions）

| 场景（隔离实例 127.0.0.1:18777） | 请求数 | 传输 | FCP | DCL/load | JSON |
|---|---|---|---|---|---|
| **W1+W3 后 · dist · 隧道模型**（4×CPU+500ms RTT+1.2Mbps） | **71** | **0.87MB** | 1.28s | **5.39s** | `harness/measurements-boot-dist-tunnel.json` |
| **回访 · 同隧道模型**（KEEP_CACHE=1） | 67（其中 /assets 45 个全部 200/0 字节缓存回放、10 个 304、9 个 403 无会话） | **0.01MB** | 1.19s | **1.88s** | `harness/measurements-boot-dist-tunnel-revisit.json` |
| 对照 · dev 树 · 同隧道模型（本批 W3 已生效） | 197 | 2.13MB | 3.41s | 16.73s | `harness/measurements-boot-dev-tunnel.json` |
| dist · 直连桌面（cpu=1 无节流） | 68 | 0.87MB | 0.12s | 0.20s | `harness/measurements-boot-dist-direct.json` |

- 对照瀑布批基线（dev 树含 monaco 预载）：25.0s / 199 reqs / 3.04MB → 本批 dist 首进 **-78% DCL**。
- W3 单独贡献：dev 树对照（monaco 预载已删）16.7s vs 瀑布批 25.0s，-8.3s ≈ monaco 932KB 单件获取+竞争；dist 首进 monaco 请求 = **0**（`grep monaco` 零命中，见 JSON）。
- W2 回访机制实证：45 个 /assets 请求 `status=200, encodedBytes=0, 无 If-Modified-Since 往返`（immutable 缓存回放）；剩余 22 = index no-cache 再验 + /vendor 10×304（无 CC 头启发式再验，瀑布批已记录、不在本批三刀范围）+ favicon。
- 验收线：①隧道首进 <8s ✅（5.4s）；回访秒级 ✅（1.9s，字节 0.01MB）。

## W1 webdist — 构建产物管线 + 引擎优先喂 dist

**构建面**：`node scripts/build-web.mjs`（既有链，零改动）→ `build/web-dist/`（entry `assets/app-ZTG36S7E.js`，136 lazy chunks，164 源 0 孤儿，vendor 原样透传）。worktree 内已产出并实测。

**引擎面（Scala）**——`StaticRoutes.scala` 单判点三分支 `StaticSource`：

1. **显式 dev 标志**（逃逸活门）：`-Dstand.webdist=dev|0|false`（legacy `nebflow.webdist` 双读，与 build.sbt 同款）→ 永远 dev 树；
2. **classpath dist**（jar 打包，`-Dstand.webdist=1 assembly`，既有行为原样）；
3. **磁盘 dist**（新）：`<引擎工作目录>/build/web-dist/index.html` **存在且新鲜**（web/ 源树无任何文件 mtime 晚于 dist index）→ 喂磁盘 dist；
4. 否则 dev 树（现状）。

**新鲜度闸 = 不破坏 sbt run 热改的关键**：源文件一改（或 merge 触树），磁盘 dist 即判过期 → 自动回 dev 树，直到重跑 build-web.mjs。这正是 build.sbt 既有裁定（「跑过 build-web 的机器不得让 sbt run 悄悄喂陈旧 dist」）的运行时化——且作者主 checkout 现存 build/web-dist 相对 main 已陈旧（favicon `?v=df9c1c` vs 现 HEAD `?v=71a456`），无此闸下次重启就会静默回退前端修复，实测闸生效（见引擎日志两分支）。源树缺失（安装态运行）视为新鲜。FS 异常一律保守回 dev 树。

服务腿：`/` 与 HEAD `/` 经 `indexResponse` 三分支（`indexWithBrandFile` 为磁盘版，同一 brand 注入 + no-cache + Content-Length 契约）；`/assets/**` 经 `assetsRoutesFor(source)`（classpath 腿/磁盘腿同守卫链：尾斜杠拒、`..`/反斜杠拒，磁盘腿另加 realpath 包含栅）；dev 腿 404（与现状一致）。`/js`、`/css`、`/vendor`、`/brand` 全部原样（C1 契约：web/ 路径在任何模式保持可达）。

启动日志单行（`engine-boot-diskdist.log`）：`static tree: disk web-dist at <path> (fresh vs web/ sources) — -Dstand.webdist=dev forces the dev tree`；dev 时 `static tree: dev web/ sources`。

**wiring**：`WebSocketRoutes.scala` GET/HEAD `/` 两处改走 `StaticRoutes.indexResponse`（原 hasBundledDist 双写删除，单判点）。

## W2 缓存头 — 哈希资产 immutable

`StaticRoutes.HashedAssetCacheControl = "public, max-age=31536000, immutable"`，`/assets/**` 两腿（classpath+磁盘）统一挂载。build-web 产物命名全哈希（entryNames `[dir]/[name]-[hash]` / chunkNames `chunks/[name]-[hash]`）⇒ 内容寻址，重建即换名，immutable 永不可能喂陈旧字节；index 保持 no-cache 唯一裁决。非哈希资产（/js、/css、/vendor、/brand）逐字不动（vendor 无 CC 头现状 = 瀑布批 W4 候选，不在本批）。

**与 #538/#542 批不互踩**：#542 吸收的缓存头补丁落 `NfFileRoutes.scala`（nf-file 200/206/304 → `private, max-age=300`，拒绝腿 no-store），路由面与本批 `/assets` 零交集；本批未触碰 NfFileRoutes 一字。

## W3 monaco 按需

预载点唯查一处的确认：`main.js` 唯一 boot 期拉 monaco 的入口 = L3611-3612 `requestIdleCallback → import('./monacoEditor.js') → preloadMonaco()`（index.html 无 preconnect/preload 提示；`viewers/monaco.js`、`viewers/shared.js:660`、`canvas.js:541` 本就是开面时动态 import）。本批删除该预载（含 3 行注释改抑制说明），**桌面与移动一并生效**（原预载不分支视口）——首开代码文件时才付 932KB，`preloadMonaco` 导出保留（API 不变）。

## 分界申报（验收④）

| 改动 | 生效条件 |
|---|---|
| **W3 monaco 按需** | **刷新即生效**（纯前端）——但仅当引擎喂的是含本批 main.js 的树。作者现运行引擎（sbt run，:8097，旧字节码）喂旧 dev 树 ⇒ **现引擎刷新不可见**；重编译重启后的 dev 树可见（本批 dev 对照 16.7s 即 W3 在 dev 树的实测）。 |
| **W1 dist 静态面** | 候重启窗：Scala 侧改动需重编译重启。重启后：若 `build/web-dist` 存在且新鲜 → 喂 dist（作者主 checkout 的现存 dist 是陈旧的 → 新鲜度闸会判 dev 树，**须先重跑 build-web.mjs**）；`-Dstand.webdist=dev` 可强制回 dev。 |
| **W2 缓存头** | 候重启窗（同上）。且仅在引擎实际喂 dist 时可见——**引擎仍喂 dev 树则 W1/W2 刷新不可见，如实申报**：dev 树无 /assets 前缀（404），immutable 无从挂载。 |
| 作者引擎落地路径 | 主仓并批后：① 重启引擎（Scala 编译生效）② `node scripts/build-web.mjs`（产新鲜 dist）→ 重启后日志出现 `disk web-dist … (fresh)` 即 W1+W2+W3 全链生效。不重启 = 全部维持现状（零行为变化，已实测 dev 回退路）。 |

## 回归

**Scala**（隔离 sbt，-batch）：
- `sbt compile` ✅ 0 error；
- 静态路由家族 7 spec（AssetsRoutes/IndexWithBrandServe/JsStaticRoutes/VendorSubtree/BrandSubtree/BrandInjection/GzipMiddleware）：**59/59 ✅**（AssetsRoutesSpec 重写：W2 immutable 契约钉、磁盘腿、DevTree 闸、注入式解析矩阵 6 站、indexWithBrandFile 契约）；
- 全 `nebflow.gateway.*`：740/741，唯一失败 `GatewayAuthSpec`（`x-nebflow-credential` 硬编码字面量 vs stand 品牌 `x-stand-credential`）——**HEAD 复跑同败**，纯本 checkout 品牌测试物，与本批零关联。

**前端 spec 家族**（self-contained，playwright-core/chromium）：
- `f1-send-queue` ALL PASS ✅（点名项）；
- canvas/monaco 家族（点名项）：`canvas-preview-nav-parity` 63 PASS、`canvas-html-zoom` 23、`canvas-html-interactive` 17、`canvas-html-toolbar-overlap` 22/0、`canvas-video-viewer` 9/9、`card-image-zoom` 54 ✅；
- `imgcard-click-safari-compat` 2 checks 0 failed ✅；
- smoke 全套（真实例 ：18777，disk-dist 态）：**11/12**，唯一失败 `cookie-blocked 糊票入口` **HEAD 复跑同败**（stash 全部改动 + dist 移除后复现）；
- `askuser-attach`（#551）：dev 树引擎 **16/16 ✅**；对 dist 引擎跑则 harness 自身炸（读模块内部 `dom` 全局，dist bundle 无导出；另有连跑触发的 429 限频）——spec 环境依赖 dev 树，非回归；
- `mdscroll-toc-scroll` 崩（hidden #activity-bar 超时）：stash W3 后**同崩** = 预存在；
- `canvas-office-viewers`：缺未跟踪夹具（`.nebflow/evidence/20261006_pptx-mvp/...pptx` ENOENT）= #542 批已登记的 worktree 环境限制；
- `check-js-types`：总量 251 < 基线 273 ✅，但 gate 红：`askAttachments.js`（#551 昨晚并入的新文件）带 1 个 checkJs 错误且不在基线 → gate 判「新文件必须 clean」。**非本批文件**（本批未触碰），如实申报不代修。

**静态检查**：build-web 0 孤儿、163→164 源全图内。

## 发现的既有缺陷（本批不修，登记待裁）

1. **P1 · #551 askAttachments.js paste 腿 ReferenceError**：L45（模块级 `installPasteListener` 内）调用 `enqueueFiles`，而该函数只在 `mountAnswerAttachments` 作用域内定义（L226）——活卡上粘贴文件必抛 `enqueueFiles is not defined`。checkJs gate 红即此。建议独立批修（提升为模块级或经 card 分发）。
2. `GatewayAuthSpec` 硬编码 legacy 头名断言与 stand 品牌冲突（测试物陈旧）。
3. smoke `cookie-blocked` 与 `mdscroll-toc-scroll` 预存在失败（本机可复现，HEAD 同态）。

## 文件清单

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/gateway/StaticRoutes.scala` | W1 三分支 StaticSource/解析器/磁盘腿/索引三分支 + W2 immutable 头 |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | GET/HEAD `/` 改走 `indexResponse`（注释更新） |
| `src/main/scala/nebflow/gateway/GatewayMain.scala` | 启动日志 1 行（static tree 公告） |
| `src/test/scala/nebflow/gateway/AssetsRoutesSpec.scala` | 重写：新契约钉 + 解析矩阵（注入式，无环境依赖） |
| `src/main/resources/web/js/main.js` | W3：删 idle monaco 预载 |
| `evidence/20261019_210000_mobile-quickwins/` | 本报告 + harness + 5 份测量 JSON + 双分支引擎日志 |

## 方法与局限

- 隧道模型同瀑布批（4×CPU+500ms/1.2Mbps 复合地板），非真机回放；WS 腿无会话 403 快速失败（与瀑布批同口径，不进 DCL 主序）。
- 回访 304 计数含 /vendor 10 项（无 CC 头再验，W4 范围）；哈希资产零再验为 W2 直接证据。
- 隔离实例（独立 home /tmp/nf-w1-home + :18777）与 Chrome CDP（:9222 独立 profile）验证毕即清（端口复查 0 残留）；宿主 :8080/:8097 未触碰。
