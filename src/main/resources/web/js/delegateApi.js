// delegateApi.js — delegate 行外部面 REST 绑定（#1228 换源接线批 · S16 粗粒度行）。
//
// 两端点均为 #1225 已落库面（引擎侧 `PresenceRoutes.scala`，本批**零引擎触碰**，
// 只做前端绑定）：
//   GET  /api/delegates/:id/output?offset=N — 外部 delegate 进度输出面
//     （ExternalOutputStore 缓冲；响应与 bg-tasks 输出面同构 + 外部扩展字段
//     cliSessionId/mailboxDepth/startedAtMs；404 = 无缓冲——记录面仍在
//     DelegateRegistry，不是错误态）。
//   POST /api/delegates/:id/open-native — 「打开原软件」：宿主上打开原 CLI 原生
//     终端并 resume（fire-and-forget，响应 {opened,command} 回显；404 = 非外部记录；
//     400 = 记录损坏或启动失败）。
//
// delegate-ui 批（2026-10-08 · 定稿蓝本 §3 三值归属「本批引擎位补齐」消费面）加宽
// 三端点（引擎侧 PresenceRoutes 已落，本件只做前端绑定）：
//   GET  /api/delegates/:id           — G4 聚合单查（record+live+verdict+task 全文+
//                                      childrenCount+output?；视图④详情首绘单缝）
//   GET  /api/delegates/:id/children  — G3 星型子代理查询（{delegateId,count,children[]}；
//                                      查无文件 = count 0 非 404；flow 子结构不走本面）
//   POST /api/delegates/:id/archive   — G6 归档单旗（幂等零删除；历史窗 =
//                                      GET /api/chains?archived=1）
//
// 定位：与 nodeData.js 同族的叶子取数件——但 nodeData 是 project/node/flowmap 域
// （#1228 停用清单已注定其 fetchFlowMap/controlChain 随 per-project 面退役），delegate
// 域端点独立落本件，退役面互不牵连。行源/链源**不在本件**（read port 单缝 =
// delegateChainStub.setSource，候 N1 定稿挂真 provider）。

import { authHeaders } from './flowHelpers.js';

/** 端点表（与 PresenceRoutes 挂载路径逐字对齐；Router("/api") 前缀）。 */
export const DELEGATE_API = {
  output: (id, offset) =>
    `/api/delegates/${encodeURIComponent(String(id || ''))}/output?offset=${Number(offset) || 0}`,
  openNative: (id) => `/api/delegates/${encodeURIComponent(String(id || ''))}/open-native`,
  detail: (id) => `/api/delegates/${encodeURIComponent(String(id || ''))}`,
  children: (id) => `/api/delegates/${encodeURIComponent(String(id || ''))}/children`,
  archive: (id) => `/api/delegates/${encodeURIComponent(String(id || ''))}/archive`,
  // 反归档（两层架构批 2026-10-09 · G4-2）：归档抽屉「恢复」动作的引擎缝——
  // archivedAt 摘旗（幂等零删除），行回在册窗（L2 平铺面）。
  unarchive: (id) => `/api/delegates/${encodeURIComponent(String(id || ''))}/unarchive`,
  // 归档历史窗（delegate-ui 修订批 2026-10-09 九点⑧：S11 右上角归档 FAB 的清单源；
  // 与 /api/chains 在册窗互补零重叠——G6 双窗口径）。
  archived: () => '/api/chains?archived=1',
};

/** 该 id 是否为星型区/归档入口的适用形态（delegate 域会话；外部 delegate 同域——
 *  引擎 G6 对外部记录同样有效，children 恒空集）。 */
export function isDelegateDomainId(id) {
  const s = String(id || '');
  return s.startsWith('delegate-') || s.startsWith('external-');
}

/**
 * 外部 delegate 进度输出（S16 粗粒度行进度腿；offset 增量读——调用方持守
 * `nextOffset`，只拉增量，不重复拉全量缓冲）。fail-soft：任何失败（网络/HTTP/
 * 解析）→ null，调用方保留上次读数（行绝不因单次拉取失败崩/清空）。
 * @param {string} id delegate id
 * @param {number} [offset] 增量偏移（首拉 0）
 * @returns {Promise<Object|null>} ExternalOutputStore.read 响应 JSON（含
 *   output/nextOffset/status/totalLines/exitCode 等键）或 null
 */
export async function fetchDelegateOutput(id, offset = 0) {
  try {
    const r = await fetch(DELEGATE_API.output(id, offset), { headers: authHeaders() });
    if (!r.ok) return null; // 404 无缓冲 / 5xx —— 都不是行崩溃面
    return await r.json();
  } catch (_) {
    return null;
  }
}

