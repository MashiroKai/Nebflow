// mobile/sessionHead.js — 会话页头同构批（件二+件三，作者令 2026-11-02）：
// 悬浮身份头的**唯一组件工厂**（四种会话同形）。
//
// 作者令：「件二 + 件三 · 三种会话页头同构（左返回 / 右三点 / 中悬浮身份头），
// 与主对话窗视觉完全一致，且不得有横贯顶栏带」。
//
// 契约：
//   · 页头 = 只有左右两颗钮（主对话 = 既有任务/搜索键；好友/群/设备 = 页面壳
//     返回钮 + 本模块产出的右上三点键）；中间是**悬浮身份头**（圆头像 + 名字
//     玻璃胶囊在下居中），悬浮于内容之上、内容从后面穿过。
//   · 四种会话（agent / 好友 / 群 / 设备）共用同一 CSS 块（mobileLayer.css 文件尾
//     「会话页头同构批」，共享类 .mh-idhead）与本模块的同一工厂 —— 差异只许
//     来自数据（头像、名字、副标题有无）。
//   · 🔴 身份头**常显**（身份头常显批，作者 2026-11-04 20:49 裁定「按你的推荐走」）：
//     本模块曾经同时是「滚动渐隐驱动器」的唯一实现（bindIdHeadFade：scroller
//     scrollTop 0→64 写 --idhead-p，CSS 消费 opacity: calc(1 - var(--idhead-p))，
//     p≥1 另置 visibility:hidden）。该驱动器**整条退役**：
//       ① 主对话腿已在 2026-11-04「顶部头像常显批」摘除（手机端对话进场即落底 ⇒
//          scrollTop 恒 ≫64 ⇒ 身份头 100% 不可见，作者报障「顶部的头像怎么又不
//          显示了」）；
//       ② 好友/群/设备三族是同一个坏法（会话一打开消息流即贴底）⇒ 同批一并摘除。
//     作者铁律：腾空间用缩高或上移，**不许让元素消失**；身份头是身份/入口类元素
//     （头像 = profile 面入口、名字胶囊 = 群设置第二热区），必须常显。
//     零消费者 ⇒ 驱动器与 --idhead-p 一并清掉（不留死代码/死 CSS）。
//   · 与 #1365（分支 delegate/delegate-nebflow-accdcd3b）的合流注记已成历史：本
//     文件即「同一组件名 .mh-idhead / .mh-sess-head + 同一 CSS 块」的唯一落点，
//     禁两套近似样式并存（注记同步见 mobileLayer.css 文件尾批次块头注）。
//
// 零 import（本模块无状态依赖；头像/文案全部由调用方注入）—— 不新增任何
// 静态模块边（check-circular 闸友好）。

/**
 * 好友/群/设备会话的悬浮身份头组件（**唯一工厂**；agent 会话 = chatChrome.js
 * 迁移桌面共享三节点，消费同一 .mh-idhead CSS 块，禁第二份样式）。
 * 结构：`.mh-idhead.mh-sess-head` > 头像槽（调用方注入节点）+ `.mh-idhead-name`
 * 名字玻璃胶囊（空串不渲染，CSS :empty 收口）+ `.mh-idhead-sub` 副标题行
 * （数据有才挂）。右上三点键单独返回（fixed 落位在页面右上，与头本体分离）。
 *
 * `nameEl` 可选：调用方**已有**名字节点（如 `messages.js` 的 `.fm-modal-name`
 * —— 既有断言面与群设置热区都挂在该节点上）时传入即可**复用一个节点**（禁复制
 * 文本 ⇒ 零双源漂移）；本工厂只保证它带 `mh-idhead-name` 类并进头结构。
 * @param {{
 *   avatar: HTMLElement, name?: string, nameEl?: HTMLElement, sub?: string,
 *   moreTitle: string, onMore: (e: MouseEvent) => void,
 * }} spec
 * @returns {{ head: HTMLDivElement, avatarSlot: HTMLSpanElement,
 *             nameEl: HTMLSpanElement, subEl: HTMLSpanElement | null, moreBtn: HTMLButtonElement }}
 */
export function createSessionIdentityHead({ avatar, name, nameEl: givenName, sub, moreTitle, onMore }) {
  const head = document.createElement('div');
  head.className = 'mh-idhead mh-sess-head';

  const avatarSlot = document.createElement('span');
  avatarSlot.className = 'mh-idhead-avatar';
  avatarSlot.appendChild(avatar);
  head.appendChild(avatarSlot);

  const nameEl = /** @type {HTMLSpanElement} */ (givenName || document.createElement('span'));
  nameEl.classList.add('mh-idhead-name');
  if (!givenName) nameEl.textContent = name || '';
  head.appendChild(nameEl);

  let subEl = null;
  if (sub) {
    subEl = document.createElement('span');
    subEl.className = 'mh-idhead-sub';
    subEl.textContent = sub;
    head.appendChild(subEl);
  }

  const moreBtn = document.createElement('button');
  moreBtn.type = 'button';
  moreBtn.className = 'mh-sess-more g2-fab';
  moreBtn.dataset.sessMore = '1';
  moreBtn.title = moreTitle;
  moreBtn.setAttribute('aria-label', moreTitle);
  moreBtn.innerHTML = '<i data-lucide="more-horizontal"></i>';
  moreBtn.addEventListener('click', (e) => {
    // 桌面面 outside-close 监听 document 冒泡 —— 入口键停冒泡（hub 行同款）。
    e.stopPropagation();
    onMore(e);
  });

  return { head, avatarSlot, nameEl, subEl, moreBtn };
}
