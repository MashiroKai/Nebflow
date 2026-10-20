// _mobile-panel-settle.mjs — 手机层 morph 面板的「终态」守护（mobile-secondary-
// screens 实施批共用测试助手；非 *.spec 文件，Playwright 不收集）。
//
// 两段式（任何一段单独用都有竞态洞）：
//   1) 等 morph「播完或被跳过」：animationend（形变腿播完）或 1.2s 超时
//      （reduced-motion / floatController 600ms 外部开合守卫跳过腿）——先到先收；
//   2) 断言终态：无 morph 类 ∧ element.getAnimations() 空（含内容淡入子腿之外
//      的面板本体动画全停）∧ transform 归位。
// 洞的来源：floatController 的 morph 是「.on 翻转 → 160ms 延时 → morphIn」，
// 只查类/transform 会在「已 .on 但形变未起」的窗口抢跑（实测 borderRadius
// 采到 17.15px 插值中值）。

/**
 * @param {import('@playwright/test').Page} page
 * @param {{overlaySel: string, panelSel: string, openClass?: string}} arg
 */
export async function settlePanel(page, { overlaySel, panelSel, openClass = 'on' }) {
  await page.evaluate(({ os, ps, oc }) => new Promise((resolve) => {
    const ov = /** @type {HTMLElement|null} */ (document.querySelector(os));
    const pm = /** @type {HTMLElement|null} */ (document.querySelector(ps));
    const isOpen = !!ov && ov.classList.contains(oc) || !!pm && pm.classList.contains(oc);
    if (!isOpen) return resolve(undefined);
    let done = false;
    const finish = () => { if (!done) { done = true; resolve(undefined); } };
    pm.addEventListener('animationend', finish, { once: true });
    setTimeout(finish, 1200);
  }), { os: overlaySel, ps: panelSel, oc: openClass });
  await page.waitForFunction(({ ps }) => {
    const m = /** @type {HTMLElement|null} */ (document.querySelector(ps));
    if (!m) return false;
    if (m.classList.contains('mh-morph-anim') || m.classList.contains('mh-morph-close')) return false;
    // 只等「有限次」动画结束（Element.getAnimations() 含子孙 —— 心跳运行点
    // daemon-pulse 1.6s infinite 恒在场，不可作终态信号；无限动画与面板盒无关）。
    const finiteRunning = m.getAnimations().some((a) => {
      try { return (a.effect?.getTiming().iterations ?? 1) !== Infinity; } catch { return true; }
    });
    if (finiteRunning) return false;
    const tf = getComputedStyle(m).transform;
    return tf === 'none' || tf === 'matrix(1, 0, 0, 1, 0, 0)';
  }, { ps: panelSel }, { timeout: 8000 });
}
