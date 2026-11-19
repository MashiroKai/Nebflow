#!/usr/bin/env node
// ask-gate-live.spec.mjs — md/html 类型硬约束闸 LIVE 验收（真实隔离引擎 + 真浏览器）。
//
// 前置：隔离引擎已 boot（在产零触碰）：
//   sbt -batch "run -- --home /tmp/ask-gate-live-home --port 8399 --no-browser"
// 且 HOME/ask/index.json 预置了「闸前时代」的 .txt 存量绑定（自然衰减面）。
//
// 覆盖：
//   L1  askOpen(.txt) → askError（拒答），无 askState、零绑定新增
//   L2  askOpen(.pdf) → askError（拒答）
//   L3  askOpen(.md)  → askState（放行，回归不误伤）
//   L4  存量 .txt 绑定 askQuestion → 无任何回答帧（spawn 面 A3 全量防线）
//   L5  存量绑定不回退：index.json 预置 .txt 绑定全程零删除零改写
//   L6  真浏览器 UI：.txt 文件 tab 入口隐藏（截图）；.md tab 入口在场可开
//       （真实 askOpen → 真实 askState → .md 绑定落盘，截图）
//
// 运行：node tests/ask-gate-live.spec.mjs
// 可选 env：LIVE_PORT（默认 8399）、LIVE_HOME（默认 /tmp/ask-gate-live-home）、
//           LIVE_FIXTURE（默认 /tmp/ask-gate-fixture）、LIVE_SHOTS_DIR。

import WebSocket from 'ws';
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HOME = process.env.LIVE_HOME || '/tmp/ask-gate-live-home';
const FIXTURE = process.env.LIVE_FIXTURE || '/tmp/ask-gate-fixture';
const BASE = `http://127.0.0.1:${process.env.LIVE_PORT || 8399}`;
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws';
const HERE = dirname(fileURLToPath(import.meta.url));
const SHOTS = process.env.LIVE_SHOTS_DIR || resolve(HERE, '../evidence/ask-mdhtml-gate/live');
mkdirSync(SHOTS, { recursive: true });

const TOKEN = JSON.parse(readFileSync(`${HOME}/auth.json`, 'utf8'));
const authHeaders = () => ({ Authorization: `Bearer ${TOKEN}` });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

// ── 真 WS 协议面 ────────────────────────────────────────────────────────────
const received = [];
const ws = new WebSocket(WS_URL, { headers: authHeaders() });
await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); setTimeout(() => rej(new Error('ws open timeout')), 10000); });
ws.on('message', (raw) => {
  try { received.push(JSON.parse(raw.toString())); } catch { /* ignore */ }
});

/** 发一帧后等谓词命中（或超时返回 null）。 */
function expectFrame(label, send, predicate, timeoutMs = 6000) {
  return new Promise((res) => {
    const timer = setTimeout(() => { received.splice(mark); res(null); }, timeoutMs);
    const mark = received.length;
    const poll = setInterval(() => {
      const hit = received.slice(mark).find(predicate);
      if (hit) { clearInterval(poll); clearTimeout(timer); res(hit); }
    }, 60);
    ws.send(JSON.stringify(send));
    void label;
  });
}

const openSend = (p) => ({ type: 'askOpen', file: { absPath: p, name: p.split('/').pop() }, mainSessionId: '' });

// L1 非白名单 .txt：askError 拒答、无 askState。
let e1 = await expectFrame('L1', openSend(`${FIXTURE}/实验记录.txt`), (m) => m.type === 'askError' || m.type === 'askState');
ok('L1 askOpen(.txt) → askError 拒答（md/html 硬闸后端腿）',
  !!e1 && e1.type === 'askError' && /not askable/.test(e1.message || ''),
  JSON.stringify(e1));

