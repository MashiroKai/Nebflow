// chatSearch.js — Chat history search modal.
//
// Header button opens a centered modal (no dimming backdrop, no blur).
// Searches message history via the REST API (backend is the source of
// truth — localStorage is only a truncated cache):
//   GET /api/sessions?includeUnindexed=1 → session list incl. unindexed
//                                          delegate/subtask/dag sessions
//   GET /api/sessions/{id}/history → full UiMessage list per session
//
// Filters: keyword (case-insensitive substring), agent (session-level, from
// the agentName of each session), tool name (clean names extracted from the
// rich labels present in the searched scope), date range (applies to
// messages that carry a timestamp; tool messages have none and are excluded
// when a date range is set).
// Clicking a result switches to that session and scrolls to the message.

import state from './state.js';
import { chatViews } from './chatView.js';
import { switchToSession } from './sidebar.js';
import { t } from './i18n.js';
import { escapeHtml } from './utils.js';

const MAX_RESULTS = 200;
const FETCH_CONCURRENCY = 4;

let initialized = false;
let lastResults = [];

// ── Init ───────────────────────────────────────────────────
export function initChatSearch() {
  if (initialized) return;
  initialized = true;

  document.getElementById('search-btn')?.addEventListener('click', openSearchModal);
  document.getElementById('search-modal-close')?.addEventListener('click', closeSearchModal);
  const overlay = document.getElementById('search-overlay');
  overlay?.addEventListener('click', (e) => {
    if (e.target === overlay) closeSearchModal();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && overlay?.classList.contains('on')) closeSearchModal();
  });
  document.getElementById('search-go')?.addEventListener('click', runSearch);
  document.getElementById('search-keyword')?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') runSearch();
  });
  // Result click — event delegation on the results container
  document.getElementById('search-results')?.addEventListener('click', (e) => {
    const el = e.target.closest('.search-result');
    if (!el) return;
    const res = lastResults[parseInt(el.dataset.idx, 10)];
    if (res) jumpToResult(res);
  });
}

// ── Modal open/close ───────────────────────────────────────
export function openSearchModal() {
  const overlay = document.getElementById('search-overlay');
  if (!overlay) return;
  renderStaticLabels();
  overlay.classList.add('on');
  const kw = document.getElementById('search-keyword');
  if (kw) kw.focus();
}

export function closeSearchModal() {
  document.getElementById('search-overlay')?.classList.remove('on');
}

/** Fill labels/placeholders that depend on the current locale. */
function renderStaticLabels() {
  const scope = document.getElementById('search-scope');
  if (scope) {
    scope.innerHTML =
      `<option value="current">${escapeHtml(t('search.scopeCurrent'))}</option>` +
      `<option value="all">${escapeHtml(t('search.scopeAll'))}</option>`;
  }
  const tool = document.getElementById('search-tool');
  if (tool) {
    tool.innerHTML = `<option value="">${escapeHtml(t('search.allTypes'))}</option>`;
  }
  const agent = document.getElementById('search-agent');
  if (agent) {
    agent.innerHTML = `<option value="">${escapeHtml(t('search.allAgents'))}</option>`;
  }
  const go = document.getElementById('search-go');
  if (go) go.textContent = t('search.go');
  const status = document.getElementById('search-status');
  if (status) status.textContent = '';
  const results = document.getElementById('search-results');
  if (results) results.innerHTML = `<div class="search-hint">${escapeHtml(t('search.hint'))}</div>`;
}

