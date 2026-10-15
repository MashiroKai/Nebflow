// mobile/canvasGestures.js — 规格④：画板手机手势与卡片视图。
//
// 作者规格（原文语义）：
//   · canvas 交互＝左右滑切页签、上滑关闭；
//   · 长按 canvas 按钮 → 缩小卡片视图（类 iOS home 多卡切换）。
//
// 实现取 Pointer Events（触屏/鼠标统一；iOS Safari 13+ 与 Playwright 桌面鼠
// 标同源可用，QA 可测）。手势区分三带，互不劫持：
//   1. 页签栏区（.canvas-header）        → touch-action:none；上滑＝关闭画板。
//   2. 底缘上滑带（面板底部 28px，iOS home-indicator 同位）→ touch-action:none；
//      上滑＝关闭画板（跟手位移，越阈释放即关）。内容区滚动零冲突。
//   3. 内容区（#canvas-content）          → touch-action:pan-y：纵向滚动归浏览器，
//      横向意图（|dx| 显著占优且越阈）交给本控制器切页签（不抢纵向滚动）。
//
// 卡片视图：#mobile-canvas-cards 覆盖层，逐页签克隆 pane 静态缩微（transform
// scale，指针穿透关闭）；克隆体剥除全部 id 防重复节点干扰；点卡片 = setActiveTab
// + 确保画板展开。Monaco/iframe 类重控件在卡片中为空白占位 —— 静态缩微卡的
// 已知边界，如实注记（QA 报告披露）。

import { openCanvas, closeCanvas, isCanvasOpen, setActiveTab } from '../canvas.js';
import { createIconsIn } from '../utils.js';

/** @type {Array<() => void>} */
let unsubs = [];

const EDGE_ZONE_PX = 40;     // 底缘上滑带高度（iOS home-indicator 同位；高于横向滚动条带）
const CLOSE_THRESHOLD = 56;  // 上滑关闭触发位移（px）
const TAB_SWITCH_PX = 56;    // 左右滑切页签触发位移（px）
const MAX_FOLLOW = 320;      // 上滑跟手最大位移（px）

// ── 手势：左右滑切页签 / 上滑关闭 ─────────────────────────────────

