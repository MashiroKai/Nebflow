// input.js — Input handling module for Nebflow
// All send logic, keyboard/input events, slash commands, attachments, drag/drop, voice.

import state, { LS_HISTORY_KEY } from './state.js';
import { key } from './branding.js';
import { activeView, setActiveView, chatViews, findViewBySessionId } from './chatView.js';
import { sendWs } from './ws.js';
import { renderUserBubble, renderSystemBubble, setBusy, renderAttachmentPreview, renderAskBubble, renderSkillBubble, cancelToolStreamRAF, refreshSendButtonState } from './chat.js';
import { renderMarkdownWithMath, escapeHtml, smartScroll } from './utils.js';
import { saveMsg } from './persistence.js';
import { saveInputDraft } from './sidebar.js';
// renderTaskList 未使用 import 已随旧任务区退役移除（2026-09-05 裁定）
import { t } from './i18n.js';
import { getLocale } from './i18n.js';
import { renderQueueBar } from './chatQueue.js';
import { ticketUrl } from './nfTicket.js';
import { makeReference } from './reference.js';
// quote-message 一期：引用条落面/清理（引用态数据面 = pendingAttachments 的
// chat-message ref；发送/排队清空后同步引用条显隐）。
import { renderQuoteStrip } from './msgQuote.js';
import { startDictation, stopDictation, isModelReady } from './voiceEngine.js';
import { notifyVoiceState } from './micOrb.js';
import { showToast } from './modal.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';
// @-mention project autocomplete (mention-routing batch; mention-cn 2026-09-27):
// '@' opens the mounted-projects popup unless the character right before it is
// an ASCII letter/digit (email-like 'foo@' never triggers) — whitespace, line
// start, CJK characters and CJK punctuation all open it. Data source =
// fetchProjects() (nodeData.js).
import { bindMention, handleMentionKeydown, closeMentionDropdown } from './mentionComplete.js';

// input.js 拆分（FE组件化批次四 2026-09-27）：slash 命令族/输入模式族/附件与引用帧族 → js/input/ 可复用模块（行为保持）。
// 主体消费面 import + 原导出面转发（23 个 export 仍全部可从 input.js 取得，批次一~三同款纪律）。
import { handleSlash, updateSlashDropdown, closeSlashDropdown, setSlashHighlight, pickSlashCommand } from './input/slashCommands.js';
export { registerSkillCommands, handleSlash, registerCommand } from './input/slashCommands.js';
import { cancelAskMode, cancelSkillMode, cancelCompactMode } from './input/inputModes.js';
export { enterAskMode, cancelAskMode, enterSkillMode, cancelSkillMode, enterCompactMode, cancelCompactMode, applyInputModes } from './input/inputModes.js';
import { addFileAttachment, pendingAttCount, isRefOnlyFrame, syncRefOnlyGate, showAttError, joinAbsPath } from './input/attachments.js';
export { addFileAttachment, wireAttachmentsOf, isRefOnlyFrame, syncRefOnlyGate, appendRefToActiveView, initGlobalFileDrop } from './input/attachments.js';

// ---------- 真人消息 turn 标志（2026-09-16 msunread-r2；作者裁定 ①）----------
// 「本机派发了一条真人消息 = 本 turn 的起点」的 per-session turn 级标志。
// 置位点 = 本文件四个**真人派发**点，与既有 `state.turnExpecting[sid] = true`
// **同点同条件**：① `send()` skill 支 ② `send()` ask 支 ③ `send()` 普通支
// ④ `drainMessageQueue`（排队消息真派发台）。
// 为什么不复用 `turnExpecting`：后端 `sessionBusy{busy:true}`（main.js）同样置它
// ⇒ 纯程序 turn（REST `rest-turn` / CLI）与真人 turn **不可分**（真渲染读数：两侧
// 帧序同为 sessionBusy→done→sessionBusy，均被置位）。本标志只由本文件（真人派发）
// 置位，是客户端**唯一** turn 级的真人痕迹（后备候选「消息缓存」已在真渲染里证伪：
// 真人腿 `done` 时刻该会话缓存为 `["tool","tool","tool"]`，真人条目不在其中）。
// 消费方 = main.js 四个终态（done / error / timeout / maxTokens）：
// `takeRealUserTurn` **取用即清** ⇒ 同一枚真人消息只置一次未读。
const realUserTurnSessions = new Set();
/** 置位「本会话有一条真人消息在飞」（模块私有；调用点 = 四个真人派发点）。 */
function markRealUserTurn(sid) { if (sid) realUserTurnSessions.add(sid); }
/** 取用并清理该会话的真人消息 turn 标志（main.js 终态调用；缺省会话 ⇒ false）。 */
export function takeRealUserTurn(sid) { return sid ? realUserTurnSessions.delete(sid) : false; }

// ---------- Large text auto-attachment (paste detection) ----------
const LARGE_TEXT_THRESHOLD = 1000;
// Conversion cap (user ruling 2026-08-27, 方案①): pastes larger than this are
// inserted inline (plain message content) instead of converting to an
// attachment. BYTE-based because persistQueue's survival cap counts base64
// chars (400_000); base64 inflates ×4/3, so 300_000 bytes → exactly 400_000
// base64 chars — every converted attachment is guaranteed refresh-survivable
// and the 400KB-2MB loss window is mathematically closed (a char-based cap
// cannot guarantee this: CJK text is 3 bytes/char).
const LARGE_TEXT_MAX_BYTES = 300_000;
// Pure-paste inline threshold: pasting into an EMPTY (or whitespace-only)
// input at or below this size keeps the text as the message BODY — the agent
// receives the full content in turn 1 with zero tool calls. Above it the
// legacy file-attachment conversion applies (rules 2/3 unchanged). Byte-based,
// consistent with LARGE_TEXT_MAX_BYTES. 64KB = top of the sanctioned 32-64KB
// band: covers nearly all source-file pastes, stays ~1/5 of the attachment
// byte cap (300_000) and far under the 10MB WS frame cap, so message text,
// drafts, input history and persisted bubbles all keep comfortable margin.
const INLINE_PASTE_MAX_BYTES = 64 * 1024;

/** Show a transient banner at the top of the viewport. */
function showAttachmentBanner(message) {
  const banner = document.createElement('div');
  banner.className = 'attachment-banner';
  banner.textContent = message;
  banner.style.cssText = 'position:fixed;top:50px;left:50%;transform:translateX(-50%);background:var(--color-surface,rgba(20,25,35,0.9));color:var(--color-text);padding:8px 16px;border-radius:8px;z-index:1000;font-size:13px;box-shadow:0 2px 12px rgba(0,0,0,0.15);border:1px solid var(--glass-border);transition:opacity 0.3s;';
  document.body.appendChild(banner);
  setTimeout(() => { banner.style.opacity = '0'; }, 2700);
  setTimeout(() => banner.remove(), 3000);
}

