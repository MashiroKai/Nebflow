#!/usr/bin/env node
// check-canvas-families.mjs — Canvas 单钮「已登记族」的**态层归属**台账与判据
// （canvas-button-unify 批补充 2026-11-18；第三轮 2026-11-18 改为**声明对实测**）。
//
// 为什么有这个判据：上一轮把 `.canvas-button` 收成纯身份钩子（零自有几何/材质），
// 几何因此**只能**来自各族类；但「哪一族的状态层（hover/按下/焦点）由谁供给」
// 当时没有台账 —— 于是「族只在别处定义了外观、态层没人认领」这件事不可见。
// 本文件把 27 个已登记族逐族落到归宿，并给出**实测**读数。
//
// ── 第三轮修的是什么（2026-11-18）────────────────────────────────────────
// 第一版台账写了 `FAMILIES[].to`（**声明**），却又用另一套规则现算 `to`（**实测**）
// 去写报告 —— 两者从不比较。于是台账里「声明 = 共享态层」的族，实测可能是
// 「族自有」甚至「无来源」，而脚本照样全绿（只有 K NOWN_UNCOVERED 一条兜底）。
// 换句话说：那份台账是**声明式**的，不是判据。
//
// 本版把三件事做成机器判据：
//   ① 实测口径写明并按文件逐条命中（见 `measure()`）；
//   ② `FAMILIES[].to`（声明）**必须落在实测集合里**，否则红 —— 声明与实测不符
//      就是红，不许「声明一套、报告一套」；
//   ③ 无态层来源的族只能进 `KNOWN_UNCOVERED`（具名 + 理由），否则红。
//
// ── 实测口径（`measure()`）────────────────────────────────────────────────
// 逐族在三份**共享态层文件**里找「族名出现在一条带状态伪类的选择器里」
// （`:hover` / `:active` / `:focus-visible`）：
//   css/flatButtons.css   → 归宿 `shared-flat-buttons`
//   css/glass.css         → 归宿 `shared-glass-round`（圆钮浮件档一族）
//   css/design-system.css → 归宿 `shared-ds-btn-base`（.ds-btn 参考实现）
// 命中可多个（一族同时被两层覆盖 = 合法）。三层皆未命中时：
//   族内（任意文件）自持 hover/active 声明         → `exempt`
//   其余（族名只在别处、态层由同元素同伴类供给）   → `companion`（必须在 KNOWN_UNCOVERED 具名）
//   连 companion 登记都没有                       → `uncovered` ⇒ 红（声明与实测皆无来源）
//
// 另外给出**改前/改后数值对照**（radius 与 hover 声明）：
//   --base <rev>  对照基线，缺省 `12eef94b1^`（canvas-button-unify 收编之前）。
//   逐族扫 CSS 源树（改前树用 `git show <rev>:<path>` 取，不起服务、不碰运行实例），
//   解析该族名下所有 `border-radius` 与 `:hover` 声明，令牌值沿 var() 链解析成
//   最终字面量。数字是**源码声明值**，不是浏览器计算值（计算值口径见
//   tests/canvas-button-unify-geom.spec.mjs）。此表只做对照，**不参与归宿判定**。
//
// 退出码：0 = 全绿；1 = 有违规；2 = 环境错误（git/读文件失败）。

import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = 'src/main/resources/web/css';
const baseFlag = process.argv.indexOf('--base');
const BASE = baseFlag > -1 ? process.argv[baseFlag + 1] : '12eef94b1^';
const outFlag = process.argv.indexOf('--out');
const OUT = outFlag > -1 ? process.argv[outFlag + 1] : join(ROOT, 'evidence', 'canvas-button-unify-supplement', 'families');

// ── 共享态层文件 → 归宿名（实测口径的唯一来源）──────────────────────────────
const SHARED_LAYERS = [
  { file: 'flatButtons.css', to: 'shared-flat-buttons' },
  { file: 'glass.css', to: 'shared-glass-round' },
  { file: 'design-system.css', to: 'shared-ds-btn-base' },
];

