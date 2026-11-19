#!/usr/bin/env node
// tests/canvas-tab-handover.spec.mjs — 手机层 canvas 页「内容区恒非空」闸 + 负控
// （作者令 2026-11-19：真机实测到的空白面按真缺陷修，并加闸）。
//
// 缺陷（真实例实测，非推断）：手机上点「全部接受」收口审阅页签时，
// `canvas.js closeTab` 把**邻位签**激活，而邻位可能是**面板签**（`plugins` 这类
// `PANEL_TAB_IDS`）⇒ `navController.syncCanvasPage` 里 `activeDocId()` 为 null
// ⇒ `adoptActiveDoc()` 无 pane 可收编（`.mf-cv-host` 子节点 0），而 `.mf-cv-empty`
// 又因 `docTabs().length > 0` 保持 hidden ⇒ canvas 页整片空白。真机读数：
// `activeTabId=plugins`、`hostKids=0`、`#canvas-panel display:none`、
// 文件 pane `display:none w=0 h=0` —— **不是**任何加载/品牌态（页面里没有 splash
// 元素，是纯空白），所以「等 splash 超时」不会自愈。
//
// 修法（唯一一处）：`navController.syncCanvasPage` 开头加「内容区非空不变量」——
// 在列文档签非空 + 活动签不是文档签 + 本站于 canvas 页 ⇒ 切回最近一张文档签。
//
// 判据（H 系列；每条都读**真装置**的状态，不读源码文本）：
//   H1 正腿：活动文档签关掉、邻位是面板签 ⇒ N 秒内内容区**非空**（可见 pane ≥1、
//      文本非空、内容区中心点命中的元素在可见 pane 内）+ 活动签是文档签 +
//      自愈事件在场。
//   H2 负控：把本修复中和（`if (false)` 替身，等价「撤掉自愈」）⇒ H1 的同一判据
//      **必红**（空白面复现）。负控不红 ⇒ 闸自己红（判据对该缺陷不敏感）。
//   H3 零回归：邻位是**文档签** ⇒ 仍按 `closeTab` 既有邻位语义落到那张文档签
//      （自愈**不得**插手；事件计数 0）。
//   H4 零回归：不在 canvas 页（消息页前台）时面板签为活动签 ⇒ **不静默换签**
//      （桌面类语义零改）；随后进 canvas 页 ⇒ 自愈生效、内容区非空。
//
// 装置：scripts/lib/visual-harness.mjs（零端口零进程，`page.route` 从**真前端源码树**
// 投送 web/，MockWS 走真实 ws.js 分发；结构上碰不到宿主实例 :6138）。场景用**真
// 产品路径**搭建：`workspace-open-item` → canvas.js `openTab` → `nav.dockTo('canvas')`
// → `closeTab` —— 不是手搓 DOM，故「邻位落到面板签」这个位形与真机同源。
//
// 用法：node tests/canvas-tab-handover.spec.mjs [outDir]
// 退出码：0 = 全绿；1 = 有 FAIL；2 = 装置错误。
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, webRoot } from '../scripts/lib/visual-harness.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const OUT = process.argv[2] && !process.argv[2].startsWith('--')
  ? process.argv[2] : join(ROOT, 'evidence', 'canvas-tab-handover');
mkdirSync(OUT, { recursive: true });

const rows = [];
let pass = 0, fail = 0;
const record = (id, name, ok, readout) => {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass += 1; else fail += 1;
};

// 自愈闸的那一行源码锚（负控以「换成 if (false)」中和它 —— 只中和**本修复**，
// 其余字节逐字不动；「真删源码直跑」是同一实验的手工版，读数见 evidence 记录）。
const FIX_ANCHOR = "if (docs.length && !activeId && stack[stack.length - 1] === 'canvas') {";
const PANEL_IDS = ['plugins', 'projects', 'wiki-graph'];

