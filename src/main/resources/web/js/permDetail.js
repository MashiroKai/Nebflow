// permDetail.js — 审批卡的**唯一**事实渲染点（危险类型 / 完整调用信息 / 作用域）。
//
// 报障（作者 2026-11-19 17:30 实拍）：控制台的 delegate 审批卡只有
// 「ⓘ 危险操作：」+「↗ 来自: stand (delegate-neb)」+「允许 Bash？」——
//   ① 要执行什么命令：没有；
//   ② 为什么算危险：没有（后端明明判了 2/3 档、命中哪条规则也有，只是没下发）；
//   ③ 作用域是哪台机器哪个目录：没有。
// 用户只能盲批。本模块把引擎已经算好的事实**照原样**渲染出来，**不做任何猜测**：
//   · 正文/理由/作用域一律取 `msg.permDetail`（后端 PermissionCardDetail 投影）；
//   · 缺席（旧后端帧 / 旧历史行）时只从 input 派生**调用正文**（那是工具参数本身），
//     理由与作用域宁可整块不渲染 —— 猜出来的「危险原因」比没有更糟。
//
// 瘦身批（作者 2026-11-21 追加令，逐字两条）：
//   ①「简化为只显示**完整调用信息 + 危险类型**，保留关键信息（工具名、参数实质内容、
//      危险等级标记、允许/拒绝操作），去掉冗余说明与装饰性段落」；
//   ②「**信息不要重复**——同一事实（工具名/参数/目标路径等）卡内只出现一次；若现状是
//      「摘要区+完整区」重复展示同一内容，改为摘要只留危险类型与一句话要点，全文只在
//      一处。验收时专门检查重复项。」
// 因此本卡的事实分工**互斥且完整**（每条事实恰有一个落点，见 renderPermDetail 正文）：
//   危险类型（档位 + 一句话要点） → 卡首横幅（chat.js 生产；本模块只在无横幅时补一行）
//   完整调用信息                  → `.perm-cmd` 一处（本模块生产，全文在 DOM、可展开）
//   作用域（设备/工作目录/目标路径）→ `.perm-scope` 一行（本模块生产，各一次）
//   判定出处（闸名 + 规则 id）     → `.perm-reason-meta` 次要字色尾注（本模块生产）
// 改前被删掉的**重复项**（逐条对应作者第二令）：
//   · 档位写了两次（横幅「危险操作」+ `.perm-risk-badge` 的「风险档 L2」）；
//   · 命中的规则标签写了两次（横幅 `{detail}` + `.perm-reason-label`）；
//   · 目标路径写了 2–3 次（`permDetail.command` 首行 + 同一 command 里的 JSON
//     `"file_path"` + `.perm-scope` 的目标路径 + `permissionDetails.js` 再印一遍）；
//   · Write 正文写了两次（`permission-payload-text` 预览 8 行 + `<details>` 全文）。
// 现改：`permissionDetails.js`（前端第二份「完整区」）整件退役 —— 调用正文只由本模块
// 的 `callBodyOf` 产出一次（Bash/Curl = 命令行原文；Write = 要写入的内容；Edit = -/+ 行；
// 其余 = 参数 JSON），目标路径只由作用域行给出。
//
// 版式（视觉返工批 2026-11-19 19:38，作者逐字「太丑了这也，要在原权限卡的基础上
// 进行优化，然后外部的大方框去掉」）：事实**内联在原卡的内容流里**——
//   · 不新增外层容器/边框/背景（上一版把三块装进一个带框的新容器 ⇒ 框里套框）；
//   · 调用正文沿用 `.bubble.ai pre` 既有的淡底 code 块语言（无边框）；
//   · 作用域压成**一行**轻量键值（设备 / 工作目录 / 目标路径），不做盒子。
// 折叠纪律：默认 6 行；超长**不静默截断** —— 全文都在 DOM 里，只是被 CSS 框住，
// 「展开全部（共 N 行）/ 收起」是真开关（键盘可达）。

/** 折叠态默认可见行数（CSS 侧 `--perm-cmd-lines` 的唯一写点）。 */
export const PERM_CMD_FOLD_LINES = 6;

/** 后端 `targetPathOf` 认的路径键（作用域行给一次 ⇒ 正文块不再重复印）。 */
const PATH_KEYS = ['file_path', 'path', 'target', 'notebook_path'];

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
 * 卡片事实的来源解析（**只读**，零判断）。
 * @param {{ toolName?: string, input?: unknown, permDetail?: any }} msg
 * @returns {{ tool: string, command: string, input: Record<string, unknown>|null,
 *   reason: null|{gate:string,ruleId:string,label:string,level:number,levelRuleId:string,levelLabel:string,safetyMode:string},
 *   scope: null|{device:string,cwd:string,target:string,remoteExec:boolean} }}
 */
