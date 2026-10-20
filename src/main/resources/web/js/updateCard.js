// updateCard.js — 互联页 · 本机统一更新卡（统一更新系统 M3 ④，DESIGN §8 健康可见）。
//
// 落位 = DESIGN 开放问题 #11 推荐默认：设备/互联页（与对端设备行同屏），**不复活**
// auth-trim 批（2026-10-20 作者令）退役的设置页更新行。
//
// 数据源（全部事件馈送，**零轮询零定时器**，与引擎侧零新增定时器纪律对齐）：
//   · serverConfig.update — 连接快照（自报 version/build/channel + 最近挂账与决策）
//   · updateState         — 收敛组件广播（见账/决策变更即推，引擎侧单点）
//   · updateProgress      — 既有编排器七相位统一进度帧（帧形不变，本卡只读）
//   · updateResult        — 受理拒绝/已在途/忙 的即时应答
// 面板纯订阅：帧到即重绘已挂载的卡；未挂载（面板未开/未登录）只更状态，零 DOM 操作。

import { t } from './i18n.js';
import { escapeHtml } from './utils.js';
import { onMessage } from './ws.js';

// 归一快照：{ version, build, channel, target, decision }（target/decision 可为 null）。
let snapshot = null;
// 最近一条编排器进度帧（七相位统一帧，引擎侧单点广播）。
let progress = null;
// 最近一条受理应答帧（updateResult：refused / busy / already-in-flight）。
let result = null;

function normUpdate(u) {
  if (!u || typeof u !== 'object') return null;
  return {
    version: u.currentVersion || u.version || null,
    build: u.build || null,
    channel: u.channel || null,
    target: u.target || null,
    decision: u.decision || null,
  };
}

onMessage('serverConfig', (msg) => {
  if (!msg || typeof msg !== 'object') return;
  snapshot = normUpdate(msg.update) || { version: msg.version || null, target: null, decision: null };
  paint();
});

onMessage('updateState', (msg) => {
  const u = normUpdate(msg);
  if (!u) return;
  snapshot = { ...(snapshot || {}), ...u };
  paint();
});

onMessage('updateProgress', (msg) => {
  if (!msg || msg.type !== 'updateProgress') return;
  progress = msg;
  paint();
});

onMessage('updateResult', (msg) => {
  if (!msg || msg.type !== 'updateResult') return;
  result = msg;
  paint();
});

// 相位 wire → 文案键（🔴 设置页 update.phase.* 键已随 auth-trim 退役，本卡用
// 自己的 neblink.updateCard.* 命名空间，不复活旧键）。
const PHASE_KEYS = ['checking', 'preparing', 'freezing', 'updating', 'restarting', 'recovering'];

function phaseText(wire) {
  if (!wire) return '';
  const key = `neblink.updateCard.phase.${wire}`;
  const text = t(key);
  // i18n 缺键回退 = 原样键名（调用方约定：t 缺键返回键名本身）
  return text === key ? wire : text;
}

function refusedReasonText(reason, detail) {
  const r = String(reason || '');
  const d = String(detail || '');
  if (r.includes('channel') || d.includes('channel mismatch')) return t('neblink.updateCard.refusedChannel');
  if (r.includes('orchestrator') || d.includes('orchestrator')) return t('neblink.updateCard.refusedUnavailable');
  return d || r || t('neblink.updateCard.refusedGeneric');
}

/** 状态行语义（优先级：进行中 > 中止 > 受理拒绝/挂账决策 > 默认）。返回 {icon, text, cls}。 */
function stateLine() {
  if (progress) {
    if (PHASE_KEYS.includes(progress.phase)) {
      return { icon: 'refresh-cw', text: `${t('neblink.updateCard.updating')} · ${phaseText(progress.phase)}`, cls: 'is-active' };
    }
    if (progress.phase === 'completed') {
      return { icon: 'check', text: t('neblink.updateCard.completed').replace('{version}', progress.latestVersion || ''), cls: 'is-ok' };
    }
    if (progress.phase === 'aborted' || progress.phase === 'rolled-back') {
      return { icon: 'alert-triangle', text: `${t('neblink.updateCard.aborted')} ${progress.detail || progress.reason || ''}`.trim(), cls: 'is-bad' };
    }
  }
  if (result && result.accepted === false && result.status === 'refused') {
    return { icon: 'alert-triangle', text: `${t('neblink.updateCard.refusedFrame')} ${result.error || ''}`.trim(), cls: 'is-bad' };
  }
  const d = snapshot?.decision;
  const tg = snapshot?.target;
  if (d?.outcome === 'refused') {
    return {
      icon: 'alert-triangle',
      text: tg ? `${t('neblink.updateCard.behind').replace('{version}', tg.version || '')} · ${refusedReasonText(d.reason, d.reason)}` : refusedReasonText(d.reason),
      cls: 'is-bad',
    };
  }
  if (d?.outcome === 'adopted') {
    return { icon: 'refresh-cw', text: tg ? `${t('neblink.updateCard.adopted').replace('{version}', tg.version || '')}` : t('neblink.updateCard.updating'), cls: 'is-active' };
  }
  if (d?.outcome === 'insync' && tg) {
    return { icon: 'check', text: t('neblink.updateCard.insync').replace('{version}', tg.version || ''), cls: 'is-ok' };
  }
  if (tg) {
    // 见账但尚无决策（旧引擎/异常窗）：staleness 可见，不报错（DESIGN §8）
    return { icon: 'download', text: t('neblink.updateCard.behind').replace('{version}', tg.version || ''), cls: '' };
  }
  return null;
}

function iconFor(name) {
  return `<i data-lucide="${name}"></i>`;
}

/**
 * 构建卡片元素（contacts.js render() 设备段顶部调用）。快照未到（连接早期）⇒ null，
 * 面板不出空壳。卡随 render() 重建；帧到时的原位重绘走 paint()。
 */
export function buildLocalUpdateCard() {
  if (!snapshot || !snapshot.version) return null;
  const card = document.createElement('div');
  card.className = 'fm-update-card';
  card.id = 'fm-local-update-card';
  card.dataset.updateCard = '1'; // QA 断言面：与 data-device-section 同族的可机械定位契约

  const ch = snapshot.channel || 'stable';
  const build = snapshot.build && snapshot.build !== 'dev' ? ` · ${escapeHtml(snapshot.build)}` : '';
  let html = `<span class="fm-update-card-self">${iconFor('laptop')} ${escapeHtml(t('neblink.updateCard.self'))} `
    + `<span class="fm-update-card-version">${escapeHtml(snapshot.version)}</span>`
    + ` · ${escapeHtml(ch)}${build}</span>`;
  const line = stateLine();
  if (line) {
    html += `<span class="fm-update-card-state ${line.cls}">${iconFor(line.icon)} ${escapeHtml(line.text)}</span>`;
  }
  card.innerHTML = html;
  return card;
}

/** 帧到时的原位重绘（卡已挂载才动 DOM；未挂载 = 状态已更，等下次 render 自取）。 */
function paint() {
  const existing = document.getElementById('fm-local-update-card');
  if (!existing) return;
  const fresh = buildLocalUpdateCard();
  if (fresh) existing.replaceWith(fresh);
}
