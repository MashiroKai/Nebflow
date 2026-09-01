// projectTab.js — Project 标签页（#27 方向调整：项目入口以标签页呈现，参考 team 面板设计）。
// 点击侧边栏 Project 按钮 → 打开 `projects` Canvas 标签页；一行/卡片一个 project，
// 沿用 .team-card 视觉与交互范式。点击 project name → 打开其 Flow Map 标签页；
// Agent.md 入口 → rules 式查看/编辑 overlay。

import { openTab, getTabPane } from './canvas.js';
import { ensureFlowCss } from './flowCss.js';
import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { fetchProjects, fetchFlowMap, summarize } from './nodeData.js';
import { openFlowMapTab } from './flowMapTab.js';
import { openAgentFile } from './agentFileViewer.js';

function openProjectTab() {
  const pane = getTabPane('projects');
  if (!pane) return;
  ensureFlowCss();
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
  return `
    <div class="team-card project-card" data-project="${esc(p.name)}" data-running="${running}">
      <div class="team-card-header">
        <div class="team-card-title" data-open-flowmap="${esc(p.name)}" title="${esc(t('project.openFlowMap', { name: p.name }))}">${esc(p.name)}</div>
        <div class="team-card-summary ${summaryCls}"><span class="dot"></span>${esc(brief)}</div>
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
      openFlowMapTab(el.getAttribute('data-open-flowmap') || '');
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
}

/** 供 test hook / 多标签页刷新。节点事件突发时防抖，避免每事件重拉全部项目+flowmap。 */
let projectsRenderTimer = null;
let renderSeq = 0;
export function rerenderProjectsTab() {
  clearTimeout(projectsRenderTimer);
  projectsRenderTimer = setTimeout(() => {
    const pane = getTabPane('projects');
    if (pane) renderProjectsInto(ensureScroll(pane));
  }, 200);
}

// 标签页恢复（刷新/重启后 canvas.js 重建 pane 并派发 canvas-tab-restore）：
// 没有这条监听时，恢复出来的 projects pane 是个空壳——样式没注入、卡片没渲染，
// 且再点 #projects-btn 也救不回来（4 态机走 setActiveTab 分支，不会再调 openFn）。
window.addEventListener('canvas-tab-restore', (/** @type {CustomEvent} */ e) => {
  if (e.detail?.id === 'projects') openProjectTab();
});

// WS 事件驱动：项目运行数变化时若 Project 标签页打开则刷新（契约后）。
import { onMessage } from './ws.js';
onMessage('nodeCreated', () => rerenderProjectsTab());
onMessage('nodeUpdated', () => rerenderProjectsTab());
onMessage('nodeCompleted', () => rerenderProjectsTab());
onMessage('nodeRemoved', () => rerenderProjectsTab());
