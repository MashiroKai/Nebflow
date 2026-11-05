// canvas-note-persist.spec.mjs — Canvas 记事本（note）持久化矩阵（持久化缺陷批 2026-11-05）
//
// 报障：作者 2026-11-05「Canvas Note 记录的内容会丢」。改前实测口径见批报告复现表：
// 内容只落 localStorage（`<brand>_note:<sid>`），引擎侧零写点 ⇒ 换浏览器/换设备/
// 换 origin 一律读不到。改后两层：本地镜像（快显/离线）+ 引擎侧真源
// （WS askNoteGet/askNotePut → `<dataRoot>/ask/notes/<sid>.md`）。
//
// 判据（P 组；改前树 P1/P2/P6/P7/P9 红，P3/P4/P5/P8 视场景）：
//   P1  写入 → **刷新页面** → 内容还在（本地镜像 + 服务端对账）
//   P2  写入 → **引擎重启**（新浏览器上下文继承 origin 存储 + 同一份服务端存量）
//      → 内容还在
//   P3  写入 → **换设备**（新上下文**空存储** + 同一服务端存量）→ 内容还在
//      （改前此处必红：localStorage 是 per-origin×per-device）
//   P4  两个不同文件互不串（A 有内容、B 为空；切回 A 仍在）
//   P5  切会话不影响记事本（记事本随文件，不随会话）
//   P6  反向验红 A：本地镜像清空**且**服务端存量清空 → 必须显示为空
//   P7  反向验红 B：只清本地镜像、服务端留存 → 内容仍在（证明读的是持久层，
//      不是拿内存/localStorage 冒充）
//   P8  隐私模式（localStorage.setItem 抛错）⇒ 状态行可读（不许静默）+ 服务端仍存
//   P9  resize 尺寸同批持久：记事本拖高 → 刷新 → 尺寸还在；ask 列高同理
//   P10 多标签页：A 标签写入 → B 标签打开读到；B 再写 → A 未编辑时采纳
//   z   零 pageerror
//
// 自包含（零后端、零 sbt）：静态路由 + routeWebSocket 夹具，服务端记事本 = 进程内
// Map（模拟 `<dataRoot>/ask/notes/<sid>.md` 的读写与 mtime 语义）。
// Run : PW_SELFCONTAINED_ONLY=1 npx playwright test tests/canvas-note-persist.spec.mjs
// 取证：NOTE_PERSIST_EVIDENCE_DIR=<目录>（默认 ~/.stand/evidence/canvas-note-persist）
import { test } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const WEB = process.env.NOTE_PERSIST_WEB_ROOT || join(REPO, 'src', 'main', 'resources', 'web');
const OUT = process.env.NOTE_PERSIST_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/canvas-note-persist';
mkdirSync(OUT, { recursive: true });

const ORIGIN = 'http://localhost:8894';
const SESSION = 'persist-session';
const FILE_A = '正式问答.md';
const FILE_B = '记录本.md';
const ABS_A = '/tmp/canvas-note-persist/' + FILE_A;
const ABS_B = '/tmp/canvas-note-persist/' + FILE_B;
const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/** 服务端记账（模拟 `<dataRoot>/ask/notes/<sid>.md`：内容 + mtime）。 */
function makeStore() { return { notes: new Map() }; }

async function routeHttp(page) {
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    const p0 = decodeURIComponent(url.pathname);
    const p = p0 === '/' ? '/index.html' : p0;
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SESSION, name: 'root', agentName: 'Nebula' }], activeId: SESSION }),
        });
      }
      if (p === '/api/nf-ticket') {
        let paths = [];
        try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
        const tickets = {};
        for (const q of (Array.isArray(paths) ? paths : [])) tickets[q] = { t: 'tk', exp: Date.now() + 600000, uses: -1 };
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets, rejected: [] }) });
      }
      if (p === '/api/nf-file') {
        return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: Buffer.from('# fixture\n\nbody\n') });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
}

