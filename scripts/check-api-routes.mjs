#!/usr/bin/env node
// check-api-routes.mjs — 路由存在性哨兵（empty-state-must-not-lie 批 · 件三，
// 2026-11-05）。
//
// ── 为什么有这条闸 ────────────────────────────────────────────────
// 2026-11-05 事故：`GET /api/conversations` 的 route case 被 `6c1feea6d` 在同一锚点
// 误删（`SocialRoutes.scala`），网关回 404；前端 `friendsApi.getConversations()`
// 拿到 404 后被 `messages.js` 的 catch 吞掉 ⇒ 消息面板渲染「暂无会话」。**没有任何
// 断言发现「后端少了一条前端正在调用的路由」** —— 后端编译绿、前端类型绿、测试
// 绿，事故靠作者肉眼发现。
//
// 本闸补的就是这条缺的断言：**静态对照前端实际调用的 API 路径集合与网关路由注册
// 面，前端调了但后端没有 ⇒ 红**。零网络、零进程、零凭据（纯源码文本扫描）。
//
// ── 判据（写死在这里，不可事后重解释）────────────────────────────
// ① 前端面 = `src/main/resources/web/js/**/*.js`（语法去注释后）里所有以 `/api/`
//    开头的**字符串字面量**（含模板字面量）。模板插值 `${…}` 归一成 `:`（段内
//    插值如 `receipts${qs}` 归一成 `receipts`，插值段整段就是变量时归一成 `:`
//    通配段）。注释里的路径**不算调用点**（去注释后不再参与 —— 诊断报告里有一堆
//    「注释里提到但没人调用」的路径，混进来只会制造假红）。
// ② 后端面 = `src/main/scala/nebflow/gateway/**/*.scala` 里的路由注册。目前仓内
//    只有四种注册形态，逐一抽取（新形态出现 ⇒ 本闸要么抽到、要么在下面的抽取段
//    显式登记，禁「抽不到就当没有」）：
//      形态① `case req @ GET -> Root / "a" / id / "b"` —— 主形态（288 处量级）；
//      形态② `pathInfo.renderString.startsWith("/prefix/")` —— 前缀面（静态资源族）；
//      形态③ `(GET, List("entries"))` 表 —— 代理族子路径表（PerceptionRoutes）；
//      形态④ `List("mail", "drafts", _*)` —— 同族通配子路径。
//    段里是标识符 ⇒ `:` 通配；字面量 ⇒ 字面量。**全段皆变量**的注册（`Root / id`，
//    静态文件兜底）不参与对照：它匹配一切，留着会把整张表变成永绿。
// ③ 挂载前缀 = `/api`（`GatewayMain.scala:2107` `Router("/api" -> …)`）；注册里已写
//    `"api"` 段的（`NfFileRoutes` 这类挂在根树上的）按原样。
// ④ 匹配 = 段数相同 + 逐段相等或任一侧为 `:` 通配；`**` 结尾的前缀面按前缀匹配。
//    🔴 判据只到**路径**粒度，不判方法：事故类是「整条 path 消失」，而从 `fetch(…)`
//    的写法里可靠地抠出 method 需要解析调用参数（`method:` 选项 / `req('M', p)`
//    两种混用），误抽即假红。方法粒度用下面的 `CRITICAL` 表补（逐条写死）。
// ⑤ `CRITICAL` = 关键路由白名单（会话列表 / 历史 / 附件 / 上传 / friends / groups /
//    sessions 族），逐条**精确**断言后端存在（method + 段形态）。它是 ①②③④ 抽取面
//    失效时的第二道保险：抽取规则被改坏 ⇒ 这张表照样红。
// ⑥ `EXTERNAL` = **不由本网关应答**的路径（relay / portal 宿主面）。每条必须带理由
//    与证据锚点；**名单过期也红**（前端已无人调用该路径 ⇒ 豁免是陈的，删掉它）。
//    这条反向判据是「豁免不得变成 baseline」的机械执行：名单只能缩，不能悄悄涨。
//
// ── 用法 ─────────────────────────────────────────────────────────
//   node scripts/check-api-routes.mjs          # 判定（红 = exit 1）
//   node scripts/check-api-routes.mjs --list   # 打印两侧抽取结果（排障用）
//   make frontend-gates                        # 本闸已挂进该 target
//   CI: .github/workflows/ci.yml「API route existence」步（同一脚本，单一真源）

