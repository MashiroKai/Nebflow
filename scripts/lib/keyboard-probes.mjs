// keyboard-probes.mjs — 键盘/焦点断言的**共享探针**（B0-5 / G5）。
//
// 为什么要有这一层：改造前 `keyboard-check.mjs` 的 27 条断言全部覆盖一期**设施**
// （注册器 / 面板 / 模态 / 焦点环 / 双模态），**0 条**覆盖业务语义 —— 没有一条问
// 「Enter 是否真的开了文件」「Tab 序是否真的到得了卡」。B1 起每条都要新断言，而
// 断言基础设施（Tab 序快照 / 事件计数 / 焦点 key 比较）今天一处都没有；各批各造
// 一套的直接后果是：同一件事在不同批里用不同口径取证，断言之间不可比。
//
// 本文件只提供**取证口径**，不提供结论。四条口径对应四类断言：
//   · tabOrderSnapshot —— Tab 序（① 可达性 ② 序列稳定性 ③ 逃逸）
//   · activeKey        —— 焦点身份（跨重渲比对、前后比较）
//   · countEvents      —— 副作用计数（"真的发生了"而不是"元素存在"）
//   · layerDepth       —— 层栈深度（读真实模块单例，不是读 DOM 猜）
//
// 纪律：探针只读页面、不写页面（countEvents 的唯一写操作是装一个计数器，
// 且可 reset）—— 探针自身绝不能影响被验行为。

/**
 * 焦点身份描述符：优先 `data-focus-key`（跨重渲稳定），其次 `#id`，最后
 * `tag.class[0]`。返回值可直接用 `===` 比较前后两拍。
 * @param {import('playwright-core').Page} page
 * @returns {Promise<string>}
 */
export async function activeKey(page) {
  return page.evaluate(() => {
    const el = /** @type {any} */ (document.activeElement);
    if (!el || el === document.body) return 'BODY';
    const dk = el.getAttribute && el.getAttribute('data-focus-key');
    if (dk) return `key:${dk}`;
    if (el.id) return `#${el.id}`;
    const cls = typeof el.className === 'string' && el.className.trim()
      ? `.${el.className.trim().split(/\s+/)[0]}` : '';
    return `${String(el.tagName).toLowerCase()}${cls}`;
  });
}

/**
 * Tab 序快照：从当前焦点起连续按 `steps` 次 Tab，逐拍记焦点身份。
 * @param {import('playwright-core').Page} page
 * @param {{steps?: number, shift?: boolean, sel?: string}} [opts]
 *   `sel` 给了就先点/聚焦它（`.focus()`），让快照有确定起点。
 * @returns {Promise<string[]>} 长度 = steps
 */
export async function tabOrderSnapshot(page, opts = {}) {
  const steps = opts.steps ?? 8;
  const sel = opts.sel;
  if (sel) {
    await page.evaluate((s) => {
      const el = /** @type {any} */ (document.querySelector(s));
      if (el) el.focus({ preventScroll: true });
    }, sel);
    await page.waitForTimeout(80);
  }
  /** @type {string[]} */
  const out = [];
  for (let i = 0; i < steps; i++) {
    await page.keyboard.press(opts.shift ? 'Shift+Tab' : 'Tab');
    await page.waitForTimeout(40);
    out.push(await activeKey(page));
  }
  return out;
}

/**
 * 装一个自定义事件计数器（同一次页面生命周期内可反复 reset）。
 * 必须在事件可能发生**之前**装 —— 典型用法：boot 之后、按键之前。
 * @param {import('playwright-core').Page} page
 * @param {string[]} names 事件名（自定义事件与内置事件都行）
 * @returns {Promise<void>}
 */
export async function trackEvents(page, names) {
  await page.evaluate((list) => {
    const w = /** @type {any} */ (window);
    w.__nfProbeCounts = w.__nfProbeCounts || {};
    w.__nfProbeTracker = w.__nfProbeTracker || {};
    for (const name of list) {
      if (w.__nfProbeTracker[name]) continue;
      w.__nfProbeCounts[name] = w.__nfProbeCounts[name] || 0;
      const fn = () => { w.__nfProbeCounts[name] += 1; };
      // 挂在 **window** 的 capture 阶段：① 产品大量事件是 `window.dispatchEvent(...)`
      // 派发的（`workspace-open-item` 就是），挂在 document 上一条也收不到
      // （实测：画布页签已出现，计数却是 0 —— 断言因此先红了一次）；
      // ② 元素派发且 bubbles 的事件照样冒泡到 window。一处挂载覆盖两种形态。
      w.addEventListener(name, fn, true);
      w.__nfProbeTracker[name] = fn;
    }
  }, names);
}

/**
 * 读计数（未装过的名字返回 0，不是抛错 —— 断言里写 `=== 0` 才有意义）。
 * @param {import('playwright-core').Page} page
 * @param {string} name
 * @returns {Promise<number>}
 */
export async function countEvents(page, name) {
  return page.evaluate((n) => /** @type {any} */ (window).__nfProbeCounts?.[n] ?? 0, name);
}

/** 清零（同一页多段断言之间用）。 @param {import('playwright-core').Page} page @returns {Promise<void>} */
export async function resetEvents(page) {
  await page.evaluate(() => {
    const w = /** @type {any} */ (window);
    for (const k of Object.keys(w.__nfProbeCounts || {})) w.__nfProbeCounts[k] = 0;
  });
}

/**
 * 层栈深度：**读真实模块单例**，不是从 DOM 推测。
 * `/js/focusTrap.js` 与产品自己 import 的是同一份 URL ⇒ 同一实例（源树模式下；
 * 产物模式下模块被合并进 entry chunk，此腿会取到一份独立副本 —— 所以本探针
 * 只在源树根下有意义，在 `NEBFLOW_WEB_ROOT=build/web-dist` 下会返回 null，
 * 断言须显式处理该分支，不得静默当成 0）。
 * @param {import('playwright-core').Page} page
 * @returns {Promise<number|null>}
 */
export async function layerDepth(page) {
  return page.evaluate(async () => {
    try {
      const mod = await import('/js/focusTrap.js');
      return typeof mod.layerDepth === 'function' ? mod.layerDepth() : null;
    } catch {
      return null;
    }
  });
}

/**
 * 某个选择器在**当前 Tab 序里**的位置（不可 Tab 到则 -1）。
 * 用于「元素在不在 Tab 序里」这类断言 —— 比 `tabIndex >= 0` 更接近用户事实
 * （被 `[hidden]`/0 尺寸/`aria-disabled` 挡住的元素 tabIndex 仍可能 ≥ 0）。
 * @param {import('playwright-core').Page} page
 * @param {string} sel
 * @param {{steps?: number}} [opts]
 * @returns {Promise<number>}
 */
export async function tabOrderIndex(page, sel, opts = {}) {
  const steps = opts.steps ?? 40;
  const snap = await tabOrderSnapshot(page, { steps, sel: 'body' });
  // BODY 不可聚焦 ⇒ 起点用页面第一站；用 selector 反查身份
  const target = await page.evaluate((s) => {
    const el = /** @type {any} */ (document.querySelector(s));
    if (!el) return null;
    const dk = el.getAttribute && el.getAttribute('data-focus-key');
    if (dk) return `key:${dk}`;
    if (el.id) return `#${el.id}`;
    const cls = typeof el.className === 'string' && el.className.trim()
      ? `.${el.className.trim().split(/\s+/)[0]}` : '';
    return `${String(el.tagName).toLowerCase()}${cls}`;
  }, sel);
  return target ? snap.indexOf(target) : -1;
}
