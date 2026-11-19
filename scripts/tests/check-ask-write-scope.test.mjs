import test from 'node:test';
import assert from 'node:assert/strict';
import {
  audit,
  auditBoundary,
  auditEditGate,
  auditHandoff,
  auditToolFace,
  defBlocks,
  stripComments,
} from '../check-ask-write-scope.mjs';

// 本文件是 check-ask-write-scope.mjs 的**检测力**测试（作者验收条目 4：注入红 /
// 还原绿）。范式与 scripts/tests/check-permission-gate.test.mjs 相同：闸的判据是纯
// 函数（源码文本 → 违规列表），所以「把绕过写回去会不会红」可以在测试里逐条钉住，
// 不必等到有人在真树上手改一遍。

const EDITS = 'src/main/scala/nebflow/core/ask/AskEditReviews.scala';
const BOUNDARY = 'src/main/scala/nebflow/core/ask/AskPathBoundary.scala';

const goodCapture = [
  '  def capture(sid: String, binding: Binding, path: String, tool: String): Either[String, Change] =',
  '    val d = AskPathBoundary.decideSync(binding.file, path)',
  '    AskWriteAudit.record(sid, tool, path, d.canonical, d.allowed, d.reason)',
  '    if !d.allowed then Left(AskPathBoundary.refuseMsg(d.reason, d.canonical)) else Right(d)',
].join('\n');

const goodBoundary = [
  'object AskPathBoundary {',
  '  val ScopeRule = "本会话只能编辑它对应的那份文档"',
  '  val HandoffHint = "复杂任务转交主会话"',
  '  def decideSync(bound: Path, requested: String): Decision =',
  '    val real = Path.of(requested).toRealPath()',
  '    val same = Files.isSameFile(bound, real)',
  '    Decision(same, real.toString, if same then "" else ScopeRule)',
].join('\n');

const goodSessions = '  val AllowedTools: Set[String] = Set("Read", "Grep", "ask_edit", "ask_write")\n';
const goodBash = [
  '  def runAskBash(session: S, command: String): R =',
  '    AskBashGuard.validate(command) match',
  '      case Left(why) => refuse(why)',
  '      case Right(ok) => execute(ok)',
].join('\n');
const goodExec = '    if askSession then return nebflow.core.ask.AskSessions.AllowedTools\n';

const goodAskTools = [
  'object SendMessageToMainChatTool extends NebflowTool {',
  '  def inputSchema: Json = Json.obj(',
  '    "required" -> Json.arr("task"),',
  '    "properties" -> Json.obj("paths" -> paths, "reason" -> reason, "expected" -> expected)',
  '  )',
  '  def run(): R = AskTools.pushHandoffFrame(AskHandoffWire.ackFrame(h))',
].join('\n');

const goodAssembler = [
  'object AskContextAssembler {',
  '  def toolListSection(): String = ToolRegistry.TOOL_MAP.values.map(t => s"- ${t.name}").mkString',
  '  val constraints = "1. 本会话只能编辑它对应的那份文档；2. 复杂任务转交主会话"',
  // 能力边界批（ask-prompt-scope 2026-11-19）：硬约束 2 的展开要件——金样须逐件在场。
  '  val boundary = "**能力边界**：简单 = 四条同时成立（单文件 / 改动局部 / 无跨模块影响 / 一句话说清）；转交必须自包含：目标 = 要达成什么 / 约束 / 验收 / 已掌握的事实；例外：作者当场明确要求"',
].join('\n');

const clean = (over = {}) => audit({
  [BOUNDARY]: goodBoundary,
  [EDITS]: goodCapture,
  'src/main/scala/nebflow/core/ask/AskSessions.scala': goodSessions,
  'src/main/scala/nebflow/core/ask/AskBashGuard.scala': goodBash,
  'src/main/scala/nebflow/agent/AgentSessionExecution.scala': goodExec,
  'src/main/scala/nebflow/core/tools/AskTools.scala': goodAskTools,
  'src/main/scala/nebflow/core/ask/AskContextAssembler.scala': goodAssembler,
  ...over,
});

test('the written-out reference verdict passes every criterion (the green baseline)', () => {
  assert.deepEqual(clean(), []);
});

