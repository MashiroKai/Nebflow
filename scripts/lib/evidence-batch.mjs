// scripts/lib/evidence-batch.mjs — shared EvidenceTemp-batch helper for scripts.
//
// Mirrors the directory convention of `nebflow.shared.EvidenceTemp` (the
// cross-platform temporary-evidence design): every short-lived, regenerable
// artifact batch lives under
//     <system tmp>/stand-evidence/<project>/<task>-<uuid>/<yyyyMMdd>/
// with a `manifest.json` (createdAt / project / batch) and NO `.active` marker,
// so the engine's TTL sweep (7 days, fail-closed on missing manifests) can
// reclaim it. Scripts must not scatter bare files in `/tmp` or write new
// evidence into repo-tree legacy dirs (`reports-evidence/`, `.nebflow/evidence/`)
// by default. Explicit `--out` overrides keep their meaning.
//
// Usage:
//   import { evidenceBatch, evidenceOut } from './lib/evidence-batch.mjs';
//   const OUT = valOf('--out', null) ? normalize(...) : evidenceBatch('glass', 'capture');
//   // or with an env override:
//   const OUT = evidenceOut(process.env.MY_OUT, 'glass', 'capture');

import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';

/** Create a unique, non-active evidence batch dir (manifest written, no .active). */
export function evidenceBatch(project, task) {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(project) || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(task)) {
    throw new Error(`invalid evidence segment: project=${project} task=${task}`);
  }
  const day = new Date().toISOString().slice(0, 10).replaceAll('-', '');
  const batch = `${task}-${randomUUID()}`;
  const dir = join(tmpdir(), 'stand-evidence', project, batch, day);
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    join(dir, 'manifest.json'),
    JSON.stringify({ createdAt: new Date().toISOString(), project, batch }) + '\n',
  );
  return dir;
}

/** `override || evidenceBatch(project, task)` — env/arg override keeps precedence. */
export function evidenceOut(override, project, task) {
  return override || evidenceBatch(project, task);
}
