// fm-md-social-live.mjs — fm-md-social 批（2026-11-02 19:30 作者令）的 **LIVE 隔离实例**
// 验收腿：真引擎（真 sbt 进程 / 真 NEBFLOW_HOME / 真端口 / 真鉴权 / 真静态树）+ 真浏览器。
//
// 与 tests/fm-md-social-shots.mjs 的分工（两条腿都要过，互不替代）：
//   · shots 腿 = 零实例（内置静态服务 + page.route 契约镜像）⇒ A1–A8 的**机械判定**；
//   · 本件 = LIVE 腿（真进程、真 HTTP、真 WS、真 console-ticket 登录）⇒ 证明**同一份
//     构建在真服务下**同样成立（静态树 / 鉴权链 / 路由装配 / 模块加载全真）。
//
// 🔴 唯一被镜像的数据面（**在此显式申报**）：`/api/conversations*`、`/api/friends`、
//    `/api/groups*`、`/api/neblink/status` 四条 REST 由 page.route 供夹具 —— 因为
//    「好友/群聊/设备三类会话要各有一条 md 消息」需要一个带 NebLink 云账号的活体
//    社交面，隔离 home 里没有（真机读数见 README：`/api/friends` 返 403 未登录）。
//    除此之外**全部走真服务**：index.html / 全部 js / css / 字体 / 图标 / WS / 登录。
//    这也正是被测面（气泡渲染）的边界：正文渲染是纯前端腿，数据来自契约。
//
// 断言（LIVE 侧，与 shots 腿同口径但读数独立）：
//   L1 真引擎起的静态树 = 磁盘 dist 或 dev web/（读 `/` 的 source 头 + 变化文件在场）
//   L2 真登录链：POST /api/auth/console-ticket + `?ticket=` 进站（非 localStorage 注入）
//   L3 三面各开一次会话（好友 / 群 / 设备）：各面 `.fm-msg-md` 在场 + h2/table/pre 齐备
//   L4 A4 XSS 硬门复验：window.__xss = 0 且 DOM 零 img/script/iframe 节点
//   L5 A5 外链：点击 ⇒ 真确认框（#delete-box）出现、零新标签页
//   L6 A6 手机层（390×844）表格横滚：scrollWidth > clientWidth 且 scrollLeft 可动
//   L7 A8 热区实测 ≥44×44（a.md-ext / .code-copy-btn 的 elementFromPoint 扫描）
//   L8 零 console error / 零 pageerror（真服务下无 404 资源）
//   L9 收尾：本件 spawn 的进程全清 + 端口释放（lsof 复查）+ 宿主 gateway PID 未动
//
// Run: node tests/fm-md-social-live.mjs
//   LIVE_PORT（缺省 8471）· LIVE_HOME（缺省 /tmp/fm-md-live-home）
//   LIVE_SHOTS_DIR（缺省 evidence/20261102_fm-md-social/live）
//   LIVE_BOOT=0  复用已起的实例（不自行 spawn，也不自行 kill）

