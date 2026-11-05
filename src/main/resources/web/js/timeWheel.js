// timeWheel.js — 时间滚轮选择器（Apple 滚轮同构）· 全站时间/日期类输入面的唯一组件单源
//
// 设计出处（2026-11-01 设计批两波，作者令 16:14 + 16:46；两批产出物零产品代码）：
//   · spec   docs/design/20261101_163000_time-wheel-picker-spec__delegate-nebflow-0c50686f.zh-CN.md
//   · mockup docs/design/20261101_time-wheel-picker-mockup__delegate-nebflow-0c50686f.html
// 本文件 = 该设计的实施（作者令 2026-11-03 22:16「把它补回来」+ 22:20「编辑面也采用同款的
// 滚轮，尽量让交互统一」）。**单源纪律**：全站任何时间/日期类编辑面都从这里取交互，
// 禁第二份实现（断言 = tests/time-wheel-picker.spec.mjs 的 single-source 用例）。
//
// 数据契约零改（spec §2.2，后端双真源）：
//   · 时段 = "HH:mm" 24h 二元组，[start,end)；start > end = 跨零点（合法）；
//     start == end = 非法（零长度无意义 / fail-safe 永不命中）。
//   · 分钟步进 5 档（spec R3 / O2），精确分钟走「精确输入」直输行兜底。
//   · 单点时刻（L4/L5）对外只产出 {date:"YYYY-MM-DD", time:"HH:mm"}，毫秒换算是落点的事。
//
// 形态（spec R4 / R7 / R8，O7 = 跟随宽度）：桌面锚定 popover（z 10001）、≤768px 底部
// sheet（z 11000、连接符「至」、顶部 grabber），两皮共用同一颗滚轮核心。
// **时段 = 原子字段**：整段「09:00-12:00」一颗触发钮，一次开合、一次提交起止两值。

import { t } from './i18n.js';

const ITEM_H = 40;
const REP = 3;
const COL_H = 200;

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
const MINS = Array.from({ length: 12 }, (_, i) => String(i * 5).padStart(2, '0'));

const easeOutCubic = (v) => 1 - Math.pow(1 - v, 3);
const REDUCED = typeof matchMedia === 'function'
  ? matchMedia('(prefers-reduced-motion: reduce)')
  : { matches: false };

const CLOCK_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
  + 'stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>';

/** HH:mm 正则（与后端 validate 同律：24h、两位）。
 *  ⚠️ 两个捕获组必须有：toMin 取 m[1]（时）+ m[2]（分）。曾漏掉分钟的括号
 *  ⇒ m[2] === undefined ⇒ toMin 恒 NaN ⇒ 跨午夜徽标/零长度拒收/跨零点提示
 *  三条实时校验全部静默失效（time-wheel-picker 批自测抓出）。 */
export const HHMM_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;

/** "HH:mm" → 分钟数；非法返回 NaN。 */
export function toMin(hm) {
  const m = HHMM_RE.exec(String(hm || ''));
  return m ? Number(m[1]) * 60 + Number(m[2]) : NaN;
}

/** 分钟就近吸附到 5 档。 */
export function snapMin5(min) {
  const v = Math.round(Number(min) / 5) * 5;
  return Math.min(55, v);
}

function pad2(n) { return String(n).padStart(2, '0'); }

