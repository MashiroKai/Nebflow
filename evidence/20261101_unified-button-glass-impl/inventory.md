# 统一按钮+玻璃批 · 落点清点（inventory）

批前侦察产出 · 2026-11-01 · 树 = worktree delegate（分支 delegate/delegate-nebflow-94fe78c9），行号均为**本树实测**；设计稿 docs/design/20261101_141229_unified-button-design__cc28a33e.md 内行号漂移处已逐条校正。机械底账：`tools/cssaudit-report.json`（246 处 backdrop 声明全清单）、`probe-report.json`（计算样式实测）、`shots/`（双引擎实拍）、`baseline/`（checkJs）。

方法三线交叉：① CSS 源逐规则机械扫描（cssaudit.cjs）；② Playwright 双引擎计算样式探针（probe.cjs，真文件打桩 + 生产 boot 链）；③ 人工读源核对。稿内「统一后」列直接抄自设计稿 §5.1/§5.2，本清点只补「现状实测」与差距。

---

## 一、玻璃面清点（任务 1a）

### 1.1 canvas ask 面板 / 气泡列（css/ask.css + js/askDock.js）

| 落点 | file:line | 玻璃写法现状 |
|---|---|---|
| 入口钮 `.ask-fab`（桌面） | ask.css:65-80（`--ask-fab-size` 44px 定义 :34） | cv 档：`--cv-bg`(:72) + `-webkit-backdrop-filter`(:73) / `backdrop-filter`(:74) 双写 `blur(var(--cv-blur,--glass-blur)) saturate(var(--cv-saturate,1.15))` + `--cv-shadow`(:75)；**被 liquid-glass.css:198-212 F1 覆盖**（同特异度后加载胜出）→ 实测 blur(20px) saturate(2.2) |
| `.ask-fab` 移动档 | ask.css:250-257（fixed 44px，dock 上方 z260）+ mobileLayer.css:1874-1883（**48px 覆盖**，后加载胜出）+ spring/reduced-motion mobileLayer.css:1914-1931 | 同上 F1；稿内「48px fixed」与现状一致，稿行号 1912-1921 → 实际 1874-1883（漂移 38 行） |
| 通知点/脉冲 | ask.css:91-102（`.ask-sess-dot` 绿点 :91-96；`.pulse` 动画 :98-102，环色 sapphire） | 非玻璃；统一时保留 |
| 覆盖层 `.ask-dock` / 列 `.ask-colwrap`/`.ask-col` | ask.css:113-150 | 面本体无玻璃（排布层）；列 mask 渐隐 :138-139 双写 |
| 列顶渐隐幕 `.ask-veil` | ask.css:152-161 | **g2 族**：`--g2-veil` 渐层(:155) + blur(`--g2-fade-blur` 18px) sat 1.5 双写(:156-157) + mask 双写(:159-160)；no-backdrop 降级 :163-166。依赖 `--g2-*` 退役时需随 M9 同步换血 |
| 气泡 `.ask-bub` | ask.css:174-185（user 变体 :187-190） | cv 档：`--cv-bg`/`--cv-border` + 双写(:181-182) blur24 sat1.15 + `--cv-shadow`；user = sapphire 0.14 底/0.35 边（backdrop-filter 保留）。实测 win=ask.css，blur(24px) saturate(1.15)（= Header 档，**已达标**） |
| 输入条 `.ask-input` | ask.css:208-217 | cv 档双写(:213-214) + `--cv-radius-bar` + focus-within sapphire 0.45(:217)。实测 blur(24px) saturate(1.15)（**已达标**） |
| 发送钮 `.ask-send` | ask.css:228-243 | 扁平绿 `--ctl-send-*`（32px 圆，无 blur）——范围外维持 |

### 1.2 主会话消息气泡（css/chat.css）

