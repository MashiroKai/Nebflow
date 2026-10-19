# 外部执行器任务可观测面 · 设计（两阶段纪律：只设计，零代码改动）

作者令：「外部 agent 的观测性也很差。点击进去终端没有看到任何可用的信息，我想的最好是能打开其桌面端，看到其具体运行情况。」
本件 = 现状盘点（file:line）＋ 设计面 A（详情面板 live 输出）＋ 设计面 B（打开桌面终端＋权限面）＋ 交互样张 ＋ 开放问题候拍。
样张：`reports/20261019_154300_external-executor-detail-mockup__delegate-nebflow-e0b069db.html`（自包含，双击可开）。

---

## 0. 结论速览

1. **捕获面已存在，缺的是消费面**：外部执行器 stdout/stderr 引擎已有全量捕获（进程内存环形缓冲，尾窗 256KB）＋ REST 增量读端点；缺的是详情抽屉把它露出来（任务面板外部行今日 display-only，点不开）。
2. **引擎今日从不拉起「真终端窗口」跑外部任务**：spawn 全程 headless 管道（ProcessBuilder，无 tty、无窗口、无 launchd）。作者在桌面上看到的 Terminal.app 窗 = 「在原软件打开」（open-native）按钮拉起的 **resume 交互窗**，且引擎**零设置窗口标题**——「窗口标题=会话 id」不是引擎行为，是 Terminal.app 默认（活跃进程名）/CLI 自身 OSC 输出的结果。设计面 B 的一半前置工作 = 立标题/tty 标注约定。
3. 设计面 A 几乎纯前端（复用既有 REST ＋ bgTaskOutputPopup 既有视觉/轮询语言）；设计面 B = 引擎侧 1 个新端点 ＋ open-native 模板改造 ＋ TCC 授权引导/降级。

---

## 1. 现状盘点（file:line）

### 1.1 stdout/stderr：有捕获，进程内存态，零落盘

- 选择与入口：`DelegateTool.call`（`src/main/scala/nebflow/core/tools/DelegateTool.scala:288-293`）→ `externalSpawn`（同文件 :460-510）→ `ExternalSessionRunner.spawn`（`src/main/scala/nebflow/core/executor/ExternalSessionRunner.scala:115-213`）。
- **spawn = 直接 spawn 管道（不是 launchd、不是 osascript、不是 tty）**：`buildProcess`（ExternalSessionRunner.scala:405-416）——`ProcessBuilder(binPath +: launchArgs)`，`pb.redirectErrorStream(false)`，cwd = 工作座位。子进程三根 std 管全在引擎手里，**无控制终端、无窗口**。
- stdout：`readLoop`（ExternalSessionRunner.scala:419-451）逐行 → `ExternalOutputStore.append(sessionId, line)` ＋ 适配器 `parseLine` → WS 帧（agentTextDelta / agentToolStart，emitFrame :761-784）。
- stderr：独立守护线程（:358-368）逐行加 `[stderr] ` 前缀写**同一缓冲**（错误面留痕，不入流帧）。
- 缓冲：`ExternalOutputStore`（`src/main/scala/nebflow/core/executor/ExternalOutputStore.scala:29-223`）复用 `BgTaskOutputBuffer`（`src/main/scala/nebflow/core/tools/BgTaskOutputStore.scala:147-187`）：
  - 尾窗 cap 256KB（`BgTaskOutputStore.CapBytes`，:40；`ExternalOutputStore.open` :67-84 / `resetForNewRun` :91-107 每轮新缓冲），行粒度字节游标 `snapshot(offset)`（:177-187），超限从头丢行、计数保留（`truncated` 标志）；
  - 终态 LRU 留存 20 条（`MaxRetained`，ExternalOutputStore.scala:32；finalizeTask :155-178），运行中条目永不淘汰；
  - 状态机 = running | completed | failed | blocked | cancelled ＋ exitCode/errorHint/cliSessionId/mailbox。
- **落盘：无**。全部进程内存——引擎重启即失（`ExternalOutputStore` 是 object 内 `Ref`，:64）；留存淘汰（>20 条终态）即失；引擎死后 CLI 孤儿进程的输出写进死管道（读循环已随引擎消亡）。
- REST 面（已有，勿重建）：
  - `GET /api/delegates/:id/output?offset=N`（`src/main/scala/nebflow/gateway/PresenceRoutes.scala:553-566`）——与 bg-tasks 输出面同构 ＋ 外部扩展键 `cliSessionId` / `mailboxDepth` / `startedAtMs`（externalOutputJson）；404 = 无缓冲（未在本实例跑过或留存已淘汰）。
  - `GET /api/delegates/:id`（PresenceRoutes.scala:574-592）——G4 聚合单查：record 全量 ＋ live ＋ verdict ＋ task 台账全文 ＋ childrenCount ＋ output 首窗（缓存淘汰前的首屏单缝）。

