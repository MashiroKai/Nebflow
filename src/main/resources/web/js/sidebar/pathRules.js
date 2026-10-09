// sidebar.js 拆分(FE组件化批次二 2026-09-27):Rules + PathPicker 模态 可复用模块(行为保持)。
// Rules Modal wiring + Path Picker Modal 两段(sidebar.js 原 :3554-3769)。
// moveSessionToFolder(原 :3771)留守主体,不属本簇。
import state from '../state.js';
import { sendWs } from '../ws.js';
import { t } from '../i18n.js';
import { escapeHtml } from './providersUi.js';

// 主体侧回调注入(拆分批次二纪律):会话侧栏段(renderSessionSidebar)留守 sidebar.js,
// 子模块不得反向 import 之,handleRulesSaved/handleRulesDeleted 的重渲由主体在模块
// 求值时注入(见 sidebar.js 的 injectRenderSessionSidebar 调用点)。
let renderSessionSidebar = null;
function injectRenderSessionSidebar(fn) { renderSessionSidebar = fn; }

/** Open rules editor modal for a folder — Nebflow standard modal. */
let activeRulesFolderId = null;
let activeRulesExists = false;
function openRulesEditor(folderId, folderName) {
  activeRulesFolderId = folderId;
  activeRulesExists = state.foldersWithRules && state.foldersWithRules.has(folderId);
  const title = document.getElementById('rules-modal-title');
  const input = document.getElementById('rules-input');
  const deleteBtn = document.getElementById('rules-modal-delete');
  title.textContent = t('rules.title') + ' — ' + folderName;
  input.value = '';
  input.placeholder = t('rules.placeholder');
  deleteBtn.style.display = activeRulesExists ? 'inline-block' : 'none';
  deleteBtn.textContent = t('rules.delete');
  document.getElementById('rules-overlay').classList.add('on');
  document.getElementById('rules-modal').classList.add('show');
  // Fetch current content
  sendWs({ type: 'getRules', folderId });
  setTimeout(() => input.focus(), 50);
}

function closeRulesEditor() {
  activeRulesFolderId = null;
  document.getElementById('rules-overlay').classList.remove('on');
  document.getElementById('rules-modal').classList.remove('show');
}

/** Handle rulesData from server. */
export function handleRulesData(data) {
  if (activeRulesFolderId === data.folderId) {
    const input = document.getElementById('rules-input');
    if (input) input.value = data.content || '';
  }
}

/** Handle rulesSaved from server. */
export function handleRulesSaved(data) {
  if (data.folderId) {
    if (!state.foldersWithRules) state.foldersWithRules = new Set();
    state.foldersWithRules.add(data.folderId);
    // Re-render sidebar to show rules.md entry
    const activeId = state.activeSessionId;
    renderSessionSidebar(state.sessions, activeId);
    closeRulesEditor();
  }
}

/** Handle rulesDeleted from server. */
export function handleRulesDeleted(data) {
  if (data.folderId) {
    if (state.foldersWithRules) state.foldersWithRules.delete(data.folderId);
    const activeId = state.activeSessionId;
    renderSessionSidebar(state.sessions, activeId);
    closeRulesEditor();
  }
}

// ----- Rules Modal wiring (called from modal.js initModals) -----
export function initRulesModal() {
  document.getElementById('rules-modal-cancel').addEventListener('click', closeRulesEditor);
  document.getElementById('rules-overlay').addEventListener('click', (e) => {
    if (e.target.id === 'rules-overlay') closeRulesEditor();
  });
  document.getElementById('rules-modal-save').addEventListener('click', () => {
    const content = document.getElementById('rules-input').value;
    sendWs({ type: 'saveRules', folderId: activeRulesFolderId, content });
  });
  document.getElementById('rules-modal-delete').addEventListener('click', () => {
    if (!activeRulesFolderId) return;
    sendWs({ type: 'deleteRules', folderId: activeRulesFolderId });
  });
}

// ===== Path Picker Modal =====
let pathPickerFolderId = null;
let pathPickerCurrentPath = '';
let pathPickerCallback = null;

export function openPathPicker(folderId, currentRoot) {
  pathPickerFolderId = folderId;
  pathPickerCallback = null;
  const startPath = currentRoot || '~';
  document.getElementById('path-picker-title').textContent = t('pathPicker.title');
  document.getElementById('path-picker-cancel').textContent = t('modal.cancel');
  document.getElementById('path-picker-clear').textContent = t('pathPicker.clear');
  document.getElementById('path-picker-select').textContent = t('pathPicker.select');
  // Show/hide clear button based on current state
  document.getElementById('path-picker-clear').style.display = currentRoot ? 'inline-block' : 'none';
  document.getElementById('path-picker-overlay').classList.add('on');
  document.getElementById('path-picker-modal').classList.add('show');
  browseTo(startPath);
}

