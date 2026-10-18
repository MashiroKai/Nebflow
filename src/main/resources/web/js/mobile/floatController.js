// mobile/floatController.js — 规格⑤：会话上层的居中悬浮按钮 + 居中浮层面板。
//
// 作者规格（原文语义）：
//   · 会话窗口两侧展开按钮删除（#sidebar-toggle / #canvas-toggle-btn —— 画板
//     已有活动栏独立按钮，侧栏入口手机层不存在；两者由 mobileLayer.css 隐藏）；
//   · 创建定时任务/心跳/查看记忆等按钮 → 居中浮于会话上层；
//   · 后台任务/智能体面板同样居中浮层；浮层移动端适配。
//
// r2（规格③ header 治理「杂项收纳 · 去向=浮层」）：#search-btn（搜索聊天记录）
// 自 header 右簇归入本浮条。
//
// r4（作者轮 4 令①「底部只留输入卡」）：r2 的「去向=底部悬浮条」作废 ——
// #mobile-float-actions 不再 fixed 悬浮于输入卡上方（r3 前置清点实锤的结构因：
// body 级 fixed 条 bottom:calc(--mobile-float-bottom + safe-area) 把搜索/定时/
// 心跳/记忆留在输入卡上沿），改 **收编进 header 右簇**（.header-right 末尾）：
// 搜索/历史/图标条上移归位进顶部一体化顶栏，输入卡上方零 header 内容。
// 收编后这些成员重新参与桌面 header 碰撞链（main.js initHeaderResizeObserver
// 按 id 测量整条 header）——r2 的 `.nb-header-hide` 顶回规则**保留**（成员
// 不参与窄宽裁剪，链对其隐藏被 CSS !important 抵消；碰撞体量仍被链计量，
// 中心带重叠时链先藏低优先级成员 bypass/voice，语义与桌面一致）。
//
// 实现：把 header 右簇的 search-btn（搜索）/ reminder-btn（定时任务）/
// daemon-btn（心跳）/ memory-btn（记忆）与后台任务/智能体两只计数胶囊
// （bg-indicator / bgagent-indicator）**整体搬移**进 #mobile-float-actions
// （现挂 .header-right 末尾 = 顶栏内的一簇）。DOM 搬移不丢事件监听（监听随
// 节点走），共享行为零复制；拆层时按记录的锚点原样归还 header。
//
// r4 收敛（作者 2026-09-07 现行裁定为纲：会话名永不截断/隐藏/压图标，顶栏
// 压力由图标让位吸收）：盾（#bypass-dropdown，r2 保留名单成员＝全链最低位）
// 在 375 档与 28px 簇钮组合下连 boot 空名态都放不下（r4 前置探针实锤）——
// 簇钮压至 24px（icon 不变 16px）后，375/390 空名态盾均站得住（free 27/42）；
// 长会话名时仍由桌面碰撞链按 09-07 裁定让盾收敛隐藏（名字优先，既有语义）。
//
// 与桌面 header 自适应隐藏链（main.js initHeaderResizeObserver）的正交处理：
// 被搬移节点仍会被该链按 id 命中并可能加 .nb-header-hide —— 手机层 CSS 以
// `#mobile-float-actions .nb-header-hide { display:flex !important }` 顶回
// （悬浮条内按钮不参与 header 宽度竞争）；内容性 hidden（如 memory-btn 未有
// 会话时的 hidden 属性）语义原样保留，不受影响。
//
// 居中浮层面板（reminder / daemon / bg / bgagent / memory / daemon-config）
// 的几何改写全部在 mobileLayer.css（body.mobile-layer 作用域， specificity
// 高于桌面 id 规则），本文件只管 DOM 搬移与悬浮条定位变量。

/** 收编簇成员（左→右）：搜索、后台任务胶囊、智能体胶囊、定时任务、心跳、记忆。 */
const FLOAT_IDS = ['search-btn', 'bg-indicator', 'bgagent-indicator', 'reminder-btn', 'daemon-btn', 'memory-btn'];

/** @type {{id:string, parent:Node, next:Node|null}[]} 归还锚点。 */
let anchors = [];
/** @type {ResizeObserver|null} */
let inputRo = null;

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || localStorage.getItem('locale') || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

function buildPill() {
  const pill = document.createElement('div');
  pill.id = 'mobile-float-actions';
  pill.setAttribute('role', 'toolbar');
  pill.setAttribute('aria-label', tt('会话工具', 'Session tools'));
  // r4：收编进 header 右簇（顶部一体化顶栏内），不再 body 级悬浮。
  const host = document.querySelector('#header .header-right') || document.body;
  host.appendChild(pill);
  return pill;
}

/** 输入区高度变化 → 更新浮层面板底距变量（面板仍锚输入卡上方；pill 已入顶栏）。 */
function watchInputArea() {
  const input = document.getElementById('input-area');
  const update = () => {
    if (!input) return;
    const h = input.getBoundingClientRect().height;
    document.documentElement.style.setProperty('--mobile-float-bottom', `${Math.round(h) + 14}px`);
  };
  update();
  if (input && window.ResizeObserver) {
    inputRo = new ResizeObserver(update);
    inputRo.observe(input);
  }
}

export function activateFloats() {
  const pill = buildPill();
  for (const id of FLOAT_IDS) {
    const el = document.getElementById(id);
    if (!el) continue;
    anchors.push({ id, parent: el.parentNode, next: el.nextSibling });
    pill.appendChild(el); // appendChild = move（监听随节点走）
  }
  watchInputArea();
  syncPillVisibility();

  // 内容性 hidden 变化（memory-btn 启用、后台任务计数清零）驱动悬浮条
  // 整体显隐（全员不可见时不留空玻璃条）。
  const mo = new MutationObserver(syncPillVisibility);
  for (const id of FLOAT_IDS) {
    const el = document.getElementById(id);
    if (el) mo.observe(el, { attributes: true, attributeFilter: ['hidden', 'class'] });
  }

  return function teardownFloats() {
    mo.disconnect();
    if (inputRo) { inputRo.disconnect(); inputRo = null; }
    // 按锚点原样归还 header（相对次序保持搬移前状态）。
    for (const a of anchors) {
      try {
        const el = document.getElementById(a.id);
        if (el && a.parent && a.parent.isConnected) a.parent.insertBefore(el, a.next);
      } catch { /* 节点树已变：放弃归还，避免二次破坏 */ }
    }
    anchors = [];
    pill.remove();
    document.documentElement.style.removeProperty('--mobile-float-bottom');
  };
}

/** 悬浮条内任一成员可见 ⇔ 悬浮条可见（全部 hidden 时整条隐藏）。 */
function syncPillVisibility() {
  const pill = document.getElementById('mobile-float-actions');
  if (!pill) return;
  const anyVisible = [...pill.children].some((el) => !(/** @type {HTMLElement} */ (el)).hidden && !el.classList.contains('hidden'));
  pill.classList.toggle('all-hidden', !anyVisible);
}