### 1.2 终端窗口怎么拉起的

- 任务执行路径：**不开窗**（见 1.1，headless 管道）。
- 唯一开窗面：「在原软件打开」`POST /api/delegates/:id/open-native`（PresenceRoutes.scala:655-687）→ `ExternalOpenNative.templateFor`（`src/main/scala/nebflow/core/executor/ExternalOpenNative.scala:45-63`）：
  - macOS：`osascript -e 'tell application "Terminal" to do script "cd '<cwd>' && <resume>"'`（`do script` = 开新窗执行；resume = `claude --resume <sid>` / `codex resume <tid>` / `zcode --resume <sid>`，interactiveResumeCommand，ExternalExecutor.scala:127-128/187-188/296-297）；
  - Windows：`cmd /c start "" cmd /k "cd /d ... && ..."`；Linux：探测 x-terminal-emulator/gnome-terminal/konsole/xterm `-e` 形态（:59-62）；
  - fire-and-forget：进程启动即回，响应 `{opened, command}` 回显（PresenceRoutes.scala:672-686）。
- **标题：引擎零 setTitle 路径**（全仓 grep `set title|custom title|windowTitle` 0 命中）。`do script` 打开的窗，标题由 Terminal.app 默认（活跃进程名）或 CLI 自身 OSC 序列决定——观察到的「external-codex-6fe4bb31」形态并非引擎标注。引擎也不知道这个窗的存在（无窗口账本）：**每次点击都开新窗、无聚焦、无复用**，同一任务多点 = 多窗。
- Apple Events 权限（今日已隐含存在）：open-native 由引擎 spawn `/usr/bin/osascript` 子进程发件。TCC「自动化」归属按 responsible process：
  - 引擎由 Terminal.app 里的 sbt 启动（本机现状，PID 39598）→ 归属 Terminal 自控 Terminal，通常免提示、直接成功；
  - 引擎由 launchd / 独立进程启动 → 首次弹「"<进程名>" 想要控制 "Terminal.app"」系统框；
  - SSH / 无 GUI 会话 → 无 TCC UI，事件恒失败（err -1743）。
  今日 open-native 对上述差异**零处理**：失败只回 400「open-native failed: …」（:681-685），面板 `console.warn` 一行了事（`src/main/resources/web/js/taskList.js:578-580`）。

### 1.3 详情面板现在渲染什么

- **任务面板外部行**（`src/main/resources/web/js/taskList.js:464-601`）：图标 ＋ #任务号 ＋ 标题 ＋ **尾行 meta**（`progressTailText`，输出缓冲最后一条非空行，:769-777；3s 增量轮询 :792-855，no-buffer 15s 退避）＋ 状态词 ＋ 时间 ＋ open-native 钮（:563-584）。**display-only：`clickable = openable || (!external && !queued)`（:474-475）——external 行没有 click listener，点不开，无任何详情**。
- **agent 弹窗**（`src/main/resources/web/js/bgAgentPopup.js:139-268`，mountDelegateStrip，G4 数据缝）：brief 摘要/全文 ＋ plugins ＋ 判词 ＋ 报告全文。`agentTextDelta` 帧只把状态翻成 responding（:730-731），**增量文本不进视图**——运行中弹窗里看不到任何 live 输出、看不到 stderr、看不到退出码。
- 对照物：后台任务（bg-tasks）已有完整输出详情卡 `bgTaskOutputPopup.js`（作者 2026-09-09 同类需求「点进去看具体输出」的产物）：毛玻璃详情卡 ＋ 终端化内芯 ＋ 2s 轮询 ＋ 滚动跟随/上滚暂停 ＋ 复制钮 ＋ 退出码/错误行 ＋ 截断徽章 ＋ 终态留存回看 ＋ 失败预算防卡死（:24-39）。**外部 delegate 没有对应物——这就是作者体感「点进去什么都没有」的根源**。

**差距一句话**：数据缝全在（缓冲/REST/字节游标），缺一张详情卡；窗口面只有「新开」没有「聚焦」。

---

## 2. 设计面 A · 详情面板 live 输出

### 2.1 入口与信息架构

