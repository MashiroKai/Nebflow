// chatQueue.js — Queue bar rendering for messages typed while LLM is busy.
// Renders a compact status bar above the input area (NOT in the chat flow).
//
// 排队呈现回退批（2026-10-22 作者令「发送了的消息还在队列里」+「已收到的标记
// 也是多余的」）：今日 #714 乐观气泡批 + 排队信息量批的**呈现层回退**——
//   ① 队列条只保留头行「等待当前回复完成 · N 条排队」（活秒表保留）；行级明细
//      （消息文本/位次标/已收到 chip/立即发送按钮/撤回/移除/拖拽重排）全部退役——
//      排队消息已作为乐观气泡上屏，队列条再渲染内容行 = 同一消息双呈现（作者
//      抱怨点）。队列动作（立即发送/撤回/移除）收敛进气泡右键菜单（msgQuote.js
//      挂点，input.js runQueuedAction 执行面），能力不丢、入口收敛。
//   ② 「已收到」标全退役：气泡 footer 的 .acked 绿✓确认标 + 队列行 ack chip
//      同撤——消息上屏本身即收到，不用标（离线腿「待送达」时钟标语义不同：
//      真离线未出线，保留）。
//   数据层不动：state.messageQueue / queueMessage 钉死寻址 / drain 翻面机制 /
//   persistQueue 持久化轨全部原样，本模块只撤 UI 重复面。
//
// 头行秒表：锚 state.turnStartTimes[sessionId]（与 workline 转轮同源同格式），
// 锚缺失（busy 标先行、锚未达的窗口）降级为无时长形态，禁编造 0s。

import state from './state.js';
import { findViewBySessionId } from './chatView.js';
import { t } from './i18n.js';
import { formatLive } from './workline.js';

/**
 * Re-render the queue bar for a given session.
 * Reads state.messageQueue[sessionId] and renders the header line only
 * （排队呈现回退批：行级明细退役，见文件头注）.
 * If queue is empty, the bar is hidden.
 *
 * @param {string} sessionId
 */
export function renderQueueBar(sessionId) {
  const view = findViewBySessionId(sessionId);
  if (!view || !view.dom.queueBar) return;

  const bar = view.dom.queueBar;
  const items = state.messageQueue[sessionId] || [];

  if (items.length === 0) {
    bar.classList.remove('visible');
    bar.innerHTML = '';
    return;
  }

  bar.classList.add('visible');
  bar.innerHTML = '';

  // Header — 排队呈现回退批：队列条 = 单行状态头（折叠钮随队列行一并退役：
  // 无行可折）。秒表锚 state.turnStartTimes[sessionId]（main.js/input.js 派发点
  // 写入、回合终态删除，与 workline 活秒表同源）——锚缺失降级为无时长形态。
  const header = document.createElement('div');
  header.className = 'queue-bar-header';

  const title = document.createElement('span');
  title.className = 'queue-bar-title';
  title.textContent = t('chatQueue.waitingTurn');
  title.appendChild(document.createTextNode(' · ' + t('chatQueue.queuedCount', { n: String(items.length) })));
  const turnStart = state.turnStartTimes[sessionId];
  if (turnStart) {
    const elapsed = document.createElement('span');
    elapsed.className = 'queue-bar-elapsed';
    elapsed.dataset.start = String(turnStart);
    elapsed.textContent = ' · ' + t('chatQueue.turnElapsed', { t: formatLive(Date.now() - turnStart) });
    title.appendChild(elapsed);
  }
  // qattachvault 禁静默面（行级失败注记退役后的唯一头行形态）：队里含失败项
  // （载荷丢失，已停止自动补发）⇒ 头行追加红色失败计数——失败不许只藏在数据里。
  const failedCount = items.filter(it => it && it.failed).length;
  if (failedCount > 0) {
    const failedEl = document.createElement('span');
    failedEl.className = 'queue-bar-failed';
    failedEl.textContent = ' · ' + t('chatQueue.failedCount', { n: String(failedCount) });
    title.appendChild(failedEl);
  }
  header.appendChild(title);

  bar.appendChild(header);

  // 头行带秒表 ⇒ 武装 1s ticker（无在场秒表节点则本调用不武装——ticker 自身
  // 在扫空时自停）。
  if (document.querySelector('.queue-bar-elapsed[data-start]')) ensureElapsedTicker();
}

// ---------- 头行活秒表（1s 自走，零外部接线） ----------
// renderQueueBar 只在队列变化时被调——秒表不能靠它。单例 interval：扫在场
// .queue-bar-elapsed[data-start]（头行重渲后新节点自动入扫；锚消失 = 回合终态
// 删 turnStartTimes → 由 renderQueueBar 的降级形态接管，此处只管已挂节点）。
// 无在场节点即自停（排队条隐藏 = 零周期工作，对齐 workline UI-G 纪律）。
let _elapsedTimer = 0;
function ensureElapsedTicker() {
  if (_elapsedTimer) return;
  _elapsedTimer = setInterval(() => {
    const nodes = /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.queue-bar-elapsed[data-start]'));
    if (!nodes.length) { clearInterval(_elapsedTimer); _elapsedTimer = 0; return; }
    nodes.forEach((el) => {
      const start = Number(el.dataset.start);
      if (!start) return;
      // 只重写「已进行 Xs」的值段：保留 ' · ' 分隔前缀（首文本节点不动，值在尾部）。
      const sep = el.textContent.match(/^(\s*·\s*)/);
      el.textContent = (sep ? sep[1] : '') + t('chatQueue.turnElapsed', { t: formatLive(Date.now() - start) });
    });
  }, 1000);
}
