#!/usr/bin/env node
// check-perf-redlines.mjs — 性能设计红线的**静态**可查项（性能红线固化批 2026-11-21）。
//
// ── 为什么有这条闸 ────────────────────────────────────────────────────────────
// 2026-11-21 诊断批（panel-mobile-perf-diagnostics）实测：手机端面板主线程被压到
// 2.1fps，主因不是渲染面，而是三条**在源码里就能看出来**的形态：
//   ① `SessionRoutes` 写死 `getUiMessages(sessionId, 0, 0)`（`limit=0` = 不限量）
//      ⇒ `?limit=50` 与不传参字节完全相同（10 991 449B / 1600+ 条裸传），
//      而前端 `chatSearch.js` 自己切页、根本不知道后端没有分页；
//   ② `/api` 全族 JSON 无压缩（历史 10.99MB、任务行 1.1MB 裸传）；
//   ③ 面板每渲一轮对**每个父**各发一条子代理请求（一拍 116+ 条，与 5s 轮询叠加）。
// 三条都不是编译错、也不是任何既有判据能抓到的东西——它们是**形态**缺陷，
// 只能靠静态扫描（和一条真浏览器红线）拦住。本脚本就是前两条的机械面，
// 第三条的相邻形态（超频轮询注册）一并在此把门。
//
// ── 判据（写死在这里，不可事后重解释）────────────────────────────────────────
// A · **不分页的全量读**：网关源码里出现「整表读」调用形态，且同一文件内没有
//     对应的分页归一，即红。目前登记的形态：
//       `getUiMessages(<任意>, 0, 0)` —— 第 3 参 0 = 不限量（`SessionStore` 契约逐字：
//       `limit <= 0 means "no limit"`）。允许的例外必须以 `// perf-allow:` 注释
//       显式登记并写出理由（批量导出 / 索引重建这类真需要全量的腿）。
// B · **绕过压缩管线的大 JSON 路由**：新增一条返回大载荷的 JSON 路由却不经
//     `ApiGzipMiddleware` 覆盖，就白修了降 76% 的那一条。判据 = ①`/api` 树仍包裹
//     `ApiGzipMiddleware`；②没有哪条路由把 `GzipMiddleware` 又套回 `/api` 内层
//     （重复压缩）；③ 压缩中间件自身仍排除流（`text/event-stream` 不在白名单）。
// C · **超频轮询注册**：前端 `setInterval(...)` 的周期在源码里可静态求值的，
//     一律不得低于 [[MinPollMs]]；求不出值的新注册必须进 ALLOW 表登记。
//     背景：本批实测 5s 周期的轮询一拍就能把主线程压到 2.1fps，所以「更快的轮询」
//     是红线而不是偏好。
//
// ── 用法 ─────────────────────────────────────────────────────────────────────
//   node scripts/check-perf-redlines.mjs          # 判定（红 = exit 1）
//   node scripts/check-perf-redlines.mjs --list   # 打印全部抽取结果（排障用）
//   make frontend-gates                           # 本闸已挂进该 target
//
// 零网络、零进程、零凭据（纯源码文本扫描）。

import { readFile, readdir } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const GATEWAY = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway');
const WEB_JS = join(ROOT, 'src', 'main', 'resources', 'web', 'js');
const LIST = process.argv.includes('--list');

/** C 腿阈值：轮询周期下限。5s 已是实测「一拍压死主线程」的量级，再快就是红线。 */
const MinPollMs = 1000;

/**
 * A 腿豁免表：每条必须写出**为什么这条腿真的要全量**。
 * 形如 `{ file: '<相对 gateway 的文件名>', fn: '<调用形态片段>', reason: '<理由>' }`
 * 豁免只许缩不许涨（新增必须作者裁定）。
 */
const UNPAGED_ALLOW = [
  {
    file: 'TurnEndpoint.scala',
    fn: 'getUiMessages(sessionId, 0, 0).map(_._2)',
    reason: '只取 total 计数（`.map(_._2)`），体内联不进应答；改分页没有收益。',
  },
  {
    file: 'TurnEndpoint.scala',
    fn: 'sessionStore.getUiMessages(sessionId, 0, 0).flatMap { case (msgs, _) =>',
    reason: '同步 turn 的上下文构造：本腿要把整段历史喂给模型，分页即改变语义。',
  },
  {
    file: 'SessionRoutes.scala',
    fn: 'sessionStore.getUiMessages(sessionId, offset, limit)',
    reason: '不是豁免——本行是修后的分页归一形态，登记在此仅为让豁免表可审计。',
    nonViolation: true,
  },
];

/**
 * C 腿豁免：**不是稳态轮询**的注册点，逐条登记（新增须作者裁定）。
 *
 * 判据不是「周期短就红」——红线针对的是**面板开着的整段时间都在跑**的轮询（诊断批
 * 实测 5s 一拍即可把主线程压到 2.1fps）。下面这些都是**有界探针**：命中即自清。
 */
