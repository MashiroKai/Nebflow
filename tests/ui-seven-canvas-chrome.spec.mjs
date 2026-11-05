// ui-seven-canvas-chrome.spec.mjs — 独立复核批 seat c1 验收 spec
//   件1（作者令 2026-11-05，逐字「我们的canvas优化之后，独立窗口打开的按钮现在没了。
//        只在手机端隐藏这个按钮。电脑端要放在胶囊的右侧。」）
//     ① 桌面 1440：`.canvas-header .canvas-pop-btn` 在挂 + 落在胶囊右侧座位（内容盒右缘
//        贴齐 ±1 + 竖直中心 = 胶囊中心 ±1）+ DOM 序 = `.canvas-title` 的同级后继
//     ② 文件卡页签：点击 → window.open 恰 1 次（选「stub 计数」路径；stub 返回 null ⇒
//        独立窗态不翻，只验入口接通）
//     ③ 非内容物页签：disabled + title/aria-label = canvas.cardwinContentOnly 提示 +
//        点击后 window.open 计数 = 0（内容物收口批判据不推翻）
//     ④ 375 body.mobile-layer：该钮 display:none / 零盒，且胶囊盒仍 = 改前（零位移）
//     ⑤ 环开态：`--cr-popw` = 32px 且中心覆层文字盒宽 = 常态 `.t-name` 盒宽 ±1（#926）
//     ⑥ 长标题（≥40 字符）：`.t-name` 单行 + ellipsis 生效 + 胶囊宽 ≤ max-width
//   件3（同一作者令，逐字「然后电脑端的canvas的底部的按钮的大小，应该和canvas顶部的
//        按钮的大小样式都统一。」）
//     ⑦ 每一对「底部钮 ↔ 顶部钮」：width/height/border-radius/padding 逐项相等（≤1），
//        内部 svg 宽高像素相等；1440 暗/亮双主题各一遍
//     ⑧ 每颗 canvas chrome 钮的计算尺寸 = 已声明 token 值（token→实测对照表）+ 源码里
//        这些钮零第二份字面量尺寸（机械扫规则块自证）
//     ⑨ 375 手机档：改前/改后逐颗读数相等（基线常量在本文件 BEFORE 表；除同一 token
//        覆盖者外）
//     ⑩ 正方/正圆、条内相邻缝一致、渲染盒零相交三条既有关系在新尺寸下仍成立
//
// 裁定（件3 两条作者令打架的显式裁定 + 留证）：
//   11-02 批「底部 chrome = 48（对齐手机 --mtap-lg）」vs 本令「底部 = 顶部」。**新令优先**
//   （本仓既定：同域作者令按时间后者覆盖前者），故 桌面档收敛到顶部圆钮档
//   （--cv-size-circle = 40 / 圆 50% / 内距 0 / 图标 --cv-icon = 18）；手机 375 档
//   按「手机档未获新令」保留 11-02 批的 48 档（--mtap-lg），两腿分档显式写死。
//   读数实证见本文件 §BEFORE（改前表）与运行时输出的 token 对照表。
//
// 运行：node tests/ui-seven-canvas-chrome.spec.mjs
// 可选 env：PORT（默认 8251）、UI7_SHOTS_DIR（截图目录）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const CSS_DIR = join(WEB_DIR, 'css');
const PORT = Number(process.env.PORT || 8251);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.UI7_SHOTS_DIR
  || '/Users/kaiyu/.stand/evidence/20261105_ui-seven/c1';
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ════════ §BEFORE：改前实测基线（本批改前树实测，落
   evidence/20261105_ui-seven/c1/measure.json）════════
   1440 桌面（html 文件卡，暗/亮同值）：
     顶部 ✕ #canvas-head-close 40×40 r=50% pad 0 svg 18 · 堆叠 #deck-fab 40×40 同款
     底部 source/ref/find/refresh 48×48 r=50% pad 0 svg 18 · zoom-btn 48×48 r=7px svg 20
     条 .canvas-zoom-bar 222×58 pad 4 gap 8 · 槽位 x=16/72/128/184 pitch 56
     .canvas-pop-btn 不存在（批⑤退役）
   375 手机（html 文件卡）：source/ref/find 48×48 @x=16/72/128（cy=710）·
     条 222×58 @x=16（cy=649，第二行）· zoom-btn 48×48 svg 20 r=7px @x=21/129/185 ·
     refresh 0×0（隐藏）· #canvas-panel .canvas-header 0×0（手机层整族不可见） */
