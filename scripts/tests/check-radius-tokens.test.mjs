import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const checker = fileURLToPath(new URL('../check-radius-tokens.mjs', import.meta.url));
function scan(css) {
  const root = mkdtempSync(join(tmpdir(), 'todo-radius-'));
  try {
    mkdirSync(join(root, 'css'));
    mkdirSync(join(root, 'js'));
    writeFileSync(join(root, 'css', 'mobile-todo-apple.css'), css);
    const result = spawnSync(process.execPath, [checker], { encoding: 'utf8', env: { ...process.env, STAND_RADIUS_WEB: root } });
    return { status: result.status, output: result.stdout + result.stderr, file: resolve(root, 'css/mobile-todo-apple.css') };
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('注释、多行、继承、令牌组合与令牌 fallback 合法', () => {
  const result = scan(`/* border-radius: 6px;\n border-top-left-radius: 50%; */
@media (max-width: 600px) {
  .a { border-radius:\n var(--ds-radius-xl)\n var(--ds-radius-none); }
  .b { border-top-left-radius: var(--ds-radius-input, var(--ds-radius-pill)); }
  .c { border-bottom-right-radius: inherit !important; }
}`);
  assert.equal(result.status, 0, result.output);
  assert.match(result.output, /todo strict 3 declarations, 0 violations/);
});

test('任何选择器与四角 longhand 均不能绕过严格规则', () => {
  const result = scan(`/* comment */
@media (max-width: 600px) {
 .fm-context-menu { border-radius: 0; }
 .plain { border-top-left-radius: 50%; }
 .plain { border-top-right-radius: 6px; }
 .plain { border-bottom-left-radius: 0 8px; }
 .plain { border-bottom-right-radius: var(--ds-radius-pill, 6px); }
 .plain { border-radius: var(--other); }
}`);
  assert.equal(result.status, 1, result.output);
  assert.match(result.output, /todo strict 6 declarations, 6 violations/);
  for (const line of [3, 4, 5, 6, 7, 8]) assert.ok(result.output.includes(`${result.file}:${line}`), result.output);
});

test('多行 fallback 字面量与混合字面量均报错', () => {
  const result = scan(`.a { border-radius:\n var(--ds-radius-input,\n  0px); }
.b { border-radius: var(--ds-radius-input) 6px; }`);
  assert.equal(result.status, 1, result.output);
  assert.match(result.output, /todo strict 2 declarations, 2 violations/);
  assert.ok(result.output.includes(`${result.file}:1`));
  assert.ok(result.output.includes(`${result.file}:4`));
});
