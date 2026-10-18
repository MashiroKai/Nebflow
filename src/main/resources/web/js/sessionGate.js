// sessionGate.js — 同源鉴权拒绝（401/403）的统一可见面 + 自愈腿。
//
// 为什么必须有（#520 取证批 2026-10-18：作者 22:38 实报「图片我还是点不动」，
// 前一批 #503 的「429 风暴窗 + 陈旧页」结论已被现役证据推翻）：
//
//   控制台可以经 relay 源（neblink.*）同源服务（冷启动门户回投的既有形态）。
//   该形态下浏览器的**有效凭据**是 relay 侧短期会话 cookie（link-server
//   `neblink_session`，ACCESS_TOKEN_TTL_SECS = 15min，HttpOnly），而控制台前端
//   **从不调用** `/api/auth/refresh`（全 web/ 零调用点，grep 已证）⇒ 15 分钟后
//   每个 same-origin `/api/*`（nf-ticket 铸票、插件面、项目列表…）都被 relay
//   门的 `require_user` 裸 403（**不落隧道帧**，conn-guard tunnelReq 平线可证），
//   而早已建立的 WS 照常工作 ⇒ 用户感知 = 「点了零反应 + 一部分面板永远要登录」，
//   ws.js 的 showTicketEntry（WS 掉线才触发）对「WS 活着 + HTTP 403」这一形
//   完全致盲。
//
// 本模块就是那个盲区的统一收口：首个 401/403 ⇒ **可见横幅**（永不静默）⇒
// 短延迟 `location.reload()`——
//   · relay 源：reload 重新走进 console_shell 的登录腿，长会话（30d refresh
//     cookie / Logto 30d `_session`）在位时**静默重铸** 15min 票，控制台恢复；
//   · 本地源：reload 落到既有已设计的未登录卡（ws.js showTicketEntry 门）。
//   两种落点都是**已设计状态**，好过静默死亡。
//
// 防循环：单页生命周期单飞（`fired`）+ 60s 冷却（sessionStorage，跨 reload
// 存活 ⇒ reload 后若立刻再 403 也不会连环刷）。零 import：nfTicket 的导入纪
// 律（只碰 branding）不因本模块破坏，i18n/modal 的依赖环一概不沾。

/** 冷却键（sessionStorage 字面量，同 ws.js `nebflow_ws_remint_at` 先例：
 *  页面会话域、非持久状态，不进 branding 命名空间）。 */
const COOLDOWN_KEY = 'stand_auth_gate_at';
/** 冷却窗：跨 reload 也生效的最小重触发间隔。 */
const COOLDOWN_MS = 60000;
/** 横幅可见到 reload 的留窗（给作者读到文案）。 */
const RELOAD_DELAY_MS = 1500;

let fired = false;
/** 测试缝（specs 换掉 reload，断言「自愈腿被拉动」而不真导航）。 */
let reloadImpl = () => window.location.reload();

/**
 * 汇报一次同源鉴权拒绝。首个调用拉起可见横幅 + 自愈 reload；同页后续调用
 * 与冷却窗内的调用一律无害化（幂等）。
 * @param {string} source 触发面读数（仅进横幅 data 属性，非行为分支）
 */
export function noteAuthRejected(source) {
  if (fired) return;
  fired = true;
  let last = 0;
  try { last = Number(sessionStorage.getItem(COOLDOWN_KEY)) || 0; } catch (e) { /* 无存储域 */ }
  if (Date.now() - last < COOLDOWN_MS) return; // 冷却中：不横幅不 reload（防环）
  try { sessionStorage.setItem(COOLDOWN_KEY, String(Date.now())); } catch (e) { /* 无存储域 */ }
  showBanner(source);
  setTimeout(() => { try { reloadImpl(); } catch (e) { /* 测试缝缺席等 */ } }, RELOAD_DELAY_MS);
}

/** 可见过期横幅（内联样式零依赖；一次性，reload 即随页销毁）。 */
function showBanner(source) {
  if (document.getElementById('stand-session-gate')) return;
  const bar = document.createElement('div');
  bar.id = 'stand-session-gate';
  bar.dataset.sessionGate = '1';
  bar.dataset.source = String(source || '');
  bar.setAttribute('role', 'alert');
  bar.style.cssText =
    'position:fixed;left:50%;transform:translateX(-50%);bottom:24px;z-index:2147483647;' +
    'max-width:92vw;padding:10px 18px;border-radius:10px;background:#b62324;color:#fff;' +
    "font:13px/1.5 -apple-system,'Segoe UI',sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.35);";
  bar.textContent = '登录状态已过期，正在重新进入控制台… / Session expired — re-entering the console…';
  (document.body || document.documentElement).appendChild(bar);
}

/** 测试缝：替换 reload 实现 / 复位单飞与冷却（仅 specs 使用）。 */
export function _sessionGateTestHooks() {
  return {
    setReload(fn) { reloadImpl = fn; },
    reset() {
      fired = false;
      try { sessionStorage.removeItem(COOLDOWN_KEY); } catch (e) { /* 无存储域 */ }
    },
  };
}
