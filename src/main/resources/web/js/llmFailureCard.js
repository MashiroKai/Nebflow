// LLM 失败卡片（卡片批 2026-11-21）：非阻塞。
// 出卡 = 后端 llmFailure 帧；收卡 = 后端 llmFailureRecovered 帧（同 agent 下一次成功调用）。
// 「等待恢复」不发任何答复（引擎自己退避续跑）；「换模型」经注入的 saveModel（= modelPanel
// 的 saveSlotPreferredModel，与设置页同一写入口）落盘。纯视图模型与 DOM 渲染分离，便于 node 测试。

/** 失败分类的中文标签（与后端 FailureClass 取值对齐；未知值原样显示）。 */
const FAILURE_LABELS = {
  'llm-transient': '模型临时故障',
  'llm-overloaded': '模型过载',
  'llm-ratelimit': '限流',
  'llm-auth': '鉴权失败',
  'llm-timeout': '超时',
  'llm-network': '网络不可达',
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
  return {
    agent,
    title: `模型调用失败 · ${agent || '未知代理'}`,
    failureLabel: FAILURE_LABELS[cls] || cls || '未分类',
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
 * 渲染卡片。
 * @param {object} msg llmFailure 帧
 * @param {{saveModel: (agent: string, modelRef: string) => Promise<any>, modelRefs: () => string[]}} deps
 *   saveModel：同源写入口；modelRefs：候选模型（与模型面板 getAllModelRefs 同源）。
 * @returns {HTMLElement}
 */
export function renderLlmFailureCard(msg, deps) {
  const m = buildLlmFailureModel(msg);
  const host = document.createElement('div');
  host.className = 'llm-failure-card';
  host.dataset.cardType = 'llmFailure';
  host.dataset.agent = m.agent;

  const head = document.createElement('div');
  head.className = 'llm-failure-head';
  head.textContent = m.title;

  const body = document.createElement('div');
  body.className = 'llm-failure-body';
  const cls = document.createElement('div');
  cls.className = 'llm-failure-row';
  cls.textContent = `失败分类：${m.failureLabel}`;
  const sum = document.createElement('div');
  sum.className = 'llm-failure-row llm-failure-detail';
  sum.textContent = m.summary;
  body.append(cls, sum);

  const acts = document.createElement('div');
  acts.className = 'llm-failure-actions';
  const status = document.createElement('span');
  status.className = 'llm-failure-status';
  status.textContent = '引擎自动重试中，恢复后本卡自动收起';

  const bWait = document.createElement('button');
  bWait.type = 'button';
  bWait.textContent = m.waitLabel;
  bWait.onclick = () => {
    status.textContent = '已选择等待恢复，引擎将继续自动重试';
  };

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
  };

  acts.append(bWait, pick, bSwitch, status);
  host.append(head, body, acts);
  return host;
}
