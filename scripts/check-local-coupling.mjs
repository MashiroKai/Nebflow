#!/usr/bin/env node
// check-local-coupling.mjs — local-coupling CI gate (STRICT-source edition).
//
// TaskBoard #491 (author-approved 2026-09-14): the repository must not ship a
// contributor's OWN machine/user identity. Prior audits (R1..R4) built their own
// pattern lists, which drifted from the in-repo authoritative list and covered
// FEWER members than it does. This gate therefore does not own a pattern list at
// all — it CONSUMES the repository's own list at run time and only enforces the
// layer that list marks fail-fast.
//
// Judgement table (the whole gate, verbatim):
//   S1  Judgement source  = the STRICT layer of `scripts/user-knowledge-patterns.txt`
//                           (parsed at run time: full-line `#` = comment, an
//                           inline `#` starts a trailing comment, blank lines are
//                           ignored, `# === STRICT ===` / `# === WIDE ===` switch
//                           the layer). There is NO second, hard-coded pattern
//                           list in this file — a parallel list is exactly the
//                           defect this gate replaces.
//   S2  Matching          = case-insensitive (the source header states the caller
//                           side matches case-insensitively). The `\b` word-boundary
//                           form must NOT be handed to a platform ERE dialect:
//                           on BSD grep, `\b` is not a word boundary and the scan
//                           silently reports ZERO hits — a false negative that
//                           looks like a clean tree (the R3-1 incident). This gate
//                           evaluates BOTH forms on every line — the native form
//                           and the portable form `(^|[^0-9A-Za-z_])…([^0-9A-Za-z_]|$)`
//                           — and asserts the two hit sets are EQUAL (control
//                           assertion; a divergence is RED, never a silent pass).
//                           See also the probe below: "no hits" is only trusted
//                           after a positive probe has hit. The assertion covers
//                           BOTH arm kinds — the STRICT arms and the
//                           run-time-derived identity arms (S5), which carry the
//                           same `\b`/portable pair; an arm whose two forms are
//                           never compared is the defect S2 exists to prevent.
//   S3  Scan face         = `src/main/**` (incl. `resources/`, `seed/`, `web/`)
//                           + build/packaging scripts + `.github/workflows/**`
//                           + `README.md`  (identical to the R4 judgement domain).
//                           `src/test/**` is reported as WARNING only (author
//                           ruling 2: the test face is moved out of the leak
//                           count). `src/main/resources/web/vendor/**` is EXEMPT
//                           (third-party shipped as-is).
//   S4  Owner allow-list  = configurable, defaults to the project's own GitHub org
//                           (`Branding.githubOrg`). A contributor may add their own
//                           org; this is a whitelist of LEGITIMATE identifiers and
//                           does not weaken the judgement source.
//   S5  Class criterion   = strings DERIVED at run time from the executing machine
//                           (HOME basename / `hostname -s` / `whoami`) must not
//                           appear in the scan face. Tokens shorter than 4 chars are
//                           ignored (they would only produce noise).
//                           MATCH FORM (author ruling 2026-09-14; precision fixed in
//                           r2): such a token is enrolled in TWO forms — (a) a
//                           WHOLE-WORD arm `\btoken\b`, in the same native +
//                           portable pair as S2, and (b) a GLUE arm (S5c) whose
//                           substring form still catches the token inside a longer
//                           identifier. The CI first-red of 2026-09-14 was that the
//                           derived token on a GitHub Actions runner is `runner`
//                           (HOME=/home/runner) and substring matching reported
//                           `NodeRunner` / `defaultRunner` / `GitRunner` … = 74 hits
//                           on a provably clean tree — but the same narrowing ALONE
//                           also loses real coverage (`myMashiros-MacBook-Pro` went
//                           RED → GREEN; the r2 review finding, on an arm with no
//                           backing in S1). The two jobs are therefore split: false
//                           positives are killed by the S5b stopword table (such a
//                           word never becomes an arm at all), and coverage is
//                           restored by (b). They are not interchangeable.
//   S5b Stopword table    = a derived token whose VALUE is a CI / generic environment
//                           word (STOPWORDS below: `runner` `home` `host` `ubuntu`
//                           `user` `work`) is NOT a contributor-machine identity, so
//                           it is not enrolled as an arm; the suppression is
//                           REPORTED (`[stop] …` line, `stopwordTokens` in --json) —
//                           never silent. A stopword can only ever drop a run-time
//                           DERIVED token: this gate's source of judgement (S1) is
//                           untouched by it, so "the gate is green because of it" is
//                           not a thing, exactly as with the S10 fallback lines. The
//                           classification is case-insensitive because the arm's own
//                           matcher is (S2) — otherwise a one-character case change
//                           would walk around the table.
//                           !! A LITERAL-ONLY GATE HAS ZERO COVERAGE FOR A
//                           CONTRIBUTOR'S OWN DIRECTORY !! — a pattern list that
//                           contains only the author's literals stays green on every
//                           other machine and defends nothing. That is why S5
//                           derives the tokens on the machine that runs the gate,
//                           and why S1 consumes a list contributors can extend.
//   S5c Glue coverage     = the closure assertion of the S5 match form. A derived
//                           identity token must be caught not only as a whole word
//                           but also INSIDE a longer identifier (glue forms:
//                           `user_<token>`, `<token>_notes`, `X<token>Y`) — that is
//                           what makes the arm a defence for a contributor whose
//                           directory name is a segment of a longer symbol. A
//                           narrowing that drops it is a SILENT coverage loss (the
//                           r2 fail: `myMashiros-MacBook-Pro` RED → GREEN on an arm
//                           whose token has no S1 backing). Mechanics: a token is
//                           enrolled with a glue arm UNLESS the arm set already
//                           catches its glue witness (S1's own form is an unbounded
//                           substring, so `kaiyu` needs no glue arm — the arm set is
//                           asked, not a hand-written list), and the closure is then
//                           re-asserted per token on the FULL arm set: a token whose
//                           glue witness is caught by NO arm is RED
//                           (`COVERAGE-GAP` line), never a silent green. A witness
//                           blanked by the S6 exclusion table (W5 masks the project's
//                           own org constant inside the token) is reported as
//                           `masked`, not counted as a gap — that masking layer's
//                           semantics are out of this batch's scope (r1 review §13.7
//                           defers it to a separate batch).
//   S6  Exclusion table   = same source as R4 table 2: W1/W2 (generic placeholder
//                           home dirs), W5 (project's own org constant), W6 (this
//                           repo's own documented placeholder precedent),
//                           W8 (platform-generic tokens), W9 (Windows placeholder
//                           / env forms). Semantics = token-replace: the excluded
//                           literal is blanked in the line, then the line is
//                           re-tested — a line whose only hit was the excluded
//                           token is suppressed (and counted).
//
//   S10 Fallback lines    = the explicit-exemption register (author ruling
//                           2026-09-14; the aliyun entry of batch 1 is the first
//                           one). SYNTAX — identical to S1, the gate has no second
//                           parser: a fallback line IS an ordinary pattern line of
//                           `scripts/user-knowledge-patterns.txt`, preceded by a
//                           comment block; the line is parsed by parseJudgementSource()
//                           under the same rules (full-line `#` = comment, inline `#`
//                           starts a trailing comment, blank lines ignored), and it is
//                           counted by `[src ] … wide=N`. CRITERION — a fallback entry
//                           is admissible only when all four hold:
//                             (a) it sits in the WIDE layer, i.e. outside the enforced
//                                 set, so an entry can never suppress a STRICT hit —
//                                 "the gate is green because of it" is not a thing this
//                                 gate does;
//                             (b) it is CLASS-SCOPED: the pattern covers the members of
//                                 one named class and nothing else. Blanket forms
//                                 (`.*`, `\S+`, `/`) are forbidden — they would swallow
//                                 hits outside the class (routing surface for a real
//                                 leak);
//                             (c) its comment block names the class, the concrete
//                                 in-repo member(s) (`file:line`) and the machine-checkable
//                                 reason the member is legitimate — never a verbal-only
//                                 exemption;
//                             (d) it is grep-able in the tree: `grep -n '<pattern>'
//                                 scripts/user-knowledge-patterns.txt` is the audit read.
//                           A contributor adds their own machine's class the same way.
//                           `(^|[^0-9A-Za-z_])…` is the portable word-boundary form (S2);
//                           do not write `\b` into a fallback line.
//
// NOT in this gate (deliberate): the WIDE layer is reference-only in the source
// itself (合法 product references, human review), and the "dangling design-note
// reference" class (author-local doc paths) is a cleanliness concern, not an
// identity leak — enforcing it here would make the gate red on legitimate
// product wording. Batch 1 clears that class in the tree; this gate does not
// re-introduce a second list to police it.
//
// Exit codes: 0 = clean, 1 = violation (or a vacuous/divergent matcher, or an
//   identity token with no glue coverage = RED),
//   2 = malformed invocation.
//
// Usage:
//   node scripts/check-local-coupling.mjs                     # the CI step
//   node scripts/check-local-coupling.mjs --root DIR          # gate another tree
//   node scripts/check-local-coupling.mjs --owner MYORG       # extra owner org
//   node scripts/check-local-coupling.mjs --json              # machine output
//   node scripts/check-local-coupling.mjs --probe             # probes + control only
//
// Offline, dependency-free, Node 18+.

