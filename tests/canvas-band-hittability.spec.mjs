#!/usr/bin/env node
// tests/canvas-band-hittability.spec.mjs — 手机层 canvas **底部控件带**的「全族逐钮命中」
// 闸 + **负控**（作者令 2026-11-19）。
//
// 背景（作者 11-18 报障 41420bed8 的续）：11-18 把「左下两钮 / 中页点条 / 右气泡钮」
// 收进既有底锚 `--mf-row-bottom`，但 11-19 新增的「审阅全量动作带」`.nf-dv-allbar`
// 没进带 —— 它是 `.nf-dv`（flex column）的**流内末件**，底 = 宿主底 = 视口底，整条
// 62px 落进 `#mobile-dock`（fixed/z200 实体）带区 ⇒ 人眼看不见 + 手指点不到。
// 作者同时立了规矩：**先把「哪些控件族应在带内」定清楚再改**（免得下次又只补一个
// selector）。族清单与三条进带判据的唯一真源 = `css/mobileLayer.css` 的
// 「底部控件带的族清单与进带判据」注释块；本闸的同名表 **BAND_FAMILY** 与它逐条对应。
//
// 判据（每条 = 该族**每个交互钮**，取**中心点**；不是 DOM 存在性）：
//   B1 非零尺寸且整体在视口内
//   B2 与 `#mobile-dock` 矩形**零交叠**（dock 是实体浮层，压上去 = 看不见）
//   B3 `elementFromPoint(钮心)` 命中自身或其后代（可见 ≠ 可点）
//   B4 覆盖率：族清单里每个 scene 标记的族都必须在对应场景里**至少有一件在场可见**
//      （族被删/被藏 = 闸红，而不是静默跳过）
//   B5 设计不变量：审阅动作带仍是**流内**（`position:static`）—— 用 `margin-bottom`
//      抬带缘，不许改成 `position:absolute/fixed`（那会把「与悬浮控件零交叠」的
//      不变量偷换成「谁压谁」）
//   B6 审阅带成员数 = 2（全部接受 / 全部拒绝；少一颗 = 收口动作缺一面）
//   B7 **负控**：把本次修复中和掉（`margin-bottom:0 !important`，等价于「撤掉修复」）
//      ⇒ 同一判据**必红**（带内钮被 dock 压住 / 点不到）。若中和后仍全绿 ⇒ 判据对该
//      缺陷不敏感 ⇒ 本闸自己红（负控不自证就不算闸）。
//   B8 负控装置自检：中和后台 computed `margin-bottom` == `0px`（否则中和没生效，
//      B7 的「仍全绿」不能算判据不敏感）
//
// 装置：scripts/lib/visual-harness.mjs（零端口零进程；page.route 从**真前端源码树**
// 投送 web/，MockWS 走真实 ws.js 分发）⇒ 结构上碰不到宿主实例 6138。审阅面走**真
// 组件链**：`js/askEditReview.js` 的 `consumeApplied` → `canvas.js openTab` →
// `js/diffView` `mountDiff(mode:'review')` —— 不是手搓 DOM，故 `.nf-dv-allbar` 的
// 几何（流内末件贴宿主底）就是产品里的那一份。
//
// 用法：node tests/canvas-band-hittability.spec.mjs [outDir]
// 退出码：0 = 全绿；2 = 装置错误。
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, webRoot } from '../scripts/lib/visual-harness.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const OUT = process.argv[2] && !process.argv[2].startsWith('--')
  ? process.argv[2] : join(ROOT, 'evidence', 'canvas-band-hittability');
mkdirSync(OUT, { recursive: true });

const rows = [];
let pass = 0, fail = 0;
const record = (id, name, ok, readout) => {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass += 1; else fail += 1;
};
const R2 = (n) => Math.round(n * 100) / 100;

// ── 族清单（唯一真源 = css/mobileLayer.css 的同名注释块）──────────────────────
// scene：这件控件在哪个场景里**应当在场**。'doc' = 文档画布页（左下工具对 / 中页点条 /
// 右气泡钮）；'review' = 审阅页签（`.nf-dv-allbar` 的成员钮）。跨场景可见的族也会被
// 逐件过判据，只是不参与自己 scene 之外的覆盖率要求。
const BAND_FAMILY = [
  { fam: 'left-pair', sel: '.canvas-find-toggle', scene: 'doc' },
  { fam: 'left-pair', sel: '.canvas-ref-select', scene: 'doc' },
  { fam: 'dots', sel: '.mf-dots', scene: 'doc' },
  { fam: 'ask-fab', sel: '.ask-fab', not: '.note-fab', scene: 'doc' },
  { fam: 'review-band', sel: '.nf-dv-allbar .nf-dv-allbtn', scene: 'review', min: 2 },
];
// 不入带（随正文流的**内容件**，允许从浮件下穿过；本闸只做「不许进带」的反向读数）：
const NOT_IN_BAND = ['.nf-dv-chunkbtn'];

