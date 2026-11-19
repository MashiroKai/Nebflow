import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');

test('FRB-SCN-003 project list does not expose panorama', () => {
  const source = read('src/main/resources/web/js/projectTab.js');
  assert.doesNotMatch(source, /openDelegateChainStructure|attachProjectsFlowmapFab/);
  assert.match(source, /mountProjectWorkspaces\(card, card\.dataset\.project\)/);
  assert.match(read('src/main/resources/web/js/delegateChainView.js'), /openDelegateChain/);
});

test('FRB-SCN-004 watcher retains other scans but not done-board', () => {
  const source = read('src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala');
  assert.doesNotMatch(source, /DoneBoardWatch/);
  assert.match(source, /DelegateDeliveryReceipts\s*\.sweep\(resources\)/);
  assert.match(source, /ChildOrphanWatch\s*\.scan\(resources\)/);
});
