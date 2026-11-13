/** Precise durable phase labels; an interrupted run is not a business failure. */
export function taskExecutionPresentation(task) {
  if (!task?.executionStatus) return null;
  const phase = String(task.executionStatus);
  const interrupted = phase === 'interrupted' || phase === 'pending-recovery';
  const labels = {
    starting: 'Starting', running: 'Running', finished: 'Finished',
    cancelled: 'Cancelled', failed: 'Failed',
    interrupted: 'Interrupted · state uncertain',
    'pending-recovery': 'Interrupted · state uncertain',
  };
  const label = interrupted
    ? 'Interrupted · state uncertain' + (task.incomplete ? ' · Result collection abnormal' : '')
    : task.incomplete ? 'Result collection abnormal' : (labels[phase] || phase);
  const state = interrupted ? 'pending-recovery'
    : task.incomplete || phase === 'failed' ? 'error'
      : phase === 'finished' || phase === 'cancelled' ? 'done' : 'active';
  const reason = String(task.terminalReason || '');
  return { label, state, reason, phase };
}

/** Per-task durable ordering; transport/request counters are never task cursors. */
export function taskRevision(row) {
  const value = row && row.committedRevision;
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

/** Snapshot rows are complete state; live revisions are invalidations, not replay data. */
export function observeTaskRevision(previous, incoming, snapshot = false) {
  const revision = taskRevision(incoming);
  const before = taskRevision(previous);
  if (revision === null) return { accept: before === null, resync: before !== null };
  if (before !== null && revision < before) return { accept: false, resync: true };
  if (before !== null && revision === before) return { accept: snapshot, resync: false };
  const runChanged = !!(previous?.runId && incoming?.runId && previous.runId !== incoming.runId);
  return { accept: true, resync: !snapshot && (before === null || revision > before + 1 || runChanged) };
}
