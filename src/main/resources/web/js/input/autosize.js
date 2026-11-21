// input/autosize.js — 输入框自适应行高 + 程序化写值的**叶子**模块（无 import）。
//
// 为什么独立成叶子：`chatView.js`（草稿恢复）需要 setComposerText，而 composer.js
// 经 ws.js 回指 chatView.js —— 直接从 composer.js 引入会形成静态环
// （chatView → composer → ws → chatView，scripts/check-circular.mjs 红）。
// 量高 / 锚定 / 写值都只碰 DOM，不需要任何模块依赖，故单独放在此叶子里；
// composer.js 原样再导出（既有导入面不变）。

/**
 * ④ 自适应行高（autosize）唯一底座 —— composer 统一批（2026-11-18 作者令
 * 「主会话与消息联系人 composer 真正统一为同一组件……含 autosize」）。
 *
 * Why this exists: before this batch the grow line
 * `input.style.height = Math.min(input.scrollHeight, 200) + 'px'` was written out
 * INLINE at five sites in `input.js` and the fm composer had no autosize at all
 * (single-line `<input>`), so the two composers could not agree on height. The
 * measurement now lives HERE once; every consumer calls it (主会话 = input.js
 * 五处调用点，语句逐字节等价；消息面 = fmComposer 的 input 事件).
 *
 * Callers that need a two-pass measure set `height:'auto'` first (input.js does),
 * then call this — the primitive deliberately holds ONLY the capped measurement
 * so the primary window's growth stays byte-identical to pre-batch behavior.
 *
 * 🔴 滚动锚定（输入框显示缺陷批 2026-11-21，作者报障「内容在输入框中间、没有
 * 完全显示」）。两个实因都在**这一处测量动作**里，与高度上限本身无关：
 *   ① 塌陷回填：`height:'auto'` 把盒子收到一行，第二刀 `min(scrollHeight,max)`
 *      才撑起来 —— 但只有**走过 input 事件**的调用点才做这一对。程序化写值腿
 *      （草稿恢复 chatView.restoreDraft / 历史召回 / 重发 / 语音替换）只写
 *      `style.height='auto'` 就收工 ⇒ 20 行内容装进 1 行高的框，可见 1–2 行、
 *      其余被裁（实测 desktop 8% / mobile 7% 可见，见 tests/chat-input-autosize.spec.mjs
 *      D1 腿）。本函数因此自己保证「先塌陷再量」（measure）—— 调用点无需记得。
 *   ② 滚动锚点失管：盒子封顶后内容比视口高，而 `scrollTop` 只由浏览器在
 *      「用户正在打字」时跟手。程序化写值 / 光标移动后 scrollTop 留在旧值
 *      （常见 = 0）⇒ 视口停在文稿开头，光标在末行 ⇒ 用户看到的是**中间一段切片**
 *      （首行与末行都不可见）。本函数因此把锚点**钉在光标行**（四向可见性保证：
 *      光标上一行也要在视口里，避免"只看得到半行"）。
 *
 * 光标不可见时必须滚到光标 TEM —— 那是「光标始终可见」这条验收的唯一判据，
 * 不是「滚到底」（光标可能在文稿中间）。
 *
 * @param {HTMLTextAreaElement|HTMLInputElement|null} input
 * @param {number} [max] cap in px (主会话既有档 = 200)
 * @param {{ scroll?: boolean, measure?: boolean }} [opts]
 *   `measure` 缺省 true（自己先塌陷再量，幂等）；`scroll` 缺省 true（把光标拉回视口）。
 */
export const COMPOSER_MAX_PX = 200;

// 光标真行的测量盒（懒建一次、之后复用）。textarea **没有** Range/光标矩形 API ——
// 只有「按 \n 数行」的近似，而近似在折行（soft wrap）下会大幅低估：一条 20 行、
// 每行都在窄框里折成两行的草稿，实际有 40 个视觉行，按 \n 数只算 20 ⇒ 锚定滚不到
// 光标（这正是"尾部看不见"的实测成因，见 tests/chat-input-autosize.spec.mjs C1 腿）。
// 所以光标 y 走镜像盒：同宽/同字体/同内距/pre-wrap 的绝对定位盒里塞「光标之前的
// 文本 + 零宽标记」，标记的 offsetTop 就是光标的内容坐标。零布局影响（盒在屏外、
// visibility:hidden 也照算布局）。
/** @type {HTMLElement|null} */
let caretMirror = null;
const CARET_MARK = '\u200b';

function caretContentY(input) {
  const cs = getComputedStyle(input);
  if (!caretMirror) {
    caretMirror = document.createElement('div');
    caretMirror.setAttribute('aria-hidden', 'true');
    caretMirror.style.cssText = 'position:absolute;left:-99999px;top:0;visibility:hidden;'
      + 'white-space:pre-wrap;overflow-wrap:break-word;word-break:break-word;pointer-events:none;';
    (document.body || document.documentElement).appendChild(caretMirror);
  }
  const m = caretMirror;
  m.style.width = input.clientWidth + 'px';
  m.style.font = cs.font;
  m.style.lineHeight = cs.lineHeight;
  m.style.padding = cs.padding;
  m.style.boxSizing = cs.boxSizing;
  m.textContent = input.value.slice(0, input.selectionStart ?? input.value.length);
  const mark = document.createElement('span');
  mark.textContent = CARET_MARK;
  m.appendChild(mark);
  // 相对镜像盒 padding box 顶（镜像盒自带同款 padding ⇒ 与 textarea 内容坐标同源）
  return mark.offsetTop;
}

