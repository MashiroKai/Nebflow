// input.js — Input handling module for Nebflow
// All send logic, keyboard/input events, slash commands, attachments, drag/drop, voice.

import state, { LS_HISTORY_KEY } from './state.js';
import { key } from './branding.js';
import { activeView, setActiveView, chatViews, findViewBySessionId } from './chatView.js';
import { sendWs, onReconnect, onMessage } from './ws.js';
import { renderUserBubble, renderSystemBubble, setBusy, renderAttachmentPreview, renderAskBubble, renderSkillBubble, cancelToolStreamRAF, refreshSendButtonState, clearAttachmentStripAnimated } from './chat.js';
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
// voiceseal 批（批 #508）：语音封存 flag（features.voice，默认封存）——
// 听写入口与全双工拖动入口在封存态一并闸死（见 onVoiceToggle/enterVoiceModeNow）。
import { voiceEnabled, voiceOrbEnabled } from './featureFlags.js';
import { showToast } from './modal.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';
// qattachvault 批（2026-10-21）：消息队列附件**载荷库**（IndexedDB）——入队写字节、
// 刷新后水合灌回、派发闸拒绝空心帧（失败可见可重传）。叶子模块（queueVault → branding）。
import {
  available as vaultAvailable, queueVaultKey, put as vaultPut, get as vaultGet,
  del as vaultDel, keys as vaultKeys,
} from './queueVault.js';
// device-provenance 批（2026-10-23 作者令）：来源设备声明随用户消息帧上行
// （`device` 键；网关对照 NebLink 花名册出已登记/未登记标记，只进模型面）。
import { deviceStamp } from './deviceIdentity.js';

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
// 置位点 = 本文件五个**真人派发**点，与既有 `state.turnExpecting[sid] = true`
// **同点同条件**：① `send()` skill 支 ② `send()` ask 支 ③ `send()` 普通支
// ④ `drainMessageQueue`（排队消息真派发台）⑤ `dispatchOfflineItem`
// （f1-send-queue：断线入队消息的重连补发真派发台）。
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
// attach-split 批（2026-10-24 现场事故修复：22 图 + 文字一条 WS 帧 > 服务端旧 10MB
// 上限 ⇒ 整帧（含文字）被静默丢弃，前端已渲乐观气泡 = 假成功）：附件与文字投递解耦。
// 单帧估算超阈 ⇒ 拆「文字必达首帧 + 附件分批帧」。阈值 8MB 刻意低于服务端**旧** 10MB
// 上限 ⇒ 拆分后的每一帧对未升级服务端同样安全；服务端新上限已提 64MB（双保险）。
const WS_SPLIT_MAX_BYTES = 8 * 1024 * 1024;

/** 单条线上附件的帧字节估算（base64 data 占绝对大头，+256 记 JSON 包装开销）。 */
function wireAttBytes(a) {
  return (a && typeof a.data === 'string' ? a.data.length : 0)
    + (a && typeof a.name === 'string' ? a.name.length : 0) + 256;
}

/**
 * 用户消息帧真派发台 —— 直发（send 普通支）/ busy drain / 立即发送 / 离线补发
 * 四腿共用（四腿帧形原本逐字同构）。低于阈值 = 原样单帧（行为逐字不变）；超阈 ⇒
 *  ① 首帧只载正文 + refs/taskRefs + 主 clientMessageId（**文字必达**，WS 有序先行）；
 *  ② 附件按累计字节切片，每片一帧，clientMessageId = 主 id + '/a' + 序号 —— **独立
 *     幂等键**（AgentActor recentMessageIds 按 id 判重：附件帧与主帧同 id 会被判重
 *     丢弃 ⇒ 附件永远到不了）；帧序 = 单连接有序 ⇒ 文字 turn 必然先于附件 turn 受理；
 *  ③ 附件帧无 content/chatWidth/refs（全部随首帧）；服务端 typeless 腿对「content 空 ∧
 *     attachments 非空」帧的受理是既有行为（准入谓词 `content.nonEmpty || attachments.
 *     nonEmpty` 早覆盖）⇒ 附件帧对未升级服务端同样可达（落 UiMessage.User + 块注入）。
 * 返回首帧 sendWs 结果（false = 帧进 ws.js pendingSends 内存轨，离线补发腿据此留队）。
 */
function dispatchUserMessageFrames({ sessionId, content, clientMessageId, taskRefs, refs, wireAtts, chatWidth }) {
  const baseBytes = (content ? content.length : 0) + (sessionId ? sessionId.length : 0)
    + (clientMessageId ? clientMessageId.length : 0) + 512;
  const totalBytes = baseBytes + (wireAtts || []).reduce((s, a) => s + wireAttBytes(a), 0);
  const buildFrame = (attachments, cmid, withExtras) => ({
    content,
    device: deviceStamp(),
    ...(withExtras && taskRefs && taskRefs.length > 0 ? { taskRefs } : {}),
    ...(withExtras && refs && refs.length > 0 ? { refs } : {}),
    attachments,
    clientMessageId: cmid,
    sessionId,
    chatWidth: chatWidth || 0
  });
  if (totalBytes <= WS_SPLIT_MAX_BYTES || (wireAtts || []).length === 0) {
    return sendWs(buildFrame(wireAtts || [], clientMessageId, true));
  }
  console.info('[send] frame over split threshold — text first, attachments chunked:', {
    sessionId, estimatedBytes: totalBytes, attachments: wireAtts.length
  });
  // ① 文字必达首帧（附件显式空数组 —— 与单帧形态同键；服务端按 content 非空受理）。
  //    正文为空（纯附件消息）时不发空心首帧 —— 网关准入谓词会把它当空帧丢弃
  //    （fwdguard 批钉死过这个静默面）：此时主幂等键让给第一片附件帧。
  let first = true;
  if (content) first = sendWs(buildFrame([], clientMessageId, true));
  // ② 附件分批帧：content 缺省（undefined 键不落 JSON），幂等键 = 有首帧时
  //    主 id + '/a' + 序号（独立键，躲 AgentActor recentMessageIds 判重），无首帧时
  //    第一片用主 id、其后 '/a' + 序号。
  let idx = 0;
  let chunk = [];
  let chunkBytes = 0;
  const flush = () => {
    if (chunk.length === 0) return;
    idx += 1;
    const cmid = (content || idx > 1) ? clientMessageId + '/a' + idx : clientMessageId;
    sendWs({
      device: deviceStamp(),
      attachments: chunk,
      clientMessageId: cmid,
      sessionId
    });
    chunk = [];
    chunkBytes = 0;
  };
  for (const a of wireAtts) {
    const ab = wireAttBytes(a);
    if (chunk.length > 0 && chunkBytes + ab > WS_SPLIT_MAX_BYTES) flush();
    chunk.push(a);
    chunkBytes += ab;
  }
  flush();
  return first;
}


