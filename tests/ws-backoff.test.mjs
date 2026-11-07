#!/usr/bin/env node
/**
 * ws 重连退避 · flap 形态（断连风暴修复批 2026-11-04）。
 *
 * ── 事故 ────────────────────────────────────────────────────────────────────
 * 作者报障 2026-11-04 19:52「为什么现在一直弹 连接断开 正在重连」。实测
 * `~/.stand/logs/stand.log`：
 *   · 18:44:42–18:51:50 共 80 次 `WebSocket client connected`，间隔 4.2–9.0s；
 *   · 同窗口服务端**零**关闭记录（无 `console zombie recycled`、无
 *     `outbound queue full`）——不是心跳判死（那一带是 90–100s），也不是随机的
 *     网络抖动（那就是「开了几秒必死」的 flap）；
 *   · 每条连接都完整跑完 onopen 引导（takeoverIdentify → setThinking →
 *     getSkills/getTeams/getConfig/getToolsList …）⇒ 握手确实成功。
 *
 * 旧口径 ws.js onopen 里的无条件 `reconnectAttempts = 0` 由此完全失效：flap 下
 * 每次握手都成功 ⇒ 退避永远停在 `2000 × 2^0 × jitter`，`MAX_RECONNECT_DELAY=30s`
 * **结构性不可达**，重连频率与故障持续时间脱钩 ⇒ 恒定的 2–3s 一拍 = 「一直弹」。
 *
 * ── 本测钉的契约 ────────────────────────────────────────────────────────────
 *   1. 红（旧规则）：退避在每次握手成功时归零 ⇒ flap 20 拍后仍然每拍 ~2s，
 *      重连间隔恒定在「连接存活时长 + 基值」这一带（复现实测 4.2–9.0s）。
 *   2. 绿（新规则）：同一串 flap（存活 2.5s）⇒ 退避逐拍升档 2→4→8→16→30，
 *      20 拍累计时长 ≥ 旧规则的 5 倍，且最后 8 拍全在 30s 档（封顶可达）。
 *   3. 不误伤：健康连接（活过 STABLE_CONN_MS）断开 ⇒ 归零，下一次仍用基值
 *      ——睡醒/换网/服务端重启不该吃前面攒的惩罚。
 *   4. 握手失败（!opened）⇒ 从不归零。
 *   5. 抖动只影响单拍（0.8–1.2 倍），不改变档位；core 严格 ≤ MAX。
 *
 * 纯函数直测：wsBackoff.js 零依赖零副作用（ws.js 本身带 DOM 副作用，无法
 * 在 node 里 import）。零网络、零浏览器、零实例。
 *
 * Run: node --test tests/ws-backoff.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  backoffDelayMs,
  attemptsAfterClose,
  closeDiagnostic,
  BASE_RECONNECT_DELAY,
  MAX_RECONNECT_DELAY,
  STABLE_CONN_MS,
} from "../src/main/resources/web/js/wsBackoff.js";

test('close diagnostics whitelist facts and discard arbitrary sensitive event data', () => {
  const event = { code: 1006, wasClean: false, reason: 'secret', url: 'secret' };
  assert.deepEqual(closeDiagnostic(event, true, 123456, 'heartbeat'), {
    code: 1006, clean: false, opened: true, durationSeconds: 120, localAction: 'heartbeat',
  });
  assert.deepEqual(closeDiagnostic({ code: 'secret', wasClean: 'secret' }, false, -1, 'secret'), {
    code: null, clean: null, opened: false, durationSeconds: null, localAction: 'none-observed',
  });
});

/** 固定随机源（0.5 ⇒ 抖动系数 1.0），让档位可逐字断言。 */
const noJitter = () => 0.5;

/**
 * 旧口径的「断开后计数」：ws.js 修复前 onopen 无条件归零，等价于每次断开都
 * 已经是 0（只要握手成功过）。这里逐字复刻，作为红腿基准。
 * @param {number} _attempts
 * @param {{opened: boolean, lifetimeMs: number}} _obs
 * @returns {number}
 */
function legacyAttemptsAfterClose(_attempts, _obs) {
  return 0;
}

/**
 * 跑一串 flap，返回每拍的重连间隔（ms）与累计时长。
 * @param {(a: number, o: {opened: boolean, lifetimeMs: number}) => number} decision
 * @param {number} flaps 拍数
 * @param {number} lifetimeMs 每条连接从握手成功到死的存活时长
 */
function simulateFlaps(decision, flaps, lifetimeMs) {
  let attempts = 0;
  /** @type {number[]} */
  const gaps = [];
  let elapsed = 0;
  for (let i = 0; i < flaps; i++) {
    // 连接开起来 → 活 lifetimeMs → 死
    elapsed += lifetimeMs;
    attempts = decision(attempts, { opened: true, lifetimeMs });
    const delay = backoffDelayMs(attempts, noJitter);
    attempts++; // scheduleReconnect 自增
    gaps.push(lifetimeMs + delay);
    elapsed += delay;
  }
  return { gaps, elapsed };
}

