// fmNodeCard.js — fm-node 卡渲染件（#1228 换源接线批 · 自 flowMapTab.js 拆出的单源件）。
//
// 角色：.solar-node.fm-node 卡片（含折叠链摘要卡）HTML 的**唯一**渲染单源。
// 消费方：flowMapTab.js（per-project 主图，调用面逐字不变）与 delegateChainView.js
// （S10 per-task 链视图 / S11 结构总视图）。拆出纪律＝「渲染件拆出复用、禁复制双
// 权威」（#1228 增补③）：两处渲染都走本件，卡面 DOM 语言（类名/结构/内联 SVG）只
// 此一份；flowMapTab 的渲染输出与本拆出前**逐字节同**（下方 nodeHtml 的 opts 扩展
// 全部缺省关闭，主图调用点零变化）。
//
// 本件只做卡面 HTML：布局（层心/层距/画布尺寸/增量 diff）仍属 flowMapTab；卡面
// 几何常量（NODE_W/NODE_H/PAD/cardTopOf）随卡走（卡内 left/top 计算是卡面 HTML
// 的一部分），flowMapTab 布局侧改从本件 import——常量单源不搬家复制。

import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { NODE_STATUS_CLS } from './nodeData.js';
import { isTerminalStatus } from './flowMapArchive.js';
// 排队视图共享解析（queuepos 批 2026-09-15）：卡与详情面板同源一份解析，落叶子模块
// （flowMapTab → flowMapArchive 单向依赖，共享件不可放 flowMapArchive；见模块头注）。
import { mergeQueueView, queueNameLabel } from './mergeQueueView.js';

// ── 卡面几何（自 flowMapTab.js 随卡迁入 · 单源）────────────────────────────
// NODE_W 对齐真实卡宽（FLOW_CSS .solar-node width:124px）——旧值 150 让布局数学
// 虚胖 26px/卡（水平净距虚增）；V_SPACING 150：典型卡高 88-105px 时层间净距 45-62px，
// 最厚卡（result+等待脚注 ~134px）仍 +16px 不重叠；H_SPACING 168 → 水平净距 44px。
export const NODE_W = 124;
export const NODE_H = 108;
export const PAD = 48;

/** 卡左上角（画布局部坐标 = world）。x 由层内水平排布定（卡宽恒 NODE_W）；
 *  y 以 pos.y 为**竖直中心**（与 collectEdges 的边端点锚点「位置即节点中心」同口径），
 *  卡高取 pos.h（实测高，标称下限 108）——标称卡与旧式 `pos.y - NODE_H/2` 逐像素一致。 */
export function cardTopOf(pos) { return pos.y - (pos.h || NODE_H) / 2 + PAD; }

// ── 内联 SVG 图标（裁定①：节点域禁 emoji/符号字符，状态图标用 SVG 描边绘制）──
// 描边风格统一：viewBox 12×12、stroke currentColor（随 .ok/.err/.warn 色板取色）、
// 圆头圆角；cancelled 减号线沿用原「—」语义。
export const FM_STATUS_SVG = {
  ok: '<path d="M2.5 6.5 5 9l4.5-5.5"/>',
  err: '<path d="M3 3l6 6M9 3l-6 6"/>',
  warn: '<path d="M3.5 1.5v9M3.5 2.5H9L7.5 4.75 9 7H3.5"/>',
  cancelled: '<path d="M3 6h6"/>',
};
/** 卡状态词 i18n 键（2026-09-07 状态保真批）：glyph 单看不可读（cancelled 减号
 *  线视觉即「-」）——终态卡 head 行 glyph 旁恒带状态文本，死亡现场一眼可辨。
 *  ready-to-merge（#1228 两裁①文案键 1）：delegate 链视图卡面用（待合并）——
 *  主图引擎节点无此状态（值域不含），本条目对主图渲染**零影响**（字节零漂移）。 */
export const FM_NODE_ST_KEY = {
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
  'ready-to-merge': 'delegate.readyToMerge',
};
export const fmSvgIcon = (cls, inner, sw) =>
  `<span class="solar-node-status ${cls}"><svg viewBox="0 0 12 12" fill="none" stroke="currentColor"`
  + ` stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${inner}</svg></span>`;
