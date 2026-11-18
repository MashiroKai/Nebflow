// askTurnState.js — Canvas Ask 回合状态与失败的**唯一判定点**（单点 + 投影）
//
// 作者令（2026-11-18 逐字）：「然后还有 Canvas Ask 对话框的状态显示不足 我不知道
// 它在干嘛 是否正常 LLM 失败也没有报错等 需要补充更多的信息」。
//
// ── 为什么要有这个模块（病根，改前实测）──────────────────────────────────
// 改前 ask 这条链上**只有一枚布尔** `session.busy`（js/askDock.js 的
// armTurn/settleTurn），判定散在三处、呈现散在两处：
//   · 「在飞/不在飞」= s.busy（armTurn/settleTurn 各写一次）；
//   · 「失败」= consumeFrame 的 `case 'error'` 直接把文案塞进助手气泡；
//   · 「超时」= 180s 静默看门（askDock.js `setInterval`）**只还按钮，零痕迹**；
//   · 「没连上」= sendWs() 返回 false 被调用方**丢弃**（返回值当场扔掉）；
//   · 「服务端拒答」= `askError` 帧进了 consumeFrame 的 `default` 分支
//     **静默消费**（switch 里根本没有这个 case）。
// ⇒ UI 只能看见「有/无 spinner」，看不出「在正常干活」还是「出问题了」，
//   而失败里最要命的几类（超时 / 未连接 / 服务端拒答）**一个字都不显示**。
//
// ── 这个模块是什么（单点边界）────────────────────────────────────────────
// 只做两件事，都对外**唯一**：
//   ① `askTurnState(input)` —— 事实 → **状态 id** 的判定（唯一一张次序表
//      PRIORITY；任何别处再判一次状态 = 第二套，见 scripts/check-ask-status.mjs）；
//   ② `askStateView(state, ctx)` —— 状态 id → **呈现投影**（文案 / 观感档 /
//      进度 / 诊断摘要；唯一一张 STATE_ROW 表）。
// 失败分类同样是单点：`classifyAskFailure()`（唯一一张分类规则）+
// `redactDiagnostics()`（凭据出不了这条线）。
//
// ── 形态纪律（为什么是这个形态）──────────────────────────────────────────
// 与 js/freezeState.js / js/bubbleSide.js / js/termWords.js 同族：**零 import、
// 零 DOM、纯函数**——消费方（js/askDock.js）只把结论拿去渲染，本模块不知道
// 任何元素、任何全局。冻结态那批的门禁（check-freeze-state.mjs）用
// 「剥 export 后 new Function」真跑本类模块，本模块同样必须保持可求值：
// **一旦有人往这里塞 import / document / localStorage，门禁立刻红**。
//
// ── 状态清单（11 态，覆盖作者令点名的全部处境）───────────────────────────
//   idle      空闲        —— 无草稿、无在飞回合（唯一**静默**态：无可报之事）
//   draft     待发送      —— 输入条里有草稿、尚无在飞回合
//   queued    已提交排队  —— 已提交，帧未送达（连接在，等补发）
//   waiting   等待响应    —— 已送达，等首个 token
//   streaming 正在生成    —— 已有内容产出（带进度/耗时/已产出量）
//   done      已完成      —— 终态（短驻留后回 idle）
//   aborted   被用户中止  —— **不是失败**（不弹错、不落错误样式）
//   offline   会话未连接  —— 前置不可用（WS 断开 / 帧入队等待重连）
//   busy      会话忙碌    —— 前置不可用（上一回合还在飞）
//   failed    请求失败    —— 带分类（见 ASK_FAILURE）
//   timeout   超时        —— 静默看门到点（改前这条**零痕迹**）
//
// 投影给出两类观感（作者令点名的区分）：正常干活 = working（queued/waiting/
// streaming）；出问题了 = blocked / error（offline/busy；failed/timeout）。

/**
 * 状态 id 闭集（**唯一**一份；别处再写一份字面量 = 第二套判定，门禁红）。
 * @type {Readonly<Record<string, string>>}
 */
export const ASK_STATE = Object.freeze({
  IDLE: 'idle',
  DRAFT: 'draft',
  QUEUED: 'queued',
  WAITING: 'waiting',
  STREAMING: 'streaming',
  DONE: 'done',
  ABORTED: 'aborted',
  OFFLINE: 'offline',
  BUSY: 'busy',
  FAILED: 'failed',
  TIMEOUT: 'timeout',
});

