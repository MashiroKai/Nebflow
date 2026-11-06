// bubble-side-color.spec.mjs — 气泡归属方底色的**真渲染**验收钉
// （bubble-side-color 批 2026-11-06）
//
// 作者令（逐字）：「消息气泡的样式现在好像绿的、白的都有。正常应该我们这方发过去的
// 是绿色，对方发过来的是白色。我们这方发的，不管是我发的还是 Agent 发的，都算我们
// 这方发，」
//
// 判据一句话：气泡底色只由「消息归属方」决定 —— 我方（作者本人 + 我方 Agent /
// delegate / 节点 / 后台任务 / 工具注入 / 系统生成）绿；对方（好友 / 群内他人 /
// 设备对端 / 外部通道）白。**没有第三档**。
//
// 🔴 这不是静态断言：页面 = 真 web 树（真模块图、真 CSS、真 index.html）+ WS 帧桩，
//   气泡由**真渲染器**产出（主会话 = chat.js 的 renderUserBubble/appendAiText/finishAi；
//   节点注入行 = chat/injectedRows.js 的 buildInjectedRow；联系人/群 = messages.js
//   导出的 bubbleEl），断言面 = getComputedStyle().backgroundColor（浏览器的计算值，
//   不是我们抄的颜色）。零宿主实例依赖：不碰 :6138 / :8080 / :8097。
//
// 判据（二值）：
//   A1 主会话·作者本人      `.row.user .bubble`         → 我方绿
//   A2 主会话·我方 Agent    `.row.ai  .bubble`          → 我方绿
//   A3 主会话·节点/注入行    `.bubble.injected`          → 我方绿
//   B1 联系人·我方发出      `.fm-msg.out .bubble`       → 我方绿
//   B2 联系人·对方发来      `.fm-msg.in  .bubble`       → 对方白
//   C1 群聊·我方发出        `.fm-msg.out .bubble`       → 我方绿
//   C2 群聊·他人发来        `.fm-msg.in  .bubble`       → 对方白
//   D1 全站只有两种底色      全体气泡 backgroundColor 去重后 == 2 档
//   E1 对比度 AA           绿底正文 ≥ 4.5:1；白底正文 ≥ 4.5:1（亮暗各一档）
//
// Run: node tests/bubble-side-color.spec.mjs            （退出码 0 = 全绿）
//      node tests/bubble-side-color.spec.mjs --json     （机器可读读数）

import { chromium } from 'playwright';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';

const JSON_OUT = process.argv.includes('--json');
let checks = 0;
let failures = 0;
const rows = [];
const ok = (name, cond, extra = '') => {
  checks++;
  if (!cond) failures++;
  if (!JSON_OUT) console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
};

