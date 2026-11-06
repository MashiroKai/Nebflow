# `#` 引待办 —— 真实前端实测（recon-live）

> 实测时间 2026-11-06 08:14–08:16（Asia/Shanghai）· worktree `delegate/todo-mobile-revamp`
> spec = `tests/todo-hash-mention-live.spec.mjs`（`node tests/todo-hash-mention-live.spec.mjs` 直跑，
> 自打 PASS/FAIL，退出码可判）· 本轮 `PASS 60 / FAIL 0 / SKIP 0`，**exit 0**
> 全量原始输出 = 同目录 `run.log`

---

## 0. 判决（一句话）

**实现了，且可用。** `#` 在真输入框里能唤起补全浮层，键盘/鼠标选中后逐字插入 `#<待办名> + 空格`，
消息体原样出线（零转义零引号），主会话两个渲染腿（用户气泡纯文本面 + assistant markdown 面）
都把 `#<待办名>` 变成可点 `.todo-tag` chip；手机层同一组断言逐条通过。
缺口集中在**「宁可漏不误」的形状门禁**（名字长得像十六进制色值/数字开头 ⇒ 引不到）与
**改名后旧 chip 失效**，两处都是设计选择而非缺陷，但用户无提示、无补救入口。

---

## 1. 实测手法（零后端、零 :6138）

形状照 `tests/_todo-face-harness.mjs`，本 spec 自足（不 import 它，避免共用件漂移）：

| 面 | 真假 | 说明 |
|---|---|---|
| 静态树（index.html / js / css） | **真** | `page.route` 从磁盘注入本 worktree 的 `src/main/resources/web`，零本地端口 |
| 输入引擎（tokenComplete / todoComplete） | **真** | 真 `#input` textarea + Playwright 真键鼠事件 |
| 发送链（input.js `send()` → ws.js） | **真** | 真点 `#send-btn`，真出帧 |
| 服务端 | 替身 | `page.routeWebSocket(/\/ws/)` 帧桩（configData / sessionList / 空 history / pong） |
| 待办清单 | 替身 | `todoStore.__seed` 夹具模式（3 条，纯本地、不发写帧） |

因此本 spec 证明**前端**端到端形态，**不证明**后端受理 / 回执 / `#` 名字在后端账本里的唯一性。

夹具三枚（任务书要求：中文 / 英文 / 与井号数字·色值易混的对照名）：

| id | name | detail | 角色 |
|---|---|---|---|
| `todo-cn-1` | `季度复盘` | 本季 OKR 与复盘纪要 | 中文主测（补全→插入→发送→chip 全走它） |
| `todo-en-2` | `release-check` | 发版前检查单 | 英文前缀过滤 / Tab 选中 |
| `todo-hex-3` | `deface` | 与六位十六进制色值同形（对照） | **对照组**：6 位纯十六进制（d/e/f/a/c/e），期望「不弹、不成 chip」 |

---

## 2. 逐条结论（含逐字值）

### ① 键入 `#` ⇒ 补全浮层会不会出现？列出哪些候选？

会出现。`#mention-dropdown` 拿到 `.on`，分组标题 = `待办`，候选 = 全部 3 条未完成待办
（空查询按 locale 名称升序）：

```
names = ["季度复盘","deface","release-check"]
title = "待办"
```

每行 = 名称 + 副标题（待办 detail 的 60 字摘要），首行默认高亮：

```
季度复盘       本季 OKR 与复盘纪要
deface        与六位十六进制色值同形（对照）
release-check  发版前检查单
```

截图：[`01-desktop-dropdown.png`](01-desktop-dropdown.png)

### ② 选中后的输入框逐字值

| 触发 | 操作 | 输入框逐字值 |
|---|---|---|
| `#` | `ArrowDown`（高亮 0→1） + `Enter` | `"#deface "` |
| `#rel` | 浮层只剩 1 条 → `Tab` | `"#release-check "` |
| `#季` | 鼠标点击候选行 | `"#季度复盘 "` |

三点共同结论：插入形态 = `#<待办全名>` + **一个尾随空格**，中文名逐字插入（无引号、无转义、
无 id、无 `#名字·id` 复合形态）；选中后浮层立即收起（`on=false`）。

