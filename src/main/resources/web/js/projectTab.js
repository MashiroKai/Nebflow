// projectTab.js — Project 标签页（#27 方向调整：项目入口以标签页呈现，参考 team 面板设计）。
// 点击侧边栏 Project 按钮 → 打开 `projects` Canvas 标签页；一行/卡片一个 project，
// 沿用 .team-card 视觉与交互范式。点击 project name → 同一标签页就地切换到其
// Flow Map 视图（不新开标签页），左上角「返回项目列表」切回列表；
// 空 Flow Map（无节点）的项目 title 不可点。AGENTS.md 入口 → rules 式查看/编辑 overlay。
//
// ── 实时更新 + 载入/归档动画（tabrealtime 批 2026-09-17 · 作者五项裁定 (a)-(e)）──────
// (a) 「项目标签页」= 本页签内的**项目卡片列表**（取证报告§主读法；代码面不存在
//     「每项目一 tab」结构）⇒ 实施面只在此列表。
// (b) 方案 B（正典）：后端补 projectCreated / projectArchived 两事件（载荷逐字取证
//     §D-2），前端在本文件订阅——🔴 方案 A（订阅既有 toolCallDetected / toolEnd）不取，
//     也不作兜底（语义寄生、脆弱、错位）。
// (c) 同批加低频兜底重拉（见文末「兜底 C」）：进程外改动（有人直接编辑/删除
//     project.json）任何事件方案都覆盖不到。🔴 (c) 明确**不并** D（canvas.js state 3
//     补拉）⇒ 本文件与 canvas.js 零交互；「切回页签看到陈旧列表」由兜底 C 的 60s 窗
//     覆盖，state 3 另立小批。
// (d) FLIP 进本批（唯一零跳变路径，辅助模块 = web/js/listFlip.js）。
// (e) 两身份事件（不采单 projectsChanged：需前端拉全量分辨、语义不清）。
//
// 🔴 渲染出口硬约束（取证 §F-2）：旧出口是 `scroll.innerHTML = projects.map(...)`
// **全量替换**——退场动画与全量替换互斥（重拉返回会把动画中的 DOM 直接换掉）。
// 本批改为**名单差集 → 按名定点增删**：新增 = 只插该卡的节点（anchor.after / prepend），
// 移除 = 退场动画结束后定点 el.remove()，刷新 = 只定点写该卡的摘要槽；
// 其余卡片的节点身份（data-project）、顺序与实例在增删/刷新中全部保持不变。
// 事件到达即起步动画（不等重拉返回）；重拉只在「名单与磁盘不一致」时产生一次定点增删。

import { openTab, getTabPane } from './canvas.js';
import { ensureFlowCss } from './flowCss.js';
import { esc, authHeaders } from './flowHelpers.js';
import { t } from './i18n.js';
import { contentText } from './contentI18n.js';
import { fetchProjects, fetchFlowMap, summarize, API } from './nodeData.js';
import { renderFlowMapInto } from './flowMapTab.js';
import { openAgentFile } from './agentFileViewer.js';
import { playFlip, prefersReducedMotion, snapshotRects } from './listFlip.js';

// 两段动画取值（🔴 逐字对齐取证报告 §F「统一取值」表，零新造）：
// 入场本体 220ms / 退场本体 280ms / 一次性强调环 900ms / 邻卡 FLIP 240ms /
// 骨架脉冲 1.2s（CSS 侧）/ reduced-motion 降级 = 无动画。
// 缓动两段均 cubic-bezier(0.16, 1, 0.3, 1)（§F 统一取值表；与 reminderRowRemove
// 0.35s 同族、向 0.28s 档收）。CSS 侧同名值落 web/css/projectPanel.css。
const PROJECT_CARD_ENTER_MS = 220;        // §F 入场本体
const PROJECT_CARD_EXIT_MS = 280;         // §F 退场本体
const PROJECT_NEW_EMPHASIS_MS = 900;      // §F 一次性强调环保持时长
const PROJECT_NEW_EMPHASIS_REDUCED_MS = 2000; // §F-3 降级：静态环保持 ~2s（新卡仍可辨识）
const PROJECT_FLIP_MS = 240;              // §F 邻卡 FLIP 衔接
const PROJECT_FLIP_EASE = 'cubic-bezier(0.4, 0, 0.2, 1)'; // split.css:503 flex 族同值
const PROJECTS_FALLBACK_POLL_MS = 60_000; // 兜底 C 频率：对齐 taskList.js:209 范式

function openProjectTab() {
  const pane = getTabPane('projects');
  if (!pane) return;
  ensureFlowCss();
  // 视图状态复位为列表：projects 标签页是「项目列表 ⇄ Flow Map 就地视图」双态页，
  // 列表渲染进 .team-scroll；若当前在 Flow Map 就地视图（nav-bar + flowmap-view-body），
  // 先清掉再建滚动体，避免列表渲染进旧 Flow Map 滚动体、或 nav-bar 残留在列表上方。
  pane.dataset.projectsView = 'list';
  delete pane.dataset.flowMapProject;
  if (pane.querySelector('.flowmap-nav-bar')) pane.innerHTML = '';
  const scroll = ensureScroll(pane);
  resetProjectsRetry(); // 用户动作打开面板 = 新一轮序列，重试预算重置
  renderProjectsInto(scroll);
}

/** 滚动体（卡片列表容器）+ 屏幕阅读器状态行。
 *  状态行（§F-3 可访问性）：`aria-live="polite"` 挂在**独立的状态行**上，
 *  🔴 不挂 `.team-scroll` 本体（全量/定点渲染会让逐卡文本被反复播报）。
 *  容器 `tabindex="-1"`：退场时若焦点在卡内且无下一张卡，焦点移交给容器
 *  （§F-3③；无 tabindex 的元素 focus() 是 no-op）。 */
