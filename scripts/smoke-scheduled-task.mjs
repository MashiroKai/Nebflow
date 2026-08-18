#!/usr/bin/env node
// smoke-scheduled-task.mjs — E2E smoke for the scheduled-task creation chain.
//
// Regression guard for the 2026-08-17 P0 ("user set a 2:00 task via the UI at
// 22:41; it never fired"). Forensics showed the WS create message can be lost
// silently (fire-and-forget send + optimistic UI), and server-side rejections
// were neither logged nor surfaced. This script pins the three load-bearing
// segments of the chain against a real isolated instance:
//
//   Phase 1 (create persists)   createScheduledTask → scheduledTaskCreated ack
//                               + <home>/scheduled-tasks/<sid>.json non-empty
//   Phase 2 (due task fires)    poll until triggerAt; assert ExternalEvent is
//                               injected as a user message (session history +
//                               "userMessage" broadcast) and the fired task is
//                               removed from storage
//   Phase 3 (rejection is loud) triggerAt in the past → server replies with an
//                               error that carries msgType=createScheduledTask
//                               (routable by the frontend) — silent-drop class
//                               stays debuggable
//
// Run (against an isolated instance, never the user's live home):
//   NEBFLOW_URL=http://localhost:8096 NEBFLOW_HOME_DIR=/tmp/nb-sched-home \
//     node scripts/smoke-scheduled-task.mjs
//
// Env:
//   NEBFLOW_URL       gateway base URL (default http://localhost:8096)
//   NEBFLOW_HOME_DIR  instance home dir holding auth.json + scheduled-tasks/
//   NEBFLOW_TOKEN     explicit token (default: read $NEBFLOW_HOME_DIR/auth.json)
//   SMOKE_SHORT       =1 skip Phase 2 (waiting a real trigger) for quick CI mode

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const URL_BASE = process.env.NEBFLOW_URL || 'http://localhost:8096';
const HOME = process.env.NEBFLOW_HOME_DIR;
if (!HOME) {
  console.error('FAIL  NEBFLOW_HOME_DIR is required (isolated instance home, never ~/.nebflow)');
  process.exit(1);
}
const TOKEN =
  process.env.NEBFLOW_TOKEN ??
  JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));

const checks = [];
let failed = 0;
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── helpers ─────────────────────────────────────────────────────────────
async function api(path, opts = {}) {
  const res = await fetch(`${URL_BASE}${path}`, {
    ...opts,
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json', ...(opts.headers || {}) },
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

function wsConnect() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${URL_BASE.replace('http', 'ws')}/ws?token=${encodeURIComponent(TOKEN)}`);
    const inbox = [];
    const waiters = [];
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      inbox.push(msg);
      for (let i = waiters.length - 1; i >= 0; i--) {
        const w = waiters[i];
        if (w.pred(msg)) { waiters.splice(i, 1); w.resolve(msg); }
      }
    });
    ws.addEventListener('open', () => resolve({ ws, inbox, waiters }));
    ws.addEventListener('error', (e) => reject(new Error('WS connect failed: ' + (e.message || 'error'))));
  });
}

function waitFor(conn, pred, timeoutMs, label) {
  const hit = conn.inbox.find(pred);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting for ${label}`)), timeoutMs);
    conn.waiters.push({ pred, resolve: (m) => { clearTimeout(t); resolve(m); } });
  });
}

// ── setup: fresh session ────────────────────────────────────────────────
const SID = 'smoke-sched-' + Date.now();
const created = await api('/api/sessions', {
  method: 'POST',
  body: JSON.stringify({ name: 'smoke-scheduled-task', agentName: 'Nebula' }),
});
// Some builds return {id}; tolerate a list or nested shapes.
const sessionId =
  created.body?.id ?? created.body?.sessionId ?? created.body?.session?.id;
if (!sessionId) {
  console.error('FAIL  could not create session:', created.status, JSON.stringify(created.body).slice(0, 200));
  process.exit(1);
}
console.log(`# session ${sessionId} (status ${created.status})`);

