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
