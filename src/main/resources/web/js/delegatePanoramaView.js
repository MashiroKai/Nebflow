// delegatePanoramaView.js — delegate 全景视图（delegate 视图重设计实施批 ·
// 2026-10-21，设计已过审 · 作者授权代审）。
//
// 定位（设计说明 §0/§2/§3/§4，样张 = delegate-view-redesign-mockup.html）：
//   按项目分组的**无边 DAG 全景**——一项目一带，带内六泳道（派发｜运行｜候合并｜
//   合并中｜完成｜汇点）讲 delegate 的旅程；下区 = 子过程真实拓扑（agentflow 步骤
//   链 / subagent 星扇），与 delegate 垂直锚定；带头右侧 = 批次推进带。
//   「候合并 / 合并中」三重编码（设计 §3）：连线（琥珀虚线 / 蓝晶行军蚁）+ 状态
//   徽标（合并队列 #n/N / 合并窗持有）+ 卡色环（琥珀 / 蓝晶）——**状态词恒中性字，
//   语义色只上点/环/徽标/字形**（铁律⑤）。
//
// 数据面（入口统一 · 作者令⑤）：与任务列表同一读取口（delegateChainStub read
// port → delegateChainProvider REST /api/delegates 镜像）；行载荷 = provider 加宽
// 投影（taskStatus 含 G1 `merging` 一等态 / mergeQueue / mergeQueuePos / topology）。
// 🔴 前端零派生：merging 判据 = 引擎 taskStatus 原值；位次 = mergeQueuePos 原值
// （缺键 = 不渲染数字，mergeQueueView 降级红线同款）；子过程 = topology 原值。
//
// 入口：任务列表点 delegate 行（openDelegatePanorama(project, {focus})）/ 项目面板
// 项目卡标题点按（openDelegatePanorama(project)）/ 「项目流程图」圆钮（全量）——
// 同一组件同一数据面，仅入参不同。卡点按 = L1 该 delegate 流程图（既有 fm-detail
// 层动态 import——静态零环）。
//
// 视觉：全部生产 token（base.css / sapphire.css），零新色值；样式落
// css/delegatePanorama.css（ensurePanoramaCss 幂等注入不需要——<link> 随
// index.html 装载；此兜底仅防模块级单测环境无 link）。

import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { listDelegateRows, getChainByTask, listDelegateChildren } from './delegateChainStub.js';
import { openPanoramaPane, attachStructureArchive } from './delegateChainView.js';

/** 视图体滚动容器选择器（与 delegateChainView.BODY_SEL 同一骨架）。 */
const BODY_SEL = '.delegate-chain-view-body';

// ── 布局几何（样张 §6 度量；纯度量非色值）──────────────────────────────────
const COLS = {
  root: { x: 12, w: 156 },
  run: { x: 196, w: 204 },
  rtm: { x: 662, w: 192 },
  merge: { x: 874, w: 166 },
  done: { x: 1058, w: 158 },
  sink: { x: 1232, w: 96 },
};
const SEPS = [180, 650, 866, 1046, 1228]; // 分列线 x（极淡 --glass-border）
const CANVAS_W = 1336; // 画布宽（min-width，窄视口横向滚动——设计 §7）
const TOP_Y = 40; // 首卡 y（列头小字带 26px 之下）
const LANE_GAP = 22; // 泳道内卡纵距
const H_CARD = 88; // 委托卡标称高
const H_CARD_REPORT = 116; // 含报告条卡高
const H_ROOT = 64; // 任务根幽卡高
const H_SINK = 64; // 汇点幽卡高
const H_SUB = 48; // 子过程卡高
const SUB_ROW_GAP = 20; // 子过程卡纵距（agentflow 链）
const ZONE_SEP_PAD = 18; // 泳道底 → 分区线
const SUB_ZONE_TOP_PAD = 46; // 分区线 → 子过程首卡
const CANVAS_BOTTOM_PAD = 16;

// 泳道拥挤阈值（设计 §7）：>12 收起子过程区（带头翻钮）；>24 终态泳道折叠为计数块。
const SUB_COLLAPSE_ROWS = 12;
const DONE_COLLAPSE_ROWS = 24;

// ── 状态映射（设计 §3 表 · 单点）───────────────────────────────────────────
/** 委托全景状态：G1 `merging` 一等态与 `restarting` = 引擎 taskStatus 原值（行
 *  status 推导不含这两个态——rowFromRegistry 的 ENGINE_TERMINAL 值域外）；其余沿
 *  rowFromRegistry 行状态（引擎终态权威 / 判词 / 活性提示）。 */
function panoStatus(r) {
  const ts = r && r.taskStatus;
  if (ts === 'merging' || ts === 'restarting') return ts;
  return String((r && r.status) || 'running');
}

/** 子过程拓扑（双臂）：新引擎行内 `topology` 载荷优先（/api/delegates 批量面）；
 *  旧引擎兼容臂 = 既有镜像读口组合——steps = /api/chains/:key chain.nodes 的
 *  flow-step 集（provider 已逐键镜像），children = 星型子代理读口（provider 已逐父
 *  镜像）。两臂同数据面（stub 读取口），零新请求、零第二权威（状态值原样透传）。 */
function topologyOf(r) {
  if (r && r.topology && (r.topology.steps.length || r.topology.children.length)) return r.topology;
  const id = String((r && r.delegateId) || '');
  const key = String((r && (r.taskNo || r.delegateId)) || '');
  const chain = key ? getChainByTask(key) : null;
  const steps = (chain && Array.isArray(chain.nodes) ? chain.nodes : [])
    .filter((n) => n && n.kind === 'flow-step')
    .map((n) => ({
      id: String(n.id || ''),
      label: String(n.label || n.name || ''),
      status: String(n.status || ''),
      deps: Array.isArray(n.deps) ? n.deps.map((d) => String(d || '')) : [],
      description: String(n.description || ''),
    }));
  const kids = id && !(r && r.external)
    ? listDelegateChildren(id).map((c) => ({
      taskId: String(c.taskId || ''),
      agentName: String(c.agentName || ''),
      description: String(c.description || ''),
      status: String(c.status || ''),
      spawnedAt: typeof c.spawnedAt === 'number' ? c.spawnedAt : 0,
      completedAt: null,
    }))
    : [];
  return { steps, children: kids };
}