// ---------- Send ----------
export function send() {
  // Capture the view at entry — activeView is a live module binding that ws.js
  // changes on every incoming message. Without capturing, setTimeout closures
  // (e.g. the 300ms isSending debounce) would clear the flag on the wrong view
  // when another session's streaming messages arrive during the window.
  const v = activeView;
  if (v.isSending) {
    console.warn('[send] blocked: already sending');
    return;
  }
  const input = v.dom.input;
  // @-mention popup: send() clears the input on every path below, which would
  // strand the popup over the next turn — close it up front.
  closeMentionDropdown();
  const text = input.value.trim();
  const isBusy = state.busySessionIds.has(v.sessionId) || state.compactingSessionIds.has(v.sessionId);
  // If in skill mode, send as skill activation
  if (v.skillMode) {
    const skillName = v.skillModeName;
    cancelSkillMode();
    if (!text) return;
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
    if (isBusy) {
      queueMessage(v, text, [], skillName);
      input.value = '';
      input.style.height = 'auto';
      saveInputDraft(v.sessionId);
      setTimeout(() => { v.isSending = false; }, 300);
      return;
    }
    v.isSending = true;
    if (v.sessionId) { state.turnExpecting[v.sessionId] = true; markRealUserTurn(v.sessionId); }
    sendWs({ type: 'skill', skillName, input: text, sessionId: v.sessionId });
    renderSkillBubble(skillName, text);
    saveMsg({type:'user', text, attachments: (v.pendingAttachments||[]).map(a=>({type:a.type,name:a.name,preview:a.preview}))});
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  // If in ask mode, send as ask question
  if (v.stream.askMode) {
    cancelAskMode();
    if (!text || isBusy || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
      v.isSending = false;
      return;
    }
    v.isSending = true;
    if (v.sessionId) { state.turnExpecting[v.sessionId] = true; markRealUserTurn(v.sessionId); }
    sendWs({ type: 'ask', question: text, sessionId: v.sessionId });
    state.sessionAskBuffers[v.sessionId] = { question: text, answer: '' };
    renderAskBubble(text);
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  // If in compact mode, send as compact command (empty input is OK — triggers default compact)
  if (v.compactMode) {
    cancelCompactMode();
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
      v.isSending = false;
      return;
    }
    if (isBusy) {
      queueMessage(v, text, [], null, 'compact');
      input.value = '';
      input.style.height = 'auto';
      saveInputDraft(v.sessionId);
      setTimeout(() => { v.isSending = false; }, 300);
      return;
    }
    v.isSending = true;
    sendWs({type:'command', command:'compact', sessionId: v.sessionId, instruction: text || undefined});
    renderSystemBubble(text
      ? t('slash.compactDone') + ' — ' + text
      : t('slash.compactDone'));
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  // Allow slash commands (except /ask <question> which sends to the agent)
  // even when the session is busy — they are UI/meta operations.
  if (text.startsWith('/') && !text.startsWith('/ask ')) {
    if (handleSlash(text)) {
      input.value = '';
      input.style.height = 'auto';
      saveInputDraft(v.sessionId);
      setTimeout(() => { v.isSending = false; }, 300);
      return;
    }
  }
  // Empty input — just ignore
  if (!text && v.pendingAttachments.length === 0) {
    return;
  }
  // Wait for pending attachment processing (image compression, file reading)
  // to prevent race condition where image is lost because send() runs before
  // addFileAttachment finishes pushing to pendingAttachments.
  if (pendingAttCount.value > 0) {
    setTimeout(() => send(), 200);
    return;
  }
  // LLM is busy — queue the message instead of blocking. Frozen sessions
  // EXEMPT: a frozen agent never runs a turn, so queueing would leave the
  // message parked forever — the whole point of freeze is that a user message
  // WAKES the agent (spec §3.2 input.js, F7). Fall through to the normal send
  // path below (direct WS frame, no queueMessage).
  if (isBusy && !state.frozenSessions.has(v.sessionId)) {
    queueMessage(v, text, v.pendingAttachments);
    input.value = '';
    input.style.height = 'auto';
    v.pendingAttachments = [];
    v.dom.attPreview.innerHTML = '';
    renderQuoteStrip({ attPreviewEl: v.dom.attPreview, attachments: v.pendingAttachments }); // quote-message 一期：排队后引用 chip 随 pending 清空
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    console.warn('[send] ws not open:', { ws: !!state.ws, readyState: state.ws?.readyState });
    return;
  }
  v.isSending = true;
  // Mark this session as expecting a turn (prevents stray thinking bubbles after done)
  if (v.sessionId) { state.turnExpecting[v.sessionId] = true; markRealUserTurn(v.sessionId); }
  // Intercept /ask <question> before normal slash handling
  if (text.startsWith('/ask ')) {
    const question = text.slice(5).trim();
    if (question) {
      sendWs({ type: 'ask', question, sessionId: v.sessionId });
      state.sessionAskBuffers[v.sessionId] = { question, answer: '' };
      renderAskBubble(question);
    }
    input.value = '';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  if (handleSlash(text)) {
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    // Debounce: keep lock briefly to prevent accidental double-trigger of slash commands
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  // quote-message 一期（spec §3.6）：clientMessageId 生成上提到 renderUserBubble
  // 之前 —— 用户行的 data-msg-id = 这个 id（与引擎帧 clientMessageId 同键，为二期
  // 引擎 id 收口预留）。
  const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  renderUserBubble(text, v.pendingAttachments, undefined, { msgId: clientMessageId });
  saveMsg({type:'user', text, attachments: (v.pendingAttachments||[]).map(a => a.type === 'taskRef'
    ? { type: a.type, name: a.subject, taskId: a.taskId, sessionId: a.sessionId, subject: a.subject }
    : a.type === 'ref'
      ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) }
      : { type: a.type, name: a.name, preview: a.preview })});
  // Save to input history
  // (/clear 特判已随命令摘除退役 —— /clear 现在就是普通文本消息，照常入历史)
  if (text) {
    state.inputHistory.push(text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e) {
      // Quota exceeded — trim history to 100 entries and retry once
      if (state.inputHistory.length > 100) {
        state.inputHistory = state.inputHistory.slice(-100);
        try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e2) {}
      }
      console.debug('[input] history save failed:', e);
    }
  }
  v.historyIndex = -1;
  v.historyDraft = '';
  try {
    // v2 §5.2/§6.1 + B (2026-08-20): taskRefs carry taskId/sessionId/subject —
    // 描述/产出不重复进载荷（agent 凭 taskId 定位任务，记忆里有上下文）；
    // 用户意见 = 本帧 content（§5.4，后端落 notes）。
    const taskRefs = (v.pendingAttachments || [])
      .filter(a => a.type === 'taskRef')
      .map(a => ({ taskId: a.taskId, sessionId: a.sessionId || v.sessionId, ...(a.subject ? { subject: a.subject } : {}) }));
    // Global Reference (2026-08-25 #303): carry unified refs on the wire. Backend's
    // processTaskReturns reads taskRefs via circe cursor downField(...).getOrElse(Nil)
    // — unknown fields (refs) are tolerated, so this is forward-compatible.
    const refs = (v.pendingAttachments || [])
      .filter(a => a.type === 'ref')
      .map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) }));
    notifyFriendRefsSent(refs);
    sendWs({
      content: text,
      ...(taskRefs.length > 0 ? { taskRefs } : {}),
      ...(refs.length > 0 ? { refs } : {}),
      attachments: (v.pendingAttachments || [])
        .filter(a => a.type !== 'taskRef' && a.type !== 'ref')
        .map(a => ({
          mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
        })),
      clientMessageId,
      sessionId: v.sessionId,
      chatWidth: v.dom.chat?.clientWidth || 0
    });
  } catch (e) {
    console.error('WebSocket send failed:', e);
  }
  input.value = '';
  input.style.height = 'auto';
  v.pendingAttachments = [];
  v.dom.attPreview.innerHTML = '';
  renderQuoteStrip({ attPreviewEl: v.dom.attPreview, attachments: v.pendingAttachments }); // quote-message 一期：发送完成 = 引用态消费（清空时机之一，spec §2.2）
  // Immediately clear the draft for this session so it is not restored after refresh
  saveInputDraft(v.sessionId);
  // ①-2 (2026-09-11) 乐观置位时序校验 —— 本地派发 = 新 turn 的起点，因此它「在
  // 已有终止帧时间戳之后」：允许置位并取代该时间戳（main.js 的 re-arm 闸靠它复位；
  // 否则上一 turn 的终止戳会把本次新 turn 的每一帧都挡在闸外）。
  // 校验点 = 帧确实写出去了：ws.js:275 的 sendWs 在非 OPEN 时是静默 no-op，此时
  // 置位会把会话顶成「无后端 turn」的假 busy —— 没有 turn 就没有终止帧，busy 永不
  // 自清、后续 Enter 全进本地队列且无 drain（正是症状① G3 的同族缺口）。
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    if (v.sessionId) delete state.lastTerminalAt[v.sessionId];
    setBusy(v.sessionId);
  } else {
    console.warn('[send] frame not dispatched (ws closed) — busy not armed (①-2 guard)');
  }
  // Start turn timer
  state.turnStartTimes[v.sessionId] = Date.now();
  // Release send lock after a short debounce to prevent double-click / rapid Enter
  setTimeout(() => { v.isSending = false; }, 300);
  // Clean up any orphaned thinking placeholders from previous incomplete streams
  if (window.__stopThinkingTimer) window.__stopThinkingTimer();
  v.dom.chat.querySelectorAll('.thinking-placeholder').forEach(el => {
    const row = el.closest('.row');
    if (row) row.remove();
  });
  v.stream.currentAiBubble = null;
  v.stream.aiText = '';
  v.stream.currentThinkingBubble = null;
  v.stream.thinkingText = '';
  // Safety timeout：后端 'timeout' 事件之外的兜底。freezetimeout B2 —— 到点不再发
  // interrupt（原实现会掐掉仍在慢速推进的 turn），改由阶梯看门只呈现「仍在处理」。
  const sid = v.sessionId;
  armBusyWatchdogFor(sid);
}

