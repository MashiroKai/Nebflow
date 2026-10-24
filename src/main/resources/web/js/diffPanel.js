// diffPanel.js — 左栏「变更文件」面板（diff 拆面板批 2026-10-22，作者令）。
//
// 作者令：「改为在最上面看，而且是一个小的摘要，点击了之后，在左侧面板出现，
// diff的文件列表，在canvas显示选中文件的diff。等于是把目前的这个面板拆为了
// 左右两个面板。」
//
// 架构（与旧形态的分工）：
//   · 摘要条（chip）——挂在委托交付注入气泡顶部（chat/injectedRows.js 建 DOM，
//     点击走本模块 toggleDiffBatch）；
//   · 左栏面板——本模块：#panel-filechanges（#sidebar-panel 的注册面板，与
//     files/messages/contacts 同族共存；无 Activity Bar 按钮，仅程序化唤起），
//     列出该批次变更文件（复用 .nf-fc 行族：徽标/路径/+a −r/来源 tag）；
//   · Canvas——diffCanvas.openFileChangesCanvas 只渲染选中文件的 diff 阅读面
//     （旧 master-detail 一体大面板退役）。
//
// 数据源不变：delegate 终态 WS 帧 / 落盘 UiMessage 的 fileChanges 载荷（引擎
// FileChangeReport.delegateBatchMetadata），live 与历史恢复走同一 builder——零
// 数据迁移，只改呈现架构。
//
// 依赖纪律（scripts/check-circular.mjs）：本模块是**叶子模块**——静态只 import
// i18n.js（其自身为叶）；activityBar / diffCanvas 走**动态 import()**（既定逃生
// 舱，见 chat.js↔sidebar.js 既有 SCC 的处置），不把本模块并入任何静态环。

import { t } from './i18n.js';

/** 注册面板 id（activityBar sidePanels 键；LS_PANEL 持久化值同此）。 */
const PANEL_ID = 'filechanges';
/** 面板 DOM 挂载点父容器（index.html #sidebar-panel）。 */
const HOST_ID = 'sidebar-panel';
/** 面板元素 id（.panel 家族命名 panel-<id>）。 */
const PANEL_ELEM_ID = 'panel-' + PANEL_ID;

/** 当前面板承载的批次（openDiffBatch 记录；chip 激活态与 toggle 判据）。
 *  @type {string | null} */
let currentBatchKey = null;
/** 当前批次的载荷（行点击 → canvas 聚焦用）。
 *  @type {any} */
let currentPayload = null;
/** init 幂等闸。 */
let initialized = false;

/** Batch identity — same key convention as the canvas tab id suffix
 *  (diffCanvas.openFileChangesCanvas)：nodeId 优先，无主批次回落 generatedAt /
 *  'adhoc'。 */
function batchKeyOf(payload) {
  return String(payload?.nodeId || payload?.generatedAt || 'adhoc');
}

/** totals for the quiet header meta（同 diffRender/diffCanvas 的合计口径）。 */
function totalsOf(payload) {
  return (payload?.files || []).reduce((acc, f) => {
    acc.a += Number(f?.added) || 0;
    acc.r += Number(f?.removed) || 0;
    return acc;
  }, { a: 0, r: 0 });
}

/** Build the panel skeleton once (boot). Panel family language: .panel +
 *  .panel-header/.panel-title（css/sidebar.css 基座）——内容面样式落
 *  css/diffView.css 的 .nfp-* 规则。 */
function ensurePanelDom() {
  if (document.getElementById(PANEL_ELEM_ID)) return;
  const host = document.getElementById(HOST_ID);
  if (!host) return;
  const panel = document.createElement('div');
  panel.id = PANEL_ELEM_ID;
  panel.className = 'panel';
  panel.setAttribute('data-panel-id', PANEL_ID);
  panel.innerHTML = `
    <div class="panel-header">
      <span class="panel-title" id="panel-title-${PANEL_ID}"></span>
      <span class="nfp-meta"></span>
    </div>
    <div class="nfp-body">
      <div class="nfp-empty"></div>
      <div class="nfp-list" role="listbox" aria-label="changed files"></div>
    </div>`;
  host.appendChild(panel);
  applyPanelText(panel);
  // 语言切换跟随面板族既例（activityBar.initSidePanels 的 locale-changed 同款）。
  window.addEventListener('locale-changed', () => applyPanelText(panel));
}

/** Apply i18n captions (title + empty state). */
function applyPanelText(panel) {
  const title = panel.querySelector(`#panel-title-${PANEL_ID}`);
  if (title) title.textContent = t('panel.diff');
  const empty = panel.querySelector('.nfp-empty');
  if (empty && !currentPayload) empty.textContent = t('panel.diffEmpty');
}

/** Render one file row into the list — reuses the .nf-fc row family
 *  (badge / path / +a −r / source tag; css/diffView.css). textContent-only
 *  assembly (XSS discipline — payload fields never innerHTML'd). */