const PANO_ST = {
  running: { word: 'task.inProgressShort', cls: 'st-run' },
  restarting: { word: 'delegate.view.restart', cls: 'st-restart' },
  'ready-to-merge': { word: 'delegate.readyToMerge', cls: 'st-rtm' },
  merging: { word: 'delegate.view.merging', cls: 'st-merge' },
  completed: { word: 'flowmap.done', cls: 'st-done' },
  failed: { word: 'flowmap.fail', cls: 'st-fail' },
  cancelled: { word: 'flowmap.st.cancelled', cls: 'st-cancel' },
  // 重启对账批 2026-10-22：已中断灰态（灰卡降透明 + 琥珀点——禁借 fail 红/done 绿）。
  interrupted: { word: 'task.nodeInterrupted', cls: 'st-stop' },
};

/** 子过程节点状态 → 卡点/边态（样张「子态定边态」：completed=已投递蓝晶实线、
 *  running=行军蚁、其余=灰实线）。 */
function subState(st) {
  if (st === 'completed') return 'del';
  if (st === 'running' || st === 'restarting') return 'inflight';
  return 'idle';
}

// ── 视图态（内存；无 URL 路由——pane 记忆沿用 openPane dataset，深链不做）─────
const UI = {
  filter: 'all', // 'all' | 项目名（pills；点项目 = 该项目全景，同组件收窄）
  subOpen: new Map(), // 项目名 → bool（>12 行带头「子过程」翻钮态；缺省折叠）
  doneOpen: new Set(), // 项目名（>24 行终态泳道展开态；缺省计数块）
};

/**
 * 打开 delegate 全景（唯一入口缝；任务列表行 / 项目面板卡 / 「项目流程图」圆钮
 * 三入口同路）。
 * @param {string} [projectFilter] 项目名（'' = 全量全景；点项目 = 该项目全景）
 * @param {{focus?: string}} [opts] focus = 落位高亮的 delegateId（任务列表行入口）
 */
export function openDelegatePanorama(projectFilter, opts = {}) {
  const filter = String(projectFilter || '');
  UI.filter = filter || 'all';
  const pane = openPanoramaPane(filter, t('delegate.view.title'));
  if (!pane) return;
  renderPanoramaBody(pane, { focus: String((opts && opts.focus) || '') });
}

/**
 * 渲染全景视图体（rerenderDelegateChainPane 分流挂钩同缝；镜像刷新重渲 ——
 * 数据权威恒在 REST 镜像，本件零 WS 直连）。
 */
export function renderPanoramaBody(pane, opts = {}) {
  const body = pane.querySelector(BODY_SEL);
  if (!body) return;
  ensurePanoramaCss();
  const rows = listDelegateRows();
  const panoRows = rows.filter((r) => r && r.archivedAt == null); // 历史窗不入全景
  body.innerHTML = '';
  const root = document.createElement('div');
  root.className = 'dvp-root';
  root.dataset.view = 'delegate-panorama';
  root.dataset.filter = UI.filter;
  root.appendChild(buildToolbar(panoRows));
  if (!panoRows.length) {
    const empty = document.createElement('div');
    empty.className = 'dvp-empty';
    empty.textContent = t('delegate.view.emptyGlobal'); // 全局零委托 → 单空态（§7）
    root.appendChild(empty);
  } else {
    const main = document.createElement('div');
    main.className = 'dvp-main';
    for (const band of buildBands(panoRows)) main.appendChild(band);
    root.appendChild(main);
  }
  root.appendChild(buildLegend());
  body.appendChild(root);
  attachStructureArchive(body); // 归档面随全景续挂（唯一视图体同一挂点）
  applyFilter(root);
  const focusId = String((opts && opts.focus) || '');
  if (focusId) {
    const card = root.querySelector(`[data-delegate-id="${cssEscape(focusId)}"]`);
    if (card) {
      card.classList.add('dvp-focus');
      card.scrollIntoView({ block: 'center', inline: 'nearest' });
      setTimeout(() => card.classList.remove('dvp-focus'), 2400);
    }
  }
}

/** 项目过滤 pills 落 DOM（同组件按 project 参数收窄——body[data-filter] 同款）。 */
function applyFilter(root) {
  const filter = root.dataset.filter || 'all';
  root.querySelectorAll('.dvp-band').forEach((b) => {
    b.hidden = filter !== 'all' && b.dataset.project !== filter;
  });
  root.querySelectorAll('.dvp-pill').forEach((p) => {
    p.setAttribute('aria-pressed', String(p.dataset.p === filter));
  });
}

// ── 顶栏工具行（全局计数 + 项目过滤 pills + 入口统一徽标）─────────────────────
function buildToolbar(rows) {
  const bar = document.createElement('div');
  bar.className = 'dvp-toolbar';
  const projects = new Set(rows.map((r) => String(r.project || '')));
  const c = { run: 0, rtm: 0, merge: 0 };
  for (const r of rows) {
    const st = panoStatus(r);
    if (st === 'running' || st === 'restarting') c.run += 1;
    else if (st === 'ready-to-merge') c.rtm += 1;
    else if (st === 'merging') c.merge += 1;
  }
  const stats = document.createElement('span');
  stats.className = 'dvp-stats';
  stats.textContent = t('delegate.view.stats', {
    projects: String(projects.size),
    delegates: String(rows.length),
    active: String(c.run + c.merge),
    rtm: String(c.rtm),
    win: String(c.merge),
  });
  bar.appendChild(stats);
  const pills = document.createElement('nav');
  pills.className = 'dvp-pills';
  pills.setAttribute('aria-label', t('delegate.view.title'));
  const mk = (val, label) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'dvp-pill';
    b.dataset.p = val;
    b.textContent = label;
    b.addEventListener('click', () => {
      UI.filter = val;
      const root = bar.closest('.dvp-root');
      if (root) {
        root.dataset.filter = val;
        applyFilter(root);
      }
    });
    return b;
  };
  pills.appendChild(mk('all', t('delegate.view.filterAll')));
  for (const name of projects) pills.appendChild(mk(name, projectLabel(name)));
  bar.appendChild(pills);
  const entry = document.createElement('span');
  entry.className = 'dvp-entry';
  entry.title = t('delegate.view.entryBadge');
  const k1 = document.createElement('kbd');
  k1.textContent = t('delegate.view.back');
  const k2 = document.createElement('kbd');
  k2.textContent = t('project.title');
  entry.append(t('delegate.view.entryBadge'), ' ', k1, '＝', k2);
  return bar;
}

/** 项目带名（'' = 缺省座位——以工作区尾词作带名，数据原文非臆造）。 */
function projectLabel(name) {
  return name || '·';
}

// ── 项目带组装 ────────────────────────────────────────────────────────────────