export function permDetailFrom(msg) {
  const tool = (msg && msg.toolName) || '';
  const d = msg && msg.permDetail;
  const normalized = normalizeToolInput(msg && msg.input);
  if (d && typeof d === 'object' && !Array.isArray(d)) {
    return {
      tool: typeof d.tool === 'string' && d.tool ? d.tool : tool,
      command: typeof d.command === 'string' ? d.command : '',
      input: normalized,
      reason: d.reason && typeof d.reason === 'object' ? d.reason : null,
      scope: d.scope && typeof d.scope === 'object' ? d.scope : null
    };
  }
  return { tool, command: commandTextFromInput(tool, normalized), input: normalized, reason: null, scope: null };
}

/**
 * **完整调用信息**（`<pre class="perm-cmd">` 的唯一内容源）：这次调用实际会做什么。
 * 同一条事实在卡里只出现一次 —— 目标路径归作用域行，这里给正文：
 *   · Bash / Curl → 引擎投影的正文（命令行原文 / `METHOD url`，逐字）
 *   · Write      → 要写入的内容原文（不是 JSON 转义串，也不是「预览 + 全文」两份）
 *   · Edit       → 请求载荷的 `-旧行 / +新行`（`replace_all` 就一行附注）
 *   · 其余       → 参数 JSON（路径键剥掉 —— 作用域行已经给了一次）
 * @param {string} toolName
 * @param {Record<string, unknown>|null} input
 * @param {{ command?: string, scope?: any }} [detail]
 * @returns {{ text: string, note: string }}
 */
export function callBodyOf(toolName, input, detail) {
  const name = String(toolName || (detail && detail.tool) || '');
  const raw = detail && typeof detail.command === 'string' ? detail.command : '';
  const obj = input || null;
  if (name === 'Bash' || name === 'Curl') {
    const cmd = obj && typeof obj.command === 'string' && obj.command ? obj.command : raw;
    return { text: raw || cmd, note: '' };
  }
  if (name === 'Write') {
    const content = obj && typeof obj.content === 'string' ? obj.content : '';
    return { text: content, note: '' };
  }
  if (name === 'Edit') {
    const oldS = obj && obj.old_string != null ? String(obj.old_string) : '';
    const newS = obj && obj.new_string != null ? String(obj.new_string) : '';
    if (oldS || newS) {
      const withPrefix = (v, p) => v.split('\n').map((line) => p + line).join('\n');
      const lines = [withPrefix(oldS, '-'), withPrefix(newS, '+')];
      if (obj && obj.replace_all) lines.push('replace_all: true');
      return { text: lines.join('\n'), note: 'chat.permRequestNotDiff' };
    }
  }
  // 其余工具（含 Write/Edit 缺参数时的兜底）：参数 JSON，路径键剥掉（作用域行已给一次）。
  if (obj) {
    const rest = {};
    for (const [k, v] of Object.entries(obj)) {
      if (PATH_KEYS.includes(k) && typeof v === 'string' && v) continue;
      rest[k] = v;
    }
    try {
      const text = JSON.stringify(Object.keys(rest).length ? rest : obj, null, 2);
      if (text && text !== '{}') return { text, note: '' };
    } catch { /* 不可序列化 ⇒ 无正文 */ }
  }
  return { text: '', note: '' };
}

/**
 * 渲染卡片事实（DOM 构建，零 innerHTML），挂在 `host` 的内容流里。
 *
 * `host` = 原权限卡的内容面（`.option-box`）——事实直接成为卡的前几行，
 * 不再是卡外的一个新框。`opts.before` 给出插入锚点（卡里的问句行），
 * 缺省追加到末尾。
 *
 * 互斥分工（每条事实恰一处）：横幅已经承载的档位/要点不再复述 —— 两个判据由
 * **生产横幅的那一处**（chat.js / persistence.js）如实传入，本模块不猜：
 *   `opts.dangerShown` 档位（「危险操作」等文字 + 语义色底）已在横幅上
 *   `opts.labelShown`  命中的规则人话标签已在横幅上（横幅文案含 `{detail}` 时）
 *
 * @param {HTMLElement} host
 * @param {ReturnType<typeof permDetailFrom>} detail
 * @param {(key: string, params?: Record<string, string|number>) => string} t
 * @param {{ level?: number, before?: Element|null, dangerShown?: boolean, labelShown?: boolean }} [opts]
 */