/** 内容区读数（判据面）：可见 pane / 文本 / 中心点命中 / 空态 / 活动签 / 自愈事件。 */
const CONTENT_PROBE = `(() => {
  const box = (el) => { const r = el.getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
  const host = document.querySelector('.mf-cv-host');
  const pg = document.querySelector('.mobile-page[data-page="canvas"]');
  const panes = host ? [...host.querySelectorAll(':scope > .canvas-tab-pane')] : [];
  const visible = panes.filter((p) => { const b = box(p); return b.w > 0 && b.h > 0 && getComputedStyle(p).display !== 'none'; });
  const empty = document.querySelector('.mf-cv-empty');
  const emptyShown = !!(empty && !empty.hidden && box(empty).h > 0);
  const text = visible.map((p) => (p.textContent || '').replace(/\\s+/g, ' ').trim()).join(' ');
  const hr = host ? host.getBoundingClientRect() : null;
  const hit = (hr && hr.width > 0 && hr.height > 0)
    ? document.elementFromPoint(hr.x + hr.width / 2, hr.y + hr.height / 2) : null;
  const hitInside = !!(hit && visible.some((p) => p === hit || p.contains(hit)));
  const activeTab = document.querySelector('#canvas-tab-bar .canvas-tab.active');
  const events = (window.__nfMobileLayerEvents || []).map((e) => (e && e.kind) || '');
  const label = (el) => el ? el.tagName.toLowerCase() + '.' + String(el.className).trim().split(/\\s+/).slice(0, 2).join('.') : null;
  return {
    pageDisplay: pg ? getComputedStyle(pg).display : null,
    hostKids: host ? host.children.length : null,
    hostBox: host ? box(host) : null,
    panes: panes.map((p) => ({ id: p.dataset.tabId, active: p.classList.contains('active'), box: box(p), kids: p.children.length })),
    visiblePanes: visible.length,
    visibleIds: visible.map((p) => p.dataset.tabId),
    textLen: text.length,
    textHead: text.slice(0, 56),
    emptyShown,
    centreHitInsidePane: hitInside,
    centreHit: label(hit),
    activeTabId: activeTab ? activeTab.dataset.tabId : null,
    selfHealEvents: events.filter((t) => t === 'canvas-page-selfheal').length,
    // 空白面定义：可见 pane 零张 **且** 空态也没显示（= 什么都没有，用户的「一片空白」）
    blank: visible.length === 0 && !emptyShown,
  };
})()`;

/** 场景：三张文档卡（两张在前 + 一张活动）、一张面板签夹在中间（真机位形）。 */
const OPEN_SCENE = `(async () => {
  const md = (n) => '# ' + n + '\\n\\n' + ('readable line for ' + n + '\\n\\n').repeat(40);
  const items = [
    { id: 'file:/tmp/handover-a.md', itemType: 'markdown', title: 'handover-a.md', content: md('a'), absPath: '/tmp/handover-a.md', pinned: true },
    { id: 'file:/tmp/handover-c.md', itemType: 'markdown', title: 'handover-c.md', content: md('c'), absPath: '/tmp/handover-c.md', pinned: true },
  ];
  for (const d of items) window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...d, size: (d.content || '').length } }));
  const canvas = await import('/js/canvas.js');
  canvas.openTab('askreview:handover', 'notes.md', { type: 'askreview', pinned: true });
  // 面板签（PANEL_TAB_IDS 成员）开在**活动签之后** —— 关掉活动文档签时，closeTab
  // 的邻位规则（ids[idx+1] || ids[idx-1]）正好落到它身上（真机同一失效位形）。
  canvas.openTab('projects', '项目', { type: 'panel', pinned: true });
  const nav = await import('/js/mobile/navController.js');
  nav.dockTo('canvas');
  canvas.setActiveTab('askreview:handover');
  return { docs: nav.docTabs().map((d) => d.id), panes: [...document.querySelectorAll('.canvas-tab-pane')].map((p) => p.dataset.tabId) };
})()`;

/** 零回归场景：只有两张文档签（邻位 = 文档签）。 */
const OPEN_DOC_ONLY = `(async () => {
  const md = (n) => '# ' + n + '\\n\\n' + ('readable line for ' + n + '\\n\\n').repeat(40);
  for (const d of [
    { id: 'file:/tmp/handover-a.md', itemType: 'markdown', title: 'handover-a.md', content: md('a'), absPath: '/tmp/handover-a.md', pinned: true },
    { id: 'file:/tmp/handover-c.md', itemType: 'markdown', title: 'handover-c.md', content: md('c'), absPath: '/tmp/handover-c.md', pinned: true },
    { id: 'file:/tmp/handover-d.md', itemType: 'markdown', title: 'handover-d.md', content: md('d'), absPath: '/tmp/handover-d.md', pinned: true },
  ]) window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...d, size: (d.content || '').length } }));
  const canvas = await import('/js/canvas.js');
  canvas.openTab('askreview:handover', 'notes.md', { type: 'askreview', pinned: true });
  const nav = await import('/js/mobile/navController.js');
  nav.dockTo('canvas');
  canvas.setActiveTab('askreview:handover');
  return { docs: nav.docTabs().map((d) => d.id) };
})()`;

