// todoFace.js — 任务列表面「换血」：待办列表（ReminderIsland 复刻版）
// （作者令 2026-11-02 20:02「先复刻 ReminderIsland 的待办体验，不接 Apple
//  Reminder；接我们自己的待办工具是下一步」）。
//
// 面形态（作者令三条）：
//   · 静默态  桌面 = **输入框下方居中小胶囊**（待办胶囊重做批，作者令 2026-11-02
//     11:52「收缩到只显示 8 待办 这么几个文字的大小程度的一个在对话框下方的
//     一个胶囊」）：宽度由内容决定（内联 flex + 左右对称内距），有逾期时**翻面**
//     成「N 逾期」（红字），两态外框宽高严格相等（宽度由 inline-grid 叠层取两态
//     最大自然宽，构造上恒等）；点开 = 自胶囊向上生长的浮层面板。
//     手机 = 胶囊同样显示（胶囊面交互批 2026-11-04 ⑤：mobileLayer.css 的整条
//     display:none 退役）；任务页（mobile/taskPanel.js 圆钮入口）照旧是页面即
//     展开态的另一条腿，入口退役归下游 entries 节点。
//   · 展开态 = ReminderIsland ReminderListView 整体复刻：极简头（计数/筛选/收起）、
//     可折叠筛选栏（All + 标签 chips + 新建 + 重要度四签）、列表行（重要度点/
//     mono 标题/备注/截止时间/标签徽标/完成钮三段动画）、行内编辑面板、行内创建行、
//     底部 Add footer（单标签视图）、空态；标签列尾垃圾桶 + 删除模式叉号（台账
//     台账1376 基线 2026-11-04：hover 动画与 450ms 教学气泡整套退役）、
//     横拖重排（幽灵快照 + 0.25 源 chip）、上划改名/下划删除（25px 阈值 +
//     pencil/xmark 渐显）、色板 popover（220px + 十色 5 列网格）。
//   · delegate 条目从本面退场：旧 delegate 渲染路径（taskList.js renderTaskList
//     链块渲染）代码零删除、本面零触发——sidebar 会话切换改调本文件
//     renderTodoTaskList；mobile/taskPanel.js 渲染核改挂本面（旧卡渲染代码保留）。
//
// 动画换算（SwiftUI spring(response:dampingFraction:) → 显式欧拉积分弹簧，
// ω=2π/response，k=ω²，c=2·ζ·ω，质量 1；逐条对应见 docs 对照表）：
//   open  spring(0.42, 0.8) / close spring(0.45, 1.0) / pop spring(0.45, 0.45)
//   行展开·创建行·行保存收起 spring(0.3, 0.8) / 完成钮 spring(0.3, 0.7) +
//   对勾 spring(0.2, 0.6) / 筛选栏·视图切换 spring(0.25, 0.8) /
//   重排 spring(0.3, 0.8) / 循环箭头 easeInOut 0.5 往复。
//   （chip 悬停 spring 与教学气泡两档已于 2026-11-04 R05 批退役 —— 悬停零动画。）
//
// 主题：ReminderIsland = macOS 纯黑毛玻璃。白透明度层按结构映射
// color-mix(var(--color-text) x%, transparent)（亮色 = 反极等价、暗色 ≈ 原生），
// 映射表落 docs 对照表「主题」节。

import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { isImeComposing } from './imeGuard.js';
import { sessionShowsNodeEntries } from './taskList.js';
import { todoStore, TAG_COLOR_PRESETS, PRIORITY_COLORS, PRIORITY_LABEL_KEYS } from './todoStore.js';
// 时间滚轮选择器（time-wheel-picker 实施批 2026-11-03，作者令 22:16/22:20）：
// 待办截止时刻从裸 `<input type="datetime-local">` 收编为同款点选面。交互单源
// = ./timeWheel.js；隐藏输入 `.tfl-due-input` 保留为值域载体（saveEditing /
// saveCreate 读面零改）。
import { pointTriggerHTML, attachTimeTriggers, msToLocalInput } from './timeWheel.js';
import { createToggle, setToggleState } from './toggle.js';
// 液态形变动效语言单源（todo-mobile-topmenu 批 · 2026-11-06）：顶部两级菜单的
// 展开/叠出、新建表单的自顶展开全部只吃这一条时长/曲线/属性白名单；reduced-motion
// 由同一模块降级（1ms），本文件不另写第二份毫秒数、不另造第二条曲线。
import { morphTransition, reducedMotion as lmReducedMotion } from './motion/liquidMorph.js';

/** 截止时间开关的无障碍标签 id 序号（同一面板可能同时挂编辑行与创建行两枚开关）。 */
let dueToggleSeq = 0;

const REDUCED_MOTION = typeof matchMedia === 'function' &&
  matchMedia('(prefers-reduced-motion: reduce)').matches;

// ── 弹簧积分器（SwiftUI spring 等价；单模拟多通道同拍落地）────────────────

function springK(response, damping) {
  const w0 = (2 * Math.PI) / response;
  return { k: w0 * w0, c: 2 * damping * w0 };
}

/**
 * 多通道弹簧动画（半隐式欧拉，dt 上限 1/30 防标签页切走后跳变）。
 * channels = [{ from, to, set(value) } ...]；返回 cancel()。
 *
 * `settle` = 收口阈值（2026-11-05 灵动岛批）：`{pos, vel}`，缺省 0.35px / 6px·s⁻¹。
 * **为什么需要它**：作者要「极轻微的过冲」，而岛形变的超调量级只有 2–4px；
 * 缺省阈值会在越过目标前的最后一小段就把值钉死（|x−to|<0.35 且 |v|<6 命中），
 * 超调**整条被吃掉**、量出来恒为 0 —— 那就不是弹簧了。形变通道给更严的收口
 * 阈值（0.05px / 1.5px·s⁻¹），让曲线真的翻过目标再落回，过冲可测可证。
 *
 * @param {Array<{from:number, to:number, set:(v:number)=>void}>} channels
 * @param {{response:number, damping:number, settle?:{pos?:number, vel?:number}}} opts
 * @param {() => void} [onDone]
 * @returns {() => void} cancel
 */
function springAnimate(channels, opts, onDone) {
  const { response, damping, settle } = opts;
  if (REDUCED_MOTION) {
    for (const ch of channels) ch.set(ch.to);
    if (onDone) onDone();
    return () => {};
  }
  const posTol = settle && settle.pos != null ? settle.pos : 0.35;
  const velTol = settle && settle.vel != null ? settle.vel : 6;
  const { k, c } = springK(response, damping);
  const st = channels.map((ch) => ({
    ch, x: ch.from, v: 0,
    done: Math.abs(ch.to - ch.from) < 0.01,
  }));
  let raf = 0;
  let last = performance.now();
  let stopped = false;
  const tick = (now) => {
    if (stopped) return;
    const frame = Math.min((now - last) / 1000, 1 / 30);
    last = now;
    // 定步长子步进（≤1/240s）：显式积分在大步长下会**吃掉振幅**（60Hz 一帧一步
    // 时 ω·dt≈0.28，实测把 1.1% 的理论超调磨成 0.02% ⇒ 「弹簧」读不出来）。
    // 子步进后曲线与解析解逐帧同量级，帧率无关（60/120/144Hz 过冲读数一致）。
    const steps = Math.max(1, Math.ceil(frame / (1 / 240)));
    const dt = frame / steps;
    let all = true;
    for (const s of st) {
      if (s.done) continue;
      for (let i = 0; i < steps; i++) {
        const a = -k * (s.x - s.ch.to) - c * s.v;
        s.v += a * dt;
        s.x += s.v * dt;
      }
      if (Math.abs(s.x - s.ch.to) < posTol && Math.abs(s.v) < velTol) {
        s.x = s.ch.to; s.done = true;
      } else all = false;
      s.ch.set(s.x);
    }
    if (all) {
      stopped = true;
      if (onDone) onDone();
      return;
    }
    raf = requestAnimationFrame(tick);
  };
  raf = requestAnimationFrame(tick);
  return () => { stopped = true; cancelAnimationFrame(raf); };
}

/** 弹簧参数表（NotchView / ReminderListView 逐条迁移）。 */
const SPRING = {
  open:   { response: 0.42, damping: 0.8 },
  close:  { response: 0.45, damping: 1.0 },
  // 岛形变（灵动岛批 2026-11-05）：**唯一一条几何弹簧**，四条通道（宽/高/两段
  // 圆角）同拍。作者令「处处运用弹簧物理效果，仅有极轻微的过冲（overshoot）」：
  //   ω = 2π/response，k = ω²（mass 1），c = 2ζω；
  //   response 0.38 ⇒ ω ≈ 16.53 rad/s、k ≈ 273.4、c ≈ 28.4；
  //   ζ = 0.82 ⇒ 理论超调 e^(−πζ/√(1−ζ²)) ≈ 1.1%（实测见批文档 readings.json）；
  //   settle 收口阈值收紧（0.05px / 1.5px·s⁻¹）⇒ 超调不被阈值吃掉、可测可证。
  // 禁用项对照：不出现 bouncy/back/elastic 类越界缓动（ζ 恒 ≥ 0.8 的「收得住」
  // 区间，超调量级 2–4px，收口半周期内落定）。
  morph:  { response: 0.38, damping: 0.82, settle: { pos: 0.05, vel: 1.5 } },
  // 展开几何生长的历史档（件5 临界阻尼）随灵动岛批退役：几何现在全走 morph。
  grow:   { response: 0.34, damping: 1.0 },
  pop:    { response: 0.45, damping: 0.45 },
  row:    { response: 0.3,  damping: 0.8 },
  check:  { response: 0.2,  damping: 0.6 },
  view:   { response: 0.25, damping: 0.8 },
  hover:  { response: 0.3,  damping: 0.65 },
  hintIn: { response: 0.3,  damping: 0.85 },
};

/** 空白区建：pointerdown → click 位移超过此值即判为拖动/滑屏（不建）。
 *  8px = 手指抖动余量；与 tests/todo-capsule-create.spec.mjs ③ 同值。 */
const BLANK_DRAG_PX = 8;

/** 事项名单行化上限（件6 2026-11-05，作者令「事项名应该简单」）= 120 字符。
 *  取值理由（可复核）：
 *   · 面板内容宽 ≈ 380px、12px 等宽体中文一字 ≈ 12px ⇒ 一行 ≈ 30 字；120 字
 *     ≈ 4 行容量，再长的名字在任何行宽下都必然读作截断（行内 `.tfl-row-title`
 *     单行 ellipsis），用户不会误以为「没存全」；
 *   · 120 字已远超一行可读量，足够区分条目；细节归备注（备注仍无上限）——
 *     两层分工正是本件要的「名与具体内容分开」；
 *   · 定值 → 代码常量与 `maxlength` 属性、门禁断言三处同一读数，可机械判。 */
const NAME_MAX = 120;

/** 事项名输入面（编辑面板与创建行的**唯一**构造点；件6 2026-11-05）。
 *  单行 = `input[type=text]`（永不 textarea）；`maxlength` 属性给浏览器原生夹紧，
 *  `input` 事件里再夹一次（粘贴、程序赋值、拖放都收敛到上限），并把换行剥离
 *  （粘贴含换行的文本折叠成单行）。IME 组字期不动值（组字中的候选串会被打断）。 */
