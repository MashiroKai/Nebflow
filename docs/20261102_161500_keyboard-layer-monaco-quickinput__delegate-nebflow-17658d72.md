---
type: fix-report
status: final
---

# 键盘层按「窄路」改造：Monaco QuickInput 换壳 · 键位规则化 · 裸监听收编（2026-11-02）

- 前置设计：[[20261102_132051_replace-nebflow-ui-with-vscode__delegate-nebflow-5313652f]]（结论：路线 **B′** —— 只换命令面板内核 + 键位解析 + 模态自写，不搬 workbench 外壳）
- 相关：[[production-ops-checklist.zh-CN]]、[[parallel-session-resources.zh-CN]]
- 实施分支 / 工作树：`delegate/delegate-nebflow-17658d72` @ `.nebflow/worktrees/delegate-nebflow-17658d72`
- 只读证据：本文件所有读数都是本机实跑的输出（命令列在 §5），没有估算值。

---

## 0 TL;DR

| 阶段 | 状态 | 关键产物 | 验收读数 |
|------|------|----------|----------|
| 1 命令面板换 Monaco 自带 | **完成** | `js/monacoQuickInput.js`（QuickInput 桥）+ `js/commandPalette.js` 换壳（命令注册表保留） | 能力对照表 §1.3；键盘断言 B 组 15 条全绿 |
| 2 键位规则化 + when 闸门 | **完成** | `js/keyContext.js`（上下文键 + `when` 求值器）+ `js/keyRules.js`（**24 条规则，每条必带 when**）+ `keymap.js` `when` 支持 | 规则表 §2.2；「EDIT 不穿透」实测 9 条 + 1 条规则级 §2.3；与 Monaco `ContextKeyExpr` 真值表 624 格逐格同值 |
| 3 收编裸监听 | **完成 2 个模块（本批约定 2–3）** | `canvas.js` 裸 keydown **4 → 0**；`explorer.js` 文档级 **2 → 0**（树导航 + Esc 清选择） | 剩余 55 个模块 / 113 处清单 §3.2（模块 + 处数 + 难度） |

三阶段都落在同一条分支，分三次提交（拆分与顺序约束见 §7）。

---

## 1 阶段 1：命令面板换 Monaco 自带

### 1.1 落地形态

- `js/monacoQuickInput.js`（新，约 230 行）：起 vendored Monaco（`vendor/monaco`，0.52.2 AMD 单包）→ 取 `QuickInputController` → 自建 layout 适配器 + 空 styles（**56 个样式键全给空串 ⇒ 不写内联样式 ⇒ CSS 是唯一外观真相**）→ 返回 controller 供 `createQuickPick()`。
- `js/commandPalette.js`（改）：命令注册表（`registerCommand` / `runCommand` / `listCommands`）与 `prettyCombo` **原样保留**；UI 面从手写 overlay（332 行 DOM + 高亮 + 键盘）换成 Monaco QuickPick。
- `css/keyboard.css`：删掉 `.nf-palette-*` 全部皮肤，新写 `#nf-quickinput-host` 的九组子选择器（widget / header / input / list / row / separator / keybinding / message）。

### 1.2 为什么不是 `quickInputService`（与任务原文的偏差，附实证）

任务写的是「用 Monaco 的 `quickInputService`」。实测**不可用**，证据：

1. standalone 版的 `IQuickInputService` 由 `StandaloneServices` 提供，其实现要求**有一个聚焦的编辑器**：不满足时直接抛 `Quick input service needs a focused editor to work.`（本机实测原文）；
2. 本产品的 `window.monaco` 是**懒加载**的（`monacoEditor.js` 只在打开文件时 require `vs/editor/editor.main`），首屏没有编辑器实例 —— 命令面板是首屏能力，等编辑器就等于「面板在打开文件前不能用」；
3. 另有一条硬约束：AMD loader 必须先 `require(['vs/editor/editor.main'])`，否则 `vs/platform/*` 子模块会去 fetch 一个不存在的 `.js` → 404（探针实测）。

