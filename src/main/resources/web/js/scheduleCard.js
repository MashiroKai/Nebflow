// scheduleCard.js — 消息流里的定时任务 / 待办卡片（<nf-sched-card>）
// （schedule-created-card 批 2026-11-05，作者令 09:03 逐字：「同时，你创建定时
// 任务或者待办的时候，应该有一个卡片显示你创建了一条定时任务/待办，就在消息里，
// 也是消息气泡的样式，然后允许我直接在上面修改或取消。」）
//
// ── 复用面清单（开工判断依据；不新建平行体系）───────────────────────────
//   ① 组件基座 = js/component.js 的 define()（light DOM、零 attachShadow、模板
//      只接 DOM 节点），构建器 = js/dom.js 的 h()/frag()（零裸 innerHTML）；
//   ② 消息内嵌卡片的落行形态 = **Pop 交付卡的两行制**：进程行（既有工具卡，
//      摘要面）在下、卡片行紧随（chat.js renderTool / persistence.js 两条历史
//      重放腿同一形状；行类名沿用 `row` + 本卡自有类名，不新开行体系）；
//   ③ 样式变量 = css/design-system.css 的 --ds-* 令牌 + css/cards.css 的卡片
//      家族几何语（圆角/内距档位），新文件 css/scheduleCard.css 零字面量；
//   ④ 写读路径 = js/scheduleApi.js（WS 帧 → 网关 ScheduledTaskStore，与 Schedule
//      工具同一份 store；零新增帧型、零新增 store）；
//   ⑤ 时间输入 = js/timeWheel.js 的 msToLocalInput（既有单点，不另写格式化）。
//
// ── 状态机（终态不可逆）─────────────────────────────────────────────────
//   active（运行中）/ paused（PAUSED，enabled=false）/ done（已完成）/
//   cancelled（已取消）/ gone（引擎侧已不在）
//   ⇒ done / cancelled / gone 是**终态**：卡片不再渲染任何可点的写操作入口。
//   ⇒ 写失败（未连接 / 错误帧 / 超时）：卡片进 error 态，**用户输入原样保留**，
//     绝不回滚成旧值、绝不渲染成「已保存」。
//
// ── 回读一致（验收第 4 条）──────────────────────────────────────────────
//   卡片显示的字段在回读到达后一律取自引擎 list 行（listTaskJson 单点），
//   不是取自本地 patch —— 本地只负责「正在提交」与「失败了」两个瞬时态。

import { define } from './component.js';
import { h, frag } from './dom.js';
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { msToLocalInput } from './timeWheel.js';
import { updateEntry, cancelEntry, readEntry, transport } from './scheduleApi.js';
// 行角色单点（todo-card-click-left 批，2026-11-18 作者令「待办卡片应该靠左 这是
// 你发出的」）：卡片落在消息流哪一侧**只由发出者决定**，由 js/bubbleSide.js 的
// rowRoleOf 把「谁发的」翻成既有角色类 `.row.user` / `.row.ai`（对齐规则仍单点在
// css/chat.css，本文件不写第二套几何）。
import { rowRoleOf } from './bubbleSide.js';
// `#` 待办引用单点（同一批作者令「点卡片 → 对话框有 # 的待办引用」）：引用形态、
// 数据面、插入目标全部复用既有 `#` 补全那条腿（js/todoComplete.js），本文件只做
// 「点哪儿算点了卡片」的判定。
import { todoRefIdentity, insertTodoReference } from './todoComplete.js';

/**
 * 组件内部结构类名（单点声明；门禁 scripts/check-component-contract.mjs R4 的
 * 解析面）。对外只有方法面 setModel / refresh —— 定义模块以外不许按这些类名取节点。
 * @type {string[]}
 */
export const INTERNAL_CLASSES = [
  'schedule-card', 'schedule-card-head', 'schedule-card-chip', 'schedule-card-name',
  'schedule-card-status', 'schedule-card-body', 'schedule-card-toggle',
  'schedule-card-meta', 'schedule-card-chip-item', 'schedule-card-id',
  'schedule-card-actions', 'schedule-card-btn', 'schedule-card-form',
  'schedule-card-field', 'schedule-card-input', 'schedule-card-note',
  'schedule-card-fail',
];

