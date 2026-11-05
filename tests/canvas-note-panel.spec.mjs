// canvas-note-panel.spec.mjs — Canvas 记事本（note）按钮 + 浮窗机械断言集
// （作者令 2026-11-05 11:38，逐字）：「给canvas 支持ask模式的文件，再加一个按钮，
// 放ask按钮旁边，同样风格的一个note 按钮，方便用户点击记录一些思路的一个记事本
// 就行。不需要复杂功能，风格和ask的保持一致。然后也和ask一样点击出来一个窗口，
// 左上角可以调整窗口大小。」
//
// 判据（同脚本；改前树 = 无 note 件 ⇒ N1..N6 全红，N7/N8 静态腿仍可读）：
//   N1 并列与同风格：note 钮紧跟 ask 钮（DOM 顺序 ask 前 note 后，既有 spec 的
//      `document.querySelector('.ask-fab')` 仍取 ask 钮）；两钮同尺寸/同圆角/同
//      材质计算值（底/描边/浮影/毛玻璃）/同图标档/同一行（bottom 相等），水平缝
//      = 一档 8px（4px 栅格）。
//   N2 点击弹窗：note 窗入场（`.note-dock` 摘 .closed）、ask 窗同时收起（视图
//      排他）、textarea 占满窗并可编辑、自动获得焦点（activeElement）。
//   N3 持久化：输入 → 关窗再开仍在；**刷新页面**后重开仍在（localStorage
//      write-through，键 = branding 命名空间 + note:<sid>）。
//   N4 左上角拖拽改尺寸：拖柄存在/可见/热区 ≥44/落在面板左上外挂 14px；向左上
//      拖 ⇒ 窗变宽变高，且受上下限（顶保险带 44 / 宽下限 260 / 高下限 120）。
//   N5 同一份 resize 实现：源码腿只许一份 `bindResize`/`applySize`/`resizeLimits`，
//      两个面板都以 surface 入参调同一函数（不是两套）。
//   N6 Ask 零回归：note 开 → 点 ask 钮 ⇒ ask 窗开、note 窗收；ask 的列/输入条/发
//      送钮在位，ask 拖柄仍可改列高（本批对 ask 只做「状态机入参化」，行为不变）。
//   N7 可见性闸同拍：无文件卡时两对节点都 `.gate-off`；开文件卡后一起摘闸。
//   N8 复用而非复制：ask.css 里 `.note-*` 规则块**零材质/零色**声明（材质来自
//      glass.css 面级浮件档成员 `.note-body`，字档来自 `.ask-input textarea` 成员）。
//   z 零 pageerror。
//
// 数据面 = 自包含（route 静态 + routeWebSocket 夹具 + 票据腿）：零后端、零 sbt。
// Run : PW_SELFCONTAINED_ONLY=1 npx playwright test tests/canvas-note-panel.spec.mjs
// 取证：NOTE_EVIDENCE_DIR=<目录>（默认 ~/.stand/evidence/canvas-note-button）
import { test } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname, extname, normalize, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
const OUT = process.env.NOTE_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/canvas-note-button';
mkdirSync(OUT, { recursive: true });

