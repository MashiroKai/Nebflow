#!/usr/bin/env node
// tests/canvas-button-unify-diff.spec.mjs — canvas-button-unify 第三轮补充：
// ① **改前/改后计算值对照**（base `BASE_REV` ↔ 当前树，同一装置、同一视口）
// ② **两族组计算值对齐**（手机待办右上角组 ↔ 桌面 Canvas 组）
//
// 作者令逐字：「比较真实计算样式与包围盒……给出数值前后表；任何**有意外观**的变化
// 即回归，必须用共享实现（不是 Canvas 私有样式）修复后重测」。
//
// 装置：scripts/lib/visual-harness.mjs（零端口零进程：page.route 从磁盘服务 web/，
// MockWS 走真实 ws.js 分发）⇒ 结构上不可能碰到宿主 6138。**同一进程**内两趟扫描：
// 第一趟把静态根切到 `--base-web <dir>`（`BASE_REV` 的 web 树，只读），第二趟切回
// 当前树 —— 同一浏览器、同一视口、同一探针，所以两侧读数可直接相减。
//
// 计算值取法：CDP `CSS.forcePseudoState` 强制 :hover/:active（不是 CSS 里手写 class），
// 因此量到的是**真态层**读数。逐元素三态：normal / hover / active。
// 属性面：radius / background / border / box-shadow / backdrop-filter / padding / gap /
//         color（图标 currentColor）/ 包围盒。
//
// 判定（canvas-button-unify 收口 2026-11-18 口径）：
//   A 组（desktop .canvas-zoom-bar）改前↔改后差必须**恰为**对齐令的
//     {radius/pad/gap}+盒高（材质搬运仍逐字符串中性）；手机参考组零变化。
//   B 成员（desktop .canvas-zoom-btn）差恰为
//     {radius/color/border/w/h/bg/shadow/transform}；
//     单钮回归修复（radius 999px/shadow float 复归）= 让差归零，不在此表。
//     除此之外任何计算值变化 = 回归。
//   C 两族组对齐（手机 .ta-pill ↔ 桌面 .canvas-zoom-bar）：radius / 玻璃 bg / filter /
//     border / padding / gap / 成员圆钮尺寸 · color / hover · active 读数必须逐值相等；
//     组总宽允许不同。
//
// 用法：node tests/canvas-button-unify-diff.spec.mjs [绝对 outDir] [--base-web <dir>]
// 退出码：0 = 全绿；1 = 有 FAIL；2 = 装置错误。

import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, webRoot, setWebRoot } from '../scripts/lib/visual-harness.mjs';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const ROOT = join(HERE, '..');
const argv = process.argv.slice(2);
const baseFlag = argv.indexOf('--base-web');
// 基线重设（作者令 2026-11-19「重设基线，不动产品」）：原 BASE_REV `12eef94b1^`
// （canvas-button-unify 批的改前态）已过时——`12eef94b1^` 早于 `bbd72b34c`
// 「Restore themed tint to shared glass surfaces」把共享玻璃底 `--nf-glass-input-bg`
// 由 rgba(128,128,128,0.16) 改为 rgba(255,255,255,0.16)，两族组容器因此各多出一条
// 与本批无关的 `bg` 差（mobile-A / desktop-A 假红）。整合线上不存在「新玻璃 + 旧对齐」
// 的提交 ⇒ 重锚到整合线 tip `5b99db0b9`（= 本支基点 = 已验收现态），红消失且仍作漂移棘轮。
const BASE_REV = '5b99db0b9';
// 对照树自供（CI 可跑）：显式 --base-web 优先；否则从 git 取 BASE_REV 的 web 子树
// 到临时目录（`git archive` = 只读取对象库，不动工作树、不动 .git 元数据）。
function provisionBase() {
  if (baseFlag > -1 && existsSync(argv[baseFlag + 1])) return argv[baseFlag + 1];
  const dir = join(tmpdir(), `cbu-base-${BASE_REV.replace(/[^\w]/g, '')}`);
  const webDir = join(dir, 'src', 'main', 'resources', 'web');
  if (existsSync(join(webDir, 'index.html'))) return webDir;
  mkdirSync(dir, { recursive: true });
  const tar = execFileSync('git', ['archive', BASE_REV, 'src/main/resources/web'], { cwd: ROOT, maxBuffer: 64 * 1024 * 1024 });
  execFileSync('tar', ['-x', '-C', dir], { input: tar, maxBuffer: 64 * 1024 * 1024 });
  return webDir;
}
const BASE_WEB = provisionBase();
const OUT = argv.find((a) => !a.startsWith('--') && a !== BASE_WEB) || join(ROOT, 'evidence', 'canvas-button-unify-supplement', 'diff');
mkdirSync(OUT, { recursive: true });