import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { join, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { homedir, hostname, userInfo } from 'node:os';

const SCRIPT_DIR = fileURLToPath(new URL('.', import.meta.url));
const DEFAULT_ROOT = resolve(SCRIPT_DIR, '..');
const RULE_SRC = 'scripts/user-knowledge-patterns.txt';
const DEFAULT_OWNERS = ['MashiroKai'];
const MIN_DERIVED_LEN = 4;

// ── invocation ──────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const flags = { root: DEFAULT_ROOT, list: '', owners: [...DEFAULT_OWNERS], json: false, probe: false, quiet: false };
for (let i = 0; i < argv.length; i += 1) {
  const a = argv[i];
  if (a === '--root') flags.root = resolve(argv[++i] || '');
  else if (a === '--list') flags.list = resolve(argv[++i] || '');
  else if (a === '--owner') flags.owners.push((argv[++i] || '').trim());
  else if (a === '--json') flags.json = true;
  else if (a === '--probe') flags.probe = true;
  else if (a === '--quiet') flags.quiet = true;
  else {
    process.stderr.write(`check-local-coupling: unknown argument: ${a}\n`);
    process.exit(2);
  }
}
if (!existsSync(flags.root) || !statSync(flags.root).isDirectory()) {
  process.stderr.write(`check-local-coupling: --root is not a directory: ${flags.root}\n`);
  process.exit(2);
}
const listPath = flags.list || join(flags.root, RULE_SRC);
if (!existsSync(listPath)) {
  process.stderr.write(`check-local-coupling: judgement source missing: ${listPath}\n`);
  process.exit(2);
}

// ── S1: consume the authoritative list (no parallel list in this file) ──────
function parseJudgementSource(text) {
  const layers = { STRICT: [], WIDE: [] };
  let level = null;
  text.split(/\r?\n/).forEach((raw, idx) => {
    const hash = raw.indexOf('#');
    const body = (hash >= 0 ? raw.slice(0, hash) : raw).trim();
    const comment = hash >= 0 ? raw.slice(hash) : '';
    const marker = /===\s*(STRICT|WIDE)\s*===/.exec(comment);
    if (marker) {
      level = marker[1];
      return;
    }
    if (!body || !level) return;
    layers[level].push({ pattern: body, line: idx + 1 });
  });
  return layers;
}

const sourceText = readFileSync(listPath, 'utf8');
const layers = parseJudgementSource(sourceText);
if (layers.STRICT.length === 0) {
  process.stderr.write(`check-local-coupling: no STRICT patterns parsed from ${listPath}\n`);
  process.exit(2);
}

// ── S6: exclusion table (same source as R4 table 2) ─────────────────────────
function buildExclusions(owners) {
  return [
    { id: 'W1', why: 'generic placeholder home dir', pats: ['/Users/you/', '/Users/x/', '/Users/me/', '/Users/user/'] },
    { id: 'W2', why: 'generic placeholder home dir', pats: ['/home/user/', '/home/project/'] },
    { id: 'W5', why: 'project-owned org constant', pats: owners.filter(Boolean) },
    { id: 'W6', why: 'repo-documented placeholder address', pats: ['192.168.1.200'] },
    { id: 'W8', why: 'platform-generic token', pats: ['/private/tmp', '/usr/local/bin', '~/Downloads', '~/.ssh', 'arm64'] },
    { id: 'W9', why: 'Windows placeholder / env form', pats: ['C:\\Users\\name', 'C:\\Users\\x', 'C:\\Users\\<seg>', '%USERPROFILE%'] },
  ];
}

const exclusions = buildExclusions(flags.owners);
const exclusionRegexes = exclusions.map((e) => ({
  id: e.id,
  why: e.why,
  re: new RegExp(e.pats.map((p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'gi'),
}));

// token-replace: blank every excluded literal, then re-test the line
function scrub(line) {
  let out = line;
  let suppressed = 0;
  for (const ex of exclusionRegexes) {
    ex.re.lastIndex = 0;
    if (ex.re.test(out)) {
      suppressed += 1;
      ex.re.lastIndex = 0;
      out = out.replace(ex.re, '\u0000');
    }
    ex.re.lastIndex = 0;
  }
  return { line: out, suppressed };
}

// ── S2: two matcher forms + the control assertion ───────────────────────────
// native  : the pattern as written (JS RegExp — `\b` is a real word boundary)
// portable: every `\b` rewritten to an explicit non-word-char alternation, the
//           form that survives any regex dialect that has no `\b` at all.
function toPortable(pattern) {
  let out = '';
  let i = 0;
  while (i < pattern.length) {
    if (pattern[i] === '\\' && pattern[i + 1] === 'b') {
      const prev = out.slice(-1);
      const trailing = prev !== '' && /[0-9A-Za-z_)\]*+?.}]/.test(prev);
      out += trailing ? '([^0-9A-Za-z_]|$)' : '(^|[^0-9A-Za-z_])';
      i += 2;
    } else if (pattern[i] === '\\') {
      out += pattern.slice(i, i + 2);
      i += 2;
    } else {
      out += pattern[i];
      i += 1;
    }
  }
  return out;
}

