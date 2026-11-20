// gitVizView.js — 项目面板「次级面板」：Git 可视化（MVP 2026-11-19；第二轮 2026-11-19
// 风格归一 + 手机四角控件 + 手机可见泳道 + 分支管理问答 agent）。
//
// 依据（本批唯一设计源）：`<dataRoot>/projects/stand/.stand/docs/git-viz-merge-research-
// design__20261119_123007.md` §七「项目面板次级面板（git 树 + 多选转发）」+ §八「复用什么、
// 新造什么」。作者 2026-11-19 18:41 逐字（第二轮）：「手机端操作逻辑可以像 canvas 一样
// 控件放四角，然后也有 canvas ask 那样的对话框，这个是专门设计的分支管理问答 agent，
// 它也是像 canvas ask 一样只问答，做事就转发给主会话 …… 手机端也要能看到泳道，然后就是
// ui 风格，要好好根据 stand 的风格优化一下。」
//
// 复用（不新造第二套）：
//   · **次级面板机制** = projects 页签的「就地视图」`pane.dataset.projectsView`（既有消费点
//     见 projectTab.js:174/1475/1501/1523）＋ `.flowmap-nav-bar` 返回条 ＋ `.flowmap-view-body`
//     视图体（骨架与 delegateChainView.js:143-204 同形，不另起一套壳）。
//   · **四角控件定位约定** = canvas 手机版那一套（见 gitViz.css 该节注：顶带 = --mchrome-top
//     + env(safe-area-inset-top)；底带 = --mf-row-bottom；圆钮材质 = glass.css `.g2-fab`
//     基准类，零材质复制）。
//   · **问答面** = canvas ask 同一条路：`askOpen` / `askQuestion` / `askHandoff` 三个既有 WS
//     帧 + `ask-` 会话身份 + `askHandoffAck` 回执帧（ws.js 的 ask 拦截面按 `ask-` 前缀路由，
//     本轮只加了一个**第二消费者注册面** addAskFrameSink，见 ws.js:98-112）。
//   · **交办出口** = 唯一一个 `submitHandoff()`：面板「多选 + 合并」钮与问答 agent 的结构化
//     交办**收敛到同一帧**（作者令「两个入口收敛到同一个出口，不许两条各写一套」）。
//
// 硬约束（只读口径，两轮不变）：本面板**没有 git 写能力** —— 数据面只有只读端点
// `GET /projects/<p>/git-graph`（后端子命令白名单 log/for-each-ref/rev-list/merge-base/
// rev-parse）；本文件的对外动作只有「向主会话交办」一种，且它只是**请求文本**，真正的
// 合并/建支/push 一律由主会话（Nebula）按落支规则派发执行。

import { fetchGitGraph } from './nodeData.js';
import { prefersReducedMotion } from './listFlip.js';
import { sendWs, addAskFrameSink } from './ws.js';
import appState from './state.js';

const CSS_ID = 'gitviz-style';
const SVG_NS = 'http://www.w3.org/2000/svg';

// 几何（与 gitViz.css 的 --gitviz-row 同源；改一处必须同步另一处）
const ROW_PITCH = 24;      // 行距（px）
const LANE_PITCH = 9;      // 泳道间距（px）
const LANE_PAD = 20;       // 泳道带左垫（留节点半径与描边）
const HEAT_CELL = 13;      // 热力图格子边长
const HEAT_GAP = 3;
const HEAT_DAYS_DEFAULT = 371;   // 默认「近 12 个月」（作者口径：近 12 周或 12 月）
const HEAT_DAYS_WEEK = 84;       // 「近 12 周」档
const LANE_MIN_MOBILE = 14;      // 手机缩略图泳道最小间距（一泳道一列 ⇒ 8 条可辨）
const MOBILE_LANE_LABEL_W = 96;  // 手机泳道标签带宽（= gitViz.css 的 calc(--ds-space-12 * 2)，两侧同源）

/** 手机档判据（与 gitViz.css 的 `@media (max-width: 640px)` 同一阈值）。 */
const MOBILE_MQ = '(max-width: 640px)';
function isMobile() {
  try { return window.matchMedia(MOBILE_MQ).matches; } catch { return false; }
}

/** 分支管理问答会话的身份前缀。
 *  `ask-` 前缀让它落进既有 ask 拦截面（ws.js:1552 `startsWith('ask-')`）与后端
 *  `AskSessions.isAskSession`；`-gv-` 段是**项目域**判据（后端
 *  `AskSessions.isGitVizSession` 逐字同判据）——纯前缀判定，两侧都不查表。 */
const GV_SID_PREFIX = 'ask-gv-';

/** 注入样式表（幂等；与 mathRender.js:45 的 <link> 做法同族）。
 *  生产路径上 index.html 已静态链接 `css/gitViz.css`（build-web.mjs 的 CSS 孤儿
 *  闸要求 web/css/*.css 必须被 index.html 引用）⇒ 本函数按 href 判在场后早退；
 *  dev harness / 动态挂载场景（pane 先于样式表出现）由它兜底补挂。 */
