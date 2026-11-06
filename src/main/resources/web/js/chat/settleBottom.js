// chat/settleBottom.js — 进场落底「武装窗」原语（手机端进场落顶修复批 · 2026-11-02）。
//
// ── 为什么需要它（作者 2026-11-02 20:42 实拍报障）────────────────────────────
// 「手机端：现在我点进聊天 是从消息最顶部开始读的 而不是最底部的最新的消息」。
// 实测（隔离实例 390×844 / dpr3，逐帧时间线见
// docs/20261102_*_mobile-chat-initial-scroll__*.md）真因**不是缺少钉底代码**，
// 而是钉底写在了错误的时刻：
//
//   好友/群/设备会话窗 `.fm-modal` 由 `messages.renderChatModal` 建在
//   `#fm-chat-overlay` 里、并当场 `scrollTop = scrollHeight`（messages.js 的
//   `renderMessages` 贴底分支）；**随后** `navController.watchChatModal()`
//   （mobile/navController.js）把整个 `.fm-modal` re-parent 进
//   `.mobile-page-body`。节点迁移本身会把所有者的滚动位**归零**——而这一次
//   归零发生在「内容已经渲染完、钉底已经做过」之后，热缓存重开时尤为致命：
//
//     t+0     WRITE scrollTop=2708（此时仍在 overlay 内，钳到 2040）
//     t+18ms  modal-added → scrollTop 被 re-parent 归 0（scrollHeight 2708）
//     t+1.5s  仍为 0 —— 期间**再无任何回底写入**（热路径 renderMessages →
//             preserveScrollAnchor 在顶部读几何 ⇒ isAtBottom=false ⇒ 只保持锚点）
//
//   冷路径反而绿：re-parent 时 flow 还是空的（h=clientHeight），行随后插入，静止位
//   恰好是底部。所以「一次性的同步写入」这种形态天然只对某一种时序成立。
//   同族既有注记：main.js 的 pinIfFollowing「下方增长不发 scroll 事件」。
//
// ── 选型：为什么是「rAF 合帧武装窗」而不是观察者 / 一次性补钉 ────────────
// 需求是「进场后的一小段时间内，无论什么原因导致视口离开底部，都要回到最新」。
// 造成离开的原因有**两类**，性质不同：
//   ① 内容变化 —— 异步渲染补齐、markdown 升级批、图片/富媒体字节迟到撑高。
//      MutationObserver / ResizeObserver 能看见。
//   ② 容器搬家 —— re-parent / display 切换导致滚动位归零，或容器尺寸变化。
//      **内容一个节点都没变、元素尺寸也可能不变**（`.fm-modal` 迁移前后
//      高度相同）⇒ MutationObserver 全瞎；ResizeObserver 只在尺寸真的变了才响。
//      本批的真因正是这一类。
// 只做 ① 的观察者补丁修不掉报障形态，只做 ② 的补丁又盖不住 ①。持续到截止时刻的
// 逐帧收敛（rAF）是**唯一同时覆盖两类**的判据，且它天然只在帧边界写 —— 不会产生
// 任何**可见的**中间滚动位（帧内写、帧末已贴底 ⇒ 用户看到的第一帧就是底部）。
// 代价：窗口期内每帧一次 `scrollHeight` 读 + 幂等比较（已贴底则不写，不产生多余
// scroll 事件），量级 = 72 帧 × 一次布局读，实测无长任务（见 G3 读数）。
//
// ── 边界（别修一个坑挖一个坑）──────────────────────────────────────────────
//   · **不做「是否该跟随」的判断**：那由调用方的既有单源判据决定
//     （主窗 `utils.js` 的 `isNearBottom`/`shouldFollowBottom`；会话窗
//     `msgScrollAnchor.js` 的 `isAtBottom`/`AT_BOTTOM_PX`）。本模块只在「已经被
//     要求跟随」的前提下做几何收敛 —— 禁第二份跟随意图判据。
//   · **用户一动手就解除**：wheel / touchstart / touchmove / pointerdown /
//     mousedown / keydown 任一落在 document 上 ⇒ 立即拆窗（不再补钉）。这是
//     「手动上翻后新消息不得强行拽回底部」的**唯一**硬闸：窗口只在「用户还没
//     表达过意图」的时间片里存在。窗口过期后同样不再有任何写入 —— 之后的到达
//     走各面既有跟随逻辑（贴底才跟，离开则胶囊报数）。
//   · **有截止时刻**（默认 1200ms，取自本机实测：re-parent ≤ 20ms、回包 ≤ 900ms、
//     markdown 升级批在 120 行历史下 ≤ 600ms；留一倍余量）。窗口外的撑高不追 ——
//     用户此时已经在读了，拽一次就是一次「修一个坑挖一个坑」。
//   · 零顶层副作用、零全局单例（同容器只留一枚窗口，重入 = 续期）。
//
// 落地判据见 tests/mobile-chat-initial-scroll.spec.mjs（G1–G8）。

