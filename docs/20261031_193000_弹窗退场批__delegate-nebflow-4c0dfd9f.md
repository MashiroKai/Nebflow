---
type: design-spec
status: frozen
date: 2026-10-31
scope: 检索跳转（js/chatSearch.js）、手机层弹窗族退场（css/mobileLayer.css 批次块 + js/mobile/popSheet.js + 12 模块接线）、设置页层级化（js/mobile/settingsSub.js + navController.js + modelPanel.js 下钻）——弹窗退场批（作者令 2026-10-31 15:13，三件）
---

# 弹窗退场批 · 设计规格书（2026-10-31 15:13 作者令）

关联：[[20261031_160300_mobile-fab-unify-search]]（检索面 FAB 入口与 act-and-exit 前身）、[[20261031_161500_mobile-topbar-batch__delegate-nebflow-53e293d1]]（手机层页面壳先例）、[[20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13]]（g2-fab 材质来源）、[[20261031_123300_bg-panel-hier-name-split__delegate-nebflow-4cbce603]]（后台面板簇）。

## §0 一句话目标

① 聊天记录检索命中不再弹消息浮窗，改主会话直接跳转定位（滚动 + 高亮闪烁）；② 手机端不再有任何形式的弹窗界面——所有弹层改铺满屏幕 + 左上角返回按钮（点名：后台任务面板、后台智能体面板、配置 LLM；顺带全面清残余）；③ 不再有「展开式」配置——苹果层级导航：点配置项进下一层级铺满屏配置、左上返回（工作台 meFace 四级层级为先例）。桌面端弹窗不在清除范围（只改①的检索定位）。

## §1 批①：检索命中 act-and-exit 主会话跳转

- 考古恢复 `chatSearch.js` 的 `jumpToResult/scrollToMessage/flashRow` 机制（21fab56d5 口径），WS 翻页驱动（getHistory/historyPage 环），滚动用「就近滚容器 scrollTop」纪律（msgQuote 同族）。
- 点检索结果 = 关搜索面 + 主会话滚动定位 + `.search-hit-flash` 闪击带（1800ms）。
- 手机层腿：关搜索面同时回聊天根（`__nfMobileNav.top === 'chat'`）再定位——T5 守卫。
- 删除物：`js/chatSearchFloat.js` 整文件、modal.css `.nf-msgfloat*` 块、locales `search.float*` 7 键（en/zh 各 7）。
- 判据换代：pr41-locale-search-jump S2/S3、msg-search-v3-smoke A4 → act-and-exit 契约。

## §2 批②：手机层弹窗族退场

### 2.1 统一机制

- 新叶子模块 `js/mobile/popSheet.js`：`mobileSheetBack(overlay, onBack)` 给弹层注入左上角「<」返回钮（48px g2-fab 同页面壳「<」材质，`.mfs-back` 标记类，幂等可换绑）。不 import navController，零回边。
- 形态批块落 `mobileLayer.css` 文件尾「弹窗退场批」块，四族：
  - **A 族**（overlay + 内盒）：bgt 输出卡 / secrets / workspacePicker / 通用确认三框（#modal-box/#delete-box/#conflict-box）/ takeover / rules / cfg-modal / **model-panel** / dropbox——overlay 吃不透明页面底（--color-bg），内盒 100% 铺平（radius 0、左右 border 退役、`padding-top: calc(64px + safe)` 让位返回钮几何）。
  - **B 族**（`.fm-context-menu`）：长按/右键菜单 → 全屏动作表（内联 left/top 压平，菜单项全宽玻璃行 44px 触击域，z 320）。
  - **C 族**（`.nf-term-tip.touch`）：词条触屏气泡 → 全屏词条卡（桌面 hover 泡不动）。
  - **D 族**：灯箱补返回钮层级（z 320）。
- 接线 12 模块：bgTaskOutputPopup / bgAgentPopup / flowAgentPopup（openStepPopup mount 后注入）、modelPanel、providersUi（返回=取消）、dropbox、modal.js initModals（返回=取消 / resolveConflict('cancel')，takeoverBlocking 绕行）、secretsPanel、workspacePicker（返回=finish cancel）、activityBar showLoginModal、contextMenu、lightbox、termTip、pathRules、daemonPanel。
- z 序批注更新：模态族 310+/10000/11000 原值桌面不动；手机层全部转页面形态。

### 2.2 点名面与返回语义

| 面 | 返回语义 |
|---|---|
| 后台任务输出卡 / 后台+flow 智能体查看器 | 关闭 |
| 配置 LLM（/model 面板、providersUi） | 取消（providersUi）/ 钻入态回列表、列表态关面板（modelPanel） |
| 通用确认三框 | 取消（与 Esc/遮罩同腿） |
| workspacePicker | onCancel |
| takeover（系统中断面） | **无返回钮**（强制二选一，无取消语义；`.takeover-live` 隐藏注入钮，modal.js 返回处理按 takeoverBlocking 绕行） |