export function ensureGitVizCss() {
  if (document.getElementById(CSS_ID) || document.querySelector('link[href="css/gitViz.css"]')) return;
  const link = document.createElement('link');
  link.id = CSS_ID;
  link.rel = 'stylesheet';
  link.href = 'css/gitViz.css';
  document.head.appendChild(link);
}

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}
function svg(tag, attrs = {}) {
  const n = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
  return n;
}
/** 线性图标（lucide 同族；与项目卡入口钮同一枚分支图符）。 */
function icon(paths, stroke = 2) {
  const s = svg('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': stroke, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' });
  for (const d of paths) s.appendChild(svg('path', { d }));
  return s;
}
const ICONS = {
  back: () => icon(['M19 12H5M12 19l-7-7 7-7']),
  view: () => icon(['M4 6h16M4 12h10M4 18h7']),
  zoom: () => icon(['M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3']),
  git: () => icon(['M6 3v12', 'M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M18 9a9 9 0 0 1-9 9']),
  close: () => icon(['M18 6 6 18M6 6l12 12']),
  send: () => icon(['M22 2 11 13', 'M22 2l-7 20-4-9-9-4 20-7z']),
};

/** 热力图色阶：零新色值 —— 全部是既有结构色 `--sapphire` 的透明度档（5 档，越深越多）。 */
const HEAT_ALPHA = [0.06, 0.22, 0.42, 0.64, 0.9];
/** 强度 → 档位 0..4。**只在 CSS 里上色**（`.gitviz-heat-cell.is-lv{n}`）：
 *  SVG 的 `fill` 是 presentation attribute，`var()` 在里面不生效 —— 上一轮把浓度写成
 *  `fill="rgb(var(--sapphire) / a)"`，浏览器整条丢弃 ⇒ 热力图全黑（375 真机截图即证据）。
 *  档位数值单源留在本表，颜色单源留在 gitViz.css。 */
function heatLevel(count, max) {
  if (!count) return 0;
  const r = max > 0 ? count / max : 0;
  if (r <= 0.12) return 1;
  if (r <= 0.32) return 2;
  if (r <= 0.62) return 3;
  return 4;
}

// ── 渲染：热力图 ────────────────────────────────────────────────────────────
/**
 * 热力图两档形态（第二轮 ② 修上一轮已知项：375 宽只显约 3.5 个月）。
 *
 * 取舍（作者要「按周聚合或分页，给出取舍理由」）：
 *   · **近 12 周** = 日格（12 列 × 7 行，格径 13 + 缝 3 ⇒ 约 218px）——375 视口
 *     下一次看全，日粒度不丢；
 *   · **近 12 月** 在手机档不铺 53 周日格（那要 848px ⇒ 只能看到约 3.5 个月，正是
 *     上一轮的缺陷），改**按月聚合**成 12 列（每列 1 个月，色深 = 该月提交数占峰值
 *     的比例）——12 个月全景一眼可见，代价是失去日粒度（要日粒度就切 12 周档）。
 *     选它而不是分页：分页要把「当月是否满月」的边界态再做一套（首末列半格）且用户
 *     每看半年得点两次；按月聚合零交互、语义单一。
 *   · 桌面档不变（53 周日格横滑，宽面板装得下）。
 */
function renderHeatmap(host, heat, opts = {}) {
  host.textContent = '';
  const mobile = opts.mobile ?? isMobile();
  // 注意：`heat.days.length` ≠ 请求区间 —— 365 天的窗为了凑整周会回 371 天（含对齐的
  // 首尾补齐），所以「近 12 月」判据必须用**请求区间** opts.days；拿数组长度比
  // HEAT_DAYS_DEFAULT 永远不成立 ⇒ 手机档会退回桌面 53 周（宽 886px 塞进 363px 的盒子，
  // 正是要修的「只显 3.5 个月」）。无 opts.days 时用「天数明显超过 12 周」判。
  const range = Number(opts.days || heat.days.length);
  const monthly = mobile && (opts.days ? Number(opts.days) === HEAT_DAYS_DEFAULT : heat.days.length > HEAT_DAYS_WEEK + 7);
  // 数据窗可能比请求区间宽（接口按 heatDays 回窗，但初始取数用的是默认窗）。渲染一律以
  // **请求区间**为准：多出来的日子切片丢掉，否则「近 12 周」会画出 53 列 886px 的桌面格。
  const heatDays = heat.days.length > range ? heat.days.slice(heat.days.length - range) : heat.days;

  const head = el('div', 'gitviz-heat-head');
  const sub = monthly
    ? `近 12 个月 · 共 ${heat.total} 次提交 · 按月聚合 · ${heat.from} → ${heat.to}`
    : `近 ${range} 天 · 共 ${heat.total} 次提交 · 峰值 ${heat.max}/日 · ${heat.from} → ${heat.to}`;
  head.append(
    el('span', 'gitviz-heat-title', '贡献热力图'),
    el('span', 'gitviz-heat-sub', sub),
  );
  if (typeof opts.onRange === 'function') {
    const seg = el('span', 'gitviz-heat-seg');
    for (const [days, label] of [[HEAT_DAYS_WEEK, '近 12 周'], [HEAT_DAYS_DEFAULT, '近 12 月']]) {
      const b = el('button', `gitviz-seg-btn${days === range ? ' is-on' : ''}`, label);
      b.type = 'button';
      b.setAttribute('aria-pressed', String(days === range));
      b.addEventListener('click', () => opts.onRange(days));
      seg.appendChild(b);
    }
    head.appendChild(seg);
  }
  host.appendChild(head);

  const cell = HEAT_CELL;
  const pitch = cell + HEAT_GAP;
  const left = mobile ? 22 : 26;
  const top = mobile ? 12 : 14;

  let cols;
  let valueOf;      // (col,row) → {count,label}
  if (monthly) {
    // 按月聚合：列 = 月份（末 12 个月），行 = 0（单行）
    const months = new Map();
    for (const d of heatDays) {
      const k = d.date.slice(0, 7);
      months.set(k, (months.get(k) || 0) + d.count);
    }
    const list = [...months.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1)).slice(-12);
    const max = Math.max(1, ...list.map(([, c]) => c));
    cols = list.length;
    valueOf = (c) => (c < list.length
      ? { count: list[c][1], label: `${Number(list[c][0].slice(5, 7))}月 · ${list[c][1]} 次提交`, short: `${Number(list[c][0].slice(5, 7))}月` }
      : null);
  } else {
    const first = new Date(`${heatDays[0].date}T00:00:00`);
    const lead = (first.getDay() + 6) % 7;           // 周一 = 0
    cols = Math.ceil((heatDays.length + lead) / 7);
    valueOf = (c, row) => {
      const idx = c * 7 + row - lead;
      const d = heatDays[idx];
      return d ? { count: d.count, label: `${d.date} · ${d.count} 次提交`, short: '' } : null;
    };
  }

  const rows = monthly ? 1 : 7;
  const w = left + cols * pitch;
  const h = top + rows * pitch;
  const s = svg('svg', { class: 'gitviz-heat-svg', width: w, height: h, viewBox: `0 0 ${w} ${h}`, role: 'img' });
  s.setAttribute('aria-label', monthly ? '近 12 个月按月聚合的提交数热力图' : `近 ${heatDays.length} 天每日提交数热力图`);

  if (!monthly) {
    const dayLabel = ['一', '三', '五'];
    for (const d of [0, 2, 4]) {
      const t = svg('text', { class: 'gitviz-heat-label', x: left - 4, y: top + d * pitch + cell - 3, 'text-anchor': 'end' });
      t.textContent = dayLabel[Math.floor(d / 2)];
      s.appendChild(t);
    }
  }

  const maxAll = monthly
    ? Math.max(1, ...Array.from({ length: cols }, (_, c) => valueOf(c)?.count || 0))
    : heat.max;
  for (let c = 0; c < cols; c += 1) {
    for (let r = 0; r < rows; r += 1) {
      const v = valueOf(c, r);
      if (!v) continue;
      if (monthly) {
        const label = svg('text', { class: 'gitviz-heat-label', x: left + c * pitch, y: top - 3 });
        label.textContent = v.short;
        s.appendChild(label);
      }
      const rect = svg('rect', {
        class: `gitviz-heat-cell is-lv${heatLevel(v.count, maxAll)}`,
        x: left + c * pitch,
        y: top + r * pitch,
        width: cell,
        height: cell,
        rx: 2.5,
      });
      const title = svg('title');
      title.textContent = v.label;
      rect.appendChild(title);
      s.appendChild(rect);
    }
  }
  host.appendChild(s);

  const legend = el('div', 'gitviz-heat-legend');
  legend.append(el('span', null, '少'));
  for (const a of HEAT_ALPHA) {
    const i = el('i');
    i.style.background = `rgb(var(--sapphire) / ${a})`;
    legend.appendChild(i);
  }
  legend.append(el('span', null, '多'));
  host.appendChild(legend);
}

// ── 泳道标签 ────────────────────────────────────────────────────────────────
/** 泳道 → 代表分支名（该泳道上第一个 ref；HEAD 优先）。图上没有 ref 的泳道给 `泳道 n`。 */
function laneName(g, lane) {
  const onLane = g.refs.filter((r) => r.lane === lane);
  if (!onLane.length) return `泳道 ${lane}`;
  const head = onLane.find((r) => r.isHead);
  return (head || onLane[0]).name;
}