function buildFileRow(f, index) {
  const row = document.createElement('div');
  row.className = 'nf-fc nf-fc-row';
  row.setAttribute('role', 'option');
  row.setAttribute('tabindex', '0');
  row.dataset.fcFile = String(index);
  const badge = document.createElement('span');
  badge.className = `nf-fc-badge nf-fc-badge-${String(f?.changeType || 'modified')}`;
  badge.textContent = String(f?.changeType || 'modified');
  const path = document.createElement('span');
  path.className = 'nf-fc-path';
  path.textContent = String(f?.path || '(unknown)');
  path.title = String(f?.path || '');
  const counts = document.createElement('span');
  counts.className = 'nf-fc-counts';
  const added = document.createElement('span');
  added.className = 'nf-fc-added';
  added.textContent = `+${Number(f?.added) || 0}`;
  const removed = document.createElement('span');
  removed.className = 'nf-fc-removed';
  removed.textContent = `−${Number(f?.removed) || 0}`;
  counts.append(added, document.createTextNode(' '), removed);
  row.append(badge, path, counts);
  if (f?.source) {
    const src = document.createElement('span');
    src.className = 'nf-fc-src';
    src.title = 'diff source';
    src.textContent = String(f.source);
    row.appendChild(src);
  }
  return row;
}

/** Mark row `index` active (single-selection semantics; -1 = none). */
function syncActiveRow(index) {
  const list = /** @type {HTMLElement | null} */ (document.querySelector(`#${PANEL_ELEM_ID} .nfp-list`));
  if (!list) return;
  list.querySelectorAll('.nf-fc').forEach((el, i) => {
    el.classList.toggle('active', i === index);
    el.setAttribute('aria-selected', i === index ? 'true' : 'false');
  });
}

/** Mirror the active batch onto every rendered chip (all chat views).
 *  Chips carry data-fc-batch (injectedRows builder); aria-pressed drives both
 *  the a11y state and the pressed styling. */
function syncChipActive(key) {
  document.querySelectorAll('.nf-diff-chip').forEach((el) => {
    const chip = /** @type {HTMLElement} */ (el);
    const on = chip.dataset.fcBatch === key;
    chip.setAttribute('aria-pressed', on ? 'true' : 'false');
  });
}

/** Meta slot faces (二次加载清剿批 2026-10-24)：模块级单点——失败面在下次成功
 *  开图时清除（错误面不粘死，恢复语义 = 成功即清），成功面重建 totals 文案。
 *  面板 DOM 不在时安全空转（meta 是辅助反馈面，缺位不阻塞主路径）。 */
function metaEl() {
  return /** @type {HTMLElement | null} */ (document.querySelector(`#${PANEL_ELEM_ID} .nfp-meta`));
}

/** Failure face: error caption in the meta slot + optional row fail mark. */
function setMetaError(row) {
  const meta = metaEl();
  if (meta) {
    meta.classList.add('nfp-meta-err');
    meta.textContent = t('panel.diffOpenFailed');
  }
  if (row) row.classList.add('nf-fc-openfail');
}

/** Success face: drop the error face, rebuild the quiet totals caption
 *  (files count + +a −r，口径走 totalsOf 单点). */
function setMetaTotals() {
  const meta = metaEl();
  if (!meta) return;
  meta.classList.remove('nfp-meta-err');
  const files = Array.isArray(currentPayload?.files) ? currentPayload.files : [];
  const tot = totalsOf(currentPayload);
  meta.innerHTML = '';
  const n = document.createElement('span');
  n.textContent = `${files.length} file${files.length === 1 ? '' : 's'} · `;
  const a = document.createElement('span');
  a.className = 'nf-fc-added';
  a.textContent = `+${tot.a}`;
  const r = document.createElement('span');
  r.className = 'nf-fc-removed';
  r.textContent = `−${tot.r}`;
  meta.append(n, a, document.createTextNode(' '), r);
}

/** Render the panel body for `payload` (list rows + quiet totals meta). */
function renderBatch(payload) {
  const panel = document.getElementById(PANEL_ELEM_ID);
  if (!panel) return;
  const files = Array.isArray(payload?.files) ? payload.files : [];
  const list = /** @type {HTMLElement | null} */ (panel.querySelector('.nfp-list'));
  const empty = /** @type {HTMLElement | null} */ (panel.querySelector('.nfp-empty'));
  const meta = /** @type {HTMLElement | null} */ (panel.querySelector('.nfp-meta'));
  if (!list || !empty || !meta) return;
  list.innerHTML = '';
  empty.hidden = files.length > 0;
  list.hidden = files.length === 0;
  if (!files.length) return;
  // 二次加载清剿批 2026-10-24（作者报障「点了没反应」）：行点击链的失败腿曾有
  // `.catch(() => {})` 静默吞掉（动态 import 失败 / canvas 通道不可用 / 面板 DOM
  // 不在）——任何取数/渲染路径必须有失败可见反馈（作者令②）。失败 ⇒ meta 槽
  // 显示错误文案 + 行失败态；重试成功 ⇒ 失败面清除（setMetaTotals，不粘死）。
  files.forEach((f, i) => {
    const row = buildFileRow(f, i);
    const open = () => {
      syncActiveRow(i);
      list.querySelectorAll('.nf-fc-openfail').forEach((el) => el.classList.remove('nf-fc-openfail'));
      import('./diffCanvas.js').then(({ openFileChangesCanvas }) => {
        const entry = openFileChangesCanvas(payload, { focusIndex: i });
        if (!entry) setMetaError(row); // canvas 容器不在（openTab null）——可见失败，禁静默
        else setMetaTotals(); // 成功 = 失败面清除（错误态不残留）
      }).catch(() => setMetaError(row));
    };
    row.addEventListener('click', open);
    row.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
    });
    list.appendChild(row);
  });
  setMetaTotals();
}

