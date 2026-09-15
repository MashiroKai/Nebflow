// fileViewers.js - Pluggable file viewer registry for Canvas tabs.
//
// Each viewer is a protocol object (see js/viewers/*.js):
//   { name, label, extensions, binary, priority, render(pane, ctx) }
// ctx = { itemType, content, absPath, fileName, size, path, rootPath }
//
// The registry maps itemType → viewer object. Built-in viewers are statically
// imported and registered at module load (P1: no lazy loading, zero risk).
// 'url' intentionally stays out of the registry - it is handled inline by
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

/**
 * Register (or overwrite) a viewer. name = itemType it handles.
 * @param {import('./utils.js').ViewerProtocol} viewer
 */
export function registerViewer(viewer) {
  registry.set(viewer.name, viewer);
}

function registerBuiltIns() {
  for (const viewer of [
    codeViewer, jsonViewer, csvViewer,
    markdownViewer, yamlViewer, htmlViewer,
    imageViewer, pdfViewer, docxViewer, xlsxViewer, pptxViewer, epubViewer,
  ]) {
    // The built-in viewer modules predate the ViewerProtocol typedef and
    // destructure ctx fields with locally-required shapes — cast at this
    // boundary; new viewers should conform to ViewerProtocol directly.
    registerViewer(/** @type {import('./utils.js').ViewerProtocol} */ (viewer));
  }
}
registerBuiltIns();

/**
 * File name → viewer itemType，判据 = **注册表自身**各 viewer 声明的 `extensions`。
 *
 * 🔴 禁新增第二张扩展名表：仓内 ext→itemType 的真源有两处且各自权威 ——
 * 服务端 `nebflow.core.workspace.FileTypeRegistry`（读文件时定 itemType）与
 * 本注册表各 viewer 的 `extensions`（渲染面能力）。本函数只把后者**读出来**，
 * 不复制任何一份表（两张表迟早漂移）。
 *
 * @param {string} fileName
 * @returns {string|null} itemType；无 viewer 认领该扩展名 ⇒ `null`
 *   （调用方据此落**可见**降级，禁静默）。`code` viewer 不声明扩展名
 *   （它是未知文本类型的注册表回落项），故不在此返回 'code'。
 */
export function itemTypeForFileName(fileName) {
  const m = /\.([A-Za-z0-9]+)\s*$/.exec(String(fileName || ''));
  if (!m) return null;
  const ext = '.' + m[1].toLowerCase();
  for (const viewer of registry.values()) {
    if (Array.isArray(viewer.extensions) && viewer.extensions.includes(ext)) return viewer.name;
  }
  return null;
}

/**
 * Render file content into a Canvas tab pane using the appropriate viewer.
 * Falls back to the code viewer if no viewer matches.
 * @param {HTMLElement} pane - the tab content pane
 * @param {import('./utils.js').ViewerContext} ctx
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
