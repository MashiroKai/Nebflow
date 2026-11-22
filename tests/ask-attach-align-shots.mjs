#!/usr/bin/env node
// ask-attach-align-shots.mjs — 主会话附件钮 `#attach-btn` vs Canvas Ask 附件钮 `.ask-attach`
// 并排对照取证（UI 小修 2026-11-xx，作者令逐字「ask canvas的附件按钮尺寸和位置应该和主会话保持一致」）。
//
// 以主会话为基准。每腿（desktop 1600×1000 / mobile 375×812）产出：
//   · main-input.png / ask-input.png   两输入条整条特写（DSF 2）
//   · compare-input.png                主会话 | Ask 并排（浏览器合成，带标）
//   · readings.json                    两钮的几何/样式/相对输入框锚点/hover 读数
// Run:
//   PHASE=before|after OUT=<证据目录> node tests/ask-attach-align-shots.mjs
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SHOTS_WEB || resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PHASE === 'before' ? 'before' : 'after';
// 默认落 <primary>/.stand/tmp/ask-attach-align/evidence/<PHASE>（worktree = <primary>/.stand/worktrees/<wt>）
const OUT = resolve(process.env.OUT || join(HERE, '..', '..', '..', 'tmp', 'ask-attach-align', 'evidence', PHASE));
mkdirSync(OUT, { recursive: true });
const DSF = 2;
const PORT = Number(process.env.PORT || 8394);
const BASE = `http://127.0.0.1:${PORT}`;
const FILE = '记忆检索调优笔记.md';
const ABS = '/tmp/ask-attach-align-fixture/' + FILE;
const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);
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
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const TRANSCRIPT = [
  { role: 'user', text: '第 1 问：检索窗口为什么要按龄衰减，而不是固定 30 天？' },
  { role: 'ai', text: '第 1 答：按龄衰减让近期证据权重更高，同时保留长尾。' },
];

async function boot(browserType, { mobile }) {
  const browser = await browserType.launch();
  const viewport = mobile ? { width: 375, height: 812 } : { width: 1600, height: 1000 };
  const ctx = await browser.newContext({ viewport, colorScheme: 'dark', hasTouch: !!mobile, deviceScaleFactor: DSF, locale: 'zh-CN' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  const page = await ctx.newPage();
  page.__errs = [];
  page.on('pageerror', (e) => page.__errs.push(String(e.message).slice(0, 200)));
  await ctx.route('**/api/**', (route) => {
    const p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], activeId: 'sess-1' }) });
    if (p === '/api/nf-ticket') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets: {}, rejected: [] }) });
    if (p === '/api/nf-file') return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: Buffer.from(`# ${FILE}\n\nfixture body\n`) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'sess-1' }));
    cfg(); ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ historyPage: true, type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      else if (m.type === 'askOpen') {
        const filePath = (m.file && m.file.absPath) || ABS;
        ws.send(JSON.stringify({
          type: 'askState', sessionId: sidOf(filePath), sessionKey: 'ask:' + sidOf(filePath).slice(4, 8),
          file: filePath, fileName: FILE, messages: TRANSCRIPT,
        }));
      }
    });
  });
  await page.goto(BASE + '/');
  await page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 30000 });
  await sleep(700);
  // 取证净化：隐藏 toast（fixture 的提示条会压在桌面 Ask 附件钮上，致 hover 命中被遮）。两腿同施。
  await page.addStyleTag({ content: '.nebflow-toast-hint,[class*="toast"]{display:none!important}' });
  return { browser, ctx, page };
}

async function openAskDock(page, mobile) {
  if (mobile) {
    await page.evaluate(async () => { const m = await import('/js/mobile/navController.js'); m.navigateTo('canvas'); });
    await sleep(700);
  }
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nfixture body\n`, absPath, pinned: true },
    }));
  }, { name: FILE, absPath: ABS });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"]`, { timeout: 15000 });
  if (mobile) await page.waitForFunction(() => { const f = document.querySelector('.ask-fab'); return f && !f.classList.contains('gate-off'); }, null, { timeout: 15000 });
  await sleep(300);
  await page.click('.ask-fab');
  await page.waitForFunction(() => { const d = document.querySelector('.ask-dock'); return d && !d.classList.contains('closed'); }, null, { timeout: 15000 });
  await sleep(500);
}

