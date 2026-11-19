#!/usr/bin/env node
// thinking-timer-proximity.spec.mjs — 「思考中」行的**计时紧贴文字**验收
// （ui-unification-20261103 追加令，作者令 2026-11-04 11:22 逐字：「这个思考中的
// 计时，这个时间和文字靠近，贴在一起，不要分这么远」）。
//
// 报障面（真实形态）：回合运行行的三个件 —— `.nf-wl-dots`（脉动点/折叠入口）→
// `.nf-wl-label`（「思考中」…）→ `.nf-wl-hint`（`14s` 计时）。旧 CSS 两处叠加把
// 计时甩到行右缘：
//   ① `.nf-wl-label { flex: 1 1 auto }` —— label 盒子吃掉整行剩余宽度；
//   ② `.nf-wl-hint { margin-left: auto }` —— 计时再被 auto 外边距推到行尾。
// ⇒ 文字与计时之间的可见空隙 = 该行剩余空间（宽面板实测数百 px），读作两段无关
// 信息。作者要的是**一个视觉群组**：dots · 思考中 14s，间隔只留一个正常字距。
//
// 判据（同一脚本两态各跑一次；改前必红 / 改后必绿）：
//   p1 dots / label / hint 三件同时在行上（前置面）；
//   p2 **文字墨迹右缘 ↔ 计时左缘**的间距 ≤ px 阈值（改前必红 >48px）——
//      用 Range 取 label 文本墨迹右缘，不是 label 盒子右缘（盒子宽度正是旧缺陷
//      的载体，拿盒子量会自我掩盖：flex 生长的盒子里，盒-盒间距恒等于 gap）；
//   p3 计时**没有**被推到行右缘（hint.right ≤ 行盒右缘 − 20px）——
//      p2 的独立表述：防止「盒子还在、只是被负 margin 拉回」的假绿；
//   p4 次序与同排：dots.right ≤ label.left ≤ label.right ≤ hint.left，且三件
//      垂直中线同带（|中线差| ≤ 2px）⇒ 读作一行；
//   p5 行盒剩余空档有界（不因两端对齐被撑满）；
//   m2..m4 手机腿（390×844，同一行结构、同一段 CSS ⇒ 同一判据）；
//   z1 零 pageerror。
//
// 同族前身：tests/thinking-timer-phase.spec.mjs（2026-10-28 同一句报障的**计时
// 数值**面 —— 那次修的是「1m58s 到底算多久」，本批修的是「它离文字多远」；两条
// 判据正交，本 spec 复用其 harness 形态但不重复其断言）。
//
// Run:
//   node tests/thinking-timer-proximity.spec.mjs                 # 改后（预期全绿）
//   PROX_WEB=<git archive 出的改前 web 根> node tests/thinking-timer-proximity.spec.mjs
//                                                                # 改前（预期红）
// 取证：PROX_EVIDENCE_DIR=<目录> PROX_PHASE=before|after（默认 …/ui-unification-20261103/
//       thinking-timer-proximity）→ 截图 + readings.json + run.log。
// 自包含：零后端（node:http 静态伺服 + routeWebSocket 桩），随机高端口，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.PROX_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PROX_PHASE === 'before' ? 'before' : 'after';
const OUT = join(process.env.PROX_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/thinking-timer-proximity', PHASE);
mkdirSync(OUT, { recursive: true });

const SID = 's-think-proximity';
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

// ── 桩服务端 ────────────────────────────────────────────────────────
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
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '思考计时贴近度' }], activeId: SID, folders: [] }));
}
const serverSends = (frames) => { for (const f of frames) currentWs.send(JSON.stringify(f)); };

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

