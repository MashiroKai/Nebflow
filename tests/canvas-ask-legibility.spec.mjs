#!/usr/bin/env node
// canvas-ask-legibility.spec.mjs — Canvas Ask 可读性与布局四件（作者令 2026-11-05
// 09:32，逐字 + 截屏证据）的机械验收。
//
// 四件 → 判据映射（每条都是「实测值 + 断言」，不是形态描述）：
//   L1  件一·不透明毛玻璃（「我说了要用毛玻璃背景，采用我们主会话的 Header 的
//       同款，那个是不透明的」）：
//       L1a 计算背景 α = 1（.ask-bub / .ask-bub.user / .ask-input 三面）；
//       L1b 不透明底 = --glass-bg 压平在 --color-bg 上的**合成值**（逐通道 ≤1，
//           证明「同款」不是新色相）；
//       L1c 同款配方逐项：blur/saturate 参数与 #header 相等、描边/阴影 token 同源、
//           顶缘折射线层在场（--glass-refraction-layer）。
//   L2  件一·可读性硬指标：正文 color 与其**最终合成背景**的对比度 ≥ 4.5:1（贴算值）。
//   L3  件一·结构证据「同位置文字只出现一次」：把一块**最坏情况背景**（黑白条纹
//       探针）铺在面板正后方，采两帧（探针亮/探针灭）在气泡与输入条内部逐像素
//       差分 ⇒ 差分像素数 = 0、最大通道差 ≤ 2。半透档必然大红（before 输出现场）。
//   L4  件二·拖动按钮只要一个小边框：把手 DOM 子节点数 = 0（旧 .ds-resize-grip
//       三枚 <i> 退役）、可见描边只剩 ::before 一条直角弧（上/左 2px，右/下 0）、
//       热区仍 ≥44（HIG 不回退）、源码面零 .ds-resize-grip 选择器。
//   L5  件三·顶部渐隐短距离完成：--ask-fade-h ≤ 16px 且 = 12px（桌面 + 手机双档）、
//       mask 首段终停 = 12px、列 padding-top ≥ fade（静态首条不被洗）、
//       且渐隐只发生在列顶那一条（首条气泡顶 ≥ 列顶 + fade）。
//   L6  件四·缩放无空白：五档（375/768/1024/1440/1920，含 resize 连续切换）下
//       「输入条顶边 − 列底边」恒 = dock row-gap（--ds-space-1h = 6px，±0.5），
//       「输入条顶边 − 最新气泡底边」恒 = row-gap + --ask-col-tail（18px，±0.5）；
//       L6c 缓存变量零残留（--ask-trunc-h / --ask-trunc-from / --ask-col-overhang
//       在 dock 上取值为空 + askDock.js 源码零 syncTruncLine）。
//
// 改前/改后同脚本同夹具（验红纪律）：
//   node tests/canvas-ask-legibility.spec.mjs                          # 改后（预期全绿）
//   ASK_BEFORE_WEB=<改前 web 根> node tests/canvas-ask-legibility.spec.mjs  # 改前（预期红）
// 可选 env：PORT（默认 8227）、ASK_LEG_SHOTS_DIR（截图目录）。
//
// 只读静态资源 + routeWebSocket mock：不连、不启、不 kill 任何引擎进程（零 6138 接触）。

import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = process.env.ASK_BEFORE_WEB
  ? resolve(process.env.ASK_BEFORE_WEB)
  : resolve(HERE, '../src/main/resources/web');
const PHASE = process.env.ASK_BEFORE_WEB ? 'before' : 'after';
const PORT = Number(process.env.PORT || 8227);
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = process.env.ASK_LEG_SHOTS_DIR
  || resolve(HERE, `../reports-evidence/canvas-ask-legibility/shots/${PHASE}`);
mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);

// ── 静态服务（serve 真实 web/ 前端源码树；canvas-ask-dock 同款）──
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
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── 断言器 ──
let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  results.push(line);
  // eslint-disable-next-line no-console
  console.log(line);
  if (cond) pass++; else fail++;
}

// ── ask 会话 mock 面 ──
/** @type {any} */ let ws = null;
function wsSend(o) { if (ws) ws.send(JSON.stringify(o)); }
const sessions = { msgs: {} };
const expectTranscript = (sid, m) => { sessions.msgs[sid] = m; };

const DOC = Array.from({ length: 44 }, (_, i) =>
  `第 ${i + 1} 段：底层文档正文（用于验证 Ask 面板是否透字）。10px 值本身未收敛，358 处布局位移需单独一批；组合四值圆角 12 处、负间距 21+ 处未动。`).join('\n\n');

const nfFileRoute = () => async (route) => {
  if (await ticketGuard(route)) return;
  const p = new URL(route.request().url()).searchParams.get('path') || '';
  return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
};