/** 本地毫秒 → "YYYY-MM-DDTHH:mm"（datetime-local 兼容串，L4 值域保持位）。 */
export function msToLocalInput(ms) {
  const d = new Date(ms);
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
    + 'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

/* ══════════════════════════════════════════════════════════════════════════
   Wheel — 单列滚轮核心（spec §6 手感参数全表）
   虚拟环形：值数组 ×3 份循环回收；pos 为浮点条目位（中份坐标系）；
   拖拽 1:1 跟手 → 释放动量指数衰减（τ=325ms）→ 低于阈值吸附最近档。
   桌面滚轮按像素累积阈值步进 + idle 吸附；键盘 spinbutton 语义 + 数字直输。
   ══════════════════════════════════════════════════════════════════════════ */
export class Wheel {
  /**
   * @param {HTMLElement} host 列容器（.tw-col）
   * @param {{values:string[], label:string, unit:string, onChange?:Function, big?:number}} opts
   */
  constructor(host, opts) {
    this.host = host;
    this.values = opts.values;
    this.N = this.values.length;
    this.label = opts.label;
    this.unit = opts.unit || '';
    this.onChange = opts.onChange || (() => {});
    this.big = opts.big || (this.N === 24 ? 6 : 12);
    this.pos = this.N;
    this.state = 'idle';
    this.v = 0;
    this.raf = null;
    this.digitBuf = '';
    this.digitTimer = null;
    this.locked = false;

    const strip = document.createElement('div');
    strip.className = 'tw-strip';
    this.items = [];
    for (let r = 0; r < REP; r++) {
      for (let i = 0; i < this.N; i++) {
        const it = document.createElement('div');
        it.className = 'tw-item';
        it.textContent = this.values[i];
        strip.appendChild(it);
        this.items.push(it);
      }
    }
    host.appendChild(strip);
    this.strip = strip;

    host.setAttribute('role', 'spinbutton');
    host.setAttribute('tabindex', '0');
    host.setAttribute('aria-label', this.label);
    host.setAttribute('aria-valuemin', '0');
    host.setAttribute('aria-valuemax', String(this.N - 1));

    host.addEventListener('pointerdown', (e) => this.onDown(e));
    host.addEventListener('pointermove', (e) => this.onMove(e));
    host.addEventListener('pointerup', () => this.onUp());
    host.addEventListener('pointercancel', () => this.onUp());
    host.addEventListener('wheel', (e) => this.onWheel(e), { passive: false });
    host.addEventListener('keydown', (e) => this.onKey(e));
    this.render();
  }

  mod(i) { return ((i % this.N) + this.N) % this.N; }
  value() { return this.values[this.mod(Math.round(this.pos))]; }
  valueIdx() { return this.mod(Math.round(this.pos)); }
  yFor(p) { return COL_H / 2 - (p + this.N + 0.5) * ITEM_H; }

  render() {
    this.strip.style.transform = 'translateY(' + this.yFor(this.pos).toFixed(2) + 'px)';
    const center = this.pos + this.N;
    for (let s = 0; s < this.items.length; s++) {
      const d = Math.abs(s - center);
      const tier = d < 0.5 ? 0 : d < 1.5 ? 1 : d < 2.5 ? 2 : d < 3.5 ? 3 : 4;
      const it = /** @type {HTMLDivElement & {_tier?: number}} */ (this.items[s]);
      if (it._tier !== tier) {
        it._tier = tier;
        const conf = [[1, 1], [0.55, 0.97], [0.3, 0.95], [0.15, 0.94], [0.06, 0.94]][tier];
        it.style.opacity = String(conf[0]);
        it.style.transform = 'scale(' + conf[1] + ')';
      }
    }
    const idx = this.valueIdx();
    if (this._a11yIdx !== idx) {
      this._a11yIdx = idx;
      this.host.setAttribute('aria-valuenow', String(idx));
      this.host.setAttribute('aria-valuetext', this.value() + ' ' + this.unit);
    }
  }

  emit() {
    if (this._lastEmitted !== this.valueIdx()) {
      this._lastEmitted = this.valueIdx();
      this.onChange();
    }
  }

  kick() {
    if (this.raf == null) {
      this._lastTs = performance.now();
      this.raf = requestAnimationFrame((ts) => this.tick(ts));
    }
  }

  tick(ts) {
    const dt = Math.min(48, ts - this._lastTs);
    this._lastTs = ts;
    if (this.state === 'coast') {
      this.pos += this.v * dt;
      this.v *= Math.exp(-dt / 325);
      if (Math.abs(this.v) < 0.0015) this.snapTo(Math.round(this.pos), 200);
    } else if (this.state === 'snap') {
      const k = Math.min(1, (ts - this.snapT0) / Math.max(1, this.snapDur));
      this.pos = this.snapFrom + (this.snapTarget - this.snapFrom) * easeOutCubic(k);
      if (k >= 1) { this.state = 'idle'; this.pos = this.snapTarget; this.emit(); }
    }
    this.render();
    if (this.state === 'idle') { this.raf = null; this.normalize(); }
    else this.raf = requestAnimationFrame((t2) => this.tick(t2));
  }

  /** pos 归位到中份邻域，防长会话漂移。 */
  normalize() {
    const n = this.N;
    if (this.pos < n * 0.5) this.pos += n;
    else if (this.pos >= n * 1.5) this.pos -= n;
    this.render();
  }

  snapTo(target, dur) {
    const n = this.N;
    while (target - this.pos > n / 2) target -= n;
    while (this.pos - target > n / 2) target += n;
    if (REDUCED.matches) {
      this.pos = target;
      this.state = 'idle';
      this.render();
      this.emit();
      return;
    }
    this.state = 'snap';
    this.snapFrom = this.pos;
    this.snapTarget = target;
    this.snapDur = Math.max(90, Math.min(280, dur));
    this.snapT0 = performance.now();
    this.kick();
  }

  onDown(e) {
    if (this.locked) return;
    this.host.setPointerCapture(e.pointerId);
    this.state = 'drag';
    this._samples = [[e.clientY, performance.now()]];
    this._lastY = e.clientY;
    this.digitBuf = '';
    this.kick();
    e.preventDefault();
  }

  onMove(e) {
    if (this.state !== 'drag') return;
    const dy = e.clientY - this._lastY;
    this._lastY = e.clientY;
    this.pos -= dy / ITEM_H;
    this._samples.push([e.clientY, performance.now()]);
    while (this._samples.length > 2 && performance.now() - this._samples[0][1] > 100) this._samples.shift();
    this.render();
    this.emit();
  }

  onUp() {
    if (this.state !== 'drag') return;
    const s = this._samples;
    let v = 0;
    if (s && s.length >= 2) {
      const dy = s[0][0] - s[s.length - 1][0];
      const dt = Math.max(1, s[s.length - 1][1] - s[0][1]);
      v = Math.max(-2.5, Math.min(2.5, (dy / ITEM_H) / dt));   // 钳 ±2.5 档/ms 防甩飞
    }
    if (Math.abs(v) < 0.004 || REDUCED.matches) this.snapTo(Math.round(this.pos), 180);
    else { this.state = 'coast'; this.v = v; }
    this.kick();
  }

  onWheel(e) {
    e.preventDefault();
    if (this.state === 'drag') return;
    if (this.state === 'coast') this.snapTo(Math.round(this.pos), 120);
    this._acc = (this._acc || 0) + e.deltaY;
    if (Math.abs(this._acc) >= 30) {
      const dir = this._acc > 0 ? 1 : -1;
      this._acc = 0;
      this.snapTo(Math.round(this.pos) + dir, 110);
    }
    clearTimeout(this._wheelIdle);
    this._wheelIdle = setTimeout(() => {
      if (this.state === 'idle') this.snapTo(Math.round(this.pos), 120);
    }, 160);
    this.kick();
  }

  onKey(e) {
    let step = 0;
    if (e.key === 'ArrowDown') step = e.shiftKey ? this.big : 1;
    else if (e.key === 'ArrowUp') step = e.shiftKey ? -this.big : -1;
    else if (e.key === 'PageDown') step = this.big;
    else if (e.key === 'PageUp') step = -this.big;
    else if (e.key === 'Home') { this.snapToNearest(0); e.preventDefault(); return; }
    else if (e.key === 'End') { this.snapToNearest(this.N - 1); e.preventDefault(); return; }
    else if (e.key === 'Enter' && this.digitBuf) { this.applyDigits(); e.preventDefault(); return; }
    else if (/^[0-9]$/.test(e.key)) {
      this.digitBuf += e.key;
      clearTimeout(this.digitTimer);
      this.digitTimer = setTimeout(() => this.applyDigits(), 900);
      if (this.digitBuf.length >= 2) this.applyDigits();
      e.preventDefault();
      return;
    }
    if (step) {
      e.preventDefault();
      this.digitBuf = '';
      this.snapTo(Math.round(this.pos) + step, 140);
    }
  }

  snapToNearest(valIdx) {
    let cand = this.N + valIdx;
    if (cand - this.pos > this.N / 2) cand -= this.N;
    if (this.pos - cand > this.N / 2) cand += this.N;
    this.snapTo(cand, 220);
  }

  applyDigits() {
    clearTimeout(this.digitTimer);
    const buf = this.digitBuf;
    this.digitBuf = '';
    if (!buf) return;
    let val = parseInt(buf, 10);
    if (isNaN(val)) return;
    if (this.N === 24) {
      if (val > 23) val = 23;
      this.snapToNearest(val);
    } else {
      if (val > 59) val = 59;
      this.snapToNearest(snapMin5(val) / 5);
    }
  }

  /** 外部置档（index）。animate=true 走 260ms（chip 双组同拨）。 */
  setTo(idx, animate) {
    let cand = this.N + ((idx % this.N) + this.N) % this.N;
    if (cand - this.pos > this.N / 2) cand -= this.N;
    if (this.pos - cand > this.N / 2) cand += this.N;
    if (animate && !REDUCED.matches) this.snapTo(cand, 260);
    else { this.pos = cand; this._a11yIdx = null; this.render(); this.emit(); }
  }

  /** 外部置值（列内值文本匹配；小时/日期列按值，分钟列按 "00"/"05"… 档文本）。 */
  setToValue(value, animate) {
    const idx = this.values.indexOf(String(value));
    if (idx >= 0) this.setTo(idx, animate);
  }
}

/* ══════════════════════════════════════════════════════════════════════════
   面板（两皮共用单例；一个页面只存在一个选择器实例）
   ══════════════════════════════════════════════════════════════════════════ */

/** @type {HTMLElement|null} */
let panel = null;
/** @type {{trigger:HTMLElement, mode:string, onConfirm:Function, days:number[]|null, allowDays:boolean}|null} */
let session = null;
/** @type {Record<string, Wheel>} */
let wheels = {};
/** @type {{iso:string,label:string}[]} */
let dateValues = [];

function isSheetMode() {
  if (typeof document === 'undefined') return false;
  if (document.body && document.body.classList.contains('mobile-layer')) return true;
  return typeof matchMedia === 'function' && matchMedia('(max-width: 768px)').matches;
}

function sepText() { return isSheetMode() ? t('timewheel.sepTo') : '-'; }

/* ── 触发字段（原子段 / 单点）──────────────────────────────────────────── */

/** 时段原子触发钮（整段 = 唯一触发点，spec R7）。
 *  @param {{start?:string, end?:string, label?:string, presets?:'standard'|'freeze'|'none',
 *           days?:number[]|null, daysAllowed?:boolean}} init */
export function rangeTriggerHTML(init) {
  const s = init.start || '09:00';
  const e = init.end || '12:00';
  const label = init.label || t('timewheel.editRange');
  const presets = init.presets || 'standard';
  const days = Array.isArray(init.days) && init.days.length ? init.days.join(',') : '';
  return '<button type="button" class="tw-trigger" data-tw-range'
    + ' data-start="' + s + '" data-end="' + e + '" data-aria-label="' + label + '"'
    + ' data-presets="' + presets + '"'
    + (init.daysAllowed ? ' data-days-allowed=""' : '')
    + (days ? ' data-days="' + days + '"' : '')
    + ' aria-haspopup="dialog" aria-expanded="false"'
    + ' aria-label="' + label + ' ' + s + ' ' + sepText() + ' ' + e + '">'
    + CLOCK_SVG
    + '<span class="tw-tv"><span data-tw-start>' + s + '</span>'
    + '<i class="tw-dash">' + sepText() + '</i><span data-tw-end>' + e + '</span></span></button>';
}

/** 单点时刻触发钮（date = "YYYY-MM-DD"，time = "HH:mm"）。 */
export function pointTriggerHTML(init) {
  const label = init.label || t('timewheel.editPoint');
  return '<button type="button" class="tw-trigger" data-tw-point'
    + ' data-date="' + init.date + '" data-time="' + init.time + '" data-aria-label="' + label + '"'
    + ' aria-haspopup="dialog" aria-expanded="false"'
    + ' aria-label="' + label + ' ' + init.date + ' ' + init.time + '">'
    + CLOCK_SVG
    + '<span class="tw-tv"><span data-tw-point-text>' + pointFace(init.date, init.time) + '</span></span></button>';
}

function pointFace(date, time) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(date || ''));
  const head = m ? (Number(m[2]) + '/' + Number(m[3])) : '';
  return (head ? head + ' ' : '') + time;
}