// ── 渲染：提交行（桌面单一列表 / 手机按泳道分组共用一个构造器）──────────────
function commitList(g, state, rows, cls) {
  const list = el('ol', cls);
  const byLane = new Map(g.refs.map((r) => [r.name, r]));
  for (const n of rows) {
    const li = el('li', 'gitviz-row');
    li.tabIndex = 0;
    li.dataset.sha = n.sha;
    if (state.focus?.kind === 'commit' && state.focus.sha === n.sha) li.classList.add('is-selected');
    else if (state.focus?.kind === 'ref' && g.refs.find((r) => r.name === state.focus.name)?.lane === n.lane) li.classList.add('is-hot');
    const cb = /** @type {HTMLInputElement} */ (document.createElement('input'));
    cb.type = 'checkbox';
    cb.checked = state.selected.has(`commit:${n.sha}`);
    cb.setAttribute('aria-label', `选中提交 ${n.short}`);
    cb.addEventListener('click', (ev) => {
      ev.stopPropagation();
      toggleSelect(state, { key: `commit:${n.sha}`, kind: 'commit', sha: n.sha, short: n.short, label: `${n.short} ${n.subject}` });
      state.repaint();
    });
    li.appendChild(cb);
    for (const name of n.refs) {
      const isHead = byLane.get(name)?.isHead;
      const chip = el('button', `gitviz-ref-chip${isHead ? ' is-head' : ''}`, name);
      chip.type = 'button';
      chip.title = `分支 ${name}（点选看详情）`;
      chip.addEventListener('click', (ev) => {
        ev.stopPropagation();
        state.focus = { kind: 'ref', name };
        state.repaint();
      });
      li.appendChild(chip);
    }
    li.appendChild(el('span', 'gitviz-subject', n.subject || '(无提交信息)'));
    li.appendChild(el('span', 'gitviz-meta', `${n.short} · ${n.date}`));
    li.addEventListener('click', () => {
      state.focus = { kind: 'commit', sha: n.sha };
      state.repaint();
    });
    li.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter' || ev.key === ' ') {
        ev.preventDefault();
        state.focus = { kind: 'commit', sha: n.sha };
        state.repaint();
      }
    });
    list.appendChild(li);
  }
  return list;
}

// ── 渲染：桌面泳道树 ────────────────────────────────────────────────────────
function renderTree(host, g, state) {
  host.textContent = '';
  const rows = g.commits;
  const laneW = Math.max(64, LANE_PAD + Math.max(1, g.lanes) * LANE_PITCH);
  const canvas = el('div', 'gitviz-canvas');
  canvas.style.setProperty('--gitviz-lane-w', `${laneW}px`);
  canvas.style.setProperty('--gitviz-row', `${ROW_PITCH}px`);
  canvas.style.height = `${rows.length * ROW_PITCH}px`;

  const yOf = (row) => row * ROW_PITCH + ROW_PITCH / 2;
  const xOf = (lane) => LANE_PAD + lane * LANE_PITCH;

  // 泳道标签列（第二轮新增）：sticky 钉在树左缘 —— 横向平移时分支名仍可读。
  const labels = el('div', 'gitviz-lane-labels');
  labels.setAttribute('aria-label', '泳道分支标签');
  for (let lane = 0; lane < Math.max(1, g.lanes); lane += 1) {
    const name = laneName(g, lane);
    const isHead = g.refs.some((r) => r.lane === lane && r.isHead);
    const tag = el('button', `gitviz-lane-tag${isHead ? ' is-head' : ''}`);
    tag.type = 'button';
    tag.title = `${name}（泳道 ${lane}）`;
    tag.append(el('i'), el('span', null, name));
    tag.addEventListener('click', () => {
      const ref = g.refs.find((r) => r.name === name);
      state.focus = ref ? { kind: 'ref', name } : null;
      state.repaint();
    });
    labels.appendChild(tag);
  }
  canvas.appendChild(labels);

  const s = svg('svg', { class: 'gitviz-svg', width: laneW, height: rows.length * ROW_PITCH, viewBox: `0 0 ${laneW} ${rows.length * ROW_PITCH}` });
  const selSha = state.focus?.kind === 'commit' ? state.focus.sha : '';
  const laneOfRef = state.focus?.kind === 'ref' ? g.refs.find((r) => r.name === state.focus.name)?.lane : undefined;

  for (const e of g.edges) {
    const x1 = xOf(e.fromLane);
    const y1 = yOf(e.fromRow);
    const x2 = xOf(e.toLane);
    const y2 = yOf(e.toRow);
    const d = e.fromLane === e.toLane
      ? `M ${x1} ${y1} L ${x2} ${y2}`
      : `M ${x1} ${y1} C ${x1} ${(y1 + y2) / 2}, ${x2} ${(y1 + y2) / 2}, ${x2} ${y2}`;
    const hot = selSha && (e.from === selSha || e.to === selSha);
    const dim = (selSha || laneOfRef != null) && !hot && e.fromLane !== laneOfRef && e.toLane !== laneOfRef;
    const p = svg('path', { class: `gitviz-edge${hot ? ' is-hot' : ''}${dim ? ' is-dim' : ''}`, d });
    p.style.stroke = `rgb(var(--sapphire) / ${hot ? 1 : 0.5 + 0.1 * ((e.fromLane % 4) + 1)})`;
    s.appendChild(p);
  }
  for (const n of rows) {
    const isHead = g.head.sha === n.sha;
    const hot = selSha === n.sha || laneOfRef === n.lane;
    const c = svg('circle', { class: `gitviz-node${isHead ? ' is-head' : ''}`, cx: xOf(n.lane), cy: yOf(n.row), r: hot ? 4.6 : 3.4 });
    c.style.fill = `rgb(var(--sapphire) / ${hot ? 1 : 0.82})`;
    s.appendChild(c);
  }
  canvas.appendChild(s);
  canvas.appendChild(commitList(g, state, rows, 'gitviz-rows'));
  host.appendChild(canvas);
}

/**
 * 手机泳道面（第二轮 ②）：**缩略泳道图 + 按泳道折叠的纵向列表**。
 *
 * 为什么不是「把 8 条泳道压成一条线」：那等于丢掉 7 支的信息，作者明令不许当交付。
 * 为什么不是「整张图横滑」：375 宽横向平移时连节点所在泳道都会滑出视口，读一条
 * 提交要来回对齐；缩略图 + 分组列表把「看结构」与「读条目」拆成两件，各自都成立。
 * 缩略图的泳道间距用**真值**（按 lanes 均分，最小 LANE_MIN_MOBILE），一条泳道一列
 * ⇒ 8 条纵向走线彼此可辨；装不下时缩略图容器横滑，泳道标签列 sticky 钉住。
 */
