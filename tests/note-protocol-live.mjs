#!/usr/bin/env node
// note-protocol-live.mjs — Canvas Note 协议的**真实 WS 可执行入口**（数据安全收尾，2026-11-14）。
//
// 分工（诚实面）：
//   · tests/data-note-queue.test.mjs = 客户端真实函数片段（版本、迟到回执、冲突选择）；
//   · src/test/.../WsAskNoteFramesSpec.scala = 真实 handler 入口的帧契约（进程内）；
//   · 本文件 = **隔离真实例 + 真 socket**：askNoteGet/askNotePut 走网关 → AskNoteStore
//     落盘，逐条验证未知基线、清空墓碑、陈旧写冲突、重复写、断线重连后的基线。
// 显式冲突选择是客户端行为（远端文本 + 两个按钮），协议面的证据是：conflict ack 必须
// 同时携带服务端 text/version/cleared，否则客户端无从给用户选择。
//
// 前置（绝不触碰宿主 :6138 / :8080 / :8097；隔离实例由主会话启动）：
//   sbt -batch "run -- --home /tmp/qa-stand-data-live-home --port 8399 --no-browser"
// 跑法：
//   node tests/note-protocol-live.mjs
// env：LIVE_HOME（默认 /tmp/qa-stand-data-live-home）、LIVE_PORT（默认 8399）、
//      LIVE_SID（可选覆盖，必须 ask-<8 hex>）。
// 退出码：0 全绿；1 有 FAIL；2 环境错误（未启动/无 auth.json/连不上）。
// 清理：只使用本文件固定的 ask-<8 hex> 会话；最后以**产品支持的清空写入**收尾，
// 不删除任何文件、不改引擎状态文件。

import WebSocket from 'ws';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';

const HOME = process.env.LIVE_HOME || '/tmp/qa-stand-data-live-home';
const PORT = process.env.LIVE_PORT || '8399';
const BASE = process.env.LIVE_BASE || `http://127.0.0.1:${PORT}`;
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws';
const RUN = `${Date.now()}`;
const SID = process.env.LIVE_SID
  || 'ask-' + createHash('sha256').update('tests/note-protocol-live.mjs', 'utf8').digest('hex').slice(0, 8);
const WRITE_TIMEOUT = 8000;

let failures = 0;
function check(id, cond, detail = '') {
  if (cond) console.log(`PASS ${id}${detail ? ' — ' + detail : ''}`);
  else { failures += 1; console.log(`FAIL ${id}${detail ? ' — ' + detail : ''}`); }
}

function token() {
  try {
    const raw = readFileSync(join(HOME, 'auth.json'), 'utf8').trim();
    const parsed = JSON.parse(raw);
    return typeof parsed === 'string' ? parsed : (parsed.token || parsed.value || raw.replace(/^"|"$/g, ''));
  } catch (e) {
    console.error(`environment: cannot read ${join(HOME, 'auth.json')}: ${e.message}`);
    console.error(`environment: start the isolated instance first, e.g. sbt -batch "run -- --home ${HOME} --port ${PORT} --no-browser"`);
    process.exit(2);
  }
}

const TOKEN = token();

/** One connection with per-frame matching. */
function connect() {
  const ws = new WebSocket(WS_URL, { headers: { Authorization: `Bearer ${TOKEN}` } });
  const buffer = [];
  const waiters = [];
  ws.on('message', (buf) => {
    let msg;
    try { msg = JSON.parse(String(buf)); } catch { return; }
    buffer.push(msg);
    for (let i = waiters.length - 1; i >= 0; i--) {
      const w = waiters[i];
      if (w.type === msg.type && (!w.pred || w.pred(msg))) {
        waiters.splice(i, 1);
        clearTimeout(w.timer);
        w.resolve(msg);
      }
    }
  });
  const ready = new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
    setTimeout(() => reject(new Error(`ws open timeout after ${WRITE_TIMEOUT}ms`)), WRITE_TIMEOUT).unref?.();
  });
  const close = () => new Promise((resolve) => { try { ws.close(); } catch { /* already closed */ } resolve(); });
  return {
    ws,
    ready,
    close,
    send(frame) { ws.send(JSON.stringify(frame)); },
    wait(type, pred, timeoutMs = WRITE_TIMEOUT) {
      return new Promise((resolve) => {
        const hit = buffer.find((m) => m.type === type && (!pred || pred(m)));
        if (hit) { buffer.splice(buffer.indexOf(hit), 1); resolve(hit); return; }
        const w = { type, pred, resolve };
        w.timer = setTimeout(() => {
          const i = waiters.indexOf(w);
          if (i >= 0) waiters.splice(i, 1);
          resolve(null);
        }, timeoutMs);
        waiters.push(w);
      });
    },
  };
}

async function round(ws, frame) {
  ws.send(frame);
  return ws.wait(frame.type === 'askNoteGet' ? 'askNoteState' : 'askNoteAck',
    (m) => !frame.writeId || m.writeId === frame.writeId || m.type === 'askError');
}

