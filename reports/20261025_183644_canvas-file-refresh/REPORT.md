# canvas 文件刷新批 — 报告（作者令 2026-10-25 17:06）

两件：① canvas 文件页签右下「重新读盘」刷新钮；② 「查看源码」提速（先归因再修）。
纯前端批（web/ 五文件 + spec），零 Scala 改动，刷新即生效。

## 一、「查看源码」提速 — 归因

链路实测（headless chromium，静态服 web/ 真源，localhost）：

| 路径 | 实测 |
|---|---|
| 冷首击（无预载，点击触发全部加载） | 288ms（方差 112–288ms，多轮） |
| 空闲预载完成后点击 | **72–132ms** |

冷击发出的请求链（request 日志，4 发）：
`/vendor/monaco/vs/loader.js` → `editor/editor.main.js`（**3.6MB**）→ `editor/editor.main.css` → `basic-languages/markdown/markdown.js`。
AMD loader 按依赖逐级取件（loader→editor.main→css/语言），非一次并行。

**根因**：`monacoEditor.js` 的 `preloadMonaco()` 自始存在但**全仓无人调用**——首击把
「loader + 3.6MB 主包 + css + 语言包」整条 AMD 链压在点击上；本机 localhost 即可测出
2–4× 差距，跨机访问（作者 Windows 端经网关）每请求再叠 1+ RTT、3.6MB 走窄带，
即「点击后转很久」。后端面排除：`pop.readFile` 是定点单文件读
（WsFileOpsHandlers.scala:518，无整目录扫描、无 base64），与本现象无关。
`/vendor/**` 为裸路径（no-cache，AssetVersions.scala:12），304 协商不改变首击形态。

**修复**：`main.js` 启动尾追加 `requestIdleCallback`（10s 兜底）空闲预载 Monaco；
移动端跳过（源码态无入口），失败静默（首击懒加载兜底不变）。
预载后点击只剩 `createEditor`，实测 72–132ms（预算 1500ms，spec 断言钉住）。

## 二、刷新按钮 — 实现

- **新增** `viewers/shared.js addRefreshButton(pane, {onRefresh})`：frosted 标准 token
  圆钮（与 source/ref 同族材质，split.css `.canvas-refresh-toggle`），40px/48px 槽距；
  座位按既有角钮实测让位（source16·ref64·find/graph112/160·缩放条按实测宽度+8），
  面板目标宽读 `--canvas-width`（过渡期 clientWidth 是瞬态值，会误判溢出）；
  放不下落第二行 bottom:64（同窄幅 html 缩放条先例）。
- **挂载单点** `canvas.js openWorkspaceItem` 三腿渲后挂载（首开/既有重渲/不可读骨架）；
  查看器渲前 `pane.innerHTML=''` 清场，故必须渲后挂。URL/面板页签（无 absPath）无钮。
- **点击动作** `refreshFileTab(entry)`：脏标签拒绝（可见 toast，不覆盖未保存修改——
  与全部既有刷新路径同纪律）；源码态隐藏（CSS `[data-source-mode="1"]`）+ 程序守卫；
  先 `nfCacheInvalidatePath`（nf-file blob 账本命中 = 零取数直渲染，不失效则图片/PDF
  刷新在喂缓存、按钮撒谎），再走既有 readFile 面
  `pop.readFile → fileContent → workspace-open-item` 重渲，不整页刷新。
- **binary 腿补洞**：readFile 二进制应答不带 content，原重渲条件 `content && …` 对
  图片/PDF 永不触发（刷新=无声空点）。explorer.js 新增 `manual` 旗标
  （pendingManual Set，随帧透传），canvas.js 重渲条件放宽为 `(content || manual)`；
  流式大文本除外（其变更面走既有 acceptMeta 保滚动位，整面重挂会把读者扔回文件头）。
- **源码↔渲染往返**：该路径不经 readFile、且渲前清场会把钮带走——`addSourceToggle`
  渲染腿渲后派发 `canvas-viewer-rerendered`（**bubbles:true**，document 级监听需冒泡），
  canvas.js 监听重挂（事件解耦，shared.js 无回边）。
