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
 */
function springAnimate(channels, { response, damping }, onDone) {
  if (REDUCED_MOTION) {
    for (const ch of channels) ch.set(ch.to);
    if (onDone) onDone();
    return () => {};
  }
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
    const dt = Math.min((now - last) / 1000, 1 / 30);
    last = now;
    let all = true;
    for (const s of st) {
      if (s.done) continue;
      const a = -k * (s.x - s.ch.to) - c * s.v;
      s.v += a * dt;
      s.x += s.v * dt;
      if (Math.abs(s.x - s.ch.to) < 0.35 && Math.abs(s.v) < 6) {
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

    // 面板容器 = 展开态面板（`.tfl-card`）。静默态 display:none（零占位）；
    // 开态 = 挂载槽内的流内块，排在胶囊**之前** ⇒ 面板出现在胶囊上方、自胶囊
    // 位置向上生长（#input-area 是 bottom 锚定的绝对层 ⇒ 高度只向上长，
    // 输入框自身位置不动；聊天让位读 #input-area.offsetHeight，零 JS 改动）。
    this.card = el('div', 'tfl-card');
    this.card.dataset.state = this.expanded ? 'open' : 'capsule';
    if (this.mobile) this.card.classList.add('tfl-mobile');

    // 面板（展开态 = ReminderListView 整体）
    this.panel = el('div', 'tfl-panel');
    this.buildHeader();
    this.buildFilterBar();
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
    this.panel.append(this.header, this.filterWrap, this.listWrap, this.footer);
    this.card.appendChild(this.panel);
    // 空白区建（作者令 2026-11-04 ②）：列表空白 / footer 空白点击 ⇒ 行内新建
    this.bindBlankCreate();
    // DOM 顺序 = 面板在前、胶囊在后（面板排在胶囊上方；声明顺序即视觉顺序）
    this.mount.appendChild(this.card);
    this.mount.appendChild(this.capsule);
    this.root = this.mount;
    if (this.mobile) {
      // 手机 = 页面即展开态：挂载槽即页面体，卡容器回退为流内块（CSS .tfl-mobile）
      this.mount.classList.add('tfl-mount-mobile');
    }
  }

  buildHeader() {
    this.header = el('div', 'tfl-header');
    this.headerCount = el('span', 'tfl-header-count');
    this.header.appendChild(this.headerCount);
    const spacer = el('span', 'tfl-header-spacer');
    this.header.appendChild(spacer);
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
     startCreate 的幂等闸吸收 —— 同一条创建行，绝不开第二条。 */
  buildFab() {
    const fab = el('button', 'tfl-fab-add');
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
  }

  renderHeader() {
    // L2 单一计数节点：标题计数**不是**另画的一份文案 —— 它就是胶囊里那颗
    // `.tfl-cap-faces`（展开时搬进 .tfl-header-count 槽）。所以这里绝不能再写
    // textContent（会抹掉搬进来的节点）；头槽的可读性只靠下面的状态镜像。
    const n = todoStore.openCount();
    const od = todoStore.overdueCount();
    this.headerCount.classList.toggle('is-overdue', od > 0);
    this.headerCount.classList.toggle('has-count', n > 0);
    this.filterToggle.classList.toggle('active', this.showFilterBar);
    this.collapseBtn.hidden = this.mobile; // 手机页面由页面框架收回
  }

  renderFilterBar() {
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
    if (this.creating) this.listInner.appendChild(this.buildCreateRow());
    if (!items.length && !this.creating) this.listInner.appendChild(this.buildEmpty());
    if (typeof createIconsIn === 'function') createIconsIn(this.listInner);
    // 编辑面板/创建面板高度过渡：先关后开（下一拍加 open 类，0fr→1fr 播动画）
    requestAnimationFrame(() => {
      const wraps = this.listInner.querySelectorAll('.tfl-edit-wrap');
      wraps.forEach((w) => w.classList.add('open'));
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
    if (editing) {
      this.titleInput = el('input', 'tfl-row-title-input');
      this.titleInput.type = 'text';
      this.titleInput.value = item.name;
      this.titleInput.placeholder = t('todoface.titlePh');
      this.titleInput.setAttribute('aria-label', t('todoface.titlePh'));
      this.titleInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !isImeComposing(e, this.titleInput)) { e.preventDefault(); e.stopPropagation(); this.saveEditing(); }
      });
      titleline.appendChild(this.titleInput);
    } else {
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
    // detail（两段化的第二段；多行文本域 —— 换行归内容，不吞回车保存语义：
    // name 输入框仍是「回车即保存」的唯一入口）
    const detailEl = el('textarea', 'tfl-edit-notes tfl-edit-detail');
    detailEl.rows = 2;
    detailEl.value = item.detail || '';
    detailEl.placeholder = t('todoface.notesPh');
    detailEl.setAttribute('aria-label', t('todoface.detailFieldLabel'));
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
    const toggle = el('button', 'tfl-switch' + (hasDue ? ' on' : ''));
    toggle.type = 'button';
    toggle.setAttribute('role', 'switch');
    toggle.setAttribute('aria-checked', String(hasDue));
    toggle.appendChild(el('span', 'tfl-switch-label', hasDue ? t('todoface.hasDue') : t('todoface.noDue')));
    toggle.appendChild(el('span', 'tfl-switch-knob-wrap', '')).appendChild(el('span', 'tfl-switch-knob'));
    // 截止时间开关 + 同款时间滚轮点选面（time-wheel-picker 批 2026-11-03）
    const { holder: dueHolder, hidden: dueInput } = dueFieldEls(item.due != null ? item.due : Date.now() + 3600_000);
    dueHolder.style.display = hasDue ? '' : 'none';
    toggle.addEventListener('click', () => {
      hasDue = !hasDue;
      toggle.classList.toggle('on', hasDue);
      toggle.setAttribute('aria-checked', String(hasDue));
      toggle.querySelector('.tfl-switch-label').textContent = hasDue ? t('todoface.hasDue') : t('todoface.noDue');
      dueHolder.style.display = hasDue ? '' : 'none';
    });
    dueRow.append(toggle, dueHolder);
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
    this.createTitle = el('input', 'tfl-row-title-input');
    this.createTitle.type = 'text';
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
    const toggle = el('button', 'tfl-switch');
    toggle.type = 'button';
    toggle.setAttribute('role', 'switch');
    toggle.setAttribute('aria-checked', 'false');
    toggle.appendChild(el('span', 'tfl-switch-label', t('todoface.noDue')));
    toggle.appendChild(el('span', 'tfl-switch-knob-wrap', '')).appendChild(el('span', 'tfl-switch-knob'));
    const { holder: dueHolder } = dueFieldEls(Date.now() + 3600_000);
    dueHolder.style.display = 'none';
    toggle.addEventListener('click', () => {
      hasDue = !hasDue;
      toggle.classList.toggle('on', hasDue);
      toggle.setAttribute('aria-checked', String(hasDue));
      toggle.querySelector('.tfl-switch-label').textContent = hasDue ? t('todoface.hasDue') : t('todoface.noDue');
      dueHolder.style.display = hasDue ? '' : 'none';
    });
    dueRow.append(toggle, dueHolder);
    panel.appendChild(dueRow);
    wrap.appendChild(panel);
    row.appendChild(wrap);
    // 创建行非文本区点击 = 保存（复刻 onTapGesture 语义；输入框 click 不冒泡触发）
    row.addEventListener('click', (e) => {
      if (e.target === this.createTitle || e.target === notes) return;
      if (e.target.closest('.tfl-edit-prio-btn') || e.target.closest('.tfl-switch') || e.target.closest('.tfl-due-holder')) return;
      this.saveCreate();
    });
    // 展开后聚焦标题（ReminderIsland onAppear 聚焦等价：web 无懒实例化竞态，
    // 一档聚焦即落地——对照表申报简化）
    setTimeout(() => { if (this.creating && this.createTitle) this.createTitle.focus(); }, 60);
    setTimeout(() => { // 滚进可视区（scrollTo anchor 等价，instant 不带动画）
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

  saveCreate() {
    if (!this.creating) return;
    const name = this.createTitle ? this.createTitle.value : '';
    const notesEl = this.listInner.querySelector('.tfl-create-panel .tfl-edit-notes');
    const detail = notesEl instanceof HTMLInputElement || notesEl instanceof HTMLTextAreaElement ? notesEl.value : '';
    const dueEl = this.listInner.querySelector('.tfl-create-panel .tfl-due-input');
    const dueOn = this.listInner.querySelector('.tfl-create-panel .tfl-switch.on') != null;
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

  // ── 展开 / 收起（重做批：面板 = 胶囊上方的流内块，自胶囊位置向上生长）──────
  //
  // 形变语言保留（scale + 原点），几何从「卡容器高度弹簧」换成「面板块 + 原点
  // 在底缘中点」：
  //   · 面板开态 display:flex 排在胶囊之前（CSS），#input-area bottom 锚定 ⇒
  //     高度只向上长、输入框位置不动、聊天让位自动跟随（main.js 读 offsetHeight）。
  //   · 过渡起源点 = `transform-origin: 50% calc(0% - 8px)`（面板**顶**缘中点 −
  //     8px 呼吸缝 = 胶囊正下方；2026-11-03 10:11 ① 挪位后方向翻转，原 50% 100%
  //     是「面板在输入框上方、向上下长」时代的底缘原点）
  //     + scale 0.94→1 / 高度弹簧取消（面板高度由内容决定，弹簧只做 scale）。

  expand() {
    if (this.expanded || this.destroyed) return;
    // L1 起点 = **展开前的胶囊盒**（先量后翻态，隐藏后不可测）。
    const capRect = this.capsuleRectNow();
    this.expanded = true;
    this.card.dataset.state = 'open';
    this.mount.classList.add('is-open');
    this.capsule.setAttribute('aria-expanded', 'true');
    if (this.cancelMorph) this.cancelMorph();
    // L2 计数节点先左移成面板标题（同一个 DOM 节点搬家，不是两处文案）。
    this.moveCountInto(true);
    this.panel.classList.add('tfl-panel-in');
    this.renderAll();
    if (this.mobile) {
      this.panel.classList.add('tfl-panel-open');
      return;
    }
    // 胶囊让位：形态本体由面板接管（visibility 而非 display ⇒ 收起时仍可量）。
    this.capsule.style.visibility = 'hidden';
    this.panel.classList.add('tfl-panel-open');
    this.morphFrom(capRect, SPRING.open, () => {
      this.card.style.transform = '';
      this.card.style.borderRadius = '';
      this.card.style.transformOrigin = '';
      this.panel.classList.remove('tfl-panel-in');
      this.measurePanel();
      this.refocusList(); // 列表持有焦点，↑↓/Return 立即可用（复刻 onAppear）
      this.bindOutsideClose();
    });
  }

  /** 胶囊当帧几何（收起态可见；展开态 visibility:hidden 仍可量 ⇒ 可复用）。 */
  capsuleRectNow() {
    if (this.mobile) return null;
    const r = this.capsule.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  }

  /**
   * L1 生长形变：面板**从胶囊矩形长成自己的矩形** —— 同一个 .tfl-card 元素在
   * 变形（不是「关胶囊 + 开面板」两节点交叉淡入）。五个通道（中心位移 tx/ty、
   * 双轴缩放 sx/sy、圆角 r）逐帧写一条 transform + border-radius：
   *   起点 = 精确的胶囊盒（含胶囊端圆角），终点 = identity（= 面板自身盒）。
   * 锚点用 50% 50% 而不是 CSS 的 50% calc(0% − 8px)：几何映射要的是「面板盒 ↔
   * 胶囊盒」的精确中心对应，顶缘原点会把起点算歪（胶囊不在面板顶缘正中）。
   */
  morphFrom(capRect, spring, done) {
    const pan = this.card.getBoundingClientRect();
    if (!capRect || REDUCED_MOTION || !pan.width || !pan.height) {
      // L5 降级：整条形变退役，直接换态（状态读数不变）
      this.card.style.transform = '';
      if (done) done();
      return;
    }
    const sx = capRect.width / pan.width;
    const sy = capRect.height / pan.height;
    const tx = (capRect.left + capRect.width / 2) - (pan.left + pan.width / 2);
    const ty = (capRect.top + capRect.height / 2) - (pan.top + pan.height / 2);
    const r0 = Math.min(capRect.height / 2, 14);   // 胶囊端圆角
    const r1 = 24;                                 // 面板顶圆角（.tfl-card）
    const m = { x: tx, y: ty, a: sx, b: sy, r: r0 };
    const write = () => {
      this.card.style.transform =
        `translateX(-50%) translate(${m.x.toFixed(2)}px, ${m.y.toFixed(2)}px) scale(${m.a.toFixed(4)}, ${m.b.toFixed(4)})`;
      this.card.style.borderRadius = `${m.r.toFixed(1)}px`;
    };
    this.card.style.transformOrigin = '50% 50%';
    write();
    this.cancelMorph = springAnimate([
      { from: tx, to: 0, set: (v) => { m.x = v; write(); } },
      { from: ty, to: 0, set: (v) => { m.y = v; write(); } },
      { from: sx, to: 1, set: (v) => { m.a = v; write(); } },
      { from: sy, to: 1, set: (v) => { m.b = v; write(); } },
      { from: r0, to: r1, set: (v) => { m.r = v; write(); } },
    ], spring, done);
  }

  /** L4 收起形变：面板矩形原路长回胶囊（同一元素、同一路径、几何连续）。 */
  morphTo(capRect, spring, done) {
    const pan = this.card.getBoundingClientRect();
    if (!capRect || REDUCED_MOTION || !pan.width || !pan.height) {
      this.card.style.transform = '';
      if (done) done();
      return;
    }
    const sx = capRect.width / pan.width;
    const sy = capRect.height / pan.height;
    const tx = (capRect.left + capRect.width / 2) - (pan.left + pan.width / 2);
    const ty = (capRect.top + capRect.height / 2) - (pan.top + pan.height / 2);
    const m = { x: 0, y: 0, a: 1, b: 1, r: 24 };
    const r1 = Math.min(capRect.height / 2, 14);
    const write = () => {
      this.card.style.transform =
        `translateX(-50%) translate(${m.x.toFixed(2)}px, ${m.y.toFixed(2)}px) scale(${m.a.toFixed(4)}, ${m.b.toFixed(4)})`;
      this.card.style.borderRadius = `${m.r.toFixed(1)}px`;
    };
    this.card.style.transformOrigin = '50% 50%';
    write();
    this.cancelMorph = springAnimate([
      { from: 0, to: tx, set: (v) => { m.x = v; write(); } },
      { from: 0, to: ty, set: (v) => { m.y = v; write(); } },
      { from: 1, to: sx, set: (v) => { m.a = v; write(); } },
      { from: 1, to: sy, set: (v) => { m.b = v; write(); } },
      { from: 24, to: r1, set: (v) => { m.r = v; write(); } },
    ], spring, done);
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
    const capRect = this.capsuleRectNow();
    // 收起动画期间卡容器保持 display:flex（data-state 在动画落地后才翻），
    // 否则一翻类就 display:none、无退场可看，胶囊侧也观察不到「从面板收成胶囊」。
    const done = () => {
      this.card.style.transform = '';
      this.card.style.borderRadius = '';
      this.card.style.transformOrigin = '';
      this.card.dataset.state = 'capsule';
      this.panel.classList.remove('tfl-panel-in', 'tfl-panel-out');
      this.moveCountInto(false); // 计数节点回胶囊（同一节点，末帧位置≈胶囊位）
      this.capsule.style.visibility = '';
      this.refocusList();
    };
    if (REDUCED_MOTION) { done(); return; }
    this.morphTo(capRect, SPRING.close, done);
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

  openTagPopover(editTagId) {
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
    // 锚定 chip 下方（arrowEdge .bottom 等价；近底翻转向上）
    let anchor = null;
    if (editing && this.chipEls) anchor = this.chipEls.get(editTagId);
    if (!anchor) anchor = this.chipsTrack.querySelector('.tfl-chip-add');
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
    this.unsub();
    this.unbindOutsideClose();
    if (this.cancelMorph) this.cancelMorph();
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
