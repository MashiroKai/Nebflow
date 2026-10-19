// mobile/morphPanel.js — v3「二级液态」形变助手（样张 20261020_214500 v3⑤ 同款）。
//
// 形变语义：面板由圆钮**原地形变**而来 —— 起始几何 = 触发圆钮的 40×40 圆角 20，
// 终态 = 面板自身的布局矩形（宽度/高度按面板各异：任务浮层 308×560，搜索居中卡…）。
// 实现 = FLIP（先按终态布局，再反算 origin 变换，过渡回 identity）：
//   · 过渡曲线 = 样张 spring `cubic-bezier(.3,1.36,.45,1)`（过冲回稳）；
//   · 圆角 20px → 面板终角（16px）随形变过渡；
//   · 内容在形变后段揭示（样张 tpFade：.32s ease，延迟 .24s）——
//     `.mh-morph-anim > *` 直接子元素整体淡入，任意面板零包装适配；
//   · 逆向收回 = 反向 FLIP（终态 → 40×40 圆钮），收毕回调里执行真正的隐藏。
// reduced-motion：全部时长压到 ≤0.01ms（frontend-verification A10 口径）。
//
// 本文件只动 transform/border-radius/子元素 opacity，不碰面板的显隐逻辑 ——
// 显隐仍归各面板既有开合路径（记忆/搜索走 overlay .on；任务/profile 面板自带
// hidden 闸）。桌面视口永不加载 mobile/ 控制器 ⇒ 零桌面痕迹。

/** 样张 spring（过冲）与收回曲线（v3 tpanel tpClose 用快出缓收）。 */
export const SPRING = 'cubic-bezier(.3,1.36,.45,1)';
export const CLOSE_EASE = 'cubic-bezier(.4,0,.72,.24)';
const OPEN_MS = 680;
const CLOSE_MS = 420;

function reducedMotion() {
  return typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** 圆钮几何（宽 40 恒定，样张③口径）。 */
const BTN = 40;

/**
 * 面板自 origin 圆钮形变展开。调用时机 = 面板已置为可见（布局矩形就绪）。
 * @param {HTMLElement} panelEl 终态已布局的面板
 * @param {HTMLElement} originEl 40×40 触发圆钮（或任意 origin 元素，取其中心 40×40）
 */
export function morphIn(panelEl, originEl) {
  if (!panelEl || !originEl) return;
  panelEl.style.opacity = ''; // 面板观察方（floatController）的预隐腿在此交还
  if (reducedMotion()) return;
  const r = panelEl.getBoundingClientRect();
  const o = originEl.getBoundingClientRect();
  if (!r.width || !r.height || !o.width) return;
  // origin 取圆钮几何：以 origin 中心为 40×40（右列/头像钮等宽恒定）。
  const ob = {
    left: o.left + o.width / 2 - BTN / 2,
    top: o.top + o.height / 2 - BTN / 2,
  };
  const dx = ob.left - r.left;
  const dy = ob.top - r.top;
  const sx = BTN / r.width;
  const sy = BTN / r.height;
  panelEl.style.setProperty('--mh-dx', `${dx}px`);
  panelEl.style.setProperty('--mh-dy', `${dy}px`);
  panelEl.style.setProperty('--mh-sx', String(sx));
  panelEl.style.setProperty('--mh-sy', String(sy));
  panelEl.style.setProperty('--mh-ms', `${OPEN_MS}ms`);
  panelEl.classList.remove('mh-morph-anim');
  void panelEl.offsetWidth; // 重放形变动画（样张 openTPanel 同款 reflow 重置）
  panelEl.classList.add('mh-morph-anim');
  const clear = () => {
    panelEl.classList.remove('mh-morph-anim');
    panelEl.removeEventListener('animationend', onEnd);
  };
  const onEnd = (e) => { if (e.target === panelEl) clear(); };
  panelEl.addEventListener('animationend', onEnd);
}

/**
 * 面板逆向形变收回为 origin 圆钮；收毕（或 reduced-motion 直通）执行 done()。
 * @param {HTMLElement} panelEl @param {HTMLElement} originEl @param {() => void} done
 */
export function morphOut(panelEl, originEl, done) {
  if (!panelEl) { if (done) done(); return; }
  if (reducedMotion() || !originEl) { if (done) done(); return; }
  const r = panelEl.getBoundingClientRect();
  const o = originEl.getBoundingClientRect();
  if (!r.width || !o.width) { if (done) done(); return; }
  const ob = {
    left: o.left + o.width / 2 - BTN / 2,
    top: o.top + o.height / 2 - BTN / 2,
  };
  panelEl.style.setProperty('--mh-dx', `${ob.left - r.left}px`);
  panelEl.style.setProperty('--mh-dy', `${ob.top - r.top}px`);
  panelEl.style.setProperty('--mh-sx', String(BTN / r.width));
  panelEl.style.setProperty('--mh-sy', String(BTN / r.height));
  panelEl.style.setProperty('--mh-ms', `${CLOSE_MS}ms`);
  panelEl.classList.remove('mh-morph-anim');
  void panelEl.offsetWidth;
  panelEl.classList.add('mh-morph-close');
  const finish = () => {
    panelEl.classList.remove('mh-morph-close');
    panelEl.removeEventListener('animationend', onEnd);
    if (done) done();
  };
  const onEnd = (e) => { if (e.target === panelEl) finish(); };
  panelEl.addEventListener('animationend', onEnd);
}
