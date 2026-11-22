// LLM 失败卡片（卡片批 2026-11-21；风格重做 2026-11-21；第 3 轮 2026-11-21：聚合键改层级+模型、
// 图标化压缩文案、大字主信息置顶）。
// 非阻塞。出卡 = 后端 llmFailure 组快照帧；收卡 = llmFailureRecovered 帧（组清空）。
// 聚合键 = 层级 + 模型（作者裁定）：同层级同模型的多个代理只出一张卡，卡上列出受影响代理。
// 视觉语言照 askuser 卡（chat.css）：卡面 `.option-box`；主标题 `.option-q`（大字，置顶）；
// 层级/分类/风险档一行小字（`.perm-reason` + `.perm-risk-badge`）；按钮 `.option-cancel`（等待）
// + `.option-confirm`（换模型，L3 主行动）。图标全部取仓内 lucide 既有名（data-lucide，PascalCase 导出）。
// 「换模型」经注入的 saveModel（= modelPanel.saveSlotPreferredModel，同一写入口）落盘；
// 「等待」不发任何答复（引擎自己退避续跑）。纯视图模型与 DOM 渲染分离，便于 node 测试。

/** 失败分类 → 中文标签 + 风险档（徽标颜色类 lv-1 / lv-2，同权限卡）。 */
const FAILURE_CLASS = {
  'llm-transient': { label: '临时故障', level: 2 },
  'llm-overloaded': { label: '过载', level: 1 },
  'llm-ratelimit': { label: '限流', level: 1 },
  'llm-auth': { label: '鉴权失败', level: 2 },
  'llm-timeout': { label: '超时', level: 1 },
  'llm-network': { label: '网络不可达', level: 2 },
};

/** 失败层级 → 中文标签 + 图标（根 = 主代理，团队成员 = 团队，子任务 = 委派）。 */
const LAYER = {
  root: { label: '主代理', icon: 'orbit' },
  team: { label: '团队成员', icon: 'users' },
  subtask: { label: '子任务', icon: 'network' },
};

/** 错误摘要截断上限（卡片只给一行摘要，完整原文在面板错误帧与日志里）。 */
export const SUMMARY_MAX = 120;
/** 代理名列表最多直接展示的个数，超出折叠为「等 N 个」。 */
export const AGENTS_SHOW_MAX = 3;

/** 聚合键：层级 + 模型（与后端 LlmFailureCard 的组键同构）。 */
export function llmFailureKey(layer, modelRef) {
  return `${layer || 'root'}|${modelRef || ''}`;
}

/**
 * 纯视图模型：把 llmFailure 组快照帧映射为卡片展示字段（无 DOM）。
 * @param {{layer?: string, modelRef?: string, agents?: string[], summary?: string, failureClass?: string}} msg
 */
export function buildLlmFailureModel(msg) {
  const layer = LAYER[msg && msg.layer] ? msg.layer : 'root';
  const modelRef = String((msg && msg.modelRef) || '');
  const agents = Array.isArray(msg && msg.agents) ? msg.agents.map(String).filter(Boolean) : [];
  const raw = String((msg && msg.summary) || '').replace(/\s+/g, ' ').trim();
  const summary = raw.length > SUMMARY_MAX ? raw.slice(0, SUMMARY_MAX - 1) + '…' : raw;
  const cls = String((msg && msg.failureClass) || '');
  const meta = FAILURE_CLASS[cls] || { label: cls || '未分类', level: 1 };
  const shown = agents.slice(0, AGENTS_SHOW_MAX);
  const more = agents.length - shown.length;
  const agentsText = agents.length === 0
    ? ''
    : shown.join('、') + (more > 0 ? ` 等 ${agents.length} 个` : '');
  return {
    key: llmFailureKey(layer, modelRef),
    layer,
    layerLabel: LAYER[layer].label,
    layerIcon: LAYER[layer].icon,
    modelRef,
    title: `${modelRef || '未知模型'} 调用失败`,
    failureLabel: meta.label,
    level: meta.level,
    agents,
    agentsText,
    summary: summary || '（无错误摘要）',
    waitLabel: '等待',
    switchLabel: '换模型',
  };
}

