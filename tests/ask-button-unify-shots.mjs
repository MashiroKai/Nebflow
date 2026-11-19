#!/usr/bin/env node
// ask-button-unify-shots.mjs — 主会话 vs Canvas Ask 转回合圆钮（send/stop）对照取证
// （ask-button-unify 2026-11-18；作者令逐字「Ask 的中止按钮中间的方框应该和主会话
//  的一致 发送按钮也和主会话一致吧」）。
//
// 同一支脚本、同一状态、逐腿跑：
//   PHASE=before → <OUT>/before/    PHASE=after → <OUT>/after/
// 每腿每屏（mobile 375×812 手机层全屏页 / desktop 1600×1000）产出：
//   · full-<面>.png          真实全屏页（手机 = 手机层页面，非弹窗/内嵌面板）
//   · <面>-rest.png          rest 态输入条特写（send 在位）
//   · <面>-busy.png          busy 态输入条特写（stop 上位）
//   · <面>.webm              同一批动作的实况录屏（playwright recordVideo）
//   · compare-rest.png / compare-busy.png   主会话 | Ask 并排特写（浏览器合成，带标）
//   · readings.json          两钮计算几何/颜色/字形/命中盒 + 命中测试（elementFromPoint）
//
// 状态由**真状态机**驱动，不是手改样式：
//   主会话  #input 填字 → #send-btn 点击 → js/chat.js setBusy → applyTurnChrome
//   Ask    .ask-dock textarea 填字 → Enter（共享键道）→ askDock submit → armTurn
//   点停    主会话 #stop-btn / Ask .ask-stop → requestTurnInterrupt（同一包单点）
//
// Run:
//   PHASE=before|after SHOTS_WEB=<web 根> OUT=<证据目录> node tests/ask-button-unify-shots.mjs
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SHOTS_WEB || resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PHASE === 'before' ? 'before' : 'after';
const OUT = resolve(process.env.OUT || join(HERE, '..', 'evidence', 'ask-button-unify', PHASE));
mkdirSync(OUT, { recursive: true });
const DSF = 2;
const WANT = (process.env.SCREENS || 'mobile,desktop').split(',');

const PORT = Number(process.env.PORT || 8393);
const BASE = `http://127.0.0.1:${PORT}`;
const FILE = '记忆检索调优笔记.md';
const ABS = '/tmp/ask-button-unify-fixture/' + FILE;
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

async function boot(browserType, { mobile, recordDir }) {
  const browser = await browserType.launch();
  const viewport = mobile ? { width: 375, height: 812 } : { width: 1600, height: 1000 };
  const opts = { viewport, colorScheme: 'dark', hasTouch: !!mobile, deviceScaleFactor: DSF, locale: 'zh-CN' };
  if (recordDir) opts.recordVideo = { dir: recordDir, size: { width: viewport.width * DSF, height: viewport.height * DSF } };
  const ctx = await browser.newContext(opts);
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
  const videoT0 = Date.now(); // recordVideo 的时间原点 ≈ 本页创建；动作点取相对时刻裁切
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
  return { browser, ctx, page, videoT0, rel: () => (Date.now() - videoT0) / 1000 };
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
  await page.waitForSelector('#input-bar #send-btn', { timeout: 15000 });
  await sleep(400);
}

/** 元素读数（几何/颜色/字形/命中盒 + elementFromPoint 命中测试）。 */
const READ = ({ sel }) => {
  const el = document.querySelector(sel);
  if (!el) return { sel, missing: true };
  const cs = getComputedStyle(el);
  const svg = el.querySelector('svg');
  const ss = svg ? getComputedStyle(svg) : null;
  const af = getComputedStyle(el, '::after');
  const r = el.getBoundingClientRect();
  const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : null; };
  const bw = num(cs.borderLeftWidth) || 0;
  const ins = [num(af.top), num(af.right), num(af.bottom), num(af.left)];
  const hit = ins.every((n) => n !== null)
    ? { w: +((r.width - 2 * bw) - ins[3] - ins[1]).toFixed(2), h: +((r.height - 2 * bw) - ins[0] - ins[2]).toFixed(2) }
    : null;
  const shapes = svg ? [...svg.querySelectorAll('path,rect,line,circle,polyline')].map((n) => {
    const a = {}; for (const at of n.attributes) a[at.name] = at.value;
    return { tag: n.tagName.toLowerCase(), attrs: a };
  }) : null;
  const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  const top = r.width > 0 ? document.elementFromPoint(cx, cy) : null;
  return {
    sel,
    cls: String(el.className || ''),
    svgCls: svg ? String(svg.getAttribute('class') || '') : null,
    rect: { w: +r.width.toFixed(2), h: +r.height.toFixed(2), left: +r.left.toFixed(2), top: +r.top.toFixed(2) },
    width: cs.width, height: cs.height, borderRadius: cs.borderTopLeftRadius, padding: cs.padding,
    border: `${cs.borderTopWidth} ${cs.borderTopStyle} ${cs.borderTopColor}`,
    background: cs.backgroundColor, color: cs.color, display: cs.display, opacity: cs.opacity,
    svg: svg ? { w: ss.width, h: ss.height, strokeWidth: ss.strokeWidth, viewBox: svg.getAttribute('viewBox'), shapes } : null,
    hitInset: { top: af.top, right: af.right, bottom: af.bottom, left: af.left },
    hitBox: hit,
    hitTestSelf: !!top && (top === el || el.contains(top)),
    hitTestTag: top ? `${top.tagName.toLowerCase()}.${String(top.className || '').split(' ')[0]}` : null,
  };
};