async function main() {
  const ws = connect();
  try {
    await ws.ready;
  } catch (e) {
    console.error(`environment: cannot connect to ${WS_URL}: ${e.message}`);
    process.exit(2);
  }

  // N1: baseline read (a fresh or previously-cleared note; version is authoritative either way).
  const start = await round(ws, { type: 'askNoteGet', sessionId: SID });
  check('N1 baseline read is a complete state', start && start.type === 'askNoteState'
    && typeof start.version === 'number' && typeof start.text === 'string', JSON.stringify(start));
  if (!start || typeof start.version !== 'number') { await ws.close(); process.exit(1); }
  const base = start.version;

  // N2: unknown base (an older page that carries no baseVersion) is refused and writes nothing.
  const noBase = await round(ws, { type: 'askNotePut', sessionId: SID, text: `blind-${RUN}`, writeId: 'w-nobase' });
  check('N2 unknown base is refused as version-required with the stored state',
    noBase && noBase.ok === false && noBase.error === 'version-required'
      && noBase.writeId === 'w-nobase' && noBase.version === base, JSON.stringify(noBase));

  // N3: a real write lands with an increasing version and the receipt echoes its writeId.
  const first = await round(ws, { type: 'askNotePut', sessionId: SID, text: `note-${RUN}`, baseVersion: base, writeId: 'w-1' });
  check('N3 write is acknowledged with its writeId and a higher version',
    first && first.ok === true && first.writeId === 'w-1' && first.version > base, JSON.stringify(first));
  if (!first || first.ok !== true) { await ws.close(); process.exit(1); }

  // N4: duplicate call (same text, same base) is idempotent: same version, no rewrite.
  const dup = await round(ws, { type: 'askNotePut', sessionId: SID, text: `note-${RUN}`, baseVersion: first.version, writeId: 'w-dup' });
  check('N4 a repeated identical write reports the stored version without a new one',
    dup && dup.ok === true && dup.version === first.version, JSON.stringify(dup));

  // N5: stale write (an old base) is a conflict that carries the stored text/version — the
  // material an explicit local/server choice needs.
  const stale = await round(ws, { type: 'askNotePut', sessionId: SID, text: `stale-${RUN}`, baseVersion: base, writeId: 'w-stale' });
  check('N5 stale base conflicts and returns the stored text and version',
    stale && stale.ok === false && stale.error === 'conflict'
      && stale.text === `note-${RUN}` && stale.version === first.version, JSON.stringify(stale));

  // N6: clear records a tombstone with a new version; an old edit over the clear conflicts.
  const cleared = await round(ws, { type: 'askNotePut', sessionId: SID, text: '', baseVersion: first.version, writeId: 'w-clear' });
  check('N6 clear stores a versioned tombstone', cleared && cleared.ok === true && cleared.version > first.version, JSON.stringify(cleared));
  const afterClear = await round(ws, { type: 'askNoteGet', sessionId: SID });
  check('N6 read of the clear says exists=false cleared=true with the clear version',
    afterClear && afterClear.text === '' && afterClear.exists === false && afterClear.cleared === true
      && afterClear.version === cleared.version, JSON.stringify(afterClear));
  const overClear = await round(ws, { type: 'askNotePut', sessionId: SID, text: 'old', baseVersion: first.version, writeId: 'w-overclear' });
  check('N6 an edit based on the pre-clear version conflicts instead of reviving stale text',
    overClear && overClear.ok === false && overClear.error === 'conflict' && overClear.cleared === true, JSON.stringify(overClear));

  // N7: reconnect (a real second socket) reads the same authoritative version; a write on it lands.
  await ws.close();
  const ws2 = connect();
  try { await ws2.ready; } catch (e) {
    console.error(`environment: reconnect failed: ${e.message}`);
    process.exit(2);
  }
  const afterReconnect = await round(ws2, { type: 'askNoteGet', sessionId: SID });
  check('N7 a reconnect reads the same base and clear state',
    afterReconnect && afterReconnect.version === cleared.version && afterReconnect.cleared === true, JSON.stringify(afterReconnect));
  const resumed = await round(ws2, { type: 'askNotePut', sessionId: SID, text: `resumed-${RUN}`, baseVersion: cleared.version, writeId: 'w-resumed' });
  check('N7 the unsaved edit resumes on the known base after reconnect',
    resumed && resumed.ok === true && resumed.version > cleared.version, JSON.stringify(resumed));

  // N8: late/duplicate receipt evidence — a repeated writeId on an old base cannot overwrite the
  // newer record; its ack still echoes the writeId so a client can discard it precisely.
  const repeatedOld = await round(ws2, { type: 'askNotePut', sessionId: SID, text: 'late-old', baseVersion: cleared.version, writeId: 'w-resumed' });
  check('N8 a late retry of an old base conflicts and echoes its writeId',
    repeatedOld && repeatedOld.ok === false && repeatedOld.error === 'conflict'
      && repeatedOld.writeId === 'w-resumed' && repeatedOld.text === `resumed-${RUN}`, JSON.stringify(repeatedOld));

  // Finish with a supported clear (no file deletion, no engine-owned state touched directly).
  const finish = await round(ws2, { type: 'askNotePut', sessionId: SID, text: '', baseVersion: resumed.version, writeId: 'w-finish' });
  check('N9 final supported clear succeeds', finish && finish.ok === true, JSON.stringify(finish));
  await ws2.close();

  console.log(failures === 0 ? `note-protocol-live: ${9 - failures}/9 checks passed (sid=${SID})`
    : `note-protocol-live: ${failures} FAILED (sid=${SID})`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(`environment: ${e && e.stack || e}`); process.exit(2); });
