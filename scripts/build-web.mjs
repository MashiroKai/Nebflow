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
//   assets/chunks/*-[hash].js  lazy chunks - every literal dynamic import()
//                              keeps its lazy boundary (viewers, modal, ...)
//   assets/app-[hash].css      app CSS concat in index.html link order
//                              (sapphire.css LAST - it is the design-system
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
// Hard guard for the design-system contract: sapphire.css is the standard
// layer and must stay LAST so equal-specificity conflicts resolve to it.
if (appCss[appCss.length - 1] !== 'css/sapphire.css') {
  throw new Error(`sapphire.css must be the last app css link in index.html (found: ${appCss[appCss.length - 1]})`);
}
// CSS orphan guard: every web/css/*.css must be linked from index.html -
// an unlinked stylesheet would be silently dropped from the bundle.
const cssOnDisk = readdirSync(join(SRC, 'css')).filter(f => f.endsWith('.css')).map(f => `css/${f}`);
const cssOrphans = cssOnDisk.filter(f => !appCss.includes(f));
if (cssOrphans.length > 0) {
  throw new Error(`unlinked stylesheets (would vanish from the bundle): ${cssOrphans.join(', ')} - link them in index.html or delete them`);
}

// ── 3. JS bundle (splitting preserves lazy boundaries) ─────────
// RELEASE STRIP MARKER (author ruling 2026-09-10, friends feature): every
// bundle produced HERE is a CI/CD release artifact — the ONLY build step the
// frontend has. Local dev (sbt run) serves the source tree directly and never
// passes through this file, so it keeps dev-tree semantics. featureFlags.js
// reads the marker inline; esbuild folds `if (true === true)` and DCE strips
// the dev branch (friendsEnabled() → physical `return false` in the bundle).
// Member-expression define (same shape as process.env.NODE_ENV).
const RELEASE_DEFINES = { 'window.__NEBFLOW_RELEASE__': 'true' };
const result = await esbuild.build({
  entryPoints: [join(SRC, 'js', 'main.js')],
  bundle: true,
  splitting: true,
  format: 'esm',
  target: 'es2022',
  minify: true,
  sourcemap: 'linked',
  metafile: true,
  define: RELEASE_DEFINES,
  outdir: join(OUT, 'assets'),
  entryNames: 'app-[hash]',
  chunkNames: 'chunks/[name]-[hash]',
  logLevel: 'warning',
});
if (result.warnings.length > 0) {
  for (const w of result.warnings) console.error('esbuild warning:', w.text, w.location?.file || '');
  throw new Error(`esbuild produced ${result.warnings.length} warning(s) - treat as failure`);
}
// NOTE: with splitting, every dynamically-imported chunk also carries an
// entryPoint flag (it is a dynamic entry). Match the real entry by path.
const jsEntry = Object.entries(result.metafile.outputs)
  .find(([, o]) => (o.entryPoint || '').replace(/\\/g, '/').endsWith('/js/main.js'))?.[0];
if (!jsEntry) throw new Error('metafile has no main.js entry output');
const jsEntryUrl = relative(OUT, jsEntry).split('\\').join('/');

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
if (!html.includes(`assets/${cssName}`)) throw new Error('index.html rewrite failed: css bundle link missing');
if (!html.includes(jsEntryUrl)) throw new Error('index.html rewrite failed: js entry missing');
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
console.log(`  chunks   : ${chunks.length} lazy chunks (dynamic import boundaries preserved)`);
console.log(`  css      : assets/${cssName} (${appCss.length} files concatenated in link order, sapphire last)`);
console.log(`  js files : ${jsFiles.length} sources, all in bundle graph (orphans: 0, exemptions: ${ORPHAN_EXEMPTIONS.size})`);
console.log(`  vendor   : copied through (${vendorCss.length} vendor css links kept separate)`);
