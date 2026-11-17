// Compare the original mock merge with the real Scala legacy PUT semantics.
import { test, expect } from '@playwright/test';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';

// Original mock's no-tombstone branch: incoming open set overwrites previous tabs.
function originalMockMerge(previous, put) {
  const tombs = new Map((previous?.tombstones || []).map(t => [t.id, { ...t }]));
  for (const t of put.tombstones || []) {
    const old = tombs.get(t.id);
    tombs.set(t.id, old ? { ...old, closedAt: Math.max(old.closedAt, t.closedAt), dev: t.dev || old.dev, reopenedAt: t.reopenedAt ?? old.reopenedAt } : { id: t.id, closedAt: t.closedAt, dev: t.dev, reopenedAt: t.reopenedAt });
  }
  for (const r of put.reopened || []) {
    const old = tombs.get(r.id);
    if (old) tombs.set(r.id, { ...old, reopenedAt: Math.max(old.reopenedAt || 0, r.reopenedAt) });
  }
  const activeIds = new Set([...tombs.values()].filter(t => (t.reopenedAt || -Infinity) < t.closedAt).map(t => t.id));
  const seen = new Set();
  const tabs = (put.tabs || []).filter(t => t && t.id && !activeIds.has(t.id) && !seen.has(t.id) && seen.add(t.id));
  const activeTabId = put.activeTabId ?? previous?.activeTabId ?? null;
  return { v: 2, tabs, tombstones: [...tombs.values()], ...(activeTabId && !activeIds.has(activeTabId) ? { activeTabId } : {}) };
}

test('legacy stale snapshot without tombstone: real and original mock diverge', async () => {
  test.setTimeout(150000);
  const root = resolve(process.cwd());
  const cp = readFileSync(join(root, 'target/e2e-classpath.txt'), 'utf8').trim();
  const dir = mkdtempSync(join(tmpdir(), 'stand-canvas-legacy-'));
  const home = join(dir, 'home'), data = join(home, '.stand');
  mkdirSync(data, { recursive: true });
  writeFileSync(join(data, 'nebflow.json'), JSON.stringify({ workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' } }));
  writeFileSync(join(data, 'onboarding.json'), JSON.stringify({ state: 'skipped' }));
  const sock = createServer(); sock.listen(0, '127.0.0.1'); await once(sock, 'listening');
  const port = sock.address().port; await new Promise((ok, fail) => sock.close(e => e ? fail(e) : ok()));
  if ([6138, 8080, 8097].includes(port)) throw new Error('protected port selected');
  const base = `http://127.0.0.1:${port}`;
  const env = { PATH: process.env.PATH, HOME: home, STAND_HOME: data, NEBFLOW_HOME: data, NEBFLOW_GATEWAY_PORT: String(port), LANG: 'en_US.UTF-8' };
  const child = spawn('java', [`-Duser.home=${home}`, '-cp', cp, 'nebflow.Main', '--foreground', '--home', data, '--port', String(port), '--no-browser'], { cwd: root, env, stdio: ['pipe', 'pipe', 'pipe'] });
  let log = ''; child.stdout.on('data', d => { log += d; }); child.stderr.on('data', d => { log += d; });
  const exit = new Promise(r => child.once('exit', (code, signal) => r({ code, signal })));
  try {
    await expect.poll(async () => { if (child.exitCode !== null) throw new Error(`server exited ${child.exitCode}: ${log.slice(-3000)}`); try { return (await fetch(`${base}/api/health`)).status; } catch { return 0; } }, { timeout: 90000 }).toBe(200);
    const token = JSON.parse(readFileSync(join(data, 'auth.json'), 'utf8'));
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
    const older = { id: 'existing-a', title: 'Older', type: 'generic' };
    const newer = { id: 'server-new-b', title: 'Server newer', type: 'generic' };
    const initial = { v: 2, tabs: [older, newer] };
    const stale = { v: 2, tabs: [older] };
    expect((await fetch(`${base}/api/canvas-tabs`, { method: 'PUT', headers, body: JSON.stringify(initial) })).status).toBe(200);
    const before = await (await fetch(`${base}/api/canvas-tabs`, { headers })).json();
    expect(before.tabs.map(t => t.id)).toEqual(['existing-a', 'server-new-b']);
    expect((await fetch(`${base}/api/canvas-tabs`, { method: 'PUT', headers, body: JSON.stringify(stale) })).status).toBe(200);
    const afterResponse = await fetch(`${base}/api/canvas-tabs`, { headers }); expect(afterResponse.status).toBe(200);
    const after = await afterResponse.json();
    const mock = originalMockMerge(originalMockMerge(null, initial), stale);
    console.log('initial=' + JSON.stringify(before.tabs.map(t => t.id)));
    console.log('stale=' + JSON.stringify(stale.tabs.map(t => t.id)) + ' tombstones=absent');
    console.log('real=' + JSON.stringify(after.tabs.map(t => t.id)));
    console.log('original-mock=' + JSON.stringify(mock.tabs.map(t => t.id)));
    expect(after.tabs.map(t => t.id)).toEqual(['existing-a', 'server-new-b']);
    expect(mock.tabs.map(t => t.id)).toEqual(['existing-a']);
  } finally {
    child.stdin.write('quit\n');
    const result = await Promise.race([exit, new Promise(r => setTimeout(() => r(null), 20000))]);
    if (!result) throw new Error(`server did not stop; preserved ${dir} port ${port}`);
    console.log(`[iso] exit=${result.code} signal=${result.signal} port=${port} home=${dir}`);
  }
});
