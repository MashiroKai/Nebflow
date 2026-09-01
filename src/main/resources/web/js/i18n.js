// i18n.js — Internationalization: t() translation function, language switching

import zhCN from './locales/zh-CN.js';
import en from './locales/en.js';
import { brand } from './brand.js';
import { key } from './branding.js';

const LOCALES = { 'zh-CN': zhCN, en };
const STORAGE_KEY = key('locale');

let current = localStorage.getItem(STORAGE_KEY) || 'zh-CN';
// Fallback if invalid
if (!LOCALES[current]) current = 'zh-CN';

/**
 * Get translated text for a key.
 * Supports nested keys via dot notation: 'settings.runtime'
 * Falls back to key itself if not found.
 * '{brand}' is always interpolated from brand.js (no need to pass it).
 */
export function t(key, params) {
  const dict = LOCALES[current] || zhCN;
  let val = dict[key];
  if (val === undefined) {
    // Try English fallback
    val = en[key];
  }
  if (val === undefined) return key;
  if (params || val.indexOf('{brand}') !== -1) {
    const p = { brand: brand.productName, ...params };
    return val.replace(/\{(\w+)\}/g, (_, k) => p[k] ?? '');
  }
  return val;
}

/** Get current locale code. */
export function getLocale() {
  return current;
}

/** Set locale and persist. Dispatches 'locale-changed' event. */
export function setLocale(code) {
  if (!LOCALES[code] || code === current) return;
  current = code;
  localStorage.setItem(STORAGE_KEY, code);
  window.dispatchEvent(new CustomEvent('locale-changed', { detail: { locale: code } }));
}

/** Get available locale codes. */
export function getAvailableLocales() {
  return Object.keys(LOCALES);
}

/**
 * Apply translations to static HTML elements (index.html).
 * Called once on init and again when locale changes.
 */
