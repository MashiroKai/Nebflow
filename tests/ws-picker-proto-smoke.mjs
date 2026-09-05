// ws-picker-proto-smoke.mjs — 隔离实例 WS 协议链取证（workspace-picker 批次）。
//
// 用 Playwright 打开隔离实例页面，在页面上下文里用原生 WebSocket 发三条命令帧：
//   1. wsBrowse.list  '~'     → 期望 wsBrowseList{path=<home>, home, entries}
//   2. wsBrowse.mkdir home/x  → 期望 wsBrowseMkdir{ok:true, path=...}
//   3. wsBrowse.list  home/x  → 期望 wsBrowseList 含新建目录名
//   4. pickWorkspaceDir       → 期望 workspaceDirPicked{fallback:true, reason:headless-jvm}
//     （实例带 -Djava.awt.headless=true 启动 —— headless gate 真链路取证；
//      绝不在非 headless 实例上发 pickWorkspaceDir，严禁在作者屏幕弹真框）
//
// 用法：node tests/ws-picker-proto-smoke.mjs <baseURL> <token>

import { chromium } from 'playwright';

const BASE_URL = process.argv[2] ?? 'http://localhost:8300';
const TOKEN = process.argv[3] ?? '';
const SUB = 'wsp-probe-sub-' + Date.now(); // 唯一名：可重复跑（os.makeDir 拒绝重复创建是正确语义）

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(`${BASE_URL}/?token=${TOKEN}`);
await page.waitForLoadState('networkidle');

const result = await page.evaluate(async ({ token, base, SUB }) => {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws?token=${token}`);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws open failed')); setTimeout(() => rej(new Error('ws open timeout')), 8000); });

  const call = (frame, matchType, timeoutMs = 6000) => new Promise((resolve) => {
    const onMsg = (e) => {
      try {
        const m = JSON.parse(e.data);
        if (m.type === matchType) { ws.removeEventListener('message', onMsg); resolve(m); }
      } catch { /* non-JSON frame */ }
    };
    ws.addEventListener('message', onMsg);
    ws.send(JSON.stringify(frame));
    setTimeout(() => { ws.removeEventListener('message', onMsg); resolve({ type: matchType, __timeout: true }); }, timeoutMs);
  });

  const list1 = await call({ type: 'wsBrowse.list', path: '~' }, 'wsBrowseList');
  // mkdir 基座选隔离实例 HOME（java 可写）。注意：macOS TCC 对隔离 forked java
  // 的 ~/ 家目录根有「文件与文件夹」保护（Operation not permitted）——这是环境
  // 权限而非代码路径，且恰好验证了 ok=false + error 传播帧。
  const mkdir = await call({ type: 'wsBrowse.mkdir', path: base, name: SUB }, 'wsBrowseMkdir');
  const list2 = await call({ type: 'wsBrowse.list', path: base }, 'wsBrowseList'); // 列 base 找新目录名
  // pickWorkspaceDir：本实例以 java.awt.headless=true 启动 → 应即时 fallback
  const pick = await call({ type: 'pickWorkspaceDir', sessionId: 'proto-probe', requestId: 'proto-1' }, 'workspaceDirPicked');
  ws.close();
  return { list1, mkdir, list2, pick };
}, { token: TOKEN, base: process.env.NEBFLOW_HOME || '/tmp/nb-ws-picker-home', SUB });

await browser.close();

const lines = [];
lines.push(`[1] wsBrowse.list '~' → type=${result.list1.type} timeout=${!!result.list1.__timeout} path=${result.list1.path} home=${result.list1.home} entries=${JSON.stringify(result.list1.entries ?? null)}`);
lines.push(`[2] wsBrowse.mkdir  → type=${result.mkdir.type} timeout=${!!result.mkdir.__timeout} ok=${result.mkdir.ok} path=${result.mkdir.path}`);
lines.push(`[3] wsBrowse.list new dir → type=${result.list2.type} timeout=${!!result.list2.__timeout} entriesContainNew=${Array.isArray(result.list2.entries) && result.list2.entries.includes(SUB)}`);
lines.push(`[4] pickWorkspaceDir → type=${result.pick.type} timeout=${!!result.pick.__timeout} fallback=${result.pick.fallback} reason=${result.pick.reason} cancelled=${result.pick.cancelled}`);
console.log(lines.join('\n'));

const ok =
  !result.list1.__timeout && Array.isArray(result.list1.entries) &&
  result.mkdir.ok === true &&
  !result.list2.__timeout && Array.isArray(result.list2.entries) && result.list2.entries.includes(SUB) &&
  !result.pick.__timeout && result.pick.fallback === true && result.pick.reason === 'headless-jvm';
console.log(ok ? 'PROTO-SMOKE PASS' : 'PROTO-SMOKE FAIL');
process.exit(ok ? 0 : 1);
