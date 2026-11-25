// delegate-chain-bulk-load.test.mjs — 重启重连风暴修复批（2026-11-21）的闸。
//
// 事故（作者手机端实测 01:40 + 引擎日志 /tmp/nebflow-sbt-restart.log）：控制台重连
// 时对**每个行键**各发一次 `GET /api/chains/<key>`（N+1 重放；生产盘 1108 个 delegate
// 行 ⇒ 单次重连即上千并发），tunnel guard 按 tunnel-per-ip-cap 整桶 429（01:38 那窗
// 691 条），面板链列表永远加载不齐 ⇒ 持续「加载中」+ 操作卡顿。
//
// 本测试锁住修复后的四条不变量（delegateChainProvider.js 的 refresh 臂）：
//   ① 一轮刷新 = **1 次行面**（/api/delegates）+ **1 次批量摘要**（/api/chain-digests）；
//      详情腿（/api/chains/<key>）有**预算上限**，且活跃行优先；
//   ② 缓存复用：同一键在同一 TTL 窗内不重放（有界轮次后全键入缓存）；
//   ③ 摘要未变 + 缓存已暖 ⇒ 重连的详情请求数 **= 0**（稳态零重放）；
//   ④ 429 ⇒ 按端点类指数退避、尊重 Retry-After，窗口内零重试（禁紧循环）；行面
//      不因详情腿被限流而停摆。
//
// 纯源码 + vm 假时钟（与 delegate-chain-throttle.test.mjs 同一套 harness 手法）：
// 零网络、零浏览器、零真实计时。注册在 scripts/run-checks.mjs（static 档，
// 需 --experimental-vm-modules）。
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const SOURCE = readFileSync(
  new URL('../src/main/resources/web/js/delegateChainProvider.js', import.meta.url),
  'utf8',
);

const DETAIL_BUDGET = 24;
const CHILD_BUDGET = 16;
const TTL_MS = 5000;

/** 假响应helpers。 */
const okJson = (body) => ({ ok: true, status: 200, json: async () => body });
const status = (code, headers = {}) => ({
  ok: false,
  status: code,
  headers: { get: (name) => (Object.prototype.hasOwnProperty.call(headers, name) ? headers[name] : null) },
  json: async () => ({}),
});

/**
 * 起一个隔离的 provider 实例（独立 vm 上下文 + 假时钟 + 记录所有 fetch）。
 * @param {(url: string, now: number) => object} server 请求应答器
 */
function harness(server) {
  let now = 0;
  let nextId = 0;
  const timers = new Map();
  const callbacks = new Map();
  const calls = [];
  let reconnectFn = null;
  let mirrorUpdates = 0;
  let liveSource;

  const schedule = (fn, ms, repeat = 0) => {
    const id = ++nextId;
    timers.set(id, { fn, at: now + ms, repeat });
    return id;
  };
  const context = vm.createContext({
    Date: class extends Date { static now() { return now; } },
    AbortSignal,
    document: { hidden: false, visibilityState: 'visible' },
    window: { addEventListener() {}, dispatchEvent() { mirrorUpdates++; } },
    CustomEvent: class {},
    setTimeout: (fn, ms) => schedule(fn, ms),
    clearTimeout: (id) => timers.delete(id),
    setInterval: (fn, ms) => schedule(fn, ms, ms),
    clearInterval: (id) => timers.delete(id),
    fetch: async (url) => {
      calls.push({ url, at: now });
      return server(url, now);
    },
  });
  const stubs = {
    './ws.js': {
      onMessage: (name, fn) => callbacks.set(name, fn),
      onReconnect: (fn) => { reconnectFn = fn; },
      setDurableTaskFrameObserver() {},
    },
    './state.js': { default: {} },
    './subagentRevision.js': { observeTaskRevision() {}, taskRevision() { return null; } },
    './events.js': {
      EVENTS: {
        DELEGATE_CHAIN_MIRROR_UPDATED: 'updated',
        SESSION_CHANGE: 'session',
        DELEGATE_ARCHIVED: 'archived',
      },
    },
    './flowHelpers.js': { authHeaders: () => ({}) },
    './delegateChainStub.js': {
      setSource(source) { liveSource = source; },
      setChildrenSource() {},
      rowFromRegistry: (r) => r,
      chainFromStoreRecord: () => ({}),
    },
    './delegateApi.js': { DELEGATE_API: { children: (id) => `/api/delegates/${encodeURIComponent(id)}/children` } },
  };

  const module = new vm.SourceTextModule(SOURCE, { context });
  const link = module.link((specifier) => new vm.SyntheticModule(
    Object.keys(stubs[specifier]),
    function () { for (const [key, value] of Object.entries(stubs[specifier])) this.setExport(key, value); },
    { context },
  ));

  return link.then(() => module.evaluate()).then(async () => {
    const flush = async () => { for (let i = 0; i < 200; i++) await Promise.resolve(); };
    const advance = async (to) => {
      for (;;) {
        const entry = [...timers].filter(([, t]) => t.at <= to).sort((a, b) => a[1].at - b[1].at)[0];
        if (!entry) break;
        const [id, timer] = entry;
        now = timer.at;
        if (timer.repeat) timer.at += timer.repeat; else timers.delete(id);
        timer.fn();
        await flush();
      }
      now = to;
      await flush();
    };
    const fire = async (evt) => { callbacks.get(evt)({}); await flush(); };
    const reconnect = async () => { reconnectFn(); await flush(); };
    const detailCalls = () => calls.filter((c) => c.url.startsWith('/api/chains/'));
    const digestCalls = () => calls.filter((c) => c.url.startsWith('/api/chain-digests'));
    const rowCalls = () => calls.filter((c) => c.url === '/api/delegates');
    const childCalls = () => calls.filter((c) => c.url.endsWith('/children'));
    const keysOf = (list) => list.map((c) => c.url.slice('/api/chains/'.length));
    return { flush, advance, fire, reconnect, calls, detailCalls, digestCalls, rowCalls, childCalls, keysOf,
      mirrorUpdates: () => mirrorUpdates, liveSource };
  });
}

