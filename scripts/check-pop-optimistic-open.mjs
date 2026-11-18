#!/usr/bin/env node
// check-pop-optimistic-open.mjs — Pop 文件卡「乐观打开」接线闸
// （pop-optimistic-canvas 批 2026-11-18 · 作者令逐字「先乐观打开在canvas占位防丢失
// 然后在canvas用一个环来显示加载进度」）。
//
// 为什么需要这个闸：本批修的缺陷是「点击零反馈」（手机端/其它端文件尚未取到、票过期、
// 凭据判定拒绝时，动作丢失）。它的回归形态只有一种——有人把「先加载、后建 tab」写回来
// （把 openTab 挪到取数 await 之后，或让占位复用一份新写的环）。这类回归**在浏览器里
// 才看得见**，而它恰恰是改前形态，最容易在重构里被「顺手」还原。故把不变量做成机器判据。
//
// 判据（逐条 = 一个具名断言，任一红即退 1）：
//   G-PO1 tab-first 时序（**回归探针本体**）：`attachmentPreview.js::previewWithProgress`
//         里 `openTab(` 必须出现在 `runProgressAttempt(` **之前**；且
//         `runProgressAttempt` 里 `renderProgressPane(` 必须出现在第一次 `load(` **之前**。
//         ——「先加载、后建 tab」注入回来 ⇒ 本条必红。
//   G-PO2 环单源：`js/progressPane.js` 存在且导出三件；`attachmentPreview.js` 从它 import；
//         全仓只有这一处构造 `.nf-progress-pane` / `nf-progress-ring` 的 DOM（禁第二套环）。
//   G-PO3 乐观接线：两处 openPopArtifact 都置 `optimisticOpen: true`；`canvas.js` 认哨
//         并路由到 `openOptimisticFile`（内部调 `previewWithProgress`）。
//   G-PO4 取消必须真 abort：`attachmentPreview.js` 有 AbortController + `canvas-tab-closed`
//         监听；`nfFileCache.fetchBlobWithProgress` 接收并把 `signal` 透传给 fetch；
//         `canvas.js` 的 load 把 `{ signal }` 交给取数器。
//   G-PO5 失败显式：失败面板带 `data-nf-error` + `data-nf-retry` 重试键；原因分型覆盖
//         离线 / 凭据拒绝 / 不存在 / 超时（`describeLoadFailure` 四分型齐备）。
//   G-PO6 二次打开走缓存：`canvas.js` 传 `cacheKey: 'nf:' + absPath`。
//   G-PO7 禁静默回落：乐观分支 `return` 在 pop.readFile 发送点**之前**（取不到不许
//         悄悄走回老腿）。
//
// 用法：node scripts/check-pop-optimistic-open.mjs [--web <dir>] [--json <out.json>]
//   `--web` 指到任意一份 web 资源树（默认仓内 src/main/resources/web）——红/绿自证时
//   用来对「未修源码」「注入回归后的源码」直跑，不改工作树。
// 退出码：0 = 全绿；1 = 有违规；2 = 环境错误（读不到源文件）。

