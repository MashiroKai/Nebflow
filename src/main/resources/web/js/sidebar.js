// sidebar.js — Left panel management: nav tabs, agent list, settings, session sidebar

import state, { LS_SESSIONS_KEY, LS_DRAFTS_KEY } from './state.js';
import { sendWs, onMessage } from './ws.js';
import { showAgentModal, startInlineNewSession, showBatchDeleteModal } from './modal.js';
import { renderMarkdownWithMath, smartScroll, stopSpinner } from './utils.js';
import { finishAgent, setStatus, renderToolPending, cancelThinkingRAF } from './chat.js';
import { restoreFromStorage, loadMsgs } from './persistence.js';
import { renderTaskList } from './taskList.js';
import { clearMemoryCache } from './memory.js';
import { refreshScheduledTasks } from './scheduled-task.js';
import { loadSecondary } from './secondary-chat.js';
import { t, getLocale, setLocale, getAvailableLocales } from './i18n.js';
import { fetchMeshStatus, meshSettingsHTML, bindMeshEvents } from './mesh.js';

const eyeSvg = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
const eyeOffSvg = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

// ---------- Fallback drag state (module level, survives re-renders) ----------
let _fallbackDrag = null;

document.addEventListener('mousemove', (e) => {
  const d = _fallbackDrag;
  if (!d) return;

  if (!d.active) {
    const dx = e.clientX - d.startX;
    const dy = e.clientY - d.startY;
    if (Math.abs(dx) < 3 && Math.abs(dy) < 3) return;
    d.active = true;
    d.tag.classList.add('dragging');
    // Create floating clone of the dragged tag
    const rect = d.tag.getBoundingClientRect();
    const clone = d.tag.cloneNode(true);
    clone.style.position = 'fixed';
    clone.style.left = rect.left + 'px';
    clone.style.top = rect.top + 'px';
    clone.style.width = rect.width + 'px';
    clone.style.pointerEvents = 'none';
    clone.style.opacity = '0.85';
    clone.style.zIndex = '10000';
    clone.style.transform = 'rotate(1.5deg) scale(1.05)';
    clone.style.boxShadow = '0 4px 16px rgba(0,0,0,0.18)';
    clone.style.transition = 'none';
    document.body.appendChild(clone);
    d.clone = clone;
  } else {
    // Move floating clone with cursor
    const clone = d.clone;
    clone.style.left = (e.clientX - 20) + 'px';
    clone.style.top = (e.clientY - 12) + 'px';

    // Detect element under cursor (hide clone temporarily)
    clone.style.display = 'none';
    const elemBelow = document.elementFromPoint(e.clientX, e.clientY);
    clone.style.display = '';

    const target = elemBelow?.closest('.cfg-tag');
    if (target && target !== d.tag && target.closest('#cfg-fallback-list')) {
      if (d.targetTag && d.targetTag !== target) d.targetTag.classList.remove('drag-over');
      target.classList.add('drag-over');
      d.targetTag = target;
    } else if (d.targetTag) {
      d.targetTag.classList.remove('drag-over');
      d.targetTag = null;
    }
  }
});

document.addEventListener('mouseup', () => {
  const d = _fallbackDrag;
  if (!d) return;

  // Clean up floating clone
  if (d.clone) d.clone.remove();
  d.tag?.classList.remove('dragging');

  if (d.active && d.targetTag) {
    const dstIdx = parseInt(d.targetTag.dataset.idx);
    d.targetTag.classList.remove('drag-over');
    if (dstIdx !== d.idx) {
      const fallbacks = state.parsedConfig.llm.model.fallbacks;
      const [moved] = fallbacks.splice(d.idx, 1);
      const insertIdx = dstIdx <= d.idx ? dstIdx : dstIdx - 1;
      fallbacks.splice(insertIdx, 0, moved);
      state.configDirty = true;
      flushConfigToServer();
      renderSettings();
    }
  } else if (d.targetTag) {
    d.targetTag.classList.remove('drag-over');
  }

  _fallbackDrag = null;
});

// ---------- Active Folder (VSCode-style) ----------
export function setActiveFolder(folderId) {
  state.activeFolderId = folderId;
  // Update UI highlight
  document.querySelectorAll('.folder-item').forEach(el => {
    el.classList.toggle('active', el.dataset.folderId === folderId);
  });
}

export function clearActiveFolder() {
  state.activeFolderId = null;
  document.querySelectorAll('.folder-item.active').forEach(el => el.classList.remove('active'));
}

// ---------- Panel Switching ----------
function showPanel(tab) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  const panel = document.getElementById('panel-' + tab);
  if (panel) panel.classList.add('active');
}

function closeSecondaryPanel() {
  state.secondarySessionId = null;
  document.body.classList.remove('split-view');
  const panel = document.getElementById('secondary-panel');
  if (panel) {
    panel.classList.remove('visible');
    panel.classList.add('hidden');
  }
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

function openInSecondary(session) {
  state.secondarySessionId = session.id;
  document.body.classList.add('split-view');
  const panel = document.getElementById('secondary-panel');
  panel.classList.remove('hidden');
  panel.classList.add('visible');
  document.getElementById('secondary-session-name').textContent = session.name;
  const badge = document.getElementById('secondary-agent-badge');
  if (badge) badge.textContent = session.agentName || 'Nebula';
  // Load session history into secondary chat
  loadSecondary(session.id, session.name);
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

export function initNavTabs() {
  // New layout: settings button in sessions panel header
  const settingsBtn = document.getElementById('settings-btn');
  if (settingsBtn) {
    settingsBtn.addEventListener('click', () => {
      showPanel('settings');
      sendWs({type: 'getConfig'});
      renderSettings();
    });
  }

  const settingsBackBtn = document.getElementById('settings-back-btn');
  if (settingsBackBtn) {
    settingsBackBtn.addEventListener('click', () => {
      showPanel('sessions');
    });
  }

  // Secondary panel close button
  const secondaryCloseBtn = document.getElementById('secondary-close-btn');
  if (secondaryCloseBtn) {
    secondaryCloseBtn.addEventListener('click', () => {
      closeSecondaryPanel();
    });
  }
}

// ---------- Agent icons in Nav Bar ----------
export function renderAgentList() {
  const list = document.getElementById('nav-agent-list');
  if (!list) return;
  list.innerHTML = '';
  state.agentsData.forEach(a => {
    const el = document.createElement('div');
    const isActive = state.selectedAgent === a.name;
    el.className = 'nav-agent' + (isActive ? ' active' : '');
    const avatar = a.avatar || '';
    const displayName = a.displayName || a.name;
    el.dataset.name = a.name;
    el.title = displayName;
    const iconHtml = avatar
      ? `<span class="nav-agent-icon">${avatar}</span>`
      : `<span class="nav-agent-icon agent-letter-icon">${escapeHtml(displayName.charAt(0).toUpperCase())}</span>`;
    el.innerHTML = `${iconHtml}<span class="nav-agent-label">${escapeHtml(displayName.slice(0, 6))}</span>`;
    el.addEventListener('click', () => selectAgent(a.name));
    el.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      sendWs({type: 'getAgentConfig', name: a.name});
    });
    list.appendChild(el);
  });
  lucide.createIcons();
  computeAgentStates();
}

// ---------- Agent State Animations ----------

/** Apply a visual state class to an agent nav element. */
function applyAgentState(agentName, stateClass) {
  const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
  if (!el) return;
  el.classList.remove('state-working', 'state-waiting', 'state-compressing', 'state-complete');
  if (stateClass !== 'idle') {
    el.classList.add('state-' + stateClass);
  }
}

/**
 * Recompute aggregate state for each agent based on all their sessions.
 * Priority order: waiting (attention) > working (busy) > compressing > idle.
 * Detects busy→idle transitions and triggers a brief 'complete' animation.
 */
export function computeAgentStates() {
  // Use sessionAgentMap (contains ALL sessions) instead of state.sessions (filtered by selectedAgent)
  const agentSessionIds = {};
  Object.entries(state.sessionAgentMap).forEach(([sid, agent]) => {
    if (!agentSessionIds[agent]) agentSessionIds[agent] = [];
    agentSessionIds[agent].push(sid);
  });

  // Ensure agents with no sessions still get 'idle'
  state.agentsData.forEach(a => {
    if (!agentSessionIds[a.name]) agentSessionIds[a.name] = [];
  });

  const newStates = {};

  Object.keys(agentSessionIds).forEach(agent => {
    const sids = agentSessionIds[agent];
    let hasAttention = false;
    let hasBusy = false;
    let hasCompacting = false;

    sids.forEach(sid => {
      if (state.attentionSessions.has(sid)) hasAttention = true;
      if (state.busySessionIds.has(sid)) hasBusy = true;
      if (state.compactingSessionIds.has(sid)) hasCompacting = true;
    });

    // Priority: attention > busy > compacting > idle
    if (hasAttention) {
      newStates[agent] = 'waiting';
    } else if (hasBusy) {
      newStates[agent] = 'working';
    } else if (hasCompacting) {
      newStates[agent] = 'compressing';
    } else {
      newStates[agent] = 'idle';
    }

    // Detect complete transition: was working, now idle
    const prevState = state.agentStates[agent];
    if (prevState === 'working' && newStates[agent] === 'idle') {
      newStates[agent] = 'complete';
      // Clear any existing timer
      if (state.agentStateTimers[agent]) {
        clearTimeout(state.agentStateTimers[agent]);
      }
      // Schedule return to idle after animation completes
      state.agentStateTimers[agent] = setTimeout(() => {
        applyAgentState(agent, 'idle');
        state.agentStates[agent] = 'idle';
      }, 600);
    }
  });

  // Apply state changes
  Object.keys(newStates).forEach(agent => {
    if (state.agentStates[agent] !== newStates[agent] || newStates[agent] === 'complete') {
      state.agentStates[agent] = newStates[agent];
      applyAgentState(agent, newStates[agent]);
    }
  });
}

/** Select an agent and load its sessions. */
export function selectAgent(agentName) {
  const isSame = state.selectedAgent === agentName;
  // When the same agent icon is clicked (e.g. returning from settings with a single agent),
  // still switch the panel back to sessions.
  if (isSame) {
    const panel = document.getElementById('panel-settings');
    if (panel && panel.classList.contains('active')) {
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
      document.getElementById('panel-sessions').classList.add('active');
      document.querySelectorAll('.nav-item[data-tab]').forEach(n => n.classList.remove('active'));
    }
    return;
  }
  // Save current input draft before switching agent tabs
  saveInputDraft(state.activeSessionId);
  state.selectedAgent = agentName;
  // Clear unread count for this agent
  const prevCount = state.agentUnreadCounts[agentName] || 0;
  if (prevCount > 0) {
    state.agentUnreadCounts[agentName] = 0;
    const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
    if (el) { const dot = el.querySelector('.agent-notif-dot'); if (dot) dot.remove(); }
  }
  // Switch back to sessions panel if on settings
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.getElementById('panel-sessions').classList.add('active');
  document.querySelectorAll('.nav-item[data-tab]').forEach(n => n.classList.remove('active'));
  // Update nav bar active state
  document.querySelectorAll('#nav-agent-list .nav-agent').forEach(el => {
    el.classList.toggle('active', el.dataset.name === agentName);
  });
  // Exit batch mode and clear active folder when switching agents
  if (state.selectedSessionIds.size > 0) exitBatchMode();
  clearActiveFolder();
  // Load sessions for this agent
  sendWs({type: 'listAgentSessions', name: agentName});
}

