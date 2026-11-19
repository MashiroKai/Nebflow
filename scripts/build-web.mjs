#!/usr/bin/env node
// build-web.mjs - P1 production bundle build (esbuild).
//
// Input : src/main/resources/web/  (source tree - dev serves this directly)
// Output: build/web-dist/          (prod tree - sbt optionally packs it; the
//         runtime serves THIS tree when web-dist/index.html is on the classpath)
//
// Layout (design doc /tmp/esbuild-design.md decision 1/5):
//   index.html                 rewritten: 16 app CSS links -> 1 bundle link,
//                              js/main.js -> assets/app-[hash].js, vendor kept
//   assets/app-[hash].js       JS entry chunk (esbuild splitting, esm)
//   assets/mobile/bootstrap-[hash].js
//                              mobile-layer entry chunk (#510). js/mobile/
//                              bootstrap.js is a SECOND <script type="module">
//                              entry in index.html (mobile-ui-refactor), so it
//                              is unreachable from main.js and a single-entry
//                              build both orphan-guarded it red and shipped a
//                              dist whose mobile hook 404'd. Bundled as a
//                              second esbuild entry; its dynamic controller
//                              imports stay lazy via the shared chunks/ pool
//                              (splitting dedupes modules reachable from both
//                              entries into shared chunks).
//   assets/chunks/*-[hash].js  lazy chunks - every literal dynamic import()
//                              keeps its lazy boundary (viewers, modal, ...)
//   assets/app-[hash].css      app CSS concat in index.html link order
//                              (source link order, including responsive overrides after the design-system
//                              override layer; esbuild's import graph is NOT
//                              trusted for ordering, links are concatenated
//                              explicitly)
//   vendor/**, favicon*, ...   copied through untouched (window-global script
//                              contract; monaco self-hosts via its AMD loader)
//
// Orphan guard: every web/js/**/*.js source file MUST appear in esbuild's
// metafile inputs - a file unreachable from the entry graph is silently
// dropped by bundling, so the build fails hard and lists them. Exemptions
// require an explicit justification below (same discipline as C1).

import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SRC = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'build', 'web-dist');

// Files under web/js that intentionally never enter the module graph.
// Every entry MUST carry a reason.
const ORPHAN_EXEMPTIONS = new Map([
  // ['js/example.js', 'reason'],
  // imgpreview decoupled package (eaa9221c9) — friends/group family, flag-sealed; wiring intentionally deferred pending neblink-server attachment DTO leg. Do not delete, do not wire while sealed.
  ['js/attachImagePreview.js', 'imgpreview decoupled package (eaa9221c9): flag-sealed family, wiring intentionally deferred (see comment above)'],
  ['js/attachPreviewField.js', 'imgpreview decoupled package (eaa9221c9): flag-sealed family, wiring intentionally deferred (see comment above)'],
  // flowMap/badges.js: flowMapTab.js's import was replaced by the fmNodeCard.js
  // single-source pin ⇒ zero live importers — a true orphan. Do not re-wire (the
  // badge family is single-sourced in fmNodeCard.js per the T6 pin).
  ['js/flowMap/badges.js', 'flowmaptab-retire (#1228): flowMapTab.js import dropped in favour of the fmNodeCard.js single-source pin ⇒ zero live importers in tree; module kept on disk pending the #1229 batch'],
  // s14-executor-userface-retire (ui-train-threefix A, 2026-10-07): the last two
  // entry points (sidebar settings panel-entry button + /agents command) left the
  // user faces with their locale keys; the Canvas-tab panel module is intentionally
  // out of the bundle graph. ExecutorRegistry/detect (scala) untouched — file kept
  // on disk pending the executor-panel deletion batch (same shape as flowMapTab
  // above: do not re-wire; do not delete early).
  ['js/executorsPanel.js', 's14-executor-userface-retire (ui-train-threefix A): settings entry + /agents command removed, module disconnected pending the executor-panel deletion batch'],
  // tasklist-flowmap batch (2026-10-10): the L1 delegate view switched to the
  // flowmap engine face (delegateFlowMap.js) — the engine's .fm-detail card is the
  // node detail layer now (its diff section mounts via flowMapArchive), so this
  // card-list-era entry shim has zero live importers. Kept on disk, do not re-wire.
  ['js/chainDetailEntry.js', 'tasklist-flowmap batch (2026-10-10): L1 switched to the flowmap engine face; the fm-detail card supersedes this shim — zero live importers, kept on disk'],
]);

// ── 1. Clean output ────────────────────────────────────────────
rmSync(OUT, { recursive: true, force: true });
mkdirSync(join(OUT, 'assets'), { recursive: true });