function ensureScroll(pane) {
  let scroll = pane.querySelector('.team-scroll');
  if (!scroll) {
    pane.innerHTML = '';
    const live = document.createElement('div');
    live.className = 'project-live-status';
    live.setAttribute('role', 'status');
    live.setAttribute('aria-live', 'polite');
    pane.appendChild(live);
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.tabIndex = -1;
    pane.appendChild(scroll);
  }
  return scroll;
}

/** 实时播报（polite 状态行）。文案 key = project.createdAnnounce / project.archivedAnnounce。 */
function announceProjects(scroll, text) {
  if (!scroll || !text) return;
  const live = scroll.closest('.canvas-tab-pane')?.querySelector('.project-live-status');
  if (live) live.textContent = text;
}

/** 打开 Project 标签页（由 activity bar Project 按钮调用）。 */
export function openProjectsTab() {
  openTab('projects', t('project.title'), { type: 'projects', closable: true });
  openProjectTab();
}

// ── 迟到对账（2026-09-14 项目面板冷启动缺陷修复）───────────────────────────────
// 缺陷（考古实测）：面板取数**只有**「打开面板」与「节点 WS 事件」两条触发，首取失败后
// **没有任何恢复通道**——gateway 冷启动窗口内打开面板 ⇒ 落 error 态并驻留 60.0s 零自愈
// （服务器已回来、WS 也已自动重连），只有用户「关面板重开」才恢复。
// 本段给出两条**互相独立**的迟到对账通道（任一即可闭合窗口）：
//   ① scheduleProjectsRetry：首取失败后按下面档位有界退避重拉（不依赖任何外部信号）；
//   ② 文件尾 onReconnect：WS 重连时面板若非 ready 立即补一次（对齐 flowMapTab.js
//      `onReconnect(() => refreshFlowMapViews())` / taskList.js 既有范式）。
// 两条通道都复用下面 renderProjectsInto 的既有分支判定，**不新造状态、不改 empty/error
// 判定**——空态与错误态依旧是两条独立分支，重试只是让「数据迟到」这件事能被再问一次。
// 不掩盖失败：预算穷尽后仍停在 error 并保留原始原因（真有故障时错误态照旧驻留）。
// 档位依据：实测冷启动窗口 boot ≈25.3s（证据 server-s2-restart.log）；若 10s 内穷尽，
// 在「WS 被环境闸住」时无法自愈，故末两档放宽到 10s/20s（自首次失败起累计 ≈40s）。
const PROJECT_RETRY_DELAYS_MS = [1000, 3000, 6000, 10000, 20000];
let projectsRetryTimer = null;
let projectsRetryAttempt = 0;

/** 重试预算归零 + 摘掉在途定时器。触发点 = 用户动作（打开/重开面板）与 WS 重连
 *  ——两者都表示「条件已变」，旧预算不再适用。 */
function resetProjectsRetry() {
  projectsRetryAttempt = 0;
  if (projectsRetryTimer) { clearTimeout(projectsRetryTimer); projectsRetryTimer = null; }
}

/** 有界退避：首取失败后按 PROJECT_RETRY_DELAYS_MS 逐档重拉；穷尽即停在 error。 */
function scheduleProjectsRetry(scroll) {
  if (projectsRetryAttempt >= PROJECT_RETRY_DELAYS_MS.length) return;
  const delay = PROJECT_RETRY_DELAYS_MS[projectsRetryAttempt++];
  if (projectsRetryTimer) clearTimeout(projectsRetryTimer);
  projectsRetryTimer = setTimeout(() => {
    projectsRetryTimer = null;
    // pane 已关（scroll 脱树）⇒ 停手：不让定时器在后台空转打 REST。
    if (!scroll.isConnected) return;
    if (scroll.dataset.projectsState === 'ready') return;
    renderProjectsInto(scroll);
  }, delay);
}

async function renderProjectsInto(scroll) {
  if (!scroll) return;
  // 渲染代：WS churn（nodeCreated/Updated/…）与手动打开会并发发起渲染，
  // 慢的那次回来晚就会用旧数据盖掉新数据（卡片"时有时无"的根因之一）。
  // 只有最后一次发起的渲染允许写 DOM。
  const seq = ++renderSeq;
  const stale = () => seq !== renderSeq || !scroll.isConnected;
  // 已有卡片时不回退到 loading——事件驱动重渲不该让列表闪成空白。
  if (!scroll.querySelector('.project-card')) {
    scroll.dataset.projectsState = 'loading';
    scroll.innerHTML = `<div class="flowmap-loading">${esc(t('project.loading'))}</div>`;
  }
  let projects;
  try {
    projects = await fetchProjects();
  } catch (e) {
    if (stale()) return;
    // 已有卡片 ⇒ **保留现列表**（一次刷新失败不该把可见列表换成错误页——兜底 C 的
    // 低频重拉失败尤其不能毁掉用户正看的列表）；无卡片 ⇒ 显式错误态（可诊断可断言，
    // 旧行为：await 抛出 → 停在 loading、卡片数 0 且无任何提示）。
    if (!scroll.querySelector('.project-card')) {
      scroll.dataset.projectsState = 'error';
      scroll.innerHTML = `<div class="team-empty">
      <div style="font:600 14px -apple-system;color:var(--color-text-muted)">${esc(t('project.loadFail'))}</div>
      <div class="hint">${esc(e?.message || '')}</div>
    </div>`;
      // 迟到对账通道 ①：服务/网络恢复后自己把列表补上（不等用户动作、不等 WS）。
      scheduleProjectsRetry(scroll);
    }
    return;
  }
  if (stale()) return;
  resetProjectsRetry(); // 取数成功（无论空态/就绪态）⇒ 本轮重试预算归零
  if (!projects || projects.length === 0) {
    if (scroll.querySelector('.project-card')) {
      // 名单变空但卡还在 ⇒ 逐卡定点退场（动画），最后一卡退完后由 afterCardsChanged
      // 落空态——不在此刻用 innerHTML 把退场中的卡直接换掉。
      scroll.dataset.projectsState = 'empty';
      for (const card of projectCards(scroll)) startCardExit(scroll, card);
      return;
    }
    renderEmptyState(scroll);
    return;
  }
  scroll.dataset.projectsState = 'ready';
  // 名单差集 → 按名定点增删（🔴 不再全量 innerHTML 替换：那会吃掉动画，也换掉全部卡节点）。
  reconcileProjectCards(scroll, projects);
  // 摘要**渐进填充**（取证 §F-1 结构性发现）：不再 `Promise.all` 等全部 flow-map 到齐
  // 才写第一笔 DOM——先按名单画全卡（名称/工作区即刻可见），摘要槽走骨架脉冲，
  // 逐项目到齐后就地填入。单项目 flow-map 慢/悬挂只影响它自己的槽（旧实现会把整列
  // 钉在旧内容上）。
  for (const p of projects) loadCardSummary(scroll, p.name);
}

