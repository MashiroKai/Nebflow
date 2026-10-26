// mergeQueueStatus.js — 子智能体面板「候合并」行队列细节读取口
// （台账 #896 交通引导员制 2026-10-26 · 前身 = panel-merge-status 批 2026-10-25）。
//
// 作者令四条 → 本模块职责：
//   · 位次 —— 数据源 = GET /api/merge-queue（引擎 MergeQueue 持久账本投影，
//     PresenceRoutes.scala）。条目 position/total → 行内「前面还有 N 位」
//     （N = position - 1；队首不渲染）。
//     🔴 只渲染不派生：位次一律取载荷 position（引擎 FIFO 序），前端不复算 rank /
//        持有者——与 mergeQueueView.js 同一纪律。
//   · 状态 —— 条目 state（waiting / turn-granted；owner-gone / failed / merged 是
//     关账终态，经通知与 recent 列呈现，不进等待行）→ turn-granted 渲染「轮到你
//     合并」（合并窗持有中）；waiting 渲染位次与未放行原因。
//   · 原因 —— lastBlockReason 非空（waiting：为什么还没放行——base 未净空等）→
//     一句原因摘要（展示面 = 首行截断，全文进 title tooltip）。
//   · 降级 —— 404 / 非 2xx / 网络失败 / 形状漂移 / 空队列 ⇒ mergeQueueEntryFor()
//     返回 null，行回静态「候合并」形态（「等待合并轮」次行照旧）——零报错、
//     零 console 噪音、不留半新半旧脏数据（失败即整表清空）。
//   · 无空转轮询 —— 取数纯事件驱动：面板渲染含候合并行（renderBgAgentDropdown）+
//     面板在开 uptick 同拍（armBgUptimeTick）。模块内双闸：成功后 15s 最小间隔、
//     失败后 60s 退避（API 未生效的旧后端绝不被打成高频 404）；无 interval 常驻，
//     面板关 = 零取数。
//
// join key：行 sessionId（剥 team- 前缀）↔ 载荷 delegateId —— MergeQueue.Member
// .delegateId = delegate 会话 id（DelegateTerminal.scala enqueue 同源），与面板行
// sid 同一命名空间。位次形状防御对齐 mergeQueueView.js：非对象 / 位次非正整数 /
// position > total ⇒ 该条目剔除（🔴 禁编造数字）。

import { authHeaders } from './flowHelpers.js';

const MERGE_QUEUE_API = '/api/merge-queue';
const FETCH_MIN_GAP_OK_MS = 15000; // 成功后最小重取间隔（uptick 节拍）
const FETCH_MIN_GAP_FAIL_MS = 60000; // 失败退避（404/网络/漂移——禁空转重试）
const REASON_DISPLAY_MAX = 80; // 摘要展示截断（全文走 tooltip）
const OPEN_STATES = new Set(['waiting', 'turn-granted']);

/**
 * 一条队列条目的行级视图（parseEntry 产物）。
 * @typedef {{delegateId: string, ahead: number, total: number, state: string, reason: string, reasonFull: string}} EntryView
 */

/**
 * 一条队列条目的形状防御 + 投影（不可用 ⇒ null，🔴 禁编造数字）。
 * @param {any} raw /api/merge-queue 条目原载荷
 * @returns {?EntryView}
 */
function parseEntry(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const delegateId = String(raw.delegateId || '');
  if (!delegateId) return null;
  const state = String(raw.state || 'waiting');
  if (!OPEN_STATES.has(state)) return null; // 关账终态不进等待行（通知面/recent 呈现）
  const p = Number(raw.position);
  const m = Number(raw.total);
  if (!Number.isInteger(p) || !Number.isInteger(m) || p < 1 || m < 1 || p > m) return null;
  const reasonFull = normalizeReason(String(raw.lastBlockReason || raw.lastEventReason || ''));
  return {
    delegateId,
    ahead: p - 1,
    total: m,
    state,
    reason: reasonFull ? truncateReason(reasonFull) : '',
    reasonFull,
  };
}

/**
 * 原因正形（折叠空白；后端已 ≤300 字符，此步只去换行噪音）。
 * @param {string} s
 * @returns {string}
 */