- **外部行可点**：taskList.js `clickable` 放开 external（点开 = 详情抽屉）。形态取**独立详情卡**（bgTaskOutputPopup 同族 overlay+modal，视觉/交互语言全套复用）；并入 L1 flowmap 节点详情为 Phase 2 候选（开放问题 §6.7）。
- 卡结构（对齐 bgt 面板视觉语言，样张逐件可看）：
  - **Header**：executor 图标（executorIcons.js 既有 `executorIcon()`）＋ title ＋ mono 副行 `external-codex-6fe4bb31` ＋ 状态词 chip（终态分色同 `.bgt-state-label--done/--failed`：中性字，红由词前图标承载——S2 状态词纪律）。
  - **元信息数据带**（Header 下、输出区上的一行 chip 族）：`exit 0` / `exit 1` / 运行中「—」；startedAt·时长；`bytes / lines`（fmtBytes/fmtLines 同款）；`仅尾窗` 截断徽章（truncated=true）；`cli: <cliSessionId>`；`待消化 N`（mailboxDepth>0）；executor 名。
  - **输出内芯**：`pre` 终端化内芯（light `#f5f5f5` / dark `#111419`，等宽 12px，与 `.bgt-output` 同源值）；`[stderr] ` 前缀行降红显示（**仅色不改文本**——textContent 纪律下按行前缀切 class）；空态占位「暂无输出」；`.code-copy-btn` hover 浮现复制（bgt 同款几何与玻璃主题，`.bgt-body > .code-copy-btn` 覆盖位同法）。
  - **Footer**：跟随态指示（`跟随中` / `已暂停——回到底部恢复`，语义同 bgt/msgScrollAnchor：输出增长时底部附近自动跟随，用户上滚即暂停）＋ 动作钮族：`复制全部`、`聚焦桌面终端`（设计面 B）、`在原软件打开`（open-native，聚焦未命中/未授权时的兜底）、运行中另有 `取消任务`（`ExternalSessionRunner.cancel` 既有面）。

### 2.2 数据与轮询（复用既有缝，逐字）

- 首绘：G4 单查 `fetchDelegateDetail`（delegateApi.js:99-107）→ record/verdict/task ＋ output 首窗；`output: null` → 卡内诚实降级注记「无输出缓冲（未在本实例运行或留存已淘汰）」，记录面照常渲染（brief/verdict/report 仍可达）——404 不是错误态。
- 增量：`fetchDelegateOutput(id, offset)`（delegateApi.js:61-69）2s 轮询，持守 `nextOffset` 字节游标；`exitCode` 到达（终态）→ 停轮询转单次回看模式；连续失败预算 5 次（~10s）终局报错（bgt :24-39 同款防「卡在那」）；单次 fetch 8s 超时（AbortController）。
- **断线重连**：字节游标无状态——WS 断连/卡片重开 = offset 0 重拉全窗，`仅尾窗` 徽章兜底说明；轮询失败保留上次读数不清空（行腿不因单次失败崩，taskList.js:816-822 同纪律）。
- **XSS 红线**：动态文本一律 `textContent`，骨架 `innerHTML` 仅静态结构（bgt :16 同款）。
- 行级联动：任务行尾行 meta 升级为可点开抽屉的链接；抽屉打开期间该行的行级轮询（`ensureDelegateProgressLoop`，taskList.js:843-855）让位（cursor 由抽屉持有），防双拉放大。

### 2.3 捕获增强（候选，默认不实施，开放问题 §6.1）

现状（内存缓冲 ＋ REST）已够 Phase 1。若要「引擎重启可回看 / 留存淘汰可回放」：

- **机制**：readLoop 逐行 append 处同步双写磁盘 tee——`<dataRoot>/external-output/<sessionId>/run-<seq>.ndjson`，行格式 `{"ts":ms,"stream":"out|err","line":"…"}`（sessionId 已过 `DelegateRegistry.validId` 白名单，路径安全）。
- **旋转**：单 run 文件 5MB cap（超限滚动 `run-<seq>.part`）；每会话保最近 5 个 run；30 天清扫（boot sweep 顺带）。上界 ~20 会话 × 5 run × 5MB = 可控。
- **读面**：`GET /api/delegates/:id/output?offset=N&replay=1` 读盘补内存缺（404 时先查盘）；内存面主路径零改。
- **成本**：落盘面、清理债、ACl（0600 同 secrets 族纪律）。**默认不做**，作者拍板后另批。

---

## 3. 设计面 B ·「打开桌面终端」（聚焦已有窗口）＋ 权限面

### 3.0 前置事实（盘点 §1.2 的直接推论）