// ── 2. Parse index.html asset references ───────────────────────
const indexSrc = readFileSync(join(SRC, 'index.html'), 'utf8');
const linkRe = /<link\s+rel="stylesheet"\s+href="([^"]+)"[^>]*>/g;
const allCssLinks = [...indexSrc.matchAll(linkRe)].map(m => m[1]);
const appCss = allCssLinks.filter(h => h.startsWith('css/'));
const vendorCss = allCssLinks.filter(h => !h.startsWith('css/'));
if (appCss.length === 0) throw new Error('no app css/ links found in index.html');
// Preserve the live source cascade, including responsive overrides after sapphire.
// Reordering CSS while bundling would change the production layout.
if (!appCss.includes('css/sapphire.css')) throw new Error('missing sapphire design-system layer');
// CSS orphan guard: every web/css/*.css must be linked from index.html -
// an unlinked stylesheet would be silently dropped from the bundle.
const cssOnDisk = readdirSync(join(SRC, 'css')).filter(f => f.endsWith('.css')).map(f => `css/${f}`);
const cssOrphans = cssOnDisk.filter(f => !appCss.includes(f));
if (cssOrphans.length > 0) {
  throw new Error(`unlinked stylesheets (would vanish from the bundle): ${cssOrphans.join(', ')} - link them in index.html or delete them`);
}

// ── 3. JS bundle (splitting preserves lazy boundaries) ─────────
// FRIENDS RELEASE STRIP MARKER — a REUSABLE OPT-IN SWITCH (author ruling
// 2026-09-14, friend-gate unseal D1②): the mechanism is kept, the DEFAULT is
// flipped. Every bundle produced HERE is a CI/CD release artifact — the ONLY
// build step the frontend has; local dev (sbt run) serves the source tree
// directly and never passes through this file, so it keeps dev-tree semantics.
//   · default (no flag)   = UNSEALED — the marker is NOT injected, so the
//     inline guard in featureFlags.js falls through to the dev config chain
//     and friends stay VISIBLE in the shipped bundle.
//   · `--strip-friends`   = RE-SEAL — the marker is injected via esbuild
//     `define`; esbuild folds `if (true === true)` and DCE strips the dev
//     branch (friendsEnabled() → physical `return false` in the bundle).
// Both forms are asserted by tests/release-strip-friends.static.mjs
// (`--expect present` | `--expect stripped`).
// Member-expression define (same shape as process.env.NODE_ENV); the empty
// define map is the no-injection form.
const STRIP_FRIENDS = process.argv.includes('--strip-friends');
const RELEASE_DEFINES = STRIP_FRIENDS
  ? { 'window.__NEBFLOW_RELEASE__': 'true' }
  : {};
const result = await esbuild.build({
  // Two entry points (see layout block above): the desktop app entry plus the
  // mobile-layer bootstrap hook — both are loaded directly by index.html via
  // <script type="module">, so both MUST be roots of the bundle graph.
  entryPoints: [
    { in: join(SRC, 'js', 'main.js'), out: 'app' },
    { in: join(SRC, 'js', 'mobile', 'bootstrap.js'), out: 'mobile/bootstrap' },
  ],
  bundle: true,
  splitting: true,
  format: 'esm',
  target: 'es2022',
  minify: true,
  sourcemap: 'linked',
  metafile: true,
  define: RELEASE_DEFINES,
  outdir: join(OUT, 'assets'),
  // [dir]/[name]: 'app' -> app-[hash].js (unchanged flat layout),
  // 'mobile/bootstrap' -> mobile/bootstrap-[hash].js (assets/ subtree, served
  // by the /assets/** wildcard route in dist mode).
  entryNames: '[dir]/[name]-[hash]',
  chunkNames: 'chunks/[name]-[hash]',
  logLevel: 'warning',
});
if (result.warnings.length > 0) {
  for (const w of result.warnings) console.error('esbuild warning:', w.text, w.location?.file || '');
  throw new Error(`esbuild produced ${result.warnings.length} warning(s) - treat as failure`);
}
// NOTE: with splitting, every dynamically-imported chunk also carries an
// entryPoint flag (it is a dynamic entry). Match the real entry by path.
const entryOutputUrl = (entrySuffix) => {
  const found = Object.entries(result.metafile.outputs)
    .find(([, o]) => (o.entryPoint || '').replace(/\\/g, '/').endsWith(entrySuffix))?.[0];
  if (!found) throw new Error(`metafile has no ${entrySuffix} entry output`);
  return relative(OUT, found).split('\\').join('/');
};
const jsEntryUrl = entryOutputUrl('/js/main.js');
// Mobile bootstrap entry (#510): emitted under assets/mobile/, rewritten into
// the second <script type="module"> tag below.
const mobileEntryUrl = entryOutputUrl('/js/mobile/bootstrap.js');

