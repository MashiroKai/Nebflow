import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const web = new URL('../src/main/resources/web/js/', import.meta.url);
const source = name => readFileSync(new URL(name, web), 'utf8');
const revisionSource = source('subagentRevision.js').replace(/export /g, '');

function fixture() {
  let snapshot = { rootSessionId: 'root-a', tasks: [] };
  let status = 200;
  let observer;
  const calls = [];
  const intervals = [];
  const context = vm.createContext({
    console, Map, Set, Date, AbortSignal,
    state: { activeSessionId: 'root-a', sessionBgAgents: {} },
    authHeaders: () => ({}),
    EVENTS: { DELEGATE_CHAIN_MIRROR_UPDATED: 'mirror', SESSION_CHANGE: 'session' },
    onMessage: () => {}, onReconnect: () => {}, setSource: () => {}, setChildrenSource: () => {},
    rowFromRegistry: x => x, chainFromStoreRecord: x => x,
    DELEGATE_API: { children: x => `/api/delegates/${x}/children` },
    setDurableTaskFrameObserver: fn => { observer = fn; },
    setInterval: fn => { intervals.push(fn); return 1; }, clearInterval: () => {},
    setTimeout, clearTimeout,
    fetch: async (url, options) => {
      calls.push({ url, options });
      if (!url.startsWith('/api/subagent-tasks/')) return { ok: true, json: async () => ({ rows: [] }) };
      return { status, ok: status === 200, json: async () => snapshot };
    },
  });
  const provider = source('delegateChainProvider.js')
    .replace(/^import .*;$/gm, '')
    .replace(/import \{[\s\S]*?\} from '\.\/delegateChainStub.js';/, '')
    .replace(/export /g, '');
  vm.runInContext(`${revisionSource}\n${provider}`, context);
  return {
    context, calls, intervals,
    snapshot: rows => { snapshot = { rootSessionId: 'root-a', tasks: rows }; },
    body: body => { snapshot = body; },
    status: value => { status = value; },
    observe: frame => observer(frame),
    refresh: () => vm.runInContext("refreshScopedTasks('root-a')", context),
    rows: () => vm.runInContext("listScopedTasks('root-a')", context),
  };
}

const row = (revision, extra = {}) => ({
  taskId: 'external-child', parentSessionId: 'absent-parent', rootSessionId: 'root-a',
  source: 'external', description: 'Accepted task', status: 'running',
  executionStatus: 'starting', runId: 'run-a', committedRevision: revision, ...extra,
});

test('durable discovery does not require a live parent or agentStart and retains terminal rows', async () => {
  const f = fixture();
  f.snapshot([row(1)]);
  await f.refresh();
  assert.equal(f.rows().length, 1);
  assert.equal(f.rows()[0].parentSessionId, 'absent-parent');
  assert.equal(f.rows()[0].executionStatus, 'starting');
  f.snapshot([row(2, { status: 'completed', executionStatus: 'finished', resultRef: 'history:external-child' })]);
  await f.refresh();
  assert.equal(f.rows()[0].status, 'completed');
  assert.equal(f.rows()[0].resultRef, 'history:external-child');
  assert.equal(f.calls.at(-1).options.credentials, 'same-origin');
});

test('out-of-order snapshots cannot overwrite a newer observed durable revision', async () => {
  const f = fixture();
  f.snapshot([row(3)]);
  await f.refresh();
  assert.equal(f.observe({ ...row(6), type: 'agentTextDelta', delta: 'persisted first' }), true);
  f.snapshot([row(4)]);
  await f.refresh();
  assert.equal(f.rows()[0].committedRevision, 3);
  f.snapshot([row(6, { executionStatus: 'running' })]);
  await f.refresh();
  assert.equal(f.rows()[0].committedRevision, 6);
  assert.equal(f.observe({ type: 'agentTextDelta', nodeSessionId: 'legacy', delta: 'legacy' }), false);
});

test('scope mismatch and transient failure preserve state, auth refusal clears it', async () => {
  const f = fixture();
  f.snapshot([row(1)]);
  await f.refresh();
  f.body({ rootSessionId: 'root-b', tasks: [row(99)] });
  await f.refresh();
  assert.equal(f.rows()[0].committedRevision, 1);
  f.status(503);
  await f.refresh();
  assert.equal(f.rows().length, 1);
  f.status(403);
  await f.refresh();
  assert.equal(f.rows().length, 0);
});