- **未变无害**：md/html 既有 content-unchanged guard 原样生效（不重挂、不丢滚动位）；
  busy 自旋（reduce-motion 停转）+ 10s 安全复位；手动刷新失败给可见 toast
  （`canvas.refreshFailed`），后台刷新静默纪律不变。
- 骨架标签（文件不可读）也挂钮：文件恢复可读后一键重读翻正。

## 三、验收（spec：tests/canvas-file-refresh.spec.mjs，7 断言全绿）

A1 全类型页签挂钮+座位不重叠+URL 页签无钮；A2 改盘→点击→新内容；
A3 未变点击无害（不重挂、滚动位保持、busy 清）；A4 图片页签刷新=账本失效+真重取
（fetch 计数 1→2）；A5 脏标签拒绝+可见 toast+零 readFile；A6 源码态隐藏/渲染态回归；
A7 预载后源码打开时延 132ms < 1500ms 预算。稳定性：全文件 5 连跑 + A4 单测 3 连跑全绿
（合跑曾现 A4 挂起，已加 500ms 账本落账等待 + 显式超时后 8 连绿）。

checkJs：260 = baseline 260，零新增。

## 四、预览族回归

- @playwright 族（canvas-file-refresh / mobile-canvas-sheet / user-attach-canvas-open /
  chat-diff-canvas / diff-canvas-first-frame / cardhead-shots / cardwin-shots）：29 passed。
- standalone node 族 12 条：9 PASS；3 FAIL 均为**存量失败**（对照实验：对 HEAD 纯净树
  `git archive` 复跑，失败逐字相同）——
  · canvas-capsule-ring 37/1（CR2-5 frosted 材质逐项相等，存量漂移）；
  · canvas-preview-nav-parity 54/5（修② 代理 URL 返回条组，存量）；
  · canvas-office-viewers：ENOENT `.nebflow/evidence/20261006_pptx-mvp/poc/decks/
    fidelity-sample.pptx`（证据件不入库，环境缺件）。

## 五、证据

`evidence/20261025_canvas-file-refresh/shots/`：01 md 页签四钮簇（刷新160·图谱112·
查找64·源码16）；02 图片页签缩放条+刷新同排让位；03 刷新前（尾部）；04 点击刷新后
新内容可见（REFRESHED-BODY-MARKER v2）；05 源码态刷新钮隐藏。

## 六、未做 / 假设

- 面板开合过渡期挂载的座位判定读 `--canvas-width`（openCanvas/colResizer/叠卡均维持
  该值）；若未来出现不经该变量的面板宽度来源，需同步此处。
- md 内嵌图片的 nf-file 账本缓存按现状保留（刷新语义=文件本体重读；内嵌图走文本内容
  重渲 + 既有账本单点，未见问题；若作者要「连图一并强刷」再立一批）。
- 移动端 sheet 面（mobile-canvas-sheet 回归绿）未做刷新钮专门适配验证；spec 断言以
  桌面 canvas 为准。
- Monaco dispose 时偶发 `Canceled` 未处理 rejection（栈全在 editor.main.js 内部
  tokenize 取消链）——源码态编辑器 dispose 为 addSourceToggle 既有路径（本批未触碰），
  非本批新增；spec 内已注明过滤。根治归 Monaco 升级面，未动。
- node_modules 为软链（worktree 缺失，链主仓）；不入库（已 ignore）。

## 七、文件清单

- `src/main/resources/web/js/viewers/shared.js` — addRefreshButton + 座位计算 + 往返事件
- `src/main/resources/web/js/canvas.js` — refreshFileTab / settle / 挂载三腿 / manual 重渲条件 / rerendered 监听
- `src/main/resources/web/js/explorer.js` — pendingManual 旗标透传
- `src/main/resources/web/js/main.js` — Monaco 空闲预载
- `src/main/resources/web/css/split.css` — `.canvas-refresh-toggle` 材质/自旋/源码态隐藏
- `src/main/resources/web/js/locales/{zh-CN,en}.js` — canvas.refresh / refreshDirty / refreshFailed
- `tests/canvas-file-refresh.spec.mjs` — 7 断言验收
