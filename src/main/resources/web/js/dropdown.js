// dropdown.js — 原生 <select> → 点击展开点选（ui-unify-panels F 批 2026-11-17）
// ═══════════════════════════════════════════════════════════════════════════
// 作者令：「下拉统一：ALL native <select> → 点击展开点选」。
//
// 形态（与 contextMenu.js 同族的「层」组件，但值语义归原生 select）：
//   · 每个单选 <select> 被 .nf-select 网格栈包裹：trigger 按钮在下、原生
//     select 蒙在上（opacity:0 + pointer-events:none —— 指针永远打不穿到
//     原生弹层，真人的点击/轻点只会打开本组件的选项单）；
//   · 原生 select **留在 DOM 里当唯一状态载体**：站点代码的 .value 读写、
//     change 监听、disabled、querySelector 定位（.cfg-model-id 等）全部照旧
//     —— 本组件只换「脸与手」，不换「账本」。Playwright 的 selectOption /
//     inputValue / toHaveValue 也因此零迁移（opacity:0 仍算 visible）；
//   · trigger 的脸 = 标准输入面本体（JS 挂 nf-field nf-field--select，造型
//     单源 css/inputSurface.css —— 含 caret、指针压线、键盘焦点环，本文件
//     与 css/dropdown.css 只写几何，零第二套造型）；
//   · 选项单 = .fm-context-menu 材质族 + .nf-select-menu 修饰（视口钳位、
//     当前项勾选、optgroup 分组、上下键巡走、Tab 层内循环、Esc 独占、
//     手机层 = 全屏动作表 + 左上返回，全部沿用 contextMenu/popSheet/focusTrap
//     的既有实现，不另起一套）。
//
// 收编方式（学 inputSurface.css 的元素级兜底精神）：startSelectEnhancer()
// 在 main.js 挂一个 MutationObserver —— 任何新进 DOM 的 <select> 出生即被
// 收编，新面板不需要谁记得调 enhanceSelects。测试/局部装配可手动调
// enhanceSelects(root)。显式跳过：multiple / size>1（真列表框）、当场不可见
//     （display:none / hidden —— 如手机层收窄的 #search-scope；可见性变化
//     由属性突变再触发，被隐藏的 select 永不包裹 ⇒ 既有 isHidden 断言不动）。
//
// 已知边界（登记而非装作没有）：站点代码**静默** set select.value（不发
// change）时 trigger 标签要到下次 focus/open 才刷新；需要立即刷新的场合调
// syncSelectFace(select)（providersUi 填 context 即用）。

import { pushLayer } from './focusTrap.js';
import { mobileSheetBack, isMobileLayer } from './mobile/popSheet.js';

const WRAP_CLASS = 'nf-select';
const TRIGGER_CLASS = 'nf-select-trigger';
const NATIVE_CLASS = 'nf-select-native';
const VAL_CLASS = 'nf-select-val';
const MENU_CLASS = 'nf-select-menu';
const OPT_CLASS = 'nf-select-opt';
const GROUP_CLASS = 'nf-select-group';
const FLAG = 'nfSelect'; // dataset 标记：已收编

/* ── 单例选项单 ────────────────────────────────────────────────────────── */
let menuEl = null;
/** @type {import('./focusTrap.js').Layer|null} */
let menuLayer = null;
let menuSelect = null;
let menuTrigger = null;
let suppressReopen = false;

/** 关掉打开中的选项单（幂等）。 */
export function closeSelectMenu() {
  const l = menuLayer;
  menuLayer = null;
  if (l) l.release(); // 先出栈（回焦 trigger）再摘 DOM
  if (menuEl) { menuEl.remove(); menuEl = null; }
  if (menuTrigger) menuTrigger.setAttribute('aria-expanded', 'false');
  menuSelect = null;
  const t = menuTrigger;
  menuTrigger = null;
  unbindDismiss();
  return t;
}

function onDocClickCapture(e) {
  if (!menuEl) return;
  const t = e.target;
  if (!(t instanceof Node)) return;
  if (menuEl.contains(t)) return; // 选项自身的 click 自己收
  if (menuTrigger && menuTrigger.contains(t)) {
    // 点在 trigger 上：本击交由 trigger 的 click 处理器收（toggle），
    // 不然 capture 先关、bubble 又开，等于永远关不掉。
    suppressReopen = true;
    return;
  }
  closeSelectMenu();
}

