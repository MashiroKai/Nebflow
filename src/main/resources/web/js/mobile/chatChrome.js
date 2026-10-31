// mobile/chatChrome.js — 聊天根顶缘悬浮双键 + 页标题入流（liquid-glass-v2 批
// 2026-10-31，作者 08:43 裁定② + 09:03 拍板 v2 + 追加折射令；取代 dock 批的
// 「顶栏双键」形态 —— 顶栏本体随本批退役，见 mobileLayer.css 顶栏退役块）。
//
// v2 契约（设计稿 §3.1 字面落地）：
//   · 无 header 容器 —— #header 手机层 display:none（mobileLayer.css）；
//   · 左右悬浮圆钮：任务/搜索迁入自建的 #mobile-fab-layer（48px v2 液体玻璃
//     正圆 = .g2-fab 共享材质：blur/sat 双档、上半镜面高光、spring 按压回弹、
//     边缘光折射；liquid-glass.css），top:16px 左右 16px，z 高于顶缘渐隐带；
//     任务键徽标 = taskBadgeCount 镜像（dock 批机制零变化）；
//   · 页标题入流：原 #header .header-center 三节点（#agent-avatar-btn /
//     #session-name / #memory-btn）整体迁入 #chat 内容流首的 .g2-chat-head，
//     形态 = 顶缘渐隐改造前原样恢复（作者 10-31 11:38 令②：52px 头像 + 名称
//     玻璃胶囊居中列，见 liquid-glass.css .g2-chat-head 块注记）——
//     节点迁移非复制：桌面 JS（agentProfile/sidebar 改名链）照写原节点，
//     零镜像漂移；chatView.setSession 清空保留本层（chatView.js 同批注记）。
//     功能谱系不动：头像 = profile 面板入口（PANEL 链全绿），记忆钮 hidden 常态。
//   · dock 收形批（作者令 10-31 22:50 ①③）：主对话每条 agent 消息补挂身份头
//     （圆头像 + agent 名；.g2-msg-meta，CSS 在 mobileLayer.css 消息流块）——
//     #chat childList 守望随行随挂，teardown 全量摘除 ⇒ 桌面 DOM 逐字节还原；
//     无头像回落 = stand logo（.g2-msg-avatar-slot / 会话头空态
//     .agent-avatar-stand-logo 同货源，零默认人形）。
//   · 顶缘 88px 渐隐带 #g2-edge-fade（index.html 静态节点 + liquid-glass.css；
//     主 JS 顶带实测把它计入 #chat 顶让位，main.js 同批）——本文件不管其显隐：
//     push 页（#mobile-pages z156）全屏不透明直盖其上，零 JS 对账。
//
// 桌面零改动边界：本模块只由 mobile/bootstrap.js 在手机视口激活/拆除；拆除 =
// 双键与两层容器摘除 + 三节点按原序归还 #header .header-center（锚点惯例同
// navController paneAnchors：记录原 parent + next，teardown insertBefore 还原）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import state from '../state.js';
import { taskBadgeCount } from './taskPanel.js';
import { pushPage, logEvent, touchLayer } from './navController.js';
import { agentAvatarDataUrl, onAgentAvatarChange } from '../agentProfile.js';
import { avatarImgNode } from '../avatarRender.js';
import { rootDisplayName } from '../rootName.js';
import { brand } from '../brand.js';

/** @type {HTMLButtonElement|null} */
let taskBtn = null;
/** @type {HTMLButtonElement|null} */
let searchBtn = null;
/** @type {HTMLElement|null} 悬浮圆钮层（#mobile-fab-layer）。 */
let fabLayer = null;
/** @type {HTMLElement|null} 页标题入流层（.g2-chat-head）。 */
let chatHead = null;
/** @type {MutationObserver|null} 聊天清空路径守望（见 activateChatChrome 注记）。 */
let headMo = null;
/** @type {MutationObserver|null} 消息流行守望：.row.ai 追加 → 补挂头像+名（批①）。 */
let msgMo = null;
/** @type {Array<() => void>} */
let unsubs = [];

/* ── 消息流头像 + agent 名（收形批①③，作者令 10-31 22:50）────────────────
 *  主对话里每条 agent 消息（.row.ai 且带直接 .bubble.ai 子泡）在泡上方补一行
 *  身份头：圆头像 + agent 名（微信式，见 mobileLayer.css .g2-msg-meta 块）。
 *  🔴 手机层专属装饰：只由本模块（mobile-layer 激活路径）挂出；teardown 时
 *  全量摘除 ⇒ 桌面 #chat DOM 逐字节还原（桌面端零感知）。实现走 #chat
 *  childList 守望（行一律 appendChatRow 直挂 #chat，单点入口），行内泡的
 *  流式 innerHTML 更新不在 childList 面上 ⇒ 守望零热路径成本。
 *  · 代发行（.agent-badge 直子 = 「Agent 代发」徽章）不装饰 —— 它已有自己的
 *    身份徽章，名字标 root 反而错标；
 *  · 头像 = agentAvatarDataUrl()（与 dock 中键/会话头同一本地条目），无头像
 *    回落 stand logo（brand 双主题 picture —— 不显示空白/默认人形）；
 *  · 名字 = rootDisplayName() 单读点；头像/名变化经 onAgentAvatarChange /
 *    nebflow-root-name-change 全量重刷（幂等，innerHTML 重建只在事件时）。 */