const arms = [];
for (const p of layers.STRICT) {
  let native;
  let portable;
  try {
    native = new RegExp(p.pattern, 'i');
    portable = new RegExp(toPortable(p.pattern), 'i');
  } catch (err) {
    process.stderr.write(`check-local-coupling: STRICT pattern at line ${p.line} does not compile: ${err.message}\n`);
    process.exit(1);
  }
  arms.push({ id: `SRC:${p.line}`, kind: 'STRICT', pattern: p.pattern, native, portable });
}

// ── S5b: CI / generic-environment words are not a machine identity ──────────
// Author ruling 2026-09-14 (CI first-red fix; scope = match precision only). On a
// CI host the S5 sources hand over the runner's own environment words instead of a
// contributor's: GitHub Actions runs as HOME=/home/runner, on `ubuntu-latest`, with
// its workspace under `_work`. Such a value is generic vocabulary — it is not
// evidence of a contributor's machine, and it collides with the product's own
// vocabulary. One line per member, each with its reason; adding a member is a
// judgement call, never a convenience. This table can only drop a run-time DERIVED
// token — it cannot reach the STRICT source of judgement (S1).
const STOPWORDS = new Map([
  ['runner', 'GitHub Actions runner account — CI sets HOME=/home/runner'],
  ['home', 'generic path segment'],
  ['host', 'generic word'],
  ['ubuntu', 'CI default image and default user name'],
  ['user', 'generic word'],
  ['work', 'GitHub Actions workspace path segment — _work'],
]);

