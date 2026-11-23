// scripts/tests/canvas-seat-draft.test.mjs — 失权端草稿的**行为**单测
//（静默接管批 2026-11-19；作者裁定「直接静默吧」+ 动机「现在通知很烦」；
//  2026-11-23 作者令「canvas ask 草稿缺陷，没有分标签页存放」：草稿按页签隔离）。
//
// 与 scripts/check-canvas-seat-draft.mjs 的分工：那道闸量**声明/接线**（源码里有这条腿、
// 落在哪个键、有无通知调用）；这里量**行为**：把 canvas.js 里的真函数抽出来放进 vm 跑。
// 多页签场景 = 同一个 localStorage 底座 + 每页签各一份 sessionStorage / 页签实例 id。
//
// 覆盖四路径（作者令）：写入（各页签只落自己的槽）/ 切换（A→B→切回 A 仍在）/
// 淘汰（上限 8、LRU、配额满先淘汰他页）/ 迁移（旧单槽一次性收编或丢弃）。
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

/** 抽出 [a, b) 之间的源码（用于非函数的顶层 const 块）。 */
function sliceBetween(src, a, b) {
  const i = src.indexOf(a);
  assert.notEqual(i, -1, `canvas.js 里找不到 ${a}`);
  const j = src.indexOf(b, i);
  assert.notEqual(j, -1, `canvas.js 里找不到 ${b}`);
  return src.slice(i, j);
}

const DRAFT_CONST_BLOCK = sliceBetween(SRC, 'const LS_DRAFT_PREFIX = key(', '/** 列出本机全部页签草稿槽键');

const FN_SRC = [
  // 数据保护门（2026-11-21）的正文上限常量与脏判定：collectSeatDraftBuffers 依赖它。
  `const SEAT_DRAFT_BUFFER_MAX_CHARS = 400_000;`,
  extractFn(SRC, 'isTabDirty'),
  extractFn(SRC, 'collectSerializableTabs'),
  extractFn(SRC, 'collectSeatDraftBuffers'),
  DRAFT_CONST_BLOCK,
  extractFn(SRC, 'seatDraftSlotKeys'),
  extractFn(SRC, 'seatDraftSlotAt'),
  extractFn(SRC, 'evictSeatDraftSlots'),
  extractFn(SRC, 'writeSeatDraftSlot'),
  extractFn(SRC, 'migrateLegacySeatDraft'),
  extractFn(SRC, 'readSeatDraft'),
  extractFn(SRC, 'captureSeatDraft'),
  extractFn(SRC, 'clearSeatDraft'),
].join('\n');

/**
 * 造一个页签宿主。storage = 所有页签共享的 localStorage 底座（Map）；
 * session = 本页签的 sessionStorage（Map，刷新保留 = 同一个 Map 再建宿主）；
 * instanceId = 本次加载的 canvasTabInstanceId（刷新会换，session 不换）。
 */
function makePage({ storage = new Map(), session = new Map(), instanceId = 'inst-1', tabs = [], dirty = {}, clock = { t: 1000 }, quota = false } = {}) {
  let fetchCalls = 0;
  const localStorage = {
    get length() { return storage.size; },
    key: (i) => [...storage.keys()][i] ?? null,
    getItem: (k) => (storage.has(k) ? storage.get(k) : null),
    setItem: (k, v) => {
      if (quota) throw new Error('QuotaExceededError');
      storage.set(k, String(v));
    },
    removeItem: (k) => { storage.delete(k); },
  };
  const sessionStorage = {
    getItem: (k) => (session.has(k) ? session.get(k) : null),
    setItem: (k, v) => { session.set(k, String(v)); },
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
    canvasTabInstanceId: instanceId,
    localStorage,
    sessionStorage,
    key: (k) => k,
    fetch: () => { fetchCalls += 1; throw new Error('草稿路径不得发网络请求'); },
    Date: { now: () => clock.t },
    JSON, console,
  };
  const ctx = createContext(sandbox);
  runInContext(
    `${FN_SRC}\nglobalThis.__draftApi = { collectSerializableTabs, collectSeatDraftBuffers, readSeatDraft, captureSeatDraft, clearSeatDraft, migrateLegacySeatDraft, seatDraftSlotKeys, seatDraftSlotKey: () => seatDraftSlotKey(), canvasDraftTabId, SEAT_DRAFT_SLOT_MAX };`,
    ctx);
  const api = ctx.__draftApi;
  return {
    api, storage, session, clock,
    get fetchCalls() { return fetchCalls; },
    setActive: (id) => { ctx.activeTabId = id; },
    addTombstone: (v) => { ctx.sessionTombstones.set(v.id ?? v, v); },
    /** 页签进入后台 → 回来，或刷新：同一 session，新 instanceId。 */
    reload: (newInstanceId) => makePage({ storage, session, instanceId: newInstanceId, tabs, dirty, clock, quota }),
  };
}

