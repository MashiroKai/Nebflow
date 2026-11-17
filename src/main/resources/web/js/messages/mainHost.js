// messages/mainHost.js — desktop host that shows a friend, group or device conversation in the main chat area.
//
// The conversation window is built once, the same for every surface (messages.js renderChatModal). On the mobile
// layer the navigation controller adopts it into a page. On desktop this module mounts it into #main in place of the
// Agent conversation instead of a dialog:
//   - the Agent view (#chat, #input-area, #top-overlays) stays in the DOM, hidden and inert, so its draft, attachments
//     and scroll position are exactly as they were when the conversation is closed;
//   - the identity of the conversation (avatar, name, sub line, group settings) is moved into #header, which keeps its
//     own left and right controls;
//   - the account avatar in the activity bar flips to the Agent face and returns to the Agent when it is clicked.
// Nothing here knows about messages, drafts or the network: messages.js owns the conversation state and passes the
// pieces in. The module is a leaf below messages.js (no import of messages.js or chatSearch.js).

import { flipAvatarToAgent, restoreAvatarAccountFace } from '../activityBar.js';

const ACTIVE_CLASS = 'fm-social-main';
const HOST_CLASS = 'fm-chat-host';
/** Agent-view elements that are hidden and inert while a conversation is shown. */
const AGENT_VIEW_IDS = ['chat', 'input-area', 'top-overlays'];

/**
 * @typedef {object} ActiveHost
 * @property {HTMLElement} overlay
 * @property {HTMLElement} modal
 * @property {HTMLElement} head
 * @property {() => void} onReturn
 * @property {{ convId: string, convName: string }} scope
 * @property {ResizeObserver|null} observer
 */

/** @type {ActiveHost|null} */
let active = null;

/** True when conversations can be hosted in the main area: desktop layer with the main header in place. */
export function canHostInMain() {
  return !document.body.classList.contains('mobile-layer')
    && !!document.getElementById('main')
    && !!document.querySelector('#header .header-center');
}

/** Search scope of the conversation shown in the main area, or null when the Agent is shown. */
export function activeSocialScope() {
  return active ? { convId: active.scope.convId, convName: active.scope.convName } : null;
}

/** Keep the scope name in step with a renamed conversation. */
export function setActiveSocialName(name) {
  if (active) active.scope.convName = String(name || active.scope.convId);
}

/**
 * Show a built conversation window in the main area.
 * @param {{ overlay: HTMLElement, modal: HTMLElement, head: HTMLElement, convId: string, convName: string,
 *           onReturn: () => void }} parts
 */
export function mountInMain(parts) {
  const main = document.getElementById('main');
  const header = document.getElementById('header');
  const center = document.querySelector('#header .header-center');
  if (!main || !header || !center) return;
  releaseHost();

  parts.overlay.classList.add(HOST_CLASS);
  main.appendChild(parts.overlay);
  parts.head.classList.add('fm-main-head');
  center.appendChild(parts.head);
  main.classList.add(ACTIVE_CLASS);
  for (const id of AGENT_VIEW_IDS) {
    const node = document.getElementById(id);
    if (node) node.toggleAttribute('inert', true);
  }

  /** @type {ActiveHost} */
  const host = {
    overlay: parts.overlay,
    modal: parts.modal,
    head: parts.head,
    onReturn: parts.onReturn,
    scope: { convId: parts.convId, convName: parts.convName || parts.convId },
    observer: null,
  };
  active = host;

  // The window clears the floating header the same way the Agent conversation does: its scroll area starts below it.
  const clearHeader = () => {
    const bottom = header.getBoundingClientRect().bottom - main.getBoundingClientRect().top;
    parts.modal.style.setProperty('--fm-head-h', Math.max(0, Math.round(bottom)) + 'px');
  };
  clearHeader();
  if (typeof ResizeObserver === 'function') {
    host.observer = new ResizeObserver(clearHeader);
    host.observer.observe(header);
    host.observer.observe(main);
  }

  flipAvatarToAgent(parts.onReturn);
  relayoutHeader();
}

/** Take the conversation out of the main area and give the Agent view back. */
export function unmountFromMain() {
  if (!active) return;
  releaseHost();
  restoreAvatarAccountFace();
}

/** Detach whatever is mounted without touching the avatar face (shared by unmount and re-mount). */
function releaseHost() {
  const host = active;
  if (!host) return;
  active = null;
  if (host.observer) host.observer.disconnect();
  host.head.remove();
  host.overlay.remove();
  const main = document.getElementById('main');
  if (main) main.classList.remove(ACTIVE_CLASS);
  for (const id of AGENT_VIEW_IDS) {
    const node = document.getElementById(id);
    if (node) node.toggleAttribute('inert', false);
  }
  relayoutHeader();
}

/** The header seats its controls by measuring its centre block; its centre content just changed, so ask it to measure
 *  again (it listens for window resize). */
function relayoutHeader() {
  window.dispatchEvent(new Event('resize'));
}
