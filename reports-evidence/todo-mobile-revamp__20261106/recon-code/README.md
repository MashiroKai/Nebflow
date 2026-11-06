# 静态核查：`#` 待办引用 / 手机端待办面板 / 搜索条→胶囊形变原型

只读取证，未改任何 `src/` 文件。工作树 = `.nebflow/worktrees/todo-mobile-revamp`，分支 `delegate/todo-mobile-revamp`，tip `fa38813ea`。
行号均为该 tip 上的实测行号（绝对路径根 = `src/main/resources/web/`，下文省略前缀）。

机械门禁实跑（本次，只读）：`node scripts/check-todo-mention.mjs` → GREEN 78/78；`node scripts/check-mention-trigger.mjs` → GREEN 9/9。

---

## 一、`#` 引待办：链路已全通（实现存在，逐环可查）

### 1.1 结论先行

- **能从输入框用 `#` 唤起待办选择：是。** 单引擎（`tokenComplete.js`）双 provider，`#` 是 `todoComplete.js` 注册的第二个 provider，与 `@` 共用弹层/分词/键盘/插入。
- **选中后输入框里是纯文本 `#名字 `（无 chip）；发送后主会话两腿都渲染成 `.todo-tag` chip**（`<span class="todo-tag" data-todo data-todo-id data-todo-detail role="button" tabindex="0">#名字</span>`）。
- **边界**：`#` + 数字（任务号）、`#` + 3/4/6/8 位十六进制（色值）、**名字后紧接汉字/字母/数字/下划线**、代码围栏/行内代码内 —— 一律不识别（原样显示，不吞字）。
- **缺口（已核实，三条）**：① chip 有 `tabindex="0"` + `role="button"` 但**只绑 `click`、无 Enter/Space 键处理**；② chip 点击依赖 `.tfl-row[data-id]` 在 DOM 里，手机任务页未打开前必然是 toast 兜底；③ 用户气泡腿与 markdown 腿的**起始门不同源**（空白门 vs 非 ASCII 字母数字门），同一段文本在两种面里识别结果可不同。

### 1.2 ① 输入侧：触发门 + 补全（谁把它接进输入框）

- `js/todoComplete.js:53-65` — `registerProvider({ id:'todo', trigger:'#', codeGate:true(:58), blockDigitStart:true(:59), blockHexColor:true(:60), load(:61), rank(:62), insertText: item => '#'+item.name (:64) })`。三道额外门禁与 `@` 的差异写在文件头 `js/todoComplete.js:9-17`。
- `js/todoComplete.js:39-51` — `loadTodos()`：数据面 = `todoStore.openItems()`，再滤 `!completed && name`；`subtitle` = detail 首行 60 字（`:46`），`createdAt` 作同级 tie-break（`:48`）。
- `js/tokenComplete.js:64-72` — `registerProvider`：同一 trigger 只允许一个（后注册覆盖并告警）。
- `js/tokenComplete.js:100-116` — `parseToken`：起始门 `/[a-zA-Z0-9]/.test(prev)` 不触发（`:108`，`C#`/`a#1`/`foo@` 拦掉）；token 到首个空白为止（`:111`）；`codeGate`（`:112`）；`blockDigitStart` `/^\d/`（`:113`）；`blockHexColor`（`:114`）。
- `js/tokenComplete.js:82-83` — `HEX_COLOR_RE = /^[0-9a-f]{3}$|^[0-9a-f]{4}$|^[0-9a-f]{6}$|^[0-9a-f]{8}$/i`（3/4/6/8 位）。
- `js/tokenComplete.js:131-143` — `isInsideCode`：围栏奇偶 + 行内反引号奇偶 + 光标行本身是围栏行；自报未覆盖缩进代码块/转义反引号（`:125-126`）。
- `js/tokenComplete.js:355-378` — `updateTokenDropdown`：先 `activeSpec` 取最近触发符 → `parseToken` → `fetchSeq` 收敛异步竞态 → `renderDropdown`。
- `js/tokenComplete.js:385-395` — `activeSpec`：取光标前**最靠后**的触发符，`@`/`#` 由位置决出，不是硬编码。
- `js/tokenComplete.js:161-193` — `rankCandidates`（档位 6/5/4/3/2/1，不变式「拼音永不压过字面前缀」）。
- `js/tokenComplete.js:208-219` — 弹层节点 `#mention-dropdown`（`role=listbox`）挂进 `#input-area`。
- `js/tokenComplete.js:251-280` — 行 DOM `.mention-item`（名称 `.mention-name` + 副标题 `.mention-path`），选中走 **`mousedown` + preventDefault**（`:269-272`，保 composer 焦点）。
- `js/tokenComplete.js:402-427` — `pickToken`：`insertText(item) + ' '` 整段替换 token 区间（`:414-415`），重设光标与高度，重派 `input` 事件让 input.js 既有监听重同步（`:419-423`）。
- `js/tokenComplete.js:441-466` — `handleTokenKeydown`：↑↓ 导航、Tab/裸 Enter 插入、Esc 关闭；**零命中时不消费键**（`:435` 注释 + `:455` 的 `items.length > 0` 条件）。
- `js/tokenComplete.js:474-484` — `bindTokenComplete(view)`：`input`（IME 组字中跳过）+ `blur` 两个监听，按元素幂等（`__tokenCompleteBound`）。
- `js/tokenComplete.js:488-493` / `:498-502` — 模块级 document 委托：外点关闭 + `selectionchange` 重判活。
- **接进输入框的三处（input.js）**：`js/input.js:50` 引入 `bindTokenComplete/handleTokenKeydown/closeTokenDropdown`；`js/input.js:51` `import './todoComplete.js'`（注册 `#` provider）；`js/input.js:2213` `bindTokenComplete(view)`（initInput 内，单 composer）；`js/input.js:1780` 键级联**第一顺位** `handleTokenKeydown(e, view)`；`js/input.js:216` `send()` 起手 `closeTokenDropdown()` 防弹层悬空。
- 双入口重复 import 无副作用：`js/main.js:51` 与 `js/input.js:51` 都 import `todoComplete.js`，ESM 单实例 ⇒ `registerProvider` 只跑一次。

