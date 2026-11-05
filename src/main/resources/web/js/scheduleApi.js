// scheduleApi.js — 定时任务/待办卡片（<nf-sched-card>）的**唯一写读腿**
// （schedule-created-card 批 2026-11-05，作者令 09:03「创建定时任务或者待办的
// 时候，应该有一个卡片……允许我直接在上面修改或取消」）。
//
// ── 为什么单独一个模块 ────────────────────────────────────────────────
// 卡片要能「原地改 / 原地取消，结果立刻回写引擎侧存储」。引擎侧的写路径**已经
// 存在且只有一条**：WS 帧 `updateScheduledTask` / `deleteScheduledTask` /
// `listScheduledTasks` → 网关 WsTasksWorkspaceHandlers → ScheduledTaskStore
// （**与 Schedule 工具同一份 store**，工具走 ScheduledTaskStore 的同名方法）。
// 本模块只做三件事，绝不自己拼一份乐观 state 冒充成功：
//   ① 发帧（sendWs 单点）；
//   ② 等**引擎回执**（scheduledTaskUpdated / scheduledTaskDeleted / error），
//      超时即失败（错误帧 / 超时 / 未连接三类都不许被渲染成「已保存」）；
//   ③ 回读（listScheduledTasks）拿权威行 —— 卡片显示的字段一律来自引擎，
//      不是来自本地 patch（验收第 4 条「回读一致」的机制面）。
//
// ── 复用面（本批判据：不新建平行体系）─────────────────────────────────
//   · 帧协议：scheduled-task.js / todoFace.js 已在用的同一组帧（零新增帧型）；
//   · 存储：ScheduledTaskStore（网关），工具与控制面共用（零新增 store）；
//   · 行 JSON：网关 listTaskJson 单点（list 面与 scheduledTaskUpdated 广播同形）；
//   · 传输：ws.js 的 sendWs / onMessage（零自造 socket）。
//
// ── 传输面可替换（harness 用）─────────────────────────────────────────
// transport 是**一个对象**（不是冻结表）：`transport.send` 可在测试里替换成记录
// 器，`.deliver(frame)` 把帧喂进本模块的真处理器 —— 与既有夹具纪律同族
// （todoStore.__seed 夹具模式）。生产路径零赋值，永远是 ws.js。

import state from './state.js';
import { sendWs, onMessage } from './ws.js';

/** 回执超时（ms）：超时 = 失败，绝不静默当作成功。 */
export const ACK_TIMEOUT_MS = 8000;

/**
 * 传输面。send 返回 boolean（false = 未连接，帧没出去）；deliver 供夹具注入
 * 入站帧（生产路径由 onMessage 直接喂）。
 * @type {{send: (frame: any) => boolean, ready: () => boolean, on: (type: string, fn: (msg: any) => void) => (() => void)}}
 */
export const transport = {
  send: (frame) => sendWs(frame),
  ready: () => !!(state.ws && state.ws.readyState === 1 /* WebSocket.OPEN */),
  on: (type, fn) => onMessage(type, fn),
};

/** 等待中的请求（单点登记表；卡片/测试都通过它读「现在有哪些请求在飞」）。 */
const PENDING = new Map(); // requestId -> { id, kind, resolve, reject, timer }

/** 等待中的 list 请求（FIFO；引擎按到达顺序回同一帧型）。 */
const LIST_WAITERS = [];

let seq = 0;
const nextRequestId = () => `sched-card-${++seq}`;

/**
 * 建一个请求：登记 + 起超时。返回 { requestId, promise }。
 * @param {{id: string, kind: string, want?: 'update'|'cancel'}} opts
 * @returns {{requestId: string, promise: Promise<any>}}
 */
function arm(opts) {
  const requestId = nextRequestId();
  const promise = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      PENDING.delete(requestId);
      reject({ code: 'timeout', message: 'timeout' });
    }, ACK_TIMEOUT_MS);
    PENDING.set(requestId, { ...opts, resolve, reject, timer });
  });
  return { requestId, promise };
}

/** 清账（成功/失败/超时都走这里；防双结）。 */
function settle(requestId, ok, payload) {
  const entry = PENDING.get(requestId);
  if (!entry) return false;
  PENDING.delete(requestId);
  clearTimeout(entry.timer);
  if (ok) entry.resolve(payload);
  else entry.reject(payload);
  return true;
}

/** 按 id 找在飞请求（回执只带 id，不带 requestId —— 帧协议既有的形状）。 */
function pendingForId(id, kind) {
  for (const [requestId, entry] of PENDING) {
    if (entry.id === id && (!kind || entry.kind === kind)) return requestId;
  }
  return null;
}

// ── 入站帧处理器（单点；ws.js 的 onMessage 一挂到底）────────────────────
function onUpdatedFrame(msg) {
  const id = msg && msg.task && msg.task.id;
  if (!id) return;
  const requestId = pendingForId(id, 'update');
  if (requestId) settle(requestId, true, { id, row: msg.task });
}

function onDeletedFrame(msg) {
  const id = msg && msg.id;
  if (!id) return;
  // 取消腿：deleted = 引擎确认行已删（成功，终态「已取消」）。
  const cancelId = pendingForId(id, 'cancel');
  if (cancelId) settle(cancelId, true, { id, deleted: true });
  // 编辑腿在飞时收到 deleted = 行在别处消失 ⇒ **失败**（不许渲染成已保存）。
  const updateId = pendingForId(id, 'update');
  if (updateId) settle(updateId, false, { code: 'gone', message: 'gone' });
}

