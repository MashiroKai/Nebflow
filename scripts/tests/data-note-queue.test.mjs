import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

function rig() {
  const src = readFileSync(new URL('../../src/main/resources/web/js/askDock.js', import.meta.url), 'utf8');
  const section = src.slice(src.indexOf("const NOTE_DRAFT_PREFIX ="), src.indexOf('function isNoteOpen()'));
  const timers = new Map(), sent = [], statuses = [], storage = new Map(), requested = [];
  let next = 0;
  const ctx = { noteEntries: new Map(), noteWriteSequence: 0, noteSid: 'A', notePath: '/a', noteTimer: 0,
    noteLocalOk: true, el: {noteText: {value: ''}}, NOTE_PUSH_DELAY_MS: 300,
    localStorage: { getItem: k => storage.get(k) ?? null, setItem: (k,v) => storage.set(k,v) }, tt: (_zh,en) => en, document: { createElement: () => ({ addEventListener() {} }) },
    loadNote: () => '', persistNoteLocal: () => true, setNoteStatus: s => statuses.push(s),
    sendWs: m => { sent.push(m); return true; }, requestNote: sid => requested.push(sid), key: s => s,
    setTimeout: fn => { const id = ++next; timers.set(id, fn); return id; }, clearTimeout: id => timers.delete(id) };
  vm.runInNewContext(section + '\nthis.api={noteEntry,scheduleNotePush,applyServerNote,applyNoteAck,resolveNoteConflict,saveNoteEntry,noteInputChanged,resyncNotesAfterReconnect,flushNote};', ctx);
  return {ctx, sent, statuses, storage, requested, run: () => { const f = [...timers.values()]; timers.clear(); f.forEach(x => x()); }};
}
test('FDC-SCN-001 pending writes belong to their files', () => {
  const r = rig(); const a = r.ctx.api.noteEntry('A'), b = r.ctx.api.noteEntry('B');
  a.version = 1; b.version = 2;
  r.ctx.api.scheduleNotePush('A', 'alpha'); r.ctx.api.scheduleNotePush('B', 'beta'); r.run();
  assert.deepEqual(r.sent.map(x => [x.sessionId,x.text,x.baseVersion]), [['A','alpha',1],['B','beta',2]]);
});
test('FDC-SCN-002 late other-file receipt cannot change current status', () => {
  const r = rig(); const a = r.ctx.api.noteEntry('A'); a.version = 1;
  r.ctx.api.scheduleNotePush('A','alpha'); r.run(); r.ctx.noteSid = 'B';
  r.ctx.api.applyNoteAck({sessionId:'A',writeId:r.sent[0].writeId,ok:true,version:2});
  assert.equal(a.version,2); assert.deepEqual(r.statuses, []);
});
test('FDC-SCN-013 read error retains draft and blocks a put', () => {
  const r = rig(); const a = r.ctx.api.noteEntry('A'); a.text='draft'; a.dirty=true;
  r.ctx.api.applyServerNote({sessionId:'A',error:'read-failed'});
  r.ctx.api.scheduleNotePush('A','draft',0); r.run();
  assert.equal(a.text,'draft'); assert.equal(r.sent.length,0); assert.equal(r.statuses.at(-1),'read-failed');
});

test('initial edit before first read holds unseen server text', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.text='new typing'; a.dirty=true;
  r.ctx.api.applyServerNote({sessionId:'A',text:'existing',version:12}); r.run();
  assert.equal(a.held,true); assert.equal(a.remote.text,'existing'); assert.equal(r.sent.length,0);
});
test('reconnect read preserves known base and sends unsaved edit', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version=12; a.text='draft'; a.dirty=true;
  r.ctx.api.applyServerNote({sessionId:'A',text:'old',version:12}); r.run();
  assert.equal(a.held,false); assert.equal(r.sent[0].baseVersion,12);
});
test('empty offline draft persists with base across reload', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version=5; a.text=''; a.dirty=true;
  r.ctx.api.saveNoteEntry('A',a); r.ctx.noteEntries.clear();
  const restored=r.ctx.api.noteEntry('A'); assert.equal(restored.text,''); assert.equal(restored.version,5); assert.equal(restored.dirty,true);
});
test('conflict receipt retains server text and explicit choice resumes', () => {
  const r = rig(), a=r.ctx.api.noteEntry('A'); a.version=1;
  r.ctx.api.scheduleNotePush('A','mine'); r.run();
  r.ctx.api.applyNoteAck({sessionId:'A',writeId:r.sent[0].writeId,ok:false,error:'conflict',text:'theirs',version:2});
  assert.equal(a.held,true); assert.equal(a.remote.text,'theirs');
  r.ctx.api.resolveNoteConflict('A','local'); r.run();
  assert.equal(a.held,false); assert.equal(r.sent[1].baseVersion,2); assert.equal(r.sent[1].text,'mine');
});

