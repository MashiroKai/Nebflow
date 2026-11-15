import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { checkSdd } from '../check-sdd.mjs';

const script = fileURLToPath(new URL('../check-sdd.mjs', import.meta.url));
const change = 'openspec/changes/example';
const spec = `### Requirement: [EW-REQ-001] Traceable intent
The workflow SHALL keep traceable intent.

#### Scenario: [EW-SCN-001] Named acceptance
- **WHEN** a contributor changes behavior
- **THEN** the change has a named acceptance scenario
`;

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'stand-sdd-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const write = (name, value) => {
    fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    fs.writeFileSync(path.join(root, name), value);
  };
  write('openspec/tooling-scope.json', JSON.stringify({ version: 1, paths: ['openspec/', 'scripts/check-sdd.mjs', 'scripts/tests/'] }));
  const trace = {
    version: 1,
    change: 'example',
    mappings: [{
      requirement: 'EW-REQ-001', scenario: 'EW-SCN-001',
      implementation: ['implementation.mjs'],
      tests: [{ path: 'acceptance.mjs', name: 'named acceptance' }],
    }],
  };
  const save = () => write(`${change}/trace.json`, JSON.stringify(trace));
  write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nIntent.\n\n## Requirements\n${spec}`);
  write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec}`);
  for (const name of ['proposal', 'design', 'tasks']) write(`${change}/${name}.md`, `# ${name}\nA bounded change.\n`);
  write('implementation.mjs', 'export const value = 1;\n');
  write('acceptance.mjs', "// named acceptance\n");
  save();
  const git = (...args) => {
    const result = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  const commit = () => {
    git('add', '.');
    git('-c', 'user.name=SDD Test', '-c', 'user.email=sdd@example.invalid', 'commit', '-qm', 'Create fixture');
    return git('rev-parse', 'HEAD');
  };
  const init = () => { git('init', '-q'); return commit(); };
  return { root, write, trace, save, git, init, commit };
}

test('valid artifacts pass without claiming test execution or diff coverage', t => {
  const f = fixture(t);
  assert.deepEqual(checkSdd({ root: f.root }), { currentSpecs: 1, activeChanges: 1, archives: 0, scopedPaths: null });
  const result = spawnSync(process.execPath, [script, '--root', f.root], { encoding: 'utf8', timeout: 10000 });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Diff coverage NOT checked/);
  assert.match(result.stdout, /were NOT executed/);
});

test('unknown base fails closed rather than falling back to no-base mode', t => {
  const f = fixture(t);
  f.init();
  assert.throws(() => checkSdd({ root: f.root, base: 'missing-base' }), /Git failed/);
});

test('explicit base requires a repository and rejects option-like refs', t => {
  const f = fixture(t);
  assert.throws(() => checkSdd({ root: f.root, base: 'HEAD' }), /Git failed/);
  assert.throws(() => checkSdd({ root: f.root, base: '--help' }), /Invalid --base/);
});

test('missing base value and unknown CLI options fail closed', t => {
  const f = fixture(t);
  for (const args of [['--base'], ['--unknown'], ['--root', f.root, '--root', f.root]]) {
    const result = spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 1);
  }
});

test('missing artifacts fail closed', t => {
  const f = fixture(t);
  fs.unlinkSync(path.join(f.root, `${change}/tasks.md`));
  assert.throws(() => checkSdd({ root: f.root }), /Missing file/);
});

test('missing current specs fail closed', t => {
  const f = fixture(t);
  fs.rmSync(path.join(f.root, 'openspec/specs'), { recursive: true });
  assert.throws(() => checkSdd({ root: f.root }), /Missing current specifications/);
});

test('malformed requirement and scenario IDs fail closed', t => {
  for (const [from, to] of [['EW-REQ-001', 'EW-REQ-one'], ['EW-SCN-001', 'EW-SCN-1']]) {
    const f = fixture(t);
    f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec.replaceAll(from, to)}`);
    assert.throws(() => checkSdd({ root: f.root }), /malformed/);
  }
});

test('malformed scenario headings cannot hide unlinked acceptance', t => {
  const f = fixture(t);
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec}\n### Scenario: [EW-SCN-002] Hidden\n- WHEN malformed\n- THEN rejected\n`);
  assert.throws(() => checkSdd({ root: f.root }), /malformed requirement or scenario heading/);
});

