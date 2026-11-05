// main.js 抽取(FE组件化批次七 2026-09-28):history 指示器族 模块(行为保持)。
import { activeView } from '../chatView.js';
import { t } from '../i18n.js';

// --- History pagination indicators ---
export function showHistoryLoader() {
  let loader = activeView.dom.chat.querySelector('.history-loader');
  if (loader) return;
  loader = document.createElement('div');
  loader.className = 'history-loader';
  loader.innerHTML = '<div class="history-spinner"></div><span>' + t('chat.loading') + '</span>';
  activeView.dom.chat.prepend(loader);
}

export function hideHistoryLoader() {
  document.querySelectorAll('.history-loader').forEach(el => el.remove());
}

export function showHistoryEnd() {
  if (activeView.dom.chat.querySelector('.history-end')) return;
  const end = document.createElement('div');
  end.className = 'history-end';
  end.textContent = t('chat.noMoreMessages');
  activeView.dom.chat.prepend(end);
}

export function clearHistoryIndicators() {
  activeView.dom.chat.querySelectorAll('.history-loader, .history-end').forEach(el => el.remove());
}

// --- 初载骨架卡（audit ②27，2026-10-25）→ 已整件退役（作者令 2026-11-05 12:28）---
// 作者逐字：「我们目前的逻辑是通过一个卡片来显示加载过程 进来就应该是加载好的
// 这个占位逻辑多余了」。实拍（IMG_1744）形态 = 4 条大圆角占位泡（i=1 红色侧 =
// 用户位绿泡）+ 渐变 shimmer，挂在聊天流顶部。
//
// 🔴 根因（本条是真 bug，不只是冗余）：挂载腿（sidebar.js renderMessages 的
// `else if (sid)` 支）在**每次重渲**都挂一次，而摘除只发生在 historyPage 帧
// ⇒ 已经渲染出真消息的会话在重渲后「占位泡与真内容同框」，且若此后没有新的
// historyPage 帧到达，占位**永不退场**（作者实拍即此形态：上面 4 个空泡，下面
// 是真实对话）。
//
// 退役处理：形态删除，三档判据保留 —— 线程**真空**（真正一条消息都还没渲染）
// 时给一条既有细加载行 `.history-loader`（spinner + 「加载中…」，非卡片/非气泡
// 形态，与翻页加载同一个件）；有内容时零占位；失败态与空态各有自己的面
// （.fm-list-error / 各面空态），不吃本条。
