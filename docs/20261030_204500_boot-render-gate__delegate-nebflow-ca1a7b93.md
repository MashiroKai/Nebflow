---
type: fix-report
status: published
date: 2026-10-30
scope: 前端引导面（src/main/resources/web/index.html）+ 验收钉（tests/boot-render-gate.spec.mjs）
---

# 偶发裸渲染修复批：boot render gate（未就绪不裸露）

> 结论先行：冷启动实例上「有时候」的整面无样式裸 DOM（作者 10-30 20:03 截图实证），
> 根因不是样式「慢」而是样式**失败**——任一 stylesheet 请求失败（实例重启掐断在途
> 请求 / 隧道上 SW 未命中回 `Response.error` / 深层 404 / gzip 流中断）时浏览器放弃
> 该表并立即无样式绘制，而 JS 模块图是独立并发 fetch 可全部成功 ⇒ 工作台列表照常
> 渲染、样式面整体未挂。修复 = index.html 全内联三件套（门样式/门脚本/引导面）：
> **未就绪不裸露**——任何 CSS 失败形态下用户看到失败卡（可重试/绕过缓存重试），
> 永不看到裸 DOM；纯慢路径由浏览器渲染阻塞天然兜底（白窗非裸）。偶发已被
> 故障注入确定性化（断/迟/404 三形态注入，45 断言）。相关面：
> [[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]]（既有审计方法论）、
> [[20261030_memory-dimension-profiles.zh-CN]]（同期批次）。

## 词条

- boot render gate（引导渲染门）— index.html 内联经典脚本 + 门样式 + body 首元素引导面；`html.nf-boot-gate` 下 body 整面 `visibility:hidden`（visibility 可被子代覆写，引导面自显）。
- script-blocking 表 — 媒体条件命中（或无媒体条件）的 render-blocking stylesheet：HTML 标准机制，**连内联经典脚本的执行都阻塞**——内联脚本执行到的那一刻，先行此类表必已全部终态（成功/失败）。
- 三态判定 — 逐链 classify：`sheet===null`（终态语义下=网络失败类）、`cssRules.length>0`=ok、0 规则=fail（404/空体）、`cssRules` 访问抛错=fail（CSSOM：加载失败的表抛 SecurityError）。
- 非适用媒体表 — media 查询不命中的 stylesheet（highlight 双主题对）：不 script-blocking、不阻塞首帧，已处理即足（适用性由媒体查询决定，与规则数无关）。
- 页内时间线 — 验收观测方法论：渲染阻塞期浏览器不产首帧、Playwright 页内注入（rAF/interval/evaluate）拿到陈旧上下文 ⇒ 窗内态只能由页内经典脚本自记录（事件 + 采样 + 旗标），DOMContentLoaded 后读回断言。

## 1. 根因（侦察结论）

四条假设逐一实证：

| 假设 | 结论 |
|---|---|
| 样式加载竞态（CSS 未就绪即渲染） | **部分成立但非病灶**：head 全链 render-blocking，模块图执行前样式必已终态；JS 渲染内容时样式结果已定。「未就绪」窗口不存在于 JS 渲染路径 |
| 缓存命中半新半旧 | 不成立：dev 树 index 引用带内容指纹 `?v=`（`AssetVersions` 设施，未入库），URL 变即内容变；SW 键=全 URL。版本错位不可能产裸 DOM |
| coldstart 资产路径 | 不成立：StaticRoutes 分发同树同源；dist/dev 混服有 mtime freshness 闸 |
| **stylesheet 请求失败** | **实锤**：裸 DOM 要求至少一条 `<link rel=stylesheet>` 到达终态-失败（浏览器随即放弃该表并解除首帧阻塞）。JS 模块请求是独立并发 fetch，成功失败互不相关——列表照常渲染（含原始文件名文本），lucide 图标 hydrate 一半 = 「碎图标」。失败源：实例重启掐断在途请求（本仓工作流每日多次重启）、隧道 SW `swrResponse` 未命中+网络失败回 `Response.error`、深层 404、gzip 流中断。冷启动提高单请求失败率 ⇒「有时候」 |

旧行为零韧性：不 gate、无骨架、无失败面——失败态恒等裸 DOM。

## 2. 修复件（index.html，全内联零请求依赖）

