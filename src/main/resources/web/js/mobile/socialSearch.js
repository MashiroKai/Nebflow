// mobile/socialSearch.js — 消息/联系人页「常驻顶部搜索条」
// （作者令 2026-11-02 07:13：两个 tab 的搜索框固定在页面顶部，不再用右上角
//  悬浮按钮控制搜索；右上搜索浮钮在这两页退役，其它面保持现状）。
//
// 形态换代（相对 2026-10-31 悬浮钮统一+搜索批的「浮钮呼出 + 展开式搜索区」）：
//   · 搜索条 = 页面体流内**首件常驻**（.mh-search-dock > .mh-search-bar），
//     静止态即在位、不再有呼出/收合；呼出钮 .mh-fab-search 与页面 section 的
//     .mh-search-open 收合语汇整件退役（两页之外的搜索入口零触碰：聊天根
//     chrome 搜索键 / 详情页搜索页等均不动）。
//   · 输入即搜、清空即回列表：消息页有查询时 .has-query 让 #fm-conversations
//     整块让位，结果区吃满余量自滚（条在流内占位 ⇒ 列表首条永不被遮挡）。
//   · 材质 = 既有玻璃包单源（--glass-control-*，sapphire.css 深浅双主题），
//     零新色值；CSS 见 mobileLayer.css「常驻搜索条」块。
//
// 件八（作者令 2026-11-05 12:28「上滑之后变成一个搜索按钮挂在右上角 … 直接使用
// 一样的就行」）：条 = **扁平长条 ⇄ 右上角圆钮**的一次性形变，同一 DOM 节点。
//   · 展开 = 扁平长条（css/mobileSearchFlat.css，微信形态 + 我方圆角档
//     --ds-radius-input）；收起 = 同节点追加 `.g2-fab`（主会话搜索钮同一个类／
//     同一套 CSS，glass.css），落位右上角、住在页面体内的常驻搜索带里。
//   · 判据/阈值单源：本条 COLLAPSE_AT / EXPAND_AT / 带高与过渡曲线分居 JS/CSS，
//     断言侧读同一组常量（tests/mobile-search-flat.spec.mjs）。
//   · 旧形态（独立 `.mh-search-fab` 浮钮 + dock.style.height 实测像素过渡）整件
//     退役：那条路线同时是 bug①（滚动容器几何逐帧变化 → 滚动卡死）与 bug②
//     （fixed + visibility:visible 自持 → 跨页泄漏）的根因，见 setCollapsed 注。
//
// 两面内容臂（数据源 = 桌面既有面/读数，零复制零第二实现）：
//   · 消息页 = 类微信跨会话聊天记录检索：范围 chips（全部/好友/设备/群聊）+
//     按会话分组结果 + <mark> 高亮 + 点行跳转定位（messages.js revealMessage
//     缝）。纯客户端渐进检索：getConversations 快照 + 每会话 getMessages 前向
//     翻页（20 页 × 50 = 1000 条/段，段尾续扫到最早）；300ms debounce + requestId
//     竞态丢弃 + AbortController 中止（chatSearch 同族纪律）。
//     —— desktop-search-field 批（2026-11-17）起检索引擎/范围 chips/结果行/
//     输入行骨架**上抽到 js/socialSearchCore.js 单一实现**（桌面消息面板搜索框
//     与本条共用同一引擎，本模块只剩 UI 壳：挂载/版面让位/收起形变）。
//   · 联系人页 = 既有桌面搜索块逻辑零改动承载：常驻条只做输入的**单入口**，
//     查询串写回 contacts.js 的 searchQ 单一源并走同一条提交流水（节流/六态
//     结果卡/渲染全复用，contacts.js#submitContactsSearch）。联系人页无「好友/
//     群/设备」切面控件（现状 = 单条流：搜索块 → 新的朋友 → 设备 → 群入口 →
//     好友列表）⇒ 本批按「同一条搜索条覆盖该页全部内容段」落地；条住在收编
//     面之外（页面体首件），面板重渲不重建输入内容（输入串跨重渲保留）。
//
// 桌面零改动边界：本模块只追加自有节点 + 读桌面导出 API；封存态
// （features.friends OFF）不建条（ensureSocialPage 同口径）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { friendsEnabled } from '../featureFlags.js';
import { logEvent } from './navEvents.js';
// 引擎/部件单一实现（desktop-search-field 批上抽；检索纪律注释见该文件）。
import { createSocialMessageSearch, buildFieldShell, SCOPES } from '../socialSearchCore.js';

