#!/usr/bin/env node
// 待办选项闸（待办数据模型与命名规范批 2026-11-18）。
//
// 作者令：「……需要决策的是待办 然后每个待办必须要有选项」；17:52 追加
// 「……要能填充图片和视频 并且支持多张叠卡显示 …… 然后这个也可以给待办应用上」。
//
// 本闸是**静态腿**（源码级断言，零浏览器/零 JVM/零端口）：判据是
//   ① 写侧强制点存在且**先于**创建（Schedule 工具面 + WS 创建帧都过同一校验器）；
//   ② 「选项必备」只管待办面（TodoClass.Todo），提醒/定时任务/引擎镜像不受强制；
//   ③ 决策/选项/媒体三件在模型里成形，且媒体是**引用**（不是内嵌字节）；
//   ④ 三面判定单点存在，且列表投影带 `todoClass`（改名收敛的机器面）。
//
// 破坏 → 红：`--mutate=<name>` 在内存里模拟缺陷（见 MUTATIONS）；也可以在真源码上
// 做等价改动，本闸同样转红。还原 → 绿。
//
// 用法：
//   node scripts/check-todo-options.mjs
//   node scripts/check-todo-options.mjs --mutate=drop-validate-in-tool
//   node scripts/check-todo-options.mjs --json
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));

const TAXONOMY = 'src/main/scala/nebflow/core/scheduler/TodoTaxonomy.scala';
const DECISION_MODEL = 'src/main/scala/nebflow/core/scheduler/TodoDecisionModel.scala';
const TOOL = 'src/main/scala/nebflow/core/tools/ScheduleTool.scala';
const WS = 'src/main/scala/nebflow/gateway/WsTasksWorkspaceHandlers.scala';

const FILES = [TAXONOMY, DECISION_MODEL, TOOL, WS];

