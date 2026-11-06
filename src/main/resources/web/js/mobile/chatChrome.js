// mobile/chatChrome.js — 聊天根顶缘悬浮双键 + 页标题入流（liquid-glass-v2 批
// 2026-10-31，作者 08:43 裁定② + 09:03 拍板 v2 + 追加折射令；取代 dock 批的
// 「顶栏双键」形态 —— 顶栏本体随本批退役，见 mobileLayer.css 顶栏退役块）。
//
// v2 契约（设计稿 §3.1 字面落地）：
//   · 无 header 容器 —— #header 手机层 display:none（mobileLayer.css）；
//   · 左右悬浮圆钮：任务/搜索迁入自建的 #mobile-fab-layer（48px v2 液体玻璃
//     正圆 = .g2-fab 共享材质：blur/sat 双档、上半镜面高光、spring 按压回弹、
//     边缘光折射；liquid-glass.css），top:16px 左右 16px，z 高于顶缘渐隐带；
//    任务键徽标 = taskBadgeCount 镜像（dock 批机制零变化）；
//    端区分批（作者令 2026-11-05 09:28，逐字）：「手机端的待办…跟电脑端的区分开，
//    电脑端是一个胶囊，手机端还是需要是之前那个左上角的一个悬浮的待办按钮」
//    ⇒ 左上「任务」圆钮**恢复**（入口收敛批 2026-11-04 撤过、本批反转），手机端
//    不再显示顶带待办胶囊（mobileLayer.css 的 #task-list display:none 档恢复）；
//    桌面端零改动（胶囊是桌面唯一入口，不受 body.mobile-layer 作用域影响）。
//   · 页标题入流：原 #header .header-center 三节点（#agent-avatar-btn /
//     #session-name / #memory-btn）整体迁入 #chat 内容流首的 .g2-chat-head，
//     节点迁移非复制：桌面 JS（agentProfile/sidebar 改名链）照写原节点，
//     零镜像漂移；chatView.setSession 清空保留本层（chatView.js 同批注记）。
//     功能谱系不动：头像 = profile 面板入口（PANEL 链全绿），记忆钮 hidden 常态。
//     形态沿革：Muse 式悬浮头像批（2026-11-01 13:33）→ sticky 顶锚大圆头像 +
//     名字下置；**会话顶栏单行三段式批（2026-11-02 07:23 作者令）** 曾把该层改为
//     position:fixed 的真顶栏（头像 40px 与名字并入顶栏同一行、与左右双钮同轴）；
//     **会话页头同构批（件二+件三，2026-11-02 15:xx 作者令）把 fixed 顶栏整条
//     退役**，该层落回**悬浮身份头**：`.g2-chat-head.mh-idhead.mh-agent-head`
//     （绝对定位、零全宽带、内容从其后穿过，**身份头常显**），与好友/群/设备会话的
//     `.mh-idhead.mh-sess-head` **共用同一 CSS 块与同一工厂**
//     （mobileLayer.css 文件尾「会话页头同构批」+ sessionHead.js
//     createSessionIdentityHead）。
//     🔴 身份头的滚动渐隐已**整条退役**：主对话腿在「顶部身份头常显批」
//     （作者报障 2026-11-04 19:54「手机端的顶部的头像怎么又不显示了」）摘除；
//     好友/群/设备三族在「身份头常显批」（作者 2026-11-04 20:49 裁定「按你的
//     推荐走」）同口径摘除。四种会话身份头一律常显，`--idhead-p` 零消费者已删。
//     节点迁移/清空守望链不变；`--muse-head-top` 变量链仍不存在（无消费方）。//   · dock 收形批（作者令 10-31 22:50 ①③）的「每条 agent 消息泡上方补挂
//     .g2-msg-meta 身份头」随 Muse 式悬浮头像批**整批退役**（作者令 ①：
//     「去掉每条消息头部的 agent 头像+名字行」）—— 守望/装饰/摘除链全数
//     摘除，本文件不再触碰消息行；会话级身份 = .g2-chat-head 悬浮锚一处。
//   · 顶缘 88px 渐隐带 #g2-edge-fade（index.html 静态节点 + liquid-glass.css；
//     主 JS 顶带实测把它计入 #chat 顶让位，main.js 同批）——本文件不管其显隐：
//     push 页（#mobile-pages z156）全屏不透明直盖其上，零 JS 对账。
//
// 桌面零改动边界：本模块只由 mobile/bootstrap.js 在手机视口激活/拆除；拆除 =
// 双键与两层容器摘除 + 三节点按原序归还 #header .header-center（锚点惯例同
// navController paneAnchors：记录原 parent + next，teardown insertBefore 还原）。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { todoStore } from '../todoStore.js';
import { pushPage, touchLayer } from './navController.js';
// 层事件流水（循环门禁修复批 2026-11-04）：事件走叶子 navEvents.js 单点。
import { logEvent } from './navEvents.js';
import { paintSlot } from '../agentProfile.js';
import { brand } from '../brand.js';