function renderTreeMobile(host, g, state) {
  host.textContent = '';
  const rows = g.commits;
  const laneCount = Math.max(1, g.lanes);
  // 列距按**实测可用宽**算，不写死 300：缩略图左边有一列 96px 的泳道标签带
  // （.gitviz-lane-labels，sticky），把 375 视口吃掉一点；留死 300 会让 8 泳道
  // 画到 324+96=420 宽，右边两个泳道直接被裁掉（375 截图即证据）。
  // 下限 LANE_MIN_MOBILE：太窄时宁可整棵树横向滚动，也不把泳道压成一列。
  const labelW = MOBILE_LANE_LABEL_W;
  const avail = Math.max(LANE_MIN_MOBILE * laneCount, (host.clientWidth || 363) - labelW - 12);
  const pitch = Math.max(LANE_MIN_MOBILE, Math.floor(avail / laneCount));
  const laneW = Math.max(96, LANE_PAD + laneCount * pitch);
  const rowPitch = 14;
  const h = Math.max(rowPitch, rows.length * rowPitch);

  const thumbWrap = el('div', 'gitviz-tree gitviz-mini-graph');
  const canvas = el('div', 'gitviz-canvas');
  canvas.style.setProperty('--gitviz-lane-w', `${laneW}px`);
  canvas.style.setProperty('--gitviz-row', `${rowPitch}px`);
  canvas.style.height = `${h}px`;

  const laneY = new Map();       // lane → 该泳道首个提交的行号（标签贴首次出现处）
  for (const n of rows) if (!laneY.has(n.lane)) laneY.set(n.lane, n.row);

  const labels = el('div', 'gitviz-lane-labels');
  labels.setAttribute('aria-label', '泳道分支标签');
  // 标签贴「该泳道首个提交」的高度；本窗没有提交的泳道（长标签带里常见）不能全堆在
  // 顶部——那样 6 个标签互相压住等于只显示 1 个。用**单调递增**兜底：空泳道排在上一
  // 个标签之后一行，8 条泳道 8 个可读标签，顺序仍是泳道序。
  let prevOwn = null;      // 上一枚标签占的行（flex 列排：marginTop 写的是**增量**）
  let cursor = -1;         // 已经用到的最小行（空泳道从它之后接）
  for (let lane = 0; lane < laneCount; lane += 1) {
    const name = laneName(g, lane);
    const isHead = g.refs.some((r) => r.lane === lane && r.isHead);
    const tag = el('button', `gitviz-lane-tag${isHead ? ' is-head' : ''}`);
    tag.type = 'button';
    tag.title = `${name}（泳道 ${lane}，点按折叠该泳道组）`;
    const span = el('span', null, name);
    span.title = name;
    tag.append(el('i'), span);
    const ownRow = laneY.has(lane) ? laneY.get(lane) : cursor + 1;
    cursor = Math.max(cursor, ownRow);
    const delta = prevOwn == null ? ownRow : Math.max(0, ownRow - prevOwn);
    prevOwn = ownRow;
    tag.style.marginTop = `${delta * rowPitch}px`;
    tag.addEventListener('click', () => {
      state.collapsed.set(lane, !(state.collapsed.get(lane) ?? false));
      state.repaint();
    });
    labels.appendChild(tag);
  }
  canvas.appendChild(labels);

  const xOf = (lane) => LANE_PAD + lane * pitch;
  const yOf = (row) => row * rowPitch + rowPitch / 2;
  const s = svg('svg', { class: 'gitviz-svg', width: laneW, height: h, viewBox: `0 0 ${laneW} ${h}` });
  const laneOfRef = state.focus?.kind === 'ref' ? g.refs.find((r) => r.name === state.focus.name)?.lane : undefined;
  for (const e of g.edges) {
    const x1 = xOf(e.fromLane);
    const y1 = yOf(e.fromRow);
    const x2 = xOf(e.toLane);
    const y2 = yOf(e.toRow);
    const d = e.fromLane === e.toLane
      ? `M ${x1} ${y1} L ${x2} ${y2}`
      : `M ${x1} ${y1} C ${x1} ${(y1 + y2) / 2}, ${x2} ${(y1 + y2) / 2}, ${x2} ${y2}`;
    const dim = laneOfRef != null && e.fromLane !== laneOfRef && e.toLane !== laneOfRef;
    const p = svg('path', { class: `gitviz-edge${dim ? ' is-dim' : ''}`, d });
    p.style.stroke = `rgb(var(--sapphire) / ${0.55 + 0.1 * ((e.fromLane % 4) + 1)})`;
    s.appendChild(p);
  }
  for (const n of rows) {
    const c = svg('circle', { class: `gitviz-node${g.head.sha === n.sha ? ' is-head' : ''}`, cx: xOf(n.lane), cy: yOf(n.row), r: 3 });
    c.style.fill = `rgb(var(--sapphire) / ${laneOfRef === n.lane ? 1 : 0.82})`;
    s.appendChild(c);
  }
  canvas.appendChild(s);
  thumbWrap.appendChild(canvas);

  // 按泳道分组的纵向列表（可折叠）
  const groups = el('div', 'gitviz-lane-groups');
  for (let lane = 0; lane < laneCount; lane += 1) {
    const laneRows = rows.filter((n) => n.lane === lane);
    if (!laneRows.length) continue;
    const name = laneName(g, lane);
    const collapsed = state.collapsed.get(lane) ?? false;
    const group = el('div', 'gitviz-lane-group');
    group.dataset.collapsed = String(collapsed);
    group.dataset.lane = String(lane);
    const head = el('button', 'gitviz-lane-head');
    head.type = 'button';
    head.setAttribute('aria-expanded', String(!collapsed));
    head.append(el('i'), el('span', null, `泳道 ${lane} · ${name}`), el('span', 'gitviz-lane-count', `${laneRows.length} 提交`));
    head.addEventListener('click', () => {
      const next = !(state.collapsed.get(lane) ?? false);
      state.collapsed.set(lane, next);
      group.dataset.collapsed = String(next);
      head.setAttribute('aria-expanded', String(!next));
    });
    const body = el('div', 'gitviz-lane-body');
    body.appendChild(commitList(g, state, laneRows, 'gitviz-lane-body-list'));
    group.append(head, body);
    groups.appendChild(group);
  }

  host.append(thumbWrap, groups);
}

// ── 渲染：右操控面板 ────────────────────────────────────────────────────────
function renderDetail(host, g, state) {
  host.textContent = '';
  host.appendChild(el('div', 'gitviz-sec-title', '选中项详情'));
  const f = state.focus;
  if (!f) {
    host.appendChild(el('div', 'gitviz-detail-empty', '点左侧提交行 / 分支标签查看详情。本面板只读：不会改动仓库。'));
    return;
  }
  const dl = el('dl', 'gitviz-kv');
  const add = (k, v, mono) => {
    dl.appendChild(el('dt', null, k));
    dl.appendChild(el('dd', mono ? 'mono' : null, v));
  };
  if (f.kind === 'commit') {
    const n = g.commits.find((c) => c.sha === f.sha);
    if (!n) return;
    add('提交', n.short, true);
    add('时间', new Date(n.ts).toLocaleString('zh-CN', { hour12: false }));
    add('作者', n.author);
    add('泳道', `#${n.lane} · ${laneName(g, n.lane)}`);
    add('父提交', n.parents.map((p) => p.slice(0, 8)).join(', ') || '(根提交)', true);
    add('分支', n.refs.join(', ') || '—');
    add('标题', n.subject);
  } else {
    const r = g.refs.find((x) => x.name === f.name);
    if (!r) return;
    add('分支', r.name, true);
    add('tip', r.tipShort, true);
    add('相对 HEAD', r.isHead ? '即 HEAD' : `领先 ${r.ahead ?? '—'} / 落后 ${r.behind ?? '—'}`);
    add('merge-base', r.mergeBase ? r.mergeBase.slice(0, 8) : '—', true);
    add('tip 时间', new Date(r.ts).toLocaleString('zh-CN', { hour12: false }));
    add('在图上', r.inGraph ? '是' : '否（超出首屏提交窗）');
  }
  host.appendChild(dl);
}