test('duplicate requirement IDs fail closed', t => {
  const f = fixture(t);
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec}\n${spec}`);
  assert.throws(() => checkSdd({ root: f.root }), /duplicate ID/);
});

test('duplicate scenario IDs fail closed', t => {
  const f = fixture(t);
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec}\n#### Scenario: [EW-SCN-001] Again\n- WHEN repeated\n- THEN rejected\n`);
  assert.throws(() => checkSdd({ root: f.root }), /duplicate ID/);
});

test('requirements need a scenario and normative language', t => {
  const f = fixture(t);
  f.write(`${change}/specs/engineering-workflow/spec.md`, '## ADDED Requirements\n### Requirement: [EW-REQ-001] Empty\nThe workflow SHALL work.\n');
  assert.throws(() => checkSdd({ root: f.root }), /no scenarios/);
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec.replace('SHALL', 'might')}`);
  assert.throws(() => checkSdd({ root: f.root }), /SHALL or MUST/);
});

test('acceptance scenarios need WHEN and THEN', t => {
  const f = fixture(t);
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec.replace('THEN', 'ALSO')}`);
  assert.throws(() => checkSdd({ root: f.root }), /WHEN and THEN/);
});

test('missing scenario links fail closed', t => {
  const f = fixture(t);
  f.trace.mappings[0].scenario = 'EW-SCN-002';
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /missing scenario link/);
});

test('every delta scenario requires a mapping', t => {
  const f = fixture(t);
  const extra = '\n#### Scenario: [EW-SCN-002] Additional\n- WHEN extra\n- THEN mapped\n';
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec}${extra}`);
  assert.throws(() => checkSdd({ root: f.root }), /missing scenario link EW-SCN-002/);
});

test('proposed additions do not need promotion into accepted current specs', t => {
  const f = fixture(t);
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nIntent\n## Requirements\n${spec.replaceAll('001', '002')}`);
  const base = f.init();
  assert.equal(checkSdd({ root: f.root }).activeChanges, 1);
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 0);
});

test('duplicate mappings and malformed JSON fail closed', t => {
  const f = fixture(t);
  f.trace.mappings.push(f.trace.mappings[0]);
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /duplicate scenario mapping/);
  f.write(`${change}/trace.json`, '{');
  assert.throws(() => checkSdd({ root: f.root }), /trace.json/);
});

test('unsafe relative, absolute and Windows paths fail closed', t => {
  for (const unsafe of ['../secret', '/etc/passwd', 'a/../../secret', 'a\\secret', 'C:/secret', './implementation.mjs', 'a//b']) {
    const f = fixture(t);
    f.trace.mappings[0].implementation = [unsafe];
    f.save();
    assert.throws(() => checkSdd({ root: f.root }), /Unsafe path/);
  }
});

test('symlink escape fails closed even when the target child is missing', t => {
  const f = fixture(t);
  fs.symlinkSync(os.tmpdir(), path.join(f.root, 'escape'));
  f.trace.mappings[0].implementation = [{ path: 'escape/stand-sdd-absent', deleted: true }];
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /Path escapes root/);
});

test('missing implementation or test files and missing test names fail closed', t => {
  const f = fixture(t);
  f.trace.mappings[0].implementation = ['absent.mjs'];
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /Missing file/);
  f.trace.mappings[0].implementation = ['implementation.mjs'];
  f.trace.mappings[0].tests[0].path = 'absent-test.mjs';
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /Missing file/);
  f.trace.mappings[0].tests[0].path = 'acceptance.mjs';
  f.trace.mappings[0].tests[0].name = 'nonexistent test';
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /missing named test/);
});

test('empty tests require a complete reasoned manual exception', t => {
  const f = fixture(t);
  f.trace.mappings[0].tests = [];
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /empty tests/);
  f.trace.mappings[0].manual = { reason: 'N/A', steps: 'Review', expected: 'Good' };
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /incomplete reasoned/);
  f.trace.mappings[0].manual.reason = 'This documentation-only scenario needs human review of the stated contribution contract.';
  f.save();
  assert.equal(checkSdd({ root: f.root }).activeChanges, 1);
});

