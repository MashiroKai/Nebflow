// chat.js — Chat rendering module for Stand
// All DOM manipulation for messages, bubbles, tool cards, option boxes, and status.

import state, { AGENT_PALETTE } from './state.js';
import { mainBubbleClass } from './chatUi.js';
// 消息条目统一批 2026-11-18：气泡节点装配基座单点（chatItem.js）。
import { messageBubbleNode } from './chatItem.js';
import { key } from './branding.js';
import { bindImeGuard, commitEnter } from './imeGuard.js';
// voice 块补封批 2026-10-31（作者令「封存所有会话的 voice 块」，漏网面）：TTS
// 输出族（<voice> 块 + VoicePlayer 播报）与全双工同 flag 封存（voiceEnabled，
// 缺席即封存；utils 渲染面剥签，此处把住播放面三入口 prefetch/enqueue/replay）。
import { voiceEnabled } from './featureFlags.js';
// 时制（12h/24h）：偏好、格式化与热区绑定的唯一属主 = timeFormat.js（主对话框
// / 设备对话框 / 好友对话框三面共享同一偏好与同一实现；本模块只消费）。
// formatFooterTime = footer-time 批（2026-10-06）的分层 footer label：
// 今天仍为纯时钟（formatHm），更早按本地日历日加日期层（昨天/星期X/M月D日/跨年）。
import { formatHm, formatFooterTime, toggleTimeFormat, bindTimeToggle } from './timeFormat.js';
import { activeView, setActiveView, findViewBySessionId, chatViews } from './chatView.js';
import { renderMarkdownWithMath, extractOutputInner, escapeHtml, appendTextWithMentions, appendUserTextWithFences, buildToolDetail, buildDelegatePromptHtml, attachToolClick, mountToolCardBody, delegatePromptRaw, foldToolCardMedia, smartScroll, playSpinner, stopSpinner, localizeToolLabel, localizeToolSummary, renderHighlightedContent, highlightCode, createMsgCopyButton, createIconsIn, statusIcon, isNearBottom, shouldFollowBottom, NEAR_BOTTOM_PX, deviceTagHtml } from './utils.js';
import { renderWithRegistry } from './cardRegistry.js';
// schedule-created-card 批（2026-11-05）：Schedule 工具创建/编辑成功后落卡片行。
// 单一调用点 = 本文件 renderTool + persistence.js 两条历史重放腿（同一个 builder）。
import { scheduleCardRowForTool } from './scheduleCard.js';
import { t } from './i18n.js';
// mobile-download 批（2026-11-18 作者令）：「下载落点」单点——AskUser 附件下载腿
// 也经此选路（手机层分享/原下载 + 落点提示；桌面层逐字原行为）。
import { saveFileBlob, notifyLanding } from './downloadSave.js';
// msgchip-provenance 批（2026-11-01）：渠道/平台来源注记剥离单点（牌窗与气泡共用）。
import { stripChannelProvenance } from './channelProvenance.js';
import { sendWs, onMessage } from './ws.js';
import { askSourceParts, removePendingAsk, askAnswerFrame, cancelAskFrame, notifyAskPlacement } from './askPending.js';
import { renderRefBlock, normalizeTaskRef, parseTaskReturnText, buildTaskRefLine, CLOSE_X_SVG } from './reference.js';
// quote-message 一期（spec §7）：主聊天区右键引用 —— 菜单/引用条/引用块/跳转收口在
// msgQuote.js 单模块（chat.js 只挂点，不复制逻辑）。
import { attachQuoteMenu, renderQuoteBlock, quoteFromRef, nextChatMsgId, syncQuoteJumpStates, scheduleQuoteButtonMount, revealQuoteTarget } from './msgQuote.js';
// 图标眼单源（设计语言批 2026-10-21）：AskUser 密钥题的「显示」眼与设置页
// provider 密码域（sidebar.js）同一份 SVG——providersUi.js 单向提供，无环。
import { eyeSvg, eyeOffSvg } from './sidebar/providersUi.js';
// （file-diff 的 chat.js 死 import 于拆面板批 2026-10-22 摘除：chat 单体时代
// 的 renderFileChangesHtml/openFileChangesCanvas 引用自 FE 组件化拆分后即无
// 调用点；委托 diff 现走 injectedRows 摘要 chip → diffPanel.js 单点收口。）
// 令牌头（2026-09-20 收尾批）：`/api/tts` 服务端已加门 ⇒ 本调用点必须带 Authorization。
// 复用既存的同族 helper（flowHelpers.authHeaders，全仓 6 处同族实例），**不新造**令牌读取
// 机制。依赖方向安全：flowHelpers.js 只 import './branding.js'，无环。
import { authHeaders } from './flowHelpers.js';
// Conditional-display widget (modelcfg Phase 1 ruling ②-A): showOptions'
// shouldShow/updateVisibility DOM mechanics live in depVisibility.js now, shared
// with the /model panel. The visibility predicate and the answer-reset stay here.
import { createDepVisibility } from './depVisibility.js';
// AskUser 附件行（顶层 `attachments` ⇒ 整次提问层的可点击附件行）。
// askuser-attach-replyrender 批（2026-10-30 作者令「我回复你的附件，不需要在
// Canvas 打开」）后行形态：**图片内联/lightbox、其余下载**——零 Canvas 依赖。
// 此前的 askuser-attach-align 批（2026-10-07「跟 Pop 保持一致」= 认领类型跳 Canvas）
// 形态随该开法退役；`previewLocalPath` 的好友/设备面认领闸本就不为本行把关。
// 附件行的类型判定仍读 viewer 注册表自身（禁第二张扩展名表；本件已在 bundle 图内）。
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
import { isRootNamed, rootDisplayName, agentDisplayName } from './rootName.js';
// askuser-dragdrop 实施批（2026-10-19，批 #551）：卡片作用域答复附件面（拖入即传
// / 粘贴 / 点选；入口判据 = `answerAttachmentsAllowed`——只给开放题，secret/dirPicker/
// 选择题卡一律静默无入口，作者修正①②）+ 历史卡「已回答 · 附 N 个文件」徽标。
import { answerAttachmentsAllowed, mountAnswerAttachments, mountAnsweredBadge } from './askAttachments.js';

// stream-ux §二.1.1: the work-line shows the FIRST line of the tool's card
// label (same string the user reads on the card), so the single-line badge and
// the card never disagree about which tool is running.
function worklineLabel(rawLabel) {
  const parts = localizeToolLabel(rawLabel).split('\n', 2);
  return parts[0];
}