/** 卡节点集合（文档顺序）。 */
function projectCards(scroll) {
  return [...scroll.querySelectorAll('.project-card')];
}

/** 稳定卡（排除退场在途者）——用于名单比对、顺序锚点与计数。 */
function stableCards(scroll) {
  return projectCards(scroll).filter((el) => el.dataset.exiting !== '1');
}

/** 按名取卡（用遍历而非属性选择器：项目名可能含引号等选择器特殊字符）。 */
function cardByName(scroll, name) {
  if (!scroll || !name) return null;
  return projectCards(scroll).find((el) => el.dataset.project === name) || null;
}

/** 清掉占位件（loading / empty / error 三个分支留下的非卡子节点）。 */
function clearPlaceholders(scroll) {
  for (const el of [...scroll.children]) {
    if (!el.classList.contains('project-card')) el.remove();
  }
}

/** 名单差集 → 按名定点增删（P3 硬判据）：
 *  ① 新名单里没有的卡 → 退场 → 定点 remove；② 新名单里有而 DOM 里没有的 → 定点插入；
 *  ③ 顺序漂移只搬动漂移的那张卡自身（身份/实例不变）。
 *  其余卡片一律不重建：不进 innerHTML、不重绑事件（重绑会叠监听器）。 */
function reconcileProjectCards(scroll, projects) {
  if (rebuildCardsOnce) {
    // 语言切换一次性重建（见文末 locale-changed）：卡内文案（含 contentI18n 的项目名/
    // 描述）必须整体重写，定点刷新覆盖不到全部静态标签 ⇒ 只此一路径允许重建，
    // 且静默（不是增删事件，不播入场动画）。
    rebuildCardsOnce = false;
    for (const card of projectCards(scroll)) card.remove();
  }
  const wanted = new Set(projects.map((p) => p.name));
  for (const card of projectCards(scroll)) {
    if (!wanted.has(card.dataset.project)) startCardExit(scroll, card);
  }
  // 🔴 占位件（loading / empty / error）**无条件**清掉：它们是「列表尚未就绪」的替身，
  // 不是列表内容——权威名单一到就必须让位，无论此刻 DOM 里有没有卡。
  // 旧写法 `if (projectCards(scroll).length)` 只在**已有卡**时清：本批出口从
  // `innerHTML = projects.map(...)`（全量替换，天然吃掉占位件）改为定点增删后，
  // **首渲**（DOM 里 0 张卡、只有 loading 占位）走 clearPlaceholders 被整个跳过 ⇒
  // 卡片随后替前插入，loading 节点作为兄弟**残留**（冷启动「加载项目…」常驻的根因；
  // 同理「空态 → 有项目」转换会残留 `.team-empty`）。clearPlaceholders 只摘非
  // `.project-card` 子节点，对退场在途的卡零影响 ⇒ 无条件调用安全且幂等。
  clearPlaceholders(scroll);
  let anchor = null;
  for (const p of projects) {
    let card = cardByName(scroll, p.name);
    if (!card) card = insertProjectCard(scroll, p, anchor);
    else if (prevStableCard(card) !== anchor) moveProjectCard(scroll, card, anchor);
    anchor = card;
  }
  scroll.dataset.projectCount = String(stableCards(scroll).length);
}

/** 前一张「稳定」卡（跳过退场在途者）——顺序比对用，避免退场卡把邻卡误判成漂移。 */
function prevStableCard(card) {
  let el = card.previousElementSibling;
  while (el && !(el.classList.contains('project-card') && el.dataset.exiting !== '1')) {
    el = el.previousElementSibling;
  }
  return el;
}

/** 定点插入一张卡：只新建/插入该节点（邻卡位置变化交给 FLIP 过渡）。 */
function insertProjectCard(scroll, p, anchor) {
  const before = snapshotRects(projectCards(scroll));
  const card = createProjectCard(p);
  if (anchor && anchor.isConnected) anchor.after(card);
  else scroll.prepend(card);
  playFlip(before, { duration: PROJECT_FLIP_MS, easing: PROJECT_FLIP_EASE });
  playCardEnter(card);
  return card;
}

/** 定点搬动一张卡（顺序漂移修复）：身份/实例不变，位置变化由 FLIP 过渡。 */
function moveProjectCard(scroll, card, anchor) {
  const before = snapshotRects(projectCards(scroll));
  if (anchor && anchor.isConnected) anchor.after(card);
  else scroll.prepend(card);
  playFlip(before, { duration: PROJECT_FLIP_MS, easing: PROJECT_FLIP_EASE });
}

/** 定点退场：施加退场类 → 动画结束（reduced-motion 为即时）→ remove + FLIP 衔接邻卡。
 *  `data-exiting` 兼作幂等闸：同一张卡不会被二次退场（本页归档的回声帧 / 兜底重拉
 *  与事件叠加时都靠它去重）。 */
