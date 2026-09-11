// flowMapTab.js — Project Flow Map 渲染（#27 方向调整 + 导航调整：点击 project 不再
// 新开标签页，由 projectTab 在 projects 标签页内就地渲染，本模块提供 renderFlowMapInto
// 渲染管线 + WS 增量更新；独立 flow-map-* 标签页降级为 legacy 路径，
// 仅供旧标签页恢复）。复用 flow-run 标签页形态 + solar-card 显示设计；
// 关闭标签页不影响项目数据。数据驱动自 §2.2 NodeList 结构；
// 点击节点卡片打开右侧详情面板（flowMapArchive）。
//
// 实时更新 + 变化动画（Flow Map 实时性）：WS nodeCreated/Updated/Completed/Removed 的
// payload 是与 NodeList 同构的节点 JSON（后端 NodePayload.buildNodeJson 单序列化点），
// 事件到达时直接并入本地快照 fmByProject，走增量 diff 渲染——新节点淡入、消失节点
// 淡出、连线生长 / 端点跟随重绘、状态色平滑过渡；全程不做整页 innerHTML，已有节点的
// 轨道动画与点击监听保持存活。视图打开（legacy 标签页或 projects 就地视图）状态下
// 收到事件即更新，不切换标签页。fetch 全量快照降级为对账兜底：增量应用后仍安排一次
// 防抖拉取，diff 渲染保证对账只在真实漂移时碰 DOM（对账无跳变）。
//
// 归档单源收口（链级抽象 P0，设计 20260910_flowmap-chain-abstraction-spec.md
// §4.2/§7-B）：主图可见集 = 后端活动区快照本身——归档资格判定唯一存在于后端
// （FlowMapStore sweep，拓扑链口径），sweep 出库逐成员广播 nodeRemoved → 增量
// diff 单卡 fm-exit 淡出（复用既有机制），600ms 对账兜底收敛；前端零链派生零
// 判据（旧 refreshChains/chainEligible 时间批镜像已删除）。
// 归档面板反馈 = flowMapArchive refetch 出现新链 id 时徽章脉冲 + 条目闪烁 + toast。

import { openTab, getTabPane } from './canvas.js';
import { ensureFlowCss } from './flowCss.js';
import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { fetchFlowMap, NODE_STATUS_CLS } from './nodeData.js';
import {
  isTerminalStatus, purgeExpired,
  ingestNodes, recordNodeRemoved, dropStore,
  syncArchiveUi, openDetailFor,
  chainMembersOf, chainHighlightIds,
} from './flowMapArchive.js';
import { toggleHTML, bindToggle, setToggleState } from './toggle.js';

// 布局常量（紧凑化：作者 2026-09-04 裁定「层级从上到下、连线竖直/曲线、压缩冗余空白」）
// NODE_W 对齐真实卡宽（FLOW_CSS .solar-node width:124px）——旧值 150 让布局数学
// 虚胖 26px/卡（水平净距虚增）；V_SPACING 150：典型卡高 88-105px 时层间净距 45-62px，
// 最厚卡（result+等待脚注 ~134px）仍 +16px 不重叠；H_SPACING 168 → 水平净距 44px。
const NODE_W = 124;
const NODE_H = 108;
const V_SPACING = 150;
const H_SPACING = 168;
const PAD = 48;

// 增量动画节奏：节点位移走 CSS left/top 过渡、边端点跟随走 rAF 插值，两者同曲线
// （easeInOutCubic ≙ cubic-bezier(0.645,0.045,0.355,1)）同时长，视觉上同步滑动。
const MOVE_MS = 400;
const easeInOutCubic = (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2);

function prefersReducedMotion() {
  return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
}

// 每项目一份状态（原来是单例 currentProject/currentFm：开第二个 flow-map 标签页会
// 互相顶掉，刷新恢复后 currentProject 为 null → WS 事件驱动的刷新整个失效）。
/** @type {Map<string, any>} project → 最近一次 NodeList 快照（WS 增量应用的目标） */
const fmByProject = new Map();
/** @type {Map<string, number>} project → 渲染代（丢弃过期 fetch 响应，fetch 对 fetch） */
const seqByProject = new Map();
/** @type {Map<string, number>} project → 快照代：任何缓存写入（fetch 接受 / WS 增量
 *  应用）都 +1。fetch 在途期间代前进 → 该响应相对缓存已旧，丢弃并重新对账——否则
 *  旧快照会把增量应用进来的节点回滚掉（节点闪没了又出现）。 */
const genByProject = new Map();
/** @type {WeakMap<HTMLElement, any>} 渲染容器 → 最近一次渲染的快照（增量 diff 基线） */
const renderedFmByContainer = new WeakMap();
/** @type {Map<string, number>} project → 对账拉取定时器 id */
const reconcileTimers = new Map();

// ── 视图过滤（P3 占位治理最小组合前端腿，报告 20260908_flowmap-占位调查与显示优化
//   提案 P3；作者定案收缩为二态「全部/进行中」）──────────────────────────
// 纯前端派生过滤：不改任何归档语义/载荷/后端。「进行中」= 隐藏终态与停滞卡
// （completed/failed/cancelled/blocked），只留活跃（running/wiring/pending）——
// 占位治理（P1 引擎判据）落地前作者可一键隐藏终态死链只看活跃。
// 默认「全部」（现状行为零漂移）；视图态持久化 localStorage（nebflow.* 键惯例，
// 参照 nebflow.onboarding.enabled），会话内多视图共享同一开关。
const VIEW_FILTER_KEY = 'nebflow.flowmap.activeOnly';

/** @returns {boolean} 当前是否「进行中」态（读取失败一律回落「全部」）。 */
function viewFilterActiveOnly() {
  try { return localStorage.getItem(VIEW_FILTER_KEY) === '1'; } catch (e) { return false; }
}

/** 视图过滤谓词（派生管线最后一环）：「全部」恒真；「进行中」放行活跃态
 *  （running/wiring/pending），隐藏终态（completed/failed/cancelled）与 blocked
 *  （待分发器处置的停滞卡，归隐藏侧——任务书口径）。 */
function passesViewFilter(node) {
  if (!viewFilterActiveOnly()) return true;
  const st = String(node?.status || '');
  return !isTerminalStatus(st) && st !== 'blocked';
}

function bumpGen(project) {
  genByProject.set(project, (genByProject.get(project) || 0) + 1);
}

// ══ 相机（固定视口 + CAD 式鼠标跟随缩放，v1 交互壳规格 §3.1 C1-C8）════
// 视口 = .fm-viewport（overflow:hidden）；相机 transform 直接作用于 .solar-canvas：
// translate(tx,ty) scale(s)，transform-origin 50% 50%（画布中心）。
// 缩放不变量（作者裁定「缩放前后鼠标下的内容屏幕位置不变」，固定中心缩放已否决）：
//   screen(L) = Wc + s·(L − C) + T   ——L 世界点（画布局部坐标）、C 画布中心（局部）、
//   Wc = 画布未变换时中心在视口坐标的位置（由 offsetLeft/offsetWidth 得出，与相机无关）
// 锚点缩放（C3）：P 点缩放前后命中的世界点不变 ⇒ T′ = P − Wc − (s′/s)(P − Wc − T)
// 状态 = { s, tx, ty, autoFit, fitS }；autoFit 态布局变化跟随重算 fit（C1），
// 任何 wheel/拖拽转入 userNav（C2）；双击空白回 fit（C5）。
// WS 全量重渲（innerHTML 换画布）后按「视口中心世界点 q」恢复相机——userNav 不丢位。

const camByViewport = new WeakMap();
const CAM_MIN_FACTOR = 0.4;   // C8 触界下限：fitScale × 0.4
const CAM_MAX_S = 4.0;        // C8 触界上限
const FOCUS_S = 1.6;          // C6/N3 点击选中目标倍率：max(当前, 1.6)
const CAM_ANIM_MS = 280;      // §4 fit/focus 相机动画；wheel/平移零动画（直接操作）

const clampNum = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/** 画布未变换时中心在视口坐标的位置——从 getBoundingClientRect 精确反解：
 *  变换后 rect 中心（视口局部）= Wc + T（与 scale 无关），故 Wc = rect 中心 − T。
 *  不用 offsetLeft/offsetWidth（整数取整），半像素误差经 (s′/s−1) 放大会破坏
 *  CAD 锚点 ±1px 断言。 */
function camWorldCenter(vp, canvas, cam) {
  const r = canvas.getBoundingClientRect();
  const vr = vp.getBoundingClientRect();
  return {
    x: r.left + r.width / 2 - vr.left - (cam ? cam.tx : 0),
    y: r.top + r.height / 2 - vr.top - (cam ? cam.ty : 0),
  };
}

/** 画布中心（局部坐标，style 宽高为我方写入的整数值，精确）。 */
function camCanvasHalf(canvas) {
  return { x: parseFloat(canvas.style.width || '') / 2 || canvas.offsetWidth / 2, y: parseFloat(canvas.style.height || '') / 2 || canvas.offsetHeight / 2 };
}

/** 视口坐标 → 世界点（画布局部坐标）。 */
function camWorldAt(vp, canvas, cam, px, py) {
  const wc = camWorldCenter(vp, canvas, cam);
  const c = camCanvasHalf(canvas);
  return { x: c.x + (px - wc.x - cam.tx) / cam.s, y: c.y + (py - wc.y - cam.ty) / cam.s };
}

/** 应用相机到画布。animate=true（fit/focus）走 280ms 相机曲线并落 .fm-cam-anim
 *  类（与既有画布宽高 0.4s 过渡并存）；false（wheel/平移/恢复）直接写 style。 */
function camApply(vp, canvas, cam, animate) {
  vp.dataset.fmCamera = cam.autoFit ? 'autofit' : 'usernav';
  vp.dataset.fitScale = cam.fitS.toFixed(3);
  const reduced = prefersReducedMotion();
  if (animate && !reduced) {
    canvas.classList.add('fm-cam-anim');
    clearTimeout(canvas.__fmCamAnimTimer);
    canvas.__fmCamAnimTimer = setTimeout(() => canvas.classList.remove('fm-cam-anim'), CAM_ANIM_MS + 80);
  } else {
    canvas.classList.remove('fm-cam-anim');
  }
  canvas.style.transform = `translate(${cam.tx.toFixed(2)}px, ${cam.ty.toFixed(2)}px) scale(${cam.s.toFixed(4)})`;
}

/** 计算 fit 相机（世界居中收进视口，A2）：scale = min(视口内边距后宽高比, 1)。 */
function camFit(vp, canvas, cur) {
  const W = parseFloat(canvas.style.width || '') || 360, H = parseFloat(canvas.style.height || '') || 240;
  const vw = vp.clientWidth, vh = vp.clientHeight;
  if (vw <= 0 || vh <= 0) return null; // 隐藏 pane：无从 fit（打开/刷新路径会重算）
  const fitS = clampNum(Math.min((vw - 64) / W, (vh - 64) / H, 1), 0.2, 1);
  const wc = camWorldCenter(vp, canvas, cur);
  return { s: fitS, tx: vw / 2 - wc.x, ty: vh / 2 - wc.y, autoFit: true, fitS };
}

/** 记住「当前视口中心对着的世界点」，供全量重渲后恢复（防 userNav 丢位）。 */
function camRemember(vp, canvas, cam) {
  cam.__q = camWorldAt(vp, canvas, cam, vp.clientWidth / 2, vp.clientHeight / 2);
}