function onDocKeyCapture(e) {
  if (!menuEl) return;
  if (e.key === 'Escape') {
    e.stopPropagation(); // 菜单开着时这击 Esc 归菜单（contextMenu 同律）
    closeSelectMenu();
    return;
  }
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') return;
  const opts = [...menuEl.querySelectorAll(`.${OPT_CLASS}`)];
  if (!opts.length) return;
  e.preventDefault();
  const idx = opts.indexOf(document.activeElement);
  let next = 0;
  if (e.key === 'Home') next = 0;
  else if (e.key === 'End') next = opts.length - 1;
  else if (idx < 0) next = e.key === 'ArrowUp' ? opts.length - 1 : 0;
  else next = e.key === 'ArrowUp' ? (idx - 1 + opts.length) % opts.length : (idx + 1) % opts.length;
  opts[next].focus();
}

const onWinHide = () => closeSelectMenu();

function bindDismiss() {
  document.addEventListener('click', onDocClickCapture, true);
  document.addEventListener('keydown', onDocKeyCapture, true);
  window.addEventListener('resize', onWinHide);
  window.addEventListener('blur', onWinHide);
}
function unbindDismiss() {
  document.removeEventListener('click', onDocClickCapture, true);
  document.removeEventListener('keydown', onDocKeyCapture, true);
  window.removeEventListener('resize', onWinHide);
  window.removeEventListener('blur', onWinHide);
}

function optionGroups(select) {
  // 平铺为 [{label, options:[HTMLOptionElement]}]；optgroup 保留分组头。
  const groups = [];
  let plain = null;
  for (const node of select.childNodes) {
    if (node instanceof HTMLOptGroupElement) groups.push({ label: node.label || '', options: [...node.querySelectorAll('option')] });
    else if (node instanceof HTMLOptionElement) (plain ??= { label: null, options: [] }).options.push(node);
  }
  if (plain) groups.unshift(plain);
  return groups;
}

function openMenu(select, trigger) {
  closeSelectMenu();
  const groups = optionGroups(select);
  const opts = groups.flatMap((g) => g.options);
  if (!opts.length) return;
  menuEl = document.createElement('div');
  menuEl.className = `fm-context-menu ${MENU_CLASS}`;
  menuEl.setAttribute('role', 'listbox');
  menuSelect = select;
  menuTrigger = trigger;
  for (const g of groups) {
    if (g.label) {
      const head = document.createElement('div');
      head.className = GROUP_CLASS;
      head.textContent = g.label;
      menuEl.appendChild(head);
    }
    for (const opt of g.options) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = OPT_CLASS;
      btn.setAttribute('role', 'option');
      const text = opt.textContent || '\u00A0';
      btn.textContent = text;
      if (opt.value === select.value) {
        btn.classList.add('is-cur');
        btn.setAttribute('aria-selected', 'true');
      }
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        pickOption(select, trigger, opt.value);
      });
      menuEl.appendChild(btn);
    }
  }
  document.body.appendChild(menuEl);
  if (isMobileLayer()) mobileSheetBack(menuEl, closeSelectMenu);

  // 定位：桌面 = trigger 正下、左对齐、视口钳位（放不下翻上）；手机层由
  // mobileLayer.css 的 fm-context-menu 全屏表接管，坐标无意义。
  if (!isMobileLayer()) {
    const r = trigger.getBoundingClientRect();
    menuEl.style.minWidth = `${Math.round(r.width)}px`;
    menuEl.style.left = `${Math.round(r.left)}px`;
    menuEl.style.top = `${Math.round(r.bottom + 4)}px`;
    const m = menuEl.getBoundingClientRect();
    if (m.right > window.innerWidth - 8) menuEl.style.left = `${Math.max(8, window.innerWidth - 8 - m.width)}px`;
    if (m.bottom > window.innerHeight - 8) menuEl.style.top = `${Math.max(8, r.top - m.height - 4)}px`;
  }
  trigger.setAttribute('aria-expanded', 'true');
  bindDismiss();
  menuLayer = pushLayer(menuEl, {
    initial: menuEl.querySelector(`.${OPT_CLASS}.is-cur`) || menuEl.querySelector(`.${OPT_CLASS}`),
    contain: true,
    name: 'select-menu',
  });
}

