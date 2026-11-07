# ROUND2 — 四条续修（气泡白色系 / 设置留白 / 提示位去重 / 工作台 hero）

作者令原文见各节标题后的逐字引语。取证方式同 ROUND1：运行中的引擎服务的是主检出
web 树，改动的文件在工作树 —— 用 Playwright `page.route()` 把**被改文件就地**换成
工作树版本（`ROUTE=1`=改后 / `ROUTE=0`=改前），引擎、数据、布局全真。
脚本 `round2.mjs`（逐面实拍+读数）、`pill-exclusive.mjs`（互斥不变式）、`probe-pill.mjs`（运行时探针）。

---

## ① 气泡回到白色系

**作者逐字**：「还是很丑啊 我们之前的白色气泡不是很好看吗」

改法（单点 token，好友/群/主会话同源；无第三档）：
- `css/base.css:40` 亮档 `--color-bubble-user: #ecebe7`（暖调近白，无蓝相；对方 = `--color-bubble-ai: #fff`）
- `css/base.css:137` 暗档 `--color-bubble-user: #33312d`（深中性，无蓝相；对方 = `#1c1f27`）
- 区分只靠明度差 + 位置 + 既有 whisper 边/柔影（`--glass-bub-ai-border/-shadow`），不靠色相。
- 消费方不变：`css/glass.css` 单点块 `--nf-bub-ours-bg`，门禁 `scripts/check-bubble-side.mjs` PASS（R1–R5）。

实测（`_data/round2-before/after.json`，getComputedStyle 每条会话我方气泡）：
| 档 | 改前（线上） | 改后 |
|---|---|---|
| 亮 | `color(srgb 0.584 0.925 0.412)` = 微信绿 #95ec69 | `color(srgb 0.925 0.922 0.906)` = **#ecebe7** |
| 暗 | 同上（绿） | `color(srgb 0.200 0.192 0.176)` = **#33312d** |

图：`20-fixes/06-bubble-white/<theme>-<size>__chat-{before,after}.png`（亮/暗 × 手机/桌面）。

## ② 设置面板留白

**作者逐字**：「然后设置面板的边距也没有考虑留白」

改法：把留白做成令牌单源，再让面板统一安全区（不逐面手填）：
- `css/stand-tokens.css:575-583` 新增 `--set-panel-pad-x/y/b`，改 `--set-group-gap` 24→36、`--set-card-pad-y` 6→10、`--set-row-pad-y` 6→10
- `css/settingsRows.css:27` `#settings-content` 统一安全区 `padding: 16px 16px 24px`
- `css/settingsRows.css:283-288` 账号段退出登录钮内缩+圆角（原为贴满卡宽的方角白条）
- 手机档 `css/settingsRows.css`（`@media max-width:768px`）面板底部 = 24 + dock 净空(54+10+8) = **96px**

