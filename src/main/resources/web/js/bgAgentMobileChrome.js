// bgAgentMobileChrome.js — 手机端后台 agent 对话框简化批（作者令 2026-11-06 10:00/12:03
// 逐字「状态显示放顶部 不要有重复的叉号 然后具体的任务细节…在顶部放一个三个点的
// 悬浮控件 点进去可查看 然后顶部中间胶囊就显示当前状态就行 右下角的终止按钮也改为
// 右下角悬浮 样式 让整体界面简洁」）。
//
// 六条口径 → 本模块的六件事（全部只在 body.mobile-layer 下发生，桌面零变化）：
//   ① 顶带状态胶囊（中）：只显示状态词面 + 时长（「运行中 · 7m」），不带任务标题。
//      词面 = 既有 locale 键（bg.running / subagents.status.* / chat.freeze*Tile，
//      冻结态按冻结态误报批 2026-11-05 的呈现分类分词——自动恢复族绝不写「已冻结」）；
//      色调 = bgRowStatus.js BG_STATUS_VISUAL 同一张表的 bg-status-<tone> 类（零新色）。
//   ② 删重复叉号：标题行 #bgagent-close 整件退役（DOM 移除）——关闭路径只留
//      popSheet.mobileSheetBack 注入的左上返回圆钮（「发起群聊」批同款）。
//   ③ 右上 ⋯ 悬浮控件（g2-fab 48 档，会话页 .mh-sess-more 同几何）：点开毛玻璃
//      详情浮层（nf-glass 单源材质）——插件 / 工作树 / 模型 / 创建时间 / 来源
//      （badgeFaceOfKind 单源词面）+ 任务内容全文（delegate 摘要条迁入）+ 最近动作 /
//      本会话后台任务（bgws 条迁入，口径⑥灰行收进详情）。
//   ④ 底部状态条退役：footer 由 mobileLayer.css 整条隐藏；uptime 由胶囊承担。
//   ⑤ 终止钮 = 右下悬浮圆钮：footer 管理条里的 stopBtn **同一节点**迁出（不建第二
//      个按钮、不复制 cancelAgent 线路——bindManageActions/syncManageControls 照旧
//      单点驱动），挂 .g2-fab + 危险档配色；行内小方块随 footer 退役。
//   ⑥ retry（冻结需人介入族的「重启并续跑」入口）+ 重试计数 chip 迁入 ⋯ 详情底部
//      动作区（功能零删：恢复入口可达，主界面更不出现）。
//
// 单实现纪律：不新造词表（locale/bgRowStatus/termWords 既有单源）、不新造圆钮
// （.g2-fab）、不新造玻璃（.nf-glass）、不新造返回钮（popSheet）；几何档与
// 「发起群聊」批同一族（桌面 40 / 手机 48 = --mtap-lg，mobileLayer.css 统一批）。
//
// 桌面零变化的两道闸：① 本函数首行 body.mobile-layer 判定（假 ⇒ 返回 null，调用方
// 走原路径）；② 全部样式挂 body.mobile-layer .bgagent-overlay 选择器
// （mobileLayer.css「后台对话框手机简化批」块）。

import { t } from './i18n.js';
import { statusIcon, createIconsIn } from './utils.js';
import { fmtUptime } from './managePanel.js';
import { bgStatusVisual } from './bgRowStatus.js';
import { badgeFaceOfKind } from './termWords.js';
import { fetchDelegateDetail, isDelegateDomainId } from './delegateApi.js';
import { FREEZE_KIND, freezeKindOf } from './freezeState.js';
import { isErrorReason } from './errorRecovery.js';

const REFRESH_MS = 15000; // 与 bgAgentWorkStrip 同拍（时长/活动戳滚动节奏一致）

/** 打开中的 chrome 账本（单例——同一时刻一个弹窗）：refresh 节拍 + 清理句柄。 */
let active = null;

/** 胶囊状态词面（零 locale 新键）：meta.status → 既有键 + BG_STATUS_VISUAL 色调键。
 *  冻结按呈现分类分词（冻结态误报批 2026-11-05）：需人介入族「已冻结」、自动恢复族
 *  「自动重试中」/「等待工作时段」——绝不用「已冻结」骗作者。 */
