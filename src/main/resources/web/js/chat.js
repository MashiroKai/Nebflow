// chat.js — Chat rendering module for Nebflow
// All DOM manipulation for messages, bubbles, tool cards, option boxes, and status.

import state, { AGENT_PALETTE } from './state.js';
import { key } from './branding.js';
// 时制（12h/24h）：偏好、格式化与热区绑定的唯一属主 = timeFormat.js（主对话框
// / 设备对话框 / 好友对话框三面共享同一偏好与同一实现；本模块只消费）。
// formatFooterTime = footer-time 批（2026-10-06）的分层 footer label：
// 今天仍为纯时钟（formatHm），更早按本地日历日加日期层（昨天/星期X/M月D日/跨年）。
import { formatHm, formatFooterTime, toggleTimeFormat, bindTimeToggle } from './timeFormat.js';
import { activeView, setActiveView, findViewBySessionId, chatViews } from './chatView.js';
import { renderMarkdownWithMath, escapeHtml, appendTextWithMentions, buildToolDetail, buildDelegatePromptHtml, attachToolClick, smartScroll, playSpinner, stopSpinner, localizeToolLabel, localizeToolSummary, renderHighlightedContent, highlightCode, createMsgCopyButton, createIconsIn, statusIcon, isNearBottom, shouldFollowBottom, NEAR_BOTTOM_PX } from './utils.js';
import { renderWithRegistry } from './cardRegistry.js';
import { t } from './i18n.js';
import { sendWs, onMessage } from './ws.js';
import { askSourceLabel, removePendingAsk } from './askPending.js';
import { renderRefBlock, normalizeTaskRef, parseTaskReturnText, buildTaskRefLine, CLOSE_X_SVG } from './reference.js';
// quote-message 一期（spec §7）：主聊天区右键引用 —— 菜单/引用条/引用块/跳转收口在
// msgQuote.js 单模块（chat.js 只挂点，不复制逻辑）。
import { attachQuoteMenu, renderQuoteBlock, quoteFromRef, nextChatMsgId, syncQuoteJumpStates, scheduleQuoteButtonMount } from './msgQuote.js';
// file-diff → 委托结果行（bug #58 修 2026-10-09）：注入行展开体里的「Changes」段
// 渲染器复用 diffRender.js（零依赖纯 HTML 模块，与 Flow Map 节点详情同一实现；
// 样式 = css/diffView.css 既有类，chat 面只追加容器级规则）。
import { renderFileChangesHtml } from './diffRender.js';
import { openFileChangesCanvas } from './diffCanvas.js';
// 令牌头（2026-09-20 收尾批）：`/api/tts` 服务端已加门 ⇒ 本调用点必须带 Authorization。
// 复用既存的同族 helper（flowHelpers.authHeaders，全仓 6 处同族实例），**不新造**令牌读取
// 机制。依赖方向安全：flowHelpers.js 只 import './branding.js'，无环。
import { authHeaders } from './flowHelpers.js';
// Conditional-display widget (modelcfg Phase 1 ruling ②-A): showOptions'
// shouldShow/updateVisibility DOM mechanics live in depVisibility.js now, shared
// with the /model panel. The visibility predicate and the answer-reset stay here.
import { createDepVisibility } from './depVisibility.js';
// AskUser 附件行（顶层 `attachments` ⇒ 整次提问层的可点击附件行）。askuser-attach-align
// 批（2026-10-07 作者令「跟 Pop 保持一致」）后打开动作**直接走** Canvas 打开腿
// `openAttachmentInCanvas`（⇒ `workspace-open-item` ⇒ `canvas.openWorkspaceItem`，
// 与 Pop 文件卡同一条通路；禁第二套开法——禁新 tabId 前缀 / 禁自拼 URL / 禁自造
// 预览）。`previewLocalPath` 的好友/设备面认领闸（含 `EXCLUDED_ITEM_TYPES = {html}`
// 的不受信内容排除）不再为本行把关——本地面信任级 = Pop 同级。
// askuser-attach-align 批（2026-10-07 作者令「未注册类型走下载兜底＝与 Pop/Canvas 同判」）：
// 附件行的类型认领判据读 viewer 注册表自身（禁第二张扩展名表；本件已在 bundle 图内）。
import { itemTypeForFileName } from './fileViewers.js';
// Runtime work-line (stream-ux §二.1.1): the single-line badge slot that shows
// the running tool / thinking and rolls to the next item. Leaf module (imports
// i18n.js only) — the call sites below are the EXISTING render paths, so the
// engine event stream (thinkingDelta / toolStart / toolEnd) is consumed
// unchanged.
import { startWorklineItem, doneWorklineItem, failWorklineItem, thinkingWorklineItem, thinkingWorklineDone, removeWorkline, beginRound, stampRound, worklineToolDetail } from './workline.js';
// Root agent DISPLAY name (single read point — rootName.js). Leaf module
// (state.js only) ⇒ no cycle. Used only on display faces; mechanism keys stay
// the literal "Nebula".
import { rootDisplayName, isRootNamed } from './rootName.js';

// stream-ux §二.1.1: the work-line shows the FIRST line of the tool's card
// label (same string the user reads on the card), so the single-line badge and
// the card never disagree about which tool is running.
function worklineLabel(rawLabel) {
  const parts = localizeToolLabel(rawLabel).split('\n', 2);
  return parts[0];
}

/** （c）面 · 作者 2026-10-08 裁定：subagent 弹窗的**在飞过程行**走 9.19 裸行
 *  形态。置位点唯一（bgAgentPopup.js ensureStepView → view.nfBareProcessRows
 *  = true）；主窗（primary）与 flow 弹窗不置位 ⇒ turn-line 剧场原样。本谓词
 *  是 chat.js 各在飞渲染点的唯一分流读点（main.js 经导出同名函数读同一标志）。 */
function bareProcessRows(view) {
  return !!(view && view.nfBareProcessRows);
}
export { bareProcessRows };

/** ③ 完成翻转（toolline-detail 批 2026-10-06）：工具完成勾选时 turn 行翻转为
 *  单行结果摘要——「<工具名> — <本地化 summary>」。数据 = toolEnd 帧前端
 *  已持有字段（label/summary），零新协议。summary 为空（旧引擎帧/无摘要）时
 *  返回 '' ⇒ doneWorklineItem 回退运行中 label（原行为逐字保留）。 */
function toolLineResultText(label, summary) {
  const sum = localizeToolSummary(summary || '', label);
  if (!sum) return '';
  const base = worklineLabel(label) || '';
  return (base ? base + ' — ' : '') + sum;
}

/** THE single row-append path for this module. #491 (author 2026-10-05
 *  「Pop的文件和媒体，要按时间顺序显示，不要总是在文字的最下方」): appends are
 *  pure doc-order — whatever a render path builds lands where the conversation
 *  put it, and nothing re-anchors afterwards. (The UI-D turn-end re-anchor
 *  this helper used to re-run on every append is gone; time order = message
 *  order is now the structure, not a post-pass.) Funnelling through one helper
 *  still means no render path can grow a second append semantic. */
function appendChatRow(chat, row) {
  chat.append(row);
}

// Permission-card escalation targets → shield label keys (permshield F1): the
// upgrade toast must name the mode exactly like the header shield does, so both
// read from the shared `bypass.*` labels instead of inventing a third wording.
const UPGRADE_MODE_LABEL = { 'auto-edits': 'bypass.autoEdits', 'auto-all': 'bypass.autoAll' };

// ---------- Time format preference (12h / 24h toggle) ----------// 实现已抽到 timeFormat.js（三面共享）。此处保留同名再次导出，模块公开面不缩水
// （全仓无外部 importer，纯兼容保留）。Legacy spelling 'nebflow:timeFormat' is
// still normalized into the storage key by branding.js at module init.
export { formatHm, toggleTimeFormat };

// ---------- Voice TTS player ----------
// Module-level singleton. Manages sequential playback of <voice> blocks:
// fetches WAV from /api/tts, plays them in order, supports click-to-replay
// and a mute toggle (persisted in localStorage).
const VoicePlayer = {
  queue: [],
  processing: false,
  muted: localStorage.getItem('voiceMuted') === 'true',
  _current: null, // { audio, url, element, resolve }
  _cache: new Map(), // text → Promise<blobUrl>（流式预取 + 缓存，有界 LRU）
  _cacheLimit: 20,   // evicted entries get URL.revokeObjectURL — blob URLs pin memory until revoked

  /** Insert into the bounded LRU cache. Evicts the oldest entry and revokes
   *  its blob URL once the (possibly in-flight) fetch resolves. */
  _cacheSet(text, promise) {
    if (this._cache.has(text)) this._cache.delete(text);
    this._cache.set(text, promise);
    while (this._cache.size > this._cacheLimit) {
      const [oldestKey, oldestPromise] = this._cache.entries().next().value;
      this._cache.delete(oldestKey);
      Promise.resolve(oldestPromise).then(url => { if (url) URL.revokeObjectURL(url); }).catch(() => {});
    }
  },

  /** 流式预取：检测到完整 voice 块时立即发起 TTS 请求（并行，不等结果）。 */
  prefetch(text) {
    if (!text || this._cache.has(text)) return;
    this._cacheSet(text, this._fetchTts(text));
  },

  /** 调用后端 TTS API，返回 blobUrl 的 Promise。 */
  async _fetchTts(text) {
    try {
      const resp = await fetch('/api/tts', {
        method: 'POST',
        // 令牌（2026-09-20 收尾批）：服务端路由已套 `withAuth` ⇒ 无令牌 403。
        // 未登录 webui（localStorage 无 token）时 `authHeaders()` 返回 {} ⇒ 请求不带
        // Authorization，服务端 403 ⇒ 下方 `!resp.ok` 走既有静默降级（返回 null），不新增崩溃面。
        headers: { 'Content-Type': 'application/json', ...authHeaders() },
        body: JSON.stringify({ text }),
      });
      if (!resp.ok) return null;
      const blob = await resp.blob();
      return URL.createObjectURL(blob);
    } catch (e) {
      return null;
    }
  },

  /** 获取音频 URL：有缓存用缓存（可能还在 in-flight），没有就发请求。 */
  async _getAudioUrl(text) {
    if (this._cache.has(text)) {
      // LRU touch — move to newest so frequently replayed clips survive.
      const p = this._cache.get(text);
      this._cache.delete(text);
      this._cache.set(text, p);
      return await p;
    }
    const p = this._fetchTts(text);
    this._cacheSet(text, p);
    return await p;
  },

  /** Add a voice block to the playback queue. */
  enqueue(text, element) {
    this.queue.push({ text, element });
    if (!this.processing) this._processQueue();
  },

  /** Process queue items sequentially. Playback is ordered, but fetches are parallel. */
  async _processQueue() {
    this.processing = true;
    let cancelled = false;
    while (this.queue.length > 0 && !cancelled) {
      const item = this.queue.shift();
      const result = await this._playItem(item);
      if (result === 'cancelled') cancelled = true;
    }
    this.processing = false;
    if (this.queue.length > 0) this._processQueue();
  },

  /** Play a single voice block. */
  async _playItem({ text, element }) {
    if (this.muted) return 'done';
    try {
      const url = await this._getAudioUrl(text);
      if (!url) return 'done';
      const result = await new Promise(resolve => {
        const audio = new Audio(url);
        this._current = { audio, url, element, resolve };
        if (element) element.classList.add('playing');
        audio.onended = () => resolve('done');
        audio.onerror = () => resolve('done');
        audio.play().catch(() => resolve('done'));
      });
      return result;
    } catch (e) {
      return 'done';
    } finally {
      if (element) element.classList.remove('playing');
      this._current = null;
    }
  },

  /** Cancel current playback and clear the queue. */
  _cancel() {
    this.queue = [];
    if (this._current) {
      this._current.audio.pause();
      // Revoke the in-flight blob URL and drop its cache entry so the revoked
      // URL can never be replayed from cache (a replay simply re-fetches).
      const cur = this._current;
      const text = cur.element ? cur.element.textContent : null;
      if (text) this._cache.delete(text);
      if (cur.url) URL.revokeObjectURL(cur.url);
      cur.resolve('cancelled');
    }
  },

  /** Click-to-replay. */
  replay(element) {
    this._cancel();
    this.enqueue(element.textContent, element);
  },

  /** Toggle mute. */
  toggleMute() {
    this.muted = !this.muted;
    localStorage.setItem('voiceMuted', String(this.muted));
    if (this.muted) this._cancel();
    sendWs({ type: 'setVoiceMuted', muted: this.muted });
    return this.muted;
  },
};

// SVG icons for the voice toggle button
const VOICE_SVG_ON = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/></svg>';
const VOICE_SVG_OFF = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>';

// Initialize voice toggle buttons (runs after DOM is ready — module scripts are deferred)
document.querySelectorAll('.voice-toggle').forEach(btn => {
  if (VoicePlayer.muted) {
    btn.classList.add('muted');
    btn.innerHTML = VOICE_SVG_OFF;
  }
  btn.addEventListener('click', () => {
    const muted = VoicePlayer.toggleMute();
    btn.classList.toggle('muted', muted);
    btn.innerHTML = muted ? VOICE_SVG_OFF : VOICE_SVG_ON;
  });
});

// Unlock audio on first user interaction (browsers block autoplay without gesture)
let _audioUnlocked = false;
function _unlockAudio() {
  if (_audioUnlocked) return;
  _audioUnlocked = true;
  const s = new Audio();
  s.play().then(() => s.pause()).catch(() => {});
}
document.addEventListener('click', _unlockAudio, { once: true });
document.addEventListener('keydown', _unlockAudio, { once: true });

// ---------- Agent color assignment ----------
export function getAgentColor(agentId) {
  if (!state.agentColors[agentId]) {
    state.agentColors[agentId] = AGENT_PALETTE[state.agentColorIdx % AGENT_PALETTE.length];
    state.agentColorIdx++;
  }
  return state.agentColors[agentId];
}

// ---------- Status bar ----------
export function setStatus(text) {
  const { statusText, statusWrap } = activeView.dom;
  if (statusText) statusText.textContent = text || '';
  if (statusWrap) statusWrap.classList.add('on');
  playSpinner();
}

export function clearStatus() {
  const { statusWrap } = activeView.dom;
  if (statusWrap) statusWrap.classList.remove('on');
  stopSpinner();
}

// ---------- Freeze visuals (work schedule, freeze-schedule spec §3.2) ------
// 2026-08-24 ruling: the standalone .frozen-status bar is RETIRED — the input
// bar itself carries the frozen state (ice-blue material + placeholder). Only
// the resume-clock formatter survives (shared by the event path and the
// schedule-window local path in main.js).
export function formatResumeClock(resumeAt) {
  if (!resumeAt) return '';
  const d = new Date(resumeAt);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

export function renderRetryStatus(msg) {
  const { chat } = activeView.dom;
  let el = document.getElementById('retry-status');
  if (!el) {
    el = document.createElement('div');
    el.id = 'retry-status';
    el.className = 'retry-status';
    chat.appendChild(el);
  }
  el.textContent = msg;
  el.style.display = 'block';
  smartScroll();
}

export function clearRetryStatus() {
  const el = document.getElementById('retry-status');
  if (el) el.style.display = 'none';
}

// ---------- Busy toggle (per-session) ----------

/** Reflect the WebSocket connection state onto the send button. The send button
 *  doubles as the connection indicator: when disconnected it goes grey/red
 *  (.disconnected) to signal sends are unavailable. Called from ws.js on
 *  connect/disconnect and from setBusy/clearBusy. */
export function refreshSendButtonState() {
  const btn = activeView?.dom?.sendBtn || document.getElementById('send-btn');
  if (!btn) return;
  btn.classList.toggle('disconnected', !state.connected);
}

export function setBusy(sessionId) {
  if (sessionId) state.busySessionIds.add(sessionId);
  window.dispatchEvent(new CustomEvent('session-busy', { detail: { sessionId, busy: true } }));
  if (activeView && activeView.sessionId === sessionId) {
    const { sendBtn, stopBtn, statusWrap } = activeView.dom;
    if (sendBtn) sendBtn.style.display = 'none';
    if (stopBtn) stopBtn.style.display = 'flex';
    if (statusWrap) statusWrap.classList.add('on');
  }
}

export function clearBusy(sessionId) {
  state.busySessionIds.delete(sessionId);
  // freezetimeout B2: 终态（done/error/interrupted/timeout/…）或真死收口时，把「仍在
  // 处理」呈现行一并收掉——本函数是全部终态路径的必经点，所以挂在这里而不是各调用点。
  removeStillProcessingNotice(sessionId);
  window.dispatchEvent(new CustomEvent('session-busy', { detail: { sessionId, busy: false } }));
  if (activeView && activeView.sessionId === sessionId) {
    const { input, sendBtn, stopBtn, statusWrap } = activeView.dom;
    if (sendBtn) sendBtn.style.display = 'flex';
    if (stopBtn) stopBtn.style.display = 'none';
    if (statusWrap) statusWrap.classList.remove('on');
    // 不夺进行中的输入焦点（2026-09-15 作者现场报 · 件②）：本函数由全部 turn 终态帧
    // 调用（clearBusyFor ← done/error/interrupted/timeout/maxTokens/compactFailed，
    // 以及 sessionBusy{busy:false}），而引擎在「AskUser 卡 park 住 turn」时**成对**
    // 发 Done + sessionBusy{busy:false}（AgentFinishTurn.finishTurnCont；2026-09-25
    // 行号引用修正：原 AgentActor.scala:3028-3059 已随 turn 收尾族迁移漂移），InteractionHub
    // 又在发卡片前先发 roundComplete（InteractionHub.scala:145-152）⇒ 卡片与终态帧
    // 落在同一时间窗。此处若无条件 focus()，用户正在 AskUser 卡输入框（或任意别的
    // 可写元素）里打的字会被夺焦、续打落到主输入框。
    // 语义：只在「无进行中的输入」（焦点在 body / 非可写元素 / 本输入框）时移交焦点，
    // 既有「turn 结束聚焦主输入框」行为保持不变——不整体删除焦点管理。
    const focused = document.activeElement;
    const isWritable = !!focused && (focused.tagName === 'TEXTAREA' || focused.tagName === 'INPUT' ||
      (focused instanceof HTMLElement && focused.isContentEditable === true));
    if (input && !(isWritable && focused !== input)) input.focus();
    refreshSendButtonState();
  }
}

// ---------- User bubble ----------
/**
 * Render the user's own message row.
 * @param {string} text
 * @param {Array} attachments
 * @param {number} [timestamp]
 * @param {{msgId?: string}} [opts]  quote-message 一期（spec §3.6/§3.4）：msgId =
 *   本行的稳定 id（用户发送腿传 clientMessageId，与引擎帧同键；其余调用点缺省
 *   生成 c-<rand>）。行挂 data-msg-id = 引用跳转的唯一寻址面。
 */
export function renderUserBubble(text, attachments, timestamp, opts) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row user';
  row.dataset.msgId = (opts && opts.msgId) || nextChatMsgId();
  // quote-message 一期：带引用发送 ⇒ 气泡上方引用块（微信形态）。数据单点 =
  // 附件里的 chat-message ref（live/排队/草稿恢复共用同一形状 ⇒ 三个入路零
  // 额外处理）；该 ref 不进气泡内的 ref 卡循环（引用块是它唯一的消费面）。
  const quoteAtt = (attachments || []).find(a => a && a.type === 'ref' && a.refType === 'chat-message');
  if (quoteAtt) {
    const quote = quoteFromRef(quoteAtt);
    if (quote) row.appendChild(renderQuoteBlock(quote));
  }

  // Text bubble (separate)
  if (text) {
    const bubble = document.createElement('div');
    // stream-ux (2026-10-03): the user's own bubble enters iMessage-style —
    // one message, one pop (scale + elastic settle), same animation family as
    // the assistant bubbles. Opt-in class (history replay stays static).
    // UI-G: main window only — a subagent / flow popup view renders the same
    // bubble with no animation class at all (its container also carries
    // `.nf-static`; this keeps the class off its DOM too).
    bubble.className = activeView.motionEnabled() ? 'bubble user nf-pop' : 'bubble user';
    const t = document.createElement('div');
    // Plain-text face with mention spans (mention-render batch): identical
    // single-text-node output when nothing matches — user text never
    // enters markdown.
    appendTextWithMentions(t, text);
    bubble.appendChild(t);
    row.appendChild(bubble);
  }

  // Attachment faces. #491 主题三 (author 2026-10-05 12:14, 【已裁决】#1):
  // media/file attachments leave the bubble — they render the POP faces as an
  // INDEPENDENT card row right after this row (AC-S2/S3/S4). Ref-type
  // attachments keep their in-bubble reference card. The old inline
  // att-img/att-file-tag bubbles are gone from the live path.
  const mediaAtts = [];
  (attachments || []).forEach(att => {
    if (att && (att.type === 'ref' || att.type === 'taskRef')) {
      // quote-message 一期：引用态 ref 的唯一消费面 = 上方引用块（不进 ref 卡
      // 循环，否则同一引用渲染两遍）。
      if (att.type === 'ref' && att.refType === 'chat-message') return;
      const bubble = document.createElement('div');
      bubble.className = 'bubble user att-bubble';
      // #303 v1.1 + (2026-08-27 打回注入块收敛): ALL task-return references -
      // unified type:'ref' AND the legacy type:'taskRef' from old
      // history/queue restores - normalize into the same Reference and render
      // through renderRefBlock(message), which emits the one-line
      // `#<任务号> <任务标题>` form for tasks. One renderer, no dual styles.
      // Ref nodes need more width than compact file tags, so the bubble is
      // widened via .att-ref-bubble.
      bubble.classList.add('att-ref-bubble');
      const ref = att.type === 'ref' ? att : normalizeTaskRef(att);
      if (ref) bubble.appendChild(renderRefBlock(ref, { mode: 'message' }));
      row.appendChild(bubble);
      return;
    }
    mediaAtts.push(att);
  });

  // Timestamp + copy button (unified v1.2 footer pill)
  const ts = timestamp || Date.now();
  row.appendChild(createMsgFooterBadge(ts, text));
  // quote-message 一期（spec §2.1）：用户文本行右键 = 复制 + 引用（好友窗 msgmenu
  // 同壳 showPopupMenu；引用走 msgQuote.startQuote 替换语义）。
  attachQuoteMenu(row, {
    kind: 'text', text,
    sessionId: activeView.sessionId, messageId: row.dataset.msgId,
    senderRole: 'user', senderName: t('chat.quoteSenderMe'), ts,
  });

  appendChatRow(chat, row);
  // The independent media/file card row (same .row.pop-artifact family the Pop
  // faces use) — time order = message order: user row, then its cards, then
  // whatever the conversation appends next (AC-S4's DOM shape).
  const mediaRow = buildUserMediaRow(mediaAtts);
  if (mediaRow) {
    // 用户附件点击统一批（2026-10-08）：live 发送时行先于后端落盘渲染，item 无
    // path —— 媒体整面点击只能落到 pathless 灯箱、文件卡点击是死的。把行登记进
    // popArtifacts 的待补表，`attachmentsSaved` 帧（后端 ui.json 落盘后回传）到达
    // 后用同一套 builders 原位重渲：item 带 path ⇒ 点击 = openPopArtifact 单点
    // （与 Pop 三修 ② 整面点击开 Canvas、与历史恢复腿三者同构）。
    registerUserMediaRow(activeView.sessionId, mediaRow, mediaAtts);
    appendChatRow(chat, mediaRow);
  }
  // quote-message 一期：可点性收敛（引用块挂载时目标行可能尚未渲染 ⇒ 追加后
  // 统一重判 data-quote-jump；未命中 ⇒ 降级注记，无死指针）。
  syncQuoteJumpStates(chat);
  // UNCONDITIONAL by author ruling (2026-09-11 A-branch): the user's own
  // message always brings the viewport back to the bottom — the near-bottom
  // judgement must NOT gate this site. Do not "converge" it.
  chat.scrollTop = chat.scrollHeight;
  return { type: 'user', text, timestamp: ts, attachments: (attachments || []).map(a => ({ type: a.type, name: a.name, preview: a.preview })) };
}

// ---------- 用户附件落盘路径回传（attachmentsSaved） ----------
// 用户附件点击统一批（2026-10-08）：后端把附件落盘（uploads/<sid>/…，或命中本机
// 原文件）并写 ui.json 后，向请求方连接回传 {type:'attachmentsSaved', sessionId,
// attachments:[{name,type,path}]}。本订阅按会话把对应 live 行原位重渲——openPopArtifact
// 单点开 Canvas（popArtifacts.patchUserMediaRow 的注释承载全部契约：顺序对齐 /
// name 防错位 / path null 不补）。会话级帧：用户已切走的会话被 ws.js :646 过滤丢弃
// ——该会话重入时由历史恢复腿（persistence.buildUserAttachmentRows，自带 path）接管。
// 旧后端不发此帧 ⇒ 订阅永不触发 ⇒ 行为与改前一致（无回归面）。
onMessage('attachmentsSaved', (msg) => {
  if (msg && msg.sessionId) patchUserMediaRow(msg.sessionId, msg.attachments);
});

// ---------- Injected bubble (task P+Q: tool-injected prompts & result notifications) ----------
// Mail/Delegate/SubTask/Skill/Flow/ExternalEvent injections arrive as WS
// {type:"user", injected:true, source} and are persisted as UiMessage.User
// with source. Rendered as a light-blue bubble on the user side (right),
// visually distinct from the user's own green bubble.

/** Map backend injection source → display label. Sources are tool/protocol
 *  names (proper nouns) — no i18n needed. Unknown sources are capitalized.
 *
 *  task / dispatch（Q2-B2，2026-09-11 任务分发器收件规则批）：后端对分发器收件的
 *  两种入口权威定名——`task` = Task 入口（Task 工具 / Mail(→project) / 重入 /
 *  spawn 首条 prompt），`dispatch` = 回流通知（DispatchNotify）。二者显式登记，
 *  **不靠首字母大写兜底**（兜底对多词源名/大小写变体不设防）。 */
const INJECTED_SOURCE_LABELS = {
  mail: 'Mail', delegate: 'Delegate', subtask: 'SubTask', skill: 'Skill',
  ask: 'Ask', flow: 'Flow', tool: 'Tool', api: 'API',
  task: 'Task', dispatch: 'Dispatch',
  // bluebubble 批（2026-09-12）：后端 `InjectionAttribution.BackendNamedSources`
  // 是唯一定名源（`src/main/scala/nebflow/agent/protocol.scala`）。system /
  // background 此前靠 `charAt(0).toUpperCase()` 兜底——偶然正确但不受保护
  // （兜底对多词源名/大小写变体不设防，且后端加新源会静默以错误标签展示），
  // 故显式登记。登记面 ⊇ 后端集合由 `InjectionSourceContractSpec` 硬门守住。
  // `node` 不在本表：它在 injectedSourceLabel 里有专用格式分支（NODE · 项目 ·
  // 节点 · 状态），spec 认「表项 ∪ 显式分支」为已登记。
  // `deviceMail`（device-mail 批，2026-09-15）同 `node`：专用 i18n 分支
  // （「来自 <from_device> 的 Nebula」，sender 携带 from_device），故不改本表——
  // 契约门 InjectionSourceContractSpec 的显式分支集合同样认它。
  system: 'System', background: 'Background',
  // b64 批（2026-09-13）：链级摘要投根通道（NodeEngine.deliverChainSummary，
  // source="chain"，`FlowMapStore.ChainSummarySource`）。后端自定名 ⇒ 必须显式登记
  // （禁首字母大写兜底，契约门 InjectionSourceContractSpec）。
  // ── 全降级列表态批（2026-09-16，作者裁定「全部降级列表态」）后的**改接口径** ──
  // 引擎侧链腿**已停发**（零投主对话 ⇒ 无即时气泡、不进 LLM 上下文）：本表项不再有
  // live 帧来源。**保留登记（不删）**的两条硬理由：
  //   ① **存量历史行仍在渲染**（非死码）：宿主 sessions 面现取 ≈49 处 `source:"chain"`（随轮转漂移），
  //      且不带 `header` 键（宿主建早于 header 批）⇒ 逐行都走本表项 → 标签 "Chain"；
  //   ② 契约门 `InjectionSourceContractSpec` ② 要求前端登记面 ⊇
  //      `InjectionAttribution.BackendNamedSources`（仍含 "chain"）；删表项须连带删
  //      后端词表 + NotificationHeader.KindLabels 两处既有 pin（未取侧，见批报告）。
  // 链级聚合信息的**现役承载面** = 链级列表/明细面（Flow Map 归档面板
  // `flowMapArchive.js`：链条目 → 成员行 → 详情窗按需取结果全文），不再经本表。
  chain: 'Chain',
};

/** Map backend eventType → display suffix for the source label.
 *  Shown as 'SOURCE · EventType' in the injected bubble header.
 *  `cancelled`（全降级列表态批 2026-09-16 补齐）：链级状态段本批拓三元后
 *  `FlowMapStore.ChainSummaryEventCancelled` 取值为 `cancelled`。**登记项**：既有
 *  shape 靠 `charAt(0).toUpperCase()` 兜底得 `Cancelled`（字节同值），此处显式登记以
 *  去掉兜底依赖；与节点腿 `NODE_STATUS_LABELS.cancelled='CANCELED'` 的**大小写差异是
 *  既存口径**（节点腿全大写、通用面 Title case），本批不合并两表（合并会改节点腿既有
 *  呈现，超出裁定面）。 */
