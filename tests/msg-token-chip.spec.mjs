// msg-token-chip.spec.mjs — 消息身份牌「带短名引用」chip 批验收（msgchip 批，
// 作者令 2026-11-01 20:17「消息身份牌系统升级」）。
//
// 驱动 tests/fixtures/msg-token-chip/harness.html（真实前端模块 + 真 marked/katex
// vendor 管线，无 stub）。引擎端点 GET /api/sessions/msg-token/{core} 由 page.route
// 拦截（可编程延迟/载荷/失败），服务端兜底同形态应答保证 WebKit fetch 泄漏零 404。
//
//   T1  渲染面：带名形态 → .msg-token-chip（牌面=短名）；旧裸牌字面不变（逐字节回归钉）
//   T2  畸形矩阵：坏 sid8 / 短 seq / 空名 / 41 字名 / 名内竖线·花括号·换行 → 全部字面透传，
//       零 chip 零 pageerror（不许崩）
//   T3  代码区豁免：围栏内 / 行内代码内的牌 = 代码内容，字面现形（output 批围栏安全同读数）
//   T4  同会话取数：本地腿即时上屏（DOM 用户行 seq 对位）→ 引擎端点权威回填（时间/会话）
//   T5  跨会话取数：sid8 判非本会话 ⇒ 纯引擎腿；head 显示来源会话名
//   T6  权威覆盖：本地对位错位（启发式边角）⇒ 引擎应答落地后覆盖渲染
//   T7  降级：双腿皆 miss → 「原消息不可达」注记；引擎腿网络失败 + 本地命中 → 本地兜底
//   T8  popover 行为：Esc 关 / 点外 pointerdown 关 / 同 chip 再点 toggle / 长文截断+展开收起
//   T9  几何：chip 行内形态；popover 视口夹取（桌面 1440×900 + 手机 375×812，越界 = FAIL）
//   T10 i18n：zh-CN / en 双语（chip hint / popover 文案 / 不可达态）
//   T11 实拍存证：1440×900 桌面 × 暗/亮 + 375×812 手机 × 暗/亮，chip 气泡 + popover 开窗
//       （引擎 chromium + webkit 双引擎全矩阵，SHOTS_DIR）
//
// 全程零 console error 断言。
//
// 运行：node tests/msg-token-chip.spec.mjs              # 双引擎
//      ENGINE=webkit node tests/msg-token-chip.spec.mjs # 仅 WebKit
// 可选 env：PORT=8351、SHOTS_DIR=<dir>（默认 <repo>/.nebflow/evidence/20261101_msgchip/）

import { chromium, webkit } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const FIXTURE = join(HERE, 'fixtures', 'msg-token-chip', 'harness.html');
const PORT = Number(process.env.PORT || 8351);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, '.nebflow', 'evidence', '20261101_msgchip');
const ENGINES = (process.env.ENGINE || 'chromium+webkit').split('+');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 夹具数据 ─────────────────────────────────────────────────────────────────
const SID = 'msgchip-harness-session';
const OTHER_SID = 'msgchip-kernel-session';
/** 与引擎 MsgTokenRegistry.shortCode 同一确定性函数（SHA-256 前 8 位 hex）。 */
const sid8 = (sid) => createHash('sha256').update(sid).digest('hex').slice(0, 8);
const CORE = `${sid8(SID)}-000002`; // 引第 2 条作者消息（harness 里渲染 3 条）
const NAMED = `{{msg:${CORE}|输入统一令}}`;
const OTHER_CORE = `${sid8(OTHER_SID)}-000001`;
const OTHER_NAMED = `{{msg:${OTHER_CORE}|迁移三裁定}}`;
const MISS_CORE = `${sid8(SID)}-999999`;
const MISS_NAMED = `{{msg:${MISS_CORE}|不存在的牌}}`;

const USER_MSGS = [
  { text: '输入统一令：所有面向作者的回复都必须用 output 块包裹，短句直给。', ts: 1761990000000 },
  { text: '迁移三裁定：①合并一律 --no-ff；②release tag 打在 merge commit 上；③不 push。', ts: 1761990060000 },
  { text: '验收口径：桌面 1440 + 手机 375 × 双引擎 × 双主题，取不到原消息要降级不许崩。', ts: 1761990120000 },
];