/** @type {HTMLButtonElement|null} */
let taskBtn = null;
/** @type {HTMLButtonElement|null} */
let searchBtn = null;
/** @type {HTMLElement|null} 悬浮圆钮层（#mobile-fab-layer）。 */
let fabLayer = null;
/** @type {HTMLElement|null} 页标题入流层（.g2-chat-head）。 */
let chatHead = null;
/** @type {MutationObserver|null} 聊天清空路径守望（见 activateChatChrome 注记）。 */
let headMo = null;
/** @type {Array<() => void>} */
let unsubs = [];

/* ── 会话头头像空态回落 stand logo（批③；2026-11-01 起为**兜底腿**）────────────
 *  #agent-avatar-btn 两端共享节点。桌面腿补齐批（2026-11-01，作者令 10-31 22:50
 *  「agent没设置头像之前 用我们stand的logo」）后，agentProfile.paintSlot 自绘
 *  stand logo（同名 .agent-avatar-stand-logo）——本守望退为兜底：仅当槽内既无
 *  头像图也无 logo 时（理论不可达，防时序空窗）补注入。原先「首字母牌保留」的
 *  分档随作者令口径作废：无头像一律 logo（首字母牌/人形字形两档均已退役）。 */
/** @type {MutationObserver|null} 会话头头像槽守望（空态 logo 兜底注入）。 */
let avatarSlotMo = null;
/** 给会话头槽注入 stand logo（空态判定幂等；有图/有 logo 时摘除注入）。 */
function paintAgentSlotLogo(slot) {
  const hasImg = !!slot.querySelector('.agent-avatar-img');
  const hasLogo = !!slot.querySelector('.agent-avatar-stand-logo');
  const injected = slot.querySelector('.agent-avatar-stand-logo');
  if (hasImg || hasLogo) {
    if (injected && hasImg) injected.remove(); // 有真头像：注入档让位（自绘档不在此列）
    return;
  }
  const pic = document.createElement('picture');
  pic.className = 'agent-avatar-stand-logo';
  pic.innerHTML =
    `<source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">` +
    `<img class="agent-avatar-logo-img" src="${brand.logoUrl}" alt="">`;
  slot.replaceChildren(pic);
}

/* 待办徽标读数（作者令 2026-11-05 17:02 附图：入口右上角 = **未完成数**）——
   🔴 根因修复（灵动岛批 2026-11-05）：读数原走 `taskPanel.taskBadgeCount()`，而
   该函数带一道会话门（`sessionShowsNodeEntries(activeSessionId)`，见
   taskPanel.js:108）——只有「显示节点条目的会话」才非零，普通会话恒 0 ⇒
   `hidden` 恒真 ⇒ **角标永不出现**（作者附图里那枚「8」在本实现下渲染不出来）。
   手机端这颗圆钮是**全局待办入口**（面板体 todoFace 读的就是 todoStore 全量
   scope:all），读数门与会话无关 ⇒ 直接取真源 `todoStore.openCount()`（与面板头
   `.tfl-cap-faces` 同一个读数源，零第二份计数逻辑）。
   沿革：入口收敛批 2026-11-04 撤过该圆钮，端区分批 2026-11-05 09:28 恢复时
   照抄了旧读数口（连带旧门）；本批只换读数源，建层/落位/角标形态零改。

   角标数字变化走**同一套弹簧**（作者「角标数字变化也要走同一套弹簧，不许瞬间
   跳变」）：参数与桌面岛形变同档（response 0.38 / ζ 0.82 / mass 1，k=ω²≈273.4，
   c=2ζω≈27.1），四条通道（scale / 位移 / 透明度……本处用 scale + 垂直位移两条）
   同拍；本地 6 行积分器（不新增模块边，check-circular 友好），定步长子步进
   保证帧率无关（与 todoFace.springAnimate 同一口径）。 */
const BADGE_SPRING = { response: 0.38, damping: 0.82 };
const REDUCED_MOTION = typeof matchMedia === 'function' &&
  matchMedia('(prefers-reduced-motion: reduce)').matches;

