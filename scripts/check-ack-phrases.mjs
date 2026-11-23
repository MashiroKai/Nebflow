#!/usr/bin/env node
// check-ack-phrases.mjs — 零 report 块回合 ✻ 字句（ack 池）静态哨兵
// （ack-badge-pool 批 2026-11-23 · 作者令：「专门为这种类型写一批文字，表达 ack
//   的意思就行，然后文字风格类似」＋「无 /report 块的回合从该池取词；有 /report
//   的回合不动；轮换沿用现机制」）
//
// Why a gate：零 <report> 块回合的角标 ✻ 段从 think 池（星海漫游语气）切到 ack 池
// （收到/已办/轻量完成语气）。这层「池分派」此前没有机器判据——下一个改动者把
// pickTurnPhrase 退回 pickThinkingPhrase、或往池里塞一条没有 {d} 的文案、或两语言
// 包条数走岔，都不会有任何东西变红。本哨兵把四件事变成机器判据：
//
//   A 池非空且对齐   zh-CN / en 两语言包都定义 ack.0–ack.N-1（连续、无缺号、
//                    无越界号）；15 ≤ N ≤ 20（作者令 15–20 条）；两包条数相等，
//                    且 == durationBadges.js 的 ACK_COUNT 常量（单源，不许漂移）。
//   B 字数与占位     每条恰一个 {d} 时长占位（池的形态契约）；长度上限
//                    zh ≤ 22 / en ≤ 56 字符（[...value].length，{d} 按字面 3 字符
//                    计）——池是「短、轻」的一句，不是说明文；附带 think.* 每条
//                    仍恰一个 {d}（既有池的契约不被顺手破坏）。
//   C 取词路径       取词单点 = durationBadges.js：
//                    · import hasOutputBlock from '../outputTag.js'（与气泡零块面
//                      同一谓词，不许第二套块判据）；
//                    · createDurationBadgeElement 的 phrase 走 pickTurnPhrase
//                      (copyText, …)（copyText = 消息全文原文），不得退回裸
//                      pickThinkingPhrase；
//                    · pickTurnPhrase 体 = hasOutputBlock → think 池 / 否则 ack 池
//                      （「有 /report 的回合不动」）；
//                    · 调用面传全文：live chat.js renderDurationBadge 的
//                      stream.aiText、回放 persistence.js 的 m.text 与 m.answer；
//                    · turnGroup.headerSegments 仍以 meta.phrase 段领头（ack 字句
//                      要能上归因行）。
//   D 轮换沿用现机制 pickFromPool 单点 = seed != null 时 (seed % count + count) %
//                    count、否则 Math.random（live 种子 = 徽章计数、回放 = 消息
//                    下标，轮内必然前进 ⇒ 不连出同条）；两池共用同一实现。
//
// 边界：本闸只管 ack.* 池与其取词路径；think.* 池的文案内容、显式注入 phrase 的
// 面（harness 里直接传 phrase 字段的自选字句）不在判据内。
//
// Exit 0 = clean；exit 1 = 违规逐条列出判据 id；exit 2 = 环境错误。
// 本地：node scripts/check-ack-phrases.mjs；登记于 scripts/run-checks.mjs（static
// profile）与 Makefile（`make ack-phrases`）。
//
// Usage: node scripts/check-ack-phrases.mjs [--json]

import { readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLocales } from './lib/locale-entries.mjs';

const ROOT = process.env.ACK_GATE_ROOT
  ? fileURLToPath(new URL('file://' + process.env.ACK_GATE_ROOT))
  : fileURLToPath(new URL('..', import.meta.url));
// 负控腿：ACK_GATE_ROOT=<无本改动的主 checkout> 重跑本闸必红（判据对缺陷敏感）。
// 例：ACK_GATE_ROOT="$PWD" node scripts/check-ack-phrases.mjs（在主 checkout 里跑）。
const DURATION_BADGES = join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'chat', 'durationBadges.js');
const CHAT_JS = join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'chat.js');
const PERSISTENCE_JS = join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'persistence.js');
const TURNGROUP_JS = join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'turnGroup.js');
const LOCALES_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'locales');

/** 作者令条数窗（2026-11-23：「ack 文案池 15–20 条」）。 */
const POOL_MIN = 15;
const POOL_MAX = 20;
/** B 档长度上限（{d} 按字面 3 字符计；池 = 短轻一句，不是说明文）。 */
const ZH_CAP = 22;
const EN_CAP = 56;

const problems = [];
const rel = (p) => relative(ROOT, p);
const add = (id, msg) => problems.push({ id, msg });

/** 剔注释但保留整文件形态（块注释逐字符换空 + 行注释尾删），供 C/D 的正则。 */
function codeOnly(src) {
  const noBlock = src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ''));
  return noBlock.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
}
const readSrc = (p) => {
  try { return readFileSync(p, 'utf8'); } catch { return ''; }
};

