// Read-only projection, not the final §零 schema or a migration.
// TODO: reconcile fields, length counting and document boundaries with §零.
// TODO: validate visibility/ownership before using counts as an entry badge.

// Temporary project tag reserved for MVP cards; not a finalized schema.
export const DECISION_CARD_PROJECT = '__decision_card_mvp__';

// Temporary code-point counting until the author confirms the counting algorithm.
export function validateNewDecisionCard({ summary = '', content = '' }) {
  const summaryLength = Array.from(summary).length;
  const contentLength = Array.from(content).length;
  return { summaryValid: summaryLength >= 40 && summaryLength <= 100, contentValid: contentLength <= 300 };
}

/** Project a ScheduledTask todo without modifying or truncating its source. */
export function todoToDecisionCard(task) {
  if (!task || task.kind !== 'todo' || task.id == null || String(task.id) === '') return null;
  // Ask/permission mirrors are not authoritative request state.
  if (/^(ask|perm)-/.test(String(task.name || ''))) return null;
  let detail = String(task.detail || '');
  let summary = String(task.content || '');
  if (task.project === DECISION_CARD_PROJECT) {
    try {
      const payload = JSON.parse(detail);
      if (typeof payload.summary !== 'string' || typeof payload.content !== 'string') return null;
      summary = payload.summary; detail = payload.content;
    } catch { return null; }
  }
  return {
    id: String(task.id),
    title: String(task.name || task.content || ''),
    summary,
    content: detail,
    cover: null,
    type: detail ? 'detail' : 'direct',
    response: null,
    status: task.completedAt == null ? 'unresolved' : 'completed',
    source: { type: 'todo', id: String(task.id), sessionId: task.sessionId || null, project: task.project || null, creator: task.creator || null },
  };
}

/** Conservative scope restriction, not a verified authorization check. */
export function currentSessionDecisionCards(rows, sessionId) {
  if (typeof sessionId !== 'string' || !sessionId) return [];
  return rows.filter(row => row && typeof row.sessionId === 'string' && row.sessionId === sessionId)
    .map(todoToDecisionCard).filter(Boolean);
}

export function shouldBlockDecisionTurn(rows, sessionId) {
  if (!sessionId) return false;
  return rows.some(row => row && row.kind === 'todo' && row.project === DECISION_CARD_PROJECT && row.sessionId === sessionId && row.completedAt == null);
}

/** Count unique unresolved IDs in an already permission-filtered card set. */
export function unresolvedDecisionCardCount(cards) {
  return new Set(cards.filter(card => card && card.status === 'unresolved' && card.id != null)
    .map(card => String(card.id))).size;
}
