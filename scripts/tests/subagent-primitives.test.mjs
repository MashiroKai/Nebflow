// Negative-control self-proof for scripts/check-subagent-primitives.mjs.
//
// The shell gate guards the P0 extraction surface (single point + single source + delegation
// wiring). These fixtures prove it actually detects breaks: a clean copy is green, each
// mutation of the surface turns it red, and a root that is not a Stand checkout is a SKIP
// (exit 2) — never a silent pass.
import test from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('../..', import.meta.url));
const GATE = 'scripts/check-subagent-primitives.mjs';
const PRIMITIVES = 'src/main/scala/nebflow/core/tools/SubagentPrimitives.scala';
const CONSUMER = 'src/main/scala/nebflow/core/tools/BlockingSubagent.scala';
const SPEC = 'src/test/scala/nebflow/core/tools/SubagentPrimitiveSpec.scala';

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'stand-subagent-primitives-'));
  cpSync(join(root, 'src/main/scala'), join(dir, 'src/main/scala'), { recursive: true });
  for (const file of [SPEC, GATE]) {
    mkdirSync(dirname(join(dir, file)), { recursive: true });
    cpSync(join(root, file), join(dir, file));
  }
  return dir;
}

function run(dir, rootOverride) {
  const argv = [join(dir, 'scripts', 'check-subagent-primitives.mjs')];
  if (rootOverride) argv.push('--root', rootOverride);
  const result = spawnSync(process.execPath, argv, { encoding: 'utf8', timeout: 30_000 });
  assert.ifError(result.error);
  assert.equal(result.signal, null);
  return { status: result.status, output: result.stdout + result.stderr };
}

function mutate(dir, file, before, after) {
  const path = join(dir, file);
  const source = readFileSync(path, 'utf8');
  assert.ok(source.includes(before), `Missing mutation anchor in ${file}: ${before}`);
  writeFileSync(path, source.replace(before, after));
  try {
    const result = run(dir);
    assert.equal(result.status, 1, `Mutation must fail the gate: ${before}\n${result.output}`);
  } finally {
    writeFileSync(path, source);
  }
}

test('baseline extraction surface is green, and mutation-free restore stays green', () => {
  const dir = fixture();
  try {
    const first = run(dir);
    assert.equal(first.status, 0, first.output);
    const second = run(dir);
    assert.equal(second.status, 0, second.output);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects a second copy of the extracted implementation (single source)', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    // A duplicate token literal in the consumer breaks single source.
    mutate(
      dir,
      CONSUMER,
      'object BlockingSubagent:',
      'object BlockingSubagent:\n  val NeverStartedToken: String = "subagent-never-started"'
    );
    // A duplicated primitive def header in the consumer breaks the move (copy instead of move).
    mutate(dir, CONSUMER, 'object BlockingSubagent:', 'object BlockingSubagent:\n  private[tools] def reap(');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects a duplicated token literal anywhere in src/main (single-source literal)', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    const duplicate = join(dir, 'src/main/scala/nebflow/tools/DuplicateToken.scala');
    mkdirSync(dirname(duplicate), { recursive: true });
    writeFileSync(duplicate, 'object DuplicateToken:\n  val t: String = "subagent-never-started"\n');
    try {
      const result = run(dir);
      assert.equal(result.status, 1, result.output);
    } finally { rmSync(duplicate, { force: true }); }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects un-extracted source (the single point missing) as a red, not a skip', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    rmSync(join(dir, PRIMITIVES), { force: true });
    const result = run(dir);
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /scan face missing/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects broken delegation wiring (export clause removed)', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    mutate(dir, CONSUMER, 'export SubagentPrimitives.{', 'export SomethingElse.{');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a root that is not a Stand checkout is a SKIP (exit 2), never a silent pass', () => {
  const dir = fixture();
  const empty = mkdtempSync(join(tmpdir(), 'stand-not-a-checkout-'));
  try {
    const result = run(dir, empty);
    assert.equal(result.status, 2, result.output);
    assert.match(result.output, /SKIP/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(empty, { recursive: true, force: true });
  }
});