/** 按记忆的世界点恢复相机（新画布尺寸下重新解 T）。无记忆（首次）→ fit。 */
function camRestore(vp, canvas) {
  const prev = camByViewport.get(vp);
  if (prev && prev.__q && vp.clientWidth > 0) {
    const wc = camWorldCenter(vp, canvas, null);
    const c = camCanvasHalf(canvas);
    const cam = {
      s: prev.s, autoFit: false, fitS: prev.fitS,
      tx: vp.clientWidth / 2 - wc.x - (prev.__q.x - c.x) * prev.s,
      ty: vp.clientHeight / 2 - wc.y - (prev.__q.y - c.y) * prev.s,
    };
    cam.__q = prev.__q;
    camByViewport.set(vp, cam);
    camApply(vp, canvas, cam, false);
    return cam;
  }
  const cam = camFit(vp, canvas);
  if (!cam) return null;
  camByViewport.set(vp, cam);
  camApply(vp, canvas, cam, false);
  return cam;
}

/** wheel 缩放（C3：锚点=光标，即时无动画）。 */
function camZoomAt(vp, canvas, cam, px, py, deltaY) {
  const k = Math.exp(-deltaY * 0.0015);
  const s2 = clampNum(cam.s * k, cam.fitS * CAM_MIN_FACTOR, CAM_MAX_S);
  if (Math.abs(s2 - cam.s) < 1e-6) return;
  const wc = camWorldCenter(vp, canvas, cam);
  // 缩放不变量：P 点世界点不动 ⇒ T′ = P − Wc − (s′/s)(P − Wc − T)
  const rx = px - wc.x - cam.tx, ry = py - wc.y - cam.ty;
  const f = s2 / cam.s;
  cam.tx = px - wc.x - rx * f;
  cam.ty = py - wc.y - ry * f;
  cam.s = s2;
  cam.autoFit = false; // C2：用户导航，autoFit 挂起
  camApply(vp, canvas, cam, false);
  camRemember(vp, canvas, cam);
}

/** 拖拽平移（C4，即时跟随）。 */
function camPanBy(vp, canvas, cam, dx, dy) {
  cam.tx += dx; cam.ty += dy;
  cam.autoFit = false;
  camApply(vp, canvas, cam, false);
  camRemember(vp, canvas, cam);
}

/** 动画回 fit（C5：双击空白）。 */
function camFitAnimated(vp, canvas) {
  const cam = camFit(vp, canvas, camByViewport.get(vp));
  if (!cam) return;
  camByViewport.set(vp, cam);
  camApply(vp, canvas, cam, true);
}

/** 相机聚焦节点（C6/N3 点击选中 + C7 跳转入口共用）：节点居中 + s′=max(s,1.6)。 */
function camFocusNode(vp, canvas, el) {
  const cam = camByViewport.get(vp);
  if (!cam || vp.clientWidth <= 0) return;
  const s2 = clampNum(Math.max(cam.s, FOCUS_S), cam.fitS * CAM_MIN_FACTOR, CAM_MAX_S);
  const wc = camWorldCenter(vp, canvas, cam);
  const vr = vp.getBoundingClientRect();
  const nr = el.getBoundingClientRect();
  // 节点中心当前屏幕位（视口局部）→ 精确世界点（rect 含当前相机变换，反解抵消）
  const L = camWorldAt(vp, canvas, cam,
    nr.left + nr.width / 2 - vr.left, nr.top + nr.height / 2 - vr.top);
  const c = camCanvasHalf(canvas);
  // 目标：世界点 L 落到视口中心（任意 s：T = P − Wc − s(L − C)）
  cam.tx = vp.clientWidth / 2 - wc.x - s2 * (L.x - c.x);
  cam.ty = vp.clientHeight / 2 - wc.y - s2 * (L.y - c.y);
  cam.s = s2;
  cam.autoFit = false;
  camApply(vp, canvas, cam, true);
  camRemember(vp, canvas, cam);
}

/** 相机聚焦节点（元素入口：点击选中 / 跳转高亮共用）。 */
function camFocusNodeEl(el) {
  const vp = el.closest('.fm-viewport');
  const canvas = el.closest('.solar-canvas');
  if (vp && canvas) camFocusNode(vp, canvas, el);
}

/** 相机与指针/滚轮/双击交互绑定（全量渲染后调用一次；增量路径画布存活不重绑）。 */
function bindCamera(vp, canvas) {
  if (vp.dataset.fmCamBound === '1') return;
  vp.dataset.fmCamBound = '1';
  let drag = null;      // {id, x, y, moved}
  let suppressClick = false;

  // C3：滚轮只做缩放（图无滚动语义），锚点=光标
  vp.addEventListener('wheel', (e) => {
    e.preventDefault();
    const cam = camByViewport.get(vp);
    const c = vp.querySelector('.solar-canvas');
    if (!cam || !c || vp.clientWidth <= 0) return;
    const rect = vp.getBoundingClientRect();
    camZoomAt(vp, c, cam, e.clientX - rect.left, e.clientY - rect.top, e.deltaY);
  }, { passive: false });

  // C4：按下拖拽平移（节点上起拖同样平移；位移 ≤3px 仍是 click，不破坏选择）
  vp.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    suppressClick = false; // 新按下序列重置（pointercancel 残留不吞下一次真点击）
    drag = { id: e.pointerId, x: e.clientX, y: e.clientY, moved: false };
  });
  vp.addEventListener('pointermove', (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    if (!drag.moved && Math.hypot(dx, dy) <= 3) return;
    const cam = camByViewport.get(vp);
    const c = vp.querySelector('.solar-canvas');
    if (!cam || !c) return;
    if (!drag.moved) { drag.moved = true; try { vp.setPointerCapture(e.pointerId); } catch (_) { /* 隐藏态 */ } }
    drag.x = e.clientX; drag.y = e.clientY;
    camPanBy(vp, c, cam, dx, dy);
  });
  const endDrag = (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    suppressClick = drag.moved; // 真拖拽：吞掉随后的 click（不选中/不收面板）
    drag = null;
  };
  vp.addEventListener('pointerup', endDrag);
  vp.addEventListener('pointercancel', endDrag);

  // 拖拽后的 click 在捕获相位吞掉：节点选择与文档级空白收起都不触发
  vp.addEventListener('click', (e) => {
    if (!suppressClick) return;
    suppressClick = false;
    e.stopPropagation();
    e.preventDefault();
  }, true);

  // C5：双击空白回 fit（节点上的双击=两次选择，不重置导航位）
  vp.addEventListener('dblclick', (e) => {
    if (e.target.closest('.fm-node')) return;
    const c = vp.querySelector('.solar-canvas');
    if (c) camFitAnimated(vp, c);
  });
  camRestore(vp, canvas);
}

// ══ L1 hover 局部强调（N1/N2 focus+context：自身+邻居提亮，其余淡出）════
// 邻接 map 每帧渲染后重建（O(V+E)），hover 时 O(deg) 查表；pointerover/out 委托在
// 画布上，80ms 宽限跨缝隙不闪烁；链齐退场中的卡（.fm-exit）不参与强调。

/** @type {WeakMap<HTMLElement, {nbrs: Map<string, Set<string>>, ends: Map<string, Set<string>>}>} */
const adjByCanvas = new WeakMap();

/** 由可见边集合构建邻接表（nbrs：节点→邻居 id 集；ends：edgeId→端点 id 集）。 */
function rebuildAdjacency(canvas, fm, edgesMap) {
  const nbrs = new Map();
  const ends = new Map();
  for (const n of visibleNodes(fm)) nbrs.set(n.id, new Set());
  for (const [key] of edgesMap) {
    const sep = key.includes('~>') ? '~>' : '=>';
    const i = key.indexOf(sep);
    const a = key.slice(0, i), b = key.slice(i + sep.length);
    if (nbrs.has(a) && nbrs.has(b)) { nbrs.get(a).add(b); nbrs.get(b).add(a); }
    ends.set(key, new Set([a, b]));
  }
  adjByCanvas.set(canvas, { nbrs, ends });
}

/** canvas → 项目名（U1 链高亮：hover/click 需读该项目的链上下文；渲染入口注入）。 */
const chainCtxByCanvas = new WeakMap();

/** hover 的链高亮集合（U1 批 · 作者裁定④）：hover 链上的任意节点（普通节点或合并
 *  节点）⇒ 高亮**整条链** = 该链全量成员 id 集，**含链上全部合并节点**（不得漏掉）。
 *  这里刻意不消费 `chainHighlightIds` 的多链退化（那只属点击语义③）——否则 hover
 *  多链合并节点会把自己所在整链 dim 掉。链不可知（孤立节点/旧后端）→ 集合退化为
 *  仅自身（等于既有无邻居行为，零 crash）。
 *  @param {HTMLElement} canvas @param {string} nodeId @returns {Set<string>} */
function hoverChainSet(canvas, nodeId) {
  const project = chainCtxByCanvas.get(canvas);
  if (!project) return new Set([nodeId]);
  return new Set(chainMembersOf(project, nodeId));
}

/** 清 hover 强调类（节点 + 边一起清，L0 还原唯一出口）。修复（20260905 作者报告
 *  「连线 hover 变淡看不清」主因）：原实现还原路径只清节点类、提前 return 跳过
 *  边类——节点 hover 结束后全部非邻接边永久卡在 .fm-edge-dim（opacity .06），
 *  整图连线「特别淡」。现在节点/边类同清，L0 恢复完整。 */
function clearHoverClasses(canvas) {
  canvas.querySelectorAll('.fm-hi, .fm-nb, .fm-dim')
    .forEach((el) => el.classList.remove('fm-hi', 'fm-nb', 'fm-dim'));
  canvas.querySelectorAll('.fm-edge-hi, .fm-edge-dim')
    .forEach((el) => el.classList.remove('fm-edge-hi', 'fm-edge-dim'));
}

// ── 点击链高亮（U1 批 · 作者 2026-09-11 裁定③）──────────────────────
// 语义：点**多链合并节点**（引擎 chainIds 长度 ≥2）⇒ 只高亮该节点本身（不得牵动/
// 高亮任何其他链节点）；点**单链合并节点 / 普通节点**⇒ 整条链一起高亮（含链上全部
// 节点）。视觉载体 = 既有 `.fm-adj` 类（flowMap.css 既有规则，本批零 CSS 改动）；
// `.fm-chain-pick` 仅是无样式的选择标记（供清除时精确定位，不影响外观）。清空时机
// = 点击空白处 / 点击另一节点 / 画布重渲染。
const CHAIN_PICK_CLASS = 'fm-chain-pick';

/** 清点击链高亮（只清本批标记的卡；归档面板 hover 的 .fm-adj-by-entry 不受影响）。 */
function clearChainPick(canvas) {
  canvas.querySelectorAll(`.fm-node.${CHAIN_PICK_CLASS}`).forEach((el) => {
    el.classList.remove('fm-adj', CHAIN_PICK_CLASS);
  });
}

