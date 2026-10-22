// chat/historyReplay.js — historyPage 初载重放腿（重连折叠持久批 2026-10-20，
// 续作 chain 69e5e2e1）。
//
// 从 main.js 的 onMessage('historyPage') 初载分支**逐字迁移**（原 main.js
// :1726-1932），一处不改动语义，只加两枚修复：
//
//   ① 折叠态跨重放持久 —— busy 重建面的 thinking 内容恒折叠（症状①：
//      重连重放把缓冲的思考全文直接摊开）。历史行腿的同一修复在
//      persistence.js（「无 text 保持展开」旧例外废除）。
//   ② 幂等重放 —— 重连循环的每一帧重放先算内容签名，与上次初载重放完全
//      一致且 DOM 已是同一签名的产物 ⇒ 整段跳过（不 wipe、不重建、不动
//      滚动位）。这是「整块重挂载」抽动（症状②）与 footer 胶囊跳动
//      （症状③）的单点收敛：签名未变 ⇒ DOM 一个节点都不碰，页内折叠态、
//      用户展开过的工具卡、滚动锚、footer 矩形全部原样存活 —— 同时是
//      #570 重复气泡家族的幂等封口（重放不再追加/重建任何行）。
//
// 抽成真模块的原因：reconnect-collapse 的 harness（tests/fixtures/
// reconnect-collapse/）直接 import 本函数驱动真实重放代码 —— 测的是被测树
// 自己的 main.js 腿，不是 harness 里的手抄副本（副本会随 main.js 漂移，
// 红绿腿全部失真）。main.js 的 handler 保留 bg/flow 门与分页分支，初载腿
// 委托到这里。

import state from '../state.js';
import { activeView } from '../chatView.js';
import { t } from '../i18n.js';
import { renderMarkdownWithMath, shouldFollowBottom } from '../utils.js';
import { restoreFromBackendHistory, findLastRealMessage } from '../persistence.js';
import {
  renderToolPending, renderAskUser, renderPermissionPrompt, reclaimAskUserCards,
  bindCollapsibleToggle,
} from '../chat.js';
import { cleanupCardIframes } from '../cardRegistry.js';
import { clearHistoryIndicators } from '../main/historyIndicators.js';

/**
 * Replay one `historyPage` frame as the INITIAL load of `view`: wipe the chat
 * DOM, restore backend history, rebuild the transient streaming faces from the
 * per-session buffers — unless the frame is byte-identical to the one this
 * view already rendered (idempotent replay: keep the DOM as-is).
 *
 * @param {WSIncomingMessage} msg  historyPage frame (sessionId/messages/offset/total/hasMore)
 * @param {ChatView} view  the view the frame was routed to (ws.js dispatch)
 */
