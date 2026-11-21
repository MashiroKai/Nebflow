// LLM 失败卡片（卡片批 2026-11-21；风格重做 2026-11-21，作者裁定「视觉品质向 askuser 卡看齐」）。
// 非阻塞。出卡 = 后端 llmFailure 帧；收卡 = 后端 llmFailureRecovered 帧（同 agent 下一次成功调用）。
// 视觉语言逐项照 askuser 卡（chat.css）：卡面 `.option-box`（渐变 --ask-card-top/bot、描边 --ask-card-bd、
// 双层阴影 --ask-card-shadow + 内高光 --ask-card-highlight、圆角 16）；问句 `.option-q`（标题字号 600）；
// 理由行 `.perm-reason` + `.perm-risk-badge`（权限卡同款徽标）；按钮行 `.option-btn-row`：
// 「换模型」= `.option-confirm`（L3 主行动，绿实底渐变 + 绿影 + hover 亮度），
// 「等待恢复」= `.option-cancel`（纯文字次级，同 askuser 取消钮语言）。
// 「换模型」经注入的 saveModel（= modelPanel.saveSlotPreferredModel，同一写入口）落盘；
// 「等待恢复」不发任何答复（引擎自己退避续跑）。纯视图模型与 DOM 渲染分离，便于 node 测试。

/** 失败分类 → 中文标签 + 风险档（徽标颜色类 lv-1 / lv-2，同权限卡）。 */
const FAILURE_CLASS = {
  'llm-transient': { label: '模型临时故障', level: 2 },
  'llm-overloaded': { label: '模型过载', level: 1 },
  'llm-ratelimit': { label: '限流', level: 1 },
  'llm-auth': { label: '鉴权失败', level: 2 },
  'llm-timeout': { label: '超时', level: 1 },
  'llm-network': { label: '网络不可达', level: 2 },
};

/** 错误摘要截断上限（卡片只给摘要，完整原文在面板错误帧与日志里）。 */
export const SUMMARY_MAX = 160;

/**
 * 纯视图模型：把 llmFailure 帧映射为卡片展示字段（无 DOM）。
 * @param {{agentName?: string, summary?: string, failureClass?: string}} msg
 */
export function buildLlmFailureModel(msg) {
  const agent = String((msg && msg.agentName) || '');
  const raw = String((msg && msg.summary) || '').replace(/\s+/g, ' ').trim();
  const summary = raw.length > SUMMARY_MAX ? raw.slice(0, SUMMARY_MAX - 1) + '…' : raw;
  const cls = String((msg && msg.failureClass) || '');
  const meta = FAILURE_CLASS[cls] || { label: cls || '未分类', level: 1 };
  return {
    agent,
    question: `模型调用失败：${agent || '未知代理'}`,
    failureLabel: meta.label,
    level: meta.level,
    summary: summary || '（无错误摘要）',
    waitLabel: '等待恢复',
    switchLabel: '换模型',
  };
}

/** 卡片 DOM 查找（同 agent 至多一张）。 */
export function findLlmFailureCard(root, agent) {
  if (!root || !agent) return null;
  return Array.from(root.querySelectorAll('.llm-failure-card')).find((el) => el.dataset.agent === agent) || null;
}

/**
 * 渲染卡片（结构：.option-box 卡盒 → 理由行 → 摘要 → 问句 → 状态 → 按钮行）。
 * @param {object} msg llmFailure 帧
 * @param {{saveModel: (agent: string, modelRef: string) => Promise<any>, modelRefs: () => string[]}} deps
 * @returns {HTMLElement}
 */
export function renderLlmFailureCard(msg, deps) {
  const m = buildLlmFailureModel(msg);
  const box = document.createElement('div');
  box.className = 'option-box llm-failure-card';
  box.dataset.cardType = 'llmFailure';
  box.dataset.agent = m.agent;

  // 理由行（同 .perm-reason）：风险档徽标 + 分类名。
  const reason = document.createElement('div');
  reason.className = 'perm-reason llm-failure-reason';
  const badge = document.createElement('span');
  badge.className = `perm-risk-badge lv-${m.level}`;
  badge.textContent = `风险档 L${m.level}`;
  const label = document.createElement('span');
  label.className = 'perm-reason-label';
  label.textContent = m.failureLabel;
  reason.append(badge, label);

  // 问句（同 .option-q）：卡的主标题。
  const q = document.createElement('div');
  q.className = 'option-q';
  q.textContent = m.question;

  // 摘要（同权限卡次要字色）。
  const sum = document.createElement('div');
  sum.className = 'llm-failure-summary';
  sum.textContent = m.summary;

  // 状态行（同 .perm-reason-gate 次要字色）。
  const status = document.createElement('div');
  status.className = 'llm-failure-status';
  status.textContent = '引擎自动重试中，恢复后本卡自动收起';

  // 按钮行（同 .option-btn-row：左右分布，取消=文字次级，确认=主行动）。
  const row = document.createElement('div');
  row.className = 'option-btn-row llm-failure-actions';

  const bWait = document.createElement('button');
  bWait.type = 'button';
  bWait.className = 'option-cancel';
  bWait.textContent = m.waitLabel;
  bWait.onclick = () => {
    status.textContent = '已选择等待恢复，引擎将继续自动重试';
  };

  const right = document.createElement('div');
  right.className = 'llm-failure-switch';
  const pick = document.createElement('select');
  pick.className = 'llm-failure-pick';
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
  bSwitch.textContent = m.switchLabel;
  bSwitch.onclick = async () => {
    if (!pick.value || !deps || typeof deps.saveModel !== 'function') return;
    bSwitch.disabled = true;
    try {
      await deps.saveModel(m.agent, pick.value);
      status.textContent = `已切换为 ${pick.value}，等待恢复`;
    } catch (e) {
      bSwitch.disabled = false;
      status.textContent = `切换失败：${e && e.message ? e.message : e}`;
    }
    bSwitch.disabled = false;
  };
  right.append(pick, bSwitch);

  row.append(bWait, right);
  box.append(reason, q, sum, row, status);
  return box;
}
