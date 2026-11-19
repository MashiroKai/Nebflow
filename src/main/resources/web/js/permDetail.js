// permDetail.js — 审批卡三要素（命令正文 / 为什么危险 / 作用域）的渲染单点。
//
// 报障（作者 2026-11-19 17:30 实拍）：控制台的 delegate 审批卡只有
// 「ⓘ 危险操作：」+「↗ 来自: stand (delegate-neb)」+「允许 Bash？」——
//   ① 要执行什么命令：没有；
//   ② 为什么算危险：没有（后端明明判了 2/3 档、命中哪条规则也有，只是没下发）；
//   ③ 作用域是哪台机器哪个目录：没有。
// 用户只能盲批。本模块把引擎已经算好的事实**照原样**渲染出来，**不做任何猜测**：
//   · 正文/理由/作用域一律取 `msg.permDetail`（后端 PermissionCardDetail 投影）；
//   · 缺席（旧后端帧 / 旧历史行）时只从 input 派生**命令正文**（那是工具参数本身），
//     理由与作用域宁可整块不渲染 —— 猜出来的「危险原因」比没有更糟。
//
// 折叠纪律：默认 6 行；超长**不静默截断** —— 全文都在 DOM 里，只是被 CSS 框住，
// 「展开全部（共 N 行）/ 收起」是真开关（键盘可达）。

/** 折叠态默认可见行数（CSS 侧 `--perm-cmd-lines` 的唯一写点）。 */
export const PERM_CMD_FOLD_LINES = 6;

/**
 * 工具参数归一化：live 帧里 `input` 是**对象**，落盘历史行里是**字符串**。
 * 改前 live 腿对对象调 JSON.parse ⇒ SyntaxError ⇒ detail 恒为空 ——
 * 这正是「危险操作：」后面空着、命令正文从不出现的直接成因。
 * @param {unknown} raw
 * @returns {Record<string, unknown>|null}
 */
export function normalizeToolInput(raw) {
  if (!raw) return null;
  if (typeof raw === 'object') return /** @type {Record<string, unknown>} */ (raw);
  if (typeof raw !== 'string') return null;
  try {
    const once = JSON.parse(raw);
    if (once && typeof once === 'object') return once;
    // 双编码（帧里是 JSON 字符串的 JSON）——历史行/旧生产者形态
    if (typeof once === 'string') {
      const twice = JSON.parse(once);
      if (twice && typeof twice === 'object') return twice;
    }
  } catch { /* 非 JSON ⇒ 无参数可读 */ }
  return null;
}

/** 旧路径兜底：从工具参数取出「要执行的命令」正文（逐字保留换行）。 */
export function commandTextFromInput(toolName, input) {
  if (!input) return '';
  if (typeof input.command === 'string' && input.command) return input.command;
  if (typeof input.url === 'string' && input.url) {
    const m = typeof input.method === 'string' ? input.method.toUpperCase() : 'GET';
    return m + ' ' + input.url;
  }
  for (const k of ['file_path', 'path', 'target', 'notebook_path']) {
    if (typeof input[k] === 'string' && input[k]) return input[k];
  }
  try { return JSON.stringify(input, null, 2); } catch { return ''; }
}

/**
 * 卡片三要素的来源解析（**只读**，零判断）。
 * @param {{ toolName?: string, input?: unknown, permDetail?: any }} msg
 * @returns {{ tool: string, command: string, reason: null|{gate:string,ruleId:string,label:string,level:number,levelRuleId:string,levelLabel:string,safetyMode:string}, scope: null|{device:string,cwd:string,target:string,remoteExec:boolean} }}
 */
export function permDetailFrom(msg) {
  const tool = (msg && msg.toolName) || '';
  const d = msg && msg.permDetail;
  if (d && typeof d === 'object' && !Array.isArray(d)) {
    return {
      tool: typeof d.tool === 'string' && d.tool ? d.tool : tool,
      command: typeof d.command === 'string' ? d.command : '',
      reason: d.reason && typeof d.reason === 'object' ? d.reason : null,
      scope: d.scope && typeof d.scope === 'object' ? d.scope : null
    };
  }
  return { tool, command: commandTextFromInput(tool, normalizeToolInput(msg && msg.input)), reason: null, scope: null };
}

/** 该工具是否是「执行命令」类（决定正文标签用词）。 */
function commandLabel(t) {
  return t('perm.detail.command');
}

/**
 * 渲染三要素块到宿主气泡里（DOM 构建，零 innerHTML）。
 * @param {HTMLElement} host
 * @param {ReturnType<typeof permDetailFrom>} detail
 * @param {(key: string, params?: Record<string, string|number>) => string} t
 */
