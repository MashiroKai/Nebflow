---
type: report
status: done
related: "[[index]]"
---

# token 用量面板手机滚动修复批（手机档自滚恢复）

> 批令（作者令 2026-11-01，渠道 console）：手机端 token 用量面板无法下滑，看不到底部。查该面板的手机档（375 宽）布局——内容区滚动容器缺失/被 overflow 截断/高度没约束，注意前批手机手势仲裁与边缘返回别再拦截。要求：底部内容（含最后一个统计块）可达、滚动跟手不与页面级手势冲突、桌面端该面板零回归。验收：375 宽深浅主题滚到底实拍 + 桌面回归一张、零 console error、真实合并 main 报 commit hash。
> 图谱：[[index]] · 词表 [[glossary]]。

## 0. 结论先行

| 项 | 内容 |
|---|---|
| 根因 | **CSS 特异性压死，不是手势仲裁**。收编面统一页面化几何块（mobileLayer.css「收编面统一页面化几何」）给 `body.mobile-layer #mobile-pages .mobile-page-body > #usage-dashboard` 压平 `overflow: hidden`，特异性 (2 id + 2 class)；P11 用量块的自滚规则 `overflow-y: auto` 选择器少一个 `.mobile-page-body`（(2 id + 1 class)）⇒ **永远落败**。叠加 B 批（2026-11-01）`height:100%→auto + flex:1 1 auto + min-height:0` 后，面板作为页体 flex 项被压回可视高——页体零余量不滚、面板 overflow:hidden 不滚，超屏内容全截断。边缘返回/切卡手势纵滑均让位（bindEdgeBack 纵向意图 disarm、bindDocSwipe 仅 canvas host），与本 bug 无涉 |
| 修法 | P11 规则选择器升到 `.mobile-page-body > #usage-dashboard` 直子形——与几何块同特异性、源序在后胜出；补 `overscroll-behavior: contain` 防滚动链逸出。**一处选择器改动，桌面零触碰**（规则全挂 body.mobile-layer） |
| 验红 | 修前树（stash 往返实证）：`#usage-dashboard` computed `overflow-y: hidden`、面板 surplus 503px（scrollHeight 1153 / clientHeight 650）、页体 surplus 0；**触屏真拖拽（CDP Input.dispatchTouchEvent 走原生输入管线）scrollTop 0→0** 卡死原位；桌面腿修前即全绿（bug 纯手机档） |
| 验绿 | 9/9：overflow-y=auto ✅、拖拽 0→503（= 满余量，跟手到底）✅、滚到底后明细表末行 `bottom=734 ≤ vh 812` 面板底完整可见（dock 之上）✅，深浅主题同过；桌面回归 scrollTop=280/overflowY=auto/末行可见 ✅；零 console error ✅ |

一句话：**自滚规则被几何块特异性压死——选择器补上直子一段即复原，零新机制。**

## 1. 改动面（2 文件 + 证据）

- `css/mobileLayer.css`：P11 用量块选择器 `body.mobile-layer #mobile-pages #usage-dashboard` → `body.mobile-layer #mobile-pages .mobile-page-body > #usage-dashboard`（+`overscroll-behavior: contain` + 根因注释钉复活）。
- `tests/usage-mobile-scroll-shots.mjs`（新增）：验红/验红 harness——静态面 route 截胡 + `/api/usage/aggregate` 确定性夹具（day/hour/agent/model/provider 五维、cost level 1–4 分布）+ routeWebSocket 注答；真触屏拖拽走 CDP `Input.dispatchTouchEvent`（原生滚动管线，非 JS scrollTo）；before/after 双相位九断言（overflow 计算值/surplus/拖拽跟手/末行可见）+ 深浅主题 + 桌面回归。注意 harness 自身两坑已排：`/sw.js` SW 脚本请求不过 page.route，mock 源上必报 "fetching the script" 噪声——按 swReg.js 逃生门 `localStorage stand_sw='0'` 关注册；`overflow:hidden` 盒对 JS scrollTop setter 开放（对手势封闭），红证必须以拖拽帧为准、不得以程序滚帧充数。
- 证据：`evidence/20261101_usage-mobile-scroll/shots/`（10 帧，随批入库）——`*-before-drag.png`（修前手指卡死原位 = 用户所见）、`*-after-drag.png`（拖拽跟手中景）、`*-after-bottom.png`（375 深/浅滚到底验收帧，末行+dock 上留白完整）、`usage-desktop-before/after.png`（桌面回归）。

## 2. 实证

- 本批 harness：before 5/9（红在 overflow/拖拽两检查 ×双主题）、after 9/9 ALL GREEN，两相位 console=0 page=0。
- 回归：`mobile-p11-usage.spec.mjs`（该面板既有验收套件）3/3 passed；`scripts/check-js-types.mjs` 0 errors。
- 已知陈旧（与本批无关，修前同红）：`tests/uifix-mobile6-shots.mjs` 等 `.mh-dd` 液态下拉族 harness 仍指向 dock 批（2026-10-29）退役的 chrome，跑前即 TimeoutError——陈旧 harness 清理留待专批。

## 3. 遗产与边界

- 「收编面自管内滚（mobileLayer.css 页体块头注）依赖直子选择器打赢几何块」是结构性约束：后续任何新收编面要自滚，规则必须写成 `.mobile-page-body > <面>` 形——本批注释已钉在 P11 块上。
- 手机档 summary 卡行 `ud-card-sub`（命中/趋势行）在极窄卡内有折行，布局既有行为未动；本批只修滚动域。