function startCardExit(scroll, card) {
  if (!card || !card.isConnected || card.dataset.exiting === '1') return;
  card.dataset.exiting = '1';
  releaseCardFocus(card); // §F-3③：退场前把焦点移出（卡随后不可交互）
  // 退场期对卡内全部交互件加闸（§F-3：`reminderRowRemove` 同款 pointer-events:none
  // 由 CSS 承担；这里补键盘面 tabindex/marker，防 Tab 落进正在消失的卡）。
  for (const el of card.querySelectorAll('button, a, [tabindex]')) {
    el.setAttribute('tabindex', '-1');
  }
  const finish = () => {
    if (!card.isConnected) return;
    const before = snapshotRects(projectCards(scroll).filter((el) => el !== card));
    card.remove(); // 定点移除：只摘这一张卡的节点
    playFlip(before, { duration: PROJECT_FLIP_MS, easing: PROJECT_FLIP_EASE });
    afterCardsChanged(scroll);
  };
  if (prefersReducedMotion()) { finish(); return; } // §F-3：降级 = 无动画 + 同步 remove()
  card.classList.add('is-exiting');
  const onEnd = (e) => {
    // animationend 会从子元素冒泡（骨架脉冲也有 animation）⇒ 只认卡自身
    if (e.target !== card) return;
    card.removeEventListener('animationend', onEnd);
    finish();
  };
  card.addEventListener('animationend', onEnd);
  // 定时器兜底（动画被主题/降级规则覆盖时 animationend 不保证到达）
  setTimeout(() => { card.removeEventListener('animationend', onEnd); finish(); },
    PROJECT_CARD_EXIT_MS + 60);
}

/** 焦点移交（§F-3③）：焦点在退场卡内 ⇒ 移交下一张卡的归档按钮，无下一张则移交容器。 */
function releaseCardFocus(card) {
  if (!card.contains(document.activeElement)) return;
  const cards = projectCards(card.parentElement);
  const idx = cards.indexOf(card);
  const next =
    cards.slice(idx + 1).find((c) => c.dataset.exiting !== '1')
    || cards.slice(0, idx).reverse().find((c) => c.dataset.exiting !== '1');
  const target = next?.querySelector('.project-archive-btn') || card.parentElement;
  target?.focus?.();
}

/** 增删后的账目收口：计数 + 「最后一张卡也没了 ⇒ 落空态」（等退场在途者退完）。 */
function afterCardsChanged(scroll) {
  if (!scroll || !scroll.isConnected) return;
  scroll.dataset.projectCount = String(stableCards(scroll).length);
  const pendingExit = projectCards(scroll).some((el) => el.dataset.exiting === '1');
  if (!stableCards(scroll).length && !pendingExit) renderEmptyState(scroll);
}

/** 空态（一键引导版，作者 2026-09-16 裁定方向 B · 仅预填）。
 *  既有两行文案不动，其下 1 枚 CTA + 1 行披露注（点击只预填、不发送）。
 *  材质不在此写——按现行单源 `.glass-control`（sapphire.css:147 基类 + `button.glass-control`
 *  四态）；样式在 flowCss.js 限布局/排版。 */
function renderEmptyState(scroll) {
  scroll.dataset.projectsState = 'empty';
  scroll.dataset.projectCount = '0';
  scroll.innerHTML = `<div class="team-empty">
      <div style="font:600 14px -apple-system;color:var(--color-text-muted)">${esc(t('project.empty'))}</div>
      <div class="hint">${esc(t('project.emptyHint'))}</div>
      <button class="glass-control team-empty-cta" type="button" data-projects-empty-cta="1"><i data-lucide="message-circle"></i>${esc(t('project.emptyCta'))}</button>
      <div class="hint team-empty-cta-note">${esc(t('project.emptyCtaHint'))}</div>
    </div>`;
  bindEmptyCta(scroll);
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(scroll));
}

/** 单卡模板。摘要槽**初始为骨架态**（渐进填充），title 初始按「摘要未知」处理（可点）；
 *  真实态由 applyCardSummary / applyCardEmptyState 定点写入（不重建节点）。 */
function projectCardHtml(p) {
  const name = p.name;
  return `
    <div class="team-card project-card" data-project="${esc(name)}" data-running="0">
      <div class="team-card-header">
        <div class="team-card-title" data-open-flowmap="${esc(name)}" title="${esc(t('project.openFlowMap', { name }))}"><span class="project-card-name">${esc(contentText('project', name, 'name', name))}</span><span class="project-empty-tag" hidden>${esc(t('project.noNodes'))}</span></div>
        <div class="team-card-summary is-pending"><span class="dot"></span><span class="project-summary-skeleton" aria-hidden="true"></span></div>
        <button class="project-archive-btn" data-archive-project="${esc(name)}" title="${esc(t('project.archive'))}" aria-label="${esc(t('project.archiveTitle', { name }))}"><i data-lucide="archive"></i></button>
      </div>
      <div class="project-fields">
        <div class="project-field" title="${esc(p.workspace)}">
          <span class="project-field-label">${esc(t('project.workspace'))}</span>
          <span class="project-field-value mono">${esc(p.workspace)}</span>
          <button class="project-open-btn" data-open-workspace="${esc(p.workspace || '')}" title="${esc(t('project.openWorkspace'))}" aria-label="${esc(t('project.openWorkspace'))}"><i data-lucide="folder-open"></i></button>
        </div>
        <div class="project-field">
          <span class="project-field-label">${esc(t('project.agentFileLabel'))}</span>
          <button class="project-link-btn" data-open-agent="${esc(name)}">${esc(t('project.agentFileOpen'))}</button>
        </div>
        <div class="project-field">
          <span class="project-field-label">${esc(t('project.runningAgents'))}</span>
          <span class="project-field-value tabular" data-running-count="${esc(name)}">0</span>
        </div>
        ${p.description ? `<div class="project-field"><span class="project-field-value desc">${esc(contentText('project', name, 'desc', p.description))}</span></div>` : ''}
      </div>
    </div>`;
}

/** 建卡元素（含该卡自身的图标与事件绑定——按卡一次，不重绑）。 */
function createProjectCard(p) {
  const wrap = document.createElement('div');
  wrap.innerHTML = projectCardHtml(p).trim();
  const card = wrap.firstElementChild;
  if (!card) return null;
  bindProjectCard(card);
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(card));
  return card;
}

