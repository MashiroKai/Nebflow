// fm-md-social-shots.mjs — 「好友/群聊/设备三类会话的消息气泡支持 markdown 渲染」批
// （2026-11-02 19:30 作者令）的 **A1–A8 机械验收 harness**。
//
// 判据纪律：判定 = 断言，图只是旁证。改前/改后两条腿跑**同一份夹具**，
// 改前腿 = `git show <base>:…` 取回批前四个文件的**真实字节**（messages.js /
// utils.js / friends.css / mobileLayer.css）由第二个静态服务原样伺服 ——
// 不是「打个 debug 开关假装改前」，是同一套夹具下的两个真实构建。
//
// 覆盖（逐字对齐任务书的 A1–A8）：
//   A1 改前截图：三面气泡里出现**裸语法**（`**` / ``` ``` ``` / `| 表 |`），
//      且 DOM 里 `.fm-msg-md` 计数 = 0（判据）。截图 A1-prefix-*。
//      附带根因实证：好友面与设备面改前帧 **md5 逐字节相等**（同一条纯文本腿），
//      群面仅多一行发送者名。
//   A2 改后渲染：三面 `.fm-msg-md` 在场，h2/strong/li/del/blockquote/行内 code/
//      pre/table/th/a.md-ext/复制钮齐备，裸语法字面量消失，逐面截图。
//   A3 纯文本回归：两条腿各建**同一份固定位置探针**（position:fixed 整数坐标，
//      仅用生产类名 .fm-msg in > .fm-msg-bubble > .fm-msg-text），
//      ① computed 13 项逐值相等 ② 探针截图 **md5 逐字节相等**。
//   A4 XSS 硬门：`<img src=x onerror=…>` / `<script>…</script>` / `<iframe
//      src=javascript:>` 进 md 渲染后 DOM 无 img/script/iframe 节点、`window.__xss`
//      恒 0、字面量可见；**验红对照**：同一段文本走可信档（= 「天真复用 agent 面
//      渲染器」的做法）⇒ img/script/iframe 真进 DOM 且 onerror 真触发。
//   A5 链接点击实测：点击 `a.md-ext` ⇒ 二次确认框（#delete-box）出现、**零新标签页**；
//      取消 ⇒ 仍零；确认 ⇒ 恰好一个新标签页且 URL = 白名单 href。
//   A6 表格小屏横向滚动：390×844 手机层下 `table` overflow-x:auto 且
//      scrollWidth > clientWidth（宽表内容真溢出、可横滚），页面级无横向滚动条。
//   A7 三类会话各自验一遍（好友 cF / 群 g-1 / 设备 dev:peer-1）——逐面过。
//   A8 热区 ≥44×44 + 字号实测：`a.md-ext` 与 `.code-copy-btn` 的**真实命中盒**
//      （elementFromPoint 四向扫描，不靠 CSS 公式推算）≥44×44；
//      md 正文/行内代码/代码块/表格字号 ≥13px，手机层正文 = 17px（--mfs-body）。
//
// 🔴 零实例依赖（同 fm-threeface-shots.mjs 纪律）：静态资源 = 内置 http server
//    （随机端口，收尾必关）；REST/WS = page.route 契约镜像，**非生产网关**。
//    真实隔离实例上的同套装包核验见 tests/fm-md-social-live.mjs。
// 收尾判据：零 console error / 零 pageerror / 零本地资源 4xx-5xx。
//
// Run: NODE_PATH=/opt/homebrew/lib/node_modules node tests/fm-md-social-shots.mjs
//   FM_MD_SHOTS=<dir>   落盘目录（缺省 evidence/20261102_fm-md-social/shots）
//   FM_MD_BASE=<rev>    改前腿基准 commit（缺省 `git merge-base HEAD main`）

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execSync } from 'node:child_process';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.FM_MD_SHOTS || join(ROOT, 'evidence', '20261102_fm-md-social', 'shots');
mkdirSync(OUT, { recursive: true });

// ── 断言出口 ────────────────────────────────────────────────────────────────
let checks = 0;
let failures = 0;
const ok = (name, cond, extra = '') => {
  checks++;
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
  return !!cond;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const md5 = (buf) => createHash('md5').update(buf).digest('hex');
const eqNum = (a, b, tol = 0.01) => typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) <= tol;

// ── 改前腿：从批前 commit 取真实字节（不是开关，是另一份构建）────────────────
const BASE_REV = process.env.FM_MD_BASE
  || execSync(`git -C "${ROOT}" merge-base HEAD main`, { encoding: 'utf8' }).trim();
const PRE_PATHS = [
  'src/main/resources/web/js/messages.js',
  'src/main/resources/web/js/utils.js',
  'src/main/resources/web/css/friends.css',
  'src/main/resources/web/css/mobileLayer.css',
];
const preFiles = new Map(); // '/js/messages.js' → Buffer
for (const p of PRE_PATHS) {
  const bytes = execSync(`git -C "${ROOT}" show ${BASE_REV}:${p}`, { maxBuffer: 1 << 28 });
  preFiles.set('/' + p.replace('src/main/resources/web/', ''), bytes);
}
console.log(`改前腿基准 = ${BASE_REV} · 覆写文件 ${preFiles.size} 个：${[...preFiles.keys()].join(' ')}`);

// ── 夹具：同一份消息集，三面各一份（消息 id = 10*p + i）───────────────────────
const FIXED = Date.now() - 3 * 3600 * 1000;
const iso = (ms) => new Date(ms).toISOString();

// 表格第 4 列刻意放一条**长无空格路径**（真实 agent 通报形态）：nowrap +
// overflow-x:auto 的口径只有真溢出才验得出来（A6）。
const mdBody = (face) => [
  `## 交付确认（${face} → 管家）`,
  '',
  '**结论**：三面 markdown 渲染已接同一条管线，下面是要点：',
  '',
  '- **加粗**要点一：`utils.renderMarkdownWithMath`',
  '- 要点二：外部输入走 `{safe:true}`',
  '- 要点三：~~旧裸文本腿~~ 已收敛',
  '',
  '```js',
  'const html = renderMarkdownWithMath(body, false, { safe: true }); // 长代码行：等宽 + overflow-x:auto + 右缘模糊淡出',
  'wrap.innerHTML = html;',
  '```',
  '',
  '| 面 | 渲染器 | 状态 | 改动文件 |',
  '| --- | --- | --- | --- |',
  '| 好友 | 共享管线 | 已接 | src/main/resources/web/js/messages.js |',
  '| 群聊 | 共享管线 | 已接 | src/main/resources/web/css/friends.css |',
  '| 设备 | 共享管线 | 已接 | src/main/resources/web/js/utils.js |',
  '',
  '> 引用块：三面同一套元素样式。',
  '',
  '详见 [Nebflow 文档](https://example.com/docs/md?x=1)。',
].join('\n');

