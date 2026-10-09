// flowMapTab.js 拆分(FE组件化批次六 2026-09-28):折叠态助手 可复用模块(行为保持)。
// 原 flowMapTab.js :130-:165 — CHAIN_COLLAPSED_KEY / collapsedCache / collapseAll /
// collapsedChainIdsOf / persistCollapsed。文件级态 collapsedCache 整态随迁(批次五
// 先例纪律:态不可劈),主体侧只经 persistCollapsed(写)/ collapsedChainIdsOf(读)
// 函数面访问;setChainCollapsed/toggleChainCollapsed 因调用主体 withNoAnim/
// rerenderFlowMap 留守 flowMapTab.js。:124-:129 折叠渲染层注释随 foldView 留主体。
// collapsedChainIdsOf 导出面由 flowMapTab.js 原样转发(公共导出面字节稳定)。

// 折叠态存 localStorage（视图偏好非协作事实；域内持久化先例 = activeOnly），值按
// project 分桶存 chainId 集。链 id 因分量合并变化时记忆失效 → 优雅降级为展开（无害）。
const CHAIN_COLLAPSED_KEY = 'nebflow.flowmap.collapsedChains';

/** @type {Map<string, Set<string>> | null} localStorage 解析缓存镜像（写路径同步维护） */
let collapsedCache = null;

/** localStorage → Map<project, Set<chainId>>（解析失败 = 空 = 全展开，零抛错）。 */
function collapseAll() {
  if (collapsedCache) return collapsedCache;
  let raw = null;
  try { raw = JSON.parse(localStorage.getItem(CHAIN_COLLAPSED_KEY) || '{}'); } catch (e) { raw = {}; }
  const m = new Map();
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    for (const [proj, ids] of Object.entries(raw)) {
      if (Array.isArray(ids)) m.set(String(proj), new Set(ids.map(String)));
    }
  }
  collapsedCache = m;
  return m;
}

/** @param {string} project @returns {Set<string>} 该项目折叠中的链 id 集（空集 = 全展开）。 */
export function collapsedChainIdsOf(project) {
  return collapseAll().get(String(project || '')) || new Set();
}

/** 折叠态持久化（缓存镜像 + localStorage 双写；写失败 = 会话态兜底，不抛）。 */
function persistCollapsed(project, ids) {
  const all = collapseAll();
  if (ids.size) all.set(String(project), ids);
  else all.delete(String(project));
  const obj = {};
  for (const [p, s] of all) obj[p] = Array.from(s);
  try { localStorage.setItem(CHAIN_COLLAPSED_KEY, JSON.stringify(obj)); } catch (e) { /* 隐私模式等 */ }
}

// 模块函数面(批次六拆分管道,非原码):persistCollapsed 供 flowMapTab.js 主体写路径
// (setChainCollapsed)调用;collapseAll 仅服务本模块读写对,不对外导出。
// collapsedChainIdsOf 的 export 关键字随 :32 原声明逐字保留,导出面由 flowMapTab.js 转发。
export { persistCollapsed };