截图：[`02-desktop-after-pick.png`](02-desktop-after-pick.png)

### ③ 真正发出的消息体字符串（截 WS 帧，不是读代码推断）

组合路径 = 键入 `#` → 选中「季度复盘」→ 继续键入 `这条是回复` → 输入框值
`"#季度复盘 这条是回复"` → 点 `#send-btn`。

真出线帧（页面→服务端，WS 帧桩原样记录）：

```json
{
  "keys": ["attachments","chatWidth","clientMessageId","content","device","sessionId"],
  "type": "<absent>",
  "content": "#季度复盘 这条是回复",
  "sessionId": "todo-hash-live-session",
  "clientMessageIdLen": 12
}
```

**逐字消息体 = `#季度复盘 这条是回复`** —— 与输入框逐字一致；`content` 里没有转义、没有
引号、没有待办 id、没有额外标记，`#季度复盘` 是**纯文本字面**（渲染期才变 chip）。用户帧本身
无 `type` 键（正文帧，`clientMessageId` 12 位）。发送后输入框被清空（`""`）。

### ④ 主会话渲染侧变 chip 了吗？

两个渲染腿都变，outerHTML 逐字相同：

**用户气泡（纯文本面，`appendTextWithMentions` 内联腿）**

```html
<span class="todo-tag" data-todo="季度复盘" data-todo-id="todo-cn-1" data-todo-detail="本季 OKR 与复盘纪要" role="button" tabindex="0">#季度复盘</span>
```

**assistant / markdown 面（`utils.js:restoreTodoRefs` 占位符还原腿）**

```html
<span class="todo-tag" data-todo="季度复盘" data-todo-id="todo-cn-1" data-todo-detail="本季 OKR 与复盘纪要" role="button" tabindex="0">#季度复盘</span>
```

chip 文本 = `#季度复盘`（不含尾随空格、不含后文），带 `data-todo` / `data-todo-id` /
`data-todo-detail` 三属性与 `role=button tabindex=0`（可点，点击委托在 `js/todoComplete.js`）。
入场动画落定后实测 `opacity = 1`（`{"row":"1","chip":"1"}`），绿色 chip 与气泡内正文同 baseline。

截图：[`03a-desktop-chip-row.png`](03a-desktop-chip-row.png)（用户气泡元素级）、
[`03b-desktop-chip-md.png`](03b-desktop-chip-md.png)（markdown 腿）、
[`03-desktop-chip.png`](03-desktop-chip.png)（整窗）

### ⑤ 手机层是否同样生效？

**同样生效，逐条一致。** 腿 B 用 **390×844** 真视口冷启动，`js/mobile/bootstrap.js` 按
`matchMedia('(max-width:768px)')` 真激活：

```
body.mobile-layer = true
window.__nfMobileLayer = {"active":true,"version":"mobile dock batch (five-tab dock + fullscreen independent pages + canvas page + social faces)"}
```

任务书指定的 `document.body.classList.add('mobile-layer')` 在这条腿上执行了（幂等补刀）。
手机层下输入框仍是**同一个** `#input`（`TEXTAREA`，宿主 `#input-area`）——不是第二套 composer，
所以 `#` 引擎天然同源。手机腿的全部断言（浮层 / 候选 / ↑↓ / Enter / Tab / 点击 / 发送帧 /
chip / 边界六连）**逐条与桌面腿同值**，无一条降级。

截图：[`04-mobile-dropdown.png`](04-mobile-dropdown.png)（含手机 dock / 顶栏）、
[`05-mobile-after-pick.png`](05-mobile-after-pick.png)、
[`06a-mobile-chip-row.png`](06a-mobile-chip-row.png)、
[`06b-mobile-chip-md.png`](06b-mobile-chip-md.png)、
[`06-mobile-chip.png`](06-mobile-chip.png)

---

## 3. 未识别 / 门禁边界实测结果

两条腿**逐条同值**。补全侧 = 键入后浮层是否 `.on`；渲染侧 = 文本是否产出 `.todo-tag`。

