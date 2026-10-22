# diff 拆面板批 2026-10-22 — 摘要上移 + 左右分体（作者令，带截图证据）

作者原话：「改为在最上面看，而且是一个小的摘要，点击了之后，在左侧面板出现，diff的文件列表，在canvas显示选中文件的diff。等于是把目前的这个面板拆为了左右两个面板」

## 改后形态（对比现状 = 截图中的底部一体 master-detail 大面板）

- **摘要条（chip）**：delegate 终态交付注入气泡**顶部**渲染紧凑玻璃 pill（`.nf-diff-chip`，
  材质 = sapphire `button.glass-control` 标准族）：`N files · +X −Y`（增删着既有
  +绿/−红），折叠态即可见可点，lucide `file-diff` 图标做可点击标识；`aria-pressed`
  = 该批次是否已在面板承载（按下态 sapphire 描边）。原展开体尾部 Changes 段与标签内
  安静 hint（`· N files +a −r`）随之退役——信息一处呈现，不再两处占位。
- **左栏「变更文件」面板**：`#panel-filechanges`（`#sidebar-panel` 内注册面板，与
  files/messages/contacts 同族共存；无 Activity Bar 按钮，仅由摘要条程序化唤起）。
  行族复用 `.nf-fc`（徽标/路径/+a −r/来源 tag），长路径 RTL 自左省略，选中态 =
  背景+阴影（visual-style 铁律 5「用阴影不用竖线」），头部 meta 合计，i18n 双语
  （panel.diff / panel.diffEmpty）。
- **Canvas 阅读面**：只渲染**选中文件**的 diff（粘顶 `.fcv-filehead` + 分块渐进
  `.nf-diff`，超大 diff 全量渲染无截断、切换即卸载）；卡标题跟随焦点文件 basename。
  旧 master-detail（`.fcv` / `.fcv-head` / `.fcv-nav`）整体退役，**不在 DOM**。
- **交互语义**：chip 点击 = 打开/切换批次（左栏列表 + canvas 焦点文件）；已打开批次
  再点 = 收起侧栏（现有面板 toggle 语义，与 Activity Bar 图标再点同族）；canvas 为
  共享面，不随 chip 收敛（自带关闭钮）。
- **多委托共存**：摘要条各自挂在对应交付消息上；点谁切谁（面板换批、canvas 换焦点、
  chip 按下态易主）；canvas 侧同 preview 组恒 1 面板（preview 替换，无标签垃圾）。
- **手机层（≤768 mobile-layer）**：该层 `#sidebar` 整层退场 ⇒ 左栏面板无承载面；
  阅读面在该层**内置横向文件条**（`.fcv-filestrip`，同 `.nf-fc` 行族，JS 判
  `body.mobile-layer` 才渲染——桌面 DOM 零新增），切换能力不丢。移动层「启动自动
  展开抑制」（layoutController.watchBootAutoOpen，r1 既定：首触前 canvas-open 一律
  收起）为既有形态，旧 master-detail 同此，非本批引入。
- **数据源不变**：delegate 终态 WS 帧 / 落盘 UiMessage 的 `fileChanges` 载荷（引擎
  `FileChangeReport.delegateBatchMetadata`），live 与历史恢复走同一 builder——零数据
  迁移、零引擎改动。Flow Map 节点详情的内联阅读面（diffView.js → 节点详情）不动。

## 结构

- 新件：`js/diffPanel.js`（左栏面板面：面板 DOM/注册/列表渲染/批次切换/toggle 收口；
  **叶子模块**——静态只 import i18n.js，activityBar/diffCanvas 走动态 import，不进
  既有静态环）。