// ── S5: run-time derived machine identity (the arm a literal-only list misses) ─
function derivedTokens() {
  const found = new Map();
  const stopped = new Map();
  const add = (value, src) => {
    const v = String(value || '').trim();
    if (v.length < MIN_DERIVED_LEN) return;
    const why = STOPWORDS.get(v.toLowerCase());
    if (why) {
      if (!stopped.has(v)) stopped.set(v, { token: v, src, why });
      return;
    }
    if (!found.has(v)) found.set(v, src);
  };
  try {
    add(homedir().split(/[/\\]/).filter(Boolean).pop(), 'HOME basename');
  } catch { /* ignore */ }
  try {
    add(hostname().split('.')[0], 'hostname -s');
  } catch { /* ignore */ }
  try {
    add(userInfo().username, 'whoami');
  } catch { /* ignore */ }
  return {
    arms: [...found].map(([token, src]) => ({ token, src })),
    stopped: [...stopped.values()],
  };
}

const derived = derivedTokens();
const identity = derived.arms;
const stopwordTokens = derived.stopped;
for (const d of identity) {
  const escaped = d.token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // S5 (a) whole-word arm: native + portable (S2) — a longer identifier that merely
  // CONTAINS the token is not the token. Kept FIRST in the arm list on purpose: this
  // is the arm whose `\b`/portable pair makes the S2 control assertion live on the
  // derived arms (a token that starts or ends with a non-word char makes the two
  // forms disagree, and the divergence is RED) — the glue arm below is `\b`-free and
  // structurally cannot carry that witness.
  const pattern = `\\b${escaped}\\b`;
  arms.push({
    id: `ID:${d.src}`,
    kind: 'DERIVED',
    pattern,
    native: new RegExp(pattern, 'i'),
    portable: new RegExp(toPortable(pattern), 'i'),
  });
}

