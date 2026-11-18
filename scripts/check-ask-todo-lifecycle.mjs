#!/usr/bin/env node
// check-ask-todo-lifecycle.mjs — 溢出转待办批（2026-11-18 作者令）的**源码断言闸**。
//
// ── 形态（作者新令逐字） ────────────────────────────────────────────────
// 「ask转待办做的也不对 要参考我们最新的待办协议 支持像askuser这样的问题卡回答了
//   所以转待办是转为具体的待办条目 而不是再待办上方显示问题」
// 承载面 = **待办条目自身**（引擎 Ask 镜像行 `ask-<requestId>` 进收件箱表），题目卡
// 渲染在**该条目行内**；作答/取消走同一收口 ⇒ 两侧同步。
//
// ── 为什么需要这道闸 ────────────────────────────────────────────────────
// 本批的全部风险都集中在「同一个问题会不会有两套承载 / 两处各自判一遍 / 两处各自
// 造一条回答」：
//   · 承载若回到「列表上方区块 + 条目」两套 ⇒ 作者点名禁止，且两处一定先后漂移；
//   · 判据（这张卡在对话面还是已转入待办）若抄成第二份 ⇒ 出现不对称态；
//   · 答复/取消帧若被抄成第二个构造器 ⇒ 键集会分叉，两处效果不再相同；
//   · 条目行的问题卡若复用了「对话内活卡」的定位面（`.option-box` /
//     `data-request-id`）⇒ 会被自己算成「原卡片在场」，判据当场自欺。
// 这些都不是运行时能可靠抓的（活卡在、状态对，只是第二份判据悄悄跑偏），所以按本仓
// 既有的「单点」闸形态做**字面量 + 出现次数**的机器判据。
//
// ── 判据（全部 exit 1 即红；exit 0 = 绿）──────────────────────────────
//   A1 判据单点：`askPlacement` 只在 js/askPending.js 定义一次；全站只有它一处
//      查询卡片定位面 `.option-box[data-request-id]`。
//   A2 对话面收口：askPending.js 的 renderBar 只从 `inlineAsks()` 取行/计数。
//   A3 条目内问题卡模板单点：`askTodoQuestion.js` 是唯一实现 `.ask-todo-card` 的
//      文件；手机与桌面两个宿主都**只 import** 它，不得自带一份实现。
//   A4 帧单点：字面量 `askUserAnswer` / `askUserCancel` 的构造只允许出现在
//      askPending.js（askAnswerFrame / cancelAskFrame）；chat.js 两条腿必须调用它。
//   A5 定位面不交叉：问题卡模板不得出现 `data-request-id` / `option-box`。
//   A6 通知族不新增（模板不得落进 toast/banner/notice 语汇）。
//   A7 旧问题不因新问题失效：镜像必须是按 requestId 的多槽 Map（见 tests 的 G4 腿）。
//   ── 作者新令点名三条（具名）────────────────────────────────────────
//   A8 **(a) 转出后存在同源待办条目（id 可对账）**：todoStore 把 `ask-<requestId>`
//      映射成行身份（`source:'ask'` + `requestId`，来源标记 = name 前缀），`ask-`
//      前缀**不再整类隐藏**，条目可见性由 askPlacement 单点判（转入才显形），且两个
//      宿主都消费该行（`isAskRow` / `buildAskCardForRow`）。
//   A9 **(b) 不存在「待办上方 ask 专用区块」**：全站无 `ask-todo-section` /
//      `mountAskTodoSection`，宿主不得再挂任何「列表上方承载区」。
//   A10 **(c) 两侧同步收口**：作答/取消只经 askPending 的单点入口
//      （`submitAskAnswer` / `cancelAsk`），问题卡模板必须调用它们、且不得自行构造
//      任何 WS 帧；两个宿主不得绕过模板直连单点或自行出帧。
//
// 用法：
//   node scripts/check-ask-todo-lifecycle.mjs                 → 0 绿 / 1 红
//   node scripts/check-ask-todo-lifecycle.mjs --web-root=DIR  → 扫另一棵 web 树
//   node scripts/check-ask-todo-lifecycle.mjs --json
//
// ⚠️ 对**未修源码**（本批之前的树）直跑 ⇒ A1/A3/A8/A9 全红（模块/判据不存在）——
//    这正是本闸检测力的自证：见 tests/ask-todo-lifecycle.spec.mjs 的闸双向退出码腿。

import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const asJson = argv.includes('--json');
const rootArg = argv.find((a) => a.startsWith('--web-root='))
  || (argv.includes('--web-root') ? `--web-root=${argv[argv.indexOf('--web-root') + 1] || ''}` : null);