const EVENT_TYPE_LABELS = {
  completed: 'Completed', failed: 'Failed', cancelled: 'Cancelled',
  crashed: 'Crashed',
  trigger: 'Triggered', inject: 'Injected',
  info: 'Info', result: 'Result', interrupt: 'Interrupt',
  follow_up: 'Follow-up', parallel: 'Parallel',
};

/** Node 完成通知状态段（NODE · 项目 · 节点 · 状态）：状态显示为全大写值
 *  （COMPLETED / FAILED / CANCELED）；未知状态原样大写。 */
const NODE_STATUS_LABELS = {
  completed: 'COMPLETED', failed: 'FAILED', cancelled: 'CANCELED',
};

function nodeStatusLabel(eventType) {
  return NODE_STATUS_LABELS[eventType] || String(eventType).toUpperCase();
}

/** Build the source label text, optionally combining with sender and eventType.
 *  e.g. source='mail', sender='Manager', eventType='result' → 'Mail · Manager · Result'
 *  sender is optional (backward compatible): absent → 'SOURCE · EventType'.
 *  sourceTeam (optional, backward compatible): present → the message came from a
 *  Team agent; the label shows the team path instead of the SOURCE prefix,
 *  e.g. 'nebflow-project/Backend · Result'.
 *  intake (optional, mailbadge batch 2026-09-13 — author ruling「必须显示 MAIL」,
 *  option C): the **intake-channel discriminant** the backend stamps next to
 *  `source` when the injection arrived through a specific receipt channel
 *  (`Mail(address="project:…")` at the dispatcher face ⇒ `intake='mail'`, while
 *  `source` stays 'task' — the bridge's consumption accounting key, unchanged by
 *  author decree). The intake value comes from the same backend-named source
 *  vocabulary, so it resolves through the same explicitly-registered table. It
 *  takes **presentation priority**; absent ⇒ the fallback path is byte-identical
 *  to before (old history rows without the field, node face, non-project Mail,
 *  dispatch notifications all render exactly as before). */
export function injectedSourceLabel(source, eventType, sender, sourceTeam, intake) {
  // 未知/畸形 source 防御（全降级列表态批 2026-09-16，J6「未知 source 不得异常」）：
  // 非字符串来源（历史脏行 / 未来新源的畸形值）先串化，禁让下方 `charAt`/`slice`
  // 抛异常把整条注入行渲染打断；空值仍走上行早退（逐字节不变）。**登记读数**：
  // 探针覆盖 'chain'（存量历史源）/ 'unknown-source' / '' / null / undefined / 数字
  // ⇒ 六例零异常（见批证据 30-liststate-readings.json 的 `j6Probe.cases`）。
  if (source != null && typeof source !== 'string') source = String(source);
  if (sourceTeam != null && typeof sourceTeam !== 'string') sourceTeam = String(sourceTeam);
  if (!source && !sourceTeam) return '';
  // Node 完成通知专用格式（唯一 Node 类注入消息，source="node" 仅 NodeEngine
  // deliverToNebula 发出）：NODE · <项目名> · <节点名> · <状态>。后端把项目名
  // 与节点名打包在 sender = "<projectName>/<nodeName>"（路径约定，同 sourceTeam
  // team/agent 惯例）；旧历史行 sender="node"（无 '/'）→ 优雅降级为
  // 'NODE · <状态>'。状态段走全大写 nodeStatusLabel（COMPLETED/FAILED/CANCELED）。
  // 不改其他消息类型格式。
  if (source === 'node') {
    const parts = ['NODE'];
    if (sender) {
      const sep = sender.indexOf('/');
      if (sep > 0 && sep < sender.length - 1) {
        parts.push(sender.slice(0, sep), sender.slice(sep + 1));
      }
    }
    if (eventType) parts.push(nodeStatusLabel(eventType));
    return parts.join(' · ');
  }
  const parts = [];
  // 跨设备 Nebula 邮件（device-mail 批，2026-09-15）：后端 source='deviceMail'
  // （`DeviceMail.SourceDeviceMail`，唯一发射点 = DeviceMailInbox 的会话注入）
  // ⇒ 标签 = i18n「来自 <from_device> 的 Nebula」双语（sender 携带 from_device）。
  // 复用同一徽章位/同一蓝气泡样式；eventType 段（INFO）照常追加。
  if (source === 'deviceMail') {
    parts.push(t('deviceMail.fromDevice', { device: sender || '?', agent: rootDisplayName() }));
  } else if (sourceTeam) {
    parts.push(sender ? `${sourceTeam}/${sender}` : sourceTeam);
  } else {
    // 收件判别字段优先（缺席 ⇒ key = source，回落路径逐字不变）。
    const key = intake || source;
    const base = INJECTED_SOURCE_LABELS[key] || key.charAt(0).toUpperCase() + key.slice(1);
    parts.push(base);
    if (sender) parts.push(sender);
  }
  if (eventType) {
    const et = EVENT_TYPE_LABELS[eventType] || eventType.charAt(0).toUpperCase() + eventType.slice(1);
    parts.push(et);
  }
  return parts.join(' · ');
}

/** Mail delivery modes from the backend contract ('queue'|'immediate').
 *  Badges are appended to the injected source label when the WS event /
 *  UiMessage carries a `delivery` field; old messages lack it → no badge
 *  (the former 'ask' mode was removed 2026-08-27 — legacy history rows that
 *  still carry it render without a badge). */
const DELIVERY_MODES = new Set(['queue', 'immediate']);

/** Append a delivery-mode badge to the label element (no-op when the field
 *  is absent or not a known mode — backward compatible with old history). */
function appendDeliveryBadge(label, delivery) {
  if (!delivery || !DELIVERY_MODES.has(delivery)) return;
  const badge = document.createElement('span');
  badge.className = `delivery-badge delivery-${delivery}`;
  badge.textContent = t(`mailDelivery.${delivery}`);
  badge.title = t(`mailDelivery.${delivery}Title`);
  label.appendChild(badge);
}

/** Build a row element for an injected message (pure builder — no DOM append,
 *  no scroll). Shared by live render (renderInjectedBubble) and history
 *  restore (persistence.js) so both paths render identically.
 *  deferFn (optional): batch-restore path passes persistence.js's deferMd to
 *  defer markdown rendering into post-append rAF batches — prevents a
 *  synchronous markdown storm when restoring long histories (P0-2).
 *  sourceTeam (optional): Team name for Team-agent messages — shown in the
 *  source label as 'team/agent' (see injectedSourceLabel).
 *  delivery (optional): Mail delivery mode 'queue'|'immediate' — shown
 *  as a badge in the label; absent on old messages → hidden.
 *  intake (optional): intake-channel discriminant (mailbadge batch) — takes
 *  label priority over `source`; absent → `source` renders as before.
 *  header (optional, 气泡四段式统一批 2026-09-15 — author ruling): the
 *  **already-rendered** four-segment header `KIND · PROJECT · SUBJECT · STATE`
 *  produced by the engine's single formatter
 *  (`nebflow.core.project.NotificationHeader`, called once at the one emission
 *  point `AgentActor#emitInjectedUserEvent`) and shipped on the WS frame /
 *  persisted in the .ui.json row. Present ⇒ rendered **verbatim** (the engine
 *  is the single source; the frontend must NOT concatenate a second time).
 *  Absent (old history rows, sources outside the engine KIND vocabulary,
 *  e.g. the in-flight device-mail source) ⇒ falls back to
 *  `injectedSourceLabel` above, byte-identical to before.
 *  fileChanges (optional, file-diff → 委托结果行 bug #58 修 2026-10-09；展开改
 *  Canvas 为 2026-10-10 裁定): the per-batch changed-file diff payload carried
 *  by delegate terminal events (engine `FileChangeReport.delegateBatchMetadata`
 *  — same wire shape as the REST file-changes face). Present with files ⇒ a
 *  collapsed「Changes」section mounts inside the expanded body showing ONLY the
 *  header stats line + per-file rows (badge / +a −r / source tag) — the diff
 *  bodies render in the CANVAS panel (diffCanvas.openFileChangesCanvas), never
 *  inline in the bubble; the collapsed label keeps the quiet `N files +a −r`
 *  hint. Absent (non-delegate rows / old history) ⇒ render unchanged. */
export function buildInjectedRow(text, source, timestamp, eventType, sender, sourceTeam, deferFn, delivery, intake, header, fileChanges) {
  const row = document.createElement('div');
  row.className = 'row user';

  const bubble = document.createElement('div');
  bubble.className = 'bubble injected';
  const label = document.createElement('div');
  label.className = 'ask-label injected-source-label';
  const trimmed = (text || '').trim();
  const content = document.createElement('div');
  // 打回注入块收敛 (2026-08-27 user ruling): the backend [打回任务 #id: title]
  // injection collapses to the one-line `#<任务号> <任务标题>` form with a
  // one-line-truncated opinion. 描述/产出 never render. Both the live dispatch
  // and BOTH history-restore paths funnel through this single builder, so every
  // entry renders byte-identically (AC d).
  const retMsg = parseTaskReturnText(trimmed);
  if (retMsg) {
    content.appendChild(buildTaskRefLine(retMsg));
  } else {
    if (deferFn) deferFn(content, trimmed);
    else content.innerHTML = renderMarkdownWithMath(trimmed, false);
  }

  // Default-collapsed (2026-08-23 ruling): blue injected bubbles show only the
  // category header (SOURCE · AGENT · EVENT_TYPE); the body expands on click.
  // Same product thought as #346: the stream stays clean, observability is
  // on-demand. Expansion is not persisted — refresh returns to collapsed.
  // Only collapses when there IS content; empty injected markers stay flat.
  // Expand affordance (2026-08-24 ruling): NO chevron icon — the quiet muted
  // label itself is the toggle (old「思考过程」interaction: cursor + hover
  // opacity only), which also fixes the icon's vertical misalignment.
  const collapsible = trimmed.length > 0;
  if (collapsible) {
    content.style.display = 'none'; // collapsed default
    bindCollapsibleToggle(label, () => content);
  }
  // 引擎单一来源优先（气泡四段式统一批 2026-09-15）：帧/落盘行带 `header` ⇒ 逐字
  // 渲染该串（**禁二次拼接**——引擎 `NotificationHeader` 是唯一格式化实现）；
  // 缺席 ⇒ 回落既有 `injectedSourceLabel`（旧历史行与词表外 source 逐字节不变）。
  label.appendChild(document.createTextNode(
    (header && String(header).trim()) ? String(header) : injectedSourceLabel(source, eventType, sender, sourceTeam, intake)
  ));
  appendDeliveryBadge(label, delivery);
  // file-diff → 委托结果行（bug #58 修）：折叠态的安静提示——「N files +a −r」。
  // 发现性钉子（「为什么我看不到」正是本案的用户报告形态）；缺载荷 ⇒ 零渲染。
  if (Array.isArray(fileChanges?.files) && fileChanges.files.length) {
    const hint = document.createElement('span');
    hint.className = 'nf-row-hint';
    const tot = fileChanges.files.reduce((acc, f) => {
      acc.a += Number(f?.added) || 0; acc.r += Number(f?.removed) || 0; return acc;
    }, { a: 0, r: 0 });
    hint.textContent = ` · ${fileChanges.files.length} file${fileChanges.files.length === 1 ? '' : 's'} +${tot.a} −${tot.r}`;
    label.appendChild(hint);
  }
  bubble.appendChild(label);
  bubble.appendChild(content);
  // file-diff → 委托结果行（2026-10-10 裁定）：展开体尾部挂「Changes」段，但只保留
  // header 统计行 + 文件行（徽标 / +a −r / 来源 tag）——diff 体不再内联展开；
  // 点头部（聚焦第一个文件）或任一文件行（聚焦该文件）→ Canvas 打开 diff 视图
  // （diffCanvas.openFileChangesCanvas，复用 diffRender.js 渲染单源）。
  // 事件委托挂在段根上：一个监听器覆盖头部与全部文件行（键盘 Enter/Space 同效）。
  if (Array.isArray(fileChanges?.files) && fileChanges.files.length) {
    const sec = document.createElement('div');
    sec.innerHTML = renderFileChangesHtml(fileChanges, { mode: 'chat' });
    const node = sec.firstElementChild;
    if (node) {
      const openInCanvas = (row, head) => {
        const idx = row ? Number(row.dataset.fcFile) : 0;
        openFileChangesCanvas(fileChanges, { focusIndex: Number.isFinite(idx) ? idx : 0 });
      };
      /** @param {MouseEvent} ev */
      const onFcClick = (ev) => {
        const el = /** @type {HTMLElement} */ (ev.target);
        openInCanvas(el.closest('[data-fc-file]'), el.closest('[data-fc-open]'));
      };
      /** @param {KeyboardEvent} ev */
      const onFcKey = (ev) => {
        if (ev.key !== 'Enter' && ev.key !== ' ') return;
        const el = /** @type {HTMLElement} */ (ev.target);
        const target = el.closest('[data-fc-file], [data-fc-open]');
        if (!target) return;
        ev.preventDefault();
        openInCanvas(target.closest('[data-fc-file]'), target);
      };
      node.addEventListener('click', onFcClick);
      node.addEventListener('keydown', onFcKey);
      content.appendChild(node);
    }
  }
  row.appendChild(bubble);

  // Timestamp + copy button (unified v1.2 footer pill). History restore may
  // lack a timestamp → copy-only footer; empty injected markers (no time AND
  // no text) stay footer-less.
  if ((timestamp && timestamp > 0) || trimmed) {
    row.appendChild(createMsgFooterBadge(timestamp, trimmed));
  }
  // quote-message 一期（spec §2.1/§2.5）：注入通知行可引用。挂点在本纯 builder
  // ⇒ live（renderInjectedBubble）与历史恢复（persistence.js）同构获得 data-msg-id
  // 与右键菜单（复制 = 正文纯文本；引用摘要 = header 或正文首行，senderRole='agent'）。
  row.dataset.msgId = nextChatMsgId();
  attachQuoteMenu(row, {
    kind: 'text', text: trimmed,
    messageId: row.dataset.msgId,
    senderRole: 'agent', senderName: injectedSourceLabel(source, eventType, sender, sourceTeam, intake),
    ts: timestamp || Date.now(),
  });
  return row;
}

/** Live-render an injected message into the active view.
 *  A-branch (2026-09-11): the scroll is CONDITIONAL — this used to jump to the
 *  bottom unconditionally, which yanked the user out of history they were
 *  reading (`chat.scrollTop = chat.scrollHeight`). It now follows the shared
 *  near-bottom judgement (utils.js shouldFollowBottom) so a notification that
 *  arrives while the user is scrolled up stays put and raises the ↓ N pill.
 *  fileChanges (optional): delegate 终态 diff 载荷透传（bug #58 修）——live 帧
 *  与历史恢复（persistence.js）走同一 builder，渲染逐字同构。 */
export function renderInjectedBubble(text, source, timestamp, eventType, sender, sourceTeam, delivery, intake, header, fileChanges) {
  const chat = activeView.dom.chat;
  const row = buildInjectedRow(text, source, timestamp || Date.now(), eventType, sender, sourceTeam, undefined, delivery, intake, header, fileChanges);
  appendChatRow(chat, row);
  if (shouldFollowBottom(activeView, chat)) chat.scrollTop = chat.scrollHeight;
}

// ---------- rAF stream render scheduler ----------
// Coalesces high-frequency streaming deltas into one DOM render per frame.
// Slots are keyed per (view, key) so concurrent views (main window + an open
// popup) and concurrent streams (ai / agent:<id> / ask) coalesce independently.
// The target is refreshed on every delta, so the pending rAF always renders
// the latest accumulated text into the latest bubble. This generalizes the
// module-level rAF pattern of appendThinkingDelta.
function scheduleStreamRender(view, key, target, render) {
  if (!view._streamRafSlots) view._streamRafSlots = {};
  let slot = view._streamRafSlots[key];
  if (!slot) slot = view._streamRafSlots[key] = { raf: null, target: null, render: null };
  slot.target = target;
  slot.render = render;
  if (!slot.raf) {
    slot.raf = requestAnimationFrame(() => {
      slot.raf = null;
      const t = slot.target; slot.target = null;
      const r = slot.render; slot.render = null;
      if (t && r) r(t);
    });
  }
}

/** Cancel a pending stream render — finish*() calls this before its final render. */
function cancelStreamRender(view, key) {
  const slot = view && view._streamRafSlots ? view._streamRafSlots[key] : null;
  if (slot && slot.raf) {
    cancelAnimationFrame(slot.raf);
    slot.raf = null;
    slot.target = null;
    slot.render = null;
  }
}

/** rAF-time scroll — mirrors smartScroll()'s snapped || near-bottom logic but
 *  scrolls the chat element captured at schedule time (activeView may point
 *  elsewhere by fire time). Same approach as appendThinkingDelta's rAF.
 *  Threshold = the shared NEAR_BOTTOM_PX (utils.js). */
function rafScrollChat(target) {
  if (target.snapped === true || target.chat.scrollHeight - target.chat.scrollTop - target.chat.clientHeight < NEAR_BOTTOM_PX) {
    target.chat.scrollTop = target.chat.scrollHeight;
  }
}

// ---------- B2 (RC-2): stream-frame block/round identity ----------
// The WS frames `textDelta` / `thinkingDelta` now carry `round` (the LLM round
// of the turn) and `block` (a strictly increasing counter of the frames of that
// type within the round) — see StreamBatching.FrameMark. The identity is what
// turns B1's tail-split from a heuristic into a deterministic decision: a frame
// whose (round, block) is strictly greater than the one the render cache was
// built under is a CONTINUATION of the same segment (the accumulated text is a
// strict superset), while a frame that repeats / lowers the identity belongs to
// a NEW segment and the closed-chunk cache must be dropped.
//
// 🔴 Deliberately NOT a DOM attribute: B1's acceptance requires the terminal
// `#chat` DOM to stay byte-identical (oracle), so the identity lives on the
// JS-side node fields (`bubble._nfSeg`, `view.stream.{ai,think}Seg`) — readable
// by a probe/spec, invisible to `outerHTML`.

/** The comparable segment key of a frame mark, or '' when the frame carries no
 *  identity (older backend / non-batched producers) — an absent key never
 *  triggers the deterministic reset, so behavior falls back to B1 exactly. */
function segKey(mark) {
  return mark && Number.isInteger(mark.round) && Number.isInteger(mark.block)
    ? mark.round + '.' + mark.block : '';
}

/** True when `next` continues the segment `prev` opened: same round and a
 *  strictly greater block (the backend hands out `block` monotonically per round;
 *  see StreamBatching.BatchState.marked). Both are `round.block` keys. */
function sameSegment(prev, next) {
  if (!prev || !next) return true;   // no identity ⇒ never a deterministic boundary
  const [pr, pb] = prev.split('.').map(Number);
  const [nr, nb] = next.split('.').map(Number);
  return pr === nr && nb > pb;
}

// ---------- AI text streaming ----------
export function appendAiText(text, mark) {
  const view = activeView;
  const chat = view.dom.chat;
  view.stream.aiText += text;
  // B2 consumption ①: record the frame's identity on the view + the live bubble.
  // The view-level key is what a later frame (or the popup's reopen-adopt path)
  // compares against to tell "same segment continues" from "a new segment began"
  // without re-parsing the accumulated text.
  const seg = segKey(mark);
  if (seg) {
    view.stream.aiSeg = seg;
    view.stream.aiRound = mark.round;
  }
  // stream-ux redesign (2026-10-03): the turn line KEEPS RUNNING while text
  // segments arrive — each completed segment pops in as its own bubble at the
  // tool/round boundary (finishAi), so there is no "badge rolls to its done
  // form on first text" step any more (the old `_nfWorklineDone` latch is
  // gone). The line only settles at the turn terminal (turnGroup).
  // 流式检测：发现完整的 <voice>...</voice> 块立即并行预取 TTS
  const voiceMatches = view.stream.aiText.match(/<voice>([\s\S]+?)<\/voice>/g);
  if (voiceMatches) {
    voiceMatches.forEach(m => {
      const content = m.replace(/<\/?voice>/g, '').trim();
      VoicePlayer.prefetch(content);
    });
  }
  if (view.stream.currentAiBubble && view.stream.currentAiBubble.classList.contains('thinking-placeholder')) {
    if (window.__stopThinkingTimer) window.__stopThinkingTimer();
    view.stream.currentAiBubble.classList.remove('thinking-placeholder');
    view.stream.currentAiBubble.innerHTML = '';
  }
  if (!view.stream.currentAiBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    view.stream.currentAiBubble = document.createElement('div');
    view.stream.currentAiBubble.className = 'bubble ai';
    row.appendChild(view.stream.currentAiBubble);
    appendChatRow(chat, row);
  }
  // Preserve any option box across re-renders: keep it OUT of the bubble while
  // streaming so innerHTML replacement can't destroy its event listeners.
  const askBox = view.stream.currentAiBubble.querySelector('.option-box');
  if (askBox) { askBox.remove(); view.stream.aiStreamAskBox = askBox; }
  // stream-ux §二.1.2 / A3 / A7 — the assistant bubble does NOT stream text.
  // Deltas only ACCUMULATE here (view.stream.aiText + the node's _nfText, which
  // bgAgentPopup's reopen-adopt reads); the single markdown render happens once
  // in finishAi(), which is when the bubble pops in as a whole. This removes the
  // per-frame markdown re-parse/re-layout that made long sessions stutter.
  // The scroll pin stays: the row is taller than the empty bubble, so keep the
  // viewport following while the turn is live.
  const bubble = view.stream.currentAiBubble;
  bubble._nfText = view.stream.aiText || '';
  // B2 consumption ②: the bubble carries the identity of the frame that last
  // wrote it. `finishAi`/`finishThinking` clear the mark when the segment ends,
  // so a mark left on the node always means "this segment is still open".
  if (seg) {
    bubble._nfSeg = seg;
    bubble._nfRound = mark.round;
  }
  if (view.stream.scrollSnapped === true || isNearBottom(chat)) {
    chat.scrollTop = chat.scrollHeight;
  }
}

export function finishAi(durationMs, model) {
  // Cancel any pending throttled render — this final render supersedes it.
  // (No rAF slot is scheduled any more; kept for a stream that was opened by an
  // older code path in the same page session.)
  cancelStreamRender(activeView, 'ai');
  if (activeView.stream.currentAiBubble) {
    if (!activeView.stream.aiText || !activeView.stream.aiText.trim()) {
      const row = activeView.stream.currentAiBubble.closest('.row');
      if (row) row.remove();
      activeView.stream.currentAiBubble = null;
      activeView.stream.aiText = '';
      activeView.stream.aiStreamAskBox = null;
      return null;
    }
    const askBox = activeView.stream.aiStreamAskBox || activeView.stream.currentAiBubble.querySelector('.option-box');
    if (askBox) askBox.remove();
    const bubble = activeView.stream.currentAiBubble;
    const firstPaint = !bubble._nfRendered;
    bubble.innerHTML = renderMarkdownWithMath(activeView.stream.aiText || '');
    bubble._nfText = activeView.stream.aiText || '';
    bubble._nfRendered = true;
    if (askBox) bubble.appendChild(askBox);
    activeView.stream.aiStreamAskBox = null;
    // stream-ux §二.1.2: the whole reply enters ONCE (scale + elastic). Only on
    // the first final render — a second finishAi on the same bubble (round
    // boundary) must not replay the entrance.
    // UI-G: `nf-pop` is CSS animation, so the class is only applied in the
    // main window; the popup views carry `.nf-static` anyway (chatView.js) and
    // this keeps the class off their DOM entirely, so a static window owns no
    // animation class even if the CSS rule were ever narrowed.
    if (firstPaint && activeView.motionEnabled()) bubble.classList.add('nf-pop');
    const ts = Date.now();
    // quote-message 一期（spec §2.1/§2.5）：助手文本行挂 id + 右键（复制 + 引用）。
    // 挂点在终态收口（finishAi）——流式期行是中间面，id 与菜单在这里一次成形。
    {
      const aiRow = bubble.closest('.row');
      if (aiRow) {
        tagQuotableRow(aiRow, {
          kind: 'text', text: activeView.stream.aiText || '',
          senderRole: 'ai', senderName: rootDisplayName(), ts,
        });
      }
    }
    let hasBadge = false;
    if (durationMs != null && durationMs > 0) {
      const seed = activeView.dom.chat.querySelectorAll('.duration-badge').length;
      renderDurationBadge(bubble, durationMs, model, seed, ts, activeView.stream.aiText);
      hasBadge = true;
    }
    // Copy button for AI message — unified footer pill with timestamp.
    // No phrase/model when no duration.
    if (!hasBadge) {
      const aiRow = bubble.closest('.row');
      if (aiRow) aiRow.appendChild(createMsgFooterBadge(ts, activeView.stream.aiText));
    }
    // Trigger voice TTS: enqueue all <voice> blocks for sequential playback,
    // and attach click-to-replay handlers on the green text.
    bubble.querySelectorAll('.voice-block').forEach(el => {
      el.addEventListener('click', () => VoicePlayer.replay(el));
      VoicePlayer.enqueue(el.textContent, el);
    });
    // ── 跟随钉底（2026-10-08 修复：整泡渲染丢失跟随腿）─────────────────────
    // stream-ux（01bf2f88b, 2026-10-02）把 appendAiText 的每帧 rAF 渲染收成此处
    // 一次性 final render 时，旧调度腿自带的 rafScrollChat（snapped||near-bottom
    // 跟随）没有跟过来：累积期空泡行一直贴底（尾钉在写），final render 一帧把
    // 气泡撑高数百 px ⇒ 距底距离一次性跳出 NEAR_BOTTOM_PX，本轮回答整块落在视
    // 口下方（工作线钉在 composer 上缘、答案从 composer 下缘渗出），且 done 之
    // 后再无任何 append 事件来消费 scrollSnapped latch ⇒ 停在旧位不跟随。
    // 此处补回与 renderInjectedBubble / appendAiText 尾钉同一份单源判据
    // （utils.js shouldFollowBottom = 跟随意图 latch ∥ 几何贴底）：用户已上翻
    // 读历史 ⇒ 不打扰（↓ N 胶囊语义不变）。同步写、不排 rAF —— 与 UI-G 弹窗
    // 静态面纪律一致（弹窗本无 rAF 调度）。
    if (shouldFollowBottom(activeView, activeView.dom.chat)) {
      activeView.dom.chat.scrollTop = activeView.dom.chat.scrollHeight;
    }
    const result = { type: 'ai', text: activeView.stream.aiText, durationMs, model, timestamp: ts };
    activeView.stream.currentAiBubble = null;
    activeView.stream.aiText = '';
    // B2: the segment closed — drop its identity so the next segment's first
    // frame is judged against an empty (not a stale) key.
    activeView.stream.aiSeg = '';
    bubble._nfSeg = null;
    return result;
  }
  return null;
}

/**
 * Format milliseconds into a human-readable duration string.
 * e.g. 5000 -> "5s", 93000 -> "1m 33s", 547000 -> "9m 7s"
 */
export function formatDuration(ms) {
  const totalSeconds = ms / 1000;
  if (totalSeconds < 1) return '< 1s';
  const rounded = Math.round(totalSeconds);
  if (rounded < 60) return rounded + 's';
  const minutes = Math.floor(rounded / 60);
  const seconds = rounded % 60;
  return minutes + 'm ' + seconds + 's';
}

/**
 * Format duration for live timer display (always ticking, no rounding).
 * e.g. 3500 -> "3s", 65000 -> "1m 5s", 3700000 -> "1h 1m 40s"
 */
export function formatLiveDuration(ms) {
  const totalSeconds = Math.floor(ms / 1000);
  if (totalSeconds < 60) return totalSeconds + 's';
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return minutes + 'm ' + seconds + 's';
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return hours + 'h ' + mins + 'm ' + seconds + 's';
}

/**
 * Cosmology-themed thinking phrases — now powered by i18n.
 * Keys: think.0 through think.18. {d} is replaced with the formatted duration.
 */
const THINKING_COUNT = 19;

/**
 * Pick a cosmology-themed thinking phrase with duration embedded.
 * e.g. "Counted some stars for 12s"
 * @param {number} durationMs
 * @param {number} [seed]
 * @returns {string} The full phrase text with duration.
 */
export function pickThinkingPhrase(durationMs, seed) {
  const idx = seed != null
    ? ((seed % THINKING_COUNT) + THINKING_COUNT) % THINKING_COUNT
    : Math.floor(Math.random() * THINKING_COUNT);
  return '✻ ' + t('think.' + idx, { d: formatDuration(durationMs) });
}

/**
 * Plain message footer badge (v1.2 unified footer form): time + copy.
 *
 * The SINGLE builder for every non-duration footer path (2026-09-06 footer
 * 补齐批): user bubbles, injected bubbles, no-duration AI/ask-answer
 * fallbacks, thinking rows, tool rows, agent rows, ask questions. Duration
 * footers (final AI/ask answers) still go through
 * createDurationBadgeElement, which carries the phrase/model metadata the
 * turn header needs — plain footers deliberately carry NO data-nf-phrase so
 * turnGroup.findDoneBadge never mistakes them for done markers.
 *
 * The divider is a true separator: inserted only between the time span and
 * the copy button — never a leading or trailing orphan. Missing timestamp →
 * copy-only; missing copyText → time-only (single element, no divider).
 *
 * @param {number} [timestamp] epoch millis (0/absent → no time span)
 * @param {string} [copyText] (empty/absent → no copy button)
 * @returns {HTMLElement}
 */