function capsuleFace(meta) {
  const status = String((meta && meta.status) || '');
  if (status === 'running' || status === 'thinking' || status === 'tool' || status === 'responding') {
    return { tone: 'active', word: t('bg.running') };
  }
  if (status === 'waiting') return { tone: 'waiting', word: t('subagents.status.waiting') };
  if (status === 'frozen') {
    const kind = freezeKindOf(meta || { frozen: true });
    if (kind === FREEZE_KIND.window) return { tone: 'frozen-auto', word: t('chat.freezeWindowTile') };
    // 需人介入族（错误冻结/循环检测/已升级到人）：如实说「已冻结」，恢复入口在 ⋯ 详情。
    if (kind === FREEZE_KIND.manual) return { tone: 'frozen', word: t('subagents.status.frozen') };
    // 自动恢复族：原因词太长进不了胶囊，只说「在等什么 + 会自己续跑」的词面。
    const why = String((meta && meta.freezeReason) || '');
    return {
      tone: 'frozen-auto',
      word: t('chat.freezeAutoTile', { reason: why }).replace(/\s*·\s*$/, '').trim(),
    };
  }
  if (status === 'stuck') return { tone: 'stuck', word: t('subagents.status.stuck') };
  if (status === 'failed') return { tone: 'error', word: t('bg.failed') };
  if (status === 'done') return { tone: 'done', word: t('bg.completed') };
  if (status === 'stopped') return { tone: 'cancelling', word: t('bg.cancelled') };
  return { tone: 'idle', word: t('subagents.status.idle') };
}

/** 状态图标名（BG_STATUS_VISUAL 表——图标零散名禁令的同一张表）。 */
function capsuleIcon(tone) {
  return bgStatusVisual(tone === 'frozen-auto' ? 'frozen' : tone).icon;
}

/**
 * 手机层 chrome 挂载（bgAgentPopup.openStepPopup 调用；桌面 ⇒ null，调用方走原路径）。
 * @param {{overlay: HTMLElement, modal: HTMLElement, bar: {stopBtn: HTMLButtonElement,
 *   retryBtn: HTMLButtonElement, retriesEl: HTMLElement, uptimeEl: HTMLElement},
 *   nodeSessionId: string, agentName: string}} spec
 * @returns {{sheetBody: HTMLElement, refresh: (meta: any) => void,
 *   autoExpandStrip: (host: HTMLElement, sid: string) => void}|null}
 */