| 落点 | file:line | 现状 |
|---|---|---|
| `.bubble` 基座 | chat.css:1099-1109 | 仅几何（max-width 80% / r16 / padding），无材质 |
| `.bubble.user` | chat.css:1110-1114 | **实色 `--color-bubble-user`（=#95ec69 实测），零 backdrop-filter** |
| `.bubble.ai` | chat.css:1329-1336 | 实色 `--color-bubble-ai` + whisper 边 0.04 + 微投影，零 blur |
| `.bubble.injected` | chat.css:1122-1126 | sapphire 0.12 tint，零 blur |

结论：**主会话消息气泡全族零玻璃**（现状即实色扁平）；本批玻璃面清点中它们是「非玻璃」基线。会话面真正的玻璃是 `#header`（chat.css:44-78，基准件 B：`--glass-bg` + blur24 sat1.15 双写 :48-49 + 折射线 :46 + 底缘透明 :55 + inset 高光 :76-77）。周边玻璃件：`.scroll-new-pill` chat.css:4176-4202、`.notif-item` ~2959-2970、`.ask-pending-row`/`.ask-pending-next` ~3072-3090、`.ws-pick-target` ~3799-3811、`#bg-dropdown` ~751-765（均 `--glass-*` blur24 sat1.15 双写）；`glass-control.ob-ghost` :2563-2568 与 `.chip-row .option-btn` :2865-2877 为显式 `backdrop-filter:none` 档。

### 1.3 输入框（css/input.css #input-bar + 表单输入）

| 落点 | file:line | 现状 |
|---|---|---|
| `#input-bar` 原配方 | input.css:17-46（基准件 C：`--glass-bg` + blur24 sat1.15 **双写 :21-22** + 三档栈阴影 :32-35 + r20 + `::before` 折射线 :47-62） | **被 liquid-glass.css:180-188 v2 改皮覆盖**：`--g2-input-bg` 渐变(.74→.58) + blur24 **sat1.6** + 高光 .90 + 投影 .08；token = stand-tokens.css:193-196/224-227/250-253。实测 win=liquid-glass.css，bg=linear-gradient、saturate(1.6) → 即稿 D10「撤改皮」的对象 |
| `#input-bar` 状态档 | input.css:249-258（frozen）、980-988（disconnected 灰） | 高特异态在 input.css 原样保留，改皮撤销时需回归这三态 |
| 队列条 `#queue-bar.visible` | input.css:1272-1300 | `--glass-bg` + 双写(:1286-1287) + 三档栈 + 折射线 —— Header 档达标 |
| slash/mention 下拉 | input.css:1093-1121（`--g2-fab-bg` + blur20 sat2.2 双写 :1099-1100）+ 降级 :1253-1261 | **F1 材质消费点**（非按钮面），`--g2-*` 退役时需换血 |
| 对话框表单输入 | `.modal-input`/`#modal-input`/`#memory-content-input`/`#rules-input` modal.css:114-155；`.cfg-input` modal.css:593-625 | etched 玻璃（`--glass-etched-bg/border`，**无 blur**）+ sapphire focus glow——范围外维持 |

### 1.4 对话框（css/modal.css）

| 落点 | file:line | 现状 |
|---|---|---|
| 共享玻璃卡 | modal.css:33-53：`#modal-box`/`#delete-box`/`#conflict-box`/`#takeover-box`/`#memory-modal`/`#rules-modal`/`#settings-modal`/`#search-modal`/`#onboarding-panel`/`.model-panel`/`.wsp-panel` | `--glass-bg` + 双写(:45-46) blur24 sat1.15 + 三档栈——Header 档达标 |
| 遮罩模糊 `#modal-overlay.takeover-blur` | modal.css:468-471 | `--cv-blur/--cv-saturate` 双写（毛玻璃背景，四修批） |
| 日期弹层 `#search-date-popover` | modal.css:811-831 | blur24 sat1.15 双写 + 白 .88 底（backdrop-root 注记） |
| `.cal-pill-listbox` | modal.css:880-895 | blur24 sat1.15 双写 |
| Toast `.nebflow-toast` | modal.css:1179-1200 | glass-control 材质双写(:1193-1194) |
| 确认键中性变体 | modal.css:241-248 | glass-control 材质 |

