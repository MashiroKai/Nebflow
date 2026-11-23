// model-rules-panel.test.mjs — 模型规则面板静态闸的 node 规格。
// 直跑：node --test tests/model-rules-panel.test.mjs（无浏览器、不占 sbt）。
// ① 闸在真树上绿；② 检测力：副本树删去一个三态键 ⇒ 闸红。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('../', import.meta.url));
const gate = join(repo, 'scripts', 'check-model-rules-panel.mjs');

test('静态闸在真树上绿', () => {
  const r = spawnSync(process.execPath, [gate], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

test('检测力：副本删去 zh-CN 的 modelRules.mode.slots ⇒ 闸红', () => {
  const dir = mkdtempSync(join(tmpdir(), 'mr-panel-neg-'));
  try {
    cpSync(join(repo, 'src'), join(dir, 'src'), { recursive: true });
    const zhPath = join(dir, 'src/main/resources/web/js/locales/zh-CN.js');
    const src = readFileSync(zhPath, 'utf8');
    writeFileSync(zhPath, src.replace(/^.*'modelRules\.mode\.slots'.*\n/m, ''));
    const r = spawnSync(process.execPath, [gate, dir], { encoding: 'utf8' });
    assert.notEqual(r.status, 0, r.stdout);
    assert.match(r.stdout, /FAIL zh-CN 三态键/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