### 1.3 ② 发送后主会话渲染侧

- 数据面注入：`js/main.js:379-388` — `let todoRefGen`（`:379`）→ `setTodoListProvider(() => ({ todos: todoStore.openItems(), gen: todoRefGen }))`（`:381`）→ `todoStore.subscribe` 自增代次（`:383`）并在**首次到达**跑一次 `enhanceMentionSpansInDom()`（`:384-387`）。
- `js/utils.js:405-407` — `setTodoListProvider`（唯一数据路径，utils 不 import 待办 store）。
- `js/utils.js:413-420` — `todoListState()`：provider 缺席/抛错 ⇒ 空清单（清单未到 ⇒ 引用按纯文本显示）。
- `js/utils.js:423-426` — 形状门禁三条正则：`TODO_DIGIT_HEAD_RE`、`TODO_HEX_SHAPE_RE`、`TODO_WORD_CONT_RE = /[\p{L}\p{N}_]/u`。
- `js/utils.js:436-459` — `matchTodoAt`：数字头直接 null（`:441`）；token 按 `[\s，。！？；：、,.!?;:)/\]】」』]` 切（`:442`）；hex 形状 null（`:443`）；最长名不区分大小写匹配（`:447-452`）；**尾字符是 `\p{L}\p{N}_` ⇒ 跳过该名**（`:452`）。
- `js/utils.js:469-493` — `protectTodoRefs`：起始门 `at===0 || !/[a-zA-Z0-9]/.test(seg[at-1])`（`:481`）；命中 → `TODOBLOCK<n>END` 占位（`:485`）；未命中 → 补回 `#` 继续扫（`:488`，**不吞字**）。
- `js/utils.js:503-515` — `restoreTodoRefs` → `<span class="todo-tag" data-todo=… data-todo-id=… data-todo-detail=… role="button" tabindex="0">#slice</span>`（`:511-513`），全部值 `escapeHtml` + `"`→`&quot;`（`:508-510`）。
- `js/utils.js:553` — `RE_TODO_RESTORE = /TODOBLOCK(\d+)END/g`。
- `js/utils.js:1023-1025` — 管线挂点：非代码段 `protectMentions(protectMath(protectMsgTokens(seg)))` 之后套 `protectTodoRefs`；`split(RE_CODE_SPLIT)` 保证围栏/行内在**奇数段原样通过** ⇒ 代码内 `#` 天然 inert。
- `js/utils.js:1134` — `html = restoreTodoRefs(html, todoBlocks)`（在 mention 还原之后、msg-token chip 之前）。
- 用户气泡腿（不走 markdown）：`js/utils.js:1167-1216` `appendTextWithMentions`，`@`/`#` 同一次扫描取更靠前者（`:1176-1183`），chip 构建 `:1202-1211`（同样三个 data 属性 + role + tabindex）；调用方 `js/utils.js:1234-1263` `appendUserTextWithFences` → `js/chat.js:563`。
- DOM 补齐腿（历史/首达）：`js/utils.js:1292-1322` `enhanceMentionSpansInDom`（TreeWalker，跳过 `pre,code,.voice-block,.mention-tag,.todo-tag,.katex,button,svg,script,style`，`:1306`）→ `:1332-1379` `wrapMentionsInTextNode`，chip 组装 `:1364-1371`。
- 待办数据源：`js/todoStore.js:455-463` `openItems()`（`maybePull()` 后 `items.filter(!completed)`，**刻意绕开分组/重要度筛选**，注释 `:456-460`）；导出 `js/todoStore.js:822`。

### 1.4 ③ 渲染产物（chip 的 DOM 形态 / 点击行为 / 属性）