const CLOSE_ACTIVE = `(async () => {
  const canvas = await import('/js/canvas.js');
  canvas.closeTab('askreview:handover');
  return true;
})()`;

/** 轮询内容区读数（作者判据：切过去后 N 秒内内容区必须非空）。 */
async function waitContent(page, ms = 4000) {
  const end = Date.now() + ms;
  let last = null;
  while (Date.now() < end) {
    last = await page.evaluate(CONTENT_PROBE);
    if (!last.blank && last.visiblePanes > 0 && last.textLen > 0) return last;
    await settle(page, 120);
  }
  return last;
}

async function boot(browser, opts = {}) {
  const width = 375, height = 812;
  const ctx = await browser.newContext({ viewport: { width, height }, hasTouch: true });
  const session = await createSession({ newPage: (o) => ctx.newPage(o) },
    { width, height, colorScheme: opts.theme ?? 'light', baseURL: 'https://stand.test/' });
  const { page } = session;
  await page.setViewportSize({ width, height });
  await page.emulateMedia({ colorScheme: opts.theme ?? 'light', reducedMotion: 'reduce' });
  if (opts.neutralizeFix) {
    // 负控装置：在装置自身的静态路由**之后**注册（后注册先命中）—— 只把自愈闸那
    // 一行换成 `if (false)`，其余字节逐字不动（= 「撤掉本修复」的等价态）。
    const f = join(webRoot(), 'js', 'mobile', 'navController.js');
    await page.route('**/js/mobile/navController.js', (route) => {
      const src = readFileSync(f, 'utf8');
      // 锚缺席 = 源码里本就没有自愈闸（「真删源码直跑」那一轮）⇒ 原样投送（已是中和态）。
      const body = src.includes(FIX_ANCHOR) ? src.replace(FIX_ANCHOR, 'if (false) {') : src;
      route.fulfill({ status: 200, contentType: 'text/javascript', body });
    });
  }
  await session.boot();
  await page.waitForSelector('body.mobile-layer');
  return { ctx, page, session };
}

