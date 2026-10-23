// agent-identity-sync.spec.mjs — 修复批「智能体名称/头像跨机同步 + 改名落到智能体
// 自我认知」（2026-10-23）验收。
//
// 报障（作者 2026-10-23 16:54 逐字）：「现在我给 agent 设置的名称和头像还是没有跨机
// 同步」——「还是」：2026-10-21 诊断批症状⑧已落引擎 display 面（agent.json displayName
// + avatar 字节），但只解决了「同引擎持久」：①多机各跑一个引擎实例，展示面只落本机
// 数据根，另一台永不可见；②同引擎多浏览器形态下前端「本地有值就永不下拉」，旧
// localStorage 键永远遮住服务端新值。
//
// 本批引擎侧：LWW 戳 + identity-sync 数据通道（presence/relay 双腿既有设施）写后
// 外推 + peer 变化对账推 + Soul.md 自我认知段 + `## Self` 提示词段/identity 提醒。
// 前端侧（本 spec 验收面）：localStorage 条目升级 {dataUrl, sha}（v1 裸值宽容迁移）
// + meta 探针 sha 对账收敛 + 服务端 404 清本地 + agentIdentityChanged/重连强制对账。
//
// 场景（真模块图 + routeWebSocket 桩服务端；harness 同 queue-idle-flush.spec.mjs 族）：
//   P1 v1 裸 dataURL 迁移：本地旧值 + 服务端同 sha ⇒ 对账后条目升级为 {dataUrl, sha}。
//   P2 sha 异 ⇒ 拉字节回填 + 重绘（他端换头像在本端可见）。
//   P3 meta 404 ⇒ 本地键清除（他端 reset 在本端收敛，v1 已知限制闭合）。
//   P4 agentIdentityChanged ⇒ 强制对账（绕 30s 节流）+ 名称探针重跑（改名可见）。
//   P5 本机上传：PUT 写穿 + 本地条目即带 sha（写入即收敛态）。
//   E  静态钉：meta 探针/静默窗/强制对账接缝在源面在场。
//
// Run: node tests/agent-identity-sync.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
// brand.js 回落 lowerName = 'stand'（stub 场景无 gateway 注入）⇒ 存储键前缀恒 stand_。
const LS_KEY = 'stand_agent_avatar_Nebula';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const sha256hex = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// 1×1 PNG（引擎 avatar 面回的字节形态）
const PNG1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const PNG2 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');
const url1 = `data:image/png;base64,${PNG1.toString('base64')}`;
const url2 = `data:image/png;base64,${PNG2.toString('base64')}`;

// ── 引擎侧可控状态（桩）────────────────────────────────────────────────
const engine = {
  avatarBytes: null,            // null = 无自定义头像（meta 404）
  displayName: null,            // agents/Nebula/agent.json 的 displayName
  metaChecks: 0,
  puts: [],                     // PUT avatar 载荷
};

// ── 桩 WS ──────────────────────────────────────────────────────────────
let currentWs = null;
function wireUp(ws) {
  currentWs = ws;
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
    if (m.type === 'listAgents') {
      ws.send(JSON.stringify({ type: 'agentList', agents: [{ name: 'Nebula', displayName: engine.displayName || 'Nebula' }] }));
      return;
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's-aid', name: '身份同步E2E' }], activeId: 's-aid', folders: [] }));
}
const serverSends = (frames) => { for (const f of frames) currentWs.send(JSON.stringify(f)); };

