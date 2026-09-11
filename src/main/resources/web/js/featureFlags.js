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
//     marker property): friends default ON so the entries are visible and
//     debuggable out of the box. `"features": { "friends": false }` in
//     ~/.nebflow/nebflow.json turns them off (two-state debugging).
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
 * Dev-tree semantics: absent property → dev defaults (ON unless explicitly
 * `false`).
 */
export function friendsEnabled() {
  if (/** @type {Window & { __NEBFLOW_RELEASE__?: boolean }} */ (window).__NEBFLOW_RELEASE__ === true) {
    return false; // release bundle: stripped (author ruling 2026-09-10)
  }
  return state.parsedConfig?.features?.friends !== false; // dev: default ON
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
