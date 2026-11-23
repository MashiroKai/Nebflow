import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Red until the public tool schema declares the merge question. This is only
// a schema smoke check; it does not replace end-to-end interaction tests.
test('AskUserQuestion exposes a merge question contract', () => {
  const source = readFileSync(new URL('../src/main/scala/nebflow/core/tools/AskUserQuestionTool.scala', import.meta.url), 'utf8');
  assert.match(source, /"merge"\s*->\s*io\.circe\.Json\.obj\(/, 'merge question missing from AskUserQuestion schema');
});
