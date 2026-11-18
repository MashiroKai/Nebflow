#!/usr/bin/env node
// ask-status-narrow-matrix.mjs — 状态行**窄屏折行矩阵**实测（作者 2026-11-18 细观感令）。
//
// 要证的一条：**折行时行首不许出现分隔符 `·`**。
// 三档视口 320 / 375 / 414（手机全屏页），逐档逐场景给出：
//   · 是否折行、行数、**每行行首首字符**、行首是否先落「非文本墨迹」（伪元素分隔符）
//   · 折行与不折行两种情形都要有读数
// 并把「段首型 ::before」这个**成因形态**注入回去，证明本量测确有检出能力
// （注入 ⇒ 行首检出 `·`/leadInk ⇒ exit 1；真实源码 ⇒ 全绿 exit 0）。
//
// Run:
//   MATRIX_WEB=<web 根> ASK_STATUS_EVIDENCE_DIR=<目录> node tests/ask-status-narrow-matrix.mjs
//   MATRIX_LEGACY=1 …   # 注入旧形态（段首 ::before）以自证检出能力
//
// 产出：<目录>/narrow-matrix/ 下的 PNG（暗/亮）与 narrow-matrix.json。
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, normalize, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.MATRIX_WEB || process.env.SHOTS_WEB || resolve(HERE, '../src/main/resources/web');
const OUT = process.env.ASK_STATUS_EVIDENCE_DIR
  || '/Users/kaiyu/stand-evidence/ask-status-visibility-20261118';
const DIR = join(OUT, 'narrow-matrix');
mkdirSync(DIR, { recursive: true });
// 证据批清单（scripts/check-evidence-manifests.mjs 的 A-face 契约）。
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({
  createdAt: new Date().toISOString(),
  project: 'stand',
  batch: 'ask-status-visibility-20261118',
  task: 'ask-status-narrow-matrix',
  source: 'tests/ask-status-narrow-matrix.mjs',
}, null, 2) + '\n');

const LEGACY = process.env.MATRIX_LEGACY === '1';
const TAG = LEGACY ? 'legacy-' : ''; // 注入档的产物不与真实档互相覆盖
const WIDTHS = (process.env.MATRIX_WIDTHS || '320,375,414').split(',').map(Number);
const PORT = Number(process.env.PORT || 8322);
const BASE = `http://127.0.0.1:${PORT}`;
const FILE = '记忆检索调优笔记.md';
const ABS = '/tmp/canvas-ask-status-fixture/' + FILE;
const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);
const SID = sidOf(ABS);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = normalize(join(WEB, p));
  if (!file.startsWith(normalize(WEB))) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let wsHandle = null;
const wsSend = (obj) => { if (wsHandle) wsHandle.send(JSON.stringify(obj)); };

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 375, height: 812 }, colorScheme: 'dark', hasTouch: true,
  deviceScaleFactor: 2, locale: 'zh-CN',
});
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('neblink_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('neblink_locale', 'zh-CN');
  localStorage.setItem('stand_sw', '0');
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
});
await ctx.route('**/api/**', (route) => {
  const p = decodeURIComponent(new URL(route.request().url()).pathname);
  if (p === '/api/sessions') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], activeId: 'sess-1' }) });
  }
  if (p === '/api/nf-ticket') {
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
    const tickets = {};
    for (const q of (Array.isArray(paths) ? paths : [])) tickets[q] = { t: 'tk', exp: Date.now() + 600000, uses: -1 };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets, rejected: [] }) });
  }
  if (p === '/api/nf-file') {
    return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: Buffer.from(`# ${FILE}\n\nfixture body\n`) });
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
});
await ctx.routeWebSocket(/\/ws/, (ws) => {
  wsHandle = ws;
  ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'sess-1' }));
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    else if (m.type === 'askOpen') {
      const filePath = (m.file && m.file.absPath) || ABS;
      ws.send(JSON.stringify({ type: 'askState', sessionId: sidOf(filePath), sessionKey: 'ask:' + sidOf(filePath).slice(4, 8), file: filePath, fileName: FILE, messages: [] }));
    }
  });
});

