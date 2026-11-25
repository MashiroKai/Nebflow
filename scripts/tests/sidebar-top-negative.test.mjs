import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, cpSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../..', import.meta.url));
const script = join(root, 'scripts/check-sidebar-top.mjs');
const fixtures = [
  ['新增文件树硬编码', 'css/split.css', '.explorer-header {', '.explorer-header { color: #123456;'],
  ['修改文件树基线值', 'css/split.css', 'var(--ds-space-block-y) 14px var(--ds-space-1)', 'var(--ds-space-block-y) 15px var(--ds-space-1)'],
  ['收藏硬编码', 'css/canvasFavorites.css', '#panel-favorites .cfp-sidebar .cfp-bar {', '#panel-favorites .cfp-sidebar .cfp-bar { border-radius: 7px;'],
  ['消息硬编码', 'css/searchField.css', '#panel-messages .fm-msg-search.has-query {', '#panel-messages .fm-msg-search.has-query { padding: 9px;'],
  ['绕开共享类', 'js/canvasFavoritesPage.js', "classList.add('panel-header')", "classList.add('private-header')"],
];
for (const [name, file, from, to] of fixtures) test(name, () => {
  const temp = mkdtempSync(join(tmpdir(), 'sidebar-top-'));
  try {
    const web = join(temp,'src/main/resources/web'); cpSync(join(root,'src/main/resources/web'),web,{recursive:true});
    const target = join(web,file), source = readFileSync(target,'utf8'); assert(source.includes(from));
    writeFileSync(target,source.replace(from,to));
    const result = spawnSync(process.execPath,[script],{env:{...process.env,SIDEBAR_TOP_ROOT:temp},encoding:'utf8'});
    assert.equal(result.status,1, result.stdout+result.stderr); assert.match(result.stderr,/sidebar-top: RED/);
    console.log(`${name}: RED ${result.stderr.trim().split('\n').slice(1).join(' | ')}`);
    writeFileSync(target,source);
    const restored = spawnSync(process.execPath,[script],{env:{...process.env,SIDEBAR_TOP_ROOT:temp},encoding:'utf8'});
    assert.equal(restored.status,0,restored.stderr); console.log(`${name}: RESTORED GREEN`);
  } finally { rmSync(temp,{recursive:true,force:true}); }
});
test('删除基线条目', () => {
  const temp = mkdtempSync(join(tmpdir(),'sidebar-top-gate-'));
  try {
    const copy = join(temp,'gate.mjs'); const source = readFileSync(script,'utf8');
    writeFileSync(copy,source.replace(/^  \{ file: 'split\.css'.*\n/m,''));
    const result = spawnSync(process.execPath,[copy],{env:{...process.env,SIDEBAR_TOP_ROOT:root},encoding:'utf8'});
    assert.equal(result.status,1,result.stderr); assert.match(result.stderr,/未登记的侧栏顶部样式/);
    console.log(`删除基线条目: RED ${result.stderr.trim().split('\n').slice(1).join(' | ')}`);
    const restored = spawnSync(process.execPath,[script],{env:{...process.env,SIDEBAR_TOP_ROOT:root},encoding:'utf8'});
    assert.equal(restored.status,0,restored.stderr); console.log('删除基线条目: RESTORED GREEN');
  } finally { rmSync(temp,{recursive:true,force:true}); }
});