async function gotoMain(page, mobile) {
  if (mobile) {
    await page.evaluate(async () => { const m = await import('/js/mobile/navController.js'); m.navigateTo('chat'); });
    await sleep(900);
  }
  await page.waitForSelector('#input-bar #attach-btn', { timeout: 15000 });
  await sleep(400);
}

/** 读数：按钮本体 + svg + 相对输入条外壳/文本框的锚点 + 计算样式（不含 hover）。 */
const READ = ({ btnSel, shellSel, inputSel }) => {
  const btn = document.querySelector(btnSel);
  const shell = document.querySelector(shellSel);
  const input = document.querySelector(inputSel);
  if (!btn || !shell || !input) return { missing: [!btn && btnSel, !shell && shellSel, !input && inputSel].filter(Boolean) };
  const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? +n.toFixed(2) : null; };
  const cs = getComputedStyle(btn);
  const svg = btn.querySelector('svg');
  const ss = svg ? getComputedStyle(svg) : null;
  const sr = shell.getBoundingClientRect();
  const br = btn.getBoundingClientRect();
  const ir = input.getBoundingClientRect();
  const svgR = svg ? svg.getBoundingClientRect() : null;
  const scs = getComputedStyle(shell);
  return {
    cls: String(btn.className),
    rect: { w: num(br.width), h: num(br.height) },
    css: {
      width: cs.width, height: cs.height, padding: cs.padding, margin: cs.margin,
      border: `${cs.borderTopWidth} ${cs.borderTopStyle} ${cs.borderTopColor}`,
      radius: cs.borderTopLeftRadius, bg: cs.backgroundColor, color: cs.color,
      display: cs.display, alignItems: cs.alignItems, flex: cs.flex,
    },
    svg: svg ? { w: ss.width, h: ss.height, stroke: ss.strokeWidth, cls: String(svg.getAttribute('class') || '') } : null,
    svgRect: svgR ? { w: num(svgR.width), h: num(svgR.height) } : null,
    // 锚点：相对输入条外壳（左缘 / 垂直居中偏差）与文本框（按钮右缘到文本框左缘的间距）
    shell: { w: num(sr.width), h: num(sr.height), padL: scs.paddingLeft, padT: scs.paddingTop, padR: scs.paddingRight, padB: scs.paddingBottom, gap: scs.columnGap, radius: scs.borderTopLeftRadius },
    btnLeftInShell: num(br.left - sr.left),
    btnRightToInputGap: num(ir.left - br.right),
    btnVCenterOffset: num((br.top + br.height / 2) - (sr.top + sr.height / 2)),
    inputVCenterOffset: num((ir.top + ir.height / 2) - (sr.top + sr.height / 2)),
    inputH: num(ir.height),
    btnCenter: { x: br.left + br.width / 2, y: br.top + br.height / 2 },
    hitTop: (() => { const t = document.elementFromPoint(br.left + br.width / 2, br.top + br.height / 2); return t ? `${t.tagName.toLowerCase()}.${String(t.className && t.className.baseVal === undefined ? t.className : '').split(' ').join('.')}` : null; })(),
    hitIsBtn: (() => { const t = document.elementFromPoint(br.left + br.width / 2, br.top + br.height / 2); return !!t && (t === btn || btn.contains(t)); })(),
  };
};

async function readSurface(page, cfg, hoverOnce) {
  const r = await page.evaluate(READ, cfg);
  // hover 读数：鼠标移到按钮中心后再读计算样式
  const pt = r.btnCenter;
  await page.mouse.move(pt.x, pt.y);
  await sleep(350);
  const hover = await page.evaluate(READ, cfg);
  await page.mouse.move(0, 0);
  await sleep(200);
  return { rest: r, hover: { cls: hover.cls, bg: hover.css && hover.css.bg, color: hover.css && hover.css.color, border: hover.css && hover.css.border } };
}

