#!/usr/bin/env node
// check-mention-trigger.mjs — mechanical gate for the mention-trigger flip
// (mention-cn batch, 2026-09-27). Node stdlib only, zero dependencies.
//
// What it asserts (against the REAL source files, never a copy):
//   (a) parseMentionToken's trigger gate in src/main/resources/web/js/
//       mentionComplete.js:
//         - '@' preceded by a CJK character, CJK punctuation, whitespace or
//           line start OPENS a mention token (the flip: Chinese sentences
//           without spaces now trigger anywhere);
//         - '@' preceded by an ASCII letter/digit (email-like 'foo@') is
//           still blocked (email defense kept);
//         - a whitespace inside the token still closes it (regression);
//         - line-start '@' still opens (regression).
//   (b) src/main/resources/seed/agents/Nebula/system.md carries the
//       Mention-hint line VERBATIM (exact full-line match).
//
// parseMentionToken and WHITESPACE_RE are extracted from the live file by
// ANCHORED extraction (fixed anchor lines + brace-depth scan). Extraction or
// shape failure exits 2 loudly — never a silent pass.
//
// Exit codes: 0 = all green | 1 = assertion red | 2 = extraction/shape failure.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC_PATH = join(root, 'src/main/resources/web/js/mentionComplete.js');
const SEED_PATH = join(root, 'src/main/resources/seed/agents/Nebula/system.md');

const fail2 = (msg) => {
  console.error('EXTRACT-FAIL: ' + msg);
  process.exit(2);
};

// ── (1) Anchored extraction from the real source ──────────────────────────
const src = readFileSync(SRC_PATH, 'utf8');
const lines = src.split('\n');

// Anchor 1: the WHITESPACE_RE declaration (single line).
const wsLine = lines.find((l) => /^const WHITESPACE_RE = \/.+\/;\s*$/.test(l));
if (!wsLine) fail2('WHITESPACE_RE declaration line not found in ' + SRC_PATH);

// Anchor 2: the parseMentionToken function block — from its exact signature
// line, brace-depth scan to the line that closes depth 0.
const startIdx = lines.findIndex((l) => /^function parseMentionToken\(input\) \{$/.test(l));
if (startIdx < 0) fail2('parseMentionToken anchor line not found in ' + SRC_PATH);
let depth = 0;
let endIdx = -1;
for (let i = startIdx; i < lines.length; i++) {
  for (const ch of lines[i]) {
    if (ch === '{') depth++;
    else if (ch === '}') depth--;
  }
  if (depth === 0) { endIdx = i; break; }
}
if (endIdx < 0 || endIdx <= startIdx) fail2('parseMentionToken block never closes (brace scan)');
const fnText = lines.slice(startIdx, endIdx + 1).join('\n');

// Shape checks on the extraction itself (exit 2, loud).
let exported;
try {
  exported = new Function(fnText + '\n' + wsLine + '\nreturn { WHITESPACE_RE, parseMentionToken };')();
} catch (e) {
  fail2('extracted snippet failed to evaluate: ' + e.message);
}
const { WHITESPACE_RE, parseMentionToken } = exported;
if (!(WHITESPACE_RE instanceof RegExp)) fail2('WHITESPACE_RE is not a RegExp');
if (!WHITESPACE_RE.test(' ') || WHITESPACE_RE.test('a')) {
  fail2('WHITESPACE_RE shape unexpected (must match whitespace, must not match "a")');
}
if (typeof parseMentionToken !== 'function') fail2('parseMentionToken did not evaluate to a function');

// ── (2) Behavior cases against the extracted real function ────────────────
// expect: 'token' => a live token object ({start, query}); 'null' => null.
const CASES = [
  { id: 'cjk-hanzi-prefix (FLIP)',            value: '帮我@',  caret: 3, expect: 'token' },
  { id: 'cjk-punct-fullwidth-period (FLIP)',  value: '。@nb',  caret: 4, expect: 'token' },
  { id: 'cjk-punct-fullwidth-paren (FLIP)',   value: '（@nb',  caret: 4, expect: 'token' },
  { id: 'whitespace-prefix (regression)',     value: ' @nb',   caret: 4, expect: 'token' },
  { id: 'line-start (regression)',            value: '@nb',    caret: 3, expect: 'token' },
  { id: 'ascii-letter-email (defense kept)',  value: 'foo@',   caret: 4, expect: 'null' },
  { id: 'ascii-digit (defense kept)',         value: 'a1@',    caret: 3, expect: 'null' },
  { id: 'space-inside-token (regression)',    value: '@nb fl', caret: 6, expect: 'null' },
];

const isLiveToken = (tok) =>
  tok !== null && typeof tok === 'object' &&
  Number.isInteger(tok.start) && typeof tok.query === 'string';

const results = [];
let red = 0;
for (const c of CASES) {
  const tok = parseMentionToken({ selectionStart: c.caret, value: c.value });
  const got = isLiveToken(tok) ? 'token' : (tok === null ? 'null' : 'shape!');
  const pass = got === c.expect;
  if (!pass) red++;
  results.push({ id: c.id, value: c.value, caret: c.caret, expect: c.expect, got, pass });
}

// Old word-start gate, emulated LOCALLY for contrast in the readout ONLY —
// never a pass criterion; every assertion above runs the real extracted code.
const oldGateToken = (value, caret) => {
  const before = value.slice(0, caret);
  const at = before.lastIndexOf('@');
  if (at < 0) return null;
  if (at > 0 && !/\s/.test(before[at - 1])) return null;
  if (/\s/.test(before.slice(at + 1))) return null;
  return { start: at, query: before.slice(at + 1) };
};
for (const r of results) {
  const c = CASES.find((x) => x.id === r.id);
  r.oldBehavior = oldGateToken(c.value, c.caret) === null ? 'null' : 'token';
}

// ── (3) Seed text assertion (verbatim full-line containment) ───────────────
const SEED_HINT_LINE = '- Mention hint: after completing a dispatched task, append a brief footer to your final reply: "Tip: use @<project name> in your message to route the next task to that project." Only include this hint when the user\'s original message did NOT contain an @-mention (they already know).';
const seedLines = readFileSync(SEED_PATH, 'utf8').split('\n');
const seedHasLine = seedLines.some((l) => l === SEED_HINT_LINE);
if (!seedHasLine) red++;
results.push({
  id: 'seed-mention-hint-line (verbatim containment)',
  value: SEED_PATH, caret: null, expect: 'exact line present', got: seedHasLine ? 'present' : 'absent',
  pass: seedHasLine, oldBehavior: 'n/a',
});

// ── (4) Verdict ─────────────────────────────────────────────────────────────
console.log('== check-mention-trigger ==  source: ' + SRC_PATH);
for (const r of results) {
  const mark = r.pass ? 'PASS' : 'FAIL';
  const v = r.value === null || r.value === undefined ? '' : ' value=' + JSON.stringify(r.value) + ' caret=' + r.caret;
  console.log(mark + '  ' + r.id + v + '  expect=' + r.expect + ' got=' + r.got + '  oldGate=' + r.oldBehavior);
}
if (red > 0) {
  console.error('RED: ' + red + ' failing assertion(s) of ' + results.length);
  process.exit(1);
}
console.log('GREEN: ' + results.length + '/' + results.length + ' assertions pass');
process.exit(0);