/** 数据 → 字段脸的刷新（单一来源，落点改 dataset 后调这里）。 */
export function paintRangeTrigger(btn) {
  if (!btn) return;
  const s = btn.dataset.start || '--:--';
  const e = btn.dataset.end || '--:--';
  const sv = btn.querySelector('[data-tw-start]');
  const ev = btn.querySelector('[data-tw-end]');
  const dash = btn.querySelector('.tw-dash');
  if (sv) sv.textContent = s;
  if (ev) ev.textContent = e;
  if (dash) dash.textContent = sepText();
  const label = btn.dataset.ariaLabel || t('timewheel.editRange');
  btn.setAttribute('aria-label', label + ' ' + s + ' ' + sepText() + ' ' + e);
}

export function paintPointTrigger(btn) {
  if (!btn) return;
  const text = btn.querySelector('[data-tw-point-text]');
  if (text) text.textContent = pointFace(btn.dataset.date, btn.dataset.time);
  const label = btn.dataset.ariaLabel || t('timewheel.editPoint');
  btn.setAttribute('aria-label', label + ' ' + (btn.dataset.date || '') + ' ' + (btn.dataset.time || ''));
}

/* ── 面板 DOM ──────────────────────────────────────────────────────────── */

function ensurePanel() {
  if (panel) return panel;
  const el = document.createElement('div');
  el.className = 'tw-panel';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', t('timewheel.titleRange'));
  el.innerHTML = ''
    + '<div class="tw-head">'
    + '  <button type="button" class="tw-btn tw-cancel">' + t('timewheel.cancel') + '</button>'
    + '  <div class="tw-title"></div>'
    + '  <button type="button" class="tw-btn tw-confirm">' + t('timewheel.confirm') + '</button>'
    + '</div>'
    + '<div class="tw-body tw-body-range">'
    + '  <div class="tw-wheel-row">'
    + '    <div class="tw-band"></div>'
    + '    <div class="tw-col" data-role="h1" data-group="start"></div>'
    + '    <div class="tw-col" data-role="m1" data-group="start"></div>'
    + '    <div class="tw-sep tw-wheel-sep">-</div>'
    + '    <div class="tw-col" data-role="h2" data-group="end"></div>'
    + '    <div class="tw-col" data-role="m2" data-group="end"></div>'
    + '    <div class="tw-fade"></div>'
    + '  </div>'
    + '  <div class="tw-chips"></div>'
    + '  <div class="tw-days" hidden></div>'
    + '  <div class="tw-status tw-status-cross"></div>'
    + '  <div class="tw-status tw-status-equal">' + t('timewheel.equalError') + '</div>'
    + '  <div class="tw-input-row">'
    + '    <input class="tw-in tw-in-start" maxlength="5" inputmode="numeric" aria-label="HH:mm" placeholder="HH:mm">'
    + '    <span class="tw-sep tw-in-sep">-</span>'
    + '    <input class="tw-in tw-in-end" maxlength="5" inputmode="numeric" aria-label="HH:mm" placeholder="HH:mm">'
    + '  </div>'
    + '  <button type="button" class="tw-input-toggle">' + t('timewheel.exactInput') + '</button>'
    + '</div>'
    + '<div class="tw-body tw-body-point" hidden>'
    + '  <div class="tw-wheel-row">'
    + '    <div class="tw-band"></div>'
    + '    <div class="tw-col tw-col-date" data-role="d1"></div>'
    + '    <div class="tw-col" data-role="ph"></div>'
    + '    <div class="tw-col" data-role="pm"></div>'
    + '    <div class="tw-fade"></div>'
    + '  </div>'
    + '</div>';
  document.body.appendChild(el);
  panel = el;

  /** @param {string} r */
  const col = (r) => /** @type {HTMLElement} */ (el.querySelector('.tw-col[data-role="' + r + '"]'));
  wheels = {
    h1: new Wheel(col('h1'), { values: HOURS, label: t('timewheel.startHour'), unit: t('timewheel.hourUnit'), onChange: () => onWheelChange() }),
    m1: new Wheel(col('m1'), { values: MINS, label: t('timewheel.startMin'), unit: t('timewheel.minUnit'), onChange: () => onWheelChange() }),
    h2: new Wheel(col('h2'), { values: HOURS, label: t('timewheel.endHour'), unit: t('timewheel.hourUnit'), onChange: () => onWheelChange() }),
    m2: new Wheel(col('m2'), { values: MINS, label: t('timewheel.endMin'), unit: t('timewheel.minUnit'), onChange: () => onWheelChange() }),
    ph: new Wheel(col('ph'), { values: HOURS, label: t('timewheel.hourUnit'), unit: t('timewheel.hourUnit'), onChange: () => {} }),
    pm: new Wheel(col('pm'), { values: MINS, label: t('timewheel.minUnit'), unit: t('timewheel.minUnit'), onChange: () => {} }),
  };

  /** @type {HTMLElement} */ (el.querySelector('.tw-cancel')).addEventListener('click', () => close(false));
  /** @type {HTMLElement} */ (el.querySelector('.tw-confirm')).addEventListener('click', () => close(true));
  /** @type {HTMLElement} */ (el.querySelector('.tw-input-toggle')).addEventListener('click', () => {
    const row = /** @type {HTMLElement} */ (el.querySelector('.tw-input-row'));
    row.classList.toggle('show');
    if (row.classList.contains('show')) {
      const a = /** @type {HTMLInputElement} */ (el.querySelector('.tw-in-start'));
      const b = /** @type {HTMLInputElement} */ (el.querySelector('.tw-in-end'));
      a.value = hm(true);
      b.value = hm(false);
      a.focus();
      a.select();
    }
  });
  const applyInput = () => {
    const a = /** @type {HTMLInputElement} */ (el.querySelector('.tw-in-start'));
    const b = /** @type {HTMLInputElement} */ (el.querySelector('.tw-in-end'));
    const okA = eatInput(a, 'h1', 'm1');
    const okB = eatInput(b, 'h2', 'm2');
    if (okA && okB) onWheelChange();
  };
  el.querySelectorAll('.tw-in').forEach((node) => {
    const inp = /** @type {HTMLInputElement} */ (node);
    inp.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { applyInput(); inp.blur(); }
    });
    inp.addEventListener('blur', applyInput);
  });
  return el;
}