const tab = (id, over = {}) => ({ id, title: id, type: 'file', absPath: `/w/${id}`, pinned: false, closable: true, ...over });
const SLOT_PREFIX = 'canvas_seat_draft:';
const slotOf = (tabId) => `${SLOT_PREFIX}${tabId}`;

// ── 基础行为（沿用单槽时代的口径，键改为本页签槽） ──

test('丢席位那一刻：当前编辑缓冲落进本页签的槽（页签 + 激活签 + 关闭意图账）', () => {
  const p = makePage({ instanceId: 'A', tabs: [tab('a'), tab('b')] });
  p.setActive('b');
  p.addTombstone({ id: 'gone-1', absPath: '/w/gone-1' });
  p.api.captureSeatDraft();
  const raw = p.storage.get(slotOf('A'));
  assert.ok(raw, '草稿必须写进本页签的槽');
  const rec = JSON.parse(raw);
  assert.equal(rec.v, 2);
  assert.deepEqual(rec.tabs.map((t) => t.id), ['a', 'b']);
  assert.equal(rec.activeTabId, 'b');
  assert.equal(rec.tombstones.length, 1);
  assert.equal(typeof rec.at, 'number');
  assert.equal(p.fetchCalls, 0, '落草稿是纯本机动作，零网络');
});

test('空集不落：没有可序列化页签时不留空草稿条', () => {
  const p = makePage({ instanceId: 'A', tabs: [tab('r', { type: 'flow-run' }), tab('u', { type: 'url' })] });
  p.api.captureSeatDraft();
  assert.equal(p.storage.size, 0);
  assert.equal(p.fetchCalls, 0);
});

test('读回：无体 / 坏 JSON / 版本不符 / 空 tabs 一律 null（且不清空既有）', () => {
  const none = makePage({ instanceId: 'A' });
  assert.equal(none.api.readSeatDraft(), null);

  const bad = makePage({ instanceId: 'A', storage: new Map([[slotOf('A'), '{not json']]) });
  assert.equal(bad.api.readSeatDraft(), null);
  assert.equal(bad.storage.get(slotOf('A')), '{not json', '坏体不得被顺手清掉');

  const old = makePage({ instanceId: 'A', storage: new Map([[slotOf('A'), JSON.stringify({ v: 1, tabs: [tab('a')] })]]) });
  assert.equal(old.api.readSeatDraft(), null);

  const empty = makePage({ instanceId: 'A', storage: new Map([[slotOf('A'), JSON.stringify({ v: 2, tabs: [] })]]) });
  assert.equal(empty.api.readSeatDraft(), null);

  const good = makePage({ instanceId: 'A', storage: new Map([[slotOf('A'), JSON.stringify({ v: 2, tabs: [tab('a')], activeTabId: 'a' })]]) });
  assert.equal(good.api.readSeatDraft().tabs.length, 1);
});