结论：用 **`QuickInputController`**（workbench 里 `QuickInputService` 自己也是包它），手工注入 4 个构造参数（options / layoutService / instantiation / contextKeyService）。语义上仍是「Monaco 自带的命令面板内核」，只是绕开了 standalone 服务层那条「必须有聚焦编辑器」的入口。**这是对任务原文的明确偏差，未按原字面执行。**

### 1.3 能力对照表（旧 332 行手写面板 ↔ 新内核）

| # | 旧面板能力 | 新实现 | 断言（keyboard-check B 组） |
|---|-----------|--------|------------------------------|
| 1 | 命令注册表 `registerCommand/runCommand/listCommands` | 原样保留（未改一行） | 全组隐式覆盖（面板跑的就是注册表） |
| 2 | 命令行 + 只读直键行，按当前模式过滤 | 同口径（`collectRows`） | 「只读直键行仍列出（含键位提示）」 |
| 3 | 搜索：`label + group + id + keyHint` 子串，命中位置越前越靠前，同分保持原序 | **逐字同口径**（`score` + `applyFilter`） | 「输入即搜」「**分组名可搜**」「**键位提示可搜**」 |
| 4 | 分组标题 | Monaco 分隔行：组名（行右小字）+ 细分隔线 | 「分组标识 ≥ 3 组」 |
| 5 | ↑↓ 移动高亮 | Monaco 列表自身导航 | 「Enter 执行命令」+ 面板实测 |
| 6 | Enter 执行命令（先关面板再执行） | 同（`closePalette()` 后 `setTimeout(…,0)` 执行，避免命令自己开的层被抢回焦） | 「Enter 执行命令（打开了设置面）」「执行后面板关闭」 |
| 7 | 禁用行 / 只读行不可执行 | 同（`acceptItem` 守卫：`!row || row.disabled || !row.cmd` 直接 return） | 「占位行按 Enter 不执行、不关面板」 |
| 8 | 无命中提示「没有匹配的命令」 | 列表内一条占位行 | 「无命中给出占位行」 |
| 9 | Esc 关闭 + 回焦到打开前元素 | 同（组件自己记 opener 并归还） | 「Esc 关闭面板」「关闭后回焦到打开前元素」 |
| 10 | a11y：`role=combobox` + `aria-activedescendant` 指高亮项 + `role=listbox` | Monaco 自带同类语义（实测：`role=combobox`/`aria-expanded=true`/`aria-controls=nfpallist`/`aria-activedescendant=list_id_1_0`/`role=option` + 每行 `aria-label`） | 「打开后焦点在搜索框」+ §5.4 读数 |
| 11 | 键位提示列（`prettyCombo`） | 行内 `description`（命令名右侧） | 「行内显示键位提示（combo 列）」 |

**能力一处没少**（11/11 有对位实现且有断言）；**形态有 5 处差异**，逐条记账：

| 差异 | 事实 | 为什么接受 |
|------|------|-----------|
| D1 内核入口 | `QuickInputService` → `QuickInputController` | §1.2，standalone 服务层要聚焦编辑器；不做就用不了 |
| D2 匹配器 | **不用** Monaco 自带匹配，改由我们按旧口径筛（`pick.filterValue = () => ''` 恒空 + `applyFilter` 赋 `items`） | Monaco 的 `QuickInputList` 只拿 `label`/`description` 匹配（`matchOnLabel`/`matchOnDescription` 是布尔开关，无可插拔谓词），**分组名不在其中** ⇒ 交给它筛，「敲『布局』筛出整组」这条旧能力直接没了。实测：交它筛 `布局`=0 行；接管后=3 行 |
| D3 分组标题位置 | 旧：左对齐组标题行；新：组名在**该组首行右侧**（Monaco separator 的渲染位） | 要改位置得改 Monaco 组件的渲染，等于重写它的列表；语义（每组有可见标识）未变 |
| D4 只读行样式 | Monaco 只把 `item.disabled` 用于 checkbox 态，**不做视觉灰化** | 区分靠「快捷键（直键）」分组 + 无执行回调；不伪造 DOM 以免和组件内部契约打架 |
| D5 占位行可选中 | 「没有匹配的命令」是列表行 ⇒ ↑↓ 会停到它 | Enter 不执行（断言覆盖）；要「不可选中」只能自造 widget，收益不抵风险 |