const REPEAT_VALUES = ['', 'hourly', 'daily', 'weekly'];
const CONTENT_CLAMP_CHARS = 120;
const CONTENT_HEAD_CHARS = 80;

// ── 纯函数（单测面；无 DOM 依赖）────────────────────────────────────────

/**
 * @param {any} raw
 * @returns {any|null}
 */
export function parseJsonSafe(raw) {
  if (raw === null || raw === undefined || raw === '') return null;
  if (typeof raw === 'object') return raw;
  try { return JSON.parse(String(raw)); } catch { return null; }
}

/**
 * 从 Schedule 工具**结果文本**里取 id 与 kind（create 的 id 只出现在结果文本里，
 * 输入面没有；edit 的 id 在输入面）。
 * 真句式（ScheduleTool.scala 单点核对）：
 *   task ：「Scheduled task <id> for <time> …」
 *   todo ：「Todo <id> registered (surfaces daily …)」
 *   edit ：「Updated task <id> (…)」/「Updated todo <id> (…)」
 * @param {string} text
 * @returns {{id: string, kind: 'task'|'todo'}|null}
 */
export function parseScheduleResult(text) {
  const s = typeof text === 'string' ? text : '';
  let m = s.match(/^\s*Scheduled task\s+([0-9a-z]{4,32})\b/i);
  if (m) return { id: m[1], kind: 'task' };
  m = s.match(/^\s*Todo\s+([0-9a-z]{4,32})\s+registered\b/i);
  if (m) return { id: m[1], kind: 'todo' };
  m = s.match(/^\s*Updated\s+(task|todo)\s+([0-9a-z]{4,32})\b/i);
  if (m) return { id: m[2], kind: m[1].toLowerCase() === 'todo' ? 'todo' : 'task' };
  return null;
}

/**
 * 工具调用 → 卡片模型。只认 create/edit 且成功（isError=false）的调用；
 * 拿不到 id 就不落卡（宁可零卡，不做一张查不到的假卡）。
 * @param {{label?: string, inputJson?: any, content?: string, isError?: boolean, sessionId?: string}} opts
 * @returns {any|null}
 */
export function scheduleCardModelFromTool({ label, inputJson, content, isError, sessionId }) {
  const name = String(label || '').split('(')[0].split('\n')[0].trim();
  if (name !== 'Schedule' || isError) return null;
  const input = parseJsonSafe(inputJson) || {};
  const action = typeof input.action === 'string' ? input.action : 'create';
  if (action !== 'create' && action !== 'edit') return null;
  /** @type {{id?: string, kind?: 'task'|'todo'}} */
  const fromResult = parseScheduleResult(content) || {};
  const id = action === 'edit'
    ? String(input.id || fromResult.id || '').trim() || null
    : (fromResult.id || null);
  if (!id) return null;
  const kind = (input.kind === 'todo' || fromResult.kind === 'todo') ? 'todo' : 'task';
  const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
  return {
    id,
    kind,
    // 发出者（todo-card-click-left 批 2026-11-18）：本 builder 的入口是 **Schedule
    // 工具调用**（agent 面）⇒ 恒 'agent'。行对齐由 emitter 经 bubbleSide.rowRoleOf
    // 落成 `.row.ai`（左），**不再**写死 `.row user`。作者本人发出的卡片形态若出现，
    // 由调用方给 `emitter:'user'`（落 `.row.user`，右）——两侧都要正确，不许一刀切。
    emitter: 'agent',
    sessionId: sessionId || null,
    name: typeof input.name === 'string' && input.name.trim() ? input.name.trim() : null,
    content: typeof input.content === 'string' && input.content.trim() ? input.content.trim() : null,
    project: typeof input.project === 'string' && input.project.trim() ? input.project.trim() : null,
    triggerAt: num(input.triggerAt),
    repeat: typeof input.repeat === 'string' && input.repeat.trim() ? input.repeat.trim() : null,
    createdAt: null,
    enabled: true,
    completed: false,
    status: 'active',
  };
}

/**
 * 本地时间显示（沿用 timeWheel 的 datetime-local 口径，展示加一个空格）。
 * @param {number|null|undefined} ms
 * @returns {string}
 */