// resend-dedup 批（修复批 #569）：`resendId` = **同一条逻辑消息**重发时携带的
// 原 clientMessageId（重试/重发腿从 state.lastUserSendId[sid] 取）。语义分叉：
//   · 全新发送（resendId 缺省）⇒ 新 id + 乐观气泡 + 落盘 + 入历史（原状）；
//   · 同 id 重发 ⇒ **零新增呈现**（首次尝试的乐观气泡/落盘/历史都已在场，再渲染
//     一份就是「重复气泡」的客户端半边）——只把帧再写一次线。服务端 recentMessageIds
//     命中该 id ⇒ 丢弃并回 `messageDuplicate` 回执（已送达场景 = 无第二次 turn、
//     无第二个气泡）；没见过 ⇒ 正常受理（真未送达场景 = 补送不丢）。
export function send(resendId) {
  // 同 id 重发：输入框常是空的（历史末条重放），空文本守卫要放行。
  const isResend = typeof resendId === 'string' && resendId.length > 0;
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
  // 同 id 重发：重放 state.inputHistory 末条（resendLastInput 会先把值塞回输入框；
  // 直接从登记表兜底取文本，输入框被并行路径清空也不落空）。
  const resendText = !text && isResend ? (state.inputHistory[state.inputHistory.length - 1] || '') : text;
  const effText = isResend ? resendText : text;
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
    // resend-dedup 批：skill 帧带客户端唯一 id（wire 形态与普通帧同键；服务端
    // 本批去重收口在 UserInput 腿，skill 腿先落 id 备二期收口）。
    const skillId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    if (v.sessionId) state.lastUserSendId[v.sessionId] = skillId;
    sendWs({ type: 'skill', skillName, input: text, clientMessageId: skillId, sessionId: v.sessionId });
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
  // qattachvault 派发闸（直发腿）：撤回回填的「载荷已失」附件（stripped/存量 image
  // 骨架且无 data）不允许再出空心帧（= 服务端 path:null 形态）。就地剔除 + 可判读
  // 提示 —— 撤回腿的 toast 已说过一次；这里拦的是「不理会提示直接重发」的残余面。
  // 禁静默：剔除可见。判据与 lostPayloadAttsOf 同律（image 结构兜底同款）。
  {
    const hollow = (v.pendingAttachments || []).filter(a => a && a.type !== 'taskRef' && a.type !== 'ref'
      && !(typeof a.data === 'string' && a.data.length > 0)
      && (a.stripped === true || (a.type === 'image' && !a.hash)));
    if (hollow.length > 0) {
      v.pendingAttachments = v.pendingAttachments.filter(a => !hollow.includes(a));
      renderAttachmentPreview({ attPreviewEl: v.dom.attPreview, attachments: v.pendingAttachments });
      showAttError(t('messages.queueAttachUnrecoverable', { n: hollow.length }));
      console.warn('[send] dropped ' + hollow.length + ' stripped attachment(s) with no recoverable payload');
    }
  }
  // Empty input — just ignore (同 id 重发放行：文本走登记表兜底)
  if (!effText && v.pendingAttachments.length === 0) {
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
  // 同 id 重发不入本地队（入队会在 drain 时另派一帧；重发的幂等性靠同 id + 服务端
  // 去重，不靠队列）。
  if (isBusy && !isResend && !state.frozenSessions.has(v.sessionId)) {
    // 输入消息排队回退批（2026-10-23 作者令「我们的输入消息排队功能采用我们的
    // 之前的。大概两天以前的设计」）：busy 排队腿回归 10-21 形态——消息只入队，
    // 呈现面 = 输入框上方的排队条行（chatQueue.js 行级明细：文本/附件/立即发送/
    // 撤回/移除/拖拽排序），会话流内**零乐观气泡**（10-22 并泡诊断批的「入队即
    // 上屏气泡」随本批退役；当前回复完成后 drain 腿逐条补渲气泡）。离线腿
    // （WS 未开）的「待送达」乐观气泡不受本批影响，照旧。
    queueMessage(v, text, v.pendingAttachments);
    input.value = '';
    input.style.height = 'auto';
    v.pendingAttachments = [];
    // uifix 批 2026-10-22：发送族清空 = 渐隐退场动画（chat.js 清空动画单点；
    // 旧 innerHTML='' 瞬时蒸发退役）。随后的 renderQuoteStrip（同空数组）在
    // renderAttachmentPreview 内命中「清空动画在播 + 空数据」闸，不打断动画。
    clearAttachmentStripAnimated({ attPreviewEl: v.dom.attPreview });
    renderQuoteStrip({ attPreviewEl: v.dom.attPreview, attachments: v.pendingAttachments }); // quote-message 一期：排队后引用 chip 随 pending 清空
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    // f1-send-queue 批（2026-10-19 · mobile-sync 审计 S4 = 头号丢面）：WS 未开
    // 不再静默早退（修前 = 消息不入队、不上屏、零提示直接消失）。改走既有客户端
    // 消息队列（persistQueue 持久化轨，刷新/重启浏览器不丢），乐观气泡上屏为
    // 「待送达」态；重连后 flushOfflineQueue 按入队时钉死的 clientMessageId 自动
    // 补发（#569 同 id 幂等轨：服务端见过 ⇒ messageDuplicate 丢弃，没见过 ⇒ 正常
    // 受理 ⇒ 恰一次，无双发）。
    // `/ask <q>` 不入队：ask 帧型在队列/drain 无承载（type:'ask' 帧），保持原早退
    // （本批残余面，证据档申报）。
    if (text.startsWith('/ask ')) {
      console.warn('[send] ws not open — /ask not queued:', { readyState: state.ws?.readyState });
      return;
    }
    enqueueOfflineMessage(v, text);
    return;
  }
  v.isSending = true;
  // Mark this session as expecting a turn (prevents stray thinking bubbles after done)
  if (v.sessionId) { state.turnExpecting[v.sessionId] = true; markRealUserTurn(v.sessionId); }
  // Intercept /ask <question> before normal slash handling
  if (text.startsWith('/ask ')) {
    const question = text.slice(5).trim();
    if (question) {
      const askId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
      sendWs({ type: 'ask', question, clientMessageId: askId, sessionId: v.sessionId });
      if (v.sessionId) state.lastUserSendId[v.sessionId] = askId;
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
  // resend-dedup 批：同 id 重发复用原 id（幂等键 = 逻辑消息，不是发送动作）；
  // 呈现/落盘/历史三件套全部跳过 —— 首次尝试已留痕，重发再留一份就是重复气泡。
  const clientMessageId = isResend ? resendId : Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const frameText = isResend ? effText : text;
  if (isResend) {
    console.info('[send] resend same id:', { sessionId: v.sessionId, clientMessageId });
  } else {
    renderUserBubble(text, v.pendingAttachments, undefined, { msgId: clientMessageId });
  }
  if (!isResend) {
    saveMsg({type:'user', text, attachments: (v.pendingAttachments||[]).map(a => a.type === 'taskRef'
      ? { type: a.type, name: a.subject, taskId: a.taskId, sessionId: a.sessionId, subject: a.subject }
      : a.type === 'ref'
        ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) }
        : { type: a.type, name: a.name, preview: a.preview })});
  }
  // Save to input history
  // (/clear 特判已随命令摘除退役 —— /clear 现在就是普通文本消息，照常入历史)
  // 同 id 重发不入历史（首次尝试已入；再入会出现上下键重影）。
  if (frameText && !isResend) {
    state.inputHistory.push(frameText);
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
    // resend-dedup 批：帧 content 用 frameText（同 id 重发时 = 登记表兜底文本，
    // 与首次尝试逐字一致）；登记 lastUserSendId = 重发腿的同 id 取用点。
    if (v.sessionId) state.lastUserSendId[v.sessionId] = clientMessageId;
    // attach-split 批：单帧超阈 ⇒ 拆「文字必达首帧 + 附件分批帧」（helper 内）。
    dispatchUserMessageFrames({
      sessionId: v.sessionId,
      content: frameText,
      clientMessageId,
      taskRefs,
      refs,
      wireAtts: (v.pendingAttachments || [])
        .filter(a => a.type !== 'taskRef' && a.type !== 'ref')
        .map(a => ({ mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0 })),
      chatWidth: v.dom.chat?.clientWidth || 0
    });
  } catch (e) {
    console.error('WebSocket send failed:', e);
  }
  input.value = '';
  input.style.height = 'auto';
  v.pendingAttachments = [];
  // uifix 批 2026-10-22：发送消费 = 渐隐退场动画（同排队腿；renderQuoteStrip 的
  // 空数据重渲染在 renderAttachmentPreview 内命中「动画在播」闸不打断）。
  clearAttachmentStripAnimated({ attPreviewEl: v.dom.attPreview });
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
    // busy-stuck-fix：乐观置位 = 草签——武装 turn-start 确认网（引擎 sessionBusy
    // {busy:true}/终态帧/本会话流帧任一到场即撤；到点无任何 turn 事实 ⇒ 判幻影
    // 收 busy，#569 判重丢弃帧不再永久卡 busy）。
    state.armTurnConfirm?.(v.sessionId);
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
// f1-send-queue：队列容量 100（与 ws.js PENDING_SEND_CAP 同律 = 「cap100 那套」）——
// 有界防长时间离线/繁忙导致内存与 localStorage 无界增长；溢出丢最旧 + 可见告警。
const QUEUE_CAP = 100;

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
        // resend-dedup 批：幂等键随队列项跨刷新存活（drain 腿同 id 复用的前提）。
        clientMessageId: it.clientMessageId || null,
        // 并泡诊断批：入队时刻随项跨刷新存活（drain 补渲腿的气泡时间戳真值源）。
        ts: it.ts || null,
        // qattachvault 批：载荷丢失的显式失败态随队列跨刷新存活（刷新后失败面不丢）。
        failed: it.failed === true,
        failCode: it.failCode || null,
        // f1-send-queue：offline 标记随队列跨刷新存活（重连补发的拣选面）。
        // rendered 刻意**不落盘**——刷新后乐观气泡不在场，恢复项按未渲染走
        // （派发时活跃视图补渲），否则刷新后永远等不到翻面、气泡也永远不会出现。
        offline: it.offline === true,
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
    // qattachvault：骨架落位后立即水合载荷库（字节灌回内存项）；boot 时 WS 尚未连接，
    // 派发闸（flushOfflineQueue 等）一律 `afterQueueHydration` 挂在水合之后 ⇒ 无竞态。
    void hydrateQueueFromVault();
    // conn-liveness（对话层活性批 2026-10-22）：刷新恢复的滞留项立即进横幅计数。
    state.updateConnBanner?.();
  } catch (e) { /* corrupt data — ignore */ }
}

