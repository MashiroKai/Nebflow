// fileViewers.js — Pluggable file viewer registry for Canvas tabs.
//
// Each viewer is a protocol object (see js/viewers/*.js):
//   { name, label, extensions, binary, priority, render(pane, ctx) }
// ctx = { itemType, content, absPath, fileName, size, path, rootPath }
//
// The registry maps itemType → viewer object. Built-in viewers are statically
// imported and registered at module load (P1: no lazy loading, zero risk).
// 'url' intentionally stays out of the registry — it is handled inline by
// canvas.js (no file-extension semantics).

import { escapeHtml } from './viewers/shared.js';
import codeViewer, { jsonViewer, csvViewer } from './viewers/monaco.js';
import markdownViewer from './viewers/markdown.js';
import yamlViewer from './viewers/yaml.js';
import htmlViewer from './viewers/html.js';
import imageViewer from './viewers/image.js';
import pdfViewer from './viewers/pdf.js';
import docxViewer from './viewers/docx.js';
import xlsxViewer from './viewers/xlsx.js';
import pptxViewer from './viewers/pptx.js';
import epubViewer from './viewers/epub.js';

const registry = new Map();  // itemType → viewer protocol object

/** Register (or overwrite) a viewer. name = itemType it handles. */
export function registerViewer(viewer) {
  registry.set(viewer.name, viewer);
}

function registerBuiltIns() {
  for (const viewer of [
    codeViewer, jsonViewer, csvViewer,
    markdownViewer, yamlViewer, htmlViewer,
    imageViewer, pdfViewer, docxViewer, xlsxViewer, pptxViewer, epubViewer,
  ]) {
    registerViewer(viewer);
  }
}
registerBuiltIns();

/**
 * Render file content into a Canvas tab pane using the appropriate viewer.
 * Falls back to the code viewer if no viewer matches.
 * @param {HTMLElement} pane — the tab content pane
 * @param {Object} ctx — { itemType, content, absPath, fileName, size }
 */
export async function renderFile(pane, ctx) {
  const viewer = registry.get(ctx.itemType) || registry.get('code');
  try {
    await viewer.render(pane, ctx);
  } catch (err) {
    console.warn('Viewer error:', err);
    pane.innerHTML = `<div class="canvas-error">Failed to render: ${escapeHtml(err.message)}</div>`;
  }
}
