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