function normalizeReason(s) {
  return String(s || '').replace(/\s+/g, ' ').trim();
}

/**
 * 摘要展示截断（超长补省略号；全文走 title tooltip）。
 * @param {string} s
 * @returns {string}
 */
function truncateReason(s) {
  return s.length > REASON_DISPLAY_MAX ? s.slice(0, REASON_DISPLAY_MAX) + '…' : s;
}

/** 行级缓存：delegateId → EntryView。 */
/** @type {Map<string, EntryView>} */
const entryByDelegate = new Map();
let lastFetchDoneAt = 0; // 上次取数完成时刻（成败均记——退避闸基准）
let lastFetchOk = false; // 上次取数是否拿到可用载荷（成败决定退避档）
let revision = 0; // 缓存内容版本（取到不同内容 +1——调用方据此决定重渲染）
let inflight = /** @type {Promise<boolean>|null} */ (null);

/**
 * /api/merge-queue 载荷 → delegateId → 条目视图 Map（导出 = fixture 验证面）。
 * queues / entries 任一层形状漂移 ⇒ 静默跳过该层（不抛错、不半解析）。
 * @param {any} body 响应 JSON
 * @returns {Map<string, EntryView>}
 */
export function mergeQueueEntriesFromPayload(body) {
  const out = new Map();
  const queues = body && typeof body === 'object' && !Array.isArray(body) && Array.isArray(body.queues)
    ? body.queues
    : [];
  for (const q of queues) {
    const entries = q && typeof q === 'object' && !Array.isArray(q) && Array.isArray(q.entries)
      ? q.entries
      : [];
    for (const raw of entries) {
      const v = parseEntry(raw);
      if (v && !out.has(v.delegateId)) out.set(v.delegateId, v);
    }
  }
  return out;
}

/**
 * 取一次状态面（节流 + fail-soft）。返回是否取到**新内容**（缓存版本前进）。
 * 节流：in-flight 去重；成功后 15s / 失败后 60s 内直接 no-op(false)。
 * 任何失败（非 2xx / 网络 / 解析 / 漂移）= 整表清空（降级红线：不留脏位次）。
 * @returns {Promise<boolean>}
 */
async function fetchOnce() {
  const gap = lastFetchOk ? FETCH_MIN_GAP_OK_MS : FETCH_MIN_GAP_FAIL_MS;
  if (inflight) return inflight;
  if (lastFetchDoneAt > 0 && Date.now() - lastFetchDoneAt < gap) return false;
  inflight = (async () => {
    let changed = false;
    let ok = false;
    try {
      const r = await fetch(MERGE_QUEUE_API, { headers: authHeaders() });
      if (r.ok) {
        const body = await r.json();
        const next = mergeQueueEntriesFromPayload(body);
        ok = true;
        if (JSON.stringify([...entryByDelegate.values()]) !== JSON.stringify([...next.values()])) {
          entryByDelegate.clear();
          for (const [k, v] of next) entryByDelegate.set(k, v);
          revision += 1;
          changed = true;
        }
      }
    } catch (_) {
      ok = false; // 网络失败 —— 走下方清空降级
    }
    if (!ok && entryByDelegate.size > 0) {
      entryByDelegate.clear();
      revision += 1;
      changed = true;
    }
    lastFetchDoneAt = Date.now();
    lastFetchOk = ok;
    return changed;
  })();
  try {
    return await inflight;
  } finally {
    inflight = null;
  }
}

/**
 * 行级读取口（渲染点唯一入口）。null = 无队列数据 ⇒ 行静态「候合并」降级形态。
 * @param {string} sid 行 sessionId（允许带 team- 前缀，此处剥）
 * @returns {?EntryView}
 */
export function mergeQueueEntryFor(sid) {
  const key = String(sid || '').replace(/^team-/, '');
  return entryByDelegate.get(key) || null;
}

/**
 * 面板侧触发口：面板含候合并行时调用（事件驱动，模块内节流）。
 * @returns {Promise<boolean>} 是否取到新内容（调用方据此重渲染面板）
 */
export function ensureMergeQueueStatus() {
  return fetchOnce();
}
