import test from 'node:test';
import assert from 'node:assert/strict';
import { audit } from '../check-ask-write-scope.mjs';

test('ordinary routing passes', () => assert.deepEqual(audit('ordinary()', 'AskPathBoundary.decideSync(a, b)'), []));
test('authorization early return is detected', () => assert.deepEqual(audit('if askSession then return restricted', 'AskPathBoundary.decideSync(a, b)'), ['Ask-specific authorization early return']));
test('Ask Bash diversion is detected', () => assert.deepEqual(audit('face.runAskBash(input)', 'AskPathBoundary.decideSync(a, b)'), ['Ask-specific Bash route']));
test('review identity guard is required', () => assert.deepEqual(audit('ordinary()', 'capture()'), ['missing inline-review identity guard']));
test('missing sources fail closed', () => assert.equal(audit(null, null).length, 2));
