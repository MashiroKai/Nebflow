// orbSettingsUI.js — settings "外观" section for the micOrb palette preset
// system (v8.3.0; design 20260902_micorb-presets-design.md §4/§5, author
// rulings 2026-09-02 20:59).
//
// Contents (task ruling):
//   1. 基调 dropdown — the 8 presets, default Neon (ruling ③).
//   2. 状态映射表 — 9 states × preset dropdown (8 presets + Ash); 配色=状态语言.
//   3. 自定义基调 — sparse slot overrides (a/b/c) over a source preset, per
//      theme, with live 84px preview orb (real OrbRenderer instance) and a
//      soft 暗纹/基色 guard hint.
//   4. 重置 — clears the whole selection back to factory defaults.
//
// Every commit goes through orbPresets.saveSaved()/clearSaved(), which
// persist to localStorage and dispatch CHANGE_EVENT — the live orb
// re-resolves its board (uniform-only, no setStateCfg, no flicker).
//
// This module is standalone (no ws/state imports) so test harnesses can
// mount the section without the app shell.

import { t, getLocale } from './i18n.js';
import { OrbRenderer } from './micOrb.js';
import {
  PRESETS, ASH, DEFAULT_BASE, DEFAULT_STATE_MAP,
  presetIdFor, boardForState, boardOf, presetName,
  loadSaved, saveSaved, clearSaved, hexToVec,
} from './orbPresets.js';

/** Locale tag for registry display names. */
function loc() { return (getLocale() || '').startsWith('zh') ? 'zh' : 'en'; }

function presetOptions(selected, includeAsh) {
  const list = includeAsh ? PRESETS.concat([ASH]) : PRESETS;
  return list.map((p) =>
    `<option value="${p.id}"${p.id === selected ? ' selected' : ''}>${presetName(p.id, loc())}</option>`
  ).join('');
}

/** Three tiny swatch dots previewing a preset's dark board. */
function swatches(id) {
  const b = boardOf(id, false, null);
  const hex = (v) => '#' + v.map((x) => Math.round(x * 255).toString(16).padStart(2, '0')).join('');
  return `<span class="orb-swatches">` +
    ['a', 'b', 'c'].map((k) => `<i style="background:${hex(b[k])}"></i>`).join('') +
    `</span>`;
}

function renderCustomSlots(sel, theme) {
  const src = PRESETS.find((p) => p.id === (sel.custom.base || sel.base)) || PRESETS[PRESETS.length - 1];
  const rows = ['a', 'b', 'c'].map((slot) => {
    const effective = (sel.custom[theme][slot]) || src[theme][slot];
    const warn = slotWarn(slot, effective);
    return `<div class="orb-slot-row" data-slot="${slot}">
      <span class="orb-slot-label">${t('settings.appearance.slot' + slot.toUpperCase())}</span>
      <input type="color" class="orb-slot-color" data-slot="${slot}" value="${effective}" aria-label="${t('settings.appearance.slot' + slot.toUpperCase())}">
      <input type="text" class="cfg-input orb-slot-hex" data-slot="${slot}" value="${effective}" spellcheck="false" maxlength="7" aria-label="hex">
      <button type="button" class="cfg-btn cfg-btn-sm orb-slot-restore" data-slot="${slot}" ${sel.custom[theme][slot] ? '' : 'disabled'}>${t('settings.appearance.restore')}</button>
    </div>
    <div class="orb-warn" data-warn="${slot}" style="display:${warn ? 'block' : 'none'}">${warn || ''}</div>`;
  }).join('');
  return rows;
}

/** Soft guard hints (design §5.2): c must stay saturated-dark; a too-dim
 *  boards risk unreadable frozen/offline (tone-lowered) states. */
function slotWarn(slot, hexStr) {
  const v = hexToVec(hexStr);
  if (!v) return null;
  const max = Math.max(...v), min = Math.min(...v);
  const l = (max + min) / 2;
  const s = max === min ? 0 : (max - min) / (1 - Math.abs(2 * l - 1));
  if (slot === 'c' && (l < 0.12 || s < 0.55)) return t('settings.appearance.warnC');
  if (slot === 'a' && l < 0.45) return t('settings.appearance.warnA');
  return null;
}

/** HTML for the section body (wrapped in #orb-appearance-body for in-place
 *  refresh). The section card itself is rendered by sidebar.js. */
