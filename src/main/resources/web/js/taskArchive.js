// taskArchive.js — 任务档案视图(Canvas panel tab)
//
// 数据源: GET /api/nf-tasks(与 TaskQuery 工具同源)。条目按项目(folderName)
// 分组,组内按 completedAt/createdAt 倒序时间线。默认视图不含 dismissed
// (口径: dismissed 只是「不想再看到」,显式 status=dismissed 查询才含);
// failed 默认可见且带失败标注。
//
// 打开方式: openTaskArchive()——入口在 taskList.js header 右端
// .task-archive-btn(档案图标钮)。(todo-panel v1.1 起「今日完成」折叠条
// 已移除,档案是完成历史的唯一回看位。)
// 恢复: persistTabs 以 panel tab 持久化(absPath=null),reload 后
// canvas-tab-restore 事件触发重新拉取渲染。

import { openTab, getTabPane, hasTab, setActiveTab, openWorkspaceItem } from './canvas.js';
import { authHeaders } from './flowHelpers.js';
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';

const TAB_ID = 'task-archive';
const ALL = '__all__';

let entries = [];
let selectedFolder = ALL;
let loaded = false;

export async function openTaskArchive() {
  if (hasTab(TAB_ID)) setActiveTab(TAB_ID);
  else openTab(TAB_ID, t('task.archiveTitle'), { type: 'taskArchive', closable: true, pinned: true });
  await loadAndRender();
}

// Re-render when the tab is restored from localStorage after a reload.
window.addEventListener('canvas-tab-restore', (e) => {
  if (e.detail?.id === TAB_ID) loadAndRender();
});

async function loadAndRender() {
  const pane = getTabPane(TAB_ID);
  if (!pane) return;
  pane.innerHTML = `<div class="task-archive"><div class="task-archive-loading">${escapeHtml(t('chat.loading'))}</div></div>`;
  try {
    // 默认视图: 终态任务(completed+failed), 不含 dismissed。limit 放宽到 500
    // 覆盖长历史; 后续筛选/分页需求再加参数。
    const resp = await fetch('/api/nf-tasks?limit=500', { headers: authHeaders() });
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const data = await resp.json();
    const list = Array.isArray(data) ? data : (data.entries || data.tasks || []);
    // Defensive: dismissed never in the default view even if the backend
    // returns them (F8 口径).
    entries = list.filter(e => e && e.status !== 'dismissed');
    loaded = true;
  } catch (err) {
    entries = [];
    loaded = false;
    pane.innerHTML = `<div class="task-archive"><div class="task-archive-error">${escapeHtml(t('task.archiveError'))}: ${escapeHtml(err.message)}</div></div>`;
    return;
  }
  render(pane);
}

// ── Grouping & rendering ─────────────────────────────────

function groupByProject(list) {
  const map = new Map(); // folderName → entries[]
  for (const e of list) {
    const key = e.folderName || '';
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(e);
  }
  // Sort inside group: completedAt || createdAt desc
  for (const arr of map.values()) {
    arr.sort((a, b) => new Date(b.completedAt || b.createdAt || 0) - new Date(a.completedAt || a.createdAt || 0));
  }
  return map;
}

function fmtDayTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  const now = new Date();
  const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
  if (sameDay) return hm;
  const yday = new Date(now); yday.setDate(now.getDate() - 1);
  const isYday = d.getFullYear() === yday.getFullYear() && d.getMonth() === yday.getMonth() && d.getDate() === yday.getDate();
  if (isYday) return `${t('task.yesterday')} ${hm}`;
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hm}`;
}

const statusIcon = { completed: 'check', failed: 'x', in_progress: 'loader-2', pending: 'square' };

function entryHtml(e) {
  const status = e.status || 'completed';
  const icon = statusIcon[status] || 'check';
  const time = fmtDayTime(e.completedAt || e.createdAt);
  const notes = Array.isArray(e.notes) ? e.notes : [];
  const events = Array.isArray(e.events) ? e.events : [];
  const noteCount = e.noteCount ?? notes.length;

  let detail = '';
  if (notes.length > 0) {
    detail += `<div class="ta-notes">` + notes.map(n => {
      const links = Array.isArray(n.links) ? n.links : [];
      const linksHtml = links.map(l => {
        const isUrl = /^https?:\/\//.test(l);
        const label = isUrl ? l : (l.split('/').pop() || l);
        return `<span class="ta-link" role="button" tabindex="0" data-link="${escapeHtml(l)}" data-link-type="${isUrl ? 'url' : 'file'}" title="${escapeHtml(l)}">` +
          `<i data-lucide="${isUrl ? 'globe' : 'file-text'}"></i>${escapeHtml(label)}</span>`;
      }).join('');
      return `<div class="ta-note"><div class="ta-note-content">${escapeHtml(n.content || '')}</div>${linksHtml ? `<div class="ta-links">${linksHtml}</div>` : ''}</div>`;
    }).join('') + `</div>`;
  }
  if (events.length > 0) {
    detail += `<details class="ta-events"><summary>${escapeHtml(t('task.events'))} (${events.length})</summary>` +
      events.map(ev => `<div class="ta-event"><span class="ta-event-time">${escapeHtml(fmtDayTime(ev.at))}</span><span class="ta-event-kind">${escapeHtml(ev.kind || '')}</span><span class="ta-event-detail">${escapeHtml(ev.detail || '')}</span></div>`).join('') +
      `</details>`;
  }

  return `<div class="ta-entry ta-${escapeHtml(status)}" data-task="${escapeHtml(e.sessionId || '')}/${escapeHtml(String(e.taskId ?? e.id ?? ''))}">` +
    `<div class="ta-entry-main" role="button" tabindex="0">` +
      `<span class="ta-status"><i data-lucide="${icon}"></i></span>` +
      `<span class="ta-subject">${escapeHtml(e.subject || '')}</span>` +
      (noteCount > 0 && notes.length === 0 ? `<span class="ta-note-count">${noteCount} ${escapeHtml(t('task.notes'))}</span>` : '') +
      `<span class="ta-time">${escapeHtml(time)}</span>` +
    `</div>` +
    (detail ? `<div class="ta-detail">${detail}</div>` : '') +
    `</div>`;
}

function render(pane) {
  pane = pane || getTabPane(TAB_ID);
  if (!pane) return;

  if (!loaded || entries.length === 0) {
    pane.innerHTML = `<div class="task-archive"><div class="task-archive-empty">${escapeHtml(t('task.archiveEmpty'))}</div></div>`;
    return;
  }

  const groups = groupByProject(entries);
  const folderNames = [...groups.keys()].sort((a, b) => a.localeCompare(b));

  // Left project rail
  let rail = `<div class="ta-rail-item ${selectedFolder === ALL ? 'active' : ''}" data-folder="${ALL}">${escapeHtml(t('task.allProjects'))}<span class="ta-rail-count">${entries.length}</span></div>`;
  for (const name of folderNames) {
    const label = name || t('task.uncategorized');
    rail += `<div class="ta-rail-item ${selectedFolder === name ? 'active' : ''}" data-folder="${escapeHtml(name)}">${escapeHtml(label)}<span class="ta-rail-count">${groups.get(name).length}</span></div>`;
  }

  // Right timeline
  const visible = selectedFolder === ALL ? entries.slice().sort((a, b) => new Date(b.completedAt || b.createdAt || 0) - new Date(a.completedAt || a.createdAt || 0)) : (groups.get(selectedFolder) || []);
  let timeline;
  if (selectedFolder === ALL) {
    // Grouped display: project header + entries, groups ordered by latest activity
    const ordered = folderNames
      .map(name => ({ name, arr: groups.get(name), latest: new Date(groups.get(name)[0]?.completedAt || groups.get(name)[0]?.createdAt || 0) }))
      .sort((a, b) => b.latest - a.latest);
    timeline = ordered.map(g =>
      `<div class="ta-group"><div class="ta-group-title">${escapeHtml(g.name || t('task.uncategorized'))}</div>` +
      g.arr.map(entryHtml).join('') + `</div>`
    ).join('');
  } else {
    timeline = `<div class="ta-group">${visible.map(entryHtml).join('')}</div>`;
  }

  pane.innerHTML = `<div class="task-archive">` +
    `<div class="ta-rail">${rail}</div>` +
    `<div class="ta-timeline">${timeline}</div>` +
    `</div>`;

  if (typeof lucide !== 'undefined') createIconsIn(pane);
  bindEvents(pane);
}

function bindEvents(pane) {
  pane.querySelectorAll('.ta-rail-item').forEach(el => {
    el.addEventListener('click', () => {
      selectedFolder = el.dataset.folder;
      render(pane);
    });
  });

  pane.querySelectorAll('.ta-entry-main').forEach(el => {
    el.addEventListener('click', () => {
      el.closest('.ta-entry').classList.toggle('expanded');
    });
  });

  pane.querySelectorAll('.ta-link').forEach(el => {
    const open = (e) => {
      e.stopPropagation();
      const link = el.dataset.link;
      if (el.dataset.linkType === 'url') {
        openWorkspaceItem({ id: 'url:' + link, itemType: 'url', title: link, url: link, pinned: true });
      } else {
        // File path → popFile 链路: absPath-only, canvas fetches content itself
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: { id: 'file:' + link, title: link.split('/').pop() || link, itemType: '', content: '', absPath: link, pinned: true }
        }));
      }
    };
    el.addEventListener('click', open);
    el.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') open(e); });
  });
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str == null ? '' : String(str);
  return div.innerHTML;
}