// ── S5c: glue coverage (the arm S5 (a) alone loses) ─────────────────────────
// A derived identity token must also be caught inside a longer identifier: the
// contributor this gate defends is not only the one whose directory name is a
// standalone word. The token is enrolled with a glue arm unless the arm set built
// so far (S1 STRICT + S5 (a)) already catches its glue witness — S1's own form is an
// unbounded substring, so `kaiyu` is covered there and needs no second arm; the
// decision is asked of the arm set, never hand-listed here.
function glueWitness(token) {
  // Word characters on BOTH sides: the tightest form of "inside a longer token".
  return `X${token}Y`;
}
function anyArmHits(text) {
  const { line } = scrub(text);
  return arms.some((arm) => arm.native.test(line) || arm.portable.test(line));
}

// Decided against the S1 + S5 (a) arm set (no glue arm enrolled yet), so the
// outcome does not depend on the order the tokens were derived in.
const glueTokens = identity.filter((d) => !anyArmHits(glueWitness(d.token)));
for (const d of glueTokens) {
  const escaped = d.token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // No `\b` — catching the token inside a longer identifier is the whole point of
  // this arm. A `\b`-free pattern IS its own portable form (`toPortable` rewrites
  // `\b` and nothing else), so the S2 pair here is structurally equal by definition,
  // not by a copied reference: the `\b`/portable control witness of the derived arm
  // set is carried by the S5 (a) arm above.
  arms.push({
    id: `ID:${d.src}:glue`,
    kind: 'DERIVED',
    pattern: escaped,
    native: new RegExp(escaped, 'i'),
    portable: new RegExp(toPortable(escaped), 'i'),
  });
}