### 1.5 全站机械底账（cssaudit-report.json）

- backdrop-filter 声明共 **246 处**（standard 123 + `-webkit-` 123），**双写率 100%**（无单写漂移）；36 处为 `none` 降级/摘除档（scrollUnderGlass、no-backdrop 块、ob-ghost 等）。
- 无定义且无 fallback 的 var 引用 11 个：`--color-surface-2`、`--font-mono`、`--color-text-secondary`、`--scale-factor`、`--anno-`（动态拼接前缀，误报嫌疑）、`--mermaid-usecase-*`×7（mermaid 动态生成，误报嫌疑）。
- 有 fallback 但无定义的引用 23 个（静默回退链：`--sapphire-rgb`、`--color-frame-bg`、`--r-pill` 等）——F1 退役批量删除 token 时**不得误删仍被 fallback 链引用的名字**，全清单在报告 JSON。

---

## 二、悬浮按钮 · 手机端 M1-M10（对照稿 §5.1）

| # | 落点 | 现状实测（file:line + 材质） | 与稿差距 |
|---|---|---|---|
| M1 | dock 待办钮 `.mh-chrome-btn[data-chrome=tasks]` | 创建 js/mobile/chatChrome.js:153（类 `mh-chrome-btn g2-fab`）；几何 mobileLayer.css:141-149（top 16+safe；tasks left16 @146 / search right16 @147 / svg20 @148 / active scale.88 @149）；材质 = `.g2-fab` 基材 liquid-glass.css:33-46 + `::before` 折射层 :49-60（blur20 sat2.2 双写 :58-59）+ 移动 48px :313-316 | 现状=F1 ✓ 稿判；稿引 mobileLayer.css:146 ✓；材质出处稿写 105-122 实为 33-60（105-122 是 `.g2-fab` 桌面几何块） |
| M2 | dock 搜索钮 `[data-chrome=search]` | chatChrome.js:169，其余同 M1 | 同 M1 |
| M3 | `.mh-fab-search` | 创建 js/mobile/socialSearch.js；几何 mobileLayer.css:1937-1943（top 16+safe / right16 / z20）；材质 `.g2-fab` | 稿行号 1975-1981 → 实际 1937-1943（漂移） |
| M4 | `.ask-fab` | askDock.js:204（仅 `ask-fab`，**无 g2-fab 类**）；材质 ask.css:65-80 cv 档 + F1 覆盖 liquid-glass.css:198-212；移动几何 ask.css:250-257 + mobileLayer.css:1874-1883（48px 胜出）；通知点/脉冲 ask.css:91-102 | ✓ 稿判；稿行号 1912-1921 → 实际 1874-1883 |
| M5 | canvas 工具圆钮族（source/ref/find/refresh） | 移动几何 mobileLayer.css:1886-1899（48px + spring scale.88）；创建 viewers/shared.js（source :631-637 / ref :725-752 / find :776-793 / refresh :800+）；材质同 D1-D4 = F1 覆盖 | ✓ |
| M6 | `.canvas-refback-btn` | split.css:3430-3457（chip 胶囊：`--glass-bg` + blur24 sat1.15 双写 :3440-3441 + `--frost-shadow` + brightness hover/active :3455-3456）；移动位 mobileLayer.css:1901-1906 | ✓ chip 形豁免；稿行号 1939-1944 → 实际 1901-1906；注意底材已是 Header 档（非 F1），稿「材质收编玻璃配方」实际**已达标**，只欠 spring/对齐 |
| M7 | `.mf-cv-close`/`.mf-cv-stack` | mobileLayer.css:1081-1105（容器零高 @1081-1091；48px g2-fab top16+safe @1093-1097；left/right16 @1098-1099；disabled .35 @1101；堆叠态让位 @1103-1105）；创建 js/mobile/navController.js:864/873 | ✓ 稿行号基本吻合 |
| M8 | `.mf-stack-x` | mobileLayer.css:1175-1176（48px）；navController.js:759（`mf-stack-x g2-fab`） | ✓ |
| M9 | `#g2-edge-fade` | liquid-glass.css:225-243（仅 mobile；`--g2-veil` + blur18 sat1.5 双写 :238-239 + mask 双写 :241-242）+ token stand-tokens.css:187-191 + 降级 :245-294 | ✓ 稿行号吻合；**待作者裁**（§5.3-1） |
| M10 | `.g2-chat-head` | liquid-glass.css:334-396（in-flow；头像 :344-372 glass-control 缘；名称胶囊 :374-392 glass-control blur 双写 :386-387）+ mobileLayer.css:2412+ 放大几何；挂载 chatChrome.js:96 | ✓ 沿用项；稿行号吻合 |