/** 给单个身份头节点填头像 + 名（幂等可重入，事件重刷入口同一函数）。 */
function paintMsgMeta(meta) {
  const nameEl = meta.querySelector('.g2-msg-name');
  if (nameEl) nameEl.textContent = rootDisplayName();
  const slot = meta.querySelector('.g2-msg-avatar-slot');
  if (!slot) return;
  const dataUrl = agentAvatarDataUrl();
  if (dataUrl) {
    const img = avatarImgNode(dataUrl);
    img.className = 'g2-msg-avatar';
    img.alt = '';
    slot.replaceChildren(img);
    return;
  }
  slot.innerHTML =
    `<picture><source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">` +
    `<img class="g2-msg-avatar g2-msg-logo" src="${brand.logoUrl}" alt=""></picture>`;
}

/** 装饰一行 agent 消息（已装饰/代发行/无泡行一律零操作幂等返回）。 */
function decorateAiRow(row) {
  if (row.querySelector(':scope > .g2-msg-meta')) return;
  if (row.querySelector(':scope > .agent-badge')) return;
  if (!row.querySelector(':scope > .bubble.ai')) return;
  const meta = document.createElement('div');
  meta.className = 'g2-msg-meta';
  const slot = document.createElement('span');
  slot.className = 'g2-msg-avatar-slot';
  const name = document.createElement('span');
  name.className = 'g2-msg-name';
  meta.append(slot, name);
  row.insertBefore(meta, row.firstChild);
  paintMsgMeta(meta);
}

/** 全量重刷（头像/名变化事件 + 激活时的存量行补挂）。 */
function repaintAllMsgMeta(chat) {
  for (const row of chat.querySelectorAll('.row.ai')) decorateAiRow(row);
  for (const meta of chat.querySelectorAll('.g2-msg-meta')) paintMsgMeta(meta);
}

/* ── 会话头头像空态回落 stand logo（批③：不显示空白/默认人形）──────────────
 *  #agent-avatar-btn 是两端共享节点（agentProfile.paintSlot 自绘：有头像 = 图、
 *  未命名 = 中性 user 人形字形、已命名无头像 = 首字母牌）。手机层只接管
 *  **人形字形** 这一档 —— 换 stand logo（brand 双主题 picture）；首字母牌是
 *  身份标记（非空白/非人形），保留。实现 = slot childList 守望：paintSlot 的
 *  replaceChildren 每次重绘都把注入节点抹掉，守望在场即随绘随补（teardown
 *  摘除注入 + 断守望，桌面 DOM 逐字节还原）。 */
/** @type {MutationObserver|null} 会话头头像槽守望（空态 logo 注入）。 */
let avatarSlotMo = null;
/** 给会话头槽注入 stand logo（空态判定幂等；有图/首字母牌时摘除注入）。 */
function paintAgentSlotLogo(slot) {
  const hasImg = !!slot.querySelector('.agent-avatar-img');
  const hasInitial = !!slot.querySelector('.agent-avatar-initial');
  const injected = slot.querySelector('.agent-avatar-stand-logo');
  if (hasImg || hasInitial) {
    if (injected) injected.remove();
    return;
  }
  if (injected) return; // 已注入（幂等）
  const pic = document.createElement('picture');
  pic.className = 'agent-avatar-stand-logo';
  pic.innerHTML =
    `<source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">` +
    `<img class="agent-avatar-logo-img" src="${brand.logoUrl}" alt="">`;
  slot.replaceChildren(pic);
}

function syncTaskBadge() {
  if (!taskBtn) return;
  const n = taskBadgeCount();
  const b = /** @type {HTMLElement|null} */ (taskBtn.querySelector('.mh-chrome-badge'));
  if (!b) return;
  b.hidden = n === 0;
  b.textContent = n > 99 ? '99+' : String(n);
  taskBtn.title = t('taskpanel.title');
}

