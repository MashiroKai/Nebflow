#!/usr/bin/env node
// smoke-agentcontrol-ws.mjs — WS 层 AgentControl 两缺口帧型冒烟（2026-08-22）。
//
// 缺口 1 cancelAgent：帧型 {type:"cancelAgent", sessionId[, reason]} →
//   cancelAgentResult {ok, sessionId[, message|error]}。本冒烟钉守卫三路
//   （空 sessionId / 不存在的 sessionId / 只读 kind 无法在空实例构造——
//   由单测层 CancelableKinds 白名单覆盖）；真实 doCancel 链路（supervisor
//   Cancelled → barrier 释放）由 AgentControl 系列 spec 覆盖。
// 缺口 2 activeAgents：getActiveAgents → activeAgents 帧含 agents 数组，
//   每项含 status/startedAt/retryCount 三新字段（空实例下数组可能为空，
//   字段结构由 ActiveAgentsEntrySpec 钉死；此处验证帧型接线）。
//
// Run: NEBFLOW_URL=http://localhost:8095 NEBFLOW_HOME_DIR=<isolated home> \
//   node scripts/smoke-agentcontrol-ws.mjs

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const URL_BASE = process.env.NEBFLOW_URL || 'http://localhost:8095';
const HOME = process.env.NEBFLOW_HOME_DIR;
if (!HOME) {
  console.error('FAIL  NEBFLOW_HOME_DIR is required (isolated instance home)');
  process.exit(1);
}
const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));

let failed = 0;
function check(name, ok, extra = '') {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
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

const conn = await wsConnect();
try {
  // ── 缺口 1：cancelAgent 帧型与守卫 ────────────────────────────────
  // 1a. 空 sessionId → ok=false + requires sessionId
  conn.ws.send(JSON.stringify({ type: 'cancelAgent', sessionId: '' }));
  const r1 = await waitFor(
    conn, (m) => m.type === 'cancelAgentResult' && m.sessionId === '', 5000, 'cancelAgentResult (empty sid)');
  check('cancelAgent empty sessionId → ok=false + error',
    r1.ok === false && /requires sessionId/.test(r1.error || ''),
    JSON.stringify(r1).slice(0, 120));

  // 1b. 不存在的 sessionId → ok=false + No live agent
  conn.ws.send(JSON.stringify({ type: 'cancelAgent', sessionId: 'no-such-agent-xyz' }));
  const r2 = await waitFor(
    conn, (m) => m.type === 'cancelAgentResult' && m.sessionId === 'no-such-agent-xyz', 5000, 'cancelAgentResult (missing)');
  check('cancelAgent unknown sessionId → ok=false + No live agent',
    r2.ok === false && /No live agent/.test(r2.error || ''),
    JSON.stringify(r2).slice(0, 140));

  // ── 缺口 2：activeAgents 帧型接线 ─────────────────────────────────
  conn.ws.send(JSON.stringify({ type: 'getActiveAgents' }));
  const r3 = await waitFor(conn, (m) => m.type === 'activeAgents', 5000, 'activeAgents');
  check('getActiveAgents → activeAgents frame with agents array',
    Array.isArray(r3.agents), `agents=${r3.agents?.length ?? '??'}`);
  if (r3.agents?.length) {
    const e = r3.agents[0];
    check('entry carries panel-refresh fields (status/startedAt/retryCount)',
      'status' in e && 'startedAt' in e && 'retryCount' in e,
      JSON.stringify(e).slice(0, 160));
  } else {
    console.log('NOTE  no live agents in isolated instance — entry fields pinned by ActiveAgentsEntrySpec');
  }
} finally {
  conn.ws.close();
}

console.log(failed === 0 ? '# ALL PASS' : `# FAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);