export function growComposerInput(input, max = COMPOSER_MAX_PX, opts = {}) {
  if (!input) return;
  const { scroll = true, measure = true } = opts;
  // ① 先塌陷再量：只量一次 scrollHeight 会把「当前盒子高度」算进结果 —— 已经
  //    封顶的框里 scrollHeight 恒等于内容高，但一行高的框里 scrollHeight 也是
  //    内容高，所以塌陷不是为了读准 scrollHeight，而是为了**内容不足时能收回去**
  //    （删文本、清空、切会话）。塌陷是 style 写，零布局抖动损失。
  const prevScrollTop = input.scrollTop;
  if (measure) input.style.height = 'auto';
  const contentH = input.scrollHeight;
  input.style.height = Math.min(contentH, max) + 'px';
  // ② 锚定：内容没超出视口 ⇒ 回到顶部（没有可滚的东西，留旧值只会造成空白错觉）；
  //    超出 ⇒ 保证光标行（含其上一行）在视口内。`scrollTop` 的写必须在高度落定之后。
  if (!scroll) return;
  const overflow = contentH - input.clientHeight;
  if (overflow <= 0) {
    input.scrollTop = 0;
    return;
  }
  const caret = typeof input.selectionStart === 'number' ? input.selectionStart : null;
  if (caret === null) {
    // 无光标信息（未聚焦 / 非 textarea）：保持既有位置，只夹进合法区间。
    input.scrollTop = Math.min(prevScrollTop, overflow);
    return;
  }
  const cs = getComputedStyle(input);
  const lineH = parseFloat(cs.lineHeight) || 0;
  const caretTop = caretContentY(input);
  const caretBottom = caretTop + lineH;
  // 目标视口 = [scrollTop, scrollTop + clientHeight]；上下各留一行的呼吸位，
  // 让「光标行 + 其上一行」同时可见（只露半行是最常见的"没完全显示"观感）。
  const upper = Math.max(0, caretTop - lineH);
  const lower = caretBottom + lineH;
  let next = prevScrollTop;
  if (upper < next) next = upper;
  if (lower > next + input.clientHeight) next = lower - input.clientHeight;
  input.scrollTop = Math.max(0, Math.min(next, overflow));
}

/**
 * ⑥ 程序化写值唯一入口（`setComposerText`）—— 输入框显示缺陷批（2026-11-21）。
 *
 * Why this exists: 产品里有六条腿把文本**程序化**塞进输入框（草稿恢复 / 历史召回 /
 * 重发上一发 / 语音替换 / 快捷指令预填 / 队列项撤回重编）。它们此前的写法各不相同，
 * 但都只有「`value = …` + 偶发 `style.height='auto'`」两句 —— 一句话都没有：
 *   ① 塌陷：`height='auto'` 之后没人再量 ⇒ 20 行草稿装进 1 行高的框（实测可见 7–8%）；
 *   ② 锚点：光标 setSelectionRange 到末尾，但 scrollTop 留在旧值（常见 0）⇒ 视口停在
 *      文稿开头，用户看到「中间一段」，尾部（光标）在哪看不见。
 *
 * 这两个症状就是作者 2026-11-21 报的「内容在输入框中间、没有完全显示」。所以程序化
 * 写值不再各自拼句子，统一走这一个入口：写值 → 量高（封顶）→ 锚定光标。
 *
 * 用法：`setComposerText(input, text)`（可选 `caret` 覆盖落点；缺省 = 文本末尾）。
 * 只做一件事的调用方（仅清空）也可用 `growComposerInput(input)` 单独补量。
 *
 * @param {HTMLTextAreaElement|HTMLInputElement|null} input
 * @param {string} text
 * @param {{ caret?: number|null, max?: number, focus?: boolean }} [opts]
 * @returns {boolean} true = 真的写了（input 缺席时 false）
 */
export function setComposerText(input, text, opts = {}) {
  if (!input) return false;
  input.value = String(text ?? '');
  // 量高 + 锚定一次成型（measure 内含塌陷，所以这里不必先写 height='auto'）。
  growComposerInput(input, opts.max ?? COMPOSER_MAX_PX);
  if (opts.focus) input.focus();
  const len = input.value.length;
  const caret = typeof opts.caret === 'number' ? Math.max(0, Math.min(opts.caret, len)) : len;
  input.setSelectionRange(caret, caret);
  // 落点定了之后再锚一次：上面那次量高用的是「写值前」的光标位置。
  growComposerInput(input, opts.max ?? COMPOSER_MAX_PX, { measure: false });
  return true;
}