// 引擎端点应答（可编程；null = 404 found:false）。
const ENGINE_DB = {
  [CORE]: { text: USER_MSGS[1].text, ts: USER_MSGS[1].ts, sessionId: SID, sessionName: 'msgchip harness' },
  [OTHER_CORE]: { text: 'kernel 链上的原话：牌号铸造逻辑不动，以消息头实铸为准。', ts: 1761980000000, sessionId: OTHER_SID, sessionName: 'kernel 迁移裁定' },
};

// ── 静态服务（真 web/ 树 + fixture harness + /api 兜底）─────────────────────
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/tf',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/' || p === '/harness.html') {
    res.writeHead(200, { 'Content-Type': MIME['.html'] });
    res.end(readFileSync(FIXTURE));
    return;
  }
  if (p.startsWith('/api/')) {
    // 兜底应答（route 拦截缺口防漏；正常腿由 page.route 编程应答）。
    const m = p.match(/^\/api\/sessions\/msg-token\/(.+)$/);
    const hit = m ? ENGINE_DB[decodeURIComponent(m[1])] : null;
    res.writeHead(hit ? 200 : 404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(hit ? { found: true, ...hit } : { found: false }));
    return;
  }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(readFileSync(file));
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

// ── 断言器 ───────────────────────────────────────────────────────────────────
let pass = 0;
const fails = [];
function ok(cond, label) {
  if (cond) { pass++; console.log(`  PASS ${label}`); }
  else { fails.push(label); console.error(`  FAIL ${label}`); }
}
const eq = (a, b, label) => ok(Object.is(a, b) || a === b, `${label} (got=${JSON.stringify(String(a).slice(0, 80))})`);