test('存储不可用（配额满）不炸：草稿尽力而为，绝不阻断丢席位那一刻', () => {
  const p = makePage({ instanceId: 'A', tabs: [tab('a')], quota: true });
  assert.doesNotThrow(() => p.api.captureSeatDraft());
  assert.equal(p.fetchCalls, 0);
});

test('清草稿只清本页签的槽；清一个不存在的槽也不炸', () => {
  const storage = new Map();
  const a = makePage({ storage, instanceId: 'A', tabs: [tab('a')] });
  const b = makePage({ storage, instanceId: 'B', tabs: [tab('b')] });
  a.api.captureSeatDraft();
  b.api.captureSeatDraft();
  a.api.clearSeatDraft();
  assert.equal(storage.has(slotOf('A')), false, 'A 自己的槽已清');
  assert.equal(storage.has(slotOf('B')), true, 'B 的槽必须不受影响');
  assert.doesNotThrow(() => a.api.clearSeatDraft());
});

test('草稿与存档共用同一个序列化核（单源：过滤规则不会两套）', () => {
  const src = extractFn(SRC, 'captureSeatDraft');
  assert.match(src, /collectSerializableTabs\(\)/, 'captureSeatDraft 必须复用 collectSerializableTabs');
  const persist = extractFn(SRC, 'persistTabs');
  assert.match(persist, /collectSerializableTabs\(\)/, 'persistTabs 也必须复用同一个核');
});

// ── 路径①：写入按页签隔离 ──

test('写入：A 与 B 各落各的槽，互不覆盖（单槽缺陷的复现用例）', () => {
  const storage = new Map();
  const a = makePage({ storage, instanceId: 'A', tabs: [tab('fa')] });
  const b = makePage({ storage, instanceId: 'B', tabs: [tab('fb')] });
  a.api.captureSeatDraft();
  b.api.captureSeatDraft();   // 旧单槽形态下这一步会把 A 的草稿覆盖掉
  assert.deepEqual(JSON.parse(storage.get(slotOf('A'))).tabs.map((t) => t.id), ['fa']);
  assert.deepEqual(JSON.parse(storage.get(slotOf('B'))).tabs.map((t) => t.id), ['fb']);
});

// ── 路径②：切换只恢复本页签自己的草稿 ──

test('切换：A 存 → 切 B 存 → 切回 A 草稿还在，B 也还在，且各读各的', () => {
  const storage = new Map();
  const a = makePage({ storage, instanceId: 'A', tabs: [tab('alpha')] });
  a.api.captureSeatDraft();
  const b = makePage({ storage, instanceId: 'B', tabs: [tab('beta')] });
  b.api.captureSeatDraft();
  // 切回 A（同一页签刷新/重新可见 = 同 session 新实例 id）
  const aBack = a.reload('A2');
  assert.equal(aBack.api.canvasDraftTabId, 'A', '页签身份跨刷新保留（sessionStorage）');
  assert.deepEqual(aBack.api.readSeatDraft().tabs.map((t) => t.id), ['alpha'], 'A 读回的是 A 自己的');
  assert.deepEqual(b.api.readSeatDraft().tabs.map((t) => t.id), ['beta'], 'B 也还在、没被 A 动');
});

test('切换：同一页签 instanceId 换了也不丢草稿（刷新 = 新 canvasTabInstanceId，槽仍对号）', () => {
  const storage = new Map();
  const a = makePage({ storage, instanceId: 'A', tabs: [tab('x')] });
  a.api.captureSeatDraft();
  const reloaded = a.reload('A-after-refresh');
  assert.ok(reloaded.api.readSeatDraft(), '刷新后仍读回本页签的草稿');
});

test('页签身份：sessionStorage 为空时以本次实例 id 为种子；已有则沿用（不新造第二套 id）', () => {
  const fresh = makePage({ instanceId: 'SEED-1' });
  assert.equal(fresh.api.canvasDraftTabId, 'SEED-1');
  assert.equal(fresh.session.get('canvas_draft_tab'), 'SEED-1', '种子写进 sessionStorage（键 = key("canvas_draft_tab")）');
  const again = fresh.reload('SEED-2');
  assert.equal(again.api.canvasDraftTabId, 'SEED-1', '刷新后沿用 sessionStorage 里的身份');
});