- DOM 形态（三个来源同一形态）：markdown 腿 `js/utils.js:511-513`；用户气泡腿 `js/utils.js:1204-1210`；DOM 补齐腿 `js/utils.js:1366-1371`。
- 属性：`data-todo` = 名字，`data-todo-id` = id（可为空串），`data-todo-detail` = detail（可为空串）；`role="button"` + `tabindex="0"`。
- 视觉：`css/chat.css:1396-1403` — `color: rgb(var(--sapphire))`、`cursor:pointer`、`border-bottom: 1px dashed color-mix(…55%, transparent)`，hover 转实线（`:1401-1403`）。无二次色值、无字号改动。
- 点击行为：`js/todoComplete.js:74-93`（document 级委托，覆盖流式/历史/重放三腿）—— 取 `data-todo-id` 找 `.tfl-row[data-id=…]`（`:83`）；**行在 DOM** ⇒ `scrollIntoView({block:'center'})` + 点 `.tfl-row-main` 进编辑面（`:85-90`）；**行不在 DOM** ⇒ `window.__showToast?.(detail || name || t('todoComplete.detailUnavailable'), 'info')`（`:91-92`）。

### 1.5 边界情形逐条判定（正面回答）

| 情形 | 结果 | 依据 |
|---|---|---|
| `#692`（井号+数字） | 补全不弹、渲染不识别 | `js/tokenComplete.js:113`；`js/utils.js:441` |
| `#fff` / `#ffffff` / `#abcd`（CSS 色值） | 同上 | `js/tokenComplete.js:114` + `:83`；`js/utils.js:443` + `:424` |
| 名字后紧接汉字（`#买菜了`，待办名「买菜」） | **不识别**（尾字 `了` 命中 `\p{L}` 被跳过） | `js/utils.js:452` + `:426` |
| 名字后紧接字母/数字/下划线（`#买牛奶abc`） | 不识别 | 同上（check 脚本用例已红/绿钉住） |
| 名字后接中文标点/空格/行尾 | 识别（`，` 已在切分符表内） | `js/utils.js:442` |
| `C#` / `a#1`（ASCII 字母数字前） | 不触发 / 不识别 | `js/tokenComplete.js:108`；`js/utils.js:481` |
| 行首 / 空白后 / 汉字后 / 中文标点后 | 触发 + 识别 | `js/tokenComplete.js:105-108`；`js/utils.js:481` |
| 代码围栏 / 行内代码内 | inert | `js/tokenComplete.js:112` + `:131-143`；`js/utils.js:1020-1026` |
| 名字恰好是 3 位纯字母（如「abc」） | **被色值门误伤，不可引用**（自报「宁漏不误」代价） | `js/utils.js:443`；`js/todoComplete.js:16-17` |

### 1.6 已知缺口（本次核出的，按严重度）

1. **chip 键盘不可达**：`role="button" tabindex="0"` 已设（`js/utils.js:512`），但全仓唯一监听是 `js/todoComplete.js:74` 的 `click`；`todoComplete.js` 内无 `keydown` ⇒ Enter/Space 无反应。
2. **手机端 chip 点击多为 toast**：任务页 `#mobile-task-panel` 惰性创建（`js/mobile/taskPanel.js:68-105`，仅 `pushPage('tasks')` 后建），未开页时 `.tfl-row[data-id]` 不在 DOM ⇒ 走 `js/todoComplete.js:91-92` 的 toast 兜底，不会真的跳到待办。
3. **起始门两处不同源**：markdown 腿 `!/[a-zA-Z0-9]/`（`js/utils.js:481`）vs 用户气泡腿/DOM 补齐腿 `MENTION_WHITESPACE_RE = /\s/`（`js/utils.js:290`、`:1184`、`:1344`）⇒ 「看#买牛奶」在 agent 面成 chip、在用户气泡面是纯文本。
4. **改名即失效**（已申报的设计代价）：`js/todoComplete.js:27-28`，旧消息 `#旧名` 降级为纯文本，不跳到错误待办。
5. **无移动端专属样式**：`#mention-dropdown` 只有一份几何（`css/input.css:1121-1155`，`bottom:100%` / `left,right:12px` / `max-height:240px`），`css/mobileLayer.css` / `css/mobileSearchFlat.css` 对 `.mention-*` / `#mention-dropdown` **零命中**（grep 已验证）⇒ 手机层直接继承桌面兜底几何；选项选中走 `mousedown`（`js/tokenComplete.js:269`），**未做真触屏验证**（本次纯静态）。
6. **locale 无缺口**：只有 `zh-CN` / `en` 两套，`todoComplete.title` / `noMatch` / `detailUnavailable` 两套齐全（`js/locales/zh-CN.js:761-763`；`js/locales/en.js:805-807`）。
7. **chip 样式无容器覆写**：`.todo-tag` 全仓只在 `css/chat.css:1396` 定义一次（grep 全 CSS 仅此一处），mobileLayer 无覆写。

---

## 二、手机端待办面板现状