export function renderPermDetail(host, detail, t, opts = {}) {
  if (!host || !detail) return null;
  const body = callBodyOf(detail.tool, detail.input, detail);
  const hasCommand = (body.text || '').trim() !== '' || (detail.command || '').trim() !== '';
  const reason = detail.reason;
  const scope = detail.scope;
  if (!hasCommand && !reason && !scope) return null;
  // 档位取二者先到者：`reason.level` 是判定闸自己给的档（载荷里恒在），`opts.level`
  // 是帧上的 dangerLevel 投影。手机层实测只有前者（横幅因此不出现）——若只看 opts，
  // 手机上的风险徽标会凭空消失，判定就丢了一处可见性。
  const level = Number(reason && reason.level) || Number(opts.level) || 0;
  const dangerShown = opts.dangerShown === true;
  const labelShown = opts.labelShown === true;
  const before = opts.before || null;

  const frag = document.createDocumentFragment();

  // ① 完整调用信息：既有 code 块（淡底、无边框、等宽、长行横向滚动），默认 6 行可展开。
  let pre = null;
  if ((body.text || '').trim() !== '') {
    if (body.note) {
      // 唯一保留的说明性一行：Edit 的 -/+ 是**请求载荷**而非文件实际 diff ——
      // 少了它会读成「文件已经长这样」。只此一处，不重复。
      const note = document.createElement('div');
      note.className = 'perm-cmd-note';
      note.textContent = t(body.note);
      frag.appendChild(note);
    }
    pre = document.createElement('pre');
    pre.className = 'perm-cmd';
    pre.textContent = body.text;
    pre.tabIndex = 0;
    // 可见标签已去掉（作者令「内联进原卡内容流」）：语义名留给读屏。
    pre.setAttribute('aria-label', t('perm.detail.command') + (detail.tool ? ' · ' + detail.tool : ''));
    frag.appendChild(pre);

    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'perm-cmd-toggle';
    toggle.hidden = true;
    frag.appendChild(toggle);
    const lines = body.text.split('\n').length;
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
  }

  // ② 作用域：**一行**轻量键值（设备 / 工作目录 / 目标路径），不装边框盒子。
  //    目标路径**只在这里**出现一次（正文块不再重复印）。
  if (scope) {
    const row = document.createElement('div');
    row.className = 'perm-scope';
    const pairs = [];
    if (typeof scope.device === 'string' && scope.device) {
      pairs.push(['perm.detail.device', scope.device + (scope.remoteExec ? ' ' + t('perm.detail.remoteExec') : '')]);
    }
    if (typeof scope.cwd === 'string' && scope.cwd) pairs.push(['perm.detail.cwd', scope.cwd]);
    if (typeof scope.target === 'string' && scope.target) pairs.push(['perm.detail.target', scope.target]);
    for (const [k, v] of pairs) {
      const item = document.createElement('span');
      item.className = 'perm-scope-item';
      const kEl = document.createElement('span');
      kEl.className = 'perm-scope-key';
      kEl.textContent = t(k);
      const vEl = document.createElement('span');
      vEl.className = 'perm-scope-val';
      // span 而非 <code>：`.bubble.ai code` 带行内淡底，作用域值要的是「一行字」不是 chip。
      vEl.textContent = v;
      item.appendChild(kEl);
      item.appendChild(vEl);
      row.appendChild(item);
    }
    if (row.firstChild) frag.appendChild(row);
  }

  // ③ 判定出处：闸名 + 规则 id（次要字色）。**不是**「理由」—— 理由（档位 +
  //    人话标签）已经由卡首横幅承载，这里只补「这话是谁判的」，不复述结论。
  //    无横幅的腿（手机层实测 / 历史行无 dangerLevel）必须自足：档位徽标与标签
  //    就落回这一行，仍然各一次。
  if (reason) {
    const row = document.createElement('div');
    row.className = 'perm-reason';
    const labelText = typeof reason.label === 'string' ? reason.label : '';
    if (!dangerShown && level > 0) {
      const lv = document.createElement('span');
      lv.className = 'perm-risk-badge lv-' + level;
      lv.textContent = t('perm.detail.level', { level });
      row.appendChild(lv);
    }
    if (!labelShown) {
      const lb = document.createElement('span');
      lb.className = 'perm-reason-label';
      lb.textContent = labelText || t('perm.detail.noRule');
      row.appendChild(lb);
    }
    const ruleId = typeof reason.ruleId === 'string' ? reason.ruleId : '';
    const gate = typeof reason.gate === 'string' ? reason.gate : '';
    if (gate || ruleId) {
      // 闸名 + 规则 id 是「出处」而不是「理由」：降为次要字色，**同一组**贴着标签
      // 排（窄屏整组折到次行），不各占一块。
      const meta = document.createElement('span');
      meta.className = 'perm-reason-meta';
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
      row.appendChild(meta);
    }
    if (row.firstChild) frag.appendChild(row);
  }

  if (!frag.firstChild) return null;
  if (before && before.parentNode === host) host.insertBefore(frag, before);
  else host.appendChild(frag);
  return frag;
}