/** WS 夹具：ask 域四帧 + **记事本两帧**（askNoteGet/askNotePut 读写 store）。 */
async function routeWs(page, store) {
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = (activeId) => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SESSION, name: 'root', agentName: 'Nebula' }], folders: [], activeId,
    }));
    sendConfig(); sendSessions(SESSION);
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions(m.activeId || SESSION);
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      } else if (m.type === 'askOpen') {
        const abs = (m.file && m.file.absPath) || ABS_A;
        ws.send(JSON.stringify({
          type: 'askState', sessionId: sidOf(abs), sessionKey: 'ask:' + sidOf(abs).slice(4, 8),
          file: abs, fileName: abs.split('/').pop(), messages: [],
        }));
      } else if (m.type === 'askNoteGet') {
        const hit = store.notes.get(m.sessionId);
        ws.send(JSON.stringify({
          type: 'askNoteState', sessionId: m.sessionId,
          text: hit ? hit.text : '', ts: hit ? hit.ts : 0, exists: !!hit,
        }));
      } else if (m.type === 'askNotePut') {
        const text = typeof m.text === 'string' ? m.text : '';
        if (!/^ask-[0-9a-f]{8}$/.test(m.sessionId || '')) {
          ws.send(JSON.stringify({ type: 'askNoteAck', sessionId: m.sessionId, ok: false, error: 'bad-session-id' }));
        } else if (text.length > 256 * 1024) {
          ws.send(JSON.stringify({ type: 'askNoteAck', sessionId: m.sessionId, ok: false, error: 'too-large' }));
        } else {
          const prev = store.notes.get(m.sessionId);
          const ts = Date.now();
          if (text) store.notes.set(m.sessionId, { text, ts }); else store.notes.delete(m.sessionId);
          ws.send(JSON.stringify({
            type: 'askNoteAck', sessionId: m.sessionId, ok: true, ts,
            overwritten: !!(prev && prev.ts > (m.baseTs || 0)),
          }));
        }
      } else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
}

const INIT = () => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('stand_sw', '0');
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  Object.defineProperty(navigator.serviceWorker, 'register', {
    value: () => new Promise(() => {}), configurable: true,
  });
};

/** 造一个客户端上下文（storageState 可继承 origin 存储；breakLocal 模拟隐私模式）。 */
async function newClient(browser, store, { storageState, viewport, breakLocal, seedLegacy } = {}) {
  const ctx = await browser.newContext({ viewport: viewport || { width: 1280, height: 720 }, storageState });
  await ctx.addInitScript(INIT);
  if (seedLegacy) {
    // 旧键兼容（branding 迁移纪律）：改名前写入的 `nebflow_note:<sid>` 拼法必须能读。
    // 迁移是启动即做（branding.js 旧前缀枚举拷贝），这里只在两键都缺时补种。
    await ctx.addInitScript(({ sid, text }) => {
      if (localStorage.getItem('nebflow_note:' + sid) === null
          && localStorage.getItem('stand_note:' + sid) === null) {
        localStorage.setItem('nebflow_note:' + sid, text);
      }
    }, seedLegacy);
  }
  if (breakLocal) {
    await ctx.addInitScript(() => {
      const proto = Storage.prototype;
      const orig = proto.setItem;
      proto.setItem = function (k, v) {
        // 只废「记事本正文/时刻/尺寸」这类体验面键，会话 token 等引导键照常写
        if (typeof k === 'string' && /_(note:|note:ts:|dock:size:)/.test(k)) {
          throw new DOMException('QuotaExceededError', 'QuotaExceededError');
        }
        return orig.call(this, k, v);
      };
    });
  }
  const page = await ctx.newPage();
  await routeHttp(page);
  await routeWs(page, store);
  await page.goto(ORIGIN + '/');
  await page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  return { ctx, page };
}

async function openFile(page, absPath, name) {
  await page.evaluate(({ absPath, name }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + absPath, itemType: 'markdown', title: name,
        content: `# ${name}\n\nfixture body\n`, absPath, pinned: true,
      },
    }));
  }, { absPath, name });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 15000 });
  await page.waitForFunction(() => {
    const f = document.querySelector('.note-fab');
    return f && !f.classList.contains('gate-off');
  }, null, { timeout: 15000 });
  await page.waitForTimeout(300);
}

async function openNote(page) {
  // 幂等开窗：已经开着就不再点（点一下是收起——切卡后窗本就开着，见 askDock
  // syncNoteForFile：切卡只换内容不换开合态）。
  const already = await page.evaluate(() => {
    const d = document.querySelector('.note-dock');
    return !!d && !d.classList.contains('closed');
  });
  if (!already) await page.click('.note-fab').catch(() => {});
  await page.waitForFunction(() => {
    const d = document.querySelector('.note-dock');
    return d && !d.classList.contains('closed');
  }, null, { timeout: 8000 });
  await page.waitForTimeout(360); // 入场动效 + 自动聚焦
}