### 2.1 入口链（逐环）

1. `js/mobile/chatChrome.js:256-263` — 左上圆钮 `button.mh-chrome-btn.g2-fab`，`dataset.chrome = 'tasks'`（`:259`），图标 `lucide list-todo` + 徽标 `.mh-chrome-badge`（`:262-263`）；几何注释 `:244-255`（`left: var(--mchrome-top)` / `top: calc(var(--mchrome-top) + env(safe-area-inset-top))`）。
2. `js/mobile/chatChrome.js:264-270` — click ⇒ `e.stopPropagation()` + `logEvent('chrome-click',{face:'tasks'})` + **`pushPage('tasks')`（`:269`）**。
3. `js/mobile/chatChrome.js:286-288` — 双钮同层（左任务 / 右搜索）；`:291-308` 徽标三腿同步（首刷 / 镜像事件 / `todoStore.subscribe`）。
4. `js/mobile/navController.js:2381-2394` — `pushPage(pageId)`：`PAGE_BY_ID` 校验（`:2383`）→ 已栈内先摘 → push → **`void ensurePanelContent(pageId)`（`:2391`）** → `renderPages()`。
5. `js/mobile/navController.js:633-712` — `ensurePanelContent` 分派：`case 'tasks'`（`:699-703`）→ `ensureTaskPanel()`（`:700`）+ `ensureOverlayReconcile('tasks','#mobile-task-panel', el => !!el.hidden, ['hidden'])`（`:702`）+ `syncOverlayFaces()`（`:703`）。
6. `js/mobile/navController.js:577-590` — `overlayFaceByPage` 收编表，`tasks: '#mobile-task-panel'`（`:587`）；`js/mobile/navController.js:59` 从 taskPanel import `ensureTaskPanel, closeTaskPanel`；`js/mobile/navController.js:122-124` PAGES 条目 `{id:'tasks', tabId:null, icon:'list-todo', zh:'任务'}`。
7. `js/mobile/taskPanel.js:262-267` — `ensureTaskPanel()`：`ensurePanel()`（惰性建）→ `render()` → `p.hidden=false` → `logEvent('tpanel-open',{via:'page'})`。
8. `js/mobile/taskPanel.js:68-105` — `ensurePanel()`：`div#mobile-task-panel.mh-tpanel`（`:70-75`），头 `.tp-hd`（图标 + `.tp-stats` + `.tp-x` 关闭，`:77-89`），体 `.tp-bd`（`:91-93`）→ **`mountTodoFaceInto(bd)`（`:95`）**；订阅 `delegate-chain-mirror-updated` / `nebflow-session-change` 只刷徽标（`:102-103`）。
9. `js/todoFace.js:2087-2092` — `mountTodoFaceInto(container)`：清空 → `new TodoFace({ mobile:true })` → 挂 `face.root`。
10. `js/todoFace.js:457` / `:462-471` — mobile 腿：`buildCreateSheet()`（`:457`）、`this.mount.classList.add('tfl-mount-mobile')`（`:464`）、`this.moveCountInto(true)`（`:470`，计数节点搬进 `.tfl-header-count`，手机无胶囊 ⇒ FLIP 首帧宽 0 自然跳过）。
11. 徽标读数：`js/mobile/taskPanel.js:108-111` `taskBadgeCount() = todoStore.openCount()`（会话不显节点条目时 0）。

### 2.2 新建表单承载 = `.mf-drawer` 底部抽屉（**两页共用一族类名，单实现事实**）

- JS 构造：`js/todoFace.js:613-639` — `buildCreateSheet()`：`.mf-drawer`（`:614`，`role=dialog`/`aria-modal`，`:616-618`）+ `.mf-drawer-scrim`（`:620-621`，点=取消）+ `.mf-drawer-box`（`:623`）内 `.mf-drawer-head`（标题 `.mf-drawer-title` + `.mf-drawer-cancel-btn` + `.mf-drawer-done-btn`，`:624-632`）+ `.mf-drawer-body`（`:634`）；整件 append 到 `this.card`（`:638`）。
- 表单本体复用同一条 `buildCreateRow()`，只换宿主：`js/todoFace.js:1226-1306`；宿主取值口 `js/todoFace.js:1340-1342` `createHost() = mobile && sheetBody ? sheetBody : listInner`；挂载点 `js/todoFace.js:968`。
- 落位（包含块）：`js/todoFace.js:608-610` 注释（`.tfl-card` 手机腿定位静态 ⇒ 包含块 = `.mobile-page`）+ `css/mobileLayer.css:5270-5271`（同口径）。
- CSS 全族：`css/mobileLayer.css:5265-5344` — `.mf-drawer`（`:5272-5280`，`position:absolute; inset:0; z-index:30; flex column; justify-content:flex-end`）、`.mf-drawer[hidden]`（`:5281`）、`.mf-drawer-scrim`（`:5282-5286`，`--color-bg 62%`）、`.mf-drawer-box`（`:5287-5297`，`max-height:82%` + `--color-bg` + 顶部 1px 分隔 + `--ds-radius-card-lg`）、`.mf-drawer-head`（`:5298-5306`）、`.mf-drawer-title`（`:5307-5313`）、`.mf-drawer-body`（`:5314-5318`）、两键（`:5321-5342`，热区 `--ds-hit-row`=44，取消中性墨 / 完成主色字，`:5339-5340`）。
- **与定时任务页共用同一族类名（单实现事实，务必据此设计）**：`js/scheduled-task.js:997-1015` 构造同一组 `mf-drawer / -scrim / -box / -head / -title`；口径登记在 `css/mobileLayer.css:5118`（「③ 新建抽屉：`.mf-drawer` 一族（待办页与定时任务页共用同一组类名）」）与 `css/mobileLayer.css:5265` 标题行 ⇒ **改这一族 = 同时改两个页面**，不存在第二套抽屉。

