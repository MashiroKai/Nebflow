# 存量红 spec 清欠批报告（三案子）— 2026-10-30 00:33

分支 `delegate/delegate-nebflow-1d49b93c`（worktree）· 基线 = main `c57993aec`（fetch 不通外网，本地 main 同步零落后，`git rev-list --count c57993aec..main` = 0）。三案子独立 commit；**产品源码零改动**（含 sw.js / activityBar.js / canvas.js —— 发现的产品回归按纪律回贴报错，未擅自修）。

## 案子 1 · tests/jev-panel-threefix.spec.mjs — 归因：spec 停在过去（C 件重排删锚）

**漂移证据链**
- 旧锚 `#jev-card` / `#jev-head` / `#jev-detail-body` / `.cfg-card-head(.btn)` / `.cfg-row` 横排 / `.jev-health-row` / `.cfg-head-chev`：现树源码（index.html / js/ / css/）**零命中**。
- 删除者：2026-10-28 settings-panel 批 C 件重排，源码自述在 `src/main/resources/web/js/sidebar.js:917-941`（「JeV 插件分配那部分的风格，与『STT 高级设置』区的风格保持一致」）——旧 `.cfg-card` 折叠卡换 STT-advance 同款：头行 `#jev-advance-toggle`（`.settings-collapse-toggle` + `.settings-collapse-chevron`）+ 头行状态位 `#jev-status-hint`（`#settings-jev-health` 徽标自折叠体迁入头行）；折叠体 `#jev-advance-body`（`hidden` 属性开合，`jevAdvanceExpanded` 默认收起）；开关 `#toggle-jev` 迁入折叠体首行；字段组 `.cfg-form-group` 纵排（新增 `#cfg-jev-key-value` 明文直显行）。
- spec 末次改动 2026-10-10（a0f2edfc7 checkpoint），重排未跟进 → main 上全红。字段级锚 `#cfg-jev-provider/#cfg-jev-endpoint/#cfg-jev-keyref/#btn-save-jev/#jev-save-state/#settings-jev-health` 及端点钉死/保存 PUT/legs 徽标逻辑**原样存活**（sidebar.js:951-968, 1348-1379, 1384-1427, 1441+）。

**修法**：按新结构重写断言，三修意图逐条保留——R1 一行收起（hidden 折叠体 + aria-expanded/controls + 头行徽标可见，徽标头行在场为重排后的**新**如实面）；R1b 开合 + 键盘（真 `<button>`，chevron rotate 180°）；R2 开关解耦（gate 已在折叠体内，收起态不可达 = 设计态，改在展开态钉「翻档不动折叠体」+ 收展不清值）；R3/R3b/R4 锚 id 存活、断言零语义改动；截图对象 `#jev-card` → JeV `.settings-section`。

**读数**：`npx playwright test tests/jev-panel-threefix.spec.mjs` → **10/10 PASS**。

## 案子 2 · tests/wiki-graph-paragraph.spec.mjs — 归因：seal 批封存入口后 spec 未跟进

**漂移证据链**
- graphseal 批（82c273a2a = ef5ad87fd + 5d52c76fc，2026-10-29）：`featureFlags.wikiGraphEnabled()`（缺席 = 封存 = 默认 OFF）+ `activityBar.js:248` boot 摘挂 `#wiki-btn`（DOM 摘挂）+ `main.js:2723-2726` 首帧 configData latch（显式 `features.wikiGraph:true` → `enableWikiGraphEntry()` 回挂）。seal spec（wiki-graph-seal.spec.mjs）随批落地。
- 本 spec 原 `openGraph` 直点 `#wiki-btn`——封存后按钮不在 DOM，10 用例全挂（词条二批 5b384bdbf 报告留档「干净树基线同挂」）。本 spec boot 的 WS mock 原本不发 configData，闸从未被喂过。

**修法**：boot 增加 `wikiGraph` 选项并在 WS open 发 configData 闸帧（关 = `{}`，开 = `features.wikiGraph:true`）；新增 §P0 双态钉——关 = `#wiki-btn` 不在 DOM + projects→agents 相邻（槽空出）+ R0 零错误；开 = 回挂静态槽（projects→wiki→agents 精确还原）+ i18n 标题 + R0 零错误。§P1–P8 全族改在开关开档跑。