spawn 是 headless 的——**运行中的外部任务在桌面上没有可聚焦的窗口**；能聚焦的只有 open-native 开过的 resume 交互窗。因此「聚焦桌面终端」的面 = 三段：窗口标注约定（让窗口可被找到）→ 聚焦端点（按约定找窗前置）→ 未授权/未命中降级。

### 3.1 窗口标注约定（协议面，open-native 模板改造点）

`ExternalOpenNative.templateFor` 的 inner 脚本前置两件（macOS 形态）：

```sh
printf '\033]0;%s\007' '<sessionId>'      # ① OSC 0：窗/Tab 标题 = 会话 id（作者期望形态自此成立）
tty > '<dataRoot>/external-windows/<sessionId>.tty'   # ② 窗口真实 tty 落账（标题被 CLI OSC 覆盖后的可靠匹配键）
cd '<cwd>' && <resume>
```

- ① 让 Terminal.app 标题 = `external-codex-6fe4bb31`；CLI 起来后可能用自身 OSC 覆盖（claude/codex 都会设标题）→ ② 的 tty 回执是覆盖鲁棒的匹配键。
- 其余平台：Windows 内层加 `title <sessionId>`；Linux 同 ①（printf OSC 通用）；② 仅 macOS 写（tty 概念对应）。回执目录 `external-windows/` 随数据根（gitignored 运行时层），boot sweep 清孤儿。
- 会话 id 进脚本一律过 `DelegateRegistry.validId` 白名单 ＋ `shQuote`（ExternalOpenNative.scala:23-27 既有纪律），或改 `osascript` argv 通道（`on run argv`）彻底离引号炼狱。

### 3.2 聚焦端点（新）：`POST /api/delegates/:id/focus-native`

引擎侧流程（纯引擎本地，零前端编排）：

1. **probe**（每次卡打开至多 1 次，结果缓存）：`osascript -e 'tell app "Terminal" to count windows'`，2s 超时 → `granted`（返回数字）/ `denied`（err -1743）/ `unknown`（超时、app 未运行、非 macOS、无 GUI 会话）。
2. **匹配**（granted 时）：
   - 有 tty 回执：`every tab of every window whose tty is "<tty>"` → 命中窗 `set index of window w to 1` ＋ `activate`；
   - 无回执（旧窗/回执丢失）：`every window whose name contains "<sessionId>"` 标题兜底；
   - 未命中 → `{matched:false}`，前端降级为一键 open-native（顺带把 ①② 约定补上，下次可聚焦）。
3. **响应形状**（可判别、自带兜底料）：

```json
{ "focused": true, "matched": true, "permission": "granted",
  "fallback": { "openNative": true }, "script": "<等价 osascript 全文>" }
```

`script` 字段 = 等价 AppleScript 全文——未授权/未命中时前端「复制 AppleScript」按钮的直接数据源（作者可贴进 Terminal 手动执行，不依赖授权面）。

### 3.3 权限引导流（TCC 自动化授权）

- **首触**：系统弹「"<引擎进程名>" 想要控制 "Terminal.app"」→ 允许 = 之后静默成功；拒绝/关掉 = 后续事件恒 -1743。
- **denied 引导 payload**（引擎随 403/`permission:denied` 返回）：`{permission:'denied', appName:'Terminal', steps:[系统设置 → 隐私与安全性 → 自动化 → <引擎> → 允许控制 Terminal.app], deepLink:'x-apple.systempreferences:com.apple.preference.security?Privacy_Automation', script:<全文>}`。
- **前端降级态**（样张「未授权降级」开关可看；对齐现有面板语言）：
  - 引导卡替换聚焦钮态：`circle-alert` 图标（红由图标承载，正文中性字）＋ 三步指引（locale 键族 `delegate.focus.*`）；
  - 按钮族：`打开系统设置`（deepLink）｜`复制 AppleScript`｜`在原软件打开`（open-native 兜底）；
  - 授权后无需刷新检测：重试钮重新 probe。
- **headless 部署（SSH/无 GUI）**：probe `unknown` → 面板注明「宿主无 GUI 会话，桌面聚焦不可用」恒降级；probe 结果缓存防重试轰炸（每卡开合至多 1 次）。
- **授权归属说明进设计文档**（也写进引导卡 footnote）：Terminal 内 sbt 启动的引擎（本机现状）TCC 归 Terminal 自控、通常免提示；launchd 启动才见弹窗——同一代码不同启动方式行为不同，降级面是常态依赖而非异常路径。

### 3.4 防御面

- 会话 id 进 AppleScript：validId 白名单 ＋ argv 通道（3.1）；cwd 沿 `shQuote`。
- fire-and-forget 同 open-native（不等窗口前置完成）；后台 fiber 收尸防僵尸。
- 聚焦钮只进抽屉 footer，不进任务行（防误触弹 Mac 窗到前台）。

