// tokenComplete.js — 输入补全单一引擎（`@` 项目 / `#` 待办 共用一套）.
//
// 本批（待办结构化 + `#` 引用 + 拼音匹配批, 2026-11-05 作者令）把原
// mentionComplete.js 里的弹层机器整体上移到本文件，**一份实现、多个触发符**：
//   · 触发符、数据源、排序、行渲染、插入形态 = provider 的职责（registerProvider）
//   · 弹层 DOM、分词、键盘导航、插入改写、关闭时机、IME/失焦/外点 = 引擎的职责
//
// 复用面清单（与批报告 §2 逐条对应，禁止另写平行实现）：
//   ① 分词  —— parseToken（触发符无关，门禁差异走 spec 开关）
//   ② 弹层  —— 单例 #mention-dropdown（沿用既有 CSS `.mention-dropdown`，
//              候选行仍 `.mention-item` / `.mention-name` / `.mention-path`）
//   ③ 键盘  —— ArrowUp/Down 选择、Enter/Tab 确认、Esc 关闭（handleTokenKeydown）
//   ④ 异步  —— fetchSeq 竞态收敛、lastRenderKey 重渲去重、TTL 缓存归 provider
//   ⑤ 插入  —— pickToken：置换 token 区间 + 补尾随空格 + 光标落位 + 派发 input
//   ⑥ 排序  —— rankCandidates（字面档 > 拼音档 > 详情档，同级按最近活跃）
//
// 宿主纪律（沿用 mentionComplete.js 既有契约）：
//   · IME：本文件不读任何 composition 原始标志（scripts/check-ime-guard.mjs）——
//     判定一律走 imeGuard.isImeComposing；inout 事件层的组字期抑制同理。
//   · 渲染：动态文本只走 textContent / dom.js 的 h()，零裸 innerHTML。
//   · 样式：沿用 `.mention-dropdown` 玻璃配方，不新增设计令牌。

import { t, getLocale } from './i18n.js';
import { isImeComposing } from './imeGuard.js';
import { pinyinScore, hasHan } from './pinyin.js';

// ── provider 注册表 ──────────────────────────────────────────────────────
/**
 * 一个补全候选（provider 归一后的行形状；字段按 provider 自取）。
 * @typedef {object} CompleteItem
 * @property {string} name       候选名（= 插入文本的正文）
 * @property {string} [subtitle] 行内次级说明（项目 = 工作区路径；待办 = detail 摘要）
 * @property {string} [detail]   排序用的详情文本（待办 = detail）
 * @property {number} [createdAt] 同级排序用（最近创建优先）
 * @property {number} [activeAt]  同级排序用（最近活跃优先，缺省退回 createdAt）
 * @property {any} [ref]          provider 自用的原始行
 */

/**
 * provider 规格：一个触发符 + 一条数据面 + 一段排序。
 * @typedef {object} ProviderSpec
 * @property {string} id                      provider id（弹层区分/调试用）
 * @property {string} trigger                 单字符触发符（'@' / '#'）
 * @property {string} titleKey                分组标题 i18n key
 * @property {string} emptyKey                零命中 i18n key
 * @property {string} [ariaLabelKey]          无障碍标签 i18n key（缺省 = titleKey）
 * @property {boolean} [codeGate]             在代码围栏/行内代码内不触发
 * @property {boolean} [blockDigitStart]      触发符后紧跟数字不触发（井号+数字 = 任务号）
 * @property {boolean} [blockHexColor]        触发符后是 3/4/6/8 位十六进制不触发（CSS 色值）
 * @property {() => Promise<CompleteItem[]>} load 候选源（provider 自持缓存）
 * @property {(items: CompleteItem[], query: string) => CompleteItem[]} rank 排序
 * @property {(item: CompleteItem) => string} insertText 插入文本（不含尾随空格）
 * @property {(item: CompleteItem) => void} [onPick] 选中后的附加动作
 */

/** @type {ProviderSpec[]} */
const providers = [];

/**
 * 注册一个 provider。同一 trigger 只允许一个（后注册的覆盖先注册的并告警）：
 * 一个触发符只有一个语义，两个 provider 抢同一字符必然是缺陷。
 * @param {ProviderSpec} spec
 */
export function registerProvider(spec) {
  const i = providers.findIndex((p) => p.trigger === spec.trigger);
  if (i >= 0) {
    console.warn(`[tokenComplete] trigger "${spec.trigger}" re-registered (${providers[i].id} → ${spec.id})`);
    providers[i] = spec;
    return;
  }
  providers.push(spec);
}

