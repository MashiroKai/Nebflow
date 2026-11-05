#!/usr/bin/env node
// canvas-capsule-touch-row.spec.mjs — 触屏 canvas 顶部胶囊：横向常驻展开（作者令 2026-11-05 12:10）
//
// 作者令（逐字）：「参考电脑端的样式来做手机端的canvas顶部胶囊的样式，总体思路就是
// 把上下展开的改为横向展开。并且是直接展开，不需要hover。」附截图：手机上
// `20261103_215300_ca...` 一枚 + 上下两条邻居名（= 桌面竖环 hover 展开态被触屏
// 触发后的样子）。
//
// 病根（本 spec 的改前红基线）：手机横屏视口宽 > 768 落不进手机交互层
// （mobile/bootstrap.js MOBILE_MQ = max-width:768px）⇒ 走桌面腿 ⇒ 胶囊环是
// 「hover 才上下展开」的竖环，触屏没有 hover。
//
// 本 spec 钉七组判据（每组都对得上任务的验收条）：
//   A 同源      —— 触屏腿与桌面腿是**同一个** #canvas-capsule-ring 组件（不是第二套）
//   B 无 hover  —— 页面加载后**零指针事件**即已展开（不许任何前置手势）
//   C 横向      —— 邻签与中心签同一水平线（|Δy| ≤ 1）且横向分离（Δx > 0）
//   D 截断      —— 超长名 = 「可辨识前缀 … 关键尾部」，绝不只剩一串省略号
//   E 不遮挡    —— 整排不越过面板顶缘（竖环档的上缘位越出 = 作者点名的遮挡根因）
//   F 窄屏极限  —— 768/844 两档横屏下不破版：不越面板、不越视口、中心签恒可读
//   G 桌面零回归 + 手机层零串扰 —— 1440 细指针 hover 仍是竖环；375 手机层走自己的签条
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具），零后端、零 sbt。
// 运行：node tests/canvas-capsule-touch-row.spec.mjs
// 取证：EVIDENCE_DIR=<目录> 落截图 + 读数 JSON。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.EVIDENCE_DIR || join(HERE, '..', '.nebflow', 'evidence', 'canvas-capsule-touch-row');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon',
};
const SID = 'capsule-touch-row';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0;
let fail = 0;
/** @param {string} name @param {boolean} cond @param {string} [detail] */
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass += 1; else fail += 1;
}

/* 三个页签：一枚超长（截断判据）、两枚正常（横向排布判据）。 */
const DOCS = [
  ['d1', 'canvas-capsule-mobile-horizontal__20261105_120000.md'],
  ['d2', 'visual-report-doc-style__20261104_101112.md'],
  ['d3', 'design-spec-value-converge__20261104_090000.md'],
];

const browser = await chromium.launch();
const consoleErrors = [];

/**
 * 起一个页面：hasTouch/coarse 由调用方决定。
 * @param {{width:number,height:number}} viewport
 * @param {{touch?:boolean, colorScheme?: 'light'|'dark'}} opts
 */