/** Update header brand to show agent display name. */
export function updateHeaderBrand(agentName) {
  const brandEl = document.querySelector('.header-brand');
  if (!brandEl) return;
  const agent = state.agentsData.find(a => a.name === agentName);
  if (agent) {
    brandEl.textContent = agent.displayName || agent.name;
  } else {
    brandEl.textContent = 'nebflow';
  }
}

// ---------- Settings Panel ----------
export function renderSettings() {
  const content = document.getElementById('settings-content');
  const cfg = state.parsedConfig || {};
  const llm = cfg.llm || {};
  const providers = llm.providers || {};
  const model = llm.model || {};
  const mcpServers = cfg.mcpServers || {};
  const compact = cfg.compact || {};
  const providerNames = Object.keys(providers);

  // Build model options from all providers
  const allModels = [];
  providerNames.forEach(pName => {
    const p = providers[pName];
    (p.models || []).forEach(m => {
      allModels.push({ ref: `${pName}/${m.id}`, label: `${pName}/${m.id}` });
    });
  });

  const defaultModel = model.default || '';
  const fallbacks = model.fallbacks || [];

  // Load card design prompt
  sendWs({type: 'getCardDesign'});

  // Build language selector options
  const locales = getAvailableLocales();
  const localeLabels = { 'zh-CN': '中文', en: 'English' };
  const langOpts = locales.map(code =>
    `<option value="${code}" ${code === getLocale() ? 'selected' : ''}>${localeLabels[code] || code}</option>`
  ).join('');

  content.innerHTML = `
    <div class="settings-section">
      <div class="settings-section-title">${t('mesh.title')}</div>
      ${meshSettingsHTML()}
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.runtime')}</div>
      <div class="settings-row">
        <span class="settings-label">${t('settings.thinkingMode')}</span>
        <div class="toggle ${state.thinkingMode ? 'on' : ''}" id="toggle-thinking"></div>
      </div>
      <div class="cfg-form-group" id="thinking-budget-group" style="display:${state.thinkingMode ? 'block' : 'none'}">
        <label class="cfg-label">${t('settings.thinkingBudget')}</label>
        <input class="cfg-input" id="cfg-thinking-budget" type="number" min="1024" value="${state.thinkingMode?.budgetTokens ?? 32000}" autocomplete="off">
        <div class="cfg-hint">${t('settings.thinkingBudgetHint')}</div>
      </div>
      <div class="settings-row">
        <span class="settings-label">${t('settings.language')}</span>
        <select class="cfg-select" id="cfg-language" style="width:auto">${langOpts}</select>
      </div>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.compaction')}</div>
      <div class="cfg-form-group">
        <label class="cfg-label">${t('settings.compactTtl')} <span class="cfg-hint">(${t('settings.compactTtlUnit')})</span></label>
        <input class="cfg-input" id="cfg-compact-ttl" type="number" min="1" max="1440" value="${compact.microCacheTtlMinutes ?? 120}" autocomplete="off">
        <div class="cfg-hint">${t('settings.compactTtlHint')}</div>
      </div>
      <div class="cfg-form-group">
        <label class="cfg-label">${t('settings.keepRecent')}</label>
        <input class="cfg-input" id="cfg-compact-keep" type="number" min="0" max="50" value="${compact.microKeepRecent ?? 5}" autocomplete="off">
        <div class="cfg-hint">${t('settings.keepRecentHint')}</div>
      </div>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.providers')}</div>
      <div id="provider-list">
        ${providerNames.map(name => renderProviderCard(name, providers[name])).join('')}
      </div>
      <button class="cfg-btn cfg-btn-add" id="btn-add-provider">${t('settings.addProvider')}</button>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.modelChain')}</div>
      <div class="cfg-form-group">
        <label class="cfg-label">${t('settings.defaultModel')}</label>
        <select class="cfg-select" id="cfg-default-model">
          <option value="">${t('settings.selectPlaceholder')}</option>
          ${allModels.map(m => `<option value="${m.ref}" ${m.ref === defaultModel ? 'selected' : ''}>${m.label}</option>`).join('')}
        </select>
      </div>
      <div class="cfg-form-group">
        <label class="cfg-label">${t('settings.fallbackModels')}</label>
        <div id="cfg-fallback-list" class="cfg-tag-list">
          ${fallbacks.map((f, i) => `
            <span class="cfg-tag" data-idx="${i}" data-fallback="${escapeHtml(f)}">${escapeHtml(f)} <span class="cfg-tag-remove" data-idx="${i}">×</span></span>
          `).join('')}
        </div>
        <select class="cfg-select cfg-select-sm" id="cfg-add-fallback">
          <option value="">${t('settings.addFallback')}</option>
          ${allModels.filter(m => m.ref !== defaultModel && !fallbacks.includes(m.ref)).map(m => `<option value="${m.ref}">${m.label}</option>`).join('')}
        </select>
      </div>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.mcpServers')}</div>
      <div id="mcp-server-list">
        ${Object.keys(mcpServers).map(name => renderMcpServerCard(name, mcpServers[name])).join('')}
        ${Object.keys(mcpServers).length === 0 ? `<div class="cfg-empty">${t('settings.noMcp')}</div>` : ''}
      </div>
      <button class="cfg-btn cfg-btn-add" id="btn-add-mcp">${t('settings.addMcp')}</button>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.cardDesign')}</div>
      <div class="cfg-hint" style="margin-bottom:8px">${t('settings.cardDesignHint')}</div>
      <button class="cfg-btn" id="btn-edit-card-design">${t('settings.cardDesignEdit')}</button>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.advanced')}</div>
      <button class="cfg-btn" id="btn-toggle-json">${t('settings.editRawJson')}</button>
    </div>
    <div class="settings-section" id="json-editor-section" style="display:${state.settingsShowJson ? 'block' : 'none'}">
      <div class="config-editor-wrap">
        <textarea id="config-editor" spellcheck="false">${escapeHtml(state.configText)}</textarea>
        <div class="config-actions">
          <button class="btn-save" id="btn-save-config">${t('settings.save')}</button>
          <button id="btn-reload-config">${t('settings.reload')}</button>
        </div>
      </div>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.about')}</div>
      <div class="about-info">
        <div>Nebflow v${state.serverVersion || '...'}</div>
        <div style="margin-top:4px;font-size:12px;color:var(--color-text-secondary)">${t('settings.connection')}: <span style="color:${state.dom.connEl.classList.contains('off') ? '#f44336' : '#4caf50'}">${state.dom.connEl.classList.contains('off') ? t('settings.disconnected') : t('settings.connected')}</span></div>
        <div style="margin-top:10px">
          <button class="cfg-btn cfg-btn-sm" id="btn-check-update">${t('settings.checkUpdate')}</button>
          <span id="update-status" style="margin-left:8px;font-size:12px;color:var(--color-text-secondary)"></span>
        </div>
        <div id="update-action" style="display:none;margin-top:8px">
          <button class="cfg-btn cfg-btn-primary" id="btn-do-update">${t('settings.updateNow')}</button>
          <button class="cfg-btn cfg-btn-sm" id="btn-dismiss-update" style="margin-left:6px">${t('settings.updateLater')}</button>
        </div>
      </div>
    </div>`;

  bindSettingsEvents(content, cfg, allModels);
  bindMeshEvents(() => renderSettings());
}

function renderProviderCard(name, p) {
  const modelCount = (p.models || []).length;
  return `
    <div class="cfg-card" data-provider="${escapeHtml(name)}">
      <div class="cfg-card-header">
        <span class="cfg-card-title">${escapeHtml(name)}</span>
        <button class="cfg-card-remove" data-provider="${escapeHtml(name)}" title="${t('provider.remove')}">×</button>
      </div>
      <div class="cfg-card-meta">
        <span class="cfg-card-badge">${escapeHtml((p.protocol || '').toUpperCase())}</span>
        <span class="cfg-card-sub">${modelCount} model${modelCount !== 1 ? 's' : ''}</span>
      </div>
    </div>`;
}

function renderMcpServerCard(name, s) {
  const type = s.url ? 'URL' : 'CMD';
  return `
    <div class="cfg-card" data-mcp="${escapeHtml(name)}">
      <div class="cfg-card-header">
        <span class="cfg-card-title">${escapeHtml(name)}</span>
        <button class="cfg-card-remove" data-mcp="${escapeHtml(name)}" title="${t('provider.remove')}">×</button>
      </div>
      <div class="cfg-card-meta">
        <span class="cfg-card-badge">${type}</span>
      </div>
    </div>`;
}