// Closure assertion (S5c): on the FULL arm set, every identity token's glue form
// must be caught — by S1, by S5 (a), or by its own glue arm.
const glueGaps = [];
const glueMasked = [];
for (const d of identity) {
  const witness = glueWitness(d.token);
  const { line: blanked } = scrub(witness);
  if (!blanked.includes(d.token)) {
    glueMasked.push({ token: d.token, src: d.src });
    continue;
  }
  if (!anyArmHits(witness)) glueGaps.push({ token: d.token, src: d.src });
}

// ── probe: prove the matcher is not vacuous (S6 / the `\b` lesson) ──────────
// A pattern's own minimal witness is generated from the pattern text itself —
// never written down here (a written-down literal would (a) be a second pattern
// list and (b) put the very string back into the tree). "No hits" is only
// trustworthy once every arm has hit its own witness.
function witnessFor(pattern) {
  // One witness per alternation branch: a branch can be empty (`^`) or a guard
  // (`[^A-Za-z]`) — taking only the first branch would under-generate and turn a
  // working matcher into a false probe failure.
  return pattern.split('|').map((branch) => {
    const atoms = [];
    let i = 0;
    const candidates = ['0', 'a', '-', '.', '_', 'X', '1', '/'];
    while (i < branch.length) {
      const c = branch[i];
      if (c === '\\') {
        const n = branch[i + 1];
        if (n === 'b' || n === 'B') atoms.push('');
        else if (n === 'd') atoms.push('0');
        else if (n === 'w') atoms.push('a');
        else if (n === 's') atoms.push(' ');
        else atoms.push(n);
        i += 2;
        continue;
      }
      if (c === '[') {
        const end = branch.indexOf(']', i);
        const cls = branch.slice(i + 1, end < 0 ? branch.length : end);
        const negated = cls.startsWith('^');
        const body = negated ? cls.slice(1) : cls;
        let pick = '';
        for (const cand of candidates) {
          const inSet = new RegExp(`^[${body}]$`).test(cand);
          if (negated ? !inSet : inSet) { pick = cand; break; }
        }
        atoms.push(pick);
        i = end < 0 ? branch.length : end + 1;
        continue;
      }
      if (c === '{') {
        const end = branch.indexOf('}', i);
        const n = Number.parseInt(branch.slice(i + 1, end).split(',')[0], 10);
        const last = atoms.length ? atoms[atoms.length - 1] : '';
        if (Number.isFinite(n) && n > 0) atoms[atoms.length - 1] = last.repeat(n);
        i = end < 0 ? branch.length : end + 1;
        continue;
      }
      if (c === '+' || c === '*' || c === '?' || c === '(' || c === ')' || c === '^' || c === '$') {
        i += 1;
        continue;
      }
      atoms.push(c);
      i += 1;
    }
    return atoms.join('');
  }).filter((w) => w !== '');
}

const probe = [];
let probeFailures = 0;
for (const arm of arms) {
  const witnesses = witnessFor(arm.pattern);
  const nativeHit = witnesses.some((w) => arm.native.test(w));
  const portableHit = witnesses.some((w) => arm.portable.test(w));
  const ok = witnesses.length > 0 && nativeHit && portableHit;
  if (!ok) probeFailures += 1;
  probe.push({ id: arm.id, witnesses: witnesses.length, hit: ok });
}

if (flags.probe) {
  const out = { root: flags.root, arms: arms.length, probe };
  process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
  process.exit(probeFailures === 0 ? 0 : 1);
}

// ── S3: scan face ───────────────────────────────────────────────────────────
const VENDOR_PREFIX = 'src/main/resources/web/vendor/';
const BUILD_PACK_TOP = ['Makefile', 'Dockerfile', 'package.json', 'package-lock.json', 'jsconfig.json', 'brand.conf', 'VERSION'];
const isBuildPack = (p) =>
  p === 'build.sbt' || p.startsWith('project/') || p.startsWith('packaging/') || p.startsWith('release/') || BUILD_PACK_TOP.includes(p);

