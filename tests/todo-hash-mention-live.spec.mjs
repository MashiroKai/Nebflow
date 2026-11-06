#!/usr/bin/env node
// todo-hash-mention-live.spec.mjs — `#` 引待办 **真实前端的实测**（不是读代码）。
//
// 回答四个问题（逐条给逐字值，见 reports-evidence/todo-mobile-revamp__20261106/recon-live/README.md）：
//   ① 在真输入框里键入 `#` 会不会唤起补全浮层？列出哪些候选？
//   ② 键盘（↑↓ + Enter/Tab）与鼠标点击选中后，输入框文本的**逐字值**是什么？
//   ③ 带 `#<待办名>` 的消息真正发出的**消息体字符串**是什么（截 WS 帧，不看代码推断）？
//   ④ 主会话渲染侧把 `#<待办名>` 变成 `.todo-tag` chip 吗？chip 的 outerHTML 是什么？
//   ⑤ 手机层（body.mobile-layer）下同一组断言是否同样成立？
//
// 零后端跑法（本批统一手法，形状照 tests/_todo-face-harness.mjs）：
//   · 静态面 = 本 worktree 的真 web 树（page.route 从磁盘注入，零本地端口）；
//   · 数据面 = WS 帧桩（configData / sessionList / 空 history）+ todoStore.__seed 夹具；
//   · 同时**记录页面→服务端的每一帧原始 JSON**（截「真正发出的消息体」的唯一可信面）；
//   · 零 sbt、零宿主实例，绝不碰 :6138。
//
// 🔴 诚实申报：静态树/输入引擎/渲染管线/发送链**全是真的**；只有
//   （a）待办清单（__seed 夹具，不发写帧）与（b）服务端（WS 帧桩）是替身。
//   所以本 spec 证明的是**前端**的端到端形态，不证明后端受理/回执。
//
// Run:  node tests/todo-hash-mention-live.spec.mjs
// 环境变量：TODOFACE_WEB_ROOT（web 树根，缺省本仓）、OUT_DIR（截图落点）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TODOFACE_WEB_ROOT
  ? normalize(process.env.TODOFACE_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.OUT_DIR
  ? normalize(process.env.OUT_DIR)
  : join(HERE, '..', 'reports-evidence', 'todo-mobile-revamp__20261106', 'recon-live');

const SID = 'todo-hash-live-session';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.jpg': 'image/jpeg', '.webp': 'image/webp',
};

mkdirSync(OUT, { recursive: true });

// ── 夹具 ────────────────────────────────────────────────────────────────
// 三枚待办（任务书要求：中文一名、英文一名、一名与「井号数字/色值」形状易混）。
//   · 季度复盘       —— 中文，主测（补全 / 插入 / 发送 / chip 全用它）
//   · release-check  —— 英文，测前缀过滤与 Tab 选中
//   · deface         —— **6 位纯十六进制**（d e f a c e 全是 hex 字符）⇒ 与 CSS 色值
//                      形状完全同形，是 blockHexColor 门禁的对照组（期望：不弹、不 chip）
const TAG_FIXTURE = [
  { id: 'tag-work', name: '工作', color: 'rgb(0, 122, 255)' },
  { id: 'tag-life', name: '生活', color: 'rgb(77, 217, 102)' },
];

const T0 = Date.UTC(2026, 10, 5, 12, 0, 0); // 固定基准，重跑结果可比
const TODO_FIXTURE = [
  { id: 'todo-cn-1', tagId: 'tag-work', name: '季度复盘', detail: '本季 OKR 与复盘纪要', due: T0 + 3600_000, priority: 2, createdAt: T0 - 9 * 3600_000 },
  { id: 'todo-en-2', tagId: 'tag-work', name: 'release-check', detail: '发版前检查单', due: T0 + 2 * 3600_000, priority: 1, createdAt: T0 - 8 * 3600_000 },
  { id: 'todo-hex-3', tagId: 'tag-life', name: 'deface', detail: '与六位十六进制色值同形（对照）', due: null, priority: 0, createdAt: T0 - 7 * 3600_000 },
];

// ── PASS/FAIL 计数 ──────────────────────────────────────────────────────
let pass = 0;
let fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  results.push({ name, cond, detail });
  if (cond) pass++; else fail++;
  return cond;
}
function skip(name, why) {
  console.log(`SKIP  ${name}  — ${why}`);
  results.push({ name, cond: null, detail: why });
}