/** 已注册的 provider（只读快照，供诊断/测试读数）。 */
export function listProviders() {
  return providers.map((p) => ({ id: p.id, trigger: p.trigger }));
}

// ── 分词 ────────────────────────────────────────────────────────────────

const WHITESPACE_RE = /\s/;
/** 3/4/6/8 位十六进制 —— CSS 色值形状（井号 + 三位/六位/八位十六进制）。 */
const HEX_COLOR_RE = /^[0-9a-f]{3}$|^[0-9a-f]{4}$|^[0-9a-f]{6}$|^[0-9a-f]{8}$/i;

/**
 * 光标处的补全 token，或 null。
 *
 * 分词规则（触发符无关，门禁差异由 spec 开关决定）：
 *   · token = 触发符到光标之间**没有空白**的一段；
 *   · 触发符前是 ASCII 字母/数字 ⇒ 不触发（`foo@` 邮件、`C#` 语言名），
 *     行首、空白、汉字、中文标点之前一律开 token（与 @ 既有翻转口径逐字一致）；
 *   · spec.codeGate ⇒ 代码围栏 / 行内代码里不触发；
 *   · spec.blockDigitStart ⇒ 触发符后紧跟数字不触发（任务号语义，留给它）；
 *   · spec.blockHexColor ⇒ 触发符后是 3/4/6/8 位十六进制不触发（色值语义）。
 *
 * @param {{value: string, selectionStart: number | null}} input
 * @param {ProviderSpec} spec
 * @returns {{start: number, query: string} | null}
 */
function parseToken(input, spec) {
  const caret = input.selectionStart ?? input.value.length;
  const before = input.value.slice(0, caret);
  const at = before.lastIndexOf(spec.trigger);
  if (at < 0) return null;
  // 起始门：触发符前是 ASCII 字母/数字 ⇒ 词中，不触发；其余（行首/空白/汉字/
  // 中文标点）一律开 token。
  const prev = at > 0 ? before[at - 1] : ' ';
  if (/[a-zA-Z0-9]/.test(prev)) return null;
  const query = before.slice(at + 1);
  // token 到第一个空白为止（手打空格即收起弹层）
  if (WHITESPACE_RE.test(query)) return null;
  if (spec.codeGate && isInsideCode(before)) return null;
  if (spec.blockDigitStart && /^\d/.test(query)) return null;
  if (spec.blockHexColor && HEX_COLOR_RE.test(query)) return null;
  return { start: at, query };
}

/**
 * 光标是否落在 Markdown 代码区（围栏或行内代码）里。
 *
 * 判据（刻意保守，宁可漏判成"不触发"也不误触发）：
 *   ① 光标之前出现奇数个围栏起始行（``` / ~~~）⇒ 围栏内；
 *   ② 光标所在行内反引号个数为奇数 ⇒ 行内代码内；
 *   ③ 光标所在行本身是围栏行 ⇒ 围栏内。
 * 未覆盖：缩进式代码块（4 空格）、转义反引号、不同长度围栏嵌套 —— 留待真实
 * 需求出现再加（报告如实申报）。
 *
 * @param {string} text 光标之前的全文
 * @returns {boolean}
 */
