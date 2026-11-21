#!/usr/bin/env node
// chat-input-autosize.spec.mjs — 主会话输入框「高度自适应 + 光标始终可见」硬闸
// （输入框显示缺陷批 2026-11-21，作者报障「输入的消息会出现内容在输入框中间、
// 没有完全显示」）。
//
// 被验缺陷（本条闸盯的就是它，两条腿都是实测复现的实因，不是推测）：
//   ① 塌陷回填：程序化写值腿（草稿恢复 / 历史召回 / 重发 / 快捷指令 / 语音替换）
//      只写 `value` + `style.height='auto'`，没有任何调用点再量高 ⇒ 20 行草稿装进
//      1 行高的框（基线实测 desktop 可见 8% / mobile 7%），其余被裁。
//   ② 滚动锚点失管：盒子封顶 200px 后内容比视口高，`scrollTop` 只由浏览器在
//      「用户正在打字」时跟手；程序化写值 / 光标移动后它留在旧值（常见 0）⇒
//      视口停在文稿开头而光标在末行 ⇒ 用户看到的正是「中间一段切片」。
//
// 判据（EXIT 0 = 全绿），两端（1440×900 桌面 / 375×812 手机）× 四态
// （空态 / 一行 / 多行 / 超长）：
//   A 自适应：盒子高 = min(内容高, 上限)；内容 ≤ 上限时**完全显示**（scrollH ≤ clientH+1）
//   B 封顶：内容 > 上限时 styleH == 上限且 scrollH > clientH（内部滚动成立，不许截断内容）
//   C 光标可见：光标行落在视口内（镜像盒量真光标 y，不靠行高推算）
//   D 塌陷：从超长态回到空态，盒子收回一行档（不留 200px 空盒）
//   E 打字跟手：逐行真打字（Shift+Enter 换行）到第 18 行，光标仍可见
//   F 静态锚：量高原语只有一处定义；产品里不得再有调用点自己写
//     `style.height='auto'` 再调它（塌陷归原语，调用点只接线）
//   G 零 pageerror
//
// 负控（`--negative-control`）：把 growComposerInput 的「量高 + 锚定」整块退回
// 基线形态（只 `min(scrollHeight,max)`，且不塌陷不锚定）后跑同一套判据 —— **必须红**。
// 红 = 控制有效（exit 0）；中和后仍全绿 ⇒ 本闸无效（exit 1）。
//
// 跑法：
//   node tests/chat-input-autosize.spec.mjs                    # 当前树（应全绿）
//   node tests/chat-input-autosize.spec.mjs --negative-control # 中和修复（应红）
// 截图：OUT 目录（缺省 .stand/tmp/chat-input-perm-card/evidence/autosize/<label>）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = dirname(HERE);
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
const NEGATIVE = process.argv.includes('--negative-control');
const LABEL = NEGATIVE ? 'negative-control' : (process.env.LABEL || 'after');
const EVID = process.env.EVID || join(REPO, '.stand', 'tmp', 'chat-input-perm-card', 'evidence', 'autosize', LABEL);
mkdirSync(EVID, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'chat-input-autosize';

const failures = [];
const passes = [];
const ok = (id, name, extra = '') => { passes.push(`${id} ${name}${extra ? ' — ' + extra : ''}`); console.log(`PASS  ${id} ${name}${extra ? '  — ' + extra : ''}`); };
const bad = (id, name, detail = '') => { failures.push(`${id} ${name}${detail ? ' — ' + detail : ''}`); console.log(`FAIL  ${id} ${name}${detail ? '  — ' + detail : ''}`); };

// ── 静态判据：量高原语单点 + 调用点不自写塌陷 ──────────────────────────────
function staticAssertions() {
  const read = (p) => readFileSync(join(REPO, p), 'utf8');
  const composer = read('src/main/resources/web/js/input/autosize.js'); // 原语叶子（避免与 ws.js 成环）
  const defs = ['src/main/resources/web/js/input.js',
    'src/main/resources/web/js/messages/fmComposer.js',
    'src/main/resources/web/js/chat.js',
    'src/main/resources/web/js/input/slashCommands.js']
    .filter((p) => /export function growComposerInput\s*\(/.test(read(p)));
  defs.length === 0
    ? ok('F1', '量高原语只在 js/input/composer.js 定义一次')
    : bad('F1', 'growComposerInput 不得在多处定义', defs.join(', '));

  const measured = /input\.style\.height = Math\.min\(contentH, max\) \+ 'px'/.test(composer)
    && /if \(measure\) input\.style\.height = 'auto'/.test(composer);
  measured
    ? ok('F2', '原语自己先塌陷再量（调用点无需记得）')
    : bad('F2', '原文必须内含 measure（塌陷）与封顶两刀');

  // 光标测量必须是**镜像盒**（textarea 无 Range），按行数推算在折行下会低估。
  const anchored = /function caretContentY\s*\(/.test(composer)
    && /caretContentY\(input\)/.test(composer)
    && /input\.scrollTop = Math\.max\(0, Math\.min\(next, overflow\)\)/.test(composer);
  anchored
    ? ok('F3', '原语按光标**真位置**锚定 scrollTop（镜像盒，折行也准）')
    : bad('F3', '原语必须按光标锚定 scrollTop', 'growComposerInput 缺 caretContentY 或 scrollTop 写点');

  // 调用点纪律：不许再出现「先写 height='auto' 再调 grow」（塌陷归原语）。
  const doubles = [
    'src/main/resources/web/js/input.js',
    'src/main/resources/web/js/messages/fmComposer.js',
    'src/main/resources/web/js/chatView.js',
    'src/main/resources/web/js/input/slashCommands.js',
  ].filter((p) => /style\.height = 'auto';\s*\n\s*growComposerInput\(/.test(read(p)));
  doubles.length === 0
    ? ok('F4', '调用点不再自写 height=\'auto\' 再调原语（塌陷单点）')
    : bad('F4', '调用点不得自写塌陷', doubles.join(', '));

  const singleWriter = /export function setComposerText\s*\(/.test(composer);
  singleWriter
    ? ok('F5', '程序化写值唯一入口 setComposerText 在位')
    : bad('F5', '程序化写值必须走 setComposerText', 'composer.js 缺该导出');
}

// ── 浏览器判据 ────────────────────────────────────────────────────────────
/** 产品 web 树 + WS 帧桩（形制同 tests/_chat-face-harness.mjs；不起本地端口）。 */
async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('nebflow_input_history', '[]');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    // 负控：把量高原语整块退回基线（不塌陷、不锚定）—— 同一套判据必须变红。
    if (NEGATIVE && file.endsWith(join('js', 'input', 'autosize.js'))) {
      const src = readFileSync(file, 'utf8');
      const head = src.indexOf('export function growComposerInput');
      const tail = src.indexOf('\n}\n', head);
      const dead = 'export function growComposerInput(input, max = COMPOSER_MAX_PX, opts = {}) {\n'
        + '  if (!input) return;\n'
        + "  input.style.height = Math.min(input.scrollHeight, max) + 'px';\n"
        + '}\n';
      return route.fulfill({ status: 200, contentType: MIME['.js'], body: src.slice(0, head) + dead + src.slice(tail + 3) });
    }
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.route('**/api/neblink/status', (r) => r.fulfill({
    json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] },
  }));
  const now = Date.now();
  await page.routeWebSocket(/\/ws/, (ws) => {
    const history = [
      { type: 'user', text: '输入框自适应闸用行', timestamp: now - 5000 },
      { type: 'ai', text: '收到', timestamp: now - 4000 },
    ];
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: history, hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: SID, messages: history, hasMore: false, offset: 0 }));
  });
  await page.goto('http://localhost:1/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await page.waitForTimeout(600);
}

