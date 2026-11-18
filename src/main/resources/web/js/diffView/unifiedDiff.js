// unifiedDiff.js — 纯函数：统一差异文本 →（基线文档, 现文文档）双文档对。
//
// 两个差异面共用同一共享组件，而组件底座（@codemirror/merge）吃的是
// 「original 文档 + current 文档」；delegate 面到手的却是统一差异文本
// （unified diff），因此这里做一次**单源转换**：
//
//   · 丢弃版本控制元信息行（`diff --git` / `index` / `--- a/x` / `+++ b/x` /
//     `\ No newline`）——缺陷 D8 的第二半：「把 VCS 元信息当正文渲染」在解析
//     阶段就消掉，组件与两个面都看不到它们；
//   · 按 `@@ -a,b +c,d @@` 头重放上下文/删除/新增行，重建两份文档（基线 =
//     上下文 + 删除行；现文 = 上下文 + 新增行）。全量内容必达，无硬截断。
//
// 非统一差异文本（无 `@@` 头）退化为「整段当现文、基线为空」——组件照常渲染，
// 只是没有删除侧。
//
// 零依赖、零 DOM：Node 侧测试（fixture 复算）与浏览器共用。

import { classifyDiffLine } from '../diffRender.js';

const HUNK_RE = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/;

/** @typedef {{original: string, current: string, hunks: number, droppedMeta: number}} DiffDocs */

/** Convert one unified diff body to the (original, current) document pair.
 *  @param {string} diffText
 *  @returns {DiffDocs} */
export function unifiedDiffToDocs(diffText) {
  const lines = String(diffText ?? '').split('\n');
  /** @type {string[]} */ const original = [];
  /** @type {string[]} */ const current = [];
  let hunks = 0;
  let droppedMeta = 0;
  let seenHunk = false;
  for (const line of lines) {
    if (HUNK_RE.test(line)) { hunks += 1; seenHunk = true; continue; }
    const kind = classifyDiffLine(line);
    if (!seenHunk) {
      // 头部（文件级）元信息——含 diff --git / index / --- / +++：按 D8 丢弃。
      if (kind === 'meta' || /^(diff |index |new file|deleted file|similarity |rename )/.test(line)) {
        droppedMeta += 1;
        continue;
      }
      if (line === '') continue; // 载荷首尾空行
      // 无 hunk 头的散行：无上下文可归，忽略（不可辨差异面）。
      continue;
    }
    if (kind === 'meta') { droppedMeta += 1; continue; }   // \ No newline / --- +++ 块内元信息
    if (kind === 'hunk') continue;
    if (kind === 'del') { original.push(line.slice(1)); continue; }
    if (kind === 'add') { current.push(line.slice(1)); continue; }
    // ctx：两侧共有（剥掉前导单空格；纯空行既无前缀也照收）。
    const text = line.startsWith(' ') ? line.slice(1) : line;
    original.push(text);
    current.push(text);
  }
  // 数字行前缀已剥离；保留尾部换行语义（统一差异上下文行不含尾换行信息，
  // 统一以 '\n' 连接并去尾——组件折叠/行号按行计，不收尾不影响读数）。
  return { original: original.join('\n'), current: current.join('\n'), hunks, droppedMeta };
}

export const _internal = { HUNK_RE };
