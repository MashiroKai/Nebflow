// scripts/tests/canvas-seat-draft.test.mjs — 失权端草稿的**行为**单测
// （静默接管批 2026-11-19；作者裁定「直接静默吧」+ 动机「现在通知很烦」）。
//
// 与 scripts/check-canvas-seat-draft.mjs 的分工：那道闸量**声明/接线**（源码里有这条腿、
// 落在哪个键、有无通知调用）；这里量**行为**：把 canvas.js 里的真函数抽出来放进 vm 跑，
// 断言「丢席位那一刻真的按当前编辑缓冲落了槽 / 空集不落 / 坏体不炸 / 存储不可用不炸 /
// 全程零 fetch」。改前形态（没有 captureSeatDraft）在这里会直接找不到函数 ⇒ 红。
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createContext, runInContext } from 'node:vm';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const SRC = readFileSync(join(ROOT, 'src/main/resources/web/js/canvas.js'), 'utf8');

/** 抽出名为 name 的顶层函数源码（从 'function name(' 到下一个顶格 '}'）。 */
function extractFn(src, name) {
  const start = src.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `canvas.js 里找不到 function ${name}(`);
  const end = src.indexOf('\n}', start);
  assert.notEqual(end, -1, `function ${name} 没有顶格收尾`);
  return src.slice(start, end + 2);
}

const FN_SRC = [
  `const LS_DRAFT_KEY = key('canvas_seat_draft');`,
  // 数据保护门（2026-11-21）的正文上限常量与脏判定：collectSeatDraftBuffers 依赖它。
  `const SEAT_DRAFT_BUFFER_MAX_CHARS = 400_000;`,
  extractFn(SRC, 'isTabDirty'),
  extractFn(SRC, 'collectSerializableTabs'),
  extractFn(SRC, 'collectSeatDraftBuffers'),
  extractFn(SRC, 'readSeatDraft'),
  extractFn(SRC, 'captureSeatDraft'),
  extractFn(SRC, 'clearSeatDraft'),
].join('\n');

/** 造一个只够这几个函数用的最小宿主：tabs / readerLocalTabs / activeTabId / tombstones
 *  / localStorage / key / renderDraftEntry / Date，以及一枚**会抛的 fetch** 钩子。
 *  dirty 项 = 给该 id 的页签挂一个「有未保存正文」的编辑器句柄（pane._editorHandle）。 */
function makeHarness({ tabs = [], stored = null, setItemThrows = false, dirty = {} } = {}) {
  const slot = new Map();
  if (stored !== null) slot.set('canvas_seat_draft', stored);
  let renderCalls = 0;
  let fetchCalls = 0;
  const localStorage = {
    getItem: (k) => (slot.has(k) ? slot.get(k) : null),
    setItem: (k, v) => { if (setItemThrows) throw new Error('QuotaExceededError'); slot.set(k, v); },
    removeItem: (k) => { slot.delete(k); },
  };
  const entryList = tabs.map((t) => {
    const e = { ...t };
    if (dirty[t.id] !== undefined) {
      e.paneEl = { _dirty: true, _editorHandle: { getDirty: () => true, model: { getValue: () => dirty[t.id] } } };
    }
    return e;
  });
  const sandbox = {
    tabs: new Map(entryList.map((t) => [t.id, t])),
    readerLocalTabs: new Set(),
    activeTabId: null,
    sessionTombstones: new Map(),
    localStorage,
    key: (k) => k,
    renderDraftEntry: () => { renderCalls += 1; },
    fetch: () => { fetchCalls += 1; throw new Error('草稿路径不得发网络请求'); },
    Date,
    JSON, console,
  };
  const ctx = createContext(sandbox);
  runInContext(
    `${FN_SRC}\nglobalThis.__draftApi = { collectSerializableTabs, collectSeatDraftBuffers, readSeatDraft, captureSeatDraft, clearSeatDraft };`,
    ctx);
  const api = ctx.__draftApi;
  return {
    api, slot,
    get renderCalls() { return renderCalls; },
    get fetchCalls() { return fetchCalls; },
    setActive: (id) => { ctx.activeTabId = id; },
    addTombstone: (v) => { ctx.sessionTombstones.set(v.id ?? v, v); },
  };
}