function pickOption(select, trigger, value) {
  closeSelectMenu();
  if (select.value !== value) {
    select.value = value;
    // 站点监听的是 change（部分双读 input）——两者都补发，bubbles 到委托层。
    select.dispatchEvent(new Event('input', { bubbles: true }));
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }
  syncSelectFace(select);
  trigger.focus();
}

/* ── 单个 select 的收编与脸面同步 ─────────────────────────────────────── */

function currentLabel(select) {
  const opt = select.selectedOptions[0];
  return (opt && opt.textContent) || '';
}

/** 刷新一个已收编 select 的 trigger 脸（标签 / 禁用 / 可见性）。幂等。 */
export function syncSelectFace(select) {
  const trigger = select.previousElementSibling;
  if (!(trigger instanceof HTMLButtonElement) || !trigger.classList.contains(TRIGGER_CLASS)) return;
  const val = trigger.querySelector(`.${VAL_CLASS}`);
  if (val) val.textContent = currentLabel(select);
  const hiddenNow = select.hidden || getComputedStyle(select).display === 'none';
  trigger.hidden = hiddenNow;
  trigger.disabled = select.disabled;
  const al = select.getAttribute('aria-label');
  if (al) trigger.setAttribute('aria-label', al);
}

function enhance(select) {
  if (select.dataset[FLAG]) return;
  // 真列表框与当场不可见的不收编（后者由属性突变再触发）。
  if (select.multiple || (select.size && select.size > 1)) return;
  if (select.hidden || getComputedStyle(select).display === 'none') return;

  select.dataset[FLAG] = '1';
  const wrap = document.createElement('span');
  wrap.className = WRAP_CLASS;
  select.parentNode.insertBefore(wrap, select);

  const trigger = document.createElement('button');
  trigger.type = 'button';
  trigger.className = `${TRIGGER_CLASS} nf-field nf-field--select`;
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.setAttribute('aria-expanded', 'false');
  const val = document.createElement('span');
  val.className = VAL_CLASS;
  trigger.appendChild(val);
  trigger.addEventListener('click', (e) => {
    e.stopPropagation();
    if (suppressReopen) { suppressReopen = false; return; } // 点外闭合路径已收
    if (select.disabled) return;
    if (menuEl && menuSelect === select) closeSelectMenu();
    else openMenu(select, trigger);
  });
  // select 保持编程可焦（站点 el.focus() 语义不变），但退出 Tab 序——
  // 键盘位由 trigger 接（可见焦点 = 标准文件给 .nf-field 的 :focus-visible 环）。
  select.tabIndex = -1;
  select.classList.add(NATIVE_CLASS);

  wrap.appendChild(trigger);
  wrap.appendChild(select);
  syncSelectFace(select);

  // 选项表 / 禁用态变化 → 脸面跟随（静默 .value 赋值不发突变，见文件头注）。
  new MutationObserver(() => syncSelectFace(select)).observe(select, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'hidden', 'class', 'aria-label'],
  });
  select.addEventListener('change', () => syncSelectFace(select));
}

/** 收编 root 下所有可收编的 <select>（root 自身是 select 也收）。幂等。 @param {Document|Element} root */
export function enhanceSelects(root = document) {
  if (root instanceof HTMLSelectElement) enhance(root);
  else if (root instanceof Element) for (const s of root.querySelectorAll('select')) enhance(s);
  else if (root instanceof Document) for (const s of root.querySelectorAll('select')) enhance(s);
}

let enhancerStarted = false;
/**
 * 全站自动收编（main.js 调一次）：新增进 DOM 的 <select> 出生即收编；
 * 被隐藏的 select 可见性翻转时补收编。已收编者零成本早退（dataset 旗标）。
 */
export function startSelectEnhancer() {
  if (enhancerStarted) return;
  enhancerStarted = true;
  enhanceSelects(document);
  new MutationObserver((muts) => {
    for (const m of muts) {
      if (m.type === 'attributes') {
        if (m.target instanceof HTMLSelectElement) {
          if (m.target.dataset[FLAG]) syncSelectFace(m.target);
          else enhance(m.target); // 从隐藏翻可见
        }
        continue;
      }
      for (const n of m.addedNodes) {
        if (n instanceof HTMLSelectElement) enhance(n);
        else if (n instanceof Element && n.querySelector('select')) enhanceSelects(n);
      }
    }
  }).observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style', 'hidden', 'disabled'] });
}