export function createMsgFooterBadge(timestamp, copyText) {
  const badge = document.createElement('div');
  badge.className = 'duration-badge';
  if (timestamp && timestamp > 0) {
    const timeSpan = document.createElement('span');
    timeSpan.className = 'duration-badge-time';
    // data-ts = 搜索跳转锚点（chatSearchFloat.js 消费，容器级挂载点同用此属性）；
    // data-ts-text = 时制刷新的显式声明（纯时钟文本节点，见 timeFormat.js 契约）；
    // data-ts-text-format="day" = 本节点文本走分层 footer label（formatFooterTime，
    // footer-time 批 2026-10-06）：时制切换就地重排仍与渲染时同一 formatter。
    timeSpan.setAttribute('data-ts', timestamp);
    timeSpan.setAttribute('data-ts-text', String(timestamp));
    timeSpan.setAttribute('data-ts-text-format', 'day');
    timeSpan.textContent = formatFooterTime(timestamp);
    bindTimeToggle(timeSpan);
    badge.appendChild(timeSpan);
  }
  if (copyText) {
    if (badge.childElementCount > 0) {
      const div = document.createElement('span');
      div.className = 'duration-badge-divider';
      badge.appendChild(div);
    }
    badge.appendChild(createMsgCopyButton(copyText));
  }
  return badge;
}

/**
 * Create a duration badge DOM element (pill style).
 *
 * v1.2 user ruling (2026-08-21 12:08): AI bubble footers are uniformly
 * time + copy only — no phrase, no model tag in the footer. The phrase
 * (with embedded duration) and model name still travel on the badge as
 * dataset attributes (data-nf-phrase / data-nf-model) so the turn-group
 * summary row (#346) can recover them: live path via main.js, history
 * reload via turnGroup.groupSegment. The summary row is now the sole
 * display home of the phrase/model metadata.
 *
 * @param {number} durationMs
 * @param {string} [model]
 * @param {number} [seed]
 * @param {number} [timestamp] - epoch millis for display
 * @returns {HTMLElement}
 */
export function createDurationBadgeElement(durationMs, model, seed, timestamp, copyText) {
  const badge = document.createElement('div');
  badge.className = 'duration-badge';
  // Metadata for #346 summary reconstruction (not rendered — footer is
  // time + copy only, v1.2 ruling).
  badge.dataset.nfPhrase = pickThinkingPhrase(durationMs, seed);
  if (model) badge.dataset.nfModel = model;

  // Divider is a SEPARATOR: insert only between two existing/forthcoming
  // elements — never as a leading or trailing orphan (v1.2 footer = time +
  // copy only; the old leading divider was a leftover of "phrase | time").
  const appendDivider = () => {
    if (badge.childElementCount === 0) return;
    const div = document.createElement('span');
    div.className = 'duration-badge-divider';
    badge.appendChild(div);
  };

  if (timestamp) {
    appendDivider();
    const timeSpan = document.createElement('span');
    timeSpan.className = 'duration-badge-time';
    // data-ts = jump anchor (chatSearchFloat.js); data-ts-text = refresh target;
    // data-ts-text-format="day" = layered footer label (formatFooterTime —
    // footer-time batch 2026-10-06), same formatter on 12/24 in-place refresh.
    timeSpan.setAttribute('data-ts', timestamp);
    timeSpan.setAttribute('data-ts-text', String(timestamp));
    timeSpan.setAttribute('data-ts-text-format', 'day');
    timeSpan.textContent = formatFooterTime(timestamp);
    bindTimeToggle(timeSpan);
    badge.appendChild(timeSpan);
  }

  if (copyText) {
    appendDivider();
    const copyBtn = createMsgCopyButton(copyText);
    badge.appendChild(copyBtn);
  }

  return badge;
}

/**
 * Render a subtle duration badge below an AI bubble.
 */
export function renderDurationBadge(bubble, durationMs, model, seed, timestamp, copyText) {
  if (!bubble) return;
  const row = bubble.closest('.row');
  if (!row) return;
  const badge = createDurationBadgeElement(durationMs, model, seed, timestamp, copyText);
  row.appendChild(badge);
}

// ---------- Multi-agent rendering ----------
export function appendAgentText(agentId, text) {
  const view = activeView;
  const chat = view.dom.chat;
  if (!view.stream.agentBubbles[agentId]) {
    const row = document.createElement('div');
    row.className = 'row ai agent-row';
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai';
    let badge = null;
    if (agentId && agentId !== 'default') {
      // F1（作者 2026-09-16 决策卡）：通用「Agent 代发」徽章 —— 主对话面**放弃**
      // agentId / uppercase / mono / 内联 agentColor 表达（内联色是本批删掉的
      // 第二处色源；材质与几何单源化在 sapphire.css 的 `.agent-badge` 一处）。
      badge = document.createElement('div');
      badge.className = 'agent-badge';
      badge.textContent = t('messages.agentBadge');
      row.appendChild(badge);
    }
    row.appendChild(bubble);
    appendChatRow(chat, row);
    view.stream.agentBubbles[agentId] = { bubble, text: '', row, badge };
  }
  const a = view.stream.agentBubbles[agentId];
  a.text += text;
  // rAF-throttled render (same scheduler as appendAiText)
  a.bubble._nfText = a.text;
  scheduleStreamRender(view, 'agent:' + agentId,
    { bubble: a.bubble, chat, snapped: view.stream.scrollSnapped },
    (target) => {
      if (!target.bubble.isConnected) return;
      target.bubble.innerHTML = renderMarkdownWithMath(target.bubble._nfText || '', true, { cache: false }) + '<span class="cursor"></span>';
      rafScrollChat(target);
    });
}

export function finishAgent(agentId) {
  cancelStreamRender(activeView, 'agent:' + agentId);
  const a = activeView.stream.agentBubbles[agentId];
  if (a) {
    if (!a.text || a.text.trim() === '') {
      if (a.row) a.row.remove();
    } else {
      a.bubble.innerHTML = renderMarkdownWithMath(a.text);
      // v1.2 unified footer (2026-09-06 补齐批): agent rows get time + copy.
      if (a.row) a.row.appendChild(createMsgFooterBadge(Date.now(), a.text));
      // 跟随钉底（2026-10-08，与 finishAi 同族同修）：agent 流式帧经
      // scheduleStreamRender→rafScrollChat 跟随，终态 final render（含 footer
      // 行）是一次原地长高，旧腿不覆盖此处 ⇒ 同一判据补回；已上翻不打扰。
      if (shouldFollowBottom(activeView, activeView.dom.chat)) {
        activeView.dom.chat.scrollTop = activeView.dom.chat.scrollHeight;
      }
    }
  }
  if (activeView.stream.activeAgentId === agentId) activeView.stream.activeAgentId = null;
}

// ---------- Tool rendering ----------

// Pop artifact faces (pop-upgrade batch 2026-10-03): the payload parser, the
// media-stack / file-card renderer and the Canvas-open dispatch live in ONE
// module (popArtifacts.js) so live rendering and history replay share the
// payload contract. chat.js re-exports the two names chatSearch.js has always
// imported from here — re-export, not redefinition (single source in
// popArtifacts.js).
export { openPopArtifact, popArtifactFromInput } from './popArtifacts.js';
import { isPopPayload, parsePopPayload, buildPopArtifactRow, renderPopToolRow, applyLegacyPopCard, buildUserMediaRow, registerUserMediaRow, patchUserMediaRow } from './popArtifacts.js';

/** quote-message 一期：AskUser 卡首个 question 的纯文本（剥 HTML 标签；镜像
 *  persistence.js askUserQuestionText 的剥标签先例 —— chat.js 不反向 import
 *  persistence（会成环），两处同步点 = 标签剥法 `replace(/<[^>]*>/g,' ')`。 */
