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
//     属主；好友/新的朋友/设备段全走桌面渲染）。原页顶「手机接入」入口行已
//     整链撤除（作者令 2026-10-31 22:55 撤除批，手机端专属——桌面
//     #social-btn/#social-overlay 同入口不动）。
//
// 通知段退役（作者令 2026-10-30 08:29 逐字「这个图的消息的底部，通知是干嘛的，
// 我觉得不用显示。就像微信一样就行了，有新消息在消息按钮的右上角加数字角标
// 就行」）：原挂在消息页底部的站内通知镜像段（notificationBanner 属主
// #notification-banner 子树只读镜像）整段摘除，**不留孤儿数据**——源头
// `addNotification`（reminder/conn/task/device-mail 四类）照旧写
// `state.notifications` 并渲染进 `#top-overlays` 内的 `#notification-banner`
// （手机层未隐藏，聊天根顶带仍可见），本节只退役它的移动镜像面，零第二实现。
//
// 封存态（显式 "features": {"friends": false} 或 --strip-friends 产物；功能开放批
// 2026-10-30 起运行态缺省为开放，friendseal 批 2026-09-25 的「缺席 = 封存」语义
// 已被本批取代）：消息/
// 联系人两面显示封存空态卡（骨架占位 + 文案），社交 dock 栏常驻（五栏固定 =
// 作者令），不伪造数据。flag ON 时 enableFriendPanels 重挂面板 → 本文件的重挂
// 观察腿自动补装真面。
//
// 桌面零改动边界：本文件只追加自有节点 + 收编期翻转 #panel-messages/
// #panel-contacts 的 .active（initMessages/initContacts 的刷新观察判据；
// 归还时还原快照）。好友模块的取数/渲染/WS 订阅零触碰。

import { friendsEnabled } from '../featureFlags.js';
import { key } from '../branding.js';

/** @type {Map<string, {face: Element, parent: Element, next: Node|null, activePrev: boolean}>} */
const anchors = new Map();
/** @type {MutationObserver|null} 面板重挂观察（enableFriendPanels 回填腿）。 */
let remountMo = null;
/** @type {Array<() => void>} */
let unsubs = [];

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
 *  收编）；联系人 = 好友封存域。通知段已退役（作者令 2026-10-30 08:29），
 *  页面体只承载收编面/封存卡。联系人页顶「手机接入」入口行已撤除
 * （作者令 2026-10-31 22:55，仅手机端；桌面同入口不动）。 */
export function ensureSocialPage(pageId, body) {
  if (!body) return;
  if (pageId === 'messages') {
    body.querySelector('.mf-social-sealed')?.remove();
    if (friendsEnabled()) adoptPanelFace('messages', '#panel-messages', '#fm-conversations');
    else if (!body.querySelector('.mf-social-sealed')) buildSealedFace(body, '消息');
    return;
  }
  const on = friendsEnabled();
  if (!on) {
    if (!body.querySelector('.mf-social-sealed')) buildSealedFace(body, '联系人');
    return;
  }
  body.querySelector('.mf-social-sealed')?.remove();
  if (pageId === 'contacts') {
    // 撤除批后联系人页 = 收编面本体，无追加节点。
    adoptPanelFace('contacts', '#panel-contacts', '#fm-contacts-body');
  }
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
