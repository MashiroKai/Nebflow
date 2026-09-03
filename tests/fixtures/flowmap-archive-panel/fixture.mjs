// fixture.mjs — Flow Map 整链归档 v3 产品 Playwright spec 的数据夹具。
//
// 数据形态 = 产品真实载荷（NodePayload.buildNodeJson 单序列化点，ProjectTypes.scala）：
//   { id, name, agent, skill, mcp, preset, status, in, out, hasWorktree, worktree,
//     result, retries, blockCount, createdAt, completedAt, ttlLeftSec, deps? }
// 与原型 snapshot-data.js（静态 mock）的关键差异：无 task 字段（链名第①级自然
// 落空、第②③级生效）、ttlLeftSec 在列、deps 条件存在。
//
// 链布局（CHAIN_BATCH_MS=120s，跨批 createdAt 间隔 ≥30min，批内秒级）：
//   批 R  T0-6h    r1(completed→r2) + r2(running,in r1)          链未齐：r1 保留主图
//   批 I  T0-5h    i1(completed→i2) + i2(running,in i1) + i3(completed,in i1)
//                  链未齐：i1/i3 保留；i1.out=i2 覆盖 i1→i2，i1→i3 走 in 代理渲染
//   批 A  T0-4h    a1(running→a2) + a2(pending,in a1)            纯活动批
//   批 X  T0-30m   x1(running,in [c4a])                          跨批引用（喂已归档链）
//   C1 3 节点 completed   T0-8h   （唯一 3 节点链；展开/成员点击样例）
//   C2 2 节点 failed 最坏 T0-7h
//   C3 2 节点 cancelled 最坏 T0-6.5h
//   C4 1 节点 completed   T0-3.5h （x1 的上游——hover 联动目标）
//   C5 1 节点 completed   T0-3h
//   C6 1 节点 completed   T0-2.5h
//   C7 1 节点 failed      T0-1.9h （最新链 → 面板置顶 + failed 徽章）
//   C8 1 节点 completed   T0-23.5h（TTL 演示链：剩余 ~30min → 「即将过期」；最老存活 → 面板末条）
//   C9 1 节点 completed   T0-25h（>24h：播种 TTL 清理即过期——徽章/面板/主图均不可见）
//
// 基线：徽章/面板条目 = 8（C1..C8）；主图可见 = 8 卡（活动 5 + 终态保留 3）；
// 面板节点合计 12。A16 整链走查：r2→R 齐徽章 9；i2→I 齐徽章 10；a1 未齐保留；
// a2→A 齐徽章 11；x1→X 齐徽章 12、主图空态。

export const PROJECT = 'alpha';
export const ROOT_SID = 'fma-root-session';

const H = 3600000;
const MIN = 60000;
const DAY = 86400000;

let seq = 0;
/** 产品载荷形态的节点构造器（真实字段集，可覆盖）。 */
export function node(over = {}) {
  seq += 1;
  return {
    id: `n-${seq}`,
    name: '节点',
    agent: 'Backend',
    skill: null,
    mcp: null,
    preset: null,
    status: 'pending',
    in: [],
    out: 'Nebula',
    deps: undefined, // 条件序列化：undefined 时不出现在载荷里
    hasWorktree: false,
    worktree: null,
    result: null,
    retries: 0,
    blockCount: 0,
    createdAt: 0,
    completedAt: null,
    ttlLeftSec: null,
    ...over,
  };
}

const T = () => Date.now();

// ── 批 R：链未齐（r1 终态保留 + r2 运行）─────────────────
export const R1 = node({
  id: 'r1', name: '诊断-登录超时', agent: 'czt-researcher', status: 'completed',
  out: 'r2', createdAt: T() - 6 * H, completedAt: T() - 5.5 * H, ttlLeftSec: (DAY / 1000) - 0.5 * H / 1000,
  result: '**定位**：上游网关超时。\n\n- 复现路径 `GET /api/slow`\n- 建议加熔断',
});
export const R2 = node({
  id: 'r2', name: '修复-登录超时', agent: 'Backend', status: 'running',
  in: ['r1'], createdAt: T() - 6 * H + 5000,
});

// ── 批 I：链未齐 + in 代理渲染（i1→i3）──────────────────
export const I1 = node({
  id: 'i1', name: '取证-构建产物', agent: 'Docs', status: 'completed',
  out: 'i2', createdAt: T() - 5 * H, completedAt: T() - 4.7 * H, ttlLeftSec: (DAY / 1000) - 4.7 * H / 1000,
  result: '构建产物清单已核对（`dist/` 12 项）。',
});
export const I2 = node({
  id: 'i2', name: '实施-构建产物', agent: 'Frontend', status: 'running',
  in: ['i1'], out: null, createdAt: T() - 5 * H + 4000,
});
export const I3 = node({
  id: 'i3', name: '验收-构建产物', agent: 'qa-frontend', status: 'completed',
  in: ['i1'], createdAt: T() - 5 * H + 8000, completedAt: T() - 4.6 * H, ttlLeftSec: (DAY / 1000) - 4.6 * H / 1000,
  result: '验收通过：产物哈希一致。',
});

// ── 批 A：纯活动 ────────────────────────────────────────
export const A1 = node({
  id: 'a1', name: '实施-面板联调', agent: 'Frontend', status: 'running',
  out: 'a2', createdAt: T() - 4 * H,
});
export const A2 = node({
  id: 'a2', name: '验收-面板联调', agent: 'qa-frontend', status: 'pending',
  in: ['a1'], createdAt: T() - 4 * H + 3000,
});

