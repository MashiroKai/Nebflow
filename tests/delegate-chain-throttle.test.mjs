import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../src/main/resources/web/js/delegateChainProvider.js', import.meta.url), 'utf8');
let now = 0, nextId = 0;
const timers = new Map(), callbacks = new Map(), calls = [];
const schedule = (fn, ms, repeat = 0) => { const id = ++nextId; timers.set(id, { fn, at: now + ms, repeat }); return id; };
const context = vm.createContext({
  Date: class extends Date { static now() { return now; } }, AbortSignal,
  document: { hidden: false, visibilityState: 'visible' },
  window: { addEventListener() {}, dispatchEvent() {} }, CustomEvent: class {},
  setTimeout: (fn, ms) => schedule(fn, ms), clearTimeout: id => timers.delete(id),
  setInterval: (fn, ms) => schedule(fn, ms, ms), clearInterval: id => timers.delete(id),
  fetch: async url => {
    if (url === '/api/delegates') return { ok: true, status: 200, json: async () => ({ rows: [{ taskNo: '1', delegateId: 'delegate-1', external: true }] }) };
    calls.push({ url, at: now });
    return { ok: false, status: 429, headers: { get: () => '5' } };
  },
});
const stubs = {
  './ws.js': { onMessage: (n, f) => callbacks.set(n, f), onReconnect() {}, setDurableTaskFrameObserver() {} },
  './state.js': { default: {} }, './subagentRevision.js': { observeTaskRevision() {}, taskRevision() {} },
  './events.js': { EVENTS: { DELEGATE_CHAIN_MIRROR_UPDATED: 'updated', SESSION_CHANGE: 'session', DELEGATE_ARCHIVED: 'archived' } },
  './flowHelpers.js': { authHeaders: () => ({}) },
  './delegateChainStub.js': { setSource() {}, setChildrenSource() {}, rowFromRegistry: r => r, chainFromStoreRecord: () => ({}) },
  './delegateApi.js': { DELEGATE_API: { children: id => `/api/delegates/${id}/children` } },
};
const module = new vm.SourceTextModule(source, { context });
await module.link(specifier => new vm.SyntheticModule(Object.keys(stubs[specifier]), function () { for (const [key, value] of Object.entries(stubs[specifier])) this.setExport(key, value); }, { context }));
await module.evaluate();
async function flush() { for (let i = 0; i < 50; i++) await Promise.resolve(); }
async function advance(to) {
  while (true) {
    const entry = [...timers].filter(([, t]) => t.at <= to).sort((a, b) => a[1].at - b[1].at)[0];
    if (!entry) break;
    const [id, timer] = entry; now = timer.at;
    if (timer.repeat) timer.at += timer.repeat; else timers.delete(id);
    timer.fn(); await flush();
  }
  now = to; await flush();
}
await flush();
for (let i = 0; i < 4; i++) { callbacks.get('nodeUpdated')({}); await advance(now + 1000); }
const retries = calls.filter(c => c.url === '/api/chains/1' && c.at > 0 && c.at < 5000).length;
console.log(`Retry-After 5: initial=${calls.filter(c => c.at === 0).length}, requests during first 5s=${retries}`);
if (process.argv.includes('--assert-backoff')) {
  assert.equal(retries, 0);
  await advance(5000); callbacks.get('nodeUpdated')({}); await advance(5500);
  assert.equal(calls.filter(c => c.url === '/api/chains/1').length, 2, 'request resumes after Retry-After');
}
