'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const cli = path.join(__dirname, '../tools/stand-messages');

test('queries retained windows without changing the fixture', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'stand-messages-'));
  try {
    const dir = path.join(root, 'sessions'); fs.mkdirSync(dir);
    const files = {
      '_index.json': { sessions: [{ id: 'one', name: 'Window', bridges: { feishu: { chat_id: 'private' } } }] },
      'one.ui.json': [
        { type: 'user', text: 'hello world', timestamp: 1700000000000, sender: 'Alice' },
        { type: 'ai', text: 'HELLO reply', timestamp: 1700000001000 },
        { type: 'user', text: 'password=supersecret', timestamp: 1700000002000 },
        { type: 'tool', content: 'hello tool secret', timestamp: 1700000003000 }
      ],
      'other.ui.json': [{ type: 'agent', agentId: 'worker', text: 'hello agent', timestamp: 1700000004000 }]
    };
    for (const [name, value] of Object.entries(files)) fs.writeFileSync(path.join(dir, name), JSON.stringify(value));
    const before = Object.fromEntries(fs.readdirSync(dir).map(n => [n, fs.readFileSync(path.join(dir, n), 'utf8')]));
    const run = (...args) => spawnSync(process.execPath, [cli, '--data-root', root, ...args], { encoding: 'utf8' });
    const all = run('--json'); assert.equal(all.status, 0, all.stderr);
    const rows = JSON.parse(all.stdout); assert.equal(rows.length, 3); assert(!all.stdout.includes('supersecret')); assert(!all.stdout.includes('private'));
    assert.equal(JSON.parse(run('--json', '--channel', 'feishu', '--sender', 'alice', '--keyword', 'WORLD', '--since', '1700000000000', '--until', '1700000000000').stdout).length, 1);
    assert.equal(JSON.parse(run('--json', '--window', 'other').stdout)[0].sender, 'worker');
    assert.equal(JSON.parse(run('--json', '--channel', 'console').stdout).length, 0);
    assert.match(run('--window', 'one').stdout, /hello world/);
    assert.notEqual(run('--since', 'invalid').status, 0);
    assert.notEqual(run('--limit', '0').status, 0);
    fs.writeFileSync(path.join(dir, 'other.ui.json'), '{');
    assert.notEqual(run('--json').status, 0);
    fs.writeFileSync(path.join(dir, 'other.ui.json'), before['other.ui.json']);
    assert.deepEqual(Object.fromEntries(fs.readdirSync(dir).map(n => [n, fs.readFileSync(path.join(dir, n), 'utf8')])), before);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
