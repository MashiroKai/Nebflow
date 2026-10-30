# Canvas 顶部文件扇（卡片扇翻）—— 过程报告

作者令 2026-10-30 20:09（逐字）：「canvas 顶部多文件 tab 视图改为**卡片式左右
翻动**操作（现状=标签页签切换，作者要卡片扇翻）」。

设计锚：作者 2026-10-29 叠卡令「参考微信叠卡：强制放缩所有尺寸卡片到一个大小
+ 扑克式轻微旋转展开」；仓内落地先例 = Pop 扇形叠卡批（d5613e0ea，
`evidence/20261030_pop-stack-fan/report.md`）——本批复用**同套卡面规格与扇形
语言**到 canvas 文件切换。令 10-30 20:09 附两条硬要求：手机端已是独立界面的
不再于主窗重复打开（去重）；文件名显示带中文界面文案（tab/卡片名不裸英文）。

## 改造点 → 落点

| 令 | 落点 | 实现 |
|---|---|---|
| 卡片扇翻（现状=竖胶囊环标签切换） | `src/main/resources/web/js/canvas.js` `initCapsuleRing` 全件重做（3538-3956 一带） | hover 标题头胶囊开扇：页签卡悬于标题头下方（FAN_DROP_Y=56），游标卡完整置顶（z5、零旋转），邻卡以**卡顶中心为轴**轻微旋开收于其下（\|rel\|=1 ±112px/±7°、\|rel\|=2 ±206px/±13° 左右镜像），\|rel\|≥3 弧外零透明（扇面最多 5 卡可见）——扑克扇形，非直直重叠 |
| 统一卡尺寸（Pop 同套规格） | `src/main/resources/web/css/split.css` §文件扇（1355 起） | 卡面固定 **220×280（11/14，aspect-ratio 承载）**，radius 14px（Pop 卡语言），材质 = 全站 frosted token 逐项 = chat #header（glass-unify 沿承，CR2-5 钉住）；卡头=类型图标+中文签+页签名+脏点，卡体=大类型字形 |
| 左右翻动 | canvas.js 滚轮机（沿承触控板手感批）+ **新卡面横拖腿** | 滚轮 deltaX 主导/deltaY 副（设备分档+累积+冷却，单事件一格沿承）；卡面横拖：轴锁 8px → 整扇 --fan-dx 1:1 跟手（.dragging 关过渡），松手 \|dx\|≥64 翻一张、速度 ≥0.5px/ms 追加一张，不足阈回弹；横拖手势消费后同源 click 一律吞（轻拖不误切换，FAN-6/7a 实证修掉） |
| 点击语义 | canvas.js 卡 click | = 标题头 ringClickCenter 同契约：游标卡=激活 → 零动作（CC1）；游标≠激活（翻动后）→ 点击翻入切换；非游标卡 → 切换。扑克叠收几何下邻卡内半被游标卡压住（点内半=点游标卡，Pop 同语义），可见 sliver 自收 pointer |
| 中文界面文案（不裸英文） | canvas.js build() + `locales/zh-CN.js`/`en.js` 新键 | 卡头中文签「文件/面板」（`canvas.fanTagFile/Panel`）+ 页签名；扇顶提示条「左右滑动或滚动翻页 · 点击卡片切换文件」（`canvas.fanHint`，t() 双语） |
| 手机端去重 | canvas.js openRing（沿承） | isMobileLayer()/cardWindowMode 整腿让位不动；hover 触发型不开扇（FAN-9 钉）；deck 开着不开扇、单页签不展沿承 |
| 标题头不被顶替（结构承 #926） | split.css：退役 `.ring-open .t-inner{opacity:0}`；canvas.js：退役中心透明覆层 + --cr-popw 同步 + RING_TOP_OUT 越出反解 | 扇悬于标题头下方、不与标题头重叠：文字行零平移由结构保证；竖环期「顶部越出克制」换代为「零越顶缘」（扇全在面板顶缘之下，CR2-6/FAN-8b 钉）；环层挂 body 裁切盒失效机制沿承 |

## 证据

- 修前：`before-desktop-dark.png` / `before-desktop-light.png` —— 竖胶囊环
  （中心 delta.md 覆层 + 上下缘位胶囊 cover-flow，即作者批的「标签页签切换」
  形态）；`before-mobile.png` —— 移动层独立界面（无环）。
- 修后：`after-desktop-dark.png` / `after-desktop-light.png` —— 同一 4 文件
  组：五卡扇（游标 delta.md 完整置顶 + 中文签 + 提示条 + 邻卡扑克旋开），
  标题头常驻其上；`after-mobile.png` —— 移动层零变化（去重）。
- spec 运行截图：`shots/1-fan-open-dark.png`（3 卡扇）/ `3-fan-open-light.png`
  / `5-fan-six-cards-dark.png`（6 卡扇，\|rel\|=2 双邻 + 1 弧外）/
  `2-rest-dark.png` / `4-rest-light.png`（平时态）。