function isInsideCode(text) {
  let fence = 0;
  for (const line of text.split('\n')) {
    if (/^\s{0,3}(```|~~~)/.test(line)) fence++;
  }
  if (fence % 2 === 1) return true;
  const lineStart = text.lastIndexOf('\n') + 1;
  const line = text.slice(lineStart);
  if (/^\s{0,3}(```|~~~)/.test(line)) return true;
  let ticks = 0;
  for (const ch of line) if (ch === '`') ticks++;
  return ticks % 2 === 1;
}

/**
 * 候选排序单一实现（`@` / `#` 共用）。档位（高者先）：
 *   6 名称完全等于查询 · 5 名称前缀 · 4 名称中段 ·
 *   3 全拼命中 · 2 拼音首字母命中 · 1 detail 命中 · 0 不命中（剔除）
 * 同级按「最近活跃（activeAt ?? createdAt ?? 0）降序」，再按 locale 自然序兜底
 * ——排序稳定，不随刷新抖动。
 * **空查询 = 无排序信号**：按 locale 名称升序（`@` 项目列表的既有口径逐字保留，
 * 新增的 `#` 待办面同口径——一个可预测的字母序优于"看谁新"）。
 *
 * 不变式（机械断言钉住）：拼音命中（3/2）**永不**压过名称前缀（5）/中段（4）；
 * 字面命中**永不**被拼音档挤掉。
 *
 * @param {CompleteItem[]} items
 * @param {string} query
 * @returns {CompleteItem[]}
 */
export function rankCandidates(items, query) {
  const q = String(query == null ? '' : query).trim().toLowerCase();
  const locale = getLocale();
  if (!q) {
    return items.slice().sort((a, b) =>
      String(a.name).localeCompare(String(b.name), locale, { numeric: true }));
  }
  /** @type {Array<{item: CompleteItem, tier: number}>} */
  const scored = [];
  for (const item of items) {
    const name = String(item.name || '');
    const lname = name.toLowerCase();
    let tier = 0;
    if (lname === q) tier = 6;
    else if (lname.startsWith(q)) tier = 5;
    else if (lname.includes(q)) tier = 4;
    else {
      const py = hasHan(name) ? pinyinScore(name, q) : 0;
      if (py === 2) tier = 3;
      else if (py === 1) tier = 2;
      else if (String(item.detail || '').toLowerCase().includes(q)) tier = 1;
    }
    if (tier > 0) scored.push({ item, tier });
  }
  scored.sort((a, b) => {
    if (a.tier !== b.tier) return b.tier - a.tier;
    const at = a.item.activeAt ?? a.item.createdAt ?? 0;
    const bt = b.item.activeAt ?? b.item.createdAt ?? 0;
    if (at !== bt) return bt - at;
    return String(a.item.name).localeCompare(String(b.item.name), locale, { numeric: true });
  });
  return scored.map((s) => s.item);
}

// ── 弹层状态 ────────────────────────────────────────────────────────────
// 编辑框是单例（chatViews.primary 拥有 #input，initInput 只对它跑一次），
// 模块级状态足够；ctx.view 仍守着每条键路径，别的视图驱动不了本弹层。
/** @type {HTMLElement | null} */
let ddEl = null;
/** @type {{view: any, input: HTMLTextAreaElement, spec: ProviderSpec} | null} */
let ctx = null;
/** @type {CompleteItem[]} */
let items = [];
let selected = 0;
let fetchSeq = 0;
let lastRenderKey = '';

function ensureDropdownEl() {
  if (ddEl) return ddEl;
  ddEl = document.createElement('div');
  ddEl.id = 'mention-dropdown';
  ddEl.className = 'mention-dropdown';
  ddEl.setAttribute('role', 'listbox');
  // 覆盖层目标与 #slash-dropdown 同一层（#input-area 里 position:absolute；
  // 弹层自身 pointer-events:auto）。
  const area = document.getElementById('input-area') || document.body;
  area.appendChild(ddEl);
  return ddEl;
}

function syncActiveRow() {
  if (!ddEl) return;
  const rows = ddEl.querySelectorAll('.mention-item');
  rows.forEach((el, i) => {
    const active = i === selected;
    el.classList.toggle('active', active);
    el.setAttribute('aria-selected', String(active));
  });
  if (ctx && items.length > 0) {
    ctx.input.setAttribute('aria-activedescendant', 'mention-opt-' + selected);
  }
  // 高亮行保持在可视区（沿用 slash 下拉的可见区算法，不用 scrollIntoView：
  // 它会往 overflow:hidden 祖先写程序化滚动）。
  const active = /** @type {HTMLElement | undefined} */ (rows[selected]);
  if (!active) return;
  const top = active.offsetTop;
  const bottom = top + active.offsetHeight;
  if (top < ddEl.scrollTop) {
    ddEl.scrollTop = top;
  } else if (bottom > ddEl.scrollTop + ddEl.clientHeight) {
    ddEl.scrollTop = bottom - ddEl.clientHeight;
  }
}

/**
 * 单行候选 DOM：名称 + 可选副标题。所有动态文本走 textContent。
 * @param {CompleteItem} item
 * @param {number} index
 * @returns {HTMLElement}
 */
function buildItem(item, index) {
  const el = document.createElement('div');
  el.className = 'mention-item';
  el.id = 'mention-opt-' + index;
  el.setAttribute('role', 'option');
  el.setAttribute('aria-selected', 'false');
  const name = document.createElement('span');
  name.className = 'mention-name';
  name.textContent = item.name;
  el.appendChild(name);
  if (typeof item.subtitle === 'string' && item.subtitle) {
    const sub = document.createElement('span');
    sub.className = 'mention-path';
    sub.textContent = item.subtitle;
    el.appendChild(sub);
  }
  // mousedown（不是 click）：preventDefault 保住 composer 焦点，无 blur 往返，
  // 光标一直归我们，直到 pickToken 挪它。
  el.addEventListener('mousedown', (e) => {
    e.preventDefault();
    pickToken(index);
  });
  el.addEventListener('mouseenter', () => {
    if (selected !== index) {
      selected = index;
      syncActiveRow();
    }
  });
  return el;
}

/** 强行收起弹层并作废在飞的异步渲染。 */
export function closeTokenDropdown() {
  fetchSeq++;
  lastRenderKey = '';
  if (ctx && ctx.input) {
    ctx.input.removeAttribute('aria-expanded');
    ctx.input.removeAttribute('aria-controls');
    ctx.input.removeAttribute('aria-activedescendant');
  }
  ctx = null;
  items = [];
  selected = 0;
  if (ddEl) {
    ddEl.classList.remove('on');
    ddEl.textContent = '';
  }
}

/**
 * 渲染（或按去重键跳过重建）。
 * @param {any} view
 * @param {HTMLTextAreaElement} input
 * @param {{start: number, query: string}} token
 * @param {ProviderSpec} spec
 * @param {CompleteItem[]} source
 * @returns {void}
 */
function renderDropdown(view, input, token, spec, source) {
  items = spec.rank(source, token.query);
  // 去重：selectionchange 每次光标移动都会重解析，同一次输入的重复渲染必须
  // 是 no-op（重建行会清掉用户的高亮）。查询变了（新按键）照旧重渲并回到首行。
  const renderKey = spec.id + '\u0000' + token.start + '\u0000' + token.query + '\u0000' +
    (items.length === 0 ? '<empty>' : items.map((p) => p.name).join('\u0001'));
  if (ctx && ddEl && ddEl.classList.contains('on') && renderKey === lastRenderKey) return;
  lastRenderKey = renderKey;
  if (items.length === 0 && source.length === 0) {
    // 数据面还没到达（首拉未回）—— 保持安静，不对未知清单说「无匹配」。
    closeTokenDropdown();
    return;
  }
  const dd = ensureDropdownEl();
  ctx = { view, input, spec };
  selected = 0;
  dd.textContent = '';
  // 分组标题纯呈现：aria-hidden，不参与 option 序列（键盘导航零影响）。
  const title = document.createElement('div');
  title.className = 'dd-group-title';
  title.setAttribute('aria-hidden', 'true');
  title.textContent = t(spec.titleKey);
  dd.appendChild(title);
  if (items.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'mention-empty';
    empty.setAttribute('role', 'option');
    empty.setAttribute('aria-disabled', 'true');
    empty.textContent = t(spec.emptyKey);
    dd.appendChild(empty);
  } else {
    items.forEach((item, i) => { dd.appendChild(buildItem(item, i)); });
  }
  dd.classList.add('on');
  dd.setAttribute('aria-label', t(spec.ariaLabelKey || spec.titleKey));
  input.setAttribute('aria-expanded', 'true');
  input.setAttribute('aria-controls', 'mention-dropdown');
  syncActiveRow();
}

/**
 * 输入事件入口：重解析光标处 token（先取触发符再按 spec 门禁）并刷新弹层。
 * 竞态由 fetchSeq 收敛（只有最新一次事件能渲染，且 token 仍活着）。
 * @param {any} view
 * @returns {void}
 */
function updateTokenDropdown(view) {
  const input = view.dom.input;
  if (!input) return;
  const spec = activeSpec(input);
  if (!spec) {
    closeTokenDropdown();
    return;
  }
  const token = parseToken(input, spec);
  if (!token) {
    closeTokenDropdown();
    return;
  }
  const seq = ++fetchSeq;
  Promise.resolve(spec.load()).then((source) => {
    if (seq !== fetchSeq) return; // 被更新的输入/关闭取代
    const current = parseToken(input, spec); // await 期间文本可能已变
    if (!current) {
      closeTokenDropdown();
      return;
    }
    renderDropdown(view, input, current, spec, Array.isArray(source) ? source : []);
  }).catch(() => { closeTokenDropdown(); });
}

/**
 * 光标前最近的触发符所属 provider（无则 null）。
 * @param {{value: string, selectionStart: number | null}} input
 * @returns {ProviderSpec | null}
 */
function activeSpec(input) {
  const caret = input.selectionStart ?? input.value.length;
  const before = input.value.slice(0, caret);
  let best = -1;
  let spec = null;
  for (const p of providers) {
    const at = before.lastIndexOf(p.trigger);
    if (at > best) { best = at; spec = p; }
  }
  return spec;
}

/**
 * 把第 index 个候选插入 token 区间，光标落在尾随空格之后。
 * @param {number} index
 * @returns {void}
 */
function pickToken(index) {
  const item = items[index];
  if (!item || !ctx) return;
  const { input, spec } = ctx;
  const token = parseToken(input, spec);
  closeTokenDropdown();
  if (!token) {
    input.focus();
    return;
  }
  const caret = input.selectionStart ?? input.value.length;
  // 全名逐字插入（含空格 / 中文名），零引号零转义。
  const insert = spec.insertText(item) + ' ';
  input.value = input.value.slice(0, token.start) + insert + input.value.slice(caret);
  const pos = token.start + insert.length;
  input.focus();
  input.setSelectionRange(pos, pos);
  // 镜像 composer 自己的自动伸高，再重派 input（前置先例：projectTab.js:813）
  // 让 input.js 既有监听重新同步（只读引用门、slash 下拉、滚动高度）。
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 200) + 'px';
  input.dispatchEvent(new Event('input', { bubbles: true }));
  if (typeof spec.onPick === 'function') {
    try { spec.onPick(item); } catch (_) { /* provider 附加动作失败不影响插入 */ }
  }
}