function askUserCardQuestionText(items) {
  const first = Array.isArray(items) ? items.find(it => it && String(it.question || '').trim()) : null;
  return first ? String(first.question || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim() : '';
}

/** quote-message 一期：行挂消息 id + 右键菜单（幂等 —— id 只在缺席时补、菜单用
 *  行级旗标防重复挂）。工具卡两段生命期（renderToolPending 建 id / renderTool 完成
 *  面补菜单带 label+summary）都收敛到这里。
 *  footer 引用按钮批：菜单已挂过的行（round 边界二次 finishAi 复用同行）刷新行上
 *  描述符并重排一次徽章收敛 —— 追加的新 footer 徽章也要补引用按钮（首挂的收敛在
 *  attachQuoteMenu 内）。 */
function tagQuotableRow(row, d) {
  if (!row) return;
  if (!row.dataset.msgId) row.dataset.msgId = nextChatMsgId();
  if (!row.__nfQuoteMenu) {
    row.__nfQuoteMenu = true;
    attachQuoteMenu(row, { ...d, messageId: row.dataset.msgId });
  } else {
    row.__nfQuoteDesc = { ...d, messageId: row.dataset.msgId };
    scheduleQuoteButtonMount(row);
  }
}

export function renderTool(label, summary, content, isError, inputJson, sessionId) {
  const sid = sessionId || activeView.sessionId;
  const chat = activeView.dom.chat;
  // stream-ux §二.1.1: the tool FINISHED — the badge swaps the spinner for the
  // drawn check (cross + error ink when the tool failed) in place, no roll. The
  // module's own key check makes a stale/other-turn call a no-op.
  // ③ 完成翻转（toolline-detail 批）：行文本 = 单行结果摘要（工具名 — 摘要），
  // 不再停留运行中 label；运行细节 span 随项重建自然卸下。
  const resultText = toolLineResultText(label, summary);
  // （c）面：弹窗裸行 —— 完成翻转属 turn-line 剧场，弹窗不落行（工具卡本体
  // 即 9.19 的完成面：icon + label — summary）。
  if (!bareProcessRows(activeView)) {
    if (isError) failWorklineItem(activeView, worklineLabel(label), resultText);
    else doneWorklineItem(activeView, worklineLabel(label), resultText);
  }

  // Cancel any pending streaming rAF so it doesn't overwrite the final render
  cancelToolStreamRAF();

  // Reuse the pending card's DOM node for a smooth transition from streaming
  // state to final state — no visual jump from remove+recreate.
  // When multiple tools are called in one LLM response, sessionToolCards[sid]
  // (a single slot) may point to a different tool's card. Search by
  // data-tool-label to find the correct one.
  cancelToolStreamRAF();
  let pending = state.sessionToolCards[sid];
  if (pending) {
    const cardEl = pending.querySelector('.tool-card');
    const existingLabel = cardEl?.dataset.toolLabel;
    if (existingLabel && label && existingLabel !== label) {
      // Slot points to a different tool's card — search DOM for the right one
      pending = null;
      const cards = chat.querySelectorAll('.row.tool .tool-card--pending');
      for (const c of cards) {
        if (c.dataset.toolLabel === label) { pending = c.closest('.row'); break; }
      }
    }
  } else {
    // No card in slot — search DOM by label
    const cards = chat.querySelectorAll('.row.tool .tool-card--pending');
    for (const c of cards) {
      if (c.dataset.toolLabel === label || !c.dataset.toolLabel) { pending = c.closest('.row'); break; }
    }
  }
  // Only clear the slot if we're consuming the card it points to
  if (pending === state.sessionToolCards[sid]) delete state.sessionToolCards[sid];
  let row, card;
  if (pending && pending.isConnected) {
    row = pending;
    card = row.querySelector('.tool-card');
    card.classList.remove('tool-card--pending');
    card.innerHTML = '';
  } else {
    row = document.createElement('div');
    // Pre-tucked (stream-ux redesign 2026-10-03): the card is the expand face;
    // the turn line owns the live spinner. Reused pending rows were born
    // tucked by renderToolPending.
    // （c）面：弹窗裸行（9.19 形态）—— 在飞工具卡可见；终态由 turnGroup 收拢
    // （9.19 同一收拢路径）。
    row.className = bareProcessRows(activeView) ? 'row tool' : 'row tool nf-tucked';
    card = document.createElement('div');
    card.className = 'tool-card';
    row.appendChild(card);
    appendChatRow(chat, row);
  }
  // NebLink tool marker
  if (label && label.startsWith('[NebLink]')) row.classList.add('neblink-row');
  // quote-message 一期（spec §2.1/§2.5）：工具卡行挂 id + 右键「引用」（仅引用，
  // 无正文可复制；摘要 = [工具] label · summary≤40）。复用 pending 行幂等。
  tagQuotableRow(row, {
    kind: 'tool', label, summary,
    senderRole: 'tool', senderName: rootDisplayName(), ts: Date.now(),
  });
  // UI-B: stamp the owning round on a freshly built row (a reused pending row
  // already carries its start round from renderToolPending — the completion
  // must not re-attribute it to a later round).
  if (!pending) stampRound(activeView, row);
  // beta.56 ruling: tool-class bubbles carry NO footer — footers are
  // message-class only (user / assistant final text / ask / agent / skill).
  // Tool rows render header + body only. (2026-09-06 二次裁定: thinking 也
  // 不属于消息类——中间过程显示，同样无 footer，见 finishThinking。)
  // #346 v2 stats: carry the tool input on the row so the turn header can
  // count 读/写 files (turnGroup computeTurnStats reads dataset.nfInput).
  if (inputJson) { try { row.dataset.nfInput = typeof inputJson === 'string' ? inputJson : JSON.stringify(inputJson); } catch {} }

  // Card tool: render standard tool card (icon + label) then card iframe below.
  // This unifies Card display with other tools — spinner → checkmark transition,
  // consistent tool card header — while the rendered HTML card appears separately.
  if (content && typeof content === 'string' && /^___\w+_HTML___/.test(content)) {
    const cIcon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                         : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
    const cLocalLabel = localizeToolLabel(label);
    const cLocalSummary = localizeToolSummary(summary, label);
    const cLabelParts = cLocalLabel.split('\n', 2);
    const cLabelHtml = escapeHtml(cLabelParts[0]) + ' &mdash; ' + escapeHtml(cLocalSummary)
      + (cLabelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(cLabelParts[1]) + '</span>' : '');
    card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + cIcon + '</span>' +
      '<div class="content"><div class="label">' + cLabelHtml + '</div></div>';
    // Card iframe in a separate row below the tool card
    const cardRow = document.createElement('div');
    cardRow.className = 'row card-content';
    const cardContainer = document.createElement('div');
    cardRow.appendChild(cardContainer);
    row.after(cardRow);
    renderWithRegistry(cardContainer, content, label);
    smartScroll();
    return { type: 'tool', label, summary, content, isError, input: inputJson };
  }

  // Tools where the input parameters are more useful than the result.
  // For these, render the tool_use input (recipient, message, action) instead
  // of the tool_result content ("Message sent...").
  const _toolName = label ? label.split('(')[0].split('\n')[0].trim() : '';

  // Pop artifact payload (pop-upgrade batch): the tool row renders as a plain
  // process face; the media stack / file cards render in a SEPARATE row below
  // (`.row.pop-artifact` — never a `.row.tool`, so turnGroup never tucks it and
  // the deliverable survives the ✻ collapse). The payload rides `content` into
  // history, so replay re-renders the same faces via persistence.js.
  //
  // #491 (author 2026-10-05 「按时间顺序显示…像微信一样」): the artifact row is
  // inserted IN PLACE, right where its payload arrived (`row.after`). The UI-D
  // re-anchor that used to run here on every tool render is removed — rows
  // appended later in the turn keep their natural order behind it.
  if (isPopPayload(content)) {
    renderPopToolRow(card, label, summary, isError);
    const popPayload = parsePopPayload(content);
    if (popPayload) {
      const artifactRow = buildPopArtifactRow(popPayload);
      if (artifactRow) {
        row.after(artifactRow);
        smartScroll();
      }
    } else {
      // Truncated payload (should not happen — SessionStore stores Pop payloads
      // whole) → the safe placeholder, never the raw sentinel text.
      const popBody = document.createElement('div');
      popBody.className = 'body open';
      popBody.textContent = t('chat.toolCardUnavailable');
      card.querySelector('.content')?.appendChild(popBody);
      attachToolClick(card);
    }
    return { type: 'tool', label, summary, content, isError, input: inputJson };
  }

  // Legacy Pop rows (pre-batch histories): rainbow filename inline in the
  // label + clickable to re-open. New Pop calls always carry the payload above.
  if (applyLegacyPopCard(card, label, summary, inputJson, isError)) {
    smartScroll();
    return { type: 'tool', label, summary, content: null, isError, input: inputJson };
  }

  if (_toolName === 'Mail' && inputJson) {
    const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                         : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
    const localLabel = localizeToolLabel(label);
    const localSummary = localizeToolSummary(summary, label);
    const labelParts = localLabel.split('\n', 2);
    const labelHtml = escapeHtml(labelParts[0]) + ' &mdash; ' + escapeHtml(localSummary)
      + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
    let mailBody = '';
    try {
      const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
      const msg = inp.message || '';
      mailBody = msg ? '<div class="tool-mail-msg">' + renderMarkdownWithMath(msg) + '</div>' : '';
    } catch {}
    card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
      '<div class="content"><div class="label">' + labelHtml + '</div>' +
      (mailBody ? '<div class="body">' + mailBody + '</div>' : '') + '</div>';
    smartScroll();
    if (mailBody) attachToolClick(card);
    return { type: 'tool', label, summary, content: null, isError, input: inputJson };
  }

  // Default rendering for all other tools
  const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                       : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  //
  // ── cardguard negative filter (author ruling 2026-09-17 · #687①) ──────────
  // A tool RESULT whose content carries the card sentinel ANYWHERE — not only
  // at position 0; the realistic shape is ToolResultGuard's persisted preview,
  // `<persisted-output>\n…Preview (first 2048 chars):\n___CARD_HTML___{…` —
  // must never be rendered as raw text into the chat stream. Both routes below
  // would do exactly that: `renderHighlightedContent` (hljs wraps the whole
  // string in a <pre>) and the plain `<pre class="tool-body-pre">` fallback.
  // Route such content to the card path (best effort) or to a safe placeholder;
  // the raw payload enters the DOM on neither branch.
  const cgSentinelIdx = typeof content === 'string' ? content.search(/___\w+_HTML___/) : -1;
  if (cgSentinelIdx >= 0) {
    const cgLocalLabel = localizeToolLabel(label);
    const cgLocalSummary = localizeToolSummary(summary, label);
    const cgLabelParts = cgLocalLabel.split('\n', 2);
    const cgLabelHtml = escapeHtml(cgLabelParts[0]) + ' &mdash; ' + escapeHtml(cgLocalSummary)
      + (cgLabelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(cgLabelParts[1]) + '</span>' : '');
    card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
      '<div class="content"><div class="label">' + cgLabelHtml + '</div></div>';
    // (i) Best effort: hand everything from the sentinel onward to the single
    // registry parser (cardRegistry owns the parse rules — no second copy of
    // them here). It writes nothing into the container when the payload does
    // not parse (e.g. the 2048-char preview cut), so probing leaks nothing.
    const cgCardRow = document.createElement('div');
    cgCardRow.className = 'row card-content';
    cgCardRow.dataset.cardguard = 'card';
    const cgCardContainer = document.createElement('div');
    cgCardRow.appendChild(cgCardContainer);
    if (renderWithRegistry(cgCardContainer, content.slice(cgSentinelIdx), label)) {
      row.after(cgCardRow);
    } else {
      // (ii) Safe placeholder — never the raw payload / <persisted-output> body.
      // Deliberately no "view source" toggle: the raw text stays out of the DOM
      // entirely, collapsed or not.
      const cgBody = document.createElement('div');
      cgBody.className = 'body open';
      cgBody.dataset.cardguard = 'placeholder';
      cgBody.textContent = t('chat.toolCardUnavailable');
      const cgContentEl = card.querySelector('.content');
      if (cgContentEl) cgContentEl.appendChild(cgBody);
    }
    smartScroll();
    return { type: 'tool', label, summary, content, isError, input: inputJson };
  }

  const detailHtml = buildToolDetail(inputJson, label);
  const delegatePromptHtml = buildDelegatePromptHtml(inputJson);
  // Render full content in body with syntax highlighting (Read/Grep only).
  // Body is hidden by default, click to expand shows full content with scroll for long output.
  const highlightHtml = content ? renderHighlightedContent(content, label) : null;
  const bodyHtml = (detailHtml + delegatePromptHtml + (highlightHtml || (content ? '<pre class="tool-body-pre">' + escapeHtml(content) + '</pre>' : ''))) || '';
  const hasBody = !!bodyHtml;
  const localLabel = localizeToolLabel(label);
  const localSummary = localizeToolSummary(summary, label);
  const labelParts = localLabel.split('\n', 2);
  const truncBadge = ''; // placeholder for future truncated content indicator
  // Device tag — subtle indicator when tool runs on a remote device
  let deviceTag = '';
  if (inputJson) {
    try {
      const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
      if (inp.device) deviceTag = '<span class="tool-device-tag">' + escapeHtml(String(inp.device)) + '</span>';
    } catch {}
  }
  const labelHtml = escapeHtml(labelParts[0]) + ' &mdash; ' + escapeHtml(localSummary) + truncBadge + deviceTag
    + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + labelHtml + '</div>' +
    (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
  smartScroll();

  if (hasBody) attachToolClick(card);
  return { type: 'tool', label, summary, content, isError, input: inputJson };
}

export function renderToolPending(label, sessionId) {
  const sid = sessionId || activeView.sessionId;
  const chat = activeView.dom.chat;
  // stream-ux §二.1.1: a tool STARTED — the single-line badge shows a spinner +
  // the tool's first label line. Idempotent per label, so the expected
  // toolCallDetected → toolStart double event does not flicker a roll.
  // （c）面：弹窗裸行 —— 不建 turn-line；在飞面 = 可见的 pending 工具卡
  // （spinner + label，9.19 形态）。worklineToolDetail 细节流自守卫（无行即空转）。
  if (!bareProcessRows(activeView)) startWorklineItem(activeView, worklineLabel(label), 'tool');
  if (activeView.stream.currentAiBubble && activeView.stream.currentAiBubble.classList.contains('thinking-placeholder')) {
    if (window.__stopThinkingTimer) window.__stopThinkingTimer();
    const row = activeView.stream.currentAiBubble.closest('.row');
    if (row) row.remove();
    activeView.stream.currentAiBubble = null;
    activeView.stream.aiText = '';
  }

  // Helper: update the label text on a pending card row.
  function updateLabel(rowEl) {
    const labelEl = rowEl.querySelector('.label');
    if (labelEl) {
      const localLabel = localizeToolLabel(label);
      const labelParts = localLabel.split('\n', 2);
      labelEl.innerHTML = escapeHtml(labelParts[0])
        + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
    }
  }

  // Helper: after the workline item is (re)built or refreshed for this label,
  // replay the finished argument stream into the typed detail line (② toolline-
  // detail batch). Same-key refresh keeps the item (and its detail span) alive;
  // a rebuilt item needs the span recreated — worklineToolDetail handles both.
  function refreshWorklineDetail() {
    reapplyWorklineDetailFromArgs(label);
  }

  // If a pending card already exists for this session, update it in-place
  // to avoid spinner flicker between toolCallDetected → toolStart events.
  // Defense: if the DOM node was removed (e.g. historyPage cleared innerHTML
  // without clearing sessionToolCards), treat it as non-existent so a fresh
  // card is created.
  const existing = state.sessionToolCards[sid];
  if (existing && existing.isConnected) {
    const cardEl = existing.querySelector('.tool-card');
    const existingLabel = cardEl?.dataset.toolLabel;
    // Reuse if no toolStart has claimed this card yet (toolCallDetected → toolStart
    // for the same tool), or if the label matches (toolStart from execution phase
    // for the same tool).
    if (!existingLabel || existingLabel === label) {
      updateLabel(existing);
      refreshWorklineDetail();
      return;
    }
    // Label mismatch — the slot holds a different tool's card.
    // Search for a pending card with matching label in the DOM.
    const cards = chat.querySelectorAll('.row.tool .tool-card--pending');
    for (const c of cards) {
      if (c.dataset.toolLabel === label) {
        const matchedRow = c.closest('.row');
        state.sessionToolCards[sid] = matchedRow;
        updateLabel(matchedRow);
        refreshWorklineDetail();
        return;
      }
    }
    // No match found — fall through to create a new card.
  } else {
    // No card in slot — try to find a pending card by label (orphaned card
    // from a previous tool whose slot was overwritten).
    const cards = chat.querySelectorAll('.row.tool .tool-card--pending');
    for (const c of cards) {
      if (c.dataset.toolLabel === label || !c.dataset.toolLabel) {
        const matchedRow = c.closest('.row');
        state.sessionToolCards[sid] = matchedRow;
        updateLabel(matchedRow);
        refreshWorklineDetail();
        return;
      }
    }
  }

  const row = document.createElement('div');
  // （c）面：弹窗裸行（9.19 形态）—— pending 卡在飞可见；主窗保持预 tucked
  // （expand face — the turn line owns the run, 2026-10-03）。
  row.className = bareProcessRows(activeView) ? 'row tool' : 'row tool nf-tucked';
  // quote-message 一期：行 id 自诞生即挂（跳转寻址面；右键菜单由完成面
  // renderTool 的 tagQuotableRow 统一补 —— 那里才有 label+summary 完整摘要）。
  row.dataset.msgId = nextChatMsgId();
  // UI-B: the row carries the round it belongs to (a tool that starts in one
  // round and completes in the next keeps its START round, so the split never
  // re-attributes it). Read by the badge-split spec through dataset.nfRound.
  stampRound(activeView, row);
  if (label && label.startsWith('[NebLink]')) row.classList.add('neblink-row');
  const card = document.createElement('div');
  card.className = 'tool-card tool-card--pending';
  const localLabel = localizeToolLabel(label);
  const labelParts = localLabel.split('\n', 2);
  const labelHtml = escapeHtml(labelParts[0])
    + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
  card.innerHTML = '<span class="icon"><span class="spinner"></span></span>' +
    '<div class="content"><div class="label">' + labelHtml + '</div></div>';
  row.appendChild(card);
  appendChatRow(chat, row);
  smartScroll();
  state.sessionToolCards[sid] = row;
  refreshWorklineDetail();
}

// ---------- Tool argument streaming ----------
// While the LLM generates tool call arguments (e.g. Write content, Bash command),
// toolArgDelta events stream partial JSON fragments. We accumulate them and try
// to extract the primary content field for display, giving the user real-time
// feedback instead of just a spinner.

const TOOL_PRIMARY_FIELDS = {
  'Write': 'content',
  'Edit': 'new_string',
  'Bash': 'command',
  'Card': 'html',
  // Delegate（toolline-detail 批 2026-10-06 修正）：入参 schema 的 brief 字段是
  // `task`（DelegateTool.scala :72 required "task"；旧值 'prompt' 在帧参数里
  // 永不出现 ⇒ 参数流式面从末对 Delegate 出过内容）。
  'Delegate': 'task',
  'Read': 'file_path',
  'Grep': 'pattern',
  'Glob': 'pattern',
  'Curl': 'body',
  'WebSearch': 'query',
  'WebFetch': 'url',
  'TaskCreate': 'description',
  'TaskUpdate': 'description',
  'Mail': 'message',
  'MailAgent': 'message',
  // zcode-484 (PLAN §3.3-4, OD-5): Pop was the one artifact-producing tool with
  // no primary-field entry, so its running badge line showed only the tool name
  // while the args streamed. `filePath` is the field that names the deliverable;
  // it arrives either as one path or as an ARRAY (the usual shape for a batch),
  // and the reader below handles BOTH: on an array it shows the first path plus
  // " +N" for the further complete ones — the plan's "first path (+N)" progress.
  'Pop': 'filePath',
  // AskUserQuestion（分层展示批追加令 2026-10-06，作者裁决点名「AskUser 输入问题
  // 的时候」应流式）：卡体流式面与细节行（TOOL_LINE_DETAIL 同批补行）同源一致
  // ——入参字段 = question（AskUserQuestionTool.scala :96/:157，帧名
  // "AskUserQuestion"）。
  'AskUserQuestion': 'question',
};

// ---------- ② 运行中类型化细节行（toolline-detail 批 2026-10-06 作者 16:51 令）----------
//
// RUNNING detail-line config — ROLLABLE：一种工具类型一行，新工具类型接入 =
// 加一行，零逻辑改动。每行把「运行中的工具」映射到它该在 turn 行细节位流式
// 展示的入参字段。数据源 = toolArgDelta 的参数 JSON 增量累积
// （view.stream.toolStreamText——main.js 在每个 toolCallDetected 复位，toolStart
// 不复位 ⇒ 执行开始时完整参数已在客户端；证据链 AgentStreamPipelines.scala:228
// ToolArgDelta → ws.js STREAM_MSG_TYPES → main.js onMessage('toolArgDelta')）。
//
//   delegate = 任务 brief 首行（field `task`，DelegateTool schema required）
//   bash     = 正在执行的具体命令（field `command`）
//   读/写    = 路径（file_path）
//   搜索     = 查询词（pattern / query）
//   web      = URL（url）
//   其余     = 默认分支：按 DEFAULT_DETAIL_FIELDS 顺序取第一个出现的短字段
//              （工具名本体已在 label 位，缺字段时不加细节行）。
//
// 🔴 bash「输出尾行」滚动不在本表能力内：执行期引擎不下发任何输出事件
// （唯一执行期帧 = 30s toolHeartbeat{label}，AgentSessionExecution.scala:2283，
// 只喂活 busy timer）——补事件方案另文候示，🔴 本批禁动引擎面。
const TOOL_LINE_DETAIL = [
  { tools: ['Delegate', 'Subagent', 'SubTask'], field: 'task' },       // 任务 brief 首行
  { tools: ['Bash'], field: 'command' },                               // 正在执行的具体命令
  { tools: ['Read', 'Write', 'Edit', 'MultiEdit', 'NotebookEdit'], field: 'file_path' }, // 路径
  { tools: ['Grep', 'Glob'], field: 'pattern' },                       // 查询词
  { tools: ['WebSearch'], field: 'query' },                            // 查询词
  { tools: ['WebFetch', 'Curl'], field: 'url' },                       // URL
  { tools: ['AskUserQuestion'], field: 'question' },                   // 问句（作者 10-06 裁决点名例——问句随 LLM 生成逐字上屏；分层展示批追加令）
];
// 默认分支的探测字段序（其余工具 = 工具名 + 一行上下文）。
const TOOL_LINE_DETAIL_DEFAULT_FIELDS = ['command', 'file_path', 'path', 'url', 'pattern', 'query', 'task', 'to', 'id', 'name'];

/** 细节行单行化：取首个非空行、封顶截断（… 尾）。流式期间值未完亦照常显示
 *  （extractFieldValueFromPartialJson 对未闭合字符串返回已到部分——这正是
 *  「流式」本体）。 */
function toolLineDetailOneLine(value, cap = 200) {
  const line = (String(value).split('\n').find(l => l.trim()) || '').trim();
  return line.length > cap ? line.slice(0, cap - 1) + '…' : line;
}

/** 映射表解析：toolName → 应展示字段序（命中表项取其 field，否则默认序）。 */
function toolLineDetailFields(toolName) {
  const name = String(toolName || '').trim();
  for (const entry of TOOL_LINE_DETAIL) {
    if (entry.tools.indexOf(name) !== -1) return [entry.field];
  }
  return TOOL_LINE_DETAIL_DEFAULT_FIELDS;
}

/** 从（可能是部分）参数 JSON 提取细节行文本；无字段可示 → ''（不加细节行）。 */
function toolLineDetailText(toolName, rawJson) {
  if (!rawJson) return '';
  for (const field of toolLineDetailFields(toolName)) {
    const v = extractFieldValueFromPartialJson(rawJson, field);
    if (v && v.value) {
      const line = toolLineDetailOneLine(v.value);
      if (line) return line;
    }
  }
  return '';
}

/**
 * Read one JSON string starting just AFTER its opening quote (the same best-
 * effort reader the field extractor needs, lifted so the array form can reuse
 * it per element). Handles the escapes \n \t \r \" \\ \/ \b \f \uXXXX.
 * Returns { value, complete, end }; `end` is the index just past the closing
 * quote when complete, or the end of the buffer while the string still streams.
 */
function readJsonString(partialJson, idx) {
  let result = '';
  while (idx < partialJson.length) {
    const ch = partialJson[idx];
    if (ch === '\\' && idx + 1 < partialJson.length) {
      const next = partialJson[idx + 1];
      switch (next) {
        case 'n': result += '\n'; break;
        case 't': result += '\t'; break;
        case 'r': result += '\r'; break;
        case '"': result += '"'; break;
        case '\\': result += '\\'; break;
        case '/': result += '/'; break;
        case 'b': result += '\b'; break;
        case 'f': result += '\f'; break;
        case 'u':
          if (idx + 5 < partialJson.length) {
            const code = parseInt(partialJson.substr(idx + 2, 4), 16);
            if (!isNaN(code)) result += String.fromCodePoint(code);
            idx += 4;
          }
          break;
        default: result += next;
      }
      idx += 2;
    } else if (ch === '"') {
      // Closing quote — the string is complete
      return { value: result, complete: true, end: idx + 1 };
    } else {
      result += ch;
      idx++;
    }
  }
  // Stream still open — return what we have so far
  return { value: result, complete: false, end: partialJson.length };
}

/**
 * The running PROGRESS reading of a still-streaming JSON ARRAY value: the first
 * string element as the visible text, plus " +N" once N further elements are
 * complete. This is what makes an array-form Pop (a batch — the plan's usual
 * shape) show the same running row the single-string form shows.
 *
 * Returns null until the first element's opening quote is on the wire, so
 * before any content arrives the array form behaves exactly like the string
 * form (no body is built yet).
 *
 * A non-string element is not this reader's business: the tool layer refuses
 * those by index with a readable error (PopTool.requestedPaths), and a partial
 * stream cannot tell "rejected shape" from "still arriving" — so the reader
 * keeps the progress it already has instead of guessing.
 */
function readJsonArrayProgress(partialJson, idx) {
  while (idx < partialJson.length && /[\s,]/.test(partialJson[idx])) idx++;
  if (idx >= partialJson.length || partialJson[idx] !== '"') return null;
  const first = readJsonString(partialJson, idx + 1);

  let extra = 0;
  let closed = false;
  let j = first.end;
  while (j < partialJson.length) {
    while (j < partialJson.length && /[\s,]/.test(partialJson[j])) j++;
    if (j >= partialJson.length) break; // stream still open
    if (partialJson[j] === ']') { closed = true; break; }
    if (partialJson[j] !== '"') break; // non-string element — see the note above
    const elem = readJsonString(partialJson, j + 1);
    if (!elem.complete) break; // still arriving — not counted yet
    extra++;
    j = elem.end;
  }

  return {
    value: extra > 0 ? first.value + ' +' + extra : first.value,
    complete: closed,
  };
}

/**
 * Best-effort extraction of a JSON field value from partial JSON.
 * Returns { value, complete } or null if the field hasn't been started yet.
 * A string value is read verbatim; an ARRAY value reads its first string
 * element and appends " +N" for the further complete ones (Pop's batch shape).
 */
function extractFieldValueFromPartialJson(partialJson, fieldName) {
  const marker = '"' + fieldName + '"';
  const markerIdx = partialJson.indexOf(marker);
  if (markerIdx === -1) return null;

  let idx = markerIdx + marker.length;
  // Skip whitespace and colon
  while (idx < partialJson.length && /[\s:]/.test(partialJson[idx])) idx++;
  if (idx >= partialJson.length) return null;

  // Array form — zcode-484 rework: the array is the plan's usual Pop shape, and
  // the old single-quote-only guard made this whole branch dead (a `[` bailed
  // out with null, so the running row built no body at all).
  if (partialJson[idx] === '[') return readJsonArrayProgress(partialJson, idx + 1);

  if (partialJson[idx] !== '"') return null;
  const s = readJsonString(partialJson, idx + 1);
  return { value: s.value, complete: s.complete };
}

// rAF-throttled rendering (same pattern as appendThinkingDelta)
let _pendingToolStreamRAF = null;
let _toolStreamRafTarget = null;

/** ② 把当前累积参数流投到 turn 行细节位（toolline-detail 批）。
 *  worklineToolDetail 内部做 kind 守卫（仅运行中工具项）与文本比对，重复调用
 *  无副作用——参数流 rAF 帧与 toolStart 终值重放共用本入口。 */
function setWorklineToolDetail(view, toolName, rawJson) {
  const text = toolLineDetailText(toolName, rawJson);
  if (text) worklineToolDetail(view || activeView, text);
}

/** toolStart 时终值重放：参数 JSON 在 toolCallDetected→toolStart 之间已流毕
 *  （toolStart 不复位缓冲，main.js :931 只在下一个 toolCallDetected 复位），
 *  但 label 刷新可能换新建项（key 变化路径）——用累积流把细节行补回当前项。
 *  帧参数缺失（如历史重放/子代理腿无 toolArgDelta）时自然为空 → 不加行。 */
function reapplyWorklineDetailFromArgs(rawLabel) {
  const stream = activeView.stream;
  if (!stream.toolStreamText) return;
  const baseName = String(rawLabel || '').split('(')[0].split('\n')[0].trim();
  if (baseName && stream.toolStreamToolName && baseName !== stream.toolStreamToolName) return;
  setWorklineToolDetail(activeView, baseName || stream.toolStreamToolName, stream.toolStreamText);
}

export function appendToolStreamDelta(toolName, delta) {
  activeView.stream.toolStreamText += delta;
  activeView.stream.toolStreamToolName = toolName;

  const sid = activeView.sessionId;
  const pendingRow = state.sessionToolCards[sid];
  if (!pendingRow || !pendingRow.isConnected) return;

  _toolStreamRafTarget = {
    view: activeView,
    row: pendingRow,
    chat: activeView.dom.chat,
    snapped: activeView.stream.scrollSnapped,
    toolName: toolName,
    rawText: activeView.stream.toolStreamText,
  };

  if (!_pendingToolStreamRAF) {
    _pendingToolStreamRAF = requestAnimationFrame(() => {
      _pendingToolStreamRAF = null;
      const target = _toolStreamRafTarget;
      _toolStreamRafTarget = null;
      if (!target || !target.row || !target.row.isConnected) return;

      // Extract displayable content from partial JSON
      const fieldName = TOOL_PRIMARY_FIELDS[target.toolName];
      let displayContent = null;
      if (fieldName) {
        const extracted = extractFieldValueFromPartialJson(target.rawText, fieldName);
        if (extracted) displayContent = extracted.value;
      }
      // ② 类型化细节行（toolline-detail 批）：参数增量 → turn 行细节位流式。
      // 只做 textContent 替换（span 首次创建后跨帧复用，流式帧零节点重建——
      // 与 ① spinner 元素复用同纪律）；无字段可示时不建行。视图取 delta 时
      // 的归属视图（target.view），非刷帧时的 activeView——防中途切视图竞态。
      setWorklineToolDetail(target.view, target.toolName, target.rawText);
      if (displayContent === null) return; // primary field not started yet

      // Find or create streaming body in the tool card
      let bodyEl = target.row.querySelector('.tool-stream-body');
      if (!bodyEl) {
        bodyEl = document.createElement('div');
        bodyEl.className = 'tool-stream-body';
        const card = target.row.querySelector('.tool-card');
        if (card) {
          const contentDiv = card.querySelector('.content');
          if (contentDiv) contentDiv.appendChild(bodyEl);
          else card.appendChild(bodyEl);
        }
      }
      // Try syntax highlighting for code content (Write/Edit/Bash tools generate code).
      // Falls back to plain text if hljs unavailable or content too large.
      // Extract file_path from partial JSON for language detection (e.g. Write/Main.scala → Scala).
      const fpResult = extractFieldValueFromPartialJson(target.rawText, 'file_path');
      const highlightLabel = fpResult ? fpResult.value : target.toolName;
      const highlighted = (displayContent.length < 20000) ? highlightCode(displayContent, highlightLabel) : null;

      // Capture scroll state BEFORE content update — if content grows significantly,
      // the post-update threshold check would fail and miss the auto-scroll.
      const wasNearBottom = isNearBottom(bodyEl);

      // ── cardguard negative filter — tool-INPUT stream path (author ruling
      // 2026-09-17 · #698 A1) ───────────────────────────────────────────────
      // This face is INDEPENDENT of renderTool's result-side filter: the tool
      // ARGUMENTS (Write content / Edit new_string / Bash command …) stream here
      // while the model is still emitting them, so no tool_result exists yet.
      // A payload carrying the card sentinel ANYWHERE — the realistic shape
      // being ToolResultGuard's persisted preview — must not reach the DOM as
      // raw text. Pre-fix BOTH routes below leaked it verbatim: the hljs <pre>
      // route and the plain `<pre class="tool-body-pre">` fallback. Same regex
      // and only that regex, as in renderTool (:1139) and in the two replay
      // routes of persistence.js.
      // Branch taken = safe placeholder, deliberately NOT the card path: the
      // stream is a PARTIAL JSON field, so the 2048-char preview cut is
      // unparseable, and re-rendering a card every frame would churn iframes.
      // The finalized row goes through renderTool, whose own cardguard takes the
      // card path when the payload does parse (:1157). The placeholder carries
      // no "view source" escape hatch — no <pre>, no collapsed body: the raw
      // text stays out of the DOM entirely.
      const cgSentinelIdx = displayContent.search(/___\w+_HTML___/);
      if (cgSentinelIdx >= 0) {
        let cgPh = bodyEl.querySelector('[data-cardguard="placeholder"]');
        if (!cgPh) {
          bodyEl.innerHTML = '';
          cgPh = document.createElement('div');
          cgPh.className = 'body open';
          cgPh.dataset.cardguard = 'placeholder';
          bodyEl.appendChild(cgPh);
        }
        cgPh.textContent = t('chat.toolCardUnavailable');
      } else if (highlighted) {
        bodyEl.innerHTML = highlighted.replace(/<\/code><\/pre>$/, '<span class="cursor"></span></code></pre>');
      } else {
        bodyEl.innerHTML = '<pre class="tool-body-pre">' + escapeHtml(displayContent) + '<span class="cursor"></span></pre>';
      }

      // Auto-scroll tool body to keep latest content visible
      if (wasNearBottom) {
        bodyEl.scrollTop = bodyEl.scrollHeight;
      }

      // Auto-scroll chat — snapped captured at schedule time to match smartScroll()'s logic
      if (target.snapped === true || isNearBottom(target.chat)) {
        target.chat.scrollTop = target.chat.scrollHeight;
      }
    });
  }
}

/** Cancel pending rAF and clear target — called when tool finalizes or on cleanup. */
export function cancelToolStreamRAF() {
  if (_pendingToolStreamRAF) {
    cancelAnimationFrame(_pendingToolStreamRAF);
    _pendingToolStreamRAF = null;
  }
  _toolStreamRafTarget = null;
}

// ---------- Error ----------
export function renderError(msg) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row error';
  const card = document.createElement('div');
  card.className = 'error-card';
  // ui-status-redesign（S8 / 盘点 #30）：行首 circle-x 红图标 + 中性正文。
  card.innerHTML = statusIcon('circle-x', 14, 'st-err') + '<span>' + escapeHtml(msg) + '</span>';
  row.appendChild(card);
  appendChatRow(chat, row);
  smartScroll();
}

// ---------- Busy watchdog · 阶梯宽限 + 报/动分离 (freezetimeout B2) ----------
// 诊断真源 = `.nebflow/20260920_162251_webui-false-timeout-diagnosis__chain-n-36a3f13d.md`
// §4.2：判定模型缺独立活性信号（「通道静默 ≡ 死」），而三处看门（input.js:852/1186/1277）
// 一判到点就发 `{type:'interrupt'}` —— 破坏性动作建立在无依据判定上（后端自判 3646/3647
// 全为「仍在推进」）。本批落地：
//   ① 报/动分离：到点只呈现「仍在处理」，**绝不自动发 interrupt**；中断降级为需用户显式
//      确认（原「重试」两步确认 → 行内「仍要中断并重试」）。
//   ② 阶梯宽限：固定 (streamTimeoutMs+30s) ⇒ 到点逐级放宽 base → 2×base → 4×base（封顶）。
//   ③ 真死不放过：阶梯放宽到顶 ∧（WS 已断 ∨ 无任何活性证据），且到点回调未迟到 ⇒ 错误态。
//      呈现文案 = 断开如实态 `chat.connectionLost`（obsfix 微批 ①，作者令）——不得沿用
//      `chat.timeout`「仍在处理」（真死态与「仍在处理」语义相悖，判词位 O-1）。
//      落盘记录带 `i18nKey`（obsfix 微批 ②）⇒ 重载按中性 notice 复渲，不留常驻错误行。
// 活性证据 = 本档内收到过任何入站 WS 帧（`state.lastWsInboundAt`，ws.js 推进）——子代理
// 心跳帧正属该形态（缺 rootSessionId 路由键 ⇒ 不重置本 timer，但仍是通道活着的证据）。
// 主线程停摆（换页冻结）会让到点回调迟到 ⇒ 计时不可信 ⇒ 一律只放宽、不判死。
// 🔴 后端独立示活通道（AgentCore/protocol/ToolHeartbeat）= B1，作者裁决项，本批禁碰。
const WATCHDOG_RUNG_FACTORS = [1, 2, 4];
const WATCHDOG_MAX_RUNG = WATCHDOG_RUNG_FACTORS.length - 1;
const WATCHDOG_LATE_TOLERANCE_MS = 15000;
const _watchdogRung = new Map();        // sid -> 档位索引

/** 第 rung 档预算 = (streamTimeoutMs + 30s) × 倍率，超出最高档即封顶。 */
function watchdogBudgetMs(rung) {
  const base = (Number(state.streamTimeoutMs) || 600000) + 30000;
  const idx = Math.max(0, Math.min(rung, WATCHDOG_MAX_RUNG));
  return base * WATCHDOG_RUNG_FACTORS[idx];
}

function clearWatchdogTimer(sid) {
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
}

/** 武装第 rung 档（沿用 state.sessionBusyTimeouts 槽位：外部各处 clear 面不变）。 */
function armWatchdogRung(sid, rung) {
  const budget = watchdogBudgetMs(rung);
  const deadlineAt = Date.now() + budget;
  _watchdogRung.set(sid, rung);
  state.sessionBusyTimeouts[sid] = setTimeout(() => onBusyWatchdogDeadline(sid, deadlineAt), budget);
}

/** 新 turn 起点（send / 队列 drain / inject）：从第 0 档起算，并清掉上一轮的呈现。 */
export function armBusyWatchdog(sid) {
  if (!sid) return;
  removeStillProcessingNotice(sid);
  clearWatchdogTimer(sid);
  armWatchdogRung(sid, 0);
}

/** 喂活（= 原 main.js resetStreamTimeout 语义）：本档作废、回第 0 档重新计时；有真进展
 *  ⇒ 已呈现的「仍在处理」行收掉（活性恢复即误报解除）。 */
export function feedBusyWatchdog(sid) {
  if (!sid || !state.busySessionIds.has(sid)) return;
  removeStillProcessingNotice(sid);
  clearWatchdogTimer(sid);
  armWatchdogRung(sid, 0);
}

/** 到点判定 = **判定与动作分离的唯一决策点**（不再有自动 interrupt 分支）。 */
export function onBusyWatchdogDeadline(sid, deadlineAt) {
  if (!sid) return;
  if (!state.busySessionIds.has(sid)) { clearWatchdogTimer(sid); return; }
  const rung = _watchdogRung.get(sid) || 0;
  const stalled = (Date.now() - deadlineAt) > WATCHDOG_LATE_TOLERANCE_MS;
  const armedAt = deadlineAt - watchdogBudgetMs(rung);
  const inbound = Number(state.lastWsInboundAt) || 0;
  const hasLiveness = stalled || inbound > armedAt;
  const wsAlive = !!(state.ws && state.ws.readyState === WebSocket.OPEN);
  if (!stalled && rung >= WATCHDOG_MAX_RUNG && (!wsAlive || !hasLiveness)) {
    // 真死：阶梯放宽到顶 ∧（WS 已断 ∨ 无任何活性证据）⇒ 仍进错误态（不放过）。
    // 🔴 freezetimeout obsfix 微批 ①（作者令「真死文案如实态」）：本支呈现由
    //    `renderTimeoutNotice`（`chat.timeout`「仍在处理」）改为 `chat.connectionLost`
    //    「连接已断开，点击重试」——真死态呈现「仍在处理」语义相悖（判词位 O-1）。
    //    子案（socket 已断 / socket 活着但零活性证据）落 `data-watchdog-dead-reason`
    //    契约面；两条子案同文案（本支判定 = 该 turn 的通道已死，分支级如实态）。
    //    慢速分支呈现（`chat.stillProcessing`）与后端 timeout 帧路径**零变动**。
    const deadReason = !wsAlive ? 'ws-closed' : 'no-inbound';
    const v = findViewBySessionId(sid);
    if (v) { setActiveView(v); renderConnectionLostNotice(sid, deadReason); clearStatus(); }
    clearWatchdogTimer(sid);
    _watchdogRung.delete(sid);
    clearBusy(sid);   // ⇒ removeStillProcessingNotice（chat.js:271）：真死态不留「仍在处理」行
    return;
  }
  // 慢 ≠ 死：只呈现 + 放宽一档 —— 不发 interrupt、不清 busy、不 drain 队列。
  // 到顶后原地续档（继续呈现，不再放宽），把判死权交回「真死」分支的条件面。
  const nextRung = Math.min(rung + 1, WATCHDOG_MAX_RUNG);
  renderStillProcessingNotice(sid, nextRung);
  armWatchdogRung(sid, nextRung);
}

/** 「仍在处理」呈现（**非** `.row error` ⇒ 不被 countsAsRealMessage 计入，未读语义不受
 *  误报污染；§4.3②）。文案全部取自 §16 冻结逐字表，本批零新增文案。
 *  幂等：同一会话同一时刻只留一行（阶梯每次到点只续档，不叠行）。 */
export function renderStillProcessingNotice(sid, rung = 0) {
  const v = findViewBySessionId(sid) || activeView;
  if (!v || !v.dom || !v.dom.chat) return null;
  const chat = v.dom.chat;
  const existing = chat.querySelector(`.still-processing-row[data-still-processing="${sid}"]`);
  if (existing) {
    // 已呈现 ⇒ 只更新档位契约（data-* 二值断言面，阶梯到点不叠行）。
    existing.dataset.stillProcessingRung = String(rung);
    return null;
  }
  const row = document.createElement('div');
  row.className = 'row notice still-processing-row';
  row.dataset.stillProcessing = sid;
  row.dataset.stillProcessingRung = String(rung);
  const card = document.createElement('div');
  card.className = 'notice-card notice-info';
  card.style.display = 'flex';
  card.style.alignItems = 'center';
  card.style.gap = '12px';
  const text = document.createElement('span');
  text.textContent = t('chat.stillProcessing');
  card.appendChild(text);
  const btnCss = 'padding:4px 12px;border-radius:6px;border:1px solid var(--color-frame-border);background:var(--color-frame-hover);color:var(--color-frame-text);cursor:pointer;font-size:13px;font-family:inherit;';
  // ① 首次动作：`chat.retry`（文案不变、语义变为**需确认**）——turn 仍在跑时不发 interrupt，
  //    只把行推进到确认态；turn 已结束（busy 已清）则直接重发，无需确认。
  const retryBtn = document.createElement('button');
  retryBtn.className = 'still-processing-retry';
  retryBtn.textContent = t('chat.retry');
  retryBtn.style.cssText = btnCss;
  retryBtn.onmouseenter = () => { retryBtn.style.background = 'var(--color-frame-active)'; };
  retryBtn.onmouseleave = () => { retryBtn.style.background = 'var(--color-frame-hover)'; };
  retryBtn.onclick = () => {
    if (state.busySessionIds.has(sid)) {
      // 仍在跑 ⇒ 需要显式确认才允许中断（确认态：只揭示确认键，本键不再可点）。
      retryBtn.remove();
      interruptBtn.style.display = '';
      return;
    }
    row.remove();
    resendLastInput(v);
  };
  card.appendChild(retryBtn);
  // ② 显式确认键（`chat.stillProcessing.interrupt`）：用户动作 ⇒ 才允许发 interrupt，
  //    随后按既有「重试」语义重发上一条输入。
  const interruptBtn = document.createElement('button');
  interruptBtn.className = 'still-processing-interrupt';
  interruptBtn.textContent = t('chat.stillProcessing.interrupt');
  interruptBtn.style.cssText = btnCss;
  interruptBtn.style.display = 'none';   // 确认态才出现（见 ①）
  interruptBtn.onmouseenter = () => { interruptBtn.style.background = 'var(--color-frame-active)'; };
  interruptBtn.onmouseleave = () => { interruptBtn.style.background = 'var(--color-frame-hover)'; };
  interruptBtn.onclick = () => {
    sendWs({ type: 'interrupt', sessionId: sid });
    row.remove();
    resendLastInput(v);
  };
  card.appendChild(interruptBtn);
  row.appendChild(card);
  appendChatRow(chat, row);
  smartScroll();
  // 取证面（A 开放项 3）：本类误报行原本不落任何记录 ⇒ 事后不可复查。落一条 system
  // 记录进本地会话缓存（backend 侧无对应帧，因此这是唯一可盘查的痕迹）。动态 import
  // 同 renderSystemBubble：persistence.js 静态依赖本模块，反向静态 import 会成环。
  // obsfix 微批 ②（作者令）：记录带 `i18nKey`（+ 同串 `content` 保底）⇒ 共享缓存恢复
  // 分支按 key 复渲成**中性 notice**（存 key 不存 HTML）；无 key 的存量记录回落 content。
  import('./persistence.js')
    .then(({ saveMsg }) => { try { saveMsg({ type: 'system', i18nKey: 'chat.stillProcessing', content: t('chat.stillProcessing') }, sid); } catch (e) { /* 缓存写失败不影响呈现 */ } })
    .catch(() => {});
  return row;
}

/** 收掉某会话的「仍在处理」行（活性恢复 / 新 turn / 终态 / 进入错误态时调用）。 */
export function removeStillProcessingNotice(sid) {
  if (!sid) return;
  try {
    Object.values(chatViews).forEach((v) => {
      if (!v || !v.dom || !v.dom.chat) return;
      const row = v.dom.chat.querySelector(`.still-processing-row[data-still-processing="${sid}"]`);
      if (row) row.remove();
    });
  } catch (e) { /* 呈现层清理失败不影响判定 */ }
}

/** 重发输入历史末条（原「重试」按钮语义，保持不变）。 */
function resendLastInput(v) {
  const history = state.inputHistory;
  const lastMsg = history.length > 0 ? history[history.length - 1] : '';
  if (!lastMsg) return;
  if (v && v.dom && v.dom.input) v.dom.input.value = lastMsg;
  import('./input.js').then(({ send }) => { setActiveView(v); send(); });
}

// ---------- 终态行（超时 / 连接已断开）with retry ----------
/** 终态行单点构造 —— 后端 `timeout` 帧（`renderTimeoutNotice`）与看门真死
 *  （`renderConnectionLostNotice`）共用，两者只差文案 key 与 `data-*` 契约属性。
 *  🔴 行为面与本批前**逐字一致**（`.row error` 容器 / `error-card` 内联样式 /
 *  「重试」键语义 / 落盘时机）；唯一增量 = 落盘记录多一个 `i18nKey`
 *  （obsfix 微批 ②：重载路径按 key 复渲，**不存 HTML**）。 */
function renderTerminalRow(sid, i18nKey, dataset = {}) {
  const v = activeView; // capture before callback
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row error';
  Object.entries(dataset).forEach(([k, val]) => { row.dataset[k] = String(val); });
  const card = document.createElement('div');
  card.className = 'error-card';
  card.style.display = 'flex';
  card.style.alignItems = 'center';
  card.style.gap = '12px';
  // ui-status-redesign（S8 同卡）：行首 circle-x 红图标，正文中性字。
  const iconSpan = document.createElement('span');
  iconSpan.setAttribute('aria-hidden', 'true');
  iconSpan.innerHTML = statusIcon('circle-x', 14, 'st-err');
  card.appendChild(iconSpan);
  const text = document.createElement('span');
  text.textContent = t(i18nKey);
  card.appendChild(text);
  const btn = document.createElement('button');
  btn.textContent = t('chat.retry');
  btn.style.cssText = 'padding:4px 12px;border-radius:6px;border:1px solid var(--color-frame-border);background:var(--color-frame-hover);color:var(--color-frame-text);cursor:pointer;font-size:13px;font-family:inherit;';
  btn.onmouseenter = () => { btn.style.background = 'var(--color-frame-active)'; };
  btn.onmouseleave = () => { btn.style.background = 'var(--color-frame-hover)'; };
  btn.onclick = () => {
    row.remove();
    // 终态（后端 timeout 帧 / 真死）下 turn 已结束 ⇒ 重发无需确认中断。
    resendLastInput(v);
  };
  card.appendChild(btn);
  row.appendChild(card);
  appendChatRow(chat, row);
  smartScroll();
  // 取证面（A 开放项 3 · 原不落任何记录 ⇒ 误报事后不可复查；obsfix ② 起带 i18nKey）。
  import('./persistence.js')
    .then(({ saveMsg }) => { try { saveMsg({ type: 'system', i18nKey, content: t(i18nKey) }, sid || state.activeSessionId); } catch (e) { /* 同上 */ } })
    .catch(() => {});
  return row;
}

/** 后端 `timeout` 帧终态行（`chat.timeout` = §5.1 冻结值「仍在处理」，**零变动**）。 */
export function renderTimeoutNotice(sid) {
  return renderTerminalRow(sid, 'chat.timeout');
}

/** 看门真死终态行（obsfix 微批 ①）= 断开如实态 `chat.connectionLost`
 *  「连接已断开，点击重试」。`reason` ∈ `'ws-closed'`（socket 已断）| `'no-inbound'`
 *  （socket 活着但本档窗口内零入站帧）——判定子案落 `data-watchdog-dead-reason`，
 *  文案为分支级单值（判定 = 该 turn 的通道已死）。 */
export function renderConnectionLostNotice(sid, reason) {
  return renderTerminalRow(sid, 'chat.connectionLost', { watchdogDead: '1', watchdogDeadReason: reason || 'unknown' });
}

// ---------- System bubble ----------
export function renderSystemBubble(text) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row notice';
  const card = document.createElement('div');
  card.className = 'notice-card notice-info';
  card.textContent = text;
  row.appendChild(card);
  appendChatRow(chat, row);
  smartScroll();
  // Persist to localStorage and backend (via recording ws send)
  import('./persistence.js').then(({ saveMsg }) => saveMsg({type: 'system', content: text}));
  return { type: 'system', text };
}