export function formatWhen(ms) {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return '';
  return msToLocalInput(ms).replace('T', ' ');
}

/**
 * 重复档 → 文案键（'' = 不重复）。
 * @param {string|null|undefined} repeat
 * @returns {string}
 */
export function repeatLabel(repeat) {
  const v = typeof repeat === 'string' ? repeat.trim().toLowerCase() : '';
  if (!REPEAT_VALUES.includes(v) || v === '') return t('schedCard.repeatOnce');
  return t(`schedCard.repeat.${v}`);
}

/**
 * 由引擎行（listTaskJson 形状）反推卡片状态。
 * @param {any|null} row
 * @returns {'active'|'paused'|'done'|'gone'}
 */
export function statusOfRow(row) {
  if (!row) return 'gone';
  if (row.completed === true) return 'done';
  if (row.enabled === false) return 'paused';
  return 'active';
}

const TERMINAL = new Set(['done', 'cancelled', 'gone']);

/**
 * @param {string} status
 * @returns {boolean}
 */
export function isTerminalStatus(status) {
  return TERMINAL.has(status);
}

// ── 组件本体 ───────────────────────────────────────────────────────────

const INSTANCES = new WeakMap();

/**
 * 挂载（component.js 的 mount 腿）：把模型渲进 light DOM，并把方法面
 * （setModel / refresh）挂到宿主元素上。
 * @param {HTMLElement} host
 * @returns {void}
 */
function mountCard(host) {
  const st = {
    model: /** @type {any} */ (host).schedModel || null,
    mode: 'view',      // view | edit | confirm
    draft: null,
    error: null,       // 写失败（可见、保留输入）
    note: null,        // 回读失败等非写失败提示
    busy: false,
    expanded: false,
    settled: false,    // 回读是否已有终态结论
  };
  INSTANCES.set(host, st);
  const api = /** @type {any} */ (host);
  api.setModel = (model) => { st.model = model; render(host, st); };
  api.refresh = () => refreshFromEngine(host, st);
  render(host, st);
  refreshFromEngine(host, st);
}

/** 回读引擎侧权威行（失败 = 可见提示，不改数据、不当作成功）。 */
function refreshFromEngine(host, st) {
  const m = st.model;
  if (!m || !transport.ready()) { render(host, st); return Promise.resolve(); }
  return readEntry({ id: m.id, kind: m.kind, sessionId: m.sessionId })
    .then((row) => {
      if (!INSTANCES.has(host)) return;          // 已离场
      if (row) {
        st.model = { ...m, ...rowFromEngine(row, m) };
        st.note = null;
      } else if (!st.settled && m.status === 'active' && st.mode === 'view') {
        // 引擎侧查不到：若本次会话内没取消过（st.settled=false），如实标「已不在」。
        st.model = { ...m, status: 'gone' };
        st.note = null;
      }
      st.settled = true;
      render(host, st);
    })
    .catch(() => {
      if (!INSTANCES.has(host)) return;
      st.note = t('schedCard.readFailed');
      render(host, st);
    });
}

/**
 * 行 → 卡片字段（字段名逐字取 listTaskJson；引擎是唯一权威）。
 * @param {any} row
 * @param {any} prev
 * @returns {any}
 */
function rowFromEngine(row, prev) {
  const status = statusOfRow(row);
  return {
    kind: row.kind === 'todo' ? 'todo' : prev.kind,
    name: typeof row.name === 'string' && row.name ? row.name : prev.name,
    content: typeof row.content === 'string' ? row.content : prev.content,
    project: typeof row.project === 'string' && row.project ? row.project : null,
    triggerAt: typeof row.triggerAt === 'number' ? row.triggerAt : prev.triggerAt,
    repeat: typeof row.repeat === 'string' && row.repeat ? row.repeat : null,
    createdAt: typeof row.createdAt === 'number' ? row.createdAt : prev.createdAt,
    enabled: row.enabled !== false,
    completed: row.completed === true,
    status,
  };
}