function canvasRoot() { return document.getElementById('canvas-panel'); }
function slideEl() { return document.getElementById('canvas-slide'); }
function tabIds() {
  return [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')]
    .map((el) => el.dataset.tabId)
    .filter(Boolean);
}
function currentTabId() {
  const el = document.querySelector('#canvas-tab-bar .canvas-tab.active');
  return el ? el.dataset.tabId : null;
}

function switchTab(dir) {
  const ids = tabIds();
  if (ids.length < 2) return;
  const cur = currentTabId();
  const i = ids.indexOf(cur);
  const next = ids[Math.min(ids.length - 1, Math.max(0, (i === -1 ? 0 : i) + dir))];
  if (next && next !== cur) setActiveTab(next);
}

function isInteractiveTarget(el) {
  // 页签关闭钮/可交互控件起步的横滑不劫持（用户在点按钮，不是在翻页签）。
  return !!el.closest('button, a, input, textarea, select, label, [contenteditable="true"], .canvas-tab-close');
}

function bindGestures() {
  const root = canvasRoot();
  if (!root) return () => {};

  // 底缘上滑带（iOS home-indicator 同位）：真实元素承载 touch-action:none，
  // 让上滑关闭手势不被内容纵向滚动吃掉；抓手法线由 CSS 绘制。
  let edgeZone = root.querySelector('.mobile-canvas-edgezone');
  if (!edgeZone) {
    edgeZone = document.createElement('div');
    edgeZone.className = 'mobile-canvas-edgezone';
    edgeZone.setAttribute('aria-hidden', 'true');
    root.appendChild(edgeZone);
  }

  /** @type {{x:number,y:number,t:number,zone:'content'|'header'|'edge',intent:'?'|'h'|'v',closed:boolean}|null} */
  let g = null;

  const zoneOf = (target, y) => {
    if (target.closest('.canvas-header')) return 'header';
    const rect = root.getBoundingClientRect();
    if (rect.bottom - y <= EDGE_ZONE_PX) return 'edge';
    return 'content';
  };

  const onDown = (e) => {
    if (!isCanvasOpen()) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    // 卡片视图打开时手势让位（卡片层自己管理交互）。
    if (document.getElementById('mobile-canvas-cards')) return;
    g = {
      x: e.clientX, y: e.clientY, t: Date.now(),
      zone: zoneOf(e.target, e.clientY),
      intent: '?', closed: false,
    };
  };

  const onMove = (e) => {
    if (!g) return;
    const dx = e.clientX - g.x;
    const dy = e.clientY - g.y;
    if (g.intent === '?') {
      if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
        g.intent = Math.abs(dx) > Math.abs(dy) * 1.4 ? 'h' : 'v';
      }
    }
    // 上滑跟手（页签栏/底缘两带）：面板整体上移，松手判定。
    if (g.intent === 'v' && dy < 0 && (g.zone === 'header' || g.zone === 'edge')) {
      const follow = Math.max(-MAX_FOLLOW, dy);
      const slide = slideEl();
      if (slide) {
        slide.style.transition = 'none';
        slide.style.transform = `translateY(${follow}px)`;
        slide.style.willChange = 'transform';
      }
    }
  };

  const resetSlide = () => {
    const slide = slideEl();
    if (slide) {
      slide.style.transition = '';
      slide.style.transform = '';
      slide.style.willChange = '';
    }
  };

  const onUp = (e, cancelled = false) => {
    if (!g) return;
    const dx = e.clientX - g.x;
    const dy = e.clientY - g.y;
    const dt = Date.now() - g.t;
    const zone = g.zone;
    const intent = g.intent;
    g = null;
    resetSlide();

    // 上滑关闭：页签栏带或底缘带，纵向意图，位移越阈（快速轻扫或长距拖拽）。
    if ((zone === 'header' || zone === 'edge') && intent === 'v' && dy < -CLOSE_THRESHOLD) {
      closeCanvas();
      return;
    }
    // 左右滑切页签：横 intention、位移越阈、时限内；内容区起步且目标非交互控件。
    // pointercancel（纵向滚动接管）时若横意图已定且位移足额，同样判切 ——
    // 浏览器吞掉后续 move 不影响已成立的横滑判定。
    if (intent === 'h' && Math.abs(dx) >= TAB_SWITCH_PX && (dt < 900 || cancelled) && !isInteractiveTarget(e.target)) {
      switchTab(dx < 0 ? 1 : -1); // 左滑＝下一页签，右滑＝上一页签（iOS 同向）
    }
  };

  root.addEventListener('pointerdown', onDown, true);
  root.addEventListener('pointermove', onMove, true);
  root.addEventListener('pointerup', onUp, true);
  root.addEventListener('pointercancel', (e) => { onUp(e, true); }, true);

  return () => {
    root.removeEventListener('pointerdown', onDown, true);
    root.removeEventListener('pointermove', onMove, true);
    root.removeEventListener('pointerup', onUp, true);
    root.querySelector('.mobile-canvas-edgezone')?.remove();
    resetSlide();
  };
}

// ── 卡片视图：长按画板按钮 → 缩小卡片（类 iOS home 多卡切换）─────────

function closeCardView() {
  document.getElementById('mobile-canvas-cards')?.remove();
}