/** 联系人页 = 远程用户名检索，去抖略长（桌面提交键另有 1s 最小间隔，同源）。 */
const CONTACTS_DEBOUNCE_MS = 450;

/** 常驻搜索条所属页（其余面的搜索入口零触碰）。 */
const PAGE_IDS = ['messages', 'contacts'];

/** 阈值算术**单源**（作者令 2026-11-04 13:07 + 2026-11-05 12:28 同一条判据）：
 *   · COLLAPSE_AT = 24px：内容上滚过 24px ⇒ 收起成右上角圆钮；
 *   · EXPAND_AT = 4px：滚回 4px 以内（回顶部）⇒ 还原为长条；
 *   · 4..24 = **回滞带**，带内状态不翻转（防抖边：收/展各只有一次阈值穿越）。
 *  断言侧读同一组常量（tests/mobile-search-flat.spec.mjs 判据 ②③）。 */
const COLLAPSE_AT = 24;
const EXPAND_AT = 4;
// 过渡锁已删除（作者令 2026-11-05 12:28 修批）：带高恒定后收/展不再改滚动容器
// 几何 ⇒ 不存在「到底被钳回 ⇒ 反向判据再触发」的抖振回路，翻转节流唯一需要的是
// 回滞带（EXPAND_AT..COLLAPSE_AT）。旧锁是 bug① 的第二个病根：用户在锁窗内继续
// 上滑，判据被整段丢弃 ⇒ 「上滑了之后动不了」的观感。

/**
 * @typedef {{
 *   pageId: string, dock: HTMLElement, bar: HTMLElement, input: HTMLInputElement,
 *   clear: HTMLButtonElement, scopeRow: HTMLElement|null, status: HTMLElement|null,
 *   results: HTMLElement|null, note: HTMLElement|null,
 * }} SearchBar
 */

/** @type {Map<string, SearchBar>} page id → 常驻条部件表。 */
const bars = new Map();
/** @type {ReturnType<typeof createSocialMessageSearch>|null} 消息页检索引擎（core 实例）。 */
let msgEngine = null;
/** @type {ReturnType<typeof setTimeout>|null} 联系人页去抖。 */
let contactTimer = null;
/** @type {Array<() => void>} */
let unsubs = [];

function pageSection(pageId) {
  return document.querySelector(`#mobile-pages .mobile-page[data-page="${pageId}"]`);
}

function pageBody(pageId) {
  return /** @type {HTMLElement|null} */ (
    pageSection(pageId)?.querySelector('.mobile-page-body') || null);
}

function msgBar() { return bars.get('messages') || null; }

// ── 部件构建 ────────────────────────────────────────────────────────

/** 输入行（玻璃单源 + 图标 + 清空键）；返回三件，事件由各页处理器接线。
 *
 *  `.nf-glass`（glass-top-overlays 批 2026-11-06）：本行**浮在滚动列表之上**
 *  （搜索带 `position:absolute`、带本体零底、内容从带下连续穿过），按作者令
 *  「所有悬浮在顶部 可以透出背景的 都用毛玻璃效果 比如消息和联系人的手机端的
 *  搜索框」⇒ 材质必须走唯一工具类，不许再在这里/在 CSS 里另造一档浅灰底。
 *  收起态（同一节点追加 `.g2-fab`）继承的仍是同一份玻璃；`.nf-glass` 常驻
 *  不收不展 —— 它不参与几何，只供材质。 */