// ── Data fetching ──────────────────────────────────────────
function authHeaders() {
  const tok = localStorage.getItem('nebflow_token') || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

async function fetchSessionList() {
  // includeUnindexed=1: also returns delegate-*/subtask-*/dag-* sessions whose
  // ui.json exists on disk but which never entered the session index.
  const resp = await fetch('/api/sessions?includeUnindexed=1', { headers: authHeaders() });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const data = await resp.json();
  return data.sessions || [];
}

async function fetchHistory(sessionId) {
  const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/history`, { headers: authHeaders() });
  if (!resp.ok) return [];
  const data = await resp.json();
  return data.messages || [];
}

/** Run async tasks with a concurrency limit. */
async function mapLimit(items, limit, fn, onProgress) {
  const out = [];
  let idx = 0;
  let done = 0;
  async function worker() {
    while (idx < items.length) {
      const item = items[idx++];
      out.push(await fn(item));
      done++;
      onProgress?.(done, items.length);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

// ── Message normalization ──────────────────────────────────
// Extract searchable plain text per UiMessage type (see shared/protocol.scala
// UiMessage encoder for the JSON shape).
function normalizeMessage(m) {
  switch (m.type) {
    case 'user':
      return { kind: 'user', text: m.text || '', ts: m.timestamp || 0 };
    case 'ai':
      return { kind: 'ai', text: (m.text || '') + (m.thinking ? '\n' + m.thinking : ''), ts: m.timestamp || 0 };
    case 'tool':
      return {
        kind: 'tool',
        tool: m.label || '',
        text: [m.summary, m.input, m.content].filter(Boolean).join('\n'),
        ts: 0,
      };
    case 'agent':
      return { kind: 'ai', text: m.text || '', ts: 0 };
    case 'ask':
      return { kind: 'ai', text: [m.question, m.answer].filter(Boolean).join('\n'), ts: 0 };
    default:
      return null; // system/askUser/askPermission etc. are not searchable
  }
}

// ── Search ─────────────────────────────────────────────────
async function runSearch() {
  const kw = (document.getElementById('search-keyword')?.value || '').trim();
  const scope = document.getElementById('search-scope')?.value || 'current';
  const toolSel = document.getElementById('search-tool')?.value || '';
  const agentSel = document.getElementById('search-agent')?.value || '';
  const fromVal = document.getElementById('search-date-from')?.value || '';
  const toVal = document.getElementById('search-date-to')?.value || '';
  const fromTs = fromVal ? new Date(fromVal + 'T00:00:00').getTime() : 0;
  const toTs = toVal ? new Date(toVal + 'T23:59:59.999').getTime() : 0;
  const statusEl = document.getElementById('search-status');
  const resultsEl = document.getElementById('search-results');
  if (!statusEl || !resultsEl) return;

  resultsEl.innerHTML = '';
  statusEl.textContent = t('search.searching');

  try {
    // Resolve the sessions to search
    let sessions;
    if (scope === 'current') {
      const sid = state.activeSessionId;
      if (!sid) {
        statusEl.textContent = t('search.noSession');
        return;
      }
      const local = (state.sessions || []).find(s => s.id === sid);
      sessions = [{ id: sid, name: local?.name || sid }];
    } else {
      sessions = await fetchSessionList();
      // Agent filter options come from the FULL list (before filtering)
      populateAgentFilter(sessions, agentSel);
      // Filter at the session level — also skips history fetches for
      // sessions that cannot match.
      if (agentSel) sessions = sessions.filter(s => agentNameOf(s) === agentSel);
    }

    // Fetch histories (bounded concurrency), with progress for the all-scope
    const showProgress = sessions.length > 1;
    const perSession = await mapLimit(sessions, FETCH_CONCURRENCY, async (s) => {
      const msgs = await fetchHistory(s.id);
      return { session: s, msgs };
    }, showProgress ? (done, total) => {
      statusEl.textContent = t('search.progress', { done, total });
    } : null);

    // Populate the tool filter dropdown from labels actually present
    populateToolFilter(perSession, toolSel);

    // Match
    const kwLower = kw.toLowerCase();
    const results = [];
    for (const { session, msgs } of perSession) {
      for (const raw of msgs) {
        const m = normalizeMessage(raw);
        if (!m) continue;
        if (toolSel === '__any__') {
          if (m.kind !== 'tool') continue;
        } else if (toolSel) {
          if (m.kind !== 'tool' || cleanToolName(m.tool) !== toolSel) continue;
        }
        if ((fromTs || toTs) && m.ts) {
          if (fromTs && m.ts < fromTs) continue;
          if (toTs && m.ts > toTs) continue;
        } else if ((fromTs || toTs) && !m.ts) {
          continue; // no timestamp → cannot satisfy a date range
        }
        if (kwLower && !m.text.toLowerCase().includes(kwLower)) continue;
        results.push({
          sessionId: session.id,
          sessionName: session.name || session.id,
          kind: m.kind,
          tool: m.tool || '',
          text: m.text,
          ts: m.ts,
        });
      }
    }

    // Newest first (timestamped), untimestamped last
    results.sort((a, b) => (b.ts || 0) - (a.ts || 0));
    lastResults = results.slice(0, MAX_RESULTS);

    statusEl.textContent = t('search.results', { n: results.length }) +
      (results.length > MAX_RESULTS ? ` (${t('search.truncated', { n: MAX_RESULTS })})` : '');
    renderResults(resultsEl, kw);
  } catch (e) {
    statusEl.textContent = `${t('search.failed')}: ${e.message}`;
  }
}

// ── Tool name normalization ────────────────────────────────
// ui.json tool labels are RICH display strings, not tool names:
//   "Edit(jarvis.html)\n  (\"/path\")", "Bash\n  (cd \"...\")",
//   "Mail(→Manager) [RESULT]", "TaskUpdate(#1, completed)"
// Deduping/filtering on the full label floods the dropdown with one variant
// per call. Extract the clean tool name — the leading identifier before
// '(' / whitespace / newline.
function cleanToolName(label) {
  const m = /^([A-Za-z_][A-Za-z0-9_-]*)/.exec(label || '');
  return m ? m[1] : (label || '');
}

/** Display name of the agent that owns a session (backend derives it from
 *  the id prefix for unindexed sessions, e.g. "Explorer (delegate)"). */
function agentNameOf(s) {
  return s.agentName || '';
}

/** Rebuild the agent filter options from the fetched session list. */
function populateAgentFilter(sessions, keepValue) {
  const sel = document.getElementById('search-agent');
  if (!sel) return;
  const counts = new Map();
  for (const s of sessions) {
    const name = agentNameOf(s);
    if (name) counts.set(name, (counts.get(name) || 0) + 1);
  }
  const sorted = [...counts.keys()].sort((a, b) => a.localeCompare(b));
  sel.innerHTML =
    `<option value="">${escapeHtml(t('search.allAgents'))}</option>` +
    sorted.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)} (${counts.get(n)})</option>`).join('');
  if ([...sel.options].some(o => o.value === keepValue)) sel.value = keepValue;
}