const WEB = rootArg
  ? join(rootArg.slice('--web-root='.length), 'js')
  : join(REPO, 'src', 'main', 'resources', 'web', 'js');

const NOTICE_TOKEN = /(^|-)(toast|snackbar|banner|notif|notification|notice)(-|$)/;

/** 去注释（判据按**代码**算，注释里提到定位面/类名是解释不是实现）。 */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

function read(rel) {
  const p = join(WEB, rel);
  if (!existsSync(p)) return null;
  try { return readFileSync(p, 'utf8'); } catch { return null; }
}
const count = (text, needle) => {
  if (!text) return 0;
  let n = 0, i = 0;
  for (;;) {
    const at = text.indexOf(needle, i);
    if (at < 0) return n;
    n++; i = at + needle.length;
  }
};

const CARD_LOCATOR = '.option-box[data-request-id';
const FILES = {
  askPending: 'askPending.js',
  askTodoQuestion: 'askTodoQuestion.js',
  chat: 'chat.js',
  todoStore: 'todoStore.js',
  mobileTodo: 'mobile/todoAppleFace.js',
  desktopTodo: 'todoFace.js',
};

const problems = [];
const fail = (code, msg) => problems.push(`${code} ${msg}`);

const src = {};
const code = {};
for (const [k, rel] of Object.entries(FILES)) {
  src[k] = read(rel);
  code[k] = src[k] == null ? null : stripComments(src[k]);
}
if (src.askPending == null) {
  console.error(`check-ask-todo-lifecycle: cannot read ${join(WEB, FILES.askPending)}`);
  process.exit(2);
}

// ── A1 判据单点 ────────────────────────────────────────────────────────
const placementDef = count(code.askPending, 'export function askPlacement');
if (placementDef !== 1) fail('A1', `askPending.js must define askPlacement exactly once (found ${placementDef})`);
if (count(code.askPending, CARD_LOCATOR) !== 2) {
  // 2 = cardInWindow（判据本身）+ jumpToAsk（既有点击跳转腿）
  fail('A1', `askPending.js must query the card locator exactly twice (predicate + jumpToAsk), found ${count(code.askPending, CARD_LOCATOR)}`);
}
for (const k of ['askTodoQuestion', 'todoStore', 'mobileTodo', 'desktopTodo']) {
  if (code[k] == null) continue;
  if (count(code[k], CARD_LOCATOR) > 0) {
    fail('A1', `${FILES[k]} queries the ask-card locator directly — the placement predicate is single-source in askPending.js`);
  }
}

// ── A2 对话面收口 ──────────────────────────────────────────────────────
if (code.askPending.includes('const entries = orderedEntries();')) {
  fail('A2', 'askPending.js renderBar still draws from the full mirror (orderedEntries) — transferred asks would show in the bar/badge again');
}
if (!code.askPending.includes('const entries = inlineAsks();')) {
  fail('A2', 'askPending.js renderBar must draw its rows/badge from inlineAsks()');
}
if (!/#ask-pending-bar|ask-pending-row/.test(code.askPending)) {
  fail('A2', 'askPending.js no longer renders the pending bar (unexpected refactor)');
}

// ── A3 条目内问题卡模板单点 ─────────────────────────────────────────────
const TEMPLATE = 'askTodoQuestion.js';
if (src.askTodoQuestion == null) {
  fail('A3', `${TEMPLATE} is missing — the in-entry question card has no single implementation`);
} else {
  if (count(code.askTodoQuestion, "'ask-todo-card'") < 1) {
    fail('A3', 'askTodoQuestion.js must be the file that builds .ask-todo-card');
  }
  // 模板单点还应是唯一 export `buildAskQuestionCard` / `buildAskCardForRow` 的文件。
  if (count(code.askTodoQuestion, 'export function buildAskQuestionCard') !== 1) {
    fail('A3', 'askTodoQuestion.js must export exactly one buildAskQuestionCard');
  }
}
for (const k of ['mobileTodo', 'desktopTodo']) {
  const text = code[k];
  if (text == null) { fail('A3', `${FILES[k]} is missing — expected a host that renders ask entries`); continue; }
  if (!/from '\.\.?\/(?:\.\.\/)?askTodoQuestion\.js'/.test(text)) {
    fail('A3', `${FILES[k]} must import the shared in-entry question card from askTodoQuestion.js`);
  }
  if (count(text, 'ask-todo-card') > 0) {
    fail('A3', `${FILES[k]} builds .ask-todo-card itself — the template must stay single-source in askTodoQuestion.js`);
  }
}

