#!/usr/bin/env node
// check-msgstyle-single-source.mjs — 消息面样式「字面量单源」静态哨兵
// （批次 ③-G，2026-09-12 波2，方案 §6.4 ③-G / 判据 ③A1·③A3·③A5）。
//
// Why a static gate: 好友消息面（#fm-chat-overlay）与 dropbox 面在收敛前各自
// 复写了同一批视觉字面量 —— 出向气泡底色 `rgba(91, 127, 191, 0.15)` 在
// friends.css 与 dropbox.css 逐字重复，气泡几何/max-width/元信息形态各写一套。
// 只靠人工审查 ⇒「改一处颜色必然漏另一处」（D2 型漂移），且下一面加入时不会有
// 任何东西变红。所以把「同值只准有一处字面量」变成机器判据：
//
//   每条规则 = 一个字面量 + 允许出现的文件与次数；其余任何 css 文件命中即红。
//   shared layer 的落点 = src/main/resources/web/css/sapphire.css
//   （index.html 里最后加载 = 设计系统标准层，同特异度平局由它胜出）。
//
// Exit 0 = clean；exit 1 = 违规逐条列出 `file:line` + 实测/期望计数。
// 本地与 CI 同判据（ci.yml 的 "Message style single source" 步骤，紧跟 IME guard）。
//
// Usage: node scripts/check-msgstyle-single-source.mjs

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
const SHARED = 'sapphire.css';

/**
 * expectIn  : 该文件 -> 期望**字面量出现次数**（全等，不是下界）。
 * allowOther: 允许命中但计数不受约束的文件（写明理由；主对话框自带自己的形态，
 *             不是本单源层的消费方）。
 * 未列出的任何 css 文件命中一次即红 —— 这正是「同一批值被第二个面复写」的形态。
 */
const RULES = [
  {
    id: 'A1 出向气泡底色（③A1）',
    literal: 'rgba(91, 127, 191, 0.15)',
    expectIn: { [SHARED]: 1 },
    allowOther: [],
    why: '出向气泡/文件卡的 sapphire tint 只准有一个字面量（--sapphire-tint-15）；两面 backgroundColor 必须逐字相等。',
  },
  {
    id: 'A3 气泡 max-width 同 token（③A3）',
    literal: '--msg-bubble-max-w: 78%',
    expectIn: { [SHARED]: 1 },
    allowOther: [],
    why: '好友面与 dropbox 面的 max-width 必须落到同一个 token，改 token 两侧同步变。',
  },
  {
    id: 'A3 面内不得再写死 78%（③A3）',
    literal: 'max-width: 78%',
    expectIn: {},
    allowOther: [],
    why: '面内写死数值即失去「改一处两侧同步」，命中即红。',
  },
  {
    id: 'A5 文件卡单一类名（③A5）',
    literal: '.msg-file-card {',
    expectIn: { [SHARED]: 1 },
    allowOther: [],
    why: '文件卡只准有一份规则（共享类名 .msg-file-card）；out 变体是修饰态，不是第二份规则。',
  },
  {
    id: 'A5 文件卡旧类名零残留（③A5）',
    literal: '.dropbox-file-card {',
    expectIn: {},
    allowOther: [],
    why: '旧类名若仍带一份规则，就等于同值双写。',
  },
  {
    id: 'A1/A2 气泡方向尾角 4px（②A1）',
    literal: 'border-bottom-right-radius: 4px',
    expectIn: { [SHARED]: 1 },
    allowOther: ['chat.css'],
    why: '尾角值是共享层单源；chat.css 是主对话框自己的形态定义（基准面，不在本层消费）。',
  },
  {
    id: 'A2/A4 元信息 pill 戒指阴影（②A3）',
    literal: '0 0 0 1px rgba(0, 0, 0, 0.04)',
    expectIn: { [SHARED]: 1 },
    allowOther: [],
    why: 'pill 的两段 ring 阴影是共享层单源，两个面不得各自复写。',
  },
];

function walkCss(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walkCss(p));
    else if (e.name.endsWith('.css')) out.push(p);
  }
  return out;
}

function countOccurrences(src, literal) {
  let n = 0;
  let idx = src.indexOf(literal);
  while (idx !== -1) {
    n++;
    idx = src.indexOf(literal, idx + literal.length);
  }
  return n;
}

function lineNumbers(src, literal) {
  const lines = src.split('\n');
  const hits = [];
  lines.forEach((l, i) => { if (l.includes(literal)) hits.push(i + 1); });
  return hits;
}

const files = walkCss(CSS_DIR);
const violations = [];
let checked = 0;

for (const rule of RULES) {
  checked++;
  const allowed = new Set([...Object.keys(rule.expectIn), ...rule.allowOther]);
  const seen = {};
  for (const f of files) {
    const rel = relative(CSS_DIR, f).split('\\').join('/');
    const src = readFileSync(f, 'utf8');
    const n = countOccurrences(src, rule.literal);
    if (n === 0) continue;
    seen[rel] = n;
    const expected = rule.expectIn[rel];
    if (expected === undefined && !allowed.has(rel)) {
      violations.push(
        `${rule.id}: \`${rule.literal}\` 出现在不允许的文件 ${rel}（行 ${lineNumbers(src, rule.literal).join(',')}）`
      );
    } else if (expected !== undefined && n !== expected) {
      violations.push(
        `${rule.id}: \`${rule.literal}\` 在 ${rel} 出现 ${n} 次，期望 ${expected} 次`
      );
    }
  }
  for (const [rel, expected] of Object.entries(rule.expectIn)) {
    if ((seen[rel] || 0) === 0) {
      violations.push(`${rule.id}: \`${rule.literal}\` 未在 ${rel} 找到（期望 ${expected} 次）—— 单源落点丢失`);
    }
  }
  if (violations.length === 0 || process.env.MSSTYLE_VERBOSE) {
    console.log(
      `  ok  ${rule.id}  [${Object.entries(seen).map(([f, n]) => `${f}×${n}`).join(', ') || '0 命中'}]`
    );
  }
}

console.log(`msgstyle-single-source: ${checked} rules, ${files.length} css files scanned.`);
if (violations.length === 0) {
  console.log(`PASS: 消息面同值字面量全部单源（共享层 = css/${SHARED}）。`);
  process.exit(0);
}

console.error('msgstyle-single-source GATE FAILURES:');
for (const v of violations) console.error('  ' + v);
console.error('\nFix: 把该值收回 css/' + SHARED + ' 的 "Shared message surface" 段（或对应的 token），');
console.error('     两个面只消费 token/共享类名，禁止就地复写字面量。');
for (const rule of RULES) {
  if (violations.some(v => v.startsWith(rule.id))) console.error(`  · ${rule.id} — ${rule.why}`);
}
process.exit(1);