/** Rebuild the tool filter options from the tool labels in the fetched set.
 *  Options are CLEAN tool names (with call counts), not raw labels. */
function populateToolFilter(perSession, keepValue) {
  const sel = document.getElementById('search-tool');
  if (!sel) return;
  const counts = new Map();
  for (const { msgs } of perSession) {
    for (const raw of msgs) {
      if (raw.type === 'tool' && raw.label) {
        const name = cleanToolName(raw.label);
        if (name) counts.set(name, (counts.get(name) || 0) + 1);
      }
    }
  }
  const sorted = [...counts.keys()].sort();
  sel.innerHTML =
    `<option value="">${escapeHtml(t('search.allTypes'))}</option>` +
    `<option value="__any__">${escapeHtml(t('search.anyTool'))}</option>` +
    sorted.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)} (${counts.get(n)})</option>`).join('');
  // Restore previous selection if still available
  if ([...sel.options].some(o => o.value === keepValue)) sel.value = keepValue;
}

// ── Result rendering ───────────────────────────────────────
function renderResults(container, kw) {
  if (lastResults.length === 0) {
    container.innerHTML = `<div class="search-hint">${escapeHtml(t('search.noResults'))}</div>`;
    return;
  }
  container.innerHTML = lastResults.map((r, i) => {
    const typeBadge = r.kind === 'tool'
      ? `${escapeHtml(t('search.typeTool'))} · ${escapeHtml(cleanToolName(r.tool))}`
      : r.kind === 'user'
        ? escapeHtml(t('search.typeUser'))
        : escapeHtml(t('search.typeAi'));
    const time = r.ts ? formatTime(r.ts) : '';
    return `<div class="search-result" data-idx="${i}">
      <div class="search-result-head">
        <span class="search-result-session">${escapeHtml(r.sessionName)}</span>
        <span class="search-result-type type-${r.kind}">${typeBadge}</span>
        <span class="search-result-time">${escapeHtml(time)}</span>
      </div>
      <div class="search-result-preview">${highlightPreview(r.text, kw)}</div>
    </div>`;
  }).join('');
}

/** Preview snippet centered on the first keyword match, with <mark> highlights. */
function highlightPreview(text, kw) {
  const MAX = 180;
  const flat = (text || '').replace(/\s+/g, ' ').trim();
  let start = 0;
  if (kw) {
    const idx = flat.toLowerCase().indexOf(kw.toLowerCase());
    if (idx > 60) start = idx - 60;
  }
  const slice = flat.slice(start, start + MAX);
  const prefix = start > 0 ? '…' : '';
  const suffix = start + MAX < flat.length ? '…' : '';
  if (!kw) return prefix + escapeHtml(slice) + suffix;

  // Index-based marking on the raw slice (escape each segment separately)
  const kwLower = kw.toLowerCase();
  let html = '';
  let pos = 0;
  const lower = slice.toLowerCase();
  while (pos < slice.length) {
    const idx = lower.indexOf(kwLower, pos);
    if (idx === -1) { html += escapeHtml(slice.slice(pos)); break; }
    html += escapeHtml(slice.slice(pos, idx));
    html += '<mark>' + escapeHtml(slice.slice(idx, idx + kw.length)) + '</mark>';
    pos = idx + kw.length;
  }
  return prefix + html + suffix;
}

function formatTime(ts) {
  const d = new Date(ts);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (sameDay) return hh + ':' + mm;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hh}:${mm}`;
}

