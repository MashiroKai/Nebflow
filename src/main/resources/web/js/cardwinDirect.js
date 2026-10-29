// cardwinDirect.js — 独立窗口本机直连腿（独立窗口本机加速批 2026-10-29，作者报障
// 「独立窗口页面打开有点慢，没走 localhost 加速吗」→ 升级「完全空白打不开」）。
//
// 根因链（网关日志 2026-10-29 10:44–11:06 取证）：popoutCardWindow 以**相对 URL**
// 开窗 ⇒ 弹窗 origin 跟随父页 = 官网入口 origin（link.mystand.space 隧道腿）；
// 隧道单上行（FIFO 邮箱 64 槽）被控制台轮询/多设备/SW 对账波挤满
// （「portal uplink inbox full … depth=64/64」连续刷屏；tunnel guard
// tunnel-per-ip-cap 429 同窗 238 连拒）⇒ 弹窗整页 200+ 请求（模块图 + API）在
// 拥塞管道上慢（10:45「有点慢」）⇒ 模块图挂起/失败（11:06「完全空白」——只剩
// index.html 静态壳的毛玻璃标题胶囊，main.js 永不启动）。
//
// 修法（origin 解析规则钉，docs/design/cardwin-origin-resolution-spec.zh-CN.md）：
//   本机会话（父页在官网入口 origin，且近路面已验证本机网关基址）
//     ⇒ 弹窗直接开到 `http://<host>:<port>` 本机网关（端口服务端下发，不硬编码），
//       带**经 relay 铸的一次性 console ticket** 兑换会话 —— 会话归属跟住父会话
//       （accountSub 相同、origin=relay-mint ⇒「经官网」标记不丢，绝不落新匿名会话）。
//   远端会话 / 无验证基址 / 探测·铸票任一失败 ⇒ 回落相对 URL（入口 origin，隧道腿
//     现状逐字不变 —— 「隧道闸不豁免」裁定不动）。
//
// 判定要稳：近路面环回候选不受设备登记限制（nearpath.js 恒探测
// 127.0.0.1/localhost:<port>），同机任意浏览器实例都验证命中 —— 「来源 IP 为
// 回环/本机局域网即加速」由探测的机械事实保证（网关侧看到的探测源 IP 就是回环/LAN）。
//
// 导入纪律：只依赖 nearpath/branding（零回边，不 import ws/canvas —— 与
// nearpath.js 同一条零环纪律）。

import { key } from './branding.js';
import { getNearPath } from './nearpath.js';

const REVALIDATE_TIMEOUT_MS = 2500;
const MINT_STATUS_TIMEOUT_MS = 5000;
const MINT_TIMEOUT_MS = 8000;

/** 近路结论的 sessionStorage 键（直连开窗后写，主窗刷新再收养时读回 pmOrigin）。 */
const PM_ORIGIN_KEY = key('cardwin_pm_origins');

/**
 * 「同一网关的 host 变体」判定：同 origin，或双环回 host（127.0.0.1/localhost/
 * ::1）且同端口 —— cookie 域随 host 走，环回变体之间仍跨源，但对「是否已在
 * 网关 origin」是同一台网关：相对开窗已最优（同源 cookie 会话），不拆直连计划。
 */
function sameGatewayOrigin(a, b) {
  if (a === b) return true;
  try {
    const ua = new URL(a), ub = new URL(b);
    if (ua.port !== ub.port || ua.protocol !== ub.protocol) return false;
    const loop = (h) => h === '127.0.0.1' || h === 'localhost' || h === '::1' || h === '[::1]';
    return loop(ua.hostname) && loop(ub.hostname);
  } catch (_) {
    return false;
  }
}

/**
 * 直连开窗计划（同步读数，供手势栈内同步分支用）。
 * @returns {{ base: string, kind: 'loopback'|'lan', selfId: string } | null}
 *   null = 不直连（远端会话/无验证基址/已在网关 origin）——开窗走相对 URL 现状。
 */
export function cardwinDirectPlan() {
  try {
    const near = getNearPath();
    if (!near || !near.base) return null;
    const origin = new URL(near.base).origin;
    if (sameGatewayOrigin(origin, location.origin)) return null;   // 已在网关（含环回 host 变体）
    if (!/^http:/.test(origin)) return null;       // 网关无证书，https 基址必败（nearpath 同判据）
    return { base: near.base.replace(/\/+$/, ''), kind: near.kind, selfId: near.selfId || '' };
  } catch (_) {
    return null;
  }
}

