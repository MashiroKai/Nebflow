// canvas-ask-resize.spec.mjs — Canvas Ask 面板「拖拽改尺寸 + send/stop 与主会话完全
// 统一」机械断言集（ui-unification-20261103-canvas-ask-resize，2026-11-04 批）。
//
// 作者令（本批唯一判据来源，逐字）：「Canvas 内 Ask 面板：允许用户交互拖拽调整对话框
// 大小（拖柄可见、热区 ≥44px，按 Apple HIG）；发送按钮与终止按钮样式与主会话**完全
// 统一**（同一组件/同一 design-system 令牌，不得另做一套）」
//
// 样板基线：evidence/canvas-ask-resize/mockup.html（静态样张，先出后做）。
//
// 断言（同脚本、改前必红 / 改后必绿；编号 = 任务书 a–c + 手机腿 + z）：
//   a) 拖柄存在 / 可见 / 热区几何 ≥44×44 —— 元素本体即热区（不靠 ::after 外扩），
//      boundingRect 直接达标；再加**命中面实证**：热区四角与中心 elementFromPoint
//      都回到拖柄（证明 44×44 是真可点、不是被盖住的空盒）。
//   b) 模拟拖拽后面板尺寸确实变化，且受上下限约束：
//      b1 拖大 → 宽/列高变大（实测 px）；
//      b2 拖到极大 → 顶缘不越视口（≥44 保险带）、左缘不越视口安全缝、
//         输入条完整在视口内且未被列覆盖（可点面仍成立）；
//      b3 拖到极小 → 宽 ≥260 / 列高 ≥120，输入条与列零相交（不遮输入）；
//      b4 极小态仍可用：发送钮在位可见、命中面 ≥44。
//   c) send/stop 与主会话同源（单一 .ds-* 组件类）：
//      c1 `.ds-turn-btn` 基类在全 css 树**恰一份定义**；
//      c2 ask.css 里 `.ask-input .ask-send` / `.ask-stop` **零规则**（纯身份钩子）；
//      c3 input.css `#send-btn` 块**零几何声明**（宽高/圆角/svg 尺寸已上抬组件层）；
//      c4 两处 DOM 都挂同一组件类（index.html #send-btn/#stop-btn、askDock.js 面板侧）；
//      c5 两处计算值逐属性相等（宽/高/圆角/图标宽高/线宽/底色/描边色）。
//   m) 手机腿：同一实现同一组件 —— 两处逐属性相等（值为手机档 48/22）、拖柄仍 ≥44。
//   z) 零 pageerror。
//
// 数据面 = 自包含（route 静态 + routeWebSocket 夹具 + 票据腿）：零后端、零 sbt。
// Run : PW_SELFCONTAINED_ONLY=1 npx playwright test tests/canvas-ask-resize.spec.mjs
// 取证：ASKRES_EVIDENCE_DIR=<目录>（默认 …/evidence/ui-unification-20261103/canvas-ask-resize）
//       + ASKRES_PHASE=before|after（子目录名）。
// 改前树：ASKRES_WEB=<git archive 出来的改前 web 根>（同一脚本跑改前树 ⇒ 红）。
import { test } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname, extname, normalize, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const WEB = process.env.ASKRES_WEB || join(REPO, 'src', 'main', 'resources', 'web');
const EVIDENCE_ROOT = process.env.ASKRES_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/canvas-ask-resize';
const OUT = join(EVIDENCE_ROOT, process.env.ASKRES_PHASE || 'after');
mkdirSync(OUT, { recursive: true });

const ORIGIN = 'http://localhost:8892';
const SID = 'askres-session';
const FILE = '记忆检索调优笔记.md';
const ABS = '/tmp/canvas-ask-resize-fixture/' + FILE;
/** 客户端同式的 ask 会话 id 派生（sha256(absPath) 前 8 hex，前缀 ask-；askDock.js 同式）。 */
const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/* ══ 源码腿（静态单源证明）════════════════════════════════════════════ */
function walkCss(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walkCss(p, acc);
    else if (extname(p) === '.css') acc.push(p);
  }
  return acc;
}
/** 取某个选择器块（简单一层，够用：本项目 CSS 无嵌套语法）。 */
function blockOf(src, selector) {
  const i = src.indexOf(selector);
  if (i < 0) return '';
  const from = src.indexOf('{', i);
  if (from < 0) return '';
  const to = src.indexOf('}', from);
  return to < 0 ? '' : src.slice(from + 1, to);
}
function readWeb(p) {
  try { return readFileSync(join(WEB, p), 'utf8'); } catch { return ''; }
}

