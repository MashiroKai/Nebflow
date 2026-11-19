// session-gate.spec.mjs — 同源鉴权拒绝统一可见面验收（#520 取证批；稳定性批
// 2026-10-26 换代：静默重座优先 + 1h 保活拍）。
//
// 背景：relay 源控制台的 15min 会话过期后，每个 same-origin /api 被 relay 门
// 裸 403（不落隧道帧），WS 却照常活着 ⇒ 「图片点了零反应」的静默根。
// 本件验收 sessionGate 的契约：
//   ① nfTicket 铸票 401/403 ⇒ **静默退避**（authgate 批 2026-10-22 作者令：铸票
//      401/403 不再拉 sessionGate —— 修「上滑历史铸票一发 401 就整页重登」的登出级
//      bug；防复活钉 = 铸票 403 零横幅零 reload）；
//   ①b 统一入口 noteAuthRejected ⇒ **静默重座优先**（稳定性批 2026-10-26）：
//      重座腿（ws.js attemptSilentReseat）不可用/被拒 ⇒ 可见横幅 + 自愈 reload
//      腿被拉动（测试缝代真导航；触发面读数随 source 属性）；
//   ①c 重座可用（返回 true=正在导航）⇒ 零横幅零 reload——「会话仍有效时无感
//      恢复，真失效才引导重登」；
//   ①d 重座悬挂（探链不落）⇒ 8s 封顶竞速后照常横幅 + reload（迟到好过永不）；
//   ② 同页幂等：后续 401/403 不叠横幅、不重复 reload；
//   ③ 瞬态失败（5xx）不触发（保持既有静默降级面）；
//   ④ 跨 reload 冷却（sessionStorage 60s）：真 reload 后冷却窗内的 403 不再
//      横幅不再 reload（防连环刷新；armer = 统一入口）。
//
// 判据换代注记（2026-10-23 spec-hygiene 批）：原 ①「铸票 403 ⇒ 横幅」钉的是
// authgate 批前的旧漏斗，该漏斗已被作者令拆除（nfTicket.js mint 401/403 静默退避），
// 干净 HEAD 即红 ⇒ 反转为防复活钉；可见面判据迁到统一入口（产品内 keepalive 403 /
// projects 401/403 走的同一入口）。
//
// 运行：node tests/session-gate.spec.mjs   （PORT 可换）

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8144);
const BASE = `http://127.0.0.1:${PORT}`;