const tab = (id, over = {}) => ({ id, title: id, type: 'file', absPath: `/w/${id}`, pinned: false, closable: true, ...over });

test('丢席位那一刻：当前编辑缓冲落进本机单槽（页签 + 激活签 + 关闭意图账）', () => {
  const h = makeHarness({ tabs: [tab('a'), tab('b')] });
  h.setActive('b');
  h.addTombstone({ id: 'gone-1', absPath: '/w/gone-1' });
  h.api.captureSeatDraft();
  const raw = h.slot.get('canvas_seat_draft');
  assert.ok(raw, '草稿键必须被写入');
  const rec = JSON.parse(raw);
  assert.equal(rec.v, 2);
  assert.deepEqual(rec.tabs.map((t) => t.id), ['a', 'b']);
  assert.equal(rec.activeTabId, 'b');
  assert.equal(rec.tombstones.length, 1);
  assert.equal(typeof rec.at, 'number');
  assert.equal(h.renderCalls, 1, '落草稿后要重渲取回入口（否则草稿在但没人看得见）');
  assert.equal(h.fetchCalls, 0, '落草稿是纯本机动作，零网络');
});

test('空集不落：没有可序列化页签时不留空草稿条', () => {
  const h = makeHarness({ tabs: [tab('r', { type: 'flow-run' }), tab('u', { type: 'url' })] });
  h.api.captureSeatDraft();
  assert.equal(h.slot.has('canvas_seat_draft'), false);
  assert.equal(h.renderCalls, 0);
  assert.equal(h.fetchCalls, 0);
});

test('读回：无体 / 坏 JSON / 版本不符 / 空 tabs 一律 null（且不清空既有）', () => {
  const none = makeHarness();
  assert.equal(none.api.readSeatDraft(), null);

  const bad = makeHarness({ stored: '{not json' });
  assert.equal(bad.api.readSeatDraft(), null);
  assert.equal(bad.slot.get('canvas_seat_draft'), '{not json', '坏体不得被顺手清掉');

  const old = makeHarness({ stored: JSON.stringify({ v: 1, tabs: [tab('a')] }) });
  assert.equal(old.api.readSeatDraft(), null);

  const empty = makeHarness({ stored: JSON.stringify({ v: 2, tabs: [] }) });
  assert.equal(empty.api.readSeatDraft(), null);

  const good = makeHarness({ stored: JSON.stringify({ v: 2, tabs: [tab('a')], activeTabId: 'a' }) });
  assert.equal(good.api.readSeatDraft().tabs.length, 1);
});

test('存储不可用（配额满）不炸：草稿尽力而为，绝不阻断丢席位那一刻', () => {
  const h = makeHarness({ tabs: [tab('a')], setItemThrows: true });
  assert.doesNotThrow(() => h.api.captureSeatDraft());
  assert.equal(h.fetchCalls, 0);
});

test('取回成功 / 用户忽略都清槽；清一个不存在的槽也不炸', () => {
  const h = makeHarness({ tabs: [tab('a')] });
  h.api.captureSeatDraft();
  assert.ok(h.slot.has('canvas_seat_draft'));
  h.api.clearSeatDraft();
  assert.equal(h.slot.has('canvas_seat_draft'), false);
  assert.doesNotThrow(() => h.api.clearSeatDraft());
});

test('草稿与存档共用同一个序列化核（单源：过滤规则不会两套）', () => {
  const src = extractFn(SRC, 'captureSeatDraft');
  assert.match(src, /collectSerializableTabs\(\)/, 'captureSeatDraft 必须复用 collectSerializableTabs');
  const persist = extractFn(SRC, 'persistTabs');
  assert.match(persist, /collectSerializableTabs\(\)/, 'persistTabs 也必须复用同一个核');
});

// ── 数据保护门（2026-11-21 作者晨间令「既裁数据保护评审门先过」）的四个要求面 ──
// 评审驳回时的原话要点：只存页签不存正文 = 假保护；取回没落回就清槽 = 丢草稿；
// 拿旧草稿盖别人的新改动 = 越界；只测happy path = 故障面没证据。