test('trace command fields are rejected and never executed', t => {
  const f = fixture(t);
  f.trace.mappings[0].tests[0].command = 'touch SHOULD_NOT_EXIST';
  f.save();
  assert.throws(() => checkSdd({ root: f.root }), /unknown field command/);
  assert.equal(fs.existsSync(path.join(f.root, 'SHOULD_NOT_EXIST')), false);
});

test('explicit base catches committed staged unstaged and untracked scoped changes', t => {
  const f = fixture(t);
  const base = f.init();
  f.write('scripts/check-sdd.mjs', 'export const added = true;\n');
  assert.throws(() => checkSdd({ root: f.root, base }), /Unmapped scoped/);
  f.trace.mappings[0].implementation.push('scripts/check-sdd.mjs', `${change}/trace.json`);
  f.save();
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 2);
  f.git('add', '.');
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 2);
  f.commit();
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 2);
  f.write('scripts/check-sdd.mjs', 'export const added = false;\n');
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 2);
});

test('historical application paths stay outside initial diff coverage', t => {
  const f = fixture(t);
  const base = f.init();
  f.write('src/legacy.scala', '// A historical path outside the initial mechanical scope.\n');
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 0);
});

test('deletions require explicit deleted references and respect the supplied base', t => {
  const f = fixture(t);
  f.write('scripts/check-sdd.mjs', 'old code\n');
  const base = f.init();
  fs.unlinkSync(path.join(f.root, 'scripts/check-sdd.mjs'));
  assert.throws(() => checkSdd({ root: f.root, base }), /Unmapped scoped deleted/);
  f.trace.mappings[0].implementation.push({ path: 'scripts/check-sdd.mjs', deleted: true }, `${change}/trace.json`);
  f.save();
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 2);
  assert.equal(checkSdd({ root: f.root }).activeChanges, 1);
  const later = f.commit();
  assert.equal(checkSdd({ root: f.root, base: later }).scopedPaths, 0);
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 2);
});

function deletedFixture(t) {
  const f = fixture(t);
  f.write('scripts/check-sdd.mjs', 'old code\n');
  f.init();
  fs.unlinkSync(path.join(f.root, 'scripts/check-sdd.mjs'));
  f.trace.mappings[0].implementation.push({ path: 'scripts/check-sdd.mjs', deleted: true }, `${change}/trace.json`);
  f.save();
  const base = f.commit();
  return { ...f, base };
}

test('historical active deletions permit later mapped changes in every worktree state', t => {
  const f = deletedFixture(t);
  f.write('scripts/tests/later.test.mjs', '// later acceptance\n');
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /Unmapped scoped present path: scripts\/tests\/later.test.mjs/);
  f.trace.mappings[0].implementation.push('scripts/tests/later.test.mjs');
  f.save();
  assert.equal(checkSdd({ root: f.root, base: f.base }).scopedPaths, 2);
  f.git('add', '.');
  assert.equal(checkSdd({ root: f.root, base: f.base }).scopedPaths, 2);
  const later = f.commit();
  assert.equal(checkSdd({ root: f.root, base: f.base }).scopedPaths, 2);
  assert.equal(checkSdd({ root: f.root, base: later }).scopedPaths, 0);
  f.write('scripts/tests/later.test.mjs', '// updated later acceptance\n');
  assert.equal(checkSdd({ root: f.root, base: later }).scopedPaths, 1);
});

test('new deletion claims for paths absent at the base fail closed', t => {
  const f = fixture(t);
  const base = f.init();
  f.trace.mappings[0].implementation.push({ path: 'never-existed.mjs', deleted: true }, `${change}/trace.json`);
  f.save();
  assert.throws(() => checkSdd({ root: f.root, base }), /deletion not present in diff or matching base trace: never-existed.mjs/);
});