// L2 非白名单 .pdf（画布可开族附件型）：同样拒。
let e2 = await expectFrame('L2', openSend(`${FIXTURE}/paper.pdf`), (m) => m.type === 'askError' || m.type === 'askState');
ok('L2 askOpen(.pdf) → askError 拒答（附件族同样闸）',
  !!e2 && e2.type === 'askError', JSON.stringify(e2));

// L3 白名单 .md：askState 放行（真实绑定 + 历史回放腿回归）。
let e3 = await expectFrame('L3', openSend(`${FIXTURE}/笔记.md`), (m) => m.type === 'askState' || m.type === 'askError');
ok('L3 askOpen(.md) → askState 放行（闸不误伤）',
  !!e3 && e3.type === 'askState' && e3.sessionId === sidOf(`${FIXTURE}/笔记.md`),
  JSON.stringify(e3 && { type: e3.type, sessionId: e3.sessionId, file: e3.file }));

// L4 存量 .txt 绑定（预置 ask/index.json，绕开 askOpen 前闸直发问帧）：
// spawn 面 agentDefFor 复问拒答——观察窗内零回答帧（textDelta/done）。
const legacySid = sidOf(`${FIXTURE}/旧笔记.txt`);
const markBeforeLegacy = received.length;
ws.send(JSON.stringify({ type: 'askQuestion', sessionId: legacySid, content: '这份文档说了什么？', mainSessionId: '' }));
await sleep(8000);
const legacyFrames = received.slice(markBeforeLegacy).filter((m) => m.sessionId === legacySid || !m.sessionId);
const answerFrames = legacyFrames.filter((m) => m.type === 'textDelta' || m.type === 'done');
ok('L4 存量 .txt 绑定 askQuestion 零回答帧（spawn 面 A3 全量防线）', answerFrames.length === 0,
  JSON.stringify(legacyFrames.map((m) => m.type)));
ws.close();

// L5 存量绑定不回退：预置 .txt 绑定原样在场；新增只可能是 .md 放行腿。
const disk = JSON.parse(readFileSync(`${HOME}/ask/index.json`, 'utf8'));
const files = (disk.bindings || []).map((b) => b.file);
ok('L5 存量 .txt 绑定全程零删除零改写（自然衰减）', files.includes(`${FIXTURE}/旧笔记.txt`), JSON.stringify(files));
ok('L5b 拒答文件零新绑定（.txt/.pdf 未进绑定表）',
  !files.includes(`${FIXTURE}/实验记录.txt`) && !files.includes(`${FIXTURE}/paper.pdf`), JSON.stringify(files));