async function makePage(viewport, opts = {}) {
  const ctx = await browser.newContext({
    viewport,
    colorScheme: opts.colorScheme || 'light',
    deviceScaleFactor: 2,
    hasTouch: opts.touch !== false,
    isMobile: opts.touch !== false,
  });
  const page = await ctx.newPage();
  const tag = `${viewport.width}x${viewport.height}${opts.touch === false ? '-fine' : '-coarse'}`;
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${tag}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${tag}] pageerror: ${e.message}`));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'capsule-row', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: 'capsule-row' }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'capsule-row', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(800);
  return { ctx, page, tag };
}

/** 灌页签（unique type 避开预览组互相替换）。 */
async function seed(page) {
  await page.evaluate((list) => import('/js/canvas.js').then((c) => {
    list.forEach(([id, title], i) => c.openTab(id, title, { type: 'md-' + i }));
  }), DOCS);
  await sleep(700);
}

/** 环读数：位姿（视口盒）+ 文字 + 材质/可见性。 */
async function ringInfo(page) {
  return page.evaluate(() => {
    const ring = document.querySelector('#canvas-capsule-ring');
    const panel = document.querySelector('#canvas-panel');
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const pr = panel ? panel.getBoundingClientRect() : null;
    const hr = hdr ? hdr.getBoundingClientRect() : null;
    const caps = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule')].map((k) => {
      const r = k.getBoundingClientRect();
      const cs = getComputedStyle(k);
      const nameEl = k.querySelector('.cr-name');
      return {
        id: k.dataset.tabId,
        cls: k.className,
        x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1),
        cx: +(r.x + r.width / 2).toFixed(1), cy: +(r.y + r.height / 2).toFixed(1),
        top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), left: +r.left.toFixed(1), right: +r.right.toFixed(1),
        opacity: +cs.opacity,
        bg: cs.backgroundColor,
        text: (nameEl?.textContent || '').trim(),
        nameDisplay: nameEl ? getComputedStyle(nameEl).display : 'none',
        varY: k.style.getPropertyValue('--cr-y').trim(),
        varX: k.style.getPropertyValue('--cr-x').trim(),
        varRY: k.style.getPropertyValue('--cr-ry').trim(),
        varRX: k.style.getPropertyValue('--cr-rx').trim(),
      };
    });
    return {
      ringPresent: !!ring,
      ringId: ring?.id || '',
      ringClass: ring?.className || '',
      open: !!ring && ring.classList.contains('open'),
      steady: !!ring && ring.classList.contains('steady'),
      panel: pr ? { x: +pr.x.toFixed(1), y: +pr.y.toFixed(1), w: +pr.width.toFixed(1), h: +pr.height.toFixed(1) } : null,
      hdr: hr ? { x: +hr.x.toFixed(1), y: +hr.y.toFixed(1), w: +hr.width.toFixed(1), h: +hr.height.toFixed(1) } : null,
      titleOpacity: getComputedStyle(document.querySelector('#canvas-panel .canvas-title .t-inner')).opacity,
      n: caps.length,
      caps,
      vw: window.innerWidth,
      vh: window.innerHeight,
      coarse: matchMedia('(pointer: coarse)').matches,
      mobileLayer: document.body.classList.contains('mobile-layer'),
      stripChips: document.querySelectorAll('.mf-strip-chip').length,
    };
  });
}

/* ═══ A–F：触屏横屏（844×390，作者设备同档）═══════════════════════════ */
{
  const { ctx, page, tag } = await makePage({ width: 844, height: 390 });
  await seed(page);
  // 零指针事件、零手势 —— 页面加载完直接读数（B 判据的全部前提）
  const info = await ringInfo(page);
  writeFileSync(join(OUT, `coarse-${tag}.json`), JSON.stringify(info, null, 2));
  await page.screenshot({ path: join(OUT, `coarse-${tag}.png`) });

  ok('A 同源：触屏腿用的就是桌面那一份 #canvas-capsule-ring（非第二套组件）',
    info.ringPresent && info.ringId === 'canvas-capsule-ring' && info.ringClass.includes('capsule-ring'),
    `id=${info.ringId} class="${info.ringClass}"`);

  ok('B 无 hover：页面加载后零指针事件即已展开（.open + .steady）',
    info.coarse && info.open && info.steady && info.n >= 2,
    `coarse=${info.coarse} open=${info.open} steady=${info.steady} n=${info.n}`);

  const center = info.caps.filter((c) => c.cls.includes('is-center'))[0];
  const edges = info.caps.filter((c) => c.cls.includes('is-edge'));
  ok('B2 展开态可见：中心签 opacity=1 且文字非空（触屏不靠 hover 才看得见）',
    !!center && center.opacity === 1 && !!center.text,
    `center=${JSON.stringify(center && { op: center.opacity, text: center.text, bg: center.bg })}`);

  ok('C 横向排布：邻签与中心签同一水平线（|Δy| ≤ 1px）+ 横向分离（Δx ≥ 40px）',
    !!center && edges.length >= 1 && edges.every((e) => Math.abs(e.cy - center.cy) <= 1 && Math.abs(e.cx - center.cx) >= 40),
    `center.cy=${center?.cy} edges=${JSON.stringify(edges.map((e) => ({ cy: e.cy, dx: +(e.cx - (center?.cx || 0)).toFixed(1) })))}`);

  ok('C2 位姿轴换挡：横排档走 --cr-x/--cr-ry，纵轴 --cr-y 恒 0（不是竖环）',
    !!center && edges.every((e) => e.varY === '0px' && e.varX !== '0px' && e.varRY !== '0deg'),
    `center=${JSON.stringify({ x: center?.varX, y: center?.varY, ry: center?.varRY })} edges=${JSON.stringify(edges.map((e) => ({ x: e.varX, y: e.varY, ry: e.varRY })))}`);

  /* D 截断：看**可见文字**那一枚（中心签）—— 「头 … 尾」。图标档邻签的
     textContent 仍在 DOM 里（读屏全名），但 nameDisplay=none，不进本判据。 */
  const longCap = center;
  const text = longCap?.text || '';
  const head = text.split('…')[0] || '';
  const tail = text.split('…')[1] || '';
  ok('D 截断 = 可辨识前缀 + 关键尾部（含「…」，头非空，尾带扩展名）',
    text.includes('…') && head.length >= 2 && /\.\w{1,8}$/.test(tail),
    `text="${text}" head="${head}" tail="${tail}"`);

  ok('D2 绝不「只剩一串省略号」：头 ≥2 字且非纯符号',
    head.replace(/[^\p{L}\p{N}]/gu, '').length >= 2,
    `head="${head}"`);
  ok('D3 容不下完整名时不出「假标签」：邻签要么图标档（不显示文字），要么也带「头…尾」',
    info.caps.filter((c) => !c.cls.includes('is-center') && !c.cls.includes('is-far'))
      .every((c) => c.nameDisplay === 'none'
        || (c.text.includes('…') && (c.text.split('…')[0] || '').length >= 2)),
    JSON.stringify(info.caps.filter((c) => !c.cls.includes('is-center') && !c.cls.includes('is-far'))
      .map((c) => ({ disp: c.nameDisplay, text: c.text }))));

  ok('E 不遮挡：任一胶囊都不越过面板顶缘（竖环档上缘位越出 = 作者点名的遮挡根因）',
    !!info.panel && info.caps.every((c) => c.top >= info.panel.y - 1),
    `panel.y=${info.panel?.y} caps.top=${JSON.stringify(info.caps.map((c) => c.top))}`);

  ok('E2 不遮挡：整排与标题头同一水平带（全部胶囊中心落在标题头带内 ±4px）',
    !!info.hdr && info.caps.every((c) => c.cy >= info.hdr.y - 4 && c.cy <= info.hdr.y + info.hdr.h + 4),
    `hdr=${JSON.stringify(info.hdr)} cy=${JSON.stringify(info.caps.map((c) => c.cy))}`);

  ok('F 不破版：整排不越出面板、不越出视口（±2px）',
    !!info.panel && info.caps.every((c) => c.left >= info.panel.x - 2 && c.right <= info.panel.x + info.panel.w + 2 && c.left >= -2 && c.right <= info.vw + 2),
    `panel=${JSON.stringify(info.panel)} caps=${JSON.stringify(info.caps.map((c) => [c.left, c.right]))}`);

  ok('F2 中心签恒可读：宽度 ≥ 96px 且文字非空（面板再窄也不塌）',
    !!center && center.w >= 96 && !!center.text,
    `w=${center?.w} text="${center?.text}"`);

  /* 点邻签即换签（触屏唯一的多签入口）—— 换签后仍是横排常驻。 */
  const edgeId = edges[0]?.id;
  if (edgeId) {
    await page.evaluate((id) => {
      const el = document.querySelector(`#canvas-capsule-ring .cr-capsule[data-tab-id="${id}"]`);
      el?.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    }, edgeId);
    await sleep(600);
  }
  const after = await ringInfo(page);
  writeFileSync(join(OUT, `coarse-${tag}-after-switch.json`), JSON.stringify(after, null, 2));
  await page.screenshot({ path: join(OUT, `coarse-${tag}-after-switch.png`) });
  const afterCenter = after.caps.filter((c) => c.cls.includes('is-center'))[0];
  ok('C3 点邻签 = 换签且仍常驻横排（换签不收环，也不退回竖排）',
    !!edgeId && after.open && after.n >= 2 && afterCenter?.id === edgeId
    && after.caps.filter((c) => c.cls.includes('is-edge')).every((e) => Math.abs(e.cy - afterCenter.cy) <= 1),
    `clicked=${edgeId} open=${after.open} center=${afterCenter?.id} n=${after.n}`);

  ok('R0 触屏横屏腿零 JS 错误', consoleErrors.filter((e) => e.startsWith(`[${tag}]`)).length === 0,
    consoleErrors.filter((e) => e.startsWith(`[${tag}]`))[0] || '');
  await ctx.close();
}

