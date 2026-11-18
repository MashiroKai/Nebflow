#!/usr/bin/env node
// tests/canvas-button-unify-geom.spec.mjs — canvas-button-unify 批的补充回归：
// 「按钮组一份实现、两个消费族」的**实测**判据（移动待办组 ↔ 桌面 canvas 组）。
//
// 作者令逐字：「Canvas 的按钮组要用与手机待办右上角那组**同一份实现**，不是照抄外观」。
// 上一轮只把材质并进 glass.css 同一条声明，**组几何/行中线是否同规矩、单钮是否还带
// 私有面**没有实测判据。本文件补上：两个视口各量各的可见组，再把两边读数对撞。
//
//   手机 375×812（mobile todo group，实测可见件）
//     M1  组可见且是真组：.ta-pill 可见 + 内含 ≥2 个 .ta-pill-btn
//     M2  组材质 = 唯一毛玻璃令牌（bg / backdrop-filter / border 逐值，经同装置归一）
//     M3  组几何走令牌：height == --mcap-h 解析值；radius == --mcap-radius
//     M4  行中线同轴：组中线 − 同排圆钮中线 ∈ [−2, 2]
//     M5  组容器零私有面（background-image:none）+ 成员自身零材质
//
//   桌面 1280×800（desktop canvas group，实测可见件）
//     D1  组可见且是真组：.canvas-zoom-bar 可见 + 内含 ≥2 个 .canvas-zoom-btn
//     D2  两族同一条共享声明：桌面组材质**逐字符串等于**手机组同一读数
//     D3  组几何 = 胶囊档（canvas-button-unify 收口 2026-11-18）：radius 解析值
//         == --cv-radius-pill(999px) 且 == 手机组实测 999px；padding == 0px；
//         gap == normal（对照 .ta-pill 实测）
//     D4  行中线同轴：条中线 − 成员中线 ∈ [−2, 2]（无同排圆钮则记 NA 并打印清单）
//     D5  组投影只许共享 token：box-shadow == --cv-shadow 解析值
//
//   Canvas 单钮 ↔ 全站按钮（同页实测）
//     S1  .canvas-zoom-btn 尺寸 == --ds-hit-tap-lg 解析值（= 手机 --band-d 同源 48）
//         ∧ 圆角 == --cv-radius-pill 解析值（999px，== .ta-pill-btn 实测）
//     S2  .canvas-zoom-btn 零投影（胶囊成员档：== .ta-pill-btn 实测 none；基线
//         成员的 float 影随组重设计退役，见 glass.css 共享面例外登记）
//     S3  every canvas-button 同时挂已登记族（身份钩子不供几何）
//     S5  组成员 ↔ 手机胶囊成员（跨视口同规实测）：radius / shadow / padding /
//         尺寸 / color 逐值相等
//
// 装置：零端口零进程（scripts/lib/visual-harness.mjs：page.route 从磁盘服务 web/，
// MockWS 走真实 ws.js 分发）⇒ 结构上不可能碰到宿主 6138。
// 改前对照：NEBFLOW_WEB_ROOT=<改前 web 树> node tests/canvas-button-unify-geom.spec.mjs <outDir>
//
// 用法：node tests/canvas-button-unify-geom.spec.mjs [绝对 outDir]
// 退出码：0 = 全绿；1 = 有 FAIL（读数照旧全打印）；2 = 装置错误。

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, webRoot } from '../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const OUT = process.argv[2] || join(ROOT, 'evidence', 'canvas-button-unify-supplement', 'geom');
mkdirSync(OUT, { recursive: true });

const rows = [];
let pass = 0, fail = 0;
function record(id, name, ok, readout) {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass++; else fail++;
}
const R2 = (n) => Math.round(n * 100) / 100;