---

## 2 阶段 2：键位规则化（when 上下文键）

### 2.1 机制

- `js/keyContext.js`（新）：上下文键仓库（`values` + **派生** `providers` + 变更订阅）、`when` 表达式求值器（语法与语义对齐 Monaco `ContextKeyExpr`：`!` `&&` `||` `==` `!=` `=~` 括号、bareword 真值、未定义键语义）。
- `js/keyRules.js`（新）：**唯一规则表**。每条规则 `{ id, key, aliases?, when(必填), group, description, run, observe? }`。handler 只做动作，判断全在 `when`。
- `js/keymap.js`（改）：注册项支持 `when`；`test(e)` = 模式 ⊕ `when` ⊕ 输入态 一条合成谓词；`setMode()` 同步写上下文键 `keyboardMode`；冲突检测改成「mode 相交 **且** when 相同或一方为空」。
- `js/keyboardBoot.js`（改）：删掉 8 条手写 `registerShortcut(...)` + capture 预读，改成 `installFocusContextKeys(); installKeyRules();`。
- **模态已挂到上下文键**：`mode.js` 的 NAV/EDIT 语义 = `keyboardMode == meta|edit` 这两条表达式；`setMode('edit')` → `getContextKey('keyboardMode') === 'edit'` 有断言（J 组）。

### 2.2 规则表（24 条，每条带非空 when）

| 组 | id | 键 | when |
|----|----|----|------|
| 键盘 | `palette.toggle` | `Mod+K` | `true` |
| 键盘 | `palette.help` | `Mod+/` | `true` |
| 导航 | `nav.center.prev` / `.next` | `[` / `]`（物理键 `@Bracket*`） | `keyboardMode == meta` |
| 导航 | `nav.sidebar.prev` / `.next` | `Shift+[` / `Shift+]` | `keyboardMode == meta` |
| 模态 | `mode.enterEdit` | `Space` | `keyboardMode == meta && !activatableFocus && !textInputFocus` |
| 模态 | `mode.sendToMeta` | `Enter`（observe） | `keyboardMode == edit && textInputFocus` |
| 文件树 | `explorer.tree.down` / `.up` / `.home` / `.end` / `.expand` / `.collapse` / `.activate` | `↓ ↑ Home End → ← Enter` | `explorerTreeFocus` |
| 文件树 | `explorer.selection.clear` | `Escape` | `explorerSelection && !textInputFocus && !deckOpen` |
| 叠卡 | `deck.card.activate` | `Enter`（别名 `Space`） | `deckOpen && deckCardFocus` |
| 叠卡 | `deck.card.close` | `Delete` | `deckOpen && deckCardFocus` |
| 叠卡 | `deck.next` / `deck.prev` | `↓ → PageDown` / `↑ ← PageUp` | `deckOpen && !textInputFocus && !deckCardFocus && !explorerTreeFocus` |
| 叠卡 | `deck.select` | `Enter` | 同上 |
| 叠卡 | `deck.close` | `Escape` | `deckOpen && !textInputFocus` |
| 画布 | `canvas.ring.close` | `Escape` | `canvasRingOpen && !textInputFocus` |
| 画布 | `canvas.mergeSelf` | `Escape` | `cardWindow && !textInputFocus` |

**条数说明**：任务目标区间 10–20，实测 **24**（22 条业务 + 2 条键盘层）。多出的原因是文件树 7 条与叠卡 6 条各自是**独立动作**（把 `Home/End/←/→` 合并成一条只会变成 switch 分支，那正是本批要消灭的形态）。若必须压进区间，可把 `deck.next`/`deck.prev` 与 `nav.*` 各并成一条「方向类」规则，但不建议。