**读数**：§P0 双态 **2/2 PASS**，回归钉 1 skipped（下述），§P1–P8 10 挂——**挂因已换代**：不再是 spec 漂移，而是下述产品回归（可见红 + 本报告登记，repo 惯例「存量红不动、开放问题入报告」）。

### ⚠ 本批核实发现的产品回归（报错不擅自改）

**开关开的回挂入口点了没反应（封存批自身回归）。** 证据链：
1. `activityBar.js:248` boot 摘挂 → `main.js:4209-4213` 的点击接线在动态 `import('./wikiGraph.js').then(...)` 里 `registerCanvasPanelButton('wiki-graph','wiki-btn',…)`——注册时 `getElementById('wiki-btn')` = **null**（元素已摘）→ `canvas.js:546-553` 的 `if (btn && …)` 不成立，**监听从未挂上**（`boundPanelButtons` 也未收编该 id）。
2. configData 到达 → `enableWikiGraphEntry()`（activityBar.js:381-401）只回挂元素 + 补标题，**无重注册**；全树无 MutationObserver/委托兜底（已盘点 activityBar.js/canvas.js）。
3. 封存批注释「main.js 的 canvas 点击接线绑在按钮**元素**上，元素重插即自然复活」对可见性成立、对点击接线不成立——接线压根没建立过。seal spec W2 只断言挂载/槽位/标题、未点击，故漏网。
4. 实证：本 spec 首轮 §P0 开关开腿点击后按钮 `[active]` 聚焦、`.wiki-graph-pane` 10s 零出现（Playwright error-context 存档）；§P1–P8 同位同形全挂于 openGraph。

**修向（产品侧一行，留产品批定夺）**：`enableWikiGraphEntry()` 回挂后补一次 `registerCanvasPanelButton('wiki-graph','wiki-btn',…)`（`boundPanelButtons` 未含该 id，重复注册幂等），或接线改 `#activity-bar` 事件委托。修好 = 摘掉 spec 内 §P0b 用例的 `.fixme` 修饰符（该用例已把回归钉成可执行断言）。

**连带同族存量（本批不动，登记）**：`wiki-graph-ux` / `wiki-graph-tip-dup-label` / `wiki-graph-multivault`（点击同一封存入口）、`capsule-size-uniform`（:171 点击）、`sidebar-restructure`（:151 结构全等含 `wiki-btn`）——均属 seal 批后未跟进的 spec 债，另批清欠时同样要过 §P0 闸 + 上/product 回归修好才有 flag-on 档可跑。

## 案子 3 · tests/canvas-preview-nav-parity.spec.mjs — 归因：**不是**「dev proxy 返回形状漂移」，是 sw.js navigate 腿劫持

**漂移证据链**
- 词条二批基线「parity 54/5(存量)」复现：`node tests/canvas-preview-nav-parity.spec.mjs` → **54 PASS / 5 FAIL / 3 SKIP**，5 红全在「修② 代理 URL 导航」族，读数 `{"bar":false,"hasIframe":false,"zoomBar":false,"stopped":true}`——帧内 `location.href='/api/nf-file?…&ticket=MOCK'` 后 pane 被停渲染护栏销毁。
- 真实机制：console-load-perf 批（97fd9e274，2026-10-21）上线 `/sw.js`；其 fetch 处理器（sw.js:145-160）**navigate 腿（`req.mode==='navigate'`）先于「/api 零接触」名单生效**——同源帧导航 `/api/nf-file?…` 被 `navigateResponse` 接管；SW 自身发起的 fetch 对 `page.route` 不可见 → 打到真静态服务器 404 → 命中**离线索引兜底副本**（sw.js:120-126）→ app shell 进帧（`nfEmbedded=1`，index.html:12）→ `viewers/html.js` 的 embedded-shell 停渲染分支触发（该分支本身行为正确）。
- 双态实证（本批探针，已清理）：SW 活 = `{bar:false,hasIframe:false,stopped:true}` 且页面层 `page.on('request')` **零 `/api/nf-file` 记录**（请求被 SW 吞入 navigateResponse）；SW 阻断 = `{bar:true,hasIframe:true}`——前端 viewer 契约（html.js load 分流 + 返回条 + pane 保活）原样成立。
- 生产语义复核：真实例（活引擎）下 SW fetch 得 200 原样透传，返回条不受影响——红是 harness 专属（mock 传输层对 SW 不可见），**非产品回归、非 proxy 返回形状漂移**。