const PLAIN_BODY = '今天下午三点在会议室开个短会，确认一下进度。（纯文本回归探针）';

// XSS 夹具：故意**带 md 语法**（`**…**`），否则会走纯文本腿、转义得毫无悬念。
const XSS_BODY = [
  '**外部来信探针**：下面这段是原始 HTML，必须原样显示成字面量。',
  '',
  '<img src=x onerror="window.__xss=(window.__xss||0)+1">',
  '<script>window.__xss=(window.__xss||0)+10</script>',
  '',
  '<iframe src="javascript:window.__xss=(window.__xss||0)+100"></iframe>',
].join('\n');

const LINK_BODY = [
  '参考 [Nebflow 文档](https://example.com/docs/md?x=1) 与 [危险链接](javascript:window.__xss=99)。',
  '',
  '图片语法 ![远端截图](https://example.com/a.png) 与 [协议相对](//example.org/path)。',
].join('\n');

const OUT_MD_BODY = [
  '**我方回复**：已收到，稍后同步到群里。',
  '',
  '- 第一项：路由已确认',
  '- 第二项：等待回执',
].join('\n');

const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(FIXED - 86400000) };

// 行形态：① md 入向 ② 纯文本入向（A3 现场腿）③ XSS 入向 ④ 链接/图片入向 ⑤ md 出向
// 三面正文的 h2 带面名（好友/群聊/设备）⇒ 三面截图可区分，且 A7 是「逐面各验一遍」。
const FACE_LABEL = { cF: '好友私聊', 'g-1': '群聊', 'dev:peer-1': '设备会话' };
const bodiesOf = (cid) => [mdBody(FACE_LABEL[cid] || cid), PLAIN_BODY, XSS_BODY, LINK_BODY, OUT_MD_BODY];
const friendRows = (p, cid) => bodiesOf(cid).map((body, i) => ({
  id: 10 * p + (i + 1),
  senderId: i === 4 ? 'me' : 'u-p',
  kind: 'text',
  body,
  createdAt: iso(FIXED + i * 60000),
  attachments: [],
}));
const deviceRows = (p, cid) => bodiesOf(cid).map((body, i) => ({
  id: 10 * p + (i + 1),
  senderDeviceId: i === 4 ? 'self-1' : 'peer-1',
  kind: 'text',
  body,
  createdAtMs: FIXED + i * 60000,
  attachments: [],
}));
const MSGS = { cF: friendRows(1, 'cF'), 'g-1': friendRows(2, 'g-1'), 'dev:peer-1': deviceRows(3, 'dev:peer-1') };

const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: MSGS.cF[0], unreadCount: 0 },
  { conversationId: 'dev:peer-1', kind: 'device', deviceId: 'peer-1', lastMessage: MSGS['dev:peer-1'][0], unreadCount: 0 },
];
const GROUPS = [{ groupId: 'g-1', title: '项目群', unreadCount: 0, memberCount: 3, role: 'owner', memberAvatars: [], lastMessage: MSGS['g-1'][1] }];
const PANELS = [['friend', 'cF', 1], ['group', 'g-1', 2], ['device', 'dev:peer-1', 3]];

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.mp4': 'video/mp4',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

// ── 两个静态服务：post = 工作树字节；pre = 批前 commit 的四个文件字节 ─────────
async function startStatic(pre) {
  const server = createServer(async (req, res) => {
    try {
      const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
      if (p.startsWith('/agents/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
      if (pre && preFiles.has(p)) {
        res.writeHead(200, { 'Content-Type': MIME[extname(p)] || 'application/octet-stream' });
        res.end(preFiles.get(p)); return;
      }
      const file = join(WEB, p === '/' ? 'index.html' : p);
      const body = await readFile(file);
      res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(body);
    } catch {
      res.writeHead(404); res.end('not found');
    }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { server, base: `http://127.0.0.1:${server.address().port}` };
}

const postSrv = await startStatic(false);
const preSrv = await startStatic(true);

const browser = await chromium.launch();
const frames = [];
const readings = { baseRev: BASE_REV, preOverrides: [...preFiles.keys()], out: OUT, legs: {} };

async function bootPage({ base, colorScheme = 'light', viewport = { width: 1280, height: 900 }, hasTouch = false } = {}) {
  const ctx = await browser.newContext({ colorScheme, viewport, deviceScaleFactor: 2, hasTouch });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    window.__xss = 0;
  });
  const page = await ctx.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  const netBad = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
  page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 300)));
  page.on('response', (r) => { if (r.status() >= 400 && !/\/api\//.test(r.url())) netBad.push(r.status() + ' ' + r.url()); });

  // A4 验红对照会**故意**让不安全渲染把 `<img src=x>` 放进 DOM（那是被测对象，
  // 不是产品缺陷）⇒ 那条图片请求由本 harness 直接应答（200 + 非图像字节：
  // onerror 照常触发，零 404 噪声）。作用域只有 `/x` 一条路径。
  await page.route('**/x', (route) => route.fulfill({ status: 200, contentType: 'text/plain', body: 'x' }));

  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    const p = u.pathname;
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        loggedIn: true,
        relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
        device: { id: 'self-1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
        peers: [{ deviceId: 'peer-1', deviceName: '书房 Mac', name: '书房 Mac', platform: 'macos', online: true, directOnline: true, avatarUrl: '' }],
      }) });
    }
    if (p === '/api/conversations') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONVS) });
    if (p === '/api/friends') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [FRIEND], incoming: [], outgoing: [] }) });
    if (p === '/api/groups') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GROUPS) });
    if (p === '/api/groups/invites') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ incoming: [] }) });
    const mm = p.match(/^\/api\/conversations\/([^/]+)\/messages$/);
    if (mm) {
      const cid = decodeURIComponent(mm[1]);
      const after = Math.max(0, Math.floor(Number(u.searchParams.get('after')) || 0));
      const rows = (MSGS[cid] || []).filter((x) => Number(x.id) > after);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type === 'getConfig') {
        ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: FIXED }], activeId: 'sess-1', folders: [] }));
  });

  await page.goto(base + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 20000 });
  await sleep(900);
  return { ctx, page, consoleErrors, pageErrors, netBad };
}