### 2.3 标题框蓝边来自哪里（聚焦描边单源）

- 标题输入 = `js/todoFace.js:1231-1238`（`nameInputEl('tfl-row-title-input')`，`nameInputEl` 定义 `js/todoFace.js:168-190`）。
- 该输入框自身样式：`css/todo-face.css:991-1001` — `border:none; outline:none; background:transparent`（特异性 (0,1,0)）。
- **蓝边真身 = 全站输入件焦点态单源**：`css/inputFocus.css:64-75` — `:is(input:not([type=checkbox]…[type=hidden]), textarea, select):focus { outline: var(--nf-field-focus-w) solid var(--nf-field-focus-line); outline-offset: var(--nf-field-focus-inset); border-color: var(--nf-field-focus-line) }`；键盘态同形 `:78-89`；该规则特异性 (0,10,1)（注释 `:61-63`）压过组件级写法 ⇒ `.tfl-row-title-input` 的 `outline:none` 无效。
- 色值/宽度/内缩三令牌：`css/inputFocus.css:52-58` — `--nf-field-focus-line = color-mix(in srgb, var(--btn-focus-ring) 78%, var(--color-text))`（`:54`）、`--nf-field-focus-w: 2px`（`:56`）、`--nf-field-focus-inset: -1px`（`:57`，负值 = 描边压在框体自身边上，零重排）。
- 例外清单（本族不适用）：`css/inputFocus.css:91-97` = `#input`（主会话输入框去高亮）；`:110-120` = 命令面板恢复键盘环；`grep tfl css/inputFocus.css` **零命中** ⇒ 待办面板任何输入框都吃这条 2px 蓝内缩描边。
- 兄弟输入面同样命中：备注 `js/todoFace.js:1242-1249`（`.tfl-edit-notes`，样式 `css/todo-face.css:900-909`）。

### 2.4 顶部现有控制逻辑（`.tfl-header` + `.tfl-filter-wrap`）

- `js/todoFace.js:474-500` — `buildHeader()`：`.tfl-header`（`:475`）+ `.tfl-header-count`（`:476`，手机页面态的计数槽）+ `.tfl-header-spacer`（`:478-479`）+ `.tfl-icon-btn.tfl-filter-toggle`（`:481-491`，点=切 `showFilterBar` 并 `renderFilterBar()`，随后 `refocusList(400)`）+ `.tfl-icon-btn.tfl-collapse-btn`（`:493-498`，点=`collapse()`）。
- 计数节点是**同一颗 DOM 在两处搬家**：`js/todoFace.js:398-411`（胶囊内 `.tfl-cap-faces` / `.tfl-cap-face-todo` / `.tfl-cap-face-overdue`）+ `js/todoFace.js:1590-1591` 注释（L2 单一计数节点）；手机腿搬家 `:470`。
- `js/todoFace.js:502-552` — `buildFilterBar()`：`.tfl-filter-wrap`（`:503`，初始 `display:none`，`:504`）内 `.tfl-filterbar`（`:505`）：
  - 第一行 `.tfl-chips-row`（`:507`）：`全部` 签 `.tfl-chip.tfl-chip-all`（`:508-513`，`switchView(null)`）+ 左/右箭头 `.tfl-chip-arrow-l/r`（`:514-521`）+ 滚动区 `.tfl-chips-viewport > .tfl-chips-track`（`:522-531`，wheel 仅在 `|dX|>|dY|` 时接管）+ 垃圾桶 `.tfl-chip-trash`（`:532-547`，切 `tfl-chips-deleting` 删除模式）。
  - track 内容 = 标签 chips + **`+` 新建标签钮 `.tfl-chip-add`**：`js/todoFace.js:842-859`（`renderChips`，add 钮在 `:850-858`，点 `openTagPopover(null)`）。
  - 第二行 `.tfl-prio-row`（`:549-550`）：`js/todoFace.js:912-931` `renderPrioChips()` 出 4 签（p=0..3 = 无/低/中/高，`PRIORITY_LABEL_KEYS`），点 = `todoStore.togglePriority(p)`（`:926`）。
