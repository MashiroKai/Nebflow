#!/usr/bin/env node
// check-pdf-entry-single-source.mjs — PDF note/ask 入口单源闸
// （文本层批 2026-11-19；作者逐字纠形「pdf note and ask 不是在侧边栏加一个控件，
//   是让 canvas 的 note 和 ask 能够支持 pdf 的阅读」）。
//
// 为什么有这个判据：PDF note/ask 批（2026-11-18）交付的形态是**侧边栏面板**
// （web/js/pdfPanel.js + web/css/pdfPanel.css + index.html 的两处标记 + main.js 的
// 注册），与 canvas 既有的两颗钮（`.ask-fab` / `.note-fab`，js/askDock.js）**并存**
// ⇒ 同一个能力两个入口、两份引导。裁定后侧边栏全链删净，入口只剩 canvas 两颗钮；
// 而「删净」这件事在源码面**不会自己变红**：残留的引用、孤儿 CSS 选择器、复活
// 的第二套面板都不会让任何既有闸失败。故把不变量做成机器判据，五条：
//
//   P1 侧边栏件不在盘上（js/pdfPanel.js、css/pdfPanel.css 都不存在）；
//   P2 侧边栏身份串零命中（`pdfPanel` / `initPdfPanel` / `pdf-btn` / `panel-pdf` /
//      `pdf-panel-body` / `.pdfp-` 在 web 源树一个字面量都不许有）；
//   P3 PDF 内容模型单源：askDock.js 用 pane 的两条读数（`_nfPdfPage` /
//      `_nfPdfSelection`）+ 既有 `/api/pdf/open`、`/api/pdf/note` 两条腿；
//   P4 PDF 可选中：viewers/pdf.js 建 `.textLayer` 并调官方 `renderTextLayer`；
//      css/split.css 只供 `.pdf-page-wrap` 收缩盒（非浮动），官方 .textLayer
//      浮动形态随 pdf.js 库交付（vendor/pdfjs/text_layer_builder.css，index.html
//      链接）—— 官方件落 vendor/ 判区，app CSS 零新增浮动选择器。
//   P5 **零新路由**：web 源树里出现的 `/api/pdf/<x>` 取值集合 ⊆ {open, note}
//      （PDF note/ask 批已落地的两条；新增任何第三条即红）。
//
// 退出码：0 = 全绿；1 = 有违规；2 = 环境错误（源树读不到）。
//
// 用法：node scripts/check-pdf-entry-single-source.mjs

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');

/** 侧边栏全链的删除清单（文件 / 身份串）+ 判定。 */
const REMOVED_FILES = ['js/pdfPanel.js', 'css/pdfPanel.css'];
const REMOVED_STRINGS = ['pdfPanel', 'initPdfPanel', 'pdf-btn', 'panel-pdf', 'pdf-panel-body', '.pdfp-'];
/** P5：PDF REST 域已落地的两条腿（新增第三条 = 新路由，即红）。 */
const ALLOWED_PDF_ROUTES = new Set(['open', 'note']);

const failures = [];
const notes = [];

function readIfExists(p) {
  try { return readFileSync(p, 'utf8'); } catch { return null; }
}

/** 递归收集 web 源树里的 .js/.mjs/.css/.html（不含 vendor/ 第三方）。 */
function collect(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (entry === 'vendor' || entry === 'node_modules') continue;
      collect(full, out);
    } else if (['.js', '.mjs', '.css', '.html'].includes(extname(entry))) {
      out.push(full);
    }
  }
  return out;
}

if (!existsSync(WEB)) {
  console.error(`pdf-entry-single-source: web source tree not found at ${WEB}`);
  process.exit(2);
}

// ── P1 侧边栏件不在盘上 ────────────────────────────────────────────────────
for (const rel of REMOVED_FILES) {
  const p = join(WEB, rel);
  if (existsSync(p)) failures.push(`P1 已删的侧边栏件仍在盘上：web/${rel}（删除点见批报告清单）`);
}
notes.push(`P1 侧边栏件缺失：${REMOVED_FILES.map((f) => `web/${f}`).join(', ')}`);

// ── P2 侧边栏身份串零命中（含孤儿 CSS 选择器）─────────────────────────────
const files = collect(WEB);
for (const file of files) {
  const text = readIfExists(file);
  if (text == null) continue;
  text.split(/\r?\n/).forEach((line, i) => {
    for (const needle of REMOVED_STRINGS) {
      if (line.includes(needle)) {
        failures.push(`P2 残留引用/孤儿选择器：${relative(ROOT, file)}:${i + 1} 含 "${needle}"`);
      }
    }
  });
}
notes.push(`P2 侧边栏身份串零命中：扫 ${files.length} 个 web 源文件（${REMOVED_STRINGS.join(' / ')}）`);