// ── 取景 ────────────────────────────────────────────────────────────────────
async function shotClip(page, box, name, pad = 14) {
  await sleep(180);
  const vp = page.viewportSize();
  const x = Math.max(0, Math.floor(box.x - pad));
  const y = Math.max(0, Math.floor(box.y - pad));
  const w = Math.min(vp.width - x, Math.ceil(box.width + pad * 2));
  const h = Math.min(vp.height - y, Math.ceil(box.height + pad * 2));
  if (w < 4 || h < 4) { ok(`shot ${name} 裁剪盒有效`, false, JSON.stringify({ x, y, w, h })); return null; }
  const buf = await page.screenshot({ clip: { x, y, width: w, height: h } });
  writeFileSync(join(OUT, name), buf);
  frames.push({ name, md5: md5(buf), bytes: buf.length });
  console.log(`SHOT ${name}  ${w}×${h} · ${buf.length}B · md5=${md5(buf).slice(0, 8)}`);
  return buf;
}
async function shotSel(page, selector, name, pad = 14) {
  const loc = page.locator(selector).first();
  if ((await loc.count()) === 0) { ok(`shot ${name} 目标在场`, false, selector); return null; }
  await loc.evaluate((e) => e.scrollIntoView({ block: 'center', inline: 'center' })).catch(() => {});
  await sleep(240);
  const box = await loc.boundingBox();
  if (!box) { ok(`shot ${name} 盒可取`, false, selector); return null; }
  return shotClip(page, box, name, pad);
}
async function shotWindow(page, name) {
  const ov = await page.$('#fm-chat-overlay .fm-modal');
  const target = ov || await page.$('#fm-chat-overlay');
  if (!target) { ok(`shot ${name} 窗口在场`, false); return null; }
  await sleep(200);
  const buf = await target.screenshot();
  writeFileSync(join(OUT, name), buf);
  frames.push({ name, md5: md5(buf), bytes: buf.length });
  console.log(`SHOT ${name}  ${buf.length}B · md5=${md5(buf).slice(0, 8)}`);
  return buf;
}

// ── 打开会话 ───────────────────────────────────────────────────────────────
async function openConv(page, cid) {
  await page.click('#messages-btn'); await sleep(600);
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(900);
  await sleep(250);
}
async function switchConv(page, cid) {
  await page.click('.fm-modal-close').catch(async () => { await page.keyboard.press('Escape'); });
  await sleep(420);
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(900);
  await sleep(250);
}

// ── A3 固定位置探针（两条腿各建同一份 DOM ⇒ 像素比对才有意义）───────────────
// 位置取整数坐标（left:100/top:100）⇒ 无亚像素相位差；宽度固定 380 定宽盒。
const PROBE_METRICS = () => {
  const b = document.querySelector('#qa-plain-probe .fm-msg-bubble');
  const w = document.querySelector('#qa-plain-probe .fm-msg-text');
  if (!b || !w) return null;
  const c = getComputedStyle(b); const wc = getComputedStyle(w);
  const r = b.getBoundingClientRect();
  return {
    fontSize: c.fontSize, lineHeight: c.lineHeight, padding: c.padding, color: c.color,
    whiteSpace: c.whiteSpace, wordBreak: c.wordBreak, borderRadius: c.borderRadius,
    bg: c.backgroundColor, borderWidth: c.borderTopWidth, overflowWrap: c.overflowWrap,
    wrapFontSize: wc.fontSize, wrapWhiteSpace: wc.whiteSpace, wrapMarginBottom: wc.marginBottom,
    box: { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2) },
  };
};
const plainProbe = (page, text) => page.evaluate((txt) => {
  document.getElementById('qa-plain-probe')?.remove();
  const host = document.createElement('div');
  host.id = 'qa-plain-probe';
  host.style.cssText = 'position:fixed;left:100px;top:100px;width:380px;z-index:99999;background:var(--color-bg,#fff);';
  // 与生产 DOM **逐层同构**：.fm-msg in > .fm-msg-bubble > .fm-msg-text（纯文本腿）
  const row = document.createElement('div');
  row.className = 'fm-msg in';
  row.style.maxWidth = 'none';
  const bubble = document.createElement('div');
  bubble.className = 'fm-msg-bubble';
  const wrap = document.createElement('div');
  wrap.className = 'fm-msg-text';
  wrap.textContent = txt;
  bubble.appendChild(wrap);
  row.appendChild(bubble);
  host.appendChild(row);
  document.body.appendChild(host);
  return new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(true))));
}, text);

const probeShot = async (page, name) => {
  const host = page.locator('#qa-plain-probe');
  if ((await host.count()) === 0) { ok(`shot ${name} 探针在场`, false); return null; }
  const box = await host.boundingBox();
  const buf = await shotClip(page, box, name, 8);
  return buf ? md5(buf) : null;
};