import { readFileSync, existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const argWeb = process.argv.indexOf('--web');
const WEB = argWeb > -1 ? process.argv[argWeb + 1] : join(ROOT, 'src/main/resources/web');
const argJson = process.argv.indexOf('--json');
const JSON_OUT = argJson > -1 ? process.argv[argJson + 1] : '';

if (!WEB || !existsSync(WEB)) {
  console.error(`[check-pop-optimistic-open] web tree not found: ${WEB}`);
  process.exit(2);
}

/** @param {string} rel @returns {string} 缺席 = 空串（⇒ 依赖它的断言自然红，退出码 1） */
function read(rel) {
  const p = join(WEB, rel);
  if (!existsSync(p)) return '';
  try { return readFileSync(p, 'utf8'); } catch { return ''; }
}

/** 抽出 `function <name>(` / `async function <name>(` 的函数体（花括号配平）。
 *  参数表可能是解构对象（`fn({ a, b })`）——故先配平圆括号到参数表结束，再找函数体的 `{`。 */
function bodyOf(src, name) {
  const re = new RegExp(`(?:async\\s+)?function\\s+${name}\\s*\\(`);
  const m = re.exec(src);
  if (!m) return null;
  const p = src.indexOf('(', m.index + m[0].length - 1);
  if (p < 0) return null;
  let pd = 0;
  let i = -1;
  for (let j = p; j < src.length; j++) {
    if (src[j] === '(') pd++;
    else if (src[j] === ')') { pd--; if (pd === 0) { i = src.indexOf('{', j); break; } }
  }
  if (i < 0) return null;
  let depth = 0;
  for (let j = i; j < src.length; j++) {
    const c = src[j];
    if (c === '{') depth++;
    else if (c === '}') { depth--; if (depth === 0) return src.slice(i, j + 1); }
  }
  return null;
}

/** 首个匹配的位置（-1 = 没有）。 */
const at = (src, needle) => (src == null ? -1 : src.indexOf(needle));

const results = [];
/** @param {string} name @param {boolean} ok @param {string} detail */
function check(name, ok, detail) {
  results.push({ name, ok, detail });
}

const canvasJs = read('js/canvas.js');
const apJs = read('js/attachmentPreview.js');
const ppJs = read('js/progressPane.js');
const nfcJs = read('js/nfFileCache.js');
const popJs = read('js/popArtifacts.js');
const chatPopJs = read('js/chat/popArtifact.js');
const splitCss = read('css/split.css');

// 源文件在场性（缺席 = 红，不当作环境错误 —— 「对未修源码直跑应红」要求缺失/未接线的
// 源树也退出 1；只有 --web 目标目录本身不存在才是环境错误 2，见上）。
{
  const need = ['js/canvas.js', 'js/attachmentPreview.js', 'js/progressPane.js', 'js/nfFileCache.js',
    'js/popArtifacts.js', 'js/chat/popArtifact.js', 'css/split.css'];
  const missing = need.filter((rel) => !existsSync(join(WEB, rel)));
  check('G-PO0 源文件齐备（乐观打开腿的全部落点文件在场）', missing.length === 0,
    missing.join(',') || 'ok');
}

// ── G-PO1 tab-first 时序（回归探针本体）──────────────────────────────────────
{
  const pwp = bodyOf(apJs, 'previewWithProgress');
  const rpa = bodyOf(apJs, 'runProgressAttempt');
  const iOpen = at(pwp, 'openTab(');
  const iAttempt = at(pwp, 'runProgressAttempt(');
  check('G-PO1a previewWithProgress: openTab 先于 runProgressAttempt（tab-first）',
    iOpen >= 0 && iAttempt >= 0 && iOpen < iAttempt,
    `openTab@${iOpen} runProgressAttempt@${iAttempt}`);
  const iPane = at(rpa, 'renderProgressPane(');
  const iLoad = at(rpa, 'load(');
  check('G-PO1b runProgressAttempt: 占位/进度环先于 load( 取数',
    iPane >= 0 && iLoad >= 0 && iPane < iLoad,
    `renderProgressPane@${iPane} load(@${iLoad}`);
  // 回归探针本体：「先加载、后建 tab」的直接注入形态 = 在 openTab 之前出现 await。
  const iAwaitBeforeOpen = iOpen < 0 ? -1 : pwp.slice(0, iOpen).indexOf('await');
  check('G-PO1c previewWithProgress: openTab 之前**零 await**（tab 同步出，不被取数挡住）',
    iOpen > 0 && iAwaitBeforeOpen < 0,
    `await-before-openTab@${iAwaitBeforeOpen}`);
}

// ── G-PO2 环单源（禁第二套）─────────────────────────────────────────────────
{
  const exportsOk = /export\s+function\s+renderProgressPane\s*\(/.test(ppJs)
    && /export\s+function\s+renderProgressFailure\s*\(/.test(ppJs)
    && /export\s+function\s+renderProgressNote\s*\(/.test(ppJs);
  check('G-PO2a progressPane.js 存在并导出三件', exportsOk, exportsOk ? 'ok' : 'missing exports');
  check('G-PO2b attachmentPreview.js 从 progressPane.js 单源 import',
    /from\s*'\.\/progressPane\.js'/.test(apJs), 'import edge missing');
  // 全仓只有 progressPane.js 构造环 DOM（禁第二份实现）。
  const others = [
    ['js/canvas.js', canvasJs], ['js/attachmentPreview.js', apJs],
    ['js/popArtifacts.js', popJs], ['js/chat/popArtifact.js', chatPopJs],
  ].filter(([, src]) => /nf-progress-ring|nf-progress-pane/.test(src.replace(/\/\/[^\n]*/g, '')));
  check('G-PO2c 无第二份环实现（构造点唯一）', others.length === 0,
    others.map(([f]) => f).join(',') || 'ok');
  check('G-PO2d 环样式单源仍在 css/split.css', /\.nf-progress-pane\b/.test(splitCss) && /\.nf-progress-ring\b/.test(splitCss));
}

// ── G-PO3 乐观接线 ─────────────────────────────────────────────────────────
{
  const popOk = /optimisticOpen:\s*true/.test(popJs);
  const chatOk = /optimisticOpen:\s*true/.test(chatPopJs);
  check('G-PO3a popArtifacts.js::openPopArtifact 置 optimisticOpen', popOk);
  check('G-PO3b js/chat/popArtifact.js::openPopArtifact 置 optimisticOpen', chatOk);
  check('G-PO3c canvas.js 认 optimisticOpen 哨', /item\.optimisticOpen/.test(canvasJs));
  const helper = bodyOf(canvasJs, 'openOptimisticFile');
  check('G-PO3d canvas.js::openOptimisticFile 复用 previewWithProgress 单点',
    helper != null && /previewWithProgress\(/.test(helper), helper ? 'ok' : 'helper missing');
}

// ── G-PO4 取消必须真 abort ─────────────────────────────────────────────────
{
  check('G-PO4a attachmentPreview.js 有 AbortController',
    /new AbortController\(\)/.test(apJs), 'no controller');
  check('G-PO4b attachmentPreview.js 监听 canvas-tab-closed 并 abort',
    /addEventListener\(\s*'canvas-tab-closed'/.test(apJs) && /ctl\.abort\(\)/.test(apJs));
  check('G-PO4c nfFileCache.fetchBlobWithProgress 接收 signal',
    /fetchBlobWithProgress\(url,\s*onProgress,\s*opts\)/.test(nfcJs) && /signal/.test(bodyOf(nfcJs, 'fetchBlobWithProgress') || ''));
  const helper = bodyOf(canvasJs, 'openOptimisticFile') || '';
  check('G-PO4d canvas.js load 把 { signal } 交给取数器',
    /fetchBlobWithProgress\(/.test(helper) && /\{\s*signal\s*\}/.test(helper));
}

// ── G-PO5 失败显式（原因 + 重试）───────────────────────────────────────────
{
  check('G-PO5a 失败面板带 data-nf-error', /data-nf-error/.test(ppJs));
  check('G-PO5b 失败面板带重试键 data-nf-retry', /data-nf-retry/.test(ppJs));
  const d = bodyOf(ppJs, 'describeLoadFailure');
  const typed = ['canvas.loadTimeout', 'canvas.loadOffline', 'canvas.loadDenied', 'canvas.loadNotFound']
    .every((k) => d != null && d.includes(k));
  check('G-PO5c 失败原因四分型齐备（超时/离线/拒绝/不存在）', typed, d ? 'ok' : 'missing describeLoadFailure');
}

// ── G-PO6 二次打开走缓存 ───────────────────────────────────────────────────
{
  const helper = bodyOf(canvasJs, 'openOptimisticFile') || '';
  check('G-PO6 canvas.js 传 cacheKey（nf:<absPath>）⇒ 二次打开零取数',
    /cacheKey:\s*'nf:'\s*\+\s*absPath/.test(helper));
}

// ── G-PO7 禁静默回落（乐观分支 return 在 pop.readFile 发送点之前）────────────
{
  const iGuard = at(canvasJs, 'if (item.optimisticOpen)');
  const iOpen = iGuard < 0 ? -1 : canvasJs.indexOf('openOptimisticFile(', iGuard);
  const iRet = iGuard < 0 ? -1 : canvasJs.indexOf('return;', iOpen < 0 ? iGuard : iOpen);
  // 乐观分支之后的第一个 pop.readFile 发送点（老腿）
  const iOldSend = iGuard < 0 ? -1 : canvasJs.indexOf("type: 'pop.readFile'", iGuard);
  check('G-PO7 乐观分支 return 早于老腿 pop.readFile 发送点（禁静默回落）',
    iGuard >= 0 && iOpen > iGuard && iRet > iOpen && iOldSend > iRet,
    `guard@${iGuard} open@${iOpen} return@${iRet} oldSend@${iOldSend}`);
}

for (const r of results) {
  console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.detail && !r.ok ? '  [' + r.detail + ']' : ''}`);
}
if (JSON_OUT) {
  try { writeFileSync(JSON_OUT, JSON.stringify({ web: WEB, results }, null, 2)); } catch { /* 读数面非关键 */ }
}

const failed = results.filter((r) => !r.ok).length;
console.log(`[check-pop-optimistic-open] ${results.length - failed}/${results.length} passed  (web=${WEB})`);
process.exit(failed ? 1 : 0);
