#!/usr/bin/env node
// check-prompt-catalog-imports.mjs — scala 侧「提示词 catalog 反向依赖」环闸
// （提示词单文件批，2026-11-01）。
//
// Why a static gate：`src/main/scala/nebflow/prompts/` 是提示词常量的单源。它被
// 引擎各层引用（shared / core / agent / llm …），所以它**自身必须零业务依赖**——
// 只要它回头 import 任何一个业务包，就有成环的可能。而 **Scala 3 不做引用环
// 诊断**：prompts ↔ core.tools 成环时**编译全绿**，代价是运行期静默 `null`
// （spike 实测 E3d：`Tools.description = "Declare terminal state via null"`）。
//
// 仓库现有 `scripts/check-circular.mjs` **只扫前端 JS** 的静态 import，
// Scala 侧没有环闸（`scripts/check-scala-layers.mjs` 管分层方向，但它允许
// 豁免清单里的反向边，且不覆盖包内自环语义）。本闸补这个空白，判据故意定得
// 比「无环」更严、也更好判：
//
//   nebflow/prompts/ 下的任何 .scala 文件
//     · 允许 import：scala.* / java.* / java(x) JDK 面，以及 nebflow.prompts.* 自身
//     · 禁止 import：任何其它 nebflow.* 包（含 nebflow.shared.Defaults）
//     · 同样禁止不带 import 的 FQN 裸引用（`nebflow.core.tools.X` 这类写法）
//
// 「零业务依赖」⇒ 按构造成环不可能，不需要做 SCC。
//
// ⚠ 判据边界（别把它当全序保证）：
//   · 只看 import 语句与被剥串/剥注释后的 FQN 引用；反射按名取数（`Class.forName`）
//     抓不到。catalog 是纯常量，本来也不该有反射。
//   · 只看 `nebflow/prompts/` 目录新增文件是否被自动纳入——目录下任何 .scala 都扫。
//
// Exit 0 = clean；exit 1 = 违规逐条列出 `file:line`。
// Usage: node scripts/check-prompt-catalog-imports.mjs

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const DIR = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'prompts');

/** import 允许的前缀（除自身包 nebflow.prompts 外）。 */
const ALLOWED_IMPORT_PREFIXES = ['scala.', 'java.', 'javax.'];
const SELF_PACKAGE = 'nebflow.prompts';

function walkScala(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walkScala(p));
    else if (e.name.endsWith('.scala')) out.push(p);
  }
  return out;
}

/** 剥掉注释与字符串字面量内容（保留换行与列位），避免注释/文案里的包名算边。 */
function stripCommentsAndStrings(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    if (c === '/' && src[i + 1] === '/') {
      while (i < n && src[i] !== '\n') { out += ' '; i++; }
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      out += '  '; i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) { out += src[i] === '\n' ? '\n' : ' '; i++; }
      if (i < n) { out += '  '; i += 2; }
      continue;
    }
    if (src.startsWith('"""', i)) {
      out += '   '; i += 3;
      while (i < n && !src.startsWith('"""', i)) { out += src[i] === '\n' ? '\n' : ' '; i++; }
      if (i < n) { out += '   '; i += 3; }
      continue;
    }
    if (c === '"') {
      out += ' '; i++;
      while (i < n) {
        if (src[i] === '\\') { out += '  '; i += 2; continue; }
        if (src[i] === '"') { out += ' '; i++; break; }
        out += src[i] === '\n' ? '\n' : ' ';
        i++;
      }
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

function lineOf(src, idx) {
  return src.slice(0, idx).split('\n').length;
}

/** 收集 import 的目标包名（支持 `import a.b.{C, D}`、`import a.b.*`、多行续行）。 */
function importTargets(stripped) {
  const hits = [];
  const re = /^[ \t]*import[ \t]+([^\n]*)/gm;
  let m;
  while ((m = re.exec(stripped)) !== null) {
    hits.push({ idx: m.index, text: m[1].trim() });
  }
  return hits;
}

function pkgOfImport(text) {
  // `nebflow.shared.{A, B}` -> nebflow.shared ; `nebflow.shared.Defaults` -> nebflow.shared.Defaults
  const brace = text.indexOf('{');
  const head = (brace === -1 ? text : text.slice(0, brace)).replace(/\.$/, '');
  return head.replace(/\.\*$/, '');
}

const files = walkScala(DIR);
const violations = [];
let importsSeen = 0;
let fqnRefs = 0;

for (const f of files) {
  const rel = relative(ROOT, f).split('\\').join('/');
  const raw = readFileSync(f, 'utf8');
  const stripped = stripCommentsAndStrings(raw);

  for (const h of importTargets(stripped)) {
    importsSeen++;
    const pkg = pkgOfImport(h.text);
    const ok =
      ALLOWED_IMPORT_PREFIXES.some((p) => pkg.startsWith(p)) ||
      pkg === SELF_PACKAGE ||
      pkg.startsWith(SELF_PACKAGE + '.');
    if (!ok) {
      violations.push(`${rel}:${lineOf(stripped, h.idx)} 反向 import \`${h.text}\` —— prompts 包必须零业务依赖`);
    }
  }

  // 不带 import 的 FQN 裸引用（剥串/剥注释后仍然出现 = 真实代码引用）
  const fqnRe = /\bnebflow\.[A-Za-z0-9_.]+/g;
  let q;
  while ((q = fqnRe.exec(stripped)) !== null) {
    fqnRefs++;
    const pkg = q[0];
    if (pkg === SELF_PACKAGE || pkg.startsWith(SELF_PACKAGE + '.')) continue;
    violations.push(
      `${rel}:${lineOf(stripped, q.index)} FQN 裸引用 \`${pkg}\` —— prompts 包必须零业务依赖`
    );
  }
}

console.log(`prompt-catalog-imports: ${files.length} scala file(s) under nebflow/prompts, ` +
  `${importsSeen} import(s), ${fqnRefs} nebflow.* FQN reference(s) scanned.`);
if (violations.length === 0) {
  console.log('PASS: nebflow/prompts has ZERO business-package imports (cycle-impossible by construction).');
  process.exit(0);
}

console.error('prompt-catalog-imports GATE FAILURES:');
for (const v of violations) console.error('  ' + v);
console.error('');
console.error('Fix: 把需要的值搬进 catalog（而不是 import 业务包）。');
console.error('     Scala 3 不报引用环，只会让环上的常量在运行期静默变 null。');
process.exit(1);