/** 渲染入口（整卡重绘；卡体小，重绘比定点变更更不容易漏面）。 */
function render(host, st) {
  const m = st.model;
  while (host.firstChild) host.removeChild(host.firstChild);
  if (!m) return;
  const terminal = isTerminalStatus(m.status);
  const card = h('div', { class: 'bubble injected schedule-card', 'data-nf-sched-card': '1',
    'data-sched-id': m.id, 'data-sched-status': m.status },
    frag(
      headFace(m, st),
      contentFace(m, st, host),
      metaFace(m, st),
      st.error ? errFace(st) : null,
      st.note && !st.error ? noteFace(st) : null,
      st.mode === 'edit' ? formFace(m, st, host) : null,
      st.mode === 'confirm' ? confirmFace(host, st) : null,
      !terminal && st.mode === 'view' ? actionsFace(host, st) : null,
    ));
  // 点击腿（todo-card-click-left 批 2026-11-18）：**待办卡**整卡可点 ⇒ 当前对话框
  // 落一条 `#<待办名>·<id>` 引用（形态/数据面/插入全部复用 `#` 补全那条腿）。
  // 只挂待办卡：定时任务（kind='task'）不在 `#` 引用的词表里，给它塞一个 `#名字`
  // 只会被渲染侧当成普通井号文本 —— 宁可无动作，不造假引用。
  if (m.kind === 'todo') attachRefClick(card, m);
  host.appendChild(card);
  // 家族图标面（铃铛/时钟…）走全仓单点 createIconsIn（与既有触发卡同法）。
  try { createIconsIn(card); } catch { /* 图标失败不阻断卡片 */ }
}

/**
 * 卡内**既有交互件**白名单：命中其一即让路（各自原样行为，绝不被整卡点击吞掉）。
 * 覆盖：编辑/取消/保存/放弃/二次确认钮（button 全域）、快速决策/选项入口
 * （[role=button]）、勾选圈与标签这类带 tabindex 的可聚焦 chip（[tabindex]）、
 * 编辑表单里的 textarea/input/select 与其 label、以及折展钮。
 */
const CARD_INTERACTIVE_SEL = [
  'button', 'a[href]', 'input', 'textarea', 'select', 'label',
  '[role="button"]', '[tabindex]', '[data-sched-act]',
  '.schedule-card-form', '.schedule-card-actions',
].join(',');

/**
 * 挂「点非交互区 = 补 `#` 引用」的委托。
 *
 * 命中判定走 **click**（不是 pointerdown）：手机上的滑动/长按不产生 click
 * ⇒ 滑动滚动不会误触发；卡内按钮自己的 handler 先跑，且这里再兜一层
 * `closest(CARD_INTERACTIVE_SEL)` 显式让路（双保险 —— 不靠 stopPropagation 的
 * 自觉，也不靠事件顺序的巧合）。
 * @param {HTMLElement} card 卡体（.bubble.injected.schedule-card）
 * @param {any} m 卡片模型
 */
function attachRefClick(card, m) {
  card.classList.add('schedule-card-refable');
  card.setAttribute('title', t('schedCard.refHint'));
  card.addEventListener('click', (e) => {
    const target = e.target instanceof Element ? e.target : null;
    if (target && target.closest(CARD_INTERACTIVE_SEL)) return;
    const identity = todoRefIdentity(m.id) || refFromCardFace(m);
    if (!identity) return;
    insertTodoReference(identity);
  });
}

/** 兜底身份：待办库里查不到（已关闭/跨会话未在册）时用卡片自己显示的名字 + 卡 id。 */
function refFromCardFace(m) {
  const name = String(m.name || m.content || '').split('\n')[0].trim();
  return name ? { name, id: m.id } : null;
}

function headFace(m, st) {
  return h('div', { class: 'schedule-card-head' }, frag(
    h('i', { 'data-lucide': m.kind === 'todo' ? 'list-checks' : 'bell-ring' }),
    h('span', { class: 'schedule-card-chip' }, m.kind === 'todo' ? t('schedCard.kindTodo') : t('schedCard.kindTask')),
    m.name ? h('span', { class: 'schedule-card-name' }, m.name) : null,
    h('span', { class: `schedule-card-status is-${m.status}` }, statusText(m.status, st)),
  ));
}

/**
 * 状态文案（事实句，不写教学句 —— 文案自解释铁律 8）。
 * @param {string} status
 * @param {any} st
 * @returns {string}
 */
