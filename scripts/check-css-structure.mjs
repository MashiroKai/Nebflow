#!/usr/bin/env node
// check-css-structure.mjs — CSS 结构完整性门禁（2026-11-07 · 作者报障
// 「为什么现在图片一点展开，就消失了？」的机检面）
//
// ── 为什么需要这道闸 ───────────────────────────────────────────────────
// 事故形态（1a8182372「定时任务页与待办页统一批」引入，存活 2026-11-06 09:43 →
// 2026-11-07 修复）：css/design-system.css 的 §㉓ `:root {` 块**漏了收括号**，
// 于是该文件其后的 ~700 条规则全部被浏览器当成 **CSS 嵌套规则** 挂进 `:root`
// 里（CDP 实测 selectorText = `& .ds-media-stack__view`）。
//
// 后果不是「样式少了一条」，而是**全文件规则的特异性被整体抬高 1 个类名**：
//   `.ds-media-stack__expand { display:none }` 由 (0,1,0) 变成 (0,2,0)，与
//   chat.css 的态规则 `.pop-expand-view.show { display:flex }` 打平；本文件在
//   index.html 中装载更晚 ⇒ 文档序胜出 ⇒ 叠卡「展开 N」点下去：折叠面被 inline
//   display:none 藏起、展开列又 display:none ⇒ **图片整组消失，只剩胶囊**。
// 既有的 CSS 相关门禁（design-tokens / flat-buttons / glass …）全都按**选择器
// 字面量**判，没人检查「块是否闭合、有没有意外嵌套」——所以整整一天全绿。
//
// ── 判据（零依赖、纯文本状态机；不引 CSS 解析器） ──────────────────────
//   S1 块闭合：全文注释/字符串感知地扫描后，块深度必须归 0，且不得出现多余的 `}`。
//   S2 意外嵌套：一个**声明体**（已经出现过 `属性:` 的块）里再开 `{`，而该 `{` 前
//      的片段不是嵌套 at-rule（`@media` / `@supports` / `@container` / `@layer`）⇒ 红。
//      这条正是本事故的形态（`:root { --ds-x: …; @media … {` 之外的任何嵌套规则
//      都算），且不影响合法写法：顶层 at-rule 里没有声明，故不会被误判。
//   S3 注释闭合：`/*` 必须有对应 `*/`（未闭合注释会把其后全部规则吞掉，同族事故）。
//
// 白名单：`--allow-nesting=<relpath,...>`（只对「确实故意使用 CSS 嵌套」的文件
// 开口子；开口即须在文件里写清理由）。当前仓内零嵌套 ⇒ 白名单为空是常态。
//
// 用法：
//   node scripts/check-css-structure.mjs             → 0 = 全绿 / 1 = 有红
//   node scripts/check-css-structure.mjs --json      → 机器读
//   node scripts/check-css-structure.mjs --root=<dir> → 扫另一棵 web 树（改前/改后同跑）

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const asJson = argv.includes('--json');
const rootArg = argv.find((a) => a.startsWith('--root='));
const allowArg = argv.find((a) => a.startsWith('--allow-nesting='));
const ALLOW_NESTING = new Set(
  (allowArg ? allowArg.slice('--allow-nesting='.length) : '')
    .split(',').map((s) => s.trim()).filter(Boolean),
);
const CSS_ROOT = rootArg
  ? join(rootArg.slice('--root='.length), 'css')
  : join(REPO, 'src', 'main', 'resources', 'web', 'css');

const NESTABLE_AT = /^@(media|supports|container|layer|document)\b/i;

