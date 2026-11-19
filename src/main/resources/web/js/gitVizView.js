// gitVizView.js — 项目面板「次级面板」：Git 可视化（MVP 2026-11-19）。
//
// 依据（本批唯一设计源）：`<dataRoot>/projects/stand/.stand/docs/git-viz-merge-research-
// design__20261119_123007.md` §七「项目面板次级面板（git 树 + 多选转发）」+ §八「复用什么、
// 新造什么」。作者 2026-11-19 14:06 逐字要求：卡片翻转到次级面板，次级面板最上面放热力图、
// 左侧可视化树、右侧操控面板。
//
// 复用（不新造第二套）：
//   · **次级面板机制** = projects 页签的「就地视图」`pane.dataset.projectsView`（既有消费点
//     见 projectTab.js:174/1475/1501/1523）＋ `.flowmap-nav-bar` 返回条 ＋ `.flowmap-view-body`
//     视图体（骨架与 delegateChainView.js:143-204 同形，不另起一套壳）。
//   · **动效降级** = listFlip.js 的 prefersReducedMotion()（同一判据，不重复实现）；
//     缓动族取值同 projectPanel.css §F 表（cubic-bezier(0.16, 1, 0.3, 1)）。
//   · **数据面** = 一条只读端点（nodeData.js 的 API.gitGraph）。PANEL 只读：本文件零写请求
//     （除 `POST /api/gitviz/forward` —— 那是**本地日志收集**，不落仓库；见 §MVP 边界）。
//
// 硬约束（本批）：只收集与展示；绝不写仓库、不自动合并、不删分支。写操作唯一执行者 = root。

import { fetchGitGraph } from './nodeData.js';
import { prefersReducedMotion } from './listFlip.js';

const CSS_ID = 'gitviz-style';
const SVG_NS = 'http://www.w3.org/2000/svg';

// 几何（与 gitViz.css 的 --gitviz-row 同源；改一处必须同步另一处）
const ROW_PITCH = 22;      // 行距（px）
const LANE_PITCH = 9;      // 泳道间距（px）
const LANE_PAD = 20;       // 泳道带左垫（留节点半径与描边）
const HEAT_CELL = 13;      // 热力图格子边长
const HEAT_GAP = 3;
const HEAT_DAYS_DEFAULT = 371;   // 默认「近 12 个月」（作者口径：近 12 周或 12 月）
const HEAT_DAYS_WEEK = 84;       // 「近 12 周」档

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

/** 热力图色阶：零新色值 —— 全部是既有结构色 `--sapphire` 的透明度档（5 档，越深越多）。 */
const HEAT_ALPHA = [0.06, 0.22, 0.42, 0.64, 0.9];
function heatAlpha(count, max) {
  if (!count) return HEAT_ALPHA[0];
  const r = max > 0 ? count / max : 0;
  if (r <= 0.12) return HEAT_ALPHA[1];
  if (r <= 0.32) return HEAT_ALPHA[2];
  if (r <= 0.62) return HEAT_ALPHA[3];
  return HEAT_ALPHA[4];
}

