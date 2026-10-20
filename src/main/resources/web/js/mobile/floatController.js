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
/** @type {MutationObserver|null} */
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

// ── 附件条 v3：in-flow 行 + 溢出收「+N」（样张 v2 p5 已批形态）──────────
// #attachment-preview 在手机层改 in-flow 首行（CSS），本段只做折叠腿：
// 单行装不下的尾部 chip 落 .mh-att-collapsed，行尾补「+N」件；点 +N 展开为
// 横向滚动（再点收拢）。chip 增删/换行由 MutationObserver + ResizeObserver 驱动。
/** @type {{ro: ResizeObserver|null, mo: MutationObserver|null, more: HTMLElement|null}} */
let attRow = { ro: null, mo: null, more: null };

/** chip 实测宽缓存（折叠态 display:none 无盒 —— 用上次可见时的宽度参算）。 */
const attChipW = new WeakMap();

function syncAttachmentRow() {
  const row = document.getElementById('attachment-preview');
  if (!row) return;
  const chips = [...row.children].filter((el) => !el.classList.contains('mh-att-more'));
  let more = attRow.more;
  if (!more) {
    more = document.createElement('button');
    more.type = 'button';
    more.className = 'mh-att-more';
    more.addEventListener('click', () => {
      row.classList.toggle('mh-att-expanded');
      collapseAttachmentRow();
    });
    attRow.more = more;
  }
  // 自激纪律：本函数由本行的 MutationObserver(childList+attributes+subtree)
  // + ResizeObserver 驱动。禁止「先重置再重折」的两段式写法 —— 折叠平衡态下
  // 每次同步都会撤销再重做（remove k + add k = 2k+2 条记录/次），微任务风暴
  // 直接冻死页面（实测 evaluate 挂死）。改为：纯读算目标 → diff 应用，
  // 平衡态零写入。
  const expanded = row.classList.contains('mh-att-expanded');

  // 目标态计算（纯读；collapsed 芯片无盒 → 用宽度缓存参算）。
  const gap = parseFloat(getComputedStyle(row).columnGap) || 6;
  const widths = chips.map((c) => {
    if (!c.classList.contains('mh-att-collapsed') && c.offsetWidth > 0) attChipW.set(c, c.offsetWidth);
    return attChipW.get(c) || 0;
  });
  const total = widths.reduce((s, w, i) => s + w + (i ? gap : 0), 0);
  const avail = row.clientWidth;
  const moreW = (more.isConnected && more.offsetWidth) || 34; // CSS min-width 28 + padding 兜底
  const overflow = !expanded && total > avail + 1;
  // 折叠目标 = 保前缀（至少 1 枚），前缀宽 + more 槽 ≤ 行宽（+N 自身吃滚动差）。
  let keep = chips.length;
  if (overflow) {
    const budget = avail - moreW - gap;
    let acc = 0;
    keep = 0;
    for (let i = 0; i < chips.length; i++) {
      const next = acc + widths[i] + (i ? gap : 0);
      if (next > budget) break;
      acc = next;
      keep++;
    }
    if (keep < 1) keep = 1; // 至少保首枚可见（全收 = 无意义折叠）
  }

  // diff 应用（平衡态零写）。
  for (let i = 0; i < chips.length; i++) {
    const want = i >= keep;
    const has = chips[i].classList.contains('mh-att-collapsed');
    if (want && !has) chips[i].classList.add('mh-att-collapsed');
    else if (!want && has) chips[i].classList.remove('mh-att-collapsed');
  }
  const hiddenCount = chips.length - keep;
  if (expanded) {
    // 展开态：+N 保留行尾作「收拢」键（点它 toggle 回折叠；文本沿用折叠计数）。
    if (more.parentElement !== row || row.lastElementChild !== more) row.appendChild(more);
    if (more.hidden) more.hidden = false;
  } else if (overflow && hiddenCount > 0) {
    if (more.parentElement !== row || row.lastElementChild !== more) row.appendChild(more);
    if (more.hidden) more.hidden = false;
    const moreText = `+${hiddenCount}`;
    if (more.textContent !== moreText) more.textContent = moreText;
  } else {
    if (more.isConnected) more.remove();
    if (!more.hidden) more.hidden = true;
  }
}

/** 展开态重算（展开后行宽变化 → 重新判定折叠）。 */
function collapseAttachmentRow() { syncAttachmentRow(); }

function watchAttachmentRow() {
  const row = document.getElementById('attachment-preview');
  if (!row) return;
  attRow.mo = new MutationObserver(syncAttachmentRow);
  attRow.mo.observe(row, { childList: true, attributes: true, subtree: true });
  if (window.ResizeObserver) {
    attRow.ro = new ResizeObserver(syncAttachmentRow);
    attRow.ro.observe(row);
  }
  syncAttachmentRow();
}

function unwatchAttachmentRow() {
  if (attRow.ro) { attRow.ro.disconnect(); attRow.ro = null; }
  if (attRow.mo) { attRow.mo.disconnect(); attRow.mo = null; }
  if (attRow.more) { attRow.more.remove(); attRow.more = null; }
  const row = document.getElementById('attachment-preview');
  if (row) {
    row.classList.remove('mh-att-expanded');
    row.querySelectorAll('.mh-att-collapsed').forEach((el) => el.classList.remove('mh-att-collapsed'));
  }
}

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
    list.appendChild(el);
  }
  if (typeof lucide !== 'undefined') createIconsIn(list);
  watchInputArea();
  watchAttachmentRow();
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
    unwatchAttachmentRow();
    // 按锚点原样归还 header（相对次序保持搬移前状态）。
    for (const a of anchors) {
      try {
        const el = document.getElementById(a.id);
        if (el) {
          el.classList.remove('mh-key');
          if (a.parent && a.parent.isConnected) a.parent.insertBefore(el, a.next);
        }
      } catch { /* 节点树已变：放弃归还，避免二次破坏 */ }
    }
    anchors = [];
    if (taskKey) { taskKey.remove(); taskKey = null; }
    document.documentElement.style.removeProperty('--mobile-float-bottom');
  };
}