**修法（修 spec 不修源的理由）**：被测契约 = 前端 viewer 面；SW = 传输层缓存面，与契约正交；harness 自设前提是「mock 传输层」，SW 破坏了该前提——`boot()` 加 `serviceWorkers:'block'` 恢复前提到位。改 sw.js 调序 = 为绿改产品，且生产行为本无症状，不做。

**读数**：修后 `node tests/canvas-preview-nav-parity.spec.mjs` → **59 PASS / 0 FAIL / 3 SKIP**（3 SKIP 为既有环境项：真样本资产不在本机 + 无 NEBFLOW_LIVE_BASE）。

**附带产品观察（同样只登记不擅动）**：sw.js 的 navigate 腿对**一切**同源导航（含 /api）生效，且 `navigateResponse` 对每次导航体做 sha-256 并写进 `INDEX_CACHE` 的 `/` 键——即一次 `/api/nf-file` 帧导航会把文件字节当「索引」缓存、把其哈希当索引指纹；下次真索引导航哈希不符 → 清 `APP_CACHE` + 对客户端补一跳 navigate（sw.js:128-143）。生产可见面 = 预览过代理文件的标签页在下次冷导航多一跳刷新 + 静态缓存整体作废一次。与 harness 5 红同根（navigate 腿越权），修向可一并裁决：零接触名单挪到 navigate 腿之前。

## 防误伤回归

- 受影响面板既有 spec 族：jev-imagegen-test-buttons / wiki-term-citation / secrets-ui-redesign / settings-key-reveal / settings-cleanup / sidebar-restructure + wiki-graph-seal（ALL PASS 复核）。读数见本报告结尾补记（运行中）。
- `node_modules` 以符号链接接入 worktree（gitignore 内，零入库）；临时探针已清理。

### 家族读数（job 15f06a4a，2026-10-30 00:56 完跑：49 PASS / 12 FAIL）

本批 diff = 三个 spec 文件 + 本报告，零产品码 → 家族红与本批无因果（独立性判据：涉红 spec 均不 import / 不驱动本批改动件）。逐案归因：

| spec | 结果 | 归因 |
|---|---|---|
| jev-imagegen-test-buttons | PASS（含截图重生成，残渣已还原） | 无恙 |
| wiki-graph-seal（node 直跑） | ALL PASS | 无恙（封存契约原样） |
| secrets-ui-redesign | 7 红（R1×2/R2/R3/R5/shots×2） | **C 件重排同族存量**：旧卡锚 `jev-card/jev-detail-body/jev-head/cfg-card` 22 处命中——与案子 1 同一笔债的姊妹 spec，另批清欠 |
| settings-key-reveal | 2 红（T1/T4） | 同上：钉 `#jev-detail-body` + keyRef eye（eye 已随重排迁到 `#cfg-jev-key-value` 行），C 件同族存量 |
| sidebar-restructure | 1 红（T1 结构全等） | seal 同族存量：`ACTIVITY_ORDER_IDS` 含 `wiki-btn`（:151），封存后不成立——已知连坐，本报告案子 2 节已登记 |
| wiki-term-citation | 9/11，2 红（§T6 移动端双腿） | **独立存量**：词条二批自验 11/11 在其批树；本树（= main 尖 + 3 spec diff）复跑 2 红稳定复现（job 3ecdf9bf），失败点在其自有 harness 助手 touchMobileLayer(:425)，与本批改动零交集；何批引入待另批归因 |

结论：本批三案子修复零误伤（家族内与三案子同面板的 jev-imagegen/seal 全绿）；12 红全部为 C 件/seal 两笔既有债的姊妹 spec 或独立存量，均登记在案、未擅动。

## 交付物

| commit | 内容 |
|---|---|
| ① | tests/jev-panel-threefix.spec.mjs 锚换代（10/10） |
| ② | tests/wiki-graph-paragraph.spec.mjs §P0 双态闸 + 回归钉（双态 2/2） |
| ③ | tests/canvas-preview-nav-parity.spec.mjs harness SW 阻断（59/0/3SKIP） |
| ④ | 本报告 |