// ── 渲染：热力图 ────────────────────────────────────────────────────────────
function renderHeatmap(host, heat, opts = {}) {
  host.textContent = '';
  const head = el('div', 'gitviz-heat-head');
  const range = Number(opts.days || heat.days.length);
  head.append(
    el('span', 'gitviz-heat-title', '贡献热力图'),
    el('span', 'gitviz-heat-sub', `近 ${range} 天 · 共 ${heat.total} 次提交 · 峰值 ${heat.max}/日 · ${heat.from} → ${heat.to}`),
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

  // 周列 × 7 行（周一…周日）。列数 = ceil(days / 7)，84 天 ⇒ 12 列。
  const first = new Date(`${heat.days[0].date}T00:00:00`);
  const lead = (first.getDay() + 6) % 7;           // 周一 = 0
  const cols = Math.ceil((heat.days.length + lead) / 7);
  const cell = HEAT_CELL;
  const pitch = cell + HEAT_GAP;
  const left = 26;
  const top = 14;
  const w = left + cols * pitch;
  const h = top + 7 * pitch;
  const s = svg('svg', { class: 'gitviz-heat-svg', width: w, height: h, viewBox: `0 0 ${w} ${h}`, role: 'img' });
  s.setAttribute('aria-label', `近 ${heat.days.length} 天每日提交数热力图`);

  const dayLabel = ['一', '三', '五'];
  for (const d of [0, 2, 4]) {
    const t = svg('text', { x: left - 4, y: top + d * pitch + cell - 3, 'text-anchor': 'end', 'font-size': 9, fill: 'var(--color-text-muted)' });
    t.textContent = dayLabel[Math.floor(d / 2)];
    s.appendChild(t);
  }
  // 月首标注（真实日期，不造数）
  let lastMonth = '';
  heat.days.forEach((d, i) => {
    const idx = lead + i;
    const col = Math.floor(idx / 7);
    const row = idx % 7;
    const month = d.date.slice(0, 7);
    if (month !== lastMonth && row === 0) {
      lastMonth = month;
      const t = svg('text', { x: left + col * pitch, y: 9, 'font-size': 9, fill: 'var(--color-text-muted)' });
      t.textContent = `${Number(month.slice(5, 7))}月`;
      s.appendChild(t);
    }
    const r = svg('rect', {
      class: 'gitviz-heat-cell',
      x: left + col * pitch,
      y: top + row * pitch,
      width: cell,
      height: cell,
      fill: `rgb(var(--sapphire) / ${heatAlpha(d.count, heat.max)})`,
    });
    const title = svg('title');
    title.textContent = `${d.date} · ${d.count} 次提交`;
    r.appendChild(title);
    s.appendChild(r);
  });
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

// ── 渲染：泳道树 ────────────────────────────────────────────────────────────
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

  const list = el('ol', 'gitviz-rows');
  const byLane = new Map(g.refs.map((r) => [r.name, r]));
  for (const n of rows) {
    const li = el('li', 'gitviz-row');
    li.tabIndex = 0;
    li.dataset.sha = n.sha;
    if (state.focus?.kind === 'commit' && state.focus.sha === n.sha) li.classList.add('is-selected');
    else if (laneOfRef != null && n.lane === laneOfRef) li.classList.add('is-hot');
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
    const subj = el('span', 'gitviz-subject', n.subject || '(无提交信息)');
    li.appendChild(subj);
    const meta = el('span', 'gitviz-meta', `${n.short} · ${n.date}`);
    li.appendChild(meta);
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
  canvas.appendChild(list);
  host.appendChild(canvas);
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
    const dd = el('dd', mono ? 'mono' : null, v);
    dl.appendChild(dd);
  };
  if (f.kind === 'commit') {
    const n = g.commits.find((c) => c.sha === f.sha);
    if (!n) return;
    add('提交', n.short, true);
    add('时间', new Date(n.ts).toLocaleString('zh-CN', { hour12: false }));
    add('作者', n.author);
    add('泳道', `#${n.lane}`);
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
    ta.placeholder = '给本项的备注（只随转发载荷带走）';
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

/** 转发载荷 → **只收集**（MVP 硬约束：面板永不写仓库、不发任何写请求）。
 *
 *  本函数只做两件不出模块的事：① 一条 console.info 本地日志；② 在 `.gitviz` 上派发
 *  一个 `gitviz-forward` CustomEvent（detail = 载荷）。生产侧到此为止——真正的「转发
 *  给主会话」由 root 侧组 AskUser 卡片承接（设计稿 §六），不在本模块内发起。
 *  dev harness 可以监听该事件把载荷落到本地日志文件（验收证据用）；产品运行面上没有
 *  任何监听者 ⇒ 零网络写面（也是 `check-api-routes` 的硬约束：前端不许凭空多出一条
 *  后端没有承接的路径）。 */
function forward(state, out, rootEl) {
  const items = [...state.selected.values()].map((it) => ({
    kind: it.kind,
    key: it.key,
    name: it.name || '',
    sha: it.sha || '',
    tip: it.tip || '',
    ahead: typeof it.ahead === 'number' ? it.ahead : null,
    behind: typeof it.behind === 'number' ? it.behind : null,
    note: it.note || '',
  }));
  const payload = {
    project: state.project,
    at: new Date().toISOString(),
    repoRoot: state.graph.root,
    graphDigest: state.graph.digest,
    head: state.graph.head,
    items,
  };
  // eslint-disable-next-line no-console
  console.info('[gitviz] forward → root（只收集，不执行）', payload);
  rootEl?.dispatchEvent(new CustomEvent('gitviz-forward', { detail: payload, bubbles: true }));
  out.textContent = `已收集 ${items.length} 项（含 ${items.filter((i) => i.note).length} 条备注）；载荷已落本地日志，未发生任何仓库写入。`;
}

// ── 组装一次视图（数据到达后）──────────────────────────────────────────────
function paint(pane, g) {
  const root = pane.querySelector('.gitviz');
  if (!root) return;
  root.dataset.state = 'ready';
  root.textContent = '';

  const state = {
    project: g.project,
    graph: g,
    focus: null,
    selected: new Map(),
    heatDays: g.heatmap.days.length,
    repaint: null,
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
  const footer = el('div', 'gitviz-forward');
  const btn = el('button', 'gitviz-btn', '转发给主会话');
  btn.type = 'button';
  btn.disabled = true;
  const hint = el('span', 'gitviz-forward-hint', '只收集与展示，不写仓库');
  const out = el('div', 'gitviz-note-io');
  footer.append(btn, hint);
  ctl.append(detail, refs, notes, footer, out);

  const repaint = () => {
    btn.disabled = state.selected.size === 0;
    renderTree(tree, g, state);
    renderDetail(detail, g, state);
    renderRefs(refs, g, state);
    renderNotes(notes, state);
  };
  state.repaint = repaint;
  btn.addEventListener('click', () => forward(state, out, root));

  // 热力图区间档切换（近 12 周 ⇄ 近 12 月）：只补拉热力图这一段读数（小窗请求，
  // 不重取整张图），渲染只动热力区块。零写请求、零仓库写入。
  const refreshHeat = async (days) => {
    if (days === state.heatDays) return;
    state.heatDays = days;
    const next = await fetchGitGraph(state.project, { heatDays: days, limit: 20, lanes: 2 }).catch(() => null);
    if (!next || !next.heatmap) return;
    state.graph.heatmap = next.heatmap;
    renderHeatmap(heat, next.heatmap, { days, onRange: refreshHeat });
  };
  renderHeatmap(heat, g.heatmap, { days: state.heatDays, onRange: refreshHeat });
  main.append(tree, ctl);
  root.append(heat, main);
  repaint();

  // 初次进入：默认聚焦 HEAD 的提交行（有内容可见，不是空详情）
  const headNode = g.commits.find((c) => c.sha === g.head.sha);
  if (headNode) {
    state.focus = { kind: 'commit', sha: headNode.sha };
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
  const backPath = svg('path', { d: 'M19 12H5M12 19l-7-7 7-7' });
  backIcon.appendChild(backPath);
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

  back.addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof opts.onBack === 'function') opts.onBack();
  });

  const graph = opts.data || (await loadGraph(projectName, root));
  if (!graph) return;
  paint(pane, graph);
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
    const g = await fetchGitGraph(projectName, { heatDays: HEAT_DAYS_DEFAULT });
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
