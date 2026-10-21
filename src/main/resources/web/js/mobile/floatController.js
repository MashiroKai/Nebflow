// mobile/floatController.js — v3「左列液态下拉 + 二级液态面板」（样张
// 20261020_214500 v3③⑤ 同款 + 作者现场扩展②「其他下拉键同款适配」）。
//
// 键位映射对齐真实客户端（任务书）：左列 = 本文件 FLOAT_IDS 六键
//   search-btn（搜索聊天记录）/ bg-indicator（后台任务）/ bgagent-indicator（智能体）
//   / reminder-btn（定时任务）/ daemon-btn（心跳）/ memory-btn（记忆）
//   + 任务键（v3⑤ 任务浮层入口，本文件建）+ pending-asks-indicator（D6 F2 待答
//   胶囊 —— 非 FLOAT_IDS 成员，内容驱动显隐，随左列直动作跳最旧待答卡）。
// r4 的「收编 header 右簇 #mobile-float-actions」作废（v3 顶栏=双列液态下拉，
// 右簇形态退役）。
//
// 交互（扩展②口径）：
//   · 任务键 → 六键收回 → 圆钮原地二级液态形变加宽成任务浮层（taskPanel.js）；
//   · 其余「点开有面板」的键（搜索/定时/心跳/记忆/后台/智能体）一律同款二级
//     液态形变（钮变面板）—— 面板显隐仍走各自既有开合路径（零功能重造），
//     本文件只拦呈现：点键瞬间收列，面板现身即从该键 origin morphIn；
//   · 无面板承载的键（pending-asks）保持直动作。
//
// 实现：把 FLOAT_IDS + pending-asks 节点整体**搬移**进 headerController 的左列
//（appendChild = move，监听随节点走，桌面归还锚点照旧）；任务键为自建节点。
// 面板 morph 靠 MutationObserver 盯六个面板载体的显隐类（.open/.on/.hidden），
// 零面板 JS 改动。拆层按锚点原样归还 header。

import { t } from '../i18n.js';
import { key } from '../branding.js';
import { createIconsIn } from '../utils.js';
import { leftList, collapseDDs } from './headerController.js';
import { openTaskPanel, taskBadgeCount } from './taskPanel.js';
import { morphIn } from './morphPanel.js';
import { logEvent, touchLayer } from './navController.js';

/** 收编左列成员（任务键之下，自上而下）：搜索、后台任务、智能体、定时、心跳、记忆、待答。 */
const FLOAT_IDS = ['search-btn', 'bg-indicator', 'bgagent-indicator', 'reminder-btn', 'daemon-btn', 'memory-btn'];
const PENDING_ASKS_ID = 'pending-asks-indicator';

/** 图标常驻键（手机小批 2026-10-21 作者令「与桌面端做区分」）：bg/bgagent 键
 *  手机层改图标常驻 + 角标计数 —— 桌面 count=0 时的 .hidden toggle 保留为计数
 *  信号源（WS 事件驱动，零新轮询），手机层 CSS 反转呈现（键常驻、角标随
 *  .hidden 隐藏，见 mobileLayer.css）；列占位由 data-mh-persist 声明
 *  （headerController observeList 对位）。图标于本处注入（桌面胶囊无图标位，
 *  键语言 = mh-key 圆键 17px lucide；拆层摘除，桌面形态零残留）。图标取色 =
 *  桌面胶囊色语义原样（绿=后台任务 / sapphire=子智能体，chat.css 同源）。 */
const PERSIST_ICONS = {
  'bg-indicator': 'square-terminal',   // 后台任务（后台 shell/远端任务）
  'bgagent-indicator': 'bot',          // 子智能体（navController profile 入口同款词形）
};

/** 键 → 面板载体（morph 目标）与「可见」判据。
 *  P6 归并（mobile-secondary-screens 实施批）：bgagent 键的 morph 载体改指
 *  #bg-dropdown（合并面板，bgStatusFace.js 押回幽灵面 + 解隐载体；列表节点
 *  已收编进载体），可见判据仍读 bgagent-dropdown 的 hidden 翻转。 */