/** 应用点击链高亮（U1 裁定③）：集合 = chainHighlightIds（多链合并 ⟶ 仅自身）。 */
function applyChainPick(canvas, nodeId) {
  clearChainPick(canvas);
  if (!canvas || !nodeId) return;
  const project = chainCtxByCanvas.get(canvas);
  if (!project) return;
  const { ids } = chainHighlightIds(project, nodeId);
  const set = new Set(ids);
  canvas.querySelectorAll('.fm-node').forEach((el) => {
    if (!set.has(el.getAttribute('data-node-id'))) return;
    el.classList.add('fm-adj', CHAIN_PICK_CLASS);
  });
}

/** 应用节点 hover 强调态（hoverId=null 还原 L0）。**U1 批改轴：链口径取代邻接口径**
 *  （作者 2026-09-11 裁定④）——自身 .fm-hi（1.12）、同链其余成员 .fm-nb（1.06，
 *  **含链上合并节点**）、链外 .fm-dim；边按「两端是否都在链内」分 .fm-edge-hi /
 *  .fm-edge-dim。边的构造规则（哪些边存在、状态档位/颜色）零改动——本函数只切
 *  hover 瞬态类。 */
function applyHover(canvas, hoverId) {
  clearHoverClasses(canvas);
  if (!hoverId) return;
  const set = hoverChainSet(canvas, hoverId);
  for (const n of canvas.querySelectorAll('.fm-node')) {
    if (n.classList.contains('fm-exit')) continue;
    const id = n.getAttribute('data-node-id');
    if (id === hoverId) n.classList.add('fm-hi');
    else if (set.has(id)) n.classList.add('fm-nb');
    else n.classList.add('fm-dim');
  }
  const adj = adjByCanvas.get(canvas);
  for (const p of canvas.querySelectorAll('[data-edge-id]')) {
    const e = adj && adj.ends.get(p.getAttribute('data-edge-id'));
    const inChain = e && Array.from(e).every((x) => x === hoverId || set.has(x));
    if (inChain) p.classList.add('fm-edge-hi');
    else p.classList.add('fm-edge-dim');
  }
}

/** 连线本体 hover 强调（20260905 作者报告「连线 hover 变淡」次因修复：边此前无
 *  hover 入口——边层 pointer-events:none + 委托只认 .fm-node）。语义：被 hover 边
 *  + 共端点邻接边 .fm-edge-hi（加粗提亮）、两端节点 .fm-nb（轻强调）；其余一切
 *  保持 L0 原样——连线 hover 只加亮、严禁淡化（.fm-dim/.fm-edge-dim 全程不落）。 */
function applyEdgeHover(canvas, edgeId) {
  const adj = adjByCanvas.get(canvas);
  if (!adj) return;
  clearHoverClasses(canvas);
  const ends = adj.ends.get(edgeId);
  if (!ends) return;
  const [ea, eb] = ends; // 端点恒为 2 个（rebuildAdjacency：new Set([a, b])）
  for (const p of canvas.querySelectorAll('[data-edge-id]')) {
    if (p.classList.contains('fm-edge-exit')) continue;
    const e = adj.ends.get(p.getAttribute('data-edge-id'));
    if (e && (e.has(ea) || e.has(eb))) p.classList.add('fm-edge-hi'); // 被 hover 边 + 共端点邻接边
  }
  for (const n of canvas.querySelectorAll('.fm-node')) {
    if (n.classList.contains('fm-exit')) continue;
    if (ends.has(n.getAttribute('data-node-id'))) n.classList.add('fm-nb');
  }
}

/** hover 事件绑定（画布级委托，全量渲染后一次；增量路径画布存活不重绑）。
 *  节点与连线两入口：pointerover 目标命中 .fm-node 走节点邻域语义（N1），
 *  命中 [data-edge-id]（边路径/箭头，pointer-events 已在 flowMap.css 开启）
 *  走连线加亮语义；两者互斥切换，80ms 宽限跨缝隙不闪烁。 */
function bindHover(canvas, projectName) {
  if (canvas.dataset.fmHoverBound === '1') return;
  // U1 链高亮上下文（作者裁定④）：hover 需读项目链上下文（链成员集），随绑定注入。
  if (projectName) chainCtxByCanvas.set(canvas, projectName);
  canvas.dataset.fmHoverBound = '1';
  let hovered = null; // 节点 hover：data-node-id
  let hoverEdge = null; // 连线 hover：data-edge-id
  let graceTimer = 0;
  const restore = () => { hovered = null; hoverEdge = null; applyHover(canvas, null); };
  canvas.addEventListener('pointerover', (e) => {
    const nEl = e.target.closest?.('.fm-node');
    if (nEl && !nEl.classList.contains('fm-exit')) {
      const id = nEl.getAttribute('data-node-id');
      if (id === hovered) { clearTimeout(graceTimer); return; }
      hovered = id; hoverEdge = null;
      clearTimeout(graceTimer);
      applyHover(canvas, id);
      return;
    }
    const eEl = e.target.closest?.('[data-edge-id]');
    if (eEl && !eEl.classList.contains('fm-edge-exit')) {
      const id = eEl.getAttribute('data-edge-id');
      if (id === hoverEdge) { clearTimeout(graceTimer); return; }
      hoverEdge = id; hovered = null;
      clearTimeout(graceTimer);
      applyEdgeHover(canvas, id);
    }
  });
  canvas.addEventListener('pointerout', (e) => {
    const nEl = e.target.closest?.('.fm-node');
    if (nEl && nEl.getAttribute('data-node-id') === hovered) {
      clearTimeout(graceTimer);
      graceTimer = setTimeout(restore, 80); // N2 宽限
      return;
    }
    const eEl = e.target.closest?.('[data-edge-id]');
    if (eEl && eEl.getAttribute('data-edge-id') === hoverEdge) {
      clearTimeout(graceTimer);
      graceTimer = setTimeout(restore, 80); // 连线间缝隙同宽限
    }
  });
}

/** 当前打开的所有 flow-map 视图 → [{project, pane}]（DOM 即真相，恢复后同样成立）。
 *  包含两类：legacy 独立标签页（data-tab-id^="flow-map-"）与 projects 标签页内
 *  的就地视图（pane.dataset.projectsView === 'flow-map'，由 projectTab 切换）。 */
function openFlowMapPanes() {
  const panes = /** @type {NodeListOf<HTMLElement>} */ (
    document.querySelectorAll('.canvas-tab-pane[data-tab-id^="flow-map-"]')
  );
  const list = Array.from(panes)
    .map((pane) => ({ project: (pane.dataset.tabId || '').slice('flow-map-'.length), pane }))
    .filter((x) => x.project);
  const pp = getTabPane('projects');
  if (pp && pp.dataset.projectsView === 'flow-map' && pp.dataset.flowMapProject) {
    list.push({ project: pp.dataset.flowMapProject, pane: pp });
  }
  return list;
}

// ── 布局：按 out 边 + deps 边 + in 边算深度层 ──────────────
// v3（规格 §3.1）：主图含链未齐终态保留卡，barrier 上游在图——in 边并入层级
// 推导（childrenMap），否则保留卡全部塌到 depth 0 平排（原型实测）。
function layoutNodes(fm) {
  const nodes = fm?.nodes || [];
  const nodeIds = new Set(nodes.map((n) => n.id));
  const childrenMap = new Map();
  const addChild = (from, to) => {
    if (!childrenMap.has(from)) childrenMap.set(from, []);
    childrenMap.get(from).push(to);
  };
  nodes.forEach((n) => {
    if (n.out && n.out !== 'Nebula' && nodeIds.has(n.out)) addChild(n.id, n.out);
    // deps 边（下游单侧持有，deps 设计 §1.4）：并入流向图（上游 → 下游），
    // 否则纯 deps 下游被当根放第 0 层、边画成逆向
    (n.deps || []).forEach((d) => { if (nodeIds.has(d)) addChild(d, n.id); });
    // in 边（barrier 输入）：v3 起参与层级推导（终态保留卡使上游在图）
    (n.in || []).forEach((x) => { if (nodeIds.has(x)) addChild(x, n.id); });
  });
  const depth = {};
  nodes.forEach((n) => { depth[n.id] = 0; });
  const visited = new Set();
  function visit(id, d) {
    depth[id] = Math.max(depth[id] || 0, d);
    if (visited.has(id)) return;
    visited.add(id);
    (childrenMap.get(id) || []).forEach((to) => visit(to, depth[id] + 1));
  }
  nodes.forEach((n) => {
    const hasUpstream = (n.in || []).some((x) => nodeIds.has(x))
      || (n.deps || []).some((x) => nodeIds.has(x));
    if (!hasUpstream) visit(n.id, 0);
  });
  nodes.forEach((n) => visit(n.id, depth[n.id] || 0));
  const atDepth = {};
  nodes.forEach((n) => {
    const d = depth[n.id] || 0;
    (atDepth[d] = atDepth[d] || []).push(n.id);
  });
  const positions = {};
  const maxAt = Object.values(atDepth).reduce((m, ids) => Math.max(m, ids.length), 0);
  Object.entries(atDepth).forEach(([d, ids]) => {
    const y = Number(d) * V_SPACING;
    const total = (ids.length - 1) * H_SPACING;
    ids.forEach((id, i) => { positions[id] = { x: i * H_SPACING - total / 2, y }; });
  });
  const maxDepth = Math.max(0, ...Object.keys(atDepth).map(Number));
  const width = Math.max((maxAt - 1) * H_SPACING + NODE_W + PAD * 2, 360);
  const height = maxDepth * V_SPACING + PAD * 2;
  return { positions, width, height };
}

/** 上游 id → 显示名解析器（deps 设计 §1.4「wiring 节点等待谁」脚注）：
 *  图内可见 → `名(id)`；不在活动区快照（已随后端 sweep 归档出库，或悬空引用）
 *  → `名/裸id（已归档）`（诚实降级，规格 §3.3/§8.4）。可见性 = 后端活动区快照
 *  单源（P0），不再查前端派生集。 */
function nameResolverOf(project) {
  const fm = fmByProject.get(project);
  const byId = new Map((fm?.nodes || []).map((n) => [n.id, n]));
  const archTag = t('flowmap.archivedTag');
  return (id) => {
    const n = byId.get(id);
    return n ? `${n.name}(${id})` : `${id}${archTag}`;
  };
}

// ── 内联 SVG 图标（裁定①：节点域禁 emoji/符号字符，状态图标用 SVG 描边绘制）──
// 描边风格统一：viewBox 12×12、stroke currentColor（随 .ok/.err/.warn 色板取色）、
// 圆头圆角；cancelled 减号线沿用原「—」语义。
const FM_STATUS_SVG = {
  ok: '<path d="M2.5 6.5 5 9l4.5-5.5"/>',
  err: '<path d="M3 3l6 6M9 3l-6 6"/>',
  warn: '<path d="M3.5 1.5v9M3.5 2.5H9L7.5 4.75 9 7H3.5"/>',
  cancelled: '<path d="M3 6h6"/>',
};
/** 终态卡状态词 i18n 键（2026-09-07 状态保真批）：glyph 单看不可读（cancelled 减号
 *  线视觉即「-」）——终态卡 head 行 glyph 旁恒带状态文本，死亡现场一眼可辨。 */
const FM_NODE_ST_KEY = {
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
};
const fmSvgIcon = (cls, inner, sw) =>
  `<span class="solar-node-status ${cls}"><svg viewBox="0 0 12 12" fill="none" stroke="currentColor"`
  + ` stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${inner}</svg></span>`;
