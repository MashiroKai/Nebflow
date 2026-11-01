#!/usr/bin/env node
// check-prompt-single-source.mjs — 提示词正文「单源」静态哨兵（提示词单文件批，2026-11-01）
//
// Why a static gate：提示词常量原本散在 6 类载体、十几处三引号字面量里，同一段纪律
// 被抄成 2–3 份（KG 落地纪律在 BuiltinAgents 里逐字重复两次、在 DelegateTool 里还有
// 一份改写版）。抄一份不会有任何东西变红，等发现时模型已经在读两套措辞。
// 现在正文单源落在 `src/main/scala/nebflow/prompts/PromptCatalog.scala`，
// 「同一段提示词是否还有第二个字面量副本」变成机器判据：
//
//   每条规则 = 一段有辨识度的提示词字面量 + 允许出现的文件与次数；
//   未列出的任何 src/main/scala 文件命中一次即红。
//
// ⚠ 两条判据边界（写在这里而不是留在脑子里 —— 否则会给人虚假安全感）：
//   1. **只抓逐字副本。** 已经改写过措辞的副本抓不到：`DelegateTool.scala` 里那份
//      KG 纪律是 471 字符的改写版（首句 `- Landing discipline (KG audit ①): …`），
//      与单源的 517 字符版本不同，本闸对它**必然放行**。改写版的收敛只能靠人工裁定
//      （改成引用 = 改模型可见字节 = 行为变更，须作者拍板），闸只负责防复发。
//   2. **只看 `src/main/scala`。** spec 里的 `contains("…")` 片段是断言锚不是提示词
//      载体，不在本闸范围（它们本就是「断言强度」的另一个话题）。
//
// Exit 0 = clean；exit 1 = 违规逐条列出 `file:line` + 实测/期望计数。
// Usage: node scripts/check-prompt-single-source.mjs

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SRC = join(ROOT, 'src', 'main', 'scala');
/** 唯一合法落点（相对 src/main/scala）。 */
const CATALOG = 'nebflow/prompts/PromptCatalog.scala';

/**
 * expectIn  : 该文件 -> 期望**字面量出现次数**（全等，不是下界）。
 * allowOther: 允许命中但计数不受约束的文件（写明理由）。
 */
const RULES = [
  // ── 角色正文（PromptCatalog.Roles）──
  {
    id: 'R1 Nebula 角色正文',
    literal: 'You are {{agent_display_name}}, the Nebflow orchestrator',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'root 系统提示词只准有这一份正文；改它 = 改模型可见字节，须在 catalog 内改。',
  },
  {
    id: 'R2 dispatcher 角色正文',
    literal: 'You are project-dispatcher: the per-project task dispatcher',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: '项目分发器正文单源。',
  },
  {
    id: 'R3 node 角色正文',
    literal: 'You are a task node running inside Nebflow',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: '节点面系统提示词单源（node_report 纪律的载体）。',
  },
  {
    id: 'R4 delegate 角色正文',
    literal: 'You are a Nebflow delegate — a background executor spawned by',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'delegate 面系统提示词单源（delegate_report 纪律的载体；与 node 面机械可分）。',
  },
  {
    id: 'R5 subagent 角色正文',
    literal: 'You are Subagent, a read-only recon specialist',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: '只读侦察子 agent 正文单源。',
  },

  // ── 共享片段（PromptCatalog.Shared；Defaults 侧只剩转派 val）──
  {
    id: 'S1 等待纪律（iron rule）',
    literal: '── Waiting discipline (iron rule) ──',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'delegate brief / 节点脚注 / subagent 三面同源，正文只准一处。',
  },
  {
    id: 'S2 插件能力查询行',
    literal: '[plugin discovery] Query installed plugin capabilities anytime',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: '装插件查询行单源（挂载权仍在派发侧）。',
  },
  {
    id: 'S3 output 块纪律',
    literal: '── Output discipline ──',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: '`<output>` 包裹纪律单源；多面注入（brief / 脚注 / subagent / root 两条）。',
  },
  {
    id: 'S4 SendMessage 边界',
    literal: '── SendMessage scope ──',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'SendMessage 只发 NebLink 的边界声明单源。',
  },
  {
    id: 'S5 KG 落地纪律（去重后）',
    literal: 'Knowledge-graph landing discipline (KG audit ①, 2026-10-28)',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: '本批去重项：原在 BuiltinAgents 出现两次（逐字相同），现在只剩 catalog 一处。',
  },

  // ── 压缩提示（PromptCatalog.Compact）──
  {
    id: 'C1 压缩 preamble',
    literal: 'Context compaction required — the conversation has grown too large',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: '压缩提示头单源（ReminderSignature 由它派生，不手抄）。',
  },
  {
    id: 'C2 Root 压缩正文',
    literal: 'You are NEBULA — the global orchestrator',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'Root profile 压缩正文单源。',
  },
  {
    id: 'C3 Manager 压缩正文',
    literal: 'You are a FLOW MANAGER.',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'Manager profile 压缩正文单源。',
  },
  {
    id: 'C4 Worker 压缩正文',
    literal: 'You are a FLOW WORKER.',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'Worker profile 压缩正文单源。',
  },
  {
    id: 'C5 Dispatcher 压缩正文',
    literal: 'You are a PROJECT DISPATCHER — a single-session task dispatcher',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'Dispatcher profile 压缩正文单源。',
  },
  {
    id: 'C6 Node 压缩正文',
    literal: 'You are a NODE WORKER — a one-shot task node',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'Node profile 压缩正文单源。',
  },
  {
    id: 'C7 压缩续跑句',
    literal: 'Continue the conversation from where it left off without asking',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: '续跑提示单源（`.trim` 随内容一起进 catalog）。',
  },

  // ── 分类器前导（PromptCatalog.Classifiers；措辞已分叉，本批只搬运）──
  {
    id: 'K1 JeV 分配器前导',
    literal: 'You are auditing task dispatch in an agent-orchestration platform. Below are (1) one dispatched node',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'JeV 分配器前导单源；⚠ 与 K2 是同一问题的两套措辞（已分叉），裁定待作者。',
  },
  {
    id: 'K2 mode 判定前导',
    literal: 'You are auditing task dispatch in an agent-orchestration platform. Below is one dispatched task brief,',
    expectIn: { [CATALOG]: 1 },
    allowOther: [],
    why: 'mode 判定前导单源；⚠ 与 K1 已分叉，本批不改措辞。',
  },
];

