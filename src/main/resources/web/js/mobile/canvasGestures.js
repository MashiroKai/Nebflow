// mobile/canvasGestures.js — r2 规格③：文档卡片手势。
//
// r1 的页签形态已废除（作者三点规格③：「canvas 层只承载 pop 出来的文档且以
// 卡片呈现」）—— r1 spec④ 的「左右滑切页签 / 长按画板按钮多卡视图」随之退役，
// 相应代码（openCardView / closeCardView / 长按事件 nf:mobile-canvas-longpress）
// 已删净。r2 手势面：
//   · 横滑（|dx| 越阈、横意图占优）→ 文档卡片间切换（docTabs 相邻激活）；
//   · 底缘带上滑 → 收起整叠卡片层（closeCanvas —— 文档保留在页签数据模型里，
//     bar「画板」键或再次 pop 文档即可重现；最后一张卡经卡片头 X 关闭时由
//     closeTab 的末页签语义自动收层）；
//   · 单卡关闭 = 卡片头 X（navController.closeCard）。
// UI 批⑨（作者令 2026-10-21）：卡片头带（.canvas-header 胶囊）手势整带移交
// navController 卡头胶囊 —— 直滑上下=换卡、左右=关卡、长按=引用钮（无按压
// 前置）。本控制器对 header 带全面让位（不收层、不切卡、不跟手位移），
// 「上滑收层」只剩底缘带一条通路；⑨ 胶囊换卡取代了旧「头上滑收层」语义。
//
// 实现取 Pointer Events（触屏/鼠标统一）。手势区分三带，互不劫持（r1 口径不变）：
//   1. 卡片头带（.canvas-header）            → touch-action:none；⑨ 起整带移交
//      卡头胶囊手势（navController：直滑换卡/关卡/长按引用钮），本控制器不碰。
//   2. 底缘上滑带（面板底部 40px，iOS home-indicator 同位）→ touch-action:none。
//   3. 内容区（#canvas-content）             → touch-action:pan-y：纵向滚动归
//      浏览器，横向意图交给本控制器切卡片（不抢纵向滚动）。

import { closeCanvas, isCanvasOpen, setActiveTab } from '../canvas.js';
import { docTabs, activeDocId, logEvent, touchLayer } from './navController.js';

const EDGE_ZONE_PX = 40;     // 底缘上滑带高度（iOS home-indicator 同位；高于横向滚动条带）
const CLOSE_THRESHOLD = 56;  // 上滑收层触发位移（px）
const CARD_SWITCH_PX = 56;   // 横滑切卡片触发位移（px）
const MAX_FOLLOW = 320;      // 上滑跟手最大位移（px）

// ── 手势：横滑切卡片 / 上滑收层 ───────────────────────────────────

function canvasRoot() { return document.getElementById('canvas-panel'); }
function slideEl() { return document.getElementById('canvas-slide'); }

function switchCard(dir) {
  const docs = docTabs();
  if (docs.length < 2) return;
  const cur = activeDocId();
  const i = docs.findIndex((d) => d.id === cur);
  const next = docs[Math.min(docs.length - 1, Math.max(0, (i === -1 ? 0 : i) + dir))];
  if (next && next.id !== cur) {
    setActiveTab(next.id);
    logEvent('gesture-card-switch', { to: next.id });
  }
}

function isInteractiveTarget(el) {
  // 卡片头关闭钮/可交互控件起步的横滑不劫持（用户在点按钮，不是在翻卡片）。
  return !!el.closest('button, a, input, textarea, select, label, [contenteditable="true"], .canvas-tab-close');
}

function bindGestures() {
  const root = canvasRoot();
  if (!root) return () => {};

  // 底缘上滑带（iOS home-indicator 同位）：真实元素承载 touch-action:none，
  // 让上滑收层手势不被内容纵向滚动吃掉；抓手法线由 CSS 绘制。
  let edgeZone = root.querySelector('.mobile-canvas-edgezone');
  if (!edgeZone) {
    edgeZone = document.createElement('div');
    edgeZone.className = 'mobile-canvas-edgezone';
    edgeZone.setAttribute('aria-hidden', 'true');
    root.appendChild(edgeZone);
  }

  /** @type {{x:number,y:number,t:number,zone:'content'|'header'|'edge',intent:'?'|'h'|'v'}|null} */
  let g = null;

  const zoneOf = (target, y) => {
    if (target.closest('.canvas-header')) return 'header';
    const rect = root.getBoundingClientRect();
    if (rect.bottom - y <= EDGE_ZONE_PX) return 'edge';
    return 'content';
  };

  const onDown = (e) => {
    // 卡片层未亮（未开或面板页签激活被页面接管）时手势不劫持。
    if (!document.body.classList.contains('mf-cards-live')) return;
    if (!isCanvasOpen()) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    touchLayer();
    g = {
      x: e.clientX, y: e.clientY, t: Date.now(),
      zone: zoneOf(e.target, e.clientY),
      intent: '?',
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
    // 上滑跟手（底缘带；⑨ 起卡头带让位给胶囊换卡手势）：面板整体上移，松手判定。
    if (g.intent === 'v' && dy < 0 && g.zone === 'edge') {
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

    // 上滑收层：底缘带（⑨ 起卡头带移交胶囊换卡手势，不再收层）。
    if (zone === 'edge' && intent === 'v' && dy < -CLOSE_THRESHOLD) {
      logEvent('cards-collapse', { via: 'swipe' });
      closeCanvas();
      return;
    }
    // 横滑切卡片：横意图、位移越阈、时限内；内容区起步且目标非交互控件。
    // 卡头带整带让位（⑨ 胶囊手势 owns：横滑=关卡，navController）。
    // pointercancel（纵向滚动接管）时若横意图已定且位移足额，同样判切 ——
    // 浏览器吞掉后续 move 不影响已成立的横滑判定。
    if (zone !== 'header' && intent === 'h' && Math.abs(dx) >= CARD_SWITCH_PX && (dt < 900 || cancelled) && !isInteractiveTarget(e.target)) {
      switchCard(dx < 0 ? 1 : -1); // 左滑＝下一张卡，右滑＝上一张卡（iOS 同向）
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

// ── 激活 / 拆除 ─────────────────────────────────────────────────

export function activateGestures() {
  const unbind = bindGestures();
  return function teardownGestures() {
    unbind();
  };
}