/**
 * 失败分类闭集（作者令点名的八类 + 兜底一类）。
 * `aborted` 在集合里，但**语义上不是失败**：它只是「被中止」这一终态的标记，
 * 分类器把它与真正的失败分开，呈现层据此走「已中止」观感而不是错误档。
 * @type {Readonly<Record<string, string>>}
 */
export const ASK_FAILURE = Object.freeze({
  NETWORK: 'network',
  OFFLINE: 'offline',
  BUSY: 'busy',
  TIMEOUT: 'timeout',
  AUTH: 'auth',
  MODEL: 'model',
  PARSE: 'parse',
  ABORTED: 'aborted',
  UNKNOWN: 'unknown',
});

/**
 * 判定优先级次序表（单点内部**唯一**一张；从上到下第一个命中即结论）。
 * 次序即语义：终态压过程态、失败压一切 —— 例如「已失败」的回合不许因为
 * `firstTokenAt` 还在而回读成 streaming。
 * @type {string[]}
 */
export const ASK_STATE_PRIORITY = [
  ASK_STATE.FAILED,
  ASK_STATE.TIMEOUT,
  ASK_STATE.ABORTED,
  ASK_STATE.OFFLINE,
  ASK_STATE.BUSY,
  ASK_STATE.QUEUED,
  ASK_STATE.STREAMING,
  ASK_STATE.WAITING,
  ASK_STATE.DONE,
  ASK_STATE.DRAFT,
  ASK_STATE.IDLE,
];

/** 呈现类别闭集（两类观感 + 两个中性终态）。 @type {string[]} */
export const ASK_STATE_CATEGORIES = ['idle', 'working', 'done', 'aborted', 'blocked', 'error'];

/**
 * 终态驻留时长（done/aborted 显示多久后回 idle；纯数值，单点在这里）。
 * @type {number}
 */
export const DONE_DWELL_MS = 2600;

/**
 * 静默看门阈值：在飞回合多少毫秒无任何流帧即判超时（改前这条只还按钮、
 * 零痕迹；阈值 180000 逐字沿用，只是判定的**单点**搬到本模块）。
 * @type {number}
 */
export const ASK_SILENCE_MS = 180000;

/** 状态 → 呈现行（**唯一**一张；`quiet` 只准 IDLE 一态为 true）。 */
const STATE_ROW = {
  [ASK_STATE.IDLE]: {
    category: 'idle', tone: 'neutral', quiet: true,
    zh: '空闲', en: 'Idle',
  },
  [ASK_STATE.DRAFT]: {
    category: 'idle', tone: 'neutral',
    zh: '待发送', en: 'Ready to send',
    hintZh: '按 Enter 发送', hintEn: 'Press Enter to send',
  },
  [ASK_STATE.QUEUED]: {
    category: 'working', tone: 'info',
    zh: '已提交排队', en: 'Queued',
    hintZh: '连接恢复后自动发出', hintEn: 'Sends automatically once connected',
  },
  [ASK_STATE.WAITING]: {
    category: 'working', tone: 'info',
    zh: '等待响应', en: 'Waiting for a response',
  },
  [ASK_STATE.STREAMING]: {
    category: 'working', tone: 'info',
    zh: '生成中', en: 'Generating',
  },
  [ASK_STATE.DONE]: {
    category: 'done', tone: 'ok',
    zh: '已完成', en: 'Done',
  },
  [ASK_STATE.ABORTED]: {
    category: 'aborted', tone: 'neutral',
    zh: '已中止', en: 'Stopped',
    hintZh: '已生成的部分保留，可继续追问', hintEn: 'The partial answer is kept; you can ask again',
  },
  [ASK_STATE.OFFLINE]: {
    category: 'blocked', tone: 'warn',
    zh: '会话未连接', en: 'Not connected',
    hintZh: '等待重连后自动发出；或重新打开会话重试',
    hintEn: 'It sends once reconnected, or reopen the session to retry',
  },
  [ASK_STATE.BUSY]: {
    category: 'blocked', tone: 'warn',
    zh: '会话忙碌', en: 'Session busy',
    hintZh: '等当前回答结束后再问', hintEn: 'Ask again once the current answer finishes',
  },
  [ASK_STATE.FAILED]: {
    category: 'error', tone: 'error',
    zh: '请求失败', en: 'Request failed',
  },
  [ASK_STATE.TIMEOUT]: {
    category: 'error', tone: 'error',
    zh: '响应超时', en: 'Timed out',
    hintZh: '稍后重试', hintEn: 'Retry in a moment',
  },
};