function openCardView() {
  if (document.getElementById('mobile-canvas-cards')) return;
  const tabs = [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')];
  if (!tabs.length) {
    // 无页签可展示：轻提示（不伪造卡片）。
    window.__showToast?.(tt('画板暂无打开的页签', 'No open canvas tabs'), 'info');
    return;
  }
  if (!isCanvasOpen()) openCanvas();

  const content = document.getElementById('canvas-content');
  const cw = content ? content.clientWidth : window.innerWidth;
  const ch = content ? content.clientHeight : window.innerHeight;

  const overlay = document.createElement('div');
  overlay.id = 'mobile-canvas-cards';
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-label', tt('画板多卡切换', 'Canvas card switcher'));

  const grid = document.createElement('div');
  grid.className = 'mcc-grid';
  overlay.appendChild(grid);

  for (const tabEl of tabs) {
    const id = tabEl.dataset.tabId;
    const pane = document.querySelector(`#canvas-content .canvas-tab-pane[data-tab-id="${CSS.escape(id)}"]`);
    const label = tabEl.querySelector('.canvas-tab-label')?.textContent || id;
    const isActive = tabEl.classList.contains('active');

    const card = document.createElement('div');
    card.className = 'mcc-card' + (isActive ? ' active' : '');
    card.dataset.tabId = id;

    const head = document.createElement('div');
    head.className = 'mcc-card-head';
    head.innerHTML = `<span class="mcc-card-label"></span><span class="mcc-card-dot"></span>`;
    head.querySelector('.mcc-card-label').textContent = label;
    card.appendChild(head);

    const preview = document.createElement('div');
    preview.className = 'mcc-preview';
    if (pane && cw > 0 && ch > 0) {
      // 静态缩微：深克隆 + 剥除 id（防重复 id 干扰桌面查询）+ scale 适配卡片。
      // 克隆不带 .active 时 display:none（split.css 页签显隐机制）—— 强制可见。
      const clone = pane.cloneNode(true);
      clone.classList.add('active');
      clone.removeAttribute('id');
      clone.querySelectorAll('[id]').forEach((n) => n.removeAttribute('id'));
      clone.style.pointerEvents = 'none';
      const holder = document.createElement('div');
      holder.className = 'mcc-clone';
      holder.appendChild(clone);
      preview.appendChild(holder);
      // 几何在入 DOM 后结算：以**源 pane** 的实时盒尺寸为准（画板此刻已展开，
      // 尺寸真实），克隆体强制同尺寸后整体 scale 进卡片。
      requestAnimationFrame(() => {
        const pw = Math.max(1, pane.clientWidth || cw);
        const ph = Math.max(1, pane.clientHeight || ch);
        const box = preview.getBoundingClientRect();
        const scale = Math.min(box.width / pw, box.height / ph);
        clone.style.width = pw + 'px';
        clone.style.height = ph + 'px';
        holder.style.width = pw + 'px';
        holder.style.height = ph + 'px';
        holder.style.transform = `scale(${scale})`;
      });
    }
    card.appendChild(preview);

    card.addEventListener('click', () => {
      closeCardView();
      setActiveTab(id);
      if (!isCanvasOpen()) openCanvas();
    });
    grid.appendChild(card);
  }

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'mcc-close';
  closeBtn.title = tt('关闭', 'Close');
  closeBtn.innerHTML = '<i data-lucide="x"></i>';
  closeBtn.addEventListener('click', closeCardView);
  overlay.appendChild(closeBtn);

  overlay.addEventListener('click', (e) => { if (e.target === overlay) closeCardView(); });
  document.body.appendChild(overlay);
  if (typeof lucide !== 'undefined') createIconsIn(closeBtn);
}

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || localStorage.getItem('locale') || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

// ── 激活 / 拆除 ─────────────────────────────────────────────────

export function activateGestures() {
  const unbind = bindGestures();
  const bar = document.getElementById('mobile-activity-bar');
  const onLongPress = () => openCardView();
  bar?.addEventListener('nf:mobile-canvas-longpress', onLongPress);
  const onKey = (e) => { if (e.key === 'Escape') closeCardView(); };
  document.addEventListener('keydown', onKey);

  return function teardownGestures() {
    closeCardView();
    bar?.removeEventListener('nf:mobile-canvas-longpress', onLongPress);
    document.removeEventListener('keydown', onKey);
    unbind();
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
  };
}
