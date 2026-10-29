// mobile/socialFaces.js — 社交三面移动页（dock 批，作者令 2026-10-29「把我们
// 的社交功能恢复 从左到右分别是 消息 联系人 对话 canvas 设置」）。
//
// 三页内容臂（数据源 = 桌面既有面/读数，零复制零第二实现）：
//   · 对话 conversations = 桌面会话列表 #fm-conversations 收编（messages.js
//     唯一属主面；行点击 = 桌面既有 openConversation 通路 → 会话窗 → navController
//     收编进「会话」详情页）。刷新触发复用 initMessages 的面板 .active 观察
//     （收编期给 #panel-messages 挂 .active，归还即还原）。
//   · 联系人 contacts = 桌面联系人面 #fm-contacts-body 收编（contacts.js 唯一
//     属主；好友/新的朋友/设备段全走桌面渲染）+「手机接入」入口行（渠道卡
//     配置面 #social-overlay 的移动入口，socialPanel 渠道数据源）。
//   · 消息 messages = 站内通知流（notificationBanner 属主的 #notification-banner
//     子树镜像：类型/文本/时间）+ 未读聚合行（#messages-badge / 设备段读数镜像，
//     点按跳「对话」根页）—— 桌面无独立通知 feed 面，此页为移动适配版
//     （任务书口径：对应面在移动端从未有过的按桌面数据源做移动适配）。
//
// 封存态（featureFlags.friendsEnabled 缺席 = 封存，release 剥除不变）：三页
// 显示封存空态卡（骨架占位 + 文案），社交 dock 栏常驻（五栏固定 = 作者令），
// 不伪造数据。flag ON 时 enableFriendPanels 重挂面板 → 本文件的重挂观察腿
// 自动补装真面。
//
// 桌面零改动边界：本文件只追加自有节点 + 收编期翻转 #panel-messages/
// #panel-contacts 的 .active（initMessages/initContacts 的刷新观察判据；
// 归还时还原快照）。好友模块的取数/渲染/WS 订阅零触碰。

import { friendsEnabled } from '../featureFlags.js';
import { key } from '../branding.js';
import { createIconsIn } from '../utils.js';

/** @type {Map<string, {face: Element, parent: Element, next: Node|null, activePrev: boolean}>} */
const anchors = new Map();
/** @type {MutationObserver|null} 面板重挂观察（enableFriendPanels 回填腿）。 */
let remountMo = null;
/** @type {Array<() => void>} */
let unsubs = [];
/** @type {MutationObserver|null} 通知流镜像观察者。 */
let notifMo = null;
/** @type {MutationObserver|null} 徽标镜像观察者。 */
let badgeMo = null;

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** 封存空态卡（骨架占位三行 + 文案；P1 批骨架卡语言同族——静态占位不闪动）。 */
function buildSealedFace(body, zhLabel) {
  const wrap = document.createElement('div');
  wrap.className = 'mf-social-sealed';
  for (let i = 0; i < 3; i++) {
    const sk = document.createElement('div');
    sk.className = 'mf-social-sealed-skel';
    wrap.appendChild(sk);
  }
  const note = document.createElement('div');
  note.className = 'mf-social-sealed-note';
  note.textContent = tt(`${zhLabel}功能未开启`, `${zhLabel} is not enabled`);
  const sub = document.createElement('div');
  sub.className = 'mf-social-sealed-sub';
  sub.textContent = tt('在配置中开启 features.friends 后可用（刷新生效）', 'Enable "features": { "friends": true } in config, then reload');
  wrap.append(note, sub);
  body.appendChild(wrap);
}

/** 面收编（幂等）：面板子面移入页面体 + 刷新判据 .active 翻转（快照还原）。 */
function adoptPanelFace(pageId, panelSel, faceSel) {
  const body = document.querySelector(`#mobile-pages .mobile-page[data-page="${pageId}"] .mobile-page-body`);
  if (!body) return;
  const panel = document.querySelector(panelSel);
  const face = document.querySelector(faceSel);
  if (!panel || !face || face.parentElement === body) return;
  anchors.set(pageId, {
    face,
    parent: /** @type {Element} */ (face.parentElement),
    next: face.nextSibling,
    activePrev: panel.classList.contains('active'),
  });
  panel.classList.add('active'); // initMessages/initContacts 的刷新观察判据（桌面口径同源）
  body.appendChild(face);
}