// ── 启动（零后端）───────────────────────────────────────────────────────
async function boot(browser, { width, height, label }) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  /** 页面 → 服务端的所有帧（原始对象）。 */
  const sent = [];

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', {
        value: () => Promise.resolve(), configurable: true,
      });
    }
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200,
        contentType: MIME[extname(file)] || 'application/octet-stream',
        body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done',
      models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      sent.push(m);
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false,
          offset: 0, total: 0,
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForSelector('#input', { timeout: 20000 });
  await page.waitForFunction(() => !!document.querySelector('#task-list'), { timeout: 20000 });

  // 夹具注入（唯一的数据面替身）
  await page.evaluate(async ({ sid, tags, items }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: items });
    const face = await import('/js/todoFace.js');
    face.renderTodoTaskList(sid);
  }, { sid: SID, tags: TAG_FIXTURE, items: TODO_FIXTURE });

  await page.waitForTimeout(400);
  return { ctx, page, sent, label };
}

// ── 读面工具 ────────────────────────────────────────────────────────────
const dropdown = (page) => page.evaluate(() => {
  const el = document.getElementById('mention-dropdown');
  if (!el) return { present: false, on: false, names: [], title: '', empty: '', activeIndex: -1 };
  return {
    present: true,
    on: el.classList.contains('on'),
    title: (el.querySelector('.dd-group-title') || {}).textContent || '',
    names: [...el.querySelectorAll('.mention-name')].map((n) => n.textContent),
    empty: (el.querySelector('.mention-empty') || {}).textContent || '',
    activeIndex: [...el.querySelectorAll('.mention-item')].findIndex((n) => n.classList.contains('active')),
  };
});

async function resetInput(page) {
  await page.click('#input');
  await page.fill('#input', '');
  await page.waitForTimeout(120);
}

async function typeInto(page, text) {
  await page.click('#input');
  await page.keyboard.type(text, { delay: 35 });
  await page.waitForTimeout(220); // 等异步 provider + 渲染落地
}

async function waitForDrop(page, timeout = 4000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    const d = await dropdown(page);
    if (d.on) return d;
    await new Promise((r) => setTimeout(r, 50));
  }
  return dropdown(page);
}

async function waitForFrame(sent, pred, timeout = 6000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    const hit = sent.find(pred);
    if (hit) return hit;
    await new Promise((r) => setTimeout(r, 50));
  }
  return null;
}

const chipHtml = (page, sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  return el ? el.outerHTML : null;
}, sel);