- 底部：`js/todoFace.js:554-569` `buildFooter()` 出 `.tfl-add-btn`（`creating` 时切「完成」+ `saveCreate`，否则「添加」+ `startCreate`），总条目视图直接 `return` 不出 footer（`:556`）；右下悬浮新建 `js/todoFace.js:586-600` `.tfl-fab-add`（手机腿加挂 `.g2-fab`，`:587`），点 = 幂等 `startCreate()`（`:592-597`）。
- 样式锚点（`css/todo-face.css`）：`.tfl-header` `:370`、`.tfl-header-count` `:378`、`.tfl-filter-wrap` `:431`、`.tfl-filterbar` `:435`（开合 `:444`）、`.tfl-chips-row` `:445`、`.tfl-chip-add` `:563`、`.tfl-prio-row` `:580`、`.tfl-footer` `:1034`、`.tfl-fab-add` `:1072-1102`。

---

## 三、「聊天记录搜索框 → 胶囊」形变：本批动效语言的唯一原型（抄成清单）

原型文件 = `css/mobileSearchFlat.css`（样式单源）+ `js/mobile/socialSearch.js`（状态单源）。文件头 `:1-62` 自述三判据与三个 bug 的结构性拆除。

### 3.1 时长 / 曲线

| 项 | 值 | 锚点 |
|---|---|---|
| 时长 | `--mhsc-dur: 280ms` | `css/mobileSearchFlat.css:74` |
| 曲线 | `--mhsc-ease: cubic-bezier(0.4, 0, 0.2, 1)` | `css/mobileSearchFlat.css:75` |
| 带高常量 | `--mhsc-band: calc(var(--ds-hit-tap-lg,48px) + var(--ds-space-3,12px))` = 60 | `css/mobileSearchFlat.css:69-72`（声明在页面体，带与滚区共同祖先） |
| reduced-motion 降级 | `--mhsc-dur: 1ms`（二值语义保留，过渡压近零） | `css/mobileSearchFlat.css:207-212` |

### 3.2 属性白名单（两端都必须是可插值属性）

- 条本体 11 项：`width` / `height` / `margin-left` / `margin-right` / `padding-left` / `padding-right` / `border-radius` / `border-color` / `background-color` / `box-shadow` / `backdrop-filter` —— `css/mobileSearchFlat.css:149-162`。
- 图标 2 项：`width` / `height` —— `css/mobileSearchFlat.css:164-169`（展开 16px 居左 → 收起 `--cv-icon` 20px 居中）。
- 输入行 2 项：`opacity` / `max-width` —— `css/mobileSearchFlat.css:171-176`。
- 编排纪律（`:146-148` 注释）：收起态的**材料与数值不写在本文件**，整件由 `.g2-fab` 供给；本文件只给过渡；两端都靠 `calc` 混合插值（百分比 ↔ 像素）。

### 3.3 两端几何（实测归档）

| 端 | 读数 | 锚点 |
|---|---|---|
| 展开（扁平长条） | `width: calc(100% - var(--ds-space-4)*2)` / `height: 44px` / `margin: 0 16` / `padding: 0 16` / `border-radius: var(--ds-radius-xl)`=12 / 底 `--ds-wash-16` / `border: 1px solid transparent` / `box-shadow:none` / 双重 `backdrop-filter:none` | `css/mobileSearchFlat.css:133-144` |
| 收起（右上角圆钮） | `margin-left: calc(100% - var(--ds-space-4) - var(--ds-hit-tap-lg,48px))` / `margin-right: 16` / `padding-left/right: 0` / `justify-content:center` / `cursor:pointer`；视觉整件 = `.g2-fab`（48×48 / 玻璃底 / 描边 / 浮影 / blur） | `css/mobileSearchFlat.css:183-190`（材料来源 `:14-21` 注释） |
| 收起态输入行 | `opacity:0; max-width:0; min-width:0; pointer-events:none` | `css/mobileSearchFlat.css:191-196` |
| 收起态清空键/范围 chips | `display:none` | `css/mobileSearchFlat.css:201-205` |
| 实测归档（375×812） | 展开 `44×343` @ `left16/right359`、`radius 12`、`rgba(128,128,128,0.16)`、内距 16、dock 高 60 ⇄ 收起 `48×48` @ `right359`、`radius 50%`、图标 20、dock 高仍 60（滚动容器几何零变化）；透明顶带：dock `absolute` / `background rgba(0,0,0,0)` / `backdrop-filter none` / `pointer-events none` / `z-index 2` / 滚区顶边 0（改前 60）/ `clientHeight 750`（改前 690）/ `padding-top 64` | `css/mobileSearchFlat.css:214-227` |

### 3.4 结构不变量（原型能成立的前提，抄的时候不能丢）