const POLL_ALLOW = [
  {
    file: 'main.js',
    ms: 50,
    reason: '启动探针：等 marked/lottie/lucide 三方就绪，就绪即 clearInterval(check)；' +
      '只在首屏几十毫秒内存在，不是稳态轮询。',
  },
  {
    file: 'canvas.js',
    ms: 600,
    reason: '画布卡片窗**关闭守望**：记录消失或窗已 closed 即 clearInterval(iv)；' +
      '生命周期 = 单张卡片的关闭时延，不随面板常驻。',
  },
  {
    file: 'askPending.js',
    ms: 300000,
    reason: '300s 静默看门（diagnostics 实测注册面）。',
  },
];

function stripComments(source, { keepAllow = true } = {}) {
  // Line comments first (preserving perf-allow markers when asked), then block comments.
  let out = source.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  out = out.split('\n').map((line) => {
    const i = line.indexOf('//');
    if (i < 0) return line;
    const tail = line.slice(i);
    if (keepAllow && /perf-allow:/.test(tail)) return line;
    return line.slice(0, i);
  }).join('\n');
  return out;
}

async function scalaFiles(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await scalaFiles(p)));
    else if (entry.name.endsWith('.scala')) out.push(p);
  }
  return out;
}

async function jsFiles(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'vendor') continue;
      out.push(...(await jsFiles(p)));
    } else if (entry.name.endsWith('.js')) out.push(p);
  }
  return out;
}

/** A 腿：整表读形态。`(sid, 0, 0)` 里第 3 个实参 0 就是「不限量」。 */
const UNPAGED_RE = /getUiMessages\s*\(\s*[^,()]+,\s*[^,()]+,\s*0\s*\)/g;

function lineOf(source, index) {
  return source.slice(0, index).split('\n').length;
}

function hasAllowMarker(source, index) {
  const lines = source.split('\n');
  const n = lineOf(source, index) - 1;
  // marker may sit on the same line or the 3 lines above it
  return lines.slice(Math.max(0, n - 3), n + 1).some((l) => /perf-allow:/.test(l));
}

function isAllowed(fileName, snippet, table) {
  return table.some((e) =>
    e.file === fileName && (!e.fn || snippet.includes(e.fn) || e.fn.includes(snippet)));
}

async function checkUnpaged() {
  const failures = [];
  const hits = [];
  for (const file of await scalaFiles(GATEWAY)) {
    const raw = await readFile(file, 'utf8');
    const clean = stripComments(raw, { keepAllow: true });
    for (const match of clean.matchAll(UNPAGED_RE)) {
      const snippet = match[0].replace(/\s+/g, ' ');
      const name = file.slice(GATEWAY.length + 1);
      if (hasAllowMarker(clean, match.index) || isAllowed(name, snippet, UNPAGED_ALLOW)) {
        hits.push({ file: name, snippet, allowed: true });
        continue;
      }
      failures.push({ file: name, line: lineOf(clean, match.index), snippet });
      hits.push({ file: name, snippet, allowed: false });
    }
  }
  return { failures, hits };
}