/** Boot hook (main.js, after initActivityBar)：建面板 DOM + 注册进侧栏面板注册表。
 *  注册走动态 import（依赖纪律见文件头）——main.js 调用时序在 initActivityBar
 *  之后，故持久化的陈旧 'filechanges' 面板 id 在本次 boot 已回落 files（diff
 *  面板是瞬态视图：批次载荷只在注入帧在场，刷新后由 chip 重新唤起）。 */
export function initDiffPanel() {
  if (initialized) return;
  initialized = true;
  ensurePanelDom();
  import('./activityBar.js').then(({ registerSidePanel }) => {
    // 无 Activity Bar 按钮（buttonId 落空）——面板只由交付消息上的摘要条唤起；
    // registerSidePanel 对缺失按钮安全（map 先登记、btn 判空后置）。
    registerSidePanel({ id: PANEL_ID, buttonId: '', panelId: PANEL_ELEM_ID, i18nKey: 'panel.diff' });
  }).catch(() => { /* 注册表不可达——面板 DOM 仍在，显示路径降级为类自管 */ });
}

/** Open (or switch to) a delegate batch: render the left-panel file list,
 *  reveal the panel (Side Bar expands if collapsed), and focus `focusIndex`'s
 *  diff in the Canvas reader.
 *  @param {any} payload fileChanges wire shape {nodeId, generatedAt, root, files[]}
 *  @param {{focusIndex?: number}} [opts]
 */
export async function openDiffBatch(payload, opts = {}) {
  const key = batchKeyOf(payload);
  currentBatchKey = key;
  currentPayload = payload;
  ensurePanelDom();
  try {
    renderBatch(payload);
  } catch {
    // 二次加载清剿批：载荷畸形（渲染装配抛错）不再静默无响应——chip/程序化入口
    // 的渲染路径也必须有失败可见反馈（作者令②），否则 = 「点了没反应」。列表可能
    // 半渲染——下次成功 open 全量重建（renderBatch 先清空）。
    setMetaError(null);
    return;
  }
  syncChipActive(key);
  syncActiveRow(Number(opts.focusIndex) || 0);
  try {
    const bar = await import('./activityBar.js');
    bar.showSidePanel(PANEL_ID);
  } catch { /* 注册表不可达——面板类已切，可见性交给既有面板族样式 */ }
  try {
    const { openFileChangesCanvas } = await import('./diffCanvas.js');
    const entry = openFileChangesCanvas(payload, { focusIndex: Number(opts.focusIndex) || 0 });
    if (!entry) {
      // 二次加载清剿批：canvas 通道不可用（容器不在）不再静默——meta 槽可见失败
      // 文案（左栏列表照常成立；作者令②禁静默无响应）。
      setMetaError(null);
    } else {
      setMetaTotals(); // 成功 = 失败面清除（错误态不残留）
    }
  } catch {
    setMetaError(null);
  }
}

/** Chip toggle entry (chat/injectedRows 摘要条点击单点)。
 *  已打开语义（作者令「已打开时再点收起」，按现有面板 toggle 语义）＝同一批次
 *  已在面板承载 + 面板处于激活 + 侧栏展开 ⇒ 收起侧栏（VSCode 式，与 Activity
 *  Bar 图标再点同族）；否则打开/切换该批次。Canvas 阅读面是共享面，有自己的
 *  关闭钮，不随本 toggle 收敛。 */
export async function toggleDiffBatch(payload, opts = {}) {
  const key = batchKeyOf(payload);
  let bar = null;
  try { bar = await import('./activityBar.js'); } catch { bar = null; }
  const panelActive = document.getElementById(PANEL_ELEM_ID)?.classList.contains('active') || false;
  const collapsed = bar ? bar.isSideBarCollapsed() : false;
  if (currentBatchKey === key && panelActive && !collapsed && bar) {
    bar.setSideBarCollapsed(true);
    return;
  }
  await openDiffBatch(payload, opts);
}
