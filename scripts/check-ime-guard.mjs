#!/usr/bin/env node
// check-ime-guard.mjs — ⑤ 中文输入「禁再分叉」静态哨兵（批次 ⑤-C，作者裁定
// 2026-09-12，方案 §4.1 步骤 4）。
//
// Why a static gate: the IME takeover only holds if the composition test has
// exactly ONE home. Before the takeover there were 15 hand-rolled copies (three
// of them absent entirely), and copy #16 is guaranteed to appear the next time
// someone adds an input — unless a machine checks. So: any file under
// src/main/resources/web/js/** that
//   ① reads `isComposing`,
//   ② compares `keyCode === 229` (with or without spaces),
//   ③ binds `compositionstart` / `compositionend` directly,
// MUST import `imeGuard.js` (the single predicate + the single writer of
// `el.dataset.imeComposing`). `js/imeGuard.js` itself is the definition site
// and is exempt.
//
// Exit 0 = clean; exit 1 = violations listed as `file:line` with the offending
// snippet. Same judgement locally and in CI (step "IME guard" in ci.yml, right
// after the checkJs / circular steps).
//
// Usage: node scripts/check-ime-guard.mjs

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'js');
const GUARD_BASENAME = 'imeGuard.js';
const GUARD_REL = `js/${GUARD_BASENAME}`;

// ① / ② / ③ — each entry: [label, regex]
const TRIGGERS = [
  ['isComposing', /\bisComposing\b/],
  ['keyCode === 229', /\bkeyCode\s*===?\s*229\b/],
  ['direct composition binding', /composition(?:start|end)\b/],
];

// A file "imports the guard" when it has a static or dynamic module reference
// to it (side-effect-only imports count too — the guard is imported for a
// reason in every case, but the import form must not matter to the sentinel).
const IMPORT_RE = /(?:from|import)\s*\(?\s*['"][^'"]*imeGuard\.js['"]/;

function walk(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith('.js')) out.push(p);
  }
  return out;
}

const files = walk(DIR);
const violations = [];
let scanned = 0;

for (const f of files) {
  const rel = relative(join(ROOT, 'src', 'main', 'resources', 'web'), f).split('\\').join('/');
  if (rel === GUARD_REL) continue; // definition site
  scanned++;
  const src = readFileSync(f, 'utf8');
  const hits = [];
  for (const [label, re] of TRIGGERS) {
    const lines = src.split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (re.test(lines[i])) hits.push({ line: i + 1, label, text: lines[i].trim() });
    }
  }
  if (hits.length === 0) continue;
  if (IMPORT_RE.test(src)) continue; // uses the single predicate — sanctioned
  for (const h of hits) {
    violations.push(`${rel}:${h.line}  [${h.label}]  ${h.text.slice(0, 120)}`);
  }
}

if (violations.length === 0) {
  console.log(`ime-guard PASS: ${scanned} files scanned, every composition check goes through ${GUARD_BASENAME}.`);
  process.exit(0);
}

console.error('ime-guard GATE FAILURES (hand-rolled IME forks):');
for (const v of violations) console.error('  ' + v);
console.error(`\nFix: import { isImeComposing, commitEnter, bindImeGuard } from './${GUARD_BASENAME}'`);
console.error('     and delete the local isComposing / keyCode 229 / composition* binding.');
process.exit(1);