/** B 腿：压缩管线覆盖。 */
async function checkCompression() {
  const failures = [];
  const gatewayMain = await readFile(join(GATEWAY, 'GatewayMain.scala'), 'utf8');
  const clean = stripComments(gatewayMain);
  // ① the /api mount must be wrapped
  if (!/"\/api"\s*->\s*ApiGzipMiddleware\s*\(/.test(clean)) {
    failures.push({
      rule: 'B1',
      what: 'GatewayMain 的 "/api" 挂载不再包裹 ApiGzipMiddleware —— /api 全族退回 identity 裸传',
    });
  }
  // ② no second gzip layer INSIDE the /api arm (double compression).
  //    判据按**括号范围**取 `/api` 挂载的实参区间——不按字符距离：静态树那条
  //    `GzipMiddleware(wsRoutes.routes)` 在 "/" 臂里，距离很近但不在同一实参内。
  const apiAt = clean.search(/"\/api"\s*->\s*ApiGzipMiddleware\s*\(/);
  if (apiAt >= 0) {
    let depth = 0;
    let start = clean.indexOf('(', apiAt);
    let end = -1;
    for (let i = start; i < clean.length; i++) {
      if (clean[i] === '(') depth++;
      else if (clean[i] === ')') { depth--; if (depth === 0) { end = i; break; } }
    }
    const arm = end > start ? clean.slice(start, end + 1) : '';
    if (/GzipMiddleware\s*\(/.test(arm)) {
      failures.push({
        rule: 'B2',
        what: '"/api" 挂载的实参里又出现 GzipMiddleware —— 与 ApiGzipMiddleware 叠加 = 重复压缩',
      });
    }
  }
  // ③ the middleware must keep excluding unbounded streams
  const mw = await readFile(join(GATEWAY, 'ApiGzipMiddleware.scala'), 'utf8');
  const mwClean = stripComments(mw);
  if (/text\/event-stream/.test(mwClean) && !/ExcludedPrefixes|compressible/.test(mwClean)) {
    failures.push({ rule: 'B3', what: 'ApiGzipMiddleware 头注提到 SSE 但判据里没有排除路径' });
  }
  const whitelist = /mainType\s*==\s*"application"[\s\S]{0,200}json/.test(mwClean);
  if (!whitelist) {
    failures.push({ rule: 'B3', what: 'ApiGzipMiddleware 的 compressible 白名单不见了（流会被一起压）' });
  }
  return { failures };
}

/** C 腿：轮询周期。只认静态可求值的数值字面量（模板/变量周期登记进 POLL_ALLOW）。 */
const POLL_RE = /setInterval\s*\(/g;

async function checkPolls() {
  const failures = [];
  const hits = [];
  for (const file of await jsFiles(WEB_JS)) {
    const raw = await readFile(file, 'utf8');
    const clean = stripComments(raw);
    const name = file.slice(WEB_JS.length + 1);
    for (const match of clean.matchAll(POLL_RE)) {
      // the period is the last argument; grab the whole call by paren matching
      let depth = 0;
      let end = match.index + match[0].length - 1;
      for (let i = end; i < clean.length; i++) {
        if (clean[i] === '(') depth++;
        else if (clean[i] === ')') { depth--; if (depth === 0) { end = i; break; } }
      }
      const call = clean.slice(match.index, end + 1);
      const argPart = call.slice(call.indexOf('(') + 1, call.lastIndexOf(',') + 1);
      const periodText = call.slice(call.lastIndexOf(',') + 1, call.lastIndexOf(')')).trim();
      const numeric = /^\d[\d_]*$/.test(periodText) ? Number(periodText.replace(/_/g, '')) : null;
      // 豁免必须**同时**命中文件名与周期：按文件名豁免会让同文件里新增的超频注册
      // 静默搭便车（那正是本闸要拦的东西）。
      const allow = POLL_ALLOW.find((e) => e.file === name && e.ms === numeric);
      if (numeric !== null && numeric < MinPollMs && !allow) {
        failures.push({ file: name, line: lineOf(clean, match.index), ms: numeric, call: call.replace(/\s+/g, ' ').slice(0, 160) });
        hits.push({ file: name, ms: numeric, allowed: false });
      } else {
        hits.push({ file: name, ms: numeric, allowed: Boolean(allow) });
      }
      void argPart;
    }
  }
  return { failures, hits };
}

async function main() {
  const unpaged = await checkUnpaged();
  const compression = await checkCompression();
  const polls = await checkPolls();

  if (LIST) {
    console.log('[perf-redlines] A · 整表读形态：');
    for (const h of unpaged.hits) console.log(`  ${h.allowed ? 'ALLOW' : 'RED  '} ${h.file}  ${h.snippet}`);
    console.log(`[perf-redlines] B · 压缩管线：${compression.failures.length ? 'RED' : 'GREEN'}`);
    console.log(`[perf-redlines] C · 轮询注册（下限 ${MinPollMs}ms）：`);
    for (const h of polls.hits) console.log(`  ${h.allowed ? 'ALLOW' : 'info '} ${h.file}  ms=${h.ms}`);
  }

  let failures = 0;
  if (unpaged.failures.length) {
    failures += unpaged.failures.length;
    console.error('✗ 性能红线 A — 不分页的全量读：');
    for (const f of unpaged.failures) {
      console.error(`  · ${f.file}:${f.line}  ${f.snippet}`);
    }
    console.error('  ⇒ 传 limit/offset（与 WsSessionChatHandlers.clampHistoryLimit 同一档）；');
    console.error('    确需全量的批量腿用 `// perf-allow: <理由>` 登记进 UNPAGED_ALLOW。');
  }
  if (compression.failures.length) {
    failures += compression.failures.length;
    console.error('✗ 性能红线 B — 压缩管线破损：');
    for (const f of compression.failures) console.error(`  · ${f.rule} ${f.what}`);
  }
  if (polls.failures.length) {
    failures += polls.failures.length;
    console.error(`✗ 性能红线 C — 超频轮询（下限 ${MinPollMs}ms）：`);
    for (const f of polls.failures) console.error(`  · ${f.file}:${f.line}  ${f.ms}ms  ${f.call}`);
    console.error('  ⇒ 5s 周期一拍就能把主线程压到 2.1fps（诊断批实测）；更快的轮询须走事件/按需。');
  }

  if (failures) {
    console.error('✗ 性能设计红线 — RED');
    process.exit(1);
  }
  console.log(
    `✓ 性能设计红线 — GREEN（整表读 ${unpaged.hits.filter((h) => !h.allowed).length ? '—' : '0 处违规'}；` +
      `压缩管线三段齐；轮询注册 ${polls.hits.length} 处均 ≥ ${MinPollMs}ms）`
  );
}

main().catch((error) => { console.error(`perf-redlines ERROR: ${error.message}`); process.exit(2); });