/** @param {HTMLInputElement|Element|null} node */
function eatInput(node, hKey, mKey) {
  const inp = /** @type {HTMLInputElement|null} */ (node);
  if (!inp) return false;
  const m = /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(String(inp.value || '').trim());
  inp.classList.toggle('err', !m);
  if (!m) return false;
  // 精确输入 = **立即落档**（不进 220ms snap 动画）：键入一个完整 HH:mm 后
  // 面板校验行必须当场跟着变（起=止 / 跨零点两态），而不是等动画跑完。
  wheels[hKey].setTo(Number(m[1]), false);
  wheels[mKey].setTo(snapMin5(Number(m[2])) / 5, false);
  return true;
}

/** @param {boolean} start */
function hm(start) {
  return start ? wheels.h1.value() + ':' + wheels.m1.value() : wheels.h2.value() + ':' + wheels.m2.value();
}

/** 实时校验（spec §5）：起==止 → 禁确定 + 错误行；起>止 → 跨零点提示条；两态互斥。 */
function onWheelChange() {
  if (!session || !panel || session.mode !== 'range') return;
  const a = toMin(hm(true));
  const b = toMin(hm(false));
  const equal = a === b;
  const cross = a > b;
  const crossEl = /** @type {HTMLElement} */ (panel.querySelector('.tw-status-cross'));
  const equalEl = /** @type {HTMLElement} */ (panel.querySelector('.tw-status-equal'));
  crossEl.classList.toggle('show', cross && !equal);
  equalEl.classList.toggle('show', equal);
  if (cross && !equal) {
    crossEl.textContent = t('timewheel.crossDay', { to: hm(false) });
  }
  /** @type {HTMLButtonElement} */ (panel.querySelector('.tw-confirm')).disabled = equal;
}