### 2.3 「EDIT 模式下哪些键不再穿透」（逐条实测，K 组）

EDIT 的定义沿用一期不变式：**焦点在对话输入框 ⇔ EDIT**（`mode.js` 的 focus/blur → `setMode`）。下表每条都是「输入框聚焦按一下 → 什么都没发生」+「失焦按一下 → 照常发生」的成对取证（只测前者会让「功能被删掉」看起来也像通过）。

| # | 键 | 改造前（裸监听） | 现在（规则 when 闸住） | 断言 |
|---|----|------------------|------------------------|------|
| 1 | `Esc` | 清文件树选择（文档级 keydown） | **不穿透**；失焦后才清 | K① / K② |
| 2 | `]` | 把焦点搬去画布/消息流（中心环） | **不穿透**（`keyboardMode == meta`）；失焦后照常 | K③（+G⑩ 正向） |
| 3 | `↓` / `→` / `PageDown` | 擦洗叠卡（文档级 keydown） | **不穿透**（位形一毫米不动）；失焦后照常擦洗 | K④ / K⑥ |
| 4 | `Esc` | 关叠卡 | **不穿透**；失焦后照常关 | K⑤ / K⑦ |
| 5 | `Esc` | 收胶囊环（**无条件**文档级） | **不穿透**（补了 `!textInputFocus`）；失焦后照常收 | K③b / K③c |
| 6 | `Esc` | 卡片窗合并回主窗（**无条件**文档级） | **不穿透**（同补 `!textInputFocus`） | 规则级（卡窗需独立窗口，未在单页装置实测，见 §6） |
| 7 | `Home` / `End` / `←` / `→` / `↑` | 文件树移动/展开（树容器 keydown） | **不穿透**（`explorerTreeFocus` 在输入框聚焦时为假） | F③/F④ 正向 + J「每行 when 非空」 |
| 8 | `Enter` | 树激活 / 叠卡选中 / 翻卡 | **不穿透**（各自 when 都排除 `textInputFocus`）；Enter 在 EDIT 里只归「发送」 | K③（焦点不搬）+ J |
| 9 | `Delete` | 关聚焦卡 | **不穿透**（`deckCardFocus` 为假） | J |
| 10 | `Space` | 进入 edit（meta 语义） | **不穿透**（`... && !textInputFocus`），在输入框里就是打空格 | 规则表 + J |

**证据强度差别**：#1–#5 是 DOM 事实实测（选中数、卡片几何、环的 `ring-open` 类）；#7–#10 是「规则 when 的构造 + 焦点类派生键」+ 正向能力仍在（F 组）；#6 只到规则级（见 §6）。

### 2.4 `when` 求值器 ≡ Monaco `ContextKeyExpr`（L 组，624 格）

为什么不直接引 Monaco 的 `ContextKeyExpr`：它属于 `vs/platform/*`，必须先 require `vs/editor/editor.main` 才有（§1.2 的懒加载契约）。所以本地实现了同语义求值器，并把**等价性当断言**：24 条规则的真实 `when` × 26 个上下文状态（全假 / 10 个键各单点置真（edit 态）/ 10 个键各单点置真（meta 态）/ 5 个混合）= **624 格**，本地求值与 `ContextKeyExpr.deserialize(when).evaluate({getValue})` 逐格比对，**0 处不一致**。差一格就说明我们发明了一套方言。

---

## 3 阶段 3：收编裸监听（按模块分批）

### 3.1 本批收编（2 个模块，符合「至少 2–3 个」）

| 模块 | 改造前 | 改造后 | 做法 |
|------|--------|--------|------|
| `js/canvas.js` | 4 处裸 keydown（叠卡层 keydown、文档级 deck keydown、胶囊环 Esc、卡片窗 Esc） | **0** | 新增 `export deckKeyAction(action, e)`（switch 9 个动作）+ 三条**派生**上下文键 `deckOpen` / `canvasRingOpen` / `cardWindow`；4 条监听全删，规则表转发 |
| `js/explorer.js` | 5 处（树导航 `onTreeKeydown`、文档级 Esc 清选择、typeahead、2 个行内输入框） | 3 处（typeahead + 2 个行内输入框） | `onTreeKeydown(e)` → `export treeKeyAction(action)`（用 `document.activeElement` 而非 `e.target`）；新增 `clearTreeSelection()`；派生键 `explorerSelection` |

