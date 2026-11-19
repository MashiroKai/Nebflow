#!/usr/bin/env node
// build-vendor-codemirror-merge.mjs — 随包分发构建：把 CodeMirror 6 差异底座
// （@codemirror/state + @codemirror/view + @codemirror/merge）打成一个同源 IIFE
// 包，落进 src/main/resources/web/vendor/codemirror-merge/，运行期零公网请求
// （既有随包分发纪律：锁版本 + sha256 入 package.json.vendorAssets，禁 CDN）。
//
// 复现步骤（检出内，一次性）：
//   npm install --no-save --no-package-lock \
//     @codemirror/state@6.7.6 @codemirror/view@6.43.15 @codemirror/merge@6.12.3
//   node scripts/build-vendor-codemirror-merge.mjs
// 构建后把打印出的 sha256 / bytes 填进 package.json 的 vendorAssets.assets
// （`shasum -a 256 <file>` 可独立复核）。
//
// 装载契约（消费者 = src/main/resources/web/js/diffView/cmLoader.js）：
//   运行期按需注入 <script src="/vendor/codemirror-merge/codemirror-merge.min.js">，
//   该脚本在 globalThis.NfCodeMirror 上挂 { EditorState, EditorView, lineNumbers,
//   highlightActiveLine, unifiedMergeView, MergeView, getChunks, acceptChunk,
//   rejectChunk, uncollapseUnchanged }。

import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = join(REPO, 'src', 'main', 'resources', 'web', 'vendor', 'codemirror-merge');
const OUT_FILE = join(OUT_DIR, 'codemirror-merge.min.js');

const ENTRY = `
import { EditorState, StateEffect, StateField, Compartment } from '@codemirror/state';
import { EditorView, lineNumbers } from '@codemirror/view';
import { unifiedMergeView, MergeView, getChunks, acceptChunk, rejectChunk, uncollapseUnchanged } from '@codemirror/merge';
globalThis.NfCodeMirror = {
  EditorState, StateEffect, StateField, Compartment,
  EditorView, lineNumbers,
  unifiedMergeView, MergeView, getChunks, acceptChunk, rejectChunk, uncollapseUnchanged,
};
`;

const res = await build({
  stdin: { contents: ENTRY, resolveDir: REPO, sourcefile: 'nf-codemirror-merge-entry.js', loader: 'js' },
  bundle: true, minify: true, format: 'iife', platform: 'browser', target: 'es2022',
  write: false, logLevel: 'warning', legalComments: 'none',
  define: { 'process.env.NODE_ENV': '"production"' },
});

mkdirSync(OUT_DIR, { recursive: true });
const code = res.outputFiles[0].contents;
writeFileSync(OUT_FILE, code);

const sha256 = createHash('sha256').update(readFileSync(OUT_FILE)).digest('hex');
console.log(`wrote ${OUT_FILE}`);
console.log(`bytes:  ${code.length}`);
console.log(`min:    ${(code.length / 1024).toFixed(1)} KB`);
console.log(`gzip:   ${(gzipSync(code).length / 1024).toFixed(1)} KB`);
console.log(`sha256: ${sha256}`);