// ── 应用引导 ─────────────────────────────────────────────────────────────────
/** route 行为注册表：每个测试可改写（token core → {status, body, delayMs, abort}）。 */
let routeBehavior = null; // fn(core) => {status?, body?, delayMs?, abort?} | null（null = 默认查 ENGINE_DB）
async function boot(browser, { viewport, colorScheme, locale = 'zh-CN' }) {
  const ctx = await browser.newContext({ viewport, colorScheme, locale, reducedMotion: 'reduce' });
  await ctx.addInitScript((loc) => {
    localStorage.setItem('nebflow_token', 't');
    // i18n 词典键 = 'zh-CN' | 'en'（en-US 会落默认 zh 兜底——按词典键归一）。
    localStorage.setItem('nebflow_locale', loc.startsWith('en') ? 'en' : 'zh-CN');
  }, locale);
  const page = await ctx.newPage();
  page.setDefaultTimeout(9000);
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${String(e.message || e).slice(0, 200)}`));
  // 资源加载噪声（mock 404/abort 的 Failed to load resource）≠ 应用 JS error —— 取数
  // 降级腿（T7/T10）**刻意**制造非 200/断连，浏览器必然记一条资源错误；只滤这一族，
  // 其余 console error 照记照红。
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('Failed to load resource')) errors.push(`console: ${m.text().slice(0, 200)}`);
  });
  await page.route('**/api/sessions/msg-token/**', async (r) => {
    const p = decodeURIComponent(new URL(r.request().url()).pathname);
    const core = decodeURIComponent(p.replace(/^\/api\/sessions\/msg-token\//, ''));
    const b = routeBehavior ? routeBehavior(core) : null;
    const hit = b || (ENGINE_DB[core] ? { status: 200, body: { found: true, ...ENGINE_DB[core] } } : { status: 404, body: { found: false } });
    if (hit.delayMs) await sleep(hit.delayMs);
    if (hit.abort) return r.abort('connectionrefused');
    return r.fulfill({ status: hit.status || 404, contentType: 'application/json', body: JSON.stringify(hit.body || { found: false }) });
  });
  await page.goto(`${BASE}/harness.html?sid=${encodeURIComponent(SID)}`);
  await page.waitForFunction(() => document.title === 'harness-ready');
  return { ctx, page, errors };
}

/** 标准场景：3 条作者行 + 1 条助手回复（引第 2 条带短名）。 */
async function seed(page) {
  for (const m of USER_MSGS) await page.evaluate(({ text, ts }) => window.__renderUserRow(text, ts), m);
  const reply =
    `收到，两条都记下了。\n\n先说第 2 条：${NAMED} —— 合并纪律、tag 位置、push 边界三件都按裁定执行。\n` +
    `第 3 条的验收口径也照办：${MISS_NAMED.replace('|不存在的牌|', '|验收口径|')}。\n\n旧形态对照（应保持字面）：{{msg:${CORE}}}。`;
  await page.evaluate((t) => window.__renderAI(t), reply);
}

// ── 测试体 ───────────────────────────────────────────────────────────────────
async function runSuite(engineName, browser) {
  console.log(`\n===== 引擎 ${engineName} =====`);

  // ── T1 渲染面 ──
  {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    await seed(page);
    const chips = await page.evaluate(() => window.__chips());
    eq(chips.length, 2, 'T1 带名形态产 2 枚 chip（输入统一令 + 验收口径）');
    eq(chips[0] && chips[0].name, '输入统一令', 'T1 chip 牌面 = 短名');
    eq(chips[0] && chips[0].token, NAMED, 'T1 chip data-msg-token = 完整牌面');
    eq(chips[0] && chips[0].role, 'button', 'T1 chip role=button');
    ok(chips.every(c => c.tabindex === '0'), 'T1 chip 可聚焦（tabindex=0）');
    const bubbleText = await page.evaluate(() => {
      const rows = document.querySelectorAll('#chat .row.ai');
      return rows[rows.length - 1].querySelector('.bubble.ai').textContent;
    });
    ok(bubbleText.includes(`{{msg:${CORE}}}`), 'T1 旧裸牌字面保留（现状渲染不变回归钉）');
    ok(!bubbleText.includes(NAMED), 'T1 带名形态不再以字面出现（已被 chip 替换）');
    eq(await page.evaluate(() => document.querySelectorAll('.msg-token-chip').length), 2, 'T1 全文档恰 2 枚 chip');
    eq(errors.length, 0, `T1 零 console/page error`);
    await ctx.close();
  }

  // ── T2 畸形矩阵 ──
  {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    const malformed = [
      `{{msg:zzzzzzzz-000001|坏sid8}}`,
      `{{msg:${sid8(SID)}-0001|短seq}}`,
      `{{msg:${sid8(SID)}-000001|}}`,
      `{{msg:${sid8(SID)}-000001|${'长'.repeat(41)}}}`,
      `{{msg:${sid8(SID)}-000001|a|b}}`,
      `{{msg:${sid8(SID)}-000001|a{b}}}`,
      `{{msg:${sid8(SID)}-000001|名\n换行}}`,
      `{{msg|无核心}}`,
      `{{msg:abc-1|x}}}}`,
    ];
    await page.evaluate((arr) => window.__renderAI('畸形矩阵：\n\n' + arr.join('\n\n')), malformed);
    eq(await page.evaluate(() => document.querySelectorAll('.msg-token-chip').length), 0, 'T2 畸形矩阵零 chip');
    const txt = await page.evaluate(() => {
      const rows = document.querySelectorAll('#chat .row.ai');
      return rows[rows.length - 1].querySelector('.bubble.ai').textContent;
    });
    ok(malformed.every(m => txt.includes(m)), 'T2 全部畸形形态字面透传');
    eq(errors.length, 0, `T2 畸形零 console/page error（不许崩）`);
    await ctx.close();
  }

  // ── T3 代码区豁免 ──
  {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    const fenced = `牌在围栏内是代码内容：\n\n\`\`\`\n${NAMED}\n\`\`\`\n\n行内代码也豁免：\`{{msg:${CORE}|代码内}}\`。\n\n围栏外的真 chip：${NAMED}`;
    await page.evaluate((t) => window.__renderAI(t), fenced);
    eq(await page.evaluate(() => document.querySelectorAll('.msg-token-chip').length), 1, 'T3 仅围栏外产 chip');
    const codeText = await page.evaluate(() => {
      const rows = document.querySelectorAll('#chat .row.ai');
      const b = rows[rows.length - 1].querySelector('.bubble.ai');
      const pre = b.querySelector('pre code');
      return (pre ? pre.textContent : '') + '||' + Array.from(b.querySelectorAll(':scope > p code')).map(c => c.textContent).join(',');
    });
    ok(codeText.includes(NAMED), 'T3 围栏内牌字面保留');
    ok(codeText.includes(`{{msg:${CORE}|代码内}}`), 'T3 行内代码内牌字面保留');
    eq(errors.length, 0, `T3 零 error`);
    await ctx.close();
  }

  // ── T4 同会话取数（本地腿即时 + 引擎回填）──
  {
    routeBehavior = (core) => (core === CORE ? { status: 200, body: { found: true, ...ENGINE_DB[CORE] }, delayMs: 500 } : null);
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    await seed(page);
    await page.click('.msg-token-chip:has-text("输入统一令")');
    const early = await page.evaluate(() => window.__pop());
    ok(!!early, 'T4 点击即弹小窗');
    eq(early.state === 'ok' || early.state === 'loading', true, 'T4 弹窗进入解析态');
    eq(early.sender, '我', 'T4 发送者 = 我');
    ok(early.body.includes(USER_MSGS[1].text.slice(0, 12)), 'T4 本地腿即时上屏（同会话零等待）');
    // 引擎腿 500ms 延迟 ⇒ 会话名/时间（引擎专属回填面）落地 = 权威应答已覆盖。
    await page.waitForFunction(() => {
      const s = document.querySelector('.msg-token-pop-session');
      return s && !s.hidden && (s.textContent || '').length > 0;
    }, { timeout: 4000 });
    const settled = await page.evaluate(() => window.__pop());
    ok(settled.body.includes('迁移三裁定'), 'T4 引擎应答落地（权威全文在场）');
    ok(settled.time.length > 0, 'T4 时间回填（formatFooterTime）');
    ok(settled.foot.includes(CORE), 'T4 牌号脚注在场');
    ok(settled.session.includes('msgchip harness'), 'T4 会话名回填');
    eq(errors.length, 0, `T4 零 error`);
    await ctx.close();
    routeBehavior = null;
  }

  // ── T5 跨会话（纯引擎腿 + 会话名来源行）──
  {
    routeBehavior = null;
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    await seed(page);
    await page.evaluate((t) => window.__renderAI(t), `kernel 链的原话在此：${OTHER_NAMED}`);
    await page.click('.msg-token-chip:has-text("迁移三裁定")');
    await page.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'ok'; });
    const pop = await page.evaluate(() => window.__pop());
    ok(pop.body.includes('牌号铸造逻辑不动'), 'T5 跨会话引擎腿取到原文');
    ok(!pop.sessionHidden && pop.session.includes('kernel 迁移裁定'), 'T5 来源会话名显示');
    eq(errors.length, 0, `T5 零 error`);
    await ctx.close();
  }

  // ── T6 权威覆盖（本地错位 → 引擎校正）──
  {
    // 本地对位错位：把端点应答换成"正确权威文"，而本地第 2 行是另一段——引擎应答必须最终覆盖。
    const authority = '引擎权威文：这才是 seq=2 的真原文（本地启发式对位错了）。';
    routeBehavior = (core) => (core === CORE ? { status: 200, body: { found: true, text: authority, ts: 1761990060000, sessionId: SID, sessionName: 'x' } } : null);
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    await seed(page);
    await page.click('.msg-token-chip:has-text("输入统一令")');
    await page.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'ok'; });
    const pop = await page.evaluate(() => window.__pop());
    ok(pop.body.includes('引擎权威文'), 'T6 引擎应答覆盖本地候选（权威单点）');
    eq(errors.length, 0, `T6 零 error`);
    await ctx.close();
    routeBehavior = null;
  }

  // ── T7 降级双腿 ──
  {
    // 7a：双腿皆 miss（未铸序号 + 端点 404）→ 不可达注记
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    await seed(page);
    await page.evaluate((t) => window.__renderAI(t), `引用一条已清理的牌：${MISS_NAMED}`);
    await page.click('.msg-token-chip:has-text("不存在的牌")');
    await page.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'miss'; });
    const miss = await page.evaluate(() => window.__pop());
    ok(miss.miss && miss.body.includes('原消息不可达'), 'T7a 双腿 miss → 不可达降级注记');
    ok(miss.footHidden, 'T7a 降级态无牌号脚注（宁缺毋假）');
    eq(errors.length, 0, `T7a 降级零 error`);
    await ctx.close();
    // 7b：引擎腿网络失败 + 本地命中 → 本地兜底
    routeBehavior = () => ({ abort: true });
    const b2 = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    const { page: page2, errors: errors2, ctx: ctx2 } = b2;
    await seed(page2);
    await page2.click('.msg-token-chip:has-text("输入统一令")');
    await page2.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'ok'; });
    const local = await page2.evaluate(() => window.__pop());
    ok(local.body.includes('迁移三裁定'), 'T7b 引擎腿失败 → 本地兜底仍可达');
    eq(errors2.length, 0, `T7b 零 page error（网络失败被取数腿吞掉）`);
    await ctx2.close();
    routeBehavior = null;
  }

  // ── T8 popover 行为 ──
  {
    const { ctx, page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    await seed(page);
    // 长文截断 + 展开
    const long = '长文原话。'.repeat(120);
    routeBehavior = (core) => (core === CORE ? { status: 200, body: { found: true, text: long, ts: 1, sessionId: SID, sessionName: '' } } : null);
    await page.click('.msg-token-chip:has-text("输入统一令")');
    // 等**引擎长文**落地（state==='ok' 在本地腿即时上屏时就已翻转——等 clamp 类才是引擎帧）。
    await page.waitForFunction(() => { const b = document.querySelector('.msg-token-pop-body'); return b && b.classList.contains('clamped'); });
    let pop = await page.evaluate(() => window.__pop());
    ok(pop.clamped && !pop.toggleHidden, 'T8 长文默认截断 + 展开钮在场');
    await page.click('.msg-token-pop-toggle');
    pop = await page.evaluate(() => window.__pop());
    ok(!pop.clamped && pop.toggleText.includes('收起'), 'T8 展开生效');
    await page.click('.msg-token-pop-toggle');
    pop = await page.evaluate(() => window.__pop());
    ok(pop.clamped && pop.toggleText.includes('展开'), 'T8 收起生效');
    // Esc 关
    await page.keyboard.press('Escape');
    eq(await page.evaluate(() => !document.querySelector('.msg-token-pop')), true, 'T8 Esc 关闭');
    // 同 chip 再点 = toggle 关
    await page.click('.msg-token-chip:has-text("输入统一令")');
    ok(await page.evaluate(() => !!document.querySelector('.msg-token-pop')), 'T8 再开');
    await page.click('.msg-token-chip:has-text("输入统一令")');
    eq(await page.evaluate(() => !document.querySelector('.msg-token-pop')), true, 'T8 同 chip 再点 = 关闭');
    // 点外关闭
    await page.click('.msg-token-chip:has-text("输入统一令")');
    ok(await page.evaluate(() => !!document.querySelector('.msg-token-pop')), 'T8 再开（点外腿）');
    await page.mouse.click(30, 450);
    eq(await page.evaluate(() => !document.querySelector('.msg-token-pop')), true, 'T8 点外 pointerdown 关闭');
    eq(errors.length, 0, `T8 零 error`);
    await ctx.close();
    routeBehavior = null;
  }

  // ── T9 几何（桌面 + 手机夹取）──
  {
    routeBehavior = null;
    const d = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    await seed(d.page);
    await d.page.click('.msg-token-chip:has-text("输入统一令")');
    await d.page.waitForFunction(() => !!document.querySelector('.msg-token-pop'));
    const dp = await d.page.evaluate(() => window.__pop());
    ok(dp.rect.width > 0 && dp.rect.width <= 360, 'T9 桌面 popover 宽 ≤ 360');
    ok(dp.rect.left >= 0 && dp.rect.right <= 1440 && dp.rect.top >= 0 && dp.rect.bottom <= 900, 'T9 桌面 popover 在视口内');
    const chipBox = await d.page.evaluate(() => { const r = document.querySelector('.msg-token-chip').getBoundingClientRect(); return { h: r.height, w: r.width }; });
    ok(chipBox.h <= 26 && chipBox.w > 20, 'T9 chip 行内小牌形态（高 ≤ 26px）');
    eq(d.errors.length, 0, `T9 桌面零 error`);
    await d.ctx.close();
    const m = await boot(browser, { viewport: { width: 375, height: 812 }, colorScheme: 'dark' });
    await seed(m.page);
    await m.page.click('.msg-token-chip:has-text("输入统一令")');
    await m.page.waitForFunction(() => !!document.querySelector('.msg-token-pop'));
    const mp = await m.page.evaluate(() => window.__pop());
    ok(mp.rect.width <= 375 - 24, 'T9 手机 popover 宽 ≤ vw-24');
    ok(mp.rect.left >= 0 && mp.rect.right <= 375 && mp.rect.top >= 0 && mp.rect.bottom <= 812, 'T9 手机 popover 在视口内');
    eq(m.errors.length, 0, `T9 手机零 error`);
    await m.ctx.close();
  }

  // ── T10 i18n 双语 ──
  {
    const zh = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark', locale: 'zh-CN' });
    await seed(zh.page);
    const titleZh = await zh.page.evaluate(() => document.querySelector('.msg-token-chip').getAttribute('title'));
    eq(titleZh, '查看原消息', 'T10 zh chip title');
    await zh.page.evaluate((t) => window.__renderAI(t), `不可达态：${MISS_NAMED}`);
    await zh.page.click('.msg-token-chip:has-text("不存在的牌")');
    await zh.page.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'miss'; });
    const missZh = await zh.page.evaluate(() => window.__pop());
    ok(missZh.body.includes('原消息不可达'), 'T10 zh 不可达态文案');
    eq(zh.errors.length, 0, `T10 zh 零 error`);
    await zh.ctx.close();
    const en = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark', locale: 'en-US' });
    await seed(en.page);
    const titleEn = await en.page.evaluate(() => document.querySelector('.msg-token-chip').getAttribute('title'));
    eq(titleEn, 'View original message', 'T10 en chip title');
    await en.page.evaluate((t) => window.__renderAI(t), `unreachable: ${MISS_NAMED}`);
    await en.page.click('.msg-token-chip:has-text("不存在的牌")');
    await en.page.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'miss'; });
    const missEn = await en.page.evaluate(() => window.__pop());
    ok(missEn.body.includes('Original message unavailable'), 'T10 en 不可达态文案');
    eq(en.errors.length, 0, `T10 en 零 error`);
    await en.ctx.close();
  }

  // ── T11 实拍存证（桌面 1440 × 暗/亮 + 手机 375 × 暗/亮，chip + popover 开窗）──
  if (SHOTS_DIR) {
    mkdirSync(SHOTS_DIR, { recursive: true });
    routeBehavior = null;
    for (const scheme of ['dark', 'light']) {
      const d = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: scheme });
      await seed(d.page);
      await sleep(200); // 渲染收敛（reducedMotion 已灭 nf-pop 动画；留一拍 rAF/布局）
      await d.page.screenshot({ path: join(SHOTS_DIR, `${engineName}-desktop1440-${scheme}-chips.png`) });
      await d.page.click('.msg-token-chip:has-text("输入统一令")');
      await d.page.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'ok'; });
      await sleep(120);
      await d.page.screenshot({ path: join(SHOTS_DIR, `${engineName}-desktop1440-${scheme}-popover.png`) });
      await d.ctx.close();
      const m = await boot(browser, { viewport: { width: 375, height: 812 }, colorScheme: scheme });
      await seed(m.page);
      await m.page.screenshot({ path: join(SHOTS_DIR, `${engineName}-mobile375-${scheme}-chips.png`) });
      await m.page.click('.msg-token-chip:has-text("输入统一令")');
      await m.page.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'ok'; });
      await sleep(120);
      await m.page.screenshot({ path: join(SHOTS_DIR, `${engineName}-mobile375-${scheme}-popover.png`) });
      await m.ctx.close();
    }
    console.log(`  SHOTS -> ${SHOTS_DIR}（${engineName} 桌面/手机 × 暗/亮 × chip/popover）`);
  }

  return { pass, fails };
}

// ── 主流程 ───────────────────────────────────────────────────────────────────
const browsers = [];
if (ENGINES.includes('chromium')) browsers.push(['chromium', await chromium.launch()]);
if (ENGINES.includes('webkit')) browsers.push(['webkit', await webkit.launch()]);
let totalPass = 0;
const totalFails = [];
try {
  for (const [name, browser] of browsers) {
    const r = await runSuite(name, browser);
    totalPass += r.pass;
    totalFails.push(...r.fails.map(f => `[${name}] ${f}`));
  }
} finally {
  for (const [, b] of browsers) await b.close().catch(() => {});
  webServer.close();
}
console.log(`\n===== 结果：${totalPass} PASS / ${totalFails.length} FAIL =====`);
if (totalFails.length) {
  console.error(totalFails.map(f => 'FAIL ' + f).join('\n'));
  process.exit(1);
}