**全仓账本**（注释剥离口径：只数真实 `addEventListener('keydown')` / `onkeydown=`，剔除 `js/keymap.js`、`js/keyRules.js`、`js/keyContext.js` 三个基础设施文件）：

- 改造前 **119** 处 / 改造后 **113** 处（−6 = canvas −4 + explorer −2）；
- 含注释行的 grep 口径：121 → 117。

**已知例外（本批不收编，理由）**：`explorer.js` 的 **typeahead**（可打印字符 → 开搜索条）仍是树容器上的裸 `keydown`。规则表是「combo → 动作」，而「任意可打印字符」不是 combo；硬塞进去会变成一条通配规则，把所有单字符键都抢走。它留在模块里，条件（过滤态已开则让位）保持原样。

### 3.2 剩余模块清单（55 个模块 / 113 处，难度 = 迁移到规则表的预估）

难度口径：**低** = 局部小面板，键位独立、无跨模块状态；**中** = 有输入态/焦点陷阱/模态壳交互；**高** = 消息流或协作面，键位与 IME、渲染委托、输入法状态交织。

| 模块 | 处数 | 难度 | 备注 |
|------|------|------|------|
| `js/messages.js` | 10 | 高 | 消息流渲染 + IME + 编辑态，多为委托型监听 |
| `js/contacts.js` | 9 | 高 | 列表导航 / 输入框 / 详情三段 |
| `js/main.js` | 6 | 中 | 装配期全局键（要先分类哪些该进规则表） |
| `js/sidebar.js` | 5 | 中 | 侧栏条目导航（与 `nav.sidebar.*` 有潜在重叠，需先合并语义） |
| `js/scheduled-task.js` | 4 | 中 | 面板内表单 + 列表 |
| `js/viewers/readerChrome.js` | 4 | 中 | 阅读器壳（分页/缩放），与 lightbox 有联合键序 |
| `js/workspacePicker.js` | 4 | 中 | 模态壳（层栈 + 回焦，需复用 focusTrap 契约） |
| `js/explorer.js` | 3 | 低 | **本批剩余** = typeahead + 2 个行内输入框（后者是输入态本地键，可能永久留在模块里） |
| `js/friendGroups.js` | 3 | 低 | 小面板 |
| `js/modal.js` | 3 | 中 | 通用确认框，与 `focusTrap.js` 耦合 |
| `js/modelPanel.js` | 3 | 低 | 小面板 |
| `js/viewers/html.js` | 3 | 低 | 预览器 |
| `js/askDock.js` / `chatSearch.js` / `dropbox.js` / `fileSearch.js` / `flowMapArchive.js` / `input.js` / `mobile/socialSearch.js` / `onboarding.js` / `plugins.js` / `projectTab.js` / `secretsPanel.js` / `usageDashboard.js` / `viewers/largeText.js` | 各 2（共 26） | 低（`input.js` 中） | 单个面板/输入态的本地键；`input.js` 是发送协议的一部分，改名归「中」 |
| 其余 30 个模块（`activityBar` `agentProfile` `askPending` `bgAgentWorkStrip` `bgTaskOutputPopup` `chat/collapsible` `chat/injectedRows` `chat.js` `configDrill` `contextMenu` `daemonPanel` `delegatePanoramaView` `diffCanvas` `diffPanel` `focusTrap` `imeGuard` `lightbox` `main/bgAgents` `mobile/navController` `monacoEditor` `msgQuote` `perception` `popArtifacts` `socialPanel` `timeFormat` `turnGroup` `viewers/image` `viewers/zoom` `voiceMode` `wikiGraph`） | 各 1（共 30） | 低 | 单处监听，多是「面板内 Esc / 输入框 Enter」 |