const R2 = (n) => Math.round(n * 100) / 100;
const rows = [];
let pass = 0, fail = 0;
const record = (id, name, ok, readout) => {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass += 1; else fail += 1;
};

// 页内探针：每个元素读三态（normal / hover / active）。
// 态切换由宿主用 CDP forcePseudoState 完成，这里只负责读数。
const PROBE = `(() => {${''}
  const R = n => Math.round(n * 100) / 100;
  const CS = el => getComputedStyle(el);
  const vis = el => !!el && el.getClientRects().length > 0 && CS(el).display !== 'none'
    && CS(el).visibility === 'visible' && Number(CS(el).opacity) > 0;
  const box = el => { const r = el.getBoundingClientRect();
    return { x: R(r.x), y: R(r.y), w: R(r.width), h: R(r.height), cx: R(r.x + r.width / 2), cy: R(r.y + r.height / 2) }; };
  const face = el => { const s = CS(el);
    return {
      radius: s.borderTopLeftRadius + '|' + s.borderBottomRightRadius,
      bg: s.backgroundColor, bgImage: s.backgroundImage,
      bd: s.backdropFilter || s.webkitBackdropFilter, bdW: s.webkitBackdropFilter,
      border: s.borderTopWidth + ' ' + s.borderTopStyle + ' ' + s.borderTopColor,
      shadow: s.boxShadow, filter: s.filter,
      pad: s.padding, gap: s.gap, color: s.color,
      w: s.width, h: s.height, transform: s.transform,
    }; };
  const svg = el => { const g = el.querySelector('svg'); if (!g) return null; const s = CS(g);
    return { w: s.width, h: s.height, fill: s.fill, stroke: s.stroke, color: s.color }; };
  const one = el => ({ cls: String(el.className), box: box(el), face: face(el), svg: svg(el) });
  window.__cbuProbe = el => one(el);
  return true;
})()`;

const MOBILE = `(() => {
  const page = document.querySelector('.mobile-page[data-page="tasks"]');
  if (!page) return { found: false };
  const group = [...page.querySelectorAll('.ta-pill')].find(e => window.__cbuProbe(e) && e.getClientRects().length);
  const members = group ? [...group.querySelectorAll('.ta-pill-btn')] : [];
  return { found: !!group, groupSel: '.ta-pill', membersSel: '.ta-pill-btn',
    group: group ? window.__cbuProbe(group) : null,
    members: members.map(e => window.__cbuProbe(e)) };
})()`;

const DESKTOP = `(() => {
  const pane = document.querySelector('.canvas-tab-pane.active') || document.querySelector('.canvas-tab-pane');
  if (!pane) return { found: false };
  const bars = [...document.querySelectorAll('.canvas-zoom-bar')].filter(e => e.getClientRects().length);
  const group = bars[0] || null;
  const members = group ? [...group.querySelectorAll('.canvas-zoom-btn')] : [];
  const hooks = [...pane.querySelectorAll('.canvas-button, .canvas-zoom-bar')].filter(e => e.getClientRects().length);
  return { found: !!group, groupSel: '.canvas-zoom-bar', membersSel: '.canvas-zoom-btn',
    group: group ? window.__cbuProbe(group) : null,
    members: members.map(e => window.__cbuProbe(e)),
    hooks: hooks.map(e => ({ role: e.classList.contains('canvas-zoom-bar') ? 'bar' : 'btn', ...window.__cbuProbe(e) })) };
})()`;

