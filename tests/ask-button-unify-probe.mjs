#!/usr/bin/env node
// ask-button-unify-probe.mjs — 主会话 send/stop 与 Canvas Ask send/stop 的逐值读数探针
// （纯测量，不做断言；读数落 JSON 供对照表与闸的期望值来源）。
// Run: node tests/ask-button-unify-probe.mjs [--out <dir>] [--mobile-only] [--desktop-only]
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SHOTS_WEB || resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const argv = process.argv.slice(2);
const argVal = (k) => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : null; };
const OUT = argVal('--out') || resolve(HERE, '..', 'evidence', 'ask-button-unify');
mkdirSync(OUT, { recursive: true });
const WANT_MOBILE = !argv.includes('--desktop-only');
const WANT_DESKTOP = !argv.includes('--mobile-only');

const PORT = Number(process.env.PORT || 8391);
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
  { role: 'user', text: '第 1 问：检索窗口为什么要按龄衰减？' },
  { role: 'ai', text: '第 1 答：按龄衰减让近期证据权重更高，同时保留长尾。' },
];

const READ_FN = ({ sel, label }) => {
  const el = document.querySelector(sel);
  if (!el) return { label, sel, missing: true };
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
  return {
    label, sel,
    rect: { w: +r.width.toFixed(2), h: +r.height.toFixed(2), left: +r.left.toFixed(2), top: +r.top.toFixed(2) },
    cls: String(el.className || ''),
    width: cs.width, height: cs.height,
    borderRadius: cs.borderTopLeftRadius,
    borderTop: `${cs.borderTopWidth} ${cs.borderTopStyle} ${cs.borderTopColor}`,
    padding: cs.padding,
    background: cs.backgroundColor,
    backgroundImage: cs.backgroundImage,
    color: cs.color,
    boxShadow: cs.boxShadow,
    display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
    flexShrink: cs.flexShrink,
    transition: cs.transition,
    svg: svg ? {
      w: ss.width, h: ss.height, strokeWidth: ss.strokeWidth,
      fill: ss.fill, stroke: ss.stroke, color: ss.color,
      viewBox: svg.getAttribute('viewBox'),
      strokeLinecap: svg.getAttribute('stroke-linecap'),
      shapes,
    } : null,
    hitInset: { top: af.top, right: af.right, bottom: af.bottom, left: af.left },
    hitBox: hit,
  };
};

async function boot(browserType, opts) {
  const { mobile, colorScheme = 'dark', deviceScaleFactor = 2 } = opts;
  const browser = await browserType.launch();
  const viewport = mobile ? { width: 375, height: 812 } : { width: 1600, height: 1000 };
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: !!mobile, deviceScaleFactor, locale: 'zh-CN' });
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
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message).slice(0, 200)));
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
  await page.goto(BASE + '/');
  await page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await sleep(700);
  return { browser, ctx, page, errors };
}

