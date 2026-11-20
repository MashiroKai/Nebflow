// Candidate A — CodeMirror 6 + @codemirror/merge (unified merge view).
import { EditorState } from '@codemirror/state';
import { EditorView, lineNumbers, highlightActiveLine } from '@codemirror/view';
import { unifiedMergeView } from '@codemirror/merge';
import { javascript } from '@codemirror/lang-javascript';
import { mountShell, reportResult } from './runtime.mjs';

const PALETTE = {
  dark: { bg: '#191c22', gutter: '#1e222a', text: '#e6e6e6', muted: '#7b7e88', line: '#2a2f3a', add: 'rgba(63,185,80,0.15)', del: 'rgba(248,81,73,0.15)', addText: '#4caf50', delText: '#f26b6b', sel: '#2b3a4d' },
  light: { bg: '#f5f6f8', gutter: '#eceef1', text: '#1b1f24', muted: '#8b8e96', line: '#e2e4e8', add: 'rgba(63,185,80,0.15)', del: 'rgba(248,81,73,0.15)', addText: '#2f9e44', delText: '#c0392b', sel: '#d7e3f4' },
};

function themeFor(mode) {
  const c = PALETTE[mode];
  return EditorView.theme({
    '&': { backgroundColor: c.bg, color: c.text, fontSize: '11px' },
    '.cm-content': { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', caretColor: 'transparent' },
    '.cm-gutters': { backgroundColor: c.gutter, color: c.muted, border: 'none', fontSize: '10px' },
    '.cm-activeLine': { backgroundColor: 'transparent' },
    '.cm-activeLineGutter': { backgroundColor: 'transparent' },
    '.cm-scroller': { lineHeight: '1.5', overflow: 'auto' },
    '&.cm-merge-a .cm-changedLine, & .cm-deletedChunk': { backgroundColor: c.del },
    '&.cm-merge-a .cm-changedText, & .cm-deletedText': { background: 'rgba(248,81,73,0.35)' },
    '.cm-changedLine': { backgroundColor: c.add },
    '.cm-changedText': { background: 'rgba(63,185,80,0.35)' },
    '.cm-insertedLine': { color: c.addText, backgroundColor: c.add },
    '.cm-deletedLine': { color: c.delText, backgroundColor: c.del },
    '.cm-changedLineGutter': { backgroundColor: c.line },
    '&.cm-focused': { outline: 'none' },
    '.cm-selectionBackground': { backgroundColor: c.sel },
  }, { dark: mode === 'dark' });
}

function renderUnified(el, side, opts = {}) {
  el.innerHTML = '';
  const view = new EditorView({
    parent: el,
    state: EditorState.create({
      doc: side.current,
      extensions: [
        lineNumbers(),
        highlightActiveLine(),
        unifiedMergeView({
          original: side.base,
          mergeControls: !!opts.actions,      // per-chunk accept/reject buttons
          gutter: true,
          highlightChanges: true,
          syntaxHighlightDeletions: true,
          collapseUnchanged: opts.fold ? { margin: 3, minSize: 4 } : undefined,
        }),
        EditorView.editable.of(false),
        EditorState.readOnly.of(true),
        javascript(),
        themeFor(side.theme),
      ],
    }),
  });
  return view;
}

mountShell(({ delegate, ask }) => {
  const t0 = performance.now();
  const vd = renderUnified(delegate.el, delegate, { actions: false, fold: false });
  const vA = renderUnified(ask.el, ask, { actions: true, fold: true });
  reportResult('codemirror', {
    mountMs: +(performance.now() - t0).toFixed(1),
    delegateLines: delegate.current.split('\n').length,
    askLines: ask.current.split('\n').length,
    delegateScrollW: vd.scrollDOM.scrollWidth,
    askScrollW: vA.scrollDOM.scrollWidth,
    delegateContentW: vd.contentDOM.clientWidth,
    askContentW: vA.contentDOM.clientWidth,
    delegateScrollH: vd.scrollDOM.scrollHeight,
    askScrollH: vA.scrollDOM.scrollHeight,
  });
  window.__VIEWS__ = { delegate: vd, ask: vA };
});
