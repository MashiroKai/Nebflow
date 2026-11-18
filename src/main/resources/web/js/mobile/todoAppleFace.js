// mobile/todoAppleFace.js — Mobile todo pages laid out in the Apple Reminders
// information architecture.
//
// Four pages (registered in navController.PAGES; page shell, back button and
// slide transitions all reuse the existing pushPage/popPage/bindEdgeBack —
// this module only produces page-body content, no new navigation semantics):
//   tasks                lists home: white three-icon capsule top-right + 2x2
//                        colored four-card matrix + "My Lists" large-radius card
//                        (todoStore.tags) + bottom button band (left = quick
//                        decision, right = the blue + FAB)
//   todo-apple-list      list detail: large colored title left-aligned + hollow
//                        check-circle rows + centered empty state
//   todo-apple-item      create/edit: placeholder input row + (i) info button +
//                        keyboard-hugging toolbar
//   todo-quick-decision  quick decision: one card in the middle (content +
//                        question + options), left/right swipe switches todos
//
// Data discipline: complete/create/edit all go through todoStore (in-memory
// store, pure frontend mock); the backend is never touched and no new entities
// are invented — buttons whose backing capability is absent are simply not
// rendered.
//
// Dependency direction: this module does NOT statically import navController
// (that would form a static cycle and turn scripts/check-circular.mjs red);
// navigation = injected nav {push, pop} (same convention as meFace.js).

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { todoStore, TAG_COLOR_PRESETS, PRIORITY_COLORS } from '../todoStore.js';
import { isAskRow, buildAskCardForRow } from '../askTodoQuestion.js';
import { bindImeGuard, commitEnter } from '../imeGuard.js';
import state from '../state.js';
// 次级 LLM 输入框唯一模板（todo-mobile 批 2026-11-18 作者令）：详情页「回复这个
// 待办」的输入条 = Canvas Ask 那一条（同一装配点 js/input/secondaryComposer.js）。
import { createSecondaryComposer, SECONDARY_COMPOSER_MAX_H } from '../input/secondaryComposer.js';
// 列表选择器（作者令「标签必须有列表来选择」）：站内唯一列表菜单组件——手机层
// 自动变成全屏动作表（可滚动 + 左上返回 + 点空白/Esc 收起，见 contextMenu.js
// 与 mobileLayer.css「弹窗退场批」B 族），不在本页另造第二套列表。
import { showPopupMenu } from '../contextMenu.js';
// 统一定时器（作者令「调整定时应该使用我们统一的定时器设置风格」）：时间滚轮
// 单点面的唯一入口（scheduled-task / scheduleCard / sidebar / todoFace 同源；
// 手机层自动走 sheet 皮）。
import { openPoint } from '../timeWheel.js';

const DAY = 86_400_000;

/** @typedef {{ push: (pageId: string) => void, pop: () => void }} TaNav */
/** @typedef {'today'|'overdue'|'all'|'completed'|'tag'} ListMode */
/**
 * Item reading (element shape of the todoStore read-only projection).
 * @typedef {{ id: string, tagId: string, title: string, notes: string,
 *   due: number|null, priority: number, completed: boolean, createdAt: number }} TaItem
 */

// ── Small element factories ──────────────────────────────────────────────

/**
 * @param {string} tag
 * @param {string} cls
 * @param {string} [text]
 * @returns {HTMLElement}
 */
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

/**
 * @param {HTMLElement} parent
 * @param {string} tag
 * @param {string} cls
 * @param {string} [text]
 * @returns {HTMLElement}
 */
function elIn(parent, tag, cls, text) {
  const n = el(tag, cls, text);
  parent.appendChild(n);
  return n;
}

/** @param {string} cls @returns {HTMLButtonElement} */
function btnEl(cls) { return /** @type {HTMLButtonElement} */ (el('button', cls)); }

/** @param {string} cls @returns {HTMLInputElement} */
function inputEl(cls) { return /** @type {HTMLInputElement} */ (el('input', cls)); }

/**
 * Lucide icon carrier (`<i data-lucide="...">`; drawing is consolidated by
 * createIconsIn).
 * @param {string} icon
 * @param {string} [cls]
 * @returns {HTMLElement}
 */
function icoEl(icon, cls) {
  const i = el('i', cls);
  i.setAttribute('data-lucide', icon);
  return i;
}

/**
 * Icon button (lucide `<i data-lucide>`; drawing is consolidated by
 * createIconsIn).
 * @param {string} cls
 * @param {string} icon
 * @param {string} label
 * @returns {HTMLButtonElement}
 */
function iconBtn(cls, icon, label) {
  const b = btnEl(cls);
  b.type = 'button';
  b.setAttribute('aria-label', label);
  b.title = label;
  // Structured build (contract R5): one icon carrier, DOM identical to the
  // previous one-line template.
  b.appendChild(icoEl(icon));
  return b;
}

/** Local day boundary 00:00 (the due-today card and the "today" list share it). */
function startOfToday() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** Priority color (PRIORITY_COLORS is number-keyed; per-step reads avoid an index-signature error). */
/** @param {number} p @returns {string} */
function priorityColor(p) {
  if (p === 3) return PRIORITY_COLORS[3];
  if (p === 2) return PRIORITY_COLORS[2];
  if (p === 1) return PRIORITY_COLORS[1];
  return PRIORITY_COLORS[0];
}

/** Priority label (0 none / 1 low / 2 medium / 3 high; reuses the todoface.prio* entries). */
/** @param {number} p @returns {string} */
function priorityLabel(p) {
  if (p === 3) return t('todoface.prioHigh');
  if (p === 2) return t('todoface.prioMed');
  if (p === 1) return t('todoface.prioLow');
  return t('todoapple.none');
}