export function renderAppearanceSection() {
  const sel = loadSaved();
  const customOn = !!sel.custom;
  const theme = currentCustomTheme();
  const stateRows = ['idle'].concat(Object.keys(DEFAULT_STATE_MAP)).map((k) => {
    const resolved = presetIdFor(k, sel);
    return `<div class="orb-state-row" data-state="${k}">
      <span class="orb-state-name">${t(stateI18nKey(k))}</span>
      ${swatches(resolved)}
      <select class="cfg-select orb-state-select" data-state="${k}" style="width:auto" aria-label="${t(stateI18nKey(k))}">${presetOptions(resolved, true)}</select>
    </div>`;
  }).join('');
  return `<div id="orb-appearance-body">
    <div class="settings-row">
      <span class="settings-label">${t('settings.appearance.orbBase')}</span>
      <select class="cfg-select" id="orb-base-select" style="width:auto">${presetOptions(sel.base, false)}</select>
    </div>
    <div class="cfg-hint">${t('settings.appearance.orbBaseHint')}</div>
    <div class="orb-state-map-title settings-label">${t('settings.appearance.stateMap')}</div>
    <div class="orb-state-map">${stateRows}</div>
    <div class="cfg-hint">${t('settings.appearance.stateMapHint')}</div>
    <div class="settings-row">
      <span class="settings-label">${t('settings.appearance.custom')}</span>
      <div class="toggle ${customOn ? 'on' : ''}" id="orb-custom-toggle" role="switch" aria-checked="${customOn}" tabindex="0"></div>
    </div>
    <div class="cfg-hint">${t('settings.appearance.customHint')}</div>
    <div id="orb-custom-body" ${customOn ? '' : 'hidden'}>
      <div class="orb-custom-controls">
        <span class="orb-slot-label">${t('settings.appearance.customSource')}</span>
        <select class="cfg-select" id="orb-custom-source" style="width:auto">${presetOptions((sel.custom ? sel.custom.base : null) || sel.base, false)}</select>
        <span class="orb-theme-seg" role="group" aria-label="theme">
          <button type="button" class="orb-theme-btn${theme === 'dark' ? ' on' : ''}" data-theme="dark">${t('settings.appearance.customThemeDark')}</button>
          <button type="button" class="orb-theme-btn${theme === 'light' ? ' on' : ''}" data-theme="light">${t('settings.appearance.customThemeLight')}</button>
        </span>
      </div>
      <div id="orb-custom-slots">${customOn ? renderCustomSlots(sel, theme) : ''}</div>
      <div class="orb-preview-wrap">
        <div class="orb-preview-slot" id="orb-preview-slot"></div>
        <span class="cfg-hint">${t('settings.appearance.preview')}</span>
      </div>
    </div>
    <div style="margin-top:8px">
      <button class="cfg-btn" id="orb-appearance-reset">${t('settings.appearance.reset')}</button>
    </div>
  </div>`;
}

function stateI18nKey(k) {
  return { 'nebula-busy': 'chat.micOrb.nebulaBusy', 'bg-agents': 'chat.micOrb.bgAgents',
           'frozen-error': 'chat.micOrb.frozenError', 'mic-error': 'chat.micOrb.micError' }[k]
    || ('chat.micOrb.' + k);
}

/* Which theme's board the custom editor is showing (per session). */
let customThemeSel = null;
function currentCustomTheme() {
  if (!customThemeSel) {
    customThemeSel = !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) ? 'light' : 'dark';
  }
  return customThemeSel;
}

/* Persistent 84px preview renderer — one WebGL context for the lifetime of
   the page; its canvas is MOVED into each freshly rendered section (avoids
   exhausting the browser's ~16-context budget across renderSettings calls). */
let preview = null;
function previewOrb() {
  if (!preview) {
    const canvas = document.createElement('canvas');
    canvas.className = 'orb-preview-canvas';
    canvas.setAttribute('aria-hidden', 'true');
    const renderer = new OrbRenderer(canvas, { size: 84 });
    preview = { canvas, renderer };
  }
  return preview;
}

/** Bind events on a freshly rendered section. `root` is #settings-content
 *  (production) or any container (harnesses). */