/** 默认武装时长（ms）。取值理由见头注「有截止时刻」。 */
export const SETTLE_MS = 1200;

/** 幂等阈值（px）：差值 ≤ 1 视为已贴底（亚像素/取整，不是跟随意图阈值）。 */
const ALREADY_BOTTOM_PX = 1;

/** region → 拆窗函数（同容器只留一枚窗口）。 */
const armed = new WeakMap();

/** 用户意图事件（document 捕获期；任一命中即拆窗）。 */
const INTENT_EVENTS = ['wheel', 'touchstart', 'touchmove', 'pointerdown', 'mousedown', 'keydown'];

/**
 * 撤掉 `region` 上还活着的武装窗（窗已过期/从未武装 ⇒ 空操作）。
 * @param {Element | null} region
 */
export function cancelSettleBottom(region) {
  const off = region && armed.get(region);
  if (off) off();
}

/**
 * 武装「进场落底窗」：从现在起 `duration` 毫秒内，每帧把 `region` 收敛到真底部。
 *
 * 调用点纪律（单一职责）：只在**已经决定跟随底部**的渲染/开窗入口调用，且必须先
 * 做一次同步钉底（首帧无可见滚动由那次同步写保证，本窗负责后续时序）。同一容器
 * 重复调用 = 续期（不叠加）。
 *
 * @param {HTMLElement | null} region 可滚动的消息容器（`#chat` / `.fm-flow`）
 * @param {{duration?: number}} [opts]
 * @returns {() => void} 拆窗函数（幂等）
 */
export function settleBottom(region, opts = {}) {
  if (!region) return () => {};
  const doc = region.ownerDocument || (typeof document !== 'undefined' ? document : null);
  const dur = Number.isFinite(opts.duration) ? Math.max(0, Number(opts.duration)) : SETTLE_MS;
  cancelSettleBottom(region); // 重入 = 续期

  let done = false;
  let raf = 0;
  let timer = 0;

  /** 一帧的收敛：只读几何 + 幂等写（已贴底不写 ⇒ 不制造 scroll 事件）。 */
  const pin = () => {
    const max = region.scrollHeight - region.clientHeight;
    if (max <= 0) return; // 内容不足一屏：无需落底（不是失败，G8 短历史腿即此形态）
    if (Math.abs(region.scrollTop - max) <= ALREADY_BOTTOM_PX) return;
    region.scrollTop = region.scrollHeight;
  };

  /** @type {() => void} */
  const off = () => {
    if (done) return;
    done = true;
    if (raf) cancelAnimationFrame(raf);
    if (timer) clearTimeout(timer);
    raf = 0;
    timer = 0;
    if (doc) for (const ev of INTENT_EVENTS) doc.removeEventListener(ev, off, true);
    if (armed.get(region) === off) armed.delete(region);
  };

  const frame = () => {
    raf = 0;
    if (done) return;
    pin();
    if (!done) raf = requestAnimationFrame(frame);
  };

  if (doc) for (const ev of INTENT_EVENTS) doc.addEventListener(ev, off, { capture: true, passive: true });
  armed.set(region, off);

  pin(); // 调用瞬间就收敛一次（调用方若已同步钉底，这里是幂等空写）
  raf = requestAnimationFrame(frame);
  // 截止：**末帧再收敛一次**后拆窗（rAF 在后台标签会被节流，兜底交给定时器；
  // 定时器也不保证顺序 —— 两种收尾都幂等，谁先到谁生效）。
  timer = setTimeout(() => { if (done) return; pin(); off(); }, dur);
  return off;
}