function sourceLegs() {
  const cssFiles = walkCss(join(WEB, 'css'));
  let turnDefs = 0;
  const turnDefFiles = [];
  for (const f of cssFiles) {
    const src = readFileSync(f, 'utf8').replace(/\/\*[\s\S]*?\*\//g, ' ');
    const hits = src.match(/^\.ds-turn-btn\s*\{/gm) || [];
    if (hits.length) { turnDefs += hits.length; turnDefFiles.push(relative(WEB, f) + ':' + hits.length); }
  }
  const ask = readWeb('css/ask.css').replace(/\/\*[\s\S]*?\*\//g, ' ');
  const input = readWeb('css/input.css').replace(/\/\*[\s\S]*?\*\//g, ' ');
  const askSendRules = (ask.match(/\.ask-input\s+\.ask-send(?![-\w])/g) || []).length;
  const askStopRules = (ask.match(/\.ask-input\s+\.ask-stop(?![-\w])/g) || []).length;
  const sendBlock = blockOf(input, '#send-btn {');
  const stopBlock = blockOf(input, '#stop-btn {');
  const geomRe = /(^|;|\s)(width|height|min-width|min-height|border-radius)\s*:/;
  const sendGeom = geomRe.test(sendBlock);
  const stopGeom = /\S/.test(stopBlock) && geomRe.test(stopBlock);
  const html = readWeb('index.html');
  const sendTag = (html.match(/<button id="send-btn"[^>]*>/) || [''])[0];
  const stopTag = (html.match(/<button id="stop-btn"[^>]*>/) || [''])[0];
  const dockJs = readWeb('js/askDock.js');
  return {
    turnDefs, turnDefFiles,
    askSendRules, askStopRules,
    sendGeom, stopGeom,
    sendTag, stopTag,
    dockSend: /class="ask-send ds-turn-btn ds-turn-btn--send"/.test(dockJs),
    dockStop: /class="ask-stop ds-turn-btn ds-turn-btn--stop"/.test(dockJs),
    hasResizeApi: /function bindResize\(/.test(dockJs) && /data-ask-resize/.test(dockJs),
  };
}

/* ══ 夹具 ════════════════════════════════════════════════════════════ */
// 长转录：列必须溢出（否则拖高测不出来 —— max-height 只有内容超了才生效）。
const TRANSCRIPT = Array.from({ length: 14 }, (_, i) => ([
  { role: 'user', text: `第 ${i + 1} 问：检索窗口为什么要按龄衰减，而不是固定 30 天？` },
  { role: 'ai', text: `第 ${i + 1} 答：按龄衰减让近期证据权重更高，同时保留长尾；固定 30 天会在跨季度复盘时丢失有效样本。这段文字用来把气泡撑到两行以上，保证列溢出。` },
])).flat();

async function routeWeb(page) {
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    const p0 = decodeURIComponent(url.pathname);
    const p = p0 === '/' ? '/index.html' : p0;
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      if (p === '/api/nf-ticket') {
        let paths = [];
        try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
        const tickets = {};
        for (const q of (Array.isArray(paths) ? paths : [])) {
          tickets[q] = { t: 'tk', exp: Date.now() + 600000, uses: -1 };
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets, rejected: [] }) });
      }
      if (p === '/api/nf-file') {
        return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: Buffer.from(`# ${FILE}\n\nfixture body\n`) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200, contentType: MIME[extname(file)] || 'application/octet-stream',
        body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
}

async function routeWs(page) {
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      } else if (m.type === 'askOpen') {
        // 服务端按 file.absPath 派生 sid（客户端同式）；夹具必须回同一 sid，
        // 否则 consumeFrame 按 sid 找不到账（回放被忽略 = 列空）。
        const filePath = (m.file && m.file.absPath) || ABS;
        ws.send(JSON.stringify({
          type: 'askState', sessionId: sidOf(filePath), sessionKey: 'ask:' + sidOf(filePath).slice(4, 8),
          file: filePath, fileName: FILE, messages: TRANSCRIPT,
        }));
      } else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
}

async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => {}), configurable: true,
    });
  });
  await routeWeb(page);
  await routeWs(page);
  await page.goto(ORIGIN + '/');
  await page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
}

