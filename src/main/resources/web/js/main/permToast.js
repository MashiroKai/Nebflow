// main.js 抽取(FE组件化批次七 2026-09-28):权限 toast 族 模块(行为保持)。
import state from '../state.js';
import { t } from '../i18n.js';

// F4 (#433): global actionable toast for permission cards whose target root
// session is unreachable. Glass panel, no overlay dimming (弹窗禁令). Stack
// top-right; removed on answer or on permissionExpired for the same root sid.
let __permToastHost = null;
export function showGlobalPermissionToast(msg) {
  const sid = msg.sessionId || '';
  if (!__permToastHost) {
    __permToastHost = document.createElement('div');
    __permToastHost.id = 'global-perm-toasts';
    document.body.appendChild(__permToastHost);
  }
  const card = document.createElement('div');
  card.className = 'global-perm-toast';
  card.dataset.rootSid = sid;
  const title = document.createElement('div');
  title.className = 'global-perm-toast-title';
  title.textContent = t('perm.fallbackTitle', { agent: msg.sourceAgent || '?' });
  const body = document.createElement('div');
  body.className = 'global-perm-toast-body';
  body.textContent = `${msg.toolName || ''}${msg.summary ? ' · ' + msg.summary : ''}`;
  const actions = document.createElement('div');
  actions.className = 'global-perm-toast-actions';
  const send = (approved) => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: sid, approved, ...(msg.requestId && { requestId: msg.requestId }) }));
    }
    card.remove();
  };
  const denyBtn = document.createElement('button');
  denyBtn.className = 'global-perm-toast-btn deny';
  denyBtn.textContent = t('perm.fallbackDeny');
  denyBtn.addEventListener('click', () => send(false));
  const okBtn = document.createElement('button');
  okBtn.className = 'global-perm-toast-btn approve';
  okBtn.textContent = t('perm.fallbackApprove');
  okBtn.addEventListener('click', () => send(true));
  actions.appendChild(denyBtn);
  actions.appendChild(okBtn);
  card.appendChild(title);
  card.appendChild(body);
  card.appendChild(actions);
  __permToastHost.appendChild(card);
  // Safety net: the backend auto-denies after 5 min — retire the card by then.
  setTimeout(() => card.remove(), 5 * 60 * 1000);
}

export function dismissGlobalPermissionToasts(rootSid) {
  if (!__permToastHost) return;
  __permToastHost.querySelectorAll('.global-perm-toast').forEach(el => {
    if (!rootSid || el.dataset.rootSid === rootSid) el.remove();
  });
}