export function statusText(status, st) {
  if (st && st.busy && status === 'active') return t('schedCard.statusWriting');
  switch (status) {
    case 'paused': return t('schedCard.statusPaused');
    case 'done': return t('schedCard.statusDone');
    case 'cancelled': return t('schedCard.statusCancelled');
    case 'gone': return t('schedCard.statusGone');
    default: return t('schedCard.statusActive');
  }
}

function contentFace(m, st, host) {
  const text = m.content || '';
  if (!text) return null;
  const long = text.length > CONTENT_CLAMP_CHARS;
  const shown = !long || st.expanded ? text : text.slice(0, CONTENT_HEAD_CHARS) + '…';
  return frag(
    h('div', { class: 'schedule-card-body' + (st.expanded ? ' is-open' : '') }, shown),
    long
      ? h('button', {
        class: 'schedule-card-toggle', type: 'button',
        'aria-expanded': st.expanded ? 'true' : 'false',
        'on:click': () => { st.expanded = !st.expanded; render(host, st); },
      }, st.expanded ? t('schedCard.collapse') : t('schedCard.expand'))
      : null,
  );
}

function metaFace(m, st) {
  const chips = [];
  if (m.project) chips.push(h('span', { class: 'schedule-card-chip-item' }, m.project));
  if (m.kind === 'task') {
    if (m.triggerAt) {
      chips.push(h('span', { class: 'schedule-card-chip-item' },
        formatWhen(m.triggerAt) + ' · ' + repeatLabel(m.repeat)));
    } else {
      chips.push(h('span', { class: 'schedule-card-chip-item' }, repeatLabel(m.repeat)));
    }
  } else if (m.createdAt) {
    chips.push(h('span', { class: 'schedule-card-chip-item' }, t('schedCard.createdAt', { at: formatWhen(m.createdAt) })));
  }
  chips.push(h('span', { class: 'schedule-card-id' }, '#' + m.id));
  return h('div', { class: 'schedule-card-meta' }, frag(chips));
}

function errFace(st) {
  return h('div', { class: 'schedule-card-fail', role: 'alert' }, st.error);
}

function noteFace(st) {
  return h('div', { class: 'schedule-card-note' }, st.note);
}

function actionsFace(host, st) {
  return h('div', { class: 'schedule-card-actions' }, frag(
    h('button', {
      class: 'schedule-card-btn', type: 'button', 'data-sched-act': 'edit',
      'on:click': () => { st.mode = 'edit'; st.error = null; st.draft = draftOf(st.model); render(host, st); },
    }, t('schedCard.edit')),
    h('button', {
      class: 'schedule-card-btn is-danger', type: 'button', 'data-sched-act': 'cancel',
      'on:click': () => { st.mode = 'confirm'; st.error = null; render(host, st); },
    }, t('schedCard.cancel')),
  ));
}

/** 编辑草稿（初值 = 当前显示值，一律来自引擎回读）。 */
function draftOf(m) {
  return {
    content: m.content || '',
    project: m.project || '',
    triggerAt: m.triggerAt ? msToLocalInput(m.triggerAt) : '',
    repeat: m.repeat || '',
  };
}