// 等待脚注图标：沙漏（替代 ⏳，描边风格与状态图标一致）
const FM_WAIT_ICON =
  '<svg class="fm-wait-note-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor"'
  + ' stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
  + '<path d="M3 1.5h6M3 10.5h6M3.8 2.6h4.4L6 6 3.8 2.6ZM3.8 9.4h4.4L6 6 3.8 9.4Z"/></svg>';

// ── 特殊节点标识（badge 批 2026-09-05）：解析与渲染分离（便于 fixture 验证）──
// 三类标识彼此可辨（文字 + 配色双通道，裁定①禁 emoji 故纯文字胶囊）：
//   merge   结构属性——数据契约 `"merge": true`，缺省/缺失 = 非 merge；
//   loop    结构属性——前瞻防御，机制侧可能暂不产出该字段，缺字段不 crash；
//   pending 状态徽标——既有 status=pending（待作者确认/派发）。
// 判定严格 `=== true`：payload 缺字段/类型漂移一律静默降级为无徽标，零 console 噪音。
/** @returns {string[]} 命中的标识键，顺序 merge → loop → pending（结构先于状态）。 */
export function nodeFlagKeys(n) {
  if (!n || typeof n !== 'object') return [];
  const keys = [];
  if (n.merge === true) keys.push('merge');
  // loop 判定双形态（语义演进）：新建 loop 节点 payload 是配置对象
  // {maxRounds, verify, enabled}；早前前瞻防御批曾约定布尔 true。两种都算 loop。
  if (n.loop === true || (n.loop && typeof n.loop === 'object' && !Array.isArray(n.loop))) keys.push('loop');
  if ((n.status || 'pending') === 'pending') keys.push('pending');
  return keys;
}
const FM_FLAG_CLS = { merge: 'fm-flag-merge', loop: 'fm-flag-loop', pending: 'fm-flag-pending' };
/** 徽标 HTML：head 行胶囊，与 .fm-worktree-badge 同语言（文案 i18n flowmap.flag.*）。
 *  loop 徽标带轮次（LoopNode 批 2026-09-06）：loop 节点运行态显示「loop N/K」—
 *  复用现有 .fm-flag-loop 胶囊配色，不改卡内纵行（88px 卡纵向不可加行）。
 *  loopRound>0 才带轮次（pending/终态回退纯「循环」文案）。 */
export function flagBadgesHtml(n) {
  return nodeFlagKeys(n).map((k) => {
    let label = esc(t(`flowmap.flag.${k}`));
    if (k === 'loop') {
      const round = n.loopRound || 0;
      const maxRounds = (n.loop && typeof n.loop === 'object') ? n.loop.maxRounds : undefined;
      if (round > 0 && maxRounds) label = esc(`loop ${round}/${maxRounds}`);
    }
    return `<span class="fm-flag-badge ${FM_FLAG_CLS[k]}" title="${label}">${label}</span>`;
  }).join('');
}

// ── 节点卡片（复用 solar 视觉）────────────────────────────
function nodeHtml(n, pos, originX, nameOf) {
  const st = n.status || 'pending';
  const cls = NODE_STATUS_CLS[st] || 'pending';
  const term = isTerminalStatus(st); // v3 链未齐终态保留卡（规格 §3.2 终态色卡）
  const left = pos.x - NODE_W / 2 + originX;
  const top = pos.y - NODE_H / 2 + PAD;
  const statusIcon = st === 'completed' ? fmSvgIcon('ok', FM_STATUS_SVG.ok, 1.5)
    : st === 'failed' ? fmSvgIcon('err', FM_STATUS_SVG.err, 1.5)
    : st === 'blocked' ? fmSvgIcon('warn', FM_STATUS_SVG.warn, 1.4)
    : st === 'cancelled' ? fmSvgIcon('cancelled', FM_STATUS_SVG.cancelled, 1.5) : '';
  // 终态状态词（2026-09-07 状态保真批）：终态卡 glyph 旁恒带文本（已完成/失败/
  // 已取消）——真实终态可见，不再是孤零零的「-」减号线。
  const statusWord = term && FM_NODE_ST_KEY[st]
    ? `<span class="fm-st-word ${esc(cls)}">${esc(t(FM_NODE_ST_KEY[st]))}</span>` : '';
  const worktreeBadge = n.hasWorktree || n.worktree
    ? `<span class="fm-worktree-badge" title="${esc(n.worktree || '')}">wt</span>` : '';
  // 特殊节点标识（badge 批）：merge/loop/pending 徽标进 head 行（wt 徽标同区，
  // 复用该行既有 flex+gap；不新增卡内纵行——88px 卡纵向不可加行，同 node-flowmap-slim 口径）
  const flags = flagBadgesHtml(n);
  // 载荷收敛（2026-09-05）：卡片不再显示 result 摘要（默认载荷无 result）——
  // 改显示 description（创建必写的一行描述）；存量节点无 description → 回退
  // taskPreview（载荷条件字段，task 首行 ≤80 截断）。结果全文经详情窗按需拉取。
  const descText = n.description || n.taskPreview || '';
  // LoopNode 运行态（2026-09-06）：running 的 loop 节点 desc 行显相位（worker/verify），
  // 与 head 行「loop N/K」徽标互补（徽标上轮次、desc 行当前角色）——复用既有 .fm-desc
  // 行，不新增卡内纵行（88px 卡纵向不可加行）。
  const loopPhase = (st === 'running' && n.loopPhase && n.loop && typeof n.loop === 'object')
    ? esc(t(`flowmap.loopPhase.${n.loopPhase === 'verify' ? 'verify' : 'worker'}`)) : '';
  const desc = descText
    ? `<div class="fm-desc" title="${esc(descText)}">${esc(descText.slice(0, 46))}${descText.length > 46 ? '…' : ''}</div>`
    : (st === 'running'
        ? `<div class="fm-desc running">${loopPhase || esc(t('flowmap.cardRunning'))}</div>`
        : '');
  // 等待脚注（deps 设计 §1.4）：pending/wiring 且持有 in/deps → 列出全部等待对象
  //（in = 等结果投递，deps = 等完成信号；上游已归档 → i18n 纯文字诚实降级，
  //  原 ⏳ 图标按裁定①换内联 SVG 沙漏，文字单独 ellipsis 截断）
  const waitParts = (st === 'pending' || st === 'wiring')
    ? [...(n.in || []), ...(n.deps || [])].map((id) => nameOf ? nameOf(id) : id)
    : [];
  const waitNote = waitParts.length
    ? `<div class="fm-wait-note" title="${esc(`${t('flowmap.waitingFor')}: ${waitParts.join(' · ')}`)}">`
      + `${FM_WAIT_ICON}<span class="fm-wait-note-text">${esc(t('flowmap.waitingFor'))}: ${esc(waitParts.join(' · '))}</span></div>`
    : '';
  // Agent 退役（node-flowmap-slim：节点恒 general，卡上无信息量）——副行改显节点
  // 元数据：preset 常显（未配置 → 克制空态「默认预设」），plugins 有则以
  // `preset · p1, p2` 同行合显、无则零空态段。单行口径与归档成员行/详情窗 meta
  // 一致；88px 固定卡纵向不可加行（独占行实测溢出加剧），复用 .solar-node-sub。
  const presetText = n.preset || t('flowmap.presetDefault');
  const pluginsText = (n.plugins || []).join(', ');
  const subTitle = pluginsText ? `${presetText} · ${pluginsText}` : presetText;
  return `
    <div class="solar-node fm-node ${cls}${term ? ' terminal' : ''}" data-node-id="${esc(n.id)}"
         data-status="${esc(st)}" tabindex="0" title="${esc(n.name)} · ${esc(subTitle)}${term && FM_NODE_ST_KEY[st] ? `（${esc(t(FM_NODE_ST_KEY[st]))}）` : ''}" style="left:${left.toFixed(1)}px;top:${top.toFixed(1)}px">
      <div class="solar-orbit">
        <div class="solar-ring ring-1"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
        <div class="solar-ring ring-2"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
        <div class="solar-ring ring-3"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>
      </div>
      <div class="fm-node-head">${worktreeBadge}${flags}${statusIcon}${statusWord}</div>
      <div class="solar-node-label" title="${esc(n.name)}">${esc(n.name)}</div>
      <div class="solar-node-sub">${esc(subTitle)}</div>
      ${st === 'pending' && (n.in || []).length > 1 ? `<div class="fm-barrier-hint">barrier ×${(n.in || []).length}</div>` : ''}
      ${waitNote}
      ${desc}
    </div>`;
}

// ── 边（SVG，复用 .flow-edge）─────────────────────────────
// 边的语义按 §2.7 的投递状态分三档，而不是 flow-run 的"正在流动"动画：
//   delivered  上游 completed → 结果已沿这条边投递（实线、加重）
//   inflight   上游 running   → 结果尚未产生（虚线行军蚁 = 等这条线出结果）
//   idle       其余（wiring/pending 上游）→ 静止细线
function edgeStateOf(n) {
  if (n.status === 'completed') return 'delivered';
  if (n.status === 'running') return 'inflight';
  return 'idle';
}

/** 边只画在「可见」节点之间（fm 已是可见派生视图，§3.1）。三态语义按 §2.7：
 *  delivered/inflight/idle。deps 边按下游 n.deps 反向渲染（上游→下游画箭头，
 *  deps 设计 §1.4），边 id 用 `~>` 与输出边 `=>` 区分。已归档链（整链消失）的
 *  上游不画边、不画锚点（v3 锚点废除，反馈②；等待脚注 i18n「已归档」文字兜底）。
 *  v3 补 in 边代理渲染（规格 §3.3）：barrier 输入在上游 out 未指向本节点时补画
 *  同语言边（源=上游卡，态=上游状态三态）——「边天然连着」，out 已覆盖不双画。 */
function collectEdges(fm, positions) {
  const vis = visibleNodes(fm);
  const ids = new Set(vis.map((n) => n.id));
  const byId = new Map(vis.map((n) => [n.id, n]));
  const edges = new Map();
  for (const n of vis) {
    if (!n.out || n.out === 'Nebula' || !ids.has(n.out)) continue;
    const from = positions[n.id];
    const to = positions[n.out];
    if (!from || !to) continue;
    edges.set(`${n.id}=>${n.out}`, {
      x1: from.x, y1: from.y, x2: to.x, y2: to.y, state: edgeStateOf(n),
    });
  }
  for (const n of vis) {
    for (const up of (n.in || [])) {
      if (!ids.has(up)) continue;
      const key = `${up}=>${n.id}`;
      if (edges.has(key)) continue; // 上游 out 已指向本节点 → 不双画
      const from = positions[up];
      const to = positions[n.id];
      if (!from || !to) continue;
      edges.set(key, {
        x1: from.x, y1: from.y, x2: to.x, y2: to.y,
        state: edgeStateOf(byId.get(up)),
      });
    }
  }
  for (const n of vis) {
    for (const d of n.deps || []) {
      if (!ids.has(d)) continue;
      const from = positions[d];
      const to = positions[n.id];
      if (!from || !to) continue;
      edges.set(`${d}~>${n.id}`, {
        x1: from.x, y1: from.y, x2: to.x, y2: to.y,
        state: depsEdgeStateOf(byId.get(d)),
        kind: 'deps',
      });
    }
  }
  return edges;
}

