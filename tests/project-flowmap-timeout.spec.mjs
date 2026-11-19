#!/usr/bin/env node
// project-flowmap-timeout.spec.mjs — 项目卡摘要有界等待验收（project-slow-folders 批
// 2026-10-28）。
//
// 现场（作者报障 2026-10-28 15:5x，DOSTEL 项目卡「加载中…」久久不出）：经 portal
// 中继链时 fetch 既不成功也**不失败**（uplink inbox-full 排队 / tunnel 429 风暴下
// 挂到浏览器级超时，分钟级）——fetchFlowMap 旧实现无时限 ⇒ 卡片摘要槽骨架脉冲
// （`is-pending`）全程无终态。
//
// 修复语义（本 spec 钉死）：
//   ① 挂死应答 ⇒ fetchFlowMap 在时限内拒绝（AbortError 形态），不再永悬 pending；
//      调用方 loadCardSummary 既有 catch 落 `unknown` 态（idle 文案 + title 可点）。
//   ② 慢但在预算内（显式 timeoutMs 参数 > 实际延迟）⇒ 正常返回载荷（无误杀）。
//   ③ 快速成功 ⇒ 正常返回（存量语义零变化）。
//   ④ 默认时限常量 FLOW_MAP_TIMEOUT_MS = 12000（e2e seam：window.__nfFlowMapTimeoutMs）。
//
// 运行：node tests/project-flowmap-timeout.spec.mjs   （PORT 可换）

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { join, resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8157);
const BASE = `http://127.0.0.1:${PORT}`;

const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.html': 'text/html' };
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  // 先读后写：writeHead(200) 不能走在 fallible read 前面 —— 404 路径
  // （如模块初始化期的 /api 探针）会在 catch 里二次 writeHead 直接炸服务器。
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
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

const FM_PAYLOAD = JSON.stringify({ nodes: [{ id: 'n1', name: 'a', status: 'completed' }], worktrees: [], meta: { project: 'DOSTEL' } });
const LAB_HTML = '<!doctype html><html><head><meta charset="utf-8"><title>lab</title></head><body>lab</body></html>';

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  await page.route('**/lab.html', (route) => route.fulfill({ contentType: 'text/html', body: LAB_HTML }));

  // flow-map 端点行为表：hang = 永不应答（复现中继挂死）；delay = 延迟应答。
  let flowMapMode = 'hang';
  await page.route('**/api/projects/DOSTEL/flow-map', async (route) => {
    if (flowMapMode === 'hang') return; // 故意既不 fulfill 也不 continue ⇒ 请求永悬
    if (flowMapMode === 'delay') {
      await sleep(300);
      return route.fulfill({ status: 200, contentType: 'application/json', body: FM_PAYLOAD });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: FM_PAYLOAD });
  });

  await page.goto(`${BASE}/lab.html`);
  await page.evaluate(() => {
    // e2e seam（生产恒 12000ms，见 nodeData.js FLOW_MAP_TIMEOUT_MS 注记）。
    // 注意：seam 是**装载期**覆盖（模块 const 在首次 import 时绑定），故本页
    // FLOW_MAP_TIMEOUT_MS === 500；干净页面的默认值在下方 ④ 用独立页钉。
    window.__nfFlowMapTimeoutMs = 500;
    window.__nd = import('/js/nodeData.js');
  });
  const seamBound = await page.evaluate(async () => (await window.__nd).FLOW_MAP_TIMEOUT_MS);
  ok('④′ seam 页内时限装载期生效 = 500', seamBound === 500, `got ${seamBound}`);

  // ④ 干净页面（无 seam）默认时限 = 12000（生产缺省不被 seam 污染）。
  const cleanPage = await browser.newPage();
  await cleanPage.goto(`${BASE}/lab.html`);
  const defaultMs = await cleanPage.evaluate(async () => (await import('/js/nodeData.js')).FLOW_MAP_TIMEOUT_MS);
  ok('④ 默认时限常量 = 12000', defaultMs === 12000, `got ${defaultMs}`);
  await cleanPage.close();

  // ① 挂死应答 ⇒ 时限内拒绝（AbortError），不再永悬。
  const t0 = Date.now();
  const hung = await page.evaluate(async () => {
    const m = await window.__nd;
    try { await m.fetchFlowMap('DOSTEL'); return { threw: false }; }
    catch (e) { return { threw: true, name: e && e.name, msg: String(e && e.message) }; }
  });
  const dt = Date.now() - t0;
  ok('① 挂死 ⇒ fetchFlowMap 拒绝（有终态）', hung.threw === true, JSON.stringify(hung));
  ok('① 拒绝形态 = AbortError', hung.name === 'AbortError', `name=${hung.name} msg=${hung.msg}`);
  ok('① 时限内返回（<3s，实际 500ms 级）', dt < 3000, `${dt}ms`);

  // ② 慢但在预算内（显式 timeoutMs=2000 > 300ms 延迟）⇒ 正常返回，无误杀。
  flowMapMode = 'delay';
  const slow = await page.evaluate(async () => {
    const m = await window.__nd;
    try { const fm = await m.fetchFlowMap('DOSTEL', 2000); return { ok: true, nodes: fm.nodes.length }; }
    catch (e) { return { ok: false, name: e && e.name, msg: String(e && e.message) }; }
  });
  ok('② 慢但在预算内 ⇒ 正常返回载荷', slow.ok === true && slow.nodes === 1, JSON.stringify(slow));

  // ③ 快速成功 ⇒ 存量语义零变化（默认时限）。
  flowMapMode = 'ok';
  const fast = await page.evaluate(async () => {
    const m = await window.__nd;
    try { const fm = await m.fetchFlowMap('DOSTEL'); return { ok: true, nodes: fm.nodes.length }; }
    catch (e) { return { ok: false, msg: String(e && e.message) }; }
  });
  ok('③ 快速成功 ⇒ 正常返回（默认时限不误杀）', fast.ok === true && fast.nodes === 1, JSON.stringify(fast));
} catch (e) {
  fail++;
  failures.push(`harness: ${e && e.message}`);
  console.error(e);
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${pass} passed, ${fail} failed${failures.length ? ' — ' + failures.join(' ; ') : ''}`);
process.exit(fail ? 1 : 0);
