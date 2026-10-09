// flowMapTab.js 拆分(FE组件化批次六 2026-09-28):徽章/文案小函数族 可复用模块(行为保持)。
// 原 flowMapTab.js :781-:872 — nodeFlagKeys / FM_FLAG_CLS / flagBadgesHtml /
// queueBadgeHtml / queueNoteText。badge 批 2026-09-05、queuepos 批 2026-09-14/15、
// vchip 批 2026-09-15 裁定注释逐字随迁;纯显示函数,零 flowMapTab 文件级态依赖。
// 导出面由 flowMapTab.js 原样转发(公共导出面字节稳定,外部 import 方零改动)。
import { esc } from '../flowHelpers.js';
import { t } from '../i18n.js';
import { mergeQueueView, queueNameLabel } from '../mergeQueueView.js';

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
