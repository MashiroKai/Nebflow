#!/usr/bin/env node
// check-ime-guard.mjs — ⑤ 中文输入「禁再分叉」静态哨兵（批次 ⑤-C，作者裁定
// 2026-09-12，方案 §4.1 步骤 4）。
//
// Why a static gate: the IME takeover only holds if the composition test has
// exactly ONE home. Before the takeover there were 15 hand-rolled copies (three
// of them absent entirely), and copy #16 is guaranteed to appear the next time
// someone adds an input — unless a machine checks. So: any occurrence in
// src/main/resources/web/js/** that
//   ① reads `isComposing`,
//   ② compares `keyCode === 229` (with or without spaces),
//   ③ binds `compositionstart` / `compositionend` directly,
// MUST come from the single predicate / the single writer of
// `el.dataset.imeComposing` in `js/imeGuard.js` — never be hand-rolled.
//
// OCCURRENCE LEVEL (F-1 fix, 2026-09-12). The first version exempted any file
// that merely *imported* `imeGuard.js` (`IMPORT_RE` + `continue`), and the 16
// importing files are exactly where the next fork is most likely to land — the
// gate stayed green for a hand-rolled copy inserted into one of them (probed
// counterexample: 2 lines into `web/js/messages.js` ⇒ exit 0, i.e. false green,
// while the PASS line claimed "every composition check goes through
// imeGuard.js"). The verdict is now drawn per OCCURRENCE:
//   · `js/imeGuard.js` — the definition site, skipped as a whole (unchanged);
//   · an explicit, reason-carrying ALLOWLIST of single occurrences (below),
//     anchored to the frozen source TEXT — a file-wide exemption is impossible
//     by construction, and an entry cannot silently cover its own replacement;
//   · comment-only prose that merely mentions a trigger is documentation, not a
//     check (see `isCommentOnlyLine` / `isBlockCommentTail`); those lines are
//     listed in the output as "not counted" so the boundary stays auditable.
// Importing `imeGuard.js` no longer exempts anything on its own.
//
// Exit 0 = clean; exit 1 = violations listed as `file:line` with the offending
// snippet; exit 2 = the allowlist itself is malformed (missing reason / anchor).
// Same judgement locally and in CI (step "IME guard" in ci.yml, right after the
// checkJs / circular steps).
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
const TRIGGER_LABELS = TRIGGERS.map(([label]) => label);

/**
 * Explicit allowlist — ONE OCCURRENCE per entry, reason mandatory.
 *
 * Discipline: same as `scripts/verify-web-assets.mjs` EXEMPTIONS (an inspected
 * list where every entry carries its justification), one notch tighter — an
 * entry names a single occurrence by the exact frozen source text
 * (whitespace-normalised), never a file and never a bare line slot. All three
 * consequences are intended:
 *   · editing the frozen line — including replacing the frozen code with real
 *     code at the same line number — breaks the anchor, so the occurrence is
 *     reported again (an entry can never mask its own replacement);
 *   · an entry sanctions at most as many occurrences as it has anchors, so
 *     cloning a frozen line elsewhere in the file is reported;
 *   · `lines` is the recorded location, human-facing only: drift alone prints a
 *     NOTE and stays green, because the binding is the text, not the slot.
 * Adding an entry requires a verbatim "why it must be exempt" reason — the
 * self-check above refuses an entry without one.
 */
const ALLOWLIST = [
  {
    file: 'js/input.js',
    lines: [1175, 1176],
    label: 'direct composition binding',
    anchors: [
      "input.addEventListener('compositionstart', () => { view.composing = true; });",
      "input.addEventListener('compositionend', () => { view.composing = false; });",
    ],
    reason:
      '⑤-A4 (author ruling 2026-09-12) freezes the view-level `view.composing` flag for one ' +
      'release: 13 of the 14 pre-takeover fork points had no `view` object at all, and that ' +
      'ruling deliberately did not exhaust the potential readers of the field. These two lines ' +
      'only write that field — it has ZERO readers (the keyboard decision reads ' +
      '`input.dataset.imeComposing`, written by bindImeGuard at input.js:1170), so they cannot ' +
      'fork the composition decision; removing them is deferred to the batch that deletes the ' +
      'field (wave-1 FE verdict §11-6). Any edit to these two lines invalidates this entry.',
  },
];

const normalize = (s) => s.replace(/\s+/g, ' ').trim();

