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

// ── 4. Orphan guard: every web/js/**/*.js must be in metafile inputs ──
const jsFiles = [];
(function walk(d) {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.js')) jsFiles.push(relative(SRC, p).split('\\').join('/'));
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
  console.error('ORPHAN JS FILES (reachable from dev server but NOT in the bundle):');
  for (const f of orphans) console.error('  ' + f);
  console.error('Fix: import it from the graph, delete it, or add an explicit ORPHAN_EXEMPTIONS entry with a reason.');
  process.exit(1);
}

// ── 5. CSS bundle: explicit concat in index.html link order ────
// (Vendor css stays as separate links - katex/highlight reference fonts via
// relative url() that would rebase wrongly from assets/.)
const concat = appCss.map(h => `/* ==== ${h} ==== */\n` + readFileSync(join(SRC, h), 'utf8')).join('\n');
const minCss = await esbuild.transform(concat, { loader: 'css', minify: true, target: 'es2022' });
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
console.log(`  js files : ${jsFiles.length} sources, all in bundle graph (orphans: 0, exemptions: ${ORPHAN_EXEMPTIONS.size})`);
console.log(`  vendor   : copied through (${vendorCss.length} vendor css links kept separate)`);
console.log(`  form     : ${STRIP_FRIENDS ? 'SEALED (--strip-friends: release marker injected, dev branch DCE-stripped)' : 'UNSEALED (default: no release marker, friends visible)'}`);
