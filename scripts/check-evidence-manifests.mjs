#!/usr/bin/env node
// check-evidence-manifests.mjs — cross-language manifest contract guard.
//
// EvidenceTemp's TTL sweep is fail-closed (nebflow.shared.EvidenceTemp.sweep:
// a date-dir without a manifest.json is skipped, never deleted). That makes
// "writes into the stand-evidence namespace" and "writes a manifest" ONE
// contract: a namespace writer without a manifest creates unbounded retention
// in exactly the place the design meant to be TTL-bounded.
//
// This guard upgrades that contract from a comment to a gate:
//   A-face (textual): every file under scripts/ and tests/ that constructs a
//     stand-evidence path inline (contains the literal 'stand-evidence') must
//     also write a manifest.json or import the shared helper
//     (scripts/lib/evidence-batch.mjs). Exemptions are file-by-file with
//     reasons below — no wildcards.
//   B-face (functional): the shared helper itself is imported and exercised —
//     a batch it creates must contain manifest.json and must NOT contain an
//     .active marker. The created batch is removed again before exit.
//
// Run: node scripts/check-evidence-manifests.mjs   (exit 0 = green, 1 = red)

import { existsSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = fileURLToPath(new URL('..', import.meta.url));

// File-by-file exemptions, each with the reason it may reference the namespace
// without writing a manifest. Reviewers: extending this list requires a reason.
const EXEMPT = new Map([
  ['scripts/check-no-tracked-artifacts.sh', 'git gate: the namespace appears as a pathspec, not a write point'],
  ['scripts/lib/writeguard.mjs', 'write policy: allows the namespace first segment; writes nothing itself'],
  ['scripts/lib/evidence-batch.mjs', 'the shared helper: it IS the manifest writer'],
  ['scripts/coldstart.sh', 'isolated instance HOME under the namespace (AGENTS §9 isolation domain) — an instance home, not an evidence batch'],
  ['scripts/check-evidence-manifests.mjs', 'this guard'],
]);

function* walk(dir) {
  let entries;
  try { entries = readdirSync(dir); } catch { return; }
  for (const name of entries) {
    if (name === 'node_modules' || name === '.git' || name === 'target') continue;
    const p = join(dir, name);
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) yield* walk(p);
    else if (/\.(mjs|cjs|js|sh)$/.test(name)) yield p;
  }
}

let failures = 0;

// ── A-face: textual contract ────────────────────────────────────────────────
let checked = 0;
for (const file of [...walk(join(REPO, 'scripts')), ...walk(join(REPO, 'tests'))]) {
  const rel = relative(REPO, file);
  let text;
  try { text = readFileSync(file, 'utf8'); } catch { continue; }
  if (!text.includes('stand-evidence')) continue;
  checked++;
  if (EXEMPT.has(rel)) continue;
  const writesManifest = text.includes('manifest.json');
  const usesSharedHelper = /lib\/evidence-batch(\.mjs)?['"]/.test(text) || /evidence-batch\.mjs/.test(text);
  if (!writesManifest && !usesSharedHelper) {
    failures++;
    console.error(`FAIL: ${rel} builds a stand-evidence path but neither writes a manifest.json nor uses scripts/lib/evidence-batch.mjs`);
    console.error('      (fail-closed sweep ⇒ a namespace batch without a manifest is never reclaimed)');
  }
}
console.log(`A-face: ${checked} file(s) reference the stand-evidence namespace (${EXEMPT.size} exempt)`);

// ── B-face: functional helper contract ─────────────────────────────────────
const { evidenceBatch } = await import('./lib/evidence-batch.mjs');
const batch = evidenceBatch('manifest-guard', 'self-check');
let manifestOk = false;
try {
  const manifest = JSON.parse(readFileSync(join(batch, 'manifest.json'), 'utf8'));
  manifestOk =
    manifest.project === 'manifest-guard' &&
    typeof manifest.batch === 'string' && manifest.batch.startsWith('self-check-') &&
    typeof manifest.createdAt === 'string' && !Number.isNaN(Date.parse(manifest.createdAt)) &&
    !existsSync(join(batch, '.active'));
} catch { manifestOk = false; }
rmSync(join(batch, '..', '..'), { recursive: true, force: true });   // remove the whole self-check project, no husks
console.log(`B-face: shared helper batch contract ${manifestOk ? 'OK' : 'FAIL'}`);
if (!manifestOk) failures++;

if (failures) {
  console.error(`\n${failures} violation(s) — fix the writer, do not extend the exemption list without a reason.`);
  process.exit(1);
}
console.log('OK: every stand-evidence writer upholds the manifest contract');
