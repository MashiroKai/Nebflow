// featureFlags.js — release gating switches (product feature flags).
//
// Friends feature (contacts panel + friend messaging) build-environment split
// (author ruling 2026-09-10, supersedes the "default off everywhere" posture
// of 2026-09-08; RUNTIME DEFAULT OPENED by the 功能开放批， author order
// 2026-10-30 18:22 verbatim: 「生图功能以及 消息 联系功能怎么还没开放出来？
// 直接开放出来。」):
//
//   - CI/CD release bundle (scripts/build-web.mjs, the esbuild chain ci.yml
//     runs before `sbt -D<lowerName>.webdist=1 assembly`): friends ship
//     VISIBLE by default (author ruling 2026-09-14, D1②). build-web.mjs keeps
//     an opt-in re-seal switch — `node scripts/build-web.mjs --strip-friends`
//     injects the compile-time marker `window.__NEBFLOW_RELEASE__ = true` via
//     esbuild `define`, so the guard below folds to `if (true)` and esbuild's
//     dead-code elimination removes the open branch — friendsEnabled()
//     becomes a physical `return false` in that artifact. Entries stay
//     detached at boot (activityBar.js) and the contacts/messages modules
//     never init (no polling, no WS handlers).
//
//   - Local dev (sbt run serves the SOURCE tree directly — no esbuild, no
//     marker property): friends default OPEN (功能开放批 2026-10-30 — the
//     runtime seal is opt-out now, mirroring the backend latch
//     `FriendsSeal.initFromDisk`). ONLY the literal `"features": { "friends":
//     false }` in <home>/nebflow.json re-seals; an absent key (or true, or a
//     wrong-typed value) leaves the feature open. A config edit takes effect
//     on page reload; an instance restart re-seals/re-opens the tool face too.
//     安全闸原样随缺省解封恢复（好友请求-确认制 / 服务端鉴权 / 双层限速 /
//     SendConfirm 三档权限链全在常编代码路径上，friendseal 封存本就是
//     零删除条件化）——不是裸开。
//
// Flag source = the existing server config channel (no new mechanism): the
// backend serves the install config verbatim via WS `configData` →
// state.parsedConfig (WebSocketRoutes getConfig → ConfigService.getConfig
// reads <home>/nebflow.json per call; brand.conf configFileName).
//
// The decision latches once per boot on the first configData (main.js); a
// config edit takes effect on page reload — no live toggle, by design.
// Device interconnect (NebLink login / device list) is NOT gated (author
// ruling 2026-09-08: Relay-only use is shippable as-is).

import state from './state.js';

/**
 * true when the friends feature (contacts + friend messaging) is enabled.
 *
 * The release re-seal guard reads the marker expression INLINE (no
 * intermediate const): esbuild's `define` replaces the member expression
 * textually, so the bundled form is `if (true === true)` → the open branch
 * is folded away. Dev-tree semantics (post-功能开放批): only an explicit
 * `false` seals — absent key = OPEN.
 */
export function friendsEnabled() {
  if (/** @type {Window & { __NEBFLOW_RELEASE__?: boolean }} */ (window).__NEBFLOW_RELEASE__ === true) {
    return false; // --strip-friends artifact: re-sealed (opt-in build switch)
  }
  return state.parsedConfig?.features?.friends !== false; // dev: default OPEN (功能开放批 2026-10-30)
}

