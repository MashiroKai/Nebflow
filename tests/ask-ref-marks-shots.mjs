#!/usr/bin/env node
// ask-ref-marks-shots.mjs — canvas 文档面「可点击下划线标记」配色批（作者更正令
// 2026-11-22 11:52「ask引用做错了 需要回退 我说的是这种 可点击的下划线标记 现在是紫色
// 在暗色模式下可见性很差」）实录收档。
//
// 口径：真页面（本仓 dev web/ 源树，`SHOTS_WEB` 可指到任何 checkout）+ 真文档渲染腿
// （viewers/markdown.js 的 wikilink / 词条 / 裸词三条 pass）+ 真浏览器；只有 REST/WS
// 传输腿打桩（图谱载荷确定化）。真机全壳：手机层 + canvas 文档页 + ask 面板在场。
//
// 产出（落 OUT）：
//   · <label>-<scheme>.png —— 390×844 主视口（dsf 2 ⇒ 780×1688 px）真机全壳帧
//   · readings-<label>.json —— 每类标记的实测：字色 / 底纹色 / 区域众数底色 / 对比度
//
// Run:
//   SHOTS_WEB=<web 根> OUT=<目录> LABEL=before|after node tests/ask-ref-marks-shots.mjs
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, normalize, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SHOTS_WEB || resolve(HERE, '../src/main/resources/web');
const OUT = process.env.OUT || resolve(HERE, '../.stand/tmp/ask-ref-marks-green/evidence');
const LABEL = process.env.LABEL || 'after';
const PORT = Number(process.env.PORT || 8323);
const BASE = `http://127.0.0.1:${PORT}`;
const W = 390;
const H = 844;
const VAULT = '/tmp/ask-ref-marks-fixture/vault';
const DOC = '联网工具能力评测样本.md';
const ABS = `${VAULT}/${DOC}`;
const SID = 'ask-' + createHash('sha256').update(ABS, 'utf8').digest('hex').slice(0, 8);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

// 样本正文：四类标记各一枚（文档链接 / 手写词条引用 / 裸词自动识别 / 未解析）。
const DOC_BODY = [
  '# 联网工具能力端到端评测样本',
  '',
  '关联：[[规范文档]]',
  '',
  'DeepSeek summary 实测 [[1000–2300]] 字符，超过上限的裸词 1000–2300 也会自动识别；',
  '未解析的示例 [[不存在的文档]] 保持灰。',
  '',
].join('\n');

// 图谱载荷：本 vault + 一个可解析文档节点 + 一个词条声明（1000–2300）。
const GRAPH = {
  projects: [{ name: 'fixture', vault: VAULT }],
  nodes: [
    {
      project: 'fixture', rel: DOC, device: 'self', stub: false,
      terms: [], links: [],
    },
    {
      project: 'fixture', rel: '规范文档.md', device: 'self', stub: false,
      terms: [{ name: '1000–2300', rel: '规范文档.md', source: 'glossary', anchor: '^t1' }], links: [],
    },
  ],
};

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

/** 区域众数底色 + 对比度（真截图像素，不估算）。 */
async function measure(page, clip, colorCss) {
  const buf = await page.screenshot({ clip, type: 'png' });
  const dataUrl = 'data:image/png;base64,' + buf.toString('base64');
  return page.evaluate(async ({ url, colorCss: c }) => {
    const img = await createImageBitmap(await (await fetch(url)).blob());
    const cv = document.createElement('canvas');
    cv.width = img.width; cv.height = img.height;
    const g = cv.getContext('2d');
    g.drawImage(img, 0, 0);
    const d = g.getImageData(0, 0, img.width, img.height).data;
    const tally = new Map();
    for (let i = 0; i < d.length; i += 4) {
      const k = `${d[i]},${d[i + 1]},${d[i + 2]}`;
      tally.set(k, (tally.get(k) || 0) + 1);
    }
    let bg = '0,0,0', best = -1;
    for (const [k, n] of tally) if (n > best) { best = n; bg = k; }
    const lin = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    const lum = ([r, gg, b]) => 0.2126 * lin(r) + 0.7152 * lin(gg) + 0.0722 * lin(b);
    const parse = (s) => (String(s).match(/rgba?\(([^)]+)\)/) || [, ''])[1].split(',').slice(0, 3).map(Number);
    const bgRgb = bg.split(',').map(Number);
    const fgRgb = parse(c);
    const L1 = lum(fgRgb), L2 = lum(bgRgb);
    const ratio = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
    return { fg: fgRgb.join(','), bg: bgRgb.join(','), bgShare: +(best / (d.length / 4)).toFixed(3), ratio: +ratio.toFixed(2) };
  }, { url: dataUrl, colorCss });
}