function bindSettingsEvents(content, cfg, allModels) {
  // Thinking toggle
  document.getElementById('toggle-thinking')?.addEventListener('click', function() {
    this.classList.toggle('on');
    const enabled = this.classList.contains('on');
    const budgetEl = document.getElementById('cfg-thinking-budget');
    const budgetVal = budgetEl ? parseInt(budgetEl.value) || 32000 : 32000;
    state.thinkingMode = enabled ? {enabled: true, budgetTokens: budgetVal} : null;
    sendWs({type: 'setThinking', thinking: state.thinkingMode});
    const group = document.getElementById('thinking-budget-group');
    if (group) group.style.display = enabled ? 'block' : 'none';
  });

  document.getElementById('cfg-thinking-budget')?.addEventListener('change', function() {
    const val = parseInt(this.value) || 32000;
    if (state.thinkingMode?.enabled) {
      state.thinkingMode = {enabled: true, budgetTokens: val};
      sendWs({type: 'setThinking', thinking: state.thinkingMode});
    }
  });

  // Language selector
  document.getElementById('cfg-language')?.addEventListener('change', function() {
    setLocale(this.value);
    renderSettings();
  });

  // --- Compact config ---
  const compactInputs = ['cfg-compact-ttl', 'cfg-compact-keep'];
  compactInputs.forEach(id => {
    document.getElementById(id)?.addEventListener('change', () => {
      if (!state.parsedConfig) state.parsedConfig = {};
      const ttl = parseInt(document.getElementById('cfg-compact-ttl')?.value) ?? 120;
      const keep = parseInt(document.getElementById('cfg-compact-keep')?.value) ?? 5;
      state.parsedConfig.compact = {
        ...(state.parsedConfig.compact || {}),
        microCacheTtlMinutes: ttl,
        microKeepRecent: keep
      };
      state.configDirty = true;
      flushConfigToServer();
    });
  });

  // --- Provider add/edit/remove ---
  document.getElementById('btn-add-provider')?.addEventListener('click', () => {
    showProviderModal(null, null, (name, data) => {
      if (!state.parsedConfig) state.parsedConfig = {llm: {providers: {}, model: {default: ''}}};
      if (!state.parsedConfig.llm) state.parsedConfig.llm = {providers: {}, model: {default: ''}};
      if (!state.parsedConfig.llm.providers) state.parsedConfig.llm.providers = {};
      if (!state.parsedConfig.llm.model) state.parsedConfig.llm.model = {default: '', fallbacks: []};
      state.parsedConfig.llm.providers[name] = data;
      // Auto-set default model if it's empty or points to a non-existent provider
      const currentDefault = state.parsedConfig.llm.model.default || '';
      const defaultProvider = currentDefault.split('/')[0];
      if (!currentDefault || !state.parsedConfig.llm.providers[defaultProvider]) {
        const firstModel = (data.models || [])[0];
        if (firstModel) {
          state.parsedConfig.llm.model.default = `${name}/${firstModel.id}`;
        }
      }
      state.configDirty = true;
      flushConfigToServer();
    });
  });

  content.querySelectorAll('.cfg-card[data-provider]').forEach(card => {
    const name = card.dataset.provider;
    card.querySelector('.cfg-card-remove')?.addEventListener('click', (e) => {
      e.stopPropagation();
      if (!confirm(t('provider.removeConfirm', { name }))) return;
      // Use null instead of delete — backend mergeConfig treats null as explicit deletion
      state.parsedConfig.llm.providers[name] = null;
      state.configDirty = true;
      flushConfigToServer();
      renderSettings();
    });
    card.addEventListener('click', () => {
      const p = state.parsedConfig.llm.providers[name];
      showProviderModal(name, p, (newName, data) => {
        if (newName !== name) {
          // Use null to signal explicit deletion of old name
          state.parsedConfig.llm.providers[name] = null;
        }
        state.parsedConfig.llm.providers[newName] = data;
        state.configDirty = true;
        flushConfigToServer();
      });
    });
  });

  // --- Model chain ---
  document.getElementById('cfg-default-model')?.addEventListener('change', function() {
    if (!state.parsedConfig.llm) state.parsedConfig.llm = {providers: {}, model: {default: ''}};
    if (!state.parsedConfig.llm.model) state.parsedConfig.llm.model = {default: '', fallbacks: []};
    const oldDefault = state.parsedConfig.llm.model.default || '';
    const newDefault = this.value;
    if (newDefault === oldDefault) return;

    const fallbacks = state.parsedConfig.llm.model.fallbacks || [];

    // Auto-adjust fallbacks:
    // 1. Remove the new default from fallbacks (it's now the primary)
    // 2. Add the old default to fallbacks (becomes a fallback option)
    let newFallbacks = fallbacks.filter(f => f !== newDefault);
    if (oldDefault && oldDefault !== newDefault && !newFallbacks.includes(oldDefault)) {
      newFallbacks.push(oldDefault);
    }

    state.parsedConfig.llm.model.default = newDefault;
    state.parsedConfig.llm.model.fallbacks = newFallbacks;
    state.configDirty = true;
    flushConfigToServer();
    renderSettings();
  });

  content.querySelectorAll('.cfg-tag-remove').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const idx = parseInt(btn.dataset.idx);
      state.parsedConfig.llm.model.fallbacks.splice(idx, 1);
      state.configDirty = true;
      flushConfigToServer();
      renderSettings();
    });
  });

  document.getElementById('cfg-add-fallback')?.addEventListener('change', function() {
    if (!this.value) return;
    if (!state.parsedConfig.llm.model.fallbacks) state.parsedConfig.llm.model.fallbacks = [];
    if (!state.parsedConfig.llm.model.fallbacks.includes(this.value)) {
      state.parsedConfig.llm.model.fallbacks.push(this.value);
      state.configDirty = true;
      flushConfigToServer();
    }
  });

  // --- Fallback drag-and-drop reorder (mouse events, no HTML5 DnD) ---
  const fallbackList = document.getElementById('cfg-fallback-list');
  if (fallbackList) {
    fallbackList.querySelectorAll('.cfg-tag').forEach(tag => {
      tag.addEventListener('mousedown', (e) => {
        if (e.button !== 0 || e.target.closest('.cfg-tag-remove')) return;
        e.preventDefault();
        _fallbackDrag = {
          tag, idx: parseInt(tag.dataset.idx),
          startX: e.clientX, startY: e.clientY,
          active: false, clone: null, targetTag: null
        };
      });
    });
  }

  // --- MCP Servers add/remove ---
  document.getElementById('btn-add-mcp')?.addEventListener('click', () => {
    showMcpModal(null, null, (name, data) => {
      if (!state.parsedConfig) state.parsedConfig = {};
      if (!state.parsedConfig.mcpServers) state.parsedConfig.mcpServers = {};
      state.parsedConfig.mcpServers[name] = data;
      state.configDirty = true;
      flushConfigToServer();
    });
  });

  content.querySelectorAll('.cfg-card[data-mcp]').forEach(card => {
    const name = card.dataset.mcp;
    card.querySelector('.cfg-card-remove')?.addEventListener('click', (e) => {
      e.stopPropagation();
      if (!confirm(t('mcp.removeConfirm', { name }))) return;
      // Use null instead of delete — backend mergeConfig treats null as explicit deletion
      state.parsedConfig.mcpServers[name] = null;
      state.configDirty = true;
      flushConfigToServer();
      renderSettings();
    });
    card.addEventListener('click', () => {
      const s = state.parsedConfig.mcpServers[name];
      showMcpModal(name, s, (newName, data) => {
        if (newName !== name) {
          // Use null to signal explicit deletion of old name
          state.parsedConfig.mcpServers[name] = null;
        }
        state.parsedConfig.mcpServers[newName] = data;
        state.configDirty = true;
        flushConfigToServer();
      });
    });
  });

  // --- Advanced JSON editor ---
  document.getElementById('btn-toggle-json')?.addEventListener('click', () => {
    state.settingsShowJson = !state.settingsShowJson;
    const sec = document.getElementById('json-editor-section');
    if (sec) sec.style.display = state.settingsShowJson ? 'block' : 'none';
  });

  document.getElementById('btn-save-config')?.addEventListener('click', () => {
    const cfg = document.getElementById('config-editor').value;
    try {
      JSON.parse(cfg);
      sendWs({type: 'updateConfig', config: cfg});
    } catch(e) {
      alert('Invalid JSON: ' + e.message);
    }
  });

  document.getElementById('btn-reload-config')?.addEventListener('click', () => {
    sendWs({type: 'getConfig'});
  });

  // --- Card design prompt ---
  document.getElementById('btn-edit-card-design')?.addEventListener('click', () => {
    import('./modal.js').then(m => m.showCardDesignModal());
  });

  // --- Check for updates ---
  document.getElementById('btn-check-update')?.addEventListener('click', () => {
    const statusEl = document.getElementById('update-status');
    const actionEl = document.getElementById('update-action');
    statusEl.textContent = t('settings.checking');
    statusEl.style.display = '';
    actionEl.style.display = 'none';
    sendWs({type: 'checkUpdate'});
  });

  document.getElementById('btn-do-update')?.addEventListener('click', () => {
    const btn = document.getElementById('btn-do-update');
    const statusEl = document.getElementById('update-status');
    btn.textContent = t('settings.updating');
    btn.disabled = true;
    statusEl.textContent = '';
    sendWs({type: 'doUpdate'});
  });

  document.getElementById('btn-dismiss-update')?.addEventListener('click', () => {
    document.getElementById('update-action').style.display = 'none';
  });
}

function flushConfigToServer() {
  const json = JSON.stringify(state.parsedConfig, null, 2);
  state.configText = json;
  sendWs({type: 'updateConfig', config: json});
}

// Listen for config validation errors
onMessage('error', (data) => {
  if (data.message && typeof data.message === 'string' && data.message.includes('Provider')) {
    // Show validation errors from backend
    const toast = document.createElement('div');
    toast.className = 'cfg-toast cfg-toast-error';
    toast.textContent = data.message;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 5000);
  }
});

// --- Provider modal ---
function showProviderModal(existingName, existingData, onSave) {
  const isEdit = !!existingName;
  const p = existingData || {baseUrl: '', apiKey: '', protocol: 'anthropic', models: []};
  const initialModels = p.models.length > 0 ? p.models : [{id: '', maxTokens: 131072, contextWindow: 200000}];

  showModal({
    title: isEdit ? t('provider.edit', { name: existingName }) : t('provider.add'),
    fields: [
      {key: 'name', label: t('provider.id'), type: 'text', value: existingName || '', placeholder: t('provider.idPlaceholder'), disabled: isEdit},
      {key: 'baseUrl', label: t('provider.baseUrl'), type: 'text', value: p.baseUrl || '', placeholder: 'https://api.example.com/v1'},
      {key: 'apiKey', label: 'API Key', type: 'text', password: true, value: p.apiKey && p.apiKey !== '***' ? p.apiKey : '', placeholder: isEdit ? t('provider.keyPlaceholder') : t('provider.required')},
      {key: 'protocol', label: t('provider.protocol'), type: 'select', value: p.protocol || 'anthropic', options: ['anthropic', 'openai']},
      {key: 'models', label: t('provider.models'), type: 'models', value: initialModels},
    ],
    onConfirm(values) {
      const name = values.name.trim();
      if (!name) return alert(t('provider.idRequired'));
      if (/\s/.test(name)) return alert(t('provider.noSpaces'));
      let baseUrl = values.baseUrl.trim();
      if (!baseUrl) return alert(t('provider.baseUrlRequired'));
      // Auto-add trailing slash
      if (!baseUrl.endsWith('/')) baseUrl += '/';
      const apiKey = values.apiKey.trim() || '***';
      if (!isEdit && apiKey === '***') return alert(t('provider.keyRequired'));
      const validModels = values.models.filter(m => m.id && m.id.trim());
      if (validModels.length === 0) return alert(t('provider.modelRequired'));
      onSave(name, {
        baseUrl,
        apiKey,
        protocol: values.protocol,
        models: validModels,
      });
    }
  });
}

// --- MCP Server modal ---
function showMcpModal(existingName, existingData, onSave) {
  const isEdit = !!existingName;
  const s = existingData || {};
  const argsStr = (s.args || []).join(' ');
  const envLines = s.env ? Object.entries(s.env).map(([k, v]) => `${k}=${v}`).join('\n') : '';
  const headersLines = s.headers ? Object.entries(s.headers).map(([k, v]) => `${k}: ${v}`).join('\n') : '';

  showModal({
    title: isEdit ? t('mcp.edit', { name: existingName }) : t('mcp.add'),
    fields: [
      {key: 'name', label: t('mcp.serverId'), type: 'text', value: existingName || '', disabled: isEdit},
      {key: 'command', label: t('mcp.command'), type: 'text', value: s.command || '', placeholder: t('mcp.commandPlaceholder')},
      {key: 'args', label: t('mcp.args'), type: 'text', value: argsStr},
      {key: 'env', label: t('mcp.env'), type: 'textarea', value: envLines},
      {key: 'url', label: t('mcp.url'), type: 'text', value: s.url || ''},
      {key: 'headers', label: t('mcp.headers'), type: 'textarea', value: headersLines},
    ],
    onConfirm(values) {
      const name = values.name.trim();
      if (!name) return alert(t('mcp.idRequired'));
      const data = {};
      if (values.url.trim()) {
        data.url = values.url.trim();
        if (values.headers.trim()) {
          data.headers = Object.fromEntries(values.headers.trim().split('\n').map(l => { const i = l.indexOf(':'); return i > 0 ? [l.slice(0, i).trim(), l.slice(i + 1).trim()] : null; }).filter(Boolean));
        }
      } else if (values.command.trim()) {
        data.command = values.command.trim();
        data.args = values.args.trim() ? values.args.trim().split(/\s+/) : [];
        if (values.env.trim()) {
          data.env = Object.fromEntries(values.env.trim().split('\n').map(l => { const i = l.indexOf('='); return i > 0 ? [l.slice(0, i).trim(), l.slice(i + 1).trim()] : null; }).filter(Boolean));
        }
      } else {
        return alert(t('mcp.cmdOrUrlRequired'));
      }
      onSave(name, data);
    }
  });
}