async function boot(browser, { viewport, colorScheme = 'dark', mobile = false }) {
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 260)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (w) => {
    ws = w;
    w.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') w.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
        w.send(JSON.stringify({
          type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8),
          file: (m.file && m.file.absPath) || '', fileName: (m.file && m.file.name) || '',
          messages: sessions.msgs[sid] || [],
        }));
      }
    });
    w.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    w.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
  else await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(mobile ? 800 : 600);
  return { ctx, page, errors };
}

async function openFile(page, name, content, { state = 'visible' } = {}) {
  const absPath = `/tmp/canvas-ask-legibility/${name}`;
  await page.evaluate(({ name, absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content, absPath, pinned: true },
    }));
  }, { name, absPath, content });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state, timeout: 10000 });
  await sleep(350);
  return absPath;
}

/** 打开 ask dock（桌面：点 fab；手机：先切 canvas 根页再点 fab）。 */
async function openDock(page, mobile = false) {
  if (mobile) {
    await page.click('#mobile-dock .dock-key[data-dock="canvas"]');
    await sleep(800);
  }
  await page.click('.ask-fab');
  await sleep(700);
}

/** 全量读数：几何 + 材质 + 渐隐 + 缓存变量残留。 */
const read = (page) => page.evaluate(() => {
  const q = (s) => document.querySelector(s);
  const box = (n) => {
    if (!n) return null;
    const b = n.getBoundingClientRect();
    const r = parseFloat(getComputedStyle(n).borderTopLeftRadius) || 0;
    return { x: b.x, y: b.y, w: b.width, h: b.height, top: b.top, bottom: b.bottom, left: b.left, right: b.right, r };
  };
  const dock = q('.ask-dock'), col = q('.ask-dock .ask-col'), input = q('.ask-dock .ask-input'), wrap = q('.ask-dock .ask-colwrap');
  const bubs = [...document.querySelectorAll('.ask-dock .ask-bub')];
  const lastBub = bubs.length ? bubs[bubs.length - 1] : null;
  const aiBub = [...document.querySelectorAll('.ask-dock .ask-bub.ai')].slice(-1)[0] || null;
  const userBub = q('.ask-dock .ask-bub.user');
  const handle = q('.ask-dock .ds-resize-handle');
  const dcs = dock ? getComputedStyle(dock) : null;
  const ccs = col ? getComputedStyle(col) : null;
  const hb = handle ? getComputedStyle(handle, '::before') : null;
  const mat = (n) => {
    if (!n) return null;
    const cs = getComputedStyle(n);
    const pb = getComputedStyle(n, '::before');
    return {
      bg: cs.backgroundColor, bgImage: cs.backgroundImage, border: cs.borderTopColor, blur: cs.backdropFilter,
      color: cs.color, radius: cs.borderTopLeftRadius, shadow: cs.boxShadow,
      // 顶缘折射线：#header 用背景层，Ask 面用同 token 的 ::before（几何等价）
      refractionBefore: pb && pb.content !== 'none' ? pb.backgroundImage : 'none',
      refractionHeight: pb && pb.content !== 'none' ? pb.height : null,
    };
  };
  // 合成值：--glass-bg 压平在 --color-bg 上（同一 theme 上下文里两个 token 都读得到）
  // token 值可能是 hex / rgb / rgba 三种写法 ⇒ 一律先塞进探针的 backgroundColor 让
  // 浏览器归一成 rgb()/rgba() 再解析（不做手写色值解析，避免各写法漏判）。
  const probe = document.createElement('div');
  document.body.appendChild(probe);
  const norm = (raw) => {
    if (!raw) return null;
    probe.style.backgroundColor = '';
    probe.style.backgroundColor = String(raw).trim();
    const s = getComputedStyle(probe).backgroundColor;
    const m = String(s).match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const p = m[1].split(/[,/]/).map((x) => parseFloat(x.trim()));
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  };
  const cs0 = getComputedStyle(probe);
  const glassBg = norm(cs0.getPropertyValue('--glass-bg'));
  const paper = norm(cs0.getPropertyValue('--color-bg'));
  const opaqueTok = norm(cs0.getPropertyValue('--glass-bg-opaque'));
  const cvOpaque = norm(cs0.getPropertyValue('--cv-bg-opaque'));
  const fadeH = dcs ? dcs.getPropertyValue('--ask-fade-h').trim() : '';
  const tail = dcs ? dcs.getPropertyValue('--ask-col-tail').trim() : ''; // 毛玻璃批 2026-11-06 起退役，恒 ''
  const under = dcs ? dcs.getPropertyValue('--ask-under').trim() : '';
  probe.remove();
  const composite = glassBg && paper ? {
    r: Math.round(glassBg.a * glassBg.r + (1 - glassBg.a) * paper.r),
    g: Math.round(glassBg.a * glassBg.g + (1 - glassBg.a) * paper.g),
    b: Math.round(glassBg.a * glassBg.b + (1 - glassBg.a) * paper.b),
  } : null;
  return {
    vp: { w: innerWidth, h: innerHeight },
    dock: box(dock), col: box(col), wrap: box(wrap), input: box(input), lastBub: box(lastBub), aiBub: box(aiBub),
    nBub: bubs.length,
    rowGap: dcs ? dcs.rowGap : '',
    fadeH, tail, under,
    colPadTop: ccs ? ccs.paddingTop : '', colPadBottom: ccs ? ccs.paddingBottom : '',
    colMask: ccs ? (ccs.maskImage || ccs.webkitMaskImage || '') : '',
    maskStops: (() => {
      const mi = ccs ? (ccs.maskImage || ccs.webkitMaskImage || '') : '';
      if (!mi || mi === 'none') return [];
      return [...mi.matchAll(/(transparent|currentcolor|rgba?\([^)]*\))[^,)]*?([\d.]+)(px|%)/gi)]
        .map((x) => ({ color: x[1], v: parseFloat(x[2]), unit: x[3] }));
    })(),
    matBubAi: mat(aiBub), matBubUser: mat(userBub), matInput: mat(input),
    header: mat(document.getElementById('header')),
    glassBg, paper, composite, opaqueTok, cvOpaque,
    // 缓存变量残留（件四 L6c）
    legacy: dcs ? {
      truncH: dcs.getPropertyValue('--ask-trunc-h').trim(),
      truncFrom: dcs.getPropertyValue('--ask-trunc-from').trim(),
      overhang: dcs.getPropertyValue('--ask-col-overhang').trim(),
      inlineStyle: dock.getAttribute('style') || '',
    } : null,
    // 拖柄形态（件二 L4）
    handle: handle ? {
      childCount: handle.children.length,
      gripSelectorHits: document.querySelectorAll('.ds-resize-grip, .ds-resize-grip i').length,
      hit: box(handle),
      arm: hb ? {
        top: hb.borderTopWidth, left: hb.borderLeftWidth,
        right: hb.borderRightWidth, bottom: hb.borderBottomWidth,
        radius: hb.borderTopLeftRadius,
        width: hb.width, height: hb.height,
      } : null,
    } : null,
  };
});