function nameInputEl(cls, value = '') {
  const input = el('input', cls);
  input.type = 'text';
  input.maxLength = NAME_MAX;
  input.value = value;
  input.addEventListener('input', (e) => {
    // IME 组字期不动值（组字中的候选串会被打断）。判定走唯一判定点
    // imeGuard.isImeComposing（scripts/check-ime-guard.mjs：文件内不得自读
    // 原始 composition 标志——件6 的 nameInputEl 曾自读 e.isComposing，本批
    // 合并时按门禁收归）。
    if (isImeComposing(e, input)) return;
    const flat = input.value.replace(/[\r\n]+/g, '').slice(0, NAME_MAX);
    if (flat === input.value) return;
    const at = input.selectionStart == null ? flat.length : input.selectionStart;
    input.value = flat;
    const pos = Math.min(at, flat.length);
    try { input.setSelectionRange(pos, pos); } catch { /* 非文本态忽略 */ }
  });
  // 真粘贴：剪贴板里的多行文本在单行输入面上会被浏览器转成空格（Chromium）
  // 或直接吞掉换行——两种都不是「折叠成单行」的确定语义。含换行时自己接管，
  // 剥掉换行后按单行插入；无换行时交回浏览器原生粘贴（保留 undo 栈）。
  input.addEventListener('paste', (e) => {
    const data = e.clipboardData ? e.clipboardData.getData('text') : '';
    if (!data || !/[\r\n]/.test(data)) return;
    e.preventDefault();
    const clean = data.replace(/[\r\n]+/g, '');
    const start = input.selectionStart == null ? input.value.length : input.selectionStart;
    const end = input.selectionEnd == null ? start : input.selectionEnd;
    const next = (input.value.slice(0, start) + clean + input.value.slice(end)).slice(0, NAME_MAX);
    input.value = next;
    const pos = Math.min(start + clean.length, NAME_MAX);
    try { input.setSelectionRange(pos, pos); } catch { /* 非文本态忽略 */ }
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  return input;
}

// ── 小件 ────────────────────────────────────────────────────────────────

/** 行尾 detail 折角钮的下折箭头（两段化批 2026-11-05）：命名空间 API 装配，
 *  零裸 innerHTML（component-contract R5）；颜色走 currentColor，不新增色值。 */
function chevronEl() {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 12 12');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(NS, 'path');
  path.setAttribute('d', 'M3 5l3 3 3-3');
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.5');
  path.setAttribute('stroke-linecap', 'round');
  path.setAttribute('stroke-linejoin', 'round');
  svg.appendChild(path);
  return svg;
}

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

/** 行尾删除 × 的 SVG 元素（命名空间 API 装配）。
 *  既有件多为内联 SVG 字符串（裸 innerHTML 赋值写面，属 component-contract R5 的存量
 *  基线债）；本批新增的删除钮**不进这笔债**，改由 createElementNS 逐属性装配 ——
 *  viewBox/描边/圆头与 X_VIEWBOX/X_PATH_D 单源逐字对应，渲染结果与字符串形态等价。 */
function xIconEl() {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', X_VIEWBOX);
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(NS, 'path');
  path.setAttribute('d', X_PATH_D);
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.5');
  path.setAttribute('stroke-linecap', 'round');
  svg.appendChild(path);
  return svg;
}

/** 实心圆 + 叉字形（`XMARK_CIRCLE_SVG` 的同款几何，走命名空间 API —— 组件契约
 *  R5 禁裸 innerHTML 赋值，本钮是本批新增调用点，必须落在基线条目内）。
 *  形状色全部交给 CSS（`.tfl-chip-x svg circle` 吃 currentColor，
 *  `path` 吃 `--tfl-glyph`）⇒ JS 侧零色值字面量。 */
function xmarkCircleGlyph() {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const circle = document.createElementNS(NS, 'circle');
  circle.setAttribute('cx', '12');
  circle.setAttribute('cy', '12');
  circle.setAttribute('r', '11');
  circle.setAttribute('fill', 'currentColor');
  const path = document.createElementNS(NS, 'path');
  path.setAttribute('d', 'M8.4 8.4l7.2 7.2M15.6 8.4l-7.2 7.2');
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke-width', '2.2');
  path.setAttribute('stroke-linecap', 'round');
  svg.append(circle, path);
  return svg;
}

/** lucide 图标槽（`<i data-lucide="…">`，与筛选/收起/建行诸钮同一渲染腿）。
 *  同样避开裸 innerHTML 赋值：本仓图标走 createIconsIn 的 data-lucide 替换，
 *  元素自带属性即足够，无需再拼字符串。 */
function lucideIcon(name) {
  const ic = document.createElement('i');
  ic.setAttribute('data-lucide', name);
  return ic;
}

const PLUS_CIRCLE_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="11" fill="currentColor"/><path d="M12 6.5v11M6.5 12h11" stroke="rgba(0,0,0,0.55)" stroke-width="2.2" stroke-linecap="round"/></svg>';
const CHECK_CIRCLE_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="11" fill="currentColor"/><path d="M7 12.5l3.4 3.4L17.2 9" stroke="rgba(0,0,0,0.55)" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" fill="none"/></svg>';
const PENCIL_CIRCLE_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="11" fill="currentColor"/><path d="M8.2 15.8l.6-2.6 5.2-5.2 2 2-5.2 5.2-2.6.6z" fill="rgba(0,0,0,0.55)" stroke="rgba(0,0,0,0.35)" stroke-width="0.6" stroke-linejoin="round"/></svg>';
const XMARK_CIRCLE_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="11" fill="currentColor"/><path d="M8.4 8.4l7.2 7.2M15.6 8.4l-7.2 7.2" stroke="rgba(0,0,0,0.55)" stroke-width="2.2" stroke-linecap="round"/></svg>';
const CHECK_SVG = '<svg viewBox="0 0 14 14" aria-hidden="true"><path d="M3 7.4l3 3L11 4.2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
// 行尾删除（细线 ×，克制；不用填充圆钮——与完成钮的实心语义区分，避免被读成「已完成」）
// 几何单源仍在此注释块（viewBox 14 / 两笔 45° 交叉 / 1.5 描边 / 圆头）；装配改走
// 命名空间 API（见下方 xIconEl）——component-contract R5 禁裸 innerHTML 赋值，
// 而本钮是本批新增调用点，必须落在基线内。逐属性与字符串形态一一对应 ⇒ 渲染不变。
const X_VIEWBOX = '0 0 14 14';
const X_PATH_D = 'M3.6 3.6l6.8 6.8M10.4 3.6l-6.8 6.8';
const CLOCK_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>';

/** 截止时间文案（ReminderIsland = style:.time 纯时刻；web 面条目常跨天，
 *  非今日补日期消歧——对照表「截止时间文案」行申报的等价适配）。 */
function dueText(ms) {
  const d = new Date(ms);
  const hm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  const now = new Date();
  if (d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()) return hm;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

function dueInputValue(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 截止时刻面（时间滚轮选择器批 2026-11-03）：同款点选钮 + 隐藏值域输入。
 *  隐藏输入保留 `.tfl-due-input` 类与 datetime-local 串值域 ⇒ 既有读面
 *  （saveEditing / saveCreate 的 `new Date(dueEl.value)`）零改动；
 *  面板确认后经 attachTimeTriggers 的 onPointChange 回写。
 *  @param {number} ms 初始时刻 */
function dueFieldEls(ms) {
  const date = dueInputValue(ms).slice(0, 10);
  const time = dueInputValue(ms).slice(11, 16);
  const holder = el('div', 'tfl-due-holder');
  holder.innerHTML = pointTriggerHTML({ date, time, label: t('timewheel.deadline') });
  const hidden = el('input', 'tfl-due-input');
  hidden.type = 'hidden';
  hidden.value = dueInputValue(ms);
  holder.appendChild(hidden);
  attachTimeTriggers(holder, {
    onPointChange: (btn) => {
      const next = `${btn.dataset.date}T${btn.dataset.time}`;
      const at = new Date(next).getTime();
      if (!Number.isNaN(at)) hidden.value = msToLocalInput(at);
    },
  });
  // 面板点击不冒泡到行体（行体点击 = 保存/进编辑）
  holder.addEventListener('click', (e) => e.stopPropagation());
  return { holder, hidden };
}

// ── 面实例 ───────────────────────────────────────────────────────────────

class TodoFace {
  /** @param {{mobile?: boolean}} opts */
  constructor(opts = {}) {
    this.mobile = !!opts.mobile;
    this.expanded = this.mobile; // 手机页面 = 直接展开态（页面即面）
    this.destroyed = false;
    // 编辑/创建/键盘选中态（ReminderListView 同名状态）
    this.editingId = null;
    this.creating = false;
    this.selectedId = null;
    this.showFilterBar = false;
    this.editingTagId = null;
    this.creatingTag = false;
    // 完成动画在飞行集（renderRows 跳过重建，动画完整落地后再销账）
    this.completingRows = new Set();
    // 删除在飞行集（同款：退场动画期间不重入）
    this.deletingRows = new Set();
    this.completingEls = new Map();
    this.expandedJustRendered = false;
    // chips 拖拽/滚动
    this.chipDrag = null; // { id, dir, dx, dy, ghost, frozen: Map }
    this.chipScroll = 0;
    // 标签删除模式（R06：点垃圾桶 ⇒ 每枚标签显叉号；叉号点击 = 走二次确认删除）。
    // 悬停教学气泡随 R05（「标签 hover 不要动画」）整链退役 —— 原 chipHover/
    // hintTimer/hintLayer 三个状态位同时摘除（hover 侧零状态）。
    this.chipDeleteMode = false;
    this.tagPopover = null;
    this.outsideBound = false;
    this.cancelMorph = null;
    // 灵动岛批 2026-11-05：岛盒缓存 + 形变闸 + 面板尺寸守望
    //   capBox = 收起态岛的实测盒（展开前拍，收起时作弹簧终点）；
    //   morphing = 形变在飞（面板 ResizeObserver 此时不抢方向盘）；
    //   islandW/H = 展开态岛的当前目标尺寸（内容变化后重新贴合用）。
    this.capBox = null;
    this.morphing = false;
    this.islandW = 0;
    this.islandH = 0;
    this.islandRo = null;
    this.enteredIds = new Set();
    this.unsub = todoStore.subscribe(() => this.onStoreChange());
    this.buildDom();
    if (this.expanded) this.panel.classList.add('tfl-panel-open');
    this.renderAll();
  }

  // ── DOM 骨架 ──────────────────────────────────────────────────────────

  buildDom() {
    // 挂载槽（重做批）：胶囊行的居中容器 —— 宽度 100%、内容 inline-flex 居中
    // （#task-list 本身仍是流内块，胶囊不再 let 宽度跟随容器 width:100%）。
    this.mount = el('div', 'tfl-mount');

    // 胶囊（静默态）。宽度策略 = **两态取最大自然宽**，由布局引擎算（inline-grid
    // 两格叠同一格 = max-content 合成），不写死数值、不需要 canvas 量字：
    //   外框 = inline-flex（宽度 = 内容），内层 `.tfl-cap-faces` = inline-grid
    //   单一格，两个文案副本各占同一格 ⇒ 胶囊宽 = max(「N 待办」「N 逾期」)，
    //   窄的那面在格内居中 ⇒ 翻面前后外框宽严格相等（0px）、内容始终居中，
    //   左右内距由同一 padding 对称给出；三位数（如 888 待办）自然撑宽不溢出。
    this.capsule = el('button', 'tfl-capsule');
    this.capsule.type = 'button';
    this.capsule.setAttribute('aria-expanded', String(this.expanded));
    this.capsule.setAttribute('aria-label', t('todoface.aria'));
    this.capsuleDot = el('span', 'tfl-capsule-dot');
    this.capsuleFaces = el('span', 'tfl-cap-faces');
    // 两面：待办面（默认）/ 逾期面（翻面）。同一时刻只有一面在流内
    // （另一面 visibility:hidden + aria-hidden）—— 无并排、无「待办」数字
    // 残留在读屏/取文本口径里（断言 ⑤：逾期时「待办」不可见且不可读）。
    this.faceTodo = el('span', 'tfl-cap-face tfl-cap-face-todo');
    this.capsuleCount = el('span', 'tfl-capsule-count');
    this.faceTodo.appendChild(this.capsuleCount);
    this.faceOverdue = el('span', 'tfl-cap-face tfl-cap-face-overdue');
    this.capsuleOverdue = el('span', 'tfl-capsule-overdue');
    this.faceOverdue.appendChild(this.capsuleOverdue);
    this.capsuleFaces.append(this.faceTodo, this.faceOverdue);
    this.capsule.append(this.capsuleDot, this.capsuleFaces);
    // 点击 = 展开；已展开时再点 = 收起（胶囊在浮层下方始终可点，作 toggle）
    this.capsule.addEventListener('click', () => {
      if (this.expanded) this.collapse(); else this.expand();
    });

    // 岛容器（`.tfl-card`）= **唯一变形元素**（灵动岛批 2026-11-05）：收起态它本身
    // 就是胶囊（宽 max-content = 内容层撑盒、高 --tfl-cap-h、圆角 999），展开态仍是
    // 它 —— 由本文件 morphOpen/morphClose 逐帧写 width/height/圆角。全生命周期同一
    // 引用（id `todo-island`，从不重建）；内容层（胶囊 / 面板）在它内部切换。
    this.card = el('div', 'tfl-card');
    this.card.id = 'todo-island';
    this.card.dataset.state = this.expanded ? 'open' : 'capsule';
    if (this.mobile) this.card.classList.add('tfl-mobile');

    // 面板（展开态 = ReminderListView 整体）
    this.panel = el('div', 'tfl-panel');
    this.buildHeader();
    // 筛选栏（All + 标签 chips + 垃圾桶 + 重要度四签）= **仅桌面腿**构建。
    // 手机页面态（todo-mobile-topmenu 批 2026-11-06）：`.tfl-filter-toggle` /
    // `.tfl-collapse-btn` / chipsRow / prioRow 整族**不建**（不是隐藏）—— 顶部
    // 控制改由一枚悬浮「⋯」菜单承载（buildTopMenu），旧入口真退役、不留两套。
    if (!this.mobile) this.buildFilterBar();
    // 列表包裹层（胶囊面交互批 2026-11-04）：列表 + 右下角新建加号 + 撤回条
    // 三者共一个定位祖先 —— 加号压不到列表末行（列表内距让位由 CSS 承担），
    // 撤回条浮在列表底部中央而不是页面级 absolute（手机页面态无定位卡容器）。
    this.listWrap = el('div', 'tfl-list-wrap');
    this.listScroll = el('div', 'tfl-list');
    this.listScroll.tabIndex = -1;
    this.listScroll.setAttribute('role', 'listbox');
    this.listScroll.setAttribute('aria-label', t('todoface.aria'));
    this.listScroll.addEventListener('keydown', (e) => this.onListKey(e));
    this.listInner = el('div', 'tfl-list-inner');
    this.listScroll.appendChild(this.listInner);
    this.listWrap.appendChild(this.listScroll);
    this.buildFab();
    this.buildUndoBar();
    this.footer = el('div', 'tfl-footer');
    if (this.filterWrap) this.panel.append(this.header, this.filterWrap, this.listWrap, this.footer);
    else this.panel.append(this.header, this.listWrap, this.footer);
    // 岛 = 骨架；两个内容层（胶囊 / 面板）都在它内部 —— 骨架是唯一的形状载体。
    this.card.appendChild(this.panel);
    // 胶囊（收起态内容层）= 岛骨架里的第二个内容层（灵动岛批 2026-11-05）：
    // 收起态显示它、展开态显示上面的 panel，两者都在 .tfl-card 内部切换。
    this.card.appendChild(this.capsule);
    // 新建表单（todo-mobile-topmenu 批 2026-11-06：承载从底部抽屉改为**顶部**，
    // 表单本体/写口零改动；`.mf-drawer` 一族与定时任务页共用，顶部形态走
    // `.mf-drawer-top` 修饰类 —— 那一路（js/scheduled-task.js 的底抽屉）零改动）。
    if (this.mobile) {
      this.buildCreateSheet();
      // 顶部悬浮「⋯」+ 两级菜单（同一批）：手机页面态顶部唯一入口。
      this.buildTopMenu();
    }
    // 空白区建（作者令 2026-11-04 ②）：列表空白 / footer 空白点击 ⇒ 行内新建
    this.bindBlankCreate();
    this.mount.appendChild(this.card);
    this.root = this.mount;
    if (this.mobile) {
      // 手机 = 页面即展开态：挂载槽即页面体，卡容器回退为流内块（CSS .tfl-mobile）
      this.mount.classList.add('tfl-mount-mobile');
      // 计数搬家一次（todo-fullbleed 批 2026-11-05）：手机页面态表头只有**一行**
      // （计数 + 筛选），面板头那条 .tp-hd 计数行退役 ⇒ 计数节点直接搬进
      // .tfl-header-count 槽（同一颗 DOM 节点，桌面展开态走 expand() 的同一条搬家口）。
      // 手机无胶囊（display:none）⇒ moveCountInto 的 FLIP 首帧宽为 0 自然跳过，
      // 落位是瞬时的，无动画残留。
      this.moveCountInto(true);
    }
  }

  buildHeader() {
    this.header = el('div', 'tfl-header');
    this.headerCount = el('span', 'tfl-header-count');
    this.header.appendChild(this.headerCount);
    const spacer = el('span', 'tfl-header-spacer');
    this.header.appendChild(spacer);
    // 手机页面态：本行只剩计数 —— 筛选/收起两枚旧入口不建（顶部控制收敛到
    // 悬浮「⋯」菜单，见 buildTopMenu；桌面腿零改动）。
    if (this.mobile) return;
    // 筛选开关（28×24 点击区；ReminderIsland compactHeader 同几何）
    this.filterToggle = el('button', 'tfl-icon-btn tfl-filter-toggle');
    this.filterToggle.type = 'button';
    this.filterToggle.title = t('todoface.filter');
    this.filterToggle.setAttribute('aria-label', t('todoface.filter'));
    this.filterToggle.innerHTML = '<i data-lucide="list-filter"></i>';
    this.filterToggle.addEventListener('click', () => {
      this.showFilterBar = !this.showFilterBar;
      this.renderFilterBar();
      // 复刻 compactHeader 注释：筛选栏展开动画打断焦点注册 → 重建（0.4s 一档）
      this.refocusList(400);
    });
    // 收起（web 面的 notch-tap 等价 affordance；quit/settings 两键无对应语义退场）
    this.collapseBtn = el('button', 'tfl-icon-btn tfl-collapse-btn');
    this.collapseBtn.type = 'button';
    this.collapseBtn.title = t('todoface.collapse');
    this.collapseBtn.setAttribute('aria-label', t('todoface.collapse'));
    this.collapseBtn.innerHTML = '<i data-lucide="chevron-up"></i>';
    this.collapseBtn.addEventListener('click', () => this.collapse());
    this.header.append(this.filterToggle, this.collapseBtn);
  }

  buildFilterBar() {
    this.filterWrap = el('div', 'tfl-filter-wrap');
    this.filterWrap.style.display = 'none';
    this.filterBar = el('div', 'tfl-filterbar');
    // 第一行：All + 箭头 + chips 滚动区 + 新建
    this.chipsRow = el('div', 'tfl-chips-row');
    this.allChip = el('button', 'tfl-chip tfl-chip-all');
    this.allChip.type = 'button';
    this.allChip.innerHTML = '<i data-lucide="tray-full"></i>';
    this.allChip.appendChild(el('span', null, t('todoface.all')));
    this.allChip.addEventListener('click', () => this.switchView(null));
    this.chipsRow.appendChild(this.allChip);
    this.chipLeft = el('button', 'tfl-chip-arrow tfl-chip-arrow-l');
    this.chipLeft.type = 'button';
    this.chipLeft.innerHTML = '<i data-lucide="chevron-left"></i>';
    this.chipLeft.addEventListener('click', () => this.scrollChips(-1));
    this.chipRight = el('button', 'tfl-chip-arrow tfl-chip-arrow-r');
    this.chipRight.type = 'button';
    this.chipRight.innerHTML = '<i data-lucide="chevron-right"></i>';
    this.chipRight.addEventListener('click', () => this.scrollChips(1));
    this.chipsViewport = el('div', 'tfl-chips-viewport');
    this.chipsTrack = el('div', 'tfl-chips-track');
    this.chipsViewport.appendChild(this.chipsTrack);
    this.chipsViewport.addEventListener('wheel', (e) => {
      // 水平意图主导（|dX|>|dY|）才接管——斜向滚动垂直分量不吞（复刻 scrollMonitor 判据）
      if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
        e.preventDefault();
        this.setChipScroll(this.chipScroll + (e.deltaX || e.deltaY));
      }
    }, { passive: false });
    // R05 垃圾桶（台账1376，作者 recall 2026-11-02 12:05「标签列放垃圾桶图标」）：
    // 常驻在 chips 行**尾**（不随 chips 滚动区滚走 —— 与新建钮不同位置：新建在
    // track 内（跟着最后一条标签走），垃圾桶在行尾固定）。点按 = 切换标签删除模式
    // （R06：每枚标签显叉号）；armed 态同时给样式与 aria-pressed。
    this.trashBtn = el('button', 'tfl-chip-trash');
    this.trashBtn.type = 'button';
    // 图标走 DOM 构建（lucide 扫 data-lucide 属性自渲染）——不新增裸 innerHTML
    // 赋值（组件契约 R5 判据；本文件基线条目只许减不许增）。
    const trashIcon = document.createElement('i');
    trashIcon.setAttribute('data-lucide', 'trash-2');
    this.trashBtn.appendChild(trashIcon);
    this.trashBtn.title = t('todoface.tagDeleteTitle');
    this.trashBtn.setAttribute('aria-label', t('todoface.tagDeleteTitle'));
    this.trashBtn.setAttribute('aria-pressed', 'false');
    this.trashBtn.addEventListener('click', () => this.toggleChipDeleteMode());
    this.chipsRow.append(this.chipLeft, this.chipsViewport, this.chipRight, this.trashBtn);
    // 第二行：重要度四签
    this.prioRow = el('div', 'tfl-prio-row');
    this.filterBar.append(this.chipsRow, this.prioRow);
    this.filterWrap.appendChild(this.filterBar);
  }

  buildFooter() {
    this.footer.textContent = '';
    if (todoStore.isAllView()) return; // 总条目禁止创建（复刻）
    const btn = el('button', 'tfl-add-btn');
    btn.type = 'button';
    if (this.creating) {
      btn.innerHTML = `<span class="tfl-add-ic tfl-add-ic-done">${CHECK_CIRCLE_SVG}</span>`;
      btn.appendChild(el('span', null, t('todoface.done')));
      btn.addEventListener('click', () => this.saveCreate());
    } else {
      btn.innerHTML = `<span class="tfl-add-ic">${PLUS_CIRCLE_SVG}</span>`;
      btn.appendChild(el('span', null, t('todoface.add')));
      btn.addEventListener('click', () => this.startCreate());
    }
    this.footer.appendChild(btn);
  }

  /* ── 右下角新建加号（胶囊面交互批 · 作者令 2026-11-04 ①）──────────────────
     形态口径：小而克制的圆钮（桌面 34px / 手机 44px，两套尺寸口径在 CSS
     `.tfl-fab-add` + `body.mobile-layer` 分支里），次级色（--color-text 8% 底 /
     60% 墨），不抢主视觉；位置 = 列表包裹层右下角（absolute），列表末行让位由
     `.tfl-list { padding-bottom }` 给出 ⇒ 永不压住最后一行。
     行为 = 复用既有行内创建行（startCreate → buildCreateRow → saveCreate 同一
     条路径，不另开写口）。创建行开在场时本钮仍常驻（不清场）：重复点按由
     startCreate 的幂等闸吸收 —— 同一条创建行，绝不开第二条。

     ★ todo-fullbleed 批（作者令 2026-11-05 17:33「加号按钮使用我们 canvas 同款的
     按钮风格放在右下角悬浮」）：**手机腿加挂 `.g2-fab`** —— 材质（--cv-bg 玻璃底
     / 1px 描边 / 零投影）、尺寸（48 = --mtap-lg = Canvas 圆钮档）、hover/active/
     focus-visible/disabled 六态、按压 spring 全部来自 css/glass.css 的圆钮级浮件
     档单源（该类同时是 Canvas 工具钮 / 页面壳返回钮 / ask 钮的基准类），不新造
     第二套按钮样式。桌面腿（34px 小钮）刻意零改动 —— 本批只动手机端两个页面。*/
  buildFab() {
    const fab = el('button', 'tfl-fab-add' + (this.mobile ? ' g2-fab' : ''));
    fab.type = 'button';
    fab.title = t('todoface.fabAdd');
    fab.setAttribute('aria-label', t('todoface.fabAdd'));
    fab.appendChild(lucideIcon('plus'));
    fab.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (!this.expanded) return;
      this.startCreate();
    });
    this.fabAdd = fab;
    this.listWrap.appendChild(fab);
  }

  /* ── 新建表单：**顶部**承载（todo-mobile-topmenu 批 · 2026-11-06）─────────
     上一批（todo-fullbleed 2026-11-05）把新建入口做成底部抽屉；本批按作者令
     改为**从页顶下滑展开**：表单覆盖在内容之上，**不占文档流、不改滚区高度**
     （`.mf-drawer` 仍是 absolute + inset:0，包含块 = 页面壳 `.mobile-page`；
     列表首条 rect / 页面壳滚动容器 / `.tfl-list` 的 scrollTop·scrollHeight 在
     开合前后逐值不变 —— 机械读数见 tests/todo-mobile-topmenu.spec.mjs ①）。
     结构 = 与定时任务页**同一族** `.mf-drawer`，本面加 `.mf-drawer-top` 修饰类
     把 flex 主轴从 bottom 翻到 top、盒圆角翻到下沿；`js/scheduled-task.js` 那一路
     （底部抽屉）类名/几何零改动，共用面不受影响。
     表单本体复用 `buildCreateRow()`（标题/备注/优先级/截止时间同一条 DOM、
     同一条 saveCreate 写口），零第二套表单。 */
  buildCreateSheet() {
    const sheet = el('div', 'mf-drawer mf-drawer-top');
    sheet.hidden = true;
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-modal', 'true');
    sheet.setAttribute('aria-label', t('todoface.sheetTitle'));

    const scrim = el('div', 'mf-drawer-scrim');
    scrim.addEventListener('click', () => this.cancelCreate());

    const box = el('div', 'mf-drawer-box');
    const head = el('div', 'mf-drawer-head');
    head.appendChild(el('span', 'mf-drawer-title', t('todoface.sheetTitle')));
    const cancel = el('button', 'mf-drawer-cancel-btn', t('todoface.cancel'));
    cancel.type = 'button';
    cancel.addEventListener('click', () => this.cancelCreate());
    const done = el('button', 'mf-drawer-done-btn', t('todoface.done'));
    done.type = 'button';
    done.addEventListener('click', () => this.saveCreate());
    head.append(cancel, done);

    this.sheetBody = el('div', 'mf-drawer-body');
    box.append(head, this.sheetBody);
    sheet.append(scrim, box);
    this.sheetEl = sheet;
    this.sheetBox = box;
    this.card.appendChild(sheet);
  }

  /** 表单开合（单一状态位 = `creating`，本函数是唯一显隐写口）。
   *  `hidden` 不能参与过渡（display:none）⇒ 先摘 hidden、下一帧再翻 `.is-open`，
   *  起点/终点几何全在 CSS（`.mf-drawer-top .mf-drawer-box`）；过渡串由液态形变
   *  单源生成（属性白名单 transform/opacity，slow 档 = 大位移腿，reduced ⇒ 1ms）。 */
  setSheetOpen(open) {
    const sheet = this.sheetEl;
    const box = this.sheetBox;
    if (!sheet || !box) return;
    clearTimeout(this.sheetTimer);
    const reduced = lmReducedMotion();
    box.style.transition = morphTransition(['transform', 'opacity'], { duration: 'slow', reduced });
    if (open) {
      const wasHidden = sheet.hidden;
      sheet.hidden = false;
      if (wasHidden || !box.classList.contains('is-open')) {
        box.classList.remove('is-open');
        void box.offsetHeight; // 强制回流：display 翻转后的起始帧必须被采纳
        requestAnimationFrame(() => {
          if (this.sheetBox === box && this.creating) box.classList.add('is-open');
        });
      }
      return;
    }
    if (sheet.hidden) return;
    box.classList.remove('is-open');
    // 收尾 = 过渡结束后再 hidden（进位一帧），reduced-motion 走 1ms 档。
    this.sheetTimer = setTimeout(() => { sheet.hidden = true; }, reduced ? 8 : 420);
  }

  /* ── 顶部悬浮「⋯」+ 两级菜单（todo-mobile-topmenu 批 · 2026-11-06）─────────
     形态（作者图2 / 图3）：
       ① 顶部**唯一**入口 = 一枚 48px 悬浮圆钮「⋯」（`.g2-fab` 圆钮级浮件档：
          材质/六态/按压全来自 css/glass.css 单源；视觉 48 = --ds-hit-tap-min，
          命中盒 ≥ --ds-hit-box-min）。落位镜像页面壳返回钮（top 16+safe / 右 16）。
       ② 一级 = 浮起的圆角**毛玻璃**面板（`.nf-glass` 单源，不另造模糊面）：
          图标+文字行、组间分隔线、危险项红字（--color-danger 既有语义色）、
          点面板外收起。
       ③ 二级 = 在**同一个菜单根**里叠出第二块玻璃面板（右缘对齐、左移一档压住
          一级面板下沿）：一级面板变暗后退（`.is-back`），条目带 ✓ 选中态，
          选中即回写 store 并整菜单收起 —— 是「叠出」不是「替换」，不跳页。
     动效 = js/motion/liquidMorph.js 单源（base 档 280ms / 唯一曲线 / 属性白名单
     transform+opacity）；reduced-motion 同源降级 1ms。
     与旧顶部控制的关系：`.tfl-filter-toggle` / `.tfl-collapse-btn` / chipsRow /
     prioRow 在手机腿**不建**（buildHeader / buildDom 早退）—— 本菜单是它们唯一
     的替代面，两者不并存。 */

  buildTopMenu() {
    const btn = el('button', 'tfl-topmenu-btn g2-fab');
    btn.type = 'button';
    btn.title = t('todoface.menu');
    btn.setAttribute('aria-label', t('todoface.menu'));
    btn.setAttribute('aria-haspopup', 'menu');
    btn.setAttribute('aria-expanded', 'false');
    btn.appendChild(lucideIcon('more-horizontal'));
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      this.toggleMenu();
    });
    this.menuBtn = btn;

    const root = el('div', 'tfl-menu');
    root.hidden = true;
    // 点外部收起：透明幕布铺满页面壳（图2 里一级面板之外的内容不变暗、但要能
    // 一击收起 ⇒ 幕布零底色，只吃指针）。
    const scrim = el('div', 'tfl-menu-scrim');
    scrim.addEventListener('click', () => this.closeMenu());
    const panel = el('div', 'tfl-menu-panel nf-glass');
    panel.setAttribute('role', 'menu');
    panel.setAttribute('aria-label', t('todoface.menu'));
    const sub = el('div', 'tfl-menu-sub nf-glass');
    sub.hidden = true;
    sub.setAttribute('role', 'menu');
    root.append(scrim, panel, sub);
    this.menuRoot = root;
    this.menuPanel = panel;
    this.menuSub = sub;
    this.card.append(btn, root);
  }

  toggleMenu() {
    if (this.menuOpen) this.closeMenu(); else this.openMenu();
  }

  openMenu() {
    if (!this.menuRoot || this.destroyed) return;
    clearTimeout(this.menuTimer);
    this.menuOpen = true;
    this.menuSubKind = null;
    this.menuRoot.hidden = false;
    this.menuRoot.style.pointerEvents = '';
    this.menuBtn.setAttribute('aria-expanded', 'true');
    this.menuSub.hidden = true;
    this.menuPanel.classList.remove('is-back');
    this.renderMenu();
    const p = this.menuPanel;
    p.style.transition = morphTransition(['transform', 'opacity'], { duration: 'base', reduced: lmReducedMotion() });
    p.classList.remove('is-open');
    void p.offsetHeight;
    requestAnimationFrame(() => { if (this.menuOpen) p.classList.add('is-open'); });
  }

  closeMenu() {
    if (!this.menuOpen) return;
    this.menuOpen = false;
    this.menuSubKind = null;
    this.menuBtn.setAttribute('aria-expanded', 'false');
    const reduced = lmReducedMotion();
    const sub = this.menuSub;
    if (sub && !sub.hidden) {
      sub.classList.remove('is-open');
      clearTimeout(this.subTimer);
      this.subTimer = setTimeout(() => { sub.hidden = true; }, reduced ? 8 : 300);
    }
    this.menuPanel.classList.remove('is-back', 'is-open');
    // 收场期间不拦点击（面板在淡出，用户可能已点向别处）
    this.menuRoot.style.pointerEvents = 'none';
    clearTimeout(this.menuTimer);
    this.menuTimer = setTimeout(() => {
      if (!this.menuOpen && this.menuRoot) this.menuRoot.hidden = true;
    }, reduced ? 8 : 300);
  }

  /** 一级面板内容（每次打开 / store 变化时重画，读数永远新鲜）。 */
  renderMenu() {
    const panel = this.menuPanel;
    if (!panel) return;
    panel.textContent = '';
    const rows = [
      this.menuRow({
        icon: 'plus-circle', label: t('todoface.sheetTitle'),
        onTap: () => { this.closeMenu(); this.startCreate(); },
      }),
      this.menuRow({
        icon: 'tag', label: t('todoface.menuView'), sub: this.viewLabel(), chevron: true, sep: true,
        onTap: (row) => this.openMenuSub('tags', row),
      }),
      this.menuRow({
        icon: 'arrow-up-down', label: t('todoface.menuPrio'), sub: this.prioLabel(), chevron: true,
        onTap: (row) => this.openMenuSub('prio', row),
      }),
      this.menuRow({
        icon: 'tags', label: t('todoface.tagNew'), sep: true,
        onTap: () => { this.closeMenu(); this.openTagPopover(null, this.menuBtn); },
      }),
      this.menuRow({
        icon: 'trash-2', label: t('todoface.tagDeleteTitle'), danger: true, sep: true,
        onTap: (row) => this.openMenuSub('deleteTags', row),
      }),
    ];
    panel.append(...rows);
    if (typeof createIconsIn === 'function') createIconsIn(panel);
  }

  /** 菜单行（一级/二级同一构造：图标 + 主文 + 可选次文 + 右侧 ›；
   *  二级可选态行在最左留出 ✓ 槽 —— 选中/未选中同宽，零跳动）。 */
  menuRow({ icon, label, sub, chevron, danger, sep, check, onTap }) {
    const row = el('button', 'tfl-menu-row'
      + (danger ? ' is-danger' : '') + (sep ? ' is-sep' : ''));
    row.type = 'button';
    row.setAttribute('role', 'menuitem');
    // 勾选槽（图3：✓ 在文字左侧，未选中行留同宽空槽）
    if (check !== undefined) {
      const slot = el('span', 'tfl-menu-check' + (check ? ' is-on' : ''));
      if (check) slot.appendChild(lucideIcon('check'));
      row.appendChild(slot);
      row.setAttribute('role', 'menuitemradio');
      row.setAttribute('aria-checked', String(!!check));
    }
    if (icon) row.appendChild(lucideIcon(icon));
    const body = el('span', 'tfl-menu-row-body');
    body.appendChild(el('span', 'tfl-menu-row-label', label));
    if (sub) body.appendChild(el('span', 'tfl-menu-row-sub', sub));
    row.appendChild(body);
    if (chevron) {
      row.appendChild(lucideIcon('chevron-right'));
      row.setAttribute('aria-haspopup', 'true');
    }
    row.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      onTap(row);
    });
    return row;
  }

  /** 一级面板里「标签」「重要度」的次行读数（与二级面板选中态同源）。 */
  viewLabel() {
    const tag = todoStore.activeTagId == null ? null : todoStore.tagById(todoStore.activeTagId);
    return tag ? tag.name : t('todoface.all');
  }

  prioLabel() {
    const sel = [...todoStore.selectedPriorities].sort((a, b) => a - b);
    if (!sel.length) return t('todoface.all');
    return sel.map((p) => t(PRIORITY_LABEL_KEYS[p])).join(' · ');
  }

  /** 二级面板：叠在一级面板下沿（对齐被点那一行），一级变暗后退。 */
  openMenuSub(kind, anchorRow) {
    if (!this.menuSub || !anchorRow) return;
    this.menuSubKind = kind;
    const sub = this.menuSub;
    this.paintSub(kind);
    const rootR = this.menuRoot.getBoundingClientRect();
    const r = anchorRow.getBoundingClientRect();
    const height = sub.offsetHeight;
    const rootH = rootR.height;
    let top = Math.round(r.top - rootR.top - 8);
    const maxTop = Math.round(rootH - height - 16);
    if (top > maxTop) top = Math.max(8, maxTop);
    sub.style.top = `${top}px`;
    sub.hidden = false;
    this.menuPanel.classList.add('is-back');
    const reduced = lmReducedMotion();
    sub.style.transition = morphTransition(['transform', 'opacity'], { duration: 'base', reduced });
    sub.classList.remove('is-open');
    void sub.offsetHeight;
    requestAnimationFrame(() => {
      if (this.menuSubKind === kind && this.menuOpen) sub.classList.add('is-open');
    });
  }

  /** 二级面板内容（kind：tags / prio / deleteTags）。 */
  paintSub(kind) {
    const sub = this.menuSub;
    if (!sub) return;
    sub.textContent = '';
    const meta = {
      tags: { icon: 'tag', title: t('todoface.menuView'), value: this.viewLabel() },
      prio: { icon: 'arrow-up-down', title: t('todoface.menuPrio'), value: this.prioLabel() },
      deleteTags: { icon: 'trash-2', title: t('todoface.tagDeleteTitle'), value: '', danger: true },
    }[kind];
    if (!meta) return;
    const head = el('div', 'tfl-menu-sub-head' + (meta.danger ? ' is-danger' : ''));
    head.appendChild(lucideIcon(meta.icon));
    const body = el('span', 'tfl-menu-row-body');
    body.appendChild(el('span', 'tfl-menu-row-label', meta.title));
    if (meta.value) body.appendChild(el('span', 'tfl-menu-row-sub', meta.value));
    head.appendChild(body);
    head.appendChild(lucideIcon('chevron-down'));
    sub.appendChild(head);

    const list = el('div', 'tfl-menu-sub-list');
    if (kind === 'tags') {
      list.append(
        this.menuRow({
          label: t('todoface.all'), check: todoStore.isAllView(),
          onTap: () => { this.closeMenu(); this.switchView(null); },
        }),
        ...todoStore.tags.map((tag) => this.menuRow({
          label: tag.name, check: todoStore.activeTagId === tag.id,
          onTap: () => { this.closeMenu(); this.switchView(tag.id); },
        })),
      );
    } else if (kind === 'prio') {
      const sel = todoStore.selectedPriorities;
      for (let p = 0; p <= 3; p++) {
        list.appendChild(this.menuRow({
          label: t(PRIORITY_LABEL_KEYS[p]), check: sel.has(p),
          onTap: () => { this.closeMenu(); todoStore.togglePriority(p); },
        }));
      }
    } else if (kind === 'deleteTags') {
      const tags = todoStore.tags;
      if (!tags.length) list.appendChild(el('div', 'tfl-menu-empty', t('todoface.menuNoTags')));
      for (const tag of tags) {
        list.appendChild(this.menuRow({
          icon: 'trash-2', label: tag.name, danger: true,
          onTap: () => { this.closeMenu(); todoStore.deleteTag(tag.id); },
        }));
      }
    }
    sub.appendChild(list);
    if (typeof createIconsIn === 'function') createIconsIn(sub);
  }

  /* ── 撤回条（完成后的撤销路径；作者令 2026-11-04 ④「取消完成必须可达」）────
     完成帧发出后浮出一条 `已完成 · 撤回`，点击 = todoStore.uncompleteItem
     （uncompleteTodo 帧 + 行回可见面）。6s 自动消失；reduced-motion 下无过渡。 */
  buildUndoBar() {
    const bar = el('div', 'tfl-undo');
    bar.hidden = true;
    bar.setAttribute('role', 'status');
    this.undoText = el('span', 'tfl-undo-text', t('todoface.completedToast'));
    this.undoBtn = el('button', 'tfl-undo-btn', t('todoface.undo'));
    this.undoBtn.type = 'button';
    this.undoBtn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      this.undoComplete();
    });
    bar.append(this.undoText, this.undoBtn);
    this.undoBar = bar;
    this.listWrap.appendChild(bar);
  }

  /** 完成后的撤回窗口（单条：后完成覆盖前一条的撤回目标，与系统提示同语义）。 */
  showUndo(id) {
    if (!this.undoBar) return;
    this.undoId = id;
    this.undoBar.hidden = false;
    clearTimeout(this.undoTimer);
    this.undoTimer = setTimeout(() => this.hideUndo(), 6000);
  }

  hideUndo() {
    clearTimeout(this.undoTimer);
    this.undoId = null;
    if (this.undoBar) this.undoBar.hidden = true;
  }

  /** 取消完成（uncompleteTodo 帧）—— 行回到可见面，计数翻牌回滚。 */
  undoComplete() {
    const id = this.undoId;
    this.hideUndo();
    if (!id) return;
    todoStore.uncompleteItem(id);
  }

  /* ── 空白区建（作者令 2026-11-04 ②「点击空白处也能创建」）──────────────────
     落点 = `.tfl-list` 的空白（行之间的空隙 / 列表下方的空白）与 `.tfl-footer`
     的空白（Add footer 之外的留白）。命中即 startCreate（同一 saveCreate 路径）。

     **防误触口径（四条，逐条落在此处 + tests/todo-capsule-create.spec.mjs）**：
      ① 互斥：新建行已开 或 卡内任一输入框聚焦 ⇒ 不打断、不重开（直接 return）；
      ② 命中排除：点击落在行 / 创建行 / chip / 按钮 / 编辑面板 / 筛选栏 / 表头 /
         加号钮 / 滚动条 ⇒ 不触发（浏览器滚动条命中目标是宿主元素本身，按
         clientWidth 判别，见 isScrollbarClick）；
      ③ 滚动抑制：pointerdown → click 位移 > 8px（拖动列表 / 滑屏）⇒ 不触发；
      ④ 触摸同口径：只挂 `click`（桌面与触摸由浏览器统一派发一次），**不**另挂
         touchend ⇒ 无双发；位移判据在触摸端同样生效。 */
  bindBlankCreate() {
    const hosts = [this.listScroll, this.footer];
    for (const host of hosts) {
      host.addEventListener('pointerdown', (e) => {
        // 只记起点：位移判据在 click 拍消费（③）。
        this.blankDown = { x: e.clientX, y: e.clientY, target: e.target, host };
      });
      host.addEventListener('click', (e) => this.onBlankClick(e, host));
    }
  }

  /** 点击是否落在「可建空白」上（口径见 bindBlankCreate 四条注解）。 */
  onBlankClick(e, host) {
    const down = this.blankDown;
    this.blankDown = null;
    if (this.destroyed || !this.expanded) return;
    // ① 互斥：创建行在开 / 任一输入框聚焦 ⇒ 不打断不重开
    if (this.creating || this.editingId != null) return;
    const active = document.activeElement;
    if (active && this.card.contains(active) && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')) return;
    // ② 命中排除：可交互件（行/chip/按钮/编辑面板/筛选栏/表头/加号/滚动条）
    const tgt = e.target;
    if (tgt && tgt.closest && tgt.closest(
      '.tfl-row, .tfl-create-row, .tfl-chip, .tfl-chip-add, .tfl-chip-arrow, .tfl-edit-panel, '
      + '.tfl-filter-wrap, .tfl-header, .tfl-fab-add, .tfl-add-btn, .tfl-undo, '
      + 'button, input, textarea, a, [role="button"]',
    )) return;
    if (this.isScrollbarClick(e, host)) return;
    // ③ 滚动抑制：位移 > 8px = 拖动/滑屏，不是点击
    if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) > BLANK_DRAG_PX) return;
    // ④ 单一事件源（click）⇒ 触摸端不会双发；此处直接进创建。
    if (!this.editingTagId && !this.creatingTag) this.startCreate();
  }

  /** 滚动条命中：指针横/纵向落在宿主的内容盒之外（轨道区）。 */
  isScrollbarClick(e, host) {
    if (!host || !host.getBoundingClientRect) return false;
    const r = host.getBoundingClientRect();
    const x = e.clientX - r.left;
    const y = e.clientY - r.top;
    const sbw = host.offsetWidth - host.clientWidth;
    const sbh = host.offsetHeight - host.clientHeight;
    if (sbw > 0 && x > host.clientWidth) return true;
    if (sbh > 0 && y > host.clientHeight) return true;
    return false;
  }

  // ── 渲染 ──────────────────────────────────────────────────────────────

  renderAll() {
    this.renderCapsule();
    this.renderHeader();
    this.renderFilterBar();
    this.renderRows();
    this.renderFooter();
    if (typeof createIconsIn === 'function') createIconsIn(this.card);
  }

  onStoreChange() {
    if (this.destroyed) return;
    this.renderAll();
    if (this.expanded && !this.mobile && !this.outsideBound) this.bindOutsideClose();
    // 菜单开着时重画两级读面（勾选态/次行读数跟随 store；面板状态类不重播动画）
    if (this.menuOpen) {
      this.renderMenu();
      if (this.menuSubKind) this.paintSub(this.menuSubKind);
    }
  }

  /** 胶囊渲染 = 翻面语义（待办胶囊重做批 2026-11-02 11:52）。
   *
   *  语义：`overdueCount() > 0` → 显示「N 逾期」（红字，dot 转红）；
   *        `overdueCount() === 0` → 显示「N 待办」。
   *  即「逾期优先、不同时显示」—— 与作者原文「出现逾期的时候就是翻转一下
   *  然后只显示 1 逾期 胶囊的大小不改变」逐条对齐（不是并排两段）。
   *
   *  翻转落地：两个面常驻 DOM、叠在同一 grid 格内（宽度恒 = 两态最大自然宽，
   *  CSS 承担），本函数只切 `.is-overdue` 类 —— 类切换驱动 CSS `@keyframes`
   *  tfl-cap-flip（前半程旧面不可见、后半程新面不可见 ⇒ 过渡期间外框零形变、
   *  不留并排中间态）。`prefers-reduced-motion: reduce` 下动画整条退役（CSS）
   *  ⇒ 直接换面，状态读数一致。*/
  renderCapsule() {
    const n = todoStore.openCount();
    const od = todoStore.overdueCount();
    this.capsuleCount.textContent = t('todoface.capsuleCount', { n: String(n) });
    this.capsuleOverdue.textContent = t('todoface.overdue', { n: String(od) });
    const overdue = od > 0;
    const prevFace = this.capFace; // 'overdue' | 'todo' | undefined（首次渲染不播）
    this.capFace = overdue ? 'overdue' : 'todo';
    this.capsule.classList.toggle('has-overdue', overdue);
    this.capsule.classList.toggle('is-overdue', overdue);
    // 翻面动画只在「面真的换了」那一拍播一次（同一面内的数字增减不重播）
    if (prevFace !== undefined && prevFace !== this.capFace) {
      this.capsule.classList.remove('tfl-cap-flip');
      void this.capsule.offsetWidth; // 强制回流，重启动画
      this.capsule.classList.add('tfl-cap-flip');
      clearTimeout(this.capFlipTimer);
      this.capFlipTimer = setTimeout(() => {
        if (this.capsule) this.capsule.classList.remove('tfl-cap-flip');
      }, 260);
    }
    // L3 计数翻转：读数真的变了那一拍，在计数**当前所在宿主**上播一次翻牌
    // （展开态 = 面板头槽；收起态 = 胶囊）。文本上面已写终值 ⇒ 多条同时变化只
    // 翻一次、恒落终值；动画只是视觉层，绝不参与读数。
    if (this.lastCount !== undefined && this.lastCount !== n) {
      const host = this.capsuleFaces.parentElement;
      if (host) {
        host.classList.remove('tfl-count-flip');
        void host.offsetWidth; // 强制回流，重启动画
        host.classList.add('tfl-count-flip');
        clearTimeout(this.countFlipTimer);
        this.countFlipTimer = setTimeout(() => { host.classList.remove('tfl-count-flip'); }, 340);
      }
    }
    this.lastCount = n;
    // 翻面态的可读性收口：非当期面 aria-hidden + 胶囊 aria-label 带出唯一读数
    this.faceTodo.setAttribute('aria-hidden', String(overdue));
    this.faceOverdue.setAttribute('aria-hidden', String(!overdue));
    this.capsule.setAttribute('aria-label',
      overdue ? t('todoface.overdue', { n: String(od) }) : t('todoface.capsuleCount', { n: String(n) }));
    // 收起态：读数一变，岛的**自然宽**也变（两态叠层取最大自然宽）⇒ 重拍盒缓存，
    // 下一次收起的弹簧终点就是这颗胶囊的真实尺寸（不是旧读数留下的宽度）。
    if (!this.expanded) this.captureCapsuleBox();
  }

  renderHeader() {
    // L2 单一计数节点：标题计数**不是**另画的一份文案 —— 它就是胶囊里那颗
    // `.tfl-cap-faces`（展开时搬进 .tfl-header-count 槽）。所以这里绝不能再写
    // textContent（会抹掉搬进来的节点）；头槽的可读性只靠下面的状态镜像。
    const n = todoStore.openCount();
    const od = todoStore.overdueCount();
    this.headerCount.classList.toggle('is-overdue', od > 0);
    this.headerCount.classList.toggle('has-count', n > 0);
    // 手机页面态两枚旧控件不建（buildHeader 早退）—— 这里只镜像桌面腿状态。
    if (this.filterToggle) this.filterToggle.classList.toggle('active', this.showFilterBar);
    if (this.collapseBtn) this.collapseBtn.hidden = this.mobile; // 手机页面由页面框架收回
  }

  renderFilterBar() {
    // 手机页面态无筛选栏（buildDom 不建）：视图/重要度搬进 ⋯ 菜单二级面板。
    if (!this.filterWrap) return;
    this.filterWrap.style.display = this.showFilterBar ? '' : 'none';
    if (!this.showFilterBar) return;
    // All chip 选中态
    const allSel = todoStore.isAllView();
    this.allChip.classList.toggle('selected', allSel);
    this.allChip.setAttribute('aria-pressed', String(allSel));
    // 标签 chips（拖拽中不重建，保手势状态）
    if (!this.chipDrag) this.renderChips();
    this.renderPrioChips();
    this.updateChipScrollBounds();
    if (typeof createIconsIn === 'function') createIconsIn(this.filterBar);
  }

  renderChips() {
    this.chipsTrack.textContent = '';
    this.chipEls = new Map();
    for (const tag of todoStore.tags) {
      const chip = this.buildTagChip(tag);
      this.chipEls.set(tag.id, chip);
      this.chipsTrack.appendChild(chip);
    }
    // 新建标签钮（chips 行尾；ReminderIsland createCalendarButton 同位）
    const add = el('button', 'tfl-chip-add');
    add.type = 'button';
    add.title = t('todoface.tagNew');
    add.setAttribute('aria-label', t('todoface.tagNew'));
    add.innerHTML = '<i data-lucide="plus"></i>';
    add.addEventListener('click', () => this.openTagPopover(null));
    this.chipsTrack.appendChild(add);
    this.updateChipScrollBounds();
  }

  buildTagChip(tag) {
    const isSelected = todoStore.activeTagId === tag.id;
    const chip = el('button', 'tfl-chip tfl-chip-tag');
    chip.type = 'button';
    chip.dataset.tagId = tag.id;
    chip.classList.toggle('selected', isSelected);
    chip.style.setProperty('--tag-color', tag.color);
    const dot = el('span', 'tfl-chip-dot');
    chip.appendChild(dot);
    chip.appendChild(el('span', 'tfl-chip-name', tag.name));
    // R06 叉号（台账1376「点垃圾桶后在标签上出现叉号用于删除」）：节点常驻、
    // 仅删除模式可见（CSS 由 `.tfl-chips-row.tfl-chips-deleting` 驱动 —— 状态单点
    // 在行级类，不在每枚 chip 上各挂一份）。用 span[role=button] 而非 button：
    // chip 本身是 button，嵌套 button 非法（HTML 内容模型）。
    const x = el('span', 'tfl-chip-x');
    x.setAttribute('role', 'button');
    x.setAttribute('tabindex', '-1');
    x.setAttribute('aria-label', t('todoface.tagDeleteTitle') + '：' + tag.name);
    x.title = t('todoface.tagDeleteTitle') + '：' + tag.name;
    // 同款「实心圆 + 叉」字形，但走 SVG DOM 构建（组件契约 R5：不新增裸 innerHTML
    // 赋值；形状色全交给 CSS —— `.tfl-chip-x svg circle/path` 吃 currentColor 与
    // --tfl-glyph，JS 侧零色值字面量）。
    x.appendChild(xmarkCircleGlyph());
    x.addEventListener('click', (ev) => {
      ev.stopPropagation(); // 不许冒泡成 chip 的视图切换
      ev.preventDefault();
      this.confirmDeleteTag(tag); // 破坏性操作 ⇒ 既有二次确认单点（HIG）
    });
    chip.appendChild(x);
    chip.setAttribute('aria-pressed', String(isSelected));
    chip.addEventListener('click', () => {
      // 手势后抑制误触发（click 在 pointerup 后仍会派发——拖/划过即吞掉本次 click）
      if (this.chipDrag || chip.dataset.gestureDone) { delete chip.dataset.gestureDone; return; }
      this.switchView(isSelected ? null : tag.id);
    });
    // R05：悬停**零**动画零教学气泡（原 hover 放大挤开 + 450ms 四向提示整套退役，
    // 见 css/todo-face.css 同名注）——这里不再挂 pointerenter/pointerleave。
    // 拖拽手势（横拖重排 / 上划改名 / 下划删除——方向锁判定复刻 DragGesture）
    chip.addEventListener('pointerdown', (e) => this.onChipPointerDown(e, tag, chip));
    return chip;
  }

  /** 标签删除模式开关（R06 入口）：行级单类 + 垃圾桶 armed 态 + aria 镜像。
   * 关模式时不留任何逐 chip 状态（叉号可见性全由 `.tfl-chips-deleting` 一处决定）。 */
  toggleChipDeleteMode() {
    this.chipDeleteMode = !this.chipDeleteMode;
    this.chipsRow.classList.toggle('tfl-chips-deleting', this.chipDeleteMode);
    this.trashBtn.classList.toggle('armed', this.chipDeleteMode);
    this.trashBtn.setAttribute('aria-pressed', String(this.chipDeleteMode));
  }

  renderPrioChips() {
    this.prioRow.textContent = '';
    const sel = todoStore.selectedPriorities;
    for (let p = 0; p <= 3; p++) {
      const chip = el('button', 'tfl-chip tfl-chip-prio');
      chip.type = 'button';
      chip.style.setProperty('--tag-color', PRIORITY_COLORS[p]);
      const isSel = sel.has(p);
      chip.classList.toggle('selected', isSel);
      const dot = el('span', 'tfl-chip-dot');
      chip.appendChild(dot);
      chip.appendChild(el('span', 'tfl-chip-name', t(PRIORITY_LABEL_KEYS[p])));
      chip.setAttribute('aria-pressed', String(isSel));
      chip.addEventListener('click', () => {
        todoStore.togglePriority(p);
        this.refocusList(400);
      });
      this.prioRow.appendChild(chip);
    }
  }

  renderFooter() {
    this.buildFooter();
    this.footer.style.display = todoStore.isAllView() ? 'none' : '';
    // 加号常在场（创建行开时不清场）：重复点按由 startCreate 的幂等闸吸收
    // （同一条创建行，绝不开第二条）——「重复点击零新行」的机械判据。
    if (this.fabAdd) this.fabAdd.hidden = false;
  }

  renderRows() {
    const items = todoStore.visibleItems();
    const nextIds = new Set(items.map((it) => it.id));
    // 键盘选中失效清理（复刻 onChange(of: visibleReminders.map(\.id))）
    if (this.selectedId && !nextIds.has(this.selectedId)) this.selectedId = null;
    if (this.editingId && !nextIds.has(this.editingId)) this.editingId = null;

    // 行内创建行在场时：只重画创建行之前的行（创建行 DOM 保输入态）
    this.listInner.textContent = '';
    // 抽屉宿主（手机腿）同拍清场：表单只在一处，绝不同屏两份
    if (this.mobile && this.sheetBody) this.sheetBody.textContent = '';
    for (const item of items) {
      if (this.completingRows.has(item.id) && this.completingEls.get(item.id)) {
        // 完成动画在飞：沿用既有 DOM（动画不因重渲打断）
        this.listInner.appendChild(this.completingEls.get(item.id));
        continue;
      }
      const row = this.buildRow(item, item.id === this.editingId);
      if (!REDUCED_MOTION && !this.enteredIds.has(item.id) && !this.expandedJustRendered) {
        row.classList.add('tfl-row-enter');
      }
      this.enteredIds.add(item.id);
      this.listInner.appendChild(row);
    }
    this.expandedJustRendered = false;
    // 创建行宿主：手机页面态 = 底部抽屉（列表不再被常驻表单挤成一条）；桌面 = 流内
    if (this.creating) {
      (this.mobile && this.sheetBody ? this.sheetBody : this.listInner).appendChild(this.buildCreateRow());
    }
    if (!items.length && !this.creating) this.listInner.appendChild(this.buildEmpty());
    if (typeof createIconsIn === 'function') {
      createIconsIn(this.listInner);
      if (this.mobile && this.sheetBody) createIconsIn(this.sheetBody);
    }
    // 表单开合随 creating 单一状态位（唯一显隐写口 = setSheetOpen）
    if (this.mobile) this.setSheetOpen(this.creating);
    // 编辑面板/创建面板高度过渡：先关后开（下一拍加 open 类，0fr→1fr 播动画）
    requestAnimationFrame(() => {
      const scope = [this.listInner];
      if (this.mobile && this.sheetBody) scope.push(this.sheetBody);
      for (const host of scope) {
        const wraps = host.querySelectorAll('.tfl-edit-wrap');
        wraps.forEach((w) => w.classList.add('open'));
      }
    });
  }

  buildEmpty() {
    const empty = el('div', 'tfl-empty');
    if (todoStore.isAllView()) {
      empty.classList.add('tfl-empty-all');
      empty.textContent = t('todoface.emptyAll');
    } else {
      empty.textContent = t('todoface.emptyAdd');
      empty.addEventListener('click', () => this.startCreate());
    }
    return empty;
  }

  // ── 行（ReminderRowView 复刻）──────────────────────────────────────────

  buildRow(item, editing = false) {
    const row = el('div', 'tfl-row');
    row.dataset.id = item.id;
    row.setAttribute('role', 'option');
    row.setAttribute('aria-selected', String(this.selectedId === item.id));
    row.classList.toggle('tfl-selected', this.selectedId === item.id);

    const dot = el('span', 'tfl-row-dot');
    dot.style.background = PRIORITY_COLORS[item.priority] || PRIORITY_COLORS[0];
    row.appendChild(dot);

    const main = el('div', 'tfl-row-main');
    const titleline = el('div', 'tfl-row-titleline');
    // 行内只读名称面（件6 2026-11-05）：编辑态与常态同一元素 —— 编辑中输入面搬到
    // 下方编辑面板里（名与 detail 两个独立输入面，DOM 序名在前），行头保留单行
    // ellipsis 的静态名，条目身份随手可读、不出现「空行头」。
    // 两段化（待办结构化批 2026-11-05）：行头显 name，detail 收在下方折叠区。
    const title = el('span', 'tfl-row-title', item.name);
    title.title = item.name;
    titleline.appendChild(title);
    // 标签徽标（总条目视图；行尾右对齐与标题同行，标题优先截断——复刻）
    if (todoStore.isAllView()) {
      const tag = todoStore.tagById(item.tagId);
      if (tag) {
        const badge = el('span', 'tfl-row-badge', tag.name);
        badge.title = tag.name;
        badge.style.setProperty('--tag-color', tag.color);
        titleline.appendChild(badge);
      }
    }
    main.appendChild(titleline);
    // detail 折叠（两段化，待办结构化批 2026-11-05）：列表行默认**只显 name**，
    // detail 收在一行折叠区里；行尾折角钮切换，展开态 = 多行全文（CSS --open）。
    if (item.detail && !editing) {
      const foldBtn = el('button', 'tfl-row-fold');
      foldBtn.type = 'button';
      foldBtn.setAttribute('aria-expanded', 'false');
      foldBtn.setAttribute('aria-label', t('todoface.detailToggle'));
      foldBtn.title = t('todoface.detailToggle');
      foldBtn.appendChild(chevronEl());
      const detail = el('div', 'tfl-row-detail', item.detail);
      detail.hidden = true;
      foldBtn.addEventListener('click', (e) => {
        // 行体点击 = 进编辑；折叠钮必须自己吃掉事件，否则一按就跳编辑态。
        e.preventDefault();
        e.stopPropagation();
        const open = detail.hidden;
        detail.hidden = !open;
        foldBtn.setAttribute('aria-expanded', String(open));
        row.classList.toggle('tfl-row-open', open);
      });
      titleline.appendChild(foldBtn);
      main.appendChild(detail);
    }
    if (item.due != null && !editing) {
      const due = el('span', 'tfl-row-due');
      due.innerHTML = CLOCK_SVG;
      const isOverdue = item.due < Date.now();
      due.appendChild(el('span', isOverdue ? 'tfl-row-due-text overdue' : 'tfl-row-due-text', dueText(item.due)));
      due.classList.toggle('overdue', isOverdue);
      main.appendChild(due);
    }
    // 点击行体 = 进编辑（展开+聚焦统一入口；编辑态点击文本区 = 保存收起）
    if (!editing) {
      main.addEventListener('click', () => { if (!this.editingId) this.startEditing(item.id); });
    }
    row.appendChild(main);
    row.appendChild(this.buildCompleteButton(item));
    row.appendChild(this.buildDeleteButton(item, row));
    if (editing) {
      row.classList.add('tfl-row-editing');
      const wrap = el('div', 'tfl-edit-wrap');
      wrap.appendChild(this.buildEditPanel(item));
      row.appendChild(wrap);
    }
    return row;
  }

  buildCompleteButton(item) {
    const btn = el('button', 'tfl-complete');
    btn.type = 'button';
    btn.setAttribute('aria-label', t('todoface.complete'));
    const ring = el('span', 'tfl-complete-ring');
    const fill = el('span', 'tfl-complete-fill');
    fill.innerHTML = CHECK_SVG;
    btn.append(ring, fill);
    btn.addEventListener('click', () => this.performComplete(item.id, btn.closest('.tfl-row')));
    return btn;
  }

  /** 行尾删除钮（作者令 2026-11-04 ④「删除 = 行尾 ×」）：桌面 hover 渐显、
   *  手机常显 44px 触摸档（CSS `body.mobile-layer .tfl-del`）。stopPropagation
   *  ⇒ 不触发主区「点行进编辑」。 */
  buildDeleteButton(item, row) {
    const btn = el('button', 'tfl-del');
    btn.type = 'button';
    btn.setAttribute('aria-label', t('todoface.del'));
    btn.title = t('todoface.del');
    btn.appendChild(xIconEl());
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      this.performDelete(item.id, row);
    });
    return btn;
  }

  /** 完成三段动画（performComplete 逐帧迁移）：
   *  0s 圆环→绿填 spring(0.3,0.7)；+0.15s 对勾 scale spring(0.2,0.6)；
   *  +0.5s 行淡出上移 easeOut 0.3；+0.8s 真删除（store completeItem）。 */
  performComplete(id, rowEl) {
    if (this.completingRows.has(id)) return;
    this.completingRows.add(id);
    const btn = rowEl && rowEl.querySelector('.tfl-complete');
    if (btn) {
      btn.classList.add('filling');
      setTimeout(() => btn.classList.add('checked'), 150);
    }
    if (rowEl) setTimeout(() => rowEl.classList.add('tfl-row-removing'), 500);
    setTimeout(() => {
      if (rowEl && rowEl.isConnected) {
        (this.completingEls = this.completingEls || new Map()).set(id, rowEl);
      }
      todoStore.completeItem(id);
      // 完成 = 行退场 + 完成帧（三段动画/计数翻牌全程未改）；退场后给撤回窗口，
      // 取消完成的唯一可达路径（作者令 2026-11-04 ④）。
      this.showUndo(id);
      setTimeout(() => { // 下一拍重渲后销账（避免本拍把在飞 DOM 接回去）
        this.completingRows.delete(id);
        if (this.completingEls) this.completingEls.delete(id);
      }, 0);
    }, 800);
  }

  /** 行尾删除（物理删除 `deleteScheduledTask`；与「完成」语义区分，不可逆）。
   *  形态：行尾 ×，桌面 hover 渐显、手机常显且 44px 触摸档（CSS）；点击先播
   *  退场（tfl-row-removing 同款过渡），180ms 后发帧，视觉与数据同拍收敛。 */
  performDelete(id, rowEl) {
    if (this.deletingRows.has(id)) return;
    this.deletingRows.add(id);
    if (rowEl) rowEl.classList.add('tfl-row-removing');
    setTimeout(() => {
      todoStore.removeItem(id);
      this.deletingRows.delete(id);
    }, 180);
  }

  buildEditPanel(item) {
    const panel = el('div', 'tfl-edit-panel');
    // ① 事项名（件6 2026-11-05）：**单行**轻量输入面（input，非 textarea）+
    //    长度上限 + 换行剥离，构造点 = nameInputEl（唯一）。占面板第一行，
    //    回车即保存（与备注同口径）；`tfl-row-title-input` 类保留给既有选择器。
    //    两段化（待办结构化批 2026-11-05）：这一格就是 name（行头显示的那一段）。
    const name = nameInputEl('tfl-edit-name tfl-row-title-input', item.name);
    name.placeholder = t('todoface.titlePh');
    name.setAttribute('aria-label', t('todoface.titlePh'));
    name.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !isImeComposing(e, name)) { e.preventDefault(); e.stopPropagation(); this.saveEditing(); }
    });
    panel.appendChild(name);
    this.titleInput = name;
    // ② detail（两段化的第二段）：文本域（可从既有长文本/多行内容起编，值里的
    //    换行不会被 input 元素吞掉）。回车沿用「回车即保存」口径（与名输入同一
    //    手感，shift+回车才换行）；空内容时高度 = 单行，与名输入面视觉同厚度。
    const detailEl = el('textarea', 'tfl-edit-notes tfl-edit-detail');
    detailEl.rows = 1;
    detailEl.value = item.detail || '';
    detailEl.placeholder = t('todoface.notesPh');
    detailEl.setAttribute('aria-label', t('todoface.detailFieldLabel'));
    detailEl.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' || e.shiftKey) return;
      if (isImeComposing(e, detailEl)) return;
      e.preventDefault();
      e.stopPropagation();
      this.saveEditing();
    });
    panel.appendChild(detailEl);
    // 重要度四选
    const prioRow = el('div', 'tfl-edit-prio');
    this.editPrio = item.priority;
    for (let p = 0; p <= 3; p++) {
      const b = el('button', 'tfl-edit-prio-btn' + (p === this.editPrio ? ' selected' : ''));
      b.type = 'button';
      b.style.setProperty('--tag-color', PRIORITY_COLORS[p]);
      const dot = el('span', 'tfl-chip-dot');
      b.appendChild(dot);
      b.appendChild(el('span', 'tfl-edit-prio-name', t(PRIORITY_LABEL_KEYS[p])));
      b.addEventListener('click', () => {
        this.editPrio = p;
        prioRow.querySelectorAll('.tfl-edit-prio-btn').forEach((x, i) => x.classList.toggle('selected', i === p));
      });
      prioRow.appendChild(b);
    }
    panel.appendChild(prioRow);
    // 截止时间开关 + 时刻输入
    const dueRow = el('div', 'tfl-edit-due');
    let hasDue = item.due != null;
    // toggle-unify 批 2026-11-05：原 `.tfl-switch`（自画轨道+圆钮+内嵌文字，26×15）
    // 整族并入全站唯一开关组件 js/toggle.js 的 `.nb-toggle`。文字标签留在控件外
    // （状态事实仍可见，aria-labelledby 把它接到开关上），控件本体的 role / aria-checked
    // / 键盘行为 / 几何全部由组件单点提供。
    const dueLabelId = `tfl-due-toggle-label-${++dueToggleSeq}`;
    const dueLabel = el('span', 'tfl-due-label', hasDue ? t('todoface.hasDue') : t('todoface.noDue'));
    dueLabel.id = dueLabelId;
    const toggle = createToggle({ on: hasDue, label: t('todoface.hasDue') });
    toggle.setAttribute('aria-labelledby', dueLabelId);
    toggle.dataset.tflDue = '1';
    // 截止时间开关 + 同款时间滚轮点选面（time-wheel-picker 批 2026-11-03）
    const { holder: dueHolder, hidden: dueInput } = dueFieldEls(item.due != null ? item.due : Date.now() + 3600_000);
    dueHolder.style.display = hasDue ? '' : 'none';
    toggle.addEventListener('click', () => {
      hasDue = !hasDue;
      setToggleState(toggle, hasDue);
      dueLabel.textContent = hasDue ? t('todoface.hasDue') : t('todoface.noDue');
      dueHolder.style.display = hasDue ? '' : 'none';
    });
    dueRow.append(dueLabel, toggle, dueHolder);
    panel.appendChild(dueRow);
    this.editDraft = { notesEl: detailEl, dueInput, get hasDue() { return hasDue; } };
    return panel;
  }

  // ── 行内创建行（inlineCreateRow 复刻）─────────────────────────────────

  buildCreateRow() {
    const row = el('div', 'tfl-create-row');
    const dot = el('span', 'tfl-row-dot');
    dot.style.background = PRIORITY_COLORS[0];
    const head = el('div', 'tfl-create-head');
    this.createTitle = nameInputEl('tfl-row-title-input');
    this.createTitle.placeholder = t('todoface.titlePh');
    this.createTitle.setAttribute('aria-label', t('todoface.titlePh'));
    this.createTitle.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !isImeComposing(e, this.createTitle)) { e.preventDefault(); e.stopPropagation(); this.saveCreate(); }
    });
    head.append(dot, this.createTitle);
    row.appendChild(head);

    const wrap = el('div', 'tfl-edit-wrap');
    const panel = el('div', 'tfl-edit-panel tfl-create-panel');
    const notes = el('input', 'tfl-edit-notes');
    notes.type = 'text';
    notes.placeholder = t('todoface.notesPh');
    notes.setAttribute('aria-label', t('todoface.notesPh'));
    notes.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !isImeComposing(e, notes)) { e.preventDefault(); e.stopPropagation(); this.saveCreate(); }
    });
    panel.appendChild(notes);
    const prioRow = el('div', 'tfl-edit-prio');
    // 重要度选择跨重渲保持（store 广播重画时不清场）；startCreate 时重置为 0。
    if (this.createPrio == null) this.createPrio = 0;
    for (let p = 0; p <= 3; p++) {
      const b = el('button', 'tfl-edit-prio-btn' + (p === this.createPrio ? ' selected' : ''));
      b.type = 'button';
      b.style.setProperty('--tag-color', PRIORITY_COLORS[p]);
      b.appendChild(el('span', 'tfl-chip-dot'));
      b.appendChild(el('span', 'tfl-edit-prio-name', t(PRIORITY_LABEL_KEYS[p])));
      b.addEventListener('click', () => {
        this.createPrio = p;
        prioRow.querySelectorAll('.tfl-edit-prio-btn').forEach((x, i) => x.classList.toggle('selected', i === p));
      });
      prioRow.appendChild(b);
    }
    panel.appendChild(prioRow);
    const dueRow = el('div', 'tfl-edit-due');
    let hasDue = false;
    // 同一组件、同一契约（见编辑行处注释）：文字标签留在控件外并做 aria-labelledby。
    const dueLabelId = `tfl-due-toggle-label-${++dueToggleSeq}`;
    const dueLabel = el('span', 'tfl-due-label', t('todoface.noDue'));
    dueLabel.id = dueLabelId;
    const toggle = createToggle({ on: false, label: t('todoface.noDue') });
    toggle.setAttribute('aria-labelledby', dueLabelId);
    toggle.dataset.tflDue = '1';
    const { holder: dueHolder } = dueFieldEls(Date.now() + 3600_000);
    dueHolder.style.display = 'none';
    toggle.addEventListener('click', () => {
      hasDue = !hasDue;
      setToggleState(toggle, hasDue);
      dueLabel.textContent = hasDue ? t('todoface.hasDue') : t('todoface.noDue');
      dueHolder.style.display = hasDue ? '' : 'none';
    });
    dueRow.append(dueLabel, toggle, dueHolder);
    panel.appendChild(dueRow);
    wrap.appendChild(panel);
    row.appendChild(wrap);
    // 创建行非文本区点击 = 保存（复刻 onTapGesture 语义；输入框 click 不冒泡触发）
    // 手机抽屉腿例外：抽屉里「点空白 = 保存」太易误触（抽屉本体就是一块大空白），
    // 保存口收敛为抽屉头「完成」键 + 输入框回车两条，取消口 = 遮罩 / 「取消」键。
    row.addEventListener('click', (e) => {
      if (this.mobile) return;
      if (e.target === this.createTitle || e.target === notes) return;
      if (e.target.closest('.tfl-edit-prio-btn') || e.target.closest('.nb-toggle') || e.target.closest('.tfl-due-label') || e.target.closest('.tfl-due-holder')) return;
      this.saveCreate();
    });
    // 展开后聚焦标题（ReminderIsland onAppear 聚焦等价：web 无懒实例化竞态，
    // 一档聚焦即落地——对照表申报简化）。手机顶部表单腿必须 preventScroll：
    // 表单位于页面壳顶部、DOM 上却在页体滚动容器内 ⇒ 缺省聚焦可能把页体往上
    // 拽（= 内容位移，违反「新建零位移」硬验收）。
    setTimeout(() => {
      if (!this.creating || !this.createTitle) return;
      if (this.mobile) this.createTitle.focus({ preventScroll: true });
      else this.createTitle.focus();
    }, 60);
    setTimeout(() => { // 滚进可视区（scrollTo anchor 等价，instant 不带动画）
      // 手机抽屉腿不滚：表单本就在抽屉可视区内，scrollIntoView 只会把页面/列表
      // 顺带蹭动一下（抽屉外的滚动容器与行不同链）。
      if (this.mobile) return;
      if (this.creating && row.isConnected) row.scrollIntoView({ block: 'nearest' });
    }, 340);
    return row;
  }

  // ── 状态机（startEditing / saveEditing / startCreate / saveCreate）─────

  startEditing(id) {
    if (this.creating) this.saveCreate(); // 互斥：先保存收起创建行（复刻）
    if (this.editingId === id) { this.saveEditing(); return; }
    this.editingId = id;
    this.selectedId = id;
    this.expandedJustRendered = true;
    this.renderRows();
    setTimeout(() => { if (this.titleInput) this.titleInput.focus(); }, 100);
  }

  saveEditing() {
    if (!this.editingId) return;
    const id = this.editingId;
    const name = this.titleInput ? this.titleInput.value : '';
    const detail = this.editDraft && this.editDraft.notesEl ? this.editDraft.notesEl.value : '';
    const due = this.editDraft && this.editDraft.hasDue && this.editDraft.dueInput.value
      ? new Date(this.editDraft.dueInput.value).getTime() : null;
    const priority = this.editPrio != null ? this.editPrio : 0;
    this.editingId = null;
    this.editDraft = null;
    this.titleInput = null;
    // 空 name 照存（EventKit 允许空标题行——复刻 saveEdits 原语义）
    todoStore.updateItem(id, { name, detail, due, priority });
    this.expandedJustRendered = true;
    this.renderRows();
    this.refocusList();
  }

  /** 创建行当前宿主（手机腿 = 底部抽屉；桌面腿 = 列表流内）。单一取值口：
   *  读表单/清表单/挂表单三处都经它，杜绝「一处搬到抽屉、另一处还在列表」的错配。 */
  createHost() {
    return this.mobile && this.sheetBody ? this.sheetBody : this.listInner;
  }

  startCreate() {
    // 幂等闸（胶囊面交互批）：创建行已开 ⇒ 只把焦点送回标题，绝不开第二条。
    // 三条入口（footer Add / 右下角加号 / 空白区点击）共用此闸。
    if (this.creating) {
      if (this.createTitle && this.createTitle.isConnected) this.createTitle.focus();
      return;
    }
    this.saveEditing(); // 互斥（复刻 collapseEditingRow）
    this.creating = true;
    this.createPrio = 0; // 每次新开重置重要度（重渲不清场，见 buildCreateRow）
    this.expandedJustRendered = true;
    this.renderRows();
    this.renderFooter();
    // 创建行滚进可视区 + 聚焦已内建（buildCreateRow 内延时档）
  }

  /** 放弃新建（抽屉：遮罩点按 / 「取消」键两条关闭路径共用一个出口）。
   *  与 saveCreate 的差别只在「不写 store」——表单清场与抽屉收起走同一条渲染链。 */
  cancelCreate() {
    if (!this.creating) return;
    this.creating = false;
    this.createTitle = null;
    this.expandedJustRendered = true;
    this.renderRows();
    this.renderFooter();
  }

  saveCreate() {
    if (!this.creating) return;
    const host = this.createHost();
    const name = this.createTitle ? this.createTitle.value : '';
    const notesEl = host.querySelector('.tfl-create-panel .tfl-edit-notes');
    const detail = notesEl instanceof HTMLInputElement || notesEl instanceof HTMLTextAreaElement ? notesEl.value : '';
    const dueEl = host.querySelector('.tfl-create-panel .tfl-due-input');
    const dueOn = host.querySelector('.tfl-create-panel .nb-toggle.on') != null;
    const due = dueOn && dueEl && dueEl.value ? new Date(dueEl.value).getTime() : null;
    this.creating = false;
    this.createTitle = null;
    this.renderRows();
    this.renderFooter();
    if (name.trim()) {
      todoStore.createItem({
        tagId: todoStore.activeTagId, name, detail,
        due, priority: this.createPrio || 0,
      });
    }
    this.refocusList();
  }

  switchView(tagId) {
    if (todoStore.activeTagId === tagId) return;
    if (this.editingId) this.saveEditing();
    if (this.creating) { this.creating = false; }
    todoStore.setActiveTag(tagId);
    this.expandedJustRendered = true; // 视图切换重渲不播入场动画（复刻 view 重重建语义）
    this.renderAll();
    this.refocusList(400); // 复刻 establishListFocus(delay: 0.4)
  }

  /** 列表焦点重建（establishListFocus 等价；delay 毫秒可选）。 */
  refocusList(delay = 0) {
    if (this.mobile) return;
    const run = () => {
      if (this.destroyed || !this.expanded) return;
      if (this.creating || this.editingId || this.editingTagId != null || this.creatingTag) return;
      if (this.listScroll && this.listScroll.isConnected) this.listScroll.focus({ preventScroll: true });
    };
    if (delay > 0) setTimeout(run, delay); else run();
  }

  // ── 键盘导航（↑↓ 选择，Return 编辑/创建，Esc 分层退出）─────────────────

  onListKey(e) {
    if (e.key === 'Escape') { e.preventDefault(); this.handleEscape(); return; }
    const active = document.activeElement;
    const inputBusy = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA');
    const creating = this.creating || this.editingId != null || this.editingTagId != null || this.creatingTag;
    if (inputBusy || creating) return; // 文本框消费键（复刻 listKeyboardActive）
    const ids = todoStore.visibleItems().map((it) => it.id);
    if (!ids.length) {
      if (e.key === 'Enter' && !todoStore.isAllView()) { e.preventDefault(); this.startCreate(); }
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (this.editingId) { this.saveEditing(); }
      const idx = this.selectedId ? ids.indexOf(this.selectedId) : (e.key === 'ArrowDown' ? -1 : ids.length);
      const next = idx + (e.key === 'ArrowDown' ? 1 : -1);
      if (next < 0 || next >= ids.length) return;
      this.selectedId = ids[next];
      this.renderRows();
      const row = this.listInner.querySelector(`.tfl-row[data-id="${CSS.escape(this.selectedId)}"]`);
      if (row) row.scrollIntoView({ block: 'nearest' });
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (!this.selectedId || !ids.includes(this.selectedId)) {
        // Return 无选中 = 点击列表空白同入口（复刻 activateBlankArea）
        if (!todoStore.isAllView()) this.startCreate();
        else if (this.editingId) this.saveEditing();
        return;
      }
      this.startEditing(this.selectedId);
    }
  }

  /** Esc 分层退出（handleEscapeFallback 复刻）：行编辑→保存收起；创建行→丢草稿；
   *  标签 popover→关；**浏览态→收起面板**（重做批：作者要求面板有明确退出方式，
   *  点外部 / Esc / 收起钮三条并存 —— 原实现浏览态 Esc 无动作）。 */
  handleEscape() {
    if (this.editingId != null) this.saveEditing();
    else if (this.creating) { this.creating = false; this.renderRows(); this.renderFooter(); }
    else if (this.editingTagId != null || this.creatingTag) this.closeTagPopover(false);
    else this.collapse();
  }

  // ── 展开 / 收起（件5 2026-11-05：**同一行内胶囊变大**）────────────────────
  //
  // 作者原话（2026-11-05 ui-seven 件5）：「然后待办的展开动画，要在同一行展开。
  // 有一种胶囊变大的感觉。」
  //
  // 几何口径（本块是展开几何的单源）：
  //   · 载体 = `.tfl-card` 本身，**真几何驱动**：动画只写 height（+ 顶圆角），
  //     不写 transform ⇒ rect.left / rect.top 从点击到终态恒定不动，只有**底缘**
  //     向下走。旧实现（2026-11-03 以前）把满尺寸面板 scale 到胶囊形状再放回去：
  //     载体 rect.left 会在动画中横移半个宽差（1440 桌面实测 174.41px / 375 手机
  //     135.9px），读成「横跳」——件5 判据 A1 直接禁掉这条路径。
  //   · 起点高 = 胶囊高、起点顶圆角 = 胶囊半径 ⇒ 首帧是**同一枚胶囊的横截面**
  //     （420×26 的全圆角胶囊），随后在原点不动的前提下向下长成面板：读作这一枚
  //     胶囊自身变大，而不是「插一个面板把邻居挤走再动画」。
  //   · 内容不缩放、不位移：`.tfl-panel` 保自然高（flex:0 0 auto），载体 overflow
  //     hidden 按高度裁切 ⇒ 行/文字逐帧零位移（件5 判据 A3）。
  //   · 胶囊本体**不消失**（件5 判据 A4）：不再置 visibility:hidden，改以
  //     `.tfl-mount.is-open` 的 0.55 压暗态留在原地当「原点」与收起入口（CSS 本就有
  //     这条规则，此前被 visibility:hidden 掩盖成死规则）；宽度用 inline min-width
  //     钉在展开前的宽（计数节点搬去面板头后胶囊只剩圆点，否则会缩成小点、left 横移）。
  //   · prefers-reduced-motion：整段直切（springAnimate 首帧即落终值），无中间帧。

  expand() {
    if (this.expanded || this.destroyed) return;
    // 起点 = **展开前的岛盒**（= 胶囊盒本身；先量后翻态，计数节点搬走后不可测）。
    const r0 = this.captureCapsuleBox();
    this.expanded = true;
    this.card.dataset.state = 'open';
    this.mount.classList.add('is-open');
    this.capsule.setAttribute('aria-expanded', 'true');
    if (this.cancelMorph) this.cancelMorph();
    // 计数节点先左移成面板标题（同一个 DOM 节点搬家，不是两处文案）。
    this.moveCountInto(true);
    this.panel.classList.add('tfl-panel-in');
    this.renderAll();
    this.panel.classList.add('tfl-panel-open');
    if (this.mobile) return;
    // 目标盒：宽 = 面板宽（min(420px, 100vw-32px)）、高 = 面板自然高。
    // 面板是绝对定位的定宽层 ⇒ 量 reader 不改它，行内文字全程零重排。
    const W = this.panel.offsetWidth || 0;
    const H = this.panel.offsetHeight || 0;
    if (!W || !H) { if (r0) this.writeIsland(r0.width, r0.height, 13, 13); return; }
    this.morphing = true;
    this.morphIsland(r0, { w: W, h: H, rt: 24, rb: 19 }, SPRING.morph, () => {
      this.morphing = false;
      this.islandW = W; this.islandH = H;
      this.panel.classList.remove('tfl-panel-in');
      this.observePanel();
      this.measurePanel();
      this.refocusList(); // 列表持有焦点，↑↓/Return 立即可用（复刻 onAppear）
      this.bindOutsideClose();
    });
  }

  /** 岛当帧盒（收起态 = 胶囊盒；展开态自然宽不再可用 ⇒ 只作起点快照）。 */
  islandRectNow() {
    if (this.mobile) return null;
    const r = this.card.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  }

  /**
   * 胶囊盒快照（= 收起态岛的几何）。**只在收起态成立**：展开态里胶囊层仍以自然
   * 宽居中（CSS `.is-open .tfl-capsule`），但计数节点已被搬进面板头 ⇒ 母盒只剩
   * 圆点，量出来不是「有计数的胶囊宽」。故：收起态随时量、展开前必量，结果缓存
   * 在 `capBox`（tab 等宽数字 ⇒ 位数不变时逐像素精确；位数变化的极端档在收起末帧
   * 归位到 CSS max-content，见 collapse 注）。
   * @returns {{left:number,top:number,width:number,height:number}|null}
   */
  captureCapsuleBox() {
    if (this.mobile) return null;
    const r = this.card.getBoundingClientRect();
    if (r.width && r.height) this.capBox = { left: r.left, top: r.top, width: r.width, height: r.height };
    return this.capBox;
  }

  /** 写岛的三条几何通道（宽 / 高 / 两段圆角）。**唯一写面**：颜色由 CSS 过渡承担。 */
  writeIsland(w, h, rt, rb) {
    const s = this.card.style;
    s.width = `${w.toFixed(2)}px`;
    s.height = `${h.toFixed(2)}px`;
    s.borderRadius = `${rt.toFixed(2)}px ${rt.toFixed(2)}px ${rb.toFixed(2)}px ${rb.toFixed(2)}px`;
  }

  /**
   * 岛形变（灵动岛批 2026-11-05）：**同一个节点**（`#todo-island`）从 from 盒
   * 弹簧过渡到 to 盒 —— 四条通道（宽 / 高 / 顶圆角 / 底圆角）同拍、同一组弹簧
   * 参数（SPRING.morph = response 0.38 / damping 0.86 / mass 1，见参数表）。
   *   · 过冲：ζ=0.86 ⇒ 理论超调 e^(−πζ/√(1−ζ²)) ≈ 0.5%，实测读数见批文档
   *     （`readings.json` 的 overshootPct）—— 作者要的「极轻微的过冲」，收得住；
   *   · 不夹紧、不改道：所有通道都允许越过目标值再落回（这就是弹簧），但量级
   *     在 1–3px 内，读不出「弹了一下」；
   *   · reduced-motion：首帧即落终值（无中间帧、无模糊 —— CSS 同媒体查询关过渡）。
   */
  morphIsland(from, to, spring, done) {
    const w0 = from ? from.width : to.w;
    const h0 = from ? from.height : to.h;
    const r0t = Math.min(h0 / 2, 13);   // 胶囊半径（26 高 ⇒ 13）
    const m = { w: w0, h: h0, rt: r0t, rb: r0t };
    const write = () => this.writeIsland(m.w, m.h, m.rt, m.rb);
    write();
    if (REDUCED_MOTION) {
      m.w = to.w; m.h = to.h; m.rt = to.rt; m.rb = to.rb;
      write();
      if (done) done();
      return;
    }
    this.cancelMorph = springAnimate([
      { from: w0, to: to.w, set: (v) => { m.w = v; write(); } },
      { from: h0, to: to.h, set: (v) => { m.h = v; write(); } },
      { from: r0t, to: to.rt, set: (v) => { m.rt = v; write(); } },
      { from: r0t, to: to.rb, set: (v) => { m.rb = v; write(); } },
    ], spring, () => { write(); if (done) done(); });
  }

  /** 展开态内容高度跟随（面板 resize ⇒ 岛重新贴合；动画期间不抢方向盘）。 */
  observePanel() {
    if (this.islandRo || this.mobile || typeof ResizeObserver !== 'function') return;
    this.islandRo = new ResizeObserver(() => {
      if (!this.expanded || this.morphing || this.destroyed) return;
      const H = this.panel.offsetHeight;
      if (!H || Math.abs(H - this.islandH) < 0.5) return;
      this.islandH = H;
      this.card.style.height = `${H.toFixed(2)}px`;
    });
    this.islandRo.observe(this.panel);
  }


  /**
   * L2 单一计数节点：胶囊里的 `.tfl-cap-faces`（todo/overdue 两面 + 计数文本）
   * 整块搬进面板头 `.tfl-header-count` 槽 / 搬回胶囊 —— **同一个节点在移动**。
   * 搬家时做一次位移+缩放 FLIP ⇒ 视觉上「文字左移、放大成标题」而不是淡出淡入。
   */
  moveCountInto(expanded) {
    const from = this.capsuleFaces.getBoundingClientRect();
    if (expanded) {
      if (this.capsuleFaces.parentElement !== this.headerCount) this.headerCount.appendChild(this.capsuleFaces);
      this.headerCount.classList.add('has-faces');
    } else {
      if (this.capsuleFaces.parentElement !== this.capsule) this.capsule.appendChild(this.capsuleFaces);
      this.headerCount.classList.remove('has-faces');
    }
    if (REDUCED_MOTION || !from.width) return;
    const to = this.capsuleFaces.getBoundingClientRect();
    if (!to.width) return;
    const dx = (from.left + from.width / 2) - (to.left + to.width / 2);
    const dy = (from.top + from.height / 2) - (to.top + to.height / 2);
    const sc = from.height / to.height || 1;
    if (this.cancelCountMorph) this.cancelCountMorph();
    const m = { x: dx, y: dy, s: sc };
    const paint = () => {
      this.capsuleFaces.style.transform =
        `translate(${m.x.toFixed(2)}px, ${m.y.toFixed(2)}px) scale(${m.s.toFixed(3)})`;
    };
    paint();
    this.cancelCountMorph = springAnimate([
      { from: dx, to: 0, set: (v) => { m.x = v; paint(); } },
      { from: dy, to: 0, set: (v) => { m.y = v; paint(); } },
      { from: sc, to: 1, set: (v) => { m.s = v; paint(); } },
    ], SPRING.pop, () => { this.capsuleFaces.style.transform = ''; });
  }

  collapse() {
    if (!this.expanded || this.mobile || this.destroyed) return;
    // 收起前把编辑态落盘（web 面不销毁树——保存比丢弃安全，对照表申报）
    if (this.editingId) this.saveEditing();
    if (this.creating) { this.creating = false; }
    this.closeTagPopover(false);
    this.expanded = false;
    this.mount.classList.remove('is-open');
    this.capsule.setAttribute('aria-expanded', 'false');
    this.unbindOutsideClose();
    if (this.cancelMorph) this.cancelMorph();
    this.panel.classList.remove('tfl-panel-open');
    this.panel.classList.add('tfl-panel-out');
    // 目标盒 = 缓存里的胶囊盒（展开前实测；tab 等宽数字 ⇒ 位数不变时逐像素精确）。
    // 末帧 data-state 翻回 capsule + 清掉 inline 尺寸 ⇒ 岛回到 CSS 自然宽
    // （max-content），计数节点也同时搬回胶囊 —— 两件事同帧完成，视觉零跳；
    // 唯一残差 = 展开期间计数位数变化（9→10），末帧一次归位，仍在 1 帧内收口。
    const box = this.capBox;
    const done = () => {
      this.morphing = false;
      this.card.style.width = '';
      this.card.style.height = '';
      this.card.style.borderRadius = '';
      this.card.dataset.state = 'capsule';
      this.islandW = 0; this.islandH = 0;
      this.panel.classList.remove('tfl-panel-in', 'tfl-panel-out');
      this.moveCountInto(false); // 计数节点回胶囊（同一节点，末帧位置≈胶囊位）
      this.refocusList();
    };
    if (REDUCED_MOTION || !box) { done(); return; }
    this.morphing = true;
    this.morphIsland(null, { w: box.width, h: box.height, rt: 13, rb: 13 }, SPRING.morph, done);
  }

  /** 面板/胶囊实测（取证与回归断言用；几何已由 CSS 决定，此处只读不写）。 */
  measurePanel() {
    this.panelH = this.card.offsetHeight || 0;
    this.capsuleH = this.capsule.offsetHeight || 0;
    this.capsuleW = this.capsule.offsetWidth || 0;
  }

  /** 胶囊 pop（notchPop 等价：+10px 高度弹跳、圆角 10/16 中间态；本批无自然
   *  触发源——数据接口留下一步，测试缝 __pop 取证）。重做批后胶囊外框
   *  固定（翻面不变量），pop 只做 scale 弹跳、绝不动高宽。 */
  pop() {
    if (this.expanded || this.mobile || this.destroyed) return;
    if (this.cancelMorph) this.cancelMorph();
    this.cancelMorph = springAnimate([
      { from: 1, to: 1.08, set: (v) => { this.capsule.style.scale = String(v); } },
    ], SPRING.pop, () => {
      this.cancelMorph = springAnimate([
        { from: 1.08, to: 1, set: (v) => { this.capsule.style.scale = String(v); } },
      ], SPRING.pop, () => { this.capsule.style.scale = ''; });
    });
  }

  // ── 点击外部关闭（handleMouseDown click-outside 等价；编辑态先落盘）─────

  /** 点击外部关闭 + 全局 Esc 收起（handleMouseDown click-outside 等价；编辑态先落盘）。
   *
   *  重做批（作者令「退出方式」）：面板是浮层后必须随时收得掉 —— 原实现 Esc 只在
   *  .tfl-list 持有焦点时经 onListKey 生效，焦点在输入框时按 Esc 无反应。本批在
   *  document 上补一个 capture 监听（IME 组合期不接管，走 isImeComposing）；
   *  与 collapse/destroy 一一对应解绑。 */
  bindOutsideClose() {
    if (this.mobile || this.outsideBound) return;
    this.outsideHandler = (e) => {
      if (!this.expanded || this.destroyed) return;
      const path = e.composedPath ? e.composedPath() : [];
      const insideCard = path.includes(this.card);
      const insideLayer = this.tagPopover && path.includes(this.tagPopover);
      if (insideCard || insideLayer || path.includes(this.mount)) return;
      if (this.editingId) this.saveEditing();
      if (this.creating) { this.creating = false; this.renderRows(); this.renderFooter(); }
      this.closeTagPopover(false);
      this.collapse();
    };
    document.addEventListener('pointerdown', this.outsideHandler, true);
    this.escHandler = (e) => {
      if (e.key !== 'Escape' || !this.expanded || this.destroyed) return;
      if (isImeComposing(e, null)) return;
      // 列表内的 Esc 由 onListKey 分层处理（行编辑/创建行/popover 优先），
      // 此处只在焦点不在面板子树内时才接管 —— 避免同一次按键双重动作。
      if (this.card.contains(document.activeElement)) return;
      e.preventDefault();
      this.handleEscape();
    };
    document.addEventListener('keydown', this.escHandler, true);
    this.outsideBound = true;
  }

  unbindOutsideClose() {
    if (this.outsideHandler) document.removeEventListener('pointerdown', this.outsideHandler, true);
    if (this.escHandler) document.removeEventListener('keydown', this.escHandler, true);
    this.escHandler = null;
    this.outsideBound = false;
  }

  // ── chips 滚动（箭头 + 水平滚轮；easeOut 0.2 步进）──────────────────────

  setChipScroll(v, animate = false) {
    const max = this.chipsMaxScroll();
    const target = Math.min(Math.max(v, max), 0);
    if (!animate || REDUCED_MOTION) {
      this.chipScroll = target;
      this.chipsTrack.style.transform = `translateX(${target}px)`;
    } else {
      const from = this.chipScroll;
      springAnimate([{ from, to: target, set: (x) => {
        this.chipScroll = x;
        this.chipsTrack.style.transform = `translateX(${x}px)`;
      } }], { response: 0.28, damping: 1.0 });
    }
    this.updateChipArrows();
  }

  chipsMaxScroll() {
    const c = this.chipsViewport, k = this.chipsTrack;
    if (!c || !k) return 0;
    return -Math.max(k.offsetWidth - c.offsetWidth, 0);
  }

  updateChipScrollBounds() {
    const max = this.chipsMaxScroll();
    if (this.chipScroll < max) this.setChipScroll(this.chipScroll);
    this.updateChipArrows();
  }

  updateChipArrows() {
    const max = this.chipsMaxScroll();
    const can = this.chipsTrack.offsetWidth > this.chipsViewport.offsetWidth + 1;
    this.chipLeft.style.display = can && this.chipScroll < -0.5 ? '' : 'none';
    this.chipRight.style.display = can && this.chipScroll > max + 0.5 ? '' : 'none';
  }

  scrollChips(dir) {
    const step = Math.max(Math.min(this.chipsViewport.offsetWidth - 40, 120), 60);
    this.setChipScroll(this.chipScroll + step * dir, true);
  }

  // ── chips 手势：横拖重排 / 上划改名 / 下划删除 ────────────────────────────
  // ⛔ 悬停教学气泡（450ms 四向提示）与「悬停放大挤开」整套于**标签删除模式批**
  // 2026-11-04 退役（UI 批次审计 D 档 R05 收口；台账1376 作者 recall 2026-11-02
  // 12:05「标签 hover 不要动画」）：`onChipHover` / `showHint` / `chevSvg` /
  // `dismissHint` 四函数 + 三处状态位（chipHover/chipTimer/hintLayer）整链摘除，
  // 样式侧对应块见 css/todo-face.css 同名注。**本仓退役不物理删除**的惯例在此
  // 例外：死码本身是本批被审的缺陷形态（审计 R08 亦因死码判 D），故真删。

  onChipPointerDown(e, tag, chip) {
    if (e.button !== 0 || this.chipDrag) return;
    const startX = e.clientX, startY = e.clientY;
    let dir = null; // 'h' | 'up' | 'down'
    let moved = false;
    const threshold = 25; // swipeThreshold
    const onMove = (ev) => {
      const dx = ev.clientX - startX;
      const dy = ev.clientY - startY;
      if (!dir) {
        if (Math.abs(dx) > Math.abs(dy) + 5 && Math.abs(dx) > 8) {
          dir = 'h';
          this.beginChipDrag(tag, chip, ev);
        } else if (Math.abs(dy) > 10) {
          dir = dy < 0 ? 'up' : 'down';
          this.beginChipSwipe(tag, chip, dir);
        }
        if (!dir) return;
      }
      moved = true;
      if (dir === 'h' && this.chipDrag) this.moveChipDrag(ev, dx);
      else if ((dir === 'up' || dir === 'down') && this.chipSwipe) {
        const prog = Math.min(1, Math.abs(dy) / threshold);
        this.chipSwipe.progress = prog;
        this.chipSwipe.dy = dy;
        const ic = this.chipSwipe.indicator;
        if (ic) ic.style.opacity = String(prog);
      }
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      if (dir || moved) chip.dataset.gestureDone = '1'; // click 抑制标记（见 click handler）
      if (dir === 'h') { this.endChipDrag(); return; }
      if (dir === 'up' || dir === 'down') {
        const swipe = this.chipSwipe;
        this.chipSwipe = null;
        if (swipe && swipe.chip.isConnected) swipe.chip.classList.remove('swiping-up', 'swiping-down');
        if (swipe && Math.abs(swipe.dy) >= threshold) {
          if (dir === 'up') this.openTagPopover(tag.id);
          else this.confirmDeleteTag(tag); // HIG 铁律：破坏性操作必须二次确认（下划不再直删）
        }
        return;
      }
      if (!moved) return; // 原地 = click（click 事件自行触发）
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  }

  /** 标签删除 = **破坏性操作** ⇒ 二次确认（手机端 HIG 铁律；作者裁定只有
   *  「关标签页」豁免）。形态 = 全站既有 __showConfirm（danger 档），与联系人
   *  删除 / 插件卸载同一套；无确认面可用时退回直删（同 contacts 先例，绝不
   *  因缺面而静默吞掉用户的删除意图）。 */
  confirmDeleteTag(tag) {
    const run = () => todoStore.deleteTag(tag.id);
    if (typeof window.__showConfirm === 'function') {
      window.__showConfirm(
        t('todoface.tagDeleteTitle'),
        t('todoface.tagDeleteConfirm', { name: tag.name || t('todoface.tagName') }),
        run,
        { tone: 'danger', confirmLabel: t('todoface.tagDelete') },
      );
      return;
    }
    run();
  }

  beginChipSwipe(tag, chip, dir) {
    chip.classList.add(dir === 'up' ? 'swiping-up' : 'swiping-down');
    const indicator = el('span', 'tfl-chip-swipe-ic');
    indicator.innerHTML = dir === 'up' ? PENCIL_CIRCLE_SVG : XMARK_CIRCLE_SVG;
    indicator.style.opacity = '0';
    chip.appendChild(indicator);
    this.chipSwipe = { chip, dy: 0, progress: 0, indicator };
  }

  beginChipDrag(tag, chip, ev) {
    const frozen = new Map();
    // 冻结帧表必须**含被拖 chip 自己**（R08 死码根因，UI 批次审计 2026-11-04 判 D；
    // 台账1376 作者 recall 2026-11-02 12:05「左右推动标签顺序的功能要保留」）：
    // 改前这里写的是 `if (id !== tag.id) frozen.set(...)`，于是 `moveChipDrag` 首句
    // `const r0 = drag.frozen.get(drag.id); if (!r0) return;` 恒取不到自己那一帧、
    // 逐帧早退 ⇒ `todoStore.reorderTag()` 永不可达（拖过 2 枚邻 chip 顺序不动）。
    // 现在全表冻结：r0 = 被拖 chip 的拖动起点帧（它是**不动**的源 chip，ghost 才跟手），
    // 越位判据与 `centerAdjust` 补偿都以此帧为基准 —— 与源 chip 的连线语义一致。
    for (const [id, elx] of (this.chipEls || new Map())) {
      if (elx && elx.isConnected) frozen.set(id, elx.getBoundingClientRect());
    }
    const ghost = chip.cloneNode(true);
    ghost.classList.add('tfl-chip-ghost');
    ghost.style.left = '0';
    ghost.style.top = '0';
    const r = chip.getBoundingClientRect();
    document.body.appendChild(ghost);
    this.chipDrag = {
      id: tag.id, dx: 0, dy: 0, ghost, centerAdjust: 0,
      ox: ev.clientX - r.left, oy: ev.clientY - r.top,
      frozen, chip,
    };
    chip.classList.add('dragging'); // 源 chip 0.25 透明度（复刻 opacity isDragged）
    this.moveChipDrag(ev, 0);
  }

  moveChipDrag(ev, dx) {
    const drag = this.chipDrag;
    if (!drag) return;
    drag.dx = dx;
    drag.ghost.style.transform = `translate(${ev.clientX - drag.ox}px, ${ev.clientY - drag.oy}px)`;
    // 越位检测（handleHorizontalReorder 复刻：有效中心越过邻 chip 中点 → 换位；
    // 有效中心带换位补偿 centerAdjust——Swift 侧 horizontalDragTranslation -= shift
    // 的等价物，保证连续拖过多枚 chip 不振荡）
    const r0 = drag.frozen.get(drag.id);
    if (!r0) return;
    const center = r0.left + r0.width / 2 + dx + (drag.centerAdjust || 0);
    const order = /** @type {any[]} */ (todoStore.tags);   // checkJs：indexOf 的入参族见下方登记（既有面，非本批引入）
    const idx = order.findIndex((x) => x.id === drag.id);
    for (const other of order) {
      if (other.id === drag.id) continue;
      const fr = drag.frozen.get(other.id);
      if (!fr) continue;
      const mid = fr.left + fr.width / 2;
      const dragBefore = idx < order.indexOf(other.id);
      const crossed = dragBefore ? center > mid : center < mid;
      if (!crossed || Math.abs(center - mid) <= 1) continue;
      const oldMid = r0.left + r0.width / 2;
      todoStore.reorderTag(drag.id, other.id);
      this.syncChipDomOrder(); // 邻 chip 让位动画（FLIP，spring 0.3/0.8 近似）
      const r1 = drag.frozen.get(drag.id);
      if (r1) drag.centerAdjust = (drag.centerAdjust || 0) + (oldMid - (r1.left + r1.width / 2));
      break;
    }
  }

  /** 拖拽期间的可见序同步：按 store 序直接重排 chips DOM（源 chip 保持 dimmed、
   *  ghost 跟指针不动），其余 chip 走 FLIP 位移过渡；随后冻结帧全量刷新。 */
  syncChipDomOrder() {
    const drag = this.chipDrag;
    if (!drag) return;
    const track = this.chipsTrack;
    const order = todoStore.tags.map((x) => x.id);
    const before = new Map();
    for (const [id, elx] of this.chipEls) {
      if (id !== drag.id && elx.isConnected) before.set(elx, elx.getBoundingClientRect());
    }
    const addBtn = track.querySelector('.tfl-chip-add');
    let prev = null;
    for (const id of order) {
      const elx = this.chipEls.get(id);
      if (!elx || !elx.isConnected || elx === drag.chip) continue;
      if (!prev) track.insertBefore(elx, track.firstChild);
      else prev.after(elx);
      prev = elx;
    }
    if (addBtn && prev) prev.after(addBtn);
    if (!REDUCED_MOTION) {
      for (const [elx, r0] of before) {
        if (!elx.isConnected) continue;
        const r1 = elx.getBoundingClientRect();
        const dx0 = r0.left - r1.left;
        if (Math.abs(dx0) <= 1) continue;
        elx.style.transition = 'none';
        elx.style.transform = `translateX(${dx0}px)`;
        requestAnimationFrame(() => {
          elx.style.transition = 'transform 0.3s cubic-bezier(0.34, 1.2, 0.64, 1)';
          elx.style.transform = 'none';
          setTimeout(() => { elx.style.transition = ''; elx.style.transform = ''; }, 330);
        });
      }
    }
    for (const [id, elx] of this.chipEls) {
      if (elx.isConnected) drag.frozen.set(id, elx.getBoundingClientRect());
    }
    this.setChipScroll(this.chipScroll);
  }

  endChipDrag() {
    const drag = this.chipDrag;
    if (!drag) return;
    this.chipDrag = null;
    drag.ghost.remove();
    if (drag.chip.isConnected) drag.chip.classList.remove('dragging');
    this.renderChips();
    if (typeof createIconsIn === 'function') createIconsIn(this.chipsTrack);
    this.setChipScroll(this.chipScroll);
  }

  // ── 标签 popover（CalendarChipPopover 复刻：名 + 十色 5 列网格）─────────

  openTagPopover(editTagId, anchorEl = null) {
    this.closeTagPopover(false);
    const editing = editTagId != null;
    const tag = editing ? todoStore.tagById(editTagId) : null;
    this.editingTagId = editing ? editTagId : null;
    this.creatingTag = !editing;
    const pop = el('div', 'tfl-tag-popover');
    pop.setAttribute('role', 'dialog');
    const name = el('input', 'tfl-tag-name');
    name.type = 'text';
    name.value = tag ? tag.name : '';
    name.placeholder = t('todoface.tagName');
    name.setAttribute('aria-label', t('todoface.tagName'));
    pop.appendChild(name);
    const grid = el('div', 'tfl-tag-colors');
    let draftColor = tag ? tag.color : TAG_COLOR_PRESETS[5];
    const dots = [];
    TAG_COLOR_PRESETS.forEach((c, i) => {
      const dot = el('button', 'tfl-tag-color' + (c === draftColor ? ' selected' : ''));
      dot.type = 'button';
      dot.style.setProperty('--tag-color', c);
      dot.setAttribute('aria-label', t('todoface.colorN', { n: String(i + 1) }));
      dot.addEventListener('click', () => {
        draftColor = c;
        dots.forEach((d) => d.classList.toggle('selected', d === dot));
        if (editing) todoStore.updateTag(editTagId, { color: c }); // 复刻 onColorChange 即时保存
      });
      dots.push(dot);
      grid.appendChild(dot);
    });
    pop.appendChild(grid);
    // 锚定 chip 下方（arrowEdge .bottom 等价；近底翻转向上）。
    // 手机页面态没有 chips 行（本批退役）⇒ 由调用点把锚点**以节点传入**
    // （顶部「⋯」钮），本函数不跨面查 DOM（组件契约 R2）。
    let anchor = anchorEl;
    if (!anchor && editing && this.chipEls) anchor = this.chipEls.get(editTagId);
    if (!anchor && this.chipsTrack) anchor = this.chipsTrack.querySelector('.tfl-chip-add');
    document.body.appendChild(pop);
    this.tagPopover = pop;
    if (anchor) {
      const r = anchor.getBoundingClientRect();
      const pw = 220;
      const px = Math.min(Math.max(8, r.left + r.width / 2 - pw / 2), window.innerWidth - pw - 8);
      const below = window.innerHeight - r.bottom > 190;
      pop.style.left = `${px}px`;
      pop.style.top = below ? `${r.bottom + 6}px` : '';
      if (!below) pop.style.bottom = `${window.innerHeight - r.top + 6}px`;
    }
    pop.classList.add('open');
    name.focus();
    const commit = () => {
      if (isImeComposing(null, name)) return; // IME 组合 Return = 上屏确认（复刻）
      const trimmed = name.value.trim();
      if (editing) {
        if (trimmed) todoStore.updateTag(editTagId, { name: trimmed });
        this.closeTagPopover(true);
      } else {
        if (trimmed) todoStore.createTag(trimmed, draftColor);
        this.closeTagPopover(true);
      }
    };
    this.tagCommit = commit;
    name.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !isImeComposing(e, name)) { e.preventDefault(); e.stopPropagation(); commit(); }
      if (e.key === 'Escape') { e.preventDefault(); this.closeTagPopover(false); }
    });
    pop.addEventListener('click', (e) => e.stopPropagation());
  }

  closeTagPopover(saved) {
    if (this.tagPopover) {
      const pop = this.tagPopover;
      this.tagPopover = null;
      pop.classList.remove('open');
      setTimeout(() => pop.remove(), 150);
    }
    this.editingTagId = null;
    this.creatingTag = false;
    this.tagCommit = null;
    if (saved !== 'skip') this.refocusList();
  }

  destroy() {
    this.destroyed = true;
    this.menuOpen = false;
    this.menuSubKind = null;
    clearTimeout(this.menuTimer);
    clearTimeout(this.subTimer);
    clearTimeout(this.sheetTimer);
    this.unsub();
    this.unbindOutsideClose();
    if (this.cancelMorph) this.cancelMorph();
    if (this.islandRo) { this.islandRo.disconnect(); this.islandRo = null; }
    if (this.tagPopover) this.tagPopover.remove();
    if (this.root && this.root.isConnected) this.root.remove();
  }
}