/** 摘要三态定点写入（只写该卡的摘要槽与运行数/可点性，不触碰其他卡）：
 *  - `pending`：骨架脉冲（flow-map 在途，§F 1.2s；判据 = 尚未知有无节点 ⇒ title 保持可点）
 *  - `ok`     ：真实摘要（running 数 / brief / 是否无节点）
 *  - `unknown`：flow-map 拉取失败（状态未知）⇒ 保持可点（原语义：让用户进视图看错误态）。 */
function applyCardSummary(card, state, summary) {
  if (!card || !card.isConnected) return;
  const running = state === 'ok' ? (summary?.running || 0) : 0;
  card.dataset.running = String(running);
  const count = card.querySelector('[data-running-count]');
  if (count) count.textContent = String(running);
  const slot = card.querySelector('.team-card-summary');
  if (slot) {
    slot.classList.toggle('running', running > 0);
    slot.classList.toggle('is-pending', state === 'pending');
    if (state === 'pending') {
      slot.innerHTML = '<span class="dot"></span><span class="project-summary-skeleton" aria-hidden="true"></span>';
    } else {
      const brief = state === 'ok'
        ? (summary?.notMounted ? t('project.notMounted') : (summary?.brief || t('project.idle')))
        : t('project.idle');
      slot.innerHTML = `<span class="dot"></span>${esc(brief)}`;
    }
  }
  if (state === 'pending') return; // 在途：不判定「无节点」，title 保持可点
  // 无节点（total === 0，含未挂载）⇒ title 不可点 + 灰显 + 「暂无节点」角标；
  // summary 为 null（拉取失败）⇒ 状态未知 ⇒ 保持可点。
  applyCardEmptyState(card, state === 'ok' && summary !== null && (summary?.total ?? 0) === 0);
}

/** title 可点性/空态角标定点切换（原模板里的 `empty` 分支现在按摘要到达时刻补写）。 */
function applyCardEmptyState(card, empty) {
  const name = card.dataset.project || '';
  card.classList.toggle('is-empty', empty);
  if (empty) card.setAttribute('data-empty-flowmap', '1');
  else card.removeAttribute('data-empty-flowmap');
  const title = card.querySelector('.team-card-title');
  if (!title) return;
  title.classList.toggle('empty', empty);
  if (empty) delete title.dataset.openFlowmap; // 🔴 无节点 → 点进 Flow Map 无意义
  else title.dataset.openFlowmap = name;
  title.title = empty ? t('project.noNodesHint', { name }) : t('project.openFlowMap', { name });
  const tag = title.querySelector('.project-empty-tag');
  if (tag) tag.hidden = !empty;
}

/** 单卡摘要取数 + 定点填充（渐进填充；失败只影响该卡槽）。 */
function loadCardSummary(scroll, name) {
  if (!name) return;
  const seq = renderSeq;
  fetchFlowMap(name)
    .then((fm) => {
      if (seq !== renderSeq) return;
      applyCardSummary(cardByName(scroll, name), 'ok', summarize(fm));
    })
    .catch(() => {
      if (seq !== renderSeq) return;
      applyCardSummary(cardByName(scroll, name), 'unknown', null);
    });
}

/** 入场（220ms 淡入 + 2px 上浮）+ 一次性强调环（900ms 后摘除）+ 屏幕阅读器「新」标记。
 *  reduced-motion（§F-3）：无动画 + 静态环保持 ~2s（「哪张新」仍可判读，语义完整）。 */
function playCardEnter(card) {
  if (!card) return;
  const reduced = prefersReducedMotion();
  const header = card.querySelector('.team-card-header');
  let mark = null;
  if (header) {
    mark = document.createElement('span');
    mark.className = 'project-new-mark';
    mark.textContent = t('project.new');
    header.insertBefore(mark, header.firstChild);
  }
  card.classList.add('is-new');
  if (!reduced) card.classList.add('is-entering');
  setTimeout(() => {
    card.classList.remove('is-new');
    card.classList.remove('is-entering');
    mark?.remove();
  }, reduced ? PROJECT_NEW_EMPHASIS_REDUCED_MS : PROJECT_NEW_EMPHASIS_MS);
}

/** 单卡事件绑定（随卡生命周期，一次）。
 *  title 的点击**总是**绑定，命中与否由 `data-open-flowmap` 在场判定——定点刷新下
 *  卡的「无节点」态会随后到达的摘要改变，按属性选择器择时绑定会漏/重。 */
function bindProjectCard(card) {
  const title = card.querySelector('.team-card-title');
  title?.addEventListener('click', (e) => {
    e.stopPropagation();
    // 同标签页切换：不开新标签页，当前 projects 标签页就地显示 Flow Map 视图
    // （左上角「返回项目列表」回到列表视图）。
    const name = title.dataset.openFlowmap;
    if (name) openFlowMapInPlace(name);
  });
  card.querySelector('[data-open-agent]')?.addEventListener('click', (e) => {
    e.stopPropagation();
    openAgentFile(e.currentTarget?.getAttribute('data-open-agent') || '');
  });
  card.querySelector('[data-open-workspace]')?.addEventListener('click', (e) => {
    e.stopPropagation();
    // §3.5「在文件浏览器中打开」——把侧边栏文件浏览器的根切到工作区并展开面板。
    // 动态 import：projectTab 是 Canvas 标签页模块，静态依赖 explorer 会把
    // 文件树拉进项目面板的首屏加载路径（且 explorer → activityBar → ... 更容易
    // 绕出循环依赖）。
    const path = e.currentTarget?.getAttribute('data-open-workspace') || '';
    if (!path) return;
    import('./explorer.js').then(({ openExplorerAt }) => {
      openExplorerAt(path);
      window.__showToast?.(t('project.workspaceOpened', { path }), 'info');
    }).catch(() => window.__showToast?.(t('project.openWorkspaceFail'), 'error'));
  });
  card.querySelector('[data-archive-project]')?.addEventListener('click', (e) => {
    e.stopPropagation();
    archiveProject(e.currentTarget?.getAttribute('data-archive-project') || '');
  });
}