// 等待脚注图标：沙漏（替代 ⏳，描边风格与状态图标一致）
export const FM_WAIT_ICON =
  '<svg class="fm-wait-note-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor"'
  + ' stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
  + '<path d="M3 1.5h6M3 10.5h6M3.8 2.6h4.4L6 6 3.8 2.6ZM3.8 9.4h4.4L6 6 3.8 9.4Z"/></svg>';

// ── 特殊节点标识（badge 批 2026-09-05）：解析与渲染分离（便于 fixture 验证）──
// 三类标识彼此可辨（文字 + 配色双通道，裁定①禁 emoji 故纯文字胶囊）：
//   merge   结构属性——数据契约 `"merge": true`，缺省/缺失 = 非 merge；
//   loop    结构属性——前瞻防御，机制侧可能暂不产出该字段，缺字段不 crash；
//   pending 状态徽标——既有 status=pending（待作者确认/派发）。
// 判定严格 `=== true`：payload 缺字段/类型漂移一律静默降级为无徽标，零 console 噪音。
/** @returns {string[]} 命中的标识键，顺序 verifier → merge → loop → pending
 *  （**角色先于其他结构属性**：role 是节点「是什么」的第一层身份；结构先于状态）。 */
export function nodeFlagKeys(n) {
  if (!n || typeof n !== 'object') return [];
  const keys = [];
  // 角色标识（vchip 批 2026-09-15）：`role` 由载荷条件键下发（ProjectTypes.scala
  // NodePayload.roleFields：**仅非 task 携带** ⇒ 缺键 = task，存量节点字段集零漂移）。
  // 严格 `=== 'verifier'`：缺键 / 形状漂移一律静默降级为无徽标（与 merge/loop 同款
  // 纪律，零 console 噪音）。verifier 的申报值域（pass/fail）在图上原本不可见——本
  // 键即该身份在 Flow Map 上的唯一可见面。
  if (n.role === 'verifier') keys.push('verifier');
  if (n.merge === true) keys.push('merge');
  // loop 判定双形态（语义演进）：新建 loop 节点 payload 是配置对象
  // {maxRounds, verify, enabled}；早前前瞻防御批曾约定布尔 true。两种都算 loop。
  if (n.loop === true || (n.loop && typeof n.loop === 'object' && !Array.isArray(n.loop))) keys.push('loop');
  if ((n.status || 'pending') === 'pending') keys.push('pending');
  return keys;
}
const FM_FLAG_CLS = { verifier: 'fm-flag-verifier', merge: 'fm-flag-merge', loop: 'fm-flag-loop', pending: 'fm-flag-pending' };

/** 徽标 HTML：head 行胶囊，与 .fm-worktree-badge 同语言（文案 i18n flowmap.flag.*）。
 *  loop 徽标带轮次（LoopNode 批 2026-09-06）：loop 节点运行态显示「loop N/K」—
 *  复用现有 .fm-flag-loop 胶囊配色，不改卡内纵行（88px 卡纵向不可加行）。
 *  loopRound>0 才带轮次（pending/终态回退纯「循环」文案）。 */
export function flagBadgesHtml(n) {
  return nodeFlagKeys(n).map((k) => {
    let label = esc(t(`flowmap.flag.${k}`));
    if (k === 'loop') {
      const round = n.loopRound || 0;
      const maxRounds = (n.loop && typeof n.loop === 'object') ? n.loop.maxRounds : undefined;
      if (round > 0 && maxRounds) label = esc(`loop ${round}/${maxRounds}`);
    }
    return `<span class="fm-flag-badge ${FM_FLAG_CLS[k]}" title="${label}">${label}</span>`;
  }).join('');
}