function buildBarRow(placeholder) {
  // 输入行骨架单一实现 = socialSearchCore.buildFieldShell（desktop-search-field
  // 批起两端同构建器）；本端只补手机层类名（收起形变/带内布局的选择器认它们）。
  const input = document.createElement('input');
  input.className = 'mh-search-input';
  input.type = 'text';
  input.autocomplete = 'off';
  const { shell: row, clear } = buildFieldShell(input, { placeholder, shellClass: 'mh-search-bar' });
  clear.classList.add('mh-search-clear');
  return { row, input, clear };
}

/** 消息页：输入行 + 范围 chips + 状态行 + 结果流 + 脚注（全部常驻，静止态折叠视觉由 CSS）。
 *  检索腿 = core 引擎实例（去抖/竞态/分段扫描全在 socialSearchCore，本函数只挂宿主）。 */
function fillMessagesBar(dock, parts) {
  const status = document.createElement('div');
  status.className = 'mh-search-status';
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  status.hidden = true;

  const results = document.createElement('div');
  results.className = 'mh-search-results';

  const note = document.createElement('div');
  note.className = 'mh-search-note';
  note.textContent = t('mhsearch.capNote');

  msgEngine = createSocialMessageSearch({
    input: parts.input,
    resultsHost: results,
    statusEl: status,
    // 输入即搜：查询串非空 ⇒ 条吃满余量 + 列表让位（CSS 判据 = .has-query）。
    onHasQuery: (has) => dock.classList.toggle('has-query', has),
  });
  const scopeRow = msgEngine.scopeRow();

  dock.append(parts.row, scopeRow, status, results, note);

  parts.input.addEventListener('input', () => msgEngine?.onValueInput());
  parts.input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      // Esc = 清空回列表（常驻条的「收起」语义已无，保留清场一键）。
      e.preventDefault();
      e.stopPropagation();
      parts.clear.click();
    } else if (e.key === 'Enter') {
      e.preventDefault();
      msgEngine?.submitNow();
    }
  });

  return { scopeRow, status, results, note };
}

/** 联系人页：只有输入行（结果卡由桌面搜索块在收编面内自渲染，逻辑零复制）。 */
function fillContactsBar(dock, parts) {
  dock.append(parts.row);
  parts.input.addEventListener('input', () => {
    const q = parts.input.value.trim();
    dock.classList.toggle('has-query', q.length > 0);
    if (contactTimer) { clearTimeout(contactTimer); contactTimer = null; }
    // 清空 = **立即**回列表（去抖只服务远程查号：空串零请求、零反爬代价）。
    // 件一（2026-11-02）起 `searchQ` 同时是好友列表**本地过滤**的输入源（描述进
    // 检索）⇒ 清空若还等 450ms，列表会停在被过滤的旧态一瞬（微信式「清空即回列表」
    // 的语义硬要求；消息页 P4 同款）。
    if (!q) { runContactsSearch(''); return; }
    contactTimer = setTimeout(() => { contactTimer = null; runContactsSearch(q); }, CONTACTS_DEBOUNCE_MS);
  });
  parts.input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (contactTimer) { clearTimeout(contactTimer); contactTimer = null; }
      runContactsSearch(parts.input.value.trim());
    } else if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      parts.clear.click();
    }
  });
}

/** contacts.js 模块句柄缓存（**同步复用**的关键，件一 2026-11-02）。
 *  动态 import 是为了不造 `socialSearch → contacts → messages → socialSearch`
 *  静态环；但 `await import()` 每次都在微任务后交付 ⇒ 调用方若在同一事件回合里
 *  先探测 DOM（`mobile-pinned-search.spec.mjs` P6「清空后首行回位」就是同步读），
 *  会读到**上一拍**的列表。首次 import 后把句柄记住，之后同步调用 —— 首拍仍异步
 *  （那时 DOM 里还没有任何查询态，无人探测）。 */
