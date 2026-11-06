#!/usr/bin/env node
// check-badge-words.mjs — 身份/类型徽标词「单一词表单源」静态哨兵
// （术语统一批 2026-11-06 · 作者令逐字「统一 根据英文」）
//
// Why a static gate: 同一概念此前有两套词面——任务列表 `task.mode.*`（中文面
// 「内核 / 分发器」）与后台智能体面板的逐字英文（kernel / dispatcher），成员行又
// 走 `subagents.kind*`（中文面「分发器 / 节点」），加上 bgAgents 里几处硬编码
// Capitalized 词（Subagent / Delegate）。同屏两套词面 = 作者报障的直接形态，且
// 下一个消费方加入时不会有任何东西变红。本哨兵把「徽标词只从一个来源取」变成
// 机器判据：
//
//   A 单源在场   徽标词表 = js/termWords.js 的 BADGE_WORDS（唯一一处定义）；
//                值域 = 作者裁定的六个逐字词面（kernel / dispatcher / node /
//                subagent / delegate / AgentFlow）。
//   B 徽标位零字面量  徽标渲染槽（.bg-task-kind / .bg-task-mode / .bg-task-entity /
//                .task-delegate-badge-mode）所在代码行不得写死词面字面量，也不得
//                出现中文译名。
//   C 生产者收敛  三个「产词函数」只准调 badgeWord()/badgeFaceOfKind()：函数体里
//                不得把词面当字面量「写进」界面（return / textContent = / escapeHtml()
//                / 字符串拼接四形态），数据值比较（=== 'kernel' 这类）不算。
//   D 译名退役   locale 不得再有 task.mode.* / subagents.kind* 键族；任何语言包的
//                值不得是「内核 / 分发器 / 节点 / 子代理」；非 locale 的前端代码
//                （剔注释后）不得把这四个译名当字符串字面量。
//   E 概念单面   六个词面字面量只准出现在 termWords.js；徽标位与产词函数里出现
//                Capitalized 变体（Kernel / Dispatcher / Node / Subagent / Delegate）
//                或 'Agent Flow' 变体即红 —— 同一概念两个词面。
//
// 边界（写清界线，免得把叙事当徽标）：正文/说明文字里的中文（locale 的叙述句、
// 代码注释、`bg.modeTitle`「执行模式：{mode}」这类说明标签）不是徽标词，本闸不管；
// 引擎协议值与记账词（managePanel.OPERABLE_KINDS 的 'Delegate'/'SubTask'、
// bgAgentKindFromSession 的返回词）是**数据面**，不是上屏的徽标词面，同样不在判据内。
//
// Exit 0 = clean；exit 1 = 违规逐条列出 `file:line` + 判据 id。
// 本地：node scripts/check-badge-words.mjs（或 `make badge-words`）；CI 与
// `make frontend-gates` 同一条命令（单一真源，别在 CI 里另抄）。
//
// Usage: node scripts/check-badge-words.mjs [--json]

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const JS_DIR = join(WEB, 'js');
const TERM_WORDS = join(JS_DIR, 'termWords.js');
const LOCALES = [join(JS_DIR, 'locales', 'zh-CN.js'), join(JS_DIR, 'locales', 'en.js')];

/** 作者裁定词表（2026-11-06 逐字）：键 = 概念，值 = 上屏词面。 */
const FACES = {
  kernel: 'kernel',
  dispatcher: 'dispatcher',
  node: 'node',
  subagent: 'subagent',
  delegate: 'delegate',
  agentflow: 'AgentFlow',
};
const FACE_VALUES = Object.values(FACES);
/** 中文译名（徽标位禁入；指身份词本身，不含叙事句）。 */
const CJK_FACES = ['内核', '分发器', '节点', '子代理'];
/** 同一概念的第二个词面（Capitalized / 空格变体）。 */
const ALT_FACES = ['Kernel', 'Dispatcher', 'Node', 'Subagent', 'Delegate', 'Agent Flow', 'AgentFlow '];
/** 徽标渲染槽类名（谁写这些槽，词就必须来自单源）。 */
const BADGE_SLOTS = ['bg-task-kind', 'bg-task-mode', 'bg-task-entity', 'task-delegate-badge-mode'];
/** 产词函数（word producers）：唯一允许的取词路径 = badgeWord()/badgeFaceOfKind()。 */
const PRODUCERS = [
  { file: join(JS_DIR, 'main', 'bgAgents.js'), names: ['bgDelegateBadgeWord', 'bgAgentflowWord', 'bgMemberKindWord'] },
];
/** 只允许消费单源的徽标槽宿主文件（各自至少要有一处 badgeWord/badgeFaceOfKind 调用）。 */
const SLOT_HOSTS = [
  join(JS_DIR, 'taskList.js'),
  join(JS_DIR, 'main.js'),
  join(JS_DIR, 'main', 'bgAgents.js'),
];