const BEFORE = {
  mobile: {
    source: { w: 48, h: 48, x: 16 },
    ref: { w: 48, h: 48, x: 72 },
    find: { w: 48, h: 48, x: 128 },
    refresh: { w: 0, h: 0 },
    bar: { w: 222, h: 58, x: 16 },
    zoomBtn: { w: 48, h: 48, svg: 20, radius: '7px' },
    header: { w: 0, h: 0 },
  },
};

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
  } catch { res.writeHead(404); res.end('not found'); }
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
const round1 = (n) => Math.round(n * 10) / 10;

const HTML_DOC = '<!doctype html><html><head><meta charset="utf-8"><style>body{font-family:system-ui;padding:32px;line-height:1.7}h1{font-size:28px}</style></head><body><h1>巡检报告</h1><p>' + '本页用于 canvas chrome 验收，内容足够长以触发滚动。'.repeat(40) + '</p></body></html>';
// 件1⑥ 长标题（≥40 字符）
const LONG_TITLE = '巡检报告 - 这是一条用来触发省略号截断的非常非常长的标题至少要四十个字符.html';

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
    if (u.includes('/api/nf-file')) return r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: HTML_DOC });
    return r.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'ui7-root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'ui7-root' }));
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
  if (mobile) await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  else await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

async function openItems(page, items) {
  await page.evaluate((list) => {
    for (const it of list) window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...it, pinned: true } }));
  }, items);
  for (const it of items) await page.waitForSelector(`.canvas-tab-pane[data-tab-id="${it.id}"]`, { timeout: 15000, state: 'attached' });
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

/* ── 一屏几何采样（所有判据同帧读数，不 mock 布局）────────────────────── */
const SEL = {
  top: ['#canvas-head-close', '#deck-fab'],
  bottom: ['.canvas-source-toggle', '.canvas-ref-select', '.canvas-find-toggle', '.canvas-refresh-toggle'],
  zoom: '.canvas-zoom-btn',
};

const geom = (page) => page.evaluate((SEL) => {
  const round = (n) => Math.round(n * 100) / 100;
  const pane = document.querySelector('.canvas-tab-pane.active');
  const q = (s) => (pane && pane.querySelector(s)) || document.querySelector(s);
  const info = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    const svg = el.querySelector('svg');
    const sr = svg ? svg.getBoundingClientRect() : null;
    return {
      sel: typeof el.className === 'string' && el.className ? el.className : el.id,
      x: round(r.x), y: round(r.y), w: round(r.width), h: round(r.height),
      right: round(r.right), bottom: round(r.bottom),
      cx: round(r.x + r.width / 2), cy: round(r.y + r.height / 2),
      radius: cs.borderTopLeftRadius, padding: cs.padding, display: cs.display,
      textOverflow: cs.textOverflow, whiteSpace: cs.whiteSpace,
      lineHeight: cs.lineHeight, fontSize: cs.fontSize,
      disabled: !!el.disabled, title: el.getAttribute('title') || '', ariaLabel: el.getAttribute('aria-label') || '',
      svg: sr ? { w: round(sr.width), h: round(sr.height) } : null,
    };
  };
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  const hs = hdr ? getComputedStyle(hdr) : null;
  const tname = document.getElementById('canvas-title-name');
  const bar = q('.canvas-zoom-bar');
  const root = getComputedStyle(document.documentElement);
  const bcs = document.body && getComputedStyle(document.body);
  const pick = {};
  for (const s of SEL.top) pick[s] = info(document.querySelector(s));
  for (const s of SEL.bottom) pick[s] = info(q(s));
  return {
    mobile: document.body.classList.contains('mobile-layer'),
    pick,
    zoomBtns: bar ? [...bar.querySelectorAll('.canvas-zoom-btn')].map((b) => {
      const g = info(b);
      g.adjNext = !!(b.nextElementSibling && b.nextElementSibling.classList.contains('canvas-zoom-btn'));
      return g;
    }) : [],
    bar: info(bar),
    barGapCss: bar ? getComputedStyle(bar).gap : null,
    hdr: info(hdr),
    hdrPad: hs ? hs.padding : null,
    hdrMaxW: hs ? hs.maxWidth : null,
    hdrBorder: hs ? { l: parseFloat(hs.borderLeftWidth), r: parseFloat(hs.borderRightWidth), t: parseFloat(hs.borderTopWidth), b: parseFloat(hs.borderBottomWidth) } : null,
    hdrPadPx: hs ? { l: parseFloat(hs.paddingLeft), r: parseFloat(hs.paddingRight) } : null,
    pop: info(hdr ? hdr.querySelector('.canvas-pop-btn') : null),
    popIsTitleNext: (() => {
      const t = hdr && hdr.querySelector('.canvas-title');
      return !!(t && t.nextElementSibling && t.nextElementSibling.classList.contains('canvas-pop-btn'));
    })(),
    tname: info(tname),
    tnameTrunc: tname ? { client: tname.clientWidth, scroll: tname.scrollWidth } : null,
    titleName: tname ? tname.textContent : '',
    tokens: {
      circle: root.getPropertyValue('--cv-size-circle').trim(),
      circleLg: root.getPropertyValue('--cv-size-circle-lg').trim(),
      icon: root.getPropertyValue('--cv-icon').trim(),
      btn: root.getPropertyValue('--cv-size-btn').trim(),
      mtapLg: bcs ? bcs.getPropertyValue('--mtap-lg').trim() : '',
    },
    pane: (() => { if (!pane) return null; const r = pane.getBoundingClientRect(); return { x: round(r.x), y: round(r.y), w: round(r.width), h: round(r.height), right: round(r.right), bottom: round(r.bottom) }; })(),
    mchrome: {
      chips: [...document.querySelectorAll('.mf-strip-chip')].map(info),
      dots: (() => { const d = document.querySelector('.mf-dots'); return d && !d.hidden ? info(d) : null; })(),
      closeBtn: info(document.querySelector('.mf-cv-close')),
      stackBtn: info(document.querySelector('.mf-cv-stack')),
      askFab: info(document.querySelector('.ask-fab')),
    },
  };
}, SEL);

