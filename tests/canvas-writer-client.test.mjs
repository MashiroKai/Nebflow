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
