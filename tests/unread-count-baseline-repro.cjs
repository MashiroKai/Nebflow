'use strict';
// Baseline regression: execute the existing production predicates and ask latch
// in isolation, without a gateway or a browser. This is not an integration test.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const web = path.join(__dirname, '../src/main/resources/web/js');
const mainSource = fs.readFileSync(path.join(web, 'utils.js'), 'utf8');
const askSource = fs.readFileSync(path.join(web, 'askDock.js'), 'utf8');
function extract(source, start, end) {
  const a = source.indexOf(start), b = source.indexOf(end, a);
  assert.ok(a >= 0 && b > a, `missing source: ${start}`);
  return source.slice(a, b).replace(/^export /, '');
}
const main = vm.runInNewContext('(' + extract(mainSource, 'export function countsAsRealMessage(', '\n}\n') + '\n})');
function row(...classes) {
  return { classList: { contains: c => classes.includes(c) }, querySelector: () => null };
}
function askCount(arrived) {
  const sharedPath = path.join(web, 'unreadCount.js');
  const shared = fs.existsSync(sharedPath) ? fs.readFileSync(sharedPath, 'utf8').replace('export function countUnread', 'function countUnread') : '';
  const sandbox = { el: {}, colUnseen: 0, colFollows: () => false, askPillSync: () => {}, requestAnimationFrame: () => {}, messages: [{role: 'sys'}] };
  const argument = shared ? 'messages' : String(arrived);
  vm.runInNewContext(shared + '\n' + extract(askSource, 'function settleCol(', '\n}\n') + '\n}\nsettleCol(' + argument + ');', sandbox);
  return sandbox.colUnseen;
}
test('local user row must not count', () => {
  assert.equal(Number(main(row('row', 'user'))), 0, 'main counts own user row');
});
test('same system-message reconciliation must produce equal counts', () => {
  const mainCount = Number(main(row('row', 'system')));
  // Existing askState passes the entire positive messages.length delta to settleCol,
  // including sys entries, even though render filters them out.
  assert.equal(askCount(1), mainCount, 'ask counts sys length delta; main excludes system row');
});