/** 开一个 md 文件卡并打开 Ask 面板（dock 挂载 + 转录回放到位）。 */
async function openDock(page) {
  // 手机层：FAB 可见性闸要求根页 = canvas（askDock.syncGate 的 mobilePageOk），
  // 先导航到看板页再挂卡。
  const mobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  if (mobile) {
    await page.evaluate(async () => {
      const m = await import('/js/mobile/navController.js');
      m.navigateTo('canvas');
    });
    await page.waitForTimeout(600);
  }
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + absPath, itemType: 'markdown', title: name,
        content: `# ${name}\n\nfixture body\n`, absPath, pinned: true,
      },
    }));
  }, { name: FILE, absPath: ABS });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"]`, { timeout: 15000 });
  await page.waitForFunction(() => {
    const f = document.querySelector('.ask-fab');
    return f && !f.classList.contains('gate-off');
  }, null, { timeout: 15000 });
  await page.click('.ask-fab');
  await page.waitForFunction(() => {
    const d = document.querySelector('.ask-dock');
    return d && !d.classList.contains('closed');
  }, null, { timeout: 15000 });
  await page.waitForFunction(() => document.querySelectorAll('.ask-dock .ask-bub').length > 10, null, { timeout: 15000 });
  await page.waitForTimeout(420);
}

/** 合成指针拖拽（同一段事件序列 = 桌面与手机同一条实现路径）。 */
async function drag(page, dx, dy) {
  await page.evaluate(({ dx, dy }) => {
    const h = document.querySelector('.ask-dock [data-ask-resize]');
    if (!h) return; // 改前树无拖柄：拖不动就是拖不动（b 腿逐条 FAIL，不抛异常）
    const r = h.getBoundingClientRect();
    const x = r.left + r.width / 2;
    const y = r.top + r.height / 2;
    const mk = (type, cx, cy, buttons) => new PointerEvent(type, {
      bubbles: true, cancelable: true, composed: true,
      pointerId: 1, pointerType: 'mouse', isPrimary: true, button: 0, buttons,
      clientX: cx, clientY: cy,
    });
    h.dispatchEvent(mk('pointerdown', x, y, 1));
    h.dispatchEvent(mk('pointermove', x + dx / 2, y + dy / 2, 1));
    h.dispatchEvent(mk('pointermove', x + dx, y + dy, 1));
    h.dispatchEvent(mk('pointerup', x + dx, y + dy, 0));
  }, { dx, dy });
  await page.waitForTimeout(120);
}