// ── 4. Orphan guard: every source module under web/js must be in metafile inputs ──
// B0-4（G4）：改造前这里只认 `.js`（`e.name.endsWith('.js')`），任何 `.mjs` /
// `.ts` / `.vue` 会**静默掉出**「每个源文件必须在打包图里」的保护 —— 一个未来
// 引入的 `.ts` 被谁 import 都无所谓，因为它压根不在被审计的集合里。现在按
// 源扩展名清单收，声明文件（`*.d.ts`）除外：它没有运行时实体，天然不进图。
const SOURCE_EXTS = ['.js', '.mjs', '.cjs', '.ts', '.tsx', '.vue'];
const isSourceModule = (name) => SOURCE_EXTS.some((x) => name.endsWith(x)) && !name.endsWith('.d.ts');
const jsFiles = [];
(function walk(d) {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (isSourceModule(e.name)) jsFiles.push(relative(SRC, p).split('\\').join('/'));
  }
})(join(SRC, 'js'));
const inputs = new Set(
  Object.keys(result.metafile.inputs).map(k => {
    // metafile keys are relative to the working dir the build ran in; make
    // them web/-relative to compare with the walk.
    const abs = join(ROOT, k);
    return relative(SRC, abs).split('\\').join('/');
  })
);
const orphans = jsFiles.filter(f => !inputs.has(f) && !ORPHAN_EXEMPTIONS.has(f));
if (orphans.length > 0) {
  console.error('ORPHAN SOURCE FILES (reachable from dev server but NOT in the bundle):');
  for (const f of orphans) console.error('  ' + f);
  console.error('Fix: import it from the graph, delete it, or add an explicit ORPHAN_EXEMPTIONS entry with a reason.');
  process.exit(1);
}

// 反向审计（B0-4 第二半）：产物里**每一个** JS 都必须能在 metafile 里找到自己的
// 登记。正向审计只保证「源文件都进了图」，管不住「产物里多出一个没人登记的
// 文件」（历史遗留、手工拷贝、构建半途而废留下的残渣）。范围限定 assets/：
// copy-through 的 vendor/ 与 sw.js 不经过打包图，属于另一条纪律。
const emitted = new Set(
  Object.keys(result.metafile.outputs).map(k => relative(OUT, join(ROOT, k)).split('\\').join('/'))
);
const strays = [];
(function walkOut(d) {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name);
    if (e.isDirectory()) walkOut(p);
    else if (e.name.endsWith('.js')) {
      const rel = relative(OUT, p).split('\\').join('/');
      if (!emitted.has(rel)) strays.push(rel);
    }
  }
})(join(OUT, 'assets'));
if (strays.length > 0) {
  console.error('UNREGISTERED JS IN DIST (present in build output but absent from the esbuild metafile):');
  for (const f of strays) console.error('  ' + f);
  console.error('Fix: remove the stale file or make the build emit it — dist must be explainable by the metafile.');
  process.exit(1);
}

