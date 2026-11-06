// bgSourceChain.js — 后台 agent 来源链解析（作者令 2026-11-01 19:23「后台 agent 的
// badge 要标注来源链」）。
//
// 口径（作者令逐字）：
//   - badge 形态 = 「上层 · 下层」分隔式，链上每级都是**名字**（delegate 短名 /
//     subagent 名 / node 名），不是 id 不是 type；
//   - 主 agent 直派的 delegate 无上层链时只显示自己（不造空段）；
//   - 根会话派发的显示 root/主 agent 名（rootName.js 单一显示名权威——Nebula 退役
//     批口径承继，机制名不上屏）；
//   - 设备名与 type 徽标契约不动，本链只挂在「谁开启」轴（来源 chip）上；
//   - 名字过长走既有省略规则（chip max-width + ellipsis），悬浮 title 给全链。
//
// 数据面（🔴 纯显示层：引擎侧 id 生成与协议字段零触碰）——全部读既有镜像：
//   - delegate 注册行镜像（delegateChainStub.listDelegateRows → provider REST 镜像）：
//     delegateId → {name 批次短名, title 批次标题}；
//   - G3 星型子代理镜像（listDelegateChildren → /api/delegates/:id/children 投影）：
//     子会话 id → {agentName 子代理名, parentSessionId 父会话}（含嵌套 delegate 子）；
//   - live/快照行桶（state.sessionBgAgents）：sessionId → 行信息（parentSessionId
//     面板树批单点注入 + agentName 四档链）＋桶键 = rootSessionId（delegate 作用域
//     桶键 = delegateId——Flow 会话可见性批的引擎自根事实）；
//   - 行内自带字段（BgTaskRegistry 快照/信封既有键）：ownerName（delegate 批次
//     短名）/ origin+originLabel（node- → 节点名、dispatcher- → dispatcher/<project>）。
//
// 纪律：解析不到 = 返回可判的空/短链，调用方回退既有显示（零臆造、零第二权威）；
// 深度护栏（≤6 级 + visited 环护栏）防脏数据死循环。叶子模块（只依赖 state /
// delegateChainStub / rootName，无环）。

import state from './state.js';
import { listDelegateRows, listDelegateChildren } from './delegateChainStub.js';
import { rootDisplayName } from './rootName.js';

/** 上溯深度护栏：来源链不是族谱——6 级足够覆盖真实嵌套，防脏 parent 链死循环。 */
const MAX_CHAIN_DEPTH = 6;

/** 子代理族会话 id 前缀（与 bgAgents.js isBgAgentId / bgAgentKindFromSession 同源
 *  口径的单点本地副本——本件叶子化不反向 import 面板模块）。 */
const CHILD_SESSION_RE = /^(subagent|subtask|workflow|ephemeral|dag)-/;

/** delegate/external 会话 id 前缀（DelegateTool spawn 形 `delegate-<agent>-<uuid8>`
 *  ＋ 外部执行器 external-*）。 */
const DELEGATE_SID_RE = /^(delegate|external)-/;

/** 泛名判读（bgAgents.js bgGenericName 同族最小副本）：id 形/内置 def 名不算名字。 */
const GENERIC_NAMES = new Set(['subagent', 'nebflow', 'nebula', 'kernel', 'general', 'worker', 'step']);
function isGenericName(name, sid) {
  const n = String(name || '').trim();
  if (!n) return true;
  if (n === String(sid || '')) return true;
  if (GENERIC_NAMES.has(n.toLowerCase())) return true;
  if (/^(subagent|workflow|subtask|ephemeral)-[0-9a-z-]+$/i.test(n)) return true;
  return false;
}

/** 文本首行（bgAgents.js bgFirstLine 同规）。 */
function firstLine(text) {
  const s = String(text || '').trim();
  if (!s) return '';
  const nl = s.indexOf('\n');
  return (nl === -1 ? s : s.slice(0, nl)).trim();
}