/** 带活跃度时间（排序键：最新 readyAt/判词/创建——设计 §2 排序）。 */
function bandTs(r) {
  const cand = [
    typeof r.createdAt === 'number' ? r.createdAt : 0,
    r.mergeQueuePos && typeof r.mergeQueuePos.readyAt === 'number' ? r.mergeQueuePos.readyAt : 0,
    r.verdict && typeof r.verdict.at === 'number' ? r.verdict.at : 0,
  ];
  return Math.max(...cand);
}

function buildBands(rows) {
  const byProject = new Map();
  for (const r of rows) {
    const name = String(r.project || '');
    if (!byProject.has(name)) byProject.set(name, []);
    byProject.get(name).push(r);
  }
  const bands = [...byProject.entries()].map(([name, rs]) => ({
    name,
    rows: rs,
    latest: Math.max(...rs.map(bandTs)),
  }));
  bands.sort((a, b) => b.latest - a.latest); // 最新活跃度倒序（设计 §2）
  return bands.map((b) => buildBand(b.name, b.rows));
}

/** 单项目带：带头（点/名/工作区/计数 chips/推进带/N 委托）+ 泳道画布 + 子过程区。 */
function buildBand(name, rows) {
  const band = document.createElement('section');
  band.className = 'dvp-band';
  band.dataset.project = name;
  band.setAttribute('aria-label', t('delegate.view.ariaBand', { name: projectLabel(name) }));

  const lanes = { run: [], rtm: [], merge: [], done: [] };
  for (const r of rows) {
    const st = panoStatus(r);
    if (st === 'running' || st === 'restarting') lanes.run.push(r);
    else if (st === 'ready-to-merge') lanes.rtm.push(r);
    else if (st === 'merging') lanes.merge.push(r);
    else lanes.done.push(r);
  }
  // 带内序：候合并/合并中按队列位次（引擎 FIFO 序透传），其余按活跃时间。
  const byPos = (a, b) => qpos(a) - qpos(b) || bandTs(b) - bandTs(a);
  lanes.rtm.sort(byPos);
  lanes.merge.sort(byPos);
  lanes.run.sort((a, b) => bandTs(b) - bandTs(a));
  lanes.done.sort((a, b) => bandTs(b) - bandTs(a));

  band.appendChild(buildBandHead(name, rows, lanes));
  band.appendChild(buildCanvas(name, rows, lanes));
  return band;
}

function qpos(r) {
  const p = r && r.mergeQueuePos && typeof r.mergeQueuePos.position === 'number' ? r.mergeQueuePos.position : 0;
  return p > 0 ? p : 9e9;
}

function buildBandHead(name, rows, lanes) {
  const head = document.createElement('div');
  head.className = 'dvp-band-head';
  const dot = document.createElement('span');
  dot.className = 'dvp-band-dot';
  dot.setAttribute('aria-hidden', 'true');
  const nm = document.createElement('span');
  nm.className = 'dvp-band-name';
  nm.textContent = projectLabel(name);
  head.append(dot, nm);
  const ws = firstNonEmpty(rows.map((r) => r.projectWorkspace || r.worktreePath || ''));
  if (ws) {
    const chip = document.createElement('span');
    chip.className = 'dvp-band-ws';
    chip.textContent = shortPath(ws);
    chip.title = ws;
    head.appendChild(chip);
  }
  const counts = document.createElement('div');
  counts.className = 'dvp-band-counts';
  const cnt = (label, n, tone) => {
    if (!n) return;
    const s = document.createElement('span');
    s.className = 'dvp-cnt';
    const i = document.createElement('i');
    if (tone) i.className = tone;
    i.textContent = String(n);
    s.append(`${label} `, i);
    counts.appendChild(s);
  };
  cnt(t('delegate.view.lane.running'), lanes.run.length, 'run');
  cnt(t('delegate.view.lane.rtm'), lanes.rtm.length, 'rtm');
  cnt(t('delegate.view.lane.merging'), lanes.merge.length, 'mrg');
  cnt(t('delegate.view.lane.done'), lanes.done.length, 'end');
  head.appendChild(counts);
  head.appendChild(Object.assign(document.createElement('span'), { className: 'dvp-band-spacer' }));
  head.appendChild(buildRibbon(lanes));
  const n = document.createElement('span');
  n.className = 'dvp-band-n';
  n.textContent = t('delegate.view.nDelegates', { n: String(rows.length) });
  head.appendChild(n);
  return head;
}

/** 批次推进带（带头右侧；cur = 合并管线活批次位置——设计 §3）：合并窗在飞 = 合并中；
 *  候合并排队 = 候合并；无活批次而已有并入 = 完成（历史批次收口）；否则运行/派发。 */
function buildRibbon(lanes) {
  let stage = 0; // 0=派发 1=运行 2=候合并 3=合并中 4=完成
  if (lanes.merge.length) stage = 3;
  else if (lanes.rtm.length) stage = 2;
  else if (lanes.done.some((r) => panoStatus(r) === 'completed')) stage = 4;
  else if (lanes.run.length) stage = 1;
  const words = ['lane.dispatch', 'lane.running', 'lane.rtm', 'lane.merging', 'lane.done'].map(
    (k) => t(`delegate.view.${k}`),
  );
  const rb = document.createElement('div');
  rb.className = 'dvp-ribbon';
  rb.setAttribute('aria-label', t('delegate.view.ribbon'));
  words.forEach((w, i) => {
    const st = document.createElement('span');
    st.className = `dvp-rb-st${i < stage ? ' past' : ''}${i === stage ? ' cur' : ''}`;
    const d = document.createElement('span');
    d.className = 'dvp-rb-dot';
    const word = document.createElement('span');
    word.className = 'dvp-rb-word';
    word.textContent = w;
    st.append(d, word);
    rb.appendChild(st);
    if (i < words.length - 1) rb.appendChild(Object.assign(document.createElement('span'), { className: 'dvp-rb-line' }));
  });
  return rb;
}

// ── 泳道画布（无边 DAG：绝对定位卡 + SVG 下垫层）─────────────────────────────