// ── 挂载缝 ───────────────────────────────────────────────────────────────

let desktopFace = null;

/**
 * 桌面任务列表面（#task-list 槽位）。会话门沿用 taskList.sessionShowsNodeEntries
 * （Nebula 统一面）——非门会话摘面（旧 delegate 面同门同语义）。
 * @param {string} [sessionId]
 */
export function renderTodoTaskList(sessionId) {
  const container = document.getElementById('task-list');
  if (!container) return;
  const show = sessionShowsNodeEntries(sessionId);
  if (!show) {
    if (desktopFace) { desktopFace.destroy(); desktopFace = null; }
    container.classList.remove('has-tasks');
    container.textContent = '';
    return;
  }
  if (!desktopFace) {
    container.classList.add('has-tasks');
    container.textContent = '';
    desktopFace = new TodoFace({});
    container.appendChild(desktopFace.root);
  }
  desktopFace.renderCapsule();
}

/** 桌面面实例缝（测试取证用；产品路径零调用）。 */
export function __desktopFace() { return desktopFace; }

/**
 * 手机任务页面板体挂载（mobile/taskPanel.js 渲染核换血缝）：页面即展开态，
 * 无胶囊/无点击外关闭（页面框架收回）。
 * @param {HTMLElement} container
 */
export function mountTodoFaceInto(container) {
  if (!container) return null;
  container.textContent = '';
  const face = new TodoFace({ mobile: true });
  container.appendChild(face.root);
  return face;
}