// ── 批 X：跨批引用（x1 in 已归档链成员 c4a）──────────────
export const X1 = node({
  id: 'x1', name: '修复-回归补丁', agent: 'Backend', status: 'running',
  in: ['c4a'], createdAt: T() - 30 * MIN,
});

// ── 已归档链 C1..C9 ─────────────────────────────────────
export const C1A = node({
  id: 'c1a', name: '诊断-console-404', agent: 'czt-researcher', status: 'completed',
  out: 'c1b', createdAt: T() - 8 * H, completedAt: T() - 7.6 * H, ttlLeftSec: DAY / 1000 - 7.6 * H / 1000,
  result: '404 根因：路由表缺 registry 项。',
});
export const C1B = node({
  id: 'c1b', name: '修复-console-404', agent: 'Backend', status: 'completed',
  in: ['c1a'], out: 'c1c', createdAt: T() - 8 * H + 4000, completedAt: T() - 7.55 * H, ttlLeftSec: DAY / 1000 - 7.55 * H / 1000,
  result: '已补齐路由表并回归 `console` 面板。',
});
export const C1C = node({
  id: 'c1c', name: '验收-console-404', agent: 'qa-frontend', status: 'completed',
  in: ['c1b'], createdAt: T() - 8 * H + 8000, completedAt: T() - 7.5 * H, ttlLeftSec: DAY / 1000 - 7.5 * H / 1000,
  result: '回归 **PASS**：控制台无 404。',
});
export const C2A = node({
  id: 'c2a', name: '实施-og-image-修复部署', agent: 'Frontend', status: 'completed',
  out: 'c2b', createdAt: T() - 7 * H, completedAt: T() - 6.6 * H, ttlLeftSec: DAY / 1000 - 6.6 * H / 1000,
  result: 'og-image 构建脚本已修正。',
});
export const C2B = node({
  id: 'c2b', name: '部署-og-image-修复部署', agent: 'Backend', status: 'failed',
  in: ['c2a'], createdAt: T() - 7 * H + 5000, completedAt: T() - 6.5 * H, ttlLeftSec: DAY / 1000 - 6.5 * H / 1000,
  result: '部署失败：registry 凭据过期。',
});
export const C3A = node({
  id: 'c3a', name: '实施-忘记密码误直登', agent: 'Frontend', status: 'cancelled',
  out: 'c3b', createdAt: T() - 6.5 * H, completedAt: T() - 6.2 * H, ttlLeftSec: DAY / 1000 - 6.2 * H / 1000,
  result: '方案取消：与 SSO 改造冲突。',
});
export const C3B = node({
  id: 'c3b', name: '验收-忘记密码误直登', agent: 'qa-frontend', status: 'completed',
  in: ['c3a'], createdAt: T() - 6.5 * H + 5000, completedAt: T() - 6.1 * H, ttlLeftSec: DAY / 1000 - 6.1 * H / 1000,
  result: '直登漏洞确认已由 SSO 侧关闭。',
});
export const C4A = node({
  id: 'c4a', name: '实施-Logto 邮件链', agent: 'Frontend', status: 'completed',
  createdAt: T() - 3.5 * H, completedAt: T() - 3.4 * H, ttlLeftSec: DAY / 1000 - 3.4 * H / 1000,
  result: '邮件模板接入 Logto SMTP 完成。',
});
export const C5A = node({
  id: 'c5a', name: '部署-静态资源', agent: 'Backend', status: 'completed',
  createdAt: T() - 3 * H, completedAt: T() - 2.9 * H, ttlLeftSec: DAY / 1000 - 2.9 * H / 1000,
  result: '静态资源已发布 CDN。',
});
export const C6A = node({
  id: 'c6a', name: '验收-品牌官网footer修订', agent: 'Designer', status: 'completed',
  createdAt: T() - 2.5 * H, completedAt: T() - 2.4 * H, ttlLeftSec: DAY / 1000 - 2.4 * H / 1000,
  result: 'footer 修订视觉验收通过。',
});
export const C7A = node({
  id: 'c7a', name: '修复-LogtoRequestError', agent: 'Backend', status: 'failed',
  createdAt: T() - 2 * H, completedAt: T() - 1.9 * H, ttlLeftSec: DAY / 1000 - 1.9 * H / 1000,
  result: '修复失败：上游 Logto 版本不兼容。',
});
// TTL 演示链：完成于 23.5h 前（剩余 ~30min < 60min → 「即将过期」）
export const C8A = node({
  id: 'c8a', name: '实施-归档TTL演示', agent: 'Docs', status: 'completed',
  createdAt: T() - 23.6 * H, completedAt: T() - 23.5 * H, ttlLeftSec: DAY / 1000 - 23.5 * H / 1000,
  result: '（TTL 演示）此链将于约 30 分钟后到达 24h TTL。',
});
// 过期链：完成于 25h 前（>24h → 播种清理）
export const C9A = node({
  id: 'c9a', name: '实施-过期链样例', agent: 'Docs', status: 'completed',
  createdAt: T() - 26 * H, completedAt: T() - 25 * H, ttlLeftSec: 0,
  result: '（>24h）播种 TTL 清理即过期，不应出现在面板或主图。',
});

/** 初始快照全量节点（服务端权威视图；patchServer 同步演化）。 */
export function allNodes() {
  return [R1, R2, I1, I2, I3, A1, A2, X1, C1A, C1B, C1C, C2A, C2B, C3A, C3B, C4A, C5A, C6A, C7A, C8A, C9A];
}

/** NodeList 载荷（REST 快照 + WS 帧同构）。 */
export function fmPayload(nodes) {
  return { nodes, worktrees: [], meta: { project: PROJECT, updatedAt: Date.now() } };
}