const SEL_TABLE = BAND_FAMILY.map(({ fam, sel, not }) => ({ fam, sel, ...(not ? { not } : {}) }));

/** 页内探针：逐族逐件取几何 + `#mobile-dock` 交叠 + 中心点命中读数。 */
const BAND_PROBE = `(() => {
  const R = (el) => { const r = el.getBoundingClientRect(); return { x:+r.x.toFixed(2), y:+r.y.toFixed(2), w:+r.width.toFixed(2), h:+r.height.toFixed(2),
    right:+r.right.toFixed(2), bottom:+r.bottom.toFixed(2), cx:+(r.x+r.width/2).toFixed(2), cy:+(r.y+r.height/2).toFixed(2) }; };
  const vis = (el) => { if (!el) return false; const s = getComputedStyle(el);
    return el.getClientRects().length > 0 && s.display !== 'none' && s.visibility === 'visible' && Number(s.opacity) > 0; };
  const dock = document.querySelector('#mobile-dock');
  const dockBox = dock ? R(dock) : null;
  const ov = (a, b) => (a && b) ? Math.round(Math.max(0, Math.min(a.right,b.right) - Math.max(a.x,b.x))
    * Math.max(0, Math.min(a.bottom,b.bottom) - Math.max(a.y,b.y)) * 100) / 100 : 0;
  const label = (el) => el.tagName.toLowerCase() + '.' + String(el.className).trim().split(/\\s+/).slice(0, 2).join('.');
  const groups = [];
  for (const s of window.__bandSels) {
    let els = [...document.querySelectorAll(s.sel)];
    if (s.not) els = els.filter((e) => !e.matches(s.not));
    const items = els.map((el) => {
      if (!vis(el)) return { cls: label(el), visible: false };
      const box = R(el);
      const t = document.elementFromPoint(box.cx, box.cy);
      const hitSelf = !!(t && (t === el || el.contains(t) || t.contains(el)));
      const outside = box.w <= 0 || box.h <= 0 || box.x < -0.5 || box.right > innerWidth + 0.5
        || box.y < -0.5 || box.bottom > innerHeight + 0.5;
      const dockOverlapPx = ov(box, dockBox);
      const problem = outside ? 'outside-viewport' : dockOverlapPx > 0.5 ? 'under-dock'
        : !hitSelf ? 'not-hittable' : null;
      return { cls: label(el), text: (el.textContent || '').trim().slice(0, 12), visible: true, box, hitSelf,
        hitTarget: t ? label(t) : null, dockOverlapPx, problem };
    });
    groups.push({ fam: s.fam, sel: s.sel, items });
  }
  const bar = document.querySelector('.nf-dv-allbar');
  const chunkButtons = [...document.querySelectorAll('.nf-dv-chunkbtn')].filter(vis)
    .map((b) => ({ cls: label(b), box: R(b), dockOverlapPx: ov(R(b), dockBox) }));
  return { viewport: { w: innerWidth, h: innerHeight }, mobileLayer: document.body.classList.contains('mobile-layer'),
    dock: dockBox, band: bar ? { box: R(bar), marginBottom: getComputedStyle(bar).marginBottom,
      position: getComputedStyle(bar).position, display: getComputedStyle(bar).display } : null,
    groups, chunkButtons };
})()`;

/** 场景就位：开三张文档卡（首张 html ⇒ 左下工具对在场；三签 ⇒ 中页点条在场）→
 *  停靠画布页 → 激活 html 页（与既有 canvas-controls-dock-clearance 同一装置口径）。 */
