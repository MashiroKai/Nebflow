#!/usr/bin/env node
// check-tag-format.mjs — release tag format gate: released tags carry NO `v`.
//
// Author ruling 2026-09-17 08:49 ("发版号不需要带 v") + batch root 2026-09-17
// #771-2: tag 未来版式 = 统一无 v. Already-published tags are immutable and stay
// untouched (不可逆); the CI release path (release.yml / auto-release.yml) builds
// the tag from the VERSION file VERBATIM.
//
// ── Why this gate exists (the defect it replaces) ───────────────────────────
// The pre-change guard probed `^v${{ ...version... }}$`, while this repository's
// date-scheme tag lineage is `2026.9.17` — NO v (evidence: worktree at
// eb58c010a, `git tag -l` = 47 tags, exactly two non-v names: the date-scheme
// release tag and a backup/* ref). A v-probe therefore can never match a
// published date-scheme tag: "Check if tag already exists" was dead code against
// the real lineage, so a re-run of the release path would try to tag/re-release
// an already-published version instead of skipping. Dropping the prefix makes
// the probe, the created tag and the GitHub Release name ONE form — and that one
// form is the form the lineage already uses.
//
// ── Judgement table (the whole gate) ────────────────────────────────────────
//   T1  Scan face    = `.github/workflows/release.yml`,
//                      `.github/workflows/auto-release.yml` (the two CI release
//                      paths) + `release/install.sh`, `release/install.ps1`
//                      (the installer download path). All four must exist.
//   T2  v-form       = 1) CI side: any `v${{ …version… }}` tag form, i.e. a
//                      literal `v` glued to a version expression — this is the
//                      shape of `git tag` / `tag_name:` / the tag-exists probe.
//                      2) Installer side: any `/v${VERSION}` or `/v$Version`
//                      path segment, i.e. a version-templated URL path carrying
//                      the `v` prefix (the `/download/v${VERSION}/…` fallback
//                      shape). Zero hits on BOTH faces = pass.
//   T3  no-v form    = the POSITIVE arm (converted, not deleted): each CI file
//                      must carry the bare version expression exactly once at
//                      each of the three build points —
//                        (a) the tag-exists probe  `grep -q "^${{ version }}$"`
//                        (b) the tag creation      `git tag "${{ version }}"`
//                        (c) the Release tag name  `tag_name: "${{ version }}"`
//                      T2 alone would also be satisfied by DELETING the tag
//                      logic; T3 is what makes this a format gate.
//   T4  Scoping      = the third-party ripgrep release URL
//                      (`…/BurntSushi/ripgrep/releases/tag/…`) is NOT our tag
//                      format — it is an upstream line whose `v` prefix was a
//                      FACT ERROR, not a live form: upstream ripgrep tags are
//                      BARE (`14.1.1`) — the v-prefixed release-tag URL answers
//                      404 while the bare one answers 200 (curl -I + GitHub ref
//                      API readings; tagfix batch 2026-09-17 un-prefixed both
//                      installer hints). T4 is unchanged in
//                      judgement: the installer-side detector stays scoped to
//                      OUR version variable (`${VERSION}` / `$Version`) on
//                      purpose — it must never flag a third-party URL — and T4
//                      asserts that scoping rather than assuming it (the probe
//                      samples below are synthetic over-broadness controls).
//   T5  Probe-before-trust = "zero hits" is only trusted after a positive probe
//                      has hit (same discipline as check-local-coupling.mjs S2 —
//                      a detector that hits nothing reports a clean tree
//                      forever). T5-1: the CI detector must hit synthetic
//                      v-forms. T5-2: the installer detector must hit a
//                      synthetic v-fallback path but MUST NOT hit the upstream
//                      ripgrep line (T4 control).
//
// Run: node scripts/check-tag-format.mjs
// Exit 0 = all green, 1 = drift detected (per-hit file:line readings printed).

import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const CI_FILES = [
  '.github/workflows/release.yml',
  '.github/workflows/auto-release.yml',
];
const INSTALL_FILES = ['release/install.sh', 'release/install.ps1'];

// T2-1: literal `v` glued to a version expression.
const V_CI = /v\$\{\{\s*[^}]*version[^}]*\}\}/gi;
// T2-2: version-templated URL path segment carrying the `v` prefix (our VERSION
// variable only — see T4).
const V_INSTALL = /\/v\$(?:\{VERSION\}|Version)(?![0-9A-Za-z_])/g;

// T3: the no-v form at each of the three build points, exactly once per file.
const NO_V_FORMS = [
  {
    label: '(a) tag-exists probe',
    re: /grep -q "\^\$\{\{ steps\.version\.outputs\.version \}\}\$"/,
  },
  {
    label: '(b) tag creation',
    re: /git tag "\$\{\{ needs\.build-jar\.outputs\.version \}\}"/,
  },
  {
    label: '(c) Release tag_name',
    re: /tag_name: "\$\{\{ needs\.build-jar\.outputs\.version \}\}"/,
  },
];

// T5: synthetic samples — the probe arms that make a zero-hit reading trustworthy.
const PROBE_CI_HITS = [
  '          git tag "v${{ needs.build-jar.outputs.version }}"',
  '          if git tag -l | grep -q "^v${{ steps.version.outputs.version }}$"; then',
  '          tag_name: "v${{ needs.build-jar.outputs.version }}"',
];
const PROBE_INSTALL_HIT =
  '    _download "$BASE/download/v${VERSION}/${JAR_NAME}" "$target" "$JAR_NAME"';
