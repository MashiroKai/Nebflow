// slash-clear-compact.spec.mjs — `/clear` + `/compact` **摘除**验收（用户指令 2026-10-09 23:12：
// 「把/slash命令 /clear /compact删掉」）。前身 = 回挂验收（D1-B 白名单形态，git 历史）。
//
// 断言面（**默认面** = 总闸 `stand_slash.enabled` 关、白名单 = {`/model`}）：
//   S1 默认面命令面：键入 `/` 的列表**恰为** ["/model"]；/clear、/compact 降级为
//      封存面 —— handleSlash 全 false（纯文本路径），零命令帧；/model 零回归
//   S2 `/clear` 纯文本路径 ⇒ 照常直发 content 帧、零 command 帧、无「上下文已清除」气泡
//   S3 `/compact` 纯文本路径 ⇒ 照常直发 content 帧、零 command 帧、COMPACT pill 不出现
//   S4 封存面零泄漏（反向读数 + 阳性对照）：/onboarding、/fork、/plan、skill 命令、
//      未知 /xxx 照旧纯文本 —— 摘除不改变封存面语义
//   S5 总闸语义：gate=1 时整张内置表照旧放行，且**不再含** /clear /compact
//
// 隔离：自包含——静态文件按 route 直接读盘（无静态服务器、无端口、无实例）、WS = routeWebSocket
// mock。不碰宿主 8080 / 宿主进程；收尾 `browser.close()`（try/finally）。
//
// 被测树覆盖：`SLASH_WEB_ROOT=<abs web dir>`（改前红 / 变异红用它指向 `git archive` 导出的临时树）。
// Run: node tests/slash-clear-compact.spec.mjs
import { chromium } from 'playwright-core';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(process.env.SLASH_WEB_ROOT || join(HERE, '..', 'src', 'main', 'resources', 'web'));
// 换代注记（2026-10-23 spec-hygiene 批）：brand 前缀已随品牌换代 nebflow_ → stand_
//（branding.js key() = `${brand.lowerName}_<suffix>`；nebflow_ 降级为 legacy 兼容
// 命名空间，boot 时自动迁移）。门键字面量判据随迁：nebflow_slash.enabled →
// stand_slash.enabled（判据语义 = 「<当前品牌>_<suffix>」不变）。
const GATE_KEY = 'stand_slash.enabled';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
};

console.log(`WEB = ${WEB}`);
console.log(`input.js sha256 = ${createHash('sha256').update(readFileSync(join(WEB, 'js', 'input.js'))).digest('hex')}`);

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra !== '' ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();

/** boot — 真渲染页面（index.html + 真 CSS/JS 读盘）+ WS mock。theme = colorScheme。 */
async function boot({ theme = 'light', gate = false, width = 1440, height = 900 } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme: theme });
  await ctx.addInitScript(([gateKey, gateOn]) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    if (gateOn) localStorage.setItem(gateKey, '1');
    else localStorage.removeItem(gateKey);
  }, [GATE_KEY, gate]);
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  // catch-all 先注册 —— Playwright 逆序匹配（后注册者优先）
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => { try { frames.push(JSON.parse(raw)); } catch { /* non-JSON */ } });
    ws.send(JSON.stringify({
      type: 'configData',
      config: '{"features":{"friends":false}}',
      configured: true,
      onboarding: 'done',
      models: [],
      defaults: {},
    }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  });
  await page.goto('http://localhost:1/index.html');
  await page.waitForSelector('#input', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, pageErrors, frames };
}

/** 真键盘：填值（触发 input 事件 ⇒ updateSlashDropdown）后按一次 Enter。 */
async function typeAndEnter(page, text) {
  await page.click('#input');
  await page.fill('#input', text);
  await sleep(150);
  await page.keyboard.press('Enter');
  await sleep(400);
}

const dropItems = (page) => page.evaluate(() => ({
  on: document.getElementById('slash-dropdown').classList.contains('on'),
  names: Array.from(document.querySelectorAll('#slash-dropdown .slash-item .slash-cmd')).map((e) => e.textContent),
}));

const sameJson = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const frameOf = (frames, pred) => frames.find(pred);

