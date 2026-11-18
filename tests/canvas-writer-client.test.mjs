import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

function client(transport) {
  const source = readFileSync(new URL('../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
  const start = source.indexOf('function createCanvasWriterClient(');
  const end = source.indexOf('// END CANVAS WRITER CLIENT', start);
  const context = { transport };
  if (start >= 0 && end > start) vm.runInNewContext(source.slice(start, end) + '\nthis.make = createCanvasWriterClient;', context);
  return context.make ? context.make(transport) : { role: () => 'writer', claim: async () => {}, put: async () => true, lost: () => {} };
}

test('new client starts reader and cannot write', async () => {
  const a = client({ claim: async () => ({role: 'writer', epoch: 1}), put: async () => true });
  assert.equal(a.role(), 'reader');
  assert.equal(await a.put({}), false);
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
  const source = readFileSync(new URL('../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
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

test('reader guards are silent and server rejections notify once per reason', () => {
  const source = readFileSync(process.env.CANVAS_TEST_SOURCE || new URL('../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
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