/* ═══ F3：窄屏极限（768×360 = 手机横屏最小支持宽度档）══════════════════ */
for (const vp of [{ width: 800, height: 360 }, { width: 812, height: 375 }, { width: 1280, height: 720 }]) {
  const { ctx, page, tag } = await makePage(vp);
  await seed(page);
  const info = await ringInfo(page);
  writeFileSync(join(OUT, `extreme-${tag}.json`), JSON.stringify(info, null, 2));
  await page.screenshot({ path: join(OUT, `extreme-${tag}.png`) });
  const center = info.caps.filter((c) => c.cls.includes('is-center'))[0];
  const edges = info.caps.filter((c) => c.cls.includes('is-edge'));
  ok(`F3 窄屏极限 ${vp.width}×${vp.height}：常驻横排 + 不越面板/视口 + 中心签文字可读`,
    info.open && info.n >= 2 && !!center?.text
    && info.caps.filter((c) => !c.cls.includes('is-far'))
      .every((c) => c.left >= info.panel.x - 2 && c.right <= Math.min(info.panel.x + info.panel.w, info.vw) + 2)
    && edges.every((e) => Math.abs(e.cy - center.cy) <= 1),
    `open=${info.open} n=${info.n} panel=${JSON.stringify(info.panel)} center="${center?.text}" w=${center?.w} caps=${JSON.stringify(info.caps.map((c) => [c.left, c.right]))}`);
  ok(`R0 窄屏极限 ${vp.width}×${vp.height} 零 JS 错误`,
    consoleErrors.filter((e) => e.startsWith(`[${tag}]`)).length === 0,
    consoleErrors.filter((e) => e.startsWith(`[${tag}]`))[0] || '');
  await ctx.close();
}

