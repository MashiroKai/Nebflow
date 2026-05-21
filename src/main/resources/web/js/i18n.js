// i18n.js — Internationalization: t() translation function, language switching

import zhCN from './locales/zh-CN.js';
import en from './locales/en.js';

const LOCALES = { 'zh-CN': zhCN, en };
const STORAGE_KEY = 'nebflow_locale';

let current = localStorage.getItem(STORAGE_KEY) || 'zh-CN';
// Fallback if invalid
if (!LOCALES[current]) current = 'zh-CN';

/**
 * Get translated text for a key.
 * Supports nested keys via dot notation: 'settings.runtime'
 * Falls back to key itself if not found.
 */
export function t(key, params) {
  const dict = LOCALES[current] || zhCN;
  let val = dict[key];
  if (val === undefined) {
    // Try English fallback
    val = en[key];
  }
  if (val === undefined) return key;
  if (params) {
    return val.replace(/\{(\w+)\}/g, (_, k) => params[k] ?? '');
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
  const map = {
    'new-agent-btn': ['title', 'nav.newAgent'],
    'nav-settings-btn': null, // handled by data-tab
    'panel-title-sessions': ['text', 'sidebar.sessions'],
    'new-folder-btn': ['title', 'sidebar.newFolder'],
    'search-input': ['placeholder', 'sidebar.searchPlaceholder'],
    'panel-title-settings': ['text', 'sidebar.settingsTitle'],
    'memory-btn': ['text', 'header.memory'],
    'input': ['placeholder', 'input.placeholder'],
    'voice-text': ['text', 'input.voiceListening'],
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

  // BG dropdown header
  const bgHeader = document.querySelector('.bg-dropdown-header');
  if (bgHeader) bgHeader.textContent = t('header.bgTasks');

  // Settings nav item title
  const settingsNavItem = document.querySelector('.nav-item[data-tab="settings"]');
  if (settingsNavItem) settingsNavItem.title = t('nav.settings');

  // New agent button title
  const newAgentBtn = document.getElementById('new-agent-btn');
  if (newAgentBtn) newAgentBtn.title = t('nav.newAgent');

  // Session panel title
  const sessionPanelTitle = document.querySelector('#panel-sessions .panel-title');
  if (sessionPanelTitle) sessionPanelTitle.textContent = t('sidebar.sessions');

  // Settings panel title
  const settingsPanelTitle = document.querySelector('#panel-settings .panel-title');
  if (settingsPanelTitle) settingsPanelTitle.textContent = t('sidebar.settingsTitle');

  // New folder button title
  const newFolderBtn = document.getElementById('new-folder-btn');
  if (newFolderBtn) newFolderBtn.title = t('sidebar.newFolder');

  // HTML lang attribute
  document.documentElement.lang = t('html.lang');
}
