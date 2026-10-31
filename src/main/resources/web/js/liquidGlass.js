// liquidGlass.js — 液体玻璃 v2 批（2026-10-31）边缘光折射能力探测（追加批）。
//
// 职责只有一件：给 <html> 挂/不挂 g2-refract-on 类开关（设计稿 §7 字面口径
// 「@supports 探测 + 类开关」）。liquid-glass.css 的 ::before 折射层在该类下
// 追加 filter:url(#g2-refract)（SVG defs 在 index.html #g2-refract-defs）：
//   · 双判据：CSS.supports('filter', 'url(...)') 解析通过 + 滤镜 defs 真在场
//     （防打包脸/隧道脸丢 SVG 时 Chromium「滤镜引用悬空 ⇒ 元素不绘制」事故）；
//   · 探测失败/异常 = 不挂类：折射层退化为高光 + 直 blur（高光/浮影/按压全保留，
//     不裸断 —— 旧 WebView 降级口径同设计稿 §7）。
// 纯探测零渲染副作用；桌面/手机同路径（折射 = 全端按钮族材质，非手机层件）。

export function initLiquidGlass() {
  try {
    const ok =
      typeof CSS !== 'undefined' &&
      !!CSS.supports &&
      CSS.supports('filter', 'url(#g2-refract)') &&
      !!document.getElementById('g2-refract');
    document.documentElement.classList.toggle('g2-refract-on', ok);
  } catch {
    /* 探测异常 = 不挂类（降级态），不阻断启动 */
  }
}