// ── A4 帧单点 ──────────────────────────────────────────────────────────
const frameLitHits = Object.keys(FILES).filter((k) => code[k] != null && code[k].includes("'askUserAnswer'"));
if (frameLitHits.length !== 1 || frameLitHits[0] !== 'askPending') {
  fail('A4', `the askUserAnswer frame literal must live in askPending.js only (found in: ${frameLitHits.join(', ') || 'none'})`);
}
const cancelLitHits = Object.keys(FILES).filter((k) => code[k] != null && code[k].includes("'askUserCancel'"));
if (cancelLitHits.length !== 1 || cancelLitHits[0] !== 'askPending') {
  fail('A4', `the askUserCancel frame literal must live in askPending.js only (found in: ${cancelLitHits.join(', ') || 'none'})`);
}
if (count(code.askPending, 'export function askAnswerFrame') !== 1) {
  fail('A4', 'askPending.js must export exactly one askAnswerFrame constructor');
}
if (count(code.askPending, 'export function cancelAskFrame') !== 1) {
  fail('A4', 'askPending.js must export exactly one cancelAskFrame constructor');
}
if (code.chat == null || !code.chat.includes('askAnswerFrame(')) {
  fail('A4', 'chat.js card-confirm leg must build its frame through askAnswerFrame');
}
if (code.chat == null || !code.chat.includes('cancelAskFrame(')) {
  fail('A4', 'chat.js card-cancel leg must build its frame through cancelAskFrame');
}

// ── A5 定位面不交叉 ────────────────────────────────────────────────────
if (code.askTodoQuestion != null) {
  // 属性名两种写法都算：字面量 `data-request-id` 与 DOM 直写 `dataset.requestId`。
  if (code.askTodoQuestion.includes('data-request-id') || /\bdataset\s*(?:\.\s*requestId|\[\s*'requestId'\s*\])/.test(code.askTodoQuestion)) {
    fail('A5', 'askTodoQuestion.js must not emit data-request-id — it would be located as the in-conversation card');
  }
  if (/\boption-box\b/.test(code.askTodoQuestion)) {
    fail('A5', 'askTodoQuestion.js must not use the .option-box class — that class is the in-conversation card face');
  }
}

