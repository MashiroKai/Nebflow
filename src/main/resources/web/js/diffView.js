// diffView.js — browser wiring for the per-node file-change diff section.
//
// Mount point: the Flow Map node detail panel (flowMapArchive.renderDetail).
// For a completed node it lazily fetches the persisted per-node change report
// (written by the engine at completion; 404 = nothing to show) and appends a
// collapsible "Changes" section under the result block. All rendering is pure
// HTML from diffRender.js; styles live in css/diffView.css.

import { authHeaders } from './flowHelpers.js';
import { API } from './nodeData.js';
import { renderFileChangesHtml } from './diffRender.js';

/** Fetch the node's file-change report. Returns the parsed payload, or null on
 *  404 / any failure (the section is optional decoration — never error-surface). */
export async function fetchFileChanges(projectName, nodeId) {
  try {
    const resp = await fetch(API.nodeFileChanges(projectName, nodeId), { headers: authHeaders() });
    if (!resp.ok) return null;
    const data = await resp.json();
    return data && Array.isArray(data.files) ? data : null;
  } catch (_) {
    return null;
  }
}

/**
 * Mount the file-changes section into the node detail body.
 *
 *  @param {any} ctx detail-layer context (uses ctx.detailBody, ctx.detailRenderSeq —
 *                   same shapes flowMapArchive's own result upgrade uses)
 *  @param {number} seq render generation at call time — a late response for a
 *                   replaced/closed detail must not mount (swapCurrent semantics)
 *  @param {string} nodeId
 *  @param {string} projectName project the report is fetched under — the REAL
 *                   root project name (⑫B chain-detail-diff fix: the delegate
 *                   scope key itself has no REST face; flowMapArchive.diffProjectFor
 *                   resolves it). '' = no project context ⇒ the section is skipped
 *                   EXPLICITLY (diff 段不适用 — no network call, no silent 404).
 */
export async function mountFileChangesSection(ctx, seq, nodeId, projectName) {
  if (!ctx || !ctx.detailBody || !nodeId) return;
  if (!projectName) return; // 无项目上下文：diff 段不适用（调用方已显式判定，双保险）
  const payload = await fetchFileChanges(projectName, nodeId);
  // Stale-generation guard: the detail was re-rendered for another node while
  // the fetch was in flight — drop the response.
  if (!payload || payload.files.length === 0) return;
  if (seq !== ctx.detailRenderSeq) return;
  const host = ctx.detailBody;
  if (!host.isConnected) return;
  // Re-entry guard: replace any previous section instead of stacking.
  const existing = host.querySelector('.nf-filechanges');
  if (existing) existing.remove();
  host.insertAdjacentHTML('beforeend', renderFileChangesHtml(payload));
}
