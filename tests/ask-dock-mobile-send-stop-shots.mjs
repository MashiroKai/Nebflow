#!/usr/bin/env node
// ask-dock-mobile-send-stop-shots.mjs — 手机端 Ask 面板 send/stop 收档批对照截图
// （作者令 2026-11-04 13:52 逐字：「手机端的发送按钮和终止按钮 小一点」）。
//
// 一个脚本、同一状态、同一视口宽（390×844 手机层、暗档、hasTouch）跑两次：
//   改前（本批 CSS 落地之前的树）→ <out>/before/   改后 → <out>/after/
// 每腿产出：
//   · mobile-ask-panel.png      整屏（面板展开、转录回放到位、rest 态）
//   · mobile-ask-inputrow.png   输入条裁切放大（两钮几何的直接视觉面）
//   · mobile-ask-busy.png       busy 态（stop 上位，双钮同档的对照面）
//   · readings.json            两钮计算几何（宽/高/圆角/图标/内距/命中盒外扩）
//
// Run:
//   SHOTS_WEB=<web 根> SENDSTOP_PHASE=before|after \
//   SENDSTOP_EVIDENCE_DIR=<目录> node tests/ask-dock-mobile-send-stop-shots.mjs
// 缺省：SHOTS_WEB = 本仓 src/main/resources/web；证据目录 = ui-unification-20261103/mobile-send-stop
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { dirname, join, normalize, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SHOTS_WEB || resolve(HERE, '../src/main/resources/web');
const PHASE = process.env.SENDSTOP_PHASE === 'before' ? 'before' : 'after';
const OUT = join(process.env.SENDSTOP_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/mobile-send-stop', PHASE);
mkdirSync(OUT, { recursive: true });

const PORT = Number(process.env.PORT || 8291);
const BASE = `http://127.0.0.1:${PORT}`;
const SID = 'askres-session';
const FILE = '记忆检索调优笔记.md';
const ABS = '/tmp/canvas-ask-resize-fixture/' + FILE;
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

const TRANSCRIPT = Array.from({ length: 14 }, (_, i) => ([
  { role: 'user', text: `第 ${i + 1} 问：检索窗口为什么要按龄衰减，而不是固定 30 天？` },
  { role: 'ai', text: `第 ${i + 1} 答：按龄衰减让近期证据权重更高，同时保留长尾；固定 30 天会在跨季度复盘时丢失有效样本。` },
])).flat();

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 390, height: 844 }, colorScheme: 'dark', hasTouch: true,
  deviceScaleFactor: 2, locale: 'zh-CN',
});
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('stand_sw', '0');
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
});
await ctx.route('**/api/**', (route) => {
  const p = decodeURIComponent(new URL(route.request().url()).pathname);
  if (p === '/api/sessions') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
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
  const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  cfg(); ses();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    else if (m.type === 'askOpen') {
      const filePath = (m.file && m.file.absPath) || ABS;
      ws.send(JSON.stringify({
        type: 'askState', sessionId: sidOf(filePath), sessionKey: 'ask:' + sidOf(filePath).slice(4, 8),
        file: filePath, fileName: FILE, messages: TRANSCRIPT,
      }));
    }
  });
});

const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e.message).slice(0, 200)));
await page.goto(BASE + '/');
await page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });

