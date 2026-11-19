// motion/collapse.js — 液态形变（Liquid Morph）语言的**展开/收起**形变腿（单源）
//
// 为什么单独一件：液态形变的单源 = ./liquidMorph.js（时长/曲线/属性白名单/降级），
// 但「一条列表行点开变成带详情的互动框」这个动作在**两处面**都要用（定时任务页的
// 条目行、待办面的列表行）。若每面各抄一份 JS，时长/曲线/属性集就会出现第二份
// 副本 —— 违反 liquidMorph.js 的纪律①。故「属性子集 + 一次性过渡 + 两端几何收敛」
// 在这里收成唯一实现，两面只喂元素与开关闭合。
//
// 形状：宿主（行/卡）两端几何**同框同边**（padding/border/radius 连续插值），
// 详情体走 width 0→1 的缩放遮罩（`.lm-collapse-clip`，见 css/design-system.css §㉔）
// + opacity —— 全部属性都在 PROPERTY_WHITELIST 内，morphTransition() 白名单外直接抛错。
//
// 禁项（与 DESIGN.md 同口径）：不加 spring / 过冲；不写第二份缓动；不改 height。
import { morphTransition, reducedMotion } from './liquidMorph.js';

/** 展开/收起吃到的属性子集（全部在白名单内；顺序固定，两处面共用同一串）。 */
const COLLAPSE_PROPS = Object.freeze([
  'background-color',
  'border-color',
  'border-radius',
  'padding-left',
  'padding-right',
  'padding-top',
  'padding-bottom',
]);

/** 宿主过渡串（建一次，两态共用 —— 同一串 = 同一动作语言）。 */
const HOST_TRANSITION = morphTransition(COLLAPSE_PROPS, { duration: 'base' });
/** 详情体过渡串（大面积显现档：大位移故走 slow，缩放 + 淡出同拍）。 */
const DETAIL_TRANSITION = morphTransition(['transform', 'opacity'], { duration: 'slow' });

/**
 * 把「详情体」元素包成可缩放遮罩：详情体被塞进 `.lm-collapse-clip` 一层，
 * 收起态 width:0 / 不占行；展开态 width:100%。
 * @param {HTMLElement} detail
 * @param {HTMLElement} [after] 插入位置（默认插到 detail 原位置）
 * @returns {HTMLElement} 遮罩层
 */
export function wrapCollapseDetail(detail, after) {
  // 幂等：详情体已在缩放遮罩里（重渲复用同一节点 / 重复调用）就直接返回，不二包。
  const parent = /** @type {HTMLElement|null} */ (detail.parentElement);
  if (parent && parent.classList.contains('lm-collapse-clip')) {
    return parent;
  }
  const clip = document.createElement('div');
  clip.className = 'lm-collapse-clip';
  clip.setAttribute('aria-hidden', 'true');
  detail.classList.add('lm-collapse-inner');
  clip.appendChild(detail);
  // after 节点必须是 parent 的直子（调用方常传 detail 自己 —— 此时退化为尾插）。
  if (after && after.parentNode !== parent) after = null;
  parent?.insertBefore(clip, after || null);
  return clip;
}

/**
 * 开合一个「可展开宿主」。幂等：同一宿主重复调用只改闭合状态。
 * @param {{host: HTMLElement, clip: HTMLElement, open: boolean}} o
 */
export function setCollapse(o) {
  const { host, clip, open } = o;
  // 显隐两态只差三个几何/材质事实（行内距、圆角、底色）由 CSS 类给出；
  // JS 只负责「同一段过渡里的属性清单 + 时长曲线」这一件事。
  host.classList.toggle('is-expanded', open);
  host.setAttribute('aria-expanded', open ? 'true' : 'false');
  clip.classList.toggle('is-open', open);
  clip.setAttribute('aria-hidden', open ? 'false' : 'true');
  if (reducedMotion()) {
    // 降级：不走过渡（类切换即刻生效；prefers-reduced-motion 二值语义保留）。
    host.style.transition = 'none';
    clip.style.transition = 'none';
    return;
  }
  host.style.transition = HOST_TRANSITION;
  clip.style.transition = DETAIL_TRANSITION;
}

/**
 * 落终态后清过渡：只清**内联值**（不写 'none' 字面量）—— 下一次 setCollapse 会
 * 重新赋值，清空这一帧也不会播过渡（同一帧内回填）。
 */
export function resetCollapse(o) {
  const { host, clip } = o;
  host.style.transition = '';
  clip.style.transition = '';
}
