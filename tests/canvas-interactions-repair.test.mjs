import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const source = p => readFileSync(new URL('../src/main/resources/web/' + p, import.meta.url), 'utf8');

test('FCI-SCN-001 stale term responses are invalidated', async () => {
  const code = source('js/termTip.js');
  const fragment = code.slice(code.indexOf('function requestTermTip('), code.indexOf('/** Mark existing term'));
  const pending = []; const painted = [];
  const ctx = { ensureTermTipEl() {}, setTimeout: fn => { pending.push(fn); return pending.length; }, clearTimeout() {}, termTipLink: null, termTipHost: null, termTipTimer: 0, termTipGeneration: 0, termInfoFor: () => new Promise(resolve => pending.push(resolve)), paintTermTip: (_a, info) => painted.push(info), hideTermTip() { ctx.termTipGeneration++; ctx.termTipLink = null; } };
  vm.runInNewContext(fragment.replace('export function', 'function') + '\nthis.tap = termTapExpand;', ctx);
  const a = {dataset: {wikiTermKind: 'term'}};
  ctx.tap(a); const first = pending.shift(); const p1 = first(); const resolve1 = pending.shift();
  ctx.tap(a); ctx.tap(a); const second = pending.shift(); const p2 = second(); const resolve2 = pending.shift();
  resolve2('new'); await p2; resolve1('old'); await p1;
  assert.deepEqual(painted, ['new']);
  assert.doesNotMatch(code, /addEventListener\('mouseenter'/);
});

test('FCI-SCN-002 note seed has own button offset', () => {
  assert.match(source('css/ask.css'), /\.note-dock\.closed \.note-body\s*\{[^}]*translateX\(calc\(-1 \* \(var\(--ask-fab-size\) \+ var\(--ds-space-2\)\)\)\)/s);
});

test('FCI-SCN-003 matching diff close is isolated', async () => {
  const code = source('js/diffPanel.js');
  const start = code.indexOf("document.addEventListener('canvas-tab-closed'");
  const end = code.indexOf("  import('./activityBar.js')", start);
  let handler; let release; let collapsed = 0;
  const ctx = { currentBatchKey: 'A', currentPayload: {}, batchGeneration: 1, PANEL_ELEM_ID: 'panel-filechanges', syncChipActive() {}, document: { addEventListener: (_n, fn) => { handler = fn; }, getElementById: () => ({classList: {contains: () => true}}) }, loadBar: () => new Promise(r => { release = r; }) };
  vm.runInNewContext(code.slice(start, end).replace("import('./activityBar.js')", 'loadBar()'), ctx);
  await handler({detail: {id: 'filechanges:B'}});
  assert.equal(ctx.currentBatchKey, 'A');
  const old = handler({detail: {id: 'filechanges:A'}});
  ctx.currentBatchKey = 'A'; ctx.batchGeneration++;
  release({setSideBarCollapsed: () => collapsed++}); await old;
  assert.equal(collapsed, 0);
  const current = handler({detail: {id: 'filechanges:A'}});
  release({setSideBarCollapsed: () => collapsed++}); await current;
  assert.equal(collapsed, 1); assert.equal(ctx.currentBatchKey, null);
});

test('FCI-SCN-004 file selection entry and labels', () => {
  const code = source('js/chatSearch.js');
  assert.match(code, /search-select-files/);
  assert.match(code, /fileSelectionMode && currentTab === 'files'/);
  assert.match(source('js/locales/zh-CN.js'), /'search.openSelectedInCanvas': '打开所选文件/);
});