const page = await ctx.newPage();
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 220)));
await page.goto(BASE + '/');
await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
await page.evaluate(async () => { const m = await import('/js/mobile/navController.js'); m.navigateTo('canvas'); });
await sleep(600);
await page.evaluate(({ name, absPath }) => {
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nfixture body\n`, absPath, pinned: true },
  }));
}, { name: FILE, absPath: ABS });
await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"]`, { timeout: 15000 });
await page.waitForFunction(() => { const f = document.querySelector('.ask-fab'); return f && !f.classList.contains('gate-off'); }, null, { timeout: 15000 });
await page.click('.ask-fab');
await page.waitForFunction(() => { const d = document.querySelector('.ask-dock'); return d && !d.classList.contains('closed'); }, null, { timeout: 15000 });
await sleep(700);

if (LEGACY) {
  // 注入**旧形态**：分隔符回到段首（`::before`）。只为自证本量测的检出能力。
  await page.addStyleTag({ content:
    '.ask-state-next::before,.ask-state-diag::before{content:"·";margin-right:var(--ds-space-1);opacity:.6;}' });
}

/**
 * 逐行读数：按 `Range` 逐字符取矩形（伪元素不在 DOM 里，故另用元素级矩形做
 * 「行首非文本墨迹」检测——伪元素会给元素矩形贡献宽度）。
 * LEGACY 模式下先剥掉「跟随前一段」的真文本分隔符，制造纯段首形态。
 */
const measure = (legacy) => page.evaluate((legacyMode) => {
  const line = document.querySelector('[data-ask-state-line]');
  if (!line || line.hidden) return { hidden: true, lines: [], violations: [] };
  const parts = ['.ask-state-text', '.ask-state-next', '.ask-state-diag']
    .map((s) => line.querySelector(s)).filter(Boolean).filter((e) => !e.hidden);
  if (legacyMode) {
    for (const el of parts) el.textContent = el.textContent.replace(/\u00A0·\s*$/, '');
  }
  const chars = [];
  for (const el of parts) {
    for (const tn of el.childNodes) {
      if (tn.nodeType !== 3) continue;
      const txt = tn.textContent;
      for (let i = 0; i < txt.length; i++) {
        const r = document.createRange();
        r.setStart(tn, i); r.setEnd(tn, i + 1);
        const rects = r.getClientRects();
        if (!rects.length) continue;
        const rect = rects[0];
        if (rect.width === 0 && rect.height === 0) continue;
        chars.push({ ch: txt[i], top: rect.top, left: rect.left, part: el.className });
      }
    }
  }
  const sorted = chars.slice().sort((a, b) => a.top - b.top || a.left - b.left);
  const groups = [];
  for (const c of sorted) {
    const g = groups.find((x) => Math.abs(x.top - c.top) <= 6);
    if (g) { g.items.push(c); g.top = Math.min(g.top, c.top); }
    else groups.push({ top: c.top, items: [c] });
  }
  const rows = groups.map((g) => {
    g.items.sort((a, b) => a.left - b.left);
    const first = g.items.find((c) => c.ch !== '\u00A0' && !/\s/.test(c.ch)) || g.items[0];
    return {
      top: g.top, left: g.items[0].left, firstChar: first.ch,
      text: g.items.map((c) => c.ch).join('').replace(/\u00A0/g, ' '),
    };
  });
  // 元素级墨迹（含伪元素）——每行最左元素矩形 vs 最左文本字符矩形
  const ink = [];
  for (const el of parts) {
    for (const r of el.getClientRects()) ink.push({ part: el.className, top: r.top, left: r.left });
  }
  const violations = [];
  for (const g of rows) {
    const near = (x) => Math.abs(x.top - g.top) <= 8;
    const inkLeft = Math.min(...ink.filter(near).map((i) => i.left), Infinity);
    const chLeft = Math.min(...chars.filter(near).map((c) => c.left), Infinity);
    g.elementLeft = Number.isFinite(inkLeft) ? +inkLeft.toFixed(1) : null;
    g.textLeft = Number.isFinite(chLeft) ? +chLeft.toFixed(1) : null;
    if (g.firstChar === '·' || g.firstChar === '\u00B7') violations.push(`行首首字符是分隔符「${g.firstChar}」`);
    if (Number.isFinite(inkLeft) && Number.isFinite(chLeft) && inkLeft < chLeft - 2) violations.push('行首先落非文本墨迹（段首伪元素分隔符）');
  }
  const box = line.getBoundingClientRect();
  return { hidden: false, count: rows.length, lines: rows, violations, box: { w: +box.width.toFixed(1), h: +box.height.toFixed(1) }, containerLeft: +box.left.toFixed(1) };
}, legacy);

/* ── 场景驱动（每个视口按同一顺序把三种形态都摆出来）──────────────────── */
async function setState(kind) {
  if (kind === 'draft') {
    await page.fill('.ask-dock textarea', 'τ 为什么取 30 天？');
    await page.waitForTimeout(500);
  } else if (kind === 'waiting') {
    await page.press('.ask-dock textarea', 'Enter');
    await page.waitForTimeout(1200);
  } else if (kind === 'failed') {
    wsSend({ type: 'error', sessionId: SID, status: 500, message: 'upstream provider error: HTTP 500 Internal Server Error' });
    await page.waitForTimeout(1600);
  } else if (kind === 'offline') {
    await page.evaluate(async () => { const m = await import('/js/state.js'); m.default.ws = null; m.default.connected = false; });
    await page.waitForTimeout(600);
    await page.fill('.ask-dock textarea', '断连时问一句，看它怎么说。');
    await page.press('.ask-dock textarea', 'Enter');
    await page.waitForTimeout(1400);
  }
}
/** 恢复连接 + 收干净上一档的回合（新提交会清 failure，done 帧后驻留到期回空闲）。 */
async function restore() {
  await page.evaluate(async () => {
    const m = await import('/js/state.js');
    m.default.connected = true;
    m.default.ws = { readyState: 1, send: () => {}, close: () => {} };
  });
  await sleep(300);
  await page.fill('.ask-dock textarea', '重置上一档的回合。');
  await page.press('.ask-dock textarea', 'Enter');
  await page.waitForTimeout(900);
  wsSend({ type: 'done', sessionId: SID });
  await page.waitForTimeout(3300); // 终态驻留 2600ms + 余量
  await page.fill('.ask-dock textarea', '');
  await page.waitForTimeout(400);
}

const results = [];
const say = (s) => console.log(s);
say(`\n窄屏折行矩阵 · 视口 ${WIDTHS.join(' / ')} · 模式=${LEGACY ? 'LEGACY 注入（应红）' : '真实源码（应绿）'}`);
const CASES = [
  { kind: 'draft', name: 'draft-待发送（短文案+短提示）' },
  { kind: 'waiting', name: 'waiting-等待响应（文案+耗时，无提示）' },
  { kind: 'failed', name: 'failed-失败（长提示+诊断摘要）' },
  { kind: 'offline', name: 'offline-会话未连接（长提示，无诊断）' },
];
let fails = 0;

for (const w of WIDTHS) {
  await page.setViewportSize({ width: w, height: 812 });
  await restore();
  await sleep(400);  for (const c of CASES) {
    await setState(c.kind);
    const m = await measure(LEGACY);
    const shot = join(DIR, `${TAG}${w}-${c.kind}-dark.png`);
    await page.screenshot({ path: shot });
    let lightShot = null;
    if (c.kind === 'failed') {
      await page.emulateMedia({ colorScheme: 'light' });
      await sleep(200);
      lightShot = join(DIR, `${TAG}${w}-${c.kind}-light.png`);
      await page.screenshot({ path: lightShot });
      await page.emulateMedia({ colorScheme: 'dark' });
      await sleep(120);
    }
    const row = {
      width: w, case: c.kind, name: c.name, wrapped: m.count > 1, lineCount: m.count,
      lines: m.lines.map((g) => ({ firstChar: g.firstChar, left: g.left, text: g.text })),
      leadInk: m.lines.some((g) => Number.isFinite(g.elementLeft) && Number.isFinite(g.textLeft) && g.elementLeft < g.textLeft - 2),
      violations: m.violations, shot, lightShot, box: m.box,
    };
    if (m.violations.length) fails++;
    results.push(row);
    say(`  ${String(w).padStart(3)}px ${c.kind.padEnd(8)} 折行=${row.wrapped ? '是' : '否'} 行数=${row.lineCount}` +
      ` 行首=${row.lines.map((l) => JSON.stringify(l.firstChar)).join('|')}` +
      ` leadInk=${row.leadInk} ${m.violations.length ? '✗ ' + m.violations.join('；') : '✓'}`);
  }
}

const out = {
  web: WEB, out: OUT, dir: DIR, legacyInjection: LEGACY, widths: WIDTHS, cases: CASES.map((c) => c.kind),
  pageErrors, violations: fails, rows: results,
};
writeFileSync(join(DIR, `narrow-matrix${LEGACY ? '-legacy' : ''}.json`), JSON.stringify(out, null, 2) + '\n');
say(`\n读数：${join(DIR, `narrow-matrix${LEGACY ? '-legacy' : ''}.json`)}`);
say(LEGACY
  ? `LEGACY 注入：${fails} 处检出（期望 > 0 —— 量测确能抓住段首分隔符）`
  : `真实源码：${fails} 处违规（期望 0）`);

await ctx.close();
await browser.close();
server.close();
process.exit(fails === 0 && !LEGACY ? 0 : fails > 0 && LEGACY ? 0 : 1);
