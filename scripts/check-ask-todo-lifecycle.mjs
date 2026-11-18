#!/usr/bin/env node
// check-ask-todo-lifecycle.mjs — 溢出转待办批（2026-11-18 作者令）的**源码断言闸**。
//
// ── 为什么需要这道闸 ────────────────────────────────────────────────────
// 本批的全部风险都集中在「两处渲染会不会各自判一遍 / 各存一份状态」：
//   · 判据（这张卡在对话面还是已转入待办）若被抄成第二份，两边一定先后漂移，
//     就出现作者点名禁止的**不对称态**（待办已答、原卡仍可作答，或反向）。
//   · 答复帧若被抄成第二个构造器，键集会慢慢分叉（secretSlots / attachments
//     这类条件键最容易被漏），两处作答效果就不再相同。
//   · 待办面的卡若复用了「对话内活卡」的定位面（`.option-box` /
//     `data-request-id`），它会被自己算成「原卡片在场」，判据当场自欺。
// 这些都不是运行时能可靠抓的（活卡在、状态对，只是第二份判据悄悄跑偏），
// 所以按本仓既有的「单点」闸形态（check-prompt-single-source / check-msgstyle-
// single-source）做**字面量 + 出现次数**的机器判据：判据一处定义、帧一处构造、
// 模板一处实现、定位面不交叉。
//
// ── 判据（A1–A6，全部 exit 1 即红；exit 0 = 绿）────────────────────────
//   A1 判据单点：`askPlacement` 只在 js/askPending.js 定义一次；全站只有它一处
//      查询卡片定位面 `.option-box[data-request-id]`。
//   A2 对话面收口：askPending.js 的 renderBar 只从 `inlineAsks()` 取行/计数，
//      不得再回落到全量 `orderedEntries()`（否则已转入待办的又进弹窗/计数气泡）。
//   A3 模板单点：`askTodoList.js` 是唯一实现 `ask-todo-card` 的文件；手机与桌面
//      两个宿主都必须**只 import** 它，不得自带一份实现。
//   A4 帧单点：字面量 `askUserAnswer` 的构造只允许出现在 askPending.js
//      （askAnswerFrame）；chat.js 卡内确认腿必须调用它。
//   A5 定位面不交叉：待办面模板不得出现 `data-request-id` / `option-box`
//      （自欺风险，见上）。
//   A6 通知族不新增：本批新增的类名不得落进通知类令牌
//      /(^|-)(toast|snackbar|banner|notif|notification|notice)(-|$)/ ——
//      与 notice-unify 三型（toast/banner/inline）不冲突：本区是待办面内的行内区，
//      不是第四种通知。
//   A7 旧问题不因新问题失效（作者追加令 2026-11-18 21:33 逐字：「后面一个问题来了
//      前面的就失效了」）：镜像必须是**按 requestId 的多槽 Map**——新帧只 upsert 自己
//      那一槽（不许整表清空）、收口只删自己那一键（removePendingAsk / submitAskAnswer
//      都不得触碰别的 id）。字面判据：
//        · 全文不得出现 `pending.clear()`（整表清空 = 后到者抹掉前者，正是该缺陷）；
//        · `notePendingAsk` 用 `pending.set(` upsert，不得 clear/重置；
//        · `removePendingAsk` 只 `pending.delete(requestId)`；
//        · `submitAskAnswer` 只调 `removePendingAsk(requestId)`（不调任何批量收口）。
//      注入「后到使前一个失效」⇒ A7 红（G4 腿自证检测力）。
//
// 用法：
//   node scripts/check-ask-todo-lifecycle.mjs                 → 0 绿 / 1 红
//   node scripts/check-ask-todo-lifecycle.mjs --web-root=DIR  → 扫另一棵 web 树
//   node scripts/check-ask-todo-lifecycle.mjs --json
//
// ⚠️ 对**未修源码**（本批之前的树）直跑 ⇒ A1–A4 全红（模块不存在）——这正是本闸
//    检测力的自证：见 tests/ask-todo-lifecycle.spec.mjs 的「闸双向退出码」腿。

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
  askTodoList: 'askTodoList.js',
  chat: 'chat.js',
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
  // 2 = cardInWindow（判据本身）+ jumpToAsk（既有点击跳转腿，本批不动）
  fail('A1', `askPending.js must query the card locator exactly twice (predicate + jumpToAsk), found ${count(code.askPending, CARD_LOCATOR)}`);
}
// 宿主面（待办面渲染器 + 两个宿主）不得自行查询对话内活卡定位面。
for (const k of ['askTodoList', 'mobileTodo', 'desktopTodo']) {
  if (code[k] == null) continue;
  if (count(code[k], CARD_LOCATOR) > 0) {
    fail('A1', `${FILES[k]} queries the ask-card locator directly — the placement predicate is single-source in askPending.js`);
  }
}

