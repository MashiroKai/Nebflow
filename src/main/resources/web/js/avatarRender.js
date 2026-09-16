// avatarRender.js — 头像 <img> 的**复用池** + 群头像九宫格的**内容签名**。
//
// 2026-09-17 uifix 批（作者令逐字：「另外群头像没有被缓存，我每次点进群，对话框的
// 群头像都会闪一下。另外再检查一下，如果好友使用默认头像的话，也会闪一下的问题
// 有没有修复。」）——本模块 = ②③ 两条的**唯一**新增件。
//
// ── 根因（改前实测，读数见报告 §②；证据 .nebflow/evidence/20260917_uifix/）──
// `messages.js::paintGroupAvatarInto` 改前是 `host.innerHTML = ''` + 重建：
//   ① **元素重建**：每次重绘把 6 枚 `<img>` 全部销毁重造（实测 `rebuild=1` /
//      `imgCreate=6` / 节点复用 `0/6` 每次进群）⇒ 新节点必须重新解码，解码期间
//      槽位没有可绘制位图 ⇒「闪一下」；
//   ② **两级数据序导致的二次重绘**：窗头首帧用列表行 `conv.memberAvatars`，
//      名册腿（`getGroupMembers`）到达后 `refreshOpenGroupHeaderAvatar` 再整体
//      重绘一次 ⇒ 每次进群**必**发生一次全量替换。列表行字段缺席时首帧是群名
//      首字母、二次重绘换成六宫格 ⇒「字母 → 头像」的更明显一次闪。
//
// ── 本模块的两件（与 ②③ 判据一一对应）──
//  1. `avatarImgNode(url)`：**已解码节点复用池**。同 URL 若池中有**已脱离文档**
//     的 `<img>`（= 上一次开窗遗留、位图仍在内存、`complete` 恒真），直接复用
//     ⇒ 重新挂载**零网络、零解码、零空白帧**。禁止「每次重拉重绘」（作者令）。
//     🔴 池**只回收脱离文档的节点**：仍在文档里的节点被别的槽位（如会话列表行）
//     占着，抢走会把那一处挖空 ⇒ `isConnected` 闸是正确性必需，不是优化。
//  2. `avatarCellsSignature(list, total, label)`：九宫格**内容签名**（userId +
//     avatarUrl + 总数 + 降级文案）。渲染侧用它做「内容没变 ⇒ 零 DOM 操作」的
//     前置闸 —— 这是「禁先清空再赋值」的机械落点（签名相同 ⇒ 不碰 DOM）。
//
// 🔴 池是**会话内**内存缓存（不落 localStorage）：头像字节来自跨源头像源站
// （无 CORS 头，浏览器直 fetch 恒失败 —— 见 `avatarCache.js` 头部 2026-09-07
// 取证），因此**字节级**缓存必须走后端代理，本批不动 `src/main/scala/**`；
// 而**已解码节点**不需要读字节就能复用，是同一目标（不闪）在纯前端可达的实现。
// 上限 = LRU 兜底，防长会话累积（头像 URL 数 ≪ 上限，正常永不触发）。

/** 每个 URL 最多留几个节点（同一头像可能同时出现在列表行 + 窗头 + 抽屉）。 */
const MAX_PER_URL = 3;
/** URL 条目上限（LRU：超限淘汰最久未用的 URL 组）。 */
const MAX_URLS = 160;

/** @type {Map<string, HTMLImageElement[]>} url -> 节点组（组内按 LRU 序，末尾最新） */
const pools = new Map();

function dropUrl(url) {
  pools.delete(url);
}

/**
 * 取一枚可用的头像 `<img>`：优先复用池中**已脱离文档**的解码节点，否则新建。
 *
 * @param {string} url 远端头像 URL（调用方已判非空）
 * @returns {HTMLImageElement}
 */
export function avatarImgNode(url) {
  let list = pools.get(url);
  if (list) {
    // 池内已有**游离**节点 ⇒ 直接复用（位图在内存里，挂载即出图）。
    const i = list.findIndex((n) => !n.isConnected);
    if (i >= 0) {
      const n = list.splice(i, 1)[0];
      list.push(n); // LRU touch
      return n;
    }
  } else {
    list = [];
    pools.set(url, list);
    if (pools.size > MAX_URLS) {
      const oldest = pools.keys().next();
      if (!oldest.done && oldest.value !== url) dropUrl(oldest.value);
    }
  }
  const img = document.createElement('img');
  img.src = url;
  img.alt = '';
  list.push(img);
  // 组内只留游离节点（在文档里的是活的，不能丢）。
  while (list.length > MAX_PER_URL) {
    const j = list.findIndex((n) => !n.isConnected);
    if (j < 0) break;
    list.splice(j, 1);
  }
  return img;
}

/** 群头像九宫格的内容签名（跨帧可比的纯字符串；`''` 表示空列表）。
 *
 *  纳入签名的量 = **决定渲染结果的全部输入**：逐格身份 + 头像 URL（顺序语义
 *  = 服务端加入序，纯透传 ⇒ 顺序是内容的一部分）+ 成员总数（决定 `+N`）+
 *  降级文案（无格子时的首字母）。
 *
 *  @param {Array<{userId?: string, avatarUrl?: string, name?: string}>|undefined} list
 *  @param {number} total
 *  @param {string} [label] 降级态用的显示名（格子为空时决定首字母）
 *  @returns {string} */
export function avatarCellsSignature(list, total, label) {
  const cells = Array.isArray(list) ? list : [];
  const parts = cells.map((c) => `${(c && c.userId) || ''}\u0001${(c && c.avatarUrl) || ''}`).join('\u0002');
  return `${cells.length}\u0003${Number(total) || 0}\u0003${label || ''}\u0003${parts}`;
}

/** 测试/诊断用：池现状（只读）。 */
export function avatarPoolStats() {
  let nodes = 0;
  let detached = 0;
  for (const list of pools.values()) {
    nodes += list.length;
    detached += list.filter((n) => !n.isConnected).length;
  }
  return { urls: pools.size, nodes, detached };
}
