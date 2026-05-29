// notificationBanner.js — Persistent notification banner (survives session switches)
// Notifications live outside #chat in #notification-banner, so resetChatForActiveSession
// (which does state.dom.chat.innerHTML = '') does NOT clear them.

import state from './state.js';

let notificationIdCounter = 0;

/** Add a notification to the persistent banner.
 *  @param type  'skill' | 'reminder'
 *  @param text  Notification message text
 *  @param opts  { dismissAfter: ms (0 = no auto-dismiss) }
 */
export function addNotification(type, text, opts = {}) {
  const id = 'notif-' + (++notificationIdCounter);

  // Deduplicate: if same type+text already exists, just bump its timestamp
  const existingIdx = state.notifications.findIndex(n => n.type === type && n.text === text);
  if (existingIdx >= 0) {
    state.notifications[existingIdx].time = Date.now();
    renderNotifications();
    return;
  }

  state.notifications.push({
    id,
    type,
    text,
    time: Date.now(),
    dismissAfter: opts.dismissAfter || 0
  });
  renderNotifications();

  if (opts.dismissAfter > 0) {
    setTimeout(() => dismissNotification(id), opts.dismissAfter);
  }
}

export function dismissNotification(id) {
  const el = document.querySelector(`.notif-item[data-id="${id}"]`);
  if (el) {
    el.classList.add('dismissing');
    setTimeout(() => {
      state.notifications = state.notifications.filter(n => n.id !== id);
      renderNotifications();
    }, 250);
  } else {
    state.notifications = state.notifications.filter(n => n.id !== id);
    renderNotifications();
  }
}

export function clearNotifications() {
  state.notifications = [];
  renderNotifications();
}

function renderNotifications() {
  const banner = document.getElementById('notification-banner');
  if (!banner) return;

  if (state.notifications.length === 0) {
    banner.innerHTML = '';
    return;
  }

  // Show the latest 3 notifications (newest first)
  const toShow = state.notifications.slice(-3).reverse();

  let html = '';
  toShow.forEach(n => {
    const iconLabel = n.type === 'skill' ? 'S' : (n.type === 'reminder' ? 'R' : 'N');
    const escapedText = escapeHtml(n.text);
    html += `
      <div class="notif-item" data-id="${escapeHtml(n.id)}">
        <span class="notif-icon ${escapeHtml(n.type)}">${iconLabel}</span>
        <span class="notif-text">${escapedText}</span>
        <button class="notif-close" data-id="${escapeHtml(n.id)}">&times;</button>
      </div>`;
  });

  banner.innerHTML = html;

  // Bind close buttons
  banner.querySelectorAll('.notif-close').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      dismissNotification(btn.dataset.id);
    });
  });
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}
