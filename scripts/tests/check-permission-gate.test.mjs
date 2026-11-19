import test from 'node:test';
import assert from 'node:assert/strict';
import { auditTrigger, auditWaitTimeout, defBlocks, readOnlyWhitelist } from '../check-permission-gate.mjs';

test('the removed over-broad rule is rejected: name alone and read-only whitelist', () => {
  const overbroad = [
    'val readOnlySingleCommand =',
    '  lower.matches("(?:cat|less|more|head|tail|grep|rg|test|stat|readlink)\\\\s+[^;&|<>]*")',
    'lower.contains("agents.md") && !readOnlySingleCommand',
  ].join('\n');
  const problems = auditTrigger(overbroad, 'object CommandRisk:\n  def isRisky(c: String): Boolean = false\n');
  assert.ok(problems.some(p => p.includes('read-only command whitelist')), problems.join('; '));
  assert.ok(problems.some(p => p.includes('never consults the CommandRisk classifier')), problems.join('; '));
});

test('a risk classifier keyed on a document file name is rejected', () => {
  const problems = auditTrigger(
    'object Gate:\n  private def commandTargetsInstructionFile(command: String): Boolean =\n    CommandRisk.isRisky(command) && command.contains("notes.md")\n',
    'object CommandRisk:\n  private val Doc = """\\\\.md$""".r\n'
  );
  assert.ok(problems.some(p => p.includes('document file-name literal')), problems.join('; '));
});

test('a target-only verdict without the risk classifier is rejected', () => {
  const problems = auditTrigger(
    'object Gate:\n  private def commandTargetsInstructionFile(command: String): Boolean =\n    command.contains("agents.md")\n',
    'object CommandRisk:\n  def isRisky(c: String): Boolean = false\n'
  );
  assert.ok(problems.some(p => p.includes('ignores the risk classifier')), problems.join('; '));
});

test('target AND risk classifier passes, and prose lists are not a whitelist', () => {
  const problems = auditTrigger(
    'object Gate:\n  // cat/grep/less are mentioned in prose only\n  private def commandTargetsInstructionFile(command: String): Boolean =\n    CommandRisk.isRisky(command) && command.contains("agents.md")\n',
    'object CommandRisk:\n  private val Write = """(?s)(?:^|[\\\\s;|&(/])(?:tee|cp)(?=$|[\\\\s;|&)])""".r\n  def isRisky(c: String): Boolean = false\n'
  );
  assert.deepEqual(problems, []);
  assert.deepEqual(readOnlyWhitelist('  // cat/grep/less are mentioned in prose only'), []);
  assert.equal(defBlocks('  def a(x: Int): Boolean =\n    true\n  def b(y: Int): Boolean =\n    false\n').length, 2);
});

test('permission waits without a timeout are rejected, with one they pass', () => {
  const unbounded = [
    '  private val TimeoutEnv = "NEBFLOW_PERMISSION_WAIT_TIMEOUT_SEC"',
    '  def permissionWaitTimeoutMs: Long = 600_000L',
    '  def awaitPermissionDecisionBounded(d: Int, timeoutMs: Long): Int =',
    '    d.get',
    '  def awaitMcpPermissionDecisionBounded(d: Int, timeoutMs: Long): Int =',
    '    d.get',
  ].join('\n');
  const problems = auditWaitTimeout(unbounded);
  assert.equal(problems.filter(p => p.includes('without a timeout')).length, 2, problems.join('; '));

  const bounded = unbounded.replaceAll('    d.get', '    d.get.timeoutTo(120L, 0)');
  assert.deepEqual(auditWaitTimeout(bounded), []);
  assert.ok(auditWaitTimeout(bounded.replace('permissionWaitTimeoutMs: Long = 600_000L', 'other: Long = 1L')).length);
  assert.ok(auditWaitTimeout(bounded.replace('"NEBFLOW_PERMISSION_WAIT_TIMEOUT_SEC"', '"other.key"')).length);
});