/** 面归还（出页/拆层）：子面原位归回面板 + .active 快照还原。 */
function restorePanelFace(pageId) {
  const a = anchors.get(pageId);
  if (!a) return;
  anchors.delete(pageId);
  if (a.face.isConnected && a.parent?.isConnected) {
    try {
      a.parent.insertBefore(a.face, a.next);
      const panel = a.parent.closest('.panel');
      if (panel) (/** @type {HTMLElement} */ (panel)).classList.toggle('active', a.activePrev);
    } catch { /* 树已变：放弃归还 */ }
  }
}

/** 栈在页 ⇒ 收编；不在 ⇒ 归还（renderPages 汇合点同款）。 */
export function syncSocialFaces(stackIncludes) {
  if (stackIncludes('conversations')) adoptPanelFace('conversations', '#panel-messages', '#fm-conversations');
  else restorePanelFace('conversations');
  if (stackIncludes('contacts')) adoptPanelFace('contacts', '#panel-contacts', '#fm-contacts-body');
  else restorePanelFace('contacts');
}

/** 页面内容臂（navController.ensurePanelContent 分派；幂等）。
 *  消息页 = 通知流（不分封存态 —— 站内通知/设备互联不属好友封存域）；
 *  对话/联系人 = 好友封存域（flag OFF = 封存空态，ON = 真面收编）。 */
export function ensureSocialPage(pageId, body) {
  if (!body) return;
  if (pageId === 'messages') {
    body.querySelector('.mf-social-sealed')?.remove();
    ensureMessagesFace(body);
    return;
  }
  const on = friendsEnabled();
  if (!on) {
    if (!body.querySelector('.mf-social-sealed')) {
      const zh = pageId === 'contacts' ? '联系人' : '对话';
      buildSealedFace(body, zh);
    }
    return;
  }
  body.querySelector('.mf-social-sealed')?.remove();
  if (pageId === 'conversations') {
    adoptPanelFace('conversations', '#panel-messages', '#fm-conversations');
  } else if (pageId === 'contacts') {
    adoptPanelFace('contacts', '#panel-contacts', '#fm-contacts-body');
    ensureSocialEntry(body);
  }
}

/** 联系人页「手机接入」入口行（渠道卡配置面 = socialPanel 渠道数据源的移动入口）。 */
function ensureSocialEntry(body) {
  if (body.querySelector('.mf-social-entry')) return;
  const entry = document.createElement('button');
  entry.type = 'button';
  entry.className = 'mf-social-entry';
  entry.dataset.page = 'social';
  const ic = document.createElement('span');
  ic.className = 'mf-social-entry-ic';
  ic.innerHTML = '<i data-lucide="smartphone"></i>';
  const label = document.createElement('span');
  label.className = 'mf-social-entry-label';
  label.textContent = tt('手机接入', 'Phone access');
  const chev = document.createElement('i');
  chev.setAttribute('data-lucide', 'chevron-right');
  chev.className = 'mf-social-entry-chev';
  entry.append(ic, label, chev);
  entry.addEventListener('click', (e) => {
    // 桌面面 outside-close 监听 document 冒泡 —— 入口键停冒泡防误关。
    e.stopPropagation();
    // navController 事件环外发（避免循环 import）：由栈层 push social 页。
    document.dispatchEvent(new CustomEvent('nf:mobile-social-entry'));
  });
  body.insertBefore(entry, body.firstChild);
  if (typeof lucide !== 'undefined') createIconsIn(entry);
}

