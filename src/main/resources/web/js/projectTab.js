// projectTab.js — Project 标签页（#27 方向调整：项目入口以标签页呈现，参考 team 面板设计）。
// 点击侧边栏 Project 按钮 → 打开 `projects` Canvas 标签页；一行/卡片一个 project，
// 沿用 .team-card 视觉与交互范式。点击 project name → 打开其 Flow Map 标签页；
// Agent.md 入口 → rules 式查看/编辑 overlay。

import { openTab, getTabPane } from './canvas.js';
import { FLOW_CSS } from './flowCss.js';
import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { fetchProjects, fetchFlowMap, summarize } from './nodeData.js';
import { openFlowMapTab } from './flowMapTab.js';
import { openAgentFile } from './agentFileViewer.js';

let projectsLoaded = false;

function openProjectTab() {
  const pane = getTabPane('projects');
  if (!pane) return;
  if (!pane.querySelector('#team-canvas-style')) {
    pane.insertAdjacentHTML('afterbegin', FLOW_CSS);
  }
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
  scroll.innerHTML = `<div class="flowmap-loading">${esc(t('project.loading'))}</div>`;
  const projects = await fetchProjects();
  if (!projects || projects.length === 0) {
    scroll.innerHTML = `<div class="team-empty">
      <div style="font:600 14px -apple-system;color:var(--color-text-muted)">${esc(t('project.empty'))}</div>
      <div class="hint">${esc(t('project.emptyHint'))}</div>
    </div>`;
    return;
  }
  const summaries = await Promise.all(projects.map(async (p) => summarize(await fetchFlowMap(p.name))));
  scroll.innerHTML = projects.map((p, i) => projectCardHtml(p, summaries[i])).join('');
  bindProjectClicks(scroll);
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(scroll));
}

function projectCardHtml(p, summary) {
  const running = summary?.running || 0;
  const brief = summary?.brief || t('project.idle');
  const summaryCls = running > 0 ? 'running' : '';
  return `
    <div class="team-card project-card" data-project="${esc(p.name)}">
      <div class="team-card-header">
        <div class="team-card-title" data-open-flowmap="${esc(p.name)}" title="${esc(t('project.openFlowMap', { name: p.name }))}">${esc(p.name)}</div>
        <div class="team-card-actions">
          <button class="team-act-btn" data-open-agent="${esc(p.name)}" title="${esc(t('project.agentFile'))}">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="9" y1="13" x2="15" y2="13"/><line x1="9" y1="17" x2="15" y2="17"/></svg>
          </button>
        </div>
        <div class="team-card-summary ${summaryCls}"><span class="dot"></span>${esc(brief)}</div>
      </div>
      <div class="project-fields">
        <div class="project-field" title="${esc(p.workspace)}">
          <span class="project-field-label">${esc(t('project.workspace'))}</span>
          <span class="project-field-value mono">${esc(p.workspace)}</span>
          <button class="project-open-btn" data-open-workspace="${esc(p.name)}" title="${esc(t('project.openWorkspace'))}" aria-label="${esc(t('project.openWorkspace'))}"><i data-lucide="folder-open"></i></button>
        </div>
        ${p.description ? `<div class="project-field"><span class="project-field-value">${esc(p.description)}</span></div>` : ''}
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
      // 契约未定：先 toast 占位（§3.5 字段已渲染）。
      const name = el.getAttribute('data-open-workspace') || '';
      window.__showToast?.(t('project.openWorkspaceSoon', { name }), 'info');
    });
  });
}

/** 供 test hook / 多标签页刷新。节点事件突发时防抖，避免每事件重拉全部项目+flowmap。 */
let projectsRenderTimer = null;
export function rerenderProjectsTab() {
  clearTimeout(projectsRenderTimer);
  projectsRenderTimer = setTimeout(() => {
    const pane = getTabPane('projects');
    if (pane) renderProjectsInto(ensureScroll(pane));
  }, 200);
}

// WS 事件驱动：项目运行数变化时若 Project 标签页打开则刷新（契约后）。
import { onMessage } from './ws.js';
onMessage('nodeCreated', () => rerenderProjectsTab());
onMessage('nodeUpdated', () => rerenderProjectsTab());
onMessage('nodeCompleted', () => rerenderProjectsTab());
onMessage('nodeRemoved', () => rerenderProjectsTab());