// ── Jump to message ────────────────────────────────────────
// DOM matching normalizes away whitespace and non-alphanumeric characters so
// markdown formatting (**bold**, `code`, list bullets) doesn't break the match.
const stripForMatch = (s) => (s || '').replace(/[^\p{L}\p{N}]/gu, '').toLowerCase();

function jumpToResult(res) {
  closeSearchModal();
  switchToSession(res.sessionId);
  scrollToMessage(res, 0);
}

function scrollToMessage(res, attempt) {
  const chat = chatViews.primary?.dom?.chat;
  if (!chat) return;
  const snippet = stripForMatch(res.text).slice(0, 40);
  if (snippet.length >= 4) {
    const rows = chat.querySelectorAll('.row, .tool-card');
    for (const el of rows) {
      if (stripForMatch(el.textContent).includes(snippet)) {
        el.scrollIntoView({ block: 'center', behavior: 'smooth' });
        el.classList.add('search-hit-flash');
        setTimeout(() => el.classList.remove('search-hit-flash'), 1800);
        return;
      }
    }
  }
  // History loads asynchronously after the session switch — retry briefly.
  if (attempt < 12) {
    setTimeout(() => scrollToMessage(res, attempt + 1), 250);
  } else {
    window.__showToast?.(t('search.jumpFailed'), 'error');
  }
}

// Re-apply labels when the locale changes while the modal is open
window.addEventListener('locale-changed', () => {
  if (document.getElementById('search-overlay')?.classList.contains('on')) renderStaticLabels();
});