function buildCanvas(name, rows, lanes) {
  const canvas = document.createElement('div');
  canvas.className = 'dvp-canvas';
  const doneCollapsed = rows.length > DONE_COLLAPSE_ROWS && !UI.doneOpen.has(name);
  const subCollapsed = rows.length > SUB_COLLAPSE_ROWS && !UI.subOpen.has(name);

  // 列头 + 分列线（无边原则：仅极淡分列线 + 9.5px 小字）。
  const laneXs = [
    ['lane.dispatch', COLS.root.x],
    ['lane.running', COLS.run.x],
    ['lane.rtm', COLS.rtm.x],
    ['lane.merging', COLS.merge.x],
    ['lane.done', COLS.done.x],
    ['lane.sink', COLS.sink.x],
  ];
  for (const [key, x] of laneXs) {
    const lb = document.createElement('span');
    lb.className = 'dvp-lane-label';
    lb.style.left = `${x}px`;
    lb.textContent = t(`delegate.view.${key}`);
    canvas.appendChild(lb);
  }
  for (const x of SEPS) {
    const sep = document.createElement('span');
    sep.className = 'dvp-lane-sep';
    sep.style.left = `${x}px`;
    canvas.appendChild(sep);
  }

  // 卡布局（列内纵栈）。geometry = 卡的 {x,y,w,h}（连线端点计算单源）。
  const geo = new Map(); // delegateId → {x,y,w,h}
  const stackBottom = (list, col, hOf) => {
    let y = TOP_Y;
    for (const r of list) {
      const h = hOf(r);
      geo.set(String(r.delegateId || ''), { x: col.x, y, w: col.w, h });
      y += h + LANE_GAP;
    }
    return list.length ? y - LANE_GAP : TOP_Y;
  };
  // 折叠臂（设计 §7）：终态列几何照建（虚位——展开复用 + 连线端点对齐），但
  // laneBottom 按计数块（56px）收——折叠即回收竖向空间，画布不为幻影栈留白。
  let laneBottom = Math.max(
    stackBottom(lanes.run, COLS.run, () => H_CARD),
    stackBottom(lanes.rtm, COLS.rtm, (r) => (reportText(r) ? H_CARD_REPORT : H_CARD)),
    stackBottom(lanes.merge, COLS.merge, () => H_CARD),
    doneCollapsed && lanes.done.length
      ? TOP_Y + 56
      : stackBottom(lanes.done, COLS.done, (r) => (reportText(r) ? H_CARD_REPORT : H_CARD)),
  );

  // 汇点 y：候合并/合并中列的视觉中心（合并管线的对岸），空则画布居中。
  const pipeline = [...lanes.merge, ...lanes.rtm, ...lanes.done];
  let sinkY = TOP_Y + 80;
  if (pipeline.length) {
    const g = geo.get(String(pipeline[0].delegateId || ''));
    if (g) sinkY = Math.max(TOP_Y, g.y + Math.min(g.h, 120) / 2 - H_SINK / 2);
  }
  const sinkGeo = { x: COLS.sink.x, y: sinkY, w: COLS.sink.w, h: H_SINK };
  laneBottom = Math.max(laneBottom, sinkY + H_SINK);

  // 子过程区（下区锚定布局）。
  const topoRows = rows.filter((r) => { const tp = topologyOf(r); return tp.steps.length || tp.children.length; });
  const subOpen = !subCollapsed;
  let zoneTop = 0;
  let canvasH = laneBottom + CANVAS_BOTTOM_PAD;
  const subGeo = new Map(); // 子卡 key → geo（连线端点）
  const subSpecs = new Map(); // 子卡 key → {kind, idx, item}（卡内容单源）
  let groups = [];
  if (topoRows.length) {
    zoneTop = laneBottom + ZONE_SEP_PAD;
    const sep = document.createElement('span');
    sep.className = 'dvp-zone-sep';
    sep.style.top = `${zoneTop}px`;
    canvas.appendChild(sep);
    const tag = document.createElement('span');
    tag.className = 'dvp-zone-tag';
    tag.style.top = `${zoneTop + 10}px`;
    tag.textContent = t('delegate.view.subzone');
    canvas.appendChild(tag);
    if (rows.length > SUB_COLLAPSE_ROWS) {
      const tgl = document.createElement('button');
      tgl.type = 'button';
      tgl.className = 'dvp-zone-toggle';
      tgl.style.top = `${zoneTop + 8}px`;
      tgl.setAttribute('aria-expanded', String(subOpen));
      tgl.textContent = `${t('delegate.view.subzone')} ${topoRows.length}${subCollapsed ? ' ›' : ' ‹'}`;
      tgl.addEventListener('click', () => {
        UI.subOpen.set(name, !subOpen);
        const pane = canvas.closest('.canvas-tab-pane');
        if (pane) renderPanoramaBody(pane);
      });
      canvas.appendChild(tgl);
    }
    const cardsTop = zoneTop + SUB_ZONE_TOP_PAD;
    let cursorX = COLS.run.x;
    let maxBottom = cardsTop;
    if (subOpen) {
      for (const r of topoRows) {
        const dg = geo.get(String(r.delegateId || ''));
        const anchorX = dg ? dg.x : cursorX;
        if (topologyOf(r).steps.length) {
          // agentflow 链：纵向步骤卡（编号徽标），锚定 delegate 左缘。
          let y = cardsTop;
          topologyOf(r).steps.forEach((s, i) => {
            const k = stepKey(r, i);
            subGeo.set(k, { x: anchorX, y, w: 190, h: H_SUB });
            subSpecs.set(k, { kind: 'chain', idx: i, item: s });
            y += H_SUB + SUB_ROW_GAP;
          });
          const gw = 190;
          groups.push({ row: r, kind: 'chain', x: anchorX, y: cardsTop, w: gw, h: y - SUB_ROW_GAP - cardsTop });
          cursorX = anchorX + gw;
          maxBottom = Math.max(maxBottom, y - SUB_ROW_GAP);
        } else {
          // subagent 星扇：横向子卡。
          const kids = topologyOf(r).children;
          const pitch = 170;
          let y = cardsTop;
          kids.forEach((c, i) => {
            const k = starKey(r, i);
            subGeo.set(k, { x: anchorX + i * pitch, y, w: 150, h: H_SUB });
            subSpecs.set(k, { kind: 'star', idx: i, item: c });
          });
          const gw = Math.max(150, kids.length * pitch - 20);
          groups.push({ row: r, kind: 'star', x: anchorX, y: cardsTop, w: gw, h: H_SUB });
          cursorX = anchorX + gw;
          maxBottom = Math.max(maxBottom, y + H_SUB);
        }
        cursorX += 40;
      }
      canvasH = maxBottom + CANVAS_BOTTOM_PAD;
    } else {
      canvasH = zoneTop + 34;
    }
  }

  canvas.style.height = `${canvasH}px`;
  canvas.style.minWidth = `${CANVAS_W}px`;
  canvas.setAttribute('role', 'img');
  canvas.setAttribute(
    'aria-label',
    t('delegate.view.ariaCanvas', {
      name: projectLabel(name),
      summary: `${t('delegate.view.lane.running')} ${lanes.run.length} · ${t('delegate.view.lane.rtm')} ${lanes.rtm.length} · ${t('delegate.view.lane.merging')} ${lanes.merge.length} · ${t('delegate.view.lane.done')} ${lanes.done.length}`,
    }),
  );

  // 任务根幽卡（按台账号分组；几何先建，派发边从它出发）。
  const roots = buildRoots(rows, canvas);

  // SVG 连线下垫层（pointer-events:none，z 序在卡下）；贴线 chip 随后落（DOM 层）。
  const chips = [];
  const svg = buildEdges({ geo, sinkGeo, subGeo, groups, lanes, doneCollapsed, roots, w: CANVAS_W, h: canvasH, chips });
  canvas.appendChild(svg);
  for (const c of chips) {
    const el = document.createElement('span');
    el.className = `dvp-e-chip${c.cls ? ` ${c.cls}` : ''}`;
    el.style.left = `${Math.round(c.x)}px`;
    el.style.top = `${Math.round(c.y) - 9}px`;
    el.textContent = c.text;
    canvas.appendChild(el);
  }

  // 卡 DOM（绝对定位；落 SVG 之上）。
  for (const [id, g] of geo) {
    const r = rows.find((x) => String(x.delegateId || '') === id);
    if (r && !(doneCollapsed && lanes.done.includes(r))) canvas.appendChild(delegateCard(r, g, name, rows));
  }
  for (const [key, g] of subGeo) {
    const spec = subSpecs.get(key);
    if (spec) canvas.appendChild(subCardEl(spec.kind, spec.idx, spec.item, g));
  }
  canvas.appendChild(sinkCard(sinkGeo, lanes));

  // 拥挤折叠：终态泳道计数块（>24 行）。
  if (doneCollapsed && lanes.done.length) {
    const blk = document.createElement('button');
    blk.type = 'button';
    blk.className = 'dvp-nd dvp-ghost dvp-done-block';
    blk.style.left = `${COLS.done.x}px`;
    blk.style.top = `${TOP_Y}px`;
    blk.style.width = `${COLS.done.w}px`;
    blk.style.height = '56px';
    blk.textContent = `${t('delegate.view.lane.done')} ×${lanes.done.length}`;
    blk.addEventListener('click', () => {
      UI.doneOpen.add(name);
      const pane = canvas.closest('.canvas-tab-pane');
      if (pane) renderPanoramaBody(pane);
    });
    canvas.appendChild(blk);
  }

  // 分列线高度 = 泳道区（下区之上）。
  const laneZoneH = Math.max(120, (zoneTop || canvasH) - 26 - 14);
  canvas.querySelectorAll('.dvp-lane-sep').forEach((s) => {
    s.style.height = `${laneZoneH}px`;
  });
  return canvas;
}

