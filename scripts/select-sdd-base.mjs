#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const oidPattern = /^[a-f0-9]{40}$/;

export function selectBase({ platform, event, head, candidate, cwd = process.cwd() }) {
  if (!['github', 'gitlab'].includes(platform) || !event || !/^[a-z_]+$/.test(event) ||
      (platform === 'github' && !['pull_request', 'push', 'workflow_dispatch'].includes(event))) throw new Error('Invalid platform or event');
  if (![head, candidate].every(value => oidPattern.test(value ?? '') && !/^0+$/.test(value))) throw new Error('Expected nonzero full lowercase commit OIDs');
  const git = args => {
    const result = spawnSync('git', args, { cwd, encoding: 'utf8', timeout: 20000 });
    if (result.error || result.signal || result.status !== 0) throw new Error(`Git validation failed: ${result.error?.message ?? result.stderr.trim()}`);
    return result.stdout.trim();
  };
  if (git(['rev-parse', 'HEAD']) !== head) throw new Error('Head does not match checkout');
  for (const oid of [head, candidate]) {
    if (git(['rev-parse', '--verify', '--end-of-options', `${oid}^{commit}`]) !== oid) throw new Error('OID is not a commit');
  }
  if (candidate === head) throw new Error('Base must be a strict ancestor');
  git(['merge-base', '--is-ancestor', candidate, head]);
  return candidate;
}

function main(args) {
  const options = {};
  for (let i = 0; i < args.length; i++) {
    const key = args[i];
    if (!['--platform', '--event', '--head', '--candidate'].includes(key) || Object.hasOwn(options, key.slice(2)) || !args[i + 1] || args[i + 1].startsWith('--')) throw new Error('Expected --platform, --event, --head and --candidate exactly once');
    options[key.slice(2)] = args[++i];
  }
  const result = selectBase(options);
  console.error(`SDD base ${result} from ${options.platform} event ${options.event}`);
  console.log(result);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); } catch (error) { console.error(`SDD base ERROR: ${error.message}`); process.exitCode = 1; }
}