/** 徽标读数脉冲：scale 1→1.22→1（弹簧，非关键帧）—— 数字变了才有动作。 */
function badgePulse(el) {
  if (!el || REDUCED_MOTION) return;
  const w0 = (2 * Math.PI) / BADGE_SPRING.response;
  const k = w0 * w0;
  const c = 2 * BADGE_SPRING.damping * w0;
  let x = 0.78; // 起始收缩档（新读数从「小」弹回 1，超调 ~1%）
  let v = 0;
  let raf = 0;
  let last = performance.now();
  const tick = (now) => {
    const frame = Math.min((now - last) / 1000, 1 / 30);
    last = now;
    const steps = Math.max(1, Math.ceil(frame / (1 / 240)));
    const dt = frame / steps;
    for (let i = 0; i < steps; i++) {
      const a = -k * (x - 1) - c * v;
      v += a * dt;
      x += v * dt;
    }
    el.style.scale = String(x);
    if (Math.abs(x - 1) < 0.004 && Math.abs(v) < 0.02) {
      el.style.scale = '';
      cancelAnimationFrame(raf);
      return;
    }
    raf = requestAnimationFrame(tick);
  };
  cancelAnimationFrame(el.__badgeRaf || 0);
  el.__badgeRaf = requestAnimationFrame(tick);
}

function syncTaskBadge() {
  if (!taskBtn) return;
  const n = todoStore.openCount();
  const b = /** @type {HTMLElement|null} */ (taskBtn.querySelector('.mh-chrome-badge'));
  if (!b) return;
  const next = n > 99 ? '99+' : String(n);
  const changed = b.textContent !== next;
  b.hidden = n === 0;
  b.textContent = next;
  taskBtn.title = t('taskpanel.title');
  if (changed && n > 0) badgePulse(b); // 数字变化 = 同一条弹簧（不瞬跳、不跳帧）
}