- **单节点形变**：整条只有 `.mh-search-bar` 一个 DOM，收/展 = 同一节点上的类切换（`css/mobileSearchFlat.css:22-24`；JS 单点 `js/mobile/socialSearch.js:584-590` `setCollapsed`，判据类 `.mh-search-collapsed` 挂 `.mh-search-dock`）。
- **几何恒等式**：带高锁常量、收/展全程滚动容器 `clientHeight/scrollHeight` 零变化 ⇒ 不触发滚回钳与事件回流（`css/mobileSearchFlat.css:44-50`；阈值 `js/mobile/socialSearch.js:75-83`，`COLLAPSE_AT=24` / 回滞 `EXPAND_AT`）。
- **不泄漏**：圆钮 = 条本体、住页面体内，随 `.mobile-page` 的 `visibility:hidden` 退场；路由切换时 `resetCollapseOutsideLive` 复位（`css/mobileSearchFlat.css:51-57`）。
- **令牌红线**：本文件色值字面量必须为 0，圆角只落 `--ds-radius-xl` / `--ds-radius-input` / `50%`，无 `font-size` 字面量（`css/mobileSearchFlat.css:60-62`，门禁 `scripts/check-design-tokens.mjs`）。

---

## 给下游节点的锚点清单（foundation / impl 直接照单使用）