/**
 * 输入框四态读数。光标真实 y 用**镜像盒**量（同宽/同字体/同内距的白空格盒），
 * 不靠「行号 × 行高」推算 —— 折行、内距、行高端档都得进读数。
 */
const MEASURE = () => {
  const ta = document.querySelector('#input');
  const cs = getComputedStyle(ta);
  const r = ta.getBoundingClientRect();
  const lh = parseFloat(cs.lineHeight) || 0;
  const padT = parseFloat(cs.paddingTop) || 0;
  const mirror = document.createElement('div');
  mirror.style.cssText = `position:absolute;left:-99999px;top:0;width:${ta.clientWidth}px;font:${cs.font};`
    + `line-height:${cs.lineHeight};padding:${cs.padding};box-sizing:${cs.boxSizing};`
    + 'white-space:pre-wrap;overflow-wrap:break-word;';
  mirror.textContent = ta.value.slice(0, ta.selectionStart ?? ta.value.length);
  const marker = document.createElement('span');
  marker.textContent = '\u200b';
  mirror.appendChild(marker);
  document.body.appendChild(mirror);
  const caretContentY = marker.getBoundingClientRect().top - mirror.getBoundingClientRect().top;
  document.body.removeChild(mirror);
  // 镜像盒自带同款内距 ⇒ 其盒顶 = textarea 边框盒顶，marker 偏移已是「内距 + 行数 × 行高」，
  // 不可再加 padT（加了就是双计，光标读数虚高 6px ⇒ 判据在边界上假红）。
  const caretVpY = r.top + caretContentY - ta.scrollTop;
  return {
    valueLen: ta.value.length,
    lines: ta.value === '' ? 0 : ta.value.split('\n').length,
    styleH: ta.style.height,
    parsedStyleH: parseFloat(ta.style.height) || 0,
    clientH: ta.clientHeight,
    scrollH: ta.scrollHeight,
    scrollTop: Math.round(ta.scrollTop),
    overflow: ta.scrollHeight - ta.clientHeight,
    lineHeight: Math.round(lh * 100) / 100,
    caretVpY: Math.round(caretVpY * 100) / 100,
    taTop: Math.round(r.top * 100) / 100,
    taBottom: Math.round(r.bottom * 100) / 100,
    caretVisible: caretVpY >= r.top - 1 && caretVpY + lh <= r.bottom + 1,
    barH: Math.round((document.querySelector('#input-bar') || {}).getBoundingClientRect ? document.querySelector('#input-bar').getBoundingClientRect().height : 0),
    bodyScrollW: document.documentElement.scrollWidth,
  };
};