/** deps 边三态（deps 设计 §1.4 视觉三重编码之一）：CSS 另叠加虚线线型（类型编码①）
 *  与空心小箭头（类型编码②），不依赖单一色觉通道，亮暗主题经 CSS 变量自适应。
 *   deps-met    上游 completed → 依赖已满足（绿 --color-success）
 *   deps-wait   上游 running   → 依赖等待（橙 --color-warning）
 *   deps-unmet  其余（wiring/pending/failed/cancelled/blocked/归档隐藏）→ 未满足（灰 --color-border） */
function depsEdgeStateOf(up) {
  const st = up?.status || 'pending';
  if (st === 'completed') return 'deps-met';
  if (st === 'running') return 'deps-wait';
  return 'deps-unmet';
}

/** 边 DOM class（含 deps 类型区分，applyEdgeDiff 与全量渲染共用同一拼接）。 */
function edgeClassOf(e) {
  return e.kind === 'deps'
    ? { path: `flow-edge fm-edge fm-edge-deps ${e.state}`, arrow: `flow-edge-arrow fm-edge-arrow fm-edge-deps-arrow ${e.state}` }
    : { path: `flow-edge fm-edge ${e.state}`, arrow: `flow-edge-arrow fm-edge-arrow ${e.state}` };
}

/** 贝塞尔路径（g 局部坐标）：端点插值动画与全量渲染共用同一形状函数。 */
function edgePathD(p) {
  const midY = (p.y1 + p.y2) / 2;
  return `M ${p.x1.toFixed(1)} ${p.y1.toFixed(1)} C ${p.x1.toFixed(1)} ${midY.toFixed(1)} ${p.x2.toFixed(1)} ${midY.toFixed(1)} ${p.x2.toFixed(1)} ${p.y2.toFixed(1)}`;
}

function edgesSvg(fm, positions, width, height) {
  const paths = Array.from(collectEdges(fm, positions)).map(([id, e]) => {
    const cls = edgeClassOf(e);
    return `
      <path class="${cls.path}" data-edge-id="${esc(id)}" d="${edgePathD(e)}"/>
      <circle class="${cls.arrow}" data-edge-id="${esc(id)}" cx="${e.x2.toFixed(1)}" cy="${e.y2.toFixed(1)}" r="3"/>`;
  }).join('');
  return `<svg class="solar-edges" width="${width}" height="${height}"><g transform="translate(${(width / 2).toFixed(1)},${PAD})">${paths}</g></svg>`;
}

// ── 图例（deps 设计 §1.4 六档 + v3 终态保留卡）：i18n 键 flowmap.legend.* ──
function legendHtml() {
  const item = (swatchCls, key) =>
    `<span class="fm-legend-item"><span class="fm-legend-swatch ${swatchCls}" aria-hidden="true"></span>${esc(t(key))}</span>`;
  return `
    <div class="flowmap-legend" data-testid="fm-legend">
      ${item('out delivered', 'flowmap.legend.outDelivered')}
      ${item('out inflight', 'flowmap.legend.outWaiting')}
      ${item('out idle', 'flowmap.legend.outIdle')}
      ${item('deps deps-met', 'flowmap.legend.depsMet')}
      ${item('deps deps-wait', 'flowmap.legend.depsWaiting')}
      ${item('deps deps-unmet', 'flowmap.legend.depsUnmet')}
      ${item('terminal', 'flowmap.legend.terminalRetained')}
    </div>`;
}

// ── 可见集（P0 单源收口）──────────────────────────────
// fmByProject 缓存的是后端权威全量快照；渲染管线只吃「可见派生视图」：
//   可见 = 快照成员本身（归档资格判定唯一在后端——sweep 出库即 nodeRemoved 广播，
//   增量管线逐卡淡出；前端不再持有任何已归档/到期排除集）。
// P3 视图过滤（2026-09-08）：可见性之上叠加 passesViewFilter——「进行中」态额外
//   隐藏终态/blocked 卡。纯派生，不回写缓存、不改归档语义。
function visibleFmView(project, fm) {
  if (!fm) return fm;
  const nodes = (fm.nodes || []).filter((n) => passesViewFilter(n));
  return nodes.length === (fm.nodes || []).length ? fm : { ...fm, nodes };
}

/** 渲染管线输入的 fm 已是可见视图（visibleFmView 单点过滤），此处恒等。 */
function visibleNodes(fm) {
  return fm?.nodes || [];
}

// ── 视图过滤控件（P3）：共享开关 .nb-toggle（toggle.js / sidebar.css，玻璃材质
//   token 全复用，零新色值）。就地视图挂进 .flowmap-nav-bar（返回钮与摘要槽之间，
//   幂等插入）；legacy 独立标签页无 nav-bar → 由 renderFlowMap 的 card-header
//   模板内嵌。两种形态共用同一 localStorage 态，翻转即全量视图重渲。 ──

/** @returns {string} 控件 HTML（label + nb-toggle 开关；on=进行中，off=全部）。 */
function viewFilterControlHtml() {
  return `<span class="flowmap-view-filter" data-testid="fm-view-filter" title="${esc(t('flowmap.viewFilter.hint'))}">`
    + `<span class="flowmap-view-filter-label">${esc(t('flowmap.viewFilter.activeOnly'))}</span>`
    + toggleHTML({
      on: viewFilterActiveOnly(),
      label: t('flowmap.viewFilter.activeOnly'),
      title: t('flowmap.viewFilter.hint'),
      attrs: ' data-testid="fm-view-filter-toggle"',
    })
    + `</span>`;
}

/** 翻转处理：持久化 → 同步所有开关实例 → 以缓存快照重渲全部打开的 Flow Map 视图
 *  （diff 管线承担节点增删过渡，无缓存时回落全量拉取）。 */
function onViewFilterChange(_el, on) {
  try { localStorage.setItem(VIEW_FILTER_KEY, on ? '1' : '0'); } catch (e) { /* 隐私模式等：会话态兜底 */ }
  document.querySelectorAll('.flowmap-view-filter .nb-toggle').forEach((el) => setToggleState(el, on));
  for (const { project, pane } of openFlowMapPanes()) {
    const scroll = pane.querySelector('.team-scroll') || pane.querySelector('.flowmap-view-body');
    if (!scroll) continue;
    const fm = fmByProject.get(project);
    if (fm) renderFlowMap(scroll, visibleFmView(project, fm), project);
    else renderFlowMapInto(scroll, project);
  }
}

/** 就地视图：把控件挂进 nav-bar（幂等；pane 重建后下次渲染自动补挂并收敛状态）。 */
function ensureViewFilterControl(paneEl) {
  const navBar = paneEl ? paneEl.querySelector('.flowmap-nav-bar') : null;
  if (!navBar) return;
  const existing = navBar.querySelector('.flowmap-view-filter');
  if (!existing) {
    navBar.querySelector('.flowmap-back-btn')?.insertAdjacentHTML('afterend', viewFilterControlHtml());
  } else {
    setToggleState(existing.querySelector('.nb-toggle'), viewFilterActiveOnly());
  }
  bindToggle(navBar, onViewFilterChange);
}

// ── header 摘要：HTML 转义版（innerHTML）与纯文本版（textContent 增量更新）共用 ──
// 计数口径（规格 §3.1）：running/wait/done/fail 继续统计全量节点（含已归档链成员
// 的终态总数）；v3 补「链未齐终态保留 {n}」= 可见集中的终态保留卡数。
function summaryParts(project, fm) {
  const nodes = fmByProject.get(project)?.nodes || (fm?.nodes || []);
  const running = nodes.filter((n) => n.status === 'running').length;
  const failed = nodes.filter((n) => n.status === 'failed').length;
  const pending = nodes.filter((n) => n.status === 'pending').length;
  const blocked = nodes.filter((n) => n.status === 'blocked').length;
  const completed = nodes.filter((n) => n.status === 'completed').length;
  const retained = (fm?.nodes || []).filter((n) => isTerminalStatus(n.status)).length;
  const parts = [];
  if (running) parts.push(`${running} ${t('flowmap.run')}`);
  if (pending) parts.push(`${pending} ${t('flowmap.wait')}`);
  if (blocked) parts.push(`${blocked} ${t('flowmap.blocked')}`);
  if (failed) parts.push(`${failed} ${t('flowmap.fail')}`);
  if (completed) parts.push(`${completed} ${t('flowmap.done')}`);
  if (retained) parts.push(t('flowmap.retained', { n: String(retained) }));
  return parts;
}

function summarizeHeader(project, fm) {
  const parts = summaryParts(project, fm);
  return parts.length ? parts.map(esc).join(' · ') : esc(t('flowmap.idle'));
}

function summaryText(project, fm) {
  const parts = summaryParts(project, fm);
  return parts.length ? parts.join(' · ') : t('flowmap.idle');
}

// ══ 增量动画（rAF 插值）══════════════════════════════════
// 边端点跟随 + svg g 平移用同一 rAF 泵驱动；节点位移由 CSS left/top 过渡承担。

/** 进行中的边端点插值：edgeId → {path, circle, from, to, start}（重入时从当前插值
 *  位置续跑，快速连发事件不跳变）。 */
const edgeFlights = new Map();
/** 进行中的 g 平移（画布宽度变化 → translate(width/2) 跟随）：{g, from, to, start} */
const gFlights = new Set();
let flightRaf = 0;

function lerpPts(a, b, k) {
  return {
    x1: a.x1 + (b.x1 - a.x1) * k,
    y1: a.y1 + (b.y1 - a.y1) * k,
    x2: a.x2 + (b.x2 - a.x2) * k,
    y2: a.y2 + (b.y2 - a.y2) * k,
  };
}

function edgeFlightNow(f, now) {
  const t = Math.min(1, (now - f.start) / MOVE_MS);
  return lerpPts(f.from, f.to, easeInOutCubic(t));
}

function pumpFlights(now) {
  flightRaf = 0;
  let live = false;
  for (const [id, f] of Array.from(edgeFlights)) {
    if (!f.path.isConnected) { edgeFlights.delete(id); continue; }
    const t = Math.min(1, (now - f.start) / MOVE_MS);
    const p = lerpPts(f.from, f.to, easeInOutCubic(t));
    f.path.setAttribute('d', edgePathD(p));
    if (f.circle) {
      f.circle.setAttribute('cx', p.x2.toFixed(1));
      f.circle.setAttribute('cy', p.y2.toFixed(1));
    }
    if (t >= 1) edgeFlights.delete(id); else live = true;
  }
  for (const f of Array.from(gFlights)) {
    if (!f.g.isConnected) { gFlights.delete(f); continue; }
    const t = Math.min(1, (now - f.start) / MOVE_MS);
    const tx = f.from + (f.to - f.from) * easeInOutCubic(t);
    f.g.setAttribute('transform', `translate(${tx.toFixed(1)},${PAD})`);
    if (t >= 1) gFlights.delete(f); else live = true;
  }
  if (live) flightRaf = requestAnimationFrame(pumpFlights);
}

function ensureFlightPump() {
  if (!flightRaf && (edgeFlights.size + gFlights.size) > 0) {
    flightRaf = requestAnimationFrame(pumpFlights);
  }
}

