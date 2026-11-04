#!/usr/bin/env node
// rcpt-diffchip-hit.spec.mjs — 完工卡 **diff 徽标（+a −r）点击面**验收
// （ui-unification-20261103 追加令，作者令 2026-11-04「diff 徽标点击退化」）。
//
// 报障面：`.nf-rcpt-note`（卡面单行右段的 `+445 −51` mono 块）是 diff 面板入口
// （click → diffPanel.toggleDiffBatch → 左栏「变更文件」+ Canvas 阅读面）。它的
// **可见本体**只有 ~18px 高（10px/1.3 字阶的 mono 段 + 1px padding），命中盒当时
// 就等于本体 ⇒ 18.3px 高的点目标：
//   · 远低于 --ds-hit-row 44（Apple HIG / WCAG 2.5.5 触控下限）；
//   · 鼠标要精确瞄准一条细带、手机上基本点不中 ⇒ 读作「点了没反应」（退化）；
//   · 与左侧 .nf-rcpt-pill（整颗胶囊可点）同排却只有 1/3 的命中高度，同一行的
//     两颗块手感不一致。
//
// 判据（同一脚本两态各跑一次；改前必红 / 改后必绿）：
//   h1 徽标是 button（可点语义在场，非静态 span）；
//   h2 **命中带高度 ≥ 44px**（沿徽标水平中线逐 px 扫 elementFromPoint，取连续
//      命中带；改前实测 ~18px 必红）；
//   h3 命中带关于徽标本体**上下对称**（上沿外扩 ≈ 下沿外扩，容差 2px）——
//      说明是刻意补足热区，不是被某个祖先容器裁掉一半；
//   h4 命中带**不侵占左邻胶囊**（在 pill 右缘 − 2px 处取点，不得命中徽标）；
//   h5 hover 反馈在场（cursor:pointer ⇒ 可点感不退化）；
//   h6 端到端：点徽标 → 左栏 #panel-filechanges active + 行数 = files 数 +
//      Canvas 打开首份文件页签 + aria-pressed 翻 true；
//   h7 再点一次 → 侧栏收起（既有「已打开时再点收起」语义），不抛错；
//   z1 零 pageerror。
//
// Run:
//   node tests/rcpt-diffchip-hit.spec.mjs                       # 改后（预期全绿）
//   RCPT_WEB=<git archive 出的改前 web 根> node tests/rcpt-diffchip-hit.spec.mjs
// 取证：RCPT_EVIDENCE_DIR=<目录> RCPT_PHASE=before|after。
// 自包含：node:http 静态伺服 + routeWebSocket 桩（真渲染链：WS 帧 → main.js
// onMessage('user') → renderInjectedBubble → injectedRows.buildInjectedRow）。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.RCPT_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.RCPT_PHASE === 'before' ? 'before' : 'after';
const OUT = join(process.env.RCPT_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/rcpt-diffchip-hit', PHASE);
mkdirSync(OUT, { recursive: true });

const SID = 's-rcpt-diffchip';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff' };

let failures = 0;
const checks = [];
function ok(name, cond, extra = '') {
  // eslint-disable-next-line no-console
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  checks.push({ name, ok: !!cond, extra });
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 真实形态载荷：files 面在场（可点）⇒ button；合计 +152 −34。
const PAYLOAD = {
  nodeId: 'rcpt-chip-probe', generatedAt: 1793515065507, root: '/Users/kaiyu/Claude code/Stand',
  files: [
    { path: 'src/main/resources/web/js/chat/fixture-a.js', changeType: 'modified', added: 76, removed: 17, source: 'tool-ledger' },
    { path: 'src/main/resources/web/js/chat/fixture-b.js', changeType: 'modified', added: 76, removed: 17, source: 'tool-ledger' },
  ],
  merge: { commit: 'b6ceb9d0e', added: 152, removed: 34 },
};

let currentWs = null;
const conns = [];
function wireUp(ws) {
  currentWs = ws;
  conns.push(1);
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{},"console":{"inputMode":"queue"}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'diff 徽标命中盒' }], activeId: SID, folders: [] }));
}