const inter = (a, b) => {
  if (!a || !b) return false;
  return !(a.right <= b.x + 0.5 || b.right <= a.x + 0.5 || a.bottom <= b.y + 0.5 || b.bottom <= a.y + 0.5);
};
/** border-radius 归一（50% → px），便于逐项数值比较。 */
const radiusPx = (v, w, h) => {
  if (typeof v !== 'string') return NaN;
  if (v.endsWith('%')) return (parseFloat(v) / 100) * Math.min(w, h);
  return parseFloat(v);
};
const padPx = (v) => {
  if (typeof v !== 'string') return NaN;
  const p = v.split(/\s+/).map((x) => parseFloat(x) || 0);
  return p[0];   // 四边同值（本族钮面）
};

/* ── ⑦/⑧ 共用：底部钮 ↔ 顶部对应钮 逐项相等 ───────────────────────────── */
function assertPairs(tag, m) {
  const tops = SEL.top.map((s) => ({ s, r: m.pick[s] })).filter((t) => t.r);
  const bottoms = [
    ...SEL.bottom.map((s) => ({ s, r: m.pick[s] })),
    ...m.zoomBtns.map((r, i) => ({ s: `.canvas-zoom-btn[${i}]`, r })),
  ].filter((b) => b.r);

  const bad = [];
  const table = [];
  for (const b of bottoms) {
    const own = radiusPx(b.r.radius, b.r.w, b.r.h);
    table.push(`${b.s}=${b.r.w}×${b.r.h}/r${round1(own)}/pad${padPx(b.r.padding)}/svg${b.r.svg ? b.r.svg.w : 'n/a'}`);
    for (const t of tops) {
      const tr = radiusPx(t.r.radius, t.r.w, t.r.h);
      if (Math.abs(b.r.w - t.r.w) > 1) bad.push(`${b.s}.w ${b.r.w}≠${t.s} ${t.r.w}`);
      if (Math.abs(b.r.h - t.r.h) > 1) bad.push(`${b.s}.h ${b.r.h}≠${t.s} ${t.r.h}`);
      if (Math.abs(own - tr) > 1) bad.push(`${b.s}.radius ${round1(own)}≠${t.s} ${round1(tr)}`);
      if (Math.abs(padPx(b.r.padding) - padPx(t.r.padding)) > 1) bad.push(`${b.s}.padding ${b.r.padding}≠${t.s} ${t.r.padding}`);
      const bs = b.r.svg, ts = t.r.svg;
      if (!bs || !ts || bs.w !== ts.w || bs.h !== ts.h) bad.push(`${b.s}.svg ${bs ? bs.w + '×' + bs.h : 'n/a'}≠${t.s} ${ts ? ts.w + '×' + ts.h : 'n/a'}`);
    }
  }
  ok(`${tag} ⑦ 底部钮 ↔ 顶部钮 尺寸/圆角/内距/图标逐项相等（${bottoms.length} 底 × ${tops.length} 顶）`,
    bottoms.length >= 4 && tops.length === 2 && bad.length === 0,
    bad.length ? bad.slice(0, 6).join(' ') : table.join(' '));
  return { bottoms, tops };
}