/**
 * Self-check: every allowlist entry must be well formed. A malformed entry
 * (no reason, no anchor, wrong shape) is exit 2, not a silent pass — an
 * exemption without a justification is exactly the failure mode this list
 * exists to prevent.
 */
function assertAllowlistShape() {
  const problems = [];
  ALLOWLIST.forEach((e, i) => {
    const id = `ALLOWLIST[${i}]`;
    if (!e || typeof e.file !== 'string' || !/^js\/[\w./-]+\.js$/.test(e.file)) {
      problems.push(`${id}: file must be a web-relative 'js/…js' path (occurrence level, not a directory)`);
    }
    if (!Array.isArray(e.lines) || e.lines.length !== 2 || !e.lines.every(Number.isInteger)) {
      problems.push(`${id}: lines must be [start, end] (recorded location of the frozen occurrence)`);
    }
    if (!TRIGGER_LABELS.includes(e.label)) {
      problems.push(`${id}: label must be one of ${TRIGGER_LABELS.join(' | ')}`);
    }
    if (!Array.isArray(e.anchors) || e.anchors.length === 0 || !e.anchors.every((a) => typeof a === 'string' && a.trim())) {
      problems.push(`${id}: anchors must be a non-empty array of frozen line texts (verbatim source)`);
    }
    if (typeof e.reason !== 'string' || e.reason.trim().length < 40) {
      problems.push(`${id}: reason missing/short — every exemption must justify itself in words`);
    }
  });
  const dupes = ALLOWLIST.map((e) => `${e.file}|${e.label}|${(e.anchors || []).map(normalize).join('~')}`)
    .filter((k, i, a) => a.indexOf(k) !== i);
  if (dupes.length) problems.push(`duplicate allowlist entry: ${[...new Set(dupes)].join(', ')}`);
  if (problems.length) {
    console.error('ime-guard SELF-CHECK FAILED (malformed allowlist entry — fix the list, not the gate):');
    for (const p of problems) console.error('  ' + p);
    process.exit(2);
  }
}

/**
 * A line whose first non-space characters are `//` or `/*` is a comment from
 * that character on: nothing on it can execute, so a trigger word there is prose
 * about the rule, not a check. (Wave-1 FE verdict §3 hand-audited exactly these
 * lines as 「注释」/「非代码」.)
 *
 * The rule is deliberately biased in ONE direction — towards reporting:
 *   · a TRAILING comment (`doSend(); // isComposing`) is still reported,
 *   · a trigger inside a string literal is still reported,
 *   · a `*`-continuation line of a block comment is only suppressed when
 *     `isBlockCommentTail` proves the block it belongs to (see below).
 * The reason for the bias: deciding where a comment starts inside a line needs
 * real string/regex awareness, and a wrong guess there hides executable code.
 * Over-reporting costs a comment reflow (move it onto its own line);
 * under-reporting costs the entire sentinel. Known residual false-positive
 * classes are reported in the F-1 batch report, never claimed away.
 */
function isCommentOnlyLine(text) {
  const t = text.trimStart();
  return t.startsWith('//') || t.startsWith('/*');
}

/**
 * True when `lines[i]` is a `*`-continuation line of a block comment, proven by
 * walking up through the comment body to its `/*` opener. The walk stops at any
 * line that is neither blank nor a `*`-line, and at a `*/` (which closes an
 * earlier block, so the current line is not inside a comment) — i.e. it only
 * succeeds on an unbroken `/** … * …` block, never on a guess.
 */
function isBlockCommentTail(lines, i, maxWalk = 500) {
  if (!lines[i].trimStart().startsWith('*')) return false;
  const floor = Math.max(0, i - maxWalk);
  for (let j = i - 1; j >= floor; j--) {
    const t = lines[j].trimStart();
    if (t.startsWith('*/')) return false; // an earlier block already closed
    if (t.startsWith('*')) continue; // comment body line
    if (t.startsWith('/*')) return true; // opener — this line is inside the block
    if (t.trim() === '') continue; // blank line inside a block comment
    return false; // anything else: not a comment block
  }
  return false;
}

function walk(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith('.js')) out.push(p);
  }
  return out;
}

assertAllowlistShape();

/** Per-entry pool of still-unconsumed anchors (+ the locations they matched). */
const allowlistState = ALLOWLIST.map((entry) => ({
  entry,
  remaining: entry.anchors.map(normalize),
  used: [],
}));