// ── 颜色工具（计算值 → 结构化；对比度按 WCAG 2.1 相对亮度）───────────────────
function parseColor(s) {
  const m = String(s).match(/rgba?\(([^)]+)\)/);
  if (m) {
    const p = m[1].split(/[,/\s]+/).filter(Boolean).map(Number);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  }
  const c = String(s).match(/color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+))?\)/);
  if (c) {
    return {
      r: Math.round(Number(c[1]) * 255), g: Math.round(Number(c[2]) * 255),
      b: Math.round(Number(c[3]) * 255), a: c[4] === undefined ? 1 : Number(c[4]),
    };
  }
  return null;
}
const lum = ({ r, g, b }) => {
  const f = (v) => { const x = v / 255; return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
};
const contrast = (fg, bg) => {
  const a = lum(fg);
  const b = lum(bg);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
};
const over = (c, base) => ({
  r: Math.round(c.r * c.a + base.r * (1 - c.a)),
  g: Math.round(c.g * c.a + base.g * (1 - c.a)),
  b: Math.round(c.b * c.a + base.b * (1 - c.a)),
  a: 1,
});
const near = (c, want, tol = 3) => !!c && Math.abs(c.r - want.r) <= tol
  && Math.abs(c.g - want.g) <= tol && Math.abs(c.b - want.b) <= tol;

const GREEN = { r: 149, g: 236, b: 105 };     // --color-bubble-user #95ec69（我方）
const WHITE = { r: 255, g: 255, b: 255 };     // --color-bubble-ai 亮档 #fff（对方）
const DARK_WHITE = { r: 28, g: 31, b: 39 };   // --color-bubble-ai 暗档 #1c1f27（对方）
const PAGE_LIGHT = { r: 245, g: 246, b: 248, a: 1 };
const PAGE_DARK = { r: 19, g: 22, b: 28, a: 1 };

// ── 在真页面里造三种面的消息（全部经真渲染器）────────────────────────────────
async function buildFaces(page) {
  return page.evaluate(async () => {
    const out = { errors: [] };
    const chat = document.getElementById('chat');

    // ① 主会话：作者本人 / 我方 Agent —— 直接调 chat.js 的渲染出口
    try {
      const mod = await import('/js/chat.js');
      mod.renderUserBubble('夹具·作者本人发的消息（我方）');
      mod.appendAiText('夹具·我方 Agent 的回复（也算我方）');
      mod.finishAi(1234, 'fixture-model');
    } catch (e) { out.errors.push('main: ' + e.message); }

    // ② 主会话：节点 / delegate / 后台任务注入行（chat/injectedRows.js 单点）
    try {
      const inj = await import('/js/chat/injectedRows.js');
      const row = inj.buildInjectedRow('夹具·delegate 完工回执（节点消息）', 'delegate', Date.now(), 'injected');
      chat.appendChild(row);
    } catch (e) { out.errors.push('injected: ' + e.message); }

    // ③ 联系人 / ④ 群聊：messages.js 导出的真渲染器（bubbleEl）
    try {
      const msg = await import('/js/messages.js');
      const host = document.createElement('div');
      host.id = 'fixture-fm-host';
      document.body.appendChild(host);

      const direct = { id: 'c-direct', kind: 'direct', friend: { userId: 'friend-1', name: '好友甲' } };
      const group = { id: 'g-1', kind: 'group', title: '群聊甲', members: [{ userId: 'friend-1', name: '群友乙' }] };
      const fixtures = [
        ['direct', 'out', direct, { id: 'd-out', body: '夹具·联系人窗·我方发出', senderId: 'me', ours: true, createdAt: Date.now() }],
        ['direct', 'in', direct, { id: 'd-in', body: '夹具·联系人窗·对方发来', senderId: 'friend-1', ours: false, createdAt: Date.now() }],
        ['group', 'out', group, { id: 'g-out', body: '夹具·群聊·我方发出', senderId: 'me', ours: true, createdAt: Date.now() }],
        ['group', 'in', group, { id: 'g-in', body: '夹具·群聊·他人发来', senderId: 'friend-1', ours: false, createdAt: Date.now() }],
      ];
      for (const [kind, dir, conv, m] of fixtures) {
        const wrap = msg.bubbleEl(m, conv);
        wrap.dataset.fixtureFace = kind;
        wrap.dataset.fixtureDir = dir;
        host.appendChild(wrap);
      }
    } catch (e) { out.errors.push('fm: ' + e.message); }

    // ⑤ 逐条 dump：sender → 归属方 → 计算出的背景色
    const dump = [];
    const pushRow = (face, sender, el) => {
      if (!el) return;
      const cs = getComputedStyle(el);
      const cls = String(el.className || '');
      const side = el.dataset.bubbleSide
        || (/theirs|peer/.test(cls) ? 'theirs' : 'ours');
      dump.push({ face, sender, side, cls, bg: cs.backgroundColor, color: cs.color });
    };

    for (const row of chat.querySelectorAll('.row')) {
      const b = row.querySelector('.bubble');
      if (!b) continue;
      const sender = row.classList.contains('user')
        ? (b.classList.contains('injected') ? 'node/injected' : 'author')
        : row.classList.contains('ai') ? 'our-agent' : 'other';
      pushRow('main', sender, b);
    }
    for (const wrap of document.querySelectorAll('#fixture-fm-host .fm-msg')) {
      pushRow(wrap.dataset.fixtureFace,
        wrap.dataset.fixtureDir === 'out' ? 'author' : 'peer',
        wrap.querySelector('.bubble'));
    }
    out.dump = dump;
    return out;
  });
}

// ── 主流程 ──────────────────────────────────────────────────────────────────
const browser = await chromium.launch();
const results = {};

for (const theme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: theme });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('pageerror', (e) => consoleErrors.push(e.message));
  await bootChat(page, { history: historyFixture(4, { lastRole: 'ai' }) });
  const built = await buildFaces(page);
  for (const e of built.errors) ok(`[${theme}] 夹具渲染`, false, e);

  const dump = built.dump;
  results[theme] = dump;

  const find = (face, sender) => dump.find((d) => d.face === face && d.sender === sender);
  const theirsWant = theme === 'dark' ? DARK_WHITE : WHITE;
  const pageBg = theme === 'dark' ? PAGE_DARK : PAGE_LIGHT;

  const checkOne = (label, face, sender, want, wantSide) => {
    const d = find(face, sender);
    if (!d) { ok(`[${theme}] ${label}`, false, '未渲染出该条消息'); return; }
    const hit = near(parseColor(d.bg), want);
    rows.push({ theme, label, face, sender, side: d.side, bg: d.bg, want: wantSide, pass: hit && d.side === wantSide });
    ok(`${label}  [${d.side}] → ${d.bg}`, hit && d.side === wantSide, `期望 ${wantSide}（${theme}）`);
  };

  checkOne('A1 主会话·作者本人 = 我方绿', 'main', 'author', GREEN, 'ours');
  checkOne('A2 主会话·我方 Agent = 我方绿', 'main', 'our-agent', GREEN, 'ours');
  checkOne('A3 主会话·节点注入行 = 我方绿', 'main', 'node/injected', GREEN, 'ours');
  checkOne('B1 联系人·我方发出 = 我方绿', 'direct', 'author', GREEN, 'ours');
  checkOne('B2 联系人·对方发来 = 对方白', 'direct', 'peer', theirsWant, 'theirs');
  checkOne('C1 群聊·我方发出 = 我方绿', 'group', 'author', GREEN, 'ours');
  checkOne('C2 群聊·他人发来 = 对方白', 'group', 'peer', theirsWant, 'theirs');

  // D1 全站只有两种底色
  const uniq = [...new Set(dump.map((d) => d.bg))];
  ok(`D1 全部气泡底色去重 == 2 档（${theme}）`, uniq.length === 2, uniq.join(' | '));

  // E1 对比度（真实计算值：前景 color 合成在气泡底 + 页面底上）
  const contrastOf = (d) => {
    const bg = over(parseColor(d.bg), pageBg);
    const fg = over(parseColor(d.color), bg);
    return contrast(fg, bg);
  };
  const oursRow = dump.find((d) => d.side === 'ours');
  const theirsRow = dump.find((d) => d.side === 'theirs');
  const cOurs = oursRow ? contrastOf(oursRow) : 0;
  const cTheirs = theirsRow ? contrastOf(theirsRow) : 0;
  ok(`E1a 我方绿底正文 ≥ 4.5:1（${theme}）`, cOurs >= 4.5, `${cOurs.toFixed(2)}:1（${oursRow && oursRow.color} on ${oursRow && oursRow.bg}）`);
  ok(`E1b 对方白底正文 ≥ 4.5:1（${theme}）`, cTheirs >= 4.5, `${cTheirs.toFixed(2)}:1（${theirsRow && theirsRow.color} on ${theirsRow && theirsRow.bg}）`);
  results[`${theme}Contrast`] = { ours: +cOurs.toFixed(2), theirs: +cTheirs.toFixed(2) };

  ok(`零 pageerror（${theme}）`, consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' / '));
  await ctx.close();
}

await browser.close();

if (JSON_OUT) {
  console.log(JSON.stringify({ results, checks, failures }, null, 2));
} else {
  console.log('\n── dump：三种面 × 逐条消息（sender → 归属方 → 背景色）──');
  for (const r of rows) {
    console.log(`  ${r.pass ? '✓' : '✗'} [${r.theme}] ${r.face.padEnd(6)} ${String(r.sender).padEnd(14)} → ${r.side.padEnd(6)} → ${r.bg}   (期望 ${r.want})`);
  }
  const lc = results.lightContrast || {};
  const dc = results.darkContrast || {};
  console.log(`\n对比度实测：我方绿底 亮 ${lc.ours}:1 / 暗 ${dc.ours}:1；对方白底 亮 ${lc.theirs}:1 / 暗 ${dc.theirs}:1`);
  console.log(`\nbubble-side-color: ${checks} checks, ${failures} failures.`);
}
process.exit(failures === 0 ? 0 : 1);
