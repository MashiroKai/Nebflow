// chatView.js — Session-scoped chat view instance.
//
// Each window that displays a chat session holds one ChatView instance.
// The view owns its DOM references, streaming state, input state, and
// pagination — so handlers route by sessionId to the right view instead
// of relying on a global singleton + temporary swap.
//
// activeView is set once by ws.js before dispatching handlers (no save/
// restore). Rendering functions in chat.js read activeView to know which
// window's DOM + stream state to target. This is NOT the old swap
// mechanism — state lives on the view object, never copied to globals.

import state from './state.js';

// ── Active view (set by ws.js before handler dispatch) ──────────────────
// Module-level mutable binding. ES module imports are live: when
// setActiveView changes this, all importers see the new value.
export let activeView = null;

export function setActiveView(v) { activeView = v; }
export function getActiveView() { return activeView; }

// ── ChatView class ──────────────────────────────────────────────────────

export class ChatView {
  /**
   * @param {string} id  — 'primary' | 'secondary' | ...
   * @param {object} dom — pre-resolved DOM refs for this window's chat subtree
   */
  constructor(id, dom) {
    this.id = id;
    this.dom = dom;
    this.sessionId = null;
    this.mounted = true;

    // ── Streaming state ──
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

    // ── Input state ──
    this.pendingAttachments = [];
    this.inputDrafts = {};      // sessionId -> { text, attachments, skillMode }
    this.composing = false;

    // ── Skill mode ──
    this.skillMode = false;
    this.skillModeName = '';
    this.skillModeDesc = '';
    this.skillModeArgHint = '';

    // ── Slash autocomplete ──
    this.slashMatches = [];
    this.slashSelectedIndex = -1;

    // ── Input history navigation (per-view) ──
    this.historyIndex = -1;
    this.historyDraft = '';

    // ── Voice recognition ──
    this.recognition = null;

    // ── Pagination ──
    this.pagination = {
      offset: 0,
      total: 0,
      hasMore: false,
      loading: false,
      pendingInitialLoad: false,
    };

    // ── Send lock (prevents rapid double-send within a single view) ──
    this.isSending = false;
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

  /** Reset input mode state (skill mode, ask mode). */
  resetInputModes() {
    this.skillMode = false;
    this.skillModeName = '';
    this.skillModeDesc = '';
    this.skillModeArgHint = '';
    this.stream.askMode = false;
  }

  /** Reset all view state for a fresh session load. */
  resetAll() {
    this.resetStream();
    this.resetPagination();
    this.resetInputModes();
    this.pendingAttachments = [];
    this.slashMatches = [];
    this.slashSelectedIndex = -1;
    this.historyIndex = -1;
    this.historyDraft = '';
  }

  /** Switch this view to display a different session. */
  setSession(sessionId) {
    if (this.sessionId === sessionId) return;
    // Save current input draft for the session we're leaving
    if (this.sessionId) {
      this.saveDraft(this.sessionId);
    }
    this.sessionId = sessionId;
    this.resetAll();
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
    const skillMode = this.skillMode ? {
      name: this.skillModeName,
      desc: this.skillModeDesc,
      argHint: this.skillModeArgHint,
    } : null;
    if (text || attachments.length > 0 || skillMode) {
      this.inputDrafts[sessionId] = {
        text,
        attachments: JSON.parse(JSON.stringify(attachments)),
        skillMode,
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
 */
export function initChatViews(primaryDom, secondaryDom) {
  chatViews.primary = new ChatView('primary', primaryDom);
  chatViews.secondary = new ChatView('secondary', secondaryDom);
  chatViews.secondary.mounted = false;
}
