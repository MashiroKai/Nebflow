#!/usr/bin/env node
// check-ask-write-scope.mjs — Canvas Ask 写面收窄闸（ask-scope 批 2026-11-18）
//
// 作者令：「约束一下 canvas 的 ask 它只有它所对应的文档的编辑能力，其他复杂任务要
// 转交给主会话，同时也要在引擎上限制它编辑其他文件」「限制落在权限判定点（工具面/
// 授权判定），不是 prompt 纪律；越权尝试要有审计留痕」。
//
// 为什么是静态闸：这条边界的三条要件都住在源码里，而它们的**失效形态**恰恰是
// 「下一个改动者顺手把字符串比较写回来 / 把 `..` 白名单放回来 / 把审计行删掉」——
// 不会有任何测试变红。本闸把要件变成机器判据：
//
//   A 判决单点唯一      AskPathBoundary 在场 ∧ 判决用身份归一（realpath + isSameFile）
//                       ∧ AskEditReviews 的路径判决**只经**它（禁回退到字符串相等
//                       / startsWith("/") / split.contains("..") 那类绕过面）
//   B 留痕在场          AskEditReviews 每次判决都调 AskWriteAudit.record；
//                       拒签文案自带「只编辑绑定文档」规则 + 转交建议
//   C 工具面无直写      ask 授权面不得含 Write/Edit；Bash 必须走只读执行器
//                       （AskBashFace → AskBashGuard.validate），不是裸 shell
//   D 转交结构化        工具 schema 必填 task 且声明 paths/reason/expected；
//                       提示词含两条硬约束；工具清单**派生**（不得手抄第二份）
//
// 自证检测力（作者验收条目 4）：把越权放行注入回去（例如把判决换回 `normalized !=
// target` 或去掉 AskPathBoundary 调用）⇒ 本闸必红。README 同目录的验收证据给了
// 注入前后的退出码读数。
//
// Usage: node scripts/check-ask-write-scope.mjs [--root <repo-root>]
//        `--root` 供「对未修源码直跑应红」取证用（默认 = 本脚本所在仓库）。
// Exit codes: 0 = 通过；1 = 违规逐条列出；2 = SKIP（--root 不是 Stand 源码树——
//             既不 PASS 也不 FAIL；CI 档按 FAIL 记）。
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { isAbsolute, resolve } from 'node:path';

const DEFAULT_ROOT = fileURLToPath(new URL('..', import.meta.url));

const F = {
  boundary: 'src/main/scala/nebflow/core/ask/AskPathBoundary.scala',
  audit: 'src/main/scala/nebflow/core/ask/AskWriteAudit.scala',
  edits: 'src/main/scala/nebflow/core/ask/AskEditReviews.scala',
  bash: 'src/main/scala/nebflow/core/ask/AskBashGuard.scala',
  sessions: 'src/main/scala/nebflow/core/ask/AskSessions.scala',
  assembler: 'src/main/scala/nebflow/core/ask/AskContextAssembler.scala',
  askTools: 'src/main/scala/nebflow/core/tools/AskTools.scala',
  exec: 'src/main/scala/nebflow/agent/AgentSessionExecution.scala',
};

/** 剥块注释与整行注释（文档句可以点名而不触发判据；行尾 `//` 保留以免截断代码行）。 */
export function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .filter((line) => !/^\s*\/\//.test(line))
    .join('\n');
}

/**
 * Scala 3 缩进语法：按 `def` 头切块（check-permission-gate.mjs 同式）。块尾 = 下一个
 * **同级或更浅**缩进的 `def` 头——嵌套的局部 `def` 因此不会把外层函数体截断
 * （Bash 腿的 `def refused(...)` 曾把 `AskBashGuard.validate` 截到块外）。
 */
export function defBlocks(source) {
  const lines = source.split('\n');
  const heads = [];
  lines.forEach((line, i) => {
    const m = /^(\s{0,6})(?:private\s+)?def\s+\w+/.exec(line);
    if (m) heads.push({ i, indent: m[1].length });
  });
  return heads.map((h, n) => {
    let end = lines.length;
    for (let k = n + 1; k < heads.length; k++) {
      if (heads[k].indent <= h.indent) { end = heads[k].i; break; }
    }
    return { header: lines[h.i].trim(), body: lines.slice(h.i, end).join('\n') };
  });
}