// ---------- Input Queue (messages typed while LLM is busy) ----------

/**
 * freezetimeout B2 (2026-09-20 · 诊断 chain-n-36a3f13d §4.2②)：新 turn 起点武装阶梯
 * 看门。判定/动作的唯一属主 = chat.js（armBusyWatchdog / onBusyWatchdogDeadline）——
 * 本文件原有的三处「到点即 `sendWs({type:'interrupt'})`」破坏性分支全部删除：到点只
 * 呈现「仍在处理」，中断降级为需用户显式确认。动态 import 沿用本文件的既有口径
 * （chat.js 侧不反向 import 本模块，静态 import 会成环）。
 */
export function armBusyWatchdogFor(sid) {
  if (!sid) return;
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  import('./chat.js')
    .then(({ armBusyWatchdog }) => { if (state.busySessionIds.has(sid)) armBusyWatchdog(sid); })
    .catch((e) => console.error('[input] arm busy watchdog failed:', e));
}

let queueCounter = 0;
const LS_QUEUE_KEY = key('message_queue');

/** Persist message queue to localStorage so it survives browser refresh.
 *  Exported for sidebar.js deleteSession — a deleted session's queue entries
 *  must also leave the persisted copy, or a refresh resurrects them (D2,
 *  mem-diag 20260907). */
export function persistQueue() {
  try {
    // Strip non-serializable fields (preview images are large; keep metadata only)
    const serializable = {};
    for (const [sid, items] of Object.entries(state.messageQueue)) {
      if (!items || items.length === 0) continue;
      serializable[sid] = items.map(it => ({
        id: it.id,
        text: it.text,
        skillName: it.skillName || null,
        mode: it.mode || null,
        // v2 B15: taskRef chips must survive refresh — keep taskId/sessionId
        // (+ subject as the display name); #303: ref blocks keep their full
        // Reference shape; file/image keep type+name plus their CLASSIFICATION
        // metadata (mimeType + hash/size) — EXCEPT pasted-text attachments,
        // whose base64 data IS the only copy of the content (the file never
        // existed on disk). Keep small text payloads (<=400KB base64) so
        // drain-after-refresh still delivers the content; data/preview payloads
        // still never persist (too large for localStorage).
        attachments: (it.attachments || []).map(a => a.type === 'taskRef'
          ? { type: 'taskRef', taskId: a.taskId, sessionId: a.sessionId, subject: a.subject, name: a.subject }
          : a.type === 'ref'
            ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) }
            : a.type === 'text' && typeof a.data === 'string' && a.data.length > 0 && a.data.length <= 400000
              ? { type: a.type, mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0 }
              // B+C 批 · 方案 B（不可恢复标记）：走到本支 = **载荷被剥离**。
              // 只在**确有载荷可丢**（`data` / `preview` 其一存在）时置 `stripped: true`
              // —— 纯元数据缺失不报，否则每个 file 附件都会假报，把真信号淹掉
              // （取证稿 §3.3 的降噪口径：噪声化 = 另一种静默）。
              // 标记随队列项一起落 localStorage ⇒ 跨刷新存活，撤回时据此给**可判读**
              // 文案（下面 recallQueuedItem），而不是让用户以为附件还在。
              //
              // busyfix-image-mime（2026-10-09，缺陷 #45 批申报）：载荷剥离 ≠ 分类元数据
              // 剥离。mimeType 是后端图片分类的**唯一判据**（WebSocketRoutes.scala:1616
              // 读帧，:1734 `mimeType.startsWith("image/") ⇒ type:"image"`），剥掉它 ⇒
              // 重发帧被落库成 `type:"file", path:null` = 历史重放永久文件卡（实证
              // 5cc7590a….ui.json「截屏2026-10-02 09.08.27.png」行）。metadata 保留后：
              // 无刷新路径 data 本就在内存队列 ⇒ 重发帧 mimeType+data 齐全，与直接发送
              // 完全同路（image 分支 + 落 uploads + 重放媒体预览）；刷新路径字节本就
              // 不可恢复（既有取舍，stripped 标记如实申报），但分类不再谎报。
              // hash/size 顺带保留 = 超大 text 附件的磁盘解析引用（后端
              // findLocalFile/Spotlight 判据 hash 非空 + size>0，WebSocketRoutes.scala:1675）。
              : { type: a.type, name: a.name, mimeType: a.mimeType, hash: a.hash || '', size: a.size || 0, ...(a.data || a.preview ? { stripped: true } : {}) })
      }));
    }
    localStorage.setItem(LS_QUEUE_KEY, JSON.stringify(serializable));
  } catch (e) { /* storage full or unavailable — non-critical */ }
}

/** Restore message queue from localStorage on page load. */
export function restoreQueue() {
  try {
    const raw = localStorage.getItem(LS_QUEUE_KEY);
    if (!raw) return;
    const data = JSON.parse(raw);
    for (const [sid, items] of Object.entries(data)) {
      if (Array.isArray(items) && items.length > 0) {
        state.messageQueue[sid] = items;
        // Restore queueCounter to avoid ID collisions
        for (const it of items) {
          if (it.id > queueCounter) queueCounter = it.id;
        }
        // Re-render queue bar for the restored session
        refreshQueue(sid);
      }
    }
  } catch (e) { /* corrupt data — ignore */ }
}

/** Helper: re-render the queue bar for a session with standard handlers. */
function refreshQueue(sessionId) {
  renderQueueBar(sessionId, {
    onImmediate: (item) => sendImmediate(sessionId, item),
    onRecall: (item) => recallQueuedItem(sessionId, item),
    onRemove: (item) => removeQueuedItem(sessionId, item)
  });
}

