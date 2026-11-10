// mobile/todoAppleFace.js — 手机端待办页 Apple 提醒事项信息架构（实现批 · 作者令
// 2026-11-02 11:57「手机端待办页完全按 Apple 提醒事项信息架构重做」）。
//
// 三页（navController.PAGES 注册；页面壳/返回钮/滑入滑出全部走既有 pushPage/
// popPage/bindEdgeBack —— 本模块只产页面体内容，导航语义零新建）：
//   tasks      列表首页：右上白色胶囊三图标 + 2×2 彩色四卡矩阵 +
//                    「我的列表」白色大圆角卡（todoStore.tags）+ 右下蓝色 FAB
//   todo-apple-list  列表详情：大号彩色标题左对齐 + 空心圆勾选行 + 居中空态
//   todo-apple-item  新建/编辑态：占位输入行 + (i) 信息钮 + 贴键盘的工具条
//
// 数据纪律：完成/新建/编辑一律走 todoStore（内存仓，纯前端 mock），不碰后端、
// 不新造实体 —— 能力缺位的按钮一律不放（见批文档「取舍」表）。
//
// 依赖方向：本模块**不静态 import navController**（那样会与其形成静态环，
// scripts/check-circular.mjs 会红）；导航 = 注入式 nav {push, pop}（meFace.js
// 同款惯例）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { todoStore, TAG_COLOR_PRESETS, PRIORITY_COLORS } from '../todoStore.js';
import { bindImeGuard, commitEnter } from '../imeGuard.js';

const DAY = 86_400_000;

/** @typedef {{ push: (pageId: string) => void, pop: () => void }} TaNav */
/** @typedef {'today'|'overdue'|'all'|'completed'|'tag'} ListMode */
/**
 * 条目读数（todoStore 只读投影的元素形状）。
 * @typedef {{ id: string, tagId: string, title: string, notes: string,
 *   due: number|null, priority: number, completed: boolean, createdAt: number }} TaItem
 */

// ── 小件工厂 ──────────────────────────────────────────────────────────────

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
 * 图标钮（lucide `<i data-lucide>`；绘制由 createIconsIn 统一收敛）。
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
  b.innerHTML = `<i data-lucide="${icon}"></i>`;
  return b;
}

/** 本地日界 00:00（今天到期卡与「今天」列表共用同一日界）。 */
function startOfToday() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** 今天某整点（工具条「日期」循环的锚点时刻 —— 提醒事项默认当天可提醒）。 */
/** @param {number} hour @returns {number} */
function todayAt(hour) {
  const d = new Date();
  d.setHours(hour, 0, 0, 0);
  return d.getTime();
}

/** 优先色（PRIORITY_COLORS 以数字键索引；逐档取值避开索引签名报错）。 */
/** @param {number} p @returns {string} */
function priorityColor(p) {
  if (p === 3) return PRIORITY_COLORS[3];
  if (p === 2) return PRIORITY_COLORS[2];
  if (p === 1) return PRIORITY_COLORS[1];
  return PRIORITY_COLORS[0];
}

/** 优先级文案（0 无 / 1 低 / 2 中 / 3 高；词条复用 todoface.prio*）。 */
/** @param {number} p @returns {string} */
function priorityLabel(p) {
  if (p === 3) return t('todoface.prioHigh');
  if (p === 2) return t('todoface.prioMed');
  if (p === 1) return t('todoface.prioLow');
  return t('todoapple.none');
}

/** 截止时间短文案（今天/明天/M月D日 + 时刻；13px 可读档）。 */
/** @param {number} ms @returns {string} */
function dueText(ms) {
  const d = new Date(ms);
  const hm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  const from = startOfToday();
  if (ms >= from && ms < from + DAY) return `${t('todoapple.today')} ${hm}`;
  if (ms >= from + DAY && ms < from + 2 * DAY) return `${t('todoapple.dueTomorrow')} ${hm}`;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

// ── 模块态（列表目标 / 条目目标 / 草稿 / 首页内联态）──────────────────────

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

/** 草稿重置（每次进入条目页重建：编辑 = 现条目读数；新建 = 列表页当前标签）。 */
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

// ── 键盘避让（visualViewport 最小实现；imeGuard 只有组字判定、无键盘几何）──
// 作者令「工具条必须贴键盘」。实现 = 监听 visualViewport 的 resize/scroll，
// 把「窗口高 −（可视高 + 可视偏移）」写进根上的 --ta-kb；工具条 CSS 侧
// bottom: calc(静止位 + var(--ta-kb))。无 visualViewport 的旧 WebView 降级 =
// --ta-kb 恒 0 ⇒ 工具条落 dock 上缘（静止位），零报错零遮挡。
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

// ── 挂载表 + 单飞重绘订阅 ────────────────────────────────────────────────
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
}

