// orbPresets.js — micOrb liquid-orb palette preset registry + state→palette
// resolution chain (v8.3.0; design doc 20260902_micorb-presets-design.md §2/§3,
// author rulings 2026-09-02 20:59).
//
// 配色 = 状态语言: each of the 9 orb states maps to a preset board (dark/light
// double boards); the mapping is user-overridable per state in the settings
// "外观" section. Board hex values are design §2.2 verbatim (identical to the
// approved preview page 20260902_micorb-presets-preview.html).
//
// Slot semantics (identical to micOrb.js PAL_DEFAULT / shader palA/palB/palC):
//   a — body base color (luminous core in dark mode)
//   b — flow secondary hue (swirl counter-color, mixed at 0.55 weight)
//   c — deep tone (dark-region base; must be saturated-dark, never gray)
//
// Persistence: localStorage key('micOrb.palette') — pure frontend visual
// preference, same paradigm as key('locale') (design §6 ruling). Reads are
// fault-tolerant: corrupt/unknown data falls back to defaults, never throws.
//
// This module is DATA + RESOLUTION only: it never touches the shader, the
// premultiplied alpha pipeline, or STATES motion semantics.

import { key } from './branding.js';

/** localStorage key for the whole selection (base/map/custom). */
const LS_KEY = key('micOrb.palette');

/** Window event dispatched by saveSaved() after every committed change. */
export const CHANGE_EVENT = 'micorb-palette-changed';

/**
 * 8 preset registries (design §2.2). `dark`/`light` are full boards; hex
 * strings are converted to float triplets lazily and cached.
 * `ash` (灰烬, the desaturated offline board) is registered separately below.
 */
export const PRESETS = [
  { id: 'nebula',  name: { zh: '星云', en: 'Nebula' },
    dark:  { a: '#8FA2FF', b: '#DC7CF0', c: '#2A1E5C' },
    light: { a: '#6B7FE8', b: '#C75FD8', c: '#221750' } },
  { id: 'ocean',   name: { zh: '海洋', en: 'Ocean' },
    dark:  { a: '#3FE0D0', b: '#2E86E8', c: '#063A66' },
    light: { a: '#1FBFBB', b: '#1F6FD0', c: '#05304F' } },
  { id: 'aurora',  name: { zh: '极光', en: 'Aurora' },
    dark:  { a: '#4BE3A0', b: '#6E7BF2', c: '#0B4A46' },
    light: { a: '#2ECC8A', b: '#5A6AE0', c: '#093B37' } },
  { id: 'sunset',  name: { zh: '日落', en: 'Sunset' },
    dark:  { a: '#FFA26B', b: '#F060A0', c: '#7A2050' },
    light: { a: '#F58A50', b: '#DE4E8C', c: '#661A42' } },
  { id: 'emerald', name: { zh: '翡翠', en: 'Emerald' },
    dark:  { a: '#35D98A', b: '#B8E05A', c: '#0A5230' },
    light: { a: '#22B870', b: '#9CC43E', c: '#084327' } },
  { id: 'magma',   name: { zh: '熔岩', en: 'Magma' },
    dark:  { a: '#FF9A3D', b: '#F0483C', c: '#7A1220' },
    light: { a: '#F0822E', b: '#D83A30', c: '#650E1A' } },
  { id: 'dawn',    name: { zh: '晨曦', en: 'Dawn' },
    dark:  { a: '#FFB3A0', b: '#8FA0F5', c: '#5C3A78' },
    light: { a: '#F89484', b: '#7A8CE8', c: '#4E3066' } },
  { id: 'neon',    name: { zh: '霓虹', en: 'Neon' },
    dark:  { a: '#3DF2F2', b: '#F05AD8', c: '#1A1A66' },
    light: { a: '#28D0D8', b: '#D848C2', c: '#151552' } },
];

/**
 * 灰烬 Ash — the desaturated offline board (ruling ②: 去饱和灰暗变体，通透灰
 * 而非死灰). Slightly blue-tinted grays with a luminous core; the offline
 * state's sat×0 additionally flattens any residual tint to pure glass-gray.
 * Available in the per-state map dropdowns (not a 基调 candidate).
 */
export const ASH = { id: 'ash', name: { zh: '灰烬', en: 'Ash' },
  dark:  { a: '#B9C2D2', b: '#8D99AE', c: '#2B3242' },
  light: { a: '#8C96A8', b: '#707C90', c: '#232B3A' } };

/** Default 基调 (ruling ③: 开箱即 Neon — replaces the old blue/violet default). */
export const DEFAULT_BASE = 'neon';

/**
 * Default state→preset mapping (ruling ②). `idle` is intentionally absent:
 * it resolves to the 基调 (which defaults to neon), so the 基调 dropdown
 * always controls the brand state unless idle is explicitly overridden.
 */
export const DEFAULT_STATE_MAP = {
  'listening':    'ocean',   // 听写中
  'processing':   'dawn',    // 识别中
  'nebula-busy':  'aurora',  // Nebula 工作中
  'bg-agents':    'nebula',  // 后台 agent 工作
  'frozen':       'emerald', // 冻结中
  'frozen-error': 'magma',   // 冻结·错误
  'mic-error':    'magma',   // 麦克风错误
  'offline':      'ash',     // 离线
};

const PRESET_BY_ID = {};
for (const p of PRESETS) PRESET_BY_ID[p.id] = p;
PRESET_BY_ID[ASH.id] = ASH;

