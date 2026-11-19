#!/usr/bin/env node
// check-ask-status.mjs — Canvas Ask 状态可见性与失败上报门禁
//
// 出处（作者令 2026-11-18 逐字）：「然后还有 Canvas Ask 对话框的状态显示不足
// 我不知道它在干嘛 是否正常 LLM 失败也没有报错等 需要补充更多的信息」。
//
// ── 本闸把「补信息」这句话变成常驻断言（对应任务书验收 1/3/4/5）───────────
//   A 组（静态结构）
//     S1 单点模块在场且是纯函数（零 import / 零 DOM）
//     S2 状态判定**只有一处**：`js/askTurnState.js` 定义状态表，
//        消费方 `js/askDock.js` 不许自带任何状态字面量（第二套判定 = 红）
//     S3 UI 只从单点取结论（必须消费 askTurnState / askStateView / classifyAskFailure）
//     S4 状态行只有一个写点、投影只读一次（syncTurnStatus 单点）
//     S5 **不许静默**：四条曾静默/易静默的路径必须各留可见痕迹
//        （error 帧 / askError 帧 / 180s 静默看门 / 提交时帧入队）
//     S6 复用既有通知三型（toast error 档 + 就地 inline），**不自建新样式族**
//     S7 取消 ≠ 失败（中止路径不得走失败上报）
//   B 组（行为：真跑纯模块，不是 grep）
//     S8 文案完整与区分度（缺文案 / 缺下一步 / 类别越界 ⇒ 红）
//     S9 进度与耗时在场（生成中给已产出量 + 已耗时）
//     S10 凭据不出诊断线（redact 真跑）
//     S11 超时阈值单点（180000 只准出现在模块里）
//
// ── 检测力自证（任务书「破坏 → 红 / 还原 → 绿」）────────────────────────
//   · 对**未修源码**直跑：单点模块缺席 ⇒ S1 红（非零退出码）；
//   · 把 `case 'error':` 分支里的 reportAskFailure() 删掉（= 把错误吞掉）⇒ S5 红；
//   · 把状态行文案表清空一项（= 状态文案缺失）⇒ S8 红。
//
// Usage: node scripts/check-ask-status.mjs
//        node scripts/check-ask-status.mjs --json          机器可读读数
//        ASK_STATUS_WEB_ROOT=<另一份 web 树> node scripts/check-ask-status.mjs
//          （取证用：把基点树导出到临时目录跑「改前红」）
// Exit 0 = clean；exit 1 = 违规逐条列出 `file:line`；exit 2 = 环境错误。

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = process.env.ASK_STATUS_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const JS_DIR = join(WEB, 'js');
const MODULE = join(JS_DIR, 'askTurnState.js');
const DOCK = join(JS_DIR, 'askDock.js');
const JSON_OUT = process.argv.includes('--json');

if (!existsSync(WEB) || !existsSync(JS_DIR)) {
  console.error(`环境错误：找不到 web 树 ${WEB}`);
  process.exit(2);
}

/** 违规累积（每条含 file:line）。 */
const violations = [];
const passes = [];
function v(code, file, msg, needle) {
  const src = file && existsSync(file) ? readFileSync(file, 'utf8') : '';
  violations.push({ code, file: file ? relative(ROOT, file) : '(none)', line: needle ? lineOf(src, needle) : 1, msg });
}
function pass(code, msg) { passes.push({ code, msg }); }
function lineOf(src, needle) {
  const i = src.indexOf(needle);
  return i < 0 ? 1 : src.slice(0, i).split('\n').length;
}
function assert(cond, code, file, msg, needle) {
  if (cond) return true;
  v(code, file, msg, needle);
  return false;
}
/** 锚点起的一段窗口（含锚点）；锚点缺席 ⇒ null。 */
function regionAfter(src, anchor, len) {
  const i = src.indexOf(anchor);
  return i < 0 ? null : src.slice(i, i + len);
}
/** 锚点到**本块收口标记**之间的正文（不含收口行）——比固定窗口精确：
 *  删掉块内那一句上报后，窗口不会「越界到隔壁分支」把红的读成绿的。 */