function walkScala(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walkScala(p));
    else if (e.name.endsWith('.scala')) out.push(p);
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

const files = walkScala(SRC);
const violations = [];

for (const rule of RULES) {
  const allowed = new Set([...Object.keys(rule.expectIn), ...rule.allowOther]);
  const seen = {};
  for (const f of files) {
    const rel = relative(SRC, f).split('\\').join('/');
    const src = readFileSync(f, 'utf8');
    const n = countOccurrences(src, rule.literal);
    if (n === 0) continue;
    seen[rel] = n;
    const expected = rule.expectIn[rel];
    if (expected === undefined && !allowed.has(rel)) {
      violations.push(
        `${rule.id}: 提示词正文出现在 catalog 之外 —— ${rel} 行 ${lineNumbers(src, rule.literal).join(',')}`
      );
    } else if (expected !== undefined && n !== expected) {
      violations.push(`${rule.id}: ${rel} 出现 ${n} 次，期望 ${expected} 次`);
    }
  }
  for (const [rel, expected] of Object.entries(rule.expectIn)) {
    if ((seen[rel] || 0) === 0) {
      violations.push(`${rule.id}: 未在 ${rel} 找到（期望 ${expected} 次）—— 单源落点丢失`);
    }
  }
  if (violations.length === 0 || process.env.PROMPT_SINGLE_SOURCE_VERBOSE) {
    console.log(`  ok  ${rule.id}  [${Object.entries(seen).map(([f, n]) => `${f}×${n}`).join(', ') || '0 命中'}]`);
  }
}

console.log(`prompt-single-source: ${RULES.length} rules, ${files.length} scala files scanned.`);
if (violations.length === 0) {
  console.log('PASS: 提示词正文全部单源（唯一合法落点 = src/main/scala/' + CATALOG + '）。');
  console.log('NOTE: 本闸只抓逐字副本；已改写措辞的副本抓不到（见脚本头注边界 1）。');
  process.exit(0);
}

console.error('prompt-single-source GATE FAILURES:');
for (const v of violations) console.error('  ' + v);
console.error('');
console.error('Fix: 把正文收回 src/main/scala/' + CATALOG + '，消费点改成引用该常量。');
console.error('     直接就地复制一份 = 第二处漂移面（本闸存在的理由）。');
for (const rule of RULES) {
  if (violations.some((v) => v.startsWith(rule.id))) console.error(`  · ${rule.id} — ${rule.why}`);
}
process.exit(1);
