// capsuleLabel.js — canvas 胶囊标签截断「可辨识前缀 + 关键尾部」的**单一来源**。
//
// 作者令 2026-11-05 12:10（手机端 canvas 顶部胶囊改横向展开，第④条）逐字：
// 「截图里文件名被截成 `..._ca...`，说明横向空间不够。规则：**优先保证可辨识
// 前缀 + 保留关键尾部**（或按电脑端的截断策略对齐），不要出现『只剩一串省略
// 号』。宽度不够时的降级策略写明。」
//
// 为什么单独一件：胶囊标签的截断此前散在两处、且两套策略 ——
//   ① 桌面/触屏腿（canvas.js §胶囊环）：`.cr-name { text-overflow: ellipsis }`
//      = 只留头部（CSS 尾切），文件名尾部（扩展名/时间戳）全丢，正是作者截图
//      里 `20261103_215300_ca...` 那种「看得出是文件、认不出是哪个文件」的形态；
//   ② 手机层腿（mobile/navController.js fitCenterLabel）：整字收束 = 只留头部
//      整字，尾部同样全丢。
// 本模块把「头 + … + 尾」的切法收成一份纯函数，两条腿都从这里取（几何/度量面
// 各用各的，切法只有一个源），避免以后再各切各的。
//
// 设计要点：
//   · **纯函数 + 注入度量**：本模块不碰 DOM 布局，只吃 `measure(text) → px`。
//     桌面腿用胶囊自身的 computed font 造探针（createTextMeasurer），手机腿可
//     复用同一探针；度量面（字体/字距/变换）由调用方保证与真实渲染一致。
//   · **头是主体、尾是关键**：尾部候选按「扩展名前 6 字 + 扩展名 → 扩展名前 3 字
//     + 扩展名 → 只留扩展名」三档降级；尾部预算上限 = 可用宽的 72%，超了就换下
//     一档 —— 保证任何宽度下头部都拿得到主体空间（不出现「…_ca.md」这种只剩
//     尾巴的形态，也不出现作者点名的「只剩一串省略号」）。
//   · **头部下限 3 个字**：连 3 个字都放不下时退到「纯头部」档（尾让位），仍保留
//     可辨识前缀 —— 这是本模块唯一允许「只留头」的极端档，且此时头部至少 1 字。
//   · 字界安全：一律按 grapheme cluster 切（中文一字 / emoji 一簇 / 拉丁一字母），
//     绝不切半个字（与手机层 fitCenterLabel 的既有铁律同规）。
//
// 契约：返回值里 `ellipsis === false` 时调用方必须**原样输出 head**（不追加
// 「…」、不加任何装饰）—— 放得下的标签零改动。

/** 省略号：与 CSS 侧 `.cr-name-mid` 的文本必须逐字一致。 */
export const CAPSULE_ELLIPSIS = '…';

/** 尾部预算上限（可用宽占比）：超过这一档尾部就降级 —— 头是辨识主体。 */
const TAIL_BUDGET_RATIO = 0.72;
/** 头部下限（grapheme 数）：低于此数据的尾部候选直接淘汰。 */
const HEAD_MIN_GRAPHEMES = 3;

/**
 * 按 grapheme cluster 切分（中文一字 / emoji 一簇 / 拉丁一字母）。
 * @param {string} text
 * @returns {string[]}
 */
export function graphemes(text) {
  const s = String(text ?? '');
  if (!s) return [];
  if (typeof Intl !== 'undefined' && Intl.Segmenter) {
    return [...new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(s)]
      .map((seg) => /** @type {{segment: string}} */ (seg).segment);
  }
  return Array.from(s);
}

/**
 * 文本度量器：`(text) => px`，`.dispose()` 摘掉探针节点。
 * @typedef {{ (text: string): number, dispose: () => void }} TextMeasurer
 */

/**
 * 造一个与目标元素**同字体面**的文本度量器（隐藏探针挂 body 直属：不受父级
 * flex 收紧 / transform scale 影响 ⇒ 量到的是未缩放的 CSS px）。
 * @param {Element} likeEl 字体基准元素（如 `.cr-name` / `.mf-strip-label`）
 * @returns {TextMeasurer} 文本渲染宽（CSS px）
 */
export function createTextMeasurer(likeEl) {
  const probe = document.createElement('span');
  probe.setAttribute('aria-hidden', 'true');
  probe.style.cssText = 'position:absolute;left:-9999px;top:0;visibility:hidden;'
    + 'white-space:nowrap;pointer-events:none;contain:layout style;';
  document.body.appendChild(probe);
  const cs = getComputedStyle(likeEl);
  probe.style.font = cs.font;
  probe.style.letterSpacing = cs.letterSpacing;
  probe.style.wordSpacing = cs.wordSpacing;
  probe.style.textTransform = cs.textTransform;
  /** @type {TextMeasurer} */
  const measure = (text) => {
    probe.textContent = String(text ?? '');
    return probe.getBoundingClientRect().width;
  };
  measure.dispose = () => probe.remove();
  return measure;
}