// chat.js 拆分(FE组件化批次三 2026-09-27):时长徽章/pop-artifact/ask·skill 气泡(纯渲染)/折叠助手/注入行
// 五簇实现迁至 ./chat/*.js。以下显式转发保持公共导出面不缩水(全部符号仍可自 ./chat.js 取得,
// 含动态 import('./chat.js') 解构形态);import 方文件零改动。主体自用符号另行 import。
import { createMsgFooterBadge, renderDurationBadge } from './chat/durationBadges.js';
// output 块语法批（作者令 2026-10-31）：<output> 包裹内容 = 用户可见全部——
// 正常态只渲染块内（框外正文摘除），turn-header 角标展开见 outputTag.js。
import { applyOutputFace, markZeroBlockFace } from './outputTag.js';
import { bindCollapsibleToggle } from './chat/collapsible.js';
// 输入行为包批（2026-11-02 作者令）：busy↔idle 按钮 chrome = input/composer.js
// 统一状态机（主会话 setBusy/clearBusy 与 canvas ask 共用同一实现）。
import { applyTurnChrome, setComposerText } from './input/composer.js';
import { withFocusKey } from './focusTrap.js';
// 审批卡三要素（命令正文 / 为什么危险 / 作用域）单点 —— 报障批 2026-11-19。
// live 渲染与两条历史重放腿共用同一个 builder，卡面形态不得分叉。
import { permDetailFrom, renderPermDetail } from './permDetail.js';
export { formatDuration, formatLiveDuration, pickThinkingPhrase, createMsgFooterBadge, createDurationBadgeElement, renderDurationBadge } from './chat/durationBadges.js';
// Pop 族合流（A 侧 pop-upgrade 批单源化）：`popArtifactFromInput` / `openPopArtifact`
// 的再导出改由下方 A 侧 `./popArtifacts.js` 单源承担（chatSearch.js 既有 import 面
// 不缩水，取到的即 A 侧新语义）；本行只留 B 侧独有的 `applyPopCard` 兼容名——
// 同时保持 ./chat/popArtifact.js 在 bundle 图内（禁孤儿化）。
export { applyPopCard } from './chat/popArtifact.js';
export { renderAskBubble, renderSkillBubble } from './chat/askBubbles.js';
export { bindCollapsibleToggle, chevronSvg } from './chat/collapsible.js';
export { injectedSourceLabel, buildInjectedRow, renderInjectedBubble } from './chat/injectedRows.js';

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

  /** 流式预取：检测到完整 voice 块时立即发起 TTS 请求（并行，不等结果）。
   *  voice 块补封批 2026-10-31：封存态（voiceEnabled 缺席）不预取——零 TTS 请求。 */
  prefetch(text) {
    if (!text || this._cache.has(text) || !voiceEnabled()) return;
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

  /** Add a voice block to the playback queue.
   *  voice 块补封批 2026-10-31：封存态（voiceEnabled 缺席）不入队——
   *  所有播报（块队列 / speakText / 全双工 aireply 同管线）单点闸死。 */
  enqueue(text, element) {
    if (!voiceEnabled()) return;
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

  /** Play a single voice block. Actual playback (post-fetch, unmuted) is
   *  announced on `window` as `voiceplayer:start` / `voiceplayer:end`
   *  (detail { text } on start) — the voice-mode overlay listens to caption
   *  the spoken line and hold its own sends while the agent talks. */
  async _playItem({ text, element }) {
    if (this.muted) return 'done';
    let started = false;
    try {
      const url = await this._getAudioUrl(text);
      if (!url) return 'done';
      const result = await new Promise(resolve => {
        const audio = new Audio(url);
        this._current = { audio, url, element, resolve };
        if (element) element.classList.add('playing');
        window.dispatchEvent(new CustomEvent('voiceplayer:start', { detail: { text } }));
        started = true;
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
      if (started) window.dispatchEvent(new CustomEvent('voiceplayer:end'));
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

  /** User barge-in (voice-mode batch): cancel the current clip and the rest
   *  of the queue so the user's captured speech flows on. The resolve ping
   *  makes the queue loop stop; `_playItem`'s finally emits the end event. */
  interrupt() {
    this._cancel();
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

/** Hand arbitrary text to the shared TTS queue — the voice-mode overlay's
 *  full-reply read rides the exact same sequential pipeline as <voice>
 *  blocks (element-less: no green-link playing state). */
export function speakText(text) {
  if (text) VoicePlayer.enqueue(text, null);
}

/** Voice-mode entry guarantee: full-duplex voice exists to HEAR the agent,
 *  so a leftover client mute (localStorage voiceMuted) must not silently
 *  swallow every clip. Flips the mute off (and syncs the server flag via
 *  the same toggle path) when set. Returns the audible state. */
export function ensureVoiceAudible() {
  if (VoicePlayer.muted) VoicePlayer.toggleMute();
  return !VoicePlayer.muted;
}

// Full-duplex voice mode: sustained mic input during playback barges in —
// the overlay detects it and fires 'voicebargein' on `window`; the player
// (owned here) answers by interrupting itself.
window.addEventListener('voicebargein', () => VoicePlayer.interrupt());

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
    // 输入行为包批（2026-11-02 作者令「文字输入框的逻辑做成一个包」）：按钮
    // chrome 互换收进包实现 applyTurnChrome（与 canvas ask .ask-stop 同一状态机
    // ——语句与顺序逐字保留，仅实现搬位）。
    applyTurnChrome(activeView.dom, true);
  }
}

export function clearBusy(sessionId) {
  state.busySessionIds.delete(sessionId);
  // freezetimeout B2: 终态（done/error/interrupted/timeout/…）或真死收口时，把「仍在
  // 处理」呈现行一并收掉——本函数是全部终态路径的必经点，所以挂在这里而不是各调用点。
  removeStillProcessingNotice(sessionId);
  window.dispatchEvent(new CustomEvent('session-busy', { detail: { sessionId, busy: false } }));
  if (activeView && activeView.sessionId === sessionId) {
    const { input } = activeView.dom;
    // 输入行为包批：按钮 chrome 互换收进包实现（搬位律同 setBusy）。
    applyTurnChrome(activeView.dom, false);
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

/** f1-send-queue：待送达小标（时钟）—— 离线入队乐观气泡的 footer 标注。
 *  视觉对齐 meta 行既有语言（与 .msg-copy/.msg-recall 同档 11px 灰小标），
 *  只加小标不改布局；补发送达后由 input.js flipOfflineMark 摘除。
 *  （输入消息排队回退批 2026-10-23：busy 排队腿回归零气泡呈现面——排队消息
 *  只在排队条行，本函数维持单形态：离线腿时钟标，pending:true 唯一调用形态。） */
function createPendingSendMark() {
  const mark = document.createElement('span');
  mark.className = 'msg-pending-mark';
  mark.title = t('chat.pendingSend');
  mark.setAttribute('aria-label', t('chat.pendingSend'));
  mark.innerHTML = statusIcon('clock', 11, '');
  return mark;
}

/**
 * Render the user's own message row.
 * @param {string} text
 * @param {Array} attachments
 * @param {number} [timestamp]
 * @param {{msgId?: string, pending?: boolean}} [opts]  quote-message 一期（spec §3.6/§3.4）：
 *   msgId = 本行的稳定 id（用户发送腿传 clientMessageId，与引擎帧同键；其余调用点缺省
 *   生成 c-<rand>）。行挂 data-msg-id = 引用跳转的唯一寻址面。
 *   f1-send-queue：pending = true ⇒ footer 前置「待送达」时钟小标（离线入队的乐观
 *   气泡）。（输入消息排队回退批 2026-10-23：busy 排队腿回归零气泡呈现面——排队
 *   消息只在排队条行，drain 后由 input.js 补渲本气泡；排队条行按钮 = 队列动作
 *   唯一入口。）
 */
/**
 * live 腿辅助（tool-card-leak-on-reload 批 2026-11-02）：DOM 里此刻是否有一个
 * **尚未终态**的引擎回合 —— 判据 = 最后一条开轮 user 行之后没有
 * `data-nf-turn-terminal` 行、也没有 `.turn-header`（collapseTurn 收拢过的轮）。
 * `busySessionIds` 是真值源，但在飞终态帧的处理窗口里它可能比 DOM 慢一拍；本
 * 判据把「回合其实已经收口」的那一拍挡掉，避免新回合的开轮行被误标成插话。
 */
function liveTurnStillOpen(chat) {
  const kids = Array.from(chat.children);
  let lastOpener = -1;
  kids.forEach((el, i) => {
    if (!el.classList || !el.classList.contains('row') || !el.classList.contains('user')) return;
    if (el.hasAttribute('data-nf-mid-turn') || el.hasAttribute('data-nf-ask-answer')) return;
    if (el.querySelector('.bubble.injected')) return;
    lastOpener = i;
  });
  if (lastOpener < 0) return false;
  for (let i = lastOpener + 1; i < kids.length; i++) {
    const el = kids[i];
    if (el.classList && el.classList.contains('turn-header')) return false;
    if (el.dataset && el.dataset.nfTurnTerminal) return false;
  }
  return true;
}

export function renderUserBubble(text, attachments, timestamp, opts) {
  const chat = activeView.dom.chat;
  // 媒体统一适配批（2026-10-20）：社交通道入站媒体的**存量「已存至」文本行**
  // 在行级换算成媒体附件（legacyMediaAttsOf 严格识别，fail-open）——存量行走与
  // 新登记行同一张媒体气泡面；附件在场（新登记/控制台）⇒ 逐字原样。换算命中
  // ⇒ 文本气泡隐去（媒体行接管，控制台纯图发送同形）。
  ({ text, atts: attachments } = unifyUserMediaFace(text, attachments));
  // msgchip-provenance 批（2026-11-01 作者令「msg 身份牌剥渠道前缀」④）：用户气泡显示面
  // 同样只留作者原生输入——渠道来源块（`<system-reminder>以下消息来自…`，社交通道入站行
  // 的 .ui.json 里是真存了这个块的）、注入头行、附件路径指针行在此剥除。依据：
  //   ① author 早前既裁「system reminder 永远不显示在会话中」（BridgeOrigin.faces 原律），
  //      存量飞书入站行破例落盘带块 ⇒ 显示面本就是漂移，剥 = 回到裁定；
  //   ② 判据 = 位置+结构（channelProvenance 单点），作者正文里自己写的同类句子原样保留。
  // 注入气泡（buildInjectedRow / NODE-MESSAGE / mail 头那族）**不动**：那是引擎自己的
  // 投递面展示（带 sender/header 行，语义不同），本批不碰它的视觉。
  text = stripChannelProvenance(text).text;
  const row = document.createElement('div');
  row.className = 'row user';
  row.dataset.msgId = (opts && opts.msgId) || nextChatMsgId();
  // tool-card-leak-on-reload 批（2026-11-02）：live 腿的回合内插话标 —— 发送时该
  // 会话仍 busy（引擎回合在跑）⇒ 本行不是轮起点（与回放腿的同一 DOM 契约
  // data-nf-mid-turn；turnGroup 的段边界判据 / workline.turnAnchor 只读它）。
  // 缺席（空闲态发送 / 旧调用点）⇒ 不落标，行为逐字不变。
  // pending:true（离线入队乐观气泡）不算：那一行根本不代表引擎回合内的输入。
  if (!(opts && opts.pending) && activeView && activeView.sessionId &&
      state.busySessionIds && state.busySessionIds.has(activeView.sessionId) &&
      liveTurnStillOpen(chat)) {
    row.dataset.nfMidTurn = '1';
  }
  // quote-message 一期：带引用发送 ⇒ 气泡上方引用块（微信形态）。数据单点 =
  // 附件里的 chat-message ref（live/排队/草稿恢复共用同一形状 ⇒ 三个入路零
  // 额外处理）；该 ref 不进气泡内的 ref 卡循环（引用块是它唯一的消费面）。
  // 🔴 多选引用批（2026-11-22 作者令「还有支持 多选引用」）：旧 `.find(...)` 只取
  // 首条是「一条回复只带一条引用」的第二个断点 —— 改为按 refs 顺序**逐条**渲染
  // （顺序 = 用户选择顺序 = 引擎逐条注入顺序），每条各自一枚引用块、各自可点回看。
  const quoteAtts = (attachments || []).filter(a => a && a.type === 'ref' && a.refType === 'chat-message');
  const quoteBoxes = quoteAtts
    .map(a => quoteFromRef(a))
    .filter(Boolean)
    .map(q => renderQuoteBlock(q))
    .filter(Boolean);
  // 引用块宽度约束（2026-10-20 作者令「引用文字不要超过用户发送的文字」）：
  // 引用块 + 文本气泡同时在场 ⇒ 包进 .msg-quote-composite（气泡驱宽复合盒，
  // 规则见 chat.css 同名段）——引用块渲染宽 = 气泡宽（窄气泡跟着窄），替代原
  // 「引用块 ≤ 100% 行宽」与气泡 80% 帽的错位。仅引用无气泡（纯引用帧被
  // isRefOnlyFrame 闸拦截；此处防御性兜底）不包裹，保持整行形态零回归。
  // 多块同盒：复合盒是 flex column（chat.css），N 块按顺序纵向叠放，宽度同一支尺。
  let composite = null;
  if (quoteBoxes.length && text) {
    composite = document.createElement('div');
    composite.className = 'msg-quote-composite';
    row.appendChild(composite);
  }
  quoteBoxes.forEach((box) => (composite || row).appendChild(box));

  // Text bubble (separate)
  if (text) {
    const bubble = messageBubbleNode(mainBubbleClass('user', activeView.motionEnabled() ? 'nf-pop' : ''));
    const t = document.createElement('div');
    // Plain-text face with mention spans (mention-render batch): identical
    // single-text-node output when nothing matches — user text never
    // enters markdown. rich-render 批（2026-11-05 作者令第 1 条）：唯一放行的
    // 结构是**已闭合的图围栏**（```mermaid / ```svg），其余仍逐字纯文本。
    appendUserTextWithFences(t, text);
    bubble.appendChild(t);
    (composite || row).appendChild(bubble);
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
      bubble.className = mainBubbleClass('user', 'att-bubble');
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
  const badge = createMsgFooterBadge(ts, text);
  // f1-send-queue：待送达小标前置（badge 为 inline-flex gap:6px，前置即有自然间距，
  // 不动 time/copy 与分隔符的既有序）。排队呈现回退批：busy 排队腿裸上屏无小标
  // （离线腿 pending:true 是唯一带标形态）。
  if (opts && opts.pending === true) badge.prepend(createPendingSendMark());
  row.appendChild(badge);
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
  const mediaRow = buildUserMediaRow(mediaAtts, { ts });
  if (mediaRow) {
    // f1-send-queue：媒体卡随行挂同一 msgId —— 离线乐观气泡被撤回/移除/溢出丢弃时
    // 「消息行 + 媒体卡」两排一起收（input.js removeOfflineBubble 的寻址面）。
    mediaRow.dataset.msgId = row.dataset.msgId;
    // 用户附件点击统一批（2026-10-08）：live 发送时行先于后端落盘渲染，item 无
    // path —— 媒体整面点击只能落到 pathless 灯箱、文件卡点击是死的。把行登记进
    // popArtifacts 的待补表，`attachmentsSaved` 帧（后端 ui.json 落盘后回传）到达
    // 后用同一套 builders 原位重渲：item 带 path ⇒ 点击 = openPopArtifact 单点
    // （与 Pop 三修 ② 整面点击开 Canvas、与历史恢复腿三者同构）。ts 同传——补丁
    // 腿重渲的 footer 信息条时间档与 live 腿同值（手机端长按退役批 2026-10-31）。
    registerUserMediaRow(activeView.sessionId, mediaRow, mediaAtts, ts);
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
// name 防错位 / path null 不补）。会话级帧：用户已切走的会话被 ws.js 会话闸过滤丢弃
// ——该会话重入时由历史恢复腿（persistence.buildUserAttachmentRows，自带 path）接管。
// 旧后端不发此帧 ⇒ 订阅永不触发 ⇒ 行为与改前一致（无回归面）。
//
// 📌 断线史（2026-10-28 报障「我自己发的图，还是点不开」根因）：本订阅在
// 2026-10-10 内网仓 dev/main 合并的冲突解决中整体丢失（checkpoint a0f2edfc7 的
// chat.js:490 原文可溯；`patchUserMediaRow`/`onMessage` 的 import 线都在、唯一
// 消费块没了）⇒ live 发送腿的行永远停在 pathless 形态，点击开 Canvas（三修 ②
// 裁定、与 Pop 同款）直到刷新才由恢复腿接管。user-attach-canvas-open spec A2
// 之所以照绿：它直接调 patchUserMediaRow，从没钉过「帧 → 订阅 → 补丁」这一跳
// ——本批随修补上（spec B 组走真 ws.js 派发路径）。若再有人合并 chat.js 冲突：
// 删这段前先跑该 spec，红了就是又丢了。
onMessage('attachmentsSaved', (msg) => {
  if (msg && msg.sessionId) patchUserMediaRow(msg.sessionId, msg.attachments);
});

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

// ---------- 主窗正文「边流边显」腿（stream-latency 批 · 2026-11-19）----------
// 作者令 + 四腿诊断（`stream-latency-four-leg-diagnosis__20261119_145015.md` 候选②）：
// 主窗助手正文原来**只累积、段末整段弹出**（唯一渲染点在 finishAi），一条长纯文本
// 回复「等到结尾才出现」——正是被读成「流式输出慢」的可见性缺口。本腿把它接进
// **既有**增量渲染器（renderStreamMarkdown：已闭合块缓存 + 只重解析未闭合尾块 +
// <img> 跨帧存活）与**既有** rAF 合批调度（scheduleStreamRender，键 'ai'），
// **不新增第二套渲染路径**。
//
// 与思考流 / 子 agent / ask 三条既有流式腿同构：每帧至多一次渲染（rAF 合批 ⇒
// 帧预算有界，不重犯逐字全量重解析的 O(n²) 自旋）；终态仍由 finishAi 做**唯一一次
// 全量收口渲染**，其结果与「一次性全量重渲」逐字节等价（断言在
// tests/main-stream-render.spec.mjs + tests/stream-ux.spec.mjs A3/A7）。
//
// 负控开关（判据 ≤1 条）：关 ⇒ appendAiText 回到改前行为（只累积 + 段末整段弹出），
// 闸据此证明本腿承重。生产代码从不关它。
let _aiBodyStream = true;
/** 负控出口：关掉「边流边显」腿（= 改前行为：气泡流式期保持空，finishAi 一次画出）。 */
export function setAiBodyStreamRender(on) { _aiBodyStream = on !== false; }
/** 读数出口：当前「边流边显」腿是否开着（负控用）。 */
export function aiBodyStreamRenderEnabled() { return _aiBodyStream; }

// ── 帧预算读数（先例：flowAnim.js 的 stats，注释同样写明「exposed for the specs」）
// 每帧流式渲染的**主线程实测耗时**（renderStreamMarkdown = 尾块解析 + DOM 换尾），
// 闸据此证明单帧成本不随累积文本失控（O(n²) 判据读数）。未开启时每帧只多一次
// `null` 判断，零计时开销。
let _aiStreamStats = null;
/** 开启并复位帧预算计数器（返回同一对象，闸读 frames/totalMs/maxMs/lastMs/chars/msSamples）。 */
export function enableAiStreamStats() {
  _aiStreamStats = { frames: 0, totalMs: 0, maxMs: 0, lastMs: 0, chars: 0, msSamples: [] };
  return _aiStreamStats;
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
    view.stream.currentAiBubble.className = mainBubbleClass('ai');
    row.appendChild(view.stream.currentAiBubble);
    appendChatRow(chat, row);
  }
  // Preserve any option box across re-renders: keep it OUT of the bubble while
  // streaming so innerHTML replacement can't destroy its event listeners.
  const askBox = view.stream.currentAiBubble.querySelector('.option-box');
  if (askBox) { askBox.remove(); view.stream.aiStreamAskBox = askBox; }
  // stream-latency 批（2026-11-19，**有意契约变更**）：正文**边流边显**。旧契约
  // 「the assistant bubble does NOT stream text」（stream-ux §二.1.2 / A3 / A7，
  // 2026-10-02）按作者令 + 四腿诊断候选②退役——判据已就地重锚到新契约（见
  // tests/stream-ux.spec.mjs A3/A7 的「有意契约变更」头注与 tests/main-stream-render.spec.mjs）。
  // Deltas 仍旧累积进 view.stream.aiText + 节点 _nfText（bgAgentPopup 的 reopen-adopt
  // 读它），增量渲染只是**多了一条逐帧腿**，终态收口渲染点仍唯一在 finishAi()。
  const bubble = view.stream.currentAiBubble;
  if (mark && typeof mark.isRoot === 'boolean') bubble._nfIsRoot = mark.isRoot;
  bubble._nfText = view.stream.aiText || '';
  // B2 consumption ②: the bubble carries the identity of the frame that last
  // wrote it. `finishAi`/`finishThinking` clear the mark when the segment ends,
  // so a mark left on the node always means "this segment is still open".
  if (seg) {
    bubble._nfSeg = seg;
    bubble._nfRound = mark.round;
  }
  if (_aiBodyStream && view.id !== 'primary') {
    // rAF 合批 ⇒ 每帧至多一次渲染（多帧 delta 合并成一帧），尾块增量 ⇒ 单帧成本
    // 与「未闭合尾块长度」同阶而非与累积全文同阶。跟随钉底收进 rAF 同拍（与
    // agent/ask 两腿同构）——因此这里不再同步读一次 scrollHeight（把该几何读留给
    // 帧内那一拍，避免每 delta 两次强制布局）。
    scheduleStreamRender(view, 'ai',
      { bubble, chat, snapped: view.stream.scrollSnapped, mark: mark || null },
      (target) => {
        if (!target.bubble.isConnected) return;
        const stats = _aiStreamStats;
        const t0 = stats ? performance.now() : 0;
        // Report face is opt-in by POSITIVE source stamp (author ruling
        // 2026-11-20: root-only, unknown identity is never guessed). Only a frame
        // that said `isRoot: true` gets the extracted body; anything else —
        // `false`, or an unstamped legacy frame — renders its full text exactly
        // as before (the tags then stay literal: no `opts.report` below).
        // 流式期：root 无 report 块的文本先照常流出（不吞）；仅当出现了标签但尚未配平
        // （流式中间态）时才隐藏，免得裸标签闪现。终态（finishAi）零块 root 折进角标。
        const rawStream = target.bubble._nfText || '';
        const streamInner = target.bubble._nfIsRoot === true ? extractOutputInner(rawStream) : null;
        const visible = target.bubble._nfIsRoot !== true ? rawStream
          : (streamInner !== null ? streamInner : (/<\/?report>/.test(rawStream) ? '' : rawStream));
        target.bubble.hidden = !visible;
        renderStreamMarkdown(target.bubble, visible, true,
          imgKeepFor(target.bubble), false, target.mark || null);
        // 流式光标：与思考 / agent / ask 三条腿同源；终态收口渲染整棵换掉它
        // （finalizeStreamingFaces 另有 ·cursor 扫尾兜底）。
        target.bubble.insertAdjacentHTML('beforeend', '<span class="cursor"></span>');
        // 入场动画的归属判据（finishAi 读它）：本气泡已经逐帧长出来过。
        target.bubble._nfStreamed = true;
        if (stats) {
          const dt = performance.now() - t0;
          stats.frames += 1;
          stats.totalMs += dt;
          stats.lastMs = dt;
          if (dt > stats.maxMs) stats.maxMs = dt;
          // 有界样本序列（闸读分布用；上限 1200 条 ⇒ 长流也不会无界增长）。
          if (stats.msSamples.length < 1200) stats.msSamples.push(dt);
          stats.chars = (target.bubble._nfText || '').length;
        }
        rafScrollChat(target);
      });
  } else if (view.stream.scrollSnapped === true || isNearBottom(chat)) {
    // 负控腿（关掉「边流边显」）：改前的同步跟随钉底，逐字保留。
    chat.scrollTop = chat.scrollHeight;
  }
}

export function finishAi(durationMs, model) {
  // Cancel any pending throttled render — this final render supersedes it.
  // stream-latency 批（2026-11-19）：本腿现在是**主窗正文**的常规在飞渲染槽
  // （appendAiText 每帧排一次），这一撤是终态唯一渲染点的前置条件。
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
    // report 块可见性批（作者令 2026-11-20）：可见性只作用于**源头身份为 root**
    // 的那条消息——帧上 `isRoot === true` 才走摘除面；`false` 或旧帧（无戳）一律
    // 全文渲染，与改前逐字一致。无平衡块 ⇒ 全文渲染，但 root 面仍以 `report:true`
    // 过管线（散标签转义为字面文本，防被当成 HTML 块吞文）。
    const rootFace = bubble._nfIsRoot === true;
    if (!rootFace || !applyOutputFace(bubble, activeView.stream.aiText || '')) {
      // 零块 root（无平衡块）：过程叙述 ⇒ 盖零块戳并**折进角标**（markZeroBlockFace 置
      // hidden；turn-header 角标展开见原文，turnGroup 读戳）。非 root 逐字不变。
      bubble.innerHTML = renderMarkdownWithMath(activeView.stream.aiText || '', true, rootFace ? { report: true } : undefined);
      if (rootFace) markZeroBlockFace(bubble, activeView.stream.aiText || '');
    }
    bubble._nfText = activeView.stream.aiText || '';
    const termText = bubble._nfText;
    void import('./viewers/markdown.js').then(({ termLinkify }) => {
      if (!bubble.isConnected || bubble._nfText !== termText) return;
      return termLinkify(bubble, null, () => bubble.isConnected && bubble._nfText === termText).then(() => {
        if (!bubble.isConnected || bubble._nfText !== termText) return;
        bubble.querySelectorAll('a.nf-wikilink-term').forEach((link) => {
          link.addEventListener('click', (event) => {
            event.preventDefault();
            void import('./termTip.js').then(({ termTapExpand }) => termTapExpand(link, null));
          });
        });
      });
    }).catch(() => {});
    bubble._nfRendered = true;
    if (askBox) bubble.appendChild(askBox);
    activeView.stream.aiStreamAskBox = null;
    // stream-ux §二.1.2 旧契约（整条一次性出现 = scale + elastic 入场）**有意变更**
    // （stream-latency 批 2026-11-19）：入场动画标记的正是「**整条一次画出**」这件事。
    // 正文已走「边流边显」腿（气泡先逐帧长出来，节点上留 `_nfStreamed`）时不再补这一下
    // ——否则 `nf-pop` 的 0% 档（opacity:0 / translateY(16px) scale(.82)，fill `both`
    // 加类即生效）会把已经看得见的整条回复闪没再弹回来，恰好是本批要消除的「段末整段
    // 弹出」观感。两条**照旧入场**的路径：单帧内 appendAiText→finishAi（rAF 尚未跑、
    // 气泡仍空）+ 关腿负控（气泡流式期保持空）——两者都仍是「整条一次画出」。
    // Only on the first final render — a second finishAi on the same bubble (round
    // boundary) must not replay the entrance.
    // UI-G: `nf-pop` is CSS animation, so the class is only applied in the
    // main window; the popup views carry `.nf-static` anyway (chatView.js) and
    // this keeps the class off their DOM entirely, so a static window owns no
    // animation class even if the CSS rule were ever narrowed.
    if (firstPaint && !bubble._nfStreamed && activeView.motionEnabled()) bubble.classList.add('nf-pop');
    const ts = Date.now();
    // 零正文终轮行（footer-leak 批 2026-11-22）：空正文 ⇒ 收起整行（空泡壳 + 徽章
    // 不上屏），DOM 保留供 turnGroup 读 terminal/nfPhrase；与回放腿 row-empty-final 同口径。
    if (!activeView.stream.aiText) {
      const emptyRow = bubble.closest('.row');
      if (emptyRow) emptyRow.classList.add('row-empty-final');
    }
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
    // and attach click-to-replay handlers on the green text. Full-duplex
    // voice mode reads the WHOLE reply instead — the per-block queue is
    // skipped and the rendered text is handed to the overlay via 'aireply'
    // (voiceMode.js chunks it into the same TTS pipeline).
    const voiceModeOn = document.body.classList.contains('voice-mode-active');
    bubble.querySelectorAll('.voice-block').forEach(el => {
      el.addEventListener('click', () => VoicePlayer.replay(el));
      if (!voiceModeOn) VoicePlayer.enqueue(el.textContent, el);
    });
    if (voiceModeOn) {
      window.dispatchEvent(new CustomEvent('aireply', {
        detail: { sessionId: activeView.sessionId, text: bubble.innerText },
      }));
    }
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

// ---------- Multi-agent rendering ----------
export function appendAgentText(agentId, text) {
  const view = activeView;
  const chat = view.dom.chat;
  if (!view.stream.agentBubbles[agentId]) {
    const row = document.createElement('div');
    row.className = 'row ai agent-row';
    const bubble = document.createElement('div');
    bubble.className = mainBubbleClass('ai');
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
      // stream:true — 这是逐帧重渲的流式快照：图围栏保持代码块，消息收尾腿才出图
      // （rich-render 批判据，见 utils.js renderMarkdownWithMath 的 opts.stream）。
      target.bubble.innerHTML = renderMarkdownWithMath(target.bubble._nfText || '', true, { cache: false, stream: true }) + '<span class="cursor"></span>';
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
import { isPopPayload, parsePopPayload, buildPopArtifactRow, renderPopToolRow, applyLegacyPopCard, buildUserMediaRow, registerUserMediaRow, patchUserMediaRow, unifyUserMediaFace, fileIconStyle, extOf, buildFileCard, openPopArtifact } from './popArtifacts.js';
// ui-unification-20261103-askuser-attach（作者令 2026-11-04）：AskUser 卡内附件的
// 媒体档 = **卡语言标准组件**（叠卡 `.ds-media-stack` 族 / 平面 `.pop-media-flat`）；
// 预览层 = 灯箱单点。禁在本文件再写第二套媒体卡 / 叠卡 / 预览实现。
// attachment-thumb-unify 批（作者令 2026-11-05 12:57「附件这里添加的图片文件等应该
// 用我们输入框同款的小图标/缩略图」）：卡区媒体档**不再**装配 220px 卡语言媒体卡
// ⇒ buildMediaFlat/buildMediaStack 在本文件的消费点清零（组件本体仍在
// popArtifacts.js，主会话/社交三窗照用）。缩略帧字节腿 = 同一 `hydrateMedia`
// （nfFileCache 账本 ⇒ 预览与灯箱复用同一份字节，零重复抓取）。
import { openMediaLightbox, hydrateMedia, clearMediaFailure } from './popArtifacts.js';
// askuser-media 批（2026-11-18）：选项预览（swatch / 单图小缩略 / 视频 / 媒体组叠卡）
// 的数据形态与渲染单点——独立模块，供待办等第二宿主复用（同一 `options[].preview`
// 形状）。媒体组的叠卡**不新造**：该模块直接喂消息面既有组件 buildMediaStack。
import { buildAskPreviewNode, askPreviewKind } from './askMediaPreview.js';

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
    card.classList.remove('tool-card--pending', 'ds-tool-card--pending');
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
    // .tool-card = DOM 寻址钩子（main.js 换面/chatSearch/turnGroup/spec），
    // .ds-tool-card = 样式族（design-system.css 唯一家）。
    card.className = 'tool-card ds-tool-card';
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
                         : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
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

  // GenerateImage（交互修正批 2026-10-30 起**阻塞语义**）：无特判面——调用期间
  // 由上方标准 pending 工具卡原位占位（spinner + 生图标签），完成时工具结果
  // 自带 ___POP_JSON___ 载荷，走下方 isPopPayload 通用腿原位直出单张图
  // （旧非阻塞占位卡 + WS 完成帧推送的第二渲染腿整腿退役，双渲染根因拆除；
  //   退役帧形与占位模块 imagegen.js 均已从生产源移除——见 imagegen-blocking spec）。

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
      // Pop 交付卡 footer 批（作者令 2026-11-01 16:19）：ts 随传——交付行 footer
      // 信息条时间档 live 腿 = 到达时刻（重放两腿 = m.timestamp，同一三腿口径）。
      const artifactRow = buildPopArtifactRow(popPayload, { ts: Date.now() });
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
                         : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
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
    // 工具卡图片引用面（tool-card-image-face 批 2026-11-04）：Mail 卡 markdown
    // 正文里的图片语法被 marked 渲成真 <img> —— 工具卡是摘要面，图片一律折叠为
    // 「🖼 图片 ×N」引用件（灯箱单点），绝不内嵌渲染。utils 单点兜底。
    if (mailBody) foldToolCardMedia(card);
    smartScroll();
    if (mailBody) attachToolClick(card);
    return { type: 'tool', label, summary, content: null, isError, input: inputJson };
  }

  // Default rendering for all other tools
  const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                       : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
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
  // 折叠单源（ui-unification-20261103-chat-stream）：长输出默认 ≤TOOL_PREVIEW_LINES
  // 行预览 + 展开控件，全文只在展开瞬间进 DOM（mountToolCardBody 单点定义，
  // persistence.js 两条历史腿同构引用）。短内容保持遗留「点击开合」形态。
  const promptText = delegatePromptRaw(inputJson);
  const localLabel = localizeToolLabel(label);
  const localSummary = localizeToolSummary(summary, label);
  const labelParts = localLabel.split('\n', 2);
  const truncBadge = ''; // placeholder for future truncated content indicator
  // Device tag — subtle indicator when tool runs on a remote device.
  // 设备角标本名批 2026-10-27：本机字面（local/self）解析为本机设备名（剥 -iso
  // 后缀显示、title 保留原值），不再直出「local」——helper 单点 deviceTagHtml。
  let deviceTag = '';
  if (inputJson) {
    try {
      const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
      if (inp.device) deviceTag = deviceTagHtml(inp.device);
    } catch {}
  }
  const labelHtml = escapeHtml(labelParts[0]) + ' &mdash; ' + escapeHtml(localSummary) + truncBadge + deviceTag
    + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + labelHtml + '</div></div>';
  const { hasBody } = mountToolCardBody(card, { detailHtml, promptText, content, label, labelTail: labelParts[1] || '' });
  smartScroll();

  if (hasBody) attachToolClick(card);
  // ── Schedule 创建/编辑卡片（schedule-created-card 批 2026-11-05）───────────
  // 作者令：创建定时任务/待办成功时，**消息流里**落一张可原地改/取消的卡片。
  // 形态沿用 Pop 交付卡的两行制（进程行 = 上面这张既有工具卡，卡片行紧随其
  // 后；行类名 `row sched-row`），卡体是 light DOM 组件 <nf-sched-card>
  // （js/scheduleCard.js），写读走 js/scheduleApi.js 的既有 WS 帧（与 Schedule
  // 工具同一 store）。判据面只在这里接线，卡的所有细节在组件里。
  const schedRow = scheduleCardRowForTool({
    label: _toolName, inputJson, content, isError, sessionId: sid,
  });
  if (schedRow) {
    row.after(schedRow);
    smartScroll();
  }
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
  card.className = 'tool-card ds-tool-card tool-card--pending ds-tool-card--pending';
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
    state.cancelTurnConfirm?.(sid);
    clearWatchdogTimer(sid);
    _watchdogRung.delete(sid);
    clearBusy(sid);   // ⇒ removeStillProcessingNotice（chat.js:271）：真死态不留「仍在处理」行
    // queue-idle-flush（2026-10-23）：真死收口 = busy→idle 迁移，排队消息照常触发
    // 投递（旧实现只清不排 ⇒ 队列滞留）。WS 已断子案由 drain 自身的 ws 闸挡下，
    // 重连后的补发轨（flushOfflineQueue）语义不变。
    // queue-one-per-turn（2026-10-24）：真死判定自带「阶梯到顶 ∧ 无活性证据」上位
    // 证据 = 权威终态 ⇒ 落终态戳放行投递未决闸（被派 turn 永无终态帧形态下，无戳
    // 则队列滞留）。
    state.lastTerminalAt[sid] = Date.now();
    state.scheduleQueueDrain?.(sid);
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
  const btnCss = 'padding:var(--ds-space-1) var(--ds-space-3);border-radius:var(--ds-radius-chip);border:1px solid var(--color-frame-border);background:var(--color-frame-hover);color:var(--color-frame-text);cursor:pointer;font-size:var(--ds-fs-body);font-family:inherit;';
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

/** 重发输入历史末条（原「重试」按钮语义，保持不变）。
 *  resend-dedup 批（修复批 #569）：重发 = **同一条逻辑消息** ⇒ 复用
 *  `state.lastUserSendId[sid]` 里首次派发的 clientMessageId（幂等键不动），
 *  不再现生成新 id。此前每次重发都带新 id ⇒ 服务端视为全新消息：
 *  「WS 断开≈15 分钟（relay 票据 TTL）后重连 → 重试补发已送达的消息」在服务端
 *  跑出第二个 turn + 客户端多渲染一个气泡（= 重复气泡报障本体）。现在：
 *    · 原消息已送达（服务端 recentMessageIds 命中该 id）⇒ 丢弃 + `messageDuplicate`
 *      回执 ⇒ 不出现重复气泡、不跑第二个 turn；
 *    · 真未送达（服务端没见过该 id）⇒ 正常受理 ⇒ 补送不丢。
 *  id 缺席（旧页面残留 / 历史重放无登记）⇒ 保持旧行为现生成（服务端无从去重，
 *  与改动前逐字同险）。 */
function resendLastInput(v) {
  const history = state.inputHistory;
  // The history also holds messages that were sent with attachments only (an empty text): the text to send again is the
  // newest one that has any.
  let lastMsg = '';
  for (let i = history.length - 1; i >= 0 && !lastMsg; i -= 1) lastMsg = history[i];
  if (!lastMsg) return;
  const sid = v && v.sessionId;
  const resendId = (sid && state.lastUserSendId[sid]) || '';
  // 程序化回填走唯一入口（输入框显示缺陷批 2026-11-21）：重发一条长消息也必须
  // 量高 + 把光标位拉进视口，否则内容塌在一行里、尾段读不到。
  if (v && v.dom && v.dom.input) setComposerText(v.dom.input, lastMsg);
  import('./input.js').then(({ send }) => { setActiveView(v); send(resendId || undefined); });
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
  btn.style.cssText = 'padding:var(--ds-space-1) var(--ds-space-3);border-radius:var(--ds-radius-chip);border:1px solid var(--color-frame-border);background:var(--color-frame-hover);color:var(--color-frame-text);cursor:pointer;font-size:var(--ds-fs-body);font-family:inherit;';
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
// 作者令 2026-10-28 11:44（无感压缩「彻底零痕迹」批）：回落同步压缩的完成/失败
// 可见面**全面停渲染**（live + 重放 + 分页/localStorage 各腿，零卡零横幅零尾注）；
// 数据面零削减——WS 帧、ui.json 记录、引擎日志、压缩档案照常留账（审计可查）。
// 唯一保留的可见例外 = 0.95 硬守卫 emergencyClean 真清上下文（数据有损）时的
// emergency 卡（安全阀，帧带 emergency:true）：live 渲染走 renderCompactDoneCard
// （main.js compactComplete 按 msg.emergency 分流），重放腿走 persistence.js
// restoreCompactCardRow 的 params.emergency 分支。失败卡（compactFailed）同刀
// 静默且无例外——引擎已有退避+熔断+应急兜底，UI 不重复报错。
// （前史：compaction-pill-remove 批 2026-10-19 拆「压缩中」胶囊时保留了完成
// 记录行；本批按当日新口径将保留面收窄至 emergency 安全阀。）
// buildCompactCardRow remains the SINGLE source of the emergency card DOM —
// the live terminal event and the history-replay restore (persistence.js) both
// build their cards through it.
const compactCheckSvg = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>';
const compactFailSvg = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>';

/** Build one compaction card row. kind: 'done' (check) | 'error' (cross).
 *  label is plain text and is escaped here. (error kind remains for component
 *  parity; no current call site renders it after the zero-trace batch.) */
export function buildCompactCardRow(kind, label) {
  const row = document.createElement('div');
  row.className = 'row notice';
  const card = document.createElement('div');
  card.className = 'compact-card';
  card.dataset.state = kind;
  const icon = kind === 'done'
    ? `<span class="compact-card-icon ok">${compactCheckSvg}</span>`
    : `<span class="compact-card-icon err">${compactFailSvg}</span>`;
  card.innerHTML = icon + `<span class="compact-card-label">${escapeHtml(label)}</span>`;
  row.appendChild(card);
  return row;
}

function appendCompactCard(view, state, label) {
  const chat = view?.dom?.chat;
  if (!chat) return null;
  const row = buildCompactCardRow(state, label);
  appendChatRow(chat, row);
  smartScroll();
  return row.querySelector('.compact-card');
}

/** Emergency hard-guard (0.95) card ONLY — the regular fallback-compaction
 *  done card is dismantled (zero-trace batch, 2026-10-28); main.js gates the
 *  call on msg.emergency.
 *  @param {object} [view]
 *  @param {{before?: string, after?: string, detail?: string}} [opts] elapsed counts
 *  carried by the emergency frame (all optional — the card renders without them) */
export function renderCompactDoneCard(view = activeView, { before, after, detail } = {}) {
  // Elapsed used to ride on view.compactStartTs (stamped by main.js on
  // compactStart); that stamp is dismantled with the done card — emergency
  // has no compactStart frame, so the card renders without the suffix.
  const startTs = Number(view?.compactStartTs) || 0;
  delete view?.compactStartTs;
  const elapsed = startTs ? Math.max(1, Math.round((Date.now() - startTs) / 1000)) : 0;
  let label = t('chat.compacted', { before, after, detail: detail || '' });
  if (elapsed) label += t('chat.compactElapsed', { seconds: elapsed });
  appendCompactCard(view, 'done', label);
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
 *  like the pre-preview version (E1/E3 zero regression).
 *
 *  askuser-media 批（2026-11-18）：形态与渲染整体迁到**单点模块**
 *  `askMediaPreview.js`（可被待办等第二宿主复用；媒体组直接喂消息面既有叠卡
 *  组件 `buildMediaStack`）。本函数退化为一行委托 —— 旧两形态（swatch / 单图
 *  小缩略）在该模块内**markup 逐字保留**（零回归）。
 *  @returns {HTMLElement|null} */
function buildOptionPreviewEl(pv) {
  return buildAskPreviewNode(pv);
}

/** 选项的文字内容（label + 可选 description）——**DOM 构建单点**。
 *  askuser-media 批（2026-11-18）：改前这两行走 `escapeHtml(...)` + 裸 innerHTML 赋值；
 *  本批改为 DOM 构建（`textContent` 天然免注入），渲染结果**逐节点等价**（同 class、
 *  同文本），同时让 `chat.js` 的组件契约闸 R5「裸 innerHTML」计数**只减不增**
 *  （`scripts/check-component-contract.mjs`：基线内文件超过基线即红）。
 *  `wrapInSpan`：true ⇒ 文字包一层 `.option-text`（带预览的选项行用，既有形态）；
 *  false ⇒ 文字直接落在宿主上（无预览单选行的既有形态）。 */
function fillOptionText(host, label, desc, wrapInSpan) {
  const target = wrapInSpan ? document.createElement('span') : host;
  if (wrapInSpan) target.className = 'option-text';
  target.appendChild(document.createTextNode(label));
  if (desc) {
    const d = document.createElement('div');
    d.className = 'option-desc';
    d.textContent = desc;
    target.appendChild(d);
  }
  if (wrapInSpan) host.appendChild(target);
  return host;
}

/** 媒体选项的**卡片体**（askuser-media 批 2026-11-18）：媒体面在上、文字在下
 *  （label + 可选 description），整块占满选项行宽。
 *
 *  媒体组消费自身操作；文字及媒体组外的选项区域仍由选项按钮处理。 */
function buildOptionMediaBody(pv, label, desc) {
  const body = document.createElement('div');
  body.className = 'option-media-body';
  const node = buildAskPreviewNode(pv);
  if (node) body.appendChild(node);
  fillOptionText(body, label, desc, true);
  return body;
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
 *  renderAskUserHistory 四处）。幂等：已带 `resolved` 类直接返回。
 *
 *  askuser-attach-replyrender 批（2026-10-30）豁免面：`.ask-attachments-wrap`（AskUser
 *  附件行承载块，只读查看面——图片内联/lightbox、其余下载）**豁免** ①③ —— 「查看
 *  一个附件」≠「重新作答」，已决卡上的答复附件必须仍可打开（2026-10-07 批建行的
 *  点击面被本哨兵静默吞掉，即「答复附件点了没反应」旧病）。豁免只放通行，不放开作答
 *  控件（option-btn/confirm/cancel 均不在 wrap 内）。 */
function lockOptionBox(box) {
  if (!box || box.classList.contains('resolved')) return;
  box.classList.add('resolved');
  box.querySelectorAll(LOCKABLE_SELECTOR).forEach((el) => {
    if (el.closest('.ask-attachments-wrap')) return; // 只读附件行豁免（本批）
    el.setAttribute('aria-disabled', 'true');
    el.setAttribute('tabindex', '-1');
    if (el instanceof HTMLButtonElement || el instanceof HTMLInputElement ||
        el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement) {
      el.disabled = true;
    }
  });
  // ③ 点击哨兵：capture 阶段在卡内任意入口的 listener 之前拦下（含程序化 .click()）。
  box.addEventListener('click', (e) => {
    if (e.target && e.target.closest && e.target.closest('.ask-attachments-wrap')) return; // 附件行豁免
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
  // askuser-dragdrop 实施批（2026-10-19，批 #551）：答复附件面控制器。**声明前置**
  // （checkAllAnswered 在 syncMulti 的草稿恢复腿里早于挂载点被调用——TDZ 防御）；
  // 挂载本体在按钮行构造处（活卡 + 入口判据通过才挂，见彼处注）。
  let answerAttach = null;
  // askuser-otherattach 批（2026-10-30 作者追加令 11:52）：选择题卡点「其他…」进入
  // 自由输入态 ⇒ 挂**同款**附件控件，附件随 Other 文本随帧到达（信封协议不变——
  // `attachments` 键卡级挂载，后端注入与题型无关）。修正①的「选择题卡无入口」
  // 收窄为「未进 Other 态无入口」；附件仍不替代选项（`satisfyGate` 对选项卡恒
  // false 不动，发送闸照旧要求本体作答 + 上传中置灰）。惰性单次挂载：Other 态
  // 激活即挂、之后常驻——已挂区不随 Other 取消再隐藏（区里有用户文件，藏=孤儿面）。
  // 资格判据 = 有 options + 允许 Other + 自由输入面在场（freeInput !== false）+
  // 非 secret/dirPicker（与 answerAttachmentsAllowed 同族 fail-closed 口径）；
  // 开放题卡走 answerAttachmentsAllowed 早挂腿（下方挂载点），这里显式排除防双挂。
  // 🔴 声明前置理由同 answerAttach：草稿恢复腿（questions.forEach 内）会调用
  // mountOtherAttachments —— 彼处按钮行尚未构建，只置 pendingOtherMount 旗标、
  // 由挂载点统一落挂（禁在 btnRow 缺席时提前插区，插位会退化到卡尾）。
  const otherAttachEligible = !!(onConfirm && requestId) &&
    !answerAttachmentsAllowed(questions) &&
    questions.some(q => q && q.options && q.options.length > 0 &&
      q.freeInput !== false && q.allowOther !== false &&
      q.secret !== true && q.dirPicker !== true &&
      // permission-landing 批续做（2026-11-18）：卡显式 opt-out「其他…」附件入口。
      // 权限卡现在带 requestId（只为卡盒 data-request-id 寻址，见 renderPermissionPrompt），
      // 但权限作答**无附件通道**（permissionAnswer 无 attachments 键）⇒ 不给它挂一个
      // 投递无门的附件控件（与 secret/dirPicker 同族的 fail-closed 口径）。
      q.otherAttach !== false);
  let pendingOtherMount = false;
  function mountOtherAttachments() {
    if (answerAttach || !otherAttachEligible) return;
    answerAttach = mountAnswerAttachments(box, { requestId, onChange: () => checkAllAnswered() });
  }
  const confirmLabel = doneLabel || t('chat.confirm');
  const saved = askSessionId ? loadAskDrafts(askSessionId) : {};
  const questionWrappers = [];
  // 无滑动（作者令 2026-11-21）：题与选项直接铺进卡盒，不套滚动区；卡内容一屏直显。

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
        const pw = /** @type {HTMLInputElement} */ (wrapper.querySelector('.option-secret-input'));
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
    // DOM 值立即清空（confirm 路径）。
    // 设计语言批 2026-10-21：内联样式整批退役 → `.option-secret-*` 类
    // （css/chat.css askuser-hybrid 段，材质对齐同卡 `.option-custom-input`
    // 兄弟输入档）；「显示/隐藏」文字钮 → 图标眼（eyeSvg/eyeOffSvg 单源 =
    // sidebar/providersUi.js，与密钥面板/设置页同一枚眼），toggle 逻辑不动
    // （type password↔text），aria-label/title 仍走 t() 双语键，aria-pressed
    // 随揭示态。信息架构与交互语义零变化（视觉语言统一批）。
    if (item.merge && typeof item.merge === 'object' && item.secret !== true) {
      const config = item.merge;
      const ops = config.operations || {};
      const enabled = key => ops[key] !== false;
      const branches = (Array.isArray(config.branches) ? config.branches : [])
        .filter(b => b && typeof b.name === 'string' && b.name.trim())
        .map(b => ({ name: b.name, note: b.note || '', selected: b.selected !== false, relations: b.relations || {} }));
      const targets = [...new Set([config.target, ...(Array.isArray(config.targets) ? config.targets : [])].filter(x => typeof x === 'string' && x))];
      let target = targets[0] || '', decision = '', text = '';
      const area = document.createElement('div');
      const requestSync = () => queueMicrotask(() => { if (area.isConnected) sync(); });
      area.className = 'ask-merge';
      const history = [];
      let addDraft = '', addError = '', addFocus = false;
      const make = (tag, cls, label) => { const el = document.createElement(tag); if (cls) el.className = cls; if (label != null) el.textContent = label; return el; };
      const button = (label, action, disabled = false) => { const b = make('button', 'option-btn', label); b.type = 'button'; b.disabled = disabled; b.onclick = action; return b; };
      function answer() {
        return { decision, branches: branches.filter(b => b.selected).map(b => ({name: b.name, note: b.note})), target, text: text.trim() };
      }
      function sync() {
        const value = answer();
        answers[qi] = decision && (decision !== 'approve' || value.branches.length) && (decision !== 'reject' || value.text) ? JSON.stringify(value) : null;
        updateVisibility();
        checkAllAnswered();
      }
      function record(message) { history.unshift(message); }
      function draw() {
        area.replaceChildren();
        const top = make('div', 'ask-merge-row');
        top.append(make('strong', '', '合并到 ← 目标分支'));
        const select = make('select', 'option-custom-input'); select.setAttribute('aria-label', '目标分支');
        targets.forEach(tgt => { const opt = make('option', '', tgt); opt.value = tgt; select.append(opt); });
        select.value = target; select.onchange = () => { target = select.value; record('目标切换：' + target); draw(); sync(); }; top.append(select); area.append(top);
        const bulk = make('div', 'ask-merge-row');
        if (enabled('select')) {
          bulk.append(button('全选', () => { branches.forEach(b => b.selected = true); record('全选'); draw(); sync(); }), button('全不选', () => { branches.forEach(b => b.selected = false); record('全不选'); draw(); sync(); }));
        }
        area.append(bulk);
        const list = make('div', 'ask-merge-list');
        let dragging = null;
        branches.forEach((branch, index) => {
          const row = make('div', 'ask-merge-item');
          row.draggable = enabled('reorder');
          row.ondragstart = e => { dragging = index; row.classList.add('dragging'); e.dataTransfer.effectAllowed = 'move'; };
          row.ondragend = () => { dragging = null; row.classList.remove('dragging'); };
          row.ondragover = e => { if (dragging !== null) e.preventDefault(); };
          row.ondrop = e => { e.preventDefault(); if (dragging === null || dragging === index) return; const moved = branches.splice(dragging, 1)[0]; branches.splice(index, 0, moved); dragging = null; record('调整顺序：' + moved.name); draw(); sync(); };
          const head = make('div', 'ask-merge-row');
          if (enabled('reorder')) {
            const handle = make('span', 'ask-merge-handle', '⋮⋮');
            handle.addEventListener('pointerdown', e => {
              if (e.pointerType !== 'touch') return;
              e.preventDefault(); handle.setPointerCapture(e.pointerId); row.classList.add('dragging');
            });
            handle.addEventListener('pointerup', e => {
              if (e.pointerType !== 'touch') return;
              row.classList.remove('dragging');
              const targetRow = document.elementFromPoint(e.clientX, e.clientY)?.closest('.ask-merge-item');
              const dest = Array.from(list.children).indexOf(targetRow);
              if (dest < 0 || dest === index) return;
              const moved = branches.splice(index, 1)[0]; branches.splice(dest, 0, moved);
              record('调整顺序：' + moved.name); draw(); sync();
            });
            handle.addEventListener('pointercancel', () => row.classList.remove('dragging'));
            head.append(handle);
          }
          head.append(make('span', '', String(index + 1)));
          // 勾选面 = 本仓既有的 role="checkbox" 按钮（messages.js `attachCheck` /
          // 多选卡 `.option-check` 同一形态），不新画一套 input[type=checkbox]：
          // toggle-unify 门禁 T4b 判「checkbox 冒充开关」，同一文件里另有
          // `-thumb` 类名（附件缩略图）时会误判成第二套开关。
          const check = make('button', 'option-check ask-merge-check');
          check.type = 'button';
          check.setAttribute('role', 'checkbox');
          check.setAttribute('aria-checked', branch.selected ? 'true' : 'false');
          check.disabled = !enabled('select');
          check.setAttribute('aria-label', '选择 ' + branch.name);
          check.onclick = () => {
            branch.selected = !branch.selected;
            record((branch.selected ? '放行：' : '排除：') + branch.name);
            draw(); sync();
          };
          head.append(check, make('strong', 'ask-merge-name', branch.name));
          const relation = branch.relations[target] || {};
          // fail-silent（作者 11-21 17:56）：契约外 / 空值 / 缺失的关系数据不渲染标签——
          // 不再以「未知」顶替（旧口径会把调用方传错键的分支也标成「未知」）。
          if (typeof relation.status === 'string' && relation.status.trim()) {
            head.append(make('span', 'ask-merge-status', relation.status));
          }
          if (enabled('delete')) head.append(button('删除', () => { branches.splice(index, 1); record('删除：' + branch.name); draw(); sync(); }));
          row.append(head);
          if (relation.conflict) row.append(make('div', 'ask-merge-conflict', '冲突：' + relation.conflict));
          const controls = make('div', 'ask-merge-row');
          const note = make('input', 'option-custom-input'); note.value = branch.note; note.placeholder = '备注或前提条件'; note.setAttribute('aria-label', branch.name + ' 备注'); note.disabled = !enabled('note'); note.oninput = () => { branch.note = note.value; sync(); }; controls.append(note);
          if (enabled('reorder')) {
            const move = dest => { const moved = branches.splice(index, 1)[0]; branches.splice(dest, 0, moved); record('调整顺序：' + moved.name); draw(); sync(); };
            controls.append(button('↑', () => move(index - 1), index === 0), button('↓', () => move(index + 1), index === branches.length - 1));
          }
          row.append(controls); list.append(row);
        });
        area.append(list);
        if (enabled('add')) {
          // 添加行（作者 11-21 17:56「添加条目完全不可用」修复）：输入值存闭包 addDraft，
          // 每次 draw() 重建 DOM 时回填，不丢字；Enter 与按钮同路径；空值 / 重复给出
          // 可见提示（旧口径静默 return = 「点了没反应」）；成功后焦点回到输入框便于连加。
          const addRow = make('div', 'ask-merge-row');
          const input = make('input', 'option-custom-input');
          input.placeholder = '输入或选择分支'; input.setAttribute('aria-label', '添加分支'); input.value = addDraft;
          input.oninput = () => { addDraft = input.value; if (addError) { addError = ''; area.querySelector('.ask-merge-add-error')?.remove(); } };
          bindImeGuard(input);
          input.onkeydown = e => { if (commitEnter(e, input)) addNow(); };
          const datalist = make('datalist'); datalist.id = 'ask-merge-candidates-' + qi + '-' + Math.random().toString(36).slice(2); input.setAttribute('list', datalist.id);
          (Array.isArray(config.candidates) ? config.candidates : []).filter(c => typeof c === 'string' && !branches.some(b => b.name === c)).forEach(c => { const opt = make('option'); opt.value = c; datalist.append(opt); });
          addRow.append(input, datalist, button('添加条目', addNow)); area.append(addRow);
          if (addError) area.append(make('div', 'ask-merge-conflict ask-merge-add-error', addError));
        }
        const comment = make('textarea', 'option-custom-input'); comment.value = text; comment.placeholder = '拒绝理由或补充意见'; comment.setAttribute('aria-label', '理由或补充意见'); comment.oninput = () => { text = comment.value; const hint = area.querySelector('.ask-merge-hint'); if (hint) hint.textContent = decision === 'reject' && !text.trim() ? '拒绝必须填写理由' : '选择回复后，点击下方确认发送。'; sync(); }; area.append(comment);
        const actions = make('div', 'ask-merge-row');
        [['approve','同意'],['reject','拒绝'],['supplement','补充'],['defer','暂缓']].forEach(([value,label]) => {
          const b = button(label, () => { decision = value; record('选择：' + label); draw(); sync(); }, value === 'approve' && !branches.some(x => x.selected));
          if (decision === value) b.classList.add('picked'); actions.append(b);
        }); area.append(actions);
        const hint = make('div', 'ask-merge-hint', decision === 'reject' && !text.trim() ? '拒绝必须填写理由' : '选择回复后，点击下方确认发送。'); area.append(hint);
        if (history.length) area.append(make('div', 'ask-merge-hint', '操作历史：' + history.join('；')));
        // 焦点回位：querySelector 给的是 Element，.focus() 只在 HTMLElement 上（js-types 闸零容忍）。
        if (addFocus) { addFocus = false; const field = area.querySelector('input[aria-label="添加分支"]'); if (field instanceof HTMLInputElement) field.focus(); }
      }
      // 添加分支（同路径：按钮 / Enter）。空值、重复给出提示，不静默吞掉。
      function addNow() {
        const name = addDraft.trim();
        if (!name) { addError = '请输入分支名'; addFocus = true; draw(); return; }
        if (branches.some(b => b.name === name)) { addError = '「' + name + '」已在列表中'; addFocus = true; draw(); return; }
        branches.push({ name, note: '', selected: true, relations: {} });
        addDraft = ''; addError = ''; addFocus = true;
        record('添加：' + name); draw(); sync();
      }
      draw(); wrapper.append(area); box.appendChild(wrapper); requestSync(); return;
    }

    if (item.secret === true) {
      const row = document.createElement('div');
      row.className = 'option-secret-row';
      const pw = document.createElement('input');
      pw.type = 'password';
      pw.className = 'option-secret-input';
      pw.autocomplete = 'off';
      pw.spellcheck = false;
      pw.placeholder = t('askUser.secretPlaceholder');
      const eye = document.createElement('button');
      eye.type = 'button';
      eye.className = 'option-secret-eye';
      eye.innerHTML = eyeSvg;
      eye.setAttribute('aria-label', t('askUser.secretShow'));
      eye.setAttribute('aria-pressed', 'false');
      eye.title = t('askUser.secretShow');
      eye.onclick = () => {
        const show = pw.type === 'password';
        pw.type = show ? 'text' : 'password';
        eye.innerHTML = show ? eyeOffSvg : eyeSvg;
        eye.setAttribute('aria-pressed', String(show));
        const label = show ? t('askUser.secretHide') : t('askUser.secretShow');
        eye.setAttribute('aria-label', label);
        eye.title = label;
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
          const mediaPreview = preview && askPreviewKind(preview) === 'media';
          if (preview) btn.classList.add('has-preview');
          const check = document.createElement('span');
          check.className = 'option-check';
          btn.appendChild(check);
          if (mediaPreview) {
            btn.classList.add('has-media');
            btn.appendChild(buildOptionMediaBody(preview, label, desc));
          } else {
            const pvNode = buildOptionPreviewEl(preview);
            if (pvNode) btn.appendChild(pvNode);
            fillOptionText(btn, label, desc, true);
          }
          btn.onclick = () => {
            btn.classList.toggle('picked');
            syncMulti(qi);
            updateVisibility();
            checkAllAnswered();
          };
        } else {
          btn.dataset.label = label;
          const preview = typeof opt === 'object' && opt !== null ? opt.preview : null;
          const pvNode = buildOptionPreviewEl(preview);
          if (preview) btn.classList.add('has-preview');
          if (preview && askPreviewKind(preview) === 'media') {
            btn.classList.add('has-media');
            btn.appendChild(buildOptionMediaBody(preview, label, desc));
          } else if (pvNode) {
            btn.appendChild(pvNode);
            fillOptionText(btn, label, desc, true);
          } else {
            fillOptionText(btn, label, desc, false);
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
          pendingOtherMount = true; // askuser-otherattach 批：草稿恢复 = 已处 Other 态（挂载点统一落挂）
        } else {
          customInput.style.display = 'none';
        }
        otherBtn.onclick = () => {
          otherBtn.classList.toggle('picked');
          if (otherBtn.classList.contains('picked')) {
            customInput.style.display = '';
            customInput.focus();
            mountOtherAttachments(); // askuser-otherattach 批：Other 自由输入态挂附件区
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
          pendingOtherMount = true; // askuser-otherattach 批：草稿恢复 = 已处 Other 态（挂载点统一落挂）
        } else {
          customInput.style.display = 'none';
        }
        otherBtn.onclick = () => {
          optsDiv.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
          otherBtn.classList.add('picked');
          customInput.style.display = '';
          customInput.focus();
          mountOtherAttachments(); // askuser-otherattach 批：Other 自由输入态挂附件区
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
          mountOtherAttachments(); // askuser-otherattach 批：键入即进 Other 态 ⇒ 同款挂载
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
  // askuser-dragdrop 实施批（2026-10-19，批 #551）：答复附件面。只在**活卡**（有
  // onConfirm 的 live 腿；历史腿传 null）+ 有 requestId + 入口判据（`mounted` ——
  // 只给开放题；secret/dirPicker/选择题卡一律静默无入口，作者修正①②）下挂载。
  // 历史/重放腿的只读附件行仍走 renderAskAttachments（单点不动）。
  const answerAttachMounted = !!(onConfirm && requestId && answerAttachmentsAllowed(questions));
  if (answerAttachMounted) {
    answerAttach = mountAnswerAttachments(box, { requestId, onChange: () => checkAllAnswered() });
  }
  // askuser-otherattach 批：草稿恢复腿置旗 ⇒ 此处统一落挂（btnRow 已构建、插位正确）。
  if (pendingOtherMount) mountOtherAttachments();
  confirmBtn.onclick = () => {
    // askuser-dragdrop 批（作者修正⑤·设计稿 §2.7）：有失败 chip ⇒ toast 且不发送
    // （上传中的 chip 已由发送钮置灰覆盖；失败不阻塞文本输入与其它 chip）。
    if (answerAttach && answerAttach.hasFailed()) {
      window.__showToast?.(t('askUser.attachSendBlocked'));
      return;
    }
    // askuser-dragdrop 批：随帧携带**已落盘**附件引用（只含 done 件；无附件 ⇒ 空数组
    // ⇒ 帧与改前逐字节同形）。
    const answerAttachments = answerAttach ? answerAttach.attachments() : [];
    // Lock the card: drop it from the Canvas answer registry (E6: late
    // _nfAskAnswer postMessages find no entry and are silently discarded).
    // #250 ④: only THIS card's entry — sibling pending cards keep their channel.
    if (askSessionId) dropAskCard(askSessionId, requestId);
    if (requestId) dirPickCards.delete(requestId); // 工作区选择卡事件一并失主
    // 已决 ⇒ 共用层单一闸（工作区选择卡的 `.ws-pick-target` / 卡片空白区在此一并失效）
    lockOptionBox(box);
    // askuser-dragdrop 批：附件面转只读并整区撤下（同一批文件随后由
    // renderAskAttachments 以**与历史恢复腿同一张脸**渲染只读行 ⇒ live 与历史逐字
    // 同构，禁同一批文件双渲染面）。
    if (answerAttach) {
      answerAttach.lock();
      const zone = box.querySelector('.ask-answer-attach-zone');
      if (zone) zone.remove();
      const dropOverlay = box.querySelector('.ask-answer-drop-overlay');
      if (dropOverlay) dropOverlay.remove();
    }
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
    // askuser-dragdrop 批（设计稿 §2.8）：作答行附件计数（行内带计数利于检索）+
    // 「已回答 · 附 N 个文件」徽标 + 只读附件行（与历史恢复腿同一渲染器）。
    if (answerAttachments.length) {
      const countLine = document.createElement('div');
      countLine.className = 'option-answer';
      countLine.style.cssText = 'color: var(--color-text-muted, #888); font-size: var(--ds-fs-hint);';
      countLine.textContent = t('askUser.attachAnswerCount', { n: String(answerAttachments.length) });
      box.appendChild(countLine);
      mountAnsweredBadge(box, answerAttachments.length);
      renderAskAttachments(box, answerAttachments.map(a => a.path), '', { llmQuestion: false });
    }
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
    if (onConfirm) onConfirm(finalAnswers, secretSlots, answerAttachments);
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
    // askuser-dragdrop 实施批（2026-10-19，批 #551）：发送闸三段——
    //   ① 基础闸（逐字保留）：可见题都已作答；
    //   ② 附件补充满足（仅**零选项卡**）：≥1 个已附 chip 且全部题无 options
    //      （作者修正①：选择题卡不显示附件入口，附件也不替代选项——设计稿 §2.5）；
    //   ③ 上传中 ⇒ 置灰 + 文案「上传中…」（任何在传 chip 都阻塞发送）。
    const baseOk = questions.every((_, qi) => !shouldShow(qi) || isAnswered(qi));
    const zeroOptions = !questions.some(q => q && q.options && q.options.length > 0);
    const attachOk = !!(answerAttach && zeroOptions && !questions.some(q => q && q.merge) && answerAttach.satisfyGate());
    const uploading = !!(answerAttach && answerAttach.uploading());
    confirmBtn.disabled = uploading || !(baseOk || attachOk);
    const labelSpan = confirmBtn.querySelector('span');
    if (labelSpan) labelSpan.textContent = uploading ? t('askUser.attachUploadingLabel') : confirmLabel;
  }
  // 交出卡盒（视觉返工批 2026-11-19 19:38）：审批卡要把三要素插进**本卡**内容流
  // （问句之前），而卡盒就在这里建成 ⇒ 返回它，免调用方二次查询 DOM。
  return box;
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

/** AskUser **附件行**的可达性探针（askuser-attach-replyrender 批 2026-10-30；
 *  与上方 `probeCanvasFile` 比较页探针**分治**——那个的开 Canvas 副作用恰是
 *  它要的（E8 按钮 ⇔ Canvas 页），附件行则恰恰相反）。
 *
 *  修前附件行复用 `pop.readFile` 探针（askuser-attach-align 批「复用 canvas 腿
 *  同一件」）——探针应答 `fileContent` 被 explorer.js 的**全局** onMessage 处理器
 *  接住并无条件重派 `workspace-open-item` ⇒ **卡一渲染就自动跳 Canvas 标签页**
 *  （作者报障「我回复你的附件……要跳 Canvas 页」的机制根因；已决卡点击哨兵豁免
 *  与否都挡不住这条全局腿）。本批弃用该探针。
 *
 *  新探针 = **下载/内联同一条票据轨**：`nfTicket.queuedTicketUrl` 铸票 ⇒
 *  `GET /api/nf-file` 带 `Range: bytes=0-0` 取首字节（服务端 Range 腿既有，
 *  206/200 = 可读；404/401/403 = 不可读）。与本行真实消费腿同源 ⇒ 「探针过但
 *  打不开」的口径漂移归零；铸票走 queuedTicketUrl 合帧出口 ⇒ 同行 N 件 ≤1 次
 *  铸票，且与内联图取数命中同一 per-path 票缓存。 */
async function probeAttachmentFile(path) {
  try {
    const { queuedTicketUrl } = await import('./nfTicket.js');
    const url = await queuedTicketUrl(path);
    if (!url) return { ok: false, reason: 'unsupported' };
    const resp = await fetch(url, { headers: { Range: 'bytes=0-0' } });
    if (resp.ok) return { ok: true, reason: 'ok' };
    // 作者实拍 IMG_1725 ⑤a（「不可用」三个字既无原因也无动作）：不可读**给原因档**，
    // 三档正好覆盖作者现场的三种成因（会话未登录 / 文件已迁走 / 网络不可达）。
    if (resp.status === 401 || resp.status === 403) return { ok: false, reason: 'denied' };
    if (resp.status === 404 || resp.status === 410) return { ok: false, reason: 'missing' };
    return { ok: false, reason: 'network' };
  } catch { return { ok: false, reason: 'network' }; }
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

/** 来源标签文本（ui-unification-20261103-askuser-attach 批）：文件卡左上角
 *  「AskUser · <问题摘要/短 id>」（作者令原话例「AskUser · 图片缩放确认」）。
 *  摘要 = 首问剥标签文本前 14 字（长问截断进 title——完整摘要在卡面本就可见）；
 *  无问题文本时回退 requestId 尾段（短 id 档——作者令给出的第二选项）。 */
function askAttOriginLabel(items, requestId) {
  const q = askUserCardQuestionText(items);
  const head = q ? q.slice(0, 14) : (requestId ? String(requestId).slice(-6) : '');
  return head ? 'AskUser · ' + head : 'AskUser';
}

/** AskUser 答复附件**不再开 Canvas**（askuser-attach-replyrender 批 2026-10-30，
 *  作者令「我回复你的附件，不需要在 Canvas 打开」）。修前形态 = 注册表认领的类型
 *  一律 `openAttachmentInCanvas` 跳 Canvas 标签页（看一个附件多开一个标签）；本批起
 *  该腿退役并整函数移除 —— 图片走内联/lightbox（`makeImageRow`），其余走下载
 *  （`downloadAttachmentFile`）。禁第二套开法。 */

/** 未注册类型的**下载兜底**（askuser-attach-align 2026-10-07 作者令）：与设备面
 *  下载键同一字节通路 —— `nfTicket.ticketUrl`（per-path 短票）⇒ `/api/nf-file`
 *  整件取回 ⇒ blob 交给浏览器下载管理器。本函数**不新开取数面**（同通路纪律）；
 *  返回 false（铸票/取回失败）时调用方就地降级为静态行（可见降级，禁静默）。
 *
 *  askuser-attach-replyrender 批（2026-10-30，作者令「我回复你的附件，不需要在
 *  Canvas 打开」）：下载腿从「未认领类型的兜底」升格为**全部非图片附件的默认开法**。 */
async function downloadAttachmentFile(path, base) {
  try {
    const { ticketUrl } = await import('./nfTicket.js');
    const resp = await fetch(await ticketUrl(path));
    if (!resp.ok) return false;
    const blob = await resp.blob();
    // mobile-download 批（2026-11-18 作者令）：落点收归单点——手机层图片/视频走系统
    // 分享（可入相册）/ 其它分享「存储到文件」（可指定位置）；能力不支持 ⇒ 原
    // `<a download>` + 落点提示。桌面层逐字保留原行为。
    const landed = await saveFileBlob(blob, base || 'attachment');
    notifyLanding(landed);
    return true;
  } catch { return false; }
}

/** 附件行的**单一渲染位**（整次提问层；`renderAskUser` / `renderAskUserHistory` 唯二调用方）。
 *
 *  §三 落点 = 卡内 `.option-q-wrapper` 之内、`.option-btn-row` 之前：本函数自建一个
 *  承载块并复用**既有类名** `.option-q-wrapper`（纯 JS 钩子），插在 `box.children`
 *  里的 `.option-btn-row` 之前。该块**不**登记进 `createDepVisibility` ⇒ 末条问题被
 *  条件隐藏时附件行不随之消失。挂载序号在**全部问题 wrapper 之后** ⇒
 *  `markAnsweredPick` 的 `wrappers[qi]`（按问题下标取）不受影响。
 *
 *  形态（attachment-thumb-unify 批 2026-11-05 12:57，作者令原话「附件这里添加的
 *  图片文件等应该用我们输入框同款的小图标/缩略图」；被本批取代的形态 =
 *  ui-unification-20261103-askuser-attach 2026-11-04 的 220px 卡语言媒体卡）：
 *   · 图片 ⇒ **与输入框附件条逐值同盒的小缩略图** `.att-thumb`（28px 档 / 同圆角
 *     / 同描边；渲染单源 = `renderAttachmentFace` 图片档，禁第二套媒体面）。点击
 *     = 灯箱原图（`openMediaLightbox` 单点，零 Canvas 依赖——10-30 作者令「不需要
 *     在 Canvas 打开」；`path` 在 ⇒ 灯箱内另给 Canvas 次级入口）。
 *   · 其余全部类型（含视频/pdf/zip/md）⇒ 文件 chip（`renderAttachmentFace` —— 与
 *     输入框附件条 `renderAttachmentPreviewInner` **同一函数**的同一张脸）：类型
 *     小图标（`.fc-icon` 色族单源）+ 文件名（可见省略号截断，不靠渐隐）。
 *   · 来源标签（`ds-att-origin`）= 标题行一处（不再每枚 chip 挂 notch：28px chip
 *     装不下 14px notch，压图且行间必撞）。
 *   · 一条 `flex-wrap: wrap` 窄条带（间距/换行全走 --ds-space-* token）：默认只显
 *     前 3 枚 + 「共 N 个」展开（卡身不随件数线性膨胀）。
 *   · 探针判定不可读 ⇒ **降级态**：图片先换同款文件 chip，再落 名字 + **原因档**
 *     （401/403 无权限 / 404/410 已不存在 / 其余网络不可达——「不可用」三字裸奔
 *     形态退役）+ 「重试下载」真动作（不净死）。下载链出错同样落该降级态。
 *  文案一律取自 `t()`（🔴 禁硬编码中文串入 JS）。
 *
 *  @param {HTMLElement} box 卡根（.option-box）
 *  @param {string[]|{path:string}[]} attachments 附件绝对路径清单
 *  @param {string} [originLabel] 来源标签文本（问题摘要腿；缺省回退 requestId 尾段） */
function renderAskAttachments(box, attachments, originLabel, opts = {}) {
  const atts = Array.isArray(attachments)
    ? attachments.filter(p => typeof p === 'string' ? p.length > 0 : !!(p && p.path))
    : [];
  if (!box || atts.length === 0) return;
  const paths = atts.map(p => typeof p === 'string' ? p : p.path);
  const btnRow = box.querySelector('.option-btn-row');
  if (!btnRow) return; // 形态兜底：无按钮行就无插位（改前形态逐字保留）

  const wrap = document.createElement('div');
  // 既有类名 + 本批钩子类（纯 JS 选择器，形在 css/chat.css / mobileLayer.css 单点）
  wrap.className = 'option-q-wrapper ask-attachments-wrap';

  const head = document.createElement('div');
  head.className = 'ask-attachments-head';
  // 标题词位 = 独立 span（文案读数不被折叠控件文本污染）。形（字阶/字重/色/边距）
  // 全在 css/chat.css（本批把原内联 cssText 收编：内联 font 简写会压过手机层的
  // 字阶覆盖，body.mobile-layer 的 --mfs-min 档此前一直是死规则）。
  const label = document.createElement('span');
  label.className = 'ask-attachments-head-label';
  label.textContent = t('askUser.attachmentsTitle') + ' · ' + paths.length;
  head.appendChild(label);
  // 来源标签：本批**从「每枚 chip 左上角 notch」收拢到标题行一处** —— chip 本批
  // 缩到 28px 档（输入框同款），14px 的 notch 会盖住缩略图上半并被下一行叠压；
  // 信息不丢（仍是「AskUser · <问题摘要>」），位置从压字的绝对定位变成标题行内联。
  const originText = originLabel || '';
  if (originText) {
    const tag = document.createElement('span');
    tag.className = 'ds-att-origin';
    tag.textContent = originText;
    tag.title = originText;
    head.appendChild(tag);
  }

  const list = document.createElement('div');
  list.className = 'ask-attachments-list';

  // ── 图/文件同一条带（输入框附件条的同一套视觉语言）────────────────────
  // 图片 ⇒ `.att-thumb` 小缩略图（28px 档，与输入框条逐值同盒；点击 = 灯箱原图）；
  // 其余（含视频/pdf/zip/md）⇒ `.att-file` 类型小图标 + 文件名 chip（同一函数）。
  // 两档都是 `renderAttachmentFace` 的同一张脸 ⇒ 卡区零第二份附件实现。
  /** @type {Array<{path:string, base:string, index:number, kind:string,
   *                el?:HTMLElement}>} 本枚的在场元素（降级时就地换脸要寻址） */
  const items = paths.map((path, i) => ({
    path, base: askAttachmentBasename(path), index: i, kind: itemTypeForFileName(path),
  }));
  items.forEach((item, i) => {
    const el = opts.llmQuestion ? attachQuestionFace(item) : (item.kind === 'image' ? attachThumbFace(item) : attachFileFace(item));
    if (i >= ASK_ATTACHMENT_FOLD_AT) el.classList.add('ask-att-folded');
    item.el = el;
    list.appendChild(el);
    // 可达性探针（票据轨首字节，probeAttachmentFile 单点）：失败 = 就地降级为
    // 同款文件 chip（原因档 + 重试下载键），只降级本枚。
    probeAttachmentFile(item.path).then(res => { if (res && !res.ok) degradeAskAttachment(item, res.reason); });
  });

  /** LLM 提问卡附件：复用 Pop 文件卡与 Canvas 唯一打开入口。探测在位且成功后才开，
   *  单卡动作不派下载或 lightbox；权限失败沿既有就地降级 + 重试路径。 */
  function attachQuestionFace(item) {
    const activate = async () => {
      const result = await probeAttachmentFile(item.path);
      if (!result || !result.ok) { degradeAskAttachment(item, result && result.reason || 'network'); return; }
      openPopArtifact(item.path, item.base);
    };
    const card = buildFileCard({ path: item.path, name: item.base, ext: extOf(item.base) }, {
      onOpen: activate,
      trailing: null,
    });
    card.classList.add('ask-att-question-card');
    card.dataset.askAttPath = item.path;
    card.tabIndex = 0;
    card.setAttribute('role', 'button');
    card.setAttribute('aria-label', item.base);
    card.addEventListener('keydown', e => {
      if (e.target === card && (e.key === 'Enter' || e.key === ' ')) {
        e.preventDefault();
        activate();
      }
    });
    return card;
  }

  /** 图片档：输入框同款小缩略图（渲染单源 = renderAttachmentFace 图片档）。
   *  点击 = 灯箱单点（`path` 在 ⇒ 灯箱内另给 Canvas 次级入口）；缩略帧字节与
   *  灯箱同走 nfFileCache 账本 ⇒ 点开看原图不再二次抓取。 */
  function attachThumbFace(item) {
    return renderAttachmentFace({ name: item.base, path: item.path }, {
      className: 'ask-att-thumb',
      ariaLabel: item.base,
      onClick: () => openMediaLightbox({ kind: 'image', name: item.base, path: item.path }),
    });
  }

  /** 非图片档：类型小图标 + 文件名 chip（截断走可见省略号，不靠渐隐）。 */
  function attachFileFace(item) {
    const chip = renderAttachmentFace({ name: item.base }, {
      className: 'ask-att-file',
      ariaLabel: t('askUser.attachmentDownload', { name: item.base }),
      onClick: () => { downloadAttachmentFile(item.path, item.base).then(ok => { if (!ok) degradeAskAttachment(item, 'network'); }); },
    });
    chip.dataset.askAttPath = item.path;
    return chip;
  }

  if (items.length > ASK_ATTACHMENT_FOLD_AT) {
    // 折叠控件：文案 = 「共 N 个」（件数真相）；展开后全部可见。真控件（确实可切换）。
    let expanded = false;
    const foldBtn = document.createElement('button');
    foldBtn.type = 'button';
    foldBtn.className = 'glass-control ask-att-fold-btn';
    foldBtn.style.cssText = 'font-size: var(--ds-fs-meta); padding: var(--ds-space-half) var(--ds-space-inline-x);';
    foldBtn.setAttribute('aria-expanded', 'false');
    foldBtn.textContent = t('askUser.attFoldTotal', { n: String(items.length) });
    foldBtn.onclick = () => {
      expanded = !expanded;
      list.querySelectorAll('.ask-att-folded').forEach(el => el.classList.toggle('ask-att-folded', !expanded));
      foldBtn.textContent = expanded ? t('ref.collapse') : t('askUser.attFoldTotal', { n: String(items.length) });
      foldBtn.setAttribute('aria-expanded', String(expanded));
    };
    list.appendChild(foldBtn);
  }

  /** 就地降级：不可读件 → 文件 chip 形态（原因档 + 重试下载真动作——「不可用」
   *  裸奔退役）。图片档先由缩略帧就地换成同款 chip（同一函数同一张脸），再落
   *  降级态 ⇒ 不新增第二套降级面。卡已被替换/移除（isConnected=false）时静默跳过。 */
  function degradeAskAttachment(item, reason) {
    let chip = item.el;
    if (!chip || !chip.isConnected) return;
    if (chip.classList.contains('ask-att-thumb')) {
      const next = attachFileFace(item);
      next.classList.toggle('ask-att-folded', chip.classList.contains('ask-att-folded'));
      chip.replaceWith(next);
      chip = next;
      item.el = next;
    }
    chip.classList.add('ask-att-unavailable');
    let note = chip.querySelector('.ask-att-reason');
    if (!note) {
      note = document.createElement('span');
      note.className = 'ask-att-reason';
      // 两档名字类：缩略/chip 面 = .att-file-name；问面文件卡（buildFileCard）= .fc-name。
      // 都不命中（结构兜底）⇒ 直接挂卡尾——原因档不得因名字类换脸而消失（⑤a「原因+动作」）。
      const name = chip.querySelector('.att-file-name, .fc-name');
      if (name) name.after(note);
      else chip.appendChild(note);
    }
    const why = t('askUser.attUnavailable_' + (reason || 'network'));
    note.textContent = why + ' · ' + t('askUser.attCanDownload');
    let retry = chip.querySelector('.ask-att-retry');
    if (!retry) {
      retry = document.createElement('button');
      retry.type = 'button';
      retry.className = 'ask-att-retry glass-control';
      retry.textContent = t('askUser.attRetryDownload');
      retry.onclick = (e) => {
        e.stopPropagation();
        downloadAttachmentFile(item.path, item.base).then(ok => {
          if (!ok) {
            retry.textContent = t('askUser.attDownloadFailed');
            window.__showToast?.(t('askUser.attachUploadFailedToast', { name: item.base }), 'error');
          } else {
            chip.classList.remove('ask-att-unavailable');
            note.remove();
            retry.remove();
          }
        });
      };
      chip.appendChild(retry);
    }
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
    senderRole: 'askUser', senderName: agentName ? agentDisplayName(agentName) : rootDisplayName(), ts: Date.now(),
  });
  // Ghost seat (askuser-hybrid 重皮 2026-10-06): this bubble's only payload is
  // the AskUser card (+ source badge / optional canvas button) — the bubble
  // de-faces so the card floats directly on the paper. Content-bearing bubbles
  // (finishAi re-attaches a preserved box under streamed markdown) keep their
  // face — the marker is deliberately an explicit class, never a blanket :has().
  const bubble = document.createElement('div');
  bubble.className = mainBubbleClass('ai', 'ob-ghost-seat');
  row.appendChild(bubble);
  // Source badge. D6 批 F1 (G9): project context (project node / dispatcher ask)
  // renders "project · nodeName" so concurrent asks from several nodes stay
  // attributable; no project field → bare agentName (root self-ask / REPL).
  // 归属命名批（2026-11-05 作者令「卡片要显示 delegate name / node name，不再只
  // 显示项目名」）：段① 换成发起实体**自身名**（delegate 批次短名，引擎侧解析随
  // 载荷 `ownerName` 下发；无 delegate 归属仍回落项目名）——两段各自成 span，
  // 段①弱化为「父批」、段②加粗为「当前节点」，窄屏折两行也不丢段。
  const badgeParts = askSourceParts({
    project: source && source.project,
    nodeName: source && source.nodeName,
    ownerName: source && source.ownerName,
    agentName
  });
  // N4 (unnamed-state batch 2026-10-06): the self-ask badge mounts only when the
  // root is NAMED — single-point `isRootNamed()` (rootName.js). The retired
  // `badgeLabel !== 'Nebula'` literal was a judgement-face use of the mechanism
  // name (the exact line rootName.js's header draws): it silently dual-tracked
  // the unnamed state AND mis-hid the badge when a user literally named the
  // agent "Nebula". Project asks keep their badge regardless of named state.
  // 归属命名批同上：delegate 归属（ownerName）与项目归属同权——**不因根未命名而
  // 隐藏**（改前判据只认 project ⇒ delegate 卡的归属会整块消失）。
  if (badgeParts && ((source && (source.project || source.ownerName)) || isRootNamed())) {
    const badge = document.createElement('div');
    badge.className = 'ask-user-source';
    const parentSpan = document.createElement('span');
    parentSpan.className = 'ask-user-source-parent';
    parentSpan.textContent = badgeParts.parent;
    badge.appendChild(parentSpan);
    if (badgeParts.node) {
      const sep = document.createElement('span');
      sep.className = 'ask-user-source-sep';
      sep.setAttribute('aria-hidden', 'true');
      sep.textContent = ' · ';
      badge.appendChild(sep);
      const nodeSpan = document.createElement('span');
      nodeSpan.className = 'ask-user-source-node';
      nodeSpan.textContent = badgeParts.node;
      badge.appendChild(nodeSpan);
    }
    // 完整名可查（长名截断后不丢信息）：hover 全量 title；窄屏/触屏点按展开
    // （长按同源——触屏长按会触发 title 的系统浮层，桌面悬停即读）。
    badge.title = badgeParts.full;
    if (badgeParts.node) {
      badge.setAttribute('role', 'button');
      badge.tabIndex = 0;
      const toggle = () => badge.classList.toggle('is-expanded');
      badge.addEventListener('click', toggle);
      badge.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
      });
    }
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
    showOptions(bubble, items, (answers, secretSlots, answerAttachments) => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        // askuser-secret 批（规格 §2 值通道）：密码题的值走 `secretSlots` 专用键
        // （网关检出后直写 secrets——值不进普通 answers 通道、不进 .ui.json）。
        // 无秘密题 ⇒ 与改前逐字节同帧（secretSlots 键缺席）。
        // askuser-dragdrop 批（2026-10-19）：随卡回传的已落盘附件走 `attachments`
        // 可选键（逐键解析向后兼容；空数组 ⇒ 键缺席，帧与改前逐字节同形）。
        state.ws.send(JSON.stringify(askAnswerFrame({
          sessionId: targetSid,
          requestId,
          answers,
          secretSlots,
          answerAttachments,
        })));
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
          // 取消帧**唯一构造器**（askPending.cancelAskFrame）——待办条目侧「取消」
          // 走的是同一个构造器 + 同一个卡片锁定单点（本批：两处取消同收口）。
          state.ws.send(JSON.stringify(cancelAskFrame({ sessionId: targetSid, requestId, by: 'web' })));
        } else {
          // 理论不可达（live 卡恒带 requestId，hub 是唯一 id 源）；无 id 兜底保留
          // 改前哨兵行为，不静默无操作。（同走 askAnswerFrame —— 帧只有一处构造。）
          state.ws.send(JSON.stringify(askAnswerFrame({ sessionId: targetSid, answers: ['__cancelled__'] })));
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
    renderAskAttachments(bubble.querySelector('.option-box'), attachments, askAttOriginLabel(items, requestId), { llmQuestion: true });
    // 感知域增强（mail-perception-impl 批）：OTP 单码片 + 感知草稿快照卡。
    // 单一可选钩子——perception.js 未装载（或未注册）时恒为 no-op，本路径与
    // 改前逐字节同行为；钩子自身零抛出（实现侧 self-contained）。答复由
    // perception.js 走与卡面同形的 askUserAnswer 帧（answers + requestId）。
    if (typeof window.__perceptionEnhanceAskCard === 'function') {
      try { window.__perceptionEnhanceAskCard(bubble, items, requestId, targetSid); }
      catch (e) { console.warn('[askUser] perception enhance skipped:', e); }
    }
  } catch (e) {
    console.error('[askUser] render failed:', e);
    bubble.textContent = t('chat.failedRender');
  }
  // Persisted entry carries the question attachment list with its originating AskUser row.
  return {
    type: 'askUser', items, requestId,
    ...(Array.isArray(attachments) && attachments.length > 0 ? { attachments } : {}),
    ...(source && (source.project || source.ownerName)
      ? { project: source.project, nodeName: source.nodeName, ownerName: source.ownerName }
      : {})
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
  // 溢出转待办批（2026-11-18）：卡被摘除 = 该问题可能就此离开对话分页窗口 ⇒
  // 显式补一次归属重估（不依赖 DOM 观察者的时序；重估本身幂等且防抖环）。
  if (cards.length) notifyAskPlacement();
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
 *  answerAttachments（askuser-dragdrop 实施批 2026-10-19，批 #551）：**作答行**随行
 *  落盘的答复附件清单（`User.answerAttachments`，`UiMessage.askUserAnswer` 单一构造
 *  点；persistence.js 两条历史腿按 `answerOf` 精确寻址取来）。已作答卡 ⇒ 渲染
 *  「已回答 · 附 N 个文件」徽标 + 只读附件行（`renderAskAttachments` 与 live 确认腿
 *  同一渲染器）。缺席 / 未作答 / 已取消 ⇒ 与改前逐字节同形。
 *  🔴 落「无作答记录」的方向 = 「未作答」而非「已作答」：宁可让一张其实已答过的卡
 *  显示待定（重放腿/作答链会纠正），也不许让一张真 pending 卡被画成已答（那正是
 *  重放腿去重判据被击穿、双开首卡恒死的成因）。 */
export function renderAskUserHistory(bubble, items, answerText, requestId, attachments, answerAttachments, source) {
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
  renderAskAttachments(box, attachments, askAttOriginLabel(items, requestId), { llmQuestion: true });
  // askuser-dragdrop 批（2026-10-19，批 #551）：**作答**附件的只读面——只在确有
  // 作答（非取消 / 非「无作答记录」）时渲染：徽标（已回答 · 附 N 个文件）+
  // renderAskAttachments 同一渲染器的只读行。判据 = answerText 为真且非取消哨兵
  // （与下方作答行分支同一判据源）。
  const answerAtts = Array.isArray(answerAttachments)
    ? answerAttachments.filter(a => a && typeof a.path === 'string' && a.path)
    : [];
  const cancelled = answerText === '__cancelled__';
  if (answerText && !cancelled && answerAtts.length) {
    mountAnsweredBadge(box, answerAtts.length);
    renderAskAttachments(box, answerAtts.map(a => a.path), askAttOriginLabel(items, requestId), { llmQuestion: false });
  }
  // 单一判据的形态锚（`sameAskCards` ②）：这张卡来自历史恢复，不是 live 卡。
  // 属性不进 innerHTML ⇒ 与 live 卡的逐字节 parity 断言（history-replay-cards.spec）
  // 不受影响。
  box.dataset.historyReplay = '1';
  box.classList.remove('ob-fade-in'); // settled card — no creation animation on replay
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
export function renderPermissionPrompt(toolName, summary, inputJson, permSessionId, dangerLevel, sourceAgent, sourceSession, sourceTeam, requestId, safetyMode, retargetedFrom, permDetail) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row ai';
  // Ghost seat (askuser-hybrid 重皮): the permission card is an option-box
  // carrier (badge/banner + box only) — the bubble de-faces, same as renderAskUser.
  const bubble = document.createElement('div');
  bubble.className = mainBubbleClass('ai', 'ob-ghost-seat');

  // Danger level decorations
  const level = dangerLevel || 0;

  // 统一处理实时和历史权限卡的命令、危险原因及作用域。
  const detailInfo = permDetailFrom({ toolName, input: inputJson, permDetail });
  const isDangerous = level >= 2;

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
  // 「危险类型」只在卡首横幅出现一次：档位（横幅底色+文案）；危险/严重档的横幅文案
  // 带 {detail}=人话标签，注意档（warning）的文案不带 ⇒ 标签只在 >=2 档被横幅承载，
  // 其余档由判定出处行给出一次（作者令：同一事实卡内只出现一次）。
  const riskLabel = (detailInfo.reason && detailInfo.reason.label)
    || (detailInfo.reason && detailInfo.reason.levelLabel) || '';
  if (dangerConf) {
    bubble.classList.add(dangerConf.cls);
    const banner = document.createElement('div');
    banner.className = 'perm-danger-banner';
    // 横幅只说**命中的规则**（后端判定给的人话标签），命令正文走下方正文块
    // —— 改前这里塞的是整条命令（且因解析失败恒为空），既不全也不该在这行。
    const bannerText = t(dangerConf.i18nKey, { detail: riskLabel });
    banner.innerHTML = dangerConf.icon + '<span>' + escapeHtml(bannerText) + '</span>';
    bubble.appendChild(banner);
  }

  // Source badge: show which sub-agent this permission request came from
  // (with team attribution when the requester is a team agent — #12)
  if (sourceAgent) {
    const sourceBadge = document.createElement('div');
    sourceBadge.className = 'perm-source-badge';
    // 来源标识**完整**（报障批 2026-11-19）：改前 `substring(0, 12)` 把
    // `delegate-stand-ab12cd34` 切成 `delegate-sta`——同名 delegate 之间无法区分，
    // 用户也没法核实「到底是谁在请求」。现给完整 id（CSS 允许折行/悬停看全）。
    const fullSession = sourceSession ? String(sourceSession) : '';
    // Nebula 退役批（作者令 2026-11-01）：来源 badge 的 agent 段走单一显示名读点
    // （根会话 = 用户起的名字 / 未命名 = 通用称呼；后台 agent = 自身身份）。
    const agentLabel = sourceTeam ? `${sourceTeam}/${sourceAgent}` : agentDisplayName(sourceAgent);
    sourceBadge.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 17l9.2-9.2M17 17V7H7"/></svg>' +
      '<span>' + escapeHtml(t('chat.permSource', { agent: agentLabel, session: fullSession })) + '</span>';
    sourceBadge.title = t('chat.permSource', { agent: agentLabel, session: fullSession });
    bubble.appendChild(sourceBadge);
  }

  // permission-landing 批 P2（2026-11-18）：落点重定锚标注。后端把原目标 root 不可达
  // 的卡重定锚到本会话（帧带 retargetedFrom/retargetReason）——卡片照常在对话流内
  // 渲染与作答，但必须**写明原因**（原目标会话短 id），不再退回全局 toast
  //（原生弹窗形态，已随本批整体退役）。
  if (retargetedFrom) {
    const retargetBadge = document.createElement('div');
    retargetBadge.className = 'perm-source-badge';
    // 构建器写 DOM（component-contract R5：不得新增裸 innerHTML 赋值）；
    // textContent 免转义，svg 走 createElementNS。
    const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    arrow.setAttribute('width', '12');
    arrow.setAttribute('height', '12');
    arrow.setAttribute('viewBox', '0 0 24 24');
    arrow.setAttribute('fill', 'none');
    arrow.setAttribute('stroke', 'currentColor');
    arrow.setAttribute('stroke-width', '2');
    const arrowPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    arrowPath.setAttribute('d', 'M5 12h14M13 6l6 6-6 6');
    arrow.appendChild(arrowPath);
    retargetBadge.appendChild(arrow);
    const note = document.createElement('span');
    note.textContent = t('perm.retargetNote', { from: String(retargetedFrom).substring(0, 12) });
    retargetBadge.appendChild(note);
    bubble.appendChild(retargetBadge);
  }

  // 三要素在下方插入原卡内容流，避免重复渲染外框。
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

  // Build the question text（命令正文已在三要素块里完整给出 ⇒ 问句只问「允许谁？」）
  const questionText = t('chat.allowTool', { tool: toolName });

  const allowLabel = t('chat.allow');
  const denyLabel = t('chat.deny');
  // 装饰性说明已删（作者令「去掉冗余说明」）：允许/拒绝只留动作本身。`summary` 只在
  // 卡上**没有任何事实块**（旧帧无 permDetail 且无可派生正文）时作为唯一信息回落 ——
  // 有事实块时它就是同一条调用的复述，不得再出现。
  const hasFacts = !!(detailInfo.command || detailInfo.reason || detailInfo.scope);
  const permOptions = [
    { label: allowLabel, desc: hasFacts ? '' : (summary || '') },
    { label: denyLabel, desc: '' }
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
  // otherAttach:false（permission-landing 批续做 2026-11-18）：权限卡带 requestId 只为
  // 卡盒 data-request-id（超时撤回/关卡帧寻址），**不带** askuser「其他…」态的附件入口
  //（权限作答没有 attachments 通道，挂上去也是投递无门；口径见 showOptions 的
  // otherAttachEligible）。
  const items = [{
    question: questionText,
    options: permOptions,
    otherAttach: false
  }];
  const cardBox = showOptions(bubble, items, (answers) => {
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
  }, undefined, requestId);
  // 事实块插到原卡内容流的最前面（问句之前）：完整调用信息 → 作用域一行 → 判定出处，
  // 再往下才是「允许 Bash？」与选项 —— 先看清事实，再作答。
  // 同一事实只落一处：Write/Edit 的正文（内容 / -+ 行）由本块唯一给出，原先并列的
  // `permissionDetails` 第二份「完整区」已退役（见 permissionDetails.js 删除批注）。
  if (cardBox && cardBox.firstElementChild) {
    renderPermDetail(cardBox, detailInfo, t, { before: cardBox.firstElementChild, labelShown: level >= 2 && !!riskLabel });
  } else if (cardBox) {
    renderPermDetail(cardBox, detailInfo, t, { labelShown: level >= 2 && !!riskLabel });
  }
  // permission-landing 批 P1/P2 续做（2026-11-18）：requestId 走 showOptions 的**第 7 参**
  //（= 卡盒 data-request-id）——超时撤回（hub CloseRequest ⇒ askUserClosed{reason:
  // "caller-withdrew"}，main.js ⇒ closeAskUserCard 按 `.option-box[data-request-id]`
  // 寻址）与作答翻面由此找到本卡，卡不再挂在「没人在听」的位置。
  // 🔴 上一版把 requestId 当第 8 参传（`undefined, undefined, requestId`）：showOptions
  // 只到第 7 参，第 8 参被静默丢弃 ⇒ 权限卡从未被贴标（js-types 的
  // `chat.js(3861,28): error TS2554: Expected 0-7 arguments, but got 8` 就是这条）。
  smartScroll();
}

// ---------- Attachment preview ----------

// ── 发送清空动画（uifix 批 2026-10-22 作者令「附件面打磨一批」之一）────────
// 载荷被消费（发送 / 排队 / 离线入队，input.js 三腿改走 renderAttachmentPreview）
// ⇒ strip 从 N>0 清到 0 的那次重建不再瞬时蒸发：chip 先「渐隐 + 上浮退场」
// （WAAPI，~160ms），落定后才清 DOM —— 行高收缩只发生一步且在视觉退场之后。
// 纪律：prefers-reduced-motion ⇒ 直清（零动画）；WAAPI 不可用 ⇒ 直清；
// 动画窗内来新渲染（用户秒补附件）⇒ dataset 闸作废在播清空、立即重建（禁吞件）。
const ATT_STRIP_CLEAR_MS = 160;

/** @param {HTMLElement} host @param {() => void} done */
function animateStripClear(host, done) {
  const kids = Array.from(host.children);
  const reduced = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!kids.length || reduced || typeof /** @type {any} */ (kids[0]).animate !== 'function') {
    done();
    return;
  }
  let finished = 0;
  const step = () => { finished += 1; if (finished >= kids.length) done(); };
  for (const k of kids) {
    try {
      const a = /** @type {any} */ (k).animate(
        [{ opacity: '1', transform: 'translateY(0) scale(1)' },
         { opacity: '0', transform: 'translateY(-6px) scale(0.96)' }],
        { duration: ATT_STRIP_CLEAR_MS, easing: 'cubic-bezier(0.4, 0, 0.2, 1)', fill: 'forwards' });
      a.addEventListener('finish', step);
      a.addEventListener('cancel', step);
    } catch { step(); }
  }
  // 硬上限：动画事件全哑也不吞清空（done 幂等，dataset 闸防跨渲染误清）。
  setTimeout(done, ATT_STRIP_CLEAR_MS + 120);
}

/**
 * 发送/排队/离线入队消费载荷后的条带清空 —— **渐隐退场动画版**（uifix 批
 * 2026-10-22 作者令「附件面打磨一批：发送后清空动画」）。chip 先「渐隐 + 上浮
 * 退场」（WAAPI ~160ms），落定才清 DOM；行高收缩只在视觉退场后发生一步。
 *
 * 纪律：**只有发送族三腿**（input.js send / queue / enqueueOfflineMessage）
 * 调用本函数；chip ✕ 移除与引用替换走普通 [[renderAttachmentPreview]]
 * （数据变化即时重建，零动画——单件删除延迟落 DOM 会吃掉点击反馈的真实感，
 * 也破坏既有测试的同步读数面）。prefers-reduced-motion / 无 WAAPI ⇒ 直清。
 * @param {{attPreviewEl?: HTMLElement}} [target] 与 renderAttachmentPreview 同形
 */
export function clearAttachmentStripAnimated(target) {
  const attPreview = (target && target.attPreviewEl) || activeView.dom.attPreview;
  if (!attPreview) return;
  if (attPreview.childElementCount === 0) return;
  if (attPreview.dataset.attClearing === '1') return; // 动画已在播：不重复排
  attPreview.dataset.attClearing = '1';
  animateStripClear(attPreview, () => {
    if (attPreview.dataset.attClearing !== '1') return; // 动画窗内来了新渲染 ⇒ 本轮清空作废
    delete attPreview.dataset.attClearing;
    attPreview.innerHTML = '';
  });
}

export function renderAttachmentPreview(target) {
  // B3-2：附件条 = `innerHTML` 全量重建 + 每 chip 一个 ✕ 按钮的面 —— 键盘用户正在
  // 某个 chip 的 ✕ 上按 Enter 时，任何一次重渲都会把节点换掉。重渲前后按 key 回焦
  // （chips 无 id ⇒ 走协议第三级 DOM 路径：同形重建时结构位序不变）。
  const box = (target && target.attPreviewEl) || activeView.dom.attPreview;
  return withFocusKey(box instanceof HTMLElement ? box : null, () => renderAttachmentPreviewInner(target));
}

/** ── 附件面单源组件（ui-unification-20261103-askuser-attach）──────────────
 *  输入框附件条（renderAttachmentPreviewInner）与 AskUser 卡附件区
 *  （renderAskAttachments）**同一函数**产出附件面 —— 机械断言 a)「两处附件渲染
 *  共用同一组件/函数（grep 无第二份实现）」的本体：
 *   · 图片档 ⇒ `.att-thumb` 真缩略帧（同一 28px 几何档，桌面/手机层各自由 CSS
 *     接管尺寸）。字节源两档同构：`src` = data:/blob: 直显（输入框即时选中的
 *     本地 File）；`path` = 绝对路径（卡区）⇒ 同一 `hydrateMedia` 账本腿取字节
 *     —— 与灯箱共用一份字节，预览后再点开零重复抓取。
 *   · 其余/图无源 ⇒ `.att-file` chip（glyph + 色族块 `.fc-icon` + 文件名；图标
 *     语言 = fileIcon.js 色族单源）。
 *  attachment-thumb-unify 批（作者令 2026-11-05 12:57：「附件这里添加的图片文件等
 *  应该用我们输入框同款的小图标/缩略图」）：卡区媒体档改走本函数的图片档（此前
 *  走卡语言 220px 媒体卡 —— 竖图被放大成 220×476 的大卡，见批文档前后对照）。
 *  可点面（role/tabIndex/Enter·Space）与 ✕ 角标**两档同法**挂根元素 ⇒ 图片档与
 *  文件档的交互语义同源，不各写一套。
 *  @param {{name:string, src?:string, mime?:string, path?:string}} att
 *    src = data:/blob: 直显源；path = 绝对路径（卡区；src 缺省时走票腿）
 *  @param {{rm?:Element, className?:string, ariaLabel?:string,
 *           onClick?:Function}} opts
 *    rm        输入框条传入 ✕ 圆盘（卡区不传——只读面无删除语义）
 *    className 宿主差异类（卡区传 'ask-att-thumb'：卡区作用域样式在 css/chat.css，
 *              输入条逐像素形态不受影响） */
function renderAttachmentFace(att, opts) {
  const o = opts || {};
  const nameText = String(att.name || '');
  const src = String(att.src || '');
  const pathText = String(att.path || '');
  const isMedia = /^image\//.test(String(att.mime || '')) || itemTypeForFileName(nameText) === 'image';
  const withClass = (cls) => cls + (o.className ? ' ' + o.className : '');
  let root;
  if (isMedia && (src || pathText)) {
    root = document.createElement('div');
    root.className = withClass('att-thumb-wrap');
    const img = document.createElement('img');
    img.className = 'att-thumb';
    img.alt = nameText;
    img.draggable = false;
    const thumbRoot = root;
    // A thumbnail that cannot be shown stays in the strip as a failed item; its retry control loads this
    // one thumbnail again and touches nothing else.
    const showThumbFailed = () => {
      if (thumbRoot.classList.contains('is-failed')) return;
      thumbRoot.classList.add('is-failed');
      img.style.display = 'none';
      const retry = document.createElement('button');
      retry.type = 'button';
      retry.className = 'att-thumb-retry';
      retry.title = t('messages.retry');
      retry.setAttribute('aria-label', t('messages.retry') + (nameText ? ' ' + nameText : ''));
      const svgNs = 'http://www.w3.org/2000/svg';
      const icon = document.createElementNS(svgNs, 'svg');
      for (const [name, value] of Object.entries({
        viewBox: '0 0 24 24', width: '14', height: '14', fill: 'none', stroke: 'currentColor',
        'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true',
      })) icon.setAttribute(name, value);
      for (const d of ['M21 12a9 9 0 1 1-3-6.7', 'M21 3v6h-6']) {
        const path = document.createElementNS(svgNs, 'path');
        path.setAttribute('d', d);
        icon.appendChild(path);
      }
      retry.appendChild(icon);
      retry.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        retry.remove();
        thumbRoot.classList.remove('is-failed');
        img.style.display = '';
        if (src) {
          img.removeAttribute('src');
          img.src = src;
        } else {
          clearMediaFailure(img);
          hydrateMedia(img, { path: pathText }, showThumbFailed);
        }
      });
      thumbRoot.appendChild(retry);
    };
    if (src) {
      img.src = src;
      img.onerror = showThumbFailed;
    } else {
      // Ticket / ledger leg (popArtifacts.hydrateMedia); its terminal failure lands in the same failed item.
      hydrateMedia(img, { path: pathText }, showThumbFailed);
    }
    root.appendChild(img);
  } else {
    root = document.createElement('div');
    root.className = withClass('att-file');
    const iconSpec = fileIconStyle(extOf(nameText));
    root.title = nameText;
    root.innerHTML = '<svg class="att-file-glyph" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>'
      + '<span class="att-file-icon fc-icon ' + iconSpec.cls + '" aria-hidden="true">' + escapeHtml(iconSpec.label) + '</span>'
      + '<span class="att-file-name">' + escapeHtml(nameText) + '</span>';
  }
  if (o.onClick) {
    root.classList.add('is-clickable');
    root.setAttribute('role', 'button');
    root.tabIndex = 0;
    if (o.ariaLabel) root.setAttribute('aria-label', o.ariaLabel);
    root.addEventListener('click', /** @type {EventListener} */ (/** @type {any} */ (o.onClick)));
    root.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); o.onClick(e); }
    });
  }
  if (o.rm) root.appendChild(o.rm);
  return root;
}

/** 附件条重建本体（重渲保焦包装见 renderAttachmentPreview）。
 *  target: optional { attPreviewEl, attachments } for non-primary windows.
 *  Defaults to the primary window's attPreview + pendingAttachments so existing
 *  call sites are unaffected. */
function renderAttachmentPreviewInner(target) {
  const attPreview = (target && target.attPreviewEl) || activeView.dom.attPreview;
  const attachments = (target && target.attachments) || activeView.pendingAttachments;
  if (!attPreview) return;
  // 清空动画协同（uifix 批 2026-10-22）：动画在播时的**空数据**重渲染（发送族
  // 腿随后的 renderQuoteStrip）不打断动画、不清 DOM —— 让退场播完；带**新内容**
  // 的重渲染（用户秒补附件/引用、chip ✕ 移除、引用替换）作废在播清空并立即
  // 重建（pending done 靠 dataset 闸自灭）。常规路径零动画、瞬时重建。
  if (attPreview.dataset.attClearing === '1' && attachments.length === 0) return;
  if (attPreview.dataset.attClearing) delete attPreview.dataset.attClearing;
  attPreview.innerHTML = '';
  // 多选引用批（2026-11-22 作者令「多选的就自动缩小为小图标」）：引用牌**条数**决定
  // 形态 —— 1 条 = 现状整卡预览（逐字节不变）；≥2 条 = 每枚缩成图标 chip（类型图标 +
  // 选择顺序号 + 定长短名 + ✕），选择顺序 = 数组顺序（= 发送时 refs 顺序）。
  // 顺序号在这里现算（不落数据面：移除中间一条后幸存者按新序重排，牌面与现实一致）。
  const quoteRefs = attachments.filter(a => a && a.type === 'ref' && a.refType === 'chat-message');
  const compactQuotes = quoteRefs.length >= 2;
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
      // 多选引用批：≥2 条 ⇒ chat-message 引用牌走紧凑档（图标 chip）。单条与其余
      // refType 逐字走原整卡面（零回归）。
      const isQuoteRef = att.type === 'ref' && att.refType === 'chat-message';
      const quoteOrder = isQuoteRef ? quoteRefs.indexOf(att) + 1 : 0;
      const card = renderRefBlock(ref,
        isQuoteRef
          ? {
            mode: 'input', closeStyle: 'disc',
            compact: compactQuotes,
            order: quoteOrder,
            // 点牌回看原消息：与气泡引用块点击同一条定位/高亮实现（msgQuote 单点）。
            // 目标不在页内（跨刷新/被裁剪）⇒ 返回 false ⇒ 牌面就地展开快照摘要。
            onOpen: (r) => revealQuoteTarget(
              r?.source?.messageId, r?.content ? r.content.itemIndex : undefined,
            ),
          }
          : { mode: 'input' },
        remove);
      attPreview.appendChild(card);
    } else if (att.type === 'image' && att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:')) {
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
      // 面本体 = 单源组件（图片档 ⇒ .att-thumb 真缩略帧 + ✕；卡区同函数同面）。
      attPreview.appendChild(renderAttachmentFace(
        { name: String(att.name || ''), src: att.preview, mime: att.mimeType || 'image/jpeg' },
        { rm },
      ));
    } else {
      // 输入框附件图标化批（作者令 2026-11-02「输入框里的附件条不显示文件名，
      // 用与图片附件缩略图一样大小的图标代替」）：
      //  · chip 本体 = `renderAttachmentFace`（本批同源化：AskUser 卡附件区
      //    消费同一函数——机械断言 a) 单点；下方注释逐字保留原实现说明）。
      //  · `.att-file-icon` = 扩展名族色块（fileIconStyle/`.fc-icon` 既有色档单源：
      //    文档蓝/表格绿/演示橙/PDF 红/压缩紫/代码灰），**手机层**由
      //    css/mobileLayer.css 作用域规则接管为 28×28 图标盒（与 .att-thumb 逐值
      //    同盒），文件名 span 在同层隐藏 —— 桌面层两件均不高亮（icon display:none
      //    ⇒ 桌面 chip 逐像素原样，按令「桌面共组件先不动」）。
      //  · 文件名信息不丢：名字进 chip 的 title（指针悬停）与 aria-label（读屏），
      //    只褪出条面；✕ 删除角标与图片附件逐字同族。
      const nameText = String(att.name || '');
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
      const wrap = renderAttachmentFace({ name: nameText }, { rm });
      attPreview.appendChild(wrap);
    }
  });
}

// ---------- /ask bubble rendering ----------

export function appendAskAnswer(delta) {
  const view = activeView;
  const chat = view.dom.chat;
  view.stream.askAnswerText += delta;
  if (!view.stream.currentAskBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    view.stream.currentAskBubble = document.createElement('div');
    view.stream.currentAskBubble.className = mainBubbleClass('ai');
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
        contentEl.innerHTML = renderMarkdownWithMath(target.bubble._nfText || '', true, { cache: false, stream: true }) + '<span class="cursor"></span>';
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
    return { mode: 'full', html: renderMarkdownWithMath(text, parseVoice, { cache: false, stream: true }) };
  }
  if (split < st.closedLen) reset();  // text shrank ⇒ new message
  st.seg = seg;
  if (split > st.closedLen) {
    st.chunks.push({ len: split, html: renderMarkdownWithMath(text.slice(st.closedLen, split), parseVoice, { cache: false, stream: true }) });
    st.closedLen = split;
  }
  const append = [];
  for (let i = st.rendered; i < st.chunks.length; i++) append.push(st.chunks[i].html);
  st.rendered = st.chunks.length;
  return { mode: 'incr', append, tail: renderMarkdownWithMath(text.slice(split), parseVoice, { cache: false, stream: true }), st };
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
    bubble.className = mainBubbleClass('ai', 'thinking-bubble');
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
  // per token（判据 = stream-ux.spec.mjs A2/A6「I1 append-only」；原并引的 A3/A7
  // 已于 stream-latency 批 2026-11-19 重锚到正文气泡契约，不再代表本条）。
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

/**
 * 终态兜底（残片清理批 · 2026-10-29 报障「回复已完成 但下面还是有一个思考过程
 * 流式卡在那」）：把一个视图里**一切在场流式面**强制 final 化 —— thinking / 正文 /
 * 工具卡一视同仁。终态后任何流式残片 = 渲染器 bug 形态（晚到帧开了新块又等不到
 * 它的结束帧 / 重放腿从陈旧缓冲重建的流式面），这里就地收口，绝不挂流式态。
 *
 * 调用点（main.js）：终态帧族（done/error/interrupted/timeout/maxTokens/compactFailed）
 * 的常规收口之后；权威 idle 重同步（applyAuthoritativeSessionBusy{busy:false}）；
 * 晚到帧闸（thinking/thinkingDelta/toolCallDetected/toolStart 的 turn 不在场丢弃腿）。
 *
 * 幂等：指针腿（finishThinking/finishAi）在常规终态已收口时是 no-op；DOM 扫描腿
 * 只动仍处流式态的面。
 * @param {{sessionId?: string, dom: {chat: HTMLElement}, stream: Object<string, any>}} [view]
 *   缺省 = activeView。外来视图临时换座跑指针腿
 *   （与 done 处理器背景腿同款模式），DOM 腿始终显式按本视图扫描。
 */
export function finalizeStreamingFaces(view) {
  const v = view || activeView;
  if (!v || !v.dom || !v.dom.chat) return;
  const chat = v.dom.chat;
  // ① 指针腿：标准终态原语（activeView 绑定；外来视图临时换座）。
  if (v !== activeView) {
    const saved = activeView;
    try {
      setActiveView(v);
      finishThinking();
      finishAi();
    } finally {
      setActiveView(saved);
    }
  } else {
    finishThinking();
    finishAi();
  }
  // ② DOM 兜底腿：指针不在场的流式思考块（重放腿重建面 / 晚到帧开出的第二块等）
  //    就地终态化 —— 与 finishThinking 同一 DOM 契约（thinking-done + 标签去流式态
  //    + 内容收拢 + 可折叠），但不持有 thinkingText（内容已随块在场）。
  chat.querySelectorAll('.thinking-bubble:not(.thinking-done)').forEach((bubble) => {
    bubble.classList.add('thinking-done');
    if (bubble.dataset) bubble.dataset.nfEnd = String(Date.now());
    const label = bubble.querySelector('.thinking-label');
    const content = bubble.querySelector('.thinking-content');
    if (label) {
      label.classList.remove('thinking-streaming');
      label.classList.add('collapsible');
      label.classList.remove('expanded');
      label.setAttribute('aria-expanded', 'false');
      label.querySelectorAll('.thinking-dot').forEach((d) => d.remove());
      const labelText = label.querySelector('.thinking-label-text');
      if (labelText) labelText.textContent = t('chat.thinkingLabel');
      if (content) bindCollapsibleToggle(label, () => content);
    }
    if (content) {
      content.style.display = 'none';
      content.querySelectorAll('.cursor').forEach((el) => el.remove());
    }
  });
  // ③ 游标扫尾：终态后任何 .cursor 都是残片（正文 / 思考 / 工具参数流一视同仁）。
  chat.querySelectorAll('.cursor').forEach((el) => el.remove());
  // ④ 在飞渲染槽：工具参数流 rAF 与正文/ask 的 rAF 槽一并撤（终态后无消费者）。
  cancelToolStreamRAF();
  cancelStreamRender(v, 'ai');
  cancelStreamRender(v, 'ask');
  // ⑤ 工作线：终态后仍在场的线拆除（collapseTurn 正常消费线；此腿兜漏网——
  //    晚到帧开出的线、指针腿没有对应消费路径的线）。
  removeWorkline(v);
}


/**
 * 统一「模型不可用」卡渲染（model-fallback-card 批，2026-11-18 作者裁定 14:37）。
 *
 * 一张卡、全槽共用：槽/标签/供应商/模型/失败分类（HTTP 状态码 / 错误码 / 超时）
 * / 可采取的动作（测试 LLM · 手动切换 · 保持现状）。动作三态回帧
 * `{type:'modelUnavailableAnswer', requestId, action, modelRef?}`（网关 → hub →
 * 等待方 deferred：switch ⇒ 就地写回该槽 model.preferred 并让原请求继续完成）。
 * `reportOnly` 帧（300s 超时回报）只展示不可答。
 */
export function renderModelUnavailable(msg) {
  const host = document.createElement('div');
  host.className = 'model-unavailable-card';
  const c = (v) => (v === null || v === undefined ? '-' : String(v));
  const fail = msg.failureCode ? `${c(msg.failureLabel)}（code ${c(msg.failureCode)}）` : c(msg.failureLabel);
  host.dataset.cardType = 'modelUnavailable';
  host.dataset.cardKind = 'unified';
  host.innerHTML =
    `<div class="muc-head">模型不可用 · 槽 ${c(msg.slotLabel)}(${c(msg.slot)})</div>` +
    `<div class="muc-body">` +
    `<div class="muc-row">provider/model：<b>${c(msg.modelRef)}</b></div>` +
    `<div class="muc-row">失败分类：<b>${fail}</b></div>` +
    `<div class="muc-row muc-detail">${c(msg.detail)}</div>` +
    (msg.test ? `<div class="muc-test">测试 LLM：${msg.test.ok ? '成功' : '失败'} · ${c(msg.test.latencyMs)}ms` +
      (msg.test.ok ? '' : ` · ${c(msg.test.failureLabel)}`) + `</div>` : '') +
    (msg.note ? `<div class="muc-note">${c(msg.note)}</div>` : '') +
    `</div>`;
  if (!msg.reportOnly) {
    const acts = document.createElement('div');
    acts.className = 'muc-actions';
    const send = (action, modelRef) => {
      const frame = { type: 'modelUnavailableAnswer', requestId: msg.requestId, action };
      if (modelRef) frame.modelRef = modelRef;
      if (typeof sendWs === 'function') sendWs(frame);
      host.querySelectorAll('button').forEach((b) => { b.disabled = true; });
    };
    const options = (msg.actions || [])
      .find((a) => a.id === 'switch')?.options || [];
    const pick = document.createElement('select');
    pick.className = 'muc-pick';
    options.forEach((o) => { const opt = document.createElement('option'); opt.value = o; opt.textContent = o; pick.appendChild(opt); });
    const bTest = document.createElement('button');
    bTest.textContent = '测试 LLM';
    bTest.onclick = () => send('test', pick.value || msg.modelRef);
    const bSwitch = document.createElement('button');
    bSwitch.textContent = '切换';
    bSwitch.onclick = () => send('switch', pick.value);
    const bDismiss = document.createElement('button');
    bDismiss.textContent = '保持现状';
    bDismiss.onclick = () => send('dismiss');
    acts.append(pick, bTest, bSwitch, bDismiss);
    host.appendChild(acts);
  }
  return host;
}