test('new active traces cannot inherit deletion claims from another change', t => {
  const f = deletedFixture(t);
  const renamed = 'openspec/changes/replacement';
  fs.renameSync(path.join(f.root, change), path.join(f.root, renamed));
  f.trace.change = 'replacement';
  f.trace.mappings[0].implementation = [{ path: 'scripts/check-sdd.mjs', deleted: true }];
  f.write(`${renamed}/trace.json`, JSON.stringify(f.trace));
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /deletion not present in diff/);
});

test('historical deletions cannot move to a different requirement or scenario mapping', t => {
  for (const [from, to] of [['EW-SCN-001', 'EW-SCN-002'], ['EW-REQ-001', 'EW-REQ-002']]) {
    const f = deletedFixture(t);
    f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec.replaceAll(from, to)}`);
    const mapping = f.trace.mappings[0];
    if (from.includes('-SCN-')) mapping.scenario = to;
    else mapping.requirement = to;
    f.save();
    assert.throws(() => checkSdd({ root: f.root, base: f.base }), /deletion not present in diff/);
  }
});

test('base references without deleted true cannot authenticate a historical deletion', t => {
  for (const reference of ['never-existed.mjs', { path: 'never-existed.mjs', deleted: false }]) {
    const f = fixture(t);
    f.trace.mappings[0].implementation.push(reference);
    f.save();
    const base = f.init();
    f.trace.mappings[0].implementation.pop();
    f.trace.mappings[0].implementation.push({ path: 'never-existed.mjs', deleted: true });
    f.save();
    assert.throws(() => checkSdd({ root: f.root, base }), /deletion not present in diff/);
  }
});

test('malformed base traces fail closed when historical deletion proof is needed', t => {
  for (const content of ['{', JSON.stringify({ version: 2, change: 'example', mappings: [] })]) {
    const f = deletedFixture(t);
    f.write(`${change}/trace.json`, content);
    const base = f.commit();
    f.save();
    assert.throws(() => checkSdd({ root: f.root, base }), /JSON|invalid trace at base/);
  }
});

test('historical deletions still reject restored files and unsafe paths', t => {
  const f = deletedFixture(t);
  f.write('scripts/check-sdd.mjs', 'restored code\n');
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /deleted path still exists/);
  fs.unlinkSync(path.join(f.root, 'scripts/check-sdd.mjs'));
  fs.symlinkSync('missing-target.mjs', path.join(f.root, 'scripts/check-sdd.mjs'));
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /deletion not present in diff/);
  fs.unlinkSync(path.join(f.root, 'scripts/check-sdd.mjs'));
  fs.symlinkSync(os.tmpdir(), path.join(f.root, 'escape'));
  f.trace.mappings[0].implementation.push({ path: 'escape/stand-sdd-absent', deleted: true });
  f.save();
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /Path escapes root/);
});

test('renames cover both the removed source and destination', t => {
  const f = fixture(t);
  f.write('openspec/old.txt', 'same content\n');
  const base = f.init();
  f.git('mv', 'openspec/old.txt', 'openspec/new.txt');
  f.trace.mappings[0].implementation.push('openspec/new.txt', `${change}/trace.json`);
  f.save();
  assert.throws(() => checkSdd({ root: f.root, base }), /Unmapped scoped deleted path: openspec\/old.txt/);
  f.trace.mappings[0].implementation.push({ path: 'openspec/old.txt', deleted: true });
  f.save();
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 3);
});

test('archives validate structure without requiring old files or current scenario IDs', t => {
  const f = fixture(t);
  const archived = 'openspec/changes/archive/2026-11-13-example';
  fs.mkdirSync(path.dirname(path.join(f.root, archived)), { recursive: true });
  fs.renameSync(path.join(f.root, change), path.join(f.root, archived));
  fs.unlinkSync(path.join(f.root, 'implementation.mjs'));
  fs.unlinkSync(path.join(f.root, 'acceptance.mjs'));
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nNew intent\n## Requirements\n${spec.replaceAll('001', '002')}`);
  assert.equal(checkSdd({ root: f.root }).archives, 1);
  const archivedTrace = JSON.parse(fs.readFileSync(path.join(f.root, `${archived}/trace.json`), 'utf8'));
  archivedTrace.mappings[0].implementation = ['../escape'];
  f.write(`${archived}/trace.json`, JSON.stringify(archivedTrace));
  assert.throws(() => checkSdd({ root: f.root }), /Unsafe path/);
});

