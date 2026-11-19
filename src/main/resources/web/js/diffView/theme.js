// theme.js — 共享差异组件（js/diffView）的 CodeMirror 主题。
//
// 纪律（硬不变式）：**颜色零字面量** —— 主题里所有色值只写 CSS 自定义属性
// 引用（`var(--…)`），真值单点在 css/stand-tokens.css 的 `--nf-dv-*` 族。这
// 使暗亮两档随应用主题（`prefers-color-scheme`）自动翻转，组件不为换肤做任何
// JS 工作；也使 D5（对比度）只需在 token 单源处一次调对。
//
// 选择器形状必须镜像 @codemirror/merge 自带主题（`&light/&dark.cm-merge-a …`
// 等三阶级特异度），否则覆盖不住自动生成的差异底色/词级强调 —— 这正是 D3/D5
// 的落点。

import { loadCodeMirror } from './cmLoader.js';

/** Build the EditorView theme extension. Call after `loadCodeMirror()`.
 *  @param {{EditorView: any}} cm */
export function diffTheme(cm) {
  const { EditorView } = cm;
  const mono = 'ui-monospace, SFMono-Regular, Menlo, monospace';
  return EditorView.theme({
    '&': {
      backgroundColor: 'var(--nf-dv-bg)',
      color: 'var(--nf-dv-fg)',
      fontSize: 'var(--nf-dv-fs)',
      fontFamily: mono,
    },
    '&.cm-focused': { outline: 'none' },
    '.cm-scroller': {
      fontFamily: mono,
      lineHeight: 'var(--nf-dv-lh)',
      overflow: 'auto',
    },
    '.cm-content': { caretColor: 'transparent', padding: '0' },
    '.cm-gutters': {
      backgroundColor: 'var(--nf-dv-gutter-bg)',
      color: 'var(--nf-dv-gutter-fg)',
      border: 'none',
      fontFamily: mono,
      fontSize: 'var(--nf-dv-gutter-fs)',
    },
    '.cm-lineNumbers .cm-gutterElement': { padding: '0 8px 0 6px' },
    '.cm-activeLine': { backgroundColor: 'transparent' },
    '.cm-activeLineGutter': { backgroundColor: 'transparent' },
    '.cm-selectionBackground, ::selection': { backgroundColor: 'var(--nf-dv-selection)' },
    // 变更底色（D3/D5）：镜像 merge 自带选择器形状以取得覆盖权。
    '&.cm-merge-a .cm-changedLine, &.cm-merge-b .cm-changedLine, & .cm-deletedChunk': {
      backgroundColor: 'var(--nf-dv-del-bg)',
    },
    '&.cm-merge-b .cm-changedLine, & .cm-inlineChangedLine': {
      backgroundColor: 'var(--nf-dv-add-bg)',
    },
    // 词级（行内）强调：底色块，正文仍用 --nf-dv-fg（对比度由 token 保证）。
    '&.cm-merge-b .cm-changedText': { background: 'var(--nf-dv-add-word)' },
    '&.cm-merge-a .cm-changedText, & .cm-deletedChunk .cm-deletedText': { background: 'var(--nf-dv-del-word)' },
    '&.cm-merge-b .cm-deletedText': { background: 'var(--nf-dv-del-word)' },
    '.cm-insertedLine, .cm-deletedLine, .cm-deletedLine del': {
      color: 'var(--nf-dv-fg)',
      textDecoration: 'none',
    },
    '.cm-deletedChunk': { padding: '0 0 0 8px', position: 'relative' },
    '.cm-insertedLine, .cm-deletedLine': { padding: '0 8px' },
    // 未变段折叠带（D8）：≥44px 触控档，颜色走 token。
    '.cm-collapsedLines': {
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: 'var(--nf-dv-hit)',
      padding: '0 var(--ds-space-2)',
      font: 'inherit',
      fontSize: 'var(--nf-dv-gutter-fs)',
      color: 'var(--nf-dv-fold-fg)',
      cursor: 'pointer',
      background: 'var(--nf-dv-fold-bg)',
      borderTop: '1px solid var(--nf-dv-border)',
      borderBottom: '1px solid var(--nf-dv-border)',
    },
  }, { dark: false });
}