/**
 * 失败类别 → 文案 / 下一步（**唯一**一张表）。
 * 每类的文案与下一步都**互不相同**（门禁断言逐类不重复）——「分不清是哪类」
 * 正是作者报的「失败了也不知道怎么回事」。
 */
const FAILURE_ROW = {
  [ASK_FAILURE.NETWORK]: {
    zh: '网络不可达', en: 'Network unreachable',
    nextZh: '检查网络后重试', nextEn: 'Check the network and retry',
  },
  [ASK_FAILURE.OFFLINE]: {
    zh: '会话未连接', en: 'Session not connected',
    nextZh: '等待重连，或重新打开会话', nextEn: 'Wait for reconnect, or reopen the session',
  },
  [ASK_FAILURE.BUSY]: {
    zh: '会话忙碌', en: 'Session busy',
    nextZh: '等当前回答结束后重试', nextEn: 'Retry once the current answer finishes',
  },
  [ASK_FAILURE.TIMEOUT]: {
    zh: '响应超时', en: 'Response timed out',
    nextZh: '稍后重试', nextEn: 'Retry in a moment',
  },
  [ASK_FAILURE.AUTH]: {
    zh: '认证或权限问题', en: 'Authentication or permission problem',
    nextZh: '检查模型密钥与权限设置', nextEn: 'Check the model key and permissions',
  },
  [ASK_FAILURE.MODEL]: {
    zh: '模型侧报错', en: 'Model-side error',
    nextZh: '重试；若持续请更换模型', nextEn: 'Retry; switch model if it persists',
  },
  [ASK_FAILURE.PARSE]: {
    zh: '响应解析失败', en: 'Response could not be parsed',
    nextZh: '重试；若持续请把错误摘要发给开发者', nextEn: 'Retry; send the diagnostic summary to a developer if it persists',
  },
  [ASK_FAILURE.ABORTED]: {
    zh: '已中止', en: 'Stopped',
    nextZh: '可继续追问', nextEn: 'You can ask again',
  },
  [ASK_FAILURE.UNKNOWN]: {
    zh: '请求失败', en: 'Request failed',
    nextZh: '重试；若持续请报告问题', nextEn: 'Retry; report a problem if it persists',
  },
};

/** 数值兜底（NaN/null/undefined ⇒ fallback）。 */
function num(v, fallback) {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback;
}

/**
 * 判定一个 ask 回合的当前状态（**唯一**判定点）。
 *
 * @param {{
 *   connected?: boolean|null,   // 前端 WS 连接态（js/state.js 的 state.connected）
 *   open?: boolean,             // dock 是否展开（收起态不呈现状态行）
 *   draft?: string,             // 输入条当前草稿（含未发送文本）
 *   turn?: null|{
 *     inflight?: boolean,       // 已提交 → 终态之间
 *     queued?: boolean,         // 帧未被 socket 收下（等补发）
 *     sentAt?: number,          // 提交时刻
 *     firstTokenAt?: number,    // 首个 token 到达时刻（0 = 还没到）
 *     lastFrameAt?: number,     // 最近一次流帧时刻（看门续臂用）
 *     chars?: number,           // 已产出字数（近似）
 *     failure?: null|object,    // classifyAskFailure() 的产物
 *     aborted?: boolean,        // 被用户/服务端中止
 *     timedOut?: boolean,       // 看门已判超时
 *     settledAt?: number,       // 终态落定时刻（done/aborted 驻留用）
 *   },
 *   now?: number,               // 当前时刻
 *   settleDwellMs?: number,     // 终态驻留时长（缺省 DONE_DWELL_MS）
 * }} [input]
 * @returns {string} ASK_STATE 成员之一
 */
