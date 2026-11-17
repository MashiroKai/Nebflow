// Read-only projection, not the final §零 schema or a migration.
// TODO: reconcile fields, length counting and document boundaries with §零.
// TODO: validate visibility/ownership before using counts as an entry badge.

/** Project a ScheduledTask todo without modifying or truncating its source. */
export function todoToDecisionCard(task) {
  if (!task || task.kind !== 'todo' || task.id == null || String(task.id) === '') return null;
  // Ask/permission mirrors are not authoritative request state.
  if (/^(ask|perm)-/.test(String(task.name || ''))) return null;
  const detail = String(task.detail || '');
  return {
    id: String(task.id),
    title: String(task.name || task.content || ''),
    summary: String(task.content || ''),
    content: detail,
    cover: null,
    type: detail ? 'detail' : 'direct',
    response: null,
    status: task.completedAt == null ? 'unresolved' : 'completed',
    source: { type: 'todo', id: String(task.id), sessionId: task.sessionId || null },
  };
}

/** Count unique unresolved IDs in an already permission-filtered card set. */
export function unresolvedDecisionCardCount(cards) {
  return new Set(cards.filter(card => card && card.status === 'unresolved' && card.id != null)
    .map(card => String(card.id))).size;
}
