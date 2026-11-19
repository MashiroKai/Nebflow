import test from 'node:test';
import assert from 'node:assert/strict';
import { audit, MUTATIONS, codeOf } from '../check-todo-options.mjs';

// 待办选项闸自身的行为：干净形态零问题；七种缺陷注入各自转红（本测试保证闸不空转）。

const TAXONOMY = 'src/main/scala/nebflow/core/scheduler/TodoTaxonomy.scala';
const DECISION_MODEL = 'src/main/scala/nebflow/core/scheduler/TodoDecisionModel.scala';
const TOOL = 'src/main/scala/nebflow/core/tools/ScheduleTool.scala';
const WS = 'src/main/scala/nebflow/gateway/WsTasksWorkspaceHandlers.scala';

function fixtures() {
  return {
    [TAXONOMY]: [
      'object TodoTaxonomy:',
      '  def classify(kind: Option[String], source: Option[String]): TodoClass = TodoClass.ScheduledTask',
      '  def requiresOptions(c: TodoClass): Boolean = c == TodoClass.Todo',
      '  def validateCreate(k: Option[String], s: Option[String], d: Option[TodoDecision]): Either[Violation, Unit] =',
      '    if d.options.isEmpty then Left(v) else Right(())',
      '  object Codes:',
      '    val OptionsRequired = "TODO_OPTIONS_REQUIRED"',
    ].join('\n'),
    [DECISION_MODEL]: [
      'case class TodoDecision(question: String = "", options: List[DecisionOption] = Nil, multiSelect: Boolean = false)',
      'case class DecisionOption(id: String = "", title: String = "", detail: Option[String] = None, media: List[DecisionMedia] = Nil)',
      'case class DecisionMedia(kind: String = "", ref: String = "", refType: String = "", mime: Option[String] = None)',
      'object DecisionMedia:',
      '  val KindImage = "image"',
      '  val KindVideo = "video"',
      '  val Kinds: Set[String] = Set(KindImage, KindVideo)',
    ].join('\n'),
    [TOOL]: [
      'object ScheduleTool:',
      '  TodoTaxonomy.validateCreate(createKind, Some(TodoTaxonomy.SourceTool), decision) match',
      '    case Left(v) => IO.pure(Left(ToolError(v.message)))',
      '    case Right(_) => createTodoAction(taskStore, taskService, input, ctx, sessionId, normalized)',
      '  private def createTodoAction(a: A, b: B, c: C, d: D, e: E): IO[X] = ???',
    ].join('\n'),
    [WS]: [
      'object WsTasksWorkspaceHandlers:',
      '  val v = crDecisionViolation.get',
      '  wsSend(io.circe.Json.obj("code" -> v.code.asJson, "message" -> v.message.asJson))',
      '  val chk = TodoTaxonomy.validateCreate(Some("todo"), Some("user"), None)',
      '  val j = "todoClass" -> nebflow.core.scheduler.TodoClass.wire(c)',
    ].join('\n'),
  };
}

test('clean tree passes', () => {
  assert.deepEqual(audit(fixtures()), []);
});

test('every declared mutation turns the gate red', () => {
  for (const [name, mutate] of Object.entries(MUTATIONS)) {
    const files = fixtures();
    mutate(files);
    const problems = audit(files);
    assert.ok(problems.length > 0, `mutation ${name} did not trip the gate`);
  }
});

test('the two decisive pins: tool validates BEFORE create, rule is scoped to Todo', () => {
  const reordered = fixtures();
  const tool = reordered[TOOL];
  reordered[TOOL] = [
    'object ScheduleTool:',
    '  createTodoAction(taskStore, taskService, input, ctx, sessionId, normalized)',
    '  TodoTaxonomy.validateCreate(createKind, Some(TodoTaxonomy.SourceTool), decision) match',
    '    case Left(v) => IO.pure(Left(ToolError(v.message)))',
    '  private def createTodoAction(a: A, b: B, c: C, d: D, e: E): IO[X] = ???',
  ].join('\n');
  assert.ok(audit(reordered).some(p => p.includes('after') || p.includes('之后')), `reorder must trip: ${audit(reordered).join('; ')}`);
  assert.ok(tool.includes('createTodoAction(taskStore'));

  const widened = fixtures();
  widened[TAXONOMY] = widened[TAXONOMY].replace('c == TodoClass.Todo', 'true');
  assert.ok(audit(widened).some(p => p.includes('TodoClass.Todo')));
});

test('comments do not satisfy the gate (codeOf strips them)', () => {
  const files = fixtures();
  files[TAXONOMY] = '// val OptionsRequired = "TODO_OPTIONS_REQUIRED"\n' + files[TAXONOMY].replace('    val OptionsRequired = "TODO_OPTIONS_REQUIRED"', '');
  assert.ok(audit(files).some(p => p.includes('TODO_OPTIONS_REQUIRED')));
  assert.ok(!codeOf('// x\n val a = 1').includes('x'));
});
