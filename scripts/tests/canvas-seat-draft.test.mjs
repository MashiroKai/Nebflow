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
  extractFn(SRC, 'collectSerializableTabs'),
  extractFn(SRC, 'readSeatDraft'),
  extractFn(SRC, 'captureSeatDraft'),
  extractFn(SRC, 'clearSeatDraft'),
].join('\n');

/** 造一个只够这几个函数用的最小宿主：tabs / readerLocalTabs / activeTabId / tombstones
 *  / localStorage / key / renderDraftEntry / Date，以及一枚**会抛的 fetch** 钩子。 */
function makeHarness({ tabs = [], stored = null, setItemThrows = false } = {}) {
  const slot = new Map();
  if (stored !== null) slot.set('canvas_seat_draft', stored);
  let renderCalls = 0;
  let fetchCalls = 0;
  const localStorage = {
    getItem: (k) => (slot.has(k) ? slot.get(k) : null),
    setItem: (k, v) => { if (setItemThrows) throw new Error('QuotaExceededError'); slot.set(k, v); },
    removeItem: (k) => { slot.delete(k); },
  };
  const sandbox = {
    tabs: new Map(tabs.map((t) => [t.id, t])),
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
    `${FN_SRC}\nglobalThis.__draftApi = { collectSerializableTabs, readSeatDraft, captureSeatDraft, clearSeatDraft };`,
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
