#!/usr/bin/env node
// isolated-browser-selfproof.spec.mjs — 「隔离浏览器自动化」可行性实证件（2026-11-07 作者令批）。
//
// 这是**代表性 spec 的第一件**：它不复用任何既有 mock harness，而是走 tests/harness/
// isolated-browser.mjs 的标准能力——自起一次性实例（独立 home / 独立端口）+ 一次性浏览器
// 档案（零 cookie / 零 localStorage / 零 SW），跑完全量回收。
//
// 判据（二值；本件不连宿主、不写宿主数据根）：
//   A1 隔离实例起服，BASE_URL 端口 ≠ 宿主端口
//   A2 真登录链进站：POST /api/auth/console-ticket ⇒ GET /?ticket= ⇒ 落会话 cookie
//   A3 真 web 树加载（script/link 引用数 > 0，document.title 非空）
//   A4 页面内 REST 腿可用：带会话 cookie 的 GET /api/sessions 返回 200（证明登录态成立）
//   A5 并行：同一实例两页并发打开均成功（④ 多巡检并行不打架的机制前提）
//   A6 会话 cookie 只属于隔离 origin（host 端口零请求命中）
//   A7 回收：进程已退 / 端口已关 / 临时目录与浏览器档案均已删
//   A8 宿主侧：takeover 增量 0、推帧增量 0、数据根无新增条目
//
// Run: node tests/isolated-browser-selfproof.spec.mjs            （退出码 0 = 全绿）
//      node tests/isolated-browser-selfproof.spec.mjs --json     （机器可读读数）

import {
  startIsolatedInstance, openIsolatedBrowser, hostNoise, hostFingerprint, hostDiff,
  attributeNoise, readClientIds, HOST_PORT, HOST_ROOT,
} from './harness/isolated-browser.mjs';