// ── 真浏览器 UI 面 ──────────────────────────────────────────────────────────
const mint = await fetch(`${BASE}/api/auth/console-ticket`, { method: 'POST', headers: authHeaders() });
const ticket = (await mint.json()).ticket;
if (!ticket) { console.error('FAIL ticket mint: HTTP ' + mint.status); process.exit(1); }

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`);
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(1200);

  async function openDoc(name, itemType) {
    const absPath = `${FIXTURE}/${name}`;
    await page.evaluate(({ absPath, itemType, content }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + absPath, itemType, title: absPath.split('/').pop(), content, absPath, pinned: true },
      }));
    }, { absPath, itemType, content: readFileSync(absPath, 'utf8') });
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 10000 });
    await sleep(800);
    return absPath;
  }
  const fabRead = () => page.evaluate(() => {
    const f = document.querySelector('.ask-fab');
    const d = document.querySelector('.ask-dock');
    return {
      present: !!f,
      display: f ? getComputedStyle(f).display : null,
      gateOff: f ? f.classList.contains('gate-off') : null,
      dockClosed: d ? d.classList.contains('closed') : null,
    };
  });

  // L6a .txt 文件 tab：入口同闸隐藏（与可见性闸同悬挂点）。
  await openDoc('实验记录.txt', 'code');
  const t = await fabRead();
  ok('L6a 真浏览器 .txt tab：ask 入口隐藏（gate-off）', t.present && t.display === 'none' && t.gateOff === true, JSON.stringify(t));
  await page.screenshot({ path: join(SHOTS, 'live-txt-no-fab.png') });

  // L6b .md tab：入口在场可开，真实 askOpen → askState → 绑定落盘。
  await openDoc('笔记.md', 'markdown');
  const m = await fabRead();
  ok('L6b 真浏览器 .md tab：ask 入口在场（放行）', m.present && m.display !== 'none' && m.gateOff === false, JSON.stringify(m));
  await page.click('.ask-fab');
  await sleep(2500);
  const opened = await page.evaluate(() => {
    const d = document.querySelector('.ask-dock');
    return d ? !d.classList.contains('closed') : false;
  });
  const diskAfter = JSON.parse(readFileSync(`${HOME}/ask/index.json`, 'utf8'));
  const mdBound = (diskAfter.bindings || []).some((b) => b.file === `${FIXTURE}/笔记.md`);
  ok('L6c 真浏览器点开 dock：真实 askOpen 全链走通（.md 绑定真实落盘）', opened && mdBound,
    JSON.stringify({ opened, mdBound, bindings: (diskAfter.bindings || []).map((b) => b.file) }));
  await page.screenshot({ path: join(SHOTS, 'live-md-fab-dock-open.png') });

  // L6d 切回 .txt tab：dock 同闸即隐即收（开着切走一并收起）。
  await openDoc('实验记录.txt', 'code');
  const back = await fabRead();
  ok('L6d 开着切回 .txt tab：入口即隐 dock 即收', back.display === 'none' && back.dockClosed === true, JSON.stringify(back));
  await page.screenshot({ path: join(SHOTS, 'live-back-to-txt-gate.png') });

  ok('L7 页面零 pageerror', errors.length === 0, errors.join(' | ').slice(0, 300));
  await ctx.close();
} finally {
  await browser.close();
}

// ── L8 词条接口同闸（接入点 c：GET /api/wiki/term?name=&project=&doc=）──────
// livegate 项目（隔离 home 预置 docs/glossary.md）注册词条 GCR。
// .txt 宿主 → 400 {reason:"not-md-html"}（不静默）；.md 宿主 → 照常消解 200；
// .md 宿主带 ?query#fragment → 归一后照常消解（与判据单点同语义端到端）。
async function termReq(doc) {
  const url = `${BASE}/api/wiki/term?name=GCR&project=livegate` +
    (doc ? `&doc=${encodeURIComponent(doc)}` : '');
  const res = await fetch(url, { headers: authHeaders() });
  let body = null;
  try { body = await res.json(); } catch { /* non-json */ }
  return { status: res.status, body };
}
const termTxt = await termReq(`${FIXTURE}/实验记录.txt`);
ok('L8a term(doc=.txt) → 400 not-md-html（词条接口同闸，明确原因不静默）',
  termTxt.status === 400 && termTxt.body && termTxt.body.reason === 'not-md-html' &&
    /not askable/.test(termTxt.body.error || ''),
  JSON.stringify(termTxt));
const termMd = await termReq(`${FIXTURE}/笔记.md`);
ok('L8b term(doc=.md) → 闸放行，GCR 照常消解（闸不误伤）',
  termMd.status === 200 && termMd.body && termMd.body.resolved === true &&
    termMd.body.term && termMd.body.term.source === 'glossary',
  JSON.stringify(termMd));
const termMdQf = await termReq(`${FIXTURE}/笔记.md?tab=1#^gcr`);
ok('L8c term(doc=.md?query#frag) → 归一后放行照常消解（路径归一端到端）',
  termMdQf.status === 200 && termMdQf.body && termMdQf.body.resolved === true,
  JSON.stringify(termMdQf));

console.log(`\n=== ask-gate-live: ${pass} pass, ${fail} fail ===`);
process.exit(fail === 0 ? 0 : 1);