const ORIGIN = 'http://localhost:8893';
const SID = 'note-session';
const FILE = '记事本验收.md';
const ABS = '/tmp/canvas-note-fixture/' + FILE;
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
const readWeb = (p) => {
  try { return readFileSync(join(WEB, p), 'utf8'); } catch { return ''; }
};
const stripComment = (s) => s.replace(/\/\*[\s\S]*?\*\//g, ' ');

/** 取出所有「选择器里含 needle」的规则块（本项目 CSS 无嵌套语法）。 */
function blocksWith(src, needle) {
  const clean = stripComment(src);
  const out = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let m;
  while ((m = re.exec(clean)) !== null) {
    const sel = m[1].trim();
    if (sel.includes(needle)) out.push({ sel, body: m[2] });
  }
  return out;
}

function sourceLegs() {
  const js = readWeb('js/askDock.js');
  const ask = readWeb('css/ask.css');
  const glass = readWeb('css/glass.css');
  const noteBlocks = blocksWith(ask, '.note-');
  // 材质/色禁项：记事本面不许自带第二套材质或色值（全走既有单源）。
  // 判据只看**记事本独占**的规则块（选择器里每一项都含 `.note-`）——与 ask 面
  // 共用的成员块（如 `.ask-input textarea, .note-body textarea`）是同一条声明，
  // 正是复用面本身，不该被判成「第二套」。
  const materialRe = /(^|;|\s)(background|background-color|border-color|box-shadow|backdrop-filter|-webkit-backdrop-filter|color)\s*:/;
  const ownBlocks = noteBlocks.filter((b) => b.sel.split(',').every((s) => s.includes('.note-')));
  const dirty = ownBlocks.filter((b) => materialRe.test(b.body)).map((b) => b.sel);
  const glassMembers = blocksWith(glass, '.note-body');
  return {
    bindResizeDefs: (js.match(/function bindResize\(/g) || []).length,
    applySizeDefs: (js.match(/function applySize\(/g) || []).length,
    resizeLimitsDefs: (js.match(/function resizeLimits\(/g) || []).length,
    bindResizeCalls: (js.match(/bindResize\(el\.(ask|note)Surface\)/g) || []).length,
    noteBlocks: noteBlocks.map((b) => b.sel),
    noteOwnBlocks: ownBlocks.map((b) => b.sel),
    noteDirtyMaterial: dirty,
    glassNoteMember: glassMembers.some((b) => b.sel.includes('.ask-input') && /--cv-bg|--glass-bg/.test(b.body)),
    noteFabHasAskFab: /class: 'ask-fab note-fab'/.test(js),
    // 记事本窗**刻意不挂** `.ask-dock` 身份类（复用走选择器成员，类名不叠加：
    // `.ask-dock.closed` 是既有 spec 的判据选择器，第二个同身份节点会让它失真）。
    noteDockIsOwnClass: /class: 'note-dock closed'/.test(js) && !/class: 'ask-dock note-dock/.test(js),
    dataNoteResize: /data-note-resize/.test(js),
  };
}

/* ══ 夹具 ════════════════════════════════════════════════════════════ */
// 长转录：ask 列必须溢出（否则 max-height 不生效，拖高测不出变化 —— 与
// canvas-ask-resize.spec 同一夹具口径）。
const TRANSCRIPT = Array.from({ length: 12 }, (_, i) => ([
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
        for (const q of (Array.isArray(paths) ? paths : [])) tickets[q] = { t: 'tk', exp: Date.now() + 600000, uses: -1 };
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
        status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file),
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

/** 开一个 md 文件卡（不点任何钮）；返回后两个入口钮都应在闸内可见。 */
async function openFile(page) {
  // 手机层：入口钮闸要求根页 = canvas（syncGate 的 mobilePageOk），先导到看板页。
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
    const f = document.querySelector('.note-fab');
    return f && !f.classList.contains('gate-off');
  }, null, { timeout: 15000 });
  await page.waitForTimeout(400);
}

/** 合成指针拖拽（与 canvas-ask-resize.spec 同一段事件序列 = 同一条实现路径）。 */
async function dragNote(page, dx, dy) {
  await page.evaluate(({ dx, dy }) => {
    const h = document.querySelector('.note-dock [data-note-resize]');
    if (!h) return;
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
  await page.waitForTimeout(140);
}

const ZERO = { x: 0, y: 0, w: 0, h: 0, left: 0, right: 0, top: 0, bottom: 0 };
/** 等入场动效落定（transform 归位）——过渡中的 scale(0.9x) 会把几何读成 43.9×43.9。 */
async function settle(page, sel) {
  await page.waitForFunction((s) => {
    const n = document.querySelector(s);
    if (!n) return false;
    const t = getComputedStyle(n).transform;
    return t === 'none' || t === 'matrix(1, 0, 0, 1, 0, 0)';
  }, sel, { timeout: 6000 }).catch(() => {});
  await page.waitForTimeout(60);
}

const measure = (page) => page.evaluate(() => {
  const rect = (n) => {
    if (!n) return { x: 0, y: 0, w: 0, h: 0, left: 0, right: 0, top: 0, bottom: 0, visible: false };
    const r = n.getBoundingClientRect();
    const cs = getComputedStyle(n);
    return {
      x: r.x, y: r.y, w: r.width, h: r.height, left: r.left, right: r.right, top: r.top, bottom: r.bottom,
      visible: cs.display !== 'none' && cs.visibility !== 'hidden' && Number(cs.opacity) > 0.01 && r.width > 0,
    };
  };
  const pick = (n) => {
    if (!n) return null;
    const cs = getComputedStyle(n);
    const svg = n.querySelector('svg');
    const sc = svg ? getComputedStyle(svg) : null;
    return {
      bg: cs.backgroundColor, borderColor: cs.borderTopColor, borderWidth: cs.borderTopWidth,
      radius: cs.borderTopLeftRadius, shadow: cs.boxShadow,
      backdrop: cs.backdropFilter || cs.webkitBackdropFilter || '',
      size: cs.width + '×' + cs.height,
      iconW: sc ? sc.width : '', iconH: sc ? sc.height : '',
      box: rect(n),
      visible: rect(n).visible,
    };
  };
  const askFab = document.querySelector('.ask-fab');
  const noteFab = document.querySelector('.note-fab');
  const askDock = document.querySelector('.ask-dock');
  const noteDock = document.querySelector('.note-dock');
  const noteHandle = noteDock ? noteDock.querySelector('[data-note-resize]') : null;
  const noteBody = noteDock ? noteDock.querySelector('.note-body') : null;
  const noteText = noteDock ? noteDock.querySelector('textarea') : null;
  const askHandle = askDock ? askDock.querySelector('[data-ask-resize]') : null;
  const askCol = askDock ? askDock.querySelector('.ask-col') : null;
  const hit = noteHandle && rect(noteHandle).w > 0 ? [
    [rect(noteHandle).left + rect(noteHandle).w / 2, rect(noteHandle).top + 2],
    [rect(noteHandle).left + rect(noteHandle).w / 2, rect(noteHandle).bottom - 2],
    [rect(noteHandle).left + 2, rect(noteHandle).top + rect(noteHandle).h / 2],
    [rect(noteHandle).right - 2, rect(noteHandle).top + rect(noteHandle).h / 2],
    [rect(noteHandle).left + rect(noteHandle).w / 2, rect(noteHandle).top + rect(noteHandle).h / 2],
  ].map(([x, y]) => {
    const e = document.elementFromPoint(x, y);
    return { ok: !!e && (e === noteHandle || noteHandle.contains(e)), x: Math.round(x), y: Math.round(y) };
  }) : [];
  return {
    askFab: pick(askFab),
    noteFab: pick(noteFab),
    askDock: rect(askDock),
    noteDock: rect(noteDock),
    noteBody: rect(noteBody),
    noteText: rect(noteText),
    noteHandle: rect(noteHandle),
    noteHandleHit: hit,
    askCol: rect(askCol),
    askHandle: rect(askHandle),
    domOrderAskBeforeNote: !!(askFab && noteFab && (askFab.compareDocumentPosition(noteFab) & Node.DOCUMENT_POSITION_FOLLOWING)),
    askDockOpen: !!askDock && !askDock.classList.contains('closed'),
    noteDockOpen: !!noteDock && !noteDock.classList.contains('closed'),
    noteFabPressed: noteFab ? noteFab.getAttribute('aria-pressed') : null,
    focusedIsNoteText: !!noteText && document.activeElement === noteText,
    noteValue: noteText ? noteText.value : null,
    noteH: noteDock ? getComputedStyle(noteDock).getPropertyValue('--note-h').trim() : '',
    noteW: noteDock ? getComputedStyle(noteDock).getPropertyValue('--ask-dock-w').trim() : '',
    gateOff: {
      askFab: askFab ? askFab.classList.contains('gate-off') : null,
      noteFab: noteFab ? noteFab.classList.contains('gate-off') : null,
      askDock: askDock ? askDock.classList.contains('gate-off') : null,
      noteDock: noteDock ? noteDock.classList.contains('gate-off') : null,
    },
    viewport: { w: window.innerWidth, h: window.innerHeight },
  };
});

const radiusPx = (v) => parseFloat(String(v || '').replace('px', ''));

test('canvas note button + panel', async ({ page }) => {
  test.setTimeout(240000);
  const readings = { checks: [] };
  const failures = [];
  page.on('pageerror', (e) => { (readings.pageErrors = readings.pageErrors || []).push(String(e.message).slice(0, 240)); });
  const check = (name, cond, extra = '') => {
    // eslint-disable-next-line no-console
    console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
    readings.checks.push({ name, ok: !!cond, extra });
    if (!cond) failures.push(name);
    return cond;
  };

  /* ── N8 源码腿（静态单源证明，先跑：不依赖浏览器）── */
  const src = sourceLegs();
  readings.source = src;
  check('N8a 记事本钮复用 ask 钮身份类（ask-fab note-fab）；窗用自有类、不叠加 ask-dock（身份类零歧义）',
    src.noteFabHasAskFab && src.noteDockIsOwnClass,
    JSON.stringify({ fab: src.noteFabHasAskFab, dockOwnClass: src.noteDockIsOwnClass }));
  check('N8b ask.css 里 `.note-*` 规则块零材质/零色声明（材质不自带第二套）',
    src.noteDirtyMaterial.length === 0, JSON.stringify({ blocks: src.noteBlocks, dirty: src.noteDirtyMaterial }));
  check('N8c 材质单源：glass.css 面级浮件档里 `.note-body` 与 `.ask-input` 同块同配方',
    src.glassNoteMember, `glassMember=${src.glassNoteMember}`);
  check('N5a 尺寸状态机只有一份：bindResize/applySize/resizeLimits 各一处定义',
    src.bindResizeDefs === 1 && src.applySizeDefs === 1 && src.resizeLimitsDefs === 1,
    JSON.stringify({ bindResizeDefs: src.bindResizeDefs, applySizeDefs: src.applySizeDefs, resizeLimitsDefs: src.resizeLimitsDefs }));
  check('N5b 两个面板都以 surface 入参调同一 bindResize（2 个调用点）',
    src.bindResizeCalls === 2, `calls=${src.bindResizeCalls}`);
  check('N4a 记事本拖柄用独立 data 钩子（ask 的 [data-ask-resize] 保持唯一）',
    src.dataNoteResize, `dataNoteResize=${src.dataNoteResize}`);

  /* ── 夹具 ── */
  await boot(page);

  /* ── N7 闸：无文件卡 ⇒ 两对节点 gate-off ── */
  const g0 = await measure(page);
  check('N7a 无文件卡：ask/note 两组入口与窗全部 gate-off（同一条可见性闸）',
    g0.gateOff.askFab === true && g0.gateOff.noteFab === true
    && g0.gateOff.askDock === true && g0.gateOff.noteDock === true,
    JSON.stringify(g0.gateOff));

  await openFile(page);
  const s0 = await measure(page);
  readings.s0 = s0;
  check('N7b 文件卡在场：两钮一起摘闸（note 钮可见）',
    s0.gateOff.noteFab === false && s0.noteFab && s0.noteFab.visible,
    JSON.stringify({ gate: s0.gateOff, box: s0.noteFab && s0.noteFab.box }));

  /* ── N1 并列 + 同风格 ── */
  const identityDup = await page.evaluate(() => ({
    askDocks: document.querySelectorAll('.ask-dock').length,
    askDockClosedMatches: document.querySelectorAll('.ask-dock.closed').length,
  }));
  check('N1z 身份类唯一：全页 `.ask-dock` 恰 1 个（记事本窗不冒领 ask 窗身份，既有判据选择器零歧义）',
    identityDup.askDocks === 1 && identityDup.askDockClosedMatches <= 1, JSON.stringify(identityDup));
  check('N1a 两个入口钮同在（note 紧跟 ask：DOM 顺序 ask → note，既有 querySelector 仍取 ask）',
    !!s0.askFab && !!s0.noteFab && s0.domOrderAskBeforeNote === true, `domOrder=${s0.domOrderAskBeforeNote}`);
  check('N1b 同尺寸（48×48 档）+ 同圆角（50%）',
    s0.askFab && s0.noteFab && s0.askFab.size === s0.noteFab.size
    && radiusPx(s0.askFab.radius) === radiusPx(s0.noteFab.radius),
    `ask=${s0.askFab && s0.askFab.size}/${s0.askFab && s0.askFab.radius} note=${s0.noteFab && s0.noteFab.size}/${s0.noteFab && s0.noteFab.radius}`);
  check('N1c 同材质（底/描边/描边宽/浮影/毛玻璃五项计算值逐项相等）',
    s0.askFab && s0.noteFab
    && s0.askFab.bg === s0.noteFab.bg && s0.askFab.borderColor === s0.noteFab.borderColor
    && s0.askFab.borderWidth === s0.noteFab.borderWidth && s0.askFab.shadow === s0.noteFab.shadow
    && s0.askFab.backdrop === s0.noteFab.backdrop,
    JSON.stringify({ ask: s0.askFab, note: s0.noteFab }));
  check('N1d 同图标档（svg 宽高逐值相等）',
    s0.askFab && s0.noteFab && s0.askFab.iconW === s0.noteFab.iconW && s0.askFab.iconH === s0.noteFab.iconH,
    `ask=${s0.askFab && s0.askFab.iconW} note=${s0.noteFab && s0.noteFab.iconW}`);
  const gapX = s0.askFab && s0.noteFab ? Math.round(s0.askFab.box.left - s0.noteFab.box.right) : -1;
  check('N1e 同一行同一中线（top/bottom 差 ≤0.5）+ 水平缝 = 8px（4px 栅格一档）',
    s0.askFab && s0.noteFab
    && Math.abs(s0.askFab.box.top - s0.noteFab.box.top) <= 0.5
    && Math.abs(s0.askFab.box.bottom - s0.noteFab.box.bottom) <= 0.5
    && gapX === 8,
    `dyTop=${s0.askFab && (s0.askFab.box.top - s0.noteFab.box.top).toFixed(1)} gap=${gapX}px`);

  /* ── N2 点击弹窗（视图排他 + 可编辑 + 自动聚焦）── */
  await page.click('.note-fab');
  await page.waitForFunction(() => {
    const d = document.querySelector('.note-dock');
    return d && !d.classList.contains('closed');
  }, null, { timeout: 8000 });
  await settle(page, '.note-dock');
  await page.waitForTimeout(420);
  const s1 = await measure(page);
  readings.s1 = s1;
  check('N2a 点 note 钮 ⇒ 记事本窗入场（.note-dock 摘 .closed，body/本体可见）',
    s1.noteDockOpen === true && s1.noteBody.w > 0 && s1.noteBody.h > 0,
    JSON.stringify({ open: s1.noteDockOpen, body: s1.noteBody }));
  check('N2b 视图排他：ask 窗此时收起（一个座位只坐一个浮窗）', s1.askDockOpen === false, `askOpen=${s1.askDockOpen}`);
  check('N2c 文本域占满窗宽，窗高 = --note-h（240px 默认档）',
    s1.noteText.w > 0 && s1.noteBody.w - s1.noteText.w >= 0 && parseFloat(s1.noteH) > 0
    && Math.abs(s1.noteDock.h - parseFloat(s1.noteH)) <= 12,
    JSON.stringify({ text: s1.noteText.w, body: s1.noteBody.w, noteH: s1.noteH, dockH: s1.noteDock.h }));
  await page.waitForTimeout(320);
  const focusS = await measure(page);
  check('N2d 打开即聚焦（activeElement = 记事本 textarea）', focusS.focusedIsNoteText === true,
    `focused=${focusS.focusedIsNoteText}`);
  await page.keyboard.type('第一行：先把检索窗口按龄衰减的想法记下来。\n第二行：下次复盘再验证。');
  const typed = await measure(page);
  readings.typed = typed;
  check('N2e 可编辑（键盘输入落到 textarea，多行内容原样）',
    typeof typed.noteValue === 'string' && typed.noteValue.includes('第一行') && typed.noteValue.split('\n').length >= 2,
    JSON.stringify({ value: typed.noteValue }));
  await page.screenshot({ path: join(OUT, 'note-open.png') }).catch(() => {});

  /* ── N3 持久化（关窗再开 / 刷新后重开）── */
  await page.click('.note-fab'); // 收起
  await page.waitForTimeout(320);
  const closed = await measure(page);
  check('N3a 再点钮收起（.note-dock 回 .closed，内容不丢）',
    closed.noteDockOpen === false && closed.noteValue === typed.noteValue,
    JSON.stringify({ open: closed.noteDockOpen, same: closed.noteValue === typed.noteValue }));
  await page.click('.note-fab'); // 再开
  await page.waitForTimeout(420);
  const reopened = await measure(page);
  check('N3b 关窗再开内容还在（页内往返零丢失）', reopened.noteValue === typed.noteValue,
    JSON.stringify({ value: reopened.noteValue }));

  const storedKeys = await page.evaluate((sid) => {
    const out = {};
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (!k) continue;
      if (k.includes('note:' + sid)) out[k] = localStorage.getItem(k);
    }
    return out;
  }, sidOf(ABS));
  readings.storedKeys = Object.keys(storedKeys);
  check('N3c write-through 落盘（localStorage 键含 branding 命名空间 + note:<sid>，值与输入一致）',
    Object.keys(storedKeys).length === 1
    && Object.values(storedKeys)[0] === typed.noteValue,
    JSON.stringify({ keys: Object.keys(storedKeys) }));

  await page.reload({ waitUntil: 'domcontentloaded' });
  await boot(page).catch(() => {}); // 再走一遍 route/boot（addInitScript 幂等）
  await openFile(page);
  await page.click('.note-fab');
  await page.waitForFunction(() => {
    const d = document.querySelector('.note-dock');
    return d && !d.classList.contains('closed');
  }, null, { timeout: 8000 });
  await settle(page, '.note-dock');
  await page.waitForTimeout(300);
  const afterReload = await measure(page);
  readings.afterReload = afterReload;
  check('N3d **刷新页面**后重开内容仍在（持久化验收）', afterReload.noteValue === typed.noteValue,
    JSON.stringify({ value: afterReload.noteValue }));

  /* ── N4 左上角拖拽改尺寸 ── */
  const r0 = await measure(page);
  check('N4b 拖柄在窗左上角（外挂 14px：handle.left ≈ dock.left − 14 / handle.top ≈ dock.top − 14）',
    r0.noteHandle.w > 0
    && Math.abs(r0.noteHandle.left - (r0.noteDock.left - 14)) <= 2
    && Math.abs(r0.noteHandle.top - (r0.noteDock.top - 14)) <= 2,
    `handle=(${r0.noteHandle.left.toFixed(1)},${r0.noteHandle.top.toFixed(1)}) dock=(${r0.noteDock.left.toFixed(1)},${r0.noteDock.top.toFixed(1)})`);
  check('N4c 拖柄热区 ≥44×44（本体即热区，Apple HIG）', r0.noteHandle.w >= 44 && r0.noteHandle.h >= 44,
    `${r0.noteHandle.w}×${r0.noteHandle.h}`);
  check('N4d 命中面实证：热区两轴中点内缩 2px + 中心 5 点全回到拖柄',
    r0.noteHandleHit.length === 5 && r0.noteHandleHit.every((p) => p.ok),
    JSON.stringify(r0.noteHandleHit.map((p) => p.ok)));

  await dragNote(page, -120, -140);
  const r1 = await measure(page);
  readings.r1 = r1;
  const dW = r1.noteDock.w - r0.noteDock.w;
  const dH = r1.noteBody.h - r0.noteBody.h;
  check('N4e 向左上拖 ⇒ 窗确实变宽（+≥40px）且变高（+≥100px）（左上角是自由角）',
    dW >= 40 && dH >= 100,
    `宽 ${r0.noteDock.w.toFixed(1)} → ${r1.noteDock.w.toFixed(1)} (+${dW.toFixed(1)})；高 ${r0.noteBody.h.toFixed(1)} → ${r1.noteBody.h.toFixed(1)} (+${dH.toFixed(1)})`);
  check('N4f 尺寸由 CSS 变量落地（--note-h 随拖拽变大；文本域跟着变高 = 占满窗）',
    parseFloat(r1.noteH) > parseFloat(r0.noteH) && r1.noteText.h > r0.noteText.h + 50,
    JSON.stringify({ noteH0: r0.noteH, noteH1: r1.noteH, textH0: r0.noteText.h, textH1: r1.noteText.h }));

  await dragNote(page, -3000, -3000);
  const r2 = await measure(page);
  readings.r2 = r2;
  check('N4g 拖到极大受上下限约束（窗顶距视口顶 ≥44 保险带；宽/高有上限，不越视口）',
    r2.noteHandle.top >= 44 - 1 && r2.noteDock.left >= 0 && r2.noteDock.right <= r2.viewport.w + 0.5,
    JSON.stringify({ handleTop: r2.noteHandle.top, dock: r2.noteDock, vp: r2.viewport }));
  await dragNote(page, 3000, 3000);
  const r3 = await measure(page);
  readings.r3 = r3;
  check('N4h 拖到极小受下限约束（宽 ≥260 / 高 ≥120）',
    r3.noteDock.w >= 260 - 0.5 && r3.noteBody.h >= 120 - 0.5,
    JSON.stringify({ w: r3.noteDock.w, h: r3.noteBody.h }));
  await page.screenshot({ path: join(OUT, 'note-resized.png') }).catch(() => {});

  /* ── N6 Ask 零回归（弹窗/列/拖柄/render 面同律）── */
  await page.click('.ask-fab');
  await page.waitForFunction(() => {
    const d = document.querySelector('.ask-dock');
    return d && !d.classList.contains('closed');
  }, null, { timeout: 10000 });
  await settle(page, '.ask-dock');
  await page.waitForTimeout(500);
  const a0 = await measure(page);
  readings.a0 = a0;
  check('N6a 点 ask 钮 ⇒ ask 窗开、记事本窗收（排他反向同样成立）',
    a0.askDockOpen === true && a0.noteDockOpen === false,
    JSON.stringify({ ask: a0.askDockOpen, note: a0.noteDockOpen }));
  check('N6b ask 窗结构完好（列/拖柄在位、列内有夹具回放气泡）',
    a0.askCol.h > 0 && a0.askHandle.w >= 44,
    JSON.stringify({ col: a0.askCol, handle: a0.askHandle }));
  // ask 拖柄仍按老行为改列高（同一段状态机、ask 面入参）
  await page.evaluate(() => {
    const h = document.querySelector('.ask-dock [data-ask-resize]');
    const r = h.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const mk = (type, cx, cy, buttons) => new PointerEvent(type, {
      bubbles: true, cancelable: true, composed: true,
      pointerId: 1, pointerType: 'mouse', isPrimary: true, button: 0, buttons, clientX: cx, clientY: cy,
    });
    h.dispatchEvent(mk('pointerdown', x, y, 1));
    h.dispatchEvent(mk('pointermove', x, y - 120, 1));
    h.dispatchEvent(mk('pointerup', x, y - 120, 0));
  });
  await page.waitForTimeout(160);
  const a1 = await measure(page);
  readings.a1 = a1;
  check('N6c ask 拖柄仍能改列高（+≥100px；本批只把状态机入参化，行为不变）',
    a1.askCol.h - a0.askCol.h >= 100,
    `列高 ${a0.askCol.h.toFixed(1)} → ${a1.askCol.h.toFixed(1)}`);
  const bubbles = await page.evaluate(() => document.querySelectorAll('.ask-dock .ask-bub').length);
  check('N6d ask 转录渲染面零回归（气泡列仍有回放气泡）', bubbles >= 1, `bubbles=${bubbles}`);

  /* ── z 零 pageerror ── */
  check('z 零 pageerror', !(readings.pageErrors && readings.pageErrors.length), JSON.stringify(readings.pageErrors || []));



  writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
  writeFileSync(join(OUT, 'run.log'), readings.checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
  if (failures.length) throw new Error(`${failures.length} FAIL: ${failures.join(' | ')}`);
});

/* ══ 手机腿（body.mobile-layer）：同一对钮、同一个窗、同一段 resize ══════════ */
test('canvas note button + panel (mobile)', async ({ browser }) => {
  test.setTimeout(180000);
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true });
  const page = await ctx.newPage();
  const readings = { checks: [] };
  const failures = [];
  page.on('pageerror', (e) => { (readings.pageErrors = readings.pageErrors || []).push(String(e.message).slice(0, 240)); });
  const check = (name, cond, extra = '') => {
    // eslint-disable-next-line no-console
    console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
    readings.checks.push({ name, ok: !!cond, extra });
    if (!cond) failures.push(name);
  };
  await boot(page);
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 }).catch(() => {});
  await openFile(page);
  const m0 = await measure(page);
  readings.m0 = m0;
  check('m1 手机档两钮都在：同 48 档 + 同圆角 + 同一行（bottom 相等）+ 水平缝 8px',
    m0.askFab && m0.noteFab
    && m0.askFab.size === m0.noteFab.size && m0.askFab.size === '48px×48px'
    && radiusPx(m0.askFab.radius) === radiusPx(m0.noteFab.radius)
    && Math.abs(m0.askFab.box.bottom - m0.noteFab.box.bottom) <= 0.5
    && Math.round(m0.askFab.box.left - m0.noteFab.box.right) === 8,
    JSON.stringify({ ask: m0.askFab && m0.askFab.box, note: m0.noteFab && m0.noteFab.box }));
  check('m2 手机档 note 钮完整在视口内（右缘让位成 ask 钮 + 一格，不出屏）',
    m0.noteFab.box.left >= 0 && m0.noteFab.box.right <= m0.viewport.w + 0.5,
    JSON.stringify({ box: m0.noteFab.box, vw: m0.viewport.w }));
  await page.click('.note-fab');
  await page.waitForFunction(() => {
    const d = document.querySelector('.note-dock');
    return d && !d.classList.contains('closed');
  }, null, { timeout: 8000 });
  await settle(page, '.note-dock');
  await page.waitForTimeout(300);
  const m1 = await measure(page);
  readings.m1 = m1;
  check('m3 手机档记事本窗：宽 = 视口 − 24（与 ask 窗同档）、在场可写',
    Math.abs(m1.noteDock.w - (m1.viewport.w - 24)) <= 1.5 && m1.noteText.w > 0,
    JSON.stringify({ w: m1.noteDock.w, vw: m1.viewport.w, text: m1.noteText.w }));
  await page.keyboard.type('手机腿：记事本也能写。');
  const m2 = await measure(page);
  check('m4 手机档可编辑（键盘输入落到 textarea）',
    typeof m2.noteValue === 'string' && m2.noteValue.includes('手机腿'), JSON.stringify({ v: m2.noteValue }));
  await dragNote(page, -60, -100);
  const m3 = await measure(page);
  readings.m3 = m3;
  check('m5 手机档左上拖拽同样生效（宽或高变大，且不越视口）',
    (m3.noteDock.w > m1.noteDock.w + 10 || m3.noteBody.h > m1.noteBody.h + 40)
    && m3.noteHandle.top >= 44 - 1 && m3.noteDock.left >= 0,
    JSON.stringify({ w0: m1.noteDock.w, w1: m3.noteDock.w, h0: m1.noteBody.h, h1: m3.noteBody.h, handleTop: m3.noteHandle.top }));
  await page.screenshot({ path: join(OUT, 'note-mobile.png') }).catch(() => {});
  check('m6 零 pageerror', !(readings.pageErrors && readings.pageErrors.length), JSON.stringify(readings.pageErrors || []));
  writeFileSync(join(OUT, 'readings-mobile.json'), JSON.stringify(readings, null, 2));
  writeFileSync(join(OUT, 'run-mobile.log'), readings.checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
  await ctx.close();
  if (failures.length) throw new Error(`${failures.length} FAIL: ${failures.join(' | ')}`);
});