const RECT = ({ sel }) => {
  const el = document.querySelector(sel);
  const r = el.getBoundingClientRect();
  return { left: r.left + r.width / 2, top: r.top + r.height / 2, w: r.width, h: r.height };
};

const BOX = { w: 176, h: 84 };
function boxOf(center, vw, vh) {
  const x = Math.max(0, Math.min(vw - BOX.w, Math.round(center.left - BOX.w / 2)));
  const y = Math.max(0, Math.min(vh - BOX.h, Math.round(center.top - BOX.h / 2)));
  return { x, y, width: BOX.w, height: BOX.h };
}

async function captureCloseup(page, sel, path) {
  const c = await page.evaluate(RECT, { sel });
  const vp = page.viewportSize();
  const clip = boxOf(c, vp.width, vp.height);
  await page.screenshot({ path, clip });
  return clip;
}

async function compose(browser, rows, dir, out) {
  const html = `<!doctype html><meta charset="utf-8"><style>
  body{margin:0;background:#141618;color:#e8ebf0;font:600 13px -apple-system,'PingFang SC',sans-serif}
  table{border-collapse:collapse}
  td{padding:8px 10px;vertical-align:middle;text-align:center}
  th{padding:6px 10px;color:#9aa4b2;font:600 12px -apple-system,'PingFang SC',sans-serif}
  img{display:block;border:1px solid #2c3138;border-radius:8px;background:#0b0d0f}
  .rowlab{color:#9aa4b2;font:600 12px -apple-system,'PingFang SC',sans-serif;white-space:nowrap}
  h1{font:600 15px -apple-system,'PingFang SC',sans-serif;margin:0 0 10px}
  </style><h1>${rows.title}</h1><table>
  <tr><th></th>${rows.cols.map((c) => `<th>${c}</th>`).join('')}</tr>
  ${rows.rows.map((r) => `<tr><td class="rowlab">${r.label}</td>${r.cells.map((src) => `<td><img src="${src}" style="width:${BOX.w * 2}px"></td>`).join('')}</tr>`).join('')}
  </table>`;
  const f = join(dir, `_compose-${out}.html`);
  writeFileSync(f, html);
  const ctx = await browser.newContext({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  await p.goto('file://' + f);
  await p.waitForFunction(() => [...document.images].every((i) => i.complete), null, { timeout: 10000 });
  await (await p.$('table')).screenshot({ path: join(dir, out + '.png') });
  await ctx.close();
}

const results = { phase: PHASE, web: WEB, dsf: DSF, screens: {} };

async function runScreen(screen, browserType) {
  const mobile = screen === 'mobile';
  const dir = join(OUT, screen);
  mkdirSync(dir, { recursive: true });

  const A = await boot(browserType, { mobile });
  const { page: pa } = A;
  await openAskDock(pa, mobile);
  await pa.screenshot({ path: join(dir, 'full-ask.png') });
  const askRest = await pa.evaluate(READ, { sel: '.ask-dock .ask-send' });
  const askClip = await captureCloseup(pa, '.ask-dock .ask-send', join(dir, 'ask-rest.png'));

  await pa.fill('.ask-dock textarea', '请总结这份文档的结论');
  await pa.press('.ask-dock textarea', 'Enter');
  await sleep(600);
  const askBusy = await pa.evaluate(READ, { sel: '.ask-dock .ask-stop' });
  const askSendBusy = await pa.evaluate(READ, { sel: '.ask-dock .ask-send' });
  await pa.screenshot({ path: join(dir, 'ask-busy.png'), clip: askClip });
  await pa.click('.ask-dock .ask-stop');
  await sleep(500);
  const askStopAfter = await pa.evaluate(READ, { sel: '.ask-dock .ask-stop' });
  const askSendAfter = await pa.evaluate(READ, { sel: '.ask-dock .ask-send' });
  const askErrs = (pa.__errs || []).slice();
  await A.ctx.close(); await A.browser.close();

  const B = await boot(browserType, { mobile });
  const { page: pb } = B;
  await gotoMain(pb, mobile);
  await pb.screenshot({ path: join(dir, 'full-main.png') });
  const mainRest = await pb.evaluate(READ, { sel: '#input-bar #send-btn' });
  const mainClip = await captureCloseup(pb, '#input-bar #send-btn', join(dir, 'main-rest.png'));
  await pb.fill('#input-bar #input', '主会话收发对照');
  await pb.click('#input-bar #send-btn');
  await sleep(700);
  const mainBusy = await pb.evaluate(READ, { sel: '#input-bar #stop-btn' });
  await pb.screenshot({ path: join(dir, 'main-busy.png'), clip: mainClip });
  await pb.click('#input-bar #stop-btn');
  await sleep(500);
  const mainAfter = await pb.evaluate(READ, { sel: '#input-bar #stop-btn' });
  const mainErrs = (pb.__errs || []).slice();
  await B.ctx.close(); await B.browser.close();

  const cmp = await boot(browserType, { mobile: false });
  await compose(cmp.browser, {
    title: `[${PHASE}] ${screen} rest — 主会话 vs Canvas Ask`,
    cols: ['主会话 #send-btn', 'Canvas Ask .ask-send'],
    rows: [{ label: 'rest', cells: ['main-rest.png', 'ask-rest.png'] }],
  }, dir, 'compare-rest');
  await compose(cmp.browser, {
    title: `[${PHASE}] ${screen} busy — 主会话 vs Canvas Ask（stop 上位）`,
    cols: ['主会话 #stop-btn', 'Canvas Ask .ask-stop'],
    rows: [{ label: 'busy', cells: ['main-busy.png', 'ask-busy.png'] }],
  }, dir, 'compare-busy');
  await cmp.ctx.close(); await cmp.browser.close();

  results.screens[screen] = {
    viewport: mobile ? { w: 375, h: 812 } : { w: 1600, h: 1000 },
    askRest, askBusy, askSendBusy, askStopAfter, askSendAfter,
    mainRest, mainBusy, mainAfter,
    clips: { ask: askClip, main: mainClip },
    pageErrors: [...askErrs, ...mainErrs],
  };
}

async function recordScreen(screen, browserType) {
  const mobile = screen === 'mobile';
  const dir = join(OUT, screen);
  const vdir = join(dir, '_video');
  const clips = {};
  for (const surface of ['main', 'ask']) {
    const vd = join(vdir, surface);
    mkdirSync(vd, { recursive: true });
    const { browser, ctx, page, rel } = await boot(browserType, { mobile, recordDir: vd });
    let clip, t0, t1;
    if (surface === 'ask') {
      await openAskDock(page, mobile);
      const c = await page.evaluate(RECT, { sel: '.ask-dock .ask-send' });
      clip = boxOf(c, page.viewportSize().width, page.viewportSize().height);
      await sleep(1200);
      t0 = Math.max(0, rel() - 1.0); // 含 rest 基线约 1s
      await page.fill('.ask-dock textarea', '请总结这份文档的结论');
      await sleep(700);
      await page.press('.ask-dock textarea', 'Enter');
      await sleep(1500);
      await page.click('.ask-dock .ask-stop');
      await sleep(1200);
      t1 = rel();
    } else {
      await gotoMain(page, mobile);
      const c = await page.evaluate(RECT, { sel: '#input-bar #send-btn' });
      clip = boxOf(c, page.viewportSize().width, page.viewportSize().height);
      await sleep(1200);
      t0 = Math.max(0, rel() - 1.0);
      await page.fill('#input-bar #input', '主会话收发对照');
      await sleep(700);
      await page.click('#input-bar #send-btn');
      await sleep(1500);
      await page.click('#input-bar #stop-btn');
      await sleep(1200);
      t1 = rel();
    }
    const vpath = await page.video().path();
    await ctx.close(); await browser.close();
    writeFileSync(join(dir, `${surface}.webm`), readFileSync(vpath));
    clips[surface] = { clip, t0: +t0.toFixed(2), t1: +t1.toFixed(2) };
    console.log(`[${PHASE}] ${screen}/${surface} video → ${join(dir, surface + '.webm')} ` + JSON.stringify(clips[surface]));
  }
  results.screens[screen] = { ...(results.screens[screen] || {}), videoClips: clips };
}

try {
  for (const s of WANT) { await runScreen(s, chromium); await recordScreen(s, chromium); }
} finally {
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(results, null, 2) + '\n');
  server.close();
}
console.log(`[${PHASE}] shots → ${OUT}`);
for (const [s, r] of Object.entries(results.screens)) {
  const f = (x) => x && JSON.stringify({ w: x.width, h: x.height, icon: x.svg && x.svg.w, stroke: x.svg && x.svg.strokeWidth, hit: x.hitBox, hitTest: x.hitTestSelf });
  console.log(`[${PHASE}] ${s} main.rest ${f(r.mainRest)}`);
  console.log(`[${PHASE}] ${s} ask.rest  ${f(r.askRest)}`);
  console.log(`[${PHASE}] ${s} main.busy ${r.mainBusy && r.mainBusy.display} / ask.busy ${r.askBusy && r.askBusy.display}`);
}