// Re-render queue bar when user switches to a different session
window.addEventListener('queuebar-refresh', (e) => {
  refreshQueue(e.detail.sessionId);
});

function queueMessage(view, text, attachments, skillName, mode) {
  const sid = view.sessionId;
  const item = {
    id: ++queueCounter,
    text,
    attachments: attachments.map(a => ({ ...a })),
    skillName,
    mode
  };
  if (!state.messageQueue[sid]) state.messageQueue[sid] = [];
  state.messageQueue[sid].push(item);
  refreshQueue(sid);
  persistQueue();
}

function sendImmediate(sessionId, item) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  const taskRefs = (item.attachments || []).filter(a => a.type === 'taskRef');
  const refs = (item.attachments || []).filter(a => a.type === 'ref');
  // File/image attachments (base64 data payload) ride the same default
  // user-message frame. Without this branch, an attachment-only queued item
  // fell into the immediateInput branch, whose backend handler reads only
  // `content` — with content === '' the frame hit handleUserText's empty
  // guard and the agent never saw it (silent non-response).
  const fileAtts = (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref');
  if (item.mode === 'compact') {
    sendWs({ type: 'command', command: 'compact', sessionId, instruction: item.text || undefined });
  } else if (item.skillName) {
    sendWs({ type: 'skill', skillName: item.skillName, input: item.text, sessionId });
  } else if (taskRefs.length > 0 || refs.length > 0 || fileAtts.length > 0) {
    // v2 + #303: a return/reference message must ride the default user-message
    // branch — the backend parses taskRefs/refs only there (WebSocketRoutes
    // §6.2); immediateInput goes through handleUserText and would drop them.
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    notifyFriendRefsSent(refs);
    sendWs({
      content: item.text,
      ...(taskRefs.length > 0 ? { taskRefs: taskRefs.map(a => ({ taskId: a.taskId, sessionId: a.sessionId || sessionId })) } : {}),
      ...(refs.length > 0 ? { refs: refs.map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) })) } : {}),
      attachments: (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref').map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
      })),
      clientMessageId,
      sessionId,
      chatWidth: activeView?.dom?.chat?.clientWidth || 0
    });
  } else {
    sendWs({ type: 'immediateInput', content: item.text, sessionId });
  }
  // Render in chat
  if (activeView && activeView.sessionId === sessionId) {
    if (item.mode === 'compact') {
      renderSystemBubble(item.text ? t('slash.compactDone') + ' — ' + item.text : t('slash.compactDone'));
    } else if (item.skillName) {
      renderSkillBubble(item.skillName, item.text);
    } else {
      renderUserBubble(item.text, item.attachments);
    }
  }
  saveMsg({ type: 'user', text: item.text, attachments: (item.attachments || []).map(a => a.type === 'taskRef'
    ? { type: a.type, name: a.subject, taskId: a.taskId, sessionId: a.sessionId, subject: a.subject }
    : a.type === 'ref'
      ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display }
      : { type: a.type, name: a.name, preview: a.preview }) }, sessionId);
  // Save to input history (same as normal send and drainMessageQueue)
  if (item.text) {
    state.inputHistory.push(item.text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e) {}
  }
  // Remove from queue and refresh bar
  const q = state.messageQueue[sessionId];
  if (q) {
    const idx = q.indexOf(item);
    if (idx >= 0) q.splice(idx, 1);
  }
  refreshQueue(sessionId);
  persistQueue();
}

function removeQueuedItem(sessionId, item) {
  const q = state.messageQueue[sessionId];
  if (!q) return;
  const idx = q.indexOf(item);
  if (idx >= 0) q.splice(idx, 1);
  refreshQueue(sessionId);
  persistQueue();
}

/** Recall a queued item back to the input box for editing, removing it from the queue. */
function recallQueuedItem(sessionId, item) {
  const q = state.messageQueue[sessionId];
  if (!q) return;
  const idx = q.indexOf(item);
  if (idx >= 0) q.splice(idx, 1);
  refreshQueue(sessionId);
  persistQueue();

  // Put text back into the input box
  const view = findViewBySessionId(sessionId);
  if (view && view.dom.input) {
    const text = item.mode === 'compact'
      ? `/compact ${item.text}`.trim()
      : item.skillName ? `/${item.skillName} ${item.text}` : item.text;
    view.dom.input.value = text;
    view.dom.input.style.height = 'auto';
    view.dom.input.focus();
    // Place cursor at end
    const len = view.dom.input.value.length;
    view.dom.input.setSelectionRange(len, len);
    // A-batch (msgqueue-recall-attach, 2026-09-14): the splice above removed the
    // last live reference to item.attachments, so the attachment payload used to
    // vanish right here with no trace. Feed it back through the SAME path send()
    // and appendRefToActiveView() use — no new mechanism. Mixed set (the input
    // box already holds pending attachments) = append, never drop either side.
    const queuedAtts = Array.isArray(item.attachments) ? item.attachments : [];
    if (queuedAtts.length > 0) {
      if (!Array.isArray(view.pendingAttachments)) view.pendingAttachments = [];
      const before = view.pendingAttachments.length;
      view.pendingAttachments.push(...queuedAtts.map(a => ({ ...a })));
      console.warn('[input] recallQueuedItem: restored ' + queuedAtts.length
        + ' attachment(s) from queued item #' + item.id
        + ' (pending ' + before + ' -> ' + view.pendingAttachments.length + ')');
      if (view.dom.attPreview) {
        renderAttachmentPreview({ attPreviewEl: view.dom.attPreview, attachments: view.pendingAttachments });
      }
      // quote-message 一期：排队项撤回输入框 —— chat-message ref 随附件原样回填；
      // 2026-10-09 改版后引用 chip 与附件同条带 ⇒ 上面的 renderAttachmentPreview
      // 一次重建即同时复原两者（数据单点 = pendingAttachments，无需二次落面调用）。
      // Refresh blind spot: persistQueue() reduces images/large payloads to a
      // {type, name} skeleton, so such an item cannot be restored for real.
      //
      // B+C 批 · 方案 B：两级判据 ——
      //  ① 显式标记（本批新增，**权威**）：`persistQueue` 在剥离 `data`/`preview` 时
      //     写下 `stripped: true`；标记随 localStorage 存活 ⇒ 撤回时**知道**自己丢过东西。
      //  ② 结构兜底（A-only 批既有，保留）：本标记落地**之前**就已持久化的旧队列项
      //     没有标记，按「四键俱缺」形态识别。⇒ 旧项不因新标记上线而漏报。
      // 判据逻辑**不含**任何「猜内容」：只读标记与结构，不尝试从服务端回补（回补不存在）。
      const stripped = view.pendingAttachments.filter(a => a.type !== 'taskRef' && a.type !== 'ref'
        && (a.stripped === true || (!a.data && !a.preview && !a.hash && !a.mimeType)));
      if (stripped.length > 0) {
        console.warn('[input] recallQueuedItem: ' + stripped.length
          + ' attachment(s) lost their payload to page-refresh persistence and must be re-added');
        // **用户可见**文案（方案 B 的「文案」半边）：修前只有 console.warn —— 对用户
        // 而言附件**静默消失了**（红点/角标没有、输入框里也不见了），正是本次要修的
        // 「不得静默」形态。文案说清**为什么**（页面刷新）与**怎么办**（重新添加）。
        window.__showToast?.(t('messages.queueAttachUnrecoverable', { n: stripped.length }), 'error');
      }
    }
    saveInputDraft(sessionId);
  } else if (Array.isArray(item.attachments) && item.attachments.length > 0) {
    // J5 residual path: no mounted input box means the payload can be neither
    // restored nor edited — say so instead of letting it vanish silently.
    console.warn('[input] recallQueuedItem: no mounted input view for ' + sessionId
      + ' — ' + item.attachments.length + ' attachment(s) of queued item #' + item.id
      + ' could not be restored');
  }
}