const PANEL_FACE = [
  { keyId: 'search-btn', panel: () => document.getElementById('search-modal'), visible: () => document.getElementById('search-overlay')?.classList.contains('on') === true },
  { keyId: 'memory-btn', panel: () => document.getElementById('memory-modal'), visible: () => document.getElementById('memory-overlay')?.classList.contains('on') === true },
  { keyId: 'reminder-btn', panel: () => document.getElementById('reminder-panel'), visible: () => document.getElementById('reminder-panel')?.classList.contains('open') === true },
  { keyId: 'daemon-btn', panel: () => document.getElementById('daemon-panel'), visible: () => document.getElementById('daemon-panel')?.classList.contains('open') === true },
  { keyId: 'bg-indicator', panel: () => document.getElementById('bg-dropdown'), visible: () => document.getElementById('bg-dropdown')?.classList.contains('hidden') === false },
  { keyId: 'bgagent-indicator', panel: () => document.getElementById('bg-dropdown'), visible: () => document.getElementById('bgagent-dropdown')?.classList.contains('hidden') === false },
];

/** @type {{id:string, parent:Node, next:Node|null}[]} 归还锚点。 */
let anchors = [];
/** @type {ResizeObserver|null} 输入区高度观察（watchInputArea；checkJs 对位 ——
 *  实际 new 的是 ResizeObserver，旧注 MutationObserver 是笔误）。 */
let inputRo = null;
/** @type {MutationObserver|null} */
let faceMo = null;
/** @type {number|null} */
let taskOpenTimer = null;
/** @type {HTMLElement|null} 任务键（morph origin + 徽标宿主）。 */
let taskKey = null;

