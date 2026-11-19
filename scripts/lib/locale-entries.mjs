// lib/locale-entries.mjs — 「界面文案判定面」的唯一实现（locale 值与 index.html 文本面）
//
// 为什么独立成 lib（ui-design-requirements 批，2026-11-18）：同一个判定面——
// 「用户能读到的文案是哪些字符串」——被两个门禁同时需要：
//   ① `scripts/check-self-explaining.mjs`：四类教学句禁止（铁律 8）
//   ② `scripts/check-copy-budget.mjs`：解释性文字长度预算（explanatory-copy-spec）
// 两处各自正则一遍 = 下一批出现「一个脚本算注释里的文案、另一个不算」的分叉。
//
// 判定面定义（两处共用，改口径只改这里）：
//   · locale 文件 `js/locales/*.js` 的**值**（单行 `'key': 'value'` 形态）
//   · `index.html` 的文本行（HTML 注释不算）
//   **代码注释不算**（语法级剔注释，不是行首 `//` 的尾管道）。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

/**
 * 去掉注释但**保留行号**（把注释内容换成等长空白/换行）。
 * - JS（locale）：块注释 `/* … *\/` + 行注释 `// …`（只认行首或前置空白的 `//`）。
 * - HTML（index.html）：`<!-- … -->`（外加其内联脚本里的块注释）。
 * @param {string} src
 * @param {'js'|'html'} kind
 */
export function stripComments(src, kind) {
  const blank = (m) => m.replace(/[^\n]/g, ' ');
  let out = src;
  if (kind === 'html') out = out.replace(/<!--[\s\S]*?-->/g, blank);
  out = out.replace(/\/\*[\s\S]*?\*\//g, blank);
  out = out.split('\n').map((line) => {
    const m = line.match(/^(\s*)\/\//);
    return m ? m[1] + ' '.repeat(line.length - m[1].length) : line;
  }).join('\n');
  return out;
}

/**
 * locale 文件的文案值行 → [{line, key, value}]。
 * 只认单行 `'key': 'value'` 形态（本仓 locale 全是这个形态）。
 * @param {string} src 已剔注释的源码
 */
export function localeEntries(src) {
  const out = [];
  src.split('\n').forEach((line, i) => {
    const m = line.match(/^\s*(['"])([^'"]+)\1\s*:\s*(['"`])([\s\S]*)\3\s*,?\s*$/);
    if (!m) return;
    out.push({ line: i + 1, key: m[2], value: m[4] });
  });
  return out;
}

/**
 * 读 locale 目录下全部 `*.js` 的文案条目。
 * @param {string} dir 绝对路径
 * @returns {{names: string[], byFile: Map<string, {key:string,value:string,line:number}[]>}}
 */
export function readLocales(dir) {
  const names = existsSync(dir) ? readdirSync(dir).filter((n) => n.endsWith('.js')).sort() : [];
  const byFile = new Map();
  for (const name of names) {
    byFile.set(name, localeEntries(stripComments(readFileSync(join(dir, name), 'utf8'), 'js')));
  }
  return { names, byFile };
}

/**
 * `index.html` 的文本行（剔注释、剔空行）→ [{line, text}]。
 * @param {string} file 绝对路径
 */
export function htmlTextLines(file) {
  if (!existsSync(file)) return [];
  const src = stripComments(readFileSync(file, 'utf8'), 'html');
  const out = [];
  src.split('\n').forEach((line, i) => {
    const text = line.trim();
    if (text) out.push({ line: i + 1, text });
  });
  return out;
}