## 三、悬浮按钮 · 桌面端 D1-D14（对照稿 §5.2）

| # | 落点 | 现状实测 | 与稿差距 |
|---|---|---|---|
| D1 | `.canvas-source-toggle`（基准件） | split.css:1881-1905（cv 档：尺寸 `--cv-size-circle` 40、双写 :1890-1891、`--cv-shadow` :1892、hover :1901-1904、active :1905）；**F1 覆盖 liquid-glass.css:198-212** → 实测 blur(20px) saturate(2.2)、win=liquid-glass.css | ✓ 稿判「cv 档被 F1 覆盖（liquid-glass.css:200）」实测吻合 |
| D2 | `.canvas-ref-select` | split.css:1912-1950（active sapphire 档 :1938-1942 = 12% 底/35% 边/primary 字；disabled :1943-1950 opacity .35）+ F1 覆盖 | ✓ 选中档保留即全站选中标准 |
| D3 | `.canvas-refresh-toggle` | split.css:1967-2004（busy 旋转 :1990-1995；`[hidden]` !important :1999-2001；reduced-motion :2002-2004）+ F1 覆盖 | ✓ busy 动画保留 |
| D4 | `.canvas-find-toggle` / `.canvas-graph-toggle` | find：split.css:3169-3185（cv 档双写 :3174-3175）+ F1 覆盖；创建 viewers/shared.js:776-793（`addFindToggle`，槽位 right 112） | **图谱钮已退役**：viewers/shared.js:761-765 注记（Canvas 改造批④ 2026-10-31 作者令「去知识图谱钮（无此面板）」），js 零创建，CSS 仅剩 liquid-glass.css:203/218/263/287 **死选择器**。D4 图谱半边应从收编面划掉、顺带删死代码（稿考古未含 10-31 晚这次退役） |
| D5 | `#deck-fab` | split.css:1529-1544（几何 fixed、JS 逐帧 :1531-1532；显隐 :1536；pressed sapphire **14%** :1540-1543；disabled :1544）+ canvas.js:1938-1939（`deck-fab g2-fab`）→ F1 | ✓ 稿「pressed 14% 并入 12%/35%」= 统一后动作，现状 14% 实证 |
| D6 | `#canvas-head-close` | split.css:1552-1560（左缘镜像几何）+ canvas.js:2052-2053（`g2-fab`）→ F1 40px | ✓ |
| D7 | `.canvas-header` / `.cr-capsule` | split.css:701-747（`--glass-bg`+折射线 :722-728 双写 :728-729 + 底缘透明 :721 + `--cv-shadow` :733）；`.cr-capsule` :1393-1476 | 已达标沿用 ✓（稿 703-731 ≈ 漂移 2 行） |
| D8 | `.nf-findbar` / `.canvas-zoom-bar` | zoom：split.css:2790-2806（cv 档双写 :2797-2798）+ 折射线 :2816-2826（zoom/findbar 共用）+ `.canvas-zoom-btn` :2833-2858；findbar：split.css:3196-3230（条 :3196-3216 双写 :3205-3206、钮 :3223-3230） | 已达标沿用 ✓（稿 2788-2856 ≈） |
| D9 | `#header` | chat.css:44-78 基准件 B | 零触碰 ✓ |
| D10 | `#input-bar` | input.css:17-62 原配方 + **v2 改皮覆盖 liquid-glass.css:180-188**（实测 bg=linear-gradient、sat 1.6） | ✓ 稿判；撤销时回归 frozen(:249-258)/disconnected(:980-988)/voice 态 |
| D11 | `.ask-fab` 桌面 | ask.css:34（`--ask-fab-size` 44px）+ :65-89 cv 档 + F1 覆盖 → 实测 44×44 F1 | ✓；44→40 为 §5.3-3 待裁 |
| D12 | 独立窗浮件 | `.pop-media-canvas` chat.css:4807-4825（28px 深底白字 .45/hover .65）；`.fwd-btn` chat.css:4746-4756（30px ghost，hover 绿 tint）；`.pop-stack-area .pill` chat.css:4560-4571（灰 .12/.2 药丸，无玻璃）；lightbox 入口即 `.pop-media-canvas` chip + `.nf-lightbox` :4828+ | 各自为政 ✓ 稿判；**待作者裁是否收编**（§5.3-2） |
| D13 | ghost 家族 | `#header .memory-btn` chat.css:115-143（28px，hover 显玻璃 :130-138）；`#search-btn` :146-174；Pattern A 大名单 sapphire.css:284-322 | 豁免维持 ✓ |
| D14 | wiki 图谱左栏控制面 | frosted 单源消费者（flowMap.css:736/781/930 等玻璃点） | 沿用 ✓ |