实测（同一节点 getComputedStyle；手机 375×812）：
| 项 | 改前 | 改后 |
|---|---|---|
| `#settings-content` padding | 16/16/**16** gap **24** | 16/16/**96**（手机）/24（桌面）gap **36** |
| `.set-row` padding | 6/6/16 | **10/10/16** |
| `.set-card` padding-y | 6 | **10** |
| 卡左缘 x / 宽（手机 375） | 0 / 375（贴满） | **16 / 343** |
| 滚到底末卡与 dock 相交 | 有 | **false（不相交）** |

图：`20-fixes/07-settings-space/<theme>-<size>__{top,bottom}-{before,after}.png`（8 张）。

## ③ 「回到底部 / 新消息」提示位去重

**作者逐字**：「然后主会话的输入框上方的小气泡 回到底部和新消息 这俩会同时出现 这俩功能是一样的 应该去重」

**根因实测**：主会话里「回到底部 / ↓N 条新消息」本来就是**同一枚**胶囊的两个文案档
（`js/scrollPill.js` 单实现，`js/utils.js:2214 syncScrollPill` 单判据）—— 主会话实拍
五态各只出现一枚（`probe-pill.mjs`）。真正会「两枚并列」的是**同宿主第二枚胶囊**：
搜索回跳位（`js/searchReturnPill.js`，`stack:'above'`）与消息位胶囊可以同时可见
（组件原设计用 stackLift 把两枚错开叠放）。机械复现见下。

改法：在胶囊工厂加**单槽互斥**不变式（全站唯一出胶囊点）：
- `js/scrollPill.js:150-171` `show()` 前先判优先级：未读计数(2) > 搜索回跳位(1) > 回到底部(0)；
  高优先级在场 ⇒ 低优先级让位；低优先级在场 ⇒ 就地收起（`hideKeepCount()`，不清计数）
- `js/scrollPill.js:173 prec()`、`js/scrollPill.js:190 resyncHost()`：高阶退场后低阶按自身状态自行恢复
- 状态判定与显示条件因此收敛成一枚：`count>0 ⇒ 「↓ N 条新消息」可点回底并清零` /
  `count==0 且滚离底部 ⇒ 「回到底部」` / `在底部 ⇒ 不显示` / 永不并列。

机械验证（`pill-exclusive.mjs`，页面内动态 import 同一模块，造两枚胶囊）：
| 状态 | 改前 | 改后 |
|---|---|---|
| A 只有搜索回跳位 | 1 枚 | 1 枚 |
| B 叠加未读计数后 | **2 枚（↓3 条新消息 + 返回搜索结果）← 作者看到的并列** | **1 枚（↓3 条新消息）** |
| C 未读清零回底后 | 1 枚 | 1 枚（搜索位自动回来） |
| 全状态 ≤1 枚 | **false** | **true** |

真实浏览器五态（375×812 亮/暗各一遍，`_data/round2-after.json` → `runs.*.pill`）：
`已在底部=0 枚` / `滚离底部=只「回到底部」` / `新消息到达=只「↓1 条新消息」` /
`连续第 2 条=只「↓2 条新消息」（计数更新）` / `点击后=0 枚且 scrollTop 回底`。
图：`20-fixes/09-pill-dedupe/<theme>-mobile__state{1-backToBottom,2-newMsg1,3-newMsg2,4-afterClick}-{before,after}.png`
（8 张 = 亮/暗 × 4 态 × 改前改后；`pill-shots.mjs` 实拍，四态可见文案与上表逐项一致）。

## ④ 工作台 hero + 账号去重 + 设置统一入口

**作者逐字**：「手机端工作台上面的账户 做出 hero 那种感觉 并且去重 把设置的统一到这里」

**理解与实现**（按「hero 作为唯一账号入口、设置页保留功能分项但不重复身份信息」落地）：
- `js/mobile/navController.js:466-524` 工作台顶部 = `.mf-me-entry` 改造为 **hero**：
  `data-hero="account"`、头像框 `huge`(88)、名/邮箱为主体、`我的空间 · 账号与设置` tagline、
  独立 `[data-hero-settings]` 设置入口（`stopPropagation`+`pushPage('settings')`，键盘可 Enter/Space）
- `css/mobileLayer.css:2648-2720` hero 造型：148px 高、22px 圆角、**无边框无分隔条**、
  `background-color: var(--color-surface)` + sapphire 径向淡染、柔影（亮 0.06 / 暗 0.36）
- `js/mobile/navController.js:104-109` **删除**工作台列表里的「设置」行（同一功能两个入口会打架），
  设置页仍可由 hero 唯一入口 push 进同一详情页壳
- `js/sidebar.js:859-865` 设置页账号段**撤掉身份卡**（头像/用户名/邮箱重复展示），只留一行指路
  `身份与头像在工作台顶部`；连接/服务/退出登录等功能分项保留

实测（亮/暗 手机 375×812）：hero box `[16,32,343,148]` 圆角 22 边框 0 底 `rgb(255,255,255)`（暗档 `surface`）、
头像 88×88、`settingsBtn=true`、列表行集 `projects/plugins/usage/reminder/daemon/background`（**不再含 settings**）；
设置页 `identityCardPresent: false`（改前桌面 true）、`dedupHintPresent: true`。
进入设置的通路已在脚本里实走：点 hero 设置钮 → 设置页实拍（即 07 那组图）。

**重复账号展示位置清单 → 处置**：
| 位置 | 处置 |
|---|---|
| 工作台顶部 `.mf-me-entry`（头像/名/邮箱） | **保留并升级为唯一权威展示（hero）** |
| 设置页身份卡 `#settings-identity-card`（头像/用户名/邮箱胶囊） | **删除**（`js/sidebar.js:859`），改一行指路 |
| 设置页账号段 `登录账号: <名>` + `退出登录` | **保留**（登录态读数与动作，不是身份展示；给出「未登录/已登录」语境与登出入口） |
| 手机 `me` / `me-user` / `me-avatar` / `me-bind` 子页 | **保留**（hero 的下钻面，非并列展示） |
| 桌面 activityBar 账号菜单 | **保留**（桌面无 hero；桌面侧唯一账号入口） |

图：`20-fixes/08-workbench-hero/<theme>-mobile__hub-{before,after}.png`（4 张，含 07 设置页对照）。

---

## 未做及原因

- **手机 dock 5 项直角**（上轮遗留）：`dock-key` 自身 `border-radius:0` 是刻意的热区件，
  视觉圆角由父层 dock 胶囊提供；要改必须同时给按压底/焦点环补圆角，本轮未动（见上轮读数：直角项 手机 5 / 桌面 0）。
- **消息记录搜索钮定位**（上轮遗留，待作者裁定）：属 2026-10-31 悬浮钮批裁定，改需推翻该裁定；
  本轮 ② 让位后再实拍，钮下已无被压的消息。
- 设置页账号段里 `.cfg-*` 老行族（如 `登录账号`）仍走自身内距，未并入 `--set-row-*` 网格；
  视觉上左缘与 `.set-row` 对齐但纵向内距不同，属既有结构，未在本轮改（改需动 `js/neblink.js` 模板）。
- 未跑全量 sbt；未 push/tag/改版本号/重启引擎。
- 门禁：`check-bubble-side` PASS、`check-settings-ios-rows` PASS、`check-design-tokens` PASS（工作树上跑）。
- 截图里若出现「控制台已在其他位置打开」弹窗，是并发批次抢控制台所致（脚本已在每次拍摄前点重新接管）。