/** Called on 'done' event — send first queued message as normal UserInput.
 *  Works even when the session isn't currently displayed (view is null):
 *  DOM operations are skipped, but the WS message is still sent. */
export function drainMessageQueue(sessionId) {
  const q = state.messageQueue[sessionId];
  if (!q || q.length === 0) return false;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return false;
  const item = q[0];
  const view = findViewBySessionId(sessionId);

  // Remove from queue
  q.shift();
  refreshQueue(sessionId);
  persistQueue();

  // Render in chat (only if this session is the active view)
  if (view && activeView && activeView.sessionId === sessionId) {
    if (item.mode === 'compact') {
      renderSystemBubble(item.text ? t('slash.compactDone') + ' — ' + item.text : t('slash.compactDone'));
    } else if (item.skillName) {
      renderSkillBubble(item.skillName, item.text);
    } else {
      renderUserBubble(item.text, item.attachments);
    }
  }

  // Save to history
  if (item.text) {
    state.inputHistory.push(item.text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e) {}
  }
  saveMsg({ type: 'user', text: item.text, attachments: (item.attachments || []).map(a => a.type === 'taskRef'
    ? { type: a.type, name: a.subject, taskId: a.taskId, sessionId: a.sessionId, subject: a.subject }
    : a.type === 'ref'
      ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display }
      : { type: a.type, name: a.name, preview: a.preview }) }, sessionId);

  // Send as normal UserInput
  if (sessionId) { state.turnExpecting[sessionId] = true; markRealUserTurn(sessionId); }
  if (view) {
    view.isSending = true;
    view.historyIndex = -1;
    view.historyDraft = '';
  }

  if (item.mode === 'compact') {
    sendWs({ type: 'command', command: 'compact', sessionId, instruction: item.text || undefined });
  } else if (item.skillName) {
    sendWs({ type: 'skill', skillName: item.skillName, input: item.text, sessionId });
  } else {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    // v2 B15: queued return messages keep their taskRefs when drained.
    // #303: unified refs ride the same default user-message frame (backend
    // parses refs via circe cursor — tolerated for forward-compat).
    const taskRefs = (item.attachments || []).filter(a => a.type === 'taskRef');
    const refs = (item.attachments || []).filter(a => a.type === 'ref');
    notifyFriendRefsSent(refs);
    sendWs({
      content: item.text,
      ...(taskRefs.length > 0 ? { taskRefs: taskRefs.map(a => ({ taskId: a.taskId, sessionId: a.sessionId || sessionId })) } : {}),
      ...(refs.length > 0 ? { refs: refs.map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) })) } : {}),
      attachments: (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref').map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
      })),
      clientMessageId,
      sessionId,
      chatWidth: view?.dom?.chat?.clientWidth || 0
    });
  }

  // ①-2: 本次 drain 派发 = 新 turn 起点 ⇒ 取代上一 turn 的终止戳（同 send()）。
  if (sessionId) delete state.lastTerminalAt[sessionId];
  setBusy(sessionId);
  state.turnStartTimes[sessionId] = Date.now();

  // Safety timeout（freezetimeout B2：到点只呈现「仍在处理」，不发 interrupt）
  armBusyWatchdogFor(sessionId);

  // Clean up thinking placeholders (only for displayed sessions)
  if (view) {
    if (window.__stopThinkingTimer) window.__stopThinkingTimer();
    view.dom.chat.querySelectorAll('.thinking-placeholder').forEach(el => {
      const row = el.closest('.row');
      if (row) row.remove();
    });
    view.stream.currentAiBubble = null;
    view.stream.aiText = '';
    view.stream.currentThinkingBubble = null;
    view.stream.thinkingText = '';
    setTimeout(() => { view.isSending = false; }, 300);
  }
  return true;
}

// ---------- Inject User Message (for plugin card interactions) ----------
export function injectUserMessage(text, options = {}) {
  /**
   * Inject a user message into the conversation as if the user typed it.
   * Used by agent frontend cards (e.g., Pulsar waveform confirm/modify buttons).
   *
   * @param {string} text - The message text to inject
   * @param {object} options - Optional: { sessionId, silent }
   *   - sessionId: target session (defaults to active)
   *   - silent: if true, don't render user bubble (for programmatic confirmations)
   */
  const sessionId = options.sessionId || activeView.sessionId;
  if (!text || !text.trim()) {
    console.warn('[injectUserMessage] empty text');
    return false;
  }
  if (!sessionId) {
    console.warn('[injectUserMessage] no active session');
    return false;
  }
  const isBusy = state.busySessionIds.has(sessionId);
  if (isBusy) {
    console.warn('[injectUserMessage] session is busy:', sessionId);
    return false;
  }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    console.warn('[injectUserMessage] ws not open');
    return false;
  }

  const trimmed = text.trim();

  // Render user bubble (unless silent)
  if (!options.silent) {
    renderUserBubble(trimmed, []);
  }

  // Save to persistence (plain user message — plugin-card interaction, not a
  // tool injection, so no `injected` marker)
  saveMsg({ type: 'user', text: trimmed });

  // Send via WebSocket (same format as normal send)
  const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  sendWs({
    content: trimmed,
    attachments: [],
    clientMessageId,
    sessionId,
    chatWidth: activeView.dom.chat?.clientWidth || 0
  });

  // Mark session as busy
  // ①-2: 本派发同样是新 turn 起点 ⇒ 取代上一 turn 的终止戳（同 send()/drain）。
  if (sessionId) delete state.lastTerminalAt[sessionId];
  setBusy(sessionId);
  // Only set timer if not already running — don't reset during active turn
  if (!state.turnStartTimes[sessionId]) state.turnStartTimes[sessionId] = Date.now();

  // Safety timeout (same as normal send) —— freezetimeout B2: 到点只呈现，不发 interrupt
  armBusyWatchdogFor(sessionId);

  return true;
}

// ---------- Initialize all input event listeners ----------

// Close the active view's slash dropdown on outside clicks. Bound ONCE at
// module scope: popup ChatViews rebuild their input DOM on every open
// (openStepPopup re-runs initInput each time), so a per-initInput document
// binding would both accumulate listeners and reference stale detached
// elements. Resolution goes through activeView — only one dropdown can be
// open at a time (updateSlashDropdown renders into activeView.dom only).
document.addEventListener('click', (e) => {
  const v = activeView;
  if (!v || !v.dom || !v.dom.input || !v.dom.slashDropdown) return;
  if (!v.dom.input.contains(e.target) && !v.dom.slashDropdown.contains(e.target)) {
    v.dom.slashDropdown.classList.remove('on');
    v.slashMatches = [];
    v.slashSelectedIndex = -1;
  }
});