// ── P3 PDF 内容模型单源（canvas 两颗钮承载）────────────────────────────────
const askDock = readIfExists(join(WEB, 'js', 'askDock.js')) || '';
for (const [label, needle] of [
  ['pane 当前页读数 `_nfPdfPage`', '_nfPdfPage'],
  ['pane 选区读数 `_nfPdfSelection`', '_nfPdfSelection'],
  ['既有身份腿 `POST /api/pdf/open`', "'/api/pdf/open'"],
  ['既有备注腿 `GET/POST /api/pdf/note`', '/api/pdf/note'],
]) {
  if (!askDock.includes(needle)) failures.push(`P3 askDock.js 缺少${label}（PDF 内容模型单源断链）`);
}
const gate = readIfExists(join(WEB, 'js', 'mdHtmlGate.js')) || '';
if (!/export function isPdfFile/.test(gate)) failures.push('P3 mdHtmlGate.js 未导出 isPdfFile（PDF 判据单源缺失）');
notes.push('P3 PDF 内容模型单源：askDock.js 的页锚备注 + 选区读数、mdHtmlGate.isPdfFile');

// ── P4 PDF 可选中（text layer，官方件随库交付）─────────────────────────────
const pdfViewer = readIfExists(join(WEB, 'js', 'viewers', 'pdf.js')) || '';
if (!pdfViewer.includes('renderTextLayer')) failures.push('P4 viewers/pdf.js 未调官方 renderTextLayer（PDF 不可选中）');
if (!/'textLayer'/.test(pdfViewer)) failures.push('P4 viewers/pdf.js 未建 .textLayer 容器（文本层缺席）');
const splitCss = readIfExists(join(WEB, 'css', 'split.css')) || '';
if (!/\.pdf-page-wrap\b/.test(splitCss)) failures.push('P4 css/split.css 缺 `.pdf-page-wrap` 收缩盒（每页定位盒缺席）');
if (/\.textLayer[\s\S]{0,200}?position\s*:\s*(?:absolute|fixed|sticky)/.test(splitCss)) {
  failures.push('P4 app CSS（split.css）出现了 textLayer 浮动规则 —— 官方件应留在 vendor/pdfjs/');
}
const vendorTl = readIfExists(join(WEB, 'vendor', 'pdfjs', 'text_layer_builder.css')) || '';
if (!/\.textLayer\b[\s\S]*?position\s*:\s*absolute/.test(vendorTl)) {
  failures.push('P4 vendor/pdfjs/text_layer_builder.css 缺官方 `.textLayer { position:absolute }`（vendored 库资产缺席）');
}
const indexHtml = readIfExists(join(WEB, 'index.html')) || '';
if (!indexHtml.includes('vendor/pdfjs/text_layer_builder.css')) {
  failures.push('P4 index.html 未链接 vendor/pdfjs/text_layer_builder.css（文本层样式不加载）');
}
notes.push('P4 文本层：viewers/pdf.js 建 .textLayer + renderTextLayer；app 侧 .pdf-page-wrap 收缩盒 + vendor/pdfjs 官方 text_layer_builder.css（index.html 链接）');

// ── P5 零新路由 ────────────────────────────────────────────────────────────
const seenRoutes = new Map();
for (const file of files) {
  if (extname(file) !== '.js') continue;
  const text = readIfExists(file);
  if (text == null) continue;
  for (const m of text.matchAll(/\/api\/pdf\/([A-Za-z0-9_-]+)/g)) {
    const seg = m[1];
    if (!seenRoutes.has(seg)) seenRoutes.set(seg, relative(ROOT, file));
  }
}
for (const [seg, where] of seenRoutes) {
  if (!ALLOWED_PDF_ROUTES.has(seg)) failures.push(`P5 出现新 PDF 路由 /api/pdf/${seg}（${where}）——零新路由红线`);
}
notes.push(`P5 零新路由：web 侧出现的 PDF 路由取值 = {${[...seenRoutes.keys()].sort().join(', ')}}`);

// ── 收口 ───────────────────────────────────────────────────────────────────
console.log('pdf-entry-single-source — PDF note/ask 入口单源');
for (const n of notes) console.log('  · ' + n);
if (failures.length) {
  console.error(`\npdf-entry-single-source FAIL (${failures.length} hits)\n` + failures.join('\n'));
  process.exit(1);
}
console.log('\npdf-entry-single-source PASS: 侧边栏删净、入口单源（canvas note/ask）、PDF 可选中、零新路由。');