// ── 空态 CTA：一键引导（方向 B · 行为 = 仅预填）────────────────────────────────
// 作者卡裁定（2026-09-16）：空态给「一键引导」；**行为硬约束 = 仅预填**——把预填文案
// 写进主输入框 + 聚焦该输入框；🔴 禁自动发送、🔴 禁走 `injectUserMessage`（等价判据 =
// 点击后零网络请求、零消息产生）。落点说明：`#input` 是 index.html:276 的静态主输入框，
// 与 main.js:225 交给 ChatView('primary') 的 `dom.input` 是**同一个元素**，故本文件不需要
// 新增任何输入模块出口。唯一副作用通道 = 写值后派发一次冒泡 `input` 事件，交给输入模块
// **既有**的两条监听（input.js 的 auto-grow 高度自适应、活动视图锚定 setActiveView）——
// 本分支零新增监听、零新增网络调用、不触碰任何发送路径。文案全部来自 i18n 键。

/** 绑定空态 CTA 的点击（单枚按钮，就地绑定）。 */
function bindEmptyCta(scroll) {
  scroll.querySelectorAll('[data-projects-empty-cta]').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      prefillProjectPrompt();
    });
  });
}

/** 把「创建一个项目」的预填指令写进主输入框并聚焦（🔴 只写值 + 聚焦，不发送）。 */
function prefillProjectPrompt() {
  const input = /** @type {HTMLTextAreaElement | null} */ (document.getElementById('input'));
  if (!input) return;
  input.value = t('project.emptyPrefill');
  // 冒泡 input 事件 = 输入模块既有监听（auto-grow / 活动视图锚定）的入口；
  // 不新增监听、不触发发送、不产生任何网络调用。
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.focus();
}

/** 归档项目（迁移方案 v2 §6.1）：显式人工动作——确认弹层（防误触，复用
 *  window.__showConfirm 既有确认范式）→ POST /api/projects/<name>/archive →
 *  **立即对目标卡施加定点退场**（取证 §F-2 触发条件①：不等重拉返回——重拉的全量
 *  替换会吃掉动画；本批出口已改成定点增删，故重拉也不会再吃掉它）。
 *  后端 ProjectStore.list 源头过滤归档项 ⇒ 兜底 C 的下一次重拉名单里也不会有它。
 *  零删除零移动：仅 project.json 打归档标记，workspace 与定义文件全部保留。
 *  tone:'neutral'——归档可逆（可取消归档），确认钮不用 danger 红。
 *  后端同批广播的 projectArchived 帧随后到达本页（同一动作的回声）——由
 *  startCardExit 的 `data-exiting` 幂等闸吸收，不产生第二次退场/第二次渲染。 */
function archiveProject(name) {
  if (!name) return;
  window.__showConfirm?.(
    t('project.archiveTitle', { name }),
    t('project.archiveConfirm', { name }),
    async () => {
      try {
        const r = await fetch(`${API.projects}/${encodeURIComponent(name)}/archive`, {
          method: 'POST',
          headers: authHeaders(),
        });
        if (!r.ok) throw new Error(`archive ${r.status}`);
        window.__showToast?.(t('project.archiveDone', { name }), 'success');
        const scroll = projectsListScroll() || getTabPane('projects')?.querySelector('.team-scroll');
        const card = scroll ? cardByName(scroll, name) : null;
        if (scroll && card) {
          startCardExit(scroll, card);            // 退场动画（280ms）→ 定点 remove + FLIP
          announceProjects(scroll, t('project.archivedAnnounce', { name }));
        } else {
          rerenderProjectsTab();                  // 卡不在本列表可见面（正常不达）⇒ 兜一次权威重拉
        }
      } catch (e) {
        window.__showToast?.(t('project.archiveFail', { name }), 'error');
      }
    },
    { tone: 'neutral' }
  );
}

/** 同标签页进入 Flow Map 视图：不新开标签页，当前 projects 标签页就地切换。
 *  pane 结构：.flowmap-nav-bar（左上角「← 项目名」单元素返回入口，点击回列表；
 *  右侧 .flowmap-summary 摘要槽——2026-09-06 顶栏合并批，两行并一行，摘要内容由
 *  flowMapTab.renderFlowMap/renderFlowMapDiff 按 pane 口径查找写入）
 *  + .flowmap-view-body（Flow Map 渲染体）。
 *  Flow Map 的 fetch/渲染/TTL 由 flowMapTab 负责（renderFlowMapInto），这里只管
 *  视图骨架与返回导航；body 挂 .flowmap-view-body 类供 flowMapTab 的 TTL ticker 定位。
 *  highlightNodeId：渲染完成后滚动定位并高亮该节点（任务列表节点条目点击跳转）。
 *  同项目视图已打开时走快速路径：不重建 pane DOM（轨道动画不被打断），仅重渲 + 高亮。 */
function openFlowMapInPlace(projectName, highlightNodeId, highlightChainId) {
  if (!projectName) return;
  openTab('projects', t('project.title'), { type: 'projects', closable: true });
  const pane = getTabPane('projects');
  if (!pane) return;
  const sameView = pane.dataset.projectsView === 'flow-map'
    && pane.dataset.flowMapProject === projectName;
  if (!sameView) {
    ensureFlowCss();
    pane.dataset.projectsView = 'flow-map';
    pane.dataset.flowMapProject = projectName;
    pane.innerHTML = `
      <div class="flowmap-nav-bar">
        <button class="flowmap-back-btn" data-back-to-projects type="button" title="${esc(projectName)}" aria-label="${esc(t('project.backToProjects'))}">
          <i data-lucide="arrow-left"></i><span class="flowmap-back-name">${esc(projectName)}</span>
        </button>
        <span class="flowmap-summary flowmap-nav-summary" aria-live="polite"></span>
      </div>
      <div class="flowmap-view-body" data-fm-project="${esc(projectName)}"></div>`;
    pane.querySelector('[data-back-to-projects]').addEventListener('click', (e) => {
      e.stopPropagation();
      showProjectsList();
    });
    import('./utils.js').then(({ createIconsIn }) => createIconsIn(pane));
  }
  ensureFlowCss(); // same-view 快速路径（如 tab 恢复后直接跳转）也要保证样式在
  renderFlowMapInto(pane.querySelector('.flowmap-view-body'), projectName,
    { highlightNodeId: highlightNodeId || '', highlightChainId: highlightChainId || '' });
}