// ════════════════════════════════════════════════════════════════════════
// S1 · 默认面命令面（列表 + 解析）—— /clear /compact 已摘除
// ════════════════════════════════════════════════════════════════════════
{
  const { ctx, page, pageErrors, frames } = await boot();
  await page.click('#input');
  await page.fill('#input', '/');
  await sleep(200);
  const dd = await dropItems(page);
  ok('S1a 默认面：键入 "/" ⇒ 下拉开且列表**恰为** ["/model"]（/clear /compact 不再列出）',
    dd.on === true && sameJson(dd.names, ['/model']), JSON.stringify(dd));

  const res = await page.evaluate(async () => {
    const m = await import('/js/input.js');
    const { key } = await import('/js/branding.js');
    return {
      gateKeyActual: key('slash.enabled'),
      clear: m.handleSlash('/clear'),
      compact: m.handleSlash('/compact'),
      model: m.handleSlash('/model'),
      ask: m.handleSlash('/ask'),
      onboarding: m.handleSlash('/onboarding'),
      fork: m.handleSlash('/fork'),
      plan: m.handleSlash('/plan'),
      skillish: m.handleSlash('/web-search 问题'),
      unknown: m.handleSlash('/nope'),
      bare: m.handleSlash('/'),
    };
  });
  ok('S1b 门键字面量核对：key("slash.enabled") === "stand_slash.enabled"（品牌前缀下划线形态）',
    res.gateKeyActual === GATE_KEY, res.gateKeyActual);
  ok('S1c 已摘除：handleSlash("/clear")/("/compact") 均 false（封存面纯文本路径）',
    res.clear === false && res.compact === false, JSON.stringify({ clear: res.clear, compact: res.compact }));
  ok('S1d /model 零回归 = true；白名单外 /ask /onboarding /fork /plan skill 命令 /未知 /裸"/" 全 false',
    res.model === true && res.ask === false && res.onboarding === false && res.fork === false
    && res.plan === false && res.skillish === false && res.unknown === false && res.bare === false, JSON.stringify(res));
  ok('S1e 摘除后零出站命令帧（/clear /compact 都派发不了 command 帧）',
    frames.filter((f) => f.type === 'command').length === 0, JSON.stringify(frames.filter((f) => f.type === 'command')));
  ok('S1f 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// S2 · `/clear` 纯文本路径（真键盘）—— 照常直发、零 command 帧
// ════════════════════════════════════════════════════════════════════════
{
  const { ctx, page, pageErrors, frames } = await boot();
  await page.click('#input');
  await page.fill('#input', '/clear');
  await sleep(200);
  const ddPre = await dropItems(page);
  await page.keyboard.press('Enter');
  await sleep(500);
  const after = await page.evaluate(async () => {
    const { t } = await import('/js/i18n.js');
    const rowTexts = Array.from(document.querySelectorAll('#chat .row')).map((e) => e.textContent);
    return { rowTexts, clearDone: t('slash.clearDone') };
  });
  const plain = frameOf(frames, (f) => typeof f.content === 'string' && f.content === '/clear');
  const cmdFrames = frames.filter((f) => f.type === 'command');
  console.log(`[S2] "/clear" 出站读数 = plain=${JSON.stringify(plain || null)} cmdFrames=${JSON.stringify(cmdFrames)}`);
  ok('S2a 键入 "/clear" ⇒ 下拉**不开**（无补全项：命令已摘除）',
    ddPre.on === false && ddPre.names.length === 0, JSON.stringify(ddPre));
  ok('S2b "/clear" 照常直发纯文本（content 帧、非命令路径）',
    !!plain, JSON.stringify(frames.filter((f) => typeof f.content === 'string')));
  ok('S2c 零 command 帧（引擎侧 clear 分支摘除后前端也无出帧口）',
    cmdFrames.length === 0, JSON.stringify(cmdFrames));
  ok('S2d 无「上下文已清除」系统气泡（slash.clearDone 文案零出现）',
    !after.rowTexts.some((x) => x.includes(after.clearDone)), JSON.stringify({ expect: after.clearDone, rowTexts: after.rowTexts }));
  ok('S2e 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// S3 · `/compact` 纯文本路径（真键盘）—— 照常直发、零 command 帧、pill 不出现
// ════════════════════════════════════════════════════════════════════════
{
  const { ctx, page, pageErrors, frames } = await boot();
  await page.click('#input');
  await page.fill('#input', '/compact');
  await sleep(200);
  const ddPre = await dropItems(page);
  await page.keyboard.press('Enter');
  await sleep(500);
  const stage = await page.evaluate(async () => {
    const { activeView } = await import('/js/chatView.js');
    return {
      compactMode: activeView.compactMode,
      pillShow: document.getElementById('compact-indicator').classList.contains('show'),
    };
  });
  const plain = frameOf(frames, (f) => typeof f.content === 'string' && f.content === '/compact');
  const cmdFrames = frames.filter((f) => f.type === 'command');
  console.log(`[S3] "/compact" 出站读数 = plain=${JSON.stringify(plain || null)} cmdFrames=${JSON.stringify(cmdFrames)}`);
  ok('S3a 键入 "/compact" ⇒ 下拉**不开**（无补全项：命令已摘除）',
    ddPre.on === false && ddPre.names.length === 0, JSON.stringify(ddPre));
  ok('S3b "/compact" 照常直发纯文本（content 帧、非命令路径）',
    !!plain, JSON.stringify(frames.filter((f) => typeof f.content === 'string')));
  ok('S3c 零 command 帧（二段式入口已摘除，instruction 形态不可达）',
    cmdFrames.length === 0, JSON.stringify(cmdFrames));
  ok('S3d 不进 COMPACT 交互态（compactMode=false + pill 无 .show）',
    stage.compactMode === false && stage.pillShow === false, JSON.stringify(stage));
  ok('S3e 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// S4 · 封存面零泄漏（反向读数）+ 阳性对照 —— 摘除不改封存面语义
// ════════════════════════════════════════════════════════════════════════
{
  // S4-PC 阳性对照：gate=1 时 /onboarding 可派发且**问候确实出现** ⇒ 证明检测器非空转
  const { ctx, page, pageErrors } = await boot({ gate: true });
  const pc = await page.evaluate(async () => {
    const m = await import('/js/input.js');
    const { t } = await import('/js/i18n.js');
    const handled = m.handleSlash('/onboarding');
    return { handled, greet1: t('ob2.greet1') }; // 真问候键（onboarding.sim.* 已退役）
  });
  let pcSeen = false;
  for (let i = 0; i < 20 && !pcSeen; i++) {
    await sleep(150);
    pcSeen = await page.evaluate((g) => document.body.textContent.includes(g), pc.greet1);
  }
  ok('S4-PC 阳性对照 gate=1：handleSlash("/onboarding")=true 且问候语真出现（检测器有效）',
    pc.handled === true && pcSeen === true, JSON.stringify({ ...pc, pcSeen }));
  ok('S4-PC 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}
{
  // S4 默认面：全部封存面命令 ⇒ 纯文本路径；引导问候零出现。
  // 每条一页（plain 发送会 arm busy，mock 无终止帧 ⇒ 同页后续发送进本地队列，
  // 会污染「照常直发」读数 —— 一页一条是最干净的读数口径）。
  const texts = ['/onboarding', '/fork', '/plan', '/web-search 你好', '/nope'];
  const perText = [];
  let greetSeen = false;
  let onboardingDom = 0;
  let cmdFrames = 0;
  let errs = [];
  for (const tx of texts) {
    const { ctx, page, pageErrors, frames } = await boot();
    await typeAndEnter(page, tx);
    const greets = await page.evaluate(async () => {
      const { t } = await import('/js/i18n.js');
      return [t('ob2.greet1'), t('ob2.greet2'), t('ob2.greet3')]; // 真问候键（onboarding.sim.* 已退役）
    });
    let seen = false;
    for (let i = 0; i < 14 && !seen; i++) { // 与 S4-PC 阳性对照同等待量级（≤2.1s）
      await sleep(150);
      seen = await page.evaluate((gs) => gs.some((g) => document.body.textContent.includes(g)), greets);
    }
    const dom = await page.evaluate(() => document.querySelectorAll('[class*="onboarding"]').length);
    perText.push({ tx, sentPlain: frames.some((f) => f.content === tx), cmds: frames.filter((f) => f.type === 'command').length, greet: seen });
    greetSeen = greetSeen || seen;
    onboardingDom += dom;
    cmdFrames += frames.filter((f) => f.type === 'command').length;
    errs = errs.concat(pageErrors);
    await ctx.close();
  }
  console.log(`[S4] 逐条读数 = ${JSON.stringify(perText)}`);
  ok('S4a 封存面命令逐条**照常直发纯文本**（未被吞）', perText.every((p) => p.sentPlain), JSON.stringify(perText));
  ok('S4b 引导问候零出现（三条 greet 文案均未入 DOM）+ onboarding DOM 零元素',
    greetSeen === false && onboardingDom === 0, JSON.stringify({ greetSeen, onboardingDom }));
  ok('S4c 零出站命令帧（封存面一条都派发不了）', cmdFrames === 0, String(cmdFrames));
  ok('S4d 零 page error', errs.length === 0, errs.join('; '));
}
{
  // S4e 非命令的 `/xxx` 回归：斜杠开头的**普通文本**不被吞（drop 下拉关闭 + 帧带 content）
  const { ctx, page, frames } = await boot();
  await typeAndEnter(page, '/Users/kaiyu/notes.md');
  const f = frameOf(frames, (x) => x.content === '/Users/kaiyu/notes.md');
  ok('S4e 非命令 "/Users/kaiyu/notes.md" 照常直发（下拉关、帧含同 content）',
    !!f && f.sessionId !== undefined, JSON.stringify(frames.filter((x) => x.content)));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// S5 · 总闸语义（回退路径完好）且全表**不含**已摘除命令
// ════════════════════════════════════════════════════════════════════════
{
  const { ctx, page, pageErrors } = await boot({ gate: true });
  await page.click('#input');
  await page.fill('#input', '/');
  await sleep(250);
  const dd = await dropItems(page);
  const res = await page.evaluate(async () => (await import('/js/input.js')).handleSlash('/ask'));
  ok('S5a gate=1 ⇒ 整张内置表照旧放行（列表含 /ask、/onboarding，条目 > 2）',
    dd.on === true && dd.names.length > 2 && dd.names.includes('/ask') && dd.names.includes('/onboarding'),
    JSON.stringify(dd));
  ok('S5b gate=1 ⇒ 已摘除命令不再回到表里（/clear /compact 均不列出）',
    !dd.names.includes('/clear') && !dd.names.includes('/compact'), JSON.stringify(dd));
  ok('S5c gate=1 ⇒ 白名单外命令可派发（/ask 返回 true）', res === true, String(res));
  ok('S5d 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL GREEN' : 'FAILURES'}: ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