const PROBE_INSTALL_HIT_PS1 =
  '    $u = "https://github.com/x/y/releases/download/v$Version/$jar"';
const PROBE_INSTALL_UPSTREAM =
  '    log_warn "Manual download: https://github.com/BurntSushi/ripgrep/releases/tag/v${RG_VERSION}"';
const PROBE_INSTALL_UPSTREAM_PS1 =
  '        Write-Warn2 "Manual download: https://github.com/BurntSushi/ripgrep/releases/tag/v$RgVersion"';

const failures = [];
let checks = 0;

function pass(msg) {
  checks++;
  console.log(`OK:   ${msg}`);
}
function fail(msg) {
  checks++;
  failures.push(msg);
  console.log(`FAIL: ${msg}`);
}
function fresh(re, flags) {
  return new RegExp(re.source, flags);
}
function hits(text, re) {
  const out = [];
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    // one fresh matcher per line: a /g regex carries lastIndex state
    if (fresh(re, 'g').test(lines[i])) out.push({ line: i + 1, text: lines[i] });
  }
  return out;
}
function occurrences(text, re) {
  const m = text.match(fresh(re, 'g'));
  return m ? m.length : 0;
}
function show(rel, h) {
  return `${rel}:${h.line}: ${h.text.trim()}`;
}

// ── T1: scan face present ───────────────────────────────────────────────────
const files = {};
for (const rel of [...CI_FILES, ...INSTALL_FILES]) {
  const abs = join(ROOT, rel);
  if (!existsSync(abs)) {
    fail(`T1 scan face missing: ${rel}`);
    continue;
  }
  files[rel] = readFileSync(abs, 'utf8');
}
if (Object.keys(files).length !== CI_FILES.length + INSTALL_FILES.length) {
  console.log(`\nFAILED (${failures.length} problem(s)) — scan face incomplete`);
  process.exit(1);
}
pass(`T1 scan face present: ${[...CI_FILES, ...INSTALL_FILES].join(', ')}`);

// ── T5: probe-before-trust ──────────────────────────────────────────────────
for (const sample of PROBE_CI_HITS) {
  if (fresh(V_CI, 'g').test(sample)) {
    pass(`T5-1 CI v-form detector hits synthetic sample: ${sample.trim()}`);
  } else {
    fail(`T5-1 CI v-form detector MISSES synthetic sample (detector is dead): ${sample.trim()}`);
  }
}
for (const sample of [PROBE_INSTALL_HIT, PROBE_INSTALL_HIT_PS1]) {
  if (fresh(V_INSTALL, 'g').test(sample)) {
    pass(`T5-2 installer detector hits synthetic sample: ${sample.trim()}`);
  } else {
    fail(`T5-2 installer detector MISSES synthetic sample (detector is dead): ${sample.trim()}`);
  }
}
for (const sample of [PROBE_INSTALL_UPSTREAM, PROBE_INSTALL_UPSTREAM_PS1]) {
  if (fresh(V_INSTALL, 'g').test(sample)) {
    fail(`T4 installer detector wrongly matches the upstream ripgrep v-tag (over-broad): ${sample.trim()}`);
  } else {
    pass(`T4 installer detector is scoped to our VERSION variable (upstream v-tag untouched): ${sample.trim()}`);
  }
}

// ── T2: zero v-forms on both faces ──────────────────────────────────────────
for (const rel of CI_FILES) {
  const h = hits(files[rel], V_CI);
  if (h.length === 0) {
    pass(`T2 CI face, zero v-forms (v\${{ …version… }}): ${rel}`);
  } else {
    fail(`T2 CI face carries ${h.length} v-form(s): ${rel}`);
    for (const x of h) console.log(`        ${show(rel, x)}`);
  }
}
for (const rel of INSTALL_FILES) {
  const h = hits(files[rel], V_INSTALL);
  if (h.length === 0) {
    pass(`T2 installer face, zero v-prefixed version paths (/v\${VERSION} | /v\$Version): ${rel}`);
  } else {
    fail(`T2 installer face carries ${h.length} v-prefixed version path(s): ${rel}`);
    for (const x of h) console.log(`        ${show(rel, x)}`);
  }
}

// ── T3: the no-v form is present, exactly once, at each build point ─────────
for (const rel of CI_FILES) {
  for (const { label, re } of NO_V_FORMS) {
    const n = occurrences(files[rel], re);
    if (n === 1) {
      pass(`T3 no-v form present exactly once — ${label}: ${rel}`);
    } else {
      fail(`T3 no-v form count != 1 (got ${n}) — ${label}: ${rel}`);
      const h = hits(files[rel], re);
      for (const x of h) console.log(`        ${show(rel, x)}`);
    }
  }
}

// ── summary ─────────────────────────────────────────────────────────────────
if (failures.length === 0) {
  console.log(`\nPASS: tag format is prefix-free (no-v) across ${CI_FILES.length + INSTALL_FILES.length} files, ${checks} assertions.`);
  process.exit(0);
}
console.log(`\nFAILED (${failures.length} of ${checks} assertions):`);
for (const f of failures) console.log(`  - ${f}`);
process.exit(1);