---

## 四、非悬浮按钮全落点（任务 1c）

### 4.1 分场景清单

| 场景 | 落点 | 材质现状 |
|---|---|---|
| **发送键族** | `#send-btn` input.css:922-968（rest/hover/active/disabled） | 扁平绿药丸 `--ctl-send-*`（h32 r999 无 blur）——v3 已换代 |
| | `.ask-send` ask.css:228-243 | 同族绿 32px 圆 |
| | `#stop-btn` input.css:992-1013 | **红底 + blur(8px) sat1.3 双写(:997-998) + inset 双高光** —— 非悬浮面里唯一残留玻璃底（v3 扁平口径外的孤儿，建议随批裁定） |
| | `#send-btn.disconnected` input.css:980-988 | 灰 `--glass-send-*`（sapphire.css:446-482）；禁用灰 `--btn-disabled-*` sapphire.css:952-954 |
| **对话框** | 中性/取消 modal.css:162-186（`#modal-cancel`/`#delete-cancel`/`#delete-alt`/`#conflict-*`/`#memory-modal-actions button`/`#rules-modal-actions button`） | sapphire 3% tint ghost + `--glass-border`，无 blur |
| | 主操作 `#memory-modal-save` modal.css:192-206 | 墨族 `--btn-primary-*`（→`--btn-ink-*` 单源） |
| | danger 族 `#modal-confirm`/`#delete-confirm` | 红实底；`confirm-neutral` 变体 modal.css:241-248 glass-control |
| | `#takeover-actions .g2-pill` modal.css:456-458 | **玻璃药丸在对话框内的消费点**（F1 材质族退役时此引用必须换血——`--g2-*` 删除后此处变裸） |
| **设置面板** | Pattern B 大名单 sapphire.css:329-377（`.cfg-btn`（非 primary/add）/`.config-actions button`/`.agent-detail-save-btn`/`.option-btn`/`.neblink-*-btn`/`.epub-nav-btn`/`.code-copy-btn`/`#modal-cancel`/`#delete-cancel`/`#memory-modal-actions button`/`#rules-modal-actions button`） | 扁平 `--ctl-neutral-*`（:341/:361/:377 三态）；`.cfg-btn-add` 虚线幽灵 sidebar.css 自管 |
| | `.cfg-btn` 侧栏实现 sidebar.css:1258-1260、玻璃 hover :1567-1568/2243-2244/2636-2637 | C3 中性扁平 + hover 玻璃控制档 |
| | `.cfg-input` modal.css:593-625、`.cfg-eye-btn` | etched 输入 + ghost 眼 |
| **卡片内/行内** | `.msg-copy` chat.css:1014-1031、`.msg-recall` :1062+、`.option-btn` chips（透明变体 chat.css:2865-2877）、`.scroll-new-pill` :4176-4202、`.notif-item` ~2959-2970、`.ask-pending-row`/`.ask-pending-next` ~3072-3090、`.ws-pick-target` ~3799-3811、`.fwd-btn` :4746-4756、`.file-card` :4719-4729 | ghost/chip/玻璃胶囊混杂——材质各自达标（多为 `--glass-control-*` 或透明 ghost） |
| **空态/文字钮** | `.error-retry-btn`/`.error-abandon-btn` input.css:113-128（文字 chip）、`skip-freeze-btn` 族 | 静音文字 chip（无底无框） |
| **导航/侧栏** | Pattern A 大名单 sapphire.css:284-322（`.panel-btn`/`.activity-avatar`/`.session-delete`/`.folder-delete`/`.explorer-*-btn`/`.canvas-close-btn`/`.reminder-create-btn`/`.cfg-eye-btn`/`.agent-detail-prompt-toggle`/`.project-archive-btn`） | ghost：rest 透明，hover 显 glass-control 玻璃（双写 :314-315） |

