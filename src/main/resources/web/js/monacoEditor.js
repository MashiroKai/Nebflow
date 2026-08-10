// monacoEditor.js — Monaco Editor integration for Canvas tabs.
//
// Loads Monaco from CDN lazily (first use). Each file gets its own editor
// instance attached to a DOM container. Tracks dirty state, handles save.
//
// Usage:
//   const ed = await createEditor(paneEl, { path, content, fileName });
//   ed.onDirty(callback);       // called when content changes
//   await ed.save();            // sends writeFile WS + marks clean
//   ed.dispose();               // cleanup when tab closes

import state from './state.js';
import { sendWs, onMessage } from './ws.js';

// Inject CSS to hide the native textarea caret (thin cursor).
// Monaco renders its own cursor div; the textarea's native caret bleeds through
// the transparent background, creating a double-cursor effect.
if (!document.getElementById('monaco-caret-fix')) {
  const style = document.createElement('style');
  style.id = 'monaco-caret-fix';
  style.textContent = '.monaco-editor textarea.inputarea { caret-color: transparent !important; }';
  document.head.appendChild(style);
}

const MONACO_BASE = '/vendor/monaco/vs';

// ── Worker configuration ──────────────────────────────────────────────
// Monaco's web worker (workerMain.js) has its own AMD loader that needs
// to know the baseUrl to resolve language modules (jsonMode.js, etc.).
// Without this, the worker tries to fetch from invalid URLs.
window.MonacoEnvironment = {
  getWorkerUrl: () => {
    const origin = window.location.origin;
    const code = `self.MonacoEnvironment={baseUrl:'${origin}${MONACO_BASE}/'};importScripts('${origin}${MONACO_BASE}/base/worker/workerMain.js');`;
    const blob = new Blob([code], { type: 'application/javascript' });
    return URL.createObjectURL(blob);
  }
};

// ── Lazy loader ────────────────────────────────────────────────────────

let monacoPromise = null;

/** Preload Monaco in the background (e.g. on page idle).
 *  Safe to call multiple times — returns the cached promise. */
export function preloadMonaco() {
  return loadMonaco();
}

export function loadMonaco() {
  if (window.monaco) return Promise.resolve(window.monaco);
  if (monacoPromise) return monacoPromise;

  monacoPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `${MONACO_BASE}/loader.js`;
    script.onload = () => {
      window.require.config({ paths: { vs: MONACO_BASE } });
      window.require(['vs/editor/editor.main'], () => {
        const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        const transparentColors = {
          'editor.background': '#00000000',
          'editor.lineHighlightBackground': '#00000000',
          'editor.lineHighlightBorder': '#00000000',
          'editorGutter.background': '#00000000',
          'editor.foldBackground': '#FFFFFF14',
          'editorCursor.foreground': '#07c160',
        };
        window.monaco.editor.defineTheme('nebflow-dark', {
          base: 'vs-dark',
          inherit: true,
          rules: [],
          colors: transparentColors,
        });
        window.monaco.editor.defineTheme('nebflow-light', {
          base: 'vs',
          inherit: true,
          rules: [],
          colors: transparentColors,
        });
        window.monaco.editor.setTheme(dark ? 'nebflow-dark' : 'nebflow-light');

        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
          window.monaco.editor.setTheme(e.matches ? 'nebflow-dark' : 'nebflow-light');
        });

        resolve(window.monaco);
      });
    };
    script.onerror = () => {
      monacoPromise = null;
      reject(new Error('Failed to load Monaco Editor'));
    };
    document.head.appendChild(script);
  });

  return monacoPromise;
}

// ── Language detection ─────────────────────────────────────────────────

const LANG_MAP = {
  scala: 'scala', sc: 'scala',
  java: 'java',
  py: 'python',
  js: 'javascript', mjs: 'javascript', cjs: 'javascript',
  ts: 'typescript', jsx: 'javascript', tsx: 'typescript',
  json: 'json',
  html: 'html', htm: 'html',
  css: 'css', scss: 'scss', less: 'less',
  xml: 'xml',
  yaml: 'yaml', yml: 'yaml',
  md: 'markdown', markdown: 'markdown',
  sh: 'shell', bash: 'shell', zsh: 'shell',
  sql: 'sql',
  go: 'go',
  rs: 'rust',
  c: 'c', cpp: 'cpp', h: 'cpp', hpp: 'cpp',
  php: 'php',
  rb: 'ruby',
  swift: 'swift',
  kt: 'kotlin', kts: 'kotlin',
  toml: 'ini',
  ini: 'ini', cfg: 'ini', conf: 'ini',
  gradle: 'groovy',
  dart: 'dart',
  lua: 'lua',
  r: 'r',
  csv: 'plaintext',
  tsv: 'plaintext',
  txt: 'plaintext',
  dockerfile: 'dockerfile',
  gitignore: 'plaintext',
  env: 'plaintext',
  properties: 'properties',
};