// ── 合并窗排队位次（排队位次可见性批 2026-09-14；作者 16:39 双裁 = 显示「数字 + 持有者
//    双显」、路线「案 A：引擎条件键 + 前端渲染」）────────────────────────────
//    **queuepos 批 2026-09-15 修正**（作者现场报「节点显示的前面还有几个都一样 / 详情面板
//    里也没有」）：数字面从 `mergeQueue.ahead` 换成 `mergeQueuePos.position` ——`ahead` 是
//    **阻塞集合的势**（「谁挡着我」）而非位次，同刻只有一个 running 时全体排队者 `ahead ≡ 1`
//    ⇒ 看起来一模一样；而 `position` 逐节点唯一、真源 = SEM-2 rank 升序。两键分工与解析
//    单点见 `./mergeQueueView.js` 头注；`mergeQueue`（含 `ahead`/`holders`）语义**逐字冻结**。
// 🔴 前端**只渲染不派生**：位次数字一律取载荷 `mergeQueuePos.position`（禁本地复算 rank /
//    持有者 / 准入过滤——判据持有方 = 引擎单点，前端复刻必静默漂移）；🔴 禁读文件票层
//    （.nebflow/locks/main-merge.queue）、🔴 禁从事件流回放（事件流是审计面）。
// 🔴 降级红线（草案 §4 逐字）：键缺失 / 形状漂移 / 不可计算 ⇒ **不渲染数字**（至多裸
//    「排队中」）；🔴 禁编造数字；🔴 禁把事件流下界当真值（「读不到」≠「不在排队」）；
//    🔴 **禁把阻塞数（ahead）当位次渲染**。同键多项目（O-1）⇒ 引擎侧持有者派生自本项目
//    store、他项目节点结构性不可见 ⇒ 位次**不可信** ⇒ 降级为裸「排队中」（数字不渲染，
//    持有者清单照列）。

/** head 行排队胶囊（文案形态「排队中 · 第 N 位 / 共 M」——queuepos 批：位次逐节点唯一、
 *  N/M 均取载荷 `mergeQueuePos`；`mergeQueue` 的持有者清单不变，仍在脚注「被 XX 挡着」）。
 *  降级两态（红线段）：位次不可计算（键缺失/形状漂移） 或 同键多项目 ⇒ 裸「排队中」无数字。
 *  title 恒带完整信息（头部窄行不牺牲可读性）。 */
export function queueBadgeHtml(n) {
  const q = mergeQueueView(n);
  if (!q) return '';
  const names = q.holders.map((h) => h.name).join(' · ');
  // 数字面只认 `mergeQueuePos.position`（🔴 禁由 ahead 顶替；不可信 ⇒ 不渲染数字）。
  const numbered = !!q.pos && !q.untrusted;
  const label = numbered
    ? esc(t('flowmap.queue.pos', { n: String(q.pos.position), m: String(q.pos.total) }))
    : esc(t('flowmap.queue.held'));
  const head = q.untrusted
    ? t('flowmap.queue.untrustedHead')
    : (q.pos
        ? t('flowmap.queue.posHead', {
            queue: queueNameLabel(q.pos.queue), n: String(q.pos.position), m: String(q.pos.total) })
        : t('flowmap.queue.heldHead'));
  const title = names ? `${head} — ${t('flowmap.queue.blockedBy', { names })}` : head;
  return `<span class="fm-flag-badge fm-flag-queue" title="${esc(title)}">${label}</span>`;
}

/** 等待脚注行文案（**形态「被 XX 挡着」**——作者 16:39 裁定②，XX = 持有者节点名，
 *  多持有者全部列出）。同键多项目 ⇒ 追加不可信标注（不隐藏既有持有者清单）。
 *  返回 '' = 未被挡（调用方保持既有脚注逻辑逐字不变）——注意：队首节点（无人挡它）
 *  在 `mergeQueue` 下无持有者 ⇒ 本行仍为空，它的可见面在 head 行胶囊（`mergeQueuePos`）。 */
export function queueNoteText(n) {
  const q = mergeQueueView(n);
  if (!q || !q.holders.length) return '';
  const names = q.holders.map((h) => h.name).join(' · ');
  const base = t('flowmap.queue.blockedBy', { names });
  return q.untrusted ? `${base}${t('flowmap.queue.untrustedSuffix')}` : base;
}

// ── 链摘要卡（折叠态，spec §3.3；复用节点卡几何 + 既有状态 glyph/色板 token）──
// 一行式内容对齐归档链条目（.fm-entry）：状态词 + 链名 + `N 节点` + 分状态计数 +
// chevron。零新色值（色板全取 flowMap.css/flowCss.js 既有 token）。点击本体或
// chevron = 展开该链（容器级捕获委托分派，见 flowMapTab bindFlowMapClicks——不做节点
// 详情入口：chainId 不是节点 id）。折叠态下成员徽标信息聚合为计数行（spec §3.3 徽标兼容）。
/** 链摘要卡计数行分桶（顺序 = 卡面展示顺序）：终态三色 + running + blocked。
 *  pending/wiring 不设桶（未起步，链卡状态词已表达；零计数桶不渲染）。 */