function renderRefs(host, g, state) {
  host.textContent = '';
  const head = el('div', 'gitviz-sec-title', `分支清单（图上 ${g.refsShown} / 本仓 ${g.refsTotal} 支）`);
  host.appendChild(head);
  for (const r of g.refs) {
    const row = el('div', 'gitviz-ref-row');
    const cb = /** @type {HTMLInputElement} */ (document.createElement('input'));
    cb.type = 'checkbox';
    cb.checked = state.selected.has(`ref:${r.name}`);
    cb.setAttribute('aria-label', `选中分支 ${r.name}`);
    cb.addEventListener('change', () => {
      toggleSelect(state, { key: `ref:${r.name}`, kind: 'ref', name: r.name, label: `分支 ${r.name}`, tip: r.tip, ahead: r.ahead, behind: r.behind });
      state.repaint();
    });
    const name = el('span', 'gitviz-ref-name', r.name);
    name.title = r.name;
    name.addEventListener('click', () => {
      state.focus = { kind: 'ref', name: r.name };
      state.repaint();
    });
    const ab = el('span', 'gitviz-ref-ab', r.isHead ? 'HEAD' : `${r.ahead ?? '?'}/${r.behind ?? '?'}`);
    ab.title = '领先 / 落后 HEAD 的提交数（git rev-list --left-right --count）';
    row.append(cb, name, ab);
    host.appendChild(row);
  }
  host.appendChild(el('div', 'gitviz-note-io', `本节读数为只读口径；图上只画最近 ${g.refsShown} 支、${g.commitsTotal} 个提交（上限 ${g.commitsLimit}）。`));
}

function renderNotes(host, state) {
  host.textContent = '';
  if (!state.selected.size) {
    host.appendChild(el('div', 'gitviz-sec-title', '备注（勾选后可写）'));
    host.appendChild(el('div', 'gitviz-detail-empty', '未勾选任何项。'));
    return;
  }
  host.appendChild(el('div', 'gitviz-sec-title', `备注 · 已勾选 ${state.selected.size} 项`));
  for (const item of state.selected.values()) {
    const row = el('div', 'gitviz-note-row');
    row.appendChild(el('span', null, item.label));
    const ta = /** @type {HTMLTextAreaElement} */ (document.createElement('textarea'));
    ta.className = 'gitviz-note';
    ta.rows = 2;
    ta.placeholder = '给本项的备注（随交办请求一起带走）';
    ta.value = item.note || '';
    ta.setAttribute('aria-label', `备注：${item.label}`);
    ta.addEventListener('input', () => { item.note = ta.value; });
    row.appendChild(ta);
    host.appendChild(row);
  }
}

function toggleSelect(state, item) {
  if (state.selected.has(item.key)) state.selected.delete(item.key);
  else state.selected.set(item.key, item);
}

// ── 交办出口（唯一一条）────────────────────────────────────────────────────
/**
 * 结构化交办请求的形状（**唯一构造点**）。
 *
 * 作者口径（2026-11-19 18:41 + 设计稿 §七「权限口径」）：面板与问答 agent 都**没有
 * git 写能力**；「把 A、B 合进 feat/base」这类请求一律表达为**请求文本**交给主会话，
 * 由主会话按落支规则派发执行。四件 = 分支清单 · 目标线 · 意图 · 理由（+ 逐项备注）。
 *
 * 通道 = 既有 `askHandoff` WS 帧（canvas ask §7 转交的同一条腿，见
 * src/main/scala/nebflow/gateway/WsAskHandlers.scala:241 handleAskHandoff）：
 * 它已经是「结构化四件 + 投进主会话 + askHandoffAck 回执」的现成通路，
 * 本轮只加一个 `git` 子对象承载 gitviz 特有字段（分支清单/目标线），不新造第二条。
 */
export function buildGitHandoff(gvState, input = {}) {
  const branches = (input.branches || [...gvState.selected.values()]
    .filter((it) => it.kind === 'ref')
    .map((it) => it.name));
  const target = String(input.target || gvState.mergeTarget?.() || '').trim();
  const notes = [...gvState.selected.values()].filter((it) => it.note).map((it) => `${it.label}：${it.note}`);
  return {
    branches,
    target,
    intent: String(input.intent || 'merge').trim(),
    reason: String(input.reason || '面板/问答 agent 只读，写操作唯一执行者 = 主会话').trim(),
    notes,
    source: input.source === 'ask' ? 'ask' : 'panel',
    project: gvState.project,
  };
}

/** 交办请求 → 人读单行（与后端 AskHandoffWire.summary 同口径的压缩；UI 回执用）。 */
export function handoffSummaryLine(req) {
  const verb = req.intent === 'merge' ? '合并' : req.intent;
  const head = `${verb} ${req.branches.join('、') || '(未选分支)'} → ${req.target || '(未定目标线)'}`;
  const note = req.notes.length ? ` · ${req.notes.length} 条备注` : '';
  return head + note;
}

/**
 * 发出去（**两个入口共用这一个函数**：面板多选合并钮 / 问答 agent 的结构化交办）。
 * @returns {boolean} 帧是否已送出（false = WS 未连，帧进了 sendWs 的有界队列）
 */
export function submitHandoff(gvState, req) {
  if (!req.branches.length) return false;
  const sid = ensureGitVizAskSession(gvState);
  return sendWs({
    type: 'askHandoff',
    sessionId: sid,
    // 结构化四件（后端 AskHandoffPort.Request 的同名槽）
    task: req.intent === 'merge'
      ? `把 ${req.branches.join('、')} 合进 ${req.target}`
      : `${req.intent}：${req.branches.join('、')} → ${req.target}`,
    paths: [req.target || 'HEAD', ...req.branches],
    reason: req.reason,
    expected: req.target ? `${req.target} 上出现这些分支的合并结果（按落支规则）` : '',
    // gitviz 专有结构化面（后端 handleAskHandoff 的 `git` 子对象）
    git: {
      project: req.project,
      branches: req.branches,
      target: req.target,
      intent: req.intent,
      notes: req.notes,
      source: req.source,
    },
  });
}