// 手机层：先导航到 canvas 页（askDock.syncGate 的 mobilePageOk），再挂 md 文件卡
await page.evaluate(async () => {
  const m = await import('/js/mobile/navController.js');
  m.navigateTo('canvas');
});
await sleep(600);
await page.evaluate(({ name, absPath }) => {
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nfixture body\n`, absPath, pinned: true },
  }));
}, { name: FILE, absPath: ABS });
await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"]`, { timeout: 15000 });
await page.waitForFunction(() => {
  const f = document.querySelector('.ask-fab');
  return f && !f.classList.contains('gate-off');
}, null, { timeout: 15000 });
await page.click('.ask-fab');
await page.waitForFunction(() => {
  const d = document.querySelector('.ask-dock');
  return d && !d.classList.contains('closed');
}, null, { timeout: 15000 });
await page.waitForFunction(() => document.querySelectorAll('.ask-dock .ask-bub').length > 10, null, { timeout: 15000 });
await sleep(500);

/* ── rest 态：整屏 + 输入条裁切 ── */
await page.screenshot({ path: join(OUT, 'mobile-ask-panel.png') });
const clip = await page.evaluate(() => {
  const b = document.querySelector('.ask-dock .ask-input').getBoundingClientRect();
  const x = Math.max(0, b.left - 10);
  const y = Math.max(0, b.top - 10);
  return { x, y, width: Math.min(window.innerWidth - x, b.width + 20), height: Math.min(160, b.height + 20) };
});
await page.screenshot({ path: join(OUT, 'mobile-ask-inputrow.png'), clip });

const read = (phase) => page.evaluate((ph) => {
  const pick = (el) => {
    if (!el) return null;
    const cs = getComputedStyle(el);
    const svg = el.querySelector('svg');
    const ss = svg ? getComputedStyle(svg) : null;
    const af = getComputedStyle(el, '::after');
    const r = el.getBoundingClientRect();
    // 命中盒 = ::after 解析尺寸（inset 相对本体 padding 盒解：44 本体 ⇒ −1px，
    // 命中面落回 44×44 边框盒）。display:none 时 Chrome 返回未解析 min() 串 ⇒ null。
    const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : null; };
    const bw = num(cs.borderLeftWidth) || 0;
    const ins = [num(af.top), num(af.right), num(af.bottom), num(af.left)];
    const hitBox = ins.every((n) => n !== null)
      ? { w: +((r.width - 2 * bw) - ins[3] - ins[1]).toFixed(2), h: +((r.height - 2 * bw) - ins[0] - ins[2]).toFixed(2) }
      : null;
    const host = el.closest('.ask-input');
    const hr = host ? host.getBoundingClientRect() : null;
    return {
      phase: ph, cls: String(el.className || ''),
      width: cs.width, height: cs.height, borderRadius: cs.borderRadius,
      padding: cs.padding, iconW: ss ? ss.width : null, iconH: ss ? ss.height : null,
      hitInset: { top: af.top, right: af.right, bottom: af.bottom, left: af.left },
      hitBox,
      insideInput: hr ? (r.left >= hr.left - 0.5 && r.right <= hr.right + 0.5 && r.top >= hr.top - 0.5 && r.bottom <= hr.bottom + 0.5) : null,
      box: { w: +r.width.toFixed(1), h: +r.height.toFixed(1), left: +r.left.toFixed(1), top: +r.top.toFixed(1) },
      color: cs.color,
    };
  };
  const bodyCs = getComputedStyle(document.body);
  return {
    dock: {
      send: pick(document.querySelector('.ask-dock .ask-send')),
      stop: pick(document.querySelector('.ask-dock .ask-stop')),
    },
    tokens: {
      mcvSizeCircle: bodyCs.getPropertyValue('--mcv-size-circle').trim(),
      mcvIcon: bodyCs.getPropertyValue('--mcv-icon').trim(),
      dsTurnBtnSize: bodyCs.getPropertyValue('--ds-turn-btn-size').trim(),
      dsTurnBtnIcon: bodyCs.getPropertyValue('--ds-turn-btn-icon').trim(),
      cvSizeCircle: bodyCs.getPropertyValue('--cv-size-circle').trim(),
      mtapLg: bodyCs.getPropertyValue('--mtap-lg').trim(),
    },
  };
}, phase);

const rest = await read('rest');

/* ── busy 态：stop 上位（composer.js applyTurnChrome 同款语句）── */
await page.evaluate(() => {
  const s = document.querySelector('.ask-dock .ask-send');
  const t = document.querySelector('.ask-dock .ask-stop');
  if (s && t) { s.style.display = 'none'; t.style.display = 'flex'; }
});
await sleep(250);
await page.screenshot({ path: join(OUT, 'mobile-ask-busy.png') });
const busy = await read('busy');

const readings = { phase: PHASE, web: WEB, viewport: { w: 390, h: 844 }, rest, busy, pageErrors: errors };
writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2) + '\n');
console.log(`[${PHASE}] tokens ` + JSON.stringify(rest.tokens));
console.log(`[${PHASE}] rest send ` + JSON.stringify({ w: rest.dock.send && rest.dock.send.width, h: rest.dock.send && rest.dock.send.height, r: rest.dock.send && rest.dock.send.borderRadius, icon: rest.dock.send && rest.dock.send.iconW, pad: rest.dock.send && rest.dock.send.padding, box: rest.dock.send && rest.dock.send.box, hitBox: rest.dock.send && rest.dock.send.hitBox, inside: rest.dock.send && rest.dock.send.insideInput }));
console.log(`[${PHASE}] rest stop ` + JSON.stringify({ w: rest.dock.stop && rest.dock.stop.width, h: rest.dock.stop && rest.dock.stop.height, r: rest.dock.stop && rest.dock.stop.borderRadius, icon: rest.dock.stop && rest.dock.stop.iconW }));
console.log(`[${PHASE}] busy send ${JSON.stringify(busy.dock.send && busy.dock.send.box)} / stop ${JSON.stringify(busy.dock.stop && busy.dock.stop.box)}`);
console.log(`[${PHASE}] shots → ${OUT}`);

await ctx.close();
await browser.close();
server.close();
