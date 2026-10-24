# 手机端 UI 欠账总清偿 + 静态资源缓存击穿 · 重做批 REPORT

- 任务书（2026-10-25 重派）：①顶部渐变条遮消息第一行（10-22 令）②布局工整化五毛病（10-19 令）③面板族（10-23 令）④静态资源缓存击穿。
- 基线：main `c59f51f7f`；工作分支 `delegate/delegate-nebflow-530e1976`。

## 0. 考古结论（先于动手，决定本批形状）

对 HEAD 逐条核账后确认：**①②③已在树内落地，④从未实现**。

| 项 | 令 | HEAD 状态 | 证据（file:line） |
|---|---|---|---|
| ① 渐变条退役/硬裁切 | 10-22 | ✅ 已落地 | 顶带裙边退役批 `c52897567`：index.html `#top-overlays` 不挂 `scroll-under-glass`（index.html:332-343）；手机 header 裙边随顶部消失点批 `eb8832dd2` 退役（chat.css:30-38 注记 + scrollUnderGlass.css:22-33 契约）；#chat 硬裁切 = `--chat-top-clip` mask、2px 极窄过渡（chat.css:36-38，作者档「硬切或极窄取极窄」） |
| ② 布局工整化 | 10-19 | ✅ 已落地 | chips 叠字：v3 悬浮顶栏 grid 52/1fr/52（mobileLayer.css:147-181），header 左右簇整列退场（:156-158）；「N 任务」卡：手机层 `#task-list display:none !important`（mobileLayer.css:1514-1517，任务浮层接替）；穿字/悬空：各控件 glass-control 悬浮（v3②）；切顶：#chat mask 硬裁切在场 |
| ③ 面板族 | 10-23 | ✅ 已落地 | 整批 = `340f4a922`（HEAD 祖先，验证 `git merge-base --is-ancestor`）：头像→智能体+记忆合并面板（floatController.js:58-60 PANEL_FACE）、#memory-btn 手机退役（mobileLayer.css:1524）、设置页 profile 入口行退役、七面居中（left/right:0+margin:auto，无 left:6px 残留）、后台合并面板 bgMergedPanel.js、画板 bottom sheet mhSheetIn + canvasGestures 手势 |
| ④ 缓存击穿 | 10-25 | ❌ **本批实现** | HEAD 无任何版本化机制；且发现「immutable at 稳定 URL」的错误半成品只存在于未合并分支 `delegate/delegate-nebflow-4398a05f`（bcddc32a5，2026-10-24 00:47）——**正是作者审计四条全「丢」的机理解释**：①②③的修复 10-22/10-23 已进树，但作者手机按稳定 URL 长效缓存（或 relay 滞留）钉死旧字节，新前端永远到不了手机 |

⇒ 作者 10-25 00:36 在手机上看到四条全坏，根因是④：代码里修好了、交付链断了。本批主交付 = ④ + 三族用例钉 + ①②③逐条核账。

## 1. 本批交付 · ④ 静态资源缓存击穿（内容寻址版本化 URL）

**机制**：dev 树 index.html 里每个 `css/…`、`vendor/…`（stylesheet/defer script）与 `js/…`（module 入口，共 39 处引用）由引擎在应答时改写为 `<path>?v=<sha-256 前 16 hex>`；静态路由面对**带版本参数**的请求回 `Cache-Control: public, max-age=31536000, immutable`（内容变 ⇒ URL 变 ⇒ immutable 永远安全），**裸请求保持 no-cache**（ES module 图内相对 import 不带版本，新鲜度由既有 sw.js 索引指纹链 + no-cache 重验证保底；本批不改任何模块字节）。index.html 本体仍 no-cache = 唯一新鲜裁判。

**修完即生效的恢复路径**：手机上被钉死的旧条目按旧键（裸 URL）留原地，index（no-cache）永拿最新 `?v=` 引用 ⇒ 新键首次即新字节；且 index 体变 ⇒ sw.js 哈希对账链清静态缓存 + 补一跳导航，模块图旧条目一次导航内对账。已中毒的「immutable at 稳定 URL」条目（若作者引擎曾跑过 bcddc32a5 形态）同样被新键绕开。

**改动面**：
- `src/main/scala/nebflow/gateway/AssetVersions.scala`（新）：`versionOf(classpathName)` = SHA-256/16hex；file: 资源以 mtime 作缓存键（sbt run 热编辑 ⇒ mtime 变 ⇒ 重算 ⇒ 下导航新 URL）；jar 资源每 boot 一次；缺文件/IO 意外 ⇒ None ⇒ 调用面原 tag 回落（加速面非语义面，packedIndexHtml 同族纪律）。
- `StaticRoutes.scala`：`ShippedAssetCacheControl` + `assetCacheControl(req)`（带非空 `v` ⇒ immutable，否则 no-cache）单点；`versionedIndexHtml`（纯函数，复用 rebuildMatches 的防 `$` 展开纪律）只动两种已知形状、favicon 自带 digest 不碰、已带 `?v=` 不匹配形状类（幂等）；dev 树 `indexResponse` 腿与隧道打包腿（pack 在前干净路径、版本化在后）接入；dist 两脸不动（esbuild 产物已内容寻址）。
- `WebSocketRoutes.scala`：/css 单文件、/vendor monaco/pdfjs/单段/fonts、根 favicon 白名单全部换 `assetCacheControl(req)`；其中单段 vendor 与 fonts 此前**完全无 Cache-Control**（启发式新鲜度可滞留数日）——裸腿显式 no-cache 顺带杀掉该滞留面。
- `sw.js` 头注更新：SW 缓存键 = 完整 URL（含版本参数），与既有索引哈希对账链叠加。