/** 消息页 = 通知流镜像 + 未读聚合行。 */
function ensureMessagesFace(body) {
  let feed = body.querySelector('.mf-msgfeed');
  if (!feed) {
    feed = document.createElement('div');
    feed.className = 'mf-msgfeed';
    body.appendChild(feed);
  }
  renderMsgFeed(feed);
  if (!notifMo) {
    const banner = document.getElementById('notification-banner');
    if (banner) {
      notifMo = new MutationObserver(() => {
        const f = document.querySelector('#mobile-pages .mf-msgfeed');
        if (f) renderMsgFeed(f);
      });
      notifMo.observe(banner, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
      unsubs.push(() => { notifMo?.disconnect(); notifMo = null; });
    }
  }
  if (!badgeMo) {
    badgeMo = new MutationObserver(() => {
      const f = document.querySelector('#mobile-pages .mf-msgfeed');
      if (f) renderMsgFeed(f);
    });
    for (const sel of ['messages-badge', 'contacts-badge']) {
      const el = document.getElementById(sel);
      if (el) badgeMo.observe(el, { childList: true, characterData: true, subtree: true, attributes: true, attributeFilter: ['hidden'] });
    }
    unsubs.push(() => { badgeMo?.disconnect(); badgeMo = null; });
  }
}

/** 通知流重打：未读聚合行（徽标镜像）+ 站内通知行（banner 子树只读镜像）。 */
function renderMsgFeed(feed) {
  feed.replaceChildren();
  const msgs = document.getElementById('messages-badge');
  const unread = msgs && !msgs.hidden ? (msgs.textContent || '').trim() : '';
  if (unread) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'mf-msgrow';
    const ic = document.createElement('span');
    ic.className = 'mf-msgrow-ic';
    ic.innerHTML = '<i data-lucide="message-circle"></i>';
    const label = document.createElement('span');
    label.className = 'mf-msgrow-label';
    label.textContent = tt('社交会话未读', 'Unread social conversations');
    const n = document.createElement('span');
    n.className = 'mf-msgrow-count';
    n.textContent = unread;
    row.append(ic, label, n);
    row.addEventListener('click', () => {
      document.dispatchEvent(new CustomEvent('nf:mobile-goto', { detail: { page: 'conversations' } }));
    });
    feed.appendChild(row);
  }
  const banner = document.getElementById('notification-banner');
  const items = banner ? [...banner.querySelectorAll('.notif-item')] : [];
  if (!unread && !items.length) {
    const empty = document.createElement('div');
    empty.className = 'mf-msgfeed-empty';
    empty.textContent = tt('暂无消息通知', 'No notifications');
    feed.appendChild(empty);
    return;
  }
  for (const item of items) {
    const text = item.querySelector('.notif-text')?.textContent || item.textContent || '';
    const row = document.createElement('div');
    row.className = 'mf-msgrow is-static';
    const ic = document.createElement('span');
    ic.className = 'mf-msgrow-ic';
    ic.innerHTML = '<i data-lucide="bell"></i>';
    const label = document.createElement('span');
    label.className = 'mf-msgrow-label';
    label.textContent = text.trim();
    row.append(ic, label);
    feed.appendChild(row);
  }
  if (typeof lucide !== 'undefined') createIconsIn(feed);
}

/** enableFriendPanels 回挂观察：面板节点出现在 #sidebar-panel 即补收编。 */
function tryRemountWatch() {
  if (remountMo) return;
  const host = document.getElementById('sidebar-panel');
  if (!host) return;
  remountMo = new MutationObserver(() => {
    if (!document.getElementById('panel-messages') && !document.getElementById('panel-contacts')) return;
    if (friendsEnabled()) {
      const inc = (id) => !!document.querySelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`);
      syncSocialFaces(inc);
    }
  });
  remountMo.observe(host, { childList: true });
  unsubs.push(() => { remountMo?.disconnect(); remountMo = null; });
}

export function activateSocialFaces() {
  tryRemountWatch();
  return function teardownSocialFaces() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    if (remountMo) { remountMo.disconnect(); remountMo = null; }
    for (const id of [...anchors.keys()]) restorePanelFace(id);
    anchors.clear();
  };
}
