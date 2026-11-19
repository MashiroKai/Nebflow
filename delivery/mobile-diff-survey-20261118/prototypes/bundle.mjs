// bundle.mjs — measure real min+gzip size and dependency-tree size for each
// candidate, by bundling a minimal real usage entry with esbuild.
import { build } from 'esbuild';
import { gzipSync, brotliCompressSync } from 'node:zlib';
import { mkdirSync, writeFileSync, readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const TMP = join(HERE, '.measure');
mkdirSync(TMP, { recursive: true });

const entries = {
  'codemirror-merge': `import {MergeView, unifiedMergeView, getChunks} from '@codemirror/merge';
import {EditorState} from '@codemirror/state';
import {EditorView, lineNumbers, highlightActiveLine} from '@codemirror/view';
import {javascript} from '@codemirror/lang-javascript';
globalThis.__t = {MergeView, unifiedMergeView, getChunks, EditorState, EditorView, lineNumbers, highlightActiveLine, javascript};`,
  'codemirror-merge-min': `import {unifiedMergeView} from '@codemirror/merge';
import {EditorState} from '@codemirror/state';
import {EditorView, lineNumbers} from '@codemirror/view';
globalThis.__t = {unifiedMergeView, EditorState, EditorView, lineNumbers};`,
  'diff2html': `import {html, parse} from 'diff2html';
globalThis.__t = {html, parse};`,
  'pierre-diffs': `import * as d from '@pierre/diffs';
globalThis.__t = d;`,
  'git-diff-view-core': `import * as d from '@git-diff-view/core';
globalThis.__t = d;`,
  'jsdiff': `import * as d from 'diff';
globalThis.__t = d;`,
  'shiki': `import {createHighlighter} from 'shiki';
globalThis.__t = {createHighlighter};`,
};

const results = {};
for (const [name, src] of Object.entries(entries)) {
  const entry = join(TMP, `${name}.js`);
  writeFileSync(entry, src);
  try {
    const out = await build({
      entryPoints: [entry], bundle: true, minify: true, format: 'iife',
      platform: 'browser', target: 'es2022', write: false, metafile: true, logLevel: 'silent',
      define: { 'process.env.NODE_ENV': '"production"' },
    });
    const code = out.outputFiles[0].contents;
    const inputs = Object.keys(out.metafile.inputs).filter((k) => k.includes('node_modules'));
    const mods = new Set(inputs.map((k) => {
      const m = k.split('node_modules/').pop();
      return m.startsWith('@') ? m.split('/').slice(0, 2).join('/') : m.split('/')[0];
    }));
    results[name] = {
      minBytes: code.length,
      minKB: +(code.length / 1024).toFixed(1),
      gzipKB: +(gzipSync(code).length / 1024).toFixed(1),
      brotliKB: +(brotliCompressSync(code).length / 1024).toFixed(1),
      moduleCount: inputs.length,
      topLevelDeps: mods.size,
      deps: [...mods].sort(),
    };
  } catch (e) {
    results[name] = { error: String(e.message || e).slice(0, 300) };
  }
}

// vendored assets already in the repo (marginal cost = 0 for these)
const REPO_WEB = '/private/tmp/stand-mobile-diff-survey-20261118/src/main/resources/web';
function dirSize(p) { let t = 0; for (const f of readdirSync(p)) { const q = join(p, f); const s = statSync(q); t += s.isDirectory() ? dirSize(q) : s.size; } return t; }
const vendored = {};
for (const v of ['monaco', 'highlight.min.js', 'marked.min.js']) {
  const p = join(REPO_WEB, 'vendor', v);
  if (existsSync(p)) vendored[v] = statSync(p).isDirectory() ? dirSize(p) : statSync(p).size;
}

writeFileSync(join(HERE, 'measurements-bundle.json'), JSON.stringify({ results, vendoredBytes: vendored }, null, 2));
for (const [k, v] of Object.entries(results)) {
  console.log(k.padEnd(24), v.error ? 'ERROR ' + v.error : `min ${String(v.minKB).padStart(7)}KB | gzip ${String(v.gzipKB).padStart(6)}KB | brotli ${String(v.brotliKB).padStart(6)}KB | modules ${v.moduleCount} | deps ${v.topLevelDeps}`);
}
console.log('vendored:', JSON.stringify(vendored));
