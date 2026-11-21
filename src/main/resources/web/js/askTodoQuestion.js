// askTodoQuestion.js — 溢出转待办批（2026-11-18 作者令，第二轮）：**待办条目内
// 问题卡**的唯一渲染模板。
//
// 形态（作者新令逐字）：「转待办是转为具体的待办条目 而不是再待办上方显示问题」
// —— 承载面 = **待办条目自身**（列表里的一行），问题卡渲染在**该行**里（桌面 =
// 行体点击展开的行内详情区；手机 = 行内的决策详情区），不再是列表上方的专用区块。
//
// 职责边界（单点纪律）：
//   · 「哪些问题已在待办面上」**不在这里判**——判据单点在 askPending.askPlacement
//     （todoStore 只消费它做可见性过滤）。
//   · 「题目本体」**不在这里存**——题目就是 askPending 的待答镜像条目（引擎 hub 的
//     前端投影）；待办行只带身份（`ask-<requestId>`），载荷按 requestId 现读。
//   · 「作答/取消」**不在这里另造**——一律走 askPending 的两个单一收口
//     （submitAskAnswer / cancelAsk），与原卡片侧同一条腿 ⇒ 两侧同步收口，绝无
//     不对称态。
//   · 本模块只做**呈现**：题面 + 选项 + 自由输入 + 确认 / 取消。
//
// 与「最新待办协议」的关系（读过并点名）：规范 = vault
// `todo-taxonomy-and-naming__20261118_182654.md`（status: active，2026-11-18 作者
// 口述落成）。协议把待办条目的问题卡能力定义为 `decision` 契约
// （question / options[].title / multiSelect / allowOther / secret / expiresAt）。
// 本模板即该契约的**渲染与交互面**：选项 → `.option-btn`（提问卡同款材质）、
// 自由输入 → `.ask-todo-other`、确认 → `.ask-todo-submit`、取消 → `.ask-todo-cancel`。
// 选项/输入/确认/取消四件齐备；**不新造第二套问题卡**。
//
// 🔴 属性/类名纪律：**绝不**用 `data-request-id` 或 `.option-box`——那两样是「对话
// 分页内的活卡」的定位面（askPending.cardInWindow 查它），待办条目里的卡一旦带同款
// 就会被算成「原卡片在场」，判据当场自欺（源码闸 A5 钉住）。

import { t } from './i18n.js';
import { askSourceLabel, submitAskAnswer, cancelAsk, pendingAskEntry } from './askPending.js';

/** 待办条目是否是「问句条目」（引擎 Ask 镜像行，name = `ask-<requestId>`）。 */
export function isAskRow(row) {
  return !!(row && row.source === 'ask' && row.requestId);
}

/**
 * 待办行 → 问题卡（单点入口，两个宿主都调它）。
 * 题面**现读** askPending 的待答镜像（按 requestId），行里不存第二份。
 * @param {any} row todoStore 行
 * @returns {HTMLElement|null} 非问句行 / 镜像已无此题 ⇒ null
 */
export function buildAskCardForRow(row) {
  if (!isAskRow(row)) return null;
  return buildAskQuestionCard(pendingAskEntry(row.requestId));
}

/** 选项标签（后端 payload 既有 {label} 也有裸字符串两形）。 */
export function optionLabel(opt) {
  if (opt == null) return '';
  if (typeof opt === 'string') return opt;
  if (typeof opt.label === 'string') return opt.label;
  if (typeof opt.text === 'string') return opt.text;
  if (typeof opt.title === 'string') return opt.title;
  return '';
}

/** 决策选项文案（`decision.options[]` 真源形 = `{id,title,detail,media}`，见
 *  引擎 `TodoDecision`）。对象取 `title`（旧形 label/text 兼容经 [[optionLabel]]），
 *  裸字符串原样；解不出 ⇒ '' —— 调用方必须**跳过**空标签，绝不把对象交给
 *  `String()` 渲染成 `[object Object]`（作者 2026-11-21 23:48 报障）。 */
export function decisionOptionLabel(opt) {
  return optionLabel(opt);
}

/** 决策选项的长说明（仅对象形携带；无 ⇒ ''）。渲染侧有值才附显示一行。 */
export function decisionOptionDetail(opt) {
  if (opt == null || typeof opt !== 'object') return '';
  const d = typeof opt.detail === 'string' ? opt.detail : (typeof opt.description === 'string' ? opt.description : '');
  return d.trim();
}

/** 需要回原卡片操作的题型（secret / 目录选择）——条目侧 fail-closed：
 *  宁可不作答，也不造一个写不回去的半面。 */
function needsOriginalCard(item) {
  const kind = String((item && (item.type || item.kind)) || '').toLowerCase();
  return kind === 'secret' || kind === 'dirpicker' || kind === 'dir-picker' || kind === 'path'
    || !!(item && item.secret);
}

/** 每题选项载荷（多题面时每题各自一组）。 */
function optionsOf(item) {
  return Array.isArray(item && item.options) ? item.options : [];
}
function isMulti(item) { return !!(item && item.multiSelect); }