/** WCAG 对比度（正文 color vs 最终合成底）。 */
const contrast = (page) => page.evaluate(() => {
  const bub = [...document.querySelectorAll('.ask-dock .ask-bub.ai')].slice(-1)[0];
  const input = document.querySelector('.ask-dock .ask-input');
  const parse = (s) => {
    const m = String(s).match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const p = m[1].split(/[,/]/).map((x) => parseFloat(x.trim()));
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  };
  const lin = (c) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  const L = (c) => 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b);
  const ratio = (fg, bg) => {
    const a = L(fg), b = L(bg);
    return +(((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)).toFixed(2));
  };
  const out = {};
  for (const [k, n] of [['bub', bub], ['input', input]]) {
    if (!n) { out[k] = null; continue; }
    const cs = getComputedStyle(n);
    const bg = parse(cs.backgroundColor);
    const fg = k === 'input'
      ? parse(getComputedStyle(n.querySelector('textarea')).color)
      : parse(cs.color);
    out[k] = bg && fg ? { bg, fg, alpha: bg.a, ratio: ratio(fg, bg) } : null;
  }
  return out;
});

/**
 * 像素差分取证（件一 L3 / 「同位置文字只出现一次」）：
 * 在面板正后方铺一块黑白条纹探针（最坏情况背景），采两帧（探针亮 / 探针灭），
 * 在给定盒内逐像素求最大通道差与超阈像素数。
 * 面板不透明 ⇒ 两帧在该盒内逐像素相同（差 0）。
 */