/** 面板实测面。 */
// 空件安全：改前树**没有**拖柄（本批才建的件）——measure 必须在缺件时给出
// 零几何读数让 a1..a5 逐条 FAIL，而不是抛「null.getBoundingClientRect」
// 把整条腿打断（那样 red 基线只剩一条异常，判据面丢光）。
const ZERO = { x: 0, y: 0, w: 0, h: 0, left: 0, right: 0, top: 0, bottom: 0 };
const rectOf = (n) => {
  if (!n) return { ...ZERO };
  const r = n.getBoundingClientRect();
  return { x: r.x, y: r.y, w: r.width, h: r.height, left: r.left, right: r.right, top: r.top, bottom: r.bottom };
};
const measure = (page) => page.evaluate(() => {
  const dock = document.querySelector('.ask-dock');
  const handle = document.querySelector('.ask-dock [data-ask-resize]');
  const col = document.querySelector('.ask-dock .ask-col');
  const input = document.querySelector('.ask-dock .ask-input');
  const send = document.querySelector('.ask-dock .ask-send');
  const rd = dock.getBoundingClientRect();
  const rh = handle ? handle.getBoundingClientRect() : { x: 0, y: 0, width: 0, height: 0, left: 0, right: 0, top: 0, bottom: 0 };
  const rc = col.getBoundingClientRect();
  const ri = input.getBoundingClientRect();
  const rs = send.getBoundingClientRect();
  const cs = handle ? getComputedStyle(handle) : null;
  // 命中面实证：沿热区**两轴中点**各内缩 2px + 中心（共 5 点）必须回到拖柄或其子件。
  // 为什么不用四角：拖柄有 10px 圆角（--ds-radius-button），圆角外的像素按规范
  // 本就不可点 —— 拿角点断言圆角件是**假判据**（会放过真缺陷，也会误杀好实现）。
  // 中点四向才是「44×44 是整块可点面、没有被邻居盖住」的正确判据。
  const hit = handle ? [
    [rh.left + rh.width / 2, rh.top + 2],
    [rh.left + rh.width / 2, rh.bottom - 2],
    [rh.left + 2, rh.top + rh.height / 2],
    [rh.right - 2, rh.top + rh.height / 2],
    [rh.left + rh.width / 2, rh.top + rh.height / 2],
  ].map(([x, y]) => {
    const e = document.elementFromPoint(x, y);
    const own = !!e && (e === handle || handle.contains(e));
    // 诊断面：不达标时报出「挡在上面的是谁」，便于定位而不是靠猜。
    const tag = e ? e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && typeof e.className === 'string' ? '.' + e.className.split(/\s+/).filter(Boolean).join('.') : '') : 'null';
    return { ok: own, at: tag, x: Math.round(x), y: Math.round(y) };
  }) : [];
  const panel = document.getElementById('canvas-panel');
  const content = document.getElementById('canvas-content');
  const pr = panel ? panel.getBoundingClientRect() : null;
  const cr = content ? content.getBoundingClientRect() : null;
  return {
    viewport: { w: window.innerWidth, h: window.innerHeight },
    panel: pr ? { left: pr.left, right: pr.right, top: pr.top, bottom: pr.bottom } : null,
    content: cr ? { left: cr.left, right: cr.right, top: cr.top, bottom: cr.bottom } : null,
    dock: { x: rd.x, y: rd.y, w: rd.width, h: rd.height, left: rd.left, right: rd.right, top: rd.top, bottom: rd.bottom },
    handle: { x: rh.x, y: rh.y, w: rh.width, h: rh.height, top: rh.top, bottom: rh.bottom, left: rh.left, visible: !!cs && cs.display !== 'none' && cs.visibility !== 'hidden' && +cs.opacity > 0 },
    handleHit: hit, handleHitAll: hit.length === 5 && hit.every((x) => x.ok),
    col: { w: rc.width, h: rc.height, top: rc.top, bottom: rc.bottom, left: rc.left, right: rc.right },
    input: { x: ri.x, y: ri.y, w: ri.width, h: ri.height, top: ri.top, bottom: ri.bottom },
    send: { x: rs.x, y: rs.y, w: rs.width, h: rs.height, visible: getComputedStyle(send).display !== 'none' },
    overlapColInput: !(rc.bottom <= ri.top || rc.top >= ri.bottom || rc.right <= ri.left || rc.left >= ri.right),
  };
});

/** send/stop 逐属性读数（计算值；display:none 也读得到几何 —— 两态同档的判据面）。 */
const buttonProps = (page) => page.evaluate(() => {
  const pick = (el) => {
    if (!el) return null;
    const cs = getComputedStyle(el);
    const svg = el.querySelector('svg');
    const ss = svg ? getComputedStyle(svg) : null;
    return {
      cls: String(el.className || ''),
      width: cs.width, height: cs.height, borderRadius: cs.borderRadius,
      padding: cs.padding, flexShrink: cs.flexShrink,
      bg: cs.backgroundColor, borderColor: cs.borderColor, color: cs.color,
      iconW: ss ? ss.width : null, iconH: ss ? ss.height : null, strokeW: ss ? ss.strokeWidth : null,
    };
  };
  return {
    mainSend: pick(document.querySelector('#send-btn')),
    mainStop: pick(document.querySelector('#stop-btn')),
    dockSend: pick(document.querySelector('.ask-dock .ask-send')),
    dockStop: pick(document.querySelector('.ask-dock .ask-stop')),
    lucideReady: !!document.querySelector('#send-btn svg'),
  };
});