export function activateChatChrome() {
  const header = document.getElementById('header');
  const main = document.getElementById('main');
  const chat = document.getElementById('chat');
  if (!header || !main || !chat) return () => {};

  // ── 页标题入流：header-center 三节点迁入 #chat 内容流首 ──
  const center = header.querySelector('.header-center');
  if (center && center.children.length) {
    chatHead = document.createElement('div');
    chatHead.className = 'g2-chat-head';
    // 先建层占位内容流首位，再按原序迁节点（teardown 原序归还 = 结构还原）。
    chat.insertBefore(chatHead, chat.firstChild);
    for (const node of [...center.children]) chatHead.appendChild(node);
    /* 聊天清空路径守望：桌面历史重放/会话迁移等 5 处 innerHTML='' 清空
       （historyReplay.js:99 / sidebar.js:2326 / persistence.js:1684 /
       main.js:2897 / chatView.setSession —— 末者已保留本层）会连迁入节点
       一并摘出（模块引用保底不回收，agentProfile 槽位写入不丢）。任一清空
       落幕后把层插回内容流首位，等价「页标题随会话存续」。守望只判
       isConnected，消息追加等常规 childList 变动零成本。 */
    headMo = new MutationObserver(() => {
      if (chatHead && chatHead.parentElement !== chat) {
        chat.insertBefore(chatHead, chat.firstChild);
      }
    });
    headMo.observe(chat, { childList: true });
  }

  // ── 消息流头像 + agent 名（收形批①③）：存量行补挂 + 追加行守望 ──
  repaintAllMsgMeta(chat);
  msgMo = new MutationObserver((records) => {
    for (const rec of records) {
      for (const node of rec.addedNodes) {
        if (!(node instanceof HTMLElement)) continue;
        if (node.matches('.row.ai')) decorateAiRow(node);
        for (const row of node.querySelectorAll('.row.ai')) decorateAiRow(row);
      }
    }
  });
  msgMo.observe(chat, { childList: true });
  const onIdentity = () => repaintAllMsgMeta(chat);
  unsubs.push(onAgentAvatarChange(onIdentity));
  window.addEventListener('nebflow-root-name-change', onIdentity);
  unsubs.push(() => window.removeEventListener('nebflow-root-name-change', onIdentity));

  // ── 会话头头像空态回落 stand logo（批③）──
  const avatarSlot = document.getElementById('agent-avatar-btn');
  if (avatarSlot) {
    paintAgentSlotLogo(avatarSlot);
    avatarSlotMo = new MutationObserver(() => paintAgentSlotLogo(avatarSlot));
    avatarSlotMo.observe(avatarSlot, { childList: true });
  }

  // ── 左右悬浮圆钮层（任务/搜索；48px v2 液体玻璃正圆）──
  fabLayer = document.createElement('div');
  fabLayer.id = 'mobile-fab-layer';

  taskBtn = document.createElement('button');
  taskBtn.type = 'button';
  taskBtn.className = 'mh-chrome-btn g2-fab';
  taskBtn.dataset.chrome = 'tasks';
  taskBtn.title = t('taskpanel.title');
  taskBtn.setAttribute('aria-label', t('taskpanel.title'));
  taskBtn.innerHTML =
    '<i data-lucide="list-todo"></i><span class="mh-chrome-badge" hidden></span>';
  taskBtn.addEventListener('click', (e) => {
    // 桌面面 outside-close 监听 document 冒泡 —— 入口键停冒泡防误关（hub 行同款）。
    e.stopPropagation();
    touchLayer();
    logEvent('chrome-click', { face: 'tasks' });
    pushPage('tasks');
  });

  searchBtn = document.createElement('button');
  searchBtn.type = 'button';
  searchBtn.className = 'mh-chrome-btn g2-fab';
  searchBtn.dataset.chrome = 'search';
  searchBtn.title = t('search.placeholder') || '搜索聊天记录';
  searchBtn.setAttribute('aria-label', searchBtn.title);
  searchBtn.innerHTML = '<i data-lucide="search"></i>';
  searchBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    touchLayer();
    logEvent('chrome-click', { face: 'search' });
    pushPage('search');
  });

  fabLayer.append(taskBtn, searchBtn);
  main.appendChild(fabLayer);
  if (typeof lucide !== 'undefined') createIconsIn(fabLayer);

  syncTaskBadge();
  const onMirror = () => syncTaskBadge();
  window.addEventListener('delegate-chain-mirror-updated', onMirror);
  window.addEventListener('nebflow-session-change', onMirror);
  const onLocale = () => {
    syncTaskBadge();
    if (searchBtn) searchBtn.title = t('search.placeholder') || '搜索聊天记录';
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => {
    window.removeEventListener('delegate-chain-mirror-updated', onMirror);
    window.removeEventListener('nebflow-session-change', onMirror);
    window.removeEventListener('locale-changed', onLocale);
  });

  return function teardownChatChrome() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    headMo?.disconnect();
    headMo = null;
    msgMo?.disconnect();
    msgMo = null;
    avatarSlotMo?.disconnect();
    avatarSlotMo = null;
    // 消息流身份头全量摘除 ⇒ 桌面 #chat DOM 逐字节还原（批①手机层专属装饰）。
    document.querySelectorAll('#chat .g2-msg-meta').forEach((m) => m.remove());
    document.querySelectorAll('#agent-avatar-btn .agent-avatar-stand-logo').forEach((n) => n.remove());
    taskBtn?.remove();
    searchBtn?.remove();
    taskBtn = null;
    searchBtn = null;
    fabLayer?.remove();
    fabLayer = null;
    // 三节点按原序归还 #header .header-center（#header 只是 display:none，DOM
    // 未动；center 原本仅承载这三节点，原序 append = 结构还原）。
    if (chatHead) {
      const center = header.querySelector('.header-center');
      if (center) {
        for (const node of [...chatHead.children]) center.appendChild(node);
      }
      chatHead.remove();
      chatHead = null;
    }
  };
}