function tt(zh, en) {
  // 语言源 = i18n.js 同源键（key('locale')，branding.js 命名空间派生）。
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** 任务键（自建；v3⑤ 入口）。 */
function buildTaskKey() {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.id = 'mobile-task-key';
  btn.className = 'mh-key';
  btn.title = tt('任务', 'Tasks');
  btn.setAttribute('aria-label', tt('任务', 'Tasks'));
  btn.innerHTML = '<i data-lucide="list-todo"></i><span class="mh-key-badge" hidden></span>';
  btn.addEventListener('click', () => {
    touchLayer();
    logEvent('dd-key', { key: 'task' });
    collapseDDs(); // ⑤ 二级液态第一步：六键收回
    if (taskOpenTimer) clearTimeout(taskOpenTimer);
    taskOpenTimer = setTimeout(() => openTaskPanel(btn), 260); // 钮形变加宽成浮层
  });
  if (typeof lucide !== 'undefined') createIconsIn(btn);
  return btn;
}

function syncTaskBadge() {
  if (!taskKey) return;
  const badge = /** @type {HTMLElement|null} */ (taskKey.querySelector('.mh-key-badge'));
  if (!badge) return;
  const n = taskBadgeCount();
  badge.hidden = n <= 0;
  badge.textContent = String(n);
}

/** 面板载体显隐 → 从触发键 origin morphIn（扩展②：一律二级液态形变）。 */
function watchPanelFaces() {
  const keyById = (id) => /** @type {HTMLElement|null} */ (document.getElementById(id));
  const recentlyClicked = new Map(); // keyId → ts（仅新开窗 morph，常亮不重播）
  const onKeyCapture = (e) => {
    const el = e.target instanceof Element ? e.target.closest('.mh-dd-bd > [id]') : null;
    if (el && el.id) {
      recentlyClicked.set(el.id, Date.now());
      // 二级液态第一步（样张⑤口径）：键按下先收列，面板现身即从该键 morph。
      collapseDDs();
    }
  };
  document.addEventListener('click', onKeyCapture, true);

  faceMo = new MutationObserver(() => {
    for (const face of PANEL_FACE) {
      if (!face.visible()) continue;
      const panelEl = face.panel();
      const originEl = keyById(face.keyId);
      if (!panelEl || !originEl || !originEl.isConnected) continue;
      const ts = recentlyClicked.get(face.keyId) || 0;
      if (Date.now() - ts > 600) continue; // 非本次点键的显隐（外部开合）不播形变
      // 键收回 → 钮变面板的时序：先隐（等列收），再 morph 揭示。
      panelEl.style.opacity = '0';
      setTimeout(() => morphIn(/** @type {HTMLElement} */ (panelEl), originEl), 160);
      break; // 一次显隐变化只播一个面
    }
  });
  for (const id of ['search-overlay', 'memory-overlay', 'reminder-panel', 'daemon-panel', 'bg-dropdown', 'bgagent-dropdown']) {
    const el = document.getElementById(id);
    if (el) faceMo.observe(el, { attributes: true, attributeFilter: ['class'] });
  }
  return () => document.removeEventListener('click', onKeyCapture, true);
}

let unwatchFaces = () => {};

/** 输入区高度变化 → 更新浮层面板底距变量（心跳配置浮层仍消费该变量）。 */
function watchInputArea() {
  const input = document.getElementById('input-area');
  const update = () => {
    if (!input) return;
    const h = input.getBoundingClientRect().height;
    document.documentElement.style.setProperty('--mobile-float-bottom', `${Math.round(h) + 14}px`);
  };
  update();
  if (input && window.ResizeObserver) {
    inputRo = new ResizeObserver(update);
    inputRo.observe(input);
  }
}

// ── 附件条 v3 → v4（uifix 批 2026-10-22）：折叠腿上移共用单点 ───────────
// #attachment-preview 桌面层同批改 in-flow 独立行（作者令「附件放在对话框上面，
// 不要遮挡文字」）⇒ 折叠腿（+N 收展）不再是手机层私有：原样上移为
// js/attachStripFold.js（观察链桌面启动即挂、幂等），手机层 bootstrap 拆装
// **不再**接管（同一条链两层共用，激活/拆层零动作）。类名/形态逐字沿用：
// .mh-att-more（+N）/ .mh-att-collapsed（收）/ .mh-att-expanded（展开横滚）。

export function activateFloats() {
  const list = leftList();
  if (!list) return () => {};
  // 任务键（自建，列首）。
  taskKey = buildTaskKey();
  list.appendChild(taskKey);
  // FLOAT_IDS + 待答胶囊：真实节点搬移（监听随节点走）。
  for (const id of [...FLOAT_IDS, PENDING_ASKS_ID]) {
    const el = document.getElementById(id);
    if (!el) continue;
    anchors.push({ id, parent: el.parentNode, next: el.nextSibling });
    el.classList.add('mh-key');
    // 图标常驻键（bg/bgagent）：注入键图标 + 常占位声明（幂等；拆层摘除）。
    const icon = PERSIST_ICONS[id];
    if (icon && !el.querySelector('.mh-persist-ico')) {
      el.dataset.mhPersist = 'count';
      const ico = document.createElement('i');
      ico.setAttribute('data-lucide', icon);
      ico.className = 'mh-persist-ico';
      ico.setAttribute('aria-hidden', 'true');
      el.insertBefore(ico, el.firstChild);
    }
    list.appendChild(el);
  }
  if (typeof lucide !== 'undefined') createIconsIn(list);
  watchInputArea();
  // 附件条折叠腿（uifix 批 2026-10-22）：观察链已由 main.js 启动即挂
  // （attachStripFold.watchAttachmentStrip，幂等 + 两层共用）——手机层激活零接管。
  syncTaskBadge();
  window.addEventListener('delegate-chain-mirror-updated', syncTaskBadge);
  window.addEventListener('nebflow-session-change', syncTaskBadge);
  unwatchFaces = watchPanelFaces();

  // 内容性 hidden 变化（memory-btn 启用、后台计数清零）→ 任务徽标/列内显隐同步。
  const mo = new MutationObserver(() => { syncTaskBadge(); });
  for (const id of FLOAT_IDS) {
    const el = document.getElementById(id);
    if (el) mo.observe(el, { attributes: true, attributeFilter: ['hidden', 'class'] });
  }

  return function teardownFloats() {
    mo.disconnect();
    unwatchFaces();
    if (taskOpenTimer) { clearTimeout(taskOpenTimer); taskOpenTimer = null; }
    window.removeEventListener('delegate-chain-mirror-updated', syncTaskBadge);
    window.removeEventListener('nebflow-session-change', syncTaskBadge);
    if (inputRo) { inputRo.disconnect(); inputRo = null; }
    // 附件条折叠观察链不拆（共用单点存续；折叠态随行存续，拆层零残留）。
    // 按锚点原样归还 header（相对次序保持搬移前状态）。
    for (const a of anchors) {
      try {
        const el = document.getElementById(a.id);
        if (el) {
          el.classList.remove('mh-key');
          // 图标常驻键拆层还原：摘注入图标 + 常占位声明（桌面胶囊形态零残留）。
          if (PERSIST_ICONS[a.id]) {
            el.querySelector('.mh-persist-ico')?.remove();
            delete el.dataset.mhPersist;
          }
          if (a.parent && a.parent.isConnected) a.parent.insertBefore(el, a.next);
        }
      } catch { /* 节点树已变：放弃归还，避免二次破坏 */ }
    }
    anchors = [];
    if (taskKey) { taskKey.remove(); taskKey = null; }
    document.documentElement.style.removeProperty('--mobile-float-bottom');
  };
}