/** ids allowed in the per-state map (8 presets + ash). */
const ALL_IDS = new Set(Object.keys(PRESET_BY_ID));
/** ids allowed as 基调 (8 presets only — ruling ①). */
const BASE_IDS = new Set(PRESETS.map((p) => p.id));

/** @returns {{id:string,name:{zh:string,en:string},dark:Object,light:Object}|null} */
export function getPreset(id) { return PRESET_BY_ID[id] || null; }

/** Localized display name of a preset id (falls back to the id itself). */
export function presetName(id, locale) {
  const p = PRESET_BY_ID[id];
  if (!p) return id;
  return p.name[locale] || p.name.en || id;
}

const HEX_RE = /^#[0-9a-fA-F]{6}$/;

/** '#RRGGBB' → [r,g,b] floats (0..1), or null when not a valid hex triple. */
export function hexToVec(h) {
  if (typeof h !== 'string' || !HEX_RE.test(h)) return null;
  return [parseInt(h.slice(1, 3), 16) / 255, parseInt(h.slice(3, 5), 16) / 255, parseInt(h.slice(5, 7), 16) / 255];
}

// Board float-triplet cache (hex strings → {a,b,c} arrays). Custom-merged
// boards are computed on demand (cheap) and not cached.
const boardCache = new Map();

/**
 * Resolve a board to shader floats. When `sel.custom` exists and its effective
 * source preset equals `id`, the custom slot overrides are merged over that
 * theme's preset slots (sparse: missing slots inherit the preset).
 * @returns {{a:number[],b:number[],c:number[]}}
 */
export function boardOf(id, isLight, sel) {
  const p = PRESET_BY_ID[id] || PRESET_BY_ID[DEFAULT_BASE];
  const theme = isLight ? 'light' : 'dark';
  let hex = p[theme];
  const custom = sel && sel.custom;
  if (custom) {
    const src = BASE_IDS.has(custom.base) ? custom.base : (BASE_IDS.has(sel.base) ? sel.base : DEFAULT_BASE);
    if (src === p.id) {
      const o = custom[theme] || {};
      hex = { a: o.a || p[theme].a, b: o.b || p[theme].b, c: o.c || p[theme].c };
    }
  }
  const ck = theme + ':' + hex.a + hex.b + hex.c;
  let board = boardCache.get(ck);
  if (!board) {
    board = { a: hexToVec(hex.a) || [0, 0, 0], b: hexToVec(hex.b) || [0, 0, 0], c: hexToVec(hex.c) || [0, 0, 0] };
    boardCache.set(ck, board);
  }
  return board;
}

/**
 * Per-state preset id: user map override ? default map ? 基调.
 * (Unknown ids were already dropped at load time; this re-guards anyway.)
 */
export function presetIdFor(stateKey, sel) {
  const s = sel || {};
  const override = s.map ? s.map[stateKey] : undefined;
  if (override && ALL_IDS.has(override)) return override;
  const mapped = DEFAULT_STATE_MAP[stateKey];
  if (mapped) return mapped;
  return BASE_IDS.has(s.base) ? s.base : DEFAULT_BASE;
}

/**
 * Full resolution chain: state → (user map override ? default map ? 基调) →
 * preset board for the active theme → {a,b,c} floats.
 */
export function boardForState(stateKey, sel, isLight) {
  return boardOf(presetIdFor(stateKey, sel), isLight, sel);
}

/* ------------------------------------------------------------------ */
/* Persistence                                                          */
/* ------------------------------------------------------------------ */

/** @returns {{base:string, map:Object, custom:Object|null}} sanitized selection */
export function loadSaved() {
  const fallback = { base: DEFAULT_BASE, map: {}, custom: null };
  let raw = null;
  try { raw = localStorage.getItem(LS_KEY); } catch { /* private mode etc. */ }
  if (!raw) return fallback;
  let data;
  try { data = JSON.parse(raw); } catch { return fallback; } // corrupt → defaults
  if (!data || typeof data !== 'object') return fallback;
  const sel = { base: BASE_IDS.has(data.base) ? data.base : DEFAULT_BASE, map: {}, custom: null };
  if (data.map && typeof data.map === 'object') {
    for (const [k, v] of Object.entries(data.map)) {
      if (Object.prototype.hasOwnProperty.call(DEFAULT_STATE_MAP, k) && ALL_IDS.has(v)) sel.map[k] = v;
    }
  }
  if (data.custom && typeof data.custom === 'object') {
    const c = { base: BASE_IDS.has(data.custom.base) ? data.custom.base : sel.base, dark: {}, light: {} };
    for (const theme of ['dark', 'light']) {
      const src = data.custom[theme];
      if (!src || typeof src !== 'object') continue;
      for (const slot of ['a', 'b', 'c']) {
        if (HEX_RE.test(src[slot] || '')) c[theme][slot] = src[slot];
      }
    }
    sel.custom = c;
  }
  return sel;
}

/**
 * Persist the selection and notify listeners (micOrb re-resolves its board).
 * @returns {boolean} false when storage is unavailable (selection still
 *                    applies for this session — callers keep their copy).
 */
export function saveSaved(sel) {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(sel));
  } catch {
    return false;
  }
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
  return true;
}

/** Drop the whole selection (settings 重置) and notify listeners. */
export function clearSaved() {
  try { localStorage.removeItem(LS_KEY); } catch { /* ignore */ }
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
}