export const FM_CHAIN_BUCKETS = ['completed', 'running', 'failed', 'cancelled', 'blocked'];

/** 链摘要卡计数行 glyph（spec §3.3「✓n · 跑n · ✗n」）：沿用节点卡 FM_STATUS_SVG
 *  同一批 path（✓/✗/—/⚑ 描边语言），running 用空心圆点（与 .solar-dot 圆点语义
 *  同源）——裁定①禁 emoji/符号字符，全 SVG 描边。cls 为 .solar-node-status 色类
 *  （flowCss.js 既有色板），空串 = 继承容器色（running=链色）。 */
export const FM_CHAIN_GLYPH = {
  completed: { cls: 'ok', inner: FM_STATUS_SVG.ok, sw: 1.5 },
  running: { cls: '', inner: '<circle cx="6" cy="6" r="2.6"/>', sw: 1.4 },
  failed: { cls: 'err', inner: FM_STATUS_SVG.err, sw: 1.5 },
  cancelled: { cls: 'cancelled', inner: FM_STATUS_SVG.cancelled, sw: 1.5 },
  blocked: { cls: 'warn', inner: FM_STATUS_SVG.warn, sw: 1.4 },
};

/** 链摘要卡状态词 i18n 键（最坏态优先：failed > cancelled > running > blocked >
 *  全完成 > pending，与归档链 chainStatusOf 同构的「最坏态」语义）。 */
export const FM_CHAIN_ST_KEY = {
  completed: 'flowmap.done',
  running: 'flowmap.run',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
  blocked: 'flowmap.blocked',
  pending: 'flowmap.wait',
};

/** 折叠 chevron（指向右 = 「点击展开」；与归档条目 chevron 同一描边语言）。 */
const FM_CHEV_SVG = '<svg viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.5"'
  + ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3.5 2l3.5 3-3.5 3"/></svg>';

/** ── 链级取消（R2，chaincancel 批 2026-09-17）─────────────────────────────────
 *  落点 = 链入口成员卡 head 行 `.fm-chain-chev` **同行** + 折叠链摘要卡 head 行同款
 *  （两形态共用同一按钮形态与容器级捕获委托单点——委托分派在 flowMapTab）。
 *  🔴 前端**只发 chainId**——成员集合 / 状态判定 / 级联闭包全在后端现读派生
 *  （「后端下发、零派生」既有硬纪律）；本件的 `memberIds` 消费面**仅**用于确认卡的
 *  计数显示（作者明列口径）。 */
const FM_CANCEL_SVG = '<svg viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.5"'
  + ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2.2 2.2l5.6 5.6M7.8 2.2l-5.6 5.6"/></svg>';

/** 链级取消按钮（两形态同款；`data-chain-id` 是唯一载荷，无状态/成员信息）。 */
function chainCancelButtonHtml(chainId) {
  const cid = String(chainId || '');
  if (!cid) return '';
  const label = t('flowmap.chain.cancel.label');
  return `<button type="button" class="fm-chain-cancel" data-chain-id="${esc(cid)}"`
    + ` aria-label="${esc(label)}" title="${esc(label)}">${FM_CANCEL_SVG}</button>`;
}