import { chromium } from 'playwright';
import { spawn, execSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const HOME = process.env.LIVE_HOME || '/tmp/fm-md-live-home';
const PORT = Number(process.env.LIVE_PORT || 8471);
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = process.env.LIVE_SHOTS_DIR || join(ROOT, 'evidence', '20261102_fm-md-social', 'live');
const SELF_BOOT = process.env.LIVE_BOOT !== '0';
mkdirSync(OUT, { recursive: true });

const HOST_GATEWAY_PID = Number(process.env.HOST_GATEWAY_PID || 0); // 宿主实例 PID（禁杀断言）

let checks = 0;
let failures = 0;
const ok = (name, cond, extra = '') => {
  checks++;
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
  return !!cond;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const md5 = (b) => createHash('md5').update(b).digest('hex');

// ── 夹具（与 shots 腿同形：三面各一条 md 消息 + 一条 XSS 消息）────────────────
const FIXED = Date.now() - 3 * 3600 * 1000;
const iso = (ms) => new Date(ms).toISOString();
const mdBody = (face) => [
  `## 交付确认（${face} → 管家）`, '',
  '**结论**：三面 markdown 渲染已接同一条管线，下面是要点：', '',
  '- **加粗**要点一：`utils.renderMarkdownWithMath`',
  '- 要点二：外部输入走 `{safe:true}`',
  '- 要点三：~~旧裸文本腿~~ 已收敛', '',
  '```js',
  'const html = renderMarkdownWithMath(body, false, { safe: true }); // LIVE 腿：真服务真静态树',
  'wrap.innerHTML = html;',
  '```', '',
  '| 面 | 渲染器 | 状态 | 改动文件 |',
  '| --- | --- | --- | --- |',
  '| 好友 | 共享管线 | 已接 | src/main/resources/web/js/messages.js |',
  '| 群聊 | 共享管线 | 已接 | src/main/resources/web/css/friends.css |',
  '| 设备 | 共享管线 | 已接 | src/main/resources/web/js/utils.js |', '',
  '> 引用块：三面同一套元素样式。', '',
  '详见 [Nebflow 文档](https://example.com/docs/md?x=1)。',
].join('\n');
const XSS_BODY = [
  '**外部来信探针**：下面这段是原始 HTML，必须原样显示成字面量。', '',
  '<img src=x onerror="window.__xss=(window.__xss||0)+1">',
  '<script>window.__xss=(window.__xss||0)+10</script>',
].join('\n');

const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(FIXED - 86400000) };
const FACE = { cF: '好友私聊', 'g-1': '群聊', 'dev:peer-1': '设备会话' };
const rowsOf = (p, cid) => [
  { id: 10 * p + 1, senderId: 'u-p', kind: 'text', body: mdBody(FACE[cid]), createdAt: iso(FIXED), attachments: [] },
  { id: 10 * p + 2, senderId: 'u-p', kind: 'text', body: XSS_BODY, createdAt: iso(FIXED + 60000), attachments: [] },
];
const devRowsOf = (p) => [
  { id: 10 * p + 1, senderDeviceId: 'peer-1', kind: 'text', body: mdBody(FACE['dev:peer-1']), createdAtMs: FIXED, attachments: [] },
  { id: 10 * p + 2, senderDeviceId: 'peer-1', kind: 'text', body: XSS_BODY, createdAtMs: FIXED + 60000, attachments: [] },
];
const MSGS = { cF: rowsOf(1, 'cF'), 'g-1': rowsOf(2, 'g-1'), 'dev:peer-1': devRowsOf(3) };
const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: MSGS.cF[0], unreadCount: 0 },
  { conversationId: 'dev:peer-1', kind: 'device', deviceId: 'peer-1', lastMessage: MSGS['dev:peer-1'][0], unreadCount: 0 },
];
const GROUPS = [{ groupId: 'g-1', title: '项目群', unreadCount: 0, memberCount: 3, role: 'owner', memberAvatars: [], lastMessage: MSGS['g-1'][0] }];

// ── 真引擎启动（隔离 home + 独占端口 + nohup 语义的 spawn）────────────────────
/** @type {import('node:child_process').ChildProcess|null} */
let engine = null;
const spawnedPids = [];

/**
 * 隔离 home 的**环境播种**（不是产品改动）：`rootName.js:111` 会探
 * `agents/<ROOT_AGENT_KEY>/agent.json` 取根 agent 显示名，而 `Seed` 只播 6 项、
 * 不含 agents/ ⇒ 全新 home 必然在那条静态请求上 404（真机 home 有该文件故无感）。
 * 本件把它补齐，让 L8「零本地资源 4xx」测的是**本批回归**而不是隔离 home 的先天缺口。
 */
function prepareHome() {
  const dir = join(HOME, 'agents', 'Nebula');
  const dst = join(dir, 'agent.json');
  try { readFileSync(dst); return { seeded: false, path: dst }; } catch { /* missing */ }
  mkdirSync(dir, { recursive: true });
  let body = '{"name":"Nebula","displayName":"Nebula","description":"Orchestrator","useWhen":null,"tools":[],"mcpServers":null}';
  for (const src of [join(process.env.HOME || '', '.nebflow', 'agents', 'Nebula', 'agent.json'), join(process.env.HOME || '', '.stand', 'agents', 'Nebula', 'agent.json')]) {
    try { body = readFileSync(src, 'utf8'); break; } catch { /* try next */ }
  }
  writeFileSync(dst, body);
  return { seeded: true, path: dst, bytes: body.length };
}