1. **门样式**（head 尾内联 `<style>`）：`html.nf-boot-gate body{visibility:hidden}` + 引导面自显规则。暗色覆写置块尾（同特异性序胜——本批自己踩过这个坑，截图实证修正）。
2. **门脚本**（head 尾内联经典脚本，先于模块图）：
   - 逐链三态判定（见词条）；初始评即终态评（script-blocking 保证），`error` 事件先于挂监听 fire 完毕的缺口由终态判据收口；
   - 全 ok ⇒ 摘门（缓存即中时 add+lift 同任务完成，MutationObserver 合并不记录 ⇒ 门态从未存活过任务边界 ⇒ 从未可绘制 = 零闪现，T1 钉死）；
   - 任一 fail 或 15s 看门狗（与卡窗静态看门狗同档预算）到点仍有未决 ⇒ 永不摘门，换失败卡：重试 / 绕过缓存重试（`nfsw=0` 逃生门，query 原样保留）；
   - 卡窗脸同进门（其壳隐藏规则也在 CSS 里，CSS 失败时卡窗同样裸整壳）；内嵌脸（`nfEmbedded`）不进（main.js 自渲染停止面）；`window.__nfBootGateFailed` 去重锚抑制卡窗看门狗双失败卡。
3. **引导面**（body 首元素 `#nf-boot-gate`）：纯 CSS 环 + 文本卡，零资产依赖——它报告的正是样式面缺席，自身绝不能再依赖任何请求。不属于「弹窗禁遮罩」族（应用本体未就绪时的整页引导面，同卡窗失败卡先例）。

**浏览器语义边界（诚实记录）**：纯慢（无失败）路径下门脚本自身被 script-block，门类直至全体 settle 才落、同刻摘——加载环在 Chromium 对 head 链实际不可见（首帧被未决表阻塞）；其存在价值是非阻塞引擎的「失败 unblock 后其余未决」窗与语义完整。慢=白窗（浏览器兜底，非裸），失败=失败卡（本件兜底）。隧道首开打包脸全 CSS 内联 ⇒ 零 link 即摘；dist 脸 16 链并 1 bundle + 3 vendor，同一判定成立（build-web 已验）。

## 3. 验收（故障注入确定性断言）

`node tests/boot-render-gate.spec.mjs` — **45/45**（截稿前双跑一致）。观测方法论见词条「页内时间线」。

- T0 静态形状钉 6（面/样式/三态判定/脚本先于模块图/去重锚/骨架环与失败隐环）
- T1 正常冷载 6：摘门、**门态未存活任务边界（零闪现钉）**、壳可见、44/44 样式表有规则体、无残留
- T2 CSS 全断（abort×33）9：失败面、壳四面 hidden 零裸露、双按钮、ERROR→失败面时序（旗标锚）
- T2d 暗色失败面截图存证
- T3 纯慢（2.5s 迟）4：无半途门样本、全体 settle 后摘门、慢≠败、壳可见
- T3b 一败一慢 3：两腿留痕、**收口不早于末条 settle（script-block 语义）**、终态失败面
- T4 半断（sapphire）5 / T5 404 体 4：任一失败即整体失败面，无半成品裸露
- T6 逃生门：点击 ⇒ URL 落 `nfsw=0`
- T7 卡窗脸同进门 5：CSS 失败不裸整壳、去重锚在场

截图证据：`evidence/20261030_boot-render-gate/`（正常 lift / 亮暗失败面 / 卡窗脸）。

回归：cardwin-content-only 23/23 绿（index.html 邻面）；build-web 全绿（gate 在 dist index 完好，orphans 0）；check-circular 与 checkJs 本批零 js/ 文件触碰、结果与基线逐字节同（循环失败系 popArtifacts/msgQuote 等存量，非本批面）。

## 4. 开放项

- 内嵌脸（`nfEmbedded`）主动不进门：其样式失败形态仍由 main.js 停止面兜，与本批同语义但未统一引导面（反增额，未动）。
- gzip 流中断（200 截断体）不可由 cssRules 判据检出（规则体部分解析）——截断≠裸，属另一故障级；若作者再现可另批。
- 引擎侧资产送达可靠性（重启在途请求 reset 等）不在本批范围——前端门是任何送达失败的最后一道，不是送达层的替代。