/** 页壳（section）—— 悬浮 chrome（胶囊/FAB/工具条）挂这里，不随页体滚动。 */
/** @param {Element} body @returns {HTMLElement|null} */
function sectionOf(body) {
  const s = body.closest('.mobile-page');
  return s instanceof HTMLElement ? s : null;
}

/** 清掉旧 chrome（页壳常驻，退出重进不得叠出第二套悬浮件）。 */
/** @param {HTMLElement} section */
function clearChrome(section) {
  for (const sel of ['.ta-cluster', '.ta-fab-wrap', '.ta-menu', '.ta-tool']) {
    for (const n of section.querySelectorAll(`:scope > ${sel}`)) n.remove();
  }
}

// ══════════════════════════════════════════════════════════════════════
// A · 列表首页（tasks）
// ══════════════════════════════════════════════════════════════════════

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @param {TaNav} nav
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildHome(root, section, nav) {
  // ── 四卡矩阵（2×2）：左上白线图标 / 右上大号白色计数 / 左下白色标签 ──
  const grid = elIn(root, 'div', 'ta-grid');
  /**
   * @param {'today'|'overdue'|'all'|'completed'} kind
   * @param {string} icon
   * @returns {HTMLButtonElement}
   */
  const card = (kind, icon) => {
    const b = /** @type {HTMLButtonElement} */ (elIn(grid, 'button', `ta-card ta-card-${kind}`));
    b.type = 'button';
    b.innerHTML = `<i data-lucide="${icon}" class="ta-card-ico"></i>` +
      '<span class="ta-card-n"></span><span class="ta-card-l"></span>';
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

  // ── 内联搜索条（胶囊搜索键展开；本地过滤 = 真实能力，无后端）──
  const searchWrap = elIn(root, 'div', 'ta-searchbar');
  searchWrap.hidden = true;
  const searchInput = inputEl('ta-search-input');
  searchInput.type = 'search';
  searchInput.autocomplete = 'off';
  bindImeGuard(searchInput);
  searchWrap.appendChild(searchInput);
  searchInput.addEventListener('input', () => { searchQuery = searchInput.value; render(); });
  searchInput.addEventListener('keydown', (e) => { if (commitEnter(e, searchInput)) searchInput.blur(); });

  // ── 「我的列表」区块标题（正文色粗体，不在卡里）+ 白色大圆角卡 ──
  const secLabel = elIn(root, 'div', 'ta-sec-label');
  const cardBox = elIn(root, 'div', 'ta-listcard');
  const rowsBox = elIn(cardBox, 'div', 'ta-rows');
  // 新建列表行（胶囊「新建列表」展开；回车落库 = todoStore.createTag 真实能力）
  const newRow = elIn(cardBox, 'div', 'ta-row ta-row-new');
  newRow.hidden = true;
  elIn(newRow, 'span', 'ta-dot ta-dot-new');
  const nlInput = inputEl('ta-newlist-input');
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

  // ── 右上白色胶囊三图标（左端留空 = 返回钮独占；三键全部有实体能力）──
  const cluster = elIn(section, 'div', 'ta-cluster');
  const pill = elIn(cluster, 'div', 'ta-pill');
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

  // 更多菜单（两项 = 真实入口：已完成视图 / 全部待办）
  const menu = elIn(section, 'div', 'ta-menu');
  menu.hidden = true;
  const mDone = btnEl('ta-menu-row');
  mDone.type = 'button';
  mDone.innerHTML = '<span class="ta-menu-l"></span><span class="ta-menu-n"></span>';
  const mAll = btnEl('ta-menu-row');
  mAll.type = 'button';
  mAll.innerHTML = '<span class="ta-menu-l"></span><span class="ta-menu-n"></span>';
  menu.append(mDone, mAll);
  mDone.addEventListener('click', () => { moreOpen = false; openList('completed', null, nav); });
  mAll.addEventListener('click', () => { moreOpen = false; openList('all', null, nav); });

  // ── 右下蓝色 FAB（+）：新建待办（默认落首个标签）──
  // 定位壳 = .ta-fab-wrap（absolute 落位，不进 chrome 扫描带 —— 见 CSS 注）；
  // 钮本体 = .ta-fab.g2-fab（作者令指定的基准类 + 材质/几何/弹簧单源）。
  const fabWrap = elIn(section, 'div', 'ta-fab-wrap');
  const fab = iconBtn('ta-fab g2-fab', 'plus', t('todoapple.newTodo'));
  fabWrap.appendChild(fab);
  fab.addEventListener('click', () => openItem(null, nav));

  /** @returns {void} */
  function render() {
    // 四卡读数：今天=今天到期未完成 / 逾期=过期未完成 / 全部=未完成总数 / 已完成=已完成数
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

    // 胶囊态
    bSearch.classList.toggle('on', searchOpen);
    bNewList.classList.toggle('on', newListOpen);
    bMore.classList.toggle('on', moreOpen);
    searchWrap.hidden = !searchOpen;
    searchInput.placeholder = t('todoapple.searchPh');
    newRow.hidden = !newListOpen;
    nlInput.placeholder = t('todoapple.newListPh');

    // 更多菜单
    menu.hidden = !moreOpen;
    /** @type {HTMLElement} */ (mDone.querySelector('.ta-menu-l')).textContent = t('todoapple.completed');
    /** @type {HTMLElement} */ (mDone.querySelector('.ta-menu-n')).textContent = String(todoStore.completedCount());
    /** @type {HTMLElement} */ (mAll.querySelector('.ta-menu-l')).textContent = t('todoapple.allTodos');
    /** @type {HTMLElement} */ (mAll.querySelector('.ta-menu-n')).textContent = String(todoStore.openCount());

    // 区块标题
    secLabel.textContent = searchOpen ? t('todoapple.searchResults') : t('todoapple.myLists');

    // 列表卡行
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
        row.innerHTML = '<span class="ta-dot"></span><span class="ta-row-label"></span><span class="ta-row-sub"></span>';
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
        row.innerHTML = '<span class="ta-dot"></span><span class="ta-row-label"></span>' +
          '<span class="ta-row-count"></span><i data-lucide="chevron-right" class="ta-chev"></i>';
        const dot = /** @type {HTMLElement} */ (row.querySelector('.ta-dot'));
        dot.style.background = tg.color;
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-label')).textContent = tg.name;
        /** @type {HTMLElement} */ (row.querySelector('.ta-row-count')).textContent = String(todoStore.openCountOf(tg.id));
        row.addEventListener('click', () => openList('tag', tg.id, nav));
        rowsBox.appendChild(row);
      }
      // 末行「全部待办」灰行 = All 入口（tags 为空时本行即唯一行 = 降级形态）
      const allRow = btnEl('ta-row ta-row-all');
      allRow.type = 'button';
      allRow.innerHTML = '<span class="ta-dot ta-dot-all"></span><span class="ta-row-label"></span>' +
        '<span class="ta-row-count"></span><i data-lucide="chevron-right" class="ta-chev"></i>';
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
// B · 列表详情页（todo-apple-list）
// ══════════════════════════════════════════════════════════════════════

/** 当前列表目标下的条目（本地只读过滤，不写 store 的筛选态）。 */
function itemsForTarget() {
  const all = todoStore.allItems();
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

/** 列表标题（文案 + 颜色）：模式页走主题 token，标签页走 tag.color（数据色）。 */
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

  // 右上白色胶囊：唯一有实体的能力 = 往本列表新建待办（createItem）
  const cluster = elIn(section, 'div', 'ta-cluster');
  const pill = elIn(cluster, 'div', 'ta-pill');
  const addBtn = iconBtn('ta-pill-btn', 'list-plus', t('todoapple.newTodo'));
  pill.appendChild(addBtn);
  addBtn.addEventListener('click', () => openItem(null, nav));

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

      // 左空心圆 checkbox：视觉 24、命中 48×48（≥44 契约）
      const check = btnEl('ta-check');
      check.type = 'button';
      check.setAttribute('aria-label', t('todoapple.markComplete'));
      check.innerHTML = '<span class="ta-check-circle"></span>';
      check.addEventListener('click', (e) => {
        e.stopPropagation();
        todoStore.completeItem(it.id);
      });
      row.appendChild(check);

      // 优先标记：priority > 0 才出现（蓝/橙/红三档数据色）
      if (it.priority > 0) {
        const prio = el('span', 'ta-prio');
        prio.style.background = priorityColor(it.priority);
        prio.setAttribute('aria-hidden', 'true');
        row.appendChild(prio);
      }

      // 行体 = 正文按钮（点击下钻条目详情；同一 push/pop 栈 + 返回手势）
      const bodyBtn = btnEl('ta-item-body');
      bodyBtn.type = 'button';
      elIn(bodyBtn, 'span', 'ta-item-title', it.title || '—');
      if (it.due != null) elIn(bodyBtn, 'span', 'ta-item-due', dueText(it.due));
      bodyBtn.addEventListener('click', () => openItem(it.id, nav));
      row.appendChild(bodyBtn);

      itemsBox.appendChild(row);
    }
    empty.hidden = list.length > 0;
    empty.textContent = listTarget.mode === 'completed'
      ? t('todoapple.emptyCompleted') : t('todoapple.emptyDone');
    if (typeof lucide !== 'undefined') createIconsIn(section);
  }

  return { root, render };
}