// ── 静态服务器 + 浏览器 ────────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
let ctx = null;
try {
  ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));

  // API 面：头像三端点可控，其余一律 200 {}
  await page.route('**/api/**', r => {
    const u = new URL(r.request().url());
    const p = u.pathname;
    if (p === '/api/agents/Nebula/avatar/meta') {
      engine.metaChecks++;
      if (!engine.avatarBytes) return r.fulfill({ status: 404, json: { error: 'no custom avatar' } });
      return r.fulfill({ json: { sha256: sha256hex(engine.avatarBytes), updatedAt: Date.now() } });
    }
    if (p === '/api/agents/Nebula/avatar' && r.request().method() === 'GET') {
      if (!engine.avatarBytes) return r.fulfill({ status: 404, json: { error: 'no custom avatar' } });
      return r.fulfill({ status: 200, body: engine.avatarBytes, contentType: 'image/png' });
    }
    if (p === '/api/agents/Nebula/avatar' && r.request().method() === 'PUT') {
      engine.puts.push(r.request().postDataJSON());
      engine.avatarBytes = Buffer.from(engine.puts[engine.puts.length - 1].dataUrl.split(',')[1], 'base64');
      return r.fulfill({ json: { saved: true } });
    }
    if (p === '/api/agents/Nebula/avatar' && r.request().method() === 'DELETE') {
      engine.avatarBytes = null;
      return r.fulfill({ json: { cleared: true } });
    }
    if (p === '/api/agents/Nebula/display-name' && r.request().method() === 'PUT') {
      engine.displayName = r.request().postDataJSON().displayName;
      return r.fulfill({ json: { saved: true } });
    }
    return r.fulfill({ json: {} });
  });
  // 静态 agent.json 探针（rootName probe 走这里，非 /api 前缀）
  await page.route('**/agents/Nebula/agent.json', r =>
    r.fulfill({ json: engine.displayName ? { name: 'Nebula', displayName: engine.displayName } : {} }));

  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1200);

  const lsGet = () => page.evaluate(k => localStorage.getItem(k), LS_KEY);
  const lsSet = (v) => page.evaluate(([, k, v]) => localStorage.setItem(k, v), [0, LS_KEY, v]);
  const lsDel = () => page.evaluate(k => localStorage.removeItem(k), LS_KEY);

  // ── P1: v1 裸 dataURL 迁移（本地旧值 + 服务端同 sha ⇒ 升级 {dataUrl, sha}）──
  // 引擎 avatar GET 面 Content-Type 恒 application/octet-stream（PresenceRoutes
  // 既有口径）⇒ 收敛断言钉「字节载荷 + sha」而不是 dataURL 的 mime 串。
  const payloadOf = (dataUrl) => String(dataUrl).split(',')[1];
  engine.avatarBytes = PNG1;
  engine.metaChecks = 0;
  await lsSet(url1);
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('nebflow-agent-identity-changed')));
  await sleep(600);
  const entry1 = await page.evaluate(k => { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } }, LS_KEY);
  ok('P1 v1 裸值迁移为 {dataUrl, sha}', entry1 && payloadOf(entry1.dataUrl) === PNG1.toString('base64') && entry1.sha === sha256hex(PNG1),
    `entry=${JSON.stringify(entry1)?.slice(0, 80)}`);
  ok('P1 meta 探针发生（对账读数）', engine.metaChecks >= 1, `metaChecks=${engine.metaChecks}`);

  // ── P2: 他端换头像（sha 异）⇒ 字节回填 + 本地更新 ───────────────────────
  engine.avatarBytes = PNG2;
  engine.metaChecks = 0;
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('nebflow-agent-identity-changed')));
  await sleep(600);
  const entry2 = await page.evaluate(k => { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } }, LS_KEY);
  ok('P2 sha 异 ⇒ 字节回填', entry2 && payloadOf(entry2.dataUrl) === PNG2.toString('base64') && entry2.sha === sha256hex(PNG2),
    `entry=${JSON.stringify(entry2)?.slice(0, 80)}`);
  const hasImg = await page.evaluate(() => {
    const slot = document.querySelector('#agent-avatar-btn');
    return Boolean(slot && slot.classList.contains('has-img'));
  });
  ok('P2 会话头重绘为自定义头像', hasImg);

  // ── P3: 他端 reset（meta 404）⇒ 本地键清除 ─────────────────────────────
  engine.avatarBytes = null;
  engine.metaChecks = 0;
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('nebflow-agent-identity-changed')));
  await sleep(600);
  const entry3 = await lsGet();
  ok('P3 meta 404 ⇒ 本地键清除', entry3 === null, `value=${String(entry3).slice(0, 60)}`);

  // ── P4: 他端改名 ⇒ agentIdentityChanged 探针重跑，名称可见 ──────────────
  engine.displayName = '小雨';
  await serverSends([{ type: 'agentIdentityChanged', agent: 'Nebula' }]);
  await sleep(800);
  const shown = await page.evaluate(async () => {
    const m = await import('/js/rootName.js');
    return { name: m.rootDisplayName(), named: m.isRootNamed() };
  });
  ok('P4 探针重跑后显示新名', shown.name === '小雨' && shown.named === true, `shown=${JSON.stringify(shown)}`);

  // ── P5: 本机上传（面板先开 → 隐藏 file input 直灌）⇒ PUT 写穿 + 本地条目即带 sha
  await page.evaluate(() => document.querySelector('#agent-avatar-btn')?.click());
  await page.waitForSelector('#agent-profile-panel input[type=file]', { state: 'attached', timeout: 5000 });
  await page.setInputFiles('#agent-profile-panel input[type=file]', {
    name: 'a.png', mimeType: 'image/png', buffer: PNG1,
  });
  await sleep(800);
  ok('P5 PUT 写穿引擎（写后外推的引擎侧另有 spec）', engine.puts.length >= 1, `puts=${engine.puts.length}`);
  // 上传走 canvas 降采样（256px 中裁 JPEG）⇒ dataURL ≠ 原文件字节；本地 sha 必须等于
  // 「实际 PUT 的 dataURL 载荷」的 sha（写入即收敛态的判据）。
  const uploadedB64 = engine.puts[0].dataUrl.split(',')[1];
  const uploadedSha = sha256hex(Buffer.from(uploadedB64, 'base64'));
  const entry5 = await page.evaluate(k => { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } }, LS_KEY);
  ok('P5 本地条目写入即收敛（带 sha）', entry5 && entry5.sha === uploadedSha && entry5.dataUrl === engine.puts[0].dataUrl,
    `sha=${entry5 && entry5.sha?.slice(0, 8)} want=${uploadedSha.slice(0, 8)}`);

  // ── E: 静态钉（源面接缝在场）───────────────────────────────────────────
  const profileSrc = await readFile(join(WEB, 'js', 'agentProfile.js'), 'utf8');
  ok('E1 meta 探针接缝在源面', profileSrc.includes('/avatar/meta'));
  ok('E2 写后静默窗在源面（在途 PUT 防误判）', profileSrc.includes('LOCAL_WRITE_GRACE_MS'));
  ok('E3 强制对账接缝（agentIdentityChanged → force converge）', /nebflow-agent-identity-changed[\s\S]{0,400}convergeAvatar\([^)]*true\)/.test(profileSrc));
  const rootSrc = await readFile(join(WEB, 'js', 'rootName.js'), 'utf8');
  ok('E4 rootName 重探针在源面', rootSrc.includes('reprobeRootName'));
  const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
  ok('E5 agentIdentityChanged 面板消费在源面', mainSrc.includes("onMessage('agentIdentityChanged'"));

  ok('页面零报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));
} catch (e) {
  ok('harness 异常', false, e.message);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAIL`);
process.exit(failures === 0 ? 0 : 1);