const TEXTS = {
  empty: '',
  oneLine: '一行文本',
  multiLine: ['第一行：多行输入', '第二行', '第三行', '第四行'].join('\n'),
  veryLong: Array.from({ length: 20 }, (_, i) => `第 ${i + 1} 行：超长文本复现，用于验证上限后的内部滚动与光标锚定`).join('\n'),
};

/** 走**产品程序化写值入口**（js/input/composer.js ⑥）—— 与草稿恢复/召回同一条腿。 */
const SET = async (page, text, caretAtEnd = true) => page.evaluate(async ([t, end]) => {
  const { setComposerText } = await import('/js/input/composer.js');
  const ta = document.querySelector('#input');
  setComposerText(ta, t, end ? {} : { caret: 0 });
}, [text, caretAtEnd]);

async function browserAssertions() {
  const browser = await chromium.launch();
  const pageErrors = [];
  try {
    for (const vp of [{ name: 'desktop-1440x900', width: 1440, height: 900 }, { name: 'mobile-375x812', width: 375, height: 812 }]) {
      const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, locale: 'zh-CN' });
      const page = await ctx.newPage();
      page.on('pageerror', (e) => pageErrors.push(`${vp.name}: ${e}`));
      await boot(page);
      const cap = await page.evaluate(async () => (await import('/js/input/composer.js')).COMPOSER_MAX_PX);
      const rec = {};
      for (const [state, text] of Object.entries(TEXTS)) {
        await page.click('#input');
        await SET(page, text);
        await page.waitForTimeout(180);
        const m = await page.evaluate(MEASURE);
        rec[state] = m;
        await page.screenshot({ path: join(EVID, `${vp.name}-${state}.png`) });

        // A/B 自适应与封顶
        const expectH = Math.min(m.scrollH, cap);
        const heightOk = Math.abs(m.clientH - expectH) <= 2;
        if (heightOk) {
          ok(`A1[${vp.name}/${state}]`, '盒高 = min(内容高, 上限)', `clientH=${m.clientH} scrollH=${m.scrollH} cap=${cap}`);
        } else {
          bad(`A1[${vp.name}/${state}]`, '盒高必须等于 min(内容高, 上限)', `clientH=${m.clientH} expect≈${expectH} scrollH=${m.scrollH}`);
        }
        const whollyShown = m.scrollH <= m.clientH + 1;
        if (m.scrollH <= cap) {
          whollyShown
            ? ok(`B1[${vp.name}/${state}]`, '未超上限 ⇒ 内容完全显示（零截断）', `scrollH=${m.scrollH} clientH=${m.clientH}`)
            : bad(`B1[${vp.name}/${state}]`, '未超上限时内容必须完全显示', `scrollH=${m.scrollH} clientH=${m.clientH}`);
        } else {
          (m.clientH <= cap + 1 && m.scrollH > m.clientH + 1)
            ? ok(`B1[${vp.name}/${state}]`, '超上限 ⇒ 封顶 + 内部滚动（内容不丢）', `clientH=${m.clientH} scrollH=${m.scrollH} scrollTop=${m.scrollTop}`)
            : bad(`B1[${vp.name}/${state}]`, '超上限必须封顶并允许内部滚动', `clientH=${m.clientH} cap=${cap} scrollH=${m.scrollH}`);
        }
        // C 光标可见
        m.caretVisible
          ? ok(`C1[${vp.name}/${state}]`, '光标行在视口内', `caretY=${m.caretVpY} 盒=[${m.taTop},${m.taBottom}] scrollTop=${m.scrollTop}`)
          : bad(`C1[${vp.name}/${state}]`, '光标必须在视口内（这条才是"没完全显示"的验收判据）',
            `caretY=${m.caretVpY} 盒=[${m.taTop},${m.taBottom}] scrollTop=${m.scrollTop} scrollH=${m.scrollH} clientH=${m.clientH}`);
      }
      // D 塌陷：超长 → 空
      await SET(page, TEXTS.veryLong);
      await page.waitForTimeout(150);
      await SET(page, '');
      await page.waitForTimeout(150);
      const collapsed = await page.evaluate(MEASURE);
      await page.screenshot({ path: join(EVID, `${vp.name}-collapse-back.png`) });
      (collapsed.clientH <= rec.oneLine.clientH + 2 && collapsed.overflow <= 1)
        ? ok(`D1[${vp.name}]`, '从超长态收回空态 = 一行档（不留 200px 空盒）', `clientH=${collapsed.clientH} oneLine=${rec.oneLine.clientH}`)
        : bad(`D1[${vp.name}]`, '空态必须收回一行档', `clientH=${collapsed.clientH} scrollH=${collapsed.scrollH}`);
      (collapsed.bodyScrollW <= vp.width)
        ? ok(`D2[${vp.name}]`, '零横向溢出')
        : bad(`D2[${vp.name}]`, '不得横向溢出', `scrollWidth=${collapsed.bodyScrollW} vp=${vp.width}`);

      // E 打字跟手：逐行真打字（Shift+Enter 换行）到第 18 行
      await page.click('#input');
      await SET(page, '');
      for (let i = 0; i < 18; i++) {
        await page.keyboard.insertText(`第 ${i + 1} 行：逐行打字超长文本，验证上限后的光标跟手`);
        if (i < 17) await page.keyboard.press('Shift+Enter');
        await page.waitForTimeout(20);
      }
      await page.waitForTimeout(250);
      const typed = await page.evaluate(MEASURE);
      await page.screenshot({ path: join(EVID, `${vp.name}-typing-18lines.png`) });
      typed.caretVisible
        ? ok(`E1[${vp.name}]`, '逐行真打字到第 18 行，光标仍可见', `caretY=${typed.caretVpY} scrollTop=${typed.scrollTop} scrollH=${typed.scrollH}`)
        : bad(`E1[${vp.name}]`, '打字路径光标必须跟手', `caretY=${typed.caretVpY} 盒=[${typed.taTop},${typed.taBottom}] scrollTop=${typed.scrollTop}`);
      await ctx.close();
    }
    pageErrors.length === 0 ? ok('G1', '零 pageerror') : bad('G1', '页面不得报错', pageErrors.slice(0, 3).join(' | '));
  } finally {
    await browser.close().catch(() => {});
  }
}

staticAssertions();
await browserAssertions();

const summary = [
  `chat-input-autosize ${NEGATIVE ? '(negative control)' : ''} label=${LABEL}`,
  `passes=${passes.length} failures=${failures.length}`,
  ...passes.map((p) => '  PASS ' + p),
  ...failures.map((f) => '  FAIL ' + f),
].join('\n');
console.log(summary);
try { writeFileSync(join(EVID, NEGATIVE ? 'gate-negative-control.txt' : 'gate-after.txt'), summary + '\n'); } catch { /* optional */ }

if (NEGATIVE) {
  if (failures.length > 0) {
    console.log(`NEGATIVE-CONTROL: RED as expected (${failures.length} 条判据在修复中和后变红) — 闸有效`);
    process.exit(0);
  }
  console.log('NEGATIVE-CONTROL: still green with the fix neutralized — 本闸无效（判据没有真的盯住自适应/锚定）');
  process.exit(1);
}
if (failures.length) {
  console.log(`chat-input-autosize: FAILED (${failures.length})`);
  process.exit(1);
}
console.log('chat-input-autosize: OK（四态 × 两端：高度自适应 / 封顶内部滚动 / 光标始终可见 / 塌陷收回）');
process.exit(0);
