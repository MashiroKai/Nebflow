// contacts.js — Contacts panel (friends-messaging-spec §3.1).
// Search-by-NebLink-ID (submit-style, no incremental search), 「新的朋友」
// request inbox (incoming + outgoing), friend list. Badges: pending incoming
// count on #contacts-btn (pure-badge model, [U3]).
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { getNeblinkState } from './neblink.js';
import { setActivityBadge, openLoginModal } from './activityBar.js';
import { onMessage } from './ws.js';
import * as api from './friendsApi.js';
import { openChatWithFriend } from './messages.js';

let friends = [];
let incoming = [];
let outgoing = [];
let requestsExpanded = false;
let lastSearchAt = 0;
let searchResult = null;   // null | {found:false} | {found:true,...}
let verifyFor = null;      // neblinkId awaiting verification-note input
let sentTo = new Set();    // neblinkIds with a pending outgoing request (this load)

function loggedIn() { return !!getNeblinkState().loggedIn; }

export function getFriendList() { return friends; }

// ── Data ─────────────────────────────────────────────────
async function refresh() {
  if (!loggedIn()) { friends = []; incoming = []; outgoing = []; render(); return; }
  try {
    const data = await api.getFriends();
    friends = data.friends || [];
    incoming = (data.incoming || []);
    outgoing = (data.outgoing || []);
    sentTo = new Set(outgoing.filter(r => r.status === 'pending').map(r => (r.to?.neblinkId || '').toLowerCase()));
  } catch { /* keep last known */ }
  render();
  updateBadge();
}

function updateBadge() {
  const n = incoming.filter(r => r.status === 'pending').length;
  setActivityBadge('contacts-btn', loggedIn() ? n : 0, t('contacts.ariaRequests', { n }));
}

// ── Render ───────────────────────────────────────────────
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function avatarEl(person, size) {
  const a = el('span', `fm-avatar fm-avatar-${size}`);
  if (person.avatarUrl) {
    const img = document.createElement('img');
    img.src = person.avatarUrl;
    img.alt = '';
    a.appendChild(img);
  } else {
    a.textContent = (person.name || person.neblinkId || '?').trim().charAt(0).toUpperCase();
  }
  a.setAttribute('aria-hidden', 'true');
  return a;
}

function render() {
  const body = document.getElementById('fm-contacts-body');
  if (!body) return;
  body.innerHTML = '';
  updateBadge();

  if (!loggedIn()) {
    const empty = el('div', 'fm-login-empty');
    empty.appendChild(el('div', 'fm-login-text', t('messages.loginRequired')));
    const btn = el('button', 'glass-control fm-login-btn', t('messages.login'));
    btn.addEventListener('click', () => openLoginModal());
    empty.appendChild(btn);
    body.appendChild(empty);
    return;
  }

  body.appendChild(buildSearch());

  // 「新的朋友」entry
  const pendingN = incoming.filter(r => r.status === 'pending').length;
  const nf = el('div', 'fm-nf-entry');
  nf.setAttribute('role', 'button');
  nf.setAttribute('tabindex', '0');
  const nfIcon = el('span', 'fm-nf-icon');
  nfIcon.innerHTML = '<i data-lucide="user-plus"></i>';
  nf.appendChild(nfIcon);
  nf.appendChild(el('span', 'fm-nf-label', t('contacts.newFriends')));
  if (pendingN > 0) nf.appendChild(el('span', 'fm-row-badge', String(pendingN)));
  nf.appendChild(el('span', 'fm-nf-chevron', ''));
  nf.querySelector('.fm-nf-chevron').innerHTML = `<i data-lucide="${requestsExpanded ? 'chevron-down' : 'chevron-right'}"></i>`;
  const toggleReq = () => { requestsExpanded = !requestsExpanded; render(); };
  nf.addEventListener('click', toggleReq);
  nf.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleReq(); } });
  body.appendChild(nf);

  if (requestsExpanded) body.appendChild(buildRequests());

  // Friend list
  const list = el('div', 'fm-friend-list');
  list.setAttribute('role', 'listbox');
  list.setAttribute('aria-label', t('panel.contacts'));
  if (friends.length === 0) {
    list.appendChild(el('div', 'fm-empty', t('contacts.empty')));
  } else {
    for (const f of friends) list.appendChild(friendRow(f));
  }
  body.appendChild(list);
  createIconsIn(body);
}

function friendRow(f) {
  const row = el('div', 'fm-row fm-friend-row');
  row.setAttribute('role', 'option');
  row.setAttribute('tabindex', '0');
  row.setAttribute('aria-selected', 'false');
  row.appendChild(avatarEl(f, 36));
  const meta = el('div', 'fm-row-meta');
  meta.appendChild(el('div', 'fm-row-name', f.name || f.neblinkId));
  meta.appendChild(el('div', 'fm-row-sub', f.neblinkId));
  row.appendChild(meta);
  const open = () => openChatWithFriend(f);
  row.addEventListener('click', open);
  row.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); open(); } });
  return row;
}

// ── Search (submit-style, 1s min interval) ───────────────
function buildSearch() {
  const wrap = el('div', 'fm-search');
  const input = document.createElement('input');
  input.className = 'fm-search-input';
  input.type = 'text';
  input.placeholder = t('contacts.searchPlaceholder');
  input.autocomplete = 'off';
  const btn = el('button', 'glass-control fm-search-btn', t('contacts.search'));
  const submit = async () => {
    const q = input.value.trim();
    if (!q) return;
    const now = Date.now();
    if (now - lastSearchAt < 1000) return; // anti-crawl min interval
    lastSearchAt = now;
    verifyFor = null;
    try {
      searchResult = await api.lookupUser(q);
    } catch { searchResult = { found: false }; }
    render();
  };
  btn.addEventListener('click', submit);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); submit(); } });
  wrap.appendChild(input);
  wrap.appendChild(btn);
  if (searchResult) wrap.appendChild(buildResultCard());
  return wrap;
}