// ── A：池非空且两语言包对齐 ────────────────────────────────────────────────
const { names, byFile } = readLocales(LOCALES_DIR);
if (!names.length) {
  console.error(`环境错误：找不到 ${rel(LOCALES_DIR)} 下的 locale 文件`);
  process.exit(2);
}
const perLocale = new Map(); // name → Map(index → value)
for (const name of names) {
  const map = new Map();
  for (const e of byFile.get(name)) {
    const m = e.key.match(/^ack\.(\d+)$/);
    if (m) map.set(Number(m[1]), e.value);
  }
  perLocale.set(name, map);
}
let poolCount = 0;
{
  const zhName = names.find((n) => n.startsWith('zh'));
  const enName = names.find((n) => n.startsWith('en'));
  if (!zhName || !enName) { add('A0', `缺语言包：zh=${zhName || '无'} en=${enName || '无'}`); }
  else {
    for (const name of [zhName, enName]) {
      const map = perLocale.get(name);
      if (map.size === 0) { add('A1', `${name} 没有任何 ack.* 条目（池缺席）`); continue; }
      const idxs = [...map.keys()].sort((a, b) => a - b);
      const contiguous = idxs.every((v, i) => v === i);
      if (!contiguous) add('A1', `${name} ack.* 键不连续（须 ack.0–ack.N-1，缺号/越界即红）：got [${idxs.join(',')}]`);
      if (idxs.length < POOL_MIN || idxs.length > POOL_MAX) {
        add('A1', `${name} 池条数 ${idxs.length} 超出作者令窗 [${POOL_MIN},${POOL_MAX}]`);
      }
      if (name === zhName) poolCount = idxs.length;
      else if (idxs.length !== poolCount) {
        add('A2', `两语言包条数不齐：zh=${poolCount} en=${idxs.length}`);
      }
    }
  }
}

// ── A3：ACK_COUNT 常量 == 池条数（单源，不许漂移）──────────────────────────
{
  const src = codeOnly(readSrc(DURATION_BADGES));
  if (!src) add('A3', `${rel(DURATION_BADGES)} 缺席`);
  const m = src.match(/const\s+ACK_COUNT\s*=\s*(\d+)/);
  if (!m) add('A3', 'durationBadges.js 缺 `const ACK_COUNT = <n>` 常量（池条数单源）');
  else if (poolCount > 0 && Number(m[1]) !== poolCount) {
    add('A3', `ACK_COUNT=${m[1]} 与 locale 池条数 ${poolCount} 不一致`);
  }
}

// ── B：字数与占位 ─────────────────────────────────────────────────────────
for (const name of names) {
  const lang = name.startsWith('en') ? 'en' : 'zh';
  const cap = lang === 'en' ? EN_CAP : ZH_CAP;
  for (const [idx, value] of perLocale.get(name)) {
    const hits = value.match(/\{d\}/g) || [];
    if (hits.length !== 1) {
      add('B1', `${name} ack.${idx} 的 {d} 占位数 = ${hits.length}（须恰 1）: ${value}`);
    }
    const measured = [...value].length;
    if (measured > cap) {
      add('B2', `${name} ack.${idx} 实测 ${measured} 字 > 上限 ${cap}: ${value}`);
    }
  }
  // think 池契约不被顺手破坏：每条仍恰一个 {d}。
  for (const e of byFile.get(name)) {
    if (!/^think\.\d+$/.test(e.key)) continue;
    const hits = e.value.match(/\{d\}/g) || [];
    if (hits.length !== 1) add('B3', `${name} ${e.key} 的 {d} 占位数 = ${hits.length}（既有契约 = 恰 1）`);
  }
}

