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

/** Row model for one unified diff body.
 *  @returns {Array<{kind: 'hunk'|'add'|'del'|'ctx'|'meta', text: string}} */
export function parseUnifiedDiff(diffText) {
  const rows = [];
  for (const line of String(diffText ?? '').split('\n')) {
    if (line.startsWith('@@')) rows.push({ kind: 'hunk', text: line });
    else if (line.startsWith('+++') || line.startsWith('---')) rows.push({ kind: 'meta', text: line });
    else if (line.startsWith('+')) rows.push({ kind: 'add', text: line });
    else if (line.startsWith('-')) rows.push({ kind: 'del', text: line });
    else if (line.startsWith('\\')) rows.push({ kind: 'meta', text: line });
    else rows.push({ kind: 'ctx', text: line });
  }
  return rows;
}

/** True when the text looks like a unified diff body (has at least one hunk header). */
export function looksLikeUnifiedDiff(diffText) {
  return /@@ -\d+(,\d+)? \+\d+(,\d+)? @@/.test(String(diffText ?? ''));
}

/** Render one unified diff body into colored rows (+ green / − red / hunk header). */
export function renderDiffHtml(diffText) {
  const rows = parseUnifiedDiff(diffText);
  if (!rows.length) return '';
  const body = rows.map((r) => {
    const cls = r.kind === 'hunk' ? 'nf-diff-hunk'
      : r.kind === 'add' ? 'nf-diff-add'
      : r.kind === 'del' ? 'nf-diff-del'
      : r.kind === 'meta' ? 'nf-diff-meta'
      : 'nf-diff-ctx';
    return `<div class="nf-diff-row ${cls}">${esc(r.text) || '&nbsp;'}</div>`;
  }).join('');
  return `<div class="nf-diff"><pre class="nf-diff-body">${body}</pre></div>`;
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

/** One file entry: a <details> row (click = expand the diff view). */
function fileEntryHtml(f) {
  const path = String(f?.path || '(unknown)');
  const added = Number(f?.added) || 0;
  const removed = Number(f?.removed) || 0;
  const counts = `<span class="nf-fc-counts"><span class="nf-fc-added">+${added}</span> <span class="nf-fc-removed">−${removed}</span></span>`;
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
    <details class="nf-fc">
      <summary class="nf-fc-row">
        ${changeBadge(f?.changeType)}
        <span class="nf-fc-path" title="${esc(path)}">${esc(path)}</span>
        ${counts}
        ${sourceTag(f?.source)}
      </summary>
      <div class="nf-fc-body">${body}</div>
    </details>`;
}

/**
 * Render the whole file-changes section (header + file list).
 * Returns '' for payloads without files, so callers can skip mounting.
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
      <div class="fm-detail-sec nf-filechanges-head">${head}</div>
      <div class="nf-filechanges-list">${files.map(fileEntryHtml).join('')}</div>
    </div>`;
}