function archiveFixture(f) {
  const archived = 'openspec/changes/archive/2026-11-13-example';
  fs.mkdirSync(path.dirname(path.join(f.root, archived)), { recursive: true });
  fs.renameSync(path.join(f.root, change), path.join(f.root, archived));
  f.write(`${archived}/tasks.md`, '- [x] Implement accepted behavior\n');
  return archived;
}

test('archiving the last active change passes its exact transition without a bookkeeping change', t => {
  const f = fixture(t);
  f.write(`${change}/tasks.md`, '- [ ] Implement accepted behavior\n');
  const base = f.init();
  archiveFixture(f);
  const result = checkSdd({ root: f.root, base });
  assert.equal(result.activeChanges, 0);
  assert.equal(result.archives, 1);
  assert.ok(result.scopedPaths > 0);
});

function newArchiveFixture(t) {
  const f = fixture(t);
  fs.rmSync(path.join(f.root, change), { recursive: true });
  const accepted = `## Purpose\nExisting intent\n## Requirements\n${spec.replaceAll('001', '002')}`;
  f.write('openspec/specs/engineering-workflow/spec.md', accepted);
  const base = f.init();
  for (const name of ['proposal', 'design']) f.write(`${change}/${name}.md`, `# ${name}\nNew change\n`);
  f.write(`${change}/tasks.md`, '- [ ] Implement accepted behavior\n');
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## ADDED Requirements\n${spec}`);
  f.write('scripts/check-sdd.mjs', '// newly implemented tooling\n');
  f.trace.mappings[0].implementation.push('scripts/check-sdd.mjs');
  f.save();
  f.write('openspec/specs/engineering-workflow/spec.md', `${accepted}\n${spec}`);
  const archived = archiveFixture(f);
  return { ...f, base, archived, accepted };
}

test('a change proposed implemented and archived in one diff passes without a base-active proposal', t => {
  const f = newArchiveFixture(t);
  const result = checkSdd({ root: f.root, base: f.base });
  assert.equal(result.activeChanges, 0);
  assert.equal(result.archives, 1);
  assert.ok(result.scopedPaths > 0);
});

test('new archives require actual diff deletions and reject fabricated absent paths', t => {
  const f = fixture(t);
  fs.rmSync(path.join(f.root, change), { recursive: true });
  f.write('scripts/check-sdd.mjs', '// old implementation\n');
  const base = f.init();
  for (const name of ['proposal', 'design']) f.write(`${change}/${name}.md`, `# ${name}\nNew change\n`);
  f.write(`${change}/tasks.md`, '- [ ] Implement accepted behavior\n');
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## MODIFIED Requirements\n${spec}`);
  f.trace.mappings[0].implementation.push({ path: 'scripts/check-sdd.mjs', deleted: true });
  f.save();
  fs.unlinkSync(path.join(f.root, 'scripts/check-sdd.mjs'));
  const archived = archiveFixture(f);
  assert.equal(checkSdd({ root: f.root, base }).archives, 1);
  f.trace.mappings[0].implementation.push({ path: 'never-existed.mjs', deleted: true });
  f.write(`${archived}/trace.json`, JSON.stringify(f.trace));
  assert.throws(() => checkSdd({ root: f.root, base }), /deletion not present in diff/);
});

test('new archives reject incomplete tasks incorrect reconciliation and missing live links', t => {
  const f = newArchiveFixture(t);
  f.write(`${f.archived}/tasks.md`, '- [ ] Implement accepted behavior\n');
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /completed tasks/);
  f.write(`${f.archived}/tasks.md`, '- [x] Implement accepted behavior\n');
  f.write('openspec/specs/engineering-workflow/spec.md', f.accepted);
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /accepted reconciliation mismatch/);
  f.write('openspec/specs/engineering-workflow/spec.md', `${f.accepted}\n${spec}`);
  fs.unlinkSync(path.join(f.root, 'scripts/check-sdd.mjs'));
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /Missing file: scripts\/check-sdd.mjs/);
});

