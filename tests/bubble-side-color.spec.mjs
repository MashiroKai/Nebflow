// bubble-side-color.spec.mjs — 气泡归属方底色的**真渲染**验收钉
// （bubble-side-color 批 2026-11-06）
//
// 作者令（逐字）：「消息气泡的样式现在好像绿的、白的都有。正常应该我们这方发过去的
// 是绿色，对方发过来的是白色。我们这方发的，不管是我发的还是 Agent 发的，都算我们
// 这方发，」
//
// 判据一句话：气泡底色只由「消息归属方」决定 —— 我方（作者本人）取**单点令牌
// `--color-bubble-user` 的当前值**；对方（好友 / 群内他人 / 设备对端）白。
//
// 🔴 色相不再写死在本文件（bubble-lightblue 批 2026-11-07 改）：本文件此前把「我方」
//   钉成字面量 #95ec69（GREEN），于是作者每改一次底色（11:10 禁绿→浅蓝、12:25→近白、
//   14:37→绿、本批→浅蓝）这条断言就假红一次 —— 它断言的本该是「哪一方吃什么令牌」，
//   不是「那个色是什么」。现改为**从页面读 --color-bubble-user 的计算值**：令牌一变
//   断言跟着变，色相裁定留在 base.css 单点，本文件只验归因与结构。
//
// 🔴 两处历史口径已在断言里改为**当下事实**（同一批，均带出处；不改就是留假红）：
//   · A2 我方 Agent —— 作者令 2026-11-07 14:37（bubble-final 批 a62dd7300）把配色
//     模型从「我方/对方」改成「作者本人 vs 助手」：`.bubble.ai` = 助手白档，
//     **不再是我方档**；归属方判定（side）仍是 ours（Agent 代表本机账号发出），
//     两者分属不同轴，断言按当前事实分开写。
//   · A3 节点注入行 —— 同批将 `.injected` 定为通知面 carve（sapphire 淡染，
//     glass.css R2 精确点名），不再是第三档对话底色。
//
// 🔴 这不是静态断言：页面 = 真 web 树（真模块图、真 CSS、真 index.html）+ WS 帧桩，
//   气泡由**真渲染器**产出（主会话 = chat.js 的 renderUserBubble/appendAiText/finishAi；
//   节点注入行 = chat/injectedRows.js 的 buildInjectedRow；联系人/群 = messages.js
//   导出的 bubbleEl），断言面 = getComputedStyle().backgroundColor（浏览器的计算值，
//   不是我们抄的颜色）。零宿主实例依赖：不碰 :6138 / :8080 / :8097。
//
// 判据（二值）：
//   A1 主会话·作者本人      `.row.user .bubble`         → 我方档（--color-bubble-user 当前值）
//   A2 主会话·我方 Agent    `.row.ai  .bubble`          → 助手白档（14:37 起，归属仍 ours）
//   A3 主会话·节点/注入行    `.bubble.injected`          → 通知面 carve（sapphire 淡染）
//   B1 联系人·我方发出      `.fm-msg.out .bubble`       → 我方档（同上）
//   B2 联系人·对方发来      `.fm-msg.in  .bubble`       → 对方白
//   C1 群聊·我方发出        `.fm-msg.out .bubble`       → 我方档（同上）
//   C2 群聊·他人发来        `.fm-msg.in  .bubble`       → 对方白
//   D1 底色档数封闭           全体气泡 backgroundColor 去重后 ≤ 3 档（作者/对方/注入 carve）
//   E1 对比度 AA           我方档正文 ≥ 4.5:1；白底正文 ≥ 4.5:1（亮暗各一档）
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