/** Short due text (today / tomorrow / M/D + time; 13px readable step). */
/** @param {number} ms @returns {string} */
function dueText(ms) {
  const d = new Date(ms);
  const hm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  const from = startOfToday();
  if (ms >= from && ms < from + DAY) return `${t('todoapple.today')} ${hm}`;
  if (ms >= from + DAY && ms < from + 2 * DAY) return `${t('todoapple.dueTomorrow')} ${hm}`;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

/**
 * 多行编辑域身高跟随内容（作者令「详情页应该标题正文要能完整显示」：容器高度
 * 不再由 CSS max-height 截断，也不出现内部滚动条）。手法与次级 LLM 输入框模板
 * 同一条（auto → scrollHeight）。
 * @param {HTMLTextAreaElement} node
 * @param {number} [cap] 上限（px；省略 = 不封顶）
 */
function autosizeField(node, cap) {
  if (!node) return;
  node.style.height = 'auto';
  const next = node.scrollHeight;
  node.style.height = `${typeof cap === 'number' ? Math.min(next, cap) : next}px`;
}

// ── 统一定时器 / 列表选择器 / 点空白收起（作者令 2026-11-18）───────────────
// 三条腿都**只调站内既有单源**，不在本页再造第二套：
//   · 定时 = timeWheel.openPoint（手机层自动 sheet 皮；与定时任务页/待办桌面面
//     同一个面板、同一档几何、同一收起语义）；
//   · 列表 = contextMenu.showPopupMenu（手机层 = 全屏可滚动动作表 + 左上返回）；
//   · 点空白收起 = 本模块唯一一处 document 级 pointerdown（首页展开面）。

/** 2 位补零（时间滚轮 date/time 串口径：YYYY-MM-DD / HH:mm）。 */
/** @param {number} n @returns {string} */
function pad2(n) { return String(n).padStart(2, '0'); }

/** 时间戳 → 触发钮落点（本地时区串；null = 无定时 ⇒ 清空数据集，选择器以
 *  「今天 09:00」起手但不写回草稿——「定时默认不打开」）。 */
/** @param {number|null} ms @returns {{date: string, time: string}|null} */
function pointOf(ms) {
  if (ms == null) return null;
  const d = new Date(ms);
  return { date: `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`, time: `${pad2(d.getHours())}:${pad2(d.getMinutes())}` };
}

/** 时间滚轮确认值 → 时间戳（ISO 无 Z = 本地时区，与面板显示同一把钟）。 */
/** @param {{date: string, time: string}} v @returns {number|null} */
function msOfPoint(v) {
  const ms = new Date(`${v.date}T${v.time}`).getTime();
  return Number.isNaN(ms) ? null : ms;
}

/**
 * 把一个按钮接到统一定时器（单点面）上。
 * @param {HTMLElement} btn 触发钮（本页的「日期」键）
 * @param {() => number|null} getMs 读草稿当前值
 * @param {(ms: number) => void} setMs 面板确认后写回草稿
 * @returns {() => void} 从草稿重绘触发钮落点（render 时调；面板确认路径同样经它）
 */
function bindPointTrigger(btn, getMs, setMs) {
  const paint = () => {
    const p = pointOf(getMs());
    if (p) { btn.dataset.date = p.date; btn.dataset.time = p.time; }
    else { delete btn.dataset.date; delete btn.dataset.time; }
  };
  paint();
  btn.addEventListener('click', () => {
    paint();
    openPoint(btn, {
      onConfirm: (v) => {
        const ms = msOfPoint(v);
        if (ms == null) return;
        btn.dataset.date = v.date;
        btn.dataset.time = v.time;
        setMs(ms);
      },
    });
  });
  return paint;
}

/**
 * 标签列表选择器（作者令「标签必须有列表来选择 因为以后可能会有很多标签」）。
 * 列表 = 站内唯一列表菜单组件（手机层 = 全屏动作表，纵向可滚 + 左上返回 +
 * 点空白/Esc 收起）；当前标签带选中态。
 * @param {HTMLElement} trigger
 * @param {() => string|null} getTagId
 * @param {(tagId: string|null) => void} setTagId
 */
function openTagPicker(trigger, getTagId, setTagId) {
  const rect = trigger.getBoundingClientRect();
  const current = getTagId();
  /** @type {Array<{label: string, checked: boolean, onClick: () => void}>} */
  const items = todoStore.tags.map((tg) => ({
    label: tg.name,
    checked: tg.id === current,
    onClick: () => setTagId(tg.id),
  }));
  items.push({ label: t('todoapple.none'), checked: current == null, onClick: () => setTagId(null) });
  showPopupMenu(rect.left, rect.bottom + 6, items);
}

// ── Module state (list target / item target / draft / home inline state) ──

/** @type {{ mode: ListMode, tagId: string|null }} */
let listTarget = { mode: 'all', tagId: null };
/** @type {string|null} */
let itemTarget = null;
/** @type {{ title: string, notes: string, due: number|null, priority: number, tagId: string|null }} */
let draft = { title: '', notes: '', due: null, priority: 0, tagId: null };
let searchOpen = false;
let searchQuery = '';
let newListOpen = false;
let moreOpen = false;
/** Retain an unconfirmed creation across page navigation. */
let pendingCreate = null;
try { pendingCreate = JSON.parse(sessionStorage.getItem('stand.todo.pendingCreate') || 'null'); } catch { /* storage unavailable */ }
function persistPendingCreate() {
  try {
    if (pendingCreate) sessionStorage.setItem('stand.todo.pendingCreate', JSON.stringify(pendingCreate));
    else sessionStorage.removeItem('stand.todo.pendingCreate');
  } catch { /* retain the in-memory draft */ }
}

/**
 * Idempotency key for one todo create. The server accepts only a lowercase v4 UUID
 * (`WsTasksWorkspaceHandlers` requestId regex); `crypto.randomUUID` is absent in
 * non-secure contexts, so the fallbacks must still produce exactly that shape.
 * @returns {string}
 */
function newCreateRequestId() {
  try {
    const c = globalThis.crypto;
    if (c && typeof c.randomUUID === 'function') return c.randomUUID();
    if (c && typeof c.getRandomValues === 'function') {
      const b = new Uint8Array(16);
      c.getRandomValues(b);
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
      return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
    }
  } catch { /* fall through */ }
  const hex = () => Math.floor(Math.random() * 0x10000).toString(16).padStart(4, '0');
  const time = Date.now().toString(16).padStart(12, '0').slice(-12);
  return `${time.slice(0, 8)}-${time.slice(8, 12)}-4${hex().slice(1)}-${(8 + Math.floor(Math.random() * 4)).toString(16)}${hex().slice(1)}-${hex()}${hex()}${hex()}`;
}

/** @param {ListMode} mode @param {string|null} tagId @param {TaNav} nav */
function openList(mode, tagId, nav) {
  listTarget = { mode, tagId };
  searchOpen = false;
  moreOpen = false;
  newListOpen = false;
  nav.push('todo-apple-list');
}

/** @param {string|null} itemId @param {TaNav} nav */
function openItem(itemId, nav) {
  itemTarget = itemId;
  nav.push('todo-apple-item');
}

/** Create without replacing the Apple list page or its scroll position.
 * @param {HTMLElement} section
 * @param {string|null} initialTagId
 * @param {HTMLButtonElement} trigger
 */
function openCreateSheet(section, initialTagId, trigger) {
  if (section.querySelector('[data-todo-create-overlay]')) return;
  const firstTag = todoStore.tags[0];
  let tagId = pendingCreate?.tagId ?? initialTagId ?? (firstTag ? firstTag.id : null);
  let priority = pendingCreate?.priority ?? 0;
  const overlay = elIn(section, 'div', 'ta-create-overlay');
  overlay.dataset.todoCreateOverlay = '';
  const sheet = elIn(overlay, 'div', 'ta-create-sheet nf-glass');
  sheet.dataset.todoCreateSheet = '';
  sheet.setAttribute('role', 'dialog');
  sheet.setAttribute('aria-label', t('todoapple.newTodo'));
  const actions = elIn(sheet, 'div', 'ta-create-actions');
  const cancel = btnEl('ta-create-cancel');
  cancel.type = 'button';
  cancel.textContent = t('todoface.cancel');
  const save = btnEl('ta-create-save');
  save.type = 'button';
  save.textContent = t('todoapple.save');
  actions.append(cancel, save);
  // D 批（同上）：新建名输入面同样换 textarea（多行名可创建）。
  const title = /** @type {HTMLTextAreaElement} */ (el('textarea', 'ta-create-title'));
  title.rows = 1;
  title.placeholder = t('todoapple.newTodo');
  title.setAttribute('aria-label', t('todoapple.newTodo'));
  bindImeGuard(title);
  const notes = /** @type {HTMLTextAreaElement} */ (el('textarea', 'ta-create-notes'));
  notes.rows = 3;
  notes.placeholder = t('todoapple.notes');
  notes.setAttribute('aria-label', t('todoapple.notes'));
  bindImeGuard(notes);
  sheet.append(title, notes);
  const options = elIn(sheet, 'div', 'ta-create-options');
  const flag = btnEl('ta-create-option');
  flag.type = 'button';
  const tag = btnEl('ta-create-option');
  tag.type = 'button';
  options.append(flag, tag);
  const renderOptions = () => {
    flag.textContent = `${t('todoapple.toPriority')}: ${priorityLabel(priority)}`;
    tag.textContent = `${t('todoapple.toTag')}: ${tagId ? (todoStore.tagById(tagId)?.name || t('todoapple.none')) : t('todoapple.none')}`;
  };
  renderOptions();
  // 重要度：作者 2026-11-18 明示「重要度可以这么切换」⇒ 保持点击循环（不改）。
  flag.addEventListener('click', () => { priority = (priority + 1) % 4; renderOptions(); });
  // 标签：作者令「标签必须有列表来选择 因为以后可能会有很多标签」⇒ 点击换成
  // 站内列表菜单（全屏可滚动动作表）；不再是「点击推进到下一个」的循环。
  tag.addEventListener('click', () => openTagPicker(tag, () => tagId, (next) => { tagId = next; renderOptions(); }));
  const close = () => {
    document.removeEventListener('nf:mobile-nav', onNavigate);
    overlay.remove();
    if (trigger.isConnected) trigger.focus();
  };
  const onNavigate = () => close();
  document.addEventListener('nf:mobile-nav', onNavigate);
  cancel.addEventListener('click', close);
  overlay.addEventListener('click', (event) => { if (event.target === overlay) close(); });
  overlay.addEventListener('keydown', (event) => { if (event.key === 'Escape') close(); });
  const error = elIn(sheet, 'div', 'ta-create-error');
  error.setAttribute('role', 'alert');
  if (pendingCreate) {
    title.value = pendingCreate.title;
    notes.value = pendingCreate.notes;
    title.readOnly = notes.readOnly = true;
    flag.disabled = tag.disabled = true;
    // The retry is pinned to its original session/identity. If that can no longer succeed
    // (session switched, or the row was edited after a lost receipt), the user needs an
    // explicit way out that is not "close the tab": drop the identity, keep the text, and
    // allow a fresh create. The original row is never guessed away.
    const discard = btnEl('ta-create-discard');
    discard.type = 'button';
    discard.textContent = t('todoapple.discardPending');
    discard.addEventListener('click', () => {
      pendingCreate = null;
      persistPendingCreate();
      title.readOnly = notes.readOnly = false;
      flag.disabled = tag.disabled = false;
      error.textContent = '';
      discard.remove();
    });
    actions.insertBefore(discard, save);
  }
  let saving = false;
  const submit = async () => {
    const value = title.value.trim();
    if (!value || saving) { if (!value) title.focus(); return; }
    if (!state.activeSessionId) { error.textContent = t('todoapple.createUnconfirmed'); return; }
    if (!pendingCreate) {
      pendingCreate = { tagId, title: value, notes: notes.value, priority, sessionId: state.activeSessionId, requestId: newCreateRequestId() };
      persistPendingCreate();
    }
    saving = true;
    save.disabled = true;
    title.readOnly = notes.readOnly = true;
    flag.disabled = tag.disabled = true;
    error.textContent = '';
    const ok = await todoStore.createItemConfirmed(pendingCreate);
    saving = false;
    save.disabled = false;
    if (ok) { pendingCreate = null; persistPendingCreate(); close(); }
    else error.textContent = t('todoapple.createUnconfirmed');
  };
  save.addEventListener('click', submit);
  title.addEventListener('keydown', (event) => { if (commitEnter(event, title)) submit(); });
  requestAnimationFrame(() => { if (overlay.isConnected) title.focus(); });
}

/** Draft reset (rebuilt on every item-page entry: edit = the item's current reading; create = the list page's current tag). */
function resetDraft() {
  if (itemTarget) {
    const it = todoStore.allItems().find((x) => x.id === itemTarget) || null;
    draft = it
      ? { title: it.title, notes: it.notes, due: it.due, priority: it.priority, tagId: it.tagId }
      : { title: '', notes: '', due: null, priority: 0, tagId: null };
    return;
  }
  const first = todoStore.tags[0];
  draft = { title: '', notes: '', due: null, priority: 0, tagId: listTarget.tagId || (first ? first.id : null) };
}

// ── 点空白收起（作者令 2026-11-18「点击空白处要能关闭各种弹窗面板」）──────
// 首页三处**展开面**（搜索条 / 新建列表行 / 更多菜单）此前只能再点同一颗钮才
// 收——点空白没有任何反应。判定只此一处：document 级 pointerdown（捕获相），
// 命中「胶囊本体 / 菜单 / 搜索条 / 新建列表行」之外即收起三面。
// 排除胶囊是必要的：pointerdown 先于 click，若此处把 moreOpen 关掉，紧接着的
// 胶囊 click 会立刻把它翻回来（同一击抖动一次）。
// 弹窗/选择器的点空白收起归各自单源：创建浮层 = 自己的 overlay 命中判定；
// 列表选择器 = contextMenu 的全局收起；定时面板 = timeWheel 的 outsideCloser。
let dismissBound = false;
function bindPanelDismiss() {
  if (dismissBound) return;
  dismissBound = true;
  document.addEventListener('pointerdown', (event) => {
    if (!moreOpen && !searchOpen && !newListOpen) return;
    if (!(event.target instanceof Element)) return;
    if (event.target.closest('.ta-pill, .ta-menu, .ta-searchbar, .ta-row-new')) return;
    moreOpen = false;
    searchOpen = false;
    newListOpen = false;
    const home = mounts.get('tasks');
    if (home) home.render();
  }, true);
}

// ── Keyboard avoidance (minimal visualViewport implementation; imeGuard only
// knows composition state, not keyboard geometry) ──
// The toolbar must hug the keyboard. Implementation: listen to visualViewport
// resize/scroll and write "window height − (visual height + visual offset)"
// into --ta-kb on the root; the toolbar CSS reads it in its bottom calc().
// Legacy WebViews without visualViewport degrade to --ta-kb staying 0, so the
// toolbar rests on the dock's top edge (resting position) with zero errors
// and zero occlusion.
let kbBound = false;
function bindKeyboardAvoidance() {
  if (kbBound) return;
  const vv = window.visualViewport;
  if (!vv) return;
  kbBound = true;
  const apply = () => {
    const kb = Math.max(0, window.innerHeight - (vv.height + vv.offsetTop));
    document.documentElement.style.setProperty('--ta-kb', `${Math.round(kb)}px`);
  };
  vv.addEventListener('resize', apply);
  vv.addEventListener('scroll', apply);
  apply();
}

// ── Mount table + single-flight rerender subscription ────────────────────
/** @type {Map<string, { root: HTMLElement, render: () => void }>} */
const mounts = new Map();
let subscribed = false;
let localeBound = false;

function rerenderAll() {
  for (const m of mounts.values()) {
    try { m.render(); } catch (e) { console.error('[todo-apple] render', e); }
  }
}

function ensureSubscribed() {
  if (!subscribed) {
    subscribed = true;
    // The Completed card and list read archived rows, which the store only
    // delivers on opt-in (includeCompleted). Refresh before subscribe so the
    // lazy first pull inside subscribe becomes a no-op (one frame, not two).
    todoStore.refresh({ includeCompleted: true });
    todoStore.subscribe(() => rerenderAll());
  }
  if (!localeBound) {
    localeBound = true;
    window.addEventListener('locale-changed', () => rerenderAll());
  }
  bindKeyboardAvoidance();
  bindPanelDismiss();
}

/** Page shell (section) — floating chrome (capsule/FAB/toolbar) hangs here so it does not scroll with the body. */
/** @param {Element} body @returns {HTMLElement|null} */
function sectionOf(body) {
  const s = body.closest('.mobile-page');
  return s instanceof HTMLElement ? s : null;
}

/** Clear stale chrome (the page shell is persistent; leave-and-reenter must not stack a second set of floating pieces). */
/** @param {HTMLElement} section */
function clearChrome(section) {
  for (const sel of ['.ta-cluster', '.ta-fab-wrap', '.ta-menu', '.ta-tool']) {
    for (const n of section.querySelectorAll(`:scope > ${sel}`)) n.remove();
  }
}

// ══════════════════════════════════════════════════════════════════════
// A · Lists home page (tasks)
// ══════════════════════════════════════════════════════════════════════

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @param {TaNav} nav
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildHome(root, section, nav) {
  // ── Four-card matrix (2x2): white outline icon top-left / large white
  // count top-right / white label bottom-left ──
  const grid = elIn(root, 'div', 'ta-grid');
  /**
   * @param {'today'|'overdue'|'all'|'completed'} kind
   * @param {string} icon
   * @returns {HTMLButtonElement}
   */
  const card = (kind, icon) => {
    const b = /** @type {HTMLButtonElement} */ (elIn(grid, 'button', `ta-card ta-card-${kind}`));
    b.type = 'button';
    // Structured build (contract R5): icon + two stat spans, DOM identical to
    // the previous template string.
    b.append(icoEl(icon, 'ta-card-ico'), el('span', 'ta-card-n'), el('span', 'ta-card-l'));
    return b;
  };
  const cToday = card('today', 'calendar-days');
  const cOverdue = card('overdue', 'circle-alert');
  const cAll = card('all', 'list-todo');
  const cDone = card('completed', 'circle-check');
  cToday.addEventListener('click', () => openList('today', null, nav));
  cOverdue.addEventListener('click', () => openList('overdue', null, nav));
  cAll.addEventListener('click', () => openList('all', null, nav));
  cDone.addEventListener('click', () => openList('completed', null, nav));

  // ── Inline search bar (expanded by the capsule search key; local filtering
  // = real capability, no backend) ──
  const searchWrap = elIn(root, 'div', 'ta-searchbar');
  searchWrap.hidden = true;
  const searchInput = inputEl('ta-search-input');
  searchInput.type = 'search';
  searchInput.autocomplete = 'off';
  bindImeGuard(searchInput);
  searchWrap.appendChild(searchInput);
  searchInput.addEventListener('input', () => { searchQuery = searchInput.value; render(); });
  searchInput.addEventListener('keydown', (e) => { if (commitEnter(e, searchInput)) searchInput.blur(); });

  // ── "My Lists" section heading (bold body-colored text, not inside a card)
  // + large-radius surface card ──
  const secLabel = elIn(root, 'div', 'ta-sec-label');
  const cardBox = elIn(root, 'div', 'ta-listcard');
  const rowsBox = elIn(cardBox, 'div', 'ta-rows');
  // New-list row (expanded by the capsule "new list" key; Enter persists it
  // via the real todoStore.createTag capability)
  const newRow = elIn(cardBox, 'div', 'ta-row ta-row-new');
  newRow.hidden = true;
  elIn(newRow, 'span', 'ta-dot ta-dot-new');
  const nlInput = inputEl('ta-newlist-input nf-field--bare');
  nlInput.type = 'text';
  nlInput.autocomplete = 'off';
  bindImeGuard(nlInput);
  newRow.appendChild(nlInput);
  const commitNewList = () => {
    const name = nlInput.value.trim();
    if (name) {
      const color = TAG_COLOR_PRESETS[todoStore.tags.length % TAG_COLOR_PRESETS.length];
      todoStore.createTag(name, color);
    }
    nlInput.value = '';
    newListOpen = false;
    render();
  };
  nlInput.addEventListener('keydown', (e) => { if (commitEnter(e, nlInput)) commitNewList(); });
  nlInput.addEventListener('blur', () => { if (newListOpen) commitNewList(); });

  // ── White three-icon capsule at the top right (left end stays empty for the
  // back button; all three keys have real backing capabilities) ──
  const cluster = elIn(section, 'div', 'ta-cluster');
  // 左槽隐形占位（ui-unify-panels 批 2026-11-17）：首页顶带左槽没有返回钮动作
  // ⇒ 按带几何口径放等宽 invisible spacer 保三区结构（子页同槽 = 壳返回钮）。
  // 绝不渲染可点但无功能的假按钮：spacer aria-hidden + pointer-events none。
  const slotSpacer = elIn(cluster, 'div', 'ta-slot-spacer');
  slotSpacer.setAttribute('aria-hidden', 'true');
  const pill = elIn(cluster, 'div', 'ta-pill nf-glass');
  const bSearch = iconBtn('ta-pill-btn', 'search', t('todoapple.search'));
  const bNewList = iconBtn('ta-pill-btn', 'list-plus', t('todoapple.newList'));
  const bMore = iconBtn('ta-pill-btn', 'ellipsis', t('todoapple.more'));
  pill.append(bSearch, bNewList, bMore);
  bSearch.addEventListener('click', () => {
    searchOpen = !searchOpen;
    moreOpen = false;
    if (!searchOpen) searchInput.value = searchQuery = '';
    render();
    if (searchOpen) searchInput.focus();
  });
  bNewList.addEventListener('click', () => {
    newListOpen = !newListOpen;
    moreOpen = false;
    render();
    if (newListOpen) nlInput.focus();
  });
  bMore.addEventListener('click', () => { moreOpen = !moreOpen; render(); });

  // More menu (two entries = real entries: completed view / all todos)
  const menu = elIn(section, 'div', 'ta-menu nf-glass');
  menu.hidden = true;
  const mDone = btnEl('ta-menu-row');
  mDone.type = 'button';
  // Structured build (contract R5): two label spans, DOM identical to the
  // previous template string.
  mDone.append(el('span', 'ta-menu-l'), el('span', 'ta-menu-n'));
  const mAll = btnEl('ta-menu-row');
  mAll.type = 'button';
  mAll.append(el('span', 'ta-menu-l'), el('span', 'ta-menu-n'));
  menu.append(mDone, mAll);
  mDone.addEventListener('click', () => { moreOpen = false; openList('completed', null, nav); });
  mAll.addEventListener('click', () => { moreOpen = false; openList('all', null, nav); });

  // ── Bottom button band: quick-decision entry (left) + new todo FAB (right) ──
  // One positioning host (.ta-fab-wrap; absolute placement, kept out of the
  // chrome scan band — see the CSS note) spans the content width and lays the
  // two round buttons out with space-between. Both are the same base class
  // (.g2-fab = material/geometry/spring single source) at the same --band-d
  // size, one at each end of the same bottom line ⇒ one group, symmetric
  // insets, zero overlap. The band itself is pointer-transparent (CSS) so the
  // list behind it stays tappable.
  //
  // Left key = quick decision (the author's 2026-11-18 request: 叠卡 icon →
  // dating-app card view). Icon = lucide `gallery-vertical` (author order
  // 2026-11-18 22:04「换一个图标」) — still the station's ONE glyph source
  // (vendor/lucide.min.js through createIconsIn), no second glyph set. `layers`
  // read as "图层" (canvas layers) and stole the deck's meaning; a stacked-card
  // glyph reads as one deck at a glance.
  // Colour = the sapphire family, one rung BELOW the right `+` key: tint fill +
  // sapphire ink + sapphire semantic edge (CSS, .ta-quick-fab). The `+` stays
  // this page's only solid primary action. Right key = the pre-existing blue +
  // (create); the two keys keep the same 48×48 box, bottom edge and hit box.
  const fabWrap = elIn(section, 'div', 'ta-fab-wrap');
  const quickFab = iconBtn('ta-quick-fab g2-fab', 'gallery-vertical', t('todoapple.quickDecision'));
  quickFab.dataset.todoQuickEntry = '';
  // 叠卡张数徽标（作者令 2026-11-18 22:04「右上角有计数」）：形态 = 既有数字角标
  // （.dock-badge.is-count，mobileLayer.css 单源），不新起第四种通知形态；本文件
  // 只负责读数与落位（CSS）。数 = 牌堆自己的长度，见 render()。
  const quickCount = el('span', 'dock-badge is-count');
  quickCount.hidden = true;
  quickFab.appendChild(quickCount);
  const fab = iconBtn('ta-fab g2-fab', 'plus', t('todoapple.newTodo'));
  fabWrap.append(quickFab, fab);
  quickFab.addEventListener('click', () => nav.push('todo-quick-decision'));
  fab.addEventListener('click', () => openCreateSheet(section, null, fab));

  /** @returns {void} */
  function render() {
    // Card readings: today = due today and open / overdue = past due and open /
    // all = total open / completed = completed count
    /** @param {HTMLButtonElement} b @param {string} label @param {number} n */
    const setCard = (b, label, n) => {
      b.classList.toggle('is-empty', n === 0);
      /** @type {HTMLElement} */ (b.querySelector('.ta-card-n')).textContent = String(n);
      /** @type {HTMLElement} */ (b.querySelector('.ta-card-l')).textContent = label;
    };
    setCard(cToday, t('todoapple.today'), todoStore.dueTodayCount());
    setCard(cOverdue, t('todoapple.overdue'), todoStore.overdueCount());
    setCard(cAll, t('todoapple.all'), todoStore.openCount());
    setCard(cDone, t('todoapple.completed'), todoStore.completedCount());

    // 叠卡张数徽标（作者令 2026-11-18 22:04）：数 = **牌堆自己的长度** ——
    // quickItems() 就是快速决策页的牌堆（同一数据源、同一过滤函数），此处不另算
    // 一份过滤条件，故徽标数与实际可滑的张数逐张相等。0 张隐藏、>99 记 `99+`
    // （与桌面 #messages-badge 的同一归一口径）。写回幂等（同值不写）——监听
    // 复用同一渲染链，无守卫的赋值会自触发。
    const deckN = quickItems().length;
    // 0 张 ⇒ 空串 + hidden（与复用面 applyDockBadges 的「空串/0 ⇒ hidden」同一口径，
    // 不靠 CSS 单独遮一个「0」字样）；>99 ⇒ `99+`。
    const deckText = deckN === 0 ? '' : (deckN > 99 ? '99+' : String(deckN));
    if (quickCount.textContent !== deckText) quickCount.textContent = deckText;
    if (quickCount.hidden !== (deckN === 0)) quickCount.hidden = deckN === 0;

    // Capsule state
    bSearch.classList.toggle('on', searchOpen);
    bNewList.classList.toggle('on', newListOpen);
    bMore.classList.toggle('on', moreOpen);
    searchWrap.hidden = !searchOpen;
    searchInput.placeholder = t('todoapple.searchPh');
    newRow.hidden = !newListOpen;
    nlInput.placeholder = t('todoapple.newListPh');

    // More menu
    menu.hidden = !moreOpen;
    /** @type {HTMLElement} */ (mDone.querySelector('.ta-menu-l')).textContent = t('todoapple.completed');
    /** @type {HTMLElement} */ (mDone.querySelector('.ta-menu-n')).textContent = String(todoStore.completedCount());
    /** @type {HTMLElement} */ (mAll.querySelector('.ta-menu-l')).textContent = t('todoapple.allTodos');
    /** @type {HTMLElement} */ (mAll.querySelector('.ta-menu-n')).textContent = String(todoStore.openCount());

    // Section heading
    secLabel.textContent = searchOpen ? t('todoapple.searchResults') : t('todoapple.myLists');

    // List card rows
    rowsBox.replaceChildren();
    if (searchOpen) {
      const q = searchQuery.trim().toLowerCase();
      const hits = todoStore.allItems().filter((it) => !it.completed
        && (!q || it.title.toLowerCase().includes(q)
          || String(it.notes || '').toLowerCase().includes(q)));
      for (const it of hits.slice(0, 30)) {
        const tg = todoStore.tagById(it.tagId);
        const row = btnEl('ta-row ta-row-hit');
        row.type = 'button';
        // Structured build (contract R5): dot + label + sub spans, DOM
        // identical to the previous template string.
        row.append(el('span', 'ta-dot'), el('span', 'ta-row-label'), el('span', 'ta-row-sub'));
        const dot = /** @type {HTMLElement} */ (row.querySelector('.ta-dot'));
        dot.style.background = tg ? tg.color : 'var(--color-text-muted)';
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-label')).textContent = it.title || '—';
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-sub')).textContent = tg ? tg.name : '';
        row.addEventListener('click', () => openItem(it.id, nav));
        rowsBox.appendChild(row);
      }
      if (!hits.length) rowsBox.appendChild(el('div', 'ta-row-empty', t('todoapple.searchEmpty')));
    } else {
      for (const tg of todoStore.tags) {
        const row = btnEl('ta-row ta-row-tag');
        row.type = 'button';
        // Structured build (contract R5): dot + label + count + chevron, DOM
        // identical to the previous template string.
        row.append(el('span', 'ta-dot'), el('span', 'ta-row-label'),
          el('span', 'ta-row-count'), icoEl('chevron-right', 'ta-chev'));
        const dot = /** @type {HTMLElement} */ (row.querySelector('.ta-dot'));
        dot.style.background = tg.color;
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-label')).textContent = tg.name;
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-count')).textContent = String(todoStore.openCountOf(tg.id));
        row.addEventListener('click', () => openList('tag', tg.id, nav));
        rowsBox.appendChild(row);
      }
      // Last row "All todos" grey row = the All entry (with no tags it is the
      // only row, i.e. the degraded form)
      const allRow = btnEl('ta-row ta-row-all');
      allRow.type = 'button';
      // Structured build (contract R5): same shape as the tag row (grey dot),
      // DOM identical to the previous template string.
      allRow.append(el('span', 'ta-dot ta-dot-all'), el('span', 'ta-row-label'),
        el('span', 'ta-row-count'), icoEl('chevron-right', 'ta-chev'));
      /** @type {HTMLElement} */ (allRow.querySelector('.ta-row-label')).textContent = t('todoapple.allTodos');
      /** @type {HTMLElement} */ (allRow.querySelector('.ta-row-count')).textContent = String(todoStore.openCount());
      allRow.addEventListener('click', () => openList('all', null, nav));
      rowsBox.appendChild(allRow);
    }

    if (typeof lucide !== 'undefined') createIconsIn(section);
  }

  return { root, render };
}

// ══════════════════════════════════════════════════════════════════════
// B · List detail page (todo-apple-list)
// ══════════════════════════════════════════════════════════════════════

/** Items under the current list target (local read-only filtering; the store's filter state is never written). */
function itemsForTarget() {
  // 可见行（含问句条目；归属变化后即时收敛）——单向读口，见 todoStore.surfacedItems。
  const all = todoStore.surfacedItems();
  const now = Date.now();
  const from = startOfToday();
  const to = from + DAY;
  /**
   * @param {TaItem} it
   * @returns {boolean}
   */
  const pick = (it) => {
    switch (listTarget.mode) {
      case 'completed': return it.completed;
      case 'today': return !it.completed && it.due != null && it.due >= from && it.due < to;
      case 'overdue': return !it.completed && it.due != null && it.due < now;
      case 'tag': return !it.completed && it.tagId === listTarget.tagId;
      default: return !it.completed;
    }
  };
  return all.filter(pick).sort((a, b) => {
    if (a.priority !== b.priority) return b.priority - a.priority;
    if (a.due != null && b.due != null) return a.due - b.due;
    if (a.due == null && b.due == null) return a.createdAt - b.createdAt;
    return a.due == null ? 1 : -1;
  });
}

/** List heading (text + color): mode pages take theme tokens, tag pages take tag.color (data color). */
/** @returns {{ text: string, color: string }} */
function listHead() {
  switch (listTarget.mode) {
    case 'completed': return { text: t('todoapple.completed'), color: 'var(--color-text-muted)' };
    case 'today': return { text: t('todoapple.today'), color: 'rgb(var(--sapphire))' };
    case 'overdue': return { text: t('todoapple.overdue'), color: 'var(--color-error)' };
    case 'tag': {
      const tg = listTarget.tagId ? todoStore.tagById(listTarget.tagId) : null;
      return { text: tg ? tg.name : t('todoapple.allTodos'), color: tg ? tg.color : 'var(--color-text)' };
    }
    default: return { text: t('todoapple.allTodos'), color: 'var(--color-text)' };
  }
}

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @param {TaNav} nav
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildList(root, section, nav) {
  const wrap = elIn(root, 'div', 'ta-detail');
  const title = elIn(wrap, 'h1', 'ta-dtitle');
  const itemsBox = elIn(wrap, 'div', 'ta-items');
  const empty = elIn(wrap, 'div', 'ta-empty');

  // White capsule top right: the only backed capability = create a todo into
  // this list (createItem)
  const cluster = elIn(section, 'div', 'ta-cluster');
  const pill = elIn(cluster, 'div', 'ta-pill nf-glass');
  const addBtn = iconBtn('ta-pill-btn', 'list-plus', t('todoapple.newTodo'));
  pill.appendChild(addBtn);
  addBtn.addEventListener('click', () => openCreateSheet(section, listTarget.mode === 'tag' ? listTarget.tagId : null, addBtn));

  /** @returns {void} */
  function render() {
    const head = listHead();
    title.textContent = head.text;
    title.style.color = head.color;
    const list = itemsForTarget();
    itemsBox.replaceChildren();
    for (const it of list) {
      const row = el('div', 'ta-item');
      row.classList.toggle('is-done', it.completed);
      row.dataset.itemId = it.id;

      // Left hollow-circle checkbox: visual 24, hit area 48x48 (>=44 contract)
      const check = btnEl('ta-check');
      check.type = 'button';
      check.setAttribute('aria-label', t('todoapple.markComplete'));
      // Structured build (contract R5): single circle span, DOM identical to
      // the previous template string.
      check.appendChild(el('span', 'ta-check-circle'));
      // 问句条目（引擎 Ask 镜像行）没有打卡语义（销账归 ask 流）——不显圆钮动作。
      const askRow = isAskRow(it);
      if (askRow) {
        row.dataset.askEntry = it.requestId; // 对账读数（非定位面）
        check.disabled = true;
        check.setAttribute('aria-label', t('askUser.pendingTitle'));
      }
      check.addEventListener('click', (e) => {
        e.stopPropagation();
        if (askRow) return;
        todoStore.completeItem(it.id);
      });
      row.appendChild(check);

      // Priority mark: only when priority > 0 (blue/orange/red data colors)
      if (it.priority > 0) {
        const prio = el('span', 'ta-prio');
        prio.style.background = priorityColor(it.priority);
        prio.setAttribute('aria-hidden', 'true');
        row.appendChild(prio);
      }

      // Row body = text button (click drills into the item detail; same
      // push/pop stack + back gesture)
      const bodyBtn = btnEl('ta-item-body');
      bodyBtn.type = 'button';
      elIn(bodyBtn, 'span', 'ta-item-title', it.title || '—');
      // 问句条目：标题旁一个「待答」标记（**行内**，不是列表上方区块）。
      if (askRow) elIn(bodyBtn, 'span', 'ta-item-due', t('askUser.todoBadge'));
      if (it.due != null) elIn(bodyBtn, 'span', 'ta-item-due', dueText(it.due));
      row.appendChild(bodyBtn);
      if (askRow) {
        // 承载面 = 条目自身：点开行内详情区 = **问题卡**（单点模板 askTodoQuestion.js；
        // 作答/取消走 askPending 的同一收口 ⇒ 与原卡片两侧同步收口）。
        bodyBtn.setAttribute('aria-expanded', 'false');
        const detail = el('div', 'ta-decision-detail');
        detail.hidden = true;
        const card = buildAskCardForRow(it);
        if (card) detail.appendChild(card);
        bodyBtn.addEventListener('click', () => {
          detail.hidden = !detail.hidden;
          bodyBtn.setAttribute('aria-expanded', String(!detail.hidden));
        });
        itemsBox.append(row, detail);
      } else if (it.decision) {
        // 旧「决策待办」行（服务端带 decision 的普通待办）：保留既有只读折叠面
        // （本轮不接线；问句条目的作答接线见上支）。
        bodyBtn.setAttribute('aria-expanded', 'false');
        const detail = el('div', 'ta-decision-detail');
        detail.hidden = true;
        elIn(detail, 'div', 'ta-decision-question', String(it.decision.question || ''));
        const options = Array.isArray(it.decision.options) ? it.decision.options : [];
        options.forEach((option) => {
          const label = elIn(detail, 'label', 'ta-decision-option');
          const input = document.createElement('input');
          input.type = it.decision.multiSelect ? 'checkbox' : 'radio';
          input.disabled = true;
          label.appendChild(input);
          elIn(label, 'span', '', String(option));
        });
        bodyBtn.addEventListener('click', () => {
          detail.hidden = !detail.hidden;
          bodyBtn.setAttribute('aria-expanded', String(!detail.hidden));
        });
        itemsBox.append(row, detail);
      } else {
        bodyBtn.addEventListener('click', () => openItem(it.id, nav));
        itemsBox.appendChild(row);
      }
    }
    empty.hidden = list.length > 0;
    empty.textContent = listTarget.mode === 'completed'
      ? t('todoapple.emptyCompleted') : t('todoapple.emptyDone');
    if (typeof lucide !== 'undefined') createIconsIn(section);
  }

  return { root, render };
}

// ══════════════════════════════════════════════════════════════════════
// C · Create / edit state (todo-apple-item)
// ══════════════════════════════════════════════════════════════════════

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @param {TaNav} nav
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildItem(root, section, nav) {
  const wrap = elIn(root, 'div', 'ta-edit');
  const row = elIn(wrap, 'div', 'ta-newrow');
  // D 批（ui-unify-panels 2026-11-17「待办内容不要截断，展示与编辑都多行」）：
  // 名编辑域 input → textarea（多行可编；保存走底部 ta-save 簇）。
  const input = /** @type {HTMLTextAreaElement} */ (elIn(row, 'textarea', 'ta-title-input nf-field--bare'));
  input.rows = 1;
  bindImeGuard(input);
  const infoBtn = iconBtn('ta-info', 'info', t('todoapple.info'));
  row.appendChild(infoBtn);
  const notes = /** @type {HTMLTextAreaElement} */ (elIn(wrap, 'textarea', 'ta-notes'));
  notes.rows = 3;
  // 详情页（编辑态）默认把**正文**摊开（作者令「详情页应该标题正文要能完整显示」）；
  // 新建态仍默认收起（作者令「定时区/展开面默认不打开」的同一条收敛口径），点 (i) 展开。
  let notesOpen = !!itemTarget;
  notes.hidden = !notesOpen;
  bindImeGuard(notes);
  const meta = elIn(wrap, 'div', 'ta-meta');
  // 回复/恢复这个待办 = **次级 LLM 输入框**唯一模板（作者令 2026-11-18）：
  // 与 Canvas Ask 输入条同一装配点、同一玻璃外壳、同一裸件 textarea、同一
  // ds-turn-btn 圆钮 —— 图2 里那个脱样式的小「发送」按钮随批退役。
  // 作者令 2026-11-18 追加批：「详情页正文可以默认展开 但是这个对话框应该挨着底部
  // 而且应该跟主会话这个一样 可以根据内容长度变化」。
  // 装配点 = **`root`（.ta-root）的最后一个孩子**，不是 `.ta-edit` 的——正文区
  // `.ta-edit` 是**本页自己的滚动面**（overflow-y:auto），回复条是它的**流内页脚**
  // （flex: 0 0 auto + margin-top:auto），与主会话「`.fm-flow` 滚 + 输入条是
  // 兄弟节点常驻底部」是同一条结构。这样贴底不需要任何定位（position）声明，
  // 正文永远不会滚到回复条下面（改前它在 `.ta-edit` 里，只能用 sticky 压住）。
  const replyBox = elIn(root, 'div', 'ta-reply');
  const replyInput = createSecondaryComposer({
    identity: 'ta-reply-input',
    placeholder: t('todoface.replyPh'),
    ariaLabel: t('todoface.replyPh'),
    sendLabel: t('todoface.send'),
    // 自增高封顶 = 主会话输入框同一口径（作者令「应该跟主会话这个一样，可以
    // 根据内容长度变化」）。唯一声明点 = secondaryComposer.js 的常量；本页
    // 不再自己压一个 132（改前这里就是第三个数）。
    maxHeight: SECONDARY_COMPOSER_MAX_H,
    onSend: () => { void sendReply(); },
  });
  replyBox.appendChild(replyInput.root);
  const replyError = elIn(replyBox, 'div', 'ta-reply-error');
  replyError.setAttribute('role', 'alert');
  let replySending = false;
  /** 回复这个待办：经主会话同一条发送腿（不吞主会话草稿/附件）。 */
  async function sendReply() {
    const item = todoStore.allItems().find((entry) => entry.id === itemTarget);
    const text = replyInput.input.value.trim();
    if (!item || !text || replySending) return;
    replySending = true;
    let ok = false;
    try {
      const { sendPrimaryTodoReply } = await import('../input.js');
      ok = sendPrimaryTodoReply(`#${item.title}·${item.id} ${text}`);
    } catch { ok = false; }
    replySending = false;
    if (ok) {
      replyInput.input.value = '';
      replyInput.autosize();
      replyError.textContent = '';
    } else replyError.textContent = t('todoapple.replyFailed');
  }

  // Top bar, three pieces: back on the left (page shell "<"), capsule on the
  // right, blue circular check save at the far right.
  // Capsule contents are trimmed to real capabilities (a button without a
  // backing capability is not rendered):
  //   · edit state = "mark complete" (the completeItem write);
  //   · create state = "flag" shortcut (same draft.priority write as the
  //     toolbar flag; no new entity) — in create state there is no item to
  //     complete, so the complete key never appears.
  const cluster = elIn(section, 'div', 'ta-cluster');
  const pill = elIn(cluster, 'div', 'ta-pill nf-glass');
  const doneBtn = iconBtn('ta-pill-btn', 'circle-check', t('todoapple.markComplete'));
  const flagBtn = iconBtn('ta-pill-btn', 'flag', t('todoapple.toPriority'));
  pill.append(doneBtn, flagBtn);
  doneBtn.addEventListener('click', () => {
    if (!itemTarget) return;
    todoStore.completeItem(itemTarget);
    nav.pop();
  });
  flagBtn.addEventListener('click', () => { draft.priority = (draft.priority + 1) % 4; render(); });
  const save = iconBtn('ta-save g2-fab', 'check', t('todoapple.save'));
  cluster.appendChild(save);
  save.addEventListener('click', () => saveDraft(nav));

  // Keyboard toolbar (hugs the keyboard: bottom reads --ta-kb; three keys =
  // date / flag / tag)
  const tool = elIn(section, 'div', 'ta-tool nf-glass');
  /**
   * @param {string} icon
   * @param {string} label
   * @returns {{ el: HTMLButtonElement, label: HTMLElement }}
   */
  const toolBtn = (icon, label) => {
    const b = btnEl('ta-tool-btn');
    b.type = 'button';
    // Structured build (contract R5): icon + label span, DOM identical to the
    // previous template string.
    b.append(icoEl(icon), el('span', 'ta-tool-label'));
    const lab = /** @type {HTMLElement} */ (b.querySelector('.ta-tool-label'));
    lab.textContent = label;
    b.setAttribute('aria-label', label);
    tool.appendChild(b);
    return { el: b, label: lab };
  };
  const tDue = toolBtn('calendar', t('todoapple.toDue'));
  const tFlag = toolBtn('flag', t('todoapple.toPriority'));
  const tTag = toolBtn('hash', t('todoapple.toTag'));
  // 日期：作者令 2026-11-18「日期也用选择器（不是点击循环）」「调整定时应该使用
  // 我们统一的定时器设置风格」⇒ 点击打开站内统一的时间滚轮单点面（与定时任务页
  // / 待办桌面面同一个面板）。落点串由草稿驱动：无定时 = 不写 dataset ⇒ 面板以
  // 「今天 09:00」起手但不写回草稿（定时默认不打开）。
  tDue.el.setAttribute('aria-haspopup', 'dialog');
  tDue.el.setAttribute('aria-expanded', 'false');
  const paintDue = bindPointTrigger(tDue.el, () => draft.due, (ms) => { draft.due = ms; render(); });
  // 重要度：作者明示「重要度可以这么切换」⇒ 保持点击循环（不改）。
  tFlag.el.addEventListener('click', () => { draft.priority = (draft.priority + 1) % 4; render(); });
  // 标签：作者令「标签必须有列表来选择 因为以后可能会有很多标签」⇒ 站内列表菜单
  // （手机层 = 全屏可滚动动作表）；不再是「点击推进到下一个」的循环。
  tTag.el.addEventListener('click', () => openTagPicker(tTag.el, () => draft.tagId, (next) => { draft.tagId = next; render(); }));

  infoBtn.addEventListener('click', () => {
    notesOpen = !notesOpen;
    notes.hidden = !notesOpen;
    if (notesOpen) { autosizeField(notes); notes.focus(); }
  });
  input.addEventListener('input', () => { draft.title = input.value; autosizeField(input); });
  notes.addEventListener('input', () => { draft.notes = notes.value; autosizeField(notes); });
  input.addEventListener('keydown', (e) => { if (commitEnter(e, input)) saveDraft(nav); });

  /** @returns {void} */
  function render() {
    input.placeholder = t('todoapple.newTodo');
    if (document.activeElement !== input) input.value = draft.title;
    if (document.activeElement !== notes) notes.value = draft.notes;
    notes.placeholder = t('todoapple.notes');
    // 标题/正文身高跟随内容（「完整显示」：容器高度不再由 CSS max-height 截断，
    // 也不需要内部滚动条）。焦点在域内时不写身高，避免打字时抖一行。
    if (document.activeElement !== input) autosizeField(input);
    if (notesOpen && !notes.hidden && document.activeElement !== notes) autosizeField(notes);

    // Draft summary (date / priority / tag chips; absent values are not shown
    // = no shell without an entity)
    meta.replaceChildren();
    if (draft.due != null) elIn(meta, 'span', 'ta-chip ta-chip-due', dueText(draft.due));
    if (draft.priority > 0) {
      const chip = elIn(meta, 'span', 'ta-chip ta-chip-prio');
      const dot = elIn(chip, 'span', 'ta-prio');
      dot.style.background = priorityColor(draft.priority);
      elIn(chip, 'span', 'ta-chip-t', priorityLabel(draft.priority));
    }
    const tg = draft.tagId ? todoStore.tagById(draft.tagId) : null;
    if (tg) {
      const chip = elIn(meta, 'span', 'ta-chip ta-chip-tag');
      const dot = elIn(chip, 'span', 'ta-dot');
      dot.style.background = tg.color;
      elIn(chip, 'span', 'ta-chip-t', tg.name);
    }

    // Toolbar readings mirror the current draft values
    paintDue();
    tDue.label.textContent = draft.due == null ? t('todoapple.noDue') : dueText(draft.due);
    tFlag.label.textContent = priorityLabel(draft.priority);
    tTag.label.textContent = tg ? tg.name : t('todoapple.toTag');
    // Capsule: in create state only "flag" is backed (nothing to complete, so
    // the complete key is absent); in edit state both keys show
    doneBtn.hidden = !itemTarget;
    replyBox.hidden = !itemTarget;
    flagBtn.classList.toggle('on', draft.priority > 0);
    if (typeof lucide !== 'undefined') createIconsIn(section);
  }

  return { root, render };
}

/** Save (create = createItem / edit = updateItem; an empty title never persists = no empty entities). */
/** @param {TaNav} nav */
function saveDraft(nav) {
  const title = draft.title.trim();
  if (!title) return;
  if (itemTarget) {
    /** @type {{title:string, notes:string, priority:number, due?:number, hasDue?:boolean, tagId?:string|null}} */
    const patch = { title, notes: draft.notes, priority: draft.priority };
    if (draft.due != null) patch.due = draft.due; else patch.hasDue = false;
    patch.tagId = draft.tagId;
    todoStore.updateItem(itemTarget, patch);
  } else {
    todoStore.createItem({
      tagId: draft.tagId, title, notes: draft.notes, due: draft.due, priority: draft.priority,
    });
  }
  nav.pop();
}

// ══════════════════════════════════════════════════════════════════════
// D · Quick decision page (todo-quick-decision): one card in the middle,
//     left/right swipe switches todos (the author's 2026-11-18 request — the
//     dating-app card view).
//
//     What the card shows, top to bottom (order fixed by the author's
//     2026-11-18 report — 手机上「只看得见内容」: 问题/选项原先排在长正文之后，
//     a long detail buried them below the fold):
//       ① the todo's title, and — when the row carries one — its decision
//          question + options (native radio/checkbox, one label per option);
//       ② the todo's long content (detail), scrolled inside `.ta-qd-scroll`;
//       ③ an answer field pinned at the card's bottom band, wired to the
//          EXISTING reply chain (input.js `sendPrimaryTodoReply`).
//     Every value is a real row field; nothing is invented.
//     Card order = todoStore.openItems() (the store's own order).
//
//     Height policy: the card is an elastic flex column between the counter line
//     and the page bottom; the head band (question/options/content) scrolls
//     inside `.ta-qd-scroll` and the answer band is `flex: none` at the card's
//     bottom, so a long content scrolls instead of clipping the question or
//     pushing the answer field off the card. The card's bottom edge sits inside
//     `.ta-root`, which carries `--mobile-dock-clearance` (the dock-clearance
//     single source) ⇒ the answer band is never truncated by the dock.
//     `.ta-qd-opt-body` is the per-option layout slot (column flow: media
//     above the label) — the mount point for the media group, see the batch
//     report §待对齐.
//
//     Gesture: the follow-the-finger pose is written into --qd-dx (JS writes
//     the value, CSS owns the transform — the same convention as the canvas
//     deck's --dtx); release settles on the design tokens --ds-dur-hover /
//     --ds-dur-surface with --ds-ease-std / --ds-ease-enter. Commit params are
//     the ones the mobile layer's own swipes already use (navController
//     EDGE_AXIS_PX 8 / EDGE_FLICK_MS 200 / threshold max(80px, 22% width)) —
//     one gesture recipe, not a second one.
//     Head/tail: an over-swipe never wraps — it stops and springs back.
// ══════════════════════════════════════════════════════════════════════

/** Gesture/motion constants of the card. Exported so the gate can pin them
 *  against the tokens they mirror (single source = the CSS custom properties). */
export const QUICK_DECISION_MOTION = {
  axisPx: 8,      // axis lock — same value as navController EDGE_AXIS_PX
  flickMs: 200,   // flick window — same value as navController EDGE_FLICK_MS
  flickPx: 28,    // short-fast-swipe leg — same formula as the shell's commit test
  minPx: 80,      // commit floor — same formula as navController / backSwipe
  ratio: 0.22,    // commit ratio of the band width — same formula
  exitMs: 160,    // = --ds-dur-hover (exit leg)
  enterMs: 240,   // = --ds-dur-surface (enter leg)
};

/** A gesture that starts inside this margin is left to the page shell's own
 *  edge band (navController EDGE_BAND_PX = 20 = the back gesture); from the
 *  screen edge the shell owns the drag, never this card. */
const QD_EDGE_PX = 24;

/** Card cursor (which todo is on the card) — module state, reset on page entry. */
let qdIndex = 0;
/** Selected option indices per todo id: view state on this device only, never a
 *  data write (selection is a local view state; only the answer field is wired
 *  out, through the existing reply chain). */
/** @type {Map<string, Set<number>>} */
const qdAnswers = new Map();
/** Un-sent answer text per todo id: typing survives a swipe (text is never lost
 *  and never lands on the wrong todo). Cleared once the reply is sent. */
/** @type {Map<string, string>} */
const qdDrafts = new Map();
/** @type {ReturnType<typeof setTimeout>|null} */
let qdTimer = null;

/** Row shape the quick card reads (the store's read-only projection). */
/** @typedef {{id:string, tagId:string|null, name:string, detail:string, due:number|null,
 *   priority:number, completed:boolean, createdAt:number,
 *   decision?:{question?:string, options?:string[], multiSelect?:boolean, allowOther?:boolean}|null}} QdRow */

/** The card queue = open todos in the store's own order (no second sort recipe).
 *  @returns {QdRow[]} */
function quickItems() { return todoStore.openItems(); }

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildQuick(root, section) {
  const wrap = elIn(root, 'div', 'ta-qd');
  const head = elIn(wrap, 'div', 'ta-qd-head');
  const count = elIn(head, 'div', 'ta-qd-count');
  const stage = elIn(wrap, 'div', 'ta-qd-stage');
  const card = elIn(stage, 'div', 'ta-qd-card nf-glass');
  // Focusable group: the keyboard leg (ArrowLeft/ArrowRight switch cards) rides
  // the same handler as the swipe leg — a swipe-only surface would have no
  // pointer-free path to the next todo. Same shape as the card faces that are
  // focusable for arrow-key paging elsewhere in the station.
  card.setAttribute('role', 'group');
  card.setAttribute('tabindex', '0');
  const scroll = elIn(card, 'div', 'ta-qd-scroll');
  // ── ③ 输入区（回答框）: the card's third band, pinned below the scroller.
  // Answer exit = the EXISTING reply chain (input.js `sendPrimaryTodoReply`, the
  // very same exit the list page's reply box and the row interaction box use):
  // the verbatim `#<todo name>·<id> <answer>` user message goes to the primary
  // session. This module never builds a frame itself.
  // The field recipe (typography + the single autosize cap) is inherited from
  // the shared `.tfl-hit-reply` rule in css/mobileLayer.css — no second ladder;
  // the page's own CSS block only owns the band's layout.
  const answer = elIn(card, 'div', 'ta-qd-answer');
  const answerRow = elIn(answer, 'div', 'ta-qd-answer-row');
  const answerInput = /** @type {HTMLTextAreaElement} */ (elIn(answerRow, 'textarea', 'ta-qd-answer-input tfl-hit-reply'));
  answerInput.rows = 1;
  answerInput.placeholder = t('todoface.replyPh');
  answerInput.setAttribute('aria-label', t('todoface.replyPh'));
  const answerSend = btnEl('ta-qd-answer-send ta-create-save');
  answerSend.type = 'button';
  answerSend.textContent = t('todoface.send');
  answerRow.appendChild(answerSend);
  const answerError = elIn(answer, 'div', 'ta-qd-answer-error');
  answerError.setAttribute('role', 'alert');
  const empty = elIn(wrap, 'div', 'ta-qd-empty');
  empty.hidden = true;

  const cancelSettle = () => { if (qdTimer) { clearTimeout(qdTimer); qdTimer = null; } };
  const reduceMotion = () => {
    try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { return false; }
  };
  /** Follow-the-finger pose (single writer of --qd-dx; CSS owns the transform). */
  /** @param {number} px */
  const pose = (px) => { card.style.setProperty('--qd-dx', `${px.toFixed(1)}px`); };

  /** Selected set for one todo (created on first use). */
  /** @param {string} id @returns {Set<number>} */
  const selectedSet = (id) => {
    let s = qdAnswers.get(id);
    if (!s) { s = new Set(); qdAnswers.set(id, s); }
    return s;
  };
  /** Selection never runs through render(): the card's scroll position survives
   *  a tap (scrolling and selecting coexist).
   *  @param {string} id @param {number} i @param {boolean} on @param {boolean} multi */
  const selectOption = (id, i, on, multi) => {
    const s = selectedSet(id);
    if (multi) { if (on) s.add(i); else s.delete(i); }
    else { s.clear(); if (on) s.add(i); }
  };

  /** Answer field autosize: measure at `height:auto`, then clamp by the CSS cap
   *  (that cap lives in exactly one place — the shared `.tfl-hit-reply` rule). */
  const autosizeAnswer = () => {
    answerInput.style.height = 'auto';
    const cap = Number.parseFloat(getComputedStyle(answerInput).maxHeight);
    const next = Number.isFinite(cap) && cap > 0
      ? Math.min(answerInput.scrollHeight, cap) : answerInput.scrollHeight;
    answerInput.style.height = `${next}px`;
  };
  answerInput.addEventListener('input', autosizeAnswer);

  /** Submit the answer through the existing reply chain. The machine-readable
   *  trace `data-qd-answer-last` is what the gate reads (never a guess). */
  const submitAnswer = async () => {
    const item = quickItems()[qdIndex];
    const text = answerInput.value.trim();
    if (!item || !text || answerSend.disabled) return;
    answerSend.disabled = true;
    answerError.textContent = '';
    let ok = false;
    try {
      const { sendPrimaryTodoReply } = await import('../input.js');
      ok = sendPrimaryTodoReply(`#${item.name}·${item.id} ${text}`);
    } catch (err) { console.error('[todoAppleFace] answer 出口不可得:', err); }
    answerSend.disabled = false;
    if (ok) {
      qdDrafts.delete(item.id);
      answerInput.value = '';
      autosizeAnswer();
    } else {
      answerError.textContent = t('todoapple.replyFailed');
    }
    card.dataset.qdAnswerLast = JSON.stringify({ itemId: item.id, ok, sent: text });
  };
  answerSend.addEventListener('click', () => { void submitAnswer(); });

  /** Release without a switch (under threshold, or head/tail): stop + spring back. */
  const springBack = () => {
    cancelSettle();
    if (reduceMotion()) { delete card.dataset.qdSettle; pose(0); return; }
    card.dataset.qdSettle = 'enter';
    pose(0);
    qdTimer = setTimeout(() => { card.dataset.qdSettle = ''; qdTimer = null; },
      QUICK_DECISION_MOTION.enterMs + 40);
  };

  /** Two legs: the current card leaves toward the swipe, the next one enters
   *  from the opposite side (both legs on token durations).
   *  @param {number} target @param {number} dir */
  const slide = (target, dir) => {
    const items = quickItems();
    if (target < 0 || target >= items.length) { springBack(); return; }
    if (reduceMotion()) { qdIndex = target; render(); pose(0); return; }
    const w = stage.getBoundingClientRect().width || card.getBoundingClientRect().width || 320;
    cancelSettle();
    card.dataset.qdSettle = 'exit';
    pose(-dir * w);
    qdTimer = setTimeout(() => {
      qdIndex = target;
      render();
      delete card.dataset.qdSettle;   // the entry jump must not animate
      pose(dir * w * 0.4);
      void card.offsetWidth;          // flush the jump, then animate to rest
      card.dataset.qdSettle = 'enter';
      pose(0);
      qdTimer = setTimeout(() => { card.dataset.qdSettle = ''; qdTimer = null; },
        QUICK_DECISION_MOTION.enterMs + 40);
    }, QUICK_DECISION_MOTION.exitMs);
  };

  // ── Pointer gesture (touch and mouse on the same leg; the inner scroller
  // keeps its vertical pan — `touch-action: pan-y` in CSS means the browser
  // never claims the horizontal axis, so the finger-follow stays live) ──
  /** @type {{id:number,x0:number,y0:number,t0:number,claimed:boolean}|null} */
  let g = null;

  stage.addEventListener('pointerdown', (e) => {
    if (g) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    if (card.hidden) return;
    // The answer band is a text surface, not a gesture surface: a drag that
    // starts inside it belongs to the field (text selection / caret), never to
    // the card switch. Same discipline as the shell's edge band.
    if (e.target instanceof Element && e.target.closest('.ta-qd-answer')) return;
    if (e.clientX <= QD_EDGE_PX || e.clientX >= window.innerWidth - QD_EDGE_PX) return;
    g = { id: e.pointerId, x0: e.clientX, y0: e.clientY, t0: Date.now(), claimed: false };
  });

  stage.addEventListener('pointermove', (e) => {
    if (!g || e.pointerId !== g.id) return;
    const dx = e.clientX - g.x0;
    const dy = e.clientY - g.y0;
    if (!g.claimed) {
      if (Math.abs(dx) <= QUICK_DECISION_MOTION.axisPx && Math.abs(dy) <= QUICK_DECISION_MOTION.axisPx) return;
      if (Math.abs(dy) >= Math.abs(dx)) { g = null; return; }   // vertical intent = the scroller's
      g.claimed = true;
      cancelSettle();
      delete card.dataset.qdSettle;   // follow the finger: no transition
      card.dataset.qdDrag = '1';
    }
    pose(dx);
  });

  /** @param {PointerEvent} e */
  const release = (e) => {
    if (!g || e.pointerId !== g.id) return;
    const st = g;
    g = null;
    if (!st.claimed) return;
    const dx = e.clientX - st.x0;
    const dy = e.clientY - st.y0;
    const dt = Date.now() - st.t0;
    delete card.dataset.qdDrag;
    const w = stage.getBoundingClientRect().width || window.innerWidth;
    const moved = Math.abs(dx) > Math.max(QUICK_DECISION_MOTION.minPx, w * QUICK_DECISION_MOTION.ratio)
      || (Math.abs(dx) > QUICK_DECISION_MOTION.flickPx && dt < QUICK_DECISION_MOTION.flickMs);
    // Swipe left = next todo, swipe right = previous todo: the direction only
    // switches the card, it is never a decision.
    const dir = dx < 0 ? 1 : -1;
    const items = quickItems();
    const target = qdIndex + dir;
    const switched = moved && target >= 0 && target < items.length;
    // Machine-readable trace of the last gesture (same convention as the
    // shell's data-back-swipe-last) — the gate reads this, not a guess.
    card.dataset.qdLast = JSON.stringify({
      dx: Math.round(dx), dy: Math.round(dy), dt, dir, moved, switched, index: qdIndex,
    });
    if (!switched) { springBack(); return; }   // head/tail (or under threshold): stop + rebound
    slide(target, dir);
  };
  stage.addEventListener('pointerup', release);
  stage.addEventListener('pointercancel', (e) => {
    if (!g || e.pointerId !== g.id) return;
    const claimed = g.claimed;
    g = null;
    if (claimed) springBack();
  });

  // Keyboard leg (ArrowLeft/ArrowRight switch cards) — skipped while a control
  // inside the card has focus, so the native radio-group arrow keys keep working.
  root.addEventListener('keydown', (e) => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    const t0 = e.target instanceof Element ? e.target : null;
    if (t0 && t0.closest('input, textarea, select')) return;
    const items = quickItems();
    const target = qdIndex + (e.key === 'ArrowRight' ? 1 : -1);
    const switched = target >= 0 && target < items.length;
    card.dataset.qdLast = JSON.stringify({ key: e.key, switched, index: qdIndex });
    if (!switched) { springBack(); return; }   // head/tail: stop + rebound
    e.preventDefault();
    slide(target, e.key === 'ArrowRight' ? 1 : -1);
  });

  /** One option row. `.ta-qd-opt-body` = the layout slot the per-option media
   *  group will be inserted into (column flow: media above the label).
   *  @param {QdRow} item @param {number} i @param {string} option @param {boolean} multi
   *  @returns {HTMLElement} */
  const optionRow = (item, i, option, multi) => {
    const row = el('label', 'ta-qd-opt');
    const input = inputEl('ta-qd-opt-input');
    input.type = multi ? 'checkbox' : 'radio';
    input.name = `ta-qd-${item.id}`;
    input.checked = selectedSet(item.id).has(i);
    const body = el('span', 'ta-qd-opt-body');
    body.appendChild(el('span', 'ta-qd-opt-label', String(option)));
    row.append(input, body);
    input.addEventListener('change', () => selectOption(item.id, i, input.checked, multi));
    return row;
  };

  /** @returns {void} */
  function render() {
    const items = quickItems();
    // Park the in-progress answer on the todo that is on the card BEFORE the card
    // changes (swipe / page entry): the text follows its todo, never the card.
    const prevId = card.dataset.itemId;
    if (prevId) qdDrafts.set(prevId, answerInput.value);
    if (qdIndex >= items.length) qdIndex = Math.max(0, items.length - 1);
    if (qdIndex < 0) qdIndex = 0;
    const reading = t('todoapple.quickCount', { n: String(qdIndex + 1), m: String(items.length) });
    head.hidden = items.length === 0;
    card.hidden = items.length === 0;
    empty.hidden = items.length > 0;
    empty.textContent = t('todoapple.emptyDone');
    count.textContent = items.length ? reading : '';
    card.setAttribute('aria-label', reading);
    scroll.replaceChildren();
    const item = items[qdIndex];
    if (!item) {
      delete card.dataset.itemId;
      answerInput.value = '';
      answerError.textContent = '';
      return;
    }
    card.dataset.itemId = item.id;   // machine-readable: which todo is on the card

    // Meta line: only the fields the row actually carries (no empty shells).
    const meta = el('div', 'ta-qd-meta');
    const tg = item.tagId ? todoStore.tagById(item.tagId) : null;
    if (tg) {
      const chip = el('span', 'ta-qd-chip');
      const dot = el('span', 'ta-qd-dot');
      dot.style.background = tg.color;
      chip.append(dot, el('span', '', tg.name));
      meta.appendChild(chip);
    }
    if (item.priority > 0) {
      const chip = el('span', 'ta-qd-chip');
      const dot = el('span', 'ta-qd-dot');
      dot.style.background = priorityColor(item.priority);
      chip.append(dot, el('span', '', priorityLabel(item.priority)));
      meta.appendChild(chip);
    }
    if (item.due != null) meta.appendChild(el('span', 'ta-qd-chip', dueText(item.due)));
    if (meta.childElementCount) scroll.appendChild(meta);

    // Read order fixed by the author's 2026-11-18 report (问答在正文之下 ⇒ 手机上
    // 只看得见内容): the todo's own title / question / options come FIRST, the long
    // content follows and scrolls. Nothing is added — the same fields, resequenced
    // so the question and its options are on screen the moment the card opens.
    scroll.appendChild(el('div', 'ta-qd-title', item.name || '—'));

    const dec = item.decision;
    if (dec) {
      if (dec.question) scroll.appendChild(el('div', 'ta-qd-q', String(dec.question)));
      const options = Array.isArray(dec.options) ? dec.options : [];
      if (options.length) {
        const list = el('div', 'ta-qd-opts');
        // Single-select → radiogroup; multi-select → checkbox group. Native
        // controls, same as the list page's decision rows (no second control
        // recipe); selection state is local view state.
        list.setAttribute('role', dec.multiSelect ? 'group' : 'radiogroup');
        options.forEach((option, i) => { list.appendChild(optionRow(item, i, option, !!dec.multiSelect)); });
        scroll.appendChild(list);
      }
    }

    // The todo's long content (detail) comes after the question/options: a long
    // body scrolls inside `.ta-qd-scroll` instead of burying the question.
    if (item.detail) scroll.appendChild(el('div', 'ta-qd-content', item.detail));

    // The answer field follows the card: this todo's parked draft, this todo's
    // label. The band itself sits outside the scroller (CSS, pinned).
    answerInput.value = qdDrafts.get(item.id) || '';
    answerError.textContent = '';
    autosizeAnswer();
    if (typeof lucide !== 'undefined') createIconsIn(card);
  }

  return { root, render };
}