- 同一 harness 同一夹具（`tests/capture-canvas-fan.mjs`，桌面 1440×900 亮暗 +
  移动 390×844）。

## Spec 读数

- 新增 `tests/canvas-card-fan.spec.mjs`（扇卡批新契约钉，22 用例，桌面暗 +
  移动腿，**22/22 绿**）：FAN-1 全卡 220×280（强制放缩到一个大小）+ 中文提示
  条；FAN-2 扇形语言（z5 游标完整置顶 / ±112·±7° / ±206·±13° 镜像 / \|rel\|≥3
  弧外零透明不可点）；FAN-3 卡头中文签+页签名（不裸英文）；FAN-4 滚轮 deltaX
  单事件一格；FAN-5 横拖跟手 + 过阈翻一张 + 回弹归零；FAN-6 小拖不翻；FAN-7
  翻页≠切换 + 点击游标卡切换/零动作两态；FAN-8 扇悬标题头下方 + 标题常驻 +
  零越顶缘；FAN-9 移动层不开扇（去重）。
- 换代重钉 `tests/canvas-capsule-ring.spec.mjs`（**43/43 绿**）：竖环几何钉
  （±edgeY/rotateX ±34°/模糊扭曲/越顶缘 ≤全高−6/中心覆层 t-inner 隐去/
  is-center pe:none 落穿）整体换代为扇契约（镜像 ±112/±7°、零模糊、零越顶缘、
  标题常驻、游标卡 pe:auto）；交互契约沿承全绿（滚轮分档冷却、点击切换+续扇
  连续性、右键菜单、deck 互斥、单页签不展、双卡方向感知〔横扇来向侧〕）。
- 换代重钉 `tests/capsule-size-uniform.spec.mjs`（**31/31 绿**）：F4b 环胶囊
  180×36 → 扇卡 220×280；F6b token 消费计数换代（扇卡面 220px 为有意字面量
  规格，F4b 钉；标题头胶囊 token 单源沿承）。
- 几何点重钉 `tests/capsule-trackpad-feel.spec.mjs`（**16/16 绿**）：TC6「区
  外」滚轮点 +320px → +560px（扇面纵深 ~400px，旧点落进 hover 区被正当消费）；
  步进过渡 0.18s/0.01s 钉沿承全绿。
- 点取重钉 `tests/capsule-hover-chain.spec.mjs`（**9/9 绿**）：AC3 缘位点
  click 从「元素中心」改「外侧上角 sliver + elementFromPoint 校验」（叠收几
  何中心被游标卡压住）；5 连滚动→点击链、deck 让位沿承全绿。
- 回归 `tests/canvas-cardhead-capsule.spec.mjs`（28）、`tests/canvas-capsule-six.spec.mjs`（19）、`tests/wiki-graph-ux.spec.mjs` §G 环腿（1）全绿。
- 闸：checkJs 259 < 基线 260（canvas.js 零错误；3 个 gate failure = bgMergedPanel/
  plugins/sidebar，HEAD 既有基线账，本批零触碰）；build-web 196 源 orphans 0。

## 存量红清欠（同因同修，最小 diff）

- graphseal 批（2026-10-29，#wiki-btn 默认摘挂）后两个 spec 裸 `#wiki-btn`
  直点自此红（干净基线复现）：
  - `tests/capsule-size-uniform.spec.mjs`：configData mock 补
    `"features":{"wikiGraph":true}` latch（wiki 图谱腿重启）。
  - `tests/wiki-graph-ux.spec.mjs`：同上（全腿经 #wiki-btn 进图谱）。
- 基线既有、不在本批修复范围：`tests/canvas-glass-unify.spec.mjs` GU2-6
  `.canvas-source-toggle` 超时（干净基线 6c53ea678 复现同败；主树 HEAD 已由
  他批修掉）；`tests/wiki-graph-ux.spec.mjs` R8/§F 两腿（干净主树同败）。

## 偏离申报

1. **扇形增量取值**（112/7°、206/13°）：Pop 扇的 14px/2°、26px/3.5° 是「叠
   层贴同一盒」的增量；文件扇是侧向排布卡，按同语言递增比侧向展开（角度随环
   距递增、左右镜像、轻微 <15°）。卡面 220×280 逐字复用 Pop 规格。若作者要
   更紧/更散的扇，改 canvas.js 四个常量即可。
2. **卡名 = 中文签 + 文件名**：文件名本身（alpha.md 等）保持原样（是文件
   名不是 UI 文案），其左缀「文件/面板」中文签、扇顶中文提示条满足「带中文
   界面文案、不裸英文」。
3. **邻卡内半点击命中游标卡**：扑克叠收语义的固有性质（与 Pop 扇一致）——
   被压住的内半读作「背面」，点击由前景卡承接（=激活时零动作）。

## 运行方式

```
node tests/canvas-card-fan.spec.mjs
node tests/canvas-capsule-ring.spec.mjs
node tests/capture-canvas-fan.mjs evidence/20261030_canvas-card-fan <tag>   # 对照截图
```
