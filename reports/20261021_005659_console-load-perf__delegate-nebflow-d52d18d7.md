# 性能批 · 控制台加载提速两目标（A 段止损落地 + B 段 dist 激活卡）

- 批次：20261021_005659 · 分支：`delegate/delegate-nebflow-d52d18d7`（自 worktree，main 零触碰）
- 依据：作者令两目标（①本地非常快 ②远程大大提速）+ 10-19 根因审计（`evidence/20261019_183728_mobile-boot-waterfall`，本批不重复审计，直接现状核起）
- 证据：`evidence/20261021_console-load-perf/`（测量脚本 + 逐请求瀑布 JSON + SW 活体测试，README 有全表）

## 结论先行

现状实测：dev 树仍在喂（150 js 请求）、gzip 全网关已开（A2 无需动）、monaco 预载已被 W3 摘除（真引擎首开 `/vendor/monaco/**` = 0 请求）。本地冷温两腿其实已达标（冷 DCL 247ms / 温 95ms）——「本地慢」的感知大头在引擎重启后的 REST/WS 装载，不在静态瀑布。真正的欠账在远程腿：隧道冷 9.2s ✗、**隧道温 8.8s ✗（171-183 次 no-cache 304 往返，审计根因#2 原样活着）**。本批 A 段交付：vendor 阻塞脚本 defer 化 + qrcode 按需（首开少 1 阻塞位/58KB）、SW 温访问秒开全套（活体测试 9/9，含版本指纹防陈旧链）、dist 激活卡备妥。预期终态（B 卡重启后）：本地冷 0.13s/温 0.06s、**远程冷 2.6s（-72%）**、**远程温 ~0.07s（-99%）**——四条验收线全绿。

## A 段（无重启立得，随合并落 main 刷新生效）

1. **实测现状两腿瀑布**（A1）：见证据 README §①§②。关键核验三项：dev 树确认在喂（`/assets` 404 + `/js` no-cache + 150 请求）；**gzip 已全开**（index/js/vendor 全部 `Content-Encoding: gzip`，A2 无需动，brotli 注记为可选）；W2 头代码在位（`StaticRoutes.scala:318`）但 dev 树下 `/assets` 恒 404，**W2 活体实证排入 B 卡核验清单**（curl immutable）。
2. **响应压缩**（A2）：已开，零改动，实证行见 `results/before/*.json` gz 列。
3. **dev 树止损**（A3）：
   - monaco：核实 W3 已改按需（`258959ce5`，`preloadMonaco/loadMonaco` 全树零调用点），真引擎首开零 monaco 字节——本批无需再动，实测复核入证据。
   - 6 个阻塞 vendor 脚本：**lucide/lottie/marked/katex/highlight 改 `defer`**（defer 经典脚本与 module 同队列按文档序执行，window 全局契约与执行顺序不变，只不再阻塞解析/首帧）；**qrcode.js 整体摘出首开**（非首屏：社交渠道二维码；`socialPanel.js` 增 `ensureVendorQr` 首用注入，原 `renderQrFallback` 兜底路径原样保留）。katex/lottie 不按需的前因：katex 服务聊天历史数学块、lottie 被 `main.js waitForGlobals` 门控，按需化引入首屏回归面——defer 已把他们移出关键路径（并行加载），留 dist 打包时代再收。
   - 前后对照：本地冷 DCL 296→247ms；改后首开请求清单无 qrcode；defer 语义由 `waitForGlobals` 既有安全网兜底（5s 超时，行为不变）。
4. **Service Worker 预缓存**（A4，评估结论=dev 树下可控，已落地）：
   - `web/sw.js`：静态面（`/js/ /css/ /vendor/ /brand/ /assets/` + 根 favicon）SWR——缓存即答 + 后台带验证器对账；导航请求一律网络优先，索引体 sha-256 哈希指纹，变更 ⇒ 清静态缓存+客户端补一跳（结构性陈旧不可能）；深处单文件改动的陈旧窗 = 一次导航（活体证实）。零接触面：非 GET、`/api`、`/uploads`、`/plugin-assets`、`/ws`、Range、跨源。逃生门：硬刷新原生绕过 / `?nfsw=0` / localStorage `stand_sw='0'`。
   - `js/swReg.js` + main.js 一行接线：load 后注册；**`/sw.js` 路由未上（引擎未重启）时 register 被拒 ⇒ 静默空转，页面行为与无 SW 逐字节一致**——故本件可先行合入，激活自动候 B 卡重启。
   - 活体测试 `test-sw-update.mjs` 9/9 PASS（安装/温控/陈旧窗一跳/旁路/reval 换新/index 指纹清缓存/稳态）。

## B 段（dist 激活卡 · 候作者窗 · 备妥不执行）

**为什么 10-20 没选上 dist**：择树逻辑 `StaticRoutes.resolveStaticSource`（`StaticRoutes.scala:54-67`，boot 经 lazy val `staticSource` `:93-102` **一次性解析**）。10-20 19:20 引擎启动时 `build/web-dist/index.html` 尚不存在（20:05:45 才出产，晚于 boot）⇒ 判 DevTree 并锁定至下次重启；且即便 20:05 后重启，20:0x-21xx 落的 web 触碰合并又把 dist 打回 stale（实证：`find src/main/resources/web -newer build/web-dist/index.html` 列出 index.html/css 五件/queueVault.js/locales）。**修掉的回落坑**：本批给 boot 行加了 stale 点名（`StaticRoutes.staleDiskDistHint`，`StaticRoutes.scala`）——dev 树在喂且磁盘 dist 存在但 stale 时，boot 日志直接报「disk dist build/web-dist is STALE (N files newer); run scripts/build-web.mjs then restart」，10-20 式静默回落不再隐形（fresh dist 不会被此提示误伤——fresh 本就会被择中；`-Dstand.webdist=dev` 的热编辑逃生门保持干净行）。

