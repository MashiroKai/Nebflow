#!/usr/bin/env node
// Ask parity replaces the historical single-document write-scope gate.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const rootDefault = fileURLToPath(new URL('..', import.meta.url));
export function audit(exec, edits) {
  const errors = [];
  if (!exec) errors.push('missing execution source');
  else {
    if (/if\s+ask(?:GitViz|Session)\s+then\s+return/.test(exec)) errors.push('Ask-specific authorization early return');
    if (/\.runAskBash\s*\(/.test(exec)) errors.push('Ask-specific Bash route');
  }
  if (!edits) errors.push('missing edit source');
  else if (!/AskPathBoundary\.decide(?:Sync)?\s*\(/.test(edits)) errors.push('missing inline-review identity guard');
  return errors;
}
function main() {
  const args = process.argv.slice(2);
  if (args.length && (args.length !== 2 || args[0] !== '--root')) throw new Error('usage: --root <repo>');
  const root = args.length ? resolve(args[1]) : rootDefault;
  if (!existsSync(resolve(root, 'src/main/scala/nebflow'))) { console.error('ask parity: SKIP'); process.exitCode = 2; return; }
  const read = p => { const f = resolve(root, p); return existsSync(f) ? readFileSync(f, 'utf8') : null; };
  const errors = audit(read('src/main/scala/nebflow/agent/AgentSessionExecution.scala'), read('src/main/scala/nebflow/core/ask/AskEditReviews.scala'));
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('ask parity: OK (static routing checks only)');
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 2; }
}