const stepKey = (r, i) => `chain:${r.delegateId}:${i}`;
const starKey = (r, i) => `star:${r.delegateId}:${i}`;

function firstNonEmpty(arr) {
  for (const v of arr) if (v) return v;
  return '';
}

/** 工作区路径短显（/Users/<name> → ~；纯显示）。 */
function shortPath(p) {
  const home = typeof window !== 'undefined' && window.NF_HOME ? window.NF_HOME : null;
  if (home && p.startsWith(home)) return `~${p.slice(home.length)}`;
  return p;
}

// ── 任务根幽卡 ───────────────────────────────────────────────────────────────
/** 返回 root 组 [{taskNo,title,count,geo}]；并落 DOM。 */
function buildRoots(rows, canvas) {
  const byTask = new Map();
  for (const r of rows) {
    const no = String(r.taskNo || '');
    if (!no) continue;
    if (!byTask.has(no)) byTask.set(no, { taskNo: no, title: String(r.title || ''), count: 0, latest: 0 });
    const g = byTask.get(no);
    g.count += 1;
    g.latest = Math.max(g.latest, bandTs(r));
  }
  const groups = [...byTask.values()].sort((a, b) => b.latest - a.latest).slice(0, 3); // 最多 3 根（防拥挤）
  let y = TOP_Y;
  const out = [];
  for (const g of groups) {
    const geoBox = { x: COLS.root.x, y, w: COLS.root.w, h: H_ROOT };
    g.geo = geoBox;
    out.push(g);
    const el = document.createElement('div');
    el.className = 'dvp-nd dvp-ghost dvp-root-card';
    el.style.cssText = `left:${geoBox.x}px;top:${geoBox.y}px;width:${geoBox.w}px;height:${geoBox.h}px`;
    el.setAttribute('role', 'img');
    el.setAttribute(
      'aria-label',
      `#${g.taskNo} ${g.title} ×${g.count}`,
    );
    const top = document.createElement('div');
    top.className = 'dvp-task-top';
    const c1 = document.createElement('span');
    c1.className = 'dvp-chip';
    c1.textContent = `#${g.taskNo}`;
    const c2 = document.createElement('span');
    c2.className = 'dvp-chip';
    c2.textContent = `×${g.count}`;
    top.append(c1, c2);
    const title = document.createElement('div');
    title.className = 'dvp-task-title';
    title.textContent = g.title;
    title.title = g.title;
    el.append(top, title);
    canvas.appendChild(el);
    y += H_ROOT + LANE_GAP;
  }
  return out;
}

// ── 卡渲染 ───────────────────────────────────────────────────────────────────

function reportText(r) {
  const rd = String(r.reportDetail || '');
  if (rd) return rd;
  const v = r.verdict;
  return v && typeof v === 'object' ? String(v.reason || '') : '';
}

function modeChip(r) {
  const tp = topologyOf(r);
  if (!tp) return '';
  if (tp.steps.length) return `agentflow ·${tp.steps.length}步`;
  if (tp.children.length) return `subagent ·${tp.children.length}`;
  return '';
}

