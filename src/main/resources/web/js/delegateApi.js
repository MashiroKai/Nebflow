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
};

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