/**
 * 胶囊内**文本可用宽**（CSS px）= 胶囊盒宽 − 左右内衬 − 其余可见件（图标/脏点）
 * − 件缝。盒宽取 `clientWidth`（已含内衬、不含描边）；其余件取 `offsetWidth`
 * （不受自身 transform 影响）。
 * @param {HTMLElement} capsule
 * @returns {number}
 */
export function capsuleTextAvail(capsule) {
  const cs = getComputedStyle(capsule);
  const padL = parseFloat(cs.paddingLeft) || 0;
  const padR = parseFloat(cs.paddingRight) || 0;
  const gap = parseFloat(cs.columnGap) || parseFloat(cs.gap) || 0;
  let used = 0;
  let others = 0;
  for (const child of Array.from(capsule.children)) {
    const ch = /** @type {HTMLElement} */ (child);
    if (ch.classList.contains('cr-name') || ch.classList.contains('mf-strip-label')) continue;
    if (getComputedStyle(ch).display === 'none') continue;
    used += ch.offsetWidth || ch.getBoundingClientRect().width;
    others += 1;
  }
  const avail = capsule.clientWidth - padL - padR - used - gap * (others ? others : 0);
  // 下限兜底：病态窄（容器还没布局好 / 面板收起）时不留负值，交头部 1 字档。
  return Math.max(18, avail);
}

/**
 * 把一条标签切成「头 + … + 尾」。放得下 ⇒ 原样返回（ellipsis=false）。
 * @param {string} text 全名（未截断）
 * @param {number} availPx 可用宽（CSS px）
 * @param {(text: string) => number} measure 文本度量器（见 createTextMeasurer）
 * @returns {{head: string, tail: string, ellipsis: boolean}}
 */
export function splitCapsuleLabel(text, availPx, measure) {
  const full = String(text ?? '');
  if (!full) return { head: '', tail: '', ellipsis: false };
  if (!(availPx > 0) || measure(full) <= availPx) return { head: full, tail: '', ellipsis: false };
  const chars = graphemes(full);
  if (chars.length < 2) return { head: full, tail: '', ellipsis: false };

  // 尾部候选（三档降级）：扩展名识别只认短后缀（≤8 字符），避免把
  // `...20261105_120000` 这类时间戳误当扩展名。
  const extMatch = /\.[A-Za-z0-9]{1,8}$/.exec(full);
  const ext = extMatch ? extMatch[0] : '';
  const extLen = ext ? graphemes(ext).length : 0;
  const bodyChars = ext ? chars.slice(0, chars.length - extLen) : chars;
  const bodyText = bodyChars.join('');
  /** @type {string[]} */
  const tailCandidates = [];
  if (ext) {
    tailCandidates.push(graphemes(bodyText).slice(-6).join('') + ext);
    tailCandidates.push(graphemes(bodyText).slice(-3).join('') + ext);
    tailCandidates.push(ext);
  } else {
    tailCandidates.push(chars.slice(-6).join(''));
    tailCandidates.push(chars.slice(-3).join(''));
    tailCandidates.push('');
  }

  const bodyGraphemes = graphemes(bodyText);
  for (const tail of tailCandidates) {
    const reserve = measure(CAPSULE_ELLIPSIS + tail);
    if (reserve > availPx * TAIL_BUDGET_RATIO) continue;   // 尾部吃掉大半预算 ⇒ 降一档
    const headBudget = availPx - reserve;
    let head = '';
    for (let i = 1; i <= bodyGraphemes.length; i++) {
      const cand = bodyGraphemes.slice(0, i).join('');
      if (measure(cand) > headBudget) break;
      head = cand;
    }
    if (graphemes(head).length < Math.min(HEAD_MIN_GRAPHEMES, bodyGraphemes.length)) continue;
    return { head, tail, ellipsis: true };
  }

  // 极限窄（尾部三档全部超预算 / 头部凑不够 3 字）：尾巴让位，只保可辨识前缀。
  let head = '';
  for (let i = 1; i <= chars.length; i++) {
    const cand = chars.slice(0, i).join('');
    if (measure(cand) > availPx) break;
    head = cand;
  }
  return { head: head || chars.slice(0, 1).join(''), tail: '', ellipsis: true };
}