test("红腿（旧规则）：flap 形态退避恒在基值——复现实测的 4.2–9.0s 恒定间隔", () => {
  const { gaps } = simulateFlaps(legacyAttemptsAfterClose, 20, 2500);
  // 每一拍都落在 存活 2.5s + 基值 2s 这一档（±抖动前）= 4.5s，与实测同带。
  for (const g of gaps) {
    assert.ok(g >= 4400 && g <= 4600, `旧规则第 N 拍应恒定 4.5s，实得 ${g}ms`);
  }
  // 「一直弹」的机制表述：20 拍内间隔毫无增长。
  assert.equal(gaps[0], gaps[19], "旧规则下重连间隔不随故障持续而增长（这才是 bug）");
});

test("绿腿（新规则）：同一串 flap 逐拍升档 2→4→8→16→30 秒", () => {
  const { gaps } = simulateFlaps(attemptsAfterClose, 20, 2500);
  const cores = gaps.slice(0, 6).map((g) => g - 2500);
  assert.deepEqual(
    cores.slice(0, 5),
    [2000, 4000, 8000, 16000, 30000],
    `档位应为 2/4/8/16/30s，实得 ${cores.join(",")}`,
  );
  // 封顶可达且不再增长。
  assert.equal(cores[5], MAX_RECONNECT_DELAY, "第 6 拍起应稳定在 30s 封顶");
  assert.ok(gaps[19] <= 2500 + MAX_RECONNECT_DELAY, "任何一拍都不得越过上限");
});

test("绿腿：20 拍累计时长至少是旧规则的 5 倍（降频即降流量）", () => {
  const legacy = simulateFlaps(legacyAttemptsAfterClose, 20, 2500);
  const fixed = simulateFlaps(attemptsAfterClose, 20, 2500);
  assert.ok(
    fixed.elapsed >= legacy.elapsed * 5,
    `新规则累计 ${fixed.elapsed}ms 应 ≥ 旧规则 ${legacy.elapsed}ms 的 5 倍`,
  );
});

test("不误伤：健康连接（活过 STABLE_CONN_MS）断开后退避归零", () => {
  // 先攒到第 4 档（16s），再让一条连接活 40s 后断开 —— 应回到基值。
  let attempts = 4;
  attempts = attemptsAfterClose(attempts, { opened: true, lifetimeMs: 40000 });
  assert.equal(attempts, 0);
  assert.equal(backoffDelayMs(attempts, noJitter), BASE_RECONNECT_DELAY);
  // 判据边界严格：恰好在阈值上也归零（不因 1ms 抖动误判 flap）。
  assert.equal(attemptsAfterClose(4, { opened: true, lifetimeMs: STABLE_CONN_MS }), 0);
  assert.equal(attemptsAfterClose(4, { opened: true, lifetimeMs: STABLE_CONN_MS - 1 }), 4);
});

test("不误伤：握手从未成功（!opened）⇒ 从不归零", () => {
  assert.equal(attemptsAfterClose(3, { opened: false, lifetimeMs: -1 }), 3);
  // 反例护栏：即便 lifetimeMs 被人错误地算成大值，!opened 也不得清零。
  assert.equal(attemptsAfterClose(3, { opened: false, lifetimeMs: 999999 }), 3);
  // 未记录开连时刻（connOpenedAt=0 ⇒ lifetimeMs=-1）同属此类。
  assert.equal(attemptsAfterClose(2, { opened: true, lifetimeMs: -1 }), 2);
});

test("抖动与封顶：抖动只改单拍（0.8–1.2 倍），core 严格 ≤ MAX", () => {
  assert.equal(backoffDelayMs(0, () => 0), BASE_RECONNECT_DELAY * 0.8);
  assert.equal(backoffDelayMs(0, () => 0.999999), BASE_RECONNECT_DELAY * (0.8 + 0.999999 * 0.4));
  // 高拍位（2^20 早已越过上限）仍钳在 MAX × 1.2 之内。
  const hi = backoffDelayMs(20, () => 0.999999);
  assert.ok(hi <= MAX_RECONNECT_DELAY * 1.2, `封顶失效：${hi}`);
  // 档位单调不降。
  let prev = 0;
  for (let a = 0; a < 8; a++) {
    const core = Math.min(BASE_RECONNECT_DELAY * Math.pow(2, a), MAX_RECONNECT_DELAY);
    assert.ok(core >= prev, "档位必须单调不降");
    prev = core;
  }
});
