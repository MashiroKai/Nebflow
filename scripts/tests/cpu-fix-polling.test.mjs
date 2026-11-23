// CPU 修复批（2026-11-21）单测：ETag-skip 与 bgPanelModel 子任务索引。
// 与 subagent-visibility.test.mjs 同款：在 vm 沙箱里加载真实 web 源码，断言行为。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const web = new URL('../../src/main/resources/web/js/', import.meta.url);
const source = name => readFileSync(new URL(name, web), 'utf8');
const revisionSource = source('subagentRevision.js').replace(/export /g, '');

function providerFixture({ etag, body }) {
  let current = body;
  let status = 200;
  let headerEtag = etag;
  const parses = { count: 0 };
  const calls = [];
  const context = vm.createContext({
    console, Map, Set, Date, AbortSignal,
    window: { addEventListener: () => {}, dispatchEvent: () => {} },
    document: { visibilityState: 'visible' },
    CustomEvent: class { constructor(type) { this.type = type; } },
    state: { activeSessionId: 'root-a', sessionBgAgents: {} },
    authHeaders: () => ({}),
    EVENTS: { DELEGATE_CHAIN_MIRROR_UPDATED: 'mirror', SESSION_CHANGE: 'session' },
    onMessage: () => {}, onReconnect: () => {}, setSource: () => {}, setChildrenSource: () => {},
    rowFromRegistry: x => x, chainFromStoreRecord: x => x,
    DELEGATE_API: { children: x => `/api/delegates/${x}/children` },
    setDurableTaskFrameObserver: () => {},
    setInterval: () => 1, clearInterval: () => {},
    setTimeout, clearTimeout,
    fetch: async (url) => {
      calls.push(url);
      if (!url.startsWith('/api/subagent-tasks/')) return { ok: true, json: async () => ({ rows: [] }) };
      const headers = { get: name => (name === 'ETag' ? headerEtag : null) };
      return { status, ok: status === 200, headers, json: async () => { parses.count++; return current; } };
    },
  });
  const provider = source('delegateChainProvider.js')
    .replace(/^import .*;$/gm, '')
    .replace(/import \{[\s\S]*?\} from '\.\/delegateChainStub.js';/, '')
    .replace(/export /g, '');
  vm.runInContext(`${revisionSource}\n${provider}`, context);
  return {
    parses, calls,
    setBody: b => { current = b; },
    setEtag: e => { headerEtag = e; },
    refresh: () => vm.runInContext("refreshScopedTasks('root-a')", context),
    rows: () => vm.runInContext("listScopedTasks('root-a')", context),
  };
}

const row = (n, extra = {}) => ({
  taskId: `task-${n}`, parentSessionId: 'root-a', rootSessionId: 'root-a',
  agentName: 'nebflow', description: `d${n}`, status: 'completed',
  spawnedAt: 1000 + n, completedAt: 2000 + n, committedRevision: n, runId: 'r1', ...extra,
});

test('same ETag on the next poll skips json() and the diff (zero parse)', async () => {
  const f = providerFixture({ etag: '"abc"', body: { rootSessionId: 'root-a', tasks: [row(1)] } });
  await f.refresh();
  assert.equal(f.parses.count, 1, 'first poll parses once');
  await f.refresh();
  await f.refresh();
  assert.equal(f.parses.count, 1, 'unchanged ETag: no further json() parses');
  assert.equal(f.rows().length, 1, 'cached rows survive the skipped polls');
});

test('a changed ETag parses again and replaces the rows', async () => {
  const f = providerFixture({ etag: '"v1"', body: { rootSessionId: 'root-a', tasks: [row(1)] } });
  await f.refresh();
  f.setEtag('"v2"');
  f.setBody({ rootSessionId: 'root-a', tasks: [row(1), row(2)] });
  await f.refresh();
  assert.equal(f.parses.count, 2);
  assert.equal(f.rows().length, 2);
});

test('absent ETag never skips (no validator, always parse)', async () => {
  const f = providerFixture({ etag: null, body: { rootSessionId: 'root-a', tasks: [row(1)] } });
  await f.refresh();
  await f.refresh();
  assert.equal(f.parses.count, 2);
});