/** 任务列表节点条目点击跳转入口（taskList.js 动态 import）：打开（或聚焦）某项目
 *  的 Flow Map 就地视图并高亮该节点。 */
export function openProjectFlowMapAt(projectName, nodeId) {
  openFlowMapInPlace(projectName, nodeId);
}

/** 任务列表链徽标点击入口（链级抽象 P1 · spec §7-B ⭐）：打开（或聚焦）该项目的
 *  Flow Map 就地视图并 fit 到该链（相机 bbox，见 flowMapTab.highlightFlowMapChain）
 *  ——与节点定位入口（openProjectFlowMapAt）对称，二者互不叠加。 */
export function openProjectFlowMapChain(projectName, chainId) {
  if (!projectName || !chainId) return;
  openTab('projects', t('project.title'), { type: 'projects', closable: true });
  const pane = getTabPane('projects');
  if (!pane) return;
  // 链定位随渲染入口下发（视图未打开时首渲完成即 fit 该链；已打开走快速路径重渲）——
  // 不在 openTab 后直接调 highlight（首渲是异步 fetch，那时卡还没入场）。
  openFlowMapInPlace(projectName, '', chainId);
}

/** 返回项目列表：重置视图状态并重渲列表（projects 标签页同页切换回列表视图）。 */
function showProjectsList() {
  const pane = getTabPane('projects');
  if (!pane) return;
  openProjectTab();
}

/** 供 test hook / 多标签页刷新。节点事件突发时防抖，避免每事件重拉全部项目+flowmap。
 *  视图分流：就地 Flow Map 视图刷新该项目的图（不闪回列表）；列表视图刷新卡片。 */
let projectsRenderTimer = null;
let renderSeq = 0;
/** 一次性重建闸：仅语言切换置位（卡内文案必须整体重写，定点刷新覆盖不到全部静态
 *  标签）；`reconcileProjectCards` 消费后立即清零 ⇒ 至多重建一轮。 */
let rebuildCardsOnce = false;
export function rerenderProjectsTab() {
  clearTimeout(projectsRenderTimer);
  projectsRenderTimer = setTimeout(() => {
    const pane = getTabPane('projects');
    if (!pane) return;
    if (pane.dataset.projectsView === 'flow-map' && pane.dataset.flowMapProject) {
      const body = pane.querySelector('.flowmap-view-body');
      if (body) renderFlowMapInto(body, pane.dataset.flowMapProject);
      return;
    }
    renderProjectsInto(ensureScroll(pane));
  }, 200);
}

// 标签页恢复（刷新/重启后 canvas.js 重建 pane 并派发 canvas-tab-restore）：
// 没有这条监听时，恢复出来的 projects pane 是个空壳——样式没注入、卡片没渲染，
// 且再点 #projects-btn 也救不回来（4 态机走 setActiveTab 分支，不会再调 openFn）。
window.addEventListener('canvas-tab-restore', (/** @type {CustomEvent} */ e) => {
  if (e.detail?.id === 'projects') openProjectTab();
});

// WS 事件驱动：列表视图的项目卡片（运行数/摘要）防抖刷新。Flow Map 就地视图的
// 节点事件刷新由 flowMapTab 的增量管线负责（事件 payload 直接 diff 渲染 + 过渡
// 动画）——这里若也全量重拉会覆盖它的 DOM、杀掉动画，故视图分流时跳过 flow-map。
// 本批起这里的「重拉」不再全量替换 DOM：`renderProjectsInto` 只做名单差集 + 定点增删
// （同名单 = 零 DOM 变更，仅摘要槽定点刷新），故事件驱动的刷新不会吃掉任何动画。
import { onMessage, onReconnect } from './ws.js';
function rerenderProjectsListView() {
  const pane = getTabPane('projects');
  if (!pane) return;
  if (pane.dataset.projectsView === 'flow-map') return;
  rerenderProjectsTab();
}
onMessage('nodeCreated', () => rerenderProjectsListView());
onMessage('nodeUpdated', () => rerenderProjectsListView());
onMessage('nodeCompleted', () => rerenderProjectsListView());
onMessage('nodeRemoved', () => rerenderProjectsListView());

// ── 项目级实时事件（方案 B · 裁定 (b)/(e)）──────────────────────────────────
// 后端两处 emit（NodeTools 创建腿 / RestApiRoutes 归档腿）经既有 wsHub 广播面全连接
// 下发；帧形 = projectCreated{project,row,mounted} / projectArchived{project,archivedAt}
// （载荷逐字 §D-2）。帧无 sessionId ⇒ 必须在 ws.js 的 GLOBAL_MSG_TYPES 内，否则
// 会被当成「无 sessionId 的会话内事件」而把 activeView 切到 primary（视图被抢走）。
// 处理原则：**只做定点增删，不触发全量重拉**（一次创建 = 一次定点插入；重拉归兜底 C），
// 故事件到达即刻起步动画，且不会与重拉竞争 DOM。面板不在列表视图（pane 不存在 /
// 就地 flow-map 视图 / 仍在 loading·error）时不动 DOM：空面板会在打开时现拉，
// loading/error 交给既有取数路径（`rerenderProjectsTab` 走防抖重拉）。

