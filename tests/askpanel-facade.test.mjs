// askpanel-facade.test.mjs — Ask 面板门面（P4 第一批）node 规格。
// 直跑：node --test tests/askpanel-facade.test.mjs（不占 sbt，无浏览器）。
// 覆盖：① 静态闸 scripts/check-askpanel-facade.mjs 在真树上绿；② 检测力自证：
// 向副本树注入一处直接 createAskDialog( 构造，闸必须红；③ 门面源码形态：
// 七键返回面、destroy/close 幂等守卫存在；④ 两个消费者不再直连 createAskDialog。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, appendFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = new URL('../', import.meta.url);
const repoPath = fileURLToPath(repo);
const gate = join(repoPath, 'scripts', 'check-askpanel-facade.mjs');
const jsDir = new URL('src/main/resources/web/js/', repo);

test('静态闸在真树上绿', () => {
  const r = spawnSync(process.execPath, [gate], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

test('检测力：注入一处直接构造 createAskDialog ⇒ 闸红', () => {
  const dir = mkdtempSync(join(tmpdir(), 'askpanel-neg-'));
  try {
    const web = join(dir, 'web');
    cpSync(join(repoPath, 'src/main/resources/web'), web, { recursive: true });
    appendFileSync(join(web, 'js', 'askDock.js'), '\nconst __neg = createAskDialog({});\n');
    const r = spawnSync(process.execPath, [gate], {
      encoding: 'utf8', env: { ...process.env, ASKPANEL_WEB_ROOT: web },
    });
    assert.equal(r.status, 1, r.stdout);
    assert.match(r.stdout, /FAIL G2 .*askDock\.js/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('门面形态：七键返回面 + open/close/destroy 幂等守卫', () => {
  const src = readFileSync(new URL('askPanel.js', jsDir), 'utf8');
  assert.match(src, /export function createAskPanel\(/);
  for (const k of ['root', 'dialog', 'open', 'close', 'isOpen', 'focusInput', 'destroy']) {
    assert.match(src, new RegExp(`^\\s*${k}\\b`, 'm'), `缺键 ${k}`);
  }
  assert.match(src, /if \(destroyed \|\| dialog\.isOpen\(\)\) return;/);
  assert.match(src, /if \(destroyed \|\| !dialog\.isOpen\(\)\) return;/);
  assert.match(src, /if \(destroyed\) return;\n\s*destroyed = true;/);
});

test('消费者只经门面构造与开收', () => {
  for (const name of ['askDock.js', 'selectionAsk.js']) {
    const src = readFileSync(new URL(name, jsDir), 'utf8');
    assert.match(src, /from '\.\/askPanel\.js'/, `${name} 未引门面`);
    assert.doesNotMatch(src, /\bcreateAskDialog\s*\(/, `${name} 仍直连 createAskDialog`);
    assert.doesNotMatch(src, /\.(openDom|closeDom)\(/, `${name} 直连 openDom/closeDom`);
  }
});