function getLanguage(fileName) {
  const name = (fileName || '').toLowerCase();
  // Special filenames
  if (name === 'dockerfile') return 'dockerfile';
  if (name === 'makefile' || name === 'gnumakefile') return 'makefile';
  if (name === '.gitignore' || name === '.dockerignore') return 'plaintext';
  const ext = name.split('.').pop();
  return LANG_MAP[ext] || 'plaintext';
}

// ── Editor factory ─────────────────────────────────────────────────────

/**
 * Create a Monaco editor instance inside a container element.
 * @param {HTMLElement} container — DOM element to mount editor in
 * @param {Object} opts — { path, content, fileName, readOnly }
 * @returns {Promise<Object>} — editor handle with methods
 */
export async function createEditor(container, opts) {
  const monaco = await loadMonaco();

  const language = getLanguage(opts.fileName);
  // Strip leading slashes from path to avoid file://// (double-slash in URI path component)
  const cleanPath = (opts.path || opts.fileName || 'untitled').replace(/\\/g, '/').replace(/^\/+/, '');
  const uri = monaco.Uri.parse(`file:///${cleanPath}`);
  let model = monaco.editor.getModel(uri);

  if (!model) {
    model = monaco.editor.createModel(opts.content || '', language, uri);
  } else {
    // Model already exists (file reopened) — update content if provided
    if (opts.content !== undefined) model.setValue(opts.content);
  }

  const editor = monaco.editor.create(container, {
    model,
    theme: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'nebflow-dark' : 'nebflow-light',
    automaticLayout: true,   // handles container resize
    fontSize: 13,
    lineHeight: 20,
    fontFamily: '"SF Mono", "JetBrains Mono", "Fira Code", "Cascadia Code", Menlo, Monaco, "Courier New", monospace',
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    padding: { top: 12, bottom: 12 },
    smoothScrolling: true,
    cursorSmoothCaretAnimation: 'off',
    cursorBlinking: 'phase',
    cursorStyle: 'line',
    renderLineHighlight: 'none',
    roundedSelection: false,
    renderWhitespace: 'selection',
    tabSize: 2,
    wordWrap: 'on',
    readOnly: opts.readOnly || false,
    lineNumbers: 'on',
    glyphMargin: false,
    folding: true,
    showFoldingControls: 'mouseover',
    guides: { indentation: true, bracketPairs: true },
    bracketPairColorization: { enabled: true },
    suggestOnTriggerCharacters: true,
    quickSuggestions: { other: true, comments: false, strings: false },
  });

  // ── Dirty tracking ──
  let dirty = false;
  const dirtyListeners = new Set();
  let originalContent = opts.content || '';

  model.onDidChangeContent(() => {
    const newDirty = model.getValue() !== originalContent;
    if (newDirty !== dirty) {
      dirty = newDirty;
      dirtyListeners.forEach(fn => fn(dirty));
    }
  });

  // ── Save handler ──
  async function save() {
    const content = model.getValue();
    return new Promise((resolve) => {
      // Set up one-shot response handler
      const cleanup = onMessage('fileSaved', (msg) => {
        if (msg.path === opts.path) {
          cleanup();
          dirty = false;
          originalContent = model.getValue();
          dirtyListeners.forEach(fn => fn(false));
          resolve(true);
        }
      });
      const cleanupErr = onMessage('fileSaveError', (msg) => {
        if (msg.path === opts.path) {
          cleanupErr();
          console.error('Save failed:', msg.error);
          resolve(false);
        }
      });

      sendWs({
        type: 'writeFile',
        sessionId: state.activeSessionId,
        path: opts.path,
        content,
        rootPath: opts.rootPath || null,
      });
    });
  }

  // ── Dispose ──
  function dispose() {
    editor.dispose();
    // Don't dispose model — Monaco caches them for reuse
  }

  return {
    editor,
    model,
    getDirty: () => dirty,
    onDirty: (fn) => { dirtyListeners.add(fn); },
    save,
    dispose,
    focus: () => editor.focus(),
  };
}

// ── Global save shortcut (Ctrl+S / Cmd+S) ──────────────────────────────

let activeEditorHandle = null;

/** Track the currently active editor for global Ctrl+S. */
export function setActiveEditor(handle) {
  activeEditorHandle = handle;
}

document.addEventListener('keydown', (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key === 's') {
    if (activeEditorHandle && activeEditorHandle.getDirty()) {
      e.preventDefault();
      activeEditorHandle.save();
    }
  }
});