export function initInput(view) {
  const input = view.dom.input;
  const sendBtn = view.dom.sendBtn;
  const stopBtn = view.dom.stopBtn;
  const attachBtn = view.dom.attachBtn;
  const voiceBtn = view.dom.voiceBtn;
  const voiceOverlay = view.dom.voiceOverlay;
  const voiceText = view.dom.voiceText;

  const slashDropdown = view.dom.slashDropdown;

  // Sync the send button's connection state on init (grey until connected).
  refreshSendButtonState();

  // ── fwdguard-impl (2026-09-17)：纯引用帧闸的提示件（每视图一件）─────────
  // 挂在 input-wrap 内、attPreview **之外**：renderAttachmentPreview 会整块
  // innerHTML='' 重建 strip，提示件挂进去会被下一次渲染抹掉（attachment-preview
  // 只承载载荷卡片）。文案走 i18n（禁硬编码中文）。
  if (!view.dom.refGateHint && input.parentElement) {
    const hint = document.createElement('div');
    hint.className = 'ref-gate-hint';
    hint.dataset.refGateHint = '1';
    hint.hidden = true;
    hint.textContent = t('input.refOnlyHint');
    input.parentElement.insertBefore(hint, input);
    view.dom.refGateHint = hint;
  }

  // Auto-resize textarea + 同步纯引用帧闸（文本变化会改变闸判据）。
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    syncRefOnlyGate(view);
  });

  // 附件/引用条的任何重建都会同步闸态（观察器覆盖面含 chat.js 侧 ref 芯片的移除
  // 按钮与 send() 尾部清空 strip 两条非本文件路径）。popup 每次打开重建 DOM ⇒
  // 旧观察器先断开，避免挂在已脱离文档的节点上。
  if (view.dom.attPreview && typeof MutationObserver !== 'undefined') {
    view.dom.refGateObserver?.disconnect?.();
    const mo = new MutationObserver(() => syncRefOnlyGate(view));
    mo.observe(view.dom.attPreview, { childList: true, subtree: true });
    view.dom.refGateObserver = mo;
  }
  syncRefOnlyGate(view);

  // Send button —— 入口①（点击）。纯引用帧在此不产帧（网关 4055 会静默丢弃它）。
  sendBtn.onclick = () => {
    setActiveView(view);
    if (isRefOnlyFrame(view)) { syncRefOnlyGate(view); return; }
    send();
  };

  // Stop button — send interrupt with sessionId, reset UI immediately
  stopBtn.onclick = () => {
    setActiveView(view);
    const sid = view.sessionId;
    sendWs({type: 'interrupt', sessionId: sid});
    if (sid && state.sessionBusyTimeouts[sid]) {
      clearTimeout(state.sessionBusyTimeouts[sid]);
      delete state.sessionBusyTimeouts[sid];
    }
    import('./chat.js').then(({ clearBusy }) => clearBusy(sid));
  };

  // Element-level IME bookkeeping (⑤ 收归) — the keyboard decision below reads
  // `input.dataset.imeComposing`, written only by bindImeGuard.
  bindImeGuard(input);

  // @deprecated ⑤-A4（作者裁定 2026-09-12）：视图级 `view.composing` 已由
  // imeGuard 的元素级判定取代（14 个分叉点里 13 个根本没有 view 对象）。
  // 保留一版不删——本批未穷尽潜在读者面；新代码一律走 imeGuard，勿再读它。
  input.addEventListener('compositionstart', () => { view.composing = true; });
  input.addEventListener('compositionend', () => { view.composing = false; });

  // Paste handler — image paste + large text detection
  input.addEventListener('paste', (e) => {
    setActiveView(view);
    const files = [];
    if (e.clipboardData.items) {
      for (const item of e.clipboardData.items) {
        if (item.kind === 'file') {
          const f = item.getAsFile();
          if (f) files.push(f);
        }
      }
    }
    if (files.length === 0) {
      // Large text paste → auto-convert to file attachment via existing mechanism
      const pastedText = e.clipboardData.getData('text/plain') || '';
      if (pastedText.length > LARGE_TEXT_THRESHOLD) {
        // Pure-paste inline: empty input + paste ≤ INLINE_PASTE_MAX_BYTES →
        // let the browser insert the text; send() then delivers it as the
        // message content. No attachment, no Read tool call downstream.
        if (!input.value.trim() && new Blob([pastedText]).size <= INLINE_PASTE_MAX_BYTES) {
          return; // browser default paste — content becomes the message body
        }
        const blob = new Blob([pastedText], { type: 'text/plain' });
        if (blob.size > LARGE_TEXT_MAX_BYTES) {
          // Above the cap: keep inline (browser default paste) + toast, never
          // silently drop the text.
          showToast(t('input.pasteTooLarge'));
          return;
        }
        e.preventDefault();
        e.stopPropagation();
        const file = new File([blob], `pasted-text-${Date.now()}.txt`, { type: 'text/plain' });
        addFileAttachment(file);
        showAttachmentBanner(`大段文本（${pastedText.length} 字符）已转为文件附件`);
        return;
      }
      return; // normal text paste, let browser handle it
    }
    e.preventDefault();
    e.stopPropagation(); // prevent document-level paste from double-processing
    files.forEach(file => addFileAttachment(file));
  });

  // Keydown handler — slash autocomplete navigation, input history navigation, Enter-to-send
  input.onkeydown = (e) => {
    setActiveView(view);
    // ⑤ IME 收归（作者裁定 2026-09-12，方案 §4.1 步骤 2）：组字判定上提到处理器
    // 首行统一短路——在 setActiveView 之后、任何 `e.key` 分支之前。组字期间
    // 每一个键都属于输入法（Enter 确认候选 / ↑↓ 选候选词 / Esc 取消组字 /
    // Backspace 删拼音），一律不 preventDefault、不动作，交还浏览器。
    // 该短路同时修掉「斜杠下拉抢先消费组字 Enter」的既有缺陷（⑤-A 同批修）。
    if (isImeComposing(e, input)) return;
    // @-mention popup navigation (ArrowUp/Down, Tab/Enter pick, Esc close).
    // Consumes keys only while the popup is open; IME keys never reach here
    // (hoisted guard above), so an IME session can neither navigate nor pick.
    if (handleMentionKeydown(e, view)) return;
    // Escape cancels ask/skill/compact mode
    if (e.key === 'Escape') {
      if (view.stream.askMode) {
        e.preventDefault();
        cancelAskMode();
        return;
      }
      if (view.skillMode) {
        e.preventDefault();
        cancelSkillMode();
        return;
      }
      if (view.compactMode) {
        e.preventDefault();
        cancelCompactMode();
        return;
      }
    }
    // Backspace/Delete on empty input cancels ask/skill/compact mode (like removing a tag)
    if ((e.key === 'Backspace' || e.key === 'Delete') && input.value.trim() === '') {
      if (view.stream.askMode) {
        e.preventDefault();
        cancelAskMode();
        return;
      }
      if (view.skillMode) {
        e.preventDefault();
        cancelSkillMode();
        return;
      }
      if (view.compactMode) {
        e.preventDefault();
        cancelCompactMode();
        return;
      }
    }
    if (slashDropdown.classList.contains('on')) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSlashHighlight((view.slashSelectedIndex + 1) % view.slashMatches.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSlashHighlight((view.slashSelectedIndex - 1 + view.slashMatches.length) % view.slashMatches.length);
        return;
      }
      if (e.key === 'Enter') {
        e.preventDefault();
        pickSlashCommand(view.slashSelectedIndex);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        closeSlashDropdown();
        return;
      }
    }
    // Input history navigation (up/down arrows). The composition arm that used
    // to sit here (`!view.composing && !e.isComposing && e.keyCode !== 229`) is
    // gone: the hoisted guard above already short-circuits every composed key
    // (⑤ 收归，方案 §4.1 步骤 2「删除 :1275 的重复判定」)。
    if (!slashDropdown.classList.contains('on')) {
      if (e.key === 'ArrowUp' && input.selectionStart === 0 && input.selectionEnd === 0) {
        e.preventDefault();
        if (state.inputHistory.length === 0) return;
        if (view.historyIndex === -1) {
          view.historyDraft = input.value;
          view.historyIndex = state.inputHistory.length - 1;
        } else if (view.historyIndex > 0) {
          view.historyIndex--;
        }
        input.value = state.inputHistory[view.historyIndex];
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
        input.setSelectionRange(input.value.length, input.value.length);
        return;
      }
      if (e.key === 'ArrowDown' && input.selectionStart === input.value.length && input.selectionEnd === input.selectionStart) {
        e.preventDefault();
        if (view.historyIndex === -1) return;
        if (view.historyIndex >= state.inputHistory.length - 1) {
          view.historyIndex = -1;
          input.value = view.historyDraft;
        } else {
          view.historyIndex++;
          input.value = state.inputHistory[view.historyIndex];
        }
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
        input.setSelectionRange(input.value.length, input.value.length);
        return;
      }
    }
    // Enter sends. The composition arm is covered by the hoisted guard, so the
    // gate is a plain Shift test now (⑤ 收归，方案 §4.1 步骤 2；语义等价、仅实现搬位)。
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      // 入口②（Enter 直发，fwdguard-impl）：纯引用帧不产帧 —— 该形态在网关
      // `WebSocketRoutes.scala:4055` 判空并被 `:4246` 静默丢弃（前端已 setBusy
      // ⇒ 会话永久转圈）。提示件由 syncRefOnlyGate 常驻显示。
      if (isRefOnlyFrame(view)) { syncRefOnlyGate(view); return; }
      send();
    }
  };

  // Attach button — hidden file input trigger (supports multiple files)
  attachBtn.onclick = () => {
    setActiveView(view);
    const f = document.createElement('input');
    f.type = 'file';
    f.multiple = true;
    f.style.display = 'none';
    document.body.appendChild(f);
    f.onchange = (e) => {
      const files = Array.from(e.target.files);
      files.forEach(file => addFileAttachment(file));
      f.remove();
    };
    f.click();
  };

  // Paste image support (Cmd/Ctrl+V) — primary only, unchanged.
  // Drag & drop moved to initGlobalFileDrop() (document-level delegation
  // covering primary + popup input bars — see #303).
  if (view.id === 'primary') {
    document.addEventListener('paste', (e) => {
      const items = e.clipboardData && e.clipboardData.items;
      if (!items) return;
      for (const item of items) {
        if (item.type && item.type.startsWith('image/')) {
          const f = item.getAsFile();
          if (f) {
            e.preventDefault();
            addFileAttachment(f);
          }
        }
      }
    });
  }

  // Voice dictation — uses browser Web Speech API (free, no API key needed)
  // Push-and-hold the mic button to start; release to stop.
  // Interim text streams directly into the input box — no overlay.
  let voiceActive = false;
  let voiceAnchor = 0;       // position where current voice segment starts
  let voiceInterimLen = 0;   // length of interim text currently displayed
  // Snapshot of input.value at the last voice write / user edit. The diff
  // baseline that lets us re-anchor the live draft slot after user edits
  // (2026-09-02 duplicate-fix; see the input listener below).
  let voiceBaseline = '';

  // Shared callbacks for dictation mode.
  function makeVoiceCallbacks() {
    return {
      onInterim: (text) => {
        // Replace [voiceAnchor, voiceAnchor + voiceInterimLen) with new interim text
        const before = input.value.substring(0, voiceAnchor);
        const after = input.value.substring(voiceAnchor + voiceInterimLen);
        input.value = before + text + after;
        voiceInterimLen = text.length;
        voiceBaseline = input.value;
        input.focus();
        input.setSelectionRange(voiceAnchor + text.length, voiceAnchor + text.length);
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
      },
      onText: (text) => {
        // Replace interim with final text + trailing space
        const before = input.value.substring(0, voiceAnchor);
        const after = input.value.substring(voiceAnchor + voiceInterimLen);
        const insert = text + ' ';
        input.value = before + insert + after;
        voiceInterimLen = 0;
        voiceAnchor = before.length + insert.length;
        voiceBaseline = input.value;
        input.setSelectionRange(voiceAnchor, voiceAnchor);
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
      },
      onState: (state, data) => {
        updateVoiceUI(state, data);
      },
    };
  }

  // Keep the voice draft slot honest across user edits while the mic is live
  // (2026-09-02 duplicate-fix). The continuous Web Speech session stays open
  // long after the user stops talking — Chrome auto-restarts on silence — so
  // moving the caret / deleting text between utterances shifts the value UNDER
  // the [voiceAnchor, voiceAnchor+voiceInterimLen) arithmetic. The next
  // interim/final then rewrote a STALE range: the same fragment landed twice,
  // or old text resurfaced at the wrong place. Programmatic .value writes
  // (the voice callbacks) never fire 'input', so everything reaching this
  // listener while voiceActive is a real user edit. Cases:
  //   1. empty slot  → the caret is the truth: next draft grows at the caret.
  //   2. edit entirely before the slot → shift the anchor by the length delta.
  //      Edit entirely after → anchor unchanged.
  //   3. edit touching the draft → the machine draft is invalidated; collapse
  //      the slot to empty at the caret. Chrome's redraft re-inserts once, at
  //      the right place — never two copies.
  input.addEventListener('input', () => {
    if (!voiceActive) return;
    if (voiceInterimLen === 0) {
      voiceAnchor = input.selectionStart ?? input.value.length;
    } else {
      const oldV = voiceBaseline;
      const newV = input.value;
      let p = 0;
      const min = Math.min(oldV.length, newV.length);
      while (p < min && oldV[p] === newV[p]) p++;
      let s = 0;
      while (s < min - p && oldV[oldV.length - 1 - s] === newV[newV.length - 1 - s]) s++;
      const editEndOld = oldV.length - s; // edit region in OLD coords: [p, editEndOld)
      const delta = newV.length - oldV.length;
      const slotEnd = voiceAnchor + voiceInterimLen;
      if (editEndOld <= voiceAnchor) {
        voiceAnchor += delta; // edit before the draft — draft slid by delta
      } else if (p >= slotEnd) {
        // edit after the draft — slot untouched
      } else {
        // edit inside the draft — draft invalidated
        voiceInterimLen = 0;
        voiceAnchor = input.selectionStart ?? input.value.length;
      }
    }
    voiceBaseline = input.value;
  });

  // Update voice UI —— 作者 2026-09-19 04:22 **修正④**：麦克风 = 普通麦克风形态
  // （micOrb 气泡/光球退役 ⇒ `notifyVoiceState` 在本窗已成 no-op，保留调用是为
  // 设置页/预览面上的同一渲染管线仍在册）。**反馈保留**（同令）：
  //   · 录音中（listening/speaking）= 既有 `.icon-btn.recording`（微信绿 + 既有
  //     `voicePulse` 脉冲，值零新增）+ `aria-pressed=true`（可访问态）；
  //   · 转写中（processing）= `.mic-btn.processing`（既有 sapphire 强调色）；
  //   · 出错 = 既有 `showToast(…, 'error')` 可见提示 + console.warn（不变）。
  function setMicState(state) {
    voiceBtn.classList.toggle('recording', state === 'listening' || state === 'speaking');
    voiceBtn.classList.toggle('processing', state === 'processing');
    voiceBtn.setAttribute('aria-pressed', String(state === 'listening' || state === 'speaking'));
  }
  function updateVoiceUI(state, data) {
    notifyVoiceState(state);
    switch (state) {
      case 'listening':
      case 'speaking':
        setMicState(state);
        break;
      case 'error':
        setMicState('idle');
        // Voice errors must be user-visible, not console-only (#stt-hotfix:
        // a denied/busy mic previously produced zero on-screen feedback).
        // data is an already-classified, i18n'd message from voiceEngine.
        showToast(data, 'error');
        console.warn('[voice] Error:', data);
        break;
      case 'idle':
        setMicState('idle');
        break;
      default:
        setMicState(state);
        break;
    }
  }

  async function startVoice() {
    voiceActive = true;
    voiceAnchor = input.selectionStart ?? input.value.length;
    voiceInterimLen = 0;
    // Add separator space if needed
    if (voiceAnchor > 0) {
      const charBefore = input.value[voiceAnchor - 1];
      if (charBefore && charBefore !== ' ' && charBefore !== '\n') {
        input.value = input.value.substring(0, voiceAnchor) + ' ' + input.value.substring(voiceAnchor);
        voiceAnchor++;
      }
    }
    voiceBaseline = input.value;
    setMicState('listening');
    input.classList.add('voice-dictating');
    input.focus();
    try { localStorage.setItem(key('voice_used'), '1'); } catch {}
    await startDictation(makeVoiceCallbacks());
  }

  function stopVoice() {
    voiceActive = false;
    // Remove any remaining interim cursor
    if (voiceInterimLen > 0) {
      const before = input.value.substring(0, voiceAnchor);
      const after = input.value.substring(voiceAnchor + voiceInterimLen);
      input.value = before + after;
      voiceInterimLen = 0;
      voiceBaseline = input.value;
    }
    // Show the "processing" state while the captured audio transcribes
    // (spec §9 pure-front-end addition — voiceEngine emits no processing state
    // after stop; the follow-up onState('idle') from the engine clears it).
    notifyVoiceState('processing');
    stopDictation();
    setMicState('processing');
    input.classList.remove('voice-dictating');
    input.focus();
  }

  // Click-toggle voice (user ruling 2026-08-25 21:34: tap once to start, tap
  // again to stop — replaces the old push-and-hold). Also spec §3.3.
  function onVoiceToggle(e) {
    if (e) e.preventDefault();
    setActiveView(view);
    if (voiceActive) stopVoice();
    else startVoice();
  }
  voiceBtn.addEventListener('click', onVoiceToggle);

  // Escape key stops voice recording (only register once)
  if (view.id === 'primary') {
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && voiceActive) {
        e.preventDefault();
        stopVoice();
      }
    });
  }

  // Slash dropdown input listener
  input.addEventListener('input', () => { setActiveView(view); updateSlashDropdown(); });

  // @-mention autocomplete (mention-routing batch): input + blur listeners,
  // token parsing and popup rendering live in mentionComplete.js. Key
  // handling is hooked in the keydown handler above (behind the imeGuard
  // short-circuit); send() closes the popup up front.
  bindMention(view);

  // Ask/skill indicator cancel buttons
  {
    const askCancel = document.getElementById('ask-indicator-cancel');
    if (askCancel) {
      askCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelAskMode();
        input.focus();
      });
    }
    const skillCancel = document.getElementById('skill-indicator-cancel');
    if (skillCancel) {
      skillCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelSkillMode();
        input.focus();
      });
    }
    const compactCancel = document.getElementById('compact-indicator-cancel');
    if (compactCancel) {
      compactCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelCompactMode();
        input.focus();
      });
    }
  }
}