| # | 文件:行 | 一句话 |
|---|---|---|
| 1 | `src/main/resources/web/js/tokenComplete.js:100-116` | 补全分词唯一实现（`parseToken`）：起始门 + 空白 + codeGate + 数字门 + 色值门 |
| 2 | `src/main/resources/web/js/tokenComplete.js:149-193` | 候选排序唯一实现 `rankCandidates`（`@`/`#` 共用） |
| 3 | `src/main/resources/web/js/tokenComplete.js:402-427` | `pickToken` 插入形态（`insertText + ' '`，重设光标、重派 input） |
| 4 | `src/main/resources/web/js/tokenComplete.js:441-466` | 弹层键盘钩子（↑↓/Tab/Enter/Esc，零命中不消费） |
| 5 | `src/main/resources/web/js/tokenComplete.js:208-219` | 弹层宿主：`#mention-dropdown` 挂进 `#input-area`（单节点单例） |
| 6 | `src/main/resources/web/css/input.css:1121-1155` | 弹层唯二几何（`bottom:100%`、`left/right:12px`、`max-height:240px`、`z-index:50`） |
| 7 | `src/main/resources/web/js/todoComplete.js:53-65` | `#` provider 注册（三道额外门禁 + 插入形态），新增引用语法的模板 |
| 8 | `src/main/resources/web/js/todoComplete.js:39-51` | `#` 数据面 = `todoStore.openItems()`（含 detail 摘要取法） |
| 9 | `src/main/resources/web/js/todoComplete.js:74-93` | `.todo-tag` 点击委托（行在 DOM → 进编辑；不在 → toast） |
| 10 | `src/main/resources/web/js/input.js:2213` | 补全引擎绑定点（initInput 单 composer） |
| 11 | `src/main/resources/web/js/input.js:1780` | 键级联第一顺位（先补全、后 slash/历史/发送） |
| 12 | `src/main/resources/web/js/utils.js:436-459` | 渲染侧最长名匹配 `matchTodoAt`（形状门禁 + 词边界） |
| 13 | `src/main/resources/web/js/utils.js:469-515` | `protectTodoRefs` / `restoreTodoRefs`（占位→chip，不吞字） |
| 14 | `src/main/resources/web/js/utils.js:1023-1025` + `:1134` | 渲染管线两个挂点（代码区已切走 ⇒ 围栏内 inert） |
| 15 | `src/main/resources/web/js/utils.js:1167-1216` | 用户气泡腿 chip 构建（**起始门用 `\s`，与 1.6③ 不同源**） |
| 16 | `src/main/resources/web/js/utils.js:1292-1322` | 首达/历史 DOM 补齐（`enhanceMentionSpansInDom`） |
| 17 | `src/main/resources/web/js/main.js:379-388` | 待办清单 provider 注入 + 代次 + 首达补齐 |
| 18 | `src/main/resources/web/js/todoStore.js:455-463` | `openItems()`（跨分组、只滤未完成） |
| 19 | `src/main/resources/web/css/chat.css:1396-1403` | `.todo-tag` 全仓唯一定义（蓝字 + 虚线下标，hover 转实线） |
| 20 | `src/main/resources/web/js/mobile/chatChrome.js:256-270` | 手机端任务圆钮 + `pushPage('tasks')` 唯一点击口 |
| 21 | `src/main/resources/web/js/mobile/navController.js:2381-2394` | `pushPage` 通用压栈 + `ensurePanelContent` 调用点 |
| 22 | `src/main/resources/web/js/mobile/navController.js:699-703` | `case 'tasks'` 分派（建面板 + overlay 对账） |
| 23 | `src/main/resources/web/js/mobile/navController.js:577-590` | `overlayFaceByPage` 收编表（`tasks → #mobile-task-panel`） |
| 24 | `src/main/resources/web/js/mobile/taskPanel.js:68-105` | 面板壳惰性构造 + 体挂载 `mountTodoFaceInto(bd)` |
| 25 | `src/main/resources/web/js/mobile/taskPanel.js:262-275` | `ensureTaskPanel` / `closeTaskPanel`（页面开合两臂） |
| 26 | `src/main/resources/web/js/todoFace.js:2087-2092` | `mountTodoFaceInto`（`new TodoFace({mobile:true})`，手机页面即展开态） |
| 27 | `src/main/resources/web/js/todoFace.js:457-471` | 手机腿三件事：抽屉、`tfl-mount-mobile`、计数搬家 |
| 28 | `src/main/resources/web/js/todoFace.js:474-500` | `.tfl-header` 三件（计数槽 / 筛选钮 / 收起钮） |
| 29 | `src/main/resources/web/js/todoFace.js:502-552` | `.tfl-filter-wrap`：chipsRow（全部+箭头+track+垃圾桶）+ prioRow |
| 30 | `src/main/resources/web/js/todoFace.js:842-859` | chips track 渲染（标签 chips + `+` 新建标签 `.tfl-chip-add`，`:850-858`） |
| 31 | `src/main/resources/web/js/todoFace.js:912-931` | prioRow 四签渲染（无/低/中/高） |
| 32 | `src/main/resources/web/js/todoFace.js:554-569` + `:586-600` | 底部 `Add footer`（`.tfl-add-btn`）+ 右下悬浮 `.tfl-fab-add`（手机腿 `.g2-fab`） |
| 33 | `src/main/resources/web/js/todoFace.js:613-639` | 新建底部抽屉 JS 构造（`.mf-drawer` 全族） |
| 34 | `src/main/resources/web/css/mobileLayer.css:5265-5344` | `.mf-drawer` 全族 CSS 唯一定义（扁平实底 / z30 / 82% 高 / 顶 1px 分割 / 44 热区两键） |
| 35 | `src/main/resources/web/css/mobileLayer.css:5118` + `:5265` | **两页共用一族类名**的书面登记（待办页 ∪ 定时任务页） |
| 36 | `src/main/resources/web/js/scheduled-task.js:997-1015` | 定时任务页同一抽屉的第二使用方（改族类名必连带） |
| 37 | `src/main/resources/web/js/todoFace.js:1226-1306` | 创建表单本体 `buildCreateRow()`（标题/备注/重要度/截止），标题输入 `:1231-1238` |
| 38 | `src/main/resources/web/js/todoFace.js:1340-1342` | `createHost()` 单一取值口（手机=抽屉体，桌面=列表流内） |
| 39 | `src/main/resources/web/css/inputFocus.css:52-58` + `:64-75` | 标题框蓝边真身（令牌 + 全站唯一焦点规则，2px 内缩） |
| 40 | `src/main/resources/web/css/todo-face.css:991-1001` | 标题输入自身 `outline:none`（特异性低，被上一条压过 —— 别在这里改蓝边） |
| 41 | `src/main/resources/web/css/mobileSearchFlat.css:69-79` | 形变算术单源：`--mhsc-band` / `--mhsc-dur:280ms` / `--mhsc-ease:cubic-bezier(0.4,0,0.2,1)` |
| 42 | `src/main/resources/web/css/mobileSearchFlat.css:149-176` | 过渡属性白名单（条 11 项 + 图标 2 项 + 输入行 2 项） |
| 43 | `src/main/resources/web/css/mobileSearchFlat.css:133-144` | 展开态几何（44 高 / 12 圆角 / `--ds-wash-16` / 零描边零投影零模糊） |
| 44 | `src/main/resources/web/css/mobileSearchFlat.css:183-196` | 收起态几何（右缘 = 100% − 16 − 48 / 输入行 opacity 0 + max-width 0） |
| 45 | `src/main/resources/web/css/mobileSearchFlat.css:207-212` | reduced-motion 降级 = `--mhsc-dur: 1ms`（同 `mobileLayer.css` 惯例） |
| 46 | `src/main/resources/web/css/mobileSearchFlat.css:214-227` | 实测读数归档（44×343 r12 ⇄ 48×48 r50%，dock 恒 60） |
| 47 | `src/main/resources/web/js/mobile/socialSearch.js:584-590` | `setCollapsed` 状态单点（类切换，无内联样式、无第二节点） |
| 48 | `src/main/resources/web/css/mobileSearchFlat.css:60-62` | 令牌红线（色值字面量 0 / 圆角三档 / 无 font-size 字面量） |
| 49 | `scripts/check-todo-mention.mjs`（345 行，本次 GREEN 78/78） | `#` 门禁矩阵 + 拼音排序 + chip 形态的机械门禁，改语法必跑 |
| 50 | `scripts/check-mention-trigger.mjs`（148 行，本次 GREEN 9/9） | 起始门回归腿（`@` 8 例 + prompt 目录条），改起始门必跑 |