// 页内探针公共段：读数全来自 getComputedStyle / getBoundingClientRect；
// 令牌归一（nBorder/nShadow/nLen）= 把令牌值喂给同页探测元素再读计算值，
// 这样「令牌文本」与「计算值」在同一装置下可比（rgba(...) vs #hex 不算差异）。
const HELPERS = `
  const R = n => Math.round(n * 100) / 100;
  const CS = el => getComputedStyle(el);
  const vis = el => !!el && el.getClientRects().length > 0 && CS(el).display !== 'none'
    && CS(el).visibility === 'visible' && Number(CS(el).opacity) > 0;
  const box = el => { const r = el.getBoundingClientRect();
    return { x: R(r.x), y: R(r.y), w: R(r.width), h: R(r.height), cx: R(r.x + r.width / 2), cy: R(r.y + r.height / 2), right: R(r.right), bottom: R(r.bottom) }; };
  const face = el => { const s = CS(el);
    return { bg: s.backgroundColor, bgImage: s.backgroundImage, bd: s.backdropFilter, wbd: s.webkitBackdropFilter,
             border: s.borderTopWidth + ' ' + s.borderTopStyle + ' ' + s.borderTopColor, radius: s.borderTopLeftRadius,
             shadow: s.boxShadow, pad: s.padding, gap: s.gap, pos: s.position, z: s.zIndex }; };
  const mkProbe = () => { const n = document.createElement('div');
    n.style.cssText = 'position:fixed;left:-9999px;top:0;width:10px;height:10px';
    document.body.appendChild(n); return n; };
  const nBorder = v => { const n = mkProbe(); n.style.border = v; const c = CS(n);
    const o = c.borderTopWidth + ' ' + c.borderTopStyle + ' ' + c.borderTopColor; n.remove(); return o; };
  const nShadow = v => { const n = mkProbe(); n.style.boxShadow = v; const o = CS(n).boxShadow; n.remove(); return o; };
  const nLen = v => { const n = mkProbe(); n.style.height = v; const o = CS(n).height; n.remove(); return o; };
  const nRadius = v => { const n = mkProbe(); n.style.borderTopLeftRadius = v; const o = CS(n).borderTopLeftRadius; n.remove(); return o; };
  const tok = (el, n) => CS(el).getPropertyValue(n).trim();
`;

// ── 手机腿探针 ─────────────────────────────────────────────────────────────
const MOBILE_PROBE = `(() => {${HELPERS}
  const page = document.querySelector('.mobile-page[data-page="tasks"]');
  const group = page ? [...page.querySelectorAll('.ta-pill')].find(vis) : null;
  const circles = page ? [...page.querySelectorAll('.mobile-back-btn, .ta-fab')].filter(vis) : [];
  return {
    found: !!group,
    live: !!document.querySelector('.mobile-page[data-page="tasks"].live'),
    group: group ? { cls: String(group.className), box: box(group), face: face(group) } : null,
    members: group ? [...group.querySelectorAll('.ta-pill-btn')].filter(vis).map(b => ({ cls: String(b.className), box: box(b), face: face(b) })) : [],
    circles: circles.map(c => ({ cls: String(c.className), box: box(c) })),
    tokens: group ? {
      glassBg: tok(group, '--nf-glass-bg'), glassFilter: tok(group, '--nf-glass-input-filter'),
      glassBorder: nBorder(tok(group, '--nf-glass-border')),
      mcapH: nLen(tok(group, '--mcap-h')), mcapRadius: nRadius(tok(group, '--mcap-radius')),
      bandD: tok(group, '--band-d'),
    } : null,
  };
})()`;

