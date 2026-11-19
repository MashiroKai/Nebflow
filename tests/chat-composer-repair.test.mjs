// Unit tests for the pure rules behind the sent-input history ledger (FCC-SCN-001, FCC-SCN-002).
// The ledger module has no DOM and no storage, so the production file is imported as is. The browser-level evidence
// (real composer, real IndexedDB, reload) lives in tests/chat-composer-consumers.spec.mjs.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  ATTACHMENT_ENTRY_CAP, REF_INLINE_CAP, alignLedger, attachmentFromMeta, attachmentMeta, entriesOverCap, payloadOf, signatureOf,
  unreferencedKeys,
} from '../src/main/resources/web/js/input/historyLedger.js';

const slot = (text, extra = {}) => ({ s: signatureOf(text), ...extra });

test('FCC-SCN-002 text signatures separate different texts and are stable', () => {
  assert.equal(signatureOf('hello'), signatureOf('hello'));
  assert.notEqual(signatureOf('hello'), signatureOf('hellp'));
  assert.notEqual(signatureOf(''), signatureOf(' '));
  assert.equal(signatureOf(undefined), signatureOf(''));
});

test('FCC-SCN-002 a ledger that matches the text list from the tail keeps its attachments', () => {
  const texts = ['a', 'b', 'c'];
  const saved = [slot('a'), slot('b', { id: 'h1', atts: [{ type: 'image', name: 'x.png', bytes: true }] }), slot('c')];
  const aligned = alignLedger(texts, saved);
  assert.equal(aligned.length, 3);
  assert.deepEqual(aligned[1].atts, [{ type: 'image', name: 'x.png', bytes: true }]);
  assert.equal(aligned[1].id, 'h1');
});

test('FCC-SCN-002 a text appended by an older build breaks the match: older entries come back text-only, never mispaired', () => {
  // The ledger knows a, b(with image), c. An older build then appended "d" without a ledger slot.
  const texts = ['a', 'b', 'c', 'd'];
  const saved = [slot('a'), slot('b', { id: 'h1', atts: [{ type: 'image', bytes: true }] }), slot('c')];
  const aligned = alignLedger(texts, saved);
  assert.equal(aligned.length, 4);
  for (const entry of aligned) assert.equal(entry.atts, undefined, 'no entry may borrow attachments after the match broke');
  assert.deepEqual(aligned.map((entry) => entry.s), texts.map(signatureOf));
});

for (const { name, savedTexts, texts } of [
  { name: 'a legacy duplicate append', savedTexts: ['same'], texts: ['same', 'same'] },
  { name: 'a duplicate appended after an anchor', savedTexts: ['anchor', 'same'], texts: ['anchor', 'same', 'same'] },
  { name: 'a repeated run appended by a legacy writer', savedTexts: ['a', 'b'], texts: ['a', 'b', 'a', 'b'] },
  { name: 'an ambiguous front truncation', savedTexts: ['same', 'same'], texts: ['same'] },
  { name: 'a front truncation and duplicate append at the history cap', savedTexts: ['old', 'same'], texts: ['same', 'same'] },
  { name: 'a repeated saved run after truncation', savedTexts: ['a', 'b', 'a', 'b'], texts: ['a', 'b'] },
]) {
  test(`FCC-SCN-002 ${name} never transfers attachments to an ambiguous match`, () => {
    const saved = savedTexts.map((text, i) => slot(text, { id: `h${i}`, atts: [{ type: 'image', bytes: true }] }));
    assert.deepEqual(alignLedger(texts, saved), texts.map((text) => slot(text)));
  });
}

test('FCC-SCN-002 fully aligned duplicate texts keep their distinct attachments', () => {
  const saved = ['same', 'same'].map((text, i) => slot(text, { id: `h${i}`, atts: [{ type: 'image', name: `${i}.png` }] }));
  assert.deepEqual(alignLedger(['same', 'same'], saved), saved);
});

test('FCC-SCN-002 a unique suffix preserves contextual duplicate texts after prepending or truncation', () => {
  const saved = ['same', 'same', 'end'].map((text, i) => slot(text, { id: `h${i}`, atts: [{ type: 'image', bytes: true }] }));
  assert.deepEqual(alignLedger(['older', 'same', 'same', 'end'], saved), [slot('older'), ...saved]);
  assert.deepEqual(alignLedger(['same', 'end'], saved), saved.slice(1));
});

test('FCC-SCN-002 a unique newest run survives mismatched legacy entries', () => {
  const saved = [slot('old'), slot('same', { id: 'h1', atts: [{ type: 'image', bytes: true }] }), slot('end')];
  assert.deepEqual(alignLedger(['legacy', 'same', 'end'], saved), [slot('legacy'), ...saved.slice(1)]);
});

test('FCC-SCN-002 legacy history without any ledger yields one text-only slot per entry', () => {
  const aligned = alignLedger(['one', 'two'], undefined);
  assert.deepEqual(aligned, [slot('one'), slot('two')]);
  assert.deepEqual(alignLedger([], [slot('x')]), []);
  assert.deepEqual(alignLedger(['one'], 'garbage'), [slot('one')]);
});

test('FCC-SCN-002 trimming the text list from the front keeps the newest entries paired', () => {
  const saved = [slot('a'), slot('b', { id: 'h1', atts: [{ type: 'text', bytes: true }] }), slot('c', { id: 'h2', atts: [{ type: 'image', bytes: true }] })];
  const aligned = alignLedger(['b', 'c'], saved);
  assert.equal(aligned[0].id, 'h1');
  assert.equal(aligned[1].id, 'h2');
});