// ── 27 个已登记族（与 scripts/check-canvas-buttons.mjs REGISTERED_FAMILIES 同一名单）──
// `to` 是本批的**决定**；exempt/companion 的 reason 必须写清可核对的事实。
// 声明会与 measure() 的实测集合对撞，不符即红（第三轮起）。
const FAMILIES = [
  { family: 'canvas-source-toggle', to: 'shared-glass-round' },
  { family: 'canvas-ref-select', to: 'shared-glass-round' },
  { family: 'canvas-find-toggle', to: 'shared-glass-round' },
  { family: 'canvas-zoom-btn', to: 'shared-flat-buttons' },
  { family: 'canvas-pan-toggle', to: 'companion' },
  { family: 'canvas-pop-btn', to: 'exempt', reason: '卡片头弹出钮：族文件 split.css 内自带 hover/active 声明（本脚本实测），态层在族内；本批不收编以免与该族几何/落位规则同档相撞。' },
  { family: 'canvas-refback-btn', to: 'shared-flat-buttons' },
  { family: 'canvas-merge-btn', to: 'exempt', reason: '合并请求钮：族文件 split.css 内自带 hover/active 声明（本脚本实测），态层在族内；收编会与该族落位规则同档相撞。' },
  { family: 'canvas-tab-close', to: 'exempt', reason: '页签关闭钮：手机/桌面两腿外观由 .canvas-tab 内联段供给（hover 声明实测存在），触屏面无 hover 语义，收编会与页签命中盒规则同档相撞。' },
  { family: 'canvas-video-btn', to: 'shared-flat-buttons' },
  { family: 'deck-fab', to: 'companion' },
  { family: 'deck-card-pop', to: 'exempt', reason: '卡片头弹出钮：手机层 display:none 恒离场，桌面侧仅卡头悬浮态可见（split.css body.card-window 段），触屏面无 hover 语义。' },
  { family: 'deck-card-x', to: 'exempt', reason: '卡片头关闭钮：同上，手机层恒离场；桌面侧态层随 .card-window 段自带。' },
  { family: 'epub-nav-btn', to: 'exempt', reason: 'EPUB 阅读器翻页钮：触屏主用面（@media (hover:none) 档已写按下反馈），hover 语义在移动阅读面无消费场景。' },
  { family: 'epub-toc-item', to: 'exempt', reason: '非按钮面：目录行是列表项（role=listitem 语义），命中盒与选中态由 .epub-toc 行规则给出，不是按钮六态对象。' },
  { family: 'epub-menu-btn', to: 'exempt', reason: 'EPUB 菜单钮：与 epub-nav-btn 同族文件、同触屏档；hover 由族文件自带。' },
  { family: 'epub-anno-swatch', to: 'exempt', reason: '非按钮面：批注色板是色样（选中态 = 描边环），不是可按下控件；给它补 scale 按下态会篡改色样语义。' },
  { family: 'xlsx-sheet-tab', to: 'exempt', reason: '非按钮面：工作表页签是 tab（选中态 = 底缘/底色），态层由表格族自带。' },
  { family: 'g2-fab', to: 'shared-glass-round', reason: '基准圆钮类：**六态**由 glass.css 圆钮浮件档供给（本脚本实测该文件确有 .g2-fab:hover/:active/:focus-visible 选择器）；几何/材质沿用 .ds-btn 参考实现口径（同径同圆角同零投影，见 geom spec S5）。第三轮修正：初版台账写 shared-ds-btn-base，但 design-system.css 只给几何不给态层选择器（实测 ds=false），声明因此与实测不符。' },
  { family: 'ask-fab', to: 'shared-glass-round' },
  { family: 'note-fab', to: 'companion' },
  { family: 'lt-btn', to: 'companion' },
  { family: 'viewer-retry-btn', to: 'companion' },
  { family: 'nf-frame-nav-back', to: 'companion' },
  { family: 'glass-control', to: 'exempt', reason: '阅读面玻璃控件条内钮：族文件内自带 13 条 hover/active/focus-visible 声明（本脚本实测），且 split.css 面板段自带组内态层；本批不收编以免与预览面留白批同档相撞。' },
  { family: 'mf-cv-close', to: 'companion' },
  { family: 'mf-cv-stack', to: 'companion' },
];

