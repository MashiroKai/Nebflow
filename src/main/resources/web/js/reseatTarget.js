// reseatTarget.js — 整页重座（silent reseat）导航目标解析（弹窗回跳批 2026-11-05）
//
// 作者报障（2026-11-05 09:23 逐字）「现在怎么老是有弹窗然后重新进控制台？」，
// 证据截图 = 一个独立浏览器窗口停在入口面「Entering your console…」且地址栏已是
// 控制台 URL、加载条未走完（= 一次**自动**整页导航在途）。
//
// 根因（本文件负责的那一段）：控制台里有三条「整页重进」腿，全部把
// **consoleUrl / 本机票 URL 原样**丢给 location.href ——
//   · ws.js remintHostSessionAndReload（WS 握手被拒的自愈重座）
//   · ws.js 环回近路跳（relay 腿 → 127.0.0.1）
//   · ws.js LAN 近路跳（relay 腿 → LAN 地址）
// 而这三条 URL 都不带卡窗契约（`card-window=1&path=…&title=…`）⇒ 独立卡窗
// （popoutCardWindow 开出来的那个 `popup=yes` 弹窗）一旦遇到握手被拒，就被重座
// 导航**升级成一整个控制台**：弹窗还在，但里面已经是完整控制台 = 作者看到并
// 报障的「弹窗然后重新进控制台」。副产物：同一浏览器由此多出一个控制台实例
// （两者抢 WS → 接管模态 → 再 reload），即「老是」的节奏。
//
// 判据与修法：重座是「把会话换新、页面重进一次」，**不是**「换个页面」——
// 所以卡窗模式在重座全程必须守恒。非卡窗页面逐字不变（零回归面）。
//
// 模块纪律：叶子（零 import，不依赖 ws/canvas），纯逻辑核 + 极薄读取面，
// 供 tests/reseat-target.test.mjs 直接跑。

/** 卡窗契约参数名（与 canvas.js popoutCardWindow / index.html 门脚本同源拼法）。 */
const CARD_FLAG = 'card-window';
const CARD_PATH = 'path';
const CARD_TITLE = 'title';

/**
 * 读当前页的重座上下文（纯函数：只吃入参，不碰 DOM/URL）。
 * @param {string} search 当前页的 location.search（含 '?'）
 * @returns {{cardWindow: boolean, path: string, title: string}}
 */
export function readReseatContext(search) {
  const ctx = { cardWindow: false, path: '', title: '' };
  let q;
  try {
    q = new URLSearchParams(typeof search === 'string' ? search : '');
  } catch (_) {
    return ctx;                    // 不可解析 ⇒ 非卡窗（现状腿）
  }
  ctx.cardWindow = q.get(CARD_FLAG) === '1';
  ctx.path = q.get(CARD_PATH) || '';
  ctx.title = q.get(CARD_TITLE) || '';
  return ctx;
}

/**
 * 解析重座导航目标：卡窗模式在重座全程守恒。
 *
 * 规则（每条都可由 tests/reseat-target.test.mjs 机械判定）：
 *   ① 非卡窗 / 无上下文 ⇒ **原样返回 target**（relay 腿与本地直连腿逐字不变）；
 *   ② 卡窗 ⇒ target 上补回 `card-window=1`，并补回 `path` / `title`（有才补）；
 *      target 已带的 `ticket` 等参数一律不动（票是这次重座的凭据）；
 *   ③ 卡窗但 path 缺失（契约不完整）⇒ 仍只补 `card-window=1`：宁可停在卡窗
 *      自己的引导/失败面上，也绝不悄悄退化成整控制台；
 *   ④ target 不可解析成 URL ⇒ 原样返回（调用方拿到的仍是现状值）。
 *
 * @param {string} target consoleUrl / 本机票 URL
 * @param {{cardWindow: boolean, path: string, title: string}} ctx 见 readReseatContext
 * @param {string} base 解析相对 target 用的基址（调用方传 location.href）
 * @returns {string} 要导航到的绝对 URL（或原样 target）
 */
export function resolveReseatTarget(target, ctx, base) {
  if (typeof target !== 'string' || target === '') return target;
  if (!ctx || !ctx.cardWindow) return target;          // ① 现状逐字
  let u;
  try {
    u = new URL(target, base || undefined);
  } catch (_) {
    return target;                                     // ④ 不可解析：现状
  }
  u.searchParams.set(CARD_FLAG, '1');                  // ②③ 卡窗守恒
  if (typeof ctx.path === 'string' && ctx.path !== '') u.searchParams.set(CARD_PATH, ctx.path);
  if (typeof ctx.title === 'string' && ctx.title !== '') u.searchParams.set(CARD_TITLE, ctx.title);
  return u.toString();
}