**两步卡（在 main 检出 `/Users/kaiyu/Claude code/Stand` 内执行）**：

```bash
# 步骤 1（必须是重启前对 web/ 的最后一步——晚于一切 web 触碰合并）
cd /Users/kaiyu/Claude code/Stand && npm run build
# 核验新鲜度（必须输出 0）：
find src/main/resources/web -type f -newer build/web-dist/index.html | wc -l
# 步骤 2：重启引擎（作者窗；现以 sbt run 跑在终端 s000）
```

**重启后核验（curl 实证清单）**：

```bash
grep -a "static tree" ~/.stand/logs/stand.log | tail -1
# 期待 "static tree: disk web-dist at ..."；若见 "STALE (N files newer)" ⇒ 有 web 合并晚于 build，回步骤 1
curl -sI 127.0.0.1:8080/assets/$(ls "/Users/kaiyu/Claude code/Stand/build/web-dist/assets" | grep '^app-.*\.js$') | grep -i cache-control
# 期待 "Cache-Control: public, max-age=31536000, immutable"（W2 生效实证）
curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:8080/sw.js          # 期待 200（SW 激活前提）
curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:8080/js/main.js    # 期待 200（C1 源模式契约不变）
```

**预期数字**（替换面同法实测，`evidence/.../results/dist-predict*`）：本地冷 DCL 134ms / 温 62ms；**隧道冷 2.6s（dev 9.2s，-72%）**；隧道温 ~0.07s（SW+immutable 双保险）。请求结构：62 请求/0.87MB（dev 194/2.16MB），45 js = 入口 chunk + boot 需要 chunks（156 lazy chunks 按需）。

**回退三道**：①`-Dstand.webdist=dev` 重启（热编辑逃生门，`StaticRoutes.scala:60`）；②`rm -rf build/web-dist` + 重启（`resolveStaticSource` 落回 DevTree）；③SW 一键退：页面控制台 `localStorage.setItem('stand_sw','0')` 后刷新（反注销），或任意 URL 加 `?nfsw=0` 单次绕过。

## 质量闸读数

- scala：`sbt compile` 577 源零错（worktree 隔离 target，未触碰在跑引擎）；改动 2 文件，`StaticRoutes.scala` scalafmt 通过，`WebSocketRoutes.scala` 仅 1 行白名单增项（scalafmt 全文件跑会卷入该文件既有格式债区域，噪声已逐 hunk 回退）；测试面编译进行中，受影响 spec（`AssetsRoutesSpec` 择树矩阵为注入探针式，钉 `resolveStaticSource` 语义——本批未改其语义，只增 boot 行可见性）。
- checkJs 闸：**main 检出现状即红**（283 错 vs 基线 273：agentProfile/historyReplay/mobile 四件/scrollUnderGlass/socialPanel 六个「new file」+ canvas.js 四项超基线——近几日合并未再生基线的既有债）；本批 worktree 与 main **逐项相同（283=283）**，swReg.js 零错、socialPanel 错数 7→7、canvas.js 不变——本批零新增闸面。
- dist 构建管线：`build-web.mjs` 本批树上出产 OK（175 js 源全图 0 孤儿；sw.js 根文件走 copy-through 自动进 dist；index 重写规则与 defer 化无冲突）。

## 文件清单

| 文件 | 变更 |
|---|---|
| `src/main/resources/web/index.html` | 6 vendor 阻塞脚本 → 5 defer + qrcode 摘除（注释注明语义保持与不做按需的前因） |
| `src/main/resources/web/js/socialPanel.js` | `ensureVendorQr` 按需注入 + `renderQr`/`drawQr` 拆分（兜底路径原样） |
| `src/main/resources/web/sw.js` | 新增：SWR 温访问秒开 SW（哈希指纹防陈旧 + 三重逃生门 + 零接触面清单） |
| `src/main/resources/web/js/swReg.js` | 新增：load 后注册器（/sw.js 未上线=静默空转；`stand_sw='0'` 反注销） |
| `src/main/resources/web/js/main.js` | +2 行：import + `initServiceWorker()` 接线 |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | 根静态白名单 + `"sw.js"`（no-cache，保证 SW 更新即时可见） |
| `src/main/scala/nebflow/gateway/StaticRoutes.scala` | boot 喂树行 stale-dist 点名（`staleDiskDistHint`，修 10-20 式静默回落坑） |
| `evidence/20261021_console-load-perf/` | 测量脚本三件 + results 五组逐请求瀑布 + README 全表 |

## 边界与未做

- 引擎重启未动（作者令）；A 段静态改动随合并落 main 后，在跑引擎从磁盘喂 web 树——**刷新即生效**，无需重启。
- 隧道温 <2s 与远程冷 <8s 两条验收线的最终兑现都压在 B 卡重启（SW 激活 + dist 择树）；合并后如作者暂不重启，现状维持「本地达标、远程冷 9s 温 8.8s」。
- SW 在手机隧道 origin（link.mystand.space）与本地 origin（127.0.0.1:8080）是两个独立注册域，互不污染；隧道腿收益同表。
- brotli、lucide 图标子集化、CSS 合流：dist 时代可选项，本批未做（注记于证据 README）。
