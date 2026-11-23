import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// 性能红线静态闸的正负控。闸脚本的 ROOT 由 import.meta.url 推出、无导出，
// 所以负控办法是：把闸脚本 + 网关源码 + 前端 js 复制到临时仓，在副本上改坏一处，
// 断言闸变红；原样副本断言绿。这样测的就是真脚本，不是重写的判据。

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

function makeMirror() {
  const dir = mkdtempSync(join(tmpdir(), 'perf-redlines-'));
  mkdirSync(join(dir, 'scripts'), { recursive: true });
  cpSync(join(REPO, 'scripts', 'check-perf-redlines.mjs'), join(dir, 'scripts', 'check-perf-redlines.mjs'));
  cpSync(join(REPO, 'src', 'main', 'scala', 'nebflow', 'gateway'),
    join(dir, 'src', 'main', 'scala', 'nebflow', 'gateway'), { recursive: true });
  cpSync(join(REPO, 'src', 'main', 'resources', 'web', 'js'),
    join(dir, 'src', 'main', 'resources', 'web', 'js'), { recursive: true });
  return dir;
}

function runGate(dir) {
  const r = spawnSync(process.execPath, [join(dir, 'scripts', 'check-perf-redlines.mjs')], { encoding: 'utf8' });
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') };
}

function edit(dir, rel, from, to) {
  const p = join(dir, rel);
  const src = readFileSync(p, 'utf8');
  assert.ok(src.includes(from), `fixture anchor missing in ${rel}: ${from}`);
  writeFileSync(p, src.replace(from, to));
}

test('原样副本：闸绿（exit 0）', () => {
  const dir = makeMirror();
  try {
    const r = runGate(dir);
    assert.equal(r.code, 0, r.out);
    assert.match(r.out, /GREEN/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('A 负控：新增不分页全量读 getUiMessages(sid, 0, 0) ⇒ 红', () => {
  const dir = makeMirror();
  try {
    edit(dir, 'src/main/scala/nebflow/gateway/SessionRoutes.scala',
      'sessionStore.getUiMessages(sessionId, offset, limit)',
      'sessionStore.getUiMessages(sessionId, offset, limit); sessionStore.getUiMessages(sessionId, 0, 0)');
    const r = runGate(dir);
    assert.equal(r.code, 1, r.out);
    assert.match(r.out, /不分页的全量读/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('B 负控：去掉 /api 上的 ApiGzipMiddleware ⇒ 红', () => {
  const dir = makeMirror();
  try {
    edit(dir, 'src/main/scala/nebflow/gateway/GatewayMain.scala',
      '"/api" -> ApiGzipMiddleware(', '"/api" -> (');
    const r = runGate(dir);
    assert.equal(r.code, 1, r.out);
    assert.match(r.out, /压缩管线破损/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('C 负控：稳态轮询周期压到 250ms ⇒ 红', () => {
  const dir = makeMirror();
  try {
    const target = join(dir, 'src/main/resources/web/js/daemons.js');
    const src = readFileSync(target, 'utf8');
    const m = src.match(/setInterval\s*\([^,]+,\s*(\d{3,6})\s*\)/);
    assert.ok(m, 'fixture: daemons.js 应有数值字面量 setInterval');
    writeFileSync(target, src.replace(m[0], m[0].replace(m[1], '250')));
    const r = runGate(dir);
    assert.equal(r.code, 1, r.out);
    assert.match(r.out, /超频轮询/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