const OPEN_DOC_SCENE = `(async () => {
  const html = '<!doctype html><html><body><h1>band gate doc</h1>' + '<p>band gate readable line</p>'.repeat(60) + '</body></html>';
  const md = '# band gate doc\\n\\n' + 'band gate readable line\\n\\n'.repeat(60);
  const items = [
    { id: 'file:/tmp/band-gate.html', itemType: 'html', title: 'band-gate', content: html, absPath: '/tmp/band-gate.html' },
    { id: 'file:/tmp/band-gate-2.md', itemType: 'markdown', title: 'band-gate-2', content: md, absPath: '/tmp/band-gate-2.md' },
    { id: 'file:/tmp/band-gate-3.md', itemType: 'markdown', title: 'band-gate-3', content: md, absPath: '/tmp/band-gate-3.md' },
  ];
  for (const d of items) window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...d, pinned: true, size: d.content.length } }));
  const nav = await import('/js/mobile/navController.js');
  nav.dockTo('canvas');
  const canvas = await import('/js/canvas.js');
  const ids = nav.docTabs().map((d) => d.id);
  canvas.setActiveTab(ids[0]);
  return ids;
})()`;

/** 审阅场景就位：真 `askEditReview` 消费一帧 `askEditApplied` ⇒ 真组件 mount。 */
const OPEN_REVIEW_SCENE = `(async () => {
  const ar = await import('/js/askEditReview.js');
  try { ar.initAskEditReview(); } catch { /* 已 init 时幂等 */ }
  const base = 'alpha\\nbeta\\ngamma\\n' + 'gap\\n'.repeat(30) + 'omega\\n';
  const cur = base.replace('alpha', 'ALPHA').replace('omega', 'omega\\nOMEGA-NEW\\n');
  ar.consumeApplied({ sessionId: 'ask-band-gate', path: '/tmp/band-gate.md', fileName: 'band-gate.md',
    baseText: base, currentText: cur, count: 2, updatedAt: Date.now(),
    hunks: [
      { id: 'h0', oldStart: 0, oldCount: 1, newStart: 0, newCount: 1, added: 1, removed: 1 },
      { id: 'h1', oldStart: 33, oldCount: 1, newStart: 33, newCount: 2, added: 2, removed: 1 },
    ] });
  return true;
})()`;

/** 逐族逐件过判据；返回失败说明串（空 = 全过）。`scene` 决定哪几族参与覆盖率。 */
function bandProblems(probe, scene) {
  const problems = [];
  for (const f of BAND_FAMILY) {
    if (f.scene !== scene && !(scene === 'review' && f.scene === 'doc')) continue;
    const g = probe.groups.find((x) => x.fam === f.fam && x.sel === f.sel);
    if (!g) { problems.push(`${f.fam}:${f.sel} probe-missing`); continue; }
    const visible = g.items.filter((i) => i.visible);
    if (f.scene === scene && (!visible.length || (f.min && visible.length < f.min))) {
      problems.push(`${f.fam}:${f.sel} coverage=${visible.length}${f.min ? `<${f.min}` : ''}`);
    }
    for (const it of visible) {
      if (it.problem) {
        problems.push(`${f.fam}:${f.sel} ${it.cls} ${it.problem} box=${JSON.stringify(it.box)}`
          + ` dockOverlap=${it.dockOverlapPx} hit=${it.hitTarget}`);
      }
    }
  }
  return problems;
}

async function bootScene(browser, opts = {}) {
  const width = opts.width ?? 375, height = opts.height ?? 812;
  const ctx = await browser.newContext({ viewport: { width, height }, hasTouch: true });
  const session = await createSession({ newPage: (o) => ctx.newPage(o) },
    { width, height, colorScheme: opts.theme ?? 'dark', baseURL: 'https://stand.test/' });
  const { page } = session;
  await page.setViewportSize({ width, height });
  await page.emulateMedia({ colorScheme: opts.theme ?? 'dark', reducedMotion: 'reduce' });
  if (opts.unfixCss) {
    // 负控装置：在装置自身的静态路由**之后**注册（Playwright 后注册先命中），
    // 只把本次修复中和掉（等价「撤掉修复」的短腿），其余字节不变。
    const cssFile = join(webRoot(), 'css', 'mobileLayer.css');
    await page.route('**/css/mobileLayer.css', (route) => {
      const base = readFileSync(cssFile, 'utf8');
      route.fulfill({ status: 200, contentType: 'text/css', body: base + '\n' + opts.unfixCss + '\n' });
    });
  }
  await session.boot();
  await page.waitForSelector('body.mobile-layer');
  await page.evaluate((sels) => { window.__bandSels = sels; }, SEL_TABLE);
  return { ctx, page, session };
}