function stripTeam(sid) {
  return String(sid || '').replace(/^team-/, '');
}

/** delegate 会话 id → 注册行镜像（查无 = null）。 */
function delegateRowOf(sid) {
  const k = stripTeam(sid);
  if (!k) return null;
  const rows = listDelegateRows() || [];
  return rows.find((r) => stripTeam(r && r.delegateId) === k) || null;
}

/** delegate 注册行 → 显示名（批次短名第一源，批次标题首行殿后；皆缺 = ''）。 */
function delegateDisplayName(row) {
  if (!row) return '';
  return String(row.name || '').trim() || firstLine(row.title);
}

function isDelegateSid(sid) {
  const k = stripTeam(sid);
  return DELEGATE_SID_RE.test(k) || !!delegateRowOf(k);
}

/** G3 子条目查（子会话 id → {name, parentSid}；查无 = null）。 */
function childEntryOf(sid) {
  const k = stripTeam(sid);
  if (!k) return null;
  for (const r of listDelegateRows() || []) {
    if (!r || r.external === true || !r.delegateId) continue;
    let kids = [];
    try {
      kids = listDelegateChildren(r.delegateId) || [];
    } catch {
      kids = []; // 源故障 = 该父无读数（stub 读取口自身已 fail-soft，双保险）
    }
    for (const c of kids) {
      if (stripTeam(c && c.taskId) === k) {
        return {
          name: String((c && c.agentName) || '').trim(),
          parentSid: stripTeam((c && c.parentSessionId) || r.delegateId),
        };
      }
    }
  }
  return null;
}

/** live/快照行桶查（sessionId → {name, parentSid, bucketSid}；查无 = null）。 */
function liveEntryOf(sid) {
  const k = stripTeam(sid);
  if (!k) return null;
  const buckets = state.sessionBgAgents || {};
  for (const bucket of Object.keys(buckets)) {
    const agents = buckets[bucket] || {};
    for (const info of Object.values(agents)) {
      if (!info) continue;
      if (stripTeam(info.sessionId) !== k) continue;
      return {
        name: String(info.agentName || '').trim() || String(info.name || '').trim(),
        parentSid: stripTeam(info.parentSessionId || ''),
        bucketSid: stripTeam(bucket),
      };
    }
  }
  return null;
}

/** 某会话 id 的显示名（链级名字读点）：delegate 短名 → live 行 agentName → G3 子名；
 *  泛名/id 形一律不算名字（''）。 */
export function chainNameOf(sid) {
  const k = stripTeam(sid);
  if (!k) return '';
  const row = delegateRowOf(k);
  if (row) return delegateDisplayName(row);
  const live = liveEntryOf(k);
  if (live && !isGenericName(live.name, k)) return live.name;
  const child = childEntryOf(k);
  if (child && child.name && !isGenericName(child.name, k)) return child.name;
  return '';
}

/** 父会话 id 判读（上溯一步；'' = 到顶）。三源按序取第一命中：
 *  ① G3 子条目 parentSessionId；② live/快照行 parentSessionId → 桶键（delegate
 *  作用域桶）；③ 行所在桶键本身（桶 = delegate 会话且非自指）。判读结果若为根域
 *  会话（无 delegate 痕迹亦无映射）＝到顶——根不在链上（作者令「不造空段」）。 */
function parentHintOf(sid, bucketSid) {
  const k = stripTeam(sid);
  if (!k) return '';
  const child = childEntryOf(k);
  if (child && child.parentSid) return child.parentSid;
  const live = liveEntryOf(k);
  if (live) {
    if (live.parentSid) return live.parentSid;
    if (live.bucketSid && live.bucketSid !== k && isDelegateSid(live.bucketSid)) return live.bucketSid;
  }
  const b = stripTeam(bucketSid);
  if (b && b !== k && isDelegateSid(b)) return b;
  return '';
}

