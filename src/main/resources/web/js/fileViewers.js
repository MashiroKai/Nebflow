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

import { t } from './i18n.js';
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
import videoViewer from './viewers/video.js';
import largeTextViewer from './viewers/largeText.js';

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
    videoViewer,
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
 * #750（nf-file 媒体面高载 503 硬失败批）：渲染腿终态错误的**就地重拉**面。
 * 修前形态：`Failed to render: <err.message>` 一块死文案——过载 503（上游先于
 * 网关放弃的裸 503）与确定性失败同貌，用户唯一出路是刷新整页。本面板在既有
 * 同一句话下加「重试」键（`canvas.retry` 既有 i18n 键；`data-nf-retry` 与
 * attachmentPreview 的失败面板同族）：点击后原 ctx 原样重入 [[renderFile]]——
 * 票据链/nfViewerUrl 重走（blob 账本命中则零取数直渲染），不刷新整页。
 * 动态文本（err.message）一律 textContent 防 XSS。
 */
function renderViewerError(pane, ctx, err) {
  pane.classList.add('scrollable');
  pane.innerHTML = '';
  const box = document.createElement('div');
  box.className = 'canvas-error';
  const msg = document.createElement('div');
  msg.textContent = `Failed to render: ${err && err.message ? err.message : err}`;
  box.appendChild(msg);
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'viewer-retry-btn';
  retry.textContent = t('canvas.retry');
  retry.setAttribute('data-nf-retry', '1');
  retry.addEventListener('click', () => {
    if (!pane.isConnected) return;
    pane.innerHTML = '';
    renderFile(pane, ctx).catch(() => {});
  });
  box.appendChild(retry);
  pane.appendChild(box);
}

/**
 * Render file content into a Canvas tab pane using the appropriate viewer.
 * Falls back to the code viewer if no viewer matches.
 * @param {HTMLElement} pane - the tab content pane
 * @param {import('./utils.js').ViewerContext} ctx
 */
export async function renderFile(pane, ctx) {
  // ── Single-point stream branch (project rule: unified factory + one renderer) ──
  // A file opened through the streaming descriptor carries NO content: it is
  // rendered by the read-only virtual-scroll view and fed by on-demand windows.
  // This is the ONE place that decides it — callers only forward `ctx.stream`.
  const stream = /** @type {any} */ (ctx).stream;
  const viewer = (stream && stream.kind === 'text')
    ? largeTextViewer
    : (registry.get(ctx.itemType) || registry.get('code'));
  try {
    await viewer.render(pane, ctx);
  } catch (err) {
    console.warn('Viewer error:', err);
    renderViewerError(pane, ctx, err);
  }
}