/**
 * 造一张**问题卡**（单点模板）。
 * @param {any} entry askPending 镜像条目（{requestId, sessionId, items, ...}）
 * @returns {HTMLElement|null} entry 无题目载荷 ⇒ null（调用方按普通行渲染）
 */
export function buildAskQuestionCard(entry) {
  if (!entry || !entry.requestId) return null;
  const items = Array.isArray(entry.items) ? entry.items : [];
  if (!items.length) return null;

  const card = document.createElement('div');
  card.className = 'ask-todo-card';
  card.dataset.askTodoId = entry.requestId;

  const src = document.createElement('div');
  src.className = 'ask-todo-src';
  src.textContent = askSourceLabel(entry) || t('askUser.pendingTitle');
  card.appendChild(src);

  /** @type {Array<Array<string>>} 每题已选标签 */
  const picked = items.map(() => []);
  /** @type {Array<string>} 每题自由输入 */
  const freeText = items.map(() => '');
  let manualOnly = false;

  items.forEach((item, qi) => {
    if (needsOriginalCard(item)) manualOnly = true;
    const q = document.createElement('div');
    q.className = 'ask-todo-q';
    q.textContent = String((item && item.question) || '');
    card.appendChild(q);

    const options = optionsOf(item);
    const multi = isMulti(item);
    if (options.length) {
      const box = document.createElement('div');
      box.className = 'ask-todo-opts';
      options.forEach((opt) => {
        const label = optionLabel(opt);
        if (!label) return;
        const btn = document.createElement('button');
        btn.type = 'button';
        // 复用既有提问卡选项材质（chat.css `.option-btn` / `.option-btn.picked`）
        btn.className = 'option-btn ask-todo-opt';
        btn.dataset.value = label;
        btn.textContent = label;
        btn.addEventListener('click', () => {
          if (card.classList.contains('is-answered')) return;
          if (multi) {
            const idx = picked[qi].indexOf(label);
            if (idx >= 0) { picked[qi].splice(idx, 1); btn.classList.remove('picked'); }
            else { picked[qi].push(label); btn.classList.add('picked'); }
          } else {
            picked[qi] = [label];
            box.querySelectorAll('.ask-todo-opt').forEach((b) => b.classList.remove('picked'));
            btn.classList.add('picked');
          }
          syncSubmit();
        });
        box.appendChild(btn);
      });
      card.appendChild(box);
    }

    const allowOther = (item && (item.allowOther || item.freeInput)) && !multi;
    if (allowOther) {
      const inp = document.createElement('input');
      inp.type = 'text';
      inp.className = 'ask-todo-other';
      inp.placeholder = t('chat.other');
      inp.addEventListener('input', () => { freeText[qi] = inp.value.trim(); syncSubmit(); });
      card.appendChild(inp);
    }
  });

  if (manualOnly) {
    const note = document.createElement('div');
    note.className = 'ask-todo-note';
    note.textContent = t('askUser.todoManual');
    card.appendChild(note);
  }

  const row = document.createElement('div');
  row.className = 'ask-todo-row';
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'ask-todo-cancel';
  cancel.textContent = t('askUser.todoCancel');
  const submit = document.createElement('button');
  submit.type = 'button';
  submit.className = 'ask-todo-submit';
  submit.textContent = t('askUser.todoSubmit');
  submit.disabled = true;
  row.append(cancel, submit);
  card.appendChild(row);

  /** 提交闸：每题有选项选中或自由输入非空（secret/dirPicker 整卡不给提交）。 */
  function allAnswered() {
    if (manualOnly || !items.length) return false;
    return items.every((_, qi) => picked[qi].length > 0 || freeText[qi]);
  }
  function syncSubmit() { submit.disabled = !allAnswered(); }
  function lock() { card.classList.add('is-answered'); submit.disabled = true; cancel.disabled = true; }

  submit.addEventListener('click', () => {
    if (!allAnswered() || card.classList.contains('is-answered')) return;
    const answers = items.map((item, qi) => {
      if (isMulti(item)) return JSON.stringify(picked[qi]);
      return picked[qi][0] || freeText[qi] || '';
    });
    const res = submitAskAnswer({
      requestId: entry.requestId,
      sessionId: entry.sessionId,
      answers,
      answerText: answers.filter(Boolean).join(' / '),
    });
    if (!res.ok) {
      if (window.__showToast && res.reason === 'no-transport') {
        window.__showToast(t('askUser.pendingSyncFailed'), 'error');
      }
      return;
    }
    lock();
  });

  // 取消（作者令「问题卡能力：选项 / 输入 / 确认 / 取消」——取消与确认同一收口单点）。
  cancel.addEventListener('click', () => {
    if (card.classList.contains('is-answered')) return;
    const res = cancelAsk({ requestId: entry.requestId, sessionId: entry.sessionId });
    if (!res.ok) {
      if (window.__showToast && res.reason === 'no-transport') {
        window.__showToast(t('askUser.pendingSyncFailed'), 'error');
      }
      return;
    }
    lock();
  });

  if (manualOnly) { submit.disabled = true; }
  return card;
}