export function bindAppearanceEvents(root) {
  const body = root.querySelector ? root.querySelector('#orb-appearance-body') : document.getElementById('orb-appearance-body');
  if (!body) return;
  let sel = loadSaved();
  const commit = () => {
    if (!saveSaved(sel)) window.__showToast?.(t('settings.appearance.saveFailed'), 'error');
  };
  const refresh = () => {
    const parent = body.parentElement;
    parent.innerHTML = renderAppearanceSection();
    bindAppearanceEvents(parent);
  };

  const isLightTheme = () => !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches);

  const mountPreview = () => {
    const slot = body.querySelector('#orb-preview-slot');
    if (!slot) return;
    const { canvas, renderer } = previewOrb();
    slot.appendChild(canvas); // moves the persistent node
    renderer.setTheme(currentCustomTheme() === 'light');
    renderer.setPalette(boardForState('idle', sel, currentCustomTheme() === 'light'));
    renderer.resume();
  };

  // 1. 基调 dropdown — also clears an explicit idle override so the new
  //    基调 is immediately visible on the brand state.
  body.querySelector('#orb-base-select')?.addEventListener('change', function () {
    sel.base = this.value;
    delete sel.map.idle; // 基调 wins for idle unless re-overridden below
    commit();
    refresh();
  });

  // 2. 状态映射 rows.
  body.querySelectorAll('.orb-state-select').forEach((el) => {
    el.addEventListener('change', () => {
      sel.map[el.dataset.state] = el.value;
      commit();
      refresh();
    });
  });

  // 3. Custom palette.
  body.querySelector('#orb-custom-toggle')?.addEventListener('click', function () {
    const on = this.classList.toggle('on');
    this.setAttribute('aria-checked', String(on));
    if (on) {
      if (!sel.custom) sel.custom = { base: sel.base, dark: {}, light: {} };
      sel.custom.base = sel.base;
    } else {
      sel.custom = null;
    }
    commit();
    refresh();
  });
  body.querySelector('#orb-custom-toggle')?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      body.querySelector('#orb-custom-toggle').click();
    }
  });

  body.querySelector('#orb-custom-source')?.addEventListener('change', function () {
    if (!sel.custom) return;
    sel.custom.base = this.value;
    commit();
    refresh();
  });

  body.querySelectorAll('.orb-theme-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      customThemeSel = btn.dataset.theme;
      refresh();
    });
  });

  const syncSlot = (slot, hexStr) => {
    if (!sel.custom) return;
    const theme = currentCustomTheme();
    if (/^#[0-9a-fA-F]{6}$/.test(hexStr)) {
      sel.custom[theme][slot] = hexStr;
      commit();
      const { renderer } = previewOrb();
      renderer.setPalette(boardForState('idle', sel, theme === 'light'));
      // update warning + effective inputs in place (no full refresh: keeps
      // the native color picker popup alive while dragging)
      const row = body.querySelector(`.orb-slot-row[data-slot="${slot}"]`);
      if (row) {
        row.querySelector('.orb-slot-color').value = hexStr;
        row.querySelector('.orb-slot-hex').value = hexStr;
        row.querySelector('.orb-slot-restore').disabled = false;
      }
      const warnEl = body.querySelector(`[data-warn="${slot}"]`);
      const warn = slotWarn(slot, hexStr);
      if (warnEl) { warnEl.textContent = warn || ''; warnEl.style.display = warn ? 'block' : 'none'; }
    }
  };
  body.querySelectorAll('.orb-slot-color').forEach((el) => {
    el.addEventListener('input', () => syncSlot(el.dataset.slot, el.value));
  });
  body.querySelectorAll('.orb-slot-hex').forEach((el) => {
    el.addEventListener('change', () => syncSlot(el.dataset.slot, el.value.trim()));
  });
  body.querySelectorAll('.orb-slot-restore').forEach((el) => {
    el.addEventListener('click', () => {
      if (!sel.custom) return;
      delete sel.custom[currentCustomTheme()][el.dataset.slot];
      commit();
      refresh();
    });
  });

  // 4. Reset — clear the whole selection back to factory defaults.
  body.querySelector('#orb-appearance-reset')?.addEventListener('click', () => {
    clearSaved();
    sel = loadSaved();
    window.__showToast?.(t('settings.appearance.resetDone'), 'success');
    refresh();
  });

  // Preview orb: idle state on the current custom/base board.
  mountPreview();

  // System theme flips must re-render the preview through the right board
  // (registered once; rebinds are cheap no-ops thanks to the guard).
  if (!bindAppearanceEvents._themeHook) {
    bindAppearanceEvents._themeHook = true;
    if (window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: light)').addEventListener?.('change', () => {
        const p = preview;
        if (!p) return;
        p.renderer.setTheme(isLightTheme());
        p.renderer.setPalette(boardForState('idle', loadSaved(), isLightTheme()));
      });
    }
  }
}