async function closeNote(page) {
  await page.click('.note-fab').catch(() => {});
  await page.waitForTimeout(280);
}

const noteValue = (page) => page.evaluate(() => {
  const t = document.querySelector('.note-dock textarea');
  return t ? t.value : null;
});

const noteStatus = (page) => page.evaluate(() => {
  const n = document.querySelector('.note-dock [data-note-status]');
  return n ? { hidden: !!n.hidden, text: n.textContent || '' } : null;
});

/** 输入并等两拍：本地镜像（防抖 250ms）+ 服务端推送（防抖 300ms + WS 往返）。
 *  先清空再打 = 每次写入确定（不把上一段残留当成本次内容）。 */
async function typeNote(page, text) {
  await page.click('.note-dock textarea');
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.press('Delete');
  await page.keyboard.type(text);
  await page.waitForTimeout(900);
}

const noteKeys = (page) => page.evaluate(() => {
  const out = {};
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (k && /_note:/.test(k)) out[k] = localStorage.getItem(k);
  }
  return out;
});

/** 两端帧名静态对齐腿：本 spec 的 WS 夹具是自己实现的，帧名打错两边也不会报错，
 *  所以必须静态对照真实前后端源码；顺带钉住落点/上限/sid 闸的位置。 */
function sourceLegs() {
  const read = (p) => { try { return readFileSync(join(REPO, p), 'utf8'); } catch { return ''; } };
  const js = read('src/main/resources/web/js/askDock.js');
  const ws = read('src/main/scala/nebflow/gateway/WsAskHandlers.scala');
  const store = read('src/main/scala/nebflow/core/ask/AskNotes.scala');
  return {
    jsSendsGet: /type:\s*'askNoteGet'/.test(js),
    jsSendsPut: /type:\s*'askNotePut'/.test(js),
    jsHandlesState: /msg\.type === 'askNoteState'/.test(js),
    jsHandlesAck: /msg\.type === 'askNoteAck'/.test(js),
    beRegistersGet: /"askNoteGet"\s*->\s*handleAskNoteGet/.test(ws),
    beRegistersPut: /"askNotePut"\s*->\s*handleAskNotePut/.test(ws),
    beSendsState: /"askNoteState"\.asJson/.test(ws),
    beSendsAck: /"askNoteAck"\.asJson/.test(ws),
    storePath: /dataRoot \/ "ask" \/ "notes"/.test(store),
    storeCap: /MaxBytes:\s*Int\s*=\s*256 \* 1024/.test(store),
    storeSidGuard: /\^ask-\[0-9a-f\]\{8\}\$/.test(store),
    storeAtomic: /AtomicJson\.writeSync\(p, body\)/.test(store),
  };
}

async function dragNote(page, dx, dy) {
  await page.evaluate(({ dx, dy }) => {
    const h = document.querySelector('.note-dock [data-note-resize]');
    const r = h.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const mk = (type, cx, cy, buttons) => new PointerEvent(type, {
      bubbles: true, cancelable: true, composed: true,
      pointerId: 1, pointerType: 'mouse', isPrimary: true, button: 0, buttons, clientX: cx, clientY: cy,
    });
    h.dispatchEvent(mk('pointerdown', x, y, 1));
    h.dispatchEvent(mk('pointermove', x + dx / 2, y + dy / 2, 1));
    h.dispatchEvent(mk('pointermove', x + dx, y + dy, 1));
    h.dispatchEvent(mk('pointerup', x + dx, y + dy, 0));
  }, { dx, dy });
  await page.waitForTimeout(200);
}

const noteSize = (page) => page.evaluate(() => {
  const d = document.querySelector('.note-dock');
  const cs = getComputedStyle(d);
  return {
    h: parseFloat(cs.getPropertyValue('--note-h')) || 0,
    w: parseFloat(cs.getPropertyValue('--ask-dock-w')) || 0,
    dockH: d.getBoundingClientRect().height,
  };
});

