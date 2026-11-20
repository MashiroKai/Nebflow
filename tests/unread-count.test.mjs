import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const base = new URL('../src/main/resources/web/js/', import.meta.url);
const source = await readFile(new URL('unreadCount.js', base), 'utf8');
const { countUnread } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const mainSrc = await readFile(new URL('utils.js', base), 'utf8');
// 内核抽取批（2026-11-18）后 settleCol / countUnread 单点在 askDialog.js（canvas 档与
// 选区档共用内核）；askDock.js 只剩画布侧脚手架，不再自持落列计数。
const askSrc = await readFile(new URL('askDialog.js', base), 'utf8');
function extract(src, start, end) { const a = src.indexOf(start), b = src.indexOf(end, a); assert.ok(a >= 0 && b > a); return src.slice(a, b).replace(/^export /, '') + '\n}'; }
const isReal = vm.runInNewContext('(' + extract(mainSrc, 'export function countsAsRealMessage(', '\n}\n') + ')');
function row(role) { const classes = ['row', role === 'sys' ? 'system' : role]; return { classList: { contains: x => classes.includes(x) }, querySelector: () => null }; }
function askPath(messages, initial = 0, atBottom = false) {
  const ctx = {
    colUnseen: initial, colSnapped: false, countUnread, messages,
    dlg: { colFollows: () => atBottom, askPillSync() {} },
    requestAnimationFrame() {},
  };
  // settleCol 在 askDialog.js 里是**对象方法的简写形态**（`settleCol(arrived = []) {`），
  // 不是顶层函数 ⇒ 不能直接当脚本求值；这里按方法切片装进一个对象再调用，
  // 语义与内核实例上的同名方法逐字一致（同一份源码切片，无二次书写）。
  const a = askSrc.indexOf('settleCol(arrived = []) {');
  assert.ok(a >= 0, 'askDialog.js 应仍定义 settleCol 方法');
  const b = askSrc.indexOf('\n    },', a);
  assert.ok(b > a, 'settleCol 方法体应以 `    },` 收尾');
  vm.runInNewContext(`const __m = {\n${askSrc.slice(a, b)}\n    }\n};\n__m.settleCol(messages);`, ctx);
  return ctx.colUnseen;
}
function mainPath(messages, initial = 0, atBottom = false) {
  return countUnread(initial, messages.filter(m => isReal(row(m.role))), atBottom);
}
test('shared policy excludes self and process roles and clears at bottom', () => {
  assert.equal(countUnread(2, [{role:'user'}, {role:'ai',self:true}, {role:'sys'}, {role:'ai'}]), 3);
  assert.equal(countUnread(3, [{role:'ai'}], true), 0);
});
test('main and ask adapters use the same shared import and agree on a mixed sequence', () => {
  for (const src of [mainSrc, askSrc]) assert.match(src, /import \{ countUnread \} from '\.\/unreadCount\.js'/);
  assert.match(mainSrc, /rec\.count = countUnread\(rec\.count, arrived, rec\.atBottom\)/);
  const messages = [{role:'user'}, {role:'sys'}, {role:'ai'}, {role:'ai'}, {role:'user'}];
  assert.equal(mainPath(messages), 2);
  assert.equal(askPath(messages), 2);
  assert.equal(mainPath(messages, 3, true), askPath(messages, 3, true));
});
test('rapid sends are excluded; rapid answers accumulate', () => {
  let count = 0;
  for (const role of ['user','user','ai','ai']) count = askPath([{role}], count);
  assert.equal(count, 2);
});
test('removed-before-observer rows are ignored; terminal is guarded by busy', () => {
  assert.match(mainSrc, /!n\.isConnected && rec\.unreadRows\.delete\(n\)/);
  assert.match(askSrc, /settleCol\(wasBusy && last && last\.role === 'ai' \? \[last\] : \[\]\)/);
  assert.equal(askPath([], 1), 1);
});