let contactsMod = null;

/** 联系人页检索腿：查询串写回 contacts.js 单一源（searchQ）并走同一提交流水
 *  （节流关 = 输入已在上游去抖；空串 = 清场回列表）。
 *  🔴 空串必须**同回合**重渲（微信式「清空即回列表」；列表本地过滤的输入源就是
 *  同一个 `searchQ`，晚一拍的代价是用户看见「暂无联系人和搜索卡并存」的中间态）。 */
function runContactsSearch(q) {
  if (contactsMod) { contactsMod.submitSearch(q, { throttle: false }); return; }
  import('../contacts.js')
    .then((m) => { contactsMod = m; m.submitSearch(q, { throttle: false }); })
    .catch(() => { /* 模块未载（好友域未初始化）——静默，桌面逻辑缺席即无票可投 */ });
}

/** 建一条常驻条（幂等；页面体首件）。 */
function ensureBar(pageId) {
  const existing = bars.get(pageId);
  if (existing && existing.dock.isConnected) return existing;
  const body = pageBody(pageId);
  if (!body) return null;
  const dock = document.createElement('div');
  dock.className = 'mh-search-dock';
  dock.setAttribute('data-mh', pageId === 'messages' ? 'msgsearch' : 'contactsearch');
  dock.setAttribute('role', 'search');
  const parts = buildBarRow(pageId === 'messages'
    ? t('mhsearch.placeholder')
    : t('contacts.searchPlaceholder'));
  /** @type {SearchBar} */
  const bar = {
    pageId, dock, bar: parts.row, input: parts.input, clear: parts.clear,
    scopeRow: null, status: null, results: null, note: null,
  };
  if (pageId === 'messages') Object.assign(bar, fillMessagesBar(dock, parts));
  else fillContactsBar(dock, parts);
  body.insertBefore(dock, body.firstChild);
  if (typeof lucide !== 'undefined') createIconsIn(dock);
  // 件八（2026-11-05）：圆钮 = 条本体的形变态（零第二节点）⇒ 只绑激活腿 + 页内
  // 滚动探针；收/展的几何全在 css/mobileSearchFlat.css，这里零内联样式。
  bindBarActivate(bar);
  bindPageScroll(bar);
  bars.set(pageId, bar);
  return bar;
}

/** 摘条（封存态/拆除；消息页同时复位检索腿）。 */
function destroyBar(pageId) {
  const bar = bars.get(pageId);
  if (!bar) return;
  bars.delete(pageId);
  bar.dock.remove();
  if (pageId === 'messages') {
    resetMsgSearch();
    msgEngine?.destroy();
    msgEngine = null;
  } else if (contactTimer) {
    clearTimeout(contactTimer);
    contactTimer = null;
  }
}

// ── 消息页检索腿（引擎 = socialSearchCore 实例；此处只留条级复位）────────

/** 清空即回列表：结果/状态清场（范围档保留——下次查询续用）+ 输入串复位。 */
function resetMsgSearch() {
  msgEngine?.hardReset();
  const bar = msgBar();
  if (bar) {
    bar.input.value = '';
    bar.bar.classList.remove('has-text');
    bar.dock.classList.remove('has-query');
  }
}

