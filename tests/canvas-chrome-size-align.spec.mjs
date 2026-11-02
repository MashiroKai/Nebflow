// canvas-chrome-size-align.spec.mjs — canvas 悬浮控件尺寸对齐批（2026-11-02 作者追加令
// 09:41「包括 canvas 的这些控件宽度也要保持一致」）验收钉。
//
// 现场：作者实拍里同一条 canvas 底部带上，缩放条内的四颗钮是 26px、旁边的独立刷新
// 圆钮是 40px、文档工具簇圆钮也是 40px —— 三档尺寸并存即「大小不一」。
// 本批把 canvas 文档 chrome 全部收到**一个**尺寸真值 48（= --cv-size-circle-lg =
// 手机层 --mtap-lg = 手机层返回钮 = 面板吸附胶囊 --mcap-h），条高随之 36 → 58
// （48 + 2×4 条内距 + 2×1 描边），底锚 18 → 11（条内钮中线 = 圆钮行中线 40）。
//
// 断言（都是 boundingBox 实测，不 mock 布局）：
//   ① 条内每个 icon 钮宽 48±1（图片 pane 四钮含抓手 / HTML pane 三钮）
//   ② 独立圆钮（壳刷新 .canvas-refresh-toggle）+ 文档工具簇圆钮（源码/引用/查找）宽 48±1
//   ③ 每个钮高 = 宽（±1）—— 正方/正圆，无压扁
//   ④ 钮高 = 尺寸真值档（手机：实测吸附胶囊高 --mcap-h；桌面：--cv-size-circle-lg）|差| ≤2px
//   ⑤ 钮与钮的水平缝隙相对一致（条内相邻缝两两相等 ±1；簇槽距 = 钮宽 + 8 ±1）
//   ⑥ 渲染盒零相交：条/钮 vs 手机环带签（.mf-strip-chip）/ 小点（.mf-dots）/
//      左右两颗悬浮钮（.mf-cv-close / .mf-cv-stack）/ ask 悬浮钮；桌面 vs 文档
//      工具簇圆钮 / 壳刷新钮 / pane 边界
//   附带：读数（24% / 100%）仍是文本、竖直居中（与邻居中线 |差| ≤1）、字号 ≥13
//
// 桌面腿（1280×800 图片 pane + HTML pane，默认宽 + 320 窄面板回落第二行）
// 手机腿（375×812 HTML pane：条 → 第二行，簇第一行）
// 双主题（dark / light）几何逐值一致。
//
// 运行：node tests/canvas-chrome-size-align.spec.mjs
// 可选 env：PORT（默认 8244）、CHROME_SIZE_SHOTS_DIR（截图目录）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8244);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.CHROME_SIZE_SHOTS_DIR || resolve(HERE, '../evidence/20261102_title-capsule-height/canvas-chrome');
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 尺寸真值（断言口径单源）──
const SIZE = 48;         // = --cv-size-circle-lg = --mtap-lg = --mcap-h
const TOL = 1;           // 尺寸容差
const TIER_TOL = 2;      // ④「与档同高」容差
const GAP = 8;           // 钮间缝（槽距 = 48 + 8）

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.ico': 'image/x-icon',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  results.push(line);
  console.log(line);
  if (cond) pass++; else fail++;
}

const IMG_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="600"><rect width="960" height="600" fill="#5b8def"/><circle cx="300" cy="200" r="90" fill="#ffd66b"/></svg>';
const HTML_DOC = '<!doctype html><html><head><meta charset="utf-8"><style>body{font-family:system-ui;padding:32px;line-height:1.7}h1{font-size:28px}</style></head><body><h1>巡检报告</h1><p>' + '本页用于尺寸对齐验收，内容足够长以触发滚动。'.repeat(40) + '</p></body></html>';

