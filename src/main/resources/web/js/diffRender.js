// diffRender.js — pure HTML renderers for the per-node file-change diff report.
//
// Zero imports, zero DOM access at module scope: the render core is consumable
// from plain Node (fixture checks) as well as the browser. The browser wiring
// (fetch + mount) lives in diffView.js.
//
// Wire contract (GET /api/projects/<name>/flow-map/nodes/<nodeId>/file-changes):
//   { nodeId, generatedAt, root, files: [{ path, changeType, added, removed,
//     source, diffText?, degradedReason? }] }
//   changeType ∈ added | modified | deleted; source ∈ git | tool-ledger.
//   diffText is a unified diff body (hunk headers + " "/"-"/"+" lines). Entries
//   without diffText carry degradedReason (file-too-large / binary-file /
//   diff-too-large / git-diff-unavailable ...) — rendered as counts-only rows.

/** HTML-escape a string (mirrors flowHelpers.esc; duplicated here to keep this
 *  module dependency-free for the Node-side fixture check). */
export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

/** One diff line → its row kind. Single source for BOTH consumers that
 *  classify lines: [[parseUnifiedDiff]] (whole-body, small faces) and the
 *  streaming Canvas renderer (diffCanvas.js, huge bodies — walks the string
 *  line-by-line instead of materializing a rows array first).
 *  二次加载清剿批 2026-10-24（作者报障「点 diff … 在 canvas 打开的体验还是很慢，
 *  甚至点了没反应」）：抽出来就是为了那条腿不再先全量 split 再渲染。 */
/** @returns {'hunk'|'add'|'del'|'ctx'|'meta'} */
export function classifyDiffLine(line) {
  if (line.startsWith('@@')) return 'hunk';
  if (line.startsWith('+++') || line.startsWith('---')) return 'meta';
  if (line.startsWith('+')) return 'add';
  if (line.startsWith('-')) return 'del';
  if (line.startsWith('\\')) return 'meta';
  return 'ctx';
}

/** Row model for one unified diff body.
 *  @returns {Array<{kind: 'hunk'|'add'|'del'|'ctx'|'meta', text: string}>} */
export function parseUnifiedDiff(diffText) {
  /** @type {Array<{kind: 'hunk'|'add'|'del'|'ctx'|'meta', text: string}>} */
  const rows = [];
  for (const line of String(diffText ?? '').split('\n')) {
    rows.push({ kind: classifyDiffLine(line), text: line });
  }
  return rows;
}

/** True when the text looks like a unified diff body (has at least one hunk header). */
export function looksLikeUnifiedDiff(diffText) {
  return /@@ -\d+(,\d+)? \+\d+(,\d+)? @@/.test(String(diffText ?? ''));
}

/** CSS class for one parsed diff row (single source for both the string
 *  renderer below and the chunked Canvas renderer in diffCanvas.js). */
export function diffRowClass(kind) {
  return kind === 'hunk' ? 'nf-diff-hunk'
    : kind === 'add' ? 'nf-diff-add'
    : kind === 'del' ? 'nf-diff-del'
    : kind === 'meta' ? 'nf-diff-meta'
    : 'nf-diff-ctx';
}

/** One parsed row → its HTML div (shared by renderDiffHtml and diffCanvas.js). */
export function diffRowHtml(r) {
  return `<div class="nf-diff-row ${diffRowClass(r.kind)}">${esc(r.text) || '&nbsp;'}</div>`;
}

/** Render one unified diff body into colored rows (+ green / − red / hunk header). */
export function renderDiffHtml(diffText) {
  const rows = parseUnifiedDiff(diffText);
  if (!rows.length) return '';
  return `<div class="nf-diff"><pre class="nf-diff-body">${rows.map(diffRowHtml).join('')}</pre></div>`;
}

/** Change-type badge for a file row. */
function changeBadge(changeType) {
  const t = String(changeType || 'modified');
  return `<span class="nf-fc-badge nf-fc-badge-${esc(t)}">${esc(t)}</span>`;
}

/** Source tag: where the diff came from (git recompute vs tool-captured ledger). */
function sourceTag(source) {
  const s = String(source || '');
  if (!s) return '';
  return `<span class="nf-fc-src" title="diff source">${esc(s)}</span>`;
}

/** One file entry: a <details> row — click expands the diff inline (the Flow
 *  Map node detail face keeps its in-place reader).
 *  （2026-10-22 拆面板批：chat 链接行形态（opts.mode==='chat'）随其唯一消费者
 *  退役——聊天面现在只渲染交付气泡顶部的摘要 chip（injectedRows.js），文件列表
 *  迁往左栏面板（diffPanel.js 自建行 DOM）。本函数回到唯一现存面 = 节点详情。）
 *  Render-only module: no DOM listeners here. */
function fileEntryHtml(f) {
  const path = String(f?.path || '(unknown)');
  const added = Number(f?.added) || 0;
  const removed = Number(f?.removed) || 0;
  const counts = `<span class="nf-fc-counts"><span class="nf-fc-added">+${added}</span> <span class="nf-fc-removed">−${removed}</span></span>`;
  const row = `
      <summary class="nf-fc-row">
        ${changeBadge(f?.changeType)}
        <span class="nf-fc-path" title="${esc(path)}">${esc(path)}</span>
        ${counts}
        ${sourceTag(f?.source)}
      </summary>`;
  let body;
  if (typeof f?.diffText === 'string' && f.diffText.length > 0) {
    body = renderDiffHtml(f.diffText);
  } else if (f?.diffText === '' ) {
    body = '<div class="nf-fc-empty">Empty file — no content lines.</div>';
  } else {
    const reason = f?.degradedReason
      ? `Diff not shown (${esc(String(f.degradedReason))}).`
      : 'Diff not available.';
    body = `<div class="nf-fc-empty">${reason} File: <code>${esc(path)}</code></div>`;
  }
  return `
    <details class="nf-fc">${row}
      <div class="nf-fc-body">${body}</div>
    </details>`;
}

/**
 * Render the whole file-changes section (header + file list).
 * Returns '' for payloads without files, so callers can skip mounting.
 * （消费者 = Flow Map 节点详情内联阅读面（diffView.js）；聊天卡面已于
 * 2026-10-22 拆面板批改走摘要 chip + 左栏面板 + Canvas 阅读器。）
 */
export function renderFileChangesHtml(payload) {
  const files = Array.isArray(payload?.files) ? payload.files : [];
  if (files.length === 0) return '';
  const totals = files.reduce((acc, f) => {
    acc.a += Number(f?.added) || 0;
    acc.r += Number(f?.removed) || 0;
    return acc;
  }, { a: 0, r: 0 });
  const head = `Changes · ${files.length} file${files.length === 1 ? '' : 's'} <span class="nf-fc-counts"><span class="nf-fc-added">+${totals.a}</span> <span class="nf-fc-removed">−${totals.r}</span></span>`;
  return `
    <div class="nf-filechanges">
      <div class="fm-detail-sec nf-filechanges-head" data-fc-open="1">${head}</div>
      <div class="nf-filechanges-list">${files.map((f) => fileEntryHtml(f)).join('')}</div>
    </div>`;
}
