// attachStripFold.js — composer 附件条「+N」折叠腿（观察链单点，手机层消费）。
//
// 出处（uifix 批 2026-10-22，作者令：「设计一批附件的ui优化。让附件放在对话框上面，
// 不要遮挡文字。」）：#attachment-preview 两层同改 **in-flow 独立行**（css/input.css
// 基座，贴输入框顶缘）——chip 与文字输入区完全分离。溢出语义两层各取所需：
//   · **桌面层 = 换行多行**（基座 flex-wrap:wrap）：chip 恒可见恒可点，折叠腿对
//     换行条带**自禁**（syncAttachmentStrip 的 flexWrap 计算值闸）；
//   · **手机层 = 单行 + 「+N」折叠**（本腿；v3 样张已批形态，纵向空间稀缺）。
// 本模块 = 手机层 floatController 既有折叠腿的**原样上移**（逻辑逐字保留，实现出处
// 见 git 历史 mobile/floatController.js「附件条 v3」段）：
//   · 单行装不下的**尾部** chip 落 `.mh-att-collapsed`（display:none），行尾补
//     「+N」计数键（`.mh-att-more`）；点 +N 展开 = `.mh-att-expanded` 横向滚动
//     全员可见，再点收拢回折叠。
//   · chip 增删 / 行宽变化由 MutationObserver(childList+attributes+subtree) +
//     ResizeObserver 驱动；观察链桌面启动即挂（main.js）＝两层共用一条链，手机层
//     bootstrap 拆装零接管。
//
// 自激纪律（原样保留，实测教训）：本模块由观察器驱动，**禁止**「先重置再重折」的
// 两段式写法 —— 折叠平衡态下每次同步都会撤销再重做（remove k + add k = 2k+2 条
// 记录/次），微任务风暴直接冻死页面。实现 = 纯读算目标 → diff 应用，平衡态零写入。

/** @type {{ro: ResizeObserver|null, mo: MutationObserver|null, more: HTMLButtonElement|null}} */
let attRow = { ro: null, mo: null, more: null };

/** chip 实测宽缓存（折叠态 display:none 无盒 —— 用上次可见时的宽度参算）。 */
const attChipW = new WeakMap();

function syncAttachmentStrip() {
  const row = document.getElementById('attachment-preview');
  if (!row) return;
  // 层自适配闸（uifix 批 2026-10-22）：换行条带（桌面基座 flex-wrap:wrap，CSS
  // 同批）chip 自行换行、无「装不下」⇒ 折叠腿不介入（误介入会把换行尾件藏掉）；
  // 单行条带（手机层 nowrap 覆盖）才折叠。读计算值 = 层语义的真源，本模块零
  // 层名感知（mobile-layer 类不进本文件）。
  if (getComputedStyle(row).flexWrap === 'wrap') {
    if (attRow.more && attRow.more.isConnected) attRow.more.remove();
    return;
  }
  // checkJs 对位：row.children 是 Element 拙集 —— 附件 chip 实为 HTMLElement。
  const chips = /** @type {HTMLElement[]} */ ([...row.children].filter((el) => !el.classList.contains('mh-att-more')));
  let more = attRow.more;
  if (!more) {
    more = document.createElement('button');
    more.type = 'button';
    more.className = 'mh-att-more';
    more.addEventListener('click', () => {
      row.classList.toggle('mh-att-expanded');
      resyncAttachmentStrip();
    });
    attRow.more = more;
  }
  const expanded = row.classList.contains('mh-att-expanded');

  // 目标态计算（纯读；collapsed 芯片无盒 → 用宽度缓存参算）。
  const gap = parseFloat(getComputedStyle(row).columnGap) || 6;
  const widths = chips.map((c) => {
    if (!c.classList.contains('mh-att-collapsed') && c.offsetWidth > 0) attChipW.set(c, c.offsetWidth);
    return attChipW.get(c) || 0;
  });
  const total = widths.reduce((s, w, i) => s + w + (i ? gap : 0), 0);
  const avail = row.clientWidth;
  const moreW = (more.isConnected && more.offsetWidth) || 34; // CSS min-width 28 + padding 兜底
  const overflow = !expanded && total > avail + 1;
  // 折叠目标 = 保前缀（至少 1 枚），前缀宽 + more 槽 ≤ 行宽（+N 自身吃滚动差）。
  let keep = chips.length;
  if (overflow) {
    const budget = avail - moreW - gap;
    let acc = 0;
    keep = 0;
    for (let i = 0; i < chips.length; i++) {
      const next = acc + widths[i] + (i ? gap : 0);
      if (next > budget) break;
      acc = next;
      keep++;
    }
    if (keep < 1) keep = 1; // 至少保首枚可见（全收 = 无意义折叠）
  }

  // diff 应用（平衡态零写入）。
  for (let i = 0; i < chips.length; i++) {
    const want = i >= keep;
    const has = chips[i].classList.contains('mh-att-collapsed');
    if (want && !has) chips[i].classList.add('mh-att-collapsed');
    else if (!want && has) chips[i].classList.remove('mh-att-collapsed');
  }
  const hiddenCount = chips.length - keep;
  if (!chips.length) {
    // 空strip：展开态残留一并清（chip 全走后 +N 不留、expanded 不挂空行）。
    row.classList.remove('mh-att-expanded');
  }
  if (expanded) {
    // 展开态：+N 保留行尾作「收拢」键（点它 toggle 回折叠；文本沿用折叠计数）。
    if (more.parentElement !== row || row.lastElementChild !== more) row.appendChild(more);
    if (more.hidden) more.hidden = false;
  } else if (overflow && hiddenCount > 0) {
    if (more.parentElement !== row || row.lastElementChild !== more) row.appendChild(more);
    if (more.hidden) more.hidden = false;
    const moreText = `+${hiddenCount}`;
    if (more.textContent !== moreText) more.textContent = moreText;
  } else {
    if (more.isConnected) more.remove();
    if (!more.hidden) more.hidden = true;
  }
}

/** 展开态重算（展开后行宽变化 → 重新判定折叠）。 */
function resyncAttachmentStrip() { syncAttachmentStrip(); }

/**
 * 常开观察链（幂等）：桌面 main.js 启动时挂一次；手机层拆装**不**接管本链。
 * 再次调用 = no-op（同一行只允许一条观察链，重复 observe 是自激放大器）。
 * @returns {() => void} teardown（应用卸载面用；常规前端生命周期不会走到）
 */
export function watchAttachmentStrip() {
  if (attRow.mo) return () => {};
  const row = document.getElementById('attachment-preview');
  if (!row) return () => {};
  attRow.mo = new MutationObserver(syncAttachmentStrip);
  attRow.mo.observe(row, { childList: true, attributes: true, subtree: true });
  if (window.ResizeObserver) {
    attRow.ro = new ResizeObserver(syncAttachmentStrip);
    attRow.ro.observe(row);
  }
  syncAttachmentStrip();
  return unwatchAttachmentStrip;
}

/** 拆观察链 + 折叠态清零（测试/卸载面；手机层拆层**不**调用 —— 折叠态随行存续）。 */
export function unwatchAttachmentStrip() {
  if (attRow.ro) { attRow.ro.disconnect(); attRow.ro = null; }
  if (attRow.mo) { attRow.mo.disconnect(); attRow.mo = null; }
  if (attRow.more) { attRow.more.remove(); attRow.more = null; }
  const row = document.getElementById('attachment-preview');
  if (row) {
    row.classList.remove('mh-att-expanded');
    row.querySelectorAll('.mh-att-collapsed').forEach((el) => el.classList.remove('mh-att-collapsed'));
  }
}
