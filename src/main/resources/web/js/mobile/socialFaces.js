// mobile/socialFaces.js — 社交页面移动页（dock 批 2026-10-29；中键改版批同日
// 23:36 修订，作者逐字「中间那个对话 就是进入我跟你的这个输入框 …最左侧的消息
// 就是 跟联系人的对话消息」）。
//
// 两页内容臂（数据源 = 桌面既有面/读数，零复制零第二实现）：
//   · 消息 messages = 跟联系人的对话消息：桌面会话列表 #fm-conversations 收编
//     （messages.js 唯一属主面；行点击 = 桌面既有 openConversation 通路 →
//     会话窗 → navController 收编进「会话」详情页）。好友封存域（flag OFF =
//     封存空态卡）。刷新触发复用 initMessages 的面板 .active 观察（收编期给
//     #panel-messages 挂 .active，归还即还原）。（原「对话」中键面迁入——
//     中键让位 chat home 键，见 navController.js DOCK_ORDER/CHAT_DEF。）
//   · 联系人 contacts = 桌面联系人面 #fm-contacts-body 收编（contacts.js 唯一
//     属主；好友/新的朋友/设备段全走桌面渲染）+「手机接入」入口行（渠道卡
//     配置面 #social-overlay 的移动入口，socialPanel 渠道数据源）。
//   · 通知段 = 站内通知流（notificationBanner 属主 #notification-banner 子树
//     镜像：类型/文本/时间），挂在消息页主体面之后 —— 非好友封存域（站内通知/
//     设备互联不属好友封存域），恒渲染。桌面无独立通知 feed 面，此段为移动
//     适配版（任务书口径：对应面在移动端从未有过的按桌面数据源做移动适配）；
//     原「未读聚合行」随面迁移退役（会话列表行自带未读数 + dock 消息键红点
//     镜像，零信息丢失）。
//
// 封存态（featureFlags.friendsEnabled 缺席 = 封存，release 剥除不变）：消息/
// 联系人两面显示封存空态卡（骨架占位 + 文案），社交 dock 栏常驻（五栏固定 =
// 作者令），不伪造数据。flag ON 时 enableFriendPanels 重挂面板 → 本文件的重挂
// 观察腿自动补装真面。
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
/** @type {MutationObserver|null} 通知段镜像观察者。 */
let notifMo = null;

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
  if (stackIncludes('messages')) adoptPanelFace('messages', '#panel-messages', '#fm-conversations');
  else restorePanelFace('messages');
  if (stackIncludes('contacts')) adoptPanelFace('contacts', '#panel-contacts', '#fm-contacts-body');
  else restorePanelFace('contacts');
}

/** 页面内容臂（navController.ensurePanelContent 分派；幂等）。
 *  消息页 = 跟联系人的对话消息（好友封存域：flag OFF = 封存空态，ON = 真面
 *  收编）+ 通知段（非封存域，恒渲染）；联系人 = 好友封存域。 */
export function ensureSocialPage(pageId, body) {
  if (!body) return;
  if (pageId === 'messages') {
    body.querySelector('.mf-social-sealed')?.remove();
    if (friendsEnabled()) adoptPanelFace('messages', '#panel-messages', '#fm-conversations');
    else if (!body.querySelector('.mf-social-sealed')) buildSealedFace(body, '消息');
    ensureNotifySection(body); // 恒插入主体面之后（appendChild 幂等保序）
    return;
  }
  const on = friendsEnabled();
  if (!on) {
    if (!body.querySelector('.mf-social-sealed')) buildSealedFace(body, '联系人');
    return;
  }
  body.querySelector('.mf-social-sealed')?.remove();
  if (pageId === 'contacts') {
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

/** 消息页通知段（页面主体面之后；站内通知镜像 + 恒渲染）。 */
function ensureNotifySection(body) {
  let sec = body.querySelector('.mf-notify-sec');
  if (!sec) {
    sec = document.createElement('div');
    sec.className = 'mf-notify-sec';
    const cap = document.createElement('div');
    cap.className = 'mf-notify-cap';
    cap.textContent = tt('通知', 'Notifications');
    const list = document.createElement('div');
    list.className = 'mf-notify-list';
    sec.append(cap, list);
  }
  body.appendChild(sec); // 恒在会话列表/封存卡之后（幂等保序）
  renderNotifyList(sec.querySelector('.mf-notify-list'));
  if (!notifMo) {
    const banner = document.getElementById('notification-banner');
    if (banner) {
      notifMo = new MutationObserver(() => {
        const l = document.querySelector('#mobile-pages .mf-notify-list');
        if (l) renderNotifyList(l);
      });
      notifMo.observe(banner, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
      unsubs.push(() => { notifMo?.disconnect(); notifMo = null; });
    }
  }
}

/** 通知段重打：站内通知行（banner 子树只读镜像）；无项 = 空态文案一行。 */
function renderNotifyList(list) {
  list.replaceChildren();
  const banner = document.getElementById('notification-banner');
  const items = banner ? [...banner.querySelectorAll('.notif-item')] : [];
  if (!items.length) {
    const empty = document.createElement('div');
    empty.className = 'mf-notify-empty';
    empty.textContent = tt('暂无消息通知', 'No notifications');
    list.appendChild(empty);
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
    list.appendChild(row);
  }
  if (typeof lucide !== 'undefined') createIconsIn(list);
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