function portListeners(p) {
  try {
    const out = execSync(`lsof -nP -tiTCP:${p} -sTCP:LISTEN 2>/dev/null || true`, { encoding: 'utf8' });
    return out.split('\n').map((s) => Number(s.trim())).filter(Boolean);
  } catch { return []; }
}

/** 本实例的进程 = cmdline 同时含 `--home <HOME>` 与 `--port <PORT>`（双条件唯一，
 *  宿主 gateway 的 `--port 6138` / 其它 sbt 进程一律不匹配 ⇒ 绝不误杀）。 */
function instancePids() {
  try {
    const out = execSync(`ps -Ao pid=,command= 2>/dev/null || true`, { encoding: 'utf8' });
    return out.split('\n')
      .filter((l) => l.includes(`--home ${HOME}`) && l.includes(`--port ${PORT}`))
      .map((l) => Number(l.trim().split(/\s+/)[0]))
      .filter((n) => Number.isFinite(n) && n > 0 && n !== process.pid);
  } catch { return []; }
}

/** auth.json 落盘形态是 **JSON 字符串**（`"abc…"`，带引号）⇒ 必须解一层，
 *  否则 Bearer 头会带上引号 ⇒ 网关 403（本件首跑实测踩坑，读数见 reports/）。 */
function readToken() {
  const raw = readFileSync(join(HOME, 'auth.json'), 'utf8').trim();
  try {
    const j = JSON.parse(raw);
    return typeof j === 'string' ? j : (j.token || j.gatewayToken || raw);
  } catch { return raw.replace(/^"|"$/g, ''); }
}

async function bootEngine() {
  const token = readToken();
  const seeded = prepareHome();
  ok('L0 隔离 home 已就位（auth.json 在场，端口独占，根 agent 静态面已播种）',
    !!token && portListeners(PORT).length === 0, JSON.stringify({ home: HOME, port: PORT, seeded, listeners: portListeners(PORT) }));
  const logPath = '/tmp/fm-md-live-engine.log';
  const child = spawn('sbt', ['-batch', `run --home ${HOME} --port ${PORT} --no-browser`], {
    cwd: ROOT,
    env: { ...process.env, NEBFLOW_HOME: HOME },
    detached: true,
    stdio: ['ignore', 'ignore', 'ignore'],
  });
  engine = child;
  spawnedPids.push(child.pid);
  writeFileSync(join(OUT, 'engine-boot.txt'), `pid=${child.pid}\nhome=${HOME}\nport=${PORT}\nlog=${logPath}\nstartedAt=${new Date().toISOString()}\n`);
  const t0 = Date.now();
  for (let i = 0; i < 120; i++) {
    await sleep(3000);
    try {
      const r = await fetch(`${BASE}/api/health`);
      if (r.ok) return { seconds: Math.round((Date.now() - t0) / 1000), logPath };
    } catch { /* not up yet */ }
  }
  throw new Error('engine never became ready (360s)');
}

function killAll() {
  // 🔴 安全纪律：绝不用「按端口串 pgrep 全杀」这类宽口径（会波及宿主/其它实例）。
  //    本件的进程身份 = cmdline 同时含 `--home <HOME>` 与 `--port <PORT>`（见 instancePids）
  //    ∪ 端口 listener（独占端口已验身）∪ 自己 spawn 的 PID。
  const targets = new Set([
    ...instancePids(),
    ...portListeners(PORT),
    ...spawnedPids.splice(0),
  ]);
  for (const pid of targets) {
    if (pid === HOST_GATEWAY_PID || pid === process.pid) continue; // 🔴 宿主实例绝不杀
    try { process.kill(pid, 'SIGTERM'); } catch { /* gone */ }
  }
  return [...targets];
}
function killAllHard() {
  const targets = new Set([...instancePids(), ...portListeners(PORT)]);
  for (const pid of targets) {
    if (pid === HOST_GATEWAY_PID || pid === process.pid) continue;
    try { process.kill(pid, 'SIGKILL'); } catch { /* gone */ }
  }
  return [...targets];
}