async function main() {
  const { mod } = loadPlaywright();
  const { browser, label } = await launchBrowser(mod);
  console.log(`browser: ${label}`);
  const readings = {};

  try {
    // ── H0 静态单源：自愈闸在源码里（缺席 ⇒ 后续腿无从谈起；负控也靠这条锚）──
    {
      const src = readFileSync(join(webRoot(), 'js', 'mobile', 'navController.js'), 'utf8');
      const hasGuard = src.includes(FIX_ANCHOR);
      const hasEvent = /logEvent\('canvas-page-selfheal'/.test(src);
      const beforeSig = src.indexOf(FIX_ANCHOR) < src.indexOf("const sig = docs.map((d) => d.id).join('|')");
      record('H0', '静态单源：内容区非空不变量在 syncCanvasPage 入口（含自愈事件）', hasGuard && hasEvent && beforeSig,
        `guard=${hasGuard} event=${hasEvent} before-persist=${beforeSig}`);
    }

    // ── H1 正腿 ────────────────────────────────────────────────────────────
    {
      const { ctx, page } = await boot(browser);
      const scene = await page.evaluate(OPEN_SCENE);
      await settle(page, 400);
      const before = await page.evaluate(CONTENT_PROBE);
      await page.evaluate(CLOSE_ACTIVE);
      const after = await waitContent(page, 4000);
      readings.H1 = { scene, before, after };
      const activeIsDoc = !!after.activeTabId && !PANEL_IDS.includes(after.activeTabId);
      record('H1a', '关掉活动文档签（邻位=面板签）后内容区有可见 pane', after.visiblePanes >= 1, `visiblePanes=${after.visiblePanes} visibleIds=${JSON.stringify(after.visibleIds)} hostKids=${after.hostKids}`);
      record('H1b', '内容区非空（文本非空 · 不是空白面）', !after.blank && after.textLen > 0, `blank=${after.blank} textLen=${after.textLen} head=${JSON.stringify(after.textHead)}`);
      record('H1c', '内容区中心点命中的元素在可见 pane 内（真看得见）', after.centreHitInsidePane === true, `centreHit=${after.centreHit} inside=${after.centreHitInsidePane}`);
      record('H1d', '活动签是文档签（不是面板签）', activeIsDoc, `activeTabId=${after.activeTabId}`);
      record('H1e', '自愈事件在场（canvas-page-selfheal）', after.selfHealEvents >= 1, `selfHealEvents=${after.selfHealEvents}`);
      await settle(page, 200);
      await page.screenshot({ path: join(OUT, 'handover-h1-after-375x812.png') });
      await ctx.close();
    }

    // ── H2 负控（中和本修复 ⇒ 同一判据必红）──────────────────────────────
    {
      const { ctx, page } = await boot(browser, { neutralizeFix: true });
      await page.evaluate(OPEN_SCENE);
      await settle(page, 400);
      await page.evaluate(CLOSE_ACTIVE);
      await settle(page, 1200);
      const after = await page.evaluate(CONTENT_PROBE);
      readings.H2 = { after };
      const red = after.blank === true && after.visiblePanes === 0;
      record('H2', '负控：中和修复后空白面复现（判据必红）', red, `blank=${after.blank} visiblePanes=${after.visiblePanes} emptyShown=${after.emptyShown} hostKids=${after.hostKids} activeTabId=${after.activeTabId} selfHealEvents=${after.selfHealEvents}`);
      await page.screenshot({ path: join(OUT, 'handover-h2-negative-control-375x812.png') });
      await ctx.close();
    }

    // ── H3 零回归：邻位是文档签 ⇒ 按既有邻位语义落签，自愈不插手 ──────────
    {
      const { ctx, page } = await boot(browser);
      const scene = await page.evaluate(OPEN_DOC_ONLY);
      await settle(page, 400);
      await page.evaluate(CLOSE_ACTIVE);
      const after = await waitContent(page, 4000);
      readings.H3 = { scene, after };
      record('H3a', '零回归：邻位文档签接管（closeTab 既有语义）', after.activeTabId === 'file:/tmp/handover-d.md', `activeTabId=${after.activeTabId}`);
      record('H3b', '零回归：该落签内容区非空', !after.blank && after.textLen > 0, `blank=${after.blank} textLen=${after.textLen}`);
      record('H3c', '零回归：自愈未插手（事件 0）', after.selfHealEvents === 0, `selfHealEvents=${after.selfHealEvents}`);
      await ctx.close();
    }

    // ── H4 零回归：不在 canvas 页 ⇒ 不静默换签；进页才自愈 ────────────────
    {
      const { ctx, page } = await boot(browser);
      await page.evaluate(OPEN_SCENE);
      await settle(page, 400);
      await page.evaluate(async () => {
        const nav = await import('/js/mobile/navController.js');
        nav.dockTo('chat');   // 前台离开 canvas 页
      });
      await settle(page, 600);
      const beforeClose = await page.evaluate(CONTENT_PROBE);
      await page.evaluate(async () => {
        const canvas = await import('/js/canvas.js');
        canvas.closeTab('askreview:handover');   // 邻位 → 面板签，但前台是消息页
      });
      await settle(page, 800);
      const offPage = await page.evaluate(CONTENT_PROBE);
      await page.evaluate(async () => {
        const nav = await import('/js/mobile/navController.js');
        nav.dockTo('canvas');
      });
      const onPage = await waitContent(page, 4000);
      readings.H4 = { beforeClose, offPage, onPage };
      record('H4a', '零回归：不在 canvas 页时不静默换签', offPage.selfHealEvents === beforeClose.selfHealEvents && offPage.activeTabId === 'projects', `activeTabId=${offPage.activeTabId} selfHeal=${beforeClose.selfHealEvents}→${offPage.selfHealEvents}`);
      record('H4b', '进 canvas 页即自愈（内容区非空）', !onPage.blank && onPage.visiblePanes > 0 && onPage.textLen > 0 && onPage.selfHealEvents >= 1, `blank=${onPage.blank} visiblePanes=${onPage.visiblePanes} selfHealEvents=${onPage.selfHealEvents} activeTabId=${onPage.activeTabId}`);
      await ctx.close();
    }
  } finally {
    await browser.close();
  }

  writeFileSync(join(OUT, 'canvas-tab-handover-readings.json'), JSON.stringify({ rows, readings }, null, 1));
  console.log(`\n${pass} passed, ${fail} failed\n${OUT}`);
  process.exit(fail ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