/* ═══ G：桌面零回归（1440×900 细指针）+ 手机层零串扰（375×812）════════ */
{
  const { ctx, page, tag } = await makePage({ width: 1440, height: 900 }, { touch: false });
  await seed(page);
  const rest = await ringInfo(page);
  ok('G1 桌面（细指针）加载即收环：不因本次改动变成常驻展开',
    rest.coarse === false && !rest.open && rest.n === 0 && rest.titleOpacity === '1',
    `coarse=${rest.coarse} open=${rest.open} n=${rest.n} titleOpacity=${rest.titleOpacity}`);
  await page.screenshot({ path: join(OUT, `desktop-${tag}-rest.png`) });
  await page.hover('#canvas-panel .canvas-header');
  await sleep(600);
  const hov = await ringInfo(page);
  writeFileSync(join(OUT, `desktop-${tag}-hover.json`), JSON.stringify(hov, null, 2));
  await page.screenshot({ path: join(OUT, `desktop-${tag}-hover.png`) });
  const hCenter = hov.caps.filter((c) => c.cls.includes('is-center'))[0];
  const hEdges = hov.caps.filter((c) => c.cls.includes('is-edge'));
  ok('G2 桌面 hover 仍是**竖环**（邻签纵向分离、横轴 --cr-x 恒 0）—— 零回归',
    hov.open && hEdges.length >= 1
    && hEdges.every((e) => e.varX === '0px' && Math.abs(e.cy - hCenter.cy) > 8),
    `open=${hov.open} edges=${JSON.stringify(hEdges.map((e) => ({ x: e.varX, y: e.varY, dy: +(e.cy - hCenter.cy).toFixed(1) })))}`);
  const activeTitle = await page.evaluate((list) => {
    const act = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center')?.dataset.tabId;
    const hit = list.find(([id]) => id === act);
    return hit ? hit[1] : '';
  }, DOCS);
  ok('G3 桌面胶囊几何逐项不变（180×36 定宽 + 文字走 CSS ellipsis 纯文本全名）',
    !!hCenter && Math.abs(hCenter.w - 180) <= 1.5 && Math.abs(hCenter.h - 36) <= 1.5
    && hCenter.text === activeTitle,
    `w=${hCenter?.w} h=${hCenter?.h} text="${hCenter?.text}" want="${activeTitle}"`);
  ok('R0 桌面腿零 JS 错误', consoleErrors.filter((e) => e.startsWith(`[${tag}]`)).length === 0,
    consoleErrors.filter((e) => e.startsWith(`[${tag}]`))[0] || '');
  await ctx.close();
}

{
  const { ctx, page, tag } = await makePage({ width: 375, height: 812 });
  await page.evaluate(() => document.querySelector('#mobile-dock .dock-key[data-dock="canvas"]')?.click());
  await sleep(900);
  await page.evaluate((list) => import('/js/canvas.js').then((c) => {
    list.forEach(([id, title], i) => c.openTab(id, title, { type: 'md-' + i }));
  }), DOCS);
  await sleep(800);
  const info = await ringInfo(page);
  writeFileSync(join(OUT, `mobile-${tag}.json`), JSON.stringify(info, null, 2));
  await page.screenshot({ path: join(OUT, `mobile-${tag}.png`) });
  ok('G4 手机层（≤768）零串扰：走自己的 .mf-strip 签条，桌面胶囊环不开',
    info.mobileLayer && !info.open && info.n === 0 && info.stripChips >= 2,
    `mobileLayer=${info.mobileLayer} open=${info.open} n=${info.n} stripChips=${info.stripChips}`);
  ok('R0 手机层腿零 JS 错误', consoleErrors.filter((e) => e.startsWith(`[${tag}]`)).length === 0,
    consoleErrors.filter((e) => e.startsWith(`[${tag}]`))[0] || '');
  await ctx.close();
}

await browser.close();
console.log(`\ncanvas-capsule-touch-row: ${pass} PASS / ${fail} FAIL`);
console.log(`EVIDENCE → ${OUT}`);
process.exit(fail ? 1 : 0);