// ═════════════════════════════════════════════════════════════════════════════
let browser = null;
const frames = [];
const readings = { live: { base: BASE, home: HOME, port: PORT }, legs: {} };
try {
  console.log(`fm-md-social LIVE · base=${BASE} · home=${HOME}`);
  console.log(`OUT=${OUT}`);
  let boot = { seconds: 0 };
  if (SELF_BOOT) {
    boot = await bootEngine();
    spawnedPids.push(...portListeners(PORT));
    console.log(`engine ready in ${boot.seconds}s`);
  }
  ok('L1 真引擎已应答 /api/health（隔离 home + 独占端口）', (await fetch(`${BASE}/api/health`)).ok);

  // ── 真登录链（console-ticket；不注入 localStorage token）─────────────────
  const token = readToken();
  // 🔴 ticket 是**一次性**的（第二次 GET 不再落会话）⇒ 每个 browser context 各铸一张。
  const mintTicket = async () => {
    const m = await fetch(`${BASE}/api/auth/console-ticket`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } });
    const b = await m.json().catch(() => ({}));
    return { ok: m.ok, status: m.status, ticket: b.ticket, expiresInSec: b.expiresInSec };
  };
  const mint = await mintTicket();
  ok('L2 真鉴权链：POST /api/auth/console-ticket 取票成功', mint.ok && !!mint.ticket, `http=${mint.status} ttl=${mint.expiresInSec}s`);
  const indexHtml = await (await fetch(`${BASE}/`)).text();
  const devTree = /js\/main\.js/.test(indexHtml);
  readings.live.index = { bytes: indexHtml.length, devTree };
  // 静态树判定（StaticRoutes：dev 源树 vs classpath/磁盘 dist）。无论哪条腿，
  // 都要证明**真服务供出的字节里含本批代码** —— 这才是 L1 的意义。
  const probeJs = devTree ? 'js/messages.js' : ((indexHtml.match(/assets\/app-[A-Za-z0-9]+\.js/) || [])[0] || 'js/messages.js');
  const servedMessages = await (await fetch(`${BASE}/${probeJs}`)).text();
  const gate = await fetch(`${BASE}/js/mdLinkGate.js`);
  const gateJs = gate.ok ? await gate.text() : '';
  const css = await (await fetch(`${BASE}/css/friends.css`)).text();
  readings.live.staticTree = {
    devTree, probeJs, bytes: servedMessages.length, gateHttp: gate.status,
    cssHits: (css.match(/fm-msg-md/g) || []).length,
  };
  ok(`L1 真引擎静态树已应答（${devTree ? 'dev 源树' : 'prod bundle'} · ${probeJs}）且**供出的字节含本批代码**`,
    /fm-msg-md/.test(servedMessages) && /bindMdExtGate/.test(servedMessages)
      && /fm-msg-md/.test(css) && /bindMdExtGate|handleMdExtClick/.test(gateJs),
    JSON.stringify(readings.live.staticTree));

  browser = await chromium.launch();

  // ── 桌面腿：三面各开一次（A7 的 LIVE 版）────────────────────────────────
  for (const [panel, cid, p] of [['friend', 'cF', 1], ['group', 'g-1', 2], ['device', 'dev:peer-1', 3]]) {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    const consoleErrors = [];
    const pageErrors = [];
    const netBad = [];
    const netAll = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
    page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 300)));
    page.on('response', (r) => {
      if (r.status() < 400) return;
      const rec = r.status() + ' ' + r.url();
      netAll.push(rec);
      if (!/\/api\//.test(r.url())) netBad.push(rec); // 静态资源面必须零 4xx
    });
    await page.route('**/x', (route) => route.fulfill({ status: 200, contentType: 'text/plain', body: 'x' }));
    await ctx.addInitScript(() => { window.__xss = 0; try { localStorage.setItem('nebflow_locale', 'zh-CN'); } catch { /* ignore */ } });

    // 数据面镜像（唯一申报的偏离，见文件头）
    await page.route('**/api/**', async (route) => {
      const u = new URL(route.request().url());
      const path = u.pathname;
      if (path === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
          loggedIn: true,
          relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
          device: { id: 'self-1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
          peers: [{ deviceId: 'peer-1', deviceName: '书房 Mac', name: '书房 Mac', platform: 'macos', online: true, directOnline: true, avatarUrl: '' }],
        }) });
      }
      if (path === '/api/conversations') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONVS) });
      if (path === '/api/friends') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [FRIEND], incoming: [], outgoing: [] }) });
      if (path === '/api/groups') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GROUPS) });
      if (path === '/api/groups/invites') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ incoming: [] }) });
      const mm = path.match(/^\/api\/conversations\/([^/]+)\/messages$/);
      if (mm) {
        const id = decodeURIComponent(mm[1]);
        const after = Math.max(0, Math.floor(Number(u.searchParams.get('after')) || 0));
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify((MSGS[id] || []).filter((r) => Number(r.id) > after)) });
      }
      return route.continue(); // 其余 API 一律真服务
    });

    const tk = await mintTicket();
    ok(`L2[${panel}] 每面独立铸票（一次性票），真链落会话`, tk.ok && !!tk.ticket, `http=${tk.status}`);
    await page.goto(`${BASE}/?ticket=${encodeURIComponent(tk.ticket)}`);
    await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 30000 });
    await sleep(1200);
    await page.click('#messages-btn'); await sleep(800);
    await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(1200);

    const r = await page.evaluate((ids) => {
      const row = document.querySelector(`.fm-flow .fm-msg[data-message-id="${ids.md}"]`);
      const xss = document.querySelector(`.fm-flow .fm-msg[data-message-id="${ids.xss}"]`);
      if (!row) return { found: false };
      const w = row.querySelector('.fm-msg-text');
      const t = w && w.querySelector('table');
      const pre = w && w.querySelector('pre');
      const a = w && w.querySelector('a.md-ext');
      const btn = w && w.querySelector('.code-copy-btn');
      const scan = (el) => {
        if (!el) return null;
        el.scrollIntoView({ block: 'center', inline: 'center' });
        const b = el.getBoundingClientRect();
        const cx = b.left + b.width / 2; const cy = b.top + b.height / 2;
        const at = (x, y) => { const e = document.elementFromPoint(x, y); return !!e && (e === el || el.contains(e)); };
        let up = 0; let down = 0; let left = 0; let right = 0;
        for (let d = 1; d <= 90; d++) { if (at(cx, cy - d)) up = d; else break; }
        for (let d = 1; d <= 90; d++) { if (at(cx, cy + d)) down = d; else break; }
        for (let d = 1; d <= 260; d++) { if (at(cx - d, cy)) left = d; else break; }
        for (let d = 1; d <= 260; d++) { if (at(cx + d, cy)) right = d; else break; }
        return { w: left + right + 1, h: up + down + 1, center: at(cx, cy) };
      };
      const hitA = scan(a); const hitB = scan(btn);
      row.scrollIntoView({ block: 'center' });
      return {
        found: true,
        cls: w && w.className,
        tags: {
          h2: w.querySelectorAll('h2').length, strong: w.querySelectorAll('strong').length,
          li: w.querySelectorAll('li').length, table: w.querySelectorAll('table').length,
          th: w.querySelectorAll('th').length, pre: w.querySelectorAll('pre').length,
          inlineCode: [...w.querySelectorAll('code')].filter((c) => !c.closest('pre')).length,
          mdExt: w.querySelectorAll('a.md-ext').length, img: w.querySelectorAll('img').length,
          script: w.querySelectorAll('script').length, iframe: w.querySelectorAll('iframe').length,
          copyBtn: w.querySelectorAll('.code-copy-btn').length,
        },
        text: w.textContent,
        xssText: xss && xss.querySelector('.fm-msg-text') ? xss.querySelector('.fm-msg-text').textContent : '',
        xssCounted: window.__xss,
        table: t ? { over: getComputedStyle(t).overflowX, sw: t.scrollWidth, cw: t.clientWidth } : null,
        pre: pre ? { over: getComputedStyle(pre).overflowX, sw: pre.scrollWidth, cw: pre.clientWidth, font: getComputedStyle(pre.querySelector('code')).fontFamily } : null,
        hit: { a: hitA, btn: hitB },
      };
    }, { md: 10 * p + 1, xss: 10 * p + 2 });
    readings.legs[`desktop-${panel}`] = r;

    ok(`L3[${panel}] 真服务下 md 正文挂 \`.fm-msg-md\` 且元素齐备（h2/strong/li/table/th/pre/行内 code/a.md-ext/复制钮）`,
      !!r.found && /\bfm-msg-md\b/.test(r.cls || '') && r.tags.h2 === 1 && r.tags.strong >= 2 && r.tags.li === 3
        && r.tags.table === 1 && r.tags.th === 4 && r.tags.pre === 1 && r.tags.inlineCode === 2
        && r.tags.mdExt >= 1 && r.tags.copyBtn === 1,
      JSON.stringify(r.tags));
    ok(`L3[${panel}] 裸语法字面量消失（\`**\`/\`##\`/\`\`\`\`\`\` 不再可见）`,
      !/\*\*/.test(r.text || '') && !/^##\s/m.test(r.text || '') && !/```/.test(r.text || ''));
    ok(`L4[${panel}] XSS 硬门（真服务）：无 img/script/iframe 节点 + window.__xss=0 + 字面量可见`,
      r.tags.img === 0 && r.tags.script === 0 && r.tags.iframe === 0 && r.xssCounted === 0
        && /<img src=x onerror=/.test(r.xssText || '') && /<script>/.test(r.xssText || ''),
      JSON.stringify({ img: r.tags.img, xss: r.xssCounted }));
    ok(`L6[${panel}] 表格 overflow-x:auto + 内容真溢出（${r.table && r.table.sw} > ${r.table && r.table.cw}）`,
      !!r.table && r.table.over === 'auto' && r.table.sw > r.table.cw + 8, JSON.stringify(r.table));
    ok(`L7[${panel}] 热区实测 ≥44×44（外链 ${r.hit.a && r.hit.a.w}×${r.hit.a && r.hit.a.h} / 复制钮 ${r.hit.btn && r.hit.btn.w}×${r.hit.btn && r.hit.btn.h}）`,
      !!r.hit.a && r.hit.a.center && r.hit.a.w >= 44 && r.hit.a.h >= 44
        && !!r.hit.btn && r.hit.btn.center && r.hit.btn.w >= 44 && r.hit.btn.h >= 44,
      JSON.stringify(r.hit));

    // L5 外链点击（真服务下同一套确认框）
    if (panel === 'friend') {
      const before = ctx.pages().length;
      await page.locator(`.fm-flow .fm-msg[data-message-id="11"] a.md-ext`).first().click();
      await sleep(700);
      const dlg = await page.evaluate(() => {
        const b = document.getElementById('delete-box');
        return { visible: !!b && getComputedStyle(b).display !== 'none', msg: (document.getElementById('delete-msg') || {}).textContent || '' };
      });
      ok('L5 真服务下点击外链 ⇒ 二次确认框出现（未直接导航）+ 零新标签页',
        dlg.visible && /example\.com/.test(dlg.msg) && ctx.pages().length === before,
        JSON.stringify({ dlg, pages: ctx.pages().length - before }));
      const buf = await page.screenshot();
      writeFileSync(join(OUT, `live-A5-confirm-${panel}.png`), buf);
      frames.push({ name: `live-A5-confirm-${panel}.png`, md5: md5(buf), bytes: buf.length });
      await page.click('#delete-cancel').catch(() => {});
      await sleep(300);
    }

    const buf = await page.screenshot();
    writeFileSync(join(OUT, `live-A2-${panel}.png`), buf);
    frames.push({ name: `live-A2-${panel}.png`, md5: md5(buf), bytes: buf.length });
    console.log(`SHOT live-A2-${panel}.png  ${buf.length}B · md5=${md5(buf).slice(0, 8)}`);

    ok(`L8[${panel}] 零 JS console error（资源加载失败另行归类）`,
      consoleErrors.filter((t) => !/Failed to load resource/.test(t)).length === 0,
      JSON.stringify(consoleErrors.filter((t) => !/Failed to load resource/.test(t))));
    ok(`L8[${panel}] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
    ok(`L8[${panel}] 零静态资源 4xx/5xx（真静态树，无 404）`, netBad.length === 0, JSON.stringify([...new Set(netBad)]));
    // API 4xx 归类读数（不是本批判据：隔离 home 无 NebLink 账号/无会话，探活类接口本就 404/403）
    readings.legs[`desktop-${panel}`].net4xx = [...new Set(netAll)];
    await ctx.close();
  }

  // ── 手机层腿（真服务 + 真 390×844）──────────────────────────────────────
  {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, hasTouch: true });
    const page = await ctx.newPage();
    const consoleErrors = []; const pageErrors = []; const netBad = []; const netAll = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
    page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 300)));
    page.on('response', (r) => {
      if (r.status() < 400) return;
      const rec = r.status() + ' ' + r.url();
      netAll.push(rec);
      if (!/\/api\//.test(r.url())) netBad.push(rec);
    });
    await page.route('**/x', (route) => route.fulfill({ status: 200, contentType: 'text/plain', body: 'x' }));
    await ctx.addInitScript(() => { window.__xss = 0; try { localStorage.setItem('nebflow_locale', 'zh-CN'); } catch { /* ignore */ } });
    await page.route('**/api/**', async (route) => {
      const u = new URL(route.request().url());
      const path = u.pathname;
      if (path === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
          loggedIn: true,
          relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
          device: { id: 'self-1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
          peers: [{ deviceId: 'peer-1', deviceName: '书房 Mac', name: '书房 Mac', platform: 'macos', online: true, directOnline: true, avatarUrl: '' }],
        }) });
      }
      if (path === '/api/conversations') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONVS) });
      if (path === '/api/friends') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [FRIEND], incoming: [], outgoing: [] }) });
      if (path === '/api/groups') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GROUPS) });
      if (path === '/api/groups/invites') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ incoming: [] }) });
      const mm = path.match(/^\/api\/conversations\/([^/]+)\/messages$/);
      if (mm) {
        const id = decodeURIComponent(mm[1]);
        const after = Math.max(0, Math.floor(Number(u.searchParams.get('after')) || 0));
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify((MSGS[id] || []).filter((r) => Number(r.id) > after)) });
      }
      return route.continue();
    });
    const tkM = await mintTicket();
    ok('L2[mobile] 手机腿独立铸票（一次性票）', tkM.ok && !!tkM.ticket, `http=${tkM.status}`);
    await page.goto(`${BASE}/?ticket=${encodeURIComponent(tkM.ticket)}`);
    await page.waitForSelector('#mobile-dock', { state: 'attached', timeout: 30000 });
    await sleep(1500);
    ok('L6 手机层已激活（真服务 390×844）', await page.evaluate(() => document.body.classList.contains('mobile-layer')));
    await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
    await sleep(800);
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="cF"]').first().click();
    await sleep(1400);
    const r = await page.evaluate(() => {
      const row = document.querySelector('.fm-flow .fm-msg[data-message-id="11"]');
      if (!row) return { found: false };
      const w = row.querySelector('.fm-msg-text');
      const t = w && w.querySelector('table');
      const btn = w && w.querySelector('.code-copy-btn');
      const scan = (el) => {
        if (!el) return null;
        el.scrollIntoView({ block: 'center', inline: 'center' });
        const b = el.getBoundingClientRect();
        const cx = b.left + b.width / 2; const cy = b.top + b.height / 2;
        const at = (x, y) => { const e = document.elementFromPoint(x, y); return !!e && (e === el || el.contains(e)); };
        let up = 0; let down = 0; let left = 0; let right = 0;
        for (let d = 1; d <= 90; d++) { if (at(cx, cy - d)) up = d; else break; }
        for (let d = 1; d <= 90; d++) { if (at(cx, cy + d)) down = d; else break; }
        for (let d = 1; d <= 260; d++) { if (at(cx - d, cy)) left = d; else break; }
        for (let d = 1; d <= 260; d++) { if (at(cx + d, cy)) right = d; else break; }
        return { w: left + right + 1, h: up + down + 1, center: at(cx, cy) };
      };
      const out = {
        found: true,
        font: getComputedStyle(w).fontSize,
        codeFont: t ? getComputedStyle(w.querySelector('td')).fontSize : null,
        table: t ? { over: getComputedStyle(t).overflowX, sw: t.scrollWidth, cw: t.clientWidth } : null,
        hit: { a: scan(w.querySelector('a.md-ext')), btn: scan(btn) },
        doc: { sw: document.scrollingElement.scrollWidth, iw: window.innerWidth },
      };
      out.scrolled = t ? (t.scrollLeft = 60, { left: t.scrollLeft }) : null;
      return out;
    });
    readings.legs.mobile = r;
    ok('L6 手机层：md 正文 17px + 表格 overflow-x:auto 且内容溢出可横滚',
      r.found && r.font === '17px' && !!r.table && r.table.over === 'auto' && r.table.sw > r.table.cw + 8,
      JSON.stringify({ font: r.font, table: r.table }));
    ok('L6 手机层：表格实际可横滚（scrollLeft 到 60）+ 页面级无横向滚动条',
      !!r.scrolled && r.scrolled.left >= 55 && r.doc.sw <= r.doc.iw + 1,
      JSON.stringify({ scrolled: r.scrolled, doc: r.doc }));
    ok('L7 手机层热区 ≥44×44（外链 + 复制钮）',
      !!r.hit.a && r.hit.a.center && r.hit.a.w >= 44 && r.hit.a.h >= 44
        && !!r.hit.btn && r.hit.btn.center && r.hit.btn.w >= 44 && r.hit.btn.h >= 44,
      JSON.stringify(r.hit));
    const buf = await page.screenshot();
    writeFileSync(join(OUT, 'live-mobile-friend.png'), buf);
    frames.push({ name: 'live-mobile-friend.png', md5: md5(buf), bytes: buf.length });
    console.log(`SHOT live-mobile-friend.png  ${buf.length}B · md5=${md5(buf).slice(0, 8)}`);
    ok('L8 手机腿零 JS console error（资源加载失败另行归类）',
      consoleErrors.filter((t) => !/Failed to load resource/.test(t)).length === 0,
      JSON.stringify(consoleErrors.filter((t) => !/Failed to load resource/.test(t))));
    ok('L8 手机腿零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    ok('L8 手机腿零静态资源 4xx/5xx', netBad.length === 0, JSON.stringify([...new Set(netBad)]));
    readings.legs.mobile.net4xx = [...new Set(netAll)];
    await ctx.close();
  }

  ok('实拍帧齐全（三面桌面 + 手机 + 确认框 ≥5 帧）', frames.length >= 5, JSON.stringify(frames.map((f) => f.name)));
  readings.frames = frames;
} catch (e) {
  failures++; checks++;
  console.error('LIVE HARNESS ERROR:', (e && e.stack) || e);
} finally {
  try { if (browser) await browser.close(); } catch { /* non-critical */ }
  if (SELF_BOOT) {
    const signalled = killAll();
    // 先 TERM 给引擎体面退（它自己会收 daemon 子进程），3s 后仍在则 KILL 兜底。
    await sleep(3000);
    if (portListeners(PORT).length || instancePids().length) killAllHard();
    await sleep(1500);
    const stillUp = portListeners(PORT);
    const lingering = instancePids();
    ok(`L9 收尾：本件 spawn 的实例全清（signalled=${JSON.stringify(signalled)}）、端口 ${PORT} 已释放`,
      stillUp.length === 0 && lingering.length === 0, JSON.stringify({ stillUp, lingering }));
    ok('L9 收尾：宿主 gateway 进程未被误杀',
      HOST_GATEWAY_PID ? (() => { try { process.kill(HOST_GATEWAY_PID, 0); return true; } catch { return false; } })() : true,
      `pid=${HOST_GATEWAY_PID || '(未申报)'}`);
    ok('L9 收尾：宿主 gateway 仍在监听 6138（品牌隔离未破）',
      HOST_GATEWAY_PID ? execSync('lsof -nP -tiTCP:6138 -sTCP:LISTEN 2>/dev/null || true', { encoding: 'utf8' }).split('\n').filter(Boolean).length > 0 : true);
  }
  readings.summary = { checks, failures, frames: frames.length };
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
  console.log(`\n=== fm-md-social LIVE: TOTAL=${checks} FAILURES=${failures} FRAMES=${frames.length} · OUT=${OUT} ===`);
  process.exit(failures === 0 ? 0 : 1);
}