test('ordering detects gaps, run replacement and duplicate delivery without local seq', () => {
  const f = fixture();
  f.context.previous = row(3);
  f.context.incoming = row(8, { runId: 'run-b', seq: 1 });
  const gap = vm.runInContext('observeTaskRevision(previous, incoming)', f.context);
  assert.equal(gap.accept, true);
  assert.equal(gap.resync, true);
  f.context.incoming = row(3, { seq: 999 });
  assert.equal(vm.runInContext('observeTaskRevision(previous, incoming).accept', f.context), false);
  assert.equal(vm.runInContext('observeTaskRevision(previous, incoming, true).accept', f.context), true);
});

test('durable panel merge uses existing identity and flags incomplete collection', () => {
  const f = fixture();
  const code = source('main/bgAgents.js');
  const info = code.slice(code.indexOf('function bgScopeStoreInfo('), code.indexOf('\n/** Silent-completion', code.indexOf('function bgScopeStoreInfo(')));
  const model = code.slice(code.indexOf('function bgPanelModel('), code.indexOf('\n/**', code.indexOf('function bgPanelModel(')));
  vm.runInContext(`${info}\n${model}`, f.context);
  vm.runInContext(`
    state.sessionBgAgents['root-a'] = { 'actor-path': { sessionId: 'external-child', currentTool: 'old' } };
    scopedTasks.set('root-a', new Map([['external-child', ${JSON.stringify(row(4, { status: 'completed', incomplete: true }))}]]));
    function visibleBgAgentEntries(rows) { return Object.entries(rows); }
    function bgDelegateGroups(entries) { return { groups: [], leftovers: entries, orphanBy: new Map() }; }
    function bgRowPlan(model) { return { items: model.leftovers, total: model.leftovers.length, tree: {}, orphanCount: 0 }; }
  `, f.context);
  const result = vm.runInContext("bgPanelModel('root-a')", f.context);
  assert.equal(result.total, 1);
  assert.equal(result.entries[0][0], 'actor-path');
  assert.equal(result.entries[0][1].status, 'Error');
  assert.equal(result.entries[0][1].incomplete, true);
  assert.equal(result.entries[0][1].done, false);
});

test('history invalidation during an in-flight load schedules another authoritative read', async () => {
  const callbacks = [];
  const sent = [];
  const popup = source('bgAgentPopup.js');
  const start = popup.indexOf('let durableHistoryTimer = null;');
  const end = popup.indexOf('\nexport function openStepPopup', start);
  const entry = { view: { visible: true, pagination: { pendingInitialLoad: true } }, meta: {} };
  const context = vm.createContext({
    stepViews: new Map([['external-child', entry]]), currentStepId: 'external-child',
    BG_PAGE_LIMIT: 100, sendWs: message => sent.push(message),
    onDurableTasksChanged: callback => callbacks.push(callback), onReconnect: () => {},
    updateFooterStatus: () => {}, setTimeout: callback => { callback(); return 1; },
  });
  vm.runInContext(`${revisionSource}\n${popup.slice(start, end)}`, context);
  callbacks[0]([{ taskId: 'external-child', executionStatus: 'finished' }]);
  assert.equal(entry.meta.status, 'done');
  assert.equal(sent.length, 0);
  assert.equal(vm.runInContext("dirtyDurableHistories.has('external-child')", context), true);
  entry.view.pagination.pendingInitialLoad = false;
  vm.runInContext('refreshDurablePopupHistory()', context);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].type, 'getHistory');
  assert.equal(sent[0].sessionId, 'external-child');
});

test('interruption remains uncertain and collection abnormality retains the actual reason', () => {
  const f = fixture();
  f.context.task = row(9, { executionStatus: 'interrupted', incomplete: true,
    terminalReason: '<not a business failure>' });
  const interrupted = vm.runInContext('taskExecutionPresentation(task)', f.context);
  assert.equal(interrupted.state, 'pending-recovery');
  assert.match(interrupted.label, /Interrupted.*state uncertain.*Result collection abnormal/);
  assert.equal(interrupted.reason, '<not a business failure>');
  f.context.task = row(10, { executionStatus: 'finished', incomplete: true, terminalReason: 'Missing final result' });
  const incomplete = vm.runInContext('taskExecutionPresentation(task)', f.context);
  assert.equal(incomplete.label, 'Result collection abnormal');
  assert.equal(incomplete.state, 'error');
  assert.equal(incomplete.reason, 'Missing final result');
  f.context.task = row(11, { executionStatus: 'cancelled' });
  assert.equal(vm.runInContext('taskExecutionPresentation(task).label', f.context), 'Cancelled');
});