/** 60 行夹具：1..40 活跃（running）、41..60 终态（completed）。 */
function fixtureRows() {
  const rows = [];
  for (let i = 1; i <= 40; i++) rows.push({ taskNo: String(i), delegateId: `delegate-${i}`, status: 'running' });
  for (let i = 41; i <= 60; i++) rows.push({ taskNo: String(i), delegateId: `delegate-${i}`, status: 'completed' });
  return rows;
}

// ── 场景 ①②③：批量首屏 + 缓存复用 + 稳态零重放 ─────────────────────────────
async function bulkScenario() {
  const rows = fixtureRows();
  const digests = {};
  for (const r of rows) digests[r.taskNo] = `d-${r.taskNo}`;
  const state = { fingerprint: 'fp-1', digests, server429: false };
  const server = (url) => {
    if (url === '/api/delegates') return okJson({ rows });
    if (url.startsWith('/api/chain-digests')) {
      if (url.includes(`since=${state.fingerprint}`)) {
        return okJson({ v: 1, fingerprint: state.fingerprint, unchanged: true });
      }
      return okJson({ v: 1, fingerprint: state.fingerprint, count: Object.keys(state.digests).length, digests: state.digests });
    }
    if (url.startsWith('/api/chains/')) {
      if (state.server429) return status(429, { 'Retry-After': '5' });
      return okJson({ record: { taskId: 'x', entries: [] }, chain: { nodes: [] } });
    }
    if (url.endsWith('/children')) return okJson({ children: [] });
    return status(404);
  };

  const h = await harness(server);
  await h.flush(); // boot 首刷

  // ① 首屏 = 1 行面 + 1 摘要；详情腿有预算、活跃行优先
  assert.equal(h.rowCalls().length, 1, 'boot: exactly one row-list request');
  assert.equal(h.digestCalls().length, 1, 'boot: exactly one bulk digest request (no N+1 replay)');
  const round1 = h.detailCalls();
  assert.ok(round1.length > 0 && round1.length <= DETAIL_BUDGET,
    `boot: detail legs are capped by the budget (got ${round1.length})`);
  assert.ok(h.keysOf(round1).every((k) => Number(k) <= 40),
    'boot: active rows are served before terminal ones');
  assert.ok(h.childCalls().length <= CHILD_BUDGET, 'boot: child legs are capped by the budget');
  assert.ok(h.mirrorUpdates() > 0, 'boot: mirror broadcast still fires');

  // ②③ 有界轮次把全部键拉进缓存；此后同一 TTL 窗内的重连 = 零详情请求
  await h.advance(1000);
  const seen = new Set(h.keysOf(h.detailCalls()));
  for (let i = 0; i < 6 && seen.size < rows.length; i++) {
    const before = h.detailCalls().length;
    await h.reconnect();
    assert.ok(h.detailCalls().length - before <= DETAIL_BUDGET, 'every round stays within the budget');
    for (const k of h.keysOf(h.detailCalls())) seen.add(k);
  }
  assert.equal(seen.size, rows.length, 'bounded rounds eventually warm every key');
  const allKeys = h.keysOf(h.detailCalls());
  const dupes = allKeys.filter((k, i) => allKeys.indexOf(k) !== i);
  assert.deepEqual(dupes, [], 'no key is replayed inside the TTL window (cache reuse)');

  const beforeSteady = h.detailCalls().length;
  const rowBefore = h.rowCalls().length;
  const digestBefore = h.digestCalls().length;
  await h.reconnect();
  assert.equal(h.detailCalls().length, beforeSteady,
    'steady-state reconnect (warm cache + unchanged digest) issues ZERO detail requests');
  assert.equal(h.rowCalls().length - rowBefore, 1, 'steady-state reconnect still reconciles the row face once');
  assert.equal(h.digestCalls().length - digestBefore, 1, 'steady-state reconnect asks the digest face once');

  // ③ 摘要变了的键才取详情
  state.digests['55'] = 'd-55-changed';
  state.fingerprint = 'fp-2';
  const beforeBump = h.detailCalls().length;
  await h.reconnect();
  const bumped = h.keysOf(h.detailCalls().slice(beforeBump));
  assert.deepEqual(bumped, ['55'], 'only the key whose digest changed is re-fetched');

  // ④ 429：退避窗内零重试（紧循环禁止），行面照常推进
  state.server429 = true;
  const before429 = h.detailCalls().length;
  const rowBefore429 = h.rowCalls().length;
  for (let t = 0; t < 20; t++) {
    await h.advance(1000 + (t + 1) * 500);
    await h.fire('nodeUpdated');
  }
  const storm = h.detailCalls().slice(before429);
  const stormAttempts = storm.filter((c) => c.at > 1500);
  // 一轮内最多 MAX_INFLIGHT 条并发详情腿（4）——被限流时其余腿当场放弃，所以
  // 一个退避窗内最多 4 次尝试；10s 内最多两个窗（Retry-After 5 各一次）。
  assert.ok(stormAttempts.length <= 2 * 4,
    `429: no tight retry loop (${stormAttempts.length} detail attempts in 10s)`);
  const bursts = [];
  for (const c of stormAttempts) {
    const last = bursts[bursts.length - 1];
    if (last && c.at - last.at < 1000) last.n++;
    else bursts.push({ at: c.at, n: 1 });
  }
  assert.ok(bursts.every((b) => b.n <= 4), '429: a burst never exceeds the concurrency cap');
  for (let i = 1; i < bursts.length; i++) {
    assert.ok(bursts[i].at - bursts[i - 1].at >= TTL_MS - 500,
      '429: bursts are spaced by the backoff window (Retry-After honoured)');
  }
  assert.ok(h.rowCalls().length > rowBefore429, '429 on the detail face never stalls the row face');
  return { stormAttempts: stormAttempts.length, bursts: bursts.length };
}