/**
 * 基址再验证（导航前最后一道闸；防近路记录陈旧——网关换端口/已关停时
 * 弹窗绝不落死端口错误页）。
 *  ① nf-reach 204 + 品牌自证头 ⇒「一个 stand 网关」可达；
 *  ② 环回基址加身份锚：/api/health 的 deviceId（环回腿才并键）与近路面
 *     selfId 等值 —— 防「同机另一实例占同端口」错认。LAN 基址无 CORS 头
 *     （probeCorsHeaders 只开环回腿）⇒ 读不到 deviceId ⇒ 单锚放行（地址清单
 *     本就来自我们网关的 nearpath 端点，与 ws.js LAN 跳腿同信任级）。
 * @param {{ base: string, kind: 'loopback'|'lan', selfId: string }} plan
 * @returns {Promise<boolean>}
 */
async function revalidateNearBase(plan) {
  try {
    const r = await fetch(`${plan.base}/api/nf-reach`, {
      cache: 'no-store', signal: AbortSignal.timeout(REVALIDATE_TIMEOUT_MS),
    });
    if (!(r.ok || r.status === 204)) return false;
    const lower = (/** @type {any} */ (window).__BRAND__?.lowerName) || 'stand';
    if (r.headers.get(`x-${lower}-gateway`) !== '1') return false;
  } catch (_) {
    return false;
  }
  if (plan.kind !== 'loopback' || !plan.selfId) return true;
  try {
    const h = await fetch(`${plan.base}/api/health`, {
      cache: 'no-store', signal: AbortSignal.timeout(REVALIDATE_TIMEOUT_MS),
    });
    if (!h.ok) return false;
    return h.json().then((j) => j && j.deviceId === plan.selfId).catch(() => false);
  } catch (_) {
    return false;
  }
}

/**
 * 经 relay 铸一次性 console ticket（会话归属桥：兑换出的会话 accountSub =
 * 父会话账号、origin=relay-mint ⇒「经官网」标记跟住）。与 ws.js 近路跳腿的
 * 铸票调用同形（portal/status 给 deviceId 锚 → portal/console-ticket 铸票 →
 * 从 consoleUrl 取票）；直连腿页面没有 /api/portal 面（404 短路）= 不铸 = 回落。
 * @returns {Promise<string|null>} ticket
 */
async function mintConsoleTicket() {
  try {
    const status = await fetch('/api/portal/status', {
      credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(MINT_STATUS_TIMEOUT_MS),
    });
    if (!status.ok) return null;
    const deviceId = (await status.json())?.boundHost?.deviceId;
    if (typeof deviceId !== 'string' || deviceId === '') return null;
    const mint = await fetch('/api/portal/console-ticket', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId }),
      signal: AbortSignal.timeout(MINT_TIMEOUT_MS),
    });
    if (!mint.ok) return null;
    const consoleUrl = (await mint.json())?.consoleUrl;
    if (typeof consoleUrl !== 'string' || consoleUrl === '') return null;
    return new URL(consoleUrl, location.href).searchParams.get('ticket') || null;
  } catch (_) {
    return null;
  }
}

/** 直连会话的 opener origin 记账（tabId → origin；主窗刷新再收养时读回）。 */
export function rememberCardwinPmOrigin(tabId, origin) {
  try {
    const all = JSON.parse(sessionStorage.getItem(PM_ORIGIN_KEY) || '{}');
    all[tabId] = origin;
    // 有界：只留最近 8 条（弹窗是低频动作，防长期累积）。
    const keys = Object.keys(all);
    if (keys.length > 8) for (const k of keys.slice(0, keys.length - 8)) delete all[k];
    sessionStorage.setItem(PM_ORIGIN_KEY, JSON.stringify(all));
  } catch (_) { /* 内存态即可 */ }
}

