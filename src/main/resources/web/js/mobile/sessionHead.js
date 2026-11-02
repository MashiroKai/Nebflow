// mobile/sessionHead.js — 会话页头同构批（件二+件三，作者令 2026-11-02）：
// 悬浮身份头的**唯一行为实现**（滚动渐隐驱动器 + 头组件工厂）。
//
// 作者令：「件二 + 件三 · 三种会话页头同构（左返回 / 右三点 / 中悬浮身份头），
// 与主对话窗视觉完全一致，且不得有横贯顶栏带」。
//
// 契约：
//   · 页头 = 只有左右两颗钮（主对话 = 既有任务/搜索键；好友/群/设备 = 页面壳
//     返回钮 + 本模块产出的右上三点键）；中间是**悬浮身份头**（圆头像 + 名字
//     玻璃胶囊在下居中），悬浮于内容之上、内容从后面穿过、滚动渐隐。
//   · 四种会话（agent / 好友 / 群 / 设备）共用同一 CSS 块（mobileLayer.css 文件尾
//     「会话页头同构批」，共享类 .mh-idhead）与本模块的同一渐隐实现 —— 差异只许
//     来自数据（头像、名字、副标题有无）。
//   · 🔴 与 #1365（分支 delegate/delegate-nebflow-accdcd3b）的对接点：#1365 在做
//     同一件事（主对话顶栏回退悬浮形态）但尚无提交可消费；本文件即「同一组件名
//     （.mh-idhead / .mh-sess-head）、同一 CSS 块（会话页头同构批）、同一渐隐
//     驱动器（bindIdHeadFade）」的落点 —— 合流时**只保留一份**：若 #1365 另带了
//     近似实现，以其组件名对齐本文件的类名与 CSS 块并删另一套，禁两套近似样式
//     并存（对接注记同步见 mobileLayer.css 文件尾批次块头注）。
//
// 零 import（本模块无状态依赖；头像/文案全部由调用方注入）—— 不新增任何
// 静态模块边（check-circular 闸友好）。

/** 渐隐行程（px）：scroller.scrollTop 0→64 映射 --idhead-p 0→1。 */
export const IDHEAD_FADE_DIST = 64;

/**
 * 滚动渐隐驱动器（**唯一实现**；主对话 #chat 与好友/群/设备 .fm-flow 共用）。
 * 把滚动量写成 head 上的 `--idhead-p`（0→1，三位小数），CSS 消费：
 *   opacity: calc(1 - var(--idhead-p))；全程走 rAF 合帧，值不变不写（零无效样式）。
 * 多个滚动容器（会话页 = .fm-flow 与 .mobile-page-body 两处都可能持有滚轴）取
 * **最大 scrollTop** 作为进度源 —— 单一口径，禁两套进度互相覆盖。
 * reduced-motion ⇒ 二值进度（滚过即隐，无插值）。
 * @param {HTMLElement} head 身份头元素（.mh-idhead）
 * @param {...HTMLElement} scrollers 滚动容器（#chat / .fm-flow / .mobile-page-body）
 * @returns {() => void} 解绑函数
 */
export function bindIdHeadFade(head, ...scrollers) {
  const list = scrollers.filter(Boolean);
  if (!head || !list.length) return () => {};
  let raf = 0;
  const reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const apply = () => {
    raf = 0;
    let st = 0;
    for (const s of list) st = Math.max(st, s.scrollTop || 0);
    let p = Math.min(1, Math.max(0, st / IDHEAD_FADE_DIST));
    if (reduced) p = p >= 0.5 ? 1 : 0;
    const v = String(Math.round(p * 1000) / 1000);
    if (head.style.getPropertyValue('--idhead-p') !== v) head.style.setProperty('--idhead-p', v);
    // 全隐后连命中面一并退出（渐隐头像不可见却拦截点击 = 坏手感）。
    const vis = p >= 1 ? 'hidden' : '';
    if (head.style.visibility !== vis) head.style.visibility = vis;
  };
  const onScroll = () => { if (!raf) raf = requestAnimationFrame(apply); };
  for (const s of list) s.addEventListener('scroll', onScroll, { passive: true });
  apply();
  return () => {
    for (const s of list) s.removeEventListener('scroll', onScroll);
    if (raf) cancelAnimationFrame(raf);
  };
}

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