const conn = await wsConnect();
try {
  // ── Phase 1: create → ack + persistence ─────────────────────────────
  const triggerAt = Date.now() + 75_000; // fires in Phase 2
  conn.ws.send(JSON.stringify({
    type: 'createScheduledTask',
    sessionId,
    content: 'smoke: scheduled task creation chain',
    triggerAt,
  }));

  const ack = await waitFor(
    conn,
    (m) => m.type === 'scheduledTaskCreated' && m.task?.triggerAt === triggerAt,
    10_000,
    'scheduledTaskCreated ack',
  );
  check('P1 ack: scheduledTaskCreated with matching triggerAt', !!ack);

  const storeFile = join(HOME, 'scheduled-tasks', `${sessionId}.json`);
  let persisted = null;
  try { persisted = JSON.parse(readFileSync(storeFile, 'utf8')); } catch { /* absent */ }
  const row = Array.isArray(persisted) && persisted.find((t) => t.triggerAt === triggerAt);
  check('P1 persistence: scheduled-tasks/<sid>.json contains the task', !!row,
    row ? `id=${row.id}` : `file=${storeFile}`);

  const listMsg = await new Promise(async (resolve) => {
    conn.ws.send(JSON.stringify({ type: 'listScheduledTasks', sessionId }));
    resolve(await waitFor(conn, (m) => m.type === 'scheduledTaskList' && m.sessionId === sessionId, 10_000, 'scheduledTaskList'));
  });
  check('P1 list: listScheduledTasks returns the pending task',
    (listMsg.tasks || []).some((t) => t.triggerAt === triggerAt));

  // ── Phase 2: due task fires ─────────────────────────────────────────
  if (process.env.SMOKE_SHORT !== '1') {
    console.log(`# waiting for trigger at ${new Date(triggerAt).toISOString()} …`);
    const fired = await waitFor(
      conn,
      (m) => m.type === 'userMessage' && m.sessionId === sessionId &&
              typeof m.content === 'string' && m.content.includes('smoke: scheduled task creation chain'),
      Math.max(30_000, triggerAt - Date.now() + 30_000),
      'userMessage broadcast of fired task',
    );
    check('P2 fire: task content broadcast as userMessage', !!fired);

    const hist = await api(`/api/sessions/${sessionId}/history`);
    const msgs = hist.body?.messages ?? hist.body ?? [];
    const injected = Array.isArray(msgs) &&
      msgs.some((m) => (m.text || '').includes('smoke: scheduled task creation chain'));
    check('P2 inject: fired task persisted into session history', !!injected);

    await sleep(2_000); // allow post-fire cleanup write
    let after = null;
    try { after = JSON.parse(readFileSync(storeFile, 'utf8')); } catch { /* absent */ }
    const gone = !Array.isArray(after) || !after.some((t) => t.triggerAt === triggerAt);
    check('P2 cleanup: fired one-shot removed from storage', gone);
  } else {
    console.log('# SMOKE_SHORT=1 — skipping Phase 2 (real trigger wait)');
  }

  // ── Phase 3: rejection is loud + routable ───────────────────────────
  conn.ws.send(JSON.stringify({
    type: 'createScheduledTask',
    sessionId,
    content: 'smoke: past-time task must be rejected loudly',
    triggerAt: Date.now() - 60_000,
  }));
  const err = await waitFor(
    conn,
    (m) => m.type === 'error' && typeof m.message === 'string' && /scheduled/i.test(m.message),
    10_000,
    'error reply for past triggerAt',
  );
  check('P3 reject: past triggerAt gets an error reply', !!err);
  check('P3 routable: error carries msgType=createScheduledTask', err?.msgType === 'createScheduledTask',
    `msgType=${err?.msgType}`);
} finally {
  try { conn.ws.close(); } catch { /* already closed */ }
}

console.log(`\n${failed === 0 ? 'ALL PASS' : failed + ' FAILED'}  (${checks.filter((c) => c.ok).length}/${checks.length})`);
process.exit(failed === 0 ? 0 : 1);