/** Consume one anchor of a matching entry; returns the entry or null. */
function takeAllowlist(file, label, rawLine) {
  const text = normalize(rawLine);
  for (const state of allowlistState) {
    const { entry } = state;
    if (entry.file !== file || entry.label !== label) continue;
    const idx = state.remaining.indexOf(text);
    if (idx === -1) continue;
    state.remaining.splice(idx, 1);
    return state;
  }
  return null;
}

const files = walk(DIR);
const violations = [];
const notCounted = []; // comment-only prose that merely mentions a trigger
const scanNotes = [];
let scanned = 0;
let definitionSite = null;

for (const f of files) {
  const rel = relative(join(ROOT, 'src', 'main', 'resources', 'web'), f).split('\\').join('/');
  if (rel === GUARD_REL) {
    definitionSite = rel;
    continue; // definition site: the predicate and the one writer live here
  }
  scanned++;
  const src = readFileSync(f, 'utf8');
  const lines = src.split('\n');

  // Collect hits per line first, so one physical line carrying two triggers is
  // reported once with both labels.
  for (let i = 0; i < lines.length; i++) {
    const labels = TRIGGERS.filter(([, re]) => re.test(lines[i])).map(([label]) => label);
    if (labels.length === 0) continue;
    const text = lines[i].trim();
    const uncovered = [];
    for (const label of labels) {
      const state = takeAllowlist(rel, label, lines[i]);
      if (state) state.used.push(i + 1);
      else uncovered.push(label);
    }
    if (uncovered.length === 0) continue; // every trigger on this line is allowlisted
    // One physical line is reported once, listing the labels that are NOT
    // covered by the allowlist.
    if (isCommentOnlyLine(lines[i]) || isBlockCommentTail(lines, i)) {
      notCounted.push(`${rel}:${i + 1}  [${uncovered.join(', ')}]`);
      continue;
    }
    violations.push(`${rel}:${i + 1}  [${uncovered.join(', ')}]  ${text.slice(0, 120)}`);
  }
}

// Allowlist bookkeeping — visibility for the reviewer, and staleness made loud.
const allowlistUsed = [];
for (const state of allowlistState) {
  const { entry, used, remaining } = state;
  const [lo, hi] = entry.lines;
  if (used.length) {
    allowlistUsed.push(`${entry.file}:${used.join(',')}  [${entry.label}]  ${normalize(entry.anchors[0]).slice(0, 96)}`);
    if (used.some((l) => l < lo || l > hi)) {
      scanNotes.push(
        `NOTE: allowlist entry ${entry.file} ${lo}-${hi} matched at line(s) ${used.join(',')} — ` +
        'location drifted, the frozen text still matches (binding is the text, not the slot).');
    }
  }
  if (remaining.length) {
    scanNotes.push(
      `NOTE: allowlist entry ${entry.file} ${lo}-${hi} has ${remaining.length} anchor(s) that matched ` +
      'nothing — the frozen occurrence is gone or was edited; drop the entry or re-record it.');
  }
}

for (const n of scanNotes) console.log(n);

if (violations.length === 0) {
  console.log(
    `ime-guard PASS: ${scanned} files scanned (${definitionSite} = definition site), ` +
    'no composition check outside js/imeGuard.js and the explicit allowlist.'
  );
  for (const line of allowlistUsed) console.log(`  allowlisted occurrence: ${line}`);
  for (const state of allowlistState) {
    const unused = state.entry.anchors.length - state.used.length;
    if (unused > 0) console.log(`  allowlisted occurrence: (${unused} anchor(s) of ${state.entry.file} unmatched — see NOTE above)`);
  }
  console.log(
    `  not counted — comment-only prose (${notCounted.length}): ${notCounted.join(', ') || '(none)'}`
  );
  process.exit(0);
}

console.error(`ime-guard GATE FAILURES (hand-rolled IME forks): ${violations.length} violation(s)`);
for (const v of violations) console.error('  ' + v);
console.error(`\nFix: import { isImeComposing, commitEnter, bindImeGuard } from './${GUARD_BASENAME}'`);
console.error('     and delete the local isComposing / keyCode 229 / composition* binding.');
console.error('     If a line is genuinely unavoidable, add ONE occurrence to the ALLOWLIST in');
console.error('     this script with a verbatim why-it-must-be-exempt reason (never a file-wide entry).');
process.exit(1);
