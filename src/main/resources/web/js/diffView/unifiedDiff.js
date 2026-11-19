// unifiedDiff.js — 纯函数：统一差异文本 →（基线文档, 现文文档）双文档对 + 按文件分节。
//
// 两个差异面共用同一共享组件，而组件底座（@codemirror/merge）吃的是
// 「original 文档 + current 文档」；delegate 面到手的却是统一差异文本
// （unified diff），因此这里做一次**单源转换**：
//
//   · 丢弃版本控制元信息行（`diff --git` / `index` / `--- a/x` / `+++ b/x` /
//     `\ No newline` / `new file mode` / `rename` …）——缺陷 D8 的第二半：「把 VCS
//     元信息当正文渲染」在解析阶段就消掉，组件与两个面都看不到它们；
//   · 按 `@@ -a,b +c,d @@` 头重放上下文/删除/新增行，重建两份文档（基线 =
//     上下文 + 删除行；现文 = 上下文 + 新增行）。全量内容必达，无硬截断。
//
// **多文件（作者令 2026-11-19「diff 要展示…多文件 diff 适配」）**：一段差异文本
// 里常有多个文件段（每个段以 `diff --git a/X b/X` 或 `--- a/X` / `+++ b/X` 起，
// 各带自己的 `@@` 头）。按文件切分后**逐段重建各自的文档对**并保留段元信息
// （路径 / 增删计数 / 变更类型）——把多文件段摊平进一份文档会让第二个文件的行号
// 从 0 重来（行号错位）且丢失「哪一行属于哪个文件」；分节后每段的行号空间独立，
// 逐块动作也能归属到正确文件。
//
// 非统一差异文本（无 `@@` 头）退化为「整段当现文、基线为空」——组件照常渲染，
// 只是没有删除侧。
//
// 零依赖、零 DOM：Node 侧测试（fixture 复算）与浏览器共用。

import { classifyDiffLine } from '../diffRender.js';

const HUNK_RE = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/;
const GIT_RE = /^diff --git a\/(.+?) b\/(.+?)\s*$/;

/** @typedef {{path: string, oldPath: string, changeType: 'added'|'modified'|'deleted',
 *   added: number, removed: number, hunks: number, docs: {original: string, current: string}}} DiffFile */
/** @typedef {{original: string, current: string, hunks: number, droppedMeta: number}} DiffDocs */

/** `--- a/x` / `+++ b/x` 的路径：剥 a//b/ 前缀；`/dev/null` 归空串。 */
function stripAB(p) {
  const v = String(p).split('\t')[0].trim();
  if (v === '/dev/null' || v === '') return '';
  return v.replace(/^(?:a|b)\//, '');
}

/** 一段差异文本 → 按文件分节的文档对表（顺序即文本顺序）。全量内容必达。 */
export function parseUnifiedDiffFiles(diffText) {
  const lines = String(diffText ?? '').split('\n');
  /** @type {{path: string, oldPath: string, changeType: string, added: number, removed: number,
   *          hunks: number, original: string[], current: string[]}[]} */
  const files = [];
  let droppedMeta = 0;
  let inHunk = false;

  const open = (path) => {
    const f = { path: path || '', oldPath: '', changeType: 'modified', added: 0, removed: 0, hunks: 0, original: [], current: [] };
    files.push(f);
    return f;
  };
  let cur = /** @type {ReturnType<typeof open>|null} */ (null);
  const ensure = () => cur || (cur = open(''));

  for (const line of lines) {
    // 文件段起点①：`diff --git a/X b/Y`。
    if (line.startsWith('diff --git ')) {
      const m = GIT_RE.exec(line);
      cur = open(m ? stripAB(m[2]) : '');
      if (m) cur.oldPath = stripAB(m[1]);
      inHunk = false;
      continue;
    }
    if (!inHunk && (line.startsWith('--- ') || line.startsWith('+++ '))) {
      // 文件段起点②：`--- a/X` / `+++ b/Y` 对（无 `diff --git` 头的差异文本）。
      // 若当前段**已经有 hunk**，说明这是下一段的头（不是当前段的装饰）⇒ 另起一段。
      if (cur && cur.hunks > 0) cur = open('');
      const f = ensure();
      const p = stripAB(line.slice(4));
      if (line.startsWith('--- ')) f.oldPath = p; else f.path = p;
      if (line.startsWith('+++ ') && !f.path) f.path = p;
      continue;
    }
    if (line.startsWith('@@')) {
      if (HUNK_RE.test(line)) { inHunk = true; ensure().hunks += 1; }
      continue;
    }
    if (inHunk) {
      const kind = classifyDiffLine(line);
      if (kind === 'meta') { droppedMeta += 1; continue; }   // \ No newline 等
      const f = ensure();
      if (kind === 'del') { f.original.push(line.slice(1)); f.removed += 1; continue; }
      if (kind === 'add') { f.current.push(line.slice(1)); f.added += 1; continue; }
      // ctx：两侧共有（剥掉前导单空格；纯空行既无前缀也照收）。
      const text = line.startsWith(' ') ? line.slice(1) : line;
      f.original.push(text);
      f.current.push(text);
      continue;
    }
    // hunk 之前的段级装饰行（文件头之外仍属元信息面）：按 D8 丢弃，只留类型线索。
    if (/^new file mode/.test(line)) { ensure().changeType = 'added'; droppedMeta += 1; continue; }
    if (/^deleted file mode/.test(line)) { ensure().changeType = 'deleted'; droppedMeta += 1; continue; }
    if (/^(diff |index |new file|deleted file|similarity |rename |old mode|new mode|copy )/.test(line)
      || classifyDiffLine(line) === 'meta') {
      droppedMeta += 1;
      continue;
    }
    if (line === '') continue;   // 载荷首尾空行
    // 无 hunk 头的散行：无上下文可归，忽略（不可辨差异面）。
  }

  let totalHunks = 0;
  const out = files.map((f) => {
    totalHunks += f.hunks;
    // 变更类型补齐：文件名侧为空（/dev/null）= 新增；旧名侧为空 = 删除。
    let changeType = f.changeType;
    if (changeType === 'modified') {
      if (f.oldPath === '' && f.path !== '') changeType = 'added';
      else if (f.path === '' && f.oldPath !== '') changeType = 'deleted';
    }
    return {
      path: f.path || f.oldPath,
      oldPath: f.oldPath,
      changeType,
      added: f.added,
      removed: f.removed,
      hunks: f.hunks,
      docs: { original: f.original.join('\n'), current: f.current.join('\n') },
    };
  });
  return { files: out, fileCount: out.length, hunks: totalHunks, droppedMeta };
}

/** Convert one unified diff body to the (original, current) document pair.
 *  多文件输入按文件顺序摊平（保持迁移批既有语义：单文件段结果逐字节不变）。
 *  @param {string} diffText
 *  @returns {DiffDocs} */
export function unifiedDiffToDocs(diffText) {
  const parsed = parseUnifiedDiffFiles(diffText);
  const original = [];
  const current = [];
  for (const f of parsed.files) {
    if (f.docs.original) original.push(f.docs.original);
    if (f.docs.current) current.push(f.docs.current);
  }
  return { original: original.join('\n'), current: current.join('\n'), hunks: parsed.hunks, droppedMeta: parsed.droppedMeta };
}

export const _internal = { HUNK_RE, GIT_RE, stripAB };