async function docScene(page) {
  await page.evaluate(OPEN_DOC_SCENE);
  await page.waitForSelector('.mf-cv-host > .canvas-tab-pane.active .canvas-find-toggle', { state: 'attached', timeout: 15000 });
  await settle(page, 300);
  return page.evaluate(BAND_PROBE);
}
async function reviewScene(page) {
  await page.evaluate(OPEN_REVIEW_SCENE);
  await page.waitForSelector('.canvas-tab-pane[data-type="askreview"] .nf-dv-allbar .nf-dv-allbtn', { state: 'attached', timeout: 20000 });
  await settle(page, 400);
  return page.evaluate(BAND_PROBE);
}

const { mod } = loadPlaywright();
const { browser, label } = await launchBrowser(mod);
const readings = [];
try {
  // ── B0 静态单源：修复规则 + 族清单注释在源码里（真源缺席 ⇒ 后续腿无从谈起）──
  const cssSrc = readFileSync(join(webRoot(), 'css', 'mobileLayer.css'), 'utf8');
  const hasRule = /body\.mobile-layer #mobile-pages \.mf-cv-host > \.canvas-tab-pane \.nf-dv-allbar\s*\{[^}]*margin-bottom:\s*var\(--mf-row-bottom/.test(cssSrc);
  const hasFamilyDoc = cssSrc.includes('底部控件带') && cssSrc.includes('进带判据');
  record('B0', '静态单源：`.nf-dv-allbar` 进带规则（`margin-bottom: var(--mf-row-bottom)`）+ 族清单注释在场',
    hasRule && hasFamilyDoc, `rule=${hasRule} familyDoc=${hasFamilyDoc} css=${join(webRoot(), 'css', 'mobileLayer.css')}`);

  // ── B1/B2/B3/B4/B5/B6：正腿（真前端源码树，亮 + 暗）─────────────────────
  let docFixedDark = null;
  for (const theme of ['dark', 'light']) {
    const { ctx, page, session } = await bootScene(browser, { theme });
    const doc = await docScene(page);
    const review = await reviewScene(page);
    readings.push({ scene: `band-${theme}`, doc, review });
    if (theme === 'dark') docFixedDark = doc;

    const docProbs = bandProblems(doc, 'doc');
    record(`B1-${theme}`, '文档画布页：左下工具对 / 中页点条 / 右气泡钮 逐钮命中自身（零 dock 交叠 / 在视口内）',
      docProbs.length === 0, docProbs.length ? docProbs.join(' | ')
        : doc.groups.map((g) => `${g.fam}:${g.items.filter((i) => i.visible).length}`).join(' '));

    const revProbs = bandProblems(review, 'review');
    record(`B2-${theme}`, '审阅页签：`.nf-dv-allbar` 全量动作钮逐钮命中自身（零 dock 交叠 / 在视口内）',
      revProbs.length === 0, revProbs.length ? revProbs.join(' | ')
        : JSON.stringify(review.band && review.band.box) + ' dock=' + JSON.stringify(review.dock));

    const btns = (review.groups.find((g) => g.fam === 'review-band') || { items: [] }).items.filter((i) => i.visible);
    record(`B3-${theme}`, '审阅动作带成员数 = 2（全部接受 / 全部拒绝），且两颗中心可点',
      btns.length === 2 && btns.every((b) => b.hitSelf && b.problem === null),
      btns.map((b) => `${b.text || b.cls}@${JSON.stringify(b.box)} hit=${b.hitTarget}`).join(' | '));

    record(`B4-${theme}`, '设计不变量：审阅动作带仍是流内带（`position:static`）',
      !!review.band && review.band.position === 'static',
      `band=${JSON.stringify(review.band)}`);

    record(`B5-${theme}`, '内容件不进带：`.nf-dv-chunkbtn` 与 dock 零交叠（随正文流，不是页底件）',
      review.chunkButtons.every((c) => c.dockOverlapPx <= 0.5),
      review.chunkButtons.map((c) => `${c.cls}@y${c.box.y} ov=${c.dockOverlapPx}`).join(' | ') || 'no visible chunk button');

    const chunks = await page.evaluate(() => document.querySelector('.canvas-tab-pane[data-type="askreview"] .nf-dv-allbar .nf-dv-allbtn[disabled]') !== null);
    record(`B6-${theme}`, '无 JS 异常（打桩未覆盖的 404 不计）', session.errors.length === 0,
      `errors=${JSON.stringify(session.errors)} disabledAcceptAll=${chunks}`);
    await ctx.close();
  }

  // ── B7/B8：负控（撤掉本次修复 ⇒ 同一判据必红）────────────────────────────
  const NEG = 'body.mobile-layer #mobile-pages .mf-cv-host > .canvas-tab-pane .nf-dv-allbar{margin-bottom:0 !important}';
  const { ctx: nctx, page: npage } = await bootScene(browser, { theme: 'dark', unfixCss: NEG });
  const docNeg = await docScene(npage);
  const neg = await reviewScene(npage);
  readings.push({ scene: 'band-negative-control', doc: docNeg, review: neg });

  record('B8', '负控装置自检：中和后审阅带 computed `margin-bottom` == `0px`（中和真生效）',
    !!neg.band && neg.band.marginBottom === '0px', `marginBottom=${JSON.stringify(neg.band && neg.band.marginBottom)}`);

  const negProbs = bandProblems(neg, 'review');
  const negBandBtns = (neg.groups.find((g) => g.fam === 'review-band') || { items: [] }).items.filter((i) => i.visible);
  record('B7', '负控：撤掉本次修复 ⇒ 同一判据必红（带内钮被 dock 压住 / 点不到）',
    negProbs.length > 0 && negBandBtns.length > 0 && negBandBtns.some((b) => b.problem === 'under-dock' || b.problem === 'not-hittable'),
    negProbs.length ? negProbs.join(' | ')
      : 'NEGATIVE CONTROL DID NOT REPRODUCE — criterion is blind to the defect (band=' + JSON.stringify(neg.band && neg.band.box) + ' dock=' + JSON.stringify(neg.dock) + ')');
  await nctx.close();

  // ── B9：既有控件零位移（本批只动审阅带；撤掉修复前后，文档画布页家族几何逐值相同）──
  const boxOf = (probe, sel) => {
    const g = probe.groups.find((x) => x.sel === sel);
    const it = g && g.items.find((i) => i.visible);
    return it ? JSON.stringify(it.box) : 'none';
  };
  const existingSels = ['.canvas-find-toggle', '.canvas-ref-select', '.mf-dots', '.ask-fab'];
  const moved = existingSels.filter((s) => boxOf(docFixedDark, s) !== boxOf(docNeg, s));
  record('B9', '既有控件零位移：撤掉本次修复前后，左下工具对 / 中页点条 / 右气泡钮几何逐值相同',
    moved.length === 0 && existingSels.every((s) => boxOf(docFixedDark, s) !== 'none'),
    moved.length ? moved.map((s) => `${s} fixed=${boxOf(docFixedDark, s)} unfixed=${boxOf(docNeg, s)}`).join(' | ')
      : existingSels.map((s) => `${s}=${boxOf(docFixedDark, s)}`).join(' '));
  const bandFixed = (readings.find((r) => r.scene === 'band-dark') || {}).review;
  record('B10', '本次修复的前后读数：审阅带 撤掉=贴视口底（被 dock 压）/ 修复=抬到 dock 上缘之上',
    !!bandFixed && !!neg.band && bandFixed.band.box.bottom < bandFixed.dock.y && neg.band.box.bottom >= neg.dock.y,
    `fixed=${JSON.stringify(bandFixed && bandFixed.band.box)} (margin ${bandFixed && bandFixed.band.marginBottom})`
    + ` | unfixed=${JSON.stringify(neg.band && neg.band.box)} (margin ${neg.band && neg.band.marginBottom})`
    + ` | dock=${JSON.stringify(bandFixed && bandFixed.dock)}`);
} catch (e) {
  record('SPEC', '驱动中断（装置/组件未接线）', false, String((e && e.message) || e).slice(0, 300));
} finally {
  await browser.close();
  writeFileSync(join(OUT, 'canvas-band-hittability.json'), JSON.stringify({ browser: label, webRoot: webRoot(), rows, readings }, null, 2));
}

console.log(`\n${pass}/${pass + fail} PASS, ${fail} FAIL  (browser=${label}, webRoot=${webRoot()})`);
console.log(`json: ${join(OUT, 'canvas-band-hittability.json')}`);
if (fail > 0) {
  console.error('\ncanvas 底部控件带全族命中闸判红：族清单里每个交互钮都必须在视口内、与 #mobile-dock 零交叠、中心点命中自身；负控（撤掉修复）必须复现红灯。');
  assert.fail(`${fail} canvas band criterion/criteria failed`);
}
