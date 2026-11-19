// Candidate C — Monaco diff editor (the copy ALREADY vendored in the repo).
// Loader recipe copied from the product (js/monacoEditor.js): set
// MonacoEnvironment (blob worker) → inject vs/loader.js → require the AMD main.
import { mountShell, reportResult } from './runtime.mjs';

const MONACO_BASE = '/monaco/vs';

function loadMonaco() {
  if (window.monaco) return Promise.resolve(window.monaco);
  window.MonacoEnvironment = {
    getWorkerUrl: () => {
      const origin = window.location.origin;
      const code = `self.MonacoEnvironment={baseUrl:'${origin}${MONACO_BASE}/'};importScripts('${origin}${MONACO_BASE}/base/worker/workerMain.js');`;
      return URL.createObjectURL(new Blob([code], { type: 'application/javascript' }));
    },
  };
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = `${MONACO_BASE}/loader.js`;
    s.onload = () => {
      window.require.config({ paths: { vs: MONACO_BASE } });
      window.require(['vs/editor/editor.main'], () => resolve(window.monaco), reject);
    };
    s.onerror = () => reject(new Error('monaco loader failed'));
    document.head.appendChild(s);
  });
}

function renderDiff(el, side) {
  el.innerHTML = '';
  el.style.height = '772px';   // Monaco needs a sized container
  el.style.overflow = 'hidden';
  const monaco = window.monaco;
  const original = monaco.editor.createModel(side.base, 'plaintext');
  const modified = monaco.editor.createModel(side.current, 'plaintext');
  const editor = monaco.editor.createDiffEditor(el, {
    readOnly: true,
    renderSideBySide: false,
    originalEditable: false,
    automaticLayout: true,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    lineNumbers: 'on',
    fontSize: 11,
    lineHeight: 16,
    renderOverviewRuler: false,
    diffWordWrap: 'off',
    scrollbar: { verticalScrollbarSize: 6, horizontalScrollbarSize: 6 },
  });
  editor.setModel({ original, modified });
  monaco.editor.setTheme(side.theme === 'dark' ? 'vs-dark' : 'vs');
  return editor;
}

mountShell(async ({ delegate, ask }) => {
  const t0 = performance.now();
  await loadMonaco();
  const loadMs = +(performance.now() - t0).toFixed(1);
  const d = renderDiff(delegate.el, delegate);
  const a = renderDiff(ask.el, ask);
  await new Promise((r) => setTimeout(r, 1200));
  const inner = (el) => { const s = el.querySelector('.monaco-scrollable-element'); return s ? { scrollW: s.scrollWidth, clientW: s.clientWidth } : null; };
  reportResult('monaco', {
    loaderMs: loadMs, mountMs: +(performance.now() - t0).toFixed(1),
    delegateScroll: inner(delegate.el), askScroll: inner(ask.el),
    delegateLines: delegate.current.split('\n').length, askLines: ask.current.split('\n').length,
    lineNumbers: delegate.el.querySelectorAll('.line-numbers').length,
    wordDiff: delegate.el.querySelectorAll('.char-insert, .char-delete').length,
  });
  window.__EDITORS__ = { delegate: d, ask: a };
});