export function askTurnState(input) {
  const i = input || {};
  const t = i.turn || null;
  const now = num(i.now, 0);
  const dwell = num(i.settleDwellMs, DONE_DWELL_MS);
  if (t && t.failure) {
    const kind = t.failure.kind;
    if (kind === ASK_FAILURE.ABORTED) return ASK_STATE.ABORTED;
    if (kind === ASK_FAILURE.BUSY) return ASK_STATE.BUSY;
    if (kind === ASK_FAILURE.OFFLINE) return ASK_STATE.OFFLINE;
    if (kind === ASK_FAILURE.TIMEOUT) return ASK_STATE.TIMEOUT;
    return ASK_STATE.FAILED;
  }
  if (t && t.aborted) {
    // 中止是**临时**终态：驻留到期即回空闲/待发送——它不是失败，不需要常驻提醒
    // （已生成的部分留在转录里，可继续追问）。failed/timeout 相反：常驻到下一次
    // 提交为止（出问题了必须一直看得见，直到用户采取行动）。
    if (!t.settledAt || now - t.settledAt < dwell) return ASK_STATE.ABORTED;
    return typeof i.draft === 'string' && i.draft.trim() ? ASK_STATE.DRAFT : ASK_STATE.IDLE;
  }
  if (t && t.timedOut) return ASK_STATE.TIMEOUT;
  if (t && t.inflight) {
    if (t.queued && i.connected === false) return ASK_STATE.OFFLINE;
    if (t.queued) return ASK_STATE.QUEUED;
    if (t.firstTokenAt) return ASK_STATE.STREAMING;
    return ASK_STATE.WAITING;
  }
  if (i.connected === false) return ASK_STATE.OFFLINE;
  if (t && t.settledAt && now - t.settledAt < dwell) return ASK_STATE.DONE;
  if (typeof i.draft === 'string' && i.draft.trim()) return ASK_STATE.DRAFT;
  return ASK_STATE.IDLE;
}

/** 秒数（一位小数；0 秒不给小数尾巴）。 */
function secsText(ms) {
  const s = Math.max(0, ms) / 1000;
  return s >= 10 ? String(Math.round(s)) : s.toFixed(1).replace(/\.0$/, '');
}

/**
 * 状态 → 呈现投影（**唯一**投影点）：消费方只读这里的结论，不许自行拼文案。
 *
 * @param {string} state askTurnState() 的返回值
 * @param {{ lang?: string, turn?: object|null, now?: number }} [ctx]
 * @returns {{ state: string, category: string, tone: string, quiet: boolean,
 *   label: string, hint: string, detail: string, diagnostic: string }}
 */
export function askStateView(state, ctx) {
  const c = ctx || {};
  const row = STATE_ROW[state] || STATE_ROW[ASK_STATE.IDLE];
  const isEn = c.lang === 'en';
  const t = c.turn || null;
  const now = num(c.now, 0);
  const from = t ? num(t.sentAt, 0) : 0;
  const elapsed = from && now > from ? now - from : 0;
  const chars = t ? num(t.chars, 0) : 0;
  let label = row.zh || '';
  let hint = row.hintZh ? (isEn ? row.hintEn : row.hintZh) : '';
  let detail = '';
  let diagnostic = '';

  if ((state === ASK_STATE.FAILED || state === ASK_STATE.TIMEOUT) && t && t.failure) {
    label = askFailureLabel(t.failure, c.lang);
    hint = askFailureNext(t.failure, c.lang);
    diagnostic = t.failure.diagnostic || '';
  } else if (state === ASK_STATE.WAITING) {
    detail = isEn ? `waiting ${secsText(elapsed)}s` : `已等 ${secsText(elapsed)} 秒`;
  } else if (state === ASK_STATE.STREAMING) {
    detail = isEn
      ? `${chars} chars · ${secsText(elapsed)}s`
      : `已 ${chars} 字 · ${secsText(elapsed)} 秒`;
  } else if (state === ASK_STATE.QUEUED || state === ASK_STATE.OFFLINE) {
    if (t && t.inflight) detail = isEn ? `queued ${secsText(elapsed)}s` : `已排队 ${secsText(elapsed)} 秒`;
  } else if (state === ASK_STATE.DONE || state === ASK_STATE.ABORTED) {
    if (elapsed) detail = isEn ? `took ${secsText(elapsed)}s` : `耗时 ${secsText(elapsed)} 秒`;
  }
  if (isEn && row.en) label = row.en;
  return {
    state,
    category: row.category,
    tone: row.tone,
    quiet: row.quiet === true,
    label,
    hint,
    detail,
    diagnostic,
  };
}

/**
 * 把一处原始失败事实分类成闭集里的一类（**唯一**分类点）。
 *
 * @param {{ message?: string, status?: number, name?: string, online?: boolean,
 *   wsOpen?: boolean, busy?: boolean, aborted?: boolean, timedOut?: boolean,
 *   elapsedMs?: number, sid?: string }} [input]
 * @returns {{ kind: string, status: number, message: string, elapsedMs: number, diagnostic: string }}
 */