async function openAskDock(page, mobile) {
  if (mobile) {
    await page.evaluate(async () => { const m = await import('/js/mobile/navController.js'); m.navigateTo('canvas'); });
    await sleep(600);
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
  await sleep(400);
}

async function readScreen(page, label) {
  const r = await page.evaluate(({ label }) => {
    const fn = (sel, lb) => {
      const el = document.querySelector(sel);
      if (!el) return { label: lb, sel, missing: true };
      const cs = getComputedStyle(el);
      const svg = el.querySelector('svg');
      const ss = svg ? getComputedStyle(svg) : null;
      const af = getComputedStyle(el, '::after');
      const rect = el.getBoundingClientRect();
      const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : null; };
      const bw = num(cs.borderLeftWidth) || 0;
      const ins = [num(af.top), num(af.right), num(af.bottom), num(af.left)];
      const hit = ins.every((n) => n !== null)
        ? { w: +((rect.width - 2 * bw) - ins[3] - ins[1]).toFixed(2), h: +((rect.height - 2 * bw) - ins[0] - ins[2]).toFixed(2) }
        : null;
      const shapes = svg ? [...svg.querySelectorAll('path,rect,line,circle,polyline')].map((n) => {
        const a = {}; for (const at of n.attributes) a[at.name] = at.value;
        return { tag: n.tagName.toLowerCase(), attrs: a };
      }) : null;
      return {
        label: lb, sel,
        rect: { w: +rect.width.toFixed(2), h: +rect.height.toFixed(2), left: +rect.left.toFixed(2), top: +rect.top.toFixed(2) },
        cls: String(el.className || ''),
        width: cs.width, height: cs.height,
        borderRadius: cs.borderTopLeftRadius,
        borderTop: `${cs.borderTopWidth} ${cs.borderTopStyle} ${cs.borderTopColor}`,
        padding: cs.padding,
        background: cs.backgroundColor,
        backgroundImage: cs.backgroundImage,
        color: cs.color,
        boxShadow: cs.boxShadow,
        display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
        svg: svg ? {
          w: ss.width, h: ss.height, strokeWidth: ss.strokeWidth,
          fill: ss.fill, stroke: ss.stroke, color: ss.color,
          viewBox: svg.getAttribute('viewBox'),
          strokeLinecap: svg.getAttribute('stroke-linecap'),
          shapes,
        } : null,
        hitInset: { top: af.top, right: af.right, bottom: af.bottom, left: af.left },
        hitBox: hit,
      };
    };
    const bodyCs = getComputedStyle(document.body);
    const tok = (n) => bodyCs.getPropertyValue(n).trim();
    return {
      label,
      bodyMobile: document.body.classList.contains('mobile-layer'),
      buttons: {
        mainSend: fn('#input-bar > #send-btn', 'main.send'),
        mainStop: fn('#input-bar > #stop-btn', 'main.stop'),
        askSend: fn('.ask-dock .ask-send', 'ask.send'),
        askStop: fn('.ask-dock .ask-stop', 'ask.stop'),
      },
      alt: {
        // 非直接子选择器的兜底读数（结构若变，指纹可诊断）
        mainSendAny: fn('#send-btn', 'main.send#any'),
        mainStopAny: fn('#stop-btn', 'main.stop#any'),
      },
      tokens: {
        dsTurnBtnSize: tok('--ds-turn-btn-size'), dsTurnBtnIcon: tok('--ds-turn-btn-icon'),
        dsTurnBtnStroke: tok('--ds-turn-btn-stroke'), dsTurnHit: tok('--ds-turn-hit'),
        dsTurnSendBg: tok('--ds-turn-send-bg'), dsTurnSendInk: tok('--ds-turn-send-ink'),
        dsTurnStopBg: tok('--ds-turn-stop-bg'), dsTurnStopInk: tok('--ds-turn-stop-ink'),
        cvSizeCircle: tok('--cv-size-circle'), cvIcon: tok('--cv-icon'),
        mcvSizeCircle: tok('--mcv-size-circle'), mcvIcon: tok('--mcv-icon'),
        mtapLg: tok('--mtap-lg'), micon: tok('--micon'),
        bottomBandD: tok('--bottom-band-d'), bottomBandIcon: tok('--bottom-band-icon'),
      },
    };
  }, { label });
  return r;
}

const results = { web: WEB, generatedAt: new Date().toISOString(), screens: {} };

if (WANT_DESKTOP) {
  const { browser, ctx, page, errors } = await boot(chromium, { mobile: false });
  await openAskDock(page, false);
  const r = await readScreen(page, 'desktop');
  r.pageErrors = errors;
  await page.screenshot({ path: join(OUT, 'probe-desktop-full.png') });
  results.screens.desktop = r;
  await ctx.close(); await browser.close();
}

if (WANT_MOBILE) {
  const { browser, ctx, page, errors } = await boot(chromium, { mobile: true });
  await openAskDock(page, true);
  const r = await readScreen(page, 'mobile');
  r.pageErrors = errors;
  await page.screenshot({ path: join(OUT, 'probe-mobile-canvas-full.png') });
  // 主会话页（chat）也拍一张，用于按钮特写（手机层主输入条）
  results.screens.mobile = r;
  await ctx.close(); await browser.close();
}

writeFileSync(join(OUT, 'probe-readings.json'), JSON.stringify(results, null, 2) + '\n');
console.log(JSON.stringify(results, null, 2));
server.close();