async function shellClip(page, shellSel, pad = 12) {
  const r = await page.evaluate((sel) => { const b = document.querySelector(sel).getBoundingClientRect(); return { x: b.left, y: b.top, w: b.width, h: b.height }; }, shellSel);
  const vp = page.viewportSize();
  const x = Math.max(0, Math.floor(r.x - pad)), y = Math.max(0, Math.floor(r.y - pad));
  const w = Math.min(vp.width - x, Math.ceil(r.w + 2 * pad)), h = Math.min(vp.height - y, Math.ceil(r.h + 2 * pad));
  return { x, y, width: w, height: h };
}

async function compose(browser, title, cols, rows, file) {
  const html = `<!doctype html><meta charset="utf-8"><style>
  body{margin:0;background:#141618;color:#e8ebf0;font:600 13px -apple-system,'PingFang SC',sans-serif}
  table{border-collapse:collapse} td{padding:8px 10px;vertical-align:middle}
  th{padding:6px 10px;color:#9aa4b2;font:600 12px -apple-system,'PingFang SC',sans-serif}
  img{display:block;border:1px solid #2c3138;border-radius:8px;background:#0b0d0f}
  .rowlab{color:#9aa4b2;font:600 12px -apple-system,'PingFang SC',sans-serif;white-space:nowrap}
  h1{font:600 15px -apple-system,'PingFang SC',sans-serif;margin:0 0 10px}
  </style><h1>${title}</h1><table><tr><th></th>${cols.map((c) => `<th>${c}</th>`).join('')}</tr>
  ${rows.map((r) => `<tr><td class="rowlab">${r.label}</td>${r.cells.map((s) => `<td><img src="${s}"></td>`).join('')}</tr>`).join('')}
  </table>`;
  const f = join(OUT, `_compose-${file}.html`);
  writeFileSync(f, html);
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  await p.goto('file://' + f);
  await p.waitForFunction(() => [...document.images].every((i) => i.complete), null, { timeout: 10000 });
  await (await p.$('table')).screenshot({ path: join(OUT, file + '.png') });
  await ctx.close();
}

const results = { phase: PHASE, web: WEB, dsf: DSF, screens: {} };
const MAIN = { btnSel: '#input-bar #attach-btn', shellSel: '#input-bar', inputSel: '#input-bar #input' };
const ASK = { btnSel: '.ask-dock .ask-attach', shellSel: '.ask-dock .ask-input', inputSel: '.ask-dock .ask-input textarea' };

for (const screen of ['desktop', 'mobile']) {
  const mobile = screen === 'mobile';
  const dir = join(OUT, screen);
  mkdirSync(dir, { recursive: true });
  const out = {};

  const A = await boot(chromium, { mobile });
  await gotoMain(A.page, mobile);
  out.main = await readSurface(A.page, MAIN);
  const mainClip = await shellClip(A.page, MAIN.shellSel);
  await A.page.screenshot({ path: join(dir, 'main-input.png'), clip: mainClip });
  out.mainErrs = A.page.__errs.slice();
  await A.ctx.close(); await A.browser.close();

  const B = await boot(chromium, { mobile });
  await openAskDock(B.page, mobile);
  out.ask = await readSurface(B.page, ASK);
  const askClip = await shellClip(B.page, ASK.shellSel);
  await B.page.screenshot({ path: join(dir, 'ask-input.png'), clip: askClip });
  out.askErrs = B.page.__errs.slice();
  await B.ctx.close(); await B.browser.close();

  const cmp = await boot(chromium, { mobile: false });
  await compose(cmp.browser,
    `[${PHASE}] ${screen} 输入条附件钮 — 主会话 #attach-btn vs Canvas Ask .ask-attach`,
    ['主会话 #attach-btn', 'Canvas Ask .ask-attach'],
    [{ label: 'input', cells: [join(dir, 'main-input.png'), join(dir, 'ask-input.png')] }],
    `compare-input-${screen}`);
  await cmp.ctx.close(); await cmp.browser.close();

  results.screens[screen] = out;
}

writeFileSync(join(OUT, 'readings.json'), JSON.stringify(results, null, 2) + '\n');
server.close();
console.log(`[${PHASE}] → ${OUT}`);
console.log(JSON.stringify(results, null, 1));