合计：**55 个模块 / 113 处**（与 §5.3 的实跑读数一致）。

---

## 4 新增/改动文件

| 文件 | 性质 |
|------|------|
| `src/main/resources/web/js/monacoQuickInput.js` | 新（Monaco QuickInput 桥，约 230 行） |
| `src/main/resources/web/js/keyContext.js` | 新（上下文键 + `when` 求值器，约 370 行） |
| `src/main/resources/web/js/keyRules.js` | 新（规则表，24 条，约 260 行） |
| `src/main/resources/web/js/commandPalette.js` | 改（注册表保留，UI 换壳） |
| `src/main/resources/web/js/keymap.js` | 改（`when` 支持 + 模式写上下文键 + 冲突检测口径） |
| `src/main/resources/web/js/keyboardBoot.js` | 改（8 条手写注册 → 装表） |
| `src/main/resources/web/js/canvas.js` | 改（+`deckKeyAction`，−4 处裸监听，+3 派生键） |
| `src/main/resources/web/js/explorer.js` | 改（+`treeKeyAction`/`clearTreeSelection`，−2 处裸监听，+1 派生键） |
| `src/main/resources/web/js/probeSurface.js` | 改（登记 3 个新模块句柄，供两形态断言取同一份实例） |
| `src/main/resources/web/css/keyboard.css` | 改（`.nf-palette-*` 全删 → `#nf-quickinput-host` 皮肤） |
| `scripts/keyboard-check.mjs` | 改（B 组重写 7→15；新增 J/K/L/M 共 19 条） |

---

## 5 基线读数（真实输出）

### 5.1 键盘断言

```
# 源树形态（src/main/resources/web）
keyboard-check: 109 passed, 0 failed, 0 skipped — 共 109 条
KEYBOARD CHECK PASS

# 产物形态（build/web-dist，先 npm run build）
keyboard-check: 109 passed, 0 failed, 0 skipped — 共 109 条
  形态：产物（…/build/web-dist）—— 断言直接跑在打包产物上
KEYBOARD CHECK PASS
```

基线 82 → 现在 109 的账：**75 条原样通过** + 旧面板 7 条（断言的是 `#nf-palette-overlay` / `#nf-palette-input` / `.nf-palette-row` 这套**已被本批退役的 DOM**）**逐条替换成 15 条新面板断言**（覆盖并超出原 7 条的能力面，见 §1.3）+ 新增 J(5) / K(10) / L(1) / M(3)。**没有一条断言被删掉不补、或被放宽阈值。**

### 5.2 视觉基线

```
VISUAL BASELINE PASS      # 28/28 SAME，DIFF 0，MISSING 0
```

### 5.3 类型闸 / 构建 / 裸监听账本

```
checkJs: 0 errors (baseline 0, zero-gate files: 3)
checkJs PASS: no new errors above baseline.

build-web OK -> build\web-dist/
  js entry : assets/app-Q37VFCBY.js
  chunks   : 232 lazy chunks
  css      : assets/app-5b9c933a.css (36 files concatenated in source link order)
  js files : 213 sources (.js/.mjs/.cjs/.ts/.tsx/.vue), all in bundle graph (orphans: 0, exemptions: 5)
  reverse  : 236 registered outputs, 0 unregistered js under assets/

# 裸 keydown（注释剥离 + 剔除三个基础设施文件）
files with bare keydown: 55   total: 113        # 改造前 119
canvas.js: 0                                    # 改造前 4
explorer.js: 3（1 typeahead + 2 行内输入框）      # 改造前 5（含 1 文档级）
```

### 5.4 新面板 a11y 读数（实测 DOM 属性）

```
input : role=combobox  aria-expanded=true  aria-controls=nfpallist  aria-autocomplete=list  aria-activedescendant=list_id_1_0
list  : role=listbox   aria-label="输入命令名…（↑↓ 选择 · Enter 执行 · Esc 关闭）"
row   : role=option    aria-label="切到文件面板, 面板"
geometry: 600×443，居中（视口 1440 → 中心 x=720）
```