/** 行内三件 + 文字墨迹的几何读数（同一 evaluate = 同一帧，无中间态）。 */
const READ = `(() => {
  const dots = document.querySelector('#chat .nf-wl-dots');
  const label = document.querySelector('#chat .nf-wl-label');
  const hint = document.querySelector('#chat .nf-wl-hint');
  if (!dots || !label || !hint) return { missing: true };
  const r = (el) => { const b = el.getBoundingClientRect(); return { left: b.left, right: b.right, top: b.top, bottom: b.bottom, w: b.width, h: b.height, cy: b.top + b.height / 2 }; };
  // 文字墨迹右缘：Range 覆盖 label 的文本节点（nowrap + clip ⇒ 未溢出时 = 本征宽）。
  const rg = document.createRange();
  rg.selectNodeContents(label);
  const textRect = rg.getBoundingClientRect();
  const item = label.parentElement;
  const ri = item ? item.getBoundingClientRect() : null;
  const csL = getComputedStyle(label);
  const csH = getComputedStyle(hint);
  const csI = item ? getComputedStyle(item) : null;
  return {
    dots: r(dots), label: r(label), hint: r(hint),
    textInk: { left: textRect.left, right: textRect.right, w: textRect.width },
    item: ri ? { left: ri.left, right: ri.right, top: ri.top, bottom: ri.bottom, w: ri.width, h: ri.height } : null,
    labelFlex: csL.flexGrow + ' ' + csL.flexShrink + ' ' + csL.flexBasis,
    hintMarginLeft: csH.marginLeft,
    itemGap: csI ? csI.gap : null,
    labelText: (label.textContent || '').trim(),
    hintText: (hint.textContent || '').trim(),
    gapTextToHint: hint.getBoundingClientRect().left - textRect.right,
    hintRightToItemRight: ri ? (ri.right - hint.getBoundingClientRect().right) : null,
    vw: innerWidth, vh: innerHeight,
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

  // 真用户派发开轮 → 引擎开轮帧 → dots 行到场（思考中 + 计时）。
  await page.fill('#input', '帮我检索一下最近的记忆调优笔记');
  await page.keyboard.press('Enter');
  await sleep(260);
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'thinking', sessionId: SID },
  ]);
  await sleep(1400); // 计时走到 ≥1s（formatLive 的 `14s` 形态）
  await page.waitForSelector('#chat .nf-wl-hint', { timeout: 10000 });

  const THRESH = 16; // 「一个正常字距」上限：行 gap 10px 的同带档（<= 阈 ⇒ 贴合）
  const d = await page.evaluate(READ);
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify({ phase: PHASE, web: WEB, threshold: THRESH, desktop: d, checks }, null, 2));
  // eslint-disable-next-line no-console
  console.log('READ ' + JSON.stringify({
    labelFlex: d.labelFlex, hintMarginLeft: d.hintMarginLeft, itemGap: d.itemGap,
    gapTextToHint: d.gapTextToHint, labelW: d.label ? d.label.w : null, textW: d.textInk ? d.textInk.w : null, itemW: d.item ? d.item.w : null,
  }));

  ok('p1 dots / label / hint 三件同时在行上（前置面）',
    !d.missing, JSON.stringify({ labelText: d.labelText, hintText: d.hintText }));
  ok(`p2 文字墨迹右缘 ↔ 计时左缘 ≤ ${THRESH}px（作者令：贴在一起，一个正常字距）`,
    !d.missing && d.gapTextToHint <= THRESH,
    `gap=${d.missing ? 'n/a' : d.gapTextToHint.toFixed(1)}px（label 盒 ${d.label ? d.label.w.toFixed(1) : '-'} / 文字墨迹 ${d.textInk ? d.textInk.w.toFixed(1) : '-'} / 行盒 ${d.item ? d.item.w.toFixed(1) : '-'}）`);
  ok('p3 计时没有被推到行右缘（hint 右侧留白 > 20px，不贴行尾）',
    !d.missing && d.hintRightToItemRight > 20,
    `hint 右侧留白=${d.missing ? 'n/a' : d.hintRightToItemRight.toFixed(1)}px`);
  ok('p4 次序与同排：dots → label → hint 严格自左至右；label 与 hint 垂直中线同带（≤2px）',
    !d.missing && d.dots.right <= d.label.left + 0.5 && d.label.right <= d.hint.left + 0.5
    && Math.abs(d.label.cy - d.hint.cy) <= 2
    && d.dots.bottom >= d.label.top - 0.5 && d.dots.top <= d.label.bottom + 0.5,
    `dots.right=${d.dots && d.dots.right.toFixed(1)} label=${d.label && d.label.left.toFixed(1)}..${d.label && d.label.right.toFixed(1)} hint.left=${d.hint && d.hint.left.toFixed(1)} cy=${[d.dots && d.dots.cy, d.label && d.label.cy, d.hint && d.hint.cy].map((n) => (n == null ? '-' : n.toFixed(1))).join('/')}`);
  ok(`p5 相邻两段间隙都 ≤ ${THRESH}px（dots→文字、文字→计时 ⇒ 整行读作一个群组）`,
    !d.missing && (d.label.left - d.dots.right) <= THRESH && (d.hint.left - d.label.right) <= THRESH,
    `dots→label=${d.missing ? 'n/a' : (d.label.left - d.dots.right).toFixed(1)}px label→hint=${d.missing ? 'n/a' : (d.hint.left - d.label.right).toFixed(1)}px（gap=${d.itemGap}）`);
  if (d.label) {
    await page.screenshot({ path: join(OUT, 'desktop-thinking-row.png'), clip: { x: 0, y: Math.max(0, d.label.top - 40), width: 900, height: 120 } }).catch(() => {});
  }
  await page.screenshot({ path: join(OUT, 'desktop-full.png') }).catch(() => {});
  await ctx.close();

  // ── 手机腿（同一行结构、同一段 CSS）──────────────────────────────
  const mctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true });
  await mctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const mp = await mctx.newPage();
  mp.on('pageerror', (e) => pageErrors.push('mobile: ' + String(e.message).slice(0, 200)));
  await mp.routeWebSocket(/\/ws/, (ws) => wireUp(ws));
  await mp.goto(BASE + '/index.html');
  await mp.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1200);
  await mp.fill('#input', '手机上想想再答');
  await mp.keyboard.press('Enter');
  await sleep(260);
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'thinking', sessionId: SID },
  ]);
  await sleep(1400);
  await mp.waitForSelector('#chat .nf-wl-hint', { timeout: 10000 });
  const md = await mp.evaluate(READ);
  writeFileSync(join(OUT, 'readings-mobile.json'), JSON.stringify({ phase: PHASE, mobile: md }, null, 2));
  ok(`m2 手机腿：文字 ↔ 计时 ≤ ${THRESH}px（同一条实现、同一判据）`,
    !md.missing && md.gapTextToHint <= THRESH,
    `gap=${md.missing ? 'n/a' : md.gapTextToHint.toFixed(1)}px（行盒 ${md.item ? md.item.w.toFixed(1) : '-'}）`);
  ok('m3 手机腿：计时不贴行右缘（右侧留白 > 20px）',
    !md.missing && md.hintRightToItemRight > 20,
    `留白=${md.missing ? 'n/a' : md.hintRightToItemRight.toFixed(1)}px`);
  ok('m4 手机腿：次序与同排（dots → label → hint；label/hint 中线同带 ≤2px）',
    !md.missing && md.dots.right <= md.label.left + 0.5 && md.label.right <= md.hint.left + 0.5
    && Math.abs(md.label.cy - md.hint.cy) <= 2
    && md.dots.bottom >= md.label.top - 0.5 && md.dots.top <= md.label.bottom + 0.5,
    `cy=${[md.dots && md.dots.cy, md.label && md.label.cy, md.hint && md.hint.cy].map((n) => (n == null ? '-' : n.toFixed(1))).join('/')}`);
  await mp.screenshot({ path: join(OUT, 'mobile-full.png') }).catch(() => {});
  await mctx.close();

  ok('z1 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));
} finally {
  await browser.close().catch(() => {});
  await new Promise((r) => server.close(r));
}

writeFileSync(join(OUT, 'run.log'), checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
const pass = checks.filter((c) => c.ok).length;
// eslint-disable-next-line no-console
console.log(`\n${PHASE}: ${pass} PASS / ${checks.length - pass} FAIL`);
process.exit(failures ? 1 : 0);