// 挂态：对每个（组 + 成员 + 钩子）元素，逐态强制伪类后读数。
async function measure(page, kind) {
  const client = await page.context().newCDPSession(page);
  await client.send('DOM.enable');
  await client.send('CSS.enable');
  const { root } = await client.send('DOM.getDocument', { depth: -1 });
  await page.evaluate(PROBE);
  // 态读数必须先冻结过渡/动画：否则 :hover/:active 读到的是**过场中间值**
  // （本装置实测过 transform 中间值 1.04561、bg 中间值 0.784 —— 那不是任何声明的值）。
  await page.addStyleTag({ content: '*,*::before,*::after{transition:none !important;animation:none !important}' });
  const base = kind === 'mobile' ? await page.evaluate(MOBILE) : await page.evaluate(DESKTOP);
  if (!base.found) { await client.detach(); return { found: false }; }

  const sel = kind === 'mobile' ? '.mobile-page[data-page="tasks"] .ta-pill' : '.canvas-zoom-bar';
  const mSel = kind === 'mobile' ? '.ta-pill-btn' : '.canvas-zoom-btn';
  const allIds = async (selector) => (await client.send('DOM.querySelectorAll', { nodeId: root.nodeId, selector })).nodeIds;
  const tracked = [...await allIds(sel), ...await allIds(mSel), ...await allIds('.canvas-button')];
  const byState = {};
  for (const state of ['normal', 'hover', 'active']) {
    const pseudo = state === 'normal' ? [] : [state];
    for (const id of tracked) await client.send('CSS.forcePseudoState', { nodeId: id, forcedPseudoClasses: [] });
    for (const id of tracked) await client.send('CSS.forcePseudoState', { nodeId: id, forcedPseudoClasses: pseudo });
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    byState[state] = kind === 'mobile' ? await page.evaluate(MOBILE) : await page.evaluate(DESKTOP);
  }
  for (const id of tracked) await client.send('CSS.forcePseudoState', { nodeId: id, forcedPseudoClasses: [] });
  await client.detach();
  return { found: true, states: byState };
}

async function scanPass(browser, kind) {
  if (kind === 'mobile') {
    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 } });
    const s = await createSession({ newPage: () => ctx.newPage() }, { width: 375, height: 812, baseURL: 'https://stand.test/' });
    await s.boot();
    await s.page.waitForSelector('body.mobile-layer', { timeout: 15000 });
    await s.page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); nav.activateNav(); nav.pushPage('tasks'); });
    await settle(s.page, 250);
    const m = await measure(s.page, 'mobile');
    await ctx.close();
    return m;
  }
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const s = await createSession({ newPage: () => ctx.newPage() }, { width: 1280, height: 800, baseURL: 'https://stand.test/' });
  await s.boot();
  const IMG_URI = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="400"><rect width="640" height="400" fill="#22304a"/></svg>');
  await s.page.evaluate((d) => { window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d })); }, {
    id: 'file:/tmp/cbu-diff.svg', itemType: 'image', title: 'cbu-diff.svg', pinned: true,
    content: '', absPath: '/tmp/cbu-diff.svg', size: 0, mtimeMs: 1757000000000, objectUrl: IMG_URI,
  });
  await s.page.waitForSelector('.canvas-tab-pane .canvas-zoom-bar', { state: 'visible', timeout: 15000 });
  await settle(s.page, 250);
  const m = await measure(s.page, 'desktop');
  await ctx.close();
  return m;
}

