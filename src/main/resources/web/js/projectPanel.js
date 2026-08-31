// projectPanel.js — 侧边栏 Project 面板（§3.5）。
// 试点期与旧 Team/Flow 面板并列；一行一个 project，点击 name 进入 Flow Map 视图。
// 字段：project name / 工作区+「在文件浏览器中打开」/ Agent.md 入口 / 描述 /
//       当前后台运行 agent 数 / 简要状态。

import { t } from './i18n.js';
import { esc } from './flowHelpers.js';
import { fetchProjects, fetchFlowMap, summarize } from './nodeData.js';

const panel = () => document.getElementById('panel-projects');
const list = () => document.getElementById('project-list');

/** 打开某项目在文件浏览器中的工作区（契约未定，先 toast 占位）。 */
async function openWorkspace(name) {
  // 契约后：fetch(API.openWorkspace(name), { method: 'POST' })
  window.__showToast?.(t('project.openWorkspaceSoon', { name }), 'info');
}

/** 打开某项目 Agent.md（契约未定，先 toast 占位）。 */
async function openAgentFile(name) {
  // 契约后：fetch(API.agentFile(name)) → 渲染到 Canvas
  window.__showToast?.(t('project.agentFileSoon', { name }), 'info');
}

function rowHtml(p, summary) {
  const runningCount = summary?.running || 0;
  const brief = summary?.brief || t('project.idle');
  const openBtn = `<button class="project-open-btn" data-open-workspace="${esc(p.name)}" title="${esc(t('project.openWorkspace'))}" aria-label="${esc(t('project.openWorkspace'))}"><i data-lucide="folder-open"></i></button>`;
  const agentEntry = `<button class="project-agent-btn" data-open-agent="${esc(p.name)}" title="${esc(t('project.agentFile'))}">Agent.md</button>`;
  return `
    <div class="project-row" data-project="${esc(p.name)}">
      <div class="project-row-head">
        <button class="project-name" data-open-flowmap="${esc(p.name)}" aria-label="${esc(t('project.openFlowMap', { name: p.name }))}">
          <span class="project-name-text">${esc(p.name)}</span>
          <span class="project-running-badge ${runningCount > 0 ? 'on' : ''}" title="${esc(t('project.runningCount'))}">${runningCount}</span>
        </button>
        ${openBtn}
      </div>
      <div class="project-workspace" title="${esc(p.workspace)}">
        <i data-lucide="folder"></i><span class="project-workspace-path">${esc(p.workspace)}</span>
      </div>
      <div class="project-meta">
        ${agentEntry}
        <span class="project-status">${esc(brief)}</span>
      </div>
      ${p.description ? `<div class="project-desc">${esc(p.description)}</div>` : ''}
    </div>`;
}

function emptyHtml() {
  return `<div class="project-empty"><div class="project-empty-main">${esc(t('project.empty'))}</div><div class="project-empty-hint">${esc(t('project.emptyHint'))}</div></div>`;
}

/** 渲染项目列表（含每个项目的运行计数/状态摘要）。 */
export async function renderProjects() {
  const el = list();
  if (!el) return;
  const projects = await fetchProjects();

  if (!projects || projects.length === 0) {
    el.innerHTML = emptyHtml();
    return;
  }

  // 逐项目并取 flow-map 摘要（mock 会快；契约后可并行 fetch 汇总）
  const summaries = await Promise.all(
    projects.map(async (p) => summarize(await fetchFlowMap(p.name)))
  );

  el.innerHTML = projects.map((p, i) => rowHtml(p, summaries[i])).join('');
  createIconsIn(el);
  bindPanelClicks();
}

function bindPanelClicks() {
  list()?.querySelectorAll('[data-open-flowmap]').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const name = btn.getAttribute('data-open-flowmap');
      if (!name) return;
      // 页面内导航进入 Flow Map 视图（flowMapView.js 监听）
      window.dispatchEvent(new CustomEvent('flowmap-open', { detail: { project: name } }));
    });
  });
  list()?.querySelectorAll('[data-open-workspace]').forEach((btn) => {
    btn.addEventListener('click', (e) => { e.stopPropagation(); openWorkspace(btn.getAttribute('data-open-workspace') || ''); });
  });
  list()?.querySelectorAll('[data-open-agent]').forEach((btn) => {
    btn.addEventListener('click', (e) => { e.stopPropagation(); openAgentFile(btn.getAttribute('data-open-agent') || ''); });
  });
}

// 从 utils 引入（避免循环依赖，延迟 lazy import）
function createIconsIn(root) {
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(root));
}

// 面板激活时刷新（进入面板即重新拉取，反映最新运行数）
export function initProjectPanel() {
  renderProjects();
  // 面板切换时（active 恢复）重刷
  window.addEventListener('panel-shown', (/** @type {CustomEvent} */ e) => {
    if (e.detail?.panel === 'projects') renderProjects();
  });
}