test('injection: restoring the string-equality verdict turns the gate red', () => {
  const injected = goodCapture.replace(
    '    val d = AskPathBoundary.decideSync(binding.file, path)\n',
    '    val normalized = path.trim\n    if normalized != target then return Left(AskPathBoundary.refuseMsg("out of scope", path))\n',
  );
  const problems = auditEditGate(injected);
  assert.ok(problems.some((p) => p.includes('string equality against the bound file')), problems.join('; '));
  assert.ok(problems.some((p) => p.includes('second verdict source')), problems.join('; '));
  // 注入必须真的红：闸的另两条要件（审计在场、单点路由）在注入后仍须报缺失。
  assert.ok(problems.some((p) => p.includes('AskPathBoundary.decide') || p.includes('AskWriteAudit.record')));
});

test('injection: a blanket `..` / startsWith("/") check turns the gate red', () => {
  const traversal = goodCapture.replace(
    '    val d = AskPathBoundary.decideSync(binding.file, path)\n',
    '    if path.split(\'/\').contains("..") || path.startsWith("/") then return Left("no")\n',
  );
  const problems = auditEditGate(traversal);
  assert.ok(problems.some((p) => p.includes('blanket `..` string check')), problems.join('; '));
  assert.ok(problems.some((p) => p.includes('`startsWith("/")`')), problems.join('; '));
});

test('injection: a lone `..` literal in the gate is a bypass surface and is refused', () => {
  const problems = auditEditGate(goodCapture.replace('    val d = ', '    val ok = !path.contains("..")\n    val d = '));
  assert.ok(problems.some((p) => p.includes('`..` literal')), problems.join('; '));
});

test('injection: dropping the audit call or the single-point routing turns the gate red', () => {
  const noAudit = auditEditGate(goodCapture.replace(/^\s*AskWriteAudit\.record\(.*\)\n/m, ''));
  assert.ok(noAudit.some((p) => p.includes('must audit every verdict')), noAudit.join('; '));
  const noRoute = auditEditGate(goodCapture.replace('AskPathBoundary.decideSync', 'legacyTargetCheck'));
  assert.ok(noRoute.some((p) => p.includes('single decision point')), noRoute.join('; '));
});

test('the verdict must normalise by identity, not by spelling', () => {
  assert.deepEqual(auditBoundary(goodBoundary), []);
  const stringy = goodBoundary.replace('val same = Files.isSameFile(bound, real)', 'val same = bound.toString == requested');
  const problems = auditBoundary(stringy);
  assert.ok(problems.some((p) => p.includes('compare file identity')), problems.join('; '));
  assert.ok(auditBoundary(goodBoundary.replace('val real = Path.of(requested).toRealPath()', 'val real = Path.of(requested)'))
    .some((p) => p.includes('resolve the real path')));
  assert.ok(auditBoundary(null).some((p) => p.includes('must exist')));
});

test('the ask face must not carry a direct write tool', () => {
  const face = (sessions, bash = goodBash, exec = goodExec) => auditToolFace({ sessions, bash, exec });
  assert.deepEqual(face(goodSessions), []);
  assert.ok(face(goodSessions.replace('"ask_edit"', '"Write"')).some((p) => p.includes('must not carry the direct write tool')));
  assert.ok(face(goodSessions.replace('"Read"', '"Edit"')).some((p) => p.includes('"Edit"')));
  assert.ok(face(undefined).some((p) => p.includes('single point must exist')));
});

test('the shell leg must pass through the query-only validator', () => {
  const bare = goodBash.replace('    AskBashGuard.validate(command) match\n      case Left(why) => refuse(why)\n', '');
  const problems = auditToolFace({ sessions: goodSessions, bash: bare, exec: goodExec });
  assert.ok(problems.some((p) => p.includes('AskBashGuard')), problems.join('; '));
  assert.ok(auditToolFace({ sessions: goodSessions, bash: goodBash, exec: 'val x = 1\n' })
    .some((p) => p.includes('early-return the closed ask face')));
});

