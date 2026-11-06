# contact-remaining-three 批 · 红绿门禁读数（2026-11-06 20:49）

三件（作者令逐字要点见批令）：
① 日期行上移（跨天只显一次，主会话居中 footer 同实现）
② 3(b) 会话内独立搜索（scope 隔离）+ 标题「搜索」两字
③ 3(a) 云同步前端徽标三态（真数据源 /api/profile/sync-status）

## 门禁读数

| 腿 | 结果 |
|---|---|
| frontend-gates（19 静态闸） | exit 0 全绿（frontend-gates.log） |
| tests/contact-shell.spec.mjs 改前树（/tmp/cp-before = git archive 9cf2d6280） | 60 项 · FAIL 18（readings-before.json） |
| tests/contact-shell.spec.mjs 本树（改后） | 60 项 · **FAIL 0**（readings-after.json） |
| @playwright/test 定点套件（profile-dual-desc + friend-info-panel + mobile-p2-search） | **10 passed** (23.9s) |

## 改前红（18 FAIL 全部 = 本批新增面判据；既有判据全 PASS）

- D1-D6 日期行族：seps=[]（零日期行）；footer 带日期层 28/40（昨天/更早逐条重复）；40 个 data-ts-text-format="day" attr
- S1 标题：title=「搜索聊天记录」≠「搜索」
- C1/C2 会话档落位：convScope=（空）/ scopeDisplay=inline-block（select 未折叠）
- C4 会话档命中 rows=0；**C4b 串全局铁证：sessionHistoryHits=/api/sessions/visual-root/history**（改前打主会话 history）
- C5 跳转 flashed=false；B1-B3 徽标空（state=''、text=''）

## 改后绿（关键读数）

- 1440 日期行 `["星期二@720","昨天@720"]` 居中（flowCenter=720），footer 纯时钟 0/40；375 档同（星期二,昨天 · 0/40）
- 会话档：convScope=1 · scopeDisplay=none · rows=1 · **sessionHistoryHits=0** · flashed=true · 状态行「分页君 · 40 条结果」
- 徽标三态随真值：synced→已同步 / syncing→待同步 / local-only→仅本地（B1/B2/B3 全绿）

## 本批后期补修（追证）

- C5 竞态根因：jumpToConvResult 复制了 jumpToResult 的 goHome 腿 ⇒ closePageFace('conversation')
  的异步 closeChat 链（navController.js:2436）与新 openConversation 赛跑，新窗被整窗摘除
  （实证：reveal=true 后 .fm-flow=0、#fm-chat-overlay absent）⇒ 会话档跳转去掉 goHome
  （closeSearchModal 的 CLOSE_WATCH 本就弹回「会话」页，窗还开着）。
- 手机层会话档入口改走搜索**页**（stageConvScope + pushPage('search')）：body 级 overlay
  在页面世界是 pointer-events:none 状态载体（mobileLayer.css:1137 族），直开=可见但点不可交互。
- data-conv-scope 双写载体+内容面（#search-modal 被收编进页体后，以载体为锚的 CSS 后代
  选择器断链 —— C2 实证 display=inline-block）。

## 附带修（同批顺手，红绿同守）

- chatSearch convRowToUiMessage 透传 wire id（normalizeConv ord 回落数组下标 ⇒ 跳转错位）。