// ── 5. CSS bundle: explicit concat in index.html link order ────
// (Vendor css stays as separate links - katex/highlight reference fonts via
// relative url() that would rebase wrongly from assets/.)
const concat = appCss.map(h => `/* ==== ${h} ==== */\n` + readFileSync(join(SRC, h), 'utf8')).join('\n');
// 只压空白，**不压语法**（2026-11-01 视觉闸 dist 形态红点归因，见下）。
// `minify: true` 会顺带把 `rgba(0,0,0,.12)` 重写成 8 位十六进制 `#0000001f`：
// 0x1f=31/255=0.1216 ≠ 0.12，即 minifier 把色彩的 **alpha 改写到 8bit 网格**上，
// 实测打包 CSS 里出现 595 处这种改写（源 CSS 自带 8 位 hex 仅 36 处）。单处误差
// ≤0.002 肉眼不可见，但渲染结果是 ±1~2/255 的像素差 —— 恰好落在 `deck-cards`
// 的平滑渐变卡面上：同一份源码，源树形态 28/28 逐字节 SAME，产物形态固定
// 73px(dark)/1477px(light) DIFF，且关掉 backdrop-filter、强制作废合成层栅格、
// 再等 3 秒都纹丝不动（= 不是未收敛，是**字节级色彩改写**）。
// 结论：产物必须与"被验证的那一形态"**同像素**，否则「验证对象 = 运行对象」这条
// 断言在色彩一级就不成立。代价 = CSS 606,358 → 618,065 字节（+1.9%），换来闸
// 一套基线同时守住源树与产物两种形态（tolerance 仍为 0，不放宽）。
const minCss = await esbuild.transform(concat, { loader: 'css', minifyWhitespace: true, target: 'es2022' });
// 色彩字面量保真断言：`minify: true` 一旦被重新启用（或 esbuild 改了默认），
// rgba() 会被重写成 8 位 hex，色彩 alpha 落到 8bit 网格上 —— 见上面那条注释的
// 实测数据。这里用「字数守恒」把它钉住：产物里的 8 位 hex 处数必须等于源 CSS
// 的处数，且 rgb()/hsl() 函数色处数也必须守恒（语法压缩会把函数色转成 hex）。
// 违反即构建失败，不允许产物悄悄改变被验证那一形态的像素。
{
  const countHex8 = (s) => (s.match(/#[0-9a-fA-F]{8}(?![\da-fA-F])/g) || []).length;
  const countFnColor = (s) => (s.match(/\b(?:rgba?|hsla?)\(/gi) || []).length;
  // 注释里也会出现 `rgba(...)` 字样，而空白压缩会整条删注释 —— 先把源侧注释剥掉
  // 再数，否则这条断言测的是"注释条数"而不是"色彩字面量"。
  const sourceCode = concat.replace(/\/\*[\s\S]*?\*\//g, '');
  const before = { hex8: countHex8(sourceCode), fn: countFnColor(sourceCode) };
  const after = { hex8: countHex8(minCss.code), fn: countFnColor(minCss.code) };
  if (before.hex8 !== after.hex8 || before.fn !== after.fn) {
    throw new Error(
      `css color literal drift: source {hex8:${before.hex8}, fn:${before.fn}} -> bundle {hex8:${after.hex8}, fn:${after.fn}}` +
      ' — CSS 压缩不得改写色彩字面量（会让产物像素与源树形态差 ±1~2/255，视觉闸 dist 形态随即出现无法归因的红点）'
    );
  }
}
const cssHash = createHash('sha256').update(minCss.code).digest('hex').slice(0, 8);
const cssName = `app-${cssHash}.css`;
writeFileSync(join(OUT, 'assets', cssName), minCss.code);

// ── 6. Rewrite index.html ──────────────────────────────────────
let html = indexSrc;
// Replace the FIRST app css link with the bundle link; drop the rest.
let firstDone = false;
html = html.replace(linkRe, (match, href) => {
  if (!href.startsWith('css/')) return match; // vendor css kept as-is
  if (firstDone) return '';
  firstDone = true;
  return `<link rel="stylesheet" href="assets/${cssName}">`;
});
// Entry module -> bundled entry.
html = html.replace(
  /<script\s+type="module"\s+src="js\/main\.js"><\/script>/,
  `<script type="module" src="${jsEntryUrl}"></script>`
);
// Mobile bootstrap hook (#510) -> its bundled entry (same discipline).
html = html.replace(
  /<script\s+type="module"\s+src="js\/mobile\/bootstrap\.js"><\/script>/,
  `<script type="module" src="${mobileEntryUrl}"></script>`
);
if (!html.includes(`assets/${cssName}`)) throw new Error('index.html rewrite failed: css bundle link missing');
if (!html.includes(jsEntryUrl)) throw new Error('index.html rewrite failed: js entry missing');
if (!html.includes(mobileEntryUrl)) throw new Error('index.html rewrite failed: mobile bootstrap entry missing');
if (html.includes('src="js/main.js"') || html.includes('src="js/mobile/bootstrap.js"')) {
  throw new Error('index.html rewrite failed: unbundled script reference survived');
}
writeFileSync(join(OUT, 'index.html'), html);

// ── 7. Copy-through: everything except js/, css/, index.html ───
// (vendor *.min.js + monaco tree + fonts + icons - the window-global script
// and AMD loader contracts stay byte-identical.)
for (const entry of readdirSync(SRC, { withFileTypes: true })) {
  if (entry.name === 'js' || entry.name === 'css' || entry.name === 'index.html') continue;
  cpSync(join(SRC, entry.name), join(OUT, entry.name), { recursive: true });
}

// ── 8. Report ──────────────────────────────────────────────────
const chunks = Object.keys(result.metafile.outputs).filter(o => o.includes('chunks/'));
console.log(`build-web OK -> ${relative(ROOT, OUT)}/`);
console.log(`  js entry : ${jsEntryUrl}`);
console.log(`  mobile   : ${mobileEntryUrl} (bootstrap hook, #510)`);
console.log(`  chunks   : ${chunks.length} lazy chunks (dynamic import boundaries preserved)`);
console.log(`  css      : assets/${cssName} (${appCss.length} files concatenated in source link order)`);
console.log(`  js files : ${jsFiles.length} sources (${SOURCE_EXTS.join('/')}), all in bundle graph (orphans: 0, exemptions: ${ORPHAN_EXEMPTIONS.size})`);
console.log(`  reverse  : ${emitted.size} registered outputs, 0 unregistered js under assets/`);
console.log(`  vendor   : copied through (${vendorCss.length} vendor css links kept separate)`);
console.log(`  form     : ${STRIP_FRIENDS ? 'SEALED (--strip-friends: release marker injected, dev branch DCE-stripped)' : 'UNSEALED (default: no release marker, friends visible)'}`);