// ── 件八：滚动收起 / 右上角圆钮（作者令 2026-11-05 12:28，逐字见文件头）───
//
// 形态：
//   · 判据类 = `.mh-search-collapsed`（挂 `.mh-search-dock`）∪ `.g2-fab`（挂条
//     本体）。**整件只有一个 DOM 节点**（条本体 .mh-search-bar）：收起 = 同一
//     节点追加 `.g2-fab`（主会话搜索钮同一个类 ⇒ 同一套 CSS，零仿制副本），
//     展开 = 摘掉。样式与过渡时长单源在 css/mobileSearchFlat.css，本模块**零
//     内联样式写入**（旧实现写 dock.style.height 实测像素 = bug① 根因，已删）。
//   · 落位 = 页面顶缘的**透明顶带**（带高常量，收/展不变）：带本体零底色、零
//     毛玻璃、手势穿透（css/mobileSearchFlat.css「透明顶带」块），收编滚区吃满
//     页体全高、内容自带让位内距 ⇒ 静止时列表首行在带下，上滚后内容从带内连续
//     穿过（作者令 2026-11-05 17:34「顶部那部分应该是透明的，能显示内容」）。
//   · 反向（下滑/回顶）同一条过渡曲线还原。
//   · 有查询（.has-query）不自动收起 —— 否则结果场被收掉、「.has-query 让位 /
//     清空回列表」的既有语义被破坏。
//   · 点圆钮 = 展开并聚焦输入框（可直接键入）。

/** 点/键激活：收起态下整条 = 一枚按钮（展开 + 聚焦输入）。
 *  无障碍：收起态补 role=button/tabindex=0/aria-label，展开即摘（输入框自己是焦点面）。 */
function bindBarActivate(bar) {
  const activate = (/** @type {Event} */ e) => {
    if (!bar.dock.classList.contains('mh-search-collapsed')) return;
    e.preventDefault();
    setCollapsed(bar, false);
    // 展开并聚焦：activeElement 命中 .mh-search-input，可直接键入。
    bar.input.focus();
    logEvent('mh-search-expand', { page: bar.pageId });
  };
  bar.bar.addEventListener('click', activate);
  bar.bar.addEventListener('keydown', (e) => {
    const k = /** @type {KeyboardEvent} */ (e).key;
    if (k === 'Enter' || k === ' ' || k === 'Spacebar') activate(e);
  });
}

/** 收起/展开的唯一写点（样式态零第二实现）：只切类，不写任何几何。
 *  bug①「上滑后动不了」的结构解：旧实现在 scroll 事件里改滚动容器高度 ⇒ 到底
 *  时 scrollTop 被钳回 ⇒ 回钳的 scroll 事件又走反向判据 ⇒ 收/展抖动。现在带高
 *  是常量（CSS `--mhsc-band`），滚动容器几何在收/展全程零变化。 */
function setCollapsed(bar, collapsed) {
  const dock = bar.dock;
  if (!dock.isConnected) return;
  if (dock.classList.contains('mh-search-collapsed') === collapsed) return;
  // 收/展各只穿一次阈值（回滞带 4..24 见常量块）；动画中途再翻转由 CSS 从当前
  // 帧继续插值 ⇒ 不需要锁，也就不存在「锁窗内上滑被判据丢弃」。
  dock.classList.toggle('mh-search-collapsed', collapsed);
  bar.bar.classList.toggle('g2-fab', collapsed);
  if (collapsed) {
    bar.bar.setAttribute('role', 'button');
    bar.bar.setAttribute('tabindex', '0');
    bar.bar.setAttribute('aria-expanded', 'false');
    bar.bar.setAttribute('aria-label', bar.input.placeholder);
  } else {
    bar.bar.removeAttribute('role');
    bar.bar.removeAttribute('tabindex');
    bar.bar.removeAttribute('aria-expanded');
    bar.bar.removeAttribute('aria-label');
  }
}

/** 路由复位（bug②「按钮泄漏到其他页面」的修点）：圆钮是**条本体的形变态**，
 *  不是独立浮件 —— 页面一离开 live，收起态立即还原为展开长条；非 live 页面
 *  的条随页面 `visibility: hidden` 一起退场（结构上不再可能压到别的页面）。
 *  调用点 = activateSocialSearch 的 `nf:mobile-nav` 监听（同一次路由广播）。 */
function resetCollapseOutsideLive() {
  for (const bar of bars.values()) {
    const sec = pageSection(bar.pageId);
    if (!sec || !sec.classList.contains('live')) setCollapsed(bar, false);
  }
}

