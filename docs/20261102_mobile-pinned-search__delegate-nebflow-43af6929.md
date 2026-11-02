---
type: design
status: implemented
---

# 常驻顶部搜索条批：消息/联系人两 tab 搜索框固定页顶、搜索浮钮退役（2026-11-02 07:13 作者令）

作者令原话（07:13）：

> 「手机端『消息』和『联系人』两个 tab 的**搜索框固定在页面顶部**，不再用右上角的悬浮按钮来控制搜索。」

范围：仓库 `Stand`，kernel 单会话。手机档优先；桌面端搜索入口**零触碰**。

上游形态见 [[20261031_160300_mobile-fab-unify-search]]（悬浮钮统一+搜索批：右上
`.mh-fab-search` 48px 浮钮 + `.mh-search-open` 展开式搜索区，本批在这两个 tab 上
整件退役）与 [[20261102_muse-avatar-shrink__delegate-nebflow-d616c46a]]（同窗口在飞的
会话头批，零交叉）。页面壳/导航底座见 `js/mobile/navController.js` dock 批注释
（dock 根页 = `.mobile-page-nohead`，body 无页头）。

---

## 1. 实现（三文件）

### 1.1 `js/mobile/socialSearch.js`（整文件重写）

形态换代：**浮钮呼出 → 页面体流内首件常驻**。

| 项 | 旧（10-31 批） | 新（本批） |
|---|---|---|
| 载体 | `.mh-fab-search`（48px 浮钮，top16/right16） | `.mh-search-dock > .mh-search-bar`（页面体**首件**） |
| 状态语汇 | 页面 section `.mh-search-open` + `max-height` 220ms 弹簧 | dock 自身 `.has-query`（单点驱动，无过渡） |
| 呼出 | 点浮钮 / Esc 收合 | 常驻；输入即搜、清空即回列表（Esc = 清空） |
| 部件表 | `fabs: Map<pageId, Fab>` | `bars: Map<pageId, SearchBar>` |

保留（行为零退化）：范围 chips（全部/好友/设备/群聊）、按会话分组结果、`<mark>`
片段高亮、点行 `openConversation + revealMessage` 跳转定位、300ms 去抖 +
requestId 竞态丢弃 + `AbortController` 中止、每会话前向翻页上限（20 页 × 50 =
1000 条，脚注声明）、locale-changed 重绘、teardown 清干净。

联系人页 = 「同一条常驻条覆盖该页全部内容段」：常驻条只做输入的**单入口**，
查询串写回 `contacts.js` 的 `searchQ` 单一源并走同一条提交流水（450ms 去抖 →
`submitSearch(q, { throttle: false })`）。

### 1.2 `js/contacts.js`（导出单一提交流水）

新增 `export async function submitSearch(rawQ, opts = {})`：桌面提交键 / Enter /
手机常驻条三处**同源**。空串 = 清场回列表（清 `searchResult`/`searchErrorKind` +
`render()`）；`opts.throttle !== false` 才走 1s 反爬最小间隔（手机侧输入已在上游
去抖，故带 `{ throttle: false }`）。`buildSearch()` 里的内联 `submit` 闭包删除，
提交键与 Enter 都改为调它（消除第二份实现）。

### 1.3 `css/mobileLayer.css`（②③④ 三块）

- **② 常驻顶部搜索条**：`.mh-search-dock{flex:none;padding:8px 0 0}`、
  `.mh-search-bar{height:44px;margin:0 16px;border-radius:999px;background:
  var(--glass-control-bg);border:1px solid var(--glass-control-border);backdrop-filter:
  blur(var(--glass-control-blur)) saturate(1.2);box-shadow: inset 高光 + inset 底缘 +
  0 1px 3px 浮影}` —— **零新色值**，深浅双主题由 `sapphire.css` 既有
  `--glass-control-*` 映射承担。无 transform（常驻不位移）。
  静止态隐藏 chips/status/note（`.mh-search-dock:not(.has-query) …{display:none}`）；
  有查询时 dock 吃满余量、结果流自滚
  （`.mobile-page[data-page="messages"] .mh-search-dock.has-query{flex:1;min-height:0}`）。
  **让位**：`.mh-search-dock.has-query ~ #fm-conversations{display:none}`（dock 与
  `#fm-conversations` 同为 `.mobile-page-body` 子节点 ⇒ 兄弟选择器成立）。
- **③ 联系人页承载**：`.fm-search-block > .fm-search{display:none}`（桌面输入行
  收起，避免同页两枚输入框）+ `.fm-search-block:not(:has(.fm-result-card)){display:none}`
  （无结果卡时整块不留空档）。
- **④ 搜索回跳胶囊抬层**：`body.mobile-layer #main [data-nf-search-return="1"]{z-index:170}`
  —— 大于页面容器 `#mobile-pages` 156、小于 `#mobile-dock` 200 与桌面模态族 310+。

旧语汇零残留：`.mh-fab-search` 块、`max-height` 收合、scaleX 弹簧、
`padding-right:72px` 全部删除。

### 1.4 其它面零触碰（申报面）

- 聊天根顶缘搜索键 `.mh-chrome-btn[data-chrome="search"]`（`mobile/chatChrome.js`）：
  未改，仍 48×48 @ top16/right359 且仍开「搜索」页。
