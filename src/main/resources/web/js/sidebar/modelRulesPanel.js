// modelRulesPanel.js — 设置页「模型规则」面板（P5，设计 §4）。
//
// 形态（作者令）：左 = 规则脚本框图（按规则分组的节点卡 + 源码切换，源码经 utils.highlightCode 渲染），
// 右 = 编写区（草稿 → staging 差异预览 → 应用）。顶栏三态 switch：follow / slots / rules（仅 rules 时规则接管）。
// 保存期校验由后端 PUT /api/model-rules 执行（P2 validate），错误带行号回显到右栏。
//
// 未做（见终报）：右栏的 ask 式 LLM 起草未接 createAskPanel 门面；当前为纯文本草稿编辑。

import { t } from './../i18n.js';
import { escapeHtml, highlightCode } from './../utils.js';
import { getAuthToken } from './../neblink.js';

const MODES = ['follow', 'slots', 'rules'];

async function api(path, opts = {}) {
  /** @type {Record<string, string>} */
  const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
  const tok = getAuthToken();
  if (tok) headers['Authorization'] = `Bearer ${tok}`;
  return fetch(path, { ...opts, headers });
}

/** 行级 LCS 差异（脚本短，O(n·m) 足够）。返回 [{kind:'eq'|'add'|'del', text}]。 */
export function lineDiff(oldText, newText) {
  const a = oldText.split('\n');
  const b = newText.split('\n');
  const n = a.length;
  const m = b.length;
  const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const out = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) { out.push({ kind: 'eq', text: a[i] }); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { out.push({ kind: 'del', text: a[i] }); i++; }
    else { out.push({ kind: 'add', text: b[j] }); j++; }
  }
  while (i < n) out.push({ kind: 'del', text: a[i++] });
  while (j < m) out.push({ kind: 'add', text: b[j++] });
  return out;
}

function diagramHtml(diagram) {
  if (!diagram || !Array.isArray(diagram.nodes) || diagram.nodes.length === 0) {
    return `<div class="mr-empty">${escapeHtml(t('modelRules.diagramEmpty'))}</div>`;
  }
  const byRule = new Map();
  for (const nd of diagram.nodes) {
    if (!byRule.has(nd.ruleId)) byRule.set(nd.ruleId, []);
    byRule.get(nd.ruleId).push(nd);
  }
  const parts = [];
  for (const [rule, nodes] of byRule) {
    const cards = nodes.map((nd) =>
      `<button type="button" class="mr-node mr-kind-${escapeHtml(nd.kind)}" data-src-line="${nd.srcLine}">
         <span class="mr-node-kind">${escapeHtml(nd.kind)}</span>
         <span class="mr-node-label">${escapeHtml(nd.label)}</span>
         <span class="mr-node-line">L${nd.srcLine}</span>
       </button>`).join('');
    parts.push(`<div class="mr-rule"><div class="mr-rule-name">${escapeHtml(rule)}</div><div class="mr-rule-nodes">${cards}</div></div>`);
  }
  const edges = (diagram.edges || []).map((e) =>
    `<li>${escapeHtml(e.from)} → ${escapeHtml(e.to)} <em>${escapeHtml(e.kind)}</em></li>`).join('');
  return parts.join('') + (edges ? `<ul class="mr-edges">${edges}</ul>` : '');
}

/**
 * 挂载「模型规则」节到宿主。返回卸载函数（重渲时先卸后挂，不叠监听）。
 * @param {HTMLElement|null} host
 * @returns {() => void}
 */