// ── 场景⑤：摘要面不可用（旧引擎 404）时不猜链 ───────────────────────────
// 2026-11-25 作者令后的口径：无批量摘要不猜链；旧引擎兼容回退自 10d4b4273 起取消（派发席 2026-11-26 00:20 裁定）。
async function noDigestFaceScenario() {
  const rows = fixtureRows().slice(0, 30);
  const server = (url) => {
    if (url === '/api/delegates') return okJson({ rows });
    if (url.startsWith('/api/chain-digests')) return status(404);
    if (url.startsWith('/api/chains/')) return okJson({ record: { taskId: 'x', entries: [] }, chain: null });
    if (url.endsWith('/children')) return okJson({ children: [] });
    return status(404);
  };
  const h = await harness(server);
  await h.flush();
  assert.equal(h.rowCalls().length, 1, 'no-digest engine: row face still loads');
  assert.equal(h.liveSource.listDelegateRows().length, rows.length, 'no-digest engine: rows reach the read port');
  assert.equal(h.liveSource.getChainByTask(rows[0].taskNo), null, 'no-digest engine: missing chain degrades immediately');
  await h.advance(1000);
  assert.equal(h.detailCalls().length, 0, 'no-digest engine: no guessed chain details');
  assert.equal(h.childCalls().length, 0, 'no-digest engine: no per-row children requests');
  assert.ok(h.mirrorUpdates() > 0, 'no-digest engine: row update is broadcast');
}

const main = async () => {
  const a = await bulkScenario();
  await noDigestFaceScenario();
  console.log(`delegate-chain-bulk-load: OK (429 window attempts=${a.stormAttempts})`);
};

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