function delegateCard(r, g, bandName, rows) {
  const st = panoStatus(r);
  const meta = PANO_ST[st] || PANO_ST.running;
  const el = document.createElement('div');
  el.className = `dvp-nd ${meta.cls}`;
  el.style.cssText = `left:${g.x}px;top:${g.y}px;width:${g.w}px;height:${g.h}px`;
  el.tabIndex = 0;
  el.dataset.delegateId = String(r.delegateId || '');
  el.dataset.status = st;
  const rep = reportText(r);
  el.setAttribute(
    'aria-label',
    `${cardTitle(r)} — ${t(meta.word)}${st === 'ready-to-merge' ? queueSuffix(r) : ''}${st === 'merging' ? ` — ${t('delegate.view.windowHold')}` : ''}`,
  );
  // head
  const head = document.createElement('div');
  head.className = 'dvp-head';
  const exec = document.createElement('span');
  exec.className = 'dvp-exec';
  exec.textContent = String(r.executor || '?').slice(0, 1).toUpperCase();
  const title = document.createElement('span');
  title.className = 'dvp-title';
  title.textContent = cardTitle(r);
  title.title = cardTitle(r);
  const stw = document.createElement('span');
  stw.className = 'dvp-st';
  const dot = document.createElement('i');
  dot.className = 'dvp-st-dot';
  const word = document.createElement('span');
  word.className = 'dvp-st-word';
  word.textContent = t(meta.word);
  stw.append(dot, word);
  head.append(exec, title, stw);
  el.appendChild(head);
  // 队列/合并窗徽标（卡顶外沿；状态徽标三件套之一——位次=引擎 mergeQueuePos 原值，
  // 缺键不渲染数字（降级红线））。
  if (st === 'ready-to-merge') {
    const q = r.mergeQueuePos;
    if (q && Number.isInteger(q.position) && q.position > 0 && Number.isInteger(q.total) && q.total >= q.position) {
      const b = document.createElement('span');
      b.className = 'dvp-qbadge rtm';
      b.textContent = t('delegate.view.queueBadge', { n: String(q.position), m: String(q.total) });
      b.title = `${String(q.branch || '')} → ${String(q.baseBranch || '')}`;
      el.appendChild(b);
    }
  } else if (st === 'merging') {
    const b = document.createElement('span');
    b.className = 'dvp-qbadge mrg';
    b.textContent = t('delegate.view.windowHold');
    el.appendChild(b);
  } else if (st === 'interrupted') {
    // 重启对账批 2026-10-22：打断卡顶徽标（琥珀警示——与队列徽标同位同语言）。
    const b = document.createElement('span');
    b.className = 'dvp-qbadge rtm';
    b.textContent = t('task.restartBadge');
    el.appendChild(b);
  }
  // meta chips（≤3，防溢出；长文本 ellipsis——案例库教训）。
  const metaRow = document.createElement('div');
  metaRow.className = 'dvp-meta';
  const chip = (text, cls) => {
    if (!text) return;
    const c = document.createElement('span');
    c.className = `dvp-chip${cls ? ` ${cls}` : ''}`;
    c.textContent = text;
    if (cls === 'branch') c.title = text;
    metaRow.appendChild(c);
  };
  if (st === 'running' || st === 'restarting') {
    const wt = r.worktreePath ? `wt:${basename(r.worktreePath)}` : '';
    chip(wt);
    chip(modeChip(r), 'mode');
    chip(st === 'restarting' ? '↻' : ago(r.createdAt), 'time');
  } else if (st === 'ready-to-merge' || st === 'merging') {
    chip(r.branch || '', 'branch');
    chip(r.baseBranch ? `←${r.baseBranch}` : '');
    chip(ago(r.mergeQueuePos && r.mergeQueuePos.readyAt ? r.mergeQueuePos.readyAt : r.createdAt), 'time');
  } else {
    chip(r.branch ? r.branch.replace(/^delegate\//, '') : '', 'branch');
    chip(ago(r.verdict && r.verdict.at ? r.verdict.at : r.createdAt), 'time');
  }
  // 重启对账批 2026-10-22：接续关系 chip（虚线 muted——「已由 #N 接续」；接续者
  // 的台账号从同带行集查，查无 = 裸 id 降级。全景 = 审计面：卡不折走，关系随身）。
  if (st === 'interrupted' && String(r.continuedBy || '')) {
    const succ = String(r.continuedBy);
    const succRow = Array.isArray(rows)
      ? rows.find((x) => String(x && x.delegateId || '') === succ)
      : null;
    chip(t('task.continuedBy', { session: succRow && succRow.taskNo ? `#${succRow.taskNo}` : succ }), 'continued');
  }
  el.appendChild(metaRow);
  // 报告条（verdict/reportStatus 在场；失败卡红染——样式类承担）。
  if (rep && g.h >= H_CARD_REPORT) {
    const bar = document.createElement('div');
    bar.className = 'dvp-report';
    const b = document.createElement('b');
    b.textContent = t('delegate.view.report');
    const txt = document.createElement('span');
    txt.textContent = ` ${rep}`;
    bar.append(b, txt);
    bar.title = rep;
    el.appendChild(bar);
  }
  // 卡点按 = L1 该 delegate 流程图（既有 fm-detail 层；动态 import 零静态环）。
  el.addEventListener('click', (e) => {
    e.stopPropagation();
    import('./delegateChainView.js')
      .then(({ openDelegateChainTask }) =>
        openDelegateChainTask(String(r.taskNo || r.delegateId || ''), {
          taskNo: r.taskNo, delegateId: r.delegateId, title: r.title,
          status: r.status, executor: r.executor, project: r.project || bandName,
        }))
      .catch(() => {});
  });
  el.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      el.click();
    }
  });
  return el;
}

function cardTitle(r) {
  const no = r.taskNo ? `#${r.taskNo} ` : '';
  return `${no}${String(r.title || r.delegateId || '')}`;
}

function queueSuffix(r) {
  const q = r.mergeQueuePos;
  if (q && Number.isInteger(q.position) && q.position > 0 && Number.isInteger(q.total) && q.total >= q.position) {
    return ` — ${t('delegate.view.queueBadge', { n: String(q.position), m: String(q.total) })}`;
  }
  return '';
}

function basename(p) {
  const s = String(p || '').replace(/\/+$/, '');
  const i = s.lastIndexOf('/');
  return i >= 0 ? s.slice(i + 1) : s;
}

