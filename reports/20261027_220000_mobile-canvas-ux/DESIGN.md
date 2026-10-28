# 手机端 Canvas 交互优化 · 设计稿（#988）

批依据：作者令 2026-10-23 ⑦（画板 bottom sheet 批）→ **2026-10-27「手机端 Canvas 交互优化」令**：
横滑语义统一 = 切卡；关卡（破坏性动作）收敛到卡头显性 X；按钮热区 ≥44px。
本批 = 交互语义改写实施批（先设计后实施；样张先于实施产出，见本目录 mockup-*.png）。

## 一、现状审计（修前，audit-*.png 375×812 + audit-metrics.json）

- 形态腿健康：bottom sheet 底缘锚定 6px、顶缘 76px、宽 359、r20、入场 mhSheetIn 全部达标。
- **换卡零显性通路**：audit-metrics `pager:false, switchbar:false` —— 手机上除「卡头上滑换卡」外无任何可见换卡入口，单卡用户不可发现。
- **关卡藏在隐形缘带里**：左右 26px 隐形缘带「内滑 = 关卡」，误触根因（横滚代码块/缩放扫边即触发），且不可发现、不可撤销。
- **横滑语义分裂**：内容区横滑曾随 ⑦ 令退役、卡头左右滑 = 关卡、上滑 = 换卡 —— 三个区域三种语义。
- 桌面遗留面：`.canvas-title` 双标题、`#canvas-pop-btn` 双钮在手机层残留。
- 审计样张：`audit-1-sheet-375x812.png`（形态）、`audit-2-longpress-375x812.png`（长按引用）、`audit-3-two-cards-375x812.png`（双卡层序）。

## 二、设计稿（mockup.html → mockup-*.png）

- `mockup-phone-375x812.png`：目标形态 —— 卡头胶囊（标题 + 页点徽标 3/3 + 引用钮 + X）／内容区／**底部快捷切换条** `[‹] [页点] [›] ｜ [收起]` ／底缘可见抓手。
- `mockup-swipe-peek.png`：横滑切卡的邻卡名边缘预览 chip。
- `mockup-overview.png`：三态总览（呈现/横滑跟手/收起）。

交互规格（五条）：
1. **呈现**（⑦ 令沿用）：bottom sheet，底缘锚定（safe 区上 6px）、顶缘留聊天头一线、自下滑入、r20。
2. **收起 = 三条显性通路**：底缘带下滑跟手（≥120px 或快甩，未越阈回弹）／卡头胶囊下滑（≥48px，未越阈回弹）／切换条「收起」钮。收起 = closeCanvas：**整叠保留状态不销毁**，再开复现。
3. **切卡 = 四条通路，环绕序**：内容区横滑（左=下一张/右=上一张，轴锁 10px、阈 56px 或快甩 0.5px/ms，跟手轻位移 + 邻卡名预览 chip）／卡头胶囊横滑（阈 64px）／切换条 `‹` `›` ／页点 tap 跳卡。单卡时 `‹›` 禁用、页点单枚。
4. **关卡 = 唯一显性 X**：卡头 X（44×44 命中盒），关激活卡落邻卡，末卡关后收层；**横滑永不关卡**（隐形 26px 侧缘带整腿退役）。
5. **内容区零劫持**：纵滚 touch-action:pan-y 归浏览器；真横滚器（代码块等，scrollWidth>clientWidth）起步即让位原生横滚；单卡横滑 no-op。

## 三、「别扭点 → 新交互 → 验收」对照表

| # | 别扭点（修前） | 新交互（修后） | 验收（mobile-canvas-sheet.spec.mjs，全绿 10/10） |
|---|---|---|---|
| 1 | 换卡无可见入口（metrics `pager:false,switchbar:false`） | 底部快捷切换条常驻：`[‹][页点 tap][›]｜[收起]`，frosted 胶囊族 | T1（bar=true,3 钮+页点）、T3c（‹›/页点全通路） |
| 2 | 关卡藏在 26px 隐形缘带，易误触不可发现 | 关卡收敛卡头显性 X；隐形侧缘带整腿退役 | T1（sideZones=0, closeBox 44×44）、T3d（邻卡落位/末卡收层） |
| 3 | 横滑语义三区三分裂（内容退役/卡头=关卡/上滑=换卡） | **横滑语义统一 = 切卡**（内容区+卡头同义，环绕） | T3、T3b（close-on-swipe retired 断言） |
| 4 | 「上滑换卡」与「下滑收起」同轴打架 | 上滑换卡退役；纵轴只留「下滑收起」一条语义 | T2b（下滑收起+未越阈回弹腿） |
| 5 | 收起不可发现且疑似销毁 | 三条显性收起通路 + 状态保留再开复现 | T2、T2b、T2c（hasTab 复现腿） |
| 6 | 移动端按钮热区过小点不中 | 卡头 X/引用钮 + 切换条四钮命中盒 ≥44px，视觉圆 30px（padding-box 解耦） | T5（全部 ≥44 + backgroundClip=padding-box） |
| 7 | 桌面遗留双标题/双钮 | `.canvas-title`/`#canvas-pop-btn` 手机层隐藏 | T1（desktopTitleHidden） |
| 8 | 纵滚/代码块横滚有被手势劫持风险 | 纵滚零劫持（pan-y）；真横滚器起步让位原生 | T4（wheel 滚动生效 + 单卡横滑 no-op） |
| 9 | 切卡黑箱跳变，无方向预告 | 跟手轻位移（≤48px）+ 邻卡名边缘预览 chip | 实现件（canvasGestures peekShow）；通路验收 T3 |

## 四、实施落点（本批 commit）

- `src/main/resources/web/js/mobile/canvasGestures.js`：侧缘带退役；内容区横滑切卡（轴锁/阈/快甩/跟手 cap/peek chip）；真横滚让位 `insideHScroller`。
- `src/main/resources/web/js/mobile/navController.js`：`switchDocCard(delta, peekOnly)` 环绕切卡单点；卡头胶囊纵横语义改写（下滑收起/横滑切卡）；`ensureSwitchbar()` 底部切换条（role=toolbar）。
- `src/main/resources/web/css/mobileLayer.css`：切换条/peek chip 样式；44px 命中盒（7px 透明边 + padding-box）；侧缘带规则删除；桌面遗留面隐藏。
- `tests/mobile-canvas-sheet.spec.mjs`：验收 spec 重写扩编（10 tests，T1–T5）。

## 五、验证记录

- spec：`node node_modules/@playwright/test/cli.js test tests/mobile-canvas-sheet.spec.mjs` → **10 passed (19.2s)**，每测内建 pageerror/console-error 零告警断言。
- 门禁：checkJs / build-web 见 REPORT.md（合并闸复跑口径同批前基线）。
- 边界：桌面端零改动；数据面/后端零改动；纯手机层（body.mobile-layer）作用域。