// ── qattachvault：队列附件载荷库（IndexedDB）——字节跨刷新存活 + 空心帧派发闸 ──────
// 缺陷链（file:line）：选图（input/attachments.js addFileAttachment ⇒ {type:'image',
// data,preview}）→ WS 未开 enqueueOfflineMessage / LLM 忙 queueMessage（内存全量）
// → persistQueue 把 image 剥成 {type,name,mimeType,hash:'',size:0,stripped:true}
// （data/preview 不落任何介质）→ 刷新 restoreQueue 骨架**替换**内存态 ⇒ 字节永久丢
// → 三条派发腿（dispatchOfflineItem / sendImmediate / drainMessageQueue）帧映射
// `data: a.data` = undefined ⇒ JSON 丢键 ⇒ 服务端 WebSocketRoutes.scala:1621
// （image 落 uploads 需 data.nonEmpty）不成立 ⇒ 零字节落盘 ⇒ ui.json 落 path:null
// ⇒ 消息已送达、图片从未落盘 ⇒ 接收面永远「媒体加载失败」。
// 修复两半：
//   ① 字节持久化：入队即把载荷写载荷库；刷新后水合灌回 ⇒ 派发帧带全量 data ⇒
//      服务端落 uploads ⇒ path 真值（「消息可见 ⇒ 文件可落盘」）；
//   ② 派发闸：水合后仍缺载荷的项（库被清/写失败/IDB 不可用 = 真丢）⇒ **拒绝出线**，
//      标 failed（队列条失败样式 + 气泡小标翻红 ! + toast 可判读）；后续项不被楔死，
//      用户撤回重编重新添加附件 = 可重传。🔴 禁静默、禁发空心帧（path:null）。

/** 项内「载荷已失」附件（权威判据 = stripped 标记 + 水合后仍无 data；B+C 批口径）。
 *  结构兜底（只对 image）：stripped 标记上线**之前**持久化的存量骨架没有标记，
 *  而 image 附件的唯一生产者（input/attachments.js addFileAttachment）恒带 data、
 *  恒不带 hash ⇒ 队列项里的 image 无 data = 必然是丢字节的骨架（大文件 text 腿
 *  靠 hash 服务端解析，不在兜底内，禁误伤）。 */
function lostPayloadAttsOf(item) {
  return (item.attachments || []).filter(a => a && a.type !== 'taskRef' && a.type !== 'ref'
    && !(typeof a.data === 'string' && a.data.length > 0)
    && (a.stripped === true || (a.type === 'image' && !a.hash)));
}

/** 入队点：把项内真载荷写进载荷库（fire-and-forget；写失败 ⇒ 跨刷新水合不中 ⇒
 *  派发闸兜底显式失败，不静默）。只写 `data`（preview = data 的 dataURL 包装，
 *  水合时按 mimeType 重建，不双写）。 */
function vaultItemPayloads(sid, item) {
  (item.attachments || []).forEach((a, idx) => {
    if (a && typeof a.data === 'string' && a.data.length > 0
        && a.type !== 'taskRef' && a.type !== 'ref') {
      void vaultPut(queueVaultKey(sid, item.id, idx), a.data);
    }
  });
}

/** 项离队（派发成功/移除/撤回/溢出）⇒ 载荷库对应键清账（fire-and-forget）。 */
function purgeVaultItem(sid, item) {
  if (!item) return;
  const n = Array.isArray(item.attachments) ? item.attachments.length : 0;
  for (let idx = 0; idx < n; idx += 1) void vaultDel(queueVaultKey(sid, item.id, idx));
}

/** @type {Promise<void>|null} 水合链（restoreQueue 触发首趟；派发闸与测试可 await
 *  最新一趟 —— 每次调用都追加一趟并串行化，页内二次刷新等价动作也能拿到新水合）。 */
let queueHydration = null;

/** 刷新后把载荷库字节灌回队列项（stripped ⇒ 命中：data 回补 + image preview 重建 +
 *  摘 stripped；不命中：保留 stripped ⇒ 派发闸拒发）。末尾清孤儿键（deleteSession/
 *  损坏恢复/溢出丢最旧的残余）。多次调用 = 多趟串行（幂等读，返回最新一趟）。 */
export function hydrateQueueFromVault() {
  const run = async () => {
    let touched = false;
    for (const [sid, items] of Object.entries(state.messageQueue)) {
      if (!Array.isArray(items)) continue;
      for (const item of items) {
        const atts = Array.isArray(item.attachments) ? item.attachments : [];
        for (let idx = 0; idx < atts.length; idx += 1) {
          const a = atts[idx];
          if (!a || a.stripped !== true) continue;
          if (typeof a.data === 'string' && a.data.length > 0) { delete a.stripped; continue; }
          let bytes = null;
          try { bytes = await vaultGet(queueVaultKey(sid, item.id, idx)); } catch { bytes = null; }
          if (typeof bytes === 'string' && bytes.length > 0) {
            a.data = bytes;
            delete a.stripped;
            if (a.type === 'image' && !a.preview) {
              a.preview = 'data:' + (a.mimeType || 'image/jpeg') + ';base64,' + bytes;
            }
            touched = true;
          }
          // 不命中 ⇒ stripped 保留：派发闸据此显式失败（禁静默、禁空心帧）。
        }
      }
    }
    if (touched) {
      for (const sid of Object.keys(state.messageQueue)) refreshQueue(sid);
    }
    // 孤儿清扫：队列里已无对应项的键删除（载荷库与 localStorage 队列是两套账，
    // 队列项被移除/溢出/会话删除时 localStorage 已清，此处收齐 IDB 侧的尾巴）。
    if (vaultAvailable()) {
      try {
        const valid = new Set();
        for (const [sid, items] of Object.entries(state.messageQueue)) {
          for (const item of (items || [])) {
            const n = Array.isArray(item.attachments) ? item.attachments.length : 0;
            for (let idx = 0; idx < n; idx += 1) valid.add(queueVaultKey(sid, item.id, idx));
          }
        }
        const all = await vaultKeys();
        await Promise.all(all.filter(k => !valid.has(k)).map(k => vaultDel(k)));
      } catch { /* 清扫失败不影响水合结果 */ }
    }
  };
  queueHydration = (queueHydration || Promise.resolve()).then(run, run);
  return queueHydration;
}

/** 派发闸的统一入口：动作体挂在水合完成之后（boot 竞态面 = 首连 flush 先于水合）。 */
function afterQueueHydration(fn) {
  (queueHydration || Promise.resolve()).then(fn, fn);
}

