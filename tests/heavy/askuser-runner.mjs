#!/usr/bin/env node
// Opt-in real gateway fixture; refuses production roots and never signals processes.
import { spawn, spawnSync } from 'node:child_process';
import { readFileSync, mkdirSync, realpathSync } from 'node:fs';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { join, resolve } from 'node:path';

const root = resolve(process.cwd());
const run = process.env.ASK_RUN_ROOT;
if (!run || !realpathSync(run).startsWith('/private/tmp/qa-')) throw Error('existing /private/tmp/qa-* root required');
const cp = readFileSync(join(root, 'target/e2e-classpath.txt'), 'utf8').trim();
const selected = process.argv[2];
if (!['input', 'refresh'].includes(selected)) throw Error('select input or refresh');
const envBase = { ...process.env, TMPDIR: run };
const pickPort = async () => { const s = createServer(); s.listen(0, '127.0.0.1'); await once(s, 'listening'); const p = s.address().port; await new Promise(r => s.close(r)); if ([6138,8080,8097].includes(p)) throw Error('protected port'); return p; };
const free = async port => { const s = createServer(); return new Promise(resolve => { s.once('error', () => resolve(false)); s.listen(port, '127.0.0.1', () => s.close(() => resolve(true))); }); };
const bounded = (p, ms, label) => Promise.race([p, new Promise((_, reject) => setTimeout(() => reject(Error(`${label} timeout`)), ms).unref())]);
const launch = (cmd, args, env, label) => {
  const child = spawn(cmd, args, { cwd: root, env, stdio: ['pipe', 'pipe', 'pipe'] });
  const exit = new Promise((resolve, reject) => { child.once('exit', (code, signal) => resolve({ code, signal })); child.once('error', reject); });
  child.stdout.on('data', b => process.stdout.write(`[${label}] ${b}`));
  child.stderr.on('data', b => process.stderr.write(`[${label}] ${b}`));
  return { child, exit };
};
const home = join(run, `home-${selected}`), shots = join(run, `shots-${selected}`);
mkdirSync(shots, { recursive: true });
const port = await pickPort(), mockPort = await pickPort();
if (port === mockPort) throw Error('port collision');
const env = { ...envBase, NEBFLOW_HOME_DIR: home, MOCK_PORT: String(mockPort) };
const fixture = spawnSync(process.execPath, ['tests/fixtures/askuser-refresh/fixture-home.mjs', '--provision'], { cwd: root, env, encoding: 'utf8' });
console.log(`[${selected}] fixture exit=${fixture.status}`);
if (fixture.status !== 0) throw Error(fixture.stderr);
const mock = launch(process.execPath, ['tests/fixtures/askuser-refresh/mock-llm.mjs', String(mockPort)], env, 'mock');
const gateway = launch('java', [`-Duser.home=${home}`, '-cp', cp, 'nebflow.Main', '--foreground', '--home', home, '--port', String(port), '--no-browser'], { ...env, HOME: home, STAND_HOME: home, NEBFLOW_HOME: home, NEBFLOW_GATEWAY_PORT: String(port) }, 'gateway');
let failed = false;
try {
  await bounded(new Promise((resolve, reject) => {
    const attempt = async () => { try { if ((await fetch(`http://127.0.0.1:${port}/api/health`)).ok) return resolve(); } catch {} if (gateway.child.exitCode !== null) return reject(Error('gateway exited before readiness')); setTimeout(attempt, 500); }; attempt();
  }), 90000, 'gateway readiness');
  const token = JSON.parse(readFileSync(join(home, 'auth.json'), 'utf8'));
  const testEnv = { ...env, BASE_URL: `http://127.0.0.1:${port}`, TOKEN: token, SHOTS: shots, QA_SHOTS: shots, ASK_WAIT_MS: '30000' };
  const args = selected === 'input' ? ['tests/askinput-off-pending-text.spec.mjs'] : ['node_modules/@playwright/test/cli.js', 'test', 'tests/askuser-refresh-survive.spec.mjs'];
  const test = launch(process.execPath, args, testEnv, 'test');
  // Do not abandon a live child after a timeout: test runner enforces its own deadlines.
  const result = await test.exit;
  console.log(`[${selected}] test exit=${result.code} signal=${result.signal}`);
  if (result.code !== 0) failed = true;
} catch (err) { console.error(`[${selected}] ${err.message}`); failed = true; }
finally {
  if (gateway.child.exitCode === null) gateway.child.stdin.write('quit\n');
  try { const result = await bounded(gateway.exit, 60000, 'gateway shutdown'); const released = await free(port); console.log(`gateway pid=${gateway.child.pid} exit=${result.code} signal=${result.signal} port_free=${released}`); if (!released || result.code !== 0) failed = true; }
  catch (err) { console.error(`${err.message}; gateway pid=${gateway.child.pid} retained`); failed = true; }
  if (mock.child.exitCode === null) mock.child.stdin.write('quit\n');
  try { const result = await bounded(mock.exit, 30000, 'mock shutdown'); const released = await free(mockPort); console.log(`mock pid=${mock.child.pid} exit=${result.code} signal=${result.signal} port_free=${released}`); if (!released || result.code !== 0) failed = true; }
  catch (err) { console.error(`${err.message}; mock pid=${mock.child.pid} retained`); failed = true; }
}
process.exitCode = failed ? 1 : 0;