### 4.2 token 消费面

- **`--ctl-*`**（定义 stand-tokens.css:294-334；稿写 296-326 ≈）：
  - `--ctl-send-*` 消费：input.css `#send-btn`（:932-957）、ask.css `.ask-send`（:230-241）、sapphire.css/mobileLayer.css 引用点（发送键族上提档）。
  - `--ctl-neutral-*` 消费：sapphire.css:341/361/377（Pattern B 三态）+ sidebar.css:1258-1260。
- **`--btn-ink-*`**：定义**双源**——sapphire.css:922-925（亮档）与 stand-tokens.css:128-153（三态整族）**并存**（加载序 stand-tokens:90 < sapphire:101，sapphire 覆写同名同值档）。双源目前同值，但属于漂移风险点，建议本批顺带归一。消费：`--btn-primary-*` 别名 sapphire.css:929-930 → modal.css `#memory-modal-save`；sidebar.css:2659/2664；perception.css（`.pp-btn` 系）；base.css:6 头注约定。
- 派生：`--g2-ink-*` 引用 `--btn-ink-*`（stand-tokens.css:181-186 等）——`--g2-*` 退役时墨族不受影响。

---

## 五、实测快照要点（probe-report.json，双引擎双主题 4 组合）

1. **F1 覆盖实证**：cv 圆钮族（source/ref/find/refresh）+ `#deck-fab` + `.ask-fab` computed `backdrop-filter = blur(20px) saturate(2.2)`、winning rule = liquid-glass.css —— 稿 D1-D4/D11/M4-M5「被 F1 盖」逐条坐实。
2. **`.g2-fab` 族的玻璃在 `::before`**：元素本体 `backdrop-filter = none`，`::before` 折射层 = blur20 sat2.2（probe 已采集 `beforeBackdropFilter` 字段）——验收断言要量伪元素，量本体永远 none。
3. **`#input-bar` 改皮实证**：bg = linear-gradient、saturate(1.6)、win = liquid-glass.css（D10 对象）。
4. **主气泡零玻璃**：`.bubble.user` = rgb(149,236,105) 实色；`.bubble.ai` = rgb(28,31,39)。
5. **ask 系已达标**：`.ask-bub`/`.ask-input`/`.canvas-refback-btn`/`.canvas-header` 均 blur(24px) saturate(1.15)（win = ask.css/split.css）= Header 档。
6. 尺寸：桌面圆钮族 40×40（`--cv-size-circle`）、ask-fab 桌面 44×44；移动档 48px 走 mobileLayer 覆盖（probe 桌面 fixture 44 实证 + CSS 链核对）。
7. 变量双主题全录（`--g2-fab-bg` .30/.10、`--ctl-send-bg` #07c160 双主题同值、`--btn-ink-bg` #111827/#e6edf3 等）。

## 六、工具链（evidence/20261101_unified-button-glass-impl/tools/）