// bgPanelModel 的 Map 索引：以纯函数形式复刻其对照语义，并与原 find 实现逐项比对。
function legacyIndex(bgAgents, childIds) {
  const out = { ...bgAgents };
  const norm = v => String(v || '').replace(/^team-/, '');
  const map = {};
  for (const child of childIds) {
    const existing = Object.keys(out).find(key => norm(out[key]?.sessionId || key) === child);
    const target = existing || child;
    out[target] = { ...(existing ? out[existing] : {}), durableTask: true };
    map[child] = target;
  }
  return { out, map };
}
function indexed(bgAgents, childIds) {
  const out = { ...bgAgents };
  const norm = v => String(v || '').replace(/^team-/, '');
  const keyBySid = new Map();
  for (const key of Object.keys(out)) {
    const sid = norm(out[key]?.sessionId || key);
    if (!keyBySid.has(sid)) keyBySid.set(sid, key);
  }
  const map = {};
  for (const child of childIds) {
    const existing = keyBySid.get(child);
    const target = existing || child;
    out[target] = { ...(existing ? out[existing] : {}), durableTask: true };
    if (!keyBySid.has(child)) keyBySid.set(child, target);
    map[child] = target;
  }
  return { out, map };
}

test('bgPanelModel Map index matches the legacy find-per-child result', () => {
  const bg = {
    'team-x1': { sessionId: 'team-x1', label: 'a' },
    'k2': { sessionId: 'x2', label: 'b' },
    'plain': { label: 'c' },
  };
  const kids = ['x1', 'x2', 'x3', 'x3', 'plain'];
  assert.deepEqual(indexed(bg, kids), legacyIndex(bg, kids));
});

test('Map index: an id seen first for a new child is reused by later duplicates', () => {
  const r = indexed({}, ['n1', 'n1']);
  assert.deepEqual(Object.keys(r.out), ['n1']);
  assert.equal(r.map.n1, 'n1');
});

// wikiGraph 在途合并：从 markdown.js 原文抽取 wikiGraph/fetchWikiGraph 与其全局态，
// 在 vm 中运行，断言并发突发只产生一次 /api/wiki/graph 请求（同时覆盖失败广播）。
const markdownSrc = source('viewers/markdown.js');
function extractWikiGraph(src) {
  const start = src.indexOf('/** @type {{at:number, data:any}|null} */');
  const endMarker = '\n/**\n * Resolve an Obsidian-style link target';
  const end = src.indexOf(endMarker, start);
  if (start < 0 || end < 0) throw new Error('wikiGraph block not found in markdown.js');
  return src.slice(start, end).replace(/^\/\*\* @type[^\n]*\n/m, '')
    .replace(/\/\*\* @type \{Promise<any>\|null\} \*\//, '');
}

function wikiFixture({ fail = false } = {}) {
  let fetches = 0;
  const context = vm.createContext({
    console, Promise, Date,
    fetch: async () => {
      fetches++;
      await new Promise(r => setTimeout(r, 5));
      if (fail) return { ok: false, status: 503, json: async () => ({}) };
      return { ok: true, status: 200, json: async () => ({ nodes: [], edges: [] }) };
    },
    __authHeaders: () => ({}),
  });
  const block = extractWikiGraph(markdownSrc)
    .replace(/await import\('\.\.\/flowHelpers\.js'\)/g, 'await Promise.resolve({ authHeaders: __authHeaders })');
  vm.runInContext(`${block}\nglobalThis.__wg = wikiGraph;`, context);
  return { wg: () => context.__wg(), fetches: () => fetches };
}

test('wiki graph burst: concurrent callers share one in-flight request', async () => {
  const f = wikiFixture();
  const results = await Promise.all(Array.from({ length: 8 }, () => f.wg()));
  assert.equal(f.fetches(), 1, 'a burst of 8 concurrent callers makes exactly one fetch');
  assert.equal(results.length, 8);
});

test('wiki graph: a failed in-flight request is shared by every waiter and not cached', async () => {
  const f = wikiFixture({ fail: true });
  const settled = await Promise.allSettled(Array.from({ length: 4 }, () => f.wg()));
  assert.equal(f.fetches(), 1, 'failure is shared, not retried per caller');
  assert.ok(settled.every(s => s.status === 'rejected'));
});