// ---------- Compaction status card ----------
// Status card for the compactStart → compactComplete/Failed lifecycle: one
// card that morphs in place (spinning → done/failed) instead of two plain
// notice bubbles. buildCompactCardRow is the SINGLE source of the card DOM —
// the live lifecycle and the history-replay restore (persistence.js) both
// build their cards through it, so a refreshed history shows the same card
// component as the live view.
const compactCheckSvg = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>';
const compactFailSvg = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>';

/** Build one compaction card row. kind: 'active' (spinner) | 'done' (check)
 *  | 'error' (cross). label is plain text and is escaped here. startTs rides
 *  on the card as data-start-ts (live only — replay has no start time). */
export function buildCompactCardRow(kind, label, startTs = 0) {
  const row = document.createElement('div');
  row.className = 'row notice';
  const card = document.createElement('div');
  card.className = 'compact-card';
  card.dataset.state = kind;
  if (startTs) card.dataset.startTs = String(startTs);
  const icon = kind === 'done'
    ? `<span class="compact-card-icon ok">${compactCheckSvg}</span>`
    : kind === 'error'
      ? `<span class="compact-card-icon err">${compactFailSvg}</span>`
      : '<span class="compact-card-spinner"></span>';
  card.innerHTML = icon + `<span class="compact-card-label">${escapeHtml(label)}</span>`;
  row.appendChild(card);
  return row;
}

function findActiveCompactCard(view) {
  return view?.dom?.chat?.querySelector('.compact-card[data-state="active"]') || null;
}

function appendCompactCard(view, state, label, startTs) {
  const chat = view?.dom?.chat;
  if (!chat) return null;
  const row = buildCompactCardRow(state, label, startTs);
  appendChatRow(chat, row);
  smartScroll();
  return row.querySelector('.compact-card');
}

export function renderCompactStartCard(view = activeView) {
  if (!view?.dom?.chat) return;
  if (findActiveCompactCard(view)) return; // one active card at a time
  appendCompactCard(view, 'active', t('chat.compactingCard'), Date.now());
}

export function renderCompactDoneCard(view = activeView, { before, after, detail } = {}) {
  const active = findActiveCompactCard(view);
  const startTs = Number(active?.dataset.startTs) || 0;
  const elapsed = startTs ? Math.max(1, Math.round((Date.now() - startTs) / 1000)) : 0;
  let label = t('chat.compacted', { before, after, detail: detail || '' });
  if (elapsed) label += t('chat.compactElapsed', { seconds: elapsed });
  if (active) {
    active.dataset.state = 'done';
    delete active.dataset.startTs;
    active.innerHTML = `<span class="compact-card-icon ok">${compactCheckSvg}</span><span class="compact-card-label">${escapeHtml(label)}</span>`;
  } else {
    // No active card (view was restored/switched mid-compaction) — append a
    // card directly in its final state.
    appendCompactCard(view, 'done', label, 0);
  }
  smartScroll();
}

export function renderCompactFailCard(view = activeView, text) {
  const active = findActiveCompactCard(view);
  if (active) {
    active.dataset.state = 'error';
    delete active.dataset.startTs;
    active.innerHTML = `<span class="compact-card-icon err">${compactFailSvg}</span><span class="compact-card-label">${escapeHtml(text)}</span>`;
  } else {
    appendCompactCard(view, 'error', text, 0);
  }
  smartScroll();
}


// ---------- Universal Option Box ----------
// Renders an inline option picker. Used by AskUser tool, /thinking, permission prompts.
const ASKUSER_DRAFTS_KEY = key('askuser_drafts');

// AskUser cards registry — lets Canvas iframes answer a visible single-choice
// question via postMessage (askuser-canvas-integration-spec, direction C §3.2).
// The Canvas button is a REMOTE TRIGGER: it writes into the card's answers
// array and runs the same confirm path — it does not hold state. Only cards
// rendered via renderAskUser carry an askSessionId; permission prompts /
// slash-cmd pickers pass undefined and stay out of the registry.
//
// 多 AskUser 并发批（#250 第④项，2026-09-13 作者裁定「6 项全补」）：
// 改前是 `Map<sessionId, entry>` —— **每会话单值（新卡覆盖旧卡）**，多卡并发时
// 旧卡不在 registry 里：它的 Canvas 提交（`_nfAskAnswer`）找不到 entry，
// 被**静默丢弃**（`:1582` 的 `if (!entry) return`），而卡片看起来仍可作答。
// 现在按卡登记（键 = sessionId + requestId），并用可见方式处理歧义提交。
const askCardRegistry = new Map(); // key(sessionId::requestId) → { sessionId, requestId, questions, answers, shouldShow, selectOption, confirmIfReady }

/** Registry key — requestId-scoped when the card has one (every AskUser card
 *  does; the requestId-less call sites never register at all). */
function askCardKey(sessionId, requestId) {
  return `${sessionId || ''}::${requestId || ''}`;
}

/** Every live (registered = not yet locked/answered) card of one session. */
function askCardsOf(sessionId) {
  return [...askCardRegistry.values()].filter(e => e.sessionId === sessionId);
}

/** Drop exactly one card's registry entry (#250 ④: never the whole session's). */
function dropAskCard(sessionId, requestId) {
  if (!sessionId) return false;
  return askCardRegistry.delete(askCardKey(sessionId, requestId));
}

/** #250 ④/⑥: an unroutable Canvas answer must not vanish silently — tell the
 *  page (the extended protocol replies with `_nfAskAnswerRejected`) and the
 *  user (glass toast). The card stays answerable by hand. */
function rejectAskAnswer(e, payload, candidateIds) {
  try {
    const target = /** @type {any} */ (e.source);
    if (target && typeof target.postMessage === 'function') {
      target.postMessage(
        { _nfAskAnswerRejected: { sessionId: payload.sessionId, requestId: payload.requestId || '', candidates: candidateIds, reason: 'ambiguous-card' } },
        '*'
      );
    }
  } catch { /* cross-origin/no window — the user-visible outlet below still fires */ }
  window.__showToast?.(t('askUser.canvasAmbiguous'), 'info');
}

/** Build the inline preview slot for an option (direction C §2.1/§4.1).
 *  swatch: 1-5 color stripes filling the 56×40 slot; image: object-fit
 *  contain — full fit, never crop (cover clipped square viewBox-only SVG
 *  icons in the 56×40 slot, author report 2026-09-07).
 *  Returns '' when the preview is absent/invalid so the button renders exactly
 *  like the pre-preview version (E1/E3 zero regression). */
function buildOptionPreview(pv) {
  if (!pv || typeof pv !== 'object') return '';
  if (pv.type === 'swatch') {
    const colors = Array.isArray(pv.colors) ? pv.colors.filter(c => typeof c === 'string' && c.trim()) : [];
    if (colors.length === 0) return ''; // E3: empty swatch → no preview
    const inner = colors.map((c, i) =>
      `<span class="preview-swatch" style="background:${escapeHtml(c.trim())};${i > 0 ? 'border-left:1px solid var(--color-surface)' : ''}"></span>`
    ).join('');
    return `<span class="option-preview" aria-hidden="true">${inner}</span>`;
  }
  if (pv.type === 'image' && pv.src) {
    // §6 preview fades in on load (200ms ease-out); on error the slot hides
    // itself (E2) — label/desc stay clickable either way.
    return `<span class="option-preview" aria-hidden="true"><img class="preview-img" src="${escapeHtml(normalizeSvgDataUri(pv.src))}" alt="" loading="lazy" onload="this.classList.add('nf-loaded')" onerror="this.closest('.option-preview').style.display='none'"></span>`;
  }
  return '';
}

/** Compat fallback for viewBox-only SVG data-URI previews (author report
 *  2026-09-07): LLM-generated icon srcs commonly carry a viewBox but no
 *  width/height attrs, and concrete sizing for dimensionless SVGs is
 *  under-specified across engines. Inject explicit intrinsic dimensions taken
 *  from the viewBox so object-fit: contain letterboxes deterministically
 *  everywhere. Strictly fail-open: any surprise (non-SVG src, unparseable
 *  payload, existing dims, degenerate viewBox) returns the src untouched —
 *  the CSS contain fix alone still renders those. Non-data-URI srcs (http,
 *  png/jpeg data-URIs) are never touched (E1/E3 zero-regression surface). */
function normalizeSvgDataUri(src) {
  if (typeof src !== 'string' || !src.startsWith('data:image/svg+xml')) return src;
  const comma = src.indexOf(',');
  if (comma < 0) return src;
  const header = src.slice(0, comma + 1);
  const body = src.slice(comma + 1);
  let svg;
  if (/;base64/i.test(header)) {
    try {
      svg = new TextDecoder().decode(Uint8Array.from(atob(body), (ch) => ch.charCodeAt(0)));
    } catch { return src; }
  } else {
    try { svg = decodeURIComponent(body); } catch { return src; }
  }
  const tag = svg.match(/<svg\b[^>]*>/i);
  if (!tag) return src;
  if (/\bwidth\s*=|\bheight\s*=/i.test(tag[0])) return src; // already has intrinsic dims
  const vb = tag[0].match(/\bviewBox\s*=\s*(?:"([^"]*)"|'([^']*)')/i);
  if (!vb) return src;
  const dims = (vb[1] ?? vb[2]).trim().split(/[\s,]+/).map(Number);
  if (dims.length !== 4 || dims.some(n => !Number.isFinite(n)) || dims[2] <= 0 || dims[3] <= 0) return src;
  const fixed = svg.replace(tag[0], tag[0].replace(/^<svg/i, `<svg width="${dims[2]}" height="${dims[3]}" `));
  if (/;base64/i.test(header)) {
    const bytes = new TextEncoder().encode(fixed);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return header + btoa(bin);
  }
  return header + encodeURIComponent(fixed);
}

/** Broadcast an answered/locked AskUser card to every Canvas iframe so their
 *  embedded "pick this" buttons disable (§5.1 S5, spec §11.2). */
function broadcastAskState(sid, requestId) {
  // #250 ④: `requestId` 随状态一起广播（新增字段，旧页面忽略）——一个会话里多张
  // 卡并存时，页面据此只关掉自己那张的按钮，而不是整会话一刀切。
  document.querySelectorAll('.canvas-tab-pane iframe').forEach(iframe => {
    try {
      /** @type {HTMLIFrameElement} */ (iframe).contentWindow?.postMessage({ _nfAskState: { sessionId: sid, requestId: requestId || '', answered: true } }, '*');
    } catch { /* cross-origin/no window — ignore */ }
  });
}

// Canvas → parent answer channel (direction C §3.2). The iframe content is
// UNTRUSTED (agent-produced HTML) — every payload is validated against the
// current visible card state; anything not matching is silently dropped.
// Bound once at module scope; cards self-register via showOptions.
window.addEventListener('message', (e) => {
  const payload = e.data && e.data._nfAskAnswer;
  if (!payload) return;
  // #250 ④: route to the EXACT card. A payload that carries the requestId
  // (extended protocol — also broadcast back in `_nfAskState`) goes straight to
  // that card; a requestId-less payload (older canvas pages) is routable only
  // when the session has exactly ONE live card. Multiple candidates = ambiguous:
  // reject visibly instead of picking one silently (the old single-slot registry
  // silently dropped the older card's submission).
  const payloadRid = typeof payload.requestId === 'string' ? payload.requestId : '';
  const cards = askCardsOf(payload.sessionId).filter(entry => !payloadRid || entry.requestId === payloadRid);
  if (cards.length === 0) return;                  // E9 no such card / E6 already locked
  if (cards.length > 1) {
    rejectAskAnswer(e, payload, cards.map(c => c.requestId));
    return;
  }
  const entry = cards[0];
  const qi = Number(payload.questionIndex);
  if (!Number.isInteger(qi) || qi < 0) return;
  const item = entry.questions[qi];
  if (!item || item.multiple === true) return;     // E4 multi stays on the card path
  if (!entry.shouldShow(qi)) return;               // E5 dependsOn-hidden question
  const answer = typeof payload.answer === 'string' ? payload.answer : '';
  const labels = (item.options || []).map(o => typeof o === 'string' ? o : o.label);
  if (!labels.includes(answer)) return;            // E7 invalid label (incl. Other free text)
  entry.selectOption(qi, answer);
  entry.confirmIfReady();                          // S3b → S4: confirm when all visible answered
});

function loadAskDrafts(sid) {
  try { return JSON.parse(localStorage.getItem(ASKUSER_DRAFTS_KEY))?.[sid] || {}; } catch { return {}; }
}

function saveAskDraft(sid, qi, value) {
  try {
    const all = JSON.parse(localStorage.getItem(ASKUSER_DRAFTS_KEY)) || {};
    if (!all[sid]) all[sid] = {};
    if (value) all[sid][qi] = value;
    else delete all[sid][qi];
    localStorage.setItem(ASKUSER_DRAFTS_KEY, JSON.stringify(all));
  } catch { /* ignore */ }
}

function clearAskDrafts(sid) {
  try {
    const all = JSON.parse(localStorage.getItem(ASKUSER_DRAFTS_KEY)) || {};
    delete all[sid];
    localStorage.setItem(ASKUSER_DRAFTS_KEY, JSON.stringify(all));
  } catch { /* ignore */ }
}

// ---------- 共用层：已决卡「禁互动」单一闸（2026-09-18 作者报单） ----------
/** 已决卡内「可交互件」的机械选择器。`[tabindex]` / `[role=*]` 一并纳入：自绘控件
 *  （div/span + role/tabindex）同样必须出 tab 序并交出点击。 */
const LOCKABLE_SELECTOR =
  'button, a, textarea, input, select, [role="button"], [role="checkbox"], [tabindex]';

/** 卡级「已决 ⇒ 禁互动」单一闸（共用层）。
 *  作者报单 2026-09-18：工作区选择卡在「已取消 / 已完成选择」后**仍可互动**（选项还
 *  能点），预期已决后置灰、不可互动。根因 = 已决动作此前分散在三组选择器
 *  （.option-btn / .option-confirm / .option-cancel）上逐件置 disabled，而卡内其它可
 *  点件（dirPicker 卡的 `.ws-pick-target` 目标件与「卡片空白区」wrapper handler，
 *  见本文件 dirPicker 块）不在任何一条选择器内 ⇒ 已决后仍能重开目录浏览器。
 *  改为单一闸收敛（未来新增可点件自动纳入）：
 *   ① 卡内全部可交互件 → disabled + aria-disabled + tabindex=-1（机械选择器）；
 *   ② 卡上加 `resolved` 类 —— 置灰视觉由 chat.css 的 `.option-box.resolved` 块承载；
 *   ③ capture 阶段点击哨兵：已决卡内任意点击 preventDefault + stopPropagation ⇒ 同时
 *      覆盖「目标件」与「卡片空白区」两个入口，且卡内 listener 一个都不会跑到。
 *  🔴 故意不用整卡 pointer-events:none：会连带杀死既有可复制面（R5「复制载荷」裁定，
 *  复制钮挂行级 .duration-badge，在卡外）与卡内文本选中面 ⇒ 禁互动由 ①③ 承担，② 只管视觉。
 *  🔴 pending 卡零变化：本函数只在已决动作处调用（cancel / confirm / closeAskUserCard /
 *  renderAskUserHistory 四处）。幂等：已带 `resolved` 类直接返回。 */
function lockOptionBox(box) {
  if (!box || box.classList.contains('resolved')) return;
  box.classList.add('resolved');
  box.querySelectorAll(LOCKABLE_SELECTOR).forEach((el) => {
    el.setAttribute('aria-disabled', 'true');
    el.setAttribute('tabindex', '-1');
    if (el instanceof HTMLButtonElement || el instanceof HTMLInputElement ||
        el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement) {
      el.disabled = true;
    }
  });
  // ③ 点击哨兵：capture 阶段在卡内任意入口的 listener 之前拦下（含程序化 .click()）。
  box.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
  }, true);
}

export function showOptions(container, questions, onConfirm, doneLabel, onCancel, askSessionId, requestId) {
  const box = document.createElement('div');
  box.className = 'option-box';
  // Tag the card with its requestId so the requestId-keyed answer/close frames
  // (askUserAnswer routing #12, askUserClosed D6-F2) and the Canvas answer
  // registry can locate the exact card they own. (The retired chat-input
  // passthrough used to be the third consumer of this tag — see e59ed251d.)
  // Permission prompts / onboarding pass no
  // requestId and stay untagged.
  if (requestId) box.dataset.requestId = requestId;
  const answers = new Array(questions.length).fill(null);
  // askuser-secret 批（规格 §1/§3）：secret 题的值**只**进这一槽（confirm 时折进
  // secretSlots 专用 WS 键）——绝不写 answers[qi]，因此普通答案通道/草稿/历史行
  // 都不会碰到值。null = 未作答（confirm 闸与 shouldShow 重置共用该语义）。
  const secretValues = new Array(questions.length).fill(null);
  const confirmLabel = doneLabel || t('chat.confirm');
  const saved = askSessionId ? loadAskDrafts(askSessionId) : {};
  const questionWrappers = [];

  // --- Conditional branching helpers ---
  // The DOM mechanics (display toggle, reveal animation, hide-reset hooking)
  // live in depVisibility.js (shared with the /model panel). This side keeps
  // only what is AskUser-specific: the predicate (a question is visible when
  // its dependsOn matches the referenced answer) and the answer reset.
  function shouldShow(qi) {
    const item = questions[qi];
    if (!item.dependsOn) return true;
    const dep = item.dependsOn;
    const refIdx = questions.findIndex(q => q.id === dep.ref);
    if (refIdx === -1) return true;
    const a = answers[refIdx];
    // Multi-select ref question: the dependency matches when equals is among the selections
    return Array.isArray(a) ? a.includes(dep.equals) : a === dep.equals;
  }

  const visibility = createDepVisibility({
    isVisible: (qi) => shouldShow(qi),
    onHide: (qi, wrapper) => {
      // A-2 (onboarding spec §7): hiding a question resets its answer so a
      // stale pick can never ride along on submit.
      if (answers[qi] !== null) {
        answers[qi] = null;
        wrapper.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
        const input = /** @type {HTMLTextAreaElement|null} */ (wrapper.querySelector('.option-custom-input'));
        if (input) input.value = '';
      }
      // askuser-secret 批：被条件隐藏的 secret 题同样清值（DOM 输入 + 槽位），
      // 陈旧密码不得随提交顺路（与 A-2 同一语义，第二槽位面）。
      if (secretValues[qi] !== null && secretValues[qi] !== undefined) {
        secretValues[qi] = null;
        const pw = wrapper.querySelector('.option-secret-input');
        if (pw) pw.value = '';
      }
    },
  });
  function updateVisibility() {
    visibility.update();
  }

  // askuser-secret 批：作答判定单一谓词（初始态与 checkAllAnswered 共用）——
  // 普通题看 answers[qi]；secret 题看 secretValues[qi]（非空 = 已作答）。
  function isAnswered(qi) {
    if (questions[qi] && questions[qi].secret === true) return !!secretValues[qi];
    return answers[qi] !== null;
  }

  // --- Multi-select: recompute answers[qi] from the DOM picked state ---
  // answers[qi] is an array of selected option labels (plus Other text when
  // typed) or null when nothing is picked. Serialized as a JSON array string
  // on submit — the answers wire stays one string slot per question.
  function syncMulti(qi) {
    const wrapper = questionWrappers[qi];
    if (!wrapper) return;
    const labels = [];
    wrapper.querySelectorAll('.option-btn.picked').forEach(el => {
      if (el.dataset.other !== '1' && el.dataset.label) labels.push(el.dataset.label);
    });
    const otherPicked = wrapper.querySelector('.option-btn[data-other="1"].picked');
    const input = wrapper.querySelector('.option-custom-input');
    if (otherPicked && input && input.value.trim()) labels.push(input.value.trim());
    answers[qi] = labels.length ? labels : null;
  }

  // --- Build question DOM ---
  questions.forEach((item, qi) => {
    const wrapper = document.createElement('div');
    wrapper.className = 'option-q-wrapper';
    questionWrappers.push(wrapper);
    visibility.register(wrapper);

    const q = document.createElement('div');
    q.className = 'option-q';
    q.innerHTML = item.question;
    wrapper.appendChild(q);

    // askuser-secret 批（规格 §1/§3）：secret 题 = 密码框（遮蔽 + 「显示」眼睛
    // 切换）。零选项、零 textarea、零草稿恢复（freeInput:false 先例）——整段
    // 选项/自由输入机制经 early-return 跳过。值只进 secretValues[qi]；确认后
    // DOM 值立即清空（confirm 路径）。inline 样式自包含（本批不动 chat.css）。
    if (item.secret === true) {
      const row = document.createElement('div');
      row.className = 'option-secret-row';
      row.style.cssText = 'display:flex;gap:8px;align-items:stretch;margin-top:6px;';
      const pw = document.createElement('input');
      pw.type = 'password';
      pw.className = 'option-secret-input';
      pw.autocomplete = 'off';
      pw.spellcheck = false;
      pw.placeholder = t('askUser.secretPlaceholder');
      pw.style.cssText = 'flex:1;min-width:0;padding:8px 10px;border-radius:8px;' +
        'border:1px solid var(--color-border, rgba(128,128,128,0.35));' +
        'background:var(--color-surface, rgba(127,127,127,0.08));color:inherit;font:inherit;';
      const eye = document.createElement('button');
      eye.type = 'button';
      eye.className = 'option-secret-eye';
      eye.textContent = t('askUser.secretShow');
      eye.style.cssText = 'padding:6px 10px;border-radius:8px;border:1px solid var(--color-border, rgba(128,128,128,0.35));' +
        'background:transparent;color:var(--color-text-muted, #888);font:inherit;cursor:pointer;';
      eye.onclick = () => {
        const show = pw.type === 'password';
        pw.type = show ? 'text' : 'password';
        eye.textContent = show ? t('askUser.secretHide') : t('askUser.secretShow');
      };
      pw.oninput = () => {
        secretValues[qi] = pw.value;
        updateVisibility();
        checkAllAnswered();
      };
      row.appendChild(pw);
      row.appendChild(eye);
      wrapper.appendChild(row);
      const hint = document.createElement('div');
      hint.className = 'option-secret-hint';
      hint.style.cssText = 'margin-top:6px;font-size:12px;color:var(--color-text-muted, #888);';
      hint.textContent = item.ephemeral ? t('askUser.secretEphemeralHint') : t('askUser.secretHint');
      wrapper.appendChild(hint);
      box.appendChild(wrapper);
      updateVisibility();
      return; // secret 题到此为止：下面的选项/自由输入/草稿机制整段跳过
    }

    // 工作区选择卡（dirPicker=true）：问题下方渲染「选择工作区」大目标——内联 SVG
    // 描边文件夹图标（禁 emoji）。点击目标或卡片空白区整体 → 应用内目录浏览器
    // （workspacePicker.js，2026-09-06 作者拍板：复用文件浏览器「选择目录」设计 +
    // 新建文件夹，不走系统对话框）。2026-09-09 作者裁定原句保留可读：「后端不下发
    // 候选 options——无候选 chips、无「其他…」按钮；空 options 使下方自由输入
    // textarea 直接可见（~ 手输兜底，展开由后端负责）」——其中**末一条已被
    // 2026-09-17 作者裁定取代**（S3 ②-5/②-6/②-7）：dirPicker 卡显式 freeInput=false
    // ⇒ 不渲染 textarea、跳过草稿恢复（答案只由选择面写入）；点选即自动提交保持不变；
    // 选择面不可用时按需揭示降级输入面并聚焦，禁死路。「不下发候选 options」一条
    // 继续成立、禁改。
    let dirPick = null;
    if (item.dirPicker) {
      const target = document.createElement('button');
      target.type = 'button';
      target.className = 'ws-pick-target';
      target.innerHTML = WS_FOLDER_SVG +
        '<span class="ws-pick-texts"><span class="ws-pick-title">' + escapeHtml(t('workspacePicker.pickTitle')) + '</span>' +
        '<span class="ws-pick-hint">' + escapeHtml(t('workspacePicker.pickHint')) + '</span></span>';
      const echo = document.createElement('div');
      echo.className = 'ws-pick-echo';
      echo.style.display = 'none';
      wrapper.appendChild(target);
      wrapper.appendChild(echo);
      dirPick = { busy: false };
      const entry = {
        sessionId: askSessionId,
        requestId,
        complete(path) {
          dirPick.busy = false;
          answers[qi] = path;
          echo.style.display = '';
          echo.textContent = '-> ' + t('workspacePicker.pickedEcho', { path });
          target.classList.remove('picking');
          target.querySelector('.ws-pick-title').textContent = t('workspacePicker.pickTitle');
          dirPickCards.delete(requestId);
          checkAllAnswered();
          if (!confirmBtn.disabled) confirmBtn.click(); // 自动作答 askUser（继续创建流程）
        },
        setIdle() {
          dirPick.busy = false;
          target.classList.remove('picking');
          target.querySelector('.ws-pick-title').textContent = t('workspacePicker.pickTitle');
        },
      };
      dirPickCards.set(requestId, entry); // 注册卡片 api（complete/setIdle），confirm/cancel 时清理
      const startPick = () => {
        if (box.classList.contains('resolved')) return; // 已决卡哨兵（共用层单一闸 lockOptionBox）
        if (dirPick.busy) return;
        if (!askSessionId || !requestId) return; // 无 requestId 的残卡不可发起（事件无主）
        dirPick.busy = true;
        target.classList.add('picking');
        target.querySelector('.ws-pick-title').textContent = t('workspacePicker.picking');
        // 2026-09-06 作者拍板：复用应用内目录浏览器（workspacePicker.js，含面包屑 /
        // 上级 / 新建文件夹 / 选中此目录），不走系统目录对话框。openPicker({sessionId,
        // onPick, onCancel})。
        import('./workspacePicker.js').then(({ openPicker, closePicker }) => {
          openPicker({
            sessionId: askSessionId,
            onPick: (p) => entry.complete(p),
            onCancel: () => entry.setIdle(),
            // 目录列表超时/error（选择面不可用）⇒ 关掉浏览器 + 揭示降级输入面
            // （2026-09-17 裁定 ②-6：按需揭示自由输入面并聚焦，禁死路）
            onListUnavailable: () => { closePicker(); revealFallbackInput(); },
          });
        }).catch(() => { entry.setIdle(); revealFallbackInput(); }); // 模块加载失败 ⇒ 降级，不悬挂卡片
      };
      target.addEventListener('click', (e) => { e.stopPropagation(); startPick(); });
      // 卡片整体可点击（作者原话「卡片整体可点击、醒目大目标」）：除按钮/输入框外的
      // 空白点击都触发选择。
      wrapper.addEventListener('click', (e) => {
        if (box.classList.contains('resolved')) return; // 已决卡：卡片空白区入口一并失效
        if ((/** @type {Element} */ (e.target)).closest('button, textarea, input')) return;
        startPick();
      });
    }

    const optsDiv = document.createElement('div');
    optsDiv.className = 'option-opts';
    const hasOptions = item.options && item.options.length > 0;
    // Multi-select question: checkbox group, confirm when done
    const isMulti = hasOptions && item.multiple === true;
    if (isMulti) optsDiv.classList.add('multi');

    if (hasOptions) {
      item.options.forEach((opt, oi) => {
        const btn = document.createElement('button');
        btn.className = 'option-btn';
        const isStr = typeof opt === 'string';
        const label = isStr ? opt : opt.label;
        const desc = isStr ? '' : (opt.desc || opt.description || '');
        // Optional extra class (e.g. permission escalation options).
        if (!isStr && typeof opt.cls === 'string' && opt.cls) btn.classList.add(opt.cls);
        if (isMulti) {
          btn.dataset.label = label;
          const preview = typeof opt === 'object' && opt !== null ? opt.preview : null;
          if (preview) btn.classList.add('has-preview');
          btn.innerHTML = '<span class="option-check"></span>' + (preview ? buildOptionPreview(preview) : '') + '<span class="option-text">' +
            escapeHtml(label) + (desc ? '<div class="option-desc">' + escapeHtml(desc) + '</div>' : '') + '</span>';
          btn.onclick = () => {
            btn.classList.toggle('picked');
            syncMulti(qi);
            updateVisibility();
            checkAllAnswered();
          };
        } else {
          btn.dataset.label = label;
          const preview = typeof opt === 'object' && opt !== null ? opt.preview : null;
          if (preview) btn.classList.add('has-preview');
          if (preview) {
            btn.innerHTML = buildOptionPreview(preview) + '<span class="option-text">' + escapeHtml(label) + (desc ? '<div class="option-desc">' + escapeHtml(desc) + '</div>' : '') + '</span>';
          } else {
            btn.innerHTML = escapeHtml(label) + (desc ? '<div class="option-desc">' + escapeHtml(desc) + '</div>' : '');
          }
          btn.onclick = () => {
            answers[qi] = label;
            optsDiv.querySelectorAll('.option-btn').forEach((el, i) => {
              el.classList.toggle('picked', i === oi);
            });
            if (customInput) {
              customInput.style.display = 'none';
              customInput.value = '';
            }
            if (askSessionId) saveAskDraft(askSessionId, qi, '');
            updateVisibility();
            checkAllAnswered();
          };
        }
        optsDiv.appendChild(btn);
      });
    }

    const allowOther = item.allowOther !== false;
    // 2026-09-17 作者裁定（S3 ②-7 协议收敛）：freeInput === false ⇒ 本卡不提供自由
    // 输入面（不渲染 textarea、跳过草稿恢复）。字段缺失 / true（旧载荷）= 逐字节现状
    // ——空 options 时 textarea 直接可见（2026-09-09 裁定，已由 09-17 裁定取代，
    // 原句见上方工作区选择卡注释块）。
    const freeInput = item.freeInput !== false;
    const customInput = document.createElement('textarea');
    customInput.className = 'option-custom-input';
    customInput.placeholder = t('chat.typeAnswer');
    customInput.rows = 2;

    // 降级兜底（2026-09-17 裁定 ②-6）：选择面不可用（动态 import 拒绝 / 目录列表
    // 超时或 error）⇒ 按需把自由输入面挂进卡片并聚焦，答案仍能成功上送
    // askUserAnswer——🔴 禁死路。常态卡（freeInput 缺省 / true）本就常驻 textarea，
    // 此函数即时返回；revealFallbackInput 只在 dirPicker 块的失败支路被调用。
    function revealFallbackInput() {
      if (freeInput) return; // 常态：textarea 已在 DOM（2026-09-09 现状）
      if (!customInput.isConnected) optsDiv.appendChild(customInput);
      customInput.style.display = '';
      customInput.focus();
    }

    // freeInput === false ⇒ 显式跳过 localStorage 草稿恢复：否则看不见的陈旧草稿会
    // 直接写进 answers[qi]，配 1912 的点选即自动提交 = 假作答（新判红点）。
    const savedVal = freeInput ? saved[qi] : undefined;
    if (savedVal && !isMulti) {
      customInput.value = savedVal;
      answers[qi] = savedVal;
    }

    if (!hasOptions) customInput.style.display = freeInput ? '' : 'none';

    let otherBtn = null;
    if (hasOptions && allowOther) {
      otherBtn = document.createElement('button');
      // opt-other = askuser-hybrid 重皮的纯样式钩子（虚线自由入口形态；单选腿
      // dashed / multi 腿保持勾选表格行，见 chat.css askuser-hybrid 段）。
      otherBtn.className = 'option-btn opt-other';
      if (isMulti) {
        otherBtn.dataset.other = '1';
        otherBtn.innerHTML = '<span class="option-check"></span><span class="option-text">' + escapeHtml(t('chat.other')) + '</span>';
        if (savedVal) {
          otherBtn.classList.add('picked');
          customInput.style.display = '';
          customInput.value = savedVal;
        } else {
          customInput.style.display = 'none';
        }
        otherBtn.onclick = () => {
          otherBtn.classList.toggle('picked');
          if (otherBtn.classList.contains('picked')) {
            customInput.style.display = '';
            customInput.focus();
          } else {
            customInput.style.display = 'none';
            customInput.value = '';
            if (askSessionId) saveAskDraft(askSessionId, qi, '');
          }
          syncMulti(qi);
          updateVisibility();
          checkAllAnswered();
        };
      } else {
        otherBtn.textContent = t('chat.other');
        if (savedVal && !item.options.some(o => (typeof o === 'string' ? o : o.label) === savedVal)) {
          otherBtn.classList.add('picked');
          customInput.style.display = '';
        } else {
          customInput.style.display = 'none';
        }
        otherBtn.onclick = () => {
          optsDiv.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
          otherBtn.classList.add('picked');
          customInput.style.display = '';
          customInput.focus();
          if (customInput.value.trim()) {
            answers[qi] = customInput.value.trim();
          }
          updateVisibility();
          checkAllAnswered();
        };
      }
      optsDiv.appendChild(otherBtn);
    } else if (hasOptions) {
      customInput.style.display = 'none';
    } else {
      answers[qi] = null;
    }

    customInput.oninput = () => {
      const val = customInput.value.trim();
      if (isMulti) {
        syncMulti(qi);
      } else if (val) {
        answers[qi] = val;
        if (hasOptions) {
          optsDiv.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
          otherBtn && otherBtn.classList.add('picked');
        }
      } else if (hasOptions && !otherBtn?.classList.contains('picked')) {
        // Don't clear answer if a preset option is selected
      } else {
        answers[qi] = null;
      }
      if (askSessionId) saveAskDraft(askSessionId, qi, val);
      updateVisibility();
      checkAllAnswered();
    };
    // freeInput === false ⇒ 不渲染 textarea：正常路径 DOM 无 .option-custom-input
    // （2026-09-17 裁定 ②-7 的目标读数，改造前为 1）。选择面不可用时由
    // revealFallbackInput() 按需挂载（降级兜底，②-6）。
    if (freeInput) optsDiv.appendChild(customInput);
    wrapper.appendChild(optsDiv);
    box.appendChild(wrapper);
    if (isMulti) syncMulti(qi); // pick up restored Other text, if any (needs the DOM in place)
  });

  // Apply initial visibility after all questions are in DOM
  updateVisibility();

  const btnRow = document.createElement('div');
  btnRow.className = 'option-btn-row';

  const cancelBtn = document.createElement('button');
  cancelBtn.className = 'option-cancel';
  cancelBtn.textContent = t('chat.cancel');
  cancelBtn.onclick = () => {
    if (askSessionId) dropAskCard(askSessionId, requestId); // #250 ④: 精确摘这一张
    if (requestId) dirPickCards.delete(requestId); // 工作区选择卡事件一并失主
    // 2026-09-18 作者报单：已决 ⇒ 禁互动改走共用层单一闸（卡内**全部**可交互件 +
    // capture 点击哨兵），取代原先的三组选择器逐件 disabled —— 旧口径在此处还漏了
    // `.option-cancel`（cancel 自身只在下一行单独 disable）与 `.ws-pick-target`。
    lockOptionBox(box);
    if (askSessionId) clearAskDrafts(askSessionId);
    if (onCancel) onCancel();
  };

  const confirmBtn = document.createElement('button');
  confirmBtn.className = 'option-confirm';
  confirmBtn.innerHTML = '<i data-lucide="check"></i><span>' + escapeHtml(confirmLabel) + '</span>';
  confirmBtn.disabled = !questions.every((_, qi) => !shouldShow(qi) || isAnswered(qi));
  confirmBtn.onclick = () => {
    // Lock the card: drop it from the Canvas answer registry (E6: late
    // _nfAskAnswer postMessages find no entry and are silently discarded).
    // #250 ④: only THIS card's entry — sibling pending cards keep their channel.
    if (askSessionId) dropAskCard(askSessionId, requestId);
    if (requestId) dirPickCards.delete(requestId); // 工作区选择卡事件一并失主
    // 已决 ⇒ 共用层单一闸（工作区选择卡的 `.ws-pick-target` / 卡片空白区在此一并失效）
    lockOptionBox(box);
    confirmBtn.style.display = 'none';
    cancelBtn.style.display = 'none';

    // askuser-secret 批：终态行逐槽映射——secret 槽只显示「已安全存储」占位
    // （值从不进 DOM 文本/草稿/任何持久面）。先取 secretSlots 快照，随后立即
    // 清空内存槽与 DOM 输入值（规格 §3「确认后立即清空 DOM 值」）。
    const secretSlots = questions
      .map((qz, qi) => (qz && qz.secret === true) && secretValues[qi] ? { idx: qi, value: secretValues[qi] } : null)
      .filter(Boolean);
    const displayParts = questions
      .map((qz, qi) => {
        if (qz && qz.secret === true) return secretValues[qi] ? t('askUser.secretStoredLine') : '';
        const a = answers[qi];
        if (!a) return '';
        return Array.isArray(a) ? '[' + a.join(', ') + ']' : a;
      })
      .filter(Boolean);
    const ansDiv = document.createElement('div');
    ansDiv.className = 'option-answer';
    ansDiv.textContent = '-> ' + displayParts.join(', ');
    box.appendChild(ansDiv);
    questions.forEach((qz, qi) => {
      if (qz && qz.secret === true) {
        secretValues[qi] = null; // 内存槽立即清（快照已取）
        const pw = questionWrappers[qi] && questionWrappers[qi].querySelector('.option-secret-input');
        if (pw) pw.value = ''; // 规格 §3「确认后立即清空 DOM 值」
      }
    });

    if (askSessionId) clearAskDrafts(askSessionId);
    // Multi-select answers serialize as JSON array strings; the wire stays one string slot per question.
    // askuser-secret 批：secret 槽的 answers 位恒空串（hub 以 stored 回执占位化），
    // 值走 secretSlots 专用键（网关检出后直写 secrets——值不进普通 answers 通道）。
    const finalAnswers = answers.map(a => Array.isArray(a) ? JSON.stringify(a) : (a !== null ? a : ''));
    if (onConfirm) onConfirm(finalAnswers, secretSlots);
  };
  btnRow.appendChild(cancelBtn);
  btnRow.appendChild(confirmBtn);
  box.appendChild(btnRow);

  // Register as a remote-answerable AskUser card (Canvas channel, direction C
  // §3.2). Deleted on confirm/cancel — a missing entry is the
  // "locked/answered" signal (E6/E9). The Canvas button is a remote trigger:
  // it writes the same answers[] slot and runs the same confirm path.
  if (askSessionId) {
    askCardRegistry.set(askCardKey(askSessionId, requestId), {
      sessionId: askSessionId, questions, answers, shouldShow, requestId,
      selectOption(qi, label) {
        const wrapper = questionWrappers[qi];
        if (!wrapper) return;
        answers[qi] = label;
        wrapper.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
        wrapper.querySelectorAll('.option-btn').forEach(el => {
          if (el.dataset.label === label) el.classList.add('picked');
        });
        checkAllAnswered();
      },
      confirmIfReady() { if (!confirmBtn.disabled) confirmBtn.click(); },
    });
  }

  // A-1 (onboarding spec §7): whole card fades in. Class applied before the
  // box enters the DOM so the animation fires exactly once on insert.
  box.classList.add('ob-fade-in');
  container.appendChild(box);
  createIconsIn(box);
  smartScroll();

  function checkAllAnswered() {
    confirmBtn.disabled = !questions.every((_, qi) => !shouldShow(qi) || isAnswered(qi));
  }
}

