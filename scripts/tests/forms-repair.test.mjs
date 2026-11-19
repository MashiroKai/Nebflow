import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { wakeableOf } from '../../src/main/resources/web/js/freezeState.js';
const read = path => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');

// ── Behaviour on the production pure module ──────────────────────────────────────────────────────────────────────
test('FRM-SCN-005 wakeableOf follows the authoritative block', () => {
  const frozen = (extra) => ({ freeze: { frozen: true, wakeable: false, asOf: 1, ...extra } });
  assert.equal(wakeableOf(frozen({ wakeable: false })), false);
  assert.equal(wakeableOf(frozen({ wakeable: true })), true);
  assert.equal(wakeableOf(frozen({ frozen: false, wakeable: true })), false);
});
test('FRM-SCN-005 a delegate-frozen reason is wakeable whichever frame described the row', () => {
  // The gateway marks every delegate-frozen reason as needing a wake (FreezeDisplay.ofWire -> FreezeTruth.wakeable),
  // including the provider-failure reasons that the engine also retries by itself. A live `delegateFrozen` frame has
  // flat fields only; a snapshot row carries the authoritative block. Both must give the same answer.
  for (const code of ['llm-failure', 'cancelled-by-quota', 'tool-failure', 'engine-fault']) {
    const live = { frozen: true, frozenReason: code };
    const snapshotFlat = { status: 'Frozen', freezeReasonWire: 'delegate-frozen:' + code };
    const snapshotBlock = { freeze: { frozen: true, wakeable: true, reasonWire: 'delegate-frozen:' + code, asOf: 10 } };
    assert.equal(wakeableOf(live), true, code + ' from a live frame');
    assert.equal(wakeableOf(snapshotFlat), true, code + ' from a snapshot without a block');
    assert.equal(wakeableOf(snapshotBlock), true, code + ' from a snapshot with a block');
    // The block still wins when it refuses.
    const refused = { freeze: { frozen: true, wakeable: false, reasonWire: 'delegate-frozen:' + code, asOf: 10 } };
    assert.equal(wakeableOf({ ...live, ...refused }), false, code + ' refused by the block');
  }
});
test('FRM-SCN-005 families that recover on their own stay without a wake action when no block is present', () => {
  for (const reason of ['schedule', 'llm-transient', 'network', 'provider-down']) {
    assert.equal(wakeableOf({ frozen: true, freezeReason: reason }), false, reason);
  }
  // Needs-human reasons and an unrecorded reason keep the honest default: a person has to look.
  assert.equal(wakeableOf({ frozen: true, freezeReason: 'loop' }), true);
  assert.equal(wakeableOf({ frozen: true }), true);
});
test('FRM-SCN-007 active count excludes retained history and structural parent', () => {
  // Runs the production state tables and the counting loop against the real row shapes: rows with an info record,
  // group children built from a store entry, and group children built from a flow-step node.
  const source = read('src/main/resources/web/js/main/bgAgents.js');
  const piece = (from, until) => {
    const at = source.indexOf(from);
    assert.notEqual(at, -1, from);
    return source.slice(at, source.indexOf(until, at) + until.length);
  };
  const tables = [
    piece('const BG_FLOW_NODE_STATE = {', '\n};'),
    piece('function bgStoreChildState(status) {', '\n}'),
    piece('function bgPlanRowState(row) {', '\n}'),
  ].join('\n');
  const start = source.indexOf('  let total = 0;', source.indexOf('const wildKeys = bgOrphanRowKeys'));
  const loop = source.slice(start, source.indexOf('\n}', start));
  const store = status => ({level: 'child', child: {type: 'store', child: {status}}});
  const flow = status => ({level: 'child', child: {type: 'flow', node: {status}}});
  const rows = [
    {level: 'root', info: {sessionId: 'root'}}, {level: 'struct', info: {status: 'Idle'}},
    {level: 'head', info: {status: 'Running'}}, {level: 'top', info: {done: true}},
    store('running'), store('restarting'), store('completed'), store('pending-recovery'),
    flow('running'), flow('pending'), flow('unexpected'),
  ];
  const result = vm.runInNewContext('(() => {' + tables + '\n' + loop + '})()', {
    tree: {order: rows}, wildKeys: new Set(), bgRowState: info => info.done ? 'done' : 'active'
  });
  assert.equal(result.total, 9, 'root anchor and structural parent are labels, every other row is retained');
  assert.equal(result.activeCount, 4, 'head, running store child, restarting store child and running flow step');
});

// ── Structure pins (secondary guard) ─────────────────────────────────────────────────────────────────────────────
// These only check that specific lines still exist. They prove nothing about behaviour: the behaviour evidence is the
// Playwright consumer specs (tests/forms-ui-consumers.spec.mjs) and the Scala specs named in the OpenSpec trace.
test('forms structure pins: the edited lines are still in place', () => {
  assert.match(read('src/main/resources/web/js/sidebar/providersUi.js'), /if \(saved === true\) close\(\)/);
  assert.match(read('src/main/resources/web/js/mobile/todoAppleFace.js'), /\n {4}patch\.tagId = draft\.tagId;/);
  const face = read('src/main/resources/web/js/todoFace.js');
  assert.match(face, /const createRow =/);
  assert.match(face, /\.tfl-row\.tfl-row-editing\[data-id=/);
  assert.match(read('src/main/resources/web/js/sidebar/providerPanel.js'), /getType: \(\) => fieldValue\(overlay, 'type'\)/);
  // 重锚（2026-11-19 普查收编批）：imagegenFormReader 的取字段访问器形态已改为
  // `getType: () => fieldValue(overlay, 'type')`（providerPanel.js:610，provider-add-tabs 批起的
  // getProvider/getType/getModel/getBaseUrl/getApiKey 一套）。意图不变：面板仍从单一表单
  // 骨架读 `type` 字段；只把选择器从 `provider:` 重锚到现行访问器写法。
  // （jevTestButton.js 结构钉已随 JeV 退役删除——jev-retire 批 2026-11-16。）
});