function startEdgeFlights(list) {
  if (prefersReducedMotion()) {
    for (const it of list) {
      it.path.setAttribute('d', edgePathD(it.to));
      if (it.circle) {
        it.circle.setAttribute('cx', it.to.x2.toFixed(1));
        it.circle.setAttribute('cy', it.to.y2.toFixed(1));
      }
    }
    return;
  }
  const now = performance.now();
  for (const it of list) {
    const cur = edgeFlights.get(it.id);
    const from = cur ? edgeFlightNow(cur, now) : it.from;
    edgeFlights.set(it.id, { path: it.path, circle: it.circle, from, to: it.to, start: now });
  }
  ensureFlightPump();
}

function animateGTranslate(g, fromTx, toTx) {
  const setFinal = () => g.setAttribute('transform', `translate(${toTx.toFixed(1)},${PAD})`);
  if (prefersReducedMotion() || Math.abs(fromTx - toTx) < 0.5) { setFinal(); return; }
  const now = performance.now();
  const prev = Array.from(gFlights).find((f) => f.g === g);
  let from = fromTx;
  if (prev) {
    const t = Math.min(1, (now - prev.start) / MOVE_MS);
    from = prev.from + (prev.to - prev.from) * easeInOutCubic(t); // 续跑：不回到起点
    gFlights.delete(prev);
  }
  gFlights.add({ g, from, to: toTx, start: now });
  ensureFlightPump();
}

/** 新边生长动画：dashoffset 从路径长度过渡到 0（画线生长），结束后清掉内联
 *  dash/animation 让状态档位（inflight 行军蚁等）的类样式接管。 */
function animateEdgeEnter(path, circle) {
  if (prefersReducedMotion()) return;
  let len = 0;
  try { len = path.getTotalLength(); } catch (_) { /* 未渲染（隐藏 pane）时量不到 */ }
  if (!Number.isFinite(len) || len <= 0) return;
  path.style.animation = 'none'; // 压掉 inflight 的 stroke-dashoffset 动画（同属性冲突）
  path.style.strokeDasharray = String(len);
  path.style.strokeDashoffset = String(len);
  path.getBoundingClientRect(); // 强制样式生效，过渡从这里起步
  path.style.transition = 'stroke-dashoffset 0.45s cubic-bezier(0.33, 0, 0.2, 1)';
  path.style.strokeDashoffset = '0';
  if (circle) {
    circle.style.transition = 'opacity 0.4s ease';
    circle.style.opacity = '0';
    requestAnimationFrame(() => { circle.style.opacity = ''; });
  }
  setTimeout(() => {
    path.style.animation = '';
    path.style.strokeDasharray = '';
    path.style.strokeDashoffset = '';
    path.style.transition = '';
    if (circle) circle.style.transition = '';
  }, 520);
}

function animateEdgeExit(path, circle) {
  path.classList.add('fm-edge-exit'); // 标记：后续 diff 不再把它当作可复用元素
  if (prefersReducedMotion()) {
    path.remove();
    if (circle) circle.remove();
    return;
  }
  path.style.transition = 'opacity 0.3s ease';
  path.style.opacity = '0';
  if (circle) {
    circle.style.transition = 'opacity 0.3s ease';
    circle.style.opacity = '0';
  }
  setTimeout(() => {
    path.remove();
    if (circle) circle.remove();
  }, 340);
}

function animateNodeEnter(el) {
  if (prefersReducedMotion()) return;
  el.classList.add('fm-enter');
  el.getBoundingClientRect(); // 强制布局，确保过渡从入场态起步
  requestAnimationFrame(() => el.classList.remove('fm-enter'));
}

function animateNodeExit(el) {
  if (prefersReducedMotion()) { el.remove(); return; }
  el.classList.add('fm-exit');
  setTimeout(() => el.remove(), 380);
}

// ══ 增量 diff 渲染 ═══════════════════════════════════════

/** 节点卡片「内容」签名：参与 nodeHtml 渲染且增量期间会变化的字段。v3 起终态
 *  保留卡加入签名（terminal class + title 标注随状态切换原地重建）。deps 段与
 *  in barrier 提示同款（deps 设计 §1.4）：pending/wiring 且有 deps 显示等待脚注，
 *  deps 集变化即重渲。2026-09-05 载荷收敛：签名带 description/taskPreview（卡片
 *  展示字段），不再含 result（载荷无 result）。Agent 退役（node-flowmap-slim）：
 *  agent 不再上卡，签名改带 preset/plugins（副行展示字段，变更即重渲）。badge 批
 *  （2026-09-05）：merge/loop 徽标字段入签名——WS 载荷带上该字段时增量路径即重渲；
 *  pending 徽标随 st（已在签名）变化。 */
function nodeContentKey(n) {
  if (!n) return '∅';
  const st = n.status || 'pending';
  return [
    st,
    n.name || '',
    n.preset || '',
    (n.plugins || []).join(','),
    n.hasWorktree || n.worktree ? 1 : 0,
    n.worktree || '',
    n.merge === true ? 1 : 0,
    n.loop === true || (n.loop && typeof n.loop === 'object') ? 1 : 0,
    n.loopRound || 0,
    n.loopPhase || '',
    n.loopLastVerdict || '',
    st === 'pending' && (n.in || []).length > 1 ? (n.in || []).length : 0,
    st === 'pending' || st === 'wiring' ? (n.deps || []).length : 0,
    n.description || n.taskPreview || '',
  ].join('|');
}

/** 就地更新节点卡片内容，但保留 .solar-orbit——轨道旋转由 flowAnim.js 的 rAF
 *  以 inline transform 逐帧驱动（状态按 pane|nodeId 键控续接）。移植 orbit 以外
 *  子节点使 .solar-dot-wrap 元素及其 inline transform 原地保留：rAF 的键控状态
 *  st.el === el 继续成立，增量更新零打断、零重挂载（全量重建路径由 flowAnim 的
 *  keyed re-attach 兜底续角度）。根 class/状态属性同步替换，rAF reconcile 据此
 *  感知 running→终态并执行滑行淡出。 */
function transplantNodeContent(el, n, pos, originX, nameOf) {
  const holder = document.createElement('div');
  holder.innerHTML = nodeHtml(n, pos, originX, nameOf);
  // firstElementChild is typed Element, but the parsed node here is always
  // the nodeHtml <div> — narrow so fresh.dataset typechecks.
  const fresh = /** @type {HTMLElement|null} */ (holder.firstElementChild);
  if (!fresh) return;
  const orbit = el.querySelector('.solar-orbit');
  Array.from(el.children).forEach((child) => { if (child !== orbit) child.remove(); });
  Array.from(fresh.children).forEach((child) => {
    if (child.classList && child.classList.contains('solar-orbit')) return;
    el.appendChild(child);
  });
  el.className = fresh.className;
  if (fresh.dataset.status !== undefined) el.dataset.status = fresh.dataset.status;
}

function applyNodeDiff(canvas, prevFm, fm, positions, width, projectName) {
  const prevById = new Map(visibleNodes(prevFm).map((n) => [n.id, n]));
  const vis = visibleNodes(fm);
  const originX = width / 2;
  const nameOf = nameResolverOf(projectName);
  const existing = new Map();
  canvas.querySelectorAll('.fm-node').forEach((el) => {
    existing.set(el.getAttribute('data-node-id'), el);
  });
  const seen = new Set();
  for (const n of vis) {
    seen.add(n.id);
    const pos = positions[n.id] || { x: 0, y: 0 };
    const left = `${(pos.x - NODE_W / 2 + originX).toFixed(1)}px`;
    const top = `${(pos.y - NODE_H / 2 + PAD).toFixed(1)}px`;
    let el = existing.get(n.id);
    if (el && el.classList.contains('fm-exit')) {
      el.remove(); // 快速删后又重建：不复用正在退场的元素（退场定时器随后空移除）
      el = null;
    }
    if (!el) {
      const holder = document.createElement('div');
      holder.innerHTML = nodeHtml(n, pos, originX, nameOf);
      el = holder.firstElementChild;
      if (!el) continue;
      el.addEventListener('click', (e) => {
        e.stopPropagation(); // §5.10：点节点 = 选择（开详情），不触发空白收起
        camFocusNodeEl(el); // N3/C6：选中即相机聚焦（居中 + ≥1.6 倍）
        openNodeDetail(projectName, n.id);
      });
      canvas.appendChild(el);
      animateNodeEnter(el);
      continue;
    }
    // 位移：CSS left/top 过渡平滑滑动（同曲线同时长的边端点插值由 rAF 负责）
    if (el.style.left !== left) el.style.left = left;
    if (el.style.top !== top) el.style.top = top;
    if (nodeContentKey(prevById.get(n.id)) !== nodeContentKey(n)) {
      transplantNodeContent(el, n, pos, originX, nameOf);
    }
  }
  for (const [id, el] of existing) {
    if (!seen.has(id)) animateNodeExit(el);
  }
}

function applyEdgeDiff(g, prevEdges, edges) {
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const paths = new Map();
  const circles = new Map();
  g.querySelectorAll('path[data-edge-id]').forEach((p) => {
    if (!p.classList.contains('fm-edge-exit')) paths.set(p.getAttribute('data-edge-id'), p);
  });
  g.querySelectorAll('circle[data-edge-id]').forEach((c) => {
    if (!c.classList.contains('fm-edge-exit')) circles.set(c.getAttribute('data-edge-id'), c);
  });
  const flights = [];
  for (const [id, e] of edges) {
    let p = paths.get(id);
    let c = circles.get(id) || null;
    if (!p) {
      const cls = edgeClassOf(e);
      p = document.createElementNS(SVG_NS, 'path');
      p.setAttribute('class', cls.path);
      p.setAttribute('data-edge-id', id);
      p.setAttribute('d', edgePathD(e));
      g.appendChild(p);
      if (!c) {
        c = document.createElementNS(SVG_NS, 'circle');
        c.setAttribute('class', cls.arrow);
        c.setAttribute('data-edge-id', id);
        c.setAttribute('cx', e.x2.toFixed(1));
        c.setAttribute('cy', e.y2.toFixed(1));
        c.setAttribute('r', '3');
        g.appendChild(c);
      }
      animateEdgeEnter(p, c);
      continue;
    }
    const prevE = prevEdges.get(id);
    if (prevE && prevE.state !== e.state) {
      // 状态档位变化：颜色/线型交由 CSS transition 平滑（flowMap.css .fm-edge 过渡）；
      // deps 类型 class 同步保留（edgeClassOf 单点拼接）
      const cls = edgeClassOf(e);
      p.setAttribute('class', cls.path);
      if (c) c.setAttribute('class', cls.arrow);
    }
    const moved = !prevE || prevE.x1 !== e.x1 || prevE.y1 !== e.y1
      || prevE.x2 !== e.x2 || prevE.y2 !== e.y2;
    if (!moved) continue;
    if (prevE) {
      flights.push({ id, path: p, circle: c, from: prevE, to: e });
    } else {
      p.setAttribute('d', edgePathD(e));
      if (c) {
        c.setAttribute('cx', e.x2.toFixed(1));
        c.setAttribute('cy', e.y2.toFixed(1));
      }
    }
  }
  for (const [id, p] of paths) {
    if (!edges.has(id)) animateEdgeExit(p, circles.get(id) || null);
  }
  if (flights.length) startEdgeFlights(flights);
}

/** 增量更新已渲染的图（不做 innerHTML 替换）。baseline 是上一帧的快照——通常取
 *  renderedFmByContainer；TTL 到期路径传「回拨 1 秒」的克隆（ticker 原地改写缓存，
 *  基线与新快照同引用时 diff 无从对比，见 tickTtl）。 */