// ── A2 对话面收口 ──────────────────────────────────────────────────────
if (code.askPending.includes('const entries = orderedEntries();')) {
  fail('A2', "askPending.js renderBar still draws from the full mirror (orderedEntries) — transferred asks would show in the bar/badge again");
}
if (!code.askPending.includes('const entries = inlineAsks();')) {
  fail('A2', 'askPending.js renderBar must draw its rows/badge from inlineAsks()');
}
if (!/#ask-pending-bar|ask-pending-row/.test(code.askPending)) {
  fail('A2', 'askPending.js no longer renders the pending bar (unexpected refactor)');
}

// ── A3 模板单点 ────────────────────────────────────────────────────────
if (src.askTodoList == null) {
  fail('A3', `${FILES.askTodoList} is missing — the ask-in-todo template has no single implementation`);
} else {
  if (count(code.askTodoList, "'ask-todo-card'") < 1) fail('A3', 'askTodoList.js must be the file that builds .ask-todo-card');
}
for (const k of ['mobileTodo', 'desktopTodo']) {
  const text = code[k];
  if (text == null) { fail('A3', `${FILES[k]} is missing — expected a host for the todo ask section`); continue; }
  if (!/from '\.\.?\/(?:\.\.\/)?askTodoList\.js'/.test(text)) {
    fail('A3', `${FILES[k]} must import the shared renderer from askTodoList.js`);
  }
  if (count(text, 'ask-todo-card') > 0) {
    fail('A3', `${FILES[k]} builds .ask-todo-card itself — the template must stay single-source in askTodoList.js`);
  }
}

// ── A4 帧单点 ──────────────────────────────────────────────────────────
const answerFrameHits = Object.keys(FILES).filter((k) => code[k] != null && code[k].includes("'askUserAnswer'"));
if (answerFrameHits.length !== 1 || answerFrameHits[0] !== 'askPending') {
  fail('A4', `the askUserAnswer frame literal must live in askPending.js only (found in: ${answerFrameHits.join(', ') || 'none'})`);
}
if (count(code.askPending, 'export function askAnswerFrame') !== 1) {
  fail('A4', 'askPending.js must export exactly one askAnswerFrame constructor');
}
if (code.chat == null || !code.chat.includes('askAnswerFrame(')) {
  fail('A4', 'chat.js card-confirm leg must build its frame through askAnswerFrame (single payload constructor)');
}

// ── A5 定位面不交叉 ────────────────────────────────────────────────────
if (code.askTodoList != null) {
  // 属性名两种写法都算：字面量 `data-request-id` 与 DOM 直写 `dataset.requestId`
  // （运行时产出的正是同一个属性名——G2 腿的破坏形态）。
  if (code.askTodoList.includes('data-request-id') || /\bdataset\s*(?:\.\s*requestId|\[\s*'requestId'\s*\])/.test(code.askTodoList)) {
    fail('A5', 'askTodoList.js must not emit data-request-id — it would be located as the in-conversation card');
  }
  if (/\boption-box\b/.test(code.askTodoList)) {
    fail('A5', 'askTodoList.js must not use the .option-box class — that class is the in-conversation card face');
  }
}

// ── A6 通知族不新增 ────────────────────────────────────────────────────
for (const k of ['askTodoList']) {
  const text = code[k];
  if (text == null) continue;
  const tokens = new Set();
  for (const m of text.matchAll(/class(?:Name)?\s*=\s*'([^']+)'/g)) {
    for (const c of m[1].split(/\s+/)) if (c) tokens.add(c);
  }
  for (const m of text.matchAll(/['"`]([a-z][a-z0-9-]*)['"`]/g)) tokens.add(m[1]);
  for (const c of tokens) {
    if (NOTICE_TOKEN.test(c)) {
      fail('A6', `askTodoList.js uses notice-family token "${c}" — the section is an inline todo-face block, not a fourth notice type`);
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
}

const label = rootArg ? ` (web-root=${WEB})` : '';
if (asJson) {
  console.log(JSON.stringify({ webRoot: WEB, ok: problems.length === 0, problems }, null, 2));
} else if (problems.length) {
  console.error(`ask-todo-lifecycle FAIL${label}: ${problems.length} violation(s)`);
  for (const p of problems) console.error(`  - ${p}`);
} else {
  console.log(`ask-todo-lifecycle PASS${label}: single placement point, single frame constructor, single template, no locator crossing, no new notice family, multi-slot mirror (per-id closure)`);
}
process.exit(problems.length ? 1 : 0);
