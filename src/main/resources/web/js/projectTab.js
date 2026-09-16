// projectTab.js — Project 标签页（#27 方向调整：项目入口以标签页呈现，参考 team 面板设计）。
// 点击侧边栏 Project 按钮 → 打开 `projects` Canvas 标签页；一行/卡片一个 project，
// 沿用 .team-card 视觉与交互范式。点击 project name → 同一标签页就地切换到其
// Flow Map 视图（不新开标签页），左上角「返回项目列表」切回列表；
// 空 Flow Map（无节点）的项目 title 不可点。AGENTS.md 入口 → rules 式查看/编辑 overlay。

import { openTab, getTabPane } from './canvas.js';
import { ensureFlowCss } from './flowCss.js';
import { esc, authHeaders } from './flowHelpers.js';
import { t } from './i18n.js';
import { contentText } from './contentI18n.js';
import { fetchProjects, fetchFlowMap, summarize, API } from './nodeData.js';
import { renderFlowMapInto } from './flowMapTab.js';
import { openAgentFile } from './agentFileViewer.js';

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

function ensureScroll(pane) {
  let scroll = pane.querySelector('.team-scroll');
  if (!scroll) {
    pane.innerHTML = '';
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    pane.appendChild(scroll);
  }
  return scroll;
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
    // 静默失败（旧行为：await 抛出 → 停在 loading，卡片数 0 且无任何提示）
    // 改为显式错误态，可诊断、可断言。
    scroll.dataset.projectsState = 'error';
    scroll.innerHTML = `<div class="team-empty">
      <div style="font:600 14px -apple-system;color:var(--color-text-muted)">${esc(t('project.loadFail'))}</div>
      <div class="hint">${esc(e?.message || '')}</div>
    </div>`;
    // 迟到对账通道 ①：服务/网络恢复后自己把列表补上（不等用户动作、不等 WS）。
    scheduleProjectsRetry(scroll);
    return;
  }
  if (stale()) return;
  resetProjectsRetry(); // 取数成功（无论空态/就绪态）⇒ 本轮重试预算归零
  if (!projects || projects.length === 0) {
    scroll.dataset.projectsState = 'empty';
    scroll.dataset.projectCount = '0';
    // 空态「一键引导」（作者卡 2026-09-16 裁定：方向 B · 行为 = 仅预填）：
    // 既有两行文案不动，其下补 1 枚 CTA + 1 行披露注（点击只预填、不发送）。
    // 材质一行不写——按现行单源 `.glass-control` 取玻璃质感（sapphire.css:147 基类 +
    // `button.glass-control` 四态）；本分支只贡献结构，样式在 flowCss.js 限布局/排版。
    scroll.innerHTML = `<div class="team-empty">
      <div style="font:600 14px -apple-system;color:var(--color-text-muted)">${esc(t('project.empty'))}</div>
      <div class="hint">${esc(t('project.emptyHint'))}</div>
      <button class="glass-control team-empty-cta" type="button" data-projects-empty-cta="1"><i data-lucide="message-circle"></i>${esc(t('project.emptyCta'))}</button>
      <div class="hint team-empty-cta-note">${esc(t('project.emptyCtaHint'))}</div>
    </div>`;
    bindEmptyCta(scroll);
    import('./utils.js').then(({ createIconsIn }) => createIconsIn(scroll));
    return;
  }
  // 摘要逐项容错：单个项目的 flow-map 失败（未挂载/500）不得连累整列卡片
  // （旧 Promise.all 一拒全拒 → 一个项目出错整页停在 loading）。
  const summaries = await Promise.all(projects.map(async (p) => {
    try { return summarize(await fetchFlowMap(p.name)); }
    catch (_) { return null; }
  }));
  if (stale()) return;
  scroll.dataset.projectsState = 'ready';
  scroll.dataset.projectCount = String(projects.length);
  scroll.innerHTML = projects.map((p, i) => projectCardHtml(p, summaries[i])).join('');
  bindProjectClicks(scroll);
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(scroll));
}