/**
 * TRUST_SEALED — ⑥ 信任好友（trusted-friend auto-draft）封存开关。
 * 独立 flag，默认 `true` = 封存生效 = 信任好友默认关。作者裁定 2026-09-12
 * （方案 §4.2 / §6.6：⑥-C = 模块常量形态、⑥-D = 存量标记不清、⑥-E = 不覆盖
 * release 面）。**不动** `features.friends` 默认值（见上，dev 默认 ON），代码
 * 保留不删（store / i18n 键 / CSS / 门禁链零删除）。
 *
 * 职责边界（与 friendsEnabled() 正交，禁混用）：`friendsEnabled()`（既有）管
 * 「好友功能整体的开/关与 release 剥除」；本常量只管「信任好友这一项子能力
 * 的封存」——二者是「总开关 × 子能力封印」的正交关系。组合态一句话：
 *   · friendsEnabled=false ⇒ 好友模块整体不装载（本常量不求值、也无意义）；
 *   · friendsEnabled=true 且 TRUST_SEALED=true ⇒ 好友功能照常可用，**仅**信任
 *     好友入口隐藏（好友行右键「信任此好友/取消信任」项 + 会话窗头
 *     `fm-trust-slot`）＋ 自动转发 early-return（`maybeAutoForward`）；
 *     聊天 / 收发 / 请求照旧；
 *   · friendsEnabled=true 且 TRUST_SEALED=false ⇒ 全量恢复（= 今天形态）。
 *   不存在「friendsEnabled=false 而 TRUST_SEALED=false 试图开启信任好友」的
 *   路径（前者已把模块整体摘除）。
 *
 * 读取形态 = **静态常量 import**（`contacts.js` / `messages.js`），不是配置读取、
 * 不过 `main.js` 的 `friendsEnabled` latch 链 ⇒ 无「必须刷新页面」约束。
 * **不得**把本常量写进任何会被 esbuild 常量折叠 / 剥除的表达式（会连带改变
 * release strip 判定）；release 面无需另加：好友模块整体不装载即已覆盖
 * （⑥-E）。
 *
 * 回退步骤（`TRUST_SEALED=false` 一键全量恢复，入口 + 自动转发全回来）：
 *   1) 本文件把 `TRUST_SEALED` 常量声明由 `true` 改成 `false`（一行）；
 *   2) `node scripts/build-web.mjs`（重建前端产物；dev 树直跑即生效）；
 *   3) **宿主 8080 需重启后生效**（前端改动，重启窗口由 Nebula 统一安排）。
 *
 * 封存 ≠ 删数据：`localStorage.fm_trusted` 存量标记保留不丢（⑥-D），store /
 * i18n 键 / CSS 全部保留。纯本地标记，零后端参与。
 */
export const TRUST_SEALED = true;

/**
 * VOICE_SEALED — 全双工语音（面 A）封存开关（voiceseal 批，作者令批 #508
 * 逐字：「把全双工语音的功能先封存。携带跟他同源的拖动居中语音气泡的功能
 * 一起隐藏。」）。
 *
 * 🔴 voiceseal-纠正批（批 #513，作者澄清令逐字：「怎么语音气泡直接没了，
 * 我要去掉的是hover拖动这些功能。」）：#508 把气泡整个藏了 = 理解过宽，
 * 本批纠正 flag 语义 —— **`voiceEnabled()` 只管面 A（全双工）**：
 *   · 面 A = 全双工语音（voice-mode batch）：`#voice-mode-overlay` +
 *     voiceMode.js + input.js 拖动手势入口 + voiceEngine conversation 模式；
 * 面 B（语音气泡 `#mic-orb-wrap`）**不再受本 flag 压制**——气泡默认显示
 * （纯静态可点，hover/拖动手势面已摘除），改由独立轻量开关
 * `voiceOrbEnabled()`（下）控制，二者正交。
 *
 * 形态沿用本仓封存惯例的**好友批分支**（`friendsEnabled()` 同款）：flag 源 =
 * 既有服务端配置通道，不新增机制 —— <home>/nebflow.json 键
 * `"features": { "voice": true }` 经 WS `configData` → `state.parsedConfig`
 * 原样到达前端；**缺席即封存（默认 OFF）**。main.js 在本 boot 首帧
 * configData 上一次性 latch（好友批同款：不热切换，配置改动刷新页面生效）。
 *
 * 与 `friendsEnabled()` 的**一处刻意差异**（在此申报）：好友批在 release
 * bundle 里走 `__NEBFLOW_RELEASE__` 物理剥除（esbuild define 折叠）；语音批
 * **不接** release 剥除分支 —— 封存姿态（默认 OFF）在 dev/release 一致，release
 * 产物同样可用 `"features": {"voice": true}` 恢复（封存 ≠ 剥除，可恢复优先；
 * ⑥-E 的「release 面无需另加」结论在此同样成立：默认 OFF 已覆盖 release 面）。
 *
 * 生效路径（同好友批）：
 *   · dev 树（sbt run 直供 source）：改 nebflow.json → 刷新页面即生效；
 *   · 打包态：改 nebflow.json + 重建 jar（web 资源内嵌）→ 重启生效；
 *   · 封存/恢复零数据副作用：localStorage `voice_used` 标记、orb 预设配置
 *     （orbPresets 本地档）、i18n 键、CSS、路由注册全部保留不删。
 */
export function voiceEnabled() {
  return state.parsedConfig?.features?.voice === true; // 缺席 = 封存（默认 OFF）
}

