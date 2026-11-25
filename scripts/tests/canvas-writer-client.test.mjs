import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { stripBrowserTakeoverGlue } from '../lib/browser-takeover-glue.mjs';

const beginGlue = '// BEGIN BROWSER TAKEOVER VIEWER GLUE';
const endGlue = '// END BROWSER TAKEOVER VIEWER GLUE';
test('viewer glue rejects unpaired markers', () => {
  assert.throws(() => stripBrowserTakeoverGlue(`${beginGlue}\ntakeover`), /unpaired/);
});
test('viewer glue rejects blocks longer than 40 lines', () => {
  assert.throws(() => stripBrowserTakeoverGlue(`${beginGlue}\ntakeover\n${'\n'.repeat(40)}${endGlue}`), /40 lines/);
});
test('viewer glue rejects dead markers', () => {
  assert.throws(() => stripBrowserTakeoverGlue(`${beginGlue}\nordinary\n${endGlue}`), /no takeover/);
});

function client(transport) {
  const source = readFileSync(new URL('../../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const start = source.indexOf('function createCanvasWriterClient(');
  const end = source.indexOf('// END CANVAS WRITER CLIENT', start);
  const context = { transport };
  if (start >= 0 && end > start) vm.runInNewContext(source.slice(start, end) + '\nthis.make = createCanvasWriterClient;', context);
  return context.make ? context.make(transport) : { role: () => 'writer', claim: async () => {}, put: async () => true, lost: () => {} };
}

test('new client starts reader; a write auto-claims when the writer slot is free', async () => {
  let claimed = 0;
  const a = client({ claim: async () => { claimed++; return { role: 'writer', epoch: 1 }; }, put: async () => true });
  assert.equal(a.role(), 'reader');
  assert.equal(await a.put({}), true);
  assert.equal(claimed, 1);
  assert.equal(a.role(), 'writer');
});
test('explicit claim enables writing', async () => {
  const a = client({ claim: async () => ({role: 'writer', epoch: 1}), put: async () => true });
  await a.claim();
  assert.equal(a.role(), 'writer');
  assert.equal(await a.put({}), true);
});
test('preempted client returns to reader and rejects writing', async () => {
  const a = client({ claim: async () => ({role: 'writer', epoch: 1}), put: async () => false });
  await a.claim();
  assert.equal(await a.put({}), false);
  assert.equal(a.role(), 'reader');
  assert.equal(await a.put({}), false);
});

test('failed save transitions to unsynced reader, drafts the buffer, without canvas takeover handler', async () => {
  const source = readFileSync(new URL('../../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const begin = source.indexOf('function writerChanged(');
  const end = source.indexOf('const canvasWriter = createCanvasWriterClient(', begin);
  assert.ok(begin >= 0 && end > begin);
  const notices = [];
  const drafts = [];
  const context = { localUnsynced: false, writerStatusEl: null, writerClaimEl: null,
    captureSeatDraft: () => drafts.push(1), window: { __showToast: m => notices.push(m) },
    serverPersistTimer: null, pendingPersistPayload: null, persistDirty: false, clearTimeout };
  vm.runInNewContext(source.slice(begin, end), context);
  const a = client({ claim: async () => ({role: 'writer', epoch: 1}), put: async () => false,
    changed: context.writerChanged, failed: () => { context.localUnsynced = true; context.writerChanged('reader'); } });
  await a.claim();
  assert.equal(await a.put({}), false);
  assert.equal(a.role(), 'reader');
  assert.equal(context.localUnsynced, true);
  // 保存失败静默保留（作者令 2026-11-22）：失败路径不产生任何通知。
  assert.deepEqual(notices, [], 'a refused save must stay silent (no toast)');
  // 静默接管批（2026-11-19）：失权端**静默**落草稿 —— 丢席位的那一刻恰落一次。
  assert.deepEqual(drafts, [1], 'losing the writer seat must stash the buffer exactly once');
  assert.doesNotMatch(source.slice(begin, end), /showCanvasTakeover|takeoverModal|localStorage\.removeItem/);
});

test('reader can close shared tabs consecutively in order', () => {
  const source = readFileSync(new URL('../../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const begin = source.indexOf('export function closeTab(');
  const end = source.indexOf('/** Promote a preview', begin);
  assert.ok(begin >= 0 && end > begin);
  const closed = [];
  const makeEntry = id => ({ id, tabEl: { style: {}, addEventListener() {} }, paneEl: { style: {} } });
  const tabs = new Map([['a', makeEntry('a')], ['b', makeEntry('b')], ['c', makeEntry('c')]]);
  const context = { tabs, activeTabId: 'a', window: {}, isTabDirty: () => false,
    independentWins: new Map(), cardWinWatch: new Map(), setTimeout: fn => fn(),
    teardownTab: id => { closed.push(id); tabs.delete(id); },
    setActiveTab: id => { context.activeTabId = id; }, closeCanvas() {}, syncCanvasPanelButtons() {},
    allowCanvasMutation: () => false, readerLocalTabs: new Set() };
  vm.runInNewContext(source.slice(begin, end).replace('export function closeTab', 'function closeTab'), context);
  context.closeTab('a');
  context.closeTab('b');
  assert.deepEqual(closed, ['a', 'b']);
  assert.deepEqual([...tabs.keys()], ['c']);
  assert.equal(context.activeTabId, 'c');
});

test('reader guards are silent (no notice surface on the canvas write path)', () => {
  const source = readFileSync(process.env.CANVAS_TEST_SOURCE || new URL('../../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const notices = [];
  // 2026-11-22 作者令：保存失败弹窗已删，写路径不再有通知面；reader 守卫只剩静默拒绝。
  const context = { window: { __showToast: message => notices.push(message) }, writerStatusEl: null,
    localUnsynced: false, applyingLiveTabsFrame: false, canvasWriter: { role: () => 'reader' },
    key: s => s };
  const guardStart = source.indexOf('function allowCanvasMutation()');
  const guardEnd = source.indexOf('// F1:', guardStart);
  assert.ok(guardStart >= 0 && guardEnd > guardStart);
  vm.runInNewContext(source.slice(guardStart, guardEnd), context);
  assert.equal(context.allowCanvasMutation(), false);
  assert.deepEqual(notices, []);
});

// ── 规则②三条断言（2026-11-18 作者令：多开只做检测，第二窗口只读 + 服务端拒写
//    + 真实保存失败文案；不新增提示，不留接管/让出措辞）────────────────────────

test('① single instance never reads as multi-open and never reports a failure', async () => {
  const events = [];
  const notices = [];
  const a = client({
    changed: (role, lost) => events.push([role, !!lost]),
    failed: () => notices.push('failed'),
    claim: async () => ({ role: 'writer', epoch: 7 }),
    put: async () => true,
  });
  assert.equal(a.role(), 'reader');
  assert.equal(await a.put({ v: 2, tabs: [] }), true);
  assert.equal(await a.put({ v: 2, tabs: [] }), true);
  assert.equal(a.role(), 'writer');
  assert.deepEqual(notices, [], 'a normal single-instance write must never announce a save failure');
  assert.deepEqual(events.filter(([, lost]) => lost), [], 'no ownership loss on a normal write');
});

test('② a second window silently takes over (preemption) and writes; no failure notice', async () => {
  let putCalls = 0;
  const notices = [];
  // 静默抢占（静默接管批 2026-11-19 作者令）：服务端对第二个窗口的申请也授予（新 epoch）。
  const a = client({
    failed: () => notices.push('failed'),
    claim: async () => ({ role: 'writer', epoch: 9 }),
    put: async () => { putCalls++; return true; },
  });
  assert.equal(await a.put({ v: 2, tabs: [] }), true);
  assert.equal(putCalls, 1, 'preemptive seat: a second window reaches the shared PUT');
  assert.equal(a.role(), 'writer');
  assert.deepEqual(notices, [], 'a silent takeover is not a save failure');
  // 只读查询腿照常回报 reader（未持席位时窗口仍能读存档）。
  const b = client({ query: async () => ({ role: 'reader', epoch: null }) });
  await b.refresh();
  assert.equal(b.role(), 'reader');
});

test('②b a never-granted claim never reaches the shared PUT and reports the real failure once', async () => {
  let putCalls = 0;
  const notices = [];
  const a = client({
    failed: () => notices.push('failed'),
    // 抢占式下 claim 恒授予；唯一「未授予」= 网络失败 / 显式 CAS（expectedEpoch）不匹配。
    claim: async () => null,
    put: async () => { putCalls++; return true; },
  });
  assert.equal(await a.put({ v: 2, tabs: [] }), false);
  assert.equal(putCalls, 0, 'a refused seat must never reach the shared PUT');
  assert.equal(a.role(), 'reader');
  assert.deepEqual(notices, ['failed']);
});

test('③ a refused write is kept silently locally (no toast, no takeover/yield wording)', () => {
  const source = readFileSync(new URL('../../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const wire = /failed:\s*\(\)\s*=>\s*\{([^}]*)\}/.exec(source);
  assert.ok(wire, 'canvas.js must wire a `failed` handler for refused writes');
  assert.match(wire[1], /localUnsynced\s*=\s*true/, 'the local fallback must stay');
  assert.doesNotMatch(wire[1], /__showToast|showToast|writerNotice|保存失败/, 'the save-failure popup is retired');
  assert.doesNotMatch(wire[1], /接管|让出|取回|控制权/);
  assert.doesNotMatch(stripBrowserTakeoverGlue(source).outside, /takeover/i, 'the canvas producer must carry no console-takeover surface');
});