test('FCC-SCN-001 attachment metadata drops the payload and keeps what is needed to send the attachment again', () => {
  const image = attachmentMeta({ type: 'image', name: 'a.png', mimeType: 'image/jpeg', data: 'QUJD', preview: 'data:image/jpeg;base64,QUJD' });
  assert.deepEqual(image, { type: 'image', name: 'a.png', mimeType: 'image/jpeg', hash: '', size: 0, bytes: true });
  const file = attachmentMeta({ type: 'text', name: 'big.bin', mimeType: 'application/octet-stream', data: '', hash: 'abc', size: 9 });
  assert.deepEqual(file, { type: 'text', name: 'big.bin', mimeType: 'application/octet-stream', hash: 'abc', size: 9, bytes: false });
  const ref = attachmentMeta({ type: 'ref', refType: 'file', id: 'r1', source: { path: '/x' }, anchor: null, meta: {}, display: 'x', content: 'body' });
  assert.equal(ref.content, 'body');
  assert.equal(ref.bytes, undefined);
  const task = attachmentMeta({ type: 'taskRef', taskId: 't1', sessionId: 's1', subject: 'Fix it' });
  assert.deepEqual(task, { type: 'taskRef', taskId: 't1', sessionId: 's1', subject: 'Fix it', name: 'Fix it' });
});

test('FCC-SCN-001 attachments are rebuilt in the shape the composer and the send path use', () => {
  const image = attachmentFromMeta({ type: 'image', name: 'a.png', mimeType: 'image/png', bytes: true }, 'QUJD');
  assert.deepEqual(image, { type: 'image', mimeType: 'image/png', data: 'QUJD', name: 'a.png', preview: 'data:image/png;base64,QUJD' });
  const file = attachmentFromMeta({ type: 'text', name: 'n.txt', mimeType: 'text/plain', hash: 'h', size: 4, bytes: true }, 'dGVzdA==');
  assert.deepEqual(file, { type: 'text', mimeType: 'text/plain', data: 'dGVzdA==', name: 'n.txt', hash: 'h', size: 4 });
  // A file whose bytes were never held (resolved by hash on the server) comes back without a payload, as it was sent.
  const unheld = attachmentFromMeta({ type: 'text', name: 'big.bin', mimeType: 'application/octet-stream', hash: 'abc', size: 9, bytes: false }, '');
  assert.equal(unheld.data, '');
  assert.equal(unheld.hash, 'abc');
  const ref = attachmentFromMeta({ type: 'ref', refType: 'file', id: 'r1', bytes: undefined }, '');
  assert.deepEqual(ref, { type: 'ref', refType: 'file', id: 'r1' });
});

test('FCC-SCN-001 only the newest entries keep attachments once a cap is crossed', () => {
  const withAtts = (n, bytes) => Array.from({ length: n }, (_, i) => slot(`m${i}`, { id: 'h' + i, atts: [{ type: 'image', bytes: true }], b: bytes }));
  // Within both caps nothing is stripped.
  assert.deepEqual(entriesOverCap(withAtts(ATTACHMENT_ENTRY_CAP, 10)), []);
  // One more than the entry cap: the single oldest entry loses its attachments.
  const over = withAtts(ATTACHMENT_ENTRY_CAP + 3, 10);
  assert.deepEqual(entriesOverCap(over), [0, 1, 2]);
  // The byte cap works from the newest backwards as well.
  const heavy = withAtts(4, 30 * 1024 * 1024);
  assert.deepEqual(entriesOverCap(heavy), [0, 1]);
  // Entries without attachments never count toward a cap and are never listed.
  const mixed = [slot('plain'), ...withAtts(2, 5), slot('also plain')];
  assert.deepEqual(entriesOverCap(mixed), []);
});

test('FCC-SCN-001 a reference with a long text keeps the text with the payloads and a short one keeps it in the ledger', () => {
  const short = { type: 'ref', refType: 'file', id: 'r1', content: 'short text', display: 'a' };
  const long = { type: 'ref', refType: 'file', id: 'r2', content: 'x'.repeat(REF_INLINE_CAP + 1), display: 'b' };
  // Short: the text stays in the ledger metadata, nothing goes to the payload store.
  assert.equal(attachmentMeta(short).content, 'short text');
  assert.equal(attachmentMeta(short).bytes, undefined);
  assert.equal(payloadOf(short), '');
  // Long: the ledger (localStorage) carries no text, the payload store carries it, and it comes back whole.
  const meta = attachmentMeta(long);
  assert.equal('content' in meta, false);
  assert.equal(meta.bytes, true);
  assert.equal(payloadOf(long), long.content);
  const rebuilt = attachmentFromMeta(meta, long.content);
  assert.equal(rebuilt.content, long.content);
  assert.equal('bytes' in rebuilt, false);
  // The other attachment kinds keep what they always kept.
  assert.equal(payloadOf({ type: 'taskRef', taskId: 't1' }), '');
  assert.equal(payloadOf({ type: 'image', data: 'AAAA' }), 'AAAA');
  assert.equal(payloadOf({ type: 'text', name: 'big.log', data: '' }), '');
});

test('FCC-SCN-001 the sweep keeps the payloads of an entry that was recorded while the key listing was in flight', async () => {
  const referenced = new Set(['hist:h1:0']);
  let listed = 0;
  // The listing answers after a new entry was recorded: its payload key is in the listing and in the ledger by then.
  const listKeys = async () => {
    listed += 1;
    await Promise.resolve();
    referenced.add('hist:h2:0');
    return ['hist:h1:0', 'hist:h2:0', 'hist:gone:0', 'queue:other'];
  };
  const orphans = await unreferencedKeys(listKeys, () => new Set(referenced), 'hist:');
  assert.equal(listed, 1);
  // Only the payload nothing refers to is swept; the new entry's payload and keys of other owners are left alone.
  assert.deepEqual(orphans, ['hist:gone:0']);
});
