// chatView.js — Session-scoped chat view instance.
//
// Each window (primary / secondary) that displays a chat session holds one
// ChatView instance. The view owns its DOM references, streaming state, and
// pagination — so handlers route by sessionId to the right view instead of
// relying on a global singleton + temporary swap.
//
// IMPORTANT: the secondary panel is a *multi-purpose* display surface — it may
// show a chat session today, but could later host a mind-map, a diff viewer,
// or other content. ChatView is therefore designed as a *mountable* view:
//   - The panel container (#secondary-panel) is NOT owned by ChatView.
//   - ChatView only owns the chat-specific DOM subtree + its state.
//   - When the panel switches to a non-chat mode, the ChatView is "detached"
//     (its state preserved) and the panel shows something else.
//
// This file currently defines the class + instance registry. The actual
// migration of handlers from the swap-based model happens in stages B–E.

import state from './state.js';

// ── ChatView class ──────────────────────────────────────────────────────

export class ChatView {
  /**
   * @param {string} id  — 'primary' | 'secondary'
   * @param {object} dom — pre-resolved DOM refs for this window's chat subtree
   */
  constructor(id, dom) {
    this.id = id;

    // DOM refs scoped to this view. Passed in so the caller controls which
    // physical elements back this view (primary window vs secondary panel).
    this.dom = dom;

    // The session this view is currently displaying. null = showing nothing.
    this.sessionId = null;

    // ── Streaming state (previously global singletons in state.js) ──
    // These are the fields that ws.js used to swap. Now each view has its own.
    this.stream = {
      aiText: '',
      currentAiBubble: null,
      thinkingText: '',
      currentThinkingBubble: null,
      currentAskBubble: null,
      askAnswerText: '',
      askMode: false,
      agentBubbles: {},
      activeAgentId: null,
      activeSubAgents: {},
      scrollSnapped: true,
    };

    // ── Pagination state (previously historyOffset/_secHistoryOffset etc.) ──
    this.pagination = {
      offset: 0,
      total: 0,
      hasMore: false,
      loading: false,
      pendingInitialLoad: false,
    };

    // ── Input state (per-view, not shared) ──
    this.pendingAttachments = [];
    this.inputDrafts = {};      // sessionId -> { text, attachments }
    this.skillMode = false;
    this.skillModeName = '';
    this.skillModeDesc = '';
    this.skillModeArgHint = '';

    // ── Whether this view is currently mounted (visible) in the DOM ──
    // The secondary view can be unmounted when the panel shows non-chat content.
    this.mounted = true;
  }

  /** Reset streaming state — called when switching to a different session. */
  resetStream() {
    this.stream = {
      aiText: '',
      currentAiBubble: null,
      thinkingText: '',
      currentThinkingBubble: null,
      currentAskBubble: null,
      askAnswerText: '',
      askMode: false,
      agentBubbles: {},
      activeAgentId: null,
      activeSubAgents: {},
      scrollSnapped: true,
    };
  }

  /** Reset pagination state — called when loading a new session's history. */
  resetPagination() {
    this.pagination = {
      offset: 0,
      total: 0,
      hasMore: false,
      loading: false,
      pendingInitialLoad: true,
    };
  }

  /** Switch this view to display a different session. */
  setSession(sessionId) {
    if (this.sessionId === sessionId) return;
    // Save current input draft for the session we're leaving
    if (this.sessionId && this.dom.input) {
      this.saveDraft(this.sessionId);
    }
    this.sessionId = sessionId;
    this.resetStream();
    this.resetPagination();
    // Clear chat area
    if (this.dom.chat) {
      this.dom.chat.innerHTML = '';
    }
    // Restore draft for the new session
    this.restoreDraft(sessionId);
  }

  // ── Draft management ──────────────────────────────────────────────────

  saveDraft(sessionId) {
    if (!sessionId || !this.dom.input) return;
    const text = this.dom.input.value;
    const attachments = this.pendingAttachments;
    if (text || attachments.length > 0) {
      this.inputDrafts[sessionId] = {
        text,
        attachments: JSON.parse(JSON.stringify(attachments)),
      };
    } else {
      delete this.inputDrafts[sessionId];
    }
  }

  restoreDraft(sessionId) {
    if (!this.dom.input) return;
    const draft = this.inputDrafts[sessionId];
    if (draft) {
      this.dom.input.value = draft.text || '';
      this.pendingAttachments = draft.attachments || [];
    } else {
      this.dom.input.value = '';
      this.pendingAttachments = [];
    }
    this.dom.input.style.height = 'auto';
  }

  // ── Convenience: sync this view's stream fields back to global state ──
  // TEMPORARY bridge during the staged migration. Legacy handlers still read
  // state.currentAiBubble etc.; while a handler hasn't been migrated yet, we
  // push the view's values into the global state before calling it, and pull
  // them back after. This is simpler than swapping 11 fields manually.
  pushToGlobal() {
    state.currentAiBubble = this.stream.currentAiBubble;
    state.aiText = this.stream.aiText;
    state.thinkingText = this.stream.thinkingText;
    state.currentThinkingBubble = this.stream.currentThinkingBubble;
    state.currentAskBubble = this.stream.currentAskBubble;
    state.askAnswerText = this.stream.askAnswerText;
    state.askMode = this.stream.askMode;
    state.agentBubbles = this.stream.agentBubbles;
    state.activeAgentId = this.stream.activeAgentId;
    state.activeSubAgents = this.stream.activeSubAgents;
    state.scrollSnapped = this.stream.scrollSnapped;
  }

  pullFromGlobal() {
    this.stream.currentAiBubble = state.currentAiBubble;
    this.stream.aiText = state.aiText;
    this.stream.thinkingText = state.thinkingText;
    this.stream.currentThinkingBubble = state.currentThinkingBubble;
    this.stream.currentAskBubble = state.currentAskBubble;
    this.stream.askAnswerText = state.askAnswerText;
    this.stream.askMode = state.askMode;
    this.stream.agentBubbles = state.agentBubbles;
    this.stream.activeAgentId = state.activeAgentId;
    this.stream.activeSubAgents = state.activeSubAgents;
    this.stream.scrollSnapped = state.scrollSnapped;
  }
}

// ── Instance registry ───────────────────────────────────────────────────

/** Map of view id -> ChatView instance. */
export const chatViews = {};

/**
 * Find which ChatView is currently displaying the given session.
 * Returns the ChatView, or null if no view shows this session.
 */
export function findViewBySessionId(sessionId) {
  if (!sessionId) return null;
  for (const view of Object.values(chatViews)) {
    if (view.mounted && view.sessionId === sessionId) return view;
  }
  return null;
}

/**
 * Initialize both ChatView instances and register them.
 * Called from main.js after DOM is ready.
 *
 * @param {object} primaryDom  — DOM refs for the primary window
 * @param {object} secondaryDom — DOM refs for the secondary panel's chat subtree
 */
export function initChatViews(primaryDom, secondaryDom) {
  chatViews.primary = new ChatView('primary', primaryDom);
  chatViews.secondary = new ChatView('secondary', secondaryDom);
  // The primary view starts mounted; secondary starts unmounted (panel hidden).
  chatViews.secondary.mounted = false;
}