import { readFile, readdir } from 'node:fs/promises';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB_JS = join(ROOT, 'src', 'main', 'resources', 'web', 'js');
const GATEWAY = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway');
const VERBOSE = process.argv.includes('--list');

/** `EXTERNAL`：本网关**不**应答、由 relay/portal 宿主应答的路径。
 *  🔴 每条都必须能在证据锚点上读到「谁应答它」；写不出锚点 = 不许进本表。 */
const EXTERNAL = [
  {
    path: '/api/auth/login',
    reason: 'relay(门户)宿主的登录腿：页面经 relay 加载时才存在，本网关无此路由',
    evidence: 'web/js/ws.js:664-692（「PUBLIC /api/auth/login-config」→ 跳 relay 自己的 /api/auth/login）',
  },
  {
    path: '/api/auth/login-config',
    reason: '同上：relay 登录腿的配置探测面',
    evidence: 'web/js/ws.js:689 `fetch(\'/api/auth/login-config\')`，注释 :664 逐字「PUBLIC」',
  },
  {
    path: '/api/portal/status',
    reason: 'portal(consoletunnel)宿主面：直连腿不存在',
    evidence: 'web/js/cardwinDirect.js:110-115（「直连腿页面没有 /api/portal 面（404 短路）」）、web/js/ws.js:682',
  },
  {
    path: '/api/portal/console-ticket',
    reason: '同上：portal 宿主的控制台票据铸造面',
    evidence: 'web/js/cardwinDirect.js:121、web/js/ws.js:700',
  },
];

/** `CRITICAL`：关键路由白名单（精确 method + path 段形态；`:` = 通配段）。
 *  判据 = 后端注册里必须存在一条同形路由。条目消失需说明理由（条目没了 =
 *  该族从此无人守）。 */
const CRITICAL = [
  // 会话列表 / 历史（本次事故的正位）
  ['GET', '/api/conversations'],
  ['GET', '/api/conversations/:id/messages'],
  ['POST', '/api/conversations/:id/read'],
  ['GET', '/api/conversations/:id/receipts'],
  // 附件
  ['POST', '/api/attachments'],
  ['POST', '/api/attachments/preview'],
  ['POST', '/api/attachments/:id/cancel'],
  // 下载 = `GET /api/friends/attachments/{id}`（鉴权下载腿；**不是**
  // `/api/attachments/{id}` —— 后者只承接上传/预览/取消三面）
  ['GET', '/api/friends/attachments/:id'],
  // 上传（好友消息附件）
  ['POST', '/api/friends/:id/messages'],
  // friends 族
  ['GET', '/api/friends'],
  ['GET', '/api/friends/requests'],
  ['POST', '/api/friends/requests'],
  ['POST', '/api/friends/requests/:id/accept'],
  ['POST', '/api/friends/requests/:id/decline'],
  ['DELETE', '/api/friends/:id'],
  // groups 族
  ['GET', '/api/groups'],
  ['POST', '/api/groups'],
  ['GET', '/api/groups/invites'],
  ['POST', '/api/groups/:id/invites'],
  ['GET', '/api/groups/:id/members'],
  ['POST', '/api/groups/:id/members/:id/kick'],
  ['PUT', '/api/groups/:id/members/:id/role'],
  ['POST', '/api/groups/:id/messages'],
  ['PUT', '/api/groups/:id/title'],
  // sessions 族
  ['GET', '/api/sessions'],
  ['GET', '/api/sessions/:id/history'],
  // profile 族（profile-sync 批 2026-11-06）：描述跨设备收敛的**只读状态出口**
  // （前端徽标读它）。先注册后端、前端随后消费 ⇒ 本条是「徽标腿不会因后端掉路由
  // 而静默空白」的第二道保险。
  ['GET', '/api/profile/sync-status'],
];

// ── 工具 ───────────────────────────────────────────────────────────

/** 递归列出目录下指定后缀的文件。 */
async function walk(dir, ext) {
  const out = [];
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...(await walk(p, ext)));
    else if (e.name.endsWith(ext)) out.push(p);
  }
  return out;
}

/** 语法去注释（字符串/模板字面量感知：`//` `/*` 出现在字面量里不得当注释）。
 *  JS 与 Scala 的注释语法在这两种形态上一致 ⇒ 同一实现服务两侧。 */
function stripComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  let quote = null;
  while (i < n) {
    const c = src[i];
    const d = src[i + 1];
    if (quote) {
      out += c;
      if (c === '\\') {
        out += d ?? '';
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      i++;
      continue;
    }
    if (c === "'" || c === '"' || c === '`') {
      quote = c;
      out += c;
      i++;
      continue;
    }
    if (c === '/' && d === '/') {
      while (i < n && src[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && d === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++;
      i += 2;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/** 抽取一段源码里的**模板感知**字符串字面量（`\u0000` = 插值占位）。 */
function stringLiterals(src) {
  const out = [];
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    const q = c === '"' || c === "'" || c === '`' ? c : null;
    if (!q) {
      i++;
      continue;
    }
    let j = i + 1;
    let body = '';
    while (j < n) {
      const ch = src[j];
      if (ch === '\\') {
        body += src[j + 1] ?? '';
        j += 2;
        continue;
      }
      if (q === '`' && ch === '$' && src[j + 1] === '{') {
        // 插值体：跳到配对的 `}`（内部可能还有字符串 / 嵌套花括号）
        let k = j + 2;
        let depth = 1;
        while (k < n && depth > 0) {
          const ck = src[k];
          if (ck === '\\') {
            k += 2;
            continue;
          }
          if (ck === "'" || ck === '"' || ck === '`') {
            const cq = ck;
            k++;
            while (k < n && src[k] !== cq) k += src[k] === '\\' ? 2 : 1;
            k++;
            continue;
          }
          if (ck === '{') depth++;
          else if (ck === '}') depth--;
          k++;
        }
        body += '\u0000';
        j = k;
        continue;
      }
      if (ch === q) {
        j++;
        break;
      }
      if (ch === '\n' && q !== '`') break; // 未闭合的单行串：放弃
      body += ch;
      j++;
    }
    out.push(body);
    i = j;
  }
  return out;
}

/** 路径归一：先砍 query，再按段把插值占位折成 `:` / 段内后缀。 */
function normalizePath(raw) {
  const s = raw.split('?')[0];
  const segs = s
    .split('/')
    .filter(Boolean)
    .map((seg) => {
      if (!seg.includes('\u0000')) return decodeURIComponent(seg);
      if (seg.startsWith('\u0000')) return ':';
      // 段内插值后缀（`receipts␀`）= 查询串拼接 ⇒ 只留字面前半段
      return decodeURIComponent(seg.replace(/\u0000/g, '')) || ':';
    });
  return '/' + segs.join('/');
}

/** 后端段序列 → 全路径（`api` 首段已在注册里写了 ⇒ 原样，否则补 `/api` 挂载前缀）。 */
function fullPath(segs) {
  return segs[0] === 'api' ? '/' + segs.join('/') : '/api/' + segs.join('/');
}

// ── 抽取：前端 ─────────────────────────────────────────────────────

const frontend = new Map(); // path -> Set<相对路径>
for (const file of await walk(WEB_JS, '.js')) {
  const rel = relative(ROOT, file);
  const src = stripComments(await readFile(file, 'utf8'));
  for (const lit of stringLiterals(src)) {
    if (!lit.startsWith('/api/')) continue;
    const p = normalizePath(lit);
    if (p === '/api') continue;
    if (!frontend.has(p)) frontend.set(p, new Set());
    frontend.get(p).add(rel);
  }
}

// ── 抽取：后端 ─────────────────────────────────────────────────────

const backend = []; // { method, path, file }
for (const file of await walk(GATEWAY, '.scala')) {
  const rel = relative(ROOT, file);
  const src = stripComments(await readFile(file, 'utf8'));

  // 形态①：`case … METHOD -> Root / "a" / b / "c" =>`
  const caseRe = /(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD)\s*->\s*Root\s*((?:\/\s*(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)\s*)+)/g;
  for (const m of src.matchAll(caseRe)) {
    const segs = [...m[2].matchAll(/\s*\/\s*("[^"]+"|[A-Za-z_][A-Za-z0-9_]*)/g)].map((x) =>
      x[1].startsWith('"') ? x[1].slice(1, -1) : ':'
    );
    if (segs.every((s) => s === ':')) continue; // 全变量 = 静态兜底面，不参与对照
    backend.push({ method: m[1], path: fullPath(segs), file: rel });
  }

  // 形态②：`startsWith("/prefix/")` 前缀面
  for (const m of src.matchAll(/startsWith\("(\/[^"]*)"\)/g)) {
    const segs = m[1].split('/').filter(Boolean);
    if (!segs.length) continue;
    backend.push({ method: '*', path: fullPath([...segs, '**']), file: rel });
  }

  // 形态③：`(METHOD, List("a", "b"))` 代理子路径表（PerceptionRoutes 族）
  for (const m of src.matchAll(/\((GET|POST|PUT|DELETE|PATCH),\s*List\(([^)]*)\)\)/g)) {
    const sub = [...m[2].matchAll(/"([^"]*)"/g)].map((x) => x[1]).filter(Boolean);
    if (!sub.length) continue;
    backend.push({ method: m[1], path: fullPath(['perception', ...sub]), file: rel });
  }

  // 形态④：同一文件里的 `List("a", "b", _*)` 通配族（PerceptionRoutes 的 drafts 族）
  if (src.includes('"perception" :: rest')) {
    for (const m of src.matchAll(/List\(((?:"[^"]*",\s*)+)_\*\)/g)) {
      const sub = [...m[1].matchAll(/"([^"]*)"/g)].map((x) => x[1]).filter(Boolean);
      if (!sub.length) continue;
      backend.push({ method: '*', path: fullPath(['perception', ...sub, '**']), file: rel });
    }
  }
}

// ── 匹配 ───────────────────────────────────────────────────────────

function segmentsOf(p) {
  return p.split('/').filter(Boolean);
}

/** 一条后端注册路径（含 `**` 前缀族）是否覆盖某前端路径形态（段级、`:` 通配）。 */
function covers(bePath, fePath) {
  const fes = segmentsOf(fePath);
  const bes = segmentsOf(bePath);
  if (bes[bes.length - 1] === '**') {
    const head = bes.slice(0, -1);
    if (head.length > fes.length) return false;
    return head.every((s, i) => s === ':' || fes[i] === ':' || s === fes[i]);
  }
  if (bes.length !== fes.length) return false;
  return bes.every((s, i) => s === ':' || fes[i] === ':' || s === fes[i]);
}

/** 前端路径是否被某条后端注册覆盖（返回命中的注册，或 null）。 */
function coveredBy(fePath) {
  for (const be of backend) if (covers(be.path, fePath)) return be;
  return null;
}

/** `CRITICAL` 一条是否命中（method 精确 + 段形态精确，`:` 通配）。 */
function criticalHit([method, path]) {
  const want = segmentsOf(path);
  return (
    backend.find((be) => {
      if (be.method !== method) return false;
      const got = segmentsOf(be.path);
      if (got.length !== want.length) return false;
      return got.every((s, i) => s === ':' || want[i] === ':' || s === want[i]);
    }) || null
  );
}

// ── 判定 ───────────────────────────────────────────────────────────

// `--emit-paths`：把本脚本抽出的前端调用面（唯一真源）交给运行期哨兵
// `scripts/check-api-routes-live.mjs`——两个哨兵共用**同一份**清单，绝不各抄一套
// （静态面判「源码里注册了没」，运行期面判「启动后真的答不答」，但「要判哪些路径、
// 用什么 method」只有这里一处算）。
//
// 每条给 `{ path, probes: [{ path, methods }] }`：
//  - `path` = 前端调用**形态**（`:` 是模板插值位）；
//  - `probes` = 该形态 × 覆盖它的后端注册，**逐条落成可打的具体路径**。
//    为什么不直接拿 `probe-id` 填空：前端把一段模板放在后端要求**字面量**的位置上
//    （实测三类：`/invites/${id}/${action}` 的 accept|decline、`/plugins/${n}/${what}`
//    的 source|approve|revoke、`/social/channels/${ch}/scan-bind/*` 的 feishu|weixin-ilink），
//    随便填空必然 404 ⇒ 本体判据当场被自己污染成假红。正确做法是**用后端注册里的
//    字面量去落位**：某条注册在这段是字面量就照它写，是变量才填空值。
//  - 一条形态 P 的判据 = 「它的任一 probe 非 404」（前端运行期只会走其中一种字面量）。
//  - 豁免表（EXTERNAL）里的路径是**外部服务**（neblink/门户），沙箱实例没有它，
//    打了只会假红 ⇒ 不进运行期清单；静态面照样盯着它们是否过期。
if (process.argv.includes('--emit-paths')) {
  const externalPaths = new Set(EXTERNAL.map((e) => e.path));

  /** 用后端注册的字面量落位，生成一条可打的具体路径。 */
  const concretize = (fePath, bePath) => {
    const fes = segmentsOf(fePath);
    let bes = segmentsOf(bePath);
    if (bes[bes.length - 1] === '**') bes = bes.slice(0, -1);
    const out = [];
    for (let i = 0; i < fes.length; i++) {
      const b = bes[i];
      if (b !== undefined && b !== ':') out.push(b); // 后端字面量优先（模板在此必须落成它）
      else if (fes[i] !== ':') out.push(fes[i]);
      else out.push('probe-id');
    }
    return '/' + out.join('/');
  };

  const emitted = [...frontend.keys()]
    .filter((p) => !externalPaths.has(p))
    .sort()
    .map((path) => {
      const probes = new Map(); // 具体路径 -> Set<method>
      for (const be of backend) {
        if (!covers(be.path, path)) continue;
        const concretePath = concretize(path, be.path);
        if (!probes.has(concretePath)) probes.set(concretePath, new Set());
        probes.get(concretePath).add(be.method === '*' ? 'GET' : be.method);
      }
      return {
        path,
        probes: probes.size
          ? [...probes.entries()]
              .map(([p, ms]) => ({ path: p, methods: [...ms].sort() }))
              .sort((a, b) => a.path.localeCompare(b.path))
          : // 静态面判「没有承接」时，运行期腿**仍然要自己打一遍**，不能因为
            // 「静态面已经红了」就跳过探测 —— 两个哨兵必须各自独立成立：运行期这条
            // 的读数是「实例上真的 404」，而不是对静态结论的复述。模板位一律填
            // `probe-id`，method 放开到全部方法族（不知道是哪个 method ⇒ 都试）。
            [
              {
                path: concretize(path, path),
                methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
              },
            ],
      };
    });

  // 管道写出必须等 flush 再退：`console.log` 到 pipe 是异步写，紧跟
  // `process.exit(0)` 会把 ~8KB 之后的字节截掉（实测：下游收到 position 8192 处 EOF，
  // 报 `Unexpected end of JSON input`）。顶层 await 在本文件可用（上面已用顶层 await）。
  await new Promise((done) => process.stdout.write(JSON.stringify(emitted, null, 2) + '\n', done));
  process.exit(0);
}

const failures = [];
const externalPaths = new Set(EXTERNAL.map((e) => e.path));

// ① 前端调用面无后端承接（豁免表里的除外）
const orphan = [];
for (const [p, files] of [...frontend].sort()) {
  if (externalPaths.has(p)) continue;
  if (coveredBy(p)) continue;
  orphan.push({ p, files: [...files] });
}

// ② 豁免表过期（前端已无人调用 ⇒ 陈了）
const staleExternal = EXTERNAL.filter((e) => !frontend.has(e.path));

// ③ 关键路由白名单
const missingCritical = CRITICAL.filter((c) => !criticalHit(c));

if (VERBOSE) {
  console.log(`[api-routes] 前端 /api 路径形态 ${frontend.size} 条：`);
  for (const p of [...frontend.keys()].sort()) console.log(`  FE  ${p}`);
  const uniq = [...new Set(backend.map((b) => `${b.method} ${b.path}`))].sort();
  console.log(`[api-routes] 后端路由注册 ${backend.length} 条（去重 ${uniq.length}）：`);
  for (const k of uniq) console.log(`  BE  ${k}`);
}

if (orphan.length) {
  failures.push(
    `${orphan.length} 条前端路径在后端没有承接（前端调了但后端没注册）：\n` +
      orphan.map((o) => `  · ${o.p}   ← ${o.files.join(', ')}`).join('\n')
  );
}
if (staleExternal.length) {
  failures.push(
    `${staleExternal.length} 条豁免已过期（前端已无人调用该路径）：\n` +
      staleExternal.map((e) => `  · ${e.path}（${e.evidence}）`).join('\n') +
      '\n  ⇒ 从脚本的 EXTERNAL 表删掉它：豁免只能缩，不能养老鼠洞。'
  );
}
if (missingCritical.length) {
  failures.push(
    `${missingCritical.length} 条关键路由在后端不存在：\n` +
      missingCritical.map(([m, p]) => `  · ${m} ${p}`).join('\n')
  );
}

if (failures.length) {
  console.error('✗ API route existence — RED');
  for (const f of failures) console.error(f);
  process.exit(1);
}

console.log(
  `✓ API route existence — GREEN（前端 ${frontend.size} 条 /api 路径全部有后端承接；` +
    `豁免 ${EXTERNAL.length} 条全在执行中；关键路由 ${CRITICAL.length} 条全在；` +
    `后端注册 ${backend.length} 条）`
);