/** 源码级缺陷注入（验红腿）：在内存里改文本，不碰磁盘。 */
export const MUTATIONS = {
  // ① 写侧强制点被拆：工具面 create 不再校验 ⇒ 无选项的待办又能落库
  'drop-validate-in-tool': files => {
    files[TOOL] = files[TOOL].replace(/TodoTaxonomy\s*\.\s*validateCreate\(/g, 'noopValidate(');
  },
  // ② 错误码被改名 ⇒ 前端/调用方按码分支会漏
  'rename-options-code': files => {
    files[TAXONOMY] = files[TAXONOMY].replace('val OptionsRequired = "TODO_OPTIONS_REQUIRED"', 'val OptionsRequired = "OOPS"');
  },
  // ③ 强制面被放宽到所有类（提醒也被要求选项 ⇒ 作者自建被误拒）
  'widen-rule-to-all': files => {
    files[TAXONOMY] = files[TAXONOMY].replace(
      'def requiresOptions(c: TodoClass): Boolean = c == TodoClass.Todo',
      'def requiresOptions(c: TodoClass): Boolean = true'
    );
  },
  // ④ 媒体从「多张」退化回单张
  'single-media-only': files => {
    files[DECISION_MODEL] = files[DECISION_MODEL].replace('media: List[DecisionMedia] = Nil', 'media: Option[DecisionMedia] = None');
  },
  // ⑤ 媒体引用面被改成内嵌字节（第二条寿命假设的入口）
  'inline-media-bytes': files => {
    files[DECISION_MODEL] = files[DECISION_MODEL].replace(
      'refType: String = "",',
      'refType: String = "",\n  bodyBase64: String = "",'
    );
  },
  // ⑥ WS 创建帧不再回传机器可读错误码
  'drop-ws-error-code': files => {
    files[WS] = files[WS].replace('"code" -> v.code.asJson,', '');
  },
  // ⑦ 列表投影不再下发 todoClass（三面判定回到「各入口各判各的」）
  'drop-todo-class-projection': files => {
    files[WS] = files[WS].replace(/"todoClass" -> nebflow\.core\.scheduler\.TodoClass/g, '"notTodoClass" -> nebflow.core.scheduler.TodoClass');
  },
};

/** 去掉块注释与整行注释——判据只认代码。 */
export function codeOf(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .filter(line => !/^\s*(\/\/|\*)/.test(line))
    .join('\n');
}

export function audit(files) {
  const problems = [];
  const has = (rel, re, label) => {
    if (!re.test(codeOf(files[rel] ?? ''))) problems.push(`${rel}: ${label}`);
  };

  // ① 写侧强制点：校验器本体 + 两条接线，且必须**先于**创建
  has(TAXONOMY, /def validateCreate\(/, '缺少唯一写侧强制点 validateCreate');
  has(TAXONOMY, /val OptionsRequired = "TODO_OPTIONS_REQUIRED"/, '缺少可机读错误码 TODO_OPTIONS_REQUIRED');
  has(TAXONOMY, /def requiresOptions\(c: TodoClass\): Boolean = c == TodoClass\.Todo/, '「选项必备」必须且只能绑定 TodoClass.Todo（提醒/定时任务/镜像不受强制）');
  has(TAXONOMY, /def classify\(kind: Option\[String\], source: Option\[String\]\): TodoClass/, '缺少三面判定单点 classify');

  const toolCode = codeOf(files[TOOL] ?? '');
  const toolValidateAt = toolCode.search(/TodoTaxonomy\s*\.\s*validateCreate\(/);
  const toolCreateAt = toolCode.indexOf('createTodoAction(taskStore');
  if (toolValidateAt < 0) problems.push(`${TOOL}: 工具面 create 未过 TodoTaxonomy.validateCreate（无选项的待办会落库）`);
  if (toolValidateAt >= 0 && toolCreateAt >= 0 && toolValidateAt > toolCreateAt) {
    problems.push(`${TOOL}: validateCreate 出现在 create 调用之后 —— 拒绝面必须在创建之前`);
  }
  if (!/def createTodoAction\(/.test(toolCode)) problems.push(`${TOOL}: 待办创建路径 createTodoAction 缺失（本闸锚点失效）`);

  const wsCode = codeOf(files[WS] ?? '');
  if (!/TodoTaxonomy\s*\.\s*validateCreate\(/.test(wsCode)) problems.push(`${WS}: WS 创建帧未过决策校验器`);
  if (!/"code" -> v\.code\.asJson/.test(wsCode)) problems.push(`${WS}: WS 拒绝帧缺机器可读 code（调用方无法按码分支）`);
  if (!/"todoClass" -> nebflow\.core\.scheduler\.TodoClass/.test(wsCode)) {
    problems.push(`${WS}: 列表/创建投影缺 todoClass（三面判定必须由服务端下发）`);
  }

  // ③ 决策/选项/媒体三件成形；媒体是引用不是字节
  const dm = codeOf(files[DECISION_MODEL] ?? '');
  if (!/case class TodoDecision\(/.test(dm)) problems.push(`${DECISION_MODEL}: TodoDecision 缺失`);
  if (!/options: List\[DecisionOption\] = Nil/.test(dm)) problems.push(`${DECISION_MODEL}: decision.options 必须是 DecisionOption 列表`);
  if (!/case class DecisionOption\(/.test(dm)) problems.push(`${DECISION_MODEL}: DecisionOption 缺失`);
  if (!/media: List\[DecisionMedia\] = Nil/.test(dm)) problems.push(`${DECISION_MODEL}: 一个选项必须能挂多张媒体（media: List[DecisionMedia]）`);
  if (!/case class DecisionMedia\(/.test(dm)) problems.push(`${DECISION_MODEL}: DecisionMedia 缺失`);
  for (const [re, label] of [
    [/kind: String = ""/, 'DecisionMedia.kind 缺失'],
    [/ref: String = ""/, 'DecisionMedia.ref 缺失（媒体是引用）'],
    [/refType: String = ""/, 'DecisionMedia.refType 缺失'],
  ]) if (!re.test(dm)) problems.push(`${DECISION_MODEL}: ${label}`);
  if (/bodyBase64|bytes: Array\[Byte\]|inlineBytes/.test(dm)) {
    problems.push(`${DECISION_MODEL}: 媒体必须是引用 —— 不得出现内嵌字节字段`);
  }
  if (!/val Kinds: Set\[String\] = Set\(KindImage, KindVideo\)/.test(dm)) problems.push(`${DECISION_MODEL}: 媒体 kind 值域单源缺失`);

  // 待办面的拒绝要点：选项 ≥1
  has(TAXONOMY, /if d\.options\.isEmpty then/, '缺少「选项 ≥1」判定');

  return problems;
}

function parseArgs(argv) {
  let mutate = null;
  let json = false;
  for (const arg of argv) {
    if (arg === '--json') json = true;
    else if (arg.startsWith('--mutate=')) mutate = arg.slice('--mutate='.length);
    else throw new Error(`unknown argument: ${arg}`);
  }
  return { mutate, json };
}

function main() {
  const { mutate, json } = parseArgs(process.argv.slice(2));
  if (mutate && !MUTATIONS[mutate]) {
    console.error(`unknown mutation: ${mutate}; known: ${Object.keys(MUTATIONS).join(', ')}`);
    process.exit(2);
  }
  const files = Object.fromEntries(FILES.map(rel => [rel, readFileSync(resolve(ROOT, rel), 'utf8')]));
  if (mutate) MUTATIONS[mutate](files);
  const problems = audit(files);
  if (json) console.log(JSON.stringify({ ok: problems.length === 0, mutate, problems }, null, 2));
  if (problems.length) {
    console.error(problems.map(p => `FAIL ${p}`).join('\n'));
    process.exit(1);
  }
  console.log(
    `todo-options gate passed${mutate ? ` (mutation: ${mutate})` : ''}: ` +
      'every todo create path is gated by TodoTaxonomy.validateCreate (options >= 1); ' +
      'options carry reference-only media (0..N per option); class projection is server-side.'
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