const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.html': 'text/html' };
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let pass = 0, fail = 0;
const failures = [];
function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; failures.push(name); console.log(`FAIL  ${name}${extra ? '  — ' + extra : ''}`); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const LAB_HTML = '<!doctype html><html><head><meta charset="utf-8"><title>lab</title></head><body>lab</body></html>';

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  // 实验页：不启动真控制台（零 WS / 零 boot 面），只供给模块源。
  await page.route('**/lab.html', (route) => route.fulfill({ contentType: 'text/html', body: LAB_HTML }));
  // 铸票门：可切换 403 / 500。
  let mintStatus = 403;
  await page.route('**/api/nf-ticket*', (route) =>
    route.fulfill({ status: mintStatus, contentType: 'text/plain', body: 'mock gate' }));

  await page.goto(`${BASE}/lab.html`);
  await page.evaluate(async () => {
    const mod = await import('/js/sessionGate.js');
    window.__gate = mod;
    mod._sessionGateTestHooks().setReload(() => {
      window.__reloads = (window.__reloads || 0) + 1;
    });
    // 缺省重座缝 = 恒不可用（lab 无 portal 面）：可见面判定确定化。重座行为
    // 本体由 ①c/①d 专站换缝驱动。
    mod._sessionGateTestHooks().setReseat(async () => false);
  });

  // ① 铸票 403 ⇒ 静默退避（authgate 批反复活钉）：零横幅零 reload。
  await page.evaluate(async () => {
    const nf = await import('/js/nfTicket.js');
    await nf.mintTickets(['/tmp/gate-probe.png']);
  });
  await sleep(300);
  ok('① 铸票 403 ⇒ 静默退避（authgate 批 2026-10-22：不再拉 sessionGate，防复活钉）',
    await page.locator('[data-session-gate="1"]').count() === 0
    && !(await page.evaluate(() => window.__reloads > 0)));

  // ①b 统一入口（重座不可用）⇒ 横幅 + reload 腿（可见面判据迁移点）。
  await page.evaluate(() => window.__gate.noteAuthRejected('nf-ticket'));
  await sleep(300);
  ok('①b 重座不可用 403 ⇒ 可见横幅出现', await page.locator('[data-session-gate="1"]').count() === 1);
  ok('①b 横幅带触发面读数', (await page.locator('[data-session-gate="1"]').getAttribute('data-source')) === 'nf-ticket');
  await sleep(1500); // > RELOAD_DELAY_MS
  ok('①b 自愈 reload 腿被拉动', (await page.evaluate(() => window.__reloads)) === 1);

  // ② 同页幂等：再来一次 403（直接打统一入口）不叠横幅不重复 reload。
  await page.evaluate(() => window.__gate.noteAuthRejected('again'));
  await sleep(100);
  ok('② 同页不叠横幅', await page.locator('[data-session-gate="1"]').count() === 1);
  ok('② 不重复 reload', (await page.evaluate(() => window.__reloads)) === 1);

  // ①c 静默重座优先（稳定性批 2026-10-26 核心钉）：重座腿可用（true=正在导航）
  // ⇒ 零横幅零 reload——「会话仍有效时无感恢复，真失效才引导重登」。
  await page.evaluate(() => {
    window.__gate._sessionGateTestHooks().reset();
    window.__reloads = 0;
    window.__reseats = [];
    // 生产里重座成功后由导航换页（横幅随页销毁）；lab 无真导航，站前手动清
    // ①b 留下的横幅节点，零横幅断言才能成立。
    document.querySelectorAll('[data-session-gate]').forEach((n) => n.remove());
    window.__gate._sessionGateTestHooks().setReseat(async (src) => {
      window.__reseats.push(src);
      return true;
    });
  });
  await page.evaluate(() => window.__gate.noteAuthRejected('heal'));
  await sleep(300);
  ok('①c 重座可用 ⇒ 零横幅', await page.locator('[data-session-gate="1"]').count() === 0);
  ok('①c 重座可用 ⇒ 零 reload（导航接管）', (await page.evaluate(() => window.__reloads)) === 0);
  ok('①c 重座腿被调用恰一次（单飞）', (await page.evaluate(() => window.__reseats.length)) === 1);
  ok('①c 重座腿带触发面读数', (await page.evaluate(() => window.__reseats[0])) === 'heal');

  // ①d 重座悬挂 ⇒ 8s 封顶竞速后照常横幅 + reload（迟到好过永不）。
  await page.evaluate(() => {
    window.__gate._sessionGateTestHooks().reset();
    window.__reloads = 0;
    document.querySelectorAll('[data-session-gate]').forEach((n) => n.remove()); // ①b 横幅随真导航销毁的 lab 等价
    window.__gate._sessionGateTestHooks().setReseat(() => new Promise(() => {})); // 永悬挂
  });
  await page.evaluate(() => window.__gate.noteAuthRejected('hang'));
  ok('①d 前置：悬挂期不横幅（等竞速封顶）', await page.locator('[data-session-gate="1"]').count() === 0);
  await sleep(8600); // > RESEAT_CAP_MS(8s) + RELOAD_DELAY_MS(1.5s) 余量见下
  await sleep(1200);
  ok('①d 封顶后横幅出现', await page.locator('[data-session-gate="1"]').count() === 1);
  ok('①d 封顶后 reload 腿被拉动', (await page.evaluate(() => window.__reloads)) === 1);
  // 还原确定化缝（后续站沿用「重座不可用」；①d 的悬挂缝不得泄漏）。
  await page.evaluate(() => window.__gate._sessionGateTestHooks().setReseat(async () => false));

  // ③ 瞬态 5xx：铸票 500 ⇒ 静默降级面保持（不新增横幅节点、不拉 reload——
  //    ①b 的横幅按设计仍留在 DOM，reload 前随页销毁；断言 = 计数与 reload 不涨）。
  await page.evaluate(() => window.__gate._sessionGateTestHooks().reset());
  mintStatus = 500;
  await page.evaluate(async () => {
    const nf = await import('/js/nfTicket.js');
    nf.resetTickets();
    await nf.mintTickets(['/tmp/gate-probe-5xx.png']);
  });
  await sleep(300);
  ok('③ 5xx 不新增横幅不拉 reload（既有静默降级面保持）',
    await page.locator('[data-session-gate="1"]').count() === 1
    && (await page.evaluate(() => window.__reloads)) === 1);

  // ④ 跨 reload 冷却：armer = 统一入口（mint 已不再拉闸）。先打一次 403（写冷却 +
  //    横幅），真 reload（sessionStorage 存活、模块态归零）后再 403 ⇒ 冷却窗内
  //    零横幅零 reload。
  mintStatus = 403;
  await page.evaluate(() => window.__gate._sessionGateTestHooks().reset());
  await page.evaluate(() => window.__gate.noteAuthRejected('cd-arm'));
  await sleep(300);
  ok('④ 前置：统一入口 403 写入冷却键（横幅 id 单例，不叠节点）',
    await page.evaluate(() =>
      Object.keys(sessionStorage).some((k) => k.includes('auth_gate_at')) &&
      document.querySelectorAll('[data-session-gate="1"]').length === 1));
  await page.reload(); // 真导航：冷却键留在 sessionStorage，单飞/横幅归零
  await page.evaluate(async () => {
    window.__gate = await import('/js/sessionGate.js');
  });
  await page.evaluate(() => window.__gate.noteAuthRejected('cooldown'));
  await sleep(200);
  ok('④ 冷却窗内 reload 后 403 不再横幅不再 reload（防连环刷新）',
    await page.locator('[data-session-gate="1"]').count() === 0
    && !(await page.evaluate(() => window.__reloads > 0)));

  // ⑤ 会话保活腿（session TTL 批 2026-10-21）：入座证实后武装 12h 一拍保活；
  //    204 拍零动作（滑动已在拍内发生）、403 拍走自愈腿、网络错拍静默、武装幂等。
  await page.reload(); // 模块态/单飞归零（sessionStorage 冷却键先清，防 403 拍落冷却窗）
  await page.evaluate(() => {
    Object.keys(sessionStorage).filter((k) => k.includes('auth_gate_at')).forEach((k) => sessionStorage.removeItem(k));
  });
  await page.evaluate(async () => {
    const mod = await import('/js/sessionGate.js');
    window.__gate = mod;
    mod._sessionGateTestHooks().setReload(() => {
      window.__reloads = (window.__reloads || 0) + 1;
    });
    window.__kaTicks = [];
    mod._keepaliveTestHooks().setSchedule((fn, ms) => {
      window.__kaTicks.push({ fn, ms });
      return 0;
    });
    mod._keepaliveTestHooks().setFetch(() =>
      window.__kaStatus === 'reject'
        ? Promise.reject(new TypeError('network down'))
        : Promise.resolve({ status: window.__kaStatus })
    );
  });
  ok('⑤ 保活拍间隔 = 1h（稳定性批 2026-10-26 收紧：WS-only 长会话的滑动窗滞后上限 12h→1h）',
    await page.evaluate(() => window.__gate.KEEPALIVE_INTERVAL_MS === 60 * 60 * 1000));
  await page.evaluate(() => {
    window.__kaStatus = 204;
    window.__gate.armSessionKeepalive();
    window.__gate.armSessionKeepalive(); // 幂等：二次武装不得叠拍
  });
  ok('⑤ 武装幂等：恰好注册一拍', await page.evaluate(() => window.__kaTicks.length === 1));
  ok('⑤ 注册间隔为 KEEPALIVE_INTERVAL_MS',
    await page.evaluate(() => window.__kaTicks[0].ms === window.__gate.KEEPALIVE_INTERVAL_MS));
  await page.evaluate(() => window.__kaTicks[0].fn());
  await sleep(200);
  ok('⑤ 204 拍零动作（不横幅不 reload）',
    await page.evaluate(() => !document.querySelector('[data-session-gate="1"]') && !(window.__reloads > 0)));
  await page.evaluate(() => {
    window.__kaStatus = 'reject';
    return window.__kaTicks[0].fn();
  });
  await sleep(200);
  ok('⑤ 网络错拍静默（离线抖动不是会话死亡）',
    await page.evaluate(() => !document.querySelector('[data-session-gate="1"]') && !(window.__reloads > 0)));
  await page.evaluate(() => {
    window.__kaStatus = 403;
    // 403 拍走 noteAuthRejected ⇒ 先静默重座（稳定性批 2026-10-26）；lab 里换缝
    // 为「重座不可用」，可见面判定确定化。
    window.__gate._sessionGateTestHooks().setReseat(async () => false);
    return window.__kaTicks[0].fn();
  });
  await sleep(300);
  ok('⑤ 403 拍拉起横幅（keepalive 触发面读数）',
    (await page.locator('[data-session-gate="1"]').getAttribute('data-source')) === 'keepalive');
  await sleep(1500);
  ok('⑤ 403 拍拉自愈 reload 腿', (await page.evaluate(() => window.__reloads)) === 1);
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail) { console.log('FAILURES: ' + failures.join(' | ')); process.exit(1); }