/** 载荷丢失项的**显式失败态**（qattachvault ②）：队列条失败样式 + 在场气泡小标翻
 *  红 ! + 可判读 toast；项留队（撤回/移除入口 = 可重传面），failed 随 persistQueue
 *  落盘 ⇒ 跨刷新失败面不丢。后续派发腿按 `!it.failed` 跳过（不楔死队列）。 */
function markQueueItemPayloadLost(sid, item) {
  if (!item || item.failed) return;
  item.failed = true;
  item.failCode = 'payload_lost';
  persistQueue();
  refreshQueue(sid); // 原始键（Object.entries 形态，与 dispatch 腿 refreshQueue 同律）
  // 气泡腿：在场 ⇒ 待送达小标原地改型为失败标（!）；不在场（刷新后）⇒ 队列条
  // 失败态即唯一失败面（materializeOfflineBubbles 只登记已派发项 ⇒ 不会补渲假送达）。
  if (item.clientMessageId) {
    const mapped = sid === 'null' ? null : sid;
    const view = (mapped && findViewBySessionId(mapped)) || activeView;
    const row = view?.dom?.chat?.querySelector(`.row.user[data-msg-id="${item.clientMessageId}"]`);
    const mark = row && row.querySelector('.msg-pending-mark');
    if (mark) {
      mark.classList.remove('msg-pending-mark');
      mark.classList.add('msg-failed-mark');
      mark.title = t('input.queueAttachLost');
      mark.setAttribute('aria-label', t('input.queueAttachLost'));
      mark.textContent = '!';
    }
  }
  if (!item.failNotified) {
    item.failNotified = true; // 内存态即可：跨刷新后气泡已不在场，toast 随失败标一次性给出
    try { showToast(t('input.queueAttachLost'), 'error'); } catch { /* toast 面失败不阻断 */ }
  }
}

/** Helper: re-render the queue bar for a session with standard handlers.
 *  （输入消息排队回退批 2026-10-23：行级明细与行按钮随 10-21 形态回归——
 *  立即发送/撤回/移除走行按钮 handler，气泡右键菜单队列动作已退役。） */
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

function queueMessage(view, text, attachments, skillName, mode, extra) {
  const sid = view.sessionId;
  // resend-dedup 批：排队时就把幂等键钉在队列项上（不是 drain 时现生成）——
  // 这条逻辑消息此后无论 drain 几次、跨几次刷新，线上帧都带同一个 id；
  // 排队项的任何重复派发都会被服务端按 id 去重。
  // f1-send-queue：`extra` = 离线腿的标记注入面（{offline, rendered}），入队即钉死。
  const item = {
    id: ++queueCounter,
    text,
    attachments: attachments.map(a => ({ ...a })),
    skillName,
    mode,
    clientMessageId: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
    // 并泡诊断批：入队时刻随项钉死 —— drain/补渲腿的气泡时间戳用它（修前
    // drain 渲染取 Date.now() ⇒ 排队项的呈现时刻 ≠ 发送时刻，同一批 drain
    // 的气泡还被标成同一时刻）。
    ts: Date.now(),
    ...(extra || {})
  };
  if (!state.messageQueue[sid]) state.messageQueue[sid] = [];
  state.messageQueue[sid].push(item);
  // f1-send-queue：cap100 溢出丢最旧（与 ws.js pendingSends 同律）+ **可见告警**
  // （审计 F1：溢出不得静默）。丢掉的若是已上屏的离线乐观气泡，连气泡一并收掉——
  // 消息已不在场，UI 不得继续谎称「待送达」。
  while (state.messageQueue[sid].length > QUEUE_CAP) {
    const dropped = state.messageQueue[sid].shift();
    console.warn('[input] queue overflow (cap ' + QUEUE_CAP + ') — dropped oldest item #' + (dropped && dropped.id));
    showToast(t('input.queueOverflow'), 'error');
    if (dropped && dropped.rendered) removeOfflineBubble(sid, dropped.clientMessageId);
    if (dropped) purgeVaultItem(sid, dropped); // qattachvault：被丢项的载荷一并清账
  }
  refreshQueue(sid);
  persistQueue();
  // qattachvault：入队即落字节（载荷库 IndexedDB）——「消息可见 ⇒ 文件可落盘」的
  // 上半句；跨刷新后由 hydrateQueueFromVault 灌回，水合不中由派发闸显式失败兜底。
  vaultItemPayloads(sid, item);
  // f1-send-queue：返回项 = 调用方取入队钉死的 clientMessageId（离线腿挂气泡用）。
  return item;
}

// ---------- f1-send-queue：断线入队（「待送达」态）与重连自动补发 ----------
// 审计 S4（mobile-sync 2026-10-19）：WS 未开时按 Enter 发纯文本 = 静默丢（最大丢面）。
// 本簇语义 = 微信式「待送达」：
//   · 入队 = 既有 queueMessage 轨（offline:true 标记 = 补发拣选面；persistQueue 持久化
//     ⇒ 刷新/重启浏览器不丢）；busy 排队项**不带** offline 标记 ⇒ 两轨不混（busy 项仍
//     等 done 终态 drain，补发腿不抢）。
//   · 上屏 = 乐观气泡照常渲染，footer 带时钟小标（msg-pending-mark）；行 data-msg-id =
//     入队钉死的 clientMessageId ⇒ 翻面/去重/撤回收泡的寻址面。
//   · 历史/落盘不在入队点做（drain/flush 派发点做一次，与 busy 排队腿同律——入队点
//     做会在派发点重复）。
//   · 补发 = onReconnect（含首连）flushOfflineQueue 按 pinned id 派发；sendWs 写出
//     成功 ⇒ 出队 + 登记 offlineDeliveredPendingUI；返 false（帧转 ws.js pendingSends
//     内存轨）⇒ 项留队、小标不翻，下次 open 同 id 再补——双发由服务端判重兜（#569）。
//   · 翻面/补渲的单点 = materializeOfflineBubbles：重连 historyPage 初载会整替 DOM
//     （审计 S6），乐观气泡可能被抹 ⇒ 登记项在 historyPage 后/2s 兜底统一落实
//     「行在场且无小标」；历史页已含同文用户消息（服务端先落盘的竞态）⇒ 不补渲防重复。

/** WS 未开时的离线入队腿（send() 普通支 :237 族的唯一调用点）。 */
function enqueueOfflineMessage(v, text) {
  const item = queueMessage(v, text, v.pendingAttachments, null, null, { offline: true, rendered: true });
  renderUserBubble(text, v.pendingAttachments, undefined, { msgId: item.clientMessageId, pending: true });
  // conn-liveness（对话层活性批 2026-10-22）：滞留计数变化 ⇒ 顶栏横幅立即重读。
  state.updateConnBanner?.();
  const input = v.dom.input;
  input.value = '';
  input.style.height = 'auto';
  v.pendingAttachments = [];
  // uifix 批 2026-10-22：离线入队消费 = 渐隐退场动画（同发送腿）。
  clearAttachmentStripAnimated({ attPreviewEl: v.dom.attPreview });
  renderQuoteStrip({ attPreviewEl: v.dom.attPreview, attachments: v.pendingAttachments }); // 引用 chip 随 pending 清空（同发送/排队腿）
  saveInputDraft(v.sessionId);
  setTimeout(() => { v.isSending = false; }, 300);
}

/** 摘掉待送达小标（翻已送达态）。同页命内乐观气泡的寻址面 = 行
 *  data-msg-id。.msg-pending-mark 只有离线腿时钟标单形态（busy 排队腿回退批
 *  2026-10-23 后零气泡零标——呈现面只在排队条行）——离线腿乐观气泡经本单点
 *  翻面（补发后调用，标缺席时 no-op 无害）。
 *  返回行是否在场（不在场 = 已被 historyPage 整替抹掉，调用方走补渲台）。 */
function flipOfflineMark(sid, msgId) {
  if (!msgId) return false;
  const view = (sid && findViewBySessionId(sid)) || activeView;
  const row = view?.dom?.chat?.querySelector(`.row.user[data-msg-id="${msgId}"]`);
  if (!row) return false;
  row.querySelector('.msg-pending-mark')?.remove();
  return true;
}