export function mountBgAgentMobileChrome({ overlay, modal, bar, nodeSessionId, agentName }) {
  if (!overlay || !(overlay instanceof HTMLElement)) return null;
  if (!document.body.classList.contains('mobile-layer')) return null; // 桌面零变化第一道闸
  closeBgAgentMobileChrome(); // 幂等（重开路径不叠旧账本）

  // ② 删重复叉号：返回圆钮（mobileSheetBack）已是一条关闭路径，标题行 ✕ 整件退役。
  const closeX = overlay.querySelector('#bgagent-close');
  if (closeX) closeX.remove();

  // ① 顶带状态胶囊（只读面：pointer-events none，点击穿透给消息流）。
  const pill = document.createElement('div');
  pill.className = 'bgagent-status-pill';
  pill.setAttribute('role', 'status');
  const pillIcon = document.createElement('span');
  pillIcon.className = 'bgagent-pill-status';
  const pillWord = document.createElement('span');
  pillWord.className = 'bgagent-pill-word';
  const pillDur = document.createElement('span');
  pillDur.className = 'bgagent-pill-dur';
  pill.append(pillIcon, pillWord, pillDur);
  overlay.appendChild(pill);

  // ③ 右上 ⋯ 键（g2-fab；几何 = 会话页 .mh-sess-more 同一条 CSS 档）。
  const moreBtn = document.createElement('button');
  moreBtn.type = 'button';
  moreBtn.className = 'bgagent-more g2-fab';
  const moreLabel = t('bgagent.more');
  moreBtn.title = moreLabel;
  moreBtn.setAttribute('aria-label', moreLabel);
  moreBtn.setAttribute('aria-expanded', 'false');
  moreBtn.innerHTML = '<i data-lucide="more-horizontal"></i>';
  overlay.appendChild(moreBtn);
  if (typeof lucide !== 'undefined') createIconsIn(moreBtn);

  // ⋯ 详情浮层：scrim（点外关闭，无暗幕——菜单形态）+ 毛玻璃卡（nf-glass 单源）。
  const sheet = document.createElement('div');
  sheet.className = 'bgagent-more-sheet';
  sheet.hidden = true;
  const scrim = document.createElement('div');
  scrim.className = 'bgagent-sheet-scrim';
  const card = document.createElement('div');
  card.className = 'bgagent-sheet-card nf-glass';
  card.setAttribute('role', 'dialog');
  card.setAttribute('aria-label', moreLabel);
  const cardTitle = document.createElement('div');
  cardTitle.className = 'bgagent-sheet-title';
  cardTitle.textContent = agentName || '';
  cardTitle.hidden = !agentName; // 零空位
  const metaBlock = document.createElement('div');
  metaBlock.className = 'bgagent-sheet-meta';
  const actions = document.createElement('div');
  actions.className = 'bgagent-sheet-actions';
  actions.hidden = true; // refresh 按恢复入口可达性开合（零空区）
  card.append(cardTitle, metaBlock);
  // 摘要条/「会话在做什么」条由 bgAgentPopup 追加在 metaBlock 之后（sheetBody 锚），
  // actions 恒垫底。
  card.appendChild(actions);
  sheet.append(scrim, card);
  overlay.appendChild(sheet);

  // 开合（⋯ 键再点 = 关；scrim 点 = 关；scrim 停冒泡——弹窗点空白关闭监听不吞）。
  const setSheet = (open) => {
    sheet.hidden = !open;
    moreBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
  };
  moreBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    setSheet(sheet.hidden);
  });
  scrim.addEventListener('click', (e) => {
    e.stopPropagation();
    setSheet(false);
  });

  // ⑤ 终止钮 = 右下悬浮圆钮：**同一节点**迁出 footer（管理条线路零复制）。
  // 类三件：.g2-fab = glass.css 圆钮级浮件档（材质/hover/active/禁用/弹簧单源，
  // 「发起群聊」批同款）；.bgagent-stop-fab = 本批落位（右下离边令牌）+ 危险档
  // 配色（mobileLayer.css）；.fa-mgmt-btn 原类保留（桌面 scoped 规则不追出 footer，
  // 无副作用）。行内小方块随 footer 退役（mobileLayer.css display:none）。
  const stopFab = bar && bar.stopBtn;
  if (stopFab) {
    stopFab.classList.add('g2-fab', 'bgagent-stop-fab');
    overlay.appendChild(stopFab);
    // 迁位补转换：openStepPopup 对图标做的是 bar.root 子树的异步 createIconsIn
    // —— 本同步迁位发生在它之前，迁出的钮不在子树里会漏转（实测圆钮空壳）。
    // 幂等（lucide 只替换未转换的 <i data-lucide>），与后到的 bar.root 转换不叠。
    if (typeof lucide !== 'undefined') createIconsIn(stopFab);
  }
  // ⑥ retry + 计数 chip 迁入详情浮层动作区（syncManageControls 照旧驱动可见性）。
  if (bar && bar.retryBtn) {
    bar.retryBtn.classList.add('bgagent-sheet-retry');
    actions.appendChild(bar.retryBtn);
    if (typeof lucide !== 'undefined') createIconsIn(bar.retryBtn); // 迁位补转换（同上）
  }
  if (bar && bar.retriesEl) {
    bar.retriesEl.classList.add('bgagent-sheet-retries');
    actions.appendChild(bar.retriesEl);
  }

  // ③ 详情 meta 行（数据到位才建行——零空位纪律）：来源 / 创建 / 工作树 / 插件。
  const metaRow = (labelKey, value, title) => {
    if (!value) return;
    const row = document.createElement('div');
    row.className = 'bgagent-meta-row';
    const label = document.createElement('span');
    label.className = 'bgagent-meta-label';
    label.textContent = t(labelKey);
    const val = document.createElement('span');
    val.className = 'bgagent-meta-value';
    val.textContent = value;
    val.title = title || value;
    row.append(label, val);
    metaBlock.appendChild(row);
  };
  if (isDelegateDomainId(nodeSessionId)) {
    fetchDelegateDetail(nodeSessionId).then((detail) => {
      if (!active || active.overlay !== overlay) return; // 弹窗已切走 → 静默弃
      const record = detail && detail.record && typeof detail.record === 'object' ? detail.record : null;
      if (!record) return; // 拉取失败 → meta 区缺席（fail-soft，浮层其余部分不受影响）
      metaRow('bgagent.detail.source', [
        badgeFaceOfKind('Delegate'),
        String(record.executor || ''),
        String(record.parentSessionId || ''),
      ].filter(Boolean).join(' · '));
      if (record.createdAt) {
        metaRow('bgagent.detail.created', new Date(Number(record.createdAt)).toLocaleString());
      }
      // 工作树：显式缺席（就地通道）也是信息 ⇒ 出「就地」词；无记录字段才缺席。
      metaRow('bgagent.detail.worktree',
        record.worktree
          ? (String(record.worktree).split('/').filter(Boolean).pop() || String(record.worktree))
          : t('bgagent.detail.noWorktree'),
        record.worktree ? String(record.worktree) : '');
      if (record.plugins && Array.isArray(record.plugins) && record.plugins.length) {
        metaRow('bgagent.detail.plugins', record.plugins.map((p) => String(p || '')).filter(Boolean).join(', '));
      }
    }).catch(() => { /* fail-soft：meta 区缺席 */ });
  }

  // ① 胶囊刷新（updateFooterStatus 直呼 + 15s 节拍——时长自行走字）。
  const refresh = (meta) => {
    const face = capsuleFace(meta);
    const dur = (meta && meta.startedAt && face.tone === 'active')
      ? fmtUptime(Date.now() - Number(meta.startedAt)) : '';
    pillIcon.className = `bgagent-pill-status bg-status-${face.tone}`;
    pillIcon.innerHTML = statusIcon(capsuleIcon(face.tone), 12);
    pillWord.textContent = face.word;
    pillDur.textContent = dur ? `· ${dur}` : '';
    pillDur.hidden = !dur;
    pill.setAttribute('aria-label', dur ? `${face.word} · ${dur}` : face.word);
    // ⑥ 动作区随恢复入口可达性开合（syncManageControls 刚在 updateFooterStatus 跑过）。
    if (bar && bar.retryBtn) actions.hidden = bar.retryBtn.style.display === 'none';
  };
  refresh({});
  const timer = setInterval(() => {
    if (active && typeof active.onTick === 'function') active.onTick();
  }, REFRESH_MS);

  const api = {
    sheetBody: card, // 摘要条 / bgws 条的挂载锚容器（bgAgentPopup 用）
    sheetAnchor: actions, // 条的插入点（恒在动作区之前；insertBefore 语义）
    refresh: (meta) => { if (active && active.overlay === overlay) refresh(meta); },
    autoExpandStrip,
  };
  active = { overlay, refresh, timer, onTick: null };
  return api;
}