async function shot(browser, scheme, readings) {
  const ctx = await browser.newContext({
    viewport: { width: W, height: H }, colorScheme: scheme, hasTouch: true,
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
    const req = route.request();
    const p = decodeURIComponent(new URL(req.url()).pathname);
    const json = (body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    if (p === '/api/sessions') return json({ sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], activeId: 'sess-1' });
    if (p === '/api/wiki/graph') return json(GRAPH);
    if (p === '/api/nf-ticket') {
      let paths = [];
      try { paths = JSON.parse(req.postData() || '{}').paths || []; } catch { paths = []; }
      const tickets = {};
      for (const q of (Array.isArray(paths) ? paths : [])) tickets[q] = { t: 'tk', exp: Date.now() + 600000, uses: -1 };
      return json({ tickets, rejected: [] });
    }
    if (p === '/api/nf-file') return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: Buffer.from(DOC_BODY) });
    if (p === '/api/canvas-writer/claim') return json({ epoch: 1, owner: 'shots' });
    if (p === '/api/canvas-writer') return json({ epoch: 1, owner: 'shots' });
    if (p === '/api/canvas-writer/release') return json({ ok: true });
    if (p.startsWith('/api/canvas-tabs')) return json({ ok: true, epoch: 1 });
    return json({});
  });
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'sess-1' }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      else if (m.type === 'askOpen') {
        ws.send(JSON.stringify({ type: 'askState', sessionId: SID, sessionKey: 'ask:marks', file: ABS, fileName: DOC, messages: [] }));
      }
    });
  });

  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.evaluate(async () => { const m = await import('/js/mobile/navController.js'); m.navigateTo('canvas'); });
  await sleep(600);
  await page.evaluate(({ name, absPath, body }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: body, absPath, pinned: true },
    }));
  }, { name: DOC, absPath: ABS, body: DOC_BODY });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"]`, { timeout: 15000 });
  await page.waitForFunction(() => { const f = document.querySelector('.ask-fab'); return f && !f.classList.contains('gate-off'); }, null, { timeout: 15000 });
  await page.click('.ask-fab');
  await page.waitForFunction(() => { const d = document.querySelector('.ask-dock'); return d && !d.classList.contains('closed'); }, null, { timeout: 15000 });
  await sleep(900);

  // 标记落位（真渲染产物：a.nf-wikilink / nf-wikilink-term / nf-term / unresolved）
  const marks = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('.canvas-md-viewer a').forEach((a) => {
      const cs = getComputedStyle(a);
      const r = a.getBoundingClientRect();
      let kind = 'wikilink';
      if (a.classList.contains('nf-wikilink-term')) kind = 'term-written';
      else if (a.classList.contains('nf-term')) kind = 'term-bare';
      else if (a.classList.contains('nf-wikilink-unresolved')) kind = 'unresolved';
      out.push({
        kind, cls: a.className, text: (a.textContent || '').trim(),
        color: cs.color, underline: cs.borderBottomColor, underlineStyle: cs.borderBottomStyle,
        rect: { x: r.x, y: r.y, w: r.width, h: r.height },
      });
    });
    return out;
  });
  if (!marks.length) throw new Error('文档面没有渲染出任何可点击标记（图谱载荷未命中？）');
  // 把标记滚到可见区上部（ask 面板仍在下半屏在场）
  await page.evaluate(() => {
    const first = document.querySelector('.canvas-md-viewer a');
    if (first) first.scrollIntoView({ block: 'center' });
  });
  await sleep(400);

  await page.screenshot({ path: join(OUT, `${LABEL}-${scheme}.png`) });
  const rows = [];
  for (const m of marks) {
    const live = await page.evaluate((t) => {
      const a = Array.from(document.querySelectorAll('.canvas-md-viewer a')).find((x) => (x.textContent || '').trim() === t);
      if (!a) return null;
      const r = a.getBoundingClientRect();
      return { x: r.x, y: r.y, w: r.width, h: r.height, color: getComputedStyle(a).color, underline: getComputedStyle(a).borderBottomColor };
    }, m.text);
    if (!live || live.w < 4 || live.h < 4) { rows.push({ ...m, skipped: '零尺寸/未上屏' }); continue; }
    const clip = { x: Math.round(live.x) + 1, y: Math.round(live.y) + 1, width: Math.max(4, Math.round(live.w) - 2), height: Math.max(4, Math.round(live.h) - 4) };
    const meas = await measure(page, clip, live.color);
    rows.push({ kind: m.kind, cls: m.cls, text: m.text.slice(0, 24), color: live.color, underline: live.underline, underlineStyle: m.underlineStyle, measured: meas });
    console.log(`  ${LABEL}/${scheme} ${m.kind.padEnd(12)} "${m.text.slice(0, 18)}" 字色 ${live.color} 底纹 ${live.underline} ⇒ 对比度 ${meas.ratio}:1（底 rgb(${meas.bg}) 占比 ${meas.bgShare}）`);
  }
  readings.push({ label: LABEL, scheme, viewport: `${W}x${H}@2x`, marks: rows, shot: join(OUT, `${LABEL}-${scheme}.png`), pageErrors: errors });
  await ctx.close();
}

const browser = await chromium.launch();
const readings = [];
try {
  for (const scheme of ['light', 'dark']) await shot(browser, scheme, readings);
} finally {
  await browser.close();
  server.close();
}
writeFileSync(join(OUT, `readings-${LABEL}.json`), JSON.stringify({ web: WEB, label: LABEL, at: new Date().toISOString(), readings }, null, 2) + '\n');
console.log('readings →', join(OUT, `readings-${LABEL}.json`));