test('FDC-SCN-020 a late conflict receipt for text that already equals the draft adopts the base', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 4;
  r.ctx.api.scheduleNotePush('A', 'mine'); r.run();
  r.ctx.api.applyServerNote({ sessionId: 'A', text: 'theirs', version: 5 });
  assert.deepEqual([a.held, a.remote.text], [true, 'theirs'], 'the earlier conflict retained both versions');
  r.ctx.api.resolveNoteConflict('A', 'remote');
  assert.deepEqual([a.text, a.version, a.held], ['theirs', 5, false]);
  // The in-flight write's conflict receipt arrives late: the stored text is already ours.
  r.ctx.api.applyNoteAck({ sessionId: 'A', writeId: r.sent[0].writeId, ok: false, error: 'conflict', text: 'theirs', version: 5 });
  assert.deepEqual([a.text, a.version, a.held, a.remote], ['theirs', 5, false, null], 'no phantom conflict reopens');
  assert.equal(r.statuses.at(-1), '');
});

test('FDC-SCN-022 a version-required receipt whose stored text equals the draft adopts the base', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.text = 'same'; a.dirty = true; a.version = null;
  a.flight = { writeId: '1', text: 'same' };
  r.ctx.api.applyNoteAck({ sessionId: 'A', writeId: '1', ok: false, error: 'version-required', text: 'same', version: 7 });
  assert.deepEqual([a.text, a.version, a.dirty, a.held], ['same', 7, false, false]);
  r.ctx.api.scheduleNotePush('A', 'next', 0); r.run();
  assert.equal(r.sent.at(-1).baseVersion, 7);
});

test('FDC-SCN-022 unknown base blocks the first put until a matching server read arrives', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A');
  assert.equal(a.version, null, 'fixture: no base yet');
  a.text = 'typed'; a.dirty = true;
  r.ctx.api.scheduleNotePush('A', 'typed', 0); r.run();
  assert.equal(r.sent.length, 0, 'a put without a known base is not sent');
  assert.equal(a.dirty, true);
  r.ctx.api.applyServerNote({ sessionId: 'A', text: 'typed', version: 9 });
  assert.equal(a.version, 9); assert.equal(a.dirty, false);
  r.ctx.api.scheduleNotePush('A', 'typed more', 0); r.run();
  assert.equal(r.sent[0].baseVersion, 9);
});

test('FDC-SCN-023 an explicit clear is sent with its base and stays empty across reload', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 3; a.text = '';
  r.ctx.api.scheduleNotePush('A', '', 0); r.run();
  assert.deepEqual([r.sent[0].text, r.sent[0].baseVersion], ['', 3]);
  r.ctx.api.applyNoteAck({ sessionId: 'A', writeId: r.sent[0].writeId, ok: true, version: 4 });
  assert.equal(a.dirty, false); assert.equal(a.version, 4);
  r.ctx.noteEntries.clear();
  const restored = r.ctx.api.noteEntry('A');
  assert.deepEqual([restored.text, restored.version, restored.dirty], ['', 4, false]);
});

test('FDC-SCN-020 a late receipt for an earlier write cannot clear the current flight', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 1;
  r.ctx.api.scheduleNotePush('A', 'first'); r.run();
  const id = r.sent[0].writeId;
  r.ctx.api.scheduleNotePush('A', 'second'); r.run();
  assert.equal(r.sent.length, 1, 'the second edit waits for the in-flight write');
  r.ctx.api.applyNoteAck({ sessionId: 'A', writeId: 'stale', ok: true, version: 99 });
  assert.equal(a.version, 1, 'a receipt for another write cannot move the base');
  assert.equal(a.flight.writeId, id);
  r.ctx.api.applyNoteAck({ sessionId: 'A', writeId: id, ok: true, version: 2 });
  assert.equal(a.version, 2); assert.equal(a.flight, null); assert.equal(a.dirty, true);
  r.run();
  assert.deepEqual([r.sent[1].text, r.sent[1].baseVersion], ['second', 2]);
});