async function pixelDiff(page, boxes, tag) {
  const a = [];
  for (let i = 0; i < boxes.length; i++) {
    const b = boxes[i];
    // 内缩必须吃掉圆角：圆角弧外的那几像素本来画的是背后背景（合法），
    // 不内缩就会把 4 个角的 AA 采样当成「透字」误报（本批实测：12 px / 最大差 148）。
    const radius = Math.max(12, Math.round(b.r || 0));
    const inset = radius + 2;
    const clip = {
      x: Math.round(b.left + inset), y: Math.round(b.top + inset),
      width: Math.max(1, Math.round(b.w - inset * 2)), height: Math.max(1, Math.round(b.h - inset * 2)),
    };
    const on = await page.screenshot({ clip });
    const off = await page.evaluate(({ idx }) => {
      const el = document.getElementById('ask-ink-probe');
      if (el) el.style.visibility = 'hidden';
      return idx;
    }, { idx: i });
    await sleep(80);
    const no = await page.screenshot({ clip });
    await page.evaluate(() => {
      const el = document.getElementById('ask-ink-probe');
      if (el) el.style.visibility = 'visible';
    });
    await sleep(60);
    a.push({ on, no, clip });
    writeFileSync(join(OUT, `probe-${tag}-${i}-on.png`), on);
    writeFileSync(join(OUT, `probe-${tag}-${i}-off.png`), no);
    void off;
  }
  // 用空白页做 PNG 解码 + 逐像素差分（浏览器内 canvas，零第三方依赖）
  const ctx = await page.context().browser().newContext();
  const p2 = await ctx.newPage();
  await p2.goto('about:blank');
  const res = await p2.evaluate(async ({ frames }) => {
    const load = (b64) => new Promise((res, rej) => {
      const img = new Image();
      img.onload = () => res(img);
      img.onerror = rej;
      img.src = 'data:image/png;base64,' + b64;
    });
    const out = [];
    for (const f of frames) {
      const [i1, i2] = await Promise.all([load(f.on), load(f.no)]);
      const w = i1.width, h = i1.height;
      const mk = (img) => {
        const c = document.createElement('canvas');
        c.width = w; c.height = h;
        const x = c.getContext('2d');
        x.drawImage(img, 0, 0);
        return x.getImageData(0, 0, w, h).data;
      };
      const d1 = mk(i1), d2 = mk(i2);
      let maxDiff = 0, over = 0;
      for (let k = 0; k < d1.length; k += 4) {
        const d = Math.max(Math.abs(d1[k] - d2[k]), Math.abs(d1[k + 1] - d2[k + 1]), Math.abs(d1[k + 2] - d2[k + 2]));
        if (d > maxDiff) maxDiff = d;
        if (d > 2) over++;
      }
      out.push({ w, h, pixels: w * h, maxDiff, over });
    }
    return out;
  }, { frames: a.map((x) => ({ on: x.on.toString('base64'), no: x.no.toString('base64') })) });
  await ctx.close();
  return res;
}

/** 在面板正后方铺最坏情况背景探针（黑白条纹，覆盖整个 pane；z 序在 dock 之下）。 */
async function installInkProbe(page) {
  await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active') || document.getElementById('canvas-content');
    if (!pane) return;
    let el = document.getElementById('ask-ink-probe');
    if (!el) {
      el = document.createElement('div');
      el.id = 'ask-ink-probe';
      el.setAttribute('aria-hidden', 'true');
      el.style.cssText = 'position:absolute;inset:0;z-index:0;pointer-events:none;'
        + 'background:repeating-linear-gradient(0deg,#000 0 10px,#fff 10px 20px);';
      pane.appendChild(el);
    }
    el.style.visibility = 'visible';
  });
  await sleep(120);
}

const shot = (page, name) => page.screenshot({ path: join(OUT, name + '.png') }).catch(() => {});

