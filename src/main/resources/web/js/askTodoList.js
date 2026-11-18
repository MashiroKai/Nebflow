// askTodoList.js — 溢出转待办批（2026-11-18 作者令）：**待办面承载区**的
// 唯一渲染模板（两处宿主共用：手机全屏待办页 mobile/todoAppleFace.js 与
// 桌面待办面板 todoFace.js）。
//
// 职责边界（单点纪律）：
//   · 「哪些问题已转入待办」**不在这里判**——判据单点在 askPending.askPlacement
//     / transferredAsks（本模块只消费该读口，禁再判一遍）。
//   · 「同一份状态」也**不在这里存**——条目就是 askPending 的待答镜像条目
//     （引擎 hub 单点的前端投影）。作答走 askPending.submitAskAnswer（与卡内
//     作答同一帧构造器 + 同一卡片锁定单点）⇒ 两处作答效果相同、绝无不对称态。
//   · 本模块只做呈现：题目 + 选项 + 提交钮。
//
// 题型覆盖：单/多选 + 可自由输入（allowOther）。secret / dirPicker 这类**必须
// 在原卡片上操作**的题型不在待办面作答（fail-closed：只给「去原卡片作答」跳转，
// 不给提交钮）——宁可不作答，也不造一个写不回去的半面。
//
// 样式类族 `ask-todo-*`（css/chat.css 内一节）；刻意不落在通知类令牌
// （toast/banner/notice）语汇里——它是**待办面内的行内区**，不是第四种通知。

import { t } from './i18n.js';
import { askSourceLabel, transferredAsks, submitAskAnswer, onAskPlacementChange } from './askPending.js';

const SECTION_CLASS = 'ask-todo-section';

/** 选项标签（后端 payload 既有 {label} 也有裸字符串两形）。 */
function optionLabel(opt) {
  if (opt == null) return '';
  if (typeof opt === 'string') return opt;
  if (typeof opt.label === 'string') return opt.label;
  if (typeof opt.text === 'string') return opt.text;
  return '';
}

/** 需要回原卡片操作的题型（待办面 fail-closed）。 */
function needsOriginalCard(item) {
  const kind = String((item && (item.type || item.kind)) || '').toLowerCase();
  return kind === 'secret' || kind === 'dirpicker' || kind === 'dir-picker' || kind === 'path';
}

/**
 * 渲染（或刷新）一个宿主容器里的待办承载区。幂等：先摘本模块上次的区，再按当前
 * 判据重建。宿主自己清空容器也不会留孤儿（每次调用都重建）。
 * @param {HTMLElement} host
 * @returns {number} 本次渲染的卡片数
 */
export function renderAskTodoSection(host) {
  if (!host || !host.querySelector) return 0;
  const prev = host.querySelector(':scope > .' + SECTION_CLASS);
  if (prev) prev.remove();
  const asks = transferredAsks();
  if (!asks.length) return 0;

  const wrap = document.createElement('div');
  wrap.className = SECTION_CLASS;
  wrap.dataset.count = String(asks.length);

  const head = document.createElement('div');
  head.className = 'ask-todo-head';
  const title = document.createElement('span');
  title.className = 'ask-todo-head-title';
  title.textContent = t('askUser.todoSectionTitle', { n: String(asks.length) });
  const hint = document.createElement('span');
  hint.className = 'ask-todo-head-hint';
  hint.textContent = t('askUser.todoSectionHint');
  head.append(title, hint);
  wrap.appendChild(head);

  for (const entry of asks) wrap.appendChild(buildCard(entry));
  host.insertBefore(wrap, host.firstChild);
  return asks.length;
}

/** 共享单例监听：归属一变，把所有仍挂着的宿主区重渲染一遍。 */
const hosts = new Set();
let wired = false;
function wire() {
  if (wired) return;
  wired = true;
  onAskPlacementChange(() => {
    for (const h of [...hosts]) {
      if (!h.isConnected) { hosts.delete(h); continue; }
      renderAskTodoSection(h);
    }
  });
}

/**
 * 宿主挂载点（宿主每次渲染调用即可；重复调用无副作用）。
 * @param {HTMLElement} host
 */
export function mountAskTodoSection(host) {
  if (!host) return;
  wire();
  hosts.add(host);
  renderAskTodoSection(host);
}

/**
 * 一张待答卡（题目 + 选项 + 提交）。
 * @param {any} entry askPending 镜像条目（{requestId, sessionId, items, ...}）
 */
function buildCard(entry) {
  const card = document.createElement('div');
  card.className = 'ask-todo-card';
  // 🔴 属性/类名纪律：**绝不**用 `data-request-id` 或 `.option-box`——那两样是
  // 「对话分页内的活卡」的定位面（askPending.cardInWindow / jumpToAsk 都在查它），
  // 待办面的卡一旦带同款就会把自己算成「原卡片在场」，判据立刻自欺。
  card.dataset.askTodoId = entry.requestId;

  const src = document.createElement('div');
  src.className = 'ask-todo-src';
  src.textContent = askSourceLabel(entry) || t('askUser.pendingTitle');
  card.appendChild(src);

  const items = Array.isArray(entry.items) ? entry.items : [];
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

    const options = Array.isArray(item && item.options) ? item.options : [];
    const multi = !!(item && item.multiSelect);
    if (options.length) {
      const box = document.createElement('div');
      box.className = 'ask-todo-opts';
      options.forEach((opt) => {
        const label = optionLabel(opt);
        if (!label) return;
        const btn = document.createElement('button');
        btn.type = 'button';
        // 复用既有提问卡选项材质（chat.css `.option-btn` / `.option-btn.picked`）——
        // 不新造第二套选项视觉。
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
            box.querySelectorAll('.ask-todo-opt').forEach(b => b.classList.remove('picked'));
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
  const submit = document.createElement('button');
  submit.type = 'button';
  submit.className = 'ask-todo-submit';
  submit.textContent = t('askUser.todoSubmit');
  submit.disabled = true;
  row.appendChild(submit);
  card.appendChild(row);

  /** 提交闸：每题有选项选中或自由输入非空（secret/dirPicker 整卡不给提交）。 */
  function allAnswered() {
    if (manualOnly || !items.length) return false;
    return items.every((_, qi) => picked[qi].length > 0 || freeText[qi]);
  }
  function syncSubmit() { submit.disabled = !allAnswered(); }

  submit.addEventListener('click', () => {
    if (!allAnswered() || card.classList.contains('is-answered')) return;
    const answers = items.map((item, qi) => {
      const multi = !!(item && item.multiSelect);
      if (multi) return JSON.stringify(picked[qi]);
      return picked[qi][0] || freeText[qi] || '';
    });
    const res = submitAskAnswer({
      requestId: entry.requestId,
      sessionId: entry.sessionId,
      answers,
      answerText: answers.filter(Boolean).join(' / '),
    });
    if (!res.ok) {
      // 已答/在飞（先到者胜）：不再产生第二条回答，区随镜像摘除自然退场。
      if (window.__showToast && res.reason === 'no-transport') {
        window.__showToast(t('askUser.pendingSyncFailed'), 'error');
      }
      return;
    }
    card.classList.add('is-answered');
    submit.disabled = true;
  });

  return card;
}