// --- Generic modal ---
function showModal({title, fields, onConfirm}) {
  // Remove existing modal
  document.getElementById('cfg-modal')?.remove();

  const overlay = document.createElement('div');
  overlay.id = 'cfg-modal';
  overlay.className = 'cfg-modal-overlay';
  overlay.innerHTML = `
    <div class="cfg-modal">
      <div class="cfg-modal-title">${escapeHtml(title)}</div>
      <div class="cfg-modal-body">
        ${fields.map(f => `
          <div class="cfg-form-group">
            <label class="cfg-label">${escapeHtml(f.label)}</label>
            ${f.type === 'select' ? `<select class="cfg-input" data-field="${f.key}" ${f.disabled ? 'disabled' : ''}>
              ${f.options.map(o => `<option value="${o}" ${o === f.value ? 'selected' : ''}>${o}</option>`).join('')}
            </select>` : f.type === 'textarea' ? `<textarea class="cfg-input cfg-textarea" data-field="${f.key}" placeholder="${escapeHtml(f.placeholder || '')}">${escapeHtml(f.value || '')}</textarea>` :
            f.type === 'models' ? `<div class="cfg-models-container" data-field="${f.key}">
              ${f.value.map((m, i) => renderModelRow(m, i)).join('')}
              <button class="cfg-model-add" type="button">${t('model.add')}</button>
            </div>` :
            f.password ? `<div class="cfg-password-wrap"><input class="cfg-input" type="text" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" autocomplete="off" style="-webkit-text-security:disc" ${f.disabled ? 'disabled' : ''}><button class="cfg-eye-btn" type="button" tabindex="-1" aria-label="Toggle visibility">${eyeSvg}</button></div>` :
            `<input class="cfg-input" type="text" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" ${f.disabled ? 'disabled' : ''}>`}
          </div>
        `).join('')}
      </div>
      <div class="cfg-modal-actions">
        <button class="cfg-btn cfg-btn-cancel" id="cfg-modal-cancel">${t('modal.cancel')}</button>
        <button class="cfg-btn cfg-btn-save" id="cfg-modal-save">${t('settings.save')}</button>
      </div>
    </div>`;

  document.body.appendChild(overlay);

  // Wire up models add/remove
  overlay.querySelectorAll('.cfg-model-add').forEach(btn => {
    btn.addEventListener('click', () => {
      const container = btn.parentElement;
      const row = document.createElement('div');
      row.className = 'cfg-model-row';
      row.innerHTML = renderModelRowContent();
      container.insertBefore(row, btn);
      row.querySelector('.cfg-model-remove').addEventListener('click', () => row.remove());
      row.querySelector('.cfg-model-id').focus();
    });
  });
  overlay.querySelectorAll('.cfg-model-remove').forEach(btn => {
    btn.addEventListener('click', () => btn.closest('.cfg-model-row').remove());
  });

  // Wire up eye toggle
  overlay.querySelectorAll('.cfg-eye-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const input = btn.parentElement.querySelector('input');
      const isMasked = input.style.webkitTextSecurity !== 'none';
      input.style.webkitTextSecurity = isMasked ? 'none' : 'disc';
      btn.innerHTML = isMasked ? eyeOffSvg : eyeSvg;
    });
  });

  const close = () => overlay.remove();
  document.getElementById('cfg-modal-cancel').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  document.getElementById('cfg-modal-save').addEventListener('click', () => {
    const values = {};
    overlay.querySelectorAll('[data-field]:not([data-field="models"])').forEach(el => {
      values[el.dataset.field] = el.value;
    });
    // Collect models
    const modelsContainer = overlay.querySelector('[data-field="models"]');
    if (modelsContainer) {
      values.models = [];
      modelsContainer.querySelectorAll('.cfg-model-row').forEach(row => {
        const id = row.querySelector('.cfg-model-id').value.trim();
        if (!id) return;
        values.models.push({
          id,
          maxTokens: parseInt(row.querySelector('.cfg-model-max').value) || 131072,
          contextWindow: parseInt(row.querySelector('.cfg-model-ctx').value) || 200000,
        });
      });
    }
    onConfirm(values);
    close();
  });
}

function renderModelRowContent(m) {
  const id = m ? escapeHtml(m.id) : '';
  const max = m ? m.maxTokens : '';
  const ctx = m ? m.contextWindow : '';
  return `<input class="cfg-input cfg-model-id" type="text" value="${id}" placeholder="${t('model.idPlaceholder')}">
<input class="cfg-input cfg-model-max" type="number" value="${max}" placeholder="${t('model.maxTokensPlaceholder')}">
<input class="cfg-input cfg-model-ctx" type="number" value="${ctx}" placeholder="${t('model.contextPlaceholder')}">
<button class="cfg-model-remove" type="button" title="${t('provider.remove')}">&times;</button>`;
}

function renderModelRow(m, index) {
  return `<div class="cfg-model-row">${renderModelRowContent(m)}</div>`;
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// ---------- Session Sidebar ----------
export function formatSessionTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  const now = new Date();
  const isToday = d.toDateString() === now.toDateString();
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const isYesterday = d.toDateString() === yesterday.toDateString();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (isToday) return hh + ':' + mm;
  if (isYesterday) return t('time.yesterday') + ' ' + hh + ':' + mm;
  return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + hh + ':' + mm;
}

/** Render a single session item into the given container. */
function renderOneSessionItem(s, container, opts = {}) {
  const inFolder = opts.inFolder || false;
  const item = document.createElement('div');
  const isSelected = state.selectedSessionIds.has(s.id);
  item.className = 'session-item'
    + (inFolder ? ' in-folder' : '')
    + (s.id === state.activeSessionId || s.id === state.secondarySessionId ? ' active' : '')
    + (state.pinnedSessions.has(s.id) ? ' pinned' : '')
    + (isSelected ? ' selected' : '');
  item.dataset.id = s.id;
  item.draggable = true;
  item.addEventListener('dragstart', (e) => {
    const selected = state.selectedSessionIds;
    if (selected.size > 1 && selected.has(s.id)) {
      e.dataTransfer.setData('text/plain', 'batch:' + [...selected].join(','));
    } else if (selected.size === 1 && selected.has(s.id)) {
      e.dataTransfer.setData('text/plain', 'batch:' + s.id);
    } else {
      e.dataTransfer.setData('text/plain', s.id);
    }
    e.dataTransfer.effectAllowed = 'move';
    item.classList.add('dragging');
    showDeleteZone();
  });
  item.addEventListener('dragend', () => {
    item.classList.remove('dragging');
    document.querySelectorAll('.folder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
    hideDeleteZone();
  });
  const statusCls = getSessionStatusClass(s.id);
  const draft = state.sessionInputDrafts[s.id];
  const draftHtml = draft && draft.text
    ? '<div class="session-draft">' + escapeHtml(draft.text.replace(/\n/g, ' ').slice(0, 60)) + '</div>'
    : '';
  const deleteBtnHtml = '<button class="session-delete" title="' + t('session.delete') + '"><i data-lucide="x"></i></button>';
  item.innerHTML =
    '<div class="session-info">' +
    '<div class="session-name">' + escapeHtml(s.name) + '</div>' +
    (draftHtml || '<div class="session-time">' + formatSessionTime(s.updatedAt || s.createdAt) + '</div>') +
    '</div>' +
    '<div class="session-status ' + statusCls + '">' +
    '<div class="status-spinner"><i data-lucide="loader-2"></i></div>' +
    '<div class="status-compact-spinner"><i data-lucide="minimize-2"></i></div>' +
    '<div class="status-dot"></div>' +
    '</div>' +
    deleteBtnHtml;
  // Double-click to rename (only in normal mode)
  const nameEl = item.querySelector('.session-name');
  if (state.selectedSessionIds.size === 0) {
    nameEl.addEventListener('dblclick', (e) => {
      e.stopPropagation();
      nameEl.contentEditable = true;
      nameEl.focus();
      const range = document.createRange();
      range.selectNodeContents(nameEl);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    });
    const finishRename = () => {
      nameEl.contentEditable = false;
      const newName = nameEl.textContent.trim();
      if (newName && newName !== s.name) {
        sendWs({type: 'renameSession', sessionId: s.id, name: newName});
      } else {
        nameEl.textContent = s.name;
      }
    };
    nameEl.addEventListener('blur', finishRename);
    nameEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        if (e.isComposing || e.keyCode === 229) return;
        e.preventDefault(); nameEl.blur();
      }
      if (e.key === 'Escape') { nameEl.textContent = s.name; nameEl.blur(); }
    });
  }
  if (state.selectedSessionIds.size === 0) {
    item.querySelector('.session-delete').onclick = (e) => {
      e.stopPropagation();
      if (typeof window.__showDeleteModal === 'function') window.__showDeleteModal(s.id, s.name);
    };
  }
  item.onclick = (e) => {
    if (e.target.closest('.session-name[contenteditable="true"]')) return;
    if (e.target.closest('.session-delete')) return;
    const hasSelection = state.selectedSessionIds.size > 0;
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      toggleSessionSelection(s.id);
    } else if (e.shiftKey && state.lastSelectedSessionId) {
      e.preventDefault();
      if (!hasSelection) state.lastSelectedSessionId = state.activeSessionId;
      selectSessionRange(state.lastSelectedSessionId, s.id);
    } else if (hasSelection) {
      // In multi-select: click selected item does nothing; click unselected exits multi-select and switches
      if (state.selectedSessionIds.has(s.id)) {
        // do nothing, keep selection
      } else {
        exitBatchMode();
        if (s.id !== state.activeSessionId) switchSession(s.id);
      }
    } else {
      clearActiveFolder();
      const isJarvis = (s.agentName || 'Nebula') === 'Jarvis';
      if (isJarvis) {
        // Jarvis session → main area, close secondary if open
        closeSecondaryPanel();
        if (s.id !== state.activeSessionId) {
          switchSession(s.id);
        } else {
          state.unreadSessions.delete(s.id);
          state.markedUnreadSessions.delete(s.id);
          persistUnread();
          persistMarkedUnread();
          updateSessionStatus(s.id);
        }
      } else {
        // Non-Jarvis session → secondary panel (画板)
        openInSecondary(s);
      }
    }
  };
  item.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    if (state.selectedSessionIds.size > 0) {
      showBatchCtxMenu(e.clientX, e.clientY);
    } else {
      showSessionCtxMenu(e.clientX, e.clientY, s.id);
    }
  });
  container.appendChild(item);
}