function renderFlowMapDiff(container, baseline, fm, projectName) {
  const canvas = container.querySelector('.solar-canvas');
  if (!canvas) return false;
  const svg = canvas.querySelector('svg.solar-edges');
  const g = svg ? svg.querySelector('g') : null;
  if (!svg || !g) return false;
  if (visibleNodes(fm).length === 0 || visibleNodes(baseline).length === 0) return false; // 图⇄空态走全量
  const prevLayout = layoutNodes(baseline);
  const { positions, width, height } = layoutNodes(fm);

  // 画布尺寸：节点增删导致重排时宽度连续过渡（.flowmap-card .solar-canvas transition），
  // 居中 margin-auto 的偏移随之连续，配合节点 left 过渡整图不跳。
  canvas.style.width = `${width}px`;
  canvas.style.height = `${height}px`;
  svg.setAttribute('width', String(width));
  svg.setAttribute('height', String(height));
  animateGTranslate(g, prevLayout.width / 2, width / 2);

  applyNodeDiff(canvas, baseline, fm, positions, width, projectName);
  applyEdgeDiff(g, collectEdges(baseline, prevLayout.positions), collectEdges(fm, positions));

  // 摘要增量（2026-09-06 顶栏合并批）：就地视图摘要已迁入 nav-bar（view-body 之外），
  // 查找提升到 pane 口径；legacy 独立标签页摘要仍在 container 内 card-header——
  // pane 级 querySelector 两种形态通吃（pane 无 nav-bar 时命中的就是容器内那条）。
  const paneEl = container.closest('.canvas-tab-pane');
  const summary = (paneEl || container).querySelector('.flowmap-summary');
  const txt = summaryText(projectName, fm);
  if (summary && summary.textContent !== txt) summary.textContent = txt;
  container.dataset.fmState = 'nodes';
  container.dataset.fmVisible = String(visibleNodes(fm).length);
  container.dataset.fmTotal = String((fmByProject.get(projectName)?.nodes || []).length);
  // 邻接 map 随边集重建（hover 强调数据源）+ autoFit 态布局变化跟随重算 fit（C1，
  // 280ms 相机动画；userNav 不动——用户导航位挂起语义）
  const vp = canvas.parentElement;
  if (vp && vp.classList.contains('fm-viewport')) {
    rebuildAdjacency(canvas, fm, collectEdges(fm, positions));
    const cam = camByViewport.get(vp);
    if (cam && cam.autoFit) {
      const fit = camFit(vp, canvas, cam);
      if (fit) { camByViewport.set(vp, fit); camApply(vp, canvas, fit, true); }
    }
  }
  return true;
}

/** 全量渲染后让整图「长出来」（图⇄空态切换走全量路径时的入场动画）。 */
function animateAllIn(container) {
  if (prefersReducedMotion()) return;
  container.querySelectorAll('.fm-node').forEach((el) => animateNodeEnter(el));
  container.querySelectorAll('path[data-edge-id]').forEach((p) => {
    const next = p.nextElementSibling;
    animateEdgeEnter(p, next && next.tagName.toLowerCase() === 'circle' ? next : null);
  });
}

/** 渲染 Flow Map 内容（header + solar 图 / 空态）进给定容器。导出供 projectTab
 *  在 projects 标签页内就地渲染（同标签页切换视图）。
 *
 *  两条路径：容器里已有渲染好的图（且新旧都有可见节点）→ 增量 diff（带过渡动画，
 *  不重建 DOM）；否则全量 innerHTML（首渲 / 图⇄空态切换）。opts.animateAll 让
 *  全量路径也带入场动画（WS 事件把图从空态唤起时用）。fm 必须是可见派生视图
 *  （visibleFmView），渲染后同步归档悬浮层（§4/§5）。 */
export function renderFlowMap(container, fm, projectName, opts = {}) {
  const rawNodes = fmByProject.get(projectName)?.nodes || [];
  const total = rawNodes.length;
  const nodes = visibleNodes(fm);
  // P3 视图过滤控件：diff/全量两路径都保证 nav-bar 开关已挂载且状态收敛
  // （pane 重建/恢复后首帧即补挂）。
  ensureViewFilterControl(container.closest('.canvas-tab-pane'));
  const prev = renderedFmByContainer.get(container);
  if (prev && nodes.length > 0 && visibleNodes(prev).length > 0
      && renderFlowMapDiff(container, prev, fm, projectName)) {
    renderedFmByContainer.set(container, fm);
    syncArchiveUi(container, projectName);
    return;
  }
  const { positions, width, height } = layoutNodes(fm);
  const nameOf = nameResolverOf(projectName);
  const nodesHtml = nodes.map((n) => nodeHtml(n, positions[n.id] || { x: 0, y: 0 }, width / 2, nameOf)).join('');
  // 空态三分（旧版一律"暂无节点，项目空闲"，把「未挂载」「已归档」两种
  // 有数据的情况说成没数据 —— qa 取证「后端有 3 节点、视图显示暂无节点」即此）。
  // v3（§7.3）：图空但有已归档链 → 「全部节点已完成，结果收入右上角归档」，
  // 悬浮钮保持可用（主图与面板可同时为「空图 + 有条目」）。
  // P3 补第四态：「进行中」过滤把未归档终态卡全部隐藏时，不说「已全部归档」
  // （死链终态并未归档）——显式告知是视图过滤所致。
  const hiddenByFilter = viewFilterActiveOnly()
    && rawNodes.some((n) => !passesViewFilter(n));
  const emptyMsg = fm?.notMounted ? t('flowmap.notMounted')
    : total > 0 ? (hiddenByFilter ? t('flowmap.viewFilter.noneActive') : t('flowmap.archive.allArchived'))
    : t('flowmap.empty');
  const state = fm?.notMounted ? 'not-mounted' : nodes.length > 0 ? 'nodes' : total > 0 ? 'archived' : 'empty';
  container.dataset.fmState = state;
  container.dataset.fmVisible = String(nodes.length);
  container.dataset.fmTotal = String(total);
  // 顶栏合并（2026-09-06）：就地视图（projects 标签页 nav-bar 右侧已有摘要槽）→
  // 摘要写进 nav-bar，body 内不再渲染头部条（项目名 title 已退役，返回钮承担）；
  // legacy 独立 flow-map 标签页无 nav-bar → 保留摘要条回落（仅摘要、无 title）。
  const paneEl = container.closest('.canvas-tab-pane');
  const navSummary = paneEl ? paneEl.querySelector('.flowmap-nav-bar .flowmap-summary') : null;
  container.innerHTML = `
    ${navSummary ? '' : `<div class="flowmap-card-header">
      ${viewFilterControlHtml()}
      <div class="flowmap-summary">${summarizeHeader(projectName, fm)}</div>
    </div>`}
    ${nodes.length === 0
      ? `<div class="dag-empty"><div class="hint">${esc(emptyMsg)}</div></div>`
      : `<div class="solar-card flowmap-card">
          <div class="fm-viewport">
            <div class="solar-canvas" style="width:${width}px;height:${height}px">
              ${edgesSvg(fm, positions, width, height)}
              ${nodesHtml}
            </div>
          </div>
          ${legendHtml()}
        </div>`}
    `;
  if (navSummary) navSummary.innerHTML = summarizeHeader(projectName, fm);
  renderedFmByContainer.set(container, fm);
  const vp = container.querySelector('.fm-viewport');
  const canvas = container.querySelector('.solar-canvas');
  if (vp && canvas) {
    bindCamera(vp, canvas);
    bindHover(canvas, projectName);
    rebuildAdjacency(canvas, fm, collectEdges(fm, positions));
  }
  bindFlowMapClicks(container, projectName);
  if (!navSummary) bindToggle(container, onViewFilterChange); // legacy card-header 内嵌开关
  if (opts.animateAll) animateAllIn(container);
  syncArchiveUi(container, projectName);
  import('./utils.js').then(({ createIconsIn }) => createIconsIn(container));
}

function bindFlowMapClicks(container, projectName) {
  container.querySelectorAll('.fm-node').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.stopPropagation(); // §5.10：点节点 = 选择（开详情），不触发空白收起
      camFocusNodeEl(el); // N3/C6：选中即相机聚焦（居中 + ≥1.6 倍）
      openNodeDetail(projectName, el.getAttribute('data-node-id') || '', container);
    });
  });
  // U1 点击链高亮（作者裁定③）：捕获相位挂容器级委托——节点自身的 click 监听会
  // stopPropagation，冒泡相位收不到；捕获相位先于节点监听执行，语义稳定。
  const canvas = container.querySelector('.solar-canvas');
  if (canvas && canvas.dataset.fmChainPickBound !== '1') {
    canvas.dataset.fmChainPickBound = '1';
    container.addEventListener('click', (e) => {
      if (!canvas.isConnected) return;
      const nEl = /** @type {HTMLElement} */ (e.target).closest?.('.fm-node');
      const id = nEl ? nEl.getAttribute('data-node-id') || '' : '';
      if (id) applyChainPick(canvas, id);
      else clearChainPick(canvas); // 点空白 = 取消选择（与既有「空白点收起面板」同拍）
    }, true);
  }
}

// 节点结果详情（§5.8）：v3 起走右侧详情面板（flowMapArchive，z 70 浮前 +
// 宽视口 dock-left 并排），主图活动卡与链未齐终态保留卡同入口。
function openNodeDetail(projectName, nodeId, container) {
  if (container) {
    openDetailFor(container, projectName, nodeId);
    return;
  }
  // 无容器上下文（理论不达，卡片点击均带容器）→ 兜底找可见视图。
  for (const { project, pane } of openFlowMapPanes()) {
    if (project !== projectName) continue;
    const scroll = pane.querySelector('.team-scroll') || pane.querySelector('.flowmap-view-body');
    if (scroll) { openDetailFor(scroll, projectName, nodeId); return; }
  }
}

// v3 死代码清理（规格 §7.6）：主图不再出现终态驻留卡——tickTtl 主图 TTL 倒计时
// ticker、卡片 fm-ttl 徽标、fmtTtl、「到期淡出」基线回拨全部移除；nodeRemoved
// 职责收缩为出库墓碑（flowMapArchive.recordNodeRemoved，§7.2）。
// ── 标签页打开 / 渲染 ────────────────────────────────────

/** 独立标签页打开（legacy 路径：canvas-tab-restore 恢复的旧 flow-map 标签页仍走这里）。
 *  项目面板点击项目不再调此函数——改为 projects 标签页内就地视图（见 projectTab.js）。 */
export function openFlowMapTab(projectName) {
  if (!projectName) return;
  openTab(`flow-map-${projectName}`, projectName, { type: 'flow-map', closable: true, pinned: true });
  renderFlowMapTab(projectName);
}

/** 把某项目的 Flow Map 渲染进任意容器（projects 标签页就地视图与 legacy 标签页共用）。
 *  双代防陈旧：seq 管 fetch 对 fetch 的先后；gen 管「fetch 在途时 WS 增量已写入缓存」
 *  ——此时这份响应相对缓存是旧的，丢弃并重新对账，否则旧快照会回滚增量状态。
 *  opts.highlightNodeId：渲染完成后滚动定位并闪烁高亮该节点（任务列表节点条目
 *  点击跳转入口，2026-09-02）。
 *  P0 播种：快照入缓存 → ingestNodes（活动节点 + chains 旁挂导入详情/链上下文
 *  数据源，零派生）→ TTL 面板兜底清理 → 可见派生视图渲染。 */