/** 卡片 DOM 查找（同层级同模型至多一张）。 */
export function findLlmFailureCard(root, key) {
  if (!root || !key) return null;
  return Array.from(root.querySelectorAll('.llm-failure-card')).find((el) => el.dataset.cardKey === key) || null;
}

/** 切换成功后脚注停留多久再淡出（毫秒）。下次该组成功（llmFailureRecovered）仍会即时清除。 */
export const SWITCH_FADE_MS = 4000;
/** 淡出动画时长（毫秒，须与 css `.llm-failure-card.is-fading` 的 transition 一致）。 */
export const FADE_OUT_MS = 300;

/**
 * 点按是否落在「控件区」（按钮 / 下拉 / 关闭钮 / 动作行）。控件区点击不触发正文点按消失。
 * @param {{closest?: (sel: string) => any}} target 事件目标（DOM 元素；无 closest 视为非控件）
 */
export function isLlmFailureControlTarget(target) {
  return !!(target && typeof target.closest === 'function'
    && target.closest('.llm-failure-actions, .llm-failure-close, button, select, option'));
}

/**
 * 已消失（用户点按消失 / 关闭钮）的组键集合——同组在本次失败期内不再复出；
 * llmFailureRecovered 收到该组后清除（下次真正失败才会再出卡）。纯内存口径，与卡片本身一致（不落盘）。
 */
export function createLlmDismissStore() {
  const keys = new Set();
  return {
    has: (key) => keys.has(key),
    add: (key) => { if (key) keys.add(key); },
    clear: (key) => { keys.delete(key); },
    size: () => keys.size,
  };
}

/** 生成一个 lucide 图标占位（由挂载后的 createIconsIn 替换为 SVG）。 */
function iconEl(name, cls) {
  const i = document.createElement('i');
  i.setAttribute('data-lucide', name);
  i.className = cls ? `llm-failure-ico ${cls}` : 'llm-failure-ico';
  i.setAttribute('aria-hidden', 'true');
  return i;
}

/**
 * 渲染卡片。结构（大字在上，细节在下）：
 *   标题（大字 .option-q：图标 + 「模型 调用失败」）
 *   元信息行（.perm-reason：风险档 L# · 层级图标+标签 · 分类）
 *   代理行（小字：受影响代理名）
 *   摘要（小字，单行省略）
 *   按钮行（等待 · 模型下拉 + 换模型）
 *   状态（小字：自动重试中 / 结果提示）
 * 消失途径（作者 23:24 报：点了不消失）：
 *   · 正文区点按 ⇒ 消失（控件区不触发）；
 *   · 标题行右侧关闭钮 ⇒ 消失（显式兜底）；
 *   · 换模型成功 ⇒ 脚注「已切换」后 SWITCH_FADE_MS 自动淡出；下次该组成功（llmFailureRecovered）即时清除。
 * 每种消失都经 deps.onDismiss(key) 通知宿主，宿主记入 dismiss store，同组不再复出。
 * @param {object} msg llmFailure 组快照帧
 * @param {{saveModel: (agent: string, modelRef: string) => Promise<any>, modelRefs: () => string[], onDismiss?: (key: string) => void}} deps
 * @returns {HTMLElement}
 */