/** 上溯链（上层→下层的名字段，不含自身；到顶/无名/环/深度护栏即停）。 */
export function upperChainOf(sid, bucketSid) {
  const segs = [];
  const visited = new Set();
  let cur = stripTeam(sid);
  for (let i = 0; cur && i < MAX_CHAIN_DEPTH; i++) {
    if (visited.has(cur)) break; // 环护栏
    visited.add(cur);
    const parent = parentHintOf(cur, bucketSid);
    if (!parent) break;
    const nm = chainNameOf(parent);
    if (!nm) break; // 上层无名（旧记录）= 不臆造，到此为止
    segs.unshift(nm);
    cur = parent;
  }
  return segs;
}

/**
 * 后台任务行的来源链（作者令 2026-11-01 19:23 主面）。返回名字段（上层→下层）；
 * 空数组 = 解析不出（调用方回退既有显示，逐字节不变）。
 *
 * 分支判据（行内既有字段，零新协议）：
 *   - ownerName 在场 = delegate 自注册 → [上层…（嵌套 delegate 才有）, 短名]；
 *     根直派 delegate 上溯即空 ⇒ 恰 [短名]（「只显示自己」）。
 *   - origin=node/dispatcher → 自段 = originLabel（节点名 / dispatcher/<project>），
 *     上层 = 注册会话上溯（delegate 作用域节点 ⇒ delegate 短名打头）。
 *   - 子代理族会话（subagent-/subtask-/workflow-/dag-…）→ 自段 = G3/live 行名，
 *     上层 = 上溯（此前这类行误显 root 名——本链顺修归属）。
 *   - origin=device（设备通道任务）→ []（「跑在哪」轴由设备 chip 承载，本链不掺）。
 *   - 其余（根会话自开）→ [rootDisplayName()]（单段——既有显示不变，Nebula 退役
 *     批的显示名权威照旧）。
 *
 * @param {{ownerName?: string, sessionId?: string, origin?: string, originLabel?: string}} task
 * @param {string} [bucketSid] 行所在桶键（activeView.sessionId——delegate 作用域父判据）
 * @returns {string[]}
 */
export function chainForTask(task, bucketSid) {
  const t = task && typeof task === 'object' ? task : {};
  const owner = String(t.ownerName || '').trim();
  const sid = stripTeam(t.sessionId || '');
  const origin = String(t.origin || '');
  if (owner) {
    const upper = sid ? upperChainOf(sid, bucketSid) : [];
    return [...upper, owner];
  }
  if (origin === 'device') return [];
  // node/dispatcher：origin 权威字段在场为主；旧后端快照缺 origin 时按会话 id 前缀
  // 同判（bgTaskOrigin 兜底链同款判据）。自名 = originLabel → 镜像名；皆缺 = []
  //（禁把 sessionId 顶上屏——调用方回退既有显示）。
  if (origin === 'node' || origin === 'dispatcher' || /^(node|dispatcher)-/.test(sid)) {
    const self = String(t.originLabel || '').trim() || chainNameOf(sid);
    if (!self) return [];
    return [...upperChainOf(sid, bucketSid), self];
  }
  if (CHILD_SESSION_RE.test(sid)) {
    const live = liveEntryOf(sid);
    const child = childEntryOf(sid);
    const self = (child && child.name && !isGenericName(child.name, sid)) ? child.name
      : (live && !isGenericName(live.name, sid)) ? live.name
      : '';
    const upper = upperChainOf(sid, bucketSid);
    return self ? [...upper, self] : upper; // 自名缺席 = 链不完整宁缺（禁 id 上屏）
  }
  // 根会话自开（origin=nebula / 旧后端无 origin）——单段显示名，链不上溯。
  return [rootDisplayName()];
}

/** 链段 → 徽标文案（「上层 · 下层」分隔式——与现有徽标分隔风格一致）。 */
export function chainLabel(segments) {
  return (Array.isArray(segments) ? segments : [])
    .map((s) => String(s || '').trim()).filter(Boolean).join(' · ');
}
