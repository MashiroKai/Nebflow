import test from 'node:test';
import assert from 'node:assert/strict';
import { validateOfficial, main } from '../check-openspec.mjs';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const valid = { items: [{ id: 'example', valid: true }], summary: { totals: { failed: 0 } } };

test('corrupt cached archive fails closed without download', async () => {
  const root = mkdtempSync(join(tmpdir(), 'openspec-cache-test-'));
  const previous = process.env.XDG_CACHE_HOME;
  const toolchain = JSON.parse(readFileSync(new URL('../../openspec/toolchain.json', import.meta.url)));
  const asset = toolchain.assets[`${process.platform}-${process.arch}`];
  if (!asset) return;
  try {
    process.env.XDG_CACHE_HOME = root;
    const directory = join(root, 'stand', 'openspec', toolchain.version);
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, `${asset.sha256}-${asset.name}`), 'corrupt');
    await assert.rejects(main(['--download']), /cached archive SHA-256 mismatch/);
  } finally {
    if (previous === undefined) delete process.env.XDG_CACHE_HOME;
    else process.env.XDG_CACHE_HOME = previous;
    rmSync(root, { recursive: true, force: true });
  }
});

test('official validator uses isolated read-only pinned invocation', () => {
  const calls = [];
  const report = validateOfficial('/tmp/example-openspec', {
    home: '/tmp/example-home', expectedVersion: '2.0.0',
    run(binary, args, options) {
      calls.push({ binary, args, options });
      return args[0] === '--version' ? '2.0.0\n' : JSON.stringify(valid);
    },
  });
  assert.deepEqual(report, valid);
  assert.deepEqual(calls.map(call => call.args), [['--version'], ['validate', '--all', '--strict', '--json']]);
  assert.equal(calls[0].options.env.HOME, '/tmp/example-home');
  assert.equal(calls[0].options.env.OPENSPEC_TELEMETRY, '0');
});

test('official validator failures and unsafe versions fail closed', async () => {
  const options = { home: '/tmp/example-home', expectedVersion: '2.0.0' };
  assert.throws(() => validateOfficial('x', { ...options, run: () => '1.0.0' }), /does not match/);
  assert.throws(() => validateOfficial('x', { ...options, expectedVersion: 'latest' }), /Invalid pinned/);
  assert.throws(() => validateOfficial('x', { ...options, run: () => { throw new Error('process failed'); } }), /process failed/);
  for (const output of ['bad json', '{}', JSON.stringify({ ...valid, items: [] }), JSON.stringify({ ...valid, items: [{ valid: false }] }), JSON.stringify({ ...valid, summary: { totals: { failed: 1 } } })]) {
    assert.throws(() => validateOfficial('x', { ...options, run: (binary, args) => args[0] === '--version' ? '2.0.0' : output }));
  }
  await assert.rejects(main([]), /exactly one/);
  await assert.rejects(main(['--download', '--binary', '/tmp/x']), /exactly one/);
  await assert.rejects(main(['--binary']), /Usage/);
});