// ══════════════════════════════════════════════════════════════════════
// C · 新建 / 编辑态（todo-apple-item）
// ══════════════════════════════════════════════════════════════════════

/** 工具条「日期」循环：无 → 今天 21:00 → 明天 21:00 → 无（真实 due 写口）。 */
function cycleDue() {
  if (draft.due == null) { draft.due = todayAt(21); return; }
  if (draft.due < startOfToday() + DAY) { draft.due = todayAt(21) + DAY; return; }
  draft.due = null;
}

/**
 * @param {HTMLElement} root
 * @param {HTMLElement} section
 * @param {TaNav} nav
 * @returns {{ root: HTMLElement, render: () => void }}
 */
function buildItem(root, section, nav) {
  const wrap = elIn(root, 'div', 'ta-edit');
  const row = elIn(wrap, 'div', 'ta-newrow');
  const input = inputEl('ta-title-input');
  input.type = 'text';
  input.autocomplete = 'off';
  bindImeGuard(input);
  row.appendChild(input);
  const infoBtn = iconBtn('ta-info', 'info', t('todoapple.info'));
  row.appendChild(infoBtn);
  const notes = /** @type {HTMLTextAreaElement} */ (elIn(wrap, 'textarea', 'ta-notes'));
  notes.rows = 3;
  notes.hidden = true;
  bindImeGuard(notes);
  const meta = elIn(wrap, 'div', 'ta-meta');

  // 顶栏三件：左返回（页面壳「<」）、右胶囊、最右蓝色圆形对勾保存。
  // 胶囊内容按实体能力取舍（作者令「没有的能力就不放按钮」）：
  //   · 编辑态 = 「标记完成」（completeItem 写口）；
  //   · 新建态 = 「旗标」快捷（与工具条旗标同一 draft.priority 写口，非新造实体）
  //     —— 新建态点「完成」无对象可完成，故不出现。
  const cluster = elIn(section, 'div', 'ta-cluster');
  const pill = elIn(cluster, 'div', 'ta-pill');
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

  // 键盘工具条（贴键盘：bottom 走 --ta-kb；三键 = 日期 / 旗标 / 标签）
  const tool = elIn(section, 'div', 'ta-tool');
  /**
   * @param {string} icon
   * @param {string} label
   * @returns {{ el: HTMLButtonElement, label: HTMLElement }}
   */
  const toolBtn = (icon, label) => {
    const b = btnEl('ta-tool-btn');
    b.type = 'button';
    b.innerHTML = `<i data-lucide="${icon}"></i><span class="ta-tool-label"></span>`;
    const lab = /** @type {HTMLElement} */ (b.querySelector('.ta-tool-label'));
    lab.textContent = label;
    b.setAttribute('aria-label', label);
    tool.appendChild(b);
    return { el: b, label: lab };
  };
  const tDue = toolBtn('calendar', t('todoapple.toDue'));
  const tFlag = toolBtn('flag', t('todoapple.toPriority'));
  const tTag = toolBtn('hash', t('todoapple.toTag'));
  tDue.el.addEventListener('click', () => { cycleDue(); render(); });
  tFlag.el.addEventListener('click', () => { draft.priority = (draft.priority + 1) % 4; render(); });
  tTag.el.addEventListener('click', () => {
    const tags = todoStore.tags;
    if (!tags.length) return;
    const i = tags.findIndex((x) => x.id === draft.tagId);
    if (i < 0) draft.tagId = tags[0] ? tags[0].id : null;
    else draft.tagId = i + 1 < tags.length ? tags[i + 1].id : null;
    render();
  });

  infoBtn.addEventListener('click', () => {
    notes.hidden = !notes.hidden;
    if (!notes.hidden) notes.focus();
  });
  input.addEventListener('input', () => { draft.title = input.value; });
  notes.addEventListener('input', () => { draft.notes = notes.value; });
  input.addEventListener('keydown', (e) => { if (commitEnter(e, input)) saveDraft(nav); });

  /** @returns {void} */
  function render() {
    input.placeholder = t('todoapple.newTodo');
    if (document.activeElement !== input) input.value = draft.title;
    if (document.activeElement !== notes) notes.value = draft.notes;
    notes.placeholder = t('todoapple.notes');

    // 草稿摘要（日期 / 优先 / 标签三 chips；空档不显示 = 无实体不造壳）
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

    // 工具条读数即当前草稿值
    tDue.label.textContent = draft.due == null ? t('todoapple.noDue') : dueText(draft.due);
    tFlag.label.textContent = priorityLabel(draft.priority);
    tTag.label.textContent = tg ? tg.name : t('todoapple.toTag');
    // 胶囊：新建态只有「旗标」有实体（无对象可完成 ⇒ 完成键不出）；编辑态两键齐
    doneBtn.hidden = !itemTarget;
    flagBtn.classList.toggle('on', draft.priority > 0);
    if (typeof lucide !== 'undefined') createIconsIn(section);
  }

  return { root, render };
}