/** 摘要条迁入浮层后自动展开一次（口径③「点进去可查看」＝任务内容全文开箱即读）；
 *  走**真实的 toggle click 路径**（aria-expanded/chevron 同步，二级折叠保留——用户
 *  可再收起）。数据到达（条 unhide）即展，拉取失败（条恒隐）不动作。 */
function autoExpandStrip(sheetBody, nodeSessionId) {
  if (!sheetBody || !(sheetBody instanceof HTMLElement)) return;
  const strip = sheetBody.querySelector(`[data-delegate-strip="${CSS.escape(nodeSessionId)}"]`);
  if (!strip) return;
  const mo = new MutationObserver(() => {
    if (strip.hidden) return;
    mo.disconnect();
    const toggle = strip.querySelector('.bgagent-strip-toggle');
    const body = strip.querySelector('.bgagent-strip-body');
    if (toggle && body && body.hidden) toggle.click(); // 同一开合路径，非平行实现
  });
  mo.observe(strip, { attributes: true, attributeFilter: ['hidden'] });
}

/** 卸载（bgAgentPopup.closeStepPopup 调用）：停节拍、摘账本。幂等。 */
export function closeBgAgentMobileChrome() {
  if (!active) return;
  if (active.timer) clearInterval(active.timer);
  active = null;
}
