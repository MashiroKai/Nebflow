// wsBackoff.js — ws 重连退避的纯决策件（断连风暴修复批 2026-11-04）。
//
// ── 为什么单拆一个纯件 ─────────────────────────────────────────────────────
// 作者报障 2026-11-04 19:52「为什么现在一直弹 连接断开 正在重连」。实测
// `~/.stand/logs/stand.log`：18:44:42–18:51:50 有 80 次 `WebSocket client
// connected`，间隔 4.2–9.0s 不间断（不是 90–100s 的心跳判死带，也不是随机的
// 网络抖动），且该窗口内服务端**零关闭记录**（无 zombie recycled、无 queue
// full）——连接是「开得起来、几秒后就没了」，属于典型的 flap（抖动连接）。
//
// 旧口径的缺陷在 ws.js onopen 里那一行无条件 `reconnectAttempts = 0`：
// **握手成功就清零**，而 flap 形态下每次握手都成功 ⇒ 退避永远停在
// `2000ms × 2^0 × jitter` = 1.6–2.4s，`MAX_RECONNECT_DELAY = 30s` 这个上限
// 对该形态**结构性不可达**（上限只在握手失败 `!opened` 那条腿才吃得到）。
// 于是重连频率与故障持续时间无关，恒定为每 2–3s 一次 —— 正是「一直弹」。
//
// 本件把「何时清零」抽成纯函数，好让 tests/ws-backoff.test.mjs 直接用
// node:test 打真值（ws.js 本身带 DOM 副作用，测试无法直接 import）。
//
// 语义（与 ws.js 的接线一一对应）：
//   · 连接活过 STABLE_CONN_MS ⇒ 视为健康连接，本次断开后退避**归零**
//     （睡醒/断网/服务端重启后应立刻用基值重连，不该吃前面攒的惩罚）
//   · 连接没活到 STABLE_CONN_MS ⇒ flap，退避**保持**并继续指数上升
//     （下一次 scheduleReconnect 自增），直到 MAX 封顶
//   · 用户显式动作（forceReconnect）仍直接归零 —— 那是人的意图，不是机器行为

/** Only numeric/boolean close facts; never copy reason, URL or event properties. */
export function closeDiagnostic(event, opened, lifetimeMs, localAction) {
  const code = Number.isInteger(event?.code) && event.code >= 0 && event.code <= 4999 ? event.code : null;
  const durationSeconds = opened && Number.isFinite(lifetimeMs) && lifetimeMs >= 0
    ? Math.floor(lifetimeMs / 10000) * 10 : null;
  return {
    code,
    clean: typeof event?.wasClean === 'boolean' ? event.wasClean : null,
    opened: opened === true,
    durationSeconds,
    localAction: ['heartbeat', 'wake'].includes(localAction) ? localAction : 'none-observed',
  };
}

/** 退避基值（ms）。 */
export const BASE_RECONNECT_DELAY = 2000;
/** 退避上限（ms）。flap 形态下应能在若干拍内吃到这个上限。 */
export const MAX_RECONNECT_DELAY = 30000;
/**
 * 「健康连接」判据（ms）：连接活过这么久才算稳定，断开时才允许退避归零。
 * 取 10s 的理由：常驻控制台连接正常寿命令以小时计，10s 远低于任何真实的
 * 使用间隔；而 flap 形态（2–9s 必死）全部落在判据之下 ⇒ 一条也清零不了。
 */
export const STABLE_CONN_MS = 10000;

/**
 * 第 attempts 拍的退避时长（含 ±20% 抖动，抖动只影响这一拍，不改变档位）。
 * @param {number} attempts 已连续失败/抖动次数（0 = 第一次重连）
 * @param {() => number} [rand] 注入的随机源（测试用；缺省 Math.random）
 * @returns {number} 毫秒
 */
export function backoffDelayMs(attempts, rand = Math.random) {
  const core = Math.min(BASE_RECONNECT_DELAY * Math.pow(2, attempts), MAX_RECONNECT_DELAY);
  return core * (0.8 + rand() * 0.4);
}

/**
 * 断开之后、重连之前，退避计数该取什么值。
 * @param {number} attempts 当前计数
 * @param {{opened: boolean, lifetimeMs: number, stableMs?: number}} obs
 *        本次连接的观测：握手是否成功过、活了多久
 * @returns {number} 新的退避计数（0 = 归零重来）
 */
export function attemptsAfterClose(attempts, obs) {
  const stableMs = obs.stableMs === undefined ? STABLE_CONN_MS : obs.stableMs;
  // 握手都没成功（!opened）⇒ 不是 flap 意义上的「成功」，保持既有升档。
  // 活够时长 ⇒ 健康连接，归零。
  return obs.opened && obs.lifetimeMs >= stableMs ? 0 : attempts;
}