// ══════════════════════════════════════════════════════════════════════
// Mount entry (dispatched by navController.ensurePanelContent; idempotent)
// ══════════════════════════════════════════════════════════════════════

/**
 * Build one of the three page bodies into `body` (idempotent: repeated calls
 * only re-render).
 * @param {string} pageId 'tasks' | 'todo-apple-list' | 'todo-apple-item' | 'todo-quick-decision'
 * @param {Element|null} body page body container
 * @param {TaNav} nav injected navigation (pushPage/popPage; avoids a static
 *   cycle with navController)
 * @returns {void}
 */
export function mountTodoAppleInto(pageId, body, nav) {
  if (!body) return;
  const section = sectionOf(body);
  if (!section) return;
  ensureSubscribed();
  // Re-entry = full page rebuild (the page shell DOM is persistent; enter /
  // leave / re-enter must not stack a second set of floating pieces; the
  // three pages share no mutable DOM and a rebuild costs a few dozen rows).
  // Draft state lives in module state, so a rebuild does not lose it.
  const prev = mounts.get(pageId);
  if (prev) {
    if (prev.root.parentElement === body) prev.root.remove();
    mounts.delete(pageId);
  }
  clearChrome(section);
  const root = el('div', 'ta-root');
  root.dataset.taPage = pageId;
  body.appendChild(root);

  /** @type {{ root: HTMLElement, render: () => void }} */
  let built;
  if (pageId === 'tasks') {
    searchOpen = false;
    searchQuery = '';
    newListOpen = false;
    moreOpen = false;
    built = buildHome(root, section, nav);
  } else if (pageId === 'todo-apple-list') {
    built = buildList(root, section, nav);
  } else if (pageId === 'todo-quick-decision') {
    // Re-entry restarts the queue and drops a settle timer left over from the
    // previous mount (a stale timer must never pose the new card).
    if (qdTimer) { clearTimeout(qdTimer); qdTimer = null; }
    qdIndex = 0;
    built = buildQuick(root, section);
  } else {
    resetDraft();
    built = buildItem(root, section, nav);
    // Create state = caret in place (new row = caret + grey placeholder)
    const inp = /** @type {HTMLInputElement|null} */ (root.querySelector('.ta-title-input'));
    if (inp) requestAnimationFrame(() => { try { inp.focus(); } catch { /* focus refused */ } });
  }
  mounts.set(pageId, built);
  built.render();
  if (typeof lucide !== 'undefined') createIconsIn(section);
}

/** Controlled rerender (QA/follow-up batches: external data changes need not wait for the store subscription). */
export function refreshTodoApple() { rerenderAll(); }