// ── 页内读数 ────────────────────────────────────────────────────────────────
/** md 行结构读数（A2）+ 裸语法残留（A1）+ 纯文本行读数（A3 现场腿）。 */
const readMdFace = (page, ids) => page.evaluate((rowIds) => {
  const q = (v) => document.querySelector(`.fm-flow .fm-msg[data-message-id="${v}"]`);
  const md = q(rowIds.md); const plain = q(rowIds.plain); const xss = q(rowIds.xss); const link = q(rowIds.link); const out = q(rowIds.out);
  const mdWrap = md && md.querySelector('.fm-msg-text');
  const txt = (el) => (el ? el.textContent : '');
  const cs = (el, p) => (el ? getComputedStyle(el)[p] : null);
  const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) }; };
  const codeEl = mdWrap && mdWrap.querySelector('pre code');
  // 行内代码 = 除 pre 内的 code 之外的任一 code（本夹具的行内代码在 li 里，不在 p 里）
  const inlineCode = mdWrap && [...mdWrap.querySelectorAll('code')].find((c) => !c.closest('pre'));
  const tableEl = mdWrap && mdWrap.querySelector('table');
  const anchor = mdWrap && mdWrap.querySelector('a.md-ext');
  return {
    found: { md: !!md, plain: !!plain, xss: !!xss, link: !!link, out: !!out },
    mdCount: document.querySelectorAll('.fm-flow .fm-msg-md').length,
    mdWrap: mdWrap ? { cls: mdWrap.className, tag: mdWrap.tagName, childCount: mdWrap.childElementCount } : null,
    mdTags: mdWrap ? {
      h2: mdWrap.querySelectorAll('h2').length,
      strong: mdWrap.querySelectorAll('strong').length,
      li: mdWrap.querySelectorAll('li').length,
      del: mdWrap.querySelectorAll('del').length,
      blockquote: mdWrap.querySelectorAll('blockquote').length,
      pre: mdWrap.querySelectorAll('pre').length,
      code: mdWrap.querySelectorAll('code').length,
      inlineCode: [...mdWrap.querySelectorAll('code')].filter((c) => !c.closest('pre')).length,
      table: mdWrap.querySelectorAll('table').length,
      th: mdWrap.querySelectorAll('th').length,
      a: mdWrap.querySelectorAll('a').length,
      mdExt: mdWrap.querySelectorAll('a.md-ext').length,
      img: mdWrap.querySelectorAll('img').length,
      script: mdWrap.querySelectorAll('script').length,
      iframe: mdWrap.querySelectorAll('iframe').length,
      copyBtn: mdWrap.querySelectorAll('.code-copy-btn').length,
      wrap: mdWrap.querySelectorAll('.code-block-wrap').length,
    } : null,
    mdText: txt(mdWrap),
    plainText: txt(plain && plain.querySelector('.fm-msg-text')),
    xssText: txt(xss && xss.querySelector('.fm-msg-text')),
    linkWrap: link && (() => {
      const w = link.querySelector('.fm-msg-text');
      return w ? {
        anchors: [...w.querySelectorAll('a')].map((a) => ({
          href: a.getAttribute('href'), cls: a.className, target: a.getAttribute('target'), rel: a.getAttribute('rel'), text: a.textContent,
        })),
        imgChips: [...w.querySelectorAll('a.md-ext-img')].map((a) => ({ href: a.getAttribute('href'), text: a.textContent })),
        imgs: w.querySelectorAll('img').length,
        text: w.textContent,
      } : null;
    })(),
    outWrap: out && (() => { const w = out.querySelector('.fm-msg-text'); return w ? { cls: w.className, strong: w.querySelectorAll('strong').length, li: w.querySelectorAll('li').length } : null; })(),
    // A3 现场腿读数（生产位置上的纯文本行；与探针读数互补）
    plainMetrics: (() => {
      const b = plain && plain.querySelector('.fm-msg-bubble');
      if (!b) return null;
      const c = getComputedStyle(b);
      return {
        fontSize: c.fontSize, lineHeight: c.lineHeight, padding: c.padding, color: c.color,
        whiteSpace: c.whiteSpace, wordBreak: c.wordBreak, borderRadius: c.borderRadius,
        bg: c.backgroundColor, borderWidth: c.borderTopWidth, box: box(b),
        wrapFontSize: cs(plain.querySelector('.fm-msg-text'), 'fontSize'),
        wrapWhiteSpace: cs(plain.querySelector('.fm-msg-text'), 'whiteSpace'),
        wrapMarginBottom: cs(plain.querySelector('.fm-msg-text'), 'marginBottom'),
      };
    })(),
    // A8 字号实测
    fonts: {
      mdBody: cs(mdWrap, 'fontSize'),
      mdBodyLine: cs(mdWrap, 'lineHeight'),
      plainBody: cs(plain && plain.querySelector('.fm-msg-text'), 'fontSize'),
      code: cs(codeEl, 'fontSize'),
      inlineCode: cs(inlineCode, 'fontSize'),
      table: cs(tableEl, 'fontSize'),
      th: cs(tableEl && tableEl.querySelector('th'), 'fontSize'),
    },
    // A6 表格溢出读数
    table: tableEl ? {
      display: cs(tableEl, 'display'), overflowX: cs(tableEl, 'overflowX'),
      scrollWidth: tableEl.scrollWidth, clientWidth: tableEl.clientWidth,
      maskImage: cs(tableEl, 'maskImage') || cs(tableEl, 'webkitMaskImage'),
      box: box(tableEl),
    } : null,
    pre: codeEl ? (() => {
      const pre = codeEl.parentElement;
      return {
        overflowX: cs(pre, 'overflowX'), scrollWidth: pre.scrollWidth, clientWidth: pre.clientWidth,
        fontFamily: cs(codeEl, 'fontFamily'), fontSize: cs(codeEl, 'fontSize'),
        maskImage: cs(pre, 'maskImage') || cs(pre, 'webkitMaskImage'),
      };
    })() : null,
    anchorPseudo: anchor ? (() => {
      const c = getComputedStyle(anchor, '::after');
      return { content: c.content, position: c.position };
    })() : null,
    xss: {
      imgNodes: document.querySelectorAll('.fm-flow .fm-msg .fm-msg-md img, .fm-flow .fm-msg .fm-msg-md script, .fm-flow .fm-msg .fm-msg-md iframe').length,
      counted: window.__xss,
    },
    doc: { scrollWidth: document.scrollingElement.scrollWidth, innerWidth: window.innerWidth },
  };
}, ids);
const ROW_IDS = (p) => ({ md: 10 * p + 1, plain: 10 * p + 2, xss: 10 * p + 3, link: 10 * p + 4, out: 10 * p + 5 });

/** A8 命中盒**实测**：从中心点用 elementFromPoint 四向扫描，实测「鼠标/手指真的
 *  落在它身上」的区域尺寸 —— 不靠 CSS 公式推算（伪元素参与命中测试，因此
 *  这条路线同时验了 ::before 外扩是否真的生效）。 */
const readHitBoxes = (page, ids) => page.evaluate(async (rowIds) => {
  const row = document.querySelector(`.fm-flow .fm-msg[data-message-id="${rowIds.md}"]`);
  if (!row) return null;
  const scan = (el) => {
    if (!el) return { found: false };
    el.scrollIntoView({ block: 'center', inline: 'center' });
    const b = el.getBoundingClientRect();
    const cx = b.left + b.width / 2; const cy = b.top + b.height / 2;
    const inVp = cx > 2 && cy > 2 && cx < window.innerWidth - 2 && cy < window.innerHeight - 2;
    const at = (x, y) => {
      const e = document.elementFromPoint(x, y);
      return !!e && (e === el || el.contains(e));
    };
    let up = 0; let down = 0; let left = 0; let right = 0;
    for (let d = 1; d <= 90; d++) { if (at(cx, cy - d)) up = d; else break; }
    for (let d = 1; d <= 90; d++) { if (at(cx, cy + d)) down = d; else break; }
    for (let d = 1; d <= 260; d++) { if (at(cx - d, cy)) left = d; else break; }
    for (let d = 1; d <= 260; d++) { if (at(cx + d, cy)) right = d; else break; }
    const pseudo = getComputedStyle(el, '::before');
    return {
      found: true, inViewport: inVp,
      rect: { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) },
      pseudoBefore: { content: pseudo.content, inset: [pseudo.top, pseudo.right, pseudo.bottom, pseudo.left].join(' ') },
      hit: { w: left + right + 1, h: up + down + 1, up, down, left, right },
      centerHit: at(cx, cy),
    };
  };
  const out = { link: scan(row.querySelector('a.md-ext')), copyBtn: scan(row.querySelector('.code-copy-btn')) };
  row.scrollIntoView({ block: 'center' });
  await new Promise((r) => setTimeout(r, 120));
  return out;
}, ids);