export function renderPermDetail(host, detail, t) {
  if (!host || !detail) return null;
  const hasCommand = typeof detail.command === 'string' && detail.command.trim() !== '';
  const reason = detail.reason;
  const scope = detail.scope;
  if (!hasCommand && !reason && !scope) return null;

  const root = document.createElement('div');
  root.className = 'perm-detail';

  // ① 命令正文（等宽、保留换行、默认 6 行、可展开全部）
  if (hasCommand) {
    const block = document.createElement('div');
    block.className = 'perm-detail-block perm-detail-command';
    const label = document.createElement('div');
    label.className = 'perm-detail-label';
    label.textContent = commandLabel(t) + ' · ' + (detail.tool || '');
    block.appendChild(label);

    const pre = document.createElement('pre');
    pre.className = 'perm-cmd';
    pre.textContent = detail.command;
    pre.tabIndex = 0;
    block.appendChild(pre);

    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'perm-cmd-toggle';
    toggle.hidden = true;
    block.appendChild(toggle);
    const lines = detail.command.split('\n').length;
    const setExpanded = (on) => {
      // 行数上限完全在 CSS（`--perm-cmd-lines`），JS 只翻类名 ——
      // 折叠/展开不写第二份尺寸。
      pre.classList.toggle('expanded', on);
      toggle.textContent = on ? t('perm.detail.collapse') : t('perm.detail.expand', { lines });
      toggle.setAttribute('aria-expanded', on ? 'true' : 'false');
    };
    toggle.addEventListener('click', () => setExpanded(!pre.classList.contains('expanded')));
    setExpanded(false);
    // 只有真的溢出才给开关（不溢出的命令不给死键）；rAF 等布局落定，
    // 宿主不可见（非活动会话）时 scrollHeight 恒 0 ⇒ 保守地按行数判定。
    const decide = () => {
      const overflowing = pre.scrollHeight > pre.clientHeight + 1;
      toggle.hidden = !(overflowing || lines > PERM_CMD_FOLD_LINES);
    };
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(decide);
    else decide();
    root.appendChild(block);
  }

  // ② 为什么危险（档位 + 命中规则 + 闸名，全部来自后端判定）
  if (reason) {
    const block = document.createElement('div');
    block.className = 'perm-detail-block perm-detail-reason';
    const label = document.createElement('div');
    label.className = 'perm-detail-label';
    label.textContent = t('perm.detail.reason');
    block.appendChild(label);

    const text = document.createElement('div');
    text.className = 'perm-reason';
    const labelText = typeof reason.label === 'string' ? reason.label : '';
    const ruleId = typeof reason.ruleId === 'string' ? reason.ruleId : '';
    const gate = typeof reason.gate === 'string' ? reason.gate : '';
    const level = Number(reason.level) || 0;
    const parts = [labelText || t('perm.detail.noRule')];
    text.textContent = parts.join(' · ');
    block.appendChild(text);

    const meta = document.createElement('div');
    meta.className = 'perm-reason-meta';
    if (level > 0) {
      const lv = document.createElement('span');
      lv.className = 'perm-risk-badge lv-' + level;
      const lvLabel = typeof reason.levelLabel === 'string' && reason.levelLabel ? reason.levelLabel : '';
      lv.textContent = t('perm.detail.level', { level }) + (lvLabel ? ' · ' + lvLabel : '');
      meta.appendChild(lv);
    }
    if (gate) {
      const g = document.createElement('span');
      g.className = 'perm-reason-gate';
      g.textContent = t('perm.detail.gate') + ' ' + gate;
      meta.appendChild(g);
    }
    if (ruleId) {
      const r = document.createElement('span');
      r.className = 'perm-reason-rule';
      r.textContent = t('perm.detail.rule') + ' ' + ruleId;
      meta.appendChild(r);
    }
    block.appendChild(meta);
    root.appendChild(block);
  }

  // ③ 作用域（设备 / 工作目录 / 目标路径）
  if (scope) {
    const block = document.createElement('div');
    block.className = 'perm-detail-block perm-detail-scope';
    const label = document.createElement('div');
    label.className = 'perm-detail-label';
    label.textContent = t('perm.detail.scope');
    block.appendChild(label);
    const rows = [];
    if (typeof scope.device === 'string' && scope.device) {
      rows.push(['perm.detail.device', scope.device + (scope.remoteExec ? ' ' + t('perm.detail.remoteExec') : '')]);
    }
    if (typeof scope.cwd === 'string' && scope.cwd) rows.push(['perm.detail.cwd', scope.cwd]);
    if (typeof scope.target === 'string' && scope.target) rows.push(['perm.detail.target', scope.target]);
    for (const [k, v] of rows) {
      const row = document.createElement('div');
      row.className = 'perm-scope-row';
      const kEl = document.createElement('span');
      kEl.className = 'perm-scope-key';
      kEl.textContent = t(k);
      const vEl = document.createElement('code');
      vEl.className = 'perm-scope-val';
      vEl.textContent = v;
      row.appendChild(kEl);
      row.appendChild(vEl);
      block.appendChild(row);
    }
    root.appendChild(block);
  }

  if (!root.firstChild) return null;
  if (!host.contains(root)) host.appendChild(root);
  return root;
}