export function chainCardHtml(n, pos, originX) {
  const st = String(n.status || 'pending');
  const cls = NODE_STATUS_CLS[st] || 'pending';
  const left = pos.x - NODE_W / 2 + originX;
  const top = cardTopOf(pos);
  const statusIcon = st === 'completed' ? fmSvgIcon('ok', FM_STATUS_SVG.ok, 1.5)
    : st === 'failed' ? fmSvgIcon('err', FM_STATUS_SVG.err, 1.5)
    : st === 'cancelled' ? fmSvgIcon('cancelled', FM_STATUS_SVG.cancelled, 1.5)
    : st === 'blocked' ? fmSvgIcon('warn', FM_STATUS_SVG.warn, 1.4) : '';
  const counts = n.counts || {};
  const countItems = FM_CHAIN_BUCKETS
    .filter((k) => counts[k] > 0)
    .map((k) => {
      const g = FM_CHAIN_GLYPH[k];
      return `<span class="fm-chain-count ${k}">${fmSvgIcon(g.cls, g.inner, g.sw)}${counts[k]}</span>`;
    }).join('');
  const title = String(n.name || n.id);
  const nodesText = t('flowmap.chain.nodes', { n: String(n.memberCount || 0) });
  return `
    <div class="solar-node fm-node fm-chain-card ${cls}" data-node-id="${esc(String(n.id))}"
         data-chain-card="1" data-chain-id="${esc(String(n.id))}" data-status="${esc(st)}"
         tabindex="0" title="${esc(t('flowmap.chain.cardHint', { chain: title, n: String(n.memberCount || 0) }))}"
         style="left:${left.toFixed(1)}px;top:${top.toFixed(1)}px">
      <div class="fm-node-head">${statusIcon}<span class="fm-st-word ${esc(cls)}">${esc(t(FM_CHAIN_ST_KEY[st] || 'flowmap.wait'))}</span>${chainCancelButtonHtml(n.id)}<button type="button" class="fm-chain-chev" data-chain-id="${esc(String(n.id))}" aria-expanded="false"
                aria-label="${esc(t('flowmap.chain.expand'))}" title="${esc(t('flowmap.chain.expand'))}">${FM_CHEV_SVG}</button>
      </div>
      <div class="solar-node-label" title="${esc(title)}">${esc(title)}</div>
      <div class="solar-node-sub">${esc(nodesText)}</div>
      ${countItems ? `<div class="fm-chain-counts">${countItems}</div>` : ''}
    </div>`;
}

// ── 节点卡片（复用 solar 视觉）────────────────────────────
// 🔴 换源接线批扩展（opts，全部缺省关闭 ⇒ 主图调用面输出逐字节不变）：
//   opts.plain      流式布局（S10/S11 链视图卡片列表）：省略绝对定位 style（left/top）
//                   ——主图画布仍需绝对定位，缺省保持；
//   opts.headPrefix head 行前置 HTML（S10 卡＝ChainNode，执行器图标置于 head 行首）；
//   opts.subText    副行文本覆盖（ChainNode 无 preset/plugins 面）；'' = 副行整行省略
//                   （零空行），title 属性同步收窄为「名（状态词）」。
//   状态词面：ready-to-merge（delegate 链新态）走 FM_NODE_ST_KEY 新条目（主图节点
//   值域不含该状态 ⇒ 主图渲染逐字节不变）。
/** @param {any} n @param {{x: number, y: number, h?: number}} pos
 *  @param {number} originX @param {(id: string) => string|null} [nameOf]
 *  @param {{plain?: boolean, headPrefix?: string, subText?: string}} [opts] */