export function mountModelRulesSection(host) {
  if (!host) return () => {};
  let saved = '';
  let mode = 'follow';
  let view = 'diagram';
  let diagram = null;
  let loadError = null;
  let errors = [];
  let status = '';
  let draft = '';

  const render = () => {
    const stagedDiff = lineDiff(saved, draft).filter((d) => d.kind !== 'eq');
    const diffHtml = stagedDiff.length === 0
      ? `<div class="mr-empty">${escapeHtml(t('modelRules.noChanges'))}</div>`
      : `<pre class="mr-diff">${stagedDiff.map((d) =>
          `<div class="mr-diff-${d.kind}">${d.kind === 'add' ? '+' : '-'} ${escapeHtml(d.text)}</div>`).join('')}</pre>`;
    const errHtml = errors.length
      ? `<ul class="mr-errors">${errors.map((e) =>
          `<li data-err-line="${e.line}">${escapeHtml(t('modelRules.errLine', { line: e.line }))}：${escapeHtml(e.message)}</li>`).join('')}</ul>`
      : '';
    const srcHtml = highlightCode(saved, 'model-rules.dsl') ||
      `<pre class="mr-source">${escapeHtml(saved)}</pre>`;
    host.innerHTML = `
      <div class="mr-root" data-mode="${mode}">
        ${loadError ? `<div class="mr-error">${escapeHtml(loadError)}</div>` : ''}
        <div class="mr-cols">
          <section class="mr-left">
            <div class="mr-viewtabs">
              <button type="button" data-view="diagram" class="${view === 'diagram' ? 'is-on' : ''}">${escapeHtml(t('modelRules.diagram'))}</button>
              <button type="button" data-view="source" class="${view === 'source' ? 'is-on' : ''}">${escapeHtml(t('modelRules.source'))}</button>
            </div>
            ${view === 'diagram' ? diagramHtml(diagram) : `<div class="mr-source-wrap">${srcHtml}</div>`}
          </section>
          <section class="mr-right">
            <textarea class="mr-draft" spellcheck="false" aria-label="${escapeHtml(t('modelRules.draftLabel'))}">${escapeHtml(draft)}</textarea>
            <div class="mr-actions">
              <button type="button" data-act="stage">${escapeHtml(t('modelRules.stage'))}</button>
              <button type="button" data-act="apply">${escapeHtml(t('modelRules.apply'))}</button>
            </div>
            <div class="mr-status">${escapeHtml(status)}</div>
            ${errHtml}
            <div class="mr-staging-title">${escapeHtml(t('modelRules.stagedDiff'))}</div>
            ${diffHtml}
          </section>
        </div>
      </div>`;
    bind();
  };

  const onClick = async (ev) => {
    const el = ev.target instanceof Element ? ev.target.closest('button') : null;
    if (!el || !host.contains(el)) return;
    if (el.dataset.modeSet) return setMode(el.dataset.modeSet);
    if (el.dataset.view) { view = el.dataset.view; return render(); }
    if (el.dataset.srcLine) {
      view = 'source';
      render();
      // 滚到节点对应行：hljs 输出为单块，按平均行高估算行位（近似；纯文本回退时同样成立）。
      const line = Number(el.dataset.srcLine);
      const wrap = host.querySelector('.mr-source-wrap');
      if (wrap) {
        const total = Math.max(1, saved.split('\n').length);
        const lh = wrap.scrollHeight / total;
        wrap.scrollTop = Math.max(0, (line - 1) * lh - lh * 2);
        wrap.setAttribute('data-focus-line', String(line));
      }
      return;
    }
    if (el.dataset.act === 'stage') {
      const dr = host.querySelector('.mr-draft');
      if (dr) draft = dr.value;
      status = '';
      errors = [];
      return render();
    }
    if (el.dataset.act === 'apply') {
      const dr = host.querySelector('.mr-draft');
      if (dr) draft = dr.value;
      await apply();
    }
  };

  const bind = () => {
    /* 事件走宿主委托，无需逐节点绑定 */
  };

  const load = async () => {
    try {
      const r = await api('/api/model-rules');
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const j = await r.json();
      saved = j.source || '';
      draft = saved;
      mode = MODES.includes(j.mode) ? j.mode : 'follow';
      diagram = j.diagram || null;
      loadError = j.error ? `${t('modelRules.loadError')}: ${j.error}` : null;
    } catch (e) {
      loadError = `${t('modelRules.loadFailed')}: ${e && e.message ? e.message : e}`;
    }
    render();
  };

  const setMode = async (m) => {
    const r = await api('/api/model-rules/mode', { method: 'PUT', body: JSON.stringify({ mode: m }) });
    if (r.ok) { mode = m; status = t('modelRules.modeSaved'); }
    else status = `${t('modelRules.saveFailed')}: HTTP ${r.status}`;
    render();
  };

  const apply = async () => {
    const r = await api('/api/model-rules', { method: 'PUT', body: JSON.stringify({ source: draft }) });
    if (r.ok) {
      saved = draft;
      errors = [];
      status = t('modelRules.applied');
      await load();
      return;
    }
    errors = [];
    if (r.status === 422) {
      const j = await r.json().catch(() => ({}));
      errors = Array.isArray(j.errors) ? j.errors : [];
      status = t('modelRules.rejected');
    } else {
      status = `${t('modelRules.saveFailed')}: HTTP ${r.status}`;
    }
    render();
  };

  host.addEventListener('click', onClick);
  host.innerHTML = `<div class="mr-empty">${escapeHtml(t('modelRules.loading'))}</div>`;
  void load();

  return () => {
    host.removeEventListener('click', onClick);
    host.innerHTML = '';
  };
}