export function renderLlmFailureCard(msg, deps) {
  const m = buildLlmFailureModel(msg);
  const box = document.createElement('div');
  box.className = 'option-box llm-failure-card';
  box.dataset.cardType = 'llmFailure';
  box.dataset.cardKey = m.key;
  box.dataset.layer = m.layer;
  box.dataset.model = m.modelRef;

  let gone = false;
  const dismiss = () => {
    if (gone) return;
    gone = true;
    if (deps && typeof deps.onDismiss === 'function') deps.onDismiss(m.key);
    box.remove();
  };
  const fadeOut = () => {
    if (gone) return;
    gone = true;
    if (deps && typeof deps.onDismiss === 'function') deps.onDismiss(m.key);
    box.classList.add('is-fading');
    setTimeout(() => box.remove(), FADE_OUT_MS);
  };

  // 正文区点按 ⇒ 消失；控件区（按钮/下拉/关闭钮/动作行）点击不触发。
  box.addEventListener('click', (e) => {
    if (isLlmFailureControlTarget(e.target)) return;
    dismiss();
  });

  // 标题：大字主信息（同 .option-q）+ 右侧关闭钮。
  const q = document.createElement('div');
  q.className = 'option-q llm-failure-title';
  q.append(iconEl('triangle-alert', 'llm-failure-ico-title'), document.createTextNode(m.title));
  const bClose = document.createElement('button');
  bClose.type = 'button';
  bClose.className = 'llm-failure-close';
  bClose.setAttribute('aria-label', '关闭');
  bClose.title = '关闭';
  bClose.append(iconEl('x'));
  bClose.onclick = (e) => { e.stopPropagation(); dismiss(); };
  q.append(bClose);

  // 元信息：风险档徽标 + 层级 + 分类（小字，同 .perm-reason 语言）。
  const reason = document.createElement('div');
  reason.className = 'perm-reason llm-failure-reason';
  const badge = document.createElement('span');
  badge.className = `perm-risk-badge lv-${m.level}`;
  badge.textContent = `L${m.level}`;
  badge.title = `风险档 L${m.level}`;
  const layer = document.createElement('span');
  layer.className = 'llm-failure-layer';
  layer.append(iconEl(m.layerIcon), document.createTextNode(m.layerLabel));
  const label = document.createElement('span');
  label.className = 'perm-reason-label';
  label.textContent = m.failureLabel;
  reason.append(badge, layer, label);

  // 代理行（小字）。
  const agents = document.createElement('div');
  agents.className = 'llm-failure-agents';
  agents.hidden = !m.agentsText;
  agents.append(iconEl('users'), document.createTextNode(m.agentsText));

  // 摘要（小字，单行省略）。
  const sum = document.createElement('div');
  sum.className = 'llm-failure-summary';
  sum.textContent = m.summary;

  // 状态行（小字）。
  const status = document.createElement('div');
  status.className = 'llm-failure-status';
  status.append(iconEl('loader-2', 'llm-failure-spin'), document.createTextNode('自动重试中'));
  const setStatus = (icon, text) => {
    status.textContent = '';
    status.append(iconEl(icon, icon === 'loader-2' ? 'llm-failure-spin' : ''), document.createTextNode(text));
  };

  // 按钮行：等待（文字次级）｜ 模型下拉 + 换模型（主行动）。
  const row = document.createElement('div');
  row.className = 'option-btn-row llm-failure-actions';

  const bWait = document.createElement('button');
  bWait.type = 'button';
  bWait.className = 'option-cancel';
  bWait.append(iconEl('hourglass'), document.createTextNode(m.waitLabel));
  bWait.onclick = () => setStatus('hourglass', '已选择等待，引擎继续自动重试');

  const right = document.createElement('div');
  right.className = 'llm-failure-switch';
  const pick = document.createElement('select');
  pick.className = 'llm-failure-pick';
  pick.setAttribute('aria-label', m.switchLabel);
  const refs = (deps && deps.modelRefs && deps.modelRefs()) || [];
  refs.forEach((r) => {
    const opt = document.createElement('option');
    opt.value = r;
    opt.textContent = r;
    pick.appendChild(opt);
  });
  const bSwitch = document.createElement('button');
  bSwitch.type = 'button';
  bSwitch.className = 'option-confirm';
  bSwitch.append(iconEl('repeat'), document.createTextNode(m.switchLabel));
  bSwitch.onclick = async () => {
    if (!pick.value || !deps || typeof deps.saveModel !== 'function') return;
    bSwitch.disabled = true;
    try {
      // 同一层级同一模型的所有代理一并切换（组级语义）。
      const targets = m.agents.length ? m.agents : [''];
      for (const a of targets) await deps.saveModel(a, pick.value);
      setStatus('check', `已切换为 ${pick.value}`);
    } catch (e) {
      bSwitch.disabled = false;
      setStatus('triangle-alert', `切换失败：${e && e.message ? e.message : e}`);
      return;
    }
    bSwitch.disabled = false;
    // 切换成功：脚注出现后短暂停留再淡出（下次该组成功仍会即时清除）。
    box.classList.add('is-switched');
    setTimeout(fadeOut, SWITCH_FADE_MS);
  };
  right.append(pick, bSwitch);

  row.append(bWait, right);
  box.append(q, reason, agents, sum, row, status);
  return box;
}