/** @param {{label:string,value:string}[]|undefined} chips */
function buildChips(chips) {
  const host = /** @type {HTMLElement} */ (panel.querySelector('.tw-chips'));
  host.innerHTML = '';
  const list = chips || [];
  list.forEach((c) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'tw-chip';
    b.dataset.preset = c.value;
    b.textContent = c.label;
    b.addEventListener('click', () => {
      const parts = c.value.split(',');
      applyRangeValue(parts[0], parts[1], true);
    });
    host.appendChild(b);
  });
  host.hidden = list.length === 0;
}

/** 星期多选（O1 裁定：编辑面同款滚轮口径 → 星期用同族胶囊多选，数据仍是 ISO 1-7）。 */
function buildDays(days) {
  const host = /** @type {HTMLElement} */ (panel.querySelector('.tw-days'));
  const allow = !!(session && session.allowDays);
  host.hidden = !allow;
  host.innerHTML = '';
  if (!allow) return;
  const label = document.createElement('span');
  label.className = 'tw-days-label';
  label.textContent = t('timewheel.daysLabel');
  host.appendChild(label);

  const every = document.createElement('button');
  every.type = 'button';
  every.className = 'tw-chip tw-day' + (days === null ? ' on' : '');
  every.dataset.day = '0';
  every.textContent = t('timewheel.everyDay');
  every.setAttribute('aria-pressed', String(days === null));
  every.addEventListener('click', () => {
    if (!session) return;
    session.days = session.days === null ? [1, 2, 3, 4, 5, 6, 7] : null;
    buildDays(session.days);
  });
  host.appendChild(every);

  const keys = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
  for (let d = 1; d <= 7; d++) {
    const on = days !== null && days.includes(d);
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'tw-chip tw-day' + (on ? ' on' : '');
    b.dataset.day = String(d);
    b.textContent = t('timewheel.' + keys[d - 1]);
    b.setAttribute('aria-pressed', String(on));
    b.addEventListener('click', () => {
      if (!session) return;
      const cur = session.days === null ? [1, 2, 3, 4, 5, 6, 7] : session.days.slice();
      const at = cur.indexOf(d);
      if (at >= 0) cur.splice(at, 1); else cur.push(d);
      cur.sort((x, y) => x - y);
      session.days = cur.length === 7 ? null : cur;
      buildDays(session.days);
    });
    host.appendChild(b);
  }
}

