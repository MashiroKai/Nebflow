// todoText.js — 待办「两段化」投影单点（待办结构化批, 2026-11-05 作者令
// 「待办要求用 name 和 detail 这样的架构」）.
//
// 只做一件事：把**旧形态**（只有一段文字的 content）无损投影成 {name, detail}
// 两段。新形态的行在后端就带 `detail` 键，前端直接用，不经过本文件。
//
// ── 为什么是"读时投影"而不是"迁移脚本" ────────────────────────────────────
// 存量待办文件**一个字节都不改**：每次读行时按同一确定性规则算出两段。
//   · 内容零丢失是结构性的（没有写路径，就没有丢的机会）；
//   · 可逆 —— 投影规则变了，重读即得新结果，旧数据原样还在；
//   · 多端一致 —— 同一 content 在任何设备/会话上算出同一 {name, detail}。
// 代价：投影不是"一次性的"，每次读都跑（纯字符串操作，行数量级 O(几十)，
// 可忽略）；且投影结果**不回写**，所以后端 Schedule list 仍按 content 展示
// （agent 面口径不变，如实申报）。
//
// ── 切分规则（确定性，机械断言钉住零丢失）─────────────────────────────────
//   ① 前 NAME_MAX 字内第一个句末标点（。！？；!?;\n）⇒ 连标点归 name，其余归
//      detail —— 「首句作标题」；
//   ② 没有句末标点且总长 ≤ NAME_MAX ⇒ 整段作 name，detail 空；
//   ③ 超长且无标点 ⇒ 在前 NAME_MAX 窗口内**优先切在空白**，CJK 无空白则硬切；
//   ④ 任何分支只丢「首尾空白」，不丢任何内容字符：
//      stripWs(name + detail) === stripWs(原文)（断言见 scripts/check-todo-mention.mjs）。

/** 标题窗口长度（字）。行宽一行可读、又被 CSS 截断兜底。 */
export const TODO_NAME_MAX = 24;

/** 句末标点（含换行）—— 标题在哪断。 */
const SENTENCE_END_RE = /[。！？；!?;\n]/;

/**
 * 一段文字 → {name, detail}（见文件头规则 ①-④）。
 * @param {string} text 原文（可为空/undefined）
 * @returns {{name: string, detail: string}}
 */
export function splitTodoText(text) {
  const raw = String(text == null ? '' : text);
  const t = raw.trim();
  if (!t) return { name: '', detail: '' };

  // ① 首句标点（窗口内）
  const window = t.slice(0, TODO_NAME_MAX);
  const m = window.match(SENTENCE_END_RE);
  if (m && typeof m.index === 'number' && m.index >= 0) {
    const cut = m.index + 1;
    // 句末是换行时，换行本身算空白（不留在标题里）
    const name = t.slice(0, cut).trim();
    const detail = t.slice(cut).trim();
    if (name) return { name, detail };
  }

  // ② 短文本整段作标题
  if (t.length <= TODO_NAME_MAX) return { name: t, detail: '' };

  // ③ 超长无标点：窗口内最后一个空白处切，没有则硬切
  const head = t.slice(0, TODO_NAME_MAX);
  let cut = head.lastIndexOf(' ');
  if (cut <= 0) cut = TODO_NAME_MAX;
  const name = t.slice(0, cut).trim();
  const detail = t.slice(cut).trim();
  return { name: name || t.slice(0, TODO_NAME_MAX), detail };
}

/**
 * 行的两段投影（唯一入口；store 与渲染层都只认它）。
 *
 * 三个来源形状，一支一支判（判据是**有没有 content 键**，不是猜）：
 *   ① 后端新行：有 content + 有 detail 键 ⇒ name/detail 直接用（name 空则退回首句）；
 *   ② 后端旧行：有 content、没有 detail 键 ⇒ splitTodoText(content) 无损投影
 *      （此时 `name` 是 upsert 键、不是标题，**不读**）；
 *   ③ 本地行 / 夹具行：没有 content 键 ⇒ 已经是两段形状，`title`/`notes` 是同义
 *      别名（件6 的两段叫 title/notes，本批正名 name/detail）。
 *
 * @param {{name?: any, detail?: any, content?: any, title?: any, notes?: any}} row
 * @returns {{name: string, detail: string, legacy: boolean}}
 */
export function projectTodoText(row) {
  const str = (v) => (typeof v === 'string' ? v : null);
  const content = str(row?.content);
  if (content !== null) {
    const name = (str(row?.name) || '').trim();
    const detail = str(row?.detail);
    if (detail !== null) {
      const split = splitTodoText(content);
      return { name: name || split.name, detail: detail.trim(), legacy: false };
    }
    const split = splitTodoText(content);
    return { name: split.name, detail: split.detail, legacy: true };
  }
  const name = ((str(row?.name) ?? str(row?.title)) || '').trim();
  const detail = ((str(row?.detail) ?? str(row?.notes)) || '').trim();
  return { name, detail, legacy: false };
}