export function openPathPickerCallback(currentRoot, callback) {
  pathPickerFolderId = null;
  pathPickerCallback = callback;
  const startPath = currentRoot || '~';
  document.getElementById('path-picker-title').textContent = t('pathPicker.title');
  document.getElementById('path-picker-cancel').textContent = t('modal.cancel');
  document.getElementById('path-picker-select').textContent = t('pathPicker.select');
  document.getElementById('path-picker-clear').style.display = 'none';
  document.getElementById('path-picker-overlay').classList.add('on');
  document.getElementById('path-picker-modal').classList.add('show');
  browseTo(startPath);
}

function closePathPicker() {
  pathPickerFolderId = null;
  pathPickerCallback = null;
  pathPickerCurrentPath = '';
  document.getElementById('path-picker-overlay').classList.remove('on');
  document.getElementById('path-picker-modal').classList.remove('show');
}

function browseTo(path) {
  sendWs({ type: 'browsePath', path });
}

function buildBreadcrumb(path) {
  const bc = document.getElementById('path-picker-breadcrumb');
  const parts = path.split('/').filter(Boolean);
  let html = '';
  let accumulated = '';
  // root
  if (path.startsWith('/')) {
    html += '<span data-path="/">/</span>';
    accumulated = '/';
  }
  parts.forEach((part, i) => {
    accumulated += (accumulated.endsWith('/') ? '' : '/') + part;
    const p = accumulated;
    if (i < parts.length - 1) {
      html += ' / <span data-path="' + escapeHtml(p) + '">' + escapeHtml(part) + '</span>';
    } else {
      html += ' / ' + escapeHtml(part);
    }
  });
  bc.innerHTML = html;
  bc.querySelectorAll('span[data-path]').forEach(span => {
    span.addEventListener('click', () => browseTo(span.dataset.path));
  });
}

export function handleBrowseResult(data) {
  const list = document.getElementById('path-picker-list');
  if (!list) return;
  pathPickerCurrentPath = data.path || '';
  buildBreadcrumb(pathPickerCurrentPath);
  const entries = data.entries || [];
  list.innerHTML = '';
  list.removeAttribute('data-empty');
  if (data.error) {
    list.setAttribute('data-empty', data.error);
    return;
  }
  // Parent directory item — hidden at filesystem root
  if (pathPickerCurrentPath !== '/') {
    const parentItem = document.createElement('div');
    parentItem.className = 'pp-item';
    parentItem.innerHTML = '<span class="pp-icon">..</span><span class="pp-name">..</span>';
    parentItem.addEventListener('click', () => {
      const parts = pathPickerCurrentPath.replace(/\/$/, '').split('/');
      parts.pop();
      const parent = parts.join('/') || '/';
      browseTo(parent);
    });
    list.appendChild(parentItem);
  }

  if (entries.length === 0) {
    const emptyHint = document.createElement('div');
    emptyHint.style.cssText = 'text-align:center;padding:24px;color:var(--color-frame-text-muted);font-size:12px;';
    emptyHint.textContent = t('pathPicker.empty');
    list.appendChild(emptyHint);
    return;
  }

  entries.forEach(entry => {
    const item = document.createElement('div');
    item.className = 'pp-item';
    item.innerHTML = '<span class="pp-icon">&#128193;</span><span class="pp-name">' + escapeHtml(entry.name) + '</span>';
    item.addEventListener('click', () => browseTo(entry.path));
    list.appendChild(item);
  });
}

export function initPathPicker() {
  document.getElementById('path-picker-cancel').addEventListener('click', closePathPicker);
  document.getElementById('path-picker-overlay').addEventListener('click', (e) => {
    if (e.target.id === 'path-picker-overlay') closePathPicker();
  });
  document.getElementById('path-picker-select').addEventListener('click', () => {
    // Callback mode (Explorer folder picker)
    if (pathPickerCallback) {
      pathPickerCallback(pathPickerCurrentPath);
      closePathPicker();
      return;
    }
    // Folder mode
    if (!pathPickerFolderId) return;
    sendWs({ type: 'setFolderProjectRoot', folderId: pathPickerFolderId, projectRoot: pathPickerCurrentPath });
    closePathPicker();
    window.dispatchEvent(new CustomEvent('project-root-changed'));
  });
  document.getElementById('path-picker-clear').addEventListener('click', () => {
    if (pathPickerCallback) {
      pathPickerCallback(null);
      closePathPicker();
      return;
    }
    if (!pathPickerFolderId) return;
    sendWs({ type: 'setFolderProjectRoot', folderId: pathPickerFolderId, projectRoot: null });
    closePathPicker();
    window.dispatchEvent(new CustomEvent('project-root-changed'));
  });
}

// sidebar.js 主体(Folder helpers 段)单向消费 openRulesEditor;inject 供主体接线。
export { openRulesEditor, injectRenderSessionSidebar };