function applyRangeValue(s, e, animate) {
  const sp = String(s || '09:00').split(':').map(Number);
  const ep = String(e || '12:00').split(':').map(Number);
  wheels.h1.setToValue(pad2(sp[0] || 0), animate);
  wheels.m1.setTo(snapMin5(sp[1] || 0) / 5, animate);
  wheels.h2.setToValue(pad2(ep[0] || 0), animate);
  wheels.m2.setTo(snapMin5(ep[1] || 0) / 5, animate);
  onWheelChange();
}

/** 日期列（今起 21 天；minMs = 现在 ⇒ 该列永不给出过去时刻）。 */
function buildDateValues() {
  const out = [];
  const base = new Date();
  base.setHours(0, 0, 0, 0);
  const wd = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
  for (let i = 0; i < 21; i++) {
    const d = new Date(base.getTime() + i * 86400000);
    const iso = d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
    out.push({
      iso,
      label: (d.getMonth() + 1) + '/' + d.getDate() + ' ' + t('timewheel.' + wd[d.getDay()]),
    });
  }
  return out;
}

/** 日期列按需（重）建：值集依赖「今天」，跨零点重开会换集。 */
function mountDateWheel() {
  const host = /** @type {HTMLElement} */ (panel.querySelector('.tw-col[data-role="d1"]'));
  const signature = dateValues.map((d) => d.iso).join('|');
  if (wheels.d1 && host.dataset.sig === signature) return;
  host.innerHTML = '';
  host.dataset.sig = signature;
  wheels.d1 = new Wheel(host, {
    values: dateValues.map((d) => d.label),
    label: t('timewheel.dateLabel'),
    unit: '',
    big: 7,
    onChange: () => {},
  });
}

function setPointValue(date, time, animate) {
  const idx = dateValues.findIndex((d) => d.iso === date);
  wheels.d1.setTo(idx >= 0 ? idx : 0, animate);
  const parts = String(time || '09:00').split(':').map(Number);
  wheels.ph.setToValue(pad2(parts[0] || 0), animate);
  wheels.pm.setTo(snapMin5(parts[1] || 0) / 5, animate);
}