function faceOf(p) {
  if (p.startsWith(VENDOR_PREFIX)) return 'VENDOR';
  if (p.startsWith('src/main/')) return 'SHIP';
  if (p.startsWith('.github/workflows/')) return 'SHIP';
  if (p === 'README.md') return 'SHIP';
  if (isBuildPack(p)) return 'SHIP';
  if (p === RULE_SRC) return 'RULE';
  if (p.startsWith('src/test/')) return 'TEST';
  return 'OUT';
}

function listFiles(root) {
  try {
    const raw = execFileSync('git', ['-C', root, 'ls-files', '-z'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    return raw.split('\0').filter(Boolean);
  } catch {
    const out = [];
    const walk = (dir) => {
      for (const e of readdirSync(dir, { withFileTypes: true })) {
        if (e.name === '.git' || e.name === 'node_modules' || e.name === 'target') continue;
        const abs = join(dir, e.name);
        if (e.isDirectory()) walk(abs);
        else if (e.isFile()) out.push(relative(root, abs));
      }
    };
    walk(root);
    return out;
  }
}

function readLines(root, rel) {
  const abs = join(root, rel);
  try {
    const buf = readFileSync(abs);
    if (buf.length > 8 * 1024 * 1024) return null;
    if (buf.subarray(0, 8192).includes(0)) return null; // binary
    return buf.toString('utf8').split(/\r?\n/);
  } catch {
    return null;
  }
}

const allFiles = listFiles(flags.root);
const judged = [];
const warned = [];
let vendorSkipped = 0;
for (const f of allFiles) {
  const face = faceOf(f);
  if (face === 'SHIP') judged.push(f);
  else if (face === 'TEST') warned.push(f);
  else if (face === 'VENDOR') vendorSkipped += 1;
}

const hits = [];
const warnings = [];
let suppressed = 0;
let controlComparisons = 0;
const controlMismatches = [];

function scan(files, sink) {
  for (const rel of files) {
    const lines = readLines(flags.root, rel);
    if (!lines) continue;
    for (let n = 0; n < lines.length; n += 1) {
      const original = lines[n];
      if (!original) continue;
      const { line: scrubbed, suppressed: sup } = scrub(original);
      if (sup > 0 && scrubbed.replace(/\u0000/g, '').trim() === '') {
        suppressed += sup;
        continue;
      }
      for (const arm of arms) {
        const nativeHit = arm.native.test(scrubbed);
        const portableHit = arm.portable.test(scrubbed);
        // S2 control assertion — EVERY arm (STRICT and DERIVED alike): the native
        // `\b` form and the portable form must agree; a divergence is RED, never a
        // silent pass.
        controlComparisons += 1;
        if (nativeHit !== portableHit) {
          controlMismatches.push({ path: rel, line: n + 1, arm: arm.id });
        }
        if (nativeHit || portableHit) {
          sink.push({
            path: rel,
            line: n + 1,
            arm: arm.id,
            kind: arm.kind,
            pattern: arm.pattern,
            excerpt: redact(scrubbed, arm),
          });
          if (sup > 0) suppressed += sup;
          break;
        }
      }
    }
  }
}

function redact(line, arm) {
  const out = line.replace(arm.native, '«redacted»');
  return out.length > 200 ? `${out.slice(0, 200)}…` : out;
}

scan(judged, hits);
scan(warned, warnings);

const red = hits.length + probeFailures + controlMismatches.length + glueGaps.length;

// ── output ──────────────────────────────────────────────────────────────────
const sha = createHash('sha256').update(sourceText).digest('hex');
const summary = {
  root: flags.root,
  judgementSource: relative(flags.root, listPath) || listPath,
  judgementSha256: sha,
  strictPatterns: layers.STRICT.length,
  widePatterns: layers.WIDE.length,
  derivedIdentityArms: identity.length,
  derivedStopwords: stopwordTokens.length,
  derivedGlueArms: glueTokens.length,
  glueCoverageGaps: glueGaps.length,
  glueCoverageMasked: glueMasked.length,
  judgedFiles: judged.length,
  testFilesWarned: warned.length,
  vendorExemptFiles: vendorSkipped,
  suppressedLines: suppressed,
  probeFailures,
  controlComparisons,
  controlMismatches: controlMismatches.length,
  hits: hits.length,
  warnings: warnings.length,
  red,
};

if (flags.json) {
  process.stdout.write(`${JSON.stringify({
    summary,
    hits,
    warnings,
    controlMismatches,
    stopwordTokens,
    glueCoverage: { enrolled: glueTokens.map((d) => `${d.token} [${d.src}]`), masked: glueMasked, gaps: glueGaps },
  }, null, 2)}\n`);
} else if (!flags.quiet) {
  const L = [];
  L.push(`[gate] local-coupling (STRICT source) root=${flags.root}`);
  L.push(`[src ] ${summary.judgementSource}  strict=${layers.STRICT.length} wide=${layers.WIDE.length} sha256=${sha.slice(0, 16)}…`);
  L.push(`[face] ship=${judged.length} files (judged) | test=${warned.length} files (warning only) | vendor=${vendorSkipped} files (exempt)`);
  L.push(`[arm ] source-patterns=${layers.STRICT.length} + runtime-derived-identity=${identity.length}${identity.length ? ` (${identity.map((d) => d.src).join(', ')})` : ''}`);
  if (stopwordTokens.length) L.push(`[stop] ${stopwordTokens.length} runtime-derived token(s) classified as a CI/generic environment word — NOT enrolled as an arm (S5b): ${stopwordTokens.map((s) => `${s.token} [${s.src}] ${s.why}`).join('; ')}`);
  L.push(`[glue] S5c glue coverage: ${identity.length - glueGaps.length - glueMasked.length}/${identity.length} identity token(s) caught inside a longer identifier (${glueTokens.length} enrolled with a glue arm${glueTokens.length ? `: ${glueTokens.map((d) => `${d.token} [${d.src}]`).join(', ')}` : ''})${glueMasked.length ? `; mask-blanked, not counted: ${glueMasked.map((m) => `${m.token} [${m.src}]`).join(', ')}` : ''}`);
  for (const g of glueGaps) L.push(`COVERAGE-GAP ${g.token} [${g.src}] — no arm catches this identity token inside a longer identifier (S5c)`);
  L.push(`[probe] ${arms.length - probeFailures}/${arms.length} arms hit their generated witness (a matcher that hits nothing is vacuous, not green)`);
  L.push(`[ctl ] native \\b form vs portable form: ${controlComparisons} comparisons, ${controlMismatches.length} divergences`);
  if (suppressed > 0) L.push(`[skip] ${suppressed} token(s) blanked by the S6 exclusion table (W1/W2/W5/W6/W8/W9)`);
  for (const h of hits) L.push(`MATCH ${h.path}:${h.line}  [${h.arm}]  ${h.excerpt}`);
  for (const c of controlMismatches.slice(0, 20)) L.push(`CTL-DIVERGENCE ${c.path}:${c.line} arm=${c.arm}`);
  if (warnings.length) {
    L.push(`[warn] ${warnings.length} hit(s) on the test face (src/test/**) — reported, never blocking (author ruling 2)`);
    for (const w of warnings.slice(0, 5)) L.push(`WARN  ${w.path}:${w.line}  [${w.arm}]`);
    if (warnings.length > 5) L.push(`WARN  … (+${warnings.length - 5} more)`);
  }
  L.push(`SUM ship_hits=${hits.length} test_warnings=${warnings.length} probe_failures=${probeFailures} ctl_divergences=${controlMismatches.length} suppressed=${suppressed}`);
  L.push(`STATUS=${red === 0 ? 'GREEN' : 'RED'}`);
  process.stdout.write(`${L.join('\n')}\n`);
}

process.exit(red === 0 ? 0 : 1);
