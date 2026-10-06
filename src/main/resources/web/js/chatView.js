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

// ── Detail-face route: the ONE session-layer decision (分层展示批, 2026-10-06) ──
//
// Author ruling (17:55 令): the SAME tool-activity data renders under TWO view
// faces. The MAIN chat flow keeps the single-line streaming work-line form
// (398dd72ac, zcode-484); a SUBAGENT/DELEGATE PANEL (bgAgentPopup /
// flowAgentPopup — the overlay monitoring a child session) shows the tool
// blocks EXPANDED instead — command/output/result detail fully open, and the
// running detail line replaced by that full expansion (「面板视图不吃细节行，
// 全量展开替代之」; reference form = git show b3c0851c8^ 展开块样式).
//
// THE CONVERGENCE POINT: the decision is made ONCE, here, at view
// construction — a panel-face view stamps its chat container with `.nf-panel`.
// Nothing in the render chain may re-derive "is this a panel?" from view ids,
// session-id prefixes or DOM walks: every consumer (CSS-able state, chat.js
// builders, turnGroup tuck, persistence restore) branches on THIS marker via
// isPanelFace() below. Deliberately OPT-IN — chatSearchFloat's throwaway
// 'search-float' view is also non-primary but must keep the MAIN face (it
// renders collapsed-turn slices and runs expandGroupContaining), so "not
// primary" alone is NOT the panel criterion.
//
// Style branch, not a data split: both faces share the same data source
// (toolStart/toolEnd/toolArgDelta + persisted history) and the same tool-block
// builder (chat.js renderTool/renderToolPending + persistence.js replay) — the
// panel differs only in the style-state its blocks settle into.
export function isPanelFace(chatEl) {
  return !!(chatEl && chatEl.classList && chatEl.classList.contains('nf-panel'));
}

// ── Motion scope: the ONE window-role decision (UI-G, author 2026-10-03) ───
//
// Author ruling 19:59: 「只需要主Nebula有流式输出，气泡动画等效果，subagent窗口
// 要尽量克制，节省资源。」 Every motion switch in the render layer reads THIS
// predicate — no per-call-site special-casing. A view that renders a subagent /
// flow popup is built with its own id ('bgagent-…', 'flow-…', 'search-float'),
// only the main Nebula conversation is 'primary'.
//
// The switch has exactly TWO application points, both driven by this one
// predicate (nothing else may special-case a window role):
//
//   1. RENDER-TIME (CSS): a non-primary view's chat container gets the
//      `nf-static` class in the constructor below. ONE CSS rule
//      (chat.css `.nf-static, .nf-static *`) then zeroes every `animation`
//      and every `transition` inside that container — no per-element
//      handling, no per-feature opt-out list.
//   2. EVENT-TIME (JS): modules that would arm a timer / rAF read
//      `view.motionEnabled()` before arming it. Consumers:
//        · workline.js    — dots live-seconds setInterval, exit setTimeout,
//                           enter-late hold, terminal roll.
//        · popArtifacts.js — the expand/collapse height transition's timer.
//      `view.motionEnabled()` is a method (not a free function import) so the
//      leaf modules stay leaves: workline.js imports i18n + utils only, and
//      popArtifacts.js reaches it through the existing `state.getActiveView`
//      accessor — neither gains a chatView.js import edge.
export function isMainWindow(view) {
  return !!view && view.id === 'primary';
}

// ── ChatView class ──────────────────────────────────────────────────────

export class ChatView {
  /**
   * @param {string} id  — 'primary' (future: additional view IDs for multi-instance)
   * @param {object} dom — pre-resolved DOM refs for this window's chat subtree
   * @param {object} [opts] opts.panelFace: panel-face views (bgagent/flow
   *        popups) opt in here; see the 分层展示批 route block above. This is
   *        THE single place the face decision is made (constructor stamps the
   *        `.nf-panel` marker once; every consumer reads isPanelFace).
   */
  constructor(id, dom, opts = {}) {
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

    // ── Motion role (UI-G 2026-10-03) ──
    // The single window-role switch every render module reads through
    // `view.motionEnabled()` (see isMainWindow above). Subagent / flow popup
    // views render static: no CSS animation, no periodic timers, no rAF loops.
    // Bound once so it can be passed around unbound.
    this.motionEnabled = () => isMainWindow(this);
    // Application point 1 (CSS): mark the non-primary container `nf-static`
    // ONCE, here, so the single chat.css rule kills every animation and every
    // transition inside it — the popup keeps its static content (克制 ≠ 破功)
    // but runs none of the main window's motion. Done at construction rather
    // than per-render so no render path can forget it.
    if (!isMainWindow(this) && this.dom && this.dom.chat) {
      this.dom.chat.classList.add('nf-static');
    }
    // 分层展示批 detail-face route (see the block above): panel views stamp
    // their container once — same single-construction shape as the UI-G
    // marker. Main face = default, zero extra class.
    this.panelFace = !!opts.panelFace;
    if (this.panelFace && this.dom && this.dom.chat) {
      this.dom.chat.classList.add('nf-panel');
    }
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