/* ── ⑧ 源码零第二份字面量尺寸（机械扫 CSS 规则块）───────────────────── */
function assertNoLiteral(tag) {
  const targets = [
    ['glass.css', '.g2-fab'],
    ['split.css', '.canvas-source-toggle'],
    ['split.css', '.canvas-ref-select'],
    ['split.css', '.canvas-find-toggle'],
    ['split.css', '.canvas-refresh-toggle'],
    ['split.css', '.canvas-zoom-btn'],
    ['split.css', '.canvas-zoom-bar'],
    ['split.css', '.canvas-pop-btn'],
  ];
  const src = {};
  const bad = [];
  for (const [file, sel] of targets) {
    const text = src[file] || (src[file] = readFileSync(join(CSS_DIR, file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, ''));
    const ruleRe = /([^{}]+)\{([^{}]*)\}/g;
    let hit = 0;
    let m;
    while ((m = ruleRe.exec(text))) {
      const sels = m[1].replace(/\s+/g, ' ').trim();
      if (/mobile-layer/.test(sels)) continue;   // 手机层档（既有 --mtap-lg 48 尺）不在本族判据内
      if (/::/.test(sels)) continue;             // 伪元素装饰规则（折射线等）不是控件盒
      // 逐段精确比（伪类/伪元素剥离后再比 token 全等）
      const parts = sels.replace(/::[a-z-]+/g, '').replace(/:[a-z-]+(\([^)]*\))?/g, '').split(',').map((x) => x.trim());
      if (!parts.includes(sel)) continue;
      const body = m[2];
      for (const decl of body.split(';')) {
        const d = decl.trim();
        const mm = /^(width|height|min-width|min-height|max-width|max-height)\s*:\s*(.+)$/.exec(d);
        if (!mm) continue;
        hit++;
        const v = mm[2].replace(/\s+/g, '');
        if (!/^var\(--[a-z0-9-]+(,[^)]*)?\)$/.test(v) && v !== 'auto' && v !== 'fit-content' && v !== 'none' && v !== '100%') {
          bad.push(`${file}:${sel}{${mm[1]}:${mm[2].trim()}}`);
        }
      }
    }
    if (hit === 0 && sel !== '.canvas-zoom-bar') bad.push(`${file}:${sel} 未命中任何宽高声明（判据失效）`);
  }
  ok(`${tag} ⑧b 源码零第二份字面量尺寸（宽高声明一律纯 token var(--…)）`, bad.length === 0, bad.slice(0, 6).join(' ') || 'clean');
}

