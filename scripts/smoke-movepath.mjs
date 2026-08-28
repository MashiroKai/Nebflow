#!/usr/bin/env node
// smoke-movepath.mjs — WS 层 movePath 帧型冒烟（2026-08-24）。
//
// 钉三件事：
//  ① 成功帧 {type:'pathMoved', oldPath, newPath}（字段名严格）——文件
//     从根移到 sub/，newPath 为相对 root 的路径；
//  ② 守卫失败帧 fileOpError（targetDir 不存在 / 移动 root 自身）；
//  ③ 磁盘实况由外层脚本核验（a.txt 消失、sub/a.txt 出现且内容一致）。
//
// Run: NEBFLOW_URL=http://localhost:8123 NEBFLOW_HOME_DIR=<isolated home> \
//   ROOT_DIR=<demo root> node scripts/smoke-movepath.mjs

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const URL_BASE = process.env.NEBFLOW_URL || 'http://localhost:8095';
const HOME = process.env.NEBFLOW_HOME_DIR;
const ROOT = process.env.ROOT_DIR;
if (!HOME || !ROOT) {
  console.error('FAIL  NEBFLOW_HOME_DIR and ROOT_DIR are required');
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

function send(conn, type, extra = {}) {
  conn.ws.send(JSON.stringify({ type, sessionId: 'smoke-session', rootPath: ROOT, ...extra }));
}

const conn = await wsConnect();
try {
  // ── ① 成功移动：a.txt → sub/a.txt ─────────────────────────────────
  send(conn, 'movePath', { path: 'a.txt', targetDir: 'sub' });
  const ok1 = await waitFor(conn, (m) => m.type === 'pathMoved', 5000, 'pathMoved');
  check('movePath success → pathMoved with strict field names',
    ok1.oldPath === 'a.txt' && ok1.newPath === 'sub/a.txt',
    JSON.stringify(ok1).slice(0, 120));

  // ── ②a 守卫：targetDir 不存在 → fileOpError ───────────────────────
  send(conn, 'movePath', { path: 'b.txt', targetDir: 'no-such-dir' });
  const err1 = await waitFor(
    conn,
    (m) => m.type === 'fileOpError' && /target directory not found/.test(m.error || ''),
    5000, 'fileOpError (missing dir)');
  check('missing targetDir → fileOpError with target directory not found',
    true, JSON.stringify(err1).slice(0, 120));

  // ── ②b 守卫：移动 root 自身 → fileOpError ─────────────────────────
  send(conn, 'movePath', { path: '.', targetDir: 'sub' });
  const err2 = await waitFor(
    conn,
    (m) => m.type === 'fileOpError' && /cannot move project root/.test(m.error || ''),
    5000, 'fileOpError (root)');
  check('moving project root → fileOpError with cannot move project root',
    true, JSON.stringify(err2).slice(0, 120));
} finally {
  conn.ws.close();
}

console.log(failed === 0 ? '# ALL PASS' : `# FAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);