test('FDC-SCN-021 explicit choice of the server version adopts it without sending', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 1;
  r.ctx.api.scheduleNotePush('A', 'mine'); r.run();
  r.ctx.api.applyNoteAck({ sessionId: 'A', writeId: r.sent[0].writeId, ok: false, error: 'conflict', text: 'theirs', version: 2 });
  assert.equal(a.held, true);
  r.ctx.api.resolveNoteConflict('A', 'remote');
  assert.deepEqual([a.text, a.version, a.dirty, a.held, a.remote], ['theirs', 2, false, false, null]);
  r.run();
  assert.equal(r.sent.length, 1, 'adopting the server copy sends nothing');
});

test('FDC-SCN-021 an unrecognised conflict choice keeps both versions for a later choice', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 1;
  r.ctx.api.scheduleNotePush('A', 'mine'); r.run();
  r.ctx.api.applyNoteAck({ sessionId: 'A', writeId: r.sent[0].writeId, ok: false, error: 'conflict', text: 'theirs', version: 2 });
  r.ctx.api.resolveNoteConflict('A', 'bogus'); r.run();
  assert.deepEqual([a.text, a.held, a.remote.text], ['mine', true, 'theirs']);
  assert.equal(r.sent.length, 1);
  r.ctx.api.resolveNoteConflict('A', 'local'); r.run();
  assert.deepEqual([r.sent[1].text, r.sent[1].baseVersion], ['mine', 2]);
});

test('FDC-SCN-020 a late ack after a reconnect read cannot roll the known base back', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 12; a.text = 'draft'; a.dirty = true; a.flight = null;
  r.ctx.api.applyNoteAck({ sessionId: 'A', writeId: '7', ok: true, version: 99 });
  assert.equal(a.version, 12, 'no in-flight write ⇒ the receipt is ignored');
  r.ctx.api.applyServerNote({ sessionId: 'A', text: 'server', version: 12 });
  r.run();
  assert.equal(a.version, 12);
  assert.deepEqual([r.sent[0].text, r.sent[0].baseVersion], ['draft', 12], 'the unsaved edit is pushed on the known base');
});

test('FDC-SCN-020 a stale server read cannot lower the known base', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 12; a.text = 'draft'; a.dirty = true;
  r.ctx.api.applyServerNote({ sessionId: 'A', text: 'older', version: 10 });
  assert.deepEqual([a.version, a.held, a.text], [12, false, 'draft']);
});

test('FDC-SCN-027 the input handler marks the draft dirty, persists it and pushes it', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 3;
  r.ctx.noteSid = 'A'; r.ctx.el.noteText.value = 'typed';
  r.ctx.api.noteInputChanged();
  assert.deepEqual([a.text, a.dirty], ['typed', true]);
  const saved = JSON.parse(r.storage.get('note:draft:A'));
  assert.deepEqual([saved.text, saved.dirty, saved.version], ['typed', true, 3]);
  r.run(); // the 250 ms flush becomes a scheduled push
  r.run(); // the push timer fires
  assert.deepEqual([r.sent[0].text, r.sent[0].baseVersion], ['typed', 3]);
});

test('FDC-SCN-027 reconnect clears the flight and re-reads a dirty draft', () => {
  const r = rig(), clean = r.ctx.api.noteEntry('B'), dirty = r.ctx.api.noteEntry('A');
  dirty.version = 3; dirty.dirty = true; dirty.flight = { writeId: '1', text: 'old' };
  clean.version = 4; clean.dirty = false;
  r.ctx.api.resyncNotesAfterReconnect();
  assert.equal(dirty.flight, null, 'a receipt from the old socket cannot be accepted');
  assert.deepEqual(r.requested, ['A'], 'only the dirty file is re-read');
});

test('FDC-SCN-027 flush writes the current text to the draft and schedules the push', () => {
  const r = rig(), a = r.ctx.api.noteEntry('A'); a.version = 4;
  r.ctx.noteSid = 'A'; r.ctx.notePath = '/a'; r.ctx.el.noteText.value = 'flushed';
  r.ctx.api.noteInputChanged(); // a dirty draft exists; the debounce is pending
  r.ctx.api.flushNote();       // leaving the card/flushing now must not lose or delay it
  assert.deepEqual([a.text, a.dirty], ['flushed', true]);
  assert.equal(JSON.parse(r.storage.get('note:draft:A')).text, 'flushed');
  r.run();
  assert.deepEqual([r.sent[0].text, r.sent[0].baseVersion], ['flushed', 4]);
});