// ---------- Workspace dir picker（Project「选择工作区」卡） ----------
// 2026-09-06 作者拍板：复用应用内目录浏览器（workspacePicker.js，含面包屑导航 /
// 上级 / 列表 / 新建文件夹 / 选中此目录），不再走系统目录对话框（macOS NSOpenPanel /
// Windows JFileChooser）。openPicker 的 onPick/onCancel 直接驱动卡片 api 的
// complete/setIdle；`dirPickCards` 登记卡片 api 供 confirm/cancel 时清理。
const WS_FOLDER_SVG = '<svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7a2 2 0 0 1 2-2h4l2.2 2.5H19a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/><path d="M3 11h18"/></svg>';
const dirPickCards = new Map(); // requestId -> 卡片 api（complete/setIdle）

// ---------- AskUser ----------
/** Open an AskUser comparison page in Canvas (direction C §2.1 canvas field).
 *  Read failure must not block the question card (E8) — errors are silent. */
function openAskCanvas(tabId, absPath) {
  import('./canvas.js').then(({ openWorkspaceItem }) => {
    openWorkspaceItem({ id: tabId, itemType: '', title: String(absPath).split('/').pop() || 'compare', content: '', absPath })
      .catch(() => {});
  }).catch(() => {});
}

/** Probe that a canvas comparison file is readable before we surface the
 *  "view comparison" button (E8). The backend pop.readFile responds with a
 *  `fileContent` frame — success carries content/size, failure carries error.
 *  Resolves true only on a positive read; a missing/broken file resolves
 *  false (the button is suppressed, question card still renders). */
function probeCanvasFile(path) {
  return new Promise(resolve => {
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) { resolve(false); return; }
    let settled = false;
    const finish = (ok) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (unsub) unsub();
      resolve(ok);
    };
    let unsub = null;
    unsub = onMessage('fileContent', (msg) => {
      if (msg.path !== path) return;
      // success = no error AND real content/binary presence; empty text files
      // legitimately arrive with content:"" so error is the reliable signal.
      finish(!msg.error && (typeof msg.content === 'string' || msg.size != null));
    });
    const timer = setTimeout(() => finish(false), 1500);
    sendWs({ type: 'pop.readFile', path, sessionId: undefined });
  });
}

// ---------- AskUser 附件行（顶层 `attachments` ⇒ 整次提问层，2026-10-02；askuser-attach-align 2026-10-07 重写判定面） ----------
/** 折叠阈值：行数 > 3 ⇒ 折叠为「N 个附件」+ 展开（设计卡 §三 3.2）。
 *  🔴 只是**展示**阈值，不是准入上限 —— 件数上限（9）由后端工具调用期拒绝，
 *  前端不复判（禁第二把尺）。 */
const ASK_ATTACHMENT_FOLD_AT = 3;

/** 展示名 = 绝对路径最后一段（设计卡 §三 3.2「basename 展示规则」）：
 *  🔴 严禁把整条绝对路径当可见标签（路径进 `title` 悬停提示即可）。 */
function askAttachmentBasename(p) {
  const parts = String(p).split('/');
  return parts[parts.length - 1] || String(p);
}

/** 附件点击的 Canvas 打开腿 —— **与 Pop 文件卡同一条通路**（askuser-attach-align
 *  2026-10-07 作者令「跟 Pop 保持一致」）：直接 `workspace-open-item` 交 Canvas
 *  既有 `openWorkspaceItem`（去重腿）⇒ `pop.readFile` ⇒ viewer 注册表渲染。
 *  🔴 刻意**不经** `previewLocalPath`：那道闸带着好友/设备面的
 *  `EXCLUDED_ITEM_TYPES = {html}` 排除（对端**不受信内容**的 2026-09-15 取证裁词）；
 *  AskUser 附件是**本机文件**、由用户本人点击，信任级与 Pop 文件卡同级 ⇒ 同通路
 *  直开（禁把不受信面的排除误伤到本地面）。开法仍只有这一条（禁第二套开法）。
 *  canvas 腿同件 `openAskCanvas` 的动态 import 形态（E8：失败不阻塞卡面）。 */
function openAttachmentInCanvas(path, title) {
  import('./canvas.js').then(({ openWorkspaceItem }) => {
    openWorkspaceItem({
      id: 'file:' + path,
      title: title || String(path).split('/').pop() || 'file',
      itemType: '',
      content: '',
      absPath: path,
      path,
      pinned: false,
    }).catch(() => {});
  }).catch(() => {});
}

/** 未注册类型的**下载兜底**（askuser-attach-align 2026-10-07 作者令）：与设备面
 *  下载键同一字节通路 —— `nfTicket.ticketUrl`（per-path 短票）⇒ `/api/nf-file`
 *  整件取回 ⇒ blob 交给浏览器下载管理器。本函数**不新开取数面**（同通路纪律）；
 *  返回 false（铸票/取回失败）时调用方就地降级为静态行（可见降级，禁静默）。 */
async function downloadAttachmentFile(path, base) {
  try {
    const { ticketUrl } = await import('./nfTicket.js');
    const resp = await fetch(await ticketUrl(path));
    if (!resp.ok) return false;
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = base || 'attachment';
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    return true;
  } catch { return false; }
}

/** 附件行的**单一渲染位**（整次提问层；`renderAskUser` 唯一调用方）。
 *
 *  §三 落点 = 卡内 `.option-q-wrapper` 之内、`.option-btn-row` 之前：本函数自建一个
 *  承载块并复用**既有类名** `.option-q-wrapper`（全仓无该类的 CSS 规则，纯 JS 钩子 ⇒
 *  零新增 CSS），插在 `box.children` 里的 `.option-btn-row` 之前。该块**不**登记进
 *  `createDepVisibility`（那是逐问题的 dependsOn 控件）⇒ 末条问题被条件隐藏时附件行
 *  不随之消失。挂载序号在**全部问题 wrapper 之后** ⇒ `markAnsweredPick` 的
 *  `wrappers[qi]`（按问题下标取）不受影响。
 *
 *  行形态（askuser-attach-align 2026-10-07 起；单一附件入口 = 本行的回形针）：
 *   · viewer 注册表认领的类型（md/pdf/html/office/图片/视频…，判据 =
 *     `itemTypeForFileName` 读注册表自身，**含 html** —— 本地面信任级 = Pop 同级，
 *     见 `openAttachmentInCanvas` 头注）⇒ 真按钮，点击走 Canvas 打开腿。
 *   · 未注册类型 ⇒ 真按钮，点击走**下载兜底**（设备面同通路；不再渲染死静态行
 *     ——「未注册类型走下载兜底＝与 Pop/Canvas 同判」，作者令原文）。
 *   · 探针判定缺失/不可读（`probeCanvasFile` 败 / 下载腿败）⇒ 该行**就地降级**为
 *     静态行 + `attachmentUnavailable` 标注（🔴 只降这一行：其余行与整卡渲染
 *     一点不改）。🔴 静态行零按钮语义（不出 `<button>` / `[role=button]`）。
 *  文案一律取自 `t()`（🔴 禁硬编码中文串入 JS）。 */
function renderAskAttachments(box, attachments) {
  const atts = Array.isArray(attachments)
    ? attachments.filter(p => typeof p === 'string' && p.length > 0)
    : [];
  if (!box || atts.length === 0) return;
  const btnRow = box.querySelector('.option-btn-row');
  if (!btnRow) return; // 形态兜底：无按钮行就无插位（改前形态逐字保留）

  const wrap = document.createElement('div');
  // 既有类名（零新增 CSS）+ 本批专属钩子类（纯 JS 选择器，无 CSS 规则 ⇒ 不触单源闸）
  wrap.className = 'option-q-wrapper ask-attachments-wrap';

  const head = document.createElement('div');
  head.className = 'ask-attachments-head';
  head.style.cssText = 'font: 600 11px -apple-system, sans-serif; color: var(--color-text-muted, #888); margin: 2px 0 4px;';
  // 标题词位 = 独立 span（折叠控件是同级 `<button>`）⇒ 文案读数不被控件文本污染。
  const label = document.createElement('span');
  label.className = 'ask-attachments-head-label';
  head.appendChild(label);

  const list = document.createElement('div');
  list.className = 'ask-attachments-list';
  list.style.cssText = 'display: flex; flex-direction: column; gap: 4px; align-items: flex-start;';

  /** 静态（不可点）行：探针失败 / 下载腿失败共用同一形态 —— 🔴 零按钮语义。 */
  function makeStaticRow(path, unavailable) {
    const base = askAttachmentBasename(path);
    const row = document.createElement('span');
    row.className = 'ask-attachment-static';
    row.title = path; // 悬停给全路径（可见标签只给 basename）
    row.style.cssText = 'font-size: 12px; color: var(--color-text-muted, #888); opacity: 0.75;';
    row.textContent = unavailable ? base + ' · ' + t('askUser.attachmentUnavailable') : base;
    return row;
  }

  atts.forEach(path => {
    const base = askAttachmentBasename(path);
    // 类型认领判据 = viewer 注册表自身（禁第二张扩展名表）。认领 ⇒ Canvas 渲染；
    // 未认领 ⇒ 下载兜底；两者都是**真按钮**（本地面无「必死的假按钮」——下载腿
    // 失败才有降级，且降级可见）。
    const claimed = !!itemTypeForFileName(path);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'glass-control ob-compare-btn';
    btn.title = path;
    btn.setAttribute('aria-label', claimed
      ? t('askUser.attachmentOpenCanvas', { name: base })
      : t('askUser.attachmentDownload', { name: base }));
    btn.innerHTML = '<i data-lucide="paperclip"></i><span>' + escapeHtml(base) + '</span>';
    btn.onclick = () => {
      if (claimed) openAttachmentInCanvas(path, base);
      else downloadAttachmentFile(path, base).then(ok => { if (!ok) degradeRow(btn, path); });
    };
    list.appendChild(btn);
    // 可达性探针（复用 canvas 腿同一件：`pop.readFile` + 1500ms 超时形态）：
    // 🔴 失败只降级**本行**，整卡渲染一点不改。
    probeCanvasFile(path).then(ok => { if (!ok) degradeRow(btn, path); });
  });

  /** 就地降级：可点行 → 不可点静态行（探针失败 / 下载腿失败共用）。
   *  卡已被替换或移除（`isConnected === false`）时静默跳过 —— 降级只服务在卡行。 */
  function degradeRow(btn, path) {
    if (!btn.isConnected) return;
    btn.replaceWith(makeStaticRow(path, true));
  }

  if (atts.length > ASK_ATTACHMENT_FOLD_AT) {
    // 折叠态：标题位显「N 个附件」；展开后显标题词。切换件是真控件（展开/收起确实可做）。
    let expanded = false;
    label.textContent = t('askUser.attachmentsCount', { n: String(atts.length) });
    list.style.display = 'none';
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'glass-control';
    toggle.style.cssText = 'font-size: 11px; padding: 1px 8px; margin-left: 6px;';
    toggle.setAttribute('aria-expanded', 'false');
    toggle.textContent = t('ref.expand');
    toggle.onclick = () => {
      expanded = !expanded;
      list.style.display = expanded ? '' : 'none';
      label.textContent = expanded
        ? t('askUser.attachmentsTitle')
        : t('askUser.attachmentsCount', { n: String(atts.length) });
      toggle.textContent = expanded ? t('ref.collapse') : t('ref.expand');
      toggle.setAttribute('aria-expanded', String(expanded));
    };
    head.appendChild(toggle);
  } else {
    label.textContent = t('askUser.attachmentsTitle');
  }

  wrap.appendChild(head);
  wrap.appendChild(list);
  btnRow.before(wrap); // 卡内、`.option-btn-row` 之前
  createIconsIn(wrap);
}

export function renderAskUser(items, askSessionId, agentName, requestId, source, attachments) {
  if (!Array.isArray(items) || items.length === 0) {
    renderError(t('chat.waitingQuestion'));
    return { type: 'askUser', items: [] };
  }
  const chat = activeView.dom.chat;
  const sid = askSessionId || activeView.sessionId;
  if (sid && state.sessionToolCards[sid]) { state.sessionToolCards[sid].remove(); delete state.sessionToolCards[sid]; }
  const row = document.createElement('div');
  row.className = 'row ai';
  // quote-message 一期（spec §2.1/§2.5）：AskUser 卡行挂 id + 右键「引用」
  // （摘要 = [提问] 首个 question 剥标签后首行 ≤60；question 富文本剥标签取纯
  // 文本先例 = persistence.js askUserQuestionText）。
  row.dataset.msgId = nextChatMsgId();
  attachQuoteMenu(row, {
    kind: 'ask', question: askUserCardQuestionText(items),
    senderRole: 'askUser', senderName: agentName || rootDisplayName(), ts: Date.now(),
  });
  // Ghost seat (askuser-hybrid 重皮 2026-10-06): this bubble's only payload is
  // the AskUser card (+ source badge / optional canvas button) — the bubble
  // de-faces so the card floats directly on the paper. Content-bearing bubbles
  // (finishAi re-attaches a preserved box under streamed markdown) keep their
  // face — the marker is deliberately an explicit class, never a blanket :has().
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai ob-ghost-seat';
  row.appendChild(bubble);
  // Source badge. D6 批 F1 (G9): project context (project node / dispatcher ask)
  // renders "project · nodeName" so concurrent asks from several nodes stay
  // attributable; no project field → bare agentName (root self-ask / REPL).
  const badgeLabel = askSourceLabel({ project: source && source.project, nodeName: source && source.nodeName, agentName });
  // N4 (unnamed-state batch 2026-10-06): the self-ask badge mounts only when the
  // root is NAMED — single-point `isRootNamed()` (rootName.js). The retired
  // `badgeLabel !== 'Nebula'` literal was a judgement-face use of the mechanism
  // name (the exact line rootName.js's header draws): it silently dual-tracked
  // the unnamed state AND mis-hid the badge when a user literally named the
  // agent "Nebula". Project asks keep their badge regardless of named state.
  if (badgeLabel && ((source && source.project) || isRootNamed())) {
    const badge = document.createElement('div');
    badge.className = 'ask-user-source';
    badge.textContent = badgeLabel;
    badge.style.cssText = 'font: 600 11px -apple-system, sans-serif; color: var(--color-text-muted, #888); margin-bottom: 8px; padding: 2px 8px; background: var(--color-surface, rgba(255,255,255,0.06)); border-radius: 6px; display: inline-block;';
    bubble.appendChild(badge);
  }
  appendChatRow(chat, row);
  // canvas field (direction C §2.1): probe readability, then offer a glass
  // "view comparison" button beside the question and auto-open it in Canvas.
  // E8: a missing/broken canvas file does not render the button and does not
  // block the question card — the probe decides, so we never show a dead path.
  const canvasPath = items.map(i => i && i.canvas).find(Boolean) || null;
  if (canvasPath) {
    const tabId = 'askcanvas:' + canvasPath;
    const viewBtn = document.createElement('button');
    viewBtn.className = 'glass-control ob-compare-btn';
    viewBtn.style.display = 'none'; // shown only after a successful probe (E8)
    viewBtn.textContent = t('askUser.viewCompare');
    viewBtn.onclick = () => openAskCanvas(tabId, canvasPath);
    bubble.appendChild(viewBtn);
    probeCanvasFile(canvasPath).then(ok => {
      if (ok) {
        viewBtn.style.display = '';
        openAskCanvas(tabId, canvasPath);
      } else {
        console.warn('[askUser] canvas file missing or unreadable:', canvasPath);
        viewBtn.remove();
      }
    });
  }
  // Use the sessionId from the askUser message, not the currently active session
  const targetSid = askSessionId || activeView.sessionId;
  try {
    showOptions(bubble, items, (answers, secretSlots) => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        // askuser-secret 批（规格 §2 值通道）：密码题的值走 `secretSlots` 专用键
        // （网关检出后直写 secrets——值不进普通 answers 通道、不进 .ui.json）。
        // 无秘密题 ⇒ 与改前逐字节同帧（secretSlots 键缺席）。
        state.ws.send(JSON.stringify({
          type: 'askUserAnswer',
          sessionId: targetSid,
          answers,
          ...(Array.isArray(secretSlots) && secretSlots.length > 0 ? { secretSlots } : {}),
          ...(requestId && { requestId })
        }));
      }
      // D6 批 F2: card-answer resolves the pending slot locally — the hub does
      // not echo a frame for card answers, so this callback is the only
      // resolution source on this path.
      removePendingAsk(requestId);
      broadcastAskState(targetSid, requestId);
      window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
    }, t('chat.confirm'), () => {
      // askucancel 批位2（2026-10-08）：卡上「取消」改走 hub 取消原语（askUserCancel
      // 帧 → CancelRequest：kind 闸 + 发起方终结通知 + 幂等裁决 + Cancelled 时
      // `__cancelled__` 落盘行），取代旧的 `__cancelled__` 哨兵答复（askUserAnswer ——
      // hub 记 answered，非取消语义）。「已取消」锁定文案由 askUserClosed
      // （reason=user-cancelled）广播 → closeAskUserCard 追加，本回调不自画终态行
      // （closeAskUserCard 对已带作答行的卡幂等跳过）；失败面 = askUserCancelResult
      // !ok → toast（main.js），卡保持既有 cancelBtn.onclick 锁定形态。
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        if (requestId) {
          state.ws.send(JSON.stringify({ type: 'askUserCancel', requestId, by: 'web' }));
        } else {
          // 理论不可达（live 卡恒带 requestId，hub 是唯一 id 源）；无 id 兜底保留
          // 改前哨兵行为，不静默无操作。
          state.ws.send(JSON.stringify({ type: 'askUserAnswer', sessionId: targetSid, answers: ['__cancelled__'] }));
        }
      }
      removePendingAsk(requestId);
      broadcastAskState(targetSid, requestId);
      window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
    }, targetSid, requestId);
    // 附件行（顶层 `attachments`，整次提问层）：插位 = 卡内 `.option-btn-row` 之前，
    // 仍在「整次提问」语义层。🔴 不新增前端模块 / 不新增 CSS；`.`option-q` 与其后
    // 的 options 区（`showOptions` 内层）原样不动 ⇒ dirPicker 路径
    // (ProjectTool.scala，原 ProjectCreateTool.scala:425) 不受影响、免回归。
    // showOptions 同步完成 `container.appendChild(box)` ⇒ 此处 box 已在 DOM 内。
    renderAskAttachments(bubble.querySelector('.option-box'), attachments);
  } catch (e) {
    console.error('[askUser] render failed:', e);
    bubble.textContent = t('chat.failedRender');
  }
  // Persisted entry carries the source-label fields (D6 批 F1) so the badge
  // survives localStorage restore / interactive re-render (main.js:1684), plus
  // the top-level attachment list (本批) so a replayed/re-rendered card keeps it.
  return {
    type: 'askUser', items, requestId,
    ...(Array.isArray(attachments) && attachments.length > 0 ? { attachments } : {}),
    ...(source && source.project ? { project: source.project, nodeName: source.nodeName } : {})
  };
}

