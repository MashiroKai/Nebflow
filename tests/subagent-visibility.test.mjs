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
  const events = [];
  const notifications = [];
  let pendingResponse;
  const context = vm.createContext({
    console, Map, Set, Date, AbortSignal,
    window: { addEventListener: () => {}, dispatchEvent: event => { events.push(event.type); } },
    document: { visibilityState: 'visible' },
    CustomEvent: class { constructor(type) { this.type = type; } },
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
      if (pendingResponse) await pendingResponse;
      return { status, ok: status === 200, json: async () => snapshot };
    },
  });
  const provider = source('delegateChainProvider.js')
    .replace(/^import .*;$/gm, '')
    .replace(/import \{[\s\S]*?\} from '\.\/delegateChainStub.js';/, '')
    .replace(/export /g, '');
  vm.runInContext(`${revisionSource}\n${provider}`, context);
  context.collectTasks = rows => { notifications.push(rows); };
  vm.runInContext('onDurableTasksChanged(collectTasks)', context);
  return {
    context, calls, intervals, events, notifications,
    resetNotifications: () => { events.length = 0; notifications.length = 0; },
    deferResponse: () => {
      let release;
      pendingResponse = new Promise(resolve => { release = resolve; });
      return () => { pendingResponse = null; release(); };
    },
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

test('unchanged historical snapshots neither notify nor replace projected rows', async () => {
  const f = fixture();
  const history = Array.from({ length: 1070 }, (_, index) => row(index + 1, {
    taskId: `historical-${index}`, status: 'completed', executionStatus: 'finished',
    prompt: 'historical prompt '.repeat(20), resultRef: `history:${index}`,
  }));
  f.snapshot(history);
  await f.refresh();
  const before = f.rows();
  f.resetNotifications();
  f.snapshot(history.map(task => ({ ...task })));
  await f.refresh();
  assert.equal(f.notifications.length, 0);
  assert.equal(f.events.length, 0);
  const after = f.rows();
  assert.equal(after.length, 1070);
  after.forEach((task, index) => {
    assert.equal(task, before[index]);
    assert.equal(task.status, 'completed');
    assert.equal(task.resultRef, `history:${index}`);
  });
});

test('empty polls are silent but removal and auth clearing still notify', async () => {
  const f = fixture();
  // The unrelated delegate-list boot refresh has its own mirror notification.
  await new Promise(setImmediate);
  f.resetNotifications();
  await f.refresh();
  await f.refresh();
  assert.equal(f.notifications.length, 0);
  assert.equal(f.events.length, 0);
  f.snapshot([row(1)]);
  await f.refresh();
  f.resetNotifications();
  f.snapshot([]);
  await f.refresh();
  assert.equal(f.rows().length, 0);
  assert.equal(f.notifications.length, 1);
  assert.equal(f.events.length, 1);
  await f.refresh();
  assert.equal(f.notifications.length, 1);
  f.snapshot([row(2)]);
  await f.refresh();
  f.resetNotifications();
  f.status(403);
  await f.refresh();
  assert.equal(f.rows().length, 0);
  assert.equal(f.notifications.length, 1);
  assert.equal(f.events.length, 1);
});

test('same-revision snapshot field changes and legacy rows remain observable', async () => {
  const f = fixture();
  f.snapshot([row(1)]);
  await f.refresh();
  f.resetNotifications();
  f.snapshot([row(1, { executionStatus: 'running', activityLabel: 'Tool activity' })]);
  await f.refresh();
  assert.equal(f.notifications.length, 1);
  assert.equal(f.rows()[0].activityLabel, 'Tool activity');
  f.snapshot([row(null, { taskId: 'legacy', status: 'completed' })]);
  await f.refresh();
  f.resetNotifications();
  const before = f.rows()[0];
  await f.refresh();
  assert.equal(f.notifications.length, 0);
  assert.equal(f.rows()[0], before);
  f.snapshot([row(null, { taskId: 'legacy', status: 'completed', description: 'Updated legacy' })]);
  await f.refresh();
  assert.equal(f.notifications.length, 1);
  assert.equal(f.rows()[0].description, 'Updated legacy');
});

test('new revisions and live invalidations still notify while stale snapshots stay silent', async () => {
  const f = fixture();
  f.snapshot([row(1)]);
  await f.refresh();
  f.resetNotifications();
  f.snapshot([row(2)]);
  await f.refresh();
  assert.equal(f.notifications.length, 1);
  assert.equal(f.notifications[0][0].committedRevision, 2);
  assert.equal(f.events.length, 1);
  f.resetNotifications();
  assert.equal(f.observe({ ...row(4), type: 'agentTextDelta' }), true);
  assert.equal(f.notifications.length, 1);
  assert.equal(f.notifications[0][0].historyInvalidated, true);
  await f.refresh();
  assert.equal(f.rows()[0].committedRevision, 2);
  assert.equal(f.notifications.length, 1);
  assert.equal(f.events.length, 1);
  f.snapshot([row(4)]);
  await f.refresh();
  assert.equal(f.rows()[0].committedRevision, 4);
  assert.equal(f.notifications.length, 2);
});

test('overlapping scoped refreshes use one request and release the gate after failure', async () => {
  const f = fixture();
  const release = f.deferResponse();
  f.status(503);
  const first = f.refresh();
  const second = f.refresh();
  assert.equal(f.calls.filter(call => call.url.startsWith('/api/subagent-tasks/')).length, 1);
  release();
  await Promise.all([first, second]);
  f.status(200);
  f.snapshot([row(1)]);
  await f.refresh();
  assert.equal(f.calls.filter(call => call.url.startsWith('/api/subagent-tasks/')).length, 2);
  assert.equal(f.rows().length, 1);
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

test('initial terminal task snapshot refreshes each root indicator once', () => {
  const main = source('main.js');
  const start = main.indexOf('onDurableTasksChanged((rows) => {');
  const end = main.indexOf('\n});', start) + 4;
  let consume;
  const cleared = [];
  const refreshed = [];
  const expecting = Object.fromEntries(Array.from({ length: 1070 }, (_, i) => [`child-${i}`, true]));
  const context = vm.createContext({
    onDurableTasksChanged: callback => { consume = callback; },
    clearBusy: id => cleared.push(id),
    state: { turnExpecting: expecting },
    updateBgAgentIndicator: (id, opts) => refreshed.push({ id, opts }),
  });
  vm.runInContext(main.slice(start, end), context);
  consume(Array.from({ length: 1070 }, (_, i) => ({
    taskId: `child-${i}`, rootSessionId: i % 2 ? 'root-a' : 'root-b', status: 'completed',
  })).concat([{ taskId: 'still-running', rootSessionId: 'root-c', status: 'running' }]));
  assert.equal(cleared.length, 1070);
  assert.equal(Object.keys(expecting).length, 0);
  assert.deepEqual(refreshed.map(item => item.id).sort(), ['root-a', 'root-b']);
  assert.ok(refreshed.every(item => item.opts.keepDropdownOpen));
});

test('hidden background dropdown does not construct its task list', () => {
  const code = source('main/bgAgents.js');
  const start = code.indexOf('export function renderBgAgentDropdown()');
  const end = code.indexOf('\n  const model = bgPanelModel(sid);', start);
  let modelReads = 0;
  const context = vm.createContext({
    activeView: { sessionId: 'root-a', dom: {
      bgagentDropdownEl: { classList: { contains: () => true } },
      bgDropdownEl: { classList: { contains: name => name === 'hidden' } },
      bgagentDropdownListEl: {},
    } },
    bgPanelModel: () => { modelReads++; return {}; },
  });
  vm.runInContext(code.slice(start, end).replace('export ', '') + '\n  bgPanelModel(sid);\n}\nrenderBgAgentDropdown();', context);
  assert.equal(modelReads, 0);
  vm.runInContext("activeView.dom.bgDropdownEl.classList.contains = () => false; renderBgAgentDropdown();", context);
  assert.equal(modelReads, 1, 'opening the dropdown must build the current authoritative model');
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