// ── 态层**未覆盖**族（本批实测：既不在三份共享态层文件的状态选择器里、族内也
// 零 hover/active 声明）。它们全部是「与其他族共用同一元素」的族：元素上同时挂
// .g2-fab / .canvas-button 之类已覆盖类，态层由那个同伴类供给；本批不把「同伴类
// 供给」改写成自己的声明（那会造出第二份态层），但必须在此**具名登记 + 写明理由**，
// 否则红 —— 名单只能显式缩小，不许悄悄长大。
const KNOWN_UNCOVERED = {
  'canvas-pan-toggle': '非独立面：恒与 .canvas-zoom-btn 同元素（js/viewers/zoom.js:mkBtn 的 "canvas-button canvas-zoom-btn canvas-pan-toggle"），态层由 canvas-zoom-btn（共享态层）供给。',
  'deck-fab': '非独立面：js/canvas.js 发射点为 "deck-fab g2-fab"，态层由 g2-fab（.ds-btn 参考实现）供给。',
  'note-fab': '非独立面：js/askDock.js 发射点为 "ask-fab note-fab"（同一元素两名），态层由 ask-fab（族内自持）供给。',
  'lt-btn': '大文本阅读面控件条内钮：随条成组、以 .canvas-button 身份钩子 + viewer 族几何渲染，态层由阅读面条内规则供给；本批不动该族（避免与阅读面留白批同档相撞）。',
  'viewer-retry-btn': '非独立面：仅失败态在 image/mobi 阅读面出现（js/viewers/image.js、mobi.js），随 viewers/shared.js 的玻璃钮族渲染，态层由该族供给。',
  'nf-frame-nav-back': '非独立面：HTML 预览 iframe 导航钮恒与 .glass-control 同元素（js/viewers/html.js），态层由 .glass-control 供给。',
  'mf-cv-close': '非独立面：js/mobile/navController.js 发射点为 "mf-cv-close g2-fab"，态层由 g2-fab（.ds-btn 参考实现）供给。',
  'mf-cv-stack': '非独立面：js/mobile/navController.js 发射点为 "mf-cv-stack g2-fab"，态层由 g2-fab（.ds-btn 参考实现）供给。',
};

const failures = [];
const facts = [];

