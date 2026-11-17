import test from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('../..', import.meta.url));
const scala = 'src/main/scala/nebflow/';
const web = 'src/main/resources/web/js/';
const scripts = ['check-freeze-state.mjs', 'check-freeze-silence.mjs', 'check-prompt-single-source.mjs'];

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'stand-stale-guards-'));
  cpSync(join(root, 'src/main/scala'), join(dir, 'src/main/scala'), { recursive: true });
  for (const file of [
    ...scripts.map(name => `scripts/${name}`),
    ...['freezeState.js', 'main.js', 'state.js', 'main/bgAgents.js', 'managePanel.js', 'ws.js', 'locales/en.js', 'locales/zh-CN.js'].map(name => web + name),
    'scripts/check-freeze-consistency.mjs',
  ]) {
    mkdirSync(dirname(join(dir, file)), { recursive: true });
    cpSync(join(root, file), join(dir, file));
  }
  return dir;
}

function run(dir, script) {
  const result = spawnSync(process.execPath, [join(dir, 'scripts', script)], { encoding: 'utf8', timeout: 10000 });
  assert.ifError(result.error);
  assert.equal(result.signal, null);
  return { status: result.status, output: result.stdout + result.stderr };
}

function mutate(dir, script, file, before, after) {
  const path = join(dir, file);
  const source = readFileSync(path, 'utf8');
  assert.ok(source.includes(before), `Missing mutation anchor: ${before}`);
  writeFileSync(path, source.replace(before, after));
  try {
    const result = run(dir, script);
    assert.equal(result.status, 1, `Mutation must fail: ${before}\n${result.output}`);
  } finally {
    writeFileSync(path, source);
  }
}

test('freeze-state preserves manual Mail admission and explicit wake notification', () => {
  const dir = fixture();
  const script = scripts[0];
  try {
    const baseline = run(dir, script);
    assert.equal(baseline.status, 0, baseline.output);
    for (const [file, before, after] of [
      ['core/tools/MailTool.scala', 'if nebflow.core.delegate.SessionResumption.requiresManual(', 'if bypassManual('],
      ['core/tools/MailTool.scala', 'queueRecoverySupplement(id, message, blocks, ctx)', 'deliverToDelegateAdmitted(id, message, blocks, ctx, system, isLegacyForm)'],
      ['core/tools/MailTool.scala', '.mailOnce(id, event, "mail-supplement", identity, producer = "mail", deduplicateReceipts = true)', '.mailOnce(id, event, "mail-supplement", identity, producer = "mail", deduplicateReceipts = false)'],
      ['core/delegate/SessionResumption.scala', 'r.restartInterruptedAt.nonEmpty', 'false'],
      ['core/delegate/SessionResumption.scala', 'r.frozenSince.nonEmpty || r.frozenReason.nonEmpty', 'r.frozenSince.nonEmpty && r.frozenReason.nonEmpty'],
      ['agent/DelegateWakeLeg.scala', '.onWoken(resources, delegateId, via, wsSend)', '.onWoken(resources, delegateId, via, None)'],
      ['core/delegate/DelegateFreeze.scala', '"type" -> "delegateWoken".asJson', '"type" -> "wrongFrame".asJson'],
    ]) mutate(dir, script, scala + file, before, after);
    mutate(dir, script, web + 'main.js', "onMessage('delegateWoken'", "onMessage('wrongFrame'");
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('freeze-silence retains scoped quiet delivery, handoff identity, and zero provider alerts', () => {
  const dir = fixture();
  const script = scripts[1];
  try {
    const baseline = run(dir, script);
    assert.equal(baseline.status, 0, baseline.output);
    for (const [file, before, after] of [
      ['agent/BackoffSupervisor.scala', '(if quiet then', '(if quiet then\n                   MailboxProducer.deliver(resources) *>'],
      ['agent/BackoffSupervisor.scala', 'val quiet = nebflow.shared.Defaults.DelegateFreezeSilence', 'val quiet = false'],
      ['agent/DelegateWakeLeg.scala', 'live.ref ! nebflow.core.ResumeHandoff.userInput(', 'live.ref ! AgentCommand.UserInput('],
      ['agent/DelegateWakeLeg.scala', 'clientMessageId = Some(s"frozen-wake:', 'clientMessageId = Some(s"changed-wake:'],
      ['core/ResumeHandoff.scala', 'clientMessageId = clientMessageId,', 'clientMessageId = None,'],
      ['core/delegate/FreezeNotice.scala', '.filter(i => i.tier == Tier.Escalate || familyOfItem(i) == DeterministicFamily)', '.filter(i => true || i.tier == Tier.Escalate || familyOfItem(i) == DeterministicFamily)'],
      ['core/delegate/FreezeNotice.scala', '.groupBy(_.parentSessionId)', '.groupBy(familyOfItem)'],
    ]) mutate(dir, script, scala + file, before, after);
    mutate(dir, script, scala + 'core/delegate/FreezeNotice.scala',
      '  end planAlerts', '      ++ providerAlerts(d)\n  end planAlerts');
    mutate(dir, script, scala + 'agent/FreezeAlertSweep.scala',
      'DelegateWakeLeg\n                  .wake(', 'AgentCommand.RestartAgent\n                  .wake(');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('prompt source keeps exact live counts and rejects classifier resurrection without changing bodies', () => {
  const dir = fixture();
  const script = scripts[2];
  const catalog = scala + 'prompts/PromptCatalog.scala';
  try {
    const baseline = run(dir, script);
    assert.equal(baseline.status, 0, baseline.output);
    assert.match(baseline.output, /18 rules/);
    for (const literal of [
      'You are {{agent_display_name}}, the Stand orchestrator',
      'You are a task node running inside Stand',
      'You are a Stand delegate — a background executor spawned by',
      'Knowledge-graph landing discipline (KG audit ①, 2026-10-28; location ruling 2026-11-03)',
    ]) {
      mutate(dir, script, catalog, literal, 'missing prompt sentinel');
      mutate(dir, script, catalog, literal, `${literal} ${literal}`);
    }
    const duplicate = join(dir, scala, 'DuplicatePrompt.scala');
    writeFileSync(duplicate, 'val duplicate = "You are a task node running inside Stand"');
    assert.equal(run(dir, script).status, 1);
    rmSync(duplicate);
    for (const literal of [
      'You are {{agent_display_name}}, the Nebflow orchestrator',
      'You are a task node running inside Nebflow',
      'You are a Nebflow delegate — a background executor spawned by',
      'Knowledge-graph landing discipline (KG audit ①, 2026-10-28)',
    ]) {
      writeFileSync(duplicate, `val duplicate = ${JSON.stringify(literal)}`);
      assert.equal(run(dir, script).status, 1, 'Old prompt copies must fail even with a valid catalog');
      rmSync(duplicate);
    }
    mutate(dir, script, catalog, 'end PromptCatalog',
      'val ModeStatePreamble = "returned"\nend PromptCatalog');
    mutate(dir, script, catalog, 'end PromptCatalog',
      'val old = "You are auditing task dispatch in an agent-orchestration platform. Below is one dispatched task brief,"\nend PromptCatalog');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
