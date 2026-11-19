// build-prototypes.mjs — bundle each candidate into one self-contained page.
import { build } from 'esbuild';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const PROTO = join(HERE, 'proto');
const DIST = join(HERE, 'dist');
mkdirSync(DIST, { recursive: true });

const fixtures = {
  delegate: JSON.parse(readFileSync(join(HERE, '..', 'fixtures', 'delegate.json'), 'utf8')),
  ask: JSON.parse(readFileSync(join(HERE, '..', 'fixtures', 'ask.json'), 'utf8')),
};
// strip provenance keys (keep payloads exactly as the product receives them)
for (const k of Object.keys(fixtures)) delete fixtures[k]._provenance;

const vendorCss = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : '');
const CANDIDATES = [
  { name: 'a-codemirror', label: 'A · CodeMirror 6 + @codemirror/merge', extraHead: '' },
  { name: 'b-diff2html', label: 'B · diff2html', extraHead: '',
    css: vendorCss(join(HERE, 'node_modules', 'diff2html', 'bundles', 'css', 'diff2html.min.css')) },
  { name: 'c-monaco', label: 'C · Monaco diff (already vendored)', extraHead: '' },
];

const SHELL = (label, css, extraHead, bundle, fx) => `<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${label}</title>
${extraHead}
<style>
  :root { --page: #0f1115; --panel: #171a21; --text: #e8e8ea; --muted: #8b8e96; --line: #262b35; }
  html[data-theme="light"] { --page: #eef0f3; --panel: #ffffff; --text: #1b1f24; --muted: #6b6f78; --line: #d9dce1; }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--page); color: var(--text);
         font: 13px/1.4 -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif; }
  .wrap { display: flex; flex-direction: column; gap: 16px; padding: 16px; align-items: flex-start; }
  .cand { font: 600 12px/1 sans-serif; color: var(--muted); }
  .screen { width: 375px; height: 812px; overflow: auto; background: var(--panel);
            border: 1px solid var(--line); border-radius: 10px; position: relative; }
  .screen-hdr { position: sticky; top: 0; z-index: 5; background: var(--panel);
                border-bottom: 1px solid var(--line); padding: 8px 10px;
                font: 600 11px/1.3 ui-monospace, Menlo, monospace; color: var(--muted); }
  .root { min-height: 0; }
  ${css}
</style>
</head>
<body>
<div class="wrap">
  <div class="cand" id="cand">${label}</div>
  <div class="screen" id="s-delegate"><div class="screen-hdr" id="s-delegate-hdr"></div><div class="root" id="delegate-root"></div></div>
  <div class="screen" id="s-ask"><div class="screen-hdr" id="s-ask-hdr"></div><div class="root" id="ask-root"></div></div>
  <div class="screen" id="s-sbs"><div class="screen-hdr">delta validate · delegate diff (side-by-side variant)</div><div class="root" id="sbs-root"></div></div>
</div>
<script>window.__FIXTURES__ = ${JSON.stringify(fx)};</script>
<script>${bundle}</script>
</body>
</html>`;

for (const c of CANDIDATES) {
  const entry = join(PROTO, `${c.name}.entry.mjs`);
  const out = await build({
    entryPoints: [entry], bundle: true, minify: true, format: 'iife',
    platform: 'browser', target: 'es2022', write: false, logLevel: 'silent',
    define: { 'process.env.NODE_ENV': '"production"' },
    loader: { '.css': 'text' },
  });
  let bundle = out.outputFiles[0].text;
  const cssPath = join(PROTO, `${c.name}.css`);
  const css = (existsSync(cssPath) ? readFileSync(cssPath, 'utf8') : '') + '\n' + (c.css || '');
  writeFileSync(join(DIST, `${c.name}.html`), SHELL(c.label, css, c.extraHead, bundle, fixtures));
  console.log(`${c.name}.html  bundle ${(bundle.length / 1024).toFixed(1)}KB (+css ${(css.length / 1024).toFixed(1)}KB)`);
}