function buildResultCard() {
  const card = el('div', 'fm-result-card');
  const r = searchResult;
  if (!r.found) {
    card.appendChild(el('div', 'fm-empty', t('contacts.notFound')));
    return card;
  }
  card.appendChild(avatarEl(r, 36));
  const meta = el('div', 'fm-row-meta');
  meta.appendChild(el('div', 'fm-row-name', r.name || r.neblinkId));
  meta.appendChild(el('div', 'fm-row-sub', r.neblinkId));
  card.appendChild(meta);

  if (r.self) {
    card.appendChild(el('span', 'fm-status-text', t('contacts.self')));
    return card;
  }
  const alreadyFriend = friends.some(f => f.neblinkId.toLowerCase() === (r.neblinkId || '').toLowerCase());
  const pending = sentTo.has((r.neblinkId || '').toLowerCase());
  if (alreadyFriend || pending) {
    card.appendChild(el('span', 'fm-status-text', t('contacts.pendingVerification')));
    return card;
  }
  if (verifyFor === r.neblinkId) {
    // Verification-note input (WeChat-style, ≤50 chars, optional)
    const box = el('div', 'fm-verify-box');
    const input = document.createElement('input');
    input.className = 'fm-verify-input';
    input.maxLength = 50;
    input.placeholder = t('contacts.verifyMessagePlaceholder');
    const sendBtn = el('button', 'glass-control', t('messages.send'));
    sendBtn.addEventListener('click', async () => {
      sendBtn.disabled = true;
      try {
        await api.sendFriendRequest(r.neblinkId, input.value.trim());
        sentTo.add(r.neblinkId.toLowerCase());
      } catch { /* keep state */ }
      verifyFor = null;
      render();
    });
    box.appendChild(input);
    box.appendChild(sendBtn);
    card.appendChild(box);
    setTimeout(() => input.focus(), 0);
  } else {
    const addBtn = el('button', 'glass-control fm-add-btn', t('contacts.addFriend'));
    addBtn.addEventListener('click', () => { verifyFor = r.neblinkId; render(); });
    card.appendChild(addBtn);
  }
  return card;
}

// ── Requests (「新的朋友」) ───────────────────────────────
function buildRequests() {
  const wrap = el('div', 'fm-requests');
  if (incoming.length === 0 && outgoing.length === 0) {
    wrap.appendChild(el('div', 'fm-empty', t('contacts.noRequests')));
    return wrap;
  }
  for (const rq of incoming) wrap.appendChild(requestRow(rq, 'in'));
  if (outgoing.length > 0) {
    wrap.appendChild(el('div', 'fm-req-group', t('contacts.sentRequests')));
    for (const rq of outgoing) wrap.appendChild(requestRow(rq, 'out'));
  }
  return wrap;
}

function requestRow(rq, dir) {
  const person = dir === 'in' ? rq.from : rq.to;
  const row = el('div', 'fm-row fm-req-row');
  row.appendChild(avatarEl(person || {}, 36));
  const meta = el('div', 'fm-row-meta');
  meta.appendChild(el('div', 'fm-row-name', person?.name || person?.neblinkId || ''));
  meta.appendChild(el('div', 'fm-row-sub', rq.note || person?.neblinkId || ''));
  row.appendChild(meta);

  const status = rq.status || 'pending';
  if (status !== 'pending') {
    row.appendChild(el('span', 'fm-status-text',
      status === 'accepted' ? t('contacts.accepted') : t('contacts.declined')));
    return row;
  }
  if (dir === 'out') {
    row.appendChild(el('span', 'fm-status-text', t('contacts.pendingVerification')));
    return row;
  }
  const accept = el('button', 'glass-control fm-req-accept', t('contacts.accept'));
  accept.addEventListener('click', async (e) => {
    e.stopPropagation();
    accept.disabled = true;
    try { await api.acceptFriendRequest(rq.requestId); } catch { /* keep */ }
    await refresh();
    window.dispatchEvent(new CustomEvent('fm-friends-changed'));
  });
  const decline = el('button', 'fm-req-decline', t('contacts.decline'));
  decline.addEventListener('click', async (e) => {
    e.stopPropagation();
    decline.disabled = true;
    try { await api.declineFriendRequest(rq.requestId); } catch { /* keep */ }
    await refresh();
  });
  const btns = el('span', 'fm-req-btns');
  btns.appendChild(accept);
  btns.appendChild(decline);
  row.appendChild(btns);
  return row;
}

// ── Wiring ───────────────────────────────────────────────
let initialized = false;

export function initContacts() {
  if (initialized) return;
  initialized = true;

  onMessage('friend_event', (msg) => {
    if (msg.event === 'friend_request' || msg.event === 'friend_accepted') refresh();
  });
  // P3 error surface — friendsApi dispatches on auth failure / network error.
  window.addEventListener('fm-auth-required', () => { openLoginModal(); });
  window.addEventListener('fm-network-error', () => { window.__showToast?.(t('messages.networkError'), 'error'); });
  window.addEventListener('fm-friends-changed', () => refresh());

  // Refresh when the panel becomes active (covers login-state changes).
  const panel = document.getElementById('panel-contacts');
  if (panel) {
    new MutationObserver(() => {
      if (panel.classList.contains('active')) refresh();
    }).observe(panel, { attributes: true, attributeFilter: ['class'] });
  }

  render();
  if (loggedIn()) refresh();
}

/** Used by messages.js for the not-friend gate (§3.3/R8). */
export function isFriendUser(userId) {
  return friends.some(f => f.userId === userId);
}