// ── A6 通知族不新增 ────────────────────────────────────────────────────
for (const k of ['askTodoQuestion']) {
  const text = code[k];
  if (text == null) continue;
  const tokens = new Set();
  for (const m of text.matchAll(/class(?:Name)?\s*=\s*'([^']+)'/g)) {
    for (const c of m[1].split(/\s+/)) if (c) tokens.add(c);
  }
  for (const m of text.matchAll(/['"`]([a-z][a-z0-9-]*)['"`]/g)) tokens.add(m[1]);
  for (const c of tokens) {
    if (NOTICE_TOKEN.test(c)) {
      fail('A6', `askTodoQuestion.js uses notice-family token "${c}" — the in-entry card is not a fourth notice type`);
    }
  }
}

// ── A7 旧问题不因新问题失效（多槽镜像 + 各自收口）──────────────────────
/** 取顶层 `export function NAME(...) {` 到下一个行首 `}` 的函数体（去注释后）。 */
function bodyOf(text, name) {
  if (!text) return '';
  const m = new RegExp('export function ' + name + '\\s*\\([^)]*\\)\\s*\\{').exec(text);
  if (!m) return '';
  const start = m.index + m[0].length;
  const end = text.indexOf('\n}', start);
  return end < 0 ? text.slice(start) : text.slice(start, end);
}
if (code.askPending != null) {
  if (/pending\s*\.\s*clear\s*\(/.test(code.askPending)) {
    fail('A7', 'askPending.js clears the whole mirror (pending.clear()) — a later ask would wipe every earlier pending ask');
  }
  const noteBody = bodyOf(code.askPending, 'notePendingAsk');
  if (noteBody && !noteBody.includes('pending.set(')) {
    fail('A7', 'notePendingAsk must upsert exactly its own slot (pending.set(requestId, …))');
  }
  const removeBody = bodyOf(code.askPending, 'removePendingAsk');
  if (removeBody && !removeBody.includes('pending.delete(requestId)')) {
    fail('A7', 'removePendingAsk must delete exactly requestId (per-id closure)');
  }
  const submitBody = bodyOf(code.askPending, 'submitAskAnswer');
  if (submitBody && !submitBody.includes('removePendingAsk(requestId)')) {
    fail('A7', 'submitAskAnswer must close only its own requestId via removePendingAsk(requestId)');
  }
  const cancelBody = bodyOf(code.askPending, 'cancelAsk');
  if (cancelBody && !cancelBody.includes('removePendingAsk(requestId)')) {
    fail('A7', 'cancelAsk must close only its own requestId via removePendingAsk(requestId)');
  }
}

// ── A8 (a) 转出后存在同源待办条目（id 可对账）─────────────────────────
if (code.todoStore == null) {
  fail('A8', 'todoStore.js is missing — the todo entry面 cannot carry the transferred ask');
} else {
  // 来源标记 = name 前缀 `ask-`（与后端 AskTodoBridge.NamePrefix 同值）；映射出身份。
  if (!/const ASK_NAME_PREFIX = 'ask-'/.test(code.todoStore)) {
    fail('A8', "todoStore.js must carry the ask- name prefix (the reconciliation source marker) as a single constant");
  }
  if (!/source:\s*askRid\s*\?\s*'ask'\s*:\s*null/.test(code.todoStore)) {
    fail('A8', 'todoStore.js mapRow must mark ask-mirror rows with source:"ask"');
  }
  if (!/requestId:\s*askRid/.test(code.todoStore)) {
    fail('A8', 'todoStore.js mapRow must expose requestId for ask-mirror rows (id reconciliation)');
  }
  if (!/function askSideVisible\(/.test(code.todoStore) || !/askPlacement\(row\.requestId\)\s*===\s*'todo'/.test(code.todoStore)) {
    fail('A8', 'todoStore.js must gate ask-entry visibility through the single predicate askPlacement');
  }
  if (/const MIRROR_NAME_RE = \/\^\(ask\|perm\)-/.test(code.todoStore)) {
    fail('A8', 'todoStore.js still blanket-hides ask- rows — the transferred ask would never become a todo entry');
  }
}
for (const k of ['mobileTodo', 'desktopTodo']) {
  const text = code[k];
  if (text == null) continue;
  if (!/isAskRow\(/.test(text)) {
    fail('A8', `${FILES[k]} must consume the ask-entry rows via isAskRow (entry-side carrier)`);
  }
  if (!/buildAskCardForRow\(/.test(text)) {
    fail('A8', `${FILES[k]} must render the in-entry question card via buildAskCardForRow`);
  }
}

// ── A9 (b) 不存在「待办上方 ask 专用区块」 ─────────────────────────────
for (const [k, text] of Object.entries(code)) {
  if (text == null) continue;
  if (text.includes('mountAskTodoSection') || text.includes('ask-todo-section')) {
    fail('A9', `${FILES[k]} still carries the "ask section above the todo list" form — the carrier must be the entry itself (no two carriers)`);
  }
  if (text.includes('askTodoList')) {
    fail('A9', `${FILES[k]} still references the removed section module askTodoList.js`);
  }
}

// ── A10 (c) 两侧同步收口 ───────────────────────────────────────────────
if (code.askTodoQuestion != null) {
  if (count(code.askTodoQuestion, 'submitAskAnswer(') < 1) {
    fail('A10', 'askTodoQuestion.js must answer through the single closure point submitAskAnswer');
  }
  if (count(code.askTodoQuestion, 'cancelAsk(') < 1) {
    fail('A10', 'askTodoQuestion.js must cancel through the single closure point cancelAsk');
  }
  if (/wsSend\s*\(|\.send\s*\(/.test(code.askTodoQuestion)) {
    fail('A10', 'askTodoQuestion.js must not build/send WS frames itself — the two-sided closure lives in askPending');
  }
}
for (const k of ['mobileTodo', 'desktopTodo']) {
  const text = code[k];
  if (text == null) continue;
  // 宿主只挂模板；自行直连单点或自行出帧 = 第二套收口。
  if (/submitAskAnswer\s*\(/.test(text) || /cancelAsk\s*\(/.test(text)) {
    fail('A10', `${FILES[k]} must not call the answer closure points directly — render through the single template`);
  }
  if (/'askUserAnswer'|'askUserCancel'/.test(text)) {
    fail('A10', `${FILES[k]} must not construct answer frames itself`);
  }
}

const label = rootArg ? ` (web-root=${WEB})` : '';
if (asJson) {
  console.log(JSON.stringify({ webRoot: WEB, ok: problems.length === 0, problems }, null, 2));
} else if (problems.length) {
  console.error(`ask-todo-lifecycle FAIL${label}: ${problems.length} violation(s)`);
  for (const p of problems) console.error(`  - ${p}`);
} else {
  console.log(`ask-todo-lifecycle PASS${label}: single placement point, single frame constructors, single in-entry card template, no locator crossing, no new notice family, multi-slot mirror, ask entry reconcilable (A8), no section form (A9), two-sided closure (A10)`);
}
process.exit(problems.length ? 1 : 0);