/**
 * #290 A2A: notify listeners (messages.js) when friend-message refs actually
 * leave on the wire - the 「已转发给 agent」 chip is stamped only at this point
 * (addendum R3: drafting the ref in pendingAttachments does NOT mark it).
 * @param {Array} refs - ref attachments about to be sent (wire shape)
 */
function notifyFriendRefsSent(refs) {
  const ids = (refs || [])
    .filter(r => r && r.refType === 'friend-message' && r.source && r.source.messageId)
    .map(r => r.source.messageId);
  if (ids.length === 0) return;
  window.dispatchEvent(new CustomEvent('fm-refs-sent', { detail: { messageIds: ids } }));
}

// ---------- #303 internal drag: explorer file → attachment pipeline ----------
// Explorer rows drop with {path, rootPath} (explorer-relative path + absolute
// root, the same coordinates readFile/listDir use). We re-read the file through
// the SAME channels the tree uses — /api/nf-file for media (binary never rides
// the WS), readFile WS for text — then feed a real File object into
// addFileAttachment, so preview chips / upload / send are byte-identical to the
// attach-button path.

async function addPathAttachment({ path, rootPath }, view, target) {
  const name = path.split('/').pop() || path;
  const absPath = joinAbsPath(rootPath, path);
  // Typed window alias — the one-shot readFile guard flag shared with explorer.js.
  const win = /** @type {Window & { __internalDragReadPath: string | null }} */ (/** @type {any} */ (window));

  // Phase 1: whitelisted media (images, pdf, mp4…) come back from nf-file.
  // Non-whitelisted extensions → 400 "File type not allowed" → phase 2.
  //
  // 2026-09-11 (C batch): the URL comes from ticketUrl() — a per-path ticket,
  // minted here (at the moment of the drop), not the global gateway token.
  // A path that cannot be ticketed (or a mint outage) yields the ticket-free
  // URL, the endpoint answers 401/400, and we fall through to the readFile
  // phase exactly as before (T4: a deterministic non-media type is not
  // retried, it just takes the WS path).
  try {
    const resp = await fetch(await ticketUrl(absPath));
    if (resp.ok) {
      const blob = await resp.blob();
      const file = new File([blob], name, { type: blob.type || 'application/octet-stream' });
      addFileAttachment(file, null, target);
      return;
    }
  } catch { /* offline/network — fall through to readFile, it surfaces the error */ }

  // Phase 2: text files via readFile WS. The answer is a fileContent frame that
  // explorer.js routes to us (window flag + 'internal-file-read' event) instead
  // of opening a Canvas tab. One-shot guard with a timeout so a lost response
  // never leaks the flag into a later normal file open.
  const readDone = new Promise((resolve) => {
    let settled = false;
    const finish = (detail) => { if (!settled) { settled = true; resolve(detail); } };
    const onRead = (e) => {
      clearTimeout(timer);
      window.removeEventListener('internal-file-read', onRead);
      finish(e.detail);
    };
    const timer = setTimeout(() => {
      window.removeEventListener('internal-file-read', onRead);
      if (win.__internalDragReadPath === path) win.__internalDragReadPath = null;
      finish({ error: 'timeout' });
    }, 8000);
    window.addEventListener('internal-file-read', onRead);
  });
  win.__internalDragReadPath = path;
  // sessionId follows explorer.js's own readFile semantics (state.activeSessionId,
  // not the drop target view's session — the file tree is session-agnostic).
  sendWs({ type: 'readFile', sessionId: state.activeSessionId, path, rootPath: rootPath || undefined });
  const detail = await readDone;
  if (detail.error) {
    showAttError(`Failed to attach ${name}: ${detail.error}`, target);
    return;
  }
  if (!detail.content) {
    // Binary non-media (zip, bin…) — readFile never sends content for these and
    // nf-file is whitelist-only, so there is nothing to embed.
    showAttError(`Cannot attach binary file ${name} — reference its path in the message instead`, target);
    return;
  }
  const file = new File([detail.content], name, { type: 'text/plain' });
  addFileAttachment(file, null, target);
}
