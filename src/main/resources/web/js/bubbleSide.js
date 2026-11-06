// bubbleSide.js — 气泡归属方判定**单点**（bubble-side-color 批，2026-11-06）
//
// 作者令（逐字）：「消息气泡的样式现在好像绿的、白的都有。正常应该我们这方发过去
// 的是绿色，对方发过来的是白色。我们这方发的，不管是我发的还是 Agent 发的，都算
// 我们这方发，」
//
// 判据一句话：**气泡底色只由「消息归属方」决定**。
//   · SIDE_OURS   我方 = 作者本人 + 我方 Agent / delegate 回执 / 节点消息 /
//                 后台任务通知 / 工具注入 / 系统生成（凡代表本机账号发出）
//                 ⇒ 绿（CSS 令牌 --nf-bub-ours-bg）
//   · SIDE_THEIRS 对方 = 好友 / 群内他人 / 设备对端 / 外部通道 / 对方 AI
//                 ⇒ 白（CSS 令牌 --nf-bub-theirs-bg）
// 不准有第三档；不准按「消息类型 / sender 种类 / 是否有工具结果 / 是否系统生成」
// 另开配色。
//
// 为什么 JS 侧只需要**一个**函数：底色从不由 JS 计算（禁内联 style.background，
// 门禁 R4 钉死）。背景色的唯一落点是 CSS —— css/glass.css 的「气泡归属方底色单点」
// 块（两枚令牌 + 一条 `--nf-bub-ours-bg` 缺省 / `.theirs` 覆盖）。CSS 缺省档 = 我方，
// 所以「角色类 → 归属方」是**常量表**，不需要每条渲染路径各自判一次。
// JS 里真正会「算」归属方的只有一处：会话窗的 out/in（好友/群/设备），
// 即 js/messages.js 的 resolveOut() —— 它经本文件落到 `.theirs` / data-bubble-side。
//
// 门禁：scripts/check-bubble-side.mjs（R1–R5）静态钉死；
//       tests/bubble-side-color.spec.mjs 逐条 dump `sender → 归属方 → 计算背景色`。

/** 我方（绿）。 */
export const SIDE_OURS = 'ours';
/** 对方（白）。 */
export const SIDE_THEIRS = 'theirs';

/** 角色类 → 归属方常量表（气泡角色类只承载几何/语义，不承载颜色）。 */
const SIDE_BY_ROLE = {
  user: SIDE_OURS,      // 作者本人输入 / 主会话我方问句
  ai: SIDE_OURS,        // 我方 Agent 回复（作者令：Agent 发的也算我方）
  injected: SIDE_OURS,  // 工具 / delegate / 节点 / 后台任务注入行
  peer: SIDE_THEIRS,    // 对方（好友 / 群内他人 / 设备对端）
};

/** 归一化：任何非 SIDE_THEIRS 的入参都落我方（缺省安全向 = 绿，绝不漏成第三色）。 */
export function bubbleSide(side) {
  return side === SIDE_THEIRS ? SIDE_THEIRS : SIDE_OURS;
}

/** 角色类 → 归属方。未登记角色一律我方。 */
export function bubbleSideOfRole(role) {
  return SIDE_BY_ROLE[role] || SIDE_OURS;
}

/** 出向（out=true）⇒ 我方；入向 ⇒ 对方。会话窗唯一的方向→归属方换算。 */
export function bubbleSideOfOutgoing(out) {
  return out === true ? SIDE_OURS : SIDE_THEIRS;
}

/**
 * 生成气泡类的**唯一**工厂：`bubbleClass('ai')` → `'bubble ai ours'`。
 * @param {string|null} role 'user' | 'ai' | 'peer' | 'injected' | null
 * @param {string} [side] 显式归属方（缺省由 role 查表）
 * @returns {string}
 */
export function bubbleClass(role, side) {
  const s = bubbleSide(side === undefined ? bubbleSideOfRole(role) : side);
  return ['bubble', role || '', s].filter(Boolean).join(' ');
}

/** 把归属方写到元素上（类 + data 属性；data 面供门禁/真渲染 dump 同源读取）。 */
export function markBubbleSide(el, side) {
  const s = bubbleSide(side);
  if (!el) return el;
  el.classList.add(s);
  el.dataset.bubbleSide = s;
  return el;
}