/** 一行里是否像声明（`name: value`，含自定义属性）——用于判定「这个块是声明体还是规则体」。 */
function looksLikeDeclaration(text) {
  return /(^|[\s;{])(-{0,2}[a-zA-Z][\w-]*)\s*:/.test(text.trim());
}

/**
 * 扫描一份 CSS，返回问题清单。
 * @param {string} src
 * @returns {{line: number, code: string, msg: string}[]}
 */
export function scanCss(src) {
  const issues = [];
  /** 块栈：{kind:'decl'|'rule'|'at', line:number, declSeen:boolean, header:string} */
  const stack = [];
  let i = 0;
  let line = 1;
  let state = 'code';           // code | comment | sq-string | dq-string
  let commentLine = 0;
  let segStart = 0;             // 当前片段起点（上一个 `{` / `}` / `;` 之后）

  const pushDecl = (seg) => {
    if (stack.length && looksLikeDeclaration(seg)) stack[stack.length - 1].declSeen = true;
  };

  while (i < src.length) {
    const ch = src[i];
    if (ch === '\n') line += 1;

    if (state === 'comment') {
      if (ch === '*' && src[i + 1] === '/') { state = 'code'; i += 2; segStart = i; continue; }
      i += 1; continue;
    }
    if (state === 'sq-string' || state === 'dq-string') {
      if (ch === '\\') { i += 2; continue; }
      if ((state === 'sq-string' && ch === "'") || (state === 'dq-string' && ch === '"')) state = 'code';
      i += 1; continue;
    }

    // state === 'code'
    if (ch === '/' && src[i + 1] === '*') {
      pushDecl(src.slice(segStart, i));
      state = 'comment'; commentLine = line; i += 2; segStart = i; continue;
    }
    if (ch === '"') { state = 'dq-string'; i += 1; continue; }
    if (ch === "'") { state = 'sq-string'; i += 1; continue; }
    if (ch === '{') {
      const header = src.slice(segStart, i).trim();
      const parent = stack[stack.length - 1];
      const isAt = header.startsWith('@');
      const headerLine = line;
      if (parent && parent.declSeen && !(isAt && NESTABLE_AT.test(header))) {
        // S2：声明体里开了一个非 at-rule 的块 = 意外嵌套（本事故形态）。
        // 同一个块只报第一条 —— 漏括号会让其后每条规则都命中，逐条刷屏反而盖住病因。
        if (!parent.nestedReported) {
          parent.nestedReported = true;
          issues.push({
            line: headerLine,
            code: 'S2',
            msg: `声明块里出现嵌套规则 \`${header.split('\n').pop().slice(0, 60)}\` —— 上一层的 \`}\` 很可能漏了`,
          });
        }
      }
      stack.push({
        kind: isAt ? 'at' : 'rule',
        line: headerLine,
        declSeen: false,
        nestedReported: false,
        header: header.split('\n').pop().slice(0, 60),
      });
      // `at` 块里若随后出现声明，得能识别 —— 与 rule 一样按 declSeen 跟踪
      segStart = i + 1;
      i += 1; continue;
    }
    if (ch === '}') {
      if (!stack.length) {
        issues.push({ line, code: 'S1', msg: '多余的 `}`（没有对应的 `{`）' });
      } else {
        stack.pop();
      }
      segStart = i + 1;
      i += 1; continue;
    }
    if (ch === ';') {
      pushDecl(src.slice(segStart, i));
      segStart = i + 1;
      i += 1; continue;
    }
    i += 1;
  }

  // 收尾：尾部残余片段同样可能是声明（无分号的最后一条声明）
  if (state === 'comment') {
    issues.push({ line: commentLine, code: 'S3', msg: '注释 `/*` 未闭合（会吞掉其后全部规则）' });
  }
  if (state === 'sq-string' || state === 'dq-string') {
    issues.push({ line, code: 'S3', msg: '字符串引号未闭合' });
  }
  for (const b of stack) {
    issues.push({
      line: b.line,
      code: 'S1',
      msg: `块未闭合：\`${b.header || '(root)'}\` 开到文件末尾（其后全部规则会被浏览器按嵌套解析）`,
    });
  }
  return issues;
}

function walkCss(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walkCss(p, out);
    else if (name.endsWith('.css')) out.push(p);
  }
  return out;
}

/** 判据自检（--selftest）：四种形态各给一份最小样张，判定必须与预期一致。
 *  改判据先改这里 —— 闸的自证面与案例绑定，避免「闸在但判据漂了」。 */
function selftest() {
  const cases = [
    {
      name: '事故形态：:root 漏收括号（其后全被当成嵌套）',
      css: ':root {\n  --a: 1;\n\n@media (hover: hover) {\n  .b { color: red; }\n}\n',
      want: ['S1'],
    },
    {
      name: '合法：顶层 at-rule 里放规则',
      css: '@media (hover: hover) {\n  .b { color: red; }\n}\n',
      want: [],
    },
    {
      name: '意外嵌套：声明体里直接嵌规则',
      css: '.a {\n  color: red;\n  .b { color: blue; }\n}\n',
      want: ['S2'],
    },
    {
      name: '注释未闭合',
      css: '.a { color: red; }\n/* 忘了收\n.b { color: blue; }\n',
      want: ['S3'],
    },
  ];
  let bad = 0;
  for (const c of cases) {
    const got = [...new Set(scanCss(c.css).map((x) => x.code))].sort();
    const want = [...c.want].sort();
    const ok = got.join(',') === want.join(',');
    if (!ok) bad += 1;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${c.name} — got=[${got}] want=[${want}]`);
  }
  console.log(bad ? `[css-structure] SELFTEST FAIL (${bad}/${cases.length})` : `[css-structure] SELFTEST PASS (${cases.length}/${cases.length})`);
  process.exit(bad ? 1 : 0);
}

function main() {
  if (argv.includes('--selftest')) { selftest(); return; }
  const files = walkCss(CSS_ROOT);
  const report = [];
  for (const file of files) {
    const rel = relative(join(CSS_ROOT, '..', '..', '..', '..', '..'), file).replace(/\\/g, '/');
    const short = rel.replace(/^.*?web\//, 'web/');
    if (ALLOW_NESTING.has(short) || ALLOW_NESTING.has(rel)) {
      // 白名单文件只跑 S1/S3（块闭合），跳过 S2
      const issues = scanCss(readFileSync(file, 'utf8')).filter((x) => x.code !== 'S2');
      report.push({ file: short, issues });
      continue;
    }
    report.push({ file: short, issues: scanCss(readFileSync(file, 'utf8')) });
  }

  const bad = report.filter((r) => r.issues.length);
  if (asJson) {
    console.log(JSON.stringify({
      root: CSS_ROOT, scanned: report.length, failed: bad.length,
      report: bad,
    }, null, 2));
    process.exit(bad.length ? 1 : 0);
  }

  console.log(`[css-structure] 扫描 ${report.length} 个 CSS 文件（${CSS_ROOT}）`);
  if (!bad.length) {
    console.log('[css-structure] PASS — 块闭合 / 注释闭合 / 零意外嵌套');
    process.exit(0);
  }
  for (const r of bad) {
    console.log(`  ✘ ${r.file}`);
    for (const it of r.issues) console.log(`      L${it.line} [${it.code}] ${it.msg}`);
  }
  console.log(`[css-structure] FAIL — ${bad.length} 个文件有红`);
  process.exit(1);
}

// 直跑判据（路径含空格时 import.meta.url 是百分号编码形态，必须同法归一）。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