export function applyLocaleToHtml() {
  // ── Register unified i18n for UI controls ──
  // All buttons/controls requiring title/text/placeholder MUST be registered here,
  // NOT hardcoded in index.html. Add:  id → [attr, 'namespace.key']
  const map = {
    'new-agent-btn': ['title', 'nav.newAgent'],
    'nav-settings-btn': null, // handled by data-tab
    'panel-title-sessions': ['text', 'sidebar.sessions'],
    'panel-title-explorer': ['text', 'panel.explorer'],
    'new-folder-btn': ['title', 'sidebar.newFolder'],
    'sidebar-toggle': ['title', 'sidebar.toggle'],
    'search-input': ['placeholder', 'sidebar.searchPlaceholder'],
    'panel-title-settings': ['text', 'sidebar.settingsTitle'],
    'memory-btn': ['title', 'header.memory'],
    'bypass-toggle': ['title', 'bypass.toggle'],
    'input': ['placeholder', 'input.placeholder'],
    'skip-freeze-btn': ['title', 'chat.skipFreeze'],
    'skip-freeze-label': ['text', 'chat.skipFreeze'],
    'voice-text': ['text', 'input.voiceListening'],
    'voice-btn': ['title', 'input.voiceBtn'],
    'voice-hint': ['text', 'input.voiceHint'],
    'modal-title': ['text', 'modal.newSession'],
    'modal-input': ['placeholder', 'modal.sessionName'],
    'modal-cancel': ['text', 'modal.cancel'],
    'modal-confirm': ['text', 'modal.create'],
    'delete-title': ['text', 'modal.deleteSession'],
    'delete-cancel': ['text', 'modal.cancel'],
    'delete-confirm': ['text', 'modal.deleteConfirm'],
    'agent-modal-title': ['text', 'modal.agentTitle'],
    'memory-modal-title': ['text', 'memory.title'],
    'memory-modal-cancel': ['text', 'modal.cancel'],
    'memory-modal-save': ['text', 'memory.save'],
    'memory-content-input': ['placeholder', 'memory.placeholder'],
    'daemon-btn': ['title', 'daemons.title'],
    'bgagent-indicator': ['title', 'subagents.indicatorTitle'],
    'flows-indicator': ['title', 'flows.indicatorTitle'],
    // ── UI remnant localization (activity bar / explorer / header / modals) ──
    'activity-avatar': ['title', 'activity.login'],
    'usage-btn': ['title', 'activity.usage'],
    'teams-btn': ['title', 'activity.teams'],
    'flows-btn': ['title', 'activity.flows'],
    'legacy-btn': ['title', 'activity.legacy'],
    'legacy-pop-title': ['text', 'activity.legacy'],
    'legacy-item-teams': ['text', 'activity.teams'],
    'legacy-item-flows': ['text', 'activity.flows'],
    'projects-btn': ['title', 'project.title'],
    'agents-btn': ['title', 'activity.agents'],
    'settings-btn': ['title', 'activity.settings'],
    'explorer-new-file-btn': ['title', 'activity.newFile'],
    'explorer-new-folder-btn': ['title', 'activity.newFolder'],
    'explorer-folder-btn': ['title', 'activity.openFolder'],
    'voice-toggle-btn': ['title', 'header.voiceOutput'],
    'reminder-btn': ['title', 'header.scheduledTasks'],
    'canvas-toggle-btn': ['title', 'header.canvas'],
    'reminder-create-btn': ['title', 'header.newTask'],
    'rules-modal-title': ['text', 'rules.title'],
    'rules-modal-cancel': ['text', 'rules.cancel'],
    'rules-modal-save': ['text', 'rules.save'],
    'path-picker-title': ['text', 'pathPicker.title'],
    'path-picker-cancel': ['text', 'modal.cancel'],
    'path-picker-clear': ['text', 'pathPicker.clear'],
    'path-picker-select': ['text', 'pathPicker.select'],
    'canvas-close-btn': ['title', 'modal.close'],
    'settings-modal-close': ['title', 'modal.close'],
    'search-modal-close': ['title', 'modal.close'],
    'daemon-close-btn': ['title', 'modal.close'],
  };

  // Static elements with IDs
  for (const [id, entry] of Object.entries(map)) {
    if (!entry) continue;
    const [attr, key] = entry;
    const el = document.getElementById(id);
    if (!el) continue;
    if (attr === 'text') el.textContent = t(key);
    else if (attr === 'title') el.title = t(key);
    else if (attr === 'placeholder') el.placeholder = t(key);
  }

  // Buttons/titles not covered by the map above
  const extras = [
    ['search-clear', 'title', 'search.clear'],
    ['sidebar-edge', 'title', 'sidebar.edgeToggle'],
    ['new-agent-btn', 'title', 'nav.newAgent'],
    ['new-folder-btn', 'title', 'sidebar.newFolder'],
    ['search-btn', 'title', 'search.btnTitle'],
    ['search-modal-title', 'text', 'search.title'],
    ['search-keyword', 'placeholder', 'search.placeholder'],
    ['search-go', 'text', 'search.go'],
  ];
  for (const [id, attr, key] of extras) {
    const el = document.getElementById(id);
    if (el) el[attr] = t(key);
  }

  // Agent modal field labels (by data-i18n attribute)
  document.querySelectorAll('[data-i18n]').forEach(el => {
    el.textContent = t(el.dataset.i18n);
  });

  // Agent modal inputs placeholders
  const placeholders = {
    'agent-name-input': 'agent.namePlaceholder',
    'agent-desc-input': 'agent.descPlaceholder',
    'agent-system-input': 'agent.systemPromptPlaceholder',
  };
  for (const [id, key] of Object.entries(placeholders)) {
    const el = document.getElementById(id);
    if (el) el.placeholder = t(key);
  }

  // Agent modal buttons
  const agentCancel = document.getElementById('agent-modal-cancel');
  if (agentCancel) agentCancel.textContent = t('modal.cancel');
  const agentSave = document.getElementById('agent-modal-save');
  if (agentSave) agentSave.textContent = t('agent.save');

  // Memory tabs
  document.querySelectorAll('.memory-tab').forEach(tab => {
    const scope = tab.dataset.scope;
    const key = 'memory.' + scope;
    tab.textContent = t(key);
  });

  // BG dropdown header — scoped to #bg-dropdown: the class-first match would
  // hit #bgagent-dropdown's "Sub-agents" header (it comes first in the DOM)
  // and wrongly overwrite it with the background-tasks label.
  const bgHeader = document.querySelector('#bg-dropdown .bg-dropdown-header');
  if (bgHeader) bgHeader.textContent = t('header.bgTasks');

  // Sub-agents dropdown header (scoped by ancestor, unlike the class-first match above)
  const bgAgentHeader = document.querySelector('#bgagent-dropdown .bg-dropdown-header');
  if (bgAgentHeader) bgAgentHeader.textContent = t('subagents.panelTitle');

  // Running flows dropdown header
  const flowsHeader = document.querySelector('#flows-dropdown .bg-dropdown-header');
  if (flowsHeader) flowsHeader.textContent = t('flows.panelTitle');

  // Reminder panel title (scheduled tasks)
  const reminderPanelTitle = document.querySelector('.reminder-panel-title');
  if (reminderPanelTitle) reminderPanelTitle.textContent = t('header.scheduledTasks');

  // Bypass menu mode buttons (data-mode → i18n key)
  const bypassModeKeys = {
    'confirm-edits': 'bypass.confirmEdits',
    'auto-edits': 'bypass.autoEdits',
    'auto-all': 'bypass.autoAll',
  };
  document.querySelectorAll('#bypass-menu button[data-mode]').forEach(btn => {
    const key = bypassModeKeys[btn.dataset.mode];
    if (key) btn.textContent = t(key);
  });

  // Daemon panel title (single element — precise selector, not class-first match)
  const daemonPanelTitle = document.querySelector('#daemon-panel .daemon-panel-title');
  if (daemonPanelTitle) daemonPanelTitle.textContent = t('daemons.title');

  // Settings nav item title
  const settingsNavItem = document.querySelector('.nav-item[data-tab="settings"]');
  if (settingsNavItem) settingsNavItem.title = t('nav.settings');

  // Session panel title
  const sessionPanelTitle = document.querySelector('#panel-sessions .panel-title');
  if (sessionPanelTitle) sessionPanelTitle.textContent = t('sidebar.sessions');

  // Settings modal title
  const settingsModalTitle = document.getElementById('settings-modal-title');
  if (settingsModalTitle) settingsModalTitle.textContent = t('sidebar.settingsTitle');

  // HTML lang attribute
  document.documentElement.lang = t('html.lang');
}