| 脚本 | 用途 | 自检结果 |
|---|---|---|
| `lib.cjs` | 共享打桩（磁盘真文件 route + MockWebSocket 生产 boot 链 + 主题双落位 + console/pageerror 收集，/api 404 单列） | — |
| `shot.cjs` | 自包含截图：target(chat/canvas/mobile/dialog) × viewport(1280×844/390×844) × engine(chromium/webkit) × theme(dark/light) → PNG + 逐张 console error/pageerror | **10 张实拍全 0 报错**（chat×4 + canvas/mobile/dialog×webkit+chromium）；mobile 形态 dock 钮/会话头/渐隐带在拍，dialog 真开设置面板 |
| `probe.cjs` | 计算样式断言：17 个生产类名 fixture + 4 个真实节点，采集 bd/-webkit-bd/::before 层/winning rule stylesheet href + 25 个关键变量 → JSON | 双引擎双主题 4 组合全 0 报错 |
| `scrollfps.cjs` | rAF 滚动采样 → avgFps/掉帧/p95/max（120 气泡长列表） | chromium 46.7fps（掉帧 49/117）· webkit 56.1fps（2/141）——基线已录 |
| `cssaudit.cjs` | CSS 机械清点（backdrop 清单/双写判定/var 审计/类名清单） | 246 处声明、双写 100% |

用法一行示例：
`NODE_PATH=/opt/homebrew/lib/node_modules node evidence/20261101_unified-button-glass-impl/tools/shot.cjs --target chat --engine both --theme both`

工程注意（WebKit 实测坑）：**WebKit 导航 `localhost:1` 挂死**（特权端口），harness 已改用假源 `http://glassshot.local/`（route 拦截全接管，零端口）；主题切换 = `html[data-theme]` 显式档 + `page.emulateMedia` 跟随档双落位（ask.css:52-60 三态结构即站内机制）。

## 七、checkJs 基线（baseline/）

- `checkjs-baseline.txt`：`checkJs: 362 errors (baseline 345, zero-gate files: 3)`，exit 1。门失败明细：**ws.js 6 errors**（P2-3 核心契约要求零错）、**modelPanel.js 10 errors**（新文件）、main.js TS2339 19>13、scheduled-task.js 9>8、总 356>345。
- `checkjs-raw-tsc.txt`：tsc 原始输出 372 行（全错误清单）。
- 提示：实施批若触碰以上门文件需先清账；按钮/玻璃批主要改 CSS，撞门概率低，但 main.js 常被牵动。

## 八、移交清单（给实施批）

1. 收编面改动锚点全部在 §二/§三表内（行号本树实测）；F1 撤覆盖的单点 = liquid-glass.css:198-221（④ 块整块退役）+ `.g2-fab/.g2-pill/.mh-chrome-btn` 基材块 :33-101 + 移动档 :311-325。
2. `--g2-*` 删除牵连的非按钮消费点：`#slash-dropdown/#mention-dropdown`（input.css:1093-1121）、`.ask-veil`（ask.css:152-161）、`#takeover-actions .g2-pill`（modal.css:456-458）——三处需换 `--cv-*`/`--ctl-*`，漏一处即裸样式。
3. `#stop-btn` 的 blur8 玻璃底（input.css:992-1013）：非悬浮面孤儿玻璃，建议随批裁定扁平化。
4. `.canvas-graph-toggle` 死选择器（liquid-glass.css:203/218/263/287）：随 ④ 块退役自然消失，无需单独处理；但收编清单 D4 应按半项（仅 find）执行。
5. `--btn-ink-*` 双定义源（sapphire.css:922+ 与 stand-tokens.css:128-153）建议归一。
6. 稿 §5.3 三项待作者裁（M9 渐隐带 / D12 独立窗 / ask-fab 44→40），本清点已备齐实测数据。
7. 验收断言直接复用 probe.cjs（稿 §7 七条二值点已可脚本化：bd 值、直径、选中档、disabled、no-backdrop、input-bar 无渐变、reduced-motion）。