export function activateChatChrome() {
  const header = document.getElementById('header');
  const main = document.getElementById('main');
  const chat = document.getElementById('chat');
  if (!header || !main || !chat) return () => {};

  // ── 页标题入流：header-center 三节点迁入 #chat 内容流首的悬浮身份头 ──
  const center = header.querySelector('.header-center');
  if (center && center.children.length) {
    chatHead = document.createElement('div');
    // 会话页头同构批：三种会话窗的 `.mh-idhead.mh-sess-head` 与本节点共享同一
    // CSS 块（居中头像 + 名字玻璃胶囊，常显）；`g2-chat-head` 类保留（既有
    // 断言面/chatView 清空守望/桌面归还链都按该类定位）。
    chatHead.className = 'g2-chat-head mh-idhead mh-agent-head';
    // 先建层占位内容流首位，再按原序迁节点（teardown 原序归还 = 结构还原）。
    chat.insertBefore(chatHead, chat.firstChild);
    for (const node of [...center.children]) chatHead.appendChild(node);
    /* 聊天清空路径守望：桌面历史重放/会话迁移等 5 处 innerHTML='' 清空
       （historyReplay.js:99 / sidebar.js:2326 / persistence.js:1684 /
       main.js:2897 / chatView.setSession —— 末者已保留本层）会连迁入节点
       一并摘出（模块引用保底不回收，agentProfile 槽位写入不丢）。任一清空
       落幕后把层插回内容流首位，等价「页标题随会话存续」。守望只判
       isConnected，消息追加等常规 childList 变动零成本。 */
    headMo = new MutationObserver(() => {
      if (chatHead && chatHead.parentElement !== chat) {
        chat.insertBefore(chatHead, chat.firstChild);
      }
    });
    headMo.observe(chat, { childList: true });
    /* 身份头**不接**滚动渐隐（身份头常显批 2026-11-04，作者 20:49 裁定「按你的
       推荐走」）。历史读数留档：11-02 同构批接入的 bindIdHeadFade 把 scrollTop
       0→64 映射成 `--idhead-p`，CSS 消费 `opacity: calc(1 - var(--idhead-p))`、
       p≥1 另置 visibility:hidden —— 手机端对话**进场即落底**（任何超过一屏的
       会话 scrollTop 恒 ≫ 64），于是整块（头像 + 名字）在**有内容的会话里 100%
       不可见**，连 `pointer-events:auto` 的头像一起退场 = profile 面板入口不可达。
       主对话腿先摘（顶部身份头常显批），好友/群/设备三族同批摘除 —— 四种会话
       现在同一口径：身份头是身份/入口类元素，作者铁律「腾空间用缩高或上移，
       不许让元素消失」⇒ 常显。驱动器与 --idhead-p 已零消费者（整条删除）。 */
    /* 顶栏建层广播已随机制退役（会话页头同构批）：悬浮身份头不在流内、main.js
       的顶让位不再按它实测（回 bandBottom 单一真值），故无需通知观察器重取。 */
  }

  // ── 消息流身份头（收形批①③）随 Muse 式悬浮头像批退役 ──
  // 作者令 2026-11-01 13:33 ①「去掉每条消息头部的 agent 头像+名字行」：
  // 守望/装饰/全量重刷链整批摘除（原 repaintAllMsgMeta + #chat childList
  // 守望 + avatar/root-name 重刷订阅），消息行回归桌面同构零装饰；会话级
  // 身份 = .g2-chat-head 悬浮锚（sticky 顶锚大圆头像 + 名字下置，
  // mobileLayer.css 文件尾批次块）一处承载。teardown 的 .g2-msg-meta 摘除
  // 行随之退役 —— 本文件此后对消息行零写入。

  // ── 会话头头像空态回落 stand logo（批③）──
  const avatarSlot = document.getElementById('agent-avatar-btn');
  if (avatarSlot) {
    paintAgentSlotLogo(avatarSlot);
    avatarSlotMo = new MutationObserver(() => paintAgentSlotLogo(avatarSlot));
    avatarSlotMo.observe(avatarSlot, { childList: true });
  }

  // ── 悬浮锚 top 变量同步（Muse 悬浮身份头恢复批 · 作者令 2026-11-02 09:14）──
  // sticky 的锚定矩形 = scrollport 内缩 padding（实测：top:0 只能锚到 padding
  // 缘）—— main.js 顶让位观察器把 padding-top 写在 #chat 上（手机层常态 96px =
  // 渐隐带 88 + 基线 8），CSS 的 top:0 够不到窗口顶。本层同步
  // --muse-head-top = -(padding-top)，mobileLayer.css 中块 ②/②-b 以 var()
  // 消费（sticky 顶锚 + 组顶锚 64px）—— 锚定阈值恒等窗口顶。#chat 的 content
  // box 随 padding 写入收缩 ⇒ ResizeObserver 必有一拍重测（横幅/键盘/层翻转
  // 引起的 reserve 变化同链覆盖）；同步轻量幂等，零滚动热路径成本。
  // ⚠ 上一批（07:23 单行三段式顶栏）曾**整条摘除**本链（fixed 顶栏不需要锚定
  // 变量）；本批随顶栏回退一并恢复，逐字沿用 2026-11-01 13:33 原实现。
  const syncHeadTop = () => {
    const pad = parseFloat(getComputedStyle(chat).paddingTop) || 0;
    chat.style.setProperty('--muse-head-top', `${-pad}px`);
  };
  syncHeadTop();
  const headTopRo = new ResizeObserver(syncHeadTop);
  headTopRo.observe(chat);
  unsubs.push(() => headTopRo.disconnect());

  // ── 悬浮圆钮层（原「任务/搜索」双钮 → 本批收敛为**只右上方搜索钮**；48px v2
  //    液体玻璃正圆）────────────────────────────────────────────────────────
  fabLayer = document.createElement('div');
  fabLayer.id = 'mobile-fab-layer';
  // 零底带类 = design-system.css 单源（透传悬浮批 2026-11-03）：聊天根本来就是
  // 这套语义的基准件，随批与 .mobile-page-header / .mf-cv-toolbar / .mf-strip
  // 收编进同一族（一处定义处处引用）。
  fabLayer.className = 'ds-chrome-band';

  /* 左上「任务」圆钮（**恢复**：作者令 2026-11-05 09:28 —— 手机端待办入口回到
     悬浮圆钮，桌面端继续用胶囊）。
     落的几何：`.mh-chrome-btn[data-chrome='tasks'] { left: var(--mchrome-top) }`
     + `top: calc(var(--mchrome-top) + env(safe-area-inset-top))`（mobileLayer.css
     聊天根悬浮圆钮块）—— 与右上搜索钮同一行同一档 48px g2-fab 材质；刘海安全区
     由 env(safe-area-inset-top) 直接避让，零额外补偿。
     层级：本层 z30 高于顶缘渐隐带（25）、低于 push 页（156）与 dock（200），
     真身是「零底带 + 子件 absolute」的 .ds-chrome-band ⇒ 顶部内容滚动时从圆钮
     底下穿过（本层无背景、无拦截面，只有圆钮本体 pointer-events:auto）。
     点击 = pushPage('tasks')：任务页（mobile/taskPanel.js 惰性建
     #mobile-task-panel，面板体 = todoFace.mountTodoFaceInto）——与恢复前的原
     实现逐字一致，不另造抽屉。 */
  taskBtn = document.createElement('button');
  taskBtn.type = 'button';
  taskBtn.className = 'mh-chrome-btn g2-fab';
  taskBtn.dataset.chrome = 'tasks';
  taskBtn.title = t('taskpanel.title');
  taskBtn.setAttribute('aria-label', t('taskpanel.title'));
  taskBtn.innerHTML =
    '<i data-lucide="list-todo"></i><span class="mh-chrome-badge" hidden></span>';
  taskBtn.addEventListener('click', (e) => {
    // 桌面面 outside-close 监听 document 冒泡 —— 入口键停冒泡防误关（hub 行同款）。
    e.stopPropagation();
    touchLayer();
    logEvent('chrome-click', { face: 'tasks' });
    pushPage('tasks');
  });

  searchBtn = document.createElement('button');
  searchBtn.type = 'button';
  searchBtn.className = 'mh-chrome-btn g2-fab';
  searchBtn.dataset.chrome = 'search';
  searchBtn.title = t('search.placeholder') || '搜索聊天记录';
  searchBtn.setAttribute('aria-label', searchBtn.title);
  searchBtn.innerHTML = '<i data-lucide="search"></i>';
  searchBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    touchLayer();
    logEvent('chrome-click', { face: 'search' });
    pushPage('search');
  });

  // 双键同层（恢复：作者令 2026-11-05 09:28 —— 左任务 / 右搜索）。
  fabLayer.append(taskBtn, searchBtn);
  main.appendChild(fabLayer);
  if (typeof lucide !== 'undefined') createIconsIn(fabLayer);

  /* 徽标同步三腿（恢复：2026-11-05 09:28）：
     ① 首刷；② 委托链镜像 / 会话切换事件；③ todoStore 订阅（真源回执与写口
     乐观更新到达即跟新 —— 只接一条订阅，零 DOM/CSS/结构改动）。 */
  syncTaskBadge();
  const onMirror = () => syncTaskBadge();
  window.addEventListener('delegate-chain-mirror-updated', onMirror);
  window.addEventListener('nebflow-session-change', onMirror);
  unsubs.push(todoStore.subscribe(syncTaskBadge));
  const onLocale = () => {
    syncTaskBadge();
    if (searchBtn) searchBtn.title = t('search.placeholder') || '搜索聊天记录';
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => {
    window.removeEventListener('delegate-chain-mirror-updated', onMirror);
    window.removeEventListener('nebflow-session-change', onMirror);
    window.removeEventListener('locale-changed', onLocale);
  });

  return function teardownChatChrome() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    headMo?.disconnect();
    headMo = null;
    avatarSlotMo?.disconnect();
    avatarSlotMo = null;
    // 消息流身份头随 Muse 式悬浮头像批退役（挂出链已摘，DOM 无此节点）。
    document.querySelectorAll('#agent-avatar-btn .agent-avatar-stand-logo').forEach((n) => n.remove());
    // （身份头滚动渐隐解绑腿已随身份头常显批 2026-11-04 删除：无监听可注销，
    //   `--idhead-p` 也不再存在 —— 四会话身份头一律常显。）
    // 归位重绘（桌面腿 2026-11-01）：摘除走的是同名类（注入档 + paintSlot 自绘档
    // 一并中招）——归还桌面形态前必须补一次 paintSlot，否则空槽挂到下一个事件。
    try { paintSlot(); } catch { /* 绘制失败不阻断其余归还 */ }
    // 悬浮锚 top 变量随层拆除（headTopRo 经 unsubs 断开）。
    chat.style.removeProperty('--muse-head-top');
    taskBtn?.remove();
    searchBtn?.remove();
    taskBtn = null;
    searchBtn = null;
    fabLayer?.remove();
    fabLayer = null;
    // 三节点按原序归还 #header .header-center（#header 只是 display:none，DOM
    // 未动；center 原本仅承载这三节点，原序 append = 结构还原）。
    if (chatHead) {
      const center = header.querySelector('.header-center');
      if (center) {
        for (const node of [...chatHead.children]) center.appendChild(node);
      }
      chatHead.remove();
      chatHead = null;
      // 会话页头同构批：建/拆层广播（nebflow-chat-head-mounted/-unmounted）随
      // fixed 顶栏退役 —— 悬浮身份头不在内容流内，main.js 的顶让位已回到
      // bandBottom 单一真值，无观察器可通知（监听腿同批摘除）。
    }
  };
}