// 我方底色**不写字面量**：运行时从页面读 --color-bubble-user 的计算值（见文件头
// 「色相不再写死在本文件」）。WHITE / DARK_WHITE 仍是对方白档的期望（= --color-bubble-ai
// 亮 #fff / 暗 #1c1f27），色相裁定同样在 base.css 单点。
const WHITE = { r: 255, g: 255, b: 255 };     // --color-bubble-ai 亮档 #fff（对方）
const DARK_WHITE = { r: 28, g: 31, b: 39 };   // --color-bubble-ai 暗档 #1c1f27（对方）
// 注入行 = 通知面 carve（glass.css R2 精确点名 .bubble.injected，sapphire 12% 淡染）。
const INJECT_CARVE = { r: 91, g: 127, b: 191 };
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
  /* 我方期望色 = 页面里 --color-bubble-user 的**计算值**（本主题解析后的实值）。
     读数路径：把令牌值挂到一个临时节点上取 computed color —— 令牌若是 color-mix()
     这类函数值，这步会得到浏览器解析后的最终色，比字符串比较稳。 */
  const oursCss = await page.evaluate(() => {
    const probe = document.createElement('div');
    probe.style.color = getComputedStyle(document.documentElement)
      .getPropertyValue('--color-bubble-user').trim();
    document.body.appendChild(probe);
    const v = getComputedStyle(probe).color;
    probe.remove();
    return v;
  });
  const oursWant = parseColor(oursCss) || { r: -1, g: -1, b: -1 };

  const checkOne = (label, face, sender, want, wantSide) => {
    const d = find(face, sender);
    if (!d) { ok(`[${theme}] ${label}`, false, '未渲染出该条消息'); return; }
    const hit = near(parseColor(d.bg), want);
    rows.push({ theme, label, face, sender, side: d.side, bg: d.bg, want: wantSide, pass: hit && d.side === wantSide });
    ok(`${label}  [${d.side}] → ${d.bg}`, hit && d.side === wantSide, `期望 ${wantSide}（${theme}）`);
  };

  /* 我方/对方 = 令牌归因（色相来自 --color-bubble-user / --color-bubble-ai 单点，
     本文件不写字面量）。A2/A3 按 2026-11-07 14:37 起的当下事实断言（见文件头）。 */
  checkOne('A1 主会话·作者本人 = 我方档', 'main', 'author', oursWant, 'ours');
  checkOne('A2 主会话·我方 Agent = 助手白档（作者令 2026-11-07 14:37）', 'main', 'our-agent', theirsWant, 'ours');
  checkOne('A3 主会话·节点注入行 = 通知面 carve（sapphire 淡染）', 'main', 'node/injected', INJECT_CARVE, 'ours');
  checkOne('B1 联系人·我方发出 = 我方档', 'direct', 'author', oursWant, 'ours');
  checkOne('B2 联系人·对方发来 = 对方白', 'direct', 'peer', theirsWant, 'theirs');
  checkOne('C1 群聊·我方发出 = 我方档', 'group', 'author', oursWant, 'ours');
  checkOne('C2 群聊·他人发来 = 对方白', 'group', 'peer', theirsWant, 'theirs');

  /* D1 底色档数封闭：作者档 / 对方白档 / 注入 carve 三档封顶（carve 是通知面，
     2026-11-07 14:37 起的既有裁定，不再是「第三档对话底色」）。 */
  const uniq = [...new Set(dump.map((d) => d.bg))];
  ok(`D1 全部气泡底色去重 ≤ 3 档（作者/对方/注入 carve，${theme}）`, uniq.length <= 3, uniq.join(' | '));

  // E1 对比度（真实计算值：前景 color 合成在气泡底 + 页面底上）
  const contrastOf = (d) => {
    const bg = over(parseColor(d.bg), pageBg);
    const fg = over(parseColor(d.color), bg);
    return contrast(fg, bg);
  };
  /* E1 = 我方档与对方档的正文对比度。取「底色确实落在该档令牌值上」的那一行 ——
     不能再取「side === ours 的第一行」：2026-11-07 14:37 起我方 Agent 虽仍归属 ours，
     底色却是助手白档，按 side 取会拿白底读成「我方档对比度」（修前读数即此形态）。 */
  const oursRow = dump.find((d) => d.side === 'ours' && near(parseColor(d.bg), oursWant));
  const theirsRow = dump.find((d) => d.side === 'theirs');
  const cOurs = oursRow ? contrastOf(oursRow) : 0;
  const cTheirs = theirsRow ? contrastOf(theirsRow) : 0;
  ok(`E1a 我方档正文 ≥ 4.5:1（${theme}）`, cOurs >= 4.5, `${cOurs.toFixed(2)}:1（${oursRow && oursRow.color} on ${oursRow && oursRow.bg}）`);
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
  console.log(`\n对比度实测：我方档 亮 ${lc.ours}:1 / 暗 ${dc.ours}:1；对方白底 亮 ${lc.theirs}:1 / 暗 ${dc.theirs}:1`);
  console.log(`\nbubble-side-color: ${checks} checks, ${failures} failures.`);
}
process.exit(failures === 0 ? 0 : 1);