export function renderSessionSidebar(sessionData, activeId) {
  state.sessions = sessionData || [];
  const prevActiveId = state.activeSessionId;
  if (activeId) state.activeSessionId = activeId;
  // If active session changed (new session, agent session, delete active), reset chat area
  if (activeId && activeId !== prevActiveId) {
    saveInputDraft(prevActiveId);
    resetChatForActiveSession();
    restoreInputDraft(activeId);
  }

  // Clean up localStorage for deleted sessions
  const currentIds = new Set((sessionData || []).map(s => s.id));
  try {
    const all = (() => { try { return JSON.parse(localStorage.getItem(LS_SESSIONS_KEY) || '{}'); } catch(e) { return {}; } })();
    let changed = false;
    for (const key of Object.keys(all)) {
      if (!currentIds.has(key)) { delete all[key]; changed = true; }
    }
    if (changed) { try { localStorage.setItem(LS_SESSIONS_KEY, JSON.stringify(all)); } catch(e) {} }
  } catch(e) {}
  const sessionList = state.dom.sessionList;
  sessionList.innerHTML = '';

  // Setup unified drop handler on sessionList (once)
  // VS Code-style: folder-wrapper is a "scope" — drop inside = move into folder, drop outside = move to root
  if (!sessionList._dropSetup) {
    sessionList._dropSetup = true;
    sessionList.addEventListener('dragover', (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
    });
    sessionList.addEventListener('drop', (e) => {
      e.preventDefault();
      // Clean up any drag-over highlights
      document.querySelectorAll('.folder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
      const data = e.dataTransfer.getData('text/plain');
      if (!data) return;
      // Determine target folder: closest folder-wrapper = move into, none = move to root
      const folderWrapper = e.target.closest('.folder-wrapper');
      const targetFolderId = folderWrapper ? folderWrapper.dataset.folderId : null;
      if (data.startsWith('folder:')) {
        const fid = data.slice(7);
        if (fid && fid !== targetFolderId) {
          sendWs({ type: 'moveFolder', folderId: fid, parentId: targetFolderId });
        }
      } else if (data.startsWith('batch:')) {
        const ids = data.slice(6).split(',').filter(Boolean);
        ids.forEach(sid => sendWs({ type: 'moveSessionToFolder', sessionId: sid, folderId: targetFolderId }));
      } else {
        sendWs({ type: 'moveSessionToFolder', sessionId: data, folderId: targetFolderId });
      }
    });
    // Click on empty area clears active folder (VSCode-style)
    sessionList.addEventListener('click', (e) => {
      if (e.target === sessionList) {
        clearActiveFolder();
      }
    });
  }

  // Sort sessions helper
  const sortSessions = (list) => [...list].sort((a, b) => {
    const pa = state.pinnedSessions.has(a.id) ? 1 : 0;
    const pb = state.pinnedSessions.has(b.id) ? 1 : 0;
    if (pb !== pa) return pb - pa;
    return (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0);
  });

  // Group sessions and folders by agent
  const allFolders = state.folders || [];
  const agentGroups = {};
  (state.sessions || []).forEach(s => {
    const agent = s.agentName || 'Nebula';
    if (!agentGroups[agent]) agentGroups[agent] = { sessions: [], folders: [] };
    agentGroups[agent].sessions.push(s);
  });
  allFolders.forEach(f => {
    const agent = f.agentName || 'Nebula';
    if (!agentGroups[agent]) agentGroups[agent] = { sessions: [], folders: [] };
    agentGroups[agent].folders.push(f);
  });

  // Sort agent groups: Jarvis first, then alphabetical
  const agentOrder = Object.keys(agentGroups).sort((a, b) => {
    if (a === 'Jarvis') return -1;
    if (b === 'Jarvis') return 1;
    return a.localeCompare(b);
  });

  // Render each agent group
  agentOrder.forEach(agentName => {
    const group = agentGroups[agentName];

    // Agent group header (only when multiple agents exist)
    if (agentOrder.length > 1) {
      const header = document.createElement('div');
      header.className = 'agent-group-header';
      header.textContent = agentName;
      sessionList.appendChild(header);
    }

    // Separate sessions by folder within this agent group
    const groupFolderIds = new Set(group.folders.map(f => f.id));
    const rootSessions = [];
    const sessionsByFolder = {};
    group.sessions.forEach(s => {
      if (s.folderId && groupFolderIds.has(s.folderId)) {
        if (!sessionsByFolder[s.folderId]) sessionsByFolder[s.folderId] = [];
        sessionsByFolder[s.folderId].push(s);
      } else {
        rootSessions.push(s);
      }
    });

    const sortedRoots = sortSessions(rootSessions);
    Object.keys(sessionsByFolder).forEach(fid => {
      sessionsByFolder[fid] = sortSessions(sessionsByFolder[fid]);
    });

    const agentFolders = group.folders.filter(f => !f.parentId);
    agentFolders.sort((a, b) => a.name.localeCompare(b.name));

    // Build mixed list of root sessions and folders
    const mixedItems = [];
    sortedRoots.forEach(s => {
      mixedItems.push({ type: 'session', data: s, pinned: state.pinnedSessions.has(s.id), time: s.updatedAt || s.createdAt || 0 });
    });
    agentFolders.forEach(f => {
      mixedItems.push({ type: 'folder', data: f, pinned: state.pinnedFolders.has(f.id) });
    });
    mixedItems.sort((a, b) => {
      if (b.pinned !== a.pinned) return b.pinned ? 1 : -1;
      if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
      if (a.type === 'folder') return a.data.name.localeCompare(b.data.name);
      return b.time - a.time;
    });

    // Render items
    let hasPinned = false;
    let renderedDivider = false;
    mixedItems.forEach(item => {
      if (item.pinned) {
        hasPinned = true;
      } else if (hasPinned && !renderedDivider) {
        const divider = document.createElement('div');
        divider.className = 'session-divider';
        sessionList.appendChild(divider);
        renderedDivider = true;
      }
      if (item.type === 'session') {
        renderOneSessionItem(item.data, sessionList, { inFolder: false });
      } else {
        renderFolderItem(item.data, sessionsByFolder[item.data.id] || [], sessionList);
      }
    });
  });
  if (typeof lucide !== 'undefined') lucide.createIcons();
  // Update header session name + brand
  const sessionNameEl = state.dom.sessionNameEl;
  const active = state.sessions.find(s => s.id === state.activeSessionId);
  if (active) {
    sessionNameEl.textContent = active.name;
    sessionNameEl.style.display = '';
    // Update header brand based on session's agent
    const agentName = active.agentName || 'Nebula';
    updateHeaderBrand(agentName);
  } else {
    sessionNameEl.textContent = '';
    sessionNameEl.style.display = 'none';
  }
  computeAgentStates();
}

// Persist sessionInputDrafts to localStorage (strip transient skill/ask mode)
function persistDrafts() {
  const stripped = {};
  for (const [sid, draft] of Object.entries(state.sessionInputDrafts)) {
    stripped[sid] = { text: draft.text, attachments: draft.attachments };
  }
  try { localStorage.setItem(LS_DRAFTS_KEY, JSON.stringify(stripped)); } catch(e) {}
}

// Save current input box content as draft for the given session
export function saveInputDraft(sessionId) {
  if (!sessionId || !state.dom.input) return;
  const text = state.dom.input.value;
  const attachments = state.pendingAttachments;
  const skillMode = state.skillMode ? {
    name: state.skillModeName,
    desc: state.skillModeDesc,
    argHint: state.skillModeArgHint,
  } : null;
  const askMode = !!state.askMode;
  if (text || attachments.length > 0 || skillMode || askMode) {
    state.sessionInputDrafts[sessionId] = {
      text,
      attachments: JSON.parse(JSON.stringify(attachments)),
      skillMode,
      askMode,
    };
  } else {
    delete state.sessionInputDrafts[sessionId];
  }
  persistDrafts();
}

// Restore input box content from draft for the given session
function restoreInputDraft(sessionId) {
  const input = state.dom.input;
  if (!input) return;
  const draft = state.sessionInputDrafts[sessionId];
  if (draft) {
    input.value = draft.text;
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    state.pendingAttachments = draft.attachments || [];
    // Restore attachment preview if import available
    import('./chat.js').then(({ renderAttachmentPreview }) => {
      renderAttachmentPreview();
    });
  } else {
    input.value = '';
    input.style.height = 'auto';
    state.pendingAttachments = [];
    if (state.dom.attPreview) state.dom.attPreview.innerHTML = '';
  }
  // Restore skill/ask mode for this session
  import('./input.js').then(({ applyInputModes }) => {
    applyInputModes(draft?.skillMode, draft?.askMode);
  });
}

// Reset chat area for the current activeSessionId (used after session list updates)
export function resetChatForActiveSession() {
  state.aiText = '';
  state.currentAiBubble = null;
  state.currentThinkingBubble = null;
  state.thinkingText = '';
  state.agentBubbles = {};
  state.activeAgentId = null;
  Object.keys(state.sessionToolCards).forEach(sid => {
    if (state.sessionToolCards[sid]) state.sessionToolCards[sid].remove();
  });
  state.sessionToolCards = {};
  state.historyOffset = 0;
  state.historyHasMore = false;
  state.historyLoading = false;
  state.pendingInitialLoad = true;
  cancelThinkingRAF();
  state.dom.chat.innerHTML = '';
  // Clear any history loader/end indicators (they live in chat, but belt-and-suspenders)
  state.dom.chat.querySelectorAll('.history-loader, .history-end').forEach(el => el.remove());

  const sid = state.activeSessionId;
  const isStreaming = state.busySessionIds.has(sid);

  // Request history from backend (historyPage handler in main.js will render it)
  if (sid) {
    sendWs({ type: 'getHistory', sessionId: sid, limit: 50 });
  }

  // Restore buffered thinking + text.
  // Two sources: in-progress streaming (sessionTexts/sessionThinkingBuffers) and
  // completed-but-not-yet-in-history (pendingRestore).
  const pendingData = state.pendingRestore[sid];
  const thinkingBuf = state.sessionThinkingBuffers[sid] || pendingData?.thinking;
  const textBuf = state.sessionTexts[sid] || pendingData?.text;
  if (thinkingBuf || textBuf) {
    const chat = state.dom.chat;

    // Restore thinking bubble (collapsed if text also exists or turn is complete)
    if (thinkingBuf) {
      state.thinkingText = thinkingBuf;
      const hasText = !!textBuf;
      const done = hasText || !isStreaming;
      const row = document.createElement('div');
      row.className = 'row ai thinking-row';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai thinking-bubble' + (done ? ' thinking-done' : '');
      const label = document.createElement('div');
      label.className = 'thinking-label' + (done ? ' collapsible' : '');
      label.textContent = t('chat.thinkingLabel');
      const content = document.createElement('div');
      content.className = 'thinking-content';
      if (done) {
        content.style.display = 'none';
        label.classList.add('expanded');
        label.onclick = () => {
          const visible = content.style.display !== 'none';
          content.style.display = visible ? 'none' : '';
          label.classList.toggle('expanded', !visible);
        };
      }
      content.innerHTML = renderMarkdownWithMath(state.thinkingText) + (!done ? '<span class="cursor"></span>' : '');
      bubble.appendChild(label);
      bubble.appendChild(content);
      row.appendChild(bubble);
      chat.appendChild(row);
      state.currentThinkingBubble = done ? null : bubble;
    }

    // Restore text bubble
    if (textBuf) {
      state.aiText = textBuf;
      const row = document.createElement('div');
      row.className = 'row ai';
      state.currentAiBubble = document.createElement('div');
      state.currentAiBubble.className = 'bubble ai';
      state.currentAiBubble.innerHTML = renderMarkdownWithMath(state.aiText) + (isStreaming ? '<span class="cursor"></span>' : '');
      row.appendChild(state.currentAiBubble);
      chat.appendChild(row);
      // If turn is complete (not streaming), this is a completed bubble — clear refs
      if (!isStreaming) {
        state.currentAiBubble = null;
        state.aiText = '';
      }
    }

    smartScroll();
  }

  // Interactive AskUser is re-rendered in the historyPage handler (main.js) after history loads

  // Re-create pending tool card if this session has an in-progress tool
  // Must be done here (not just in historyPage handler) so the spinner is visible
  // immediately when switching back, before the async getHistory roundtrip completes.
  if (state.sessionPendingTools[sid]) {
    renderToolPending(state.sessionPendingTools[sid].label, sid);
  }

  // Update busy UI
  const isBusy = isStreaming;
  const { input, sendBtn, stopBtn } = state.dom;
  input.disabled = isBusy;
  sendBtn.style.display = isBusy ? 'none' : 'flex';
  stopBtn.style.display = isBusy ? 'flex' : 'none';

  // Clear any residual status from previous session
  const statusWrap = state.dom.statusWrap;
  statusWrap.classList.remove('on');
  stopSpinner();

  if (!isBusy) input.focus();

  // Restore cached task list for this session (or clear if none)
  renderTaskList(state.sessionTasks[sid] || []);
  // Restore background task indicator for this session
  if (state.updateBgTasksUI) state.updateBgTasksUI();
}

export function switchSession(sessionId) {
  // Clear unread + marked unread for this session
  state.unreadSessions.delete(sessionId);
  state.markedUnreadSessions.delete(sessionId);
  persistUnread();
  persistMarkedUnread();
  // Save draft for the session we're leaving
  saveInputDraft(state.activeSessionId);
  const prevActiveId = state.activeSessionId;
  // Switch active session
  state.activeSessionId = sessionId;
  // Clear memory cache so new session fetches fresh content
  clearMemoryCache();
  // Update sidebar status for both sessions (activeSessionId has changed,
  // so getSessionStatusClass may return different values, e.g. compacting → busy or vice versa)
  updateSessionStatus(sessionId);
  if (prevActiveId && prevActiveId !== sessionId) updateSessionStatus(prevActiveId);
  resetChatForActiveSession();
  // Restore input draft for the new session
  restoreInputDraft(sessionId);
  // Task list and bg task indicator are restored inside resetChatForActiveSession()
  sendWs({type: 'switchSession', sessionId});
  // Refresh scheduled tasks for the new session
  if (typeof refreshScheduledTasks === 'function') refreshScheduledTasks(sessionId);
}

export function deleteSession(sessionId) {
  delete state.sessionInputDrafts[sessionId];
  state.unreadSessions.delete(sessionId);
  state.markedUnreadSessions.delete(sessionId);
  state.pinnedSessions.delete(sessionId);
  persistUnread();
  persistMarkedUnread();
  persistPinned();
  persistDrafts();
  sendWs({type: 'deleteSession', sessionId});
}

export function deleteFolder(folderId) {
  // Remove from local state immediately so UI reflects deletion without waiting for server.
  // Cascading: collect all descendant folder IDs (children, grandchildren, etc.)
  const allFolders = state.folders || [];
  const descendantIds = (fid) => {
    const children = allFolders.filter(f => f.parentId === fid).map(f => f.id);
    return children.concat(children.flatMap(descendantIds));
  };
  const idsToRemove = new Set([folderId, ...descendantIds(folderId)]);
  state.folders = allFolders.filter(f => !idsToRemove.has(f.id));
  renderSessionSidebar(state.sessions, state.activeSessionId);
  sendWs({type: 'deleteFolder', folderId});
}

// ---------- Session status state machine ----------
// Priority: attention > compacting-bg > busy > marked unread > unread > idle
// Only one indicator visible at a time, controlled by a single CSS class.
// Attention takes priority over compacting so AskUser/permission prompts are visible
// even while the session is still technically compacting.
//
// Compact logic:
//   - Active session compacting: green compact info shown in chat area status bar;
//     sidebar shows 'busy' (falls through).
//   - Non-active session compacting: sidebar shows 'compacting' spinner.

function getSessionStatusClass(sessionId) {
  if (state.attentionSessions.has(sessionId)) return 'attention';
  // Only show 'compacting' spinner in sidebar for non-active sessions.
  // Active session shows compact info in the chat area status bar instead.
  if (state.compactingSessionIds.has(sessionId) && sessionId !== state.activeSessionId) return 'compacting';
  if (state.busySessionIds.has(sessionId)) return 'busy';
  if (state.markedUnreadSessions.has(sessionId) || state.unreadSessions.has(sessionId)) return 'unread';
  return '';
}

function persistMarkedUnread() {
  try {
    localStorage.setItem('nebflow_marked_unread', JSON.stringify([...state.markedUnreadSessions]));
  } catch(e) {}
}

function persistUnread() {
  try {
    localStorage.setItem('nebflow_unread', JSON.stringify([...state.unreadSessions]));
  } catch(e) {}
}

export { persistUnread };

function persistPinned() {
  try {
    localStorage.setItem('nebflow_pinned', JSON.stringify([...state.pinnedSessions]));
  } catch(e) {}
}

// ---------- Session context menu ----------

let activeCtxMenu = null;

function dismissCtxMenu() {
  if (activeCtxMenu) { activeCtxMenu.remove(); activeCtxMenu = null; }
}

function showSessionCtxMenu(x, y, sessionId) {
  dismissCtxMenu();
  const isPinned = state.pinnedSessions.has(sessionId);
  const session = state.sessions.find(s => s.id === sessionId);
  const currentFolderId = session?.folderId || null;

  // Build folder submenu HTML — unified list, no agent filtering
  const agentFolders = state.folders || [];
  let folderSubHtml = '';
  if (currentFolderId !== null) {
    folderSubHtml += '<div class="ctx-sub" data-folder-id="">' + t('ctx.removeFolder') + '</div>';
  }
  if (agentFolders.length > 0) {
    agentFolders.forEach(f => {
      if (f.id !== currentFolderId) {
        folderSubHtml += '<div class="ctx-sub" data-folder-id="' + f.id + '">' + escapeHtml(f.name) + '</div>';
      }
    });
  }
  if (!folderSubHtml) {
    folderSubHtml = '<div class="ctx-disabled">' + t('ctx.noFolders') + '</div>';
  }

  const menu = document.createElement('div');
  menu.className = 'session-ctx-menu';
  menu.innerHTML =
    '<div class="ctx-item" data-action="mark-unread">' + t('ctx.markUnread') + '</div>' +
    '<div class="ctx-item" data-action="toggle-pin">' + (isPinned ? t('ctx.unpin') : t('ctx.pin')) + '</div>' +
    '<div class="ctx-separator"></div>' +
    '<div class="ctx-item ctx-has-sub" data-action="move-to-folder">' + t('ctx.moveToFolder') + ' <span style="float:right;color:var(--color-frame-text-muted)">▸</span></div>' +
    '<div class="ctx-submenu" id="ctx-folder-submenu">' + folderSubHtml + '</div>';
  document.body.appendChild(menu);
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';

  menu.querySelector('[data-action="mark-unread"]').addEventListener('click', () => {
    state.markedUnreadSessions.add(sessionId);
    state.unreadSessions.delete(sessionId);
    persistUnread();
    persistMarkedUnread();
    updateSessionStatus(sessionId);
    dismissCtxMenu();
  });
  menu.querySelector('[data-action="toggle-pin"]').addEventListener('click', () => {
    if (isPinned) state.pinnedSessions.delete(sessionId);
    else state.pinnedSessions.add(sessionId);
    persistPinned();
    renderSessionSidebar(state.sessions, state.activeSessionId);
    dismissCtxMenu();
  });

  // Submenu interactions
  const subTrigger = menu.querySelector('[data-action="move-to-folder"]');
  const subMenu = menu.querySelector('#ctx-folder-submenu');
  let subTimer = null;

  subTrigger.addEventListener('mouseenter', () => {
    subTimer = setTimeout(() => { subMenu.style.display = 'block'; }, 100);
  });
  subTrigger.addEventListener('mouseleave', () => {
    if (subTimer) clearTimeout(subTimer);
  });
  subMenu.addEventListener('mouseenter', () => {
    if (subTimer) clearTimeout(subTimer);
  });
  subMenu.addEventListener('mouseleave', () => {
    subMenu.style.display = 'none';
  });

  subMenu.querySelectorAll('.ctx-sub').forEach(el => {
    el.addEventListener('click', () => {
      const fid = el.dataset.folderId;
      sendWs({ type: 'moveSessionToFolder', sessionId, folderId: fid || null });
      dismissCtxMenu();
    });
  });

  activeCtxMenu = menu;
}

// ---------- Batch Selection Context Menu ----------

function showBatchCtxMenu(x, y) {
  dismissCtxMenu();
  const count = state.selectedSessionIds.size;
  const allCount = state.sessions.length;
  const menu = document.createElement('div');
  menu.className = 'session-ctx-menu';
  menu.innerHTML =
    '<div class="ctx-item" data-action="batch-select-all">' + (count === allCount ? t('ctx.deselectAll') : t('ctx.selectAll', { count: allCount })) + '</div>' +
    '<div class="ctx-separator"></div>' +
    '<div class="ctx-item" data-action="batch-delete">' + t('ctx.batchDelete', { count }) + '</div>' +
    '<div class="ctx-item" data-action="batch-cancel">' + t('ctx.batchCancel') + '</div>';
  document.body.appendChild(menu);
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';

  menu.querySelector('[data-action="batch-select-all"]').addEventListener('click', () => {
    const allIds = state.sessions.map(s => s.id);
    const allSelected = allIds.every(id => state.selectedSessionIds.has(id));
    if (allSelected) {
      state.selectedSessionIds.clear();
    } else {
      allIds.forEach(id => state.selectedSessionIds.add(id));
    }
    state.lastSelectedSessionId = null;
    updateBatchToolbar();
    renderSessionSidebar(state.sessions, state.activeSessionId);
    dismissCtxMenu();
  });
  menu.querySelector('[data-action="batch-delete"]').addEventListener('click', () => {
    if (state.selectedSessionIds.size > 0) {
      showBatchDeleteModal();
    }
    dismissCtxMenu();
  });
  menu.querySelector('[data-action="batch-cancel"]').addEventListener('click', () => {
    exitBatchMode();
    dismissCtxMenu();
  });
  activeCtxMenu = menu;
}

// ---------- Batch Selection Logic ----------

export function toggleSessionSelection(sessionId) {
  if (state.selectedSessionIds.has(sessionId)) {
    state.selectedSessionIds.delete(sessionId);
  } else {
    state.selectedSessionIds.add(sessionId);
  }
  state.lastSelectedSessionId = sessionId;
  updateBatchToolbar();
  // Full re-render to keep selection visuals consistent with Shift+select
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

function selectSessionRange(fromId, toId) {
  const fromSession = state.sessions.find(s => s.id === fromId);
  const toSession = state.sessions.find(s => s.id === toId);
  if (!fromSession || !toSession) return;

  const fromFolder = fromSession.folderId || null;
  const toFolder = toSession.folderId || null;

  if (fromFolder !== toFolder) {
    // Different levels — just select both, no range
    state.selectedSessionIds.add(fromId);
    state.selectedSessionIds.add(toId);
  } else {
    // Same level — select range within this level only
    const sameLevel = state.sessions.filter(s => (s.folderId || null) === fromFolder);
    sameLevel.sort((a, b) => {
      if (!fromFolder) {
        const pa = state.pinnedSessions.has(a.id) ? 1 : 0;
        const pb = state.pinnedSessions.has(b.id) ? 1 : 0;
        if (pb !== pa) return pb - pa;
      }
      return (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0);
    });
    const fromIdx = sameLevel.findIndex(s => s.id === fromId);
    const toIdx = sameLevel.findIndex(s => s.id === toId);
    if (fromIdx === -1 || toIdx === -1) return;
    const start = Math.min(fromIdx, toIdx);
    const end = Math.max(fromIdx, toIdx);
    for (let i = start; i <= end; i++) {
      state.selectedSessionIds.add(sameLevel[i].id);
    }
  }
  state.lastSelectedSessionId = toId;
  updateBatchToolbar();
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

export function exitBatchMode() {
  state.selectedSessionIds.clear();
  state.lastSelectedSessionId = null;
  updateBatchToolbar();
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

function updateBatchToolbar() {
  // Floating toolbar removed — batch actions are via drag-to-delete-zone or context menu
  const count = state.selectedSessionIds.size;
  let bar = document.getElementById('multi-select-bar');
  if (count === 0 && bar) bar.remove();
}

let _deleteZone = null;

function showDeleteZone() {
  const panel = document.getElementById('panel-sessions');
  if (!panel) return;
  if (_deleteZone) return;
  const zone = document.createElement('div');
  zone.className = 'delete-drop-zone';
  const count = state.selectedSessionIds.size;
  zone.innerHTML =
    '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
    '<span>' + (count > 1 ? t('session.dropDeleteCount', { count }) : t('session.dropDelete')) + '</span>';
  zone.addEventListener('dragover', (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    zone.classList.add('active');
  });
  zone.addEventListener('dragleave', () => {
    zone.classList.remove('active');
  });
  zone.addEventListener('drop', (e) => {
    e.preventDefault();
    zone.classList.remove('active');
    const data = e.dataTransfer.getData('text/plain');
    // Handle folder drop
    if (data.startsWith('folder:')) {
      const fid = data.slice(7);
      const f = state.folders.find(x => x.id === fid);
      if (f && typeof window.__showDeleteFolderModal === 'function') {
        window.__showDeleteFolderModal(fid, f.name);
      }
      return;
    }
    let ids = [];
    if (data.startsWith('batch:')) {
      ids = data.slice(6).split(',').filter(Boolean);
    } else if (data) {
      ids = [data];
    }
    if (ids.length > 0) {
      // If already in selection mode, show batch delete confirmation
      if (state.selectedSessionIds.size > 1) {
        showBatchDeleteModal();
      } else {
        // Single drag — confirm delete
        const sid = ids[0];
        const s = state.sessions.find(x => x.id === sid);
        if (s && typeof window.__showDeleteModal === 'function') {
          window.__showDeleteModal(sid, s.name);
        }
      }
    }
  });
  panel.appendChild(zone);
  _deleteZone = zone;
}

function hideDeleteZone() {
  if (_deleteZone) {
    _deleteZone.remove();
    _deleteZone = null;
  }
}

export function batchDeleteSelected() {
  const ids = [...state.selectedSessionIds];
  if (ids.length === 0) return;
  sendWs({ type: 'batchDeleteSessions', sessionIds: ids });
  // Clean up local state
  ids.forEach(id => {
    delete state.sessionInputDrafts[id];
    state.unreadSessions.delete(id);
    state.markedUnreadSessions.delete(id);
    state.pinnedSessions.delete(id);
  });
  persistUnread();
  persistMarkedUnread();
  persistPinned();
  persistDrafts();
  exitBatchMode();
}

// Keyboard shortcuts for multi-select
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && state.selectedSessionIds.size > 0) {
    e.preventDefault();
    exitBatchMode();
  }
  if (e.key === 'Delete' && state.selectedSessionIds.size > 0) {
    e.preventDefault();
    showBatchDeleteModal();
  }
});

// Dismiss on click outside
document.addEventListener('click', (e) => {
  if (activeCtxMenu && !activeCtxMenu.contains(e.target)) dismissCtxMenu();
});
document.addEventListener('contextmenu', (e) => {
  if (activeCtxMenu && !activeCtxMenu.contains(e.target)) dismissCtxMenu();
}, true);

function updateFolderStatus(folderId) {
  // Update this folder's DOM element if it exists (may be absent when parent is collapsed)
  const folderEl = state.dom.sessionList.querySelector(`.folder-item[data-folder-id="${folderId}"]`);
  if (folderEl) {
    const el = folderEl.querySelector('.folder-status');
    if (el) el.className = 'folder-status ' + getFolderStatusClass(folderId);
  }
  // Always cascade up to parent folder — even if this folder's DOM element is absent,
  // the status should still propagate to ancestors that ARE rendered.
  const folder = (state.folders || []).find(f => f.id === folderId);
  if (folder && folder.parentId) {
    updateFolderStatus(folder.parentId);
  }
}

export function updateSessionStatus(sessionId) {
  if (!sessionId) return;
  const item = state.dom.sessionList.querySelector(`.session-item[data-id="${sessionId}"]`);
  if (item) {
    const el = item.querySelector('.session-status');
    if (el) el.className = 'session-status ' + getSessionStatusClass(sessionId);
  }
  // Cascade update parent folder status if session belongs to one
  const session = (state.sessions || []).find(s => s.id === sessionId);
  if (session && session.folderId) {
    updateFolderStatus(session.folderId);
  }
}

/** Show/hide the attention indicator for a session in the sidebar. */
export function setSessionAttention(sessionId, attention) {
  if (!sessionId) return;
  if (attention) state.attentionSessions.add(sessionId);
  else state.attentionSessions.delete(sessionId);
  updateSessionStatus(sessionId);
  // Also update agent notification dot for attention (yellow dot for askUser)
  const agentName = state.sessionAgentMap[sessionId];
  if (agentName && agentName !== state.selectedAgent) {
    if (attention) {
      state.agentUnreadCounts[agentName] = (state.agentUnreadCounts[agentName] || 0) + 1;
    } else {
      const cur = state.agentUnreadCounts[agentName] || 0;
      if (cur > 0) state.agentUnreadCounts[agentName] = cur - 1;
    }
    updateAgentNotificationDotExternal(agentName);
  }
}

// Update agent notification dot (callable from other modules)
function updateAgentNotificationDotExternal(agentName) {
  const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
  if (!el) return;
  const count = state.agentUnreadCounts[agentName] || 0;
  let dot = el.querySelector('.agent-notif-dot');
  if (count > 0) {
    if (!dot) {
      dot = document.createElement('div');
      dot.className = 'agent-notif-dot';
      el.appendChild(dot);
    }
  } else if (dot) {
    dot.remove();
  }
}

/** Update a session's activity timestamp without reordering it.
 *  Visual indicators (bold/unread dot) are handled by updateSessionStatus separately.
 *  Root sessions never move above folders — sorting is handled by renderSessionList.
 *  In-folder sessions update their folder's latest activity time for folder-level sorting. */
function markSessionActivity(sessionId) {
  // Don't update if session is busy (in progress)
  if (state.busySessionIds.has(sessionId)) return;

  // Update updatedAt in state.sessions so future renders stay sorted
  const session = state.sessions.find(s => s.id === sessionId);
  if (!session) return;
  session.updatedAt = Date.now();

  const sessionList = state.dom.sessionList;
  const item = sessionList.querySelector(`.session-item[data-id="${sessionId}"]`);
  if (!item) return;

  // Update the time display to reflect the new activity
  const timeEl = item.querySelector('.session-time');
  if (timeEl) timeEl.textContent = formatSessionTime(session.updatedAt);

  // In-folder sessions: also update the folder's latestActivity so the folder
  // itself sorts correctly among root sessions on next render.
  if (item.classList.contains('in-folder')) {
    const folderEl = item.closest('.folder-section');
    if (folderEl) {
      const folderId = folderEl.dataset.id;
      if (folderId && state.folders[folderId]) {
        state.folders[folderId].latestActivity = session.updatedAt;
      }
    }
  }
}

// Listen for state changes from other modules (dispatched via CustomEvent)
window.addEventListener('session-busy', (e) => {
  updateSessionStatus(e.detail.sessionId);
  computeAgentStates();
});

window.addEventListener('session-attention', (e) => {
  setSessionAttention(e.detail.sessionId, e.detail.attention);
  computeAgentStates();
});

window.addEventListener('session-unread', (e) => {
  updateSessionStatus(e.detail.sessionId);
  markSessionActivity(e.detail.sessionId);
});

window.addEventListener('session-compacting', (e) => {
  updateSessionStatus(e.detail.sessionId);
  computeAgentStates();
});

/** Refresh header model-info bar for the active session. */
export function initHeaderModelInfo() {
  if (typeof state.updateHeaderModelInfo === 'function') {
    state.updateHeaderModelInfo();
  }
}

/** Build a human-readable breadcrumb path for a folder ID, e.g. "项目A / 模块B". */
export function getTargetPath(folderId) {
  if (!folderId) return null;
  const allFolders = state.folders || [];
  const parts = [];
  let current = allFolders.find(f => f.id === folderId);
  while (current) {
    parts.unshift(current.name);
    current = current.parentId ? allFolders.find(f => f.id === current.parentId) : null;
  }
  return parts.length > 0 ? parts.join(' / ') : null;
}

/** Create a new folder inline (no prompt). Inserts an editable row into the sidebar. */
export function createNewFolder(parentFolderId) {
  let container;
  if (parentFolderId) {
    // Ensure parent folder is expanded so children container exists
    if (!state.expandedFolders.has(parentFolderId)) {
      state.expandedFolders.add(parentFolderId);
      persistExpandedFolders();
      renderSessionSidebar(state.sessions, state.activeSessionId);
    }
    const wrapper = state.dom.sessionList.querySelector('.folder-wrapper[data-folder-id="' + parentFolderId + '"]');
    container = wrapper ? wrapper.querySelector('.folder-children') : null;
  } else {
    container = document.getElementById('session-list');
  }
  if (!container) return;

  // Remove any existing inline-new-folder input
  container.querySelectorAll('.folder-new-row').forEach(el => el.remove());

  const targetPath = getTargetPath(parentFolderId) || t('path.root');

  const row = document.createElement('div');
  row.className = 'folder-new-row';
  row.innerHTML =
    '<div class="creation-path">' + escapeHtml(targetPath) + ' &gt;</div>' +
    '<div class="folder-new-row-inner">' +
    '<div class="folder-new-arrow"><i data-lucide="chevron-right"></i></div>' +
    '<div class="folder-new-icon"><i data-lucide="folder"></i></div>' +
    '<input class="folder-new-input" placeholder="' + t('folder.newPlaceholder') + '">' +
    '</div>';
  container.insertBefore(row, container.firstChild);

  const input = row.querySelector('.folder-new-input');
  if (typeof lucide !== 'undefined') lucide.createIcons();
  input.focus();

  const cancel = () => row.remove();
  const confirm = () => {
    const name = input.value.trim();
    row.remove();
    if (name) {
      const payload = { type: 'createFolder', name, agentName: state.selectedAgent || 'Nebula' };
      if (parentFolderId) payload.parentId = parentFolderId;
      import('./ws.js').then(({ sendWs }) => sendWs(payload));
    }
  };

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); confirm(); }
    if (e.key === 'Escape') { e.preventDefault(); cancel(); }
  });
  input.addEventListener('blur', () => {
    // Small delay so Enter can fire first
    setTimeout(() => {
      if (document.activeElement !== input && row.parentNode) cancel();
    }, 150);
  });
}

