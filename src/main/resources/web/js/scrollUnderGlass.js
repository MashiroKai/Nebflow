// scrollUnderGlass.js — uifix ⑤ 浮层下缘毛玻璃 · 降级探测器（作者令 2026-10-20 第五件）
//
// 工具类本体在 css/scrollUnderGlass.css（.scroll-under-glass 裙边：主态 =
// backdrop blur + tint + mask 渐隐；降级态 = 纯 tint + mask 渐隐）。
// 本模块只管「两态切换」的机制（作者令：性能兜底机制自选，两态都要有 spec）：
//   1) 特性检测：引擎不支持 backdrop-filter → html.no-backdrop（永久降级态）；
//   2) 渲染预算探测：页面滚动（= 内容可能正穿过裙边）时开窗采样 rAF 帧间隔，
//      中位帧距 >34ms（≈持续 <30fps）判定吃紧 → html.glass-degraded（单向锁，
//      本页生效，不再回切——降级后再探测只会白耗预算）。
// 探测节流：每 4s 至多开一窗；单窗 24 帧即出结论。
//
// spec 通道：window.__scrollUnderGlass = { degrade(on), state() }。
//   degrade(true)  手动进入降级态（spec 驱动降级断言用，与自动判定同一条腿）；
//   degrade(false) 解锁回主态（auto 判定仍可再次降级）。

const DEGRADE_CLASS = 'glass-degraded';
const NO_SUPPORT_CLASS = 'no-backdrop';
const FRAME_BUDGET_MS = 34;      // 中位帧距阈值 ≈ 持续 <30fps
const WINDOW_FRAMES = 24;        // 单窗帧数
const ARM_COOLDOWN_MS = 4000;    // 开窗节流

let degraded = false;
let supported = true;

export function initScrollUnderGlass() {
  const doc = document.documentElement;
  supported = typeof CSS !== 'undefined' && !!CSS.supports &&
    (CSS.supports('backdrop-filter', 'blur(1px)') || CSS.supports('-webkit-backdrop-filter', 'blur(1px)'));
  if (!supported) doc.classList.add(NO_SUPPORT_CLASS);

  let probing = false;
  let raf = 0;
  let lastT = 0;
  let lastArm = 0;
  const dts = [];

  function setDegrade(on) {
    degraded = !!on;
    doc.classList.toggle(DEGRADE_CLASS, degraded);
  }

  function tick(t) {
    if (lastT) dts.push(t - lastT);
    lastT = t;
    if (dts.length < WINDOW_FRAMES) { raf = requestAnimationFrame(tick); return; }
    probing = false;
    const sorted = [...dts].sort((a, b) => a - b);
    const median = sorted[sorted.length >> 1];
    if (median > FRAME_BUDGET_MS) setDegrade(true);   // 单向锁：本页不再回切
  }

  function arm() {
    if (degraded || probing || !supported) return;
    const now = performance.now();
    if (now - lastArm < ARM_COOLDOWN_MS) return;
    lastArm = now;
    probing = true;
    dts.length = 0;
    lastT = 0;
    raf = requestAnimationFrame(tick);
  }

  window.addEventListener('scroll', arm, { passive: true, capture: true });

  window.__scrollUnderGlass = {
    degrade: setDegrade,
    state: () => ({
      degraded,
      noSupport: doc.classList.contains(NO_SUPPORT_CLASS),
      supported,
      probing,
    }),
  };
}