/** 页内滚动探针（scroll 不冒泡 ⇒ 挂 section 捕获相位；navController.bindRollProbe
 *  同族纪律）。读数 = **真实滚动容器的** scrollTop（消息页 #fm-conversations /
 *  联系人页 #fm-contacts-body；查询态结果场 .mh-search-results 由 has-query 闸挡掉）。
 *  上滚过 COLLAPSE_AT(24) 收起；滚回 EXPAND_AT(4) 以内（回顶部）展开；
 *  4..24 = 回滞带。防抖边三闸 = 有查询 / 焦点在条内 / 事件源非滚动容器
  （过渡锁已删，见 STATE_LOCK 退役注；滚动永远不被拦）。 */
function bindPageScroll(bar) {
  const section = pageSection(bar.pageId);
  if (!section) return;
  section.addEventListener('scroll', (e) => {
    if (!bar.dock.isConnected) return;
    const src = /** @type {HTMLElement|null} */ (e.target);
    if (!src || src === section || typeof src.scrollTop !== 'number') return;
    const y = src.scrollTop;
    const collapsed = bar.dock.classList.contains('mh-search-collapsed');
    if (bar.dock.classList.contains('has-query') || document.activeElement === bar.input) return;
    if (!collapsed) {
      if (y > COLLAPSE_AT) setCollapsed(bar, true);
    } else if (y <= EXPAND_AT) {
      setCollapsed(bar, false);
    }
  }, { capture: true, passive: true });
}

// ── 生命周期 ─────────────────────────────────────────────────────────

export function activateSocialSearch() {
  // 门控随 config 到达重对账（nav 事件汇合点）：封存态不建条；缺席默认开放
  // （friendsEnabled 口径）⇒ 激活即建，显式封存 config 到达后下一拍 nav 摘除。
  const syncGate = () => {
    const on = friendsEnabled();
    for (const pageId of PAGE_IDS) {
      if (on) ensureBar(pageId);
      else destroyBar(pageId);
    }
    // 件八（bug② 修点，作者令 2026-11-05 12:28「这个搜索按钮会泄漏到其他页面」）：
    // 每次路由广播（navController.renderPages → `nf:mobile-nav`）把**非 live 页**
    // 的收起态无条件还原为展开长条 —— 回到该页永远是展开的长条，圆钮不会被带走。
    resetCollapseOutsideLive();
  };
  syncGate();
  document.addEventListener('nf:mobile-nav', syncGate);

  // Esc：输入框自有处理（清空回列表）；这里兜底「焦点不在条内」的同键语义 ——
  // 焦点在条外时不吞事件（页面的返回/关闭语义各自保留），故不做全局拦。
  const onLocale = () => {
    for (const bar of bars.values()) {
      bar.input.placeholder = bar.pageId === 'messages'
        ? t('mhsearch.placeholder')
        : t('contacts.searchPlaceholder');
      bar.input.setAttribute('aria-label', bar.input.placeholder);
      bar.clear.title = t('mhsearch.clear');
      bar.clear.setAttribute('aria-label', t('mhsearch.clear'));
      // 件八：收起态的无障碍名随语言面同拍（收起态整条 = 一枚按钮）。
      if (bar.bar.classList.contains('g2-fab')) {
        bar.bar.setAttribute('aria-label', bar.input.placeholder);
      }
      bar.scopeRow?.querySelectorAll('.mh-scope-chip').forEach((c) => {
        const def = SCOPES.find((s) => s.value === (/** @type {HTMLElement} */ (c)).dataset.scope);
        if (def) c.textContent = t(def.labelKey);
      });
      if (bar.note) bar.note.textContent = t('mhsearch.capNote');
    }
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));

  return function teardownSocialSearch() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    document.removeEventListener('nf:mobile-nav', syncGate);
    if (contactTimer) { clearTimeout(contactTimer); contactTimer = null; }
    for (const pageId of [...bars.keys()]) destroyBar(pageId);
    msgEngine = null;
  };
}