可视化证据：`build/palette-final.png`（未入库，见 §6 说明）。

---

## 6 已证实 / 未验证分界

**已证实（有断言、两形态都跑过）**
1. 新面板的 11 项能力对位（§1.3），含「分组名可搜」「键位提示可搜」这两条**旧面板独有**能力；
2. `when` 求值器与 Monaco `ContextKeyExpr` 624 格逐格同值；
3. EDIT 下 #1–#5 键不穿透（DOM 事实）+ 对应正向能力仍在；
4. 规则表 24 条、每条 when 非空且可编译、模式真挂在 `keyboardMode` 上；
5. `canvas.js` 裸 keydown 归零、`explorer.js` 文档级归零、业务面总数下降；
6. 视觉 28/28、checkJs 0 错、构建 0 孤儿、键盘断言两形态各 109/109。

**未验证 / 有保留**
1. `canvas.mergeSelf`（卡窗 Esc）只到**规则级**：卡窗是 `?card-window=1` 独立窗口，需要 window.open 级装置，本批装置（单页 + MockWS）测不到；`!textInputFocus` 的实际效果未实测。
2. **真实实例**里的手感未验：本批所有读数都在离线装置（`page.route` 磁盘服务 + MockWS，零端口零进程），未在 6138 实例上点过（红线：不许碰）。
3. IME 组字态与新面板的交互未单独实测（面板输入框是普通 `<input>`，走 Monaco 的 `onDidChangeValue`；`imeGuard` 只在业务键侧）。
4. 面板虚拟列表**只渲染可视行**：断言里的「>= 12 行」是可视行数，不是行总数（37 行 = 23 项 + 14 分隔，取自 `aria-setsize`）。
5. 占位行仍可被 ↑↓ 选中（D5），只保证 Enter 不执行。
6. 知识的**自动校验器未跑**：`check_wiki_docs.py` 需要 python3，本机 `python3` 是 Microsoft Store 别名（不可用）⇒ 本文件按 Obsidian 规范手写（frontmatter `type`/`status` + wikilink），未经脚本校验。
7. 中间提交不单独可跑绿：见 §7。

---

## 7 提交与顺序约束

三阶段分三次提交（`scripts/keyboard-check.mjs` 的 B 组件随阶段 1 落库，J/K/L/M 随阶段 3 落库）：

| 提交 | 内容 | 单独可跑绿？ |
|------|------|--------------|
| ① 阶段 1 | `monacoQuickInput.js` + `commandPalette.js` + `keyContext.js`（面板要的上下文键基座）+ `keyboard.css` + `probeSurface` 登记 + 键盘断言 B 组重写 | 是（旧键盘层 + 新面板） |
| ② 阶段 2 | `keyRules.js` + `keymap.js` + `keyboardBoot.js` | **否**：规则表 import 了阶段 3 才有的 `canvas.deckKeyAction` / `explorer.treeKeyAction`（前向依赖），boot 会在 import 阶段失败 |
| ③ 阶段 3 | `canvas.js` + `explorer.js` + 键盘断言 J/K/L/M | 是（**验收点 = HEAD**） |

结论：**绿在 HEAD**；② 是文件面拆分而非可交付点，读代码时请按 ①→②→③ 顺序读。

---

## 8 复现命令

```bash
# 键盘闸（源树 / 产物两形态）
NEBFLOW_PW_PATH=<playwright-core> node scripts/keyboard-check.mjs
node scripts/build-web.mjs
NEBFLOW_PW_PATH=<playwright-core> node scripts/keyboard-check.mjs --web-root build/web-dist
# 视觉闸 / 类型闸
NEBFLOW_PW_PATH=<playwright-core> node scripts/visual-baseline.mjs --check
node scripts/check-js-types.mjs
# 裸监听账本（注释剥离口径，剔除三个基础设施文件）
node -e "…见 §5.3 脚本"   # 逐文件计数，输出 55 files / 113 total
```
