// msgScrollAnchor.js — ⑩ 合并期滚动位保持（好友消息面 / Dropbox 面**共用一份实现**）。
//
// 作者 2026-09-14 17:21 令：「打开应该能够直接显示，而不是空白」，且**已显示内容不得
// 因同步到达而消失或跳动**。本模块负责后半句里的「跳动」。
//
// ── 为什么不能用 `scrollTop += (新高 − 旧高)` ──────────────────────────────
// 那个公式只在内容加在**视口上方**（prepend，如「加载更早消息」）时等价于「保持所见
// 内容」。消息面最常见的增量恰恰加在**下方**（append：`after=<水位>` 的 keyset 增量、
// `dropbox-history` 回包补齐）—— 此时高度差全部落在视口**之外**，该公式会把视图整体
// 下移一个增量高度，用户正在读的那条消息被推走。这就是「同步到达就跳」的形态。
//
// 锚点法取「当前视口顶部那一条消息节点」为参照，合并前后把它的 `offsetTop` 对回原值：
// prepend / append / 就地替换（签名变 ⇒ 单条重建）三种形态同时成立，且不需要知道
// 增量加在哪一侧。**一份实现，无第二个公式**（改一处两面同步）。
//
// 纪律：本模块零 DOM 之外的状态、零顶层副作用、不做任何渲染决策（只做滚动补偿）。
// 消息节点判据走**属性名**（`data-message-id` / `data-msg-id`）而不是调用方传谓词：
// 谓词参数在 `region.children`（`Element`）上取 `.dataset` 会各面各写一次类型断言，
// 断言一旦漏写就是新面的 checkJs 红。

/** 消息节点判据的唯一落点（属性名 → 是否命中）。 */
const isMessageNode = (n, attr) => {
  const el = /** @type {HTMLElement} */ (n);
  return !!(el.dataset && el.dataset[attr]);
};

/**
 * 视口顶部第一条消息节点（第一个「底边越过 scrollTop」的消息节点）。
 * 必须按**文档顺序**扫描并跳过非消息节点（状态行、加载更早按钮行）。
 *
 * @param {HTMLElement} region
 * @param {number} scrollTop
 * @param {string} msgIdAttr 消息 id 所在的 `data-*` 属性名（camelCase）
 * @returns {HTMLElement | null}
 */
export function firstVisibleMessage(region, scrollTop, msgIdAttr) {
  for (const n of region.children) {
    if (!isMessageNode(n, msgIdAttr)) continue;
    const el = /** @type {HTMLElement} */ (n);
    if (el.offsetTop + el.offsetHeight > scrollTop) return el;
  }
  return null;
}

/**
 * 保持 `region` 的可视锚点不动地执行一次结构性变更。
 *
 * @param {HTMLElement} region 可滚动的消息区容器
 * @param {() => void} mutate 同步改变 `region` 子节点结构的函数
 * @param {string} msgIdAttr 消息 id 所在的 `data-*` 属性名（camelCase）
 */
export function preserveScrollAnchor(region, mutate, msgIdAttr) {
  if (!region) { mutate(); return; }
  const st = region.scrollTop;
  // 已在底部 ⇒ 用户要的是「跟着最新走」，钉底优先于锚点保持（与既有 sticky 语义一致）。
  const atBottom = region.scrollHeight - region.scrollTop - region.clientHeight <= 40;
  const anchor = firstVisibleMessage(region, st, msgIdAttr);
  const anchorTop = anchor ? anchor.offsetTop : 0;

  mutate();

  if (atBottom) { region.scrollTop = region.scrollHeight; return; }
  if (anchor && anchor.isConnected) {
    region.scrollTop = st + (anchor.offsetTop - anchorTop);
    return;
  }
  // 锚点已不在文档里（该消息被服务端删/被帧丢弃）⇒ **只保持绝对位置**：
  // 绝不改判到别的锚点上（那才会真的跳），也绝不把用户扔回底部。
  region.scrollTop = st;
}