// ── 分支管理问答（canvas ask 形态，只问答）──────────────────────────────────
/** 会话 id：`ask-gv-<8hex(project)>` —— 稳定键（同项目重开同一会话，同 ask 每文件一会话律）。 */
function gvSessionId(project) {
  const src = `gitviz:${project}`;
  let h = 0x811c9dc5;
  for (let i = 0; i < src.length; i += 1) {
    h ^= src.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return GV_SID_PREFIX + h.toString(16).padStart(8, '0');
}

/** 打开（或复用）该项目的分支管理问答会话。 */
function ensureGitVizAskSession(gvState) {
  const sid = gvSessionId(gvState.project);
  if (gvState.ask.sid !== sid || !gvState.ask.opened) {
    gvState.ask.sid = sid;
    gvState.ask.opened = true;
    // 项目域绑定（后端 askOpen 的 gitviz 分支：按项目解仓库根，不走文件类型白名单）
    sendWs({ type: 'askOpen', kind: 'gitviz', project: gvState.project, sessionId: sid });
  }
  return sid;
}

// 会话 → 视图状态（帧回调按 sid 找面板；面板重挂即覆盖）
const LIVE = new Map();
function liveStateFor(sid) {
  return LIVE.get(sid) || null;
}

/**
 * ask 帧消费者（第二消费者注册面 = ws.js 的 addAskFrameSink）。
 *
 * 只吃本面板自己那几个会话（`ask-gv-` 前缀）的帧，其余 ask-* 帧原样放行给 askDock
 * ——两条腿同一条 `ask-` 拦截面、零交集（作者令「复用同一条路」的运行时形态）。
 */
export function onGitVizAskFrame(msg) {
  const sid = msg?.sessionId;
  if (typeof sid !== 'string' || !sid.startsWith(GV_SID_PREFIX)) return;
  const gv = liveStateFor(sid);
  if (!gv) return;
  switch (msg.type) {
    case 'askState': {
      if (Array.isArray(msg.messages)) {
        gv.ask.msgs = msg.messages.map((m) => ({ role: m.role, text: m.text }));
        gv.ask.paint();
      }
      break;
    }
    case 'textDelta': {
      const last = gv.ask.msgs[gv.ask.msgs.length - 1];
      const chunk = msg.delta || msg.text || '';
      if (last && last.role === 'ai' && gv.ask.streaming) last.text += chunk;
      else { gv.ask.msgs.push({ role: 'ai', text: chunk }); gv.ask.streaming = true; }
      gv.ask.paint();
      break;
    }
    case 'done': {
      gv.ask.streaming = false;
      gv.ask.paint();
      break;
    }
    case 'error':
    case 'askError': {
      gv.ask.streaming = false;
      gv.ask.msgs.push({ role: 'sys', text: `出错了：${msg.message || msg.error || '未知原因'}` });
      gv.ask.paint();
      break;
    }
    case 'askHandoffAck': {
      // 回执 = 机器可读 handoff + 人读 summary（后端 AskHandoffWire.ackFrame 单源）
      gv.ask.msgs.push({
        role: 'sys',
        text: msg.ok
          ? `已转交主会话：${msg.summary || msg.question || ''}`
          : `转交失败：${msg.error || '未知原因'}`,
      });
      gv.ask.paint();
      break;
    }
    default:
      break;
  }
}

/** 问答窗（canvas ask 的同一形态：气泡列 + 输入条 + 转交回执行）。 */
function buildAskDock(gvState, root) {
  const dock = el('div', 'gitviz-ask-dock nf-glass');
  dock.hidden = true;
  dock.setAttribute('role', 'dialog');
  dock.setAttribute('aria-label', '分支管理问答（只问答）');

  const head = el('div', 'gitviz-ask-head');
  head.append(el('b', null, '分支管理问答'));
  const ro = el('span', 'gitviz-ask-ro', '只问答');
  ro.title = '本 agent 没有任何 git 写能力：数据面只有只读子命令，合并/建支/push 一律交办主会话';
  head.appendChild(ro);
  const closeBtn = el('button', 'gitviz-corner gitviz-corner--inline');
  closeBtn.type = 'button';
  closeBtn.setAttribute('aria-label', '关闭问答');
  closeBtn.appendChild(ICONS.close());
  head.appendChild(closeBtn);

  const col = el('div', 'gitviz-ask-col');
  col.setAttribute('aria-live', 'polite');

  const handoffLine = el('div', 'gitviz-ask-handoff');
  handoffLine.hidden = true;

  const form = el('div', 'gitviz-ask-input');
  const ta = /** @type {HTMLTextAreaElement} */ (document.createElement('textarea'));
  ta.rows = 1;
  ta.placeholder = '问分支状态，或说「把 A、B 合进 feat/base」——后者转交主会话';
  ta.setAttribute('aria-label', '问答输入');
  const sendBtn = el('button', 'gitviz-btn gitviz-ask-send');
  sendBtn.type = 'button';
  sendBtn.setAttribute('aria-label', '发送');
  sendBtn.appendChild(ICONS.send());
  form.append(ta, sendBtn);

  dock.append(head, col, handoffLine, form);
  root.appendChild(dock);

  const paint = () => {
    col.textContent = '';
    if (!gvState.ask.msgs.length) {
      col.appendChild(el('div', 'gitviz-ask-bub is-sys',
        '我是分支管理问答分身：能回答分支/提交/合并基数的问题。要动手（合并、建支、push）请直接说——我产出结构化交办请求转给主会话，我自己没有任何 git 写能力。'));
    }
    for (const m of gvState.ask.msgs) col.appendChild(el('div', `gitviz-ask-bub is-${m.role}`, m.text));
    col.scrollTop = col.scrollHeight;
  };
  gvState.ask.paint = paint;
  gvState.ask.handoffLine = handoffLine;

  const submit = () => {
    const text = ta.value.trim();
    if (!text) return;
    ta.value = '';
    gvState.ask.msgs.push({ role: 'user', text });
    const sid = ensureGitVizAskSession(gvState);
    sendWs({ type: 'askQuestion', sessionId: sid, content: text, mainSessionId: appState.activeSessionId || '' });
    paint();
  };
  sendBtn.addEventListener('click', submit);
  ta.addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter' && !ev.shiftKey) { ev.preventDefault(); submit(); }
  });
  closeBtn.addEventListener('click', () => { dock.hidden = true; gvState.ask.open = false; });
  return { dock, paint, focusInput: () => ta.focus() };
}

// ── 四角控件（手机端，canvas 同款操作逻辑）───────────────────────────────────
/**
 * 四角分工（作者建议，本实现逐条落位；理由随钮）：
 *   左上 返回/关闭 —— 与顶部返回条同义（回项目列表），拇指不离开左上区即可退出；
 *   右上 视图与筛选 —— 泳道全开/全收（手机读图的第一个动作是「先看结构还是先读条」）；
 *   左下 缩放/热力图 —— 薄面板：热力图区间档 + 泳道折叠 + 图例（低频、需要细看时才开）；
 *   右下 分支管理 ask FAB —— 最高频动作（问/交办），落在右手拇指位，同 canvas 的 ask 座位。
 * 中间留图：容器 pointer-events:none、钮自身 auto ⇒ 命中面只被四颗圆钮吃掉。
 */
function buildCorners(gvState, onBack) {
  const wrap = el('div', 'gitviz-corners');
  const mk = (corner, label, ic) => {
    const b = el('button', `gitviz-corner gitviz-corner--${corner} g2-fab`);
    b.type = 'button';
    b.setAttribute('aria-label', label);
    b.title = label;
    b.appendChild(ic);
    wrap.appendChild(b);
    return b;
  };

  const back = mk('tl', '返回项目列表', ICONS.back());
  back.addEventListener('click', () => { if (typeof onBack === 'function') onBack(); });

  const view = mk('tr', '视图与筛选', ICONS.view());
  view.setAttribute('aria-pressed', 'false');
  view.addEventListener('click', () => {
    const allCollapsed = gvState.collapsedAll();
    gvState.setCollapsedAll(!allCollapsed);
    view.setAttribute('aria-pressed', String(!allCollapsed));
    gvState.repaint();
  });

  const zoom = mk('bl', '缩放与热力图', ICONS.zoom());
  zoom.addEventListener('click', () => gvState.sheet.toggle(zoom));

  const ask = mk('br', '分支管理问答', ICONS.git());
  ask.setAttribute('aria-haspopup', 'dialog');
  ask.addEventListener('click', () => {
    const open = gvState.ask.dock.hidden;
    gvState.ask.dock.hidden = !open;
    gvState.ask.open = open;
    if (open) {
      ensureGitVizAskSession(gvState);
      gvState.ask.paint();
      gvState.ask.dock.querySelector('textarea')?.focus();
    }
  });
  return wrap;
}