/** 读回直连会话的 opener origin（主窗刷新后 hello 收养腿 / 无 sessionStorage 形态给 ''）。 */
export function readCardwinPmOrigin(tabId) {
  try {
    const all = JSON.parse(sessionStorage.getItem(PM_ORIGIN_KEY) || '{}');
    return typeof all[tabId] === 'string' ? all[tabId] : '';
  } catch (_) {
    return '';
  }
}

export function forgetCardwinPmOrigin(tabId) {
  try {
    const all = JSON.parse(sessionStorage.getItem(PM_ORIGIN_KEY) || '{}');
    if (all[tabId] !== undefined) {
      delete all[tabId];
      sessionStorage.setItem(PM_ORIGIN_KEY, JSON.stringify(all));
    }
  } catch (_) { /* 内存态即可 */ }
}

/**
 * 执行直连开窗导航（popoutCardWindow 已同步预留好空白窗之后调）。
 * 链：基址再验证 → 铸票 → 导航 `<base>/<深链>&ticket=…&cwpo=…&cwdir=1`；
 * 任一环失败 / 窗已被用户关掉 ⇒ 导航回落相对 URL（= 改动前行为，隧道腿现状）。
 *
 * 时延账：环回再验证 ~1-5ms + 铸票一拍（经 relay，~100-500ms）⇒ 占位窗可见
 * 「正在打开」~零点几秒，对比隧道整页加载（拥塞时秒级～挂起）为数量级改善。
 *
 * @param {Window} win 已预留的窗（about:blank，同源可写占位）
 * @param {{ base: string, kind: 'loopback'|'lan', selfId: string }} plan cardwinDirectPlan() 读数
 * @param {string} fallbackUrl 相对深链（'/?card-window=1&path=…&title=…'）
 * @param {string} tabId 主窗侧卡 id（PM origin 记账键）
 * @returns {Promise<boolean>} true = 直连导航已发起
 */
export async function navigateCardWindowDirect(win, plan, fallbackUrl, tabId) {
  const t0 = (window.performance && performance.now()) || Date.now();
  // 占位面：about:blank 同源可写 —— 铸票期间非白屏（复用主窗视觉语言，内联零依赖）。
  try {
    if (!win.closed) {
      win.document.open();
      win.document.write(
        '<!doctype html><meta charset="utf-8"><title>…</title>' +
        '<body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;' +
        'background:#f6f7f9;font:13px/1.6 -apple-system,\'Segoe UI\',sans-serif;color:#6a737d">' +
        '<div style="text-align:center"><div style="font-size:22px;margin-bottom:8px">⧉</div>' +
        '正在打开独立窗口…</div></body>'
      );
      win.document.close();
    }
  } catch (_) { /* 占位失败不拦主链 */ }
  const fail = async (why) => {
    console.info(`[cardwin] direct-open fallback: ${why}`);
    // 记账随回落清账：弹窗已落回入口 origin（同源 = BC 承载通道；PM 桥腿对
    // 本机 origin 的投递天然不达，留着只会在再收养腿误导）。
    forgetCardwinPmOrigin(tabId);
    try { if (!win.closed) win.location.href = fallbackUrl; } catch (_) { /* 窗已失控 */ }
    return false;
  };
  if (win.closed) return fail('window-closed');
  if (!(await revalidateNearBase(plan))) return fail('revalidate-miss');
  if (win.closed) return fail('window-closed');
  const ticket = await mintConsoleTicket();
  if (!ticket) return fail('mint-miss');
  if (win.closed) return fail('window-closed');
  try {
    // 深链相对形式（'/?card-window=1&path=…'）必须解析到**本机网关基址**而非
    // 当前页（入口）origin —— 第二参是基址，不是无谓参数。
    const u = new URL(fallbackUrl, plan.base + '/');
    u.searchParams.set('ticket', ticket);
    u.searchParams.set('cwpo', location.origin);   // PM 桥对端 origin（卡窗侧钉定用）
    u.searchParams.set('cwdir', '1');              // 直连标记（网络面板/诊断可辨）
    rememberCardwinPmOrigin(tabId, new URL(plan.base).origin);
    win.location.href = u.href;
    const t1 = (window.performance && performance.now()) || Date.now();
    console.info(`[cardwin] direct-open ok base=${plan.base} kind=${plan.kind} prep=${Math.round(t1 - t0)}ms`);
    return true;
  } catch (_) {
    return fail('navigate-error');
  }
}
