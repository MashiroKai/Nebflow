# 手机端 Canvas 交互优化 · 交付报告（#988）

批依据：作者令 2026-10-23 ⑦（画板 bottom sheet 批）→ **2026-10-27「手机端 Canvas 交互优化」令**。
本报告 = 收官交付（设计稿、样张、commit、验收对照、门禁记录）；交互规格与审计详见同目录 `DESIGN.md`。

## 一、交付物

| 类别 | 产物 |
|---|---|
| 设计稿 | `DESIGN.md`（现状审计 + 五条交互规格 + 实施落点）；`mockup.html` |
| 样张（设计目标） | `mockup-phone-375x812.png`（目标形态：卡头胶囊+切换条+抓手）、`mockup-swipe-peek.png`（邻卡名 peek chip）、`mockup-overview.png`（呈现/横滑跟手/收起三态） |
| 样张（修前审计 375×812） | `audit-1-sheet-375x812.png`（形态）、`audit-2-longpress-375x812.png`（长按引用）、`audit-3-two-cards-375x812.png`（双卡层序）、`audit-metrics.json`（`pager:false, switchbar:false` = 换卡零显性通路的量化证据） |
| 代码 commit | **`f78ffcdd1`**（本分支 `delegate/delegate-nebflow-821b3f47`）：4 files，+518/−117 |
| 验收 spec | `tests/mobile-canvas-sheet.spec.mjs` 重写扩编，T1–T5 共 10 tests |

## 二、「别扭点 → 新交互 → 验收」对照表

| # | 别扭点（修前） | 新交互（修后） | 验收 |
|---|---|---|---|
| 1 | 换卡无可见入口（audit-metrics `pager:false,switchbar:false`） | 底部快捷切换条常驻：`[‹][页点 tap][›]｜[收起]`，frosted 胶囊族 | T1（bar=true, 3 钮+页点）、T3c（‹›/页点全通路绿） |
| 2 | 关卡藏在 26px 隐形缘带，易误触、不可发现、不可撤销 | 关卡收敛卡头显性 X（关激活卡落邻卡，末卡关后收层）；隐形侧缘带整腿退役 | T1（sideZones=0, closeBox 44×44）、T3d（邻卡落位/末卡收层） |
| 3 | 横滑语义三区三分裂（内容区退役／卡头左右滑=关卡／上滑=换卡） | **横滑语义统一 = 切卡**（内容区+卡头同义，左=下一张/右=上一张，环绕） | T3、T3b（close-on-swipe retired 断言） |
| 4 | 「上滑换卡」与「下滑收起」同轴打架 | 上滑换卡退役；纵轴只留「下滑收起」一条语义 | T2b（下滑收起 + 未越阈回弹腿） |
| 5 | 收起不可发现且疑似销毁 | 三条显性收起通路（底缘带下滑跟手 ≥120px 或快甩／卡头下滑 ≥48px／切换条「收起」钮）；closeCanvas 状态保留，再开复现 | T2、T2b、T2c（hasTab 复现腿） |
| 6 | 移动端按钮热区过小点不中 | 卡头 X/引用钮 + 切换条四钮命中盒 ≥44px（7px 透明边 + padding-box，视觉圆 30px 解耦） | T5（全部 ≥44 + backgroundClip=padding-box） |
| 7 | 桌面遗留双标题/双钮（`.canvas-title`/`#canvas-pop-btn`） | 手机层隐藏 | T1（desktopTitleHidden） |
| 8 | 纵滚/代码块横滚有被手势劫持风险 | 纵滚零劫持（touch-action:pan-y 归浏览器）；真横滚器（scrollWidth>clientWidth）起步让位原生横滚；单卡横滑 no-op | T4（wheel 滚动生效 + 单卡横滑 no-op） |
| 9 | 切卡黑箱跳变，无方向预告 | 跟手轻位移（≤48px，0.35 系数）+ 邻卡名边缘预览 chip（peekShow/peekClear） | 实现件 `canvasGestures.js` peek chip；通路验收 T3 |

## 三、门禁记录（本 worktree 实跑）

| 门 | 结果 |
|---|---|
| checkJs（`node scripts/check-js-types.mjs`，pinned tsc 5.5.4） | **PASS：257 < 基线 260（−3）**；触及文件零错误 |
| spec（Playwright，`tests/mobile-canvas-sheet.spec.mjs`） | **10 passed (19.5s)**，每测内建 pageerror/console-error 零告警断言 |
| build-web（`node scripts/build-web.mjs`，esbuild 0.25.9） | PASS：186 源全入图，orphans 0，exemptions 5 |
| `node --check` 语法面 | 三个触及 .js 均过（spec 内运行即证） |

checkJs 尾巴的来龙去脉：首跑门面红「`canvasGestures.js: new file with 2 errors`」——
`canvasGestures.js(72,11)/(79,11): error TS2339: Property 'dataset' does not exist on type 'Element'`
（`querySelector` 返回 `Element`，`.dataset` 是 `HTMLElement` 成员）。修法 = 声明处 JSDoc 收窄
`/** @type {HTMLElement|null} */`（同文件既有风格，line 45/125 同族），重跑门面 257<260 PASS、
canvasGestures/navController/mobileLayer 三触及件 tsc 全量 grep 零命中。

环境注记：本 worktree `node_modules` 为空（前批 `20261027_121500_canvas-tab-revive` 曾因
`node_modules/typescript` 缺失报 checkJs 环境级不可跑）——本批从 root checkout 符号链接
**pinned 同版本** `typescript@5.5.4` + `esbuild@0.25.9` 后全门实跑；gate 的 npx 回退本就禁用，
此路径不绕 pin，`node_modules/` 在 .gitignore 不入库。

## 四、边界

- 桌面端零改动（纯 `body.mobile-layer` 手机层作用域）；数据面/后端零改动。
- 卡头带（.canvas-header）整带归 `navController.js`，`canvasGestures.js` 不碰；切换条自管钮群。