export function renderFlowMapInto(container, projectName, opts = {}) {
  if (!container) return;
  const seq = (seqByProject.get(projectName) || 0) + 1;
  seqByProject.set(projectName, seq);
  const genAtStart = genByProject.get(projectName) || 0;
  // 首渲加载态：以 fmState 判定（2026-09-06 顶栏合并后就地视图 body 内不再渲染
  // card-header，旧「无 header = 未渲染」判据失效）；refetch 不闪 loading。
  if (!container.dataset.fmState || container.dataset.fmState === 'loading'
      || container.dataset.fmState === 'error') {
    container.dataset.fmState = 'loading';
    container.innerHTML = `<div class="flowmap-loading">${esc(t('flowmap.loading'))}</div>`;
  }
  fetchFlowMap(projectName).then((fm) => {
    if (seqByProject.get(projectName) !== seq || !container.isConnected) return; // 过期响应丢弃
    if ((genByProject.get(projectName) || 0) !== genAtStart) {
      scheduleReconcile(projectName); // 在途期间有增量落进缓存 → 这份旧了，重拉收敛
      return;
    }
    bumpGen(projectName);
    fmByProject.set(projectName, fm);
    ingestNodes(projectName, fm.nodes, fm.chains);
    purgeExpired(projectName);
    renderFlowMap(container, visibleFmView(projectName, fm), projectName);
    if (opts.highlightNodeId) highlightFlowMapNode(container, projectName, opts.highlightNodeId);
  }).catch(() => {
    if (seqByProject.get(projectName) !== seq || !container.isConnected) return;
    container.dataset.fmState = 'error';
    container.innerHTML = `<div class="dag-empty"><div class="hint">${esc(t('flowmap.loadFail'))}</div></div>`;
  });
}

/** 高亮定位某节点（任务列表节点条目点击跳转）：相机聚焦居中并闪烁两轮状态环。
 *  固定视口下无滚动语义——scrollIntoView 会错误滚动 overflow:hidden 容器，改为
 *  相机 focusNode（v1 交互壳规格 §10⑦）。节点不存在（已归档/视图空态）时静默。 */
export function highlightFlowMapNode(container, projectName, nodeId) {
  if (!container || !nodeId) return;
  const el = container.querySelector(`.fm-node[data-node-id="${CSS.escape(String(nodeId))}"]`);
  if (!el) return;
  camFocusNodeEl(el);
  el.classList.remove('fm-node-flash');
  void el.offsetWidth; // 强制 reflow：连续点击也能重启动画
  el.classList.add('fm-node-flash');
  clearTimeout(el.__fmFlashTimer);
  el.__fmFlashTimer = setTimeout(() => el.classList.remove('fm-node-flash'), 2100);
}

function renderFlowMapTab(projectName) {
  const pane = getTabPane(`flow-map-${projectName}`);
  if (!pane) return;
  ensureFlowCss();
  const scroll = ensureScroll(pane, `flow-map-scroll-${projectName}`);
  renderFlowMapInto(scroll, projectName);
}

function ensureScroll(pane, id) {
  let scroll = pane.querySelector('.team-scroll');
  if (!scroll) {
    pane.innerHTML = '';
    scroll = document.createElement('div');
    scroll.className = 'team-scroll';
    scroll.id = id;
    pane.appendChild(scroll);
  }
  return scroll;
}

// 标签页关闭时清理该项目的状态（不影响项目数据，数据在服务端 flow-map.json）。
document.addEventListener('canvas-tab-closed', (/** @type {CustomEvent} */ e) => {
  const id = e.detail?.id || '';
  if (typeof id === 'string' && id.startsWith('flow-map-')) {
    const project = id.slice('flow-map-'.length);
    fmByProject.delete(project);
    seqByProject.delete(project);
    genByProject.delete(project);
    dropStore(project); // 归档 store 随视图生命周期（详情/面板数据源）
    const timer = reconcileTimers.get(project);
    if (timer) { clearTimeout(timer); reconcileTimers.delete(project); }
  }
});

// 标签页恢复：canvas.js 重建 pane 后派发 canvas-tab-restore——没有这条监听，
// 恢复出来的 flow-map 是个空壳（无样式、无内容），且 WS 事件也刷不出来。
window.addEventListener('canvas-tab-restore', (/** @type {CustomEvent} */ e) => {
  const id = e.detail?.id || '';
  if (typeof id === 'string' && id.startsWith('flow-map-')) {
    renderFlowMapTab(id.slice('flow-map-'.length));
  }
});

let refreshTimer = null;
/** WS 兜底全量刷新：只刷 legacy 独立 flow-map 标签页（服务端快照为权威）。
 *  projects 就地视图的兜底在 refreshFlowMapViews——增量路径的渲染由
 *  handleNodeWsEvent 直接 diff 两类视图。 */
export function refreshOpenFlowMap(project) {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    openFlowMapPanes()
      .filter(({ project: p, pane }) =>
        (!project || p === project) && (pane.dataset.tabId || '').startsWith('flow-map-'))
      .forEach(({ project: p }) => renderFlowMapTab(p));
  }, 200);
}

/** 刷新某项目（缺省=全部）所有打开的 Flow Map 视图：legacy 标签页 + projects 就地视图。
 *  断线重连后事件有缺口，全量拉权威快照；renderFlowMap 的 diff 保证快照与现渲染
 *  一致时不碰 DOM（无跳变）。 */
function refreshFlowMapViews(project) {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    openFlowMapPanes()
      .filter(({ project: p, pane }) =>
        (!project || p === project) && (pane.dataset.tabId || '').startsWith('flow-map-'))
      .forEach(({ project: p }) => renderFlowMapTab(p));
    const pp = getTabPane('projects');
    const ppProject = pp && pp.dataset ? pp.dataset.flowMapProject : '';
    if (pp && pp.dataset.projectsView === 'flow-map' && ppProject && (!project || ppProject === project)) {
      const body = pp.querySelector('.flowmap-view-body');
      if (body) renderFlowMapInto(body, ppProject);
    }
  }, 200);
}

// ── WS 事件 → 增量渲染（实时更新核心）─────────────────────

/** 对账拉取：增量应用后安排一次防抖全量拉取。服务端快照权威，事件丢失（重连缺口、
 *  广播时序）或 payload 漂移都在这里收敛；renderFlowMap 的 diff 保证无漂移时零 DOM
 *  变更（对账不可见）。 */
function scheduleReconcile(project) {
  const prev = reconcileTimers.get(project);
  if (prev) clearTimeout(prev);
  reconcileTimers.set(project, setTimeout(() => {
    reconcileTimers.delete(project);
    renderFlowMapTabIfNeeded(project);
  }, 600));
}

function renderFlowMapTabIfNeeded(project) {
  for (const { project: p, pane } of openFlowMapPanes()) {
    if (p !== project) continue;
    const scroll = pane.querySelector('.team-scroll') || pane.querySelector('.flowmap-view-body');
    if (scroll) renderFlowMapInto(scroll, project);
  }
}

/** 节点 WS 事件（{type, project, nodeId, node}）处理（P0 sweep 事实驱动）：
 *  1) 缓存可用（有快照、已挂载、payload 带节点身份）→ 并入快照 → ingestNodes
 *     （详情/链上下文数据源，零派生）→ 增量 diff 渲染（出库节点单卡 fm-exit
 *     淡出）→ 归档面板同步 → nodeRemoved 触发 /archive 防抖 refetch（面板条目
 *     到达反馈 = refetch 新链 id diff，见 flowMapArchive.fetchRemoteChains）
 *     → 600ms 对账兜底；
 *  2) 否则（无缓存 / 未挂载刚激活 / 视图处于非图状态 / 旧帧缺字段）→ 全量拉快照。
 *  归档资格/时机判定唯一在后端 sweep：前端不再判「链齐」（旧 refreshChains +
 *  整链同帧退场 animateChainExit 已删），退场 = 出库事实的增量消化。 */
function handleNodeWsEvent(msg) {
  const type = String(msg?.type || '');
  const project = msg?.project;
  if (!project) { refreshOpenFlowMap(); return; } // 旧帧无 project → 全量兜底
  const panes = openFlowMapPanes().filter((x) => x.project === project);
  if (panes.length === 0) return; // 该项目的图没开着：无事可做（打开时会重新拉取）
  const fm = fmByProject.get(project);
  const node = msg?.node;
  if (fm && !fm.notMounted && node && node.id) {
    const prevNode = (fm.nodes || []).find((n) => n.id === node.id) || null;
    if (type === 'nodeRemoved') recordNodeRemoved(project, prevNode || node);
    const nodes = (fm.nodes || []).filter((n) => n.id !== node.id);
    if (type !== 'nodeRemoved') nodes.push(node);
    const next = { ...fm, nodes, meta: { ...(fm.meta || {}), updatedAt: Date.now() } };
    bumpGen(project);
    fmByProject.set(project, next);
    ingestNodes(project, next.nodes, next.chains);
    renderFlowMapPanes(project, panes, next);
    for (const { pane } of panes) {
      const scroll = pane.querySelector('.team-scroll') || pane.querySelector('.flowmap-view-body');
      if (scroll) syncArchiveUi(scroll, project);
    }
    scheduleReconcile(project);
    return;
  }
  refreshFlowMapViews(project);
}

/** 增量渲染所有 pane 的可见派生视图（WS 事件常规路径）。 */
function renderFlowMapPanes(project, panes, fm) {
  for (const { pane } of panes) {
    const scroll = pane.querySelector('.team-scroll') || pane.querySelector('.flowmap-view-body');
    if (!scroll) continue;
    // 视图还没渲染出图（空态/加载态）→ 全量渲染并让新图入场；已在图态 → 纯增量。
    renderFlowMap(scroll, visibleFmView(project, fm), project,
      { animateAll: !scroll.querySelector('.solar-canvas') });
  }
}

// WS 事件驱动（契约 §2）：四类节点事件全部走增量管线。之前是「任何事件 → 全量
// 重拉 + innerHTML 整页替换」：没有动画、轨道旋转被打断、与其他渲染方互相覆盖。
import { onMessage, onReconnect } from './ws.js';
for (const evt of ['nodeCreated', 'nodeUpdated', 'nodeCompleted', 'nodeRemoved']) {
  onMessage(evt, handleNodeWsEvent);
}
// 断线期间的事件有缺口：重连后对所有打开的 Flow Map 视图拉权威快照（diff 保证
// 与现渲染一致时零 DOM 变更）。
onReconnect(() => refreshFlowMapViews());

// 视口尺寸变化（窗口缩放）：仅 autoFit 态重算 fit（userNav 保持用户导航位）。
let camResizeTimer = 0;
window.addEventListener('resize', () => {
  clearTimeout(camResizeTimer);
  camResizeTimer = setTimeout(() => {
    for (const { pane } of openFlowMapPanes()) {
      const vp = pane.querySelector('.fm-viewport');
      const canvas = vp?.querySelector('.solar-canvas');
      const cam = vp ? camByViewport.get(vp) : null;
      if (!vp || !canvas || !cam || !cam.autoFit) continue;
      const fit = camFit(vp, canvas, cam);
      if (fit) { camByViewport.set(vp, fit); camApply(vp, canvas, fit, false); }
    }
  }, 150);
});