// ===== Folder helpers =====

function persistExpandedFolders() {
  try {
    localStorage.setItem('nebflow_expanded_folders', JSON.stringify([...state.expandedFolders]));
  } catch(e) {}
}

function persistPinnedFolders() {
  try {
    localStorage.setItem('nebflow_pinned_folders', JSON.stringify([...state.pinnedFolders]));
  } catch(e) {}
}

// Collect all descendant folder IDs (including the folder itself) recursively
function getAllDescendantFolderIds(folderId) {
  const allFolders = state.folders || [];
  const result = new Set([folderId]);
  const children = allFolders.filter(f => f.parentId === folderId);
  for (const child of children) {
    const desc = getAllDescendantFolderIds(child.id);
    desc.forEach(id => result.add(id));
  }
  return result;
}

function getFolderStatusClass(folderId, sessions) {
  const allSessions = sessions || (state.sessions || []);
  const descendantIds = getAllDescendantFolderIds(folderId);
  const folderSessions = allSessions.filter(s => descendantIds.has(s.folderId));
  for (const s of folderSessions) {
    if (state.attentionSessions.has(s.id)) return 'attention';
  }
  for (const s of folderSessions) {
    if (state.compactingSessionIds.has(s.id) && s.id !== state.activeSessionId) return 'compacting';
  }
  for (const s of folderSessions) {
    if (state.busySessionIds.has(s.id)) return 'busy';
  }
  for (const s of folderSessions) {
    if (state.markedUnreadSessions.has(s.id) || state.unreadSessions.has(s.id)) return 'unread';
  }
  return '';
}