/**
 * voiceOrbEnabled — 语音气泡（面 B）轻量开关（voiceseal-纠正批 #513 新立）。
 *
 * 语义与 `voiceEnabled()` **反向**：气泡按作者令 #513 回归为**默认产品形态**
 * （可见、纯静态可点），故缺席/非 false = 显示（默认 ON）；只有显式
 * `"features": { "voiceOrb": false }` 才藏气泡（继承 #508 的隐藏能力，但与
 * 全双工解耦——藏气泡不再连带封听写等基础功能）。
 *
 * 取舍申报（任务④的两个选项中**两样都做**）：`voiceEnabled()` 收窄为只管
 * 面 A（不再压气泡）＋ 气泡另立本轻量开关。理由：#508 的作者令里「隐藏气泡」
 * 是一个真实存在过的产品意图，零删除惯例下保留一个可退出的开关比「气泡永久
 * 不可配置」更可回退；且与面 A 正交后，藏气泡不再误伤点击听写。
 * latch 时序与面 A 同帧（main.js configData 首帧，applyVoiceSeal 双参），
 * boot 期配置未到的窗口按默认 ON（气泡在位）。
 *
 * 生效路径同面 A：dev 树改 nebflow.json 刷新即生效；打包态重建 jar + 重启。
 */
export function voiceOrbEnabled() {
  return state.parsedConfig?.features?.voiceOrb !== false; // 缺席 = 显示（默认 ON）
}

/**
 * voiceOrbMountable — orb 单例**挂载判据**（#513）：气泡 flag 默认 ON，但挂载
 * 时序沿用 #508 的 **configData latch 制** —— 配置未到（首帧 configData 之前）
 * 不挂载。原因：flag 缺席 = 默认显示，若 boot 期（配置未到）就按默认值预挂，
 * 显式 `voiceOrb:false` 的页面会先建单例（GL 上下文 + 1s 轮询 + aria 接管）
 * 再被 latch 藏起 —— 单例无销毁 API，资源占用与 aria 改写都无法收回。挂载
 * 判据因此 = 「flag 默认/显式 ON **且** 配置已到达」；main.js 的 latch 在
 * flag ON 时调 initMicOrb() 完成挂载（boot 期的 initMicOrb() 调用点保留，
 * 在本判据下自然为 no-op）。#508 的两条 QA spec（micorb-settings-hidden /
 * verify-micorb-hide-qa）本就按 latch 挂载时序同步过，此处维持同一时序。
 */
export function voiceOrbMountable() {
  return voiceOrbEnabled() && state.parsedConfig != null;
}

/**
 * consoleInputMode — 控制台输入「忙时投递模式」（queuedirect 批，作者令
 * 2026-10-28 11:1x「取消我们的这个输入排队功能（不删代码）……改为，直接输入给你」；
 * 11:14 补充裁定「应该不会打断你的 llm 调用吧？效果就像是我们现在的立即发送一样」）。
 *
 * 取值（<home>/nebflow.json 的 `console.inputMode`，经既有 configData 通道到达）：
 *   · 'direct'（缺席/非法值一律按它 = **新缺省**）：busy 会话里发送的消息**立即出线**
 *     ——等价排队条上的「立即发送」：`immediateInput` 帧腿直投，引擎在当前回合的
 *     最近安全边界（工具批次完成后 / 回合末续轮）把消息并入 agent 正在进行的回合；
 *     **不取消、不打断**在飞 LLM 调用（#939 Mail 工具边界注入同源语义，作者 11:14 裁定）。
 *   · 'queue'：旧行为逐字回归——消息只入本地队列（输入框上方排队条），等当前回合
 *     done 终态后 drainMessageQueue 逐条补发。排队实现**一行不删**，本开关切回即旧行为。
 *
 * 读取形态 = 实时读 `state.parsedConfig`（send() 每次派发时求值；configData 重到即生效，
 * 无 latch——与 friends/voice 的「首帧 latch」刻意不同：本开关不挂载资源，无需 latch）。
 */
export function consoleInputMode() {
  const m = state.parsedConfig?.console?.inputMode;
  return m === 'queue' ? 'queue' : 'direct'; // 缺席/非法值 = direct（新缺省，作者令）
}