function projectCardHtml(p, summary) {
  const running = summary?.running || 0;
  const brief = summary?.notMounted ? t('project.notMounted') : (summary?.brief || t('project.idle'));
  const summaryCls = running > 0 ? 'running' : '';
  // 无节点 → 点进 Flow Map 无意义：title 不可点（无 data-open-flowmap）、灰显 + 「暂无节点」角标。
  // summary 为 null = flow-map 拉取失败（状态未知）→ 保持可点，让用户进视图看错误态；
  // 只有确认无节点（total === 0，含未挂载）才禁用。
  const empty = summary !== null && (summary?.total ?? 0) === 0;
  const titleOpenAttr = empty ? '' : ` data-open-flowmap="${esc(p.name)}"`;
  const titleTooltip = empty ? t('project.noNodesHint', { name: p.name }) : t('project.openFlowMap', { name: p.name });
  return `
    <div class="team-card project-card${empty ? ' is-empty' : ''}" data-project="${esc(p.name)}" data-running="${running}" ${empty ? 'data-empty-flowmap="1"' : ''}>
      <div class="team-card-header">
        <div class="team-card-title${empty ? ' empty' : ''}"${titleOpenAttr} title="${esc(titleTooltip)}">${esc(contentText('project', p.name, 'name', p.name))}${empty ? `<span class="project-empty-tag">${esc(t('project.noNodes'))}</span>` : ''}</div>
        <div class="team-card-summary ${summaryCls}"><span class="dot"></span>${esc(brief)}</div>
        <button class="project-archive-btn" data-archive-project="${esc(p.name)}" title="${esc(t('project.archive'))}" aria-label="${esc(t('project.archiveTitle', { name: p.name }))}"><i data-lucide="archive"></i></button>
      </div>
      <div class="project-fields">
        <div class="project-field" title="${esc(p.workspace)}">
          <span class="project-field-label">${esc(t('project.workspace'))}</span>
          <span class="project-field-value mono">${esc(p.workspace)}</span>
          <button class="project-open-btn" data-open-workspace="${esc(p.workspace || '')}" title="${esc(t('project.openWorkspace'))}" aria-label="${esc(t('project.openWorkspace'))}"><i data-lucide="folder-open"></i></button>
        </div>
        <div class="project-field">
          <span class="project-field-label">${esc(t('project.agentFileLabel'))}</span>
          <button class="project-link-btn" data-open-agent="${esc(p.name)}">${esc(t('project.agentFileOpen'))}</button>
        </div>
        <div class="project-field">
          <span class="project-field-label">${esc(t('project.runningAgents'))}</span>
          <span class="project-field-value tabular" data-running-count="${esc(p.name)}">${running}</span>
        </div>
        ${p.description ? `<div class="project-field"><span class="project-field-value desc">${esc(contentText('project', p.name, 'desc', p.description))}</span></div>` : ''}
      </div>
    </div>`;
}

function bindProjectClicks(scroll) {
  scroll.querySelectorAll('[data-open-flowmap]').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      // 同标签页切换：不开新标签页，当前 projects 标签页就地显示 Flow Map 视图
      // （左上角「返回项目列表」回到列表视图）。
      openFlowMapInPlace(el.getAttribute('data-open-flowmap') || '');
    });
  });
  scroll.querySelectorAll('[data-open-agent]').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      openAgentFile(el.getAttribute('data-open-agent') || '');
    });
  });
  scroll.querySelectorAll('[data-open-workspace]').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      // §3.5「在文件浏览器中打开」——把侧边栏文件浏览器的根切到工作区并展开面板。
      // 动态 import：projectTab 是 Canvas 标签页模块，静态依赖 explorer 会把
      // 文件树拉进项目面板的首屏加载路径（且 explorer → activityBar → ... 更容易
      // 绕出循环依赖）。
      const path = el.getAttribute('data-open-workspace') || '';
      if (!path) return;
      import('./explorer.js').then(({ openExplorerAt }) => {
        openExplorerAt(path);
        window.__showToast?.(t('project.workspaceOpened', { path }), 'info');
      }).catch(() => window.__showToast?.(t('project.openWorkspaceFail'), 'error'));
    });
  });
  scroll.querySelectorAll('[data-archive-project]').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      archiveProject(el.getAttribute('data-archive-project') || '');
    });
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

/** 绑定空态 CTA 的点击（单枚按钮，就地绑定，与 bindProjectClicks 同款 querySelectorAll 范式）。 */
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
 *  列表重渲（后端 ProjectStore.list 源头过滤归档项，卡片即时消失，无需刷新页面，
 *  对齐 WS 事件驱动的 rerenderProjectsTab 既有刷新机制）。
 *  零删除零移动：仅 project.json 打归档标记，workspace 与定义文件全部保留。
 *  tone:'neutral'——归档可逆（可取消归档），确认钮不用 danger 红。 */
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
        rerenderProjectsTab();
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

// 切语言即重渲（contenti18n 批）：项目名/描述走 locale 映射（js/contentI18n.js），
// 语言一变列表卡片必须重渲——本批前此处零监听。复用上面的视图分流判据
// （rerenderProjectsListView：pane 不存在或正处就地 Flow Map 视图时不动）。
window.addEventListener('locale-changed', () => rerenderProjectsListView());

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