/** 面板定位（popover 锚定整段字段下方 6px，视口钳位 8px，空间不足翻转上方）。 */
function place(trigger) {
  const el = /** @type {HTMLElement} */ (panel);
  const sheet = isSheetMode();
  el.classList.toggle('popover', !sheet);
  el.classList.toggle('sheet', sheet);
  const sep = sepText();
  const ws = el.querySelector('.tw-wheel-sep');
  const is = el.querySelector('.tw-in-sep');
  if (ws) ws.textContent = sep;
  if (is) is.textContent = sep;
  el.classList.remove('in', 'out');
  void el.offsetWidth;

  let grab = /** @type {HTMLElement|null} */ (el.querySelector('.tw-grabber'));
  if (sheet && !grab) {
    grab = document.createElement('div');
    grab.className = 'tw-grabber';
    el.prepend(grab);
    bindGrabber(grab);
  } else if (!sheet && grab) grab.remove();

  if (!sheet) {
    const r = trigger.getBoundingClientRect();
    const W = 300;
    const x = Math.min(Math.max(8, r.left), window.innerWidth - W - 8);
    let y = r.bottom + 6;
    const ph = el.offsetHeight || 400;
    if (y + ph > window.innerHeight - 8) y = Math.max(8, r.top - ph - 6);
    el.style.left = x + 'px';
    el.style.top = y + 'px';
  } else {
    el.style.left = '';
    el.style.top = '';
  }
  el.classList.add('in');
  trigger.classList.add('open');
  trigger.setAttribute('aria-expanded', 'true');
  document.addEventListener('pointerdown', outsideCloser, true);
  document.addEventListener('keydown', escCloser, true);
  const first = session && session.mode === 'point' ? wheels.d1.host : wheels.h1.host;
  setTimeout(() => first.focus({ preventScroll: true }), sheet ? 300 : 170);
}

/** @param {Event} ev */
function outsideCloser(ev) {
  if (!session) return;
  const target = ev.target;
  if (!(target instanceof Node)) return;
  if (panel && panel.contains(target)) return;
  if (session.trigger.contains(target)) return;
  close(false);
}

/** @param {Event} ev */
function escCloser(ev) {
  const e = /** @type {KeyboardEvent} */ (ev);
  if (e.key !== 'Escape' || !session) return;
  // 层叠语义（ESC 只关最内层）：面板开着时这一击归面板，不再往下传给宿主
  // 面（设置浮层 sidebar.js:222 也监听 Escape——不拦会把设置页一起关掉）。
  e.stopPropagation();
  e.stopImmediatePropagation();
  e.preventDefault();
  close(false);
}

/** @param {HTMLElement} grab */
function bindGrabber(grab) {
  let y0 = 0;
  let dy = 0;
  let t0 = 0;
  let dragging = false;
  grab.addEventListener('pointerdown', (e) => {
    dragging = true;
    y0 = e.clientY;
    dy = 0;
    t0 = performance.now();
    grab.setPointerCapture(e.pointerId);
  });
  grab.addEventListener('pointermove', (e) => {
    if (!dragging || !panel) return;
    dy = Math.max(0, e.clientY - y0);
    panel.style.transform = 'translateY(' + dy + 'px)';
  });
  const end = () => {
    if (!dragging || !panel) return;
    dragging = false;
    const v = dy / Math.max(1, performance.now() - t0);
    panel.style.transform = '';
    if (dy > 120 || v > 0.5) close(false);
  };
  grab.addEventListener('pointerup', end);
  grab.addEventListener('pointercancel', end);
}

/**
 * 打开时段选择器。trigger = 整段原子字段（非半边）；再点同一字段 = 无操作。
 * @param {HTMLElement} trigger
 * @param {{title?:string, chips?:{label:string,value:string}[], allowDays?:boolean,
 *          days?:number[]|null, onConfirm?:Function}} [opts]
 */
export function openRange(trigger, opts) {
  const el = ensurePanel();
  const o = opts || {};
  if (session && session.trigger === trigger && el.classList.contains('in')) return;
  if (session) close(false);
  session = { trigger, mode: 'range', onConfirm: o.onConfirm || (() => {}), days: o.days === undefined ? null : o.days, allowDays: !!o.allowDays };
  const title = o.title || t('timewheel.titleRange');
  /** @type {HTMLElement} */ (el.querySelector('.tw-title')).textContent = title;
  el.setAttribute('aria-label', title);
  /** @type {HTMLElement} */ (el.querySelector('.tw-body-range')).hidden = false;
  /** @type {HTMLElement} */ (el.querySelector('.tw-body-point')).hidden = true;
  /** @type {HTMLElement} */ (el.querySelector('.tw-input-row')).classList.remove('show');
  /** @type {HTMLElement} */ (el.querySelector('.tw-input-toggle')).hidden = false;
  /** @type {HTMLButtonElement} */ (el.querySelector('.tw-confirm')).disabled = false;
  buildChips(o.chips);
  buildDays(session.days);
  applyRangeValue(trigger.dataset.start || '09:00', trigger.dataset.end || '12:00', false);
  place(trigger);
}

/**
 * 打开单点时刻选择器（L4 定时任务 / L5 待办截止）。
 * @param {HTMLElement} trigger
 * @param {{title?:string, onConfirm?:Function}} [opts]
 */
export function openPoint(trigger, opts) {
  const el = ensurePanel();
  const o = opts || {};
  if (session && session.trigger === trigger && el.classList.contains('in')) return;
  if (session) close(false);
  session = { trigger, mode: 'point', onConfirm: o.onConfirm || (() => {}), days: null, allowDays: false };
  const title = o.title || t('timewheel.titlePoint');
  /** @type {HTMLElement} */ (el.querySelector('.tw-title')).textContent = title;
  el.setAttribute('aria-label', title);
  /** @type {HTMLElement} */ (el.querySelector('.tw-body-range')).hidden = true;
  /** @type {HTMLElement} */ (el.querySelector('.tw-body-point')).hidden = false;
  dateValues = buildDateValues();
  mountDateWheel();
  setPointValue(trigger.dataset.date || dateValues[0].iso, trigger.dataset.time || '09:00', false);
  place(trigger);
}