function blockOf(src, anchor, endMarker) {
  const i = src.indexOf(anchor);
  if (i < 0) return null;
  const rest = src.slice(i + anchor.length);
  const j = rest.indexOf(endMarker);
  return j < 0 ? null : rest.slice(0, j);
}
/** 逐行剥注释（保留行号）：纯度检查按「代码」算，注释里提到 import/document 不算。 */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length));
}
/** 递归收集 web/js 下的 .js 模块文件（不含 .spec/.test）。 */
function jsFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    const st = statSync(abs);
    if (st.isDirectory()) jsFiles(abs, out);
    else if (name.endsWith('.js')) out.push(abs);
  }
  return out;
}

// ══ S1 单点模块在场且是纯函数 ════════════════════════════════════════════
const modOk = assert(existsSync(MODULE), 'S1', MODULE, '单点模块 src/main/resources/web/js/askTurnState.js 缺席——状态判定没有单点', null);
if (!modOk) {
  report();
}
const modSrc = readFileSync(MODULE, 'utf8');

assert(/export\s+const\s+ASK_STATE\s*=/.test(modSrc), 'S1', MODULE, '缺少 `export const ASK_STATE`（状态闭集必须由单点声明）', 'export');
assert(/export\s+function\s+askTurnState\s*\(/.test(modSrc), 'S1', MODULE, '缺少 `export function askTurnState()`（唯一判定函数）', 'export');
assert(/export\s+function\s+askStateView\s*\(/.test(modSrc), 'S1', MODULE, '缺少 `export function askStateView()`（唯一投影函数）', 'export');
assert(/export\s+const\s+ASK_FAILURE\s*=/.test(modSrc), 'S1', MODULE, '缺少 `export const ASK_FAILURE`（失败分类闭集）', 'export');
assert(/export\s+function\s+classifyAskFailure\s*\(/.test(modSrc), 'S1', MODULE, '缺少 `export function classifyAskFailure()`', 'export');
assert(/export\s+function\s+redactDiagnostics\s*\(/.test(modSrc), 'S1', MODULE, '缺少 `export function redactDiagnostics()`（凭据脱敏单点）', 'export');
assert(/export\s+const\s+ASK_SILENCE_MS\s*=/.test(modSrc), 'S1', MODULE, '缺少 `export const ASK_SILENCE_MS`（超时阈值单点）', 'export');
if (/(^|\n)\s*import\s/.test(stripComments(modSrc)) || /\bdocument\.|\bwindow\.|\blocalStorage\b/.test(stripComments(modSrc))) {
  v('S1', MODULE, '单点模块必须是纯函数模块（禁 import / DOM / localStorage 访问）——否则行为断言无法真跑', 'import');
} else if (modOk) {
  pass('S1', '单点模块在场且纯函数（零 import / 零 DOM）');
}

// ══ S2 状态判定只有一处 ════════════════════════════════════════════════
// S2a 全局哨兵：`streaming` 是状态闭集里**唯一**不被其它模块占用的词——它只准
//     出现在单点模块里（同 check-bubble-side R4 的 'theirs' 手法）。
const SENTINEL = ['streaming'];
const DOCK_FORBIDDEN = ['queued', 'waiting', 'streaming', 'aborted', 'offline'];
for (const abs of jsFiles(JS_DIR)) {
  if (abs === MODULE) continue;
  const src = stripComments(readFileSync(abs, 'utf8'));
  for (const w of SENTINEL) {
    if (new RegExp(`['"\`]${w}['"\`]`).test(src)) {
      v('S2', abs, `状态字面量 '${w}' 出现在单点模块之外——状态判定必须收在 js/askTurnState.js（第二套判定）`, `'${w}'`);
    }
  }
}
const dockSrc = stripComments(readFileSync(DOCK, 'utf8'));
for (const w of DOCK_FORBIDDEN) {
  if (new RegExp(`['"\`]${w}['"\`]`).test(dockSrc)) {
    v('S2', DOCK, `消费方自带状态字面量 '${w}'——UI 只许读单点结论，不许各处各判一套`, `'${w}'`);
  }
}
// S2b 定义单点：ASK_STATE / askTurnState 的**定义**各只准一处。
let stateDefs = 0;
let fnDefs = 0;
for (const abs of jsFiles(JS_DIR)) {
  const src = readFileSync(abs, 'utf8');
  if (/export\s+const\s+ASK_STATE\s*=/.test(src)) stateDefs++;
  if (/export\s+function\s+askTurnState\s*\(/.test(src)) fnDefs++;
}
assert(stateDefs === 1, 'S2', MODULE, `ASK_STATE 定义出现 ${stateDefs} 处（必须恰好 1 处）`, 'ASK_STATE');
assert(fnDefs === 1, 'S2', MODULE, `askTurnState 定义出现 ${fnDefs} 处（必须恰好 1 处）`, 'askTurnState');
if (stateDefs === 1 && fnDefs === 1 && !violations.some(x => x.code === 'S2')) {
  pass('S2', '状态判定单点：状态闭集/判定函数各定义 1 处，消费方零状态字面量');
}

// ══ S3 UI 只从单点取结论 ═══════════════════════════════════════════════
assert(/from\s+['"]\.\/askTurnState\.js['"]/.test(dockSrc), 'S3', DOCK, 'askDock.js 未从单点模块导入', 'from');
assert(dockSrc.includes('askTurnState('), 'S3', DOCK, 'askDock.js 未调用 askTurnState()（状态判定必须走单点）', 'askTurnState');
assert(dockSrc.includes('askStateView('), 'S3', DOCK, 'askDock.js 未调用 askStateView()（呈现必须走投影）', 'askStateView');
assert(dockSrc.includes('classifyAskFailure('), 'S3', DOCK, 'askDock.js 未调用 classifyAskFailure()（失败必须分类）', 'classifyAskFailure');
if (!violations.some(x => x.code === 'S3')) pass('S3', 'UI 只从单点取结论（判定/投影/分类三腿全消费）');

// ══ S4 状态行唯一写点 + 投影只读一次 ══════════════════════════════════
const viewCalls = (dockSrc.match(/askStateView\(/g) || []).length;
assert(viewCalls === 1, 'S4', DOCK, `askStateView() 被调用 ${viewCalls} 次（投影只许一处读点）`, 'askStateView(');
const projDefs = (dockSrc.match(/function\s+syncTurnStatus\s*\(/g) || []).length;
assert(projDefs === 1, 'S4', DOCK, `syncTurnStatus 定义 ${projDefs} 处（状态行只许一个写点）`, 'function syncTurnStatus');
const textWrites = (dockSrc.match(/el\.stateText\.textContent\s*=/g) || []).length;
assert(textWrites === 1, 'S4', DOCK, `状态行文本赋值 ${textWrites} 处（必须恰好 1 处，全在 syncTurnStatus 内）`, 'el.stateText.textContent =');
if (!violations.some(x => x.code === 'S4')) pass('S4', '状态行单写点（syncTurnStatus）+ 投影单读点');

// ══ S5 不许静默（四条曾静默的路径必须各留可见痕迹）════════════════════
// 每条的「块」取到本块收口为止（case 分支 = 4 空格缩进的 `    }`；顶层函数 =
// 列 0 的 `}`）——删掉那一句上报 ⇒ 块内立刻缺词 ⇒ 红（不会越界读到隔壁块）。
const silence = [
  { code: 'S5-error-frame', anchor: "case 'error': {", end: '\n    }\n', need: 'reportAskFailure(',
    why: "error 帧（LLM 侧失败）路径必须走失败上报单点——只塞气泡不分类不上报 = 静默" },
  { code: 'S5-askerror-frame', anchor: "case 'askError': {", end: '\n    }\n', need: 'reportAskFailure(',
    why: 'askError 帧（服务端拒答）此前落在 default 分支被静默消费；现在必须有显式上报' },
  { code: 'S5-silence-watch', anchor: 'function askSilenceWatch(', end: '\n}\n', need: 'reportAskFailure(',
    why: '静默看门到点（超时）此前只还按钮、零痕迹；现在必须走失败上报' },
  { code: 'S5-send-queued', anchor: "sendWs({ type: 'askQuestion'", end: '\n}\n', need: 'noteAskQueued(',
    why: 'sendWs 返回 false（帧入队/未连接）此前被丢弃；现在必须落「已提交排队/会话未连接」状态' },
];
for (const s of silence) {
  const block = blockOf(dockSrc, s.anchor, s.end);
  if (block === null) {
    v(s.code, DOCK, `找不到锚点/收口 ${JSON.stringify(s.anchor)}——静默判定失守（路径被移除或改名）`, s.anchor);
    continue;
  }
  assert(block.includes(s.need), s.code, DOCK, `${s.why}（块内缺 ${s.need}）`, s.anchor);
}
if (!violations.some(x => x.code.startsWith('S5-'))) pass('S5', '不许静默：error / askError / 静默看门 / 帧入队 四条路径各留可见痕迹');

// ══ S6 复用既有通知三型，不自建新样式族 ════════════════════════════════
const toastCalls = (dockSrc.match(/__showToast\s*\(|showToast\s*\(/g) || []).length;
assert(toastCalls >= 1, 'S6', DOCK, '失败必须有 toast 错误档（未见 __showToast/showToast 调用）', 'showToast');
assert(toastCalls <= 2, 'S6', DOCK, `toast 调用点 ${toastCalls} 处（只许失败上报这一个单点）`, 'showToast');
assert(!/nebflow-toast/.test(dockSrc), 'S6', DOCK, '不得自建 toast 样式族（nebflow-toast* 归 js/modal.js + css/modal.css 单源）', 'nebflow-toast');
assert(!/TOAST_TYPES|TOAST_ICONS/.test(dockSrc), 'S6', DOCK,
  '不得自建通知类型/图标表（单源在 js/modal.js：本基点是 TOAST_ICONS，notice-unify 批会收成 TOAST_TYPES——两个名字都拦）', 'TOAST_TYPES');
if (!violations.some(x => x.code === 'S6')) pass('S6', '通知三型复用：toast error 档 + 就地 inline，零新样式族');

// ══ S7 取消 ≠ 失败 ═════════════════════════════════════════════════════
const stopBlock = blockOf(dockSrc, 'function stopAsk(', '\n}\n');
if (stopBlock === null) {
  v('S7', DOCK, '找不到 stopAsk()——中止路径判定失守', 'function stopAsk(');
} else {
  assert(stopBlock.includes('settleTurn('), 'S7', DOCK, 'stopAsk 未收口回合', 'settleTurn(');
  assert(!stopBlock.includes('reportAskFailure('), 'S7', DOCK, 'stopAsk 走了失败上报——用户主动中止不是失败（不许弹错）', 'reportAskFailure(');
}
if (!violations.some(x => x.code === 'S7')) pass('S7', '取消 ≠ 失败：中止路径只收口、不上报');

// ══ B 组：真跑纯模块 ═══════════════════════════════════════════════════
const body = modSrc.replace(/^export\s+(const|function|let)\s/gm, '$1 ');
const NAMES = [
  'ASK_STATE', 'ASK_FAILURE', 'ASK_STATE_PRIORITY', 'ASK_STATE_CATEGORIES',
  'DONE_DWELL_MS', 'ASK_SILENCE_MS',
  'askTurnState', 'askStateView', 'classifyAskFailure', 'askFailureLabel',
  'askFailureNext', 'redactDiagnostics', 'isAskNonFailureTerminal',
];
let mod = null;
try {
  // eslint-disable-next-line no-new-func
  mod = new Function(`${body}\nreturn { ${NAMES.join(', ')} };`)();
} catch (e) {
  v('S8', MODULE, `纯模块无法求值：${e.message}`, 'export');
}
if (mod) {
  const { ASK_STATE, ASK_FAILURE, ASK_STATE_PRIORITY, ASK_STATE_CATEGORIES,
    ASK_SILENCE_MS, askTurnState, askStateView, classifyAskFailure,
    redactDiagnostics, isAskNonFailureTerminal } = mod;
  const states = Object.values(ASK_STATE);
  const fails = Object.values(ASK_FAILURE);

  // S8 文案完整与区分度
  const labelsZh = [];
  const labelsEn = [];
  let quiet = [];
  for (const st of states) {
    for (const lang of ['zh', 'en']) {
      const view = askStateView(st, { lang, turn: null, now: 0 });
      if (!view.label) v('S8', MODULE, `状态 '${st}' 在 ${lang} 档缺文案（状态文案缺失）`, st);
      if (!view.tone) v('S8', MODULE, `状态 '${st}' 缺观感档 tone`, st);
      if (!ASK_STATE_CATEGORIES.includes(view.category)) {
        v('S8', MODULE, `状态 '${st}' 的类别 '${view.category}' 不在闭集里`, st);
      }
      if (lang === 'zh') { labelsZh.push(view.label); if (view.quiet) quiet.push(st); }
      else labelsEn.push(view.label);
    }
  }
  assert(quiet.length === 1 && quiet[0] === ASK_STATE.IDLE, 'S8', MODULE,
    `静默态必须恰好一个且为 idle（实测 ${JSON.stringify(quiet)}）——其余状态都必须有可见文案`, 'IDLE');
  assert(new Set(labelsZh).size === labelsZh.length, 'S8', MODULE, '中文状态文案有重复（分不清状态）', 'zh');
  assert(new Set(labelsEn).size === labelsEn.length, 'S8', MODULE, '英文状态文案有重复', 'en');
  assert(ASK_STATE_PRIORITY.length === states.length
    && states.every((s) => ASK_STATE_PRIORITY.includes(s)), 'S8', MODULE,
    'ASK_STATE_PRIORITY 不是闭集（状态清单与次序表不同步）', 'ASK_STATE_PRIORITY');

  const fLabels = [];
  const fNexts = [];
  for (const k of fails) {
    for (const lang of ['zh', 'en']) {
      const lab = mod.askFailureLabel({ kind: k }, lang);
      const next = mod.askFailureNext({ kind: k }, lang);
      if (!lab || lab === 'undefined') v('S8', MODULE, `失败类别 '${k}' 在 ${lang} 档缺文案`, k);
      if (!next || next === 'undefined') v('S8', MODULE, `失败类别 '${k}' 在 ${lang} 档缺下一步`, k);
      if (lang === 'zh') { fLabels.push(lab); fNexts.push(next); }
    }
  }
  assert(new Set(fLabels).size === fLabels.length, 'S8', MODULE, '失败文案有重复（分不清是哪类失败）', 'FAILURE');
  assert(new Set(fNexts).size === fNexts.length, 'S8', MODULE, '失败下一步有重复（每类都要给可执行的出路）', 'FAILURE');
  if (!violations.some(x => x.code === 'S8')) {
    pass('S8', `文案完整与区分度：${states.length} 态 / ${fails.length} 类失败，文案与下一步两两不同`);
  }

  // S8b 两条观感（正常干活 vs 出问题）必须分得开
  const working = ['QUEUED', 'WAITING', 'STREAMING'].map((k) => askStateView(ASK_STATE[k], { lang: 'zh' }).category);
  const problem = ['OFFLINE', 'BUSY', 'FAILED', 'TIMEOUT'].map((k) => askStateView(ASK_STATE[k], { lang: 'zh' }).category);
  assert(working.every((c) => c === 'working'), 'S8', MODULE, `正常干活态类别必须都是 working（实测 ${JSON.stringify(working)}）`, 'working');
  assert(problem.every((c) => c === 'blocked' || c === 'error'), 'S8', MODULE, `前置不可用/失败态必须是 blocked|error（实测 ${JSON.stringify(problem)}）`, 'blocked');

  // S8c 判定函数真跑：关键迁移
  const now = 1_000_000;
  const cases = [
    [{ turn: null, connected: true, draft: '' }, ASK_STATE.IDLE, '空闲'],
    [{ turn: null, connected: true, draft: '在吗' }, ASK_STATE.DRAFT, '待发送'],
    [{ turn: { inflight: true, queued: true, sentAt: now }, connected: true }, ASK_STATE.QUEUED, '已提交排队'],
    [{ turn: { inflight: true, queued: true, sentAt: now }, connected: false }, ASK_STATE.OFFLINE, '未连接入队'],
    [{ turn: { inflight: true, sentAt: now } , connected: true }, ASK_STATE.WAITING, '等待响应'],
    [{ turn: { inflight: true, sentAt: now, firstTokenAt: now }, connected: true }, ASK_STATE.STREAMING, '正在生成'],
    [{ turn: { inflight: false, settledAt: now }, connected: true, now: now + 100 }, ASK_STATE.DONE, '已完成'],
    [{ turn: { inflight: false, aborted: true, settledAt: now }, connected: true, now: now + 100 }, ASK_STATE.ABORTED, '被中止'],
    [{ turn: { inflight: false, failure: { kind: ASK_FAILURE.TIMEOUT }, settledAt: now }, connected: true, now: now + 100 }, ASK_STATE.TIMEOUT, '超时'],
    [{ turn: { inflight: false, failure: { kind: ASK_FAILURE.MODEL, status: 500 }, settledAt: now }, connected: true, now: now + 100 }, ASK_STATE.FAILED, '请求失败'],
    [{ turn: null, connected: false }, ASK_STATE.OFFLINE, '会话未连接'],
    [{ turn: null, connected: true, now: now }, ASK_STATE.IDLE, '空闲（终态驻留到期）'],
  ];
  for (const [input, want, why] of cases) {
    const got = askTurnState({ now, settleDwellMs: 2600, draft: '', ...input });
    assert(got === want, 'S8', MODULE, `askTurnState 判 ${why} 得 '${got}'，应为 '${want}'`, 'askTurnState');
  }

  // S9 进度/耗时在场
  const stream = askStateView(ASK_STATE.STREAMING, { lang: 'zh', turn: { sentAt: now - 3200, firstTokenAt: now - 3000, chars: 128 }, now });
  assert(stream.detail.includes('128'), 'S9', MODULE, `生成中必须给已产出量（实测 detail='${stream.detail}'）`, 'STREAMING');
  assert(/\d/.test(stream.detail.replace('128', '')), 'S9', MODULE, `生成中必须给已耗时（实测 detail='${stream.detail}'）`, 'STREAMING');
  const wait = askStateView(ASK_STATE.WAITING, { lang: 'zh', turn: { sentAt: now - 2500 }, now });
  assert(/\d/.test(wait.detail), 'S9', MODULE, `等待响应必须给已等时长（实测 detail='${wait.detail}'）`, 'WAITING');
  if (!violations.some(x => x.code === 'S9')) pass('S9', '进度与耗时在场（生成中已有产出量 + 已耗时）');

  // S10 凭据不出诊断线
  const secrets = [
    ['sk-abcdef1234567890', 'API key'],
    ['Bearer eyJhbGciOiJIUzI1NiJ9.payload', 'Bearer 载荷'],
    ['token=deadbeefcafe1234', 'token 赋值'],
    ['api_key: "AKIA1234567890ABCD"', 'api_key 赋值'],
    ['A'.repeat(64), '超长不透明串'],
  ];
  for (const [secret, why] of secrets) {
    const red = redactDiagnostics(`boom ${secret} end`);
    assert(!red.includes(secret), 'S10', MODULE, `诊断线泄了凭据（${why}）`, 'redactDiagnostics');
  }
  const cls = classifyAskFailure({ message: 'upstream 500 token=deadbeefcafe1234' });
  assert(!cls.diagnostic.includes('deadbeefcafe1234'), 'S10', MODULE, 'classifyAskFailure 的诊断摘要泄了凭据', 'classifyAskFailure');
  if (!violations.some(x => x.code === 'S10')) pass('S10', '凭据不出诊断线（redact 真跑：key / Bearer / token / 长串全清）');

  // S10b 分类真跑：八类各归其位
  const classCases = [
    [{ aborted: true }, ASK_FAILURE.ABORTED, '被中止'],
    [{ timedOut: true }, ASK_FAILURE.TIMEOUT, '超时'],
    [{ wsOpen: false, message: 'x' }, ASK_FAILURE.OFFLINE, '会话未连接'],
    [{ online: false, message: 'x' }, ASK_FAILURE.NETWORK, '网络不可达'],
    [{ message: 'failed to fetch' }, ASK_FAILURE.NETWORK, '网络不可达（消息面）'],
    [{ status: 401 }, ASK_FAILURE.AUTH, '认证'],
    [{ status: 403, message: 'permission denied' }, ASK_FAILURE.AUTH, '权限'],
    [{ busy: true }, ASK_FAILURE.BUSY, '会话忙碌'],
    [{ status: 500, message: 'model upstream' }, ASK_FAILURE.MODEL, '模型侧报错'],
    [{ message: 'unexpected token < in JSON' }, ASK_FAILURE.PARSE, '响应解析失败'],
    [{ message: '莫名其妙' }, ASK_FAILURE.UNKNOWN, '兜底'],
  ];
  for (const [input, want, why] of classCases) {
    const got = classifyAskFailure(input).kind;
    assert(got === want, 'S10', MODULE, `失败分类 ${why} 得 '${got}'，应为 '${want}'`, 'classifyAskFailure');
  }
  // 取消 ≠ 失败（行为面）
  const abortView = askStateView(ASK_STATE.ABORTED, { lang: 'zh' });
  assert(abortView.category !== 'error' && abortView.tone !== 'error', 'S7', MODULE,
    `中止态不得落错误观感（实测 category=${abortView.category} tone=${abortView.tone}）`, 'ABORTED');
  assert(abortView.diagnostic === '', 'S7', MODULE, '中止态不得带失败诊断', 'ABORTED');
  assert(isAskNonFailureTerminal(ASK_STATE.ABORTED) === true, 'S7', MODULE, 'isAskNonFailureTerminal(aborted) 应为 true', 'isAskNonFailureTerminal');
  assert(askTurnState({ turn: { failure: { kind: ASK_FAILURE.ABORTED } }, now }) === ASK_STATE.ABORTED, 'S7', MODULE,
    '中止分类必须落 aborted 态而不是 failed 态', 'askTurnState');

  // S11 超时阈值单点
  assert(ASK_SILENCE_MS === 180000, 'S11', MODULE, `静默看门阈值漂了（实测 ${ASK_SILENCE_MS}，基线 180000）`, 'ASK_SILENCE_MS');
  assert(dockSrc.includes('ASK_SILENCE_MS'), 'S11', DOCK, 'askDock.js 未使用单点阈值 ASK_SILENCE_MS', 'ASK_SILENCE_MS');
  assert(!/\b180000\b/.test(dockSrc), 'S11', DOCK, 'askDock.js 里硬编码了 180000——阈值必须是单点常量', '180000');
  if (!violations.some(x => x.code === 'S11')) pass('S11', '超时阈值单点（ASK_SILENCE_MS，消费方零硬编码）');
}

// ══ S12 分隔符只能「跟随前一段」（折行时不落行首）════════════════════════
// 作者 2026-11-18 细观感令：375px 窄屏一折行，段首型 ::before 分隔符就把一枚孤零零
// 的 `·` 顶到下一行行首。S12 把成因（段首分隔符）与形态（NBSP 黏在前一段尾巴上）
// 都钉住——运行期的「行首首字符」实测在 tests/ask-status-narrow-matrix.mjs。
const CSS = join(WEB, 'css', 'ask.css');
const cssSrc = existsSync(CSS) ? readFileSync(CSS, 'utf8') : '';
const cssBlock = blockOf(cssSrc, '.ask-state-line {', '.ask-state-line.is-error');
if (cssBlock === null) {
  v('S12', CSS, '找不到 .ask-state-line 样式块——折行分隔符判定失守', '.ask-state-line {');
} else {
  assert(!/\.ask-state-[a-z-]+::before/.test(cssBlock), 'S12', CSS,
    '状态行里出现了段首型 `::before` 分隔符——窄屏折行时它会把 `·` 顶到下一行行首', '::before');
}
const sepDefs = [...dockSrc.matchAll(/const\s+ASK_SEP\s*=\s*'([^']*)'/g)].map(m => m[1]);
assert(sepDefs.length === 1, 'S12', DOCK, `ASK_SEP 定义 ${sepDefs.length} 处（分隔符必须单点，恰好 1 处）`, 'const ASK_SEP');
if (sepDefs.length === 1) {
  assert(sepDefs[0].startsWith('\u00A0') || sepDefs[0].startsWith('\\u00A0'), 'S12', DOCK,
    `ASK_SEP 字面量 ${JSON.stringify(sepDefs[0])} 未以 U+00A0（不换行空格）起头——普通空格会重新给出断点`, 'const ASK_SEP');
}
const statusBlock = blockOf(dockSrc, 'function syncTurnStatus(', '\n}\n');
if (statusBlock === null) {
  v('S12', DOCK, '找不到 syncTurnStatus()——分隔符消费形态判定失守', 'function syncTurnStatus(');
} else {
  const uses = (statusBlock.match(/ASK_SEP/g) || []).length;
  assert(uses >= 2, 'S12', DOCK, `syncTurnStatus 里 ASK_SEP 只用了 ${uses} 处（文案段与下一步段各要跟随一枚）`, 'ASK_SEP');
  assert(!/ASK_SEP\s*\+/.test(statusBlock) && !/=\s*ASK_SEP\b/.test(statusBlock), 'S12', DOCK,
    '分隔符被当成段首写法（`ASK_SEP + …` / `= ASK_SEP`）——它只许追加在前一段的文本尾巴上', 'ASK_SEP');
}
if (!violations.some(x => x.code === 'S12')) pass('S12', '折行分隔符跟随前一段（CSS 零段首 ::before + ASK_SEP 单点 NBSP 黏着）');

// ══ 报告 ══════════════════════════════════════════════════════════════
function report() {
  const ok = violations.length === 0;
  if (JSON_OUT) {
    console.log(JSON.stringify({ ok, web: WEB, passes, violations }, null, 2));
  } else if (ok) {
    for (const p of passes) console.log(`✓ ${p.code} ${p.msg}`);
    console.log(`✓ ask-status gate passed (${passes.length} rules)`);
  } else {
    for (const x of violations) {
      console.error(`✗ ${x.code} ${x.file}:${x.line} — ${x.msg}`);
    }
    console.error(`✗ ask-status FAILED (${violations.length} violations, ${passes.length} rules ok)`);
  }
  process.exit(ok ? 0 : 1);
}
report();