// 属性面 = 判定表实际比较的键。**必须含 transform**：改前成员在 hover/active 上带
// matrix(1.05)/matrix(0.96)（退役钩子的抬亮/按下档），改后为 none —— 这是本批唯一
// 的**行为**改变（随组重设计退役），列进表面才能被白名单显式认领；漏掉它 = 该档
// 无论有意还是回归都看不见（A/B 变成少报）。包围盒另由 box 列单独比较。
const FACE_KEYS = ['radius', 'bg', 'bgImage', 'bd', 'border', 'shadow', 'filter', 'pad', 'gap', 'color', 'transform', 'w', 'h'];

function faceRows(snap, label) {
  const out = [];
  const st = snap.states;
  for (const state of ['normal', 'hover', 'active']) {
    const s = st[state];
    if (!s || !s.found) continue;
    out.push([`${label}/group/${state}`, s.group.face, s.group.box]);
    (s.members || []).forEach((m, i) => out.push([`${label}/member${i}/${state}`, m.face, m.box]));
  }
  return out;
}

function diffRows(a, b) {
  const mapB = new Map(b.map(([k, f, box]) => [k, { f, box }]));
  const out = [];
  for (const [k, f, box] of a) {
    const t = mapB.get(k);
    if (!t) { out.push({ key: k, changed: ['(missing in current)'], a: f, b: null, boxA: box, boxB: null }); continue; }
    const changed = FACE_KEYS.filter((p) => String(f[p]) !== String(t.f[p]));
    const boxChanged = ['w', 'h', 'x', 'y'].filter((p) => Math.abs(Number(box[p] ?? 0) - Number(t.box?.[p] ?? 0)) > 0.51);
    if (changed.length || boxChanged.length) out.push({ key: k, changed, boxChanged, a: f, b: t.f, boxA: box, boxB: t.box });
  }
  return out;
}

// 有意改动白名单（canvas-button-unify 收口 2026-11-18 实测口径，派发方裁定）：
//   · 组（desktop/…/group）= 组规格对齐手机待办胶囊（作者令「多个按钮的组合…
//     使用手机端待办顶端右上角那样的样式」）：radius 10→999px、pad 4→0、gap 8→normal
//     + 盒高 50→48（盒宽随钮数不变：40+8 缝 ≡ 48 成员并排；底锚定死 ⇒ 盒 y 随高移）。
//   · 成员（desktop/…/member）= 成员规格对齐 .ta-pill-btn：radius 50%→999px、
//     color muted→frame-text-bright、border 1px 透明→0px none、尺寸 40→48、
//     hover 底 cv-hover-bg→transparent（胶囊成员零 hover 声明）、shadow float→none
//     （胶囊成员零投影，glass.css 共享面例外登记——单钮的 float 修复不进组成员）、
//     **transform hover matrix(1.05)→none / active matrix(0.96)→none**（胶囊成员
//     零 hover / 零按下形变，实测 .ta-pill-btn 两态 transform 均为 none；条内
//     「抬亮 + 按下缩放」档随组重设计退役 —— 本批唯一的行为改变，故必须列进
//     FACE_KEYS 由本表显式认领，不许它落在判定面之外）。
//   · 单钮回归修复（radius 999px / shadow float 自 glass.css 共享面供给）不在
//     本表 —— 修复的目的恰是让「改后↔基线」的 radius/shadow 差**归零**，
//     即不再出现 diff。本夹具（image pane）可测成员；source/ref/find 圆钮
//     不在本夹具渲染面，其修复读数走 geom spec 令牌对照。
//   · 手机待办组（mobile/…）零变化（参考系本体）。
// 其余任何计算值变化 = 回归。
const INTENDED_GROUP = new Set(['radius', 'pad', 'gap', 'h']);
const INTENDED_MEMBER = new Set(['radius', 'color', 'border', 'w', 'h', 'bg', 'shadow', 'transform']);
const INTENDED_BOX_GROUP = new Set(['h', 'y']);
const INTENDED_BOX_MEMBER = new Set(['w', 'h', 'x', 'y']);