/** 收掉离线乐观气泡（撤回/移除/溢出丢弃时——消息不在场，UI 不得谎称待送达）。
 *  选择器不带 .user 前缀：renderUserBubble 的随行媒体卡挂同一 msgId，一起收。 */
function removeOfflineBubble(sid, msgId) {
  if (!msgId) return;
  const view = (sid && findViewBySessionId(sid)) || activeView;
  view?.dom?.chat?.querySelectorAll(`[data-msg-id="${msgId}"]`).forEach(el => {
    (el.closest('.row') || el).remove();
  });
}

// sid → [{ text, attachments, msgId }]：已派发（帧已写出）但「气泡最终态」未落实的
// 离线项。落实 = materializeOfflineBubbles（historyPage 后 + 2s 兜底双触发）。
// 项常驻至某次 historyPage 落实后清除；从不清仓的残余（本会话再无 historyPage）
// 每页命数条封顶，可忽略。
const offlineDeliveredPendingUI = new Map();

function registerOfflineDelivered(sid, item, msgId) {
  const key = sid || '';
  if (!offlineDeliveredPendingUI.has(key)) offlineDeliveredPendingUI.set(key, []);
  offlineDeliveredPendingUI.get(key).push({ text: item.text, attachments: item.attachments, msgId });
  // 兜底：无 historyPage 可循（会话未重载/帧丢失）时 2s 后自行落实。
  setTimeout(() => materializeOfflineBubbles(key, undefined), 2000);
}

/**
 * 落实已派发离线项的气泡终态：行在 ⇒ 摘小标；行不在（被 historyPage 抹掉/跨刷新
 * 从未渲染）⇒ 补渲（无小标 = 已送达态）。historyMsgs 在场时先查重——服务端已把
 * 本条落盘进历史页（补发帧先于 getHistory 被处理的竞态）⇒ 历史行就是它的呈现，
 * 再补一份就是重复气泡。
 */
function materializeOfflineBubbles(sid, historyMsgs) {
  const list = offlineDeliveredPendingUI.get(sid);
  if (!list || list.length === 0) return;
  const view = sid ? findViewBySessionId(sid) : activeView;
  // 非活跃视图不补渲（气泡由落盘缓存/下次切会话的历史真值源承担）；登记保留。
  if (!view || !activeView) return;
  if (sid && activeView.sessionId !== sid) return;
  for (const entry of list) {
    const row = view.dom.chat?.querySelector(`.row.user[data-msg-id="${entry.msgId}"]`);
    if (row) { row.querySelector('.msg-pending-mark')?.remove(); continue; }
    const inHistory = Array.isArray(historyMsgs)
      && historyMsgs.some(m => m && m.type === 'user' && m.text === entry.text);
    if (inHistory) continue;
    renderUserBubble(entry.text, entry.attachments || [], undefined, { msgId: entry.msgId });
  }
  // historyPage 触发 = 权威落实点（整替已完成，此后直到下次重连不再被抹）⇒ 清仓；
  // 兜底定时器触发（historyMsgs undefined）不清仓——后续真来的 historyPage 还要再核。
  if (historyMsgs !== undefined) offlineDeliveredPendingUI.delete(sid);
}

/** 重连（含首连）后补发全部 offline 标记项（ws.js onopen 回调注册点在本簇尾）。
 *  qattachvault：动作体挂在水合完成之后（boot 首连 flush 可能先于水合 ⇒ 抢跑会把
 *  尚未灌回字节的项误判成「载荷丢失」）；failed 项跳过（显式失败态，不楔死队列）。 */
export function flushOfflineQueue() {
  afterQueueHydration(() => {
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
    for (const [sid, items] of Object.entries(state.messageQueue)) {
      if (!Array.isArray(items) || items.length === 0) continue;
      // 每轮重新 find：dispatchOfflineItem 成功即出队，find 自然前进；失败（ws 中途
      // 又断）⇒ 留队并整体停轮（保序，剩余项留待下次 open）。failed（载荷丢失，终态）
      // 同律跳过 —— 已有显式失败面，再派发只会重复拒绝。
      let item;
      while ((item = items.find(it => it && it.offline && !it.failed))) {
        if (lostPayloadAttsOf(item).length > 0) { markQueueItemPayloadLost(sid, item); continue; }
        if (!dispatchOfflineItem(sid === 'null' ? null : sid, item)) return;
      }
    }
  });
}

/** 单条离线项的真派发台（真人派发点 ⑤）。帧形与 drainMessageQueue 普通支逐字同构。 */
function dispatchOfflineItem(sid, item) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return false;
  const view = sid ? findViewBySessionId(sid) : null;
  // resend-dedup 批：幂等键 = 入队时钉死的 clientMessageId（存量恢复项现场补一枚）。
  const clientMessageId = item.clientMessageId
    || (item.clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6));
  const taskRefs = (item.attachments || []).filter(a => a.type === 'taskRef');
  const refs = (item.attachments || []).filter(a => a.type === 'ref');
  notifyFriendRefsSent(refs);
  const sent = dispatchUserMessageFrames({
    sessionId: sid,
    content: item.text,
    clientMessageId,
    taskRefs: taskRefs.length > 0 ? taskRefs.map(a => ({ taskId: a.taskId, sessionId: a.sessionId || sid })) : taskRefs,
    refs: refs.length > 0 ? refs.map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) })) : refs,
    wireAtts: (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref').map(a => ({
      mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
    })),
    chatWidth: view?.dom?.chat?.clientWidth || 0
  });
  if (!sent) return false; // 帧在 ws.js pendingSends 内存轨；项留队，下次 open 同 id 再补
  // conn-liveness（对话层活性批 2026-10-22）：项已派发 ⇒ 滞留计数变化 ⇒ 横幅重读。
  state.updateConnBanner?.();
  // 写出成功 ⇒ 出队 + 持久化（先出队 = 「移出即已派发」口径，同 sendImmediate）
  const q = state.messageQueue[sid];
  if (q) { const idx = q.indexOf(item); if (idx >= 0) q.splice(idx, 1); }
  refreshQueue(sid);
  persistQueue();
  purgeVaultItem(sid, item); // qattachvault：载荷已随帧出线 ⇒ 载荷库清账
  // 呈现面：行还在（同页命、未被整替）⇒ 原地摘小标翻已送达态；登记进
  // offlineDeliveredPendingUI 由 materializeOfflineBubbles 兜底最终态（S6 整替窗口）。
  flipOfflineMark(sid, clientMessageId);
  registerOfflineDelivered(sid, item, clientMessageId);
  // 落盘 + 入历史（恰好一次 = 派发点；入队点刻意不做）
  saveMsg({ type: 'user', text: item.text, attachments: (item.attachments || []).map(a => a.type === 'taskRef'
    ? { type: a.type, name: a.subject, taskId: a.taskId, sessionId: a.sessionId, subject: a.subject }
    : a.type === 'ref'
      ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display }
      : { type: a.type, name: a.name, preview: a.preview }) }, sid);
  if (item.text) {
    state.inputHistory.push(item.text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch (e) {}
  }
  // turn 簿记（同 send()/drainMessageQueue：本次派发 = 新 turn 起点 ⇒ 取代上一 turn
  // 终止戳、武装看门；服务端 busy 中 ⇒ 帧由服务端排队受理，busy 登记本就恒真）。
  if (sid) {
    state.turnExpecting[sid] = true; markRealUserTurn(sid);
    state.lastUserSendId[sid] = clientMessageId;
    delete state.lastTerminalAt[sid];
  }
  setBusy(sid);
  // busy-stuck-fix：补发腿同款草签（同 id 重放被服务端判重丢弃时，无 turn 无终态帧，
  // 靠确认网/判重回执收幻影 busy）。
  state.armTurnConfirm?.(sid);
  state.turnStartTimes[sid] = Date.now();
  armBusyWatchdogFor(sid);
  return true;
}