function formFace(m, st, host) {
  const d = st.draft || draftOf(m);
  const field = (labelText, control) =>
    h('label', { class: 'schedule-card-field' }, frag(h('span', null, labelText), control));

  const contentInput = h('textarea', {
    class: 'schedule-card-input', rows: '3', 'data-sched-field': 'content',
    'on:input': (ev) => { d.content = /** @type {HTMLTextAreaElement} */ (ev.target).value; },
  });
  /** @type {HTMLTextAreaElement} */ (contentInput).value = d.content;
  // 用户一动输入，上一次的失败就不再是「这次的」失败：**只摘错误节点，不重绘**
  // （重绘会拆掉正在输入的控件 ⇒ 丢焦点、断中文输入法组字；见 ime-guard 纪律）。
  contentInput.addEventListener('input', () => {
    if (!st.error) return;
    st.error = null;
    const fail = host.querySelector('.schedule-card-fail');
    if (fail && fail.parentNode) fail.parentNode.removeChild(fail);
  });

  const projectInput = h('input', {
    class: 'schedule-card-input', type: 'text', 'data-sched-field': 'project',
    placeholder: t('schedCard.projectPlaceholder'),
    'on:input': (ev) => { d.project = /** @type {HTMLInputElement} */ (ev.target).value; },
  });
  /** @type {HTMLInputElement} */ (projectInput).value = d.project;

  const timeInput = m.kind === 'task'
    ? h('input', {
      class: 'schedule-card-input', type: 'datetime-local', step: '60', 'data-sched-field': 'triggerAt',
      'on:input': (ev) => { d.triggerAt = /** @type {HTMLInputElement} */ (ev.target).value; },
    })
    : null;
  if (timeInput) /** @type {HTMLInputElement} */ (timeInput).value = d.triggerAt;

  const repeatSelect = m.kind === 'task' ? h('select', {
    class: 'schedule-card-input', 'data-sched-field': 'repeat',
    'on:change': (ev) => { d.repeat = /** @type {HTMLSelectElement} */ (ev.target).value; },
  }, frag(REPEAT_VALUES.map((v) => h('option', { value: v },
    v === '' ? t('schedCard.repeatOnce') : t(`schedCard.repeat.${v}`))))) : null;
  if (repeatSelect) /** @type {HTMLSelectElement} */ (repeatSelect).value = d.repeat;

  return h('div', { class: 'schedule-card-form' }, frag(
    field(t('schedCard.fieldContent'), contentInput),
    field(t('schedCard.fieldProject'), projectInput),
    m.kind === 'task' ? field(t('schedCard.fieldTriggerAt'), timeInput) : null,
    m.kind === 'task' ? field(t('schedCard.fieldRepeat'), repeatSelect) : null,
    h('div', { class: 'schedule-card-actions' }, frag(
      h('button', {
        class: 'schedule-card-btn is-primary', type: 'button', 'data-sched-act': 'save',
        'on:click': () => { saveEdit(host, st); },
      }, st.busy ? t('schedCard.saving') : t('schedCard.save')),
      h('button', {
        class: 'schedule-card-btn', type: 'button', 'data-sched-act': 'discard',
        'on:click': () => { st.mode = 'view'; st.draft = null; st.error = null; render(host, st); },
      }, t('schedCard.discard')),
    )),
  ));
}

function confirmFace(host, st) {
  return h('div', { class: 'schedule-card-form' }, frag(
    h('div', { class: 'schedule-card-note' }, t('schedCard.confirmCancelAsk')),
    h('div', { class: 'schedule-card-actions' }, frag(
      h('button', {
        class: 'schedule-card-btn is-danger', type: 'button', 'data-sched-act': 'cancel-yes',
        'on:click': () => { doCancel(host, st); },
      }, st.busy ? t('schedCard.cancelling') : t('schedCard.confirmCancel')),
      h('button', {
        class: 'schedule-card-btn', type: 'button', 'data-sched-act': 'cancel-no',
        'on:click': () => { st.mode = 'view'; render(host, st); },
      }, t('schedCard.keep')),
    )),
  ));
}

/** 保存（原地）：帧 → 引擎回执 → 回读 → 从权威行重绘。失败：错误可见 + 输入保留。 */
function saveEdit(host, st) {
  const m = st.model;
  const d = st.draft || draftOf(m);
  if (st.busy) return;
  const patch = {};
  const content = String(d.content || '').trim();
  if (content && content !== (m.content || '')) patch.content = content;
  const project = String(d.project || '').trim();
  if (project !== (m.project || '')) patch.project = project;
  if (m.kind === 'task') {
    const ms = d.triggerAt ? new Date(d.triggerAt).getTime() : NaN;
    if (Number.isFinite(ms) && ms !== m.triggerAt) patch.triggerAt = ms;
    const repeat = d.repeat || '';
    if (repeat !== (m.repeat || '')) patch.repeat = repeat;
  }
  if (Object.keys(patch).length === 0) {
    st.error = t('schedCard.noChange');
    render(host, st);
    return;
  }
  st.busy = true;
  st.error = null;
  render(host, st);
  updateEntry({ id: m.id, sessionId: m.sessionId, patch })
    .then((ack) => {
      if (!INSTANCES.has(host)) return;
      st.model = { ...m, ...rowFromEngine(ack.row, m) };
      st.busy = false;
      st.mode = 'view';
      st.draft = null;
      st.error = null;
      st.settled = true;
      render(host, st);
      // 第二腿：回读一次，确认显示值与 list 面逐字段一致（验收第 4 条）。
      refreshFromEngine(host, st);
    })
    .catch((err) => {
      if (!INSTANCES.has(host)) return;
      st.busy = false;
      st.error = failText(err);
      // 失败**保留输入**：draft 不动、mode 停在 edit。
      st.draft = d;
      render(host, st);
    });
}

