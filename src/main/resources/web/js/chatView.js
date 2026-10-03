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
import { cleanupCardIframes } from './cardRegistry.js';

// ── Active view (set by ws.js before handler dispatch) ──────────────────
// Module-level mutable binding. ES module imports are live: when
// setActiveView changes this, all importers see the new value.
export let activeView = null;

export function setActiveView(v) { activeView = v; }

// P2-4 cycle cut: utils.js and cardRegistry.js read the active view through
// this state.js accessor instead of statically importing this module.
state.getActiveView = () => activeView;

// ── ChatView class ──────────────────────────────────────────────────────

export class ChatView {
  /**
   * @param {string} id  — 'primary' (future: additional view IDs for multi-instance)
   * @param {object} dom — pre-resolved DOM refs for this window's chat subtree
   */
  constructor(id, dom) {
    this.id = id;
    this.dom = dom;
    this.sessionId = null;
    this.mounted = true;

    // ── Visibility gating ──
    // The primary view is always visible. Popup views (flow / bg-agent) start
    // hidden: while visible === false, ws.js dispatches their streaming events
    // with view=null so chat.js skips DOM rendering entirely (global state
    // buffers in state.js still accumulate — nothing is lost).
    this.visible = true;
    // Set when events were skipped while hidden. openStepPopup checks this to
    // force a full history refresh instead of showing stale/partial DOM.
    this.dirtyWhileHidden = false;

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
      // activeSubAgents moved to state.sessionBgAgents (global, keyed by sessionId)
      // so agentDone events are processed even when the session isn't displayed.
      scrollSnapped: true,
      toolStreamText: '',
      toolStreamToolName: '',
    };

    // ── Input state ──
    this.pendingAttachments = [];
    this.inputDrafts = {};      // sessionId -> { text, attachments, skillMode }
    // @deprecated ⑤-A4（作者裁定 2026-09-12）：IME 组字态现由 imeGuard.js 的
    // 元素级判定承担（`el.dataset.imeComposing`，写入点唯一 = bindImeGuard）。
    // 本字段保留一版不删（本批未穷尽潜在读者面），新代码勿再读写。
    this.composing = false;

    // ── Skill mode ──
    this.skillMode = false;
    this.skillModeName = '';
    this.skillModeDesc = '';
    this.skillModeArgHint = '';

    // ── Compact mode ──
    this.compactMode = false;

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
      scrollSnapped: true,
      toolStreamText: '',
      toolStreamToolName: '',
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

  /** Reset input mode state (skill mode, ask mode, compact mode). */
  resetInputModes() {
    this.skillMode = false;
    this.skillModeName = '';
    this.skillModeDesc = '';
    this.skillModeArgHint = '';
    this.compactMode = false;
    this.stream.askMode = false;
    // Reset DOM state — paddingLeft and indicator visibility from a previous
    // session's ask/skill/compact mode must not persist into the new session.
    if (this.dom.input) {
      this.dom.input.style.paddingLeft = '';
    }
    const askEl = document.getElementById('ask-indicator');
    const skillEl = document.getElementById('skill-indicator');
    const compactEl = document.getElementById('compact-indicator');
    if (askEl) askEl.classList.remove('show');
    if (skillEl) skillEl.classList.remove('show');
    if (compactEl) compactEl.classList.remove('show');
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
    // Clear chat area — release card iframe observers/browsing contexts first
    if (this.dom.chat) {
      cleanupCardIframes(this.dom.chat);
      this.dom.chat.innerHTML = '';
    }
    // Clear queue bar — will be re-rendered by the event listener in input.js
    if (this.dom.queueBar) {
      this.dom.queueBar.innerHTML = '';
      this.dom.queueBar.classList.remove('visible');
    }
    // Restore draft for the new session
    this.restoreDraft(sessionId);
    // Notify queue bar to re-render for the new session
    window.dispatchEvent(new CustomEvent('queuebar-refresh', { detail: { sessionId } }));
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
    // Render attachment preview for restored image/file attachments
    if (this.dom.attPreview) this.dom.attPreview.innerHTML = '';
    if (this.pendingAttachments.length > 0) {
      import('./chat.js').then(({ renderAttachmentPreview }) => {
        setActiveView(this);
        renderAttachmentPreview();
      });
    }
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
 * Initialize the primary ChatView instance and register it.
 * Called from main.js after DOM is ready.
 *
 * The chatViews registry supports future multi-view expansion —
 * additional views can be registered via chatViews.<id> = new ChatView(...).
 */
export function initChatView(primaryDom) {
  chatViews.primary = new ChatView('primary', primaryDom);
}