// ---------- 一 id 一活卡：单一判据（双开缺陷批「案 A③」2026-09-21 chain-askuserdup） ----------
/** 容器内与 `requestId` **指同一张提问**的既有卡（`.option-box`）——本仓「一 id 一活卡」
 *  的**单一判据**，live / replay / history 三路共用（禁三处各自演化）。
 *
 *  ① **id 优先（无条件）**：`data-request-id === requestId` ⇒ 恒命中，不论该卡是否带
 *     `.option-answer` / `.resolved`。
 *     改前这里是纯 DOM 启发式（`!!box.querySelector('.option-answer')` = 「有作答行 ⇒
 *     判为已答 ⇒ 不删」），而历史恢复路径对**仍 pending**的卡也补了一行 `.option-answer`
 *     ⇒ 判据对这笔卡恒被击穿 ⇒ 重放腿再挂一张同 id 的活卡（首卡历史卡恒死）。
 *  ② **形态兜底**（`opts.legacyTwin`）：无 id 的**历史恢复**卡（`data-history-replay="1"`）
 *     且**确无作答记录**（无 `.option-answer`）。只为案 B 之前的旧 `.ui.json` 行保留
 *     （旧行不落 requestId ⇒ 没有 id 可匹，只能靠形态）。案 B 之后新行带 id ⇒ 走 ①。
 *
 *  🔴 方向不可反：**不许**再拿「有作答行」当「已答」的判据（那正是被击穿的启发式），
 *  只许拿「确实没有作答记录」当**可删/可替换**的判据。
 *  🔴 `legacyTwin` 只给**替换腿**（live / replay：新帧是这张卡的真身，旧孪生卡该让位）；
 *  历史腿问的是「同 id 的活卡是否已在 DOM」，那里**不能**开这个口子，否则任何一张遗留
 *  未作答卡都会让后画的历史卡被静默跳过。
 */
export function sameAskCards(chat, requestId, opts = {}) {
  const out = [];
  if (!chat || !chat.querySelectorAll) return out;
  chat.querySelectorAll('.option-box').forEach((box) => {
    const rid = box.dataset.requestId || '';
    if (requestId && rid === requestId) { out.push(box); return; } // ① id 优先，无条件
    if (opts.legacyTwin && !rid && box.dataset.historyReplay === '1'
        && !box.querySelector('.option-answer')) out.push(box); // ② 旧行形态兜底
  });
  return out;
}

/** 消费侧：移除 [[sameAskCards]] 命中的既有卡，返回移除张数。调用方在挂新卡**之前**
 *  调用（live 腿覆盖 `#433 F4` 同 id 多送 / replay 腿 = 本缺陷主修复面）。 */
export function reclaimAskUserCards(chat, requestId, opts = {}) {
  const cards = sameAskCards(chat, requestId, opts);
  cards.forEach((box) => {
    const row = box.closest('.row.ai');
    (row || box).remove();
  });
  return cards.length;
}

/** Lock an AskUser card whose owning ask was closed by the engine (the
 *  `askUserClosed` frame, main.js onMessage). Historically this function had a
 *  second caller: the chat-input passthrough receiver (`askUserAnswered`,
 *  author ruling 2026-08-29 23:50), retired 2026-09-14 (作者令 / 落地
 *  e59ed251d) — its frame has no producer left, so the only live entry is
 *  `askUserClosed`.
 *  Returns true when a matching unanswered card was found and locked.
 *  `note`: override the lock-line text — the askUserClosed path passes
 *  t('askUser.sourceClosed') (source node died) or t('askUser.turnInterrupted')
 *  (owning turn interrupted, #250 ②); the default is kept for any future
 *  caller that closes a card without a reason. */
export function closeAskUserCard(sessionId, requestId, note) {
  if (!sessionId || !requestId) return false;
  const view = findViewBySessionId(sessionId);
  const chat = view && view.dom && view.dom.chat;
  if (!chat) return false;
  const boxes = chat.querySelectorAll('.option-box[data-request-id="' + CSS.escape(requestId) + '"]');
  for (const box of boxes) {
    if (box.querySelector('.option-answer')) continue; // already answered/locked
    lockOptionBox(box); // 已决 ⇒ 共用层单一闸（含 dirPicker 卡的目标件/空白区）
    dirPickCards.delete(requestId); // 工作区选择卡：卡片关闭后事件一并失主
    const confirmBtn = box.querySelector('.option-confirm');
    const cancelBtn = box.querySelector('.option-cancel');
    if (confirmBtn) confirmBtn.style.display = 'none';
    if (cancelBtn) cancelBtn.style.display = 'none';
    const ansDiv = document.createElement('div');
    ansDiv.className = 'option-answer';
    ansDiv.textContent = '-> ' + (note || t('chat.answeredViaChatInput'));
    box.appendChild(ansDiv);
    // #250 ④: registry is per-card now — drop exactly this card's entry and
    // broadcast its id. Drafts are still session-keyed (pre-existing shape), so
    // they are only cleared once the session has no live card left; clearing
    // them while a sibling card is still open would wipe that card's answers.
    if (dropAskCard(sessionId, requestId)) {
      if (askCardsOf(sessionId).length === 0) clearAskDrafts(sessionId);
      broadcastAskState(sessionId, requestId);
    }
    if (!chat.querySelector('.option-box:not(:has(.option-answer))')) {
      window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId, attention: false } }));
    }
    return true;
  }
  return false;
}

// ---------- AskUser history replay ----------
/** History-replay twin of renderAskUser: rebuilds the SAME option-box card
 *  through the SAME showOptions component (question wrappers, descriptions,
 *  previews, Other textarea, confirm/cancel row), then applies the terminal
 *  lock state — the exact end-state of the live confirm path — so a restored
 *  answered card is structurally identical to the live one. persistence.js
 *  calls this from restoreFromBackendHistory / restoreFromStorage; unanswered
 *  trailing cards are re-rendered interactively by main.js (renderAskUser).
 *  answerText: the persisted user answer that followed the askUser entry
 *  (answer slots joined with '\n' by the gateway askUserAnswer recording),
 *  or null when no answer was recorded. The '__cancelled__' sentinel
 *  reproduces the live cancel end-state: everything disabled, buttons
 *  visible, no answer line. `null` (案 A①) = 历史无作答记录 ⇒ 不补答案行，改挂
 *  `.option-answer-pending` 显式待定标注（禁把「无记录」画成「已作答」）。
 *  requestId: 案 B 起随 askUser 行落盘的提问 id（旧行缺席 ⇒ undefined）。
 *  attachments: askUser 历史恢复批（2026-10-02，chain-askuser-histattach）——提问
 *  行随行落盘的附件引用列表（与 live 腿 `renderAskUser` 同构的顶层 `attachments`）。
 *  缺席 / 空列表 ⇒ 本函数与改前**逐字节同形**（`renderAskAttachments` 自行早退）。
 *  🔴 落「无作答记录」的方向 = 「未作答」而非「已作答」：宁可让一张其实已答过的卡
 *  显示待定（重放腿/作答链会纠正），也不许让一张真 pending 卡被画成已答（那正是
 *  重放腿去重判据被击穿、双开首卡恒死的成因）。 */
export function renderAskUserHistory(bubble, items, answerText, requestId, attachments) {
  if (!Array.isArray(items) || items.length === 0) return;
  try {
    // Ghost seat (askuser-hybrid 重皮): history-replay twin of renderAskUser —
    // the bubble is this card's carrier, de-face it the same way.
    bubble.classList.add('ob-ghost-seat');
    // 案 B：持久化的 requestId 透传进 showOptions ⇒ 历史恢复的卡也带
    // `data-request-id`（可 id 寻址：重放腿按 id 替换、askUserClosed 关卡可达）。
    // 旧 .ui.json 行无该键 ⇒ undefined ⇒ 卡不带属性（改前形态，逐字不变）。
    showOptions(bubble, items, null, null, null, undefined, requestId);
  } catch (e) {
    console.error('[askUser] history render failed:', e);
    bubble.textContent = t('chat.failedRender');
    return;
  }
  const box = bubble.querySelector('.option-box');
  if (!box) return;
  // 附件行（askUser 历史恢复批）：与 live 腿（`renderAskUser` 内同一句，单点）同款
  // 调用 —— 🔴 经 `.option-box` 定位、插位由 `renderAskAttachments` 自身决定
  // （卡内 `.option-btn-row` 之前），本函数不新开第二套开法 / 不新增模块 / 不新增 CSS。
  // 缺席或空列表 ⇒ 函数内早退 ⇒ 历史卡形态与改前逐字节同。
  renderAskAttachments(box, attachments);
  // 单一判据的形态锚（`sameAskCards` ②）：这张卡来自历史恢复，不是 live 卡。
  // 属性不进 innerHTML ⇒ 与 live 卡的逐字节 parity 断言（history-replay-cards.spec）
  // 不受影响。
  box.dataset.historyReplay = '1';
  box.classList.remove('ob-fade-in'); // settled card — no creation animation on replay
  const cancelled = answerText === '__cancelled__';
  if (answerText && !cancelled) markAnsweredPick(box, items, answerText);
  // Lock — same end-state as the live confirm path (confirmBtn.onclick) or
  // cancel path (cancelBtn.onclick) depending on the recorded answer. 2026-09-18
  // 作者报单：改走共用层单一闸（回放孪生卡的内嵌 dirPicker 目标件此前同病：不在
  // 三组选择器内 ⇒ 视觉仍可点）。
  lockOptionBox(box);
  if (!cancelled) {
    const confirmBtn = box.querySelector('.option-confirm');
    const cancelBtn = box.querySelector('.option-cancel');
    if (confirmBtn) confirmBtn.style.display = 'none';
    if (cancelBtn) cancelBtn.style.display = 'none';
    const ansDiv = document.createElement('div');
    // 案 A①（双开缺陷批 2026-09-21，chain-askuserdup）：**无作答记录**（`answerText
    // == null`：旧行无法取证、或非阻塞提问 pending 期间后继 ai/tool 行把 run 截断）
    // 不再伪装成「已作答」——改前无条件补 `.option-answer`，于是重放腿的去重判据
    // （看卡上有没有作答行）对这笔卡必然失效 ⇒ 同 id 双卡、首卡恒死。
    // 现改挂**显式待定标注**（死卡显式标注优于静默死亡）：卡片保持终态（不自作主张
    // 变回可点 —— 「无作答记录的已答过卡」与「真 pending 卡」在数据上不可分离），
    // 但把「历史无作答记录」明说出来，并让 `.option-answer` 的语义此后**严格等于
    // 「已作答」**（`closeAskUserCard` 复用该语义 ⇒ 历史卡可被引擎关闭）。
    if (answerText == null) {
      ansDiv.className = 'option-answer-pending';
      ansDiv.textContent = t('askUser.historyUnanswered');
    } else {
      ansDiv.className = 'option-answer';
      ansDiv.textContent = '-> ' + formatHistoryAnswer(answerText);
    }
    box.appendChild(ansDiv);
  } else {
    // askucancel 批位2（2026-10-08）：历史恢复的已取消卡补显式「已取消」标注——
    // 改前该分支只锁卡（灰化 + 按钮在场禁用）无任何文字，终态语义不可读。挂
    // `.option-answer`（案 A① 定死的语义 =「已作答」）是如实回放：`__cancelled__`
    // 本就是引擎取消裁决落盘的作答行；顺带让 legacyTwin ② 判据（「确无作答记录」
    // 才可被无 id 兜底替换）不再命中已终态的取消卡 —— 有作答记录 ⇒ 不可删，
    // 与 sameAskCards 的教义一致（不许拿「有作答行」当「已答」的反面用）。
    const cancelDiv = document.createElement('div');
    cancelDiv.className = 'option-answer';
    cancelDiv.textContent = '-> ' + t('askUser.cancelled');
    box.appendChild(cancelDiv);
  }
}

/** Mark the picked option buttons from a persisted answer string so the
 *  restored card shows the same picked state the user left it in. Answer
 *  slots are joined with '\n' (gateway askUserAnswer recording); a slot is a
 *  JSON array string for multi-select questions. A slot matching no preset
 *  label is the Other free text — restored into the textarea with the Other
 *  button picked, exactly as the live card looked at confirm time. */
function markAnsweredPick(box, items, answerText) {
  const slots = answerText.split('\n');
  const wrappers = box.querySelectorAll('.option-q-wrapper');
  items.forEach((item, qi) => {
    const slot = slots[qi];
    if (slot == null || slot === '') return;
    const wrapper = wrappers[qi];
    if (!wrapper) return;
    let labels = null;
    if (slot.startsWith('[')) {
      try { const parsed = JSON.parse(slot); if (Array.isArray(parsed)) labels = parsed; } catch { /* plain text */ }
    }
    const texts = labels || [slot];
    const btns = Array.from(wrapper.querySelectorAll('.option-btn'));
    // Preset options carry data-label (both single and multi branches); the
    // Other button never does (multi marks it data-other, single marks nothing).
    let matched = false;
    // Declared type needed: the closure assigns otherBtn, but CFA only sees
    // the `= null` initializer and narrows later reads to never.
    let /** @type {Element|null} */ otherBtn = null;
    btns.forEach(btn => {
      if (btn.dataset.label === undefined) { otherBtn = btn; return; }
      if (texts.includes(btn.dataset.label)) { btn.classList.add('picked'); matched = true; }
    });
    if (!matched && otherBtn) {
      otherBtn.classList.add('picked');
      const input = wrapper.querySelector('.option-custom-input');
      if (input) { input.value = slot; input.style.display = ''; }
    }
  });
}

/** Format a persisted answer string the way the live confirm path renders
 *  its option-answer line: multi-select slots as '[a, b]', slots joined
 *  with ', '. */
function formatHistoryAnswer(answerText) {
  return (answerText || '').split('\n').filter(Boolean).map(slot => {
    if (slot.startsWith('[')) {
      try { const arr = JSON.parse(slot); if (Array.isArray(arr)) return '[' + arr.join(', ') + ']'; } catch { /* plain text */ }
    }
    return slot;
  }).join(', ');
}

// ---------- Permission prompt ----------
export function renderPermissionPrompt(toolName, summary, inputJson, permSessionId, dangerLevel, sourceAgent, sourceSession, sourceTeam, requestId, safetyMode) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row ai';
  // Ghost seat (askuser-hybrid 重皮): the permission card is an option-box
  // carrier (badge/banner + box only) — the bubble de-faces, same as renderAskUser.
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai ob-ghost-seat';

  // Danger level decorations
  const level = dangerLevel || 0;

  // Parse input to extract detail and check danger
  let detail = '';
  let isDangerous = level >= 2;
  try {
    const input = JSON.parse(inputJson || '{}');
    if (input.command) {
      detail = input.command;
    } else if (input.file_path) detail = input.file_path;
    else if (input.url) detail = input.url;
  } catch (e) {}

  // Danger icons (shared SVG shapes, no text — text comes from i18n).
  // ui-status-redesign（S14 / 盘点 #51）：图标 = 语义档唯一载体（currentColor，
  // 色由 .st-warn/.st-err token 档给出——原 stroke="var(--…)" 写法在 presentation
  // attribute 里不解析，等于从未生效，本批一并修正）；横幅正文转中性字。
  const warningIcon = '<svg class="st-ic st-warn" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>';
  const dangerIcon = '<svg class="st-ic st-err" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>';
  const criticalIcon = '<svg class="st-ic st-err" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 16h.01"/><path d="M12 8v4"/><path d="M15.312 2a2 2 0 0 1 1.414.586l4.688 4.688A2 2 0 0 1 22 8.688v6.624a2 2 0 0 1-.586 1.414l-4.688 4.688a2 2 0 0 1-1.414.586H8.688a2 2 0 0 1-1.414-.586l-4.688-4.688A2 2 0 0 1 2 15.312V8.688a2 2 0 0 1 .586-1.414l4.688-4.688A2 2 0 0 1 8.688 2z"/></svg>';

  const dangerConfigs = {
    1: { cls: 'perm-warning', icon: warningIcon, i18nKey: 'chat.permLevel.warning' },
    2: { cls: 'perm-dangerous', icon: dangerIcon, i18nKey: 'chat.permLevel.dangerous' },
    3: { cls: 'perm-critical', icon: criticalIcon, i18nKey: 'chat.permLevel.critical' }
  };
  const dangerConf = dangerConfigs[level];
  if (dangerConf) {
    bubble.classList.add(dangerConf.cls);
    const banner = document.createElement('div');
    banner.className = 'perm-danger-banner';
    // i18n label with {detail} placeholder for dangerous/critical levels
    const bannerText = t(dangerConf.i18nKey, { detail: detail || '' });
    banner.innerHTML = dangerConf.icon + '<span>' + escapeHtml(bannerText) + '</span>';
    bubble.appendChild(banner);
  }

  // Source badge: show which sub-agent this permission request came from
  // (with team attribution when the requester is a team agent — #12)
  if (sourceAgent) {
    const sourceBadge = document.createElement('div');
    sourceBadge.className = 'perm-source-badge';
    const shortSession = sourceSession ? sourceSession.substring(0, 12) : '';
    const agentLabel = sourceTeam ? `${sourceTeam}/${sourceAgent}` : sourceAgent;
    sourceBadge.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 17l9.2-9.2M17 17V7H7"/></svg>' +
      '<span>' + escapeHtml(t('chat.permSource', { agent: agentLabel, session: shortSession })) + '</span>';
    bubble.appendChild(sourceBadge);
  }

  row.appendChild(bubble);
  appendChatRow(chat, row);

  const targetSid = permSessionId || activeView.sessionId;

  // 🔴 Silent auto-approve REMOVED (permshield F1, 2026-09-13): this is where
  // `state.bypassSessions` used to answer `permissionAnswer{approved:true}` for
  // the user with no interaction and no visible card. Per S1's backend
  // disposition the set is structurally unreachable anyway (global `auto-all`
  // ⇒ `ToolReversibility.isReversible` is true for every tool ⇒ AgentCore
  // never emits `askPermission`), so the only thing the branch could still do
  // was approve a card silently. Every permission answer on the frontend now
  // originates from a user click on a rendered option.

  // Build the question text
  let questionText = t('chat.allowTool', { tool: toolName });
  if (detail) {
    questionText = t('chat.allowTool', { tool: toolName }) + '  <code class="perm-detail-code">' + escapeHtml(detail) + '</code>';
  }

  const allowLabel = t('chat.allow');
  const denyLabel = t('chat.deny');
  const allowDesc = isDangerous ? t('chat.permExecCmd') : (summary || '');
  const permOptions = [
    { label: allowLabel, desc: allowDesc },
    { label: denyLabel, desc: t('chat.skipTool') }
  ];
  /* Permission escalation chain (Backend contract a56f7437; target changed by
     permshield S1, 2026-09-13): when the card's mode is one step below the
     tool's class, offer an upgrade option that approves this call AND changes
     the mode — now the **application-level persisted** mode (global), not a
     session override, so it applies to every session and survives a restart
     (`WebSocketRoutes.applyPermissionUpgrade` → `setSafetyDefaultMode`):
       confirm-edits + Write/Edit -> auto-edits; auto-edits + Bash/Curl -> auto-all.
     Missing/unknown safetyMode (older backend) -> no upgrade option.
     The card frame's mode is the same global value the shield renders
     (`AgentCore.askUserPermission` → `SharedResources.effectiveSafetyMode`). */
  let upgradeLabel = null;
  let upgradeMode = null;
  if (safetyMode === 'confirm-edits' && (toolName === 'Write' || toolName === 'Edit')) {
    upgradeMode = 'auto-edits';
    upgradeLabel = t('chat.permUpgradeAutoEdits');
    permOptions.splice(1, 0, { label: upgradeLabel, desc: t('chat.permUpgradeAutoEditsDesc'), cls: 'perm-upgrade-option' });
  } else if (safetyMode === 'auto-edits' && (toolName === 'Bash' || toolName === 'Curl')) {
    upgradeMode = 'auto-all';
    upgradeLabel = t('chat.permUpgradeAutoAll');
    permOptions.splice(1, 0, { label: upgradeLabel, desc: t('chat.permUpgradeAutoAllDesc'), cls: 'perm-upgrade-option' });
  }
  const items = [{
    question: questionText,
    options: permOptions
  }];
  showOptions(bubble, items, (answers) => {
    // approved only on Allow or the upgrade option (Other/custom text stays
    // non-approving, same as the pre-escalation semantics).
    const approved = answers[0] === allowLabel || (upgradeLabel !== null && answers[0] === upgradeLabel);
    const doUpgrade = approved && upgradeMode && answers[0] === upgradeLabel ? upgradeMode : null;
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: targetSid, approved, ...(requestId && { requestId }), ...(doUpgrade && { upgradeMode: doUpgrade }) }));
    }
    if (doUpgrade) {
      // The escalation lands on the SAME application-level persisted mode as the
      // shield (permshield S1: `applyPermissionUpgrade` → ConfigService
      // `.setSafetyDefaultMode` + `configUpdated` broadcast) — it is NOT a
      // session-scoped override and it survives a restart. Mirror it globally
      // (optimistic; the configData broadcast is authoritative) and refresh the
      // shield so the header agrees with the card the user just answered.
      if (state.applyGlobalSafetyMode) state.applyGlobalSafetyMode(doUpgrade);
      // 🔴 No silent persistence: the option label no longer promises "this time
      // only", so the click has to say what actually happened — the app-wide
      // mode changed and it stays changed after a restart.
      window.__showToast?.(t('chat.permUpgradeApplied', { mode: t(UPGRADE_MODE_LABEL[doUpgrade] || 'bypass.autoEdits') }), 'success');
    }
    // Track answered permission: prevents re-creating interactive prompt on
    // session switch-back while tool is still executing (askPermission is still
    // the last history entry until toolEnd is recorded).
    state.answeredPermissions.add(targetSid);
    // Remove the permission prompt row from DOM immediately after answering
    row.remove();
    window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
  }, t('chat.confirm'), () => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: targetSid, approved: false, ...(requestId && { requestId }) }));
    }
    // Track denied permission (same reason as above)
    state.answeredPermissions.add(targetSid);
    // Remove the permission prompt row from DOM immediately after denying
    row.remove();
    window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
  });
  smartScroll();
}

// ---------- Attachment preview ----------
export function renderAttachmentPreview(target) {
  // target: optional { attPreviewEl, attachments } for non-primary windows.
  // Defaults to the primary window's attPreview + pendingAttachments so existing
  // call sites are unaffected.
  const attPreview = (target && target.attPreviewEl) || activeView.dom.attPreview;
  const attachments = (target && target.attachments) || activeView.pendingAttachments;
  if (!attPreview) return;
  attPreview.innerHTML = '';
  attachments.forEach((att, idx) => {
    // ── 全局引用（#303 v1.1）：type='ref'（新统一模型）与旧 type='taskRef'
    // 都走 renderRefBlock —— 输入框引用块定高/截断/可展开（A1-A6）。旧 taskRef
    // 经 normalizeTaskRef 归一为 refRefType='task' 渲染（§2.4 渐进收敛）。──
    // quote-message 引用 chip（2026-10-09 用户反馈改版）：chat-message ref 与其它
    // 载荷**同条带**渲染在本附件条（#attachment-preview，输入框上方），形态 =
    // 胶囊 chip（尺寸档见 input.css 作用域规则；颜色/图标/✕ 圆盘沿用现状）。
    // 原整宽引用条 #msg-quote-strip 退役，引用态渲染自此与附件单点合一。
    if (att.type === 'ref' || att.type === 'taskRef') {
      const ref = att.type === 'ref' ? att : normalizeTaskRef(att);
      const remove = () => {
        attachments.splice(idx, 1);
        renderAttachmentPreview(target);
      };
      const card = renderRefBlock(ref,
        att.refType === 'chat-message' ? { mode: 'input', closeStyle: 'disc' } : { mode: 'input' },
        remove);
      attPreview.appendChild(card);
    } else if (att.type === 'image' && att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:')) {
      const wrap = document.createElement('div');
      wrap.style.position = 'relative';
      const img = document.createElement('img');
      img.src = att.preview;
      img.className = 'att-thumb';
      img.onerror = () => { img.style.display = 'none'; };
      wrap.appendChild(img);
      const rm = document.createElement('div');
      rm.className = 'att-remove';
      // 修正②（作者 2026-09-19 04:22）：图形由文本字符 'x' 改为**几何对称 SVG ✕**
      // ⇒ 形心 == 圆盘中心（旧形态偏心 1px/16px，见 input.css 的 `.att-remove` 段）。
      rm.innerHTML = CLOSE_X_SVG;
      rm.setAttribute('role', 'button');
      rm.setAttribute('aria-label', t('common.remove'));
      rm.title = t('common.remove');
      rm.onclick = () => {
        attachments.splice(idx, 1);
        renderAttachmentPreview(target);
      };
      wrap.appendChild(rm);
      attPreview.appendChild(wrap);
    } else {
      const wrap = document.createElement('div');
      wrap.className = 'att-file';
      wrap.style.position = 'relative';
      wrap.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:100px">' + escapeHtml(att.name) + '</span>';
      const rm = document.createElement('div');
      rm.className = 'att-remove';
      rm.innerHTML = CLOSE_X_SVG;   // 修正②：几何对称 ✕（同图片附件键）
      rm.setAttribute('role', 'button');
      rm.setAttribute('aria-label', t('common.remove'));
      rm.title = t('common.remove');
      rm.onclick = () => {
        attachments.splice(idx, 1);
        renderAttachmentPreview(target);
      };
      wrap.appendChild(rm);
      attPreview.appendChild(wrap);
    }
  });
}

// ---------- /ask bubble rendering ----------
export function renderAskBubble(question) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble user';
  const label = document.createElement('div');
  label.className = 'ask-label';
  label.textContent = t('chat.askLabel');
  const q = document.createElement('div');
  q.textContent = question;
  bubble.appendChild(label);
  bubble.appendChild(q);
  row.appendChild(bubble);
  // v1.2 unified footer (2026-09-06 补齐批): ask question bubbles too.
  row.appendChild(createMsgFooterBadge(Date.now(), question));
  appendChatRow(chat, row);
  smartScroll();
}

export function renderSkillBubble(skillName, text) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble user';
  const label = document.createElement('div');
  label.className = 'ask-label';
  label.textContent = t('chat.skillLabel', { skill: skillName });
  const content = document.createElement('div');
  content.textContent = text;
  bubble.appendChild(label);
  bubble.appendChild(content);
  row.appendChild(bubble);
  // v1.2 unified footer (2026-09-06 补齐批): skill bubbles too.
  row.appendChild(createMsgFooterBadge(Date.now(), text));
  appendChatRow(chat, row);
  smartScroll();
}

export function appendAskAnswer(delta) {
  const view = activeView;
  const chat = view.dom.chat;
  view.stream.askAnswerText += delta;
  if (!view.stream.currentAskBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    view.stream.currentAskBubble = document.createElement('div');
    view.stream.currentAskBubble.className = 'bubble ai';
    const label = document.createElement('div');
    label.className = 'ask-label';
    label.textContent = t('chat.askLabel');
    const content = document.createElement('div');
    view.stream.currentAskBubble.appendChild(label);
    view.stream.currentAskBubble.appendChild(content);
    row.appendChild(view.stream.currentAskBubble);
    appendChatRow(chat, row);
  }
  // rAF-throttled render (same scheduler as appendAiText)
  const bubble = view.stream.currentAskBubble;
  bubble._nfText = view.stream.askAnswerText || '';
  scheduleStreamRender(view, 'ask',
    { bubble, chat, snapped: view.stream.scrollSnapped },
    (target) => {
      if (!target.bubble.isConnected) return;
      const contentEl = target.bubble.querySelector('div:not(.ask-label)');
      if (contentEl) {
        contentEl.innerHTML = renderMarkdownWithMath(target.bubble._nfText || '', true, { cache: false }) + '<span class="cursor"></span>';
      }
      rafScrollChat(target);
    });
}

export function finishAskAnswer(durationMs, model) {
  cancelStreamRender(activeView, 'ask');
  if (activeView.stream.currentAskBubble) {
    const contentEl = activeView.stream.currentAskBubble.querySelector('div:not(.ask-label)');
    if (contentEl) {
      contentEl.innerHTML = renderMarkdownWithMath(activeView.stream.askAnswerText || '');
    }
    if (durationMs != null && durationMs > 0) {
      const seed = activeView.dom.chat.querySelectorAll('.duration-badge').length;
      // v1.2 footer ruling: time + copy only (metadata lives in the summary row)
      renderDurationBadge(activeView.stream.currentAskBubble, durationMs, model, seed, Date.now(), activeView.stream.askAnswerText);
    } else {
      // v1.2 unified footer (2026-09-06 补齐批): no-duration ask answers
      // still get time + copy (mirrors finishAi's no-duration fallback).
      const askRow = activeView.stream.currentAskBubble.closest('.row');
      if (askRow) askRow.appendChild(createMsgFooterBadge(Date.now(), activeView.stream.askAnswerText));
    }
    activeView.stream.currentAskBubble = null;
    activeView.stream.askAnswerText = '';
  }
}