test('new archive coverage cannot be reused for later code changes from an old archive', t => {
  const f = newArchiveFixture(t);
  assert.equal(checkSdd({ root: f.root, base: f.base }).archives, 1);
  const acceptedBase = f.commit();
  f.write('scripts/check-sdd.mjs', '// unrelated later implementation\n');
  assert.throws(() => checkSdd({ root: f.root, base: acceptedBase }), /Unmapped scoped present path: scripts\/check-sdd.mjs/);
});

test('new archives do not cover unlisted implementation changes', t => {
  const f = newArchiveFixture(t);
  f.write('scripts/tests/unrelated.test.mjs', '// not mapped by the archive\n');
  assert.throws(() => checkSdd({ root: f.root, base: f.base }), /Unmapped scoped present path: scripts\/tests\/unrelated.test.mjs/);
});

test('archive transitions require completed tasks and unchanged approved artifacts', t => {
  const f = fixture(t);
  f.write(`${change}/tasks.md`, '- [ ] Implement accepted behavior\n');
  const base = f.init();
  const archived = archiveFixture(f);
  f.write(`${archived}/tasks.md`, '- [ ] Implement accepted behavior\n');
  assert.throws(() => checkSdd({ root: f.root, base }), /completed tasks/);
  f.write(`${archived}/tasks.md`, '- [x] Implement accepted behavior\n');
  f.write(`${archived}/proposal.md`, 'Changed after acceptance\n');
  assert.throws(() => checkSdd({ root: f.root, base }), /archive content differs/);
});

test('archive mappings do not authorize unrelated tooling changes', t => {
  const f = fixture(t);
  f.write(`${change}/tasks.md`, '- [ ] Implement accepted behavior\n');
  f.trace.mappings[0].implementation.push('scripts/check-sdd.mjs');
  f.write('scripts/check-sdd.mjs', '// existing implementation\n');
  f.save();
  const base = f.init();
  archiveFixture(f);
  f.write('scripts/check-sdd.mjs', '// unrelated change\n');
  assert.throws(() => checkSdd({ root: f.root, base }), /Unmapped scoped present path: scripts\/check-sdd.mjs/);
});

test('current additions cannot hide behind an old file-level mapping', t => {
  const f = fixture(t);
  f.trace.mappings[0].implementation.push('openspec/specs/engineering-workflow/spec.md');
  f.save();
  const base = f.init();
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nIntent\n## Requirements\n${spec}\n${spec.replaceAll('001', '002')}`);
  assert.throws(() => checkSdd({ root: f.root, base }), /without associated delta: EW-REQ-002/);
});

test('current scenario additions and content edits require matching MODIFIED deltas', t => {
  const f = fixture(t);
  f.trace.mappings[0].implementation.push('openspec/specs/engineering-workflow/spec.md', `${change}/specs/engineering-workflow/spec.md`, `${change}/trace.json`);
  f.save();
  const base = f.init();
  const amended = `${spec}\n#### Scenario: [EW-SCN-002] New acceptance\n- WHEN added\n- THEN traced\n`;
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nIntent\n## Requirements\n${amended}`);
  assert.throws(() => checkSdd({ root: f.root, base }), /does not match associated delta/);
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## MODIFIED Requirements\n${amended}`);
  f.trace.mappings.push({ ...f.trace.mappings[0], scenario: 'EW-SCN-002' });
  f.save();
  assert.ok(checkSdd({ root: f.root, base }).scopedPaths > 0);
});

test('accepted additions reconcile current specs while archiving', t => {
  const f = fixture(t);
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nExisting intent\n## Requirements\n${spec.replaceAll('001', '002')}`);
  f.write(`${change}/tasks.md`, '- [ ] Implement accepted behavior\n');
  const base = f.init();
  archiveFixture(f);
  assert.throws(() => checkSdd({ root: f.root, base }), /accepted reconciliation mismatch/);
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nExisting intent\n## Requirements\n${spec.replaceAll('001', '002')}\n${spec}`);
  assert.equal(checkSdd({ root: f.root, base }).activeChanges, 0);
});

