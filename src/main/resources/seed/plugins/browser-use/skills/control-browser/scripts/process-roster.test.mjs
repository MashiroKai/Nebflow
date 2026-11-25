import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {parse, readRoster, writeRoster} from './process-roster.mjs';

test('argument parsing rejects malformed or ambiguous operations', () => {
  assert.deepEqual(parse(['start', '--node', 'n', '--tag', 'web', '--', 'node', 'server.js']), {action:'start', node:'n', tag:'web', command:['node','server.js']});
  assert.deepEqual(parse(['clean', '42']), {action:'clean', pid:42});
  assert.deepEqual(parse(['forget', '42']), {action:'forget', pid:42});
  for (const input of [[], ['clean','-1'], ['clean','0'], ['start','--node','n'], ['list','extra']]) assert.throws(() => parse(input));
});
test('roster round trip and malformed data rejection', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stand-roster-test-'));
  try {
    const file = path.join(dir, 'roster.json');
    assert.deepEqual(readRoster(file), []);
    const entries = [{pid: 123, pgid: 123, node:'n', tag:'web', started:'x'}];
    writeRoster(file, entries);
    assert.deepEqual(readRoster(file), entries);
    assert.equal(fs.statSync(file).mode & 0o777, 0o600);
    fs.writeFileSync(file, '{}');
    assert.throws(() => readRoster(file));
  } finally { fs.rmSync(dir, {recursive:true, force:true}); }
});