test('数据保护①正文保护：脏页签的未保存正文进草稿，干净页签不进', () => {
  const h = makeHarness({
    tabs: [tab('dirty-one'), tab('clean-one')],
    dirty: { 'dirty-one': 'console.log("未保存的改动")' },
  });
  h.api.captureSeatDraft();
  const rec = JSON.parse(h.slot.get('canvas_seat_draft'));
  assert.ok(Array.isArray(rec.buffers), '草稿必须带 buffers');
  assert.equal(rec.buffers.length, 1, '只收脏页签的正文');
  assert.equal(rec.buffers[0].absPath, '/w/dirty-one');
  assert.equal(rec.buffers[0].text, 'console.log("未保存的改动")');
  assert.equal(rec.buffers[0].truncated, false);
  assert.equal(h.fetchCalls, 0, '正文保护同样零网络');
});

test('数据保护①正文保护：超上限正文标 truncated，不静默截断', () => {
  const big = 'x'.repeat(400_001);
  const h = makeHarness({ tabs: [tab('huge')], dirty: { huge: big } });
  h.api.captureSeatDraft();
  const rec = JSON.parse(h.slot.get('canvas_seat_draft'));
  assert.equal(rec.buffers.length, 1);
  assert.equal(rec.buffers[0].truncated, true, '超上限必须标 truncated（取回时据此拒写回）');
  assert.equal(rec.buffers[0].text.length, 400_000);
});

test('数据保护①正文保护：落草稿时记磁盘版本读数供冲突判定', () => {
  const h = makeHarness({ tabs: [tab('v', { _fileVer: '120:1700000000000' })], dirty: { v: 'body' } });
  h.api.captureSeatDraft();
  const rec = JSON.parse(h.slot.get('canvas_seat_draft'));
  assert.equal(rec.buffers[0].baseVer, '120:1700000000000');
});

test('数据保护②确认后清槽：取回逻辑只在正文全落回时清槽（源码判据）', () => {
  const src = extractFn(SRC, 'restoreSeatDraft');
  assert.match(src, /if\s*\(\s*stuck\s*===\s*0\s*\)[\s\S]{0,200}?clearSeatDraft\(\)/,
    '只有 stuck===0 才允许清槽');
  assert.match(src, /localStorage\.setItem\(\s*LS_DRAFT_KEY/, '有冲突时必须把草稿留着');
});

test('数据保护③冲突不覆盖：磁盘版本变了就不写回，记为 conflict', () => {
  const src = extractFn(SRC, 'flushSeatDraftBuffers');
  assert.match(src, /buf\.baseVer\s*&&\s*entry\._fileVer\s*&&\s*entry\._fileVer\s*!==\s*buf\.baseVer/,
    '判据必须是版本读数不等');
  assert.match(src, /verdict\s*=\s*'conflict'/, '冲突要有显式判定');
  assert.match(src, /truncated[\s\S]{0,80}verdict\s*=\s*'truncated'/,
    '截断正文不得静默写回');
});

test('数据保护④故障注入：存储写失败 / 坏 JSON / 缺 buffers 字段都不炸', () => {
  // 写失败
  const quota = makeHarness({ tabs: [tab('q')], dirty: { q: 'x' }, setItemThrows: true });
  assert.doesNotThrow(() => quota.api.captureSeatDraft());
  // 坏 JSON
  const broken = makeHarness({ stored: '{not json' });
  assert.equal(broken.api.readSeatDraft(), null);
  assert.equal(broken.slot.get('canvas_seat_draft'), '{not json', '坏体不得被顺手清掉');
  // 旧版本体（无 buffers 字段）仍可读：草稿格式向后兼容
  const legacy = makeHarness({ stored: JSON.stringify({ v: 2, tabs: [tab('a')], activeTabId: 'a' }) });
  const rec = legacy.api.readSeatDraft();
  assert.ok(rec, '无 buffers 的旧草稿必须仍能读回');
  assert.equal(rec.buffers, undefined);
});