function onErrorFrame(msg) {
  const id = msg && msg.id;
  const msgType = msg && msg.msgType;
  const kind = msgType === 'deleteScheduledTask' ? 'cancel' : msgType === 'updateScheduledTask' ? 'update' : null;
  if (!kind) return;
  const requestId = id ? pendingForId(id, kind) : null;
  if (requestId) settle(requestId, false, { code: 'engine', message: msg.message || 'error' });
}

function onListFrame(msg) {
  const waiter = LIST_WAITERS.shift();
  if (!waiter) return;
  clearTimeout(waiter.timer);
  waiter.resolve(Array.isArray(msg.tasks) ? msg.tasks : []);
}

let wired = false;
/**
 * 挂入站帧监听（幂等）。生产 = main.js 启动期调一次；测试可不调（用
 * transport.deliver 直接喂真处理器）。两条入口共用同一组处理器 ⇒ 测试覆盖面
 * 与生产面同源。
 */
export function initScheduleApi() {
  if (wired) return;
  wired = true;
  /** @type {Array<[string, (msg: any) => void]>} */
  const FRAMES = [
    ['scheduledTaskUpdated', onUpdatedFrame],
    ['scheduledTaskDeleted', onDeletedFrame],
    ['scheduledTaskCompleted', (msg) => { if (msg && msg.id) onDeletedFrame({ id: msg.id }); }],
    ['error', onErrorFrame],
    ['scheduledTaskList', onListFrame],
  ];
  for (const [type, fn] of FRAMES) transport.on(type, fn);
}

/**
 * 把一帧喂进真处理器（夹具/单测入口；生产路径 = ws.js 的 onMessage）。
 * @param {any} msg
 * @returns {void}
 */
export function deliverFrame(msg) {
  if (!msg || typeof msg.type !== 'string') return;
  if (msg.type === 'scheduledTaskUpdated') onUpdatedFrame(msg);
  else if (msg.type === 'scheduledTaskDeleted') onDeletedFrame(msg);
  else if (msg.type === 'scheduledTaskCompleted') onDeletedFrame({ id: msg.id });
  else if (msg.type === 'error') onErrorFrame(msg);
  else if (msg.type === 'scheduledTaskList') onListFrame(msg);
}

/** 在飞请求数（诊断/断言用）。 */
export function pendingCount() {
  return PENDING.size;
}

/**
 * 改一条记录（内容/项目/触发时间/重复）—— `updateScheduledTask` 帧，
 * **与 Schedule 工具 edit 动作同一条 store 写路径**（网关同一 handler 族）。
 * 只在引擎回执到达后才 resolve；错误帧/超时/未连接一律 reject。
 * @param {{id: string, sessionId?: string, patch: Record<string, any>}} opts
 * @returns {Promise<{id: string, row: any}>}
 */
export function updateEntry({ id, sessionId, patch }) {
  if (!id) return Promise.reject({ code: 'bad-request', message: 'id required' });
  if (!transport.ready()) return Promise.reject({ code: 'offline', message: 'offline' });
  const frame = { type: 'updateScheduledTask', id };
  if (sessionId) frame.sessionId = sessionId;
  for (const k of ['content', 'project', 'priority', 'triggerAt', 'repeat']) {
    if (patch && Object.prototype.hasOwnProperty.call(patch, k) && patch[k] !== undefined) frame[k] = patch[k];
  }
  const { requestId, promise } = arm({ id, kind: 'update' });
  const sent = transport.send(frame);
  if (!sent) {
    settle(requestId, false, { code: 'offline', message: 'offline' });
  }
  return promise;
}

/**
 * 取消一条记录 —— `deleteScheduledTask` 帧（与 Schedule 工具 cancel 动作同一
 * store 写路径）。`scheduledTaskDeleted` 回执 = 成功（行已从存储删除）。
 * @param {{id: string, sessionId?: string}} opts
 * @returns {Promise<{id: string, deleted: true}>}
 */
export function cancelEntry({ id, sessionId }) {
  if (!id) return Promise.reject({ code: 'bad-request', message: 'id required' });
  if (!transport.ready()) return Promise.reject({ code: 'offline', message: 'offline' });
  const { requestId, promise } = arm({ id, kind: 'cancel' });
  const sent = transport.send({ type: 'deleteScheduledTask', id, sessionId });
  if (!sent) settle(requestId, false, { code: 'offline', message: 'offline' });
  return promise;
}

/**
 * 回读：读权威行。定时任务 = 单会话列表；待办 = 跨会话收件箱（含已完成，
 * 否则「已完成」会被误读成「已取消」）。
 * @param {{id: string, kind: 'task'|'todo', sessionId?: string}} opts
 * @returns {Promise<any|null>} 命中的行（listTaskJson 形状）或 null（引擎侧已不在）
 */
export function readEntry({ id, kind, sessionId }) {
  if (!transport.ready()) return Promise.reject({ code: 'offline', message: 'offline' });
  const frame = kind === 'todo'
    ? { type: 'listScheduledTasks', scope: 'all', includeCompleted: true, sessionId }
    : { type: 'listScheduledTasks', sessionId };
  const promise = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      const i = LIST_WAITERS.indexOf(waiter);
      if (i >= 0) LIST_WAITERS.splice(i, 1);
      reject({ code: 'timeout', message: 'timeout' });
    }, ACK_TIMEOUT_MS);
    // eslint-disable-next-line no-use-before-define
    const waiter = { resolve: (rows) => resolve(rows.find((r) => r && r.id === id) || null), reject, timer };
    LIST_WAITERS.push(waiter);
  });
  const sent = transport.send(frame);
  if (!sent) {
    const waiter = LIST_WAITERS.pop();
    if (waiter) clearTimeout(waiter.timer);
    return Promise.reject({ code: 'offline', message: 'offline' });
  }
  return promise;
}