export function classifyAskFailure(input) {
  const i = input || {};
  const status = num(i.status, 0);
  const message = typeof i.message === 'string' ? i.message : '';
  const elapsedMs = num(i.elapsedMs, 0);
  let kind = ASK_FAILURE.UNKNOWN;
  if (i.aborted) kind = ASK_FAILURE.ABORTED;
  else if (i.timedOut || /timed?\s*out|timeout/i.test(message)) kind = ASK_FAILURE.TIMEOUT;
  else if (i.wsOpen === false) kind = ASK_FAILURE.OFFLINE;
  else if (i.online === false || i.name === 'TypeError'
    || /network|failed to fetch|fetch failed|econnrefused|enotfound|socket hang ?up/i.test(message)) kind = ASK_FAILURE.NETWORK;
  else if (status === 401 || status === 403
    || /unauthoriz|forbidden|invalid api key|permission denied|\b401\b|\b403\b/i.test(message)) kind = ASK_FAILURE.AUTH;
  else if (i.busy || /busy|already (answering|running)|in flight|queue full/i.test(message)) kind = ASK_FAILURE.BUSY;
  else if (/parse|unexpected token|\bjson\b|schema|deserializ/i.test(message)) kind = ASK_FAILURE.PARSE;
  else if (status >= 400 || /model|provider|upstream|rate ?limit|\b429\b|\b5\d\d\b/i.test(message)) kind = ASK_FAILURE.MODEL;
  const safe = redactDiagnostics(message).slice(0, 160);
  const parts = [];
  // 顺序 = 窄屏可读性：会话号 → **耗时** → 状态码 → 错误摘要。诊断线在手机上会被
  // 省略号截尾（单行 + ellipsis），所以两枚短字段必须排在长摘要之前，保证
  // 「状态码 / 耗时」这两项任何屏宽都看得见；摘要被截时点一下复制全文。
  if (i.sid) parts.push(String(i.sid));
  if (elapsedMs) parts.push(secsText(elapsedMs) + 's');
  if (status) parts.push('HTTP ' + status);
  if (safe) parts.push(safe);
  return { kind, status, message: safe, elapsedMs, diagnostic: parts.join(' · ') };
}

/**
 * 失败 → 展示文案（地带状态码；**不含任何凭据**——消息已在分类时过 redact）。
 * @param {{kind?: string, status?: number}} failure
 * @param {string} [lang]
 * @returns {string}
 */
export function askFailureLabel(failure, lang) {
  const f = failure || {};
  const row = FAILURE_ROW[f.kind] || FAILURE_ROW[ASK_FAILURE.UNKNOWN];
  const base = lang === 'en' ? row.en : row.zh;
  return f.status ? `${base}（HTTP ${f.status}）` : base;
}

/**
 * 失败 → 可执行的下一步。
 * @param {{kind?: string}} failure
 * @param {string} [lang]
 * @returns {string}
 */
export function askFailureNext(failure, lang) {
  const row = FAILURE_ROW[(failure || {}).kind] || FAILURE_ROW[ASK_FAILURE.UNKNOWN];
  return lang === 'en' ? row.nextEn : row.nextZh;
}

/**
 * 凭据脱敏（诊断线上**唯一**的净化点）：key/token/authorization/password/secret
 * 的值、Bearer 载荷、超长不透明串一律替换为 `[redacted]`。
 * 「不许把凭据/token 打出来」这句在本模块里是代码，不是纪律口号。
 * @param {string} text
 * @returns {string}
 */
export function redactDiagnostics(text) {
  let s = typeof text === 'string' ? text : '';
  s = s.replace(/\b(sk|pk|ghp|gho|xox[abprs])[-_][A-Za-z0-9_-]{6,}/g, '[redacted]');
  s = s.replace(/\bBearer\s+[A-Za-z0-9._~+/=-]{6,}/gi, 'Bearer [redacted]');
  s = s.replace(
    /(api[_-]?key|access[_-]?token|refresh[_-]?token|token|authorization|password|passwd|secret|credential)\s*[:=]\s*"?[^\s"',;]{4,}"?/gi,
    '$1=[redacted]',
  );
  s = s.replace(/\b[A-Za-z0-9+/=_-]{40,}\b/g, '[redacted]');
  return s;
}

/**
 * 该状态是不是「不是失败」的终态（取消 ≠ 失败：呈现层据此零错误提示）。
 * @param {string} state
 * @returns {boolean}
 */
export function isAskNonFailureTerminal(state) {
  return state === ASK_STATE.ABORTED || state === ASK_STATE.DONE;
}