function renderFolderItem(folder, sessions, container) {
  const folderWrapper = document.createElement('div');
  folderWrapper.className = 'folder-wrapper';
  folderWrapper.dataset.folderId = folder.id;

  const isExpanded = state.expandedFolders.has(folder.id);
  const isPinned = state.pinnedFolders.has(folder.id);

  const folderEl = document.createElement('div');
  folderEl.className = 'folder-item'
    + (isPinned ? ' pinned' : '')
    + (state.activeFolderId === folder.id ? ' active' : '');
  folderEl.dataset.folderId = folder.id;
  folderEl.draggable = true;

  // Always use state.sessions (not the passed-in direct children) so subfolder
  // sessions are included in the status calculation.
  const statusCls = getFolderStatusClass(folder.id);

  folderEl.innerHTML =
    '<div class="folder-arrow">' +
      (isExpanded
        ? '<i data-lucide="chevron-down"></i>'
        : '<i data-lucide="chevron-right"></i>') +
    '</div>' +
    '<div class="folder-icon"><i data-lucide="folder"></i></div>' +
    '<div class="folder-name">' + escapeHtml(folder.name) + '</div>' +
    (folder.projectRoot ? '<span class="folder-badge" title="' + escapeHtml(folder.projectRoot) + '"><i data-lucide="hard-drive" style="width:12px;height:12px"></i></span>' : '') +
    '<div class="folder-status ' + statusCls + '">' +
      '<div class="status-spinner"><i data-lucide="loader-2"></i></div>' +
      '<div class="status-compact-spinner"><i data-lucide="minimize-2"></i></div>' +
      '<div class="folder-status-dot"></div>' +
    '</div>' +
    '<button class="folder-delete" title="' + t('sidebar.folderDelete') + '"><i data-lucide="x"></i></button>';

  // Drag: folder itself can be dragged (into another folder) or to delete zone
  folderEl.addEventListener('dragstart', (e) => {
    e.dataTransfer.setData('text/plain', 'folder:' + folder.id);
    e.dataTransfer.effectAllowed = 'move';
    folderEl.classList.add('dragging');
    showDeleteZone();
  });
  folderEl.addEventListener('dragend', () => {
    folderEl.classList.remove('dragging');
    document.querySelectorAll('.folder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
    hideDeleteZone();
  });

  // VSCode-style: click selects folder + expand/collapse
  folderEl.addEventListener('click', (e) => {
    if (e.target.closest('.folder-delete')) return;
    if (e.target.closest('.folder-name[contenteditable="true"]')) return;
    setActiveFolder(folder.id);
    toggleFolder(folder.id);
  });

  // Right-click context menu
  folderEl.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    showFolderCtxMenu(e.clientX, e.clientY, folder.id);
  });

  // Double-click to rename folder (inline, same as session)
  const folderNameEl = folderEl.querySelector('.folder-name');
  const startFolderRename = () => {
    folderNameEl.contentEditable = true;
    folderNameEl.focus();
    const range = document.createRange();
    range.selectNodeContents(folderNameEl);
    const sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  };
  folderNameEl.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    startFolderRename();
  });
  const finishFolderRename = () => {
    folderNameEl.contentEditable = false;
    const newName = folderNameEl.textContent.trim();
    if (newName && newName !== folder.name) {
      sendWs({ type: 'renameFolder', folderId: folder.id, name: newName });
    } else {
      folderNameEl.textContent = folder.name;
    }
  };
  folderNameEl.addEventListener('blur', finishFolderRename);
  folderNameEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      if (e.isComposing || e.keyCode === 229) return;
      e.preventDefault(); folderNameEl.blur();
    }
    if (e.key === 'Escape') { folderNameEl.textContent = folder.name; folderNameEl.blur(); }
  });

  // Delete button
  folderEl.querySelector('.folder-delete').addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof window.__showDeleteFolderModal === 'function') {
      window.__showDeleteFolderModal(folder.id, folder.name);
    }
  });

  // Dragover highlight for folder scope
  folderEl.addEventListener('dragover', (e) => {
    folderEl.classList.add('drag-over');
  });
  folderEl.addEventListener('dragleave', () => {
    folderEl.classList.remove('drag-over');
  });

  folderWrapper.appendChild(folderEl);

  // Render children if expanded (sub-folders first, then sessions)
  if (isExpanded) {
    const childrenContainer = document.createElement('div');
    childrenContainer.className = 'folder-children';

    // Rules.md entry — only shown when rules exist for this folder
    if (state.foldersWithRules && state.foldersWithRules.has(folder.id)) {
      const rulesItem = document.createElement('div');
      rulesItem.className = 'session-item in-folder rules-item';
      rulesItem.innerHTML =
        '<div class="session-icon"><i data-lucide="file-text" style="width:14px;height:14px;color:var(--color-text-muted)"></i></div>' +
        '<div class="session-name">rules.md</div>';
      rulesItem.addEventListener('click', (e) => {
        e.stopPropagation();
        openRulesEditor(folder.id, folder.name);
      });
      childrenContainer.appendChild(rulesItem);
    }

    // Sub-folders — alphabetically sorted
    const subFolders = (state.folders || [])
      .filter(f => f.parentId === folder.id)
      .sort((a, b) => a.name.localeCompare(b.name));
    subFolders.forEach(sub => {
      const subSessions = (state.sessions || []).filter(s => s.folderId === sub.id)
        .sort((a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0));
      renderFolderItem(sub, subSessions, childrenContainer);
    });

    // Sessions inside this folder
    sessions.forEach(s => {
      renderOneSessionItem(s, childrenContainer, { inFolder: true });
    });
    // Dragover highlight for expanded children area
    childrenContainer.addEventListener('dragover', (e) => {
      folderEl.classList.add('drag-over');
    });
    childrenContainer.addEventListener('dragleave', (e) => {
      if (!childrenContainer.contains(e.relatedTarget)) {
        folderEl.classList.remove('drag-over');
      }
    });
    folderWrapper.appendChild(childrenContainer);
  }

  container.appendChild(folderWrapper);
}