const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '');
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const readTree = (rev) => {
  const files = rev
    ? execFileSync('git', ['ls-tree', '-r', '--name-only', rev, CSS_DIR], { cwd: ROOT, encoding: 'utf8' }).trim().split('\n')
    : readdirSync(join(ROOT, CSS_DIR)).filter((f) => f.endsWith('.css')).map((f) => `${CSS_DIR}/${f}`);
  const out = {};
  for (const f of files) {
    if (!f.endsWith('.css')) continue;
    out[f] = rev
      ? execFileSync('git', ['show', `${rev}:${f}`], { cwd: ROOT, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
      : readFileSync(join(ROOT, f), 'utf8');
  }
  return out;
};

// ── var() 链解析（令牌 → 最终字面量；跨文件按 stand-tokens.css 优先）──────────
function buildTokens(tree) {
  const tokens = new Map();
  for (const [file, src] of Object.entries(tree)) {
    for (const m of stripComments(src).matchAll(/(--[a-z0-9-]+)\s*:\s*([^;{}]+);/gi)) {
      if (!tokens.has(m[1])) tokens.set(m[1], m[2].trim());
    }
  }
  return tokens;
}
function resolve(value, tokens, depth = 0) {
  if (depth > 8) return value;
  const m = /^var\(\s*(--[a-z0-9-]+)\s*(?:,\s*([^)]*))?\)$/i.exec(value.trim());
  if (!m) return value;
  const hit = tokens.get(m[1]);
  return hit ? resolve(hit, tokens, depth + 1) : (m[2] || value).trim();
}

// ── 逐族取「radius 声明」与「hover 声明」（改前/改后对照表用）────────────────
function scanFamily(tree, family) {
  const tokens = buildTokens(tree);
  const radii = [];
  const hovers = [];
  const re = new RegExp(`(?:^|[\\s,>+~(])[^\\s,>+~()]*\\.${escapeRe(family)}(?=[\\s,{:.\\[]|$)`);
  for (const [file, src] of Object.entries(tree)) {
    const clean = stripComments(src);
    for (const m of clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      if (!re.test(m[1])) continue;
      const sel = m[1].trim().replace(/\s+/g, ' ').slice(-70);
      const radius = /(?:^|[;\s{])border-radius\s*:\s*([^;]+)/.exec(m[2]);
      if (radius) radii.push({ file, sel, value: resolve(radius[1].trim(), tokens) });
      if (/:hover|:active|:focus-visible/.test(m[1])) {
        const bg = /(?:^|[;\s{])background(?:-color)?\s*:\s*([^;]+)/.exec(m[2]);
        const tr = /(?:^|[;\s{])transform\s*:\s*([^;]+)/.exec(m[2]);
        hovers.push({
          file, sel, state: (m[1].match(/:(hover|active|focus-visible)/) || [])[1],
          bg: bg ? resolve(bg[1].trim(), tokens) : null,
          transform: tr ? resolve(tr[1].trim(), tokens) : null,
        });
      }
    }
  }
  return { radii, hovers };
}

// ── 实测：族名是否出现在某份共享态层文件的**状态选择器**里 ───────────────────
// 返回命中的归宿集合。判据只看「选择器含族名 + 含状态伪类」，不看声明体
// （体是几何/材质还是别的，不影响「态层由谁供给」这句话）。
function sharedStateHits(sources, family) {
  const re = new RegExp('\\.' + escapeRe(family) + '(?![A-Za-z0-9_-])');
  const hits = [];
  for (const { file, to } of SHARED_LAYERS) {
    const src = stripComments(sources[file] || '');
    let n = 0;
    for (const m of src.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      if (!re.test(m[1])) continue;
      if (/:hover|:active|:focus-visible/.test(m[1])) n += 1;
    }
    if (n) hits.push({ to, file, n });
  }
  return hits;
}

// ── 交叉校验：名单与 check-canvas-buttons.mjs 一致 ──────────────────────────
const gateSrc = readFileSync(join(ROOT, 'scripts/check-canvas-buttons.mjs'), 'utf8');
const listMatch = /const REGISTERED_FAMILIES = \[([\s\S]*?)\];/.exec(gateSrc);
if (!listMatch) { console.error('check-canvas-families: 取不到 check-canvas-buttons.mjs 的族名单'); process.exit(2); }
const gateFamilies = [...listMatch[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
const mine = FAMILIES.map((f) => f.family);
if (gateFamilies.length !== 27) failures.push(`族名单不是 27 个（实测 ${gateFamilies.length}）`);
for (const f of gateFamilies) if (!mine.includes(f)) failures.push(`族 ${f} 在本台账里没有归宿`);
for (const f of mine) if (!gateFamilies.includes(f)) failures.push(`本台账多出未登记族 ${f}`);
if (new Set(mine).size !== mine.length) failures.push('本台账存在重复族名');
for (const name of Object.keys(KNOWN_UNCOVERED)) if (!gateFamilies.includes(name)) failures.push(`KNOWN_UNCOVERED 里的 ${name} 不是已登记族`);

const sharedSources = {};
for (const { file } of SHARED_LAYERS) sharedSources[file] = readFileSync(join(ROOT, CSS_DIR, file), 'utf8');

let before = {}, after = {};
try { before = readTree(BASE); } catch (e) { console.error(`check-canvas-families: 基线树 ${BASE} 取不到（${e.message.split('\n')[0]}）`); process.exit(2); }
after = readTree(null);

const registry = [];
const counts = {};
for (const entry of FAMILIES) {
  const { family } = entry;
  const b = scanFamily(before, family);
  const a = scanFamily(after, family);
  const ownStates = a.hovers.length;
  const hits = sharedStateHits(sharedSources, family);

  // 实测归宿集合（声明必须落在其中；见文件头「实测口径」）。
  let measured;
  if (hits.length) measured = hits.map((h) => h.to);
  else if (ownStates) measured = ['exempt'];
  else if (KNOWN_UNCOVERED[family]) measured = ['companion'];
  else measured = ['uncovered'];

  // ① 声明对实测：不符即红（第三轮的核心判据）。
  if (!measured.includes(entry.to)) {
    failures.push(`${family}: 台账声明 ${entry.to}，实测 ${measured.join('|')} —— 声明与实测不符`);
  }
  // ② 无态层来源的族不许「装作有归宿」：只能进 KNOWN_UNCOVERED 具名登记。
  if (measured[0] === 'uncovered') {
    failures.push(`${family}: 无态层来源且未在 KNOWN_UNCOVERED 具名登记（不在三份共享态层文件的状态选择器里、族内零 hover/active 声明）`);
  }
  // ③ companion 必须有具名理由。
  if (measured.includes('companion') && !KNOWN_UNCOVERED[family]) {
    failures.push(`${family}: 实测 companion 但 KNOWN_UNCOVERED 无登记`);
  }

  const reason = entry.reason || KNOWN_UNCOVERED[family] || (ownStates
    ? `族文件自持 ${ownStates} 条 hover/active/focus-visible 声明（本脚本实测），态层在族内；本批不收编以免与该族几何/落位规则同档相撞。`
    : null);
  if (!reason || reason.trim().length < 24) failures.push(`${family}: ${entry.to} 必须写明理由（≥24 字）`);

  const radiusB = [...new Set(b.radii.map((r) => r.value))];
  const radiusA = [...new Set(a.radii.map((r) => r.value))];
  const hoverB = b.hovers.map((h) => `${h.state}:${h.bg || h.transform || '-'}`);
  const hoverA = a.hovers.map((h) => `${h.state}:${h.bg || h.transform || '-'}`);
  const stateLayerFile = entry.to === 'shared-flat-buttons' ? 'css/flatButtons.css'
    : entry.to === 'shared-glass-round' ? 'css/glass.css'
      : entry.to === 'shared-ds-btn-base' ? 'css/design-system.css (.ds-btn 参考实现)'
        : entry.to === 'companion' ? '(同元素同伴类)' : '(族自有声明)';
  const rec = {
    family, to: entry.to, measured, reason, stateLayerFile,
    before: { radius: radiusB, hover: hoverB },
    after: { radius: radiusA, hover: hoverA },
    radiusChanged: JSON.stringify(radiusB) !== JSON.stringify(radiusA),
    hoverChanged: JSON.stringify(hoverB) !== JSON.stringify(hoverA),
    ownStateCount: ownStates,
    sharedRef: {
      flatButtons: hits.some((h) => h.to === 'shared-flat-buttons'),
      glassRound: hits.some((h) => h.to === 'shared-glass-round'),
      designSystem: hits.some((h) => h.to === 'shared-ds-btn-base'),
    },
  };
  registry.push(rec);
  counts[entry.to] = (counts[entry.to] || 0) + 1;
  facts.push(`${family}  ${entry.to}${measured.length > 1 ? ` (亦实测: ${measured.filter((m) => m !== entry.to).join('|')})` : ''}`
    + `  radius ${JSON.stringify(radiusB)} → ${JSON.stringify(radiusA)}`
    + `  hover(n=${hoverB.length}→${hoverA.length}) ${JSON.stringify(hoverB)} → ${JSON.stringify(hoverA)}`
    + `  [flat=${rec.sharedRef.flatButtons} glass=${rec.sharedRef.glassRound} ds=${rec.sharedRef.designSystem}]`);
}

mkdirSync(OUT, { recursive: true });
// ── 身份钩子 `.canvas-button` 自身的前后对照（本批真正被收编的那一层）──────────
// 上一批改的不是任何**族**的 radius/hover，而是钩子本身：`.canvas-button.canvas-button`
// 那份私有面（border-radius: var(--ds-radius-pill) / box-shadow: var(--ds-shadow-float)
// + hover 块）被摘除。逐族扫描看不见这条（选择器不以族名收尾），所以单列一行。
const hookB = scanFamily(before, 'canvas-button');
const hookA = scanFamily(after, 'canvas-button');
const hookLine = 'hook .canvas-button  radius ' + JSON.stringify([...new Set(hookB.radii.map((r) => r.value))])
  + ' → ' + JSON.stringify([...new Set(hookA.radii.map((r) => r.value))])
  + '  hover ' + JSON.stringify(hookB.hovers.map((h) => `${h.state}:${h.bg || h.transform || '-'}`))
  + ' → ' + JSON.stringify(hookA.hovers.map((h) => `${h.state}:${h.bg || h.transform || '-'}`));

const jsonPath = join(OUT, 'family-audit.json');
const logPath = join(OUT, 'family-audit.log');
const bucketLine = Object.entries(counts).map(([k, v]) => `${k}=${v}`).join('  ');
const report = [
  `canvas-button family audit — base=${BASE}`,
  `归宿（声明 = 实测）：${bucketLine}  合计=${registry.length}`,
  `radius 改动的族：${registry.filter((r) => r.radiusChanged).map((r) => r.family).join(', ') || '(无)'}`,
  `hover/态层改动的族：${registry.filter((r) => r.hoverChanged).map((r) => r.family).join(', ') || '(无)'}`,
  '',
  hookLine,
  '',
  "逐族：归宿（声明=实测）/ radius 改前→改后 / hover 改前→改后 / 共享层命中",
  ...facts,
].join('\n');
writeFileSync(jsonPath, JSON.stringify({ base: BASE, counts, registry, failures }, null, 2));
writeFileSync(logPath, report + '\n');
console.log(report);
if (failures.length) {
  console.error(`\ncanvas-families FAIL (${failures.length} hits)\n` + failures.join('\n'));
  process.exitCode = 1;
} else {
  console.log(`\ncanvas-families PASS: 27 族各有归宿，声明与实测一致（${bucketLine}）`);
}
console.log(`json: ${jsonPath}`);