/**
 * wikiGraphEnabled — 词典文档图谱（wiki graph）**边栏入口**开关（graphseal 批，
 * 作者手写想法① 2026-10-29 逐字：「词典边栏的图谱入口暂时隐藏，因为目前感觉
 * 并没有很好用，未来可能还要优化。但是保留词条有关的各种关联、引用的功能，
 * 需要保留」）。
 *
 * 形态沿用本仓封存惯例（`voiceEnabled()` 同款）：flag 源 = 既有服务端配置
 * 通道，不新增机制 —— <home>/nebflow.json 键 `"features": { "wikiGraph": true }`
 * 经 WS `configData` → `state.parsedConfig` 原样到达前端；**缺席即封存
 * （默认 OFF）= 边栏图谱入口隐藏**。main.js 在本 boot 首帧 configData 上一次性
 * latch（好友批同款：不热切换，配置改动刷新页面生效）。
 *
 * 范围边界（申报）：只藏**边栏入口** —— activityBar.js boot 摘挂 `#wiki-btn`
 * （静态槽：Projects 与 Plugins 之间）。词条/文档的关联引用功能零触碰
 * （markdown [[wikilink]] 解析、反链、引用跳转等不经本 flag）。图谱入口面
 * 随 Canvas 改造批④（2026-10-31 作者令「去知识图谱钮」）已收缩：文件查看器
 * 「在图谱中打开」浮钮（viewers/shared.js addGraphToggle）整件退役——剩余
 * 入口 = 工作台图谱行（flagGated，本 flag 藏）与 wiki-graph 标签页深链。
 *
 * 生效路径（同好友/语音批）：
 *   · dev 树（sbt run 直供 source）：改 nebflow.json → 刷新页面即生效；
 *   · 打包态：改 nebflow.json → 刷新页面生效（配置逐字透传，无需重建 jar）。
 * 封存 ≠ 删数据/删代码：index.html 按钮静态槽、canvas.js 页签工厂与刷新注册、
 * wikiGraph.js 全部原样保留；恢复 = 配置加 `"wikiGraph": true` 一行（零改码）。
 * 不接 `__NEBFLOW_RELEASE__` 剥除分支（voiceseal 纠正批同款取舍：封存 ≠ 剥除，
 * 可恢复优先 —— release 产物同样可用配置恢复）。
 */
export function wikiGraphEnabled() {
  return state.parsedConfig?.features?.wikiGraph === true; // 缺席 = 封存（默认 OFF）
}

/**
 * PANEL_HIDE_WORKSPACE_SECTIONS — 项目面板「▶ 工作区」**入口不设置**开关
 * （hide-workspace-sections 批，作者令 2026-11-21 逐字：「git 可视化面板和项目面板
 * 的工作区都**先隐藏**（「先」=暂时隐藏，功能保留可恢复，不是删除）」；同日 12:03
 * 澄清逐字：「我要的是git可视化面板完全先封存 项目面板也不设置入口」）。
 *
 * 语义 = **UI 不出现**（条件渲染），不是数据不取、更不是代码删除：
 *   · 项目面板：项目卡内「▶ 工作区」折叠区块（`projectWorkspaces.js` 的
 *     `.project-workspaces` `<details>`）整块不挂载 ⇒ 卡上零入口；
 *     卡片其余字段（名称 / 工作区**路径**字段行 / AGENTS.md / 运行中 agent /
 *     描述）照常 —— 路径字段行与本折叠区块同名不同物，不在隐藏范围。
 *   · Git 可视化面板：**不归本开关**（12:03 澄清把该项从「藏区块」升为「整面板
 *     封存」）⇒ 见下方独立开关 `GITVIZ_SEALED`。两个开关语义彼此正交，禁混用：
 *     本开关只动项目卡上的那一个折叠区块，动不到 gitviz 面板的任何像素。
 *
 * 形态 = **模块常量**（不走配置通道，也不接 `__NEBFLOW_RELEASE__` 剥除）：
 * 作者令明说「先隐藏」⇒ 恢复点 = 改这一行为 `false` 即全量回。
 * 本仓封存惯例的取舍申报：`voiceEnabled()`/`wikiGraphEnabled()` 用 nebflow.json
 * 键是因为那是**长期**产品开关；本批是**临时**动作，配置项会把一次性动作钉进产品
 * 配置面（多一个要长期维护的键）。故取 `TRUST_SEALED`（本文件:92）的模块常量形态，
 * 读取为静态 import、无 latch 时序。
 *
 * 恢复步骤（一改即回）：
 *   1) 本常量 `true` → `false`（一行）；
 *   2) 前端产物重建（`node scripts/build-web.mjs`；dev 树（sbt run 直供 source）
 *      刷新页面即生效）；
 *   3) 打包态需重启宿主（前端资源内嵌 jar；重启窗口由 Nebula 统一安排）。
 *
 * 隐藏 ≠ 删数据/删代码：`projectWorkspaces.js` 整个模块、i18n 键、CSS
 * （projectPanel.css:1094-1106 的 `.project-workspaces*`）、后端只读端点
 * `GET /api/projects/<name>/workspaces` 全部原样保留。
 */
