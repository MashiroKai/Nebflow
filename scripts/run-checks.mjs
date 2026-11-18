#!/usr/bin/env node
// Shared checkout-only checks. Runtime, browser, JVM and installed-plugin gates stay separate.
import { spawn } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { isAbsolute, resolve } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const nodeCheck = (name, script, profiles = ['static', 'frontend']) => ({
  name, argv: [process.execPath, `scripts/${script}.mjs`], profiles, timeoutMs: name === 'js-types' ? 150_000 : 120_000,
});

const scriptTests = readdirSync(new URL('./tests/', import.meta.url))
  .filter(name => name.endsWith('.test.mjs')).sort().map(name => `scripts/tests/${name}`);
if (!scriptTests.length) throw new Error('No script unit tests found; refusing broad test discovery');

// Membership is declared once here, not copied into Make or CI command lists.
export const CHECKS = [
  nodeCheck('css-structure', 'check-css-structure'),
  nodeCheck('floating-clearance', 'check-floating-clearance'),
  nodeCheck('floating-discovery', 'check-floating-discovery'),
  nodeCheck('mobile-floating-inventory', 'check-mobile-floating-inventory'),
  nodeCheck('design-tokens', 'check-design-tokens'),
  nodeCheck('message-style', 'check-msgstyle-single-source'),
  nodeCheck('chat-ui', 'check-chat-ui'),
  { name: 'chat-ui-computed', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/chat-ui-computed.mjs'] },
  { name: 'mobile-canvas-hit-clearance', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/mobile-canvas-hit-clearance.spec.mjs'] },
  { name: 'mobile-decision-preview-occlusion', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/mobile-decision-preview-occlusion.spec.mjs'] },
  { name: 'media-gallery', profiles: ['frontend'], timeoutMs: 120_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/mobile-image-gallery.spec.mjs', 'tests/media-click-unify.spec.mjs', '--workers=1'] },
  nodeCheck('bubble-side', 'check-bubble-side'),
  nodeCheck('self-explaining', 'check-self-explaining'),
  nodeCheck('badge-words', 'check-badge-words'),
  nodeCheck('circular', 'check-circular'),
  nodeCheck('fixture-secrets', 'check-fixture-secrets'),
  nodeCheck('api-routes', 'check-api-routes'),
  nodeCheck('provider-panel', 'check-provider-panel'),
  nodeCheck('freeze-state', 'check-freeze-state'),
  nodeCheck('flat-buttons', 'check-flat-buttons'),
  nodeCheck('glass', 'check-glass-unify'),
  nodeCheck('freeze-silence', 'check-freeze-silence'),
  nodeCheck('freeze-replay', 'check-freeze-replay-silence'),
  nodeCheck('canvas-hint', 'check-canvas-hint-tokens'),
  nodeCheck('canvas-buttons', 'check-canvas-buttons'),
  nodeCheck('toggle', 'check-toggle-unify'),
  nodeCheck('scroll-pill', 'check-scroll-pill-center'),
  nodeCheck('input-surface', 'check-input-surface'),
  nodeCheck('dropdown-single-control', 'check-dropdown-single-control'),
  nodeCheck('mobile-canvas-strip', 'check-mobile-canvas-strip'),
  nodeCheck('panel-unify', 'check-panel-unify'),
  nodeCheck('settings-ios-rows', 'check-settings-ios-rows'),
  ...[
    ['js-types', 'check-js-types'], ['ime', 'check-ime-guard'],
    ['component-contract', 'check-component-contract'], ['takeover-identify', 'check-takeover-identify'],
    ['design-spec', 'check-design-spec-sync'], ['prompt-source', 'check-prompt-single-source'],
    ['prompt-imports', 'check-prompt-catalog-imports'], ['provider-models', 'check-provider-modellist'],
    ['local-coupling', 'check-local-coupling'], ['tag-format', 'check-tag-format'],
    ['evidence-manifests', 'check-evidence-manifests'], ['scala-layers', 'check-scala-layers'],
    ['dispatcher-seal', 'check-dispatcher-seal'],
    ['node-stuck-notify', 'check-node-stuck-notify'],
  ].map(([name, script]) => nodeCheck(name, script, ['static'])),
  { name: 'tooling-lint', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, 'node_modules/eslint/bin/eslint.js', 'eslint.config.mjs',
      'scripts/check-js-types.mjs', 'scripts/run-checks.mjs', 'scripts/check-sdd.mjs', 'scripts/check-openspec.mjs', 'scripts/tests/'] },
  { name: 'script-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, '--test', ...scriptTests] },
  { name: 'worktree-manifest', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/check-delivery.py', '--manifest-only'] },
  { name: 'cli-logo', profiles: ['static'], timeoutMs: 150_000,
    argv: ['python3', '-B', 'scripts/check-logo-matrix.py'] },
  { name: 'cli-logo-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/tests/test-cli-banner-payload.py'] },
  nodeCheck('sdd', 'check-sdd', ['static']),
  nodeCheck('sdd-changed', 'check-sdd', ['sdd-changed']),
];

export function selectChecks(profile, base, manifestTmp) {
  if (!['static', 'frontend', 'sdd-changed'].includes(profile)) throw new Error(`Unknown profile: ${profile}`);
  if (profile === 'sdd-changed' && !base) throw new Error('sdd-changed requires an explicit --base');
  if (profile !== 'sdd-changed' && base) throw new Error('--base is only valid with sdd-changed');
  if (manifestTmp && (profile !== 'static' || !isAbsolute(manifestTmp))) throw new Error('--manifest-tmp requires an absolute path and the static profile');
  return CHECKS.filter(check => check.profiles.includes(profile)).map(check => ({
    ...check, argv: [...check.argv, ...(base ? ['--base', base] : []),
      ...(manifestTmp && check.name === 'worktree-manifest' ? ['--tmp', manifestTmp] : [])],
  }));
}

// Each POSIX child owns its process group; never signal the caller's group.
async function terminateTree(child) {
  if (!Number.isInteger(child?.pid) || child.pid <= 1 || child.pid === process.pid) return;
  if (process.platform !== 'win32') {
    try { process.kill(-child.pid, 'SIGKILL'); }
    catch (error) { if (error.code !== 'ESRCH') throw error; }
    return;
  }
  await new Promise((resolveCleanup, rejectCleanup) => {
    const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', shell: false });
    const timer = setTimeout(() => {
      killer.kill();
      rejectCleanup(new Error('Process-tree cleanup timed out'));
    }, 5000);
    killer.once('error', error => { clearTimeout(timer); rejectCleanup(error); });
    killer.once('close', code => {
      clearTimeout(timer);
      if (code === 0) resolveCleanup();
      else rejectCleanup(new Error(`Process-tree cleanup failed: taskkill exit ${code}`));
    });
  });
}

export async function runCheck(check, { cwd = ROOT, log = console.log } = {}) {
  if (!Array.isArray(check.argv) || !check.argv.length || !check.argv.every(arg => typeof arg === 'string') ||
      !Number.isFinite(check.timeoutMs) || check.timeoutMs <= 0) throw new Error('Invalid check declaration');
  const start = performance.now();
  log(`START ${check.name}`);
  return new Promise(resolveResult => {
    let timedOut = false;
    let interrupted;
    let failure;
    let child;
    let timer;
    let heartbeat;
    let finished = false;
    let stopping = false;
    let cleanup;
    const finish = async (code, signal) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      clearInterval(heartbeat);
      await cleanup;
      process.removeListener('SIGINT', onInterrupt);
      process.removeListener('SIGTERM', onTerminate);
      const elapsedMs = Math.round(performance.now() - start);
      const ok = !failure && !timedOut && !interrupted && !signal && code === 0;
      const reason = interrupted ?? (timedOut ? 'timeout' : failure ? failure.message : signal ? `signal ${signal}` : `exit ${code}`);
      log(`${ok ? 'PASS' : 'FAIL'} ${check.name} (${elapsedMs}ms; ${reason})`);
      resolveResult({ name: check.name, ok, code, signal, interrupted, timedOut, error: failure?.message, elapsedMs });
    };
    const stop = () => {
      if (stopping || finished) return;
      stopping = true;
      cleanup = terminateTree(child).catch(error => { failure = error; });
      // Do not wait forever for close, even if inherited descriptors stay open.
      void cleanup.then(() => finish(null, interrupted ?? 'SIGKILL'));
    };
    const onInterrupt = () => { interrupted = 'SIGINT'; stop(); };
    const onTerminate = () => { interrupted = 'SIGTERM'; stop(); };
    process.on('SIGINT', onInterrupt);
    process.on('SIGTERM', onTerminate);
    try {
      child = spawn(check.argv[0], check.argv.slice(1), {
        cwd, stdio: 'inherit', shell: false, detached: process.platform !== 'win32',
      });
    } catch (error) {
      failure = error;
      void finish(null, null);
      return;
    }
    child.once('error', error => { failure = error; });
    child.once('close', (code, signal) => { void finish(code, signal); });
    timer = setTimeout(() => { timedOut = true; stop(); }, check.timeoutMs);
    heartbeat = setInterval(() => log(`RUNNING ${check.name} (${Math.round(performance.now() - start)}ms)`), 15_000);
  });
}

export async function main(args = process.argv.slice(2)) {
  let profile = 'static';
  let base;
  let manifestTmp;
  let list = false;
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--list' || arg === '--dry-run') list = true;
    else if (arg === '--profile' || arg === '--base' || arg === '--manifest-tmp') {
      const value = args[++index];
      if (!value || value.startsWith('--')) throw new Error(`Missing value for ${arg}`);
      if (arg === '--profile') profile = value;
      else if (arg === '--base') base = value;
      else manifestTmp = value;
    } else throw new Error(`Unknown argument: ${arg}`);
  }
  const checks = selectChecks(profile, base, manifestTmp);
  if (list) {
    for (const check of checks) console.log(`${check.name}: ${JSON.stringify(check.argv)} (timeout ${check.timeoutMs}ms)`);
    return 0;
  }
  let failures = 0;
  for (const check of checks) {
    const result = await runCheck(check);
    if (result.interrupted) return result.interrupted === 'SIGINT' ? 130 : 143;
    if (!result.ok) failures++;
  }
  console.log(`${profile}: ${checks.length - failures}/${checks.length} checks passed`);
  return failures ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(code => { process.exitCode = code; }).catch(error => {
    console.error(`Checks ERROR: ${error.message}`);
    process.exitCode = 2;
  });
}
