// Negative-control self-proof for scripts/check-subagent-primitive-visibility.mjs.
//
// The shell gate guards the P1 relaxation surface (the five reuse members exactly
// `private[core]`, Timeout/NeverStartedToken untouched, and no reader introduced outside
// core/tools). These fixtures prove it actually detects breaks: a clean copy is green, each
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
const GATE = 'scripts/check-subagent-primitive-visibility.mjs';
const PRIMITIVES = 'src/main/scala/nebflow/core/tools/SubagentPrimitives.scala';

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'stand-primitive-visibility-'));
  cpSync(join(root, 'src/main/scala'), join(dir, 'src/main/scala'), { recursive: true });
  mkdirSync(dirname(join(dir, GATE)), { recursive: true });
  cpSync(join(root, GATE), join(dir, GATE));
  return dir;
}

function run(dir, rootOverride) {
  const argv = [join(dir, GATE)];
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

test('baseline relaxation surface is green, and mutation-free restore stays green', () => {
  const dir = fixture();
  try {
    const first = run(dir);
    assert.equal(first.status, 0, first.output);
    const second = run(dir);
    assert.equal(second.status, 0, second.output);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects a member tightened back to private[tools] (relaxation inverted)', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    mutate(dir, PRIMITIVES, '  private[core] def reap(', '  private[tools] def reap(');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects a wider-than-needed grant (public member, not private[core])', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    mutate(dir, PRIMITIVES, '  private[core] def startSpy(', '  def startSpy(');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects narrowing a neighbour that must stay public', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    mutate(dir, PRIMITIVES, '  val NeverStartedToken: String', '  private[core] val NeverStartedToken: String');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects an introduced reader outside core/tools (behaviour-node scope)', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    const reader = join(dir, 'src/main/scala/nebflow/core/project/IntruderSpec.scala');
    mkdirSync(dirname(reader), { recursive: true });
    writeFileSync(reader, 'package nebflow.core.project\n\nobject Intruder:\n  val hint = "SubagentPrimitives"\n');
    try {
      const result = run(dir);
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /outside core\/tools/);
    } finally { rmSync(reader, { force: true }); }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('rejects an un-relaxed source (the single point missing) as a red, not a skip', () => {
  const dir = fixture();
  try {
    assert.equal(run(dir).status, 0);
    rmSync(join(dir, PRIMITIVES), { force: true });
    const result = run(dir);
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /scan face missing/);
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
