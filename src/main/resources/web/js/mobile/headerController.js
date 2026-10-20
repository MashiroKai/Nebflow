// mobile/headerController.js — v3「悬浮顶栏 + 双圆钮液态下拉列」（样张
// 20261020_214500 v3②③ 同款落地；mobile-mockup-375 v3 实施批）。
//
// 作者已批形态：
//   · 顶栏悬浮态：通栏玻璃底面 + 底缘分隔线退役（#header 手机层改透明悬浮层，
//     聊天内容直顶状态栏下滚动）；圆钮/头像/名称胶囊**各自悬浮** glass-control
//     （材质值 = sapphire.css --glass-control-* 既有 token，零新色值）。
//   · 液态下拉：大抽层弹层退役；圆钮自身向下拉长（**宽 40 恒定**），子钮依次
//     液态弹出（spring 过冲 `cubic-bezier(.3,1.36,.45,1)` + squash/stretch 关键帧
//     + 模糊析出 stagger），再点反向收回。左右各一列，每钮独立。
//   · 键位映射对齐真实客户端（任务书）：左列 = floatController FLOAT_IDS 六键
//     （+任务键 + pending-asks 胶囊，见 floatController.js 头注）；右列 =
//     layoutController 活动栏七键**去聊天**（画板/项目/图谱/插件/用量/设置）。
//     两列的子钮归属权在各键主（float/layout），本文件只建列壳与开合/排列。
//   · 中簇 = 会话头（头像 + 名称胶囊，agentProfile.js 插入的同一节点），CSS 网格
//     居中；时间戳本就不在顶栏（v3① 口径 = 顶栏不出现时间，spec 断言守护）。
//   · 点空白全收（scrim 一件：收两列 + 任务浮层 + profile 面板）。
//
// 结构：本文件不搬移任何既有节点 —— 只向 #header 追加左右两个 `.mh-dd` 列壳
// （桌面视口永不激活 ⇒ 零桌面痕迹）；#header 既有子节点的 v3 摆放全部在
// mobileLayer.css（grid + 隐藏桌面簇）。拆层时列壳原样移除。

import { createIconsIn } from '../utils.js';
import { logEvent, touchLayer } from './navController.js';
import { closeTaskPanel, isTaskPanelOpen } from './taskPanel.js';
import { closeProfilePanel } from '../agentProfile.js';

const HD_ICON = 'sliders-horizontal'; // 左钮（会话功能）
const NAV_ICON = 'layers';            // 右钮（前往）

/** @type {HTMLElement|null} */
let ddL = null;
/** @type {HTMLElement|null} */
let ddR = null;
/** @type {HTMLElement|null} */
let scrim = null;
/** @type {MutationObserver[]} */
const listObservers = [];

function buildDD(side) {
  const dd = document.createElement('div');
  dd.className = 'mh-dd';
  dd.dataset.dd = side;
  const hd = document.createElement('button');
  hd.type = 'button';
  hd.className = 'mh-dd-hd glass-control';
  hd.setAttribute('aria-expanded', 'false');
  hd.innerHTML = `<i data-lucide="${side === 'left' ? HD_ICON : NAV_ICON}"></i>`;
  const bd = document.createElement('div');
  bd.className = 'mh-dd-bd';
  dd.append(hd, bd);
  hd.addEventListener('click', () => {
    touchLayer();
    const willOpen = !dd.classList.contains('open');
    logEvent('dd-toggle', { side, open: willOpen });
    setDD(dd, willOpen);
  });
  return dd;
}

/** 开/合一列：互斥（开一列收另一列）+ 宽 40 恒定的目标高度变量。 */
function setDD(dd, open) {
  if (!dd) return;
  const other = dd === ddL ? ddR : ddL;
  if (open && other && other.classList.contains('open')) setDD(other, false);
  dd.classList.toggle('open', open);
  const hd = dd.querySelector('.mh-dd-hd');
  if (hd) hd.setAttribute('aria-expanded', open ? 'true' : 'false');
  syncScrim();
}