test('the handoff must be structured, required and visible', () => {
  assert.deepEqual(auditHandoff({ askTools: goodAskTools, assembler: goodAssembler }), []);
  assert.ok(auditHandoff({ askTools: goodAskTools.replace('Json.arr("task")', 'Json.arr("question")'), assembler: goodAssembler })
    .some((p) => p.includes('REQUIRE the structured `task` field')));
  assert.ok(auditHandoff({ askTools: goodAskTools.replace('"expected" -> expected, ', '').replace('"expected" -> expected', ''), assembler: goodAssembler })
    .some((p) => p.includes('declare "expected"')));
  assert.ok(auditHandoff({ askTools: goodAskTools.replace(' def run(): R = AskTools.pushHandoffFrame(AskHandoffWire.ackFrame(h))', ''), assembler: goodAssembler })
    .some((p) => p.includes('askHandoffAck')));
});

test('the prompt must derive its tool list and state both hard constraints', () => {
  const handoff = (assembler) => auditHandoff({ askTools: goodAskTools, assembler });
  assert.ok(handoff(goodAssembler.replace('ToolRegistry.TOOL_MAP.values', 'List("Read", "ask_edit")'))
    .some((p) => p.includes('ToolRegistry.TOOL_MAP')));
  assert.ok(handoff(goodAssembler.replace('  def toolListSection(): String = ToolRegistry.TOOL_MAP.values.map(t => s"- ${t.name}").mkString\n', '  val toolList = "- Read: read\\n- ask_edit: edit"\n'))
    .some((p) => p.includes('toolListSection')));
  assert.ok(handoff(goodAssembler.replace('1. 本会话只能编辑它对应的那份文档；', ''))
    .some((p) => p.includes('constraint 1')));
  assert.ok(handoff(goodAssembler.replace('2. 复杂任务转交主会话', ''))
    .some((p) => p.includes('constraint 2')));
});

test('the capability boundary must state the simple-edit test, the default handoff and the author exception', () => {
  const handoff = (assembler) => auditHandoff({ askTools: goodAskTools, assembler });
  // 正面：金样含全部要件 ⇒ 零违规。
  assert.deepEqual(handoff(goodAssembler), []);
  // 负控（删掉该段 ⇒ 红）：抽掉整段能力边界后，逐条要件都要报红。
  const noBoundary = goodAssembler.replace(/^\s*val boundary = .*$\n?/m, '');
  assert.notEqual(noBoundary, goodAssembler, 'the fixture must actually lose the boundary line');
  const problems = handoff(noBoundary);
  for (const marker of [
    'capability-boundary section',
    '"simple edit" test',
    'no-cross-module',
    'self-contained',
    'on-the-spot explicit-request',
  ]) {
    assert.ok(problems.some((p) => p.includes(marker)), `${marker} must go red when the boundary is deleted: ${problems.join('; ')}`);
  }
});

test('a hand-written tool inventory in the prompt is refused as a second source of truth', () => {
  const handWritten = goodAssembler.replace(
    '  def toolListSection(): String = ToolRegistry.TOOL_MAP.values.map(t => s"- ${t.name}").mkString\n',
    '  val toolList = """\n- Read / Glob / Grep: read and search the current file\n- Bash: a query-only shell face\n- ask_edit / ask_write: edit THIS file only\n"""\n',
  );
  const problems = auditHandoff({ askTools: goodAskTools, assembler: handWritten });
  assert.ok(problems.some((p) => p.includes('hand-writes a tool inventory')), problems.join('; '));
  // 生成面的那行 `s"- $n: $capped"` 不是手抄清单（名字位是插值），不得误判。
  assert.deepEqual(auditHandoff({ askTools: goodAskTools, assembler: goodAssembler }), []);
});

test('comment stripping keeps prose from tripping the criteria', () => {
  const prose = [
    'object AskPathBoundary {',
    '  // 旧实现曾用 normalized != target 与 startsWith("/")，注释里的说明不该触发判据',
    '  val ScopeRule = "本会话只能编辑它对应的那份文档"',
    '  val HandoffHint = "复杂任务转交主会话"',
    '  def decideSync(b: Path, r: String): Decision =',
    '    val real = Path.of(r).toRealPath()',
    '    Decision(Files.isSameFile(b, real), real.toString, "")',
    '}',
  ].join('\n');
  assert.deepEqual(auditBoundary(prose), []);
  assert.ok(!stripComments(prose).includes('startsWith'));
  assert.equal(defBlocks(stripComments(prose)).length, 1);
});