- 详情页搜索页（`navController` PAGES `search` → `chatSearch.openSearchModal`）：未改。
- 桌面端：本批三条规则全部挂 `body.mobile-layer` 之下（该类只在 ≤768px 激活），
  桌面渲染逐字节不变。

---

## 2. 验收（`tests/mobile-pinned-search.spec.mjs`，11/11 绿）

旧 `tests/mobile-fab-search.spec.mjs` 按新契约整件改写并 `git mv` 为
`tests/mobile-pinned-search.spec.mjs`（F1-F5 浮钮契约作废）。

| 项 | 判据 | 结果 |
|---|---|---|
| P1 | 消息页条 = body 首件 / 44px / left16-right359 / 999px / 贴页面顶 ≤12px / 材质 = `--glass-control-bg` 探针同值 / 含 inset 影 / transform none / 首行 top ≥ bar.bottom / 浮钮 0 / `.mh-search-open` false / chips+note+status 不占位 | 绿 |
| P2 | 联系人页同款几何材质 + 桌面 `.fm-search-input` 行 `display:none` + 无结果卡时搜索块 `display:none` + 好友首行在条下 | 绿 |
| P3 | 输入「部署」⇒ 3 组（分页君/阿凯/周末球局）· 3 行 · 3 个 `<mark>`；`#fm-conversations` `display:none`；首结果 top ≥ bar.bottom | 绿 |
| P4 | 清空键 ⇒ 行清零、输入空、`has-query` 落、列表回位 | 绿 |
| P5 | Esc ⇒ 同上（条保持可见，无收合位移） | 绿 |
| P6 | 联系人输入 `xintongxue` ⇒ 结果卡含「新同学」；输入串跨面板重渲保留（常驻条与 `searchQ` 同值）；清空 ⇒ 卡片消失 + 首行回位 | 绿 |
| P7 | 其它面零触碰：chrome 搜索键 48×48 @16/359 且能开搜索页；搜索页/详情页 `.mh-search-dock` = 0；详情页「<」48 @16/16 + 大标题在 | 绿 |
| P8 | 胶囊手机层 z = 170，层序 156 < 170 < 200 | 绿 |
| P9 | 封存态（friends=false）两页 dock 0 + 浮钮 0 + 骨架卡在 | 绿 |
| P10 / P10b | 亮档 `rgba(255,255,255,0.6)` / 暗档 `rgba(255,255,255,0.14)`，两页与 token 探针同值 | 绿 |

回归：`mobile-dock.spec.mjs` D1-D19 全绿（21/21）；`search-return-pill.spec.mjs`
（独立脚本，chromium）39/39 全绿 —— 手机 390×844 × 亮/暗两腿的跳转→胶囊→回位
链路在常驻条形态下照旧成立（胶囊被本批抬到 170 后压在会话页之上、dock 之下）。

实拍：`evidence/20261102_mobile-pinned-search/`（375×812 × 亮/暗），
脚本 `tests/shots-mobile-pinned-search.mjs`（零 console error 门）：

- `m1-messages-pinned-*` 消息页静止态（常驻条 + 右上**无**浮钮 + 首条在条下）
- `m2-messages-results-*` 输入「部署」结果态（列表让位 / 分组 / mark / 脚注）
- `m3-messages-cleared-*` 清空回列表态
- `c1/c2/c3-contacts-*` 联系人页同三态
- `p1-search-return-pill-*` 搜索回跳胶囊一帧（「回到跳转前位置」压在会话页上）

---

## 3. 假设与开放项（申报）

1. **联系人页没有「好友/群/设备」切面控件**（现状 = 单条流：搜索块 → 新的朋友 →
   设备入口 → 群邀请 → 创建群聊 → 好友列表）。作者令要求「三面共用同一条搜索
   入口，切面不重建输入内容」——本批落地为「**同一条常驻搜索条覆盖该页全部内容
   段**」，输入框住在收编面**之外**（页面体首件），面板重渲零触碰输入内容（P6 已
   钉）。若后续该页新增切面控件，本条的输入串天然跨切面保留，无需再改。
2. **面板标题「上滑收缩胶囊」批**（在飞）只作用于详情页（`buildSection` 里
   `if (!def.dock)` 才建 `.mobile-page-header`/`.mobile-page-title`）；消息/联系人
   是 dock 根页（`.mobile-page-nohead`，body 无页头、`padding-top:0`）⇒ 搜索条与
   收缩标题**不同屏**，不存在打架。视觉层级仍按「条贴在页体最上、其它面浮层在
   其上」处理（dock z200 > 页面容器 z156）。
3. **消息页「全部」档仍含设备会话与后台会话**（沿用 10-31 批的 scope 语义：chips
   只在「好友/设备/群聊」间收窄，不改会话集合本身）。
4. 旧测试同步：`tests/shots-mobile-fab-search.mjs` 删掉浮钮点击/展开 4 帧腿（改拍
   两页常驻条静止帧）；`tests/mobile-emergency-shots.mjs` 的 2b/3b 腿由「点浮钮 →
   填充桌面输入」改为「直接填充常驻条 → 清空」。

## 4. 关联

- [[20261031_160300_mobile-fab-unify-search]]（被本批在这两个 tab 上取代的浮钮搜索批）
- [[20261102_muse-avatar-shrink__delegate-nebflow-d616c46a]]（同窗口在飞批，零交叉）