export function replayHistoryPageInitialLoad(msg, view) {
  const sid = msg.sessionId;
  view.pagination.pendingInitialLoad = false;

  // ── 幂等重放签名 ─────────────────────────────────────────────────────
  // 输入集 = 这一帧重放会消费的**全部**状态：历史消息本体 + busy 态 + 两个
  // ask-pending 判定 + 每会话瞬态（thinking/text/pendingRestore/pendingTool/
  // askAnswer）+ answeredPermissions（askPermission 重建腿读它）。任何一项
  // 变化 ⇒ 签名变化 ⇒ 全量重放。JSON.stringify 对 WS 反序列化来的纯数据帧
  // 恒安全（无循环引用、无函数）。
  const lastHistMsg = findLastRealMessage(msg.messages) || undefined;
  const isAskUserPending = !!(lastHistMsg && lastHistMsg.type === 'askUser'
    && Array.isArray(lastHistMsg.items) && lastHistMsg.items.length > 0);
  const isAskPermissionPending = !!(lastHistMsg && lastHistMsg.type === 'askPermission'
    && lastHistMsg.toolName);
  const replaySig = JSON.stringify([
    msg.messages,
    state.busySessionIds.has(sid),
    isAskUserPending,
    isAskPermissionPending,
    state.sessionThinkingBuffers[sid] || null,
    state.sessionTexts[sid] || null,
    state.pendingRestore[sid] || null,
    (state.sessionPendingTools[sid] && state.sessionPendingTools[sid].label) || null,
    (state.sessionAskBuffers[sid] && state.sessionAskBuffers[sid].answer) || null,
    !!(state.answeredPermissions && state.answeredPermissions.has(sid)),
  ]);
  const chatEl = activeView.dom.chat;
  const sigUnchanged = view.pagination.lastReplaySig === replaySig
    && chatEl.dataset.nfReplayConsumed === '1'
    && chatEl.childElementCount > 0;

  // 分页字段无条件更新（轻量，无 DOM 副作用）。
  view.pagination.offset = msg.offset;
  view.pagination.total = msg.total;
  view.pagination.hasMore = msg.hasMore;

  if (sigUnchanged) {
    // 重连循环的重复帧：DOM 已是同一签名的产物 ⇒ 一个节点都不碰。
    // 折叠态 / 展开过的工具卡 / 滚动锚 / footer 矩形原样存活；滚动不劫持
    //（末尾的 shouldFollowBottom rAF 只在真渲染腿跑）。
    return;
  }

  // 重连视口不劫持（续作 69e5e2e1，2026-10-21）：跟随意图 + 视口锚**在 wipe
  // 之前**采集并随 opts 传入 restoreFromBackendHistory——wipe 后的空滚动区对
  // isNearBottom 恒真（「空 = 贴底」），函数内部采集会把「回看中段」误读成
  // 「跟随底部」⇒ 重建腿照旧把用户拽到底 + 强落 scrollSnapped（视口劫持 +
  // 跟随手势污染，正是本批症状②在「签名有变化」真重建腿的残留面）。
  // 首载/贴底 ⇒ follow（照旧贴底，行为同形）；回看中段 ⇒ 锚位保持。
  const followBottom = shouldFollowBottom(view, activeView.dom.chat);
  const anchor = { scrollTop: activeView.dom.chat.scrollTop };

  // Initial load or full refresh — replace
  cleanupCardIframes(activeView.dom.chat);
  activeView.dom.chat.innerHTML = '';
  // Reset sessionToolCards — innerHTML clear above removes all tool pending
  // card DOM nodes, but renderToolPending uses sessionToolCards[sid] as an
  // existence check. A stale DOM reference causes it to skip card creation
  // (update-in-place on a detached node), leaving no spinner visible.
  Object.keys(state.sessionToolCards).forEach(sid => delete state.sessionToolCards[sid]);
  // Clear history indicators before rendering
  clearHistoryIndicators();

  // #346 boundary fix (2026-08-24): when the session is still mid-turn at
  // reload/reconnect, the trailing segment must stay flat — grouping it
  // would stamp it 'failed' and strand a zombie group (see turnGroup.js).
  const isStillBusy = state.busySessionIds.has(sid);
  restoreFromBackendHistory(msg.messages, { busyTail: isStillBusy, followBottom, anchor });

  // Detect if the agent is waiting for AskUser — in that case it's NOT actively streaming.
  // Issue #43 (2026-09-03): agent-injected user bubbles (delegate results,
  // Mail, flow/node notifications) legitimately queue AFTER a still-pending
  // askUser entry — requiring askUser to be the literal LAST message made
  // the pending detection fail exactly when results arrived during the
  // wait, and the restored card stayed locked with no way to answer.
  // findLastRealMessage (shared with persistence.js, spec-covered) scans
  // backward over injected bubbles; a non-injected user message after the
  // askUser means an answer was recorded (the card's confirm/cancel path) —
  // the ask is no longer pending. (The retired chat-input passthrough used to
  // be a second producer of that shape; it is gone since e59ed251d.)

  if (isAskUserPending || isAskPermissionPending) {
    // Agent is blocked on AskUser/AskPermission — clear stale sessionTexts so we don't create
    // a phantom streaming bubble for text that's already in history.
    delete state.sessionTexts[sid];
  }

  // Re-create streaming/completed state from sessionTexts/sessionThinkingBuffers/pendingRestore.
  // (isStillBusy computed above for the busyTail history-restore hint.)
  if (!isAskUserPending && !isAskPermissionPending) {
    // If the backend history already includes the completed message, clean up pendingRestore
    // to avoid duplication. Check last AI message text+thinking match.
    const pendingData = state.pendingRestore[sid];
    if (pendingData && !isStillBusy) {
      const lastAiMsg = msg.messages && [...msg.messages].reverse().find(m => m.type === 'ai' && (m.text || m.thinking));
      const textMatch = lastAiMsg && lastAiMsg.text === pendingData.text;
      const thinkingMatch = lastAiMsg && lastAiMsg.thinking === pendingData.thinking;
      // Exact match: both text and thinking (or both absent) match.
      // Loose match: text matches and thinking is absent on both sides, or
      //             thinking matches and text is absent on both sides.
      const looseMatch = lastAiMsg && (
        (textMatch && (!lastAiMsg.thinking || !pendingData.thinking || thinkingMatch)) ||
        (!lastAiMsg.text && !pendingData.text && thinkingMatch)
      );
      if (textMatch || looseMatch) {
        delete state.pendingRestore[sid];
      }
      // If texts don't match, keep pendingRestore — it may be from a turn
      // the backend hasn't persisted yet. The sessionPendingAiMessages fix
      // ensures text is not concatenated across rounds, so even if rendered
      // as extra bubbles, the content is correct (not duplicated/concatenated).
    }

    const pd = state.pendingRestore[sid];
    // After historyPage restores the authoritative state, clean up any remaining
    // per-session buffer state that wasn't consumed by the active streaming path.
    // This prevents stale data from leaking through on subsequent restores.
    if (!isStillBusy && !pd && !state.sessionTexts[sid]) {
      delete state.sessionThinkingBuffers[sid];
      delete state.sessionPendingAiMessages[sid];
    }
    const thinkBuf = state.sessionThinkingBuffers[sid] || pd?.thinking;
    const txtBuf = state.sessionTexts[sid] || pd?.text;

    // Restore thinking bubble
    if (thinkBuf) {
      activeView.stream.thinkingText = thinkBuf;
      const hasText = !!txtBuf;
      const done = hasText || !isStillBusy;
      const chat = activeView.dom.chat;
      const row = document.createElement('div');
      row.className = 'row ai thinking-row';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai thinking-bubble' + (done ? ' thinking-done' : '');
      const label = document.createElement('div');
      label.className = 'thinking-label' + (done ? ' collapsible' : '');
      label.textContent = t('chat.thinkingLabel');
      const content = document.createElement('div');
      content.className = 'thinking-content';
      if (done) {
        content.style.display = 'none';
        label.classList.add('expanded');
        label.onclick = () => {
          const visible = content.style.display !== 'none';
          content.style.display = visible ? 'none' : '';
          label.classList.toggle('expanded', !visible);
        };
      } else {
        // 重连折叠持久批（2026-10-20）：busy 重建面恒折叠 —— 重连重放不得把
        // 缓冲的思考全文摊开（症状① mid-turn 腿；workline 单行揭示不受影响，
        // 后续 textDelta 照常 rAF 渲染进这个 content，但收着显示）。用户可点
        // label 手动展开 —— 与持久行同一折叠器（onclick 赋值语义，重复绑定
        // 天然幂等）。
        content.style.display = 'none';
        bindCollapsibleToggle(label, () => content);
      }
      content.innerHTML = renderMarkdownWithMath(activeView.stream.thinkingText, true, { cache: done }) + (!done ? '<span class="cursor"></span>' : '');
      bubble.appendChild(label);
      bubble.appendChild(content);
      row.appendChild(bubble);
      chat.appendChild(row);
      activeView.stream.currentThinkingBubble = done ? null : bubble;
    }

    // Restore text bubble
    if (txtBuf) {
      activeView.stream.aiText = txtBuf;
      const chat = activeView.dom.chat;
      const row = document.createElement('div');
      row.className = 'row ai';
      activeView.stream.currentAiBubble = document.createElement('div');
      activeView.stream.currentAiBubble.className = 'bubble ai';
      activeView.stream.currentAiBubble.innerHTML = renderMarkdownWithMath(activeView.stream.aiText, true, { cache: !isStillBusy }) + (isStillBusy ? '<span class="cursor"></span>' : '');
      row.appendChild(activeView.stream.currentAiBubble);
      chat.appendChild(row);
      if (!isStillBusy) {
        activeView.stream.currentAiBubble = null;
        activeView.stream.aiText = '';
        delete state.pendingRestore[sid];
      }
    }

    const askBuf = state.sessionAskBuffers[sid];
    if (isStillBusy && askBuf && askBuf.answer) {
      activeView.stream.askAnswerText = askBuf.answer;
      const chat = activeView.dom.chat;
      const row = document.createElement('div');
      activeView.stream.currentAskBubble = document.createElement('div');
      activeView.stream.currentAskBubble.className = 'bubble ai';
      const label = document.createElement('div');
      label.className = 'ask-label';
      label.textContent = t('chat.askLabel');
      const content = document.createElement('div');
      content.innerHTML = renderMarkdownWithMath(activeView.stream.askAnswerText, true, { cache: false }) + '<span class="cursor"></span>';
      activeView.stream.currentAskBubble.appendChild(label);
      activeView.stream.currentAskBubble.appendChild(content);
      row.appendChild(activeView.stream.currentAskBubble);
      chat.appendChild(row);
    }
    // Re-create pending tool card if this session has an in-progress tool.
    // Also restored in sidebar.js resetChatForActiveSession() for immediate
    // feedback before history arrives. This re-creation ensures the spinner
    // persists after the async getHistory roundtrip clears the DOM.
    if (state.sessionPendingTools[sid]) {
      renderToolPending(state.sessionPendingTools[sid].label, sid);
    }
  }

  // Re-create interactive AskUser if the last history message is an unanswered askUser.
  // Must be OUTSIDE the busySessionIds check — a non-active session that received
  // AskUser was never added to busySessionIds (setBusy only runs for the active session).
  // Simply check if the last history message is askUser with items — if already answered,
  // there would be subsequent Ai/Tool messages after it, so it wouldn't be the last message.
  if (isAskUserPending) {
    // Remove the superseded askUser cards (restored by restoreFromBackendHistory)
    // through the SAME single-judgment function (案 A③) — 同 id 的既有卡 + 无 id 的
    // 历史恢复未作答卡。改前这里是「删掉**全部**带 .option-box 的 .row.ai」，
    // 会把更早那些**已作答**的历史卡一并从 DOM 里抹掉（超出本步语义）。
    reclaimAskUserCards(activeView.dom.chat, lastHistMsg.requestId, { legacyTwin: true });
    // 附件位（本批）与另两条腿同形透传：`lastHistMsg.attachments` 今日恒 undefined
    // —— 后端 `.ui.json` 落盘时**不写** attachments（WebSocketRoutes.scala:2294-2298
    // 的 `UiMessage.AskUser(items, askRid)`）⇒ 历史行没有该键可透传，本处是**形态
    // 对齐**的防御位，不是活路径（见过程件「构造性边界」一节）。
    renderAskUser(lastHistMsg.items, sid, lastHistMsg.agentName, lastHistMsg.requestId, { project: lastHistMsg.project, nodeName: lastHistMsg.nodeName }, lastHistMsg.attachments);
  }

  // Re-create interactive AskPermission if the last history message is an unanswered askPermission.
  // Same logic as AskUser — if already answered, there would be subsequent tool messages.
  // Guard: skip if the user has already answered this permission in the current session
  // (tool is still executing, askPermission is still the last history entry).
  if (isAskPermissionPending && !state.answeredPermissions.has(sid)) {
    // Remove the disabled askPermission row (restored by restoreFromBackendHistory).
    activeView.dom.chat.querySelectorAll('.row.ai').forEach(row => {
      if (row.querySelector('.permission-pending-box')) row.remove();
    });
    renderPermissionPrompt(lastHistMsg.toolName, lastHistMsg.summary, lastHistMsg.input, sid, lastHistMsg.dangerLevel, lastHistMsg.sourceAgent, lastHistMsg.sourceSession, lastHistMsg.sourceTeam, lastHistMsg.requestId, lastHistMsg.safetyMode);
  }

  // Final scroll-to-bottom: after all rendering (history + streaming bubbles + pending tools)
  // is complete, ensure the viewport shows the latest content.
  // Uses rAF to avoid layout thrashing — fires after any pending style calculations.
  // A-branch (2026-09-11): BOTH passes are conditional. The trailing
  // setTimeout used to jump to the bottom unconditionally 150ms later —
  // it yanked a user who had already scrolled up during the deferred
  // markdown/iframe layout settle.
  requestAnimationFrame(() => {
    const chat = view.dom.chat;
    if (shouldFollowBottom(view, chat)) {
      chat.scrollTop = chat.scrollHeight;
      view.stream.scrollSnapped = true;
    }
    // Second pass after deferred markdown rendering settles
    setTimeout(() => {
      if (shouldFollowBottom(view, chat)) chat.scrollTop = chat.scrollHeight;
    }, 150);
  });

  // 幂等戳：DOM 已是本签名的产物。下一帧签名一致 ⇒ 走顶部 skip。
  chatEl.dataset.nfReplayConsumed = '1';
  view.pagination.lastReplaySig = replaySig;
}
