// projectTab.js — Project 标签页（#27 方向调整：项目入口以标签页呈现，参考 team 面板设计）。
// 点击侧边栏 Project 按钮 → 打开 `projects` Canvas 标签页；一行/卡片一个 project，
// 沿用 .team-card 视觉与交互范式。点击 project name → 同一标签页就地切换到其
// Flow Map 视图（不新开标签页），左上角「返回项目列表」切回列表；
// 空 Flow Map（无节点）的项目 title 不可点。AGENTS.md 入口 → rules 式查看/编辑 overlay。

import { openTab, getTabPane } from './canvas.js';
import { ensureFlowCss } from './flowCss.js';
import { esc, authHeaders } from './flowHelpers.js';
import { t } from './i18n.js';
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
    return;
  }
  if (stale()) return;
  if (!projects || projects.length === 0) {
    scroll.dataset.projectsState = 'empty';
    scroll.dataset.projectCount = '0';
    scroll.innerHTML = `<div class="team-empty">
      <div style="font:600 14px -apple-system;color:var(--color-text-muted)">${esc(t('project.empty'))}</div>
      <div class="hint">${esc(t('project.emptyHint'))}</div>
    </div>`;
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
        <div class="team-card-title${empty ? ' empty' : ''}"${titleOpenAttr} title="${esc(titleTooltip)}">${esc(p.name)}${empty ? `<span class="project-empty-tag">${esc(t('project.noNodes'))}</span>` : ''}</div>
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
        ${p.description ? `<div class="project-field"><span class="project-field-value desc">${esc(p.description)}</span></div>` : ''}
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

/** 归档项目（迁移方案 v2 §6.1）：显式人工动作——确认弹层（防误触，复用
 *  window.__showConfirm 既有确认范式）→ POST /api/projects/<name>/archive →
 *  列表重渲（后端 ProjectStore.list 源头过滤归档项，卡片即时消失，无需刷新页面，
 *  对齐 WS 事件驱动的 rerenderProjectsTab 既有刷新机制）。
 *  零删除零移动：仅 project.json 打归档标记，workspace 与定义文件全部保留。 */
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
    }
  );
}

/** 同标签页进入 Flow Map 视图：不新开标签页，当前 projects 标签页就地切换。
 *  pane 结构：.flowmap-nav-bar（左上角返回按钮）+ .flowmap-view-body（Flow Map 渲染体）。
 *  Flow Map 的 fetch/渲染/TTL 由 flowMapTab 负责（renderFlowMapInto），这里只管
 *  视图骨架与返回导航；body 挂 .flowmap-view-body 类供 flowMapTab 的 TTL ticker 定位。
 *  highlightNodeId：渲染完成后滚动定位并高亮该节点（任务列表节点条目点击跳转）。
 *  同项目视图已打开时走快速路径：不重建 pane DOM（轨道动画不被打断），仅重渲 + 高亮。 */
function openFlowMapInPlace(projectName, highlightNodeId) {
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
        <button class="flowmap-back-btn" data-back-to-projects type="button" title="${esc(t('project.backToProjects'))}" aria-label="${esc(t('project.backToProjects'))}">
          <i data-lucide="arrow-left"></i><span>${esc(t('project.backToProjects'))}</span>
        </button>
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
    { highlightNodeId: highlightNodeId || '' });
}

/** 任务列表节点条目点击跳转入口（taskList.js 动态 import）：打开（或聚焦）某项目
 *  的 Flow Map 就地视图并高亮该节点。 */
export function openProjectFlowMapAt(projectName, nodeId) {
  openFlowMapInPlace(projectName, nodeId);
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
import { onMessage } from './ws.js';
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