// 首连/重连收口点：ws.js onopen 在 flushPendingSends 之后、引导帧族之后调
// reconnectCallbacks —— 离线项补发先行，历史重拉（main.js onReconnect）随后。
onReconnect(() => {
  try { flushOfflineQueue(); } catch (e) { console.error('[input] offline flush failed:', e); }
});

// historyPage 后落实离线气泡终态（S6 整替窗口的收口）。input.js 模块先于 main.js
// 求值 ⇒ 本处理器注册在前、同步段先跑；setTimeout(0) 把落实推到 main.js 处理器
// （整替+历史重渲）完成之后。
onMessage('historyPage', (msg) => {
  const sid = msg && msg.sessionId;
  setTimeout(() => {
    try { materializeOfflineBubbles(sid || '', msg && msg.messages); } catch (e) { /* 呈现兜底不炸处理器 */ }
  }, 0);
});

function sendImmediate(sessionId, item) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  // resend-dedup 批（「已派发还重发」判断修正）：立即发送的第一步就把项移出队列
  // ⇒ 项已不在队列 = 已派发过（双击 / 陈旧 handler 重放）⇒ 不再二连发。
  const q0 = state.messageQueue[sessionId];
  if (!q0 || !q0.includes(item)) {
    console.warn('[input] sendImmediate: item already dispatched — skip');
    return;
  }
  // qattachvault 派发闸：载荷丢失（水合不中）⇒ 拒绝出线（空心帧 = path:null 形态，
  // 禁发）。显式失败态就地给出；项留队，撤回重编 = 可重传。
  if (lostPayloadAttsOf(item).length > 0) {
    markQueueItemPayloadLost(sessionId, item);
    return;
  }
  const taskRefs = (item.attachments || []).filter(a => a.type === 'taskRef');
  const refs = (item.attachments || []).filter(a => a.type === 'ref');
  // File/image attachments (base64 data payload) ride the same default
  // user-message frame. Without this branch, an attachment-only queued item
  // fell into the immediateInput branch, whose backend handler reads only
  // `content` — with content === '' the frame hit handleUserText's empty
  // guard and the agent never saw it (silent non-response).
  const fileAtts = (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref');
  // resend-dedup 批：幂等键 = 队列项入队时钉死的 clientMessageId（存量恢复项
  // 现场补一枚）。立即发送是对同一逻辑消息的投递 ⇒ 同 id。
  const itemMsgId = item.clientMessageId
    || (item.clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6));
  state.lastUserSendId[sessionId] = itemMsgId;
  if (item.mode === 'compact') {
    sendWs({ type: 'command', command: 'compact', sessionId, instruction: item.text || undefined });
  } else if (item.skillName) {
    sendWs({ type: 'skill', skillName: item.skillName, input: item.text, clientMessageId: itemMsgId, sessionId });
  } else if (taskRefs.length > 0 || refs.length > 0 || fileAtts.length > 0) {
    // v2 + #303: a return/reference message must ride the default user-message
    // branch — the backend parses taskRefs/refs only there (WebSocketRoutes
    // §6.2); immediateInput goes through handleUserText and would drop them.
    const clientMessageId = itemMsgId;
    notifyFriendRefsSent(refs);
    dispatchUserMessageFrames({
      sessionId,
      content: item.text,
      clientMessageId,
      taskRefs: taskRefs.length > 0 ? taskRefs.map(a => ({ taskId: a.taskId, sessionId: a.sessionId || sessionId })) : taskRefs,
      refs: refs.length > 0 ? refs.map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) })) : refs,
      wireAtts: (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref').map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
      })),
      chatWidth: activeView?.dom?.chat?.clientWidth || 0
    });
  } else {
    // resend-dedup 批：immediateInput 帧带客户端唯一 id（gateway 本批不消费该腿的
    // id——ImmediateInput 命令无此字段；wire 先行，候二期命令形状收口）。
    // device-provenance 批：「立即发送」腿同律随帧声明。
    sendWs({ type: 'immediateInput', content: item.text, device: deviceStamp(), clientMessageId: itemMsgId, sessionId });
  }
  // Render in chat
  // resend-dedup 批：renderUserBubble 落 data-msg-id = 幂等键（与普通发送同键）。
  // f1-send-queue：rendered 项（离线入队的乐观气泡已在场）零新增呈现 —— 摘掉
  // 小标即「已送达」翻面；重渲一份就是重复气泡。
  // 翻面落空（行不在场：刷新后 / historyPage 整替）⇒ 补渲一次已送达态气泡 ——
  // 消息此刻已被受理，界面上不能凭空消失（对话层活性批 2026-10-22 收口）。
  if (item.rendered) {
    if (!flipOfflineMark(sessionId, itemMsgId) && activeView && activeView.sessionId === sessionId) {
      renderUserBubble(item.text, item.attachments);
    }
  } else if (activeView && activeView.sessionId === sessionId) {
    if (item.mode === 'compact') {
      renderSystemBubble(item.text ? t('slash.compactDone') + ' — ' + item.text : t('slash.compactDone'));
    } else if (item.skillName) {
      renderSkillBubble(item.skillName, item.text);
    } else {
      // 排队项补渲：时间戳 = 入队时刻（item.ts），非补渲时刻（排队回退批保留
      // 该数据层真值钉）。
      renderUserBubble(item.text, item.attachments, item.ts, { msgId: itemMsgId });
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
  purgeVaultItem(sessionId, item); // qattachvault：载荷已随帧出线 ⇒ 载荷库清账
}

function removeQueuedItem(sessionId, item) {
  const q = state.messageQueue[sessionId];
  if (!q) return;
  const idx = q.indexOf(item);
  if (idx >= 0) q.splice(idx, 1);
  // f1-send-queue：离线乐观气泡随移除一并收掉（消息已不在场，不留谎称待送达的壳）。
  if (item.rendered) removeOfflineBubble(sessionId, item.clientMessageId);
  refreshQueue(sessionId);
  persistQueue();
  purgeVaultItem(sessionId, item); // qattachvault：项已离队 ⇒ 载荷库清账
}

/** Recall a queued item back to the input box for editing, removing it from the queue. */
function recallQueuedItem(sessionId, item) {
  const q = state.messageQueue[sessionId];
  if (!q) return;
  const idx = q.indexOf(item);
  if (idx >= 0) q.splice(idx, 1);
  // f1-send-queue：离线乐观气泡随撤回一并收掉（文本回输入框重编 = 该条逻辑消息
  // 已撤回；气泡留着会继续显示「待送达」却永远不会补发）。
  if (item.rendered) removeOfflineBubble(sessionId, item.clientMessageId);
  refreshQueue(sessionId);
  persistQueue();
  purgeVaultItem(sessionId, item); // qattachvault：项已离队（回输入框重编）⇒ 载荷库清账

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
 *  DOM operations are skipped, but the WS message is still sent.
 *  qattachvault：动作体挂在水合完成之后（boot 后立刻 done 的竞态面）；failed
 *  （载荷丢失终态）项跳过 —— 不楔死队列，其余项照常 drain。 */
export function drainMessageQueue(sessionId) {
  afterQueueHydration(() => drainMessageQueueNow(sessionId));
}

function drainMessageQueueNow(sessionId) {
  const q = state.messageQueue[sessionId];
  if (!q || q.length === 0) return false;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return false;
  const item = q.find(it => !it.failed);
  if (!item) return false;
  // qattachvault 派发闸：载荷丢失（水合不中）⇒ 拒绝出线（空心帧 = path:null 形态，
  // 禁发）。显式失败态就地给出；项留队，撤回重编 = 可重传。
  if (lostPayloadAttsOf(item).length > 0) {
    markQueueItemPayloadLost(sessionId, item);
    return false;
  }
  const view = findViewBySessionId(sessionId);

  // Remove from queue
  const itemIdx = q.indexOf(item);
  if (itemIdx >= 0) q.splice(itemIdx, 1);
  refreshQueue(sessionId);
  persistQueue();
  purgeVaultItem(sessionId, item); // qattachvault：载荷已随帧出线 ⇒ 载荷库清账

  // Render in chat (only if this session is the active view)
  // f1-send-queue：rendered 项（离线入队的乐观气泡已在场）零新增呈现 —— 摘掉
  // 小标即可；重渲一份就是重复气泡（与 sendImmediate 同律）。
  // 翻面落空（行不在场：刷新后 / historyPage 整替）⇒ 补渲已送达态气泡（同
  // sendImmediate 的收口——消息已受理，不能凭空消失）。
  if (item.rendered) {
    if (!flipOfflineMark(sessionId, item.clientMessageId) && view && activeView && activeView.sessionId === sessionId) {
      renderUserBubble(item.text, item.attachments);
    }
  } else if (view && activeView && activeView.sessionId === sessionId) {
    if (item.mode === 'compact') {
      renderSystemBubble(item.text ? t('slash.compactDone') + ' — ' + item.text : t('slash.compactDone'));
    } else if (item.skillName) {
      renderSkillBubble(item.skillName, item.text);
    } else {
      // 排队项 drain 补渲：时间戳 = 入队时刻（item.ts），非 drain 渲染时刻
      // （排队回退批保留该数据层真值钉）。
      renderUserBubble(item.text, item.attachments, item.ts);
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
    sendWs({ type: 'skill', skillName: item.skillName, input: item.text, clientMessageId: item.clientMessageId, sessionId });
  } else {
    // resend-dedup 批：幂等键 = 队列项入队时钉死的 clientMessageId（存量恢复项
    // 缺失时现场补一枚，仍在本项生命周期内保持稳定）。重复 drain / 刷新后再
    // drain 的重复派发都在服务端按 id 去重。
    const clientMessageId = item.clientMessageId
      || (item.clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6));
    if (sessionId) state.lastUserSendId[sessionId] = clientMessageId;
    // v2 B15: queued return messages keep their taskRefs when drained.
    // #303: unified refs ride the same default user-message frame (backend
    // parses refs via circe cursor — tolerated for forward-compat).
    const taskRefs = (item.attachments || []).filter(a => a.type === 'taskRef');
    const refs = (item.attachments || []).filter(a => a.type === 'ref');
    notifyFriendRefsSent(refs);
    dispatchUserMessageFrames({
      sessionId,
      content: item.text,
      clientMessageId,
      taskRefs: taskRefs.length > 0 ? taskRefs.map(a => ({ taskId: a.taskId, sessionId: a.sessionId || sessionId })) : taskRefs,
      refs: refs.length > 0 ? refs.map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display, ...(a.content ? { content: a.content } : {}) })) : refs,
      wireAtts: (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref').map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
      })),
      chatWidth: view?.dom?.chat?.clientWidth || 0
    });
  }

  // ①-2: 本次 drain 派发 = 新 turn 起点 ⇒ 取代上一 turn 的终止戳（同 send()）。
  if (sessionId) delete state.lastTerminalAt[sessionId];
  setBusy(sessionId);
  // busy-stuck-fix：drain 派发腿同款草签（服务端 busy 排队闸判重丢弃时，无 turn
  // 无终态帧，靠确认网/判重回执收幻影 busy）。
  state.armTurnConfirm?.(sessionId);
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
    device: deviceStamp(), // device-provenance 批：插件卡注入腿同律随帧声明
    attachments: [],
    clientMessageId,
    sessionId,
    chatWidth: activeView.dom.chat?.clientWidth || 0
  });

  // Mark session as busy
  // ①-2: 本派发同样是新 turn 起点 ⇒ 取代上一 turn 的终止戳（同 send()/drain）。
  if (sessionId) delete state.lastTerminalAt[sessionId];
  setBusy(sessionId);
  // busy-stuck-fix：插件卡注入腿同款草签（同上三条派发腿）。
  state.armTurnConfirm?.(sessionId);
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

  // ── fwdguard-impl（2026-09-17）→ #531 占位形态修正（作者令 2026-10-18 23:36）──
  // 纯引用帧闸的提示**不再设独立提示行**：旧实现插入 .ref-gate-hint div（#input
  // 之前的文档流位），blocked 时在附件 chip 下方另起一行渲染「请附一句话后发送」
  // ——作者裁定提示应落在**原输入框自己的 placeholder 位**（updateInputPlaceholder
  // 的 isRefOnlyFrame 分支），本元素整个移除。（旧注里「结构角色由 44px 让位带
  // 承担」一段已随 uifix 批 2026-10-22 附件条改 in-flow 独立行失效 —— overlay
  // 命中带不复存在，chip 与文字区完全分离，无点击吃面问题。）
  // 闸的视觉态 = 发送键禁用（syncRefOnlyGate）+ placeholder 文案（下方两个 helper）。

  // Auto-resize textarea + 同步纯引用帧闸（文本变化会改变闸判据）。
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    syncRefOnlyGate(view);
    // 输入使闸解除时（blocked→false）占位符立即回到附件/通用文案 ——
    // 闸文案由输入框自身 placeholder 承担（#531，2026-10-18）。
    updateInputPlaceholder();
  });

  // Voice dictation state — declared at the top of the setup scope because
  // the placeholder helper below runs during init (before the dictation
  // section executes) and reads `voiceActive`; a later `let` would be a
  // temporal-dead-zone reference there and crash the whole initInput chain.
  // Push-and-hold the mic button to start; release to stop.
  // Interim text streams directly into the input box — no overlay.
  let voiceActive = false;
  let voiceAnchor = 0;       // position where current voice segment starts
  let voiceInterimLen = 0;   // length of interim text currently displayed
  // Snapshot of input.value at the last voice write / user edit. The diff
  // baseline that lets us re-anchor the live draft slot after user edits
  // (2026-09-02 duplicate-fix; see the input listener below).
  let voiceBaseline = '';

  // 附件/引用条的任何重建都会同步闸态（观察器覆盖面含 chat.js 侧 ref 芯片的移除
  // 按钮与 send() 尾部清空 strip 两条非本文件路径）。popup 每次打开重建 DOM ⇒
  // 旧观察器先断开，避免挂在已脱离文档的节点上。
  if (view.dom.attPreview && typeof MutationObserver !== 'undefined') {
    view.dom.refGateObserver?.disconnect?.();
    const mo = new MutationObserver(() => { syncRefOnlyGate(view); updateInputPlaceholder(); });
    mo.observe(view.dom.attPreview, { childList: true, subtree: true });
    view.dom.refGateObserver = mo;
  }
  syncRefOnlyGate(view);
  updateInputPlaceholder();

  /** Contextual composer placeholder (mobile voice redesign): the pending
   *  attachment count and the mic state replace the generic line —
   *  attachments no longer claim their own row, the placeholder names them.
   *  Count source = the RENDERED strip (childElementCount), NOT the global
   *  pendingAttCount in-flight counter: this runs from the attPreview
   *  MutationObserver, which fires after addFileAttachment has already
   *  decremented the counter back to 0, so the counter can never drive the
   *  has-attachments class at steady state (composer-typing fix 2026-10-18).
   *  uifix 批 2026-10-22：strip 改 in-flow 独立行后 44px padding 补偿随 overlay
   *  退役，本函数仍是 strip 重建的**占位符/类同步单点**（has-attachments 类保留
   *  为状态钩子，CSS 消费面已零）。发送清空动画窗内（chat.js，~160ms）计数含
   *  退场 chip，落定后由本观察器再同步一次 —— 短暂窗口零行为影响。 */
  function updateInputPlaceholder() {
    if (voiceActive) { input.placeholder = t('input.ph.listening'); return; }
    const n = view.dom.attPreview ? view.dom.attPreview.childElementCount : 0;
    input.parentElement?.classList.toggle('has-attachments', n > 0);
    // 纯引用帧（blocked）：发送被闸禁用，「请附一句话后发送」直接落在输入框自己的
    // placeholder 位（#531 作者令 2026-10-18：不新增独立占位行；清空附件/输入附言
    // 后由下方分支恢复原文案）。
    if (isRefOnlyFrame(view)) { input.placeholder = t('input.refOnlyHint'); return; }
    if (n > 0) { input.placeholder = t('input.ph.attachments').replace('%n', String(n)); return; }
    input.placeholder = t('input.placeholder');
  }

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
    state.cancelTurnConfirm?.(sid);
    if (sid && state.sessionBusyTimeouts[sid]) {
      clearTimeout(state.sessionBusyTimeouts[sid]);
      delete state.sessionBusyTimeouts[sid];
    }
    // queue-idle-flush（2026-10-23）：stop = 真实 busy→idle 迁移，排队消息照常触发
    // 投递（终态 `interrupted` 帧到场会再触发一次——同一 pending 槽去重；帧丢失时本
    // 腿兜底，队列不再滞留）。🔴 触发必须挂在 clearBusy **之后**（同一回调内）：
    // clearBusy 走动态 import 异步腿，若触发在同步面先发，定时器 +50ms 可能落在
    // 「busy 尚未清 + 上次派发后有新轮事实」的窗口里，被运行中事实闸当成本轮仍在
    // 跑而拦下（无 re-arm = 触发丢失）——正是实测撞到的滞留形态。派发闸的冷却窗/
    // 运行中事实闸照旧把关。
    import('./chat.js').then(({ clearBusy }) => {
      clearBusy(sid);
      state.scheduleQueueDrain?.(sid);
    });
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
    input.placeholder = t('input.ph.listening');
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
    // voiceseal 批（批 #508）→ 纠正批（#513）：门收窄为气泡开关
    // voiceOrbEnabled()（默认显示）。#508 曾以全双工 flag 连带闸死听写 =
    // 理解过宽；#513 起点击听写（非全双工基础路径）随气泡回归照常可用，
    // 全双工封存不受影响（会话腿仍由 enterVoiceModeNow/voiceMode 闸死）。
    // 气泡显式隐藏（voiceOrb:false）时正常情形不可点，此闸兜住程序化调用。
    if (!voiceOrbEnabled()) return;
    if (e) e.preventDefault();
    // A drag that just entered voice mode must not also toggle dictation
    // (the synthetic click fires after pointerup).
    if (Date.now() < suppressVoiceClickUntil) return;
    setActiveView(view);
    if (voiceActive) stopVoice();
    else startVoice();
  }
  voiceBtn.addEventListener('click', onVoiceToggle);

  // Full-duplex voice entry (voice-mode batch): dragging the voice bubble
  // right (gesture below) is the only way in. Dynamic import keeps
  // input.js ↔ voiceMode.js cycle-free at module-eval time (same pattern as
  // micOrb → voiceEngine).
  function enterVoiceModeNow() {
    // voiceseal 批（批 #508）：封存（默认）时全双工入口闸死 —— 不动态加载
    // voiceMode.js（voiceMode.enterVoiceMode 内还有一道终闸，双保险）。
    if (!voiceEnabled()) return;
    setActiveView(view); // utterances must land in THIS session even if ws.js flips activeView later
    import('./voiceMode.js').then((m) => {
      m.enterVoiceMode({
        // send() operates on the active view's input; the ws-closed path
        // leaves the value uncleared — that's the failure signal.
        sendText: (/** @type {string} */ text) => {
          input.value = text;
          send();
          const ok = input.value !== text;
          input.value = '';
          input.style.height = 'auto';
          return ok;
        },
        // Push-to-talk and voice mode share one mic session.
        ensureDictationIdle: () => { if (voiceActive) stopVoice(); },
        // Replies for THIS session are read aloud; others are ignored.
        getSessionId: () => view.sessionId,
      });
    }).catch(() => {});
  }

  // Drag-in gesture (voice redesign): press the voice bubble and swipe right
  // past the threshold — full-duplex voice opens, and the synthetic click is
  // suppressed so dictation does not also start. The bubble tracks the
  // pointer horizontally while dragging (the wrap transition is disabled by
  // .dragging, so tracking is 1:1 and the snap-back animates on release);
  // the gradient chevrons (.voice-drag-hint, css/voiceMode.css) float above
  // the bubble inside the same wrap, shown on hover and while dragging.
  // 🟡 voiceseal 纠正批（批 #513，作者澄清：「我要去掉的是hover拖动这些
  // 功能。」）：拖动手势 = 全双工唯一入口，**归属面 A**——默认封存态
  // （voiceEnabled() OFF）整个手势面 dead：pointerdown 闸住后 voiceDrag
  // 恒 null ⇒ 跟手 transform / `.dragging` 类 / 拖右开全双工 / 合成 click
  // 抑制全不发生，气泡固定默认位、纯静态可点（hover 箭头另由 voiceMode.css
  // 的 `.voice-static` 姿态规则压死）。代码零删除：features.voice:true 时
  // 手势随面 A 原样回归（#508 封存/恢复机制不变）。
  let voiceDrag = null;
  let suppressVoiceClickUntil = 0;
  const micOrbWrap = voiceBtn?.closest('.mic-orb-wrap') || null;
  // Scope-local placeholder refresh (the shared updateInputPlaceholder lives
  // in the per-view setup scope and is not reachable here).
  // Count source = the rendered strip, same as updateInputPlaceholder — the
  // global pendingAttCount is an in-flight counter (already 0 when this runs),
  // so it could never hold has-attachments open (composer-typing fix).
  const refreshPlaceholder = () => {
    if (voiceActive) { input.placeholder = t('input.ph.listening'); return; }
    const n = view.dom.attPreview ? view.dom.attPreview.childElementCount : 0;
    input.parentElement?.classList.toggle('has-attachments', n > 0);
    // 纯引用帧分支同 updateInputPlaceholder：闸文案落在输入框自身 placeholder
    // （#531，2026-10-18；无独立提示行）。
    if (isRefOnlyFrame(view)) { input.placeholder = t('input.refOnlyHint'); return; }
    input.placeholder = n > 0
      ? t('input.ph.attachments').replace('%n', String(n))
      : t('input.placeholder');
  };
  voiceBtn.addEventListener('pointerdown', (e) => {
    // #513 纠正：拖动手势 = 全双工入口（面 A），默认封存态整个 dead ——
    // 不捕获指针、不进跟手态、不改 placeholder（chevrons 的 hover 显示
    // 面也已由 css 静态姿态压死）。gesture 体零删除，flag ON 原样回归。
    if (!voiceEnabled()) return;
    // Capture the pointer to THIS element: mouse/pen drags leave the 44px
    // button immediately, and without capture the pointermove events go to
    // whatever element is under the cursor — the gesture never fires.
    try { voiceBtn.setPointerCapture(e.pointerId); } catch (_) {}
    voiceDrag = { x: e.clientX, y: e.clientY, fired: false };
    micOrbWrap?.classList.add('dragging');
    input.placeholder = t('input.ph.drag');
  });
  voiceBtn.addEventListener('pointermove', (e) => {
    if (!voiceDrag || voiceDrag.fired) return;
    const dx = e.clientX - voiceDrag.x;
    const dy = Math.abs(e.clientY - voiceDrag.y);
    // Rightward tracking only, with a little overshoot past the fire
    // threshold so the bubble keeps following the finger after the point
    // where voice mode would open.
    if (micOrbWrap) micOrbWrap.style.transform = `translateX(${Math.min(Math.max(dx, 0), 84)}px)`;
    if (dx > 56 && dy < 44) {
      voiceDrag.fired = true;
      suppressVoiceClickUntil = Date.now() + 700;
      if (micOrbWrap) { micOrbWrap.classList.remove('dragging'); micOrbWrap.style.transform = ''; }
      refreshPlaceholder();
      enterVoiceModeNow();
    }
  });
  const endVoiceDrag = () => {
    if (!voiceDrag) return;
    voiceDrag = null;
    if (micOrbWrap) { micOrbWrap.classList.remove('dragging'); micOrbWrap.style.transform = ''; }
    refreshPlaceholder();
  };
  voiceBtn.addEventListener('pointerup', endVoiceDrag);
  voiceBtn.addEventListener('pointercancel', endVoiceDrag);

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