/**
 * 弹层的 keydown 钩子。调用方（input.js 的 keydown 处理器）必须：
 *   ① 先过 imeGuard 短路（组字中的键根本到不了这里）；
 *   ② 在 slash 下拉 / 历史 / Enter 发送分支**之前**调它；
 *   ③ 返回 true 即 stop（键已被消费）。
 * 只在弹层打开时消费键：ArrowUp/Down 导航、Tab 与裸 Enter 插入、Esc 关闭。
 * 零命中时弹层只是提示：Enter（含 Shift）与 Tab 一律不放行消费（原样透传）。
 *
 * @param {KeyboardEvent} e
 * @param {any} view
 * @returns {boolean}
 */
export function handleTokenKeydown(e, view) {
  if (!ctx || ctx.view !== view || !ddEl || !ddEl.classList.contains('on')) return false;
  if (e.key === 'ArrowDown' && items.length > 0) {
    e.preventDefault();
    selected = (selected + 1) % items.length;
    syncActiveRow();
    return true;
  }
  if (e.key === 'ArrowUp' && items.length > 0) {
    e.preventDefault();
    selected = (selected - 1 + items.length) % items.length;
    syncActiveRow();
    return true;
  }
  if ((e.key === 'Enter' || e.key === 'Tab') && !e.shiftKey && items.length > 0) {
    e.preventDefault();
    pickToken(selected);
    return true;
  }
  if (e.key === 'Escape') {
    e.preventDefault();
    closeTokenDropdown();
    return true;
  }
  return false;
}

