import test from 'node:test';
import assert from 'node:assert/strict';
import { CHECKS, selectChecks, runCheck, main } from '../run-checks.mjs';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { ESLint } from 'eslint';
import { fileURLToPath } from 'node:url';

const command = (source, timeoutMs = 2000) => ({ name: 'fixture', argv: [process.execPath, '-e', source], timeoutMs });

test('registry names are unique and all checks have explicit bounded argv', () => {
  assert.equal(new Set(CHECKS.map(check => check.name)).size, CHECKS.length);
  for (const check of CHECKS) {
    assert.ok(check.argv.length > 1);
    assert.ok(check.argv.every(arg => typeof arg === 'string'));
    assert.ok(check.timeoutMs > 0);
  }
  assert.ok(selectChecks('static').some(check => check.name === 'sdd'));
  assert.ok(CHECKS.find(check => check.name === 'js-types').timeoutMs > 120_000);
  assert.ok(!selectChecks('static').find(check => check.name === 'sdd').argv.includes('--base'));
  assert.ok(selectChecks('frontend').every(check => !check.argv.join(' ').includes('node_modules')));
  assert.deepEqual(selectChecks('sdd-changed', 'main')[0].argv.slice(-2), ['--base', 'main']);
  assert.throws(() => selectChecks('unknown'));
  assert.throws(() => selectChecks('sdd-changed'));
  assert.throws(() => selectChecks('static', 'main'));
});

test('runner records success, nonzero exit, spawn failure, signal and timeout', async () => {
  const logs = [];
  const options = { log: message => logs.push(message) };
  const success = await runCheck(command('process.exitCode = 0'), options);
  assert.equal(success.ok, true);
  assert.ok(success.elapsedMs >= 0);
  const failed = await runCheck(command('process.exitCode = 7'), options);
  assert.equal(failed.ok, false);
  assert.equal(failed.code, 7);
  const spawnFailure = await runCheck({ ...command(''), argv: ['/definitely-missing-check-executable'] }, options);
  assert.equal(spawnFailure.ok, false);
  assert.match(spawnFailure.error, /ENOENT/);
  const signal = await runCheck(command("process.kill(process.pid, 'SIGTERM')"), options);
  assert.equal(signal.ok, false);
  assert.ok(signal.signal);
  const timeout = await runCheck(command('setInterval(() => {}, 1000)', 150), options);
  assert.equal(timeout.ok, false);
  assert.equal(timeout.timedOut, true);
  assert.ok(logs.some(message => message.startsWith('PASS fixture')));
  assert.ok(logs.some(message => message.includes('timeout')));
});

test('timeout and interruption remove owned grandchildren', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'check-tree-'));
  const runnerUrl = new URL('../run-checks.mjs', import.meta.url).href;
  const isRunning = pid => {
    try { process.kill(pid, 0); }
    catch (error) { if (error.code === 'ESRCH') return false; throw error; }
    // An orphan may briefly be a zombie until the OS reaps it.
    if (process.platform !== 'win32') {
      const status = spawnSync('ps', ['-p', String(pid), '-o', 'stat='], { encoding: 'utf8', timeout: 2000 });
      return status.status === 0 && !status.stdout.trim().startsWith('Z');
    }
    return true;
  };
  try {
    for (const mode of ['timeout', 'SIGINT', 'SIGTERM']) {
      const pidFile = join(directory, `${mode}.pid`);
      const parentFile = join(directory, `${mode}.cjs`);
      const harnessFile = join(directory, `${mode}.mjs`);
      writeFileSync(parentFile, `
        const { spawn } = require('node:child_process');
        require('node:fs').writeFileSync(${JSON.stringify(pidFile + '.parent')}, String(process.pid));
        const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
        require('node:fs').writeFileSync(${JSON.stringify(pidFile)}, String(child.pid));
        setInterval(() => {}, 1000);
      `);
      writeFileSync(harnessFile, `
        import { runCheck } from ${JSON.stringify(runnerUrl)};
        import { existsSync } from 'node:fs';
        const mode = ${JSON.stringify(mode)};
        let attempts = 0;
        const timer = mode === 'timeout' ? null : setInterval(() => {
          if (existsSync(${JSON.stringify(pidFile)})) { clearInterval(timer); process.kill(process.pid, mode); }
          else if (++attempts > 100) { clearInterval(timer); process.exitCode = 2; }
        }, 20);
        const result = await runCheck({ name: 'tree-fixture', argv: [process.execPath, ${JSON.stringify(parentFile)}], timeoutMs: mode === 'timeout' ? 500 : 5000 });
        clearInterval(timer);
        if (result.ok || (mode === 'timeout' ? !result.timedOut : result.interrupted !== mode)) process.exitCode = 1;
      `);
      let pid;
      try {
        const result = spawnSync(process.execPath, [harnessFile], { encoding: 'utf8', timeout: 10_000, killSignal: 'SIGKILL' });
        pid = Number(readFileSync(pidFile, 'utf8'));
        assert.equal(result.status, 0, result.stdout + result.stderr);
        for (let attempt = 0; attempt < 40 && isRunning(pid); attempt++) await delay(50);
        assert.equal(isRunning(pid), false, `${mode} left grandchild ${pid} running`);
      } finally {
        let parentPid;
        try { parentPid = Number(readFileSync(pidFile + '.parent', 'utf8')); } catch { /* The fixture may not have started. */ }
        if (parentPid && isRunning(parentPid)) {
          if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(parentPid), '/T', '/F'], { timeout: 5000 });
          else process.kill(-parentPid, 'SIGKILL');
        }
        if (pid && isRunning(pid)) process.kill(pid, 'SIGKILL');
      }
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('lint covers new nested tooling tests and rejects undefined names', async () => {
  const eslint = new ESLint({ cwd: fileURLToPath(new URL('../..', import.meta.url)) });
  for (const filePath of ['scripts/tests/new.test.mjs', 'scripts/tests/nested/helper.mjs', 'scripts/run-checks.mjs']) {
    const [invalid] = await eslint.lintText('undefinedToolName();', { filePath });
    assert.ok(invalid.messages.some(message => message.ruleId === 'no-undef'), filePath);
    const [valid] = await eslint.lintText('console.log(process.platform);', { filePath });
    assert.equal(valid.errorCount, 0, filePath);
  }
});

test('arguments and malformed declarations fail closed', async () => {
  for (const args of [['--profile'], ['--base'], ['--oops'], ['--profile', 'missing']]) {
    await assert.rejects(main(args));
  }
  await assert.rejects(runCheck({ name: 'invalid', argv: ['node'], timeoutMs: 0 }));
});
