// slash-clear-compact.spec.mjs — `/clear` + `/compact` 回挂验收（D1-B 白名单形态）
//
// 上游真源：`.nebflow/reports/20260917_slashcmd-arch.md`（§6.3 改动面 / §6.4 红锚）
// + 作者四项裁定：D1=B（保门 + 最小白名单，只放行 `/clear` + `/compact`）/
//   D2=历史语义（清 LLM 机器记忆、**保留聊天记录**）/ D3=`/fork` 不回挂、`/plan` 不可回挂 /
//   D4=`/compact` 维持二段式（pill + 可选 instruction）。
//
// 断言面（**默认面** = 总闸 `nebflow_slash.enabled` 关、白名单 = {`/clear`,`/compact`}）：
//   S1 默认面命令面：键入 `/` 的列表**恰为**这两条；白名单外 `handleSlash` 全 false；
//      白名单内 true（解析面）
//   S2 `/clear` 真键盘路径 ⇒ 出站帧逐字 + 历史语义的 UI 反馈（`slash.clearDone` 运行时文案）
//   S3 `/compact` 二段式 ⇒ 第一段只进 COMPACT 态（**零帧**）、第二段出站帧逐字
//      （带 instruction / 空 input 两形态）
//   S4 封存面零泄漏（**反向读数**）：`/onboarding`（含引导问候）、`/fork`、`/plan`、
//      skill 命令、未知 `/xxx` 全部走纯文本路径；**阳性对照**证明「问候零出现」不是空转
//   S5 总闸语义未被白名单改坏：gate=1 时整张内置表照旧放行（回退路径完好）
//   S6 亮/暗双主题**真渲染**读数（下拉 + COMPACT pill）
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
const GATE_KEY = 'nebflow_slash.enabled';
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
// S1 · 默认面命令面（列表 + 解析）
// ════════════════════════════════════════════════════════════════════════
{
  const { ctx, page, pageErrors, frames } = await boot();
  await page.click('#input');
  await page.fill('#input', '/');
  await sleep(200);
  const dd = await dropItems(page);
  ok('S1a 默认面：键入 "/" ⇒ 下拉开且列表**恰为** ["/clear","/compact"]',
    dd.on === true && sameJson(dd.names, ['/clear', '/compact']), JSON.stringify(dd));

  const res = await page.evaluate(async () => {
    const m = await import('/js/input.js');
    const { key } = await import('/js/branding.js');
    return {
      gateKeyActual: key('slash.enabled'),
      clear: m.handleSlash('/clear'),
      compact: m.handleSlash('/compact'),
      ask: m.handleSlash('/ask'),
      onboarding: m.handleSlash('/onboarding'),
      fork: m.handleSlash('/fork'),
      plan: m.handleSlash('/plan'),
      skillish: m.handleSlash('/web-search 问题'),
      unknown: m.handleSlash('/nope'),
      bare: m.handleSlash('/'),
    };
  });
  ok('S1b 门键字面量核对：key("slash.enabled") === "nebflow_slash.enabled"（前缀下划线形态）',
    res.gateKeyActual === GATE_KEY, res.gateKeyActual);
  ok('S1c 白名单内：handleSlash("/clear")/("/compact") 均 true',
    res.clear === true && res.compact === true, JSON.stringify({ clear: res.clear, compact: res.compact }));
  ok('S1d 白名单外：/ask /onboarding /fork /plan skill 命令 /未知 /裸"/" 全 false',
    res.ask === false && res.onboarding === false && res.fork === false && res.plan === false
    && res.skillish === false && res.unknown === false && res.bare === false, JSON.stringify(res));

  const cmdFrames = frames.filter((f) => f.type === 'command');
  ok('S1e 解析面出帧读数：/clear 立即出 1 帧、/compact 出 0 帧（二段式：选中不执行）',
    cmdFrames.length === 1 && cmdFrames[0].command === 'clear', JSON.stringify(cmdFrames));
  ok('S1f 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// S2 · /clear 端到端（真键盘路径）—— 出站帧逐字
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
    return { value: /** @type {HTMLTextAreaElement} */ (document.getElementById('input')).value, rowTexts, expect: t('slash.clearDone') };
  });
  const sid = await page.evaluate(async () => (await import('/js/chatView.js')).activeView.sessionId);
  const clearFrame = frameOf(frames, (f) => f.type === 'command' && f.command === 'clear');
  console.log(`[S2] /clear 出站帧逐字 = ${JSON.stringify(clearFrame)}`);
  ok('S2a "/clear" 键入后下拉只列该条（用户可见面）',
    ddPre.on === true && sameJson(ddPre.names, ['/clear']), JSON.stringify(ddPre));
  ok('S2b /clear ⇒ 出站帧 {type:"command",command:"clear",sessionId}（三键、无多余键）',
    !!clearFrame && sameJson(Object.keys(clearFrame).sort(), ['command', 'sessionId', 'type']) && clearFrame.sessionId === sid,
    JSON.stringify({ clearFrame, sid }));
  ok('S2c /clear **不**同时外发纯文本（非纯文本路径）',
    frames.filter((f) => typeof f.content === 'string' && f.content.startsWith('/clear')).length === 0,
    JSON.stringify(frames.filter((f) => typeof f.content === 'string')));
  ok('S2d /clear 的 UI 反馈 = 运行时 t("slash.clearDone") 的系统气泡（历史语义：保留聊天记录）',
    after.rowTexts.some((x) => x.includes(after.expect)) && after.value === '',
    JSON.stringify({ expect: after.expect, rowTexts: after.rowTexts, value: after.value }));
  ok('S2e 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// S3 · /compact 二段式 —— 第一段零帧、第二段出站帧逐字（两形态）
// ════════════════════════════════════════════════════════════════════════
{
  const { ctx, page, pageErrors, frames } = await boot();
  await page.click('#input');
  await page.fill('#input', '/compact');
  await sleep(200);
  const ddPre = await dropItems(page);
  await page.keyboard.press('Enter');
  await sleep(400);
  const stage1 = await page.evaluate(async () => {
    const { activeView } = await import('/js/chatView.js');
    const pill = document.getElementById('compact-indicator');
    return {
      compactMode: activeView.compactMode,
      pillShow: pill.classList.contains('show'),
      pillDisplay: getComputedStyle(pill).display,
      placeholder: /** @type {HTMLTextAreaElement} */ (document.getElementById('input')).placeholder,
    };
  });
  ok('S3a "/compact" 键入后下拉只列该条',
    ddPre.on === true && sameJson(ddPre.names, ['/compact']), JSON.stringify(ddPre));
  ok('S3b 第一段（选中即入 COMPACT 交互态）：compactMode=true + pill .show + display:flex',
    stage1.compactMode === true && stage1.pillShow === true && stage1.pillDisplay === 'flex', JSON.stringify(stage1));
  ok('S3c 第一段**零出站帧**（二段式：选中不执行）',
    frames.filter((f) => f.type === 'command').length === 0, JSON.stringify(frames));

  // 第二段：带 instruction
  await page.click('#input');
  await page.fill('#input', '只保留结论');
  await sleep(150);
  await page.keyboard.press('Enter');
  await sleep(500);
  const c1 = frameOf(frames, (f) => f.type === 'command' && f.command === 'compact');
  const sid = await page.evaluate(async () => (await import('/js/chatView.js')).activeView.sessionId);
  console.log(`[S3] /compact 出站帧逐字（带 instruction）= ${JSON.stringify(c1)}`);
  ok('S3d 第二段 ⇒ {type:"command",command:"compact",sessionId,instruction}（四键、逐字）',
    !!c1 && sameJson(Object.keys(c1).sort(), ['command', 'instruction', 'sessionId', 'type'])
    && c1.instruction === '只保留结论' && c1.sessionId === sid, JSON.stringify({ c1, sid }));
  const after2 = await page.evaluate(async () => ({
    value: /** @type {HTMLTextAreaElement} */ (document.getElementById('input')).value,
    compactMode: (await import('/js/chatView.js')).activeView.compactMode,
    pillShow: document.getElementById('compact-indicator').classList.contains('show'),
  }));
  ok('S3e 第二段发完：输入清空 + 退出 COMPACT 态（cancelCompactMode 在发帧前 + pill 收）',
    after2.value === '' && after2.compactMode === false && after2.pillShow === false, JSON.stringify(after2));
  ok('S3f 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}
{
  // 空 input 形态：instruction 键**不出现**（`text || undefined` 经 JSON 序列化后丢弃）
  const { ctx, page, frames } = await boot();
  await page.click('#input');
  await page.fill('#input', '/compact');
  await sleep(200);
  await page.keyboard.press('Enter');
  await sleep(350);
  await page.click('#input');
  await page.keyboard.press('Enter');
  await sleep(500);
  const c0 = frameOf(frames, (f) => f.type === 'command' && f.command === 'compact');
  console.log(`[S3] /compact 出站帧逐字（空 input）= ${JSON.stringify(c0)}`);
  ok('S3g 空 input 形态 ⇒ 帧为 {type,command,sessionId}（无 instruction 键）',
    !!c0 && sameJson(Object.keys(c0).sort(), ['command', 'sessionId', 'type']), JSON.stringify(c0));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// S4 · 封存面零泄漏（反向读数）+ 阳性对照
// ════════════════════════════════════════════════════════════════════════
{
  // S4-PC 阳性对照：gate=1 时 /onboarding 可派发且**问候确实出现** ⇒ 证明检测器非空转
  const { ctx, page, pageErrors } = await boot({ gate: true });
  const pc = await page.evaluate(async () => {
    const m = await import('/js/input.js');
    const { t } = await import('/js/i18n.js');
    const handled = m.handleSlash('/onboarding');
    return { handled, greet1: t('onboarding.sim.greet1') };
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
  // S4 默认面：全部白名单外命令 ⇒ 纯文本路径；引导问候零出现。
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
      return [t('onboarding.sim.greet1'), t('onboarding.sim.greet2'), t('onboarding.sim.greet3')];
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
  ok('S4a 白名单外命令逐条**照常直发纯文本**（未被吞）', perText.every((p) => p.sentPlain), JSON.stringify(perText));
  ok('S4b 引导问候零出现（三条 greet 文案均未入 DOM）+ onboarding DOM 零元素',
    greetSeen === false && onboardingDom === 0, JSON.stringify({ greetSeen, onboardingDom }));
  ok('S4c 零出站命令帧（白名单外一条都派发不了）', cmdFrames === 0, String(cmdFrames));
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
// S5 · 总闸语义未被白名单改坏（回退路径完好）
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
  ok('S5b gate=1 ⇒ 白名单外命令可派发（/ask 返回 true）', res === true, String(res));
  ok('S5c 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// S6 · 亮/暗双主题真渲染读数
// ════════════════════════════════════════════════════════════════════════
{
  const reads = {};
  for (const theme of ['light', 'dark']) {
    const { ctx, page, pageErrors } = await boot({ theme });
    const padClosed = await page.evaluate(() => /** @type {HTMLElement} */ (document.getElementById('input')).style.paddingLeft);
    await page.click('#input');
    await page.fill('#input', '/');
    await sleep(250);
    const dd = await page.evaluate(() => {
      const el = document.getElementById('slash-dropdown');
      const cs = getComputedStyle(el);
      const cmd = document.querySelector('#slash-dropdown .slash-cmd');
      const r = el.getBoundingClientRect();
      return {
        display: cs.display, bg: cs.backgroundColor, blur: cs.backdropFilter || cs.webkitBackdropFilter,
        cmdColor: cmd ? getComputedStyle(cmd).color : null,
        items: document.querySelectorAll('#slash-dropdown .slash-item').length,
        inViewport: r.width > 0 && r.height > 0 && r.left >= 0 && r.right <= window.innerWidth && r.bottom <= window.innerHeight,
      };
    });
    await page.click('#input');
    await page.fill('#input', '/compact');
    await sleep(200);
    await page.keyboard.press('Enter');
    await sleep(350);
    const pill = await page.evaluate(() => {
      const el = document.getElementById('compact-indicator');
      const cs = getComputedStyle(el);
      return { show: el.classList.contains('show'), display: cs.display, color: cs.color, padOpen: /** @type {HTMLElement} */ (document.getElementById('input')).style.paddingLeft };
    });
    reads[theme] = { dd, pill, padClosed, pageErrors };
    await ctx.close();
  }
  console.log(`[S6] light = ${JSON.stringify({ dd: reads.light.dd, pill: reads.light.pill, padClosed: reads.light.padClosed })}`);
  console.log(`[S6] dark  = ${JSON.stringify({ dd: reads.dark.dd, pill: reads.dark.pill, padClosed: reads.dark.padClosed })}`);
  for (const t of ['light', 'dark']) {
    const r = reads[t];
    ok(`S6a [${t}] 下拉真渲染：display=block + 2 条 + 真毛玻璃 + 视口内`,
      r.dd.display === 'block' && r.dd.items === 2 && r.dd.blur.includes('blur') && r.dd.inViewport
      && r.dd.bg !== 'rgba(0, 0, 0, 0)' && r.dd.bg !== 'transparent', JSON.stringify(r.dd));
    ok(`S6b [${t}] COMPACT pill 真渲染：display=flex + 输入框让位（增量口径：开 > 关）`,
      r.pill.display === 'flex' && parseFloat(r.pill.padOpen || '0') > parseFloat(r.padClosed || '0'), JSON.stringify({ pill: r.pill, padClosed: r.padClosed }));
    ok(`S6c [${t}] 零 page error`, r.pageErrors.length === 0, r.pageErrors.join('; '));
  }
  ok('S6d 双主题由 token 驱动：亮/暗两档下拉底与 pill 字色**各不相同**',
    reads.light.dd.bg !== reads.dark.dd.bg && reads.light.pill.color !== reads.dark.pill.color,
    JSON.stringify({ bg: [reads.light.dd.bg, reads.dark.dd.bg], pillColor: [reads.light.pill.color, reads.dark.pill.color] }));
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL GREEN' : 'FAILURES'}: ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