const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (path.startsWith('/api/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

/** 命中带扫描：徽标水平中线逐 px 上/下探，取 elementFromPoint 仍落在徽标
 *  （或其伪元素=本体/子件）的连续区段 —— 这是「可点高度」的唯一可信读数：
 *  getBoundingClientRect 只给可见本体，::after 外扩不进 rect。 */
const HITBAND = `(() => {
  const note = document.querySelector('.nf-rcpt-note');
  if (!note) return { missing: true };
  const nb = note.getBoundingClientRect();
  const cx = nb.left + nb.width / 2;
  const cy = nb.top + nb.height / 2;
  const owns = (x, y) => {
    const e = document.elementFromPoint(x, y);
    return !!e && (e === note || note.contains(e));
  };
  let up = 0;
  while (up < 60 && owns(cx, cy - up - 1)) up++;
  let down = 0;
  while (down < 60 && owns(cx, cy + down + 1)) down++;
  const cs = getComputedStyle(note);
  const pill = document.querySelector('.nf-rcpt-pill');
  const pillRight = pill ? pill.getBoundingClientRect().right : null;
  const atPillEdge = pillRight == null ? null : (() => {
    const e = document.elementFromPoint(pillRight - 2, cy);
    return e ? (e === note || note.contains(e) ? 'note' : (e.tagName.toLowerCase() + '.' + String(e.className || '').split(/\\s+/).filter(Boolean).join('.'))) : 'null';
  })();
  return {
    tag: note.tagName,
    rect: { left: nb.left, right: nb.right, top: nb.top, bottom: nb.bottom, w: nb.width, h: nb.height, cy },
    hitHeight: up + down + 1, /* 中线左右各探出的 px + 中线上那一个 px */
    hitUp: up, hitDown: down,
    cursor: cs.cursor,
    afterInsetTop: getComputedStyle(note, '::after').top,
    atPillEdge,
    noteText: (note.textContent || '').trim(),
  };
})()`;

const browser = await chromium.launch();
const pageErrors = [];
try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => pageErrors.push(String(e.message).slice(0, 200)));
  await page.routeWebSocket(/\/ws/, (ws) => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1200);
  ok('boot 后在线', conns.length > 0);

  await page.evaluate((P) => import('/js/state.js').then((mod) => {
    const s = mod.default;
    s.ws.onmessage({ data: JSON.stringify({
      type: 'user', injected: true, sessionId: s.activeSessionId, timestamp: Date.now(),
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: '委托完成：徽标命中盒探针。', fileChanges: P,
    }) });
  }), PAYLOAD);
  await page.waitForSelector('.nf-rcpt-note', { timeout: 10000 });
  await sleep(250);

  const h = await page.evaluate(HITBAND);
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify({ phase: PHASE, web: WEB, h, checks }, null, 2));
  // eslint-disable-next-line no-console
  console.log('READ ' + JSON.stringify({ tag: h.tag, rectH: h.rect && h.rect.h, hitHeight: h.hitHeight, up: h.hitUp, down: h.hitDown, atPillEdge: h.atPillEdge, afterTop: h.afterInsetTop }));

  ok('h1 徽标是 button（可点语义在场）', h.tag === 'BUTTON', `tag=${h.tag} text=${h.noteText}`);
  ok('h2 命中带高度 ≥ 44px（HIG / WCAG 2.5.5 触控下限；改前 ~18px 必红）',
    h.hitHeight >= 43.5, `hit=${h.hitHeight.toFixed(1)}px（本体 ${h.rect.h.toFixed(1)}px，上扩 ${h.hitUp} / 下扩 ${h.hitDown}）`);
  ok('h3 命中带上下对称外扩（|上−下| ≤ 2px ⇒ 热区是刻意补足，不是被容器裁掉一半）',
    Math.abs(h.hitUp - h.hitDown) <= 2, `上 ${h.hitUp} / 下 ${h.hitDown}`);
  ok('h4 命中带不侵占左邻胶囊（pill 右缘 −2px 处不落到徽标上）',
    h.atPillEdge !== 'note', `pill 边缘命中=${h.atPillEdge}`);
  ok('h5 可点感在场（cursor: pointer）', h.cursor === 'pointer', `cursor=${h.cursor}`);

  await page.screenshot({ path: join(OUT, 'desktop-chip.png'), clip: { x: Math.max(0, h.rect.left - 240), y: Math.max(0, h.rect.top - 20), width: 420, height: 80 } }).catch(() => {});

  // ── 端到端：点徽标 → 左栏「变更文件」+ Canvas 阅读面 ──────────────
  await page.click('.nf-rcpt-note');
  await page.waitForTimeout(900);
  const e2e = await page.evaluate(() => {
    const p = document.getElementById('panel-filechanges');
    const tabs = [...document.querySelectorAll('#canvas-tabs .canvas-tab, #canvas-tab-bar .canvas-tab')].map((t) => t.textContent.trim());
    return {
      panelActive: !!p && p.classList.contains('active'),
      rows: p ? p.querySelectorAll('.nf-fc').length : -1,
      notePressed: document.querySelector('.nf-rcpt-note')?.getAttribute('aria-pressed'),
      tabs,
    };
  });
  writeFileSync(join(OUT, 'readings-e2e.json'), JSON.stringify({ phase: PHASE, e2e }, null, 2));
  ok('h6 点徽标 → 左栏面板 active + 行数 = files 数（2）+ Canvas 打开首份文件 + aria-pressed=true',
    e2e.panelActive && e2e.rows === 2 && e2e.tabs.some((t) => t.includes('fixture-a')) && e2e.notePressed === 'true',
    JSON.stringify(e2e));
  await page.screenshot({ path: join(OUT, 'desktop-open.png') }).catch(() => {});

  await page.click('.nf-rcpt-note');
  await page.waitForTimeout(600);
  const again = await page.evaluate(() => {
    const p = document.getElementById('panel-filechanges');
    return { panelActive: !!p && p.classList.contains('active'), sidebar: document.getElementById('sidebar')?.className || '' };
  });
  ok('h7 再点一次 = 收起（已打开语义；不抛错即链路健康）', !again.panelActive, JSON.stringify(again));

  ok('z1 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));
  await ctx.close();
} finally {
  await browser.close().catch(() => {});
  await new Promise((r) => server.close(r));
}

writeFileSync(join(OUT, 'run.log'), checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
const pass = checks.filter((c) => c.ok).length;
// eslint-disable-next-line no-console
console.log(`\n${PHASE}: ${pass} PASS / ${checks.length - pass} FAIL`);
process.exit(failures ? 1 : 0);