/** 子钮序号/列高同步（子钮由各键主追加 —— 变化即重排 stagger 序与 --dh）。 */
function observeList(dd) {
  const bd = dd.querySelector('.mh-dd-bd');
  if (!bd) return;
  const sync = () => {
    // 隐藏键（计数为 0 的指示键等）不占位 —— 只给可见键排 stagger 序与列高，
    // 否则列尾留一段空玻璃（样张口径：列即所见）。
    const kids = [...bd.children].filter((k) => !(k.hidden || k.classList.contains('hidden')));
    [...bd.children].forEach((k) => {
      if (k.hasAttribute('data-vi')) k.removeAttribute('data-vi');
    });
    kids.forEach((k, i) => {
      if (k.style.getPropertyValue('--i') !== String(i)) k.style.setProperty('--i', String(i));
      if (!k.hasAttribute('data-vi')) k.setAttribute('data-vi', '');
    });
    // 宽 40 恒定：列高（CSS height 直接用）= 头钮 40 + n*40 + (n-1)*6 间隙
    // + 底 6 padding（少算头钮 ⇒ 末键被 overflow 裁掉 —— 实测设置键消失）。
    // 手机六件②：键 36/gap 4 紧凑档（与 mobileLayer .mh-key 同步）
    dd.style.setProperty('--dh', `${40 + kids.length * 36 + Math.max(0, kids.length - 1) * 4 + 4}px`);
    dd.classList.toggle('empty', kids.length === 0);
  };
  const mo = new MutationObserver(sync);
  // childList = 键主增删键；attributes(subtree) = 键主 toggle hidden/类（0 计数
  // 指示键显隐）→ 列高/stagger 即时重排。sync 只在值变化时写属性 ⇒ 不自激。
  mo.observe(bd, { childList: true, attributes: true, attributeFilter: ['hidden', 'class'], subtree: true });
  listObservers.push(mo);
  sync();
}

function anySurfaceOpen() {
  return (ddL && ddL.classList.contains('open'))
    || (ddR && ddR.classList.contains('open'))
    || isTaskPanelOpen()
    || document.body.classList.contains('ap-open');
}

function syncScrim() {
  if (!scrim) return;
  scrim.classList.toggle('open', !!anySurfaceOpen());
}

/** 收两列（子钮沉回）；不动任务浮层/profile（各自的 X / scrim 统一收口在此）。 */
export function collapseDDs() {
  setDD(ddL, false);
  setDD(ddR, false);
}

/** 点空白全收（样张：scrim click → closeDD + closeTPanel）。 */
function collapseAll() {
  collapseDDs();
  closeTaskPanel(true);
  closeProfilePanel();
  syncScrim();
}

/** @returns {HTMLElement|null} 左列子钮容器（floatController 挂 FLOAT_IDS 键）。 */
export function leftList() { return ddL ? /** @type {HTMLElement} */ (ddL.querySelector('.mh-dd-bd')) : null; }
/** @returns {HTMLElement|null} 右列子钮容器（layoutController 挂页面键）。 */
export function rightList() { return ddR ? /** @type {HTMLElement} */ (ddR.querySelector('.mh-dd-bd')) : null; }
/** @returns {HTMLElement|null} 左列根（任务键 morph 的 origin 圆钮）。 */
export function leftDD() { return ddL; }

export function activateHeader() {
  const header = document.getElementById('header');
  if (!header) return () => {};
  document.body.classList.add('mh-v3');
  ddL = buildDD('left');
  ddR = buildDD('right');
  header.prepend(ddL);
  header.appendChild(ddR);
  observeList(ddL);
  observeList(ddR);
  if (typeof lucide !== 'undefined') { createIconsIn(ddL); createIconsIn(ddR); }

  scrim = document.createElement('div');
  scrim.className = 'mh-scrim';
  scrim.addEventListener('click', collapseAll);
  document.body.appendChild(scrim);

  // 任何表面开合（含 profile 的 body.ap-open）都同步 scrim。
  const mo = new MutationObserver(syncScrim);
  mo.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  listObservers.push(mo);

  return function teardownHeader() {
    for (const mo of listObservers.splice(0)) mo.disconnect();
    scrim.remove();
    scrim = null;
    ddL.remove();
    ddR.remove();
    ddL = null;
    ddR = null;
    document.body.classList.remove('mh-v3');
  };
}