/** 左下角钮开出的薄面板（热力图档 / 泳道折叠 / 图例）——不是遮罩，再点即收。 */
function buildSheet(gvState, root) {
  const sheet = el('div', 'gitviz-sheet nf-glass');
  sheet.hidden = true;

  const mkRow = (title) => {
    const row = el('div', 'gitviz-sheet-row');
    row.appendChild(el('span', 'gitviz-sheet-title', title));
    return row;
  };

  const heatRow = mkRow('热力图区间');
  const seg = el('span', 'gitviz-heat-seg');
  heatRow.appendChild(seg);

  const laneRow = mkRow('泳道');
  const laneBtn = el('button', 'gitviz-seg-btn', '全部折叠');
  laneBtn.type = 'button';
  laneRow.appendChild(laneBtn);

  const legend = mkRow('图例');
  legend.appendChild(el('span', null, '节点 = 提交 · 竖线 = 泳道 · 曲线 = 分叉/合流'));

  sheet.append(heatRow, laneRow, legend);
  root.appendChild(sheet);

  return {
    el: sheet,
    heatSeg: seg,
    laneBtn,
    toggle(anchor) {
      sheet.hidden = !sheet.hidden;
      anchor?.setAttribute('aria-pressed', String(!sheet.hidden));
    },
    close() { sheet.hidden = true; },
    syncHeatSeg(days) {
      seg.textContent = '';
      for (const [d, label] of [[HEAT_DAYS_WEEK, '近 12 周'], [HEAT_DAYS_DEFAULT, '近 12 月']]) {
        const b = el('button', `gitviz-seg-btn${d === days ? ' is-on' : ''}`, label);
        b.type = 'button';
        b.setAttribute('aria-pressed', String(d === days));
        b.addEventListener('click', () => this.onPick?.(d));
        seg.appendChild(b);
      }
    },
  };
}

/** 默认目标线：优先 `feat/base`（本仓落支母线的既有命名），否则 HEAD 所在支，否则第一支。 */
function defaultTarget(g) {
  const names = g.refs.map((r) => r.name);
  if (names.includes('feat/base')) return 'feat/base';
  const headRef = g.refs.find((r) => r.isHead);
  return headRef?.name || names[0] || '';
}

// Measure visible overlays against the actual scroll owner, as canvas does for
// its bottom chrome. Re-evaluate on resize and when either dismissible panel
// opens or closes; ignore disconnected views instead of retaining observers.
function observeGitVizClearance(root, owner) {
  const controls = [...root.querySelectorAll('.gitviz-corner--tl, .gitviz-corner--tr, .gitviz-corner--bl, .gitviz-corner--br, .gitviz-ask-dock, .gitviz-sheet')];
  let frame = 0;
  const sync = () => {
    frame = 0;
    if (!root.isConnected) { resize.disconnect(); mutation.disconnect(); return; }
    const bounds = owner.getBoundingClientRect();
    if (bounds.width < 1 || bounds.height < 1) return;
    let top = Infinity;
    for (const control of controls) {
      const style = getComputedStyle(control);
      const rect = control.getBoundingClientRect();
      if (control.hidden || style.display === 'none' || style.visibility === 'hidden' || rect.width < 1 || rect.height < 1) continue;
      if (rect.right <= bounds.left || rect.left >= bounds.right || rect.top >= bounds.bottom || rect.bottom <= bounds.top) continue;
      if (control.matches('.gitviz-corner--tl, .gitviz-corner--tr')) continue;
      top = Math.min(top, Math.max(bounds.top, rect.top));
    }
    const next = `${Number.isFinite(top) ? Math.round((bounds.bottom - top + 12) * 100) / 100 : 0}px`;
    if (owner.style.getPropertyValue('--gitviz-bottom-clearance') !== next) owner.style.setProperty('--gitviz-bottom-clearance', next);
  };
  const schedule = () => { if (!frame) frame = requestAnimationFrame(sync); };
  const resize = new ResizeObserver(schedule);
  resize.observe(owner);
  controls.forEach(control => resize.observe(control));
  const mutation = new MutationObserver(schedule);
  controls.forEach(control => mutation.observe(control, { attributes: true, attributeFilter: ['hidden', 'class', 'style'] }));
  schedule();
}

// ── 组装一次视图（数据到达后）──────────────────────────────────────────────
function paint(pane, g, opts = {}) {
  const root = pane.querySelector('.gitviz');
  if (!root) return;
  root.dataset.state = 'ready';
  root.textContent = '';

  const mobile = isMobile();
  const lanes = Math.max(1, g.lanes);
  const gvState = {
    project: g.project,
    graph: g,
    focus: null,
    selected: new Map(),
    heatDays: mobile ? HEAT_DAYS_WEEK : g.heatmap.days.length,
    collapsed: new Map(),
    repaint: null,
    selectedTarget: '',
    ask: { sid: '', opened: false, open: false, msgs: [], streaming: false, dock: null, paint: () => {}, handoffLine: null },
    collapsedAll: () => [...Array(lanes).keys()].every((l) => gvState.collapsed.get(l) ?? false),
    setCollapsedAll: (v) => { for (let l = 0; l < lanes; l += 1) gvState.collapsed.set(l, v); },
    mergeTarget: () => gvState.selectedTarget || defaultTarget(g),
    sheet: null,
    onBack: opts.onBack,
  };

  const heat = el('section', 'gitviz-heat');
  const main = el('div', 'gitviz-main');
  const tree = el('div', 'gitviz-tree');
  tree.tabIndex = 0;
  tree.setAttribute('role', 'region');
  tree.setAttribute('aria-label', '只读分支泳道树');
  const ctl = el('aside', 'gitviz-ctl');

  const detail = el('div');
  const refs = el('div');
  const notes = el('div');

  // 目标线选择（交办请求的 target；默认 feat/base 或 HEAD 分支）
  const targetRow = el('div', 'gitviz-forward');
  targetRow.appendChild(el('span', 'gitviz-forward-hint', '目标线'));
  const targetSel = /** @type {HTMLSelectElement} */ (document.createElement('select'));
  targetSel.className = 'gitviz-ref-name';
  targetSel.setAttribute('aria-label', '交办目标分支');
  for (const r of g.refs) {
    const o = el('option', null, r.name);
    o.value = r.name;
    targetSel.appendChild(o);
  }
  targetSel.value = defaultTarget(g);
  targetSel.addEventListener('change', () => { gvState.selectedTarget = targetSel.value; });
  targetRow.appendChild(targetSel);

  const footer = el('div', 'gitviz-forward');
  const btn = el('button', 'gitviz-btn', '多选合并 → 交办主会话');
  btn.type = 'button';
  btn.disabled = true;
  const hint = el('span', 'gitviz-forward-hint', '只收集与展示，不写仓库');
  const out = el('div', 'gitviz-note-io');
  footer.append(btn, hint);
  ctl.append(detail, refs, notes, targetRow, footer, out);

  const repaint = () => {
    btn.disabled = gvState.selected.size === 0;
    if (mobile) renderTreeMobile(tree, g, gvState);
    else renderTree(tree, g, gvState);
    renderDetail(detail, g, gvState);
    renderRefs(refs, g, gvState);
    renderNotes(notes, gvState);
    if (gvState.sheet) gvState.sheet.laneBtn.textContent = gvState.collapsedAll() ? '全部展开' : '全部折叠';
  };
  gvState.repaint = repaint;

  // 入口①（既有入口保留）：多选 + 合并钮 ⇒ 与问答 agent **同一个出口**
  btn.addEventListener('click', () => {
    const req = buildGitHandoff(gvState, { source: 'panel' });
    const sent = submitHandoff(gvState, req);
    out.textContent = `${sent ? '已交办' : '已入队待发'}：${handoffSummaryLine(req)}（本面板零仓库写入）`;
  });

  // 热力图区间档切换（近 12 周 ⇄ 近 12 月）：只补拉热力图这一段读数（小窗请求，
  // 不重取整张图），渲染只动热力区块。零写请求、零仓库写入。
  const refreshHeat = async (days) => {
    if (days === gvState.heatDays) return;
    gvState.heatDays = days;
    const next = await fetchGitGraph(gvState.project, { heatDays: days, limit: 20, lanes: 2 }).catch(() => null);
    if (!next || !next.heatmap) return;
    gvState.graph.heatmap = next.heatmap;
    renderHeatmap(heat, next.heatmap, { days, mobile, onRange: refreshHeat });
    gvState.sheet?.syncHeatSeg(days);
  };
  renderHeatmap(heat, g.heatmap, { days: gvState.heatDays, mobile, onRange: refreshHeat });

  main.append(tree, ctl);
  root.append(heat, main);

  // 薄面板 + 问答窗 + 四角控件（都挂在 .gitviz 内：包含块 = .gitviz，见 CSS）
  const sheet = buildSheet(gvState, root);
  gvState.sheet = sheet;
  sheet.onPick = (d) => { refreshHeat(d); sheet.syncHeatSeg(d); };
  sheet.syncHeatSeg(gvState.heatDays);
  sheet.laneBtn.addEventListener('click', () => {
    gvState.setCollapsedAll(!gvState.collapsedAll());
    repaint();
  });

  const askDock = buildAskDock(gvState, root);
  gvState.ask.dock = askDock.dock;
  LIVE.set(gvSessionId(g.project), gvState);

  root.appendChild(buildCorners(gvState, opts.onBack));
  observeGitVizClearance(root, main);
  repaint();

  // 初次进入：默认聚焦 HEAD 的提交行（有内容可见，不是空详情）
  const headNode = g.commits.find((c) => c.sha === g.head.sha);
  if (headNode) {
    gvState.focus = { kind: 'commit', sha: headNode.sha };
    repaint();
  }
}