async function main() {
  const { mod } = loadPlaywright();
  const pw = mod.playwright || mod;
  const { browser, label } = await launchBrowser(pw);
  const log = { baseWeb: BASE_WEB, currentWeb: webRoot(), browser: label };

  console.log(`base-web    = ${BASE_WEB}`);
  console.log(`current-web = ${webRoot()}`);
  const snapshots = {};
  for (const [tag, web, kind] of [['base-mobile', BASE_WEB, 'mobile'], ['base-desktop', BASE_WEB, 'desktop'],
    ['current-mobile', null, 'mobile'], ['current-desktop', null, 'desktop']]) {
    setWebRoot(web);
    snapshots[tag] = await scanPass(browser, kind);
    console.log(`scanned ${tag} (webRoot=${webRoot()}) found=${snapshots[tag].found}`);
  }
  await browser.close();
  setWebRoot(null);
  log.snapshots = snapshots;
  log.baseWeb = BASE_WEB;

  // ── A/B：改前 vs 改后 ────────────────────────────────────────────────────
  for (const kind of ['mobile', 'desktop']) {
    const a = snapshots[`base-${kind}`], b = snapshots[`current-${kind}`];
    if (!a.found || !b.found) { record(`${kind}-AB`, `改前/改后对照（${kind}）取到两族件`, false, `baseFound=${a.found} currentFound=${b.found}`); continue; }
    const diffs = diffRows(faceRows(a, kind), faceRows(b, kind));
    const groupDiffs = diffs.filter((d) => /group/.test(d.key));
    const memberDiffs = diffs.filter((d) => /member/.test(d.key));
    const otherDiffs = diffs.filter((d) => !/group|member/.test(d.key));
    const groupOk = groupDiffs.every((d) => d.changed.every((p) => INTENDED_GROUP.has(p))
      && d.boxChanged.every((p) => INTENDED_BOX_GROUP.has(p)));
    record(`${kind}-A`, `${kind}：组改前↔改后差恰为对齐令的 {${[...INTENDED_GROUP].join('/')}}（手机腿参考系零变化）`,
      groupOk && (kind === 'mobile' ? groupDiffs.length === 0 : true),
      groupDiffs.map((d) => `${d.key}: ${d.changed.join(',')}|box:${(d.boxChanged || []).join(',')}`).join('; ') || '组读数逐值相同');
    const memberOk = memberDiffs.every((d) => d.changed.every((p) => INTENDED_MEMBER.has(p))
      && d.boxChanged.every((p) => INTENDED_BOX_MEMBER.has(p)));
    record(`${kind}-B`, `${kind}：成员改前↔改后差恰为对齐令的 {${[...INTENDED_MEMBER].join('/')}}，其余零漂移`,
      memberOk && otherDiffs.length === 0 && (kind === 'mobile' ? memberDiffs.length === 0 : true),
      [...memberDiffs.map((d) => `${d.key}: ${d.changed.join(',')}|box:${(d.boxChanged || []).join(',')}`),
        ...otherDiffs.map((d) => `${d.key}: ${(d.changed || []).join(',')}`)].join('; ')
        || '成员与参考系读数逐值相同');
    log[`${kind}Diffs`] = diffs;
  }

  // ── C：两族组对齐（当前树）──────────────────────────────────────────────
  const mg = snapshots['current-mobile'].states.normal;
  const dg = snapshots['current-desktop'].states.normal;
  if (mg && mg.found && dg && dg.found) {
    const KEYS = ['radius', 'bg', 'bd', 'bdW', 'border', 'pad', 'gap'];
    const diffs = KEYS.filter((k) => String(mg.group.face[k]) !== String(dg.group.face[k]));
    record('C1', '两族组容器：radius / 玻璃 bg / filter / border / padding / gap 逐值相等',
      diffs.length === 0,
      diffs.length ? diffs.map((k) => `${k}: mobile「${mg.group.face[k]}」 ≠ desktop「${dg.group.face[k]}」`).join(' | ')
        : KEYS.map((k) => `${k}=${mg.group.face[k]}`).join(' | '));
    record('C2', '两族组成员圆钮：尺寸 / 圆角 / color 逐值相等',
      mg.members.length > 0 && dg.members.length > 0 &&
      mg.members.every((m) => m.box.w === dg.members[0].box.w && m.box.h === dg.members[0].box.h) &&
      mg.members.every((m) => m.face.radius === dg.members[0].face.radius && m.face.color === dg.members[0].face.color),
      `mobile member=${JSON.stringify({ w: mg.members[0]?.box.w, h: mg.members[0]?.box.h, radius: mg.members[0]?.face.radius, color: mg.members[0]?.face.color, svg: mg.members[0]?.svg })}`
      + ` | desktop member=${JSON.stringify({ w: dg.members[0]?.box.w, h: dg.members[0]?.box.h, radius: dg.members[0]?.face.radius, color: dg.members[0]?.face.color, svg: dg.members[0]?.svg })}`);
    for (const state of ['hover', 'active']) {
      const m = snapshots['current-mobile'].states[state], d = snapshots['current-desktop'].states[state];
      const same = m && d && m.found && d.found &&
        m.members.every((x) => x.face.transform === d.members[0].face.transform && x.face.bg === d.members[0].face.bg);
      record(`C3-${state}`, `两族组成员 ${state} 态读数相等（transform / bg）`,
        same, `mobile=${JSON.stringify(m?.members?.[0]?.face && { t: m.members[0].face.transform, bg: m.members[0].face.bg })} desktop=${JSON.stringify(d?.members?.[0]?.face && { t: d.members[0].face.transform, bg: d.members[0].face.bg })}`);
    }
  } else {
    record('C1-C3', '两族组当前树读数可取', false, `mobile=${!!mg?.found} desktop=${!!dg?.found}`);
  }

  const stamp = new Date().toISOString();
  const body = [
    `canvas-button-unify diff regression — ${stamp}`,
    `baseWeb=${BASE_WEB}`, `currentWeb=${log.currentWeb}`, `browser=${label}`,
    `viewports: mobile 375x812 | desktop 1280x800`,
    '',
    ...rows.map((r) => `${r.verdict}  ${r.id}  ${r.name}\n        ${r.readout}`),
    '',
    `RESULT ${pass}/${pass + fail} PASS, ${fail} FAIL`,
  ].join('\n');
  writeFileSync(join(OUT, 'canvas-button-unify-diff.log'), body + '\n');
  writeFileSync(join(OUT, 'canvas-button-unify-diff.json'), JSON.stringify({ stamp, ...log, rows, pass, fail }, null, 2));
  // 数值前后表
  const table = [];
  for (const kind of ['mobile', 'desktop']) {
    const a = snapshots[`base-${kind}`], b = snapshots[`current-${kind}`];
    if (!a?.found || !b?.found) continue;
    for (const [k, f, box] of faceRows(a, kind)) {
      const cur = faceRows(b, kind).find(([ck]) => ck === k);
      table.push([kind, k, ...FACE_KEYS.map((p) => String(f[p])), ...FACE_KEYS.map((p) => String(cur ? cur[1][p] : '-')), `${box.w}x${box.h}`]);
    }
  }
  writeFileSync(join(OUT, 'canvas-button-unify-diff-table.tsv'),
    ['viewport\tkey\t' + FACE_KEYS.map((k) => `before:${k}`).join('\t') + '\t' + FACE_KEYS.map((k) => `after:${k}`).join('\t') + '\tbox']
    .concat(table.map((r) => r.join('\t'))).join('\n') + '\n');
  console.log(`\nRESULT ${pass}/${pass + fail} PASS, ${fail} FAIL`);
  console.log(`log:   ${join(OUT, 'canvas-button-unify-diff.log')}`);
  console.log(`json:  ${join(OUT, 'canvas-button-unify-diff.json')}`);
  console.log(`table: ${join(OUT, 'canvas-button-unify-diff-table.tsv')}`);
  process.exitCode = fail ? 1 : 0;
}

main().catch((e) => { console.error('harness error:', e); process.exit(2); });
