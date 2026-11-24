import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const source = p => readFileSync(new URL('../../src/main/resources/web/' + p, import.meta.url), 'utf8');

test('FCI-SCN-001 stale term responses are invalidated', async () => {
  const code = source('js/termTip.js');
  const fragment = code.slice(code.indexOf('function requestTermTip('), code.indexOf('/** Mark existing term'));
  const pending = []; const painted = [];
  const el = { classList: { add() {} }, querySelector: () => ({ focus() {} }) };
  const ctx = { ensureTermTipEl: () => el, AbortController, t: key => key, setTimeout: fn => { pending.push(fn); return pending.length; }, clearTimeout() {}, termTipLink: null, termTipHost: null, termTipTimer: 0, termTipGeneration: 0, termInfoFor: () => new Promise(resolve => pending.push(resolve)), paintTermTip: (_a, info) => { if (info.definition !== 'termPreview.loading') painted.push(info.definition); }, hideTermTip() { ctx.termTipGeneration++; ctx.termTipLink = null; } };
  vm.runInNewContext(fragment.replaceAll('export function', 'function') + '\nthis.tap = termTapExpand;', ctx);
  const a = {dataset: {wikiTermKind: 'term'}};
  ctx.tap(a); const first = pending.shift(); const p1 = first(); const resolve1 = pending.shift();
  ctx.tap(a); ctx.tap(a); const second = pending.shift(); const p2 = second(); const resolve2 = pending.shift();
  resolve2({ definition: 'new' }); await p2; resolve1({ definition: 'old' }); await p1;
  assert.deepEqual(painted, ['new']);
  assert.doesNotMatch(code, /addEventListener\('mouseenter'/);
});

test('FCI-SCN-002 note seed has own button offset', () => {
  const css = source('css/ask.css');
  // 重锚（2026-11-19 普查收编批）：闭环意图仍成立，机制换成了 token + 闭态种子形变。
  // 旧写法（2026-10-28 批）：`.note-dock.closed .note-body{… transform: translateX(
  //   calc(-1 * (var(--ask-fab-size) + var(--ds-space-2)))) …}` —— 位移直接写死在该块里。
  // 现机制（位置感知 P2 续批④，ask.css:310-328）：同一偏移量下沉为 .note-dock 上的
  //   fallback token `--note-seed-x0`（= calc(-1 * (var(--ask-fab-size) + var(--ds-space-2)))，
  //   ask.css:318），闭态种子改读 `translate(var(--note-seed-dx, var(--note-seed-x0)),
  //   var(--note-seed-dy, var(--note-seed-y0)))`（ask.css:326-328；dx/dy 由 askDock.js
  //   syncNoteSeed 实测 note 钮圆心写入，两轴缺省仍落 note 钮）。三枚断言同强度：
  //   ① 偏移公式仍在（只是搬到 token）；② note 闭态种子确实消费该 token（且保留 JS 实测覆盖位）；
  //   ③ 种子仍是 fab 同径正圆（「钮长成面板」起手帧不许比钮大一圈，ask.css:297-302）。
  assert.match(
    css,
    /\.note-dock\s*\{[^}]*--note-seed-x0:\s*calc\(-1 \* \(var\(--ask-fab-size\) \+ var\(--ds-space-2\)\)\)/s,
  );
  assert.match(
    css,
    /\.note-dock\.closed \.note-body\s*\{[^}]*transform:\s*translate\(\s*var\(--note-seed-dx,\s*var\(--note-seed-x0\)\),\s*var\(--note-seed-dy,\s*var\(--note-seed-y0\)\)\s*\)/s,
  );
  assert.match(
    css,
    /\.note-dock\.closed \.note-body\s*\{[^}]*width:\s*var\(--ask-seed-size\)[^}]*border-radius:\s*50%/s,
  );
});

test('FCI-SCN-003 matching diff close is isolated', async () => {
  const code = source('js/diffPanel.js');
  const start = code.indexOf("document.addEventListener('canvas-tab-closed'");
  const end = code.indexOf("  import('./activityBar.js')", start);
  let handler; let release; let collapsed = 0;
  const ctx = { currentBatchKey: 'A', currentPayload: {}, batchGeneration: 1, PANEL_ELEM_ID: 'panel-filechanges', syncChipActive() {}, clearBatch() {}, document: { addEventListener: (_n, fn) => { handler = fn; }, getElementById: () => ({classList: {contains: () => true}}) }, loadBar: () => new Promise(r => { release = r; }) };
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