// ── 入口：卡片翻转 → 次级面板 ──────────────────────────────────────────────
/**
 * 打开（或复用）projects 页签内的 git 可视化次级面板。
 * @param {HTMLElement} pane `.canvas-tab-pane[data-type="projects"]`
 * @param {string} projectName 项目名（数据面 = API.gitGraph）
 * @param {{fromCard?: HTMLElement|null, onBack?: (() => void)|null, data?: any}} [opts]
 */
export async function openGitViz(pane, projectName, opts = {}) {
  if (!pane) return;
  ensureGitVizCss();
  const reduced = prefersReducedMotion();
  const card = opts.fromCard || null;

  // ① 翻转（卡片 3D 转出）——reduced-motion 直接落位，不做动画（同 listFlip 判据）。
  if (card && card.isConnected && !reduced) {
    card.classList.add('is-gitviz-out');
    await new Promise((r) => setTimeout(r, 190));
  }
  if (card) card.classList.remove('is-gitviz-out');

  // ② 铺次级面板骨架（复用既有就地视图壳：返回条 + 视图体）
  // 🔴 DOM 一律走构建器（禁裸 innerHTML 赋值：component-contract 闸 R5；绕过净化面 = 回退）。
  pane.dataset.projectsView = 'git-viz';
  pane.dataset.gitVizProject = String(projectName || '');
  pane.textContent = '';

  const nav = el('div', 'flowmap-nav-bar');
  const back = el('button', 'flowmap-back-btn');
  back.type = 'button';
  back.setAttribute('data-gitviz-back', '');
  back.setAttribute('aria-label', '返回项目列表');
  const backIcon = svg('svg', { viewBox: '0 0 24 24', width: 14, height: 14, fill: 'none', stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' });
  backIcon.appendChild(svg('path', { d: 'M19 12H5M12 19l-7-7 7-7' }));
  const backName = el('span', 'flowmap-back-name', `← ${projectName || '项目'}`);
  back.append(backIcon, backName);
  const navTitle = el('span', 'delegate-chain-title', 'Git 可视化 · 只读');
  const navSummary = el('span', 'flowmap-summary flowmap-nav-summary');
  navSummary.setAttribute('aria-live', 'polite');
  nav.append(back, navTitle, navSummary);

  const body = el('div', 'flowmap-view-body gitviz-view-body');
  const root = el('div', 'gitviz');
  root.dataset.state = 'loading';
  root.appendChild(el('div', 'gitviz-state', '正在读取 git 图…'));
  body.appendChild(root);
  pane.append(nav, body);

  const goBack = () => { if (typeof opts.onBack === 'function') opts.onBack(); };
  back.addEventListener('click', (e) => { e.stopPropagation(); goBack(); });

  const graph = opts.data || (await loadGraph(projectName, root));
  if (!graph) return;
  paint(pane, graph, { onBack: goBack });
  // 落地淡入（与卡片转出成对）
  if (!reduced) root.classList.add('is-entering');
}

/** 视图内的错误态（DOM 构建器，禁裸 innerHTML）。 */
function stateBlock(host, text) {
  host.textContent = '';
  host.appendChild(el('div', 'gitviz-state', text));
}

async function loadGraph(projectName, view) {
  try {
    // 初始窗 = 本档默认档位（手机 12 周 / 桌面 12 月）——取数窗与渲染档位同源，
    // 否则会出现「数据 371 天 / 画 12 周」的错配。
    const g = await fetchGitGraph(projectName, { heatDays: isMobile() ? HEAT_DAYS_WEEK : HEAT_DAYS_DEFAULT });
    if (!g || g.state !== 'ok') {
      if (view) stateBlock(view, `无法读取 git 图：${g?.message || '未知原因'}`);
      return null;
    }
    return g;
  } catch (err) {
    if (view) stateBlock(view, `git 图读取失败：${err?.message || err}`);
    return null;
  }
}

/** 重渲（pane 被 canvas 重建后 / 手动刷新）。保持 projectsView 守卫语义。 */
export async function rerenderGitVizPane(pane) {
  if (!pane || pane.dataset.projectsView !== 'git-viz') return;
  const name = pane.dataset.gitVizProject || '';
  const view = pane.querySelector('.gitviz');
  if (!view || !name) return;
  view.dataset.state = 'loading';
  stateBlock(view, '正在读取 git 图…');
  const g = await loadGraph(name, view);
  if (g) paint(pane, g);
}

// 帧消费者注册（模块加载即挂；返回的注销器留给热重载/测试）。
export const disposeGitVizAskFrames = addAskFrameSink(onGitVizAskFrame);