/** 原地取消：二次确认后发 delete 帧；回执到达才把状态改成「已取消」。 */
function doCancel(host, st) {
  const m = st.model;
  if (st.busy) return;
  st.busy = true;
  st.error = null;
  render(host, st);
  cancelEntry({ id: m.id, sessionId: m.sessionId })
    .then(() => {
      if (!INSTANCES.has(host)) return;
      st.busy = false;
      st.mode = 'view';
      st.settled = true;
      st.model = { ...m, status: 'cancelled' };
      render(host, st);
    })
    .catch((err) => {
      if (!INSTANCES.has(host)) return;
      st.busy = false;
      st.mode = 'view';
      st.error = failText(err);
      render(host, st);
    });
}

/**
 * 失败文案（三类失败各自可辨：未连接 / 引擎错误帧 / 超时；行已不在单列）。
 * @param {any} err
 * @returns {string}
 */
export function failText(err) {
  const code = err && err.code;
  if (code === 'offline') return t('schedCard.errOffline');
  if (code === 'timeout') return t('schedCard.errTimeout');
  if (code === 'gone') return t('schedCard.errGone');
  if (code === 'engine' && err && err.message) return t('schedCard.errEngine', { detail: err.message });
  return t('schedCard.errGeneric');
}

/** 渲染辅助（单点重绘入口：状态全在实例闭包里，重绘 = render(host, st)）。 */

// 模板：极简壳。真正的 DOM 由 mountCard 用 h()/frag() 落（模板里零 HTML 串）。
function template() {
  return h('div', { class: 'schedule-card-base' });
}

define({ tag: 'nf-sched-card', template, mount: mountCard });

/**
 * 建一行卡片（供 chat.js renderTool / persistence.js 两条重放腿共用）。
 *
 * 行侧（左/右）由 `model.emitter` 决定（缺省 = agent 侧，见 bubbleSide.rowRoleOf）：
 * 卡片的工具进程行本身就是 `.row.tool`（左），卡片行过去却写死 `.row user`（右）
 * ⇒ agent 亲手创建的待办卡看着像作者自己发的（todo-card-click-left 批作者报障）。
 * @param {any} model scheduleCardModelFromTool 的返回值
 * @returns {HTMLElement|null} 卡片行（`.row` + `.sched-row`）；模型不可用 ⇒ null
 */
export function buildScheduleCardRow(model) {
  if (!model || !model.id) return null;
  const el = document.createElement('nf-sched-card');
  /** @type {any} */ (el).schedModel = model;
  return h('div', {
    class: 'row ' + rowRoleOf(model.emitter) + ' sched-card-row',
    'data-sched-row-id': model.id,
    'data-sched-emitter': model.emitter === 'user' ? 'user' : 'agent',
  }, el);
}

/**
 * 工具调用 → 卡片行（chat.js / persistence.js 的唯一调用点）。
 * @param {{label?: string, inputJson?: any, content?: string, isError?: boolean, sessionId?: string}} opts
 * @returns {HTMLElement|null}
 */
export function scheduleCardRowForTool(opts) {
  return buildScheduleCardRow(scheduleCardModelFromTool(opts));
}

/** 供外部诊断（harness/测试）读一卡的当前状态快照。 */
export function cardStateOf(el) {
  const st = INSTANCES.get(el);
  if (!st) return null;
  return {
    mode: st.mode,
    busy: st.busy,
    error: st.error,
    note: st.note,
    model: st.model ? { ...st.model } : null,
  };
}
