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
  hideChatSkeleton();
}

// --- Initial-load skeleton (audit ②27, mobile P1 batch 2026-10-25) ---
// 初次进会话（历史未加载完）的 loading 骨架卡：3-4 条渐变占位泡（官网气泡同
// 语言：AI 左 / 用户右、16px 圆角族），historyPage 到场即让位（main.js 处理器
// 同帧摘除 → 渲染行顶上，零布局跳变）。纯呈现件：aria-hidden + pointer-events
// none，不进无障碍树、不拦截交互。
export function showChatSkeleton() {
  const chat = activeView && activeView.dom && activeView.dom.chat;
  if (!chat || chat.querySelector('.chat-skeleton')) return;
  const sk = document.createElement('div');
  sk.className = 'chat-skeleton';
  sk.setAttribute('aria-hidden', 'true');
  for (let i = 0; i < 4; i++) {
    const rowEl = document.createElement('div');
    rowEl.className = 'chat-sk-row' + (i === 1 ? ' chat-sk-row-me' : '');
    const bubble = document.createElement('div');
    bubble.className = 'chat-sk-bubble chat-sk-w' + [62, 38, 76, 50][i];
    rowEl.appendChild(bubble);
    sk.appendChild(rowEl);
  }
  chat.appendChild(sk);
}

export function hideChatSkeleton() {
  document.querySelectorAll('.chat-skeleton').forEach(el => el.remove());
}
