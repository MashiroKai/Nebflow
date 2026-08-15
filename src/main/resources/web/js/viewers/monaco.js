// viewers/monaco.js — Monaco editor viewer (code / json / csv).
// Migrated verbatim from fileViewers.js (viewMonaco). Full editing, not read-only.
// Used for all text-based file types without a dedicated viewer.

/** Monaco editor viewer — replaces read-only code viewer with full editing.
 *  Used for all text-based file types (code, markdown, json, csv, etc.) */
async function viewMonaco(pane, ctx) {
  // Show loading indicator while Monaco loads (first time only).
  pane.innerHTML = '<div class="canvas-loading"><div class="canvas-loading-spinner"></div></div>';

  const { createEditor } = await import('../monacoEditor.js');
  // Create a container for Monaco
  const container = document.createElement('div');
  container.className = 'canvas-monaco-container';
  container.style.width = '100%';
  container.style.height = '100%';
  pane.innerHTML = '';
  pane.appendChild(container);

  const handle = await createEditor(container, {
    path: ctx.path || ctx.fileName,
    content: ctx.content || '',
    fileName: ctx.fileName,
    rootPath: ctx.rootPath || null,
  });

  // Store handle on the pane for canvas.js to access
  pane._editorHandle = handle;

  // Forward dirty state to the pane (for tab indicator)
  handle.onDirty((dirty) => {
    pane._dirty = dirty;
    pane.dispatchEvent(new CustomEvent('editor-dirty-change', { detail: { dirty } }));
  });

  // Notify canvas of the active editor
  const { setActiveEditor } = await import('../monacoEditor.js');
  pane.addEventListener('canvas-tab-activated', () => {
    setActiveEditor(handle);
    handle.focus();
  });

  // If this pane is already active (just opened), set as active editor
  if (pane.classList.contains('active')) {
    setActiveEditor(handle);
  }
}

// 'code' is the registry fallback for every unknown text itemType —
// it declares no extensions of its own.
export default {
  name: 'code',
  label: 'Code (Monaco)',
  extensions: [],
  binary: false,
  priority: 0,
  render: viewMonaco,
};

export const jsonViewer = {
  name: 'json',
  label: 'JSON (Monaco)',
  extensions: ['.json'],
  binary: false,
  priority: 0,
  render: viewMonaco,
};

export const csvViewer = {
  name: 'csv',
  label: 'CSV (Monaco)',
  extensions: ['.csv', '.tsv'],
  binary: false,
  priority: 0,
  render: viewMonaco,
};