async function boot(browser, { viewport, colorScheme, mobile = false }) {
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: mobile, locale: 'zh-CN' });
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
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 240)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text().slice(0, 200)); });
  await page.route('**/agents/**', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await page.route('**/api/**', (r) => {
    const u = decodeURIComponent(r.request().url());
    if (u.includes('/api/nf-file')) {
      if (u.includes('.svg')) return r.fulfill({ status: 200, contentType: 'image/svg+xml', body: IMG_SVG });
      return r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: HTML_DOC });
    }
    return r.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'size-root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'size-root' }));
    cfg(); ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto(BASE + '/index.html');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId && s.ws && s.ws.readyState === 1;
  }, null, { timeout: 20000 });
  if (mobile) {
    await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  } else {
    await page.waitForSelector('#activity-bar', { timeout: 15000 });
  }
  await sleep(600);
  return { ctx, page, errors };
}

async function openItems(page, items) {
  await page.evaluate((list) => {
    for (const it of list) {
      window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...it, pinned: true } }));
    }
  }, items);
  for (const it of items) {
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="${it.id}"]`, { timeout: 15000, state: 'attached' });
  }
  await sleep(1200);
}

async function activate(page, id) {
  const clicked = await page.evaluate((tid) => {
    const chip = [...document.querySelectorAll('.mf-strip-chip')].find((c) => c.dataset.tabId === tid);
    if (chip) { chip.click(); return true; }
    const tab = [...document.querySelectorAll('.canvas-tab')].find((t) => (t.dataset.tabId || '') === tid);
    if (tab) { tab.click(); return true; }
    return false;
  }, id);
  await sleep(900);
  return clicked;
}

// 一屏全量几何（所有判据同帧采样，避免跨帧错位）
const PROBE = `(() => {
  const round = (n) => Math.round(n * 100) / 100;
  const box = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    if (!r.width && !r.height) return null;
    return { x: round(r.x), y: round(r.y), w: round(r.width), h: round(r.height), right: round(r.right), bottom: round(r.bottom), cx: round(r.x + r.width / 2), cy: round(r.y + r.height / 2) };
  };
  const vis = (el) => el && el.offsetParent !== null && getComputedStyle(el).display !== 'none';
  const pane = document.querySelector('.canvas-tab-pane.active');
  const bar = pane ? pane.querySelector('.canvas-zoom-bar') : null;
  const pct = bar ? bar.querySelector('.canvas-zoom-pct') : null;
  const panel = document.getElementById('canvas-panel');
  const cs = getComputedStyle(document.body);
  const root = getComputedStyle(document.documentElement);
  return {
    mobile: document.body.classList.contains('mobile-layer'),
    bar: box(bar),
    barRadius: bar ? getComputedStyle(bar).borderRadius : null,
    barPad: bar ? getComputedStyle(bar).padding : null,
    btn: bar ? [...bar.querySelectorAll('.canvas-zoom-btn')].map((b) => ({ t: b.title || b.getAttribute('aria-label') || '', r: box(b), svg: box(b.querySelector('svg')) })).filter((b) => b.r) : [],
    pct: pct ? { text: pct.textContent.trim(), fontSize: getComputedStyle(pct).fontSize, r: box(pct) } : null,
    refresh: box(document.querySelector('#canvas-content > .canvas-refresh-toggle')),
    cluster: ['.canvas-source-toggle', '.canvas-ref-select', '.canvas-find-toggle']
      .map((s) => ({ s, r: box(pane ? pane.querySelector(s) : null) })).filter((c) => c.r),
    tier: {
      mcapH: cs.getPropertyValue('--mcap-h').trim(),
      cvCircleLg: root.getPropertyValue('--cv-size-circle-lg').trim(),
      cvSizeBtn: root.getPropertyValue('--cv-size-btn').trim(),
      mtapLg: cs.getPropertyValue('--mtap-lg').trim(),
    },
    stripChips: [...document.querySelectorAll('.mf-strip-chip')].map((c) => box(c)).filter(Boolean),
    dots: (() => { const d = document.querySelector('.mf-dots'); return d && !d.hidden ? box(d) : null; })(),
    closeBtn: box(document.querySelector('.mf-cv-close')),
    stackBtn: box(document.querySelector('.mf-cv-stack')),
    askFab: box(document.querySelector('.ask-fab')),
    capsule: box(document.querySelector('.mobile-page-title-mini')),
    pane: box(pane),
    panel: box(panel),
  };
})()`;

const inter = (a, b) => {
  if (!a || !b) return false;
  return !(a.right <= b.x + 0.5 || b.right <= a.x + 0.5 || a.bottom <= b.y + 0.5 || b.bottom <= a.y + 0.5);
};

/** 一条腿的全套 ①–⑥ 断言。
 *  @param {string} tag 断言前缀
 *  @param {'desktop'|'mobile'} leg */
function assertLeg(tag, m, leg) {
  const boxes = [...m.btn.map((b) => ({ name: `bar:${b.t || 'btn'}`, r: b.r })),
    ...(m.refresh ? [{ name: 'refresh', r: m.refresh }] : []),
    ...m.cluster.map((c) => ({ name: c.s, r: c.r }))];

  // ① 条内 icon 钮宽 48±1
  const barWs = m.btn.map((b) => b.r.w);
  ok(`${tag} ① 条内 icon 钮宽 48±1（实测 ${barWs.map((w) => w.toFixed(0)).join('/')}，${barWs.length} 钮）`,
    barWs.length >= 3 && barWs.every((w) => Math.abs(w - SIZE) <= TOL), JSON.stringify(barWs));

  // ② 独立圆钮 + 簇圆钮宽 48±1
  const circWs = [m.refresh?.w, ...m.cluster.map((c) => c.r.w)].filter((w) => w != null);
  ok(`${tag} ② 独立圆钮 + 工具簇圆钮宽 48±1（刷新 ${m.refresh ? m.refresh.w.toFixed(0) : '无'} · 簇 ${m.cluster.map((c) => c.r.w.toFixed(0)).join('/') || '无'}）`,
    circWs.length >= 1 && circWs.every((w) => Math.abs(w - SIZE) <= TOL), JSON.stringify(circWs));

  // ③ 高 = 宽（每个钮都不许被压扁）
  const square = boxes.filter((b) => Math.abs(b.r.h - b.r.w) > TOL).map((b) => `${b.name}=${b.r.w}×${b.r.h}`);
  ok(`${tag} ③ 钮高 = 钮宽 ±1（正方/正圆，${boxes.length} 件）`, square.length === 0, square.join(' ') || 'all square');

  // ④ 钮高 = 尺寸真值档（手机：吸附胶囊实测高；桌面：--cv-size-circle-lg）
  const tierVal = leg === 'mobile'
    ? (m.capsule ? m.capsule.h : parseFloat(m.tier.mcapH))
    : parseFloat(m.tier.cvCircleLg);
  const heights = boxes.map((b) => b.r.h);
  ok(`${tag} ④ 钮高 = 档高（${leg === 'mobile' ? '吸附胶囊 --mcap-h' : '--cv-size-circle-lg'} = ${tierVal}）|差| ≤2px`,
    Number.isFinite(tierVal) && Math.abs(tierVal - SIZE) <= TOL && heights.every((h) => Math.abs(h - tierVal) <= TIER_TOL),
    `tier=${tierVal} heights=${heights.map((h) => h.toFixed(0)).join('/')} tokens=${JSON.stringify(m.tier)}`);

  // ⑤ 缝相对一致：条内相邻缝两两相等 ±1，且 ≈8（原 4/26 ≈ 15.4% ⇒ 8/48 ≈ 16.7%）
  const sorted = [...m.btn].sort((a, b) => a.r.x - b.r.x);
  const barGaps = [];
  for (let i = 1; i < sorted.length; i++) {
    const prev = sorted[i - 1].r, cur = sorted[i].r;
    // 读数（pct）整块夹在两钮之间时跳过该对（那一段是「钮—读数—钮」，不是纯钮缝）
    const between = m.pct && m.pct.r && m.pct.r.x >= prev.right - 0.5 && m.pct.r.right <= cur.x + 0.5;
    if (between) continue;
    barGaps.push(round1(cur.x - prev.right));
  }
  const gapSpread = barGaps.length > 1 ? Math.max(...barGaps) - Math.min(...barGaps) : 0;
  const pitch = m.cluster.length >= 2
    ? m.cluster.map((c) => c.r.x).sort((a, b) => a - b).map((x, i, arr) => (i ? round1(x - arr[i - 1] - SIZE) : null)).filter((v) => v != null)
    : [];
  ok(`${tag} ⑤ 钮间缝相对一致（条内缝 ${barGaps.join('/') || 'n/a'} 两两差 ≤1；簇槽距缝 ${pitch.join('/') || 'n/a'} ≈8±1）`,
    (barGaps.length === 0 || gapSpread <= 1) && (barGaps.every((g) => g >= GAP - 1) || barGaps.length === 0) && pitch.every((g) => Math.abs(g - GAP) <= TOL),
    `spread=${gapSpread} barGaps=${JSON.stringify(barGaps)} clusterGaps=${JSON.stringify(pitch)}`);

  // ⑤b 读数（文本件）两侧净空 ≥8（作者令「paddings 不要挤」）——读数块与左右钮的缝
  const pctClear = (() => {
    if (!m.pct || !m.pct.r) return null;
    const lefts = m.btn.map((b) => b.r).filter((r) => r.right <= m.pct.r.x + 0.5);
    const rights = m.btn.map((b) => b.r).filter((r) => r.x >= m.pct.r.right - 0.5);
    const l = lefts.length ? m.pct.r.x - Math.max(...lefts.map((r) => r.right)) : null;
    const rr = rights.length ? Math.min(...rights.map((r) => r.x)) - m.pct.r.right : null;
    return { l: l == null ? null : round1(l), r: rr == null ? null : round1(rr) };
  })();
  ok(`${tag} ⑤b 读数两侧净空 ≥8px（不吃挤；实测 左 ${pctClear ? pctClear.l : 'n/a'} / 右 ${pctClear ? pctClear.r : 'n/a'}）`,
    !!pctClear && (pctClear.l == null || pctClear.l >= GAP - 1) && (pctClear.r == null || pctClear.r >= GAP - 1),
    JSON.stringify(pctClear));

  // ⑥ 渲染盒零相交
  const anti = [];
  if (leg === 'mobile') {
    const hostile = [...m.stripChips.map((c, i) => ({ name: `chip${i}`, r: c })), ...(m.dots ? [{ name: 'dots', r: m.dots }] : []),
      ...(m.closeBtn ? [{ name: 'closeBtn', r: m.closeBtn }] : []), ...(m.stackBtn ? [{ name: 'stackBtn', r: m.stackBtn }] : []),
      ...(m.askFab ? [{ name: 'askFab', r: m.askFab }] : [])];
    for (const b of boxes) for (const h of hostile) if (inter(b.r, h.r)) anti.push(`${b.name}∩${h.name}`);
    // bar 本体（含内距外框）同样不许压环带/小点/浮钮
    if (m.bar) for (const h of hostile) if (inter(m.bar, h.r)) anti.push(`bar∩${h.name}`);
  } else {
    // 桌面：条 vs 簇圆钮 vs 刷新钮 两两零交叠（同排水平缝 ≥8 由 ⑤ 覆盖）
    const desk = [{ name: 'bar', r: m.bar }, ...(m.refresh ? [{ name: 'refresh', r: m.refresh }] : []), ...m.cluster.map((c) => ({ name: c.s, r: c.r }))];
    for (let i = 0; i < desk.length; i++) for (let j = i + 1; j < desk.length; j++) {
      if (desk[i].r && desk[j].r && inter(desk[i].r, desk[j].r)) anti.push(`${desk[i].name}∩${desk[j].name}`);
    }
    // 全套 chrome 完整落在 pane 内（不许越界出可视区）
    if (m.pane) {
      for (const d of desk) {
        if (!d.r) continue;
        if (d.r.x < m.pane.x - 0.5 || d.r.y < m.pane.y - 0.5 || d.r.right > m.pane.right + 0.5 || d.r.bottom > m.pane.bottom + 0.5) anti.push(`${d.name}出界`);
      }
    }
  }
  ok(`${tag} ⑥ 渲染盒零相交（${leg === 'mobile' ? '环带签/小点/左右浮钮/ask 钮' : '条/簇圆钮/刷新钮 + 全部落在 pane 内'}）`,
    anti.length === 0, anti.join(' ') || 'clean');

  // 附带：读数仍是文本、竖直居中、字号 ≥13、宽度随内容
  const pctD = m.pct && m.pct.r ? Math.abs(m.pct.r.cy - (m.bar ? m.bar.cy : 0)) : 999;
  ok(`${tag} 读数行竖直居中（|cy − 条中线| ≤1）且字号 ≥13（实测 ${m.pct ? m.pct.fontSize : 'n/a'} · ${m.pct ? m.pct.text : 'n/a'}）`,
    !!m.pct && pctD <= 1 && parseFloat(m.pct.fontSize) >= 13, `dy=${pctD.toFixed(1)}`);
  ok(`${tag} 条本体：圆角语义保留（${m.barRadius}）+ 条内距 4px（${m.barPad}）+ 条高 = 48 + 2×4 + 2×1 = 58`,
    !!m.bar && Math.abs(m.bar.h - 58) <= 2, m.bar ? `h=${m.bar.h}` : 'no bar');

  return { boxes, barGaps, pitch };
}

const round1 = (n) => Math.round(n * 10) / 10;

const browser = await chromium.launch();
try {
  /* ════════ 桌面腿 1：图片 pane（四钮含抓手）+ 壳刷新钮 ════════ */
  {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1280, height: 800 }, colorScheme: 'dark' });
    await openItems(page, [
      { id: 'file:/tmp/size/probe.svg', itemType: 'image', title: '巡检路线示意.svg', content: '', absPath: '/tmp/size/probe.svg' },
    ]);
    await activate(page, 'file:/tmp/size/probe.svg');
    const m = await page.evaluate(PROBE);
    assertLeg('桌面-图片', m, 'desktop');
    ok('桌面-图片 条内四钮齐（抓手/缩小/放大/适应视图）', m.btn.length === 4, m.btn.map((b) => b.t).join(' · '));
    ok('桌面-图片 壳刷新钮在场（独立圆钮 = 作者实拍「旁边那颗又大一号的」）', !!m.refresh, JSON.stringify(m.refresh));
    await page.screenshot({ path: join(SHOTS_DIR, 'desktop-image-dark.png'), clip: { x: Math.max(0, (m.panel ? m.panel.x : 700)), y: Math.max(0, (m.pane ? m.pane.bottom - 150 : 600)), width: 500, height: 150 } }).catch(() => {});
    ok('桌面-图片 零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
    await ctx.close();
  }

  /* ════════ 桌面腿 2：HTML pane（三钮 + 三圆钮簇 + 壳刷新钮，同排一行）════════ */
  {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1280, height: 800 }, colorScheme: 'dark' });
    await openItems(page, [
      { id: 'file:/tmp/size/report.html', itemType: 'html', title: '巡检报告.html', content: HTML_DOC, absPath: '/tmp/size/report.html' },
    ]);
    await activate(page, 'file:/tmp/size/report.html');
    await sleep(800);
    const m = await page.evaluate(PROBE);
    assertLeg('桌面-HTML', m, 'desktop');
    ok('桌面-HTML 三圆钮簇齐（源码/引用/查找）', m.cluster.length === 3, m.cluster.map((c) => c.s).join(' '));
    ok('桌面-HTML 读数 = 100%（既有缩放读数语义零改）', !!m.pct && m.pct.text === '100%', m.pct && m.pct.text);
    ok('桌面-HTML 零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
    await page.screenshot({ path: join(SHOTS_DIR, 'desktop-html-dark.png'), clip: { x: Math.max(0, (m.panel ? m.panel.x : 700)), y: Math.max(0, (m.pane ? m.pane.bottom - 170 : 600)), width: 500, height: 170 } }).catch(() => {});
    await ctx.close();
  }

  /* ════════ 桌面腿 3：窄面板 320（容器查询回落第二行：条升到圆钮行上方）════════ */
  {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
    await page.evaluate(() => document.documentElement.style.setProperty('--canvas-width', '320px'));
    await sleep(700);
    await openItems(page, [
      { id: 'file:/tmp/size/narrow.html', itemType: 'html', title: '巡检报告.html', content: HTML_DOC, absPath: '/tmp/size/narrow.html' },
    ]);
    await activate(page, 'file:/tmp/size/narrow.html');
    await sleep(800);
    const m = await page.evaluate(PROBE);
    assertLeg('桌面-窄320', m, 'desktop');
    // 第二行判定：条底锚 72 ⇒ 条底 + 8 = 圆钮行顶（16 + 48）
    const stacked = !!m.bar && m.cluster.length > 0 && m.bar.bottom <= m.cluster[0].r.y + 0.5;
    ok('桌面-窄320 条升到圆钮行上方第二行（容器查询 ≤414px 命中，条底 ≤ 簇顶）', stacked,
      m.bar && m.cluster.length ? `barBottom=${m.bar.bottom} clusterTop=${m.cluster[0].r.y}` : 'missing');
    ok('桌面-窄320 零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
    await ctx.close();
  }

  /* ════════ 手机腿：375×812 HTML pane（条第二行 / 簇第一行 / 环带 / 小点 / 浮钮）════════ */
  for (const scheme of ['dark', 'light']) {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 375, height: 812 }, colorScheme: scheme, mobile: true });
    await openItems(page, [
      { id: 'file:/tmp/size/m.html', itemType: 'html', title: '巡检报告.html', content: HTML_DOC, absPath: '/tmp/size/m.html' },
      { id: 'file:/tmp/size/m2.md', itemType: 'markdown', title: '说明.md', content: '# 探针\n\n正文。', absPath: '/tmp/size/m2.md' },
    ]);
    await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
    await sleep(1400);
    await activate(page, 'file:/tmp/size/m.html');
    const m = await page.evaluate(PROBE);
    const tag = `手机-${scheme}`;
    assertLeg(tag, m, 'mobile');
    ok(`${tag} 条内三钮齐（缩小/放大/适应视图）`, m.btn.length === 3, m.btn.map((b) => b.t).join(' · '));
    ok(`${tag} 三圆钮簇齐（源码/引用/查找）`, m.cluster.length === 3, m.cluster.map((c) => c.s).join(' '));
    ok(`${tag} 环带 / 小点 / 两悬浮钮 都在场（⑥ 有对家才判得出）`,
      m.stripChips.length >= 2 && !!m.dots && !!m.closeBtn && !!m.stackBtn,
      `chips=${m.stripChips.length} dots=${!!m.dots} close=${!!m.closeBtn} stack=${!!m.stackBtn}`);
    ok(`${tag} 读数 = 100%（文本未被改写成按钮）`, !!m.pct && m.pct.text === '100%', m.pct && m.pct.text);
    ok(`${tag} 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await page.screenshot({ path: join(SHOTS_DIR, `mobile-${scheme}.png`) });
    await ctx.close();
  }
} finally {
  await browser.close();
  server.close();
}

console.log(results.join('\n'));
console.log(`\n═══ canvas-chrome-size-align: ${pass} PASS / ${fail} FAIL ═══`);
process.exit(fail ? 1 : 0);