// ── 单腿跑法（桌面 / 手机层 共用同一组断言）─────────────────────────────
async function runLeg(page, sent, leg) {
  const shots = [];
  const shot = async (file) => {
    const p = join(OUT, file);
    await page.screenshot({ path: p });
    shots.push(p);
    return p;
  };

  // ── 0. 数据面自检：候选源真的是刚种的 3 条 ──────────────────────────
  const open = await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    return todoStore.openItems().map((r) => ({ id: r.id, name: r.name, detail: r.detail }));
  });
  ok(`[${leg}] 夹具面：未完成待办 = 3 条且名字逐字为 季度复盘 / release-check / deface`,
    open.length === 3 && open.map((r) => r.name).join('|') === '季度复盘|release-check|deface',
    JSON.stringify(open.map((r) => r.name)));

  // ── 1. 键入 `#` → 补全浮层是否出现 ────────────────────────────────
  await resetInput(page);
  await typeInto(page, '#');
  const d1 = await waitForDrop(page);
  ok(`[${leg}] 键入 \`#\` ⇒ 补全浮层出现（#mention-dropdown.on）`, d1.on, `names=${JSON.stringify(d1.names)}`);
  ok(`[${leg}] 浮层候选 = 全部 3 条未完成待办`, d1.names.length === 3,
    `names=${JSON.stringify(d1.names)}  title=${JSON.stringify(d1.title)}`);
  await shot(leg === 'desktop' ? '01-desktop-dropdown.png' : '04-mobile-dropdown.png');

  // ── 2. 键盘：↑↓ 导航 + Enter 确认，读逐字值 ──────────────────────
  const namesOrder = d1.names.slice();
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(120);
  const d2 = await dropdown(page);
  ok(`[${leg}] ArrowDown 把高亮行从 0 移到 1`, d2.activeIndex === 1, `activeIndex=${d2.activeIndex}`);
  const targetName = namesOrder[d2.activeIndex];
  await page.keyboard.press('Enter');
  await page.waitForTimeout(200);
  const kbVal = await page.inputValue('#input');
  ok(`[${leg}] Enter 确认后输入框逐字值 = \`#${targetName} \`（含一个尾随空格）`,
    kbVal === `#${targetName} `, JSON.stringify(kbVal));
  const closed = await dropdown(page);
  ok(`[${leg}] 选中后浮层收起`, !closed.on, `on=${closed.on}`);

  // ── 3. 键盘 Tab 确认（第二枚待办，前缀过滤）─────────────────────
  await resetInput(page);
  await typeInto(page, '#rel');
  const d3 = await waitForDrop(page);
  ok(`[${leg}] 键入 \`#rel\` ⇒ 前缀过滤到 1 条（release-check）`,
    d3.names.length === 1 && d3.names[0] === 'release-check', JSON.stringify(d3.names));
  await page.keyboard.press('Tab');
  await page.waitForTimeout(200);
  const tabVal = await page.inputValue('#input');
  ok(`[${leg}] Tab 确认后逐字值 = \`#release-check \``, tabVal === '#release-check ', JSON.stringify(tabVal));

  // ── 4. 鼠标点击选中 ─────────────────────────────────────────────
  await resetInput(page);
  await typeInto(page, '#季');
  const d4 = await waitForDrop(page);
  ok(`[${leg}] 键入 \`#季\` ⇒ 中文前缀命中 1 条（季度复盘）`,
    d4.names.length === 1 && d4.names[0] === '季度复盘', JSON.stringify(d4.names));
  await page.click('#mention-dropdown .mention-item');
  await page.waitForTimeout(220);
  const clickVal = await page.inputValue('#input');
  ok(`[${leg}] 点击候选后逐字值 = \`#季度复盘 \``, clickVal === '#季度复盘 ', JSON.stringify(clickVal));
  await shot(leg === 'desktop' ? '02-desktop-after-pick.png' : '05-mobile-after-pick.png');

  // ── 5. 真发送：从 WS 帧桩截「真正发出的消息体」────────────────────
  await resetInput(page);
  await typeInto(page, '#');
  const before = await waitForDrop(page);
  const idx = before.names.indexOf('季度复盘');
  ok(`[${leg}] 空查询下列表含「季度复盘」`, idx >= 0, JSON.stringify(before.names));
  for (let i = 0; i < idx; i++) { await page.keyboard.press('ArrowDown'); await page.waitForTimeout(80); }
  await page.keyboard.press('Enter');
  await page.waitForTimeout(200);
  await page.keyboard.type('这条是回复', { delay: 35 });
  await page.waitForTimeout(200);
  const composed = await page.inputValue('#input');
  ok(`[${leg}] 组合后输入框逐字值 = \`#季度复盘 这条是回复\``,
    composed === '#季度复盘 这条是回复', JSON.stringify(composed));

  const framesBefore = sent.length;
  await page.click('#send-btn');
  const frame = await waitForFrame(sent,
    (f) => typeof f.content === 'string' && f.content.includes('这条是回复'));
  ok(`[${leg}] 点发送后真有帧出线（WS 帧桩截到 content 含「这条是回复」）`, !!frame,
    `新增帧数 ${sent.length - framesBefore}`);

  if (frame) {
    ok(`[${leg}] 真正发出的消息体**逐字** = \`#季度复盘 这条是回复\`（无转义、无 id、无引号）`,
      frame.content === '#季度复盘 这条是回复', JSON.stringify(frame.content));
    console.log(`      FRAME ${leg}: ${JSON.stringify({
      keys: Object.keys(frame).sort(),
      type: typeof frame.type === 'string' ? frame.type : '<absent>',
      content: frame.content, sessionId: frame.sessionId,
      clientMessageIdLen: typeof frame.clientMessageId === 'string' ? frame.clientMessageId.length : null,
    })}`);
  }
  const afterSend = await page.inputValue('#input');
  ok(`[${leg}] 发送后输入框被清空`, afterSend === '', JSON.stringify(afterSend));

  // ── 6. 主会话渲染侧：用户气泡里的 `.todo-tag` chip ────────────────
  // 等入场动画（.nf-pop）落定再截图 —— 半透明中间帧不能当视觉证据。
  await page.waitForTimeout(900);
  const rowOpacity = await page.evaluate(() => {
    const r = document.querySelector('.row.user');
    return r ? { row: getComputedStyle(r).opacity, chip: getComputedStyle(r.querySelector('.todo-tag') || r).opacity } : null;
  });
  const userChip = await chipHtml(page, '.row.user .todo-tag');
  ok(`[${leg}] 主会话用户气泡把 \`#季度复盘\` 渲染成 .todo-tag chip`, !!userChip,
    userChip ? userChip.slice(0, 200) : 'no chip');
  if (userChip) console.log(`      CHIP(user) ${leg}: ${userChip}`);
  const chipText = await page.evaluate(() => {
    const el = document.querySelector('.row.user .todo-tag');
    return el ? el.textContent : null;
  });
  ok(`[${leg}] chip 文本 = \`#季度复盘\`（不含尾随空格/正文）`, chipText === '#季度复盘', JSON.stringify(chipText));
  console.log(`      OPACITY ${leg}: ${JSON.stringify(rowOpacity)}`);
  // 气泡行元素级实拍（整窗截图会被窄栏压小，element shot 才有可读的 chip 证据）
  {
    const row = page.locator('.row.user').last();
    await row.scrollIntoViewIfNeeded();
    const file = leg === 'desktop' ? '03a-desktop-chip-row.png' : '06a-mobile-chip-row.png';
    await row.screenshot({ path: join(OUT, file) });
    shots.push(join(OUT, file));
  }

  // ── 7. markdown 腿（assistant 面，restoreTodoRefs 的直调腿）────────
  const mdChip = await page.evaluate(async () => {
    if (typeof marked === 'undefined') return { skipped: true };
    const u = await import('/js/utils.js');
    const html = u.renderMarkdownWithMath('#季度复盘 这条是回复', false);
    const host = document.createElement('div');
    host.className = 'row assistant';
    host.id = '__md-probe';
    host.innerHTML = html;
    document.querySelector('#chat').appendChild(host);
    const el = host.querySelector('.todo-tag');
    return { skipped: false, html: html, chip: el ? el.outerHTML : null };
  });
  if (mdChip.skipped) {
    skip(`[${leg}] markdown 腿 chip（restoreTodoRefs）`, 'marked 未在页内就绪');
  } else {
    ok(`[${leg}] markdown 腿（restoreTodoRefs）同样产出 .todo-tag chip`, !!mdChip.chip,
      mdChip.chip || JSON.stringify(mdChip.html));
    if (mdChip.chip) console.log(`      CHIP(md) ${leg}: ${mdChip.chip}`);
  }
  // markdown 腿实拍（#__md-probe 行；元素级截图 = 小字号 chip 也可读）
  {
    const probe = page.locator('#__md-probe');
    if (await probe.count()) {
      await probe.scrollIntoViewIfNeeded();
      const file = leg === 'desktop' ? '03b-desktop-chip-md.png' : '06b-mobile-chip-md.png';
      await probe.screenshot({ path: join(OUT, file) });
      shots.push(join(OUT, file));
    }
  }
  await shot(leg === 'desktop' ? '03-desktop-chip.png' : '06-mobile-chip.png');

  // ── 8. 未识别边界情形（补全侧 + 渲染侧，逐条实测）──────────────────
  const gate = async (label, typed, expectOn) => {
    await resetInput(page);
    await typeInto(page, typed);
    const d = await dropdown(page);
    ok(`[${leg}] 边界 补全侧 ${label}`, d.on === expectOn,
      `输入 ${JSON.stringify(typed)} ⇒ on=${d.on} names=${JSON.stringify(d.names)}`);
  };
  await gate('`#1`（井号+数字=任务号形状）不弹', '#1', false);
  await gate('`#faf`（3 位十六进制=色值）不弹', '#faf', false);
  await gate('`#deface`（6 位十六进制；清单里真有同名待办也不弹）', '#deface', false);
  await gate('`C#` 前接 ASCII 字母不弹', 'C#', false);
  await gate('` #`（空白之后）弹', ' #', true);

  const renderCase = await page.evaluate(async () => {
    if (typeof marked === 'undefined') return { skipped: true };
    const u = await import('/js/utils.js');
    const cases = {
      digit: '#1 这是任务号不是待办',
      hex: '#faf 这是色值不是待办',
      hexNamed: '#deface 与待办同名但形状是色值',
      wordCont: '#季度复盘会 后缀连着汉字',
      ok: '#季度复盘 这是待办引用',
      embedded: 'a#季度复盘 词中不认',
    };
    const out = {};
    for (const [k, text] of Object.entries(cases)) {
      const html = u.renderMarkdownWithMath(text, false);
      const host = document.createElement('div');
      host.innerHTML = html;
      const el = host.querySelector('.todo-tag');
      out[k] = el ? el.outerHTML : null;
    }
    return { skipped: false, out };
  });
  if (renderCase.skipped) {
    skip(`[${leg}] 渲染侧边界情形`, 'marked 未在页内就绪');
  } else {
    const r = renderCase.out;
    ok(`[${leg}] 渲染侧 \`#1\` 不成 chip（井号数字）`, r.digit === null, String(r.digit));
    ok(`[${leg}] 渲染侧 \`#faf\` 不成 chip（3 位色值）`, r.hex === null, String(r.hex));
    ok(`[${leg}] 渲染侧 \`#deface\` 不成 chip（6 位色值，同名单也不认）`, r.hexNamed === null, String(r.hexNamed));
    ok(`[${leg}] 渲染侧 \`#季度复盘会\` 不成 chip（后缀连着汉字 ⇒ 不吞字）`, r.wordCont === null, String(r.wordCont));
    ok(`[${leg}] 渲染侧 \`#季度复盘\` 成 chip（正例）`, !!r.ok, String(r.ok));
    ok(`[${leg}] 渲染侧 \`a#季度复盘\` 不成 chip（词中不认）`, r.embedded === null, String(r.embedded));
  }

  return shots;
}

