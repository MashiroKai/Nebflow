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
//     节点迁移非复制：桌面 JS（agentProfile/sidebar 改名链）照写原节点，
//     零镜像漂移；chatView.setSession 清空保留本层（chatView.js 同批注记）。
//     功能谱系不动：头像 = profile 面板入口（PANEL 链全绿），记忆钮 hidden 常态。
//     形态随 Muse 式悬浮头像批（作者令 2026-11-01 13:33）升级为 sticky 顶锚
//     大圆头像 + 名字下置 —— 样式全在 mobileLayer.css 文件尾批次块，本文件
//     节点迁移/清空守望链不变。
//   · dock 收形批（作者令 10-31 22:50 ①③）的「每条 agent 消息泡上方补挂
//     .g2-msg-meta 身份头」随 Muse 式悬浮头像批**整批退役**（作者令 ①：
//     「去掉每条消息头部的 agent 头像+名字行」）—— 守望/装饰/摘除链全数
//     摘除，本文件不再触碰消息行；会话级身份 = .g2-chat-head 悬浮锚一处。
//   · 顶缘 88px 渐隐带 #g2-edge-fade（index.html 静态节点 + liquid-glass.css；
//     主 JS 顶带实测把它计入 #chat 顶让位，main.js 同批）——本文件不管其显隐：
//     push 页（#mobile-pages z156）全屏不透明直盖其上，零 JS 对账。
//
// 桌面零改动边界：本模块只由 mobile/bootstrap.js 在手机视口激活/拆除；拆除 =
// 双键与两层容器摘除 + 三节点按原序归还 #header .header-center（锚点惯例同
// navController paneAnchors：记录原 parent + next，teardown insertBefore 还原）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { taskBadgeCount } from './taskPanel.js';
import { pushPage, logEvent, touchLayer } from './navController.js';
import { paintSlot } from '../agentProfile.js';
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
/** @type {Array<() => void>} */
let unsubs = [];

/* ── 会话头头像空态回落 stand logo（批③；2026-11-01 起为**兜底腿**）────────────
 *  #agent-avatar-btn 两端共享节点。桌面腿补齐批（2026-11-01，作者令 10-31 22:50
 *  「agent没设置头像之前 用我们stand的logo」）后，agentProfile.paintSlot 自绘
 *  stand logo（同名 .agent-avatar-stand-logo）——本守望退为兜底：仅当槽内既无
 *  头像图也无 logo 时（理论不可达，防时序空窗）补注入。原先「首字母牌保留」的
 *  分档随作者令口径作废：无头像一律 logo（首字母牌/人形字形两档均已退役）。 */
/** @type {MutationObserver|null} 会话头头像槽守望（空态 logo 兜底注入）。 */
let avatarSlotMo = null;
/** 给会话头槽注入 stand logo（空态判定幂等；有图/有 logo 时摘除注入）。 */
function paintAgentSlotLogo(slot) {
  const hasImg = !!slot.querySelector('.agent-avatar-img');
  const hasLogo = !!slot.querySelector('.agent-avatar-stand-logo');
  const injected = slot.querySelector('.agent-avatar-stand-logo');
  if (hasImg || hasLogo) {
    if (injected && hasImg) injected.remove(); // 有真头像：注入档让位（自绘档不在此列）
    return;
  }
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

  // ── 消息流身份头（收形批①③）随 Muse 式悬浮头像批退役 ──
  // 作者令 2026-11-01 13:33 ①「去掉每条消息头部的 agent 头像+名字行」：
  // 守望/装饰/全量重刷链整批摘除（原 repaintAllMsgMeta + #chat childList
  // 守望 + avatar/root-name 重刷订阅），消息行回归桌面同构零装饰；会话级
  // 身份 = .g2-chat-head 悬浮锚（sticky 顶锚大圆头像 + 名字下置，
  // mobileLayer.css 文件尾批次块）一处承载。teardown 的 .g2-msg-meta 摘除
  // 行随之退役 —— 本文件此后对消息行零写入。

  // ── 会话头头像空态回落 stand logo（批③）──
  const avatarSlot = document.getElementById('agent-avatar-btn');
  if (avatarSlot) {
    paintAgentSlotLogo(avatarSlot);
    avatarSlotMo = new MutationObserver(() => paintAgentSlotLogo(avatarSlot));
    avatarSlotMo.observe(avatarSlot, { childList: true });
  }

  // ── 悬浮锚 top 变量同步（Muse 式悬浮头像批 · 作者令 2026-11-01 13:33）──
  // sticky 的锚定矩形 = scrollport 内缩 padding（实测：top:0 只能锚到 padding
  // 缘）—— main.js 顶让位观察器把 padding-top 写在 #chat 上（手机层常态
  // 96px = 渐隐带 88 + 基线 8），CSS 的 top:0 够不到窗口顶。本层同步
  // --muse-head-top = -(padding-top)，mobileLayer.css 的 .g2-chat-head 以
  // var() 消费 —— 锚定阈值恒等窗口顶。#chat 的 content box 随 padding 写入
  // 收缩 ⇒ ResizeObserver 必有一拍重测（横幅/键盘/层翻转引起的 reserve
  // 变化同链覆盖）；同步轻量幂等，零滚动热路径成本。
  const syncHeadTop = () => {
    const pad = parseFloat(getComputedStyle(chat).paddingTop) || 0;
    chat.style.setProperty('--muse-head-top', `${-pad}px`);
  };
  syncHeadTop();
  const headTopRo = new ResizeObserver(syncHeadTop);
  headTopRo.observe(chat);
  unsubs.push(() => headTopRo.disconnect());

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
    avatarSlotMo?.disconnect();
    avatarSlotMo = null;
    // 消息流身份头随 Muse 式悬浮头像批退役（挂出链已摘，DOM 无此节点）。
    document.querySelectorAll('#agent-avatar-btn .agent-avatar-stand-logo').forEach((n) => n.remove());
    // 归位重绘（桌面腿 2026-11-01）：摘除走的是同名类（注入档 + paintSlot 自绘档
    // 一并中招）——归还桌面形态前必须补一次 paintSlot，否则空槽挂到下一个事件。
    try { paintSlot(); } catch { /* 绘制失败不阻断其余归还 */ }
    // 悬浮锚 top 变量随层拆除（headTopRo 经 unsubs 断开）。
    chat.style.removeProperty('--muse-head-top');
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