| 输入 | 补全侧实测 | 渲染侧实测 | 判读 |
|---|---|---|---|
| `#1` | 不弹（`on=false`） | 不成 chip | 井号+数字=任务号形状，让位给任务号语义 |
| `#faf` | 不弹 | 不成 chip | 3 位十六进制=CSS 色值形状 |
| `#deface` | **不弹**（清单里真有同名待办也照样不弹） | **不成 chip** | 6 位十六进制形状；对照组的核心结论 |
| `C#` | 不弹 | —（`a#季度复盘` 亦不成 chip） | 前接 ASCII 字母/数字 ⇒ 词中，不开 token |
| ` #`（空白后） | 弹，3 条候选 | — | 行首 / 空白 / 汉字 / 中文标点之后照常触发 |
| `#季度复盘会` | — | 不成 chip | 后缀连着汉字 ⇒ 不吞字（最长名匹配后要求词边界） |
| `#季度复盘` | 弹（`#季` 前缀命中 1 条） | 成 chip | 正例 |

结论：门禁是**形状判据**，两侧（补全 `parseToken` 与渲染 `matchTodoAt`）同源同值，
**未观察到补全侧说「能引」而渲染侧不认（或反之）的错位**。

---

## 4. 缺口与残余面（诚实申报）

1. **形状撞车的待办引不到**：名字若整段像 3/4/6/8 位十六进制（如 `deface`、`fade`）或以数字
   开头，补全与渲染**双双不认**，且**没有任何提示**告诉用户「这条待办引不了」。这是刻意的
   「宁可漏不误」，但对用户是静默的。
2. **改名即断链**：显存消息里的 `#旧名` 在待办改名后变回普通文本（原样显示，不会跳到错误待办）。
3. **点击 chip 的落点行为本轮未测**：委托在 `js/todoComplete.js`（行在 DOM ⇒ 滚到并打开编辑面；
   行不在 ⇒ toast 呈现 detail）。本轮只验证了 chip 生成与可点属性（`role=button tabindex=0`），
   未验证点击后的跳转/toast 实况。
4. **名字含空格的待办未覆盖**：插入会把全名逐字写入（含空格），但补全 token 以空白为界，
   带空格名的补全体验（打一半带空格）本轮未造夹具实测。
5. **数据面是夹具**：`__seed` 不拉真源、不发写帧 ⇒ 候选来自「前端认为的未完成清单」。
   后端账本里名字唯一性、`#` 名字的跨端解析，本轮不覆盖。
6. 手机腿的「真触屏」未接 CDP（本轮用真键鼠 + 真点击；触屏手势面不在本任务范围）。

---

## 5. 重跑

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/todo-mobile-revamp"
node tests/todo-hash-mention-live.spec.mjs        # exit 0 = 全绿；PASS/FAIL/SKIP 逐行自打
```

可选环境变量：`TODOFACE_WEB_ROOT`（换 web 树根）、`OUT_DIR`（换截图落点，缺省本目录）。
**零本地端口、零 sbt、零 :6138 写操作**（只读也不碰：WS 与 API 全被 route/routeWebSocket 截走）。

## 6. 文件清单

| 文件 | 内容 |
|---|---|
| `run.log` | 本轮全量输出（60 PASS / 0 FAIL / 0 SKIP，逐条 detail） |
| `01-desktop-dropdown.png` | 桌面：键入 `#` 的补全浮层（3 候选 + 首行高亮） |
| `02-desktop-after-pick.png` | 桌面：选中后输入框 = `#季度复盘` |
| `03a-desktop-chip-row.png` | 桌面：用户气泡元素级截图（chip + 正文） |
| `03b-desktop-chip-md.png` | 桌面：markdown 腿 chip 元素级截图 |
| `03-desktop-chip.png` | 桌面：整窗 |
| `04-mobile-dropdown.png` | 手机层（390×844，真激活）：`#` 浮层 + dock |
| `05-mobile-after-pick.png` | 手机层：选中后输入框 |
| `06a-mobile-chip-row.png` / `06b-mobile-chip-md.png` | 手机层：两个渲染腿的元素级 chip |
| `06-mobile-chip.png` | 手机层：整窗 |