test('canvas note persistence matrix', async ({ browser }) => {
  test.setTimeout(300000);
  const readings = { checks: [] };
  const failures = [];
  const check = (name, cond, extra = '') => {
    // eslint-disable-next-line no-console
    console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
    readings.checks.push({ name, ok: !!cond, extra });
    if (!cond) failures.push(name);
  };

  const store = makeStore();
  const TLS = '第一行：记事本内容必须在刷新/重启/换设备后都还在。\n第二行：换行也要原样保留。';

  /* ── P12 两端帧名静态对齐（不依赖浏览器，先跑）── */
  const src = sourceLegs();
  readings.source = src;
  check('P12a 前端两帧 / 后端两帧逐名对齐（askNoteGet、askNotePut；出站 askNoteState、askNoteAck）',
    src.jsSendsGet && src.jsSendsPut && src.beRegistersGet && src.beRegistersPut
    && src.jsHandlesState && src.jsHandlesAck && src.beSendsState && src.beSendsAck,
    JSON.stringify(src));
  check('P12b 落盘单点：<dataRoot>/ask/notes/<sid>.md + 256 KiB 上限 + sid 硬闸 + 原子写',
    src.storePath && src.storeCap && src.storeSidGuard && src.storeAtomic,
    JSON.stringify({ p: src.storePath, c: src.storeCap, g: src.storeSidGuard, a: src.storeAtomic }));

  /* ── 主客户端 ── */
  const c1 = await newClient(browser, store);
  const page = c1.page;
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message).slice(0, 240)));

  await openFile(page, ABS_A, FILE_A);
  await openNote(page);
  await typeNote(page, TLS);
  const v0 = await noteValue(page);
  check('P0 写入生效（textarea 内容 = 输入原文）', v0 === TLS, JSON.stringify({ v: v0 }));

  const keys = await noteKeys(page);
  const bodyKey = 'stand_note:' + sidOf(ABS_A);
  check('P0b 本地镜像落盘（branding 命名空间 + note:<sid> 键，值为纯文本原文）',
    keys[bodyKey] === TLS, JSON.stringify(Object.keys(keys)));
  check('P0c 引擎侧真源落盘（askNotePut 到达服务端存量，内容逐字一致）',
    (store.notes.get(sidOf(ABS_A)) || {}).text === TLS,
    JSON.stringify({ sids: [...store.notes.keys()], sid: sidOf(ABS_A) }));

  /* ── P1 刷新页面 ── */
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  await openFile(page, ABS_A, FILE_A);
  await openNote(page);
  const v1 = await noteValue(page);
  check('P1 写入 → **刷新页面** → 内容还在', v1 === TLS, JSON.stringify({ v: v1 }));

  /* ── P2 引擎重启（同 origin 存储 + 同一服务端存量 + 全新上下文）── */
  const state = await c1.ctx.storageState();
  await c1.ctx.close();
  const c2 = await newClient(browser, store, { storageState: state });
  await openFile(c2.page, ABS_A, FILE_A);
  await openNote(c2.page);
  const v2 = await noteValue(c2.page);
  check('P2 写入 → **引擎重启**（新上下文继承 origin 存储）→ 内容还在', v2 === TLS, JSON.stringify({ v: v2 }));
  const state2 = await c2.ctx.storageState();
  await c2.ctx.close();

  /* ── P3 换设备（空存储 + 同一服务端存量）── */
  const c3 = await newClient(browser, store);
  await openFile(c3.page, ABS_A, FILE_A);
  await openNote(c3.page);
  await c3.page.waitForTimeout(600); // 等 askNoteGet 对账回包
  const v3 = await noteValue(c3.page);
  const keys3 = await noteKeys(c3.page);
  check('P3 写入 → **换设备/换浏览器**（空存储）→ 内容由引擎侧真源补齐（改前必红）',
    v3 === TLS, JSON.stringify({ v: v3 }));
  check('P3b 换设备读到后本地镜像被回填（下次离线也读得到）', keys3[bodyKey] === TLS,
    JSON.stringify(Object.keys(keys3)));

  /* ── P4 两文件互不串 ── */
  await openFile(c3.page, ABS_B, FILE_B);
  await openNote(c3.page);
  const vB0 = await noteValue(c3.page);
  check('P4a 切到另一个文件：记事本为空（每文件一份草稿，不漏别人内容）', vB0 === '', JSON.stringify({ v: vB0 }));
  await typeNote(c3.page, 'B 文件的独立记录');
  await openFile(c3.page, ABS_A, FILE_A);
  await c3.page.waitForTimeout(500);
  const vA2 = await noteValue(c3.page);
  check('P4b 切回 A：A 的内容仍在（不被 B 覆盖）', vA2 === TLS, JSON.stringify({ v: vA2 }));
  check('P4c 两文件的键互不相同（键里带文件身份 sid）',
    ('stand_note:' + sidOf(ABS_A)) !== ('stand_note:' + sidOf(ABS_B))
    && (store.notes.get(sidOf(ABS_B)) || {}).text === 'B 文件的独立记录',
    JSON.stringify({ a: sidOf(ABS_A), b: sidOf(ABS_B), bText: (store.notes.get(sidOf(ABS_B)) || {}).text }));

  /* ── P5 切会话不影响（记事本随文件，不随会话）── */
  const keysBefore = await noteKeys(c3.page);
  await c3.page.evaluate(() => {
    document.dispatchEvent(new CustomEvent('canvas-tab-switched', { detail: { id: 'x' } }));
  });
  await c3.page.waitForTimeout(300);
  const keysAfter = await noteKeys(c3.page);
  const v5 = await noteValue(c3.page);
  check('P5 切会话/重放切卡事件：记事本内容与键都不变（键里无 sessionId）',
    v5 === TLS && JSON.stringify(keysBefore) === JSON.stringify(keysAfter)
    && !Object.keys(keysAfter).some((k) => k.includes(SESSION)),
    JSON.stringify({ v: v5, keys: Object.keys(keysAfter) }));

  /* ── P7 反向验红 B：只清本地镜像，服务端留存 ⇒ 仍在 ── */
  await c3.page.evaluate(() => {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i);
      if (k && /_note:/.test(k)) localStorage.removeItem(k);
    }
  });
  await c3.page.reload({ waitUntil: 'domcontentloaded' });
  await c3.page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  await openFile(c3.page, ABS_A, FILE_A);
  await openNote(c3.page);
  await c3.page.waitForTimeout(700);
  const v7 = await noteValue(c3.page);
  check('P7 反向验红 B：本地镜像清空、只留引擎侧 → 内容仍在（读的是持久层，不是内存/localStorage）',
    v7 === TLS, JSON.stringify({ v: v7 }));

  /* ── P6 反向验红 A：两层都清空 ⇒ 必须为空 ──
     做法必须用**全新上下文**（空 origin 存储 + 已清空的服务端存量）：改后的写入面
     在 pagehide 会同步落一次本地镜像（不掉最后一截输入），所以在**同一个页面**
     里「清存储 → reload」会被上一页的 pagehide flush 合法地重新落盘——那不是
     「读到了内存冒充」，而是持久面正常工作。全新上下文没有这个前序写手，测的才是
     「读点到底读什么」。 */
  store.notes.clear();
  const c6 = await newClient(browser, store);
  await openFile(c6.page, ABS_A, FILE_A);
  await openNote(c6.page);
  await c6.page.waitForTimeout(800);
  const v6 = await noteValue(c6.page);
  check('P6 反向验红 A：本地镜像 + 引擎侧都为空 + 干净上下文 → 显示为空（没有拿旧值/内存态冒充）',
    v6 === '', JSON.stringify({ v: v6 }));
  await c6.ctx.close();

  /* ── P9 resize 尺寸同批持久 ── */
  await typeNote(c3.page, TLS);
  const size0 = await noteSize(c3.page);
  await dragNote(c3.page, -120, -140);
  const size1 = await noteSize(c3.page);
  check('P9a 拖拽确实改了尺寸（--note-h 变大）', size1.h > size0.h + 40,
    JSON.stringify({ before: size0.h, after: size1.h }));
  await closeNote(c3.page);
  await openNote(c3.page);
  await c3.page.waitForTimeout(500);
  const size2 = await noteSize(c3.page);
  check('P9b 关窗再开：尺寸保持（不再回默认 240 档）', Math.abs(size2.h - size1.h) <= 2,
    JSON.stringify({ dragged: size1.h, reopened: size2.h }));
  await c3.page.reload({ waitUntil: 'domcontentloaded' });
  await c3.page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  await openFile(c3.page, ABS_A, FILE_A);
  await openNote(c3.page);
  await c3.page.waitForTimeout(700);
  const size3 = await noteSize(c3.page);
  check('P9c **刷新页面**后尺寸仍在（尺寸偏好与内容同批持久）',
    Math.abs(size3.h - size1.h) <= 4 && Math.abs(size3.w - size1.w) <= 4,
    JSON.stringify({ dragged: size1, afterReload: size3 }));
  await closeNote(c3.page);

  /* ── P10 多标签页并发（同浏览器）── */
  const tabB = await c3.ctx.newPage();
  await routeHttp(tabB);
  await routeWs(tabB, store);
  await tabB.goto(ORIGIN + '/');
  await tabB.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  await openFile(tabB, ABS_A, FILE_A);
  await openNote(tabB);
  await tabB.waitForTimeout(600);
  const vB1 = await noteValue(tabB);
  check('P10a 第二个标签页打开同文件：读到同一份内容', vB1 === TLS, JSON.stringify({ v: vB1 }));
  await typeNote(tabB, '（B 标签追加）');
  const aSawIt = await c3.page.waitForFunction(
    (t) => {
      const el = document.querySelector('.note-dock textarea');
      return !!el && el.value.includes(t);
    },
    '（B 标签追加）',
    { timeout: 6000 },
  ).then(() => true).catch(() => false);
  const vA3 = await noteValue(c3.page);
  check('P10b 另一标签页写入后本页（未编辑态）采纳新内容（storage 事件直通，不静默分叉）',
    aSawIt && typeof vA3 === 'string' && vA3.includes('（B 标签追加）'), JSON.stringify({ v: vA3 }));
  await tabB.close();
  await c3.ctx.close();

  /* ── P8 隐私模式：存储不可用 ⇒ 状态行可读 + 服务端仍存 ── */
  const store2 = makeStore();
  const c4 = await newClient(browser, store2, { breakLocal: true });
  await openFile(c4.page, ABS_A, FILE_A);
  await openNote(c4.page);
  await typeNote(c4.page, '隐私模式下写的内容');
  const st = await noteStatus(c4.page);
  check('P8a 本地存储不可用 ⇒ 窗内状态行出现且文案可读（不许静默丢）',
    st && st.hidden === false && st.text.length > 0, JSON.stringify(st));
  check('P8b 该场景下内容仍落引擎侧真源（不是单纯降级成丢失）',
    (store2.notes.get(sidOf(ABS_A)) || {}).text === '隐私模式下写的内容',
    JSON.stringify({ sid: sidOf(ABS_A), hit: (store2.notes.get(sidOf(ABS_A)) || {}).text }));
  const state4 = await c4.ctx.storageState();
  await c4.ctx.close();
  const c5 = await newClient(browser, store2, { storageState: state4 });
  await openFile(c5.page, ABS_A, FILE_A);
  await openNote(c5.page);
  await c5.page.waitForTimeout(700);
  const v8 = await noteValue(c5.page);
  check('P8c 隐私模式下写的内容在正常环境重开可读回（真源补回本地）', v8 === '隐私模式下写的内容',
    JSON.stringify({ v: v8 }));
  await c5.ctx.close();

  check('z 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

  /* ── P11 旧键兼容（branding 迁移纪律：index.html:300/602 同款读法）── */
  const LEGACY = '改名前写入的旧键内容';
  const store3 = makeStore();
  const c7 = await newClient(browser, store3, { seedLegacy: { sid: sidOf(ABS_A), text: LEGACY } });
  await openFile(c7.page, ABS_A, FILE_A);
  await openNote(c7.page);
  await c7.page.waitForTimeout(700);
  const v11 = await noteValue(c7.page);
  const keys11 = await noteKeys(c7.page);
  check('P11 旧键兼容：`nebflow_note:<sid>` 存量值启动即迁移到 branding 命名空间并被读到',
    v11 === LEGACY && !!keys11['stand_note:' + sidOf(ABS_A)],
    JSON.stringify({ v: v11, keys: Object.keys(keys11) }));
  await c7.ctx.close();

  readings.failures = failures;
  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
  writeFileSync(join(OUT, 'run.log'),
    readings.checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
  if (failures.length) throw new Error(`${failures.length} FAIL: ${failures.join(' | ')}`);
});
