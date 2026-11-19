import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

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

test('failed save transitions to unsynced reader without canvas takeover handler', async () => {
  const source = readFileSync(new URL('../../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const begin = source.indexOf('function writerChanged(');
  const end = source.indexOf('const writerRejectionNotices', begin);
  assert.ok(begin >= 0 && end > begin);
  const notices = [];
  const context = { localUnsynced: false, writerStatusEl: null, writerClaimEl: null,
    serverPersistTimer: null, pendingPersistPayload: null, persistDirty: false, clearTimeout };
  vm.runInNewContext(source.slice(begin, end), context);
  const a = client({ claim: async () => ({role: 'writer', epoch: 1}), put: async () => false,
    changed: context.writerChanged, failed: () => notices.push('Canvas 保存失败，本地变更已保留。') });
  await a.claim();
  assert.equal(await a.put({}), false);
  assert.equal(a.role(), 'reader');
  assert.equal(context.localUnsynced, true);
  assert.deepEqual(notices, ['Canvas 保存失败，本地变更已保留。']);
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

test('reader guards are silent and server rejections notify once per reason', () => {
  const source = readFileSync(process.env.CANVAS_TEST_SOURCE || new URL('../../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const notices = [];
  const context = { window: { __showToast: message => notices.push(message) }, writerStatusEl: null,
    localUnsynced: false, applyingLiveTabsFrame: false, canvasWriter: { role: () => 'reader' } };
  const noticeStart = source.indexOf('const writerRejectionNotices = new Set();');
  const start = noticeStart < 0 ? source.indexOf('function writerNotice(') : noticeStart;
  const end = source.indexOf('const canvasWriter =', start);
  const guardStart = source.indexOf('function allowCanvasMutation()');
  const guardEnd = source.indexOf('// F1:', guardStart);
  assert.ok(start >= 0 && end > start && guardStart >= 0 && guardEnd > guardStart);
  vm.runInNewContext(source.slice(start, end) + source.slice(guardStart, guardEnd), context);
  assert.equal(context.allowCanvasMutation(), false);
  assert.deepEqual(notices, []);
  context.writerNotice('rejected', 'save:409');
  context.writerNotice('rejected', 'save:409');
  context.writerNotice('other rejection', 'save:403');
  assert.deepEqual(notices, ['rejected', 'other rejection']);
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

test('② true multi-open: the second window stays a readable reader and never writes', async () => {
  let putCalls = 0;
  const notices = [];
  const a = client({
    failed: () => notices.push('failed'),
    // 服务端非抢占席位：已有写者 ⇒ 拒授（HTTP 409 writer_already_held）。
    claim: async () => null,
    put: async () => { putCalls++; return true; },
  });
  assert.equal(await a.put({ v: 2, tabs: [] }), false);
  assert.equal(putCalls, 0, 'a refused slot must never reach the shared PUT');
  assert.equal(a.role(), 'reader');
  assert.deepEqual(notices, ['failed']);
  // 只读可看：查询腿照常回报 reader，窗口仍能读存档。
  const b = client({ query: async () => ({ role: 'reader', epoch: null }) });
  await b.refresh();
  assert.equal(b.role(), 'reader');
});

test('③ a refused write carries the real save-failure copy, never takeover/yield wording', () => {
  const source = readFileSync(new URL('../../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const wire = /failed:\s*\(\)\s*=>\s*\{([^}]*)\}/.exec(source);
  assert.ok(wire, 'canvas.js must wire a `failed` handler for refused writes');
  assert.match(wire[1], /保存失败/);
  assert.doesNotMatch(wire[1], /接管|让出|取回|控制权/);
  assert.doesNotMatch(source, /takeover/i, 'the canvas producer must carry no console-takeover surface');
});