const PROPS = ['width', 'height', 'borderRadius', 'padding', 'flexShrink', 'bg', 'borderColor', 'color', 'iconW', 'iconH', 'strokeW'];
function diffProps(a, b) {
  if (!a || !b) return { missing: true };
  const out = {};
  for (const k of PROPS) if (String(a[k]) !== String(b[k])) out[k] = [a[k], b[k]];
  return out;
}

/* ══ 用例 ════════════════════════════════════════════════════════════ */
test('a,b,c · 拖拽改尺寸 + send/stop 与主会话完全统一', async ({ page }) => {
  const failures = [];
  const readings = { phase: process.env.ASKRES_PHASE || 'after', web: WEB, checks: [] };
  page.on('pageerror', (e) => {
    (readings.pageErrors = readings.pageErrors || []).push(String(e.message).slice(0, 240));
  });
  const check = (name, cond, extra = '') => {
    // eslint-disable-next-line no-console
    console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
    readings.checks.push({ name, ok: !!cond, extra });
    if (!cond) failures.push(name);
    return cond;
  };

  /* ── c 腿静态面：单源证明（改前树必然红：.ds-turn-btn 不存在）── */
  const src = sourceLegs();
  readings.source = src;
  check('c1 `.ds-turn-btn` 基类全 css 树恰一份定义（单一组件类）',
    src.turnDefs === 1, `defs=${src.turnDefs} ${JSON.stringify(src.turnDefFiles)}`);
  check('c2 ask.css 里 `.ask-input .ask-send` 零规则（面板不再自带第二套形态）',
    src.askSendRules === 0, `rules=${src.askSendRules}`);
  check('c3 ask.css 里 `.ask-input .ask-stop` 零规则（同上）',
    src.askStopRules === 0, `rules=${src.askStopRules}`);
  check('c4 input.css `#send-btn` 块零几何声明（宽高/圆角已上抬组件层）',
    !src.sendGeom, `geom=${src.sendGeom}`);
  check('c5 input.css `#stop-btn` 无几何块残留', !src.stopGeom, `geom=${src.stopGeom}`);
  check('c6 主会话 DOM 挂同一组件类（index.html #send-btn/#stop-btn）',
    /ds-turn-btn ds-turn-btn--send/.test(src.sendTag) && /ds-turn-btn ds-turn-btn--stop/.test(src.stopTag),
    `${src.sendTag} | ${src.stopTag}`);
  check('c7 面板 DOM 挂同一组件类（askDock.js）', src.dockSend && src.dockStop,
    JSON.stringify({ send: src.dockSend, stop: src.dockStop }));

  /* ── 夹具 ── */
  await boot(page);
  await openDock(page);
  await page.waitForFunction(() => !!document.querySelector('#send-btn svg'), null, { timeout: 10000 }).catch(() => {});

  /* ── a 腿：拖柄存在 / 可见 / 热区 ≥44 ── */
  const a = await measure(page);
  readings.a = a;
  check('a1 拖柄存在（.ask-dock [data-ask-resize]）', !!a.handle && a.handle.w > 0, JSON.stringify(a.handle));
  check('a2 拖柄可见（display/visibility/opacity 三关 + 面积 > 0）',
    a.handle.visible, `visible=${a.handle.visible}`);
  check('a3 热区几何 ≥44×44（本体即热区，boundingRect 直接达标 —— Apple HIG）',
    a.handle.w >= 44 && a.handle.h >= 44, `${a.handle.w.toFixed(1)}×${a.handle.h.toFixed(1)}`);
  check('a4 命中面实证：热区两轴中点内缩 2px + 中心 elementFromPoint 全回到拖柄（44×44 真可点）',
    a.handleHitAll, JSON.stringify(a.handleHit));
  check('a5 拖柄在面板左上角（左缘 ≤ 面板左缘 + 2，顶缘 ≤ 面板顶缘 + 2）',
    a.handle.w > 0 && a.handle.left <= a.dock.left + 2 && a.handle.top <= a.dock.top + 2,
    `handle=(${a.handle.left.toFixed(1)},${a.handle.top.toFixed(1)}) dock=(${a.dock.left.toFixed(1)},${a.dock.top.toFixed(1)})`);

  /* ── b1：拖大 → 尺寸确实变化 ── */
  await drag(page, -120, -160);
  const b1 = await measure(page);
  readings.b1 = b1;
  const dW = b1.dock.w - a.dock.w;
  const dH = b1.col.h - a.col.h;
  // 宽的上限受**画布面板宽度**约束（dock 绝对定位在 #canvas-panel 内且面板
  // overflow:hidden ⇒ 超出即被裁剪，用户拿不到），故拖 −120 可能提前触顶；
  // 判据 = 「确实变宽且全程在面板内」，极限值另由 b2f 幂等钉住。
  check('b1a 拖拽后面板变宽（← 拖 −120 ⇒ 宽增 ≥40px，且不越出画布面板）',
    dW >= 40 && (!b1.panel || b1.dock.left >= b1.panel.left - 0.5),
    `宽 ${a.dock.w.toFixed(1)} → ${b1.dock.w.toFixed(1)} (+${dW.toFixed(1)}) panel=${b1.panel ? b1.panel.left.toFixed(1) + '..' + b1.panel.right.toFixed(1) : 'n/a'}`);
  check('b1b 拖拽后面板变高（↑ 拖 −160 ⇒ 列高增 ≥120px）', dH >= 120, `列高 ${a.col.h.toFixed(1)} → ${b1.col.h.toFixed(1)} (+${dH.toFixed(1)})`);
  check('b1c 拖大后输入条仍在列下方、零相交（列只吃自己那份空间）', !b1.overlapColInput, JSON.stringify({ col: b1.col, input: b1.input }));

  /* ── b2：拖到极大 → 上下限 + 安全区 ── */
  await drag(page, -3000, -3000);
  const b2 = await measure(page);
  readings.b2 = b2;
  const topGuard = 44, safe = 12;
  check('b2a 极大态受上限约束：面板顶距视口顶 ≥44（顶保险带，拖柄永远完整可点）',
    b2.dock.top >= topGuard - 1.5, `dock.top=${b2.dock.top.toFixed(1)} (viewport h=${b2.viewport.h})`);
  check('b2b 极大态不拖出视口：左缘 ≥12 安全缝、右缘 ≤ 视口宽',
    b2.dock.left >= safe - 1.5 && b2.dock.right <= b2.viewport.w + 0.5,
    `left=${b2.dock.left.toFixed(1)} right=${b2.dock.right.toFixed(1)} vw=${b2.viewport.w}`);
  check('b2c 极大态拖柄仍在视口内且可点（两轴中点 + 中心命中）',
    b2.handle.w >= 44 && b2.handle.top >= 0 && b2.handle.left >= 0 && b2.handleHitAll,
    `handle.top=${b2.handle.top.toFixed(1)} left=${b2.handle.left.toFixed(1)} hit=${JSON.stringify(b2.handleHit)}`);
  check('b2d 极大态不遮输入：列与输入条零相交，输入条完整在视口内',
    !b2.overlapColInput && b2.input.bottom <= b2.viewport.h + 0.5,
    `overlap=${b2.overlapColInput} input.bottom=${b2.input.bottom.toFixed(1)}`);
  check('b2e 极大态整体仍在画布面板内容框内（宽/顶都不越面板：面板 overflow:hidden ⇒ 越界即裁）',
    !b2.panel || (b2.dock.left >= b2.panel.left + 7 && b2.dock.right <= b2.panel.right + 0.5
      && b2.dock.top >= Math.max(44, b2.panel.top + 7) - 0.5),
    `dock=${[b2.dock.left, b2.dock.top, b2.dock.right].map((n) => n.toFixed(1)).join('/')} panel=${b2.panel ? [b2.panel.left, b2.panel.top, b2.panel.right].map((n) => n.toFixed(1)).join('/') : 'n/a'}`);
  // 极限幂等：再拖一次同样的极大位移，尺寸必须**不再变化**（= 已被上下限钉住）。
  await drag(page, -3000, -3000);
  const b2f = await measure(page);
  readings.b2f = b2f;
  check('b2f 极限幂等：重复极大拖拽后宽高不再增长（上下限真的在生效，不是被内容撑住）',
    Math.abs(b2f.dock.w - b2.dock.w) < 1.5 && Math.abs(b2f.col.h - b2.col.h) < 1.5
    && b2f.dock.top >= Math.max(43, (b2f.content ? b2f.content.top - 1 : 43)),
    `w ${b2.dock.w.toFixed(1)}→${b2f.dock.w.toFixed(1)} colH ${b2.col.h.toFixed(1)}→${b2f.col.h.toFixed(1)} top=${b2f.dock.top.toFixed(1)} contentTop=${b2f.content ? b2f.content.top.toFixed(1) : 'n/a'}`);

  /* ── b3：拖到极小 → 下限 + 可用 ── */
  await drag(page, 3000, 3000);
  const b3 = await measure(page);
  readings.b3 = b3;
  check('b3a 极小态受宽度下限约束（≥260px）', b3.dock.w >= 260 - 1.5, `w=${b3.dock.w.toFixed(1)}`);
  check('b3b 极小态受高度下限约束（列 ≥120px）', b3.col.h >= 120 - 1.5, `col.h=${b3.col.h.toFixed(1)}`);
  check('b3c 极小态不遮输入（列与输入条零相交）', !b3.overlapColInput, JSON.stringify({ col: b3.col, input: b3.input }));
  check('b3d 极小态仍可用：发送钮在位可见、几何 ≥36（未塌陷）',
    b3.send.visible && b3.send.w >= 35.5 && b3.send.h >= 35.5,
    `send=${b3.send.w.toFixed(1)}×${b3.send.h.toFixed(1)} visible=${b3.send.visible}`);
  check('b3e 极小态拖柄热区仍 ≥44×44', b3.handle.w >= 44 && b3.handle.h >= 44,
    `${b3.handle.w.toFixed(1)}×${b3.handle.h.toFixed(1)}`);
  await page.screenshot({ path: join(OUT, 'desktop-after-drag.png') }).catch(() => {});

  /* ── c 腿动态面：两处计算值逐属性相等 ── */
  const bp = await buttonProps(page);
  readings.buttons = bp;
  check('c8 主会话图标已渲染（#send-btn svg 在位 ⇒ 逐属性比较有对象）', bp.lucideReady);
  const dSend = diffProps(bp.mainSend, bp.dockSend);
  const dStop = diffProps(bp.mainStop, bp.dockStop);
  readings.diff = { send: dSend, stop: dStop };
  check('c9 send 与主会话逐属性相等（宽/高/圆角/padding/底色/描边/图标宽高/线宽）',
    Object.keys(dSend).length === 0, JSON.stringify(dSend));
  check('c10 stop 与主会话逐属性相等（同上；busy 态显隐由状态机单点驱动）',
    Object.keys(dStop).length === 0, JSON.stringify(dStop));
  check('c11 两处都挂同一 .ds-* 组件类（send）',
    /(^|\s)ds-turn-btn(\s|$)/.test(bp.mainSend.cls) && /(^|\s)ds-turn-btn(\s|$)/.test(bp.dockSend.cls),
    `${bp.mainSend.cls} | ${bp.dockSend.cls}`);
  check('c12 两处都挂同一 .ds-* 组件类（stop）',
    /(^|\s)ds-turn-btn(\s|$)/.test(bp.mainStop.cls) && /(^|\s)ds-turn-btn(\s|$)/.test(bp.dockStop.cls),
    `${bp.mainStop.cls} | ${bp.dockStop.cls}`);
  check('c13 圆形旧版几何（r50% + 36×36 + 18px 图标）在两处同时成立（作者 10:11 ③ 裁定）',
    bp.mainSend.borderRadius === '50%' && bp.dockSend.borderRadius === '50%'
    && bp.mainSend.width === '36px' && bp.dockSend.width === '36px'
    && bp.mainSend.iconW === '18px' && bp.dockSend.iconW === '18px',
    JSON.stringify({ main: [bp.mainSend.width, bp.mainSend.borderRadius, bp.mainSend.iconW], dock: [bp.dockSend.width, bp.dockSend.borderRadius, bp.dockSend.iconW] }));

  check('z1 页面零 pageerror', !readings.pageErrors || readings.pageErrors.length === 0,
    (readings.pageErrors || []).slice(0, 2).join(' | '));

  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
  writeFileSync(join(OUT, 'run.log'), readings.checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
  if (failures.length) throw new Error(`${failures.length} FAIL: ${failures.join(' | ')}`);
});

test('m · 手机腿：同一实现同一组件（两处逐属性相等 + 拖柄 ≥44）', async ({ browser }) => {
  const failures = [];
  const readings = { phase: process.env.ASKRES_PHASE || 'after', leg: 'mobile', checks: [] };
  const check = (name, cond, extra = '') => {
    // eslint-disable-next-line no-console
    console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
    readings.checks.push({ name, ok: !!cond, extra });
    if (!cond) failures.push(name);
    return cond;
  };

  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => {
    (readings.pageErrors = readings.pageErrors || []).push(String(e.message).slice(0, 240));
  });
  await boot(page);
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 }).catch(() => {});
  await openDock(page);

  const m1 = await measure(page);
  readings.m1 = m1;
  check('m1 手机层拖柄在位且热区 ≥44×44（与桌面同一元素、同一实现）',
    m1.handle.w >= 44 && m1.handle.h >= 44 && m1.handle.visible,
    `${m1.handle.w.toFixed(1)}×${m1.handle.h.toFixed(1)} visible=${m1.handle.visible}`);
  check('m2 手机层命中面实证（两轴中点 + 中心回到拖柄）', m1.handleHitAll, JSON.stringify(m1.handleHit));

  await drag(page, -60, -140);
  const m2 = await measure(page);
  readings.m2 = m2;
  check('m3 手机层拖拽同样生效（宽或列高有变化且不越视口）',
    (m2.dock.w !== m1.dock.w || m2.col.h !== m1.col.h)
    && m2.dock.left >= 11.5 && m2.dock.top >= 0 && m2.input.bottom <= m2.viewport.h + 0.5,
    `w ${m1.dock.w.toFixed(1)}→${m2.dock.w.toFixed(1)} col ${m1.col.h.toFixed(1)}→${m2.col.h.toFixed(1)} left=${m2.dock.left.toFixed(1)}`);

  await drag(page, -3000, -3000);
  const m3 = await measure(page);
  readings.m3 = m3;
  check('m4 手机层拖到极大仍受顶保险带约束（重夹同一实现）',
    m3.handle.w >= 44 && m3.dock.top >= 43 && !m3.overlapColInput && m3.handleHitAll,
    `top=${m3.dock.top.toFixed(1)} overlap=${m3.overlapColInput} hit=${JSON.stringify(m3.handleHit)}`);

  const bp = await buttonProps(page);
  readings.buttons = bp;
  const dSend = diffProps(bp.mainSend, bp.dockSend);
  const dStop = diffProps(bp.mainStop, bp.dockStop);
  readings.diff = { send: dSend, stop: dStop };
  check('m5 手机层 send 与主会话逐属性相等（同组件 ⇒ 同手机档）', Object.keys(dSend).length === 0, JSON.stringify(dSend));
  check('m6 手机层 stop 与主会话逐属性相等', Object.keys(dStop).length === 0, JSON.stringify(dStop));
  check('m7 手机档同档升到 48/22（两处同时成立，非一侧单独放大的旧病）',
    bp.mainSend.width === '48px' && bp.dockSend.width === '48px'
    && bp.mainSend.iconW === '22px' && bp.dockSend.iconW === '22px',
    JSON.stringify({ main: [bp.mainSend.width, bp.mainSend.iconW], dock: [bp.dockSend.width, bp.dockSend.iconW] }));

  await page.screenshot({ path: join(OUT, 'mobile-after-drag.png') }).catch(() => {});
  writeFileSync(join(OUT, 'readings-mobile.json'), JSON.stringify(readings, null, 2));
  writeFileSync(join(OUT, 'run-mobile.log'), readings.checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
  await ctx.close();
  if (failures.length) throw new Error(`${failures.length} FAIL: ${failures.join(' | ')}`);
});