export function renderAskError(msg) {
  cancelStreamRender(activeView, 'ask');
  // Clean up any in-progress ask bubble
  if (activeView.stream.currentAskBubble) {
    const row = activeView.stream.currentAskBubble.closest('.row');
    if (row) row.remove();
    activeView.stream.currentAskBubble = null;
    activeView.stream.askAnswerText = '';
  }
  renderError(msg || t('chat.askFailed'));
}

// ---------- Thinking bubble rendering ----------
// Throttle thinking rendering to ~60fps using rAF. Without this, fast thinking
// streams re-render the entire accumulated text on every delta, which gets
// O(n^2) slow as text grows and keeps the main thread busy — causing visible
// lag when user tries to interact (e.g. switching agents).
let _pendingThinkingRAF = null;
// Capture the bubble + chat at schedule time so the rAF renders into the correct
let _thinkingRafTarget = null;

// Thinking stream behavior (2026-09-07 09:24 author ruling): thinking streams
// EXPANDED while generating, then AUTO-COLLAPSES once the stream finishes —
// the long-lived pre-2026-08-20 behavior (old finishThinking set
// content.style.display='none' + label.collapsible). The 2026-09-05
// 思考直播回归 ruling keeps the live feed expanded mid-stream; this ruling
// restores only the terminal auto-collapse.

/** #345 duration label removed (2026-08-24 ruling): the done label reverts to
 *  the pre-#345 design — always「思考过程」(chat.thinkingLabel), no duration
 *  numbers, no chevron icon. */

/** Shared keyboard-activatable toggle for thinking labels and the #346 turn
 *  summary bar (spec §8: both implementations share one helper). */
export function bindCollapsibleToggle(el, getContent, onToggle) {
  el.setAttribute('role', 'button');
  el.setAttribute('tabindex', '0');
  el.setAttribute('aria-expanded', el.classList.contains('expanded') ? 'true' : 'false');
  const toggle = () => {
    const content = getContent();
    if (!content) return;
    const visible = content.style.display !== 'none';
    content.style.display = visible ? 'none' : '';
    el.classList.toggle('expanded', !visible);
    el.setAttribute('aria-expanded', String(!visible));
    onToggle?.(!visible);
  };
  el.onclick = toggle;
  el.onkeydown = (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
  };
}

/** 12px inline chevron (currentColor) used by the #346 turn summary bar —
 *  no icon library, muted color follows the text. (2026-08-24 ruling: thinking
 *  labels and injected-bubble headers no longer use it.) */
export function chevronSvg() {
  const span = document.createElement('span');
  span.className = 'nf-chevron';
  span.setAttribute('aria-hidden', 'true');
  span.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>';
  return span;
}
// ── 件① 图片节点跨帧保管表 ────────────────────────────────────────────
// key = 思考气泡元素，value = src → 已创建的 <img> 队列（WeakMap ⇒ 气泡随聊天区
// 释放时自动回收，无跨气泡 / 跨会话泄漏）。
const _thinkingImgKeep = new WeakMap();

/** @param {HTMLElement} bubble @returns {Map<string, HTMLImageElement[]>} */
function imgKeepFor(bubble) {
  let keep = _thinkingImgKeep.get(bubble);
  if (!keep) { keep = new Map(); _thinkingImgKeep.set(bubble, keep); }
  return keep;
}

/**
 * Stream-markdown render that CARRIES already-created <img> nodes across frames
 * (2026-09-15 作者现场报 · 件①：思考流带图 ⇒ 图渲染不出来 + 连续抽动).
 *
 * 流式面每帧都要重解析累积文本，因此 `contentEl.innerHTML` 必须整棵换掉——这对
 * 文本是对的，对 <img> 是致命的：逐帧造出新元素 ⇒ 该元素上的「图像加载状态」被逐帧
 * 重置。可加载图靠内存缓存掩盖，加载失败的图则每帧重发外呼、破图盒在「有/无图数据」
 * 之间每秒来回（帧率量级）= 作者报的连续抽动；图片也永远停不到已渲染态。
 *
 * 语义（图片 = 终态内容，不是流式内容）：图片 markdown 到达时整 token 到位，故其 DOM
 * 节点跨帧存活——同一元素、同一已解码位图、同一次外呼、尺寸不再跳。
 *  - 每帧先把上一帧活着的 <img> 按 src 收进 `keep`（每次内建每次清空 ⇒ 无跨气泡泄漏）；
 *  - 新渲染出的 <img> 先**不带 src**（src 落在 data-nf-keep-src），因此它不会自己发起
 *    一次多余的外呼；随后按 src 把上一帧的同一个元素放回去（`replaceWith`）；
 *  - 首次出现的图才真正拿到 src 去加载，失败时挂 `.nf-img-failed`（稳定失败占位，
 *    CSS 在 chat.css）——**不重试**（🔴 禁无限重试）。
 *
 * 修法 1（渲染路径优化，2026-09-30）：流式帧**不再**对整段累积文本做全量
 * `renderMarkdownWithMath` —— 那是 O(n²) 自旋（诊断 chain-fe-perf：rAF 回调内整段
 * 重解析，单帧成本随 streak 变长而超过帧预算 ⇒ 100% 单核）。改为**只重画变化尾段**：
 * 文本按「安全块边界」切成**已闭合块**与**未闭合尾块**，已闭合块的 HTML 逐块缓存、
 * 每帧复用，只有尾块重解析。安全边界判定见 streamSplitPoint（保守 —— 拿不准就不用，
 * 退回全量路径），因此结果与全量路径**逐字节等价**（判据 1/2 的 oracle）。
 *
 * @param {HTMLElement} contentEl 流式渲染目标（.thinking-content）
 * @param {string} text 累积文本
 * @param {boolean} parseVoice renderMarkdownWithMath 的 parseVoice
 * @param {Map<string, HTMLImageElement[]>} keep 本次气泡的图片节点保管表
 * @param {boolean} [useCache] 传给 renderMarkdownWithMath 的 cache（流式帧传 false —
 *   逐帧快照不进 LRU；收尾帧传 true — 稳定文本照旧入缓存，且**走全量路径**以
 *   保证终态 DOM 与全量重渲染逐字节相等）
 * @param {{round:number,block:number}|null} [mark] B2 段标识。同一 round 内 block
 *   严格递增 ⇒ 累积文本是同段续写（缓存有效）；round 变 / block 不增 ⇒ 段边界
 *   （丢弃块缓存走诚实的全量渲染）。缺省 null ⇒ 与 B1 逐字节同行为。
 */
function renderStreamMarkdown(contentEl, text, parseVoice, keep, useCache, mark) {
  keep.clear();
  contentEl.querySelectorAll('img').forEach((img) => {
    const key = img.getAttribute('src');
    if (!key) return;
    const queue = keep.get(key);
    if (queue) queue.push(img); else keep.set(key, [img]);
  });
  const plan = streamRenderPlan(contentEl, text, parseVoice, useCache === true, mark);
  if (plan.mode === 'full') {
    contentEl.innerHTML = plan.html.replace(/<img\b([^>]*?)\ssrc=/g, '<img$1 data-nf-keep-src=');
    adoptFreshImgs(contentEl, keep);
    return;
  }
  // Incremental write: the closed chunks are appended ONCE (their nodes are
  // never rewritten), and only the tail's nodes are swapped. Swapping at
  // `st.tailIndex` keeps the concatenated innerHTML identical to the previous
  // whole-subtree assignment, so the DOM stays byte-equal to the oracle.
  const st = plan.st;
  const old = Array.prototype.slice.call(contentEl.childNodes, st.tailIndex);
  if (plan.append.length) {
    const tpl = document.createElement('div');
    tpl.innerHTML = plan.append.join('').replace(/<img\b([^>]*?)\ssrc=/g, '<img$1 data-nf-keep-src=');
    const frag = document.createDocumentFragment();
    let added = 0;
    while (tpl.firstChild) { frag.appendChild(tpl.firstChild); added++; }
    contentEl.insertBefore(frag, old.length ? old[0] : null);
    // count the nodes BEFORE insertBefore: the insert MOVES them out of the
    // fragment, so frag.childNodes is empty afterwards (reading it there would
    // leave tailIndex at 0 and wipe the whole tree on the next frame).
    st.tailIndex += added;
  }
  for (let i = 0; i < old.length; i++) old[i].remove();
  // Build the new content off-DOM, then hand its nodes over in one insert: the
  // tail parse happens without touching the live tree, so the browser lays out
  // at most once for the tail — the same repair cost as the previous assignment,
  // minus the rebuild of every already-closed block above it.
  const holder = document.createElement('div');
  holder.innerHTML = plan.tail.replace(/<img\b([^>]*?)\ssrc=/g, '<img$1 data-nf-keep-src=');
  for (let n = holder.firstChild; n; n = holder.firstChild) contentEl.appendChild(n);
  adoptFreshImgs(contentEl, keep);
}

/**
 * Re-attach the previous frame's live <img> nodes to the freshly rendered ones
 * (2026-09-15 件①, unchanged semantics): a fresh <img> carries its src in
 * `data-nf-keep-src` so it does not fire a load of its own; the node kept from
 * the previous frame is put back in its place by src, so the decoded bitmap and
 * the load state survive across frames (no flicker, no re-fetch). Only a
 * genuinely new image gets its `src` and its one-shot error handler.
 * @param {HTMLElement} root
 * @param {Map<string, HTMLImageElement[]>} keep
 */
function adoptFreshImgs(root, keep) {
  root.querySelectorAll('img[data-nf-keep-src]').forEach((fresh) => {
    const key = fresh.getAttribute('data-nf-keep-src');
    fresh.removeAttribute('data-nf-keep-src');
    const queue = keep.get(key);
    const kept = queue && queue.shift();
    if (kept) {
      fresh.replaceWith(kept);
      return;
    }
    fresh.setAttribute('src', key);
    fresh.addEventListener('error', () => fresh.classList.add('nf-img-failed'), { once: true });
  });
}

// ── 修法 1：增量流式渲染状态 ──────────────────────────────────────────
// 每个流式容器一份状态：已闭合块（文本长度 + 其 HTML）与已渲染前缀长度。键为容器
// 元素（WeakMap ⇒ 随气泡回收，无跨气泡 / 跨会话泄漏）。
const _streamMdState = new WeakMap();

// 链接引用定义是**文档级**的：[a]: /x 出现在前面而 [y][a] 出现在后面时，前缀/尾块
// 分开解析会得到与整段解析不同的结果。检测到任一引用定义 ⇒ 本段文本退回全量路径。
const RE_REF_DEF = /^[ \t]{0,3}\[[^\]\n]+\]:[ \t]*\S/m;
// 行首「块级构造」判据：列表项 / 引用 / 围栏 / 表格行 / 缩进代码。安全边界两侧都不
// 得是这些（宽松列表、跨空行引用会在空行处继续，分开解析会改变结构）。
const RE_BLOCK_OPEN = /^[ \t]*(?:[-*+][ \t]|\d+[.)][ \t]|>|```|~~~|\||(?:[ \t]{4,}\S))/;

/** Last non-blank line of `s` (trimmed), or '' when there is none. */
function lastNonBlankLine(s) {
  const lines = s.split('\n');
  for (let i = lines.length - 1; i >= 0; i--) if (lines[i].trim() !== '') return lines[i];
  return '';
}

/** First non-blank line of `s` (trimmed), or '' when there is none. */
function firstNonBlankLine(s) {
  for (const line of s.split('\n')) if (line.trim() !== '') return line;
  return '';
}

/** True when `s`'s math delimiters are balanced (no dangle across a split). */
function mathBalanced(s) {
  const noCode = s.split(/(```[\s\S]*?(?:```|$)|~~~[\s\S]*?(?:~~~|$)|`+[^`\n]*?`+)/g)
    .map((seg, i) => (i % 2 === 1 ? '' : seg)).join('');
  if (noCode.includes('$$')) return false;
  if ((noCode.match(/\\\(/g) || []).length !== (noCode.match(/\\\)/g) || []).length) return false;
  if ((noCode.match(/\\\[/g) || []).length !== (noCode.match(/\\\]/g) || []).length) return false;
  if ((noCode.match(/(^|[^\\$])\$/g) || []).length % 2 !== 0) return false;
  return true;
}

/**
 * Offset just past the LAST blank-line boundary in `text` that is provably safe
 * to split at, or -1 when none qualifies. Conservative by design: any ambiguity
 * (list/quote/fence/table/code adjacency, unclosed math, reference definitions)
 * disqualifies the boundary, and a disqualified split means the caller falls
 * back to the full-render path — never a guess.
 */
function streamSplitPoint(text) {
  if (text.length < 2) return -1;
  if (RE_REF_DEF.test(text)) return -1;      // document-level refs ⇒ never split
  let best = -1;
  let idx = 0;
  while (true) {
    const b = text.indexOf('\n\n', idx);
    if (b < 0) break;
    const p = b + 2;
    const before = text.slice(0, p);
    const after = firstNonBlankLine(text.slice(p));
    const prev = lastNonBlankLine(text.slice(0, p));
    if (
      after !== '' &&
      !RE_BLOCK_OPEN.test(after) &&
      !RE_BLOCK_OPEN.test(prev) &&
      mathBalanced(before) &&
      !hasOpenFence(before)
    ) {
      best = p;
    }
    idx = p;
  }
  return best;
}

/** True when `s` contains an odd number of fence-opening lines (unclosed fence). */
function hasOpenFence(s) {
  let n = 0;
  for (const line of s.split('\n')) if (/^\s{0,3}(?:```|~~~)/.test(line)) n++;
  return n % 2 === 1;
}

/**
 * Plan for one stream frame. Same decision tree as before, but expressed as a
 * *write plan* so the caller can touch only the DOM that actually changed:
 *   { mode: 'full', html }                       — replace the whole subtree
 *   { mode: 'incr', append: [html…], tail, st }  — append new closed chunks,
 *                                                  re-render only the open tail
 *
 * Why the DOM has to be incremental too (2026-10-01, 判据 3): the old path
 * assigned `contentEl.innerHTML` every frame, which destroys and rebuilds the
 * whole subtree — Layout + RecalcStyle dominated the frame cost (measured
 * 21.4 s / 7.3 s of 57 s CPU over a 426-frame stream) and did NOT shrink when
 * only the parse was cached. Chunk HTML is therefore appended ONCE and never
 * rewritten; only the tail's nodes are swapped (they start at `st.tailIndex`).
 * The resulting innerHTML is byte-identical to the concatenation the previous
 * implementation produced, so 判据 1/2 equivalence is preserved.
 * @param {HTMLElement} contentEl
 * @param {string} text
 * @param {boolean} parseVoice
 * @param {boolean} final
 * @param {{round:number,block:number}|null} [mark] B2 frame identity (see
 *   renderStreamMarkdown). When it is present and does NOT continue the identity
 *   the cache was built under, the segment is provably new ⇒ the closed-chunk
 *   cache is dropped. That makes the reset DETERMINISTIC: B1 could only infer a
 *   new segment from `text` shrinking (`split < st.closedLen`), so a new segment
 *   whose text happens to be longer than the previous one's closed prefix kept a
 *   stale chunk cache. An absent/equal-or-advancing mark leaves B1's behavior
 *   byte-for-byte unchanged (the conservative fallback path is untouched).
 */
function streamRenderPlan(contentEl, text, parseVoice, final, mark) {
  if (final) {
    _streamMdState.delete(contentEl);
    return { mode: 'full', html: renderMarkdownWithMath(text, parseVoice, { cache: true }) };
  }
  let st = _streamMdState.get(contentEl);
  if (!st) { st = { chunks: [], closedLen: 0, rendered: 0, tailIndex: 0, seg: '' }; _streamMdState.set(contentEl, st); }
  const reset = () => { st.chunks.length = 0; st.closedLen = 0; st.rendered = 0; st.tailIndex = 0; };
  // B2: `sameSegment` says whether this frame continues the segment the cache was
  // built under — same round AND a strictly larger block. Anything else (round
  // moved on, block did not advance, or either side carries no identity) means
  // the cached closed chunks belong to a DIFFERENT segment and must be dropped.
  // B1 could only infer this from `text` shrinking, so a new segment whose text
  // is longer than the old closed prefix (e.g. a second thinking block reusing
  // the container) silently kept stale chunks; the mark closes that gap.
  const seg = segKey(mark);
  if (seg && st.seg && !sameSegment(st.seg, seg)) reset();
  const split = streamSplitPoint(text);
  if (split <= 0) {
    // Not safely splittable — drop the cache and do the honest full render.
    reset();
    st.seg = seg;
    return { mode: 'full', html: renderMarkdownWithMath(text, parseVoice, { cache: false }) };
  }
  if (split < st.closedLen) reset();  // text shrank ⇒ new message
  st.seg = seg;
  if (split > st.closedLen) {
    st.chunks.push({ len: split, html: renderMarkdownWithMath(text.slice(st.closedLen, split), parseVoice, { cache: false }) });
    st.closedLen = split;
  }
  const append = [];
  for (let i = st.rendered; i < st.chunks.length; i++) append.push(st.chunks[i].html);
  st.rendered = st.chunks.length;
  return { mode: 'incr', append, tail: renderMarkdownWithMath(text.slice(split), parseVoice, { cache: false }), st };
}

// ── §③ 思考窗硬上限（stream-919-unify 批 · 作者 2026-10-07 10:10 澄清令）──────
// 「思考过程要限制一个最大窗口进行流式，不要占满整个屏幕。比如最多 5 行。」
// 流式与展开两态同窗：超窗＝内部滚动（内容零丢失，持久化与展开面完整），
// 绝不向下游无限撑开布局。行数计数读数落 data-nf-think-lines（DOM 属性，
// 非可见串——§16 零文案合规；可见行数徽系候裁件，本批不落）。
// 单点真源＝本常量；chat.css `.thinking-content { max-height: 7.5em }` 是
// JS 缺席时的同值兜底（5 行 × 1.5 行高，两处同式同值，改常量须同步改 CSS）。
export const THINKING_WINDOW_MAX_LINES = 5;
const THINKING_WINDOW_EM = THINKING_WINDOW_MAX_LINES * 1.5; // 13px 档行高 1.5

/** 把思考内容窗钉在上限并写行数计数读数（源文本行数，非渲染行数）。
 *  写点＝四处：live rAF（appendThinkingDelta 流式帧）／finishThinking（终态
 *  帧）／persistence 两条历史重放腿——四点同一入口，读数与窗形不分家。 */
export function applyThinkingWindowCap(contentEl, text) {
  if (!contentEl) return;
  contentEl.style.maxHeight = THINKING_WINDOW_EM + 'em';
  const lines = String(text || '').split('\n').length;
  contentEl.dataset.nfThinkLines = String(lines);
}

export function appendThinkingDelta(delta, mark) {
  // NOTE: always accumulate thinking text for saveMsg even if we skip DOM creation
  activeView.stream.thinkingText += delta;
  // B2 consumption: the frame's (round, block) identity rides along with the
  // accumulated text so the render cache can tell "same segment continues" from
  // "a new thinking block began" deterministically (see sameSegment).
  const seg = segKey(mark);
  if (seg) {
    activeView.stream.thinkSeg = seg;
    activeView.stream.thinkRound = mark.round;
  }
  // If text bubble already exists (e.g. second+ thinking block after text has started),
  // do NOT create a new thinking bubble — it would appear after the text (misplaced).
  // The thinking content is still accumulated in activeView.stream.thinkingText + sessionThinkingBuffers
  // and will be captured correctly by finishThinking() + done handler's fallback.
  if (!activeView.stream.currentThinkingBubble) {
    if (activeView.stream.currentAiBubble) {
      // Text already showing — skip DOM bubble creation for this thinking block.
      // Content is in activeView.stream.thinkingText for persistence; no bubble needed.
      return;
    }
    const chat = activeView.dom.chat;
    const row = document.createElement('div');
    // stream-ux redesign (2026-10-03): the thinking feed is NEVER visible as a
    // bubble during the run — the turn line carries the single-line reveal.
    // The bubble is the EXPAND face, so it is born pre-tucked; content still
    // renders into it (rAF below) and turnGroup untucks it on demand.
    // （c）面：弹窗裸行（9.19 形态）—— 在飞 thinking 气泡可见裸流；§③ 5 行窗
    // 上限照钉（下方 rAF 的 applyThinkingWindowCap 与主窗同一入口，后令治前令）。
    row.className = bareProcessRows(activeView) ? 'row ai thinking-row' : 'row ai thinking-row nf-tucked';
    // UI-B: the process row carries the round it belongs to (same contract as
    // the tool rows), so a reader can verify the round split from the DOM.
    stampRound(activeView, row);
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai thinking-bubble';
    // #346 v2 stats: thinking duration for the turn header (turnGroup.js
    // computes 思考 <N>s from nfStart/nfEnd on the bubble).
    bubble.dataset.nfStart = String(Date.now());
    const label = document.createElement('div');
    label.className = 'thinking-label thinking-streaming collapsible expanded';
    const labelText = document.createElement('span');
    labelText.className = 'thinking-label-text';
    labelText.textContent = t('chat.thinkingInProgress');
    label.appendChild(labelText);
    // #345: three pulse dots after the label (OpenAI paradigm)
    for (let i = 0; i < 3; i++) {
      const dot = document.createElement('span');
      dot.className = 'thinking-dot';
      if (i === 1) dot.style.animationDelay = '0.15s';
      if (i === 2) dot.style.animationDelay = '0.3s';
      label.appendChild(dot);
    }
    const content = document.createElement('div');
    content.className = 'thinking-content';
    // 2026-09-05 ruling (思考直播回归): the thinking content streams EXPANDED
    // — the #345 streaming-collapse default stays reverted. The label stays
    // clickable (collapsible) so the user can fold the live feed away;
    // finishThinking auto-collapses at stream end (2026-09-07 09:24 ruling).
    content.style.display = '';
    bubble.appendChild(label);
    bubble.appendChild(content);
    row.appendChild(bubble);
    appendChatRow(chat, row);
    // Click/Enter folds or re-reveals the live content (rAF keeps rendering
    // into it); finishThinking auto-collapses regardless at stream end.
    bindCollapsibleToggle(label, () => content);
    activeView.stream.currentThinkingBubble = bubble;
  }
  // stream-ux §二.1.1: 思考 = 单行流式揭示 — the work-line reveals the same
  // accumulated thinking text on ONE line. Each delta only writes the target
  // width (a CSS step transition does the cadence) — the row is never rebuilt
  // per token (A2/A3/A7).
  // （c）面：弹窗裸行 —— 单行揭示属 turn-line 剧场，弹窗不落行；思考流本体
  // 走上方可见气泡（9.19 形态）。
  if (!bareProcessRows(activeView)) thinkingWorklineItem(activeView, activeView.stream.thinkingText);
  // Capture the render target synchronously (correct during ws.js push/pull window).
  // Store accumulated text on the bubble node so the rAF reads it regardless of
  // which view global state points to at fire time.
  _thinkingRafTarget = { bubble: activeView.stream.currentThinkingBubble, chat: activeView.dom.chat, snapped: activeView.stream.scrollSnapped, mark: null };
  _thinkingRafTarget.bubble._nfText = activeView.stream.thinkingText;
  // B2 consumption: the identity of the last frame that wrote this bubble. The
  // rAF renders the accumulated text under it, so the render cache resets the
  // moment the backend's monotone (round, block) pair stops advancing.
  if (seg) {
    _thinkingRafTarget.bubble._nfSeg = seg;
    _thinkingRafTarget.bubble._nfRound = mark.round;
    _thinkingRafTarget.mark = mark;
  }
  // Schedule a rAF render if one isn't already pending — caps re-render rate
  // and coalesces multiple deltas into a single DOM update.
  if (!_pendingThinkingRAF) {
    _pendingThinkingRAF = requestAnimationFrame(() => {
      _pendingThinkingRAF = null;
      const target = _thinkingRafTarget;
      _thinkingRafTarget = null;
      if (!target || !target.bubble) return;
      const contentEl = target.bubble.querySelector('.thinking-content');
      if (contentEl) {
        // 件①：同一渲染 + 图片节点跨帧存活（否则图片每帧重建 ⇒ 见 renderStreamMarkdown）
        renderStreamMarkdown(contentEl, target.bubble._nfText || '', true, imgKeepFor(target.bubble), false, target.mark || null);
        // §③ 思考窗：流式帧同窗钉限＋计数读数随渲染刷新（源文本行数）。
        applyThinkingWindowCap(contentEl, target.bubble._nfText || '');
        contentEl.insertAdjacentHTML('beforeend', '<span class="cursor"></span>');
      }
      // Scroll the correct chat element directly — smartScroll() reads state.dom
      // at rAF time which may be the wrong window. Capture snapped at schedule
      // time to match smartScroll()'s snapped || near-bottom logic.
      // The disposition is keep/clamp only — write `1e7` and let the browser
      // clamp to the content bottom in this same frame instead of reading
      // `scrollHeight` back. That read, sitting right after this frame's subtree
      // mutation, forced an extra synchronous layout of the freshly written tail
      // every frame (measured as the second layout cost of the thinking stream);
      // the landing position is identical because the clamp IS the bottom.
      // `snapped === true` short-circuits the ||, so the common follow path now
      // reads no geometry at all here.
      if (target.snapped === true || isNearBottom(target.chat)) {
        target.chat.scrollTop = 1e7;
      }
    });
  }
}

// Cancel pending rAF — called by persist on cleanup when no active session.
export function cancelThinkingRAF() {
  if (_pendingThinkingRAF) {
    cancelAnimationFrame(_pendingThinkingRAF);
    _pendingThinkingRAF = null;
  }
}

export function finishThinking() {
  cancelThinkingRAF();
  if (activeView.stream.currentThinkingBubble) {
    // stream-ux §二.1.1: thinking COMPLETED — the work-line swaps the width
    // reveal for the drawn check and keeps the text on the single line.
    // （c）面：弹窗裸行 —— thinking 完成面 = 气泡本体收拢（下方 2026-09-07
    // 09:24 自动收拢，9.19 同在），不落 turn-line 完成项。
    if (!bareProcessRows(activeView)) thinkingWorklineDone(activeView, activeView.stream.thinkingText);
    const contentEl = activeView.stream.currentThinkingBubble.querySelector('.thinking-content');
    if (contentEl) {
      // 件①：收尾同一口径渲染（图片节点照旧存活 ⇒ 终结帧不再重拉一次、不再跳一次）
      // B2: the final render passes the still-open segment's identity so the
      // cache reset rule is evaluated identically to the streaming frames —
      // `final` already forces the full path, so the mark only keeps the
      // accounting consistent (no DOM effect).
      renderStreamMarkdown(contentEl, activeView.stream.thinkingText || '', false,
        imgKeepFor(activeView.stream.currentThinkingBubble), true,
        activeView.stream.thinkSeg
          ? { round: activeView.stream.thinkRound, block: Number(activeView.stream.thinkSeg.split('.')[1]) } : null);
      // §③ 思考窗：终态帧同窗钉限＋计数读数定稿（收起/再展开两态同窗——
      // max-height 是元素常驻样式，与 display 收展正交）。
      applyThinkingWindowCap(contentEl, activeView.stream.thinkingText || '');
    }
    // #346 v2 stats: close the thinking-duration window for the turn header.
    if (activeView.stream.currentThinkingBubble.dataset) {
      activeView.stream.currentThinkingBubble.dataset.nfEnd = String(Date.now());
    }
    // Auto-collapse at stream end (2026-09-07 09:24 author ruling, restoring
    // the long-lived pre-2026-08-20 behavior): expanded while streaming,
    // collapsed once done — regardless of mid-stream user toggles. The label
    // stays clickable so the user can re-expand afterwards. The turn-terminal
    // tuck (turnGroup collapseTurn) still folds the whole row at done.
    activeView.stream.currentThinkingBubble.classList.add('thinking-done');
    const label = activeView.stream.currentThinkingBubble.querySelector('.thinking-label');
    const content = activeView.stream.currentThinkingBubble.querySelector('.thinking-content');
    // Done label reverts to the pre-#345 design (2026-08-24 ruling):
    // text「思考过程」, streaming dots removed, no chevron.
    if (label) {
      label.classList.remove('thinking-streaming');
      label.classList.add('collapsible');
      label.querySelectorAll('.thinking-dot').forEach((d) => d.remove());
      const labelText = label.querySelector('.thinking-label-text');
      if (labelText) labelText.textContent = t('chat.thinkingLabel');
      if (content) content.style.display = 'none';
      label.classList.remove('expanded');
      label.setAttribute('aria-expanded', 'false');
      bindCollapsibleToggle(label, () => content);
    } else if (content) {
      content.style.display = 'none';
    }
    const text = activeView.stream.thinkingText;
    // 2026-09-06 二次裁定: thinking rows carry NO footer — thinking is
    // intermediate-process display, not message-class; footers are
    // message-class only (user / assistant final text / ask / agent /
    // skill). Corrects f8ea9385's classification.
    activeView.stream.currentThinkingBubble = null;
    activeView.stream.thinkingText = '';
    // B2: the thinking segment closed — clear its identity (see finishAi).
    activeView.stream.thinkSeg = '';
    return text;
  }
  return '';
}