/** 保存（新建 = createItem / 编辑 = updateItem；空标题不落库 = 不造空实体）。 */
/** @param {TaNav} nav */
function saveDraft(nav) {
  const title = draft.title.trim();
  if (!title) return;
  if (itemTarget) {
    /** @type {{title:string, notes:string, priority:number, due?:number, hasDue?:boolean, tagId?:string}} */
    const patch = { title, notes: draft.notes, priority: draft.priority };
    if (draft.due != null) patch.due = draft.due; else patch.hasDue = false;
    if (draft.tagId) patch.tagId = draft.tagId;
    todoStore.updateItem(itemTarget, patch);
  } else {
    todoStore.createItem({
      tagId: draft.tagId, title, notes: draft.notes, due: draft.due, priority: draft.priority,
    });
  }
  nav.pop();
}

// ══════════════════════════════════════════════════════════════════════
// 挂载入口（navController.ensurePanelContent 分派；幂等）
// ══════════════════════════════════════════════════════════════════════

/**
 * 把三页之一的页面体建进 body（幂等：重复调用只重绘）。
 * @param {string} pageId 'tasks' | 'todo-apple-list' | 'todo-apple-item'
 * @param {Element|null} body 页面体容器
 * @param {TaNav} nav 导航注入（pushPage/popPage；避免与 navController 静态成环）
 * @returns {void}
 */
export function mountTodoAppleInto(pageId, body, nav) {
  if (!body) return;
  const section = sectionOf(body);
  if (!section) return;
  ensureSubscribed();
  // 重入 = 整页重建（页壳 DOM 常驻，进/出/再进不得叠出第二套悬浮件；三页
  // 互不共享可变 DOM，重建成本 = 一页几十行）。草稿状态在模块态里，重建不丢。
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
  } else {
    resetDraft();
    built = buildItem(root, section, nav);
    // 新建态 = 光标就位（作者令「新建行 = 光标 + 灰色中文占位」）
    const inp = /** @type {HTMLInputElement|null} */ (root.querySelector('.ta-title-input'));
    if (inp) requestAnimationFrame(() => { try { inp.focus(); } catch { /* 焦点被拒 */ } });
  }
  mounts.set(pageId, built);
  built.render();
  if (typeof lucide !== 'undefined') createIconsIn(section);
}

/** 受控重绘（QA/后续批：外部数据面变化后不必等 store 订阅）。 */
export function refreshTodoApple() { rerenderAll(); }