/** AskPathBoundary 的判决必须真正做身份归一，不是又一次字符串比较。 */
export function auditBoundary(boundary) {
  const problems = [];
  if (boundary == null) {
    problems.push(`${F.boundary}: missing — the single ask write-path decision point must exist`);
    return problems;
  }
  const code = stripComments(boundary);
  if (!/toRealPath\(/.test(code)) {
    problems.push(`${F.boundary}: the verdict must resolve the real path (no \`toRealPath()\` found)`);
  }
  if (!/Files\.isSameFile\(/.test(code)) {
    problems.push(`${F.boundary}: the verdict must compare file identity (no \`Files.isSameFile(\` found)`);
  }
  if (!/ScopeRule|HandoffHint/.test(code)) {
    problems.push(`${F.boundary}: the refusal text must state the scope rule and the handoff suggestion`);
  }
  return problems;
}

/**
 * AskEditReviews 的目标闸：判决只经 AskPathBoundary；禁任何回退到
 * 字符串相等 / startsWith("/") / split.contains("..") 的接受路径。
 */
export function auditEditGate(edits) {
  const problems = [];
  if (edits == null) {
    problems.push(`${F.edits}: missing — the ask edit target gate must exist`);
    return problems;
  }
  const code = stripComments(edits);
  const capture = defBlocks(code).find((b) => /^def capture\(/.test(b.header));
  if (!capture) {
    problems.push(`${F.edits}: no \`def capture(\` gate found`);
    return problems;
  }
  if (!/AskPathBoundary\.decide/.test(capture.body)) {
    problems.push(`${F.edits}: capture must route its path verdict through AskPathBoundary.decide (single decision point)`);
  }
  if (!/AskWriteAudit\.record\(/.test(capture.body)) {
    problems.push(`${F.edits}: capture must audit every verdict through AskWriteAudit.record`);
  }
  const bypasses = [
    [/!=\s*target\b/, 'string equality against the bound file (`!= target`) is a bypassable verdict — it must go through AskPathBoundary'],
    [/startsWith\("\/"\)/, 'an absolute-path `startsWith("/")` check is not an identity criterion'],
    [/split\('\/'\)\.contains\("\.\."\)/, 'a blanket `..` string check is not an identity criterion (equivalent spellings must be normalised, not banned)'],
    // 「临时放开 `..` 绕过」的检测力（作者验收条目 4）：gate 里任何 `..` 字面量
    // 或路径串谓词都是绕过面——归一（`normalize` + realpath + isSameFile）只准在
    // AskPathBoundary 里发生。
    [/["']\.\.["']/, 'a `..` literal inside the capture gate is a string-level traversal check — normalisation belongs to AskPathBoundary'],
    [/\bpath\.contains\(|\bnormalized\.contains\(/, 'a substring predicate on the requested path is not an identity criterion'],
    [/\bnormalized\b/, 'a locally re-derived `normalized` path spelling is a second verdict source — the request must go to AskPathBoundary as-is'],
  ];
  for (const [re, why] of bypasses) {
    if (re.test(capture.body)) problems.push(`${F.edits}: ${why}`);
  }
  return problems;
}

/** ask 授权面：不得含直写工具；Bash 必须走只读执行器。 */
export function auditToolFace({ sessions, bash, exec }) {
  const problems = [];
  if (sessions == null) {
    problems.push(`${F.sessions}: missing — the ask tool face single point must exist`);
  } else {
    const code = stripComments(sessions);
    const face = /val AllowedTools[\s\S]{0,600}/.exec(code);
    if (!face) problems.push(`${F.sessions}: no \`val AllowedTools\` found`);
    else {
      for (const direct of ['"Write"', '"Edit"', '"MultiEdit"']) {
        if (face[0].includes(direct)) {
          problems.push(`${F.sessions}: the closed ask face must not carry the direct write tool ${direct}`);
        }
      }
    }
  }
  if (bash == null) {
    problems.push(`${F.bash}: missing — the query-only Bash face must exist`);
  } else {
    const code = stripComments(bash);
    const face = defBlocks(code).find((b) => /^def runAskBash\(/.test(b.header));
    if (!face) problems.push(`${F.bash}: no \`def runAskBash(\` found`);
    else if (!/AskBashGuard\.validate\(/.test(face.body)) {
      problems.push(`${F.bash}: the ask Bash leg must validate through AskBashGuard (no bare shell)`);
    }
  }
  if (exec == null) {
    problems.push(`${F.exec}: missing`);
  } else {
    const code = stripComments(exec);
    if (!/if askSession then return nebflow\.core\.ask\.AskSessions\.AllowedTools/.test(code)) {
      problems.push(`${F.exec}: buildAllowedToolSet must still early-return the closed ask face before any other grant`);
    }
  }
  return problems;
}

/** 转交结构化：schema 必填 task + 四件声明；提示词两条约束 + 清单派生。 */
export function auditHandoff({ askTools, assembler }) {
  const problems = [];
  if (askTools == null) {
    problems.push(`${F.askTools}: missing`);
  } else {
    const code = stripComments(askTools);
    const schema = /object SendMessageToMainChatTool[\s\S]*?inputSchema[\s\S]*?required" -> Json\.arr\(([^)]*)\)/.exec(code);
    if (!schema) problems.push(`${F.askTools}: send_message_to_main_chat inputSchema not found`);
    else if (!schema[1].includes('"task"')) {
      problems.push(`${F.askTools}: the handoff tool must REQUIRE the structured \`task\` field`);
    }
    for (const field of ['"paths"', '"reason"', '"expected"']) {
      if (!code.includes(field)) problems.push(`${F.askTools}: the structured handoff must declare ${field}`);
    }
    if (!/AskHandoffWire\.ackFrame\(|AskTools\.pushHandoffFrame\(/.test(code)) {
      problems.push(`${F.askTools}: the handoff must surface a panel frame (askHandoffAck), never silently`);
    }
  }
  if (assembler == null) {
    problems.push(`${F.assembler}: missing`);
  } else {
    const code = stripComments(assembler);
    if (!/toolListSection\(\)/.test(code)) {
      problems.push(`${F.assembler}: the ask prompt must DERIVE its tool list (toolListSection), not hand-write a second inventory`);
    }
    if (!/ToolRegistry\.TOOL_MAP/.test(code)) {
      problems.push(`${F.assembler}: the derived tool list must come from the registry (ToolRegistry.TOOL_MAP)`);
    }
    // 「清单不许手写第二份」：提示词里任何**逐条工具名清单**都是第二个真源。判据是
    // 形状（`- <名字>: <说明>`，就是 toolListSection 生成的那种行），不是名字表——
    // 闸里写名字表本身就成了第三份清单。
    const handWritten = code
      .split('\n')
      .map((line, i) => [i + 1, line])
      .filter(([, line]) => /^\s*-\s+[A-Za-z][A-Za-z0-9_]*(?:\s*[/,]\s*[A-Za-z][A-Za-z0-9_]*)*\s*:/.test(line));
    if (handWritten.length) {
      problems.push(`${F.assembler}: the prompt still hand-writes a tool inventory (line ${handWritten[0][0]}) — the list must be produced by toolListSection()`);
    }
    if (!/本会话只能编辑它对应的那份文档/.test(code)) {
      problems.push(`${F.assembler}: the prompt must state constraint 1 (this session edits only its bound document)`);
    }
    if (!/复杂任务转交主会话/.test(code)) {
      problems.push(`${F.assembler}: the prompt must state constraint 2 (hand complex tasks to the main session)`);
    }
  }
  return problems;
}

export function audit(texts) {
  return [
    ...auditBoundary(texts[F.boundary]),
    ...auditEditGate(texts[F.edits]),
    ...auditToolFace({ sessions: texts[F.sessions], bash: texts[F.bash], exec: texts[F.exec] }),
    ...auditHandoff({ askTools: texts[F.askTools], assembler: texts[F.assembler] }),
  ];
}

/** 读面：缺失文件 = null（判据自己决定缺失是不是违规）。 */
function readAll(root) {
  return Object.fromEntries(
    Object.values(F).map((rel) => [rel, existsSync(resolve(root, rel)) ? readFileSync(resolve(root, rel), 'utf8') : null]),
  );
}

function main(argv = process.argv.slice(2)) {
  let root = DEFAULT_ROOT;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--root') {
      const value = argv[++i];
      if (!value || value.startsWith('--')) throw new Error('--root requires a directory');
      root = isAbsolute(value) ? value : resolve(process.cwd(), value);
    } else throw new Error(`Unknown argument: ${argv[i]}`);
  }
  // SKIP 口径（作者验收条目 6）：--root 不是 Stand 源码树 ⇒ exit 2（既不 PASS 也不
  // FAIL；CI 档按 FAIL 记）。绝不把「找不到文件」混进 PASS。
  if (!existsSync(resolve(root, 'src/main/scala/nebflow'))) {
    console.error(`ask-write-scope: SKIP — ${root} is not a Stand source tree (no src/main/scala/nebflow)`);
    process.exit(2);
  }
  const texts = readAll(root);
  const problems = audit(texts);
  if (problems.length) {
    console.error(`ask-write-scope: ${problems.length} violation(s) in ${root}`);
    for (const problem of problems) console.error('  FAIL ' + problem);
    process.exit(1);
  }
  console.log(`ask-write-scope: OK (${root}) — single normalising verdict point, audited, closed write face, structured handoff`);
  process.exit(0);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(`ask-write-scope: ERROR ${error.message}`);
    process.exit(2);
  }
}