// ═══════════════════════════════════════════════════════════════════════════
const browser = await chromium.launch();
const summary = { phase: PHASE, web: WEB_DIR };
try {
  // ── 桌面腿（1440×900 暗档）：四件主判据 ──
  const { page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  const abs = await openFile(page, '可读性取证.md', DOC);
  // 一段中等长度的 AI 答（遮住背后文档正文；单条短会话来测像素，不滚动）
  expectTranscript(sidOf(abs), [
    { role: 'user', text: '这份文档里明确没做的有哪几条？' },
    { role: 'assistant', text: '明确没做的（§8）：\n\n- 10px 值本身未收敛（358 处布局位移，需单独一批）；\n- 组合四值圆角 12 处、负间距 21+ 处未动；\n- 14px 间距 109 处（定版明载不是档）；\n- check-visual 现为红，等评审。' },
  ]);
  await openDock(page, false);
  await sleep(900);

  ok('L0 页面零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await shot(page, `dock-1440-dark-${PHASE}`);

  const d = await read(page);
  writeFileSync(join(OUT, 'readings-1440-dark.json'), JSON.stringify({ phase: PHASE, d }, null, 2));

  // ── L1 件一：不透明毛玻璃（Header 同款）──
  const aOf = (m) => (m ? Math.round(Number(String(m.bg).match(/rgba?\(([^)]+)\)/)[1].split(',')[3] ?? 1) * 100) / 100 : null);
  const alphaOf = (bgStr) => {
    const m = String(bgStr).match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const p = m[1].split(',').map((x) => parseFloat(x.trim()));
    return p.length > 3 ? p[3] : 1;
  };
  const aBub = alphaOf(d.matBubAi && d.matBubAi.bg);
  const aInput = alphaOf(d.matInput && d.matInput.bg);
  const aUser = alphaOf(d.matBubUser && d.matBubUser.bg);
  ok('L1a 三面计算背景 α = 1（气泡 / 用户气泡 / 输入条：不再透出底层文档）',
    aBub === 1 && aInput === 1 && aUser === 1,
    JSON.stringify({ bub: d.matBubAi && d.matBubAi.bg, user: d.matBubUser && d.matBubUser.bg, input: d.matInput && d.matInput.bg }));
  void aOf;

  const near = (x, y) => !!x && !!y && Math.abs(x - y) <= 1;
  const c = d.composite;
  const tok = d.opaqueTok;
  ok('L1b 不透明底 = --glass-bg 压平在 --color-bg 上的合成值（同色收 α，零新色相）',
    !!c && !!tok && near(tok.r, c.r) && near(tok.g, c.g) && near(tok.b, c.b),
    JSON.stringify({ composite: c, glassBgOpaque: tok, glassBg: d.glassBg, paper: d.paper, cvBgOpaque: d.cvOpaque }));

  const blurNum = (s) => {
    const m = String(s || '').match(/blur\(([\d.]+)px\)/);
    return m ? parseFloat(m[1]) : null;
  };
  ok('L1c 「Header 同款」逐项：blur 参数与 #header 相等 + 描边同 token + 顶缘折射线层同 token 在场',
    !!d.header && blurNum(d.matBubAi.blur) === blurNum(d.header.blur)
    && d.matBubAi.border === d.header.border
    && /gradient/.test(d.header.bgImage)
    && /gradient/.test(d.matBubAi.refractionBefore) && /gradient/.test(d.matInput.refractionBefore)
    && d.matBubAi.refractionHeight === '1px' && d.matInput.refractionHeight === '1px',
    JSON.stringify({
      bubBlur: d.matBubAi.blur, inputBlur: d.matInput.blur, headerBlur: d.header.blur,
      bubBorder: d.matBubAi.border, headerBorder: d.header.border,
      headerRefraction: String(d.header.bgImage).slice(0, 60),
      bubRefraction: String(d.matBubAi.refractionBefore).slice(0, 60),
      inputRefractionH: d.matInput.refractionHeight,
    }));

  // ── L2 件一：对比度 ≥ 4.5:1（贴计算值）──
  const ct = await contrast(page);
  writeFileSync(join(OUT, 'contrast-1440-dark.json'), JSON.stringify({ phase: PHASE, ct }, null, 2));
  ok('L2 正文与其最终合成背景的对比度 ≥ 4.5:1（气泡 + 输入条，WCAG AA）',
    !!ct.bub && ct.bub.ratio >= 4.5 && !!ct.input && ct.input.ratio >= 4.5,
    JSON.stringify({ bub: ct.bub && ct.bub.ratio, input: ct.input && ct.input.ratio, bubBg: ct.bub && ct.bub.bg }));

  // ── L3 件一：结构证据——同位置文字只出现一次（像素级）──
  await installInkProbe(page);
  const boxes = [d.aiBub, d.input].filter(Boolean);
  const diffs = await pixelDiff(page, boxes, `1440-dark-${PHASE}`);
  writeFileSync(join(OUT, 'pixel-diff-1440-dark.json'), JSON.stringify({ phase: PHASE, diffs }, null, 2));
  const maxDiff = Math.max(...diffs.map((x) => x.maxDiff));
  const over = diffs.reduce((s, x) => s + x.over, 0);
  ok('L3 面板内部像素与背后内容无关（最坏情况背景探针亮/灭两帧：差分像素 0、最大通道差 ≤2）',
    over === 0 && maxDiff <= 2,
    JSON.stringify({ frames: diffs.map((x) => ({ px: x.pixels, maxDiff: x.maxDiff, over: x.over })) }));
  await shot(page, `dock-1440-dark-probe-${PHASE}`);
  await page.evaluate(() => { const el = document.getElementById('ask-ink-probe'); if (el) el.remove(); });

  // ── L4 件二：拖动按钮 = 一个小边框 ──
  const h = d.handle;
  ok('L4a 把手 DOM 零装饰子节点（旧 .ds-resize-grip 三枚 L 形 <i> 退役；DOM 命中数 = 0）',
    !!h && h.childCount === 0 && h.gripSelectorHits === 0,
    JSON.stringify({ childCount: h && h.childCount, gripHits: h && h.gripSelectorHits }));
  ok('L4b 可见描边只剩一条直角弧（::before：上/左 2px，右/下 0，18px 臂长 + 7px 圆角）',
    !!h && !!h.arm && parseFloat(h.arm.top) === 2 && parseFloat(h.arm.left) === 2
    && parseFloat(h.arm.right) === 0 && parseFloat(h.arm.bottom) === 0
    && Math.round(parseFloat(h.arm.width)) === 18 && Math.round(parseFloat(h.arm.height)) === 18
    && parseFloat(h.arm.radius) === 7,
    JSON.stringify(h && h.arm));
  ok('L4c 热区不回退（把手命中盒 ≥44×44，HIG 2.5.5）',
    !!h && h.hit && h.hit.w >= 44 && h.hit.h >= 44,
    JSON.stringify(h && h.hit && { w: h.hit.w, h: h.hit.h }));
  const cssSrc = readFileSync(join(WEB_DIR, 'css/design-system.css'), 'utf8');
  ok('L4d 形态单源面零 .ds-resize-grip 选择器残留（源码面）',
    !/^\s*\.ds-resize-grip\s*(,|\{)/m.test(cssSrc) && !/\.ds-resize-grip\s+i\s*\{/.test(cssSrc),
    `askCss hits=${(cssSrc.match(/\.ds-resize-grip\s*\{/g) || []).length}`);

  // ── L5 件三：顶部渐隐短距离完成 ──
  const fadePx = parseFloat(d.fadeH);
  // 首个**不透明**停点 = 「全实」那条线（第 0 停点必然是 transparent 0）。
  const opaqueStops = (d.maskStops || []).filter((s) => !/rgba?\(0, 0, 0, 0\)|transparent/i.test(s.color));
  const firstOpaque = opaqueStops[0];
  const colPadTop = parseFloat(d.colPadTop);
  const padBottom = parseFloat(d.colPadBottom);
  ok('L5a --ask-fade-h 短档：≤16px 且 = 12px（桌面档，规范值）',
    fadePx === 12, `fadeH=${d.fadeH}`);
  ok('L5b 渐隐载体 = 逐泡 opacity（.ask-col 零 mask-image；毛玻璃批 2026-11-06：mask 是 backdrop root，会架空气泡 backdrop-filter = 半透明假玻璃）',
    String(d.colMask || 'none') === 'none' && fadePx === 12,
    JSON.stringify({ mask: String(d.colMask).slice(0, 60), fadeH: d.fadeH }));
  ok('L5c 渐隐只占列顶那一条、不吃正文可读区（列 padding-top ≥ fade；静态首条气泡顶 ≥ 列顶 + fade）',
    colPadTop >= fadePx && !!d.aiBub && !!d.col && d.aiBub.top >= d.col.top + fadePx - 0.5,
    JSON.stringify({ padTop: d.colPadTop, fadeH: d.fadeH, bubTop: d.aiBub && d.aiBub.top, colTop: d.col && d.col.top }));
  ok('L5d 底缘收口锚在列 padding（padding-bottom = --ask-under + --ds-space-1h；毛玻璃批 2026-11-06 起 mask 停点 → padding 净空）',
    Math.abs(padBottom - (parseFloat(d.under) + parseFloat(d.rowGap))) <= 0.6,
    JSON.stringify({ padBottom: d.colPadBottom, under: d.under, rowGap: d.rowGap }));

  // ── L6 件四：五档缩放无空白（毛玻璃批 2026-11-06 判据换代）──────────────
  // 读法：① 列盒底仍钉输入条垂直中线（穿入批几何不变）：输入条顶边 − 列底边
  //       = −--ask-under（≈ −29）；
  //       ② 内容净空：输入条顶边 − 最新气泡底边 = row-gap（--ds-space-1h = 6px）
  //       ⇒ 最新气泡完全在聊天框之上（作者 2026-11-06 令②「可以拉动到聊天框之上」；
  //       改前气泡底边扎进输入条 17px 必红）。两式都只由 token 决定 ⇒ 与内容多少、
  //       窗口尺寸彻底解耦（改前：208px 空白）。
  const TOL = 0.6;
  const measureTier = async (label) => {
    const g = await read(page);
    const rowGap = parseFloat(g.rowGap) || 0;
    const under = parseFloat(g.under) || 0;
    const r = {
      tier: label, vp: g.vp,
      dockH: g.dock && +g.dock.h.toFixed(1),
      colH: g.col && +g.col.h.toFixed(1),
      rowGap, under, tail: g.tail, fadeH: g.fadeH, colMask: String(g.colMask || 'none'),
      inputToColBottom: g.input && g.col ? +(g.input.top - g.col.bottom).toFixed(2) : null,
      inputToLastBub: g.input && g.lastBub ? +(g.input.top - g.lastBub.bottom).toFixed(2) : null,
      legacy: g.legacy,
    };
    r.gapOk = Math.abs(r.inputToColBottom - -under) <= TOL;
    r.blankOk = Math.abs(r.inputToLastBub - rowGap) <= TOL;
    r.maskGone = r.colMask === 'none';
    return r;
  };
  const tiers = [];
  for (const vp of [{ width: 1920, height: 1080 }, { width: 1440, height: 900 }, { width: 1024, height: 900 }]) {
    await page.setViewportSize(vp);
    await sleep(620);
    tiers.push(await measureTier(`desktop-${vp.width}`));
    await shot(page, `resize-${vp.width}x${vp.height}-dark-${PHASE}`);
  }
  writeFileSync(join(OUT, 'tiers-desktop.json'), JSON.stringify({ phase: PHASE, tiers }, null, 2));
  ok('L6a 桌面三档（1920 / 1440 / 1024）：列盒底仍钉输入条垂直中线（穿入批几何不变，输入条顶边 − 列底边 = −--ask-under）',
    tiers.length === 3 && tiers.every((t) => t.gapOk),
    JSON.stringify(tiers.map((t) => ({ tier: t.tier, gap: t.inputToColBottom, under: t.under }))));
  ok('L6b 桌面三档：输入条顶边 − 最新气泡底边 恒 = row-gap（6px，最新气泡完全在聊天框之上；改前 −17 扎进输入条必红）',
    tiers.length === 3 && tiers.every((t) => t.blankOk),
    JSON.stringify(tiers.map((t) => ({ tier: t.tier, gap: t.inputToLastBub, expect: t.rowGap }))));
  ok('L6c 缓存变量零残留（--ask-trunc-h / --ask-trunc-from / --ask-col-overhang 三档全空 + dock 无内联尺寸样式）',
    tiers.every((t) => !!t.legacy && !t.legacy.truncH && !t.legacy.truncFrom && !t.legacy.overhang && !/--ask-(trunc|col-overhang)/.test(t.legacy.inlineStyle)),
    JSON.stringify(tiers.map((t) => t.legacy)));
  ok('L6e 列内无整段透明带（毛玻璃批 2026-11-06：旧「mask 归零线」载体退役 ⇒ 判据换代为列零 mask、内容直达 padding 上沿）',
    tiers.every((t) => t.maskGone),
    JSON.stringify(tiers.map((t) => ({ tier: t.tier, colH: t.colH, colMask: t.colMask }))));
  const jsSrc = readFileSync(join(WEB_DIR, 'js/askDock.js'), 'utf8');
  // 剥掉注释再扫：退役说明里必然出现函数名，判据要的是**调用点**归零。
  const jsCode = jsSrc.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
  ok('L6d askDock.js 代码面零 syncTruncLine 定义/调用、零截断变量写入（状态机整体退役，不是刷新得更勤）',
    !/function\s+syncTruncLine/.test(jsCode) && !/syncTruncLine\s*\(/.test(jsCode)
    && !/--ask-trunc/.test(jsCode) && !/--ask-col-overhang/.test(jsCode),
    JSON.stringify({
      decl: /function\s+syncTruncLine/.test(jsCode),
      calls: (jsCode.match(/syncTruncLine\s*\(/g) || []).length,
      truncVar: /--ask-trunc/.test(jsCode),
    }));
  await page.context().close();

  // ── 手机腿（375 / 768 两档）：同两条几何判据 + 短渐隐同档 ──
  const mob = await boot(browser, { viewport: { width: 390, height: 844 }, colorScheme: 'dark', mobile: true });
  try {
    const mabs = await openFile(mob.page, '可读性取证-手机.md', DOC, { state: 'attached' });
    expectTranscript(sidOf(mabs), [
      { role: 'user', text: '这份文档里明确没做的有哪几条？' },
      { role: 'assistant', text: '明确没做的（§8）：\n\n- 10px 值本身未收敛（358 处布局位移，需单独一批）；\n- 组合四值圆角 12 处、负间距 21+ 处未动；\n- 14px 间距 109 处（定版明载不是档）。' },
    ]);
    await openDock(mob.page, true);
    await sleep(900);
    const mtiers = [];
    for (const vp of [{ width: 375, height: 812 }, { width: 768, height: 800 }]) {
      await mob.page.setViewportSize(vp);
      await sleep(700);
      const g = await read(mob.page);
      const rowGap = parseFloat(g.rowGap) || 0;
      const under = parseFloat(g.under) || 0;
      const t = {
        tier: `mobile-${vp.width}`, vp: g.vp, visible: !!(g.dock && g.dock.w > 0),
        colH: g.col && +g.col.h.toFixed(1), rowGap, under, tail: g.tail, fadeH: g.fadeH,
        colMask: String(g.colMask || 'none'),
        inputToColBottom: g.input && g.col ? +(g.input.top - g.col.bottom).toFixed(2) : null,
        inputToLastBub: g.input && g.lastBub ? +(g.input.top - g.lastBub.bottom).toFixed(2) : null,
        legacy: g.legacy,
      };
      t.gapOk = Math.abs(t.inputToColBottom - -under) <= TOL;
      t.blankOk = Math.abs(t.inputToLastBub - rowGap) <= TOL;
      t.maskGone = t.colMask === 'none';
      mtiers.push(t);
      await shot(mob.page, `resize-${vp.width}x${vp.height}-mobile-dark-${PHASE}`);
    }
    writeFileSync(join(OUT, 'tiers-mobile.json'), JSON.stringify({ phase: PHASE, mtiers }, null, 2));
    ok('L6h 手机两档（375 / 768）：列盒底钉输入条中线 + 内容净空 = row-gap（最新气泡完全在聊天框之上）',
      mtiers.length === 2 && mtiers.every((t) => t.visible && t.gapOk && t.blankOk),
      JSON.stringify(mtiers.map((t) => ({ tier: t.tier, vis: t.visible, gap: t.inputToColBottom, under: t.under, bub: t.inputToLastBub }))));
    ok('L6i 手机两档列零 mask（毛玻璃批载体换代，无归零线概念）',
      mtiers.every((t) => t.maskGone),
      JSON.stringify(mtiers.map((t) => ({ tier: t.tier, colH: t.colH, colMask: t.colMask }))));
    ok('L6f 手机档 --ask-fade-h 同为 12px（短渐隐不按腿分叉）',
      mtiers.every((t) => parseFloat(t.fadeH) === 12),
      JSON.stringify(mtiers.map((t) => t.fadeH)));
    ok('L6g 手机两档缓存变量同为零残留',
      mtiers.every((t) => !!t.legacy && !t.legacy.truncH && !t.legacy.truncFrom && !t.legacy.overhang),
      JSON.stringify(mtiers.map((t) => t.legacy)));
  } finally {
    await mob.ctx.close();
  }

  // ── 亮档腿（1440×900 light）：同一组四件读数 + 截图（双档取证）──
  const lit = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  try {
    const labs = await openFile(lit.page, '可读性取证-亮.md', DOC);
    expectTranscript(sidOf(labs), [
      { role: 'user', text: '这份文档里明确没做的有哪几条？' },
      { role: 'assistant', text: '明确没做的（§8）：\n\n- 10px 值本身未收敛（358 处布局位移，需单独一批）；\n- 组合四值圆角 12 处、负间距 21+ 处未动。' },
    ]);
    await openDock(lit.page, false);
    await sleep(900);
    const ld = await read(lit.page);
    const lct = await contrast(lit.page);
    writeFileSync(join(OUT, 'readings-1440-light.json'), JSON.stringify({ phase: PHASE, ld, lct }, null, 2));
    const alphaOf2 = (bgStr) => {
      const m = String(bgStr).match(/rgba?\(([^)]+)\)/);
      if (!m) return null;
      const p = m[1].split(',').map((x) => parseFloat(x.trim()));
      return p.length > 3 ? p[3] : 1;
    };
    ok('L7 亮档：三面 α = 1 且对比度 ≥ 4.5:1（双档同一律）',
      alphaOf2(ld.matBubAi.bg) === 1 && alphaOf2(ld.matInput.bg) === 1
      && !!lct.bub && lct.bub.ratio >= 4.5 && !!lct.input && lct.input.ratio >= 4.5,
      JSON.stringify({ bub: ld.matBubAi.bg, input: ld.matInput.bg, bubRatio: lct.bub && lct.bub.ratio, inputRatio: lct.input && lct.input.ratio }));
    await installInkProbe(lit.page);
    const ldiffs = await pixelDiff(lit.page, [ld.aiBub, ld.input].filter(Boolean), `1440-light-${PHASE}`);
    writeFileSync(join(OUT, 'pixel-diff-1440-light.json'), JSON.stringify({ phase: PHASE, ldiffs }, null, 2));
    ok('L8 亮档：面板内部像素与背后内容无关（同 L3 判据）',
      ldiffs.every((x) => x.over === 0 && x.maxDiff <= 2),
      JSON.stringify(ldiffs.map((x) => ({ maxDiff: x.maxDiff, over: x.over }))));
    await shot(lit.page, `dock-1440-light-${PHASE}`);
    await lit.page.evaluate(() => { const el = document.getElementById('ask-ink-probe'); if (el) el.remove(); });
  } finally {
    await lit.ctx.close();
  }
} finally {
  summary.pass = pass; summary.fail = fail; summary.results = results;
  writeFileSync(join(OUT, 'results.json'), JSON.stringify(summary, null, 2));
  // eslint-disable-next-line no-console
  console.log(`\n${PHASE}: PASS=${pass} FAIL=${fail}`);
  await browser.close();
  server.close();
}
if (fail > 0) process.exitCode = 1;