export const PANEL_HIDE_WORKSPACE_SECTIONS = true;

/**
 * panelWorkspaceSectionsHidden — 上常量的读取面（面板侧一律经本函数判定，
 * 不直接 import 常量：将来若改回配置驱动，只改本函数体，调用点零改动）。
 */
export function panelWorkspaceSectionsHidden() {
  return PANEL_HIDE_WORKSPACE_SECTIONS === true;
}

/**
 * GITVIZ_SEALED — 「Git 可视化」面板**整面板封存**开关
 * （hide-workspace-sections 批，作者 2026-11-21 12:03 console 逐字：
 * 「我要的是git可视化面板完全先封存 项目面板也不设置入口」）。
 *
 * 语义 = **入口全部下掉**（不是藏某个区块、也不是删面板）：
 *   · 封存后面板**仍完整存在且可用**（`gitVizView.js` 一行未删、
 *     `openGitViz()` 仍导出、CSS/数据面/问答腿全部在），只是**产品面零入口**；
 *   · 逐点盘查结论：本仓面板入口只有一处（+ 其派生重渲支路），无第二处：
 *       ① 项目卡卡头「Git 可视化」钮 `.project-gitviz-btn[data-gitviz-project]`
 *          —— 模板 `projectTab.js` 的 `projectCardHtml()`；点击 ⇒ 卡片翻转进
 *          次级面板（`openGitViz`）——**唯一**真实入口；
 *       ② 面板重渲支路 `rerenderProjectsTab()` 的
 *          `pane.dataset.projectsView === 'git-viz'` 分支 —— 只在①进入后才可能
 *          命中，且 `projectsView` 不落盘（canvas 恢复不持久化视图种类）⇒
 *          ①封住即整面板不可达，刷新/重连也不会自己回到该视图。
 *     工作台/看板 tile、侧边栏 slot、命令面板项、深链 URL：**本仓不存在**
 *     （`index.html` 只有一张 `<link>` 样式表引用，零按钮零路由）。
 *   · 后端 `gitviz_graph` / `gitviz_handoff` **工具面**与 `askOpen kind="gitviz"`
 *     是 agent 取数/交办腿，不是面板入口，不归本开关（零删除，原样保留）。
 *
 * 形态 = **模块常量**，与 `PANEL_HIDE_WORKSPACE_SECTIONS` 同款取舍（临时动作，
 * 不钉进 nebflow.json 长期产品配置面）。两个开关各自独立：翻任一个不影响另一个。
 *
 * 恢复步骤（一改即回）：
 *   1) 本常量 `true` → `false`（一行）⇒ 卡头钮回挂、点击即进面板；
 *   2) 前端产物重建（`node scripts/build-web.mjs`；dev 树刷新页面即生效）；
 *   3) 打包态需重启宿主（前端资源内嵌 jar；重启窗口由 Nebula 统一安排）。
 *
 * 封存 ≠ 删代码：`gitVizView.js` / `gitViz.css` / `GITVIZ_BTN_SVG` 图标 /
 * i18n 键 `project.gitVizOpen`（zh-CN:90, en:91）/ 数据面 `API.gitGraph`
 * 与 `GET /api/projects/<name>/git-graph` 全部原样保留。
 */
export const GITVIZ_SEALED = true;

/**
 * gitvizSealed — 上常量的读取面（同 `panelWorkspaceSectionsHidden()` 的形态约定：
 * 调用点只认函数，将来改配置驱动时零改调用点）。
 */
export function gitvizSealed() {
  return GITVIZ_SEALED === true;
}

// `outputBlockEnabled()`（output-block-gate 批 2026-11-01 的 `<output>` 用户可见块
// 特性总闸）已在 report 块可见性批（作者令 2026-11-20）**整件退役**：可见性改由
// 逐条消息的**发射会话身份**决定（帧上的 `isRoot` 正面打标），不再有「关 = 全文
// 泄漏」的回退面，也就不再有需要热读的开关。键 `outputBlock.enabled` 不再被任何
// 一侧读取（后端 `nebflow.shared.OutputBlockGate` 同批删除）。