// ── C：取词路径（单点在 durationBadges.js，调用面传全文）──────────────────
{
  const src = codeOnly(readSrc(DURATION_BADGES));
  if (!/import\s*\{\s*hasOutputBlock\s*\}\s*from\s*'\.\.\/outputTag\.js'/.test(src)) {
    add('C1', 'durationBadges.js 未从 outputTag.js 单源引入 hasOutputBlock（块判据不许第二套）');
  }
  // createDurationBadgeElement 体：phrase 必须走 pickTurnPhrase(copyText, …)。
  const fnAt = src.indexOf('export function createDurationBadgeElement');
  const fnBody = fnAt >= 0 ? src.slice(fnAt, src.indexOf('\n}', fnAt) + 2) : '';
  if (!fnBody) add('C2', 'createDurationBadgeElement 不在场');
  else {
    if (!/pickTurnPhrase\s*\(\s*copyText\s*,/.test(fnBody)) {
      add('C2', 'createDurationBadgeElement 的 phrase 未走 pickTurnPhrase(copyText, …)（零块回合不会换 ack 池）');
    }
    if (/pickThinkingPhrase\s*\(/.test(fnBody)) {
      add('C2', 'createDurationBadgeElement 体里仍裸调 pickThinkingPhrase（分派必须经 pickTurnPhrase）');
    }
  }
  // pickTurnPhrase 体：hasOutputBlock → think 池，否则 ack 池（「有 /report 的回合不动」）。
  const ptAt = src.indexOf('export function pickTurnPhrase');
  const ptBody = ptAt >= 0 ? src.slice(ptAt, src.indexOf('\n}', ptAt) + 2) : '';
  if (!ptBody) add('C3', 'pickTurnPhrase 不在场（取词分派单点消失）');
  else {
    if (!/hasOutputBlock\s*\(\s*String\s*\(\s*text\s*\|\|\s*''\s*\)\s*\)/.test(ptBody)) {
      add('C3', 'pickTurnPhrase 未以 hasOutputBlock(全文) 为分派判据');
    }
    if (!/pickThinkingPhrase\s*\(/.test(ptBody) || !/pickAckPhrase\s*\(/.test(ptBody)) {
      add('C3', 'pickTurnPhrase 分派缺腿：块面须 pickThinkingPhrase、零块面须 pickAckPhrase');
    }
  }
  // 调用面传全文原文（live = stream.aiText；回放 = m.text / m.answer）。
  const chat = codeOnly(readSrc(CHAT_JS));
  if (!/renderDurationBadge\s*\([^)]*activeView\.stream\.aiText\s*\)/.test(chat)) {
    add('C4', `${rel(CHAT_JS)} live 腿 renderDurationBadge 未传全文 activeView.stream.aiText`);
  }
  const pers = codeOnly(readSrc(PERSISTENCE_JS));
  const badgeCalls = pers.match(/createDurationBadgeElement\s*\(([^)]*)\)/g) || [];
  if (!badgeCalls.length) add('C5', `${rel(PERSISTENCE_JS)} 无 createDurationBadgeElement 调用（回放徽章腿消失）`);
  const joined = badgeCalls.join('\n');
  if (!/m\.text\s*\)/.test(joined)) add('C5', `${rel(PERSISTENCE_JS)} 回放 AI 腿未传全文 m.text`);
  if (!/m\.answer\s*\)/.test(joined)) add('C5', `${rel(PERSISTENCE_JS)} 回放应答腿未传全文 m.answer`);
  // header 仍以 badge 的 phrase 段领头（ack 字句要能上归因行）。
  const tg = codeOnly(readSrc(TURNGROUP_JS));
  if (!/if\s*\(\s*meta\.phrase\s*\)\s*parts\.push\s*\(\s*meta\.phrase\s*\)/.test(tg)) {
    add('C6', `${rel(TURNGROUP_JS)} headerSegments 的 ✻ phrase 段领头面被改（ack 字句上不了归因行）`);
  }
}

// ── D：轮换沿用现机制（pickFromPool 单点，seed 取模 + 随机回落）────────────
{
  const src = codeOnly(readSrc(DURATION_BADGES));
  const pf = src.match(/function\s+pickFromPool[\s\S]*?\n\}/);
  if (!pf) add('D1', 'pickFromPool 共用取词单点消失（两池必须同一轮换实现）');
  else {
    const body = pf[0];
    if (!/\(\s*seed\s*%\s*count\s*\)\s*\+\s*count\s*\)\s*%\s*count/.test(body)) {
      add('D1', 'pickFromPool 缺 seed 确定式取模轮换（live/回放的「不连出同条」判据面）');
    }
    if (!/Math\.floor\s*\(\s*Math\.random\s*\(\s*\)\s*\*\s*count\s*\)/.test(body)) {
      add('D1', 'pickFromPool 缺无种子随机回落（现机制的另一半）');
    }
  }
  for (const fn of ['pickThinkingPhrase', 'pickAckPhrase']) {
    const at = src.indexOf(`export function ${fn}`);
    const body = at >= 0 ? src.slice(at, src.indexOf('\n}', at)) : '';
    if (!/pickFromPool\s*\(/.test(body)) {
      add('D2', `${fn}() 未走 pickFromPool 共用单点（第二套轮换实现）`);
    }
  }
}

// ── 输出 ──────────────────────────────────────────────────────────────────
const json = process.argv.includes('--json');
if (json) {
  console.log(JSON.stringify({ ok: problems.length === 0, poolCount, problems }, null, 2));
} else {
  console.log('check-ack-phrases: 零 report 块回合 ✻ 字句池门禁（A 池非空对齐 / B 字数占位 / C 取词路径 / D 轮换同构）');
  console.log(`  池 = locales ack.0–ack.${poolCount - 1 || '?'}（${poolCount} 条）→ durationBadges.js ACK_COUNT 单源`);
  if (problems.length === 0) {
    console.log('  ✅ clean：池非空且两语言对齐，{d} 占位齐备，取词分派与轮换同构在单点上。');
  } else {
    for (const p of problems) console.log(`  ❌ [${p.id}] ${p.msg}`);
    console.log(`  ${problems.length} 条违规`);
  }
}
process.exit(problems.length === 0 ? 0 : 1);