const problems = [];
const rel = (p) => relative(ROOT, p);
const add = (id, file, line, msg) => problems.push({ id, file: rel(file), line, msg });

/** 递归列出 web/js 下所有 .js。 */
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith('.js')) out.push(p);
  }
  return out;
}
const FILES = walk(JS_DIR);
const isLocale = (p) => LOCALES.includes(p);

/** 剔注释（块注释 + 行注释），保留行数（逐行替换为空）。 */
function codeOnly(src) {
  const noBlock = src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ''));
  return noBlock.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
}
const lines = (src) => src.split('\n');

// ── A：单源在场 ────────────────────────────────────────────────────────────
const termSrc = (() => {
  try { return readFileSync(TERM_WORDS, 'utf8'); } catch { return ''; }
})();
if (!termSrc) {
  add('A1', TERM_WORDS, 0, '徽标词单源模块缺失：js/termWords.js 必须存在（徽标词只从这里取）');
} else {
  if (!/export const BADGE_WORDS\s*=\s*Object\.freeze\(\{/.test(termSrc)) {
    add('A1', TERM_WORDS, 0, 'BADGE_WORDS 表未按 `export const BADGE_WORDS = Object.freeze({` 形态定义');
  }
  if (!/export function badgeWord\s*\(/.test(termSrc)) add('A1', TERM_WORDS, 0, '缺 badgeWord() 取词函数');
  if (!/export function badgeFaceOfKind\s*\(/.test(termSrc)) add('A1', TERM_WORDS, 0, '缺 badgeFaceOfKind()（协议/来源词 → 徽标词面单点归一）');
  for (const [k, v] of Object.entries(FACES)) {
    const re = new RegExp("'?" + k + "'?\\s*:\\s*'" + v.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + "'");
    if (!re.test(termSrc)) add('A1', TERM_WORDS, 0, `词表缺少裁定词面 ${k} → '${v}'`);
  }
}
// A2：词表定义唯一（任何其它文件不得再造一张表）。
for (const f of FILES) {
  if (f === TERM_WORDS) continue;
  const code = codeOnly(readFileSync(f, 'utf8'));
  if (/BADGE_WORDS\s*=\s*Object\.freeze\(/.test(code)) {
    add('A2', f, 0, '第二张徽标词表：徽标词只准在 js/termWords.js 定义一次');
  }
}

// ── B：徽标渲染槽零字面量 / 零译名 ─────────────────────────────────────────
const quoted = (w) => new RegExp("['\"`]" + w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + "['\"`]");
for (const f of FILES) {
  if (isLocale(f)) continue;
  const code = codeOnly(readFileSync(f, 'utf8'));
  lines(code).forEach((ln, i) => {
    if (!BADGE_SLOTS.some((s) => ln.includes(s))) return;
    for (const w of [...FACE_VALUES, ...ALT_FACES]) {
      if (quoted(w).test(ln)) add('B1', f, i + 1, `徽标槽里写死了词面 '${w}'（应取 badgeWord()）`);
    }
    for (const w of CJK_FACES) {
      if (quoted(w).test(ln)) add('B2', f, i + 1, `徽标槽里出现中文译名 '${w}'`);
    }
  });
}

// ── C：产词函数收敛 + 槽宿主必须消费单源 ──────────────────────────────────
for (const { file, names } of PRODUCERS) {
  const src = (() => { try { return readFileSync(file, 'utf8'); } catch { return ''; } })();
  for (const name of names) {
    const start = src.indexOf('function ' + name);
    if (start < 0) { add('C1', file, 0, `产词函数 ${name}() 不在场（单源消费点消失或改名）`); continue; }
    const next = src.indexOf('\nfunction ', start + 10);
    const body = src.slice(start, next > 0 ? next : src.length);
    const code = codeOnly(body);
    if (!/badgeWord\s*\(|badgeFaceOfKind\s*\(/.test(code)) {
      add('C1', file, 0, `${name}() 未从单源取词（缺 badgeWord()/badgeFaceOfKind() 调用）`);
    }
    // 把词面「写进界面」的四形态：return / textContent= / escapeHtml() / 拼接。
    for (const w of [...FACE_VALUES, ...ALT_FACES]) {
      const pats = [
        new RegExp('return\\s+[\'"`]' + w + '[\'"`]'),
        new RegExp('textContent\\s*=\\s*[\'"`]' + w + '[\'"`]'),
        new RegExp('escapeHtml\\(\\s*[\'"`]' + w + '[\'"`]'),
        new RegExp('\\+\\s*[\'"`]' + w + '[\'"`]\\s*\\+'),
      ];
      if (pats.some((re) => re.test(code))) add('C2', file, 0, `${name}() 把词面 '${w}' 写成字面量（应取单源）`);
    }
  }
}
for (const f of SLOT_HOSTS) {
  const src = (() => { try { return readFileSync(f, 'utf8'); } catch { return ''; } })();
  const code = codeOnly(src);
  const hasSlot = BADGE_SLOTS.some((s) => code.includes(s));
  if (hasSlot && !/badgeWord\s*\(|badgeFaceOfKind\s*\(/.test(code)) {
    add('C3', f, 0, '本文件渲染徽标槽但未从单源取词（无 badgeWord()/badgeFaceOfKind()）');
  }
}

/** 徽标键形（locale 里「身份/类型词」键族；叙述句/说明标签不在此形内——界线写进
 *  文件头：徽标位管，叙事不管）。 */
const BADGE_KEY_SHAPE = /(^|\.)(kind|mode)[A-Z.]/;

// ── D：译名退役（locale 键族 + 语言包值 + 代码字面量）───────────────────
for (const f of LOCALES) {
  const src = (() => { try { return readFileSync(f, 'utf8'); } catch { return ''; } })();
  if (!src) { add('D1', f, 0, '语言包缺失'); continue; }
  const code = codeOnly(src);
  lines(code).forEach((ln, i) => {
    if (/'(task\.mode\.|subagents\.kind)/.test(ln)) {
      add('D1', f, i + 1, '退役键族仍在场（徽标词不再走 locale：task.mode.* / subagents.kind*）');
    }
    const m = ln.match(/^\s*'([^']+)'\s*:\s*'([^']*)'/);
    if (m && BADGE_KEY_SHAPE.test(m[1]) && CJK_FACES.includes(m[2].trim())) {
      add('D2', f, i + 1, `徽标键 ${m[1]} 把词译成了「${m[2].trim()}」（徽标词面 = 英文原词）`);
    }
  });
}
for (const f of FILES) {
  if (isLocale(f)) continue;
  const code = codeOnly(readFileSync(f, 'utf8'));
  lines(code).forEach((ln, i) => {
    for (const w of CJK_FACES) {
      if (quoted(w).test(ln)) add('D3', f, i + 1, `代码里出现中文徽标词字面量 '${w}'`);
    }
  });
}

// ── E：概念单面（词面字面量只准在单源里；徽标位与产词函数禁变体）─────────
for (const f of FILES) {
  if (f === TERM_WORDS || isLocale(f)) continue;
  const code = codeOnly(readFileSync(f, 'utf8'));
  lines(code).forEach((ln, i) => {
    const inSlot = BADGE_SLOTS.some((s) => ln.includes(s));
    for (const w of ALT_FACES) {
      if (!new RegExp("['\"`]" + w + "['\"`]").test(ln)) continue;
      // 数据面记账词（协议 kind / 权限矩阵）不是徽标面 —— 只判徽标槽行。
      if (inSlot) add('E1', f, i + 1, `徽标位出现第二词面 '${w}'（同一概念只准一个词面）`);
    }
  });
}

// ── 输出 ──────────────────────────────────────────────────────────────────
const json = process.argv.includes('--json');
if (json) {
  console.log(JSON.stringify({ ok: problems.length === 0, problems }, null, 2));
} else {
  console.log('check-badge-words: 身份/类型徽标词单源门禁（A 单源 / B 槽零字面量 / C 生产者收敛 / D 译名退役 / E 概念单面）');
  console.log('  单源 = ' + rel(TERM_WORDS) + ' → ' + JSON.stringify(FACES));
  if (problems.length === 0) {
    console.log('  ✅ clean：徽标词只来自单源，中文译名零命中，同一概念单一面。');
  } else {
    for (const p of problems) {
      console.log(`  ❌ [${p.id}] ${p.file}${p.line ? ':' + p.line : ''} — ${p.msg}`);
    }
    console.log(`  ${problems.length} 条违规`);
  }
}
process.exit(problems.length === 0 ? 0 : 1);