/**
 * 把补全引擎绑到一个 composer 视图（input / blur 监听）。initInput 调一次；
 * 按元素幂等（重建的 DOM 节点会正确重绑，与 bindImeGuard 的标记法一致）。
 * @param {any} view
 * @returns {void}
 */
export function bindTokenComplete(view) {
  const input = view.dom.input;
  if (!input || input.__tokenCompleteBound) return;
  input.__tokenCompleteBound = true;
  input.addEventListener('input', (e) => {
    // 组字中不触发（对齐 ime-guard：判定单点 isImeComposing，绝不读原始标志）。
    if (isImeComposing(e, input)) return;
    updateTokenDropdown(view);
  });
  input.addEventListener('blur', () => { closeTokenDropdown(); });
}

// 外点关闭：一次模块级 document 委托（与 slash 下拉同法），一个 composer、
// 一个弹层，不在每次 initInput 累加监听。
document.addEventListener('click', (e) => {
  if (!ctx || !ddEl) return;
  const target = e.target;
  if (target instanceof Element && (ctx.input.contains(target) || ddEl.contains(target))) return;
  closeTokenDropdown();
});

// 不派发 input 的光标移动（鼠标点、左右箭头、Home/End）也要重新判活：
// 弹层代表**光标处**的 token，光标离开 token 就收起。selectionchange 是
// document 级，所以先守住"拥有该弹层的视图"，再更新。
document.addEventListener('selectionchange', () => {
  if (!ctx) return;
  if (document.activeElement !== ctx.input) return;
  updateTokenDropdown(ctx.view);
});

// ── 测试缝（机器断言用）────────────────────────────────────────────────
/** 纯函数视图（scripts/check-todo-mention.mjs 的提取锚点）。 */
export const __test = { parseToken, isInsideCode, rankCandidates, WHITESPACE_RE, HEX_COLOR_RE };