export function nodeHtml(n, pos, originX, nameOf, opts = {}) {
  const o = opts || {};
  if (n && n.chainCard) return chainCardHtml(n, pos, originX); // 折叠链卡单点分派
  const st = n.status || 'pending';
  const cls = NODE_STATUS_CLS[st] || 'pending';
  const term = isTerminalStatus(st); // v3 链未齐终态保留卡（规格 §3.2 终态色卡）
  const delegateReady = st === 'ready-to-merge'; // delegate 链视图新态（主图值域不含 → 恒 false）
  const left = pos.x - NODE_W / 2 + originX;
  const top = cardTopOf(pos);
  const styleAttr = o.plain ? '' : ` style="left:${left.toFixed(1)}px;top:${top.toFixed(1)}px"`;
  const statusIcon = st === 'completed' ? fmSvgIcon('ok', FM_STATUS_SVG.ok, 1.5)
    : st === 'failed' ? fmSvgIcon('err', FM_STATUS_SVG.err, 1.5)
    : st === 'blocked' ? fmSvgIcon('warn', FM_STATUS_SVG.warn, 1.4)
    : st === 'cancelled' ? fmSvgIcon('cancelled', FM_STATUS_SVG.cancelled, 1.5) : '';
  // 状态词（2026-09-07 状态保真批）：终态卡 glyph 旁恒带文本（已完成/失败/
  // 已取消）——真实终态可见，不再是孤零零的「-」减号线。delegate 链视图的
  // ready-to-merge 卡同样带词（待合并——§16 键 1）。
  const statusWord = (term || delegateReady) && FM_NODE_ST_KEY[st]
    ? `<span class="fm-st-word ${esc(cls)}">${esc(t(FM_NODE_ST_KEY[st]))}</span>` : '';
  const worktreeBadge = n.hasWorktree || n.worktree
    ? `<span class="fm-worktree-badge" title="${esc(n.worktree || '')}">wt</span>` : '';
  // 特殊节点标识（badge 批）：merge/loop/pending 徽标进 head 行（wt 徽标同区，
  // 复用该行既有 flex+gap；不新增卡内纵行——88px 卡纵向不可加行，同 node-flowmap-slim 口径）
  const flags = flagBadgesHtml(n);
  // 排队位次胶囊（排队位次可见性批）：head 行内、flags 之后（与 merge/pending 同区，
  // 复用该行既有 flex+gap）。未排队（键缺失）⇒ '' ⇒ head 行 DOM 与改动前**逐字一致**。
  const queueFlag = queueBadgeHtml(n);
  // 链折叠控件（P1 · spec §3.3）：链入口成员卡 head 行右端 chevron（控件属链不属成员
  // ——仅「可见成员 ≥2 的链」的入口卡带，成员 ≤1 恒无）。点击 = 折叠该链（容器级
  // 捕获委托单点分派，见 flowMapTab bindFlowMapClicks）；title/aria 带链名与成员数，避免
  // 「这个箭头的对象是谁」的歧义。
  // chaincancel 批（R2）：链入口成员卡 head 行右端新增 `.fm-chain-cancel`（折叠链摘要卡
  // 同款）——**只发 chainId**；判定/执行全在后端。位置在 `chainChev`
  // **之前**：`.fm-chain-chev` 的 `margin-left:auto` 保持既有 chevron 的右端定位不变
  // （既有控件零视觉漂移），取消控件紧贴其左。
  const chainChev = n.chainHead
    ? chainCancelButtonHtml(n.chainHead.id) + `<button type="button" class="fm-chain-chev fm-chain-chev-open" data-chain-id="${esc(String(n.chainHead.id))}"
         aria-expanded="true" aria-label="${esc(t('flowmap.chain.collapse', { chain: String(n.chainHead.title), n: String(n.chainHead.n) }))}"
         title="${esc(t('flowmap.chain.collapse', { chain: String(n.chainHead.title), n: String(n.chainHead.n) }))}">${FM_CHEV_SVG}</button>`
    : '';
  // 载荷收敛（2026-09-05）：卡片不再显示 result 摘要（默认载荷无 result）——
  // 改显示 description（创建必写的一行描述）；存量节点无 description → 回退
  // taskPreview（载荷条件字段，task 首行 ≤80 截断）。结果全文经详情窗按需拉取。
  const descText = n.description || n.taskPreview || '';
  // LoopNode 运行态（2026-09-06）：running 的 loop 节点 desc 行显相位（worker/verify），
  // 与 head 行「loop N/K」徽标互补（徽标上轮次、desc 行当前角色）——复用既有 .fm-desc
  // 行，不新增卡内纵行（88px 卡纵向不可加行）。
  const loopPhase = (st === 'running' && n.loopPhase && n.loop && typeof n.loop === 'object')
    ? esc(t(`flowmap.loopPhase.${n.loopPhase === 'verify' ? 'verify' : 'worker'}`)) : '';
  const desc = descText
    ? `<div class="fm-desc" title="${esc(descText)}">${esc(descText.slice(0, 46))}${descText.length > 46 ? '…' : ''}</div>`
    : (st === 'running'
        ? `<div class="fm-desc running">${loopPhase || esc(t('flowmap.cardRunning'))}</div>`
        : '');
  // 等待脚注（deps 设计 §1.4）：pending/wiring 且持有 in/deps → 列出全部等待对象
  //（in = 等结果投递，deps = 等完成信号；上游已归档 → i18n 纯文字诚实降级，
  //  原 ⏳ 图标按裁定①换内联 SVG 沙漏，文字单独 ellipsis 截断）
  const waitParts = (st === 'pending' || st === 'wiring')
    ? [...(n.in || []), ...(n.deps || [])].map((id) => nameOf ? nameOf(id) : id)
    : [];
  // 合并窗排队脚注（排队位次可见性批 2026-09-14）：**同一脚注行**（不新增卡内纵行）——
  // 排队态首段 = 「被 XX 挡着」（持有者全列，作者 16:39 裁定②）；上游等待段非空时
  // 以 ` · ` 续接（合并窗等待与上游等待是两个独立成因，禁互相吞并）。未排队 ⇒
  // queueNote 为空串 ⇒ 本行文案与改动前**逐字一致**（既有 emoji 清零/防溢出两条
  // 验收口径零回归）。
  const queueNote = queueNoteText(n);
  const waitText = queueNote
    ? (waitParts.length ? `${queueNote} · ${t('flowmap.waitingFor')}: ${waitParts.join(' · ')}` : queueNote)
    : (waitParts.length ? `${t('flowmap.waitingFor')}: ${waitParts.join(' · ')}` : '');
  const waitNote = waitText
    ? `<div class="fm-wait-note${queueNote ? ' fm-wait-note-queue' : ''}" title="${esc(waitText)}">`
      + `${FM_WAIT_ICON}<span class="fm-wait-note-text">${esc(waitText)}</span></div>`
    : '';
  // Agent 退役（node-flowmap-slim：节点恒 general，卡上无信息量）——副行改显节点
  // 元数据：preset 常显（未配置 → 克制空态「默认预设」），plugins 有则以
  // `preset · p1, p2` 同行合显、无则零空态段。单行口径与归档成员行/详情窗 meta
  // 一致；88px 固定卡纵向不可加行（独占行实测溢出加剧），复用 .solar-node-sub。
  // 🔴 换源接线批：opts.subText 给出时整面覆盖（ChainNode 无 preset/plugins 权威面，
  //   禁回落「默认预设」臆造空态）；'' = 副行整行省略（零空行）。
  const subGiven = 'subText' in o;
  const presetText = n.preset || t('flowmap.presetDefault');
  const pluginsText = (n.plugins || []).join(', ');
  const subTitle = subGiven ? String(o.subText || '') : (pluginsText ? `${presetText} · ${pluginsText}` : presetText);
  const titleAttr = subGiven
    ? `${esc(n.name)}${subTitle ? ` · ${esc(subTitle)}` : ''}${(term || delegateReady) && FM_NODE_ST_KEY[st] ? `（${esc(t(FM_NODE_ST_KEY[st]))}）` : ''}`
    : `${esc(n.name)} · ${esc(subTitle)}${term && FM_NODE_ST_KEY[st] ? `（${esc(t(FM_NODE_ST_KEY[st]))}）` : ''}`;
  const subHtml = !subGiven || subTitle ? `<div class="solar-node-sub">${esc(subTitle)}</div>` : '';
  return `
    <div class="solar-node fm-node ${cls}${term ? ' terminal' : ''}" data-node-id="${esc(n.id)}"
         data-status="${esc(st)}" tabindex="0" title="${titleAttr}"${styleAttr}>
      <div class="solar-orbit">
        <div class="solar-ring ring-1"><div class="solar-dot-wrap"><div class="solar-dot-spin"><div class="solar-dot"></div></div></div></div>
        <div class="solar-ring ring-2"><div class="solar-dot-wrap"><div class="solar-dot-spin"><div class="solar-dot"></div></div></div></div>
        <div class="solar-ring ring-3"><div class="solar-dot-wrap"><div class="solar-dot-spin"><div class="solar-dot"></div></div></div></div>
      </div>
      <div class="fm-node-head${queueFlag ? ' fm-head-queued' : ''}">${o.headPrefix || ''}${worktreeBadge}${flags}${queueFlag}${statusIcon}${statusWord}${chainChev}</div>
      <div class="solar-node-label" title="${esc(n.name)}">${esc(n.name)}</div>
      ${subHtml}
      ${st === 'pending' && (n.in || []).length > 1 ? `<div class="fm-barrier-hint">barrier ×${(n.in || []).length}</div>` : ''}
      ${waitNote}
      ${desc}
    </div>`;
}