test('normal REMOVED rationale needs no scenario and archived removals must be absent', t => {
  const f = fixture(t);
  f.write(`${change}/tasks.md`, '- [ ] Implement accepted behavior\n');
  f.write(`${change}/specs/engineering-workflow/spec.md`, '## REMOVED Requirements\n### Requirement: [EW-REQ-001] Traceable intent\n**Reason**: Replaced by another contract.\n**Migration**: Use EW-REQ-002.\n');
  delete f.trace.mappings[0].scenario;
  f.save();
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nIntent\n## Requirements\n${spec}\n${spec.replaceAll('001', '002')}`);
  const base = f.init();
  assert.equal(checkSdd({ root: f.root, base }).activeChanges, 1);
  archiveFixture(f);
  assert.throws(() => checkSdd({ root: f.root, base }), /accepted reconciliation mismatch/);
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nIntent\n## Requirements\n${spec.replaceAll('001', '002')}`);
  assert.equal(checkSdd({ root: f.root, base }).archives, 1);
});

test('accepted removal can delete the last current capability file', t => {
  const f = fixture(t);
  f.write(`${change}/tasks.md`, '- [ ] Implement accepted behavior\n');
  f.write(`${change}/specs/engineering-workflow/spec.md`, '## REMOVED Requirements\n### Requirement: [EW-REQ-001] Traceable intent\n**Reason**: This capability is no longer used.\n**Migration**: No replacement is needed.\n');
  delete f.trace.mappings[0].scenario;
  f.save();
  const base = f.init();
  archiveFixture(f);
  fs.unlinkSync(path.join(f.root, 'openspec/specs/engineering-workflow/spec.md'));
  assert.equal(checkSdd({ root: f.root, base }).currentSpecs, 0);
});

test('new capabilities use generic stable IDs without checker edits', t => {
  const f = fixture(t);
  const newSpec = spec.replaceAll('EW-', 'BILLING-API-');
  f.write('openspec/specs/billing/spec.md', `## Purpose\nBilling\n## Requirements\n${newSpec}`);
  assert.equal(checkSdd({ root: f.root }).currentSpecs, 2);
  f.write('openspec/specs/duplicate/spec.md', `## Purpose\nDuplicate\n## Requirements\n${newSpec}`);
  assert.throws(() => checkSdd({ root: f.root }), /duplicate ID/);
});

test('explicit scope registry expands tooling without covering the application tree', t => {
  const f = fixture(t);
  f.write('openspec/tooling-scope.json', JSON.stringify({ version: 1, paths: ['openspec/', 'custom.config.mjs'] }));
  const base = f.init();
  f.write('custom.config.mjs', '// needs trace\n');
  assert.throws(() => checkSdd({ root: f.root, base }), /Unmapped scoped present path: custom.config.mjs/);
  f.trace.mappings[0].implementation.push('custom.config.mjs', `${change}/trace.json`);
  f.save();
  f.write('src/legacy.scala', '// remains outside scope\n');
  assert.equal(checkSdd({ root: f.root, base }).scopedPaths, 2);
});

test('scope deletion cannot disable coverage recorded at the explicit base', t => {
  const f = fixture(t);
  const base = f.init();
  f.write('openspec/tooling-scope.json', JSON.stringify({ version: 1, paths: ['openspec/'] }));
  f.write('scripts/check-sdd.mjs', '// still covered by base registry\n');
  f.trace.mappings[0].implementation.push('openspec/tooling-scope.json', `${change}/trace.json`);
  f.save();
  assert.throws(() => checkSdd({ root: f.root, base }), /Unmapped scoped present path: scripts\/check-sdd.mjs/);
});

test('removed delta scenarios may be absent from current specifications', t => {
  const f = fixture(t);
  f.write(`${change}/specs/engineering-workflow/spec.md`, `## REMOVED Requirements\n${spec}\n**Reason**: The behavior is no longer supported.\n**Migration**: Use the replacement behavior.\n`);
  f.write('openspec/specs/engineering-workflow/spec.md', `## Purpose\nIntent\n## Requirements\n${spec.replaceAll('001', '002')}`);
  assert.equal(checkSdd({ root: f.root }).activeChanges, 1);
});
