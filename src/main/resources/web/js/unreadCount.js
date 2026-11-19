// Shared scroll-pill policy. Callers supply only newly arrived, visible messages;
// the transport does not currently provide stable IDs across replay/remount.
export function countUnread(current, messages, atBottom = false) {
  if (atBottom) return 0;
  return Math.max(0, current) + messages.filter((message) =>
    message && !message.self && message.role !== 'user' && message.role !== 'sys' && message.role !== 'system'
  ).length;
}
