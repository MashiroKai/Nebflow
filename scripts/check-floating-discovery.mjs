#!/usr/bin/env node
// Conservative change detector, not an inference of whether a positioned box
// is interactive chrome. Existing source is the review baseline; every new
// positioning selector or mobile DOM-construction line requires human review.
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const web = 'src/main/resources/web';
// An explicit baseline is preferable in CI; locally compare against the
// integration branch. Missing refs must fail closed, never silently skip.
//
// Why the default baseline is the UNION OF ALL PARENTS rather than `HEAD^1`:
// a MERGE result is the union of its two sides, and every side was reviewed on
// its own road to the merge. `HEAD^1` is only the side that was merged INTO, so
// whatever the other side brought in reads as brand new against it. The real case
// (2026-11-19, landing the sync of `main` into `feat/base`, merge 7c8d05cfe,
// parents 2f11fb041 + 91dcb8ebc):
//   · web/css/searchField.css exists only on `main` ⇒ `git show HEAD^1:<it>`
//     fails ⇒ the whole file counted as "new" and the run hard-errored;
//   · web/css/ask.css and mobileLayer.css DO exist on `HEAD^1`, but `main`'s
//     version added rules ⇒ their already-reviewed `position:` rules were
//     reported as unreviewed additions.
// Pinning `FLOAT_DISCOVERY_BASE=main` cleared both, which is what told us the
// baseline derivation — not the tree — was wrong.
//
// So the default baseline of a file is every parent copy of it, compared together:
//   · ordinary commit — one parent, so behaviour is exactly the old `HEAD^1` one;
//   · merge commit    — content carried by either side counts as already reviewed;
//   · no parent has it — genuinely new file, empty baseline (unchanged semantic).
// The judgement itself is NOT relaxed: a candidate is still "added" only when it
// appears in NO parent copy, so anything that exists solely in the merge result
// (conflict resolution / evil merge) stays red. An explicit FLOAT_DISCOVERY_BASE
// is still a single ref with no fallback, so a file missing there keeps the empty
// baseline and the whole file stays flagged — the negative-control leg depends on
// that; do not turn it into a skip.
// Existence of `name` inside a commit is probed with `git cat-file -e`, never by
// parsing stderr: git translates "path … exists on disk, but not in <commit>"
// (the zh locale on this machine turned the old English-only regex into a hard
// error), and a localization must not decide whether the gate goes red.
function git(args) {
  return spawnSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
}

function baselineRefs() {
  const requested = process.env.FLOAT_DISCOVERY_BASE;
  if (requested) {
    const r = git(['rev-parse', '--verify', `${requested}^{commit}`]);
    if (r.status !== 0) throw new Error(`floating discovery baseline unavailable (${requested}): ${r.stderr.trim()}; set FLOAT_DISCOVERY_BASE to an available reviewed commit`);
    return [r.stdout.trim()];
  }
  // Comparing HEAD to itself would silently approve every committed addition on
  // the integration branch, so the baseline is always strictly older than HEAD.
  const r = git(['rev-list', '--parents', '-n', '1', 'HEAD']);
  if (r.status !== 0) throw new Error(`floating discovery baseline unavailable (HEAD parents): ${r.stderr.trim()}; set FLOAT_DISCOVERY_BASE to an available reviewed commit`);
  const [, ...parents] = r.stdout.trim().split(/\s+/);
  if (parents.length === 0) throw new Error('floating discovery baseline unavailable (HEAD has no parent commit): set FLOAT_DISCOVERY_BASE to an available reviewed commit');
  return parents;
}

// Baseline texts for `name`: every parent copy that exists. A parent lacking the
// file contributes nothing (that side never had those lines); when no parent has
// it the baseline is a single empty text = new file (unchanged semantic).
function baselineFor(name, refs) {
  const previous = [];
  for (const ref of refs) {
    if (git(['cat-file', '-e', `${ref}:${name}`]).status !== 0) continue;
    const r = git(['show', `${ref}:${name}`]);
    // The refs were verified above and cat-file says the path is there, so a
    // failing read is a real error and must stay one (fail closed).
    if (r.status !== 0) return { error: `${name}: cannot read discovery baseline ${ref}: ${r.stderr.trim()}` };
    previous.push(r.stdout);
  }
  return { previous: previous.length ? previous : [''] };
}
function files(dir, suffix) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const p = join(dir, e.name);
    return e.isDirectory() ? files(p, suffix) : e.name.endsWith(suffix) ? [p] : [];
  });
}
export function cssCandidates(source) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, ' ');
  return [...clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)].flatMap(m => {
    const pos = [...m[2].matchAll(/(?:^|;)\s*position\s*:\s*(fixed|absolute|sticky)\s*(?:!important)?\s*(?=;|$)/gi)];
    return pos.map(p => `${m[1].replace(/\s+/g, ' ').trim()} :: ${p[1].toLowerCase()}`);
  });
}
export function dynamicCandidates(source) {
  return source.split('\n').map(line => line.trim()).filter(line =>
    /(?:createElement(?:NS)?\s*\(|\.innerHTML\s*(?:\+?=)|\.insertAdjacentHTML\s*\(|\.outerHTML\s*=|\.className\s*=|\.classList\.(?:add|toggle)\s*\(|\.style\.(?:position|cssText)\s*=|\.style\.setProperty\s*\(\s*['"]position['"])/.test(line)
    && !/^(?:\/\/|\*|\/\*)/.test(line));
}
function added(current, old) {
  const counts = new Map();
  for (const x of old) counts.set(x, (counts.get(x) || 0) + 1);
  return current.filter(x => { const n = counts.get(x) || 0; if (n) { counts.set(x, n - 1); return false; } return true; });
}
export function audit({ content, previous, name, kind }) {
  const extract = kind === 'css' ? cssCandidates : dynamicCandidates;
  // `previous` is one baseline text, or one per merge parent: a candidate is
  // unreviewed only when none of the reviewed copies contains it.
  const prior = (Array.isArray(previous) ? previous : [previous]).flatMap(src => extract(src));
  return added(extract(content), prior).map(x => `${name}: unreviewed ${kind === 'css' ? 'positioned selector' : 'dynamic DOM construction'}: ${x}`);
}
function run() {
  let refs;
  try { refs = baselineRefs(); } catch (error) { return [error.message]; }
  const css = files(join(root, web, 'css'), '.css');
  const js = files(join(root, web, 'js/mobile'), '.js');
  const errors = [];
  for (const path of [...css, ...js]) {
    const name = relative(root, path).split('\\').join('/');
    // Per-file baseline: every parent copy that exists, union-compared (see
    // baselineRefs — a merge parent may predate a file the other parent brought
    // in, and either side's reviewed content must count as already reviewed).
    const base = baselineFor(name, refs);
    if (base.error) { errors.push(base.error); continue; }
    errors.push(...audit({ name, kind: path.endsWith('.css') ? 'css' : 'js', content: readFileSync(path, 'utf8'), previous: base.previous }));
  }
  return errors;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const errors = process.argv[2] === '--pair'
    ? audit({ kind: process.argv[3], name: process.argv[5], previous: readFileSync(process.argv[4], 'utf8'), content: readFileSync(process.argv[5], 'utf8') })
    : run();
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('floating discovery ratchet: PASS');
}