---

## 4. 交互样张

`reports/20261019_154300_external-executor-detail-mockup__delegate-nebflow-e0b069db.html`（自包含零依赖，浅/深双主题跟系统）：

- 任务面板：1 内部 delegate 链行 ＋ 1 外部运行中行 ＋ 1 外部终态行（对照现状 display-only 与新可点态）；
- 点外部行 → **详情抽屉完整态**：live 流（模拟逐行追加、跟随/上滚暂停、滚底恢复）＋ `[stderr]` 降红 ＋ 元信息带（exit code/时长/bytes/lines/仅尾窗/cli/待消化）＋ Footer 三钮；
- **未授权降级态**（页内演示开关）：TCC 引导卡 ＋ deepLink/复制 AppleScript/open-native 兜底；
- 终态态：exit 0 徽章 ＋ 停轮询回看 ＋ 复制全部；
- 视觉 token 取 sapphire.css/bgTaskOutputPopup 同名同值（--glass-bg/--glass-blur/--glass-border/顶缘蓝宝石折射线/终端内芯双色/4px 细滚动条/code-copy-btn hover 浮现）。

---

## 5. 实施切分（候拍后另批，本批零代码）

- **Phase 1**（本设计主体）：前端详情抽屉（纯消费既有 REST）＋ 外部行可点 ＋ i18n 键（`delegate.detail.*`/`delegate.focus.*` 族）；引擎侧仅 ExternalOpenNative 标题/tty 约定（§3.1）＋ `focus-native` 端点（§3.2）。
- **Phase 2**（候选）：磁盘 tee 回放（§2.3）、xterm.js 内嵌终端（引擎 pty 中继）、L1 flowmap 节点详情并入、帧视图（JSONL 噪声过滤）。

---

## 6. 开放问题清单（候作者拍板）

1. **磁盘 tee**（§2.3）：要不要落盘＋旋转？默认 Phase 1 不做（内存面已够），做则按 5MB×5run×30 天上界。
2. **聚焦匹配键优先级**：tty 回执（可靠，需写 `external-windows/` 账）优先、标题 contains 兜底——是否接受落账文件？
3. **未命中行为**：设计按「面板内一键 open-native、不自动开窗」；备选 = 未命中直接自动开新窗（一步到位但窗口失控风险）。
4. **内嵌终端复刻 vs 桌面聚焦**（作者令方向的对偶）：内嵌（xterm.js ＋ 引擎 pty 中继）窗口无关、NebLink 远端会话只有这条路能看；推荐 Phase 2 先做远程只读 tail（本设计 A 面），内嵌交互终端再后。
5. **stderr/噪声**：`[stderr]` 仅降红够吗？claude stream-json 的 JSONL 在 tail 很密，候「帧视图」（解析面已有 parseLine 四帧词汇，可复用）Phase 2。
6. **任务行尾行 meta 升级为可点链接**开抽屉——设计按是；备选保持纯文本、只有整行可点。
7. **抽屉形态**：独立详情卡（本设计，bgt 同族）vs 并入 L1 flowmap 节点详情（链行同窗）。样张按前者。
8. **focus-native 与 open-native 的钮面合并**：设计按抽屉内一钮「聚焦桌面终端」智能态（先聚焦、未命中/未授权露出 open-native 兜底），实现拆两端点。

---

## 附：本件引用面速查

| 面 | 位置 |
|---|---|
| spawn（headless 管道） | ExternalSessionRunner.scala:405-416（buildProcess）、:419-451（readLoop）、:358-368（stderr 线程） |
| 输出缓冲 | ExternalOutputStore.scala:29-223；BgTaskOutputStore.scala:147-187（Buffer）、:39-43（cap/留存） |
| REST 输出面 | PresenceRoutes.scala:553-566（/output）、:574-592（G4 单查） |
| open-native | PresenceRoutes.scala:655-687；ExternalOpenNative.scala:45-63（模板）、:23-27（白名单/转义） |
| 任务行（display-only） | taskList.js:464-601（:474-475 clickable）、:754-855（尾行轮询） |
| agent 弹窗（无输出面） | bgAgentPopup.js:139-268（strip）、:730-731（delta 仅翻状态） |
| 视觉语言母版 | bgTaskOutputPopup.js:1-16（语言声明）、:59-230（CSS）；sapphire.css:37-62（玻璃 token） |
| 前端 API 绑定 | delegateApi.js:30-43（端点表）、:61-107（output/detail） |