/** A4 验红对照：用**可信档**（= 直接复用 agent 面渲染器的天真做法）渲染同一段外部文本。 */
const naiveControl = (page, text) => page.evaluate(async (txt) => {
  const m = await import('/js/utils.js');
  window.__xss = 0;
  const host = document.createElement('div');
  host.id = 'qa-naive-host';
  host.style.cssText = 'position:fixed;left:-4000px;top:0;width:320px;';
  document.body.appendChild(host);
  // 可信档（safe 缺省 = false）= 若把 agent 会话那套**原样**搬到三面会发生的渲染
  host.innerHTML = m.renderMarkdownWithMath(txt, false);
  await new Promise((r) => setTimeout(r, 800)); // 等 onerror 触发
  const out = {
    img: host.querySelectorAll('img').length,
    script: host.querySelectorAll('script').length,
    iframe: host.querySelectorAll('iframe').length,
    onerrorAttr: host.innerHTML.includes('onerror'),
    xssFired: window.__xss,
  };
  host.remove();
  window.__xss = 0;
  return out;
}, text);

// ═════════════════════════════════════════════════════════════════════════════
try {
  console.log(`fm-md-social shots · WEB=${WEB}`);
  console.log(`OUT=${OUT}`);

  // ══ 腿 PRE：改前（批前 commit 的真实字节）——A1 裸语法 + A3 基线（双主题）═══
  for (const scheme of ['light', 'dark']) {
    const pre = { panels: {}, probe: {} };
    const { ctx, page, consoleErrors, pageErrors, netBad } = await bootPage({ base: preSrv.base, colorScheme: scheme });
    try {
      // A3 探针（固定位置，与生产布局无关）
      await plainProbe(page, PLAIN_BODY);
      pre.probe.metrics = await page.evaluate(PROBE_METRICS);
      pre.probe.md5 = await probeShot(page, `A3-probe-prefix-${scheme}.png`);
      await page.evaluate(() => document.getElementById('qa-plain-probe')?.remove());

      let first = true;
      for (const [panel, cid, p] of PANELS) {
        if (first) { await openConv(page, cid); first = false; } else { await switchConv(page, cid); }
        const r = await readMdFace(page, ROW_IDS(p));
        pre.panels[panel] = r;
        ok(`[A1][${scheme}][${panel}] 改前：DOM 里零 \`.fm-msg-md\`（正文走纯文本腿）`, r.mdCount === 0, `mdCount=${r.mdCount}`);
        ok(`[A1][${scheme}][${panel}] 改前：气泡里出现**裸语法**（\`##\` / \`**\` / \`\`\`\`\`\` / \`| 表 |\` 字面可见）`,
          /\*\*结论\*\*/.test(r.mdText) && /^##\s/m.test(r.mdText) && /```/.test(r.mdText) && /\|\s*面\s*\|/.test(r.mdText),
          JSON.stringify({ head: r.mdText.slice(0, 60) }));
        await shotSel(page, `.fm-flow .fm-msg[data-message-id="${10 * p + 1}"]`, `A1-prefix-${panel}-${scheme}.png`, 16);
      }
      readings.legs[`pre-${scheme}`] = pre;
      ok(`[A1][${scheme}] 改前腿零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[A1][${scheme}] 改前腿零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
      ok(`[A1][${scheme}] 改前腿零本地资源 4xx/5xx`, netBad.length === 0, JSON.stringify([...new Set(netBad)]));
    } finally { await ctx.close(); }
  }

  // 根因实证（比帧更硬的判据）：改前三面的正文节点**逐字节同款纯文本**
  // —— `.fm-msg-md` 计数 0、`<h2>/<strong>/<table>` 节点 0、md 原文逐字可见、
  // 内层节点结构只剩「一个文本节点」（= textContent 装载，不是 innerHTML 渲染）。
  for (const scheme of ['light', 'dark']) {
    const pre = readings.legs[`pre-${scheme}`];
    const texts = ['friend', 'group', 'device'].map((pn) => pre.panels[pn].mdText);
    const structs = ['friend', 'group', 'device'].map((pn) => pre.panels[pn].mdWrap);
    ok(`[A1][${scheme}] 根因实证：三面改前正文节点**同款纯文本**（md 计数 0 + 零元素子节点 + md 原文逐字可见）`,
      texts.every((t) => /\*\*结论\*\*/.test(t) && /\|\s*面\s*\|/.test(t))
        && structs.every((w) => !!w && w.tag === 'DIV' && w.childCount === 0)
        && ['friend', 'group', 'device'].every((pn) => pre.panels[pn].mdCount === 0),
      JSON.stringify({ texts: texts.map((t) => t.slice(0, 24)), structs }));
  }

  // ══ 腿 POST：改后（工作树字节）——A2/A3/A4/A5/A7/A8 双主题 ═════════════════
  for (const scheme of ['light', 'dark']) {
    const { ctx, page, consoleErrors, pageErrors, netBad } = await bootPage({ base: postSrv.base, colorScheme: scheme });
    const perPanel = {};
    const pagesOpened = [];
    ctx.on('page', (pg) => pagesOpened.push(pg));
    try {
      // A3 探针（与 PRE 腿同一份 DOM、同一整数位置）
      await plainProbe(page, PLAIN_BODY);
      perPanel.probe = { metrics: null, md5: null };
      perPanel.probe.metrics = await page.evaluate(PROBE_METRICS);
      perPanel.probe.md5 = await probeShot(page, `A3-probe-${scheme}.png`);
      const preProbe = readings.legs[`pre-${scheme}`].probe;
      const sameKeys = ['fontSize', 'lineHeight', 'padding', 'color', 'whiteSpace', 'wordBreak',
        'borderRadius', 'bg', 'borderWidth', 'overflowWrap', 'wrapFontSize', 'wrapWhiteSpace', 'wrapMarginBottom'];
      const diffs = sameKeys.filter((k) => preProbe.metrics[k] !== perPanel.probe.metrics[k]);
      ok(`[A3][${scheme}] 纯文本探针实测逐值一致（font-size / line-height / padding / color / 圆角 / 白空格 / overflow-wrap / 内层间距；共 ${sameKeys.length} 项）`,
        diffs.length === 0, JSON.stringify({ diffs, before: preProbe.metrics, after: perPanel.probe.metrics }));
      ok(`[A3][${scheme}] 纯文本探针几何逐值相等（宽 × 高 + 同坐标）`,
        eqNum(preProbe.metrics.box.w, perPanel.probe.metrics.box.w, 0.01)
          && eqNum(preProbe.metrics.box.h, perPanel.probe.metrics.box.h, 0.01)
          && eqNum(preProbe.metrics.box.x, perPanel.probe.metrics.box.x, 0.01)
          && eqNum(preProbe.metrics.box.y, perPanel.probe.metrics.box.y, 0.01),
        JSON.stringify({ before: preProbe.metrics.box, after: perPanel.probe.metrics.box }));
      ok(`[A3][${scheme}] 纯文本探针**像素级一致**（改前 ${String(preProbe.md5).slice(0, 8)} == 改后 ${String(perPanel.probe.md5).slice(0, 8)}，md5 逐字节相等）`,
        !!preProbe.md5 && preProbe.md5 === perPanel.probe.md5,
        JSON.stringify({ pre: preProbe.md5, post: perPanel.probe.md5 }));
      await page.evaluate(() => document.getElementById('qa-plain-probe')?.remove());

      let first = true;
      for (const [panel, cid, p] of PANELS) {
        if (first) { await openConv(page, cid); first = false; } else { await switchConv(page, cid); }
        const ids = ROW_IDS(p);
        const r = await readMdFace(page, ids);
        perPanel[panel] = r;

        // ── A2 渲染成立 ──
        ok(`[A2][${scheme}][${panel}] 正文挂上 \`.fm-msg-text.fm-msg-md\`（md 语法被识别）`,
          r.mdCount >= 2 && !!r.mdWrap && /\bfm-msg-md\b/.test(r.mdWrap.cls),
          JSON.stringify({ mdCount: r.mdCount, cls: r.mdWrap && r.mdWrap.cls }));
        ok(`[A2][${scheme}][${panel}] 元素齐备（h2 / strong×2 / li×3 / del / blockquote / 行内 code×2 / pre / table / th×4 / a.md-ext / 复制钮）`,
          !!r.mdTags && r.mdTags.h2 === 1 && r.mdTags.strong >= 2 && r.mdTags.li === 3 && r.mdTags.del === 1
            && r.mdTags.blockquote === 1 && r.mdTags.pre === 1 && r.mdTags.inlineCode === 2
            && r.mdTags.table === 1 && r.mdTags.th === 4
            && r.mdTags.mdExt >= 1 && r.mdTags.copyBtn === 1 && r.mdTags.wrap === 1,
          JSON.stringify(r.mdTags));
        ok(`[A2][${scheme}][${panel}] 裸语法字面量消失（\`**\` / \`##\` / \`\`\`\`\`\` / \`| --- |\` 全部不再可见）`,
          !/\*\*/.test(r.mdText) && !/^##\s/m.test(r.mdText) && !/```/.test(r.mdText) && !/\|\s*---/.test(r.mdText),
          JSON.stringify({ head: r.mdText.slice(0, 60) }));
        ok(`[A2][${scheme}][${panel}] 收发两侧同档（本机所发的 md 行同样渲染：strong + li 在场）`,
          !!r.outWrap && /\bfm-msg-md\b/.test(r.outWrap.cls) && r.outWrap.strong >= 1 && r.outWrap.li === 2,
          JSON.stringify(r.outWrap));
        await shotSel(page, `.fm-flow .fm-msg[data-message-id="${ids.md}"]`, `A2-md-${panel}-${scheme}.png`, 16);
        await shotWindow(page, `A7-window-${panel}-${scheme}.png`);

        // ── A4 XSS 硬门 ──
        ok(`[A4][${scheme}][${panel}] XSS 字面量可见而非执行（无 img/script/iframe 节点；字面量原文在）`,
          r.xss.imgNodes === 0 && /<img src=x onerror=/.test(r.xssText) && /<script>/.test(r.xssText)
            && /<iframe src="javascript:/.test(r.xssText),
          JSON.stringify({ imgNodes: r.xss.imgNodes, head: r.xssText.slice(0, 80) }));
        ok(`[A4][${scheme}][${panel}] 页面全程 window.__xss = 0（无 onerror / 无脚本执行）`, r.xss.counted === 0, `__xss=${r.xss.counted}`);
        const naive = await naiveControl(page, XSS_BODY);
        perPanel[panel].naive = naive;
        ok(`[A4-control][${scheme}][${panel}] 验红对照：可信档（= 天真复用 agent 渲染器）判定必红 —— img/script/iframe 真进 DOM 且 onerror 真触发`,
          naive.img >= 1 && naive.script >= 1 && naive.iframe >= 1 && naive.xssFired > 0,
          JSON.stringify(naive));

        // ── A5 链接：白名单 / 降级 / 图片不取远端 ──
        const lw = r.linkWrap || { anchors: [], imgChips: [], imgs: 0, text: '' };
        ok(`[A5][${scheme}][${panel}] https 链接 ⇒ \`a.md-ext\`（target=_blank + rel=noopener noreferrer nofollow）`,
          lw.anchors.some((a) => a.href === 'https://example.com/docs/md?x=1' && /\bmd-ext\b/.test(a.cls)
            && a.target === '_blank' && /noopener/.test(a.rel || '') && /noreferrer/.test(a.rel || '') && /nofollow/.test(a.rel || '')),
          JSON.stringify(lw.anchors));
        ok(`[A5][${scheme}][${panel}] \`javascript:\` 链接降级为纯文本（零锚点承载它）`,
          !lw.anchors.some((a) => /javascript:/i.test(a.href || '')) && /危险链接/.test(lw.text),
          JSON.stringify(lw.anchors.map((a) => a.href)));
        ok(`[A5][${scheme}][${panel}] 图片语法不自动取远端字节 ⇒ 降级为 \`a.md-ext-img\` 小牌（零 <img> 节点）`,
          lw.imgs === 0 && lw.imgChips.length === 1 && /^https:\/\/example\.com\/a\.png$/.test(lw.imgChips[0].href),
          JSON.stringify({ imgs: lw.imgs, chips: lw.imgChips }));
        ok(`[A5][${scheme}][${panel}] 协议相对 \`//example.org/path\` 钉成 https（不跟随页面 origin）`,
          lw.anchors.some((a) => a.href === 'https://example.org/path'), JSON.stringify(lw.anchors.map((a) => a.href)));
        ok(`[A5][${scheme}][${panel}] 外链标识：\`a.md-ext::after\` 有字形（明显外链标识）`,
          !!r.anchorPseudo && /↗/.test(r.anchorPseudo.content), JSON.stringify(r.anchorPseudo));

        // ── A6 表格 / 代码块：overflow-x:auto + 模糊淡出 + 真溢出 ──
        ok(`[A6][${scheme}][${panel}] 表格 display:block + overflow-x:auto + 右缘模糊淡出`,
          !!r.table && r.table.display === 'block' && r.table.overflowX === 'auto' && /linear-gradient/.test(r.table.maskImage || ''),
          JSON.stringify(r.table));
        ok(`[A6][${scheme}][${panel}] 表格内容真溢出 ⇒ 可横滚（scrollWidth ${r.table && r.table.scrollWidth} > clientWidth ${r.table && r.table.clientWidth}）`,
          !!r.table && r.table.scrollWidth > r.table.clientWidth + 8,
          JSON.stringify(r.table && { sw: r.table.scrollWidth, cw: r.table.clientWidth }));
        ok(`[A6][${scheme}][${panel}] 代码块 overflow-x:auto + 等宽可读 + 模糊淡出（无省略号截断）`,
          !!r.pre && r.pre.overflowX === 'auto' && /mono/i.test(r.pre.fontFamily) && /linear-gradient/.test(r.pre.maskImage || ''),
          JSON.stringify(r.pre));

        // ── A8 热区（实测命中盒）+ 字号 ──
        const hit = await readHitBoxes(page, ids);
        perPanel[panel].hit = hit;
        ok(`[A8][${scheme}][${panel}] 外链热区 ≥44×44（elementFromPoint 四向扫描实测命中盒）`,
          !!hit && hit.link.found && hit.link.centerHit && hit.link.hit.w >= 44 && hit.link.hit.h >= 44,
          JSON.stringify(hit && hit.link));
        ok(`[A8][${scheme}][${panel}] 代码块复制钮热区 ≥44×44（实测命中盒）`,
          !!hit && hit.copyBtn.found && hit.copyBtn.centerHit && hit.copyBtn.hit.w >= 44 && hit.copyBtn.hit.h >= 44,
          JSON.stringify(hit && hit.copyBtn));
        const f = r.fonts;
        ok(`[A8][${scheme}][${panel}] 字号实测：正文 ${f.mdBody}（= 纯文本腿 ${f.plainBody}，同屏不做双字号）· 行内代码 ${f.inlineCode} · 代码块 ${f.code} · 表格 ${f.table}（可读辅助字 ≥13px）`,
          f.mdBody === f.plainBody && parseFloat(f.code) >= 13 && parseFloat(f.table) >= 13
            && parseFloat(f.inlineCode) >= 13 && parseFloat(f.mdBody) >= 13,
          JSON.stringify(f));

        // ── A3 现场腿：生产位置上的纯文本行（样式逐值 + 宽高相等；y 随前序内容变化，不比）──
        const before = readings.legs[`pre-${scheme}`].panels[panel].plainMetrics;
        const after = r.plainMetrics;
        const styleDiffs = ['fontSize', 'lineHeight', 'padding', 'color', 'whiteSpace', 'wordBreak',
          'borderRadius', 'bg', 'borderWidth', 'wrapFontSize', 'wrapWhiteSpace', 'wrapMarginBottom']
          .filter((k) => before[k] !== after[k]);
        ok(`[A3][${scheme}][${panel}] 纯文本行（生产位置）样式逐值一致（12 项）`,
          !!before && !!after && styleDiffs.length === 0, JSON.stringify({ styleDiffs, before, after }));
        ok(`[A3][${scheme}][${panel}] 纯文本行几何相等（宽 × 高 ±0.5px、x 同 —— y 随前序 markdown 行高度变化，不计）`,
          !!before && !!after && eqNum(before.box.w, after.box.w, 0.5) && eqNum(before.box.h, after.box.h, 0.5)
            && eqNum(before.box.x, after.box.x, 0.5),
          JSON.stringify({ before: before && before.box, after: after && after.box }));

        // ── A5 点击实测（仅 light 腿做交互，dark 只做静态读数）──
        if (scheme === 'light') {
          const openedBefore = pagesOpened.length;
          await page.locator(`.fm-flow .fm-msg[data-message-id="${ids.md}"] a.md-ext`).first().click();
          await sleep(600);
          const dlg = await page.evaluate(() => {
            const box = document.getElementById('delete-box');
            const title = document.getElementById('delete-title');
            const msg = document.getElementById('delete-msg');
            const btn = document.getElementById('delete-confirm');
            const ov = document.getElementById('modal-overlay');
            return {
              visible: !!box && getComputedStyle(box).display !== 'none',
              neutral: !!box && box.classList.contains('confirm-neutral'),
              title: title ? title.textContent : '',
              message: msg ? msg.textContent : '',
              confirmLabel: btn ? btn.textContent : '',
              overlayOn: !!ov && ov.classList.contains('on'),
            };
          });
          perPanel[panel].dialog = dlg;
          ok(`[A5][${scheme}][${panel}] 点击外链 ⇒ **二次确认框**出现（未直接导航；文案含 host）`,
            dlg.visible && dlg.overlayOn && /example\.com/.test(dlg.message) && dlg.neutral === true,
            JSON.stringify(dlg));
          ok(`[A5][${scheme}][${panel}] 确认框出现瞬间零新标签页（拦住了直跳）`, pagesOpened.length === openedBefore,
            JSON.stringify({ openedBefore, after: pagesOpened.length }));
          {
            const buf = await page.screenshot();
            writeFileSync(join(OUT, `A5-confirm-${panel}.png`), buf);
            frames.push({ name: `A5-confirm-${panel}.png`, md5: md5(buf), bytes: buf.length });
            console.log(`SHOT A5-confirm-${panel}.png  ${buf.length}B · md5=${md5(buf).slice(0, 8)}`);
          }
          await page.click('#delete-cancel');
          await sleep(400);
          ok(`[A5][${scheme}][${panel}] 取消 ⇒ 零新标签页（fail closed）`, pagesOpened.length === openedBefore,
            JSON.stringify({ opened: pagesOpened.length }));
          await page.locator(`.fm-flow .fm-msg[data-message-id="${ids.md}"] a.md-ext`).first().click();
          await sleep(400);
          await page.click('#delete-confirm');
          await page.waitForEvent('popup', { timeout: 5000 }).catch(() => null);
          await sleep(700);
          const urls = pagesOpened.map((pg) => pg.url()).filter(Boolean);
          ok(`[A5][${scheme}][${panel}] 确认 ⇒ 恰好一个新标签页且指向白名单 href`,
            pagesOpened.length === openedBefore + 1 && urls.some((u) => u.startsWith('https://example.com/docs/md')),
            JSON.stringify({ count: pagesOpened.length - openedBefore, urls }));
          for (const pg of pagesOpened.splice(0)) { await pg.close().catch(() => {}); }
          await page.click('#delete-cancel').catch(() => {});
          await sleep(250);
        }
      }
      readings.legs[`post-${scheme}`] = perPanel;
      ok(`[${scheme}] 零 console error`, consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok(`[${scheme}] 零 pageerror`, pageErrors.length === 0, JSON.stringify(pageErrors));
      ok(`[${scheme}] 零本地资源 4xx/5xx`, netBad.length === 0, JSON.stringify([...new Set(netBad)]));
    } finally { await ctx.close(); }
  }

  // ══ 腿 MOBILE：390×844 手机层（A6 横滚 + A8 17px 正文 + 热区）══════════════
  {
    const { ctx, page, consoleErrors, pageErrors, netBad } = await bootPage({ base: postSrv.base, viewport: { width: 390, height: 844 }, hasTouch: true });
    const mobile = {};
    try {
      ok('[A6] 390×844 手机层已激活（body.mobile-layer）',
        await page.evaluate(() => document.body.classList.contains('mobile-layer')));
      await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
      await sleep(700);
      await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="cF"]').first().click();
      await sleep(1200);
      const adopted = await page.evaluate(() => {
        const body = document.querySelector('.mobile-page[data-page="conversation"] .mobile-page-body');
        return { hasBody: !!body, modalInPage: !!(body && body.querySelector(':scope > .fm-modal')), msgs: document.querySelectorAll('.fm-flow .fm-msg').length };
      });
      mobile.adopted = adopted;
      ok('[A6] 手机层会话窗已收编进 conversation 页体（.mobile-page-body > .fm-modal）', adopted.modalInPage, JSON.stringify(adopted));
      const ids = ROW_IDS(1);
      const r = await readMdFace(page, ids);
      mobile.face = r;
      ok('[A6] 手机层表格横向可滚（scrollWidth > clientWidth + overflow-x:auto）',
        !!r.table && r.table.overflowX === 'auto' && r.table.scrollWidth > r.table.clientWidth + 8,
        JSON.stringify(r.table));
      ok('[A6] 手机层页面级无横向滚动条（doc.scrollWidth ≤ innerWidth）',
        r.doc.scrollWidth <= r.doc.innerWidth + 1, JSON.stringify(r.doc));
      ok('[A8] 手机层 md 正文 = --mfs-body 17px（Apple 正文档）',
        r.fonts.mdBody === '17px', JSON.stringify(r.fonts));
      ok('[A8] 手机层行内代码/代码块/表格 = --mfs-sub 15px（≥13 可读辅助字下限）',
        parseFloat(r.fonts.code) === 15 && parseFloat(r.fonts.table) === 15 && parseFloat(r.fonts.inlineCode) === 15,
        JSON.stringify(r.fonts));
      const hit = await readHitBoxes(page, ids);
      mobile.hit = hit;
      ok('[A8] 手机层外链热区 ≥44×44（实测命中盒）',
        !!hit && hit.link.found && hit.link.centerHit && hit.link.hit.w >= 44 && hit.link.hit.h >= 44,
        JSON.stringify(hit && hit.link));
      ok('[A8] 手机层复制钮热区 ≥44×44 且常显（hover:none 档 opacity:1）',
        !!hit && hit.copyBtn.found && hit.copyBtn.centerHit && hit.copyBtn.hit.w >= 44 && hit.copyBtn.hit.h >= 44
          && await page.evaluate((id) => {
            const b = document.querySelector(`.fm-flow .fm-msg[data-message-id="${id}"] .code-copy-btn`);
            return !!b && getComputedStyle(b).opacity === '1';
          }, ids.md),
        JSON.stringify(hit && hit.copyBtn));
      await shotSel(page, `.fm-flow .fm-msg[data-message-id="${ids.md}"]`, 'A6-mobile-md.png', 10);
      await page.screenshot({ path: join(OUT, 'A7-mobile-window-friend.png') }).catch(() => {});
      // 表格横滚实证：滚到 60px 后取景（左缘位移 + 右缘模糊淡出）
      const tbl = page.locator(`.fm-flow .fm-msg[data-message-id="${ids.md}"] table`).first();
      await tbl.evaluate((e) => { e.scrollIntoView({ block: 'center' }); e.scrollLeft = 60; }).catch(() => {});
      await sleep(300);
      const scrolled = await tbl.evaluate((e) => ({ left: e.scrollLeft, sw: e.scrollWidth, cw: e.clientWidth }));
      mobile.scrolled = scrolled;
      ok('[A6] 手机层表格可实际横滚（scrollLeft ≥ 55 且内容宽度 > 容器宽）',
        scrolled.left >= 55 && scrolled.sw > scrolled.cw, JSON.stringify(scrolled));
      const tb = await tbl.boundingBox();
      if (tb) await shotClip(page, tb, 'A6-mobile-table-scrolled.png', 8);
      ok('[A6] 手机腿零 console error', consoleErrors.length === 0, JSON.stringify(consoleErrors));
      ok('[A6] 手机腿零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
      ok('[A6] 手机腿零本地资源 4xx/5xx', netBad.length === 0, JSON.stringify([...new Set(netBad)]));
      readings.legs.mobile = mobile;
    } finally { await ctx.close(); }
  }

  // ── 帧统计 ──
  const seen = new Set(); let dup = 0;
  for (const f of frames) { if (seen.has(f.md5)) dup++; else seen.add(f.md5); }
  readings.frames = { total: frames.length, unique: seen.size, duplicates: dup, list: frames };
  console.log(`\nFRAMES total=${frames.length} unique=${seen.size} duplicates=${dup}`);
  // 应有帧：改前 3 面 × 2 主题 + 改前探针 2 + 改后 3 面 × 2 主题 + 改后探针 2
  //         + 改后整窗 3 × 2 + 手机 2 + 确认框 3 = 26
  ok('实拍帧齐全（改前 3 面 × 双主题 + 探针 + 改后 3 面 × 双主题 + 整窗 + 手机腿 + 确认框 ≥ 26 帧）',
    frames.length >= 26, JSON.stringify({ total: frames.length, names: frames.map((f) => f.name) }));
  for (const scheme of ['light', 'dark']) {
    const hashes = ['friend', 'group', 'device'].map((pn) => (frames.find((f) => f.name === `A2-md-${pn}-${scheme}.png`) || {}).md5);
    ok(`[A2][${scheme}] 三面 md 帧内容互异（3 张逐帧唯一）`,
      hashes.every(Boolean) && new Set(hashes).size === 3, JSON.stringify(hashes));
  }
} catch (e) {
  failures++; checks++;
  console.error('HARNESS ERROR:', (e && e.stack) || e);
} finally {
  try { await browser.close(); } catch { /* non-critical */ }
  try { await new Promise((r) => postSrv.server.close(r)); } catch { /* non-critical */ }
  try { await new Promise((r) => preSrv.server.close(r)); } catch { /* non-critical */ }
  readings.summary = { checks, failures, frames: frames.length };
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
  console.log(`\n=== fm-md-social shots: TOTAL=${checks} FAILURES=${failures} FRAMES=${frames.length} · OUT=${OUT} ===`);
  process.exit(failures === 0 ? 0 : 1);
}
