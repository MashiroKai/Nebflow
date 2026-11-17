import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// The web directory is browser ESM in a CommonJS package.
const source = await readFile(new URL('../src/main/resources/web/js/decisionCardModel.js', import.meta.url), 'utf8');
const { todoToDecisionCard, unresolvedDecisionCardCount, validateNewDecisionCard, currentSessionDecisionCards } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('only rows with an explicit matching session are projected', () => {
  const rows = [{ id: '1', kind: 'todo', sessionId: 's' }, { id: '2', kind: 'todo' }, { id: '3', kind: 'todo', sessionId: 'other' }];
  assert.deepEqual(currentSessionDecisionCards(rows, 's').map(card => card.id), ['1']);
  assert.deepEqual(currentSessionDecisionCards(rows, ''), []);
  const matching = currentSessionDecisionCards([...rows, rows[0], { id: '4', kind: 'todo', sessionId: null }, { id: '5', kind: 'todo', sessionId: 's', completedAt: 1 }], 's');
  assert.equal(unresolvedDecisionCardCount(matching), 1);
});

test('new card length boundaries reject without truncation', () => {
  for (const n of [39, 40, 100, 101]) {
    assert.equal(validateNewDecisionCard({ summary: '字'.repeat(n), content: '' }).summaryValid, n >= 40 && n <= 100);
  }
  for (const n of [299, 300, 301]) {
    assert.equal(validateNewDecisionCard({ summary: '字'.repeat(40), content: '字'.repeat(n) }).contentValid, n <= 300);
  }
});

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
