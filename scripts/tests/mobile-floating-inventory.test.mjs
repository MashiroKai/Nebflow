import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { checkInventory } from '../check-mobile-floating-inventory.mjs';
const source = file => readFileSync(new URL(`../../src/main/resources/web/css/${file}`, import.meta.url), 'utf8');
test('declared mobile float families have source-level owners', () => assert.deepEqual(checkInventory(source), []));
test('todo scroll root cannot shrink below its content and clearance', () => {
  const css = source('mobile-todo-apple.css').replace(/\/\*[\s\S]*?\*\//g, ' ');
  const rule = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].find(m => /\.mobile-page\[data-page="tasks"\] \.ta-root/.test(m[1]));
  assert.ok(rule && /flex\s*:\s*1\s+0\s+auto\s*;/.test(rule[2]));
});
for (const [name, file, pattern] of [
  ['todo top capsule', 'mobile-todo-apple.css', /padding-top: calc\(var\(--mchrome-clear\)/g],
  ['todo bottom FAB', 'mobile-todo-apple.css', /padding-bottom: calc\(var\(--mobile-dock-clearance\) \+ var\(--mpage-fab-clearance/g],
  ['todo editor tool', 'mobile-todo-apple.css', /--ta-kb/g],
  ['message search', 'mobileSearchFlat.css', /padding-top: calc\(var\(--mhsc-band\)/g],
  ['session identity', 'mobileLayer.css', /padding-top: calc\(104px/g],
  ['modal drawer', 'mobileLayer.css', /overflow-y: auto/g],
]) test(`rejects missing ${name} responsibility`, () => {
  const errors = checkInventory(f => f === file ? source(f).replace(pattern, 'removed') : source(f));
  assert.ok(errors.some(e => e.startsWith(`${name}:`)), errors.join('\n'));
});
