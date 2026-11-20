#!/usr/bin/env node
// Textual ratchet, not a Scala parser or a proof of hermeticity.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { resolve, relative, join } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const rules = [
  ['environment', /\b(?:System\.getenv|sys\.env)\b/, 'Inject an explicit test environment.'],
  ['absolute-path', /\/(?:Users|home)\//, 'Use a per-test temporary directory.'],
  ['fixed-port', /(?:\bport\s*[:=]\s*|\b(?:ServerSocket|InetSocketAddress)\s*\(\s*|localhost:|127\.0\.0\.1:)([1-9]\d{3,4})\b/i, 'Bind port 0 and use the assigned port.'],
  ['sleep', /\bThread\.sleep\s*\(/, 'Await a real signal with a deadline.'],
  ['real-data-root', /(?:~\/\.stand\b|\bstand\.json\b)/, 'Use an isolated test data root and configuration.'],
];
function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(join(dir, e.name)) : e.isFile() && e.name.endsWith('.scala') ? [join(dir, e.name)] : []);
}
const args = process.argv.slice(2);
const generate = args[0] === '--generate-baseline';
if (generate) args.shift();
const source = resolve(args[0] || join(root, 'src/test'));
const baselineFile = resolve(args[1] || join(root, 'scripts/test-hermetic-baseline.json'));
const baseline = generate ? { version: 1, entries: [] } : JSON.parse(readFileSync(baselineFile, 'utf8'));
const generated = [];
if (baseline.version !== 1 || !Array.isArray(baseline.entries)) throw new Error('Invalid baseline');
const remaining = new Map();
for (const key of baseline.entries) remaining.set(key, (remaining.get(key) || 0) + 1);
let total = 0, fresh = 0;
for (const file of walk(source).sort()) {
  const path = relative(source, file).split('\\').join('/');
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  lines.forEach((line, index) => {
    for (const [rule, pattern, hint] of rules) {
      if (!pattern.test(line)) continue;
      total++;
      const digest = createHash('sha256').update(line.trim()).digest('hex');
      const key = `${path}:${rule}:${digest}`;
      generated.push(key);
      const count = remaining.get(key) || 0;
      if (count) remaining.set(key, count - 1);
      else { fresh++; console.log(`${file}:${index + 1}: ${rule}: ${hint}`); }
    }
  });
}
if (generate) writeFileSync(baselineFile, `${JSON.stringify({ version: 1, entries: generated }, null, 2)}\n`);
console.log(`test-hermetic: ${total} findings, ${generate ? 0 : fresh} new; textual scan only`);
process.exitCode = !generate && fresh ? 1 : 0;
