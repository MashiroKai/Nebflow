#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// The reviewed scope is data, not an implicit repository-wide backfill gate.
const SCOPE_FILE = 'openspec/tooling-scope.json';
const ID = /^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-(REQ|SCN)-[0-9]{3,}$/;
const fail = message => { throw new Error(message); };
const text = value => typeof value === 'string' && value.trim().length > 0;

function keys(value, allowed, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${label}: expected object`);
  for (const key of Object.keys(value)) if (!allowed.includes(key)) fail(`${label}: unknown field ${key}`);
}

function safePath(root, relative, mustExist = true) {
  if (!text(relative) || path.posix.isAbsolute(relative) || relative.includes('\\') ||
      relative.includes('\0') || relative.split('/').some(part => !part || part === '.' || part === '..') ||
      /^[A-Za-z]:/.test(relative)) fail(`Unsafe path: ${String(relative)}`);
  const full = path.join(root, relative);
  // Check existing ancestors too: a missing child under an escaping symlink is unsafe.
  let ancestor = full;
  while (!fs.existsSync(ancestor) && ancestor !== root) ancestor = path.dirname(ancestor);
  const real = fs.realpathSync(ancestor);
  if (real !== root && !real.startsWith(root + path.sep)) fail(`Path escapes root: ${relative}`);
  if (mustExist && (!fs.existsSync(full) || !fs.statSync(full).isFile())) fail(`Missing file: ${relative}`);
  return full;
}

function files(root, relative) {
  const folder = safePath(root, relative, false);
  if (!fs.existsSync(folder)) return [];
  if (!fs.statSync(folder).isDirectory()) fail(`Expected directory: ${relative}`);
  const result = [];
  for (const entry of fs.readdirSync(folder, { withFileTypes: true })) {
    const child = `${relative}/${entry.name}`;
    if (entry.isSymbolicLink()) fail(`Symlink in specification tree: ${child}`);
    if (entry.isDirectory()) result.push(...files(root, child));
    else if (entry.isFile()) result.push(child);
  }
  return result;
}

function read(root, relative) { return fs.readFileSync(safePath(root, relative), 'utf8'); }

function parseSpec(root, relative, delta = false, source = read(root, relative)) {
  const capability = relative.split('/').at(-2);
  if (!delta && (!/^## Purpose\s*$/m.test(source) || !/^## Requirements\s*$/m.test(source))) {
    fail(`${relative}: expected Purpose and Requirements sections`);
  }
  if (delta && !/^## (ADDED|MODIFIED|REMOVED) Requirements\s*$/m.test(source)) {
    fail(`${relative}: expected a change-delta section`);
  }
  const requirements = new Map();
  const scenarios = new Map();
  let current;
  let scenario;
  let section;
  const finishScenario = () => {
    if (scenario && (!/\bWHEN\b/.test(scenario.body) || !/\bTHEN\b/.test(scenario.body))) {
      fail(`${relative}: ${scenario.id} needs WHEN and THEN`);
    }
  };
  const finishRequirement = () => {
    finishScenario();
    if (current && current.section !== 'REMOVED' && !current.scenarios.length) fail(`${relative}: ${current.id} has no scenarios`);
    if (current?.section === 'REMOVED' && (!/\*\*Reason\*\*:/.test(current.body) || !/\*\*Migration\*\*:/.test(current.body))) {
      fail(`${relative}: removed requirement needs Reason and Migration`);
    }
    if (current && current.section !== 'REMOVED' && !/\b(SHALL|MUST)\b/.test(current.body)) {
      fail(`${relative}: ${current.id} needs SHALL or MUST`);
    }
  };
  for (const line of source.split('\n')) {
    if (/^\s*#{3,}\s+(Requirement|Scenario)\b/.test(line) &&
        !/^### Requirement:/.test(line) && !/^#### Scenario:/.test(line)) {
      fail(`${relative}: malformed requirement or scenario heading`);
    }
    const sectionMatch = /^## (ADDED|MODIFIED|REMOVED) Requirements\s*$/.exec(line);
    if (sectionMatch) section = sectionMatch[1];
    if (/^### Requirement:/.test(line)) {
      finishRequirement();
      const match = /^### Requirement: \[([^\]]+)\] (\S.*)$/.exec(line);
      if (!match || !ID.test(match[1]) || !match[1].includes('-REQ-')) fail(`${relative}: malformed requirement ID`);
      if (requirements.has(match[1])) fail(`${relative}: duplicate ID ${match[1]}`);
      current = { id: match[1], title: match[2], body: '', content: `${line}\n`, scenarios: [], section, capability };
      requirements.set(current.id, current);
      scenario = undefined;
    } else if (/^#### Scenario:/.test(line)) {
      finishScenario();
      const match = /^#### Scenario: \[([^\]]+)\] (\S.*)$/.exec(line);
      if (!current || !match || !ID.test(match[1]) || !match[1].includes('-SCN-') ||
          match[1].split('-SCN-')[0] !== current.id.split('-REQ-')[0]) fail(`${relative}: malformed or orphan scenario ID`);
      if (scenarios.has(match[1])) fail(`${relative}: duplicate ID ${match[1]}`);
      scenario = { id: match[1], requirement: current.id, body: '', section: current.section };
      scenarios.set(scenario.id, scenario);
      current.scenarios.push(scenario.id);
      current.content += `${line}\n`;
    } else {
      if (current && !/^## /.test(line)) {
        current.body += `${line}\n`;
        current.content += `${line}\n`;
      }
      if (scenario) scenario.body += `${line}\n`;
    }
  }
  finishRequirement();
  if (!requirements.size) fail(`${relative}: no requirements`);
  for (const requirement of requirements.values()) requirement.content = requirement.content.trim();
  return { requirements, scenarios };
}

function mergeSpecs(target, source, label) {
  for (const field of ['requirements', 'scenarios']) {
    for (const [id, value] of source[field]) {
      if (target[field].has(id)) fail(`${label}: duplicate ID ${id}`);
      target[field].set(id, value);
    }
  }
}
const emptySpecs = () => ({ requirements: new Map(), scenarios: new Map() });

function git(root, args) {
  const result = spawnSync('git', ['-C', root, ...args], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 8 * 1024 * 1024,
  });
  if (result.error || result.signal || result.status !== 0) {
    fail(`Git failed (${args[0]}): ${result.error?.message || result.stderr.trim() || result.signal || result.status}`);
  }
  return result.stdout;
}

function resolveBase(root, base) {
  if (!text(base) || base.startsWith('-')) fail('Invalid --base');
  if (fs.realpathSync(git(root, ['rev-parse', '--show-toplevel']).trim()) !== root) fail('--root must be the Git repository root');
  return git(root, ['rev-parse', '--verify', '--end-of-options', `${base}^{commit}`]).trim();
}

function baseFiles(root, oid) {
  return git(root, ['ls-tree', '-r', '--name-only', '-z', oid]).split('\0').filter(Boolean);
}

function baseRead(root, oid, relative) {
  return git(root, ['show', `${oid}:${relative}`]);
}

function loadScope(root, source) {
  const scope = JSON.parse(source ?? read(root, SCOPE_FILE));
  keys(scope, ['version', 'paths'], SCOPE_FILE);
  if (scope.version !== 1 || !Array.isArray(scope.paths) || !scope.paths.length) fail('Invalid tooling scope');
  const paths = new Set();
  for (const item of scope.paths) {
    if (!text(item)) fail('Invalid tooling scope path');
    const name = item.endsWith('/') ? item.slice(0, -1) : item;
    safePath(root, name, false);
    if (paths.has(item)) fail(`Duplicate scope path: ${item}`);
    paths.add(item);
  }
  if (!paths.has('openspec/')) fail('Tooling scope must include openspec/');
  return paths;
}

function changedPaths(root, base) {
  if (!text(base) || base.startsWith('-')) fail('Invalid --base');
  if (fs.realpathSync(git(root, ['rev-parse', '--show-toplevel']).trim()) !== root) fail('--root must be the Git repository root');
  const oid = git(root, ['rev-parse', '--verify', '--end-of-options', `${base}^{commit}`]).trim();
  const records = git(root, ['diff', '--name-status', '-z', '--find-renames', oid, '--']).split('\0');
  const changed = new Map();
  for (let index = 0; index < records.length && records[index];) {
    const status = records[index++];
    const first = records[index++];
    if (!first) fail('Malformed Git diff output');
    if (/^[RC]\d+$/.test(status)) {
      const second = records[index++];
      if (!second) fail('Malformed Git rename output');
      if (status.startsWith('R')) changed.set(first, 'deleted');
      changed.set(second, 'present');
    } else if (/^[AMDT]$/.test(status)) changed.set(first, status === 'D' ? 'deleted' : 'present');
    else fail(`Unsupported Git status: ${status}`);
  }
  for (const file of git(root, ['ls-files', '--others', '--exclude-standard', '-z']).split('\0').filter(Boolean)) changed.set(file, 'present');
  return changed;
}

function validateTrace(root, relative, specs, archived, coverage, changes, liveArchive = false, history) {
  let trace;
  try { trace = JSON.parse(read(root, relative)); } catch (error) { fail(`${relative}: ${error.message}`); }
  keys(trace, ['version', 'change', 'mappings'], relative);
  const folderName = relative.split('/').at(-2);
  const expectedName = archived ? folderName.replace(/^\d{4}-\d{2}-\d{2}-/, '') : folderName;
  if (trace.version !== 1 || trace.change !== expectedName || !Array.isArray(trace.mappings) || !trace.mappings.length) fail(`${relative}: invalid version, change name or mappings`);
  let baseTrace;
  const historicalDeletion = (mapping, name) => {
    if (!history || changes.has(name) || history.files.has(name) || !history.files.has(relative)) return false;
    if (!baseTrace) {
      baseTrace = JSON.parse(baseRead(root, history.oid, relative));
      keys(baseTrace, ['version', 'change', 'mappings'], `${relative} at base`);
      if (baseTrace.version !== 1 || baseTrace.change !== trace.change || !Array.isArray(baseTrace.mappings)) {
        fail(`${relative}: invalid trace at base`);
      }
    }
    return baseTrace.mappings.some(previous => previous?.requirement === mapping.requirement &&
      previous.scenario === mapping.scenario && Array.isArray(previous.implementation) &&
      previous.implementation.some(reference => reference && typeof reference === 'object' &&
        !Array.isArray(reference) && Object.keys(reference).length === 2 &&
        reference.path === name && reference.deleted === true));
  };
  const linked = new Set();
  for (const mapping of trace.mappings) {
    keys(mapping, ['requirement', 'scenario', 'implementation', 'tests', 'manual'], relative);
    const requirement = specs.requirements.get(mapping.requirement);
    const removal = requirement?.section === 'REMOVED' && mapping.scenario === undefined;
    const definition = specs.scenarios.get(mapping.scenario);
    if (!removal && (!definition || definition.requirement !== mapping.requirement)) fail(`${relative}: missing scenario link ${mapping.scenario}`);
    const identity = removal ? mapping.requirement : mapping.scenario;
    if (linked.has(identity)) fail(`${relative}: duplicate scenario mapping ${identity}`);
    linked.add(identity);
    if (!Array.isArray(mapping.implementation) || !mapping.implementation.length) fail(`${relative}: empty implementation paths`);
    for (const reference of mapping.implementation) {
      let name = reference;
      let deleted = false;
      if (typeof reference !== 'string') {
        keys(reference, ['path', 'deleted'], relative);
        if (reference.deleted !== true) fail(`${relative}: deletion reference requires deleted: true`);
        name = reference.path;
        deleted = true;
      }
      safePath(root, name, (!archived || liveArchive) && !deleted);
      if (!archived || liveArchive) {
        if (deleted && fs.existsSync(path.join(root, name))) fail(`${relative}: deleted path still exists: ${name}`);
        if (changes && deleted && changes.get(name) !== 'deleted' &&
            !historicalDeletion(mapping, name)) fail(`${relative}: deletion not present in diff or matching base trace: ${name}`);
        const status = deleted ? 'deleted' : 'present';
        if (coverage.has(name) && coverage.get(name) !== status) fail(`${relative}: conflicting path status: ${name}`);
        if (!liveArchive || changes?.get(name) === status) coverage.set(name, status);
      }
    }
    if (!Array.isArray(mapping.tests)) fail(`${relative}: tests must be an array`);
    if (!mapping.tests.length && !mapping.manual) fail(`${relative}: empty tests require a reasoned manual exception`);
    for (const test of mapping.tests) {
      keys(test, ['path', 'name'], relative);
      if (!text(test.name)) fail(`${relative}: missing test name`);
      safePath(root, test.path, !archived || liveArchive);
      if ((!archived || liveArchive) && !read(root, test.path).includes(test.name)) fail(`${relative}: missing named test ${test.name}`);
    }
    if (mapping.manual) {
      keys(mapping.manual, ['reason', 'steps', 'expected'], relative);
      if (!text(mapping.manual.reason) || mapping.manual.reason.trim().length < 20 ||
          !text(mapping.manual.steps) || !text(mapping.manual.expected)) fail(`${relative}: incomplete reasoned manual exception`);
    }
  }
  for (const scenario of specs.scenarios.keys()) if (!linked.has(scenario)) fail(`${relative}: missing scenario link ${scenario}`);
  for (const requirement of specs.requirements.values()) {
    if (requirement.section === 'REMOVED' && !requirement.scenarios.length && !linked.has(requirement.id)) fail(`${relative}: missing removal link ${requirement.id}`);
  }
  return trace;
}

export function checkSdd({ root = process.cwd(), base } = {}) {
  root = fs.realpathSync(root);
  const oid = base === undefined ? undefined : resolveBase(root, base);
  const changes = oid === undefined ? undefined : changedPaths(root, oid);
  const baselineFiles = oid === undefined ? [] : baseFiles(root, oid);
  const history = oid === undefined ? undefined : { oid, files: new Set(baselineFiles) };
  const scope = loadScope(root);
  if (baselineFiles.includes(SCOPE_FILE)) {
    for (const item of loadScope(root, baseRead(root, oid, SCOPE_FILE))) scope.add(item);
  }
  const baseline = emptySpecs();
  for (const file of baselineFiles.filter(file => /^openspec\/specs\/[^/]+\/spec\.md$/.test(file))) {
    mergeSpecs(baseline, parseSpec(root, file, false, baseRead(root, oid, file)), file);
  }
  const currentFiles = files(root, 'openspec/specs').filter(file => file.endsWith('/spec.md'));
  if (!currentFiles.length && !baseline.requirements.size) fail('Missing current specifications');
  const current = emptySpecs();
  for (const file of currentFiles) mergeSpecs(current, parseSpec(root, file), file);
  const coverage = new Map();
  const associations = new Map();
  const archivePaths = new Set();
  const activeScenarios = new Set();
  let active = 0;
  let archives = 0;
  const changeRoot = safePath(root, 'openspec/changes', false);
  if (!fs.existsSync(changeRoot)) fail('Missing changes directory');
  const folders = fs.readdirSync(changeRoot, { withFileTypes: true });
  const validateChange = (folder, archived) => {
    for (const artifact of ['proposal.md', 'design.md', 'tasks.md']) {
      if (!read(root, `${folder}/${artifact}`).trim()) fail(`${folder}: empty ${artifact}`);
    }
    const specs = emptySpecs();
    const deltas = files(root, `${folder}/specs`).filter(file => file.endsWith('/spec.md'));
    if (!deltas.length) fail(`${folder}: missing change deltas`);
    for (const file of deltas) mergeSpecs(specs, parseSpec(root, file, true), file);
    if (!archived) for (const id of specs.scenarios.keys()) {
      if (activeScenarios.has(id)) fail(`${folder}: scenario appears in multiple active changes: ${id}`);
      activeScenarios.add(id);
    }
    const trace = validateTrace(root, `${folder}/trace.json`, specs, archived, coverage, changes, false, history);
    let accepted = false;
    if (archived && oid) {
      const name = trace.change;
      const original = `openspec/changes/${name}`;
      const originalFiles = baselineFiles.filter(file => file.startsWith(`${original}/`));
      if (originalFiles.length && !fs.existsSync(path.join(root, original))) {
        const tasks = read(root, `${folder}/tasks.md`);
        if (!/- \[[xX]\]/.test(tasks) || /- \[ \]/.test(tasks)) fail(`${folder}: archive transition requires completed tasks`);
        for (const old of originalFiles) {
          const target = folder + old.slice(original.length);
          const before = baseRead(root, oid, old);
          const after = read(root, target);
          if (old.endsWith('/tasks.md')) {
            if (before.replace(/\[[ xX]\]/g, '[]') !== after.replace(/\[[ xX]\]/g, '[]')) fail(`${folder}: archive tasks changed beyond completion`);
          } else if (before !== after) fail(`${folder}: archive content differs from base-active change: ${old}`);
          archivePaths.add(old);
          archivePaths.add(target);
        }
        const afterFiles = files(root, folder);
        if (afterFiles.length !== originalFiles.length) fail(`${folder}: archive transition added unexpected artifacts`);
        accepted = true;
      } else if (!originalFiles.length && !fs.existsSync(path.join(root, original)) &&
          !baselineFiles.some(file => file.startsWith(`${folder}/`) ||
            file.startsWith('openspec/changes/archive/') && file.split('/')[3].replace(/^\d{4}-\d{2}-\d{2}-/, '') === name)) {
        const tasks = read(root, `${folder}/tasks.md`);
        if (!/- \[[xX]\]/.test(tasks) || /- \[ \]/.test(tasks)) fail(`${folder}: new archive requires completed tasks`);
        const artifacts = files(root, folder);
        if (!artifacts.every(file => changes.get(file) === 'present')) fail(`${folder}: new archive is not fully introduced by this diff`);
        validateTrace(root, `${folder}/trace.json`, specs, true, coverage, changes, true);
        for (const file of artifacts) archivePaths.add(file);
        accepted = true;
      }
    }
    if (!archived || accepted) {
      for (const [id, definition] of specs.requirements) {
        if (associations.has(id)) fail(`${folder}: requirement appears in multiple relevant changes: ${id}`);
        associations.set(id, { ...definition, accepted });
        if (accepted) {
          const now = current.requirements.get(id);
          if (definition.section === 'REMOVED' ? now !== undefined : !now || now.content !== definition.content || now.capability !== definition.capability) {
            fail(`${folder}: accepted reconciliation mismatch for ${id}`);
          }
          archivePaths.add(`openspec/specs/${definition.capability}/spec.md`);
        }
      }
    }
  };
  for (const entry of folders) {
    if (entry.isSymbolicLink()) fail(`Symlink in changes: ${entry.name}`);
    if (!entry.isDirectory()) continue;
    if (entry.name === 'archive') {
      const archiveRoot = path.join(changeRoot, 'archive');
      for (const archived of fs.readdirSync(archiveRoot, { withFileTypes: true })) {
        if (!archived.isDirectory() || !/^\d{4}-\d{2}-\d{2}-.+/.test(archived.name)) fail(`Invalid archive entry: ${archived.name}`);
        validateChange(`openspec/changes/archive/${archived.name}`, true);
        archives++;
      }
    } else {
      validateChange(`openspec/changes/${entry.name}`, false);
      active++;
    }
  }
  if (changes) {
    const ids = new Set([...baseline.requirements.keys(), ...current.requirements.keys()]);
    for (const id of ids) {
      const before = baseline.requirements.get(id);
      const after = current.requirements.get(id);
      if (before?.content === after?.content && before?.capability === after?.capability) continue;
      const delta = associations.get(id);
      if (!delta) fail(`Current requirement changed without associated delta: ${id}`);
      if (!after) {
        if (delta.section !== 'REMOVED') fail(`Current removal requires REMOVED delta: ${id}`);
      } else if (delta.section === 'REMOVED' || delta.content !== after.content || delta.capability !== after.capability) {
        fail(`Current requirement does not match associated delta: ${id}`);
      } else if (before && delta.section !== 'MODIFIED') {
        fail(`Existing current requirement requires MODIFIED delta: ${id}`);
      } else if (!before && delta.section !== 'ADDED') {
        fail(`New current requirement requires ADDED delta: ${id}`);
      }
    }
  }
  let scoped = 0;
  if (changes) for (const [file, status] of changes) {
    if (![...scope].some(item => item.endsWith('/') ? file.startsWith(item) : file === item)) continue;
    safePath(root, file, status !== 'deleted');
    scoped++;
    if (coverage.get(file) !== status && !archivePaths.has(file)) fail(`Unmapped scoped ${status} path: ${file}`);
  }
  return { currentSpecs: currentFiles.length, activeChanges: active, archives, scopedPaths: changes ? scoped : null };
}

function main() {
  const options = {};
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i++) {
    const option = args[i];
    if (!['--base', '--root'].includes(option) || !text(args[i + 1]) || args[i + 1].startsWith('--')) fail(`Usage: node scripts/check-sdd.mjs [--base <git-ref>] [--root <fixture>] (invalid ${option})`);
    const key = option.slice(2);
    if (Object.hasOwn(options, key)) fail(`Duplicate option: ${option}`);
    options[key] = args[++i];
  }
  const result = checkSdd(options);
  console.log(`SDD link validation passed: ${result.currentSpecs} current specs, ${result.activeChanges} active changes, ${result.archives} archives.`);
  console.log(result.scopedPaths === null ? 'Diff coverage NOT checked (no --base supplied).' : `Diff coverage checked: ${result.scopedPaths} scoped paths.`);
  console.log('Link completeness only; tests and manual verification were NOT executed.');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) { console.error(`SDD validation failed: ${error.message}`); process.exitCode = 1; }
}