export function toggleFolder(folderId) {
  if (state.expandedFolders.has(folderId)) {
    state.expandedFolders.delete(folderId);
  } else {
    state.expandedFolders.add(folderId);
  }
  persistExpandedFolders();
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

function showFolderCtxMenu(x, y, folderId) {
  dismissCtxMenu();
  const isPinned = state.pinnedFolders.has(folderId);
  const folder = state.folders.find(f => f.id === folderId);
  const hasParent = folder && folder.parentId;
  const menu = document.createElement('div');
  menu.className = 'session-ctx-menu';
  let html =
    '<div class="ctx-item" data-action="toggle-pin">' + (isPinned ? t('ctx.unpin') : t('ctx.pin')) + '</div>' +
    '<div class="ctx-item" data-action="rename">' + t('ctx.rename') + '</div>' +
    '<div class="ctx-item" data-action="new-subfolder">' + t('ctx.newSubfolder') + '</div>';
  // Project root — available for all folders
  html += '<div class="ctx-separator"></div>' +
    '<div class="ctx-item" data-action="set-project-root">' + t('ctx.setProjectRoot') + '</div>';
  // Rules — dynamic: create if not exists, edit if exists
  const hasRules = state.foldersWithRules && state.foldersWithRules.has(folderId);
  html += '<div class="ctx-item" data-action="edit-rules">' + (hasRules ? t('ctx.editRules') : t('ctx.createRules')) + '</div>';
  if (hasParent) {
    html += '<div class="ctx-item" data-action="move-out">' + t('ctx.moveOut') + '</div>';
  }
  html +=
    '<div class="ctx-separator"></div>' +
    '<div class="ctx-item" data-action="delete" style="color:var(--color-error)">' + t('ctx.deleteFolder') + '</div>';
  menu.innerHTML = html;

  menu.querySelector('[data-action="toggle-pin"]').addEventListener('click', () => {
    if (isPinned) state.pinnedFolders.delete(folderId);
    else state.pinnedFolders.add(folderId);
    persistPinnedFolders();
    renderSessionSidebar(state.sessions, state.activeSessionId);
    dismissCtxMenu();
  });

  menu.querySelector('[data-action="rename"]').addEventListener('click', () => {
    dismissCtxMenu();
    if (!folder) return;
    // Trigger inline edit on the folder name element
    const folderEl = state.dom.sessionList.querySelector(`.folder-item[data-folder-id="${folderId}"]`);
    const nameEl = folderEl ? folderEl.querySelector('.folder-name') : null;
    if (nameEl) {
      nameEl.contentEditable = true;
      nameEl.focus();
      const range = document.createRange();
      range.selectNodeContents(nameEl);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
  });

  menu.querySelector('[data-action="new-subfolder"]').addEventListener('click', () => {
    dismissCtxMenu();
    createNewFolder(folderId);
  });

  // Set project root — opens directory picker
  menu.querySelector('[data-action="set-project-root"]').addEventListener('click', () => {
    if (!folder) return;
    openPathPicker(folderId, folder.projectRoot);
    dismissCtxMenu();
  });

  // Edit rules
  menu.querySelector('[data-action="edit-rules"]').addEventListener('click', () => {
    if (!folder) return;
    openRulesEditor(folderId, folder.name);
    dismissCtxMenu();
  });

  if (hasParent) {
    menu.querySelector('[data-action="move-out"]').addEventListener('click', () => {
      sendWs({ type: 'moveFolder', folderId, parentId: null });
      dismissCtxMenu();
    });
  }

  menu.querySelector('[data-action="delete"]').addEventListener('click', () => {
    if (folder && typeof window.__showDeleteFolderModal === 'function') {
      window.__showDeleteFolderModal(folder.id, folder.name);
    }
    dismissCtxMenu();
  });

  document.body.appendChild(menu);
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';
  activeCtxMenu = menu;
}

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

function openPathPicker(folderId, currentRoot) {
  pathPickerFolderId = folderId;
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

function closePathPicker() {
  pathPickerFolderId = null;
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
    if (!pathPickerFolderId) return;
    sendWs({ type: 'setFolderProjectRoot', folderId: pathPickerFolderId, projectRoot: pathPickerCurrentPath });
    closePathPicker();
  });
  document.getElementById('path-picker-clear').addEventListener('click', () => {
    if (!pathPickerFolderId) return;
    sendWs({ type: 'setFolderProjectRoot', folderId: pathPickerFolderId, projectRoot: null });
    closePathPicker();
  });
}

export function moveSessionToFolder(sessionId, folderId) {
  sendWs({ type: 'moveSessionToFolder', sessionId, folderId });
}