- 改件：`chat/injectedRows.js`（chip 建 DOM + 点击接线；Changes 段与 hint 摘除）、
  `diffCanvas.js`（master-detail 退役 → 单文件阅读面 + 标题跟随 + 手机层文件条）、
  `diffRender.js`（chat 链接行形态随唯一消费者退役，节点详情面原样）、`chat.js`
  （FE 组件化拆分遗留的两条死 import 摘除）、`main.js`（initDiffPanel boot 挂点，
  时序在 initActivityBar 后——持久化的陈旧面板 id 先行回落 files）、`diffView.css`
  （退役规则摘除 + chip/面板/文件条样式，全部既有 token 零新色值）、locales ×2。

## 证据（shots/，`node tests/diff-summary-split-shots.mjs`，25/25 PASS）

- `1-chip-<dark|light>.png` — **态① 摘要条**：折叠态交付气泡顶部 chip
  （`5 files +351 −140`，图标+着色计数）；旧 hint 零残留。
- `2-panel-canvas-<dark|light>.png` — **态②+③ 同帧**：左栏面板激活（5 行，
  首行选中，meta 合计）+ canvas 阅读面（焦点文件 diff）；旧 `.fcv/.fcv-nav` 不在 DOM。
- `3-canvas-<dark|light>.png` — **态③ 特写**：canvas 只剩选中文件阅读面。
- `4-switch-batch-<dark|light>.png` — **多委托切换**：双交付气泡各挂 chip；面板换
  批次 B（2 行）、canvas 换焦点、面板恒 1；chip 按下态随批走（false/true）。
- `5-recollapse-<dark|light>.png` — **再点收起**：侧栏收起、canvas 保留（共享面）。
- `6-mobile-cards.png` — **手机层**：mf-cards-live 卡片层 + 阅读面内置文件条
  （5 pills 横滚）+ 点条切换焦点（标题跟随 diffPanel.js）。
- 断言清单 = 本进程 stdout（25 PASS：亮/暗 × ①②③④⑤⑥ + 手机 ×3）。

## 回归

- `chat-diff-canvas.spec.mjs` **重写为换代规格**（6/6 PASS）：T1 摘要条渲染 + 标签
  hint 摘除；T2 气泡内零 diff 面；T3 点 chip → 左栏面板 + canvas 联动（行/选中态/
  meta/焦点文件）；T4 旧 master-detail 不在 DOM（.fcv/.fcv-head/.fcv-nav = 0）；
  T5 20000 行 diff 全量渲染（行数精确 + 末行在场）；T6 降级原因行保留 +
  batch-too-large 零出现；T7 面板行切换即卸载上一 diff；T8 多委托切换（面板换批/
  canvas 换焦点/chip 按下态易主/面板恒 1）；T9 再点收起 ↔ 复原；T10 暗亮两态构造性
  证明（filehead 1px 实线、chip 1px 描边胶囊——次档 = 透明底+描边的本代玻璃语言）。
- 邻族：`chain-detail-diff`（节点详情面不回归）、`badge-injected-turn`、
  `bgagent-injected-bubbles` —— 21/21 passed。
- checkJs：**257 = 基线 257**（新件 diffPanel.js 零错误）。
- check-circular：HEAD 既有 SCC[3]（popArtifacts/reference/msgQuote）原样；**本批净删
  SCC[8]**（原 8 节点环 chat/injectedRows→diffCanvas→canvas→activityBar→sidebar→chat，
  改道 diffPanel 动态 import 后断链）——门禁输出较 HEAD 严格改善。
- `npm run build`：179 sources 全量入图（orphans: 0），diffPanel.js 正常打包。

## 已知边角（记录不动，本批不做）

- Canvas 居中标题胶囊对 7 字符 basename（`main.js`）偶发挤压成 `mai…`——胶囊量宽
  顺序的全站 chrome 现象（所有画板卡共用；旧标题 `Diff · 5 files` 挤压更甚）；
  全路径/增删/来源在粘顶文件头恒可见。修胶囊 = 顺手改别的，未动。
- 手机层首触前 chip 打开的卡片会被「启动自动展开抑制」收起（r1 既定口径，旧
  master-detail 同此）；真实用户先摸过移动层后不受影响。
