// probeSurface.js — 键盘/焦点 QA 探针面（只读模块句柄登记，2026-11-01 装置批）
//
// 为什么需要这个文件（而不是让验证脚本自己 `import('/js/xxx.js')`）：
//   源树运行时 index.html 直接加载 `js/main.js`，模块 URL 就是源路径 —— 验证脚本
//   `import('/js/focusTrap.js')` 与产品自己 import 的是**同一 URL ⇒ 同一实例**，
//   所以源树形态下「读模块单例」这件事一直能成立。
//   但运行实例吃的是 jar 里的 `web-dist`（`StaticRoutes.scala` 的 BundledDist）：
//   那里**没有 `/js/` 这条路由**，模块被合进 entry chunk，跨 chunk 导出的名字还被
//   压缩成单字母别名（`Me as a`）。于是验证脚本既取不到 URL、也取不到名字，
//   `keyboard-check` 一换到 `NEBFLOW_WEB_ROOT=build/web-dist` 就报
//   `Failed to fetch dynamically imported module` —— 「键盘闸验的是源树，跑的是
//   bundle」，视觉闸已闭合的「验的 = 跑的」在键盘侧一直没闭合。
//
//   本文件把需要探针的模块句柄**登记在产品侧**：模块图由打包器维护，所以无论
//   源树（11 条 URL）还是产物（同一个 chunk 图）都拿到**产品自己用的那一份实例**，
//   不是另起一份副本。登记表只读、冻结，业务侧不得写入。
//
// 口径说明：
//   · 键 = 源路径（与验证脚本的既有写法一一对应）；值 = 该模块的**命名空间对象**。
//   · 这是**只读句柄**：不导出任何新的行为，不改任何业务状态，与既有的
//     `window.__nfMobileNav` / `window.__nfRenderGate` / `window.__showConfirm`
//     （QA 探针先例）同一性质。
//   · 台账口径：它不是 feature flag —— 没有开关，因为它没有行为副作用；关掉它
//     只会让验证面消失，等于把「验的 ≠ 跑的」重新打开。
import * as keymap from './keymap.js';
import * as keyContext from './keyContext.js';
import * as keyRules from './keyRules.js';
import * as commandPalette from './commandPalette.js';
import * as focusTrap from './focusTrap.js';
import * as explorer from './explorer.js';
import * as sidebar from './sidebar.js';
import * as memory from './memory.js';
import * as pathRules from './sidebar/pathRules.js';
import * as chatSearch from './chatSearch.js';
import * as socialPanel from './socialPanel.js';
import * as secretsPanel from './secretsPanel.js';
import * as daemonPanel from './daemonPanel.js';
import * as lightbox from './lightbox.js';

/** 登记表版本：表结构变化时 +1，验证脚本据此拒绝「表在但不是这一版」。 */
export const PROBE_SURFACE_VERSION = 1;

/** 全局键名（验证脚本 scripts/lib/probe-surface.mjs 同名）。 */
export const PROBE_SURFACE_GLOBAL = '__nfProbeSurface';

/**
 * 安装探针面。幂等（重复调用只替换成同一份句柄）。返回登记表本身。
 * @returns {{ version: number, modules: Record<string, object> }}
 */
export function installProbeSurface() {
  /** @type {Record<string, object>} */
  const modules = Object.freeze({
    '/js/keymap.js': keymap,
    '/js/keyContext.js': keyContext,
    '/js/keyRules.js': keyRules,
    '/js/commandPalette.js': commandPalette,
    '/js/focusTrap.js': focusTrap,
    '/js/explorer.js': explorer,
    '/js/sidebar.js': sidebar,
    '/js/memory.js': memory,
    '/js/sidebar/pathRules.js': pathRules,
    '/js/chatSearch.js': chatSearch,
    '/js/socialPanel.js': socialPanel,
    '/js/secretsPanel.js': secretsPanel,
    '/js/daemonPanel.js': daemonPanel,
    '/js/lightbox.js': lightbox,
  });
  const surface = Object.freeze({ version: PROBE_SURFACE_VERSION, modules });
  /** @type {any} */ (window)[PROBE_SURFACE_GLOBAL] = surface;
  return surface;
}