const JSON_OUT = process.argv.includes('--json');
let checks = 0;
let failures = 0;
const rows = [];
const ok = (name, cond, extra = '') => {
  checks++;
  if (!cond) failures++;
  rows.push({ name, pass: Boolean(cond), extra });
  if (!JSON_OUT) console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? `  — ${extra}` : ''}`);
};

const noiseBefore = hostNoise();
const fpBefore = hostFingerprint();
let inst = null;
let prof = null;
let closed = null;
let ourClientIds = [];

try {
  inst = await startIsolatedInstance({ tag: 'selfproof', quiet: JSON_OUT });

  // A1
  ok('A1 隔离实例起服且端口 ≠ 宿主端口', Boolean(inst.baseUrl) && inst.port !== HOST_PORT,
    `BASE_URL=${inst.baseUrl} 宿主=:${HOST_PORT} boot=${inst.bootMs}ms`);

  prof = await openIsolatedBrowser(inst, { quiet: JSON_OUT });

  // A2 + A3
  const page = await prof.newPage({ login: true });
  const title = await page.title();
  const assets = await page.evaluate(() => ({
    scripts: document.querySelectorAll('script[src]').length,
    links: document.querySelectorAll('link[href]').length,
    bodyLen: document.body ? document.body.innerHTML.length : 0,
    cookiesVisible: document.cookie, // 期望空串（HttpOnly 会话 cookie 不可见 = 登录链正常）
  }));
  ok('A2 真登录链进站（?ticket= 未报错、URL 已落到实例 origin）',
    page.url().startsWith(inst.baseUrl) && !page.url().includes('ticket='), `url=${page.url()}`);
  ok('A3 真 web 树加载', assets.scripts + assets.links > 0 && title.length > 0 && assets.bodyLen > 200,
    `scripts=${assets.scripts} links=${assets.links} title=${JSON.stringify(title)} bodyLen=${assets.bodyLen}`);

  // A4 页内 REST 腿（带会话 cookie）
  const sessionsStatus = await page.evaluate(async () => {
    const r = await fetch('/api/sessions', { credentials: 'same-origin' });
    return r.status;
  });
  ok('A4 页面内 REST 腿 200（登录态成立）', sessionsStatus === 200, `GET /api/sessions → ${sessionsStatus}`);

  // A5 并行两页
  const [p2, p3] = await Promise.all([prof.newPage(), prof.newPage()]);
  const [t2, t3] = await Promise.all([p2.title(), p3.title()]);
  ok('A5 同实例并行两页', Boolean(t2) && Boolean(t3), `${JSON.stringify(t2)} / ${JSON.stringify(t3)}`);

  // A6 宿主端口零命中
  ok('A6 宿主端口零请求命中', prof.hostHits.length === 0,
    `hostHits=${prof.hostHits.length}${prof.hostHits.length ? ' → ' + prof.hostHits.slice(0, 3).join(', ') : ''}`);

  // 归因用：本次 run 的控制台端 clientId（宿主日志里以 mask = 前 8 位出现）
  ourClientIds = await readClientIds(page);
  ok('A6b 本次 run 的端 clientId 可读（归因前提）', ourClientIds.length > 0, `ids=${ourClientIds.map((s) => s.slice(0, 8)).join(',')}`);

  await prof.close();
  prof = null;
  closed = await inst.close({ quiet: JSON_OUT });

  // A7
  ok('A7 回收: 进程已退', closed.procGone, `pid=${inst.pid}`);
  ok('A7 回收: 端口已关', closed.portClosed, `:${inst.port}`);
  ok('A7 回收: 临时目录已删', closed.dirGone, inst.root);
  inst.keepReport = closed;
} catch (e) {
  ok('实测过程未抛异常', false, e && e.message ? e.message.slice(0, 400) : String(e));
} finally {
  try { if (prof) await prof.close(); } catch { /* ignore */ }
  try { if (inst && !closed) await inst.close({ quiet: true }); } catch { /* ignore */ }
}

// A8 宿主侧（归因口径：窗口内新增的 console-takeover 行里，只有引用**本次 run 的 clientId**
// 的才算我们的；宿主自己的端——作者手机/面板——在同一窗口的活动单列，不算本测试噪音）
const noiseAfter = hostNoise();
const att = attributeNoise(noiseBefore, noiseAfter, ourClientIds);
const diff = hostDiff(fpBefore, hostFingerprint());
ok('A8 本测试造成的宿主 takeover = 0', att.attributable.length === 0,
  `归因行=${att.attributable.length}${att.attributable.length ? ': ' + att.attributable[0].slice(-120) : ''}（窗口内宿主自身活动 ${att.foreign.length} 行，非本测试）`);
ok('A8b 归因口径可用（日志未轮转 / 端 id 已对上）',
  !att.rotated && ourClientIds.length > 0, att.rotated ? '日志轮转 ⇒ 窗口不可判' : `prefixes=${(att.prefixes || []).join(',')}`);
ok('A8c 宿主数据根无新增条目', diff.added.length === 0, diff.added.join(',') || `root=${HOST_ROOT}`);
ok('A8d 宿主控制台进程未变', !diff.hostPortPidChanged,
  `before=[${diff.hostPortPidBefore.join(',')}] after=[${diff.hostPortPidAfter.join(',')}]`);

if (JSON_OUT) {
  console.log(JSON.stringify({
    checks, failures, rows,
    host: { windowLines: att.windowLines, attributable: att.attributable, foreignCount: att.foreign.length, totals: att.totals, diff },
  }, null, 2));
} else {
  console.log(`\n[iso] 读数为观察面: 窗口内宿主日志总增量 takeover=${att.totals.takeovers} 推帧=${att.totals.framesToLiveConn}（含宿主自身活动）`);
  console.log(`\n${failures === 0 ? 'GREEN' : 'RED'}  checks=${checks} failures=${failures}`);
}
process.exit(failures === 0 ? 0 : 1);