const browser = await chromium.launch();
try {
  /* ════════════════ 桌面腿 1440×900（暗 / 亮双主题）════════════════ */
  for (const scheme of ['dark', 'light']) {
    const tag = `桌面-${scheme}`;
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: scheme });
    const FILE = 'file:/tmp/ui7/report.html';
    await openItems(page, [
      { id: FILE, itemType: 'html', title: LONG_TITLE, content: HTML_DOC, absPath: '/tmp/ui7/report.html' },
      { id: 'file:/tmp/ui7/plain.md', itemType: 'markdown', title: '说明.md', content: '# x\n\n正文\n', absPath: '/tmp/ui7/plain.md' },
    ]);
    await activate(page, FILE);
    const m = await geom(page);

    /* ① 右座存在 + 位置 + DOM 序 */
    ok(`${tag} ①a 胶囊右座有 .canvas-pop-btn 且在挂可见`,
      !!m.pop && m.pop.display !== 'none' && m.pop.w > 0 && m.pop.h > 0,
      m.pop ? `${m.pop.w}×${m.pop.h} display=${m.pop.display}` : 'missing');
    const contentRight = m.hdr ? m.hdr.right - m.hdrBorder.r - m.hdrPadPx.r : NaN;
    ok(`${tag} ①b rect 完全落在胶囊 padding 盒内 + 右缘贴齐内容盒（右差 ≤1）`,
      !!m.pop && m.pop.x >= m.hdr.x + m.hdrBorder.l - 0.5 && m.pop.right <= m.hdr.right - m.hdrBorder.r + 0.5 &&
      m.pop.y >= m.hdr.y + m.hdrBorder.t - 0.5 && m.pop.bottom <= m.hdr.bottom - m.hdrBorder.b + 0.5 &&
      Math.abs(m.pop.right - contentRight) <= 1,
      `pop.right=${m.pop && m.pop.right} contentRight=${round1(contentRight)} 胶囊=${m.hdr && m.hdr.w}×${m.hdr && m.hdr.h}`);
    ok(`${tag} ①c 竖直中心 = 胶囊中心（|差| ≤1）`,
      !!m.pop && Math.abs(m.pop.cy - m.hdr.cy) <= 1, `pop.cy=${m.pop && m.pop.cy} hdr.cy=${m.hdr.cy}`);
    ok(`${tag} ①d DOM 序 = .canvas-title 的同级后继（右座单件，非浮动）`,
      m.popIsTitleNext === true, `title.nextElementSibling=${m.popIsTitleNext ? 'canvas-pop-btn' : '其他'}`);

    /* ② 文件卡点击 → window.open 恰 1 次（stub 计数路径） */
    const openCount = await (async () => {
      await page.evaluate(() => {
        window.__ui7Open = 0;
        window.__ui7OrigOpen = window.open;
        window.open = function () { window.__ui7Open++; return null; };
      });
      await page.click('#canvas-panel .canvas-header .canvas-pop-btn', { timeout: 3000 }).catch(() => {});
      await sleep(400);
      return page.evaluate(() => { const n = window.__ui7Open | 0; window.open = window.__ui7OrigOpen; return n; });
    })();
    ok(`${tag} ② 文件卡页签：点击独立窗入口 → window.open 恰 1 次（stub 计数路径）`,
      openCount === 1, `window.open=${openCount}`);

    /* ⑤ 环开态（#926 无重排判据）：常态 t-name 盒宽 vs 中心覆层文字盒宽 */
    const restNameW = m.tname ? m.tname.w : NaN;
    const hdrBox = m.hdr ? { x: m.hdr.cx, y: m.hdr.cy } : null;
    if (hdrBox) await page.mouse.move(hdrBox.x, hdrBox.y);
    await sleep(700);
    const ring = await page.evaluate(() => {
      const root = document.getElementById('canvas-capsule-ring');
      const hdr = document.querySelector('#canvas-panel .canvas-header');
      const center = root ? root.querySelector('.cr-capsule.is-center') : null;
      const name = center ? center.querySelector('.cr-name') : null;
      const r = name ? name.getBoundingClientRect() : null;
      return {
        open: !!hdr && hdr.classList.contains('ring-open'),
        caps: root ? root.querySelectorAll('.cr-capsule').length : 0,
        popw: root ? getComputedStyle(root).getPropertyValue('--cr-popw').trim() : '',
        nameW: r ? Math.round(r.width * 100) / 100 : null,
      };
    });
    ok(`${tag} ⑤a 前置：环真开（ring-open + ≥2 环胶囊 + --cr-popw = 32px）`,
      ring.open && ring.caps >= 2 && ring.popw === '32px',
      `open=${ring.open} caps=${ring.caps} --cr-popw=${ring.popw}`);
    ok(`${tag} ⑤b #926 无重排：中心覆层文字盒宽 = 常态 .t-name 盒宽 ±1`,
      ring.nameW != null && Math.abs(ring.nameW - restNameW) <= 1,
      `ring=${ring.nameW} rest=${restNameW}`);
    await page.mouse.move(hdrBox ? hdrBox.x : 700, (hdrBox ? hdrBox.y : 300) + 400);
    await sleep(600);

    /* ⑥ 长标题不换排 */
    const longOk = !!m.tname && m.tnameTrunc && m.tnameTrunc.scroll > m.tnameTrunc.client &&
      m.tname.h <= 20 && m.tname.textOverflow === 'ellipsis' && m.tname.whiteSpace === 'nowrap' &&
      !!m.hdr && m.hdr.w <= Math.max(220, m.pane.w - 368) + 1 && m.hdr.w <= m.pane.w * 0.76 + 1;
    ok(`${tag} ⑥ 长标题（${LONG_TITLE.length} 字符）单行 + ellipsis + 胶囊宽 ≤ max-width`,
      longOk,
      `t-name ${m.tname && m.tname.w}×${m.tname && m.tname.h} scroll=${m.tnameTrunc && m.tnameTrunc.scroll}/client=${m.tnameTrunc && m.tnameTrunc.client} ellipsis=${m.tname && m.tname.textOverflow} 胶囊=${m.hdr && m.hdr.w} maxW=${m.hdrMaxW}`);

    /* ⑦ 底部 ↔ 顶部 同尺寸同样式 */
    const { bottoms } = assertPairs(tag, m);

    /* ⑧ token 对照表 + 源码零第二份字面量 */
    const tokenW = parseFloat(m.tokens.circleLg);
    const tokenIcon = parseFloat(m.tokens.icon);
    const tokenBad = bottoms.filter((b) => !(Math.abs(b.r.w - tokenW) <= 0.5 && Math.abs(b.r.h - tokenW) <= 0.5 && b.r.svg && Math.abs(b.r.svg.w - tokenIcon) <= 0.5 && Math.abs(b.r.svg.h - tokenIcon) <= 0.5));
    ok(`${tag} ⑧a 每颗 chrome 钮计算尺寸 = token（--cv-size-circle-lg=${m.tokens.circleLg} / --cv-icon=${m.tokens.icon}；--cv-size-circle=${m.tokens.circle}）`,
      tokenBad.length === 0 && Math.abs(tokenW - parseFloat(m.tokens.circle)) <= 0.5,
      tokenBad.length ? tokenBad.map((b) => `${b.s}=${b.r.w}×${b.r.h}/svg${b.r.svg ? b.r.svg.w : 'n/a'}`).join(' ') : bottoms.map((b) => `${b.s}→${b.r.w}×${b.r.h}`).join(' '));
    if (scheme === 'dark') assertNoLiteral(tag);

    /* ⑩ 既有三条关系在新尺寸下仍成立 */
    const boxes = [...bottoms.map((b) => ({ name: b.s, r: b.r }))];
    const square = boxes.filter((b) => Math.abs(b.r.h - b.r.w) > 1).map((b) => `${b.name}=${b.r.w}×${b.r.h}`);
    ok(`${tag} ⑩a 正方/正圆（高 = 宽 ±1，${boxes.length} 件）`, square.length === 0, square.join(' ') || 'all square');
    const barGaps = m.zoomBtns.filter((b) => b.adjNext).map((b) => round1(m.zoomBtns[m.zoomBtns.indexOf(b) + 1].x - b.right));
    ok(`${tag} ⑩b 条内相邻缝一致（${barGaps.join('/') || 'n/a'} 两两差 ≤1 且 ≥7）`,
      barGaps.length >= 1 && Math.max(...barGaps) - Math.min(...barGaps) <= 1 && Math.min(...barGaps) >= 7,
      JSON.stringify(barGaps));
    // 条内钮是条的子件（必然相交）→ 顶层只比「条 + 四圆钮」；条内钮单独判 pane 内
    const hostiles = [m.bar, ...SEL.bottom.map((sel) => m.pick[sel])].filter(Boolean);
    const anti = [];
    for (let i = 0; i < hostiles.length; i++) for (let j = i + 1; j < hostiles.length; j++) {
      if (inter(hostiles[i], hostiles[j])) anti.push(`${hostiles[i].sel || i}∩${hostiles[j].sel || j}`);
    }
    if (m.pane) for (const h of [...hostiles, ...m.zoomBtns]) {
      if (h.x < m.pane.x - 0.5 || h.y < m.pane.y - 0.5 || h.right > m.pane.right + 0.5 || h.bottom > m.pane.bottom + 0.5) anti.push(`${h.sel}出界`);
    }
    ok(`${tag} ⑩c 渲染盒零相交（条/四圆钮两两不相交 + 条内钮与顶层盒全落 pane 内）`, anti.length === 0, anti.join(' ') || 'clean');

    await page.screenshot({ path: join(SHOTS_DIR, `after-desktop-${scheme}.png`) }).catch(() => {});

    /* ③ 非内容物页签：禁用 + 提示 + 零开窗（判据不推翻） */
    const panelTab = await page.evaluate(() => {
      const ids = [...document.querySelectorAll('.canvas-tab[data-tab-id]')].map((t) => t.dataset.tabId);
      return ids.find((id) => id && !id.startsWith('file:')) || null;
    });
    const hint = await page.evaluate(async () => {
      const mod = await import('/js/i18n.js');
      return (mod.t || mod.default && mod.default.t)('canvas.cardwinContentOnly');
    });
    if (!panelTab) {
      ok(`${tag} ③ 非内容物页签禁用态（前置缺失：无功能面板页签）`, false, 'no non-content tab');
    } else {
      await activate(page, panelTab);
      const probe = await page.evaluate(() => {
        const b = document.querySelector('#canvas-panel .canvas-header .canvas-pop-btn');
        return b ? { disabled: !!b.disabled, ariaDisabled: b.getAttribute('aria-disabled'), title: b.getAttribute('title') || '', label: b.getAttribute('aria-label') || '' } : null;
      });
      ok(`${tag} ③a 非内容物页签：按钮禁用 + 提示文案 = canvas.cardwinContentOnly（${panelTab}）`,
        !!probe && probe.disabled === true && probe.title === hint && phraseEq(probe.label, hint),
        probe ? `disabled=${probe.disabled} title="${probe.title}" label="${probe.label}" hint="${hint}"` : 'missing');
      const n2 = await (async () => {
        await page.evaluate(() => {
          window.__ui7Open = 0;
          window.__ui7OrigOpen = window.open;
          window.open = function () { window.__ui7Open++; return null; };
        });
        await page.evaluate(() => document.querySelector('#canvas-panel .canvas-header .canvas-pop-btn')?.click());
        await sleep(300);
        return page.evaluate(() => { const n = window.__ui7Open | 0; window.open = window.__ui7OrigOpen; return n; });
      })();
      ok(`${tag} ③b 非内容物页签点击 → window.open 计数 = 0（内容物收口判据不推翻）`, !!probe && n2 === 0, `window.open=${n2}`);
    }

    ok(`${tag} 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await ctx.close();
  }

  /* ════════════════ 手机腿 375×812（⑨ 手机档零位移）════════════════ */
  {
    const tag = '手机-375';
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 375, height: 812 }, colorScheme: 'dark', mobile: true });
    const FILE = 'file:/tmp/ui7/m.html';
    await openItems(page, [
      { id: FILE, itemType: 'html', title: '巡检报告.html', content: HTML_DOC, absPath: '/tmp/ui7/m.html' },
      { id: 'file:/tmp/ui7/m2.md', itemType: 'markdown', title: '说明.md', content: '# x\n\n正文\n', absPath: '/tmp/ui7/m2.md' },
    ]);
    await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
    await sleep(1400);
    await activate(page, FILE);
    const m = await geom(page);

    /* ④ 手机端隐藏 + 胶囊零位移 */
    const popHidden = !!m.pop && (m.pop.display === 'none' || (m.pop.w === 0 && m.pop.h === 0));
    ok(`${tag} ④a body.mobile-layer：.canvas-pop-btn display:none / 零盒`,
      popHidden, m.pop ? `display=${m.pop.display} ${m.pop.w}×${m.pop.h}` : 'missing');
    ok(`${tag} ④b 胶囊盒与改前相等 ±1（手机档零位移；改前 = ${BEFORE.mobile.header.w}×${BEFORE.mobile.header.h}）`,
      !!m.hdr && Math.abs(m.hdr.w - BEFORE.mobile.header.w) <= 1 && Math.abs(m.hdr.h - BEFORE.mobile.header.h) <= 1,
      m.hdr ? `${m.hdr.w}×${m.hdr.h}` : 'missing');

    /* ⑨ 逐颗读数 = 改前基线 */
    const B = BEFORE.mobile;
    const rows = [
      ['source', m.pick['.canvas-source-toggle'], B.source],
      ['ref', m.pick['.canvas-ref-select'], B.ref],
      ['find', m.pick['.canvas-find-toggle'], B.find],
      ['refresh', m.pick['.canvas-refresh-toggle'], B.refresh],
      ['bar', m.bar, B.bar],
    ];
    const mismatch = [];
    for (const [name, cur, base] of rows) {
      if (!cur) { if (base) mismatch.push(`${name}=missing`); continue; }
      for (const k of Object.keys(base)) if (Math.abs((cur[k] ?? 0) - base[k]) > 1) mismatch.push(`${name}.${k} ${cur[k]}≠${base[k]}`);
    }
    const zoomBad = m.zoomBtns.filter((b) => Math.abs(b.w - B.zoomBtn.w) > 1 || Math.abs(b.h - B.zoomBtn.h) > 1 ||
      !b.svg || Math.abs(b.svg.w - B.zoomBtn.svg) > 1 || b.radius !== B.zoomBtn.radius);
    ok(`${tag} ⑨a 手机档钮面读数 = 改前基线（source/ref/find/refresh/条 逐值相等 ±1）`,
      mismatch.length === 0, mismatch.slice(0, 6).join(' ') || rows.map(([n, c]) => `${n}=${c ? c.w + '×' + c.h + '@' + c.x : 'n/a'}`).join(' '));
    ok(`${tag} ⑨b 手机档条内钮 = 改前（48×48 / svg ${B.zoomBtn.svg} / r=${B.zoomBtn.radius}）`,
      m.zoomBtns.length === 3 && zoomBad.length === 0,
      zoomBad.length ? zoomBad.map((b) => `${b.w}×${b.h}/svg${b.svg ? b.svg.w : 'n/a'}/r${b.radius}`).join(' ') : m.zoomBtns.map((b) => `${b.w}×${b.h}/svg${b.svg.w}/r${b.radius}`).join(' '));

    /* ⑩ 手机腿：正方 + 缝一致 + 零相交（vs 环带签/小点/两颗浮钮/ask） */
    const mBoxes = [...SEL.bottom.map((s) => ({ name: s, r: m.pick[s] })), ...m.zoomBtns.map((r, i) => ({ name: 'zoom' + i, r }))].filter((b) => b.r && b.r.w > 0);
    const mSq = mBoxes.filter((b) => Math.abs(b.r.h - b.r.w) > 1).map((b) => `${b.name}=${b.r.w}×${b.r.h}`);
    ok(`${tag} ⑩d 手机腿正方/正圆（高 = 宽 ±1，${mBoxes.length} 件）`, mSq.length === 0, mSq.join(' ') || 'all square');
    const mGaps = m.zoomBtns.filter((b) => b.adjNext).map((b) => round1(m.zoomBtns[m.zoomBtns.indexOf(b) + 1].x - b.right));
    ok(`${tag} ⑩e 手机腿条内相邻缝一致（${mGaps.join('/')} 两两差 ≤1 且 ≥7）`,
      mGaps.length >= 1 && Math.max(...mGaps) - Math.min(...mGaps) <= 1 && Math.min(...mGaps) >= 7, JSON.stringify(mGaps));
    const circles = SEL.bottom.map((s) => m.pick[s]).filter((b) => b && b.w > 0);
    const hostile = [m.bar, ...circles, ...m.mchrome.chips, m.mchrome.dots, m.mchrome.closeBtn, m.mchrome.stackBtn, m.mchrome.askFab].filter(Boolean);
    const mAnti = [];
    for (let i = 0; i < hostile.length; i++) for (let j = i + 1; j < hostile.length; j++) {
      if (inter(hostile[i], hostile[j])) mAnti.push(`#${i}∩#${j}`);
    }
    ok(`${tag} ⑩f 手机腿渲染盒零相交（条/钮 vs 环带签/小点/左右浮钮/ask）`, mAnti.length === 0, mAnti.join(' ') || 'clean');

    await page.screenshot({ path: join(SHOTS_DIR, 'after-mobile-dark.png') }).catch(() => {});
    ok(`${tag} 零 pageerror`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await ctx.close();
  }
} finally {
  await browser.close();
  server.close();
}

/** 文案相等（允许浏览器把 title 规整化：去首尾空白）。 */
function phraseEq(a, b) {
  return String(a || '').trim() === String(b || '').trim();
}

console.log(results.join('\n'));
console.log(`\n═══ ui-seven-canvas-chrome: ${pass} PASS / ${fail} FAIL ═══`);
process.exit(fail ? 1 : 0);