// ── 主流程 ──────────────────────────────────────────────────────────────
const browser = await chromium.launch({ headless: true });
const shotPaths = [];

try {
  // 腿 A：桌面（1440×900）
  {
    const { ctx, page, sent } = await boot(browser, { width: 1440, height: 900, label: 'desktop' });
    shotPaths.push(...await runLeg(page, sent, 'desktop'));
    await ctx.close();
  }

  // 腿 B：手机层（390×844；bootstrap.js 按 matchMedia ≤768px 真激活）。
  // 任务书指定的 `document.body.classList.add('mobile-layer')` 在此是**幂等补刀**：
  // 真激活已加过则不动；未加（真激活失效）则手动加，并如实标注是手动腿。
  {
    const { ctx, page, sent } = await boot(browser, { width: 390, height: 844, label: 'mobile' });
    const info = await page.evaluate(() => {
      const real = document.body.classList.contains('mobile-layer');
      document.body.classList.add('mobile-layer'); // 任务书指定动作（幂等）
      return {
        realActivation: real,
        nowOn: document.body.classList.contains('mobile-layer'),
        probe: window.__nfMobileLayer ? { ...window.__nfMobileLayer } : null,
      };
    });
    ok('[mobile] 手机层类成立（body.mobile-layer）', info.nowOn === true,
      `真激活=${info.realActivation} probe=${JSON.stringify(info.probe)}`);
    // 手机层下 #input 仍是同一个宿主（同一 composer，不是第二套实现）
    const same = await page.evaluate(() => {
      const el = document.getElementById('input');
      return {
        exists: !!el, tag: el ? el.tagName : null,
        area: el && el.closest('#input-area') ? '#input-area' : null,
      };
    });
    ok('[mobile] 输入框仍是 #input（同一 composer 宿主）',
      same.exists && same.area === '#input-area', JSON.stringify(same));
    shotPaths.push(...await runLeg(page, sent, 'mobile'));
    await ctx.close();
  }
} catch (e) {
  console.log('FAIL  spec 执行异常 — ' + (e && e.stack ? e.stack.split('\n')[0] : String(e)));
  console.log(e && e.stack ? e.stack : '');
  fail++;
} finally {
  await browser.close();
}

console.log('\n──────── SUMMARY ────────');
console.log(`PASS ${pass}  FAIL ${fail}  SKIP ${results.filter((r) => r.cond === null).length}`);
console.log('SHOTS ' + shotPaths.join('\n      '));
process.exit(fail === 0 ? 0 : 1);