/** 列表视图的滚动体（面板没开 / 处于就地 Flow Map 视图 ⇒ null）。 */
function projectsListScroll() {
  const pane = getTabPane('projects');
  if (!pane || pane.dataset.projectsView === 'flow-map') return null;
  const scroll = /** @type {HTMLElement | null} */ (pane.querySelector('.team-scroll'));
  return scroll && scroll.isConnected ? scroll : null;
}

/** 事件行 → 卡片行（§D-2 `row` 与 GET /api/projects 行同构，五字段）。
 *  缺 row 时按 `project` 名兜底：仅名字可用也先立卡（摘要槽随后定点填充），
 *  避免「事件到了却因为字段缺失不立卡」这种静默丢帧。 */
function normalizeProjectRow(msg) {
  const name = msg?.project ?? msg?.row?.name;
  if (!name || typeof name !== 'string') return null;
  const row = msg?.row && typeof msg.row === 'object' ? msg.row : {};
  return {
    name,
    workspace: typeof row.workspace === 'string' ? row.workspace : '',
    description: typeof row.description === 'string' ? row.description : '',
  };
}

function onProjectCreatedFrame(msg) {
  const scroll = projectsListScroll();
  if (!scroll) return;
  const state = scroll.dataset.projectsState;
  if (state !== 'ready' && state !== 'empty') { rerenderProjectsTab(); return; }
  const row = normalizeProjectRow(msg);
  if (!row || cardByName(scroll, row.name)) return; // 幂等：已在列表 ⇒ 零 DOM 变更（无重复渲染）
  if (state === 'empty') { clearPlaceholders(scroll); scroll.dataset.projectsState = 'ready'; }
  insertProjectCard(scroll, row, stableCards(scroll).slice(-1)[0] || null); // 末尾插入（§F-1）
  scroll.dataset.projectCount = String(stableCards(scroll).length);
  announceProjects(scroll, t('project.createdAnnounce', { name: row.name }));
}

function onProjectArchivedFrame(msg) {
  const scroll = projectsListScroll();
  if (!scroll) return;
  const state = scroll.dataset.projectsState;
  if (state !== 'ready' && state !== 'empty') { rerenderProjectsTab(); return; }
  const name = typeof msg?.project === 'string' ? msg.project : '';
  if (!name) return;
  const card = cardByName(scroll, name);
  if (!card) return; // 已不在列表（含本页归档的回声 / 幂等重归档）⇒ no-op，无重复渲染
  startCardExit(scroll, card); // 退场动画（280ms）→ 定点 remove + FLIP 衔接邻卡
  announceProjects(scroll, t('project.archivedAnnounce', { name }));
}

onMessage('projectCreated', (msg) => onProjectCreatedFrame(msg));
onMessage('projectArchived', (msg) => onProjectArchivedFrame(msg));

// ── 兜底 C：低频重拉（裁定 (c) 同批）──────────────────────────────────────────
// 为什么必须有：进程外改动（有人直接编辑/删除 `~/.nebflow/projects/<name>/project.json`，
// 或另一个实例写的盘）**任何事件方案都覆盖不到**——全仓无文件 watcher。取证 §H-5 明确
// 声明此为不可覆盖面 ⇒ 事件面 + 低频重拉是唯一闭合方案。
// 频率与范式对齐 taskList.js:209（`setInterval(refreshLastActive, 60_000)`）。
// 门控（§D-3）：文档可见 ∧ projects 页签在前台（`.canvas-tab-pane.active`，split.css:643）
// ∧ 非 flow-map 就地视图 ∧ 面板已出列表（ready/empty；loading/error 由既有有界退避
// 重试 + onReconnect 腿负责，不在此叠加第二个重试源）。
// 与事件面不冲突：事件到达即刻定点增删、不等轮询；轮询仅在「名单与磁盘不一致」时
// 产生一次定点增删（同名单 ⇒ reconcile 零 DOM 变更，只定点刷新摘要槽）。
setInterval(() => {
  if (document.visibilityState !== 'visible') return;
  const pane = getTabPane('projects');
  if (!pane || !pane.classList.contains('active')) return;
  const scroll = projectsListScroll();
  if (!scroll) return;
  const state = scroll.dataset.projectsState;
  if (state !== 'ready' && state !== 'empty') return;
  renderProjectsInto(scroll);
}, PROJECTS_FALLBACK_POLL_MS);

// 切语言即重渲（contenti18n 批）：项目名/描述走 locale 映射（js/contentI18n.js），
// 语言一变列表卡片必须重渲——本批前此处零监听。复用上面的视图分流判据
// （rerenderProjectsListView：pane 不存在或正处就地 Flow Map 视图时不动）。
// 🔴 定点刷新覆盖不到卡内全部静态标签（工作区/AGENTS.md/归档 aria 等）⇒ 语言切换
// 是本文件**唯一**允许重建卡节点的路径（一次性标志，见 reconcileProjectCards）。
window.addEventListener('locale-changed', () => {
  rebuildCardsOnce = true;
  rerenderProjectsListView();
});

// 迟到对账通道 ②：WS 重连 = 代码里既有的「服务已回来」真信号（ws.js:416 逐个回调）。
// 面板此刻若不是 ready（error / loading / 空壳），立即补一次取数与渲染——冷启动期间
// 无人建节点，节点事件面天然为空，这条信号是本面板此前唯一缺的入口。
// 已 ready 的面板不重拉：重连抖动不该反复打 REST（列表权威刷新仍归上面的节点事件面）。
onReconnect(() => {
  const pane = getTabPane('projects');
  if (!pane) return;
  if (pane.dataset.projectsView === 'flow-map') return; // 视图分流同上面的节点事件面
  const scroll = /** @type {HTMLElement | null} */ (pane.querySelector('.team-scroll'));
  if (!scroll || !scroll.isConnected) return;
  if (scroll.dataset.projectsState === 'ready') return;
  resetProjectsRetry(); // 条件已变（连接恢复）⇒ 重试预算重置
  renderProjectsInto(scroll);
});
