import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// The web directory is browser ESM in a CommonJS package.
const source = await readFile(new URL('../src/main/resources/web/js/decisionCardModel.js', import.meta.url), 'utf8');
const { todoToDecisionCard, unresolvedDecisionCardCount } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('projects todo read-only without truncating legacy text', () => {
  const task = { id: '1', kind: 'todo', name: '标题', content: '长'.repeat(400), detail: '背景', completedAt: null };
  const original = structuredClone(task);
  const card = todoToDecisionCard(task);
  assert.deepEqual(task, original);
  assert.equal(card.summary.length, 400);
  assert.equal(card.type, 'detail');
  assert.equal(card.status, 'unresolved');
  assert.equal(todoToDecisionCard({ ...task, completedAt: 0 }).status, 'completed');
});

test('rejects non-todos and request mirrors, counts unresolved unique IDs', () => {
  assert.equal(todoToDecisionCard({ id: 'a', kind: 'timer' }), null);
  assert.equal(todoToDecisionCard({ id: 'a', kind: 'todo', name: 'ask-123' }), null);
  const card = todoToDecisionCard({ id: '1', kind: 'todo', content: '事项' });
  assert.equal(card.type, 'direct');
  assert.equal(unresolvedDecisionCardCount([card, card, { id: '2', status: 'completed' }]), 1);
});