### 2.3 回归实证修出的关键坑

`#daemon-config-overlay` 静止态无静态 CSS 隐藏（`display:none` 来自 daemonPanel.js 运行时注入的 PANEL_CSS），弹窗退场批给它无条件 `height:100% !important` 后，空壳在手机层拦截全屏指针事件（T3/T4/T5 初跑全被它挡住）。修法： fullscreen 规则只挂 `.open` 态；同时把返回钮注入从兜底创建分支提到 ensureOverlay 主路径（index.html 声明态原本拿不到返回钮）。

### 2.4 文档化排除项（非弹窗，不在退场范围）

voiceMode 全双工语音面（输入卡原位形变）、slash/mention 输入锚定自动补全下拉、#schedule-editor（开关附挂表单，非 chevron 展开）、登录卡即 nebflow-login-modal（铺满屏已含返回语义外的自身形态）。

## §3 批③：设置页层级化（展开式配置退役）

- 新叶子模块 `js/mobile/settingsSub.js`：`SETTINGS_SUB` 三子页注册表（settings-stt / settings-ttl / settings-jev），机制与 navController overlay 收编 `adoptOverlayFace` 同锚点惯例——子页入栈时折叠体节点（#stt-advance-body 等）re-parent 进子页面体，出栈归还设置页原位；节点内事件绑定随节点走，桌面渲染/绑定零改动。设置页重渲（WS echo）按 id 重取最新节点收编、摘除旧副本。入口行点击拦截 = document 捕获期委托（先于按钮自身 bubble 监听，手机层 stopPropagation + push；桌面穿透原样）。
- navController 接线：import、PAGES 展开 `...SETTINGS_SUB.map`、buildSection 分派、renderPages 汇合 `syncSettingsSubFaces(stack)`、activateNav 注入 `initSettingsSub({push})`。
- CSS：手机层 `#settings-modal .settings-collapse-body { display:none }`（折叠体在设置页内不渲染）、入口行 chevron `rotate(-90deg)`（右转 = 层级导航 affordance）、子页体内补侧距/底距。
- modelPanel 同批下钻：`drilledRole` 状态——手机层点角色头 = 下钻该角色全屏配置（renderPanel 只渲染被钻块），钻入态再点同头 = 回列表；返回钮语义 = 钻入态回列表 / 列表态关面板；桌面 drilledRole 恒 null 零变化。

## §4 验收

- 守卫 spec `tests/mobile-popup-retire.spec.mjs` T1-T6 6/6（/model 铺满屏+下钻、bgt 输出卡、secrets/wsp/confirm/长按菜单、设置子页 push/pop、手机层检索跳闪、桌面零变化守卫）。
- 取证 `tests/popup-retire-shots.mjs` → `evidence/20261031_popup-retire/`（26 张）：双端双主题整视口截图 + 下钻/跳转动画帧（model-drill-frame1/2、settings-sub-frame1/2、search-jump-frame1/2）+ 几何探针（铺满屏断言 w=vw/h=vh/r=0/backDisplay=flex；桌面 w<600/r=20px/backDisplay=none）——ALL PASS。
- 终态回归（最终代码重跑）：mobile-v3 / mobile-dock / mobile-p12-settings / mobile-p6-bgstatus / mobile-p2-search / settings-thinking-global-only / micorb-settings-hidden / pr41(12) / mobile-msg-longpress(5) / mobile-popup-retire(6) / settings-cleanup 共 66 passed + 1 failed（仅存量 P parity）；tmp-msg-jump-verify 10/10、msg-search-v3-smoke 39/39、stream-smoke 18/18。check-js-types PASS（344<350）、check-circular 无新增 SCC。
- 存量欠账（pristine fee1f3cd3 同款实证，非本批引入）：
  · dialog-unify + bg-task-output-viewer 共 7 失败；
  · search-realshape T1-T4（桌面 `#search-btn` 被 nb-header-hide 隐藏，早前顶栏批遗址）；
  · settings-key-reveal T1/T4（#jev-head DOM 换代欠账）；
  · settings-cleanup P locale parity（zh 多键，双语键集欠账）。
- 本批修出并回绿的点：longpress T1 判据换代（菜单项排除注入的 .mfs-back）；
  settings-cleanup T1[zh] 在同 worker 紧跟 realshape 跑时的顺序污染（独立重跑绿，非代码问题）。
- 注：无头 swiftshader 环境下 g2-fab 液体玻璃材质偶发渲染为纯色圆（pristine HEAD 同款），属 harness 视觉伪影，非本批缺陷。