/**
 * 「打开原软件」（S16 出口腿；钮 title/aria = §16 键 6）。返回可判别结果对象
 * （controlChain 同形）：ok 200 {opened,command}；http 非 2xx（404 非外部记录 /
 * 400 记录损坏或启动失败）；network fetch 拒绝。不抛异常（行腿不因单次出口失败中断）。
 * @param {string} id delegate id
 * @returns {Promise<{state:'ok', data:any}|{state:'http', status:number, data:any}|{state:'network'}>}
 */
export async function openDelegateNative(id) {
  try {
    const r = await fetch(DELEGATE_API.openNative(id), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    });
    let data = null;
    try { data = await r.json(); } catch (_) { data = null; }
    if (!r.ok) return { state: 'http', status: r.status, data };
    return { state: 'ok', data };
  } catch (_) {
    return { state: 'network' };
  }
}

/**
 * G4 delegate 聚合单查（视图④详情首绘单缝）。fail-soft：任何失败（网络/HTTP/解析）
 * → null，调用方优雅缺席（弹窗不因单次拉取失败崩/清空）。
 * @param {string} id delegate id
 * @returns {Promise<Object|null>} {record,live,verdict,task,childrenCount,output?} 或 null
 */
export async function fetchDelegateDetail(id) {
  try {
    const r = await fetch(DELEGATE_API.detail(id), { headers: authHeaders() });
    if (!r.ok) return null; // 404 查无记录 / 400 非法 id / 5xx —— 都不是崩面
    return await r.json();
  } catch (_) {
    return null;
  }
}

/**
 * G3 星型子代理查询（视图②④星型区数据缝）。fail-soft 同上；404（父无记录）与
 * 网络失败同归 null（消费端按「无读数」降级为空区），查无文件 = {count:0,children:[]}。
 * @param {string} id delegate id
 * @returns {Promise<Object|null>} {delegateId,count,children[]} 或 null
 */
export async function fetchDelegateChildren(id) {
  try {
    const r = await fetch(DELEGATE_API.children(id), { headers: authHeaders() });
    if (!r.ok) return null;
    return await r.json();
  } catch (_) {
    return null;
  }
}

/**
 * 归档 delegate 历史窗清单（九点⑧ S11 归档 FAB 清单源；只读）。fail-soft：任何失败
 * （网络/HTTP/解析/形状漂移）→ null（调用方空态兜底）；成功 = DelegateRow 形数组
 * （在册窗同投影 rowFromRegistry——本件不 import stub，归一归消费端既有链路）。
 * @returns {Promise<Array<Object>|null>}
 */
export async function fetchArchivedDelegates() {
  try {
    const r = await fetch(DELEGATE_API.archived(), { headers: authHeaders() });
    if (!r.ok) return null;
    const body = await r.json();
    const rows = Array.isArray(body && body.rows) ? body.rows : null;
    return rows && rows.every((x) => x && typeof x === 'object') ? rows : null;
  } catch (_) {
    return null;
  }
}

/**
 * G6 归档动作（视图④归档入口的引擎缝；幂等零删除）。返回可判别结果对象
 * （openDelegateNative 同形）：ok 200 {id,archived,archivedAt}；http 非 2xx
 * （404 查无记录 / 400 非法 id）；network fetch 拒绝。不抛异常。
 * @param {string} id delegate id
 * @returns {Promise<{state:'ok', data:any}|{state:'http', status:number, data:any}|{state:'network'}>}
 */
export async function archiveDelegate(id) {
  try {
    const r = await fetch(DELEGATE_API.archive(id), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    });
    let data = null;
    try { data = await r.json(); } catch (_) { data = null; }
    if (!r.ok) return { state: 'http', status: r.status, data };
    return { state: 'ok', data };
  } catch (_) {
    return { state: 'network' };
  }
}

/**
 * G4-2 反归档动作（两层架构批 2026-10-09 · 归档抽屉「恢复」钮的引擎缝；幂等零删除
 * ——archivedAt 摘旗，行回在册窗）。返回可判别结果对象（archiveDelegate 同形）：
 * ok 200 {id,archived:false}；http 非 2xx（404 查无记录 / 400 非法 id）；network
 * fetch 拒绝。不抛异常。
 * @param {string} id delegate id
 * @returns {Promise<{state:'ok', data:any}|{state:'http', status:number, data:any}|{state:'network'}>}
 */
export async function unarchiveDelegate(id) {
  try {
    const r = await fetch(DELEGATE_API.unarchive(id), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    });
    let data = null;
    try { data = await r.json(); } catch (_) { data = null; }
    if (!r.ok) return { state: 'http', status: r.status, data };
    return { state: 'ok', data };
  } catch (_) {
    return { state: 'network' };
  }
}