/**
 * 关闭面板。commit=true 提交（时段 = 一次提交起止两值；单点 = 日期+时刻）；
 * 取消 / ESC / 点外 / grabber 甩关 = 还原（不写回）。
 * @param {boolean} commit
 */
export function close(commit) {
  const el = panel;
  if (!session || !el) return;
  const s = session;
  session = null;
  document.removeEventListener('pointerdown', outsideCloser, true);
  document.removeEventListener('keydown', escCloser, true);
  s.trigger.classList.remove('open');
  s.trigger.setAttribute('aria-expanded', 'false');
  if (commit) {
    if (s.mode === 'range') {
      s.onConfirm({ start: hm(true), end: hm(false), days: s.days });
    } else {
      const picked = dateValues[wheels.d1.valueIdx()];
      s.onConfirm({ date: picked ? picked.iso : '', time: wheels.ph.value() + ':' + wheels.pm.value() });
    }
  }
  el.classList.remove('in');
  el.classList.add('out');
  setTimeout(() => {
    if (!session) el.classList.remove('in', 'out');
    el.style.transform = '';
  }, 240);
  s.trigger.focus({ preventScroll: true });
}

/* ── 委托绑定（一处装配，增删行零重绑）────────────────────────────────── */

/** 快捷档白名单（按落点配置，spec §8 / O3）：
 *  · standard = 全部四档（provider 不可用时段）；
 *  · freeze   = 不含「全天」——冻结是黑名单，全天 ≈ 全停（危险面，后端无翻转点特殊态），
 *               本批按 spec O3 倾向隐藏，作者一行可改回。
 *  · none     = 无快捷档。 */
function presetsFor(kind) {
  if (kind === 'none') return [];
  const all = [
    { label: t('timewheel.presetAllDay'), value: '00:00,23:59' },
    { label: t('timewheel.presetNight'), value: '23:00,08:00' },
    { label: t('timewheel.presetDay'), value: '09:00,18:00' },
    { label: t('timewheel.presetAfternoon'), value: '14:00,18:00' },
  ];
  if (kind === 'freeze') return all.slice(1);
  return all;
}

/** @param {HTMLElement} btn */
function parseDays(btn) {
  const raw = btn.dataset.days;
  if (!raw) return null;
  const out = String(raw).split(',').map(Number).filter((n) => n >= 1 && n <= 7);
  return out.length ? out : null;
}

/**
 * 把 root 内的时段/单点触发钮接到选择器（委托式，只绑一次）。
 * 触发钮自带落点配置：data-presets="standard|freeze|none"；
 * data-days-allowed 存在时开星期面（O1 裁定：按星期编辑面同款），data-days="6,7" 为现值。
 * @param {HTMLElement|Document} root
 * @param {{onRangeChange?:(btn:HTMLElement, v:{start:string,end:string,days:number[]|null})=>void,
 *          onPointChange?:(btn:HTMLElement, v:{date:string,time:string})=>void}} [opts]
 */
export function attachTimeTriggers(root, opts) {
  const host = root || document;
  const o = opts || {};
  /** @param {Event} ev */
  host.addEventListener('click', (ev) => {
    const el = ev.target instanceof Element ? ev.target : null;
    if (!el) return;
    const btn = /** @type {HTMLElement|null} */ (el.closest('[data-tw-range],[data-tw-point]'));
    if (!btn) return;
    ev.preventDefault();
    ev.stopPropagation();
    if (btn.hasAttribute('data-tw-range')) {
      const allowDays = btn.hasAttribute('data-days-allowed');
      openRange(btn, {
        chips: presetsFor(btn.dataset.presets || 'standard'),
        allowDays,
        days: allowDays ? parseDays(btn) : null,
        onConfirm: (v) => {
          if (v.start === v.end) return;
          btn.dataset.start = v.start;
          btn.dataset.end = v.end;
          if (allowDays) {
            if (v.days && v.days.length) btn.dataset.days = v.days.join(',');
            else delete btn.dataset.days;
          }
          paintRangeTrigger(btn);
          if (o.onRangeChange) o.onRangeChange(btn, v);
        },
      });
    } else {
      openPoint(btn, {
        onConfirm: (v) => {
          btn.dataset.date = v.date;
          btn.dataset.time = v.time;
          paintPointTrigger(btn);
          if (o.onPointChange) o.onPointChange(btn, v);
        },
      });
    }
  });
}

/** 测试/调试面（不改产品行为）：面板开合与形态读数。 */
export function panelState() {
  const el = panel;
  return {
    open: !!session,
    mode: session ? session.mode : '',
    sheet: !!(el && el.classList.contains('sheet')),
    popover: !!(el && el.classList.contains('popover')),
    zIndex: el ? getComputedStyle(el).zIndex : '',
  };
}
