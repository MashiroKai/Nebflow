// chat.js 拆分(FE组件化批次三 2026-09-27):ask/skill 气泡显示(纯渲染部分;appendAskAnswer/finishAskAnswer/renderAskError 因闭包捕获主体 rAF 调度器与 renderError 留守 chat.js) 可复用模块(行为保持)。
import { activeView } from '../chatView.js';
import { t } from '../i18n.js';
import { smartScroll } from '../utils.js';
import { createMsgFooterBadge } from './durationBadges.js';

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
  // A 侧 #491 单点落序（chat.js appendChatRow = `chat.append(row)`）随簇同位落此。
  chat.append(row);
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
  // A 侧 #491 单点落序（chat.js appendChatRow = `chat.append(row)`）随簇同位落此。
  chat.append(row);
  smartScroll();
}