test('两页签永不共用一槽：不同 session = 不同 tabId', () => {
  const storage = new Map();
  const a = makePage({ storage, session: new Map(), instanceId: 'A' });
  const b = makePage({ storage, session: new Map(), instanceId: 'B' });
  assert.notEqual(a.api.canvasDraftTabId, b.api.canvasDraftTabId);
});

// ── 路径③：容量上限 8 + LRU 淘汰 + 配额满先淘汰他页 ──

test('淘汰：第 9 个页签落草稿时淘汰最久未动的那一个，槽数恒 ≤ 8', () => {
  const storage = new Map();
  const clock = { t: 1000 };
  for (let i = 1; i <= 9; i++) {
    clock.t = 1000 + i;   // 第 i 个页签最后落草稿于 1000+i
    const p = makePage({ storage, clock, instanceId: `T${i}`, tabs: [tab(`t${i}`)] });
    p.api.captureSeatDraft();
  }
  const keys = [...storage.keys()].filter((k) => k.startsWith(SLOT_PREFIX));
  assert.equal(keys.length, 8, '槽数上限 8');
  assert.equal(storage.has(slotOf('T1')), false, '最久未动（at 最早）的 T1 被淘汰');
  assert.equal(storage.has(slotOf('T9')), true, '最新的 T9 保留');
});

test('LRU 口径是「最后一次落草稿」：刚重写过的老页签不会被淘汰', () => {
  const storage = new Map();
  const clock = { t: 1000 };
  for (let i = 1; i <= 8; i++) {
    clock.t = 1000 + i;
    makePage({ storage, clock, instanceId: `T${i}`, tabs: [tab(`t${i}`)] }).api.captureSeatDraft();
  }
  clock.t = 2000;                                  // T1 再次落草稿 ⇒ 变成最新
  makePage({ storage, clock, instanceId: 'T1', tabs: [tab('t1')] }).api.captureSeatDraft();
  clock.t = 2001;
  makePage({ storage, clock, instanceId: 'NEW', tabs: [tab('new')] }).api.captureSeatDraft();
  assert.equal(storage.has(slotOf('T1')), true, 'T1 刚被重写，不应被淘汰');
  assert.equal(storage.has(slotOf('T2')), false, '他页中最久未动的 T2 被淘汰');
});

test('坏体槽视为最旧（at=0）：先被淘汰，不占名额', () => {
  const storage = new Map([[slotOf('JUNK'), '{broken']]);
  const clock = { t: 5000 };
  for (let i = 1; i <= 8; i++) {
    clock.t = 5000 + i;
    makePage({ storage, clock, instanceId: `G${i}`, tabs: [tab(`g${i}`)] }).api.captureSeatDraft();
  }
  assert.equal(storage.has(slotOf('JUNK')), false, '坏体槽被优先清掉');
  assert.equal(storage.size, 8, '槽数恒 ≤ 8');
});

test('未超上限时不误删他页；配额持续满时先淘汰他页再试，仍失败也不抛', () => {
  const storage = new Map([[slotOf('OTHER'), JSON.stringify({ v: 2, tabs: [tab('o')], at: 1 })]]);
  const ok = makePage({ storage, instanceId: 'ME2', tabs: [tab('m2')] });
  ok.api.captureSeatDraft();
  assert.equal(storage.has(slotOf('ME2')), true);
  assert.equal(storage.has(slotOf('OTHER')), true, '未超上限时不误删他页');

  // 配额持续满：writeSeatDraftSlot 先淘汰全部他页再试一次；两次都失败则静默返回，不抛
  const full = makePage({ storage, instanceId: 'ME3', tabs: [tab('m3')], quota: true });
  assert.doesNotThrow(() => full.api.captureSeatDraft());
  assert.equal(storage.has(slotOf('OTHER')), false, '配额满时他页槽被让出（尽力而为）');
});