/** 相对时刻（m/h/d；与归档行元数据同语义的本地小镜像）。 */
function ago(ms) {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return '';
  const diff = Date.now() - ms;
  if (diff < 60_000) return t('task.justNow');
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

/** 子过程卡（agentflow 步骤 / subagent 子代理）：内容 = subSpecs 单源。 */
function subCardEl(kind, idx, item, g) {
  const st = String(item.status || '');
  const el = document.createElement('div');
  el.className = 'dvp-nd dvp-sub';
  el.style.cssText = `left:${g.x}px;top:${g.y}px;width:${g.w}px;height:${g.h}px`;
  el.tabIndex = 0;
  el.dataset.subKey = `${kind}:${idx}`;
  el.setAttribute('aria-label', `${String(item.label || item.agentName || '')} — ${subWord(st)}`);
  const head = document.createElement('div');
  head.className = 'dvp-head';
  if (kind === 'chain') {
    const num = document.createElement('span');
    num.className = 'dvp-sub-num';
    num.textContent = String(idx + 1);
    head.appendChild(num);
  } else {
    const ex = document.createElement('span');
    ex.className = 'dvp-exec dvp-exec-sm';
    ex.textContent = String(item.agentName || '?').slice(0, 1).toUpperCase();
    head.appendChild(ex);
  }
  const title = document.createElement('span');
  title.className = 'dvp-sub-title';
  const label = kind === 'chain' ? String(item.label || '') : String(item.agentName || '');
  title.textContent = label;
  title.title = String(item.description || label);
  const dot = document.createElement('i');
  dot.className = 'dvp-st-dot';
  if (st === 'completed') dot.classList.add('done');
  else if (st === 'running' || st === 'restarting') dot.classList.add('run');
  else if (st === 'failed') dot.classList.add('fail');
  head.append(title, dot);
  el.appendChild(head);
  return el;
}

function subWord(st) {
  if (st === 'completed') return t('flowmap.done');
  if (st === 'running' || st === 'restarting') return t('task.inProgressShort');
  if (st === 'failed') return t('flowmap.fail');
  return t('delegate.view.stepPending');
}

function sinkCard(g, lanes) {
  const el = document.createElement('div');
  el.className = 'dvp-nd dvp-ghost dvp-sink';
  el.style.cssText = `left:${g.x}px;top:${g.y}px;width:${g.w}px;height:${g.h}px`;
  const live = lanes.merge.length > 0; // 合并窗活跃（持有者在合并泳道）
  if (live) el.classList.add('live');
  const base = firstBase(lanes);
  el.setAttribute('role', 'img');
  const nm = document.createElement('div');
  nm.className = 'dvp-sink-name';
  nm.textContent = `⎇ ${base || '·'}`;
  const sub = document.createElement('div');
  sub.className = 'dvp-sink-sub';
  sub.textContent = t('delegate.view.sinkSub');
  el.append(nm, sub);
  el.setAttribute('aria-label', `${nm.textContent} · ${sub.textContent}${live ? ' · live' : ''}`);
  return el;
}

/** 汇点 base 分支（带内首个非空 baseBranch——数据原文，零默认臆造）。 */
function firstBase(lanes) {
  for (const list of [lanes.merge, lanes.rtm, lanes.done, lanes.run]) {
    for (const r of list) if (r.baseBranch) return String(r.baseBranch);
  }
  return '';
}

// ── SVG 连线（状态化；样张 §4 三族）──────────────────────────────────────────

/** 贝塞尔路径 + 中点（t=.5 三次贝塞尔 M=(P0+3P1+3P2+P3)/8）。 */
function hPath(x0, y0, x1, y1) {
  const mx = (x0 + x1) / 2;
  const d = `M${r1(x0)},${r1(y0)} C ${r1(mx)},${r1(y0)} ${r1(mx)},${r1(y1)} ${r1(x1)},${r1(y1)}`;
  const mxp = (x0 + 3 * mx + 3 * mx + x1) / 8;
  const myp = (y0 + 3 * y0 + 3 * y1 + y1) / 8;
  return { d, mid: { x: mxp, y: myp } };
}
function vPath(x0, y0, x1, y1) {
  const my = (y0 + y1) / 2;
  const d = `M${r1(x0)},${r1(y0)} C ${r1(x0)},${r1(my)} ${r1(x1)},${r1(my)} ${r1(x1)},${r1(y1)}`;
  return { d, mid: { x: (x0 + x1) / 2, y: my } };
}
function arcPath(x0, y0, x1, y1, lift) {
  const c1x = x0 + (x1 - x0) * 0.25;
  const c2x = x0 + (x1 - x0) * 0.6;
  const d = `M${r1(x0)},${r1(y0)} C ${r1(c1x)},${r1(y0 - lift)} ${r1(c2x)},${r1(y0 - lift)} ${r1(x1)},${r1(y1)}`;
  const mxp = (x0 + 3 * c1x + 3 * c2x + x1) / 8;
  const myp = (y0 + 3 * (y0 - lift) + 3 * (y0 - lift) + y1) / 8;
  return { d, mid: { x: mxp, y: myp } };
}
const r1 = (n) => Math.round(n * 10) / 10;

function buildEdges({ geo, sinkGeo, subGeo, groups, lanes, doneCollapsed, roots, w, h, chips }) {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.classList.add('dvp-edges');
  svg.setAttribute('width', String(w));
  svg.setAttribute('height', String(h));
  svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
  svg.setAttribute('aria-hidden', 'true');
  const mk = (cls, d, marker) => {
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('d', d);
    p.setAttribute('class', cls);
    if (marker) p.setAttribute('marker-end', `url(#${marker})`);
    svg.appendChild(p);
    return p;
  };
  // 共享箭头 marker（每带一副；填充走 CSS var——零字面色值）。
  const defs = document.createElementNS(NS, 'defs');
  defs.innerHTML = ['idle', 'inflight', 'del', 'amb', 'mrg']
    .map(
      (k) =>
        `<marker id="dvp-mk-${k}" viewBox="0 0 8 8" refX="6.5" refY="4" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M0,0.5 L7.5,4 L0,7.5 Z" class="dvp-mk-${k}"/></marker>`,
    )
    .join('');
  svg.appendChild(defs);

  const sinkL = { x: sinkGeo.x, y: sinkGeo.y + sinkGeo.h / 2 };

  // ① 派发边（拓扑 · 降噪：仅运行中的 delegate 保留——设计 §4）。
  if (roots.length) {
    for (const r of lanes.run) {
      const g = geo.get(String(r.delegateId || ''));
      if (!g) continue;
      const root = roots.reduce((best, rg) => {
        if (!best) return rg;
        return Math.abs(rg.geo.y - g.y) < Math.abs(best.geo.y - g.y) ? rg : best;
      }, null);
      if (!root) continue;
      const { d } = hPath(root.geo.x + COLS.root.w, root.geo.y + H_ROOT / 2, COLS.run.x, g.y + g.h / 2);
      mk('e-idle', d, 'dvp-mk-idle');
    }
  }

  // ② 子过程边（拓扑）：链内顺序短边（上游态定边态）+ 星扇（子态定边态）。
  for (const grp of groups) {
    const dg = geo.get(String(grp.row.delegateId || ''));
    if (!dg) continue;
    if (grp.kind === 'chain') {
      const steps = topologyOf(grp.row).steps;
      let prev = { x: dg.x + dg.w / 2, y: dg.y + dg.h, st: panoStatus(grp.row) };
      steps.forEach((s, i) => {
        const sg = subGeo.get(stepKey(grp.row, i));
        if (!sg) return;
        const st = subState(prev.st);
        const { d } = vPath(prev.x, prev.y, sg.x + sg.w / 2, sg.y);
        mk(`e-${st}`, d, st === 'del' ? 'dvp-mk-del' : st === 'inflight' ? 'dvp-mk-inflight' : 'dvp-mk-idle');
        prev = { x: sg.x + sg.w / 2, y: sg.y + sg.h, st: String(s.status || '') };
      });
    } else {
      const kids = topologyOf(grp.row).children;
      const from = { x: dg.x + Math.min(dg.w / 2, 60), y: dg.y + dg.h };
      kids.forEach((c, i) => {
        const sg = subGeo.get(starKey(grp.row, i));
        if (!sg) return;
        const st = subState(String(c.status || ''));
        const { d } = hPath(from.x, from.y, sg.x + sg.w / 2, sg.y);
        mk(`e-${st}`, d, st === 'del' ? 'dvp-mk-del' : st === 'inflight' ? 'dvp-mk-inflight' : 'dvp-mk-idle');
      });
    }
  }

  // ③ 合并管线边（状态）：候合并=琥珀虚线 / 合并中=蓝晶行军蚁 / 已并入=蓝晶实线。
  for (const r of lanes.rtm) {
    const g = geo.get(String(r.delegateId || ''));
    if (!g) continue;
    const { d, mid } = arcPath(g.x + g.w, g.y + g.h / 2, sinkL.x, sinkL.y + 6, 60);
    mk('e-rtm', d, 'dvp-mk-amb');
    const q = r.mergeQueuePos;
    if (q && Number.isInteger(q.position) && q.position > 0) {
      chips.push({ x: mid.x, y: mid.y, text: t('delegate.view.fifo', { n: String(q.position) }), cls: '' });
    }
  }
  for (const r of lanes.merge) {
    const g = geo.get(String(r.delegateId || ''));
    if (!g) continue;
    const { d, mid } = hPath(g.x + g.w, g.y + g.h / 2, sinkL.x, sinkL.y);
    mk('e-merge', d, 'dvp-mk-mrg');
    chips.push({ x: mid.x, y: mid.y, text: t('delegate.view.mergeWindow'), cls: 'mrgc' });
  }
  if (!doneCollapsed) {
    for (const r of lanes.done) {
      if (panoStatus(r) !== 'completed') continue; // 失败/取消终态不入合并管线（§8.6）
      const g = geo.get(String(r.delegateId || ''));
      if (!g) continue;
      const { d } = hPath(g.x + g.w, g.y + g.h / 2, sinkL.x, sinkL.y + 18);
      mk('e-del', d, 'dvp-mk-del');
    }
  }

  return svg;
}

// ── 图例（页脚玻璃条：状态 7 态｜连线 5 型｜形状 4 类）─────────────────────────
function buildLegend() {
  const foot = document.createElement('footer');
  foot.className = 'dvp-legend';
  foot.setAttribute('aria-label', t('delegate.view.legend.status'));
  const group = (head) => {
    const g = document.createElement('div');
    g.className = 'dvp-lg-group';
    const h = document.createElement('span');
    h.className = 'dvp-lg-h';
    h.textContent = head;
    g.appendChild(h);
    return g;
  };
  const item = (inner, label) => {
    const s = document.createElement('span');
    s.className = 'dvp-lg-item';
    s.append(inner, document.createTextNode(label));
    return s;
  };
  const dotEl = (cls) => {
    const i = document.createElement('i');
    i.className = `dvp-lg-dot ${cls || ''}`;
    return i;
  };
  const glyphEl = (ch, cls) => {
    const s = document.createElement('span');
    s.className = `dvp-lg-glyph ${cls || ''}`;
    s.textContent = ch;
    return s;
  };
  const lineEl = (cls) => {
    const i = document.createElement('i');
    i.className = `dvp-lg-line ${cls || ''}`;
    return i;
  };
  const g1 = group(t('delegate.view.legend.status'));
  g1.append(
    item(dotEl('lg-run'), t('task.inProgressShort')),
    item(dotEl('lg-rtm'), t('delegate.readyToMerge')),
    item(dotEl('lg-mrg'), t('delegate.view.merging')),
    item(dotEl('lg-fail'), t('flowmap.fail')),
    item(glyphEl('✓', 'lg-done'), t('flowmap.done')),
    item(glyphEl('−', 'lg-mut'), t('flowmap.st.cancelled')),
    item(glyphEl('↻', 'lg-mut'), t('delegate.view.restart')),
  );
  const g2 = group(t('delegate.view.legend.edges'));
  g2.append(
    item(lineEl('lg-idle'), t('delegate.view.legend.edgeIdle')),
    item(lineEl('lg-inflight'), t('delegate.view.legend.edgeInflight')),
    item(lineEl('lg-del'), t('delegate.view.legend.edgeDelivered')),
    item(lineEl('lg-rtm'), t('delegate.view.legend.edgeRtm')),
    item(lineEl('lg-merge'), t('delegate.view.legend.edgeMerge')),
  );
  const g3 = group(t('delegate.view.legend.shapes'));
  const shapeEl = (cls) => {
    const i = document.createElement('i');
    i.className = `dvp-lg-shape ${cls}`;
    return i;
  };
  g3.append(
    item(shapeEl('sh-root'), t('delegate.view.legend.shapeRoot')),
    item(shapeEl('sh-delegate'), t('delegate.view.legend.shapeDelegate')),
    item(shapeEl('sh-sub'), t('delegate.view.legend.shapeSub')),
    item(shapeEl('sh-sink'), t('delegate.view.legend.shapeSink')),
  );
  foot.append(g1, g2, g3);
  return foot;
}

// ── 样式兜底（正常路径 = index.html <link> 装载 css/delegatePanorama.css；此处仅
//    防无 link 环境（模块级单测/夹具页）。幂等。零新色值——全 var token。）───────
const PANO_CSS_ID = 'delegate-panorama-css';
let panoCssChecked = false;
function ensurePanoramaCss() {
  if (panoCssChecked || typeof document === 'undefined') return;
  panoCssChecked = true;
  if (!document.querySelector('link[href*="delegatePanorama.css"]') && !document.getElementById(PANO_CSS_ID)) {
    const link = document.createElement('link');
    link.id = PANO_CSS_ID;
    link.rel = 'stylesheet';
    link.href = 'css/delegatePanorama.css';
    document.head.appendChild(link);
  }
}

/** CSS.escape 兜底（旧内核无 CSS.escape 时的最小形）。 */
function cssEscape(s) {
  const v = String(s || '');
  if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') return CSS.escape(v);
  return v.replace(/["\\]/g, '\\$&');
}