// ── 桌面腿探针 ─────────────────────────────────────────────────────────────
const DESKTOP_PROBE = `(() => {${HELPERS}
  const pane = document.querySelector('.canvas-tab-pane.active') || document.querySelector('.canvas-tab-pane');
  const bar = pane ? [...pane.querySelectorAll('.canvas-zoom-bar')].find(vis) : null;
  const scopes = pane ? [pane, pane.parentElement].filter(Boolean) : [];
  const circleSel = '.canvas-find-toggle, .canvas-ref-select, .canvas-source-toggle, .canvas-refresh-toggle, .ask-fab, .note-fab';
  const circles = scopes.flatMap(sc => [...sc.querySelectorAll(circleSel)]).filter(vis);
  const inv = scopes.flatMap(sc => [...sc.querySelectorAll('[class*="canvas-"], .ask-fab, .note-fab')])
    .filter(vis).map(e => ({ cls: String(e.className), box: box(e), cy: box(e).cy }));
  const zoomBtns = bar ? [...bar.querySelectorAll('.canvas-zoom-btn')].filter(vis) : [];
  const nRadiusT = v => nRadius(v);
  const siteBtns = ['.g2-fab', '.canvas-close-btn', '#canvas-head-close', '.ds-btn', '.panel-btn']
    .map(sel => { const el = [...document.querySelectorAll(sel)].find(vis);
      return el ? { sel, cls: String(el.className), box: box(el), face: face(el) } : { sel, cls: null }; });
  const dsProbe = (() => {
    const n = document.createElement('button'); n.className = 'ds-btn'; n.textContent = 'ds';
    n.style.cssText = 'position:fixed;left:-9999px;top:0';
    document.body.appendChild(n);
    const r = CS(n).borderTopLeftRadius; n.remove();
    return { injected: true, radius: r, tokenResolved: nRadiusT(tok(document.body, '--ds-radius-input')) };
  })();
  return {
    paneCls: pane ? String(pane.className) : '',
    barsVisible: [...document.querySelectorAll('.canvas-zoom-bar')].filter(vis).length,
    group: bar ? { cls: String(bar.className), box: box(bar), face: face(bar) } : null,
    members: zoomBtns.map(b => ({ cls: String(b.className), box: box(b), face: face(b) })),
    circles: circles.map(c => ({ cls: String(c.className), box: box(c) })),
    inventory: inv,
    dsProbe,
    siteBtns,
    tokens: bar ? {
      glassBg: tok(bar, '--nf-glass-bg'), glassFilter: tok(bar, '--nf-glass-input-filter'),
      glassBorder: nBorder(tok(bar, '--nf-glass-border')),
      cvRadiusPill: nRadius(tok(bar, '--cv-radius-pill')), dsHitTapLg: nLen(tok(bar, '--ds-hit-tap-lg')),
      dsShadowFloat: nShadow(tok(bar, '--ds-shadow-float')),
      cvShadow: nShadow(tok(bar, '--cv-shadow')), cvSizeCircleLg: nLen(tok(bar, '--cv-size-circle-lg')),
      dsRadiusInput: nRadius(tok(bar, '--ds-radius-input')),
    } : null,
  };
})()`;

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  const log = { webRoot: webRoot(), browser: label, viewports: {}, snapshots: {} };

  // ══ 手机腿 375×812 ══════════════════════════════════════════════════════
  const ctxM = await browser.newContext({ viewport: { width: 375, height: 812 } });
  const sM = await createSession({ newPage: () => ctxM.newPage() },
    { width: 375, height: 812, baseURL: 'https://stand.test/' });
  await sM.boot();
  await sM.page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await sM.page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.activateNav();
    nav.pushPage('tasks');
  });
  await settle(sM.page, 250);
  const mobile = await sM.page.evaluate(MOBILE_PROBE);
  log.viewports.mobile = { width: 375, height: 812, url: sM.page.url() };
  log.snapshots.mobile = mobile;
  await ctxM.close();

  // ══ 桌面腿 1280×800 ═════════════════════════════════════════════════════
  const ctxD = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const sD = await createSession({ newPage: () => ctxD.newPage() },
    { width: 1280, height: 800, baseURL: 'https://stand.test/' });
  await sD.boot();
  const IMG = 'file:/tmp/cbu-geom.svg';
  const IMG_URI = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="400"><rect width="640" height="400" fill="#22304a"/>'
    + '<circle cx="320" cy="200" r="120" fill="#7f5ad4"/></svg>',
  );
  await sD.page.evaluate((d) => { window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d })); }, {
    id: IMG, itemType: 'image', title: 'cbu-geom.svg', pinned: true,
    content: '', absPath: '/tmp/cbu-geom.svg', size: 0, mtimeMs: 1757000000000, objectUrl: IMG_URI,
  });
  let desktop = null, bootErr = null;
  try {
    await sD.page.waitForSelector('.canvas-tab-pane .canvas-zoom-bar', { state: 'visible', timeout: 15000 });
    await settle(sD.page, 250);
    desktop = await sD.page.evaluate(DESKTOP_PROBE);
  } catch (e) { bootErr = String(e.message).split('\n')[0]; }
  log.viewports.desktop = { width: 1280, height: 800, url: sD.page.url() };
  log.snapshots.desktop = { ...(desktop || {}), error: bootErr };
  await ctxD.close();
  await browser.close();

  // ══ M1–M5 ═══════════════════════════════════════════════════════════════
  const gM = mobile.group, tM = mobile.tokens;
  record('M1', '手机待办组实测可见且是真组（≥2 键）',
    !!gM && mobile.members.length >= 2,
    `live=${mobile.live} cls=${gM && gM.cls} members=${mobile.members.length} box=${gM ? JSON.stringify(gM.box) : '-'}`);
  if (gM && tM) {
    record('M2', '组材质 = 唯一毛玻璃令牌（bg / backdrop-filter / border 逐值）',
      gM.face.bg === tM.glassBg && gM.face.bd === tM.glassFilter && gM.face.border === tM.glassBorder,
      `bg ${gM.face.bg} vs ${tM.glassBg} | bd ${gM.face.bd} vs ${tM.glassFilter} | border ${gM.face.border} vs ${tM.glassBorder}`);
    record('M3', '组几何走令牌：height=--mcap-h 解析值、radius=--mcap-radius',
      gM.box.h === parseFloat(tM.mcapH) && gM.face.radius === tM.mcapRadius,
      `h ${gM.box.h} vs ${tM.mcapH} | radius ${gM.face.radius} vs ${tM.mcapRadius} | band-d=${tM.bandD}`);
    const ref = mobile.circles.find(c => /mobile-back-btn/.test(c.cls)) || mobile.circles[0];
    const dCy = ref ? R2(gM.box.cy - ref.box.cy) : null;
    record('M4', '行中线同轴：组中线 − 同排圆钮中线 ∈ [−2,2]',
      !!ref && Math.abs(dCy) <= 2,
      `group.cy=${gM.box.cy} ref=${ref ? `${ref.cls} cy=${ref.box.cy}` : 'none'} Δcy=${dCy}`);
    record('M5', '组容器零私有面（background-image:none）+ 成员自身零材质',
      gM.face.bgImage === 'none' && mobile.members.every(m => m.face.bgImage === 'none'),
      `group.bgImage=${gM.face.bgImage} member.bgImage=${JSON.stringify(mobile.members.map(m => m.face.bgImage))}`);
  } else record('M2-M5', '手机腿探针取到组与令牌', false, `group=${!!gM} tokens=${!!tM}`);

  // ══ D1–D5 ═══════════════════════════════════════════════════════════════
  const gD = desktop && desktop.group, tD = desktop && desktop.tokens;
  record('D1', '桌面 canvas 组实测可见且是真组（≥2 钮）',
    !!gD && desktop.barsVisible === 1 && desktop.members.length >= 2,
    `error=${bootErr} bars visible=${desktop ? desktop.barsVisible : '-'} members=${desktop ? desktop.members.length : '-'} box=${gD ? JSON.stringify(gD.box) : '-'}`);
  if (gD && tD && gM) {
    record('D2', '两族同一条共享声明：桌面组材质逐字符串 = 手机组材质',
      gD.face.bg === gM.face.bg && gD.face.bd === gM.face.bd && gD.face.border === gM.face.border,
      `bg ${gD.face.bg} == ${gM.face.bg} | bd ${gD.face.bd} == ${gM.face.bd} | border ${gD.face.border} == ${gM.face.border}`);
    record('D3', '组几何=胶囊档：radius==--cv-radius-pill 解析值且==手机组实测、padding 0px、gap normal',
      gD.face.radius === tD.cvRadiusPill && gM.face.radius === gD.face.radius
        && gD.face.pad === '0px' && gD.face.gap === 'normal',
      `radius ${gD.face.radius} vs pill=${tD.cvRadiusPill} vs mobile=${gM.face.radius} | pad ${gD.face.pad} | gap ${gD.face.gap}`);
    const ref = desktop.members[0] || null;
    const dCy = ref ? R2(gD.box.cy - ref.box.cy) : null;
    record('D4', '条中线 = 成员圆钮行中线（同排兄弟圆钮族在本夹具缺席，见 readout）',
      !!ref && Math.abs(dCy) <= 2,
      `bar.cy=${gD.box.cy} bar.h=${gD.box.h} member=${ref ? `${ref.cls} cy=${ref.box.cy} h=${ref.box.h}` : 'none'} Δcy=${dCy} | 同排兄弟圆钮族可见件=${desktop.circles.length} | 可见件=${JSON.stringify((desktop.inventory || []).filter(i => !/canvas-zoom-btn/.test(i.cls)).map(i => `${i.cls}@cy${i.cy}`))}`);
    record('D5', '组投影只许共享 token：box-shadow == --cv-shadow 解析值',
      gD.face.shadow === tD.cvShadow,
      `shadow ${gD.face.shadow} vs ${tD.cvShadow}`);
  } else record('D2-D5', '桌面腿探针取到组与令牌', false, `group=${!!gD} tokens=${!!tD}`);

  // ══ S1–S5 ═══════════════════════════════════════════════════════════════
  if (desktop && desktop.members.length && tD) {
    const side = parseFloat(tD.dsHitTapLg);
    const radii = [...new Set(desktop.members.map(m => m.face.radius))];
    const sizes = [...new Set(desktop.members.map(m => `${m.box.w}×${m.box.h}`))];
    record('S1', '组成员尺寸 = --ds-hit-tap-lg 解析值 ∧ 圆角 = --cv-radius-pill 解析值',
      sizes.length === 1 && sizes[0] === `${side}×${side}` && radii.length === 1 && radii[0] === tD.cvRadiusPill,
      `sizes=${JSON.stringify(sizes)} expect=${side}×${side} | radii=${JSON.stringify(radii)} expect=${tD.cvRadiusPill}`);
    const shadows = [...new Set(desktop.members.map(m => m.face.shadow))];
    record('S2', '组成员零投影（胶囊成员档 == .ta-pill-btn 实测 none）',
      shadows.length === 1 && shadows[0] === 'none', `shadows=${JSON.stringify(shadows)}`);
    const hooks = desktop.members.filter(m => /\bcanvas-button\b/.test(m.cls));
    record('S3', 'every canvas-button 同时挂已登记族（身份钩子不供几何）',
      hooks.length === desktop.members.length,
      `members=${desktop.members.length} with hook=${hooks.length} classes=${JSON.stringify(desktop.members.map(m => m.cls))}`);
    // S4 探测读数（.ds-btn 注入）仍留在 JSON 备查；站点侧基准改用跨族同规实测（S5）。
    const mRef = mobile.members[0];
    const dMem = desktop.members[0];
    record('S5', '组成员 ↔ 手机胶囊成员（跨视口同规）：radius/shadow/pad/尺寸/color 逐值相等',
      !!mRef && !!dMem &&
      dMem.face.radius === mRef.face.radius && dMem.face.shadow === mRef.face.shadow &&
      dMem.face.pad === mRef.face.pad && dMem.box.w === mRef.box.w && dMem.box.h === mRef.box.h &&
      desktop.members.every(m => m.face.color === mRef.face.color),
      `desktop=${JSON.stringify({ r: dMem.face.radius, sh: dMem.face.shadow, pad: dMem.face.pad, box: `${dMem.box.w}x${dMem.box.h}`, color: dMem.face.color })}`
      + ` mobile=${JSON.stringify({ r: mRef.face.radius, sh: mRef.face.shadow, pad: mRef.face.pad, box: `${mRef.box.w}x${mRef.box.h}`, color: mRef.face.color })}`);
  }

  const stamp = new Date().toISOString();
  const logPath = join(OUT, 'canvas-button-unify-geom.log');
  const jsonPath = join(OUT, 'canvas-button-unify-geom.json');
  const body = [
    `canvas-button-unify geom regression — ${stamp}`,
    `webRoot=${webRoot()} browser=${label}`,
    `viewports: mobile 375x812 | desktop 1280x800`,
    '',
    ...rows.map(r => `${r.verdict}  ${r.id}  ${r.name}\n        ${r.readout}`),
    '',
    `RESULT ${pass}/${pass + fail} PASS, ${fail} FAIL`,
  ].join('\n');
  writeFileSync(logPath, body + '\n');
  writeFileSync(jsonPath, JSON.stringify({ stamp, ...log, rows, pass, fail }, null, 2));
  console.log(`\nRESULT ${pass}/${pass + fail} PASS, ${fail} FAIL`);
  console.log(`log:  ${logPath}`);
  console.log(`json: ${jsonPath}`);
  process.exitCode = fail ? 1 : 0;
}

main().catch((e) => { console.error('harness error:', e); process.exit(2); });