**刻意不做**：不改模块图 import 指示符（不重写 js 字节）；隧道打包面 url() 字体引用维持裸路径（fonts 裸腿已显式 no-cache）。

## 2. 用例钉（任务书三钉）

1. **渐变条不可见 + 气泡第一行完整可读** → `tests/mobile-topline-clear.spec.mjs`（新，自足 mock，375×812）：T1 顶带零渐变（#top-overlays 无 scroll-under-glass、#header 无底面无 ::after 裙边、#chat 硬裁切变量在场）；T2 首行几何在 header 玻璃区之下 + elementFromPoint 不落 header；T3 滚动态无渐变蒙版元素横跨内容顶带（header 悬浮控件豁免）。
2. **面板合并行为断言** → 已在树内（本批核账确认）：`tests/bg-merged-panel.spec.mjs`（桌面归并 3 测）、`tests/mobile-v3.spec.mjs` T3（头像→合并面板 agent 页签）、`tests/mobile-p5-memory.spec.mjs`（合并几何居中）、`tests/mobile-p6-bgstatus.spec.mjs`（后台合并+幽灵面退场）。零重写。
3. **资源 URL 版本化断言** → Scala：`AssetVersioningSpec`（新：指纹确定性/内容敏感/缺文件回落/形状改写/幂等/dev index 全引用带指纹/端到端 immutable）；`JsStaticRoutesSpec`+`VendorSubtreeRoutesSpec` 断言换代（裸 no-cache、带 v immutable）；Playwright：`tests/asset-url-versioning.spec.mjs`（新，instance-backed，已登记 playwright.config.mjs instanceBacked 名单：index 全引用带指纹/版本化 immutable/裸 no-cache/favicon immutable+sw.js no-cache/零资产 404；dist 脸优雅跳过）。

## 3. 未做 / 移交

- **编译与 spec 执行**：sbt 由主会话代跑（任务书纪律）——请跑 `sbt testOnly nebflow.gateway.AssetVersioningSpec nebflow.gateway.JsStaticRoutesSpec nebflow.gateway.VendorSubtreeRoutesSpec nebflow.gateway.IndexWithBrandServeSpec nebflow.gateway.IndexEntryAliasSpec nebflow.gateway.ConsoleFastpathSpec`；Playwright：`node node_modules/@playwright/test/cli.js test tests/mobile-topline-clear.spec.mjs`（自足）+ 实例起后 `PW_SELFCONTAINED_ONLY=` 点名 `tests/asset-url-versioning.spec.mjs`（BASE_URL 指向实例）。
- checkJs 零触碰（本批未改 web/js 任何字节；worktree 无 node_modules，门禁由主会话侧照常）。
- 手机端最终确认需作者在真机刷新一次：新 index（no-cache）即带新 `?v=` 引用，旧缓存条目作废。
- ①②③未再动一字：核账结论「已在树内」如需真机复核，随时可回退本判断（但三族 spec 钉会先红）。

## 4. 关键假设

- 「四条全丢」= 作者在手机端所见（交付链断裂），非代码面缺失——考古证据链见 §0；若作者本意是 ①②③ 还有树外新规格，需新令补规格书。
- 引擎跑 dev 树（sbt run / fresh 判据既有）；dist 脸不受本批影响（已内容寻址）。

## 5. 主会话验证补遗（2026-10-25 04:1x）

本批 sbt 验证留主会话代跑（delegate 零 sbt），首跑暴露 6 红，全部归因修复后 **23 spec 队列 234/234 全绿**：

1. **rebuildMatches 三参 append 编译陷阱（主修复，StaticRoutes.scala）**：`sb.append(target, last, m.start)` 三参形式对 `scala.collection.mutable.StringBuilder` 被 Scala 3.5.2 编译器 tupling 成 `append(Tuple3)`——元组 toString 整串拼进结果（`((…,0,23)…(…,49,50),0,141)` 碎片），单资源缺文件回落路径同样中毒。字节码级钉死（javap：Tuple3.apply + append(Object)）。修复 = 显式 `target.slice(last, m.start)` 单参 append，附注释防复发。packedIndexHtml/rewriteCssUrls 同用此助手，一处修复全覆盖。
2. **AssetVersioningSpec 自胜正则（spec 修复）**：dev-tree 断言的正则尾四引号被词法并进内容，闭引号成必选项 ⇒ 完全版本化（=本批目标形态）的 body 反而收集为零。修 = 显式容许可选 `(?:\?v=[0-9a-f]+)?` 尾，group(1) 恒裸路径。
3. **误案两起记录在案**：①首次 6 红曾疑 zinc 陈类（Clean 重编复现，排除）；②IndexEntryAliasSpec OOM/53s 超时系 dump 碎片下游效应，主修复后自愈。
4. spec 侧教训：本批 delegate 零 sbt 验证留白，spec 首跑即首验——后续批照旧「分支内 spec 绿再合并」，避免带病入库。
