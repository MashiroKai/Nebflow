// featureFlags.js — release gating switches (product feature flags).
//
// Friends feature (contacts panel + friend messaging) build-environment split
// (author ruling 2026-09-10, supersedes the "default off everywhere" posture
// of 2026-09-08):
//
//   - CI/CD release bundle (scripts/build-web.mjs, the esbuild chain ci.yml
//     runs before `sbt -D<lowerName>.webdist=1 assembly`): friends are
//     STRIPPED. build-web.mjs injects the compile-time marker
//     `window.__NEBFLOW_RELEASE__ = true` via esbuild `define`, so the guard
//     below folds to `if (true)` and esbuild's dead-code elimination removes
//     the dev branch — friendsEnabled() becomes a physical `return false` in
//     shipped bundles. Entries stay detached at boot (activityBar.js) and the
//     contacts/messages modules never init (no polling, no WS handlers).
//
//   - Local dev (sbt run serves the SOURCE tree directly — no esbuild, no
//     marker property): friends default SEALED (friendseal batch, author
//     ruling 2026-09-25 — the seal is the default posture; the backend latches
//     the same key at boot). `"features": { "friends": true }` in
//     <home>/nebflow.json unseals (two-state debugging); a config edit takes
//     effect on reload, an instance restart unseals the tool face too.
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
 * The release guard reads the marker expression INLINE (no intermediate
 * const): esbuild's `define` replaces the member expression textually, so
 * the bundled form is `if (true === true)` → the dev branch is folded away.
 * Dev-tree semantics: absent property → sealed (friendseal batch 2026-09-25:
 * default OFF unless explicitly `true`).
 */
export function friendsEnabled() {
  if (/** @type {Window & { __NEBFLOW_RELEASE__?: boolean }} */ (window).__NEBFLOW_RELEASE__ === true) {
    return false; // release bundle: stripped (author ruling 2026-09-10)
  }
  return state.parsedConfig?.features?.friends === true; // dev: default OFF (sealed, 2026-09-25)
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