test('配额满的写入路径：writeSeatDraftSlot 失败后淘汰他页（源码判据）', () => {
  const src = extractFn(SRC, 'writeSeatDraftSlot');
  assert.match(src, /evictSeatDraftSlots\(\s*slotKey\s*,\s*0\s*\)/, '写失败必须淘汰他页槽');
  assert.match(src, /localStorage\.setItem\(\s*slotKey/, '写入走 setItem');
});

// ── 路径④：旧单槽迁移 ──

test('迁移：本页签无自有槽时，旧单槽收编进自己的槽并删旧键（at 保留）', () => {
  const legacy = JSON.stringify({ v: 2, at: 777, tabs: [tab('old')], activeTabId: 'old' });
  const storage = new Map([['canvas_seat_draft', legacy]]);
  const p = makePage({ storage, instanceId: 'FIRST', tabs: [tab('old')] });
  p.api.migrateLegacySeatDraft();
  assert.equal(storage.has('canvas_seat_draft'), false, '旧单槽键被删除');
  assert.equal(storage.get(slotOf('FIRST')), legacy, '正文原样收进本页签槽');
  assert.equal(p.api.readSeatDraft().at, 777);
});

test('迁移：本页签已有自有槽时，旧单槽原样保留（留给下一个没有槽的页签）', () => {
  const legacy = JSON.stringify({ v: 2, at: 1, tabs: [tab('old')] });
  const storage = new Map([['canvas_seat_draft', legacy], [slotOf('X'), JSON.stringify({ v: 2, at: 2, tabs: [tab('x')] })]]);
  const p = makePage({ storage, instanceId: 'X', tabs: [tab('x')] });
  p.api.migrateLegacySeatDraft();
  assert.equal(storage.get('canvas_seat_draft'), legacy, '旧单槽不被吞');
  assert.equal(JSON.parse(storage.get(slotOf('X'))).tabs[0].id, 'x', '自有槽不被覆盖');
});

test('迁移：旧单槽不可读（坏 JSON / 旧版本）⇒ 安全丢弃并删旧键', () => {
  const broken = makePage({ storage: new Map([['canvas_seat_draft', '{nope']]), instanceId: 'B1' });
  broken.api.migrateLegacySeatDraft();
  assert.equal(broken.storage.has('canvas_seat_draft'), false);
  assert.equal(broken.storage.has(slotOf('B1')), false, '不可读的不迁入自有槽');

  const v1 = makePage({ storage: new Map([['canvas_seat_draft', JSON.stringify({ v: 1, tabs: [tab('a')] })]]), instanceId: 'B2' });
  v1.api.migrateLegacySeatDraft();
  assert.equal(v1.storage.has('canvas_seat_draft'), false);
});

test('迁移：没有旧单槽时是空操作', () => {
  const p = makePage({ instanceId: 'NONE' });
  assert.doesNotThrow(() => p.api.migrateLegacySeatDraft());
  assert.equal(p.storage.size, 0);
});

test('迁移：写不进（配额满）时旧键保留，不丢草稿', () => {
  const legacy = JSON.stringify({ v: 2, at: 3, tabs: [tab('keep')] });
  const storage = new Map([['canvas_seat_draft', legacy]]);
  const p = makePage({ storage, instanceId: 'Q', tabs: [tab('keep')], quota: true });
  p.api.migrateLegacySeatDraft();
  assert.equal(storage.get('canvas_seat_draft'), legacy, '写不进就保留旧键，下次再迁');
});

// ── 数据保护门（2026-11-21）的四个要求面（键已切到本页签槽）──

test('数据保护①正文保护：脏页签的未保存正文进草稿，干净页签不进', () => {
  const p = makePage({
    instanceId: 'A',
    tabs: [tab('dirty-one'), tab('clean-one')],
    dirty: { 'dirty-one': 'console.log("未保存的改动")' },
  });
  p.api.captureSeatDraft();
  const rec = JSON.parse(p.storage.get(slotOf('A')));
  assert.ok(Array.isArray(rec.buffers), '草稿必须带 buffers');
  assert.equal(rec.buffers.length, 1, '只收脏页签的正文');
  assert.equal(rec.buffers[0].absPath, '/w/dirty-one');
  assert.equal(rec.buffers[0].text, 'console.log("未保存的改动")');
  assert.equal(rec.buffers[0].truncated, false);
  assert.equal(p.fetchCalls, 0, '正文保护同样零网络');
});

test('数据保护①正文保护：超上限正文标 truncated，不静默截断', () => {
  const big = 'x'.repeat(400_001);
  const p = makePage({ instanceId: 'A', tabs: [tab('huge')], dirty: { huge: big } });
  p.api.captureSeatDraft();
  const rec = JSON.parse(p.storage.get(slotOf('A')));
  assert.equal(rec.buffers.length, 1);
  assert.equal(rec.buffers[0].truncated, true, '超上限必须标 truncated（取回时据此拒写回）');
  assert.equal(rec.buffers[0].text.length, 400_000);
});

test('数据保护①正文保护：落草稿时记磁盘版本读数供冲突判定', () => {
  const p = makePage({ instanceId: 'A', tabs: [tab('v', { _fileVer: '120:1700000000000' })], dirty: { v: 'body' } });
  p.api.captureSeatDraft();
  const rec = JSON.parse(p.storage.get(slotOf('A')));
  assert.equal(rec.buffers[0].baseVer, '120:1700000000000');
});

test('数据保护②确认后清槽：取回逻辑只在正文全落回时清本页签槽（源码判据）', () => {
  const src = extractFn(SRC, 'restoreSeatDraftOnce');
  assert.match(src, /if\s*\(\s*stuck\s*===\s*0\s*\)[\s\S]{0,200}?clearSeatDraft\(\)/,
    '只有 stuck===0 才允许清槽');
  assert.match(src, /writeSeatDraftSlot\(\s*seatDraftSlotKey\(\)/, '有冲突时必须把本页签草稿留着');
});

test('数据保护③冲突不覆盖：磁盘版本变了就不写回，记为 conflict；脏页签记 local-dirty', () => {
  const src = extractFn(SRC, 'flushSeatDraftBuffers');
  assert.match(src, /buf\.baseVer\s*&&\s*entry\._fileVer\s*&&\s*entry\._fileVer\s*!==\s*buf\.baseVer/,
    '判据必须是版本读数不等');
  assert.match(src, /verdict\s*=\s*'conflict'/, '冲突要有显式判定');
  assert.match(src, /truncated[\s\S]{0,80}verdict\s*=\s*'truncated'/, '截断正文不得静默写回');
  assert.match(src, /isTabDirty\(entry\)[\s\S]{0,80}verdict\s*=\s*'local-dirty'/, '正在编辑的页签不得被草稿盖掉');
});

test('数据保护④故障注入：存储写失败 / 坏 JSON / 缺 buffers 字段都不炸', () => {
  const quota = makePage({ instanceId: 'Q', tabs: [tab('q')], dirty: { q: 'x' }, quota: true });
  assert.doesNotThrow(() => quota.api.captureSeatDraft());
  const broken = makePage({ instanceId: 'K', storage: new Map([[slotOf('K'), '{not json']]) });
  assert.equal(broken.api.readSeatDraft(), null);
  assert.equal(broken.storage.get(slotOf('K')), '{not json', '坏体不得被顺手清掉');
  const legacyBody = makePage({ instanceId: 'L', storage: new Map([[slotOf('L'), JSON.stringify({ v: 2, tabs: [tab('a')], activeTabId: 'a' })]]) });
  const rec = legacyBody.api.readSeatDraft();
  assert.ok(rec, '无 buffers 的旧草稿必须仍能读回');
  assert.equal(rec.buffers, undefined);
});
